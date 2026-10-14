#!/bin/sh
# Compiles Turtle WoW's realm for Linux on musl (Alpine), for this machine's architecture:
# Tortoise-WoW (warcraft/turtle/server-version.env) with its map tools, and ACE, which Alpine
# doesn't package, from its release (checked against its SHA-256). Leaves what
# scripts/package-tortoise.sh packs in build/tortoise-dist: the programs, their configs and the SQL.
#
# The slow part: CI caches its output by its inputs (the pins, the patches, this script and the
# fetch script). Built apart from the ChromieCraft realm (build/azerothcore*): nothing is shared.
#
# On a machine with Docker, runs itself in an Alpine container of the machine's architecture
# (GitHub's ubuntu-24.04-arm runners build the phone's arm64). DOCKER_RUN_ARGS adds arguments to
# `docker run` (a proxy, for example). FETCH=0 compiles build/tortoise as it is.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/turtle/server-version.env
. "$ROOT/warcraft/turtle/server-version.env"

if [ -z "${IN_ALPINE:-}" ]; then
	mkdir -p "$ROOT/build"
	# shellcheck disable=SC2086
	exec docker run --rm -e IN_ALPINE=1 -e JOBS="${JOBS:-}" -e FETCH="${FETCH:-1}" -e NINJA_ARGS="${NINJA_ARGS:-}" \
		${DOCKER_RUN_ARGS:-} -v "$ROOT:/repo" -w /repo "alpine:$ALPINE_VERSION" /repo/scripts/compile-tortoise.sh
fi

APP_FILES=/data/data/com.logie.warcraft/files/turtle
PREFIX="$APP_FILES/server"
SRC=/repo/build/tortoise
BUILD=/repo/build/tortoise-build
DIST=/repo/build/tortoise-dist
ARCH=$(uname -m)
ACE_DIR="/repo/build/ace-$ACE_VERSION-$ARCH"
JOBS="${JOBS:-$(nproc)}"

apk add -q --no-progress build-base cmake samurai git ccache curl perl openssl-dev \
	mariadb-connector-c-dev readline-dev bzip2-dev zlib-dev linux-headers
export CCACHE_DIR=/repo/build/ccache-tortoise CCACHE_MAXSIZE=2G
export CCACHE_SLOPPINESS=pch_defines,time_macros,include_file_mtime,include_file_ctime
git config --global --add safe.directory '*'

# A baseline instruction set for the architecture: Tortoise's own flags ask for the build
# machine's (-march=native), which would tie the programs to CI's processor rather than phones'.
case "$ARCH" in
aarch64) MARCH=armv8-a ;;
x86_64) MARCH=x86-64-v2 ;;
*) echo "No baseline -march for $ARCH" >&2; exit 1 ;;
esac

# ACE, once per version, built with its own makefiles in its own tree, which is then $ACE_DIR as
# Tortoise's FindACE expects an ACE_ROOT (ace/ACE.h, lib/libACE.so).
if [ ! -f "$ACE_DIR/lib/libACE.so" ]; then
	rm -rf "$ACE_DIR" "$ACE_DIR.tmp"
	mkdir -p "$ACE_DIR.tmp"
	curl -fsSL --retry 5 -o "$ACE_DIR.tmp/ace.tar.bz2" "$ACE_URL"
	echo "$ACE_SHA256  $ACE_DIR.tmp/ace.tar.bz2" | sha256sum -c -
	tar -C "$ACE_DIR.tmp" -xjf "$ACE_DIR.tmp/ace.tar.bz2"
	mv "$ACE_DIR.tmp/ACE_wrappers" "$ACE_DIR"
	rm -rf "$ACE_DIR.tmp"
	export ACE_ROOT="$ACE_DIR"
	printf '#include "ace/config-linux.h"\n' > "$ACE_ROOT/ace/config.h"
	cat > "$ACE_ROOT/include/makeinclude/platform_macros.GNU" <<EOF
debug=0
optimize=1
inline=1
ssl=0
threads=1
CCFLAGS += -march=$MARCH
include \$(ACE_ROOT)/include/makeinclude/platform_linux.GNU
EOF
	make -s -C "$ACE_ROOT/ace" -j "$JOBS" > "$ACE_DIR.log" 2>&1 || { tail -60 "$ACE_DIR.log"; rm -rf "$ACE_DIR"; exit 1; }
	[ -f "$ACE_ROOT/lib/libACE.so" ] || { echo "ACE built no lib/libACE.so" >&2; rm -rf "$ACE_DIR"; exit 1; }
	# Only the library and its headers are needed from here on.
	rm -rf "$ACE_ROOT/tests" "$ACE_ROOT/examples" "$ACE_ROOT/performance-tests" "$ACE_ROOT/apps" "$ACE_ROOT/protocols" "$ACE_ROOT/netsvcs"
	unset ACE_ROOT
fi

[ "${FETCH:-1}" = 0 ] || sh /repo/scripts/fetch-tortoise.sh "$SRC"
sed -i "s/-march=native/-march=$MARCH/g" "$SRC/CMakeLists.txt"
if grep -q -- '-march=native' "$SRC/CMakeLists.txt"; then
	echo "CMakeLists.txt still asks for -march=native" >&2
	exit 1
fi

# musl gives new threads a 128 KiB stack unless the program asks for more; glibc gives 8 MiB.
cmake -S "$SRC" -B "$BUILD" -G Ninja \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_INSTALL_PREFIX="$PREFIX" \
	-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
	-DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,stack-size=8388608" \
	-DACE_ROOT="$ACE_DIR" \
	-DDEBUG_SYMBOLS=OFF -DUSE_EXTRACTORS=ON -DMODULES=disabled -DUSE_STD_MALLOC=ON \
	-DUSE_DISCORD_BOT=OFF -DENABLE_SOAP=OFF -DALLOW_TURTLE_ADDONS=ON > "$BUILD.cmake.log" 2>&1 ||
	{ tail -60 "$BUILD.cmake.log"; exit 1; }
grep -E "Core revision|Build modules|Memory allocation" "$BUILD.cmake.log" || true
ccache -z > /dev/null
# shellcheck disable=SC2086
ninja -C "$BUILD" -j "$JOBS" ${NINJA_ARGS:-}
ccache -s

# The programs and their configs, installed for the phone's paths, then the SQL.
rm -rf "$DIST" "$DIST.install"
DESTDIR="$DIST.install" ninja -C "$BUILD" install > /dev/null
mkdir -p "$DIST/bin" "$DIST/etc" "$DIST/lib"
for prog in mangosd realmd mapextractor vmapextractor vmap_assembler MoveMapGen; do
	found=$(find "$DIST.install" "$BUILD" -type f -name "$prog" -perm -u+x | head -1)
	[ -n "$found" ] || { echo "$prog wasn't built" >&2; exit 1; }
	cp "$found" "$DIST/bin/"
done
cp "$DIST.install$PREFIX"/etc/mangosd.conf.dist "$DIST.install$PREFIX"/etc/realmd.conf.dist "$DIST/etc/"
cp "$ACE_DIR"/lib/libACE.so* "$DIST/lib/"
rm -rf "$DIST.install"
strip "$DIST"/bin/*
mkdir -p "$DIST/sql"
cp "$SRC/sql/create_databases.sql" "$DIST/sql/"
cp -r "$SRC/sql/base" "$SRC/sql/database_updates" "$DIST/sql/"
cp "$SRC/tools/mmap/mmapSettings.txt" "$SRC/tools/mmap/offmesh.txt" "$DIST/etc/" 2>/dev/null || true
# The pinned commit (the checkout is that plus the app's patches).
echo "$TORTOISE_COMMIT" > "$DIST/commit"
du -sh "$DIST"
