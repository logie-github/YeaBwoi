// HD Models' engine side (engine/hdbody.h) with a real converted set and the game's model:
//   hdbody_test SET.whd FILES_DIR MODEL_FILE_ID
// - the .whd read, written back (writeWhd) and read again: the same meshes, bones, joints, choices;
// - the fit on the model: every vertex's four weights sum to 255 and name bones the model has; the limbs turned to the model's
//   rest directions (the worst angle left, degrees); where its joints stand against the model's (model units, about metres:
//   the neck's height and the pelvis's place are matched by the fit, the rest are the HD figure's own proportions);
//   the triangles drawn (the body, the most hair and the most outfit);
// - the weights: the converter's (the HD rig's authored weights mapped onto the game's bones by name) against a nearest-vertex
//   transfer from the game's own body (hdTransferWeights), within 8 cm of the main joints: how often the two give the same
//   main bone, and how each skins in the game's poses;
// - the game's poses (its own sequences in FILES_DIR: standing, walking, a chair, kneeling, asleep, cheering, bowing), skinned on
//   the CPU as the renderer skins the HD figure (bodyrig.h bodyEditFrom on its rig's pivots): each triangle's longest edge's
//   growth over the bind pose (the 99.9th percentile against the model's own body in the same pose, which must stay within
//   1.5 times it and 2 cm more: the HD figure's buttock crease walking, 2.9 cm against the model's 1.2, is the largest) and the collapsed triangles (area under 5 % of the bind's: at most 0.5 %).
// Exit 0 when all pass.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <string>
#include <vector>

#include "bodyrig.h"
#include "bytes.h"
#include "hdbody.h"
#include "m2.h"

using namespace wc;

static int failures = 0;
#define CHECK(c, ...) do { if (!(c)) { ++failures; std::printf("FAIL: " __VA_ARGS__); std::printf("\n"); } } while (0)

static bool readAll(const std::string& p, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(p.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END); long n = std::ftell(f); std::fseek(f, 0, SEEK_SET);
    out.resize(size_t(std::max(0L, n)));
    bool ok = std::fread(out.data(), 1, out.size(), f) == out.size();
    std::fclose(f);
    return ok;
}

static void skin(const std::vector<M2Vertex>& vs, const std::vector<M4>& mats, std::vector<V3>& out) {
    out.resize(vs.size());
    for (size_t i = 0; i < vs.size(); ++i) {
        V3 p(vs[i].pos[0], vs[i].pos[1], vs[i].pos[2]), s;
        for (int k = 0; k < 4; ++k) if (vs[i].weights[k] && vs[i].bones[k] < mats.size()) s = s + mats[vs[i].bones[k]].point(p) * (vs[i].weights[k] / 255.0f);
        out[i] = s;
    }
}

static void stretch(const std::vector<V3>& rest, const std::vector<V3>& posed, const std::vector<uint32_t>& idx, float& p999, double& collapsed) {
    std::vector<float> s;
    size_t col = 0, n = 0;
    for (size_t t = 0; t + 2 < idx.size(); t += 3) {
        uint32_t a = idx[t], b = idx[t + 1], c = idx[t + 2];
        float worst = 0;
        for (auto [x, y] : {std::pair<uint32_t, uint32_t>{a, b}, {b, c}, {c, a}}) worst = std::max(worst, (posed[x] - posed[y]).length() - (rest[x] - rest[y]).length());
        float ar = (rest[b] - rest[a]).cross(rest[c] - rest[a]).length(), ap = (posed[b] - posed[a]).cross(posed[c] - posed[a]).length();
        if (ar < 1e-10f) continue;
        ++n; s.push_back(worst);
        if (ap < 0.05f * ar) ++col;
    }
    std::sort(s.begin(), s.end());
    p999 = s.empty() ? 0 : s[std::min(s.size() - 1, size_t(0.999 * double(s.size())))];
    collapsed = n ? double(col) / double(n) : 0;
}

int main(int argc, char** argv) {
    if (argc < 4) { std::printf("usage: hdbody_test SET.whd FILES_DIR MODEL_FILE_ID\n"); return 2; }
    std::vector<uint8_t> raw;
    if (!readAll(argv[1], raw)) { std::printf("can't read %s\n", argv[1]); return 2; }
    HdSet set; std::string err;
    CHECK(parseWhd(raw.data(), raw.size(), set, err), "parse: %s", err.c_str());
    if (failures) return 1;
    // ---- the file round trip
    {
        std::vector<uint8_t> back = writeWhd(set);
        HdSet r; std::string e2;
        CHECK(parseWhd(back.data(), back.size(), r, e2), "parse again: %s", e2.c_str());
        bool same = r.meshes.size() == set.meshes.size() && r.bones == set.bones && r.joints.size() == set.joints.size() &&
                    r.hair.size() == set.hair.size() && r.outfits.size() == set.outfits.size() && r.overlays.size() == set.overlays.size() &&
                    r.textures.size() == set.textures.size() && r.race == set.race && r.sex == set.sex && r.tints.size() == set.tints.size();
        for (size_t i = 0; same && i < r.meshes.size(); ++i)
            same = r.meshes[i].pos == set.meshes[i].pos && r.meshes[i].idx == set.meshes[i].idx && r.meshes[i].bw == set.meshes[i].bw && r.meshes[i].parts.size() == set.meshes[i].parts.size();
        for (size_t i = 0; same && i < r.outfits.size(); ++i) same = r.outfits[i].hidden == set.outfits[i].hidden && r.outfits[i].classes == set.outfits[i].classes;
        CHECK(same, "round trip changed the set");
        std::vector<uint8_t> cut(back.begin(), back.begin() + long(back.size() / 2));
        CHECK(!parseWhd(cut.data(), cut.size(), r, e2), "a file cut in half is refused");
        std::printf("file: %zu meshes, %zu textures, %zu bones, %zu joints, %zu hair, %zu outfits, %zu overlays; written back %zu bytes (read %zu)\n",
                    set.meshes.size(), set.textures.size(), set.bones.size(), set.joints.size(), set.hair.size(), set.outfits.size(),
                    set.overlays.size(), back.size(), raw.size());
    }
    const std::string dir = argv[2];
    FileLookup look = [&](uint32_t f) { return dir + "/" + std::to_string(f); };
    M2Model m;
    if (!m.load(uint32_t(std::strtoul(argv[3], nullptr, 10)), look)) { std::printf("model: %s\n", m.error.c_str()); return 2; }
    // ---- the fit
    HdOptions o; o.on = true;
    HdFit fit = fitHd(m, set, o, err);
    CHECK(!fit.verts.empty(), "fit: %s", err.c_str());
    if (fit.verts.empty()) return 1;
    size_t badSum = 0, badBone = 0;
    for (const M2Vertex& v : fit.verts) {
        int s = 0;
        for (int k = 0; k < 4; ++k) { s += v.weights[k]; if (v.weights[k] && v.bones[k] >= m.bones.size()) ++badBone; }
        if (s != 255) ++badSum;
    }
    CHECK(badSum == 0 && badBone == 0, "weights: %zu don't sum to 255, %zu name a bone the model lacks", badSum, badBone);
    CHECK(fit.stats.jointError < 1.0f, "limbs left %.2f degrees off the model's rest directions", fit.stats.jointError);
    std::printf("fit: scale %.3f, %zu vertices, %zu triangles drawn (bare, first hair), unmapped %zu, limbs within %.3f degrees, %.1f ms\n",
                fit.stats.scale, fit.stats.vertices, fit.stats.triangles, fit.stats.unmapped, fit.stats.jointError, fit.stats.ms);
    std::printf("joints (HD rig against the model's, model units):\n");
    for (const char* n : {"Pelvis", "Neck1", "Head", "L_Clavicle", "L_UpperArm", "L_Forearm", "L_Hand", "L_Thigh", "L_Calf", "L_Foot", "L_Breast"}) {
        int b = m.findWowBone(n);
        if (b < 0) continue;
        V3 d = fit.rigPivots[size_t(b)] - m.bones[size_t(b)].pivot;
        std::printf("  %-11s HD (%.3f %.3f %.3f) model (%.3f %.3f %.3f) off %.1f cm\n", n, fit.rigPivots[size_t(b)].x, fit.rigPivots[size_t(b)].y,
                    fit.rigPivots[size_t(b)].z, m.bones[size_t(b)].pivot.x, m.bones[size_t(b)].pivot.y, m.bones[size_t(b)].pivot.z, d.length() * 100);
    }
    {
        int nk = m.findWowBone("Neck1"), pv = m.findWowBone("Pelvis");
        float neckDz = std::fabs(fit.rigPivots[size_t(nk)].z - m.bones[size_t(nk)].pivot.z);
        V3 pd = fit.rigPivots[size_t(pv)] - m.bones[size_t(pv)].pivot;
        CHECK(neckDz < 0.02f, "the neck's height %.1f cm off", neckDz * 100);
        CHECK(std::hypot(pd.x, pd.y) < 0.02f, "the pelvis %.1f cm off across", std::hypot(pd.x, pd.y) * 100);
    }
    size_t bodyTris = set.meshes[size_t(set.body)].idx.size() / 3, hairMax = 0, outMax = 0;
    for (const HdChoice& c : set.hair) { size_t t = 0; for (int k : c.meshes) t += set.meshes[size_t(k)].idx.size() / 3; hairMax = std::max(hairMax, t); }
    for (const HdChoice& c : set.outfits) { size_t t = 0; for (int k : c.meshes) t += set.meshes[size_t(k)].idx.size() / 3; outMax = std::max(outMax, t); }
    std::printf("triangles: body %zu, most hair %zu, most outfit %zu: at most %zu drawn\n", bodyTris, hairMax, outMax, bodyTris + hairMax + outMax);
    CHECK(bodyTris <= 40000, "the body has %zu triangles", bodyTris);
    // ---- authored (mapped) weights against a transfer from the game's body, by the main joints
    std::vector<M2Vertex> body(fit.verts.begin(), fit.verts.begin() + fit.bodyVertexEnd), moved = body;
    {
        std::vector<V3> pts;
        for (const M2Vertex& v : body) pts.push_back(V3(v.pos[0], v.pos[1], v.pos[2]));
        std::vector<std::array<uint8_t, 4>> bi, bw;
        hdTransferWeights(m, {}, pts, bi, bw);
        for (size_t i = 0; i < moved.size(); ++i) for (int k = 0; k < 4; ++k) { moved[i].bones[k] = bi[i][size_t(k)]; moved[i].weights[k] = bw[i][size_t(k)]; }
        int s0 = 0;
        for (const M2Vertex& v : moved) { int s = 0; for (int k = 0; k < 4; ++k) s += v.weights[k]; if (s == 255) ++s0; }
        std::printf("transfer: %d of %zu vertices with weights summing to 255\n", s0, moved.size());
        auto main = [](const M2Vertex& v) { int b = 0; for (int k = 1; k < 4; ++k) if (v.weights[k] > v.weights[b]) b = k; return int(v.bones[b]); };
        for (const char* n : {"Neck1", "L_UpperArm", "L_Forearm", "L_Hand", "L_Thigh", "L_Calf", "L_Foot"}) {
            int b = m.findWowBone(n);
            if (b < 0) continue;
            V3 c = fit.rigPivots[size_t(b)];
            size_t near = 0, agree = 0;
            for (size_t i = 0; i < body.size(); ++i) {
                if ((pts[i] - c).length() > 0.08f) continue;
                ++near; if (main(body[i]) == main(moved[i])) ++agree;
            }
            std::printf("  by %-10s %5zu vertices, the same main bone %.0f %%\n", n, near, near ? 100.0 * double(agree) / double(near) : 0.0);
        }
    }
    // ---- the poses
    const LegEdit edit = bodyEditFrom(m, fit.rigPivots, BodyScale());
    std::vector<M4> bindMats;
    legEditMatrices(m.bones, std::vector<BonePose>(), edit, bindMats);
    std::vector<V3> hRest, tRest, mRest;
    skin(body, bindMats, hRest);
    skin(moved, bindMats, tRest);
    std::vector<uint32_t> mIdx;
    for (const M2Submesh& sm : m.submeshes) if (sm.id == 0) mIdx.insert(mIdx.end(), m.indices.begin() + sm.indexStart, m.indices.begin() + sm.indexStart + sm.indexCount);
    skin(m.vertices, std::vector<M4>(m.bones.size(), M4()), mRest);
    struct Pose { const char* what; uint16_t anim; };
    const Pose poses[] = {{"stand", 0}, {"walk", 4}, {"chair", 103}, {"kneel", 115}, {"asleep", 100}, {"cheer", 68}, {"bow", 66}};
    std::printf("poses (edge growth 99.9th pct cm: HD authored / HD transferred / model; collapsed %%):\n");
    for (const Pose& p : poses) {
        int seq = m.findSequence(p.anim);
        if (seq >= 0) m.ensureSequence(seq, look);
        if (seq < 0 || !m.sequenceReady(seq)) { std::printf("  %s: not here (anim %u), skipped\n", p.what, p.anim); continue; }
        float h = 0, t = 0, mo = 0; double hc = 0, tc = 0, mc = 0;
        const uint32_t dur = std::max<uint32_t>(1, m.sequences[size_t(seq)].duration);
        for (float f : {0.2f, 0.4f, 0.6f, 0.8f, 0.97f}) {
            uint32_t at = uint32_t(f * float(dur));
            std::vector<BonePose> bp; m.pose(seq, at, at, bp, false);
            std::vector<M4> em, mm;
            legEditMatrices(m.bones, bp, edit, em);
            m.bonesToMatrices(bp, mm);
            std::vector<V3> hp, tp, mp;
            skin(body, em, hp); skin(moved, em, tp); skin(m.vertices, mm, mp);
            float a, b, c; double ca, cb, cc;
            stretch(hRest, hp, fit.bodyIndices, a, ca); stretch(tRest, tp, fit.bodyIndices, b, cb); stretch(mRest, mp, mIdx, c, cc);
            h = std::max(h, a); t = std::max(t, b); mo = std::max(mo, c); hc = std::max(hc, ca); tc = std::max(tc, cb); mc = std::max(mc, cc);
        }
        if (std::getenv("HD_WHERE")) {
            // where the worst stretches are (the main bone of their first corner), at the middle of the sequence
            uint32_t at = dur / 2;
            std::vector<BonePose> bp; m.pose(seq, at, at, bp, false);
            std::vector<M4> em; legEditMatrices(m.bones, bp, edit, em);
            std::vector<V3> hp; skin(body, em, hp);
            std::vector<std::pair<float, uint32_t>> w;
            for (size_t q = 0; q + 2 < fit.bodyIndices.size(); q += 3) {
                uint32_t a = fit.bodyIndices[q], b = fit.bodyIndices[q + 1], c = fit.bodyIndices[q + 2];
                float g = 0;
                for (auto [x, y] : {std::pair<uint32_t, uint32_t>{a, b}, {b, c}, {c, a}}) g = std::max(g, (hp[x] - hp[y]).length() - (hRest[x] - hRest[y]).length());
                w.push_back({g, a});
            }
            std::sort(w.rbegin(), w.rend());
            std::map<int, int> byBone;
            for (size_t q = 0; q < std::min<size_t>(40, w.size()); ++q) { const M2Vertex& v = body[w[q].second]; int bb = 0; for (int k = 1; k < 4; ++k) if (v.weights[k] > v.weights[bb]) bb = k; byBone[v.bones[bb]]++; }
            std::printf("    worst 40 by main bone:");
            for (auto [b, n] : byBone) std::printf(" %d(key %d)x%d", b, m.bones[size_t(b)].keyBoneId, n);
            std::printf("  top %.1f cm at (%.2f %.2f %.2f)\n", w[0].first * 100, hRest[w[0].second].x, hRest[w[0].second].y, hRest[w[0].second].z);
        }
        std::printf("  %-7s %5.1f / %5.1f / %5.1f   collapsed %.2f / %.2f / %.2f %%\n", p.what, h * 100, t * 100, mo * 100, hc * 100, tc * 100, mc * 100);
        CHECK(h <= 1.5f * mo + 0.02f, "%s: the HD body stretches %.1f cm against the model's %.1f", p.what, h * 100, mo * 100);
        CHECK(hc <= 0.005, "%s: %.2f %% of the HD body's triangles collapse", p.what, hc * 100);
    }
    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
