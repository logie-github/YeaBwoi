#include "softtess.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace wc {

namespace {
uint64_t ekey(uint32_t a, uint32_t b) { return a < b ? (uint64_t(a) << 32) | b : (uint64_t(b) << 32) | a; }

float dist(const M2Vertex& a, const M2Vertex& b) {
    const float x = a.pos[0] - b.pos[0], y = a.pos[1] - b.pos[1], z = a.pos[2] - b.pos[2];
    return std::sqrt(x * x + y * y + z * z);
}

bool shouldSplit(const std::vector<M2Vertex>& v, const SoftTess& st, uint32_t a, uint32_t b, float maxEdge) {
    if (a >= st.mask.size() || b >= st.mask.size() || !st.mask[a] || !st.mask[b]) return false;
    return dist(v[a], v[b]) > maxEdge;
}

uint32_t midpoint(std::vector<M2Vertex>& v, SoftTess& st, uint32_t a, uint32_t b) {
    const uint64_t k = ekey(a, b);
    auto it = st.mid.find(k);
    if (it != st.mid.end()) return it->second;
    M2Vertex m;
    const M2Vertex pa = v[a], pb = v[b];
    for (int i = 0; i < 3; ++i) m.pos[i] = 0.5f * (pa.pos[i] + pb.pos[i]);
    float n[3], l = 0;
    for (int i = 0; i < 3; ++i) { n[i] = pa.normal[i] + pb.normal[i]; l += n[i] * n[i]; }
    l = std::sqrt(l);
    for (int i = 0; i < 3; ++i) m.normal[i] = l > 1e-9f ? n[i] / l : pa.normal[i];
    for (int i = 0; i < 2; ++i) { m.uv[i] = 0.5f * (pa.uv[i] + pb.uv[i]); m.uv2[i] = 0.5f * (pa.uv2[i] + pb.uv2[i]); }
    // the union of the two ends' influences, each at half
    uint8_t bone[8]; float w[8]; int n8 = 0;
    auto add = [&](const M2Vertex& s) {
        for (int i = 0; i < 4; ++i) {
            if (s.weights[i] == 0) continue;
            int f = -1;
            for (int j = 0; j < n8; ++j) if (bone[j] == s.bones[i]) f = j;
            if (f < 0) { f = n8++; bone[f] = s.bones[i]; w[f] = 0; }
            w[f] += 0.5f * s.weights[i];
        }
    };
    add(pa); add(pb);
    int order[8];
    for (int i = 0; i < n8; ++i) order[i] = i;
    std::sort(order, order + n8, [&](int x, int y) { return w[x] > w[y]; });
    float tot = 0;
    const int keep = std::min(n8, 4);
    for (int i = 0; i < keep; ++i) tot += w[order[i]];
    std::memset(m.weights, 0, 4); std::memset(m.bones, 0, 4);
    int sum = 0;
    for (int i = 0; i < keep; ++i) {
        const int q = order[i];
        int val = tot > 0 ? int(std::lround(w[q] / tot * 255.0f)) : 0;
        if (i == 0) val = 0;
        m.bones[i] = bone[q]; m.weights[i] = uint8_t(std::min(val, 255)); sum += val;
    }
    if (keep > 0) m.weights[0] = uint8_t(std::clamp(255 - sum, 0, 255));
    const uint32_t id = uint32_t(v.size());
    v.push_back(m);
    st.mask.resize(v.size(), 0);
    st.mask[id] = 1;
    st.mid.emplace(k, id);
    ++st.added;
    return id;
}
} // namespace

void tessellateTriangles(std::vector<M2Vertex>& verts, const uint32_t* tris, size_t count, float maxEdge, SoftTess& st,
                         std::vector<uint32_t>& out) {
    std::vector<uint32_t> cur(tris, tris + count), next;
    if (st.mask.size() < verts.size()) st.mask.resize(verts.size(), 0);
    for (int pass = 0; pass < st.passes; ++pass) {
        next.clear();
        bool any = false;
        for (size_t t = 0; t + 2 < cur.size(); t += 3) {
            uint32_t v[3] = {cur[t], cur[t + 1], cur[t + 2]};
            bool f[3];
            int nf = 0;
            for (int e = 0; e < 3; ++e) {
                f[e] = shouldSplit(verts, st, v[e], v[(e + 1) % 3], maxEdge);
                if (f[e] && st.vertexCap && verts.size() + 3 > st.vertexCap && !st.mid.count(ekey(v[e], v[(e + 1) % 3]))) f[e] = false;
                nf += f[e];
            }
            if (!nf) { next.insert(next.end(), v, v + 3); continue; }
            any = true;
            if (nf == 3) {
                const uint32_t m0 = midpoint(verts, st, v[0], v[1]), m1 = midpoint(verts, st, v[1], v[2]), m2 = midpoint(verts, st, v[2], v[0]);
                const uint32_t q[12] = {v[0], m0, m2, m0, v[1], m1, m2, m1, v[2], m0, m1, m2};
                next.insert(next.end(), q, q + 12);
            } else if (nf == 2) {
                int u = 0;
                for (int e = 0; e < 3; ++e) if (!f[e]) u = e;
                const uint32_t w0 = v[(u + 1) % 3], w1 = v[(u + 2) % 3], w2 = v[u];
                const uint32_t m0 = midpoint(verts, st, w0, w1), m1 = midpoint(verts, st, w1, w2);
                // (m0, w1, m1) and the quad w0, m0, m1, w2 on its shorter diagonal
                const uint32_t a[3] = {m0, w1, m1};
                next.insert(next.end(), a, a + 3);
                if (dist(verts[w0], verts[m1]) < dist(verts[m0], verts[w2])) { const uint32_t b[6] = {w0, m0, m1, w0, m1, w2}; next.insert(next.end(), b, b + 6); }
                else { const uint32_t b[6] = {m0, m1, w2, w0, m0, w2}; next.insert(next.end(), b, b + 6); }
            } else {
                int k = 0;
                for (int e = 0; e < 3; ++e) if (f[e]) k = e;
                const uint32_t w0 = v[k], w1 = v[(k + 1) % 3], w2 = v[(k + 2) % 3];
                const uint32_t m = midpoint(verts, st, w0, w1);
                const uint32_t q[6] = {w0, m, w2, m, w1, w2};
                next.insert(next.end(), q, q + 6);
            }
        }
        cur.swap(next);
        if (!any) break;
    }
    out.insert(out.end(), cur.begin(), cur.end());
}

std::vector<uint8_t> softRegionMask(const M2Model& m, const std::vector<M2Vertex>& verts, size_t count, float minShare) {
    static const char* const names[] = {"Chest", "Spine1", "Spine2", "Waist", "Pelvis", "L_Breast", "R_Breast", "L_Thigh", "R_Thigh", "L_UpperArm", "R_UpperArm"};
    std::vector<uint8_t> bone(m.bones.size(), 0);
    for (const char* n : names) {
        const int b = m.findWowBone(n);
        if (b >= 0 && size_t(b) < bone.size()) bone[size_t(b)] = 1;
    }
    std::vector<uint8_t> mask(std::min(count, verts.size()), 0);
    for (size_t i = 0; i < mask.size(); ++i) {
        float s = 0;
        for (int k = 0; k < 4; ++k) if (verts[i].bones[k] < bone.size() && bone[verts[i].bones[k]]) s += verts[i].weights[k] / 255.0f;
        mask[i] = s >= minShare;
    }
    return mask;
}

} // namespace wc
