// The time of day as light (daylight.h).
#include "daylight.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace wc {

namespace {

constexpr float kPi = 3.14159265358979f;
constexpr float kMinElevationDeg = 10.0f;   // the light never grazes flatter than this (a wall facing it stays lit)
constexpr float kMaxElevationDeg = 62.0f;   // the noon sun's (and the midnight moon's) height

// A room's light at the dead of night: about two thirds of the day's, its blue and green lowered more than its red,
// so what is left reads as lamp and hearth light. (The baked vertex light holds the lamps and the windows' day light
// alike: it can only be scaled as a whole, not split.)
constexpr float kInteriorNight[3] = {0.72f, 0.60f, 0.48f};

float smooth(float e0, float e1, float x) {
    float t = std::clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

bool readKey(const Json& k, DayKey& out) {
    if (k.kind == Json::Array && k.size() >= 13) {
        out.hour = float(k[0].num());
        for (int i = 0; i < 3; ++i) {
            out.ambient[i] = float(k[size_t(1 + i)].num());
            out.direct[i] = float(k[size_t(4 + i)].num());
            out.sky[i] = float(k[size_t(7 + i)].num());
            out.fog[i] = float(k[size_t(10 + i)].num());
        }
        return true;
    }
    if (k.kind == Json::Object && k.has("hour")) {
        out.hour = float(k["hour"].num());
        auto c3 = [&](const char* name, float* dst) {
            const Json& v = k[name];
            if (v.kind == Json::Array && v.size() >= 3) for (int i = 0; i < 3; ++i) dst[i] = float(v[size_t(i)].num());
        };
        c3("ambient", out.ambient); c3("direct", out.direct); c3("sky", out.sky); c3("fog", out.fog);
        return true;
    }
    return false;
}

} // namespace

float wrapHour(float hours) {
    if (!std::isfinite(hours)) return 0;
    float h = std::fmod(hours, 24.0f);
    if (h < 0) h += 24.0f;
    return h >= 24.0f ? 0.0f : h;
}

bool parseDayCycle(const Json& j, DayCycle& out) {
    out.keys.clear();
    const Json& keys = j.kind == Json::Object ? j["keys"] : j;
    if (keys.kind != Json::Array) return false;
    for (const Json& k : keys.a) {
        DayKey d;
        if (!readKey(k, d)) continue;
        d.hour = wrapHour(d.hour);
        out.keys.push_back(d);
    }
    std::stable_sort(out.keys.begin(), out.keys.end(), [](const DayKey& a, const DayKey& b) { return a.hour < b.hour; });
    // two keys at one hour would be a jump: the first is kept
    out.keys.erase(std::unique(out.keys.begin(), out.keys.end(),
                               [](const DayKey& a, const DayKey& b) { return std::fabs(a.hour - b.hour) < 1e-4f; }),
                   out.keys.end());
    return !out.keys.empty();
}

const DayCycle& defaultDayCycle() {
    static const DayCycle c = [] {
        // LightParams 12 (Light 1, Eastern Kingdoms' default): LightData's rows, Time / 120
        static const float rows[][13] = {
            {0.0f, 0.212f, 0.345f, 0.424f, 0.141f, 0.275f, 0.392f, 0.0f, 0.047f, 0.122f, 0.016f, 0.047f, 0.122f},
            {4.5f, 0.212f, 0.345f, 0.424f, 0.141f, 0.275f, 0.392f, 0.0f, 0.047f, 0.122f, 0.016f, 0.047f, 0.122f},
            {6.0f, 0.506f, 0.573f, 0.635f, 0.655f, 0.263f, 0.0f, 0.325f, 0.439f, 0.522f, 0.2f, 0.267f, 0.302f},
            {7.0f, 0.439f, 0.518f, 0.596f, 0.42f, 0.341f, 0.149f, 0.475f, 0.631f, 0.714f, 0.58f, 0.745f, 0.831f},
            {12.0f, 0.498f, 0.584f, 0.667f, 0.416f, 0.337f, 0.259f, 0.322f, 0.498f, 0.655f, 0.302f, 0.471f, 0.561f},
            {16.5f, 0.541f, 0.573f, 0.651f, 0.455f, 0.29f, 0.165f, 0.322f, 0.498f, 0.655f, 0.325f, 0.353f, 0.427f},
            {17.0f, 0.592f, 0.565f, 0.639f, 0.553f, 0.235f, 0.0f, 0.4f, 0.349f, 0.514f, 0.357f, 0.239f, 0.294f},
            {17.8333f, 0.592f, 0.565f, 0.639f, 0.498f, 0.243f, 0.051f, 0.408f, 0.353f, 0.506f, 0.357f, 0.239f, 0.294f},
            {19.0f, 0.592f, 0.565f, 0.639f, 0.424f, 0.255f, 0.129f, 0.42f, 0.361f, 0.502f, 0.357f, 0.239f, 0.294f},
            {20.5f, 0.212f, 0.345f, 0.424f, 0.141f, 0.275f, 0.392f, 0.0f, 0.047f, 0.122f, 0.016f, 0.047f, 0.122f},
            {21.0f, 0.212f, 0.345f, 0.424f, 0.141f, 0.275f, 0.392f, 0.0f, 0.047f, 0.122f, 0.016f, 0.047f, 0.122f},
            {22.0f, 0.212f, 0.345f, 0.424f, 0.141f, 0.275f, 0.392f, 0.0f, 0.047f, 0.122f, 0.016f, 0.047f, 0.122f},
        };
        DayCycle d;
        for (const auto& r : rows) {
            DayKey k;
            k.hour = r[0];
            for (int i = 0; i < 3; ++i) {
                k.ambient[i] = r[1 + i]; k.direct[i] = r[4 + i]; k.sky[i] = r[7 + i]; k.fog[i] = r[10 + i];
            }
            d.keys.push_back(k);
        }
        return d;
    }();
    return c;
}

void sampleKeys(const DayCycle& c, float hours, DayKey& out) {
    float h = wrapHour(hours);
    out = DayKey();
    out.hour = h;
    if (c.keys.empty()) return;
    const size_t n = c.keys.size();
    // the last key at or before h (the day's last, from the day before, when h is before the first)
    size_t i = n - 1;
    for (size_t k = 0; k < n; ++k) if (c.keys[k].hour <= h) i = k;
    const DayKey& a = c.keys[i];
    const DayKey& b = c.keys[(i + 1) % n];
    float span = b.hour - a.hour;
    float at = h - a.hour;
    if (span <= 0) span += 24.0f;     // round midnight (or a single key: a whole day)
    if (at < 0) at += 24.0f;
    float t = span > 1e-6f ? std::clamp(at / span, 0.0f, 1.0f) : 0.0f;
    for (int k = 0; k < 3; ++k) {
        out.ambient[k] = a.ambient[k] + (b.ambient[k] - a.ambient[k]) * t;
        out.direct[k] = a.direct[k] + (b.direct[k] - a.direct[k]) * t;
        out.sky[k] = a.sky[k] + (b.sky[k] - a.sky[k]) * t;
        out.fog[k] = a.fog[k] + (b.fog[k] - a.fog[k]) * t;
    }
}

V3 lightDirection(float hours, float frameYawDeg) {
    // theta: 0 east (6:00), pi/2 south (12:00), pi west (18:00), 3pi/2 north (0:00); x north, y west
    float theta = kPi * (wrapHour(hours) - 6.0f) / 12.0f;
    V3 east(0, -1, 0), south(-1, 0, 0);
    V3 across = east * std::cos(theta) + south * std::sin(theta);
    float elev = std::max(kMinElevationDeg, kMaxElevationDeg * std::fabs(std::sin(theta))) * kPi / 180.0f;
    V3 toLight = across * std::cos(elev) + V3(0, 0, 1) * std::sin(elev);
    // into the location's frame: the inverse of its yaw
    float y = -frameYawDeg * kPi / 180.0f;
    V3 local(toLight.x * std::cos(y) - toLight.y * std::sin(y), toLight.x * std::sin(y) + toLight.y * std::cos(y), toLight.z);
    return (local * -1.0f).normalized();
}

float nightAmount(float hours) {
    float h = wrapHour(hours);
    if (h < 12.0f) return 1.0f - smooth(4.5f, 7.0f, h);
    return smooth(18.5f, 21.0f, h);
}

void interiorTint(float hours, float out[3]) {
    float n = nightAmount(hours);
    for (int i = 0; i < 3; ++i) out[i] = 1.0f + (kInteriorNight[i] - 1.0f) * n;
}

DaySample sampleDay(const DayCycle& c, float hours, float frameYawDeg) {
    DaySample s;
    DayKey k;
    sampleKeys(c.empty() ? defaultDayCycle() : c, hours, k);
    for (int i = 0; i < 3; ++i) { s.ambient[i] = k.ambient[i]; s.direct[i] = k.direct[i]; s.sky[i] = k.sky[i]; s.fog[i] = k.fog[i]; }
    s.lightDir = lightDirection(hours, frameYawDeg);
    s.night = nightAmount(hours);
    interiorTint(hours, s.interior);
    return s;
}

// ------------------------------------------------------------------------------------------------ the cinematic look

namespace {

constexpr float kKeyFloor = 0.42f;      // the least brightness the key keeps on a person (a dark room's people stay lit)
constexpr float kActorCeiling = 1.05f;  // a person's ambient and key together never brighter than this (luminance)
constexpr float kFlickerRoom = 0.04f;   // the flicker's reach on a room's light and on the key, at full flicker
constexpr float kFlickerKey = 0.06f;

float clamp01(float x) { return std::isfinite(x) ? std::clamp(x, 0.0f, 1.0f) : 0.0f; }
float clampFlicker(float x) { return std::isfinite(x) ? std::clamp(x, -1.0f, 1.0f) : 0.0f; }
float fin(float x, float lo, float hi, float dflt) { return std::isfinite(x) ? std::clamp(x, lo, hi) : dflt; }
float lerpf(float a, float b, float t) { return a + (b - a) * t; }

// a's hue toward b's by t (both luminance 1: the result too)
void mixHue(const float a[3], const float b[3], float t, float out[3]) {
    float m[3];
    for (int i = 0; i < 3; ++i) m[i] = a[i] + (b[i] - a[i]) * t;
    cineHue(m, out);
}

// the hue's largest difference from white brought within `limit` (the luminance stays 1: the difference's weighted sum is 0)
void limitChroma(float u[3], float limit) {
    float h[3];
    cineHue(u, h);
    float m = 0;
    for (int i = 0; i < 3; ++i) m = std::max(m, std::fabs(h[i] - 1.0f));
    float k = m > limit && m > 0 ? limit / m : 1.0f;
    for (int i = 0; i < 3; ++i) u[i] = 1.0f + (h[i] - 1.0f) * k;
}

// a multiplier: luminance within [lo, hi], its hue's chroma within `limit`
void limitMultiplier(float m[3], float lo, float hi, float limit) {
    float l = cineLum(m);
    if (!(l > 1e-4f)) { m[0] = m[1] = m[2] = 1; return; }
    float h[3] = {m[0], m[1], m[2]};
    limitChroma(h, limit);
    l = std::clamp(l, lo, hi);
    for (int i = 0; i < 3; ++i) m[i] = h[i] * l;
}

// a multiplier of luminance `l` whose hue leans toward `hue` by `t`
void tinted(const float hue[3], float t, float l, float out[3]) {
    for (int i = 0; i < 3; ++i) out[i] = (1.0f + (hue[i] - 1.0f) * t) * l;
}

// How warm a hue is: 0 cold (blue over red), 1 warm (red over blue), 0.5 neutral; continuous.
float warmth(const float h[3]) {
    float d = (h[0] - h[2]) / std::max(h[0] + h[2], 1e-3f);
    return std::clamp(0.5f + 1.6f * d, 0.0f, 1.0f);
}

float smooth01(float e0, float e1, float x) {
    float t = std::clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// The profile's every number as (offset, count) in CineProfile, by name: the override block's names, the blend, the scale.
struct Field { const char* name; size_t offset; int count; };
const Field kFields[] = {
    {"key", offsetof(CineProfile, key), 3}, {"keyGain", offsetof(CineProfile, keyGain), 1},
    {"sunBias", offsetof(CineProfile, sunBias), 1}, {"practicalBias", offsetof(CineProfile, practicalBias), 1},
    {"rim", offsetof(CineProfile, rim), 3}, {"rimGain", offsetof(CineProfile, rimGain), 1},
    {"actorAmbient", offsetof(CineProfile, actorAmbient), 1}, {"sceneAmbient", offsetof(CineProfile, sceneAmbient), 3},
    {"sceneSun", offsetof(CineProfile, sceneSun), 3}, {"sceneSky", offsetof(CineProfile, sceneSky), 3},
    {"room", offsetof(CineProfile, room), 3}, {"lift", offsetof(CineProfile, lift), 3},
    {"gamma", offsetof(CineProfile, gamma), 1}, {"gain", offsetof(CineProfile, gain), 3},
    {"contrast", offsetof(CineProfile, contrast), 1}, {"saturation", offsetof(CineProfile, saturation), 1},
    {"vignette", offsetof(CineProfile, vignette), 1}, {"flicker", offsetof(CineProfile, flicker), 1},
    {"flickerColour", offsetof(CineProfile, flickerColour), 3},
};
constexpr int kFieldCount = int(sizeof kFields / sizeof kFields[0]);
static_assert(sizeof(CineProfile) == 37 * sizeof(float), "CineProfile holds floats only: kFields covers them all");

float* at(CineProfile& p, const Field& f) { return reinterpret_cast<float*>(reinterpret_cast<char*>(&p) + f.offset); }
const float* at(const CineProfile& p, const Field& f) {
    return reinterpret_cast<const float*>(reinterpret_cast<const char*>(&p) + f.offset);
}

bool readColour(const Json& v, float out[3]) {
    if (v.kind != Json::Array || v.size() < 3) return false;
    float c[3];
    bool big = false;
    for (int i = 0; i < 3; ++i) {
        c[i] = float(v[size_t(i)].num());
        if (!std::isfinite(c[i])) return false;
        big = big || c[i] > 1.5f;
    }
    for (int i = 0; i < 3; ++i) out[i] = big ? c[i] / 255.0f : c[i];
    return true;
}

bool readPracticals(const Json& j, CinePracticals& out) {
    if (j.kind != Json::Object) return false;
    out = CinePracticals();
    if (j.has("flames")) out.flames = std::max(0, int(j["flames"].num()));
    if (j.has("lights")) out.lights = std::max(out.flames, int(j["lights"].num()));
    else out.lights = out.flames;
    readColour(j["colour"], out.colour);
    const Json& a = j["at"];
    if (a.kind == Json::Array && a.size() >= 3) {
        V3 v(float(a[0].num()), float(a[1].num()), float(a[2].num()));
        if (std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z)) { out.at = v; out.hasAt = true; }
    }
    return true;
}

// The grade's tone at a grey level (the mean of the channels' transfer).
float toneAt(const CineProfile& p, float v) {
    float c[3] = {v, v, v}, o[3];
    cineGradeColour(p, c, o);
    return (o[0] + o[1] + o[2]) / 3.0f;
}
float channelMax(const CineProfile& p, float v) {
    float c[3] = {v, v, v}, o[3];
    cineGradeColour(p, c, o);
    return std::max(o[0], std::max(o[1], o[2]));
}
float channelMin(const CineProfile& p, float v) {
    float c[3] = {v, v, v}, o[3];
    cineGradeColour(p, c, o);
    return std::min(o[0], std::min(o[1], o[2]));
}
// The grade's tone limits: black not washed out, the shadows not crushed, the highlights not blown, the mid-grey kept near.
bool toneOk(const CineProfile& p) {
    return channelMax(p, 0.0f) <= 0.07f && channelMin(p, 0.04f) >= 0.018f && channelMax(p, 0.9f) <= 0.97f &&
           channelMin(p, 1.0f) >= 0.80f && toneAt(p, 0.5f) >= 0.36f && toneAt(p, 0.5f) <= 0.60f;
}

} // namespace

float cineLum(const float c[3]) { return 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]; }

void cineHue(const float c[3], float out[3]) {
    float l = cineLum(c);
    if (!(l > 1e-4f) || !std::isfinite(l)) { out[0] = out[1] = out[2] = 1; return; }
    for (int i = 0; i < 3; ++i) out[i] = std::max(0.0f, c[i]) / l;
    float l2 = cineLum(out);
    if (l2 > 1e-4f) for (int i = 0; i < 3; ++i) out[i] /= l2;
}

float cineHueDistance(const float a[3], const float b[3]) {
    float sa = std::max(a[0] + a[1] + a[2], 1e-4f), sb = std::max(b[0] + b[1] + b[2], 1e-4f);
    float dr = a[0] / sa - b[0] / sb, dg = a[1] / sa - b[1] / sb;
    return std::sqrt(dr * dr + dg * dg);
}

float moodFlicker(float seconds) {
    if (!std::isfinite(seconds)) return 0;
    double t = std::fmod(double(seconds), 3600.0);
    double v = 0.5 * std::sin(t * 2.13) + 0.3 * std::sin(t * 3.71 + 1.3) + 0.2 * std::sin(t * 0.87 + 0.4);
    return float(std::clamp(v, -1.0, 1.0));
}

bool parseCineOverride(const Json& j, CineOverride& out) {
    out = CineOverride();
    if (j.kind != Json::Object) return false;
    for (int k = 0; k < kFieldCount; ++k) {
        const Field& f = kFields[k];
        if (!j.has(f.name)) continue;
        const Json& v = j[f.name];
        float* dst = at(out.values, f);
        if (f.count == 3) {
            if (!readColour(v, dst)) continue;
        } else {
            if (v.kind != Json::Number || !std::isfinite(float(v.num()))) continue;
            *dst = float(v.num());
        }
        out.mask |= 1u << k;
    }
    if (j.has("hue") && readColour(j["hue"], out.hue)) {
        cineHue(out.hue, out.hue);
        out.hasHue = true;
    }
    return true;
}

bool parseCineArea(const Json& j, CineArea& out) {
    out = CineArea();
    if (j.kind != Json::Object) return false;
    const Json& pr = j["practicals"];
    readPracticals(pr["inside"], out.inside);
    readPracticals(pr["outside"], out.outside);
    const Json& ov = j["override"];
    parseCineOverride(ov["inside"], out.overInside);
    parseCineOverride(ov["outside"], out.overOutside);
    return true;
}

void cineAreaHue(const CineInput& in, float out[3]) {
    // outside: the sky's ambient, the sun's (or moon's) colour where it shines, a little of the fog
    float ha[3], hd[3], hf[3], ext[3];
    cineHue(in.ambient, ha); cineHue(in.direct, hd); cineHue(in.fog, hf);
    float wd = 0.3f * smooth01(0.02f, 0.10f, cineLum(in.direct));
    for (int i = 0; i < 3; ++i) ext[i] = 0.5f * ha[i] + wd * hd[i] + 0.2f * hf[i];
    cineHue(ext, ext);
    if (!in.interior) { std::copy(ext, ext + 3, out); return; }
    // a room: its own light (ambient and baked together, as it is drawn), a little of the day through its windows
    float sum[3] = {in.roomAmbient[0] + in.roomLight[0], in.roomAmbient[1] + in.roomLight[1], in.roomAmbient[2] + in.roomLight[2]};
    float room[3];
    if (cineLum(sum) > 0.01f) cineHue(sum, room); else std::copy(ext, ext + 3, room);
    float day = 1.0f - (in.hour < 0 ? 0.0f : nightAmount(in.hour));
    mixHue(room, ext, 0.15f * day, out);
}

CineProfile cineNeutral() { return CineProfile(); }

CineProfile cineProfile(const CineInput& in, const CineOverride* over) {
    CineProfile p;
    const float night = in.hour < 0 ? 0.0f : nightAmount(in.hour);
    const float day = 1.0f - night;
    float area[3];
    if (over && over->hasHue) std::copy(over->hue, over->hue + 3, area); else cineAreaHue(in, area);
    limitChroma(area, 0.6f);
    const float warm = warmth(area);
    const CinePracticals& pr = in.practicals;
    const float flames = std::min(1.0f, float(pr.flames) / 3.0f);   // how much the flames count (three and more: fully)
    const float lights = std::min(1.0f, float(std::max(pr.lights, pr.flames)) / 3.0f);
    float prac[3];
    cineHue(pr.colour, prac);
    limitChroma(prac, 0.6f);
    float sky[3];
    cineHue(in.sky, sky);
    limitChroma(sky, 0.5f);

    if (in.interior) {
        // the key: the room's own light, pulled toward its practicals' colour as far as the room is warm already (a cold
        // room keeps its cold key; a candle in it shows as a warm rim and a flicker)
        mixHue(area, prac, lights * (0.20f + 0.50f * warm), p.key);
        limitChroma(p.key, 0.42f);
        p.keyGain = 0.85f;   // (raised below where the room is dark: its people stay readable)
        p.practicalBias = pr.hasAt ? 0.55f * lights : 0.0f;
        // the rim: a cold room's warm accent from its flames; otherwise the day's sky through the windows, by night the room
        float rimSrc[3];
        float coldFlame = flames * (1.0f - warm);
        float dayRim[3];
        mixHue(area, sky, day, dayRim);
        mixHue(dayRim, prac, std::clamp(coldFlame * 1.5f, 0.0f, 1.0f), rimSrc);
        mixHue(area, rimSrc, 0.7f, p.rim);
        p.rimGain = 0.42f + 0.12f * flames;
        p.actorAmbient = 0.58f;
        tinted(area, 0.15f, 0.82f, p.sceneAmbient);
        tinted(p.key, 0.10f, 1.0f, p.sceneSun);
        tinted(area, 0.10f, 0.85f, p.sceneSky);
        tinted(area, 0.12f, 0.90f - 0.05f * night, p.room);
        p.contrast = 0.30f;
        p.gamma = 1.05f;
        p.saturation = 1.0f + 0.10f * (warm - 0.5f) * 2.0f * (warm > 0.5f ? 1.0f : 0.8f);
        p.vignette = 1.0f;
        p.flicker = flames * (0.55f + 0.45f * night);
    } else {
        // the key: the sun (by night the moon) and the area's own colour; by night a flame nearby warms it a little (its
        // warmth shows on the rim and in the flicker, the moon stays the key)
        float sun[3];
        float dw = smooth01(0.02f, 0.10f, cineLum(in.direct));
        float ha[3];
        cineHue(in.ambient, ha);
        float hd[3];
        cineHue(in.direct, hd);
        mixHue(ha, hd, dw, sun);
        float k0[3];
        mixHue(sun, area, 0.5f + 0.2f * night, k0);
        mixHue(k0, prac, 0.15f * night * flames, p.key);
        limitChroma(p.key, 0.28f + 0.14f * night);
        p.keyGain = 1.0f * day + 0.95f * night;
        p.sunBias = 0.45f * day + 0.25f * night;
        p.practicalBias = pr.hasAt ? 0.30f * night * lights : 0.0f;
        // the rim: the sky by day (warmer as the sun lowers), the moon by night
        float rd[3], rn[3];
        mixHue(sky, sun, 0.4f, rd);
        mixHue(area, sun, 0.5f, rn);
        float rnf[3];
        mixHue(rn, prac, 0.6f * flames, rnf);
        mixHue(rd, rnf, night, p.rim);
        p.rimGain = 0.32f * day + 0.50f * night;
        p.actorAmbient = 0.80f * day + 0.65f * night;
        tinted(area, 0.10f, 0.95f * day + 0.80f * night, p.sceneAmbient);
        tinted(p.key, 0.10f, 1.03f, p.sceneSun);
        tinted(area, 0.05f, 1.0f * day + 0.88f * night, p.sceneSky);
        p.room[0] = p.room[1] = p.room[2] = 1.0f;
        p.contrast = 0.12f * day + 0.28f * night;
        p.gamma = 1.0f + 0.04f * night;
        p.saturation = 1.05f * day + 0.90f * night;
        p.vignette = 0.55f * day + 0.90f * night;
        p.flicker = pr.hasAt ? flames * night * 0.5f : 0.0f;
    }
    // a place already dark (a dim room, a night without a lamp) isn't made darker still: less contrast, the mid-tones a
    // little lifted, its light kept nearer its own
    const float lit = in.interior ? cineLum(in.roomAmbient) + cineLum(in.roomLight) : cineLum(in.ambient) + 0.5f * cineLum(in.direct);
    const float dark = 1.0f - smooth01(0.18f, 0.50f, lit);
    p.contrast *= 1.0f - 0.6f * dark;
    if (in.interior) p.keyGain += 0.15f * dark;
    p.gamma = 1.0f + (p.gamma - 1.0f) * (1.0f - dark) - 0.05f * dark;
    for (float* m : {p.sceneAmbient, p.room})
        for (int i = 0; i < 3; ++i) m[i] += (1.0f - m[i]) * 0.5f * dark;
    // the grade toward the area's own colour: the shadows lifted into it, the highlights toward the key
    const float liftAmt = in.interior ? 0.030f : 0.015f + 0.020f * night;
    for (int i = 0; i < 3; ++i) {
        p.lift[i] = 0.010f + 0.008f * dark + liftAmt * (area[i] - 1.0f);
        p.gain[i] = 1.0f + 0.05f * (p.key[i] - 1.0f);
    }
    std::copy(prac, prac + 3, p.flickerColour);
    if (over && over->mask) {
        for (int k = 0; k < kFieldCount; ++k)
            if (over->mask & (1u << k)) std::copy(at(over->values, kFields[k]), at(over->values, kFields[k]) + kFields[k].count, at(p, kFields[k]));
    }
    cineLimit(p);
    return p;
}

void cineLimit(CineProfile& p) {
    limitChroma(p.key, 0.5f);
    limitChroma(p.rim, 0.5f);
    limitChroma(p.flickerColour, 0.6f);
    p.keyGain = fin(p.keyGain, 0.6f, 1.4f, 1.0f);
    p.sunBias = fin(p.sunBias, 0.0f, 0.8f, 0.0f);
    p.practicalBias = fin(p.practicalBias, 0.0f, 0.8f, 0.0f);
    if (p.sunBias + p.practicalBias > 0.85f) {
        float k = 0.85f / (p.sunBias + p.practicalBias);
        p.sunBias *= k; p.practicalBias *= k;
    }
    p.rimGain = fin(p.rimGain, 0.0f, 0.8f, 0.0f);
    p.actorAmbient = fin(p.actorAmbient, 0.4f, 1.1f, 1.0f);
    limitMultiplier(p.sceneAmbient, 0.6f, 1.15f, 0.3f);
    limitMultiplier(p.sceneSun, 0.6f, 1.15f, 0.3f);
    limitMultiplier(p.sceneSky, 0.6f, 1.15f, 0.3f);
    limitMultiplier(p.room, 0.6f, 1.15f, 0.3f);
    for (int i = 0; i < 3; ++i) {
        p.lift[i] = fin(p.lift[i], 0.0f, 0.06f, 0.0f);
        p.gain[i] = fin(p.gain[i], 0.9f, 1.05f, 1.0f);
    }
    p.gamma = fin(p.gamma, 0.85f, 1.2f, 1.0f);
    p.contrast = fin(p.contrast, 0.0f, 0.45f, 0.0f);
    p.saturation = fin(p.saturation, 0.75f, 1.25f, 1.0f);
    p.vignette = fin(p.vignette, 0.0f, 1.2f, 0.0f);
    p.flicker = fin(p.flicker, 0.0f, 1.0f, 0.0f);
    // the tone: the grade's difference from neutral shrunk until black, the shadows, the mid-grey and the highlights hold
    if (!toneOk(p)) {
        CineProfile n = cineNeutral(), g = p;
        float lo = 0, hi = 1;
        for (int it = 0; it < 14; ++it) {
            float t = 0.5f * (lo + hi);
            CineProfile q = p;
            for (int i = 0; i < 3; ++i) { q.lift[i] = lerpf(n.lift[i], g.lift[i], t); q.gain[i] = lerpf(n.gain[i], g.gain[i], t); }
            q.gamma = lerpf(n.gamma, g.gamma, t);
            q.contrast = lerpf(n.contrast, g.contrast, t);
            (toneOk(q) ? lo : hi) = t;
        }
        for (int i = 0; i < 3; ++i) { p.lift[i] = lerpf(n.lift[i], g.lift[i], lo); p.gain[i] = lerpf(n.gain[i], g.gain[i], lo); }
        p.gamma = lerpf(n.gamma, g.gamma, lo);
        p.contrast = lerpf(n.contrast, g.contrast, lo);
    }
}

CineProfile cineBlend(const CineProfile& a, const CineProfile& b, float t) {
    t = clamp01(t);
    CineProfile out = a;
    for (const Field& f : kFields)
        for (int i = 0; i < f.count; ++i) at(out, f)[i] = lerpf(at(a, f)[i], at(b, f)[i], t);
    return out;
}

CineProfile cineScaled(const CineProfile& p, float strength) { return cineBlend(cineNeutral(), p, strength); }

void cineStep(CineProfile& now, const CineProfile& target, float dt, float tau) {
    if (!(dt > 0) || !std::isfinite(dt)) return;
    float k = tau > 1e-4f ? 1.0f - std::exp(-dt / tau) : 1.0f;
    now = cineBlend(now, target, k);
}

void cineGradeColour(const CineProfile& p, const float in[3], float out[3]) {
    float c[3];
    for (int i = 0; i < 3; ++i) {
        float v = std::clamp(in[i], 0.0f, 1.0f);
        v = p.lift[i] + v * (p.gain[i] - p.lift[i]);
        v = std::pow(std::max(v, 0.0f), p.gamma);
        float s = v * v * (3.0f - 2.0f * v);
        c[i] = v + (s - v) * p.contrast;
    }
    float l = cineLum(c);
    for (int i = 0; i < 3; ++i) out[i] = std::clamp(l + (c[i] - l) * p.saturation, 0.0f, 1.0f);
}

void cineScene(const CineProfile& p, float strength, float flicker, float ambient[3], float sun[3], float sky[3],
               float fog[3], float roomTint[3]) {
    float s = clamp01(strength);
    if (s <= 0) return;
    float f = clampFlicker(flicker) * p.flicker;
    for (int i = 0; i < 3; ++i) {
        ambient[i] *= lerpf(1.0f, p.sceneAmbient[i], s);
        sun[i] *= lerpf(1.0f, p.sceneSun[i], s);
        sky[i] *= lerpf(1.0f, p.sceneSky[i], s);
        fog[i] *= lerpf(1.0f, p.sceneSky[i], s);
        roomTint[i] *= lerpf(1.0f, p.room[i], s) * (1.0f + kFlickerRoom * f * s * p.flickerColour[i]);
    }
}

MoodActorLight cineActor(const CineProfile& p, float strength, float flicker, const float ambient[3], const float direct[3]) {
    MoodActorLight out;
    float s = clamp01(strength);
    float f = clampFlicker(flicker) * p.flicker;
    // how bright the person was lit (their ambient and their light together): the key keeps it, never under the floor
    float lit[3] = {ambient[0] + direct[0], ambient[1] + direct[1], ambient[2] + direct[2]};
    float bright = std::max(cineLum(lit), kKeyFloor);
    for (int i = 0; i < 3; ++i) {
        out.ambient[i] = ambient[i] * lerpf(1.0f, p.actorAmbient, s);
        float key = p.key[i] * bright * p.keyGain * (1.0f + kFlickerKey * f * p.flickerColour[i]);
        out.key[i] = lerpf(direct[i], key, s);
        out.rim[i] = p.rim[i] * bright * 1.2f * p.rimGain * s;
    }
    // the ceiling: the ambient and the key together never past it (a daylit face isn't blown out)
    float total = cineLum(out.ambient) + cineLum(out.key);
    float before = cineLum(ambient) + cineLum(direct);
    float ceiling = std::max(kActorCeiling, before);
    if (s > 0 && total > ceiling && cineLum(out.key) > 1e-4f) {
        float k = std::max(0.0f, (ceiling - cineLum(out.ambient)) / cineLum(out.key));
        for (int i = 0; i < 3; ++i) out.key[i] *= k;
    }
    return out;
}

void moodDirections(const V3& eye, const V3& target, V3& keyTravel, V3& rimToward) {
    V3 f(target.x - eye.x, target.y - eye.y, 0);
    float len = std::sqrt(f.x * f.x + f.y * f.y);
    f = len > 1e-4f ? V3(f.x / len, f.y / len, 0) : V3(1, 0, 0);
    V3 right(f.y, -f.x, 0);            // the view's right (f x up)
    V3 up(0, 0, 1);
    V3 toKey = (right * 0.85f - f * 0.45f + up * 0.36f).normalized();
    keyTravel = toKey * -1.0f;
    rimToward = (f * 0.85f - right * 0.35f + up * 0.5f).normalized();
}

V3 cineKeyDirection(const V3& eye, const V3& target, const V3& sunTravel, float sunBias, bool hasPractical,
                    const V3& practicalAt, float practicalBias) {
    V3 key, rim;
    moodDirections(eye, target, key, rim);
    float sb = clamp01(sunBias), pb = hasPractical ? clamp01(practicalBias) : 0.0f;
    if (sb + pb > 0.85f) { float k = 0.85f / (sb + pb); sb *= k; pb *= k; }
    V3 d = key * (1.0f - sb - pb);
    if (sb > 0 && sunTravel.length() > 1e-4f) d = d + sunTravel.normalized() * sb;
    if (pb > 0) {
        V3 t = target - practicalAt;
        t.z = 0;
        float l = t.length();
        if (l > 0.3f) {
            t = t * (1.0f / l);
            d = d + (t * 0.93f + V3(0, 0, -0.36f)).normalized() * pb;
        }
    }
    // low (it travels downward, at most steeply as the sun's noon) and toward the camera's side of the people (seen from
    // the camera the light falls on what is shown: it may graze, never come from behind)
    V3 f(target.x - eye.x, target.y - eye.y, 0);
    float fl = f.length();
    f = fl > 1e-4f ? f * (1.0f / fl) : V3(1, 0, 0);
    float along = d.x * f.x + d.y * f.y;
    if (along < 0.10f) d = d + f * (0.10f - along);
    if (d.length() < 1e-4f) return key;
    d = d.normalized();
    d.z = std::clamp(d.z, -0.85f, -0.15f);
    return d.normalized();
}

} // namespace wc
