#!/usr/bin/env bash
# Tests Turtle WoW's realm package (build/turtle/turtle-server.tar.xz) the way the phone runs it:
# unpacked into /data/data/com.logie.warcraft/files/turtle, driven by its own ctl.sh, on a Linux
# machine of the same architecture with no musl of its own.
#
#   1. Its MariaDB starts from the seed (Tortoise's databases made and its world imported), on its
#      own port, with its own databases (tw_*).
#   2. An account made the way the app makes it logs in to the login server with the 1.18.1
#      (7272) protocol and gets the realm list, with the realm on 127.0.0.1:8090.
#   3. The world server, with TortoiseBots in it, applies Tortoise's database updates and the
#      bots' own, and stops where it needs the game's data (DBC files), which CI can't have: Turtle WoW's client can't be distributed, so the
#      data is made on the phone from the user's client. Header-only stand-in map files get it
#      past its check of the start areas (CI only).
#   4. A backup of the accounts and characters restores an account deleted after it.
#   5. With strace, no program made a system call Android's seccomp policy forbids apps.
#   6. Nothing of the ChromieCraft realm's folders (files/server, files/mysql...) was made.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP=/data/data/com.logie.warcraft/files
FILES="$APP/turtle"
PKG="${TURTLE_PACKAGE:-$ROOT/build/turtle/turtle-server.tar.xz}"
CHECK="$ROOT/warcraft/turtle/server/auth_check.py"
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
grep -E '^(LoginDatabase.Info|DataDir|WorldServerPort|BindIP|Database.AutoUpdate.Path) ' "$FILES/etc/mangosd.conf"
grep -E '^(LoginDatabaseInfo|RealmServerPort|BindIP) ' "$FILES/etc/realmd.conf"
grep -q '127.0.0.1;3311;tortoise;tortoise;tw_world' "$FILES/etc/mangosd.conf" || fail "mangosd.conf doesn't use Turtle's database"
grep -E '^AiPlayerbot\.(Enabled|MinRandomBots|MaxRandomBots|RandomBotAutologin) ' "$FILES/etc/aiplayerbot.conf"
grep -q '^AiPlayerbot.Enabled = 1$' "$FILES/etc/aiplayerbot.conf" || fail "the bots aren't on"
grep -q '^AiPlayerbot.MaxRandomBots = 40$' "$FILES/etc/aiplayerbot.conf" || fail "the world bots aren't a phone's share"
# Every key the launcher and ctl.sh set is one TortoiseBots reads (a misspelt one would only be
# added to the end of the file and ignored).
for key in AiPlayerbot.Enabled AiPlayerbot.MinRandomBots AiPlayerbot.MaxRandomBots AiPlayerbot.RandomBotAutologin AiPlayerbot.Observability; do
	grep -q "^$key *=" "$FILES/server/etc/aiplayerbot.conf.dist" || fail "TortoiseBots' template has no $key"
done
[ -f "$FILES/etc/modules/tortoise_bots.conf" ] || fail "no etc/modules/tortoise_bots.conf (the world server won't start without it)"
[ -f "$FILES/server/addons/TortoiseBotsManager/TortoiseBotsManager.toc" ] || fail "the bots' addon isn't in the package"

echo "== MariaDB"
CTL db & pids+=($!)
wait_for 120 "MariaDB didn't start" CTL db-ping
for db in tw_logon tw_char tw_world tw_logs; do
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$db'" | CTL sql)
	echo "$db: $tables tables"
	[ "$tables" -gt 0 ] || fail "$db has no tables"
done
creatures=$(echo "SELECT COUNT(*) FROM creature_template" | CTL sql tw_world)
echo "tw_world: $creatures creature templates"
[ "$creatures" -gt 1000 ] || fail "the world wasn't imported"
echo "SELECT id, name, address, port, realmbuilds FROM realmlist" | CTL sql tw_logon

echo "== account and login (1.18.1, build 7272)"
python3 -I "$CHECK" account-sql TURTLETEST Shell1 4 | CTL sql tw_logon
CTL realmd > "$FILES/logs/realmd.out" 2>&1 & pids+=($!)
wait_for 60 "realmd didn't start" python3 -c "import socket; socket.create_connection(('127.0.0.1', 3724), 1)"
realms=$(python3 -I "$CHECK" login 127.0.0.1 TURTLETEST Shell1) || fail "the login failed"
echo "$realms"
echo "$realms" | grep -q "^Tortoise WoW 127.0.0.1:8090 " || fail "the realm list hasn't the realm on 127.0.0.1:8090"
if python3 -I "$CHECK" login 127.0.0.1 TURTLETEST wrong > /dev/null 2>&1; then fail "a wrong password logged in"; fi
# The game asks for the realm list again within the second; Tortoise's flood guard
# (MinRealmListDelay) dropped it with "Disconnected from server" until ctl.sh turned it off.
python3 -I "$CHECK" login 127.0.0.1 TURTLETEST Shell1 3 > /dev/null || fail "the login server dropped a realm list asked for again at once"

echo "== world server: database updates"
# No game data in CI (see above): header-only maps, line of sight and pathfinding off.
python3 -I "$CHECK" stand-in-maps "$FILES/data/maps"
sed -i 's/^vmap.enableLOS .*/vmap.enableLOS = 0/; s/^vmap.enableHeight .*/vmap.enableHeight = 0/; s/^mmap.enabled .*/mmap.enabled = 0/' "$FILES/etc/mangosd.conf"
updates=$(find "$FILES/server/sql/database_updates" "$FILES/server/modules" -name '*.sql' | wc -l)
bot_updates=$(find "$FILES/server/modules/TortoiseBots/data/sql" -name '*.sql' | wc -l)
[ "$bot_updates" -gt 0 ] || fail "the package has none of TortoiseBots' database updates"
start=$(date +%s)
set +e
CTL mangosd < /dev/null > "$FILES/logs/mangosd.out" 2>&1
code=$?
set -e
echo "mangosd ended with $code after $(($(date +%s) - start)) s"
grep -E "Auto-Updater|AutoUpdater" "$FILES/logs/mangosd.out" | tail -5 || true
grep -q "Processing module TortoiseBots updates from $FILES/server/modules/TortoiseBots/data/sql" "$FILES/logs/mangosd.out" ||
	fail "the world server didn't find TortoiseBots' database updates in the package"
grep -q "DB AutoUpdater FAILED" "$FILES/logs/mangosd.out" && fail "a database update failed"
grep -q "required \*.dbc files" "$FILES/logs/mangosd.out" || fail "the world server didn't get as far as the DBC files"
applied=$(( $(echo "SELECT COUNT(*) FROM migrations" | CTL sql tw_world) + $(echo "SELECT COUNT(*) FROM migrations" | CTL sql tw_char) ))
echo "database updates applied: $applied of $updates ($bot_updates of them TortoiseBots')"
[ "$applied" -eq "$updates" ] || fail "not every database update was applied"

echo "== backup and restore"
CTL backup "$FILES/backups.test.sql"
grep -q "TURTLETEST" "$FILES/backups.test.sql" || fail "the backup hasn't the account"
echo "DELETE FROM account WHERE username = 'TURTLETEST'" | CTL sql tw_logon
CTL restore "$FILES/backups.test.sql"
[ "$(echo "SELECT COUNT(*) FROM account WHERE username = 'TURTLETEST'" | CTL sql tw_logon)" = 1 ] || fail "the restore didn't bring the account back"
python3 -I "$CHECK" login 127.0.0.1 TURTLETEST Shell1 > /dev/null || fail "the restored account can't log in"

echo "== stop"
CTL db-stop
for pid in "${pids[@]}"; do kill "$pid" 2> /dev/null || true; done
wait 2> /dev/null || true

echo "== nothing outside Turtle's folder"
after=$(ls -A "$APP")
[ "$before" = "$after" ] || fail "something was made beside files/turtle: $after"

if [ ${#TRACE[@]} -gt 0 ]; then
	echo "== system calls"
	python3 -I "$ROOT/warcraft/server/seccomp_check.py" check "$FILES/server" "$FILES"/logs/syscalls/* || fail "a program made a system call Android forbids apps"
fi
echo "Turtle WoW's realm: OK"
