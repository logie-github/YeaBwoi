// Spring-bone jiggle (README "Spring-bone jiggle"): the Zenless-Zone-Zero kind of soft-body motion, the pure part (no GL; engine-test/
// springbone_test.cpp). Helper bone chains of 2 to 5 segments lie inside the soft regions (the thighs' outer and inner sides, the cheeks
// of the butt, both breasts, the belly, optionally the upper arms), each rooted on the bone that carries that region (Pelvis, a thigh,
// Chest, Spine, an upper arm). Every segment's tail is a point mass sprung (an implicit damped spring, stable at any step) to where the
// parent bone would carry it rigidly, so the skeleton's motion reaches it by inertia: delayed follow-through, overshoot, a settle.
// A soft limit on the segments' stretch, a limit on how far a tail strays and collision capsules (the thighs, so a cheek does not sink
// into them) keep it a body. A chain's tails make its segments' transforms (a rotation about the segment's head and the head's offset,
// in the parent bone's bind space); the vertices carry their own weights on the segments (from their distance to the chain), and the
// vertex shader moves each vertex by the weighted transforms BEFORE the skinning (the same place the live sculpt works in): so the mesh
// follows smoothly with no tearing at the regions' borders and there is no bone limit (the transforms are a texture, not bones).
//
// Deterministic: fixed 1/60 s substeps with an accumulator (the parent's pose blended between the last call's and now), no allocation
// after build, no clocks; it sleeps at rest. Cooperating with the tissue lattice (tissue.h): the spring bones are the SECONDARY
// MOTION driven by the skeleton; the lattice is the CONTACT response (pinch, squeeze, grip). The lattice's frames ride the
// spring-bone-deformed rest (SpringBones::regionOffset), so nothing moves twice, and while the spring bones are on the lattice's own
// bone-driven lag jiggle is turned down (kLatticeJiggleShare).
#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "wmath.h"

namespace wc {

enum class SbRegion : int { Thigh = 0, Butt, Breast, Belly, Arm, Count };
constexpr int kSbRegions = int(SbRegion::Count);

enum class SbPreset : int { Natural = 0, Zzz, Subtle, Count };
constexpr int kSbPresets = int(SbPreset::Count);

// The skeleton the builder reads: the model's bone index of each joint (-1: it has none) and its pivot in the vertices' bind space.
enum SbJoint { kSbPelvis = 0, kSbSpine, kSbChest, kSbThighL, kSbThighR, kSbCalfL, kSbCalfR, kSbBreastL, kSbBreastR, kSbArmL, kSbArmR,
               kSbForearmL, kSbForearmR, kSbJoints };
struct SbSkeleton {
    int bone[kSbJoints];
    V3 pivot[kSbJoints];
    SbSkeleton() { for (int& b : bone) b = -1; }
    bool has(int j) const { return bone[j] >= 0; }
};

// One region's behaviour.
struct SbRegionParams {
    float freq = 3.0f;        // Hz of a tail's spring (root segment; the tip's is lower)
    float zeta = 0.2f;        // damping ratio (< 1: it overshoots)
    float gain = 1.0f;        // what is drawn of the tails' offset from rest (more than 1: exaggerated; the sim itself is unchanged)
    float maxOffset = 0.30f;  // the farthest a tail strays from its rest, as a share of the chain's length
    float maxAngle = 0.60f;   // radians: the most a segment turns from its rest
    float sag = 0.15f;        // the change of gravity's pull from the rest pose, in segment lengths per g (lying down sags it)
    float tipSoft = 0.15f;    // each segment further out has its frequency this much lower
};
struct SbStyle { SbRegionParams region[kSbRegions]; };
const SbStyle& sbPreset(SbPreset p);
const char* sbPresetName(SbPreset p);

// A helper chain, in the parent bone's bind space.
struct SbChain {
    SbRegion region = SbRegion::Butt;
    int side = 0;               // -1 right, +1 left, 0 centre
    int parentBone = 0;         // the model bone that carries it
    int minLevel = 1;           // 1: kept at Low, 2: High only
    int first = 0;              // its first helper's index
    int segments = 3;
    V3 root;                    // the head of segment 0 (rigid on the parent)
    V3 tail[5];                 // each segment's tail at rest
    float radius = 0.05f;       // how far from the chain a vertex still follows it (the full influence inside 0.55 of it)
    float length = 0.1f;
    std::array<int, 6> gate{{-1, -1, -1, -1, -1, -1}};   // the bones whose vertices the chain may move (the skin weights' gate)
    V3 head(int i) const { return i == 0 ? root : tail[i - 1]; }
};
struct SbCapsule { int bone = -1; V3 a, b; float r = 0.05f; };   // bind space of `bone`

constexpr int kSbMaxHelpers = 64;
constexpr int kSbMaxPerVertex = 4;
constexpr float kLatticeJiggleShare = 0.35f;   // the tissue lattice's own jiggle while the spring bones are on

struct SbRig {
    std::vector<SbChain> chains;
    std::vector<SbCapsule> capsules;
    int helpers = 0;
    float unit = 0.24f;        // the thigh's length: the scale the rig was measured at
    bool empty() const { return chains.empty(); }
};

// The rig for a body (its vertices' positions, bone indices and weights per vertex: 4 each, the weights summing to 255). Chains
// whose region has too few vertices are left out. `soft` (optional, one per vertex): the share of each vertex in a soft region, 0..1
// (what the tessellation looks at).
struct SbBodyView {
    const V3* pos = nullptr;
    const uint8_t* bones = nullptr;     // 4 per vertex
    const uint8_t* weights = nullptr;   // 4 per vertex
    size_t count = 0;
};
SbRig buildSpringRig(const SbBodyView& body, const SbSkeleton& sk);

// Per vertex: up to kSbMaxPerVertex helpers (index into the rig's, -1 none) and their weights (their sum at most 1: the rest of the vertex stays rigid).
struct SbVertexWeights {
    std::vector<int16_t> index;   // 4 per vertex
    std::vector<float> weight;    // 4 per vertex
    size_t vertices() const { return weight.size() / kSbMaxPerVertex; }
};
void computeSpringWeights(const SbRig& rig, const SbBodyView& v, SbVertexWeights& out);
// The share of each vertex that lies in a soft region (the greatest envelope of any chain), for the tessellation.
void springSoftMask(const SbRig& rig, const SbBodyView& v, std::vector<float>& mask);

struct SbSettings {
    SbPreset preset = SbPreset::Natural;
    float amount = 1.0f;       // 0 .. 2: the jiggle's size
    int level = 2;             // 0 off, 1 low (the chains marked minLevel 1), 2 high
};

class SpringBones {
public:
    void build(const SbRig& rig);
    bool built() const { return rig_.helpers > 0; }
    const SbRig& rig() const { return rig_; }
    void setSettings(const SbSettings& s);
    const SbSettings& settings() const { return set_; }
    // A region's size, 1 = the nominal (the sculpt's Bust and Butt sliders): bigger is heavier: a lower frequency and a larger swing.
    void setRegionSize(SbRegion r, float size);
    float regionSize(SbRegion r) const { return size_[int(r)]; }

    // Advances by dt seconds (clamped to 0.1) in fixed 1/60 s substeps. `world`: the actor's matrix; `bones`: the model's bone
    // matrices (bind space to model space, as the skinning takes them). `snap`: the tails jump to rest (the first frame, a teleport).
    void step(float dt, const M4& world, const M4* bones, int boneCount, bool snap = false);
    // A kick in the world: every tail within `radius` of `at` gets velocity `v` (a finger's grab or a tap), weighted by closeness.
    void kick(const V3& at, const V3& v, float radius);
    void reset();

    // The helpers' transforms (bind space of their chain's parent bone), the identity at rest: 16 floats each, column-major, the
    // vertex shader's rows (uploadable as 4 RGBA32F texels per helper). Always `helpers` of them.
    const std::vector<M4>& transforms() const { return out_; }
    bool asleep() const { return asleep_; }
    // The mean world offset of a region's tails from their rigid rest (the tissue lattice's frame follows it); false: no such chain.
    bool regionOffset(SbRegion r, int side, V3* worldDelta) const;
    // The largest offset of any tail from its rest, in the chain's lengths, and the energy (J-like units; the tests bound them).
    float maxOffsetRatio() const;
    float energy() const;
    int substepsRun() const { return ran_; }
    const V3& tailWorld(int chain, int seg) const { return st_[size_t(chain)].x[seg]; }

private:
    struct State { V3 x[5], v[5]; V3 target[5]; };
    void substep(float h, float blend);
    void collide(const M4& pw, int c);
    void emit();
    SbRig rig_;
    SbSettings set_;
    float size_[kSbRegions] = {1, 1, 1, 1, 1};
    std::vector<State> st_;
    std::vector<M4> out_;
    std::vector<M4> parent0_, parent1_;     // per chain: the parent's world matrix at the last call and now
    std::vector<M4> capture0_, capture1_;   // per capsule's bone, the same
    std::vector<M4> bonesNow_;
    M4 world_;
    float acc_ = 0;
    bool have_ = false, asleep_ = false;
    int quiet_ = 0, ran_ = 0;
    std::vector<V3> restDown_;              // per chain: gravity's direction at rest in the parent's bind space
};

// The affine inverse of a matrix (the rigid or scaled kind the bones are).
M4 sbInverse(const M4& m);

} // namespace wc
