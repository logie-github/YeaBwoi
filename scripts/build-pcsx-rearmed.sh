#!/usr/bin/env bash
# Builds the PlayStation emulator core for the Azure Dreams app: PCSX ReARMed's libretro core, for
# arm64, at the commit pinned in azuredreams/core-version.env. The library lands in
# azuredreams/android/core/arm64-v8a/, which the app packages as one of its native libraries.
#
# Needs the Android NDK: set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../azuredreams/core-version.env
source "$ROOT/azuredreams/core-version.env"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -x "$NDK/ndk-build" ]; then
	echo "Set ANDROID_NDK_HOME to the Android NDK" >&2
	exit 1
fi

SRC="${PCSX_REARMED_DIR:-$ROOT/build/pcsx_rearmed}"
OUT="$ROOT/azuredreams/android/core/arm64-v8a"

if [ ! -d "$SRC/.git" ]; then
	mkdir -p "$SRC"
	git -C "$SRC" init -q
	git -C "$SRC" remote add origin "$PCSX_REARMED_REPO"
fi
git -C "$SRC" fetch -q --depth 1 origin "$PCSX_REARMED_COMMIT"
git -C "$SRC" checkout -q --force FETCH_HEAD
git -C "$SRC" clean -qfdx

"$NDK/ndk-build" -C "$SRC/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-26 -j"$(nproc)"

mkdir -p "$OUT"
cp "$SRC/libs/arm64-v8a/libretro.so" "$OUT/libpcsx_rearmed_libretro_android.so"
echo "Built $OUT/libpcsx_rearmed_libretro_android.so ($(git -C "$SRC" rev-parse --short HEAD))"
