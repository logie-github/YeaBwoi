#!/bin/sh
# Checks out Turtle WoW's realm source into the folder given (default build/tortoise): Tortoise-WoW
# at the commit pinned in warcraft/turtle/server-version.env, with the app's patches
# (warcraft/turtle/patches/). Used by scripts/compile-tortoise.sh. Nothing here touches the
# ChromieCraft realm's source (scripts/fetch-azerothcore.sh, build/azerothcore).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/turtle/server-version.env
. "$ROOT/warcraft/turtle/server-version.env"
SRC="${1:-$ROOT/build/tortoise}"

if [ ! -d "$SRC/.git" ]; then
	git init -q "$SRC"
	git -C "$SRC" remote add origin "$TORTOISE_REPO"
fi
git -C "$SRC" fetch -q --depth 1 origin "$TORTOISE_COMMIT"
git -C "$SRC" checkout -q --force "$TORTOISE_COMMIT"
git -C "$SRC" clean -qfdx
# Its libraries that are submodules (none at this commit; kept for newer pins).
[ ! -f "$SRC/.gitmodules" ] || git -C "$SRC" submodule update -q --init --recursive --depth 1
# The app's own changes (warcraft/turtle/patches/).
set -- "$ROOT"/warcraft/turtle/patches/*.patch
if [ -f "$1" ]; then
	git -C "$SRC" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am -q "$@"
fi
echo "Tortoise-WoW $(git -C "$SRC" rev-parse --short HEAD) (pinned ${TORTOISE_COMMIT%"${TORTOISE_COMMIT#???????}"}) in $SRC"
