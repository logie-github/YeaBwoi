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
#   5. What earlier versions' custom races left (their SQL in the world database, their files, a
#      character of one of them) is taken out the way the realm's start does it (ctl.sh races):
#      the world database is made again, the files go, the world server starts, and the
#      character is left out of the character list but kept. A module that was in the realm and
#      isn't now (ctl.sh modules) takes what it put in the world database with it, and the
#      realm's modules put theirs in again.
#   6. Wrath Remix, the second realm: its databases are made, its world server starts with the
#      mod-wrath-remix module on (the ChromieCraft realm's said it was off), its SQL is in its
#      databases and not in ChromieCraft's, both realms are in the realm list, and a new
#      character there is a level 10 Timerunner in the Caverns of Time who plays the prologue
#      (warcraft/server/remix_prologue.steps) to the first chapter, with Northrend's creatures at
#      its levels, and has its class's starter kit on, its abilities learned and its weapon skill
#      at its level's maximum; every NPC Remix placed stands on the floor (remix_check.py floors).
#   7. The auth server starts again the way the app starts it, and the account logs in.
#   8. With strace, no server program made a system call Android's seccomp policy forbids apps.
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

echo "== the realm's modules on record for the world database (ctl.sh modules, as each start runs it)"
CTL modules > "$FILES/logs/modules.out" 2>&1 || fail "ctl.sh modules failed: $(tail -5 "$FILES/logs/modules.out")"
cat "$FILES/logs/modules.out"
grep -qxF mod-zone-difficulty "$FILES/mysql/.world-modules" || fail "the realm's modules aren't on record"
if grep -q "Taking out" "$FILES/logs/modules.out"; then fail "the first start made the world database again"; fi

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
start_world() { # log name [realm]
	world_log="$FILES/logs/$1"
	rm -f "$FILES/run/world.in"
	mkfifo "$FILES/run/world.in"
	CTL world ${2:-} < "$FILES/run/world.in" > "$world_log" 2>&1 & pids+=($!)
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

	echo "== what earlier versions' custom races left, taken out"
	# A phone that had them on: their SQL in the world database (the table app_races says so),
	# their files, and a character of one of them (a Worgen, race 12: a copy of the human).
	echo "CREATE TABLE app_races (version VARCHAR(255) NOT NULL); INSERT INTO app_races VALUES ('races 52fd006');" |
		CTL sql acore_world
	echo "CREATE TEMPORARY TABLE wolf AS SELECT * FROM characters WHERE name = 'Testhero'; UPDATE wolf SET guid = (SELECT MAX(guid) + 1 FROM characters), name = 'Testwolf', race = 12; INSERT INTO characters SELECT * FROM wolf;" |
		CTL sql acore_characters
	mkdir -p "$FILES/races/dbc" "$FILES/data-races/dbc"
	touch "$FILES/races/on" "$FILES/races/world.sql"
	start=$(date +%s)
	CTL races > "$FILES/logs/races.out" 2>&1 || fail "taking the races out failed: $(tail -5 "$FILES/logs/races.out")"
	tail -3 "$FILES/logs/races.out"
	echo "took $(($(date +%s) - start)) s"
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'acore_world' AND table_name = 'app_races'" | CTL sql)
	[ "$tables" = 0 ] || fail "the world database still has the races"
	if [ -e "$FILES/races" ] || [ -e "$FILES/data-races" ]; then fail "the races' files are still there"; fi
	if grep -q "data-races" "$FILES/etc/worldserver.conf"; then fail "the world server reads the races' tables"; fi

	echo "== a module taken out of the realm: what it put in the world database goes"
	# The realm swapped for one without a module (mod-gone) that had put a table in the world.
	echo "mod-gone" >> "$FILES/mysql/.world-modules"
	echo "CREATE TABLE mod_gone (id INT NOT NULL)" | CTL sql acore_world
	start=$(date +%s)
	CTL modules > "$FILES/logs/modules-gone.out" 2>&1 || fail "taking the module out failed: $(tail -5 "$FILES/logs/modules-gone.out")"
	tail -3 "$FILES/logs/modules-gone.out"
	echo "took $(($(date +%s) - start)) s"
	grep -q "Taking out of the world: mod-gone" "$FILES/logs/modules-gone.out" || fail "the module that went wasn't seen"
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'acore_world' AND table_name = 'mod_gone'" | CTL sql)
	[ "$tables" = 0 ] || fail "the world database still has what the module put in"
	if grep -qxF mod-gone "$FILES/mysql/.world-modules"; then fail "the module that went is still on record"; fi
	CTL modules > "$FILES/logs/modules-again.out" 2>&1 || fail "ctl.sh modules failed the second time"
	if grep -q "Taking out" "$FILES/logs/modules-again.out"; then fail "the world database was made again for nothing"; fi

	start_world world-noraces.out
	wait_world_ready
	applied=$(echo "SELECT COUNT(*) FROM updates WHERE state = 'MODULE'" | CTL sql acore_world)
	echo "the realm's module updates in the new world database: $applied"
	[ "$applied" -gt 0 ] || fail "the realm's modules didn't put their SQL in again"
	python3 "$ROOT/warcraft/server/auth_check.py" characters 127.0.0.1 tester secret123 |
		tee "$FILES/logs/characters-noraces.out" || fail "the character list didn't come"
	grep -q "^Testhero" "$FILES/logs/characters-noraces.out" || fail "the human left the list"
	if grep -q "^Testwolf" "$FILES/logs/characters-noraces.out"; then fail "the Worgen is in the character list"; fi
	kept=$(echo "SELECT COUNT(*) FROM characters WHERE name = 'Testwolf'" | CTL sql acore_characters)
	echo "characters of the custom races kept: $kept"
	[ "$kept" = 1 ] || fail "the Worgen was deleted"
	python3 "$ROOT/warcraft/server/auth_check.py" world 127.0.0.1 tester secret123 Testhero ".remix" > "$FILES/logs/world-remix-off.out" ||
		fail "the human couldn't get into the world after the races went"
	cat "$FILES/logs/world-remix-off.out"
	grep -q "in the world on map" "$FILES/logs/world-remix-off.out" || fail "the human couldn't get into the world after the races went"
	grep -q "Wrath Remix: off on this realm" "$FILES/logs/world-remix-off.out" || fail "Wrath Remix answered on the ChromieCraft realm"
	stop_world
	grep -q "Wrath Remix: off" "$FILES/logs/world.out" || fail "the ChromieCraft realm's world server didn't say Wrath Remix is off"

	echo "== Wrath Remix: the second realm"
	start=$(date +%s)
	CTL dbimport remix > "$FILES/logs/dbimport-remix.out" 2>&1 || fail "the Remix databases weren't made: $(tail -5 "$FILES/logs/dbimport-remix.out")"
	echo "the Remix databases took $(($(date +%s) - start)) s"
	for db in acore_world_remix acore_characters_remix acore_playerbots_remix; do
		tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$db'" | CTL sql)
		echo "$db: $tables tables"
	done
	[ "$(echo "SELECT COUNT(*) FROM creature_template" | CTL sql acore_world_remix)" -gt 20000 ] || fail "the Remix world database looks empty"
	[ "$(echo "SELECT COUNT(*) FROM updates_include WHERE path LIKE '%mod-wrath-remix%'" | CTL sql acore_world_remix)" = 1 ] ||
		fail "the Remix world database doesn't list Wrath Remix's SQL"
	[ "$(echo "SELECT COUNT(*) FROM updates_include WHERE path LIKE '%mod-wrath-remix%'" | CTL sql acore_world)" = 0 ] ||
		fail "the ChromieCraft world database lists Wrath Remix's SQL"
	CTL modules remix > "$FILES/logs/modules-remix.out" 2>&1 || fail "ctl.sh modules remix failed"
	[ -f "$FILES/mysql/.world-modules-remix" ] || fail "the Remix realm's modules aren't on record"
	grep -q "WrathRemix.Enable = 1" "$FILES/etc-remix/modules/mod_wrath_remix.conf" || fail "Wrath Remix isn't on in its realm's config"
	grep -q "WrathRemix.Enable = 0" "$FILES/etc/modules/mod_wrath_remix.conf" || fail "Wrath Remix isn't off in ChromieCraft's config"
	grep -q "^RealmID = 2" "$FILES/etc-remix/worldserver.conf" || fail "the Remix world server isn't realm 2"
	grep -q "^StartPlayerLevel = 10" "$FILES/etc-remix/worldserver.conf" || fail "Timerunners don't start at 10"

	# Both realms in the realm list (the auth server reads it again every 20 s or so).
	CTL realmlist 192.168.1.50 || fail "ctl.sh realmlist failed"
	for _ in $(seq 40); do
		realms=$(python3 "$ROOT/warcraft/server/auth_check.py" login 127.0.0.1 tester secret123 || true)
		echo "$realms" | grep -q "Wrath Remix" && break
		sleep 2
	done
	echo "$realms"
	echo "$realms" | grep -q "Wrath Remix	127.0.0.1:8086" || fail "the realm list doesn't have Wrath Remix at 127.0.0.1:8086"
	echo "$realms" | grep -q "ChromieCraft	127.0.0.1:8085" || fail "the realm list lost ChromieCraft"

	start_world world-remix.out remix
	wait_world_ready
	grep -q "Wrath Remix: on (realm 2)" "$world_log" || fail "Wrath Remix didn't turn on in its realm"
	grep -E "Wrath Remix: [0-9]+ level bands" "$world_log" || fail "Wrath Remix didn't load its tables"
	[ "$(echo "SELECT COUNT(*) FROM creature_template WHERE entry = 770001" | CTL sql acore_world_remix)" = 1 ] || fail "Moratari isn't in the Remix world"
	[ "$(echo "SELECT COUNT(*) FROM quest_template WHERE ID >= 1000000" | CTL sql acore_world_remix)" = 0 ] || fail "quests in the millions (the core's quest table grows to the highest id)"
	[ "$(echo "SELECT COUNT(*) FROM creature_template WHERE entry = 770001" | CTL sql acore_world)" = 0 ] || fail "Moratari is in the ChromieCraft world"
	[ "$(echo "SELECT QuestLevel FROM quest_template WHERE ID = 11672" | CTL sql acore_world)" = 71 ] || fail "Remix changed ChromieCraft's quests"
	echo "Enlistment Day: level $(echo "SELECT QuestLevel FROM quest_template WHERE ID = 11672" | CTL sql acore_world_remix) on Wrath Remix"

	export WORLD_PORT=8086
	python3 "$ROOT/warcraft/server/auth_check.py" world 127.0.0.1 tester secret123 Timerunner | tee "$FILES/logs/remix-client.out" ||
		fail "a character couldn't get into the Remix world"
	grep -q "level 10, map 1" "$FILES/logs/remix-client.out" || fail "the Timerunner isn't a level 10 in the Caverns of Time"
	python3 "$ROOT/warcraft/server/auth_check.py" characters 127.0.0.1 tester secret123 | tee "$FILES/logs/remix-characters.out"
	if grep -q "^Testhero" "$FILES/logs/remix-characters.out"; then fail "ChromieCraft's character is on Wrath Remix"; fi

	echo "== Wrath Remix's prologue"
	python3 "$ROOT/warcraft/server/auth_check.py" script 127.0.0.1 tester secret123 Timerunner 1 1 \
		"$ROOT/warcraft/server/remix_prologue.steps" | tee "$FILES/logs/remix-prologue.out" || fail "the prologue didn't play through"
	band=$(grep -o "band check: [0-9]* banded creatures, levels [0-9]* to [0-9]*" "$FILES/logs/remix-prologue.out" | tail -1)
	echo "Valiance Keep: $band"
	[ -n "$band" ] && [ "${band##* }" -le 23 ] || fail "Borean Tundra's creatures aren't at Chapter I's levels ($band)"
	row=$(echo "SELECT r.bronze, r.chapter FROM remix_character r JOIN characters c ON c.guid = r.guid WHERE c.name = 'Timerunner'" | CTL sql acore_characters_remix)
	echo "Timerunner's record: $row"
	[ "$row" = "$(printf '225\t1')" ] || fail "the Timerunner's Bronze and chapter weren't kept ($row)"
	[ "$(echo "SELECT COUNT(*) FROM character_inventory i JOIN item_instance t ON t.guid = i.item JOIN characters c ON c.guid = i.guid WHERE c.name = 'Timerunner' AND t.itemEntry = 42985" | CTL sql acore_characters_remix)" = 1 ] ||
		fail "the heirloom bought from Alurmi isn't in the bags"
	# The warrior's starter kit, on (every piece in its slot: heirlooms and greens), its
	# abilities (Defensive Stance from the class quest, Bloodrage from the trainer) and its axe skill
	# at a level 10's 50.
	kit=$(echo "SELECT GROUP_CONCAT(CONCAT(i.slot, ':', t.itemEntry) ORDER BY i.slot) FROM character_inventory i JOIN item_instance t ON t.guid = i.item JOIN characters c ON c.guid = i.guid WHERE c.name = 'Timerunner' AND i.bag = 0 AND i.slot < 19" | CTL sql acore_characters_remix)
	echo "Timerunner's gear: $kit"
	for piece in 2:42949 4:48685 5:832 6:2978 7:3302 9:2980 10:8350 11:50255 12:42991 14:2284 15:42943; do
		case ",$kit," in *",$piece,"*) ;; *) fail "the warrior's starter kit isn't on ($piece missing: $kit)" ;; esac
	done
	[ "$(echo "SELECT COUNT(*) FROM character_spell s JOIN characters c ON c.guid = s.guid WHERE c.name = 'Timerunner' AND s.spell IN (71, 2687)" | CTL sql acore_characters_remix)" = 2 ] ||
		fail "the warrior didn't learn Defensive Stance and Bloodrage"
	axe=$(echo "SELECT k.value FROM character_skills k JOIN characters c ON c.guid = k.guid WHERE c.name = 'Timerunner' AND k.skill = 172" | CTL sql acore_characters_remix)
	[ "$axe" = 50 ] || fail "the warrior's two-handed axe skill isn't 50 ($axe)"
	[ "$(echo "SELECT kit FROM remix_character r JOIN characters c ON c.guid = r.guid WHERE c.name = 'Timerunner'" | CTL sql acore_characters_remix)" = 1 ] ||
		fail "the starter kit isn't on record"

	echo "== Wrath Remix's NPCs on the floor"
	echo "SELECT guid, map, position_x, position_y, position_z, Comment FROM creature WHERE guid BETWEEN 7704001 AND 7704199 ORDER BY guid" |
		CTL sql acore_world_remix > "$FILES/logs/remix-spawns.tsv"
	python3 "$ROOT/warcraft/server/remix_check.py" floors 127.0.0.1 tester secret123 Timerunner "$FILES/logs/remix-spawns.tsv" |
		tee "$FILES/logs/remix-floors.out" || fail "Wrath Remix has NPCs floating or buried"
	unset WORLD_PORT
	stop_world
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
