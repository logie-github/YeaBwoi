#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the archive utility only; game archives are always downloaded by the app.
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
SEVENZIP_COMMIT=5e96a8279489832924056b1fa82f29d5837c9469
NDK_DIR=${ANDROID_NDK_HOME:-${ANDROID_HOME:?}/ndk/24.0.8215888}
LLVM_DIR="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
curl --fail --location --retry 3 "https://github.com/ip7z/7zip/archive/$SEVENZIP_COMMIT.tar.gz" -o "$WORK_DIR/source.tar.gz"
tar -xzf "$WORK_DIR/source.tar.gz" -C "$WORK_DIR"
cd "$WORK_DIR/7zip-$SEVENZIP_COMMIT/CPP/7zip/Bundles/Alone2"
make -f makefile.gcc -j2 \
  CC="$LLVM_DIR/aarch64-linux-android28-clang" CXX="$LLVM_DIR/aarch64-linux-android28-clang++" \
  CFLAGS_WARN_WALL= CXXFLAGS_EXTRA=-DZ7_AFFINITY_DISABLE CFLAGS_BASE2=-DZ7_AFFINITY_DISABLE \
  LIB2=-ldl LDFLAGS_STATIC_3=-static-libstdc++
DEST_DIR="$ROOT_DIR/warcraft/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST_DIR"
cp _o/7zz "$DEST_DIR/libtww7zip.so"
"$LLVM_DIR/llvm-strip" "$DEST_DIR/libtww7zip.so"
"$LLVM_DIR/llvm-readelf" -h "$DEST_DIR/libtww7zip.so"
