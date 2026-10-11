#!/usr/bin/env bash
# Tests the World of Warcraft app's server package (warcraft/android/server/server.tar.xz) the way
# the phone runs it: unpacked into /data/data/com.logie.warcraft/files, driven by its ctl.sh, on a
# Linux machine of the same architecture that has no musl of its own (so the package has to bring
# everything it needs).
#
#   1. MariaDB starts from the seed data folder.
#   2. dbimport creates and fills the auth, characters, world and playerbots databases.
#   3. The auth server starts; an account made the way the app makes them logs in and gets the
#      realm list, with the realm's address.
#   4. The world server loads its configs and modules and updates the databases (including the
#      first ChromieCraft progression brackets), then stops where it needs the client's data
#      (dbc), which only the phone has.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILES=/data/data/com.logie.warcraft/files
PKG="$ROOT/warcraft/android/server/server.tar.xz"
SUDO=""
[ "$(id -u)" = 0 ] || SUDO=sudo

$SUDO rm -rf "$FILES"
$SUDO mkdir -p "$FILES"
$SUDO chown "$(id -u):$(id -g)" "$FILES"
tar -C "$FILES" -xJf "$PKG"
cat "$FILES/server/VERSION"
CTL="sh $FILES/server/ctl.sh"
export FILES

pids=()
cleanup() {
	for pid in "${pids[@]}"; do kill "$pid" 2> /dev/null || true; done
	wait 2> /dev/null || true
}
trap cleanup EXIT
fail() {
	echo "FAILED: $*" >&2
	for log in "$FILES"/logs/*; do
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
$CTL configure
ls "$FILES/etc" "$FILES/etc/modules"
# The first ChromieCraft brackets, as the app sets them.
sed -i 's/^ProgressionSystem.Bracket_0 .*/ProgressionSystem.Bracket_0 = 1/; s/^ProgressionSystem.Bracket_1_19 .*/ProgressionSystem.Bracket_1_19 = 1/' \
	"$FILES/etc/modules/progression_system.conf"

echo "== MariaDB"
$CTL db & pids+=($!)
wait_for 60 "MariaDB didn't start" $CTL db-ping
echo "MariaDB is up"

echo "== dbimport"
start=$(date +%s)
$CTL dbimport > "$FILES/logs/dbimport.out" 2>&1 || fail "dbimport exited with $?"
echo "dbimport took $(($(date +%s) - start)) s"
for db in acore_auth acore_characters acore_world acore_playerbots; do
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$db'" | $CTL sql)
	echo "$db: $tables tables"
	[ "$tables" -gt 0 ] || fail "$db has no tables"
done
creatures=$(echo "SELECT COUNT(*) FROM creature_template" | $CTL sql acore_world)
echo "creature_template: $creatures rows"
[ "$creatures" -gt 20000 ] || fail "the world database looks empty"

echo "== auth server"
python3 "$ROOT/warcraft/server/auth_check.py" account-sql tester secret123 | $CTL sql acore_auth
echo "UPDATE realmlist SET address = '192.168.1.50', localAddress = '127.0.0.1' WHERE id = 1" | $CTL sql acore_auth
$CTL auth > "$FILES/logs/auth.out" 2>&1 & pids+=($!)
wait_for 60 "the auth server didn't start" grep -q "Authserver started\|Starting network service\|bound to" "$FILES/logs/auth.out"
sleep 2
realms=$(python3 "$ROOT/warcraft/server/auth_check.py" login 127.0.0.1 tester secret123) ||
	fail "the test account couldn't log in"
echo "$realms"
echo "$realms" | grep -q "127.0.0.1:8085" || fail "the realm list doesn't point the phone's own client at 127.0.0.1"
if python3 "$ROOT/warcraft/server/auth_check.py" login 127.0.0.1 tester wrongpass > /dev/null 2>&1; then
	fail "a wrong password logged in"
fi

echo "== world server"
mkfifo "$FILES/run/world.in"
$CTL world < "$FILES/run/world.in" > "$FILES/logs/world.out" 2>&1 & pids+=($!)
exec 3> "$FILES/run/world.in"
world=$!
for _ in $(seq 300); do kill -0 "$world" 2> /dev/null || break; sleep 1; done
exec 3>&-
grep -q "Loading mod-progression-system updates" "$FILES/logs/world.out" || fail "the world server didn't load its modules"
grep -qi "playerbots" "$FILES/logs/world.out" || fail "the world server doesn't mention playerbots"
progression=$(echo "SELECT COUNT(*) FROM updates WHERE name LIKE '%progression%'" | $CTL sql acore_world)
echo "progression updates applied: $progression"
[ "$progression" -gt 0 ] || fail "the progression brackets' SQL wasn't applied"
grep -qi "dbc" "$FILES/logs/world.out" || fail "the world server stopped before reaching the client data"
tail -5 "$FILES/logs/world.out"
echo "The world server got as far as the client data (dbc), which CI doesn't have."

$CTL db-stop
echo "PASSED"
