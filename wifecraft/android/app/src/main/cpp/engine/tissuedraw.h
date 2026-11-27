// Soft tissue on the GPU side (tissue.h is the maths): one body's lattices (breasts, buttocks, belly, outer thighs), their field as a 3D
// texture the m2 program's vertex shader samples (kM2Vs: uTsN, uTsTex on unit 5, uTsA, uTsI, uTsDim), and the hooks to the scene
// (Scene::stepTissue, tissuedraw.cpp). Where a lattice covers the skin the analytic dent of contactdraw.h steps aside; garments follow
// the field at uCtLayer (ContactDraw::layer) as they do the dent.
#pragma once

#include <vector>

#include "tissue.h"

namespace wc {

constexpr int kTissueRegions = 7;      // breast L R, butt L R, belly, thigh L R
constexpr int kTissueTexXY = 14;       // the texture's footprint per region: the lattice's largest side
constexpr int kTissueTexZ = kTissueLayers * kTissueRegions;

struct TissueDraw {
    TissueRegion reg[kTissueRegions];
    bool on[kTissueRegions] = {};      // the region is built and live for this body
    int builtLevel = -1;               // the level the lattices were built for
    float builtUnit = 0;               // the body's unit then (a sculpt that changes it builds them again)
    bool uploaded = false;
    bool needUpload = true;
    unsigned tex = 0;
    int usedN = 0;                     // regions in the uniforms
    int slice[kTissueRegions] = {};
    int mapped[kTissueRegions] = {};   // the region each uniform entry is
    float uA[kTissueRegions * 12] = {};
    float uI[kTissueRegions * 4] = {};
    std::vector<uint16_t> half;
    std::vector<TissueHandle> manual;  // fed every frame (the desktop check's grips; a pinch)
    int handles = 0;                   // the last frame's handles on all regions (stats)

    bool active() const { return usedN > 0 && uploaded; }
    void uniforms(unsigned program) const;                 // GL thread: bind the field and set the block (usedN 0: off)
    static void off(unsigned program);
    static void initGl(unsigned program);                  // the empty 3D texture on unit 5, the samplers' units
    void forget() { tex = 0; uploaded = false; needUpload = true; }
    void upload();                                         // GL thread: the field to the texture
};

} // namespace wc
