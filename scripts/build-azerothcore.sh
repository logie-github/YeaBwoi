#!/bin/sh
# Builds the World of Warcraft app's private server for this machine's architecture (arm64 on an
# arm64 machine: the phone's) and packs it into warcraft/android/server/server.tar.xz: the slow
# compile (scripts/compile-azerothcore.sh), then the package (scripts/package-azerothcore.sh), each
# in an Alpine (musl) container. CI runs the two separately, so that a change to the packaging
# doesn't compile anything.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/compile-azerothcore.sh"
"$ROOT/scripts/package-azerothcore.sh"
