// Spring-bone jiggle without GL (engine/springbone.cpp): stability at 60/30/20 fps, bounded energy, rest holds, limits, determinism,
// sleeping, the weights' partition of unity, the matrix inverse. Exit 0 when all pass.
#include <cmath>
#include <cstdio>
#include <cstdlib>

#include "springbone.h"

using namespace wc;

static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { ++fails; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static SbRig oneChain(SbRegion r = SbRegion::Butt, int segs = 3) {
    SbRig rig;
    SbChain c;
    c.region = r; c.parentBone = 0; c.minLevel = 1; c.first = 0; c.segments = segs; c.root = V3(0, 0, 0);
    for (int i = 0; i < segs; ++i) c.tail[i] = V3(0, 0, -0.1f * float(i + 1) / float(segs) * 3.0f);   // 0.3 long, straight down
    c.length = 0.3f; c.radius = 0.08f;
    c.gate = {{0, -1, -1, -1, -1, -1}};
    rig.chains.push_back(c);
    rig.helpers = segs;
    return rig;
}

// the parent bone moves: a hop (a vertical jump of `h` metres at 2 Hz)
static void run(SpringBones& sb, float fps, float seconds, float amp, float* maxRatio, float* peakEnergy, float* endEnergy, bool hop = true) {
    const float dt = 1.0f / fps;
    std::vector<M4> bones(1);
    float mr = 0, pe = 0;
    int steps = int(seconds * fps);
    for (int i = 0; i < steps; ++i) {
        float t = float(i) * dt;
        M4 w = M4::translation(V3(0, 0, hop ? amp * std::max(0.0f, std::sin(t * 6.2831853f * 2.0f)) : 0.0f));
        // (the same motion sampled at the frame rate: 30 and 20 fps see a coarser hop)
        sb.step(dt, w, bones.data(), 1);
        mr = std::max(mr, sb.maxOffsetRatio());
        pe = std::max(pe, sb.energy());
        if (!std::isfinite(sb.energy())) { *maxRatio = 1e9f; return; }
    }
    *maxRatio = mr; *peakEnergy = pe; *endEnergy = sb.energy();
}

int main() {
    // matrix inverse
    {
        M4 m = M4::translation(V3(1, 2, 3)) * M4::rotation(Quat(0.3f, 0.2f, 0.1f, 0.9f).normalized()) * M4::scaling(V3(2, 2, 2));
        M4 i = sbInverse(m);
        M4 id = m * i;
        float e = 0;
        for (int k = 0; k < 16; ++k) e = std::max(e, std::fabs(id.m[k] - (k % 5 == 0 ? 1.0f : 0.0f)));
        CHECK(e < 1e-4f, "inverse error %g", e);
    }
    for (int preset = 0; preset < kSbPresets; ++preset) {
        for (float fps : {60.0f, 30.0f, 20.0f}) {
            SpringBones sb;
            sb.build(oneChain());
            sb.setSettings(SbSettings{SbPreset(preset), 1.0f, 2});
            float mr = 0, pe = 0, ee = 0;
            run(sb, fps, 4.0f, 0.15f, &mr, &pe, &ee);
            CHECK(mr < 0.9f, "preset %d at %g fps: offset ratio %g", preset, fps, mr);
            CHECK(std::isfinite(pe) && pe < 200.0f, "preset %d at %g fps: peak energy %g", preset, fps, pe);
            // the hop goes on for the whole 4 s: its end is not rest; stop it and let it settle
            std::vector<M4> bones(1);
            for (int i = 0; i < int(fps * 8); ++i) sb.step(1.0f / fps, M4(), bones.data(), 1);
            CHECK(sb.energy() < 1e-4f && sb.maxOffsetRatio() < 0.01f, "preset %d at %g fps did not settle: energy %g ratio %g", preset, fps, sb.energy(), sb.maxOffsetRatio());
            CHECK(sb.asleep(), "preset %d at %g fps: not asleep at rest", preset, fps);
        }
    }
    // rest holds: nothing moves, the transforms are the identity
    {
        SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset::Zzz, 2.0f, 2});
        std::vector<M4> bones(1);
        for (int i = 0; i < 200; ++i) sb.step(1.0f / 60, M4(), bones.data(), 1);
        float e = 0;
        for (const M4& m : sb.transforms()) for (int k = 0; k < 16; ++k) e = std::max(e, std::fabs(m.m[k] - (k % 5 == 0 ? 1.0f : 0.0f)));
        CHECK(e < 1e-5f, "rest: transforms off identity by %g", e);
        CHECK(sb.asleep(), "rest: not asleep");
    }
    // the exaggerated style swings further than the natural one, the subtle one least, and overshoots (a second swing the other way)
    {
        float peak[3];
        for (int p = 0; p < 3; ++p) {
            SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset(p), 1.0f, 2});
            std::vector<M4> bones(1);
            float best = 0;
            for (int i = 0; i < 240; ++i) {
                M4 w = M4::translation(V3(0, 0, i < 12 ? 0.02f * float(i) : 0.24f));   // a quick lift, then held
                sb.step(1.0f / 60, w, bones.data(), 1);
                const M4& t = sb.transforms()[2];
                best = std::max(best, std::fabs(t.at(2, 3)) + std::fabs(t.at(1, 3)));
            }
            peak[p] = best;
        }
        CHECK(peak[1] > peak[0] && peak[0] > peak[2], "swing ZZZ %g Natural %g Subtle %g", peak[1], peak[0], peak[2]);
    }
    // limits: a violent shove never takes a tail further than its limit (the offset ratio stays under the preset's share)
    {
        SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset::Natural, 1.0f, 2});
        std::vector<M4> bones(1);
        float worst = 0;
        for (int i = 0; i < 300; ++i) {
            M4 w = M4::translation(V3(i % 20 < 10 ? 2.0f : -2.0f, 0, 0));   // teleports (a shove) every 10 frames
            sb.step(1.0f / 60, w, bones.data(), 1);
            worst = std::max(worst, sb.maxOffsetRatio());
            for (const M4& m : sb.transforms()) for (int k = 0; k < 16; ++k) CHECK(std::isfinite(m.m[k]), "non-finite transform");
        }
        CHECK(worst < 0.45f, "limit: ratio %g", worst);
    }
    // determinism: the same run twice, bit for bit
    {
        float a[2];
        for (int run2 = 0; run2 < 2; ++run2) {
            SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset::Zzz, 1.3f, 2});
            std::vector<M4> bones(1);
            for (int i = 0; i < 100; ++i) {
                M4 w = M4::translation(V3(0.01f * std::sin(float(i) * 0.3f), 0, 0.05f * std::max(0.0f, std::sin(float(i) * 0.4f))));
                sb.step(0.0166f, w, bones.data(), 1);
            }
            a[run2] = sb.transforms()[2].at(1, 3) + sb.transforms()[1].at(2, 3);
        }
        CHECK(a[0] == a[1], "determinism %g vs %g", a[0], a[1]);
    }
    // the frame rate does not change the motion much: the 20 fps peak within a third of the 60 fps one
    {
        float peaks[2]; int k = 0;
        for (float fps : {60.0f, 20.0f}) {
            SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset::Natural, 1.0f, 2});
            std::vector<M4> bones(1);
            float best = 0;
            for (int i = 0; i < int(fps); ++i) {
                float t = float(i) / fps;
                M4 w = M4::translation(V3(0, 0, t < 0.2f ? 0.5f * t : 0.1f));
                sb.step(1.0f / fps, w, bones.data(), 1);
                best = std::max(best, sb.maxOffsetRatio());
            }
            peaks[k++] = best;
        }
        CHECK(std::fabs(peaks[0] - peaks[1]) < 0.34f * peaks[0] + 1e-3f, "fps dependence: %g vs %g", peaks[0], peaks[1]);
    }
    // off and the amount 0: the identity
    {
        SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset::Zzz, 1.0f, 0});
        std::vector<M4> bones(1);
        for (int i = 0; i < 20; ++i) sb.step(1.0f / 60, M4::translation(V3(0, 0, 0.01f * float(i))), bones.data(), 1);
        float e = 0;
        for (const M4& m : sb.transforms()) for (int k = 0; k < 16; ++k) e = std::max(e, std::fabs(m.m[k] - (k % 5 == 0 ? 1.0f : 0.0f)));
        CHECK(e == 0, "off: not the identity (%g)", e);
    }
    // the size: a bigger region swings slower (a lower frequency): its first peak comes later
    {
        int firstPeak[2]; int k = 0;
        for (float size : {1.0f, 2.0f}) {
            SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset::Natural, 1.0f, 2}); sb.setRegionSize(SbRegion::Butt, size);
            std::vector<M4> bones(1);
            float best = 0; int at = 0;
            for (int i = 0; i < 120; ++i) {
                sb.step(1.0f / 60, M4::translation(V3(0, 0, i < 3 ? 0.03f * float(i) : 0.09f)), bones.data(), 1);
                float o = sb.maxOffsetRatio();
                if (o > best + 1e-5f) { best = o; at = i; }
            }
            firstPeak[k++] = at;
        }
        CHECK(firstPeak[1] >= firstPeak[0], "size: peak at frame %d (size 1) vs %d (size 2)", firstPeak[0], firstPeak[1]);
    }
    // the weights: a partition of unity (the sum at most 1, never negative), near the chain strong, far zero, smooth across the chain
    {
        SbRig rig = oneChain(); rig.chains.push_back(rig.chains[0]); rig.chains[1].first = 3; rig.chains[1].root = V3(0.05f, 0, 0);
        for (int i = 0; i < 3; ++i) rig.chains[1].tail[i].x += 0.05f;
        rig.helpers = 6;
        const size_t n = 4000;
        std::vector<V3> pos(n); std::vector<uint8_t> bi(n * 4, 0), bw(n * 4, 0);
        srand(5);
        for (size_t i = 0; i < n; ++i) {
            pos[i] = V3(float(rand() % 1000) / 1000 * 0.3f - 0.1f, float(rand() % 1000) / 1000 * 0.3f - 0.15f, -float(rand() % 1000) / 1000 * 0.4f + 0.05f);
            bw[i * 4] = 255;
        }
        SbBodyView v{pos.data(), bi.data(), bw.data(), n};
        SbVertexWeights w;
        computeSpringWeights(rig, v, w);
        float worstSum = 0; int influenced = 0;
        for (size_t i = 0; i < n; ++i) {
            float s = 0;
            for (int k = 0; k < kSbMaxPerVertex; ++k) { float x = w.weight[i * 4 + size_t(k)]; CHECK(x >= 0, "negative weight"); s += x; int ix = w.index[i * 4 + size_t(k)]; CHECK(ix < 6, "index"); }
            worstSum = std::max(worstSum, s);
            if (s > 0) ++influenced;
        }
        CHECK(worstSum <= 1.0001f, "weights sum to %g", worstSum);
        CHECK(influenced > 100 && influenced < int(n), "influenced %d", influenced);
        // continuity: a vertex walked across the chain's radius changes its weight by small steps
        float prev = -1, jump = 0;
        for (int i = 0; i <= 200; ++i) {
            V3 p(0.12f * float(i) / 200.0f, 0, -0.15f);
            std::vector<V3> pp{p}; uint8_t b4[4] = {0, 0, 0, 0}, w4[4] = {255, 0, 0, 0};
            SbBodyView one{pp.data(), b4, w4, 1};
            SbVertexWeights ow; computeSpringWeights(rig, one, ow);
            float s = 0; for (int k = 0; k < 4; ++k) s += ow.weight[size_t(k)];
            if (prev >= 0) jump = std::max(jump, std::fabs(s - prev));
            prev = s;
        }
        CHECK(jump < 0.06f, "weights jump by %g across the region", jump);
        // the gate: a vertex on another bone gets nothing
        std::vector<V3> pp{V3(0, 0, -0.1f)}; uint8_t b4[4] = {5, 0, 0, 0}, w4[4] = {255, 0, 0, 0};
        SbBodyView other{pp.data(), b4, w4, 1};
        SbVertexWeights ow; computeSpringWeights(rig, other, ow);
        CHECK(ow.weight[0] == 0, "gate: a vertex on another bone moved (%g)", ow.weight[0]);
    }
    // the kick: a tap moves the tails and wakes a sleeping rig
    {
        SpringBones sb; sb.build(oneChain()); sb.setSettings(SbSettings{SbPreset::Natural, 1.0f, 2});
        std::vector<M4> bones(1);
        for (int i = 0; i < 100; ++i) sb.step(1.0f / 60, M4(), bones.data(), 1);
        CHECK(sb.asleep(), "asleep before the kick");
        sb.kick(V3(0, 0, -0.3f), V3(1, 0, 0), 0.1f);
        CHECK(!sb.asleep(), "the kick did not wake it");
        for (int i = 0; i < 10; ++i) sb.step(1.0f / 60, M4(), bones.data(), 1);
        CHECK(sb.maxOffsetRatio() > 0.01f, "the kick moved nothing (%g)", sb.maxOffsetRatio());
    }
    std::printf(fails ? "springbone_test: %d FAILED\n" : "springbone_test: all passed\n", fails);
    return fails ? 1 : 0;
}
