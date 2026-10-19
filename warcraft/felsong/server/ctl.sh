#!/bin/sh
# Runs Felsong's realm on the phone (LegionCore 7.3.5): its own MariaDB, the Battle.net login
# server (bnetserver), the world server (worldserver) and the server's data tools. Everything is
# under $FILES (files/felsong), apart from the other realms' (files/server..., files/turtle):
# other folders, another MariaDB on another port, other configs and ports. The app runs it with
# Android's shell (/system/bin/sh), so it sticks to POSIX sh. Server commands `exec`, so the
# process the app holds is the server itself.
#
#   ctl.sh configure [lan]          write the configs (from the shipped .dist files) and folders;
#                                   "lan" lets PCs on the Wi-Fi in (the default is this phone only)
#   ctl.sh db-init                  make MariaDB's data folder from the seed, if there's none
#   ctl.sh db                       run MariaDB (makes its data folder from the seed first)
#   ctl.sh db-ping | db-stop        ask MariaDB if it's up / to shut down
#   ctl.sh sql [database]           run the SQL on stdin (prints tab-separated rows)
#   ctl.sh backup FILE | restore FILE  the accounts and characters (auth, characters) to or from FILE
#   ctl.sh bnetserver | worldserver run the login server / world server (the world server reads
#                                   commands on its standard input and stops when it ends)
#   ctl.sh maps|vmaps|mmaps CLIENT  make the server's data from a 7.3.5 (26972) client folder
#   ctl.sh data-done                put the data made (data.work) in place of data
#   ctl.sh patch-client EXE         patch the client's Wow-64.exe for this realm (Wow-64_Patched.exe
#                                   beside it) and write the certificate bundle it trusts under
#                                   $FILES/.wine (the app copies it into the game's Windows)
#
# FILES is the Felsong folder; the programs are built to run from $FILES/server.
set -eu

FILES="${FILES:-/data/data/com.logie.warcraft/files/felsong}"
SERVER="$FILES/server"
ETC="$FILES/etc"
DATA="$FILES/data"
DB_DIR="$FILES/mysql"
LOGS="$FILES/logs"
RUN="$FILES/run"
# None of the other realms' (3310, 3311; 3724, 8085, 8086, 8090): the client's Battle.net port
# (1119) and its REST login (8081) are Legion's own, the world's are Felsong's.
DB_PORT="${DB_PORT:-3312}"
BNET_PORT=1119
REST_PORT=8081
WORLD_PORT=8095
INSTANCE_PORT=8096
DB_USER=felsong
DB_PASS=felsong
export HOME="$FILES" TMPDIR="$RUN/tmp"
# OpenSSL's legacy module (RC4: Legion's world connection is encrypted with it) is in the package,
# not where its libcrypto looks.
export OPENSSL_MODULES="$SERVER/lib/ossl-modules"

case "$FILES" in
*/files/felsong) ;;
*) echo "FILES must be the app's files/felsong folder, not $FILES" >&2; exit 2 ;;
esac

# Sets `Key = value` in a config file, adding the line if the file doesn't have the key.
setkey() {
	file=$1 key=$2 value=$3
	if grep -q "^$key[[:space:]]*=" "$file"; then
		escaped=$(printf '%s' "$value" | sed 's/[|&\\]/\\&/g')
		sed -i "s|^$key[[:space:]]*=.*|$key = $escaped|" "$file"
	else
		printf '%s = %s\n' "$key" "$value" >> "$file"
	fi
}

# Copies a shipped .dist config to the live one the first time; settings the user changed stay.
config_from_dist() {
	dist=$1 conf=$2
	[ -f "$conf" ] || cp "$dist" "$conf"
}

dbinfo() {
	echo "\"127.0.0.1;$DB_PORT;$DB_USER;$DB_PASS;$1\""
}

configure() {
	mkdir -p "$ETC" "$DATA" "$LOGS" "$RUN/tmp"
	bind=127.0.0.1
	[ "${1:-}" = lan ] && bind=0.0.0.0
	for name in bnetserver worldserver; do
		config_from_dist "$SERVER/etc/$name.conf.dist" "$ETC/$name.conf"
	done
	conf="$ETC/bnetserver.conf"
	setkey "$conf" LoginDatabaseInfo "$(dbinfo auth)"
	setkey "$conf" LogsDir "\"$LOGS\""
	setkey "$conf" BattlenetPort "$BNET_PORT"
	setkey "$conf" LoginREST.Port "$REST_PORT"
	setkey "$conf" BindIP "\"$bind\""
	setkey "$conf" Updates.EnableDatabases 0
	setkey "$conf" MySQLExecutable "\"$SERVER/bin/mariadb\""
	setkey "$conf" Game.Build.Version 26972
	conf="$ETC/worldserver.conf"
	setkey "$conf" LoginDatabaseInfo "$(dbinfo auth)"
	setkey "$conf" WorldDatabaseInfo "$(dbinfo world)"
	setkey "$conf" CharacterDatabaseInfo "$(dbinfo characters)"
	setkey "$conf" HotfixDatabaseInfo "$(dbinfo hotfixes)"
	setkey "$conf" DataDir "\"$DATA\""
	setkey "$conf" LogsDir "\"$LOGS\""
	setkey "$conf" WorldServerPort "$WORLD_PORT"
	setkey "$conf" InstanceServerPort "$INSTANCE_PORT"
	setkey "$conf" BindIP "\"$bind\""
	setkey "$conf" RealmID 1
	# The database updater: the SQL the package ships ($SERVER/sql/updates...), applied with
	# MariaDB's client as the world server starts.
	setkey "$conf" SourceDirectory "\"$SERVER\""
	setkey "$conf" MySQLExecutable "\"$SERVER/bin/mariadb\""
	setkey "$conf" Updates.EnableDatabases 15
	setkey "$conf" Updates.AutoSetup 0
	setkey "$conf" Console.Enable 1
	setkey "$conf" Ra.Enable 0
	setkey "$conf" SOAP.Enabled 0
	setkey "$conf" Warden.Enabled 0
	setkey "$conf" GameType 0
	# One player on a phone: the dungeon finder takes one, and the core's dungeon scaling
	# (Solocraft) is the app's choice ($SOLOCRAFT, on unless 0).
	setkey "$conf" DungeonFinder.DebugJoin 1
	setkey "$conf" Solocraft.Enable "${SOLOCRAFT:-1}"
	# Memory: maps unload when no one is near; nothing loads whole grids up front.
	setkey "$conf" GridUnload 1
	setkey "$conf" ThreadPool 2
	setkey "$conf" MapUpdate.Threads 2
	# The Battle.net server reads its TLS certificate and key from its working folder.
	cp "$SERVER/etc/bnetserver.cert.pem" "$SERVER/etc/bnetserver.key.pem" "$RUN/"
}

# The programs (musl) run with libandroidcompat preloaded: it makes the system calls Android
# doesn't allow apps with ones it does. Only for them: Android's own programs can't load it.
COMPAT="$SERVER/lib/libandroidcompat.so"
musl_programs() {
	export LD_PRELOAD="$COMPAT"
}

db_client() {
	LD_PRELOAD="$COMPAT" "$SERVER/bin/mariadb" --no-defaults -h127.0.0.1 -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$@"
}

# The databases as the package made them, copied whole and then renamed into place, so a copy
# that stopped halfway is never taken for the databases.
db_init() {
	if [ ! -d "$DB_DIR/mysql" ]; then
		rm -rf "$DB_DIR.new"
		cp -r "$SERVER/mariadb-seed" "$DB_DIR.new"
		rm -rf "$DB_DIR"
		mv "$DB_DIR.new" "$DB_DIR"
	fi
}

cmd=${1:-}
[ $# -gt 0 ] && shift
case "$cmd" in
configure)
	configure "$@"
	;;
db-init)
	mkdir -p "$RUN/tmp" "$LOGS"
	db_init
	;;
db)
	mkdir -p "$RUN/tmp" "$LOGS"
	db_init
	# MariaDB refuses to run as root unless told to (only on a test machine: never on a phone).
	[ "$(id -u)" = 0 ] && set -- --user=root "$@"
	musl_programs
	# Android's app sandbox blocks io_uring, so InnoDB does its own asynchronous I/O.
	exec "$SERVER/bin/mariadbd" --no-defaults --datadir="$DB_DIR" --basedir="$SERVER" \
		--lc-messages-dir="$SERVER/share/mariadb" --character-sets-dir="$SERVER/share/mariadb/charsets" \
		--plugin-dir="$SERVER/lib/mariadb-plugins" --tmpdir="$RUN/tmp" \
		--socket="$RUN/mysqld.sock" --pid-file="$RUN/mysqld.pid" \
		--bind-address=127.0.0.1 --port="$DB_PORT" --skip-name-resolve \
		--character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
		--innodb-use-native-aio=0 --innodb-log-file-size=96M --innodb-buffer-pool-size=256M \
		--max-allowed-packet=64M --log-error="$LOGS/mariadb.log" "$@"
	;;
db-ping)
	musl_programs
	exec "$SERVER/bin/mariadb-admin" --no-defaults -h127.0.0.1 -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
		--connect-timeout=2 ping
	;;
db-stop)
	musl_programs
	exec "$SERVER/bin/mariadb-admin" --no-defaults -h127.0.0.1 -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
		shutdown
	;;
sql)
	db_client --batch --skip-column-names ${1:+"$1"}
	;;
backup)
	# Written beside, then renamed: a backup that stopped halfway never replaces a good one.
	LD_PRELOAD="$COMPAT" "$SERVER/bin/mariadb-dump" --no-defaults --socket="$RUN/mysqld.sock" -uroot \
		--single-transaction --triggers --databases auth characters > "$1.tmp"
	mv "$1.tmp" "$1"
	;;
restore)
	[ -s "$1" ] || { echo "No backup at $1" >&2; exit 1; }
	LD_PRELOAD="$COMPAT" "$SERVER/bin/mariadb" --no-defaults --socket="$RUN/mysqld.sock" -uroot < "$1"
	;;
bnetserver)
	cd "$RUN"
	musl_programs
	exec "$SERVER/bin/bnetserver" -c "$ETC/bnetserver.conf"
	;;
worldserver)
	cd "$RUN"
	musl_programs
	exec "$SERVER/bin/worldserver" -c "$ETC/worldserver.conf"
	;;
maps)
	# dbc (the DB2 files), maps, cameras and gt, read from the client's CASC storage. The tool
	# reads the client from its working folder and writes there: the work folder links to it.
	rm -rf "$DATA.work"
	mkdir -p "$DATA.work" && cd "$DATA.work"
	musl_programs
	exec "$SERVER/bin/mapextractor" -i "$1" -o "$DATA.work"
	;;
vmaps)
	mkdir -p "$DATA.work/vmaps" && cd "$DATA.work"
	musl_programs
	"$SERVER/bin/vmap4extractor" -d "$1/Data"
	"$SERVER/bin/vmap4assembler" Buildings vmaps
	rm -rf Buildings
	;;
mmaps)
	# Pathfinding, from maps and vmaps (the slow one: hours).
	cd "$DATA.work"
	musl_programs
	exec "$SERVER/bin/mmaps_generator"
	;;
patch-client)
	# LegionCore's connection_patcher: the portal takes a plain host, the Battle.net signing key and
	# the certificate fingerprint are this realm's (bnetserver's own certificate), the versions
	# check is cut. It writes <name>_Patched.exe beside the program and the certificate bundle under
	# $HOME/.wine/drive_c/users/Public/Application Data/Blizzard Entertainment/Battle.net/Cache/,
	# and HOME is $FILES here: nothing lands outside Felsong's folder.
	[ -f "$1" ] || { echo "No client program at $1" >&2; exit 1; }
	mkdir -p "$RUN/tmp"
	cd "$RUN"
	musl_programs
	exec "$SERVER/bin/connection_patcher" "$1" < /dev/null
	;;
data-done)
	# What was made replaces what was there, in one rename; the old data goes after.
	[ -d "$DATA.work/dbc" ] && [ -d "$DATA.work/maps" ] || { echo "No dbc and maps in $DATA.work" >&2; exit 1; }
	rm -rf "$DATA.old"
	[ ! -d "$DATA" ] || mv "$DATA" "$DATA.old"
	mv "$DATA.work" "$DATA"
	rm -rf "$DATA.old"
	;;
*)
	sed -n '2,23p' "$0" >&2
	exit 2
	;;
esac
