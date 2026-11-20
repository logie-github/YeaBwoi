// The tabard flap's cloth on a real model, without GL (engine/cloth.h): a look file's model and geosets, an animation
// played at 60 frames a second, the cloth stepped as Scene steps it, and per frame what the renders can't show by eye:
// how many free cloth points lie inside the body (the nearest skinned body vertex's normal faces away from the point,
// within 5 cm), the deepest one, the cloth's largest stretch, and the time a frame of cloth takes.
//
//   build/cloth_check FILES_DIR LOOKFILE ANIM_ID FRAMES [STEP_MS]     exit 0 when no frame has a point inside the body
//                                                                      and the stretch stays under 5%
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "../android/app/src/main/cpp/engine/cloth.h"

using namespace wc;

namespace {

// Scene's characterGeosets (scene.cpp), for a look's groups.
std::vector<uint16_t> geosetsOf(const M2Model& m, const std::map<int, int>& groups) {
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

} // namespace

int main(int argc, char** argv) {
    if (argc < 5) { std::fprintf(stderr, "usage: cloth_check FILES_DIR LOOKFILE ANIM_ID FRAMES [STEP_MS]\n"); return 2; }
    std::string dir = argv[1];
    FileLookup files = [&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        std::ifstream f(p);
        return f.good() ? p : std::string();
    };
    uint32_t model = 0;
    std::map<int, int> groups;
    {
        std::ifstream in(argv[2]);
        std::string line;
        while (std::getline(in, line)) {
            std::istringstream ss(line);
            std::string k;
            ss >> k;
            if (k == "model") ss >> model;
            else if (k == "geo") {
                std::string list; ss >> list;
                std::stringstream ls(list);
                std::string item;
                while (std::getline(ls, item, ',')) { auto e = item.find('='); if (e != std::string::npos) groups[std::stoi(item.substr(0, e))] = std::stoi(item.substr(e + 1)); }
            }
        }
    }
    M2Model m;
    if (!m.load(model, files)) { std::fprintf(stderr, "model %u: %s\n", model, m.error.c_str()); return 2; }
    const int anim = std::atoi(argv[3]), frames = std::atoi(argv[4]);
    const int stepMs = argc > 5 ? std::atoi(argv[5]) : 16;
    int seq = m.findSequence(uint16_t(anim));
    if (seq < 0) { std::fprintf(stderr, "no animation %d\n", anim); return 2; }
    m.ensureSequence(seq, files);
    if (!m.sequenceReady(seq)) { std::fprintf(stderr, "animation %d: its keys aren't in the files folder\n", anim); return 2; }
    std::vector<uint16_t> geosets = geosetsOf(m, groups);
    ClothRig rig = clothRig(m, m.vertices, m.indices, m.submeshes, geosets);
    if (!rig.ok) { std::fprintf(stderr, "no cloth on this look\n"); return 2; }
    std::printf("rig: %zu pieces, %zu points, %zu skin vertices", rig.pieces.size(), rig.particles(), rig.surface.size());
    for (const ClothPiece& p : rig.pieces) std::printf(" | side %+d %dx%d links %zu", p.side, p.cols, p.rows, p.links.size());
    std::printf("\nflap texture box: u %.4f..%.4f v %.4f..%.4f (2048x1024 px x %.0f..%.0f y %.0f..%.0f)\n", rig.uv0[0], rig.uv1[0], rig.uv0[1],
                rig.uv1[1], rig.uv0[0] * 2048, rig.uv1[0] * 2048, rig.uv0[1] * 1024, rig.uv1[1] * 1024);
    for (const ClothBodySeg& b : rig.body) {
        std::printf("body %-10s ok %d start %.3f end %.3f; front, side, back, side by section:", kFleshSegs[b.seg].name, b.ok, b.start, b.end);
        for (int s = 0; s < kClothSections; ++s)
            std::printf(" %.3f/%.3f/%.3f/%.3f", b.r[s][0], b.r[s][kClothBins / 4], b.r[s][kClothBins / 2], b.r[s][3 * kClothBins / 4]);
        std::printf("\n");
    }

    // the body as drawn (not the flap, not a cape): for the inside test
    std::vector<uint8_t> used(m.vertices.size(), 0);
    for (const M2Submesh& sm : m.submeshes) {
        if (sm.id / 100 == kClothGroup && sm.id >= 100) continue;
        if (sm.id / 100 == 15) continue;
        if (std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t k = sm.indexStart; k < sm.indexStart + sm.indexCount; ++k) used[m.indices[k]] = 1;
    }
    std::vector<uint32_t> bodyIdx;
    for (size_t i = 0; i < used.size(); ++i) if (used[i]) bodyIdx.push_back(uint32_t(i));

    ClothSim sim;
    sim.setRig(&rig);
    sim.setFloor(0.0f);   // the model's own floor
    std::vector<BonePose> pose;
    std::vector<M4> mats;
    std::vector<V3> posed;
    std::vector<ClothCapsule> caps;
    ClothSurface skin;
    const M4 world;   // the model's own place: the sim in model units
    int worstInside = 0, framesInside = 0;
    float swing[2] = {0, 0};
    float deepest = 0, worstStretch = 0;
    double clothMs = 0;
    uint32_t dur = std::max<uint32_t>(1, m.sequences[size_t(seq)].duration);
    std::vector<V3> bp(bodyIdx.size()), bn(bodyIdx.size());
    for (int f = 0; f < frames; ++f) {
        uint32_t now = 1000 + uint32_t(f * stepMs);
        m.pose(seq, (now - 1000) % dur, now, pose, true);
        m.bonesToMatrices(pose, mats);
        auto t0 = std::chrono::steady_clock::now();
        clothPosed(rig, m.vertices, mats, world, posed);
        clothCapsules(m, rig, mats, world, caps);
        clothSurface(rig, m.vertices, mats, world, skin);
        sim.step(posed, caps, std::getenv("CLOTH_NOSKIN") ? nullptr : &skin, 1.0f, now);
        auto t1 = std::chrono::steady_clock::now();
        if (f > 0) clothMs += std::chrono::duration<double, std::milli>(t1 - t0).count();
        // the body skinned this frame
        for (size_t i = 0; i < bodyIdx.size(); ++i) {
            const M2Vertex& v = m.vertices[bodyIdx[i]];
            V3 p(v.pos[0], v.pos[1], v.pos[2]), nn(v.normal[0], v.normal[1], v.normal[2]), s(0, 0, 0), sn(0, 0, 0);
            float tw = 0;
            for (int k = 0; k < 4; ++k) {
                if (!v.weights[k]) continue;
                float w = v.weights[k] / 255.0f;
                const M4& b = mats[v.bones[k]];
                s = s + b.point(p) * w;
                sn = sn + V3(b.at(0, 0) * nn.x + b.at(0, 1) * nn.y + b.at(0, 2) * nn.z, b.at(1, 0) * nn.x + b.at(1, 1) * nn.y + b.at(1, 2) * nn.z,
                             b.at(2, 0) * nn.x + b.at(2, 1) * nn.y + b.at(2, 2) * nn.z) * w;
                tw += w;
            }
            bp[i] = tw > 0 ? s * (1.0f / tw) : p;
            bn[i] = sn.normalized();
        }
        int inside = 0;
        float frameDeep = 0, minClear = 1e9f;
        const std::vector<V3>& x = std::getenv("CLOTH_POSED") ? posed : sim.positions();
        for (const ClothPiece& p : rig.pieces)
            for (size_t i = 0; i < p.points.size(); ++i) {
                if (p.points[i].pinned) continue;
                const V3& q = x[p.first + i];
                float best = 1e9f;
                size_t bi = 0;
                for (size_t k = 0; k < bp.size(); ++k) {
                    V3 d = q - bp[k];
                    float dd = d.dot(d);
                    if (dd < best) { best = dd; bi = k; }
                }
                float dist = std::sqrt(best);
                float sd = (q - bp[bi]).dot(bn[bi]);
                if (sd < 0 && dist < 0.05f) {
                    ++inside; frameDeep = std::max(frameDeep, -sd);
                    if (std::getenv("CLOTH_DEBUG") && std::getenv("CLOTH_DEBUG")[0] == '3')
                        std::printf("     inside: side %+d r%zu c%zu at %.3f %.3f %.3f by %.4f (body vertex %u at %.3f %.3f %.3f)\n", p.side, i / size_t(p.cols),
                                    i % size_t(p.cols), q.x, q.y, q.z, -sd, bodyIdx[bi], bp[bi].x, bp[bi].y, bp[bi].z);
                }
                minClear = std::min(minClear, sd < 0 ? -dist : dist);
            }
        // how far each piece swings from hanging straight down: its top middle to its hem's middle, from the vertical
        for (size_t pi = 0; pi < rig.pieces.size() && pi < 2; ++pi) {
            const ClothPiece& p = rig.pieces[pi];
            V3 top = x[p.first + size_t(p.cols / 2)], hem = x[p.first + size_t((p.rows - 1) * p.cols + p.cols / 2)];
            V3 d = hem - top;
            float ang = std::acos(std::clamp(-d.z / std::max(1e-6f, d.length()), -1.0f, 1.0f)) * 57.2958f;
            swing[pi] = std::max(swing[pi], ang);
        }
        float stretch = sim.maxStretch(1.0f);
        if (std::getenv("CLOTH_DEBUG")) {
            float w = 0; int wp = 0; ClothLink wl;
            for (size_t pi = 0; pi < rig.pieces.size(); ++pi)
                for (const ClothLink& l : rig.pieces[pi].links) {
                    if (l.kind) continue;
                    float s2 = (x[rig.pieces[pi].first + l.b] - x[rig.pieces[pi].first + l.a]).length() / l.rest - 1;
                    if (s2 > w) { w = s2; wp = int(pi); wl = l; }
                }
            int C = rig.pieces[size_t(wp)].cols;
            std::printf("   worst link piece %d (%d,%d)-(%d,%d) %.3f\n", wp, wl.a / C, wl.a % C, wl.b / C, wl.b % C, w);
            if (std::getenv("CLOTH_DEBUG")[0] == '2') {
                const ClothPiece& pc = rig.pieces[size_t(wp)];
                for (int r = 0; r < pc.rows; ++r) {
                    size_t k = pc.first + size_t(r * pc.cols + wl.a % C);
                    std::printf("     r%d x %.3f %.3f %.3f posed %.3f %.3f %.3f\n", r, x[k].x, x[k].y, x[k].z, posed[k].x, posed[k].y, posed[k].z);
                }
            }
        }
        worstStretch = std::max(worstStretch, stretch);
        worstInside = std::max(worstInside, inside);
        if (inside) ++framesInside;
        deepest = std::max(deepest, frameDeep);
        std::printf("frame %3d t %5u inside %2d deepest %.4f nearest %.4f stretch %.4f\n", f, now, inside, frameDeep, minClear, stretch);
    }
    if (std::getenv("CLOTH_ROWS")) {
        const std::vector<V3>& x = sim.positions();
        for (const ClothPiece& p : rig.pieces)
            for (int r = 0; r < p.rows; r += 2) {
                std::printf("side %+d row %2d:", p.side, r);
                for (int c = 0; c < p.cols; c += 2) { const V3& q = x[p.first + size_t(r * p.cols + c)]; const V3& o = posed[p.first + size_t(r * p.cols + c)];
                    std::printf(" (%.3f %.3f %.3f | %.3f %.3f %.3f)", q.x, q.y, q.z, o.x, o.y, o.z); }
                std::printf("\n");
            }
    }
    std::printf("summary: frames %d, frames with a point inside %d, most inside %d, deepest %.4f, worst stretch %.4f, widest swing %.0f/%.0f deg, cloth %.4f ms a frame (desktop)\n",
                frames, framesInside, worstInside, deepest, worstStretch, swing[0], swing[1], frames > 1 ? clothMs / (frames - 1) : 0.0);
    return framesInside == 0 && worstStretch < 0.05f ? 0 : 1;
}
