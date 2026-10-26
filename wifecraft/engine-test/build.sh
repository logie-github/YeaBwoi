#!/usr/bin/env bash
# Builds the desktop harness that draws Wifecraft's scenes off-screen (EGL, OpenGL ES 3; on
# Ubuntu: libegl-dev libgles-dev libegl-mesa0). Output: wifecraft/engine-test/build/harness.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
engine="$here/../android/app/src/main/cpp/engine"
mkdir -p "$here/build"
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/harness.cpp" "$engine"/*.cpp \
    -lEGL -lGLESv2 -o "$here/build/harness"
echo "$here/build/harness"
