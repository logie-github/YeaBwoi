#!/bin/sh
# The starting areas' package (build/zones/cata-zones.tar.xz and cata-zones.json, its size and
# SHA-256 for the app): Gilneas, Kezan and the Lost Isles for the Worgen and Goblins, from Cataclysm
# Classic's files, made for 3.3.5a (warcraft/modules/mod-worgen-goblin/tools/zones/package.py).
#
#   WOW_DATA_ZIP=data.zip scripts/package-zones.sh
#
# WOW_DATA_ZIP: the server data the app downloads (warcraft/server-data.env): its dbc folder is
# 3.3.5a's tables. Builds, for this machine, AzerothCore's data tools from the realm's core
# (scripts/fetch-azerothcore.sh) and the converter at its pinned commit (warcraft/zones-version.env).
# The game's files are fetched into build/zones/cache (kept between runs).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../warcraft/zones-version.env
. "$ROOT/warcraft/zones-version.env"
WORK="$ROOT/build/zones"
TOOLS="$WORK/tools"
ZONES="$ROOT/warcraft/modules/mod-worgen-goblin/tools/zones"
mkdir -p "$WORK" "$TOOLS"

# The converter.
if [ ! -x "$WORK/wotlkconv/bin/wotlkconv" ] || [ "$(cat "$WORK/wotlkconv.commit" 2> /dev/null)" != "$WOTLKCONV_COMMIT" ]; then
	rm -rf "$WORK/wotlkconv" "$WORK/wotlkconv-src"
	git init -q "$WORK/wotlkconv-src"
	git -C "$WORK/wotlkconv-src" fetch -q --depth 1 "$WOTLKCONV_REPO" "$WOTLKCONV_COMMIT"
	git -C "$WORK/wotlkconv-src" checkout -q FETCH_HEAD
	python3 -m venv "$WORK/wotlkconv"
	"$WORK/wotlkconv/bin/pip" install -q "$WORK/wotlkconv-src"
	echo "$WOTLKCONV_COMMIT" > "$WORK/wotlkconv.commit"
fi

# AzerothCore's data tools, from the realm's core, for this machine.
if [ ! -x "$TOOLS/mmaps_generator" ]; then
	"$ROOT/scripts/fetch-azerothcore.sh" "$WORK/azerothcore"
	cmake -S "$WORK/azerothcore" -B "$WORK/tools-build" -DCMAKE_BUILD_TYPE=Release -DAPPS_BUILD=none \
		-DTOOLS_BUILD=all -DSCRIPTS=none -DMODULES=none -DWITH_WARNINGS=0 > "$WORK/tools-cmake.log"
	cmake --build "$WORK/tools-build" -j "$(nproc)" --target map_extractor vmap4_extractor vmap4_assembler mmaps_generator \
		> "$WORK/tools-build.log"
	for tool in map_extractor vmap4_extractor vmap4_assembler mmaps_generator; do
		cp "$(find "$WORK/tools-build" -name "$tool" -type f -perm -u+x | head -1)" "$TOOLS/"
	done
	cp "$WORK/azerothcore/src/tools/mmaps_generator/mmaps-config.yaml" "$TOOLS/"
fi
g++ -std=c++17 -O2 -I"$ROOT/warcraft/modules/mod-skyborne/src" "$ZONES/mkmpq.cpp" \
	"$ROOT/warcraft/modules/mod-skyborne/src/SkyborneFiles.cpp" -lz -o "$TOOLS/zones_mkmpq"

# 3.3.5a's tables, from the server data.
rm -rf "$WORK/dbc"
mkdir -p "$WORK/dbc"
unzip -q -j "${WOW_DATA_ZIP:?the server data (data.zip)}" "dbc/LiquidType.dbc" "dbc/GameObjectDisplayInfo.dbc" -d "$WORK/dbc"

python3 "$ZONES/package.py" "$WORK/cache" "$WORK/wotlkconv/bin/wotlkconv" "$TOOLS" "$WORK/dbc" "$WORK/package"

# The package: package/ as cata-zones/ in a tar.xz, and what the app checks it by.
TAG="$("$ROOT/scripts/zones-hash.sh")"
echo "$TAG" > "$WORK/package/TAG"
rm -f "$WORK/cata-zones.tar.xz"
tar -C "$WORK" --transform 's,^package,cata-zones,' --owner=0 --group=0 --sort=name -cf - package |
	xz -T0 -6 > "$WORK/cata-zones.tar.xz"
unpacked=$(du -sb "$WORK/package" | cut -f1)
python3 - "$WORK/cata-zones.tar.xz" "$TAG" "$unpacked" > "$WORK/cata-zones.json" <<'PY'
import hashlib, json, os, sys
path, tag, unpacked = sys.argv[1], sys.argv[2], int(sys.argv[3])
digest = hashlib.sha256()
with open(path, "rb") as f:
    for block in iter(lambda: f.read(1 << 20), b""):
        digest.update(block)
print(json.dumps({"file": "cata-zones.tar.xz", "tag": tag, "sha256": digest.hexdigest(),
                  "size": os.path.getsize(path), "unpacked": unpacked}, indent=1))
PY
cat "$WORK/cata-zones.json"
