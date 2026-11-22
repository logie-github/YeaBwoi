// The bone layer's and the reach's maths without GL or game files (engine/bonelayer.h): build/bonelayer_test, exit 0 when all pass.
#include <cstdio>
#include "bonelayer.h"
using namespace wc;
static int failed = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); ++failed; } } while (0)
static bool near(float a, float b, float e = 1e-3f) { return std::fabs(a - b) <= e; }
int main() {
    // a rotation track samples its keys and wraps last to first at the loop length (no seam)
    BoneAnimTrack t; t.bone = "#1"; t.rotation = true;
    t.times = {0.0f, 1.0f}; t.values = {0, 0, 0, 1, 0, std::sin(0.5f), 0, std::cos(0.5f)};   // 0 then 1 radian about y
    Quat mid = layer::sampleRotation(t, 0.5f, 2.0f);
    CHECK(near(2 * std::asin(mid.y), 0.5f, 0.01f));
    Quat wrapped = layer::sampleRotation(t, 1.999f, 2.0f);
    CHECK(near(wrapped.y, 0.0f, 0.01f));                         // back at the first key as the loop ends
    CHECK(near(layer::sampleRotation(t, 5.0f, 0.0f).y, std::sin(0.5f)));   // not a loop: the last key holds
    CHECK(near(layer::layerTime(3500, 1000, 2.0f, true), 0.5f));
    CHECK(layer::layerTime(500, 1000, 2.0f, true) == 0.0f);
    // a reachable target: the palm comes to it, the arm's lengths are kept
    layer::ArmPose arm{V3(0, 0, 0), V3(0, -0.3f, -0.2f), V3(0.1f, -0.5f, -0.4f), V3(0.15f, -0.6f, -0.5f)};
    layer::ReachGoal g; g.target = V3(0.45f, -0.1f, -0.1f); g.along = V3(-1, 0, 0); g.pole = V3(0, -1, 0); g.weight = 1;
    layer::ReachTurns r = layer::solveReach(arm, g);
    CHECK(r.reachable);
    CHECK((r.palm - g.target).length() < 0.01f);
    CHECK(near((r.elbow - arm.shoulder).length(), (arm.elbow - arm.shoulder).length(), 1e-3f));
    CHECK(near((r.wrist - r.elbow).length(), (arm.wrist - arm.elbow).length(), 1e-3f));
    // weight 0 leaves the arm as posed
    g.weight = 0; r = layer::solveReach(arm, g);
    CHECK((r.wrist - arm.wrist).length() < 1e-3f);
    // out of reach: it stretches straight toward it and stops short
    g.weight = 1; g.target = V3(3, 0, 0); r = layer::solveReach(arm, g);
    CHECK(!r.reachable);
    CHECK((r.palm - g.target).length() > 1.0f);
    CHECK(r.wrist.x > 0.3f);
    std::printf(failed ? "bonelayer_test: %d failed\n" : "bonelayer_test: all pass\n", failed);
    return failed ? 1 : 0;
}
