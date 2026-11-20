// The sculpt sliders' maths (sculpt.h). Pure: no GL, no files.
#include "sculpt.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_map>

namespace wc {

namespace {

constexpr uint8_t kUnset = 255;

float smooth(float a, float b, float x) {
    if (b == a) return x >= b ? 1.0f : 0.0f;
    float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3 - 2 * t);
}

float gauss(float d, float sigma) { return std::exp(-0.5f * (d * d) / (sigma * sigma)); }

V3 pivotAt(const std::vector<V3>& p, int i) { return i >= 0 && size_t(i) < p.size() ? p[size_t(i)] : V3(); }

// The point of segment a-b nearest p.
V3 nearestOn(const V3& a, const V3& b, const V3& p) {
    V3 ab = b - a;
    float l2 = ab.dot(ab);
    if (l2 < 1e-12f) return a;
    float t = std::clamp((p - a).dot(ab) / l2, 0.0f, 1.0f);
    return a + ab * t;
}

} // namespace

SculptRig sculptRig(const std::vector<V3>& pivots, const std::vector<int16_t>& parents, const SculptBones& b) {
    SculptRig r;
    const size_t n = pivots.size();
    r.group.assign(n, kUnset);
    auto seed = [&](int i, uint8_t g) { if (i >= 0 && size_t(i) < n) r.group[size_t(i)] = g; };
    seed(b.pelvis, kGroupPelvis); seed(b.waist, kGroupWaist); seed(b.chest, kGroupChest);
    seed(b.neck, kGroupNeck); seed(b.head, kGroupNeck);
    for (int k = 0; k < 2; ++k) {
        seed(b.breast[k], k == 0 ? kGroupBreastL : kGroupBreastR);
        seed(b.clavicle[k], kGroupClavicle);
        seed(b.upperArm[k], k == 0 ? kGroupUpperArmL : kGroupUpperArmR);
        seed(b.forearm[k], k == 0 ? kGroupForearmL : kGroupForearmR);
        seed(b.hand[k], kGroupOther);            // the hands and fingers keep their size
        seed(b.thigh[k], k == 0 ? kGroupThighL : kGroupThighR);
        seed(b.calf[k], kGroupCalf);
        seed(b.ankle[k], kGroupCalf);
    }
    // a bone without a group takes its nearest named ancestor's (twist bones, fingers, the jaw)
    for (size_t i = 0; i < n; ++i) {
        if (r.group[i] != kUnset) continue;
        int p = parents.size() > i ? parents[i] : -1;
        uint8_t g = kGroupOther;
        for (int guard = 0; p >= 0 && size_t(p) < n && guard < 256; ++guard) {
            if (r.group[size_t(p)] != kUnset) { g = r.group[size_t(p)]; break; }
            p = parents[size_t(p)];
        }
        r.group[i] = g == kUnset ? uint8_t(kGroupOther) : g;
    }
    r.pelvis = pivotAt(pivots, b.pelvis);
    r.waist = b.waist >= 0 ? pivotAt(pivots, b.waist) : r.pelvis + V3(0, 0, 0.1f);
    r.chest = b.chest >= 0 ? pivotAt(pivots, b.chest) : r.waist + V3(0, 0, 0.15f);
    r.neck = b.neck >= 0 ? pivotAt(pivots, b.neck) : r.chest + V3(0, 0, 0.15f);
    r.unit = std::max(r.pelvis.z, 0.1f);
    const float U = r.unit;
    r.hasBreastBones = b.breast[0] >= 0 && b.breast[1] >= 0;
    for (int k = 0; k < 2; ++k) {
        float side = k == 0 ? 1.0f : -1.0f;      // left is +y
        r.breast[k] = b.breast[k] >= 0 ? pivotAt(pivots, b.breast[k]) : r.chest + V3(0.09f * U, side * 0.08f * U, -0.06f * U);
        r.hip[k] = b.thigh[k] >= 0 ? pivotAt(pivots, b.thigh[k]) : r.pelvis + V3(0, side * 0.1f * U, -0.05f * U);
        r.knee[k] = b.calf[k] >= 0 ? pivotAt(pivots, b.calf[k]) : r.hip[k] + V3(0, 0, -0.45f * U);
        r.ankle[k] = b.ankle[k] >= 0 ? pivotAt(pivots, b.ankle[k]) : r.knee[k] + V3(0, 0, -0.45f * U);
        r.shoulder[k] = b.upperArm[k] >= 0 ? pivotAt(pivots, b.upperArm[k]) : r.chest + V3(0, side * 0.18f * U, 0.05f * U);
        r.elbow[k] = b.forearm[k] >= 0 ? pivotAt(pivots, b.forearm[k]) : r.shoulder[k] + V3(0, side * 0.3f * U, 0);
        r.wrist[k] = b.hand[k] >= 0 ? pivotAt(pivots, b.hand[k]) : r.elbow[k] + V3(0, side * 0.28f * U, 0);
    }
    r.ok = b.pelvis >= 0;
    return r;
}

bool sculptActive(const float* values, size_t count) {
    if (!values) return false;
    for (size_t i = 0; i < count && i < size_t(kSculptCount); ++i)
        if (i != kSculptHeight && i != kSculptLegLength && std::fabs(values[i]) > 1e-4f) return true;
    return false;
}

size_t applySculpt(const SculptRig& rig, std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                   const float* values, size_t count, const SculptOptions& opt, SculptStats* stats) {
    if (stats) *stats = SculptStats();
    if (!rig.ok || verts.empty() || !sculptActive(values, count)) return 0;
    float s[kSculptCount] = {};
    for (size_t i = 0; i < count && i < size_t(kSculptCount); ++i) s[i] = std::isfinite(values[i]) ? std::clamp(values[i], -1.0f, 1.0f) : 0.0f;
    const float U = rig.unit;
    const float spineX = rig.waist.x;
    const float maxMove = kSculptMaxShare * U;

    std::vector<V3> before(verts.size());
    for (size_t i = 0; i < verts.size(); ++i) before[i] = V3(verts[i].pos[0], verts[i].pos[1], verts[i].pos[2]);
    std::vector<uint8_t> pinned(verts.size(), 0);
    for (uint32_t i : opt.pinned) if (i < pinned.size()) pinned[i] = 1;

    size_t moved = 0;
    float most = 0;
    std::vector<float> share(verts.size(), 0.0f);
    for (size_t vi = 0; vi < verts.size(); ++vi) {
        if (pinned[vi]) continue;
        const M2Vertex& v = verts[vi];
        float W[kGroupCount] = {};
        float total = 0;
        for (int k = 0; k < 4; ++k) {
            if (!v.weights[k]) continue;
            uint8_t g = v.bones[k] < rig.group.size() ? rig.group[v.bones[k]] : uint8_t(kGroupOther);
            float w = v.weights[k] / 255.0f;
            W[g] += w; total += w;
        }
        if (total <= 0) continue;
        for (float& w : W) w /= total;
        const V3 p = before[vi];
        const float front = smooth(-0.02f * U, 0.06f * U, p.x - spineX);
        V3 d;

        // the bust: grows about a centre behind each breast (outward and forward), and lifts
        float bust[2];
        for (int k = 0; k < 2; ++k) {
            if (rig.hasBreastBones) bust[k] = W[k == 0 ? kGroupBreastL : kGroupBreastR];
            else {
                float side = k == 0 ? smooth(-0.01f * U, 0.02f * U, p.y) : smooth(-0.01f * U, 0.02f * U, -p.y);
                bust[k] = (W[kGroupChest] + W[kGroupBreastL] + W[kGroupBreastR]) * front * side *
                          gauss(p.z - rig.breast[k].z, 0.07f * U) * gauss(std::fabs(p.y) - std::fabs(rig.breast[k].y), 0.07f * U);
            }
        }
        if (s[kSculptBust] != 0) {
            for (int k = 0; k < 2; ++k) {
                if (bust[k] <= 0) continue;
                V3 c = rig.breast[k] - V3(0.05f * U, 0, 0);
                V3 r = p - c;
                r.y *= 0.6f;                                     // more forward than outward: the two never meet
                d = d + r * (0.4f * s[kSculptBust] * bust[k]);
            }
        }
        if (s[kSculptBustLift] != 0) d.z += 0.035f * U * s[kSculptBustLift] * (bust[0] + bust[1]) * std::max(front, 0.3f);

        // the waist: wider or narrower about the spine
        if (s[kSculptWaist] != 0) {
            float arms = W[kGroupUpperArmL] + W[kGroupUpperArmR] + W[kGroupForearmL] + W[kGroupForearmR];
            float m = W[kGroupWaist] * (1 - arms) * (1 - 0.6f * (bust[0] + bust[1]));
            d.x += (p.x - spineX) * 0.2f * s[kSculptWaist] * m;
            d.y += p.y * 0.2f * s[kSculptWaist] * m;
        }
        // the belly: forward, a soft band round the navel (the front scaled out from the spine: never folds)
        if (s[kSculptBelly] != 0) {
            float m = (W[kGroupWaist] + 0.6f * W[kGroupPelvis]) * gauss(p.z - (rig.waist.z - 0.04f * U), 0.1f * U);
            d.x += std::max(0.0f, p.x - spineX) * 0.55f * s[kSculptBelly] * m;
        }
        // the hips: wider across, a band round the hip joints
        const float thighs = W[kGroupThighL] + W[kGroupThighR];
        if (s[kSculptHips] != 0) {
            float m = (W[kGroupPelvis] + thighs) * gauss(p.z - (rig.pelvis.z - 0.06f * U), 0.12f * U);
            d.y += p.y * 0.18f * s[kSculptHips] * m;
        }
        // the butt: back, a band under the pelvis (the back scaled out from the spine)
        if (s[kSculptButt] != 0) {
            float m = (W[kGroupPelvis] + 0.7f * thighs) * gauss(p.z - (rig.pelvis.z - 0.07f * U), 0.09f * U);
            d.x += std::min(0.0f, p.x - spineX) * 0.6f * s[kSculptButt] * m;
        }
        // the thighs and arms: thicker or thinner about their bones' axes
        auto limb = [&](float value, float w, const V3& a, const V3& b) {
            if (value == 0 || w <= 0) return;
            V3 r = p - nearestOn(a, b, p);
            d = d + r * (0.3f * value * w);
        };
        for (int k = 0; k < 2; ++k) {
            limb(s[kSculptThighs], W[k == 0 ? kGroupThighL : kGroupThighR], rig.hip[k], rig.knee[k]);
            limb(s[kSculptArms], W[k == 0 ? kGroupUpperArmL : kGroupUpperArmR], rig.shoulder[k], rig.elbow[k]);
            limb(s[kSculptArms], W[k == 0 ? kGroupForearmL : kGroupForearmR], rig.elbow[k], rig.wrist[k]);
        }
        // the shoulders: broader or narrower, nothing at the body's middle line
        if (s[kSculptShoulders] != 0) {
            float m = W[kGroupClavicle] + 0.8f * (W[kGroupUpperArmL] + W[kGroupUpperArmR]) +
                      W[kGroupChest] * smooth(0.06f * U, 0.16f * U, std::fabs(p.y)) * smooth(rig.chest.z - 0.1f * U, rig.chest.z + 0.05f * U, p.z);
            m = std::min(m, 1.0f) * smooth(0.0f, 0.04f * U, std::fabs(p.y));
            d.y += (p.y >= 0 ? 1.0f : -1.0f) * 0.04f * U * s[kSculptShoulders] * m;
        }

        float len = d.length();
        if (len > maxMove) { d = d * (maxMove / len); len = maxMove; }
        if (len < 1e-7f) continue;
        verts[vi].pos[0] = p.x + d.x; verts[vi].pos[1] = p.y + d.y; verts[vi].pos[2] = p.z + d.z;
        share[vi] = std::min(1.0f, len / (0.01f * U));
        most = std::max(most, len);
        ++moved;
    }
    if (!moved) return 0;

    // normals: made again for what moved, from the triangles round each position (UV seams' copies share them)
    size_t flipped = 0;
    if (!indices.empty()) {
        const float q = 1e-4f * U;
        auto key = [&](const V3& p) {
            auto c = [&](float v) { return uint64_t(int64_t(std::floor(v / q)) & 0x1FFFFF); };
            return c(p.x) << 42 | c(p.y) << 21 | c(p.z);
        };
        std::unordered_map<uint64_t, V3> acc;
        acc.reserve(verts.size());
        for (size_t t = 0; t + 2 < indices.size(); t += 3) {
            uint32_t a = indices[t], b = indices[t + 1], c = indices[t + 2];
            if (a >= verts.size() || b >= verts.size() || c >= verts.size()) continue;
            V3 pa(verts[a].pos[0], verts[a].pos[1], verts[a].pos[2]), pb(verts[b].pos[0], verts[b].pos[1], verts[b].pos[2]),
               pc(verts[c].pos[0], verts[c].pos[1], verts[c].pos[2]);
            V3 n = (pb - pa).cross(pc - pa);
            V3 n0 = (before[b] - before[a]).cross(before[c] - before[a]);
            if (n0.length() > 1e-10f && n.dot(n0) < 0) ++flipped;
            if (share[a] > 0 || share[b] > 0 || share[c] > 0)
                for (uint32_t i : {a, b, c}) { V3& s2 = acc[key(before[i])]; s2 = s2 + n; }
        }
        for (size_t i = 0; i < verts.size(); ++i) {
            if (share[i] <= 0) continue;
            auto it = acc.find(key(before[i]));
            if (it == acc.end() || it->second.length() < 1e-12f) continue;
            V3 fresh = it->second.normalized();
            V3 old(verts[i].normal[0], verts[i].normal[1], verts[i].normal[2]);
            V3 n = (old * (1 - share[i]) + fresh * share[i]).normalized();
            verts[i].normal[0] = n.x; verts[i].normal[1] = n.y; verts[i].normal[2] = n.z;
        }
    }
    if (stats) { stats->moved = moved; stats->maxMove = most; stats->flipped = flipped; }
    return moved;
}

} // namespace wc
