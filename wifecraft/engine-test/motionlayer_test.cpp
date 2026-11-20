// The body's additive motion and the atmosphere's motes without GL (engine/motionlayer.h, engine/particles.h):
// build/motionlayer_test [FILES_DIR]. The maths alone (breathing's rate and depth by intensity, the look-at's limits and
// easing, the clip's weight on the head, the turn about a pivot, the motes' determinism and bounds); with FILES_DIR (the game's
// files by id) also on the human woman and man standing (their Stand animation), measured: how far the layer moves the head,
// the chest, the hands and the feet at most (in cm for a 170 cm body) at full intensity, and the head's look-at angles
// against the limits for targets all round. Exit 0 when every check passes.
#include "../android/app/src/main/cpp/engine/motionlayer.h"
#include "../android/app/src/main/cpp/engine/particles.h"
#include "../android/app/src/main/cpp/engine/m2.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <string>

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::printf("FAIL: " __VA_ARGS__); std::printf("\n"); } } while (0)

namespace {
constexpr float kDeg = 57.29578f;

V3 colOf(const M4& m, int c) { return V3(m.at(0, c), m.at(1, c), m.at(2, c)).normalized(); }

void mathsChecks() {
    // breathing: faster and deeper with the intensity, none with Options' breathing at 0
    CHECK(motion::breathRate(1) > motion::breathRate(0), "rate rises");
    CHECK(std::fabs(motion::breathRate(0) * 60 - 12) < 0.01f && std::fabs(motion::breathRate(1) * 60 - 30) < 0.01f, "12..30 a minute");
    CHECK(motion::breathDepth(1, 1) > motion::breathDepth(0, 1), "depth rises");
    CHECK(motion::breathDepth(1, 0) == 0, "breathing off");
    CHECK(motion::breathDepth(5, 5) <= MotionLimits::kBreathMaxRad + 1e-6f, "clamped");
    CHECK(motion::swayAmount(1, 1) < motion::swayAmount(0, 1), "idle quietens");
    for (int i = 0; i < 2000; ++i) {
        float n = motion::idleNoise(i * 0.37f, 12345, i % 3);
        CHECK(n >= -1 && n <= 1, "noise in range");
    }
    // look-at: in front, the angle itself; past the limits, clamped; behind, nothing
    float y, p;
    V3 f(1, 0, 0), u(0, 0, 1);
    CHECK(motion::lookAngles(f, u, V3(1, 1, 0), y, p) && std::fabs(y - 0.7854f) < 1e-3f && std::fabs(p) < 1e-3f, "45 deg left: %f %f", y, p);
    CHECK(motion::lookAngles(f, u, V3(0.2f, -1, 0), y, p) && std::fabs(y + MotionLimits::kLookYaw) < 1e-4f, "clamped right: %f", y);
    CHECK(!motion::lookAngles(f, u, V3(-1, 0.1f, 0), y, p) && y == 0 && p == 0, "behind: none");
    CHECK(motion::lookAngles(f, u, V3(1, 0, 5), y, p) && std::fabs(p - MotionLimits::kLookUp) < 1e-4f, "up clamped: %f", p);
    CHECK(motion::lookAngles(f, u, V3(1, 0, -5), y, p) && std::fabs(p + MotionLimits::kLookDown) < 1e-4f, "down clamped: %f", p);
    CHECK(!motion::lookAngles(f, u, V3(0.01f, 0, 0), y, p), "too near");
    // easing: toward the goal, never past it
    float v = 0;
    for (int i = 0; i < 30; ++i) v = motion::ease(v, 1, 1.0f / 60, MotionLimits::kLookTau);
    CHECK(v > 0.6f && v < 1, "eased half a second: %f", v);
    CHECK(motion::ease(0.3f, 1, NAN, 1) == 0.3f, "bad dt");
    // the clip's weight on the head
    CHECK(motion::clipHeadWeight(0.05f) == 1 && motion::clipHeadWeight(0.6f) == 0, "clip weight ends");
    CHECK(motion::clipHeadWeight(0.25f) > 0 && motion::clipHeadWeight(0.25f) < 1, "clip weight between");
    // a turn about a pivot keeps the pivot and moves a point by the arc
    M4 t = motion::turnAbout(V3(1, 2, 3), V3(0, 0, 1), 0.5f);
    V3 a = t.point(V3(1, 2, 3));
    CHECK((a - V3(1, 2, 3)).length() < 1e-5f, "pivot kept");
    V3 b = t.point(V3(2, 2, 3));
    CHECK(std::fabs((b - V3(1, 2, 3)).length() - 1) < 1e-5f && std::fabs(std::atan2(b.y - 2, b.x - 1) - 0.5f) < 1e-4f, "turned");
    // the placement's inverse
    M4 w = M4::translation(V3(3, 4, 5)) * M4::rotationZ(0.7f) * M4::scaling(V3(1.3f, 1.3f, 1.3f));
    V3 back = motion::placementInverse(V3(3, 4, 5), 0.7f, 1.3f).point(w.point(V3(0.3f, -0.2f, 1.1f)));
    CHECK((back - V3(0.3f, -0.2f, 1.1f)).length() < 1e-5f, "inverse");
}

void particleChecks() {
    ParticleField a, b, c;
    a.configure(48, 99); b.configure(48, 99); c.configure(48, 100);
    V3 centre(10, 20, 1), half(2, 2, 1);
    a.setBox(centre, half); b.setBox(centre, half); c.setBox(centre, half);
    for (int i = 0; i < 60 * 40; ++i) { a.step(1.0f / 60); b.step(1.0f / 60); c.step(1.0f / 60); }
    bool same = true, differ = false, inside = true, alphaOk = true;
    for (size_t i = 0; i < a.motes().size(); ++i) {
        const Mote& x = a.motes()[i]; const Mote& y = b.motes()[i]; const Mote& z = c.motes()[i];
        same = same && x.p.x == y.p.x && x.p.y == y.p.y && x.p.z == y.p.z && x.age == y.age;
        differ = differ || x.p.x != z.p.x;
        V3 d = x.p - centre;
        inside = inside && std::fabs(d.x) <= half.x * 1.06f && std::fabs(d.y) <= half.y * 1.06f && std::fabs(d.z) <= half.z * 1.06f;
        float al = ParticleField::alpha(x);
        alphaOk = alphaOk && al >= 0 && al <= 1;
    }
    CHECK(a.motes().size() == 48, "count kept");
    CHECK(same, "the same seed, the same motes");
    CHECK(differ, "another seed, other motes");
    CHECK(inside, "every mote in its box");
    CHECK(alphaOk, "brightness 0..1");
    ParticleField d;
    d.configure(1000, 1);
    CHECK(int(d.motes().size()) == ParticleField::kMaxCount, "capped at %d", ParticleField::kMaxCount);
    // moving the box brings every mote into the new one at once
    a.setBox(V3(-50, 0, 0), half);
    bool moved = true;
    for (const Mote& m : a.motes()) moved = moved && std::fabs(m.p.x + 50) <= half.x * 1.06f;
    CHECK(moved, "motes follow the box");
    std::printf("motes: 48 stepped 40 s twice from seed 99 identical, seed 100 differs, all inside their box\n");
}

// The layer on a real body standing: how far it moves things, and where the head looks.
void bodyChecks(const std::string& dir) {
    FileLookup files = [&](uint32_t f) {
        std::string p = dir + "/" + std::to_string(f);
        FILE* x = std::fopen(p.c_str(), "rb");
        if (!x) return std::string();
        std::fclose(x);
        return p;
    };
    const std::pair<const char*, uint32_t> bodies[] = {{"human woman", 1000764}, {"human man", 1011653}};
    for (const auto& [name, id] : bodies) {
        M2Model m;
        if (!m.load(id, files)) { std::printf("%s (%u) isn't here: skipped\n", name, id); continue; }
        int stand = m.findSequence(0);
        if (stand >= 0) m.ensureSequence(stand, files);
        int head = m.findWowBone("Head"), chest = m.findWowBone("Chest"), lh = m.findWowBone("L_Hand"), lf = m.findWowBone("L_Foot"),
            rf = m.findWowBone("R_Foot"), neck = m.findWowBone("Neck1"), shoulder = m.findWowBone("L_UpperArm");
        if (head < 0 || chest < 0) { std::printf("%s: no head or chest bone: skipped\n", name); continue; }
        std::vector<BonePose> pose;
        std::vector<M4> base, mats;
        MotionState st;
        st.on = true; st.seed = 3; st.intensity = 1; st.shown = 1;
        MotionParams params;
        params.breathing = 1; params.idle = 1; params.lookAt = false;
        M4 inv = motion::placementInverse(V3(), 0, 1);
        float height = 0;
        float maxHead = 0, maxChest = 0, maxHand = 0, maxFoot = 0, maxNeck = 0, maxShoulder = 0;
        for (int i = 0; i < 60 * 30; ++i) {
            uint32_t ms = uint32_t(i * 1000 / 60);
            if (stand >= 0 && m.sequenceReady(stand)) {
                uint32_t dur = std::max<uint32_t>(1, m.sequences[size_t(stand)].duration);
                m.pose(stand, ms % dur, ms, pose, true);
            } else pose.assign(m.bones.size(), BonePose());
            m.bonesToMatrices(pose, base);
            mats = base;
            motion::apply(st, m, mats, inv, params, ms);
            auto moved = [&](int b) {
                if (b < 0) return 0.0f;
                const V3& pv = m.bones[size_t(b)].pivot;
                return (mats[size_t(b)].point(pv) - base[size_t(b)].point(pv)).length();
            };
            if (i == 0) height = base[size_t(head)].point(m.bones[size_t(head)].pivot).z;
            maxHead = std::max(maxHead, moved(head)); maxChest = std::max(maxChest, moved(chest));
            maxHand = std::max(maxHand, moved(lh)); maxNeck = std::max(maxNeck, moved(neck));
            maxShoulder = std::max(maxShoulder, moved(shoulder)); maxFoot = std::max({maxFoot, moved(lf), moved(rf)});
        }
        float cm = height > 0 ? 170.0f / (height * 1.08f) : 0;   // the head joint is about 92% of the body's height
        std::printf("%s standing, intensity 1, breathing and idle 100%%: most moved over 30 s: head %.2f cm, neck %.2f cm, shoulder %.2f cm, chest joint %.2f cm, hand %.2f cm, feet %.3f cm\n",
                    name, maxHead * cm, maxNeck * cm, maxShoulder * cm, maxChest * cm, maxHand * cm, maxFoot * cm);
        CHECK(maxHead * cm < 3.0f, "%s: the head moves %.2f cm", name, maxHead * cm);
        CHECK(maxChest * cm < 2.0f, "%s: the chest moves %.2f cm", name, maxChest * cm);
        CHECK(maxNeck * cm > 0.3f && maxShoulder * cm > 0.3f, "%s: the chest breathes (neck %.3f cm, shoulder %.3f cm)", name, maxNeck * cm, maxShoulder * cm);
        CHECK(maxFoot * cm < 0.01f, "%s: the feet stay (%.3f cm)", name, maxFoot * cm);

        // eye contact: targets all round at head height, 1.5 yards away; the head's turn never past the limits
        params.lookAt = true; params.breathing = 0; params.idle = 0;
        float worstYaw = 0, worstPitch = 0, best = 0;
        for (int deg = -180; deg < 180; deg += 15) {
            for (int up = -60; up <= 60; up += 30) {
                MotionState s2;
                s2.on = true; s2.intensity = 0.2f; s2.hasTarget = true;
                float a = deg / kDeg, e = up / kDeg;
                V3 h = base[size_t(head)].point(m.bones[size_t(head)].pivot);
                s2.targetPoint = h + V3(std::cos(a) * std::cos(e), std::sin(a) * std::cos(e), std::sin(e)) * 1.5f;
                for (int i = 0; i < 120; ++i) {   // two seconds: settled
                    mats = base;
                    motion::apply(s2, m, mats, inv, params, uint32_t(i * 1000 / 60));
                }
                CHECK(std::fabs(s2.yaw) <= MotionLimits::kLookYaw + 1e-3f && s2.pitch <= MotionLimits::kLookUp + 1e-3f &&
                      s2.pitch >= -MotionLimits::kLookDown - 1e-3f, "%s: look %d,%d past the limits: %f %f", name, deg, up, s2.yaw, s2.pitch);
                // how far the head's forward turned from the clip's
                float turned = std::acos(std::clamp(colOf(mats[size_t(head)], 0).dot(colOf(base[size_t(head)], 0)), -1.0f, 1.0f));
                if (std::abs(deg) <= 45 && up == 0) best = std::max(best, turned);
                worstYaw = std::max(worstYaw, std::fabs(s2.yaw)); worstPitch = std::max(worstPitch, std::fabs(s2.pitch));
                if (std::abs(deg) > 125) CHECK(std::fabs(s2.yaw) < 1e-3f, "%s: a target behind (%d) is not looked at: %f", name, deg, s2.yaw);
            }
        }
        std::printf("%s eye contact: largest yaw %.1f deg (limit %.0f), largest pitch %.1f deg (limits +%.0f/-%.0f), a target 45 deg to the side turns the head %.1f deg\n",
                    name, worstYaw * kDeg, MotionLimits::kLookYaw * kDeg, worstPitch * kDeg, MotionLimits::kLookUp * kDeg,
                    MotionLimits::kLookDown * kDeg, best * kDeg);
        CHECK(best * kDeg > 30, "%s: the head turns toward a target 45 deg aside (%.1f deg)", name, best * kDeg);
    }
}
} // namespace

int main(int argc, char** argv) {
    mathsChecks();
    particleChecks();
    if (argc > 1) bodyChecks(argv[1]);
    std::printf(failures ? "motionlayer_test: %d FAILED\n" : "motionlayer_test: all passed\n", failures);
    return failures ? 1 : 0;
}
