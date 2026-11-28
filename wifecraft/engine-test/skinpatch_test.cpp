// The fine skin patch's maths (engine/skinpatch.cpp) and the soft regions' tessellation (engine/softtess.cpp), no GL, no game files:
// build/skinpatch_test, exit 0 when all pass.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <map>
#include <vector>

#include "skinpatch.h"
#include "softtess.h"

using namespace wc;

static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; std::printf("FAIL %s:%d  ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static SkinPatch make(float T = 0.025f, float sp = 0.0033f, int sub = 6) {
    SkinPatch p; PatchSpec s; s.thickness = T; s.spacing = sp; s.substeps = sub; p.build(s); return p;
}
// the two fingertips of a pinch: material points at +-l0/2 on the x axis, the fingers at +-l/2 (or moved by `shift`)
static void pinchPads(SkinPatch& p, float l0, float l, float shift = 0, bool swap = false) {
    p.beginPads();
    for (int e = 0; e < 2; ++e) {
        PatchPad d;
        const int side = swap ? 1 - e : e;
        d.key = uint32_t(e + 1); d.mate = uint32_t(2 - e);
        d.x = (side ? 1.0f : -1.0f) * l * 0.5f + shift; d.y = 0; d.radius = 0.011f; d.push = 0.0025f; d.grip = 1; d.aspect = 1.15f;
        d.dirx = 1; d.diry = 0;
        p.addPad(d);
    }
    (void)l0;
}
static void run(SkinPatch& p, float l0, float l1, int closeTicks, int holdTicks, float shift = 0, bool swap = false) {
    for (int t = 0; t < closeTicks + holdTicks; ++t) {
        const float f = t < closeTicks ? float(t + 1) / float(closeTicks) : 1.0f;
        pinchPads(p, l0, l0 + (l1 - l0) * f, shift * f, swap);
        p.step(1.0f / 60.0f);
    }
}

int main() {
    // the rest holds
    {
        SkinPatch p = make();
        for (int t = 0; t < 120; ++t) { p.beginPads(); p.step(1.0f / 60.0f); }
        float m = 0; for (int j = 0; j < kPatchN; ++j) for (int i = 0; i < kPatchN; ++i) m = std::fmax(m, std::fmax(std::fabs(p.a(i, j)), std::fmax(std::fabs(p.b(i, j)), std::fabs(p.c(i, j)))));
        CHECK(m < 0.0005f, "rest drifted %.3f mm", m * 1000);
        CHECK(p.asleep(), "no pads: not asleep");
    }
    // a fold's height grows as the fingers close and stays inside the tissue
    {
        float rise[4]; const float gaps[4] = {0.04f, 0.03f, 0.02f, 0.015f};
        for (int k = 0; k < 4; ++k) {
            SkinPatch p = make(); run(p, 0.05f, gaps[k], 40, 40);
            rise[k] = p.maxRise();
            CHECK(p.finite(), "pinch %d not finite", k);
            CHECK(p.maxRise() <= 0.025f * 1.0f + 1e-5f, "rise %.1f mm above the thickness", p.maxRise() * 1000);
            CHECK(p.maxSink() >= -0.025f, "sink %.1f mm below the floor", p.maxSink() * 1000);
            std::printf("  pinch %.1f cm from 5: rise %.1f mm sink %.1f mm slide %.1f mm\n", gaps[k] * 100, p.maxRise() * 1000, p.maxSink() * 1000, p.maxSlide() * 1000);
        }
        CHECK(rise[0] < rise[1] && rise[1] < rise[2] + 1e-4f && rise[2] <= rise[3] + 1e-4f, "fold height not rising with the closing: %.1f %.1f %.1f %.1f", rise[0] * 1000, rise[1] * 1000, rise[2] * 1000, rise[3] * 1000);
        CHECK(rise[3] > 0.012f, "a 1.5 cm pinch folds only %.1f mm", rise[3] * 1000);
        CHECK(rise[0] < rise[3] * 0.8f, "no difference between a small and a full closing");
    }
    // fingers apart: nothing folds
    {
        SkinPatch p = make(); run(p, 0.03f, 0.05f, 40, 20);
        CHECK(p.maxRise() < 0.008f, "pulled apart still rises %.1f mm", p.maxRise() * 1000);   // (the skin outside the fingers bunches a little as they part: well under a closing pinch's 19 mm)
        CHECK(p.maxRise() < 0.4f * 0.0195f, "pulling apart folds as much as closing");
    }
    // a poke: the dent, a bulge round it, springing back
    {
        SkinPatch p = make();
        for (int t = 0; t < 40; ++t) { p.beginPads(); PatchPad d; d.key = 1; d.push = 0.007f; d.radius = 0.011f; p.addPad(d); p.step(1.0f / 60.0f); }
        CHECK(p.maxSink() < -0.004f && p.maxSink() > -0.0095f, "dent %.1f mm", p.maxSink() * 1000);
        CHECK(p.maxRise() > 0.0002f, "no bulge round the dent");
        int ticks = 0;
        for (; ticks < 180 && !p.asleep(); ++ticks) { p.beginPads(); p.step(1.0f / 60.0f); }
        CHECK(p.asleep(), "a poke did not settle in 3 s");
        std::printf("  poke released: asleep after %d ticks (%.0f ms)\n", ticks, ticks * 1000.0f / 60.0f);
        CHECK(ticks <= 60, "settling took %d ticks", ticks);
    }
    // release settling after a pinch
    {
        SkinPatch p = make(); run(p, 0.05f, 0.02f, 40, 40);
        int ticks = 0;
        for (; ticks < 240 && !p.asleep(); ++ticks) { p.beginPads(); p.step(1.0f / 60.0f); }
        CHECK(p.asleep(), "the pinch did not settle");
        CHECK(p.maxRise() < 0.0005f && p.maxSlide() < 0.0005f, "not back at rest: %.2f %.2f mm", p.maxRise() * 1000, p.maxSlide() * 1000);
        std::printf("  pinch released: asleep after %d ticks (%.0f ms)\n", ticks, ticks * 1000.0f / 60.0f);
        CHECK(ticks <= 120, "settling took %d ticks", ticks);
    }
    // determinism
    {
        SkinPatch a = make(), b = make(); run(a, 0.05f, 0.02f, 30, 20); run(b, 0.05f, 0.02f, 30, 20);
        bool same = true; for (int j = 0; j < kPatchN; ++j) for (int i = 0; i < kPatchN; ++i) if (a.c(i, j) != b.c(i, j) || a.a(i, j) != b.a(i, j)) same = false;
        CHECK(same, "two runs differ");
    }
    // swapping the fingers (the same two pads, the other way round) gives the same fold
    {
        SkinPatch a = make(), b = make(); run(a, 0.05f, 0.02f, 40, 40); run(b, 0.05f, 0.02f, 40, 40, 0, true);
        CHECK(std::fabs(a.maxRise() - b.maxRise()) < 0.1f * a.maxRise() + 1e-4f, "swapped fingers: %.1f vs %.1f mm", a.maxRise() * 1000, b.maxRise() * 1000);
        float d = 0; for (int j = 0; j < kPatchN; ++j) for (int i = 0; i < kPatchN; ++i) d = std::fmax(d, std::fabs(a.c(i, j) - b.c(i, j)));
        CHECK(d < 0.004f, "swapped fingers differ by %.1f mm somewhere", d * 1000);
    }
    // left-right symmetry of the fold (both fingers move alike), within the solver's sweep order
    {
        SkinPatch p = make(); run(p, 0.05f, 0.02f, 40, 40);
        float worst = 0; const int j = kPatchN / 2;
        for (int i = 4; i < kPatchN / 2; ++i) worst = std::fmax(worst, std::fabs(p.c(i, j) - p.c(kPatchN - 1 - i, j)));
        CHECK(worst < 0.003f, "fold not symmetric: %.1f mm", worst * 1000);
    }
    // violent input: NaN, enormous moves, enormous pushes, a thousand pads' keys
    {
        SkinPatch p = make();
        for (int t = 0; t < 120; ++t) {
            p.beginPads();
            for (int e = 0; e < 14; ++e) {
                PatchPad d; d.key = uint32_t(e + (t % 3) * 5); d.x = (t % 7 == 0 ? NAN : float((e * 37 + t * 11) % 200 - 100) * 0.1f); d.y = float((e * 53 + t) % 90 - 45) * 0.05f;
                d.push = t % 5 == 0 ? 5.0f : 0.05f; d.radius = (t % 4 == 0) ? 1e9f : 0.0f; d.grip = 7.0f; d.mate = uint32_t((e + 1) + (t % 3) * 5);
                p.addPad(d);
            }
            p.step(t % 9 == 0 ? 10.0f : (t % 11 == 0 ? NAN : 1.0f / 30.0f));
        }
        CHECK(p.finite(), "violent input left NaN");
        CHECK(p.maxRise() <= 0.025f * 1.2f && p.maxSink() >= -0.025f, "violent input out of bounds: %.1f %.1f", p.maxRise() * 1000, p.maxSink() * 1000);
    }
    // timing
    {
        for (int sub : {3, 5, 6}) {
            SkinPatch p = make(0.025f, 0.0033f, sub);
            run(p, 0.05f, 0.02f, 40, 20);
            const auto t0 = std::chrono::steady_clock::now();
            const int n = 300;
            for (int t = 0; t < n; ++t) { pinchPads(p, 0.05f, 0.02f); p.step(1.0f / 60.0f); }
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / n;
            std::printf("  one patch, %d substeps, a pinch held: %.3f ms per 1/60 s step on this machine\n", sub, ms);
            CHECK(ms < 3.0, "a patch step takes %.2f ms", ms);
        }
    }
    // tessellation: a strip of big triangles, the middle in the mask: no cracks, the edges short, the attributes kept
    {
        std::vector<M2Vertex> v; std::vector<uint32_t> idx;
        const int n = 6; const float h = 0.03f;
        for (int j = 0; j <= n; ++j) for (int i = 0; i <= n; ++i) {
            M2Vertex x{}; x.pos[0] = float(i) * h; x.pos[1] = float(j) * h; x.pos[2] = 0.0f; x.normal[2] = 1; x.uv[0] = float(i) / n; x.uv[1] = float(j) / n;
            x.bones[0] = i < 3 ? 1 : 2; x.weights[0] = 255; v.push_back(x);
        }
        for (int j = 0; j < n; ++j) for (int i = 0; i < n; ++i) {
            const uint32_t a = uint32_t(j * (n + 1) + i), b = a + 1, c = a + n + 1, d = c + 1;
            const uint32_t t[6] = {a, b, c, b, d, c}; idx.insert(idx.end(), t, t + 6);
        }
        SoftTess st; st.mask.assign(v.size(), 0);
        for (int j = 0; j <= n; ++j) for (int i = 1; i <= 4; ++i) st.mask[size_t(j * (n + 1) + i)] = 1;
        std::vector<uint32_t> out;
        const size_t before = idx.size() / 3;
        tessellateTriangles(v, idx.data(), idx.size(), 0.0095f, st, out);
        CHECK(out.size() / 3 > before * 4, "only %zu triangles from %zu", out.size() / 3, before);
        // watertight: every edge is shared by two triangles, or one on the outer border
        std::map<std::pair<uint32_t, uint32_t>, int> edge;
        for (size_t t = 0; t + 2 < out.size(); t += 3) for (int e = 0; e < 3; ++e) { uint32_t a = out[t + size_t(e)], b = out[t + size_t((e + 1) % 3)]; ++edge[{std::min(a, b), std::max(a, b)}]; }
        int bad = 0, border = 0;
        for (auto& kv : edge) {
            const M2Vertex &a = v[kv.first.first], &b = v[kv.first.second];
            const bool onBorder = (a.pos[0] == b.pos[0] && (a.pos[0] == 0 || a.pos[0] == n * h)) || (a.pos[1] == b.pos[1] && (a.pos[1] == 0 || a.pos[1] == n * h));
            if (kv.second > 2) ++bad;
            else if (kv.second == 1 && !onBorder) ++bad;
            border += kv.second == 1;
        }
        CHECK(bad == 0, "%d cracked or over-shared edges", bad);
        // area kept
        double area = 0;
        for (size_t t = 0; t + 2 < out.size(); t += 3) {
            const M2Vertex &a = v[out[t]], &b = v[out[t + 1]], &c = v[out[t + 2]];
            area += 0.5 * std::fabs((b.pos[0] - a.pos[0]) * (c.pos[1] - a.pos[1]) - (c.pos[0] - a.pos[0]) * (b.pos[1] - a.pos[1]));
        }
        CHECK(std::fabs(area - double(n * h) * n * h) < 1e-6, "area %.6f", area);
        // the masked middle's edges are short, the unmasked outer columns keep theirs (they only get the splits their neighbours need)
        float longest = 0;
        for (size_t t = 0; t + 2 < out.size(); t += 3) for (int e = 0; e < 3; ++e) {
            const uint32_t a = out[t + size_t(e)], b = out[t + size_t((e + 1) % 3)];
            if (a < st.mask.size() && b < st.mask.size() && st.mask[a] && st.mask[b]) {
                const float dx = v[a].pos[0] - v[b].pos[0], dy = v[a].pos[1] - v[b].pos[1];
                longest = std::fmax(longest, std::sqrt(dx * dx + dy * dy));
            }
        }
        CHECK(longest <= 0.0095f + 1e-5f, "a masked edge of %.1f mm is left", longest * 1000);
        bool norm = true, uvok = true;
        for (size_t i = (size_t(n) + 1) * (n + 1); i < v.size(); ++i) {
            const float l = std::sqrt(v[i].normal[0] * v[i].normal[0] + v[i].normal[1] * v[i].normal[1] + v[i].normal[2] * v[i].normal[2]);
            if (std::fabs(l - 1) > 1e-4f) norm = false;
            if (std::fabs(v[i].uv[0] - v[i].pos[0] / (n * h)) > 1e-4f || std::fabs(v[i].uv[1] - v[i].pos[1] / (n * h)) > 1e-4f) uvok = false;
            int sum = 0; for (int k = 0; k < 4; ++k) sum += v[i].weights[k];
            if (sum != 255) norm = false;
        }
        CHECK(norm, "a new vertex' normal or weights are off");
        CHECK(uvok, "a new vertex' uv is not the midpoint");
        std::printf("  tessellation: %zu -> %zu triangles, %zu vertices added, %d border edges\n", before, out.size() / 3, st.added, border);
    }
    std::printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
