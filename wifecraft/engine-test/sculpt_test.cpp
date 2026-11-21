// The creator's sculpt and body pick maths (engine/sculpt.h, engine/bodypick.h) on a made-up body: a torso, a bust, legs
// and arms of rings, skinned to a small skeleton, mirror-symmetric. No GL, no game files. build/sculpt_test, exit 0 when all
// pass. Checks: nothing moves at zero; every slider at both ends stays within its reach, keeps the body symmetric, turns no
// triangle over, leaves the neck and ankle rings where they are and changes what it says it does; pinned vertices stay;
// the bone regions, a triangle's region and the ray pick.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>

#include "bodypick.h"
#include "bodyrig.h"
#include "sculpt.h"

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

enum Bone { ROOT, PELVIS, SPINE1, CHEST, NECK, HEAD, LBREAST, RBREAST, LTHIGH, RTHIGH, LCALF, RCALF, LANKLE, RANKLE,
            LCLAV, RCLAV, LUPPER, RUPPER, LFORE, RFORE, LHAND, RHAND, NBONES };

struct Body {
    std::vector<V3> pivots;
    std::vector<int16_t> parents;
    std::vector<M2Vertex> verts;
    std::vector<uint32_t> indices;
    std::vector<int> mirror;            // each vertex's mirror image (y -> -y)
    std::vector<uint8_t> group;         // what made it: 0 torso, 1 bust, 2 leg, 3 arm, 4 neck, 5 ankle ring
};

static M2Vertex vert(V3 p, V3 n, std::vector<std::pair<int, float>> w) {
    M2Vertex v{};
    v.pos[0] = p.x; v.pos[1] = p.y; v.pos[2] = p.z;
    v.normal[0] = n.x; v.normal[1] = n.y; v.normal[2] = n.z;
    float total = 0; for (auto& [b, x] : w) total += x;
    int k = 0, sum = 0;
    for (auto& [b, x] : w) { if (k >= 4) break; v.bones[k] = uint8_t(b); v.weights[k] = uint8_t(std::lround(255 * x / total)); sum += v.weights[k]; ++k; }
    if (k) v.weights[0] = uint8_t(v.weights[0] + (255 - sum));
    return v;
}

// A tube of rings along a->b (radius r(t)), its weights w(t, around): `segs` round, `rings` long. Returns its first vertex.
template <class R, class W>
static size_t tube(Body& B, V3 a, V3 b, int rings, int segs, R r, W w, uint8_t kind) {
    size_t first = B.verts.size();
    V3 ax = (b - a).normalized();
    V3 u = std::fabs(ax.z) > 0.9f ? V3(1, 0, 0) : V3(0, 0, 1);
    V3 e1 = u.cross(ax).normalized(), e2 = ax.cross(e1);
    // e1, e2 chosen so that the tube's angle 0 points forward (+x) for a vertical tube
    if (std::fabs(ax.z) > 0.9f) { e1 = V3(1, 0, 0); e2 = ax.cross(e1); }
    for (int i = 0; i <= rings; ++i) {
        float t = float(i) / rings;
        V3 c = a + (b - a) * t;
        for (int j = 0; j < segs; ++j) {
            float ang = 2 * 3.14159265f * j / segs;
            V3 n = e1 * std::cos(ang) + e2 * std::sin(ang);
            B.verts.push_back(vert(c + n * r(t), n, w(t)));
            B.group.push_back(kind);
        }
    }
    for (int i = 0; i < rings; ++i)
        for (int j = 0; j < segs; ++j) {
            uint32_t p0 = uint32_t(first + i * segs + j), p1 = uint32_t(first + i * segs + (j + 1) % segs);
            uint32_t q0 = p0 + segs, q1 = p1 + segs;
            V3 A(B.verts[p0].pos[0], B.verts[p0].pos[1], B.verts[p0].pos[2]), Bq(B.verts[p1].pos[0], B.verts[p1].pos[1], B.verts[p1].pos[2]),
               C(B.verts[q0].pos[0], B.verts[q0].pos[1], B.verts[q0].pos[2]);
            V3 n(B.verts[p0].normal[0], B.verts[p0].normal[1], B.verts[p0].normal[2]);
            bool out = (Bq - A).cross(C - A).dot(n) > 0;
            if (out) { B.indices.insert(B.indices.end(), {p0, p1, q0, p1, q1, q0}); }
            else { B.indices.insert(B.indices.end(), {p0, q0, p1, p1, q0, q1}); }
        }
    return first;
}

static float lerp(float a, float b, float t) { return a + (b - a) * t; }

// The left side's part [v0, v1) of vertices and [i0, i1) of indices copied onto the right: y and the normal's y turned over,
// left bones for right ones, the triangles wound the other way.
static void mirrorPart(Body& B, size_t v0, size_t v1, size_t i0, size_t i1) {
    auto other = [](int b) {
        switch (b) {
            case LBREAST: return int(RBREAST); case LTHIGH: return int(RTHIGH); case LCALF: return int(RCALF); case LANKLE: return int(RANKLE);
            case LCLAV: return int(RCLAV); case LUPPER: return int(RUPPER); case LFORE: return int(RFORE); case LHAND: return int(RHAND);
            default: return b;
        }
    };
    size_t base = B.verts.size();
    for (size_t i = v0; i < v1; ++i) {
        M2Vertex v = B.verts[i];
        v.pos[1] = -v.pos[1]; v.normal[1] = -v.normal[1];
        for (int k = 0; k < 4; ++k) v.bones[k] = uint8_t(other(v.bones[k]));
        B.verts.push_back(v);
        B.group.push_back(B.group[i]);
    }
    for (size_t t = i0; t + 2 < i1; t += 3)
        B.indices.insert(B.indices.end(), {uint32_t(B.indices[t] - v0 + base), uint32_t(B.indices[t + 2] - v0 + base), uint32_t(B.indices[t + 1] - v0 + base)});
}

static Body makeBody() {
    Body B;
    B.pivots.resize(NBONES); B.parents.assign(NBONES, -1);
    auto bone = [&](int i, int parent, V3 p) { B.pivots[i] = p; B.parents[i] = int16_t(parent); };
    bone(ROOT, -1, V3(0, 0, 0)); bone(PELVIS, ROOT, V3(0, 0, 1.0f)); bone(SPINE1, PELVIS, V3(0, 0, 1.15f)); bone(CHEST, SPINE1, V3(0, 0, 1.3f));
    bone(NECK, CHEST, V3(0, 0, 1.52f)); bone(HEAD, NECK, V3(0, 0, 1.62f));
    bone(LBREAST, CHEST, V3(0.08f, 0.08f, 1.33f)); bone(RBREAST, CHEST, V3(0.08f, -0.08f, 1.33f));
    bone(LTHIGH, PELVIS, V3(0, 0.09f, 0.95f)); bone(RTHIGH, PELVIS, V3(0, -0.09f, 0.95f));
    bone(LCALF, LTHIGH, V3(0, 0.09f, 0.5f)); bone(RCALF, RTHIGH, V3(0, -0.09f, 0.5f));
    bone(LANKLE, LCALF, V3(0, 0.09f, 0.08f)); bone(RANKLE, RCALF, V3(0, -0.09f, 0.08f));
    bone(LCLAV, CHEST, V3(0, 0.05f, 1.45f)); bone(RCLAV, CHEST, V3(0, -0.05f, 1.45f));
    bone(LUPPER, LCLAV, V3(0, 0.2f, 1.45f)); bone(RUPPER, RCLAV, V3(0, -0.2f, 1.45f));
    bone(LFORE, LUPPER, V3(0, 0.48f, 1.45f)); bone(RFORE, RUPPER, V3(0, -0.48f, 1.45f));
    bone(LHAND, LFORE, V3(0, 0.74f, 1.45f)); bone(RHAND, RFORE, V3(0, -0.74f, 1.45f));

    // the torso, z 0.95 .. 1.5: pelvis, spine, chest, neck by height, blended between
    auto torsoW = [](float t) {
        float z = lerp(0.95f, 1.5f, t);
        std::vector<std::pair<int, float>> w;
        auto band = [&](int b, float lo, float hi) { float x = std::max(0.0f, 1 - std::fabs(z - (lo + hi) / 2) / ((hi - lo) / 2 + 0.05f)); if (x > 0) w.emplace_back(b, x); };
        band(PELVIS, 0.9f, 1.08f); band(SPINE1, 1.08f, 1.24f); band(CHEST, 1.24f, 1.44f); band(NECK, 1.44f, 1.6f);
        if (w.empty()) w.emplace_back(CHEST, 1.0f);
        return w;
    };
    tube(B, V3(0, 0, 0.95f), V3(0, 0, 1.5f), 22, 24, [](float t) { return 0.13f + 0.03f * std::cos(t * 6.28f); }, torsoW, 0);
    // the neck ring: a short tube weighted to the neck alone (where the Warcraft head meets the body)
    tube(B, V3(0, 0, 1.5f), V3(0, 0, 1.56f), 1, 24, [](float) { return 0.05f; }, [](float) { return std::vector<std::pair<int, float>>{{NECK, 1}}; }, 4);
    // the bust: a short tube out of each side of the chest, forward, weighted to its breast bone (the left made, the right mirrored)
    {
        size_t v0 = B.verts.size(), i0 = B.indices.size();
        tube(B, V3(0.08f, 0.075f, 1.33f), V3(0.16f, 0.075f, 1.32f), 6, 16, [](float t) { return 0.055f * (1 - 0.6f * t); },
             [](float t) { return std::vector<std::pair<int, float>>{{LBREAST, 0.4f + 0.6f * t}, {CHEST, 1 - (0.4f + 0.6f * t)}}; }, 1);
        mirrorPart(B, v0, B.verts.size(), i0, B.indices.size());
    }
    // the legs, z 0.95 .. 0.08, the thigh above the knee, the calf below, the ankle ring at the foot; the arms out along y
    {
        size_t v0 = B.verts.size(), i0 = B.indices.size();
        const float y = 0.09f;
        tube(B, V3(0, y, 0.95f), V3(0, y, 0.1f), 20, 16, [](float t) { return 0.08f - 0.04f * t; },
             [](float t) {
                 float z = lerp(0.95f, 0.1f, t), a = std::clamp((z - 0.42f) / 0.16f, 0.0f, 1.0f);
                 std::vector<std::pair<int, float>> w;
                 if (a > 0) w.emplace_back(LTHIGH, a);
                 if (a < 1) w.emplace_back(LCALF, 1 - a);
                 if (z > 0.88f) w.emplace_back(PELVIS, (z - 0.88f) * 6);
                 return w;
             }, 2);
        tube(B, V3(0, y, 0.1f), V3(0, y, 0.07f), 1, 16, [](float) { return 0.04f; },
             [](float) { return std::vector<std::pair<int, float>>{{LANKLE, 1}}; }, 5);
        tube(B, V3(0, 0.14f, 1.45f), V3(0, 0.72f, 1.45f), 18, 12, [](float t) { return 0.045f - 0.015f * t; },
             [](float t) {
                 float yy = lerp(0.14f, 0.72f, t), a = std::clamp((0.56f - yy) / 0.16f, 0.0f, 1.0f);
                 std::vector<std::pair<int, float>> w;
                 if (yy < 0.24f) w.emplace_back(LCLAV, (0.24f - yy) * 10);
                 if (a > 0) w.emplace_back(LUPPER, a);
                 if (a < 1) w.emplace_back(LFORE, 1 - a);
                 return w;
             }, 3);
        mirrorPart(B, v0, B.verts.size(), i0, B.indices.size());
    }
    // mirror images
    B.mirror.assign(B.verts.size(), -1);
    std::map<std::tuple<long, long, long>, int> at;
    auto keyOf = [](float x, float y, float z) { return std::make_tuple(std::lround(x * 1e4), std::lround(y * 1e4), std::lround(z * 1e4)); };
    for (size_t i = 0; i < B.verts.size(); ++i) at[keyOf(B.verts[i].pos[0], B.verts[i].pos[1], B.verts[i].pos[2])] = int(i);
    for (size_t i = 0; i < B.verts.size(); ++i) {
        auto it = at.find(keyOf(B.verts[i].pos[0], -B.verts[i].pos[1], B.verts[i].pos[2]));
        if (it != at.end()) B.mirror[i] = it->second;
    }
    return B;
}

static SculptBones namedBones() {
    SculptBones b;
    b.pelvis = PELVIS; b.waist = SPINE1; b.chest = CHEST; b.neck = NECK; b.head = HEAD;
    b.breast[0] = LBREAST; b.breast[1] = RBREAST; b.clavicle[0] = LCLAV; b.clavicle[1] = RCLAV;
    b.upperArm[0] = LUPPER; b.upperArm[1] = RUPPER; b.forearm[0] = LFORE; b.forearm[1] = RFORE; b.hand[0] = LHAND; b.hand[1] = RHAND;
    b.thigh[0] = LTHIGH; b.thigh[1] = RTHIGH; b.calf[0] = LCALF; b.calf[1] = RCALF; b.ankle[0] = LANKLE; b.ankle[1] = RANKLE;
    return b;
}

static V3 P(const M2Vertex& v) { return V3(v.pos[0], v.pos[1], v.pos[2]); }

// The largest |measure| over the vertices of a kind whose position passes `in`.
template <class F, class M>
static float most(const std::vector<M2Vertex>& vs, const Body& B, F in, M measure) {
    float m = -1e9f;
    for (size_t i = 0; i < vs.size(); ++i) if (in(i, P(vs[i]))) m = std::max(m, measure(P(vs[i])));
    return m;
}

int main() {
    Body B = makeBody();
    SculptRig rig = sculptRig(B.pivots, B.parents, namedBones());
    CHECK(rig.ok, "rig");
    CHECK(std::fabs(rig.unit - 1.0f) < 1e-6f, "unit %f", rig.unit);
    size_t mirrored = 0; for (int m : B.mirror) if (m >= 0) ++mirrored;
    CHECK(mirrored == B.verts.size(), "the made body is symmetric (%zu of %zu)", mirrored, B.verts.size());

    // nothing at zero (and the height and leg length are no moves here)
    {
        auto v = B.verts;
        float z[kSculptCount] = {};
        z[kSculptHeight] = 1; z[kSculptLegLength] = 1;
        CHECK(applySculpt(rig, v, B.indices, z, kSculptCount) == 0, "zero moves nothing");
        CHECK(std::memcmp(v.data(), B.verts.data(), v.size() * sizeof(M2Vertex)) == 0, "zero leaves the body exactly");
        CHECK(!sculptActive(z, kSculptCount), "height and leg length are not mesh moves");
    }

    const char* names[kSculptCount] = {"height", "shoulders", "arms", "bust", "bustLift", "waist", "belly", "hips", "butt", "thighs", "legLength",
                                       "neck", "chest", "bustWidth", "bustSpacing", "shoulderSlope", "upperArms", "forearms", "calves",
                                       "buttLift", "bodyFat", "muscle"};
    for (int k = 1; k < kSculptCount; ++k) {
        if (k == kSculptLegLength || k == kSculptNeck) continue;   // (the neck: the game's bodies only, below)
        for (float s : {-1.0f, 1.0f}) {
            auto v = B.verts;
            float vals[kSculptCount] = {};
            vals[k] = s;
            SculptStats st;
            size_t moved = applySculpt(rig, v, B.indices, vals, kSculptCount, {}, &st);
            CHECK(moved > 0, "%s %+.0f moves something", names[k], s);
            CHECK(st.maxMove <= kSculptMaxShare * rig.unit + 1e-5f, "%s %+.0f bounded (%f)", names[k], s, st.maxMove);
            CHECK(st.flipped == 0, "%s %+.0f turns no triangle over (%zu)", names[k], s, st.flipped);
            float asym = 0;
            for (size_t i = 0; i < v.size(); ++i) {
                int m = B.mirror[i];
                V3 di = P(v[i]) - P(B.verts[i]), dm = P(v[size_t(m)]) - P(B.verts[size_t(m)]);
                asym = std::max(asym, std::max(std::fabs(di.x - dm.x), std::max(std::fabs(di.y + dm.y), std::fabs(di.z - dm.z))));
            }
            CHECK(asym < 2e-5f, "%s %+.0f symmetric (%g)", names[k], s, asym);
            if (asym >= 2e-5f) for (size_t i = 0; i < v.size(); ++i) { int m = B.mirror[i]; V3 di = P(v[i]) - P(B.verts[i]), dm = P(v[size_t(m)]) - P(B.verts[size_t(m)]);
                if (std::fabs(di.x - dm.x) + std::fabs(di.y + dm.y) + std::fabs(di.z - dm.z) > 5e-4f) { std::printf("  v%zu g%d (%f %f %f) d(%f %f %f) m(%f %f %f)\n", i, B.group[i], B.verts[i].pos[0], B.verts[i].pos[1], B.verts[i].pos[2], di.x, di.y, di.z, dm.x, dm.y, dm.z); break; } }
            float seam = 0;
            for (size_t i = 0; i < v.size(); ++i) if (B.group[i] == 4 || B.group[i] == 5) seam = std::max(seam, (P(v[i]) - P(B.verts[i])).length());
            CHECK(seam == 0, "%s %+.0f keeps the neck and ankle rings (%g)", names[k], s, seam);
            // normals still unit length
            float bad = 0;
            for (auto& x : v) bad = std::max(bad, std::fabs(V3(x.normal[0], x.normal[1], x.normal[2]).length() - 1));
            CHECK(bad < 1e-3f, "%s normals unit (%g)", names[k], bad);
        }
    }

    // what each slider does, at +1 against -1
    auto run = [&](int k, float s) { auto v = B.verts; float vals[kSculptCount] = {}; vals[k] = s; applySculpt(rig, v, B.indices, vals, kSculptCount); return v; };
    auto kind = [&](uint8_t g) { return [&B, g](size_t i, V3) { return B.group[i] == g; }; };
    auto band = [&](uint8_t g, float lo, float hi) { return [&B, g, lo, hi](size_t i, V3 p) { return B.group[i] == g && p.z >= lo && p.z <= hi; }; };
    auto fx = [](V3 p) { return p.x; };
    auto fnx = [](V3 p) { return -p.x; };
    auto fay = [](V3 p) { return std::fabs(p.y); };
    auto fr = [](V3 p) { return std::sqrt(p.x * p.x + p.y * p.y); };
    {
        auto up = run(kSculptBust, 1), dn = run(kSculptBust, -1);
        CHECK(most(up, B, kind(1), fx) > most(B.verts, B, kind(1), fx) + 0.01f, "bust + comes forward");
        CHECK(most(dn, B, kind(1), fx) < most(B.verts, B, kind(1), fx) - 0.01f, "bust - goes back");
        auto lift = run(kSculptBustLift, 1);
        CHECK(most(lift, B, kind(1), [](V3 p) { return p.z; }) > most(B.verts, B, kind(1), [](V3 p) { return p.z; }) + 0.01f, "bust lift raises it");
        auto w1 = run(kSculptWaist, 1), w0 = run(kSculptWaist, -1);
        CHECK(most(w1, B, band(0, 1.12f, 1.18f), fr) > most(w0, B, band(0, 1.12f, 1.18f), fr) + 0.01f, "the waist widens");
        auto b1 = run(kSculptBelly, 1);
        CHECK(most(b1, B, band(0, 1.05f, 1.15f), fx) > most(B.verts, B, band(0, 1.05f, 1.15f), fx) + 0.01f, "the belly comes forward");
        auto h1 = run(kSculptHips, 1), h0 = run(kSculptHips, -1);
        CHECK(most(h1, B, band(2, 0.85f, 0.95f), fay) > most(h0, B, band(2, 0.85f, 0.95f), fay) + 0.01f, "the hips widen");
        auto u1 = run(kSculptButt, 1);
        CHECK(most(u1, B, band(2, 0.85f, 0.95f), fnx) > most(B.verts, B, band(2, 0.85f, 0.95f), fnx) + 0.005f, "the butt goes back");
        auto t1 = run(kSculptThighs, 1);
        auto thighR = [](V3 p) { return std::sqrt(p.x * p.x + (std::fabs(p.y) - 0.09f) * (std::fabs(p.y) - 0.09f)); };
        CHECK(most(t1, B, band(2, 0.6f, 0.8f), thighR) > most(B.verts, B, band(2, 0.6f, 0.8f), thighR) + 0.01f, "the thighs thicken");
        CHECK(most(t1, B, band(2, 0.1f, 0.3f), thighR) < most(B.verts, B, band(2, 0.1f, 0.3f), thighR) + 1e-4f, "the calves stay");
        auto a1 = run(kSculptArms, 1);
        auto armR = [](V3 p) { return std::sqrt(p.x * p.x + (p.z - 1.45f) * (p.z - 1.45f)); };
        CHECK(most(a1, B, kind(3), armR) > most(B.verts, B, kind(3), armR) + 0.005f, "the arms thicken");
        auto s1 = run(kSculptShoulders, 1);
        float out = 0;
        for (size_t i = 0; i < s1.size(); ++i)
            if (B.group[i] == 3 && std::fabs(B.verts[i].pos[1]) < 0.24f) out = std::max(out, std::fabs(s1[i].pos[1]) - std::fabs(B.verts[i].pos[1]));
        CHECK(out > 0.01f, "the shoulders broaden (%f)", out);
    }
    // a pinned vertex stays
    {
        auto v = B.verts;
        float vals[kSculptCount] = {}; vals[kSculptHips] = 1; vals[kSculptBust] = 1;
        SculptOptions opt;
        for (uint32_t i = 0; i < v.size(); ++i) if (B.group[i] == 1) opt.pinned.push_back(i);
        applySculpt(rig, v, B.indices, vals, kSculptCount, opt);
        float moved = 0; for (uint32_t i : opt.pinned) moved = std::max(moved, (P(v[i]) - P(B.verts[i])).length());
        CHECK(moved == 0, "pinned vertices stay (%g)", moved);
    }
    // every slider at once, at both ends: still bounded and no flips
    for (float s : {-1.0f, 1.0f}) {
        auto v = B.verts; float vals[kSculptCount];
        for (float& x : vals) x = s;
        SculptStats st; applySculpt(rig, v, B.indices, vals, kSculptCount, {}, &st);
        CHECK(st.maxMove <= kSculptMaxShare + 1e-5f && st.flipped == 0, "all at %+.0f: move %f flips %zu", s, st.maxMove, st.flipped);
    }

    // ---- the body pick
    std::vector<std::pair<int, uint8_t>> seeds = {
        {ROOT, kRegionHips}, {PELVIS, kRegionHips}, {SPINE1, kRegionWaist}, {CHEST, kRegionChest}, {NECK, kRegionNeck}, {HEAD, kRegionHead},
        {LTHIGH, kRegionLegs}, {RTHIGH, kRegionLegs}, {LANKLE, kRegionFeet}, {RANKLE, kRegionFeet},
        {LUPPER, kRegionArms}, {RUPPER, kRegionArms}, {LHAND, kRegionHands}, {RHAND, kRegionHands}};
    std::vector<uint8_t> regions = boneRegionsFrom(B.parents, seeds);
    CHECK(regions[LBREAST] == kRegionChest, "a breast takes the chest's region");
    CHECK(regions[LCALF] == kRegionLegs && regions[LFORE] == kRegionArms, "the calves are legs, the forearms arms");
    CHECK(regions[LCLAV] == kRegionChest, "the clavicles the chest's");
    CHECK(boneRegionsFrom({-1, 0, 5}, {}).size() == 3, "a bad parent is no crash");
    {
        M2Vertex a = vert(V3(), V3(0, 0, 1), {{CHEST, 0.7f}, {SPINE1, 0.3f}});
        M2Vertex b = vert(V3(), V3(0, 0, 1), {{SPINE1, 1}});
        CHECK(triangleRegion(a, a, a, regions, false) == kRegionChest, "the dominant region");
        CHECK(triangleRegion(a, b, b, regions, false) == kRegionWaist, "the dominant region, the other way");
        CHECK(triangleRegion(a, a, b, regions, false) == kRegionWaist, "summed by weight (0.7+0.7 chest against 0.3+0.3+1 waist)");
        CHECK(triangleRegion(a, a, a, regions, true) == kRegionHair, "a hair geoset's triangle");
    }
    std::vector<M2Submesh> subs;
    M2Submesh body; body.id = 0; body.indexStart = 0; body.indexCount = uint32_t(B.indices.size()); subs.push_back(body);
    auto pickAt = [&](V3 o, V3 d, const std::vector<M4>& mats = {}, std::vector<uint16_t> geo = {}) {
        return pickBody(B.verts, B.indices, subs, geo, mats, regions, o, d.normalized()).region;
    };
    CHECK(pickAt(V3(2, 0.05f, 1.33f), V3(-1, 0, 0)) == kRegionChest, "the chest from the front (%d)", pickAt(V3(2, 0.05f, 1.33f), V3(-1, 0, 0)));
    CHECK(pickAt(V3(2, 0, 1.15f), V3(-1, 0, 0)) == kRegionWaist, "the waist");
    CHECK(pickAt(V3(2, 0, 0.98f), V3(-1, 0, 0)) == kRegionHips, "the hips");
    CHECK(pickAt(V3(2, 0.09f, 0.7f), V3(-1, 0, 0)) == kRegionLegs, "a leg");
    CHECK(pickAt(V3(2, 0.4f, 1.45f), V3(-1, 0, 0)) == kRegionArms, "an arm");
    CHECK(pickAt(V3(2, 0.5f, 0.5f), V3(-1, 0, 0)) == kRegionNone, "beside her: nothing");
    CHECK(pickAt(V3(2, 0, 1.33f), V3(-1, 0, 0), {}, {5}) == kRegionNone, "a hidden geoset is not hit");
    {
        // posed: everything one unit up; the old chest height is now the hips' height minus one, the chest one higher
        std::vector<M4> up(NBONES, M4::translation(V3(0, 0, 1)));
        CHECK(pickAt(V3(2, 0.05f, 2.33f), V3(-1, 0, 0), up) == kRegionChest, "posed: the chest where it moved to");
        CHECK(pickAt(V3(2, 0.05f, 1.33f), V3(-1, 0, 0), up) != kRegionChest, "posed: not where it was");
    }
    // a hair geoset over the head: a tube round the neck ring's top, geoset 5 (hair)
    {
        Body H = B;
        size_t first = H.verts.size(); size_t i0 = H.indices.size();
        tube(H, V3(0, 0, 1.56f), V3(0, 0, 1.75f), 4, 16, [](float) { return 0.09f; }, [](float) { return std::vector<std::pair<int, float>>{{HEAD, 1}}; }, 6);
        (void)first;
        std::vector<M2Submesh> s2 = subs;
        M2Submesh hair; hair.id = 5; hair.indexStart = uint32_t(i0); hair.indexCount = uint32_t(H.indices.size() - i0); s2.push_back(hair);
        uint8_t r = pickBody(H.verts, H.indices, s2, {}, {}, regions, V3(2, 0, 1.65f), V3(-1, 0, 0)).region;
        CHECK(r == kRegionHair, "the hair (%d)", r);
    }

    // ---- Leg Length on the skeleton (bodyrig.h): a made-up skeleton, legs straight down, the feet on the floor
    {
        CHECK(std::fabs(legLengthFactor(-1) - 0.85f) < 1e-6f && std::fabs(legLengthFactor(0) - 1) < 1e-6f &&
              std::fabs(legLengthFactor(1) - 1.2f) < 1e-6f && std::fabs(legLengthFactor(0.5f) - 1.1f) < 1e-6f &&
              std::fabs(legLengthFactor(-0.5f) - 0.925f) < 1e-6f && legLengthFactor(5) == 1.2f && legLengthFactor(NAN) == 1.0f,
              "the slider's mapping");
        // 0 root, 1 pelvis, 2/3 thighs, 4/5 calves, 6/7 ankle twists (the calf's), 8/9 feet, 10/11 toes, 12 a sole helper (left)
        std::vector<M2Bone> bones(13);
        const V3 piv[13] = {{0, 0, 1.05f}, {0, 0, 1.0f}, {0, 0.1f, 0.95f}, {0, -0.1f, 0.95f}, {0.02f, 0.1f, 0.52f}, {0.02f, -0.1f, 0.52f},
                            {0, 0.1f, 0.1f}, {0, -0.1f, 0.1f}, {0, 0.1f, 0.1f}, {0, -0.1f, 0.1f}, {0.12f, 0.1f, 0.03f}, {0.12f, -0.1f, 0.03f},
                            {0.05f, 0.1f, 0.0f}};
        const int16_t par[13] = {-1, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 8};
        std::vector<V3> pivots(piv, piv + 13);
        std::vector<int16_t> parents(par, par + 13);
        for (int i = 0; i < 13; ++i) { bones[size_t(i)].pivot = piv[i]; bones[size_t(i)].parent = par[i]; }
        LegBones legs[2] = {{2, 4, 8}, {3, 5, 9}};
        CHECK(!makeLegEdit(pivots, parents, legs, 1.0f).active, "a factor of 1 edits nothing");
        LegBones broken[2] = {{2, 4, -1}, {3, 5, 9}};
        CHECK(!makeLegEdit(pivots, parents, broken, 1.2f).active, "a leg without its foot: no edit");
        for (float k : {0.85f, 1.2f}) {
            LegEdit e = makeLegEdit(pivots, parents, legs, k);
            CHECK(e.active, "k %.2f active", k);
            auto near = [](V3 a, V3 b) { return (a - b).length() < 1e-5f; };
            V3 knee = piv[2] + (piv[4] - piv[2]) * k, foot = knee + (piv[8] - piv[4]) * k;
            CHECK(near(e.pivots[4], knee) && near(e.knee[0], knee), "k %.2f: the knee down the thigh's axis", k);
            CHECK(near(e.pivots[8], foot) && near(e.ankle[0], foot), "k %.2f: the ankle down the shin's axis", k);
            CHECK(near(e.bind[2].point(piv[4]), e.bind[4].point(piv[4])), "k %.2f: the thigh and the calf agree at the knee", k);
            CHECK(near(e.bind[4].point(piv[8]), e.bind[8].point(piv[8])), "k %.2f: the calf and the foot agree at the ankle", k);
            CHECK(near(e.bind[6].point(piv[6]), e.pivots[8]), "k %.2f: the ankle twist goes with the shin", k);
            V3 toeMove = e.pivots[10] - piv[10], footMove = e.pivots[8] - piv[8];
            CHECK(near(toeMove, footMove) && near(e.pivots[12] - piv[12], footMove), "k %.2f: the toes and soles move with the foot (rigid)", k);
            CHECK(e.part[1] == kLegNone && e.part[2] == kLegThigh && e.part[4] == kLegCalf && e.part[6] == kLegCalf && e.part[10] == kLegFoot,
                  "k %.2f: each bone's part", k);
            // the thigh's midpoint, a vertex 4 cm in front of it: along the axis scaled, across it kept
            V3 mid = (piv[2] + piv[4]) * 0.5f + V3(0.04f, 0, 0);
            V3 axis = (piv[4] - piv[2]).normalized();
            V3 q = e.bind[2].point(mid) - piv[2], q0 = mid - piv[2];
            CHECK(std::fabs(q.dot(axis) - q0.dot(axis) * k) < 1e-5f && ((q - axis * q.dot(axis)) - (q0 - axis * q0.dot(axis))).length() < 1e-5f,
                  "k %.2f: a thigh vertex stretched along the bone only", k);
            // the bind pose drawn: every foot bone where it was (the soles on the floor), the hips raised by the legs' change
            std::vector<M4> mats;
            float lift = 0;
            legEditMatrices(bones, {}, e, mats, &lift);
            CHECK(std::fabs(lift - e.lift) < 1e-5f && std::fabs(lift - (k - 1) * 0.85f) < 1e-4f, "k %.2f: standing, raised by the legs' change (%f)", k, lift);
            CHECK(std::fabs(mats[12].point(piv[12]).z - piv[12].z) < 1e-5f && std::fabs(mats[10].point(piv[10]).z - piv[10].z) < 1e-5f,
                  "k %.2f: the soles stay on the floor", k);
            CHECK(near(mats[1].point(piv[1]), piv[1] + V3(0, 0, lift)), "k %.2f: the pelvis is only raised", k);
            // posed: the left thigh forward 40 degrees, the knee back 60: the lowest foot point where the model's own skeleton has it
            std::vector<BonePose> pose(13);
            pose[2].r = Quat(0, std::sin(-0.35f), 0, std::cos(-0.35f));
            pose[4].r = Quat(0, std::sin(0.52f), 0, std::cos(0.52f));
            std::vector<M4> own;
            legPosed(bones, pose, nullptr, own);
            legEditMatrices(bones, pose, e, mats);
            float lo = 1e9f, loOwn = 1e9f;
            for (int b : {8, 9, 10, 11, 12}) { lo = std::min(lo, mats[size_t(b)].point(piv[b]).z); loOwn = std::min(loOwn, own[size_t(b)].point(piv[b]).z); }
            CHECK(std::fabs(lo - loOwn) < 1e-5f, "k %.2f: posed, the planted foot stays on the ground (%f %f)", k, lo, loOwn);
            float shin = (mats[8].point(piv[8]) - mats[4].point(piv[4])).length(), shin0 = (piv[8] - piv[4]).length();
            CHECK(std::fabs(shin - shin0 * k) < 1e-4f, "k %.2f: posed, the shin is k times as long (%f)", k, shin / shin0);
            float footLen = (mats[10].point(piv[10]) - mats[8].point(piv[8])).length();
            CHECK(std::fabs(footLen - (piv[10] - piv[8]).length()) < 1e-5f, "k %.2f: posed, the foot keeps its size", k);
        }
    }

    // ---- the Eve body's proportions on the skeleton (bodyrig.h makeBodyEdit): torso, arms, hands, clavicles, legs together
    {
        // 0 root, 1 pelvis, 2/3 thighs, 4/5 calves, 6/7 feet, 8 spine, 9 chest, 10 neck, 11 head, 12/13 clavicles,
        // 14/15 upper arms, 16/17 forearms, 18/19 hands, 20 a left finger, 21 a left breast
        const V3 piv[22] = {{0, 0, 1.05f}, {0, 0, 1.0f}, {0, 0.1f, 0.95f}, {0, -0.1f, 0.95f}, {0, 0.1f, 0.52f}, {0, -0.1f, 0.52f},
                            {0, 0.1f, 0.1f}, {0, -0.1f, 0.1f}, {0, 0, 1.1f}, {0, 0, 1.3f}, {0, 0, 1.5f}, {0, 0, 1.56f},
                            {0, 0.03f, 1.45f}, {0, -0.03f, 1.45f}, {0, 0.17f, 1.44f}, {0, -0.17f, 1.44f}, {0, 0.37f, 1.22f},
                            {0, -0.37f, 1.22f}, {0, 0.56f, 1.0f}, {0, -0.56f, 1.0f}, {0, 0.62f, 0.93f}, {0.08f, 0.09f, 1.3f}};
        const int16_t par[22] = {-1, 0, 1, 1, 2, 3, 4, 5, 0, 8, 9, 10, 9, 9, 12, 13, 14, 15, 16, 17, 18, 9};
        std::vector<V3> pivots(piv, piv + 22);
        std::vector<int16_t> parents(par, par + 22);
        BodyBones bb;
        bb.legs[0] = {2, 4, 6}; bb.legs[1] = {3, 5, 7};
        bb.arms[0] = {12, 14, 16, 18}; bb.arms[1] = {13, 15, 17, 19};
        bb.root = 0; bb.pelvis = 1; bb.spine = 8; bb.neck = 10;
        BodyScale k;
        k.legs = 0.78f; k.torso = 0.88f; k.arms = 0.9f; k.hands = 0.84f; k.spread = 1.12f;
        LegEdit e = makeBodyEdit(pivots, parents, bb, k);
        auto near = [](V3 a, V3 b) { return (a - b).length() < 1e-5f; };
        CHECK(e.active, "proportions: active");
        CHECK(!makeBodyEdit(pivots, parents, bb, BodyScale()).active, "proportions: all 1 edits nothing");
        // every bone's map agrees with its parent's at its own pivot: the skin stays joined
        float worst = 0;
        for (int b = 1; b < 22; ++b) worst = std::max(worst, (e.bind[size_t(par[b])].point(piv[b]) - e.bind[size_t(b)].point(piv[b])).length());
        CHECK(worst < 1e-5f, "proportions: parent and child agree at every joint (%g)", worst);
        // the neck comes down by the torso's share, the head keeps its size
        float neckDrop = piv[10].z - e.pivots[10].z, want = (1 - k.torso) * (piv[10].z - 0.95f);
        CHECK(std::fabs(neckDrop - want) < 1e-5f, "proportions: the neck comes down with the torso (%f %f)", neckDrop, want);
        CHECK(near(e.pivots[11] - e.pivots[10], piv[11] - piv[10]), "proportions: the head moves with the neck, as it is");
        // the clavicle spread, the arm's segments, the hand's size
        CHECK(std::fabs((e.pivots[14] - e.pivots[12]).length() - (piv[14] - piv[12]).length() * k.spread) < 1e-5f, "proportions: the clavicle spread");
        CHECK(std::fabs((e.pivots[16] - e.pivots[14]).length() - (piv[16] - piv[14]).length() * k.arms) < 1e-5f &&
              std::fabs((e.pivots[18] - e.pivots[16]).length() - (piv[18] - piv[16]).length() * k.arms) < 1e-5f, "proportions: the arm's two segments");
        CHECK(std::fabs((e.pivots[20] - e.pivots[18]).length() - (piv[20] - piv[18]).length() * k.hands) < 1e-5f, "proportions: the hand scaled about the wrist");
        CHECK(e.part[21] == kPartTorso && e.part[11] == kPartNeck && e.part[20] == kPartHand && e.part[12] == kPartClavicle, "proportions: parts");
        // mirror symmetric
        float asym = 0;
        for (int b = 0; b < 22; ++b) {
            int m = b;
            if (b >= 2 && b <= 7) m = b ^ 1; else if (b >= 12 && b <= 19) m = b ^ 1;
            if (b == 20 || b == 21) continue;
            V3 p = e.pivots[size_t(b)], q = e.pivots[size_t(m)];
            asym = std::max(asym, (p - V3(q.x, -q.y, q.z)).length());
        }
        CHECK(asym < 1e-5f, "proportions: symmetric (%g)", asym);
        // standing: the soles on the floor
        std::vector<M2Bone> bones(22);
        for (int i = 0; i < 22; ++i) { bones[size_t(i)].pivot = piv[i]; bones[size_t(i)].parent = par[i]; }
        std::vector<M4> mats;
        float lift = 0;
        legEditMatrices(bones, {}, e, mats, &lift);
        CHECK(std::fabs(mats[6].point(piv[6]).z - piv[6].z) < 1e-5f, "proportions: the feet stay on the floor (lift %f)", lift);
        CHECK(lift < 0, "proportions: shorter legs bring her down (%f)", lift);
        // another rig's pivots (the Eve rig: bodyEditFrom), every factor 1: active, and with the model's own pivots it poses
        // exactly as the model's own skeleton does (the regular path is the same skeleton)
        LegEdit same = makeBodyEdit(pivots, parents, bb, BodyScale(), true);
        CHECK(same.active, "a rig's edit is active at factor 1");
        std::vector<BonePose> pose(22);
        pose[14].r = Quat(0, std::sin(0.4f), 0, std::cos(0.4f)).normalized();
        pose[4].r = Quat(std::sin(0.3f), 0, 0, std::cos(0.3f)).normalized();
        std::vector<M4> a1, a2;
        legEditMatrices(bones, pose, same, a1);
        legPosed(bones, pose, nullptr, a2);
        float diff = 0;
        for (size_t i = 0; i < a1.size(); ++i) for (int k = 0; k < 16; ++k) diff = std::max(diff, std::fabs(a1[i].m[k] - a2[i].m[k]));
        CHECK(diff < 1e-6f, "the model's own pivots as a rig: the model's own pose (%g)", diff);
        // a rig whose arm pivot is elsewhere: the arm still turns as the model's (same direction), about its own joint
        std::vector<V3> moved = pivots;
        moved[14] = moved[14] + V3(0, 0.03f, -0.02f); moved[16] = moved[16] + V3(0, 0.03f, -0.02f); moved[18] = moved[18] + V3(0, 0.03f, -0.02f); moved[20] = moved[20] + V3(0, 0.03f, -0.02f);
        LegEdit other = makeBodyEdit(moved, parents, bb, BodyScale(), true);
        legEditMatrices(bones, pose, other, a1);
        V3 dOwn = a2[16].point(piv[16]) - a2[14].point(piv[14]), dRig = a1[16].point(other.pivots[16]) - a1[14].point(other.pivots[14]);
        CHECK(std::fabs(dOwn.normalized().dot(dRig.normalized()) - 1) < 1e-5f, "a rig's upper arm points as the model's");
    }

    if (failures) { std::printf("%d failed\n", failures); return 1; }
    std::printf("sculpt_test: all passed (%zu vertices, %zu triangles)\n", B.verts.size(), B.indices.size() / 3);
    return 0;
}
