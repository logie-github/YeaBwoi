// The body pick's maths (bodypick.h). Pure: no GL, no scene.
#include "bodypick.h"

#include <algorithm>
#include <cmath>

namespace wc {

namespace {

// Möller–Trumbore: the ray's parameter at the triangle (either face), or false.
bool hitTri(const V3& o, const V3& d, const V3& a, const V3& b, const V3& c, float& t) {
    V3 e1 = b - a, e2 = c - a;
    V3 p = d.cross(e2);
    float det = e1.dot(p);
    if (std::fabs(det) < 1e-12f) return false;
    float inv = 1.0f / det;
    V3 s = o - a;
    float u = s.dot(p) * inv;
    if (u < 0 || u > 1) return false;
    V3 q = s.cross(e1);
    float v = d.dot(q) * inv;
    if (v < 0 || u + v > 1) return false;
    t = e2.dot(q) * inv;
    return t > 1e-6f;
}

V3 skinned(const M2Vertex& v, const std::vector<M4>& mats) {
    V3 p(v.pos[0], v.pos[1], v.pos[2]);
    if (mats.empty()) return p;
    V3 out;
    float total = 0;
    for (int k = 0; k < 4; ++k) {
        if (!v.weights[k] || v.bones[k] >= mats.size()) continue;
        float w = v.weights[k] / 255.0f;
        out = out + mats[v.bones[k]].point(p) * w;
        total += w;
    }
    return total > 0 ? out * (1.0f / total) : p;
}

} // namespace

std::vector<uint8_t> boneRegionsFrom(const std::vector<int16_t>& parents, const std::vector<std::pair<int, uint8_t>>& seeds) {
    const size_t n = parents.size();
    constexpr uint8_t kUnset = 255;
    std::vector<uint8_t> r(n, kUnset);
    for (const auto& [i, g] : seeds) if (i >= 0 && size_t(i) < n) r[size_t(i)] = g;
    std::vector<uint8_t> out(n, kRegionNone);
    for (size_t i = 0; i < n; ++i) {
        int b = int(i);
        for (int guard = 0; b >= 0 && size_t(b) < n && guard < 256; ++guard) {
            if (r[size_t(b)] != kUnset) { out[i] = r[size_t(b)]; break; }
            b = parents[size_t(b)];
        }
    }
    return out;
}

uint8_t triangleRegion(const M2Vertex& a, const M2Vertex& b, const M2Vertex& c, const std::vector<uint8_t>& regions, bool hair) {
    if (hair) return kRegionHair;
    float sum[kRegionCount] = {};
    for (const M2Vertex* v : {&a, &b, &c})
        for (int k = 0; k < 4; ++k) {
            if (!v->weights[k]) continue;
            uint8_t g = v->bones[k] < regions.size() ? regions[v->bones[k]] : uint8_t(kRegionNone);
            if (g < kRegionCount) sum[g] += v->weights[k];
        }
    uint8_t best = kRegionNone;
    float most = 0;
    for (uint8_t g = 1; g < kRegionCount; ++g) if (sum[g] > most) { most = sum[g]; best = g; }
    return best;
}

BodyPickHit pickBody(const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices, const std::vector<M2Submesh>& subs,
                     const std::vector<uint16_t>& geosets, const std::vector<M4>& mats, const std::vector<uint8_t>& regions,
                     const V3& o, const V3& d) {
    BodyPickHit hit;
    const bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    std::vector<V3> posed(verts.size());
    std::vector<uint8_t> done(verts.size(), 0);
    auto at = [&](uint32_t i) -> const V3& {
        if (!done[i]) { posed[i] = skinned(verts[i], mats); done[i] = 1; }
        return posed[i];
    };
    float best = 1e30f;
    uint16_t curGeoset = 0;
    auto tryRange = [&](uint32_t start, uint32_t count, bool hair) {
        for (uint32_t t = start; t + 2 < start + count && t + 2 < indices.size(); t += 3) {
            uint32_t a = indices[t], b = indices[t + 1], c = indices[t + 2];
            if (a >= verts.size() || b >= verts.size() || c >= verts.size()) continue;
            float tt;
            if (!hitTri(o, d, at(a), at(b), at(c), tt) || tt >= best) continue;
            uint8_t g = triangleRegion(verts[a], verts[b], verts[c], regions, hair);
            if (g == kRegionNone) continue;
            best = tt;
            hit.region = g; hit.t = tt; hit.point = o + d * tt; hit.geoset = curGeoset;
        }
    };
    if (subs.empty()) tryRange(0, uint32_t(indices.size()), false);
    for (const M2Submesh& sm : subs) {
        if (!all && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        curGeoset = sm.id;
        tryRange(sm.indexStart, sm.indexCount, hairGeoset(sm.id));
    }
    return hit;
}

} // namespace wc
