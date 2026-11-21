// Cinematic mode's zone mood (engine/daylight.h ZoneLight, cineProfile's zone part, cineZoneLight), without GL or game
// files: build/zonelight_test LOCATIONS_JSON [ZONE_LIGHT_JSON], exit 0 when all pass. Checks: every place's zone light read
// (and the same keys as assets/zone_light.json), continuous over the day and round midnight; zone mood 0 changes nothing,
// exactly; for every place, hour, side, strength and boost the profile's limits hold (no crushed blacks, no blown
// highlights, the mid-grey kept) and the light never goes muddy; skin stays skin (its hue and brightness under the mood
// near what the look without it gives); and Tirisfal reads as Tirisfal: mauve-purple ambient, teal sky, purple fog.
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

#include "daylight.h"

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } } while (0)

static bool readJson(const char* path, Json& out) {
    std::ifstream in(path);
    if (!in) return false;
    std::stringstream ss;
    ss << in.rdbuf();
    return Json::parse(ss.str(), out);
}

static float zoneStep(const ZoneKey& a, const ZoneKey& b) {
    float d = 0;
    const float* as[] = {a.ambient, a.direct, a.skyTop, a.skyMid, a.fog, a.endFog};
    const float* bs[] = {b.ambient, b.direct, b.skyTop, b.skyMid, b.fog, b.endFog};
    for (int c = 0; c < 6; ++c) for (int i = 0; i < 3; ++i) d = std::max(d, std::fabs(as[c][i] - bs[c][i]));
    return d;
}

static float profileDiff(const CineProfile& a, const CineProfile& b, bool all = true) {
    const float* x = &a.key[0];
    const float* y = &b.key[0];
    float d = 0;
    // (without `all`: the zone's target colours and fog distances left out: they act only through zoneMix and fogMix, which
    // start from 0, so they may be anything at zone mood 0)
    size_t n = all ? sizeof(CineProfile) / sizeof(float) : size_t(&a.zoneAmbient[0] - x);
    for (size_t i = 0; i < n; ++i) d = std::max(d, std::fabs(x[i] - y[i]));
    if (!all) d = std::max(d, std::fabs(a.fogMix - b.fogMix));
    return d;
}

static void grade(const CineProfile& p, float v, float out[3]) {
    float c[3] = {v, v, v};
    cineGradeColour(p, c, out);
}

// The tone limits (as cineLimit keeps them) at the strength the grade is drawn at.
static void checkTone(const CineProfile& full, float s, const char* what) {
    CineProfile p = cineScaled(full, s);
    float o[3];
    grade(p, 0.0f, o);
    CHECK(std::max(o[0], std::max(o[1], o[2])) <= 0.075f, "%s: black washed out (%.3f %.3f %.3f)", what, o[0], o[1], o[2]);
    grade(p, 0.04f, o);
    CHECK(std::min(o[0], std::min(o[1], o[2])) >= 0.015f, "%s: shadows crushed (%.3f %.3f %.3f)", what, o[0], o[1], o[2]);
    grade(p, 0.5f, o);
    float m = (o[0] + o[1] + o[2]) / 3;
    CHECK(m >= 0.35f && m <= 0.61f, "%s: mid-grey to %.3f", what, m);
    grade(p, 1.0f, o);
    CHECK(std::min(o[0], std::min(o[1], o[2])) >= 0.79f, "%s: white dulled (%.3f %.3f %.3f)", what, o[0], o[1], o[2]);
    for (int i = 0; i < 3; ++i) CHECK(full.mid[i] >= 0.85f && full.mid[i] <= 1.15f, "%s: mid %d %.3f", what, i, full.mid[i]);
    CHECK(full.saturation <= 1.35f && full.contrast <= 0.45f, "%s: saturation %.2f contrast %.2f", what, full.saturation, full.contrast);
}

// A skin colour (albedo) lit as a person is (the ambient and the key, a little rim) and graded: what the camera shows.
static void litSkin(const CineProfile& p, float s, const float albedo[3], const float amb[3], const float dir[3], float out[3]) {
    MoodActorLight m = cineActor(p, s, 0, amb, dir);
    float c[3];
    for (int i = 0; i < 3; ++i) c[i] = std::min(1.0f, albedo[i] * (m.ambient[i] + 0.8f * m.key[i]) + 0.15f * m.rim[i] * albedo[i]);
    cineGradeColour(cineScaled(p, s), c, out);
}

static CineInput inputAt(const DayCycle& c, float h, bool interior, const float roomAmbient[3], const float roomLight[3]) {
    DaySample d = sampleDay(c, h);
    CineInput in;
    for (int i = 0; i < 3; ++i) { in.ambient[i] = d.ambient[i]; in.direct[i] = d.direct[i]; in.sky[i] = d.sky[i]; in.fog[i] = d.fog[i]; }
    in.hour = h;
    in.interior = interior;
    if (interior)
        for (int i = 0; i < 3; ++i) { in.roomAmbient[i] = roomAmbient[i]; in.roomLight[i] = roomLight[i] * d.interior[i]; }
    return in;
}

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "zonelight_test LOCATIONS_JSON [ZONE_LIGHT_JSON]\n"); return 2; }
    Json root, zfile;
    CHECK(readJson(argv[1], root), "%s isn't JSON", argv[1]);
    const bool haveFile = argc > 2 && readJson(argv[2], zfile);

    // parsing: a short key skipped, unsorted keys sorted, 0..255 colours brought to 0..1
    {
        Json j;
        Json::parse(R"({"glow": 0.4, "keys": [[18, 1,0,0, 0,0,0, 0,0,0, 0,0,0, 0,0,0, 0,0,0, 5, 100],
                        [6, 255,0,0, 0,0,0, 0,0,0, 0,0,0, 0,0,0, 0,0,0, 2, 50], [3, 1, 2]]})", j);
        ZoneLight z;
        CHECK(parseZoneLight(j, z) && z.keys.size() == 2, "the zone isn't read (%zu keys)", z.keys.size());
        CHECK(z.keys.size() == 2 && z.keys[0].hour == 6 && std::fabs(z.keys[0].ambient[0] - 1) < 1e-4f && z.keys[1].fogFar == 100,
              "zone keys not sorted or scaled");
        ZoneKey k;
        sampleZone(z, 12, k);
        CHECK(std::fabs(k.ambient[0] - 1) < 1e-4f && std::fabs(k.fogFar - 75) < 1e-3f, "zone not interpolated (%.3f %.2f)", k.ambient[0], k.fogFar);
        ZoneLight e;
        CHECK(!parseZoneLight(Json(), e) && e.empty(), "nothing read as a zone");
    }

    int places = 0, checked = 0;
    float worstStep = 0, worstSkin = 0, worstSkinLum = 1;
    std::string worstSkinAt;
    // skins: fair, mid, dark, and an undead's grey-green (the Forsaken of Brill)
    const float skins[4][3] = {{0.86f, 0.66f, 0.55f}, {0.64f, 0.44f, 0.33f}, {0.36f, 0.24f, 0.18f}, {0.55f, 0.58f, 0.50f}};
    for (const Json& e : root["locations"].a) {
        std::string id = e["id"].str();
        CineArea area;
        parseCineArea(e["cinematic"], area);
        CHECK(!area.zone.empty(), "%s has no zone light", id.c_str());
        if (area.zone.empty()) continue;
        ++places;
        // the same keys as zone_light.json's
        if (haveFile) {
            const Json& pz = zfile["places"][id];
            ZoneLight f;
            CHECK(parseZoneLight(pz, f) && f.keys.size() == area.zone.keys.size(), "%s: zone_light.json and locations.json differ", id.c_str());
            for (size_t i = 0; i < f.keys.size() && i < area.zone.keys.size(); ++i)
                CHECK(zoneStep(f.keys[i], area.zone.keys[i]) < 1e-5f && f.keys[i].hour == area.zone.keys[i].hour, "%s: key %zu differs", id.c_str(), i);
        }
        // continuous: a sim minute moves no colour far, midnight from both sides alike
        for (float h = 0; h < 24.0f; h += 1.0f / 60) {
            ZoneKey a, b;
            sampleZone(area.zone, h, a);
            sampleZone(area.zone, h + 1.0f / 60, b);
            float d = zoneStep(a, b);
            worstStep = std::max(worstStep, d);
            CHECK(d < 0.03f, "%s: the zone light jumps %.3f at %.2f h", id.c_str(), d, h);
        }
        DayCycle c;
        parseDayCycle(e["dayCycle"], c);
        const Json& ia = e["views"]["inside"]["light"]["ambient"];
        float roomAmb[3] = {0, 0, 0}, roomLight[3] = {0.55f, 0.40f, 0.25f};   // a lamp-lit room's baked light
        for (int i = 0; i < 3; ++i) if (ia.kind == Json::Array) roomAmb[i] = float(ia[size_t(i)].num());
        for (int side = 0; side < 2; ++side) {
            const bool interior = side == 0;
            for (float h = 0; h < 24; h += 1.0f) {
                CineInput in = inputAt(c, h, interior, roomAmb, roomLight);
                in.practicals = interior ? area.inside : area.outside;
                CineProfile base = cineProfile(in);
                // zone mood 0: nothing of it, exactly
                CineInput z0 = in;
                z0.hasZone = true;
                sampleZone(area.zone, h, z0.zone);
                z0.zoneAmount = 0;
                CHECK(profileDiff(cineProfile(z0), base) == 0, "%s %d %.0f h: zone mood 0 changes the profile", id.c_str(), side, h);
                CineProfile prev = base;
                for (int boost = 0; boost < 2; ++boost)
                    for (float zs = 0.25f; zs <= 1.001f; zs += 0.25f) {
                        CineInput zi = z0;
                        zi.zoneAmount = zs;
                        zi.zoneBoost = boost == 1;
                        CineProfile p = cineProfile(zi);
                        char what[128];
                        std::snprintf(what, sizeof what, "%s %s %.0f h zone %.2f%s", id.c_str(), interior ? "inside" : "outside", h, zs, boost ? " boost" : "");
                        checkTone(p, 1.0f, what);
                        checkTone(p, 0.5f, what);
                        if (!boost) {
                            // continuous in the strength
                            CHECK(profileDiff(p, prev, false) < 0.35f, "%s: a jump of %.3f from the strength before", what, profileDiff(p, prev, false));
                            prev = p;
                        }
                        // the light it makes: never muddy (not black, not grey-brown sludge: some light kept)
                        float amb[3], sun[3], sky[3], hor[3], bel[3], fog[3];
                        for (int i = 0; i < 3; ++i) { amb[i] = in.ambient[i]; sun[i] = in.direct[i]; sky[i] = in.sky[i]; hor[i] = bel[i] = fog[i] = in.fog[i]; }
                        float fs = 28, fe = 60;
                        cineZoneLight(p, 1.0f, amb, sun, sky, hor, bel, fog, fs, fe);
                        CHECK(cineLum(amb) >= 0.09f && cineLum(amb) <= 0.85f, "%s: ambient %.3f", what, cineLum(amb));
                        CHECK(fe <= 60.0f + 1e-3f && fe >= 30.0f && fs >= 0 && fs < fe, "%s: fog %.1f..%.1f", what, fs, fe);
                        // skin: lit by the room's or the sky's light, graded; its hue near the look without the zone's, never dark sludge
                        float pAmb[3], pDir[3];
                        for (int i = 0; i < 3; ++i) {
                            pAmb[i] = interior ? (roomAmb[i] + roomLight[i] * 0.8f) : amb[i];
                            pDir[i] = interior ? roomLight[i] * 0.5f : sun[i];
                        }
                        float bAmb[3], bDir[3];
                        for (int i = 0; i < 3; ++i) {
                            bAmb[i] = interior ? pAmb[i] : in.ambient[i];
                            bDir[i] = interior ? pDir[i] : in.direct[i];
                        }
                        for (const auto& sk : skins) {
                            float a[3], b[3];
                            litSkin(base, 1.0f, sk, bAmb, bDir, b);
                            litSkin(p, 1.0f, sk, pAmb, pDir, a);
                            // its hue's distance from the skin's own: never much further than the look without the zone mood
                            // takes it (a moonlit face is already bluer), nor past a limit where it was near
                            float d = cineHueDistance(a, sk) - cineHueDistance(b, sk);
                            if (d > worstSkin) { worstSkin = d; worstSkinAt = what; }
                            float allow = (boost ? 0.08f : 0.05f) + std::max(0.0f, 0.10f - cineHueDistance(b, sk));
                            CHECK(d < allow, "%s: skin (%.2f %.2f %.2f) %.3f further from its own hue", what, sk[0], sk[1], sk[2], d);
                            float la = cineLum(a), lb = cineLum(b);
                            if (lb > 0.02f) worstSkinLum = std::min(worstSkinLum, la / lb);
                            CHECK(la >= 0.55f * lb && la >= std::min(0.05f, lb), "%s: skin %.3f against %.3f: crushed", what, la, lb);
                        }
                        ++checked;
                    }
            }
        }
        // Tirisfal: mauve-purple ambient (green lowest), a teal sky (green over red), a purple fog (blue and red over green)
        if (id == "brill_house") {
            for (float h : {2.0f, 12.0f, 19.0f, 22.0f}) {
                CineInput in = inputAt(c, h, false, roomAmb, roomLight);
                in.hasZone = true;
                sampleZone(area.zone, h, in.zone);
                in.zoneAmount = 0.7f;
                CineProfile p = cineProfile(in);
                CHECK(p.zoneAmbient[1] < p.zoneAmbient[0] && p.zoneAmbient[1] < p.zoneAmbient[2], "Brill %.0f h: the ambient isn't mauve (%.2f %.2f %.2f)", h, p.zoneAmbient[0], p.zoneAmbient[1], p.zoneAmbient[2]);
                CHECK(p.zoneSky[1] > p.zoneSky[0], "Brill %.0f h: the sky isn't teal", h);
                CHECK(p.zoneFog[2] > p.zoneFog[1] && p.zoneFog[0] > p.zoneFog[1], "Brill %.0f h: the fog isn't purple", h);
                CHECK(p.lift[2] > p.lift[1] && p.mid[1] < 1.0f, "Brill %.0f h: the grade isn't toward purple (lift %.3f %.3f %.3f, mid g %.3f)", h, p.lift[0], p.lift[1], p.lift[2], p.mid[1]);
                CHECK(cineLum(p.zoneSun) < 0.25f, "Brill %.0f h: the sun %.2f isn't gloomy", h, cineLum(p.zoneSun));
                CHECK(p.fogFar < 60.0f, "Brill %.0f h: the fog (%.0f) isn't close", h, p.fogFar);
            }
        }
    }
    CHECK(places == 11, "%d places with a zone light", places);
    std::printf("%d places' zone light, %d place-hour-strengths checked; worst minute step %.4f; skin at most %.3f further from its own hue than without (%s), "
                "skin brightness at least %.2f of the look without the zone mood\n",
                places, checked, worstStep, worstSkin, worstSkinAt.c_str(), worstSkinLum);
    std::printf(failures ? "zonelight_test: %d failures\n" : "zonelight_test: all passed\n", failures);
    return failures ? 1 : 0;
}
