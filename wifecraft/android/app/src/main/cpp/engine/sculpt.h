// The creator's sculpt sliders on a skinned body (the Eve body: evescene.cpp applies them after fitting it): the bind-pose
// vertices moved by smooth regions made from their bone weights, so the body still bends with its bones as before.
//
//   - Regions: each bone belongs to a group (breast, chest, waist, pelvis, thigh, calf, upper arm, forearm, clavicle, other) by
//     its WoW-side name, a bone without a name taking its parent's (sculptRigOf). A vertex's mask for a group is its weight
//     on that group's bones (0..1), so the regions blend as smoothly as the skinning does; a few masks are shaped further by
//     where the vertex is (the front for the bust and belly, the back for the butt, a soft band in height for the hips).
//   - Moves: a bone axis's radial scale (thighs, arms), a scale about a centre (bust), a scale across the body (hips,
//     waist), a push along a direction (belly forward, butt back, bust lift up, shoulders outward). Every displacement is a
//     slider (-1..1) times a mask (0..1) times a fixed reach (a share of the body's own hip height), so it is bounded
//     ([kSculptMaxShare] of the hip height at most), mirror-symmetric across the body (x forward, y left, z up: every move
//     either keeps y's sign or scales it), and 0 for every slider leaves the body exactly as it was.
//   - The neck and ankle rings (where Eve meets the Warcraft head and feet) are weighted to the neck, head and ankle bones,
//     which no move touches; [SculptOptions::pinned] keeps any other vertex (a seam) where it is.
//   - Normals: those of moved vertices are made again from the triangles round them, shared by position (a UV seam's two
//     copies keep one normal), blended in by how far the vertex moved.
// Slider order as the app's (creator/BodySculpt.kt SculptSlider). Height is the actor's scale (the app's), not a move here;
// leg length needs the skeleton changed and is not a move here either (the app shows it greyed).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

enum SculptSlider {
    kSculptHeight = 0, kSculptShoulders, kSculptArms, kSculptBust, kSculptBustLift, kSculptWaist, kSculptBelly, kSculptHips,
    kSculptButt, kSculptThighs, kSculptLegLength, kSculptCount
};

enum SculptGroup : uint8_t {
    kGroupOther = 0, kGroupBreastL, kGroupBreastR, kGroupChest, kGroupWaist, kGroupPelvis, kGroupThighL, kGroupThighR,
    kGroupCalf, kGroupUpperArmL, kGroupUpperArmR, kGroupForearmL, kGroupForearmR, kGroupClavicle, kGroupNeck, kGroupCount
};

constexpr float kSculptMaxShare = 0.12f;   // the most any vertex moves: this share of the hip height

// The skeleton as the sculpt needs it: each bone's group, and the joints its moves are about (bind pose, model space).
struct SculptRig {
    std::vector<uint8_t> group;           // per bone
    V3 pelvis, waist, chest, neck;        // spine joints
    V3 breast[2];                         // left, right (the breast bones' pivots, or estimates)
    V3 hip[2], knee[2], ankle[2];         // the thighs' and calves' joints
    V3 shoulder[2], elbow[2], wrist[2];   // the arms'
    bool hasBreastBones = false;
    float unit = 1;                       // the hip height (pelvis z over the feet), the moves' scale
    bool ok = false;
};

// A rig from bone pivots, parents and the named bones' indices (the pure core: tests build their own). `named` holds, per
// group, the bones that start it ([sculptRigOf] finds them by name in a model).
struct SculptBones {
    int pelvis = -1, waist = -1, chest = -1, neck = -1, head = -1;
    int breast[2] = {-1, -1}, clavicle[2] = {-1, -1}, upperArm[2] = {-1, -1}, forearm[2] = {-1, -1}, hand[2] = {-1, -1};
    int thigh[2] = {-1, -1}, calf[2] = {-1, -1}, ankle[2] = {-1, -1};
};
SculptRig sculptRig(const std::vector<V3>& pivots, const std::vector<int16_t>& parents, const SculptBones& b);
SculptRig sculptRigOf(const M2Model& m);   // bodyrig.cpp

struct SculptOptions {
    std::vector<uint32_t> pinned;   // vertices never moved
};

struct SculptStats {
    size_t moved = 0;
    float maxMove = 0;              // in model units
    size_t flipped = 0;             // triangles whose facing turned over (should stay 0)
};

// The sliders (kSculptCount values, -1..1; fewer: the rest 0) on `verts` (bind pose, the rig's bone indices). `indices`:
// the triangles (for the normals and the flip count; may be empty). Returns how many vertices moved.
size_t applySculpt(const SculptRig& rig, std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                   const float* values, size_t count, const SculptOptions& opt = {}, SculptStats* stats = nullptr);

// Whether any slider the mesh takes is set (0 for all of them: nothing to do).
bool sculptActive(const float* values, size_t count);

} // namespace wc
