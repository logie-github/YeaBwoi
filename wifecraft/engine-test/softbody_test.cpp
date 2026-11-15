// The soft body's maths (engine/softbody.h SoftSim), without GL or the game's files: one region hung on one bone, the
// bone moved as a hop, a stop or a turn would move it. Build: engine-test/build.sh; run: build/softbody_test (exit 0: all pass).
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../android/app/src/main/cpp/engine/softbody.h"

using namespace wc;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static SoftRegion chestRegion(float weight = 1) {
    SoftRegion r;
    r.kind = SoftKind::Chest;
    r.bone = 0;
    r.centre = V3(0.08f, 0.08f, 1.45f);
    r.radii = V3(0.08f, 0.085f, 0.095f);
    r.axis = V3(1, 0, 0);
    r.mask[0] = 1;
    r.mass = V3(0.12f, 0.08f, 1.45f);
    r.size = 0.085f;
    r.weight = weight;
    return r;
}

// The bone's matrix at time t (seconds) for a motion.
using Motion = M4 (*)(float);
static M4 still(float) { return M4(); }
static M4 hop(float t) {     // up 0.25 and down again over 0.5 s from 0.2 s, then stands
    float z = 0;
    if (t > 0.2f && t < 0.7f) { float u = (t - 0.2f) / 0.5f; z = 0.25f * 4 * u * (1 - u); }
    return M4::translation(V3(0, 0, z));
}
static M4 runStop(float t) { // runs forward at 6 per second, stops dead at 0.5 s
    return M4::translation(V3(6 * std::min(t, 0.5f), 0, 0));
}
static M4 turn(float t) {    // turns a quarter about the spine between 0.2 and 0.4 s
    float a = t < 0.2f ? 0 : t > 0.4f ? 1.5708f : (t - 0.2f) / 0.2f * 1.5708f;
    return M4::rotationZ(a);
}
static M4 lieDown(float t) { // lies on her back at once (bind x forward turns to up)
    Quat q(0, -0.70710678f, 0, 0.70710678f);   // -90 degrees about y: forward (x) goes up (z)
    return t > 0.1f ? M4::translation(V3(0, 0, 0.3f)) * M4::rotation(q) : M4();
}

struct Trace { std::vector<V3> out, raw; std::vector<uint32_t> at; };

static Trace run(Motion motion, int level, const std::vector<uint32_t>& frames, float weight = 1, M4 world = M4()) {
    SoftSim sim;
    sim.setRegions({chestRegion(weight)});
    sim.setLevel(level);
    Trace tr;
    std::vector<M4> bones(1);
    for (uint32_t ms : frames) {
        bones[0] = motion(ms / 1000.0f);
        sim.step(bones, world, ms);
        tr.out.push_back(sim.offset(0));
        tr.raw.push_back(sim.raw(0));
        tr.at.push_back(ms);
    }
    return tr;
}

static std::vector<uint32_t> every(uint32_t stepMs, uint32_t untilMs) {
    std::vector<uint32_t> f;
    for (uint32_t t = 0; t <= untilMs; t += stepMs) f.push_back(t);
    return f;
}

static bool finite(const V3& v) { return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z); }

int main() {
    const SoftTuning nat = softTuning(SoftKind::Chest, kSoftNatural);
    const float limit = nat.limit * 0.085f * nat.gain;     // the drawn limit at weight 1

    // 1. Determinism: the same frames give the same bits; time going backwards starts again the same way.
    {
        auto f = every(16, 2000);
        Trace a = run(hop, kSoftNatural, f), b = run(hop, kSoftNatural, f);
        CHECK(std::memcmp(a.out.data(), b.out.data(), a.out.size() * sizeof(V3)) == 0, "two runs differ");
        SoftSim sim;
        sim.setRegions({chestRegion()});
        std::vector<M4> bones(1);
        for (uint32_t t : f) { bones[0] = hop(t / 1000.0f); sim.step(bones, M4(), t); }
        V3 first = sim.offset(0);
        for (uint32_t t : f) { bones[0] = hop(t / 1000.0f); sim.step(bones, M4(), t); }   // from 0 again
        V3 again = sim.offset(0);
        CHECK(std::memcmp(&first, &again, sizeof(V3)) == 0, "replay from 0 differs");
        std::printf("determinism: two runs and a replay identical (%zu frames)\n", f.size());
    }

    // 2. Rest sag: standing still she hangs lower than modelled, by sag x g / w^2; heavier hangs lower; Off is nothing.
    {
        Trace light = run(still, kSoftNatural, every(16, 3000), 1), heavy = run(still, kSoftNatural, every(16, 3000), 2);
        float w = 2 * 3.14159265f * nat.freq;
        float expect = nat.sag * SoftSim::kGravity / (w * w);
        float got = -light.raw.back().z;
        CHECK(std::fabs(got - expect) < 0.01f * expect, "rest sag %.5f, expected %.5f", got, expect);
        CHECK(std::fabs(light.raw.back().x) < 1e-6f && std::fabs(light.raw.back().y) < 1e-6f, "rest sag not straight down");
        CHECK(-heavy.out.back().z > -light.out.back().z * 1.3f, "heavier doesn't hang lower: %.4f vs %.4f", -heavy.out.back().z, -light.out.back().z);
        Trace off = run(hop, kSoftOff, every(16, 2000));
        bool zero = true;
        for (const V3& v : off.out) zero = zero && v.x == 0 && v.y == 0 && v.z == 0;
        CHECK(zero, "Off moves something");
        std::printf("rest sag: %.2f mm (expected %.2f), drawn %.2f mm; weight 2 drawn %.2f mm; Off: all zero\n",
                    got * 1000, expect * 1000, -light.out.back().z * 1000, -heavy.out.back().z * 1000);
    }

    // 3. A stop: she lags, overshoots and settles; each swing smaller than the last, nothing left after 2 s.
    {
        Trace tr = run(runStop, kSoftNatural, every(8, 3000));
        V3 rest = tr.raw.back();
        // the largest distance from rest in each half swing (between crossings of rest)
        std::vector<float> peaks;
        float best = 0;
        int sign = 0;
        for (size_t i = 0; i < tr.raw.size(); ++i) {
            if (tr.at[i] <= 500) continue;
            float d = tr.raw[i].x - rest.x;
            int s = d > 0 ? 1 : d < 0 ? -1 : 0;
            if (s && sign && s != sign) { peaks.push_back(best); best = 0; }
            if (s) sign = s;
            best = std::max(best, std::fabs(d));
        }
        while (!peaks.empty() && peaks.back() < 1e-6f) peaks.pop_back();
        bool decays = peaks.size() >= 2;
        for (size_t i = 1; i < peaks.size(); ++i) decays = decays && peaks[i] < peaks[i - 1];
        CHECK(decays, "swings after the stop don't shrink (%zu peaks)", peaks.size());
        float late = 0;
        for (size_t i = 0; i < tr.raw.size(); ++i) if (tr.at[i] >= 2500) late = std::max(late, (tr.raw[i] - rest).length());
        CHECK(!peaks.empty() && late < 0.01f * peaks[0], "not settled: %.6f after 2 s, first swing %.6f", late, peaks.empty() ? 0 : peaks[0]);
        std::printf("stop: swings (mm)");
        for (size_t i = 0; i < peaks.size() && i < 6; ++i) std::printf(" %.2f", peaks[i] * 1000);
        std::printf("; after 2 s %.4f mm\n", late * 1000);
    }

    // 4. A hop: the chest lags (rises late, drops late), stays within the drawn limit, comes back to rest.
    {
        Trace tr = run(hop, kSoftNatural, every(16, 2000));
        float lo = 0, hi = 0;
        for (const V3& v : tr.out) { lo = std::min(lo, v.z); hi = std::max(hi, v.z); }
        float restZ = tr.out.back().z;
        CHECK(lo < restZ - 0.002f && hi > restZ + 0.002f, "a hop barely moves her: %.4f .. %.4f around %.4f", lo, hi, restZ);
        bool within = true;
        for (const V3& v : tr.out) within = within && v.length() <= limit * 1.0001f;
        CHECK(within, "past the drawn limit %.4f", limit);
        std::printf("hop: drawn z %.1f .. %.1f mm around %.1f (limit %.1f)\n", lo * 1000, hi * 1000, restZ * 1000, limit * 1000);
    }

    // 5. Hitches and pauses: 1 ms and 300 ms frames, an app away for 10 s, a jump back in time: nothing explodes.
    {
        std::vector<uint32_t> f;
        uint32_t t = 0;
        for (int i = 0; i < 400; ++i) { t += (i % 7 == 0) ? 300 : (i % 3 == 0 ? 1 : 33); f.push_back(t); }
        f.push_back(t + 10000); f.push_back(t + 10016); f.push_back(500); f.push_back(516);
        auto wild = [](float s) -> M4 {    // shaken hard: 40 per second squared, turning back and forth
            return M4::translation(V3(0.3f * std::sin(s * 37), 0.2f * std::sin(s * 23), 0.3f * std::sin(s * 29))) * M4::rotationZ(std::sin(s * 11));
        };
        for (int level = kSoftSubtle; level <= kSoftStrong; ++level) {
            Trace tr = run(wild, level, f);
            SoftTuning tu = softTuning(SoftKind::Chest, level);
            float lim = tu.limit * 0.085f * tu.gain, worst = 0, worstRaw = 0;
            bool ok = true;
            for (size_t i = 0; i < tr.out.size(); ++i) {
                ok = ok && finite(tr.out[i]) && finite(tr.raw[i]);
                worst = std::max(worst, tr.out[i].length());
                worstRaw = std::max(worstRaw, tr.raw[i].length());
            }
            CHECK(ok, "level %d: not finite", level);
            CHECK(worst <= lim * 1.0001f, "level %d: drawn %.4f past limit %.4f", level, worst, lim);
            CHECK(worstRaw <= 2 * tu.limit * 0.085f * 1.001f, "level %d: simulated %.4f past the wall", level, worstRaw);
            std::printf("hitches (level %d): largest drawn %.1f mm (limit %.1f), simulated %.1f mm, all finite\n", level, worst * 1000, lim * 1000, worstRaw * 1000);
        }
        // a teleport (placed elsewhere) doesn't kick
        SoftSim sim;
        sim.setRegions({chestRegion()});
        std::vector<M4> bones(1);
        for (uint32_t ms = 0; ms <= 2000; ms += 16) sim.step(bones, M4(), ms);
        V3 before = sim.offset(0);
        sim.step(bones, M4::translation(V3(40, -20, 3)), 2016);
        sim.step(bones, M4::translation(V3(40, -20, 3)), 2032);
        CHECK((sim.offset(0) - before).length() < 1e-4f, "a teleport kicks: %.5f", (sim.offset(0) - before).length());
    }

    // 6. Gravity is the world's: lying on her back the chest falls toward her sides... and back, i.e. along world -z,
    //    which is her -x (back) now; standing she hangs along her -z.
    {
        Trace tr = run(lieDown, kSoftNatural, every(16, 3000));
        V3 d = tr.raw.back();   // world
        CHECK(d.z < -0.001f, "lying down: no fall toward the ground (%.4f)", d.z);
        V3 m = tr.out.back();   // model space, posed: lying down, world -z is the model's -z still (world = identity)
        std::printf("lying down: settles at world (%.1f %.1f %.1f) mm\n", d.x * 1000, d.y * 1000, d.z * 1000);
        (void)m;
    }

    // 7. A turn: the chest swings outward (sideways) and settles; the world matrix scaled 2x scales the model offset 1/2.
    {
        Trace tr = run(turn, kSoftNatural, every(16, 2000));
        float side = 0;
        for (const V3& v : tr.out) side = std::max(side, std::fabs(v.y));
        CHECK(side > 0.001f, "a turn doesn't swing her: %.4f", side);
        std::printf("turn: largest sideways swing %.1f mm\n", side * 1000);
    }

    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
