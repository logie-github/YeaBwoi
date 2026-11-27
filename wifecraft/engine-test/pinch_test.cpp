// The pinch's maths alone (engine/contact.h: no GL, no game files): build/pinch_test, exit 0 when all pass.
// The profile (dent, ridge, dent), its volume, the spring, the tracker's pairing and its merge rules, symmetry, degenerate input.
#include <cmath>
#include <cstdio>
#include <vector>

#include "contact.h"

using namespace wc;
static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::printf("FAIL %s:%d: %s  ", __FILE__, __LINE__, #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static const float kPad = kPadDefaultWorld;            // 1.1 cm in world units
static const float kCm = 0.01f / kYardM;               // one centimetre in world units

static PinchOut shape(float ridge, float dent = 0.0f, float lengthCm = 4) {
    PinchOut o;
    o.a = V3(0, -0.5f * lengthCm * kCm, 0); o.b = V3(0, 0.5f * lengthCm * kCm, 0); o.normal = V3(0, 0, 1);   // the fingers along y, the crest across along x
    o.pad = kPad; o.radius = kPinchWidthMul * kPad; o.ridge = ridge; o.dent = dent; o.squeeze = 0; o.blanch = 1; o.flush = 1; o.taut = 0; o.lean = 0; o.ripple = 0;
    return o;
}
static PinchIn pin(uint32_t key, V3 a, V3 b, float l0, int region = kCrThigh) {
    PinchIn p; p.key = key; p.a = a; p.b = b; p.normal = V3(0, 0, 1); p.pad = kPad; p.l0 = l0; p.pressure = 1; p.region = region; return p;
}
static void runPinch(ContactSolver& s, float seconds, float fps, const PinchIn* held, int n) {
    int frames = int(seconds * fps + 0.5f);
    for (int i = 0; i < frames; ++i) { s.begin(); for (int k = 0; k < n; ++k) s.addPinch(held[k]); s.step(1.0f / fps); }
}
static PinchTouch touch(int pointer, V3 pos, int region = kCrThigh, float pressure = 1) {
    PinchTouch t; t.pointer = pointer; t.pos = pos; t.normal = V3(0, 0, 1); t.pad = kPad; t.pressure = pressure; t.region = region; return t;
}

int main() {
    // the fold: a wall across the line joining the pads (crest along the pad width), zero at the pads' centres, symmetric, tapering at its ends
    {
        const float L = 3 * kCm, w = kPinchWidthMul * kPad, H = 0.015f / kYardM;
        CHECK(pinchFold(0, 0, L, w, H) == H, "crest height");
        CHECK(pinchFold(0.5f * L, 0, L, w, H) == 0 && pinchFold(0, w, L, w, H) == 0 && pinchFold(2 * L, 0, L, w, H) == 0, "zero at the pads' centres and at the ends and beyond");
        CHECK(std::fabs(pinchFold(0.3f * L, 0.4f * w, L, w, H) - pinchFold(-0.3f * L, -0.4f * w, L, w, H)) < 1e-9f, "symmetric");
        // steep flanks: the wall rises most of its height within a third of the gap, and the crest is rounded (flat near the top)
        CHECK(pinchFold(0.25f * L, 0, L, w, H) > 0.5f * H && pinchFold(0.05f * L, 0, L, w, H) > 0.98f * H, "wall and crest");
        // the ends taper: half height at 0.4 of the half-width or so, a fingertip's width; the foot eases in (no hard edge: slope 0 at the base)
        float half = 0;
        for (float t = 0; t < w; t += w / 400) if (pinchFold(0, t, L, w, H) < 0.5f * H) { half = t; break; }
        CHECK(2 * half / kCm > 1.4f && 2 * half / kCm < 3.2f, "fold width at half height %.2f cm", 2 * half / kCm);
        const float foot = (pinchFold(0.5f * L, 0, L, w, H) - pinchFold(0.5f * L - 1e-5f, 0, L, w, H)) / 1e-5f;
        const float flank = (pinchFold(0.25f * L, 0, L, w, H) - pinchFold(0.25f * L + 1e-5f, 0, L, w, H)) / 1e-5f;
        CHECK(std::fabs(foot) < 0.2f * flank, "the foot eases in (slope %g against %g mid-flank)", foot, flank);
        CHECK(2 * w / kCm > 4.0f && 2 * w / kCm < 5.2f, "fold base width %.2f cm", 2 * w / kCm);
        // the height cap: no taller than the gap can hold
        CHECK(pinchFoldHeight(10.0f, L, kPad) < 1.1f * L + 0.3f * kPad + 1e-6f && pinchFoldHeight(-1, L, kPad) == 0 && pinchFoldHeight(0.001f, L, kPad) == 0.001f, "fold cap");
        // soft over stiff: the tallest fold per region
        CHECK(pinchHeightMax(kPad, 1.0f) > pinchHeightMax(kPad, 0.5f) && pinchHeightMax(kPad, 0.5f) > pinchHeightMax(kPad, 0.0f) && pinchHeightMax(kPad, 0.8f) / kCm < 2.1f, "tallest fold: %g cm", pinchHeightMax(kPad, 0.8f) / kCm);
    }
    {
        PinchOut o = shape(0.011f / kYardM, 0.5f * 0.011f / kYardM);
        const V3 n(0, 0, 1);
        const float mid = pinchDisplace(o, V3(0, 0, 0), n).z;
        const float tip = pinchDisplace(o, o.a, n).z;
        const float beyond = pinchDisplace(o, V3(0, o.b.y + 0.5f * o.radius, 0), n).z;
        CHECK(mid > 0.6f * o.ridge && mid <= o.ridge + 1e-6f, "the mesh follows the fold (lower: %g of %g)", mid, o.ridge);
        CHECK(tip < mid * 0.5f, "fingertip lower than the crest (%g vs %g)", tip, mid);
        CHECK(tip < 0, "a fingertip's own dent shows when it is deep enough: %g", tip);
        CHECK(beyond < mid && std::fabs(pinchDisplace(o, V3(1, 1, 0), n).z) < 1e-9f, "falls off, far points still");
        CHECK(std::fabs(pinchDisplace(o, V3(1.5f * o.radius, 0, 0), n).z) < 1e-6f, "zero past the fold");
        // the far side of a thin part does not move
        CHECK(std::fabs(pinchDisplace(o, V3(0, 0, 0), V3(0, 0, -1)).z) < 1e-4f, "back side stays");
        // the crest runs across the pull: along x the height holds over the pad width, along y it falls away
        CHECK(pinchDisplace(o, V3(0.4f * o.radius, 0, 0), n).z > 0.8f * mid && pinchDisplace(o, V3(0, 0.45f * (o.b.y - o.a.y), 0), n).z < 0.6f * mid, "wall, not a blob");
    }
    // symmetry: swapping the fingers gives the same field, and the field is mirror-symmetric across the pull and across the crest
    {
        PinchOut o = shape(0.01f, 0.003f), q = o; std::swap(q.a, q.b);
        o.squeeze = q.squeeze = 0.2f; o.lean = q.lean = 0;
        for (float x = -0.04f; x <= 0.04f; x += 0.005f) for (float y = -0.03f; y <= 0.03f; y += 0.006f) {
            V3 p(x, y, 0), n(0, 0, 1);
            V3 d1 = pinchDisplace(o, p, n), d2 = pinchDisplace(q, p, n);
            CHECK((d1 - d2).length() < 1e-6f, "swap at %g %g", x, y);
            V3 m = pinchDisplace(o, V3(x, -y, 0), n);
            CHECK(std::fabs(m.z - d1.z) < 1e-6f && std::fabs(m.y + d1.y) < 1e-6f && std::fabs(m.x - d1.x) < 1e-6f, "mirror across the crest at %g %g", x, y);
            V3 m2 = pinchDisplace(o, V3(-x, y, 0), n);
            CHECK(std::fabs(m2.z - d1.z) < 1e-6f && std::fabs(m2.x + d1.x) < 1e-6f, "mirror across the pull at %g %g", x, y);
        }
    }
    // volume of the fold the mesh follows: positive, linear in the height, growing with the gap
    {
        auto vol = [&](float ridge, float cm) {
            PinchOut o = shape(ridge, 0, cm); double up = 0; const double h = 0.0006 / kYardM;
            for (double x = -0.12; x < 0.12; x += h) for (double y = -0.12; y < 0.12; y += h) { float z = pinchDisplace(o, V3(float(x), float(y), 0), V3(0, 0, 1)).z; if (z > 0) up += z * h * h; }
            return up;
        };
        const float r = 0.008f / kYardM;
        const double v1 = vol(r, 3), v2 = vol(2 * r, 3), v3 = vol(r, 5);
        CHECK(v1 > 0 && std::fabs(v2 / v1 - 2) < 0.02, "linear in height: %g", v2 / v1);
        CHECK(v3 > v1, "wider gap, more skin held");
    }
    // the squeeze draws the skin toward the crest line along the pull (strongest at the pads, fading outside), never lifts it; the middle and far points stay
    {
        PinchOut o = shape(0.012f, 0); o.squeeze = 0.18f; o.lean = 0;
        const V3 n(0, 0, 1);
        V3 nearPad = pinchDisplace(o, V3(0, o.b.y + 0.3f * kPad, 0), n), farOut = pinchDisplace(o, V3(0, o.b.y + 2.5f * kPad, 0), n);
        CHECK(nearPad.y < 0 && farOut.y < 0 && std::fabs(nearPad.y) > std::fabs(farOut.y), "drawn in, more near the pad: %g %g", nearPad.y, farOut.y);
        CHECK(std::fabs(nearPad.x) < 1e-7f, "along the pull only");
        V3 other = pinchDisplace(o, V3(0, o.a.y - 0.3f * kPad, 0), n);
        CHECK(other.y > 0, "the other side drawn the other way");
        CHECK(pinchDisplace(o, V3(0, 0, 0), n).y == 0, "the middle stays");
        CHECK(pinchDisplace(o, V3(1, 1, 0), n).length() == 0, "far point");
        // the lean toward gravity moves the fold along the surface, down
        PinchOut l = shape(0.012f, 0); l.normal = V3(1, 0, 0); l.a = V3(0, -0.015f, 0); l.b = V3(0, 0.015f, 0); l.lean = 0.2f;
        V3 d = pinchDisplace(l, V3(0, 0, 0), V3(1, 0, 0));
        CHECK(d.z < 0 && d.x > 0, "leans down, lifts out: %g %g", d.z, d.x);
    }
    // the ridge asked for: gathered skin, half as height, up to 1.6 pads, soft over stiff, none when pulled apart (taut instead)
    {
        float taut = -1;
        const float l0 = 6 * kCm;
        float r3 = pinchRidgeTarget(3 * kCm, l0, kPad, 1.0f, &taut);
        float r15 = pinchRidgeTarget(1.5f * kCm, l0, kPad, 1.0f, &taut);
        float r6 = pinchRidgeTarget(6 * kCm, l0, kPad, 1.0f, &taut);
        CHECK(r6 == 0 && taut == 0, "no fold at the start");
        CHECK(r3 > 0 && r15 >= r3 && r15 <= pinchHeightMax(kPad, 1.0f) && r3 <= pinchHeightMax(kPad, 1.0f), "grows with the closing and saturates at the tissue: %g %g", r3, r15);
        CHECK(pinchRidgeTarget(5.5f * kCm, l0, kPad, 1.0f) < 0.7f * kCm, "starts gently: %g cm at 5 mm closed", pinchRidgeTarget(5.5f * kCm, l0, kPad, 1.0f) / kCm);
        CHECK(pinchRidgeTarget(3 * kCm, l0, kPad, 0.1f) < 0.7f * r3 && pinchRidgeTarget(3 * kCm, l0, kPad, 0.9f) > pinchRidgeTarget(3 * kCm, l0, kPad, 0.5f), "soft over stiff");
        CHECK(r15 / kCm < 2.2f, "the fold is at most 2.2 cm: %g cm", r15 / kCm);
        float apart = pinchRidgeTarget(8 * kCm, l0, kPad, 1.0f, &taut);
        CHECK(apart == 0 && taut > 0.5f && taut <= 1, "stretched: no ridge, taut %g", taut);
        CHECK(pinchRidgeTarget(NAN, l0, kPad, 1) == 0 && pinchRidgeTarget(1, 0, kPad, 1) == 0 && pinchRidgeTarget(-5, l0, kPad, 1) <= pinchHeightMax(kPad, 1.0f) + 1e-6f, "degenerate");
    }
    // the solver's spring: the fold settles in about 150 ms, springs back on release with a slight rebound, 20 and 60 fps agree
    {
        float at60 = 0, at20 = 0;
        for (float fps : {60.0f, 20.0f}) {
            ContactSolver s;
            PinchIn p = pin(1, V3(-1.5f * kCm, 0, 0), V3(1.5f * kCm, 0, 0), 6 * kCm);
            runPinch(s, 0.08f, fps, &p, 1);
            const float early = s.ridgeOf(1);
            runPinch(s, 0.8f, fps, &p, 1);
            const float held = s.ridgeOf(1);
            const float want = pinchRidgeTarget(3 * kCm, 6 * kCm, kPad, contactRegion(kCrThigh).softness);
            CHECK(std::fabs(held - want) < 0.03f * want, "settles to %g (want %g) at %g fps", held, want, fps);
            CHECK(early < held && early > 0.2f * held, "rising at 80 ms: %g of %g", early, held);
            float peak = held;
            for (int i = 0; i < int(1.5f * fps); ++i) { s.begin(); s.step(1.0f / fps); peak = std::min(peak, s.ridgeOf(1)); }
            CHECK(s.ridgeOf(1) < 0.02f * held, "sprang back at %g fps: %g", fps, s.ridgeOf(1));
            (fps > 30 ? at60 : at20) = held;
        }
        CHECK(std::fabs(at60 - at20) < 0.02f * at60, "frame rate independent");
    }
    // the solver draws it: a pinch takes two of the eight; fingers' dent is small next to the ridge; releasing frees it
    {
        ContactSolver s;
        PinchIn p = pin(1, V3(-1.5f * kCm, 0, 0), V3(1.5f * kCm, 0, 0), 6 * kCm);
        runPinch(s, 0.7f, 60, &p, 1);
        CHECK(s.pinchCount() == 1, "drawn");
        const PinchOut& o = s.pinchOut()[0];
        CHECK(o.dent > 0 && o.dent < 0.5f * o.ridge, "fingertip dent %g small beside the ridge %g", o.dent, o.ridge);
        CHECK(o.flush > 0.3f && o.blanch > 0.3f && std::fabs(o.radius - kPinchWidthMul * kPad) < 1e-6f, "shading %g %g", o.flush, o.blanch);
        // cap: Low (4): a pinch (2) leaves two for the rest; the governor's step 1 the same; off: none
        ContactSolver lo; lo.setLevel(kContactLow);
        runPinch(lo, 0.7f, 60, &p, 1);
        CHECK(lo.pinchCount() == 1, "pinch at Low");
        ContactSolver off; off.setLevel(kContactOff);
        runPinch(off, 0.7f, 60, &p, 1);
        CHECK(off.pinchCount() == 0, "none when off");
        ContactSolver load; load.setLoad(2);
        runPinch(load, 0.7f, 60, &p, 1);
        CHECK(load.pinchCount() == 0, "none under the governor's last step");
        // stage contacts lose two slots to a pinch at Low; fingers' touches first
        ContactSolver mix; mix.setLevel(kContactLow);
        for (int f = 0; f < 90; ++f) {
            mix.begin(); mix.addPinch(p);
            for (int k = 0; k < 4; ++k) { ContactIn c; c.key = 50u + k; c.pos = V3(1 + 0.3f * k, 0, 0); c.normal = V3(0, 0, 1); c.depth = 0.02f; c.radius = 0.05f; c.region = kCrButt; mix.add(c); }
            mix.step(1.0f / 60);
        }
        CHECK(mix.pinchCount() == 1 && mix.count() <= 2, "the pinch's two slots out of four: %d contacts", mix.count());
        // two pinches (four fingers) at High: both drawn
        ContactSolver two;
        PinchIn q[2] = {p, pin(2, V3(0.2f, -1.5f * kCm, 0), V3(0.2f, 1.5f * kCm, 0), 6 * kCm)};
        runPinch(two, 0.7f, 60, q, 2);
        CHECK(two.pinchCount() == 2, "two pinches %d", two.pinchCount());
    }
    // the tracker: pairing rules
    {
        const float wpm = 1.0f / kYardM;
        PinchTracker tr; PinchIn out[kPinchMax];
        // two fingers 6 cm apart on a thigh: a candidate only (plain touches) until they close
        PinchTouch t[2] = {touch(0, V3(-3 * kCm, 0, 0)), touch(1, V3(3 * kCm, 0, 0))};
        CHECK(tr.update(t, 2, wpm, out, 0x100) == 0 && tr.pairs() == 1 && !tr.pinching(0), "candidate, not a pinch");
        t[0].pos = V3(-2.9f * kCm, 0, 0); t[1].pos = V3(2.9f * kCm, 0, 0);
        CHECK(tr.update(t, 2, wpm, out, 0x100) == 0, "closed by 2 mm: under the 4 mm onset, still plain touches");
        t[0].pos = V3(-2.5f * kCm, 0, 0); t[1].pos = V3(2.3f * kCm, 0, 0);
        int n = tr.update(t, 2, wpm, out, 0x100);
        CHECK(n == 1 && tr.pinching(0) && tr.pinching(1) && std::fabs(out[0].l0 - 6 * kCm) < 1e-6f, "latched with l0 6 cm: n %d l0 %g", n, out[0].l0);
        // keeps going as they close to 1.5 cm and to touching (below the 1 cm formation limit)
        t[0].pos = V3(-0.75f * kCm, 0, 0); t[1].pos = V3(0.75f * kCm, 0, 0);
        CHECK(tr.update(t, 2, wpm, out, 0x100) == 1 && out[0].l0 > 5.9f * kCm, "held at 1.5 cm");
        t[0].pos = V3(-0.2f * kCm, 0, 0); t[1].pos = V3(0.2f * kCm, 0, 0);
        CHECK(tr.update(t, 2, wpm, out, 0x100) == 1, "held below 1 cm once started");
        // apart again: still the pinch (taut) until 10 cm, then let go
        t[0].pos = V3(-4 * kCm, 0, 0); t[1].pos = V3(4 * kCm, 0, 0);
        CHECK(tr.update(t, 2, wpm, out, 0x100) == 1, "pulled to 8 cm: taut, still a pinch");
        t[0].pos = V3(-6 * kCm, 0, 0); t[1].pos = V3(6 * kCm, 0, 0);
        CHECK(tr.update(t, 2, wpm, out, 0x100) == 0 && tr.pairs() == 0, "past 10 cm: let go");
        // a finger lifts: the pinch ends; the other is a plain touch
        PinchTracker t2; PinchTouch s2[2] = {touch(0, V3(-3 * kCm, 0, 0)), touch(1, V3(3 * kCm, 0, 0))}; t2.update(s2, 2, wpm, out, 0);
        PinchTouch c2[2] = {touch(0, V3(-1 * kCm, 0, 0)), touch(1, V3(1 * kCm, 0, 0))};
        CHECK(t2.update(c2, 2, wpm, out, 0) == 1, "closing");
        CHECK(t2.update(c2, 1, wpm, out, 0) == 0 && !t2.pinching(0) && !t2.pinching(1), "a finger lifted");
        // too close to begin with (under 1 cm), too far (over 8 cm): no pair
        PinchTracker t3;
        PinchTouch near2[2] = {touch(0, V3(0, 0, 0)), touch(1, V3(0.5f * kCm, 0, 0))};
        PinchTouch far2[2] = {touch(0, V3(0, 0, 0)), touch(1, V3(9 * kCm, 0, 0))};
        t3.update(near2, 2, wpm, out, 0); CHECK(t3.pairs() == 0, "under 1 cm: one blob, no pair");
        t3.update(far2, 2, wpm, out, 0); CHECK(t3.pairs() == 0, "over 8 cm: two touches");
        // stiff regions (shin, skull, ribs) do not pinch; different skins facing away do not
        PinchTouch shin[2] = {touch(0, V3(0, 0, 0), kCrShin), touch(1, V3(4 * kCm, 0, 0), kCrShin)};
        t3.update(shin, 2, wpm, out, 0); CHECK(t3.pairs() == 0, "a shin does not pinch");
        PinchTouch away[2] = {touch(0, V3(0, 0, 0)), touch(1, V3(4 * kCm, 0, 0))};
        away[1].normal = V3(0, 0, -1);
        t3.update(away, 2, wpm, out, 0); CHECK(t3.pairs() == 0, "opposite sides of a thin part do not pinch");
        // three fingers: the nearest two pair, the third alone; four fingers: two pairs
        PinchTracker t4;
        PinchTouch three[3] = {touch(0, V3(0, 0, 0)), touch(1, V3(2 * kCm, 0, 0)), touch(2, V3(7 * kCm, 0, 0))};
        t4.update(three, 3, wpm, out, 0);
        CHECK(t4.pairs() == 1 && !t4.pinching(2), "three fingers: one pair");
        PinchTracker t5;
        PinchTouch four[4] = {touch(0, V3(0, 0, 0)), touch(1, V3(3 * kCm, 0, 0)), touch(2, V3(0, 20 * kCm, 0)), touch(3, V3(3 * kCm, 20 * kCm, 0))};
        t5.update(four, 4, wpm, out, 0);
        CHECK(t5.pairs() == 2, "four fingers: two pairs (%d)", t5.pairs());
        // degenerate: NaN positions, duplicates, nothing
        PinchTracker t6;
        PinchTouch bad[2] = {touch(0, V3(NAN, 0, 0)), touch(1, V3(0, 0, 0))};
        CHECK(t6.update(bad, 2, wpm, out, 0) == 0 && t6.update(nullptr, 0, wpm, out, 0) == 0 && t6.update(bad, 2, 0.0f, out, 0) == 0, "NaN, none, zero scale");
        // the pad scales the thresholds through wpm: 2x fingers pinch from twice as far
        PinchTracker t7;
        PinchTouch wide[2] = {touch(0, V3(0, 0, 0)), touch(1, V3(14 * kCm, 0, 0))};
        t7.update(wide, 2, wpm, out, 0); CHECK(t7.pairs() == 0, "14 cm: out of reach at 1x");
        t7.update(wide, 2, 2 * wpm, out, 0); CHECK(t7.pairs() == 1, "7 cm of a 2x finger: in reach");
    }
    // hostile input to the solver
    {
        ContactSolver s;
        PinchIn bad = pin(1, V3(NAN, 0, 0), V3(), 1);
        s.begin(); s.addPinch(bad); s.step(0.016f);
        PinchIn same = pin(2, V3(), V3(), 6 * kCm);   // both fingers on one point: a point blob, finite
        runPinch(s, 0.5f, 60, &same, 1);
        const PinchOut& o = s.pinchOut()[0];
        CHECK(s.pinchCount() == 1 && std::isfinite(o.ridge) && std::isfinite(pinchDisplace(o, V3(0.01f, 0, 0), V3(0, 0, 1)).z), "coincident fingertips stay finite");
        s.begin(); s.step(-5); s.begin(); s.step(NAN); s.begin(); s.step(1e9f);
        CHECK(std::isfinite(s.ridgeOf(2)), "dt hostile");
    }
    if (failures == 0) std::printf("pinch_test: all passed\n");
    return failures ? 1 : 0;
}
