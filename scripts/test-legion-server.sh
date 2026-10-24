#!/usr/bin/env bash
# Tests Legion's realm package (build/felsong/felsong-server.tar.xz) the way the phone runs it:
# unpacked into /data/data/com.logie.warcraft/files/felsong, driven by its own ctl.sh, on a Linux
# machine of the same architecture with no musl of its own (CI's arm64 runner; with qemu-user and
# binfmt registered, any machine).
#
#   1. Its MariaDB starts from the seed (LegionCore's databases made, its world and hotfixes
#      imported), on its own port, with its own databases (auth, characters, world, hotfixes), and
#      the realm in auth.realmlist is "Legion" on 127.0.0.1:8095 for build 26972.
#   2. An account made the way the app makes it logs in to the Battle.net server's REST login over
#      TLS with the server's own certificate and gets a login ticket; a wrong password gets none.
#   3. The world server applies the database updates and stops where it needs the game's data
#      (DB2 files), which CI can't have: Legion's client can't be distributed, so the data is made
#      on the phone from the user's client.
#   4. A backup of the accounts and characters restores an account deleted after it.
#   5. The client tools and the connection patcher are in the package and start.
#   6. With strace, no program made a system call Android's seccomp policy forbids apps.
#   7. Nothing of the other games' folders (files/server, files/mysql, files/turtle...) was made.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP=/data/data/com.logie.warcraft/files
FILES="$APP/felsong"
PKG="${LEGION_PACKAGE:-$ROOT/build/felsong/felsong-server.tar.xz}"
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
# (Under qemu-user the trace is of the emulator's own calls, not the programs'.)
if [ "$(uname -m)" != aarch64 ]; then
	echo "Not an arm64 machine: the system calls aren't checked against Android's policy."
elif command -v strace > /dev/null; then
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
grep -E '^(LoginDatabaseInfo|WorldDatabaseInfo|DataDir|WorldServerPort|InstanceServerPort|BindIP|Updates.EnableDatabases) ' "$FILES/etc/worldserver.conf"
grep -E '^(LoginDatabaseInfo|BattlenetPort|LoginREST.Port|BindIP|Game.Build.Version) ' "$FILES/etc/bnetserver.conf"
grep -q '127.0.0.1;3312;felsong;felsong;world' "$FILES/etc/worldserver.conf" || fail "worldserver.conf doesn't use Legion's database"
grep -q '^Game.Build.Version = 26972$' "$FILES/etc/bnetserver.conf" || fail "the login server isn't set to build 26972"
[ -f "$FILES/server/lib/ossl-modules/legacy.so" ] || fail "OpenSSL's legacy module isn't in the package"
grep -q 'export OPENSSL_MODULES="$SERVER/lib/ossl-modules"' "$FILES/server/ctl.sh" || fail "ctl.sh doesn't point OpenSSL at the package's modules"
for program in worldserver bnetserver mapextractor vmap4extractor vmap4assembler mmaps_generator connection_patcher mariadbd mariadb; do
	[ -x "$FILES/server/bin/$program" ] || fail "the package has no $program"
done

echo "== MariaDB"
CTL db & pids+=($!)
wait_for 240 "MariaDB didn't start" CTL db-ping
for db in auth characters world hotfixes; do
	tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$db'" | CTL sql)
	echo "$db: $tables tables"
	[ "$tables" -gt 0 ] || fail "$db has no tables"
done
creatures=$(echo "SELECT COUNT(*) FROM creature_template" | CTL sql world)
echo "world: $creatures creature templates"
[ "$creatures" -gt 50000 ] || fail "the world wasn't imported"
echo "SELECT id, name, address, port, gamePort, gamebuild FROM realmlist" | CTL sql auth
realm=$(echo "SELECT CONCAT(name, ' ', address, ':', port, ' ', gamebuild) FROM realmlist WHERE id = 1" | CTL sql auth)
[ "$realm" = "Legion 127.0.0.1:8095 26972" ] || fail "the realm isn't Legion on 127.0.0.1:8095 for build 26972 ($realm)"
[ "$(echo "SELECT COUNT(*) FROM account WHERE username = 'GM@GM'" | CTL sql auth)" = 0 ] || fail "LegionCore's public administrator account (GM@GM) is still there"

echo "== account and login (7.3.5, build 26972)"
python3 -I "$CHECK" account-sql LEGIONTEST@LOCAL Shell1 3 | CTL sql auth
CTL bnetserver > "$FILES/logs/bnetserver.out" 2>&1 & pids+=($!)
wait_for 90 "the login server didn't open its Battle.net port" python3 -c "import socket; socket.create_connection(('127.0.0.1', 1119), 1)"
wait_for 60 "the login server didn't open its REST port" python3 -c "import socket; socket.create_connection(('127.0.0.1', 8081), 1)"
python3 -I "$CHECK" login "$FILES/run/bnetserver.cert.pem" LEGIONTEST@LOCAL Shell1 || fail "the account couldn't log in"
python3 -I "$CHECK" login "$FILES/run/bnetserver.cert.pem" legiontest@local SHELL1 || fail "the login isn't case-insensitive as the client's is"
if python3 -I "$CHECK" login "$FILES/run/bnetserver.cert.pem" LEGIONTEST@LOCAL wrong > /dev/null 2>&1; then fail "a wrong password got a login ticket"; fi

echo "== world server: database updates"
updates=$(find "$FILES/server/sql/updates" -name '*.sql' | wc -l)
echo "$updates update files in the package"
start=$(date +%s)
set +e
CTL worldserver < /dev/null > "$FILES/logs/worldserver.out" 2>&1
code=$?
set -e
echo "worldserver ended with $code after $(($(date +%s) - start)) s"
grep -iE "updat|DB2|db2" "$FILES/logs/worldserver.out" | tail -8 || true
grep -qE "Applying of file .* failed|Could not update the .* database|DatabasePool .* NOT opened" "$FILES/logs/worldserver.out" && fail "the world server couldn't open or update its databases"
grep -qE "required \*\.db2 files|not found or not compatible|Incorrect DataDir value|Loading DB2" "$FILES/logs/worldserver.out" || fail "the world server didn't get as far as the DB2 files"

echo "== backup and restore"
CTL backup "$FILES/backups.test.sql"
grep -q "LEGIONTEST@LOCAL" "$FILES/backups.test.sql" || fail "the backup hasn't the account"
echo "DELETE FROM account WHERE username = 'LEGIONTEST@LOCAL'" | CTL sql auth
CTL restore "$FILES/backups.test.sql"
[ "$(echo "SELECT COUNT(*) FROM account WHERE username = 'LEGIONTEST@LOCAL'" | CTL sql auth)" = 1 ] || fail "the restore didn't bring the account back"
python3 -I "$CHECK" login "$FILES/run/bnetserver.cert.pem" LEGIONTEST@LOCAL Shell1 || fail "the restored account can't log in"

echo "== the client tools"
mkdir -p "$FILES/run/tools" && cd "$FILES/run/tools"
for tool in mapextractor vmap4extractor vmap4assembler connection_patcher; do
	set +e
	LD_PRELOAD="$FILES/server/lib/libandroidcompat.so" OPENSSL_MODULES="$FILES/server/lib/ossl-modules" HOME="$FILES" "$FILES/server/bin/$tool" --help > "$FILES/logs/tool-$tool.out" 2>&1 < /dev/null
	set -e
	[ -s "$FILES/logs/tool-$tool.out" ] || fail "$tool printed nothing (it doesn't start)"
	grep -qiE "error while loading|not found|cannot open shared|Segmentation|Bus error" "$FILES/logs/tool-$tool.out" && fail "$tool didn't start: $(head -3 "$FILES/logs/tool-$tool.out")"
done
cd "$ROOT"

echo "== stop"
CTL db-stop
for pid in "${pids[@]}"; do kill "$pid" 2> /dev/null || true; done
wait 2> /dev/null || true

echo "== nothing outside Legion's folder"
after=$(ls -A "$APP")
[ "$before" = "$after" ] || fail "something was made beside files/felsong: $after"

if [ ${#TRACE[@]} -gt 0 ]; then
	echo "== system calls"
	python3 -I "$ROOT/warcraft/server/seccomp_check.py" check "$FILES/server" "$FILES"/logs/syscalls/* || fail "a program made a system call Android forbids apps"
fi
echo "Legion's realm: OK"
