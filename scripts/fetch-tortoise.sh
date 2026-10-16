#!/bin/sh
# Checks out Turtle WoW's realm source into the folder given (default build/tortoise): Tortoise-WoW
# at the commit pinned in warcraft/turtle/server-version.env, with the app's patches
# (warcraft/turtle/patches/), the TortoiseBots module in its modules/TortoiseBots (with the app's
# patches for it, warcraft/turtle/patches/tortoisebots/), and the TortoiseBotsManager addon beside
# it (<folder>-addon). Used by scripts/compile-tortoise.sh. Nothing here touches the ChromieCraft
# realm's source (scripts/fetch-azerothcore.sh, build/azerothcore).
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
# A pinned commit of another repository in a folder, with the app's patches for it, if any.
checkout() { # folder repository commit patches-folder
	if [ ! -d "$1/.git" ]; then
		git init -q "$1"
		git -C "$1" remote add origin "$2"
	fi
	git -C "$1" fetch -q --depth 1 origin "$3"
	git -C "$1" checkout -q --force "$3"
	git -C "$1" clean -qfdx
	set -- "$1" "$4"/*.patch
	folder=$1
	shift
	if [ -f "$1" ]; then
		git -C "$folder" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am -q "$@"
	fi
}
checkout "$SRC/modules/TortoiseBots" "$TORTOISEBOTS_REPO" "$TORTOISEBOTS_COMMIT" "$ROOT/warcraft/turtle/patches/tortoisebots"
checkout "$SRC-addon" "$TBM_REPO" "$TBM_COMMIT" "$ROOT/warcraft/turtle/patches/tortoisebotsmanager"
echo "Tortoise-WoW $(git -C "$SRC" rev-parse --short HEAD) (pinned ${TORTOISE_COMMIT%"${TORTOISE_COMMIT#???????}"}) in $SRC"
echo "TortoiseBots $(git -C "$SRC/modules/TortoiseBots" rev-parse --short HEAD), TortoiseBotsManager $(git -C "$SRC-addon" rev-parse --short HEAD)"
