// Soft tissue: the chest, the belly, the rear, the thighs and the upper arms hang under gravity and bounce, lag and
// settle as the body moves (Options' Soft body: Off, Subtle, Natural, Strong).
//
// Each region is one simulated point, a damped spring hung on the bone that carries it (the chest, the lower spine,
// the pelvis, a thigh, an upper arm), simulated on the CPU at a fixed 120 Hz in the world, driven by that bone's own
// motion (its successive positions, so turns and swings as much as steps and hops) and by gravity. The vertex shader
// moves the region's skin by the point's displacement: weighted by where the vertex lies in the region (fading to zero
// at its edges and at the body behind it) and by which bones carry the vertex (a hand, the head or a calf by the chest
// never moves with it). No per-vertex work on the CPU per frame: a handful of uniforms.
//
// SoftSim is the pure maths (no GL; engine-test/softbody_test.cpp). softRig() finds the regions on a model from its
// bones and its skin's vertices (the shaped ones where a Shape is picked: a heavier shape hangs lower and swings more).
#pragma once

#include <cstdint>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

constexpr int kSoftMaxRegions = 10;

enum class SoftKind : uint8_t { Chest, Belly, Rear, Thigh, Arm };

// Off, Subtle, Natural (the default), Strong.
enum SoftLevel : int { kSoftOff = 0, kSoftSubtle = 1, kSoftNatural = 2, kSoftStrong = 3 };

struct SoftRegion {
    SoftKind kind = SoftKind::Chest;
    int bone = -1;            // the bone that carries it
    V3 centre;                // the region's base (model space, at rest): where its skin starts to move
    V3 radii{0.1f, 0.1f, 0.1f};   // half sizes along the model's axes
    V3 axis;                  // outward (the chest's and the belly's front, the rear's back); 0: the whole ellipsoid moves
    float mask[4] = {0, 0, 0, 0};  // how much a vertex carried by each bone class (chest, lower torso, thigh, arm) moves
    V3 mass;                  // the simulated point (model space, at rest): inside the moving flesh
    float weight = 1;         // heavier (a fuller shape): hangs lower, swings slower
    float size = 0.1f;        // its lateral half size, for the displacement limit
};

// What softRig found: the regions, and each bone's class (4 floats per bone: chest, lower torso, thigh, upper arm).
struct SoftRig {
    std::vector<SoftRegion> regions;
    std::vector<float> boneClass;
};

// `verts`, `indices`, `submeshes` the body as drawn (a shaped body's own, or the model's), `geosets` the geosets shown
// (empty: all). A shaped body is also compared with the model's own: how far the shape filled each region out.
SoftRig softRig(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                const std::vector<M2Submesh>& submeshes, const std::vector<uint16_t>& geosets);

// One region's tuning at a level (softTuning).
struct SoftTuning {
    float freq = 3;           // Hz, for weight 1
    float damping = 0.3f;     // ratio of critical
    float sag = 0.3f;         // gravity's share beyond what the model was made hanging with (the rest sag)
    float limit = 0.25f;      // the largest displacement drawn, as a share of the region's size
    float gain = 1;           // how much of the simulated displacement is drawn
    V3 stiffness{1, 1, 1};    // along the carrying bone's forward, side and up (1: as `freq` says)
};
SoftTuning softTuning(SoftKind kind, int level);

class SoftSim {
public:
    static constexpr int kHz = 120;          // the fixed step
    static constexpr uint32_t kMaxFrameMs = 250;   // a longer gap (a pause, a hitch, the app away) holds still
    static constexpr int kMaxSteps = 8;      // at most this much simulated time per frame (66 ms)
    static constexpr float kGravity = 9.81f; // model units (about a metre) per second squared
    static constexpr float kTeleport = 0.5f; // a carrying point moving farther in one frame was put there, not moved

    void setRegions(const std::vector<SoftRegion>& r) { regions_ = r; state_.assign(r.size(), State()); started_ = false; }
    const std::vector<SoftRegion>& regions() const { return regions_; }
    void setLevel(int level) { level_ = level; }
    int level() const { return level_; }
    void reset() { started_ = false; }

    // Advances to `nowMs` with the bones' model-space matrices and the actor's world matrix. Time going backwards
    // starts again from rest (the same frames give the same result).
    void step(const std::vector<M4>& bones, const M4& world, uint32_t nowMs);

    // The displacement drawn for region i (model space, posed), how much its sides draw in (a share of the offset
    // from its centre), and its centre now (model space, posed).
    V3 offset(size_t i) const { return i < state_.size() ? state_[i].out : V3(); }
    float squash(size_t i) const { return i < state_.size() ? state_[i].squash : 0; }
    V3 centreNow(size_t i) const { return i < state_.size() ? state_[i].centre : V3(); }
    // The simulated displacement (world), before the drawing limit: for the tests.
    V3 raw(size_t i) const { return i < state_.size() ? state_[i].u : V3(); }
    // The displacement the region rests at, carried as now (world).
    V3 rest(size_t i) const { return i < state_.size() ? state_[i].eq : V3(); }

private:
    struct State {
        V3 x, v;              // the point and its velocity (world)
        V3 anchor;            // where it is carried to, at the last step (world)
        V3 u;                 // x - anchor
        V3 eq;
        V3 out, centre;
        float squash = 0;
    };
    std::vector<SoftRegion> regions_;
    std::vector<State> state_;
    int level_ = kSoftNatural;
    bool started_ = false;
    uint32_t last_ = 0;
    uint32_t acc_ = 0;        // carried time, in 1/120000 s
};

// How much a body geoset takes the soft body's motion (a cape none; everything lying on the skin all of it).
float softGeosetGain(uint16_t geoset);

// An actor's soft body as the m2 program draws it (GL thread): the sim, the bones' classes as a texture (one texel per
// bone: chest, lower torso, thigh, upper arm), and the same for each skinned extra model by its bone map.
struct SoftDraw {
    SoftSim sim;
    bool dirty = true;                // the regions to find again (a new look or shape)
    std::vector<float> boneClass;
    size_t bones = 0;
    unsigned gateTex = 0;
    struct SkinnedGate { const void* mesh = nullptr; std::vector<int> map; unsigned tex = 0; };
    std::vector<SkinnedGate> skinned;

    void rebuild(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                 const std::vector<M2Submesh>& submeshes, const std::vector<uint16_t>& geosets, bool glAlive);
    void release(bool glAlive);       // the actor is gone
    void forget();                    // the GL context is gone
    bool active() const;
    unsigned bodyGate();
    unsigned skinnedGate(size_t index, const void* mesh, const std::vector<int>& boneMap);
    // Sets the program's soft body uniforms for a draw with `gate` (0, or the level Off: none).
    void uniforms(unsigned program, unsigned gate) const;
    static void off(unsigned program);
    static void setGain(unsigned program, float gain);
private:
    void dropSkinned(bool glAlive);
};

} // namespace wc
