// Imported animations (anim/Anim, by WoW-side bone names) as the engine plays them: their tracks, the key pair a moment
// falls between (a loop wraps from its last key to its first), and fitting a clip to the model it plays on. Header only:
// scene.cpp plays them, engine-test/boneanim_test.cpp checks them without a GL context.
#pragma once

#include <cmath>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

// An imported animation on a person: each track replaces one bone's local rotation (x,y,z,w about its pivot, in its
// parent's frame: at rest every M2 bone's is the identity, so the model's axes) or translation (model units, given for
// kAnimRootHeight and scaled to the model's own Root). Times in seconds.
struct BoneAnimTrack {
    std::string bone;
    bool rotation = true;
    std::vector<float> times;
    std::vector<float> values;     // 4 per time (rotation) or 3 (position)
};
struct BoneAnim {
    std::vector<BoneAnimTrack> tracks;
    float duration = 0;            // seconds; 0 or one key: a held pose
    bool loop = false;             // otherwise the last key holds
};

// The key pair around `t` (seconds) and how far between them it is. With `loopLen` (a looping clip's length, 0: none)
// longer than the last key, a moment past the last key moves on toward the first, which comes again at `loopLen`: no
// hitch where the clip comes round. Otherwise the first and the last key hold outside the keys.
inline void boneKeys(const std::vector<float>& times, float t, float loopLen, size_t& a, size_t& b, float& f) {
    a = b = 0; f = 0;
    if (times.empty()) return;
    size_t last = times.size() - 1;
    if (times.size() == 1 || !(t > times[0])) return;
    if (t >= times[last]) {
        a = b = last;
        if (loopLen > times[last] + 1e-4f && times[0] <= 1e-4f && t < loopLen) { b = 0; f = (t - times[last]) / (loopLen - times[last]); }
        return;
    }
    size_t lo = 0, hi = last;
    while (hi - lo > 1) { size_t mid = (lo + hi) / 2; if (times[mid] <= t) lo = mid; else hi = mid; }
    a = lo; b = hi;
    float span = times[b] - times[a];
    f = span > 0 ? (t - times[a]) / span : 0;
}

// The rest directions the importer converted every clip for (anim/Retarget: assets/sims_profile.json's wowRest, written by
// tools/wow_bones.py from the night elf woman, 921844): each bone toward the bone it aims at, in model axes. The unit
// tests check this table against the profile (BoneAnimRestTableTest).
struct AnimRest { const char* bone; const char* aim; float x, y, z; };
inline const AnimRest kAnimRest[] = {
    {"Spine1", "Spine2", -0.0789f, 0.0f, 0.9969f},
    {"Spine2", "Chest", 0.0f, 0.0f, 1.0f},
    {"Chest", "Neck1", -0.1289f, 0.0f, 0.9917f},
    {"Neck1", "Head", 0.1717f, 0.0f, 0.9852f},
    {"L_Clavicle", "L_UpperArm", -0.4093f, 0.8902f, 0.2f},
    {"L_UpperArm", "L_Forearm", -0.0257f, 0.8369f, -0.5467f},
    {"L_Forearm", "L_Hand", 0.1785f, 0.8212f, -0.542f},
    {"L_Hand", "L_Middle1", 0.3139f, 0.8717f, -0.3763f},
    {"L_Thumb1", "L_Thumb2", 0.4717f, 0.5155f, -0.7154f},
    {"L_Index1", "L_Index2", 0.2374f, 0.6685f, -0.7048f},
    {"L_Middle1", "L_Middle2", 0.1899f, 0.5686f, -0.8004f},
    {"L_Ring1", "L_Ring2", 0.1363f, 0.5218f, -0.8421f},
    {"L_Pinky1", "L_Pinky2", 0.1208f, 0.6607f, -0.7408f},
    {"L_Thigh", "L_Calf", -0.0424f, 0.0852f, -0.9955f},
    {"L_Calf", "L_Foot", -0.1563f, 0.081f, -0.9844f},
    {"L_Foot", "L_Toe", 0.9381f, 0.004f, -0.3463f},
    {"R_Clavicle", "R_UpperArm", -0.4093f, -0.8902f, 0.2f},
    {"R_UpperArm", "R_Forearm", -0.0257f, -0.8369f, -0.5467f},
    {"R_Forearm", "R_Hand", 0.1785f, -0.8212f, -0.542f},
    {"R_Hand", "R_Middle1", 0.3139f, -0.8717f, -0.3762f},
    {"R_Thumb1", "R_Thumb2", 0.4717f, -0.5155f, -0.7154f},
    {"R_Index1", "R_Index2", 0.2374f, -0.6685f, -0.7048f},
    {"R_Middle1", "R_Middle2", 0.1899f, -0.5686f, -0.8004f},
    {"R_Ring1", "R_Ring2", 0.1363f, -0.5218f, -0.8421f},
    {"R_Pinky1", "R_Pinky2", 0.1208f, -0.6607f, -0.7408f},
    {"R_Thigh", "R_Calf", -0.0424f, -0.0836f, -0.9956f},
    {"R_Calf", "R_Foot", -0.1564f, -0.0804f, -0.9844f},
    {"R_Foot", "R_Toe", 0.9381f, -0.004f, -0.3463f},
};

inline Quat quatMul(const Quat& a, const Quat& b) {
    return Quat(a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
                a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z);
}
inline Quat quatInv(const Quat& a) { return Quat(-a.x, -a.y, -a.z, a.w); }
// The shortest turn taking direction `from` onto direction `to` (both unit).
inline Quat quatArc(V3 from, V3 to) {
    float d = from.dot(to);
    if (d < -0.999999f) {
        V3 axis = V3(1, 0, 0).cross(from);
        if (axis.dot(axis) < 1e-6f) axis = V3(0, 1, 0).cross(from);
        axis = axis.normalized();
        return Quat(axis.x, axis.y, axis.z, 0);
    }
    V3 c = from.cross(to);
    return Quat(c.x, c.y, c.z, 1 + d).normalized();
}

// Fits a clip converted for the reference skeleton to `m`: a bone whose rest direction on this model (its pivot toward
// its aim's) differs from the reference's (kAnimRest) is turned by the difference, R = arc(model's, reference's), and its
// key becomes inv(R_parent) * key * R, the parent being the nearest bone above it in the model that a rotation track of
// the clip drives. The clip's pose then lands on this model's limbs as on the reference's (a human's shoulders sit 25
// degrees from a night elf's at rest: unfitted, the arms of every clip came out that far off). Returns the tracks turned.
inline int fitBoneAnim(BoneAnim& anim, const M2Model& m) {
    // the clip's own rotation tracks: a bone whose aim has none was converted without the rest turn (Retarget's Q)
    std::map<std::string, bool> rotated;
    for (const BoneAnimTrack& t : anim.tracks) if (t.rotation) rotated[t.bone] = true;
    std::map<std::string, Quat> fix;
    for (const AnimRest& r : kAnimRest) {
        if (!rotated.count(r.bone) || !rotated.count(r.aim)) continue;
        int i = m.findWowBone(r.bone), j = m.findWowBone(r.aim);
        if (i < 0 || j < 0) continue;
        V3 d = m.bones[size_t(j)].pivot - m.bones[size_t(i)].pivot;
        if (d.length() < 1e-5f) continue;
        Quat q = quatArc(d.normalized(), V3(r.x, r.y, r.z).normalized());
        if (std::fabs(q.w) < 0.99999f) fix[r.bone] = q;     // more than about half a degree
    }
    if (fix.empty()) return 0;
    std::map<int, std::string> driven;                      // model bone -> the rotation track's name
    for (const BoneAnimTrack& t : anim.tracks) {
        if (!t.rotation) continue;
        int b = m.findWowBone(t.bone);
        if (b >= 0) driven.emplace(b, t.bone);
    }
    int turned = 0;
    for (BoneAnimTrack& t : anim.tracks) {
        if (!t.rotation || t.values.size() < t.times.size() * 4) continue;
        int b = m.findWowBone(t.bone);
        if (b < 0) continue;
        std::string parent;
        for (int i = m.bones[size_t(b)].parent, guard = 0; i >= 0 && size_t(i) < m.bones.size() && guard < 256; i = m.bones[size_t(i)].parent, ++guard) {
            auto it = driven.find(i);
            if (it != driven.end()) { parent = it->second; break; }
        }
        auto own = fix.find(t.bone);
        auto up = parent.empty() ? fix.end() : fix.find(parent);
        if (own == fix.end() && up == fix.end()) continue;
        Quat r = own == fix.end() ? Quat() : own->second;
        Quat pi = up == fix.end() ? Quat() : quatInv(up->second);
        for (size_t k = 0; k < t.times.size(); ++k) {
            float* v = &t.values[k * 4];
            Quat q = quatMul(quatMul(pi, Quat(v[0], v[1], v[2], v[3]).normalized()), r).normalized();
            v[0] = q.x; v[1] = q.y; v[2] = q.z; v[3] = q.w;
        }
        // keep neighbouring keys on the same side (a turn and its negation are the same turn; slerp takes the short way)
        for (size_t k = 1; k < t.times.size(); ++k) {
            float* p = &t.values[(k - 1) * 4];
            float* v = &t.values[k * 4];
            if (p[0] * v[0] + p[1] * v[1] + p[2] * v[2] + p[3] * v[3] < 0) for (int c = 0; c < 4; ++c) v[c] = -v[c];
        }
        ++turned;
    }
    return turned;
}

} // namespace wc
