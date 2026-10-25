#!/usr/bin/env bash
# Tests the Legion realm package (build/felsong/felsong-server.tar.xz) the way the phone runs it:
# unpacked into /data/data/com.logie.warcraft/files/felsong, driven by its own ctl.sh, on a Linux
# machine of the same architecture with no musl of its own (or any machine with qemu's arm64 binfmt).
#
#   1. Its MariaDB starts from the seed (LegionCore's databases made, its world and hotfixes
#      imported), on its own port (3312), with its own databases (auth, characters, world, hotfixes).
#   2. The Battle.net login server (bnetserver) listens on 1119 (TLS) and 8081 (its REST login): the
#      login form comes back, an account made the way the app makes it gets a login ticket, a wrong
#      password doesn't.
#   3. The world server applies LegionCore's database updates and stops where it needs the client's
#      data (the DB2 tables), which CI can't have: the data is made on the phone from the user's client.
#   4. A backup of the accounts and characters restores an account deleted after it.
#   5. The client tools are in the package (the connection patcher refuses a missing client).
#   6. With strace (an arm64 machine), no program made a system call Android's seccomp policy forbids apps.
#   7. Nothing of the other realms' folders (files/server, files/mysql, files/turtle...) was made.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP=/data/data/com.logie.warcraft/files
FILES="$APP/felsong"
PKG="${FELSONG_PACKAGE:-$ROOT/build/felsong/felsong-server.tar.xz}"
CHECK="$ROOT/warcraft/felsong/server/login_check.py"
SUDO=""
[ "$(id -u)" = 0 ] || SUDO=sudo

$SUDO pkill -f "$FILES/server/bin/" 2> /dev/null && sleep 5 || true
$SUDO rm -rf "$FILES"
$SUDO mkdir -p "$FILES"
$SUDO chown -R "$(id -u):$(id -g)" "$APP"
before=$(ls -A "$APP")
tar -C "$FILES" -xJf "$PKG"
cat "$FILES/server/VERSION"
export FILES

TRACE=()
if command -v strace > /dev/null && [ "$(uname -m)" = aarch64 ]; then
	filter=$(python3 -I "$ROOT/warcraft/server/seccomp_check.py" filter)
	TRACE=(strace -f --seccomp-bpf -qq -e signal=none -e "trace=$filter")
	mkdir -p "$FILES/logs/syscalls"
else
	echo "No strace on an arm64 machine: the system calls aren't checked against Android's policy."
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
port_open() { python3 -c "import socket,sys; socket.create_connection(('127.0.0.1', int(sys.argv[1])), 1)" "$1"; }

echo "== configure"
CTL configure
grep -E '^(LoginDatabaseInfo|WorldDatabaseInfo|HotfixDatabaseInfo|DataDir|WorldServerPort|InstanceServerPort|BindIP|SourceDirectory|Solocraft.Enable) ' "$FILES/etc/worldserver.conf"
grep -E '^(LoginDatabaseInfo|BattlenetPort|LoginREST.Port|BindIP|Game.Build.Version) ' "$FILES/etc/bnetserver.conf"
grep -q '127.0.0.1;3312;felsong;felsong;world' "$FILES/etc/worldserver.conf" || fail "worldserver.conf doesn't use the realm's own database"
grep -q '^Game.Build.Version = 26972$' "$FILES/etc/bnetserver.conf" || fail "the login server isn't set for client 26972"
grep -q '^Solocraft.Enable = 1$' "$FILES/etc/worldserver.conf" || fail "Solocraft isn't on"
[ -f "$FILES/run/bnetserver.cert.pem" ] && [ -f "$FILES/run/bnetserver.key.pem" ] || fail "the login server's certificate isn't in its working folder"
[ -f "$FILES/server/lib/ossl-modules/legacy.so" ] || fail "OpenSSL's legacy module isn't in the package"
for key in Solocraft.Enable DungeonFinder.DebugJoin GridUnload MapUpdate.Threads Rate.XP.Kill StartPlayerLevel vmap.enableLOS mmap.enablePathFinding; do
	grep -q "^$key *=" "$FILES/server/etc/worldserver.conf.dist" || fail "LegionCore's template has no $key (the app sets it)"
done

echo "== MariaDB"
CTL db & pids+=($!)
wait_for 300 "MariaDB didn't start" CTL db-ping
for db in auth characters world hotfixes; do
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$db'" | CTL sql)
	echo "$db: $tables tables"
	[ "$tables" -gt 0 ] || fail "$db has no tables"
done
creatures=$(echo "SELECT COUNT(*) FROM creature_template" | CTL sql world)
echo "world: $creatures creature templates"
[ "$creatures" -gt 50000 ] || fail "the world wasn't imported"
echo "SELECT id, name, address, port, gamePort, gamebuild FROM realmlist" | CTL sql auth
[ "$(echo "SELECT COUNT(*) FROM account WHERE username = 'GM@GM'" | CTL sql auth)" = 0 ] || fail "LegionCore's public GM@GM account is still there"

# The allied races and hero classes the app's game options unlock (patch 0009): their creation data.
echo "SELECT raceID, expansion, achievementId FROM race_unlock_requirement WHERE raceID > 22 ORDER BY raceID" | CTL sql world
allied=$(echo "SELECT COUNT(DISTINCT race) FROM playercreateinfo WHERE race IN (27, 28, 29, 30)" | CTL sql world)
echo "allied races with a start: $allied of 4"
[ "$allied" = 4 ] || fail "the allied races (27 to 30) have no start in playercreateinfo"
[ "$(echo "SELECT COUNT(*) FROM playercreateinfo WHERE class IN (6, 12)" | CTL sql world)" -gt 0 ] || fail "death knights and demon hunters have no start"

# The Undead paladins option's rows (FelsongGameOptions.sql, the same statements): in, then out.
echo "== game options: undead paladins"
cat > "$FILES/run/undead-paladins.sql" <<'SQL'
DELETE FROM hotfixes.hotfix_data WHERE Id = 2000000001;
DELETE FROM hotfixes.char_base_info WHERE ID = 1000;
DELETE FROM world.playercreateinfo WHERE race = 5 AND class = 2;
DELETE FROM world.playercreateinfo_action WHERE race = 5 AND class = 2;
DELETE FROM world.player_levelstats WHERE race = 5 AND class = 2;
INSERT INTO hotfixes.char_base_info (ID, RaceID, ClassID) VALUES (1000, 5, 2);
INSERT INTO hotfixes.hotfix_data (Id, TableHash, RecordId, Deleted) VALUES (2000000001, 812099832, 1000, 0);
DROP TEMPORARY TABLE IF EXISTS app_copy;
CREATE TEMPORARY TABLE app_copy SELECT * FROM world.playercreateinfo WHERE race = 5 AND class = 1;
UPDATE app_copy SET class = 2;
INSERT INTO world.playercreateinfo SELECT * FROM app_copy;
DROP TEMPORARY TABLE app_copy;
DROP TEMPORARY TABLE IF EXISTS app_copy;
CREATE TEMPORARY TABLE app_copy SELECT * FROM world.playercreateinfo_action WHERE race = 10 AND class = 2;
UPDATE app_copy SET race = 5;
INSERT INTO world.playercreateinfo_action SELECT * FROM app_copy;
DROP TEMPORARY TABLE app_copy;
DROP TEMPORARY TABLE IF EXISTS app_copy;
CREATE TEMPORARY TABLE app_copy SELECT * FROM world.player_levelstats WHERE race = 10 AND class = 2;
UPDATE app_copy SET race = 5;
INSERT INTO world.player_levelstats SELECT * FROM app_copy;
DROP TEMPORARY TABLE app_copy;
SQL
CTL sql world < "$FILES/run/undead-paladins.sql" || fail "the undead paladins' rows didn't go in"
[ "$(echo "SELECT COUNT(*) FROM playercreateinfo WHERE race = 5 AND class = 2" | CTL sql world)" = 1 ] || fail "no undead paladin start"
echo "undead paladin action buttons: $(echo "SELECT COUNT(*) FROM playercreateinfo_action WHERE race = 5 AND class = 2" | CTL sql world)"
# (every race and class with a start has level stats: the world server stops without them, issue #74)
missing=$(echo "SELECT COUNT(*) FROM playercreateinfo p WHERE NOT EXISTS (SELECT 1 FROM player_levelstats s WHERE s.race = p.race AND s.class = p.class AND s.level = 1)" | CTL sql world)
[ "$missing" = 0 ] || fail "$missing race and class starts have no level stats"
head -5 "$FILES/run/undead-paladins.sql" | CTL sql world || fail "the undead paladins' rows didn't come out"
rm -f "$FILES/run/undead-paladins.sql"

echo "== Battle.net login (7.3.5, build 26972)"
python3 -I "$CHECK" account-sql legion@phone Shell1 3 | CTL sql auth
CTL bnetserver > "$FILES/logs/bnetserver.out" 2>&1 & pids+=($!)
wait_for 180 "bnetserver didn't listen on 1119" port_open 1119
wait_for 60 "bnetserver didn't listen on 8081" port_open 8081
form=$(python3 -I "$CHECK" form 127.0.0.1) || fail "the login form didn't come back"
echo "login form: $form"
ticket=$(python3 -I "$CHECK" login 127.0.0.1 legion@phone Shell1) || fail "the login failed"
echo "login ticket: $ticket"
if python3 -I "$CHECK" login 127.0.0.1 legion@phone wrong > /dev/null 2>&1; then fail "a wrong password got a login ticket"; fi

echo "== world server: database updates"
# No client data in CI: empty data folders get the world server to its DB2 load, where it stops.
python3 -I "$CHECK" stand-in-data "$FILES/data"
updates=$(find "$FILES/server/sql/updates" -name '*.sql' | wc -l)
start=$(date +%s)
set +e
timeout 3600 sh "$FILES/server/ctl.sh" worldserver < /dev/null > "$FILES/logs/worldserver.out" 2>&1
code=$?
set -e
echo "worldserver ended with $code after $(($(date +%s) - start)) s"
grep -iE "update|applied|db2" "$FILES/logs/worldserver.out" | tail -8 || true
grep -qE "Applying of file .* failed|Could not update the .* database|Could not populate" "$FILES/logs/worldserver.out" && fail "a database update failed"
grep -qE "required \*\.db2 files|not found by path|Incorrect DataDir" "$FILES/logs/worldserver.out" || fail "the world server didn't get as far as the DB2 files"
applied=0
for db in auth characters world hotfixes; do
	n=$(echo "SELECT COUNT(*) FROM updates" | CTL sql $db 2> /dev/null || echo 0)
	applied=$((applied + n))
done
echo "database updates recorded: $applied (the package carries $updates files)"
[ "$applied" -gt 0 ] || fail "no database update was recorded"

echo "== backup and restore"
CTL backup "$FILES/backups.test.sql"
grep -q "LEGION@PHONE" "$FILES/backups.test.sql" || fail "the backup hasn't the account"
echo "DELETE FROM account WHERE username = 'LEGION@PHONE'" | CTL sql auth
CTL restore "$FILES/backups.test.sql"
[ "$(echo "SELECT COUNT(*) FROM account WHERE username = 'LEGION@PHONE'" | CTL sql auth)" = 1 ] || fail "the restore didn't bring the account back"
python3 -I "$CHECK" login 127.0.0.1 legion@phone Shell1 > /dev/null || fail "the restored account can't log in"

echo "== client tools"
set +e
CTL patch-client "$FILES/no-such-client.exe" > "$FILES/logs/patch-client.out" 2>&1
code=$?
set -e
[ "$code" -ne 0 ] || fail "patch-client accepted a missing client"
for prog in mapextractor vmap4extractor vmap4assembler mmaps_generator connection_patcher; do
	[ -x "$FILES/server/bin/$prog" ] || fail "$prog isn't in the package"
done

echo "== stop"
CTL db-stop
for pid in "${pids[@]}"; do kill "$pid" 2> /dev/null || true; done
wait 2> /dev/null || true

echo "== nothing outside the realm's folder"
after=$(ls -A "$APP")
[ "$before" = "$after" ] || fail "something was made beside files/felsong: $after"

if [ ${#TRACE[@]} -gt 0 ]; then
	echo "== system calls"
	python3 -I "$ROOT/warcraft/server/seccomp_check.py" check "$FILES/server" "$FILES"/logs/syscalls/* || fail "a program made a system call Android forbids apps"
fi
echo "The Legion realm: OK"
