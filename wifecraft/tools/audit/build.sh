#!/usr/bin/env bash
# Builds the placement audit (engine-test/audit.cpp, tools/audit/README.md): wifecraft/engine-test/build/audit.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
test="$here/../../engine-test"
engine="$here/../../android/app/src/main/cpp/engine"
mkdir -p "$test/build"
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" "$test/audit.cpp" "$engine"/*.cpp -lEGL -lGLESv2 -lz -o "$test/build/audit"
echo "$test/build/audit"
