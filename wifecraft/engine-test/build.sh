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
# The soft body's maths alone (no GL context, no game files): build/softbody_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/softbody_test.cpp" "$engine/softbody.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -lGLESv2 -o "$here/build/softbody_test"
echo "$here/build/softbody_test"
# The body shapes' geometry alone (engine/bodyshape.cpp: the split, the shape's field, the normals; no GL, no game files):
# build/bodyshape_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/bodyshape_test.cpp" "$engine/bodyshape.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/bodyshape_test"
echo "$here/build/bodyshape_test"
