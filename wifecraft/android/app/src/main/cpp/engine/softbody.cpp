#include "softbody.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <set>

namespace wc {

namespace {

constexpr float kPi = 3.14159265f;

// The 3 x 3 part of a matrix: column c as a vector.
V3 column(const M4& m, int c) { return V3(m.at(0, c), m.at(1, c), m.at(2, c)); }

// A rotation (orthonormal columns) taken from a matrix that may also scale.
struct R3 {
    V3 c[3];
    V3 apply(const V3& v) const { return c[0] * v.x + c[1] * v.y + c[2] * v.z; }
    V3 applyT(const V3& v) const { return V3(c[0].dot(v), c[1].dot(v), c[2].dot(v)); }
};

R3 rotationOf(const M4& m) {
    R3 r;
    V3 x = column(m, 0).normalized();
    V3 y = column(m, 1);
    y = (y - x * x.dot(y)).normalized();
    r.c[0] = x;
    r.c[1] = y;
    r.c[2] = x.cross(y);
    return r;
}

// The inverse of a matrix's 3 x 3 part (the world's: a turn and a scale), applied to a direction.
struct Inv3 {
    float m[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    explicit Inv3(const M4& w) {
        float a = w.at(0, 0), b = w.at(0, 1), c = w.at(0, 2);
        float d = w.at(1, 0), e = w.at(1, 1), f = w.at(1, 2);
        float g = w.at(2, 0), h = w.at(2, 1), i = w.at(2, 2);
        float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        float det = a * A + b * B + c * C;
        if (std::fabs(det) < 1e-12f) return;
        float k = 1.0f / det;
        m[0] = A * k; m[1] = -(b * i - c * h) * k; m[2] = (b * f - c * e) * k;
        m[3] = B * k; m[4] = (a * i - c * g) * k;  m[5] = -(a * f - c * d) * k;
        m[6] = C * k; m[7] = -(a * h - b * g) * k; m[8] = (a * e - b * d) * k;
    }
    V3 apply(const V3& v) const {
        return V3(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z);
    }
};

V3 mul(const V3& a, const V3& b) { return V3(a.x * b.x, a.y * b.y, a.z * b.z); }

bool finite(const V3& v) { return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z); }

} // namespace

// ------------------------------------------------------------------------------------------------------------ tuning

SoftTuning softTuning(SoftKind kind, int level) {
    SoftTuning t;
    switch (kind) {
        case SoftKind::Chest: t.freq = 2.6f; t.damping = 0.28f; t.sag = 0.35f; t.limit = 0.28f; t.stiffness = V3(1.8f, 1.4f, 1.0f); break;
        case SoftKind::Belly: t.freq = 3.6f; t.damping = 0.40f; t.sag = 0.20f; t.limit = 0.12f; t.stiffness = V3(1.3f, 1.6f, 1.0f); break;
        case SoftKind::Rear:  t.freq = 3.2f; t.damping = 0.34f; t.sag = 0.25f; t.limit = 0.18f; t.stiffness = V3(1.5f, 1.6f, 1.0f); break;
        case SoftKind::Thigh: t.freq = 4.6f; t.damping = 0.45f; t.sag = 0.10f; t.limit = 0.09f; t.stiffness = V3(1.3f, 1.3f, 1.0f); break;
        case SoftKind::Arm:   t.freq = 4.6f; t.damping = 0.45f; t.sag = 0.12f; t.limit = 0.09f; t.stiffness = V3(1.0f, 1.0f, 1.0f); break;
    }
    switch (level) {
        case kSoftSubtle: t.freq *= 1.3f; t.damping *= 1.45f; t.sag *= 0.6f; t.gain = 0.55f; break;
        case kSoftStrong: t.freq *= 0.85f; t.damping *= 0.75f; t.sag *= 1.4f; t.gain = 1.45f; break;
        case kSoftNatural: break;
        default: t.gain = 0; break;
    }
    return t;
}

// ------------------------------------------------------------------------------------------------------------ the rig

namespace {

enum Class { kChest = 0, kLower = 1, kThigh = 2, kArm = 3, kNone = 4 };

struct Named { const char* name; Class cls; };
const Named kNamed[] = {
    {"Chest", kChest}, {"L_Breast", kChest}, {"R_Breast", kChest},
    {"Root", kLower}, {"Pelvis", kLower}, {"Waist", kLower}, {"Spine1", kLower}, {"Spine2", kLower},
    {"L_Thigh", kThigh}, {"R_Thigh", kThigh},
    {"L_UpperArm", kArm}, {"R_UpperArm", kArm},
    {"Neck1", kNone}, {"Neck2", kNone}, {"Head", kNone}, {"Jaw", kNone},
    {"L_Clavicle", kNone}, {"R_Clavicle", kNone}, {"L_Forearm", kNone}, {"R_Forearm", kNone},
    {"L_Hand", kNone}, {"R_Hand", kNone}, {"L_Calf", kNone}, {"R_Calf", kNone}, {"L_Foot", kNone}, {"R_Foot", kNone},
};

V3 pos(const M2Vertex& v) { return V3(v.pos[0], v.pos[1], v.pos[2]); }

} // namespace

SoftRig softRig(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                const std::vector<M2Submesh>& submeshes, const std::vector<uint16_t>& geosets) {
    SoftRig rig;
    size_t nb = m.bones.size();
    if (!nb || verts.empty()) return rig;

    // Each bone's class: that of the first named bone at or above it.
    std::vector<int> named(nb, -1);
    for (const Named& n : kNamed) {
        int b = m.findWowBone(n.name);
        if (b >= 0 && size_t(b) < nb && named[size_t(b)] < 0) named[size_t(b)] = n.cls;
    }
    rig.boneClass.assign(nb * 4, 0.0f);
    for (size_t i = 0; i < nb; ++i) {
        int cls = kNone;
        for (int b = int(i), guard = 0; b >= 0 && size_t(b) < nb && guard < 256; b = m.bones[size_t(b)].parent, ++guard)
            if (named[size_t(b)] >= 0) { cls = named[size_t(b)]; break; }
        if (cls < 4) rig.boneClass[i * 4 + size_t(cls)] = 1;
    }

    // The skin: the vertices of the body's shown sections drawn with its skin texture (as drawn, and as modelled).
    struct Body {
        const std::vector<M2Vertex>* v;
        std::vector<uint8_t> skin;
    };
    auto skinOf = [&](const std::vector<M2Vertex>& vs, const std::vector<uint32_t>& ix, const std::vector<M2Submesh>& subs) {
        Body b{&vs, std::vector<uint8_t>(vs.size(), 0)};
        for (const M2Batch& bt : m.batches) {
            if (bt.submesh >= subs.size() || bt.textureCombo >= m.textureCombos.size()) continue;
            uint16_t ti = m.textureCombos[bt.textureCombo];
            if (ti >= m.textures.size() || m.textures[ti].type != 1) continue;
            if (bt.material < m.materials.size() && m.materials[bt.material].blend >= 2) continue;
            const M2Submesh& sm = subs[bt.submesh];
            if (!geosets.empty() && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
            for (uint32_t t = sm.indexStart; t < sm.indexStart + sm.indexCount && t < ix.size(); ++t)
                if (ix[t] < b.skin.size()) b.skin[ix[t]] = 1;
        }
        return b;
    };
    const bool shaped = &verts != &m.vertices;
    Body drawn = skinOf(verts, indices, submeshes);
    Body modelled = shaped ? skinOf(m.vertices, m.indices, m.submeshes) : Body{&m.vertices, {}};
    // Each skin vertex's share of each class, by its bones' weights.
    auto gate = [&](const M2Vertex& v, float out[4]) {
        out[0] = out[1] = out[2] = out[3] = 0;
        float total = 0;
        for (int k = 0; k < 4; ++k) {
            if (!v.weights[k] || v.bones[k] >= nb) continue;
            float w = v.weights[k] / 255.0f;
            total += w;
            for (int c = 0; c < 4; ++c) out[c] += w * rig.boneClass[size_t(v.bones[k]) * 4 + size_t(c)];
        }
        if (total > 0) for (int c = 0; c < 4; ++c) out[c] /= total;
    };

    auto pivot = [&](const char* name, int& b) -> V3 { b = m.findWowBone(name); return b >= 0 ? m.bones[size_t(b)].pivot : V3(); };
    int bChest, bSpine1, bSpine2, bPelvis, bNeck, bArmL, bArmR, bForeL, bForeR, bThighL, bThighR, bCalfL, bCalfR, bBreastL, bBreastR;
    V3 chest = pivot("Chest", bChest), spine1 = pivot("Spine1", bSpine1), spine2 = pivot("Spine2", bSpine2);
    V3 pelvis = pivot("Pelvis", bPelvis), neck = pivot("Neck1", bNeck);
    V3 armL = pivot("L_UpperArm", bArmL), armR = pivot("R_UpperArm", bArmR);
    V3 foreL = pivot("L_Forearm", bForeL), foreR = pivot("R_Forearm", bForeR);
    V3 thighL = pivot("L_Thigh", bThighL), thighR = pivot("R_Thigh", bThighR);
    V3 calfL = pivot("L_Calf", bCalfL), calfR = pivot("R_Calf", bCalfR);
    V3 breastL = pivot("L_Breast", bBreastL), breastR = pivot("R_Breast", bBreastR);
    if (bChest < 0 || bPelvis < 0 || bThighL < 0 || bThighR < 0) return rig;   // not a body we know
    if (bSpine1 < 0) { spine1 = pelvis; bSpine1 = bPelvis; }
    if (bSpine2 < 0) spine2 = (spine1 + chest) * 0.5f;
    const float shoulder = bArmL >= 0 ? std::fabs(armL.y) : 0.17f;
    const float hip = std::max(0.02f, std::fabs(thighL.y));
    const float leg = bCalfL >= 0 ? std::max(0.1f, thighL.z - calfL.z) : 0.5f;

    // The skin vertex at the extreme of `dir` (+1 most forward, -1 most back) among those passing `keep`.
    auto extreme = [&](const Body& b, float dir, auto keep, V3& apex) -> bool {
        float best = -1e9f;
        bool found = false;
        float g[4];
        for (size_t i = 0; i < b.v->size() && i < b.skin.size(); ++i) {
            if (!b.skin[i]) continue;
            const M2Vertex& v = (*b.v)[i];
            gate(v, g);
            V3 p = pos(v);
            if (!keep(p, g)) continue;
            if (p.x * dir > best) { best = p.x * dir; apex = p; found = true; }
        }
        return found;
    };
    // The region's apex as drawn, and how much farther out along `dir` a shape put it than the model has it.
    auto fit = [&](float dir, auto keep, V3& apex, float& grown) -> bool {
        grown = 0;
        if (!extreme(drawn, dir, keep, apex)) return false;
        V3 was;
        if (shaped && extreme(modelled, dir, keep, was)) grown = (apex.x - was.x) * dir;
        return true;
    };
    auto heavier = [](float grown, float size) { return 1.0f + 2.0f * std::clamp(grown / std::max(size, 1e-3f), 0.0f, 0.75f); };

    // The chest: where the breast bones are (the HD women's models have them); without them, no breasts to move.
    for (int s = 0; s < 2 && bBreastL >= 0 && bBreastR >= 0; ++s) {
        float side = s == 0 ? 1.0f : -1.0f;
        V3 P = s == 0 ? breastL : breastR;
        V3 apex;
        float grown;
        bool ok = fit(1.0f, [&](const V3& p, const float* g) {
            return g[kChest] + g[kLower] >= 0.5f && p.y * side > 0.15f * shoulder &&
                   std::fabs(p.y - P.y) < 0.6f * shoulder && std::fabs(p.z - P.z) < 0.6f * shoulder;
        }, apex, grown);
        if (!ok) continue;
        float depth = std::max(apex.x - P.x, 0.25f * shoulder);
        SoftRegion r;
        r.kind = SoftKind::Chest;
        r.bone = bChest;
        r.centre = V3(apex.x - depth, apex.y, apex.z);
        r.radii = V3(depth, 0.5f * shoulder, 0.55f * shoulder);
        r.axis = V3(1, 0, 0);
        r.mask[kChest] = 1; r.mask[kLower] = 0.5f;
        r.mass = V3(apex.x - 0.45f * depth, apex.y, apex.z);
        r.size = 0.5f * shoulder;
        r.weight = heavier(grown, r.size);
        rig.regions.push_back(r);
    }
    // The belly, under the navel's height up to the ribs, at the front.
    {
        float top = (spine2.z + chest.z) * 0.5f;
        V3 apex;
        float grown;
        if (fit(1.0f, [&](const V3& p, const float* g) {
                return g[kLower] + g[kChest] >= 0.5f && std::fabs(p.y) < 0.6f * hip && p.z > pelvis.z && p.z < top;
            }, apex, grown)) {
            float depth = std::max(0.55f * (apex.x - spine1.x), 0.3f * hip);
            SoftRegion r;
            r.kind = SoftKind::Belly;
            r.bone = bSpine1;
            r.centre = V3(apex.x - depth, 0, apex.z);
            r.radii = V3(depth, 1.15f * hip, 0.55f * std::max(0.05f, chest.z - pelvis.z));
            r.axis = V3(1, 0, 0);
            r.mask[kChest] = 0.4f; r.mask[kLower] = 1; r.mask[kThigh] = 0.3f;
            r.mass = V3(apex.x - 0.4f * depth, 0, apex.z);
            r.size = 1.15f * hip;
            r.weight = heavier(grown, r.size);
            rig.regions.push_back(r);
        }
    }
    // The rear, each side: the hindmost skin between the hip joint's height (less a third of the thigh) and the pelvis.
    for (int s = 0; s < 2; ++s) {
        float side = s == 0 ? 1.0f : -1.0f;
        V3 T = s == 0 ? thighL : thighR;
        V3 apex;
        float grown;
        if (!fit(-1.0f, [&](const V3& p, const float* g) {
                return g[kLower] + g[kThigh] >= 0.5f && p.y * side > 0.15f * hip && p.y * side < 1.8f * hip &&
                       p.z > T.z - 0.3f * leg && p.z < pelvis.z + 0.05f * leg;
            }, apex, grown)) continue;
        float depth = std::max(0.5f * (pelvis.x - apex.x), 0.3f * hip);
        SoftRegion r;
        r.kind = SoftKind::Rear;
        r.bone = bPelvis;
        r.centre = V3(apex.x + depth, apex.y, apex.z);
        r.radii = V3(depth, 1.0f * hip, 0.24f * leg);
        r.axis = V3(-1, 0, 0);
        r.mask[kLower] = 1; r.mask[kThigh] = 1;
        r.mass = V3(apex.x + 0.45f * depth, apex.y, apex.z);
        r.size = hip;
        r.weight = heavier(grown, r.size);
        rig.regions.push_back(r);
    }
    // The thighs, lightly: the upper leg's flesh, carried by the thigh.
    for (int s = 0; s < 2 && bCalfL >= 0 && bCalfR >= 0; ++s) {
        V3 T = s == 0 ? thighL : thighR, C = s == 0 ? calfL : calfR;
        SoftRegion r;
        r.kind = SoftKind::Thigh;
        r.bone = s == 0 ? bThighL : bThighR;
        r.centre = lerp(T, C, 0.4f);
        r.radii = V3(0.9f * hip, 0.9f * hip, 0.3f * leg);
        r.mask[kThigh] = 1;
        r.mass = r.centre - V3(0.3f * hip, 0, 0);
        r.size = 0.9f * hip;
        rig.regions.push_back(r);
    }
    // The upper arms, lightly: under the upper arm, carried by it.
    for (int s = 0; s < 2 && bArmL >= 0 && bArmR >= 0 && bForeL >= 0 && bForeR >= 0; ++s) {
        V3 U = s == 0 ? armL : armR, F = s == 0 ? foreL : foreR;
        float len = (F - U).length();
        if (len < 0.05f) continue;
        SoftRegion r;
        r.kind = SoftKind::Arm;
        r.bone = s == 0 ? bArmL : bArmR;
        r.centre = lerp(U, F, 0.5f);
        r.radii = V3(0.45f * len, 0.45f * len, 0.45f * len);
        r.mask[kArm] = 1;
        r.mass = r.centre - V3(0.15f * len, 0, 0);
        r.size = 0.2f * len;
        rig.regions.push_back(r);
    }
    (void)neck;
    if (rig.regions.size() > size_t(kSoftMaxRegions)) rig.regions.resize(size_t(kSoftMaxRegions));
    return rig;
}

// ------------------------------------------------------------------------------------------------------------ the sim

void SoftSim::step(const std::vector<M4>& bones, const M4& world, uint32_t nowMs) {
    if (level_ == kSoftOff || regions_.empty()) {
        for (State& s : state_) s = State();
        started_ = false;
        return;
    }
    bool restart = !started_ || nowMs < last_;
    uint32_t dtMs = restart ? 0 : nowMs - last_;
    bool hold = dtMs > kMaxFrameMs;
    last_ = nowMs;
    started_ = true;

    int steps = 0;
    if (restart || hold) acc_ = 0;
    else {
        acc_ += dtMs * uint32_t(kHz);          // 1/120000 s
        steps = int(acc_ / 1000);
        if (steps > kMaxSteps) { steps = kMaxSteps; acc_ %= 1000; }
        else acc_ -= uint32_t(steps) * 1000;
    }
    const float h = 1.0f / float(kHz);
    const Inv3 toModel(world);
    const float scale = std::max(1e-4f, column(world, 0).length());
    const V3 gWorld(0, 0, -kGravity);

    for (size_t i = 0; i < regions_.size(); ++i) {
        const SoftRegion& rg = regions_[i];
        State& st = state_[i];
        M4 bone = rg.bone >= 0 && size_t(rg.bone) < bones.size() ? bones[size_t(rg.bone)] : M4();
        M4 f = world * bone;
        V3 A = f.point(rg.mass);
        R3 R = rotationOf(f);
        SoftTuning t = softTuning(rg.kind, level_);
        float w = 2 * kPi * t.freq / std::sqrt(std::max(0.25f, rg.weight));
        V3 k = t.stiffness * (w * w);
        float c = 2 * t.damping * w;
        // Gravity beyond what the model was made hanging with: as modelled, standing, it hangs `sag` of the way more.
        V3 gBind = R.apply(V3(0, 0, -kGravity));
        V3 gEff = gWorld - gBind * (1 - t.sag);
        V3 gl = R.applyT(gEff);
        st.eq = R.apply(V3(gl.x / k.x, gl.y / k.y, gl.z / k.z));
        const float limit = t.limit * rg.size * std::sqrt(std::max(1.0f, rg.weight)) * scale;   // world units

        if (restart || !finite(st.x) || !finite(st.v)) {
            st.anchor = A; st.x = A + st.eq; st.v = V3(); st.u = st.eq;
        } else if (hold || (A - st.anchor).length() > kTeleport * scale) {
            // put there, or long away: keep the displacement, drop the swing
            st.anchor = A; st.x = A + st.u; st.v = V3();
        } else if (steps > 0) {
            V3 A0 = st.anchor;
            V3 vA = (A - A0) * (1.0f / (float(steps) * h));
            for (int j = 0; j < steps; ++j) {
                V3 Aj = lerp(A0, A, float(j + 1) / float(steps));
                V3 u = st.x - Aj;
                V3 spring = R.apply(mul(k, R.applyT(u))) * -1.0f;
                V3 a = spring + gEff - (st.v - vA) * c;
                st.v = st.v + a * h;                 // semi-implicit Euler: velocity first
                st.x = st.x + st.v * h;
                u = st.x - Aj;
                float len = u.length();
                if (len > 2 * limit) {               // a wall well past the drawn limit: nothing runs away
                    V3 n = u * (1.0f / len);
                    st.x = Aj + n * (2 * limit);
                    float out = (st.v - vA).dot(n);
                    if (out > 0) st.v = st.v - n * out;
                }
            }
            st.anchor = A;
            st.u = st.x - A;
        }
        // Drawn: softly limited, in the model's space.
        V3 um = toModel.apply(st.u);
        float lm = limit / scale, len = um.length();
        V3 shown = len > 1e-7f ? um * (lm * std::tanh(len / lm) / len) : V3();
        st.out = shown * t.gain;
        st.squash = std::clamp(0.5f * st.out.length() / std::max(rg.size, 1e-3f), 0.0f, 0.3f);
        st.centre = bone.point(rg.mass);
    }
}

// ------------------------------------------------------------------------------------------------------------ drawing

namespace {

struct Locs {
    int n = -1, gain = -1, gate = -1, rest = -1, invR = -1, axis = -1, mask = -1, disp = -1, ctr = -1;
};

const Locs& locs(unsigned program) {
    static unsigned cached = 0;
    static Locs l;
    if (cached != program) {
        cached = program;
        l.n = glGetUniformLocation(program, "uSoftN");
        l.gain = glGetUniformLocation(program, "uSoftGain");
        l.gate = glGetUniformLocation(program, "uSoftGate");
        l.rest = glGetUniformLocation(program, "uSoftRest");
        l.invR = glGetUniformLocation(program, "uSoftInvR");
        l.axis = glGetUniformLocation(program, "uSoftAxis");
        l.mask = glGetUniformLocation(program, "uSoftMask");
        l.disp = glGetUniformLocation(program, "uSoftDisp");
        l.ctr = glGetUniformLocation(program, "uSoftCtr");
    }
    return l;
}

unsigned gateTexture(const std::vector<float>& cls, size_t bones) {
    std::vector<uint8_t> px(std::max<size_t>(1, bones) * 4, 0);
    for (size_t i = 0; i < bones && i * 4 + 3 < cls.size(); ++i)
        for (int c = 0; c < 4; ++c) px[i * 4 + size_t(c)] = uint8_t(std::lround(std::clamp(cls[i * 4 + size_t(c)], 0.0f, 1.0f) * 255));
    unsigned tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, GLsizei(std::max<size_t>(1, bones)), 0, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    return tex;
}

} // namespace

float softGeosetGain(uint16_t geoset) {
    // A cape hangs on its own: it doesn't take the body's swing. Every other section of the body (skin, underwear,
    // sleeves, tabards, belts drawn on it) lies on the skin and moves with it, or the skin would come through.
    return geoset / 100 == 15 ? 0.0f : 1.0f;
}

void SoftDraw::rebuild(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                       const std::vector<M2Submesh>& submeshes, const std::vector<uint16_t>& geosets, bool glAlive) {
    SoftRig rig = softRig(m, verts, indices, submeshes, geosets);
    sim.setRegions(rig.regions);
    boneClass = rig.boneClass;
    bones = m.bones.size();
    if (glAlive && gateTex) glDeleteTextures(1, &gateTex);
    gateTex = 0;
    dropSkinned(glAlive);
    dirty = false;
}

void SoftDraw::dropSkinned(bool glAlive) {
    for (auto& s : skinned) if (glAlive && s.tex) glDeleteTextures(1, &s.tex);
    skinned.clear();
}

void SoftDraw::release(bool glAlive) {
    if (glAlive && gateTex) glDeleteTextures(1, &gateTex);
    gateTex = 0;
    dropSkinned(glAlive);
}

void SoftDraw::forget() {
    gateTex = 0;
    skinned.clear();
}

bool SoftDraw::active() const { return sim.level() != kSoftOff && !sim.regions().empty(); }

unsigned SoftDraw::bodyGate() {
    if (!gateTex) gateTex = gateTexture(boneClass, bones);
    return gateTex;
}

unsigned SoftDraw::skinnedGate(size_t index, const void* mesh, const std::vector<int>& boneMap) {
    if (skinned.size() <= index) skinned.resize(index + 1);
    SkinnedGate& s = skinned[index];
    if (s.tex && s.mesh == mesh && s.map == boneMap) return s.tex;
    if (s.tex) glDeleteTextures(1, &s.tex);
    std::vector<float> cls(std::max<size_t>(1, boneMap.size()) * 4, 0.0f);
    for (size_t i = 0; i < boneMap.size(); ++i)
        if (boneMap[i] >= 0 && size_t(boneMap[i]) < bones)
            for (int c = 0; c < 4; ++c) cls[i * 4 + size_t(c)] = boneClass[size_t(boneMap[i]) * 4 + size_t(c)];
    s.mesh = mesh;
    s.map = boneMap;
    s.tex = gateTexture(cls, std::max<size_t>(1, boneMap.size()));
    return s.tex;
}

void SoftDraw::uniforms(unsigned program, unsigned gate) const {
    const Locs& l = locs(program);
    if (!gate || !active()) { glUniform1i(l.n, 0); return; }
    float rest[kSoftMaxRegions * 4] = {}, invR[kSoftMaxRegions * 4] = {}, axis[kSoftMaxRegions * 4] = {};
    float mask[kSoftMaxRegions * 4] = {}, disp[kSoftMaxRegions * 4] = {}, ctr[kSoftMaxRegions * 4] = {};
    const auto& rs = sim.regions();
    int n = int(std::min<size_t>(rs.size(), size_t(kSoftMaxRegions)));
    for (int i = 0; i < n; ++i) {
        const SoftRegion& r = rs[size_t(i)];
        float* q;
        q = &rest[i * 4]; q[0] = r.centre.x; q[1] = r.centre.y; q[2] = r.centre.z;
        q = &invR[i * 4]; q[0] = 1.0f / std::max(r.radii.x, 1e-4f); q[1] = 1.0f / std::max(r.radii.y, 1e-4f); q[2] = 1.0f / std::max(r.radii.z, 1e-4f);
        q = &axis[i * 4]; q[0] = r.axis.x; q[1] = r.axis.y; q[2] = r.axis.z; q[3] = r.axis.length() > 0.5f ? 1.0f : 0.0f;
        q = &mask[i * 4]; for (int c = 0; c < 4; ++c) q[c] = r.mask[c];
        V3 d = sim.offset(size_t(i)), cn = sim.centreNow(size_t(i));
        q = &disp[i * 4]; q[0] = d.x; q[1] = d.y; q[2] = d.z; q[3] = sim.squash(size_t(i));
        q = &ctr[i * 4]; q[0] = cn.x; q[1] = cn.y; q[2] = cn.z;
    }
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, gate);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(l.gate, 2);
    glUniform1i(l.n, n);
    glUniform1f(l.gain, 1.0f);
    glUniform4fv(l.rest, n, rest);
    glUniform4fv(l.invR, n, invR);
    glUniform4fv(l.axis, n, axis);
    glUniform4fv(l.mask, n, mask);
    glUniform4fv(l.disp, n, disp);
    glUniform4fv(l.ctr, n, ctr);
}

void SoftDraw::off(unsigned program) {
    glUniform1i(locs(program).n, 0);
}

void SoftDraw::setGain(unsigned program, float gain) {
    glUniform1f(locs(program).gain, gain);
}

} // namespace wc
