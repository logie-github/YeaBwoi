#!/usr/bin/env bash
# Builds the desktop harness that draws Wifecraft's scenes off-screen (EGL, OpenGL ES 3; on
# Ubuntu: libegl-dev libgles-dev libegl-mesa0). Output: wifecraft/engine-test/build/harness.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
engine="$here/../android/app/src/main/cpp/engine"
mkdir -p "$here/build"
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/harness.cpp" "$engine"/*.cpp \
    -lEGL -lGLESv2 -lz -o "$here/build/harness"
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
# The time of day as light (engine/daylight.cpp: the game's day cycle sampled, continuous over the day, the sun's path, the
# rooms' night): build/daylight_test [LOCATIONS_JSON], exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/daylight_test.cpp" "$engine/daylight.cpp" "$engine/json.cpp" \
    -o "$here/build/daylight_test"
echo "$here/build/daylight_test"
# Imported animations as the engine plays them (engine/boneanim.h: keys, the loop's wrap, the fit to a model's rest pose) and
# the game's layers (M2Model::isLayer, a layer posed over its base): build/boneanim_test [FILES_DIR], exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/boneanim_test.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/boneanim_test"
echo "$here/build/boneanim_test"
# Human scale on the game's models (engine/humanscale.h, anim/HumanScale.kt: the heights, the scales, feet on the floor, a
# partner pose's hands): build/humanscale_test FILES_DIR, exit 0 when all pass (skipped without the human models).
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/humanscale_test.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/humanscale_test"
echo "$here/build/humanscale_test"
# Body parts without GL (engine/bodypart.cpp: the part file, the lower body's measurements, the attach matrix):
# build/bodypart_test [PARTS_DIR [FILES_DIR]], exit 0 when all pass (real part files and the game's men when given).
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/bodypart_test.cpp" "$engine/bodypart.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/bodypart_test"
echo "$here/build/bodypart_test"
# Model parts (engine/modelpart.cpp: the regions cut by bone, the stand-in's cut, the fit on another skeleton, the manifest,
# the version 2 part file): build/modelpart_test [FILES_DIR], exit 0 when all pass (the game's models checked when given);
# and build/modelparts, the desktop cutter (MANIFEST FILES_DIR OUT_DIR [SET...]).
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/modelpart_test.cpp" "$engine/modelpart.cpp" "$engine/bodypart.cpp" "$engine/blp.cpp" "$engine/json.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/modelpart_test"
echo "$here/build/modelpart_test"
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/modelparts.cpp" "$engine/modelpart.cpp" "$engine/bodypart.cpp" "$engine/blp.cpp" "$engine/json.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/modelparts"
echo "$here/build/modelparts"
# The scene film (tools/film/README.md): draws a track the JVM recorder made of the app's live simulation, frame by frame;
# also serves the location's walk tests to the recorder (--serve). build/film.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/film.cpp" "$engine"/*.cpp \
    -lEGL -lGLESv2 -lz -o "$here/build/film"
echo "$here/build/film"
