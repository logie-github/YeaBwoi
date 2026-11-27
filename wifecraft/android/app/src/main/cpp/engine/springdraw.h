// Spring-bone jiggle on the GPU side (springbone.h is the maths): one body's helper chains, its per-vertex weights as a float texture and
// the helpers' transforms as another, both read by the m2 program's vertex shader (kM2Vs: uSbOn, uSbW on unit 8, uSbM on unit 9), which
// moves each vertex by its weighted helper transforms BEFORE the skinning (as the live sculpt does), so there is no bone limit.
// The scene's hooks are Scene::stepSprings and Scene::setSpringBones (springdraw.cpp).
#pragma once

#include <cstdint>
#include <vector>

#include "springbone.h"

namespace wc {

struct SpringDraw {
    SpringBones sim;
    SbVertexWeights weights;       // per vertex of the drawn buffer (the HD fit's vertices)
    uint64_t builtKey = 0;         // the fit it was built for
    bool ready = false;            // rig built and weights uploaded
    unsigned wTex = 0, mTex = 0;
    int helpers = 0;
    std::vector<float> packed;     // the weights' texels (width kSpringTexW)
    bool needUpload = false;
    float builtMs = 0;
    size_t softVertices = 0;       // vertices with a helper weight (stats)
    bool active() const { return ready && wTex && mTex && sim.built(); }
    void uniforms(unsigned program) const;     // GL thread: bind the textures and turn it on (not active: off)
    static void off(unsigned program);
    void forgetGl() { wTex = mTex = 0; needUpload = true; }
};
constexpr int kSpringTexW = 2048;

} // namespace wc
