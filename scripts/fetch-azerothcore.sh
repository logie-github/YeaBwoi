#!/bin/sh
# Checks out the World of Warcraft app's server source into the folder given (default
# build/azerothcore): AzerothCore at the revision ChromieCraft runs (warcraft/server-version.env),
# the playerbots fork's core changes merged onto it, the app's patches (warcraft/patches/azerothcore/),
# the modules at their pinned commits, and the app's own modules (warcraft/modules/: Wrath Remix's).
# Used by scripts/build-azerothcore.sh (the phone's server) and CI's Windows data tools.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/server-version.env
. "$ROOT/warcraft/server-version.env"
SRC="${1:-$ROOT/build/azerothcore}"
# Modules the app's user picked (the module browser; .github/workflows/build-warcraft-modules.yml),
# as server-version.env lists them: "name repository commit" a line.
MODULES="$MODULES
${WOW_EXTRA_MODULES:-}"

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
	# Some modules bring their libraries as submodules.
	[ ! -f "$dir/.gitmodules" ] || git -C "$dir" submodule update -q --init --recursive --depth 1
	# The core changes a module ships for itself (patches/azerothcore-*.patch, e.g. hooks it calls
	# and the stock core lacks): applied to the core when they apply cleanly. When they don't, the
	# module is left as it is and fails its own check, which the module build leaves it out for.
	for hooks in "$dir"/patches/azerothcore-*.patch; do
		[ -f "$hooks" ] || continue
		if git -C "$SRC" apply --ignore-space-change --check "$hooks" 2> /dev/null; then
			git -C "$SRC" apply --ignore-space-change "$hooks"
			echo "$name: applied its core patch $(basename "$hooks")"
		else
			echo "$name: its core patch $(basename "$hooks") doesn't apply to this core" >&2
		fi
	done
	# The app's own changes to the module (warcraft/patches/<module>/).
	set -- "$ROOT"/warcraft/patches/"$name"/*.patch
	if [ -f "$1" ]; then
		git -C "$dir" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am -q "$@"
	fi
done
# The app's own modules, kept in this repository (warcraft/modules/), copied in as they are.
for dir in "$ROOT"/warcraft/modules/*/; do
	[ -d "$dir" ] || continue
	rm -rf "$SRC/modules/$(basename "$dir")"
	cp -r "${dir%/}" "$SRC/modules/"
done
# Every folder in modules/ is a module (the core keeps only files there): those not listed go.
for dir in "$SRC"/modules/*/; do
	[ -d "$dir" ] || continue
	name=$(basename "$dir")
	[ ! -d "$ROOT/warcraft/modules/$name" ] || continue
	echo "$MODULES" | grep -q "^$name " || rm -rf "$dir"
done
echo "AzerothCore $(git -C "$SRC" rev-parse --short HEAD) (ChromieCraft ${CORE_COMMIT%"${CORE_COMMIT#???????}"} + playerbots ${PLAYERBOT_CORE_COMMIT%"${PLAYERBOT_CORE_COMMIT#???????}"}) in $SRC"
