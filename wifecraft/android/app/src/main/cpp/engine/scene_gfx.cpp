// The graphics options on the scene (graphics_settings.h, postfx.h): the settings and their stats, the key light's shadow
// pass, the frame's start and end round Scene::render's drawing, and the skin and hair shading's numbers.
#include "scene.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>

#include "daylight.h"
#include "flesh.h"
#include "skin.h"

namespace wc {

namespace {

M4 ortho(float l, float r, float b, float t, float n, float f) {
    M4 m;
    m.at(0, 0) = 2 / (r - l); m.at(1, 1) = 2 / (t - b); m.at(2, 2) = -2 / (f - n);
    m.at(0, 3) = -(r + l) / (r - l); m.at(1, 3) = -(t + b) / (t - b); m.at(2, 3) = -(f + n) / (f - n);
    return m;
}

// The shadow map's box round the camera's target (yards) and how far above it the light's eye is.
constexpr float kShadowRadius = 2.6f, kShadowEye = 8.0f;

} // namespace

void Scene::setGraphics(const float* v, int n) {
    GraphicsSettings g;
    g.read(v, n);
    const bool aniso = g.anisotropy != gfx_.anisotropy;
    gfx_ = g;
    postfx_.configure(g);
    if (aniso && glReady_) {
        for (auto& [key, t] : textures_)
            if (t.id && t.id != white_) { glBindTexture(GL_TEXTURE_2D, t.id); applyAnisotropy(g.anisotropy); }
        for (auto& [id, a] : actors_)
            for (auto& [type, tex] : a.composed)
                if (tex) { glBindTexture(GL_TEXTURE_2D, tex); applyAnisotropy(g.anisotropy); }
    }
}

void Scene::graphicsStats(float* out, int n) const {
    const PostStats& s = postfx_.stats();
    float v[PostStats::kStages + 4];
    v[0] = s.cpuMs;
    for (int i = 0; i < PostStats::kStages; ++i) v[1 + i] = s.gpu[i];
    v[1 + PostStats::kStages] = s.timersAvailable ? 1.0f : 0.0f;
    v[2 + PostStats::kStages] = float(postfx_.active() ? s.sceneW : viewW_);
    v[3 + PostStats::kStages] = float(postfx_.active() ? s.sceneH : viewH_);
    for (int i = 0; i < n; ++i) out[i] = i < int(sizeof v / sizeof v[0]) ? v[i] : 0.0f;
}

void Scene::setSheen(int id, float sheen) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.sheen = std::isfinite(sheen) ? std::clamp(sheen, 0.0f, 1.0f) : 0.0f;
}

void Scene::textureMemory(size_t& count, size_t& bytes) const {
    count = 0; bytes = 0;
    for (const auto& [key, t] : textures_)
        if (t.bytes) { ++count; bytes += t.bytes; }
}

void Scene::skinOf(Actor& a, const M4& world) {
    float* out = drawSkin_;
    out[0] = a.sheen;
    out[1] = out[2] = out[3] = 0;
    for (int k = 0; k < 2; ++k) { drawEyes_[k * 3] = 0; drawEyes_[k * 3 + 1] = 0; drawEyes_[k * 3 + 2] = -1e6f; }
    const M2Model* m = a.mesh ? a.mesh->model.get() : nullptr;
    if (!m || (gfx_.eyes <= 0 && gfx_.thinSkin <= 0)) return;
    if (a.fxModel != m) {
        a.fxModel = m;
        const char* names[5] = {"L_Eye", "R_Eye", "Head", "L_Hand", "R_Hand"};
        for (int k = 0; k < 5; ++k) a.fxBones[k] = m->findWowBone(names[k]);
    }
    auto posed = [&](int k, M4& out) {
        const int b = a.fxBones[k];
        if (b < 0 || size_t(b) >= a.mats.size() || size_t(b) >= m->bones.size()) return false;
        out = world * a.mats[size_t(b)];
        return true;
    };
    auto joint = [&](int k, V3& c) {
        M4 t;
        if (!posed(k, t)) return false;
        c = t.point(m->bones[size_t(a.fxBones[k])].pivot);
        return true;
    };
    // the eyeballs' centres: their bones' pivots, posed
    if (gfx_.eyes > 0)
        for (int k = 0; k < 2; ++k) {
            V3 c;
            if (joint(k, c)) { drawEyes_[k * 3] = c.x; drawEyes_[k * 3 + 1] = c.y; drawEyes_[k * 3 + 2] = c.z; }
        }
    if (gfx_.thinSkin <= 0) return;
    // the thin parts are found on the HD skin's layout alone (a 2048 x 1024 material: the face on its right half, the
    // hands in the band under the arms)
    bool hd = false;
    for (const SkinLayer& l : a.layers)
        if (l.textureType == 1 && l.materialW == 2048 && l.materialH == 1024) { hd = true; break; }
    if (!hd) return;
    // The flesh profile (flesh.h's pack: the pelvis in model units, each segment's reaches in body units; measured once for
    // the look, actorFlesh keeps it) is the thickness proxy: what sticks out past the head's measured flesh in front (the
    // nose, the lips) or at its sides (the ears), and a hand past the middle of its own length (the fingers), is thin.
    if (a.flesh.empty())
        for (auto& [id, other] : actors_)
            if (&other == &a) { std::vector<float> tmp; actorFlesh(id, tmp); break; }
    const std::vector<float>& f = a.flesh;
    constexpr size_t kSeg0 = 4, kSegSize = 7;
    auto reach = [&](int seg, int field) {   // 1 front, 3 side, 6 tip; model units (0: not measured)
        const size_t i = kSeg0 + size_t(seg) * kSegSize;
        return f.size() > i + 6 && f[1] > 0 && f[i] > 0.5f ? f[i + size_t(field)] * f[1] : 0.0f;
    };
    float front = reach(kFleshHead, 1), side = reach(kFleshHead, 3);
    float hand = std::max(reach(kFleshLHand, 6), reach(kFleshRHand, 6));
    if (front < 0.02f) front = 0.1f;   // (a human's, until the look is measured)
    if (side < 0.02f) side = 0.075f;
    if (hand < 0.02f) hand = 0.09f;
    M4 head;
    V3 hc, hl, hr;
    if (!posed(2, head) || !joint(2, hc)) return;
    // world -> the head's own frame (x its front, y its side, as the model stands), its reaches to 1
    V3 ax[3];
    for (int c = 0; c < 3; ++c) ax[c] = V3(head.at(0, c), head.at(1, c), head.at(2, c)).normalized();
    const float sx = 1.0f / (front * a.scale), sy = 1.0f / (side * a.scale);
    M4 t;
    for (int r = 0; r < 3; ++r) {
        const float k = r == 0 ? sx : r == 1 ? sy : 1.0f;
        t.at(r, 0) = ax[r].x * k; t.at(r, 1) = ax[r].y * k; t.at(r, 2) = ax[r].z * k;
        t.at(r, 3) = -ax[r].dot(hc) * k;
    }
    std::copy(t.m, t.m + 16, drawThin_);
    const float inv = 1.0f / (hand * a.scale);
    const bool l = joint(3, hl), r = joint(4, hr);
    if (!l) hl = V3(0, 0, -1e6f);
    if (!r) hr = V3(0, 0, -1e6f);
    const float hands[8] = {hl.x, hl.y, hl.z, inv, hr.x, hr.y, hr.z, inv};
    std::copy(hands, hands + 8, drawThin_ + 16);
    out[3] = 1;
}

V3 Scene::keyLightTravel() const {
    V3 travel = V3(light_.sunDir[0], light_.sunDir[1], light_.sunDir[2]).normalized();
    if (cineMood_ > 0) {
        V3 key, rim;
        moodDirections(eye_, target_, key, rim);
        travel = key;
    }
    // never from under the horizon, never quite level: the shadows stay on the floor near the people
    if (travel.z > -0.3f) { travel.z = -0.3f; travel = travel.normalized(); }
    return travel;
}

void Scene::renderShadows(uint32_t nowMs) {
    const V3 travel = keyLightTravel();
    const V3 centre = target_;
    const V3 up = std::fabs(travel.z) > 0.95f ? V3(1, 0, 0) : V3(0, 0, 1);
    M4 view = M4::lookAt(centre - travel * kShadowEye, centre, up);
    // the centre on the map's texel grid: no shimmer at the shadows' edges as the camera drifts
    const float texel = 2 * kShadowRadius / float(gfx_.shadowQuality >= 1 ? 2048 : 1024);
    V3 c = view.point(centre);
    view = M4::translation(V3(std::floor(c.x / texel) * texel - c.x, std::floor(c.y / texel) * texel - c.y, 0)) * view;
    const float nearZ = kShadowEye - kShadowRadius * 1.4f, farZ = kShadowEye + kShadowRadius * 1.4f;
    const M4 proj = ortho(-kShadowRadius, kShadowRadius, -kShadowRadius, kShadowRadius, nearZ, farZ);
    const M4 lvp = proj * view;
    if (!postfx_.shadowBegin()) return;
    // the casters: the people; outdoors also the location's walls, furniture and ground inside the box (drawStatic culls to
    // it). In a room the place's own light is baked into it: only the people cast (onto the floor, the bed, the walls).
    bool indoors = false;
    if (location_)
        for (const StaticGroup& g : location_->groups) {
            const V3 p = centre + V3(0, 0, 0.5f);
            if (g.interior && p.x >= g.boundsMin.x && p.y >= g.boundsMin.y && p.z >= g.boundsMin.z && p.x <= g.boundsMax.x &&
                p.y <= g.boundsMax.y && p.z <= g.boundsMax.z) { indoors = true; break; }
        }
    const M4 view0 = view_, proj0 = proj_;
    view_ = view; proj_ = proj;
    litSetup(m2Prog_, lvp);
    for (auto& [id, a] : actors_)
        if (a.visible && !a.ghost && !a.firstSkin && (a.position - centre).length() < kShadowRadius * 2) drawActor(a, nowMs, false);
    if (!indoors) drawStatic(false, lvp);
    view_ = view0; proj_ = proj0;
    postfx_.shadowEnd(lvp, centre, kShadowRadius, farZ - nearZ);
}

bool Scene::gfxBegin(int& width, int& height, uint32_t nowMs) {
    postfx_.cpuBegin();
    // the people's skin and hair shading (kLitFs's uSkinFx: wrap, sheen, warm scatter, hair; all 0: as before)
    if (m2Prog_) {
        glUseProgram(m2Prog_);
        glUniform4f(gl::loc(m2Prog_, "uSkinFx"), 0.5f * gfx_.skin, gfx_.skin, gfx_.skin, gfx_.hair);
        glUniform4f(gl::loc(m2Prog_, "uSkinFx2"), gfx_.eyes, gfx_.thinSkin, 0, 0);
    }
    if (!postfx_.active()) return false;
    if (postfx_.wantsShadows()) renderShadows(nowMs);
    if (!postfx_.begin(width, height)) return false;
    width = postfx_.sceneW();
    height = postfx_.sceneH();
    return true;
}

void Scene::gfxEnd(int outW, int outH, uint32_t nowMs) {
    PostFrame f;
    f.view = view_;
    f.proj = proj_;
    // the clip planes as the frame's projection has them
    const float a = proj_.at(2, 2), b = proj_.at(2, 3);
    if (std::fabs(a - 1) > 1e-6f && std::fabs(a + 1) > 1e-6f) { f.nearZ = b / (a - 1); f.farZ = b / (a + 1); }
    const V3 travel = keyLightTravel();
    f.sunDir[0] = travel.x; f.sunDir[1] = travel.y; f.sunDir[2] = travel.z;
    std::copy(light_.sunColor, light_.sunColor + 3, f.sunColor);
    std::copy(light_.ambient, light_.ambient + 3, f.ambient);
    std::copy(light_.fogColor, light_.fogColor + 3, f.fogColor);
    f.fogStart = light_.fogStart;
    f.fogEnd = light_.fogEnd;
    // the band kept sharp: the people near the camera's target, feet to head (else the target itself)
    float lo = 1e9f, hi = -1e9f;
    for (const auto& [id, act] : actors_) {
        if (!act.visible || act.ghost || (act.position - target_).length() > 4.0f) continue;
        for (float up : {0.0f, 1.0f, 2.0f}) {
            float z = -view_.point(act.position + V3(0, 0, up * act.scale)).z;
            lo = std::min(lo, z); hi = std::max(hi, z);
        }
    }
    const float d = (target_ - eye_).length();
    if (hi < lo) { lo = d; hi = d; }
    lo = std::min(lo, d); hi = std::max(hi, d);
    f.focusNear = std::max(0.2f, lo - 0.25f);
    f.focusFar = hi + 0.35f;
    // the app's band on the subject (setFocus), where it gave one
    if (focusFar_ > 0 && focusFar_ > focusNear_) {
        f.focusNear = std::max(0.05f, focusNear_);
        f.focusFar = focusFar_;
        f.focusStrength = std::min(1.0f, std::max(0.0f, focusStrength_));
    }
    f.editor = creatorBackdrop_ != nullptr || ghost_.on || gizmoMode_ != 0;
    f.gradeActive = cineGrade_.active();
    f.seconds = float(nowMs % 1000000u) / 1000.0f;
    postfx_.end(outW, outH, f);
}

} // namespace wc
