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
float slider(const std::vector<float>& v, int k) { return size_t(k) < v.size() ? v[size_t(k)] : 0.0f; }
} // namespace

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

void Scene::setSpringBones(int level, float amount, int preset) {
    springLevel_ = std::clamp(level, 0, 2);
    springAmount_ = std::isfinite(amount) ? std::clamp(amount, 0.0f, 2.0f) : 1.0f;
    springPreset_ = std::clamp(preset, 0, kSbPresets - 1);
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
        if (springLevel_ <= 0 || !a.visible || !a.character || !a.mesh || !a.mesh->model || !a.hd.active || a.hd.fit.bodyVertexEnd == 0) {
            if (sd.ready) { sd.sim.setSettings(SbSettings{SbPreset::Natural, 0, 0}); }
            continue;
        }
        if ((a.position - eye_).length() > 18.0f) continue;
        const HdFit& fit = a.hd.fit;
        const M2Model& m = *a.mesh->model;
        const uint64_t key = a.hd.key ^ 0x5b5b5b5bull;
        if (sd.builtKey != key || !sd.ready) {
            const auto t0 = std::chrono::steady_clock::now();
            SbSkeleton sk;
            const char* names[kSbJoints] = {"Pelvis", "Spine1", "Chest", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Breast", "R_Breast",
                                            "L_UpperArm", "R_UpperArm", "L_Forearm", "R_Forearm"};
            for (int j = 0; j < kSbJoints; ++j) {
                int b = m.findWowBone(names[j]);
                if (b >= 0 && size_t(b) < fit.rigPivots.size()) { sk.bone[j] = b; sk.pivot[j] = fit.rigPivots[size_t(b)]; }
            }
            std::vector<V3> pos(fit.verts.size());
            std::vector<uint8_t> bi(fit.verts.size() * 4), bw(fit.verts.size() * 4);
            for (size_t i = 0; i < fit.verts.size(); ++i) {
                const M2Vertex& v = fit.verts[i];
                pos[i] = V3(v.pos[0], v.pos[1], v.pos[2]);
                for (int k = 0; k < 4; ++k) { bi[i * 4 + size_t(k)] = v.bones[k]; bw[i * 4 + size_t(k)] = v.weights[k]; }
            }
            SbBodyView body{pos.data(), bi.data(), bw.data(), size_t(fit.bodyVertexEnd)};
            SbRig rig = buildSpringRig(body, sk);
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
