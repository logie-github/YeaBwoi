// The portrait light: a three-point rig for the person Interact mode (and the hidden mode) shows, laid out from the camera so the picture is
// always a studio portrait whichever way she is turned or the camera is orbited. A key (warm, high, to the camera's left, large and soft: the
// skin wraps it), a fill (cool, low, from the right) and a rim (from behind), over a hemisphere ambient (sky above, a warm bounce below).
// Only the people drawn with it are lit by it; the place's own light stays for everything else.
//
// Pure maths (no GL): engine-test/portrait_test checks the geometry and the bounds. kLitFs (scene.cpp) shades with the numbers.
#pragma once

#include <algorithm>
#include <cmath>

#include "wmath.h"

namespace wc {
namespace portrait {

enum Preset { kOff = 0, kNatural = 1, kSoft = 2, kDramatic = 3, kWarm = 4, kPresets = 5 };

struct Rig {
    bool on = false;
    float keyTravel[3] = {0, 0, -1};    // the direction each light travels (unit)
    float fillTravel[3] = {0, 0, -1};
    float rimTravel[3] = {0, 0, -1};
    float keyColor[3] = {0, 0, 0};      // linear, times its intensity
    float fillColor[3] = {0, 0, 0};
    float rimColor[3] = {0, 0, 0};
    float sky[3] = {0, 0, 0};           // the hemisphere's colour for a normal facing up
    float ground[3] = {0, 0, 0};        // ... and down
    float ambient[3] = {0, 0, 0};       // their mean (what the old single ambient would have been)
    float wrap = 0.45f;                 // how far the key wraps round the skin (0 a plain Lambert, 1 half-Lambert and over)
    float specular = 0.06f;             // the skin's broad highlight
    float scatter = 0.45f;              // the red band at the key's terminator
    float shoulder = 0.78f;             // where the soft shoulder starts rolling highlights off
};

struct Look {
    float keyAzimuth, keyElevation;     // degrees: to the camera's left, above the horizon
    float keyColor[3];
    float fillAzimuth, fillElevation;   // negative azimuth: to the camera's right
    float fillColor[3];
    float rimAzimuth, rimElevation;     // from behind her: degrees round from the camera's side (180 straight behind)
    float rimColor[3];
    float sky[3], ground[3];
    float wrap, specular, scatter;
};

inline Look lookOf(int preset) {
    switch (preset) {
    case kSoft:
        return {18, 30, {0.50f, 0.46f, 0.42f}, -22, 12, {0.30f, 0.34f, 0.42f}, 140, 30, {0.18f, 0.19f, 0.21f},
                {0.32f, 0.35f, 0.42f}, {0.22f, 0.17f, 0.13f}, 0.85f, 0.04f, 0.4f};
    case kDramatic:
        return {62, 32, {1.05f, 0.93f, 0.80f}, -50, 4, {0.07f, 0.09f, 0.14f}, 128, 38, {0.80f, 0.82f, 0.88f},
                {0.10f, 0.11f, 0.15f}, {0.06f, 0.05f, 0.05f}, 0.22f, 0.09f, 0.5f};
    case kWarm:
        return {34, 28, {0.95f, 0.74f, 0.52f}, -38, 6, {0.19f, 0.20f, 0.30f}, 138, 34, {0.80f, 0.55f, 0.38f},
                {0.30f, 0.28f, 0.30f}, {0.30f, 0.20f, 0.13f}, 0.5f, 0.06f, 0.6f};
    case kNatural:
    default:
        return {38, 40, {0.78f, 0.69f, 0.58f}, -36, 8, {0.15f, 0.18f, 0.25f}, 140, 36, {0.36f, 0.37f, 0.40f},
                {0.28f, 0.31f, 0.37f}, {0.24f, 0.18f, 0.13f}, 0.42f, 0.06f, 0.45f};
    }
}

// The direction the light travels for a light that sits [azimuth] degrees round from the camera (positive: to the camera's left; 180: straight
// behind her) and [elevation] degrees up, aimed at the target. Horizontal basis from the camera's own heading: the light does not tilt with the
// camera's pitch (it stays "overhead" however the camera looks up or down at her).
inline V3 lightTravel(const V3& eye, const V3& target, float azimuthDeg, float elevationDeg) {
    V3 f(target.x - eye.x, target.y - eye.y, 0.0f);
    f = f.length() > 1e-4f ? f.normalized() : V3(1, 0, 0);
    const V3 left(-f.y, f.x, 0.0f);   // (z up: left = up x forward)
    const float az = azimuthDeg * 3.14159265f / 180.0f, el = elevationDeg * 3.14159265f / 180.0f;
    // toward the light: back toward the camera by cos(az), to the left by sin(az), up by sin(el)
    const V3 to = (f * -std::cos(az) + left * std::sin(az)) * std::cos(el) + V3(0, 0, std::sin(el));
    return (to * -1.0f).normalized();
}

// The rig for [preset] seen from [eye] toward [target]. [placeTint] (a colour, any scale; null: none) leans the ambient toward the place's own
// ambient colour a little, so a room's warmth or a cave's chill still reaches her.
inline Rig make(int preset, const V3& eye, const V3& target, const float* placeTint = nullptr) {
    Rig r;
    if (preset <= kOff || preset >= kPresets) return r;
    const Look k = lookOf(preset);
    r.on = true;
    auto put = [](float* o, const V3& v) { o[0] = v.x; o[1] = v.y; o[2] = v.z; };
    put(r.keyTravel, lightTravel(eye, target, k.keyAzimuth, k.keyElevation));
    put(r.fillTravel, lightTravel(eye, target, k.fillAzimuth, k.fillElevation));
    put(r.rimTravel, lightTravel(eye, target, k.rimAzimuth, k.rimElevation));
    float tint[3] = {1, 1, 1};
    if (placeTint) {
        const float m = std::max({placeTint[0], placeTint[1], placeTint[2], 1e-3f});
        for (int i = 0; i < 3; ++i) tint[i] = 0.7f + 0.3f * std::clamp(placeTint[i] / m, 0.0f, 1.0f);
    }
    for (int i = 0; i < 3; ++i) {
        r.keyColor[i] = k.keyColor[i];
        r.fillColor[i] = k.fillColor[i];
        r.rimColor[i] = k.rimColor[i];
        r.sky[i] = k.sky[i] * tint[i];
        r.ground[i] = k.ground[i] * tint[i];
        r.ambient[i] = 0.5f * (r.sky[i] + r.ground[i]);
    }
    r.wrap = k.wrap; r.specular = k.specular; r.scatter = k.scatter;
    return r;
}

} // namespace portrait
} // namespace wc
