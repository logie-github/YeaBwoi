#!/bin/sh
# Packs Felsong's realm into build/felsong/felsong-server.tar.xz: the programs
# scripts/compile-legioncore.sh left in build/legioncore-dist with every library they load (from the
# phone's Alpine, build/sysroot-aarch64), Alpine's MariaDB for arm64, the system call shim, the
# SQL, the configs, the Battle.net server's certificate, a seed database folder with LegionCore's
# databases made and its world and hotfixes imported, and its ctl.sh. Next to it,
# felsong-server.json: the package's size and SHA-256 (the app checks the download against them)
# and what's in it.
#
# The programs run from /data/data/com.logie.warcraft/files/felsong/server (their interpreter is
# that folder's lib/ld-musl-aarch64.so.1, their libraries ../lib), apart from the other realms'.
# The seed database is made here by this machine's MariaDB, the same version as the package's
# (InnoDB's files are the same on x86_64 and arm64).
#
# On a machine with Docker, runs itself in the same Alpine as the compile. DOCKER_RUN_ARGS adds
# arguments to `docker run`.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/felsong/server-version.env
. "$ROOT/warcraft/felsong/server-version.env"

if [ -z "${IN_ALPINE:-}" ]; then
	# shellcheck disable=SC2086
	exec docker run --rm -e IN_ALPINE=1 ${DOCKER_RUN_ARGS:-} \
		-v "$ROOT:/repo" -w /repo "alpine:$ALPINE_VERSION" /repo/scripts/package-legioncore.sh
fi

APP_FILES=/data/data/com.logie.warcraft/files/felsong
PREFIX="$APP_FILES/server"
DIST=/repo/build/legioncore-dist
STAGE=/repo/build/felsong-stage
OUT=/repo/build/felsong
DB_RELEASE=/repo/build/legioncore-db-$CLIENT_BUILD.7z

if [ ! -x "$DIST/bin/worldserver" ]; then
	echo "No compiled realm in build/legioncore-dist: run scripts/compile-legioncore.sh first." >&2
	exit 1
fi

apk add -q --no-progress patchelf xz tar 7zip curl clang20 lld20 llvm20 alpine-keys mariadb mariadb-client
# The phone's Alpine: its MariaDB, and the libraries the programs were linked against.
if [ "$(uname -m)" = aarch64 ]; then
	SYSROOT=
	# (a fresh container on an arm64 machine: the libraries the compile linked against, as there)
	apk add -q --no-progress mariadb mariadb-client libstdc++ boost-dev openssl-dev mariadb-connector-c-dev readline-dev bzip2-dev zlib-dev ncurses-dev
else
	SYSROOT=/repo/build/sysroot-aarch64
	[ -f "$SYSROOT/.made" ] || { echo "No build/sysroot-aarch64: run scripts/compile-legioncore.sh first." >&2; exit 1; }
	apk add -q --no-progress --root "$SYSROOT" --arch aarch64 --no-scripts \
		--keys-dir /usr/share/apk/keys/aarch64 --repositories-file /etc/apk/repositories mariadb mariadb-client
fi
host_db=$(apk info -e -v mariadb)
phone_db=$(apk info -e -v --root "${SYSROOT:-/}" mariadb)
[ "$host_db" = "$phone_db" ] || { echo "MariaDB here ($host_db) isn't the package's ($phone_db)" >&2; exit 1; }

rm -rf "$STAGE"
pkg="$STAGE/server"
mkdir -p "$pkg/bin" "$pkg/lib" "$pkg/etc" "$pkg/share"
cp "$DIST"/bin/* "$pkg/bin/"
for prog in mariadbd mariadb mariadb-admin mariadb-dump; do
	cp "$SYSROOT/usr/bin/$prog" "$pkg/bin/"
done
cp -r "$SYSROOT/usr/share/mariadb" "$pkg/share/mariadb"
cp "$DIST"/etc/* "$pkg/etc/"
cp -r "$DIST/sql" "$pkg/sql"
# OpenSSL 3 keeps RC4 in its legacy provider: Legion's world connection is encrypted with it.
mkdir -p "$pkg/lib/ossl-modules"
cp -L "$SYSROOT/usr/lib/ossl-modules/legacy.so" "$pkg/lib/ossl-modules/"
# System calls Android doesn't allow apps, made with ones it does: the ChromieCraft realm's shim,
# compiled again here into this package.
clang --target=aarch64-alpine-linux-musl ${SYSROOT:+--sysroot="$SYSROOT"} -fuse-ld=lld -O2 -shared -fPIC \
	-o "$pkg/lib/libandroidcompat.so" /repo/warcraft/server/android_compat.c

# Every library the programs load (their DT_NEEDED, and theirs), from the phone's Alpine.
needed() {
	llvm-readelf -d "$1" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'
}
copy_deps() {
	for name in $(needed "$1"); do
		case "$name" in ld-musl-*|libc.musl-*|libc.so) continue ;; esac
		[ -f "$pkg/lib/$name" ] && continue
		found=
		for dir in "$SYSROOT/usr/lib" "$SYSROOT/lib"; do
			[ -e "$dir/$name" ] && { found="$dir/$name"; break; }
		done
		[ -n "$found" ] || { echo "$(basename "$1") needs $name, which isn't in the phone's Alpine" >&2; exit 1; }
		cp -L "$found" "$pkg/lib/$name"
		copy_deps "$pkg/lib/$name"
	done
}
for prog in "$pkg"/bin/* "$pkg/lib/ossl-modules/legacy.so" "$pkg/lib/libandroidcompat.so"; do
	copy_deps "$prog"
done
ldso="$SYSROOT/lib/ld-musl-aarch64.so.1"
cp -L "$ldso" "$pkg/lib/ld-musl-aarch64.so.1"
cp -L "$ldso" "$pkg/lib/libc.musl-aarch64.so.1"
interp="$PREFIX/lib/ld-musl-aarch64.so.1"
for prog in "$pkg"/bin/*; do
	llvm-strip "$prog"
	patchelf --set-interpreter "$interp" --set-rpath '$ORIGIN/../lib' "$prog"
done
for lib in "$pkg"/lib/*.so*; do
	case "$(basename "$lib")" in ld-musl-*|libc.musl-*) continue ;; esac
	llvm-strip --strip-unneeded "$lib"
	patchelf --set-rpath '$ORIGIN' "$lib"
done
llvm-strip --strip-unneeded "$pkg/lib/ossl-modules/legacy.so"
patchelf --set-rpath '$ORIGIN/..' "$pkg/lib/ossl-modules/legacy.so"
# Every program's libraries are in the package now.
for prog in "$pkg"/bin/* "$pkg"/lib/*.so* "$pkg/lib/ossl-modules/legacy.so"; do
	for name in $(needed "$prog"); do
		case "$name" in ld-musl-*|libc.musl-*|libc.so) continue ;; esac
		[ -f "$pkg/lib/$name" ] || { echo "$(basename "$prog") needs $name, not packed" >&2; exit 1; }
	done
done

# The databases, made here: LegionCore's login and characters bases, its world and hotfixes (the
# project's release, checked against its SHA-256) and the app's additions (seed.sql). The world
# server applies the updates (sql/updates) on its first start.
if ! echo "$LEGIONCORE_DB_SHA256  $DB_RELEASE" | sha256sum -c - > /dev/null 2>&1; then
	curl -fsSL --retry 5 -o "$DB_RELEASE.tmp" "$LEGIONCORE_DB_URL"
	echo "$LEGIONCORE_DB_SHA256  $DB_RELEASE.tmp" | sha256sum -c -
	mv "$DB_RELEASE.tmp" "$DB_RELEASE"
fi
rm -rf /tmp/legioncore-db && mkdir -p /tmp/legioncore-db
7zz x -bd -o/tmp/legioncore-db "$DB_RELEASE" > /dev/null
world_sql=$(find /tmp/legioncore-db -name 'LegionCore_world_*.sql' | head -1)
hotfixes_sql=$(find /tmp/legioncore-db -name 'LegionCore_hotfixes_*.sql' | head -1)
[ -n "$world_sql" ] && [ -n "$hotfixes_sql" ] || { echo "The release has no world or hotfixes SQL" >&2; exit 1; }

seed="$pkg/mariadb-seed"
mariadb-install-db --no-defaults --auth-root-authentication-method=normal --skip-test-db \
	--user=root --datadir="$seed" > /dev/null
mariadbd --no-defaults --user=root --datadir="$seed" --socket=/tmp/seed.sock --skip-networking \
	--character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
	--innodb-use-native-aio=0 --innodb-log-file-size=96M --innodb-buffer-pool-size=1G \
	--max-allowed-packet=256M > /tmp/seed.log 2>&1 &
for _ in $(seq 60); do mariadb-admin --socket=/tmp/seed.sock ping > /dev/null 2>&1 && break; sleep 1; done
sqlc() { mariadb --socket=/tmp/seed.sock -uroot --max-allowed-packet=256M "$@"; }
for db in auth characters world hotfixes; do
	echo "CREATE DATABASE \`$db\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | sqlc
done
sqlc auth < "$DIST/sql/base/auth_database.sql"
sqlc characters < "$DIST/sql/base/characters_database.sql"
sqlc world < "$world_sql"
sqlc hotfixes < "$hotfixes_sql"
sqlc < /repo/warcraft/felsong/server/seed.sql
rm -rf /tmp/legioncore-db
creatures=$(echo "SELECT COUNT(*) FROM world.creature_template;" | sqlc -N)
hotfix_tables=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'hotfixes';" | sqlc -N)
echo "world: $creatures creature templates; hotfixes: $hotfix_tables tables"
[ "$creatures" -gt 50000 ] || { echo "The world database looks empty" >&2; exit 1; }
mariadb-admin --socket=/tmp/seed.sock -uroot shutdown
wait
rm -f "$seed"/*.err "$seed"/*.pid "$seed"/aria_log_control.lock

cat > "$pkg/VERSION" <<EOF
legioncore $(cat "$DIST/commit")
world $(basename "$world_sql" .sql)
client $CLIENT_BUILD
mariadb $phone_db
alpine $ALPINE_VERSION aarch64
EOF
cp /repo/warcraft/felsong/server/ctl.sh "$pkg/ctl.sh"
chmod +x "$pkg/ctl.sh"

mkdir -p "$OUT"
rm -f "$OUT/felsong-server.tar.xz"
tar -C "$STAGE" -cf - server | xz -T0 -6 > "$OUT/felsong-server.tar.xz"
size=$(stat -c %s "$OUT/felsong-server.tar.xz")
sha=$(sha256sum "$OUT/felsong-server.tar.xz" | cut -d' ' -f1)
unpacked=$(du -sb "$pkg" | cut -f1)
cat > "$OUT/felsong-server.json" <<EOF
{
  "file": "felsong-server.tar.xz",
  "size": $size,
  "sha256": "$sha",
  "unpacked": $unpacked,
  "legioncore": "$(cat "$DIST/commit")",
  "client_build": $CLIENT_BUILD,
  "arch": "aarch64"
}
EOF
du -sh "$pkg" "$OUT/felsong-server.tar.xz"
cat "$pkg/VERSION" "$OUT/felsong-server.json"
