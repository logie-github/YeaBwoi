#!/bin/sh
# Runs the World of Warcraft app's private server: MariaDB, the database import, the auth and world
# servers, the client data tools. The app runs it with Android's shell (/system/bin/sh) and CI runs
# it with busybox's, so it sticks to POSIX sh. Every server command `exec`s, so the process the
# app holds is the server itself.
#
#   ctl.sh configure                write the configs (from the shipped .dist files) and folders
#   ctl.sh db                       run MariaDB (makes its data folder from the seed first)
#   ctl.sh db-ping | db-stop        ask MariaDB if it's up / to shut down
#   ctl.sh sql [database]           run the SQL on stdin (prints tab-separated rows)
#   ctl.sh dbimport                 create and update the databases
#   ctl.sh auth | world             run the auth server / world server
#   ctl.sh maps|vmaps|mmaps CLIENT  extract the server's data from a 3.3.5a client folder
#
# FILES is the app's files folder; the programs are built to run from $FILES/server.
set -eu

FILES="${FILES:-/data/data/com.logie.warcraft/files}"
SERVER="$FILES/server"
ETC="$FILES/etc"
DATA="$FILES/data"
DB_DIR="$FILES/mysql"
LOGS="$FILES/logs"
RUN="$FILES/run"
DB_PORT="${DB_PORT:-3310}"
DB_USER=acore
DB_PASS=acore
export HOME="$FILES" TMPDIR="$RUN/tmp"
export AC_DISABLE_INTERACTIVE=1
# OpenSSL's legacy provider (RC4, for the game's packet headers), shipped with the server.
export OPENSSL_MODULES="$SERVER/lib/ossl-modules"

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
	mkdir -p "$ETC/modules" "$DATA" "$LOGS" "$RUN/tmp"
	for name in worldserver authserver dbimport; do
		conf="$ETC/$name.conf"
		config_from_dist "$SERVER/etc/$name.conf.dist" "$conf"
		setkey "$conf" LogsDir "\"$LOGS\""
		setkey "$conf" TempDir "\"$RUN/tmp\""
		setkey "$conf" SourceDirectory "\"$SERVER/src\""
		setkey "$conf" MySQLExecutable "\"$SERVER/bin/mariadb\""
		setkey "$conf" LoginDatabaseInfo "$(dbinfo acore_auth)"
		setkey "$conf" Updates.AutoSetup 1
	done
	for name in worldserver dbimport; do
		conf="$ETC/$name.conf"
		setkey "$conf" WorldDatabaseInfo "$(dbinfo acore_world)"
		setkey "$conf" CharacterDatabaseInfo "$(dbinfo acore_characters)"
	done
	setkey "$ETC/dbimport.conf" Updates.EnableDatabases 7
	setkey "$ETC/worldserver.conf" DataDir "\"$DATA\""
	setkey "$ETC/worldserver.conf" PidFile "\"$RUN/worldserver.pid\""
	setkey "$ETC/authserver.conf" PidFile "\"$RUN/authserver.pid\""
	for dist in "$SERVER"/etc/modules/*.conf.dist; do
		conf="$ETC/modules/$(basename "$dist" .dist)"
		config_from_dist "$dist" "$conf"
	done
	if [ -f "$ETC/modules/playerbots.conf" ]; then
		setkey "$ETC/modules/playerbots.conf" PlayerbotsDatabaseInfo "$(dbinfo acore_playerbots)"
	fi
	# dbimport reads module configs too (the playerbots database).
	cp "$SERVER/etc/mmaps-config.yaml" "$ETC/mmaps-config.yaml"
}

# The server's programs (musl) run with libandroidcompat preloaded: it makes the system calls
# Android doesn't allow apps with ones it does. Only for them: Android's own programs (sed, cp, the
# shell) can't load it.
COMPAT="$SERVER/lib/libandroidcompat.so"
musl_programs() {
	export LD_PRELOAD="$COMPAT${1:+:$1}"
}

db_client() {
	LD_PRELOAD="$COMPAT" "$SERVER/bin/mariadb" --no-defaults -h127.0.0.1 -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$@"
}

cmd=${1:-}
[ $# -gt 0 ] && shift
case "$cmd" in
configure)
	configure
	;;
db)
	mkdir -p "$RUN/tmp" "$LOGS"
	if [ ! -d "$DB_DIR/mysql" ]; then
		rm -rf "$DB_DIR.new"
		cp -r "$SERVER/mariadb-seed" "$DB_DIR.new"
		rm -rf "$DB_DIR"
		mv "$DB_DIR.new" "$DB_DIR"
	fi
	# MariaDB refuses to run as root unless told to (only on a test machine: never on a phone).
	[ "$(id -u)" = 0 ] && set -- --user=root "$@"
	musl_programs
	# Android's app sandbox blocks io_uring, so InnoDB does its own asynchronous I/O.
	exec "$SERVER/bin/mariadbd" --no-defaults --datadir="$DB_DIR" --basedir="$SERVER" \
		--lc-messages-dir="$SERVER/share/mariadb" --character-sets-dir="$SERVER/share/mariadb/charsets" \
		--plugin-dir="$SERVER/lib/mariadb-plugins" --tmpdir="$RUN/tmp" \
		--socket="$RUN/mysqld.sock" --pid-file="$RUN/mysqld.pid" \
		--bind-address=127.0.0.1 --port="$DB_PORT" --skip-name-resolve \
		--innodb-use-native-aio=0 --innodb-log-file-size=48M --innodb-buffer-pool-size=256M \
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
dbimport)
	cd "$RUN"
	musl_programs
	exec "$SERVER/bin/dbimport" -c "$ETC/dbimport.conf"
	;;
auth)
	cd "$RUN"
	musl_programs
	exec "$SERVER/bin/authserver" -c "$ETC/authserver.conf"
	;;
world)
	cd "$RUN"
	# jemalloc in place of musl's malloc, which is slow for the world server's many threads.
	musl_programs "$SERVER/lib/libjemalloc.so.2"
	# Whether the phone's player just selected an enemy, for the app's touch controls
	# (patches/azerothcore/0007).
	export WOW_SELECTION_FILE="$RUN/selection"
	exec "$SERVER/bin/worldserver" -c "$ETC/worldserver.conf"
	;;
maps)
	# dbc, Cameras and maps, from the client's Data folder.
	mkdir -p "$DATA.work" && cd "$DATA.work"
	musl_programs
	exec "$SERVER/bin/map_extractor" -i "$1" -o "$DATA.work"
	;;
vmaps)
	mkdir -p "$DATA.work/vmaps" && cd "$DATA.work"
	musl_programs
	"$SERVER/bin/vmap4_extractor" -d "$1/Data/"
	exec "$SERVER/bin/vmap4_assembler" Buildings vmaps
	;;
mmaps)
	# Pathfinding, from maps and vmaps (the slow one).
	cd "$DATA.work"
	musl_programs
	exec "$SERVER/bin/mmaps_generator" --config "$ETC/mmaps-config.yaml" ${1:+--threads "$1"}
	;;
*)
	sed -n '2,17p' "$0" >&2
	exit 2
	;;
esac
