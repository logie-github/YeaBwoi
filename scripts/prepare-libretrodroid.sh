#!/usr/bin/env bash
# Fetches LibretroDroid at the commit pinned in azuredreams/libretrodroid-version.env, with its
# submodules (oboe, libretro-common), and applies azuredreams/patches/libretrodroid/ (the HD-2D
# shader, what SwanStation needs, fast forward's sound). The Azure Dreams and Parasite Eve apps both
# build it from azuredreams/android/libretrodroid/upstream/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../azuredreams/libretrodroid-version.env
source "$ROOT/azuredreams/libretrodroid-version.env"

SRC="$ROOT/azuredreams/android/libretrodroid/upstream"

if [ ! -d "$SRC/.git" ]; then
	mkdir -p "$SRC"
	git -C "$SRC" init -q
	git -C "$SRC" remote add origin "$LIBRETRODROID_REPO"
fi
git -C "$SRC" fetch -q --depth 1 origin "$LIBRETRODROID_COMMIT"
git -C "$SRC" checkout -q --force FETCH_HEAD
git -C "$SRC" clean -qfdx
git -C "$SRC" submodule update -q --init --recursive --depth 1

git -C "$SRC" -c user.name="Azure Dreams" -c user.email="azuredreams@users.noreply.github.com" \
	am -q "$ROOT"/azuredreams/patches/libretrodroid/*.patch
echo "LibretroDroid $(git -C "$SRC" rev-parse --short HEAD) ready in $SRC"
