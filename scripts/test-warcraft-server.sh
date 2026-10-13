#!/usr/bin/env bash
# Tests the World of Warcraft app's server package (warcraft/android/server/server.tar.xz) the way
# the phone runs it: unpacked into /data/data/com.logie.warcraft/files, driven by its ctl.sh, on a
# Linux machine of the same architecture that has no musl of its own (so the package has to bring
# everything it needs).
#
#   1. MariaDB starts from the seed data folder.
#   2. dbimport creates and fills the auth, characters and world databases.
#   3. The auth server starts; an account made the way the app makes them logs in and gets the
#      realm list, with the realm's address.
#   4. The world server, on jemalloc, loads its configs and modules and updates the databases
#      (the first ChromieCraft progression brackets, the playerbots module's own database).
#      With the server's data (WOW_DATA_ZIP: the data.zip the app downloads, see
#      warcraft/server-data.env), it starts all the way, a character is created and enters the
#      world, a GM command answers, and the server shuts down the way the app stops it.
#      Without it, it stops where it needs the data (dbc).
#      The app's add-on message (where the spellbook is) is kept for the app, and cleared at the
#      logout.
#   5. With the custom races' server package (WOW_RACES_ZIP: WarcraftRaces-server.zip, see
#      scripts/build-races-package.sh), the races are turned on the way the app does it: a Worgen
#      and a Goblin are created and enter the world. Then off: the world database is made again,
#      the world server starts, and the two are left out of the character list but kept.
#   6. The auth server starts again the way the app starts it, and the account logs in.
#   7. With strace, no server program made a system call Android's seccomp policy forbids apps.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILES=/data/data/com.logie.warcraft/files
PKG="$ROOT/warcraft/android/server/server.tar.xz"
SUDO=""
[ "$(id -u)" = 0 ] || SUDO=sudo

# Server programs left from an earlier run would hold the ports.
$SUDO pkill -f "$FILES/server/bin/" 2> /dev/null && sleep 5 || true
$SUDO rm -rf "$FILES"
$SUDO mkdir -p "$FILES"
$SUDO chown "$(id -u):$(id -g)" "$FILES"
tar -C "$FILES" -xJf "$PKG"
cat "$FILES/server/VERSION"
export FILES
DATA_ZIP="${WOW_DATA_ZIP:-}"
if [ -n "$DATA_ZIP" ]; then
	echo "== server data"
	unzip -q "$DATA_ZIP" -d "$FILES/data"
	du -sh "$FILES"/data/*
fi

# Android kills an app's process that makes a system call its seccomp policy doesn't allow (the
# server runs as the app). With strace, every server program here runs under a filter that stops
# only on such calls, and the test fails if one is made.
TRACE=()
if command -v strace > /dev/null; then
	filter=$(python3 -I "$ROOT/warcraft/server/seccomp_check.py" filter)
	TRACE=(strace -f --seccomp-bpf -qq -e signal=none -e "trace=$filter")
	mkdir -p "$FILES/logs/syscalls"
else
	echo "No strace: the system calls aren't checked against Android's policy."
fi
CTL() {
	if [ ${#TRACE[@]} -gt 0 ]; then
		"${TRACE[@]}" -o "$FILES/logs/syscalls/$1.$BASHPID.$RANDOM" sh "$FILES/server/ctl.sh" "$@"
	else
		sh "$FILES/server/ctl.sh" "$@"
	fi
}

pids=()
cleanup() {
	for pid in "${pids[@]}"; do kill "$pid" 2> /dev/null || true; done
	pkill -f "$FILES/server/bin/" 2> /dev/null || true
	wait 2> /dev/null || true
}
trap cleanup EXIT
fail() {
	echo "FAILED: $*" >&2
	for log in "$FILES"/logs/*; do
		[ -f "$log" ] || continue
		echo "--- $log" >&2
		tail -40 "$log" >&2
	done
	exit 1
}
wait_for() { # seconds, description, command...
	local seconds=$1 what=$2
	shift 2
	for _ in $(seq "$seconds"); do
		"$@" > /dev/null 2>&1 && return 0
		sleep 1
	done
	fail "$what"
}

echo "== configure"
CTL configure
ls "$FILES/etc" "$FILES/etc/modules"
# The app's first-run settings (Server.applySettings): the first ChromieCraft brackets, and 50
# random bots that only play while someone's logged in.
sed -i 's/^ProgressionSystem.Bracket_0 .*/ProgressionSystem.Bracket_0 = 1/; s/^ProgressionSystem.Bracket_1_19 .*/ProgressionSystem.Bracket_1_19 = 1/' \
	"$FILES/etc/modules/progression_system.conf"
sed -i 's/^Playerbots.MinRandomBots .*/Playerbots.MinRandomBots = 50/; s/^Playerbots.MaxRandomBots .*/Playerbots.MaxRandomBots = 50/; s/^Playerbots.DisabledWithoutRealPlayer .*/Playerbots.DisabledWithoutRealPlayer = 1/' \
	"$FILES/etc/modules/playerbots.conf"
grep -E "^Playerbots.(MinRandomBots|MaxRandomBots|DisabledWithoutRealPlayer) " "$FILES/etc/modules/playerbots.conf"

echo "== MariaDB"
CTL db & pids+=($!)
wait_for 60 "MariaDB didn't start" CTL db-ping
echo "MariaDB is up"

echo "== dbimport"
start=$(date +%s)
CTL dbimport > "$FILES/logs/dbimport.out" 2>&1 || fail "dbimport exited with $?"
echo "dbimport took $(($(date +%s) - start)) s"
# (acore_playerbots is filled by the playerbots module when the world server starts: checked there.)
for db in acore_auth acore_characters acore_world; do
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$db'" | CTL sql)
	echo "$db: $tables tables"
	[ "$tables" -gt 0 ] || fail "$db has no tables"
done
creatures=$(echo "SELECT COUNT(*) FROM creature_template" | CTL sql acore_world)
echo "creature_template: $creatures rows"
[ "$creatures" -gt 20000 ] || fail "the world database looks empty"

echo "== auth server"
python3 "$ROOT/warcraft/server/auth_check.py" account-sql tester secret123 | CTL sql acore_auth
echo "UPDATE realmlist SET address = '192.168.1.50', localAddress = '127.0.0.1' WHERE id = 1" | CTL sql acore_auth
CTL auth > "$FILES/logs/auth.out" 2>&1 & pids+=($!)
port_open() { (echo > "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }
wait_for 60 "the auth server didn't open port 3724" port_open 3724
realms=$(python3 "$ROOT/warcraft/server/auth_check.py" login 127.0.0.1 tester secret123) ||
	fail "the test account couldn't log in"
echo "$realms"
echo "$realms" | grep -q "127.0.0.1:8085" || fail "the realm list doesn't point the phone's own client at 127.0.0.1"
if python3 "$ROOT/warcraft/server/auth_check.py" login 127.0.0.1 tester wrongpass > /dev/null 2>&1; then
	fail "a wrong password logged in"
fi

echo "== world server"
start_world() { # log name
	world_log="$FILES/logs/$1"
	rm -f "$FILES/run/world.in"
	mkfifo "$FILES/run/world.in"
	CTL world < "$FILES/run/world.in" > "$world_log" 2>&1 & pids+=($!)
	world=$!
	# Its console: the app stops it with a command there.
	exec 3> "$FILES/run/world.in"
}
# Up to "ready", as the app waits for it (Server.READY).
wait_world_ready() {
	local start ready=""
	start=$(date +%s)
	for _ in $(seq 1800); do
		kill -0 "$world" 2> /dev/null || break
		[ -n "$jemalloc" ] || ! on_jemalloc || jemalloc=1
		if grep -q "(worldserver-daemon) ready" "$world_log"; then ready=1; break; fi
		sleep 1
	done
	[ -n "$ready" ] || fail "the world server didn't start"
	echo "The world server was ready in $(($(date +%s) - start)) s."
}
stop_world() {
	echo "server shutdown 1" >&3
	for _ in $(seq 90); do kill -0 "$world" 2> /dev/null || break; sleep 1; done
	if kill -0 "$world" 2> /dev/null; then fail "the world server didn't shut down"; fi
	grep -q "Halting process" "$world_log" || fail "the world server didn't shut down cleanly"
	exec 3>&-
	tail -3 "$world_log"
	echo "The world server shut down."
}
start_world world.out
jemalloc=""
on_jemalloc() {
	local pid
	pid=$(cat "$FILES/run/worldserver.pid" 2> /dev/null || true)
	[ -n "$pid" ] && grep -q libjemalloc "/proc/$pid/maps" 2> /dev/null
}
check_modules() {
	[ -n "$jemalloc" ] || fail "the world server didn't run on jemalloc"
	echo "The world server ran on jemalloc."
	grep -q "Loading mod-progression-system updates" "$FILES/logs/world.out" || fail "the world server didn't load its modules"
	grep -qi "playerbots" "$FILES/logs/world.out" || fail "the world server doesn't mention playerbots"
	progression=$(echo "SELECT COUNT(*) FROM updates WHERE name LIKE '%progression%'" | CTL sql acore_world)
	echo "progression updates applied: $progression"
	[ "$progression" -gt 0 ] || fail "the progression brackets' SQL wasn't applied"
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'acore_playerbots'" | CTL sql)
	echo "acore_playerbots: $tables tables (made by the playerbots module)"
	[ "$tables" -gt 0 ] || fail "the playerbots module didn't set up its database"
}

if [ -n "$DATA_ZIP" ]; then
	wait_world_ready
	grep -E "^(Loading|>>) .*(DBC|Maps|MMap|VMap|Creature Data|GameObject Data|Quest)" "$FILES/logs/world.out" | head -12 || true
	check_modules

	echo "== a character in the world"
	python3 "$ROOT/warcraft/server/auth_check.py" world 127.0.0.1 tester secret123 Testhero ".server info" |
		tee "$FILES/logs/world-client.out" || fail "the character couldn't get into the world"
	grep -q "in the world on map" "$FILES/logs/world-client.out" || fail "the character didn't enter the world"
	grep -qi "players online\|uptime" "$FILES/logs/world-client.out" || fail "the GM command didn't answer"
	characters=$(echo "SELECT COUNT(*) FROM characters" | CTL sql acore_characters)
	echo "characters: $characters (the test's, and the random bots')"
	# Data made for another version of the core (vmaps, mmaps) is refused when a map loads.
	if grep -iE "mmtile.*(generator|version|navmesh)|vmap.*(version|magic)" "$FILES/logs/world.out" | head -5 | grep .; then
		fail "the server data doesn't match this server"
	fi

	echo "== the app's add-on: where the spellbook is"
	python3 "$ROOT/warcraft/server/auth_check.py" world-note 127.0.0.1 tester secret123 Testhero "$FILES/run/interface" |
		tee "$FILES/logs/world-note.out" || fail "the realm didn't keep the add-on's note for the app"
	[ "$(cat "$FILES/run/interface")" = "-" ] || fail "the add-on's note stayed after the logout"
	echo "The realm kept the note, and cleared it at the logout."

	echo "== world server shutdown, the way the app stops it"
	stop_world

	if [ -n "${WOW_RACES_ZIP:-}" ]; then
		echo "== custom races on"
		# What the app does: the package's files in files/races, the switch's mark, then ctl.sh
		# races before the world server starts.
		mkdir -p "$FILES/races"
		unzip -q -o "$WOW_RACES_ZIP" -d "$FILES/races"
		touch "$FILES/races/on"
		cat "$FILES/races/races.txt"
		start=$(date +%s)
		CTL races > "$FILES/logs/races-on.out" 2>&1 || fail "turning the races on failed: $(tail -5 "$FILES/logs/races-on.out")"
		cat "$FILES/logs/races-on.out"
		echo "took $(($(date +%s) - start)) s"
		for race in 9 12; do
			classes=$(echo "SELECT COUNT(*) FROM playercreateinfo WHERE race = $race" | CTL sql acore_world)
			echo "race $race: $classes classes"
			[ "$classes" -gt 0 ] || fail "the world database has no race $race"
		done
		grep -q "data-races" "$FILES/etc/worldserver.conf" || fail "the world server doesn't read the races' tables"
		# The random bots' characters go, so the world server makes them again with the races on.
		echo "DELETE c FROM acore_characters.characters c JOIN acore_auth.account a ON a.id = c.account WHERE a.username LIKE 'RNDBOT%'; DELETE FROM acore_playerbots.playerbots_random_bots;" |
			CTL sql
		start_world world-races.out
		wait_world_ready
		python3 "$ROOT/warcraft/server/auth_check.py" world-as 127.0.0.1 tester secret123 Testwolf 12 1 |
			tee "$FILES/logs/world-worgen.out" || fail "a Worgen couldn't get into the world"
		grep -q "in the world on map" "$FILES/logs/world-worgen.out" || fail "the Worgen didn't enter the world"
		python3 "$ROOT/warcraft/server/auth_check.py" world-as 127.0.0.1 tester secret123 Testgoblin 9 1 |
			tee "$FILES/logs/world-goblin.out" || fail "a Goblin couldn't get into the world"
		grep -q "in the world on map" "$FILES/logs/world-goblin.out" || fail "the Goblin didn't enter the world"
		bots=$(echo "SELECT COUNT(*) FROM acore_characters.characters c JOIN acore_auth.account a ON a.id = c.account WHERE a.username LIKE 'RNDBOT%'" | CTL sql)
		custom=$(echo "SELECT COUNT(*) FROM acore_characters.characters c JOIN acore_auth.account a ON a.id = c.account WHERE a.username LIKE 'RNDBOT%' AND c.race NOT IN (1, 2, 3, 4, 5, 6, 7, 8, 10, 11)" | CTL sql)
		echo "random bots made with the races on: $bots, of the custom races: $custom"
		[ "$bots" -gt 0 ] || fail "the random bots weren't made again"
		[ "$custom" = 0 ] || fail "random bots took custom races"
		stop_world

		# A record of an achievement only the races have, which has to come through.
		achievement=$(sed -n 's/^achievements \([0-9]*\).*/\1/p' "$FILES/races/achievements.txt")
		echo "INSERT INTO character_achievement (guid, achievement, date) SELECT guid, $achievement, UNIX_TIMESTAMP() FROM characters WHERE name = 'Testwolf'" |
			CTL sql acore_characters

		echo "== custom races off"
		rm "$FILES/races/on"
		start=$(date +%s)
		CTL races > "$FILES/logs/races-off.out" 2>&1 || fail "turning the races off failed: $(tail -5 "$FILES/logs/races-off.out")"
		tail -3 "$FILES/logs/races-off.out"
		echo "took $(($(date +%s) - start)) s"
		classes=$(echo "SELECT COUNT(*) FROM playercreateinfo WHERE race IN (9, 12)" | CTL sql acore_world)
		[ "$classes" = 0 ] || fail "the world database still has the races"
		grep -q "data-races" "$FILES/etc/worldserver.conf" && fail "the world server still reads the races' tables"
		start_world world-norace.out
		wait_world_ready
		python3 "$ROOT/warcraft/server/auth_check.py" characters 127.0.0.1 tester secret123 |
			tee "$FILES/logs/characters-norace.out" || fail "the character list didn't come"
		grep -q "^Testhero" "$FILES/logs/characters-norace.out" || fail "the human left the list"
		if grep -qE "^(Testwolf|Testgoblin)" "$FILES/logs/characters-norace.out"; then
			fail "characters of the custom races are in the list with the races off"
		fi
		kept=$(echo "SELECT COUNT(*) FROM characters WHERE name IN ('Testwolf', 'Testgoblin')" | CTL sql acore_characters)
		echo "characters of the custom races kept: $kept"
		[ "$kept" = 2 ] || fail "characters of the custom races were deleted"
		kept=$(echo "SELECT COUNT(*) FROM app_races_achievement WHERE achievement = $achievement" | CTL sql acore_characters)
		echo "the races' achievements kept aside: $kept"
		[ "$kept" = 1 ] || fail "the races' achievement wasn't kept aside"
		python3 "$ROOT/warcraft/server/auth_check.py" world 127.0.0.1 tester secret123 Testhero |
			grep -q "in the world on map" || fail "the human couldn't get into the world with the races off"
		stop_world
	fi
else
	# Without the server's data, it runs until it needs dbc, then stops.
	for _ in $(seq 300); do
		kill -0 "$world" 2> /dev/null || break
		[ -n "$jemalloc" ] || ! on_jemalloc || jemalloc=1
		sleep 1
	done
	exec 3>&-
	check_modules
	grep -qi "dbc" "$FILES/logs/world.out" || fail "the world server stopped before reaching the client data"
	tail -5 "$FILES/logs/world.out"
	echo "The world server got as far as the client data (dbc), which this run doesn't have."
fi

echo "== auth server, started again after the world server"
# A world server that stopped while loading leaves the realm flagged; the app clears the flag
# before starting the auth server, which otherwise refuses to run.
kill "$(cat "$FILES/run/authserver.pid")" 2> /dev/null || true
sleep 3
echo "UPDATE realmlist SET flag = flag & ~1 WHERE id = 1" | CTL sql acore_auth
CTL auth > "$FILES/logs/auth2.out" 2>&1 & pids+=($!)
wait_for 60 "the auth server didn't start again" port_open 3724
python3 "$ROOT/warcraft/server/auth_check.py" login 127.0.0.1 tester secret123 ||
	fail "the test account couldn't log in after the restart"

CTL db-stop
wait "${pids[0]}" 2> /dev/null || true

if [ ${#TRACE[@]} -gt 0 ]; then
	echo "== system calls"
	if [ "$(uname -m)" = aarch64 ]; then
		python3 -I "$ROOT/warcraft/server/seccomp_check.py" check "$FILES/server/" "$FILES"/logs/syscalls/* ||
			fail "the server made system calls Android doesn't allow apps"
	else
		# Elsewhere musl makes the older calls the phone's arm64 doesn't have (open, poll, ...),
		# which Android's policy for that architecture leaves out: listed, not counted.
		python3 -I "$ROOT/warcraft/server/seccomp_check.py" check "$FILES/server/" "$FILES"/logs/syscalls/* ||
			echo "(Not arm64: only CI's arm64 run counts.)"
	fi
fi
echo "PASSED"
