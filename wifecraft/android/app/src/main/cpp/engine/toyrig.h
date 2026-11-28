// The body's reactions to a toy as one more additive layer on the posed matrices (README "Toy interaction: reactions"). The app's
// ToyReactions model (Kotlin) decides the numbers each frame; this applies them: small turns of the posed bones about their posed
// pivots, in model axes (x forward, y left, z up), and a hand reached to the toy by two-bone IK (bonelayer.h's solveReach).
//
// Order on a posed body (Scene::animate): the clip, the imported animation, the soft body's local poses, the matrices, the motion layer
// (breathing, weight shifts, eye contact: the life layers), the bone layer (a touch's reaction), the reach, then this layer: it adds
// to all of them and never replaces a key. Nothing below the hips moves but the thighs and knees, by a few degrees.
//
// Pure maths (no GL): engine-test/toyrig_test checks the turns on a synthetic skeleton.
#pragma once

#include <cstdint>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {
namespace toyrig {

// The numbers asked for, radians unless said (all eased in the engine too: a missed frame never jumps).
struct Pose {
    float breath = 0;        // the chest's pitch amplitude of a breath (the model adds a half share to the shoulders)
    float breathHz = 0.3f;   // breaths a second
    float flutter = 0;       // the chest and belly's fast small shake
    float arch = 0;          // the back's arch (spine and chest lean back, + = back)
    float hipTilt = 0;       // the pelvis' pitch (+ = the front rises); the thighs stay where they are
    float thighPart = 0;     // each thigh turned outward
    float kneeFlex = 0;      // each knee bent
    float headBack = 0;      // the head tilted back
    float shoulder = 0;      // the clavicles lifted
    float tremor = 0;        // 0..1: the thighs and belly tremble
    float reachL = 0, reachR = 0;   // 0..1: that hand put on the toy
};

constexpr float kTremorHz = 9.0f;
constexpr float kMaxTurn = 0.35f;   // no turn ever exceeds 20 degrees

struct State {
    bool on = false;
    Pose want, shown;
    float phase = 0, tremorPhase = 0;
    uint32_t lastMs = 0;
    bool started = false;
    const M2Model* model = nullptr;
    int pelvis = -2, waist = -2, chest = -2, neck = -2, head = -2, lclav = -2, rclav = -2, lthigh = -2, rthigh = -2, lcalf = -2, rcalf = -2;
    int uarm[2] = {-2, -2}, farm[2] = {-2, -2}, hand[2] = {-2, -2}, finger[2] = {-2, -2};
    std::vector<std::vector<int>> sub;   // per bone, found once: subtree lists indexed by role below
};

// Applies `st` to `mats` (as posed, model space) for the time `nowMs`; `entryModel` is the toy's entry in model space (the hands'
// goal). Returns the turns applied for the tests' sake (the pelvis pitch after easing).
float apply(State& st, const M2Model& m, std::vector<M4>& mats, uint32_t nowMs, const V3& entryModel);

} // namespace toyrig
} // namespace wc
