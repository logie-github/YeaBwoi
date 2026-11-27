// Flesh contact on the GPU side (contact.h is the maths): one body's solver, its touch, and the uniform block the m2 program's vertex
// shader takes (kM2Vs: uCtN, uCtA, uCtB, uCtC, uCtLayer, uCtShade). Any draw that uses the m2 program after ContactDraw::uniforms
// follows the skin: the HD figure (its skin part with the full dent, its other parts at kGarmentLayer of it), the game's model, the
// stockings and the parts. API for other layers (clothing, stockings): ContactDraw::layer(program, scale, shade) before a draw
// (scale: the share of the dent that surface follows, 0.85 for an under-layer; shade 0: no pressure colour on it); ContactDraw::off
// after the actor.
#pragma once

#include "contact.h"

namespace wc {

constexpr float kGarmentLayer = 0.85f;   // a garment on the skin follows 85% of its dent (so the skin never pokes through)

struct ContactDraw {
    ContactSolver solver;
    // a touch (Interact: pushTouch): held until released or kTouchHoldMs has passed since the last push
    struct Touch { bool on = false; ContactIn in; uint32_t lastMs = 0, holdMs = 1500; };
    static constexpr uint32_t kTouchHoldMs = 1500;   // (the default hold: a tap asks for less)
    Touch touch;
    std::vector<ContactIn> manual;       // fed every frame (the desktop check's presses; Scene::setContactManual)
    int derived = 0;                     // the last frame's derived contacts (stats)
    float radiusScale = 1;               // the dent's radius times this: a coarse mesh (the game's own body) needs a broader dent than its triangles can show

    bool active() const { return solver.count() > 0; }
    void uniforms(unsigned program) const;                  // GL thread: the block for the contacts now (none: uCtN 0)
    static void off(unsigned program);
    static void layer(unsigned program, float scale, float shade);
};

} // namespace wc
