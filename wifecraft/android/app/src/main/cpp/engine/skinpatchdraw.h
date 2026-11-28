// Fine skin patches on the GPU side (skinpatch.h is the maths): one body's patches (up to kMaxPatches, each a 32 x 32 block of one RGBA16F
// 2D texture on unit 8) and the uniform block the m2 program's vertex and fragment stages take (kM2Vs, kLitFs: uPsN, uPsC, uPsU, uPsV,
// uPsW, uPsTex). Scene::stepSkinPatches (skinpatchdraw.cpp) creates a patch on the skin where a finger, a grip or a pinch lands, binds it to
// a bone so it rides the body, feeds it the pads, and lets it go when it has settled.
#pragma once

#include <cstdint>
#include <vector>

#include "skinpatch.h"
#include "wmath.h"

namespace wc {

constexpr int kMaxPatches = 3;
constexpr int kPatchUnit = 8;

struct PatchSlot {
    bool on = false;
    SkinPatch sim;
    int bone = -1;                 // the bone whose bind space holds the frame
    V3 oB, t1B, t2B, nB;           // the frame in that bind space
    V3 oW, t1W, t2W, nW;           // the frame now (world)
    float unit = 1;                // world units per metre
    float thickness = 0.02f;
    float lastPad = 0;             // seconds since a pad was on it
    bool dirty = true;             // its texels are to be uploaded
    uint32_t born = 0;
};

struct SkinPatchDraw {
    PatchSlot slot[kMaxPatches];
    int usedN = 0;
    int mapped[kMaxPatches] = {};  // the slot each uniform entry is
    float uC[kMaxPatches * 4] = {}, uU[kMaxPatches * 4] = {}, uV[kMaxPatches * 4] = {}, uW[kMaxPatches * 4] = {};
    unsigned tex = 0;
    bool uploaded = false, needUpload = true;
    std::vector<uint16_t> half;
    int pads = 0;                  // the last frame's pads (stats)
    float stepMs = 0;              // the last frame's CPU time in the solver (ms)

    bool active() const { return usedN > 0 && uploaded; }
    void uniforms(unsigned program) const;
    static void off(unsigned program);
    static void initGl(unsigned program);
    void forget() { tex = 0; uploaded = false; needUpload = true; }
    void upload();
    int liveCount() const { int n = 0; for (const PatchSlot& s : slot) n += s.on; return n; }
};

} // namespace wc
