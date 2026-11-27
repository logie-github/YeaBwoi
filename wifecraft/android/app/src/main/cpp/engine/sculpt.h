// The creator's sculpt sliders on a skinned body (an HD figure: hdscene.cpp applies them after fitting it): the bind-pose
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
//   - The neck and ankle rings (where an HD figure's own body is cut off from the Warcraft head and feet) are weighted to the neck, head and ankle bones,
//     which no move touches; [SculptOptions::pinned] keeps any other vertex (a seam) where it is.
//   - Normals: those of moved vertices are made again from the triangles round them, shared by position among the copies
//     that faced alike before (a UV seam's two keep one normal; an overlapping hidden geoset's do not), blended in by how far
//     the vertex moved.
//   - The game's own bodies (every race's default model, both sexes: scene.cpp refreshBody, [SculptOptions::gameBody]) take
//     the same moves on the model's whole vertex list (the skin and the clothing geosets on it, so a robe or sleeves follow),
//     with the spine followed along its curve (an orc's chest stands far forward of its pelvis), the limbs' moves faded out
//     before the wrists and ankles (the hands and feet keep their size and seams), and any triangle that would turn over or
//     collapse eased back (its corners' moves halved, by place, up to six times, then dropped). Their women's bust is a soft
//     mound by place round each breast bone (their breast bones' weights are too light and patchy to carry it), and the bust
//     grows round (outward from its centre) rather than drawn to a point; Droop turns it down about its root.
//   - The Bust & Butt designer's shapes (after the twenty-two): the bust's projection, droop, upper fullness, roundness, side
//     fullness and underside about each breast; the butt's width, projection, roundness, cheek gap, hip dip and underbutt about
//     each cheek's centre (below the hip joints, behind); a man's pectorals and abs.
// Slider order as the app's (creator/BodySculpt.kt SculptSlider). Height is the actor's scale (the app's), not a move here;
// leg length is the skeleton's (bodyrig.h), not a move here either. Body Fat and Muscle are no moves of their own: each adds
// shares of the others (more waist, belly, hips, thighs...; more shoulders, arms, chest, calves...).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

enum SculptSlider {
    kSculptHeight = 0, kSculptShoulders, kSculptArms, kSculptBust, kSculptBustLift, kSculptWaist, kSculptBelly, kSculptHips,
    kSculptButt, kSculptThighs, kSculptLegLength,
    // added after the first eleven (older saves' arrays end before them)
    kSculptNeck, kSculptChest, kSculptBustWidth, kSculptBustSpacing, kSculptShoulderSlope, kSculptUpperArms, kSculptForearms,
    kSculptCalves, kSculptButtLift, kSculptBodyFat, kSculptMuscle,
    // the bust and butt designer's shape sliders (added after the twenty-two; arrays that end before them leave them at 0)
    kSculptBustProjection, kSculptBustDroop, kSculptBustUpper, kSculptBustRound, kSculptBustSide, kSculptUnderbust,
    kSculptButtWidth, kSculptButtProjection, kSculptButtRound, kSculptCheekGap, kSculptHipDip, kSculptUnderbutt,
    kSculptPecs, kSculptAbs, kSculptCount
};
// The values array: the kSculptCount sliders, then (optional) each one's side balance (the body's left side takes the slider
// plus it, the right the slider minus it; 0: both alike, as the creator's Symmetry keeps them). Height and Leg Length have none.
constexpr int kSculptValues = 2 * kSculptCount;

enum SculptGroup : uint8_t {
    kGroupOther = 0, kGroupBreastL, kGroupBreastR, kGroupChest, kGroupWaist, kGroupPelvis, kGroupThighL, kGroupThighR,
    kGroupCalf, kGroupUpperArmL, kGroupUpperArmR, kGroupForearmL, kGroupForearmR, kGroupClavicle, kGroupNeck, kGroupHead,
    kGroupFoot, kGroupCount
};

constexpr float kSculptMaxShare = 0.12f;   // the most any vertex moves: this share of the hip height

// The skeleton as the sculpt needs it: each bone's group, and the joints its moves are about (bind pose, model space).
struct SculptRig {
    std::vector<uint8_t> group;           // per bone
    V3 pelvis, waist, chest, neck, head;  // spine joints
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
SculptRig sculptRigOf(const M2Model& m);   // by the model's WoW-side bone names

struct SculptOptions {
    std::vector<uint32_t> pinned;   // vertices never moved
    bool gameBody = false;          // the game's own body (the curved spine, the limbs' ends kept; the Neck slider on)
};

struct SculptStats {
    size_t moved = 0;
    float maxMove = 0;              // in model units
    size_t flipped = 0;             // triangles whose facing turned over (should stay 0)
    size_t eased = 0;               // triangles eased back so as not to turn over or collapse
};

// The sliders (up to kSculptValues values, -1..1; fewer: the rest 0) on `verts` (bind pose, the rig's bone indices). `indices`:
// the triangles (for the normals and the flip count; may be empty). Returns how many vertices moved.
size_t applySculpt(const SculptRig& rig, std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                   const float* values, size_t count, const SculptOptions& opt = {}, SculptStats* stats = nullptr);

// Whether any slider the mesh takes is set (0 for all of them: nothing to do).
bool sculptActive(const float* values, size_t count);

// The live sculpt (scene.cpp draws it on the GPU while a slider moves; bodyrig.cpp sculptSync). Every move above is a slider
// times a fixed field (the masks depend on the body before the sculpt, not on the values), so each move slider's field for a
// value of 1 is made once per body and the vertex shader blends them by the sliders' change since the last CPU sculpt: a drag
// changes uniforms only. Kept sparse: per vertex the sliders that move it more than kSculptMorphFloor of the hip height (the
// bust's near the chest, the butt's near the hips...). What it leaves to the CPU sculpt that follows when the sliders rest:
// the 12% cap, the eased triangles, Body Fat's and Muscle's clamps (1.5) and the normals (all exact again once it has run).
constexpr float kSculptMorphFloor = 1e-5f;
struct SculptMorph {
    std::vector<uint32_t> first;    // per vertex, and one more: its entries are [first[v], first[v + 1])
    std::vector<float> side;        // per vertex: the share of a balance it takes (1 her left, -1 her right, between at the middle)
    std::vector<uint8_t> channel;   // per entry: the slider (SculptSlider)
    std::vector<V3> delta;          // per entry: its move for a value of 1 (model units, bind pose)
    float unit = 1;                 // the rig's hip height
    size_t vertices() const { return first.empty() ? 0 : first.size() - 1; }
    size_t entries() const { return delta.size(); }
    bool ok() const { return !first.empty(); }
};
// The fields of every move slider on `verts` (the body before the sculpt; the same options as applySculpt's). On the game's
// bodies the copies of a vertex at one place share their mean, as applySculpt makes them.
bool buildSculptMorph(const SculptRig& rig, const std::vector<M2Vertex>& verts, const SculptOptions& opt, SculptMorph& out);
// The weights of the fields for slider values (as applySculpt takes them): `sym` per slider for both sides, `bal` per slider
// times the vertex's side (kSculptCount each; Body Fat's and Muscle's shares folded into the moves they add to).
void sculptMorphWeights(const float* values, size_t count, bool gameBody, float* sym, float* bal);
// The vertex shader's sum on the CPU (the tests): each position plus its fields times their weights.
void applySculptMorph(const SculptMorph& m, const float* sym, const float* bal, std::vector<V3>& positions);

} // namespace wc
