// Spring-bone jiggle in the scene: see springdraw.h.
#include <GLES3/gl3.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>

#include "glutil.h"
#include "scene.h"
#include "sculpt.h"

namespace wc {

namespace {
constexpr int kUnitW = 8, kUnitM = 9;

unsigned makeTex(int w, int h, const float* data) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, w, h, 0, GL_RGBA, GL_FLOAT, data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}
struct BodyArrays {
    std::vector<V3> pos;
    std::vector<uint8_t> bi, bw;
    SbBodyView view(size_t n) const { return SbBodyView{pos.data(), bi.data(), bw.data(), n}; }
};
BodyArrays arraysOf(const std::vector<M2Vertex>& verts) {
    BodyArrays b;
    b.pos.resize(verts.size()); b.bi.resize(verts.size() * 4); b.bw.resize(verts.size() * 4);
    for (size_t i = 0; i < verts.size(); ++i) {
        const M2Vertex& v = verts[i];
        b.pos[i] = V3(v.pos[0], v.pos[1], v.pos[2]);
        for (int k = 0; k < 4; ++k) { b.bi[i * 4 + size_t(k)] = v.bones[k]; b.bw[i * 4 + size_t(k)] = v.weights[k]; }
    }
    return b;
}
float slider(const std::vector<float>& v, int k) { return size_t(k) < v.size() ? v[size_t(k)] : 0.0f; }
} // namespace

SbRig modelSpringRig(const M2Model& m, const std::vector<V3>& pivots, const std::vector<M2Vertex>& verts) {
    SbSkeleton sk;
    const char* names[kSbJoints] = {"Pelvis", "Spine1", "Chest", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Breast", "R_Breast",
                                    "L_UpperArm", "R_UpperArm", "L_Forearm", "R_Forearm"};
    for (int j = 0; j < kSbJoints; ++j) {
        int b = m.findWowBone(names[j]);
        if (b >= 0 && size_t(b) < pivots.size()) { sk.bone[j] = b; sk.pivot[j] = pivots[size_t(b)]; }
    }
    BodyArrays arr = arraysOf(verts);
    return buildSpringRig(arr.view(verts.size()), sk);
}

SbRig hdSpringRig(const M2Model& m, const HdFit& fit) {
    SbSkeleton sk;
    const char* names[kSbJoints] = {"Pelvis", "Spine1", "Chest", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Breast", "R_Breast",
                                    "L_UpperArm", "R_UpperArm", "L_Forearm", "R_Forearm"};
    for (int j = 0; j < kSbJoints; ++j) {
        int b = m.findWowBone(names[j]);
        if (b >= 0 && size_t(b) < fit.rigPivots.size()) { sk.bone[j] = b; sk.pivot[j] = fit.rigPivots[size_t(b)]; }
    }
    BodyArrays arr = arraysOf(fit.verts);
    return buildSpringRig(arr.view(fit.bodyVertexEnd), sk);
}

size_t hdTessellateSoft(const M2Model& m, HdFit& fit, float edgeShare, size_t cap, TessStats* stats) {
    if (edgeShare <= 0 || cap == 0 || fit.bodyVertexEnd == 0 || fit.bodyIndices.empty()) return 0;
    SbRig rig = hdSpringRig(m, fit);
    if (rig.empty()) return 0;
    const size_t end = fit.bodyVertexEnd, bodyIdx = fit.bodyIndices.size();
    BodyArrays arr = arraysOf(fit.verts);
    std::vector<float> soft;
    springSoftMask(rig, arr.view(end), soft);
    std::vector<M2Vertex> bv(fit.verts.begin(), fit.verts.begin() + long(end));
    std::vector<uint32_t> bi(fit.indices.begin(), fit.indices.begin() + long(bodyIdx));
    std::vector<TessRange> ranges;
    size_t bodyParts = 0;
    for (const HdDrawPart& p : fit.parts) {
        if (size_t(p.start) + p.count > bodyIdx) break;
        ranges.push_back({p.start, p.count}); ++bodyParts;
    }
    const size_t added = tessellateSoftRegions(bv, bi, ranges, soft, edgeShare * rig.unit, cap, stats);
    if (added == 0) return 0;
    // the body's vertices first, then the others' (their indices up by `added`)
    std::vector<M2Vertex> verts = std::move(bv);
    verts.insert(verts.end(), fit.verts.begin() + long(end), fit.verts.end());
    std::vector<uint32_t> idx = bi;
    const size_t restFrom = bodyIdx;
    for (size_t i = restFrom; i < fit.indices.size(); ++i) {
        uint32_t x = fit.indices[i];
        idx.push_back(x >= end ? x + uint32_t(added) : x);
    }
    const int64_t shift = int64_t(bi.size()) - int64_t(bodyIdx);
    for (size_t i = 0; i < fit.parts.size(); ++i) {
        if (i < bodyParts) { fit.parts[i].start = ranges[i].start; fit.parts[i].count = ranges[i].count; }
        else fit.parts[i].start = uint32_t(int64_t(fit.parts[i].start) + shift);
    }
    fit.verts = std::move(verts);
    fit.indices = std::move(idx);
    fit.bodyIndices = bi;
    fit.bodyVertexEnd = uint32_t(end + added);
    fit.stats.vertices = fit.verts.size();
    fit.stats.triangles = fit.indices.size() / 3;
    return added;
}

void SpringDraw::uniforms(unsigned p) const {
    glUniform1i(gl::loc(p, "uSbW"), kUnitW);
    glUniform1i(gl::loc(p, "uSbM"), kUnitM);
    if (!active()) { glUniform1i(gl::loc(p, "uSbOn"), 0); return; }
    glActiveTexture(GL_TEXTURE0 + kUnitW);
    glBindTexture(GL_TEXTURE_2D, wTex);
    glActiveTexture(GL_TEXTURE0 + kUnitM);
    glBindTexture(GL_TEXTURE_2D, mTex);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(gl::loc(p, "uSbOn"), 1);
}
void SpringDraw::off(unsigned p) { glUniform1i(gl::loc(p, "uSbOn"), 0); }

namespace {
uint64_t tessKeyOf(float edge, int cap) { return uint64_t(edge * 1e6f) * 1000003ull + uint64_t(cap) + 1; }
}

// The game's own body (no HD figure drawn instead): made again with its soft regions tessellated when the setting changed.
void Scene::checkBodyTessellation(Actor& a) {
    BodyGl& b = a.body;
    const bool want = tessEdge_ > 0 && a.character && !a.hd.requested && a.mesh && a.mesh->model && a.mesh->model->vertices.size() < 400000;
    const uint64_t key = want ? tessKeyOf(tessEdge_, tessCap_) : 0;
    if (b.tessKey == key || !glReady_) return;
    b.shapeDirty = true; b.dirty = true;   // (refreshBody makes it: tessellateOwnBody)
    a.soft.dirty = true;
}

void Scene::tessellateOwnBody(Actor& a) {
    BodyGl& b = a.body;
    const M2Model& m = *a.mesh->model;
    const bool want = tessEdge_ > 0 && a.character && !a.hd.requested && m.vertices.size() < 400000;
    b.tessKey = want ? tessKeyOf(tessEdge_, tessCap_) : 0;
    b.tessAdded = 0;
    if (!want) return;
    if (b.shaped.empty()) { b.shaped = m.vertices; b.indices = m.indices; b.submeshes = m.submeshes; }
    std::vector<V3> piv(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) piv[i] = m.bones[i].pivot;
    SbRig rig = modelSpringRig(m, piv, b.shaped);
    if (rig.empty()) return;
    BodyArrays arr = arraysOf(b.shaped);
    std::vector<float> soft;
    springSoftMask(rig, arr.view(b.shaped.size()), soft);
    std::vector<TessRange> ranges;
    for (const M2Submesh& s : b.submeshes) ranges.push_back({s.indexStart, s.indexCount});
    TessStats ts;
    b.tessAdded = tessellateSoftRegions(b.shaped, b.indices, ranges, soft, tessEdge_ * rig.unit, size_t(tessCap_), &ts);
    for (size_t i = 0; i < b.submeshes.size() && i < ranges.size(); ++i) { b.submeshes[i].indexStart = ranges[i].start; b.submeshes[i].indexCount = ranges[i].count; }
    std::fprintf(stderr, "body tessellation: %zu -> %zu vertices, %zu -> %zu triangles, soft edges p50 %.2f -> %.2f mm, %d passes%s, %.1f ms\n", ts.verticesBefore,
                 ts.verticesAfter, ts.trianglesBefore, ts.trianglesAfter, ts.edgeP50Before * 1000, ts.edgeP50After * 1000, ts.passes, ts.capped ? " (capped)" : "", ts.ms);
}

void Scene::setSpringBones(int level, float amount, int preset) {
    springLevel_ = std::clamp(level, 0, 2);
    springAmount_ = std::isfinite(amount) ? std::clamp(amount, 0.0f, 2.0f) : 1.0f;
    springPreset_ = std::clamp(preset, 0, kSbPresets - 1);
}

void Scene::setTessellation(float edgeShare, int cap) {
    tessEdge_ = std::isfinite(edgeShare) ? std::clamp(edgeShare, 0.0f, 0.05f) : 0.0125f;
    tessCap_ = std::clamp(cap, 0, 200000);
}

bool Scene::hdBodyMesh(int id, std::vector<float>& pos, std::vector<uint32_t>& indices) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.hd.active) return false;
    const HdFit& f = it->second.hd.fit;
    pos.clear();
    for (uint32_t i = 0; i < f.bodyVertexEnd; ++i) { pos.push_back(f.verts[i].pos[0]); pos.push_back(f.verts[i].pos[1]); pos.push_back(f.verts[i].pos[2]); }
    indices = f.bodyIndices;
    return true;
}

bool Scene::springStats(int id, int& helpers, int& chains, size_t& vertices, bool& asleep) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.spring.ready) return false;
    helpers = it->second.spring.sim.rig().helpers;
    chains = int(it->second.spring.sim.rig().chains.size());
    vertices = it->second.spring.softVertices;
    asleep = it->second.spring.sim.asleep();
    return true;
}

void Scene::kickSprings(int id, const V3& worldAt, const V3& velocity, float radius) {
    auto it = actors_.find(id);
    if (it != actors_.end() && it->second.spring.ready) it->second.spring.sim.kick(worldAt, velocity, radius);
}

void Scene::stepSprings(uint32_t clock) {
    const float dt = springStarted_ && clock >= springClock_ ? std::min(float(clock - springClock_) / 1000.0f, 0.25f) : 0.0f;
    springClock_ = clock; springStarted_ = true;
    for (auto& [id, a] : actors_) {
        SpringDraw& sd = a.spring;
        if (springLevel_ <= 0 || !a.visible || !a.character || !a.mesh || !a.mesh->model) {
            if (sd.ready) { sd.sim.setSettings(SbSettings{SbPreset::Natural, 0, 0}); }
            continue;
        }
        if ((a.position - eye_).length() > 18.0f) continue;
        checkBodyTessellation(a);
        const bool hd = a.hd.active && a.hd.fit.bodyVertexEnd > 0;
        if (a.hd.requested && !hd) continue;      // (its figure is not fitted yet: the game's body is not drawn)
        const M2Model& m = *a.mesh->model;
        // the vertices the draw uses: the HD fit's, else the actor's shaped body (its own buffer), else the model's own
        const bool own = !hd && a.body.vao && !a.body.shaped.empty();
        const std::vector<M2Vertex>& drawn = hd ? a.hd.fit.verts : own ? a.body.shaped : m.vertices;
        const uint64_t key = (hd ? a.hd.key : uint64_t(reinterpret_cast<uintptr_t>(&m)) * 31 + (own ? a.body.shaped.size() * 7 + a.body.baseGen : 0) + 3) ^ 0x5b5b5b5bull;
        if (sd.builtKey != key || !sd.ready) {
            const auto t0 = std::chrono::steady_clock::now();
            SbRig rig;
            if (hd) rig = hdSpringRig(m, a.hd.fit);
            else {
                std::vector<V3> piv(m.bones.size());
                for (size_t i = 0; i < m.bones.size(); ++i) piv[i] = m.bones[i].pivot;
                rig = modelSpringRig(m, piv, drawn);
            }
            std::vector<V3> pos(drawn.size());
            std::vector<uint8_t> bi(drawn.size() * 4), bw(drawn.size() * 4);
            for (size_t i = 0; i < drawn.size(); ++i) {
                const M2Vertex& v = drawn[i];
                pos[i] = V3(v.pos[0], v.pos[1], v.pos[2]);
                for (int k = 0; k < 4; ++k) { bi[i * 4 + size_t(k)] = v.bones[k]; bw[i * 4 + size_t(k)] = v.weights[k]; }
            }
            SbBodyView all{pos.data(), bi.data(), bw.data(), pos.size()};
            sd.sim.build(rig);
            computeSpringWeights(rig, all, sd.weights);
            sd.helpers = rig.helpers;
            sd.softVertices = 0;
            const size_t n = pos.size();
            sd.packed.assign(size_t((n + kSpringTexW - 1) / kSpringTexW) * kSpringTexW * 4, -1.0f);
            for (size_t i = 0; i < n; ++i) {
                bool any = false;
                for (int k = 0; k < kSbMaxPerVertex; ++k) {
                    int ix = sd.weights.index[i * 4 + size_t(k)];
                    float w = sd.weights.weight[i * 4 + size_t(k)];
                    if (ix >= 0 && w > 0) { sd.packed[i * 4 + size_t(k)] = float(ix) + std::min(w, 1.0f) * 0.99f; any = true; }
                }
                if (any) ++sd.softVertices;
            }
            if (glReady_) {
                if (sd.wTex) glDeleteTextures(1, &sd.wTex);
                if (sd.mTex) glDeleteTextures(1, &sd.mTex);
                sd.wTex = makeTex(kSpringTexW, int(sd.packed.size() / (4 * size_t(kSpringTexW))), sd.packed.data());
                std::vector<float> id(size_t(std::max(rig.helpers, 1)) * 16, 0.0f);
                for (size_t h = 0; h < size_t(std::max(rig.helpers, 1)); ++h) { id[h * 16] = id[h * 16 + 5] = id[h * 16 + 10] = id[h * 16 + 15] = 1; }
                sd.mTex = makeTex(4, std::max(rig.helpers, 1), id.data());
                glBindTexture(GL_TEXTURE_2D, 0);
            }
            sd.builtKey = key;
            sd.ready = !rig.empty();
            sd.builtMs = float(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
            std::fprintf(stderr, "spring bones: %zu chains, %d helpers, %zu of %zu vertices weighted, %.1f ms\n", rig.chains.size(), rig.helpers,
                         sd.softVertices, n, sd.builtMs);
        }
        if (!sd.ready) continue;
        sd.sim.setSettings(SbSettings{SbPreset(springPreset_), springAmount_, springLevel_});
        const float bust = slider(a.sculpt, kSculptBust) + 0.5f * slider(a.sculpt, kSculptBustWidth) + 0.7f * slider(a.sculpt, kSculptBustProjection);
        const float butt = slider(a.sculpt, kSculptButt) + 0.5f * slider(a.sculpt, kSculptButtWidth) + 0.7f * slider(a.sculpt, kSculptButtProjection) +
                           0.3f * slider(a.sculpt, kSculptHips);
        sd.sim.setRegionSize(SbRegion::Breast, std::clamp(1.0f + 0.45f * bust, 0.5f, 2.0f));
        sd.sim.setRegionSize(SbRegion::Butt, std::clamp(1.0f + 0.4f * butt, 0.5f, 2.0f));
        sd.sim.setRegionSize(SbRegion::Thigh, std::clamp(1.0f + 0.3f * slider(a.sculpt, kSculptThighs), 0.6f, 1.6f));
        sd.sim.setRegionSize(SbRegion::Belly, std::clamp(1.0f + 0.4f * slider(a.sculpt, kSculptBelly), 0.6f, 1.8f));
        const M4 W = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
        sd.sim.step(dt, W, a.mats.data(), int(a.mats.size()));
        if (glReady_ && sd.mTex && !sd.sim.transforms().empty()) {
            glBindTexture(GL_TEXTURE_2D, sd.mTex);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, GLsizei(sd.sim.transforms().size()), GL_RGBA, GL_FLOAT, sd.sim.transforms().data());
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }
}

} // namespace wc
