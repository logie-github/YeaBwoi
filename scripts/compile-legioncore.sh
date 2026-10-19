#!/bin/sh
# Compiles Felsong's realm for the phone, Linux arm64 on musl (Alpine): LegionCore 7.3.5
# (warcraft/felsong/server-version.env, with the app's patches) with its scripts compiled in, its
# Battle.net login server and its tools (the map, vmap and mmap makers, the connection patcher).
# Leaves what scripts/package-legioncore.sh packs in build/legioncore-dist: the programs, their
# configs, the Battle.net server's certificate and the SQL.
#
# With clang: natively on an arm64 machine, or on any other by cross-compiling into an arm64
# Alpine of the same version (build/sysroot-aarch64, made here from Alpine's own packages), which
# is how it's built while CI can't (FELSONG.md). The slow part (hours): ccache keeps what it can.
# Built apart from the other realms (build/azerothcore*, build/tortoise*): nothing is shared.
#
# On a machine with Docker, runs itself in an Alpine container. DOCKER_RUN_ARGS adds arguments to
# `docker run` (a proxy, for example). FETCH=0 compiles build/legioncore as it is.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/felsong/server-version.env
. "$ROOT/warcraft/felsong/server-version.env"

if [ -z "${IN_ALPINE:-}" ]; then
	mkdir -p "$ROOT/build"
	# shellcheck disable=SC2086
	exec docker run --rm -e IN_ALPINE=1 -e JOBS="${JOBS:-}" -e FETCH="${FETCH:-1}" -e NINJA_ARGS="${NINJA_ARGS:-}" \
		${DOCKER_RUN_ARGS:-} -v "$ROOT:/repo" -w /repo "alpine:$ALPINE_VERSION" /repo/scripts/compile-legioncore.sh
fi

APP_FILES=/data/data/com.logie.warcraft/files/felsong
PREFIX="$APP_FILES/server"
SRC=/repo/build/legioncore
BUILD=/repo/build/legioncore-build
DIST=/repo/build/legioncore-dist
SYSROOT=/repo/build/sysroot-aarch64
JOBS="${JOBS:-$(nproc)}"
# What the programs link against, in the phone's Alpine.
DEPS="musl-dev libstdc++-dev gcc boost-dev openssl-dev mariadb-connector-c-dev readline-dev bzip2-dev zlib-dev ncurses-dev linux-headers"

apk add -q --no-progress cmake samurai git ccache curl perl clang20 lld20 llvm20 alpine-keys
export CCACHE_DIR=/repo/build/ccache-legioncore CCACHE_MAXSIZE=3G
export CCACHE_SLOPPINESS=pch_defines,time_macros,include_file_mtime,include_file_ctime
git config --global --add safe.directory '*'

# musl's C++11 NULL is nullptr (stddef.h, stdio.h, string.h, stdlib.h, time.h, unistd.h, locale.h,
# wchar.h); glibc's is __null, which converts to a number. The core's scripts pass NULL where a
# number is meant in more places than are worth patching one by one (a stack count, a spell id, a
# threat amount...), so in the compiler's headers, used for this build only, NULL is glibc's kind
# of null: 0L. The phone never sees these headers.
integer_null() {
	find "$1" -maxdepth 1 -name '*.h' -exec sed -i 's/define NULL nullptr/define NULL 0L/' {} +
}

TOOLCHAIN=/repo/build/legioncore-toolchain.cmake
if [ "$(uname -m)" = aarch64 ]; then
	# shellcheck disable=SC2086
	apk add -q --no-progress $DEPS
	SYSROOT=
	integer_null /usr/include
	cat > "$TOOLCHAIN" <<EOF
set(CMAKE_C_COMPILER clang)
set(CMAKE_CXX_COMPILER clang++)
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld")
EOF
else
	# The phone's Alpine, its packages unpacked (not run: --no-scripts) for their headers and
	# libraries, checked against Alpine's arm64 signing keys.
	if [ ! -f "$SYSROOT/.made" ] || [ "$(cat "$SYSROOT/.made")" != "$ALPINE_VERSION $DEPS" ]; then
		rm -rf "$SYSROOT"
		# shellcheck disable=SC2086
		apk add -q --no-progress --root "$SYSROOT" --arch aarch64 --initdb --no-scripts \
			--keys-dir /usr/share/apk/keys/aarch64 --repositories-file /etc/apk/repositories $DEPS
		echo "$ALPINE_VERSION $DEPS" > "$SYSROOT/.made"
	fi
	integer_null "$SYSROOT/usr/include"
	cat > "$TOOLCHAIN" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_SYSROOT $SYSROOT)
set(CMAKE_C_COMPILER clang)
set(CMAKE_CXX_COMPILER clang++)
set(CMAKE_C_COMPILER_TARGET aarch64-alpine-linux-musl)
set(CMAKE_CXX_COMPILER_TARGET aarch64-alpine-linux-musl)
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
EOF
fi

[ "${FETCH:-1}" = 0 ] || sh /repo/scripts/fetch-legioncore.sh "$SRC"

# armv8-a, every arm64 phone. musl gives new threads a 128 KiB stack unless the program asks for
# more; glibc gives 8 MiB. Scripts compiled into the world server (static), no git revision.
cmake -S "$SRC" -B "$BUILD" -G Ninja \
	-DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_INSTALL_PREFIX="$PREFIX" \
	-DCMAKE_C_FLAGS="-march=armv8-a" -DCMAKE_CXX_FLAGS="-march=armv8-a" \
	-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
	-DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld -Wl,-z,stack-size=8388608" \
	-DTOOLS=1 -DSCRIPTS=static -DWITHOUT_GIT=1 > "$BUILD.cmake.log" 2>&1 ||
	{ tail -60 "$BUILD.cmake.log"; exit 1; }
ccache -z > /dev/null
# shellcheck disable=SC2086
ninja -C "$BUILD" -j "$JOBS" ${NINJA_ARGS:-}
ccache -s

rm -rf "$DIST" "$DIST.install"
DESTDIR="$DIST.install" ninja -C "$BUILD" install > /dev/null
mkdir -p "$DIST/bin" "$DIST/etc"
for prog in bnetserver worldserver mapextractor vmap4extractor vmap4assembler mmaps_generator connection_patcher; do
	found=$(find "$DIST.install" -type f -name "$prog" | head -1)
	[ -n "$found" ] || { echo "$prog wasn't built" >&2; exit 1; }
	cp "$found" "$DIST/bin/"
done
cp "$(find "$DIST.install" -name bnetserver.conf.dist | head -1)" "$(find "$DIST.install" -name worldserver.conf.dist | head -1)" "$DIST/etc/"
# The Battle.net server's TLS certificate and key, which it reads from its working folder: the
# project's own (TrinityCore's, valid to 2036), which the connection patcher's client trusts.
cp "$SRC/src/server/bnetserver/bnetserver.cert.pem" "$SRC/src/server/bnetserver/bnetserver.key.pem" "$DIST/etc/"
rm -rf "$DIST.install"
llvm-strip "$DIST"/bin/*
# The SQL: the login and characters databases' bases and every database's updates. The world and
# hotfixes bases are the project's release (package-legioncore.sh).
mkdir -p "$DIST/sql"
cp -r "$SRC/sql/base" "$SRC/sql/updates" "$DIST/sql/"
[ ! -d "$SRC/sql/old" ] || cp -r "$SRC/sql/old" "$DIST/sql/"
echo "$LEGIONCORE_COMMIT" > "$DIST/commit"
file "$DIST/bin/worldserver" 2>/dev/null || true
du -sh "$DIST"
