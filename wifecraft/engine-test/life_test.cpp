// Checks life.h without GL or a game file: the limits and the pull back of the look, the blinks' timing, the breathing rate, the weight shifts and
// gestures' intervals, the saccades' size, determinism from a seed, bounds of every output over ten minutes.
//   g++ -std=c++17 -I../android/app/src/main/cpp/engine life_test.cpp -o build/life_test && build/life_test
#include <cmath>
#include <cstdio>
#include <vector>
#include "life.h"
using namespace wc::life;
static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { ++fails; std::printf("FAIL %s: ", #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

int main() {
    // ---- the look command: identity inside, easing to the limit, pulled back past it, odd and continuous
    {
        const float L = rad(70);
        CHECK(std::fabs(lookCommand(rad(30), L) - rad(30)) < 1e-5f, "inside the linear part");
        CHECK(lookCommand(rad(-30), L) == -lookCommand(rad(30), L), "odd");
        float prev = 0, peak = 0;
        for (float a = 0; a <= rad(220); a += rad(0.25f)) {
            const float y = lookCommand(a, L);
            CHECK(y >= -1e-6f && y <= L + 1e-5f, "never past the limit: %f", deg(y));
            CHECK(std::fabs(y - prev) < rad(1.0f), "continuous at %f", deg(a));
            peak = std::max(peak, y); prev = y;
        }
        CHECK(peak > 0.9f * L, "reaches nearly the limit: %f", deg(peak));
        CHECK(lookCommand(rad(180), L) < 1e-4f, "behind her she faces front: %f", deg(lookCommand(rad(180), L)));
        CHECK(lookCommand(rad(95), L) > lookCommand(rad(160), L), "pulled back as the camera goes round");
    }
    CHECK(std::fabs(kYawShares.spine + kYawShares.neck + kYawShares.head - 1) < 1e-5f && std::fabs(kPitchShares.spine + kPitchShares.neck + kPitchShares.head - 1) < 1e-5f, "shares sum to 1");
    CHECK(kYawShares.spine * 70 <= 15.0f && kYawShares.neck >= 0.3f && kYawShares.neck <= 0.36f && kYawShares.head >= 0.5f && kYawShares.head <= 0.55f, "shares as asked");
    // ---- the blink
    {
        CHECK(blinkShape(0) == 0 && blinkShape(0.065f) == 1.0f && blinkShape(0.2f) == 0, "blink ends");
        float closeT = 0, openT = 0;
        for (float t = 0; t < 0.2f; t += 0.001f) { if (blinkShape(t) < 0.999f && t < 0.07f) closeT = t; if (t > 0.07f && blinkShape(t) > 0.001f) openT = t; }
        CHECK(closeT < 0.07f && openT - 0.07f > closeT, "closing quicker than opening: %f vs %f", closeT, openT - 0.07f);
    }
    // ---- the breath
    CHECK(breathShape(0) < 0.01f && std::fabs(breathShape(2 * kPi * 0.4f) - 1.0f) < 1e-4f, "breath peaks at 40%%");
    // ---- ten minutes of life at 60 fps, every level, both gaze modes, a camera that slowly orbits, and a touch now and then
    for (int level = 0; level <= 3; ++level) for (int gaze = 0; gaze <= 2; ++gaze) {
        LifeState st; Params p; p.level = level; p.gaze = gaze; p.lookLimitDeg = 70;
        st.configure(p, 1234);
        LifeState twin; twin.configure(p, 1234);
        float blinkStart = -1, lastBlinkStart = -1; int blinks = 0, doubles = 0;
        std::vector<float> blinkGaps;
        float breaths = 0, prevB = 0; bool rising = false;
        int glances = 0, contact = 0, frames = 0, gestures = 0, shifts = 0; int prevGesture = 0; float prevSide = 0, lastShiftT = 0, lastGestT = -100;
        float minGestGap = 1e9f, minShiftGap = 1e9f;
        bool prevBlinking = false; float maxSacc = 0; float maxHeadYawDeg = 0, maxSpineDeg = 0, maxEyeOffDeg = 0;
        const float dt = 1.0f / 60.0f;
        for (int i = 0; i < 60 * 600; ++i) {
            const float t = float(i) * dt;
            Input in;
            in.camYaw = rad(60.0f) * std::sin(t * 0.05f) + rad(40.0f) * std::sin(t * 0.013f);
            in.camPitch = rad(10.0f) * std::sin(t * 0.07f);
            if (i == 60 * 100 + 30) { st.touch(kEventFlinch, rad(10), rad(-20), 1.0f); twin.touch(kEventFlinch, rad(10), rad(-20), 1.0f); }
            const Output o = st.step(dt, in);
            const Output o2 = twin.step(dt, in);
            CHECK(o.headYaw == o2.headYaw && o.lid[0] == o2.lid[0] && o.eyeOffYaw == o2.eyeOffYaw, "deterministic");
            // bounds
            const float totalYaw = o.spineYaw + o.neckYaw + o.headYaw;
            maxHeadYawDeg = std::max(maxHeadYawDeg, std::fabs(deg(totalYaw)));
            maxSpineDeg = std::max(maxSpineDeg, std::fabs(deg(o.spineYaw)));
            CHECK(std::fabs(totalYaw) <= rad(70.0f) * 1.001f + rad(6.0f), "turn within the limit: %f", deg(totalYaw));
            CHECK(std::fabs(deg(o.spineYaw)) <= 15.0f, "spine takes at most 15 degrees: %f", deg(o.spineYaw));
            CHECK(o.lid[0] >= 0 && o.lid[0] <= 1.0f && o.lid[1] >= 0 && o.lid[1] <= 1.0f && o.lidTrack >= 0 && o.lidTrack <= 1.0f, "lids 0..1");
            CHECK(std::fabs(deg(o.chestPitch)) < 2.0f && std::fabs(deg(o.waistRoll)) < 3.0f && std::fabs(o.shiftY) < 0.03f, "life amplitudes");
            maxEyeOffDeg = std::max(maxEyeOffDeg, std::fabs(deg(o.eyeOffYaw)));
            CHECK(std::fabs(deg(o.eyeOffYaw)) < 30.0f && std::fabs(deg(o.eyeOffPitch)) < 30.0f, "eye offsets small");
            for (float v : {o.headYaw, o.headPitch, o.neckYaw, o.spinePitch, o.impulsePitch, o.impulseRoll, o.headRoll}) CHECK(std::isfinite(v), "finite");
            if (level == 0) CHECK(o.chestPitch == 0 && o.waistRoll == 0 && o.gesture == kNone, "level 0 is still");
            if (gaze == 0) CHECK(o.headYaw == 0 && o.eyeWeight < 1e-3f && !o.gazeOn, "gaze off leaves the head and eyes");
            // blinks
            const bool blinking = o.lid[0] > 0.5f;
            if (blinking && !prevBlinking) {
                ++blinks;
                if (lastBlinkStart >= 0) { const float gap = t - lastBlinkStart; if (gap < 0.6f) ++doubles; else blinkGaps.push_back(gap); }
                lastBlinkStart = t;
            }
            prevBlinking = blinking;
            // breaths: count the peaks
            if (level > 0) { const float b = o.clavLift; if (b > prevB) rising = true; else if (rising && b < prevB) { rising = false; ++breaths; } prevB = b; }
            // eye contact: the eyes are on the camera unless a glance is on
            ++frames; if (o.gazeOn && o.eyeWeight > 0.9f && std::fabs(o.eyeOffYaw) < rad(3.0f) && std::fabs(o.eyeOffPitch) < rad(3.0f)) ++contact;
            if (o.gesture != kNone && prevGesture == kNone) { ++gestures; if (t - lastGestT < minGestGap) minGestGap = t - lastGestT; lastGestT = t; }
            prevGesture = o.gesture;
            (void)glances; (void)shifts; (void)prevSide; (void)lastShiftT; (void)minShiftGap; (void)maxSacc;
        }
        if (level > 0 || gaze > 0) {
            CHECK(blinks > 600 / 6 * 0.9f && blinks < 600 / 2.5f * 1.4f, "blink count %d (level %d gaze %d)", blinks, level, gaze);
            float mean = 0; for (float g : blinkGaps) mean += g; mean /= std::max<size_t>(1, blinkGaps.size());
            CHECK(mean > 2.2f && mean < 6.5f, "mean gap %f", mean);
            CHECK(doubles > 0, "some double blinks");
        }
        if (level > 0) {
            const float perMin = float(breaths) / 10.0f;
            CHECK(perMin > 11.0f && perMin < 17.0f, "breaths a minute %f", perMin);
        }
        if (gaze > 0 && level > 0) {
            const float held = float(contact) / float(frames);
            CHECK(held > 0.55f && held < 0.95f, "eye contact held %f of the time (level %d gaze %d)", held, level, gaze);
            CHECK(maxEyeOffDeg < 30.0f, "glances stay small");
        }
        if (level >= 2) {
            CHECK(gestures >= 600 / 35 && gestures <= 600 / 6, "gestures %d (level %d)", gestures, level);
            CHECK(minGestGap >= 7.5f, "gestures apart: %f", minGestGap);
        }
        if (level == 3 && gaze == 2) std::printf("lively+wander: blinks %d (doubles %d), breaths/min %.1f, contact %.2f, gestures %d, max turn %.1f deg, max spine %.1f deg\n", blinks, doubles, breaths / 10.0f, float(contact) / frames, gestures, maxHeadYawDeg, maxSpineDeg);
    }
    // ---- the camera behind her: the eyes let go, the head faces front
    {
        LifeState st; Params p; p.level = 2; p.gaze = 1; st.configure(p, 9);
        Output o;
        for (int i = 0; i < 600; ++i) { Input in; in.camYaw = rad(170); o = st.step(1.0f / 60, in); }
        CHECK(std::fabs(deg(o.headYaw + o.neckYaw + o.spineYaw)) < 3.0f, "faces front with the camera behind: %f", deg(o.headYaw + o.neckYaw + o.spineYaw));
        CHECK(o.eyeWeight < 0.05f, "eyes ignore a camera behind her: %f", o.eyeWeight);
        for (int i = 0; i < 600; ++i) { Input in; in.camYaw = rad(40); o = st.step(1.0f / 60, in); }
        CHECK(std::fabs(deg(o.headYaw + o.neckYaw + o.spineYaw) - 40.0f) < 3.0f, "turns to a camera 40 degrees round: %f", deg(o.headYaw + o.neckYaw + o.spineYaw));
        CHECK(o.eyeWeight > 0.95f, "eyes back on it");
        // a smaller limit stops the head sooner
        LifeState s2; Params p2; p2.level = 2; p2.gaze = 1; p2.lookLimitDeg = 30; s2.configure(p2, 9);
        for (int i = 0; i < 600; ++i) { Input in; in.camYaw = rad(40); o = s2.step(1.0f / 60, in); }
        CHECK(deg(o.headYaw + o.neckYaw + o.spineYaw) < 31.0f, "limit 30: %f", deg(o.headYaw + o.neckYaw + o.spineYaw));
    }
    // ---- the head lags the camera, the eyes lead it
    {
        LifeState st; Params p; p.level = 0; p.gaze = 1; st.configure(p, 3);
        for (int i = 0; i < 300; ++i) st.step(1.0f / 60, Input());
        Input in; in.camYaw = rad(40);
        Output o; float at80 = 0;
        for (int i = 0; i < 5; ++i) { o = st.step(1.0f / 60, in); at80 = o.headYaw + o.neckYaw + o.spineYaw; }
        CHECK(at80 < rad(15.0f), "the head is still on its way after 80 ms: %f", deg(at80));
    }
    // ---- a touch turns the head toward it and back
    {
        LifeState st; Params p; p.level = 2; p.gaze = 1; st.configure(p, 5);
        for (int i = 0; i < 300; ++i) st.step(1.0f / 60, Input());
        st.touch(kEventAttend, rad(50), rad(-30), 1.0f);
        float maxYaw = 0; Output o;
        for (int i = 0; i < 90; ++i) { o = st.step(1.0f / 60, Input()); maxYaw = std::max(maxYaw, o.headYaw + o.neckYaw + o.spineYaw); }
        CHECK(maxYaw > rad(8.0f), "turns toward the touch: %f", deg(maxYaw));
        for (int i = 0; i < 300; ++i) o = st.step(1.0f / 60, Input());
        CHECK(std::fabs(o.headYaw + o.neckYaw + o.spineYaw) < rad(2.0f), "and back to the camera");
    }
    std::printf(fails ? "life_test: %d FAILED\n" : "life_test: ok\n", fails);
    return fails ? 1 : 0;
}
