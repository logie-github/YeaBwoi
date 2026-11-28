// Tessellation of the soft regions: see tessellate.h.
#include "tessellate.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <unordered_map>

#include "wmath.h"

namespace wc {

namespace {
V3 posOf(const M2Vertex& v) { return V3(v.pos[0], v.pos[1], v.pos[2]); }
V3 nrmOf(const M2Vertex& v) { return V3(v.normal[0], v.normal[1], v.normal[2]); }
uint64_t edgeKey(uint32_t a, uint32_t b) { return a < b ? (uint64_t(a) << 32) | b : (uint64_t(b) << 32) | a; }

struct Weld {   // the normals of all vertices at one place, summed
    std::unordered_map<uint64_t, V3> sum;
    float cell = 1e-4f;
    uint64_t key(const V3& p) const {
        auto q = [&](float v) { return uint64_t(int64_t(std::llround(double(v) / double(cell))) & 0x1FFFFF); };
        return (q(p.x) << 42) | (q(p.y) << 21) | q(p.z);
    }
    void add(const M2Vertex& v) { sum[key(posOf(v))] = sum[key(posOf(v))] + nrmOf(v); }
    V3 at(const V3& p, const V3& fallback) const {
        auto it = sum.find(key(p));
        if (it == sum.end() || it->second.length() < 1e-6f) return fallback;
        return it->second.normalized();
    }
};

M2Vertex midVertex(const M2Vertex& a, const M2Vertex& b, const Weld& weld, float curve) {
    M2Vertex m;
    const V3 pa = posOf(a), pb = posOf(b);
    V3 na = weld.at(pa, nrmOf(a)), nb = weld.at(pb, nrmOf(b));
    V3 mid = (pa + pb) * 0.5f;
    if (curve > 0) {
        V3 d = (na * (pb - pa).dot(na) + nb * (pa - pb).dot(nb)) * (-0.125f * curve);
        float len = (pb - pa).length(), dl = d.length();
        if (dl > 0.25f * len && dl > 1e-9f) d = d * (0.25f * len / dl);
        mid = mid + d;
    }
    m.pos[0] = mid.x; m.pos[1] = mid.y; m.pos[2] = mid.z;
    V3 n = (nrmOf(a) + nrmOf(b)).normalized();
    m.normal[0] = n.x; m.normal[1] = n.y; m.normal[2] = n.z;
    for (int i = 0; i < 2; ++i) { m.uv[i] = 0.5f * (a.uv[i] + b.uv[i]); m.uv2[i] = 0.5f * (a.uv2[i] + b.uv2[i]); }
    // skin weights: the ends' influences merged (half each), the four strongest kept, normalised to 255
    uint8_t bone[8]; float w[8]; int n8 = 0;
    auto put = [&](uint8_t bi, float wt) {
        if (wt <= 0) return;
        for (int i = 0; i < n8; ++i) if (bone[i] == bi) { w[i] += wt; return; }
        bone[n8] = bi; w[n8] = wt; ++n8;
    };
    for (int k = 0; k < 4; ++k) { put(a.bones[k], a.weights[k] * 0.5f); put(b.bones[k], b.weights[k] * 0.5f); }
    float fw[4] = {0, 0, 0, 0};
    float total = 0;
    for (int k = 0; k < 4; ++k) { m.bones[k] = 0; m.weights[k] = 0; }
    for (int k = 0; k < 4 && n8 > 0; ++k) {
        int best = 0;
        for (int i = 1; i < n8; ++i) if (w[i] > w[best]) best = i;
        m.bones[k] = bone[best]; fw[k] = w[best]; total += w[best];
        w[best] = w[n8 - 1]; bone[best] = bone[n8 - 1]; --n8;
    }
    if (total > 0) {
        int acc = 0, top = 0;
        for (int k = 0; k < 4; ++k) {
            m.weights[k] = uint8_t(std::lround(fw[k] * 255.0f / total));
            acc += m.weights[k];
            if (m.weights[k] > m.weights[top]) top = k;
        }
        m.weights[top] = uint8_t(int(m.weights[top]) + (255 - acc));
    }
    return m;
}

float percentile(std::vector<float>& v, float q) {
    if (v.empty()) return 0;
    size_t k = std::min(v.size() - 1, size_t(q * float(v.size())));
    std::nth_element(v.begin(), v.begin() + long(k), v.end());
    return v[k];
}
} // namespace

size_t tessellateSoftRegions(std::vector<M2Vertex>& verts, std::vector<uint32_t>& indices, std::vector<TessRange>& ranges,
                             std::vector<float>& soft, float maxEdge, size_t cap, TessStats* stats, float curve, float threshold) {
    const auto t0 = std::chrono::steady_clock::now();
    TessStats st;
    st.verticesBefore = verts.size(); st.trianglesBefore = indices.size() / 3;
    soft.resize(verts.size(), 0.0f);
    if (maxEdge <= 0 || cap == 0 || indices.size() < 3) { if (stats) { st.verticesAfter = verts.size(); st.trianglesAfter = indices.size() / 3; *stats = st; } return 0; }
    Weld weld;
    // (a cell well under the shortest edge that matters)
    weld.cell = std::max(maxEdge * 0.002f, 1e-6f);
    for (const M2Vertex& v : verts) weld.add(v);
    auto qualifies = [&](uint32_t a, uint32_t b) { return 0.5f * (soft[a] + soft[b]) >= threshold; };
    auto lengthOf = [&](uint32_t a, uint32_t b) { return (posOf(verts[a]) - posOf(verts[b])).length(); };
    // ranges in order of their start
    std::vector<size_t> order(ranges.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t x, size_t y) { return ranges[x].start < ranges[y].start; });
    auto measure = [&](float* p50, float* p99, size_t* over) {
        std::vector<float> e;
        size_t n = 0;
        for (size_t i = 0; i + 2 < indices.size(); i += 3)
            for (int c = 0; c < 3; ++c) {
                uint32_t a = indices[i + size_t(c)], b = indices[i + size_t((c + 1) % 3)];
                if (a < b && qualifies(a, b)) { float l = lengthOf(a, b); e.push_back(l); if (l > maxEdge) ++n; }
            }
        *p50 = percentile(e, 0.5f); *p99 = percentile(e, 0.99f); *over = n;
    };
    size_t over0 = 0;
    measure(&st.edgeP50Before, &st.edgeP99Before, &over0);
    size_t added = 0;
    for (int pass = 0; pass < 8; ++pass) {
        // the edges to split this pass
        std::unordered_map<uint64_t, float> cand;
        for (size_t i = 0; i + 2 < indices.size(); i += 3)
            for (int c = 0; c < 3; ++c) {
                uint32_t a = indices[i + size_t(c)], b = indices[i + size_t((c + 1) % 3)];
                if (!qualifies(a, b)) continue;
                float l = lengthOf(a, b);
                if (l > maxEdge) cand[edgeKey(a, b)] = l;
            }
        if (cand.empty()) break;
        std::vector<std::pair<float, uint64_t>> byLen;
        byLen.reserve(cand.size());
        for (auto& [k, l] : cand) byLen.push_back({l, k});
        std::sort(byLen.begin(), byLen.end(), [](auto& x, auto& y) { return x.first != y.first ? x.first > y.first : x.second < y.second; });
        size_t room = cap > added ? cap - added : 0;
        if (byLen.size() > room) { byLen.resize(room); st.capped = true; }
        if (byLen.empty()) break;
        std::unordered_map<uint64_t, uint32_t> mid;
        mid.reserve(byLen.size() * 2);
        for (auto& [l, k] : byLen) {
            uint32_t a = uint32_t(k >> 32), b = uint32_t(k & 0xFFFFFFFFu);
            M2Vertex m = midVertex(verts[a], verts[b], weld, curve);
            mid[k] = uint32_t(verts.size());
            verts.push_back(m);
            soft.push_back(0.5f * (soft[a] + soft[b]));
        }
        added += byLen.size();
        // the triangles re-cut, range by range
        std::vector<uint32_t> out;
        out.reserve(indices.size() + byLen.size() * 6);
        std::vector<TessRange> nr = ranges;
        auto cut = [&](size_t from, size_t to) {
            for (size_t i = from; i + 3 <= to; i += 3) {
                uint32_t v[3] = {indices[i], indices[i + 1], indices[i + 2]};
                uint32_t m[3] = {0, 0, 0};
                int marked = 0, bits = 0;
                for (int c = 0; c < 3; ++c) {
                    auto it = mid.find(edgeKey(v[c], v[(c + 1) % 3]));
                    if (it != mid.end()) { m[c] = it->second; ++marked; bits |= 1 << c; }
                }
                if (marked == 0) { out.insert(out.end(), {v[0], v[1], v[2]}); continue; }
                if (marked == 1) {
                    int r = bits == 1 ? 0 : bits == 2 ? 1 : 2;
                    uint32_t p = v[r], q = v[(r + 1) % 3], s = v[(r + 2) % 3], mm = m[r];
                    out.insert(out.end(), {p, mm, s, mm, q, s});
                } else if (marked == 2) {
                    int u = !(bits & 1) ? 0 : !(bits & 2) ? 1 : 2;   // the unmarked edge
                    int r = (u + 1) % 3;
                    uint32_t a = v[r], b = v[(r + 1) % 3], c = v[(r + 2) % 3], m0 = m[r], m1 = m[(r + 1) % 3];
                    // edges: a-b (m0), b-c (m1), c-a unmarked: the triangle (m0, b, m1) and the quad a, m0, m1, c cut on its shorter diagonal
                    out.insert(out.end(), {m0, b, m1});
                    float d1 = (posOf(verts[m0]) - posOf(verts[c])).length(), d2 = (posOf(verts[a]) - posOf(verts[m1])).length();
                    if (d1 < d2) out.insert(out.end(), {a, m0, c, m0, m1, c});
                    else out.insert(out.end(), {a, m0, m1, a, m1, c});
                } else {
                    out.insert(out.end(), {v[0], m[0], m[2], m[0], v[1], m[1], m[2], m[1], v[2], m[0], m[1], m[2]});
                }
            }
        };
        size_t cursor = 0;
        for (size_t oi : order) {
            const TessRange& r = ranges[oi];
            size_t s = r.start, e = std::min<size_t>(indices.size(), size_t(r.start) + r.count);
            if (s < cursor) continue;
            cut(cursor, s);   // (a gap between ranges: cut too, so no triangle is left behind)
            nr[oi].start = uint32_t(out.size());
            cut(s, e);
            nr[oi].count = uint32_t(out.size()) - nr[oi].start;
            cursor = e;
        }
        cut(cursor, indices.size());
        indices.swap(out);
        ranges.swap(nr);
        ++st.passes;
        if (st.capped) break;
    }
    measure(&st.edgeP50After, &st.edgeP99After, &st.softEdgesAfter);
    st.softEdgesBefore = over0;
    st.verticesAfter = verts.size(); st.trianglesAfter = indices.size() / 3;
    st.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (stats) *stats = st;
    return added;
}

} // namespace wc
