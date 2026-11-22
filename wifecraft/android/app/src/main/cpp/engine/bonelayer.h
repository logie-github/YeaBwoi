// Two additive layers over a posed body, for the stages only some bodies take part in (Sindragosa's partner stage):
//
//  * a bone layer: rotation keys (anim/ClipLayers.kt bakes them; the same track format as an imported animation) turned onto
//    whatever pose the actor's own animation made, about each bone's posed pivot, in model axes (x forward, y left, z up), on
//    the bone and every bone under it, in the order of the tracks (root of a chain first, so a neck bends as a chain). Nothing
//    of the game's own animation is replaced: her breathing, her wings and her tail go on under it.
//  * a reach: one arm of a person put on a point of another actor's body (a hand on a dragon's head): two-bone IK on the
//    shoulder, elbow and wrist as posed, the hand laid along a direction. A weight 0..1 blends the arm from the pose the
//    clip gave it to the target, so it rises to the head and comes down again.
//
// Pure maths (no GL): engine-test/bonelayer_test checks it without a game file.
#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

#include "boneanim.h"
#include "wmath.h"

namespace wc {
namespace layer {

// The rotation of a rotation track at `t` seconds: the key pair around it (a loop of length `loopLen` wraps from the last
// key to the first, as boneKeys has it), slerped. A track with no keys: the identity.
inline Quat sampleRotation(const BoneAnimTrack& tr, float t, float loopLen) {
    if (tr.times.empty() || tr.values.size() < tr.times.size() * 4) return Quat();
    size_t a, b; float f;
    boneKeys(tr.times, t, loopLen, a, b, f);
    auto key = [&](size_t i) { const float* v = &tr.values[i * 4]; return Quat(v[0], v[1], v[2], v[3]).normalized(); };
    return a == b ? key(a) : slerp(key(a), key(b), f);
}

// A position track's value (x,y,z) at `t`, linearly between its keys (the loop wraps). No keys: `fallback`.
inline V3 samplePosition(const BoneAnimTrack& tr, float t, float loopLen, const V3& fallback) {
    if (tr.times.empty() || tr.values.size() < tr.times.size() * 3) return fallback;
    size_t a, b; float f;
    boneKeys(tr.times, t, loopLen, a, b, f);
    auto key = [&](size_t i) { const float* v = &tr.values[i * 3]; return V3(v[0], v[1], v[2]); };
    return a == b ? key(a) : lerp(key(a), key(b), f);
}

// Where a looping layer is `nowMs - startMs` into its loop (seconds, wrapped to [0, duration)); a layer that doesn't
// loop holds its last key, so the time simply runs on.
inline float layerTime(uint32_t nowMs, uint32_t startMs, float duration, bool loop) {
    float t = float(int32_t(nowMs - startMs)) / 1000.0f;
    if (!(t > 0)) return 0;
    if (loop && duration > 1e-4f) t = std::fmod(t, duration);
    return t;
}

// The shortest rotation taking unit `from` onto unit `to`, as a quaternion (boneanim.h's quatArc, kept apart so this header
// stands on its own).
inline Quat arc(const V3& from, const V3& to) { return quatArc(from.normalized(), to.normalized()); }

// A rotation `q` about `pivot` as a matrix (model space).
inline M4 rotateAbout(const V3& pivot, const Quat& q) {
    return M4::translation(pivot) * M4::rotation(q) * M4::translation(pivot * -1.0f);
}

// The arm as posed (model space): the shoulder's, elbow's and wrist's pivots and the hand's far end (its middle finger's
// root), and what the solve wants of it.
struct ArmPose { V3 shoulder, elbow, wrist, fingers; };
struct ReachGoal {
    V3 target;          // where the palm's centre is to be
    V3 along;           // the direction the fingers are to point (unit): the hand lies along it
    V3 pole;            // the side the elbow bends toward (any length; 0: the elbow stays on its side as posed)
    float weight = 1;   // 0 the arm as posed .. 1 the palm on the target
};
// The three turns the solve makes, to be laid on the posed matrices in this order: `upper` about the shoulder on the upper arm
// and everything under it, `fore` about `elbow` (where the elbow now is) on the forearm and under, `hand` about `wrist`
// (where the wrist now is) on the hand and under. `palm` is where the palm's centre comes to; `reachable` false when the
// target is beyond the arm's length (the arm stretches straight toward it and stops short).
struct ReachTurns {
    Quat upper, fore, hand;
    V3 elbow, wrist, palm;
    bool reachable = true;
};

inline ReachTurns solveReach(const ArmPose& arm, const ReachGoal& goal) {
    ReachTurns out;
    const V3 S = arm.shoulder;
    const float l1 = (arm.elbow - S).length(), l2 = (arm.wrist - arm.elbow).length();
    const V3 handNow = arm.fingers - arm.wrist;
    const float handLen = handNow.length();
    const V3 handDirNow = handLen > 1e-6f ? handNow * (1.0f / handLen) : V3(1, 0, 0);
    // the palm's centre is half-way along the hand: the wrist stands that far back from it along the hand's direction
    const float w = std::clamp(goal.weight, 0.0f, 1.0f);
    const V3 palmNow = arm.wrist + handNow * 0.5f;
    // the hand's direction goes from the one the clip gave it to the wanted one as the weight does
    V3 wanted = goal.along.length() > 1e-6f ? goal.along.normalized() : handDirNow;
    V3 handDir = lerp(handDirNow, wanted, w);
    handDir = handDir.length() > 1e-6f ? handDir.normalized() : wanted;
    V3 palmGoal = lerp(palmNow, goal.target, w);
    V3 wristGoal = palmGoal - handDir * (handLen * 0.5f);
    V3 toWrist = wristGoal - S;
    float d = toWrist.length();
    const float lo = std::fabs(l1 - l2) + 1e-4f, hi = l1 + l2 - 1e-4f;
    if (d > hi) { out.reachable = false; wristGoal = S + toWrist * (hi / std::max(d, 1e-8f)); d = hi; }
    if (d < lo) { wristGoal = S + (d > 1e-8f ? toWrist * (lo / d) : V3(0, 0, -lo)); d = lo; }
    const V3 u = (wristGoal - S) * (1.0f / std::max(d, 1e-8f));
    const float a = (l1 * l1 - l2 * l2 + d * d) / (2 * d);
    const float h = std::sqrt(std::max(0.0f, l1 * l1 - a * a));
    // the elbow bends toward the pole (else the side it is on now)
    V3 pole = goal.pole.length() > 1e-6f ? goal.pole : (arm.elbow - S);
    V3 perp = pole - u * pole.dot(u);
    if (perp.length() < 1e-6f) perp = (arm.elbow - S) - u * (arm.elbow - S).dot(u);
    if (perp.length() < 1e-6f) perp = V3(0, 0, -1) - u * u.dot(V3(0, 0, -1));
    perp = perp.normalized();
    V3 elbowGoal = S + u * a + perp * h;
    out.upper = arc(arm.elbow - S, elbowGoal - S);
    // the upper arm turned about the shoulder takes the wrist with it
    M4 up = rotateAbout(S, out.upper);
    V3 wristAfter = up.point(arm.wrist);
    V3 elbowAfter = up.point(arm.elbow);
    out.fore = arc(wristAfter - elbowAfter, wristGoal - elbowAfter);
    M4 fo = rotateAbout(elbowAfter, out.fore);
    V3 wristFinal = fo.point(wristAfter);
    V3 fingersAfter = fo.point(up.point(arm.fingers));
    V3 handAfter = fingersAfter - wristFinal;
    // the hand lies along `handDir` as far as the weight has brought it
    V3 dirAfter = handAfter.length() > 1e-6f ? handAfter.normalized() : handDirNow;
    Quat full = arc(dirAfter, handDir);
    out.hand = full;
    out.elbow = elbowAfter;
    out.wrist = wristFinal;
    V3 palm = rotateAbout(wristFinal, out.hand).point(fingersAfter);
    out.palm = wristFinal + (palm - wristFinal) * 0.5f;
    return out;
}

} // namespace layer
} // namespace wc
