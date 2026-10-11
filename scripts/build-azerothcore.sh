#!/bin/sh
# Builds the World of Warcraft app's private server for Linux arm64 (musl, Alpine): AzerothCore at
# the revision ChromieCraft runs with the playerbots fork's core changes merged onto it, the modules
# in warcraft/server-version.env, AzerothCore's data tools, and Alpine's MariaDB. Packs everything
# the phone runs into warcraft/android/server/server.tar.xz (git-ignored), which the app ships as an
# asset and unpacks into its storage.
#
# The programs run from /data/data/com.logie.warcraft/files/server, the way Termux runs its
# programs from its own folder: each one's interpreter is that folder's lib/ld-musl-*.so.1 and it
# finds its libraries in ../lib, so they need nothing from the phone but the kernel.
#
# On a machine with Docker, runs itself in an Alpine container of the machine's architecture
# (GitHub's ubuntu-24.04-arm runners build the phone's arm64). DOCKER_RUN_ARGS adds arguments to
# `docker run` (a proxy, for example).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/server-version.env
. "$ROOT/warcraft/server-version.env"

if [ -z "${IN_ALPINE:-}" ]; then
	mkdir -p "$ROOT/build"
	# shellcheck disable=SC2086
	exec docker run --rm -e IN_ALPINE=1 -e JOBS="${JOBS:-}" -e NINJA_ARGS="${NINJA_ARGS:-}" ${DOCKER_RUN_ARGS:-} \
		-v "$ROOT:/repo" -w /repo "alpine:$ALPINE_VERSION" /repo/scripts/build-azerothcore.sh
fi

APP_FILES=/data/data/com.logie.warcraft/files
PREFIX="$APP_FILES/server"
SRC=/repo/build/azerothcore
BUILD=/repo/build/azerothcore-build
STAGE=/repo/build/warcraft-server
OUT=/repo/warcraft/android/server
JOBS="${JOBS:-$(nproc)}"

apk add -q --no-progress build-base cmake samurai git ccache patchelf xz tar boost-dev openssl-dev \
	mariadb-connector-c-dev readline-dev bzip2-dev zlib-dev linux-headers mariadb mariadb-client
export CCACHE_DIR=/repo/build/ccache CCACHE_MAXSIZE=2G
git config --global --add safe.directory '*'

sh /repo/scripts/fetch-azerothcore.sh "$SRC"

# musl gives new threads a 128 KiB stack unless the program asks for more; glibc gives 8 MiB.
cmake -S "$SRC" -B "$BUILD" -G Ninja \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_INSTALL_PREFIX="$PREFIX" \
	-DCONF_DIR="$APP_FILES/etc" \
	-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
	-DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,stack-size=8388608" \
	-DAPPS_BUILD=all -DTOOLS_BUILD=all -DSCRIPTS=static -DMODULES=static \
	-DWITH_WARNINGS=0 -DUSE_COREPCH=1 -DUSE_SCRIPTPCH=1 > "$BUILD.cmake.log" 2>&1 ||
	{ tail -50 "$BUILD.cmake.log"; exit 1; }
grep -E "^\s*\+- mod-|Modules" "$BUILD.cmake.log" || true
# shellcheck disable=SC2086
ninja -C "$BUILD" -j "$JOBS" ${NINJA_ARGS:-}
rm -rf "$PREFIX" "$APP_FILES/etc"
DESTDIR=/ ninja -C "$BUILD" install > /dev/null
ccache -s | head -5 || true

# The package: programs, their libraries, MariaDB, SQL and configs.
rm -rf "$STAGE"
pkg="$STAGE/server"
mkdir -p "$pkg/bin" "$pkg/lib" "$pkg/etc/modules" "$pkg/share" "$pkg/src/data"
for prog in worldserver authserver dbimport map_extractor vmap4_extractor vmap4_assembler mmaps_generator; do
	cp "$PREFIX/bin/$prog" "$pkg/bin/"
done
for prog in mariadbd mariadb mariadb-admin mariadb-dump; do
	cp "$(command -v "$prog")" "$pkg/bin/"
done
cp -r /usr/share/mariadb "$pkg/share/mariadb"
cp "$APP_FILES"/etc/*.conf.dist "$pkg/etc/"
cp "$APP_FILES"/etc/modules/*.conf.dist "$pkg/etc/modules/"
# mmaps_generator's settings (passed with --config).
cp "$SRC/src/tools/mmaps_generator/mmaps-config.yaml" "$pkg/etc/"

# SQL, where the servers look for it (SourceDirectory): the core's, the modules' and the
# progression system's brackets.
cp -r "$SRC/data/sql" "$pkg/src/data/sql"
rm -rf "$pkg/src/data/sql/archive" "$pkg/src/data/sql/create"
for dir in "$SRC"/modules/mod-*; do
	name=$(basename "$dir")
	if [ -d "$dir/data/sql" ]; then
		mkdir -p "$pkg/src/modules/$name/data"
		cp -r "$dir/data/sql" "$pkg/src/modules/$name/data/sql"
	fi
	for sql in "$dir"/src/Bracket_*/sql; do
		[ -d "$sql" ] || continue
		rel=${sql#"$dir/"}
		mkdir -p "$pkg/src/modules/$name/$(dirname "$rel")"
		cp -r "$sql" "$pkg/src/modules/$name/$rel"
	done
done

# Every library the programs load, then the programs pointed at them.
ldso=$(ls /lib/ld-musl-*.so.1)
cp "$ldso" "$pkg/lib/"
interp="$PREFIX/lib/$(basename "$ldso")"
copy_deps() {
	ldd "$1" 2>/dev/null | awk '/=>/ { print $3 }' | while read -r lib; do
		[ -f "$lib" ] || continue
		base=$(basename "$lib")
		case "$base" in ld-musl-*|libc.musl-*) continue ;; esac
		[ -f "$pkg/lib/$base" ] && continue
		cp -L "$lib" "$pkg/lib/$base"
		copy_deps "$lib"
	done
}
for prog in "$pkg"/bin/*; do
	copy_deps "$prog"
done
# musl's libc is its loader; programs ask for it as libc.musl-<arch>.so.1.
cp "$ldso" "$pkg/lib/libc.musl-$(uname -m).so.1"
for prog in "$pkg"/bin/*; do
	strip "$prog"
	patchelf --set-interpreter "$interp" --set-rpath '$ORIGIN/../lib' "$prog"
done
for lib in "$pkg"/lib/*.so*; do
	case "$(basename "$lib")" in ld-musl-*|libc.musl-*) continue ;; esac
	strip --strip-unneeded "$lib"
	patchelf --set-rpath '$ORIGIN' "$lib"
done

# An empty MariaDB data folder with the server's databases and user, made here by the same
# MariaDB, so the phone only copies it.
seed="$pkg/mariadb-seed"
mariadb-install-db --no-defaults --auth-root-authentication-method=normal --skip-test-db \
	--user=root --datadir="$seed" > /dev/null
mariadbd --no-defaults --user=root --datadir="$seed" --socket=/tmp/seed.sock --skip-networking \
	--innodb-use-native-aio=0 --innodb-log-file-size=48M > /tmp/seed.log 2>&1 &
for _ in $(seq 60); do mariadb-admin --socket=/tmp/seed.sock ping > /dev/null 2>&1 && break; sleep 1; done
mariadb --socket=/tmp/seed.sock -uroot < /repo/warcraft/server/create-databases.sql
mariadb-admin --socket=/tmp/seed.sock -uroot shutdown
wait
rm -f "$seed"/*.err "$seed"/*.pid "$seed"/aria_log_control.lock

cat > "$pkg/VERSION" <<EOF
core $CORE_COMMIT
playerbot-core $PLAYERBOT_CORE_COMMIT
merged $(git -C "$SRC" rev-parse HEAD)
$(echo "$MODULES" | awk 'NF { print $1, $3 }')
mariadb $(mariadbd --version | awk '{ print $3 }')
alpine $(cat /etc/alpine-release) $(uname -m)
EOF
cp /repo/warcraft/server/ctl.sh "$pkg/ctl.sh"

mkdir -p "$OUT"
tar -C "$STAGE" -cf - server | xz -T0 -6 > "$OUT/server.tar.xz"
du -sh "$pkg" "$OUT/server.tar.xz"
cat "$pkg/VERSION"
