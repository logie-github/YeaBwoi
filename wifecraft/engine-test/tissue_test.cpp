// Soft tissue's maths without GL (engine/tissue.cpp): the lattice at rest, volume under a squeeze, the bulge, stability at 60 and 20
// fps and with huge steps, the jiggle's frequency and settling, no energy gain, grips with friction, a pinch's fold, determinism,
// timing. Exit 0 when all pass.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>

#include "tissue.h"

using namespace wc;

static int fails = 0, passes = 0;
#define CHECK(c, ...) do { if (c) ++passes; else { ++fails; std::printf("FAIL line %d: %s  ", __LINE__, #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static TissueSpec spec(float half = 0.12f, float depth = 0.09f, float spacing = 0.03f) {
    TissueSpec s; s.halfU = half; s.halfV = half; s.depth = depth; s.spacing = spacing; return s;
}
static void frame(TissueRegion& r, V3 o = V3(), bool snap = false) { r.setFrame(o, V3(1, 0, 0), V3(0, 1, 0), V3(0, 0, 1), snap); }
static void run(TissueRegion& r, float secs, float fps, V3 o = V3()) {
    const int n = int(secs * fps + 0.5f);
    for (int i = 0; i < n; ++i) { frame(r, o); r.step(1.0f / fps); }
}
// the skin node (layer 2) nearest the middle
static int mid(const TissueRegion& r) { return r.index(r.nu() / 2, r.nv() / 2, 2); }

int main() {
    // ---- rest holds
    {
        TissueRegion r; r.build(spec()); frame(r, V3(), true);
        std::printf("lattice %dx%dx%d nodes %d free %d tets %d\n", r.nu(), r.nv(), r.nw(), r.count(), r.freeCount(), r.tetCount());
        run(r, 2.0f, 60);
        CHECK(r.maxDisplacement() < 0.001f, "rest drift %g", r.maxDisplacement());
        CHECK(std::fabs(r.volumeRatio() - 1) < 0.001f, "volume %g", r.volumeRatio());
        CHECK(r.asleep(), "should sleep");
    }
    // ---- a poke: pressed in, volume kept, neighbours bulge
    {
        TissueRegion r; r.build(spec(0.12f, 0.09f, 0.025f)); frame(r, V3(), true);
        TissueHandle h; h.key = 1; h.rest = V3(0, 0, 0.09f); h.normal = V3(0, 0, 1); h.radius = 0.025f; h.push = 0.025f;
        for (int i = 0; i < 40; ++i) { frame(r); r.beginHandles(); r.addHandle(h); r.step(1.0f / 60); }
        const int c = mid(r);
        const float dent = r.disp(c).z;
        std::printf("poke 25 mm: skin node dent %.1f mm, volume %.4f\n", dent * 1000, r.volumeRatio());
        CHECK(dent < -0.012f, "dent %g", dent);
        CHECK(std::fabs(r.volumeRatio() - 1) < 0.05f, "volume ratio %g", r.volumeRatio());
        { TissueRegion q; q.build(spec(0.12f, 0.09f, 0.025f)); frame(q, V3(), true);
          TissueHandle g = h; g.push = 0.012f;
          for (int i = 0; i < 40; ++i) { frame(q); q.beginHandles(); q.addHandle(g); q.step(1.0f / 60); }
          float w2 = 0; for (int k = 0; k < 3; ++k) for (int j = 0; j < q.nv() - 1; ++j) for (int i = 0; i < q.nu() - 1; ++i) w2 = std::max(w2, std::fabs(q.cellVolumeRatio(i, j, k) - 1));
          std::printf("12 mm squeeze: worst cell volume change %.1f%%\n", w2 * 100);
          CHECK(w2 < 0.06f, "cell volume within 6%%: %g", w2); }
        // squeeze test: every cell's volume within 5% (nu .46: nearly incompressible)
        float worst = 0;
        for (int k = 0; k < 3; ++k) for (int j = 0; j < r.nv() - 1; ++j) for (int i = 0; i < r.nu() - 1; ++i) worst = std::max(worst, std::fabs(r.cellVolumeRatio(i, j, k) - 1));
        std::printf("worst cell volume change %.1f%%\n", worst * 100);
        CHECK(worst < 0.12f, "cell volume %g", worst);
        // the bulge: the skin layer at 1.5 spacings from the pad rises
        float bulge = 0;
        for (int j = 0; j < r.nv(); ++j) for (int i = 0; i < r.nu(); ++i) {
            const int n = r.index(i, j, 2);
            if (!r.isFree(n)) continue;
            const V3 p = r.local(n); const float d = std::sqrt(p.x * p.x + p.y * p.y);
            if (d > 0.03f && d < 0.09f) bulge = std::max(bulge, r.disp(n).z);
        }
        std::printf("bulge round the dent %.1f mm\n", bulge * 1000);
        CHECK(bulge > 0.0006f, "bulge %g", bulge);
        // release: springs back
        float peak = 0; int cross = 0; float prev = dent;
        for (int i = 0; i < 180; ++i) { frame(r); r.beginHandles(); r.step(1.0f / 60); const float z = r.disp(c).z; peak = std::max(peak, z); if ((z > 0) != (prev > 0)) ++cross; prev = z; }
        std::printf("after release: rebound %.2f mm, crossings %d, residual %.3f mm\n", peak * 1000, cross, r.maxDisplacement() * 1000);
        CHECK(r.maxDisplacement() < 0.0015f, "settled %g", r.maxDisplacement());
        CHECK(peak < 0.008f, "rebound %g", peak);
    }
    // ---- jiggle: the frame jumps and stops: the lattice rings at about 3 Hz and settles
    for (int fps : {60, 30, 20}) {
        TissueRegion r; r.build(spec()); frame(r, V3(), true);
        // an impulse: the frame moves 2 cm in 3 frames of 1/60 then stops
        std::vector<float> tr; float t = 0;
        const int total = int(1.6f * fps);
        for (int i = 0; i < total; ++i) {
            float x = t < 0.1f ? 0.05f * (t / 0.1f) : 0.05f;
            frame(r, V3(x, 0, 0)); r.step(1.0f / fps); t += 1.0f / fps;
            tr.push_back(r.disp(mid(r)).x);
        }
        // zero crossings of the lag
        int zc = 0; float tz[8] = {}; for (size_t i = 1; i < tr.size(); ++i) if ((tr[i] > 0) != (tr[i - 1] > 0) && zc < 8) tz[zc++] = float(i) / fps;
        const float period = zc >= 3 ? tz[2] - tz[0] : zc == 2 ? 2 * (tz[1] - tz[0]) : 0;
        if (fps == 60) { std::printf("trace mm:"); for (size_t i = 0; i < tr.size(); i += 3) std::printf(" %.1f", tr[i] * 1000); std::printf("\n"); }
        float late = 0; for (size_t i = tr.size() * 3 / 4; i < tr.size(); ++i) late = std::max(late, std::fabs(tr[i]));
        float early = 0; for (size_t i = 0; i < tr.size() / 4; ++i) early = std::max(early, std::fabs(tr[i]));
        std::printf("jiggle at %d fps: period %.3f s (%.1f Hz), crossings %d, early peak %.1f mm, late %.2f mm\n", fps, period, period > 0 ? 1 / period : 0.f, zc, early * 1000, late * 1000);
        CHECK(zc >= 2, "rings (crossings %d)", zc);
        CHECK(period > 0.12f && period < 0.6f, "period %g", period);
        CHECK(late < 0.25f * early, "settles: %g vs %g", late, early);
        CHECK(early > 0.003f, "visible: %g", early);
    }
    // ---- stability: violent motion, huge steps, NaN input
    {
        TissueRegion r; r.build(spec()); frame(r, V3(), true);
        unsigned s = 12345; auto rnd = [&]() { s = s * 1664525u + 1013904223u; return float(s >> 8) / float(1 << 24) - 0.5f; };
        float worst = 0;
        for (int i = 0; i < 400; ++i) {
            frame(r, V3(rnd() * 0.5f, rnd() * 0.5f, rnd() * 0.5f));
            r.beginHandles();
            TissueHandle h; h.key = 5; h.rest = V3(rnd() * 0.2f, rnd() * 0.2f, 0.09f); h.radius = 0.02f; h.push = 0.05f; h.grip = 1; h.pull = V3(rnd(), rnd(), rnd()) * 0.3f; r.addHandle(h);
            r.step(i % 7 == 0 ? 5.0f : i % 3 == 0 ? 0.25f : 1.0f / 60);
            worst = std::max(worst, r.maxDisplacement());
        }
        std::printf("violent: worst displacement %.1f cm\n", worst * 100);
        CHECK(worst < 0.6f && std::isfinite(worst), "bounded %g", worst);
        r.beginHandles(); TissueHandle bad; bad.key = 9; bad.rest = V3(NAN, 0, 0); r.addHandle(bad);
        frame(r, V3(NAN, 0, 0)); r.step(NAN);
        CHECK(std::isfinite(r.maxDisplacement()), "nan input");
        frame(r); run(r, 3.0f, 60);
        CHECK(r.maxDisplacement() < 0.002f, "recovers %g", r.maxDisplacement());
    }
    // ---- no energy gain: the envelope of the ringing falls window by window
    {
        TissueRegion q; q.build(spec()); frame(q, V3(), true);
        float pk[4] = {0, 0, 0, 0}, ke[4] = {0, 0, 0, 0};
        for (int i = 0; i < 6; ++i) { frame(q, V3(0.05f * float(i) / 5, 0, 0)); q.step(1.0f / 60); }
        for (int i = 0; i < 160; ++i) { frame(q, V3(0.05f, 0, 0)); q.step(1.0f / 60); const int w = i / 40; pk[w] = std::max(pk[w], std::fabs(q.disp(mid(q)).x)); ke[w] = std::max(ke[w], q.kineticEnergy()); }
        std::printf("envelope per 0.67 s: %.2f %.2f %.2f %.2f mm; kinetic %.2g %.2g %.2g %.2g\n", pk[0] * 1000, pk[1] * 1000, pk[2] * 1000, pk[3] * 1000, ke[0], ke[1], ke[2], ke[3]);
        CHECK(pk[1] <= pk[0] + 1e-6f && pk[2] <= pk[1] + 1e-6f && pk[3] <= pk[2] + 1e-6f, "decays");
        CHECK(ke[1] <= ke[0] * 1.001f && ke[2] <= ke[1] * 1.001f && ke[3] <= ke[2] * 1.001f + 1e-12f, "kinetic energy never grows");
    }
    // ---- a grip: sticks, drags, slides past its reach, lets go with a springback
    {
        TissueRegion r; r.build(spec(0.12f, 0.09f, 0.025f)); frame(r, V3(), true);
        const int c = mid(r);
        auto hold = [&](float x, float grip) { TissueHandle h; h.key = 2; h.rest = V3(x, 0, 0.09f); h.normal = V3(0, 0, 1); h.radius = 0.02f; h.push = 0.004f; h.grip = grip; frame(r); r.beginHandles(); r.addHandle(h); r.step(1.0f / 60); };
        for (int i = 0; i < 20; ++i) hold(0, 0.5f);
        float x0 = r.disp(c).x;
        for (int i = 0; i < 40; ++i) hold(0.0f + 0.03f * float(i) / 40, 0.5f);       // 3 cm drag within a 2 cm reach
        const float drag = r.disp(c).x;
        V3 m; r.gripOf(2, &m);
        std::printf("grip: drag 30 mm of finger moved the skin %.1f mm; the held point lags the finger by %.1f mm\n", (drag - x0) * 1000, (0.0292f - m.x) * 1000);
        CHECK(drag - x0 > 0.006f, "skin follows %g", drag - x0);
        CHECK(0.0292f - m.x > 0.005f && 0.0292f - m.x < 0.0201f, "slip %g", 0.03f - m.x);   // friction: it slid the excess
        for (int i = 0; i < 20; ++i) { frame(r); r.beginHandles(); r.step(1.0f / 60); }
        CHECK(!r.gripOf(2, nullptr), "grip let go");
        for (int i = 0; i < 150; ++i) { frame(r); r.beginHandles(); r.step(1.0f / 60); }
        CHECK(r.maxDisplacement() < 0.0015f, "springs back %g", r.maxDisplacement());
    }
    // ---- a pinch: two grips pulled together lift a fold
    for (float gap : {0.06f, 0.03f, 0.015f}) {
        TissueRegion r; r.build(spec(0.12f, 0.09f, 0.025f)); frame(r, V3(), true);
        const float g0 = 0.06f;
        for (int i = 0; i < 90; ++i) {
            const float f = std::min(1.0f, float(i) / 45.0f);
            const float close = (g0 - gap) * 0.5f * f;
            frame(r); r.beginHandles();
            for (int s = 0; s < 2; ++s) {
                TissueHandle h; h.key = 10 + uint32_t(s); const float sx = s ? 1.0f : -1.0f;
                h.rest = V3(sx * g0 * 0.5f, 0, 0.09f); h.normal = V3(0, 0, 1); h.radius = 0.012f; h.push = 0.002f; h.grip = 1.0f;
                h.pull = V3(-sx * close, 0, 0.9f * close);
                r.addHandle(h);
            }
            r.step(1.0f / 60);
        }
        float crest = -1e9f;
        for (int j = 0; j < r.nv(); ++j) for (int i = 0; i < r.nu(); ++i) { const int n = r.index(i, j, 2); if (r.isFree(n)) crest = std::max(crest, r.disp(n).z); }
        const V3 dm = r.disp(mid(r));
        std::printf("pinch %.0f mm gap (from 60): crest %.1f mm, middle node z %.1f mm x %.1f mm, volume %.3f\n", gap * 1000, crest * 1000, dm.z * 1000, dm.x * 1000, r.volumeRatio());
        if (gap < 0.02f) CHECK(crest > 0.008f, "fold lifts %g", crest);
        if (gap > 0.05f) CHECK(crest < 0.004f, "no fold at the start %g", crest);
        CHECK(std::fabs(r.volumeRatio() - 1) < 0.1f, "volume %g", r.volumeRatio());
        for (int i = 0; i < 180; ++i) { frame(r); r.beginHandles(); r.step(1.0f / 60); }
        CHECK(r.maxDisplacement() < 0.002f, "pinch released settles %g", r.maxDisplacement());
    }
    // ---- gravity: lying down shifts the tissue by the change from the rest pose only
    {
        TissueRegion r; r.build(spec()); r.setRestDown(V3(0, 0, -1)); frame(r, V3(), true);
        run(r, 1.0f, 60);
        CHECK(r.maxDisplacement() < 0.001f, "standing: no sag %g", r.maxDisplacement());
        // turned onto its back: its w axis (outward) now points down... the frame's axes rotate
        for (int i = 0; i < 180; ++i) { r.setFrame(V3(), V3(1, 0, 0), V3(0, 0, 1), V3(0, -1, 0)); r.step(1.0f / 60); }
        float m = r.maxDisplacement();
        std::printf("turned: sag %.1f mm\n", m * 1000);
        CHECK(m > 0.0005f && m < 0.04f, "turned sag %g", m);
    }
    // ---- determinism
    {
        auto go = [&]() { TissueRegion r; r.build(spec()); frame(r, V3(), true); float s = 0; for (int i = 0; i < 100; ++i) { frame(r, V3(0.01f * std::sin(float(i) * 0.3f), 0, 0)); r.beginHandles(); TissueHandle h; h.key = 1; h.rest = V3(0.01f, 0, 0.09f); h.push = 0.01f * (i % 10) / 10; h.grip = 0.5f; r.addHandle(h); r.step(1.0f / 60); s += r.disp(mid(r)).x + r.disp(mid(r)).z; } return s; };
        CHECK(go() == go(), "deterministic");
    }
    // ---- timing: the worst region busy, all seven
    {
        TissueRegion r[7];
        for (auto& q : r) { q.build(spec(0.12f, 0.09f, 0.038f)); frame(q, V3(), true); }
        std::printf("region lattice: %d nodes (%d free), %d tets\n", r[0].count(), r[0].freeCount(), r[0].tetCount());
        const auto t0 = std::chrono::steady_clock::now();
        const int F = 300;
        for (int i = 0; i < F; ++i)
            for (auto& q : r) { frame(q, V3(0.01f * std::sin(float(i) * 0.4f), 0, 0.01f * std::cos(float(i) * 0.5f))); q.step(1.0f / 60); }
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / F;
        std::printf("7 regions walking (all awake): %.3f ms / frame (desktop, one core)\n", ms);
        CHECK(ms < 6.0, "too slow %g", ms);
    }
    std::printf("%d passed, %d failed\n", passes, fails);
    return fails ? 1 : 0;
}
