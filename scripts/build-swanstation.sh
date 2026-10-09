#!/usr/bin/env bash
# Builds the PlayStation emulator core for the Azure Dreams app: SwanStation's libretro core (a
# GPLv3 fork of DuckStation that draws with OpenGL ES, so it can render at several times the
# PlayStation's resolution), for arm64, at the commit pinned in azuredreams/core-version.env, with
# azuredreams/patches/swanstation/ applied (the scene's depth for 2D-HD). The library lands in
# azuredreams/android/core/arm64-v8a/, which the app packages as one of its native libraries.
#
# Needs the Android NDK (ANDROID_NDK_HOME or ANDROID_NDK_ROOT), CMake and Ninja (CMAKE and NINJA
# can point at the ones in the Android SDK).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../azuredreams/core-version.env
source "$ROOT/azuredreams/core-version.env"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
	echo "Set ANDROID_NDK_HOME to the Android NDK" >&2
	exit 1
fi
CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-ninja}"

SRC="${SWANSTATION_DIR:-$ROOT/build/swanstation}"
OUT="$ROOT/azuredreams/android/core/arm64-v8a"

if [ ! -d "$SRC/.git" ]; then
	mkdir -p "$SRC"
	git -C "$SRC" init -q
	git -C "$SRC" remote add origin "$SWANSTATION_REPO"
fi
git -C "$SRC" fetch -q --depth 1 origin "$SWANSTATION_COMMIT"
git -C "$SRC" checkout -q --force FETCH_HEAD
git -C "$SRC" clean -qfdx
git -C "$SRC" -c user.name="Azure Dreams" -c user.email="azuredreams@users.noreply.github.com" \
	am -q "$ROOT"/azuredreams/patches/swanstation/*.patch

"$CMAKE" -S "$SRC" -B "$SRC/build-android" -G Ninja -DCMAKE_MAKE_PROGRAM="$(command -v "$NINJA")" \
	-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release > /dev/null
"$NINJA" -C "$SRC/build-android" -j"$(nproc)" > /dev/null

mkdir -p "$OUT"
# The app's old core, from before SwanStation.
rm -f "$OUT/libpcsx_rearmed_libretro_android.so"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$OUT/libswanstation_libretro_android.so" \
	"$SRC/build-android/swanstation_libretro_android.so"
echo "Built $OUT/libswanstation_libretro_android.so ($(git -C "$SRC" rev-parse --short HEAD))"
