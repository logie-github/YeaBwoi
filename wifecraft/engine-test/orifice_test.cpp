// The orifice deformer's maths without GL or game files (engine/orifice.h): a toy measured along its axis, the opening's width against
// the radius profile, symmetry, the lips' bulge, the rim hugging the shaft, the depth clamps, the hysteresis, determinism and the sleeve.
// Exit 0 when all pass.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "orifice.h"

using namespace wc;
using namespace wc::orifice;

static int g_fail = 0, g_run = 0;
#define CHECK(c, ...) do { ++g_run; if (!(c)) { ++g_fail; std::printf("FAIL %s:%d: %s  ", __FILE__, __LINE__, #c); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

// A dildo as a mesh of rings: shaft radius `r` for `shaftLen`, a rounded tip, then a flare of radius 2r for `baseLen`; along `dir`.
static std::vector<float> makeToy(float r, float shaftLen, float baseLen, const V3& dir, const V3& origin) {
    std::vector<float> p;
    const V3 a = dir.normalized();
    const V3 side1 = (std::fabs(a.z) < 0.9f ? V3(0, 0, 1) : V3(1, 0, 0)).cross(a).normalized(), side2 = a.cross(side1);
    const int nz = 80, na = 24;
    for (int i = 0; i <= nz; ++i) {
        const float s = (shaftLen + baseLen) * float(i) / nz;          // from the tip
        float rad;
        if (s < shaftLen) rad = s < r ? r * std::sqrt(std::max(0.0f, 1 - (r - s) * (r - s) / (r * r))) : r;
        else rad = 2 * r;
        for (int j = 0; j < na; ++j) {
            const float t = 6.2831853f * j / na;
            const V3 q = origin + a * (-s) + side1 * (std::cos(t) * rad) + side2 * (std::sin(t) * rad);   // the tip at the origin, the base behind
            p.push_back(q.x); p.push_back(q.y); p.push_back(q.z);
        }
    }
    return p;
}

static Frame testFrame() {
    Frame f;
    f.u = 1.25f;
    f.axis = V3(-0.17f, 0, 0.985f).normalized();
    f.centre = V3(0.016f, 0, 1.0f);
    f.lateral = (V3(0, 1, 0) - f.axis * f.axis.y).normalized();
    f.fwd = f.lateral.cross(f.axis).normalized();
    f.slitBack = 0.012f; f.slitFront = 0.045f; f.cleftDepth = 0.02f;
    f.ok = true;
    return f;
}

static Dyn settled(const Frame& f, const ToyShape& toy, float depth) {
    Dyn d;
    for (int i = 0; i < 400; ++i) stepDyn(d, f, &toy, true, depth, 0, 1.0f / 60);
    return d;
}

static ToyShape fixedToy(float r) {
    ToyShape t; t.ok = true; t.length = 0.15f; t.totalLength = 0.2f; t.axis = V3(0, 0, 1);
    for (int i = 0; i < kProfile; ++i) t.radius[i] = r;
    t.maxRadius = r;
    return t;
}

int main() {
    // ---- measureToy: any orientation, the narrower end is the tip, the flare ends the insertable length
    {
        const V3 dirs[] = {V3(0, 0, 1), V3(0.3f, 0.9f, 0.1f), V3(-1, 0.2f, 0.4f)};
        for (const V3& dir : dirs) {
            auto p = makeToy(0.02f, 0.14f, 0.05f, dir, V3(0.3f, -0.2f, 0.7f));
            ToyShape t = measureToy(p.data(), p.size() / 3, false);
            CHECK(t.ok, "measured");
            CHECK(std::fabs(t.axis.dot(dir.normalized()) - 1.0f) < 0.02f, "axis %.3f", t.axis.dot(dir.normalized()));
            CHECK(std::fabs(t.length - 0.14f) < 0.012f, "length %.4f", t.length);
            CHECK(std::fabs(t.totalLength - 0.19f) < 0.01f, "total %.4f", t.totalLength);
            CHECK(std::fabs(t.radiusAt(0.07f) - 0.02f) < 0.003f, "radius mid %.4f", t.radiusAt(0.07f));
            CHECK(t.radiusAt(0.0f) < 1e-6f && t.radiusAt(0.002f) < 0.02f, "round tip");
            const V3 tipWorld = V3(0.3f, -0.2f, 0.7f);
            CHECK((t.tip - tipWorld).length() < 0.01f, "tip %.4f", (t.tip - tipWorld).length());
        }
        auto p = makeToy(0.02f, 0.2f, 0.0f, V3(0, 1, 0), V3());   // a plain rod, both ends alike
        ToyShape t = measureToy(p.data(), p.size() / 3, true);
        CHECK(t.ok && std::fabs(t.length - 0.1f) < 0.01f, "double ended: half, %.4f", t.length);
    }
    const Frame f = testFrame();
    const float u = f.u;
    // ---- opening width against the radius profile
    {
        float prev = 0;
        for (float r : {0.008f, 0.012f, 0.018f, 0.025f}) {
            ToyShape toy = fixedToy(r / u);        // (the radius in metres: 0.008 model units = ...)
            Dyn d = settled(f, toy, 0.05f);
            const float want = toy.radiusAt(0.05f / u) * u;
            CHECK(std::fabs(d.radius - want) < want * 0.02f, "radius %.4f vs %.4f", d.radius, want);
            // the walls at the middle of the slit, either side of the midline
            const V3 pl = f.centre + f.lateral * 0.0004f, pr = f.centre - f.lateral * 0.0004f;
            const V3 ql = deformPoint(f, &toy, d, pl, nullptr), qr = deformPoint(f, &toy, d, pr, nullptr);
            const float gap = (ql - qr).dot(f.lateral);
            CHECK(gap > 2 * want * 0.95f && gap < 2 * (want + kSkinThickness * u) * 1.5f, "gap %.4f for radius %.4f", gap, want);
            CHECK(gap > prev, "wider with a larger toy");
            prev = gap;
        }
    }
    ToyShape toy = fixedToy(0.014f);
    Dyn d = settled(f, toy, 0.06f);
    const float rE = d.radius;
    // ---- symmetry about the midline plane
    {
        float worst = 0;
        for (int i = 0; i < 400; ++i) {
            const V3 rel(((i * 37) % 100 - 50) * 0.0006f, ((i * 53) % 100 - 50) * 0.0004f + 0.00013f, ((i * 71) % 100 - 50) * 0.0004f);
            const V3 p = f.centre + f.lateral * rel.y + f.fwd * rel.x + f.axis * rel.z;
            const V3 pm = f.centre - f.lateral * rel.y + f.fwd * rel.x + f.axis * rel.z;
            const V3 a = deformPoint(f, &toy, d, p, nullptr) - f.centre, b = deformPoint(f, &toy, d, pm, nullptr) - f.centre;
            worst = std::max(worst, std::fabs(a.dot(f.lateral) + b.dot(f.lateral)));
            worst = std::max(worst, std::fabs(a.dot(f.fwd) - b.dot(f.fwd)));
            worst = std::max(worst, std::fabs(a.dot(f.axis) - b.dot(f.axis)));
        }
        CHECK(worst < 1e-4f, "mirror error %.6f", worst);
    }
    // ---- the lips bulge outward (volume the opening displaced) and nothing is inside the shaft; no NaN; nothing beyond the region moves
    {
        bool nan = false, inside = false, moved = false;
        float bulgeOut = 0;
        for (int i = 0; i < 4000; ++i) {
            const V3 rel(((i * 37) % 200 - 100) * 0.0007f, ((i * 53) % 200 - 100) * 0.0006f, ((i * 71) % 200 - 100) * 0.0005f);
            const V3 p = f.centre + f.lateral * rel.y + f.fwd * rel.x + f.axis * rel.z;
            float st = 0;
            const V3 q = deformPoint(f, &toy, d, p, &st);
            if (!std::isfinite(q.x) || !std::isfinite(q.y) || !std::isfinite(q.z) || !std::isfinite(st)) nan = true;
            const V3 r2 = q - f.centre;
            const float h = r2.dot(f.axis), s = d.depth - h;
            if (regionWeight(f, p) > 0 && s >= 0 && s <= toy.length * u && (r2 - f.axis * h).length() < toy.radiusAt(s / u) * u * 0.999f) inside = true;
            if ((p - f.centre).length() > kRegionRadius * u * 1.001f && (q - p).length() > 1e-7f) moved = true;
            // the lips well outside the slit but inside the region: displaced outward in the lateral direction on their side
            const float l = rel.y;
            if (std::fabs(l) > rE * 1.2f && std::fabs(l) < 0.03f * u && std::fabs(rel.x) < 0.01f && rel.z < 0.0f) bulgeOut += (q - p).dot(f.lateral) * (l > 0 ? 1 : -1);
        }
        CHECK(!nan, "no NaN"); CHECK(!inside, "no vertex left inside the shaft"); CHECK(!moved, "nothing outside the region moves");
        CHECK(bulgeOut > 0, "the lips bulge outward: %.5f", bulgeOut);
    }
    // ---- the oval fits the cross-section at the entry plane: closed at its ends, widest at the middle
    {
        const Oval o = ovalFor(f, rE);
        CHECK(o.half >= rE && o.front >= o.half && o.back >= o.half * 0.99f - 1e-6f, "oval %.4f %.4f %.4f", o.half, o.back, o.front);
        const V3 mid = deformPoint(f, &toy, d, f.centre + f.lateral * 0.0004f, nullptr);
        const V3 nearEnd = deformPoint(f, &toy, d, f.centre + f.fwd * (o.front * 0.97f) + f.lateral * 0.0004f, nullptr);
        CHECK((mid - f.centre).dot(f.lateral) > (nearEnd - f.centre).dot(f.lateral) * 2, "widest at the middle");
    }
    // ---- depth clamps: a toy that is not in opens nothing; the radius follows the profile as it goes deeper
    {
        Dyn d0 = settled(f, toy, -0.02f);
        CHECK(d0.radius == 0, "outside: closed");
        Dyn d1 = settled(f, toy, 0.0f);
        CHECK(d1.radius == 0, "at the plane: closed");
        Dyn d2 = settled(f, toy, 0.004f);
        Dyn d3 = settled(f, toy, 0.02f);
        CHECK(d2.radius > 0 && d2.radius < d3.radius, "eased in over the first 8 mm: %.4f < %.4f", d2.radius, d3.radius);
        ToyShape cone = toy;
        cone.maxRadius = 0.034f;
        for (int i = 0; i < kProfile; ++i) cone.radius[i] = 0.004f + 0.002f * i;
        Dyn a = settled(f, cone, 0.03f), b = settled(f, cone, 0.12f);
        CHECK(b.radius > a.radius * 1.5f, "the opening follows the profile: %.4f vs %.4f", a.radius, b.radius);
        CHECK(settled(f, cone, 5.0f).radius <= cone.maxRadius * u * 1.001f, "clamped at the last sample");
    }
    // ---- hysteresis: withdrawing, the rim is dragged and the lips relax in about 300 ms; pushing in is quicker
    {
        Dyn h = settled(f, toy, 0.06f);
        const float r0 = h.radius;
        float maxDrag = 0;
        for (int i = 0; i < 6; ++i) { stepDyn(h, f, &toy, true, 0.06f - 0.012f * (i + 1), 0, 1.0f / 60); maxDrag = std::max(maxDrag, h.drag); }
        CHECK(maxDrag > 0.002f, "dragged on withdrawal: %.5f", maxDrag);
        Dyn g = h;
        for (int i = 0; i < 18; ++i) stepDyn(g, f, &toy, false, 0.0f, 0, 1.0f / 60);   // pulled out: 0.3 s
        CHECK(g.radius > 0.25f * r0 * 0.3f && g.radius < 0.6f * r0, "after 300 ms the lips are partway: %.4f of %.4f", g.radius, r0);
        for (int i = 0; i < 120; ++i) stepDyn(g, f, &toy, false, 0.0f, 0, 1.0f / 60);
        CHECK(g.radius == 0 && g.drag == 0, "closed and the drag let go");
        Dyn in; float t = 0;
        for (; t < 0.5f && in.radius < 0.9f * r0; t += 1.0f / 60) stepDyn(in, f, &toy, true, 0.06f, 0, 1.0f / 60);
        CHECK(t < 0.25f, "opens in %.3f s", t);
    }
    // ---- wetness rises with activity, falls slowly without; the tremor follows the vibration
    {
        Dyn w;
        for (int i = 0; i < 600; ++i) stepDyn(w, f, &toy, true, 0.03f + 0.02f * std::sin(i * 0.1f), 0.6f, 1.0f / 60);
        CHECK(w.wet > 0.3f && w.wet <= 1.0f && w.tremor > 0.5f, "wet %.3f tremor %.3f", w.wet, w.tremor);
        const float before = w.wet;
        for (int i = 0; i < 60; ++i) stepDyn(w, f, &toy, false, 0, 0, 1.0f / 60);
        CHECK(w.wet < before && w.wet > before * 0.9f, "wet falls slowly");
        CHECK(w.tremor < 0.2f, "tremor stops with the toy");
    }
    // ---- determinism: the same steps, the same bits
    {
        Dyn a, b;
        for (int i = 0; i < 300; ++i) { const float dep = 0.04f + 0.03f * std::sin(i * 0.05f); stepDyn(a, f, &toy, true, dep, 0.5f, 1.0f / 60); stepDyn(b, f, &toy, true, dep, 0.5f, 1.0f / 60); }
        CHECK(std::memcmp(&a, &b, sizeof a) == 0, "dyn bits");
        const V3 p = f.centre + f.lateral * 0.01f + f.fwd * 0.005f;
        const V3 q1 = deformPoint(f, &toy, a, p, nullptr), q2 = deformPoint(f, &toy, b, p, nullptr);
        CHECK(std::memcmp(&q1, &q2, sizeof q1) == 0, "deform bits");
    }
    // ---- the interior: nothing without an opening, scaled by it, a closed tube about 6 cm deep that holds the shaft
    {
        Sleeve s0; Dyn none;
        buildSleeve(f, &toy, none, s0);
        CHECK(s0.empty(), "no sleeve while closed");
        Sleeve s1;
        buildSleeve(f, &toy, d, s1);
        CHECK(!s1.empty() && s1.v.size() == size_t((s1.rings + 1) * s1.segs * 7), "sleeve built");
        float lo = 1e9f, hi = -1e9f, rmax = 0, rmaxMouth = 0;
        for (size_t i = 0; i < s1.v.size(); i += 7) {
            const V3 p(s1.v[i], s1.v[i + 1], s1.v[i + 2]);
            const float h = (p - f.centre).dot(f.axis);
            lo = std::min(lo, h); hi = std::max(hi, h);
            const float rad = ((p - f.centre) - f.axis * h).length();
            rmax = std::max(rmax, rad);
            if (s1.v[i + 6] == 0) rmaxMouth = std::max(rmaxMouth, rad);
            CHECK(std::isfinite(p.x + p.y + p.z), "finite");
        }
        CHECK(hi - lo >= kSleeveDepth * u * 0.95f, "depth %.4f", hi - lo);
        CHECK(rmaxMouth >= rE * 0.99f, "mouth %.4f holds the shaft %.4f", rmaxMouth, rE);
        // every ring wide enough for the shaft where the shaft is
        bool tight = false;
        for (int i = 0; i <= s1.rings; ++i) for (int j = 0; j < s1.segs; ++j) {
            const size_t k = size_t(i * s1.segs + j) * 7;
            const V3 p(s1.v[k], s1.v[k + 1], s1.v[k + 2]);
            const float h = (p - f.centre).dot(f.axis), s = d.depth - h;
            if (s >= 0 && s <= toy.length * u && s1.v[k + 6] < 0.75f) {
                const float rad = ((p - f.centre) - f.axis * h).length();
                if (rad < toy.radiusAt(s / u) * u * 0.99f * 0.7f) tight = true;   // (an ellipse's short side)
            }
        }
        CHECK(!tight, "the tube is not narrower than the shaft");
        Rim rim; buildRim(f, d, rim);
        CHECK(!rim.empty() && rim.idx.size() % 3 == 0, "rim");
    }
    // ---- the slit found on a body's vertices: two walls of a long slit and a short anus-like cluster
    {
        std::vector<M2Vertex> vs;
        auto add = [&](float x, float y, float z, float ny, int bone) {
            M2Vertex v{}; v.pos[0] = x; v.pos[1] = y; v.pos[2] = z; v.normal[1] = ny; v.weights[0] = 255; v.bones[0] = uint8_t(bone); vs.push_back(v);
        };
        for (int i = 0; i < 60; ++i) { const float x = 0.01f + 0.0006f * i; add(x, 0.0005f, 0.52f + 0.0002f * (i % 7), 0.95f, 1); add(x, -0.0005f, 0.52f + 0.0002f * (i % 7), -0.95f, 1); }
        for (int i = 0; i < 40; ++i) { const float x = -0.04f + 0.0003f * i; add(x, 0.0005f, 0.53f, 0.9f, 1); }
        add(0.02f, 0.0004f, 0.53f, 0.95f, 5);   // another bone's: ignored
        Frame fr;
        CHECK(findHdVulva(vs.data(), vs.size(), 1, 1.25f, 0.0f, fr), "slit found");
        CHECK(fr.ok && std::fabs(fr.centre.x - (0.01f + 0.0006f * 59 / 2)) < 0.002f, "the long cluster, not the short one: %.4f", fr.centre.x);
        CHECK(fr.slitFront > 0.012f && fr.slitBack > 0.012f && fr.axis.z > 0.9f && fr.axis.x < 0, "frame");
        CHECK(std::fabs(fr.lateral.dot(fr.axis)) < 1e-4f && std::fabs(fr.fwd.dot(fr.lateral)) < 1e-4f, "orthonormal");
        CHECK(!findHdVulva(vs.data(), 0, 1, 1.25f, 0.0f, fr), "nothing without vertices");
    }
    std::printf("orifice_test: %d checks, %d failed\n", g_run, g_fail);
    return g_fail ? 1 : 0;
}
