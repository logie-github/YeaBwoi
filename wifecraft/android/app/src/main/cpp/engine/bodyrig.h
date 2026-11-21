// The skeleton edited per actor: the creator's Leg Length slider (sculpt.h kSculptLegLength) and the Eve body's proportions
// (evebody.h EveProportions: her legs, torso, arms and hands at a realistic length under the Warcraft head). Header only and
// GL-free (scene.cpp's animate uses it; engine-test/sculpt_test checks its maths on a made-up skeleton).
//
// The shared model is never changed. Instead each bone gets a bind deformation D (bind pose -> edited bind pose), by its
// nearest named ancestor:
//   - the torso (Root, Pelvis, the spine, the chest, the breasts): stretched in height by `torso` about the hip joints'
//     height, so the neck comes down (or up) and the hips stay,
//   - the neck and head: moved as they are with the top of the torso (the head keeps its size),
//   - each clavicle: moved with the torso, stretched along itself (sternum -> shoulder joint) by `spread`,
//   - each upper arm and forearm: moved with the joint above, stretched along itself by `arms`,
//   - each hand and its fingers: moved with the wrist, scaled by `hands` about it,
//   - each thigh: stretched along its axis (hip -> knee) by `legs` about the hip; each calf (and the ankle's twist, a hoof's
//     hock) moved with the knee and stretched along the shin; each foot (toes, soles) moved as it is with the ankle.
// Stretches act along the bone only, so girths stay. D agrees with itself at every joint (the parent's and the child's maps
// take the child's pivot to the same place), so the skin stays joined. The posed matrices are the model's own animation run
// on the edited skeleton (the pivots D moves them to; the shins' key translations scaled) times D: any vertex in the model's
// bind space (the Warcraft body, the Eve body fitted to the unedited skeleton, cloth, attachments) is carried into the
// edited bind and posed there, so everything skinned on the actor agrees with no refit. The whole body is then raised or
// lowered so the feet meet the ground as the animation puts them on the model's own skeleton (standing: by `lift`).
//
// legLengthFactor maps the Leg Length slider (-1..1) to a factor on `legs`: 0.85 at -1, 1 at 0, 1.2 at +1 (linear on each
// side); Eve's own proportions are multiplied by it.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

constexpr float kLegShortest = 0.85f, kLegLongest = 1.2f;

inline float legLengthFactor(float slider) {
    if (!std::isfinite(slider)) return 1.0f;
    float s = std::clamp(slider, -1.0f, 1.0f);
    return s < 0 ? 1.0f + (1.0f - kLegShortest) * s : 1.0f + (kLegLongest - 1.0f) * s;
}

// The factors of an edit (1: as the model has it).
struct BodyScale {
    float legs = 1, torso = 1, arms = 1, hands = 1, spread = 1;
    bool identity() const {
        return std::fabs(legs - 1) < 1e-4f && std::fabs(torso - 1) < 1e-4f && std::fabs(arms - 1) < 1e-4f && std::fabs(hands - 1) < 1e-4f &&
               std::fabs(spread - 1) < 1e-4f;
    }
    bool operator==(const BodyScale& o) const {
        return legs == o.legs && torso == o.torso && arms == o.arms && hands == o.hands && spread == o.spread;
    }
};

enum LegPart : uint8_t {
    kLegNone = 0, kLegThigh = 1, kLegCalf = 2, kLegFoot = 3,
    kPartTorso = 4, kPartNeck = 5, kPartClavicle = 6, kPartUpperArm = 7, kPartForearm = 8, kPartHand = 9
};

struct LegEdit {
    bool active = false;
    float factor = 1;                 // the shins' key moves are scaled by this (the legs' factor)
    BodyScale scale;
    const void* model = nullptr;      // what it was made for (Scene: made again when the model or the factors change)
    const void* base = nullptr;       // the rig's pivots it was made on (the Eve rig's; null: the model's own)
    std::vector<V3> pivots;           // per bone: the edited bind pivots
    std::vector<M4> bind;             // per bone: D, the model's bind pose -> the edited bind pose
    std::vector<uint8_t> part;        // per bone: LegPart
    float lift = 0;                   // the bind pose's: the feet's mean drop (model units, z)
    V3 knee[2], ankle[2];             // the edited joints (left, right), for tests and measures
    V3 shoulder[2], wrist[2], neck;
};

// The named bones (indices into the skeleton; -1 when missing). Legs and torso are needed; the rest are edited when there.
struct LegBones { int thigh = -1, calf = -1, foot = -1; };
struct ArmBones { int clavicle = -1, upperArm = -1, forearm = -1, hand = -1; };
struct BodyBones {
    LegBones legs[2];
    ArmBones arms[2];
    int root = -1, pelvis = -1, spine = -1, neck = -1;
};

// D for a stretch by k along unit axis d about point c, then moved by t: x -> c + t + S (x - c), S = I + (k - 1) d d^T.
inline M4 legStretch(V3 c, V3 d, float k, V3 t) {
    M4 s;
    float a[3] = {d.x, d.y, d.z};
    for (int r = 0; r < 3; ++r) for (int col = 0; col < 3; ++col) s.at(r, col) = (r == col ? 1.0f : 0.0f) + (k - 1.0f) * a[r] * a[col];
    return M4::translation(c + t) * s * M4::translation(c * -1.0f);
}

// The pure core: the edit from pivots and parents (tests build their own). Inactive when the factors are all ~1, a leg lacks
// a bone, or a factor isn't finite.
// `force`: active even with every factor 1 (`pivots` are another rig's, the Eve rig's, not the model's own).
inline LegEdit makeBodyEdit(const std::vector<V3>& pivots, const std::vector<int16_t>& parents, const BodyBones& bb, BodyScale k,
                            bool force = false) {
    LegEdit e;
    e.factor = k.legs;
    e.scale = k;
    const size_t n = pivots.size();
    e.pivots = pivots;
    e.bind.assign(n, M4());
    e.part.assign(n, kLegNone);
    for (float f : {k.legs, k.torso, k.arms, k.hands, k.spread}) if (!std::isfinite(f) || f <= 0.1f) return e;
    if (k.identity() && !force) { e.active = force; return e; }
    auto ok = [&](int b) { return b >= 0 && size_t(b) < n; };
    for (int s = 0; s < 2; ++s) if (!ok(bb.legs[s].thigh) || !ok(bb.legs[s].calf) || !ok(bb.legs[s].foot)) return e;
    // the torso's stretch in height about the hip joints' mean height
    const float zh = (pivots[size_t(bb.legs[0].thigh)].z + pivots[size_t(bb.legs[1].thigh)].z) * 0.5f;
    const M4 Dt = legStretch(V3(0, 0, zh), V3(0, 0, 1), k.torso, V3(0, 0, 0));
    std::vector<std::pair<int, M4>> named;   // (bone, D) for each named bone
    std::vector<uint8_t> namedPart;
    auto add = [&](int b, uint8_t part, const M4& D) { if (ok(b)) { named.push_back({b, D}); namedPart.push_back(part); } };
    for (int b : {bb.root, bb.pelvis, bb.spine}) add(b, kPartTorso, Dt);
    if (ok(bb.neck)) { V3 p = pivots[size_t(bb.neck)]; add(bb.neck, kPartNeck, M4::translation(Dt.point(p) - p)); e.neck = Dt.point(p); }
    float drop = 0;
    for (int s = 0; s < 2; ++s) {
        // the leg
        V3 hip = pivots[size_t(bb.legs[s].thigh)], knee = pivots[size_t(bb.legs[s].calf)], foot = pivots[size_t(bb.legs[s].foot)];
        V3 dt = knee - hip, ds = foot - knee;
        if (dt.length() < 1e-5f || ds.length() < 1e-5f) return e;
        V3 hip2 = Dt.point(hip);
        V3 knee2 = hip2 + dt * k.legs;
        V3 foot2 = knee2 + ds * k.legs;
        add(bb.legs[s].thigh, kLegThigh, legStretch(hip, dt.normalized(), k.legs, hip2 - hip));
        add(bb.legs[s].calf, kLegCalf, legStretch(knee, ds.normalized(), k.legs, knee2 - knee));
        add(bb.legs[s].foot, kLegFoot, M4::translation(foot2 - foot));
        e.knee[s] = knee2; e.ankle[s] = foot2;
        drop += (foot2.z - foot.z) * 0.5f;
        // the arm, joint by joint from the clavicle's root (each child's map agrees with its parent's at its pivot)
        const ArmBones& a = bb.arms[s];
        if (!ok(a.upperArm) || !ok(a.forearm) || !ok(a.hand)) continue;
        V3 sh = pivots[size_t(a.upperArm)], el = pivots[size_t(a.forearm)], wr = pivots[size_t(a.hand)];
        V3 sh2 = Dt.point(sh);
        if (ok(a.clavicle)) {
            V3 c = pivots[size_t(a.clavicle)], c2 = Dt.point(c), dc = sh - c;
            M4 Dc = dc.length() > 1e-5f ? legStretch(c, dc.normalized(), k.spread, c2 - c) : M4::translation(c2 - c);
            add(a.clavicle, kPartClavicle, Dc);
            sh2 = Dc.point(sh);
        }
        V3 du = el - sh, df = wr - el;
        if (du.length() < 1e-5f || df.length() < 1e-5f) continue;
        V3 el2 = sh2 + du * k.arms, wr2 = el2 + df * k.arms;
        add(a.upperArm, kPartUpperArm, legStretch(sh, du.normalized(), k.arms, sh2 - sh));
        add(a.forearm, kPartForearm, legStretch(el, df.normalized(), k.arms, el2 - el));
        add(a.hand, kPartHand, M4::translation(wr2) * M4::scaling(V3(k.hands, k.hands, k.hands)) * M4::translation(wr * -1.0f));
        e.shoulder[s] = sh2; e.wrist[s] = wr2;
    }
    // each bone by its nearest named ancestor (itself included); a bone under none stays as it is
    for (size_t b = 0; b < n; ++b) {
        int c = int(b), guard = 0;
        bool found = false;
        while (c >= 0 && size_t(c) < n && guard++ < 256 && !found) {
            for (size_t i = 0; i < named.size(); ++i) {
                if (named[i].first != c) continue;
                e.part[b] = namedPart[i];
                e.bind[b] = named[i].second;
                e.pivots[b] = named[i].second.point(pivots[b]);
                found = true;
                break;
            }
            c = parents[size_t(c)];
        }
    }
    e.lift = -drop;
    e.active = true;
    return e;
}

// Legs only (the Leg Length slider alone).
inline LegEdit makeLegEdit(const std::vector<V3>& pivots, const std::vector<int16_t>& parents, const LegBones legs[2], float k) {
    BodyBones bb;
    bb.legs[0] = legs[0]; bb.legs[1] = legs[1];
    BodyScale sc;
    sc.legs = k;
    return makeBodyEdit(pivots, parents, bb, sc);
}

inline BodyBones bodyBonesOf(const M2Model& m) {
    BodyBones bb;
    const char* side[2] = {"L_", "R_"};
    for (int s = 0; s < 2; ++s) {
        std::string p = side[s];
        bb.legs[s].thigh = m.findWowBone(p + "Thigh");
        bb.legs[s].calf = m.findWowBone(p + "Calf");
        bb.legs[s].foot = m.findWowBone(p + "Foot");
        bb.arms[s].clavicle = m.findWowBone(p + "Clavicle");
        bb.arms[s].upperArm = m.findWowBone(p + "UpperArm");
        bb.arms[s].forearm = m.findWowBone(p + "Forearm");
        bb.arms[s].hand = m.findWowBone(p + "Hand");
    }
    bb.root = m.findWowBone("Root");
    bb.pelvis = m.findWowBone("Pelvis");
    bb.spine = m.findWowBone("Spine1");
    bb.neck = m.findWowBone("Neck1");
    return bb;
}

inline LegEdit bodyEditOf(const M2Model& m, BodyScale k) {
    std::vector<V3> pivots(m.bones.size());
    std::vector<int16_t> parents(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) { pivots[i] = m.bones[i].pivot; parents[i] = m.bones[i].parent; }
    LegEdit e = makeBodyEdit(pivots, parents, bodyBonesOf(m), k);
    e.model = &m;
    return e;
}

// The edit on another rig of the same bones (the Eve rig: each bone's pivot where Eve's joint is, evebody.h EveBody::rigPivots):
// the model's animation run about those pivots, active whatever the factors.
inline LegEdit bodyEditFrom(const M2Model& m, const std::vector<V3>& basePivots, BodyScale k) {
    if (basePivots.size() != m.bones.size()) return bodyEditOf(m, k);
    std::vector<int16_t> parents(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) parents[i] = m.bones[i].parent;
    LegEdit e = makeBodyEdit(basePivots, parents, bodyBonesOf(m), k, true);
    e.model = &m;
    e.base = basePivots.data();
    return e;
}

inline LegEdit legEditOf(const M2Model& m, float k) {
    BodyScale sc;
    sc.legs = k;
    return bodyEditOf(m, sc);
}

// M2Model::bonesToMatrices on a skeleton with the edit's pivots (the model's own when `e` is null), the shins' key moves
// scaled by the factor.
inline void legPosed(const std::vector<M2Bone>& bones, const std::vector<BonePose>& poses, const LegEdit* e, std::vector<M4>& posed) {
    const size_t n = bones.size();
    posed.assign(n, M4());
    std::vector<uint8_t> done(n, 0);
    for (int pass = 0; pass < 8; ++pass) {
        bool all = true;
        for (size_t i = 0; i < n; ++i) {
            if (done[i]) continue;
            int p = bones[i].parent;
            if (p >= 0 && size_t(p) < n && !done[size_t(p)]) { all = false; continue; }
            const M2Bone& b = bones[i];
            const V3 pv = e ? e->pivots[i] : b.pivot;
            BonePose q = i < poses.size() ? poses[i] : BonePose();
            if (e && e->part[i] == kLegCalf) q.t = q.t * e->factor;   // (the shin's own key moves, along a longer shin)
            M4 local = M4::translation(pv) * M4::translation(q.t) * M4::rotation(q.r) * M4::scaling(q.s) * M4::translation(pv * -1.0f);
            M4 parent = (p >= 0 && size_t(p) < n) ? posed[size_t(p)] : M4();
            if ((b.flags & 7) && p >= 0) {
                V3 moved = parent.point(pv) - pv;
                if ((b.flags & 6) == 6) parent = (b.flags & 1) ? M4() : M4::translation(moved);
                else if (b.flags & 1) { parent.m[12] -= moved.x; parent.m[13] -= moved.y; parent.m[14] -= moved.z; }
            }
            posed[i] = parent * local;
            done[i] = 1;
        }
        if (all) break;
    }
}

// The posed matrices on the edited skeleton, each times its bone's D, all raised so the feet meet the ground as the
// animation has them: by how much lower the lowest foot bone (the feet's pivots, the toes', the soles') stands than it does
// on the model's own skeleton in the same pose. Standing that is the bind's `lift`; walking, the planted foot stays planted.
// `bones` are the model's (pivots, parents, flags), `poses` the animation's (fewer: the rest at the bind). `liftOut`: the lift.
inline void legEditMatrices(const std::vector<M2Bone>& bones, const std::vector<BonePose>& poses, const LegEdit& e, std::vector<M4>& out,
                            float* liftOut = nullptr) {
    const size_t n = bones.size();
    out.assign(n, M4());
    if (e.pivots.size() != n || e.bind.size() != n || e.part.size() != n) return;
    std::vector<M4> posed, own;
    legPosed(bones, poses, &e, posed);
    legPosed(bones, poses, nullptr, own);
    float lo = 1e30f, loOwn = 1e30f;
    for (size_t i = 0; i < n; ++i) {
        out[i] = posed[i] * e.bind[i];
        if (e.part[i] != kLegFoot) continue;
        lo = std::min(lo, out[i].point(bones[i].pivot).z);
        loOwn = std::min(loOwn, own[i].point(bones[i].pivot).z);
    }
    float lift = lo < 1e29f && loOwn < 1e29f ? loOwn - lo : e.lift;
    if (!std::isfinite(lift)) lift = e.lift;
    const M4 up = M4::translation(V3(0, 0, lift));
    for (size_t i = 0; i < n; ++i) out[i] = up * out[i];
    if (liftOut) *liftOut = lift;
}

} // namespace wc
