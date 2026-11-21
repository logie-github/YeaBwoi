// The Eve body's maths without GL (engine/evebody.cpp): the .weve file and its checksum, the PNG reader, the colour maths
// (Recolor's gain, Lab and the nearest shade), the texture's composition (tint, overlay, seam feather), the fit on a
// synthetic skeleton and body (the cut rule, the cut edge, the rings snapped onto it with its weights, the torso's map),
// the measure mesh and the breast regions. build/eve_test [SET_DIR FILES_DIR MODEL_ID ...]: with a converted set and the
// game's files, also fits each model given and prints its numbers (MODEL:SKIN:FIT_JSON[:GEO]: the real fit table's row, the
// seams' colour, the posed seams; with GEO, a look's geoset groups "0=2,4=1,...", also her flesh as the scene measures it and
// the ankles' step). Exit 0 when all pass.
#include <chrono>
#include <thread>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <array>
#include <map>
#include <string>
#include <vector>

#include <zlib.h>

#include <algorithm>

#include "bodyrig.h"
#include "bytes.h"
#include "evebody.h"
#include "m2.h"
#include "modelpart.h"
#include "flesh.h"

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

// The fit in the game's own poses (the model's sequences whose keys are in FILES_DIR: sitting on a chair, kneeling, lying dead
// and asleep, cheering with the arms up, bowing), skinned on the CPU as the renderer skins her (the skeleton edited by her
// proportions, bodyrig.h): the seams can't open (each stitch vertex on its model vertex), the strip between her ring and the
// model's edge stays a strip (no edge across it grown more than 3 cm, or than half again the model's own cut edge grows), and her own triangles stretch and collapse
// little more than the model's own body does in the same pose (each triangle's longest edge growth over the bind pose: her
// 99.9th percentile within 1.5 times the model's and 1 cm more; her coarser mesh spans a joint with fewer, longer
// triangles; her collapsed triangles, area under 5 % of the bind pose's, at most 0.2 % of them). A pose whose sequence or .anim isn't
// here is skipped and said.
void posedSeams(M2Model& m, const FileLookup& look, const EveResult& res, size_t eveVerts, const std::string& name, const EveRaceFit& fit) {
    struct Pose { const char* what; uint16_t anim; };
    const Pose poses[] = {{"sit on a chair", 103}, {"kneel", 115}, {"lie dead", 1}, {"lie asleep", 100},
                          {"cheer, arms up", 68}, {"bow", 66}};
    const std::vector<M2Vertex>& ev = res.body.verts;
    const std::vector<uint32_t>& ei = res.body.indices;
    // each stitch vertex's model vertex (the same place and weights: the check above)
    std::vector<int> twin(ev.size(), -1);
    for (size_t v = eveVerts; v < ev.size(); ++v)
        for (size_t w = 0; w < m.vertices.size(); ++w) {
            const M2Vertex& a = ev[v]; const M2Vertex& b = m.vertices[w];
            if (std::fabs(a.pos[0] - b.pos[0]) < 1e-6f && std::fabs(a.pos[1] - b.pos[1]) < 1e-6f && std::fabs(a.pos[2] - b.pos[2]) < 1e-6f) { twin[v] = int(w); break; }
        }
    // (on her own rig, the Eve rig's pivots: as the scene runs her, engine/bodyrig.h bodyEditFrom)
    LegEdit edit = res.body.rigPivots.empty() ? bodyEditOf(m, res.body.scale) : bodyEditFrom(m, res.body.rigPivots, res.body.scale);
    auto skinAll = [&](const std::vector<M2Vertex>& vs, const std::vector<M4>& mats, std::vector<V3>& out) {
        out.resize(vs.size());
        for (size_t i = 0; i < vs.size(); ++i) {
            V3 p(vs[i].pos[0], vs[i].pos[1], vs[i].pos[2]), s;
            for (int k = 0; k < 4; ++k) if (vs[i].weights[k] && vs[i].bones[k] < mats.size()) s = s + mats[vs[i].bones[k]].point(p) * (vs[i].weights[k] / 255.0f);
            out[i] = s;
        }
    };
    // per triangle: the longest edge's stretch over the bind pose's, and the area's share of the bind pose's
    auto tris = [&](const std::vector<V3>& rest, const std::vector<V3>& posed, const std::vector<uint32_t>& idx, size_t below,
                    std::vector<float>& stretch, size_t& collapsed, size_t& counted) {
        stretch.clear(); collapsed = counted = 0;
        for (size_t t = 0; t + 2 < idx.size(); t += 3) {
            uint32_t a = idx[t], b = idx[t + 1], c = idx[t + 2];
            if (a >= below || b >= below || c >= below) continue;
            float worst = 0;   // the longest edge's growth over the bind pose (model units: a stretch the eye sees, not a 2 mm edge doubled)
            for (auto [x, y] : {std::pair<uint32_t, uint32_t>{a, b}, {b, c}, {c, a}})
                worst = std::max(worst, (posed[x] - posed[y]).length() - (rest[x] - rest[y]).length());
            float ar = (rest[b] - rest[a]).cross(rest[c] - rest[a]).length(), ap = (posed[b] - posed[a]).cross(posed[c] - posed[a]).length();
            if (ar < 1e-9f) continue;
            ++counted;
            stretch.push_back(worst);
            if (ap < 0.05f * ar) ++collapsed;
        }
        std::sort(stretch.begin(), stretch.end());
    };
    auto pct = [](const std::vector<float>& s, double q) { return s.empty() ? 0.0f : s[std::min(s.size() - 1, size_t(q * double(s.size())))]; };
    std::vector<M4> bindMats;
    if (edit.active) legEditMatrices(m.bones, std::vector<BonePose>(), edit, bindMats); else bindMats.assign(m.bones.size(), M4());
    std::vector<V3> eRest, mRest;
    skinAll(ev, bindMats, eRest);
    std::vector<M4> plain(m.bones.size(), M4());
    skinAll(m.vertices, plain, mRest);
    for (const Pose& p : poses) {
        int seq = m.findSequence(p.anim);
        if (seq >= 0) m.ensureSequence(seq, look);
        if (seq < 0 || !m.sequenceReady(seq)) { std::printf("  %s, %s: not here (anim %u), skipped\n", name.c_str(), p.what, p.anim); continue; }
        // the sequence sampled through: the worst moment of each measure
        float gap = 0, strip = 0, modelEdge = 0, e999 = 0, m999 = 0, eMax = 0, mMax = 0, e99 = 0, m99 = 0;
        size_t ec = 0, en = 0, mc = 0, mn = 0;
        const uint32_t dur = std::max<uint32_t>(1, m.sequences[size_t(seq)].duration);
        for (float f : {0.2f, 0.4f, 0.6f, 0.8f, 0.97f}) {
            uint32_t t = uint32_t(f * float(dur));
            std::vector<BonePose> bp; m.pose(seq, t, t, bp, false);
            std::vector<M4> em, mm;
            if (edit.active) legEditMatrices(m.bones, bp, edit, em); else m.bonesToMatrices(bp, em);
            m.bonesToMatrices(bp, mm);
            std::vector<V3> eP, mP, mPose;
            skinAll(ev, em, eP);
            skinAll(m.vertices, em, mP);   // the model's edge under the same (edited) skeleton, as the renderer draws the cut body
            skinAll(m.vertices, mm, mPose);
            for (size_t v = eveVerts; v < ev.size(); ++v) if (twin[v] >= 0) gap = std::max(gap, (eP[v] - mP[size_t(twin[v])]).length());
            for (size_t k = 0; k + 2 < ei.size(); k += 3) {
                if (ei[k] < eveVerts && ei[k + 1] < eveVerts && ei[k + 2] < eveVerts) continue;
                for (int q = 0; q < 3; ++q) {
                    uint32_t x = ei[k + q], y = ei[k + (q + 1) % 3];
                    if (x >= eveVerts && y >= eveVerts) {   // the model's own edge at the cut: how much the model itself stretches there
                        modelEdge = std::max(modelEdge, (eP[x] - eP[y]).length() - (eRest[x] - eRest[y]).length());
                        continue;
                    }
                    float r = (eRest[x] - eRest[y]).length(), d = (eP[x] - eP[y]).length();
                    strip = std::max(strip, d - r);
                    if (getenv("EVE_DIAG") && d - r > 0.01f) std::printf("    strip edge %u-%u rest %.4f posed %.4f at %.2f %.2f %.2f\n", x, y, r, d, eRest[x].x, eRest[x].y, eRest[x].z);
                }
            }
            std::vector<float> es, ms; size_t c1, n1, c2, n2;
            tris(eRest, eP, ei, eveVerts, es, c1, n1);
            tris(mRest, mPose, m.indices, m.vertices.size(), ms, c2, n2);
            if (getenv("EVE_DIAG") && f == 0.6f) {
                const char* names[] = {"Neck1", "Chest", "Spine1", "Pelvis", "L_UpperArm", "R_UpperArm", "L_Forearm", "R_Forearm", "L_Hand", "R_Hand", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Foot", "R_Foot"};
                std::vector<std::pair<float, size_t>> w;
                for (size_t t2 = 0; t2 + 2 < ei.size(); t2 += 3) {
                    uint32_t a = ei[t2], b = ei[t2 + 1], c = ei[t2 + 2];
                    if (a >= eveVerts || b >= eveVerts || c >= eveVerts) continue;
                    float worst = 0;
                    for (auto [x, y] : {std::pair<uint32_t, uint32_t>{a, b}, {b, c}, {c, a}}) worst = std::max(worst, (eP[x] - eP[y]).length() - (eRest[x] - eRest[y]).length());
                    w.push_back({worst, t2});
                }
                std::sort(w.rbegin(), w.rend());
                for (size_t q = 0; q < 12 && q < w.size(); ++q) {
                    V3 c = (eRest[ei[w[q].second]] + eRest[ei[w[q].second + 1]] + eRest[ei[w[q].second + 2]]) * (1.0f / 3);
                    const char* best = "?"; float bd = 1e9f;
                    for (const char* nm : names) { int b = m.findWowBone(nm); if (b < 0) continue; float d = (m.bones[size_t(b)].pivot - c).length(); if (d < bd) { bd = d; best = nm; } }
                    float edge = 0; for (int k = 0; k < 3; ++k) edge = std::max(edge, (eRest[ei[w[q].second + k]] - eRest[ei[w[q].second + (k + 1) % 3]]).length());
                    std::printf("    stretch %.2f near %s (%.3f) rest edge %.4f at %.2f %.2f %.2f\n", w[q].first, best, bd, edge, c.x, c.y, c.z);
                }
            }
            if (pct(es, 0.999) - pct(ms, 0.999) * 1.5f > e999 - m999 * 1.5f || en == 0) {   // the moment hers is furthest past the model's
                e999 = pct(es, 0.999); m999 = pct(ms, 0.999); e99 = pct(es, 0.99); m99 = pct(ms, 0.99);
                eMax = es.empty() ? 0 : es.back(); mMax = ms.empty() ? 0 : ms.back();
            }
            if (c1 >= ec) { ec = c1; en = n1; mc = c2; mn = n2; }
        }
        // the rig against the model's own skeleton in the same pose: where the head, hands and feet end up (the scene places her by
        // them: furniture, partners); the worst moment
        if (!res.body.rigPivots.empty()) {
            float worst[5] = {0, 0, 0, 0, 0}, turn = 0;   // head, hands, feet, pelvis, hips; (turn: the most a limb segment points away from the model's, degrees)
            for (float f : {0.2f, 0.5f, 0.8f}) {
                uint32_t t = uint32_t(f * float(dur));
                std::vector<BonePose> bp; m.pose(seq, t, t, bp, false);
                if (getenv("EVE_REST")) bp.clear();
                std::vector<M4> em, mm;
                legEditMatrices(m.bones, bp, edit, em); m.bonesToMatrices(bp, mm);
                const char* segs[][2] = {{"L_UpperArm", "L_Forearm"}, {"L_Forearm", "L_Hand"}, {"R_UpperArm", "R_Forearm"}, {"R_Forearm", "R_Hand"},
                                         {"L_Thigh", "L_Calf"}, {"L_Calf", "L_Foot"}, {"R_Thigh", "R_Calf"}, {"R_Calf", "R_Foot"}};
                for (const auto& sg : segs) {
                    int a2 = m.findWowBone(sg[0]), b2 = m.findWowBone(sg[1]);
                    if (a2 < 0 || b2 < 0) continue;
                    // (each skeleton's joint where its own bind pose has it: the Eve rig's at its own pivots)
                    V3 de = em[size_t(b2)].point(edit.pivots[size_t(b2)]) - em[size_t(a2)].point(edit.pivots[size_t(a2)]);
                    V3 dm = mm[size_t(b2)].point(m.bones[size_t(b2)].pivot) - mm[size_t(a2)].point(m.bones[size_t(a2)].pivot);
                    float c = std::clamp(de.normalized().dot(dm.normalized()), -1.0f, 1.0f);
                    turn = std::max(turn, std::acos(c) * 57.2958f);
                    if (getenv("EVE_DIAG")) std::printf("    %s %.1f deg\n", sg[0], std::acos(c) * 57.2958f);
                }
                const char* ends[5][2] = {{"Head", ""}, {"L_Hand", "R_Hand"}, {"L_Foot", "R_Foot"}, {"Pelvis", ""}, {"L_Thigh", "R_Thigh"}};
                for (int k = 0; k < 5; ++k) for (const char* n : ends[k]) {
                    if (!n[0]) continue;
                    int b = m.findWowBone(n); if (b < 0) continue;
                    worst[k] = std::max(worst[k], (em[size_t(b)].point(edit.pivots[size_t(b)]) - mm[size_t(b)].point(m.bones[size_t(b)].pivot)).length());
                }
            }
            const float h = m.bones[size_t(m.findWowBone("Head"))].pivot.z;
            std::printf("  %s, %s: the Eve rig against the model's: limbs point within %.1f deg; head %.3f, hands %.3f, feet %.3f, pelvis %.3f, hips %.3f apart "
                        "(%.1f%% / %.1f%% / %.1f%% / %.1f%% / %.1f%% of the head's height)\n", name.c_str(), p.what, turn, worst[0], worst[1], worst[2], worst[3], worst[4],
                        100 * worst[0] / h, 100 * worst[1] / h, 100 * worst[2] / h, 100 * worst[3] / h, 100 * worst[4] / h);
            CHECK(turn < 3.0f, "%s, %s: a limb of the Eve rig points %.1f degrees off the model's", name.c_str(), p.what, turn);
            CHECK(worst[0] <= 0.12f * h && worst[2] <= 0.12f * h, "%s, %s: the head or the feet stray from the model's (%.3f, %.3f)", name.c_str(), p.what, worst[0], worst[2]);
            // with her limbs at the model's lengths and her spine bending at its joints (the table's defaults): the pelvis, the
            // hips, the feet and the head where the model's are (the scene places partners and furniture by them), the hands
            // within 6% of her height (her clavicles and shoulders are hers)
            if (fit.lengths >= 1 && fit.spine >= 1) {
                CHECK(worst[0] <= 0.01f * h && worst[2] <= 0.01f * h && worst[3] <= 0.01f * h && worst[4] <= 0.01f * h,
                      "%s, %s: head %.3f, feet %.3f, pelvis %.3f, hips %.3f off the model's", name.c_str(), p.what, worst[0], worst[2], worst[3], worst[4]);
                CHECK(worst[1] <= 0.06f * h, "%s, %s: the hands %.3f off the model's", name.c_str(), p.what, worst[1]);
            }
        }
        std::printf("  %s, %s: seam gap %.5f, strip's edges grown %.4f (the model's cut edge %.4f), edges grown p99 %.3f p99.9 %.3f max %.3f (the model's %.3f %.3f %.3f), "
                    "collapsed %zu/%zu (the model's %zu/%zu)\n", name.c_str(), p.what, gap, strip, modelEdge, e99, e999, eMax, m99, m999, mMax, ec, en, mc, mn);
        CHECK(gap < 1e-4f, "%s, %s: a seam opens %.5f", name.c_str(), p.what, gap);
        CHECK(strip <= std::max(0.03f, 1.5f * modelEdge), "%s, %s: the seam's strip grew %.4f (the model's edge there %.4f)", name.c_str(), p.what, strip, modelEdge);
        CHECK(e999 <= m999 * 1.5f + 0.01f, "%s, %s: her triangles' edges grow %.3f, the model's %.3f", name.c_str(), p.what, e999, m999);
        CHECK(ec * 500 <= en, "%s, %s: %zu of her %zu triangles collapse", name.c_str(), p.what, ec, en);
    }
}


// The ankle seam measured (README, "Her own rig", the ankles): per side, each of her vertices 0.2 to 2 cm over the model's
// cut edge (at its angle round the model's ankle joint; the edge slants over the foot's top) against the model's own skin
// round the ankle as it was (the lower leg her cut took and the foot it kept, the look's shown geosets), at rest on her rig:
// its distance from that skin along the skin's normal at the nearest point is the step the eye sees where her shin meets
// the foot (cm at her human-scale height, 1.709 yards = 156 cm), and the angle between her normal and the skin's there the
// fold of shading. `step`, `turn`: the worst of both sides; `mean`: the larger side's mean |step|; `taper`: the step 2.5 to
// 5 cm up (her shin eased from the model's back to her own over the band).
struct AnkleStep { float step = 0, mean = 0, turn = 0, taper = 0; int samples = 0; };

// The texture at the ankle seams as the GPU samples it (README, "Her own rig", the ankles): the ankle rings lie on the edge
// of her UV island and the seam strip's UVs on it, so a bilinear or mipmapped lookup there takes texels outside the island
// too. Per mip level (eveMips, as the scene uploads them; level 0 bilinear at the texel centres as GL samples it), the
// mean and worst CIE ΔE of the composed texture at the strip's and the rings' UVs against the seam's own colour
// (EveResult::ankleRgb, the band's colour at the ring). EVE_TEX_DUMP=DIR writes the texture (DIR/NAME.ppm) and those UVs.
struct SeamBleed { float mean[4] = {0, 0, 0, 0}, worst[4] = {0, 0, 0, 0}; int samples = 0, open = 0, gaps = 0; float openLen = 0, gapLen = 0; };
SeamBleed seamBleed(const M2Model& m, const EveResult& res, const char* name, const std::vector<uint16_t>& geosets) {
    SeamBleed out;
    const EveBody& b = res.body;
    if (res.rgba.empty() || b.verts.empty()) return out;
    float top = -1e30f;
    for (const M2Vertex& v : b.verts) top = std::max(top, v.pos[2]);
    // the strip's triangles at the ankles (a corner past her own vertices: the model's edge), every corner's UV
    std::vector<std::pair<float, float>> uvs;
    for (size_t t = 0; t + 2 < b.indices.size(); t += 3) {
        uint32_t i[3] = {b.indices[t], b.indices[t + 1], b.indices[t + 2]};
        if (std::max({i[0], i[1], i[2]}) < b.stats.eveVerts) continue;
        if (b.verts[i[0]].pos[2] > 0.25f * top) continue;   // (the neck's strip is high)
        for (uint32_t k : i) uvs.push_back({b.verts[k].uv[0], b.verts[k].uv[1]});
    }
    if (uvs.empty()) return out;
    std::vector<std::vector<uint8_t>> levels = eveMips(res.rgba, res.w, res.h);
    levels.insert(levels.begin(), res.rgba);
    const V3 want = eveLab(res.ankleRgb);
    for (int L = 0; L < 4 && L < int(levels.size()); ++L) {
        int w = std::max(1, res.w >> L), h = std::max(1, res.h >> L);
        const std::vector<uint8_t>& img = levels[size_t(L)];
        double sum = 0; float worst = 0;
        for (auto [u, v] : uvs) {
            float x = u * w - 0.5f, y = v * h - 0.5f;
            int x0 = int(std::floor(x)), y0 = int(std::floor(y));
            float fx = x - x0, fy = y - y0;
            V3 c;
            for (int dy = 0; dy < 2; ++dy) for (int dx = 0; dx < 2; ++dx) {
                int xx = std::clamp(x0 + dx, 0, w - 1), yy = std::clamp(y0 + dy, 0, h - 1);
                const uint8_t* q = &img[(size_t(yy) * w + xx) * 4];
                float wt = (dx ? fx : 1 - fx) * (dy ? fy : 1 - fy);
                c = c + eveLinear((uint32_t(q[0]) << 16) | (uint32_t(q[1]) << 8) | q[2]) * wt;
            }
            float de = (eveLab(eveRgb(c)) - want).length();
            sum += de; worst = std::max(worst, de);
        }
        out.mean[L] = float(sum / uvs.size()); out.worst[L] = worst;
    }
    out.samples = int(uvs.size());
    // open edges at the ankles: an edge of one triangle only, with a corner of hers, is a crack between her ring and the strip
    {
        // (welded by place: her UV copies and the strip's copies of the model's vertices are one point each)
        std::map<std::array<long, 3>, uint32_t> weld;
        std::vector<uint32_t> id(b.verts.size());
        for (size_t v = 0; v < b.verts.size(); ++v) {
            std::array<long, 3> k{std::lround(b.verts[v].pos[0] * 1e5), std::lround(b.verts[v].pos[1] * 1e5), std::lround(b.verts[v].pos[2] * 1e5)};
            id[v] = weld.emplace(k, uint32_t(v)).first->second;
        }
        std::map<std::pair<uint32_t, uint32_t>, int> uses;
        for (size_t t = 0; t + 2 < b.indices.size(); t += 3)
            for (int k = 0; k < 3; ++k) {
                uint32_t x = id[b.indices[t + k]], y = id[b.indices[t + (k + 1) % 3]];
                if (x != y) uses[{std::min(x, y), std::max(x, y)}]++;
            }
        int open = 0; float worst = 0;
        for (auto& [e, n] : uses) {
            if (n != 1 || std::min(e.first, e.second) >= b.stats.eveVerts) continue;
            if (b.verts[e.first].pos[2] > 0.25f * top) continue;
            ++open;
            const float* p0 = b.verts[e.first].pos; const float* p1 = b.verts[e.second].pos;
            worst = std::max(worst, (V3(p0[0], p0[1], p0[2]) - V3(p1[0], p1[1], p1[2])).length());
        }
        out.open = open; out.openLen = worst;
    }
    // the model's own shown skin, cut: each edge of its cut edge at the ankles (an edge of one of its shown triangles only)
    // that the strip's bottom doesn't run along is a gap between the strip and the model's skin (seen as a hairline)
    {
        auto key = [](const float* p) { return std::array<long, 3>{std::lround(p[0] * 1e4), std::lround(p[1] * 1e4), std::lround(p[2] * 1e4)}; };
        std::map<std::array<long, 3>, uint32_t> weld;
        auto idOf = [&](const float* p) { return weld.emplace(key(p), uint32_t(weld.size())).first->second; };
        std::map<std::pair<uint32_t, uint32_t>, int> uses;
        std::map<std::pair<uint32_t, uint32_t>, float> len;
        for (const M2Submesh& sm : res.cutSubs) {
            if (std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
            for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount && t + 2 < res.cutIndices.size(); t += 3)
                for (int k = 0; k < 3; ++k) {
                    const float* p0 = m.vertices[res.cutIndices[t + k]].pos; const float* p1 = m.vertices[res.cutIndices[t + (k + 1) % 3]].pos;
                    if (p0[2] > 0.25f * top) continue;
                    uint32_t x = idOf(p0), y = idOf(p1);
                    if (x == y) continue;
                    std::pair<uint32_t, uint32_t> e{std::min(x, y), std::max(x, y)};
                    uses[e]++;
                    len[e] = (V3(p0[0], p0[1], p0[2]) - V3(p1[0], p1[1], p1[2])).length();
                }
        }
        std::map<std::pair<uint32_t, uint32_t>, int> strip;
        for (size_t t = 0; t + 2 < b.indices.size(); t += 3)
            for (int k = 0; k < 3; ++k) {
                uint32_t x = b.indices[t + k], y = b.indices[t + (k + 1) % 3];
                if (x < b.stats.eveVerts || y < b.stats.eveVerts) continue;
                uint32_t a = idOf(b.verts[x].pos), c = idOf(b.verts[y].pos);
                if (a != c) strip[{std::min(a, c), std::max(a, c)}]++;
            }
        for (auto& [e, n] : uses) {
            if (n != 1 || strip.count(e)) continue;
            // (the model's other open edges: those far from the strip aren't the seam; only edges with a corner on the strip)
            bool near = false;
            for (auto& [f, k] : strip) if (f.first == e.first || f.second == e.first || f.first == e.second || f.second == e.second) { near = true; break; }
            if (!near) continue;
            ++out.gaps; out.gapLen = std::max(out.gapLen, len[e]);
        }
    }
    if (const char* dir = std::getenv("EVE_TEX_DUMP")) {
        std::string base = std::string(dir) + "/" + name;
        for (char& ch : base) if (ch == ' ' || ch == '\'') ch = '_';
        if (FILE* f = std::fopen((base + ".ppm").c_str(), "wb")) {
            std::fprintf(f, "P6 %d %d 255\n", res.w, res.h);
            for (size_t i = 0; i < size_t(res.w) * res.h; ++i) std::fwrite(&res.rgba[i * 4], 1, 3, f);
            std::fclose(f);
        }
        if (FILE* f = std::fopen((base + ".uv").c_str(), "w")) {
            for (auto [u, v] : uvs) std::fprintf(f, "%.5f %.5f\n", u, v);
            std::fclose(f);
        }
    }
    return out;
}

// A look's shown geosets from its groups, as the scene shows them (scene.cpp characterGeosets).
std::vector<uint16_t> shownGeosets(const M2Model& m, const std::map<int, int>& groups) {
    std::vector<uint16_t> out;
    for (const M2Submesh& sm : m.submeshes) {
        int id = sm.id, group = id < 100 ? 0 : id / 100, value = id < 100 ? id : id % 100;
        bool show = id == 0 || (id >= 100 && value == 1) || group == 32;
        if (group == 17 || group == 35) show = false;
        auto it = groups.find(group);
        if (id != 0 && it != groups.end()) show = value == it->second;
        if (show && std::find(out.begin(), out.end(), sm.id) == out.end()) out.push_back(sm.id);
    }
    return out;
}

AnkleStep ankleStep(const M2Model& m, const EveResult& res, bool print, const char* name, const std::vector<uint16_t>& geosets) {
    AnkleStep out;
    const std::vector<M2Vertex>& ev = res.body.verts;
    const size_t eveVerts = res.body.stats.eveVerts;
    const float H = res.body.stats.height > 0 ? res.body.stats.height : 2.0f;
    const float cm = 156.25f / H;   // cm per model unit
    std::vector<uint8_t> skin(m.submeshes.size(), 0);
    for (const M2Batch& bt : m.batches) {
        if (bt.submesh >= m.submeshes.size() || bt.textureCombo >= m.textureCombos.size()) continue;
        uint16_t ti = m.textureCombos[bt.textureCombo];
        const uint16_t id = m.submeshes[bt.submesh].id;
        if (ti < m.textures.size() && m.textures[ti].type == 1 &&
            (geosets.empty() ? id % 100 <= 1 : std::find(geosets.begin(), geosets.end(), id) != geosets.end())) skin[bt.submesh] = 1;
    }
    // as drawn at rest: both skinned on her rig's bind (bodyrig.h; posedSeams' bindMats)
    LegEdit edit = res.body.rigPivots.empty() ? bodyEditOf(m, res.body.scale) : bodyEditFrom(m, res.body.rigPivots, res.body.scale);
    std::vector<M4> bindMats;
    if (edit.active) legEditMatrices(m.bones, std::vector<BonePose>(), edit, bindMats); else bindMats.assign(m.bones.size(), M4());
    auto skinned = [&](const M2Vertex& v, bool normal) {
        V3 p = normal ? V3(v.normal[0], v.normal[1], v.normal[2]) : V3(v.pos[0], v.pos[1], v.pos[2]), q; float w = 0;
        for (int k = 0; k < 4; ++k) if (v.weights[k] && v.bones[k] < bindMats.size()) {
            const M4& M = bindMats[v.bones[k]];
            q = q + (normal ? M.point(p) - M.point(V3(0, 0, 0)) : M.point(p)) * (v.weights[k] / 255.0f); w += v.weights[k] / 255.0f;
        }
        return w > 0 ? (normal ? q.normalized() : q * (1.0f / w)) : p;
    };
    struct Tri { V3 a, b, c, na, nb, nc; };
    for (int sd = 0; sd < 2; ++sd) {
        int fb = m.findWowBone(sd ? "R_Foot" : "L_Foot");
        if (fb < 0) continue;
        const V3 o = bindMats[size_t(fb)].point(m.bones[size_t(fb)].pivot);
        auto mine = [&](V3 p) { return p.z < 0.25f * H && (sd ? p.y < 0 : p.y > 0) && V3(p.x - o.x, p.y - o.y, 0).length() < 0.1f * H; };
        // the model's cut edge on this leg: the stitch's model vertices (exact copies of its edge), their height by angle
        std::vector<std::pair<float, float>> edge;
        for (size_t v = eveVerts; v < ev.size(); ++v) { V3 p = skinned(ev[v], false); if (mine(p)) edge.push_back({std::atan2(p.y - o.y, p.x - o.x), p.z}); }
        if (edge.size() < 3) continue;
        auto edgeAt = [&](float a) {   // the edge's height at angle a: inverse-distance over the nearest edge vertices by angle
            float wsum = 0, z = 0;
            for (auto& [ea, ez] : edge) { float d = std::fabs(std::remainder(ea - a, 6.2831853f)); float w = 1.0f / (d * d + 1e-4f); wsum += w; z += ez * w; }
            return z / wsum;
        };
        // the model's own skin round the ankle as it was (the removed lower leg and the kept foot: what her shin replaces and meets)
        std::vector<Tri> model;
        for (size_t si = 0; si < m.submeshes.size(); ++si) if (skin[si])
            for (uint32_t t = m.submeshes[si].indexStart; t + 2 < m.submeshes[si].indexStart + m.submeshes[si].indexCount && t + 2 < m.indices.size(); t += 3) {
                uint32_t i0 = m.indices[t], i1 = m.indices[t + 1], i2 = m.indices[t + 2];
                if (i0 >= res.body.cut.size() || i1 >= res.body.cut.size() || i2 >= res.body.cut.size()) continue;
                V3 A = skinned(m.vertices[i0], false), B = skinned(m.vertices[i1], false), C = skinned(m.vertices[i2], false);
                if (!mine((A + B + C) * (1.0f / 3))) continue;
                model.push_back({A, B, C, skinned(m.vertices[i0], true), skinned(m.vertices[i1], true), skinned(m.vertices[i2], true)});
            }
        // the nearest point of the model's skin to p and the skin's normal there
        auto nearest = [&](const std::vector<Tri>& ts, V3 p, V3& at, V3& nn) {
            float best = 1e30f; bool any = false;
            for (const Tri& t : ts) {
                // (the nearest point of a triangle: sampled finely enough for a check, 1 mm on these)
                V3 e1 = t.b - t.a, e2 = t.c - t.a, nrm = e1.cross(e2);
                float area = nrm.length(); if (area < 1e-12f) continue;
                nrm = nrm * (1.0f / area);
                V3 q = p - nrm * (p - t.a).dot(nrm);
                // barycentric of the projection; outside: the nearest of the edges
                V3 v2 = q - t.a; float d00 = e1.dot(e1), d01 = e1.dot(e2), d11 = e2.dot(e2), d20 = v2.dot(e1), d21 = v2.dot(e2);
                float den = d00 * d11 - d01 * d01; if (std::fabs(den) < 1e-20f) continue;
                float u = (d11 * d20 - d01 * d21) / den, w = (d00 * d21 - d01 * d20) / den;
                if (u < 0 || w < 0 || u + w > 1) {
                    float bu = 0, bw = 0, bd = 1e30f;
                    for (int k = 0; k <= 20; ++k) for (int e = 0; e < 3; ++e) {
                        float f = k / 20.0f, uu = e == 0 ? f : e == 1 ? 1 - f : 0, ww = e == 0 ? 0 : e == 1 ? f : f;
                        V3 x = t.a + e1 * uu + e2 * ww; float d = (x - p).dot(x - p);
                        if (d < bd) { bd = d; bu = uu; bw = ww; }
                    }
                    u = bu; w = bw; q = t.a + e1 * u + e2 * w;
                }
                float d = (q - p).dot(q - p);
                if (d < best) { best = d; at = q; nn = (t.na * (1 - u - w) + t.nb * u + t.nc * w).normalized(); any = true; }
            }
            return any;
        };
        float worst = 0, wsgn = 0, wh = 0, wa = 0, sum = 0, turn = 0, taper = 0; int n = 0;
        for (size_t v = 0; v < eveVerts; ++v) {
            V3 p = skinned(ev[v], false);
            if (!mine(p)) continue;
            float a = std::atan2(p.y - o.y, p.x - o.x), h = (p.z - edgeAt(a)) * cm;
            if (h < 0.2f || h > 5.0f) continue;
            V3 at, nn;
            if (!nearest(model, p, at, nn) || (at - p).length() > 0.05f * H) continue;
            float st = (p - at).dot(nn) * cm;
            if (h > 2.5f) { taper = std::max(taper, std::fabs(st)); continue; }
            sum += std::fabs(st); ++n;
            if (std::fabs(st) > worst) { worst = std::fabs(st); wsgn = st; wh = h; wa = a * 57.2958f; }
            turn = std::max(turn, std::acos(std::clamp(skinned(ev[v], true).dot(nn), -1.0f, 1.0f)) * 57.2958f);
        }
        if (print) std::printf("  %s ankle %s: step %.2f cm (%+.2f at %.1f cm up, %.0f deg), mean %.2f cm over %d of her vertices 0.2-2 cm up; her normals within %.0f deg "
                               "of the model's skin; 2.5-5 cm up %.2f cm\n", name, sd ? "R" : "L", worst, wsgn, wh, wa, n ? sum / n : 0.0f, n, turn, taper);
        out.step = std::max(out.step, worst); out.turn = std::max(out.turn, turn); out.taper = std::max(out.taper, taper);
        out.mean = std::max(out.mean, n ? sum / n : 0.0f); out.samples += n;
    }
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
            CHECK(body.verts[e.vertexCount() - 1].bones[0] == kChest, "breast to chest: %d", body.verts[e.vertexCount() - 1].bones[0]);
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
    // ---- Keep undead bones (eveDetailMask, eveDetailLayer): a flat "model" body of skin with a red wound painted on it and a
    // tan bone piece (an island of its own), Eve's square 1 cm in front of it on a texture of her own
    {
        auto vtx = [](float x, float y, float z, float u, float v) {
            M2Vertex m{}; m.pos[0] = x; m.pos[1] = y; m.pos[2] = z; m.normal[0] = 1; m.uv[0] = u; m.uv[1] = v; m.weights[0] = 255; return m;
        };
        // the model: its skin a quad over (0,0)..(1,1) of its texture, on the plane x = 0 facing +x; the piece a small quad of
        // its own (separate vertices) mapped to the texture's corner (0.875..1, 0..0.125), standing in front of the skin
        std::vector<M2Vertex> wow = {vtx(0, 0, 0, 0, 0.25f), vtx(0, 1, 0, 0.75f, 0.25f), vtx(0, 1, 1, 0.75f, 1), vtx(0, 0, 1, 0, 1),
                                     vtx(0.004f, 0.7f, 0.7f, 0.875f, 0), vtx(0.004f, 0.95f, 0.7f, 1, 0), vtx(0.004f, 0.95f, 0.95f, 1, 0.125f),
                                     vtx(0.004f, 0.7f, 0.95f, 0.875f, 0.125f)};
        std::vector<uint32_t> tris = {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7};
        const int sw = 64, sh = 64;
        std::vector<uint8_t> skin(size_t(sw) * sh * 4);
        for (int y = 0; y < sh; ++y) for (int x = 0; x < sw; ++x) {
            uint8_t* p = &skin[(size_t(y) * sw + x) * 4];
            uint32_t c = 0xACB3A5;                                               // the Forsaken's pale tone
            if (x >= 16 && x < 24 && y >= 32 && y < 40) c = 0x8A1E3C;            // a wound (u 0.25..0.375, v 0.5..0.625)
            if (x >= 56 && y < 8) c = (x + y) % 3 ? 0xB89A6A : 0xD8D0C0;         // the bone piece: tan with pale highlights
            if (x >= 8 && x < 10 && y >= 50 && y < 52) c = 0xA4AC9E;             // a shading shift: no detail
            p[0] = uint8_t(c >> 16); p[1] = uint8_t(c >> 8); p[2] = uint8_t(c); p[3] = 255;
        }
        EveDetailMask dm = eveDetailMask(wow, tris, skin.data(), sw, sh);
        CHECK(!dm.empty() && dm.tone == 0xACB3A5u, "the skin's tone found: %06X", dm.tone);
        CHECK(dm.pieces == 1 && dm.piece[4] && dm.piece[7] && !dm.piece[0] && !dm.piece[3], "the bone piece found by its island (%zu)", dm.pieces);
        CHECK(dm.a[size_t(36) * sw + 20] == 255 && dm.a[size_t(20) * sw + 30] == 0 && dm.a[size_t(50) * sw + 8] == 0,
              "the wound is detail, the skin and its shading are not (%d %d %d)", dm.a[size_t(36) * sw + 20], dm.a[size_t(20) * sw + 30], dm.a[size_t(50) * sw + 8]);
        CHECK(dm.a[size_t(1) * sw + 60] == 255 && dm.a[size_t(0) * sw + 56] == 255, "all of the piece is detail, its highlights too");
        // Eve: one quad 1 cm in front (x = 0.01), on the left half of her 40 x 40 texture (u 0.1..0.5, v 0.1..0.9)
        EveBody eb;
        eb.verts = {vtx(0.01f, 0, 0, 0.1f, 0.1f), vtx(0.01f, 1, 0, 0.5f, 0.1f), vtx(0.01f, 1, 1, 0.5f, 0.9f), vtx(0.01f, 0, 1, 0.1f, 0.9f)};
        eb.indices = {0, 1, 2, 0, 2, 3};
        const float tint[3] = {1, 1, 1};
        const int w = 40, h = 40;
        EveDetailLayer L1 = eveDetailLayer(eb, w, h, wow, tris, skin.data(), sw, sh, dm, tint, 0.1f);
        EveDetailLayer L2 = eveDetailLayer(eb, w, h, wow, tris, skin.data(), sw, sh, dm, tint, 0.1f);
        CHECK(!L1.empty() && L1.rgba == L2.rgba && L1.covered == L2.covered, "the transfer is deterministic (%zu covered)", L1.covered);
        // (the LUT's round trip: a byte may come back one off)
        auto near1 = [](int a, uint32_t want) { return std::abs(a - int(want)) <= 1; };
        // the wound's place on the model (y and z 0.333..0.5) is Eve's u 0.233..0.3, v 0.367..0.5
        auto at = [&](float u, float v) { return &L1.rgba[(size_t(v * h) * w + size_t(u * w)) * 4]; };
        const uint8_t* wound = at(0.255f, 0.43f);
        CHECK(wound[3] > 200 && near1(wound[0], 0x8A) && near1(wound[1], 0x1E) && near1(wound[2], 0x3C), "the wound on Eve, its own colour, no blur (%02X%02X%02X a %d)",
              wound[0], wound[1], wound[2], wound[3]);
        CHECK(at(0.2f, 0.75f)[3] == 0, "her skin elsewhere untouched by the layer");
        const uint8_t* bone = at(0.44f, 0.76f);   // (the piece over y, z 0.7..0.95: Eve's u 0.38..0.48, v 0.66..0.86)
        CHECK(bone[3] > 200 && (near1(bone[0], 0xB8) || near1(bone[0], 0xD8)), "the bone piece on Eve (%02X%02X%02X a %d)", bone[0], bone[1], bone[2], bone[3]);
        // the gap fill: her island is texels 4..19 of a row; the layer grown into 1..3 and 20..22 (her colour there, so no mip
        // level reads bare black past her edge), nothing beyond
        int y = int(0.43f * h);
        bool grownOk = L1.grown > 0;
        for (int x = 0; x < w; ++x) {
            const uint8_t* q = &L1.rgba[(size_t(y) * w + x) * 4];
            bool inside = x >= 4 && x < 20, near = (x >= 1 && x < 4) || (x >= 20 && x < 23);
            if (near) grownOk &= q[0] > 0 && q[1] > 0;
            else if (!inside) grownOk &= q[0] == 0 && q[3] == 0;
        }
        CHECK(grownOk, "grown 3 texels past her island, nothing beyond (%zu grown)", L1.grown);
        // layer off: eveCompose with none, or with a layer that covers nothing, gives the same bytes as before
        {
            std::vector<uint8_t> base(size_t(w) * h * 4, 150), a, b, c;
            for (size_t i = 3; i < base.size(); i += 4) base[i] = 255;
            EvePrep prep; prep.w = w; prep.h = h;
            eveCompose(base, w, h, V3(1.1f, 0.9f, 1.0f), nullptr, 0, 0, 0, 0, 0, V3(1, 1, 1), prep, 0, 0, a);
            eveCompose(base, w, h, V3(1.1f, 0.9f, 1.0f), nullptr, 0, 0, 0, 0, 0, V3(1, 1, 1), prep, 0, 0, b, nullptr, nullptr);
            EveDetailLayer none = L1;
            for (size_t i = 3; i < none.rgba.size(); i += 4) none.rgba[i] = 0;
            eveCompose(base, w, h, V3(1.1f, 0.9f, 1.0f), nullptr, 0, 0, 0, 0, 0, V3(1, 1, 1), prep, 0, 0, c, nullptr, &none);
            CHECK(a == b && a == c, "the layer off (or covering nothing): the same bytes");
            std::vector<uint8_t> d;
            eveCompose(base, w, h, V3(1.1f, 0.9f, 1.0f), nullptr, 0, 0, 0, 0, 0, V3(1, 1, 1), prep, 0, 0, d, nullptr, &L1);
            size_t wi = (size_t(int(0.43f * h)) * w + size_t(0.255f * w)) * 4;
            CHECK(d != a && std::abs(int(d[wi]) - 0x8A) <= 2 && std::abs(int(d[wi + 1]) - 0x1E) <= 2, "the layer on: the wound composed in (%02X%02X%02X)",
                  d[wi], d[wi + 1], d[wi + 2]);
        }
        // the clean: the wound and the piece eased to the tone (the colour transfer reads that), the skin as it was
        std::vector<uint8_t> cl = skin;
        eveDetailClean(cl.data(), sw, sh, dm);
        auto rgbOf = [&](const std::vector<uint8_t>& px, int x, int yy) { const uint8_t* q = &px[(size_t(yy) * sw + x) * 4]; return uint32_t(q[0]) << 16 | uint32_t(q[1]) << 8 | q[2]; };
        auto nearRgb = [&](uint32_t a, uint32_t b) { return near1(int(a >> 16), b >> 16) && near1(int((a >> 8) & 255), (b >> 8) & 255) && near1(int(a & 255), b & 255); };
        CHECK(nearRgb(rgbOf(cl, 20, 36), 0xACB3A5) && nearRgb(rgbOf(cl, 60, 2), 0xACB3A5) && rgbOf(cl, 30, 20) == 0xACB3A5u && rgbOf(cl, 8, 50) == 0xA4AC9Eu,
              "the clean: %06X %06X", rgbOf(cl, 20, 36), rgbOf(cl, 60, 2));
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
            // MODEL[:SKIN[:FIT_JSON]]: with its base skin's file, the skin's colour across the seams is checked too
            std::string arg = argv[i];
            uint32_t skinId = 0;
            std::string fitPath;
            std::map<int, int> groupsArg;
            {
                size_t c1 = arg.find(':');
                if (c1 != std::string::npos) {
                    size_t c2 = arg.find(':', c1 + 1);
                    skinId = uint32_t(std::strtoul(arg.substr(c1 + 1, c2 == std::string::npos ? std::string::npos : c2 - c1 - 1).c_str(), nullptr, 10));
                    if (c2 != std::string::npos) {
                        // MODEL:SKIN:FIT_JSON[:GEO]: GEO a look's geoset groups ("0=2,4=1,..."), shown as the scene shows them
                        size_t c3 = arg.find(':', c2 + 1);
                        fitPath = arg.substr(c2 + 1, c3 == std::string::npos ? std::string::npos : c3 - c2 - 1);
                        if (c3 != std::string::npos) {
                            std::string g = arg.substr(c3 + 1);
                            size_t at = 0;
                            while (at < g.size()) {
                                size_t comma = g.find(',', at), eq = g.find('=', at);
                                if (eq == std::string::npos || (comma != std::string::npos && eq > comma)) break;
                                groupsArg[std::atoi(g.substr(at, eq - at).c_str())] = std::atoi(g.substr(eq + 1, comma == std::string::npos ? std::string::npos : comma - eq - 1).c_str());
                                if (comma == std::string::npos) break;
                                at = comma + 1;
                            }
                        }
                    }
                }
            }
            if (!m.load(uint32_t(std::atoi(argv[i])), look)) { std::printf("model %s: %s\n", argv[i], m.error.c_str()); continue; }
            // the real fit table's row (the app's defaults under it): height kept, seams stitched shut, the colour across them
            if (!fitPath.empty() && skinId) {
                Bytes fj; readFile(fitPath, fj);
                EveFitTable table; std::string ferr;
                CHECK(parseEveFitTable(std::string(fj.begin(), fj.end()), table, ferr), "fit table: %s", ferr.c_str());
                const EveRaceFit* row = table.forModel(m.fileId);
                if (row && row->enabled) {
                    auto set = std::make_shared<EveSet>();
                    set->dir = dir; set->index = ix;
                    EveJob j;
                    j.serial = 1; j.key = 1; j.model = &m; j.set = set; j.mesh = std::make_shared<EveMesh>(e); j.fit = *row;
                    j.indices = m.indices; j.subs = m.submeshes;
                    if (!groupsArg.empty()) j.geosets = shownGeosets(m, groupsArg);
                    j.skinPath = look(skinId);
                    EveResult res = eveCompute(j);
                    CHECK(res.ok, "model %u: %s", m.fileId, res.status.c_str());
                    if (res.ok) {
                        const EveFitStats& st = res.body.stats;
                        float heads = st.head > 0 ? st.heightAfter / st.head : 0;
                        std::printf("  %s: height %.3f -> %.3f (%.2f heads), seams ΔE neck %.1f ankles %.1f/%.1f, stitched %d\n", row->name.c_str(), st.height,
                                    st.heightAfter, heads, res.seamDE[0], res.seamDE[1], res.seamDE[2], st.stitched);
                        CHECK(std::fabs(st.heightAfter - st.height) <= 0.02f * st.height, "%s: she stands as tall as the model (%.3f / %.3f)", row->name.c_str(),
                              st.heightAfter, st.height);
                        // (every geoset shown here: a model whose extra skin geosets hide its head isn't measured)
                        CHECK(st.head > 0.2f * st.height || (heads >= 7.0f && heads <= 9.2f), "%s: %.2f head heights", row->name.c_str(), heads);
                        for (int k = 0; k < 3; ++k)
                            CHECK(res.seamDE[k] >= 0 && res.seamDE[k] < 3.0f, "%s: seam %d's colour ΔE %.1f under 3", row->name.c_str(), k, res.seamDE[k]);
                        CHECK(st.stitched == 3, "%s: three seams stitched (%d)", row->name.c_str(), st.stitched);
                        // her own rig: the head's and the feet's chains stay the model's (its head, hair and feet ride their own joints)
                        if (row->rig > 0) {
                            bool keep = res.body.rigPivots.size() == m.bones.size();
                            for (const char* n : {"Neck1", "Head", "L_Foot", "R_Foot", "L_Toe", "R_Toe"}) {
                                int b = m.findWowBone(n);
                                if (keep && b >= 0) keep = (res.body.rigPivots[size_t(b)] - m.bones[size_t(b)].pivot).length() < 1e-6f;
                            }
                            int ua = m.findWowBone("L_UpperArm");
                            CHECK(keep && ua >= 0 && (res.body.rigPivots[size_t(ua)] - m.bones[size_t(ua)].pivot).length() > 1e-4f,
                                  "%s: her rig moves her body's joints and keeps the head's and feet's", row->name.c_str());
                        }
                        // the stitch: every vertex added past Eve's own sits exactly on a model vertex with its weights (no gap in any pose)
                        size_t added = 0, off = 0;
                        for (size_t v = res.body.stats.eveVerts; v < res.body.verts.size(); ++v) {
                            ++added;
                            const M2Vertex& x = res.body.verts[v];
                            bool on = false;
                            for (const M2Vertex& w : m.vertices) {
                                if (std::fabs(w.pos[0] - x.pos[0]) > 1e-6f || std::fabs(w.pos[1] - x.pos[1]) > 1e-6f || std::fabs(w.pos[2] - x.pos[2]) > 1e-6f) continue;
                                int same = 0;
                                for (int a = 0; a < 4; ++a) for (int b = 0; b < 4; ++b)
                                    if (w.bones[a] == x.bones[b] && w.weights[a] && std::abs(int(w.weights[a]) - int(x.weights[b])) <= 2) ++same;
                                int nw = 0; for (int a = 0; a < 4; ++a) nw += w.weights[a] ? 1 : 0;
                                if (same >= nw) { on = true; break; }
                            }
                            if (!on) ++off;
                        }
                        CHECK(added > 0 && off == 0, "%s: %zu stitch vertices, %zu off the model's edge", row->name.c_str(), added, off);
                        posedSeams(m, look, res, res.body.stats.eveVerts, row->name, *row);
                        // her flesh as the scene measures it (Scene::actorFlesh, flesh.h FleshGuide: her skin by area, her trunk and thighs by
                        // the model's own segments where she is) against the model's own body's: a race whose build she keeps (no `bulk`) reads
                        // within 0.035 body units of it at the pelvis, the spine's front and the chest's front (read by her weights alone her
                        // spine's front came 0.05 deeper and her pelvis 0.04 narrower than the human woman's); the hip joints the model's
                        if (row->rig > 0 && !j.geosets.empty()) {
                            const LegEdit legs = bodyEditFrom(m, res.body.rigPivots, res.body.scale);
                            FleshGuide guide;
                            guide.byArea = true;
                            guide.from = res.measure.verts.size() - res.body.verts.size();
                            guide.idx = &m.indices; guide.subs = &m.submeshes;
                            FleshMeasure fe = measureFlesh(m, res.measure.verts, res.measure.indices, res.measure.subs, j.geosets, nullptr, legs.active ? &legs : nullptr, &guide);
                            FleshMeasure fm = measureFlesh(m, m.vertices, m.indices, m.submeshes, j.geosets);
                            if (fe.ok && fm.ok) {
                                const FleshSeg &ep = fe.seg[kFleshPelvis], &mp = fm.seg[kFleshPelvis], &es = fe.seg[kFleshSpine], &ms = fm.seg[kFleshSpine];
                                const FleshSeg &ec = fe.seg[kFleshChest], &mc = fm.seg[kFleshChest];
                                std::printf("  %s: her flesh (body units) pelvis front/back/side %.3f/%.3f/%.3f (the model's %.3f/%.3f/%.3f), spine front %.3f (%.3f), "
                                            "chest front %.3f (%.3f); hips %.3f (%.3f)\n", row->name.c_str(), ep.front, ep.back, ep.side, mp.front, mp.back, mp.side,
                                            es.front, ms.front, ec.front, mc.front, fe.rest[15].z, fm.rest[15].z);   // (15: L_Thigh)
                                const EveBulk& bk = row->bulk;
                                const bool keepsHers = bk.arm == 1 && bk.forearm == 1 && bk.thigh == 1 && bk.calf == 1 && bk.shoulders == 1 && bk.chest == 1 &&
                                                       bk.waist == 1 && bk.hips == 1;
                                if (keepsHers)
                                    CHECK(std::fabs(ep.side - mp.side) <= 0.035f && std::fabs(ep.back - mp.back) <= 0.035f && std::fabs(es.front - ms.front) <= 0.035f &&
                                          std::fabs(ec.front - mc.front) <= 0.035f, "%s: her flesh as measured strays from the model's own body", row->name.c_str());
                            }
                            CHECK(fe.ok && fm.ok, "%s: her flesh and the model's measured", row->name.c_str());
                        }
                        // the ankles: her shin meets the model's foot as its own leg did (no cuff, no fold of shading), on the look's own
                        // geosets (MODEL:SKIN:FIT:GEO; without them every geoset's edges are cut and the measure means nothing)
                        if (row->rig > 0 && row->ankleMatch > 0 && !j.geosets.empty()) {
                            AnkleStep as = ankleStep(m, res, true, row->name.c_str(), j.geosets);
                            CHECK(as.samples > 20 && as.step <= 0.3f, "%s: the ankle's step %.2f cm (at most 0.3 over %d vertices)", row->name.c_str(), as.step, as.samples);
                            CHECK(as.turn <= 5.0f, "%s: her normals at the ankle %.1f degrees off the model's skin's (at most 5)", row->name.c_str(), as.turn);
                            SeamBleed sb = seamBleed(m, res, row->name.c_str(), j.geosets);
                            std::printf("  %s: the ankle seams' texture as sampled (ΔE against the seam's colour, mean/worst) mip 0 %.1f/%.1f, 1 %.1f/%.1f, "
                                        "2 %.1f/%.1f, 3 %.1f/%.1f over %d UVs; open edges at the ankles %d (longest %.4f), gaps to the model's skin %d (longest %.4f)\n", row->name.c_str(), sb.mean[0], sb.worst[0],
                                        sb.mean[1], sb.worst[1], sb.mean[2], sb.worst[2], sb.mean[3], sb.worst[3], sb.samples, sb.open, sb.openLen, sb.gaps, sb.gapLen);
                        }
                        // Keep undead bones (a row with "detail"): its details carried, the seams' colour as with them off (within 3)
                        if (row->detail > 0) {
                            EveJob off = j;
                            off.opt.bones = false;
                            EveResult r0 = eveCompute(off);
                            std::printf("  %s: Keep undead bones: %zu pieces, %zu vertices kept, %zu texels, %.1f ms; seams ΔE off %.1f/%.1f/%.1f\n", row->name.c_str(),
                                        res.detailPieces, res.detailKept, res.detailCovered, res.detailMs, r0.seamDE[0], r0.seamDE[1], r0.seamDE[2]);
                            CHECK(res.detail && res.detailPieces > 0 && res.detailCovered > 0 && (row->detail < 2 || res.detailKept > 0), "%s: details carried", row->name.c_str());
                            CHECK(r0.ok && !r0.detail && r0.rgba != res.rgba, "%s: off, none", row->name.c_str());
                            for (int k = 0; k < 3; ++k)
                                CHECK(std::fabs(res.seamDE[k] - r0.seamDE[k]) <= 3.0f, "%s: seam %d's ΔE %.1f with them, %.1f without", row->name.c_str(), k,
                                      res.seamDE[k], r0.seamDE[k]);
                            // the set's cache: the same body again is the same bytes, made no slower than the first
                            EveResult again = eveCompute(j);
                            CHECK(again.rgba == res.rgba && again.cutIndices == res.cutIndices, "%s: the cached layer gives the same bytes (%.1f ms)", row->name.c_str(),
                                  again.detailMs);
                            // off is exactly the table without the row's details
                            EveJob plain = j;
                            plain.fit.detail = 0;
                            EveResult rp = eveCompute(plain);
                            CHECK(rp.rgba == r0.rgba && rp.cutIndices == r0.cutIndices, "%s: off is the body as it was", row->name.c_str());
                        }
                    }
                }
            }
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
