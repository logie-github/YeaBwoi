#!/bin/sh
# Packs Turtle WoW's realm into build/turtle/turtle-server.tar.xz: the programs
# scripts/compile-tortoise.sh left in build/tortoise-dist (the world server with TortoiseBots in
# it) with every library they load, Alpine's MariaDB, the system call shim, the SQL (the core's and
# the bots'), the configs, a seed database folder with Tortoise's databases made and its world
# imported, the bots' control addon (TortoiseBotsManager) and its ctl.sh. Next to it, turtle-server.json: the
# package's size and SHA-256 (the app checks the download against them) and what's in it.
#
# Not shipped in the APK: CI publishes it as a release of its own (turtle-server-<hash>) and the app
# downloads it when Turtle WoW is installed. The programs run from
# /data/data/com.logie.warcraft/files/turtle/server (their interpreter is that folder's
# lib/ld-musl-*.so.1, their libraries ../lib), apart from the ChromieCraft realm's files/server.
#
# On a machine with Docker, runs itself in the same Alpine as the compile. DOCKER_RUN_ARGS adds
# arguments to `docker run`.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/turtle/server-version.env
. "$ROOT/warcraft/turtle/server-version.env"

if [ -z "${IN_ALPINE:-}" ]; then
	# shellcheck disable=SC2086
	exec docker run --rm -e IN_ALPINE=1 ${DOCKER_RUN_ARGS:-} \
		-v "$ROOT:/repo" -w /repo "alpine:$ALPINE_VERSION" /repo/scripts/package-tortoise.sh
fi

APP_FILES=/data/data/com.logie.warcraft/files/turtle
PREFIX="$APP_FILES/server"
DIST=/repo/build/tortoise-dist
STAGE=/repo/build/turtle-stage
OUT=/repo/build/turtle

if [ ! -x "$DIST/bin/mangosd" ]; then
	echo "No compiled realm in build/tortoise-dist: run scripts/compile-tortoise.sh first." >&2
	exit 1
fi

apk add -q --no-progress build-base patchelf xz tar openssl-dev mariadb-connector-c-dev readline-dev \
	bzip2-dev zlib-dev mariadb mariadb-client

rm -rf "$STAGE"
pkg="$STAGE/server"
mkdir -p "$pkg/bin" "$pkg/lib" "$pkg/etc" "$pkg/share" "$pkg/sql"
cp "$DIST"/bin/* "$pkg/bin/"
for prog in mariadbd mariadb mariadb-admin mariadb-dump; do
	cp "$(command -v "$prog")" "$pkg/bin/"
done
cp -L "$DIST"/lib/libACE.so* "$pkg/lib/"
# OpenSSL 3 keeps RC4 in its legacy provider; Tortoise's ARC4 (Warden, off here) would need it.
mkdir -p "$pkg/lib/ossl-modules"
cp -L /usr/lib/ossl-modules/legacy.so "$pkg/lib/ossl-modules/"
# System calls Android doesn't allow apps, made with ones it does: the ChromieCraft realm's shim,
# compiled again here into this package.
cc -O2 -shared -fPIC -o "$pkg/lib/libandroidcompat.so" /repo/warcraft/server/android_compat.c
cp -r /usr/share/mariadb "$pkg/share/mariadb"
cp "$DIST"/etc/*.conf.dist "$pkg/etc/"
mkdir -p "$pkg/etc/modules"
cp "$DIST"/etc/modules/*.conf.dist "$pkg/etc/modules/"
cp "$DIST"/etc/offmesh.txt "$pkg/etc/" 2>/dev/null || touch "$pkg/etc/offmesh.txt"
# The world server's updates (mangosd applies the ones a database hasn't had, as it starts). The
# login database has none; the updater makes its folder if it's missing, so it's there already.
cp -r "$DIST/sql/database_updates" "$pkg/sql/"
mkdir -p "$pkg/sql/database_updates/auth"
# TortoiseBots' own updates (modules/TortoiseBots/data/sql/{world,character}), which the world
# server applies the same way, and its addon, which the app copies into the client's AddOns.
cp -r "$DIST/modules" "$pkg/modules"
cp -r "$DIST/addons" "$pkg/addons"

# Every library the programs load, then the programs pointed at them. ACE is in $pkg/lib already:
# the programs' own run path names ACE's build folder, which isn't there when the compile comes
# from CI's cache (every ACE symbol would read as missing).
missing=$(for prog in "$pkg"/bin/* "$pkg/lib/ossl-modules/legacy.so"; do
	LD_LIBRARY_PATH="$pkg/lib" ldd "$prog" 2>&1 | grep "not found" | sed "s|^|$(basename "$prog"): |"
done)
if [ -n "$missing" ]; then
	echo "$missing" >&2
	echo "Libraries the programs were built against aren't in this Alpine: compile again." >&2
	exit 1
fi
ldso=$(ls /lib/ld-musl-*.so.1)
cp "$ldso" "$pkg/lib/"
interp="$PREFIX/lib/$(basename "$ldso")"
copy_deps() {
	LD_LIBRARY_PATH="$pkg/lib" ldd "$1" 2>/dev/null | awk '/=>/ { print $3 }' | while read -r lib; do
		[ -f "$lib" ] || continue
		base=$(basename "$lib")
		case "$base" in ld-musl-*|libc.musl-*) continue ;; esac
		[ -f "$pkg/lib/$base" ] && continue
		cp -L "$lib" "$pkg/lib/$base"
		copy_deps "$lib"
	done
}
for prog in "$pkg"/bin/* "$pkg"/lib/libACE.so*; do
	copy_deps "$prog"
done
cp "$ldso" "$pkg/lib/libc.musl-$(uname -m).so.1"
for prog in "$pkg"/bin/*; do
	strip "$prog"
	patchelf --set-interpreter "$interp" --set-rpath '$ORIGIN/../lib' "$prog"
done
for lib in "$pkg"/lib/*.so*; do
	case "$(basename "$lib")" in ld-musl-*|libc.musl-*) continue ;; esac
	[ -L "$lib" ] && continue
	strip --strip-unneeded "$lib"
	patchelf --set-rpath '$ORIGIN' "$lib"
done
strip --strip-unneeded "$pkg/lib/ossl-modules/legacy.so"
patchelf --set-rpath '$ORIGIN/..' "$pkg/lib/ossl-modules/legacy.so"
still=$(for prog in "$pkg"/bin/*; do
	LD_LIBRARY_PATH="$pkg/lib" ldd "$prog" 2>&1 | grep "not found" | sed "s|^|$(basename "$prog"): |"
done)
[ -z "$still" ] || { echo "$still" >&2; exit 1; }

# The databases, made here by the same MariaDB: Tortoise's create script, its world (sql/base) and
# the app's additions (seed.sql). The phone only copies the folder; the world server applies the
# updates on its first start.
seed="$pkg/mariadb-seed"
mariadb-install-db --no-defaults --auth-root-authentication-method=normal --skip-test-db \
	--user=root --datadir="$seed" > /dev/null
mariadbd --no-defaults --user=root --datadir="$seed" --socket=/tmp/seed.sock --skip-networking \
	--character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
	--innodb-use-native-aio=0 --innodb-log-file-size=48M --innodb-buffer-pool-size=512M \
	--max-allowed-packet=64M > /tmp/seed.log 2>&1 &
for _ in $(seq 60); do mariadb-admin --socket=/tmp/seed.sock ping > /dev/null 2>&1 && break; sleep 1; done
sqlc() { mariadb --socket=/tmp/seed.sock -uroot --max-allowed-packet=64M "$@"; }
sqlc < "$DIST/sql/create_databases.sql"
for file in "$DIST"/sql/base/*.sql; do
	sqlc tw_world < "$file" || { echo "Importing $(basename "$file") failed" >&2; exit 1; }
done
sqlc < /repo/warcraft/turtle/server/seed.sql
tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'tw_world';" | sqlc -N)
creatures=$(echo "SELECT COUNT(*) FROM tw_world.creature_template;" | sqlc -N)
echo "tw_world: $tables tables, $creatures creature templates"
[ "$creatures" -gt 1000 ] || { echo "The world database looks empty" >&2; exit 1; }
mariadb-admin --socket=/tmp/seed.sock -uroot shutdown
wait
rm -f "$seed"/*.err "$seed"/*.pid "$seed"/aria_log_control.lock

cat > "$pkg/VERSION" <<EOF
tortoise $(cat "$DIST/commit")
tortoisebots $(cat "$DIST/tortoisebots-commit")
tortoisebotsmanager $(cat "$DIST/tbm-commit")
ace $ACE_VERSION
client $CLIENT_BUILD
mariadb $(mariadbd --version | awk '{ print $3 }')
alpine $(cat /etc/alpine-release) $(uname -m)
EOF
cp /repo/warcraft/turtle/server/ctl.sh "$pkg/ctl.sh"
chmod +x "$pkg/ctl.sh"

mkdir -p "$OUT"
rm -f "$OUT/turtle-server.tar.xz"
tar -C "$STAGE" -cf - server | xz -T0 -6 > "$OUT/turtle-server.tar.xz"
size=$(stat -c %s "$OUT/turtle-server.tar.xz")
sha=$(sha256sum "$OUT/turtle-server.tar.xz" | cut -d' ' -f1)
unpacked=$(du -sb "$pkg" | cut -f1)
cat > "$OUT/turtle-server.json" <<EOF
{
  "file": "turtle-server.tar.xz",
  "size": $size,
  "sha256": "$sha",
  "unpacked": $unpacked,
  "tortoise": "$(cat "$DIST/commit")",
  "tortoisebots": "$(cat "$DIST/tortoisebots-commit")",
  "client_build": $CLIENT_BUILD,
  "arch": "$(uname -m)"
}
EOF
du -sh "$pkg" "$OUT/turtle-server.tar.xz"
cat "$pkg/VERSION" "$OUT/turtle-server.json"
