// A body's flesh around its bones (anim/FleshProfile.kt reads it): for each main bone segment of a dressed model (its shown
// geosets, its body shape), how far its skinned vertices reach from the segment, in the stance imported clips stand it in
// (humanscale.h standMatrices: every aimed bone pointing as the reference's do). Header only: scene.cpp measures an actor
// with it (Scene::actorFlesh), engine-test checks it on a synthetic body and on the game's models without a GL context.
//
// Each segment runs from one joint to the next (the pelvis from the Root to between the hip joints; the head and the hands
// on past their joint by their own length, `tip`). Its frame: the axis along it; n = sign x normalize(side x axis), where
// side runs from the right hip joint to the left (the pelvis, legs and feet) or the right upper arm to the left (the spine,
// chest and head), the sign making n the body's front in that stance (the foot's n is its top); m = axis x n. Arms and hands
// are round (their side axis is along them). Per segment, from the vertices whose bones belong to it (each bone to its
// nearest named ancestor, each vertex counted by its skin weight on that bone):
//   front, back  the weighted 90th percentile of the vertices' reach along +n and -n,
//   side         the same of their reach either way along m (round: of their distance from the axis, all three alike),
//   start, end   how far they reach past the segment's ends along it (0 when under a tenth do),
//   tip          an extended segment's length past its joint (the 95th percentile).
// All in body units: the standing pelvis (the Root joint over the soles, StandFit.pelvis) is 1. `rest` holds the main
// joints in that stance in the reference skeleton's units (the pelvis 1.441 over the soles, the Root at the reference's x
// and y): anim/BodyGeometry turns a clip's rotations on this body's own bones with them.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "humanscale.h"
#include "m2.h"
#include "wmath.h"

namespace wc {

enum FleshSegment {
    kFleshPelvis, kFleshSpine, kFleshChest, kFleshHead,
    kFleshLUpperArm, kFleshLForearm, kFleshLHand, kFleshRUpperArm, kFleshRForearm, kFleshRHand,
    kFleshLThigh, kFleshLCalf, kFleshLFoot, kFleshRThigh, kFleshRCalf, kFleshRFoot,
    kFleshCount
};

// A segment: from joint `from` to `to` ("Hips": between the hip joints), or, with `extend`, from `from` on past `to` along
// extend->to by its tip. `sign` makes n the front; `round` arms and hands; `upper` its side axis from the upper arms.
struct FleshSegDef { const char* name; const char* from; const char* to; const char* extend; float sign; bool round; bool upper; };
inline const FleshSegDef kFleshSegs[kFleshCount] = {
    {"Pelvis", "Root", "Hips", nullptr, -1, false, false},
    {"Spine", "Spine1", "Chest", nullptr, 1, false, true},
    {"Chest", "Chest", "Neck1", nullptr, 1, false, true},
    {"Head", "Neck1", "Head", "Neck1", 1, false, true},
    {"L_UpperArm", "L_UpperArm", "L_Forearm", nullptr, 1, true, true},
    {"L_Forearm", "L_Forearm", "L_Hand", nullptr, 1, true, true},
    {"L_Hand", "L_Hand", "L_Hand", "L_Forearm", 1, true, true},
    {"R_UpperArm", "R_UpperArm", "R_Forearm", nullptr, 1, true, true},
    {"R_Forearm", "R_Forearm", "R_Hand", nullptr, 1, true, true},
    {"R_Hand", "R_Hand", "R_Hand", "R_Forearm", 1, true, true},
    {"L_Thigh", "L_Thigh", "L_Calf", nullptr, -1, false, false},
    {"L_Calf", "L_Calf", "L_Foot", nullptr, -1, false, false},
    {"L_Foot", "L_Foot", "L_Toe", nullptr, -1, false, false},
    {"R_Thigh", "R_Thigh", "R_Calf", nullptr, -1, false, false},
    {"R_Calf", "R_Calf", "R_Foot", nullptr, -1, false, false},
    {"R_Foot", "R_Foot", "R_Toe", nullptr, -1, false, false},
};

// The joints `rest` holds, in anim/BodyGeometry.JOINTS' order, and the segment each named bone's vertices belong to.
inline const char* const kFleshJoints[] = {
    "Root", "Pelvis", "Spine1", "Spine2", "Chest", "Neck1", "Head",
    "L_Clavicle", "L_UpperArm", "L_Forearm", "L_Hand", "R_Clavicle", "R_UpperArm", "R_Forearm", "R_Hand",
    "L_Thigh", "L_Calf", "L_Foot", "L_Toe", "R_Thigh", "R_Calf", "R_Foot", "R_Toe",
};
constexpr int kFleshJointCount = int(sizeof(kFleshJoints) / sizeof(kFleshJoints[0]));
inline const std::pair<const char*, int> kFleshBoneSegs[] = {
    {"Root", kFleshPelvis}, {"Pelvis", kFleshPelvis}, {"Spine1", kFleshSpine}, {"Spine2", kFleshSpine},
    {"Chest", kFleshChest}, {"L_Clavicle", kFleshChest}, {"R_Clavicle", kFleshChest}, {"L_Breast", kFleshChest}, {"R_Breast", kFleshChest}, {"Neck1", kFleshHead}, {"Head", kFleshHead},
    {"L_UpperArm", kFleshLUpperArm}, {"L_Forearm", kFleshLForearm}, {"L_Hand", kFleshLHand},
    {"R_UpperArm", kFleshRUpperArm}, {"R_Forearm", kFleshRForearm}, {"R_Hand", kFleshRHand},
    {"L_Thigh", kFleshLThigh}, {"L_Calf", kFleshLCalf}, {"L_Foot", kFleshLFoot}, {"L_Toe", kFleshLFoot},
    {"R_Thigh", kFleshRThigh}, {"R_Calf", kFleshRCalf}, {"R_Foot", kFleshRFoot}, {"R_Toe", kFleshRFoot},
};

// The reference skeleton's Root pivot (anim/BodyGeometry.REF; its z is kAnimRootHeight).
constexpr float kFleshRefRootX = -0.016f, kFleshRefRootY = 0.0f;

struct FleshSeg {
    float front = 0, back = 0, side = 0, start = 0, end = 0, tip = 0;
    float weight = 0;     // the skin weight measured (a check: 0, no vertices)
    bool ok = false;
};

struct FleshMeasure {
    bool ok = false;
    float pelvis = 0;     // the standing pelvis over the soles (model units, before the actor's scale)
    float lowest = 0;     // the body's lowest vertex in that stance over the soles (body units: a check)
    size_t vertices = 0;  // the shown vertices measured
    FleshSeg seg[kFleshCount];
    V3 rest[kFleshJointCount];
    bool restOk[kFleshJointCount] = {};

    // The layout Scene::actorFlesh hands the app (anim/FleshProfile.of): version, pelvis, lowest, the segment count, then
    // each segment's ok, front, back, side, start, end, tip; the joint count, then each joint's x, y, z (NaN: none).
    std::vector<float> pack() const {
        std::vector<float> out = {1.0f, pelvis, lowest, float(kFleshCount)};
        for (const FleshSeg& s : seg) out.insert(out.end(), {s.ok ? 1.0f : 0.0f, s.front, s.back, s.side, s.start, s.end, s.tip});
        out.push_back(float(kFleshJointCount));
        for (int j = 0; j < kFleshJointCount; ++j) {
            if (restOk[j]) out.insert(out.end(), {rest[j].x, rest[j].y, rest[j].z});
            else out.insert(out.end(), {NAN, NAN, NAN});
        }
        return out;
    }
};

// One measured point: where it is (any units, the joints' too), the segment it counts for and its skin weight there.
struct FleshPoint { V3 p; int seg; float w; };

// The value at or under which [q] of the weight lies (0 with no weight).
inline float fleshPercentile(std::vector<std::pair<float, float>>& vw, float q) {
    if (vw.empty()) return 0;
    std::sort(vw.begin(), vw.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    float total = 0;
    for (auto& x : vw) total += x.second;
    if (total <= 0) return 0;
    float acc = 0, want = q * total;
    for (auto& x : vw) { acc += x.second; if (acc >= want) return x.first; }
    return vw.back().first;
}

// A segment's frame from the joints (a map with "Hips" added, as fleshJoints makes it); false when its joints are missing.
struct FleshFrame { V3 a0, a1, axis, n, m; float len = 0; bool round = false; };
inline bool fleshFrame(int s, const std::map<std::string, V3>& J, float tip, FleshFrame& f) {
    const FleshSegDef& d = kFleshSegs[s];
    auto from = J.find(d.from), to = J.find(d.to);
    if (from == J.end() || to == J.end()) return false;
    f.a0 = from->second;
    if (d.extend) {
        auto e = J.find(d.extend);
        if (e == J.end()) return false;
        V3 dir = (to->second - e->second).normalized();
        f.a1 = to->second + dir * tip;
    } else f.a1 = to->second;
    V3 ax = f.a1 - f.a0;
    f.len = ax.length();
    f.axis = f.len > 1e-6f ? ax * (1.0f / f.len) : (d.extend ? (to->second - J.at(d.extend)).normalized() : V3(0, 0, 1));
    auto l = J.find(d.upper ? "L_UpperArm" : "L_Thigh"), r = J.find(d.upper ? "R_UpperArm" : "R_Thigh");
    if (l == J.end() || r == J.end()) { l = J.find(d.upper ? "L_Thigh" : "L_UpperArm"); r = J.find(d.upper ? "R_Thigh" : "R_UpperArm"); }
    V3 side = l != J.end() && r != J.end() ? (l->second - r->second).normalized() : V3(0, 1, 0);
    V3 c = side.cross(f.axis);
    f.round = d.round || c.length() < 0.25f;
    f.n = c.normalized() * d.sign;
    f.m = f.axis.cross(f.n);
    return true;
}

// The joints of a body (any units) with "Hips" added: between the hip joints.
inline void fleshAddHips(std::map<std::string, V3>& J) {
    auto l = J.find("L_Thigh"), r = J.find("R_Thigh");
    if (l != J.end() && r != J.end()) J["Hips"] = (l->second + r->second) * 0.5f;
}

// The measure from joints and points in one frame (any units; [unit] of them is one body unit), the floor at z = [ground].
inline FleshMeasure fleshOf(std::map<std::string, V3> J, const std::vector<FleshPoint>& pts, float ground, float unit) {
    FleshMeasure out;
    if (!(unit > 1e-5f)) return out;
    fleshAddHips(J);
    std::vector<std::vector<const FleshPoint*>> bySeg(kFleshCount);
    for (const FleshPoint& p : pts) if (p.seg >= 0 && p.seg < kFleshCount && p.w > 0) bySeg[size_t(p.seg)].push_back(&p);
    const float k = 1.0f / unit;
    for (int s = 0; s < kFleshCount; ++s) {
        const auto& ps = bySeg[size_t(s)];
        FleshSeg& o = out.seg[s];
        float tip = 0;
        const FleshSegDef& d = kFleshSegs[s];
        if (d.extend) {
            // its length past the joint: along extend -> to, from `to`
            auto to = J.find(d.to), e = J.find(d.extend);
            if (to == J.end() || e == J.end()) continue;
            V3 dir = (to->second - e->second).normalized();
            std::vector<std::pair<float, float>> a;
            for (const FleshPoint* p : ps) a.push_back({(p->p - to->second).dot(dir), p->w});
            tip = std::max(0.0f, fleshPercentile(a, 0.95f));
        }
        FleshFrame f;
        if (ps.empty() || !fleshFrame(s, J, tip, f)) continue;
        std::vector<std::pair<float, float>> fr, bk, sd, st, en;
        float wsum = 0;
        for (const FleshPoint* p : ps) {
            V3 q = p->p - f.a0;
            float al = q.dot(f.axis), dn = q.dot(f.n), dm = q.dot(f.m);
            if (f.round) { float r = std::sqrt(dn * dn + dm * dm); fr.push_back({r, p->w}); }
            else { fr.push_back({dn, p->w}); bk.push_back({-dn, p->w}); sd.push_back({std::fabs(dm), p->w}); }
            st.push_back({-al, p->w});
            en.push_back({al - f.len, p->w});
            wsum += p->w;
        }
        if (f.round) { o.front = o.back = o.side = std::max(0.0f, fleshPercentile(fr, 0.9f)) * k; }
        else {
            o.front = std::max(0.0f, fleshPercentile(fr, 0.9f)) * k;
            o.back = std::max(0.0f, fleshPercentile(bk, 0.9f)) * k;
            o.side = std::max(0.0f, fleshPercentile(sd, 0.9f)) * k;
        }
        o.start = std::max(0.0f, fleshPercentile(st, 0.9f)) * k;
        o.end = std::max(0.0f, fleshPercentile(en, 0.9f)) * k;
        o.tip = tip * k;
        o.weight = wsum;
        o.ok = true;
    }
    float low = 1e9f;
    for (const FleshPoint& p : pts) low = std::min(low, p.p.z);
    out.lowest = pts.empty() ? 0 : (low - ground) * k;
    // the joints in the reference skeleton's units: the pelvis kAnimRootHeight over the soles, the Root at the reference's x, y
    auto root = J.find("Root");
    const float rk = kAnimRootHeight * k;
    for (int j = 0; j < kFleshJointCount; ++j) {
        auto it = J.find(kFleshJoints[j]);
        if (it == J.end() || root == J.end()) continue;
        V3 v = it->second;
        out.rest[j] = V3(kFleshRefRootX + (v.x - root->second.x) * rk, kFleshRefRootY + (v.y - root->second.y) * rk, (v.z - ground) * rk);
        out.restOk[j] = true;
    }
    out.pelvis = unit;
    out.ok = true;
    for (int s : {kFleshPelvis, kFleshSpine, kFleshChest, kFleshLThigh, kFleshRThigh}) if (!out.seg[s].ok) out.ok = false;
    return out;
}

// The lowest point of a segment's flesh with its frame [f] (z up): its lower end less the reach down there (the section's
// reach toward -z, and its end's reach past it when the axis runs down there). anim/FleshProfile.lowest is the same.
inline float fleshLowest(const FleshSeg& s, const FleshFrame& f) {
    const V3 down(0, 0, -1);
    float dn = down.dot(f.n), dm = down.dot(f.m);
    float rn = dn > 0 ? s.front : s.back;
    float disk = f.round ? s.front * std::sqrt(std::max(0.0f, 1.0f - f.axis.z * f.axis.z))
                         : std::sqrt(rn * rn * dn * dn + s.side * s.side * dm * dm);
    float az = f.axis.z;
    float z0 = f.a0.z - disk - (az > 0 ? s.start * az : 0.0f);
    float z1 = f.a1.z - disk - (az < 0 ? s.end * -az : 0.0f);
    return std::min(z0, z1);
}

// A dressed model measured: [verts] (the model's own, or its shaped body's) and the triangles [idx] of [subs] whose geoset
// is in [geosets] (empty: all), in the clips' stance. [dropped] (if given): per bone, 1 for what hangs off the body and was
// left out (a tail, a cape: engine-test/audit tells the body's own sink from a tail's with it).
inline FleshMeasure measureFlesh(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& idx,
                                 const std::vector<M2Submesh>& subs, const std::vector<uint16_t>& geosets,
                                 std::vector<uint8_t>* droppedOut = nullptr) {
    FleshMeasure out;
    StandFit stand = measureStand(m);
    if (!stand.ok) return out;
    std::vector<M4> mats;
    standMatrices(m, mats);
    if (mats.size() != m.bones.size()) return out;
    // each bone's segment: its nearest named ancestor's
    std::vector<int> named(m.bones.size(), -1);
    for (const auto& [name, seg] : kFleshBoneSegs) {
        int b = m.findWowBone(name);
        if (b >= 0 && size_t(b) < named.size()) named[size_t(b)] = seg;
    }
    std::vector<int> segOf(m.bones.size(), -1);
    for (size_t b = 0; b < m.bones.size(); ++b) {
        int c = int(b), guard = 0;
        while (c >= 0 && named[size_t(c)] < 0 && guard++ < 64) c = m.bones[size_t(c)].parent;
        segOf[b] = c >= 0 ? named[size_t(c)] : -1;
    }
    std::vector<uint8_t> used(verts.size(), 0);
    for (const M2Batch& bt : m.batches) {
        if (bt.submesh >= subs.size()) continue;
        const M2Submesh& sm = subs[bt.submesh];
        if (!geosets.empty() && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t k = sm.indexStart; k < sm.indexStart + sm.indexCount && k < idx.size(); ++k) if (idx[k] < used.size()) used[idx[k]] = 1;
    }
    std::map<std::string, V3> J;
    for (const char* name : kFleshJoints) {
        int b = m.findWowBone(name);
        if (b >= 0 && size_t(b) < mats.size()) J[name] = mats[size_t(b)].point(m.bones[size_t(b)].pivot);
    }
    // the shown vertices in the stance
    std::vector<V3> at(verts.size());
    std::vector<float> wsum(verts.size(), 0);
    for (size_t i = 0; i < verts.size(); ++i) {
        if (!used[i]) continue;
        const M2Vertex& vx = verts[i];
        V3 p(vx.pos[0], vx.pos[1], vx.pos[2]), s(0, 0, 0);
        for (int k = 0; k < 4; ++k) {
            if (!vx.weights[k] || vx.bones[k] >= mats.size()) continue;
            float w = vx.weights[k] / 255.0f;
            s = s + mats[vx.bones[k]].point(p) * w;
            wsum[i] += w;
        }
        if (wsum[i] > 0) { at[i] = s * (1.0f / wsum[i]); ++out.vertices; }
    }
    // What hangs off the body is left out: a bone that isn't named itself (a tail, a cape, a kilt's flaps) whose vertices sit
    // farther from its segment than the segment's own flesh reaches (the named bone's 90th percentile), and every bone under
    // it. The head and the hands keep all theirs (ears, fingers); a twist bone or a belly's helper sits within and stays.
    std::map<std::string, V3> JH = J;
    fleshAddHips(JH);
    auto segDistance = [&](int seg, V3 p) {
        FleshFrame f;
        if (!fleshFrame(seg, JH, 0.0f, f)) return 0.0f;
        V3 q = p - f.a0;
        float t = std::clamp(q.dot(f.axis), 0.0f, f.len);
        return (q - f.axis * t).length();
    };
    std::vector<V3> boneMean(m.bones.size(), V3(0, 0, 0));
    std::vector<float> boneW(m.bones.size(), 0);
    std::vector<std::vector<std::pair<float, float>>> own(kFleshCount);
    for (size_t i = 0; i < verts.size(); ++i) {
        if (wsum[i] <= 0) continue;
        const M2Vertex& vx = verts[i];
        for (int k = 0; k < 4; ++k) {
            if (!vx.weights[k] || vx.bones[k] >= segOf.size()) continue;
            size_t b = vx.bones[k];
            float w = vx.weights[k] / 255.0f / wsum[i];
            boneMean[b] = boneMean[b] + at[i] * w;
            boneW[b] += w;
            if (named[b] >= 0) own[size_t(named[b])].push_back({segDistance(named[b], at[i]), w});
        }
    }
    std::vector<float> ownReach(kFleshCount, 0);
    for (int sg = 0; sg < kFleshCount; ++sg) ownReach[size_t(sg)] = fleshPercentile(own[size_t(sg)], 0.9f);
    std::vector<uint8_t> dropped(m.bones.size(), 0);
    for (size_t b = 0; b < m.bones.size(); ++b) {
        // parents come first in an M2's bone list
        int parent = m.bones[b].parent;
        if (parent >= 0 && size_t(parent) < b && dropped[size_t(parent)]) { dropped[b] = 1; continue; }
        int sg = segOf[b];
        if (named[b] >= 0 || sg < 0 || boneW[b] <= 0 || kFleshSegs[sg].extend) continue;
        V3 mean = boneMean[b] * (1.0f / boneW[b]);
        if (segDistance(sg, mean) > ownReach[size_t(sg)]) dropped[b] = 1;
    }
    if (droppedOut) *droppedOut = dropped;
    std::vector<FleshPoint> pts;
    pts.reserve(verts.size() * 2);
    for (size_t i = 0; i < verts.size(); ++i) {
        if (wsum[i] <= 0) continue;
        const M2Vertex& vx = verts[i];
        for (int k = 0; k < 4; ++k) {
            if (!vx.weights[k] || vx.bones[k] >= segOf.size() || dropped[vx.bones[k]]) continue;
            pts.push_back({at[i], segOf[vx.bones[k]], vx.weights[k] / 255.0f / wsum[i]});
        }
    }
    size_t shown = out.vertices;
    out = fleshOf(J, pts, stand.ground, stand.pelvis);
    out.vertices = shown;
    return out;
}

} // namespace wc
