#include "bodypart.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "bytes.h"

namespace wc {

bool parsePart(const uint8_t* data, size_t size, PartData& out, std::string& error) {
    Reader r(data, size);
    out = PartData();
    if (!r.has(0, 60)) { error = "is too short for a part file"; return false; }
    if (r.u32(0) != kPartMagic) { error = "is not a Wifecraft part file"; return false; }
    uint32_t ver = r.u32(4);
    if (ver != kPartVersion && ver != kPartVersion2) {
        error = "is version " + std::to_string(ver) + "; this build reads " + std::to_string(kPartVersion) + " and " + std::to_string(kPartVersion2);
        return false;
    }
    out.version = ver;
    out.flags = r.u32(8);
    uint32_t v = r.u32(12), ic = r.u32(16), bc = r.u32(20);
    if (bc > 255 || uint64_t(v) * 12 > size || uint64_t(ic) * 4 > size || ic % 3 != 0) { error = "has bad counts"; return false; }
    out.anchorSims = V3(r.f32(24), r.f32(28), r.f32(32));
    out.defaultScale = r.f32(36);
    out.attachOffset = V3(r.f32(40), r.f32(44), r.f32(48));
    out.textureWidth = r.u32(52);
    out.textureHeight = r.u32(56);
    size_t o = 60;
    auto str = [&](std::string& s) {
        if (!r.has(o, 2)) return false;
        uint16_t n = r.u16(o);
        o += 2;
        if (!r.has(o, n)) return false;
        s.assign(reinterpret_cast<const char*>(data + o), n);
        o += n;
        return true;
    };
    if (!str(out.attachBone) || !str(out.id) || !str(out.state) || !str(out.variant)) { error = "ends inside its names"; return false; }
    out.bones.resize(bc);
    for (std::string& b : out.bones) if (!str(b)) { error = "ends inside its bone names"; return false; }
    if (ver == kPartVersion2) {
        if (!r.has(o, 10)) { error = "ends inside its material"; return false; }
        out.textureFileId = r.u32(o);
        out.blend = r.u16(o + 4);
        out.materialFlags = r.u16(o + 6);
        uint16_t rc = r.u16(o + 8);
        o += 10;
        out.refs.resize(rc);
        for (auto& rf : out.refs) {
            if (!str(rf.first) || !r.has(o, 12)) { error = "ends inside its reference bones"; return false; }
            rf.second = V3(r.f32(o), r.f32(o + 4), r.f32(o + 8));
            o += 12;
        }
    }
    size_t need = size_t(v) * 12 + ((out.flags & 1) ? size_t(v) * 12 : 0) + ((out.flags & 2) ? size_t(v) * 8 : 0) +
                  ((out.flags & 4) ? size_t(v) * 8 : 0) + size_t(ic) * 4;
    if (!r.has(o, need)) { error = "is shorter than its counts"; return false; }
    auto floats = [&](std::vector<float>& f, size_t n) {
        f.resize(n);
        if (n) std::memcpy(f.data(), data + o, n * 4);
        o += n * 4;
    };
    floats(out.positions, size_t(v) * 3);
    if (out.flags & 1) floats(out.normals, size_t(v) * 3);
    if (out.flags & 2) floats(out.uvs, size_t(v) * 2);
    if (out.flags & 4) {
        out.boneIndices.assign(data + o, data + o + size_t(v) * 4); o += size_t(v) * 4;
        out.boneWeights.assign(data + o, data + o + size_t(v) * 4); o += size_t(v) * 4;
        for (uint8_t b : out.boneIndices) if (b >= bc) { error = "names bone " + std::to_string(b) + " of " + std::to_string(bc); return false; }
    }
    out.indices.resize(ic);
    if (ic) std::memcpy(out.indices.data(), data + o, size_t(ic) * 4);
    for (uint32_t i : out.indices) if (i >= v) { error = "has index " + std::to_string(i) + " of " + std::to_string(v) + " vertices"; return false; }
    for (float f : out.positions) if (!std::isfinite(f)) { error = "has a position that is not a number"; return false; }
    return true;
}

std::vector<uint8_t> writePart(const PartData& p) {
    std::vector<uint8_t> b;
    auto u32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back(uint8_t(v >> (8 * i))); };
    auto u16 = [&](uint16_t v) { b.push_back(uint8_t(v)); b.push_back(uint8_t(v >> 8)); };
    auto f32 = [&](float f) { uint32_t v; std::memcpy(&v, &f, 4); u32(v); };
    auto str = [&](const std::string& s) { u16(uint16_t(std::min<size_t>(s.size(), 0xFFFF))); b.insert(b.end(), s.begin(), s.begin() + std::min<size_t>(s.size(), 0xFFFF)); };
    bool v2 = p.modelFrame();
    size_t n = p.vertexCount();
    uint32_t flags = p.flags & ~7u;
    if (p.normals.size() == n * 3 && n) flags |= 1;
    if (p.uvs.size() == n * 2 && n) flags |= 2;
    if (p.boneIndices.size() == n * 4 && p.boneWeights.size() == n * 4 && n) flags |= 4;
    u32(kPartMagic); u32(v2 ? kPartVersion2 : kPartVersion); u32(flags); u32(uint32_t(n)); u32(uint32_t(p.indices.size()));
    u32(uint32_t(p.bones.size()));
    f32(p.anchorSims.x); f32(p.anchorSims.y); f32(p.anchorSims.z);
    f32(p.defaultScale);
    f32(p.attachOffset.x); f32(p.attachOffset.y); f32(p.attachOffset.z);
    u32(p.textureWidth); u32(p.textureHeight);
    str(p.attachBone); str(p.id); str(p.state); str(p.variant);
    for (const std::string& s : p.bones) str(s);
    if (v2) {
        u32(p.textureFileId); u16(p.blend); u16(p.materialFlags); u16(uint16_t(p.refs.size()));
        for (const auto& r : p.refs) { str(r.first); f32(r.second.x); f32(r.second.y); f32(r.second.z); }
    }
    for (float f : p.positions) f32(f);
    if (flags & 1) for (float f : p.normals) f32(f);
    if (flags & 2) for (float f : p.uvs) f32(f);
    if (flags & 4) { b.insert(b.end(), p.boneIndices.begin(), p.boneIndices.end()); b.insert(b.end(), p.boneWeights.begin(), p.boneWeights.end()); }
    for (uint32_t i : p.indices) u32(i);
    return b;
}

namespace {

// The lower body's vertices as the actor shows them: its shown geosets (`geosets`; empty or 0xFFFF alone: all) that are the
// body (id 0) or the lower body's group (13xx: the bare legs, trousers and robes' tops); every shown one when none is.
template <typename F> void eachBodyVertex(const M2Model& m, const std::vector<uint16_t>& geosets, F f) {
    bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    auto shown = [&](uint16_t id) { return all || std::find(geosets.begin(), geosets.end(), id) != geosets.end(); };
    bool any = false;
    for (const M2Submesh& sm : m.submeshes) if (sm.indexCount && shown(sm.id) && (sm.id == 0 || sm.id / 100 == 13)) { any = true; break; }
    for (const M2Submesh& sm : m.submeshes) {
        if (!sm.indexCount || !shown(sm.id) || (any && sm.id != 0 && sm.id / 100 != 13)) continue;
        for (uint32_t i = sm.indexStart; i < sm.indexStart + sm.indexCount && i < m.indices.size(); ++i)
            if (m.indices[i] < m.vertices.size()) f(m.vertices[m.indices[i]]);
    }
}

} // namespace

float bodyFrontX(const M2Model& m, const std::vector<uint16_t>& geosets, float z, float halfWidth, float band, float fallback) {
    float best = -1e9f;
    eachBodyVertex(m, geosets, [&](const M2Vertex& v) {
        const float* p = v.pos;
        if (std::fabs(p[1]) <= halfWidth && std::fabs(p[2] - z) <= band && p[0] > best) best = p[0];
    });
    return best > -1e8f ? best : fallback;
}

std::vector<uint8_t> legBones(const M2Model& m) {
    std::vector<uint8_t> leg(m.bones.size(), 0);
    int roots[2] = {m.findWowBone("L_Thigh"), m.findWowBone("R_Thigh")};
    for (size_t i = 0; i < m.bones.size(); ++i) {
        // a thigh, or under one (parents come before their children in the files, but walk up to be sure)
        int b = int(i);
        for (int guard = 0; b >= 0 && guard < 64; ++guard) {
            if (b == roots[0] || b == roots[1]) { leg[i] = 1; break; }
            b = m.bones[size_t(b)].parent;
        }
    }
    return leg;
}

float legWeight(const M2Vertex& v, const std::vector<uint8_t>& leg) {
    int sum = 0, all = 0;
    for (int k = 0; k < 4; ++k) {
        all += v.weights[k];
        if (v.weights[k] && v.bones[k] < leg.size() && leg[v.bones[k]]) sum += v.weights[k];
    }
    return all ? float(sum) / float(all) : 0.0f;
}

float bodyCrotchZ(const M2Model& m, const std::vector<uint16_t>& geosets, float halfWidth, float above, float below, float minX) {
    // the lowest midline vertex the legs don't carry (most of its weight off the thighs and below): where the legs part. Knees
    // that touch (a woman's stance) are on the midline too, but carried by the legs; a body without thighs takes any vertex.
    std::vector<uint8_t> leg = legBones(m);
    bool hasLegs = std::find(leg.begin(), leg.end(), 1) != leg.end();
    float best = 1e9f;
    eachBodyVertex(m, geosets, [&](const M2Vertex& v) {
        const float* p = v.pos;
        if (std::fabs(p[1]) <= halfWidth && p[0] >= minX && p[2] > above && p[2] < below && p[2] < best &&
            (!hasLegs || legWeight(v, leg) < kCrotchLegShare)) best = p[2];
    });
    return best < 1e8f ? best : -1.0f;
}

PartPlacement placeParts(const M2Model& m, const std::vector<uint16_t>& geosets, const PartFit& fit) {
    PartPlacement pl;
    pl.pelvis = m.findWowBone("Pelvis");
    if (pl.pelvis < 0) pl.pelvis = m.findWowBone("Root");
    if (pl.pelvis < 0 || size_t(pl.pelvis) >= m.bones.size()) return pl;
    pl.pivot = m.bones[size_t(pl.pelvis)].pivot;
    float h = pl.pivot.z > 0.05f ? pl.pivot.z : 1.0f;
    pl.scale = h / kPartReferenceHeight * (fit.scale > 0 ? fit.scale : 1.0f);
    pl.crotch = bodyCrotchZ(m, geosets, 0.03f * h, 0.25f * h, 1.05f * h, pl.pivot.x - 0.12f * h);   // (not a tail behind)
    if (pl.crotch < 0) pl.crotch = pl.pivot.z - 0.25f * h;
    // the root: kPartRootShare of the way from where the legs part up to the hip joints (the thighs' pivots), on the body's
    // front: the body's own proportions, whatever its race and build; without thighs, kPartRootAboveCrotch (scaled) above
    int lt = m.findWowBone("L_Thigh"), rt = m.findWowBone("R_Thigh");
    float hip = lt >= 0 && rt >= 0 ? 0.5f * (m.bones[size_t(lt)].pivot.z + m.bones[size_t(rt)].pivot.z) : -1.0f;
    float z = (hip > pl.crotch ? pl.crotch + kPartRootShare * (hip - pl.crotch) : pl.crotch + kPartRootAboveCrotch * pl.scale) + fit.dz * h;
    float front = bodyFrontX(m, geosets, z, 0.03f * h, 0.025f * h, pl.pivot.x + 0.12f * h);
    // no front there (the midline at that height only the back's: a tauren's belly hangs lower): the highest height below it,
    // down to where the legs part, whose midline front stands before the pelvis
    for (float zz = z; front <= pl.pivot.x && zz > pl.crotch; zz -= 0.01f * h) {
        float f = bodyFrontX(m, geosets, zz, 0.03f * h, 0.012f * h, -1e9f);
        if (f > pl.pivot.x) { front = f; z = zz; }
    }
    // the gap between the inner thighs (bind, 15 to 40% down from the hips): the base and scrotum narrowed to fit it
    if (lt >= 0 && rt >= 0) {
        int calves[2] = {m.findWowBone("L_Calf"), m.findWowBone("R_Calf")};
        int thighs[2] = {lt, rt};
        float half = 0;
        int legs = 0;
        for (int side = 0; side < 2; ++side) {
            if (calves[side] < 0) continue;
            V3 hp = m.bones[size_t(thighs[side])].pivot, kp = m.bones[size_t(calves[side])].pivot, ax = (kp - hp).normalized();
            float len = (kp - hp).length(), sum = 0;
            int n = 0;
            for (const M2Vertex& v : m.vertices) {
                int best = -1, bw = 0;
                for (int q = 0; q < 4; ++q) if (v.weights[q] > bw) { bw = v.weights[q]; best = v.bones[q]; }
                if (best != thighs[side]) continue;
                V3 p(v.pos[0], v.pos[1], v.pos[2]);
                float along = (p - hp).dot(ax);
                if (along < 0.15f * len || along > 0.4f * len) continue;
                V3 out = p - hp - ax * along;
                if (out.normalized().y * (hp.y > 0 ? -1.0f : 1.0f) < 0.7f) continue;   // the side toward the other thigh
                sum += out.length(); ++n;
            }
            if (n) { half += std::fabs(hp.y) - sum / float(n); ++legs; }
        }
        if (legs) pl.squeeze = std::clamp((half / float(legs)) / (kPartBaseHalfWidth * pl.scale), kPartSqueezeMin, 1.0f);
    }
    pl.root = V3(front - kPartSink * pl.scale + fit.dx * h, 0.0f, z);
    pl.front = front;
    pl.ok = std::isfinite(pl.root.x) && std::isfinite(pl.root.z) && std::isfinite(pl.scale);
    return pl;
}

std::vector<float> bodySkinUvs(const M2Model& m, const std::vector<uint16_t>& geosets, V3 centre, float radius, size_t maxPoints) {
    std::vector<float> out;
    std::vector<const M2Vertex*> near;
    eachBodyVertex(m, geosets, [&](const M2Vertex& v) {
        V3 d = V3(v.pos[0], v.pos[1], v.pos[2]) - centre;
        if (d.dot(d) <= radius * radius && std::find(near.begin(), near.end(), &v) == near.end()) near.push_back(&v);
    });
    size_t step = near.size() > maxPoints && maxPoints ? (near.size() + maxPoints - 1) / maxPoints : 1;
    for (size_t i = 0; i < near.size(); i += step) { out.push_back(near[i]->uv[0]); out.push_back(near[i]->uv[1]); }
    return out;
}

// ------------------------------------------------------------------------------------------------ the shaft, arousal, insertion

Quat quatMul(const Quat& a, const Quat& b) {
    return Quat(a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
                a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
                a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
                a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z);
}

V3 quatRotate(const Quat& q, const V3& v) {
    V3 u(q.x, q.y, q.z);
    V3 t = u.cross(v) * 2.0f;
    return v + t * q.w + u.cross(t);
}

Quat quatAxisAngle(const V3& axis, float radians) {
    V3 a = axis.normalized();
    float s = std::sin(radians * 0.5f);
    return Quat(a.x * s, a.y * s, a.z * s, std::cos(radians * 0.5f));
}

Quat quatFromTo(const V3& from, const V3& to) {
    V3 f = from.normalized(), t = to.normalized();
    float d = f.dot(t);
    if (d > 0.999999f) return Quat();
    if (d < -0.999999f) {
        V3 side = std::fabs(f.x) < 0.9f ? V3(1, 0, 0).cross(f) : V3(0, 1, 0).cross(f);
        return quatAxisAngle(side, 3.14159265f);
    }
    V3 c = f.cross(t);
    return Quat(c.x, c.y, c.z, 1.0f + d).normalized();
}

float quatAngle(const Quat& q) {
    float w = std::min(1.0f, std::fabs(q.w));
    return 2.0f * std::acos(w);
}

M4 affineInverse(const M4& m) {
    // the 3x3 inverse by cofactors, then the translation
    float a = m.at(0, 0), b = m.at(0, 1), c = m.at(0, 2);
    float d = m.at(1, 0), e = m.at(1, 1), f = m.at(1, 2);
    float g = m.at(2, 0), h = m.at(2, 1), i = m.at(2, 2);
    float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    float det = a * A + b * B + c * C;
    M4 r;
    if (std::fabs(det) < 1e-12f) return r;
    float k = 1.0f / det;
    r.at(0, 0) = A * k;                 r.at(0, 1) = -(b * i - c * h) * k; r.at(0, 2) = (b * f - c * e) * k;
    r.at(1, 0) = B * k;                 r.at(1, 1) = (a * i - c * g) * k;  r.at(1, 2) = -(a * f - c * d) * k;
    r.at(2, 0) = C * k;                 r.at(2, 1) = -(a * h - b * g) * k; r.at(2, 2) = (a * e - b * d) * k;
    V3 t(m.at(0, 3), m.at(1, 3), m.at(2, 3));
    for (int row = 0; row < 3; ++row) r.at(row, 3) = -(r.at(row, 0) * t.x + r.at(row, 1) * t.y + r.at(row, 2) * t.z);
    return r;
}

namespace {
float smooth01(float e0, float e1, float x) {
    float t = std::clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3 - 2 * t);
}
} // namespace

PartShaft measureShaft(const PartData& d, const PartData* other) {
    PartShaft s;
    size_t n = d.vertexCount();
    if (!n || d.modelFrame()) return s;
    s.root = V3(kPartRootX, 0.0f, kPartRootZ);
    // the base and scrotum: the vertices the other state's mesh has too (to half a millimetre)
    std::vector<uint8_t> shared(n, 0);
    if (other && other->vertexCount() && !other->modelFrame()) {
        auto key = [](const V3& p) {
            auto q = [](float v) { return int64_t(std::llround(v * 2000.0)) & 0x1FFFFF; };
            return q(p.x) << 42 | q(p.y) << 21 | q(p.z);
        };
        std::vector<int64_t> keys;
        keys.reserve(other->vertexCount());
        for (size_t v = 0; v < other->vertexCount(); ++v) keys.push_back(key(partSimsPoint(*other, v)));
        std::sort(keys.begin(), keys.end());
        for (size_t v = 0; v < n; ++v) shared[v] = std::binary_search(keys.begin(), keys.end(), key(partSimsPoint(d, v))) ? 1 : 0;
    } else {
        // no other mesh: the shaft is what stands in front of the root
        for (size_t v = 0; v < n; ++v) shared[v] = partSimsPoint(d, v).x < s.root.x - 0.002f ? 1 : 0;
    }
    float best = -1;
    V3 tip;
    for (size_t v = 0; v < n; ++v) {
        if (shared[v]) continue;
        V3 p = partSimsPoint(d, v);
        if (std::fabs(p.y) > 0.03f) continue;
        float l = (p - s.root).length();
        if (l > best) { best = l; tip = p; }
    }
    if (best < 0.02f) return s;
    s.axis = (tip - s.root).normalized();
    s.length = best;
    s.weights.assign(n, 0.0f);
    for (size_t v = 0; v < n; ++v) {
        if (shared[v]) continue;
        float t = (partSimsPoint(d, v) - s.root).dot(s.axis);
        s.weights[v] = smooth01(0.004f, 0.028f, t);
    }
    s.ok = true;
    return s;
}

void posePart(const PartData& d, const PartShaft& s, const PartBend& b, std::vector<M2Vertex>& out) {
    size_t n = d.vertexCount();
    out.resize(n);
    bool bend = s.ok && s.weights.size() == n;
    V3 axisNow = bend ? quatRotate(b.turn, s.axis) : V3(1, 0, 0);
    for (size_t i = 0; i < n; ++i) {
        M2Vertex& o = out[i];
        std::memset(&o, 0, sizeof o);
        V3 p = partSimsPoint(d, i);
        V3 nn = d.normals.size() >= (i + 1) * 3 ? V3(d.normals[i * 3], d.normals[i * 3 + 1], d.normals[i * 3 + 2]) : V3(0, 0, 1);
        if (b.squeeze < 1.0f) p.y *= b.squeeze + (1.0f - b.squeeze) * (bend ? s.weights[i] : 0.0f);
        if (bend && s.weights[i] > 0) {
            float w = s.weights[i];
            Quat q = slerp(Quat(), b.turn, w);
            V3 r = quatRotate(q, p - s.root);
            float along = r.dot(axisNow);
            if (along > 0) r = r + axisNow * (along * (b.stretch - 1.0f) * w);
            p = s.root + r;
            nn = quatRotate(q, nn);
        }
        o.pos[0] = p.x; o.pos[1] = p.y; o.pos[2] = p.z;
        o.normal[0] = nn.x; o.normal[1] = nn.y; o.normal[2] = nn.z;
        if (d.uvs.size() >= (i + 1) * 2) { o.uv[0] = d.uvs[i * 2]; o.uv[1] = d.uvs[i * 2 + 1]; }
    }
}

std::vector<uint32_t> maskTriangles(const std::vector<uint32_t>& indices, const std::vector<uint32_t>& mask) {
    if (mask.empty()) return indices;
    std::vector<uint32_t> sorted = mask;
    std::sort(sorted.begin(), sorted.end());
    auto hidden = [&](uint32_t v) { return std::binary_search(sorted.begin(), sorted.end(), v); };
    std::vector<uint32_t> out;
    out.reserve(indices.size());
    for (size_t t = 0; t + 2 < indices.size(); t += 3)
        if (!hidden(indices[t]) && !hidden(indices[t + 1]) && !hidden(indices[t + 2])) out.insert(out.end(), indices.begin() + long(t), indices.begin() + long(t) + 3);
    return out;
}

float rootSkinWeight(const V3& p) {
    float d = (p - V3(kPartRootX, 0.0f, kPartRootZ)).length();
    return 1.0f - smooth01(kRootSkinFull, kRootSkinEnd, d);
}

float rootShade(const V3& p) {
    float d = (p - V3(kPartRootX, 0.0f, kPartRootZ)).length();
    float x = (d - (kPartSink + 0.008f)) / 0.006f;   // a ring just outside where the shaft leaves the skin
    return 1.0f - kRootShade * std::exp(-x * x);
}

size_t blendRootToSkin(const PartData& d, uint8_t* rgba, int w, int h, uint32_t rgb) {
    size_t n = d.vertexCount();
    if (!rgba || w <= 0 || h <= 0 || d.uvs.size() < n * 2 || d.modelFrame()) return 0;
    std::vector<float> weight(size_t(w) * size_t(h), -1.0f), shade(size_t(w) * size_t(h), 1.0f);
    std::vector<float> vw(n), vs(n);
    for (size_t v = 0; v < n; ++v) { V3 p = partSimsPoint(d, v); vw[v] = rootSkinWeight(p); vs[v] = rootShade(p); }
    for (size_t t = 0; t + 2 < d.indices.size(); t += 3) {
        uint32_t i0 = d.indices[t], i1 = d.indices[t + 1], i2 = d.indices[t + 2];
        if (vw[i0] <= 0 && vw[i1] <= 0 && vw[i2] <= 0 && vs[i0] >= 0.999f && vs[i1] >= 0.999f && vs[i2] >= 0.999f) continue;
        float x0 = d.uvs[i0 * 2] * w - 0.5f, y0 = d.uvs[i0 * 2 + 1] * h - 0.5f;
        float x1 = d.uvs[i1 * 2] * w - 0.5f, y1 = d.uvs[i1 * 2 + 1] * h - 0.5f;
        float x2 = d.uvs[i2 * 2] * w - 0.5f, y2 = d.uvs[i2 * 2 + 1] * h - 0.5f;
        float den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (std::fabs(den) < 1e-9f) continue;
        int minX = std::max(0, int(std::floor(std::min({x0, x1, x2}))) - 1), maxX = std::min(w - 1, int(std::ceil(std::max({x0, x1, x2}))) + 1);
        int minY = std::max(0, int(std::floor(std::min({y0, y1, y2}))) - 1), maxY = std::min(h - 1, int(std::ceil(std::max({y0, y1, y2}))) + 1);
        const float eps = 1.0f / std::max(1.0f, std::fabs(den));   // about a texel round the edges: no seams
        for (int y = minY; y <= maxY; ++y)
            for (int x = minX; x <= maxX; ++x) {
                float a = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / den;
                float b = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / den;
                float c = 1.0f - a - b;
                if (a < -eps || b < -eps || c < -eps) continue;
                a = std::max(a, 0.0f); b = std::max(b, 0.0f); c = std::max(c, 0.0f);
                float s = a + b + c;
                float wt = (a * vw[i0] + b * vw[i1] + c * vw[i2]) / s, sh = (a * vs[i0] + b * vs[i1] + c * vs[i2]) / s;
                size_t k = size_t(y) * size_t(w) + size_t(x);
                if (wt > weight[k]) weight[k] = wt;
                if (sh < shade[k]) shade[k] = sh;
            }
    }
    // each vertex's own texels too (a triangle flat in the texture, a seam's, paints none, yet draws the texels at its corners)
    for (uint32_t v : d.indices) {
        int cx = int(d.uvs[v * 2] * w), cy = int(d.uvs[v * 2 + 1] * h);
        for (int y = cy - 1; y <= cy + 1; ++y)
            for (int x = cx - 1; x <= cx + 1; ++x) {
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                size_t k = size_t(y) * size_t(w) + size_t(x);
                if (vw[v] > weight[k]) weight[k] = vw[v];
                if (weight[k] >= 0 && vs[v] < shade[k]) shade[k] = vs[v];
            }
    }
    float tr = float((rgb >> 16) & 255), tg = float((rgb >> 8) & 255), tb = float(rgb & 255);
    size_t changed = 0;
    for (size_t k = 0; k < weight.size(); ++k) {
        if (weight[k] < 0) continue;
        float wt = std::clamp(weight[k], 0.0f, 1.0f), sh = shade[k];
        uint8_t* px = rgba + k * 4;
        float o[3] = {float(px[0]), float(px[1]), float(px[2])}, t3[3] = {tr, tg, tb};
        for (int c = 0; c < 3; ++c) px[c] = uint8_t(std::clamp((o[c] + (t3[c] - o[c]) * wt) * sh + 0.5f, 0.0f, 255.0f));
        ++changed;
    }
    return changed;
}

float easeLevel(float level) {
    float t = std::clamp(std::isfinite(level) ? level : 0.0f, 0.0f, 1.0f);
    return t * t * (3 - 2 * t);
}

bool levelSlotOn(float level, bool wasOn) {
    return wasOn ? level > kPartSwapDown : level >= kPartSwapUp;
}

namespace {
// the pitch and length the level draws
void levelShape(float level, const PartShaft& flaccid, const PartShaft& erect, float hang, float& pitch, float& length) {
    float u = easeLevel(level);
    float pf = flaccid.ok ? flaccid.pitch() : -1.2f, pe = std::max(erect.ok ? erect.pitch() : 0.05f, kErectPitchMin);
    float lf = flaccid.ok ? flaccid.length : 0.1f, le = erect.ok ? erect.length : lf * 1.5f;
    pitch = pf + (pe - pf) * u + hang * (1.0f - u);
    length = lf + (le - lf) * u;
}
} // namespace

PartBend levelBend(float level, bool slotOn, const PartShaft& flaccid, const PartShaft& erect, float hang) {
    PartBend b;
    const PartShaft& mesh = slotOn ? erect : flaccid;
    if (!mesh.ok) return b;
    float pitch, length;
    levelShape(level, flaccid, erect, hang, pitch, length);
    // a turn about y by -delta raises the pitch (atan2(z, x)) by delta
    b.turn = quatAxisAngle(V3(0, 1, 0), -(pitch - mesh.pitch()));
    b.stretch = mesh.length > 1e-4f ? std::clamp(length / mesh.length, 0.5f, 2.0f) : 1.0f;
    return b;
}

V3 levelAxis(float level, const PartShaft& flaccid, const PartShaft& erect, float hang) {
    float pitch, length;
    levelShape(level, flaccid, erect, hang, pitch, length);
    return V3(std::cos(pitch), 0.0f, std::sin(pitch));
}

float insertionDepth(float length, float distance, float rest) {
    if (!(length > 0) || !std::isfinite(distance)) return 0.0f;
    return std::clamp(length - distance + rest, 0.0f, length);
}

Quat stepAim(const Quat& current, const Quat& target, float maxRadians) {
    Quat inv(-current.x, -current.y, -current.z, current.w);
    float gap = quatAngle(quatMul(inv, target));
    if (gap <= maxRadians || gap < 1e-5f) return target;
    return slerp(current, target, std::clamp(maxRadians / gap, 0.0f, 1.0f));
}

float thighPitch(const M2Model& m, const std::vector<M4>& mats, int pelvis) {
    if (pelvis < 0 || size_t(pelvis) >= mats.size() || size_t(pelvis) >= m.bones.size()) return 0.0f;
    M4 back = affineInverse(mats[size_t(pelvis)]);
    float sum = 0;
    int count = 0;
    const char* names[2][2] = {{"L_Thigh", "L_Calf"}, {"R_Thigh", "R_Calf"}};
    for (auto& leg : names) {
        int t = m.findWowBone(leg[0]), c = m.findWowBone(leg[1]);
        if (t < 0 || c < 0 || size_t(t) >= mats.size() || size_t(c) >= mats.size()) continue;
        V3 bt = m.bones[size_t(t)].pivot, bc = m.bones[size_t(c)].pivot;
        V3 pt = back.point(mats[size_t(t)].point(bt)), pc = back.point(mats[size_t(c)].point(bc));
        V3 bind = bc - bt, now = pc - pt;
        float a0 = std::atan2(bind.z, bind.x), a1 = std::atan2(now.z, now.x);
        float d = a1 - a0;
        while (d > 3.14159265f) d -= 6.2831853f;
        while (d < -3.14159265f) d += 6.2831853f;
        sum += d;
        ++count;
    }
    return count ? sum / float(count) : 0.0f;
}

float bodyBackX(const M2Model& m, const std::vector<uint16_t>& geosets, float z, float halfWidth, float band, float fallback) {
    float best = 1e9f;
    eachBodyVertex(m, geosets, [&](const M2Vertex& v) {
        const float* p = v.pos;
        if (std::fabs(p[1]) <= halfWidth && std::fabs(p[2] - z) <= band && p[0] < best) best = p[0];
    });
    return best < 1e8f ? best : fallback;
}

EntryAnchors measureEntries(const M2Model& m, const std::vector<uint16_t>& geosets, bool female) {
    EntryAnchors e;
    e.measured = true;
    int pelvis = m.findWowBone("Pelvis");
    if (pelvis < 0) pelvis = m.findWowBone("Root");
    if (pelvis < 0 || size_t(pelvis) >= m.bones.size()) return e;
    V3 pv = m.bones[size_t(pelvis)].pivot;
    float h = pv.z > 0.05f ? pv.z : 1.0f;
    // where the legs part on the midline, and that vertex's depth
    float crotch = 1e9f, crotchX = pv.x;
    float minX = pv.x - 0.12f * h;
    std::vector<uint8_t> leg = legBones(m);
    bool hasLegs = std::find(leg.begin(), leg.end(), 1) != leg.end();
    eachBodyVertex(m, geosets, [&](const M2Vertex& v) {
        const float* p = v.pos;
        if (std::fabs(p[1]) <= 0.03f * h && p[0] >= minX && p[2] > 0.25f * h && p[2] < 1.05f * h && p[2] < crotch &&
            (!hasLegs || legWeight(v, leg) < kCrotchLegShare)) { crotch = p[2]; crotchX = p[0]; }
    });
    if (crotch > 1e8f) { crotch = pv.z - 0.25f * h; crotchX = pv.x; }
    e.crotch = crotch;
    e.front = bodyFrontX(m, geosets, crotch + 0.05f * h, 0.03f * h, 0.025f * h, pv.x + 0.12f * h);
    e.back = bodyBackX(m, geosets, crotch + 0.07f * h, 0.03f * h, 0.025f * h, pv.x - 0.12f * h);
    auto set = [&](int k, int bone, V3 p, V3 in, float r) {
        EntryAnchor& a = e.at[k];
        a.ok = true; a.bone = bone; a.point = p; a.inward = in.normalized(); a.radius = r;
    };
    if (female) set(kEntryVaginal, pelvis, V3(crotchX + (e.front - crotchX) * 0.45f, 0.0f, crotch + 0.025f * h), V3(-0.35f, 0, 1), 0.02f * h);
    set(kEntryAnal, pelvis, V3(crotchX + (e.back - crotchX) * 0.55f, 0.0f, crotch + 0.05f * h), V3(0.45f, 0, 0.9f), 0.015f * h);
    set(kEntryThigh, pelvis, V3(crotchX + 0.02f * h, 0.0f, crotch - 0.06f * h), V3(-1, 0, 0), 0.03f * h);
    // between the breasts: the front surface on the midline at the breasts' height (their bones, else below the chest's)
    int chest = m.findWowBone("Chest");
    if (chest < 0) chest = m.findWowBone("Spine2");
    if (chest >= 0) {
        int lb = m.findWowBone("L_Breast"), rb = m.findWowBone("R_Breast");
        float z = lb >= 0 && rb >= 0 ? 0.5f * (m.bones[size_t(lb)].pivot.z + m.bones[size_t(rb)].pivot.z) : m.bones[size_t(chest)].pivot.z - 0.05f * h;
        float fx = -1e9f;
        for (const M2Vertex& v : m.vertices) if (std::fabs(v.pos[1]) <= 0.015f * h && std::fabs(v.pos[2] - z) <= 0.03f * h && v.pos[0] > fx) fx = v.pos[0];
        if (fx > -1e8f) set(kEntryChest, chest, V3(fx, 0.0f, z), V3(0, 0, -1), 0.04f * h);
    }
    // the mouth: the Breath attachment (17) where the model has it, else in front of the head
    int breath = 17;
    int at = breath < int(m.attachmentLookup.size()) ? m.attachmentLookup[size_t(breath)] : -1;
    if (at >= 0 && size_t(at) < m.attachments.size() && m.attachments[size_t(at)].bone < m.bones.size()) {
        const M2Attachment& a = m.attachments[size_t(at)];
        set(kEntryOral, int(a.bone), a.position, V3(-1, 0, 0), 0.012f * h);
    } else {
        int head = m.findWowBone("Head");
        if (head >= 0) {
            V3 hp = m.bones[size_t(head)].pivot;
            set(kEntryOral, head, V3(hp.x + 0.08f * h, 0.0f, hp.z - 0.02f * h), V3(-1, 0, 0), 0.012f * h);
        }
    }
    return e;
}

M4 partMatrix(const M4& pelvisBone, const PartPlacement& pl) {
    return pelvisBone * M4::translation(pl.root) * M4::scaling(V3(pl.scale, pl.scale, pl.scale)) *
           M4::translation(V3(-kPartRootX, 0.0f, -kPartRootZ));
}

} // namespace wc
