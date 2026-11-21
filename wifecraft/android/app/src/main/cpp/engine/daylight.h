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

#include <cstdint>
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

// ---- the zone's own light (cinematic mode's zone mood)
//
// The light the game itself draws at the place, by the hour (assets/zone_light.json, made by tools/zone_light.py; the
// renderer reads the copy in the location's "cinematic": {"zone": {...}}): the zone's ZoneLight (else a point Light's
// falloff, else the map's default) and its LightParams' LightData keyframes. Tirisfal's is a mauve ambient, a dim blue
// moon-like sun, a dark teal sky and a deep purple fog all day; Teldrassil's violet; Durotar's dusty red. Cinematic mode
// pulls the location's light, its fog and its grade toward it (cineProfile's zone part, by the "Zone mood" option).
//
//   zone: {source, glow, keys: [[hour, ambient rgb, direct rgb, sky top rgb, sky middle rgb, fog rgb, end fog rgb,
//                                fog near, fog far (yards), sun rgb, cloud rgb, sky band 1 rgb, sky band 2 rgb,
//                                horizon ambient rgb, ground ambient rgb], ...]}
//   (the last six bands are optional: a key of 21 numbers leaves them black, and the look then works from the first six;
//   sun: the sun's disc and glow (SunColor; Tirisfal's a pale green), cloud: the clouds' sunlit colour (CloudSunColor; a
//   deep green there), the bands: the sky between the middle and the fog (SkyBand1Color, SkyBand2Color), horizon and ground
//   ambient: the ambient from the side and from below (HorizonAmbientColor, GroundAmbientColor))
struct ZoneKey {
    float hour = 0;
    float ambient[3] = {0, 0, 0};
    float direct[3] = {0, 0, 0};
    float skyTop[3] = {0, 0, 0};
    float skyMid[3] = {0, 0, 0};
    float fog[3] = {0, 0, 0};
    float endFog[3] = {0, 0, 0};
    float fogNear = 0, fogFar = 500;
    float sun[3] = {0, 0, 0};
    float cloud[3] = {0, 0, 0};
    float band1[3] = {0, 0, 0};
    float band2[3] = {0, 0, 0};
    float horizonAmbient[3] = {0, 0, 0};
    float groundAmbient[3] = {0, 0, 0};
};

struct ZoneLight {
    std::vector<ZoneKey> keys;   // sorted by hour, each in [0, 24)
    float glow = 0;
    bool empty() const { return keys.empty(); }
};

// "zone" read (an object with "keys"); false when it holds no key (a key with fewer than 21 numbers is skipped).
bool parseZoneLight(const Json& j, ZoneLight& out);
// The keys at `hours` (linear between neighbours, wrapping round midnight; negative hours: noon's). Empty: all zero.
void sampleZone(const ZoneLight& z, float hours, ZoneKey& out);

// ---- the cinematic look: the area's own light, made low-key (cinematic mode; Scene::setCinematic)
//
// Each place keeps its own mood: the look is a profile worked out from the place's light at the hour (cineProfile: the map
// light's ambient, sun or moon, sky and fog; a room's own ambient and baked light; the practical lights standing near:
// candles, torches, hearths, lanterns, crystals), never one fixed warm look laid over all of them. A cold room stays cold
// (its key keeps the room's hue, a candle behind gives only a warm rim and a flicker), a warm inn stays warm (the key takes
// the flames' colour), a daylit exterior gets a sun key and a soft grade, a night exterior a moon key and a deeper one.
// What the engine does with it: scale and tint the location's light (ambient, sun, sky, fog, a room's own baked light),
// light the people with a key from a low side angle (biased toward the sun or the practicals) and a rim from behind (the
// actor shader's one directional light and its rim term), and grade the finished picture (grade.h: lift, gamma, gain,
// contrast, saturation, vignette). `strength` 0..1 (Options' lighting, faded in and out) scales the profile's every
// difference from neutral: 0 changes nothing, exactly.

// A slow, smooth wobble in -1..1 (three slow sines, none in step with another): the flicker at `seconds`.
float moodFlicker(float seconds);

// The practical lights near one of a location's views (locations.json's "cinematic": {"practicals": {"inside": {...},
// "outside": {...}}}, made by tools/locations.py from the doodads' file names).
struct CinePracticals {
    int flames = 0;                          // candles, torches, braziers, hearths, oil lanterns: they flicker
    int lights = 0;                          // every practical light (flames and the steady ones: crystals, arcane lamps)
    float colour[3] = {1.0f, 0.70f, 0.42f};  // their light's mean colour
    bool hasAt = false;
    V3 at;                                   // where their light comes from (weighted toward the nearest), the location's frame
};

// A cinematic profile: everything at full strength (cineScaled brings it to a strength).
struct CineProfile {
    float key[3] = {1, 1, 1};        // the key light's colour (luminance 1)
    float keyGain = 1;               // its brightness over the person's own light (the room's or the sky's)
    float sunBias = 0;               // 0..1: the key's direction pulled from the camera's side toward the sun's (or moon's)
    float practicalBias = 0;         // 0..1: ... toward the practical lights (CinePracticals::at)
    float rim[3] = {1, 1, 1};        // the rim light from behind (luminance 1)
    float rimGain = 0;
    float actorAmbient = 1;          // a person's ambient times this (deeper shadows under 1)
    float sceneAmbient[3] = {1, 1, 1};   // the location's light times these
    float sceneSun[3] = {1, 1, 1};
    float sceneSky[3] = {1, 1, 1};
    float room[3] = {1, 1, 1};           // a room's own light (its ambient and baked light)
    float lift[3] = {0, 0, 0};       // the grade: where black goes,
    float gamma = 1;                 //   the mid-tones' power (over 1: darker),
    float gain[3] = {1, 1, 1};       //   where white goes,
    float contrast = 0;              //   the S-curve's share,
    float saturation = 1;            //   colour (1: as it is)
    float vignette = 0;              // the vignette option times this
    float flicker = 0;               // 0..1: how much the practicals' flicker moves the room's light and the key
    float flickerColour[3] = {1, 1, 1};
    // the zone mood (cineZoneLight; all neutral without a zone or at zone mood 0)
    float mid[3] = {1, 1, 1};        // the grade's mid-tones by channel (over 1: that channel's mid-tones raised)
    float shadowTint[3] = {1, 1, 1}; // the grade's split tone: the shadows times this (eased out by the mid-tones; luminance
    float highTint[3] = {1, 1, 1};   //   about 1), the highlights times this (eased in from the mid-tones)
    float exposure = 1;              // the frame brightened before the grade (a soft shoulder: 0 and 1 stay where they are)
    float roomFloor[3] = {0, 0, 0};  // a room's ambient raised by this (absolute light, before the room's tint): a dark room
                                     //   stays readable in its own (or the zone's) colour
    float zoneMix = 0;               // 0..1: the location's ambient, sun, sky and fog pulled toward these
    float zoneAmbient[3] = {0, 0, 0};
    float zoneSun[3] = {0, 0, 0};
    float zoneSky[3] = {0, 0, 0};
    float zoneFog[3] = {0, 0, 0};    // (also the sky at and under the horizon)
    float fogMix = 0;                // 0..1: the fog's distances pulled in toward these (never pushed out past the area's)
    float fogNear = 0, fogFar = 0;   // yards
};

// A field a location may set by hand (locations.json "cinematic": {"override": {"inside"|"outside": {name: value}}}):
// key, keyGain, sunBias, practicalBias, rim, rimGain, actorAmbient, sceneAmbient, sceneSun, sceneSky, room, lift, gamma,
// gain, contrast, saturation, vignette, flicker, flickerColour, mid, shadowTint, highTint, exposure, roomFloor (each
// replaces the derived value; the limits still hold),
// and hue (the area's own colour, in place of the one its light gives).
struct CineOverride {
    uint32_t mask = 0;               // which of CineProfile's fields are set (cineOverrideFields' order)
    CineProfile values;
    bool hasHue = false;
    float hue[3] = {1, 1, 1};
    bool empty() const { return mask == 0 && !hasHue; }
};

struct CineArea {
    CinePracticals inside, outside;
    CineOverride overInside, overOutside;
    ZoneLight zone;              // the zone's own light ("zone"; empty: none, the zone mood does nothing)
    // "exposure": a number (both sides) or {"inside": n, "outside": n}: the place's own exposure times the derived one
    // (0.5..2; 1: as derived). For a room whose baked light is darker or brighter than the rest.
    float exposureInside = 1, exposureOutside = 1;
};

// "cinematic" read (missing or broken parts keep their defaults); false when it isn't an object.
bool parseCineArea(const Json& j, CineArea& out);
// One override object ({name: number or [r,g,b]}) read; unknown names are skipped. False when it isn't an object.
bool parseCineOverride(const Json& j, CineOverride& out);

// What the profile is worked out from.
struct CineInput {
    float ambient[3] = {0.5f, 0.5f, 0.5f};   // the area's light at the hour (before the cinematic look)
    float direct[3] = {0.4f, 0.4f, 0.4f};    // the sun's (by night the moon's)
    float sky[3] = {0.3f, 0.5f, 0.7f};
    float fog[3] = {0.3f, 0.4f, 0.5f};
    bool interior = false;                   // the people stand in a room
    float roomAmbient[3] = {0, 0, 0};        // the room's own ambient (MOHD) and its baked light's mean (times the hour's tint)
    float roomLight[3] = {0, 0, 0};
    float hour = -1;                         // the world clock's hour (negative: no clock, as by day)
    float exposure = 1;                      // the place's exposure tweak (CineArea's, for the side the people stand on)
    CinePracticals practicals;
    // the zone mood: the zone's light at the hour (sampleZone), how much of it (Options' Zone mood, 0..1: 0 changes nothing,
    // exactly) and the Mood boost (its colours exaggerated: more saturation and contrast)
    bool hasZone = false;
    ZoneKey zone;
    float zoneAmount = 0;
    bool zoneBoost = false;
};

// The profile for a place at an hour, then `over` laid on it, then the limits (cineLimit). Continuous in every input but
// `interior` (the engine blends the change: cineStep).
CineProfile cineProfile(const CineInput& in, const CineOverride* over = nullptr);
// The area's own colour (luminance 1) as cineProfile sees it: a room's light (with a little of the day through its windows),
// or the sky's ambient, sun and fog.
void cineAreaHue(const CineInput& in, float out[3]);
// The limits: no colour cast past a chroma limit, no crushed blacks or blown highlights in the grade, nothing out of range.
void cineLimit(CineProfile& p);
// Neutral: changes nothing.
CineProfile cineNeutral();
// `p`'s every difference from neutral times `strength` (0..1).
CineProfile cineScaled(const CineProfile& p, float strength);
// a + (b - a) * t, field by field.
CineProfile cineBlend(const CineProfile& a, const CineProfile& b, float t);
// `now` eased toward `target` over `dt` seconds (time constant `tau`): frame-rate independent.
void cineStep(CineProfile& now, const CineProfile& target, float dt, float tau = 1.0f);

// The grade's transfer of one colour (0..1), as grade.cpp's shader does it, for a profile already at its strength.
void cineGradeColour(const CineProfile& p, const float in[3], float out[3]);
// The exposure's soft shoulder on one channel (0..1): v * e / (1 + v * (e - 1)); 0 and 1 stay, the slope at black is e.
float cineExpose(float v, float exposure);

// The zone mood on the location's light at `strength` (before cineScene): the ambient, sun, sky top, fog and the sky at and
// under the horizon pulled toward the profile's zone colours by zoneMix, the fog's start and end pulled in toward its
// distances by fogMix (never past where they were: the end is the loaded area's edge). zoneMix 0: nothing changes.
void cineZoneLight(const CineProfile& p, float strength, float ambient[3], float sun[3], float skyTop[3], float horizon[3],
                   float below[3], float fog[3], float& fogStart, float& fogEnd);

// The location's light under the profile at `strength`: each array changed in place; the flicker (-1..1) moves the room's
// light by a few percent, toward the flames' colour.
void cineScene(const CineProfile& p, float strength, float flicker, float ambient[3], float sun[3], float sky[3],
               float fog[3], float roomTint[3]);

// A person's light under the profile, from what the room or the sky gives them (`ambient` and the directional `direct`):
// the ambient lowered, a key in the profile's colour about as bright as their light was (never under a floor, never over a
// ceiling), a rim. At strength 0: the ambient and direct as given, no rim.
struct MoodActorLight {
    float ambient[3] = {0, 0, 0};
    float key[3] = {0, 0, 0};
    float rim[3] = {0, 0, 0};
};
MoodActorLight cineActor(const CineProfile& p, float strength, float flicker, const float ambient[3], const float direct[3]);

// The key's and the rim's directions for a camera at `eye` looking at `target` (Z up): `keyTravel` the way the key's light
// travels (from the camera's right, low, about 20 degrees up, a little in front of the people), `rimToward` toward the rim
// light (behind them, seen from the camera, and above). Both unit length; a camera looking straight down keeps +X ahead.
void moodDirections(const V3& eye, const V3& target, V3& keyTravel, V3& rimToward);
// The key pulled toward the sun's travel (`sunTravel`, by `sunBias`) and toward the light from `practicalAt` (by
// `practicalBias`, when `hasPractical`), kept low and on the people's camera side (it never turns into a back light).
V3 cineKeyDirection(const V3& eye, const V3& target, const V3& sunTravel, float sunBias, bool hasPractical,
                    const V3& practicalAt, float practicalBias);

// Colour helpers (tests): the luminance (0.299, 0.587, 0.114); a colour at luminance 1; the distance between two colours'
// chromaticities (r and g over r + g + b; 0: one hue).
float cineLum(const float c[3]);
void cineHue(const float c[3], float out[3]);
float cineHueDistance(const float a[3], const float b[3]);

} // namespace wc
