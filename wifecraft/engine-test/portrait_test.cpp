// Checks the portrait rig's geometry and bounds (portrait.h) without GL or a game file.
//   g++ -std=c++17 -I../android/app/src/main/cpp/engine portrait_test.cpp -o build/portrait_test && build/portrait_test
#include <cmath>
#include <cstdio>
#include "portrait.h"
using namespace wc;
static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { ++fails; std::printf("FAIL %s: ", #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)
int main() {
    // cameras all round a person at the origin, up and down
    for (int yaw = 0; yaw < 360; yaw += 30) for (int pitch : {-20, 0, 25}) {
        const float y = yaw * 3.14159265f / 180, p = pitch * 3.14159265f / 180;
        const V3 eye(3 * std::cos(y) * std::cos(p), 3 * std::sin(y) * std::cos(p), 1.2f + 3 * std::sin(p));
        const V3 target(0, 0, 1.2f);
        const V3 fwd = (target - eye).normalized();
        const V3 flat = V3(fwd.x, fwd.y, 0).normalized();
        const V3 left(-flat.y, flat.x, 0);
        for (int pr = 1; pr < portrait::kPresets; ++pr) {
            portrait::Rig r = portrait::make(pr, eye, target);
            CHECK(r.on, "preset %d on", pr);
            const V3 key(r.keyTravel[0], r.keyTravel[1], r.keyTravel[2]), fill(r.fillTravel[0], r.fillTravel[1], r.fillTravel[2]), rim(r.rimTravel[0], r.rimTravel[1], r.rimTravel[2]);
            CHECK(std::fabs(key.length() - 1) < 1e-4f && std::fabs(fill.length() - 1) < 1e-4f && std::fabs(rim.length() - 1) < 1e-4f, "unit directions");
            // the key comes from above and from the camera's left (it travels to the right), on the camera's side
            CHECK(key.z < -0.3f, "key above: %f", key.z);
            CHECK(key.dot(left) < 0, "key from the left: %f", key.dot(left));
            CHECK(key.dot(flat) > 0, "key on the camera side (travels away from it)");
            // the fill from the right, lower than the key
            CHECK(fill.dot(left) > 0, "fill from the right");
            CHECK(-fill.z < -key.z, "fill lower than the key");
            // the rim from behind her: it travels toward the camera
            CHECK(rim.dot(flat) < -0.3f, "rim comes from behind her: %f", rim.dot(flat));
            for (int i = 0; i < 3; ++i) {
                CHECK(std::isfinite(r.keyColor[i]) && r.keyColor[i] >= 0 && r.keyColor[i] <= 1.1f, "key colour");
                CHECK(r.fillColor[i] >= 0 && r.fillColor[i] < r.keyColor[i] + 0.2f, "fill below key");
                CHECK(r.sky[i] >= 0 && r.sky[i] <= 0.6f && r.ground[i] >= 0 && r.ground[i] <= 0.6f, "ambient bounds");
            }
            CHECK(r.keyColor[0] >= r.keyColor[2], "key warm");
            const float peak = r.keyColor[1] + r.fillColor[1] + r.rimColor[1] + r.sky[1];
            CHECK(peak < 2.0f, "peak %f", peak);
        }
    }
    CHECK(!portrait::make(0, V3(3, 0, 1), V3(0, 0, 1)).on, "off");
    CHECK(!portrait::make(99, V3(3, 0, 1), V3(0, 0, 1)).on, "bad preset off");
    // the place's tint leans the ambient but never more than 30 percent
    const float warmRoom[3] = {1.0f, 0.5f, 0.2f};
    portrait::Rig a = portrait::make(1, V3(3, 0, 1), V3(0, 0, 1)), b = portrait::make(1, V3(3, 0, 1), V3(0, 0, 1), warmRoom);
    CHECK(b.sky[2] < a.sky[2] && b.sky[2] >= 0.69f * a.sky[2], "tint bounded");
    // a degenerate camera (eye over the target)
    portrait::Rig d = portrait::make(1, V3(0, 0, 5), V3(0, 0, 1));
    CHECK(std::isfinite(d.keyTravel[0]) && std::isfinite(d.keyTravel[2]), "degenerate");
    std::printf(fails ? "portrait_test: %d FAILED\n" : "portrait_test: ok\n", fails);
    return fails ? 1 : 0;
}
