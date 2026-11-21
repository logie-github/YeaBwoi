// Body parts without a GL context (engine/bodypart.h): the part file read back (a file made here as bodyparts/PartFile
// writes it, its damaged forms, and real exported parts when given), the lower body's measurements on a made-up mesh,
// the attach matrix, and the placement on the game's men when their files are given.
//   build/bodypart_test [PARTS_DIR [FILES_DIR]]   exit 0 when all pass
#include <cmath>
#include <cstdio>
#include <algorithm>
#include <cstring>
#include <dirent.h>
#include <string>
#include <sys/stat.h>
#include <vector>

#include "bodypart.h"
#include "bytes.h"

using namespace wc;

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); ++failures; } } while (0)
static bool near(float a, float b, float e = 1e-4f) { return std::fabs(a - b) <= e; }

struct Writer {
    std::vector<uint8_t> b;
    void u32(uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back(uint8_t(v >> (8 * i))); }
    void f32(float f) { uint32_t v; std::memcpy(&v, &f, 4); u32(v); }
    void str(const std::string& s) { b.push_back(uint8_t(s.size())); b.push_back(uint8_t(s.size() >> 8)); b.insert(b.end(), s.begin(), s.end()); }
};

// A square of two triangles, every section present, as PartFile.write lays it out.
static std::vector<uint8_t> sample(uint32_t version = 1, uint32_t badIndex = 0) {
    Writer w;
    w.u32(kPartMagic); w.u32(version); w.u32(7); w.u32(4); w.u32(6); w.u32(2);
    w.f32(0.0f); w.f32(0.93f); w.f32(0.07f);   // anchor (Sims: y up, z forward)
    w.f32(1.55f);
    w.f32(0); w.f32(0); w.f32(0);
    w.u32(16); w.u32(8);
    w.str("Pelvis"); w.str("ABC"); w.str("ON"); w.str("Medium Uncut High"); w.str("Pelvis"); w.str("L_Thigh");
    float pos[12] = {0, -0.01f, 0, 0, 0.01f, 0, 0, 0.01f, -0.02f, 0, -0.01f, -0.02f};
    for (float f : pos) w.f32(f);
    for (int i = 0; i < 4; ++i) { w.f32(1); w.f32(0); w.f32(0); }
    float uv[8] = {0, 0, 1, 0, 1, 1, 0, 1};
    for (float f : uv) w.f32(f);
    for (int i = 0; i < 4; ++i) { w.b.push_back(0); w.b.push_back(1); w.b.push_back(0); w.b.push_back(0); }
    for (int i = 0; i < 4; ++i) { w.b.push_back(250); w.b.push_back(5); w.b.push_back(0); w.b.push_back(0); }
    uint32_t idx[6] = {0, 1, 2, 0, 2, badIndex ? badIndex : 3};
    for (uint32_t i : idx) w.u32(i);
    return w.b;
}

static void parser() {
    std::vector<uint8_t> f = sample();
    PartData p;
    std::string err;
    CHECK(parsePart(f.data(), f.size(), p, err));
    CHECK(p.vertexCount() == 4 && p.indices.size() == 6 && p.bones.size() == 2);
    CHECK(p.state == "ON" && p.slot() == 1 && p.id == "ABC" && p.variant == "Medium Uncut High" && p.attachBone == "Pelvis");
    CHECK(p.bones[1] == "L_Thigh" && p.textureWidth == 16 && p.textureHeight == 8);
    CHECK(near(p.anchorSims.y, 0.93f) && near(p.defaultScale, 1.55f));
    CHECK(p.normals.size() == 12 && p.uvs.size() == 8 && p.boneIndices.size() == 16 && p.boneWeights[0] == 250);
    // anchor + position, axes as PartMesh.AttachSpec.default (Sims z -> x, x -> y, y -> z)
    V3 q = partSimsPoint(p, 2);
    CHECK(near(q.x, 0.07f) && near(q.y, 0.01f) && near(q.z, 0.91f));
    // damaged forms
    PartData bad;
    CHECK(!parsePart(f.data(), 40, bad, err));
    std::vector<uint8_t> v3 = sample(3);
    CHECK(!parsePart(v3.data(), v3.size(), bad, err) && err.find("version 3") != std::string::npos);
    // written back by writePart (version 1: not in a model's frame), byte for byte
    CHECK(writePart(p) == f);
    std::vector<uint8_t> oob = sample(1, 9);
    CHECK(!parsePart(oob.data(), oob.size(), bad, err) && err.find("index 9") != std::string::npos);
    std::vector<uint8_t> cut(f.begin(), f.end() - 8);
    CHECK(!parsePart(cut.data(), cut.size(), bad, err));
    std::vector<uint8_t> magic = f; magic[0] = 'X';
    CHECK(!parsePart(magic.data(), magic.size(), bad, err));
    std::vector<uint8_t> bone = f;
    // the first bone index byte (after the header, names, positions, normals, UVs) named past the bone count
    size_t at = f.size() - 6 * 4 - 16 - 16;
    bone[at] = 7;
    CHECK(!parsePart(bone.data(), bone.size(), bad, err) && err.find("bone 7") != std::string::npos);
}

// A made-up lower body: two legs (columns of vertices at y +-0.2) joined on the midline above z 0.8, the front at x 0.15.
static M2Model body() {
    M2Model m;
    auto add = [&](float x, float y, float z) { M2Vertex v{}; v.pos[0] = x; v.pos[1] = y; v.pos[2] = z; v.uv[0] = x + 0.5f; v.uv[1] = z; m.vertices.push_back(v); };
    for (int k = 2; k <= 26; ++k) {
        float z = float(k) * 0.05f;
        add(0.10f, 0.2f, z); add(0.10f, -0.2f, z);
        if (k >= 16) { add(0.15f, 0.0f, z); add(-0.15f, 0.0f, z); }
    }
    add(-0.30f, 0.0f, 0.4f);   // a tail behind, low on the midline
    M2Submesh body;
    body.id = 1301;
    for (uint32_t i = 0; i < m.vertices.size(); ++i) m.indices.push_back(i);
    body.indexStart = 0; body.indexCount = uint32_t(m.indices.size());
    m.submeshes.push_back(body);
    M2Submesh robe = body; robe.id = 1302;   // another lower-body geoset over the same vertices, not shown below
    m.submeshes.push_back(robe);
    return m;
}

static void measures() {
    M2Model m = body();
    std::vector<uint16_t> shown{1301};
    CHECK(near(bodyFrontX(m, shown, 1.0f, 0.03f, 0.03f, -1), 0.15f));
    CHECK(near(bodyFrontX(m, shown, 0.5f, 0.03f, 0.03f, -1), -1));        // only the legs there: nothing on the midline
    CHECK(near(bodyCrotchZ(m, shown, 0.03f, 0.25f, 1.2f, -0.2f), 0.8f, 1e-3f));
    CHECK(near(bodyCrotchZ(m, shown, 0.03f, 0.25f, 1.2f, -1.0f), 0.4f, 1e-3f));   // the tail counts when not left out
    CHECK(bodyCrotchZ(m, shown, 0.03f, 1.25f, 1.2f, -1) < 0);
    std::vector<float> uv = bodySkinUvs(m, shown, V3(0.15f, 0, 1.0f), 0.06f, 100);
    CHECK(uv.size() == 6);   // z 0.95, 1.0, 1.05 on the front midline
    std::vector<float> few = bodySkinUvs(m, shown, V3(0, 0, 1.0f), 0.5f, 4);
    CHECK(!few.empty() && few.size() <= 8);
}

static void matrix() {
    PartPlacement pl;
    pl.ok = true; pl.root = V3(0.2f, 0, 1.0f); pl.scale = 1.2f;
    M4 m = partMatrix(M4(), pl);
    V3 r = m.point(V3(kPartRootX, 0, kPartRootZ));
    CHECK(near(r.x, 0.2f) && near(r.y, 0) && near(r.z, 1.0f));
    V3 d = m.point(V3(kPartRootX + 0.1f, 0, kPartRootZ - 0.05f));
    CHECK(near(d.x, 0.2f + 0.12f) && near(d.z, 1.0f - 0.06f));
    // the pelvis turned a quarter about z around its pivot (0, 0, 1): the root at (0.2, 0, 1) goes to (0, 0.2, 1)
    M4 bone = M4::translation(V3(0, 0, 1)) * M4::rotationZ(1.5707963f) * M4::translation(V3(0, 0, -1));
    V3 t = partMatrix(bone, pl).point(V3(kPartRootX, 0, kPartRootZ));
    CHECK(near(t.x, 0, 1e-4f) && near(t.y, 0.2f) && near(t.z, 1.0f));
}

static int realParts(const std::string& dir) {
    DIR* d = opendir(dir.c_str());
    if (!d) return 0;
    int n = 0;
    while (dirent* e = readdir(d)) {
        std::string name = e->d_name;
        if (name.size() < 6 || name.substr(name.size() - 5) != ".wcbp") continue;
        Bytes b;
        PartData p;
        std::string err;
        bool ok = readFile(dir + "/" + name, b) && parsePart(b.data(), b.size(), p, err);
        if (!ok) std::fprintf(stderr, "FAIL %s: %s\n", name.c_str(), err.c_str());
        CHECK(ok);
        CHECK(p.attachBone == "Pelvis" && (p.state == "DEFAULT" || p.state == "ON") && p.vertexCount() > 100);
        // every part of the set in the Sims body's frame: between the thighs, from 0.8 to 1 m up
        float zmin = 9, zmax = -9;
        for (size_t v = 0; v < p.vertexCount(); ++v) { V3 q = partSimsPoint(p, v); zmin = std::fmin(zmin, q.z); zmax = std::fmax(zmax, q.z); CHECK(std::fabs(q.y) < 0.05f); }
        CHECK(zmin > 0.8f && zmax < 1.0f);
        ++n;
    }
    closedir(d);
    return n;
}

// Model units per metre for a man of the model at human scale (anim/HumanScale: 1.8518 yards tall; dwarves, gnomes and
// goblins keep their own size: a yard a unit).
static float unitsPerMetre(const M2Model& m, bool own) {
    float top = 0;
    for (const M2Vertex& v : m.vertices) top = std::fmax(top, v.pos[2]);
    float yardsPerUnit = own || top <= 0 ? 1.0f : 1.8518f / top;
    return 1.0f / (yardsPerUnit * 0.9144f);
}

// The closest distance from p to the triangle (a, b, c).
static float triDistance(V3 p, V3 a, V3 b, V3 c) {
    V3 ab = b - a, ac = c - a, ap = p - a;
    float d1 = ab.dot(ap), d2 = ac.dot(ap);
    if (d1 <= 0 && d2 <= 0) return (p - a).length();
    V3 bp = p - b; float d3 = ab.dot(bp), d4 = ac.dot(bp);
    if (d3 >= 0 && d4 <= d3) return (p - b).length();
    float vc = d1 * d4 - d3 * d2;
    if (vc <= 0 && d1 >= 0 && d3 <= 0) return (p - (a + ab * (d1 / (d1 - d3)))).length();
    V3 cp = p - c; float d5 = ab.dot(cp), d6 = ac.dot(cp);
    if (d6 >= 0 && d5 <= d6) return (p - c).length();
    float vb = d5 * d2 - d1 * d6;
    if (vb <= 0 && d2 >= 0 && d6 <= 0) return (p - (a + ac * (d2 / (d2 - d6)))).length();
    float va = d3 * d6 - d5 * d4;
    if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) return (p - (b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6))))).length();
    float den = 1.0f / (va + vb + vc);
    return (p - (a + ab * (vb * den) + ac * (vc * den))).length();
}

// The playable races' men (their default models): the parts' root on each body's own front between where the legs part and
// the hip joints, centred, within 1 cm of the body's surface; and, with the real parts (`parts`: their DEFAULT and ON
// meshes), no part vertex more than 1 cm inside a thigh (a capsule hip -> knee of the thigh's own girth) standing, walking,
// sitting on the ground, kneeling and lying, flaccid (with its hang) and erect.
static void men(const std::string& dir, const std::string& parts) {
    FileLookup lk = [&](uint32_t id) { std::string p = dir + "/" + std::to_string(id); struct stat st; return stat(p.c_str(), &st) == 0 ? p : std::string(); };
    struct Race { const char* name; uint32_t model; bool own; bool poses = true; };
    const Race races[] = {{"human", 1011653u, false}, {"orc", 917116u, false}, {"dwarf", 878772u, true}, {"night elf", 974343u, false},
                          {"undead", 959310u, false}, {"tauren", 968705u, false, false}, {"gnome", 900914u, true}, {"troll", 1022938u, false},
                          {"goblin", 119376u, true}, {"blood elf", 1100087u, false}, {"draenei", 1005887u, false}, {"worgen", 307454u, false}};
    PartData flaccidPart, erectPart;
    bool haveParts = false;
    if (!parts.empty()) {
        Bytes a, b; std::string err;
        haveParts = readFile(parts + "/E853F196527D7CF4.wcbp", a) && parsePart(a.data(), a.size(), flaccidPart, err) &&
                    readFile(parts + "/BC041B5233235A43.wcbp", b) && parsePart(b.data(), b.size(), erectPart, err);
        if (!haveParts) std::fprintf(stderr, "skip the poses: the user's set's Medium Uncut High parts aren't in %s\n", parts.c_str());
    }
    PartShaft sf, se;
    if (haveParts) { sf = measureShaft(flaccidPart, &erectPart); se = measureShaft(erectPart, &flaccidPart); CHECK(sf.ok && se.ok); }
    for (const Race& r : races) {
        M2Model m;
        if (lk(r.model).empty() || !m.load(r.model, lk)) { std::fprintf(stderr, "skip %s (model %u not here)\n", r.name, r.model); continue; }
        std::vector<uint16_t> geos{0, 1301};
        PartPlacement pl = placeParts(m, geos, PartFit());
        CHECK(pl.ok);
        if (!pl.ok) continue;
        float upm = unitsPerMetre(m, r.own), cm = upm / 100.0f;
        int lt = m.findWowBone("L_Thigh"), rt = m.findWowBone("R_Thigh"), lc = m.findWowBone("L_Calf"), rc = m.findWowBone("R_Calf");
        float hip = 0.5f * (m.bones[size_t(lt)].pivot.z + m.bones[size_t(rt)].pivot.z);
        // the root under the shown body's surface: its nearest triangle of the body or the lower body's group, and how many of
        // them a ray from it straight forward crosses (odd: it is inside the body)
        float nearest = 1e9f;
        int crossings = 0;
        for (const M2Submesh& sm : m.submeshes) {
            if (sm.id != 0 && sm.id / 100 != 13) continue;
            for (uint32_t i = sm.indexStart; i + 2 < sm.indexStart + sm.indexCount && i + 2 < m.indices.size(); i += 3) {
                const float* a = m.vertices[m.indices[i]].pos; const float* b = m.vertices[m.indices[i + 1]].pos; const float* c = m.vertices[m.indices[i + 2]].pos;
                nearest = std::fmin(nearest, triDistance(pl.root, V3(a[0], a[1], a[2]), V3(b[0], b[1], b[2]), V3(c[0], c[1], c[2])));
                // the ray (root, +x) against the triangle, in the y-z plane
                float ya = a[1] - pl.root.y, za = a[2] - pl.root.z, yb = b[1] - pl.root.y, zb = b[2] - pl.root.z, yc = c[1] - pl.root.y, zc = c[2] - pl.root.z;
                float d0 = yb * zc - zb * yc, d1 = yc * za - zc * ya, d2 = ya * zb - za * yb;
                if ((d0 > 0 && d1 > 0 && d2 > 0) || (d0 < 0 && d1 < 0 && d2 < 0)) {
                    float sum = d0 + d1 + d2, x = (d0 * a[0] + d1 * b[0] + d2 * c[0]) / sum;
                    if (x > pl.root.x) ++crossings;
                }
            }
        }
        std::printf("%-9s model %u: crotch %.3f hip %.3f pelvis x %.3f root (%.3f, %.3f, %.3f) %.2f cm from the nearest skin, %.2f cm under the front (%s), scale %.3f, squeeze %.2f\n",
                    r.name, r.model, pl.crotch, hip, pl.pivot.x, pl.root.x, pl.root.y, pl.root.z, nearest / cm, (pl.front - pl.root.x) / cm,
                    crossings % 2 ? "inside" : "OUTSIDE", pl.scale, pl.squeeze);
        // centred on the midline, and inside the body: under its front surface by kPartSink (1 cm at least), 3 cm at most
        CHECK(std::fabs(pl.root.y) < 0.5f * cm);
        CHECK(pl.front - pl.root.x >= 1.0f * cm && pl.front - pl.root.x < 3.0f * cm);
        CHECK(pl.root.z > pl.crotch && pl.root.z < hip);
        CHECK(pl.root.x > pl.pivot.x);
        if (!haveParts || lc < 0 || rc < 0) continue;
        // each thigh's inner girth: the mean distance from its axis of the inner-side vertices it carries most of (bind)
        float girth[2] = {0, 0};
        for (int side = 0; side < 2; ++side) {
            int t = side ? rt : lt, c = side ? rc : lc;
            V3 h = m.bones[size_t(t)].pivot, k = m.bones[size_t(c)].pivot, ax = (k - h).normalized();
            float sum = 0; int n = 0;
            for (const M2Vertex& v : m.vertices) {
                int best = -1, bw = 0;
                for (int q = 0; q < 4; ++q) if (v.weights[q] > bw) { bw = v.weights[q]; best = v.bones[q]; }
                if (best != t) continue;
                V3 p(v.pos[0], v.pos[1], v.pos[2]);
                float along = (p - h).dot(ax);
                if (along < 0.15f * (k - h).length() || along > 0.6f * (k - h).length()) continue;
                V3 out = p - h - ax * along;
                // the thigh's inner side only (toward the other thigh): the side a part between them meets
                if (out.normalized().y * (side ? 1.0f : -1.0f) < 0.7f) continue;
                sum += out.length(); ++n;
            }
            girth[side] = n ? 0.85f * sum / float(n) : 0.0f;   // (a little inside the mean: the thigh's soft surface)
        }
        struct Pose { const char* name; uint16_t anim; };
        for (Pose ps : {Pose{"stand", 0}, Pose{"walk", 4}, Pose{"sit", 97}, Pose{"kneel", 115}, Pose{"lie", 100}}) {
            int seq = m.findSequence(ps.anim);
            if (seq < 0) { std::printf("  %s: no sequence %u\n", ps.name, ps.anim); continue; }
            m.ensureSequence(seq, lk);
            if (!m.sequenceReady(seq)) { std::printf("  %s: sequence %u not loaded\n", ps.name, ps.anim); continue; }
            std::vector<BonePose> pose; std::vector<M4> mats;
            uint32_t at = std::min<uint32_t>(600, m.sequences[size_t(seq)].duration / 2);
            m.pose(seq, at, at, pose, true);
            m.bonesToMatrices(pose, mats);
            float hang = std::clamp(thighPitch(m, mats, pl.pelvis), -0.35f, 1.75f) * kFlaccidThighShare;
            float worst = 0;
            bool worstShaft = false, worstErect = false;
            for (int erect = 0; erect < 2; ++erect) {
                const PartData& d = erect ? erectPart : flaccidPart;
                PartBend b = levelBend(erect ? 1.0f : 0.0f, erect != 0, sf, se, hang);
                b.squeeze = pl.squeeze;
                std::vector<M2Vertex> posed;
                posePart(d, erect ? se : sf, b, posed);
                M4 pm = partMatrix(mats[size_t(pl.pelvis)], pl);
                for (int side = 0; side < 2; ++side) {
                    int t = side ? rt : lt, c = side ? rc : lc;
                    V3 h = mats[size_t(t)].point(m.bones[size_t(t)].pivot), k = mats[size_t(c)].point(m.bones[size_t(c)].pivot);
                    V3 ax = (k - h).normalized(); float len = (k - h).length();
                    const PartShaft& sh = erect ? se : sf;
                    for (size_t vi = 0; vi < posed.size(); ++vi) {
                        const M2Vertex& v = posed[vi];
                        V3 p = pm.point(V3(v.pos[0], v.pos[1], v.pos[2]));
                        float along = (p - h).dot(ax);
                        if (along < 0.1f * len || along > len) continue;   // (the groin itself, where thigh and part meet, aside)
                        float in = girth[side] - (p - h - ax * along).length();
                        if (in > worst) { worst = in; worstShaft = vi < sh.weights.size() && sh.weights[vi] > 0.5f; worstErect = erect != 0; }
                    }
                }
            }
            std::printf("  %-5s: deepest into a thigh %.2f cm (hang %.0f deg%s)\n", ps.name, worst / cm, hang * 57.2958f,
                        worst > 0 ? (worstShaft ? (worstErect ? ", the erect shaft" : ", the flaccid shaft") : ", the base or scrotum") : "");
            // (a tauren's hooved legs, and the erect shaft kneeling, where the thigh's capsule is a rough stand-in for a raised
            // thigh: measured, not held to it; README)
            if (r.poses && !(worstErect && std::string(ps.name) == "kneel")) CHECK(worst < 1.0f * cm);
        }
    }
}

// The shaft, the bend, the level's swap and the insertion maths (no game files).
static void shaftMaths() {
    // a shaft along +x from the root: the level bend turns it about y; a quarter turn up puts the tip straight up
    PartShaft s;
    s.ok = true; s.root = V3(kPartRootX, 0, kPartRootZ); s.axis = V3(1, 0, 0); s.length = 0.15f;
    PartShaft hang = s; hang.axis = V3(0, 0, -1); hang.length = 0.1f;
    V3 a0 = levelAxis(0.0f, hang, s, 0.0f), a1 = levelAxis(1.0f, hang, s, 0.0f);
    CHECK(near(a0.z, -1, 1e-4f) && a1.x > 0.8f && a1.z > 0.4f);
    // erect: forward and up, kErectPitchMin above the pelvis' forward however level the ON mesh's own shaft is
    PartBend b1 = levelBend(1.0f, true, hang, s, 0.0f);
    V3 up1 = quatRotate(b1.turn, s.axis);
    CHECK(near(std::atan2(up1.z, up1.x), kErectPitchMin, 1e-3f) && near(b1.stretch, 1, 1e-4f));
    CHECK(near(std::atan2(a1.z, a1.x), kErectPitchMin, 1e-3f));
    PartBend b0 = levelBend(1.0f, false, hang, s, 0.0f);   // the flaccid mesh drawn at full level: on the erect line, 1.5 long
    CHECK((quatRotate(b0.turn, hang.axis) - up1).length() < 1e-3f && near(b0.stretch, 1.5f, 1e-3f));
    // the swap: hysteresis about kPartSwapUp / kPartSwapDown; both meshes at the same pitch at any level
    CHECK(!levelSlotOn(0.5f, false) && levelSlotOn(0.5f, true) && levelSlotOn(0.56f, false) && !levelSlotOn(0.44f, true));
    for (float lv : {0.3f, 0.5f, 0.7f}) {
        V3 x0 = quatRotate(levelBend(lv, false, hang, s, 0).turn, hang.axis), x1 = quatRotate(levelBend(lv, true, hang, s, 0).turn, s.axis);
        CHECK((x0 - x1).length() < 1e-3f);
    }
    // the hang (thighs forward) lifts the flaccid shaft, not the erect one
    CHECK(levelAxis(0.0f, hang, s, 1.2f).x > 0.9f && (levelAxis(1.0f, hang, s, 1.2f) - a1).length() < 1e-4f);
    // the HD body's own genitals left out: a triangle touching a masked vertex goes
    std::vector<uint32_t> tri{0, 1, 2, 2, 3, 4, 5, 6, 7};
    CHECK(maskTriangles(tri, {3}) == (std::vector<uint32_t>{0, 1, 2, 5, 6, 7}) && maskTriangles(tri, {}) == tri);
    // insertion depth: the length past the entry plus the rest, 0..length
    CHECK(near(insertionDepth(0.15f, 0.20f, 0.0f), 0.0f) && near(insertionDepth(0.15f, 0.10f, 0.0f), 0.05f));
    CHECK(near(insertionDepth(0.15f, 0.10f, 0.03f), 0.08f) && near(insertionDepth(0.15f, 0.0f, 0.05f), 0.15f));
    CHECK(near(insertionDepth(0.15f, -0.05f, 0.0f), 0.15f) && near(insertionDepth(0, 0.1f, 0.1f), 0.0f));
    // the pelvis moving in a thrust: the depth follows the distance one for one
    float prev = -1;
    for (float dist = 0.2f; dist >= 0.05f; dist -= 0.01f) { float dd = insertionDepth(0.15f, dist, 0.0f); CHECK(dd >= prev - 1e-6f); prev = dd; }
    // the aim's rate: a 90 degree turn takes three steps of 30
    Quat target = quatAxisAngle(V3(0, 1, 0), 1.5707963f), cur;
    int steps = 0;
    while (quatAngle(quatMul(Quat(-cur.x, -cur.y, -cur.z, cur.w), target)) > 1e-3f && steps < 10) { cur = stepAim(cur, target, 0.5235988f); ++steps; }
    CHECK(steps == 3);
    // quaternions: from-to and back
    Quat q = quatFromTo(V3(1, 0, 0), V3(0, 0, 1));
    CHECK((quatRotate(q, V3(1, 0, 0)) - V3(0, 0, 1)).length() < 1e-4f);
    M4 m = M4::translation(V3(1, 2, 3)) * M4::rotationZ(0.7f) * M4::scaling(V3(1.5f, 1.5f, 1.5f));
    CHECK((affineInverse(m).point(m.point(V3(0.3f, -0.2f, 0.9f))) - V3(0.3f, -0.2f, 0.9f)).length() < 1e-4f);
}

// CIE76 colour difference of two sRGB colours (0xRRGGBB).
static float deltaE(uint32_t a, uint32_t b) {
    auto lab = [](uint32_t c, float out[3]) {
        float rgb[3];
        for (int i = 0; i < 3; ++i) { float v = float((c >> (16 - 8 * i)) & 255) / 255.0f; rgb[i] = v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f); }
        float x = (0.4124f * rgb[0] + 0.3576f * rgb[1] + 0.1805f * rgb[2]) / 0.95047f, y = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
        float z = (0.0193f * rgb[0] + 0.1192f * rgb[1] + 0.9505f * rgb[2]) / 1.08883f;
        auto f = [](float t) { return t > 0.008856f ? std::cbrt(t) : 7.787f * t + 16.0f / 116.0f; };
        out[0] = 116 * f(y) - 16; out[1] = 500 * (f(x) - f(y)); out[2] = 200 * (f(y) - f(z));
    };
    float p[3], q[3];
    lab(a, p); lab(b, q);
    return std::sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]));
}

// The root blended to the body's skin (blendRootToSkin) on the user's parts: a part texture of another colour, every texel
// a vertex within the sink of the root lands on is the skin's colour within Delta E 3; the shaft's far end keeps its own.
static void rootSkin(const std::string& parts) {
    for (const char* id : {"E853F196527D7CF4", "BC041B5233235A43", "8EAB853E315F99D5", "8B702175BB2F67EB"}) {
        Bytes b; PartData d; std::string err;
        if (!readFile(parts + "/" + id + ".wcbp", b) || !parsePart(b.data(), b.size(), d, err)) continue;
        const int w = 256, h = 256;
        const uint32_t own = 0xD07A48, skin = 0xB3815A;
        std::vector<uint8_t> px(size_t(w) * h * 4);
        for (size_t k = 0; k < size_t(w) * h; ++k) { px[k * 4] = 0xD0; px[k * 4 + 1] = 0x7A; px[k * 4 + 2] = 0x48; px[k * 4 + 3] = 255; }
        CHECK(blendRootToSkin(d, px.data(), w, h, skin) > 0);
        float worstRoot = 0, farthest = 0; uint32_t tipColour = 0;
        std::vector<uint8_t> used(d.vertexCount(), 0);
        for (uint32_t i : d.indices) used[i] = 1;
        for (size_t v = 0; v < d.vertexCount(); ++v) {
            if (!used[v]) continue;   // (a vertex no triangle draws)
            V3 p = partSimsPoint(d, v);
            float dist = (p - V3(kPartRootX, 0, kPartRootZ)).length();
            int x = std::clamp(int(d.uvs[v * 2] * w), 0, w - 1), y = std::clamp(int(d.uvs[v * 2 + 1] * h), 0, h - 1);
            const uint8_t* q = &px[(size_t(y) * w + x) * 4];
            uint32_t c = uint32_t(q[0]) << 16 | uint32_t(q[1]) << 8 | q[2];
            if (dist <= kPartSink) worstRoot = std::fmax(worstRoot, deltaE(c, skin));
            if (dist > farthest) { farthest = dist; tipColour = c; }
        }
        std::printf("part %s: root's colour to the skin's Delta E %.2f at most; the tip's to its own %.2f\n", id, worstRoot, deltaE(tipColour, own));
        CHECK(worstRoot < 3.0f);
        CHECK(deltaE(tipColour, own) < 3.0f);
    }
}

// The entry anchors on the women (the game's default models, when given): the vulva between where the legs part and the
// front, the anus behind it, both centred and close to the skin; the mouth in front of the head, above the chest.
static void women(const std::string& dir) {
    FileLookup lk = [&](uint32_t id) { std::string p = dir + "/" + std::to_string(id); struct stat st; return stat(p.c_str(), &st) == 0 ? p : std::string(); };
    for (uint32_t id : {1000764u, 949470u, 950080u, 921844u, 997378u, 986648u, 940356u, 1018060u, 119369u, 1100258u, 1022598u, 307453u}) {
        M2Model m;
        if (lk(id).empty() || !m.load(id, lk)) continue;
        EntryAnchors e = measureEntries(m, {0, 1301}, true);
        const EntryAnchor& v = e.at[kEntryVaginal]; const EntryAnchor& a = e.at[kEntryAnal]; const EntryAnchor& o = e.at[kEntryOral];
        CHECK(v.ok && a.ok);
        if (!v.ok || !a.ok) continue;
        int pel = m.findWowBone("Pelvis");
        float h = m.bones[size_t(pel)].pivot.z;
        float cm = unitsPerMetre(m, false) / 100.0f;
        std::printf("woman model %u: crotch %.3f vaginal (%.3f, %.3f) anal (%.3f, %.3f) oral %s\n", id, e.crotch, v.point.x, v.point.z, a.point.x, a.point.z, o.ok ? "yes" : "no");
        CHECK(std::fabs(v.point.y) < 1.0f * cm && std::fabs(a.point.y) < 1.0f * cm);
        CHECK(v.point.x > a.point.x);                                  // the vulva in front of the anus
        CHECK(v.point.z > e.crotch && v.point.z < e.crotch + 0.06f * h);
        CHECK(e.crotch > 0.6f * h && e.crotch < h);                    // where the legs part: not the knees
        if (o.ok) CHECK(m.bones[size_t(o.bone)].pivot.z > h);
    }
}

int main(int argc, char** argv) {
    parser();
    measures();
    matrix();
    shaftMaths();
    if (argc > 1) std::printf("%d real part files read\n", realParts(argv[1]));
    if (argc > 1) rootSkin(argv[1]);
    if (argc > 2) { men(argv[2], argv[1]); women(argv[2]); }
    std::printf(failures ? "bodypart_test: %d FAILED\n" : "bodypart_test: all passed\n", failures);
    return failures ? 1 : 0;
}
