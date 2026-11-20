// Model parts without a GL context (engine/modelpart.h): the regions cut by bone dominance on a made-up skeleton and mesh
// (head and hair, a pauldron's helper, the arm's shell against its skin, a strap by the neck, a tasset hung too low, the
// forearm left out), the pieces too small kept out, the pose baked in, the stand-in's cut, the fit and its matrix, the
// version 2 part file written and read back, the manifest, and the game's models when given.
//   build/modelpart_test [FILES_DIR]   exit 0 when all pass
#include <cmath>
#include <cstdio>
#include <string>
#include <sys/stat.h>
#include <vector>

#include "bytes.h"
#include "modelpart.h"

using namespace wc;

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); ++failures; } } while (0)
static bool near(float a, float b, float e = 1e-4f) { return std::fabs(a - b) <= e; }

// Bones: 0 Root, 1 Pelvis, 2 Chest, 3 Neck1, 4 Head, 5 a hair helper, 6 L_Clavicle, 7 L_UpperArm, 8 L_Forearm, 9 a pauldron
// helper (on the clavicle), 10 a tasset helper (on the upper arm, hung below the reach), 11 a sleeve helper (on the forearm).
static M2Model skeleton(float headZ = 1.6f, float pelvisZ = 1.0f) {
    M2Model m;
    struct B { int key, parent; V3 pivot; };
    float s = (headZ - pelvisZ) / 0.6f;   // (a taller torso: everything above the pelvis stretched)
    auto up = [&](float z) { return pelvisZ + (z - 1.0f) * s; };
    const B bones[] = {
        {26, -1, V3(0, 0, up(1.0f))}, {51, 0, V3(0, 0, up(1.0f))}, {53, 1, V3(0, 0, up(1.3f))}, {49, 2, V3(0, 0, up(1.5f))},
        {6, 3, V3(0, 0, up(1.6f))}, {-1, 4, V3(-0.05f, 0, up(1.7f))}, {2, 2, V3(0, 0.04f, up(1.45f))}, {0, 6, V3(0, 0.15f, up(1.45f))},
        {62, 7, V3(0, 0.3f, up(1.2f))}, {-1, 6, V3(0, 0.17f, up(1.5f))}, {-1, 7, V3(0, 0.2f, up(1.1f))}, {-1, 8, V3(0, 0.35f, up(1.1f))},
    };
    for (const B& b : bones) {
        M2Bone o;
        o.keyBoneId = b.key;
        o.parent = int16_t(b.parent);
        o.pivot = b.pivot;
        m.bones.push_back(o);
    }
    return m;
}

// A triangle about `c` (a small one in the y-z plane, facing +x), all its corners on `bone`.
static void tri(M2Model& m, int bone, V3 c, float size = 0.01f) {
    V3 corners[3] = {c, c + V3(0, size, 0), c + V3(0, 0, size)};
    for (V3 p : corners) {
        M2Vertex v{};
        v.pos[0] = p.x; v.pos[1] = p.y; v.pos[2] = p.z;
        v.normal[0] = 1;
        v.bones[0] = uint8_t(bone); v.weights[0] = 255;
        v.uv[0] = 0.25f; v.uv[1] = 0.75f;
        m.indices.push_back(uint32_t(m.vertices.size()));
        m.vertices.push_back(v);
    }
}

// The skeleton with one triangle per case, in one submesh (id 0) drawn by one opaque batch with texture file 77.
static M2Model mesh() {
    M2Model m = skeleton();
    tri(m, 4, V3(0.08f, 0, 1.65f));            // 0 the face (Head)
    tri(m, 5, V3(-0.1f, 0, 1.7f));             // 1 the hair (its helper)
    tri(m, 3, V3(0.05f, 0, 1.52f));            // 2 the neck (Neck1: not the head's)
    tri(m, 9, V3(0, 0.17f, 1.52f));            // 3 the pauldron (the clavicle's helper)
    tri(m, 7, V3(0, 0.16f, 1.43f));            // 4 the upper arm's skin, by its axis
    tri(m, 7, V3(0, 0.22f, 1.5f));             // 5 the upper arm's shell, off its axis
    tri(m, 6, V3(-0.15f, 0.03f, 1.45f));       // 6 a strap on the clavicle by the neck (off the axis, near the midline)
    tri(m, 10, V3(0, 0.2f, 1.08f));            // 7 a tasset (a helper hung below the reach)
    tri(m, 8, V3(0, 0.3f, 1.2f));              // 8 the forearm (a stop)
    tri(m, 11, V3(0, 0.35f, 1.1f));            // 9 a sleeve (a helper under the stop)
    M2Submesh sm;
    sm.id = 0; sm.indexStart = 0; sm.indexCount = uint32_t(m.indices.size());
    m.submeshes.push_back(sm);
    M2Texture t; t.fileId = 77;
    m.textures.push_back(t);
    m.textureCombos.push_back(0);
    m.materials.push_back(M2Material());
    M2Batch b; b.submesh = 0; b.textureCombo = 0; b.material = 0;
    m.batches.push_back(b);
    return m;
}

static std::vector<int> triangles(const std::vector<bool>& mask) {
    std::vector<int> out;
    for (size_t t = 0; t * 3 + 2 < mask.size(); ++t) if (mask[t * 3] && mask[t * 3 + 1] && mask[t * 3 + 2]) out.push_back(int(t));
    return out;
}

static void regions() {
    M2Model m = mesh();
    CHECK(near(torsoLength(m), 0.6f));
    std::vector<bool> sub = boneSubtrees(m, {"Head"});
    CHECK(sub[4] && sub[5] && !sub[3] && !sub[2]);
    PartRegion head = defaultPartRegions().at("head");
    CHECK((triangles(regionVertices(m, head)) == std::vector<int>{0, 1}));
    PartRegion sh = defaultPartRegions().at("shoulder_l");
    // the pauldron's helper and the shell; not the skin by the axis, the strap by the neck, the tasset hung low, the forearm
    CHECK((triangles(regionVertices(m, sh)) == std::vector<int>{3, 5}));
    PartRegion noStandoff = sh; noStandoff.standoff = 0;
    CHECK((triangles(regionVertices(m, noStandoff)) == std::vector<int>{3, 4, 5}));
    PartRegion noSide = sh; noSide.side = 0;
    CHECK((triangles(regionVertices(m, noSide)) == std::vector<int>{3, 5, 6}));
    PartRegion noReach = sh; noReach.reach = -1;
    CHECK((triangles(regionVertices(m, noReach)) == std::vector<int>{3, 5, 7}));
    PartRegion helpersOnly = sh; helpersOnly.named = false;
    CHECK((triangles(regionVertices(m, helpersOnly)) == std::vector<int>{3}));
    PartRegion boxed = head; boxed.box = {-1, 0, -1, 1, -1, 1};   // behind the Head pivot only: the hair
    CHECK((triangles(regionVertices(m, boxed)) == std::vector<int>{1}));
    PartRegion keyed = head; keyed.roots = {"#6"};               // a bone by its key id
    CHECK((triangles(regionVertices(m, keyed)) == std::vector<int>{0, 1}));
}

static void extract() {
    M2Model m = mesh();
    PartRegion head = defaultPartRegions().at("head");
    head.islands = 1;
    std::vector<PartData> parts;
    CHECK(extractRegion(m, {}, head, {}, "set/", parts) == 2);
    CHECK(parts.size() == 1);
    if (parts.size() != 1) return;
    const PartData& p = parts[0];
    CHECK(p.modelFrame() && p.version == kPartVersion2 && p.attachBone == "Head" && p.id == "set/head_0" && p.variant == "head");
    CHECK(p.textureFileId == 77 && p.blend == 0 && p.vertexCount() == 6 && p.indices.size() == 6);
    CHECK(near(p.anchorSims.z, 1.6f) && p.refs.size() == 2 && p.refs[0].first == "Head" && p.refs[1].first == "Pelvis");
    V3 q = partModelPoint(p, 0);
    CHECK(near(q.x, 0.08f) && near(q.y, 0) && near(q.z, 1.65f));
    CHECK(near(p.positions[2], 0.05f) && near(p.uvs[1], 0.75f));
    // shown geosets: a submesh not shown gives nothing
    parts.clear();
    CHECK(extractRegion(m, {5}, head, {}, "", parts) == 0 && parts.empty());
    // a texture slot without a file for it: nothing drawn, nothing cut; with one, that file
    M2Model slot = m;
    slot.textures[0].type = 11; slot.textures[0].fileId = 0;
    parts.clear();
    CHECK(extractRegion(slot, {}, head, {}, "", parts) == 0);
    CHECK(extractRegion(slot, {}, head, {{11, 99}}, "", parts) == 2 && parts.size() == 1 && parts[0].textureFileId == 99);
    // pieces: the face and hair are two lone triangles; the default (12) drops both, 2 keeps a piece of two
    parts.clear();
    CHECK(extractRegion(m, {}, defaultPartRegions().at("head"), {}, "", parts) == 0);
    M2Model joined = m;
    joined.vertices[3] = joined.vertices[0];   // the hair's first corner at the face's: one piece of two
    PartRegion two = head; two.islands = 2;
    parts.clear();
    CHECK(extractRegion(joined, {}, two, {}, "", parts) == 2);
    PartRegion three = head; three.islands = 3;
    parts.clear();
    CHECK(extractRegion(joined, {}, three, {}, "", parts) == 0);
}

static void posed() {
    M2Model m = mesh();
    PartRegion head = defaultPartRegions().at("head");
    head.islands = 1;
    // the whole head turned a quarter about z at its pivot, the hair's helper lifted 0.1 more: taken back into the Head's
    // bind frame, the face is where it was modelled and the hair 0.1 higher
    std::vector<M4> pose(m.bones.size());
    V3 hp = m.bones[4].pivot;
    M4 turn = M4::translation(hp) * M4::rotationZ(1.5707963f) * M4::translation(V3() - hp);
    pose[4] = turn;
    pose[5] = turn * M4::translation(V3(0, 0, 0.1f));
    std::vector<PartData> parts;
    CHECK(extractRegion(m, {}, head, {}, "", parts, &pose) == 2 && parts.size() == 1);
    if (parts.empty()) return;
    V3 face = partModelPoint(parts[0], 0), hair = partModelPoint(parts[0], 3);
    CHECK(near(face.x, 0.08f, 1e-3f) && near(face.y, 0, 1e-3f) && near(face.z, 1.65f, 1e-3f));
    CHECK(near(hair.x, -0.1f, 1e-3f) && near(hair.y, 0, 1e-3f) && near(hair.z, 1.8f, 1e-3f));
    // the inverse itself
    M4 a = M4::translation(V3(1, 2, 3)) * M4::rotationZ(0.7f) * M4::scaling(V3(2, 2, 2));
    V3 r = (invertMatrix(a) * a).point(V3(0.3f, -0.2f, 0.9f));
    CHECK(near(r.x, 0.3f, 1e-4f) && near(r.y, -0.2f, 1e-4f) && near(r.z, 0.9f, 1e-4f));
}

static void cut() {
    M2Model m = mesh();
    std::vector<M2Submesh> subs = m.submeshes;
    PartRegion r;
    r.roots = {"Head"};
    r.attach = "Head";
    std::vector<bool> mask = regionVertices(m, r);
    std::vector<uint32_t> idx = cutTriangles(m.indices, subs, mask, 3);
    CHECK(idx.size() == m.indices.size() - 6 && subs[0].indexStart == 0 && subs[0].indexCount == idx.size());
    CHECK(idx[0] == 6);   // the face's and the hair's triangles gone, the neck's first
    // a triangle with two corners on the head stays (the seam's: the piece overlaps it rather than leaving a gap)
    M2Model seam = m;
    seam.vertices[2].bones[0] = 3;   // the face's third corner on the neck
    std::vector<M2Submesh> s2 = seam.submeshes;
    std::vector<uint32_t> i2 = cutTriangles(seam.indices, s2, regionVertices(seam, r), 3);
    CHECK(i2.size() == seam.indices.size() - 3);
    std::vector<M2Submesh> s3 = seam.submeshes;
    CHECK(cutTriangles(seam.indices, s3, regionVertices(seam, r), 2).size() == seam.indices.size() - 6);
}

static void fit() {
    M2Model src = mesh();
    PartRegion head = defaultPartRegions().at("head");
    head.islands = 1;
    std::vector<PartData> parts;
    extractRegion(src, {}, head, {}, "", parts);
    if (parts.empty()) { CHECK(false); return; }
    // a body whose torso is 0.8 (Head 2.0, Pelvis 1.2): the head four thirds the size, its pivot on the body's
    M2Model body = skeleton(2.0f, 1.2f);
    BonePartPlacement pl = placeBonePart(parts[0], body, BonePartFit());
    CHECK(pl.ok && pl.bone == 4 && near(pl.scale, 0.8f / 0.6f) && near(pl.target.z, 2.0f) && near(pl.source.z, 1.6f));
    M4 mat = bonePartMatrix(M4(), pl);
    V3 a = mat.point(V3(0.1f, 0, 1.6f));
    CHECK(near(a.x, 0.1f * 0.8f / 0.6f) && near(a.z, 2.0f));
    // the fit: up a tenth of the body's scale bones' length, and a factor on the size
    BonePartFit f;
    f.offset = V3(0, 0, 0.1f);
    f.scale = 0.5f;
    BonePartPlacement pf = placeBonePart(parts[0], body, f);
    CHECK(near(pf.target.z, 2.08f) && near(pf.scale, 0.8f / 0.6f * 0.5f));
    // the body's head turned a quarter about z at its pivot: a point in front of the head goes to its left
    M4 bone = M4::translation(V3(0, 0, 2.0f)) * M4::rotationZ(1.5707963f) * M4::translation(V3(0, 0, -2.0f));
    V3 t = bonePartMatrix(bone, pl).point(V3(0.1f, 0, 1.6f));
    CHECK(near(t.x, 0, 1e-4f) && near(t.y, 0.1f * 0.8f / 0.6f) && near(t.z, 2.0f));
    // a body without the bone: not placed
    M2Model none;
    CHECK(!placeBonePart(parts[0], none, BonePartFit()).ok);
}

static void file() {
    M2Model m = mesh();
    PartRegion sh = defaultPartRegions().at("shoulder_l");
    sh.islands = 1;
    std::vector<PartData> parts;
    extractRegion(m, {}, sh, {}, "s/", parts);
    if (parts.empty()) { CHECK(false); return; }
    parts[0].blend = 1; parts[0].materialFlags = 4;
    std::vector<uint8_t> bytes = writePart(parts[0]);
    PartData back;
    std::string err;
    CHECK(parsePart(bytes.data(), bytes.size(), back, err));
    CHECK(back.version == kPartVersion2 && back.modelFrame() && back.attachBone == "L_Clavicle" && back.id == "s/shoulder_l_0");
    CHECK(back.textureFileId == 77 && back.blend == 1 && back.materialFlags == 4 && back.refs.size() == 2);
    CHECK(back.refs[0].first == "L_UpperArm" && near(back.refs[0].second.y, 0.15f) && back.ref("R_UpperArm") && !back.ref("Head"));
    CHECK(back.positions == parts[0].positions && back.uvs == parts[0].uvs && back.indices == parts[0].indices);
    CHECK(near(back.anchorSims.y, 0.04f) && back.normals.size() == back.positions.size());
    // cut short inside the reference bones
    std::vector<uint8_t> cutShort(bytes.begin(), bytes.begin() + 120);
    CHECK(!parsePart(cutShort.data(), cutShort.size(), back, err));
}

static void manifest() {
    const char* good = R"({
      "regions": {"backpiece": {"attach": "Chest", "roots": ["Chest"], "box": [-3, -0.2, -3, 3, 0.2, 3], "islands": 40,
                                "scale": ["L_UpperArm", "R_UpperArm"]},
                  "head": {"islands": 5}},
      "sets": [{"set": "a_partial", "character": "a", "look": "x", "display": 5, "model": 123, "standIn": 456,
                "standInLook": "bare", "textures": {"11": 900}, "geosets": [0, 101], "regions": ["head", "backpiece"],
                "fit": {"head": {"offset": [0, 0, 0.01], "scale": 1.02}}, "skinMatch": false},
               {"set": "b_partial", "model": 7, "regions": ["shoulder_l"], "cut": []}]
    })";
    ModelPartManifest man;
    std::string err;
    CHECK(parseModelPartManifest(good, man, err));
    CHECK(man.sets.size() == 2 && man.regions.count("shoulder_r") && man.regions.count("backpiece"));
    const PartRegion& bp = man.regions["backpiece"];
    CHECK(bp.attach == "Chest" && bp.box.size() == 6 && near(bp.box[1], -0.2f) && bp.islands == 40 && bp.scaleA == "L_UpperArm");
    CHECK(man.regions["head"].islands == 5 && man.regions["head"].attach == "Head");   // a built-in, changed in part
    const ModelPartSet& a = man.sets[0];
    CHECK(a.set == "a_partial" && a.display == 5 && a.model == 123 && a.standIn == 456 && a.standInLook == "bare");
    CHECK(a.textures.at(11) == 900 && (a.geosets == std::vector<uint16_t>{0, 101}) && a.regions.size() == 2 && !a.skinMatch);
    CHECK((a.cut == std::vector<std::string>{"Head"}) && near(a.fits.at("head").offset.z, 0.01f) && near(a.fits.at("head").scale, 1.02f));
    CHECK(man.sets[1].cut.empty() && man.sets[1].skinMatch);
    CHECK(!parseModelPartManifest(R"({"sets": [{"set": "../x", "model": 1, "regions": ["head"]}]})", man, err));
    CHECK(!parseModelPartManifest(R"({"sets": [{"set": "x", "model": 1, "regions": ["wings"]}]})", man, err) && err.find("wings") != std::string::npos);
    CHECK(!parseModelPartManifest(R"({"sets": [{"set": "x", "regions": ["head"]}]})", man, err));
    CHECK(!parseModelPartManifest(R"({"regions": {"r": {"roots": ["Head"]}}})", man, err));   // no attach bone
    CHECK(!parseModelPartManifest("[1, 2", man, err));
    // a model not downloaded
    std::string report;
    CHECK(parseModelPartManifest(good, man, err));
    CHECK(!buildModelPartSet(man.sets[0], man, [](uint32_t) { return std::string(); }, "/nonexistent", report) && report.find("isn't downloaded") != std::string::npos);
}

static void real(const std::string& dir) {
    FileLookup lk = [&](uint32_t id) { std::string p = dir + "/" + std::to_string(id); struct stat st; return stat(p.c_str(), &st) == 0 ? p : std::string(); };
    M2Model syl, standIn;
    if (lk(3221727).empty() || !syl.load(3221727, lk) || lk(1733758).empty() || !standIn.load(1733758, lk)) {
        std::fprintf(stderr, "skip the game's models (not here)\n");
        return;
    }
    std::vector<PartData> head, shoulders;
    size_t ht = extractRegion(syl, {}, defaultPartRegions().at("head"), {}, "", head);
    size_t st = extractRegion(syl, {}, defaultPartRegions().at("shoulder_l"), {}, "", shoulders);
    std::printf("Sylvanas (3221727): head %zu triangles in %zu parts, left pauldron %zu in %zu\n", ht, head.size(), st, shoulders.size());
    CHECK(ht > 1500 && st > 300);
    V3 hp = syl.bones[size_t(syl.findWowBone("Head"))].pivot;
    for (const PartData& p : head)
        for (size_t v = 0; v < p.vertexCount(); ++v) CHECK((partModelPoint(p, v) - hp).length() < 0.45f);
    for (const PartData& p : shoulders)
        for (size_t v = 0; v < p.vertexCount(); ++v) CHECK(partModelPoint(p, v).y > 0.0f && partModelPoint(p, v).z > 1.3f);
    // on the Void Elf woman's skeleton (the same rig): in place, the same size
    BonePartPlacement pl = placeBonePart(head[0], standIn, BonePartFit());
    CHECK(pl.ok && near(pl.scale, 1.0f, 0.02f) && (pl.target - pl.source).length() < 0.01f);
    // the stand-in's cut: its head and hair, a third or so of its triangles; its neck kept
    PartRegion r; r.roots = {"Head"}; r.attach = "Head";
    std::vector<M2Submesh> subs = standIn.submeshes;
    std::vector<uint32_t> kept = cutTriangles(standIn.indices, subs, regionVertices(standIn, r), 3);
    std::printf("Void Elf woman (1733758): %zu of %zu triangles kept by the cut\n", kept.size() / 3, standIn.indices.size() / 3);
    CHECK(kept.size() < standIn.indices.size() && kept.size() > standIn.indices.size() / 3);
    std::vector<float> uvs;
    CHECK(maskUvs(standIn, {}, faceVertices(standIn), [](const M2Texture& t) { return t.type == 1 ? 1u : 0u; }, uvs) == 1 && uvs.size() >= 20);
}

int main(int argc, char** argv) {
    regions();
    extract();
    posed();
    cut();
    fit();
    file();
    manifest();
    if (argc > 1) real(argv[1]);
    std::printf(failures ? "modelpart_test: %d FAILED\n" : "modelpart_test: all passed\n", failures);
    return failures ? 1 : 0;
}
