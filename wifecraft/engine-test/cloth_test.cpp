// The hanging cloth's maths alone (engine/cloth.h; no GL): a strip pinned along its top edge falls under gravity and
// hangs at its own length, a capsule pushes points out to its surface (and a sided one keeps a front piece in front),
// a strip hanging over a body stays outside it, the same frames give the same cloth, and a long gap or a pin put far
// away hangs it again from the pose; a limb lying across has the cloth laid over its top (a lap, a shin), a row turned
// edge-on is turned back within the twist cone, no link is left past the strain limit, two touching thighs act as one (no
// cloth sinks between them), a gathered row is spread back out, a flap lying on her side stays on her front, and the
// tabard's panel gives the flap its texture window and coordinates. With a files folder holding the human woman's model (1000764) the real flap's rig
// is checked too: two pieces, and the flap's texture rectangle on its 2048 x 1024 skin (x 744..916, y 176..380).
//   build/cloth_test [FILES_DIR]     exit 0 when all pass
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "../android/app/src/main/cpp/engine/cloth.h"

using namespace wc;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); ++failures; } } while (0)

// A strip of cols x rows points `spacing` apart, its top row along y at height `top`, laid out flat toward +x (so it has
// to fall to hang), row 0 pinned.
static ClothRig strip(int cols, int rows, float spacing, float top, std::vector<V3>& rest) {
    ClothRig rig;
    ClothPiece p;
    p.side = 1;
    p.cols = cols;
    p.rows = rows;
    rest.clear();
    for (int r = 0; r < rows; ++r)
        for (int c = 0; c < cols; ++c) {
            ClothPoint pt;
            pt.pinned = r == 0;
            pt.uv[0] = float(c) / float(cols - 1);
            pt.uv[1] = float(r) / float(rows - 1);
            p.points.push_back(pt);
            rest.push_back(V3(float(r) * spacing, (float(c) - float(cols - 1) * 0.5f) * spacing, top));
        }
    clothLinks(p, rest);
    p.first = 0;
    rig.pieces.push_back(p);
    rig.ok = true;
    return rig;
}

static ClothCapsule upright(V3 base, float height, float radius, bool sided) {
    ClothCapsule c;
    c.a0 = base + V3(0, 0, height);
    c.a1 = base;
    c.axis = V3(0, 0, -1);
    c.len = height;
    c.n = V3(1, 0, 0);
    c.m = c.axis.cross(c.n);
    for (auto& s : c.r) for (float& r : s) r = radius;
    c.reach = radius;
    c.sided = sided;
    c.torso = sided;
    return c;
}

int main(int argc, char** argv) {
    // ---- a pinned strip falls and hangs at its own length
    {
        std::vector<V3> rest;
        ClothRig rig = strip(5, 9, 0.05f, 1.0f, rest);
        CHECK(rig.pieces[0].links.size() > 0 && rig.pieces[0].tethers.size() == 5 * 8, "links %zu tethers %zu", rig.pieces[0].links.size(), rig.pieces[0].tethers.size());
        ClothSim sim;
        sim.setRig(&rig);
        sim.setFollow(false);
        std::vector<ClothCapsule> none;
        for (uint32_t t = 0; t <= 4000; t += 16) sim.step(rest, none, nullptr, 1.0f, t);
        const std::vector<V3>& x = sim.positions();
        const float length = 0.05f * 8;
        for (int c = 0; c < 5; ++c) {
            const V3& top = x[size_t(c)];
            const V3& hem = x[size_t(8 * 5 + c)];
            CHECK(std::fabs(top.z - 1.0f) < 1e-5f && std::fabs(top.x) < 1e-5f, "pin %d moved: %.4f %.4f", c, top.x, top.z);
            // straight down from its pin, at its length (within the cloth's give)
            CHECK(std::fabs(hem.x - top.x) < 0.02f, "column %d hangs off the vertical by %.3f", c, hem.x - top.x);
            float drop = top.z - hem.z;
            CHECK(drop > length * 0.97f && drop < length * (1.0f + ClothSim::kSlack) + 1e-3f, "column %d drops %.4f (length %.4f)", c, drop, length);
        }
        CHECK(sim.maxStretch(1.0f) < 0.05f, "stretch %.4f", sim.maxStretch(1.0f));
        std::printf("strip: hem %.4f below the pins (length %.4f), stretch %.4f\n", x[0].z - x[40].z, length, sim.maxStretch(1.0f));
    }

    // ---- a capsule pushes a point out to its surface (radially), a sided one to its front
    {
        ClothCapsule c = upright(V3(0, 0, 0), 1.0f, 0.1f, false);
        V3 p(0.05f, 0.0f, 0.5f), n;
        CHECK(clothCollide(c, 0, 0.01f, p, n), "a point inside wasn't pushed");
        CHECK(std::fabs(std::hypot(p.x, p.y) - 0.11f) < 1e-4f && n.x > 0.99f, "pushed to %.4f %.4f", p.x, p.y);
        V3 q(0.3f, 0.0f, 0.5f);
        CHECK(!clothCollide(c, 0, 0.01f, q, n), "a point outside was pushed");
        V3 far(0.0f, 0.0f, 1.5f);
        CHECK(!clothCollide(c, 0, 0.01f, far, n), "a point past its end was pushed");
        bool within = false;
        CHECK(std::fabs(clothCapsuleDepth(c, V3(0.04f, 0.0f, 0.5f), within) + 0.06f) < 1e-4f && within, "depth");
        // sided: a front piece's point behind the axis comes out at the front, never the back
        ClothCapsule t = upright(V3(0, 0, 0), 1.0f, 0.1f, true);
        V3 behind(-0.05f, 0.02f, 0.5f);
        CHECK(clothCollide(t, 1, 0.01f, behind, n), "sided: not pushed");
        CHECK(behind.x > 0.09f && std::fabs(behind.y - 0.02f) < 1e-5f && n.x > 0.99f, "sided: front piece went to %.4f %.4f", behind.x, behind.y);
        V3 front(0.05f, 0.02f, 0.5f);
        CHECK(clothCollide(t, -1, 0.01f, front, n), "sided back: not pushed");
        CHECK(front.x < -0.09f && n.x < -0.99f, "sided: back piece went to %.4f", front.x);
    }

    // ---- a strip hanging in front of a body stays outside it
    {
        std::vector<V3> rest;
        ClothRig rig = strip(5, 9, 0.05f, 1.0f, rest);
        // a round body just behind the strip's pins, and the pins on its front surface
        ClothCapsule body = upright(V3(-0.05f, 0, 0.2f), 1.0f, 0.1f, true);
        std::vector<ClothCapsule> bodies = {body};
        // posed: hanging straight down through the body's front half
        std::vector<V3> posed = rest;
        for (int r = 0; r < 9; ++r)
            for (int c = 0; c < 5; ++c) posed[size_t(r * 5 + c)] = V3(0.0f, rest[size_t(r * 5 + c)].y, 1.0f - 0.05f * float(r));
        ClothSim sim;
        sim.setRig(&rig);
        sim.setFollow(false);
        for (uint32_t t = 0; t <= 2000; t += 16) sim.step(posed, bodies, nullptr, 1.0f, t);
        int inside = 0;
        float worst = 1;
        for (size_t i = 5; i < sim.positions().size(); ++i) {
            bool within = false;
            float d = clothCapsuleDepth(body, sim.positions()[i], within);
            if (within && d < 0) ++inside;
            if (within) worst = std::min(worst, d);
        }
        CHECK(inside == 0, "%d free points inside the body (deepest %.4f)", inside, worst);
        std::printf("over a body: nearest %.4f outside its surface\n", worst);
    }

    // ---- the same frames give the same cloth; a long gap hangs it again from the pose
    {
        std::vector<V3> rest;
        ClothRig rig = strip(4, 6, 0.05f, 1.0f, rest);
        ClothCapsule body = upright(V3(-0.05f, 0, 0.2f), 1.0f, 0.1f, true);
        auto run = [&](std::vector<V3>& out) {
            ClothSim sim;
            sim.setRig(&rig);
            for (uint32_t t = 0; t <= 1500; t += 16) {
                std::vector<V3> posed = rest;
                float sway = 0.05f * std::sin(float(t) * 0.004f);
                for (V3& p : posed) p.y += sway;
                sim.step(posed, {body}, nullptr, 1.0f, t);
            }
            out = sim.positions();
        };
        std::vector<V3> a, b;
        run(a);
        run(b);
        CHECK(a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(V3)) == 0, "two runs differ");
        ClothSim sim;
        sim.setRig(&rig);
        sim.setFollow(false);
        sim.step(rest, {}, nullptr, 1.0f, 0);
        for (uint32_t t = 16; t <= 500; t += 16) sim.step(rest, {}, nullptr, 1.0f, t);
        // put 3 m away: the pins jump, the cloth hangs again there rather than being dragged across
        std::vector<V3> moved = rest;
        for (V3& p : moved) p.x += 3.0f;
        sim.step(moved, {}, nullptr, 1.0f, 516);
        CHECK(sim.stepsTaken() == 0 && sim.positions().back().x > 2.5f, "teleport: steps %d, hem at %.3f", sim.stepsTaken(), sim.positions().back().x);
        CHECK(sim.maxStretch(1.0f) < 0.05f, "teleport stretch %.4f", sim.maxStretch(1.0f));
    }

    // ---- a limb lying across (a raised thigh: the front piece) lays the cloth over its top, never out at its side
    {
        ClothCapsule thigh;
        thigh.a0 = V3(0, 0, 0.5f); thigh.a1 = V3(0.4f, 0, 0.5f); thigh.axis = V3(1, 0, 0); thigh.len = 0.4f;
        thigh.n = V3(0, 0, 1); thigh.m = thigh.axis.cross(thigh.n);
        for (auto& sct : thigh.r) for (float& r : sct) r = 0.07f;
        thigh.reach = 0.07f; thigh.drape = 1; thigh.up = V3(0, 0, 1);
        V3 n;
        V3 low(0.2f, 0.03f, 0.47f);   // in it, low on its side
        CHECK(clothCollide(thigh, 1, 0.01f, low, n), "a point in a raised thigh wasn't pushed");
        CHECK(std::fabs(low.y - 0.03f) < 1e-5f && low.z > 0.5f + 0.07f && n.z > 0.99f, "drape: went to %.4f %.4f (not over the top)", low.y, low.z);
        V3 beside(0.2f, 0.09f, 0.5f);   // between the thighs, past its side: free to fall there
        CHECK(!clothCollide(thigh, 1, 0.01f, beside, n), "a point beside the thigh was pushed");
        V3 back(0.2f, 0.0f, 0.48f);    // the back piece goes round it (radially)
        CHECK(clothCollide(thigh, -1, 0.01f, back, n) && back.z < 0.5f, "back piece over the thigh: %.4f", back.z);
        // a strip pinned above a lap of two thighs lies on them: nothing inside either
        ClothCapsule left = thigh, right = thigh;
        left.a0.y = left.a1.y = 0.07f; right.a0.y = right.a1.y = -0.07f;
        std::vector<ClothCapsule> lap = {left, right};
        std::vector<V3> rest;
        ClothRig rig = strip(5, 9, 0.05f, 0.6f, rest);   // laid out toward +x along the thighs, 10 cm above their axes
        for (V3& p : rest) p.x += 0.02f;
        std::vector<V3> posed = rest;
        for (V3& p : posed) p.z -= 0.1f;                 // the game's flap through the thighs: it isn't followed in
        ClothSim sim;
        sim.setRig(&rig);
        for (uint32_t t = 0; t <= 2000; t += 16) sim.step(posed, lap, nullptr, 1.0f, t);
        int inside = 0;
        for (size_t i = 5; i < sim.positions().size(); ++i)
            for (const ClothCapsule& c : lap) { bool within = false; if (clothCapsuleDepth(c, sim.positions()[i], within) < -1e-4f && within) ++inside; }
        CHECK(inside == 0, "%d points of the strip inside the lap", inside);
        CHECK(sim.maxStretch(1.0f) <= ClothSim::kMaxStrain + 0.01f, "lap stretch %.4f", sim.maxStretch(1.0f));
        std::printf("over a lap: %d inside, stretch %.4f\n", inside, sim.maxStretch(1.0f));
    }

    // ---- no link past the strain limit: a strip dragged hard against a body that holds its middle
    {
        std::vector<V3> rest;
        ClothRig rig = strip(5, 9, 0.05f, 1.0f, rest);
        ClothCapsule bar = upright(V3(0.0f, 0.0f, 0.5f), 0.4f, 0.06f, false);   // a post the strip hangs across
        ClothSim sim;
        sim.setRig(&rig);
        float worst = 0;
        for (uint32_t t = 0; t <= 3000; t += 16) {
            std::vector<V3> posed = rest;
            float swing = 0.25f * std::sin(float(t) * 0.006f);
            for (V3& p : posed) { p.y += swing; p.x -= 0.12f; }
            sim.step(posed, {bar}, nullptr, 1.0f, t);
            if (t > 500) worst = std::max(worst, sim.maxStretch(1.0f));
        }
        CHECK(worst <= ClothSim::kMaxStrain + 0.02f, "dragged: stretch %.4f", worst);
        std::printf("dragged across a post: worst stretch %.4f (limit %.2f)\n", worst, ClothSim::kMaxStrain);
    }

    // ---- a row turned edge-on is turned back within the twist cone about the hips' up axis
    {
        std::vector<V3> rest;
        ClothRig rig = strip(5, 9, 0.05f, 1.0f, rest);
        for (V3& p : rest) p.x = 0.0f;                      // hanging flat, its rows along y (the hips' side)
        for (int r = 0; r < 9; ++r) for (int c = 0; c < 5; ++c) rest[size_t(r * 5 + c)].z = 1.0f - 0.05f * float(r);
        clothLinks(rig.pieces[0], rest);
        // the hips: the thighs' tops apart along y, the spine up (no flesh: they only frame the hips)
        auto bone = [](int seg, V3 a0, V3 a1) {
            ClothCapsule c; c.seg = seg; c.a0 = a0; c.a1 = a1; c.len = (a1 - a0).length(); c.axis = (a1 - a0).normalized();
            c.n = V3(1, 0, 0); c.m = c.axis.cross(c.n); return c;
        };
        std::vector<ClothCapsule> hips = {bone(kFleshLThigh, V3(-0.5f, 0.1f, 1.2f), V3(-0.5f, 0.1f, 0.8f)),
                                          bone(kFleshRThigh, V3(-0.5f, -0.1f, 1.2f), V3(-0.5f, -0.1f, 0.8f)),
                                          bone(kFleshSpine, V3(-0.5f, 0, 1.2f), V3(-0.5f, 0, 1.6f))};
        // posed: the whole flap turned 90 degrees about z (edge-on to the front), its pins too: nothing but the cone turns
        // its rows back (the links keep any shape turned whole)
        std::vector<V3> posed = rest;
        for (size_t i = 0; i < posed.size(); ++i) { float y = posed[i].y; posed[i].y = 0; posed[i].x = y; }
        ClothSim sim;
        sim.setRig(&rig);
        sim.setGravity(false);
        sim.step(posed, hips, nullptr, 1.0f, 0);
        float worstAngle = 0;
        for (int r = 3; r < 9; ++r) {   // (the rows by the turned pins are held by them)
            V3 L = sim.positions()[size_t(r * 5 + 4)] - sim.positions()[size_t(r * 5)];
            float ang = std::atan2(std::fabs(L.x), std::fabs(L.y));
            worstAngle = std::max(worstAngle, ang);
        }
        CHECK(worstAngle <= ClothSim::kTwistCone + 0.05f, "a row stays %.1f degrees off the hips' side", worstAngle * 57.2958f);
        // (at the cone a row keeps cos 60 = half its width along the side; the rows by the turned pins less)
        CHECK(sim.minWidthRatio(1.0f) >= 0.35f, "width ratio %.3f", sim.minWidthRatio(1.0f));
        std::printf("twist: worst row %.1f degrees off the hips' side (cone %.0f), width ratio %.3f\n", worstAngle * 57.2958f,
                    ClothSim::kTwistCone * 57.2958f, sim.minWidthRatio(1.0f));
    }

    // ---- two thighs side by side as one (clothBridge): cloth laid across them never sinks into the groove where they touch
    {
        auto thighAt = [](int seg, float y) {
            ClothCapsule c; c.seg = seg;
            c.a0 = V3(0, y, 0.5f); c.a1 = V3(0.4f, y, 0.5f); c.axis = V3(1, 0, 0); c.len = 0.4f;
            c.n = V3(0, 0, 1); c.m = c.axis.cross(c.n);
            for (auto& sct : c.r) for (float& r : sct) r = 0.07f;
            c.reach = 0.07f; c.drape = 1; c.up = V3(0, 0, 1);
            return c;
        };
        ClothCapsule left = thighAt(kFleshLThigh, 0.07f), right = thighAt(kFleshRThigh, -0.07f);   // touching
        V3 n;
        V3 groove(0.2f, 0.0f, 0.55f);   // between them, below their tops (0.57): in the groove
        CHECK(clothBridge(left, right, V3(0, 0, 1), 0.01f, groove, n), "a point in the groove wasn't lifted");
        CHECK(std::fabs(groove.z - 0.58f) < 1e-3f && std::fabs(groove.y) < 1e-6f && n.z > 0.99f, "groove: lifted to %.4f", groove.z);
        V3 above(0.2f, 0.02f, 0.6f);
        CHECK(!clothBridge(left, right, V3(0, 0, 1), 0.01f, above, n), "a point above the lap was moved");
        V3 outside(0.2f, 0.12f, 0.55f);   // past the left one's axis: the capsule's own business
        CHECK(!clothBridge(left, right, V3(0, 0, 1), 0.01f, outside, n), "a point outside the pair was bridged");
        V3 past(0.45f, 0.0f, 0.55f);      // past the knees
        CHECK(!clothBridge(left, right, V3(0, 0, 1), 0.01f, past, n), "a point past the knees was bridged");
        ClothCapsule wideL = thighAt(kFleshLThigh, 0.2f), wideR = thighAt(kFleshRThigh, -0.2f);   // knees apart: a 26 cm gap
        V3 between(0.2f, 0.0f, 0.55f);
        CHECK(!clothBridge(wideL, wideR, V3(0, 0, 1), 0.01f, between, n), "thighs far apart were bridged");
        // a strip pinned above the pair, its middle column right over the groove, lies on them: no point below the
        // bridge's top between the axes, nothing inside either thigh
        std::vector<ClothCapsule> lap = {left, right};
        std::vector<V3> rest;
        ClothRig rig = strip(5, 9, 0.04f, 0.6f, rest);
        for (V3& p : rest) p.x += 0.02f;
        std::vector<V3> posed = rest;
        for (V3& p : posed) p.z -= 0.15f;   // the game's flap through the thighs
        ClothSim sim;
        sim.setRig(&rig);
        int sunk = 0, inside = 0;
        for (uint32_t t = 0; t <= 2000; t += 16) {
            sim.step(posed, lap, nullptr, 1.0f, t);
            for (size_t i = 5; i < sim.positions().size(); ++i) {
                const V3& q = sim.positions()[i];
                if (q.x > 0.01f && q.x < 0.39f && std::fabs(q.y) < 0.06f && q.z < 0.57f + 0.005f) ++sunk;
                for (const ClothCapsule& c : lap) { bool within = false; if (clothCapsuleDepth(c, q, within) < -1e-4f && within) ++inside; }
            }
        }
        CHECK(sunk == 0 && inside == 0, "the strip sank into the groove %d times, inside a thigh %d times", sunk, inside);
        std::printf("thighs as one: groove lifted to %.3f, a strip over them sank %d times\n", groove.z, sunk);
    }

    // ---- a row gathered up is spread back out: never narrower end to end than kMinWidth of its width at rest (here the
    // links alone already do it; the real flap's kneel in cloth_check is where the spread counts)
    {
        std::vector<V3> rest;
        ClothRig rig = strip(5, 9, 0.05f, 1.0f, rest);
        for (V3& p : rest) p.x = 0.0f;
        for (int r = 0; r < 9; ++r) for (int c = 0; c < 5; ++c) rest[size_t(r * 5 + c)].z = 1.0f - 0.05f * float(r);
        clothLinks(rig.pieces[0], rest);
        auto bone = [](int seg, V3 a0, V3 a1) {
            ClothCapsule c; c.seg = seg; c.a0 = a0; c.a1 = a1; c.len = (a1 - a0).length(); c.axis = (a1 - a0).normalized();
            c.n = V3(1, 0, 0); c.m = c.axis.cross(c.n); return c;
        };
        std::vector<ClothCapsule> hips = {bone(kFleshLThigh, V3(-0.5f, 0.1f, 1.2f), V3(-0.5f, 0.1f, 0.8f)),
                                          bone(kFleshRThigh, V3(-0.5f, -0.1f, 1.2f), V3(-0.5f, -0.1f, 0.8f)),
                                          bone(kFleshSpine, V3(-0.5f, 0, 1.2f), V3(-0.5f, 0, 1.6f))};
        // the free rows posed gathered to a quarter of their width (the pins as they are)
        std::vector<V3> posed = rest;
        for (size_t i = size_t(2 * 5); i < posed.size(); ++i) posed[i].y *= 0.25f;
        ClothSim sim;
        sim.setRig(&rig);
        sim.setFollow(false);
        float narrow = 1e9f;
        for (uint32_t t = 0; t <= 1000; t += 16) {
            sim.step(posed, hips, nullptr, 1.0f, t);
            if (t == 0) continue;
            for (int r = 2; r < 9; ++r) {
                float w = (sim.positions()[size_t(r * 5 + 4)] - sim.positions()[size_t(r * 5)]).length();
                narrow = std::min(narrow, w / rig.pieces[0].restWidth[size_t(r)]);
            }
        }
        CHECK(narrow >= ClothSim::kMinWidth - 0.03f, "a row stayed at %.3f of its width", narrow);
        std::printf("min width: narrowest free row %.3f of its width (at least %.2f)\n", narrow, ClothSim::kMinWidth);
    }

    // ---- lying on her side: the flap lies on her front (the cloth's weight along the hips' side turned onto her), its
    // rows kept across her hips (seen from her front at least 60% of their width), not slid edge-on down her side
    {
        auto bone = [](int seg, V3 a0, V3 a1, V3 n, float r) {
            ClothCapsule c; c.seg = seg; c.a0 = a0; c.a1 = a1; c.len = (a1 - a0).length(); c.axis = (a1 - a0).normalized();
            c.n = (n - c.axis * n.dot(c.axis)).normalized(); c.m = c.axis.cross(c.n);
            for (auto& sct : c.r) for (float& x : sct) x = r;
            c.reach = r; return c;
        };
        // lying on her left side along x, her front toward +y: the hips' side (right to left) points down, the spine along -x
        const V3 front(0, 1, 0);
        std::vector<ClothCapsule> body = {bone(kFleshPelvis, V3(0, 0, 0.2f), V3(0.1f, 0, 0.2f), front, 0.1f),
                                          bone(kFleshSpine, V3(0, 0, 0.2f), V3(-0.3f, 0, 0.2f), front, 0.1f),
                                          bone(kFleshLThigh, V3(0.08f, 0, 0.12f), V3(0.5f, 0, 0.12f), front, 0.07f),
                                          bone(kFleshRThigh, V3(0.08f, 0, 0.28f), V3(0.5f, 0, 0.28f), front, 0.07f)};
        for (ClothCapsule& c : body) c.torso = c.sided = c.seg == kFleshPelvis || c.seg == kFleshSpine;
        // the flap on her front, its top edge across her hips (along z), hanging toward her knees (+x)
        ClothRig rig;
        ClothPiece p;
        p.side = 1; p.cols = 5; p.rows = 8;
        std::vector<V3> rest;
        for (int r = 0; r < 8; ++r)
            for (int c = 0; c < 5; ++c) {
                ClothPoint pt; pt.pinned = r < kClothPinnedRows; p.points.push_back(pt);
                rest.push_back(V3(0.06f + 0.045f * float(r), 0.13f, 0.28f - 0.04f * float(c)));
            }
        clothLinks(p, rest);
        rig.pieces.push_back(p);
        rig.ok = true;
        ClothSim sim;
        sim.setRig(&rig);
        sim.setFloor(0.0f);
        for (uint32_t t = 0; t <= 3000; t += 16) sim.step(rest, body, nullptr, 1.0f, t);
        const float width = sim.minWidthRatio(1.0f);
        float lowest = 1e9f, sag = 0;
        for (size_t i = 0; i < rest.size(); ++i) { lowest = std::min(lowest, sim.positions()[i].z); sag += rest[i].z - sim.positions()[i].z; }
        sag /= float(rest.size() - size_t(kClothPinnedRows * 5));
        CHECK(width >= 0.6f, "lying: the flap's narrowest row %.3f of its width", width);
        CHECK(lowest > 0.04f, "lying: the flap slid down her side to %.3f", lowest);
        // (with the cloth's whole weight along the hips' side its free points sag 5.8 mm toward the floor; turned, 3.4)
        CHECK(sag < 0.0045f, "lying: the flap sags %.4f down her side", sag);
        std::printf("lying: narrowest row %.3f of its width, lowest point %.3f above the floor, sag %.4f\n", width, lowest, sag);
    }

    // ---- the tabard's panel: the window composed for the cloth and the grid's coordinates on it
    {
        int rect[4];
        CHECK(clothPanelRect({512, 0, 512, 256, 512, 256, 512, 128}, 2048, 1024, rect), "no panel");
        CHECK(rect[0] == 512 && rect[2] == 512 && rect[1] == 150 && rect[1] + rect[3] == 384, "panel %d %d %d %d", rect[0], rect[1], rect[2], rect[3]);
        CHECK(!clothPanelRect({}, 2048, 1024, rect) && !clothPanelRect({512, 0, 0, 256}, 2048, 1024, rect), "a panel of nothing");
        float a[2], b[2], c[2], d[2];
        clothPanelUv(1, 0.0f, 0.0f, 512, 234, a);    // the front's middle, top: the torso's front middle (the left edge)
        clothPanelUv(1, -1.0f, 1.0f, 512, 234, b);   // the front's first column, hem
        clothPanelUv(1, 1.0f, 1.0f, 512, 234, c);    // its last column: mirrored, the same place
        clothPanelUv(-1, 0.0f, 0.5f, 512, 234, d);   // the back's middle: the right edge
        CHECK(std::fabs(a[0] - 1.5f / 512) < 1e-5f && std::fabs(a[1] - 1.5f / 234) < 1e-5f, "front middle %.4f %.4f", a[0], a[1]);
        CHECK(std::fabs(b[0] - kClothPanelHalf) < 1e-5f && std::fabs(b[0] - c[0]) < 1e-6f && b[1] > 0.99f, "front edge %.4f %.4f", b[0], c[0]);
        CHECK(std::fabs(d[0] - (1 - 1.5f / 512)) < 1e-5f && std::fabs(d[1] - 0.5f) < 1e-5f, "back middle %.4f", d[0]);
        std::printf("panel: window %d %d %d %d; front middle u %.4f, edge %.4f; back middle %.4f\n", rect[0], rect[1], rect[2], rect[3], a[0], b[0], d[0]);
    }

    // ---- the real flap (with the game's files)
    if (argc > 1) {
        std::string dir = argv[1];
        FileLookup files = [&](uint32_t id) { std::string p = dir + "/" + std::to_string(id); std::ifstream f(p); return f.good() ? p : std::string(); };
        M2Model m;
        if (!files(1000764).empty() && m.load(1000764, files)) {
            std::vector<uint16_t> shown;
            for (const M2Submesh& sm : m.submeshes) if (sm.id == 0 || sm.id == 1202 || sm.id == 1301 || sm.id == 2201 || sm.id == 501) shown.push_back(sm.id);
            ClothRig rig = clothRig(m, m.vertices, m.indices, m.submeshes, shown);
            CHECK(rig.ok && rig.pieces.size() == 2, "rig ok %d pieces %zu", rig.ok, rig.pieces.size());
            if (rig.pieces.size() == 2) CHECK(rig.pieces[0].side == 1 && rig.pieces[1].side == -1, "sides %d %d", rig.pieces[0].side, rig.pieces[1].side);
            for (const ClothPiece& p : rig.pieces)
                for (size_t i = 0; i < p.points.size(); ++i)
                    CHECK(p.points[i].pinned == (int(i) / p.cols < kClothPinnedRows), "piece %+d point %zu pinned %d", p.side, i, p.points[i].pinned);
            CHECK(rig.particles() <= 256, "%zu points", rig.particles());
            int rect[4];
            CHECK(clothUvRect(m, shown, 2048, 1024, 0, rect), "no flap rectangle");
            CHECK(std::abs(rect[0] - 744) <= 1 && std::abs(rect[0] + rect[2] - 916) <= 1 && std::abs(rect[1] - 176) <= 1 && std::abs(rect[1] + rect[3] - 380) <= 1,
                  "flap rectangle %d %d %d %d", rect[0], rect[1], rect[2], rect[3]);
            std::printf("real flap: %zu pieces, %zu points, texture x %d..%d y %d..%d\n", rig.pieces.size(), rig.particles(), rect[0], rect[0] + rect[2],
                        rect[1], rect[1] + rect[3]);
            std::vector<uint16_t> noFlap;
            for (uint16_t g : shown) if (g != 1202) noFlap.push_back(g);
            CHECK(!clothRig(m, m.vertices, m.indices, m.submeshes, noFlap).ok, "a rig without a flap shown");
        } else std::printf("(the human woman's model isn't in %s: the real flap not checked)\n", dir.c_str());
    }

    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
