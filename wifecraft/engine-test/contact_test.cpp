// Flesh contact's maths alone (engine/contact.h: no GL, no game files): build/contact_test, exit 0 when all pass.
#include <cmath>
#include <cstdio>
#include <cstdlib>

#include "contact.h"

using namespace wc;
static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::printf("FAIL %s:%d: %s  ", __FILE__, __LINE__, #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static ContactIn press(uint32_t key, V3 pos, float depth, int region = kCrButt, float radius = 0.05f) {
    ContactIn c; c.key = key; c.pos = pos; c.normal = V3(1, 0, 0); c.depth = depth; c.region = region; c.radius = radius; return c;
}
static void run(ContactSolver& s, float seconds, float fps, const ContactIn* held, int n) {
    int frames = int(seconds * fps + 0.5f);
    for (int i = 0; i < frames; ++i) { s.begin(); for (int k = 0; k < n; ++k) s.add(held[k]); s.step(1.0f / fps); }
}

int main() {
    // falloff and ring
    CHECK(contactFalloff(0) == 1 && contactFalloff(1) == 0 && contactFalloff(2) == 0, "falloff ends");
    for (float q = 0; q < 1; q += 0.05f) CHECK(contactFalloff(q) >= contactFalloff(q + 0.05f) - 1e-6f, "monotone at %g", q);
    CHECK(contactRing(0.5f) == 0 && contactRing(1.0f) > 0.99f && contactRing(1.7f) == 0, "ring profile");

    // volume: the ring keeps the share of the dent's volume it was asked for (numeric integral over the plane)
    for (float share : {0.25f, 0.3f, 0.4f}) {
        ContactOut c; c.pos = V3(); c.normal = V3(0, 0, 1); c.radius = 0.05f; c.depth = 0.02f; c.bulge = c.depth * contactBulgeGain(share);
        double dent = 0, up = 0; const double h = 0.0005;
        for (double x = -0.1; x < 0.1; x += h) for (double y = -0.1; y < 0.1; y += h) {
            V3 p(float(x), float(y), 0);
            V3 d = contactDisplace(c, p, V3(0, 0, 1));
            // (squeeze is tangential: volume-neutral here)
            c.squeeze = 0;
            if (d.z < 0) dent += -d.z * h * h; else up += d.z * h * h;
        }
        CHECK(std::fabs(up / dent - share) < 0.03, "bulge share %g got %g", share, up / dent);
        double dentExpect = 3.14159265 * 0.05 * 0.05 * 0.02 / 3;
        CHECK(std::fabs(dent / dentExpect - 1) < 0.05, "dent volume %g vs %g", dent, dentExpect);
    }
    // the far side of a thin part does not move; far points do not move; no NaN
    {
        ContactOut c; c.pos = V3(); c.normal = V3(1, 0, 0); c.radius = 0.05f; c.depth = 0.03f; c.bulge = 0.003f; c.squeeze = 0.2f;
        V3 back = contactDisplace(c, V3(0.001f, 0, 0), V3(-1, 0, 0));
        CHECK(back.length() < 1e-3f, "far side moved %g", back.length());
        V3 far = contactDisplace(c, V3(1, 1, 1), V3(1, 0, 0));
        CHECK(far.length() == 0, "far moved");
        V3 on = contactDisplace(c, V3(0, 0, 0), V3(1, 0, 0));
        CHECK(std::fabs(on.x + 0.03f) < 1e-5f, "centre moves by the depth: %g", on.x);
        float bl, fl; contactDisplace(c, V3(0, 0, 0), V3(1, 0, 0), &bl, &fl);
        CHECK(bl == 0, "blanch 0 when ContactOut.blanch is 0");
        // the normal's angle change near the dent: a finite-difference on the dent surface (the shader's own recompute)
        float maxAng = 0;
        for (float x = -0.06f; x <= 0.06f; x += 0.002f) {
            V3 p0(0, x, 0), p1(0, x + 0.002f, 0);
            V3 a = p0 + contactDisplace(c, p0, V3(1, 0, 0)), b = p1 + contactDisplace(c, p1, V3(1, 0, 0));
            V3 t = (b - a).normalized();
            maxAng = std::max(maxAng, std::fabs(std::atan2(t.x, t.y)));
        }
        std::printf("info: 3 cm dent, radius 5 cm: max surface normal angle change %.1f deg\n", maxAng * 57.2958f);
    }

    // dynamics: a press settles quickly
    for (float fps : {60.0f, 20.0f}) {
        ContactSolver s;
        ContactIn c = press(1, V3(), 0.03f);
        s.begin(); s.add(c); s.step(0.0f);
        run(s, 0.15f, fps, &c, 1);
        float target = std::min(0.03f * contactRegion(kCrButt).softness, contactRegion(kCrButt).maxDepth);
        CHECK(s.depthOf(1) > 0.9f * target, "fps %g: press at 150ms %g of %g", fps, s.depthOf(1), target);
        run(s, 0.5f, fps, &c, 1);
        CHECK(std::fabs(s.depthOf(1) - target) < 0.0015f, "fps %g: settled %g of %g", fps, s.depthOf(1), target);
        // release: springs back in 200..400 ms, a slight rebound at most
        float minDepth = 1, t95 = 0;
        int frames = int(1.0f * fps);
        for (int i = 0; i < frames; ++i) {
            s.begin(); s.step(1.0f / fps);
            float d = s.depthOf(1);
            minDepth = std::min(minDepth, d);
            if (d > 0.05f * target) t95 = (i + 2) / fps;   // (the last time still above 5%)
        }
        CHECK(t95 > 0.12f && t95 < 0.45f, "fps %g: released in %g s", fps, t95);
        CHECK(minDepth > -0.25f * target, "fps %g: rebound %g", fps, minDepth);
        CHECK(s.flushOf(1) > 0.1f || s.alive() == 0, "flush outlives the touch at fps %g", fps);
        run(s, 6.0f, fps, nullptr, 0);
        CHECK(s.alive() == 0 && s.count() == 0, "fps %g: all gone after 6 s (alive %d)", fps, s.alive());
    }
    // stiff regions dent less and softness scales; Softness 0 and Off draw nothing
    {
        ContactSolver a, b, z, off;
        z.setSoftness(0); off.setLevel(kContactOff);
        ContactIn soft = press(1, V3(), 0.03f, kCrBreast), stiff = press(1, V3(), 0.03f, kCrSkull);
        run(a, 0.6f, 60, &soft, 1); run(b, 0.6f, 60, &stiff, 1); run(z, 0.6f, 60, &soft, 1); run(off, 0.6f, 60, &soft, 1);
        CHECK(a.depthOf(1) > 5 * b.depthOf(1), "soft %g stiff %g", a.depthOf(1), b.depthOf(1));
        CHECK(z.count() == 0 || z.out()[0].depth < 1e-6f, "softness 0");
        CHECK(off.count() == 0, "off");
        ContactSolver dbl; dbl.setSoftness(2);
        run(dbl, 0.6f, 60, &soft, 1);
        CHECK(dbl.depthOf(1) > 1.5f * a.depthOf(1), "softness 200%% deeper: %g vs %g", dbl.depthOf(1), a.depthOf(1));
    }
    // merge and cap: 20 contacts, at most 8 (4 at Low, 4 under load, 0 at the lowest step); nothing pops
    {
        ContactSolver s;
        ContactIn in[20];
        for (int i = 0; i < 20; ++i) in[i] = press(uint32_t(i + 1), V3(0.0f, 0.12f * (i % 10), 0.12f * (i / 10)), 0.02f + 0.001f * i, kCrThigh);
        run(s, 0.5f, 60, in, 20);
        CHECK(s.count() <= kContactMax && s.count() > 0, "cap: %d", s.count());
        s.setLevel(kContactLow); run(s, 1.0f, 60, in, 20);
        CHECK(s.count() <= 4, "low cap %d", s.count());
        s.setLevel(kContactHigh); s.setLoad(1); run(s, 1.0f, 60, in, 20);
        CHECK(s.count() <= 4, "load cap %d", s.count());
        s.setLoad(2); run(s, 1.0f, 60, in, 20);
        CHECK(s.count() == 0, "lowest step: off (%d)", s.count());
        // switching the cap does not pop: the total change per frame stays small
        s.setLoad(0); float prev = 0, worst = 0;
        for (int f = 0; f < 90; ++f) {
            s.begin(); for (auto& c : in) s.add(c); s.step(1 / 60.0f);
            float tot = 0; for (int i = 0; i < s.count(); ++i) tot += s.out()[i].depth;
            if (f) worst = std::max(worst, std::fabs(tot - prev));
            prev = tot;
        }
        CHECK(worst < 0.03f, "pop: %g m between frames", worst);
        // two contacts one on the other merge into one dent
        ContactSolver m; ContactIn two[2] = {press(1, V3(), 0.03f), press(2, V3(0.005f, 0, 0), 0.03f)};
        run(m, 0.6f, 60, two, 2);
        CHECK(m.count() == 1, "merged: %d", m.count());
        CHECK(m.out()[0].depth <= contactRegion(kCrButt).maxDepth * 1.7f, "merged depth bounded %g", m.out()[0].depth);
    }
    // hostile input: NaN, inf, huge, negative, dt 0 and huge
    {
        ContactSolver s;
        float nan = std::nanf(""), inf = INFINITY;
        ContactIn bad[5] = {press(1, V3(nan, 0, 0), 0.02f), press(2, V3(0, 0, 0), inf), press(3, V3(0, 0, 0), -5), press(4, V3(0, 0, 0), 99), press(5, V3(0, 0, 0), 0.02f, kCrButt, nan)};
        for (float dt : {0.0f, 0.016f, 5.0f, -1.0f, nan}) {
            s.begin(); for (auto& c : bad) s.add(c); s.step(dt);
            for (int i = 0; i < s.count(); ++i) {
                const ContactOut& o = s.out()[i];
                CHECK(std::isfinite(o.depth) && std::isfinite(o.bulge) && std::isfinite(o.pos.x) && o.depth <= 0.07f, "bad out");
            }
        }
        // going back and forth in time (a restart) keeps everything finite
        run(s, 1.0f, 60, bad, 5);
        for (int i = 0; i < s.count(); ++i) CHECK(std::isfinite(s.out()[i].depth), "finite");
    }
    // pressure shading: blanch follows the dent, flush stays after
    {
        ContactSolver s; ContactIn c = press(1, V3(), 0.03f, kCrButt);
        run(s, 0.5f, 60, &c, 1);
        float b1 = s.out()[0].blanch, f1 = s.out()[0].flush;
        CHECK(b1 > 0.5f && f1 > 0.5f, "shading at press %g %g", b1, f1);
        run(s, 0.6f, 60, nullptr, 0);
        CHECK(s.count() == 1 && s.out()[0].blanch < 0.2f && s.out()[0].flush > 0.2f, "flush outlives blanch (%d)", s.count());
    }
    // derivation: a hand against a hip capsule, nothing in the air
    {
        ContactCapsule hip{V3(0, -0.1f, 1.0f), V3(0, 0.1f, 1.0f), 0.1f, kCrHip};
        ContactCapsule thigh{V3(0, 0.05f, 0.9f), V3(0, 0.05f, 0.5f), 0.08f, kCrThigh};
        ContactCapsule caps[2] = {hip, thigh};
        ContactTool t[3];
        t[0].p = V3(0.14f, 0.0f, 1.0f); t[0].radius = 0.035f; t[0].id = 1;    // gap 0.14-0.1-0.035 = 0.005: touching
        t[1].p = V3(0.30f, 0.0f, 1.0f); t[1].radius = 0.035f; t[1].id = 2;    // far
        t[2].p = V3(0.11f, 0.05f, 0.7f); t[2].radius = 0.035f; t[2].id = 3;   // pressing the thigh: penetrates
        ContactIn out[4];
        int n = deriveContacts(t, 3, caps, 2, 7, out, 4);
        CHECK(n == 2, "two contacts: %d", n);
        CHECK(out[0].region == kCrHip && out[0].normal.x > 0.99f && std::fabs(out[0].pos.x - 0.1f) < 1e-4f, "hip contact");
        CHECK(out[1].region == kCrThigh && out[1].depth > out[0].depth, "thigh presses deeper (%g vs %g)", out[1].depth, out[0].depth);
        CHECK(out[0].key != out[1].key, "keys differ");
        ContactIn other[4];
        int n2 = deriveContacts(t, 3, caps, 2, 8, other, 4);
        CHECK(n2 == 2 && other[0].key != out[0].key, "body keys separate");
        // a stage frame costs little
    }
    // the pick's regions: the groin by its bands (the same as simulation/InteractRegions.kt), the rest by code
    {
        CHECK(contactRegionForPick(6, 0.58f, 0.0f, true) == kCrGroin, "groin from the front");
        CHECK(contactRegionForPick(7, 0.57f, 0.02f, true) == kCrGroin, "groin on the legs' side of the pick");
        CHECK(contactRegionForPick(6, 0.58f, 0.06f, true) != kCrGroin, "beside it");
        CHECK(contactRegionForPick(6, 0.58f, 0.0f, false) == kCrButt, "behind: the butt");
        CHECK(contactRegionForPick(6, 0.5f, 0.0f, true) == kCrHip && contactRegionForPick(6, 0.7f, 0.0f, true) == kCrBelly, "hip below, belly above");
        CHECK(contactRegionForPick(4, 0.75f, 0, true) == kCrBreast && contactRegionForPick(4, 0.75f, 0, false) == kCrRibs, "chest");
        CHECK(contactRegionForPick(7, 0.4f, 0, true) == kCrThigh && contactRegionForPick(7, 0.15f, 0, true) == kCrShin, "legs");
        CHECK(contactRegionForPick(1, 0.95f, 0, true) == kCrSkull, "head");
        CHECK(contactRegionForPick(99, 0.5f, 0, true) == kCrOther, "junk");
        // every region has a softness and a depth limit in range, the soft ones softer than the stiff
        for (int r = 0; r < kCrCount; ++r) { ContactRegionInfo i = contactRegion(r); CHECK(i.softness > 0 && i.softness <= 1 && i.maxDepth > 0 && i.maxDepth < 0.05f, "region %d", r); }
        CHECK(contactRegion(kCrBreast).softness > contactRegion(kCrRibs).softness && contactRegion(kCrButt).softness > contactRegion(kCrSkull).softness, "soft over stiff");
        CHECK(contactCap(kContactOff, 0) == 0 && contactCap(kContactLow, 0) == 4 && contactCap(kContactHigh, 0) == 8 && contactCap(kContactHigh, 1) == 4 && contactCap(kContactHigh, 2) == 0, "caps");
    }
    // multi-touch: each finger is its own contact; two close fingers stay two dents; touches come before the stage's contacts in the cap
    {
        const float pad = kPadDefaultWorld;
        auto finger = [&](int k, V3 pos) {
            ContactIn c; c.key = 0x7001u + uint32_t(k); c.touch = true; c.pos = pos; c.normal = V3(0, 0, 1); c.region = kCrThigh;
            c.radius = fingerDentRadius(pad); c.depth = fingerDentDepth(pad, 1.0f); c.cap = fingerDentCap(pad); return c;
        };
        CHECK(std::fabs(fingerDentRadius(pad) - 1.5f * pad) < 1e-6f, "dent radius is 1.5 pads");
        CHECK(fingerDentDepth(pad, 1.0f) <= fingerDentCap(pad) && fingerDentDepth(pad, 0.0f) == 0 && fingerDentDepth(pad, 9.0f) == fingerDentDepth(pad, 1.0f), "finger depth bounds");
        // two fingers 2.2 cm apart (one pad's width and a bit) are two dents; on top of one another one
        for (float gapM : {0.022f, 0.012f}) {
            ContactSolver s; const float gap = gapM / kYardM;
            ContactIn held[2] = {finger(0, V3(0, 0, 0)), finger(1, V3(gap, 0, 0))};
            run(s, 0.6f, 60, held, 2);
            CHECK(s.count() == 2, "two fingers %g m apart stay two dents (%d)", gapM, s.count());
        }
        {
            ContactSolver s;
            ContactIn held[2] = {finger(0, V3(0, 0, 0)), finger(1, V3(0.1f * pad, 0, 0))};
            run(s, 0.6f, 60, held, 2);
            CHECK(s.count() == 1, "fingers nearly on one another merge (%d)", s.count());
        }
        // a finger's dent stays within the cap whatever the Softness slider
        {
            ContactSolver s; s.setSoftness(2.0f);
            ContactIn held[1] = {finger(0, V3())};
            run(s, 1.0f, 60, held, 1);
            CHECK(s.count() == 1 && s.out()[0].depth <= fingerDentCap(pad) * 2.0f + 1e-6f && s.out()[0].depth > 0, "dent %g", s.out()[0].depth);
        }
        // release of one pointer: that one springs back, the other stays pressed
        {
            ContactSolver s;
            ContactIn held[2] = {finger(0, V3(0, 0, 0)), finger(1, V3(0.1f, 0, 0))};
            run(s, 0.6f, 60, held, 2);
            const float d0 = s.depthOf(0x7001u), d1 = s.depthOf(0x7002u);
            run(s, 0.8f, 60, held + 1, 1);
            CHECK(s.depthOf(0x7001u) < 0.05f * d0 && std::fabs(s.depthOf(0x7002u) - d1) < 0.05f * d1, "pointer 0 let go (%g -> %g), pointer 1 held (%g -> %g)", d0, s.depthOf(0x7001u), d1, s.depthOf(0x7002u));
        }
        // the cap of eight is shared: four stage contacts and five fingers: the fingers are all drawn
        {
            ContactSolver s;
            std::vector<ContactIn> held;
            for (int k = 0; k < 5; ++k) held.push_back(finger(k, V3(0.1f * k, 0, 0)));
            for (int k = 0; k < 6; ++k) { ContactIn c = press(900u + k, V3(1.0f + 0.2f * k, 0, 0), 0.02f, kCrButt, 0.05f); held.push_back(c); }
            run(s, 1.5f, 60, held.data(), int(held.size()));
            int fingers = 0;
            for (int i = 0; i < s.count(); ++i) if (s.out()[i].touch) ++fingers;
            CHECK(s.count() <= kContactMax && fingers == 5, "cap %d, fingers drawn %d of 5", s.count(), fingers);
        }
    }
    if (failures == 0) std::printf("contact_test: all passed\n");
    return failures ? 1 : 0;
}
