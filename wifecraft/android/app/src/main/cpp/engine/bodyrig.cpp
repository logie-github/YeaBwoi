// The Scene's side of the creator's body pick and sculpt (bodypick.h, sculpt.h): a model's bones named into regions and the
// sculpt's rig, the tap's ray against the actor as posed, and the sculpt kept on the actor (the Eve body takes it as it is
// fitted: evescene.cpp).
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>

#include <GLES3/gl3.h>

#include "bodypick.h"
#include "glutil.h"
#include "scene.h"
#include "sculpt.h"

namespace wc {

std::vector<uint8_t> boneRegionsOf(const M2Model& m) {
    static const std::pair<const char*, uint8_t> kNamed[] = {
        {"Root", kRegionHips}, {"Pelvis", kRegionHips},
        {"Waist", kRegionWaist}, {"Spine1", kRegionWaist},
        {"Spine2", kRegionChest}, {"Chest", kRegionChest}, {"L_Breast", kRegionChest}, {"R_Breast", kRegionChest},
        {"L_Clavicle", kRegionChest}, {"R_Clavicle", kRegionChest},
        {"Neck1", kRegionNeck}, {"Neck2", kRegionNeck},
        {"Head", kRegionHead}, {"Jaw", kRegionHead}, {"L_Eye", kRegionHead}, {"R_Eye", kRegionHead},
        {"L_UpperArm", kRegionArms}, {"R_UpperArm", kRegionArms}, {"L_Forearm", kRegionArms}, {"R_Forearm", kRegionArms},
        {"L_Hand", kRegionHands}, {"R_Hand", kRegionHands},
        {"L_Thigh", kRegionLegs}, {"R_Thigh", kRegionLegs}, {"L_Calf", kRegionLegs}, {"R_Calf", kRegionLegs},
        {"L_Ankle", kRegionFeet}, {"R_Ankle", kRegionFeet}, {"L_Foot", kRegionFeet}, {"R_Foot", kRegionFeet},
        {"L_Toe", kRegionFeet}, {"R_Toe", kRegionFeet},
    };
    std::vector<std::pair<int, uint8_t>> seeds;
    for (const auto& [name, region] : kNamed) {
        int b = m.findWowBone(name);
        if (b >= 0) seeds.emplace_back(b, region);
    }
    std::vector<int16_t> parents(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) parents[i] = m.bones[i].parent;
    return boneRegionsFrom(parents, seeds);
}

int Scene::pickBodyRegion(int id, float sx, float sy, int width, int height) const {
    return pickBodyDetail(id, sx, sy, width, height, nullptr);
}

// out (8 floats, may be null): region code, side (-1 her right, 0 middle, 1 her left), the hit's height over the head bone's pivot
// (1 at the crown), forward (model x over the pelvis height), sideways (y), the model-space z over the pelvis height, 1 when the ray
// came from in front of her (the hit's normal faces the eye), the hit's geoset id.
int Scene::pickBodyDetail(int id, float sx, float sy, int width, int height, float* out) const {
    if (out) for (int i = 0; i < 8; ++i) out[i] = 0;
    auto it = actors_.find(id);
    if (it == actors_.end() || width <= 0 || height <= 0) return kRegionNone;
    const Actor& a = it->second;
    if (!a.mesh || !a.mesh->model || !a.visible) return kRegionNone;
    const M2Model& m = *a.mesh->model;
    // the camera's ray through the point (as Scene::pick), then into the actor's model space
    V3 f = (target_ - eye_).normalized();
    V3 s = f.cross(V3(0, 0, 1)).normalized();
    V3 u = s.cross(f);
    float th = std::tan(fov_ * 3.14159265f / 360.0f), aspect = float(width) / float(height);
    float nx = 2.0f * sx / float(width) - 1.0f, ny = 1.0f - 2.0f * sy / float(height);
    V3 d = (f + s * (nx * th * aspect) + u * (ny * th)).normalized();
    V3 rel = eye_ - a.position;
    float c = std::cos(-a.facing), sn = std::sin(-a.facing);
    auto unturn = [&](const V3& v) { return V3(v.x * c - v.y * sn, v.x * sn + v.y * c, v.z); };
    float scale = a.scale > 1e-6f ? a.scale : 1.0f;
    V3 o = unturn(rel) * (1.0f / scale);
    V3 dm = unturn(d);
    // the body as drawn: Eve's with the cut body, else the shaped body, else the model's own
    const bool eve = a.eve.active && a.eve.measure.host >= 0;
    const BodyGl& b = a.body;
    const bool own = !b.shaped.empty() && !b.indices.empty() && !b.submeshes.empty();
    const std::vector<M2Vertex>& verts = eve ? a.eve.measure.verts : own ? b.shaped : m.vertices;
    const std::vector<uint32_t>& idx = eve ? a.eve.measure.indices : own ? b.indices : m.indices;
    const std::vector<M2Submesh>& subs = eve ? a.eve.measure.subs : own ? b.submeshes : m.submeshes;
    if (boneRegionsModel_ != &m) { boneRegions_ = boneRegionsOf(m); boneRegionsModel_ = &m; }
    BodyPickHit hit = pickBody(verts, idx, subs, a.geosets, a.mats, boneRegions_, o, dm);
    // the hair: a style that is a model of its own (not the body's geosets) is not in these triangles, so a tap round the
    // head that misses the body, or lands on the top of the head, is the hair
    const int head = m.findWowBone("Head"), pelvis = m.findWowBone("Pelvis");
    if (head >= 0 && size_t(head) < a.mats.size()) {
        const V3 hp = a.mats[size_t(head)].point(m.bones[size_t(head)].pivot);
        const float unit = std::max(pelvis >= 0 ? m.bones[size_t(pelvis)].pivot.z : 1.0f, 0.1f);
        if (hit.region == kRegionHead && hit.point.z > hp.z + 0.1f * unit) { if (out) { out[0] = kRegionHair; out[1] = 0; out[2] = hit.point.z / std::max(hp.z, 0.1f); out[3] = hit.point.x / unit; out[4] = hit.point.y / unit; out[5] = hit.point.z / unit; out[6] = dm.x < 0 ? 1.f : 0.f; out[7] = float(hit.geoset); } return kRegionHair; }
        if (hit.region == kRegionNone) {
            V3 dn = dm.normalized();
            float along = (hp - o).dot(dn);
            float miss = (o + dn * along - hp).length();
            if (along > 0 && miss < 0.2f * unit) { if (out) out[0] = kRegionHair; return kRegionHair; }
        }
    }
    // the side of her body it landed on (bits 8-9: 1 her left, 2 her right, 0 the middle), for the creator's one-sided sculpt
    if (hit.region != kRegionNone && hit.region != kRegionHair) {
        const int pelvis = m.findWowBone("Pelvis");
        const float unit = std::max(pelvis >= 0 ? m.bones[size_t(pelvis)].pivot.z : 1.0f, 0.1f);
        if (out) {
            const float top = head >= 0 ? std::max(m.bones[size_t(head)].pivot.z, 0.1f) : 1.7f * unit;
            out[0] = float(hit.region); out[1] = hit.point.y > 0.03f * unit ? 1.f : hit.point.y < -0.03f * unit ? -1.f : 0.f;
            out[2] = hit.point.z / top; out[3] = hit.point.x / unit; out[4] = hit.point.y / unit; out[5] = hit.point.z / unit;
            out[6] = dm.x < 0 ? 1.f : 0.f; out[7] = float(hit.geoset);
        }
        if (hit.point.y > 0.03f * unit) return int(hit.region) | (1 << 8);
        if (hit.point.y < -0.03f * unit) return int(hit.region) | (2 << 8);
    }
    return hit.region;
}

void Scene::setSculpt(int id, const float* values, size_t count) {
    auto it = actors_.find(id);
    if (it == actors_.end() || (!values && count)) return;
    it->second.sculptWant.assign(values, values + std::min(count, size_t(kSculptValues)));
    // taken on the next frame (sculptSync): the Eve body fitted again with it (evescene.cpp: eveCheck mixes it into the key),
    // the game's own body sculpted again (refreshBody)
}

namespace {
constexpr uint64_t kSculptMinMs = 166;     // without the live sculpt: the game's body sculpted again at most six times a second
constexpr uint64_t kSculptSettleMs = 150;  // with it: the CPU sculpt made again once the sliders have rested this long
constexpr uint64_t kSculptStaleMs = 1000;  // or, while they keep moving, once the change it hasn't made is this old
constexpr int kMorphTexWidth = 2048;       // the fields' textures' width (GLES 3's least GL_MAX_TEXTURE_SIZE)
constexpr uint32_t kMorphMostPerVertex = 64;   // the vertex shader's loop (kM2Vs morphed)
static_assert(kSculptCount <= 64, "the live sculpt's uniforms hold 64 sliders");

uint64_t steadyMs() {
    return uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
}

// The mesh sliders of a sculpt: the skeleton's (Height, Leg Length) and their sides at 0; none set: empty.
std::vector<float> meshValues(const std::vector<float>& values) {
    std::vector<float> want;
    if (!sculptActive(values.data(), values.size())) return want;
    want = values;
    for (size_t i = 0; i < want.size(); ++i)
        if (i % size_t(kSculptCount) == size_t(kSculptHeight) || i % size_t(kSculptCount) == size_t(kSculptLegLength)) want[i] = 0;
    return want;
}

// A float texture kMorphTexWidth texels wide from `px` (RGBA rows, padded to whole rows). 0: too tall for this GPU.
unsigned floatTexture(const std::vector<float>& px) {
    GLint most = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &most);
    const size_t rows = px.size() / (4 * size_t(kMorphTexWidth));
    if (rows == 0 || most < kMorphTexWidth || rows > size_t(most)) return 0;
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, kMorphTexWidth, GLsizei(rows), 0, GL_RGBA, GL_FLOAT, px.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (glGetError() != GL_NO_ERROR) { glDeleteTextures(1, &t); return 0; }
    return t;
}

// `texels` RGBA texels' worth of floats, padded to whole rows of kMorphTexWidth.
std::vector<float> texelRows(size_t texels) {
    const size_t rows = std::max<size_t>(1, (texels + kMorphTexWidth - 1) / kMorphTexWidth);
    return std::vector<float>(rows * kMorphTexWidth * 4, 0.0f);
}
} // namespace

void Scene::sculptSync(Actor& a) {
    // the Eve body on her: asked for and offered for this model (else the game's body carries the sculpt)
    bool eve = a.eve.requested && eveSet_ && a.mesh && a.mesh->model;
    if (eve) { const EveRaceFit* fit = eveSet_->fits.forModel(a.mesh->model->fileId); eve = fit && fit->enabled; }
    BodyGl& b = a.body;
    const uint64_t t = steadyMs();
    // the live sculpt (the game's body; Eve's fit is made again on the worker, evescene.cpp): with its fields made, every
    // change is drawn at once on the GPU and the CPU sculpt follows when the sliders rest
    const bool live = !eve && sculptThrottle_ &&
                      (a.sculptWant == a.sculpt ? b.morphIdxTex != 0 && b.morphGen == b.baseGen : sculptMorphReady(a));
    if (a.sculptWant != a.sculpt) {
        if (eve || live || !sculptThrottle_ || a.sculptAtMs == 0 || t - a.sculptAtMs >= kSculptMinMs) { a.sculpt = a.sculptWant; a.sculptAtMs = t ? t : 1; }
    }
    // what the game's body carries: the mesh sliders while Eve is off (under Eve the body is cut and Eve takes them)
    std::vector<float> want = eve ? std::vector<float>() : meshValues(a.sculpt);
    if (want == b.sculpted) { b.live.clear(); b.staleAtMs = 0; return; }
    if (live) {
        if (want != b.live) { b.live = want; b.liveAtMs = t; if (!b.staleAtMs) b.staleAtMs = t ? t : 1; }
        if (sculptHold_ || (t - b.liveAtMs < kSculptSettleMs && t - b.staleAtMs < kSculptStaleMs)) return;   // (on the GPU meanwhile)
    }
    b.live.clear();
    b.staleAtMs = 0;
    b.sculpted = std::move(want);
    if (!b.shapeDirty) b.sculptOnly = true;   // (a change of shape, cut or model already waiting makes the body whole again)
    b.shapeDirty = true;
    b.dirty = true;
    a.soft.dirty = true;
    a.cloth.dirty = true;
}

bool Scene::sculptMorphReady(Actor& a) {
    BodyGl& b = a.body;
    if (!glReady_ || !a.mesh || !a.mesh->model || b.shapeDirty) return false;   // (a new shape or cut first: refreshBody)
    const M2Model& m = *a.mesh->model;
    if (b.genModel != static_cast<const void*>(&m) || b.genMorph != b.morph || b.genCut != a.parts.cut) {
        // the body as it is was made from these (refreshBody counts them again only when they change)
        ++b.baseGen;
        b.genModel = &m; b.genMorph = b.morph; b.genCut = a.parts.cut;
    }
    if (b.morphIdxTex && b.morphGen == b.baseGen) return true;
    if (b.morphFailedGen == b.baseGen) return false;
    // the body before the sculpt: the one kept for the next change while sculpted, else the shaped body or the model's own
    const std::vector<M2Vertex>* base = &m.vertices;
    if (sculptActive(b.sculpted.data(), b.sculpted.size())) {
        if (b.sculptBase.empty()) return false;
        base = &b.sculptBase;
    } else if (!b.shaped.empty()) {
        base = &b.shaped;
    }
    dropSculptMorph(a);
    const auto t0 = std::chrono::steady_clock::now();
    SculptMorph f;
    SculptOptions opt;
    opt.gameBody = true;
    bool ok = buildSculptMorph(sculptRigOf(m), *base, opt, f) && f.vertices() == base->size();
    uint32_t most = 0;
    if (ok) {
        const size_t n = f.vertices(), e = f.entries();
        std::vector<float> idx = texelRows(n);
        for (size_t v = 0; v < n; ++v) {
            const uint32_t count = f.first[v + 1] - f.first[v];
            most = std::max(most, count);
            idx[v * 4] = float(f.first[v]); idx[v * 4 + 1] = float(count); idx[v * 4 + 2] = f.side[v];
        }
        std::vector<float> dat = texelRows(e);
        for (size_t i = 0; i < e; ++i) {
            dat[i * 4] = float(f.channel[i]); dat[i * 4 + 1] = f.delta[i].x; dat[i * 4 + 2] = f.delta[i].y; dat[i * 4 + 3] = f.delta[i].z;
        }
        ok = most <= kMorphMostPerVertex && e < (size_t(1) << 24);   // (the shader's loop; entries exact as floats)
        if (ok) {
            b.morphIdxTex = floatTexture(idx);
            b.morphDataTex = floatTexture(dat);
            ok = b.morphIdxTex && b.morphDataTex;
            b.morphBytes = (idx.size() + dat.size()) * sizeof(float);
        }
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    if (!ok) { dropSculptMorph(a); b.morphFailedGen = b.baseGen; return false; }
    b.morphGen = b.baseGen;
    b.morphVertices = f.vertices();
    b.morphMs = float(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    std::fprintf(stderr, "body live sculpt: %zu vertices, %zu field entries (at most %u a vertex), %.1f MB, %.1f ms\n",
                 f.vertices(), f.entries(), most, b.morphBytes / 1048576.0, b.morphMs);
    return true;
}

bool Scene::sculptMorphDraw(Actor& a) {
    BodyGl& b = a.body;
    if (b.live.empty() || b.live == b.sculpted || !b.morphIdxTex || !b.morphDataTex || b.morphGen != b.baseGen || !m2Prog_) return false;
    if (!a.mesh || !a.mesh->model) return false;
    const size_t drawn = b.vao ? b.shaped.size() : a.mesh->model->vertices.size();
    if (drawn != b.morphVertices) return false;
    // the change since the CPU sculpt: the fields' weights now less theirs then (the sum is linear in them)
    float ws[kSculptCount], bs[kSculptCount], w0[kSculptCount], b0[kSculptCount];
    sculptMorphWeights(b.live.data(), b.live.size(), true, ws, bs);
    sculptMorphWeights(b.sculpted.empty() ? nullptr : b.sculpted.data(), b.sculpted.size(), true, w0, b0);
    float uw[64] = {}, ub[64] = {};
    for (int k = 0; k < kSculptCount; ++k) { uw[k] = ws[k] - w0[k]; ub[k] = bs[k] - b0[k]; }
    glUseProgram(m2Prog_);
    glUniform4fv(gl::loc(m2Prog_, "uMorphW"), 16, uw);
    glUniform4fv(gl::loc(m2Prog_, "uMorphB"), 16, ub);
    glActiveTexture(GL_TEXTURE6);
    glBindTexture(GL_TEXTURE_2D, b.morphIdxTex);
    glActiveTexture(GL_TEXTURE7);
    glBindTexture(GL_TEXTURE_2D, b.morphDataTex);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(gl::loc(m2Prog_, "uMorphOn"), 1);
    return true;
}

void Scene::dropSculptMorph(Actor& a) {
    BodyGl& b = a.body;
    if (glReady_) {
        if (b.morphIdxTex) glDeleteTextures(1, &b.morphIdxTex);
        if (b.morphDataTex) glDeleteTextures(1, &b.morphDataTex);
    }
    b.morphIdxTex = b.morphDataTex = 0;
    b.morphGen = 0;
    b.morphVertices = b.morphBytes = 0;
}

float Scene::sculptMs(int id) const {
    auto it = actors_.find(id);
    return it == actors_.end() ? 0.0f : it->second.body.sculptMs;
}

size_t Scene::sculptMorphBytes(int id, float* ms, bool* drawing) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return 0;
    const BodyGl& b = it->second.body;
    if (ms) *ms = b.morphMs;
    if (drawing) *drawing = !b.live.empty() && b.live != b.sculpted && b.morphIdxTex && b.morphGen == b.baseGen;
    return b.morphIdxTex ? b.morphBytes : 0;
}

} // namespace wc
