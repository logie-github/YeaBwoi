// The graphics options on the scene (graphics_settings.h, postfx.h): the settings and their stats, the key light's shadow
// pass, the frame's start and end round Scene::render's drawing, and the skin and hair shading's numbers.
#include "scene.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>

#include "daylight.h"

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
    f.editor = creatorBackdrop_ != nullptr || ghost_.on || gizmoMode_ != 0;
    f.gradeActive = cineGrade_.active();
    f.seconds = float(nowMs % 1000000u) / 1000.0f;
    postfx_.end(outW, outH, f);
}

} // namespace wc
