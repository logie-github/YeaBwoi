#!/bin/sh
# Checks out Felsong's realm source into the folder given (default build/legioncore): LegionCore
# 7.3.5 at the commit pinned in warcraft/felsong/server-version.env, with the app's patches
# (warcraft/felsong/patches/). Used by scripts/compile-legioncore.sh. Nothing here touches the
# other realms' sources (build/azerothcore, build/tortoise).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/felsong/server-version.env
. "$ROOT/warcraft/felsong/server-version.env"
SRC="${1:-$ROOT/build/legioncore}"

if [ ! -d "$SRC/.git" ]; then
	git init -q "$SRC"
	git -C "$SRC" remote add origin "$LEGIONCORE_REPO"
fi
git -C "$SRC" fetch -q --depth 1 origin "$LEGIONCORE_COMMIT"
git -C "$SRC" checkout -q --force "$LEGIONCORE_COMMIT"
git -C "$SRC" clean -qfdx
[ ! -f "$SRC/.gitmodules" ] || git -C "$SRC" submodule update -q --init --recursive --depth 1
set -- "$ROOT"/warcraft/felsong/patches/*.patch
if [ -f "$1" ]; then
	git -C "$SRC" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am -q "$@"
fi
echo "LegionCore $(git -C "$SRC" rev-parse --short HEAD) (pinned ${LEGIONCORE_COMMIT%"${LEGIONCORE_COMMIT#???????}"}) in $SRC"
