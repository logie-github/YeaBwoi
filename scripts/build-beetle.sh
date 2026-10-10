#!/usr/bin/env bash
# Builds the PlayStation emulator core for the Parasite Eve app: Beetle PSX HW (libretro's port of
# Mednafen's PlayStation emulation) with its OpenGL ES 3 hardware renderer and HD texture
# replacement, for arm64, at the commit pinned in parasiteeve/core-version.env, with
# parasiteeve/patches/beetle/ applied. The library lands in parasiteeve/android/core/arm64-v8a/,
# which the app packages as one of its native libraries.
#
# Needs the Android NDK (ANDROID_NDK_HOME or ANDROID_NDK_ROOT).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../parasiteeve/core-version.env
source "$ROOT/parasiteeve/core-version.env"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -x "$NDK/ndk-build" ]; then
	echo "Set ANDROID_NDK_HOME to the Android NDK" >&2
	exit 1
fi

SRC="${BEETLE_DIR:-$ROOT/build/beetle-psx}"
OUT="$ROOT/parasiteeve/android/core/arm64-v8a"

if [ ! -d "$SRC/.git" ]; then
	mkdir -p "$SRC"
	git -C "$SRC" init -q
	git -C "$SRC" remote add origin "$BEETLE_REPO"
fi
git -C "$SRC" fetch -q --depth 1 origin "$BEETLE_COMMIT"
git -C "$SRC" checkout -q --force FETCH_HEAD
git -C "$SRC" clean -qfdx
git -C "$SRC" -c user.name="Parasite Eve" -c user.email="parasiteeve@users.noreply.github.com" \
	am -q "$ROOT"/parasiteeve/patches/beetle/*.patch

# The hardware renderer with OpenGL ES only: LibretroDroid offers no Vulkan.
"$NDK/ndk-build" -C "$SRC/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-26 HAVE_HW=1 NO_VULKAN=1 \
	-j"$(nproc)" > /dev/null

mkdir -p "$OUT"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$OUT/libbeetle_psx_hw_libretro_android.so" \
	"$SRC/libs/arm64-v8a/libretro.so"
echo "Built $OUT/libbeetle_psx_hw_libretro_android.so ($(git -C "$SRC" rev-parse --short HEAD))"
