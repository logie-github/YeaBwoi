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

} // namespace wc
