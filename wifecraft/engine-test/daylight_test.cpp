// The time of day as light (engine/daylight.cpp), without GL or game files: the keyframes sampled and wrapped round
// midnight, every quantity continuous over the day (no jump at a keyframe, a phase's edge, midnight or the sun's handing
// over to the moon), the sun where it should be, the rooms' night tint. build/daylight_test [LOCATIONS_JSON]: exit 0 when
// all pass; with the app's locations.json, every entry's dayCycle is read and checked the same way.
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

#include "daylight.h"

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } } while (0)

static bool near(float a, float b, float eps = 1e-4f) { return std::fabs(a - b) <= eps; }

// The largest change of any colour channel or of the light's direction between two moments `step` hours apart, over the
// whole day (and across midnight).
static float maxStep(const DayCycle& c, float step, float* atHour) {
    float worst = 0;
    for (float h = -step; h < 24.0f + step; h += step) {
        DaySample a = sampleDay(c, h), b = sampleDay(c, h + step);
        float d = 0;
        for (int i = 0; i < 3; ++i) {
            d = std::max(d, std::fabs(a.ambient[i] - b.ambient[i]));
            d = std::max(d, std::fabs(a.direct[i] - b.direct[i]));
            d = std::max(d, std::fabs(a.sky[i] - b.sky[i]));
            d = std::max(d, std::fabs(a.fog[i] - b.fog[i]));
            d = std::max(d, std::fabs(a.interior[i] - b.interior[i]));
        }
        d = std::max(d, (a.lightDir - b.lightDir).length());
        if (d > worst) { worst = d; if (atHour) *atHour = h; }
    }
    return worst;
}

static void checkCycle(const DayCycle& c, const char* name) {
    // a sim minute's change is small everywhere: at most 0.02 in a channel (the steepest of the game's keyframes, 19:00 to
    // 20:30 at dusk, moves 0.38 in 90 minutes) and well under a degree of the sun
    float at = 0;
    float w = maxStep(c, 1.0f / 60.0f, &at);
    CHECK(w < 0.02f, "%s: a sim minute changes the light by %.4f at %.3f h", name, w, at);
    // and it halves with the step (continuous: no jump hides between two samples)
    float w2 = maxStep(c, 1.0f / 120.0f, nullptr);
    CHECK(w2 < w * 0.6f + 1e-4f, "%s: half the step, %.4f vs %.4f: a jump", name, w2, w);
    // midnight from both sides
    DaySample a = sampleDay(c, 23.9999f), b = sampleDay(c, 0.0f), b24 = sampleDay(c, 24.0f);
    for (int i = 0; i < 3; ++i) {
        CHECK(near(a.ambient[i], b.ambient[i], 1e-3f), "%s: ambient jumps at midnight", name);
        CHECK(near(b.ambient[i], b24.ambient[i]), "%s: 24:00 isn't 0:00", name);
    }
    // the keys themselves are hit exactly
    for (const DayKey& k : c.keys) {
        DayKey s;
        sampleKeys(c, k.hour, s);
        for (int i = 0; i < 3; ++i) CHECK(near(s.ambient[i], k.ambient[i]) && near(s.direct[i], k.direct[i]), "%s: key %.3f not hit", name, k.hour);
    }
}

int main(int argc, char** argv) {
    const DayCycle& d = defaultDayCycle();
    CHECK(d.keys.size() == 12, "the default cycle has %zu keys", d.keys.size());
    checkCycle(d, "default");

    // wrapping
    CHECK(near(wrapHour(25.5f), 1.5f), "wrap 25.5");
    CHECK(near(wrapHour(-1.0f), 23.0f), "wrap -1");
    CHECK(near(wrapHour(24.0f), 0.0f), "wrap 24");

    // the game's numbers: noon is LightData's noon, the dead of night its night (blue: more blue than red)
    DaySample noon = sampleDay(d, 12), night = sampleDay(d, 2), dawn = sampleDay(d, 6), dusk = sampleDay(d, 17.5f);
    CHECK(near(noon.ambient[0], 0.498f) && near(noon.direct[2], 0.259f), "noon isn't LightData's noon");
    CHECK(night.direct[2] > night.direct[0] * 2, "the night's light isn't blue moonlight");
    CHECK(dawn.direct[0] > dawn.direct[2] + 0.4f, "dawn isn't warm");
    CHECK(dusk.direct[0] > dusk.direct[2] + 0.3f, "dusk isn't golden");
    float lum = [](const DaySample& s) { return s.ambient[0] + s.ambient[1] + s.ambient[2] + s.direct[0] + s.direct[1] + s.direct[2]; }(noon);
    float lumN = night.ambient[0] + night.ambient[1] + night.ambient[2] + night.direct[0] + night.direct[1] + night.direct[2];
    CHECK(lumN < lum * 0.75f, "the night (%.2f) isn't darker than noon (%.2f)", lumN, lum);

    // the sun: from the east at 6 (light travelling west: +y), high in the south at noon (travelling north, down), the west
    // at 18; the moon highest at midnight; never under 10 degrees
    V3 s6 = lightDirection(6), s12 = lightDirection(12), s18 = lightDirection(18), s0 = lightDirection(0);
    CHECK(s6.y > 0.9f, "6:00 light doesn't come from the east (%.2f %.2f %.2f)", s6.x, s6.y, s6.z);
    CHECK(s12.x > 0.3f && s12.z < -0.8f, "noon's sun isn't high in the south (%.2f %.2f %.2f)", s12.x, s12.y, s12.z);
    CHECK(s18.y < -0.9f, "18:00 light doesn't come from the west");
    CHECK(s0.z < -0.8f && s0.x < -0.3f, "the midnight moon isn't high in the north");
    for (float h = 0; h < 24; h += 0.05f) CHECK(-lightDirection(h).z >= std::sin(9.9f * 3.14159265f / 180.0f), "the light under 10 degrees at %.2f", h);
    // a location's frame turns it: yaw 90 (local x is the world's y) sends the 6:00 light (world +y) along local +x
    V3 f = lightDirection(6, 90);
    CHECK(f.x > 0.9f, "the frame's yaw isn't applied (%.2f %.2f %.2f)", f.x, f.y, f.z);

    // the rooms: 1 by day, darker and warmer by night, eased in between
    float t[3];
    interiorTint(12, t);
    CHECK(near(t[0], 1) && near(t[1], 1) && near(t[2], 1), "a room is tinted at noon");
    interiorTint(1, t);
    CHECK(t[0] < 1 && t[2] < t[1] && t[1] < t[0], "a room at night isn't darker and warmer (%.2f %.2f %.2f)", t[0], t[1], t[2]);
    CHECK(near(nightAmount(19.75f), 0.5f, 0.05f), "dusk's ease isn't halfway at 19:45 (%.2f)", nightAmount(19.75f));

    // parsing: arrays and objects, unsorted keys sorted, out-of-range hours wrapped, a second key at one hour dropped
    Json j;
    Json::parse(R"({"keys": [[18, 0.5,0.5,0.5, 0.4,0.3,0.2, 0.1,0.1,0.1, 0.2,0.2,0.2], {"hour": 6, "ambient": [0.2,0.2,0.2], "direct": [1,0,0], "sky": [0,0,0], "fog": [0,0,0]}, [30, 0,0,0, 0,0,0, 0,0,0, 0,0,0]]})", j);
    DayCycle p;
    CHECK(parseDayCycle(j, p) && p.keys.size() == 2, "the cycle isn't read (%zu keys)", p.keys.size());
    CHECK(p.keys.size() == 2 && near(p.keys[0].hour, 6) && near(p.keys[0].direct[0], 1) && near(p.keys[1].hour, 18), "keys not sorted / wrapped");
    checkCycle(p, "parsed");
    Json none;
    DayCycle q;
    CHECK(!parseDayCycle(none, q), "nothing read as a cycle");
    // an empty cycle samples the default
    CHECK(near(sampleDay(q, 12).ambient[0], 0.498f), "an empty cycle doesn't fall back to the default");

    // the app's locations: each entry's dayCycle read and continuous
    if (argc > 1) {
        std::ifstream in(argv[1]);
        std::stringstream ss;
        ss << in.rdbuf();
        Json root;
        CHECK(Json::parse(ss.str(), root), "%s isn't JSON", argv[1]);
        int n = 0;
        for (const Json& e : root["locations"].a) {
            DayCycle c;
            std::string id = e["id"].str();
            CHECK(parseDayCycle(e["dayCycle"], c), "%s has no dayCycle", id.c_str());
            if (c.empty()) continue;
            checkCycle(c, id.c_str());
            // the entry's own light is the cycle's noon (the grading leaves noon as it was)
            const Json& L = e["light"];
            DayKey k;
            sampleKeys(c, 12, k);
            if (L.has("ambient")) CHECK(near(k.ambient[0], float(L["ambient"][0].num()), 2e-3f), "%s: the cycle's noon isn't the entry's light", id.c_str());
            ++n;
        }
        std::printf("%d locations' day cycles checked\n", n);
    }
    std::printf(failures ? "daylight_test: %d failures\n" : "daylight_test: all passed\n", failures);
    return failures ? 1 : 0;
}
