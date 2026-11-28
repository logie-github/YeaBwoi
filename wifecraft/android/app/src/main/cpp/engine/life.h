// A person's life and gaze, for Interact mode and the hidden mode: what makes her read as someone behind the glass and not a posed doll.
// Breathing (12 to 16 a minute, asymmetric, the chest, shoulders and head with it), weight shifts every 8 to 20 s (the torso over one hip,
// the shoulders and head answering), slow noise on the spine, neck, head, arms, hands and fingers so no two moments are alike, an idle gesture
// now and then (a hair tuck, a hand to her arm, a shoulder roll, a finger to her lip, a shy tilt of the head), a start and a flinch at a touch
// with her head and eyes turning toward it and back; and she looks at the camera: the head and neck (and a little of the spine) turn toward it
// within a limit and let go gently past it, the eyes lead the head and keep contact most of the time with small saccades, drifts and glances
// away and back, and she blinks (every 2.5 to 6 s, sometimes twice, faster during a turn of the head).
//
// This file is the pure part (no GL, no skeleton): LifeState::step takes the time, what the camera looks like from her torso and what the
// player did, and gives angles in radians and weights. lifedraw.cpp lays them over the posed bones. Deterministic from a seed.
// engine-test/life_test checks its bounds, its timing and its limits.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace wc {
namespace life {

constexpr float kPi = 3.14159265f;
inline float rad(float d) { return d * kPi / 180.0f; }
inline float deg(float r) { return r * 180.0f / kPi; }
inline float smooth(float x) { x = std::clamp(x, 0.0f, 1.0f); return x * x * (3.0f - 2.0f * x); }

struct Rng {
    uint32_t s;
    explicit Rng(uint32_t seed = 1) : s(seed ? seed : 1u) {}
    uint32_t next() { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return s; }
    float uni() { return float(next() >> 8) * (1.0f / 16777216.0f); }
    float range(float a, float b) { return a + (b - a) * uni(); }
    bool chance(float p) { return uni() < p; }
};

inline float hash01(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
    return float(x & 0xffffffu) * (1.0f / 16777216.0f);
}
// Smooth value noise in -1..1 at about `hz` cycles a second: stateless, per seed and channel.
inline float noise1(float t, uint32_t seed, int ch, float hz = 0.25f) {
    const float x = t * hz;
    const float i = std::floor(x);
    float f = x - i;
    f = f * f * f * (f * (f * 6.0f - 15.0f) + 10.0f);
    const uint32_t k = seed * 2654435761u + uint32_t(ch) * 40503u;
    const float a = hash01(k + uint32_t(int32_t(i)) * 374761393u) * 2.0f - 1.0f;
    const float b = hash01(k + uint32_t(int32_t(i) + 1) * 374761393u) * 2.0f - 1.0f;
    return a + (b - a) * f;
}

// A critically damped spring: [x] and its velocity [v] toward [target] at angular rate [omega] (exact for any dt).
inline void critDamp(float& x, float& v, float target, float omega, float dt) {
    const float e = std::exp(-omega * dt);
    const float d = x - target;
    const float c = v + omega * d;
    x = target + (d + c * dt) * e;
    v = (v - omega * c * dt) * e;
}

// How far round (signed radians) she turns toward something [angle] round from her torso's front, with [limit] the most she turns: the angle
// as it is up to 60% of the limit, then easing in to the limit (about 0.96 of it at 1.2 limit), then, as the camera goes on round past that, a
// gentle pull back to facing front (all of it by 50 degrees further). Continuous and odd.
inline float lookCommand(float angle, float limit) {
    const float s = angle < 0 ? -1.0f : 1.0f, x = std::fabs(angle);
    const float lin = 0.6f * limit;
    float y = x;
    if (x > lin) y = lin + (limit - lin) * std::tanh((x - lin) / (limit - lin));
    if (x > 1.2f * limit) y *= 1.0f - smooth((x - 1.2f * limit) / rad(50.0f));
    return s * y;
}

// The share of the turn each part takes: the spine a little, the neck a third, the head the rest (yaw), and for pitch the spine less.
struct Shares { float spine, neck, head; };
constexpr Shares kYawShares = {0.14f, 0.34f, 0.52f};
constexpr Shares kPitchShares = {0.08f, 0.36f, 0.56f};

// A blink's closure 0..1 [t] seconds after it began: closing in 60 ms, shut for 10, opening in 90 (faster to close than to open).
inline float blinkShape(float t) {
    if (t < 0.0f) return 0.0f;
    if (t < 0.06f) return smooth(t / 0.06f);
    if (t < 0.07f) return 1.0f;
    if (t < 0.16f) return 1.0f - smooth((t - 0.07f) / 0.09f);
    return 0.0f;
}

// A breath's shape 0..1 from its phase (radians): in over 40% of it, out over 60%.
inline float breathShape(float phase) {
    float p = std::fmod(phase / (2.0f * kPi), 1.0f);
    if (p < 0) p += 1.0f;
    return p < 0.4f ? 0.5f - 0.5f * std::cos(kPi * p / 0.4f) : 0.5f + 0.5f * std::cos(kPi * (p - 0.4f) / 0.6f);
}

enum Level { kOff = 0, kSubtle = 1, kNatural = 2, kLively = 3 };
enum GazeMode { kGazeOff = 0, kGazeCamera = 1, kGazeWander = 2 };
enum Gesture { kNone = 0, kHairTuck, kTouchArm, kShoulderRoll, kLipTouch, kShyTilt, kLookAway, kGestures };
enum Event { kEventFlinch = 1, kEventLeanIn, kEventStepBack, kEventGiggle, kEventAttend };

struct Params {
    int level = kNatural;
    int gaze = kGazeCamera;
    float lookLimitDeg = 70.0f;   // 20..90
};

// What the engine tells her each frame.
struct Input {
    float camYaw = 0, camPitch = 0;    // the camera as seen from her torso: radians round from its front (left positive) and up
    float camDist = 1.5f;              // the camera's distance (world)
    bool armsFree = true;              // a hand may be lifted (her pose is a stand or a held pose)
};

// What she does this frame (radians, model axes at the engine's layer: yaw about up, pitch about the left axis, positive nodding down, roll about the front).
struct Output {
    // breathing and weight
    float chestPitch = 0, clavLift = 0, headBob = 0;
    float waistRoll = 0, waistYaw = 0, shiftY = 0, chestRoll = 0, headRoll = 0;
    // noise
    float spineN[3] = {0, 0, 0}, neckN[3] = {0, 0, 0}, headN[3] = {0, 0, 0};   // pitch, yaw, roll
    float upperN[2][2] = {{0, 0}, {0, 0}}, foreN[2] = {0, 0}, handN[2] = {0, 0};   // per side (left, right): pitch, roll; forearm; hand
    float curl[2] = {0, 0};            // the fingers' curl 0..1, per hand
    // the gaze
    float spineYaw = 0, spinePitch = 0, neckYaw = 0, neckPitch = 0, headYaw = 0, headPitch = 0;
    float eyeWeight = 0;               // 1: the eyes follow the camera; 0: they rest straight (the camera is behind her, or gaze is off)
    float eyeOffYaw = 0, eyeOffPitch = 0;   // saccade, drift and glance, on top of the camera's direction (in the head's frame)
    float lid[2] = {0, 0};             // the upper lids' closure, left and right
    float lidTrack = 0;                // the lids following a downward glance 0..1
    // gestures
    int gesture = kNone;
    float gestureWeight = 0;           // its envelope 0..1
    int gestureSide = 0;               // 0 her right hand, 1 her left
    // touch
    float impulsePitch = 0, impulseRoll = 0;
    bool gazeOn = false;
};

struct LifeState {
    Params p;
    uint32_t seed = 1;
    bool started = false;
    float t = 0;
    Rng rng{1};
    // breath
    float breathPhase = 0;
    // weight shift
    float side = 0, sideFrom = 0, sideTo = 0, sideT = 1, sideDur = 2, nextShift = 9;
    // gestures
    int gesture = kNone, gestureSide = 0;
    float gestureT = 0, gestureDur = 0, nextGesture = 12;
    // gaze
    float headYaw = 0, headYawV = 0, headPitch = 0, headPitchV = 0;
    float saccYaw = 0, saccPitch = 0, saccYawV = 0, saccPitchV = 0, saccTY = 0, saccTP = 0, nextSacc = 0.6f;
    float glanceT = -1, glanceDur = 0, glanceYaw = 0, glancePitch = 0, glanceHead = 0, nextGlance = 5;
    float eyeW = 0, eyeWV = 0;
    float attT = -1, attYaw = 0, attPitch = 0;
    // blink
    float blinkT = -1, nextBlink = 3, blinkAgain = 0;
    float lastCamYaw = 0, lastCamPitch = 0;
    // touch impulse (a damped spring)
    float impP = 0, impPV = 0, impR = 0, impRV = 0;

    void configure(const Params& pr, uint32_t sd) {
        p = pr; p.lookLimitDeg = std::clamp(p.lookLimitDeg, 20.0f, 90.0f);
        if (!started || sd != seed) { seed = sd ? sd : 1u; rng = Rng(seed * 2891336453u + 7u); started = false; }
    }

    float amount() const { return p.level == kSubtle ? 0.5f : p.level == kLively ? 1.6f : p.level == kNatural ? 1.0f : 0.0f; }

    void start() {
        started = true; t = 0;
        nextBlink = rng.range(1.0f, 3.0f);
        nextShift = rng.range(5.0f, 12.0f);
        nextGesture = rng.range(6.0f, 14.0f);
        nextGlance = rng.range(2.0f, 5.0f);
        nextSacc = rng.range(0.2f, 0.8f);
        breathPhase = rng.range(0.0f, 2.0f * kPi);
    }

    // A touch: [yaw], [pitch] where the touched place lies from her torso's front (radians); [kind] an Event; [strength] 0..1.
    void touch(int kind, float yaw, float pitch, float strength) {
        strength = std::clamp(strength, 0.0f, 1.0f);
        attT = 0; attYaw = std::clamp(yaw, -rad(80), rad(80)); attPitch = std::clamp(pitch, -rad(40), rad(30));
        glanceT = 0; glanceDur = 0.9f; glanceYaw = std::clamp(yaw, -rad(30), rad(30)) * 0.6f; glancePitch = std::clamp(pitch, -rad(25), rad(15)); glanceHead = 0.5f;
        blinkT = (blinkT < 0 || blinkT > 0.16f) ? 0.0f : blinkT;   // (a start blinks)
        const float s = strength;
        switch (kind) {
        // (velocities in rad/s: the critically damped return makes a bump of about a twenty-fourth of them in radians)
        case kEventFlinch: impPV += rad(-150) * s; impRV += rad(90) * s * (rng.chance(0.5f) ? 1 : -1); break;
        case kEventLeanIn: impPV += rad(120) * s; break;
        case kEventStepBack: impPV += rad(-180) * s; break;
        case kEventGiggle: impPV += rad(80) * s; impRV += rad(120) * s * (rng.chance(0.5f) ? 1 : -1); break;
        default: break;
        }
        if (kind == kEventGiggle) { gesture = kShyTilt; gestureT = 0; gestureDur = 2.0f; }
    }

    // The desktop check: a gesture now, held (its envelope at 1) for a minute.
    void forceGesture(int g, int sd) { gesture = g; gestureT = 0.8f; gestureDur = 60.0f; gestureSide = sd; }

    Output step(float dt, const Input& in) {
        Output o;
        dt = std::clamp(dt, 0.0f, 0.1f);
        if (!started) start();
        t += dt;
        const float A = amount();
        const bool living = p.level != kOff;
        const bool gaze = p.gaze != kGazeOff;
        // ---- breathing: 12 to 16 a minute, the rate wandering slowly
        if (living) {
            const float rate = (14.0f + 1.8f * noise1(t, seed, 90, 0.02f)) / 60.0f;
            breathPhase += 2.0f * kPi * rate * dt;
            const float b = breathShape(breathPhase) - 0.4f;
            o.chestPitch = -rad(1.1f) * A * b;
            o.clavLift = rad(0.8f) * A * b;
            o.headBob = rad(0.3f) * A * b;
        }
        // ---- weight shifts every 8 to 20 s: over one hip, the shoulders and head answering
        if (living) {
            nextShift -= dt;
            if (nextShift <= 0) {
                sideFrom = side;
                const float opts[5] = {-1.0f, -0.45f, 0.0f, 0.45f, 1.0f};
                float to = side;
                for (int k = 0; k < 8 && std::fabs(to - side) < 0.4f; ++k) to = opts[rng.next() % 5];
                sideTo = to; sideT = 0; sideDur = rng.range(1.6f, 3.0f);
                nextShift = rng.range(8.0f, 20.0f) / std::max(0.6f, std::min(A, 1.2f));
            }
            if (sideT < 1.0f) { sideT = std::min(1.0f, sideT + dt / sideDur); side = sideFrom + (sideTo - sideFrom) * smooth(sideT); }
            o.waistRoll = side * rad(1.3f) * A;
            o.chestRoll = -side * rad(0.9f) * A;
            o.headRoll = side * rad(0.8f) * A;
            o.waistYaw = side * rad(0.5f) * A;
            o.shiftY = side * 0.012f * A;
        }
        // ---- slow noise: no two moments alike
        if (living) {
            auto n = [&](int ch, float hz) { return noise1(t, seed, ch, hz); };
            for (int a = 0; a < 3; ++a) {
                o.spineN[a] = rad(0.55f) * A * n(1 + a, 0.17f);
                o.neckN[a] = rad(0.8f) * A * n(4 + a, 0.21f);
                o.headN[a] = rad(1.0f) * A * n(7 + a, 0.19f);
            }
            for (int s = 0; s < 2; ++s) {
                o.upperN[s][0] = rad(1.3f) * A * n(10 + s, 0.13f);
                o.upperN[s][1] = rad(1.0f) * A * n(12 + s, 0.11f);
                o.foreN[s] = rad(1.6f) * A * n(14 + s, 0.15f);
                o.handN[s] = rad(2.2f) * A * n(16 + s, 0.18f);
                o.curl[s] = 0.5f + 0.5f * n(18 + s, 0.07f);
            }
        }
        // ---- the gaze
        o.gazeOn = gaze;
        const float L = rad(p.lookLimitDeg);
        const float camYawCmd = lookCommand(in.camYaw, L);
        const float pl = std::min(rad(35.0f), 0.5f * L);
        float camPitchCmd = std::clamp(in.camPitch, -pl, pl * 0.85f);
        if (std::fabs(in.camYaw) > 1.2f * L) camPitchCmd *= 1.0f - smooth((std::fabs(in.camYaw) - 1.2f * L) / rad(50.0f));
        // eye contact: the eyes follow the camera until it is further round than the head's reach and a margin (30 degrees)
        const float behind = smooth((std::fabs(in.camYaw) - (L + rad(15.0f))) / rad(30.0f));
        const float wantW = gaze ? 1.0f - behind : 0.0f;
        critDamp(eyeW, eyeWV, wantW, 10.0f, dt);
        o.eyeWeight = std::clamp(eyeW, 0.0f, 1.0f);
        // glances away and back (camera: rarely and short; with wander: every 3 to 8 s, longer, the head following a little)
        const bool wander = p.gaze == kGazeWander;
        if (gaze && living) {
            nextGlance -= dt;
            if (nextGlance <= 0 && glanceT < 0) {
                glanceT = 0;
                glanceDur = wander ? rng.range(0.6f, 1.5f) : rng.range(0.3f, 0.7f);
                const float mag = wander ? rng.range(rad(10), rad(24)) : rng.range(rad(6), rad(13));
                const int kind = int(rng.next() % 3);
                glanceYaw = (kind == 2 ? 0.0f : (rng.chance(0.5f) ? 1.0f : -1.0f) * mag);
                glancePitch = kind == 2 ? -mag : rng.range(-rad(6), rad(4));
                glanceHead = wander ? 0.35f : 0.0f;
                nextGlance = (wander ? rng.range(3.0f, 8.0f) : rng.range(6.0f, 14.0f));
                if (rng.chance(0.6f)) blinkT = (blinkT < 0 || blinkT > 0.16f) ? 0.0f : blinkT;   // (a glance often blinks)
            }
        }
        float glanceW = 0;
        if (glanceT >= 0) {
            glanceT += dt;
            const float up = smooth(glanceT / 0.12f), down = 1.0f - smooth((glanceT - glanceDur) / 0.18f);
            glanceW = std::min(up, down);
            if (glanceT > glanceDur + 0.2f) glanceT = -1;
        }
        // saccades: a new small target every 0.3 to 1.2 s, reached fast; and a slow drift
        nextSacc -= dt;
        if (nextSacc <= 0) {
            nextSacc = rng.range(0.3f, 1.2f);
            const float m = rng.range(rad(0.5f), rad(1.5f));
            const float a = rng.range(0.0f, 2.0f * kPi);
            saccTY = m * std::cos(a); saccTP = m * std::sin(a) * 0.7f;
        }
        critDamp(saccYaw, saccYawV, saccTY, 70.0f, dt);
        critDamp(saccPitch, saccPitchV, saccTP, 70.0f, dt);
        const float drift = rad(0.4f);
        // touch attention: the head and eyes toward the touched place for a moment, then back to the camera
        float att = 0;
        if (attT >= 0) {
            attT += dt;
            att = std::min(smooth(attT / 0.25f), 1.0f - smooth((attT - 1.0f) / 0.6f));
            if (attT > 1.7f) attT = -1;
        }
        // head targets: the camera's command, the attention and a glance's share, eased by a critically damped spring (the eyes lead)
        float tYaw = camYawCmd, tPitch = camPitchCmd;
        if (gaze) {
            tYaw = tYaw * (1.0f - 0.6f * att) + attYaw * 0.6f * att + glanceYaw * glanceHead * glanceW;
            tPitch = tPitch * (1.0f - 0.6f * att) + attPitch * 0.6f * att + glancePitch * glanceHead * glanceW;
        } else { tYaw = 0; tPitch = 0; }
        tYaw = std::clamp(tYaw, -L, L);
        critDamp(headYaw, headYawV, tYaw, 7.0f, dt);
        critDamp(headPitch, headPitchV, tPitch, 7.0f, dt);
        if (gaze) {
            o.spineYaw = headYaw * kYawShares.spine; o.neckYaw = headYaw * kYawShares.neck; o.headYaw = headYaw * kYawShares.head;
            o.spinePitch = -headPitch * kPitchShares.spine; o.neckPitch = -headPitch * kPitchShares.neck; o.headPitch = -headPitch * kPitchShares.head;
            // (pitch: up is positive in headPitch, the engine's nod is positive down)
            o.eyeOffYaw = saccYaw + drift * noise1(t, seed, 40, 0.3f) + glanceYaw * glanceW * (1.0f - 0.5f * glanceHead);
            o.eyeOffPitch = saccPitch + drift * noise1(t, seed, 41, 0.3f) + glancePitch * glanceW;
        }
        // ---- blinks: every 2.5 to 6 s, sometimes two; a turn of the head brings one
        if (living || gaze) {
            const float turn = std::fabs(in.camYaw - lastCamYaw) + std::fabs(in.camPitch - lastCamPitch);
            if (dt > 1e-4f && turn / dt > rad(45.0f) && (blinkT < 0 || blinkT > 0.16f) && nextBlink > 0.4f) { blinkT = 0; nextBlink = rng.range(2.5f, 6.0f); }
            nextBlink -= dt;
            if (nextBlink <= 0 && (blinkT < 0 || blinkT > 0.16f)) {
                blinkT = 0;
                nextBlink = rng.range(2.5f, 6.0f);
                blinkAgain = rng.chance(0.12f) ? rng.range(0.18f, 0.32f) : 0.0f;
            }
            if (blinkT >= 0) {
                blinkT += dt;
                if (blinkAgain > 0 && blinkT > blinkAgain) { blinkT = 0; blinkAgain = 0; }
                if (blinkT > 0.2f && blinkAgain <= 0) blinkT = -1;
            }
        }
        lastCamYaw = in.camYaw; lastCamPitch = in.camPitch;
        const float bl = blinkT >= 0 ? blinkShape(blinkT) : 0.0f;
        o.lid[0] = bl; o.lid[1] = bl * 0.97f;   // (nearly together)
        // the lids follow a look down; less a look up
        const float eyeDown = -(camPitchCmd + o.eyeOffPitch) * o.eyeWeight;
        o.lidTrack = std::clamp(eyeDown / rad(20.0f), 0.0f, 1.0f);
        // ---- idle gestures every 10 to 30 s
        if (living) {
            if (gesture == kNone) {
                nextGesture -= dt;
                if (nextGesture <= 0) {
                    const float scale = p.level == kSubtle ? 2.0f : p.level == kLively ? 0.6f : 1.0f;
                    nextGesture = rng.range(10.0f, 30.0f) * scale;
                    float w[kGestures] = {0, 3, 0, 3, 1, 3, wander || gaze ? 2.0f : 0.0f};   // (no kTouchArm yet: the hand on her other elbow did not read right)
                    if (!in.armsFree) { w[kHairTuck] = w[kTouchArm] = w[kLipTouch] = 0; }
                    if (p.level == kSubtle) { w[kHairTuck] = w[kTouchArm] = w[kLipTouch] = 0; }
                    float sum = 0; for (float x : w) sum += x;
                    if (sum > 0) {
                        float r = rng.uni() * sum; int g = kNone;
                        for (int k = 1; k < kGestures; ++k) { if (r < w[k]) { g = k; break; } r -= w[k]; }
                        if (g != kNone) {
                            gesture = g; gestureT = 0; gestureSide = rng.chance(0.65f) ? 0 : 1;
                            gestureDur = g == kShoulderRoll ? 1.7f : g == kLipTouch ? 2.6f : g == kLookAway ? 2.4f : g == kShyTilt ? 2.4f : 3.0f;
                            if (g == kLookAway) { glanceT = 0; glanceDur = 1.6f; glanceYaw = (rng.chance(0.5f) ? 1.0f : -1.0f) * rad(rng.range(18.0f, 30.0f)); glancePitch = -rad(rng.range(4.0f, 14.0f)); glanceHead = 0.45f; }
                        }
                    }
                }
            } else {
                gestureT += dt;
                if (gestureT >= gestureDur) { gesture = kNone; nextGesture = std::max(nextGesture, rng.range(8.0f, 18.0f)); }
            }
            if (gesture != kNone) {
                const float up = smooth(gestureT / 0.7f), down = 1.0f - smooth((gestureT - (gestureDur - 0.8f)) / 0.8f);
                o.gesture = gesture; o.gestureWeight = std::min(up, down); o.gestureSide = gestureSide;
                if (gesture == kShyTilt) {
                    o.headRoll += rad(5.0f) * o.gestureWeight * (gestureSide ? -1.0f : 1.0f);
                    o.headPitch += rad(3.0f) * o.gestureWeight;
                }
                if (gesture == kLookAway) o.gesture = kNone;
            }
        }
        // ---- touch impulse: a damped spring (a flinch overshoots a little and settles)
        critDamp(impP, impPV, 0.0f, 9.0f, dt);   // (critically damped: no ringing; the impulse's velocity decays into a bump)
        critDamp(impR, impRV, 0.0f, 9.0f, dt);
        o.impulsePitch = impP; o.impulseRoll = impR;
        if (!living) { o.impulsePitch = o.impulseRoll = 0; }
        return o;
    }
};

} // namespace life
} // namespace wc
