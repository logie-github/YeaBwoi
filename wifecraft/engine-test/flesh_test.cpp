// The flesh measure (engine/flesh.h, anim/FleshProfile.kt) on a synthetic body with known extents: build/flesh_test, exit 0
// when all pass. No GL context, no game files (the game's bodies are measured by engine-test/audit's `flesh` command).
#include "../android/app/src/main/cpp/engine/flesh.h"

#include <cstdio>
#include <chrono>
#include <random>

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::printf("FAIL: " __VA_ARGS__); std::printf("\n"); } } while (0)

namespace {

const float kPi = 3.14159265f;

// The reference skeleton's rest joints (anim/BodyGeometry.REF), model units, the floor at 0.
std::map<std::string, V3> refJoints() {
    return {
        {"Root", V3(-0.016f, 0, 1.441f)}, {"Pelvis", V3(-0.014f, 0.001f, 1.434f)}, {"Spine1", V3(-0.016f, 0, 1.473f)},
        {"Spine2", V3(-0.021f, 0, 1.537f)}, {"Chest", V3(-0.021f, 0, 1.611f)}, {"Neck1", V3(-0.067f, 0, 1.965f)},
        {"Head", V3(-0.054f, 0, 2.042f)},
        {"L_Clavicle", V3(-0.043f, 0.055f, 1.877f)}, {"L_UpperArm", V3(-0.097f, 0.170f, 1.903f)}, {"L_Forearm", V3(-0.105f, 0.461f, 1.713f)},
        {"L_Hand", V3(-0.044f, 0.746f, 1.525f)},
        {"R_Clavicle", V3(-0.043f, -0.055f, 1.877f)}, {"R_UpperArm", V3(-0.097f, -0.170f, 1.903f)}, {"R_Forearm", V3(-0.105f, -0.461f, 1.713f)},
        {"R_Hand", V3(-0.044f, -0.746f, 1.525f)},
        {"L_Thigh", V3(-0.009f, 0.099f, 1.301f)}, {"L_Calf", V3(-0.035f, 0.150f, 0.703f)}, {"L_Foot", V3(-0.129f, 0.199f, 0.108f)},
        {"L_Toe", V3(0.087f, 0.200f, 0.028f)},
        {"R_Thigh", V3(-0.009f, -0.099f, 1.301f)}, {"R_Calf", V3(-0.035f, -0.149f, 0.703f)}, {"R_Foot", V3(-0.129f, -0.199f, 0.108f)},
        {"R_Toe", V3(0.087f, -0.198f, 0.028f)},
    };
}

// An elliptic tube round segment [s] of the joints: [front] along +n, [back] along -n, [side] along m (model units), its
// vertices evenly round it and along it, each counted with weight [w].
void tube(std::vector<FleshPoint>& out, const std::map<std::string, V3>& J, int s, float front, float back, float side, float w = 1) {
    std::map<std::string, V3> withHips = J;
    fleshAddHips(withHips);
    FleshFrame f;
    if (!fleshFrame(s, withHips, 0.0f, f)) return;
    for (int i = 0; i <= 20; ++i)
        for (int k = 0; k < 72; ++k) {
            float th = 2 * kPi * k / 72, c = std::cos(th), sn = std::sin(th);
            float rn = c >= 0 ? front : back;
            out.push_back({f.a0 + f.axis * (f.len * i / 20) + f.n * (rn * c) + f.m * (side * sn), s, w});
        }
}

// The 90th percentile of r cos(theta) over theta evenly round: cos(0.1 pi) of r; of r |sin(theta)|: cos(0.05 pi) of r.
const float kRing90 = std::cos(0.1f * kPi);
const float kRingAbs90 = std::cos(0.05f * kPi);

} // namespace

int main() {
    // ---- a known body: the reference skeleton, its thighs thick at the back (a seat's flesh), its pelvis wide
    auto J = refJoints();
    std::vector<FleshPoint> pts;
    tube(pts, J, kFleshPelvis, 0.15f, 0.20f, 0.22f);
    tube(pts, J, kFleshSpine, 0.14f, 0.12f, 0.18f);
    tube(pts, J, kFleshChest, 0.16f, 0.12f, 0.20f);
    tube(pts, J, kFleshLThigh, 0.09f, 0.12f, 0.10f);
    tube(pts, J, kFleshRThigh, 0.09f, 0.12f, 0.10f);
    tube(pts, J, kFleshLCalf, 0.06f, 0.07f, 0.06f);
    tube(pts, J, kFleshRCalf, 0.06f, 0.07f, 0.06f);
    tube(pts, J, kFleshLFoot, 0.05f, 0.04f, 0.05f);
    tube(pts, J, kFleshRFoot, 0.05f, 0.04f, 0.05f);
    tube(pts, J, kFleshLUpperArm, 0.05f, 0.05f, 0.05f);
    // a point far off the left forearm with a tiny weight: the percentile leaves it out
    tube(pts, J, kFleshLForearm, 0.04f, 0.04f, 0.04f);
    pts.push_back({J["L_Forearm"] + V3(0, 0, 1.0f), kFleshLForearm, 0.001f});
    const float unit = 1.441f;
    FleshMeasure m = fleshOf(J, pts, 0.0f, unit);
    CHECK(m.ok, "the synthetic body is measured");
    auto near = [](float got, float want, float tol) { return std::fabs(got - want) <= tol; };
    struct Want { int s; float front, back, side; };
    const Want wants[] = {
        {kFleshPelvis, 0.15f, 0.20f, 0.22f}, {kFleshSpine, 0.14f, 0.12f, 0.18f}, {kFleshChest, 0.16f, 0.12f, 0.20f},
        {kFleshLThigh, 0.09f, 0.12f, 0.10f}, {kFleshRThigh, 0.09f, 0.12f, 0.10f}, {kFleshLCalf, 0.06f, 0.07f, 0.06f},
        {kFleshLFoot, 0.05f, 0.04f, 0.05f},
    };
    for (const Want& w : wants) {
        const FleshSeg& s = m.seg[w.s];
        // the 90th percentile of a ring's reach is cos(0.1 pi) of its radius (its side, either way: cos(0.05 pi))
        CHECK(s.ok, "%s measured", kFleshSegs[w.s].name);
        CHECK(near(s.front * unit, w.front * kRing90, 0.006f), "%s front %.4f, want %.4f", kFleshSegs[w.s].name, s.front * unit, w.front * kRing90);
        CHECK(near(s.back * unit, w.back * kRing90, 0.006f), "%s back %.4f, want %.4f", kFleshSegs[w.s].name, s.back * unit, w.back * kRing90);
        CHECK(near(s.side * unit, w.side * kRingAbs90, 0.006f), "%s side %.4f, want %.4f", kFleshSegs[w.s].name, s.side * unit, w.side * kRingAbs90);
        CHECK(s.start < 0.002f && s.end < 0.002f, "%s ends %.4f %.4f (the tube stops at its joints)", kFleshSegs[w.s].name, s.start, s.end);
    }
    // the front is the body's front: the thighs' back (their -n) is behind them (-x) at rest
    {
        auto H = J; fleshAddHips(H);
        FleshFrame f; fleshFrame(kFleshLThigh, H, 0, f);
        CHECK(f.n.x > 0.9f, "the thigh's n is its front at rest (n %.2f %.2f %.2f)", f.n.x, f.n.y, f.n.z);
        fleshFrame(kFleshSpine, H, 0, f);
        CHECK(f.n.x > 0.9f, "the spine's n is its front at rest");
        fleshFrame(kFleshLFoot, H, 0, f);
        CHECK(f.n.z > 0.9f, "the foot's n is its top (n %.2f %.2f %.2f)", f.n.x, f.n.y, f.n.z);
    }
    const FleshSeg& fa = m.seg[kFleshLForearm];
    CHECK(near(fa.front * unit, 0.04f, 0.002f), "a round forearm: its radius (%.4f), the stray point left out", fa.front * unit);
    CHECK(fa.front == fa.back && fa.back == fa.side, "a round segment's three reaches are one");
    // the rest joints: the pelvis at kAnimRootHeight over the soles, the Root at the reference's x
    CHECK(m.restOk[0] && near(m.rest[0].z, kAnimRootHeight, 1e-4f) && near(m.rest[0].x, kFleshRefRootX, 1e-4f), "the Root's rest");
    CHECK(near(m.rest[17].z, 0.108f, 1e-4f), "the left foot's rest height (%.4f)", m.rest[17].z);

    // ---- the same body twice as big (a bigger model): the same body units
    {
        std::vector<FleshPoint> big = pts;
        auto J2 = J;
        for (auto& [k, v] : J2) v = v * 2.0f;
        for (auto& p : big) p.p = p.p * 2.0f;
        FleshMeasure m2 = fleshOf(J2, big, 0.0f, unit * 2);
        for (int s = 0; s < kFleshCount; ++s)
            CHECK(near(m2.seg[s].front, m.seg[s].front, 1e-4f) && near(m2.seg[s].side, m.seg[s].side, 1e-4f), "%s scales away", kFleshSegs[s].name);
        CHECK(near(m2.rest[5].z, m.rest[5].z, 1e-4f), "the rest joints scale away");
    }

    // ---- a thick seat: buttocks reaching 0.1 below the hip joints (the pelvis's end)
    {
        std::vector<FleshPoint> seat = pts;
        auto H = J; fleshAddHips(H);
        for (int k = 0; k < 400; ++k) seat.push_back({H["Hips"] + V3(-0.1f, 0.05f * std::sin(float(k)), -0.10f), kFleshPelvis, 1});
        FleshMeasure ms = fleshOf(J, seat, 0.0f, unit);
        CHECK(ms.seg[kFleshPelvis].end * unit > 0.05f, "the buttocks' reach under the hips is the pelvis's end (%.4f)", ms.seg[kFleshPelvis].end * unit);
    }

    // ---- the lowest point of a segment's flesh: a horizontal thigh (sitting) rests on its back
    {
        FleshSeg s; s.front = 0.06f; s.back = 0.09f; s.side = 0.07f; s.ok = true;
        std::map<std::string, V3> sit = {{"L_Thigh", V3(0, 0.1f, 0.5f)}, {"L_Calf", V3(0.4f, 0.1f, 0.5f)}, {"R_Thigh", V3(0, -0.1f, 0.5f)},
                                         {"R_Calf", V3(0.4f, -0.1f, 0.5f)}};
        FleshFrame f;
        CHECK(fleshFrame(kFleshLThigh, sit, 0, f), "a sitting thigh's frame");
        CHECK(f.n.z > 0.99f, "a sitting thigh's front faces up (n %.2f %.2f %.2f)", f.n.x, f.n.y, f.n.z);
        CHECK(near(fleshLowest(s, f), 0.5f - 0.09f, 1e-4f), "it rests on its back's flesh: lowest %.4f", fleshLowest(s, f));
        // standing (the axis down): its side and front don't matter, its end's reach does
        std::map<std::string, V3> st = {{"L_Thigh", V3(0, 0.1f, 0.9f)}, {"L_Calf", V3(0, 0.1f, 0.5f)}, {"R_Thigh", V3(0, -0.1f, 0.9f)}, {"R_Calf", V3(0, -0.1f, 0.5f)}};
        s.end = 0.03f;
        fleshFrame(kFleshLThigh, st, 0, f);
        CHECK(near(fleshLowest(s, f), 0.5f - 0.03f, 1e-4f), "a standing thigh's lowest is its end's reach: %.4f", fleshLowest(s, f));
    }

    // ---- the packed floats: their layout (anim/FleshProfile.of reads it)
    {
        std::vector<float> p = m.pack();
        CHECK(p.size() == size_t(4 + 7 * kFleshCount + 1 + 3 * kFleshJointCount), "the packed size %zu", p.size());
        CHECK(p[0] == 1.0f && p[3] == float(kFleshCount) && p[4 + 7 * kFleshCount] == float(kFleshJointCount), "the packed header");
        CHECK(near(p[4 + 7 * kFleshLThigh + 2], m.seg[kFleshLThigh].back, 1e-6f), "the left thigh's back where FleshProfile reads it");
    }

    // ---- what it costs: the measure of a body of 12 000 vertex weights (a dressed character has 3 000 to 9 000 vertices)
    {
        std::mt19937 rng(3);
        std::vector<FleshPoint> many;
        for (int i = 0; i < 12000; ++i) {
            const FleshPoint& b = pts[size_t(rng() % pts.size())];
            many.push_back({b.p + V3(float(rng() % 100) * 1e-4f, 0, 0), b.seg, 1});
        }
        auto t0 = std::chrono::steady_clock::now();
        for (int r = 0; r < 10; ++r) (void)fleshOf(J, many, 0.0f, unit);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 10;
        std::printf("flesh measure of 12000 weights: %.2f ms on this machine\n", ms);
    }

    std::printf(failures ? "flesh_test: %d FAILED\n" : "flesh_test: all passed\n", failures);
    return failures ? 1 : 0;
}
