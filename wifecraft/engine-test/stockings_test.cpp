// The stockings' maths (engine/stockings.cpp, engine/stockfabric.cpp) on a synthetic body (two cylinder legs): no GL, no game files.
// Exit 0 when all pass.
#include <cmath>
#include <cstdio>
#include <set>

#include "stockfabric.h"
#include "stockings.h"
#include "bodypick.h"

using namespace wc;

static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { ++fails; std::printf("FAIL %s:%d: %s  ", __FILE__, __LINE__, #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

struct Body { std::vector<M2Vertex> v; std::vector<uint32_t> idx; std::vector<uint8_t> regions; };

// two legs (cylinders of radius r, centred y = +-0.1, z from 0 to 1.2), 24 sides, 48 rings; bones: 0 pelvis (hips) above z 1.0, 1 leg, 2 foot below 0.08
static Body makeBody(bool male = false) {
    Body b;
    b.regions = {kRegionHips, kRegionLegs, kRegionFeet};
    const int sides = 24, rings = 48;
    const float r = male ? 0.1f : 0.08f;
    for (int leg = 0; leg < 2; ++leg)
        for (int j = 0; j <= rings; ++j)
            for (int i = 0; i < sides; ++i) {
                float a = 6.2831853f * i / sides, z = 1.2f * j / rings;
                M2Vertex m{};
                m.pos[0] = r * std::cos(a); m.pos[1] = (leg ? -0.1f : 0.1f) + r * std::sin(a); m.pos[2] = z;
                m.normal[0] = std::cos(a); m.normal[1] = std::sin(a); m.normal[2] = 0;
                m.uv[0] = float(i) / sides; m.uv[1] = z;
                int bone = z > 1.0f ? 0 : z < 0.08f ? 2 : 1;
                m.bones[0] = uint8_t(bone); m.weights[0] = 255;
                b.v.push_back(m);
            }
    const int per = (rings + 1) * sides;
    for (int leg = 0; leg < 2; ++leg)
        for (int j = 0; j < rings; ++j)
            for (int i = 0; i < sides; ++i) {
                uint32_t a = uint32_t(leg * per + j * sides + i), bb = uint32_t(leg * per + j * sides + (i + 1) % sides);
                uint32_t c = a + sides, d = bb + sides;
                b.idx.insert(b.idx.end(), {a, bb, c, bb, d, c});
            }
    return b;
}

static StockShell make(const Body& b, int style, float offset = 0.003f, int denier = 20) {
    StockSpec sp; sp.style = style; sp.offset = offset; sp.denier = denier;
    StockShell s;
    bool ok = buildStockings(b.v, b.idx, {}, {}, b.regions, sp, 0.0f, 0.08f, 0.5f, 1.0f, s);
    CHECK(ok, "style %d built", style);
    return s;
}

int main() {
    Body body = makeBody();
    // ---- the extents: ankle < knee < thigh < tights, a band only where the style has one
    StockExtent ea = stockExtent(kStockAnkle, 0, 0.08f, 0.5f, 1.0f), ek = stockExtent(kStockKnee, 0, 0.08f, 0.5f, 1.0f),
                et = stockExtent(kStockThigh, 0, 0.08f, 0.5f, 1.0f), eh = stockExtent(kStockTights, 0, 0.08f, 0.5f, 1.0f);
    CHECK(ea.top < ek.top && ek.top < et.top && et.top < eh.top, "%.3f %.3f %.3f %.3f", ea.top, ek.top, et.top, eh.top);
    CHECK(ea.bandHeight == 0 && ek.bandHeight > 0 && et.bandHeight > ek.bandHeight, "bands");
    CHECK(ek.top < 0.5f && et.top > 0.5f && et.top < 1.0f && eh.top > 1.0f, "knee below the knee, thigh between, tights above the hip");
    // ---- each style's shell: inside its extent, offset along the normal, weights copied, band after the weave
    for (int style = 0; style < kStockStyles; ++style) {
        StockShell s = make(body, style);
        CHECK(!s.verts.empty() && s.indices.size() % 3 == 0, "style %d", style);
        float zmax = -1e9f, zmin = 1e9f;
        for (size_t i = 0; i < s.verts.size(); ++i) {
            const M2Vertex& v = s.verts[i];
            zmax = std::max(zmax, v.pos[2] - s.extent.slope * v.pos[0]); zmin = std::min(zmin, v.pos[2]);
            // distance from its leg's axis = radius + offset
            float cy = v.pos[1] >= 0 ? 0.1f : -0.1f;
            float d = std::hypot(v.pos[0], v.pos[1] - cy);
            CHECK(d > 0.0822f && d < (style == kStockGarter ? 0.0862f : 0.0832f), "style %d offset %.5f", style, d - 0.08f);   // (a clipped corner lies on a facet: up to 0.7 mm under)
            // the weights and bones are the body vertex's (or the nearer corner's of a clipped one): one bone at full weight
            CHECK(v.weights[0] == 255 && v.bones[0] <= 2, "weights copied");
            CHECK(s.source[i] < body.v.size() && v.bones[0] == body.v[s.source[i]].bones[0], "source");
            CHECK(std::isfinite(v.uv[0]) && std::isfinite(v.uv[1]), "uv");
        }
        if (style != kStockGarter) CHECK(zmax <= s.extent.top + 1e-3f && zmax > s.extent.top - 0.02f, "style %d top %.3f vs %.3f", style, zmax, s.extent.top);
        else CHECK(zmax > s.extent.top + 0.02f, "the garter's belt reaches above the stocking's top: %.3f vs %.3f", zmax, s.extent.top);
        CHECK(zmin >= s.extent.bottom - 0.03f && zmin < 0.03f, "style %d bottom %.3f", style, zmin);
        for (uint32_t k : s.indices) CHECK(k < s.verts.size(), "index");
        if (s.extent.bandHeight > 0) {
            CHECK(s.bandStart > 0 && s.bandStart < s.indices.size(), "style %d has a band after the weave", style);
            for (size_t t = s.bandStart; t < s.indices.size(); t += 3) {
                float cz = 0; for (int q = 0; q < 3; ++q) cz += (s.verts[s.indices[t + q]].pos[2] - s.extent.slope * s.verts[s.indices[t + q]].pos[0]) / 3;
                CHECK(cz >= s.extent.top - s.extent.bandHeight - 1e-4f, "band triangle at %.3f", cz);
            }
            for (size_t t = 0; t < s.bandStart; t += 3) {
                float cz = 0; for (int q = 0; q < 3; ++q) cz += (s.verts[s.indices[t + q]].pos[2] - s.extent.slope * s.verts[s.indices[t + q]].pos[0]) / 3;
                CHECK(cz <= s.extent.top - s.extent.bandHeight + 1e-4f, "weave triangle in the band's place %.3f", cz);
            }
        } else CHECK(s.bandStart == s.indices.size(), "ankle has no band");
    }
    // ---- more of the leg, more triangles; the offset is the spec's
    CHECK(make(body, kStockThigh).indices.size() > make(body, kStockKnee).indices.size(), "thigh bigger than knee");
    CHECK(make(body, kStockTights).indices.size() > make(body, kStockThigh).indices.size(), "tights bigger");
    { StockShell s = make(body, kStockKnee, 0.0015f); CHECK(std::fabs(s.maxGap - 0.0015f) < 1e-6f, "1.5 mm"); }
    // ---- a man's legs (the same generic geometry) and a body that has no legs at all
    { Body m = makeBody(true); StockShell s = make(m, kStockThigh); CHECK(!s.verts.empty(), "male");
      float d = std::hypot(s.verts[0].pos[0], s.verts[0].pos[1] - (s.verts[0].pos[1] >= 0 ? 0.1f : -0.1f)); CHECK(d > 0.1022f && d < 0.1032f, "male radius %.4f", d); }
    { Body none = makeBody(); none.regions = {kRegionHead, kRegionHead, kRegionHead}; StockSpec sp; StockShell s;
      CHECK(!buildStockings(none.v, none.idx, {}, {}, none.regions, sp, 0, 0.08f, 0.5f, 1.0f, s), "a creature with no legs is skipped"); }
    // ---- the textures: size, sheerness by denier, the seam
    { StockSpec a; a.denier = 8; StockSpec b; b.denier = 80;
      auto ta = stockingWeave(a, 64), tb = stockingWeave(b, 64);
      CHECK(ta.size() == 64 * 64 * 4 && tb.size() == ta.size(), "size");
      double ma = 0, mb = 0; for (size_t i = 3; i < ta.size(); i += 4) { ma += ta[i]; mb += tb[i]; }
      CHECK(ma < mb * 0.5, "8 D much sheerer than 80 D");
      CHECK(denierAlpha(8) < denierAlpha(20) && denierAlpha(20) < denierAlpha(40) && denierAlpha(40) < denierAlpha(80), "monotonic");
      StockSpec c; c.seam = true; auto tc = stockingWeave(c, 128), td = stockingWeave(a, 128);
      CHECK(tc[3] > td[3], "the seam column is denser"); CHECK(stockingBand(a, 64).size() == 64 * 64 * 4, "band");
      a.lace = false; CHECK(stockingBand(a, 64) != stockingBand(b, 64), "elastic differs"); }

    // ---- the fabric: a patch of the thigh shell
    StockShell sh = make(body, kStockThigh);
    std::vector<uint32_t> weld, wtris; std::vector<V3> rest;
    stockWeld(sh, weld, rest, wtris);
    CHECK(rest.size() < sh.verts.size() && rest.size() > 100, "welded %zu of %zu", rest.size(), sh.verts.size());
    for (size_t i = 0; i < sh.verts.size(); ++i) CHECK((rest[weld[i]] - V3(sh.verts[i].pos[0], sh.verts[i].pos[1], sh.verts[i].pos[2])).length() < 1e-4f, "weld position");
    sh.indices = wtris;   // (the fabric's own triangles, on the welded vertices)
    StockFabric f; f.init(rest, sh.indices, 7);
    for (int i = 0; i < 120; ++i) f.step(1.0f / 60);
    CHECK(f.tension() < 0.05f && f.deadTriangles() == 0 && f.tornCount() == 0, "at rest: tension %.3f", f.tension());
    float maxErr = 0; for (size_t i = 0; i < rest.size(); ++i) maxErr = std::max(maxErr, (f.positions()[i] - rest[i]).length());
    CHECK(maxErr < 1e-4f, "rest fit %.6f", maxErr);
    // a pinch on the front of the thigh, dragged sideways in growing steps: tension rises with the distance, nothing is NaN, nothing tears yet
    const V3 where(0.083f, 0.1f, 0.75f);
    float lastT = 0; bool rose = true;
    for (float dist : {0.01f, 0.025f, 0.045f}) {
        StockFabric g; g.init(rest, sh.indices, 7);
        CHECK(g.grab(where, 0.02f, 0.05f), "grabbed");
        for (int i = 0; i < 90; ++i) { g.drag(where + V3(dist, 0, 0)); g.step(1.0f / 60); }
        for (const V3& p : g.positions()) CHECK(std::isfinite(p.x) && std::isfinite(p.y) && std::isfinite(p.z), "NaN");
        if (g.tension() < lastT - 1e-4f) rose = false;
        lastT = g.tension();
        CHECK(g.worstRatio() < 3.0f, "edges stay bounded %.2f", g.worstRatio());
    }
    CHECK(rose && lastT > 0.1f, "tension rises with the pull (%.3f)", lastT);
    // release: it springs back and re-fits the leg
    { StockFabric g; g.init(rest, sh.indices, 7); g.grab(where, 0.02f, 0.05f);
      for (int i = 0; i < 60; ++i) { g.drag(where + V3(0.04f, 0, 0)); g.step(1.0f / 60); }
      float stretched = 0; for (size_t i = 0; i < rest.size(); ++i) stretched = std::max(stretched, (g.positions()[i] - rest[i]).length());
      g.release();
      for (int i = 0; i < 240; ++i) g.step(1.0f / 60);
      float after = 0; for (size_t i = 0; i < rest.size(); ++i) after = std::max(after, (g.positions()[i] - rest[i]).length());
      CHECK(stretched > 0.01f && after < 0.0015f, "stretched %.4f then %.5f", stretched, after);
      CHECK(g.tension() < 0.05f, "tension back to %.3f", g.tension()); }
    // pulled hard: it tears (a run), deterministically for a seed; the dead triangles are a few cm long along the leg
    auto tornAt = [&](uint32_t seed, StockFabric& g) {
        g.init(rest, sh.indices, seed);
        g.grab(where, 0.02f, 0.05f);
        for (int i = 0; i < 240 && g.tornCount() == 0; ++i) { g.drag(where + V3(0.02f + 0.0006f * i, 0, 0)); g.step(1.0f / 60); }
    };
    StockFabric t1, t2, t3; tornAt(7, t1); tornAt(7, t2); tornAt(99, t3);
    CHECK(t1.tornCount() == 1 && t1.deadTriangles() > 2, "tore: %d runs, %zu dead", t1.tornCount(), t1.deadTriangles());
    CHECK(t1.alive() == t2.alive(), "the same seed makes the same run");
    CHECK(t1.alive() != t3.alive() || t1.deadTriangles() > 0, "another seed may differ");
    { float zlo = 1e9f, zhi = -1e9f; const auto& a = t1.alive();
      for (size_t t = 0; t < a.size(); ++t) if (!a[t]) for (int k = 0; k < 3; ++k) { float z = rest[sh.indices[t * 3 + k]].z; zlo = std::min(zlo, z); zhi = std::max(zhi, z); }
      CHECK(zhi - zlo > 0.02f && zhi - zlo < 0.25f, "the run is %.3f long", zhi - zlo); }
    // below full tension nothing tears
    { StockFabric g; g.init(rest, sh.indices, 7); g.grab(where, 0.02f, 0.05f); for (int i = 0; i < 90; ++i) { g.drag(where + V3(0.008f, 0, 0)); g.step(1.0f / 60); }
      CHECK(g.tornCount() == 0 && g.deadTriangles() == 0, "a gentle pull does not tear"); }
    // undo takes back the latest tear, repairAll the lot; the rim marks the torn edge
    { bool anyRim = false; for (uint8_t r : t1.rim()) anyRim |= r != 0; CHECK(anyRim, "a rim");
      // a second pull makes a second run (or grows it), undo takes only the latest
      t1.release(); for (int i = 0; i < 60; ++i) t1.step(1.0f / 60);
      size_t dead1 = t1.deadTriangles();
      t1.grab(V3(0.083f, 0.1f, 0.6f), 0.02f, 0.05f);
      for (int i = 0; i < 300 && t1.tornCount() < 2; ++i) { t1.drag(V3(0.083f, 0.1f, 0.6f) + V3(0.02f + 0.0006f * i, 0, 0)); t1.step(1.0f / 60); }
      CHECK(t1.tornCount() == 2 && t1.deadTriangles() > dead1, "a second run");
      t1.release();
      CHECK(t1.undo() && t1.tornCount() == 1 && t1.deadTriangles() == dead1, "undo takes back one tear: %zu vs %zu", t1.deadTriangles(), dead1);
      t1.repairAll(); CHECK(t1.deadTriangles() == 0 && t1.tornCount() == 0, "repaired"); CHECK(!t1.undo(), "nothing to undo"); }
    // a grab on nothing, and a stepping with junk, are harmless
    { StockFabric g; g.init(rest, sh.indices, 1); CHECK(!g.grab(V3(5, 5, 5), 0.02f, 0.1f), "miss"); g.step(0); g.step(-1); g.step(NAN); g.drag(V3(1, 1, 1)); g.release();
      for (const V3& p : g.positions()) CHECK(std::isfinite(p.x + p.y + p.z), "junk"); }
    // a different iteration count still holds (the governor lowers it)
    { StockFabric g; g.params.iterations = 2; g.init(rest, sh.indices, 7); g.grab(where, 0.02f, 0.05f);
      for (int i = 0; i < 90; ++i) { g.drag(where + V3(0.04f, 0, 0)); g.step(1.0f / 60); }
      for (const V3& p : g.positions()) CHECK(std::isfinite(p.x + p.y + p.z), "NaN"); }
    std::printf(fails ? "stockings_test: %d FAILED\n" : "stockings_test: all passed\n", fails);
    return fails ? 1 : 0;
}
