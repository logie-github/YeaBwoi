// The time of day as light (engine/daylight.cpp), without GL or game files: the keyframes sampled and wrapped round
// midnight, every quantity continuous over the day (no jump at a keyframe, a phase's edge, midnight or the sun's handing
// over to the moon), the sun where it should be, the rooms' night tint. build/daylight_test [LOCATIONS_JSON]: exit 0 when
// all pass; with the app's locations.json, every entry's dayCycle is read and checked the same way. And cinematic mode's
// profile (daylight.h cineProfile): neutral at strength 0, each kind of place keeping its identity, the limits holding for
// any light and override, continuous in the hour, the blend's maths, the key's direction, the "cinematic" JSON; with the
// app's locations, every place at every half hour inside and out.
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

// ------------------------------------------------------------------------------------------- the cinematic profile

// `area`'s hue brought to `like`'s strength (its largest difference from white): two hues compared by their direction, a
// sanity limit's lesser saturation aside
static void atStrengthOf(const float area[3], const float like[3], float out[3]) {
    float ma = 0, ml = 0;
    for (int i = 0; i < 3; ++i) { ma = std::max(ma, std::fabs(area[i] - 1)); ml = std::max(ml, std::fabs(like[i] - 1)); }
    float k = ma > ml && ma > 0 ? ml / ma : 1.0f;
    for (int i = 0; i < 3; ++i) out[i] = 1 + (area[i] - 1) * k;
}

static float warmthOf(const float c[3]) { return (c[0] - c[2]) / std::max(c[0] + c[2], 1e-3f); }   // > 0 warm, < 0 cold

static float profileStep(const CineProfile& a, const CineProfile& b) {
    const float* x = &a.key[0];
    const float* y = &b.key[0];
    float d = 0;
    for (size_t i = 0; i < sizeof(CineProfile) / sizeof(float); ++i) d = std::max(d, std::fabs(x[i] - y[i]));
    return d;
}

// The limits every profile keeps, at full strength and scaled.
static void checkLimits(const CineProfile& p, const char* what) {
    float h[3];
    cineHue(p.key, h);
    CHECK(near(cineLum(p.key), 1.0f, 2e-3f), "%s: the key's luminance isn't 1 (%.3f)", what, cineLum(p.key));
    for (int i = 0; i < 3; ++i) {
        CHECK(std::fabs(p.key[i] - 1) <= 0.5f + 1e-3f && std::fabs(p.rim[i] - 1) <= 0.5f + 1e-3f, "%s: a colour cast past the limit", what);
        CHECK(p.lift[i] >= 0 && p.lift[i] <= 0.06f && p.gain[i] >= 0.9f && p.gain[i] <= 1.05f, "%s: lift/gain out of range", what);
    }
    for (float s : {1.0f, 0.7f, 0.3f}) {
        CineProfile q = cineScaled(p, s);
        float c0[3] = {0, 0, 0}, c4[3] = {0.04f, 0.04f, 0.04f}, c5[3] = {0.5f, 0.5f, 0.5f}, c9[3] = {0.9f, 0.9f, 0.9f}, c1[3] = {1, 1, 1};
        float o0[3], o4[3], o5[3], o9[3], o1[3];
        cineGradeColour(q, c0, o0); cineGradeColour(q, c4, o4); cineGradeColour(q, c5, o5); cineGradeColour(q, c9, o9); cineGradeColour(q, c1, o1);
        for (int i = 0; i < 3; ++i) {
            CHECK(o0[i] <= 0.07f + 1e-4f, "%s at %.1f: black washed out (%.3f)", what, s, o0[i]);
            CHECK(o4[i] >= 0.018f - 1e-4f, "%s at %.1f: the shadows crushed (%.3f)", what, s, o4[i]);
            CHECK(o9[i] <= 0.97f + 1e-4f, "%s at %.1f: the highlights blown (%.3f)", what, s, o9[i]);
            CHECK(o1[i] >= 0.80f - 1e-4f, "%s at %.1f: white greyed (%.3f)", what, s, o1[i]);
        }
        // the mid-grey by the grade itself (the exposure is meant to move it: its own range below)
        CineProfile q1 = q;
        q1.exposure = 1;
        cineGradeColour(q1, c5, o5);
        float m = (o5[0] + o5[1] + o5[2]) / 3;
        CHECK(m >= 0.36f - 1e-4f && m <= 0.60f + 1e-4f, "%s at %.1f: the mid-grey moved to %.3f", what, s, m);
        CHECK(q.exposure >= 0.6f - 1e-4f && q.exposure <= 1.9f + 1e-4f, "%s at %.1f: exposure %.2f", what, s, q.exposure);
        cineGradeColour(q, c5, o5);
        m = (o5[0] + o5[1] + o5[2]) / 3;
        CHECK(m <= 0.72f, "%s at %.1f: the exposure takes the mid-grey to %.3f", what, s, m);
    }
}

// The engine's measured mean baked light of each location's inside room (Scene's StaticGroup::meanLight at the inside
// view's target, by day): what the tests stand in for the WMO's vertex colours (the profile itself gets it from the engine).
struct RoomFixture { const char* id; float light[3]; };
static const RoomFixture kRooms[] = {
    {"embassy", {0.252f, 0.226f, 0.195f}}, {"goldshire_inn", {0.589f, 0.401f, 0.242f}}, {"thunderbrew", {0.390f, 0.369f, 0.326f}},
    {"razor_hill_inn", {0.408f, 0.240f, 0.165f}}, {"bloodhoof_inn", {0.258f, 0.247f, 0.217f}},
    {"fairbreeze_inn", {0.234f, 0.229f, 0.216f}}, {"brill_house", {0.096f, 0.090f, 0.107f}}, {"gadgetzan_inn", {0, 0, 0}},
    {"senjin_hut", {0.320f, 0.228f, 0.157f}}, {"telaar_hut", {0.326f, 0.241f, 0.332f}},
};

static void cineTests() {
    // neutral: strength 0 changes nothing, exactly
    CineInput in;
    CineProfile p = cineProfile(in);
    CineProfile z = cineScaled(p, 0), n = cineNeutral();
    CHECK(profileStep(z, n) == 0, "strength 0 isn't neutral");
    float amb[3] = {0.5f, 0.6f, 0.7f}, sun[3] = {0.4f, 0.35f, 0.3f}, sky[3] = {0.3f, 0.5f, 0.7f}, fog[3] = {0.3f, 0.4f, 0.5f}, room[3] = {1, 1, 1};
    cineScene(p, 0, 1, amb, sun, sky, fog, room);
    CHECK(amb[0] == 0.5f && amb[2] == 0.7f && sun[1] == 0.35f && room[0] == 1 && room[2] == 1, "cineScene at strength 0 changes the light");
    float pa[3] = {0.3f, 0.3f, 0.35f}, pd[3] = {0.2f, 0.2f, 0.2f};
    MoodActorLight m0 = cineActor(p, 0, 1, pa, pd);
    CHECK(m0.ambient[0] == pa[0] && m0.key[1] == pd[1] && m0.rim[0] == 0, "cineActor at strength 0 changes a person's light");
    float g0[3] = {0.25f, 0.5f, 0.75f}, go[3];
    cineGradeColour(cineNeutral(), g0, go);
    CHECK(near(go[0], 0.25f) && near(go[1], 0.5f) && near(go[2], 0.75f), "the neutral grade changes a colour");

    // the kinds of place keep their identity
    auto interior = [](float ra0, float ra1, float ra2, float rl0, float rl1, float rl2, int flames, float hour) {
        CineInput i;
        i.interior = true;
        i.roomAmbient[0] = ra0; i.roomAmbient[1] = ra1; i.roomAmbient[2] = ra2;
        i.roomLight[0] = rl0; i.roomLight[1] = rl1; i.roomLight[2] = rl2;
        i.hour = hour;
        i.practicals.flames = i.practicals.lights = flames;
        if (flames) { i.practicals.hasAt = true; i.practicals.at = V3(1, 0, 1); }
        return i;
    };
    // a cold, dark undead room with candles (the Brill house): the key stays cold, the rim takes the flames' warmth, it flickers
    CineInput cold = interior(0.086f, 0.145f, 0.267f, 0.069f, 0.054f, 0.051f, 5, 22);
    CineProfile pc = cineProfile(cold);
    CHECK(pc.key[2] > pc.key[0], "a cold room's key turned warm (%.2f %.2f %.2f)", pc.key[0], pc.key[1], pc.key[2]);
    CHECK(warmthOf(pc.rim) > warmthOf(pc.key) + 0.1f, "a cold room's candles give no warm rim");
    CHECK(pc.flicker > 0.5f, "a room with candles doesn't flicker");
    CHECK(pc.lift[2] >= pc.lift[0], "a cold room's shadows aren't cold");
    // the same room with no flame: no flicker
    CineInput coldDark = cold;
    coldDark.practicals = CinePracticals();
    CHECK(cineProfile(coldDark).flicker == 0, "a room without a flame flickers");
    // a warm inn (Goldshire): warm key, warm grade
    CineProfile pw = cineProfile(interior(0.075f, 0.063f, 0.055f, 0.589f, 0.401f, 0.242f, 9, 22));
    CHECK(warmthOf(pw.key) > 0.25f && pw.gain[0] >= pw.gain[2] && pw.lift[0] >= pw.lift[2], "a warm inn isn't warm");
    // a daylit exterior: the sun's key, a soft grade; a night exterior: the moon's (blue), deeper
    const DayCycle& d = defaultDayCycle();
    auto exterior = [&](float hour) {
        CineInput e;
        DaySample s = sampleDay(d, hour);
        for (int i = 0; i < 3; ++i) { e.ambient[i] = s.ambient[i]; e.direct[i] = s.direct[i]; e.sky[i] = s.sky[i]; e.fog[i] = s.fog[i]; }
        e.hour = hour;
        return e;
    };
    CineProfile pd12 = cineProfile(exterior(12)), pn = cineProfile(exterior(2)), pdusk = cineProfile(exterior(18));
    CHECK(pd12.sunBias > 0.3f && pd12.contrast < pn.contrast && pd12.vignette < pn.vignette, "day isn't softer than night");
    CHECK(pn.key[2] > pn.key[0], "the night's key isn't the moon's blue");
    CHECK(warmthOf(pdusk.key) > warmthOf(pd12.key), "dusk's key isn't warmer than noon's");
    CHECK(pd12.flicker == 0 && pn.flicker == 0, "an exterior without flames flickers");

    // the limits hold for anything: random light, hours, practicals and wild overrides
    unsigned seed = 12345;
    auto rnd = [&seed]() { seed = seed * 1664525u + 1013904223u; return float(seed >> 8) / float(1u << 24); };
    for (int k = 0; k < 3000; ++k) {
        CineInput r;
        for (int i = 0; i < 3; ++i) {
            r.ambient[i] = rnd() * 1.4f; r.direct[i] = rnd() * 1.4f; r.sky[i] = rnd(); r.fog[i] = rnd();
            r.roomAmbient[i] = rnd() * 0.8f; r.roomLight[i] = rnd() * 0.9f; r.practicals.colour[i] = rnd();
        }
        r.interior = rnd() < 0.5f;
        r.hour = rnd() * 26 - 1;
        r.practicals.flames = int(rnd() * 6);
        r.practicals.lights = r.practicals.flames + int(rnd() * 3);
        r.practicals.hasAt = rnd() < 0.5f;
        CineOverride o;
        if (k % 3 == 0) {
            Json j;
            char buf[400];
            std::snprintf(buf, sizeof buf, R"({"keyGain": %.2f, "lift": [%.2f, 0, 0.5], "gain": [2, %.2f, 0], "gamma": %.2f, "contrast": %.2f, "saturation": %.2f, "key": [1, 0, 0], "hue": [0, 0, 1], "vignette": 9})",
                          rnd() * 6 - 2, rnd() * 3 - 1, rnd() * 3, rnd() * 4 - 1, rnd() * 2, rnd() * 3);
            Json::parse(buf, j);
            parseCineOverride(j, o);
        }
        CineProfile q = cineProfile(r, o.empty() ? nullptr : &o);
        checkLimits(q, k % 3 == 0 ? "random with overrides" : "random");
        if (failures > 20) return;
    }

    // continuous in the hour (a sim minute moves nothing far; half the step, half the change: no jump), inside and out
    CineInput ci = cold;
    for (int pass = 0; pass < 2; ++pass) {
        float worst = 0, worstHalf = 0, at = 0;
        for (float h = 0; h < 24.0f; h += 1.0f / 60.0f) {
            CineInput a = pass ? exterior(h) : ci, b = pass ? exterior(h + 1.0f / 60.0f) : ci, c = pass ? exterior(h + 1.0f / 120.0f) : ci;
            a.hour = h; b.hour = h + 1.0f / 60.0f; c.hour = h + 1.0f / 120.0f;
            if (!pass) for (int i = 0; i < 3; ++i) {   // the room's light under the hour's tint, as the engine gives it
                float ta[3], tb[3], tc[3];
                interiorTint(a.hour, ta); interiorTint(b.hour, tb); interiorTint(c.hour, tc);
                a.roomLight[i] = cold.roomLight[i] * ta[i]; b.roomLight[i] = cold.roomLight[i] * tb[i]; c.roomLight[i] = cold.roomLight[i] * tc[i];
            }
            float s = profileStep(cineProfile(a), cineProfile(b));
            if (s > worst) { worst = s; at = h; }
            worstHalf = std::max(worstHalf, profileStep(cineProfile(a), cineProfile(c)));
        }
        CHECK(worst < 0.03f, "%s: a sim minute moves the profile by %.4f at %.2f h", pass ? "exterior" : "interior", worst, at);
        CHECK(worstHalf < worst * 0.6f + 1e-4f, "%s: half the step, %.4f vs %.4f: a jump", pass ? "exterior" : "interior", worstHalf, worst);
    }

    // the blend: eased toward the target, frame-rate independent, never past it; a blend's ends are its profiles
    CineProfile a = cineProfile(cold), b = cineProfile(exterior(12));
    CHECK(profileStep(cineBlend(a, b, 0), a) == 0 && profileStep(cineBlend(a, b, 1), b) < 1e-6f, "the blend's ends");
    CineProfile s60 = a, s30 = a;
    for (int i = 0; i < 60; ++i) cineStep(s60, b, 1.0f / 60.0f, 1.0f);
    for (int i = 0; i < 30; ++i) cineStep(s30, b, 1.0f / 30.0f, 1.0f);
    CHECK(profileStep(s60, s30) < 1e-4f, "the blend depends on the frame rate (%.5f)", profileStep(s60, s30));
    float d0 = profileStep(a, b), d1 = profileStep(s60, b);
    CHECK(d1 < d0 * 0.40f && d1 > d0 * 0.33f, "a second of blending isn't about 1 - 1/e of the way (%.3f of %.3f)", d1, d0);
    for (int i = 0; i < 600; ++i) cineStep(s60, b, 1.0f / 60.0f, 1.0f);
    CHECK(profileStep(s60, b) < 0.01f * d0, "the blend doesn't arrive");
    CineProfile mid = cineBlend(a, b, 0.5f);
    checkLimits(mid, "a blend between two profiles");
    CineProfile still = a;
    cineStep(still, b, 0, 1.0f);
    CHECK(profileStep(still, a) == 0, "no time moved the blend");

    // the key's direction: unit, low, never a back light; the practical's pull sends it away from the light
    V3 eye(-5, 0, 1.6f), tgt(0, 0, 1.1f);
    for (float sb : {0.0f, 0.5f, 0.85f})
        for (float pb : {0.0f, 0.5f}) {
            V3 sunT = lightDirection(17);
            V3 k = cineKeyDirection(eye, tgt, sunT, sb, true, V3(4, 3, 2), pb);
            CHECK(near(k.length(), 1, 1e-3f) && k.z < -0.1f && k.z > -0.9f, "the key isn't low and unit (%.2f %.2f %.2f)", k.x, k.y, k.z);
            CHECK(k.x > 0.05f, "the key turned into a back light (%.2f %.2f %.2f)", k.x, k.y, k.z);
        }
    V3 k0 = cineKeyDirection(eye, tgt, V3(0, 0, -1), 0, true, V3(0, 6, 1), 0);
    V3 k1 = cineKeyDirection(eye, tgt, V3(0, 0, -1), 0, true, V3(0, 6, 1), 0.8f);
    CHECK(k1.y < k0.y, "the practical's pull doesn't turn the key away from it");

    // the JSON: practicals and overrides read, 0..255 colours, broken values skipped, unknown names ignored
    Json j;
    CHECK(Json::parse(R"({"practicals": {"inside": {"flames": 3, "lights": 4, "colour": [255, 170, 90], "at": [1, 2, 3]},
        "outside": {"flames": 0}}, "override": {"inside": {"keyGain": 1.2, "rim": [1, 0.5, 0.2], "flicker": "much",
        "gamma": [1], "nonsense": 4, "hue": [0.2, 0.3, 0.9]}, "outside": 5}})", j), "the override JSON");
    CineArea area;
    CHECK(parseCineArea(j, area), "cinematic not read");
    CHECK(area.inside.flames == 3 && area.inside.lights == 4 && area.inside.hasAt && near(area.inside.at.y, 2), "practicals not read");
    CHECK(near(area.inside.colour[1], 170.0f / 255.0f), "a 0..255 colour not read");
    CHECK(area.outside.flames == 0 && !area.outside.hasAt, "outside practicals not read");
    CHECK(area.overInside.hasHue && area.overInside.hue[2] > area.overInside.hue[0], "the hue override not read");
    CHECK(near(area.overInside.values.keyGain, 1.2f) && area.overInside.mask != 0, "keyGain not read");
    CHECK(area.overInside.values.flicker == 0 && area.overInside.values.gamma == 1, "a broken value was read");
    CHECK(area.overOutside.empty(), "a broken override block read as something");
    CineProfile over = cineProfile(cold, &area.overInside);
    CHECK(near(over.keyGain, 1.2f) && over.rim[0] > over.rim[2], "the override isn't laid on");
    CineArea none;
    CHECK(!parseCineArea(Json(), none) && none.inside.flames == 0, "nothing read as a cinematic block");
}

// The app's locations under the profile: every place at every half hour, inside and out: the limits hold, the key keeps
// the area's own hue (a cold place keeps a cold key, a warm one a warm key), flames only where there are flames.
static void cineLocations(const Json& root) {
    int n = 0;
    float worstKey = 0;
    std::string worstAt;
    for (const Json& e : root["locations"].a) {
        std::string id = e["id"].str();
        DayCycle c;
        parseDayCycle(e["dayCycle"], c);
        CineArea area;
        CHECK(parseCineArea(e["cinematic"], area), "%s has no cinematic block", id.c_str());
        const RoomFixture* room = nullptr;
        for (const RoomFixture& r : kRooms) if (id == r.id) room = &r;
        const Json& ia = e["views"]["inside"]["light"]["ambient"];
        for (int side = 0; side < 2; ++side) {
            if (side == 0 && !room) continue;   // (a place whose inside view stands in no interior group: its outside's)
            for (float h = 0; h < 24; h += 0.5f) {
                DaySample s = sampleDay(c, h);
                CineInput in;
                for (int i = 0; i < 3; ++i) { in.ambient[i] = s.ambient[i]; in.direct[i] = s.direct[i]; in.sky[i] = s.sky[i]; in.fog[i] = s.fog[i]; }
                in.hour = h;
                in.interior = side == 0;
                if (in.interior) {
                    for (int i = 0; i < 3; ++i) {
                        in.roomAmbient[i] = ia.kind == Json::Array ? float(ia[size_t(i)].num()) : 0.0f;
                        in.roomLight[i] = room->light[i] * s.interior[i];
                    }
                }
                in.practicals = in.interior ? area.inside : area.outside;
                const CineOverride& o = in.interior ? area.overInside : area.overOutside;
                CineProfile p = cineProfile(in, o.empty() ? nullptr : &o);
                char what[96];
                std::snprintf(what, sizeof what, "%s %s %.1f h", id.c_str(), in.interior ? "inside" : "outside", h);
                checkLimits(p, what);
                float hue[3];
                cineAreaHue(in, hue);
                float hueL[3];
                atStrengthOf(hue, p.key, hueL);
                float dk = cineHueDistance(p.key, hueL);
                if (dk > worstKey) { worstKey = dk; worstAt = what; }
                // (outside, the key is the sun's or the moon's, half of it the area's: a little further from the sky's hue)
                CHECK(dk < (in.interior ? 0.10f : 0.12f), "%s: the key's hue is %.3f from the area's", what, dk);
                if (warmthOf(hue) < -0.15f) CHECK(warmthOf(p.key) < 0.05f, "%s: a cold place got a warm key", what);
                if (warmthOf(hue) > 0.15f) CHECK(warmthOf(p.key) > 0, "%s: a warm place got a cold key", what);
                if (in.practicals.flames == 0) CHECK(p.flicker == 0, "%s: flickers without a flame", what);
                ++n;
            }
        }
    }
    std::printf("%d place-hours' cinematic profiles checked (the key at most %.3f from the area's hue: %s)\n", n, worstKey, worstAt.c_str());
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
        cineLocations(root);
    }
    cineTests();
    std::printf(failures ? "daylight_test: %d failures\n" : "daylight_test: all passed\n", failures);
    return failures ? 1 : 0;
}
