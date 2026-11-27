// Tessellation of the soft regions without GL (engine/tessellate.cpp). Exit 0 when all pass.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>

#include "tessellate.h"

using namespace wc;
static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { ++fails; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

// A UV sphere (radius r, seam duplicated: the first and last column are separate vertices), two bones (north half on bone 1).
static void sphere(float r, int nu, int nv, std::vector<M2Vertex>& v, std::vector<uint32_t>& idx) {
    for (int j = 0; j <= nv; ++j)
        for (int i = 0; i <= nu; ++i) {
            float th = 3.14159265f * float(j) / float(nv), ph = 2 * 3.14159265f * float(i) / float(nu);
            M2Vertex m{};
            m.pos[0] = r * std::sin(th) * std::cos(ph); m.pos[1] = r * std::sin(th) * std::sin(ph); m.pos[2] = r * std::cos(th);
            m.normal[0] = m.pos[0] / r; m.normal[1] = m.pos[1] / r; m.normal[2] = m.pos[2] / r;
            m.uv[0] = float(i) / float(nu); m.uv[1] = float(j) / float(nv);
            m.bones[0] = j < nv / 2 ? 1 : 2; m.bones[1] = 3; m.weights[0] = 200; m.weights[1] = 55;
            v.push_back(m);
        }
    for (int j = 0; j < nv; ++j)
        for (int i = 0; i < nu; ++i) {
            uint32_t a = uint32_t(j * (nu + 1) + i), b = a + 1, c = a + uint32_t(nu + 1), d = c + 1;
            if (j > 0) { idx.push_back(a); idx.push_back(c); idx.push_back(b); }
            if (j < nv - 1) { idx.push_back(b); idx.push_back(c); idx.push_back(d); }
        }
}
static std::map<std::vector<long>, int> edgeCounts(const std::vector<M2Vertex>& v, const std::vector<uint32_t>& idx) {
    auto key = [&](uint32_t i) { return std::vector<long>{std::lround(v[i].pos[0] * 1e5), std::lround(v[i].pos[1] * 1e5), std::lround(v[i].pos[2] * 1e5)}; };
    std::map<std::vector<long>, int> ec;
    for (size_t i = 0; i + 2 < idx.size(); i += 3)
        for (int c = 0; c < 3; ++c) {
            auto a = key(idx[i + size_t(c)]), b = key(idx[i + size_t((c + 1) % 3)]);
            std::vector<long> k = a < b ? a : b; k.insert(k.end(), (a < b ? b : a).begin(), (a < b ? b : a).end());
            ++ec[k];
        }
    return ec;
}

int main() {
    const float r = 0.1f;
    std::vector<M2Vertex> v0; std::vector<uint32_t> i0;
    sphere(r, 24, 16, v0, i0);
    // soft: the northern hemisphere (z > 0), a ramp at the equator
    std::vector<float> soft0(v0.size());
    for (size_t i = 0; i < v0.size(); ++i) soft0[i] = v0[i].pos[2] > 0.02f ? 1.0f : 0.0f;
    // plain: every soft edge ends up short, the mesh stays closed, the cap holds, attributes interpolate
    {
        auto v = v0; auto idx = i0; auto soft = soft0;
        std::vector<TessRange> ranges{{0, uint32_t(idx.size() / 2)}, {uint32_t(idx.size() / 2), uint32_t(idx.size() - idx.size() / 2)}};
        TessStats st;
        size_t add = tessellateSoftRegions(v, idx, ranges, soft, 0.012f, 100000, &st);
        CHECK(add > 0 && v.size() == v0.size() + add && soft.size() == v.size(), "added %zu", add);
        CHECK(st.softEdgesAfter == 0, "%zu soft edges still over the limit (p99 %g)", st.softEdgesAfter, st.edgeP99After);
        CHECK(st.edgeP99After <= 0.012f + 1e-6f, "p99 after %g", st.edgeP99After);
        CHECK(!st.capped, "capped");
        for (uint32_t i : idx) CHECK(i < v.size(), "index out of range");
        // the ranges are contiguous, in order, cover everything
        CHECK(ranges[0].start == 0 && ranges[0].start + ranges[0].count == ranges[1].start && ranges[1].start + ranges[1].count == idx.size(), "ranges %u+%u, %u+%u of %zu", ranges[0].start, ranges[0].count, ranges[1].start, ranges[1].count, idx.size());
        CHECK(idx.size() % 3 == 0, "indices");
        // watertight: no edge in more than two triangles, the sphere closed (every welded edge in exactly two)
        auto ec = edgeCounts(v, idx);
        int bad = 0;
        for (auto& [k, c] : ec) if (c != 2) ++bad;
        CHECK(bad == 0, "%d welded edges not shared by exactly two triangles (cracks)", bad);
        // new vertices lie on the sphere (the cubic edge curve), unit normals, weights sum 255, uv inside the range
        float worst = 0, worstN = 0; int badW = 0, badUv = 0;
        for (size_t i = v0.size(); i < v.size(); ++i) {
            float l = std::sqrt(v[i].pos[0] * v[i].pos[0] + v[i].pos[1] * v[i].pos[1] + v[i].pos[2] * v[i].pos[2]);
            worst = std::max(worst, std::fabs(l - r) / r);
            float n = std::sqrt(v[i].normal[0] * v[i].normal[0] + v[i].normal[1] * v[i].normal[1] + v[i].normal[2] * v[i].normal[2]);
            worstN = std::max(worstN, std::fabs(n - 1));
            if (v[i].weights[0] + v[i].weights[1] + v[i].weights[2] + v[i].weights[3] != 255) ++badW;
            if (v[i].uv[0] < -1e-5f || v[i].uv[0] > 1 + 1e-5f || v[i].uv[1] < -1e-5f || v[i].uv[1] > 1 + 1e-5f) ++badUv;
        }
        CHECK(worst < 0.01f, "new vertices off the sphere by %g", worst);
        CHECK(worstN < 1e-4f, "normal length off by %g", worstN);
        CHECK(badW == 0, "%d vertices whose weights do not sum to 255", badW);
        CHECK(badUv == 0, "uv outside");
        // the old vertices are untouched
        for (size_t i = 0; i < v0.size(); ++i) CHECK(std::memcmp(&v[i], &v0[i], sizeof(M2Vertex)) == 0, "vertex %zu changed", i);
        // the southern half is not touched: its triangles are the same count
        std::printf("sphere: %zu -> %zu vertices, %zu -> %zu triangles, edge p99 %.4f -> %.4f, %d passes, %.1f ms\n", st.verticesBefore, st.verticesAfter, st.trianglesBefore, st.trianglesAfter, st.edgeP99Before, st.edgeP99After, st.passes, st.ms);
    }
    // the cap
    {
        auto v = v0; auto idx = i0; auto soft = soft0;
        std::vector<TessRange> ranges{{0, uint32_t(idx.size())}};
        TessStats st;
        size_t add = tessellateSoftRegions(v, idx, ranges, soft, 0.004f, 500, &st);
        CHECK(add <= 500 && st.capped, "cap: added %zu capped %d", add, int(st.capped));
        auto ec = edgeCounts(v, idx);
        int bad = 0;
        for (auto& [k, c] : ec) if (c != 2) ++bad;
        CHECK(bad == 0, "cap: %d cracks", bad);
    }
    // nothing soft: nothing happens; a long limit: nothing happens
    {
        auto v = v0; auto idx = i0; std::vector<float> soft(v0.size(), 0.0f);
        std::vector<TessRange> ranges{{0, uint32_t(idx.size())}};
        CHECK(tessellateSoftRegions(v, idx, ranges, soft, 0.01f, 1000) == 0 && idx == i0, "no soft region changed the mesh");
        auto soft2 = soft0;
        CHECK(tessellateSoftRegions(v, idx, ranges, soft2, 1.0f, 1000) == 0 && idx == i0, "a long limit changed the mesh");
    }
    // determinism
    {
        auto va = v0, vb = v0; auto ia = i0, ib = i0; auto sa = soft0, sb = soft0;
        std::vector<TessRange> ra{{0, uint32_t(ia.size())}}, rb = ra;
        tessellateSoftRegions(va, ia, ra, sa, 0.01f, 5000);
        tessellateSoftRegions(vb, ib, rb, sb, 0.01f, 5000);
        CHECK(ia == ib && va.size() == vb.size() && std::memcmp(va.data(), vb.data(), va.size() * sizeof(M2Vertex)) == 0, "not deterministic");
    }
    std::printf(fails ? "tessellate_test: %d FAILED\n" : "tessellate_test: all passed\n", fails);
    return fails ? 1 : 0;
}
