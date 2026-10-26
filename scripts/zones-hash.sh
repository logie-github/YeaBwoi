#!/bin/sh
# The starting areas' package's release tag, from everything that goes into it: cata-zones-<hash>,
# the same here and in CI (.github/workflows/build-warcraft.yml). SHA-256 of the sorted files' SHA-256s.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
Z=warcraft/modules/mod-worgen-goblin
{
	find warcraft/zones-version.env warcraft/server-version.env warcraft/server-data.env \
		"$Z/tools/zones" "$Z/data/zones/files.tsv" "$Z/data/zones/client/dbc/Map.txt" \
		warcraft/modules/mod-skyborne/src/SkyborneFiles.cpp warcraft/modules/mod-skyborne/src/SkyborneFiles.h \
		scripts/package-zones.sh -type f ! -name '*.pyc' | LC_ALL=C sort | while read -r f; do sha256sum "$f" | cut -d' ' -f1; done
} | sha256sum | cut -c1-12 | sed 's/^/cata-zones-/'
