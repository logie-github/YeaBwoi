// Body parts without a GL context (engine/bodypart.h): the part file read back (a file made here as bodyparts/PartFile
// writes it, its damaged forms, and real exported parts when given), the lower body's measurements on a made-up mesh,
// the attach matrix, and the placement on the game's men when their files are given.
//   build/bodypart_test [PARTS_DIR [FILES_DIR]]   exit 0 when all pass
#include <cmath>
#include <cstdio>
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

static void men(const std::string& dir) {
    FileLookup lk = [&](uint32_t id) { std::string p = dir + "/" + std::to_string(id); struct stat st; return stat(p.c_str(), &st) == 0 ? p : std::string(); };
    // human, orc, night elf, tauren men: the parts' root above where the legs part, on the body's front, near the midline
    for (uint32_t id : {1011653u, 917116u, 974343u, 968705u}) {
        M2Model m;
        if (lk(id).empty() || !m.load(id, lk)) { std::fprintf(stderr, "skip model %u (not here)\n", id); continue; }
        PartPlacement pl = placeParts(m, {0, 1301}, PartFit());
        CHECK(pl.ok);
        float h = pl.pivot.z;
        std::printf("model %u: pelvis %.3f crotch %.3f root (%.3f, %.3f, %.3f) scale %.3f\n", id, h, pl.crotch, pl.root.x, pl.root.y, pl.root.z, pl.scale);
        CHECK(pl.crotch > 0.4f * h && pl.crotch < h);
        CHECK(pl.root.z > pl.crotch && pl.root.z < h + 0.05f * h);
        CHECK(pl.root.x > pl.pivot.x && pl.root.x < pl.pivot.x + 0.3f * h);
    }
}

int main(int argc, char** argv) {
    parser();
    measures();
    matrix();
    if (argc > 1) std::printf("%d real part files read\n", realParts(argv[1]));
    if (argc > 2) men(argv[2]);
    std::printf(failures ? "bodypart_test: %d FAILED\n" : "bodypart_test: all passed\n", failures);
    return failures ? 1 : 0;
}
