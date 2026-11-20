#include "modelpart.h"

#include "blp.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <sys/stat.h>
#include <tuple>

namespace wc {

namespace {

// A bone by WoW-side name, or "#N" for the bone with key id N (a cape's chain has no WoW-side name).
int findBone(const M2Model& m, const std::string& name) {
    if (!name.empty() && name[0] == '#') {
        int key = std::atoi(name.c_str() + 1);
        for (size_t i = 0; i < m.bones.size(); ++i) if (m.bones[i].keyBoneId == key) return int(i);
        return -1;
    }
    return m.findWowBone(name);
}

V3 pivotOf(const M2Model& m, int b) { return b >= 0 && size_t(b) < m.bones.size() ? m.bones[size_t(b)].pivot : V3(); }

// The bone's axis: from its pivot toward its child with the most bones under it (the upper arm's forearm, not a twist).
bool boneAxis(const M2Model& m, int b, V3& a, V3& c) {
    std::vector<int> under(m.bones.size(), 0);
    for (size_t i = 0; i < m.bones.size(); ++i)
        for (int p = m.bones[i].parent, guard = 0; p >= 0 && size_t(p) < m.bones.size() && guard < 256; p = m.bones[size_t(p)].parent, ++guard)
            ++under[size_t(p)];
    int best = -1;
    for (size_t i = 0; i < m.bones.size(); ++i)
        if (m.bones[i].parent == b && (best < 0 || under[i] > under[size_t(best)])) best = int(i);
    if (best < 0) return false;
    a = pivotOf(m, b);
    c = pivotOf(m, best);
    return (c - a).length() > 1e-5f;
}

float segmentDistance(V3 p, V3 a, V3 c) {
    V3 d = c - a;
    float t = d.dot(d) > 0 ? std::clamp((p - a).dot(d) / d.dot(d), 0.0f, 1.0f) : 0.0f;
    return (p - (a + d * t)).length();
}

PartRegion region(const char* name, const char* attach, std::vector<std::string> roots, std::vector<std::string> stops,
                  float standoff, float reach, const char* a, const char* b) {
    PartRegion r;
    r.name = name; r.attach = attach; r.roots = std::move(roots); r.stops = std::move(stops);
    r.standoff = standoff; r.reach = reach; r.scaleA = a; r.scaleB = b;
    return r;
}

std::string jsonString(const std::string& s) {
    std::string o = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if (uint8_t(c) < 0x20) { char buf[8]; std::snprintf(buf, sizeof buf, "\\u%04x", c); o += buf; }
        else o += c;
    }
    return o + "\"";
}

std::vector<std::string> strings(const Json& j) {
    std::vector<std::string> out;
    for (size_t i = 0; i < j.size(); ++i) if (j[i].kind == Json::String) out.push_back(j[i].s);
    return out;
}

} // namespace

const std::map<std::string, PartRegion>& defaultPartRegions() {
    static const std::map<std::string, PartRegion> regions = [] {
        std::map<std::string, PartRegion> r;
        // the head, its hair, helm, horns, face and eyes: everything the Head bone carries
        r["head"] = region("head", "Head", {"Head"}, {}, 0, -1, "Head", "Pelvis");
        // a pauldron: the shoulder's own helper bones, and the clavicle's and upper arm's vertices standing off the arm
        // (a shell, not the skin or a sleeve) no lower than a third of a torso below the clavicle
        r["shoulder_l"] = region("shoulder_l", "L_Clavicle", {"L_Clavicle"}, {"L_Forearm"}, 0.12f, 0.30f, "L_UpperArm", "R_UpperArm");
        r["shoulder_r"] = region("shoulder_r", "R_Clavicle", {"R_Clavicle"}, {"R_Forearm"}, 0.12f, 0.30f, "L_UpperArm", "R_UpperArm");
        r["shoulder_l"].side = r["shoulder_r"].side = 0.18f;
        r["shoulder_l"].islands = r["shoulder_r"].islands = 40;
        // gloves: the hand and its fingers (the stand-in's hands would need cutting too)
        r["hand_l"] = region("hand_l", "L_Hand", {"L_Hand"}, {}, 0, -1, "L_UpperArm", "R_UpperArm");
        r["hand_r"] = region("hand_r", "R_Hand", {"R_Hand"}, {}, 0, -1, "L_UpperArm", "R_UpperArm");
        // a cloak on the cape's chain (key bone 36 and under), rigid on the chest: it won't swing
        r["cloak"] = region("cloak", "Chest", {"#36"}, {}, 0, -1, "L_UpperArm", "R_UpperArm");
        return r;
    }();
    return regions;
}

std::vector<bool> boneSubtrees(const M2Model& m, const std::vector<std::string>& names) {
    std::vector<bool> root(m.bones.size(), false), out(m.bones.size(), false);
    for (const std::string& n : names) {
        int b = findBone(m, n);
        if (b >= 0 && size_t(b) < root.size()) root[size_t(b)] = true;
    }
    for (size_t i = 0; i < m.bones.size(); ++i) {
        int b = int(i);
        for (int guard = 0; b >= 0 && size_t(b) < m.bones.size() && guard < 256; b = m.bones[size_t(b)].parent, ++guard)
            if (root[size_t(b)]) { out[i] = true; break; }
    }
    return out;
}

float torsoLength(const M2Model& m) {
    int h = m.findWowBone("Head"), p = m.findWowBone("Pelvis");
    if (p < 0) p = m.findWowBone("Root");
    if (h < 0 || p < 0) return 1.0f;
    float d = (pivotOf(m, h) - pivotOf(m, p)).length();
    return d > 1e-4f ? d : 1.0f;
}

std::vector<bool> regionVertices(const M2Model& m, const PartRegion& r, float torso, const std::vector<M2Vertex>* vertices) {
    const std::vector<M2Vertex>& verts = vertices ? *vertices : m.vertices;
    if (torso <= 0) torso = torsoLength(m);
    std::vector<bool> in = boneSubtrees(m, r.roots), stop = boneSubtrees(m, r.stops);
    std::vector<int> roots;
    for (const std::string& n : r.roots) roots.push_back(findBone(m, n));
    // per bone: 0 out, 1 helper (counts), 2 named (counts by standoff and reach)
    std::vector<uint8_t> cls(m.bones.size(), 0);
    for (size_t i = 0; i < m.bones.size(); ++i) {
        if (!in[i] || stop[i]) continue;
        bool helper = false;
        for (int b = int(i), guard = 0; b >= 0 && size_t(b) < m.bones.size() && in[size_t(b)] && guard < 256; b = m.bones[size_t(b)].parent, ++guard) {
            bool isRoot = std::find(roots.begin(), roots.end(), b) != roots.end();
            if (!isRoot && m.bones[size_t(b)].keyBoneId < 0) { helper = true; break; }
        }
        cls[i] = helper ? (r.helpers ? 1 : 0) : (r.named ? 2 : 0);
    }
    int attach = findBone(m, r.attach);
    float floorZ = r.reach >= 0 && attach >= 0 ? pivotOf(m, attach).z - r.reach * torso : -1e9f;
    // a helper hung lower than the reach (a sleeve's or a tasset's bone) is not the region's, nor anything under it
    for (size_t i = 0; i < m.bones.size(); ++i)
        if (cls[i] == 1)
            for (int b = int(i), guard = 0; b >= 0 && size_t(b) < m.bones.size() && cls[size_t(b)] == 1 && guard < 256; b = m.bones[size_t(b)].parent, ++guard)
                if (pivotOf(m, b).z < floorZ) { cls[i] = 0; break; }
    std::map<int, std::pair<V3, V3>> axes;
    V3 origin = pivotOf(m, attach);
    bool boxed = r.box.size() == 6;
    std::vector<bool> out(verts.size(), false);
    for (size_t v = 0; v < verts.size(); ++v) {
        int b = dominantBone(verts[v]);
        if (b < 0 || size_t(b) >= cls.size() || !cls[size_t(b)]) continue;
        if (boxed) {
            float q[3] = {(verts[v].pos[0] - origin.x) / torso, (verts[v].pos[1] - origin.y) / torso, (verts[v].pos[2] - origin.z) / torso};
            if (q[0] < r.box[0] || q[0] > r.box[1] || q[1] < r.box[2] || q[1] > r.box[3] || q[2] < r.box[4] || q[2] > r.box[5]) continue;
        }
        if (cls[size_t(b)] == 2) {
            V3 p(verts[v].pos[0], verts[v].pos[1], verts[v].pos[2]);
            if (p.z < floorZ) continue;
            if (r.side > 0 && std::fabs(p.y) < r.side * torso) continue;
            if (r.standoff > 0) {
                auto ax = axes.find(b);
                if (ax == axes.end()) {
                    V3 a, c;
                    if (!boneAxis(m, b, a, c)) a = c = pivotOf(m, b);
                    ax = axes.emplace(b, std::make_pair(a, c)).first;
                }
                if (segmentDistance(p, ax->second.first, ax->second.second) < r.standoff * torso) continue;
            }
        }
        out[v] = true;
    }
    return out;
}

namespace {

// The inverse of a 4x4 matrix (Gauss-Jordan with partial pivoting); the identity when it is singular.
M4 inverse(const M4& a) {
    float m[4][8];
    for (int r = 0; r < 4; ++r)
        for (int c = 0; c < 4; ++c) { m[r][c] = a.m[c * 4 + r]; m[r][c + 4] = r == c ? 1.0f : 0.0f; }
    for (int c = 0; c < 4; ++c) {
        int piv = c;
        for (int r = c + 1; r < 4; ++r) if (std::fabs(m[r][c]) > std::fabs(m[piv][c])) piv = r;
        if (std::fabs(m[piv][c]) < 1e-12f) return M4();
        if (piv != c) for (int k = 0; k < 8; ++k) std::swap(m[c][k], m[piv][k]);
        float d = m[c][c];
        for (int k = 0; k < 8; ++k) m[c][k] /= d;
        for (int r = 0; r < 4; ++r) {
            if (r == c) continue;
            float f = m[r][c];
            if (f != 0) for (int k = 0; k < 8; ++k) m[r][k] -= f * m[c][k];
        }
    }
    M4 out;
    for (int r = 0; r < 4; ++r) for (int c = 0; c < 4; ++c) out.m[c * 4 + r] = m[r][c + 4];
    return out;
}

int findSet(std::vector<uint32_t>& parent, uint32_t i) {
    while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
    return int(i);
}

} // namespace

M4 invertMatrix(const M4& m) { return inverse(m); }

size_t extractRegion(const M2Model& m, const std::vector<uint16_t>& geosets, const PartRegion& r,
                     const std::map<uint32_t, uint32_t>& textures, const std::string& prefix, std::vector<PartData>& out,
                     const std::vector<M4>* pose) {
    std::vector<bool> mask = regionVertices(m, r);
    bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    int attach = findBone(m, r.attach);
    V3 anchor = pivotOf(m, attach);
    int sa = findBone(m, r.scaleA), sb = findBone(m, r.scaleB);
    bool posed = pose && attach >= 0 && size_t(attach) < pose->size() && pose->size() >= m.bones.size();
    M4 back = posed ? inverse((*pose)[size_t(attach)]) : M4();
    // each vertex where the part keeps it: posed (skinned as it stands, then back into its attach bone's bind frame), or bind
    auto placeVertex = [&](const M2Vertex& v, V3& p, V3& nrm) {
        p = V3(v.pos[0], v.pos[1], v.pos[2]);
        nrm = V3(v.normal[0], v.normal[1], v.normal[2]);
        if (!posed) return;
        V3 sp, sn;
        float wsum = 0;
        for (int k = 0; k < 4; ++k) {
            if (!v.weights[k] || v.bones[k] >= pose->size()) continue;
            float w = v.weights[k] / 255.0f;
            const M4& bm = (*pose)[v.bones[k]];
            sp = sp + bm.point(p) * w;
            sn = sn + (bm.point(nrm) - bm.point(V3())) * w;
            wsum += w;
        }
        if (wsum <= 0) return;
        sp = sp * (1.0f / wsum);
        p = back.point(sp);
        nrm = (back.point(sn) - back.point(V3())).normalized();
    };
    // the region's triangles in every shown batch: (batch, first index)
    struct Tri { size_t batch; uint32_t at; };
    std::vector<Tri> tris;
    std::vector<uint32_t> fids(m.batches.size(), 0);
    for (size_t bi = 0; bi < m.batches.size(); ++bi) {
        const M2Batch& bt = m.batches[bi];
        if (bt.submesh >= m.submeshes.size() || bt.textureCombo >= m.textureCombos.size()) continue;
        const M2Submesh& sm = m.submeshes[bt.submesh];
        if (!all && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        uint16_t ti = m.textureCombos[bt.textureCombo];
        if (ti >= m.textures.size()) continue;
        const M2Texture& tex = m.textures[ti];
        uint32_t fid = tex.fileId;
        if (tex.type != 0) { auto it = textures.find(tex.type); fid = it != textures.end() ? it->second : 0; }
        if (!fid) continue;
        fids[bi] = fid;
        for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount && t + 2 < m.indices.size(); t += 3) {
            uint32_t ix[3] = {m.indices[t], m.indices[t + 1], m.indices[t + 2]};
            if (ix[0] >= m.vertices.size() || ix[1] >= m.vertices.size() || ix[2] >= m.vertices.size()) continue;
            int count = int(mask[ix[0]]) + int(mask[ix[1]]) + int(mask[ix[2]]);
            if (count >= std::max(1, r.corners)) tris.push_back({bi, t});
        }
    }
    // pieces: triangles joined by a corner at the same place (a seam's split vertices too); the small ones dropped
    std::vector<bool> keep(tris.size(), true);
    if (r.islands > 1 && !tris.empty()) {
        std::map<std::tuple<int, int, int>, uint32_t> at;
        std::vector<uint32_t> parent(tris.size());
        for (uint32_t i = 0; i < parent.size(); ++i) parent[i] = i;
        for (uint32_t i = 0; i < tris.size(); ++i)
            for (int k = 0; k < 3; ++k) {
                const float* q = m.vertices[m.indices[tris[i].at + k]].pos;
                auto key = std::make_tuple(int(std::lround(q[0] * 2e4f)), int(std::lround(q[1] * 2e4f)), int(std::lround(q[2] * 2e4f)));
                auto f = at.find(key);
                if (f == at.end()) at.emplace(key, i);
                else { int a = findSet(parent, i), b = findSet(parent, f->second); if (a != b) parent[size_t(a)] = uint32_t(b); }
            }
        std::map<int, size_t> size;
        for (uint32_t i = 0; i < tris.size(); ++i) ++size[findSet(parent, i)];
        for (uint32_t i = 0; i < tris.size(); ++i) keep[i] = size[findSet(parent, i)] >= size_t(r.islands);
    }
    size_t taken = 0;
    int n = 0;
    for (size_t bi = 0; bi < m.batches.size(); ++bi) {
        if (!fids[bi]) continue;
        const M2Batch& bt = m.batches[bi];
        PartData p;
        std::map<uint32_t, uint32_t> place;
        for (size_t i = 0; i < tris.size(); ++i) {
            if (tris[i].batch != bi || !keep[i]) continue;
            for (int k = 0; k < 3; ++k) {
                uint32_t g = m.indices[tris[i].at + k];
                auto f = place.find(g);
                if (f == place.end()) {
                    const M2Vertex& v = m.vertices[g];
                    V3 q, nrm;
                    placeVertex(v, q, nrm);
                    f = place.emplace(g, uint32_t(p.positions.size() / 3)).first;
                    p.positions.insert(p.positions.end(), {q.x - anchor.x, q.y - anchor.y, q.z - anchor.z});
                    p.normals.insert(p.normals.end(), {nrm.x, nrm.y, nrm.z});
                    p.uvs.insert(p.uvs.end(), {v.uv[0], v.uv[1]});
                }
                p.indices.push_back(f->second);
            }
            ++taken;
        }
        if (p.indices.empty()) continue;
        M2Material mat = bt.material < m.materials.size() ? m.materials[bt.material] : M2Material();
        p.flags = kPartModelFrame | 1 | 2;
        p.version = kPartVersion2;
        p.anchorSims = anchor;
        p.attachBone = r.attach;
        p.id = prefix + r.name + "_" + std::to_string(n++);
        p.state = "MODEL";
        p.variant = r.name;
        p.textureFileId = fids[bi];
        p.blend = mat.blend;
        p.materialFlags = mat.flags;
        p.refs = {{r.scaleA, pivotOf(m, sa)}, {r.scaleB, pivotOf(m, sb)}};
        out.push_back(std::move(p));
    }
    return taken;
}

std::vector<uint32_t> cutTriangles(const std::vector<uint32_t>& indices, std::vector<M2Submesh>& subs,
                                   const std::vector<bool>& mask, int corners) {
    std::vector<uint32_t> out;
    out.reserve(indices.size());
    for (M2Submesh& sm : subs) {
        uint32_t start = uint32_t(out.size());
        for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount && t + 2 < indices.size(); t += 3) {
            int count = 0;
            for (int k = 0; k < 3; ++k) { uint32_t i = indices[t + k]; if (i < mask.size() && mask[i]) ++count; }
            if (count >= corners) continue;
            out.insert(out.end(), {indices[t], indices[t + 1], indices[t + 2]});
        }
        sm.indexStart = start;
        sm.indexCount = uint32_t(out.size()) - start;
    }
    return out;
}

std::vector<bool> faceVertices(const M2Model& m) {
    int h = m.findWowBone("Head"), j = m.findWowBone("Jaw");
    std::vector<bool> out(m.vertices.size(), false);
    if (h < 0) return out;
    V3 hp = pivotOf(m, h);
    float t = torsoLength(m);
    for (size_t v = 0; v < m.vertices.size(); ++v) {
        const M2Vertex& x = m.vertices[v];
        int b = dominantBone(x);
        if (b != h && b != j) continue;
        if (x.pos[2] < hp.z - 0.06f * t || x.pos[2] > hp.z + 0.09f * t) continue;
        if (x.pos[0] < hp.x + 0.12f * t || std::fabs(x.pos[1]) > 0.08f * t || x.normal[0] < 0.5f) continue;
        out[v] = true;
    }
    return out;
}

uint32_t maskUvs(const M2Model& m, const std::vector<uint16_t>& geosets, const std::vector<bool>& mask,
                 const std::function<uint32_t(const M2Texture&)>& accept, std::vector<float>& uvs) {
    uvs.clear();
    bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    std::map<uint32_t, std::vector<uint32_t>> byTexture;
    for (const M2Batch& bt : m.batches) {
        if (bt.submesh >= m.submeshes.size() || bt.textureCombo >= m.textureCombos.size()) continue;
        if (bt.material < m.materials.size() && m.materials[bt.material].blend >= 2) continue;
        const M2Submesh& sm = m.submeshes[bt.submesh];
        if (!all && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        uint16_t ti = m.textureCombos[bt.textureCombo];
        if (ti >= m.textures.size()) continue;
        uint32_t key = accept(m.textures[ti]);
        if (!key) continue;
        std::vector<uint32_t>& list = byTexture[key];
        for (uint32_t i = sm.indexStart; i < sm.indexStart + sm.indexCount && i < m.indices.size(); ++i) {
            uint32_t v = m.indices[i];
            if (v < m.vertices.size() && v < mask.size() && mask[v]) list.push_back(v);
        }
    }
    uint32_t best = 0;
    size_t most = 0;
    for (auto& [key, list] : byTexture) {
        std::sort(list.begin(), list.end());
        list.erase(std::unique(list.begin(), list.end()), list.end());
        if (list.size() > most) { most = list.size(); best = key; }
    }
    if (best) for (uint32_t v : byTexture[best]) { uvs.push_back(m.vertices[v].uv[0]); uvs.push_back(m.vertices[v].uv[1]); }
    return best;
}

int meanColourAt(const uint8_t* rgba, int w, int h, const std::vector<float>& uvs) {
    auto lin = [](uint8_t c) { float f = c / 255.0f; return f <= 0.04045f ? f / 12.92f : std::pow((f + 0.055f) / 1.055f, 2.4f); };
    auto srgb = [](double f) { f = std::clamp(f, 0.0, 1.0); double s = f <= 0.0031308 ? f * 12.92 : 1.055 * std::pow(f, 1 / 2.4) - 0.055; return int(std::lround(s * 255)); };
    double r = 0, g = 0, b = 0;
    long n = 0;
    for (size_t i = 0; i + 1 < uvs.size(); i += 2) {
        float u = uvs[i] - std::floor(uvs[i]), v = uvs[i + 1] - std::floor(uvs[i + 1]);
        int cx = std::min(w - 1, int(u * w)), cy = std::min(h - 1, int(v * h));
        for (int y = std::max(0, cy - 1); y <= std::min(h - 1, cy + 1); ++y)
            for (int x = std::max(0, cx - 1); x <= std::min(w - 1, cx + 1); ++x) {
                const uint8_t* px = rgba + (size_t(y) * w + x) * 4;
                r += lin(px[0]); g += lin(px[1]); b += lin(px[2]); ++n;
            }
    }
    if (!n) return -1;
    return srgb(r / n) << 16 | srgb(g / n) << 8 | srgb(b / n);
}

BonePartPlacement placeBonePart(const PartData& p, const M2Model& body, const BonePartFit& fit) {
    BonePartPlacement pl;
    pl.bone = findBone(body, p.attachBone);
    if (pl.bone < 0 || size_t(pl.bone) >= body.bones.size()) return pl;
    pl.source = p.anchorSims;
    float ratio = 1, dt = 0;
    if (p.refs.size() >= 2) {
        int a = findBone(body, p.refs[0].first), b = findBone(body, p.refs[1].first);
        float ds = (p.refs[0].second - p.refs[1].second).length();
        if (a >= 0 && b >= 0) dt = (pivotOf(body, a) - pivotOf(body, b)).length();
        if (ds > 1e-4f && dt > 1e-4f) ratio = dt / ds;
    }
    if (dt <= 1e-4f) dt = torsoLength(body);
    pl.scale = ratio * (fit.scale > 0 ? fit.scale : 1.0f);
    pl.target = pivotOf(body, pl.bone) + fit.offset * dt;
    pl.ok = std::isfinite(pl.scale) && std::isfinite(pl.target.x) && std::isfinite(pl.target.z);
    return pl;
}

M4 bonePartMatrix(const M4& bone, const BonePartPlacement& pl) {
    return bone * M4::translation(pl.target) * M4::scaling(V3(pl.scale, pl.scale, pl.scale)) * M4::translation(V3() - pl.source);
}

bool parseModelPartManifest(const std::string& text, ModelPartManifest& out, std::string& error) {
    out = ModelPartManifest();
    out.regions = defaultPartRegions();
    Json j;
    if (!Json::parse(text, j, &error)) return false;
    if (j.kind != Json::Object) { error = "is not an object"; return false; }
    for (const auto& [name, rj] : j["regions"].o) {
        PartRegion r = out.regions.count(name) ? out.regions[name] : PartRegion();
        r.name = name;
        if (rj.has("attach")) r.attach = rj["attach"].str();
        if (rj.has("roots")) r.roots = strings(rj["roots"]);
        if (rj.has("stops")) r.stops = strings(rj["stops"]);
        if (rj.has("helpers")) r.helpers = rj["helpers"].num(1) != 0;
        if (rj.has("named")) r.named = rj["named"].num(1) != 0;
        if (rj.has("standoff")) r.standoff = float(rj["standoff"].num());
        if (rj.has("reach")) r.reach = float(rj["reach"].num(-1));
        if (rj.has("side")) r.side = float(rj["side"].num());
        if (rj.has("islands")) r.islands = int(rj["islands"].num());
        if (rj["box"].size() == 6) { r.box.clear(); for (size_t k = 0; k < 6; ++k) r.box.push_back(float(rj["box"][k].num())); }
        if (rj.has("corners")) r.corners = int(rj["corners"].num(1));
        if (rj["scale"].size() == 2) { r.scaleA = rj["scale"][0].str(); r.scaleB = rj["scale"][1].str(); }
        if (r.attach.empty() || r.roots.empty()) { error = "region " + name + " has no attach bone or roots"; return false; }
        out.regions[name] = r;
    }
    const Json& sets = j["sets"];
    for (size_t i = 0; i < sets.size(); ++i) {
        const Json& sj = sets[i];
        ModelPartSet s;
        s.set = sj["set"].str();
        s.character = sj["character"].str();
        s.look = sj["look"].str();
        s.display = uint32_t(sj["display"].num());
        s.model = uint32_t(sj["model"].num());
        s.standIn = uint32_t(sj["standIn"].num());
        s.standInLook = sj["standInLook"].str();
        for (const auto& [k, v] : sj["textures"].o) s.textures[uint32_t(std::atoi(k.c_str()))] = uint32_t(v.num());
        for (size_t g = 0; g < sj["geosets"].size(); ++g) s.geosets.push_back(uint16_t(sj["geosets"][g].num()));
        s.regions = strings(sj["regions"]);
        s.skinMatch = sj["skinMatch"].num(1) != 0;
        s.cut = sj.has("cut") ? strings(sj["cut"]) : std::vector<std::string>{"Head"};
        for (const auto& [k, f] : sj["fit"].o) {
            BonePartFit bf;
            if (f["offset"].size() == 3) bf.offset = V3(float(f["offset"][0].num()), float(f["offset"][1].num()), float(f["offset"][2].num()));
            bf.scale = float(f["scale"].num(1));
            s.fits[k] = bf;
        }
        bool safe = !s.set.empty() && s.set.find('/') == std::string::npos && s.set.find("..") == std::string::npos;
        if (!safe || !s.model || s.regions.empty()) { error = "set " + std::to_string(i) + " needs a set name (no slashes), a model and regions"; return false; }
        for (const std::string& r : s.regions)
            if (!out.regions.count(r)) { error = "set " + s.set + " names region " + r + ", which isn't defined"; return false; }
        out.sets.push_back(std::move(s));
    }
    return true;
}

bool buildModelPartSet(const ModelPartSet& s, const ModelPartManifest& man, const FileLookup& files, const std::string& dir,
                       std::string& report) {
    M2Model m;
    if (files(s.model).empty()) { report = "set " + s.set + ": model " + std::to_string(s.model) + " isn't downloaded"; return false; }
    if (!m.load(s.model, files)) { report = "set " + s.set + ": " + m.error; return false; }
    // the source standing (its Stand's first moment): helper bones (hair, a pauldron's, a shoulder's wings) as she is seen,
    // not as modelled; the bind pose when the animation isn't downloaded
    std::vector<M4> mats;
    bool posed = false;
    int stand = m.findSequence(0);
    if (stand >= 0) {
        m.ensureSequence(stand, files);
        if (m.sequenceReady(stand)) {
            std::vector<BonePose> poses;
            m.pose(stand, 0, 0, poses);
            m.bonesToMatrices(poses, mats);
            posed = mats.size() >= m.bones.size();
        }
    }
    // the source's skin (its face's): the stand-in's body is tinted to it where its own face's colour differs (setBodyTint)
    std::string skin;
    if (s.skinMatch) {
        std::vector<float> uvs;
        std::vector<bool> mask = faceVertices(m);
        uint32_t fid = maskUvs(m, s.geosets, mask, [&](const M2Texture& t) {
            if (!t.type) return t.fileId;
            auto it = s.textures.find(t.type);
            return it != s.textures.end() ? it->second : 0u;
        }, uvs);
        Bytes blp;
        Image img;
        if (fid && readFile(files(fid), blp) && decodeBlp(blp, 512, img)) {
            int c = meanColourAt(img.rgba.data(), img.width, img.height, uvs);
            char hex[16];
            if (c >= 0) { std::snprintf(hex, sizeof hex, "%06X", c); skin = hex; }
        }
    }
    std::string folder = dir + "/" + s.set;
    ::mkdir(dir.c_str(), 0755);
    ::mkdir(folder.c_str(), 0755);
    std::string index = "{\n  \"set\": " + jsonString(s.set) + ",\n  \"model\": " + std::to_string(s.model) +
                        ",\n  \"standIn\": " + std::to_string(s.standIn) + ",\n  \"standInLook\": " + jsonString(s.standInLook) + ",\n  \"skinColour\": " + jsonString(skin) + ",\n  \"cut\": [";
    for (size_t i = 0; i < s.cut.size(); ++i) index += (i ? ", " : "") + jsonString(s.cut[i]);
    index += "],\n  \"parts\": [";
    report = posed ? "posed as it stands\n" : "bind pose (its Stand isn't downloaded)\n";
    size_t written = 0;
    for (const std::string& rn : s.regions) {
        auto rit = man.regions.find(rn);
        if (rit == man.regions.end()) { report += "region " + rn + " not defined\n"; continue; }
        std::vector<PartData> parts;
        size_t tris = extractRegion(m, s.geosets, rit->second, s.textures, s.set + "/", parts, posed ? &mats : nullptr);
        BonePartFit fit = s.fits.count(rn) ? s.fits.at(rn) : BonePartFit();
        char line[160];
        std::snprintf(line, sizeof line, "%s: %zu triangles in %zu parts\n", rn.c_str(), tris, parts.size());
        report += line;
        for (const PartData& p : parts) {
            std::string name = p.id.substr(s.set.size() + 1) + ".wcbp";
            std::vector<uint8_t> bytes = writePart(p);
            FILE* f = std::fopen((folder + "/" + name).c_str(), "wb");
            bool ok = f && std::fwrite(bytes.data(), 1, bytes.size(), f) == bytes.size();
            if (f) std::fclose(f);
            if (!ok) { report += "couldn't write " + folder + "/" + name + "\n"; return false; }
            char fitText[200];
            std::snprintf(fitText, sizeof fitText, "\"offset\": [%.4f, %.4f, %.4f], \"scale\": %.4f", fit.offset.x, fit.offset.y, fit.offset.z, fit.scale);
            index += std::string(written ? "," : "") + "\n    {\"file\": " + jsonString(name) + ", \"region\": " + jsonString(rn) +
                     ", \"bone\": " + jsonString(p.attachBone) + ", \"texture\": " + std::to_string(p.textureFileId) +
                     ", \"blend\": " + std::to_string(p.blend) + ", \"triangles\": " + std::to_string(p.indices.size() / 3) + ", " + fitText + "}";
            ++written;
        }
    }
    index += "\n  ]\n}\n";
    if (!written) {   // (no set.json: a stand-in must never lose its head for pieces that aren't there)
        report += "no parts: " + folder + "/set.json not written\n";
        std::remove((folder + "/set.json").c_str());
        return false;
    }
    FILE* f = std::fopen((folder + "/set.json").c_str(), "wb");
    bool ok = f && std::fwrite(index.data(), 1, index.size(), f) == index.size();
    if (f) std::fclose(f);
    if (!ok) { report += "couldn't write " + folder + "/set.json\n"; return false; }
    report += std::to_string(written) + " parts written to " + folder + "\n";
    return written > 0;
}

} // namespace wc
