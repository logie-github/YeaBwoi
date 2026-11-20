// The time of day (the world clock's hour, 0..24) as light: the game's own day cycle for a location (its map light's
// LightData keyframes: assets/locations.json's "dayCycle", made by tools/locations.py), sampled at an hour with the
// keyframes wrapping round midnight, the sun's (by night the moon's) direction moving continuously round the sky, and
// how much a room's own light dims and warms at night. No GL: Scene applies it (location.cpp: Scene::setTimeOfDay).
//
//   dayCycle: {source, keys: [[hour, ambient r g b, direct r g b, sky r g b, fog r g b], ...]}
//     ambient: LightData.AmbientColor, direct: DirectColor (the sun's or moon's light), sky: SkyMiddleColor,
//     fog: SkyFogColor; hour = LightData.Time / 120 (the game counts half-minutes, 2880 a day).
//
// Everything is continuous in the hour (no jump at a keyframe, at midnight or where the sun hands over to the moon):
// daylight_test checks it.
#pragma once

#include <vector>

#include "json.h"
#include "wmath.h"

namespace wc {

struct DayKey {
    float hour = 0;
    float ambient[3] = {0, 0, 0};
    float direct[3] = {0, 0, 0};
    float sky[3] = {0, 0, 0};
    float fog[3] = {0, 0, 0};
};

struct DayCycle {
    std::vector<DayKey> keys;   // sorted by hour, each in [0, 24)
    bool empty() const { return keys.empty(); }
};

// What the cycle gives at an hour.
struct DaySample {
    float ambient[3] = {0, 0, 0};
    float direct[3] = {0, 0, 0};
    float sky[3] = {0, 0, 0};
    float fog[3] = {0, 0, 0};
    V3 lightDir;                // the direction the light travels (WoW's axes: x north, y west, z up), unit length
    float night = 0;            // 0 by day .. 1 by night
    float interior[3] = {1, 1, 1};   // a room's own light (its ambient and baked vertex light) times this
};

// "dayCycle" read (an object with "keys", or the keys' array itself); false when it holds no key.
bool parseDayCycle(const Json& j, DayCycle& out);

// A cycle for places without one: the game's Elwynn Forest and Stormwind light (LightParams 12), the same numbers
// tools/locations.py writes for the Embassy.
const DayCycle& defaultDayCycle();

// The hour brought into [0, 24).
float wrapHour(float hours);

// The keyframes at `hours` (linear between neighbours, wrapping from the last of the day to the first of the next).
void sampleKeys(const DayCycle& c, float hours, DayKey& out);

// The light's direction at `hours`: the sun from the east at 6, high in the south at noon, the west at 18; by night the
// moon, round the north, highest at midnight. Never lower than 10 degrees over the horizon. `frameYawDeg` turns it into
// a location's own frame (world = origin + Rz(yawDeg) * local).
V3 lightDirection(float hours, float frameYawDeg = 0);

// 0 from 7:00 to 18:30, 1 from 21:00 to 4:30, eased between.
float nightAmount(float hours);

// The room's light times this at `hours`: 1 by day; by night darker and warmer, as if lit by its lamps alone.
void interiorTint(float hours, float out[3]);

// Everything above at `hours`.
DaySample sampleDay(const DayCycle& c, float hours, float frameYawDeg = 0);

// ---- the cinematic look: a warm, low-key light laid over the hour's (cinematic mode; Scene::setCinematic)
//
// `strength` 0..1 (0: nothing changes, exactly); `flicker` -1..1, moodFlicker's slow wobble, moves the warm light by a few
// percent as a candle or hearth would. What the engine can do with it: scale and tint the location's light (ambient, sun,
// sky, fog, a room's own baked light), light the people with a warm key from a low side angle and a soft rim from behind
// (the actor shader's one directional light and its rim term), and grade the finished picture (grade.h).

// A slow, smooth wobble in -1..1 (three slow sines, none in step with another): the flicker at `seconds`.
float moodFlicker(float seconds);

// The location's light under the mood: ambient lowered and warmed, the sun's colour warmed, the sky and fog darker, a room's
// own light lower and warmer (with the flicker on it). Each array is changed in place.
void moodScene(float strength, float flicker, float ambient[3], float sun[3], float sky[3], float fog[3], float roomTint[3]);

// A person's light under the mood, from what the room or the sky gives them (`ambient` and the directional `direct`):
// less ambient (deeper shadows), a warm key about as bright as their light was (the key's colour), and a soft rim, warm in
// a room and cool outside. At strength 0: the ambient and direct as given, no rim.
struct MoodActorLight {
    float ambient[3] = {0, 0, 0};
    float key[3] = {0, 0, 0};
    float rim[3] = {0, 0, 0};
};
MoodActorLight moodActor(float strength, float flicker, bool interior, const float ambient[3], const float direct[3]);

// The key's and the rim's directions for a camera at `eye` looking at `target` (Z up): `keyTravel` the way the key's light
// travels (from the camera's right, low, about 20 degrees up, a little in front of the people), `rimToward` toward the rim
// light (behind them, seen from the camera, and above). Both unit length; a camera looking straight down keeps +X ahead.
void moodDirections(const V3& eye, const V3& target, V3& keyTravel, V3& rimToward);

} // namespace wc
