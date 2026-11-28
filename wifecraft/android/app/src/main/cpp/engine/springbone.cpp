// Spring-bone jiggle: see springbone.h.
#include "springbone.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace wc {

namespace {
constexpr float kStep = 1.0f / 60.0f;
constexpr int kMaxSubsteps = 6;
constexpr float kPi = 3.14159265f;

float sstep(float a, float b, float x) {
    float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}
V3 rotVec(const M4& m, const V3& v) {   // the matrix without its translation
    return {m.at(0, 0) * v.x + m.at(0, 1) * v.y + m.at(0, 2) * v.z, m.at(1, 0) * v.x + m.at(1, 1) * v.y + m.at(1, 2) * v.z,
            m.at(2, 0) * v.x + m.at(2, 1) * v.y + m.at(2, 2) * v.z};
}
M4 lerpM(const M4& a, const M4& b, float t) {
    M4 r;
    for (int i = 0; i < 16; ++i) r.m[i] = a.m[i] + (b.m[i] - a.m[i]) * t;
    return r;
}
float maxDiff(const M4& a, const M4& b) {
    float d = 0;
    for (int i = 0; i < 16; ++i) d = std::max(d, std::fabs(a.m[i] - b.m[i]));
    return d;
}
// distance from p to segment a-b; t: the parameter 0..1
float segDist(const V3& p, const V3& a, const V3& b, float* t) {
    V3 ab = b - a;
    float l2 = ab.dot(ab);
    float s = l2 > 1e-12f ? std::clamp((p - a).dot(ab) / l2, 0.0f, 1.0f) : 0.0f;
    if (t) *t = s;
    return (p - (a + ab * s)).length();
}
Quat axisAngle(const V3& axis, float angle) {
    float h = angle * 0.5f, s = std::sin(h);
    return Quat(axis.x * s, axis.y * s, axis.z * s, std::cos(h));
}

SbStyle makeStyle(SbPreset p) {
    SbStyle s;
    // Thigh, Butt, Breast, Belly, Arm
    struct R { float f, z, g, off, sag; };
    R r[kSbRegions];
    switch (p) {
    case SbPreset::Zzz:     // exaggerated, bouncy, a long settle
        r[0] = {3.6f, 0.11f, 1.6f, 0.22f, 0.20f}; r[1] = {2.7f, 0.09f, 1.7f, 0.26f, 0.25f}; r[2] = {2.3f, 0.08f, 1.7f, 0.26f, 0.25f};
        r[3] = {2.0f, 0.13f, 1.5f, 0.22f, 0.20f}; r[4] = {3.2f, 0.12f, 1.3f, 0.20f, 0.10f};
        break;
    case SbPreset::Subtle:
        r[0] = {5.2f, 0.34f, 0.8f, 0.12f, 0.08f}; r[1] = {4.2f, 0.32f, 0.9f, 0.14f, 0.10f}; r[2] = {3.8f, 0.30f, 0.9f, 0.14f, 0.10f};
        r[3] = {3.2f, 0.36f, 0.8f, 0.12f, 0.08f}; r[4] = {5.0f, 0.36f, 0.7f, 0.10f, 0.05f};
        break;
    default:                // Natural
        r[0] = {4.6f, 0.22f, 1.0f, 0.16f, 0.12f}; r[1] = {3.4f, 0.20f, 1.1f, 0.18f, 0.15f}; r[2] = {3.0f, 0.18f, 1.1f, 0.18f, 0.15f};
        r[3] = {2.6f, 0.24f, 1.0f, 0.16f, 0.12f}; r[4] = {4.2f, 0.26f, 0.9f, 0.14f, 0.08f};
        break;
    }
    for (int i = 0; i < kSbRegions; ++i) {
        s.region[i].freq = r[i].f; s.region[i].zeta = r[i].z; s.region[i].gain = r[i].g; s.region[i].maxOffset = r[i].off;
        s.region[i].sag = r[i].sag; s.region[i].maxAngle = p == SbPreset::Zzz ? 0.34f : p == SbPreset::Subtle ? 0.16f : 0.24f; s.region[i].tipSoft = 0.15f;
    }
    return s;
}
} // namespace

const SbStyle& sbPreset(SbPreset p) {
    static const SbStyle styles[kSbPresets] = {makeStyle(SbPreset::Natural), makeStyle(SbPreset::Zzz), makeStyle(SbPreset::Subtle)};
    int i = int(p);
    return styles[i < 0 || i >= kSbPresets ? 0 : i];
}
const char* sbPresetName(SbPreset p) {
    switch (p) { case SbPreset::Zzz: return "ZZZ"; case SbPreset::Subtle: return "Subtle"; default: return "Natural"; }
}

M4 sbInverse(const M4& m) {
    // inverse of the 3x3 (cofactors) and the translation carried through it
    const float a = m.at(0, 0), b = m.at(0, 1), c = m.at(0, 2), d = m.at(1, 0), e = m.at(1, 1), f = m.at(1, 2), g = m.at(2, 0),
                h = m.at(2, 1), i = m.at(2, 2);
    const float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    float det = a * A + b * B + c * C;
    if (std::fabs(det) < 1e-20f) return M4();
    float id = 1.0f / det;
    M4 r;
    r.at(0, 0) = A * id;                 r.at(0, 1) = -(b * i - c * h) * id;  r.at(0, 2) = (b * f - c * e) * id;
    r.at(1, 0) = B * id;                 r.at(1, 1) = (a * i - c * g) * id;   r.at(1, 2) = -(a * f - c * d) * id;
    r.at(2, 0) = C * id;                 r.at(2, 1) = -(a * h - b * g) * id;  r.at(2, 2) = (a * e - b * d) * id;
    V3 t(m.at(0, 3), m.at(1, 3), m.at(2, 3));
    r.at(0, 3) = -(r.at(0, 0) * t.x + r.at(0, 1) * t.y + r.at(0, 2) * t.z);
    r.at(1, 3) = -(r.at(1, 0) * t.x + r.at(1, 1) * t.y + r.at(1, 2) * t.z);
    r.at(2, 3) = -(r.at(2, 0) * t.x + r.at(2, 1) * t.y + r.at(2, 2) * t.z);
    return r;
}

// ------------------------------------------------------------------------------------------------ the rig

namespace {
float gateWeight(const SbBodyView& v, size_t i, const std::array<int, 6>& gate) {
    float w = 0;
    for (int k = 0; k < 4; ++k) {
        int b = v.bones[i * 4 + size_t(k)];
        for (int g : gate) if (g >= 0 && g == b) { w += v.weights[i * 4 + size_t(k)] / 255.0f; break; }
    }
    return w;
}
float boneWeight(const SbBodyView& v, size_t i, int bone) {
    float w = 0;
    for (int k = 0; k < 4; ++k) if (v.bones[i * 4 + size_t(k)] == bone) w += v.weights[i * 4 + size_t(k)] / 255.0f;
    return w;
}

struct Builder {
    const SbBodyView& v;
    const SbSkeleton& sk;
    SbRig rig;
    float L = 0.24f;   // the thigh's length
    explicit Builder(const SbBodyView& vv, const SbSkeleton& s) : v(vv), sk(s) {}
    V3 P(size_t i) const { return v.pos[i]; }
    int bone(int j) const { return sk.bone[j]; }

    void add(SbRegion region, int side, int parent, int minLevel, int segs, const V3& root, const V3& end, float radius,
             std::array<int, 6> gate) {
        if (parent < 0 || (end - root).length() < 1e-4f * 0 + 0.01f * L) return;
        if (rig.helpers + segs > kSbMaxHelpers) return;
        SbChain c;
        c.region = region; c.side = side; c.parentBone = parent; c.minLevel = minLevel; c.first = rig.helpers;
        c.segments = segs; c.root = root; c.length = (end - root).length(); c.radius = std::max(radius, 0.05f * L); c.gate = gate;
        for (int i = 0; i < segs; ++i) c.tail[i] = lerp(root, end, float(i + 1) / float(segs));
        rig.chains.push_back(c);
        rig.helpers += segs;
    }
    // mean and rms distance from a line
    static void stats(const V3* pts, size_t n, V3& mean) {
        mean = V3();
        for (size_t i = 0; i < n; ++i) mean = mean + pts[i];
        if (n) mean = mean * (1.0f / float(n));
    }
    float rmsFromAxis(const std::vector<V3>& pts, const V3& a, const V3& dir) const {
        double s = 0;
        for (const V3& p : pts) { V3 q = p - a; V3 perp = q - dir * q.dot(dir); s += perp.dot(perp); }
        return pts.empty() ? 0.0f : float(std::sqrt(s / double(pts.size())));
    }
    // a chain from `root` through the cluster's centroid out to its far side
    void throughCluster(SbRegion region, int side, int parent, int minLevel, int segs, const V3& root, const std::vector<V3>& pts,
                        std::array<int, 6> gate, float reach, float radiusMul) {
        if (pts.size() < 12) return;
        V3 c; stats(pts.data(), pts.size(), c);
        V3 dir = (c - root);
        if (dir.length() < 0.02f * L) return;
        dir = dir.normalized();
        float far = 0;
        for (const V3& p : pts) far = std::max(far, (p - root).dot(dir));
        float rms = rmsFromAxis(pts, root, dir);
        add(region, side, parent, minLevel, segs, root, root + dir * (far * reach), rms * radiusMul, gate);
    }
};
} // namespace

SbRig buildSpringRig(const SbBodyView& body, const SbSkeleton& sk) {
    Builder B(body, sk);
    if (!body.pos || body.count == 0) return B.rig;
    if (sk.has(kSbThighL) && sk.has(kSbCalfL)) B.L = std::max((sk.pivot[kSbThighL] - sk.pivot[kSbCalfL]).length(), 0.05f);
    B.rig.unit = B.L;
    const float L = B.L;
    const int pelvis = sk.bone[kSbPelvis];
    const V3 pel = sk.pivot[kSbPelvis];
    const int thighB[2] = {sk.bone[kSbThighL], sk.bone[kSbThighR]};
    const int thighJ[2] = {kSbThighL, kSbThighR};
    const int calfJ[2] = {kSbCalfL, kSbCalfR};
    const int breastJ[2] = {kSbBreastL, kSbBreastR};
    const int armJ[2] = {kSbArmL, kSbArmR};
    const int foreJ[2] = {kSbForearmL, kSbForearmR};
    const int spine = sk.has(kSbSpine) ? sk.bone[kSbSpine] : pelvis;
    const int chest = sk.has(kSbChest) ? sk.bone[kSbChest] : spine;
    const size_t n = body.count;

    // ---- the butt: per side, three chains (the cheek, its upper outer part, its lower part)
    if (pelvis >= 0 && sk.has(kSbThighL) && sk.has(kSbThighR)) {
        for (int si = 0; si < 2; ++si) {
            const float s = si == 0 ? 1.0f : -1.0f;
            const V3 th = sk.pivot[thighJ[si]];
            const float ty = std::fabs(th.y);
            std::vector<V3> all;
            for (size_t i = 0; i < n; ++i) {
                V3 p = B.P(i);
                if (p.x > pel.x - 0.02f * L) continue;
                if (p.z < th.z - 0.5f * L || p.z > th.z + 0.5f * L) continue;
                float ys = p.y * s;
                if (ys < 0.02f * L || ys > 1.9f * ty + 0.2f * L) continue;
                float g = boneWeight(body, i, pelvis) + boneWeight(body, i, spine) + boneWeight(body, i, thighB[0]) + boneWeight(body, i, thighB[1]);
                if (g < 0.6f) continue;
                all.push_back(p);
            }
            if (all.size() < 20) continue;
            std::vector<float> zs;
            for (const V3& p : all) zs.push_back(p.z);
            std::nth_element(zs.begin(), zs.begin() + long(zs.size() / 2), zs.end());
            const float zm = zs[zs.size() / 2];
            std::vector<V3> up, lo;
            for (const V3& p : all) (p.z >= zm ? up : lo).push_back(p);
            std::array<int, 6> gate{{pelvis, spine, thighB[0], thighB[1], -1, -1}};
            V3 root(pel.x, s * 0.35f * ty, th.z + 0.05f * L);
            B.throughCluster(SbRegion::Butt, si == 0 ? 1 : -1, pelvis, 1, 3, root, all, gate, 0.92f, 2.0f);
            V3 ru(pel.x, s * 0.35f * ty, zm + 0.06f * L), rl(pel.x, s * 0.35f * ty, zm - 0.04f * L);
            B.throughCluster(SbRegion::Butt, si == 0 ? 1 : -1, pelvis, 2, 3, ru, up, gate, 0.92f, 2.0f);
            B.throughCluster(SbRegion::Butt, si == 0 ? 1 : -1, pelvis, 2, 3, rl, lo, gate, 0.92f, 2.0f);
        }
    }
    // ---- the thighs: an outer and an inner chain along each, and the capsule that keeps the butt from sinking into it
    for (int si = 0; si < 2; ++si) {
        if (!sk.has(thighJ[si]) || !sk.has(calfJ[si])) continue;
        const float s = si == 0 ? 1.0f : -1.0f;
        const V3 th = sk.pivot[thighJ[si]], ca = sk.pivot[calfJ[si]];
        const float len = (th - ca).length();
        const V3 axis = (ca - th) * (1.0f / std::max(len, 1e-6f));
        std::vector<V3> outer, inner, mid;
        for (size_t i = 0; i < n; ++i) {
            if (boneWeight(body, i, thighB[si]) < 0.6f) continue;
            V3 p = B.P(i), q = p - th;
            float t = q.dot(axis) / len;
            if (t < 0.05f || t > 0.95f) continue;
            V3 perp = q - axis * q.dot(axis);
            if (t > 0.3f && t < 0.7f) mid.push_back(p);
            ((p.y - th.y) * s >= 0 ? outer : inner).push_back(p);
            (void)perp;
        }
        std::array<int, 6> gate{{thighB[si], pelvis, spine, -1, -1, -1}};
        for (int pass = 0; pass < 2; ++pass) {
            const std::vector<V3>& pts = pass == 0 ? outer : inner;
            if (pts.size() < 20) continue;
            V3 c; Builder::stats(pts.data(), pts.size(), c);
            V3 q = c - th;
            V3 lat = q - axis * q.dot(axis);
            V3 root = th + lat * 0.6f + axis * (0.05f * len);
            V3 end = th + lat + axis * (0.74f * len);
            std::vector<V3> shifted;
            for (const V3& p : pts) shifted.push_back(p);
            float rms = B.rmsFromAxis(shifted, root, (end - root).normalized());
            B.add(SbRegion::Thigh, si == 0 ? 1 : -1, thighB[si], pass == 0 ? 1 : 2, 4, root, end, rms * 1.8f, gate);
        }
        if (mid.size() >= 20) {
            std::vector<float> d;
            for (const V3& p : mid) { V3 q = p - th; V3 perp = q - axis * q.dot(axis); d.push_back(perp.length()); }
            std::nth_element(d.begin(), d.begin() + long(d.size() / 2), d.end());
            SbCapsule cap;
            cap.bone = thighB[si]; cap.a = th; cap.b = th + axis * (0.95f * len); cap.r = 0.7f * d[d.size() / 2];
            B.rig.capsules.push_back(cap);
        }
    }
    // ---- the breasts: two chains each (the whole breast and its lower pole)
    for (int si = 0; si < 2; ++si) {
        if (!sk.has(breastJ[si])) continue;
        const float s = si == 0 ? 1.0f : -1.0f;
        const V3 bj = sk.pivot[breastJ[si]];
        const int bb = sk.bone[breastJ[si]];
        std::vector<V3> pts;
        for (size_t i = 0; i < n; ++i) {
            V3 p = B.P(i);
            if ((p - bj).length() > 0.45f * L || p.x < bj.x - 0.15f * L || p.y * s < -0.05f * L) continue;
            float g = boneWeight(body, i, chest) + boneWeight(body, i, bb) + boneWeight(body, i, spine);
            if (g < 0.5f) continue;
            pts.push_back(p);
        }
        if (pts.size() < 24) continue;
        std::vector<float> zs;
        for (const V3& p : pts) zs.push_back(p.z);
        std::nth_element(zs.begin(), zs.begin() + long(zs.size() / 2), zs.end());
        const float zm = zs[zs.size() / 2];
        std::vector<V3> lo;
        for (const V3& p : pts) if (p.z < zm) lo.push_back(p);
        std::array<int, 6> gate{{chest, bb, spine, -1, -1, -1}};
        V3 root(bj.x - 0.12f * L, bj.y, bj.z);
        B.throughCluster(SbRegion::Breast, si == 0 ? 1 : -1, chest, 1, 3, root, pts, gate, 0.95f, 2.0f);
        V3 rl(bj.x - 0.12f * L, bj.y, zm);
        B.throughCluster(SbRegion::Breast, si == 0 ? 1 : -1, chest, 2, 3, rl, lo, gate, 0.95f, 2.2f);
    }
    // ---- the belly
    if (pelvis >= 0 && spine >= 0 && sk.has(kSbThighL)) {
        const V3 th = sk.pivot[kSbThighL];
        std::vector<V3> pts;
        for (size_t i = 0; i < n; ++i) {
            V3 p = B.P(i);
            if (p.x < pel.x + 0.02f * L || p.z < th.z + 0.1f * L || p.z > th.z + 0.9f * L || std::fabs(p.y) > 0.6f * L) continue;
            if (boneWeight(body, i, spine) + boneWeight(body, i, pelvis) < 0.6f) continue;
            pts.push_back(p);
        }
        if (pts.size() >= 20) {
            V3 c; Builder::stats(pts.data(), pts.size(), c);
            std::array<int, 6> gate{{spine, pelvis, -1, -1, -1, -1}};
            B.throughCluster(SbRegion::Belly, 0, spine, 2, 2, V3(pel.x - 0.02f * L, 0, c.z), pts, gate, 0.92f, 2.0f);
        }
    }
    // ---- the upper arms' back
    for (int si = 0; si < 2; ++si) {
        if (!sk.has(armJ[si]) || !sk.has(foreJ[si])) continue;
        const V3 a = sk.pivot[armJ[si]], f = sk.pivot[foreJ[si]];
        const float len = (f - a).length();
        if (len < 1e-4f) continue;
        const V3 axis = (f - a) * (1.0f / len);
        const int ab = sk.bone[armJ[si]];
        std::vector<V3> pts;
        for (size_t i = 0; i < n; ++i) {
            if (boneWeight(body, i, ab) < 0.7f) continue;
            V3 p = B.P(i), q = p - a;
            float t = q.dot(axis) / len;
            if (t < 0.1f || t > 0.9f || p.x > a.x) continue;
            pts.push_back(p);
        }
        if (pts.size() < 20) continue;
        V3 c; Builder::stats(pts.data(), pts.size(), c);
        V3 q = c - a, lat = q - axis * q.dot(axis);
        V3 root = a + lat * 0.6f + axis * (0.08f * len);
        V3 end = a + lat + axis * (0.85f * len);
        std::array<int, 6> gate{{ab, sk.bone[foreJ[si]], -1, -1, -1, -1}};
        B.add(SbRegion::Arm, si == 0 ? 1 : -1, ab, 2, 3, root, end, B.rmsFromAxis(pts, root, (end - root).normalized()) * 1.8f, gate);
    }
    return B.rig;
}

void computeSpringWeights(const SbRig& rig, const SbBodyView& v, SbVertexWeights& out) {
    out.index.assign(v.count * kSbMaxPerVertex, -1);
    out.weight.assign(v.count * kSbMaxPerVertex, 0.0f);
    struct Box { V3 lo, hi; };
    std::vector<Box> box(rig.chains.size());
    for (size_t c = 0; c < rig.chains.size(); ++c) {
        const SbChain& ch = rig.chains[c];
        V3 lo = ch.root, hi = ch.root;
        for (int i = 0; i < ch.segments; ++i) {
            lo = V3(std::min(lo.x, ch.tail[i].x), std::min(lo.y, ch.tail[i].y), std::min(lo.z, ch.tail[i].z));
            hi = V3(std::max(hi.x, ch.tail[i].x), std::max(hi.y, ch.tail[i].y), std::max(hi.z, ch.tail[i].z));
        }
        float m = 1.2f * ch.radius;
        box[c] = {lo - V3(m, m, m), hi + V3(m, m, m)};
    }
    for (size_t i = 0; i < v.count; ++i) {
        const V3 p = v.pos[i];
        int cand[10]; float cw[10]; int nc = 0;
        for (size_t c = 0; c < rig.chains.size(); ++c) {
            const Box& b = box[c];
            if (p.x < b.lo.x || p.y < b.lo.y || p.z < b.lo.z || p.x > b.hi.x || p.y > b.hi.y || p.z > b.hi.z) continue;
            const SbChain& ch = rig.chains[c];
            float best = 1e9f, u = 0;
            for (int s = 0; s < ch.segments; ++s) {
                float t; float d = segDist(p, ch.head(s), ch.tail[s], &t);
                if (d < best) { best = d; u = float(s) + t; }
            }
            float env = 1.0f - sstep(0.55f * ch.radius, 1.15f * ch.radius, best);
            if (env <= 0) continue;
            env *= sstep(0.25f, 0.7f, gateWeight(v, i, ch.gate));
            if (env <= 1e-4f) continue;
            float uc = std::clamp(u, 0.5f, float(ch.segments) - 0.5f);
            for (int s = 0; s < ch.segments && nc < 9; ++s) {
                float h = std::max(0.0f, 1.0f - std::fabs(uc - (float(s) + 0.5f)));
                if (h * env > 1e-4f) { cand[nc] = ch.first + s; cw[nc] = h * env; ++nc; }
            }
        }
        // the four strongest, their sum held to 1
        for (int k = 0; k < kSbMaxPerVertex && nc > 0; ++k) {
            int bi = 0;
            for (int q = 1; q < nc; ++q) if (cw[q] > cw[bi]) bi = q;
            out.index[i * kSbMaxPerVertex + size_t(k)] = int16_t(cand[bi]);
            out.weight[i * kSbMaxPerVertex + size_t(k)] = cw[bi];
            cand[bi] = cand[nc - 1]; cw[bi] = cw[nc - 1]; --nc;
        }
        float sum = 0;
        for (int k = 0; k < kSbMaxPerVertex; ++k) sum += out.weight[i * kSbMaxPerVertex + size_t(k)];
        if (sum > 1.0f) for (int k = 0; k < kSbMaxPerVertex; ++k) out.weight[i * kSbMaxPerVertex + size_t(k)] /= sum;
    }
}

void springSoftMask(const SbRig& rig, const SbBodyView& v, std::vector<float>& mask) {
    mask.assign(v.count, 0.0f);
    for (size_t i = 0; i < v.count; ++i) {
        const V3 p = v.pos[i];
        float m = 0;
        for (const SbChain& ch : rig.chains) {
            float best = 1e9f;
            for (int s = 0; s < ch.segments; ++s) best = std::min(best, segDist(p, ch.head(s), ch.tail[s], nullptr));
            float env = 1.0f - sstep(0.55f * ch.radius, 1.15f * ch.radius, best);
            if (env > m) m = env * sstep(0.25f, 0.7f, gateWeight(v, i, ch.gate));
        }
        mask[i] = m;
    }
}

// ------------------------------------------------------------------------------------------------ the dynamics

void SpringBones::build(const SbRig& rig) {
    rig_ = rig;
    st_.assign(rig_.chains.size(), State());
    out_.assign(size_t(rig_.helpers), M4());
    parent0_.assign(rig_.chains.size(), M4());
    parent1_.assign(rig_.chains.size(), M4());
    capture0_.assign(rig_.capsules.size(), M4());
    capture1_.assign(rig_.capsules.size(), M4());
    restDown_.assign(rig_.chains.size(), V3(0, 0, -1));
    have_ = false; asleep_ = false; quiet_ = 0; acc_ = 0;
}

void SpringBones::setSettings(const SbSettings& s) {
    SbSettings n = s;
    n.amount = std::isfinite(n.amount) ? std::clamp(n.amount, 0.0f, 2.0f) : 1.0f;
    n.level = std::clamp(n.level, 0, 2);
    int p = int(n.preset);
    if (p < 0 || p >= kSbPresets) n.preset = SbPreset::Natural;
    if (n.preset != set_.preset || n.amount != set_.amount || n.level != set_.level) asleep_ = false;
    set_ = n;
}
void SpringBones::setRegionSize(SbRegion r, float size) {
    float s = std::isfinite(size) ? std::clamp(size, 0.4f, 2.5f) : 1.0f;
    if (s != size_[int(r)]) asleep_ = false;
    size_[int(r)] = s;
}

void SpringBones::reset() {
    for (size_t c = 0; c < st_.size(); ++c) {
        const SbChain& ch = rig_.chains[c];
        for (int j = 0; j < ch.segments; ++j) { st_[c].v[j] = V3(); st_[c].x[j] = parent1_[c].point(ch.tail[j]); st_[c].target[j] = st_[c].x[j]; }
    }
    acc_ = 0; quiet_ = 0; asleep_ = false;
    emit();
}

void SpringBones::collide(const M4& pw, int c) {
    const SbChain& ch = rig_.chains[size_t(c)];
    if (ch.region != SbRegion::Butt && ch.region != SbRegion::Belly) return;
    State& s = st_[size_t(c)];
    for (size_t k = 0; k < rig_.capsules.size(); ++k) {
        const SbCapsule& cap = rig_.capsules[k];
        const M4& cm = capture1_[k];
        V3 a = cm.point(cap.a), b = cm.point(cap.b);
        float scale = rotVec(cm, V3(1, 0, 0)).length();
        float r = cap.r * scale;
        for (int j = 0; j < ch.segments; ++j) {
            float t;
            float d = segDist(s.x[j], a, b, &t);
            if (d >= r) continue;
            V3 q = a + (b - a) * t;
            V3 out = s.x[j] - q;
            float l = out.length();
            if (l < 1e-7f) continue;
            s.x[j] = q + out * (r / l);
        }
    }
    (void)pw;
}

void SpringBones::substep(float h, float blend) {
    for (size_t c = 0; c < rig_.chains.size(); ++c) {
        const SbChain& ch = rig_.chains[c];
        if (ch.minLevel > set_.level) continue;
        State& s = st_[c];
        const SbRegionParams& rp = sbPreset(set_.preset).region[int(ch.region)];
        const M4 pm = lerpM(parent0_[c], parent1_[c], blend);
        const float size = std::max(size_[int(ch.region)], 0.1f);
        const float f0 = rp.freq / std::sqrt(size);
        const V3 down = rotVec(pm, restDown_[c]).normalized();
        const V3 gdelta = V3(0, 0, -1) - down;
        const V3 rootW = pm.point(ch.root);
        const float chainLen = (pm.point(ch.tail[ch.segments - 1]) - rootW).length();
        for (int j = 0; j < ch.segments; ++j) {
            V3 headRest = pm.point(ch.head(j)), tailRest = pm.point(ch.tail[j]);
            float segLen = (tailRest - headRest).length();
            V3 target = tailRest + gdelta * (rp.sag * segLen * float(j + 1) / float(ch.segments));
            s.target[j] = target;
            float w = 2.0f * kPi * f0 * (1.0f - rp.tipSoft * float(j) / float(std::max(ch.segments - 1, 1)) * 1.0f);
            V3 xOld = s.x[j];
            // implicit damped spring: unconditionally stable
            V3 v = (s.v[j] + (target - s.x[j]) * (h * w * w)) * (1.0f / (1.0f + 2.0f * rp.zeta * w * h + w * w * h * h));
            V3 x = s.x[j] + v * h;
            // stretch: the segment may stretch or shorten a quarter of its rest length
            V3 head = j == 0 ? rootW : s.x[j - 1];
            V3 d = x - head;
            float l = d.length(), lo = 0.75f * segLen, hi = 1.25f * segLen;
            if (l > 1e-8f && (l < lo || l > hi)) x = head + d * (std::clamp(l, lo, hi) / l);
            // how far it may stray
            V3 off = x - target;
            float ol = off.length(), lim = rp.maxOffset * chainLen * std::sqrt(size);
            if (ol > lim && ol > 1e-8f) x = target + off * (lim / ol);
            s.x[j] = x;
            collide(pm, int(c));
            s.v[j] = (s.x[j] - xOld) * (1.0f / h);
            float sp = s.v[j].length(), cap = 12.0f * chainLen;   // (a runaway cannot happen, but a teleport must not fling)
            if (sp > cap) s.v[j] = s.v[j] * (cap / sp);
        }
    }
}

void SpringBones::step(float dt, const M4& world, const M4* bones, int boneCount, bool snap) {
    world_ = world;
    if (rig_.chains.empty()) return;
    if (set_.level <= 0 || set_.amount <= 0) {
        bool dirty = false;
        for (const M4& m : out_) if (maxDiff(m, M4()) > 0) { dirty = true; break; }
        if (dirty) std::fill(out_.begin(), out_.end(), M4());
        asleep_ = true; ran_ = 0;
        return;
    }
    // the parents now
    bool moved = !have_ || snap;
    std::vector<M4> now(rig_.chains.size());
    for (size_t c = 0; c < rig_.chains.size(); ++c) {
        int pb = rig_.chains[c].parentBone;
        now[c] = world * (pb >= 0 && pb < boneCount ? bones[pb] : M4());
        if (have_ && maxDiff(now[c], parent1_[c]) > 1e-6f) moved = true;
    }
    std::vector<M4> capNow(rig_.capsules.size());
    for (size_t k = 0; k < rig_.capsules.size(); ++k) {
        int cb = rig_.capsules[k].bone;
        capNow[k] = world * (cb >= 0 && cb < boneCount ? bones[cb] : M4());
        if (have_ && maxDiff(capNow[k], capture1_[k]) > 1e-6f) moved = true;
    }
    ran_ = 0;
    if (!have_ || snap) {
        parent0_ = parent1_ = now; capture0_ = capture1_ = capNow;
        have_ = true;
        reset();
        return;
    }
    if (asleep_ && !moved) { return; }
    // a teleport: the root moved further than the chain is long
    for (size_t c = 0; c < rig_.chains.size(); ++c) {
        if ((now[c].point(rig_.chains[c].root) - parent1_[c].point(rig_.chains[c].root)).length() > 3.0f * rig_.chains[c].length + 0.5f * rig_.unit) {
            parent0_ = parent1_ = now; capture0_ = capture1_ = capNow;
            reset();
            return;
        }
    }
    parent0_ = parent1_; parent1_ = now;
    capture0_ = capture1_; capture1_ = capNow;
    asleep_ = false;
    acc_ += std::clamp(dt, 0.0f, 0.1f);
    int n = std::min(int(acc_ / kStep), kMaxSubsteps);
    if (acc_ > kStep * float(kMaxSubsteps)) acc_ = std::fmod(acc_, kStep);
    acc_ -= float(n) * kStep;
    // (the capsules are used at the pose of the end of the call, as the substeps blend only the chains' parents)
    for (int i = 0; i < n; ++i) { substep(kStep, float(i + 1) / float(n)); ++ran_; }
    if (n > 0) {
        // rest?
        bool still = !moved;
        if (still) {
            for (size_t c = 0; c < rig_.chains.size() && still; ++c) {
                const SbChain& ch = rig_.chains[c];
                for (int j = 0; j < ch.segments; ++j)
                    if (st_[c].v[j].length() > 0.004f * ch.length || (st_[c].x[j] - st_[c].target[j]).length() > 0.004f * ch.length) { still = false; break; }
            }
        }
        quiet_ = still ? quiet_ + n : 0;
        if (quiet_ >= 30) {
            for (size_t c = 0; c < rig_.chains.size(); ++c)
                for (int j = 0; j < rig_.chains[c].segments; ++j) { st_[c].x[j] = st_[c].target[j]; st_[c].v[j] = V3(); }
            asleep_ = true;
        }
        emit();
    }
}

void SpringBones::emit() {
    for (size_t c = 0; c < rig_.chains.size(); ++c) {
        const SbChain& ch = rig_.chains[c];
        const SbRegionParams& rp = sbPreset(set_.preset).region[int(ch.region)];
        if (ch.minLevel > set_.level || set_.level <= 0) {
            for (int j = 0; j < ch.segments; ++j) out_[size_t(ch.first + j)] = M4();
            continue;
        }
        const M4 inv = sbInverse(parent1_[c]);
        const float size = std::max(size_[int(ch.region)], 0.1f);
        const float g = rp.gain * set_.amount * std::pow(size, 0.35f);
        V3 disp[5];
        for (int j = 0; j < ch.segments; ++j) {
            V3 cur = inv.point(st_[c].x[j]);
            V3 d = (cur - ch.tail[j]) * g;
            float dl = d.length(), lim = 0.4f * ch.length;   // (never more than 40% of the chain's length from rest)
            if (dl > lim) d = d * (lim / dl);
            disp[j] = ch.tail[j] + d;
        }
        for (int j = 0; j < ch.segments; ++j) {
            V3 h0 = ch.head(j), t0 = ch.tail[j];
            V3 h1 = j == 0 ? ch.root : disp[j - 1];
            V3 t1 = disp[j];
            V3 dr = (t0 - h0).normalized(), dc = (t1 - h1).normalized();
            V3 ax = dr.cross(dc);
            float sn = ax.length(), cs = std::clamp(dr.dot(dc), -1.0f, 1.0f);
            float ang = std::atan2(sn, cs);
            ang = std::min(ang, rp.maxAngle);
            M4 R;
            if (sn > 1e-7f) R = M4::rotation(axisAngle(ax * (1.0f / sn), ang));
            out_[size_t(ch.first + j)] = M4::translation(h1) * R * M4::translation(h0 * -1.0f);
        }
    }
}

void SpringBones::kick(const V3& at, const V3& vel, float radius) {
    if (radius <= 1e-6f) return;
    for (size_t c = 0; c < rig_.chains.size(); ++c)
        for (int j = 0; j < rig_.chains[c].segments; ++j) {
            float d = (st_[c].x[j] - at).length();
            if (d >= radius) continue;
            float w = 1.0f - d / radius;
            st_[c].v[j] = st_[c].v[j] + vel * w;
        }
    asleep_ = false; quiet_ = 0;
}

bool SpringBones::regionOffset(SbRegion r, int side, V3* out) const {
    V3 sum; int n = 0;
    for (size_t c = 0; c < rig_.chains.size(); ++c) {
        const SbChain& ch = rig_.chains[c];
        if (ch.region != r || (side != 0 && ch.side != side) || ch.minLevel > set_.level) continue;
        for (int j = 0; j < ch.segments; ++j) { sum = sum + (st_[c].x[j] - st_[c].target[j]); ++n; }
    }
    if (!n) return false;
    if (out) *out = sum * (1.0f / float(n));
    return true;
}

float SpringBones::maxOffsetRatio() const {
    float m = 0;
    for (size_t c = 0; c < rig_.chains.size(); ++c)
        for (int j = 0; j < rig_.chains[c].segments; ++j)
            m = std::max(m, (st_[c].x[j] - st_[c].target[j]).length() / std::max(rig_.chains[c].length, 1e-6f));
    return m;
}

float SpringBones::energy() const {
    double e = 0;
    for (size_t c = 0; c < rig_.chains.size(); ++c)
        for (int j = 0; j < rig_.chains[c].segments; ++j) {
            const SbRegionParams& rp = sbPreset(set_.preset).region[int(rig_.chains[c].region)];
            double w = 2.0 * kPi * rp.freq;
            e += 0.5 * double(st_[c].v[j].dot(st_[c].v[j])) + 0.5 * w * w * double((st_[c].x[j] - st_[c].target[j]).dot(st_[c].x[j] - st_[c].target[j]));
        }
    return float(e);
}

} // namespace wc
