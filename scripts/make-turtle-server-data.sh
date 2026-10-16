#!/bin/sh
# Makes Turtle WoW's server data on a computer, pathfinding (mmaps) included, from your own Turtle
# WoW 1.18.1 client, for the app to import (Turtle WoW → Game files → Import zip): the same files
# the phone makes, in a fraction of the phone's hours. Needs Docker (on Linux, or Docker Desktop
# on a Mac or Windows) and a checkout of this repository.
#
#   scripts/make-turtle-server-data.sh /path/to/TurtleWoW [parts]
#
# parts: the folders the zip gets, comma-separated: dbc,maps,vmaps,mmaps (the default, all of
# them), or "mmaps" for pathfinding alone, to add to the data the phone made (the maps and vmaps
# it's made from are made here anyway, and left out of the zip).
#
# The tools are Tortoise-WoW's, built for this computer by scripts/compile-tortoise.sh from the
# commit and patches the app's realm is built from (warcraft/turtle/server-version.env), so what
# they make is the realm's own format: the first run compiles them (from minutes to an hour,
# depending on the computer), later runs reuse them. The client folder is only read. Leaves
# build/turtle-server-data.zip; the app checks every file of it again as it imports it.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/turtle/server-version.env
. "$ROOT/warcraft/turtle/server-version.env"

[ $# -ge 1 ] || { sed -n '2,17p' "$0" >&2; exit 2; }
CLIENT="$(cd "$1" && pwd)"
PARTS="${2:-dbc,maps,vmaps,mmaps}"
for part in $(echo "$PARTS" | tr ',' ' '); do
	case "$part" in
	dbc | maps | vmaps | mmaps) ;;
	*) echo "Not a part of the server's data: $part (dbc, maps, vmaps, mmaps)" >&2; exit 2 ;;
	esac
done
want() { case ",$PARTS," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

# The client: WoW.exe beside Data, and 1.18.1 (7272)'s WoW.exe, else a warning (the app checks the
# DBC files against Turtle's checksums as it imports them).
exe=$(find "$CLIENT" -maxdepth 1 -iname wow.exe | head -1)
data=$(find "$CLIENT" -maxdepth 1 -iname data -type d | head -1)
[ -n "$exe" ] && [ -n "$data" ] || { echo "No WoW.exe and Data folder in $CLIENT: give the Turtle WoW folder" >&2; exit 1; }
expected=$(awk -F'\t' '$1 == "file" && $2 == "WoW.exe" { print $4 }' "$ROOT/warcraft/android/app/src/main/assets/turtle/client-manifest.tsv")
actual=$( (sha256sum "$exe" 2> /dev/null || shasum -a 256 "$exe") | awk '{ print $1 }')
if [ "$actual" != "$expected" ]; then
	echo "Warning: $exe isn't Turtle WoW 1.18.1 (7272)'s, the only client the realm accepts; carrying on." >&2
fi

# The tools, for this computer, from the realm's own commit.
DIST="$ROOT/build/tortoise-dist"
if [ "$(cat "$DIST/commit" 2> /dev/null)" != "$TORTOISE_COMMIT" ] || [ ! -x "$DIST/bin/MoveMapGen" ]; then
	echo "== Building Tortoise-WoW's tools (once)"
	"$ROOT/scripts/compile-tortoise.sh"
fi

echo "== Making $PARTS from $CLIENT"
WORK="$ROOT/build/turtle-server-data.work"
ZIP="$ROOT/build/turtle-server-data.zip"
rm -rf "$WORK" "$ZIP"
mkdir -p "$WORK"
vmaps=0 mmaps=0
if want vmaps || want mmaps; then vmaps=1; fi
if want mmaps; then mmaps=1; fi
# shellcheck disable=SC2086
docker run --rm ${DOCKER_RUN_ARGS:-} -e VMAPS=$vmaps -e MMAPS=$mmaps -e PARTS="$PARTS" \
	-e OWNER="$(id -u):$(id -g)" -e CLIENT_DATA="/client/$(basename "$data")" \
	-v "$ROOT:/repo" -v "$CLIENT:/client:ro" -w /repo/build/turtle-server-data.work \
	"alpine:$ALPINE_VERSION" sh -eu -c '
		# What it makes is yours, whatever happens (the container runs as root).
		trap "chown -R \"\$OWNER\" /repo/build/turtle-server-data.work; [ ! -e ../turtle-server-data.zip ] || chown \"\$OWNER\" ../turtle-server-data.zip" EXIT
		apk add -q --no-progress libstdc++ libgcc zlib libbz2 zip > /dev/null
		export LD_LIBRARY_PATH=/repo/build/tortoise-dist/lib
		tools=/repo/build/tortoise-dist/bin
		echo "-- dbc and maps (minutes)"
		"$tools/mapextractor" -i /client -o . > mapextractor.log 2>&1 || { tail -20 mapextractor.log; exit 1; }
		if [ "$VMAPS" = 1 ]; then
			echo "-- vmaps (minutes more)"
			mkdir -p vmaps
			{ "$tools/vmapextractor" -d "$CLIENT_DATA" && "$tools/vmap_assembler" Buildings vmaps; } > vmaps.log 2>&1 || { tail -20 vmaps.log; exit 1; }
			rm -rf Buildings
		fi
		if [ "$MMAPS" = 1 ]; then
			echo "-- mmaps (pathfinding, on every core: the long one)"
			# With --silent it ends with 1 once it has built everything (tools/mmap/src/generator.cpp).
			code=0
			"$tools/MoveMapGen" --silent --offMeshInput /repo/build/tortoise-dist/etc/offmesh.txt > mmaps.log 2>&1 || code=$?
			[ "$code" -le 1 ] || { tail -20 mmaps.log; exit "$code"; }
		fi
		for part in $(echo "$PARTS" | tr "," " "); do
			[ -n "$(ls -A "$part" 2> /dev/null)" ] || { echo "$part came out empty: see build/turtle-server-data.work/*.log" >&2; exit 1; }
		done
		zip -q -r -1 ../turtle-server-data.zip $(echo "$PARTS" | tr "," " ")
	'
rm -rf "$WORK"
echo "== $ZIP ($(du -h "$ZIP" | cut -f1))"
echo "Copy it to the phone, then in the app: Turtle WoW → Game files → Import zip."
