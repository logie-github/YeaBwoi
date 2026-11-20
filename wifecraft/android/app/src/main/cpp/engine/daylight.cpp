// The time of day as light (daylight.h).
#include "daylight.h"

#include <algorithm>
#include <cmath>

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

// What the mood multiplies at full strength.
constexpr float kMoodAmbient[3] = {0.66f, 0.58f, 0.52f};   // the location's ambient: lower, the blue lowered most
constexpr float kMoodSun[3] = {1.05f, 0.86f, 0.64f};       // the sun or moon: warmer
constexpr float kMoodSky[3] = {0.80f, 0.74f, 0.72f};       // the sky and fog: darker, a little warm
constexpr float kMoodRoom[3] = {0.90f, 0.78f, 0.66f};      // a room's own light: lower and warmer (lamplight)
constexpr float kMoodActorAmbient[3] = {0.50f, 0.45f, 0.45f};
constexpr float kKeyColour[3] = {1.0f, 0.70f, 0.44f};      // the key: a warm, low lamp
constexpr float kRimWarm[3] = {1.0f, 0.62f, 0.45f};
constexpr float kRimCool[3] = {0.52f, 0.62f, 1.0f};
constexpr float kFlickerRoom = 0.035f;                     // the flicker's reach on a room's light and on the key
constexpr float kFlickerKey = 0.05f;
constexpr float kKeyFloor = 0.42f;                         // the least brightness the key keeps on a person

float clamp01(float x) { return std::isfinite(x) ? std::clamp(x, 0.0f, 1.0f) : 0.0f; }
float clampFlicker(float x) { return std::isfinite(x) ? std::clamp(x, -1.0f, 1.0f) : 0.0f; }

void scaleBy(float v[3], const float k[3], float s, float extra = 1.0f) {
    for (int i = 0; i < 3; ++i) v[i] *= (1.0f + (k[i] - 1.0f) * s) * extra;
}

} // namespace

float moodFlicker(float seconds) {
    if (!std::isfinite(seconds)) return 0;
    double t = std::fmod(double(seconds), 3600.0);
    double v = 0.5 * std::sin(t * 2.13) + 0.3 * std::sin(t * 3.71 + 1.3) + 0.2 * std::sin(t * 0.87 + 0.4);
    return float(std::clamp(v, -1.0, 1.0));
}

void moodScene(float strength, float flicker, float ambient[3], float sun[3], float sky[3], float fog[3], float roomTint[3]) {
    float s = clamp01(strength);
    if (s <= 0) return;
    float f = clampFlicker(flicker);
    scaleBy(ambient, kMoodAmbient, s);
    scaleBy(sun, kMoodSun, s);
    scaleBy(sky, kMoodSky, s);
    scaleBy(fog, kMoodSky, s);
    scaleBy(roomTint, kMoodRoom, s, 1.0f + kFlickerRoom * f * s);
}

MoodActorLight moodActor(float strength, float flicker, bool interior, const float ambient[3], const float direct[3]) {
    MoodActorLight out;
    float s = clamp01(strength);
    float f = clampFlicker(flicker);
    // how bright the person was lit (their ambient and their light together, by the eye's weights): the key keeps it
    float lum = 0.299f * (ambient[0] + direct[0]) + 0.587f * (ambient[1] + direct[1]) + 0.114f * (ambient[2] + direct[2]);
    // a person in a dark room is still lit (the low key is the room's, not theirs): never under kKeyFloor
    float bright = std::max(lum, kKeyFloor);
    const float* rim = interior ? kRimWarm : kRimCool;
    for (int i = 0; i < 3; ++i) {
        out.ambient[i] = ambient[i] * (1.0f + (kMoodActorAmbient[i] - 1.0f) * s);
        float key = kKeyColour[i] * bright * 1.1f;
        out.key[i] = (direct[i] + (key - direct[i]) * s) * (1.0f + kFlickerKey * f * s);
        out.rim[i] = rim[i] * bright * 0.6f * s;
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

} // namespace wc
