#include "stockings.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <tuple>

#include "bodypick.h"

namespace wc {

namespace {
constexpr float kPi = 3.14159265f;
V3 P(const M2Vertex& v) { return V3(v.pos[0], v.pos[1], v.pos[2]); }
V3 N(const M2Vertex& v) { return V3(v.normal[0], v.normal[1], v.normal[2]); }

// the polygon of triangle `t` below the plane z = top (Sutherland-Hodgman against one plane), as vertices carrying every attribute
struct Corner { M2Vertex v; uint32_t src; };

Corner mix(const Corner& a, const Corner& b, float t) {
    Corner c = (t < 0.5f) ? a : b;   // the weights and bones of the nearer corner
    for (int k = 0; k < 3; ++k) { c.v.pos[k] = a.v.pos[k] + (b.v.pos[k] - a.v.pos[k]) * t; c.v.normal[k] = a.v.normal[k] + (b.v.normal[k] - a.v.normal[k]) * t; }
    for (int k = 0; k < 2; ++k) { c.v.uv[k] = a.v.uv[k] + (b.v.uv[k] - a.v.uv[k]) * t; c.v.uv2[k] = a.v.uv2[k] + (b.v.uv2[k] - a.v.uv2[k]) * t; }
    return c;
}
} // namespace

float denierAlpha(int d) {
    d = std::max(5, std::min(100, d));
    // 8 D: nearly bare (0.16), 20 D: sheer (0.38), 40 D: semi (0.66), 80 D: opaque (0.96)
    float t = (float(d) - 5.0f) / 75.0f;
    return std::min(0.98f, 0.10f + 0.88f * std::sqrt(std::max(0.0f, t)));
}

const std::vector<StockColour>& stockColours() {
    static const std::vector<StockColour> c = {
        {"Ink black", 0.04f, 0.04f, 0.05f}, {"Nude", 0.86f, 0.66f, 0.55f}, {"Tan", 0.72f, 0.5f, 0.35f}, {"Deep tan", 0.5f, 0.33f, 0.22f},
        {"White", 0.95f, 0.95f, 0.97f}, {"Red", 0.78f, 0.06f, 0.1f}, {"Navy", 0.05f, 0.09f, 0.3f}};
    return c;
}

StockExtent stockExtent(int style, float floorZ, float ankleZ, float kneeZ, float pelvisZ) {
    StockExtent e;
    e.bottom = floorZ - 0.02f;
    const float thigh = kneeZ + 0.78f * (pelvisZ - kneeZ);
    switch (style) {
        case kStockAnkle: e.top = ankleZ + 0.05f * (kneeZ - ankleZ); e.bandHeight = 0.0f; break;
        case kStockKnee: e.top = kneeZ - 0.02f * (kneeZ - ankleZ); e.bandHeight = 0.05f * (kneeZ - ankleZ); break;
        case kStockThigh: e.top = thigh; e.bandHeight = 0.10f * (pelvisZ - kneeZ); e.slope = 0.06f; break;
        default: e.top = pelvisZ + 0.14f * (pelvisZ - kneeZ); e.bandHeight = 0.055f * (pelvisZ - kneeZ); e.slope = 0.16f; break;
    }
    return e;
}

bool buildStockings(const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices, const std::vector<M2Submesh>& subs,
                    const std::vector<uint16_t>& geosets, const std::vector<uint8_t>& regions, const StockSpec& spec,
                    float floorZ, float ankleZ, float kneeZ, float pelvisZ, StockShell& out) {
    out = StockShell();
    if (verts.empty() || indices.size() < 3 || !(pelvisZ > kneeZ) || !(kneeZ > floorZ)) return false;
    const StockExtent ex = stockExtent(spec.style, floorZ, ankleZ, kneeZ, pelvisZ);
    out.extent = ex;
    // which body vertex is a leg's (a foot's) and, for tights, a hip's: the region of its heaviest bone
    std::vector<uint8_t> region(verts.size(), kRegionNone);
    for (size_t i = 0; i < verts.size(); ++i) {
        float best = -1; uint8_t r = kRegionNone;
        for (int k = 0; k < 4; ++k) {
            if (!verts[i].weights[k]) continue;
            if (float(verts[i].weights[k]) > best) { best = verts[i].weights[k]; r = verts[i].bones[k] < regions.size() ? regions[verts[i].bones[k]] : uint8_t(kRegionNone); }
        }
        region[i] = r;
    }
    const float slope = ex.slope;
    auto hOf = [&](const M2Vertex& v) { return v.pos[2] - slope * v.pos[0]; };
    auto wanted = [&](uint32_t i) {
        uint8_t r = region[i];
        if (r == kRegionLegs || r == kRegionFeet) return true;
        // (the upper thigh is weighted to the pelvis, a tights' top reaches the waist's own bone: the clip keeps only what the style reaches)
        return r == kRegionHips || (r == kRegionWaist && spec.style == kStockTights);
    };
    // the triangles of the shown submeshes
    std::vector<std::pair<uint32_t, uint32_t>> ranges;
    const bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    if (subs.empty()) ranges.push_back({0, uint32_t(indices.size())});
    for (const M2Submesh& sm : subs) {
        if (!all && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        // the hair and the other non-body groups carry no leg vertices; a skirt or a boot's geoset would, so keep only the body's own (group 0 or the legs' base)
        ranges.push_back({sm.indexStart, sm.indexCount});
    }
    // the leg's centre by side, to turn the UV round it
    double cy[2] = {0, 0}, cx[2] = {0, 0}; int cn[2] = {0, 0};
    for (size_t i = 0; i < verts.size(); ++i) {
        if (region[i] != kRegionLegs || verts[i].pos[2] < ex.bottom + 0.1f || verts[i].pos[2] > kneeZ) continue;
        int s = verts[i].pos[1] >= 0 ? 0 : 1;
        cy[s] += verts[i].pos[1]; cx[s] += verts[i].pos[0]; ++cn[s];
    }
    float legY[2], legX[2];
    for (int s = 0; s < 2; ++s) { legY[s] = cn[s] ? float(cy[s] / cn[s]) : (s ? -0.09f : 0.09f); legX[s] = cn[s] ? float(cx[s] / cn[s]) : 0.0f; }
    const float bandBottom = ex.top - ex.bandHeight;
    std::vector<uint32_t> weave, band;
    out.minGap = 1e9f; out.maxGap = -1e9f;
    auto emit = [&](const std::vector<Corner>& poly) {
        if (poly.size() < 3) return;
        const uint32_t base = uint32_t(out.verts.size());
        float cz = 0;
        for (const Corner& c : poly) cz += hOf(c.v);
        cz /= float(poly.size());
        const bool inBand = ex.bandHeight > 0 && cz >= bandBottom;
        std::vector<float> us(poly.size());
        for (size_t i = 0; i < poly.size(); ++i) {
            V3 p = P(poly[i].v) + N(poly[i].v).normalized() * spec.offset;
            int s = p.y >= 0 ? 0 : 1;
            us[i] = std::atan2(p.y - legY[s], p.x - legX[s]) / (2.0f * kPi) + 0.5f;   // 0 and 1 at the back
        }
        // a polygon that straddles the back is unwrapped (the texture repeats)
        float umin = *std::min_element(us.begin(), us.end()), umax = *std::max_element(us.begin(), us.end());
        if (umax - umin > 0.5f) for (float& u : us) if (u < 0.5f) u += 1.0f;
        for (size_t i = 0; i < poly.size(); ++i) {
            const Corner& c = poly[i];
            M2Vertex v = c.v;
            V3 n = N(v).normalized();
            V3 p = P(v) + n * spec.offset;
            out.minGap = std::min(out.minGap, spec.offset); out.maxGap = std::max(out.maxGap, spec.offset);
            v.pos[0] = p.x; v.pos[1] = p.y; v.pos[2] = p.z;
            v.normal[0] = n.x; v.normal[1] = n.y; v.normal[2] = n.z;
            v.uv[0] = us[i];
            v.uv[1] = inBand ? std::max(0.0f, std::min(1.0f, (ex.top - (p.z - slope * p.x)) / std::max(ex.bandHeight, 1e-4f))) : p.z / 0.045f;
            v.uv2[0] = inBand ? 1.0f : 0.0f; v.uv2[1] = 0;
            out.verts.push_back(v);
            out.source.push_back(c.src);
        }
        std::vector<uint32_t>& dst = inBand ? band : weave;
        for (size_t k = 1; k + 1 < poly.size(); ++k) { dst.push_back(base); dst.push_back(base + uint32_t(k)); dst.push_back(base + uint32_t(k + 1)); }
    };
    // a vertex's shell index would be shared in a finished mesh; here each clipped triangle is its own (3-4 vertices): ~3x the legs' triangles, fine for a few thousand
    for (auto [start, count] : ranges) {
        for (uint32_t t = start; t + 2 < start + count && t + 2 < indices.size(); t += 3) {
            uint32_t ix[3] = {indices[t], indices[t + 1], indices[t + 2]};
            if (ix[0] >= verts.size() || ix[1] >= verts.size() || ix[2] >= verts.size()) continue;
            if (!(wanted(ix[0]) && wanted(ix[1]) && wanted(ix[2]))) continue;
            float zmin = std::min({hOf(verts[ix[0]]), hOf(verts[ix[1]]), hOf(verts[ix[2]])});
            if (zmin > ex.top) continue;
            // clip at the top plane
            std::vector<Corner> in = {{verts[ix[0]], ix[0]}, {verts[ix[1]], ix[1]}, {verts[ix[2]], ix[2]}}, poly;
            for (size_t i = 0; i < in.size(); ++i) {
                const Corner& a = in[i]; const Corner& b = in[(i + 1) % in.size()];
                const float ha = hOf(a.v), hb = hOf(b.v);
                bool ai = ha <= ex.top, bi = hb <= ex.top;
                if (ai) poly.push_back(a);
                if (ai != bi) { float tt = (ex.top - ha) / (hb - ha); poly.push_back(mix(a, b, tt)); }
            }
            // a triangle crossing the band's lower edge is split there too so the band is a clean ring
            if (ex.bandHeight > 0) {
                std::vector<Corner> lo, hi;
                for (size_t i = 0; i < poly.size(); ++i) {
                    const Corner& a = poly[i]; const Corner& b = poly[(i + 1) % poly.size()];
                    const float ha = hOf(a.v), hb = hOf(b.v);
                    bool ah = ha >= bandBottom, bh = hb >= bandBottom;
                    if (ah) hi.push_back(a); else lo.push_back(a);
                    if (ah != bh) { float tt = (bandBottom - ha) / (hb - ha); Corner m = mix(a, b, tt); hi.push_back(m); lo.push_back(m); }
                }
                emit(hi); emit(lo);
            } else emit(poly);
        }
    }
    if (weave.empty() && band.empty()) return false;
    out.indices = weave; out.bandStart = uint32_t(out.indices.size());
    out.indices.insert(out.indices.end(), band.begin(), band.end());
    return true;
}

void stockWeld(const StockShell& s, std::vector<uint32_t>& weld, std::vector<V3>& unique, std::vector<uint32_t>& tris) {
    weld.assign(s.verts.size(), 0); unique.clear(); tris.clear();
    std::map<std::tuple<int64_t, int64_t, int64_t>, uint32_t> at;
    for (size_t i = 0; i < s.verts.size(); ++i) {
        const float* p = s.verts[i].pos;
        auto k = std::make_tuple(int64_t(std::llround(p[0] * 20000.0f)), int64_t(std::llround(p[1] * 20000.0f)), int64_t(std::llround(p[2] * 20000.0f)));
        auto f = at.find(k);
        if (f == at.end()) { f = at.emplace(k, uint32_t(unique.size())).first; unique.push_back(V3(p[0], p[1], p[2])); }
        weld[i] = f->second;
    }
    for (uint32_t i : s.indices) tris.push_back(weld[i]);
}

namespace {
uint32_t hash2(int x, int y) { uint32_t h = uint32_t(x) * 374761393u + uint32_t(y) * 668265263u; h = (h ^ (h >> 13)) * 1274126177u; return h ^ (h >> 16); }
}

std::vector<uint8_t> stockingWeave(const StockSpec& spec, int size) {
    std::vector<uint8_t> px(size_t(size) * size * 4);
    const float base = denierAlpha(spec.denier);
    const int cells = 64;    // knit cells across the texture
    for (int y = 0; y < size; ++y) for (int x = 0; x < size; ++x) {
        float fx = float(x) / size * cells, fy = float(y) / size * cells;
        float cx = fx - std::floor(fx) - 0.5f, cy = fy - std::floor(fy) - 0.5f;
        // a knit: two interlocking diagonal threads per cell, brighter where they cross, a hole between
        float t1 = 1.0f - std::min(1.0f, std::fabs(cx + cy) * 2.2f), t2 = 1.0f - std::min(1.0f, std::fabs(cx - cy) * 2.2f);
        float thread = std::max(t1, t2);
        float jitter = ((hash2(int(fx), int(fy)) & 255) / 255.0f - 0.5f) * 0.08f;
        float a = base * (0.62f + 0.55f * thread) + jitter * base;
        a = std::max(0.02f, std::min(0.99f, a));
        float shade = 0.82f + 0.3f * thread;
        uint8_t* o = &px[(size_t(y) * size + x) * 4];
        o[0] = uint8_t(std::min(1.0f, spec.r * shade) * 255); o[1] = uint8_t(std::min(1.0f, spec.g * shade) * 255); o[2] = uint8_t(std::min(1.0f, spec.b * shade) * 255);
        o[3] = uint8_t(a * 255);
        // the seam: the column at u = 0 (the back), a darker, denser line a few pixels wide
        if (spec.seam && (x < size / 90 + 1 || x >= size - size / 90)) { o[0] = uint8_t(o[0] * 0.45f); o[1] = uint8_t(o[1] * 0.45f); o[2] = uint8_t(o[2] * 0.45f); o[3] = uint8_t(std::min(255.0f, o[3] + 120.0f)); }
    }
    return px;
}

std::vector<uint8_t> stockingBand(const StockSpec& spec, int size, bool elastic) {
    std::vector<uint8_t> px(size_t(size) * size * 4);
    const float base = std::min(0.98f, denierAlpha(spec.denier) + 0.3f);
    for (int y = 0; y < size; ++y) for (int x = 0; x < size; ++x) {
        float u = float(x) / size, v = float(y) / size;   // v 0 at the top edge, 1 where the band meets the weave
        float a = base, shade = 0.85f;
        if (spec.lace && !elastic) {
            // scallops along the top edge and a floral mesh below: holes in a repeating pattern
            float sx = std::fmod(u * 24.0f, 1.0f) - 0.5f;
            float edge = 0.12f + 0.12f * std::sqrt(std::max(0.0f, 1.0f - 4.0f * sx * sx));
            if (v < edge) a = 0.0f;
            else {
                float cx = std::fmod(u * 36.0f, 1.0f) - 0.5f, cy = std::fmod(v * 4.0f, 1.0f) - 0.5f;
                float hole = std::sqrt(cx * cx + cy * cy);
                a = (hole < 0.22f) ? base * 0.35f : (hole < 0.3f ? 1.0f : base);
                shade = (hole < 0.3f && hole >= 0.22f) ? 1.1f : 0.85f;
            }
        } else {
            // plain elastic: a fine rib, a slightly darker, denser band
            float rib = 0.5f + 0.5f * std::sin(u * 3.14159f * 2.0f * 90.0f);
            a = elastic ? 0.97f : std::min(1.0f, base + 0.15f * rib); shade = 0.7f + 0.2f * rib;
            if (v < 0.08f) shade *= 1.25f;   // a faint highlight along the edge
        }
        uint8_t* o = &px[(size_t(y) * size + x) * 4];
        o[0] = uint8_t(std::min(1.0f, spec.r * shade) * 255); o[1] = uint8_t(std::min(1.0f, spec.g * shade) * 255); o[2] = uint8_t(std::min(1.0f, spec.b * shade) * 255);
        o[3] = uint8_t(std::max(0.0f, std::min(1.0f, a)) * 255);
    }
    return px;
}

} // namespace wc
