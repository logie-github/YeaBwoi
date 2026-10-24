#!/bin/sh
# The Legion realm package's release tag, from everything that goes into it: felsong-server-<hash>,
# the same here and in CI (.github/workflows/build-warcraft.yml), so a package published from one
# machine is the one the other finds already published. SHA-256 of the sorted files' SHA-256s.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
{
	find warcraft/felsong/server-version.env warcraft/felsong/patches warcraft/felsong/server warcraft/server/android_compat.c \
		scripts/fetch-legioncore.sh scripts/compile-legioncore.sh scripts/package-legioncore.sh scripts/test-felsong-server.sh \
		-type f | LC_ALL=C sort | while read -r f; do sha256sum "$f" | cut -d' ' -f1; done
} | sha256sum | cut -c1-12 | sed 's/^/felsong-server-/'
