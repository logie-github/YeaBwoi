// The body's additive motion: breathing (the chest rising and the shoulders with it, rate and depth by an intensity 0..1),
// small weight shifts and head tilts while standing about, and eye contact (the neck and head turned toward a point, within
// limits, eased, and given up while a clip drives the head itself). Laid over whatever pose the animation made, as small
// rotations of the posed bone matrices about their posed pivots: it never replaces a clip's keys, and the feet never move
// (nothing below the waist is turned). Pure maths (no GL): engine-test/motionlayer_test checks its bounds.
#pragma once

#include <cstdint>
#include <vector>

#include "wmath.h"

namespace wc {

class M2Model;

// Options' Immersion page, for everyone on stage: [breathing] and [idle] 0..1 scale those motions, [lookAt] eye contact.
struct MotionParams {
    float breathing = 0.7f;
    float idle = 0.6f;
    bool lookAt = true;
};

// The limits and rates (radians, seconds).
struct MotionLimits {
    static constexpr float kBreathMinPerS = 12.0f / 60.0f;    // breaths a second at intensity 0
    static constexpr float kBreathMaxPerS = 30.0f / 60.0f;    // ... at 1
    static constexpr float kBreathMinRad = 0.012f;            // the chest's pitch at intensity 0 (about 0.7 degrees)
    static constexpr float kBreathMaxRad = 0.040f;            // ... at 1 (about 2.3 degrees), times MotionParams::breathing
    static constexpr float kNeckTakesBack = 0.65f;            // the neck turns back this share of the chest's breath
    static constexpr float kShoulderShare = 0.35f;            // the clavicles rise by this share of the chest's breath
    static constexpr float kSwayRad = 0.022f;                 // the waist's side-to-side weight shift (about 1.3 degrees)
    static constexpr float kTiltRad = 0.035f;                 // the head's idle tilt (about 2 degrees)
    static constexpr float kLookYaw = 1.05f;                  // eye contact: 60 degrees either way
    static constexpr float kLookUp = 0.35f;                   // 20 degrees up
    static constexpr float kLookDown = 0.45f;                 // 26 degrees down
    static constexpr float kLookGiveUp = 1.9f;                // a target further round than 109 degrees isn't looked at
    static constexpr float kLookTau = 0.35f;                  // the turn's time constant
    static constexpr float kNeckShare = 0.4f;                 // of a look-at turn, the neck's (the head the rest)
    static constexpr float kIntensityTau = 1.2f;              // the intensity eased in the engine too (a missed frame never jumps)
};

// One actor's layer. [on] once the app drives it (setMotion): the creator's and the thumbnails' actors never are.
struct MotionState {
    bool on = false;
    float intensity = 0.2f;        // asked (setMotion)
    float shown = 0.2f;            // eased
    float phase = 0;               // the breath's phase (radians), integrated so a change of rate never jumps
    float yaw = 0, pitch = 0;      // the look-at turn applied now
    float clipWeight = 1;          // 1: the clip leaves the head free; 0: it drives the head (no look-at)
    float idleWeight = 1;          // the weight shifts and tilts: 1 standing about, less while a clip plays
    int target = 0;                // the actor looked at (>0), -1 the camera, 0 nobody
    V3 targetPoint;                // where (world), filled each frame
    bool hasTarget = false;
    uint32_t seed = 1;
    uint32_t lastMs = 0;
    bool started = false;
    // the bones, found once per model (-2: not looked for yet, -1: none)
    const M2Model* model = nullptr;
    int waist = -2, chest = -2, neck = -2, head = -2, lclav = -2, rclav = -2;
    std::vector<std::vector<int>> subtrees;   // waist, chest, neck, head, lclav, rclav: each bone and every one under it
};

namespace motion {

// Breaths a second and the chest's pitch amplitude (radians) at an intensity, with Options' breathing share.
float breathRate(float intensity);
float breathDepth(float intensity, float breathing);
// The idle motions' amplitudes (radians) at an intensity: they quieten as it rises (a body taken up by the moment).
float swayAmount(float intensity, float idle);
float tiltAmount(float intensity, float idle);
// Slow, never-repeating noise in -1..1 (three sines of unrelated periods), per actor seed.
float idleNoise(float seconds, uint32_t seed, int channel);

// The look-at turn wanted for a direction: [forward] the head's facing now, [up] the neck's up, [toTarget] from the head to
// the target (all in the same frame). Yaw about [up] and pitch, both clamped to the limits; false (and 0, 0) when the
// target is behind (past kLookGiveUp) or too near to say.
bool lookAngles(const V3& forward, const V3& up, const V3& toTarget, float& yaw, float& pitch);
// [value] eased toward [goal] by [dt] seconds with time constant [tau].
float ease(float value, float goal, float dt, float tau);

// How strongly a clip drives the head: its Head and Neck tracks' largest turn away from their first key (radians) to a
// weight 1 (barely moves: look-at free) .. 0 (turns more than about 20 degrees: the clip's own).
float clipHeadWeight(float maxTurn);

// A rotation by [angle] about the unit [axis] at [pivot], as a matrix (model space).
M4 turnAbout(const V3& pivot, const V3& axis, float angle);
// A quaternion's rotation angle from another (radians, 0..pi).
float angleBetween(const Quat& a, const Quat& b);

// The layer on one actor's posed bone matrices (model space) at [nowMs]: [world] its placement (target points into model
// space), [params] Options'. Changes [mats] in place; [st] keeps its phases. Cheap: a few matrix products per bone of the
// upper body.
void apply(MotionState& st, const M2Model& model, std::vector<M4>& mats, const M4& worldInverse, const MotionParams& params,
           uint32_t nowMs);

// The rigid inverse of a placement (translation, rotation about z, uniform scale).
M4 placementInverse(const V3& position, float facing, float scale);

} // namespace motion
} // namespace wc
