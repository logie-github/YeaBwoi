// The body shapes' geometry (engine/bodyshape.cpp) on a made-up piece of skin, no GL: build/bodyshape_test exits 0 when
// every check holds. The skin: a rounded patch (a chest's curve, today's 3 cm between vertices) in two sections meeting
// along a seam (twin vertices there, each section's own texture coordinates); the shape: a smooth mound forward and down
// (gravity), given as Wrath++'s package gives it, every other vertex of the patch (the looks') plus finer records between.
#include <cmath>
#include <cstdio>
#include <map>
#include <set>
#include <vector>

#include "../android/app/src/main/cpp/engine/bodyshape.h"

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } } while (0)

// The rest surface: x forward, a gentle dome over (y, z).
static float surfX(float y, float z) { return 0.12f - 1.5f * (y * y + (z - 1.4f) * (z - 1.4f)); }
static V3 surfN(float y, float z) { return V3(1, 3.0f * y, 3.0f * (z - 1.4f)).normalized(); }
// The shape: a mound centred low (its tip below centre: it hangs), moving forward and down, 0 past 9 cm.
static V3 shapeMove(V3 p) {
    float u = p.y / 0.09f, v = (p.z - 1.38f) / (p.z > 1.38f ? 0.09f : 0.065f);
    float r2 = u * u + v * v;
    if (r2 >= 1) return V3();
    float b = (1 - r2) * (1 - r2);
    return V3(0.04f * b, 0, -0.015f * b);
}

int main() {
    const int N = 13;                 // 13 x 13 vertices, 3 cm apart: 36 cm square around the chest's centre
    const float step = 0.03f;
    M2Model m;
    auto vertex = [&](int i, int j, float u0) {
        float y = (i - N / 2) * step, z = 1.4f + (j - N / 2) * step;
        M2Vertex v{};
        v.pos[0] = surfX(y, z); v.pos[1] = y; v.pos[2] = z;
        V3 n = surfN(y, z);
        v.normal[0] = n.x; v.normal[1] = n.y; v.normal[2] = n.z;
        v.uv[0] = u0 + i * 0.01f; v.uv[1] = 0.3f + j * 0.01f;
        v.weights[0] = uint8_t(i < N / 2 ? 255 : 128); v.bones[0] = 1;
        v.weights[1] = uint8_t(i < N / 2 ? 0 : 127); v.bones[1] = 2;
        m.vertices.push_back(v);
        return uint32_t(m.vertices.size() - 1);
    };
    // two sections: columns 0..6 and 6..12, column 6 twice (the seam's twins, each section's texture coordinates)
    std::map<std::pair<int, int>, uint32_t> left, right;
    for (int i = 0; i <= N / 2; ++i) for (int j = 0; j < N; ++j) left[{i, j}] = vertex(i, j, 0.1f);
    for (int i = N / 2; i < N; ++i) for (int j = 0; j < N; ++j) right[{i, j}] = vertex(i, j, 0.5f);
    for (int s = 0; s < 2; ++s) {
        auto& at = s ? right : left;
        M2Submesh sm;
        sm.id = uint16_t(s ? 2201 : 0);
        sm.indexStart = uint32_t(m.indices.size());
        for (int i = s ? N / 2 : 0; i < (s ? N - 1 : N / 2); ++i)
            for (int j = 0; j + 1 < N; ++j) {
                uint32_t a = at[{i, j}], b = at[{i + 1, j}], c = at[{i + 1, j + 1}], d = at[{i, j + 1}];
                m.indices.insert(m.indices.end(), {a, b, c, a, c, d});
            }
        sm.indexCount = uint32_t(m.indices.size()) - sm.indexStart;
        m.submeshes.push_back(sm);
    }
    const size_t own = m.vertices.size();

    // the package's records: every other vertex of the patch exactly (the looks', each twin with its own texture
    // coordinates), a cap's twin at one of them that the shape leaves, then finer ones between (the refined)
    std::vector<float> rec;
    auto record = [&](V3 p, V3 n, float u, float v) {
        V3 d = shapeMove(p);
        float r[16] = {p.x, p.y, p.z, d.x, d.y, d.z, n.x, n.y, n.z, n.x, n.y, n.z, u * 2, v, u * 2, v};
        rec.insert(rec.end(), r, r + 16);
    };
    for (size_t k = 0; k < own; ++k) {
        const M2Vertex& v = m.vertices[k];
        int i = int(std::lround(v.pos[1] / step)) + N / 2, j = int(std::lround((v.pos[2] - 1.4f) / step)) + N / 2;
        if (i % 2 || j % 2) continue;
        record(V3(v.pos[0], v.pos[1], v.pos[2]), V3(v.normal[0], v.normal[1], v.normal[2]), v.uv[0], v.uv[1]);
    }
    {   // a hidden cap's vertex at the mound's centre-ish looks vertex: the shape leaves it (0 move), facing down
        float y = 0, z = 1.38f + 0.02f;
        float r[16] = {surfX(y, z), y, z, 0, 0, 0, 0, 0, -1, 0, 0, -1, 0.2f, 0.4f, 0.2f, 0.4f};
        rec.insert(rec.end(), r, r + 16);
    }
    for (float y = -0.18f; y <= 0.18f; y += 0.015f)
        for (float z = 1.22f; z <= 1.58f; z += 0.015f) {
            if (std::fmod(std::fabs(y) + 1e-4f, 0.03f) < 2e-4f && std::fmod(std::fabs(z - 1.4f) + 1e-4f, 0.03f) < 2e-4f) continue;
            float u = y < 0 ? 0.1f : 0.5f;
            record(V3(surfX(y, z), y, z), surfN(y, z), (u + (y / step + N / 2) * 0.01f), 0.3f + ((z - 1.4f) / step + N / 2) * 0.01f);
        }
    const size_t count = rec.size() / kBodyMorphStride;

    std::vector<bool> splittable(m.submeshes.size(), true);
    ShapedBody out;
    bool ok = shapeBody(m, splittable, rec.data(), count, 1, out);
    CHECK(ok, "the shape moved nothing");
    std::fprintf(stderr, "moved %zu, added %zu, split %zu triangles, %zu at a looks vertex\n", out.moved, out.added, out.split, out.exact);
    CHECK(out.added > 0 && out.split > 0, "nothing was split");
    CHECK(out.vertices.size() == own + out.added && out.addedRest.size() == out.added, "vertex counts");

    // 1. the model's own vertices keep their numbers, bones and texture coordinates; at a looks vertex they move exactly so
    float worstExact = 0;
    for (size_t k = 0; k < own; ++k) {
        const M2Vertex& a = m.vertices[k];
        const M2Vertex& b = out.vertices[k];
        CHECK(a.bones[0] == b.bones[0] && a.weights[0] == b.weights[0] && a.uv[0] == b.uv[0], "vertex %zu lost its bones or texture", k);
        int i = int(std::lround(a.pos[1] / step)) + N / 2, j = int(std::lround((a.pos[2] - 1.4f) / step)) + N / 2;
        if (i % 2 || j % 2) continue;
        V3 want = shapeMove(V3(a.pos[0], a.pos[1], a.pos[2]));
        V3 got = V3(b.pos[0], b.pos[1], b.pos[2]) - V3(a.pos[0], a.pos[1], a.pos[2]);
        worstExact = std::max(worstExact, (got - want).length());
    }
    CHECK(worstExact < 1e-6f, "a vertex at a looks vertex moved %.6f m off the package's move", worstExact);

    // 2. every vertex (the model's between the looks', the split's new ones) lands where the shape's surface is: within
    // 2 mm of the true move on a 4 cm mound
    float worst = 0;
    for (size_t k = 0; k < out.vertices.size(); ++k) {
        V3 rest = k < own ? V3(m.vertices[k].pos[0], m.vertices[k].pos[1], m.vertices[k].pos[2]) : out.addedRest[k - own];
        if (std::fabs(rest.y) > 0.15f || std::fabs(rest.z - 1.4f) > 0.15f) continue;      // (the patch's edge: one-sided)
        V3 got = V3(out.vertices[k].pos[0], out.vertices[k].pos[1], out.vertices[k].pos[2]) - rest;
        worst = std::max(worst, (got - shapeMove(rest)).length());
    }
    std::fprintf(stderr, "worst miss of the shape's move: %.2f mm (looks vertex: %.4f mm)\n", worst * 1000, worstExact * 1000);
    CHECK(worst < 0.002f, "a vertex missed the shape's move by %.2f mm", worst * 1000);

    // 3. nothing opens: twins at one place stay at one place, and every edge inside the patch has a triangle on each side
    std::map<std::tuple<long, long, long>, std::set<std::tuple<long, long, long>>> placesAfter;
    auto key = [](const float* p) { return std::make_tuple(std::lround(p[0] * 1e5), std::lround(p[1] * 1e5), std::lround(p[2] * 1e5)); };
    for (size_t k = 0; k < out.vertices.size(); ++k) {
        V3 rest = k < own ? V3(m.vertices[k].pos[0], m.vertices[k].pos[1], m.vertices[k].pos[2]) : out.addedRest[k - own];
        placesAfter[key(&rest.x)].insert(key(out.vertices[k].pos));
    }
    for (auto& [k, after] : placesAfter) CHECK(after.size() == 1, "vertices at one place parted (%zu places after)", after.size());
    std::map<std::pair<std::tuple<long, long, long>, std::tuple<long, long, long>>, int> edges;
    for (const M2Submesh& sm : out.submeshes)
        for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount; t += 3)
            for (int e = 0; e < 3; ++e) {
                auto a = key(out.vertices[out.indices[t + e]].pos), b = key(out.vertices[out.indices[t + (e + 1) % 3]].pos);
                edges[a < b ? std::make_pair(a, b) : std::make_pair(b, a)]++;
            }
    size_t open = 0;
    for (auto& [e, n] : edges) {
        float y0 = std::get<1>(e.first) * 1e-5f, z0 = std::get<2>(e.first) * 1e-5f, y1 = std::get<1>(e.second) * 1e-5f, z1 = std::get<2>(e.second) * 1e-5f;
        bool rim = (std::fabs(y0) > 0.179f && std::fabs(y1) > 0.179f) || (std::fabs(z0 - 1.4f) > 0.179f && std::fabs(z1 - 1.4f) > 0.179f);
        if (n != 2 && !rim) ++open;
    }
    CHECK(open == 0, "%zu edges inside the patch with a triangle on one side only (a crack)", open);

    // 4. the sections keep their ids and order, their ranges tile the index list; the split ones' new vertices carry their
    // corners' bones (weights summing to 255) and texture coordinates between theirs
    uint32_t next = 0;
    for (size_t s = 0; s < out.submeshes.size(); ++s) {
        CHECK(out.submeshes[s].id == m.submeshes[s].id, "section %zu changed id", s);
        CHECK(out.submeshes[s].indexStart == next, "section %zu starts at %u, not %u", s, out.submeshes[s].indexStart, next);
        next += out.submeshes[s].indexCount;
    }
    CHECK(next == out.indices.size(), "sections cover %u of %zu indices", next, out.indices.size());
    for (size_t k = own; k < out.vertices.size(); ++k) {
        const M2Vertex& v = out.vertices[k];
        int sum = v.weights[0] + v.weights[1] + v.weights[2] + v.weights[3];
        CHECK(sum == 255, "added vertex %zu's weights sum to %d", k, sum);
        for (int b = 0; b < 4; ++b) CHECK(!v.weights[b] || v.bones[b] == 1 || v.bones[b] == 2, "added vertex %zu has a bone none of its corners has", k);
        CHECK(v.uv[0] >= 0.1f && v.uv[0] <= 0.62f && v.uv[1] >= 0.3f && v.uv[1] <= 0.42f, "added vertex %zu's texture coordinates off the patch", k);
    }

    // 5. smooth: the normals face as the shaped surface does (its own faces' normals, not the rest's), unit length
    float worstN = 1;
    for (size_t k = 0; k < out.vertices.size(); ++k) {
        V3 rest = k < own ? V3(m.vertices[k].pos[0], m.vertices[k].pos[1], m.vertices[k].pos[2]) : out.addedRest[k - own];
        if (std::fabs(rest.y) > 0.13f || std::fabs(rest.z - 1.4f) > 0.13f) continue;
        // the shaped surface's normal, numerically: S(y, z) = rest + move
        auto S = [&](float y, float z) { V3 p(surfX(y, z), y, z); return p + shapeMove(p); };
        float h = 0.002f;
        V3 du = S(rest.y + h, rest.z) - S(rest.y - h, rest.z), dv = S(rest.y, rest.z + h) - S(rest.y, rest.z - h);
        V3 want = du.cross(dv).normalized();
        if (want.x < 0) want = want * -1.0f;
        V3 n(out.vertices[k].normal[0], out.vertices[k].normal[1], out.vertices[k].normal[2]);
        CHECK(std::fabs(n.length() - 1) < 1e-3f, "vertex %zu's normal isn't unit length", k);
        worstN = std::min(worstN, n.dot(want));
    }
    std::fprintf(stderr, "normals: worst agreement with the shaped surface %.4f (%.1f degrees)\n", worstN, std::acos(std::min(1.0f, worstN)) * 57.2958f);
    CHECK(worstN > std::cos(12 * 3.14159265f / 180), "a normal is %.1f degrees off the shaped surface", std::acos(worstN) * 57.2958f);

    // 6. records that move nothing: no shape, nothing made
    std::vector<float> still(rec);
    for (size_t i = 0; i < count; ++i) for (int c = 3; c < 6; ++c) still[i * kBodyMorphStride + c] = 0;
    ShapedBody none;
    CHECK(!shapeBody(m, splittable, still.data(), count, 1, none) && none.vertices.empty(), "a shape that moves nothing made a body");

    if (failures) { std::fprintf(stderr, "%d check(s) failed\n", failures); return 1; }
    std::fprintf(stderr, "body shape checks: all passed\n");
    return 0;
}
