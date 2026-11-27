// Spring-bone jiggle on the GPU side (springbone.h is the maths): one body's helper chains, its per-vertex weights as a float texture and
// the helpers' transforms as another, both read by the m2 program's vertex shader (kM2Vs: uSbOn, uSbW on unit 8, uSbM on unit 9), which
// moves each vertex by its weighted helper transforms BEFORE the skinning (as the live sculpt does), so there is no bone limit.
// The scene's hooks are Scene::stepSprings and Scene::setSpringBones (springdraw.cpp).
#pragma once

#include <cstdint>
#include <vector>

#include "hdbody.h"
#include "springbone.h"
#include "tessellate.h"

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

// The spring rig of an HD fit: the skeleton's joints from the model's bones and the fit's rig pivots, the chains from the body's vertices.
SbRig hdSpringRig(const M2Model& m, const HdFit& fit);
// The same for the game's own model: its bones' pivots and the vertices that are drawn.
SbRig modelSpringRig(const M2Model& m, const std::vector<V3>& pivots, const std::vector<M2Vertex>& verts);
// The HD body's soft regions tessellated in the fit (tessellate.h): edges there at most `edgeShare` x the thigh's length (0.0095: about 4 mm
// on a woman), at most `cap` new vertices; the body's vertices stay first (bodyVertexEnd, the other parts' indices shift). Returns the vertices added.
size_t hdTessellateSoft(const M2Model& m, HdFit& fit, float edgeShare, size_t cap, TessStats* stats = nullptr);

} // namespace wc
