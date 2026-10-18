#!/bin/sh
# Builds Warcraft I and II for the Warcraft app, native on Android (arm64): the Stratagus engine
# (warcrafts/stratagus-version.env) as SDL's libmain.so, War1gus's and Wargus's data tools as
# programs (shipped as libwar1tool.so and libwartool.so, which Android lets the app run),
# innoextract for GOG's installers (libinnoextract.so, with Boost and liblzma), SDL's Java
# activity, and the two games' scripts. Into warcrafts/android/stratagus (git-ignored):
#
#   jniLibs/arm64-v8a/   libmain.so libwartool.so libwar1tool.so libinnoextract.so
#   java/                org/libsdl/app/*.java (SDL's, for the engine's screen)
#   assets/stratagus/    war1gus.zip wargus.zip (scripts, campaigns, maps, contrib, shaders)
#
# STRATAGUS_WORK is where to keep the sources and builds (default build/stratagus-android).
# ANDROID_NDK_ROOT, else $ANDROID_HOME/ndk/<STRATAGUS_NDK>, is the NDK.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcrafts/stratagus-version.env
. "$ROOT/warcrafts/stratagus-version.env"
WORK="${STRATAGUS_WORK:-$ROOT/build/stratagus-android}"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}/ndk/$STRATAGUS_NDK}"
OUT="$ROOT/warcrafts/android/stratagus"
JOBS="$(nproc 2> /dev/null || echo 4)"
mkdir -p "$WORK"

fetch() { # repo commit dir
	if [ ! -d "$3/.git" ]; then
		git init -q "$3"
		git -C "$3" remote add origin "$1"
	fi
	git -C "$3" remote set-url origin "$1"
	git -C "$3" cat-file -e "$2^{commit}" 2> /dev/null || git -C "$3" fetch -q --depth 1 origin "$2"
	git -C "$3" checkout -q --force "$2"
	git -C "$3" clean -qfdx
}

fetch "$STRATAGUS_REPO" "$STRATAGUS_COMMIT" "$WORK/stratagus"
git -C "$WORK/stratagus" submodule update -q --init --depth 1 --force third-party
git -C "$WORK/stratagus/third-party" clean -qfdx
fetch "$WARGUS_REPO" "$WARGUS_COMMIT" "$WORK/wargus"
fetch "$WAR1GUS_REPO" "$WAR1GUS_COMMIT" "$WORK/war1gus"

for patch in "$ROOT"/warcrafts/patches/stratagus/*.patch; do
	git -C "$WORK/stratagus" apply "$patch"
done
for patch in "$ROOT"/warcrafts/patches/stratagus-deps/*.patch; do
	git -C "$WORK/stratagus/third-party" apply "$patch"
done

# tolua++ for this machine: it writes the engine's Lua bindings during the build.
cmake -S "$WORK/stratagus/third-party/lua-5.1.5" -B "$WORK/host-lua" -DCMAKE_BUILD_TYPE=Release > /dev/null
make -C "$WORK/host-lua" -j"$JOBS" toluapp > /dev/null

android() { # source build [cmake args...]
	src="$1"; build="$2"; shift 2
	cmake -S "$src" -B "$build" -G "Unix Makefiles" \
		-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
		-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static \
		-DCMAKE_BUILD_TYPE=Release "$@" > "$build.cmake.log"
}

mkdir -p "$WORK/engine" "$WORK/tools"
android "$WORK/stratagus" "$WORK/engine" \
	-DBUILD_VENDORED_LUA=ON -DBUILD_VENDORED_SDL=ON -DBUILD_VENDORED_MEDIA_LIBS=ON \
	-DSTRATAGUS_HOST_TOLUAPP="$WORK/host-lua/toluapp" \
	-DWITH_STACKTRACE=OFF -DWITH_OPENMP=OFF -DBUILD_TESTING=OFF -DENABLE_USEGAMEDIR=ON
make -C "$WORK/engine" -j"$JOBS" stratagus > "$WORK/engine.make.log" || { tail -40 "$WORK/engine.make.log"; exit 1; }

android "$ROOT/warcrafts/stratagus/tools" "$WORK/tools" \
	-DSTRATAGUS_SRC="$WORK/stratagus" -DSTRATAGUS_BUILD="$WORK/engine" \
	-DSTRATAGUS_INCLUDE_DIR="$WORK/stratagus/gameheaders" \
	-DWARGUS_SRC="$WORK/wargus" -DWAR1GUS_SRC="$WORK/war1gus" -DSTORM_SKIP_INSTALL=ON \
	-DZLIB_INCLUDE_DIR="$WORK/engine/zlib/src/zlib-build" -DZLIB_LIBRARY="$WORK/engine/zlib/src/zlib-build/libz.a" \
	-DBZIP2_INCLUDE_DIR="$WORK/stratagus/third-party/bzip2" -DBZIP2_LIBRARIES="$WORK/engine/bzip2/src/bzip2-build/libbz2.a"
make -C "$WORK/tools" -j"$JOBS" wartool war1tool > "$WORK/tools.make.log" || { tail -40 "$WORK/tools.make.log"; exit 1; }

# innoextract, for GOG's installers: liblzma and Boost for Android first (zlib and bzip2 are the
# engine build's), from archives checked against their SHA-256.
download() { # url sha256 file
	if [ ! -f "$3" ] || ! echo "$2  $3" | sha256sum -c - > /dev/null 2>&1; then
		curl -fsSL -o "$3" "$1"
		echo "$2  $3" | sha256sum -c - > /dev/null || { echo "$3: wrong SHA-256" >&2; exit 1; }
	fi
}
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
ZLIB_DIR="$WORK/engine/zlib/src/zlib-build"
BZIP2_DIR="$WORK/engine/bzip2/src/bzip2-build"
BZIP2_INC="$WORK/stratagus/third-party/bzip2"
download "$XZ_URL" "$XZ_SHA256" "$WORK/xz.tar.gz"
download "$BOOST_URL" "$BOOST_SHA256" "$WORK/boost.tar.bz2"
rm -rf "$WORK/xz" "$WORK/boost" "$WORK/inno-prefix"
mkdir -p "$WORK/xz" "$WORK/boost"
tar -xzf "$WORK/xz.tar.gz" -C "$WORK/xz" --strip-components 1
tar -xjf "$WORK/boost.tar.bz2" -C "$WORK/boost" --strip-components 1
android "$WORK/xz" "$WORK/xz-build" -DBUILD_SHARED_LIBS=OFF -DXZ_NLS=OFF -DXZ_DOC=OFF \
	-DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF -DXZ_TOOL_LZMAINFO=OFF -DXZ_TOOL_SCRIPTS=OFF \
	-DCMAKE_INSTALL_PREFIX="$WORK/inno-prefix"
make -C "$WORK/xz-build" -j"$JOBS" install > "$WORK/xz.make.log" || { tail -40 "$WORK/xz.make.log"; exit 1; }
(
	cd "$WORK/boost"
	./bootstrap.sh --with-libraries=filesystem,iostreams,program_options,system,date_time > "$WORK/boost.bootstrap.log"
	echo "using clang : android : $TOOLCHAIN/aarch64-linux-android26-clang++ : <archiver>$TOOLCHAIN/llvm-ar <ranlib>$TOOLCHAIN/llvm-ranlib <cxxflags>-fPIC <cflags>-fPIC ;" > android-config.jam
	./b2 -j"$JOBS" --user-config=android-config.jam toolset=clang-android target-os=android \
		architecture=arm address-model=64 binary-format=elf abi=aapcs link=static runtime-link=static \
		threading=multi variant=release cxxstd=17 \
		--with-filesystem --with-iostreams --with-program_options --with-system --with-date_time \
		-sNO_LZMA=1 -sNO_ZSTD=1 -sZLIB_INCLUDE="$ZLIB_DIR" -sZLIB_LIBRARY_PATH="$ZLIB_DIR" -sZLIB_NAME=z \
		-sBZIP2_INCLUDE="$BZIP2_INC" -sBZIP2_LIBRARY_PATH="$BZIP2_DIR" -sBZIP2_NAME=bz2 \
		--prefix="$WORK/inno-prefix" install > "$WORK/boost.b2.log"
) || { tail -40 "$WORK/boost.b2.log"; exit 1; }
fetch "$INNOEXTRACT_REPO" "$INNOEXTRACT_COMMIT" "$WORK/innoextract"
android "$WORK/innoextract" "$WORK/innoextract-build" \
	-DUSE_STATIC_LIBS=ON -DWITH_CONV=builtin -DBUILD_TESTS=OFF \
	-DCMAKE_FIND_ROOT_PATH="$WORK/inno-prefix" -DBoost_ROOT="$WORK/inno-prefix" -DBoost_NO_SYSTEM_PATHS=ON \
	-DBoost_USE_STATIC_RUNTIME=ON \
	-DLZMA_INCLUDE_DIR="$WORK/inno-prefix/include" -DLZMA_LIBRARY="$WORK/inno-prefix/lib/liblzma.a" \
	-DZLIB_INCLUDE_DIR="$ZLIB_DIR" -DZLIB_LIBRARY="$ZLIB_DIR/libz.a" \
	-DBZIP2_INCLUDE_DIR="$BZIP2_INC" -DBZIP2_LIBRARIES="$BZIP2_DIR/libbz2.a" -DBZIP2_LIBRARY="$BZIP2_DIR/libbz2.a" \
	"-DCMAKE_EXE_LINKER_FLAGS=-L$BZIP2_DIR -L$ZLIB_DIR"
make -C "$WORK/innoextract-build" -j"$JOBS" innoextract > "$WORK/innoextract.make.log" || { tail -40 "$WORK/innoextract.make.log"; exit 1; }

strip="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
rm -rf "$OUT"
mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/java" "$OUT/assets/stratagus"
"$strip" -o "$OUT/jniLibs/arm64-v8a/libmain.so" "$WORK/engine/libmain.so"
"$strip" -o "$OUT/jniLibs/arm64-v8a/libwartool.so" "$WORK/tools/wartool"
"$strip" -o "$OUT/jniLibs/arm64-v8a/libwar1tool.so" "$WORK/tools/war1tool"
"$strip" -o "$OUT/jniLibs/arm64-v8a/libinnoextract.so" "$WORK/innoextract-build/innoextract"
cp -r "$WORK/stratagus/third-party/SDL/android-project/app/src/main/java/org" "$OUT/java/"

# The games' own files, which the tools' data goes beside.
for game in war1gus wargus; do
	(cd "$WORK/$game" && zip -qr -X "$OUT/assets/stratagus/$game.zip" scripts campaigns maps contrib shaders)
done

{
	echo "stratagus $STRATAGUS_COMMIT"
	echo "wargus $WARGUS_COMMIT"
	echo "war1gus $WAR1GUS_COMMIT"
	echo "innoextract $INNOEXTRACT_COMMIT"
} > "$OUT/assets/stratagus/VERSION"
ls -la "$OUT/jniLibs/arm64-v8a" "$OUT/assets/stratagus"
