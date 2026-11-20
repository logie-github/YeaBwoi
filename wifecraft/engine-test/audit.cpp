// The placement audit (tools/audit/README.md): plays the cases a plan names (people dressed at human scale, placed where the
// app's own code put them, posed by an imported clip or a game animation) and measures each moment on the real skinned
// meshes: one body inside the other (capsules fitted to each body's own vertices, hands apart), how far apart the two
// bodies' pelvises and trunks are (two bodies on one axis), body vertices inside a piece of furniture (its solid from the
// model's own triangles), resting bodies that float over their surface, and anything under the floor. Writes one CSV row per
// measured moment and renders the pictures the plan asks for. Desktop only (Mesa EGL, surfaceless).
//
//   audit PLAN CSV_OUT
//
// The plan, one command a line ('#' comments):
//   files DIR                              the game files, each named by its file id
//   location JSON ID VIEW                  loads a location (its doodads are furniture: `solid doodad`)
//   floor Z                                the floor's height for the floor checks
//   prop NAME MODEL X Y Z YAW SCALE        a model placed in the scene
//   contact NAME sit|lie                   prints where the app's measure of the prop's surface is (Scene::propContact)
//   solid NAME prop PROPNAME | solid NAME doodad MODEL X Y Z    a piece of furniture's solid (heightfield of its triangles)
//   clearsolids | clearprops
//   actor SLOT LOOK TARGET|own XY          person SLOT (0, 1) dressed in LOOK, at human scale (TARGET the sex's height) and
//                                          the clips' root motion across the floor XY (Scene::setHumanRootMotion)
//   place SLOT X Y Z FACING [raw]          where the app put them (the engine's floor pose; raw: Scene::kRaised, not stood on the floor)
//   clip SLOT PATH | emote SLOT ANIM LOOP | still SLOT     what they play (from the case's clock 0)
//   hide SLOT
//   flesh SLOT OUT                         writes the person's flesh as the app reads it (Scene::actorFlesh, engine/flesh.h):
//                                          the packed floats on one line, then a line per segment; and how long it took
//   case ID key=value...                   starts a case: its keys go into every CSV row (rest0=, rest1=: sit|lie|kneel|floor|stand)
//   measure T                              poses everyone at T ms and writes a row
//   camera EX EY EZ TX TY TZ FOV | render PNG W H T
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <iostream>
#include <iterator>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unordered_map>
#include <utility>
#include <vector>

// the measure reads the scene's own state (the posed bones, the drawn geosets, the props' and doodads' transforms)
#define private public
#define protected public
#include "../android/app/src/main/cpp/engine/scene.h"
#include "../android/app/src/main/cpp/engine/flesh.h"
#undef private
#undef protected

using namespace wc;

static std::vector<std::string> split(const std::string& s, char c) {
    std::vector<std::string> out;
    std::stringstream ss(s);
    std::string item;
    while (std::getline(ss, item, c)) if (!item.empty()) out.push_back(item);
    return out;
}

static uint32_t number(const std::string& s) { return uint32_t(std::strtoul(s.c_str(), nullptr, 0)); }

struct Look {
    uint32_t model = 0;
    float scale = 1;
    bool character = true;
    std::map<uint32_t, uint32_t> tex;
    std::map<int, int> geo;
    std::vector<SkinLayer> layers;
    std::vector<DressModel> extras;
};

static bool readLook(const std::string& path, Look& out) {
    std::ifstream in(path);
    if (!in) { std::fprintf(stderr, "no look file %s\n", path.c_str()); return false; }
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string k;
        ss >> k;
        if (k.empty() || k[0] == '#') continue;
        if (k == "model") ss >> out.model;
        else if (k == "scale") ss >> out.scale;
        else if (k == "character") { int c = 1; ss >> c; out.character = c != 0; }
        else if (k == "tex" || k == "geo") {
            std::string v; ss >> v;
            for (auto& kv : split(v, ',')) {
                auto p = split(kv, '=');
                if (p.size() != 2) continue;
                if (k == "tex") out.tex[number(p[0])] = number(p[1]); else out.geo[std::atoi(p[0].c_str())] = std::atoi(p[1].c_str());
            }
        } else if (k == "layer") {
            SkinLayer l; std::string t1, t2;
            ss >> l.textureType >> l.layer >> l.blend >> l.x >> l.y >> l.w >> l.h >> l.materialW >> l.materialH >> l.fileId >> t1 >> t2;
            if (!t1.empty()) l.tint1 = number(t1);
            if (!t2.empty()) l.tint2 = number(t2);
            out.layers.push_back(l);
        } else if (k == "extra") {
            DressModel d; ss >> d.attachment >> d.model >> d.texture >> d.geoset; out.extras.push_back(d);
        }
    }
    return out.model != 0;
}

static bool readBoneAnim(const std::string& path, BoneAnim& out) {
    std::ifstream in(path);
    if (!in) { std::fprintf(stderr, "no anim file %s\n", path.c_str()); return false; }
    std::map<std::pair<std::string, bool>, size_t> index;
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string k; ss >> k;
        if (k.empty() || k[0] == '#') continue;
        if (k == "duration") ss >> out.duration;
        else if (k == "loop") out.loop = true;
        else if (k == "rot" || k == "pos") {
            std::string bone; float t; ss >> bone >> t;
            bool rot = k == "rot";
            auto key = std::make_pair(bone, rot);
            if (!index.count(key)) { index[key] = out.tracks.size(); out.tracks.push_back({bone, rot, {}, {}}); }
            BoneAnimTrack& tr = out.tracks[index[key]];
            tr.times.push_back(t);
            for (int i = 0; i < (rot ? 4 : 3); ++i) { float v = 0; ss >> v; tr.values.push_back(v); }
        }
    }
    return true;
}

static void writePng(const std::string& path, int w, int h, const std::vector<uint8_t>& rgb) {
    std::vector<uint8_t> raw;
    raw.reserve(size_t(h) * (w * 3 + 1));
    for (int y = 0; y < h; ++y) { raw.push_back(0); raw.insert(raw.end(), rgb.begin() + size_t(y) * w * 3, rgb.begin() + size_t(y + 1) * w * 3); }
    uLongf zn = compressBound(uLong(raw.size()));
    std::vector<uint8_t> z(zn);
    compress2(z.data(), &zn, raw.data(), uLong(raw.size()), 6);
    z.resize(zn);
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    auto be = [&](uint32_t v) { uint8_t q[4] = {uint8_t(v >> 24), uint8_t(v >> 16), uint8_t(v >> 8), uint8_t(v)}; std::fwrite(q, 1, 4, f); };
    auto chunk = [&](const char* type, const std::vector<uint8_t>& data) {
        be(uint32_t(data.size()));
        std::vector<uint8_t> td(type, type + 4);
        td.insert(td.end(), data.begin(), data.end());
        std::fwrite(td.data(), 1, td.size(), f);
        be(uint32_t(crc32(0, td.data(), uInt(td.size()))));
    };
    const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    std::fwrite(sig, 1, 8, f);
    chunk("IHDR", {uint8_t(w >> 24), uint8_t(w >> 16), uint8_t(w >> 8), uint8_t(w), uint8_t(h >> 24), uint8_t(h >> 16), uint8_t(h >> 8), uint8_t(h), 8, 2, 0, 0, 0});
    chunk("IDAT", z);
    chunk("IEND", {});
    std::fclose(f);
}

static bool readText(const std::string& path, std::string& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "no file %s\n", path.c_str()); return false; }
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

// ---- geometry

static float segDist(V3 p, V3 a, V3 b, float* along = nullptr) {
    V3 ab = b - a;
    float l2 = ab.x * ab.x + ab.y * ab.y + ab.z * ab.z;
    float t = l2 > 1e-10f ? std::clamp(((p - a).x * ab.x + (p - a).y * ab.y + (p - a).z * ab.z) / l2, 0.0f, 1.0f) : 0.0f;
    if (along) *along = t;
    return (p - (a + ab * t)).length();
}

// the shortest distance between two segments (sampled: exact enough for bodies)
static float segSegDist(V3 a0, V3 a1, V3 b0, V3 b1) {
    float best = 1e9f;
    for (int i = 0; i <= 16; ++i) best = std::min(best, segDist(lerp(a0, a1, i / 16.0f), b0, b1));
    for (int i = 0; i <= 16; ++i) best = std::min(best, segDist(lerp(b0, b1, i / 16.0f), a0, a1));
    return best;
}

// The regions a body's vertices are given to (each by its dominant bone's nearest named ancestor) and the joint each
// capsule runs to (its child; none: toward its own vertices' far end).
struct RegionDef { const char* bone; const char* child; bool hand; bool trunk; };
static const RegionDef kRegions[] = {
    {"Root", "Spine1", false, true}, {"Pelvis", nullptr, false, true}, {"Spine1", "Spine2", false, true},
    {"Spine2", "Chest", false, true}, {"Chest", "Neck1", false, true}, {"Neck1", "Head", false, false},
    {"Head", nullptr, false, false},
    {"L_Clavicle", "L_UpperArm", false, false}, {"L_UpperArm", "L_Forearm", false, false}, {"L_Forearm", "L_Hand", false, false},
    {"L_Hand", nullptr, true, false},
    {"R_Clavicle", "R_UpperArm", false, false}, {"R_UpperArm", "R_Forearm", false, false}, {"R_Forearm", "R_Hand", false, false},
    {"R_Hand", nullptr, true, false},
    {"L_Thigh", "L_Calf", false, false}, {"L_Calf", "L_Foot", false, false}, {"L_Foot", "L_Toe", false, false}, {"L_Toe", nullptr, false, false},
    {"R_Thigh", "R_Calf", false, false}, {"R_Calf", "R_Foot", false, false}, {"R_Foot", "R_Toe", false, false}, {"R_Toe", nullptr, false, false},
};
static constexpr int kRegionCount = int(sizeof(kRegions) / sizeof(kRegions[0]));

struct Capsule { V3 a, b; float r = 0; bool ok = false; };

// One posed body in the world: its drawn vertices, each one's region, its capsules and its main joints.
struct Body {
    std::vector<V3> v;
    std::vector<int8_t> region;
    Capsule caps[kRegionCount];
    std::map<std::string, V3> joint;
    float minZ = 1e9f;
    float minBodyZ = 1e9f;              // the same leaving out what hangs off it (a tail, a cape: flesh.h's dropped bones)
    int lowBone = -1, lowVertex = -1;   // the lowest vertex's dominant bone and its number (AUDIT_DEBUG)
};

struct ModelInfo { std::vector<int> regionOfBone; std::vector<uint8_t> hanging; };

static const ModelInfo& infoOf(const M2Model& m) {
    static std::map<const M2Model*, ModelInfo> cache;
    auto it = cache.find(&m);
    if (it != cache.end()) return it->second;
    ModelInfo info;
    std::vector<int> named(m.bones.size(), -1);
    for (int r = 0; r < kRegionCount; ++r) { int b = m.findWowBone(kRegions[r].bone); if (b >= 0 && size_t(b) < named.size()) named[size_t(b)] = r; }
    info.regionOfBone.assign(m.bones.size(), -1);
    for (size_t b = 0; b < m.bones.size(); ++b) {
        int c = int(b), guard = 0;
        while (c >= 0 && named[size_t(c)] < 0 && guard++ < 64) c = m.bones[size_t(c)].parent;
        info.regionOfBone[b] = c >= 0 ? named[size_t(c)] : -1;
    }
    // what hangs off the body (a tail, a cape), as the flesh measure leaves it out: every geoset shown
    measureFlesh(m, m.vertices, m.indices, m.submeshes, {}, &info.hanging);
    return cache[&m] = info;
}

static bool bodyOf(const Scene& scene, int id, Body& out) {
    auto it = scene.actors_.find(id);
    if (it == scene.actors_.end() || !it->second.mesh || !it->second.mesh->model || it->second.mats.empty()) return false;
    const Scene::Actor& a = it->second;
    const M2Model& m = *a.mesh->model;
    const ModelInfo& info = infoOf(m);
    const M4 world = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    const bool shaped = !a.body.shaped.empty() && !a.body.indices.empty();
    const std::vector<M2Vertex>& verts = shaped ? a.body.shaped : m.vertices;
    const std::vector<uint32_t>& idx = shaped ? a.body.indices : m.indices;
    const std::vector<M2Submesh>& subs = shaped && !a.body.submeshes.empty() ? a.body.submeshes : m.submeshes;
    std::vector<uint8_t> used(verts.size(), 0);
    for (const M2Batch& b : m.batches) {
        if (b.submesh >= subs.size()) continue;
        const M2Submesh& sm = subs[b.submesh];
        if (!a.geosets.empty() && std::find(a.geosets.begin(), a.geosets.end(), sm.id) == a.geosets.end()) continue;
        for (uint32_t k = sm.indexStart; k < sm.indexStart + sm.indexCount && k < idx.size(); ++k) if (idx[k] < used.size()) used[idx[k]] = 1;
    }
    out = Body();
    std::vector<std::vector<V3>> byRegion(kRegionCount);
    for (size_t i = 0; i < verts.size(); ++i) {
        if (!used[i]) continue;
        const M2Vertex& vx = verts[i];
        V3 p(vx.pos[0], vx.pos[1], vx.pos[2]), s(0, 0, 0);
        float wsum = 0; int dom = -1, domW = -1;
        for (int k = 0; k < 4; ++k) {
            if (!vx.weights[k]) continue;
            size_t b = vx.bones[k];
            if (b >= a.mats.size()) continue;
            float w = vx.weights[k] / 255.0f;
            s = s + a.mats[b].point(p) * w;
            wsum += w;
            if (vx.weights[k] > domW) { domW = vx.weights[k]; dom = int(b); }
        }
        if (wsum <= 0) continue;
        V3 wp = world.point(s * (1.0f / wsum));
        int r = dom >= 0 && size_t(dom) < info.regionOfBone.size() ? info.regionOfBone[size_t(dom)] : -1;
        out.v.push_back(wp);
        out.region.push_back(int8_t(r));
        if (wp.z < out.minZ) { out.minZ = wp.z; out.lowBone = dom; out.lowVertex = int(i); }
        if (wp.z < out.minBodyZ && !(dom >= 0 && size_t(dom) < info.hanging.size() && info.hanging[size_t(dom)])) out.minBodyZ = wp.z;
        if (r >= 0) byRegion[size_t(r)].push_back(wp);
    }
    for (int r = 0; r < kRegionCount; ++r) {
        int b = m.findWowBone(kRegions[r].bone);
        if (b < 0 || size_t(b) >= a.mats.size()) continue;
        out.joint[kRegions[r].bone] = world.point(a.mats[size_t(b)].point(m.bones[size_t(b)].pivot));
    }
    for (int r = 0; r < kRegionCount; ++r) {
        const auto& pts = byRegion[size_t(r)];
        auto ja = out.joint.find(kRegions[r].bone);
        if (pts.size() < 8 || ja == out.joint.end()) continue;
        V3 a0 = ja->second, b0;
        auto jb = kRegions[r].child ? out.joint.find(kRegions[r].child) : out.joint.end();
        if (jb != out.joint.end()) b0 = jb->second;
        else {
            // toward its own vertices: the joint to their centre, carried on to their far side
            V3 c(0, 0, 0);
            for (const V3& p : pts) c = c + p;
            c = c * (1.0f / float(pts.size()));
            b0 = a0 + (c - a0) * 1.6f;
        }
        // the segment kept inside the vertices (a capsule's ends are rounded): pulled in by the radius
        std::vector<float> d;
        d.reserve(pts.size());
        for (const V3& p : pts) d.push_back(segDist(p, a0, b0));
        std::nth_element(d.begin(), d.begin() + long(d.size() * 7 / 10), d.end());
        float rad = std::min(d[d.size() * 7 / 10], 0.35f);
        out.caps[r] = Capsule{a0, b0, rad, true};
    }
    return true;
}

// How deep point p is inside body b's capsules (negative: outside), skipping its hands when [hands] is false.
static float insideDepth(const Body& b, V3 p, bool hands, int* which = nullptr) {
    float best = -1e9f;
    for (int r = 0; r < kRegionCount; ++r) {
        const Capsule& c = b.caps[r];
        if (!c.ok || (!hands && kRegions[r].hand)) continue;
        float d = c.r - segDist(p, c.a, c.b);
        if (d > best) { best = d; if (which) *which = r; }
    }
    return best;
}

// A piece of furniture's solid: over a grid, the solid stretches of each cell's vertical line, from its triangles: a stretch
// starts at an underside (a triangle facing down) and ends at a top (facing up); a top with no underside under it is solid
// down to the hit below it (or the floor): a bed's frame, a couch's base. A canopy over a bed is its own stretch.
struct Solid {
    std::string name;
    float x0 = 0, y0 = 0, cell = 0.025f;
    int nx = 0, ny = 0;
    std::vector<std::vector<std::pair<float, float>>> spans;   // per cell: (bottom, top), lowest first
    const std::vector<std::pair<float, float>>* at(float x, float y) const {
        int i = int(std::floor((x - x0) / cell)), j = int(std::floor((y - y0) / cell));
        if (i < 0 || j < 0 || i >= nx || j >= ny) return nullptr;
        const auto& v = spans[size_t(j) * nx + i];
        return v.empty() ? nullptr : &v;
    }
    // the stretch p is in, or null
    const std::pair<float, float>* inside(V3 p) const {
        const auto* v = at(p.x, p.y);
        if (v) for (const auto& s : *v) if (p.z >= s.first && p.z <= s.second) return &s;
        return nullptr;
    }
    // the top of the highest stretch at or below p (what p would rest on), else NaN
    float topUnder(V3 p, float slack) const {
        const auto* v = at(p.x, p.y);
        float best = NAN;
        if (v) for (const auto& s : *v) if (s.second <= p.z + slack) best = s.second;
        return best;
    }
};

static Solid makeSolid(const std::string& name, const std::vector<V3>& tris, float floorZ) {
    Solid s; s.name = name;
    V3 lo(1e9f, 1e9f, 1e9f), hi(-1e9f, -1e9f, -1e9f);
    for (const V3& p : tris) { lo = V3(std::min(lo.x, p.x), std::min(lo.y, p.y), std::min(lo.z, p.z)); hi = V3(std::max(hi.x, p.x), std::max(hi.y, p.y), std::max(hi.z, p.z)); }
    s.x0 = lo.x; s.y0 = lo.y;
    s.nx = std::max(1, int(std::ceil((hi.x - lo.x) / s.cell))); s.ny = std::max(1, int(std::ceil((hi.y - lo.y) / s.cell)));
    std::vector<std::vector<std::pair<float, int>>> hits(size_t(s.nx) * s.ny);
    for (size_t t = 0; t + 2 < tris.size(); t += 3) {
        const V3 &A = tris[t], &B = tris[t + 1], &C = tris[t + 2];
        V3 nrm = (B - A).cross(C - A);
        float nl = nrm.length();
        if (nl < 1e-10f || std::fabs(nrm.z) < 0.15f * nl) continue;   // standing on edge: no top, no underside
        int dir = nrm.z > 0 ? 1 : -1;
        int i0 = std::max(0, int(std::floor((std::min({A.x, B.x, C.x}) - s.x0) / s.cell)));
        int i1 = std::min(s.nx - 1, int(std::floor((std::max({A.x, B.x, C.x}) - s.x0) / s.cell)));
        int j0 = std::max(0, int(std::floor((std::min({A.y, B.y, C.y}) - s.y0) / s.cell)));
        int j1 = std::min(s.ny - 1, int(std::floor((std::max({A.y, B.y, C.y}) - s.y0) / s.cell)));
        float den = (B.y - C.y) * (A.x - C.x) + (C.x - B.x) * (A.y - C.y);
        for (int j = j0; j <= j1; ++j) for (int i = i0; i <= i1; ++i) {
            float px = s.x0 + (i + 0.5f) * s.cell, py = s.y0 + (j + 0.5f) * s.cell;
            float u = ((B.y - C.y) * (px - C.x) + (C.x - B.x) * (py - C.y)) / den;
            float v = ((C.y - A.y) * (px - C.x) + (A.x - C.x) * (py - C.y)) / den;
            float w = 1 - u - v;
            if (u < -1e-4f || v < -1e-4f || w < -1e-4f) continue;
            float z = u * A.z + v * B.z + w * C.z;
            if (z < floorZ + 0.02f) continue;   // the floor under it (a rug, a base plate) isn't the piece
            hits[size_t(j) * s.nx + i].push_back({z, dir});
        }
    }
    s.spans.resize(hits.size());
    for (size_t k = 0; k < hits.size(); ++k) {
        auto& h = hits[k];
        if (h.empty()) continue;
        std::sort(h.begin(), h.end());
        bool in = false; float start = floorZ, last = floorZ;
        for (auto& [z, d] : h) {
            if (d < 0) { if (!in) { in = true; start = z; } }
            else {
                float b = in ? start : last;
                if (z - b < 0.06f) b = z - 0.06f;   // a single sheet (a seat with no underside): a few centimetres thick
                if (!s.spans[k].empty() && b <= s.spans[k].back().second + 0.01f) s.spans[k].back().second = z;
                else s.spans[k].push_back({b, z});
                in = false;
            }
            last = z;
        }
    }
    return s;
}

// How deep p is in the solid (0: not inside): the least of the way up out of its top and the way out sideways.
static float solidDepth(const Solid& s, V3 p) {
    const auto* span = s.inside(p);
    if (!span) return 0;
    float up = span->second - p.z, side = 0.3f;
    for (int ring = 1; ring * s.cell < side; ++ring) {
        bool out = false;
        for (int k = -ring; k <= ring && !out; ++k) {
            const float offs[4][2] = {{float(k), float(-ring)}, {float(k), float(ring)}, {float(-ring), float(k)}, {float(ring), float(k)}};
            for (auto& o : offs) if (!s.inside(V3(p.x + o[0] * s.cell, p.y + o[1] * s.cell, p.z))) { out = true; break; }
        }
        if (out) { side = ring * s.cell; break; }
    }
    return std::min(up, side);
}

static void modelTris(const M2Model& m, const M4& world, std::vector<V3>& out) {
    for (size_t k = 0; k + 2 < m.indices.size(); k += 3)
        for (int c = 0; c < 3; ++c) {
            const M2Vertex& v = m.vertices[m.indices[k + size_t(c)]];
            out.push_back(world.point(V3(v.pos[0], v.pos[1], v.pos[2])));
        }
}

// ---- the run

struct Person {
    int id = 0;
    std::string look;
    bool shown = false;
};

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: audit PLAN CSV_OUT\n"); return 2; }
    EGLDisplay dpy = EGL_NO_DISPLAY;
    auto getPlatformDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (getPlatformDisplay) dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (dpy == EGL_NO_DISPLAY) dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(dpy, nullptr, nullptr)) { std::fprintf(stderr, "eglInitialize failed\n"); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n);
    EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, n ? cfg : nullptr, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) { std::fprintf(stderr, "no GLES 3 context\n"); return 1; }

    std::string dir;
    FileLookup lookup = [&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 ? p : std::string();
    };
    Scene scene(lookup);
    scene.initGl();
    scene.setSoftBody(kSoftOff);

    // one framebuffer for the measure's poses (tiny) and one per picture size
    auto target = [](int w, int h) {
        static std::map<std::pair<int, int>, unsigned> fbos;
        auto& f = fbos[{w, h}];
        if (!f) {
            unsigned color, depth;
            glGenFramebuffers(1, &f); glBindFramebuffer(GL_FRAMEBUFFER, f);
            glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
            glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, f);
        glViewport(0, 0, w, h);
    };

    std::ifstream plan(argv[1]);
    if (!plan) { std::fprintf(stderr, "no plan %s\n", argv[1]); return 1; }
    FILE* csv = std::fopen(argv[2], "w");
    if (!csv) { std::fprintf(stderr, "can't write %s\n", argv[2]); return 1; }
    std::fprintf(csv, "case,t,keys,aa_body_depth,aa_body_frac,aa_hand_depth,pelvis_dist,trunk_gap,same_axis,"
                      "f0_depth,f0_frac,f1_depth,f1_frac,float0,float1,sink0,sink1,worst_region0,worst_region1,body_sink0,body_sink1\n");

    std::map<std::string, int> props;
    std::vector<Solid> solids;
    std::map<std::string, Person> cache;          // dressed people by slot and look (dressing is the slow part)
    std::map<int, std::string> slotKey;           // slot -> the cache key it shows
    float floorZ = 0;
    std::string caseId, caseKeys;
    std::map<std::string, std::string> keys;
    const uint32_t base = 100000;                  // the case's clock 0 on the engine's clock (cases move it on)
    uint32_t clock0 = base;
    std::map<std::string, std::unique_ptr<BoneAnim>> clips;
    std::string line;
    size_t rows = 0, renders = 0;
    // frames from [from] to just before the case's clock: the poses settle
    auto warm = [&](uint32_t from) {
        target(32, 32);
        for (uint32_t t = from; t + 400 < clock0; t += 250) scene.render(32, 32, t);
    };
    auto shownSlots = [&]() {
        std::vector<std::pair<int, int>> out;
        for (auto& [slot, key] : slotKey) if (cache[key].shown) out.push_back({slot, cache[key].id});
        return out;
    };
    while (std::getline(plan, line)) {
        std::istringstream ss(line);
        std::string cmd; ss >> cmd;
        if (cmd.empty() || cmd[0] == '#') continue;
        if (cmd == "files") ss >> dir;
        else if (cmd == "location") {
            std::string path, id, view, json; ss >> path >> id >> view;
            if (readText(path, json) && !scene.loadLocation(json, id, view)) std::fprintf(stderr, "location: %s\n", scene.locationError().c_str());
        } else if (cmd == "floor") ss >> floorZ;
        else if (cmd == "prop") {
            std::string name; uint32_t model; float x, y, z, yaw, sc;
            ss >> name >> model >> x >> y >> z >> yaw >> sc;
            int id = scene.addProp(model, V3(x, y, z), yaw, sc);
            if (!id) std::fprintf(stderr, "prop %s (%u): %s\n", name.c_str(), model, scene.modelError(model).c_str());
            else props[name] = id;
        } else if (cmd == "contact") {
            std::string name, kind; ss >> name >> kind;
            V3 p; float facing = 0, half = 0;
            bool ok = props.count(name) && scene.propContact(props[name], kind == "lie" ? Scene::kLying : Scene::kSitting, p, facing, half);
            std::printf("contact %s %s %d %.4f %.4f %.4f %.4f %.4f\n", name.c_str(), kind.c_str(), ok ? 1 : 0, p.x, p.y, p.z, facing, half);
            V3 lo, hi;
            if (props.count(name) && scene.propBounds(props[name], lo, hi))
                std::printf("bounds %s %.4f %.4f %.4f %.4f %.4f %.4f\n", name.c_str(), lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);
            std::fflush(stdout);
        } else if (cmd == "solid") {
            std::string name, kind; ss >> name >> kind;
            std::vector<V3> tris;
            if (kind == "prop") {
                std::string pn; ss >> pn;
                auto it = props.count(pn) ? scene.editorProps_.find(props[pn]) : scene.editorProps_.end();
                if (it != scene.editorProps_.end() && it->second.inst.mesh && it->second.inst.mesh->model)
                    modelTris(*it->second.inst.mesh->model, it->second.inst.world, tris);
            } else if (kind == "doodad" && scene.location_) {
                uint32_t model; float x, y, z; ss >> model >> x >> y >> z;
                float best = 1e9f; const Scene::Instance* hit = nullptr;
                for (const auto& in : scene.location_->doodads) {
                    if (in.modelFileId != model || !in.mesh || !in.mesh->model) continue;
                    float d = (in.world.point(V3()) - V3(x, y, z)).length();
                    if (d < best) { best = d; hit = &in; }
                }
                for (const auto& in : scene.locExtras_) {
                    if (in.modelFileId != model || !in.mesh || !in.mesh->model) continue;
                    float d = (in.world.point(V3()) - V3(x, y, z)).length();
                    if (d < best) { best = d; hit = &in; }
                }
                if (hit && best < 0.5f) modelTris(*hit->mesh->model, hit->world, tris);
            }
            if (tris.empty()) { std::fprintf(stderr, "solid %s: no triangles\n", name.c_str()); continue; }
            solids.push_back(makeSolid(name, tris, floorZ));
            const Solid& s = solids.back();
            size_t cells = 0;
            for (auto& v : s.spans) if (!v.empty()) ++cells;
            // the stretches under its middle, as a check of what it is made of
            std::string mid;
            if (const auto* v = s.at(s.x0 + s.nx * s.cell * 0.5f, s.y0 + s.ny * s.cell * 0.5f))
                for (auto& sp : *v) { char b[64]; std::snprintf(b, sizeof b, " [%.3f %.3f]", sp.first - floorZ, sp.second - floorZ); mid += b; }
            // the surface at its middle's height (the mattress, the seat): its extent in the world (x and y)
            float topMid = NAN;
            if (const auto* v = s.at(s.x0 + s.nx * s.cell * 0.5f, s.y0 + s.ny * s.cell * 0.5f)) topMid = v->front().second;
            float lx = 1e9f, hx = -1e9f, ly = 1e9f, hy = -1e9f;
            for (int j = 0; j < s.ny; ++j) for (int i = 0; i < s.nx; ++i) for (auto& sp : s.spans[size_t(j) * s.nx + i])
                if (std::fabs(sp.second - topMid) < 0.06f) {
                    float x = s.x0 + (i + 0.5f) * s.cell, y = s.y0 + (j + 0.5f) * s.cell;
                    lx = std::min(lx, x); hx = std::max(hx, x); ly = std::min(ly, y); hy = std::max(hy, y);
                }
            std::printf("solid %s: %zu triangles, %d x %d cells (%zu covered), at its middle over the floor:%s; that surface spans x %.3f..%.3f, y %.3f..%.3f\n",
                        name.c_str(), tris.size() / 3, s.nx, s.ny, cells, mid.c_str(), lx, hx, ly, hy);
            std::fflush(stdout);
        } else if (cmd == "clearsolids") solids.clear();
        else if (cmd == "clearprops") { scene.clearProps(); props.clear(); }
        else if (cmd == "actor") {
            int slot; std::string lookPath, tgt; float xy = 0;
            ss >> slot >> lookPath >> tgt >> xy;
            std::string key = std::to_string(slot) + ":" + lookPath + ":" + tgt;
            if (slotKey.count(slot) && slotKey[slot] != key) { Person& old = cache[slotKey[slot]]; scene.showActor(old.id, false); old.shown = false; }
            Person& p = cache[key];
            if (!p.id) {
                Look lf;
                int id = readLook(lookPath, lf) ? scene.addActor(lf.model, lf.tex, lf.geo, lf.scale) : 0;
                if (id <= 0) { std::fprintf(stderr, "actor %d (%s): FAILED\n", slot, lookPath.c_str()); continue; }
                scene.setCharacter(id, lf.character);
                scene.dress(id, lf.geo, lf.layers, lf.extras);
                float stand = scene.actorStandHeight(id);
                if (tgt != "own" && stand > 0.15f && stand < 12.f) scene.setActorScale(id, std::clamp(std::strtof(tgt.c_str(), nullptr) / stand, 0.2f, 4.0f));
                p.id = id; p.look = lookPath;
            }
            scene.setHumanRootMotion(p.id, xy);
            scene.showActor(p.id, true);
            p.shown = true;
            slotKey[slot] = key;
        } else if (cmd == "flesh") {
            int slot; std::string out; ss >> slot >> out;
            if (!slotKey.count(slot)) continue;
            int id = cache[slotKey[slot]].id;
            // a frame first: the body's shape is made when it is drawn
            target(32, 32);
            scene.render(32, 32, clock0);
            auto t0 = std::chrono::steady_clock::now();
            std::vector<float> v;
            bool ok = scene.actorFlesh(id, v);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            FILE* f = std::fopen(out.c_str(), "w");
            if (!f) { std::fprintf(stderr, "flesh: can't write %s\n", out.c_str()); continue; }
            for (size_t i = 0; i < v.size(); ++i) std::fprintf(f, "%s%.5f", i ? " " : "", v[i]);
            std::fprintf(f, "\n");
            if (ok) {
                const Scene::Actor& a = scene.actors_.at(id);
                std::fprintf(f, "# %s: pelvis %.4f model units (x scale %.4f = %.4f yards), lowest %.4f, measured in %.2f ms\n",
                             cache[slotKey[slot]].look.c_str(), v[1], a.scale, v[1] * a.scale, v[2], ms);
                for (int s = 0; s < kFleshCount; ++s) {
                    const float* q = &v[size_t(4 + 7 * s)];
                    std::fprintf(f, "# %-11s ok %d front %.4f back %.4f side %.4f start %.4f end %.4f tip %.4f (body units)\n",
                                 kFleshSegs[s].name, int(q[0]), q[1], q[2], q[3], q[4], q[5], q[6]);
                }
            } else std::fprintf(f, "# not measured\n");
            std::fclose(f);
            std::fprintf(stderr, "flesh slot %d: %s in %.2f ms\n", slot, ok ? "measured" : "NOT measured", ms);
        } else if (cmd == "hide") {
            int slot; ss >> slot;
            if (slotKey.count(slot)) { Person& p = cache[slotKey[slot]]; scene.showActor(p.id, false); p.shown = false; }
        } else if (cmd == "place") {
            // `raw` at the end: at Z as given (Scene::kRaised, as the app's sink places a clip raised onto furniture);
            // otherwise stood on the floor under them
            int slot; float x, y, z, f; std::string raw; ss >> slot >> x >> y >> z >> f >> raw;
            if (slotKey.count(slot)) {
                int id = cache[slotKey[slot]].id;
                scene.placeActor(id, V3(x, y, z), f, raw == "raw" ? Scene::kRaised : Scene::kOnFloor);
            }
        } else if (cmd == "clip" || cmd == "emote" || cmd == "still") {
            int slot; ss >> slot;
            if (!slotKey.count(slot)) continue;
            int id = cache[slotKey[slot]].id;
            if (cmd == "clip") {
                std::string path; ss >> path;
                auto& c = clips[path];
                if (!c) { c = std::make_unique<BoneAnim>(); readBoneAnim(path, *c); }
                scene.setBoneAnim(id, *c, clock0 - 3000);
                warm(clock0 - 3000);
                scene.setBoneAnim(id, *c, clock0);
            } else if (cmd == "emote") {
                int anim = 0, loop = 0; ss >> anim >> loop;
                scene.clearBoneAnim(id);
                // its keys come from their own file the first time, and the last case's pose blends away: played for 4.5 s
                // before the case's clock (as the app's waiting actor would go on), then again from the case's start
                scene.playAnim(id, uint16_t(anim), loop != 0, clock0 - 5000);
                warm(clock0 - 5000);
                scene.playAnim(id, uint16_t(anim), loop != 0, clock0);
            } else {
                scene.clearBoneAnim(id);
                scene.playAnim(id, 0, true, clock0);
            }
        } else if (cmd == "case") {
            ss >> caseId;
            std::string rest; std::getline(ss, rest);
            keys.clear();
            for (auto& kv : split(rest, ' ')) { auto p = kv.find('='); if (p != std::string::npos) keys[kv.substr(0, p)] = kv.substr(p + 1); }
            caseKeys.clear();
            for (auto& [k, v] : keys) caseKeys += (caseKeys.empty() ? "" : ";") + k + "=" + v;
            clock0 += 60000;
            // the poses settle: an engine frame at the case's start (the blend from the last case's pose ends by the measure)
        } else if (cmd == "measure") {
            uint32_t t; ss >> t;
            target(32, 32);
            // the blend into a new animation takes up to 0.4 s: a frame well before, then the moment itself
            // (the plan measures from 500 ms on: a change of animation blends for up to 0.4 s)
            scene.render(32, 32, clock0 + t - std::min<uint32_t>(t, 450));
            scene.render(32, 32, clock0 + t);
            auto slots = shownSlots();
            Body b[2]; bool have[2] = {false, false};
            for (auto& [slot, id] : slots) if (slot >= 0 && slot < 2) have[slot] = bodyOf(scene, id, b[slot]);
            if (std::getenv("AUDIT_DEBUG"))
                for (int s = 0; s < 2; ++s) if (have[s]) {
                    const Scene::Actor& a = scene.actors_.at(slots[size_t(s)].second);
                    const M2Model& m = *a.mesh->model;
                    int sub = -1;
                    for (const M2Submesh& sm : m.submeshes)
                        for (uint32_t k = sm.indexStart; k < sm.indexStart + sm.indexCount && k < m.indices.size(); ++k)
                            if (int(m.indices[k]) == b[s].lowVertex) sub = sm.id;
                    std::fprintf(stderr, "debug %s slot %d: lowest %.3f over the floor, bone %d (key %d, parent %d), geoset %d; pelvis %.3f\n", caseId.c_str(), s,
                                 b[s].minZ - floorZ, b[s].lowBone, b[s].lowBone >= 0 ? m.bones[size_t(b[s].lowBone)].keyBoneId : -9,
                                 b[s].lowBone >= 0 ? m.bones[size_t(b[s].lowBone)].parent : -9, sub,
                                 b[s].joint.count("Pelvis") ? b[s].joint["Pelvis"].z - floorZ : -9.0f);
                }
            float aaBody = 0, aaFrac = 0, aaHand = 0, pelvis = NAN, trunk = NAN; int same = 0;
            if (have[0] && have[1]) {
                size_t inside = 0, total = 0;
                for (int s = 0; s < 2; ++s) {
                    const Body& me = b[s]; const Body& other = b[1 - s];
                    for (size_t i = 0; i < me.v.size(); ++i) {
                        int r = me.region[i];
                        bool hand = r >= 0 && kRegions[r].hand;
                        float d = insideDepth(other, me.v[i], false);
                        if (hand) { aaHand = std::max(aaHand, d); continue; }
                        ++total;
                        if (d > 0.02f) ++inside;
                        aaBody = std::max(aaBody, d);
                    }
                }
                aaFrac = total ? float(inside) / float(total) : 0;
                auto p0 = b[0].joint.find("Pelvis"), p1 = b[1].joint.find("Pelvis");
                if (p0 == b[0].joint.end()) p0 = b[0].joint.find("Root");
                if (p1 == b[1].joint.end()) p1 = b[1].joint.find("Root");
                if (p0 != b[0].joint.end() && p1 != b[1].joint.end()) pelvis = std::hypot(p0->second.x - p1->second.x, p0->second.y - p1->second.y);
                // the trunks (pelvis to neck): how far apart, less both trunks' radii
                auto trunkOf = [](const Body& x, V3& lo, V3& hi, float& r) {
                    auto a = x.joint.find("Root"), c = x.joint.find("Neck1");
                    if (a == x.joint.end() || c == x.joint.end()) return false;
                    lo = a->second; hi = c->second; r = 0; int k = 0;
                    for (int i = 0; i < kRegionCount; ++i) if (kRegions[i].trunk && x.caps[i].ok) { r += x.caps[i].r; ++k; }
                    r = k ? r / k : 0.15f;
                    return true;
                };
                V3 a0, a1, b0, b1; float ra = 0, rb = 0;
                if (trunkOf(b[0], a0, a1, ra) && trunkOf(b[1], b0, b1, rb)) trunk = segSegDist(a0, a1, b0, b1) - ra - rb;
                same = (!std::isnan(pelvis) && pelvis < 0.25f) || (!std::isnan(trunk) && trunk < -0.12f) ? 1 : 0;
            }
            float fd[2] = {0, 0}, ff[2] = {0, 0}, fl[2] = {NAN, NAN}, sk[2] = {0, 0}, bsk[2] = {0, 0};
            std::string worst[2];
            for (int s = 0; s < 2; ++s) {
                if (!have[s]) continue;
                const Body& me = b[s];
                size_t in = 0; float deepest = 0; int deepRegion = -1;
                for (size_t i = 0; i < me.v.size(); ++i)
                    for (const Solid& so : solids) {
                        float d = solidDepth(so, me.v[i]);
                        if (d > 0.015f) ++in;
                        if (d > deepest) { deepest = d; deepRegion = me.region[i]; }
                    }
                fd[s] = deepest; ff[s] = me.v.empty() ? 0 : float(in) / float(me.v.size());
                worst[s] = deepRegion >= 0 ? kRegions[deepRegion].bone : "";
                sk[s] = std::max(0.0f, floorZ - me.minZ);
                bsk[s] = std::max(0.0f, floorZ - me.minBodyZ);
                // a resting body: the gap between it and whatever it rests on (the furniture's top under it, else the floor)
                std::string rest = keys.count("rest" + std::to_string(s)) ? keys["rest" + std::to_string(s)] : "stand";
                float gap = me.minZ - floorZ;
                if (rest != "stand") {
                    for (size_t i = 0; i < me.v.size(); ++i)
                        for (const Solid& so : solids) {
                            float t = so.topUnder(me.v[i], 0.02f);
                            if (!std::isnan(t)) gap = std::min(gap, me.v[i].z - t);
                        }
                }
                fl[s] = gap;
            }
            std::fprintf(csv, "%s,%u,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%.4f,%.4f\n",
                         caseId.c_str(), t, caseKeys.c_str(), aaBody, aaFrac, aaHand, pelvis, trunk, same,
                         fd[0], ff[0], fd[1], ff[1], fl[0], fl[1], sk[0], sk[1], worst[0].c_str(), worst[1].c_str(), bsk[0], bsk[1]);
            ++rows;
        } else if (cmd == "camera") {
            float ex, ey, ez, tx, ty, tz, fov; ss >> ex >> ey >> ez >> tx >> ty >> tz >> fov;
            scene.setCamera(V3(ex, ey, ez), V3(tx, ty, tz), fov);
        } else if (cmd == "render") {
            std::string out; int w, h; uint32_t t; ss >> out >> w >> h >> t;
            target(w, h);
            // a few frames first: the skins are composed over the first frames, and the blend into the clip settles
            for (int k = 0; k < 3; ++k) scene.render(w, h, clock0 + t - std::min<uint32_t>(t, 450) + 100 * k);
            scene.render(w, h, clock0 + t);
            std::vector<uint8_t> px(size_t(w) * h * 4), rgb(size_t(w) * h * 3);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
            for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) for (int c = 0; c < 3; ++c)
                rgb[(size_t(h - 1 - y) * w + x) * 3 + c] = px[(size_t(y) * w + x) * 4 + c];
            writePng(out, w, h, rgb);
            ++renders;
        }
    }
    std::fclose(csv);
    std::fprintf(stderr, "audit: %zu rows, %zu pictures (GL error %d)\n", rows, renders, int(glGetError()));
    return 0;
}
