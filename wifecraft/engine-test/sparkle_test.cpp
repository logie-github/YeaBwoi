// The interactable sparkles' maths (engine/sparkle.h SparkleField), without GL or the game's files: motes stay over the
// footprint and rise, fade in and out, the density and the strength scale them, an occupied spot (strength 0) makes none, and
// the same clock gives the same motes. Build: engine-test/build.sh; run: build/sparkle_test (exit 0: all pass).
#include <cmath>
#include <cstdio>
#include <vector>

#include "../android/app/src/main/cpp/engine/sparkle.h"

using namespace wc;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

int main() {
    // a spot turned 30 degrees, 1.0 x 0.5 yards half sizes, its surface at z 0.6
    const float yaw = 0.5235988f;
    const float spot[8] = {2.0f, -1.0f, 0.6f, 1.0f, 0.5f, yaw, 1.0f, 3.0f};
    SparkleField f;
    f.set(spot, 1, 1.0f);
    CHECK(!f.empty(), "one spot is not empty");
    std::vector<Spark> a, b;
    size_t most = 0;
    for (float t = 0; t < 12.0f; t += 0.37f) {
        a.clear();
        f.generate(t, a);
        most = std::max(most, a.size());
        for (const Spark& s : a) {
            // inside the footprint (rotated back), between the surface and its rise
            float dx = s.p.x - 2.0f, dy = s.p.y + 1.0f;
            float lx = dx * std::cos(yaw) + dy * std::sin(yaw), ly = -dx * std::sin(yaw) + dy * std::cos(yaw);
            CHECK(std::fabs(lx) <= 1.0f + 1e-3f && std::fabs(ly) <= 0.5f + 1e-3f, "mote outside the footprint: %.3f %.3f", lx, ly);
            CHECK(s.p.z >= 0.6f && s.p.z <= 0.6f + 0.05f + SparkleField::kRise + 1e-3f, "mote height %.3f", s.p.z);
            CHECK(s.alpha > 0 && s.alpha <= 1.0f, "alpha %.3f", s.alpha);
            CHECK(s.size >= 0.09f && s.size <= 0.16f + 1e-4f, "size %.3f", s.size);
        }
    }
    CHECK(most >= 3 && most <= size_t(SparkleField::kMaxPerSpot), "motes at once: %zu", most);
    // the same clock, the same motes
    a.clear(); b.clear();
    f.generate(5.25f, a); f.generate(5.25f, b);
    CHECK(a.size() == b.size(), "deterministic count");
    for (size_t i = 0; i < a.size() && i < b.size(); ++i) CHECK(a[i].p.x == b[i].p.x && a[i].alpha == b[i].alpha, "deterministic mote %zu", i);
    // they move with time
    b.clear();
    f.generate(5.6f, b);
    bool moved = a.size() != b.size();
    for (size_t i = 0; i < a.size() && i < b.size(); ++i) moved = moved || a[i].p.z != b[i].p.z;
    CHECK(moved, "the motes rise as the clock runs");
    // an occupied spot (strength 0) and a switched-off field make none
    float off[8] = {2, -1, 0.6f, 1, 0.5f, 0, 0.0f, 3};
    SparkleField g;
    g.set(off, 1, 1.0f);
    CHECK(g.empty(), "strength 0 is empty");
    f.set(spot, 1, 0.0f);
    CHECK(f.empty(), "density 0 is empty");
    a.clear(); f.generate(2.0f, a);
    CHECK(a.empty(), "density 0 makes no motes");
    // density scales the count; a bigger footprint has more
    CHECK(SparkleField::motesFor(0.5f, 0.5f, 1.0f) >= 4, "a small spot has at least 4");
    CHECK(SparkleField::motesFor(0.5f, 0.5f, 0.5f) < SparkleField::motesFor(0.5f, 0.5f, 1.0f) || SparkleField::motesFor(0.5f, 0.5f, 1.0f) <= 2, "half density has fewer");
    CHECK(SparkleField::motesFor(2.0f, 2.0f, 1.0f) == SparkleField::kMaxPerSpot, "a big spot is capped");
    CHECK(SparkleField::motesFor(1.0f, 1.0f, 0.0f) == 0, "no density, no motes");
    // bad numbers are dropped
    float bad[16] = {NAN, 0, 0, 1, 1, 0, 1, 0,   1, 2, 3, 1, 1, 0, 1, 7};
    SparkleField h;
    h.set(bad, 2, 1.0f);
    CHECK(h.spots().size() == 1, "a non-finite spot is dropped (%zu)", h.spots().size());
    // fade: nothing at the very start, nothing at the end
    CHECK(SparkleField::lifeAlpha(0.0f, 1.0f, 0.0f) < 0.01f && SparkleField::lifeAlpha(1.0f, 1.0f, 0.0f) < 0.01f, "fades at both ends");
    CHECK(SparkleField::lifeAlpha(0.1f, 1.0f, 0.0f) > 0.5f, "bright after the first tenth");
    std::printf(failures ? "%d failures\n" : "all sparkle tests passed\n", failures);
    return failures ? 1 : 0;
}
