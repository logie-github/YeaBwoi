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
	# The core changes a module needs and ships for itself (warcraft/module-core-patches.txt: module,
	# then its patch's path in the module): applied to the core when they apply cleanly. A module
	# is listed once its patch is known to be the one for the pinned module (some modules keep old
	# patches in patches/ that mustn't be applied). When it doesn't apply, the module is left as it
	# is and fails its own check, which the module build leaves it out for.
	while read -r mod patch; do
		[ "$mod" = "$name" ] && [ -f "$dir/$patch" ] || continue
		if git -C "$SRC" apply --ignore-space-change --check "$dir/$patch" 2> /dev/null; then
			git -C "$SRC" apply --ignore-space-change "$dir/$patch"
			echo "$name: applied its core patch $patch"
		else
			echo "$name: its core patch $patch doesn't apply to this core" >&2
		fi
	done < "$ROOT/warcraft/module-core-patches.txt"
	# The app's own changes to the module (warcraft/patches/<module>/).
	set -- "$ROOT"/warcraft/patches/"$name"/*.patch
	if [ -f "$1" ]; then
		# A module whose files have CRLF line endings looks modified after the checkout, which
		# git am refuses: the patches are applied as they are then.
		git -C "$dir" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am -q "$@" ||
			{
				git -C "$dir" -c user.name="World of Warcraft" -c user.email="warcraft@users.noreply.github.com" am --abort
				git -C "$dir" apply "$@"
			}
	fi
	# AzerothCore's loader calls Add<folder>Scripts() (the folder's name, - and . as _). The module
	# browser installs a module under its repository's name, which can be longer than the folder the
	# module expects (buildthehomelab's wow-mod-legacy defines Addmod_legacyScripts, for mod-legacy):
	# the entry point is then missing and the world server doesn't link. When the module defines none
	# for its folder but one for a name its folder's name ends with, that entry point is added.
	want=$(echo "$name" | tr -- '-.' '__')
	defined=$(grep -rhoE --include='*.cpp' --include='*.h' \
		'^[[:space:]]*void[[:space:]]+Add[A-Za-z0-9_]+Scripts[[:space:]]*\([[:space:]]*(void)?[[:space:]]*\)[[:space:]]*(\{.*)?$' "$dir" 2> /dev/null |
		sed -E 's/^[[:space:]]*void[[:space:]]+Add([A-Za-z0-9_]+)Scripts.*/\1/' | sort -u || true)
	if [ -n "$defined" ] && ! echo "$defined" | grep -qx "$want"; then
		own=$(echo "$defined" | while read -r fn; do
			case "$want" in *_"$fn") echo "$fn" ;; esac
		done | awk '{ print length, $0 }' | sort -rn | head -n 1 | cut -d' ' -f2-)
		if [ -n "$own" ]; then
			to="$dir/src"
			[ -d "$to" ] || to="$dir"
			cat > "$to/wow_entry_point.cpp" <<EOF
// Added by the app's build (scripts/fetch-azerothcore.sh): AzerothCore calls the entry point for the
// folder this module is installed in ($name); the module's own is Add${own}Scripts.
void Add${own}Scripts();
void Add${want}Scripts() { Add${own}Scripts(); }
EOF
			echo "$name: entry point Add${want}Scripts added, calling the module's Add${own}Scripts"
		fi
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
