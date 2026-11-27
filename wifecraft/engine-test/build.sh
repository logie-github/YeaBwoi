#!/usr/bin/env bash
# Builds the desktop harness that draws Wifecraft's scenes off-screen (EGL, OpenGL ES 3; on
# Ubuntu: libegl-dev libgles-dev libegl-mesa0). Output: wifecraft/engine-test/build/harness.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
engine="$here/../android/app/src/main/cpp/engine"
mkdir -p "$here/build"
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/harness.cpp" "$engine"/*.cpp \
    -lEGL -lGLESv2 -lz -lpthread -o "$here/build/harness"
echo "$here/build/harness"
# The soft body's maths alone (no GL context, no game files): build/softbody_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/softbody_test.cpp" "$engine/softbody.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -lGLESv2 -o "$here/build/softbody_test"
echo "$here/build/softbody_test"
# Soft tissue's lattice and solver alone (engine/tissue.cpp: XPBD Neo-Hookean cells, jiggle, grips, pinch; no GL): build/tissue_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/tissue_test.cpp" "$engine/tissue.cpp" \
    -o "$here/build/tissue_test"
echo "$here/build/tissue_test"
# The body shapes' geometry alone (engine/bodyshape.cpp: the split, the shape's field, the normals; no GL, no game files):
# build/bodyshape_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/bodyshape_test.cpp" "$engine/bodyshape.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/bodyshape_test"
echo "$here/build/bodyshape_test"
# The time of day as light (engine/daylight.cpp: the game's day cycle sampled, continuous over the day, the sun's path, the
# rooms' night; cinematic mode's area profile: identity, limits, continuity, the blend, the JSON, every place at every half
# hour): build/daylight_test [LOCATIONS_JSON], exit 0 when all pass.
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
# The flesh measure (engine/flesh.h, anim/FleshProfile.kt: each bone segment's flesh, front, back and side, from a synthetic body
# with known extents; the packed floats' layout; its cost): build/flesh_test, exit 0 when all pass. No GL, no game files.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/flesh_test.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/flesh_test"
echo "$here/build/flesh_test"
# Cinematic mode's light (engine/daylight.h's profile on the light: strength 0 changes nothing, a warm room lower and warmer, the
# slow flicker only where there is a flame, the key's and
# rim's directions; grade.h's letterbox bars): build/mood_test, exit 0 when all pass. No GL context, no game files.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/mood_test.cpp" "$engine"/*.cpp \
    -lEGL -lGLESv2 -lz -o "$here/build/mood_test"
echo "$here/build/mood_test"
# The tabard flap as cloth (engine/cloth.h: a pinned strip hangs at its length, the body's capsules push it out and keep a
# front piece in front, a lap keeps it on top, the strain limit, the twist cone, the tabard's panel, the same frames give
# the same cloth, a teleport hangs it again; the real flap's rig and texture rectangle when given the files):
# build/cloth_test [FILES_DIR], exit 0 when all pass. No GL.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/cloth_test.cpp" "$engine/cloth.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/cloth_test"
echo "$here/build/cloth_test"
# The cloth on a real look over an animation (build/cloth_check FILES_DIR LOOKFILE ANIM_ID FRAMES [STEP_MS]): per frame the
# free cloth points inside the skinned body, the stretch, the narrowest row's width, the swing and the cost; exit 0 when
# none is inside, the stretch stays at or under 10% and no row narrows below 60%; CLOTH_DUMP=FILE writes the last frame's
# body, cloth and capsules. No GL.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/cloth_check.cpp" "$engine/cloth.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/cloth_check"
echo "$here/build/cloth_check"
# The body's additive motion and the atmosphere's motes (engine/motionlayer.h: breathing by intensity, the look-at's limits,
# the clip's weight on the head; engine/particles.h: seeded, bounded): build/motionlayer_test [FILES_DIR], exit 0 when all
# pass; with the game's files, measured on the human woman and man standing (cm moved, look-at angles). No GL.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -DWC_NO_GL -I"$engine" \
    "$here/motionlayer_test.cpp" "$engine/motionlayer.cpp" "$engine/particles.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/motionlayer_test"
echo "$here/build/motionlayer_test"
# The creator's sculpt sliders and tap-to-edit body pick (engine/sculpt.h, engine/bodypick.h) on a made-up symmetric body:
# bounded, symmetric, no triangle turned over, the neck and ankle rings kept, each slider doing what it says, the game's
# bodies' path (every slider, the neck, one side's balance, the same twice); the bone regions and the ray pick; the
# skeleton's edit (engine/bodyrig.h: Leg Length and the HD figure's own rig). With FILES_DIR MODEL... also the game's
# default bodies (every slider at both ends: bounded, none turned over, symmetric skin, head/hands/feet kept, no seam opening
# between geosets, a robe following the skin, the cost). build/sculpt_test [FILES_DIR MODEL...], exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/sculpt_test.cpp" "$engine/sculpt.cpp" "$engine/bodypick.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/sculpt_test"
echo "$here/build/sculpt_test"
# Cinematic mode's zone mood (engine/daylight.h ZoneLight, cineProfile's zone part: the zone's own light from the game's
# ZoneLight/LightData, tools/zone_light.py): every place's zone read and continuous, zone mood 0 changing nothing, the limits,
# skin kept skin, Tirisfal's mauve and teal: build/zonelight_test LOCATIONS_JSON [ZONE_LIGHT_JSON], exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/zonelight_test.cpp" "$engine/daylight.cpp" "$engine/json.cpp" \
    -o "$here/build/zonelight_test"
echo "$here/build/zonelight_test"
# HD Models (engine/hdbody.cpp: the .whd read and written back, the fit on the game's model, the weights, the game's poses
# skinned on the CPU): build/hdbody_test SET.whd FILES_DIR MODEL_FILE_ID, exit 0 when all pass (needs a converted set:
# tools/hd/whd_convert.py, and the model's files).
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/hdbody_test.cpp" "$engine/hdbody.cpp" "$engine/png.cpp" "$engine/softbody.cpp" "$engine/modelpart.cpp" "$engine/bodypart.cpp" \
    "$engine/blp.cpp" "$engine/json.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" "$engine/sculpt.cpp" -lGLESv2 -lz -lpthread -o "$here/build/hdbody_test"
echo "$here/build/hdbody_test"
# The bone layer and the reach (engine/bonelayer.h: a layer's keys sampled with the loop's wrap, a two-bone arm put on a point,
# out of reach it stops short; no GL, no game files): build/bonelayer_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/bonelayer_test.cpp" "$engine/m2.cpp" "$engine/bytes.cpp" \
    -o "$here/build/bonelayer_test"
echo "$here/build/bonelayer_test"
# The interactable sparkles' maths (engine/sparkle.h: motes over a footprint, rising and fading, the density and a spot's
# strength; no GL, no game files): build/sparkle_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -DWC_NO_GL -I"$engine" \
    "$here/sparkle_test.cpp" "$engine/sparkle.cpp" \
    -o "$here/build/sparkle_test"
echo "$here/build/sparkle_test"
# The rooms against their people (README, "Character size"): each place's ceiling, doorways and furniture heights from its wall
# triangles and locations.json: build/scale_measure FILES_DIR LOCATIONS_JSON [--only=ID,ID] (needs GL like the harness).
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/scale_measure.cpp" "$engine"/*.cpp \
    -lEGL -lGLESv2 -lz -lpthread -o "$here/build/scale_measure"
echo "$here/build/scale_measure"
# Procedural stockings and their fabric (engine/stockings.cpp: the shell from the leg triangles, the clip, the band, the weave; engine/stockfabric.cpp:
# the pull, the tension, the tear, undo, repair; on a synthetic body, no GL, no game files): build/stockings_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/stockings_test.cpp" "$engine/stockings.cpp" "$engine/stockfabric.cpp" "$engine/bodypick.cpp" \
    -o "$here/build/stockings_test"
echo "$here/build/stockings_test"
# Flesh contact's maths (engine/contact.h: the dent's falloff, the bulge ring's volume, the springs at 60 and 20 fps, the cap and merge, the
# derivation from proxies, the pick's regions): build/contact_test, exit 0 when all pass. No GL, no game files.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/contact_test.cpp" "$engine/contact.cpp" \
    -o "$here/build/contact_test"
echo "$here/build/contact_test"
# The pinch's maths (engine/contact.h: the profile, its volume, the spring, the tracker's pairing): build/pinch_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/pinch_test.cpp" "$engine/contact.cpp" \
    -o "$here/build/pinch_test"
echo "$here/build/pinch_test"
# Spring-bone jiggle (engine/springbone.cpp: the chains' dynamics, the weights; no GL): build/springbone_test, exit 0 when all pass.
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -I"$engine" \
    "$here/springbone_test.cpp" "$engine/springbone.cpp" \
    -o "$here/build/springbone_test"
echo "$here/build/springbone_test"
