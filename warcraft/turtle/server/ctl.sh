#!/bin/sh
# Runs Turtle WoW's realm on the phone (Tortoise-WoW): its own MariaDB, the login server (realmd),
# the world server (mangosd) and the map tools. Everything is under $FILES (files/turtle), apart
# from the ChromieCraft realm's files/server, files/etc, files/mysql...: other folders, another
# MariaDB on another port, other databases (tw_*), other configs. The app runs it with Android's
# shell (/system/bin/sh) and CI with busybox's, so it sticks to POSIX sh. Server commands `exec`,
# so the process the app holds is the server itself.
#
#   ctl.sh configure [lan]          write the configs (from the shipped .dist files) and folders;
#                                   "lan" lets PCs on the Wi-Fi in (the default is this phone only).
#                                   The bots (TortoiseBots) are on, with a phone's share of world
#                                   bots ($WORLD_BOTS, default 40); the app sets its own count after
#   ctl.sh db-init                  make MariaDB's data folder from the seed, if there's none
#   ctl.sh db                       run MariaDB (makes its data folder from the seed first)
#   ctl.sh db-ping | db-stop        ask MariaDB if it's up / to shut down
#   ctl.sh sql [database]           run the SQL on stdin (prints tab-separated rows)
#   ctl.sh backup FILE | restore FILE  the accounts and characters (tw_logon, tw_char) to or from FILE
#   ctl.sh realmd | mangosd         run the login server / world server
#   ctl.sh maps|vmaps|mmaps CLIENT  make the server's data from a 1.18.1 client folder
#   ctl.sh data-done                put the data made (data.work) in place of data
#
# FILES is the Turtle folder; the programs are built to run from $FILES/server.
set -eu

FILES="${FILES:-/data/data/com.logie.warcraft/files/turtle}"
SERVER="$FILES/server"
ETC="$FILES/etc"
DATA="$FILES/data"
DB_DIR="$FILES/mysql"
LOGS="$FILES/logs"
RUN="$FILES/run"
# Not ChromieCraft's (3310, 8085, 8086): the login server's 3724 is the game's own, so the app runs
# one realm at a time.
DB_PORT="${DB_PORT:-3311}"
REALMD_PORT=3724
WORLD_PORT=8090
DB_USER=tortoise
DB_PASS=tortoise
export HOME="$FILES" TMPDIR="$RUN/tmp"

case "$FILES" in
*/files/turtle) ;;
*) echo "FILES must be the app's files/turtle folder, not $FILES" >&2; exit 2 ;;
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
	for name in mangosd realmd; do
		config_from_dist "$SERVER/etc/$name.conf.dist" "$ETC/$name.conf"
	done
	conf="$ETC/realmd.conf"
	setkey "$conf" LoginDatabaseInfo "$(dbinfo tw_logon)"
	setkey "$conf" LogsDir "\"$LOGS/\""
	setkey "$conf" PidFile "\"$RUN/realmd.pid\""
	setkey "$conf" RealmServerPort "$REALMD_PORT"
	setkey "$conf" BindIP "\"$bind\""
	setkey "$conf" PatchesDir "\"$RUN/patches\""
	setkey "$conf" WaitAtStartupError 0
	setkey "$conf" SendMail 0
	# A PIN (two-factor) for staff accounts is Tortoise's default (rank 1 and up): a realm on the
	# phone for its owner, whose first account is its administrator, asks for none (ranks go to 6).
	setkey "$conf" ForcePinAccountRank 100
	# Its flood guard drops a client that asks for the realm list twice within the second ("Disconnected
	# from server"), which the game does against a realm on the same phone (the app sets it too).
	setkey "$conf" MinRealmListDelay 0
	conf="$ETC/mangosd.conf"
	setkey "$conf" LoginDatabase.Info "$(dbinfo tw_logon)"
	setkey "$conf" WorldDatabase.Info "$(dbinfo tw_world)"
	setkey "$conf" CharacterDatabase.Info "$(dbinfo tw_char)"
	setkey "$conf" LogsDatabase.Info "$(dbinfo tw_logs)"
	setkey "$conf" DataDir "\"$DATA\""
	setkey "$conf" LogsDir "\"$LOGS\""
	setkey "$conf" PidFile "\"$RUN/mangosd.pid\""
	setkey "$conf" WorldServerPort "$WORLD_PORT"
	setkey "$conf" BindIP "\"$bind\""
	setkey "$conf" RealmID 1
	setkey "$conf" Database.AutoUpdate.Enabled 1
	setkey "$conf" Database.AutoUpdate.Path "\"$SERVER/sql/database_updates/\""
	setkey "$conf" Database.AutoUpdate.SortByName 1
	setkey "$conf" Console.Enable 1
	setkey "$conf" WaitAtStartupError 0
	setkey "$conf" HttpApi.Enable 0
	setkey "$conf" SOAP.Enabled 0
	setkey "$conf" Warden.WinEnabled 0
	setkey "$conf" Warden.OSXEnabled 0
	mkdir -p "$RUN/patches"
	# The bots: TortoiseBots reads aiplayerbot.conf beside mangosd.conf, and the core loads each
	# module's .conf from etc/modules/ (it won't start without it). The template's 500 world bots
	# are an average PC's; a phone gets far fewer (the launcher's Settings change the count).
	mkdir -p "$ETC/modules"
	config_from_dist "$SERVER/etc/modules/tortoise_bots.conf.dist" "$ETC/modules/tortoise_bots.conf"
	config_from_dist "$SERVER/etc/aiplayerbot.conf.dist" "$ETC/aiplayerbot.conf"
	conf="$ETC/aiplayerbot.conf"
	bots="${WORLD_BOTS:-40}"
	setkey "$conf" AiPlayerbot.Enabled 1
	setkey "$conf" AiPlayerbot.MinRandomBots "$bots"
	setkey "$conf" AiPlayerbot.MaxRandomBots "$bots"
	if [ "$bots" -gt 0 ]; then setkey "$conf" AiPlayerbot.RandomBotAutologin 1; else setkey "$conf" AiPlayerbot.RandomBotAutologin 0; fi
	# Its telemetry (a UDP port) and dashboard stay off.
	setkey "$conf" AiPlayerbot.Observability 0
	setkey "$ETC/modules/tortoise_bots.conf" AiPlayerbot.Observability 0
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

# The databases as the package made them (Tortoise's schema and world), copied whole and then
# renamed into place, so a copy that stopped halfway is never taken for the databases.
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
		--innodb-use-native-aio=0 --innodb-log-file-size=48M --innodb-buffer-pool-size=192M \
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
	# As MariaDB's root, through its socket: the triggers in tw_logon need it to be made again.
	# Written beside, then renamed: a backup that stopped halfway never replaces a good one.
	LD_PRELOAD="$COMPAT" "$SERVER/bin/mariadb-dump" --no-defaults --socket="$RUN/mysqld.sock" -uroot \
		--single-transaction --triggers --databases tw_logon tw_char > "$1.tmp"
	mv "$1.tmp" "$1"
	;;
restore)
	[ -s "$1" ] || { echo "No backup at $1" >&2; exit 1; }
	LD_PRELOAD="$COMPAT" "$SERVER/bin/mariadb" --no-defaults --socket="$RUN/mysqld.sock" -uroot < "$1"
	;;
realmd)
	cd "$RUN"
	musl_programs
	exec "$SERVER/bin/realmd" -c "$ETC/realmd.conf"
	;;
mangosd)
	cd "$RUN"
	musl_programs
	exec "$SERVER/bin/mangosd" -c "$ETC/mangosd.conf"
	;;
maps)
	# dbc and maps, from the client's Data folder (the tool finds Data/ under the client).
	rm -rf "$DATA.work"
	mkdir -p "$DATA.work" && cd "$DATA.work"
	musl_programs
	exec "$SERVER/bin/mapextractor" -i "$1" -o "$DATA.work"
	;;
vmaps)
	mkdir -p "$DATA.work/vmaps" && cd "$DATA.work"
	musl_programs
	"$SERVER/bin/vmapextractor" -d "$1/Data"
	"$SERVER/bin/vmap_assembler" Buildings vmaps
	rm -rf Buildings
	;;
mmaps)
	# Pathfinding, from maps and vmaps (the slow one).
	cd "$DATA.work"
	musl_programs
	exec "$SERVER/bin/MoveMapGen" --silent --offMeshInput "$SERVER/etc/offmesh.txt"
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
	sed -n '2,19p' "$0" >&2
	exit 2
	;;
esac
