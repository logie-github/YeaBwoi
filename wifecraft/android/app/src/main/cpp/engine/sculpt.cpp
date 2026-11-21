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

// The point of segment a-b nearest p (and how far along it, 0..1).
V3 nearestOn(const V3& a, const V3& b, const V3& p, float* along = nullptr) {
    V3 ab = b - a;
    float l2 = ab.dot(ab);
    if (l2 < 1e-12f) { if (along) *along = 0; return a; }
    float t = std::clamp((p - a).dot(ab) / l2, 0.0f, 1.0f);
    if (along) *along = t;
    return a + ab * t;
}

} // namespace

SculptRig sculptRig(const std::vector<V3>& pivots, const std::vector<int16_t>& parents, const SculptBones& b) {
    SculptRig r;
    const size_t n = pivots.size();
    r.group.assign(n, kUnset);
    auto seed = [&](int i, uint8_t g) { if (i >= 0 && size_t(i) < n) r.group[size_t(i)] = g; };
    seed(b.pelvis, kGroupPelvis); seed(b.waist, kGroupWaist); seed(b.chest, kGroupChest);
    seed(b.neck, kGroupNeck); seed(b.head, kGroupHead);   // (the head: no move touches it)
    for (int k = 0; k < 2; ++k) {
        seed(b.breast[k], k == 0 ? kGroupBreastL : kGroupBreastR);
        seed(b.clavicle[k], kGroupClavicle);
        seed(b.upperArm[k], k == 0 ? kGroupUpperArmL : kGroupUpperArmR);
        seed(b.forearm[k], k == 0 ? kGroupForearmL : kGroupForearmR);
        seed(b.hand[k], kGroupOther);            // the hands and fingers keep their size
        seed(b.thigh[k], k == 0 ? kGroupThighL : kGroupThighR);
        seed(b.calf[k], kGroupCalf);
        seed(b.ankle[k], kGroupFoot);            // the feet keep their size
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
    r.head = b.head >= 0 ? pivotAt(pivots, b.head) : r.neck + V3(0, 0, 0.1f);
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

// The rig of a model by its WoW-side bone names (M2Model::findWowBone): the game's own models and the Eve rig alike.
SculptRig sculptRigOf(const M2Model& m) {
    SculptBones b;
    auto find = [&](const char* a, const char* alt = nullptr) { int i = m.findWowBone(a); return i >= 0 || !alt ? i : m.findWowBone(alt); };
    b.pelvis = find("Pelvis", "Root");
    b.waist = find("Spine1", "Waist");
    b.chest = find("Chest", "Spine2");
    b.neck = find("Neck1");
    b.head = find("Head");
    const char* side[2] = {"L_", "R_"};
    for (int k = 0; k < 2; ++k) {
        std::string s = side[k];
        b.breast[k] = m.findWowBone(s + "Breast");
        b.clavicle[k] = m.findWowBone(s + "Clavicle");
        b.upperArm[k] = m.findWowBone(s + "UpperArm");
        b.forearm[k] = m.findWowBone(s + "Forearm");
        b.hand[k] = m.findWowBone(s + "Hand");
        b.thigh[k] = m.findWowBone(s + "Thigh");
        b.calf[k] = m.findWowBone(s + "Calf");
        b.ankle[k] = m.findWowBone(s + "Ankle");
    }
    std::vector<V3> pivots(m.bones.size());
    std::vector<int16_t> parents(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) { pivots[i] = m.bones[i].pivot; parents[i] = m.bones[i].parent; }
    return sculptRig(pivots, parents, b);
}

bool sculptActive(const float* values, size_t count) {
    if (!values) return false;
    for (size_t i = 0; i < count && i < size_t(kSculptValues); ++i) {
        const size_t k = i % size_t(kSculptCount);
        if (k != kSculptHeight && k != kSculptLegLength && std::fabs(values[i]) > 1e-4f) return true;
    }
    return false;
}

size_t applySculpt(const SculptRig& rig, std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                   const float* values, size_t count, const SculptOptions& opt, SculptStats* stats) {
    if (stats) *stats = SculptStats();
    if (!rig.ok || verts.empty() || !sculptActive(values, count)) return 0;
    float s0[kSculptCount] = {}, bal[kSculptCount] = {};
    bool sided = false;
    for (size_t i = 0; i < count && i < size_t(kSculptValues); ++i) {
        const float v = std::isfinite(values[i]) ? std::clamp(values[i], -1.0f, 1.0f) : 0.0f;
        if (i < size_t(kSculptCount)) s0[i] = v;
        else if (v != 0 && i - kSculptCount != size_t(kSculptHeight) && i - kSculptCount != size_t(kSculptLegLength)) { bal[i - kSculptCount] = v; sided = true; }
    }
    // the values on one side (`side`: 1 the left, -1 the right, between them across the middle), with Body Fat and Muscle as
    // shares of the other moves (the per-vertex cap below still bounds the sum)
    float s[kSculptCount];
    auto compose = [&](float side) {
        for (int i = 0; i < kSculptCount; ++i) s[i] = std::clamp(s0[i] + side * bal[i], -1.0f, 1.0f);
        const float fat = s[kSculptBodyFat], mus = s[kSculptMuscle];
        auto add = [&s](int k, float v) { s[k] = std::clamp(s[k] + v, -1.5f, 1.5f); };
        if (fat != 0) {
            add(kSculptWaist, 0.6f * fat); add(kSculptBelly, 0.7f * fat); add(kSculptHips, 0.4f * fat); add(kSculptButt, 0.4f * fat);
            add(kSculptThighs, 0.6f * fat); add(kSculptUpperArms, 0.6f * fat); add(kSculptForearms, 0.35f * fat);
            add(kSculptCalves, 0.4f * fat); add(kSculptBust, 0.25f * fat);
            if (opt.gameBody) add(kSculptNeck, 0.4f * fat);
        }
        if (mus != 0) {
            add(kSculptShoulders, 0.4f * mus); add(kSculptUpperArms, 0.7f * mus); add(kSculptForearms, 0.5f * mus);
            add(kSculptChest, 0.7f * mus); add(kSculptThighs, 0.4f * mus); add(kSculptCalves, 0.5f * mus);
            add(kSculptWaist, -0.25f * mus); add(kSculptBelly, -0.3f * mus);
            if (opt.gameBody) add(kSculptNeck, 0.5f * mus);
        }
        if (!opt.gameBody) s[kSculptNeck] = 0;   // (the Eve body's neck ring meets the Warcraft head: kept)
    };
    compose(0);
    const float U = rig.unit;
    const float maxMove = kSculptMaxShare * U;
    // the spine's depth at a height: the Eve body's is upright (its waist), the game's bodies follow their own curve
    auto spineAt = [&](float z) {
        if (!opt.gameBody) return rig.waist.x;
        const V3 j[4] = {rig.pelvis, rig.waist, rig.chest, rig.neck};
        if (z <= j[0].z) return j[0].x;
        for (int i = 0; i < 3; ++i) {
            if (j[i + 1].z <= j[i].z + 1e-5f) return rig.waist.x;   // (joints not climbing: upright)
            if (z <= j[i + 1].z) return j[i].x + (j[i + 1].x - j[i].x) * (z - j[i].z) / (j[i + 1].z - j[i].z);
        }
        return j[3].x;
    };

    std::vector<V3> before(verts.size());
    for (size_t i = 0; i < verts.size(); ++i) before[i] = V3(verts[i].pos[0], verts[i].pos[1], verts[i].pos[2]);
    std::vector<uint8_t> pinned(verts.size(), 0);
    for (uint32_t i : opt.pinned) if (i < pinned.size()) pinned[i] = 1;

    std::vector<V3> disp(verts.size());
    std::vector<uint8_t> moves(verts.size(), 0);
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
        if (W[kGroupOther] + W[kGroupHead] + W[kGroupFoot] > 0.999f) continue;   // (the head, hands and feet: no move)
        const V3 p = before[vi];
        if (opt.gameBody) {
            // at the body's middle line a side's groups are taken as both sides' mean (a model's weights there lean one way or
            // the other; the mean keeps the moves mirror-symmetric where the vertex is its own mirror image)
            const float a = smooth(0.0f, 0.03f * U, std::fabs(p.y));
            if (a < 1) {
                const uint8_t pairs[4][2] = {{kGroupBreastL, kGroupBreastR}, {kGroupThighL, kGroupThighR}, {kGroupUpperArmL, kGroupUpperArmR},
                                             {kGroupForearmL, kGroupForearmR}};
                for (const auto& pr : pairs) {
                    const float mean = 0.5f * (W[pr[0]] + W[pr[1]]);
                    W[pr[0]] = a * W[pr[0]] + (1 - a) * mean;
                    W[pr[1]] = a * W[pr[1]] + (1 - a) * mean;
                }
            }
        }
        if (sided) compose(2 * smooth(-0.015f * U, 0.015f * U, p.y) - 1);   // (this side's values: one or the other off the middle)
        const float spineX = spineAt(p.z);
        const float front = smooth(-0.02f * U, 0.06f * U, p.x - spineX);
        const float arms = W[kGroupUpperArmL] + W[kGroupUpperArmR] + W[kGroupForearmL] + W[kGroupForearmR];
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
            if (opt.gameBody) bust[k] *= 1 - std::min(arms, 1.0f);   // (an arm's skin beside the chest stays an arm)
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
        // the bust's width (each breast about its own centre, side to side) and spacing (both apart or together)
        for (int k = 0; k < 2; ++k) {
            if (bust[k] <= 0) continue;
            const float side = k == 0 ? 1.0f : -1.0f;
            if (s[kSculptBustWidth] != 0) d.y += (p.y - rig.breast[k].y) * 0.6f * s[kSculptBustWidth] * bust[k];
            if (s[kSculptBustSpacing] != 0) d.y += side * 0.04f * U * s[kSculptBustSpacing] * bust[k];
        }

        // the chest (the pectorals, a man's or a woman's above the bust): its front forward, a little wider
        if (s[kSculptChest] != 0) {
            float m = (W[kGroupChest] + 0.5f * W[kGroupClavicle] + W[kGroupBreastL] + W[kGroupBreastR]) * front * (1 - std::min(arms, 1.0f)) *
                      gauss(p.z - (rig.chest.z + 0.02f * U), 0.09f * U);
            d.x += std::max(0.0f, p.x - spineX) * 0.35f * s[kSculptChest] * m;
            d.y += p.y * 0.06f * s[kSculptChest] * m;
        }

        // the waist: wider or narrower about the spine
        if (s[kSculptWaist] != 0) {
            float m = W[kGroupWaist] * (1 - arms) * (1 - 0.6f * (bust[0] + bust[1]));
            d.x += (p.x - spineX) * 0.2f * s[kSculptWaist] * m;
            d.y += p.y * 0.2f * s[kSculptWaist] * m;
        }
        // the belly: forward, a soft band round the navel (the front scaled out from the spine: never folds)
        if (s[kSculptBelly] != 0) {
            float m = (W[kGroupWaist] + 0.6f * W[kGroupPelvis]) * gauss(p.z - (rig.waist.z - 0.04f * U), 0.1f * U);
            if (opt.gameBody) m *= 1 - std::min(arms, 1.0f);
            d.x += std::max(0.0f, p.x - spineX) * 0.55f * s[kSculptBelly] * m;
        }
        // the hips: wider across, a band round the hip joints
        const float thighs = W[kGroupThighL] + W[kGroupThighR];
        if (s[kSculptHips] != 0) {
            float m = (W[kGroupPelvis] + thighs) * gauss(p.z - (rig.pelvis.z - 0.06f * U), 0.12f * U);
            if (opt.gameBody) m *= 1 - std::min(arms, 1.0f);   // (a hand hanging by the hip stays where it is)
            d.y += p.y * 0.18f * s[kSculptHips] * m;
        }
        // the butt: back, a band under the pelvis (the back scaled out from the spine); its lift: that band raised
        if (s[kSculptButt] != 0 || s[kSculptButtLift] != 0) {
            float m = (W[kGroupPelvis] + 0.7f * thighs) * gauss(p.z - (rig.pelvis.z - 0.07f * U), 0.09f * U);
            if (opt.gameBody) m *= 1 - std::min(arms, 1.0f);
            d.x += std::min(0.0f, p.x - spineX) * 0.6f * s[kSculptButt] * m;
            if (s[kSculptButtLift] != 0) d.z += 0.045f * U * s[kSculptButtLift] * m * smooth(0.0f, 0.05f * U, spineX - p.x);
        }
        // the limbs: thicker or thinner about their bones' axes; on the game's bodies faded out before the wrists and
        // ankles (`fade`: from that share of the bone on)
        auto limb = [&](float value, float w, const V3& a, const V3& b, float fade, float rise = 0) {
            if (value == 0 || w <= 0) return;
            float t = 0;
            V3 r = p - nearestOn(a, b, p, &t);
            float k = fade < 1 ? 1 - smooth(fade, fade + 0.25f, t) : 1.0f;   // (nothing from 0.25 past it)
            if (rise > 0) k *= smooth(0.0f, rise, t);                         // (and from nothing at the joint it starts at)
            d = d + r * (0.3f * value * w * k);
        };
        const float wristFade = opt.gameBody ? 0.6f : 1.0f;
        for (int k = 0; k < 2; ++k) {
            const float thigh = W[k == 0 ? kGroupThighL : kGroupThighR];
            const float upper = W[k == 0 ? kGroupUpperArmL : kGroupUpperArmR], fore = W[k == 0 ? kGroupForearmL : kGroupForearmR];
            limb(s[kSculptThighs], thigh, rig.hip[k], rig.knee[k], 1.0f);
            limb(s[kSculptArms], upper, rig.shoulder[k], rig.elbow[k], 1.0f);
            limb(s[kSculptArms], fore, rig.elbow[k], rig.wrist[k], wristFade);
            limb(s[kSculptUpperArms], upper, rig.shoulder[k], rig.elbow[k], opt.gameBody ? 0.75f : 1.0f);   // (the elbow kept)
            limb(s[kSculptForearms], fore, rig.elbow[k], rig.wrist[k], 0.6f, opt.gameBody ? 0.2f : 0.0f);
            // the calves (one group for both: the side by where the vertex is, both halves at the middle line)
            const float mine = smooth(-0.03f * U, 0.03f * U, k == 0 ? p.y : -p.y);
            limb(s[kSculptCalves] * 0.75f, W[kGroupCalf] * mine, rig.knee[k], rig.ankle[k], 0.6f, 0.35f);   // (the knee kept)
        }
        // the neck (the game's bodies): thicker or thinner about its axis, faded out before the head
        if (s[kSculptNeck] != 0 && W[kGroupNeck] > 0) {
            float t = 0;
            V3 r = p - nearestOn(rig.neck, rig.head, p, &t);
            d = d + r * (0.25f * s[kSculptNeck] * W[kGroupNeck] * (1 - smooth(0.6f, 1.0f, t)));
        }
        // the shoulders: broader or narrower, nothing at the body's middle line
        if (s[kSculptShoulders] != 0) {
            float upper = 0.8f;
            if (opt.gameBody) {   // (the upper arm leans out from the shoulder, the elbow kept: no step where the forearm starts)
                float t = 0;
                nearestOn(rig.shoulder[p.y >= 0 ? 0 : 1], rig.elbow[p.y >= 0 ? 0 : 1], p, &t);
                upper *= 1 - smooth(0.0f, 0.9f, t);
            }
            float m = W[kGroupClavicle] + upper * (W[kGroupUpperArmL] + W[kGroupUpperArmR]) +
                      W[kGroupChest] * smooth(0.06f * U, 0.16f * U, std::fabs(p.y)) * smooth(rig.chest.z - 0.1f * U, rig.chest.z + 0.05f * U, p.z);
            m = std::min(m, 1.0f) * smooth(0.0f, 0.04f * U, std::fabs(p.y));
            d.y += (p.y >= 0 ? 1.0f : -1.0f) * 0.04f * U * s[kSculptShoulders] * m;
        }
        // the shoulders' slope: their tops lowered (or squared up), the clavicles and the upper arm's top, not the middle
        if (s[kSculptShoulderSlope] != 0) {
            const int k = p.y >= 0 ? 0 : 1;
            float t = 0;
            nearestOn(rig.shoulder[k], rig.elbow[k], p, &t);
            float m = W[kGroupClavicle] + (W[kGroupUpperArmL] + W[kGroupUpperArmR]) * (1 - smooth(0.0f, 0.7f, t)) +
                      W[kGroupChest] * smooth(0.05f * U, 0.15f * U, std::fabs(p.y)) * smooth(rig.chest.z - 0.05f * U, rig.chest.z + 0.08f * U, p.z);
            m = std::min(m, 1.0f) * smooth(0.02f * U, 0.08f * U, std::fabs(p.y));
            d.z -= 0.055f * U * s[kSculptShoulderSlope] * m;
        }

        float len = d.length();
        if (len > maxMove) { d = d * (maxMove / len); len = maxMove; }
        if (len < 1e-7f) continue;
        disp[vi] = d;
        moves[vi] = 1;
    }

    // a vertex's place (a UV seam's or a geoset's copies at one point share it: they move alike and share a normal)
    const float q = 1e-4f * U;
    auto key = [&](const V3& p) {
        auto c = [&](float v) { return uint64_t(int64_t(std::floor(v / q)) & 0x1FFFFF); };
        return c(p.x) << 42 | c(p.y) << 21 | c(p.z);
    };
    // triangles that would turn over (or, on the game's bodies, shrink to a tenth) eased back: their corners' moves halved
    // by place, at most six times, then dropped
    size_t eased = 0;
    if (!indices.empty()) {
        std::unordered_map<uint64_t, uint32_t> placeOf;
        placeOf.reserve(verts.size() / 8 + 16);
        std::vector<uint32_t> place(verts.size(), 0);
        for (size_t i = 0; i < verts.size(); ++i) {
            if (!moves[i]) continue;   // (only a moving corner is eased)
            auto ins = placeOf.emplace(key(before[i]), uint32_t(placeOf.size()));
            place[i] = ins.first->second;
        }
        if (opt.gameBody && !placeOf.empty()) {
            // copies at one place (two geosets meeting, weighted differently) move as one: their moves averaged, a copy
            // that would stay included, so no seam opens between sections drawn together
            for (size_t i = 0; i < verts.size(); ++i) {
                if (moves[i] || pinned[i]) continue;
                auto it = placeOf.find(key(before[i]));
                if (it != placeOf.end()) { place[i] = it->second; moves[i] = 1; }
            }
            std::vector<V3> sum(placeOf.size());
            std::vector<uint16_t> n(placeOf.size(), 0);
            for (size_t i = 0; i < verts.size(); ++i) if (moves[i]) { sum[place[i]] = sum[place[i]] + disp[i]; if (n[place[i]] < 65535) ++n[place[i]]; }
            for (size_t i = 0; i < verts.size(); ++i) if (moves[i] && n[place[i]] > 1) disp[i] = sum[place[i]] * (1.0f / n[place[i]]);
        }
        std::vector<float> ease(placeOf.size() + 1, 1.0f);
        for (int round = 0; round < 7; ++round) {
            size_t bad = 0;
            for (size_t t = 0; t + 2 < indices.size(); t += 3) {
                uint32_t a = indices[t], b = indices[t + 1], c = indices[t + 2];
                if (a >= verts.size() || b >= verts.size() || c >= verts.size()) continue;
                if (!moves[a] && !moves[b] && !moves[c]) continue;
                V3 pa = before[a] + disp[a] * ease[place[a]], pb = before[b] + disp[b] * ease[place[b]], pc = before[c] + disp[c] * ease[place[c]];
                V3 n0 = (before[b] - before[a]).cross(before[c] - before[a]);
                float a0 = n0.dot(n0);
                if (a0 < 1e-20f) continue;
                float kept = (pb - pa).cross(pc - pa).dot(n0);
                if (kept >= (opt.gameBody ? 0.1f : 0.0f) * a0) continue;
                ++bad;
                for (uint32_t i : {a, b, c}) if (moves[i]) ease[place[i]] = round < 6 ? ease[place[i]] * 0.5f : 0.0f;
            }
            if (!bad) break;
            eased += bad;
        }
        for (size_t i = 0; i < verts.size(); ++i) if (moves[i]) disp[i] = disp[i] * ease[place[i]];
    }

    size_t moved = 0;
    float most = 0;
    std::vector<float> share(verts.size(), 0.0f);
    for (size_t vi = 0; vi < verts.size(); ++vi) {
        if (!moves[vi]) continue;
        float len = disp[vi].length();
        if (len < 1e-7f) continue;
        const V3 p = before[vi] + disp[vi];
        verts[vi].pos[0] = p.x; verts[vi].pos[1] = p.y; verts[vi].pos[2] = p.z;
        share[vi] = std::min(1.0f, len / (0.01f * U));
        most = std::max(most, len);
        ++moved;
    }
    if (!moved) return 0;

    // normals: made again for what moved, from the triangles round each position (UV seams' copies share them)
    size_t flipped = 0;
    if (!indices.empty()) {
        // a place's normal is shared by its copies that faced alike before (a UV seam's two, or two sections meeting), not by
        // a copy facing another way (an overlapping hidden geoset's, a fold's other side)
        struct Entry { V3 n0, acc; int next; };
        std::vector<Entry> ents;
        std::unordered_map<uint64_t, int> first;
        first.reserve(verts.size() / 4 + 16);
        auto slot = [&](uint32_t i, bool make) -> int {
            const V3 n0(verts[i].normal[0], verts[i].normal[1], verts[i].normal[2]);
            const uint64_t k = key(before[i]);
            auto it = first.find(k);
            int e = it == first.end() ? -1 : it->second;
            for (int x = e; x >= 0; x = ents[size_t(x)].next) if (ents[size_t(x)].n0.dot(n0) > 0.7f) return x;
            if (!make) return -1;
            ents.push_back({n0, V3(), e});
            first[k] = int(ents.size() - 1);
            return int(ents.size() - 1);
        };
        for (size_t t = 0; t + 2 < indices.size(); t += 3) {
            uint32_t a = indices[t], b = indices[t + 1], c = indices[t + 2];
            if (a >= verts.size() || b >= verts.size() || c >= verts.size()) continue;
            V3 pa(verts[a].pos[0], verts[a].pos[1], verts[a].pos[2]), pb(verts[b].pos[0], verts[b].pos[1], verts[b].pos[2]),
               pc(verts[c].pos[0], verts[c].pos[1], verts[c].pos[2]);
            V3 n = (pb - pa).cross(pc - pa);
            V3 n0 = (before[b] - before[a]).cross(before[c] - before[a]);
            if (n0.length() > 1e-10f && n.dot(n0) < 0) ++flipped;
            if (share[a] > 0 || share[b] > 0 || share[c] > 0)
                for (uint32_t i : {a, b, c}) { Entry& en = ents[size_t(slot(i, true))]; en.acc = en.acc + n; }
        }
        for (size_t i = 0; i < verts.size(); ++i) {
            if (share[i] <= 0) continue;
            int e = slot(uint32_t(i), false);
            if (e < 0 || ents[size_t(e)].acc.length() < 1e-12f) continue;
            V3 fresh = ents[size_t(e)].acc.normalized();
            V3 old(verts[i].normal[0], verts[i].normal[1], verts[i].normal[2]);
            V3 n = (old * (1 - share[i]) + fresh * share[i]).normalized();
            verts[i].normal[0] = n.x; verts[i].normal[1] = n.y; verts[i].normal[2] = n.z;
        }
    }
    if (stats) { stats->moved = moved; stats->maxMove = most; stats->flipped = flipped; stats->eased = eased; }
    return moved;
}

} // namespace wc
