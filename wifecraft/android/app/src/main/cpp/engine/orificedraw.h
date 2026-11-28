// Toy interaction in the scene (README "Toy interaction"): one toy at a time held by the finger, on one actor. The maths are
// orifice.h's; here the scene's side: where the toy is (free in front of the body, then snapped to the entry's axis and pushed in),
// the HD body's vulva deformed around it (its vertices moved in bind space and uploaded for the region only), the interior sleeve
// and the rim overlay drawn, the toy itself drawn by the prop path (propmesh.h). Scene::setToy / toyInfo / stepToy / toyBody /
// drawToy are defined in orificedraw.cpp.
//
// Draw order: the HD body (skin, with the opening moved), the sleeve (depth tested: the skin hides it but for the opening), the
// rim's thin overlay, then the toy with the depth test (inside, the skin and the sleeve's near wall hide it; through the opening it
// shows).
#pragma once

#include <cstdint>
#include <vector>

#include "m2.h"
#include "orifice.h"
#include "wmath.h"

namespace wc {

// What the screen tells the engine each frame about the toy (Native.setToy).
struct ToyInput {
    int mode = 0;            // 0 none, 1 held free (the finger at fx, fy), 2 snapped to the entry (depth)
    float fx = 0, fy = 0;    // the finger, in pixels of a width x height view
    int width = 0, height = 0;
    float depth = 0;         // mode 2: the tip's depth past the entry plane, Sims metres (negative: outside, in front of it)
    float vibration = 0;     // 0..1
    float roll = 0;          // turn about the toy's own axis, radians
    bool hidden = false;     // the toy not drawn (the desktop check of the opening alone)
};

// What the screen asks of the engine (Native.toyInfo): where the entry is on screen and how long the toy's insertable part is.
struct ToyInfo {
    bool ready = false;      // the entry was found and the camera known
    bool hd = false;         // the body is an HD figure (its opening is geometry); else the game's body (no opening)
    float entryX = 0, entryY = 0;   // the entry's centre, pixels
    float axisX = 0, axisY = 1;     // the way in on screen (unit)
    float pxPerMetre = 0;           // pixels one Sims metre along the way in covers on screen
    float insertLength = 0;         // the toy's insertable length, Sims metres (0: the prop is not measured)
    float radius = 0;               // the opening's radius now, Sims metres
    float wet = 0;                  // 0..1
    float depth = 0;                // the tip's depth now, Sims metres
    float tipX = 0, tipY = 0;       // the toy's tip on screen
};

struct ToyState {
    bool active = false;
    int actor = -1;
    uint64_t guid = 0;
    bool doubleEnded = false;
    ToyInput in;
    // measured
    orifice::ToyShape shape;
    uint64_t shapeGuid = 0;
    orifice::Frame frame;
    bool frameOk = false, frameHd = false;
    int frameBone = -1;
    uint64_t frameKey = 0;
    orifice::Dyn dyn;
    uint32_t lastMs = 0;
    bool started = false;
    // the toy's matrix (prop metres to the scene) and tip, as last placed
    M4 propModel;
    V3 tipWorld;
    bool placed = false;
    // the camera as last drawn
    M4 vp, invVp;
    V3 eye;
    bool haveCam = false;
    // the HD region (indices into the fit's vertices), the vertices as they are at rest, their normals and incident triangles
    std::vector<uint32_t> region;
    std::vector<int32_t> slot;
    std::vector<V3> restPos, restN, restCalc;
    std::vector<uint32_t> triStart, triList;
    std::vector<M2Vertex> stage;
    std::vector<std::pair<uint32_t, uint32_t>> runs;
    std::vector<V3> work;
    std::vector<uint32_t> bodyTris;
    uint64_t uploadedFor = 0;
    unsigned uploadedVbo = 0;
    bool gpuDeformed = false;
    float lastRadius = -1, lastDrag = -1, lastTremor = -1;
    // GL
    unsigned sleeveProg = 0, rimProg = 0;
    unsigned sVao = 0, sVbo = 0, sIbo = 0, rVao = 0, rVbo = 0, rIbo = 0;
    orifice::Sleeve sleeve;
    orifice::Rim rim;
    void forgetGl() { sleeveProg = rimProg = 0; sVao = sVbo = sIbo = rVao = rVbo = rIbo = 0; uploadedVbo = 0; }
};

} // namespace wc
