// The hanging cloth's maths alone (engine/cloth.h; no GL): a strip pinned along its top edge falls under gravity and
// hangs at its own length, a capsule pushes points out to its surface (and a sided one keeps a front piece in front),
// a strip hanging over a body stays outside it, the same frames give the same cloth, and a long gap or a pin put far
// away hangs it again from the pose. With a files folder holding the human woman's model (1000764) the real flap's rig
// is checked too: two pieces, and the flap's texture rectangle on its 2048 x 1024 skin (x 744..916, y 176..380).
//   build/cloth_test [FILES_DIR]     exit 0 when all pass
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
