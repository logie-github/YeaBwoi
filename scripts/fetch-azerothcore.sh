#!/bin/sh
# Checks out the World of Warcraft app's server source into the folder given (default
# build/azerothcore): AzerothCore at the revision ChromieCraft runs (warcraft/server-version.env),
# the playerbots fork's core changes merged onto it, the app's patches (warcraft/patches/azerothcore/),
# and the modules at their pinned commits.
# Used by scripts/build-azerothcore.sh (the phone's server) and CI's Windows data tools.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/server-version.env
. "$ROOT/warcraft/server-version.env"
SRC="${1:-$ROOT/build/azerothcore}"

if [ ! -d "$SRC/.git" ]; then
	git init -q "$SRC"
	git -C "$SRC" remote add origin "$CORE_REPO"
	git -C "$SRC" remote add playerbots "$PLAYERBOT_CORE_REPO"
fi
git -C "$SRC" fetch -q --filter=blob:none origin "$CORE_COMMIT"
git -C "$SRC" fetch -q --filter=blob:none playerbots "$PLAYERBOT_CORE_COMMIT"
git -C "$SRC" checkout -q --force "$CORE_COMMIT"
git -C "$SRC" clean -qfdx -e modules/
git -C "$SRC" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" \
	merge -q --no-edit "$PLAYERBOT_CORE_COMMIT"
# The app's own changes to the core (warcraft/patches/azerothcore/).
set -- "$ROOT"/warcraft/patches/azerothcore/*.patch
if [ -f "$1" ]; then
	git -C "$SRC" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am -q "$@"
fi

echo "$MODULES" | while read -r name repo commit; do
	[ -n "$name" ] || continue
	dir="$SRC/modules/$name"
	if [ ! -d "$dir/.git" ]; then
		git init -q "$dir"
		git -C "$dir" remote add origin "$repo"
	fi
	git -C "$dir" fetch -q --depth 1 origin "$commit"
	git -C "$dir" checkout -q --force FETCH_HEAD
	git -C "$dir" clean -qfdx
done
for dir in "$SRC"/modules/mod-*; do
	[ -d "$dir" ] || continue
	echo "$MODULES" | grep -q "^$(basename "$dir") " || rm -rf "$dir"
done
echo "AzerothCore $(git -C "$SRC" rev-parse --short HEAD) (ChromieCraft ${CORE_COMMIT%"${CORE_COMMIT#???????}"} + playerbots ${PLAYERBOT_CORE_COMMIT%"${PLAYERBOT_CORE_COMMIT#???????}"}) in $SRC"
