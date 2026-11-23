// The graphics options' offscreen chain (graphics_settings.h): the scene is drawn into a picture of its own (RGBA16F where the
// GPU can draw into it, else RGBA8; 4x or 2x multisampled and resolved when asked; at the render scale's size) with a depth
// texture, then, in order:
//
//   shadow map   the key light's depth from above the people (a box 2.6 yards round the camera's target: the pair, the bed,
//                the furniture by them; casters further out are clipped), 1024 or 2048 px. Drawn before the scene.
//   AO           half size: ambient occlusion from the depth alone (normals from the depth's neighbours), and contact
//                shadows (a short march toward the key light in the depth); then a depth-aware blur each way.
//   lighting     the scene's colour times its AO (weighted to the light's ambient share), its shadow (PCF, or PCSS-lite:
//                the penumbra widening with the blocker's distance) and contact shadow, and the area's depth haze.
//   bloom        a soft threshold, then a dual-filter (Kawase) pyramid down to 1/32 and back up to half size.
//   depth of field  half size: each pixel's circle of confusion from its depth (0 over the band kept sharp: the people
//                by the camera's target), a 24-tap disc gather that keeps a sharp near thing from taking the blur behind it.
//   light shafts quarter size: the bright sky and windows blurred toward the sun's place on the screen.
//   final        to the framebuffer that was bound (the screen, or cinematic mode's grade picture: grade.h, which then
//                grades it): the resample to the view's size with contrast-adaptive sharpening, chromatic aberration,
//                depth of field's mix, bloom and shafts added, the tone curve and exposure, vignette and grain (left to
//                the grade while it draws its own), a little dither.
//
// Each step is skipped when its effect is off; GPU timer queries (EXT_disjoint_timer_query) time each one when asked.
// OpenGL ES 3.0 (multisampling by renderbuffers and a resolve blit; float targets with EXT_color_buffer_(half_)float).
#pragma once

#include <cstdint>

#include "graphics_settings.h"
#include "wmath.h"

namespace wc {

// What the scene tells the chain each frame.
struct PostFrame {
    M4 view, proj;
    float nearZ = 0.2f, farZ = 4000;
    float sunDir[3] = {0, 0, -1};   // the way the key light travels (world)
    float sunColor[3] = {1, 1, 1};
    float ambient[3] = {0.5f, 0.5f, 0.5f};
    float fogColor[3] = {0.7f, 0.7f, 0.8f};
    float fogStart = 120, fogEnd = 900;
    float focusNear = 0, focusFar = 0;   // the view distances kept sharp (focusFar <= 0: depth of field off this frame)
    float focusStrength = 1;             // 0..1 laid over the blur (the subject near: full; far shots: less)
    bool editor = false;                 // the creator (depth of field off unless dofInEditor)
    bool gradeActive = false;            // cinematic mode's grade draws the vignette and grain itself
    float seconds = 0;
};

// The pass timings, ms (the last finished frame's GPU times; NaN where unknown).
struct PostStats {
    enum Stage { kShadow = 0, kScene, kAo, kLighting, kBloom, kDof, kShafts, kFinal, kStages };
    float gpu[kStages];
    float cpuMs = 0;          // Scene::render from the shadow pass to the final pass, on the CPU
    bool timersAvailable = false;
    int sceneW = 0, sceneH = 0;
};

class PostFx {
public:
    PostFx();
    void configure(const GraphicsSettings& s) { s_ = s; }
    const GraphicsSettings& settings() const { return s_; }
    bool active() const { return s_.post() && !failed_; }
    bool wantsShadows() const { return active() && s_.shadows > 0; }

    // The shadow map's target bound (cleared), its size as the viewport. False: none (no shadows this frame).
    bool shadowBegin();
    // The shadow pass done: `lightViewProj` is what it was drawn with; the framebuffer bound before shadowBegin bound again.
    void shadowEnd(const M4& lightViewProj, const V3& centre, float radius, float depthRange);

    // The scene's target bound for a view `width` x `height` (the framebuffer bound now is where `end` draws); `sceneW` and
    // `sceneH` are its size (the render scale's). False: the chain can't be had (drawn straight as before).
    bool begin(int width, int height);
    int sceneW() const { return sw_; }
    int sceneH() const { return sh_; }
    // Everything after the scene, onto the framebuffer bound at `begin`, `outW` x `outH`. Leaves it bound.
    void end(int outW, int outH, const PostFrame& f);

    // The frame's CPU start (Scene::render) and the stats as last measured.
    void cpuBegin();
    const PostStats& stats() const { return stats_; }
    // The harness: each stage timed on the CPU with glFinish around it (software GL has no timer queries). Slows the frame.
    void setSyncTiming(bool on) { syncTiming_ = on; }

    void release();   // GL objects deleted (the context is still there)
    void forget();    // forgotten (the context is gone)

private:
    struct Target {
        unsigned fbo = 0, tex = 0;
        int w = 0, h = 0;
        unsigned fmt = 0;
    };
    bool ensure(Target& t, int w, int h, unsigned internalFormat, bool linear);
    void drop(Target& t);
    bool ensurePrograms();
    void bindTarget(const Target& t);
    void quad();
    void stageBegin(int stage);
    void stageEnd();
    void collectTimers();

    GraphicsSettings s_;
    bool failed_ = false, progsTried_ = false;
    unsigned colourFmt_ = 0;   // RGBA16F or RGBA8
    // the scene's target: colour and depth textures (resolved), and the multisampled renderbuffers when asked
    unsigned fbo_ = 0, colour_ = 0, depth_ = 0;
    unsigned msFbo_ = 0, msColour_ = 0, msDepth_ = 0;
    int msSamples_ = 0;
    int sw_ = 0, sh_ = 0;
    int prevFbo_ = 0;
    // the shadow map
    unsigned shadowFbo_ = 0, shadowTex_ = 0, samplerCmp_ = 0, samplerRaw_ = 0;
    int shadowSize_ = 0;
    bool shadowValid_ = false;
    M4 lightViewProj_;
    V3 shadowCentre_;
    float shadowRadius_ = 0, shadowRange_ = 1;
    int shadowPrevFbo_ = 0;
    int shadowViewport_[4] = {0, 0, 0, 0};
    // the passes' pictures
    Target aoA_, aoB_, lit_, coc_, dof_, shafts_;
    static constexpr int kBloomLevels = 5;
    Target bloom_[kBloomLevels];
    unsigned vao_ = 0;
    unsigned pAo_ = 0, pBlur_ = 0, pLight_ = 0, pDown_ = 0, pUp_ = 0, pCoc_ = 0, pDof_ = 0, pShafts_ = 0, pFinal_ = 0;
    // timers: a ring of frames, a query per stage
    static constexpr int kRing = 4;
    unsigned queries_[kRing][PostStats::kStages] = {};
    bool queryUsed_[kRing][PostStats::kStages] = {};
    int ring_ = 0;
    int openStage_ = -1;
    bool timersTried_ = false, timersOk_ = false;
    bool syncTiming_ = false;
    double stageStart_ = 0;
    double cpuStart_ = 0;
    PostStats stats_;
};

} // namespace wc
