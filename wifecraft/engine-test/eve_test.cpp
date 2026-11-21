// The Eve body's maths without GL (engine/evebody.cpp): the .weve file and its checksum, the PNG reader, the colour maths
// (Recolor's gain, Lab and the nearest shade), the texture's composition (tint, overlay, seam feather), the fit on a
// synthetic skeleton and body (the cut rule, the cut edge, the rings snapped onto it with its weights, the torso's map),
// the measure mesh and the breast regions. build/eve_test [SET_DIR FILES_DIR MODEL_ID ...]: with a converted set and the
// game's files, also fits each model given and prints its numbers. Exit 0 when all pass.
#include <chrono>
#include <thread>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <zlib.h>

#include "bytes.h"
#include "evebody.h"
#include "m2.h"
#include "modelpart.h"

using namespace wc;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

// ---------------------------------------------------------------------------------------------------- a synthetic body
namespace {

struct BoneDef { const char* name; int key; int parent; V3 pivot; };
// a woman 2 units tall: the human woman's proportions roughly (model units)
const BoneDef kBones[] = {
    {"Root", 26, -1, V3(0, 0, 1.2f)}, {"Pelvis", 51, 0, V3(0.02f, 0, 1.16f)}, {"Spine1", 4, 1, V3(0, 0, 1.21f)},
    {"Chest", 53, 2, V3(-0.03f, 0, 1.39f)}, {"Neck1", 49, 3, V3(-0.036f, 0, 1.655f)}, {"Head", 6, 4, V3(-0.033f, 0, 1.71f)},
    {"L_UpperArm", 0, 3, V3(-0.04f, 0.17f, 1.58f)}, {"R_UpperArm", 1, 3, V3(-0.04f, -0.17f, 1.58f)},
    {"L_Forearm", 62, 6, V3(-0.06f, 0.37f, 1.36f)}, {"R_Forearm", 61, 7, V3(-0.06f, -0.37f, 1.36f)},
    {"L_Hand", 65, 8, V3(-0.01f, 0.56f, 1.15f)}, {"R_Hand", 64, 9, V3(-0.01f, -0.56f, 1.15f)},
    {"L_Thigh", 56, 1, V3(0.03f, 0.087f, 1.09f)}, {"R_Thigh", 55, 1, V3(0.03f, -0.087f, 1.09f)},
    {"L_Calf", 58, 12, V3(0, 0.087f, 0.59f)}, {"R_Calf", 57, 13, V3(0, -0.087f, 0.59f)},
    {"L_Foot", 59, 14, V3(-0.057f, 0.087f, 0.09f)}, {"R_Foot", 60, 15, V3(-0.057f, -0.087f, 0.09f)},
    {"Twist73", 73, 6, V3(-0.05f, 0.27f, 1.47f)},
};
constexpr int kNeck = 4, kChest = 3, kLCalf = 14, kRCalf = 15, kLFoot = 16, kRFoot = 17, kTwist = 18;

M2Vertex vtx(V3 p, int bone, V3 n = V3(1, 0, 0), float u = 0.5f, float v = 0.5f) {
    M2Vertex m{};
    m.pos[0] = p.x; m.pos[1] = p.y; m.pos[2] = p.z;
    m.normal[0] = n.x; m.normal[1] = n.y; m.normal[2] = n.z;
    m.uv[0] = u; m.uv[1] = v;
    m.bones[0] = uint8_t(bone); m.weights[0] = 255;
    return m;
}

// a tube of `n` around (cx, cy) radius r between z0 (bone b0) and z1 (bone b1), appended to the model
void tube(M2Model& m, V3 c, float r, float z0, float z1, int b0, int b1, int n = 12) {
    uint32_t base = uint32_t(m.vertices.size());
    for (int k = 0; k < 2; ++k)
        for (int i = 0; i < n; ++i) {
            float a = 6.2831853f * i / n;
            V3 dir(std::cos(a), std::sin(a), 0);
            m.vertices.push_back(vtx(V3(c.x + r * dir.x, c.y + r * dir.y, k ? z1 : z0), k ? b1 : b0, dir, float(i) / n, k ? 0.1f : 0.2f));
        }
    for (int i = 0; i < n; ++i) {
        uint32_t j = (i + 1) % n;
        uint32_t a = base + i, b = base + j, c2 = base + n + i, d = base + n + j;
        for (uint32_t t : {a, b, c2, b, d, c2}) m.indices.push_back(t);
    }
}

M2Model syntheticModel() {
    M2Model m;
    m.fileId = 777;
    for (const BoneDef& d : kBones) {
        M2Bone b;
        b.keyBoneId = d.key; b.parent = int16_t(d.parent); b.pivot = d.pivot;
        m.bones.push_back(b);
    }
    // the neck: chest-weighted below (cut), neck-weighted above (kept): the cut edge is the ring at 1.64
    tube(m, V3(-0.03f, 0, 0), 0.045f, 1.56f, 1.64f, kChest, kNeck);
    tube(m, V3(-0.03f, 0, 0), 0.045f, 1.64f, 1.72f, kNeck, kNeck);
    // the legs: calf-weighted down to 0.16 (cut), the feet below (kept)
    for (int s = 0; s < 2; ++s) {
        float y = s ? -0.087f : 0.087f;
        tube(m, V3(-0.06f, y, 0), 0.042f, 0.22f, 0.16f, s ? kRCalf : kLCalf, s ? kRFoot : kLFoot);
        tube(m, V3(-0.06f, y, 0), 0.042f, 0.16f, 0.05f, s ? kRFoot : kLFoot, s ? kRFoot : kLFoot);
    }
    M2Submesh sm; sm.id = 0; sm.indexStart = 0; sm.indexCount = uint32_t(m.indices.size());
    m.submeshes.push_back(sm);
    M2Batch bt; bt.submesh = 0; bt.material = 0; bt.textureCombo = 0;
    m.batches.push_back(bt);
    m.textureCombos.push_back(0);
    M2Texture tx; tx.type = 1; m.textures.push_back(tx);
    m.materials.push_back(M2Material());
    return m;
}

// Eve: the same build at Sims scale (1/1.15), a neck tube open at the top and two shins open at the bottom, a chest point
EveMesh syntheticEve() {
    EveMesh e;
    e.variant = "plain";
    e.bones = {"Chest", "Neck1", "L_Calf", "R_Calf", "L_UpperArmTwist", "L_Breast"};
    const float k = 1 / 1.15f;
    auto J = [&](const char* n, V3 p) { e.joints.push_back({n, p * k}); };
    J("neck", V3(-0.03f, 0, 1.60f)); J("shoulder", V3(-0.04f, 0.17f, 1.58f)); J("elbow", V3(-0.06f, 0.37f, 1.36f)); J("wrist", V3(-0.01f, 0.56f, 1.15f));
    J("hip", V3(0.03f, 0.087f, 1.09f)); J("knee", V3(0, 0.087f, 0.59f)); J("ankle", V3(-0.057f, 0.087f, 0.09f));
    for (const char* f : {"Thumb", "Index", "Mid", "Ring", "Pinky"}) {
        std::string s = f;
        J((s + "K").c_str(), V3(0, 0.6f, 1.1f)); J((s + "P").c_str(), V3(0, 0.62f, 1.08f)); J((s + "T").c_str(), V3(0, 0.64f, 1.06f));
    }
    auto add = [&](V3 p, int bone, float u, float v) {
        e.pos.insert(e.pos.end(), {p.x, p.y, p.z}); e.nrm.insert(e.nrm.end(), {1, 0, 0}); e.uv.insert(e.uv.end(), {u, v}); e.uv2.insert(e.uv2.end(), {0, 0});
        e.bi.insert(e.bi.end(), {uint8_t(bone), 0, 0, 0}); e.bw.insert(e.bw.end(), {255, 0, 0, 0});
        return uint32_t(e.pos.size() / 3 - 1);
    };
    auto etube = [&](V3 c, float r, float z0, float z1, int b0, int b1, const char* ringName, bool ringTop) {
        const int n = 10;
        uint32_t base = uint32_t(e.pos.size() / 3);
        for (int s = 0; s < 2; ++s) for (int i = 0; i < n; ++i) {
            float a = 6.2831853f * i / n;
            add(V3(c.x + r * std::cos(a), c.y + r * std::sin(a), s ? z1 : z0), s ? b1 : b0, 0.1f + 0.08f * i, s ? 0.3f : 0.6f);
        }
        for (int i = 0; i < n; ++i) {
            uint32_t j = (i + 1) % n;
            for (uint32_t t : {base + i, base + j, base + n + i, base + j, base + n + j, base + n + i}) e.idx.push_back(t);
        }
        std::vector<uint32_t> ring;
        for (int i = 0; i < n; ++i) ring.push_back(base + (ringTop ? n : 0) + i);
        e.rings.push_back({ringName, ring});
    };
    etube(V3(-0.03f, 0, 0) * k, 0.04f * k, 1.45f * k, 1.62f * k, 0, 1, "neck", true);
    etube(V3(-0.05f, 0.087f, 0) * k, 0.04f * k, 0.30f * k, 0.50f * k, 2, 2, "L_ankle", false);
    etube(V3(-0.05f, -0.087f, 0) * k, 0.04f * k, 0.30f * k, 0.50f * k, 3, 3, "R_ankle", false);
    // a breast point, weighted to the breast bone (the model has none: resolved to Chest)
    add(V3(0.12f, 0.08f, 1.40f) * k, 5, 0.5f, 0.5f);
    e.texW = 64; e.texH = 64;
    return e;
}

std::vector<uint8_t> pngOf(int w, int h, const std::vector<uint8_t>& rgba) {
    std::vector<uint8_t> raw;
    for (int y = 0; y < h; ++y) {
        raw.push_back(y % 2 ? 1 : 0);   // filters none and sub, alternately
        for (int x = 0; x < w * 4; ++x) {
            uint8_t v = rgba[size_t(y) * w * 4 + x];
            raw.push_back(y % 2 && x >= 4 ? uint8_t(v - rgba[size_t(y) * w * 4 + x - 4]) : v);
        }
    }
    uLongf n = compressBound(uLong(raw.size()));
    std::vector<uint8_t> z(n);
    compress(z.data(), &n, raw.data(), uLong(raw.size()));
    z.resize(n);
    std::vector<uint8_t> out = {137, 80, 78, 71, 13, 10, 26, 10};
    auto chunk = [&](const char* t, const std::vector<uint8_t>& d) {
        uint32_t len = uint32_t(d.size());
        for (int i = 3; i >= 0; --i) out.push_back(uint8_t(len >> (8 * i)));
        out.insert(out.end(), t, t + 4);
        out.insert(out.end(), d.begin(), d.end());
        for (int i = 0; i < 4; ++i) out.push_back(0);   // (the reader doesn't check CRCs)
    };
    std::vector<uint8_t> ih(13, 0);
    for (int i = 0; i < 4; ++i) { ih[3 - i] = uint8_t(w >> (8 * i)); ih[7 - i] = uint8_t(h >> (8 * i)); }
    ih[8] = 8; ih[9] = 6;
    chunk("IHDR", ih); chunk("IDAT", z); chunk("IEND", {});
    return out;
}

} // namespace

int main(int argc, char** argv) {
    // ---- the file
    {
        EveMesh e = syntheticEve();
        std::vector<uint8_t> b = writeEve(e);
        EveMesh r; std::string err;
        CHECK(parseEve(b.data(), b.size(), r, err), "parse: %s", err.c_str());
        CHECK(r.vertexCount() == e.vertexCount() && r.idx == e.idx && r.bones == e.bones && r.rings.size() == 3, "round trip");
        V3 j; CHECK(r.joint("hip", j) && std::fabs(j.z - 1.09f / 1.15f) < 1e-5f, "joint");
        b[b.size() - 3] ^= 0x40;
        CHECK(!parseEve(b.data(), b.size(), r, err) && err.find("checksum") != std::string::npos, "a damaged file is refused (%s)", err.c_str());
    }
    // ---- PNG
    {
        int w = 5, h = 4;
        std::vector<uint8_t> px(size_t(w) * h * 4);
        for (size_t i = 0; i < px.size(); ++i) px[i] = uint8_t(i * 37 + 11);
        std::vector<uint8_t> f = pngOf(w, h, px), back; int bw = 0, bh = 0; std::string err;
        CHECK(decodePng(f.data(), f.size(), back, bw, bh, err) && bw == w && bh == h && back == px, "PNG round trip: %s", err.c_str());
    }
    // ---- colour
    {
        V3 g = eveGain(0x808080, 0x404040);
        float expect = eveToLinear(128 / 255.0f) / eveToLinear(64 / 255.0f);
        CHECK(std::fabs(g.x - expect) < 1e-3f && std::fabs(g.y - g.z) < 1e-6f, "gain %.3f vs %.3f", g.x, expect);
        CHECK(eveRgb(eveLinear(0xC08A6B)) == 0xC08A6Bu, "linear round trip");
        V3 lw = eveLab(0xFFFFFF);
        CHECK(std::fabs(lw.x - 100) < 0.5f && std::fabs(lw.y) < 0.5f && std::fabs(lw.z) < 0.5f, "white's Lab %.2f %.2f %.2f", lw.x, lw.y, lw.z);
        std::vector<EveShadeInfo> sh(3);
        sh[0].swatch = 0x2A201D; sh[1].swatch = 0xEDCFC0; sh[2].swatch = 0xB4765E;
        float d = 0;
        CHECK(eveNearestShade(0xE8C9B8, sh, &d) == 1 && d < 5, "nearest pale (%.1f)", d);
        CHECK(eveNearestShade(0x30241F, sh) == 0, "nearest dark");
    }
    // ---- the texture: tint to the target, the overlay's brightness, the seam's feather
    {
        int w = 16, h = 16;
        std::vector<uint8_t> base(size_t(w) * h * 4, 128), out;
        for (size_t i = 3; i < base.size(); i += 4) base[i] = 255;
        EvePrep prep; prep.w = w; prep.h = h;
        prep.seam.push_back({uint32_t(5 * w + 5), 0.0f, 0});              // on the neck ring
        prep.seam.push_back({uint32_t(6 * w + 6), kEveFeather * 2, 0});   // past the feather
        uint32_t target = 0xC08A6B;
        V3 gain = eveGain(target, 0x808080);
        std::vector<uint8_t> ov(4 * 4 * 4, 0);
        for (int i = 0; i < 16; ++i) { ov[i * 4] = 200; ov[i * 4 + 1] = 100; ov[i * 4 + 2] = 100; ov[i * 4 + 3] = 255; }
        eveCompose(base, w, h, gain, ov.data(), 4, 4, 0, 0, 0.5f, V3(1, 1, 1), prep, 0x102030, 0x102030, out);
        auto rgb = [&](int x, int y) { const uint8_t* p = &out[(size_t(y) * w + x) * 4]; return uint32_t(p[0]) << 16 | uint32_t(p[1]) << 8 | p[2]; };
        uint32_t c = rgb(10, 10);
        CHECK(std::abs(int(c >> 16) - 0xC0) <= 1 && std::abs(int((c >> 8) & 255) - 0x8A) <= 1 && std::abs(int(c & 255) - 0x6B) <= 1, "tinted to the target: %06X", c);
        CHECK(rgb(5, 5) == 0x102030u, "the ring's texel is the Warcraft skin's: %06X", rgb(5, 5));
        CHECK(rgb(6, 6) == c, "past the feather: untouched");
        // the overlay at 50%: halfway (in linear light) between the tinted skin and its colour
        uint32_t o = rgb(1, 1);
        float want = eveToSrgb((eveToLinear(0xC0 / 255.0f) + eveToLinear(200 / 255.0f)) * 0.5f) * 255;
        CHECK(std::fabs(float(o >> 16) - want) <= 1.5f, "overlay blend %d vs %.1f", int(o >> 16), want);
    }
    // ---- the fit on the synthetic body
    {
        M2Model m = syntheticModel();
        EveMesh e = syntheticEve();
        EveRaceFit fit;
        fit.neckTuck = 0; fit.neckLift = 0; fit.ankleTuck = 0; fit.ankleSink = 0;
        EveBody body = fitEve(m, m.vertices, m.indices, m.submeshes, {}, e, fit);
        CHECK(body.ok, "fit: %s", body.error.c_str());
        if (body.ok) {
            CHECK(std::fabs(body.stats.unit - 1.15f) < 0.01f, "unit %.3f", body.stats.unit);
            // the cut: chest- and calf-weighted vertices go, the neck's and the feet's stay
            size_t cutChest = 0, keptNeck = 0;
            for (size_t i = 0; i < m.vertices.size(); ++i) {
                if (m.vertices[i].bones[0] == kChest || m.vertices[i].bones[0] == kLCalf) cutChest += body.cut[i];
                if (m.vertices[i].bones[0] == kNeck || m.vertices[i].bones[0] == kLFoot) keptNeck += !body.cut[i];
            }
            CHECK(cutChest == 24, "cut %zu", cutChest);
            CHECK(keptNeck == 72, "kept %zu", keptNeck);
            CHECK(body.stats.neckEdges == 12 && body.stats.ankleEdges[0] == 12 && body.stats.ankleEdges[1] == 12, "edges %zu %zu %zu",
                  body.stats.neckEdges, body.stats.ankleEdges[0], body.stats.ankleEdges[1]);
            // the rings on the cut edge (the 0.045 ring at 1.64; the feet's 0.042 ring at 0.16), with its weights
            for (const auto& rg : e.rings) {
                bool neck = rg.first == "neck";
                float z = neck ? 1.64f : 0.16f, r = neck ? 0.045f : 0.042f;
                V3 c = neck ? V3(-0.03f, 0, z) : V3(-0.06f, rg.first[0] == 'L' ? 0.087f : -0.087f, z);
                int want = neck ? kNeck : rg.first[0] == 'L' ? kLFoot : kRFoot;
                for (uint32_t v : rg.second) {
                    const M2Vertex& o = body.verts[v];
                    V3 d = V3(o.pos[0], o.pos[1], o.pos[2]) - c;
                    float rad = std::sqrt(d.x * d.x + d.y * d.y);
                    CHECK(std::fabs(d.z) < 1e-4f && rad <= r + 1e-4f && rad > r * 0.9f, "%s vertex %u at dz %.4f r %.4f", rg.first.c_str(), v, d.z, rad);
                    CHECK(o.bones[0] == want && o.weights[0] == 255, "%s vertex %u weighted to bone %d (%d)", rg.first.c_str(), v, o.bones[0], o.weights[0]);
                }
            }
            // the twist helper resolved by its key bone; the breast (missing on this model) to the chest
            CHECK(body.verts.back().bones[0] == kChest, "breast to chest: %d", body.verts.back().bones[0]);
            // weights sum to 255 everywhere
            bool sums = true;
            for (const M2Vertex& v : body.verts) sums &= v.weights[0] + v.weights[1] + v.weights[2] + v.weights[3] == 255;
            CHECK(sums, "weights sum to 255");
            // the measure mesh: the cut body and Eve in the host section
            std::vector<M2Submesh> subs = m.submeshes;
            std::vector<uint32_t> idx = cutTriangles(m.indices, subs, body.cut, 1);
            EveMeasureMesh mm = eveMeasureMesh(m, m.vertices, idx, subs, {}, body);
            CHECK(mm.host == 0 && mm.verts.size() == m.vertices.size() + body.verts.size() && mm.indices.size() == idx.size() + body.indices.size(),
                  "measure mesh %d %zu %zu", mm.host, mm.verts.size(), mm.indices.size());
            auto t0 = std::chrono::steady_clock::now();
            for (int i = 0; i < 20; ++i) fitEve(m, m.vertices, m.indices, m.submeshes, {}, e, fit);
            std::printf("synthetic fit: %.3f ms each\n", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 20);
        }
        // a model without a neck can't take it
        M2Model bad = m;
        for (M2Bone& b : bad.bones) if (b.keyBoneId == 49) b.keyBoneId = -2;
        EveBody none = fitEve(bad, bad.vertices, bad.indices, bad.submeshes, {}, e, fit);
        CHECK(!none.ok && !none.error.empty(), "no neck: refused");
    }
    // ---- the fit table
    {
        EveFitTable t; std::string err;
        CHECK(parseEveFitTable(R"({"races":[{"race":1,"name":"Human","models":[1000764],"enabled":true,"fit":{"girth":1.2,"hips":0.9}},
            {"race":7,"name":"Gnome","models":[940356],"enabled":false,"reason":"no"}]})", t, err), "table: %s", err.c_str());
        const EveRaceFit* h = t.forModel(1000764);
        CHECK(h && h->enabled && std::fabs(h->girth - 1.2f) < 1e-6f && std::fabs(h->hips - 0.9f) < 1e-6f, "human row");
        const EveRaceFit* g = t.forModel(940356);
        CHECK(g && !g->enabled && g->reason == "no" && !t.forModel(1), "gnome row");
        // "defaults": every row starts from them, its own fit over them
        EveFitTable d;
        CHECK(parseEveFitTable(R"({"defaults":{"fit":{"heads":7.3,"hipRatio":0.52,"girth":1.3}},"races":[{"race":1,"models":[1],"enabled":true,
            "fit":{"girth":1.2}},{"race":2,"models":[2],"enabled":true}]})", d, err), "defaults: %s", err.c_str());
        CHECK(d.forModel(1) && std::fabs(d.forModel(1)->heads - 7.3f) < 1e-6f && std::fabs(d.forModel(1)->girth - 1.2f) < 1e-6f &&
              std::fabs(d.forModel(2)->girth - 1.3f) < 1e-6f && std::fabs(d.forModel(2)->hipRatio - 0.52f) < 1e-6f, "defaults under each row");
    }
    // ---- a real set on real models (optional): SET_DIR FILES_DIR MODEL...
    if (argc >= 4) {
        std::string dir = argv[1], files = argv[2];
        Bytes sj; std::string err;
        EveSetIndex ix;
        readFile(dir + "/set.json", sj);
        CHECK(parseEveSetIndex(std::string(sj.begin(), sj.end()), ix, err), "set: %s", err.c_str());
        Bytes mb; EveMesh e;
        readFile(dir + "/" + ix.meshes[0].second, mb);
        CHECK(parseEve(mb.data(), mb.size(), e, err), "mesh: %s", err.c_str());
        FileLookup look = [&](uint32_t id) { std::string p = files + "/" + std::to_string(id); FILE* f = std::fopen(p.c_str(), "rb"); if (!f) return std::string(); std::fclose(f); return p; };
        for (int i = 3; i < argc; ++i) {
            M2Model m;
            if (!m.load(uint32_t(std::atoi(argv[i])), look)) { std::printf("model %s: %s\n", argv[i], m.error.c_str()); continue; }
            EveRaceFit fit;
            auto t0 = std::chrono::steady_clock::now();
            EveBody b = fitEve(m, m.vertices, m.indices, m.submeshes, {}, e, fit);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::printf("model %s: %s %.1f ms, neck edges %zu, ankles %zu/%zu, ring moves %.4f/%.4f/%.4f\n", argv[i], b.ok ? "fitted" : b.error.c_str(), ms,
                        b.stats.neckEdges, b.stats.ankleEdges[0], b.stats.ankleEdges[1], b.stats.ringMoveMean[0], b.stats.ringMoveMean[1], b.stats.ringMoveMean[2]);
            // her proportions: 7.3 of the model's head heights, the factors within their bounds
            EveRaceFit pf;
            pf.heads = 7.3f;
            EveBody pb = fitEve(m, m.vertices, m.indices, m.submeshes, {}, e, pf);
            if (pb.ok) {
                const BodyScale& k = pb.scale;
                std::printf("  proportions: head %.3f height %.3f (%.2f heads) -> %.3f; legs %.3f torso %.3f arms %.3f hands %.3f\n", pb.stats.head,
                            pb.stats.height, pb.stats.height / std::max(pb.stats.head, 1e-3f), pb.stats.heightAfter, k.legs, k.torso, k.arms, k.hands);
                // (every geoset shown here: a model whose extra skin geosets hide its head measure is left as it is)
                CHECK(k.identity() || std::fabs(pb.stats.heightAfter / pb.stats.head - 7.3f) < 0.05f, "model %s: 7.3 heads", argv[i]);
                CHECK(k.legs >= 0.6f && k.legs <= 1.3f && k.torso >= 0.7f && k.torso <= 1.3f && k.arms >= 0.6f && k.hands >= 0.6f, "model %s: factors bounded", argv[i]);
                CHECK(b.scale.identity(), "model %s: no heads target, no edit", argv[i]);
            }
            // the split fit (a draenei's): Eve to mid-thigh, the model's own legs below, the seam rings at the cut
            {
                EveMesh sm = eveSplitMesh(e, 0.5f);
                const std::vector<uint32_t>* rl = sm.ring("L_ankle");
                const std::vector<uint32_t>* rr = sm.ring("R_ankle");
                V3 hip, knee; e.joint("hip", hip); e.joint("knee", knee);
                const float zc = hip.z - (hip.z - knee.z) * 0.5f;
                bool above = rl && rr && !rl->empty() && !rr->empty();
                if (above) for (const auto* r : {rl, rr}) for (uint32_t v : *r) above &= sm.pos[v * 3 + 2] >= zc - 1e-4f;
                float lowest = 1e9f;
                for (uint32_t i : sm.idx) lowest = std::min(lowest, sm.pos[i * 3 + 2]);
                CHECK(above && lowest >= zc - 1e-4f && sm.idx.size() < e.idx.size() && sm.ankleFeather > kEveFeather,
                      "split mesh: rings at the cut (%zu/%zu), nothing drawn below (lowest %.3f, cut %.3f)", rl ? rl->size() : 0, rr ? rr->size() : 0, lowest, zc);
                EveRaceFit sf;
                sf.split = 0.5f;
                EveBody sb = fitEve(m, m.vertices, m.indices, m.submeshes, {}, sm, sf);
                int calves[2] = {m.findWowBone("L_Calf"), m.findWowBone("R_Calf")};
                size_t calfCut = 0;
                for (size_t v = 0; v < m.vertices.size() && sb.ok; ++v) {
                    const M2Vertex& x = m.vertices[v];
                    int best = 0;
                    for (int k = 1; k < 4; ++k) if (x.weights[k] > x.weights[best]) best = k;
                    if (sb.cut[v] && (x.bones[best] == calves[0] || x.bones[best] == calves[1])) ++calfCut;
                }
                std::printf("  split fit: %s, thigh rings %zu/%zu edges, snapped %zu/%zu, move %.4f/%.4f, calf vertices cut %zu\n", sb.ok ? "ok" : sb.error.c_str(),
                            sb.stats.ankleEdges[0], sb.stats.ankleEdges[1], sb.stats.ringSnapped[1], sb.stats.ringSnapped[2], sb.stats.ringMoveMean[1],
                            sb.stats.ringMoveMean[2], calfCut);
                CHECK(sb.ok && calfCut == 0 && sb.stats.ankleEdges[0] > 3 && sb.stats.ankleEdges[1] > 3, "model %s: split fit keeps the calves, finds both thigh rings", argv[i]);
            }
            // the fit off the GL thread: the worker's result is byte for byte the one computed at once
            if (!ix.hasSkin) continue;
            auto set = std::make_shared<EveSet>();
            set->dir = dir; set->index = ix;
            auto mesh = std::make_shared<EveMesh>(e);
            auto job = [&](uint64_t serial, uint64_t key) {
                auto j = std::make_unique<EveJob>();
                j->serial = serial; j->key = key; j->base = 1; j->model = &m; j->set = set; j->mesh = mesh; j->fit = pf;
                j->opt.overlaySlot = ix.overlays.empty() ? -1 : 0;
                j->indices = m.indices; j->subs = m.submeshes;
                j->sculpt = {0, 0.3f, -0.2f, 0.4f, 0, 0, 0.2f, 0.3f, 0, 0, 0};
                j->rig = sculptRig({}, {}, SculptBones());
                return j;
            };
            EveResult now = eveCompute(*job(1, 8));
            EveWorker w;
            w.submit(job(5, 7));   // (the same actor's older look: replaced while queued, or run and its result replaced)
            w.submit(job(5, 8));
            auto t1 = std::chrono::steady_clock::now();
            while (!w.idle() && std::chrono::steady_clock::now() - t1 < std::chrono::seconds(20)) std::this_thread::sleep_for(std::chrono::milliseconds(2));
            std::unique_ptr<EveResult> later = w.take(5);
            CHECK(later && later->key == 8, "model %s: the newest look's fit is the one kept", argv[i]);
            CHECK(later && later->ok && now.ok, "model %s: the worker's fit came", argv[i]);
            if (later && now.ok) {
                bool same = later->body.verts.size() == now.body.verts.size() &&
                            !std::memcmp(later->body.verts.data(), now.body.verts.data(), now.body.verts.size() * sizeof(M2Vertex)) &&
                            later->rgba == now.rgba && later->cutIndices == now.cutIndices && later->body.indices == now.body.indices &&
                            later->measure.indices == now.measure.indices && later->neckRgb == now.neckRgb && later->shade == now.shade &&
                            later->body.scale == now.body.scale;
                CHECK(same, "model %s: the worker's result is the same bytes as the one at once", argv[i]);
                std::printf("  worker and at once: %s (%zu vertices, %zu texture bytes, %zu cut indices)\n", same ? "identical" : "DIFFERENT",
                            now.body.verts.size(), now.rgba.size(), now.cutIndices.size());
            }
            CHECK(!w.take(5) && w.idle(), "model %s: nothing left behind", argv[i]);
        }
    }
    std::printf(failures ? "%d FAILED\n" : "eve_test: all passed\n", failures);
    return failures ? 1 : 0;
}
