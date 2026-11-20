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

float bodyCrotchZ(const M2Model& m, const std::vector<uint16_t>& geosets, float halfWidth, float above, float below, float minX) {
    float best = 1e9f;
    eachBodyVertex(m, geosets, [&](const M2Vertex& v) {
        const float* p = v.pos;
        if (std::fabs(p[1]) <= halfWidth && p[0] >= minX && p[2] > above && p[2] < below && p[2] < best) best = p[2];
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
    float z = pl.crotch + kPartRootAboveCrotch * pl.scale + fit.dz * h;
    float front = bodyFrontX(m, geosets, z, 0.03f * h, 0.025f * h, pl.pivot.x + 0.12f * h);
    pl.root = V3(front + fit.dx * h, 0.0f, z);
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

M4 partMatrix(const M4& pelvisBone, const PartPlacement& pl) {
    return pelvisBone * M4::translation(pl.root) * M4::scaling(V3(pl.scale, pl.scale, pl.scale)) *
           M4::translation(V3(-kPartRootX, 0.0f, -kPartRootZ));
}

} // namespace wc
