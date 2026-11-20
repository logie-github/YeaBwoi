// Human scale (the live scene's people, anim/HumanScale.kt): how tall a body stands in the pose imported animations put it
// in. Header only: scene.cpp sizes actors and fits root motion with it, engine-test/humanscale_test.cpp checks it on the
// game's models without a GL context.
#pragma once

#include <cmath>
#include <vector>

#include "boneanim.h"
#include "m2.h"

namespace wc {

// A body standing as an imported clip stands it: every bone the clips aim (kAnimRest, the reference night elf woman's rest
// directions) turned to point as the reference's do, through fitBoneAnim, the rest at the bind. A hunched model (an orc
// man, a tauren) stands up and bent legs (a worgen's, a draenei's) straighten, as they do under a Sims clip; Warcraft's own
// animations never use this. All in model units, z up, from the model's origin:
//   head   the Head joint above the ground (the base of the skull: hair, horns and ears left out),
//   pelvis the Root joint above the ground (what a clip's vertical root motion is measured against),
//   ground where the soles end up (the feet's joints moved by the straightening, each sole kept its bind height below
//          its joint): below 0 the straightened legs reach under the model's floor, and the body is lifted by -ground.
//   bindHead the Head joint in the model's own stance (its bind pose, the ground at 0), as Warcraft's animations stand it.
// ok false: the model lacks a Root, Head or either foot (a creature's skeleton): it can't be measured.
// height(): what human scale sizes by, the mean of the two stances' heads: they differ by under 4% but for the hunched
// tauren (the man's 2.55 in his stance, 3.23 straightened), whose size then sits between the two, neither stance off by
// more than about 12%.
struct StandFit {
    float head = 0, pelvis = 0, ground = 0, bindHead = 0;
    bool ok = false;
    float height() const { return ok ? 0.5f * (head + bindHead) : 0.0f; }
};

// The bone matrices of that stance (model space; empty when the model has no bones). flesh.h measures the body in it too.
inline void standMatrices(const M2Model& m, std::vector<M4>& mats) {
    mats.clear();
    if (m.bones.empty()) return;
    // one identity key (the reference's rest) on every bone the clips aim and on what each aims at
    BoneAnim stand;
    for (const AnimRest& r : kAnimRest) {
        for (const char* n : {r.bone, r.aim}) {
            bool have = false;
            for (const BoneAnimTrack& t : stand.tracks) if (t.bone == n) { have = true; break; }
            if (have) continue;
            BoneAnimTrack t;
            t.bone = n;
            t.rotation = true;
            t.times = {0.0f};
            t.values = {0.0f, 0.0f, 0.0f, 1.0f};
            stand.tracks.push_back(t);
        }
    }
    fitBoneAnim(stand, m);
    std::vector<BonePose> poses(m.bones.size());
    for (const BoneAnimTrack& t : stand.tracks) {
        int b = m.findWowBone(t.bone);
        if (b < 0 || size_t(b) >= poses.size() || t.values.size() < 4) continue;
        poses[size_t(b)].r = Quat(t.values[0], t.values[1], t.values[2], t.values[3]).normalized();
    }
    m.bonesToMatrices(poses, mats);
}

inline StandFit measureStand(const M2Model& m) {
    StandFit out;
    int root = m.findWowBone("Root"), head = m.findWowBone("Head");
    int lf = m.findWowBone("L_Foot"), rf = m.findWowBone("R_Foot");
    if (root < 0 || head < 0 || lf < 0 || rf < 0 || m.bones.empty()) return out;
    std::vector<M4> mats;
    standMatrices(m, mats);
    if (mats.size() != m.bones.size()) return out;
    auto at = [&](int b) { return mats[size_t(b)].point(m.bones[size_t(b)].pivot); };
    float soleL = at(lf).z - m.bones[size_t(lf)].pivot.z;
    float soleR = at(rf).z - m.bones[size_t(rf)].pivot.z;
    out.ground = std::fmin(soleL, soleR);
    out.head = at(head).z - out.ground;
    out.pelvis = at(root).z - out.ground;
    out.bindHead = m.bones[size_t(head)].pivot.z;
    out.ok = std::isfinite(out.head) && std::isfinite(out.pelvis) && out.head > 0.05f && out.pelvis > 0.02f && out.bindHead > 0.05f;
    return out;
}

} // namespace wc
