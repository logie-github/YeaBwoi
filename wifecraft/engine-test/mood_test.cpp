// Cinematic mode's light without GL (engine/daylight.h's mood, grade.h's letterbox bars): strength 0 changes nothing, the mood
// lowers and warms the light, the flicker is slow and small, the key comes from the camera's side and low, the rim from
// behind. build/mood_test, exit 0 when all pass.
#include <cmath>
#include <cstdio>

#include "daylight.h"
#include "grade.h"

using namespace wc;

static int failures = 0;
static void check(bool ok, const char* what) {
    if (!ok) { std::printf("FAIL %s\n", what); ++failures; }
}

int main() {
    // strength 0: exactly as given
    float amb[3] = {0.5f, 0.6f, 0.7f}, sun[3] = {0.4f, 0.35f, 0.3f}, sky[3] = {0.3f, 0.5f, 0.7f}, fog[3] = {0.3f, 0.4f, 0.5f}, room[3] = {1, 1, 1};
    float a0[3] = {0.5f, 0.6f, 0.7f};
    moodScene(0, 1, amb, sun, sky, fog, room);
    check(amb[0] == a0[0] && amb[1] == a0[1] && amb[2] == a0[2] && room[0] == 1 && room[2] == 1, "strength 0 changes nothing");
    // full strength: darker, warmer (blue lowered most)
    moodScene(1, 0, amb, sun, sky, fog, room);
    check(amb[0] < 0.5f * 0.75f && amb[2] < 0.7f * 0.6f, "ambient lowered");
    check(amb[0] / amb[2] > 0.5f / 0.7f, "ambient warmer");
    check(room[0] > room[1] && room[1] > room[2] && room[0] < 1, "room light lower and warmer");
    check(sun[0] / sun[2] > 0.4f / 0.3f, "sun warmer");
    // the flicker: within -1..1, slow (a 30 Hz frame moves it a little), and a few percent on the room
    float maxStep = 0, lo = 1, hi = -1;
    for (int i = 0; i < 30 * 120; ++i) {
        float t = i / 30.0f;
        float f = moodFlicker(t), g = moodFlicker(t + 1 / 30.0f);
        maxStep = std::fmax(maxStep, std::fabs(g - f));
        lo = std::fmin(lo, f); hi = std::fmax(hi, f);
    }
    check(lo >= -1 && hi <= 1 && hi - lo > 1.0f, "flicker spans its range");
    check(maxStep < 0.25f, "flicker slow");
    float r1[3] = {1, 1, 1}, r2[3] = {1, 1, 1}, z[3] = {0, 0, 0}, z2[3] = {0, 0, 0}, z3[3] = {0, 0, 0}, z4[3] = {0, 0, 0};
    moodScene(1, 1, z, z2, z3, z4, r1);
    float zz[3] = {0, 0, 0}, zz2[3] = {0, 0, 0}, zz3[3] = {0, 0, 0}, zz4[3] = {0, 0, 0};
    moodScene(1, -1, zz, zz2, zz3, zz4, r2);
    check(r1[0] / r2[0] > 1.03f && r1[0] / r2[0] < 1.1f, "flicker a few percent");
    // a person: strength 0 keeps the light, no rim; full strength less ambient, a warm key as bright, a rim
    float pa[3] = {0.3f, 0.3f, 0.35f}, pd[3] = {0.2f, 0.2f, 0.2f};
    MoodActorLight m0 = moodActor(0, 0, true, pa, pd);
    check(m0.ambient[0] == pa[0] && m0.key[1] == pd[1] && m0.rim[0] == 0, "actor strength 0 unchanged");
    MoodActorLight m1 = moodActor(1, 0, true, pa, pd);
    check(m1.ambient[0] < pa[0] * 0.6f, "actor ambient lowered");
    check(m1.key[0] > m1.key[1] && m1.key[1] > m1.key[2], "key warm");
    check(m1.rim[0] > m1.rim[2], "interior rim warm");
    MoodActorLight m2 = moodActor(1, 0, false, pa, pd);
    check(m2.rim[2] > m2.rim[0], "exterior rim cool");
    // directions: camera at -x looking +x; the view's right is -y; the key comes from -y (its light travels +y), low, from
    // a little in front; the rim from behind (+x) and above
    V3 key, rim;
    moodDirections(V3(-5, 0, 1.5f), V3(0, 0, 1), key, rim);
    check(key.y > 0.6f, "key from the camera's right");
    check(key.z < 0 && key.z > -0.5f, "key low");
    check(key.x > 0, "key from a little in front");
    check(rim.x > 0.6f && rim.z > 0.3f, "rim from behind and above");
    check(std::fabs(key.length() - 1) < 1e-4f && std::fabs(rim.length() - 1) < 1e-4f, "unit directions");
    // letterbox: 16:9 has bars, 2.39 none
    check(std::fabs(letterboxBars(1600, 900) - 0.5f * (1 - (16.0f / 9) / 2.39f)) < 1e-5f, "16:9 bars");
    check(letterboxBars(2390, 1000) == 0 && letterboxBars(0, 0) == 0, "no bars when wide");
    if (failures == 0) std::printf("mood_test: all passed\n");
    return failures ? 1 : 0;
}
