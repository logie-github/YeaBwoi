// A fine skin patch: a local simulation of the skin and the fat under it round a finger, a grip or a pinch, at 3 to 5 mm (the lattice
// of tissue.h is 3.6 cm: a finger's pad is 1.1 cm). The pure part (no GL; engine-test/skinpatch_test.cpp); skinpatchdraw.cpp feeds it and draws it.
//
// A regular grid of kPatchN x kPatchN nodes in the tangent plane at the contact (centre, two tangents, the outward normal). A node carries a
// displacement from its rest place: along the skin (a, b: the skin slides over the fat) and out of it (c). Metres. The solver is XPBD
// ("small steps": fixed 1/360 s substeps, one pass each):
//   - skin tension: every edge and diagonal resists stretching (stiffer as it stretches: skin's J curve) and, softly, being squeezed (it
//     gathers: past half its length a barrier stops it); the 3D length counts, so a skin drawn over a fold is drawn in;
//   - the slab is incompressible: where the in-plane flow gathers the skin (area ratio J < 1) the surface rises by T (1 / J - 1), T the
//     thickness of skin and fat, up to a cap (the tissue's limit); where it is drawn thin (J > 1) it sinks a little. A fold's volume is the
//     flesh gathered into it, so its height follows the gap, and a dent's volume comes back as a bulge round it;
//   - bending (the crest and the foot are rounded, a crease stays small), a soft tangential tie to the body and the floor the bone makes;
//   - pads: a finger's kernel on the nodes: it presses (c), and a gripping pad drags the skin along (it slips once the finger is more than
//     the grip's reach from the point it holds).
// The outer ring of nodes is held at rest, so the patch blends into the lattice and the body. Deterministic: fixed steps, no allocation after build.
#pragma once

#include <cstdint>
#include <vector>

namespace wc {

constexpr int kPatchN = 32;
constexpr int kPatchPads = 12;

struct PatchSpec {
    float spacing = 0.0033f;      // metres between nodes (the patch covers 31 x spacing)
    float thickness = 0.02f;      // T: skin and fat there (metres)
    float riseCap = 0.7f;         // the fold's highest rise, in T
    float softness = 1.0f;        // 0.5 .. 1.4: scales how deep a pad presses and how easily the skin gathers
    int substeps = 6;
};

// A finger, palm or other body on the patch (metres, in the patch's plane: x along the first tangent, y the second).
struct PatchPad {
    uint32_t key = 0;             // the same finger from frame to frame (its grip goes on)
    float x = 0, y = 0;           // where the finger is on the undeformed skin
    float radius = 0.011f;        // the pad's
    float push = 0;               // pressed in (metres)
    float grip = 0;               // 0: it only presses; 1: it sticks to the skin and drags it
    float reach = 0.04f;          // the grip slips once the finger is further than this from the point it holds
    float dirx = 1, diry = 0;     // the pad's long axis (unit) and how much longer it is than wide (a fingertip pressed across a fold)
    float aspect = 1;
    uint32_t mate = 0;            // a pinch's other fingertip (its key): the skin between the two is drawn together as one fold
};

class SkinPatch {
public:
    void build(const PatchSpec& s);
    bool built() const { return built_; }
    void reset();
    void beginPads() { np_ = 0; }
    void addPad(const PatchPad& p) { if (np_ < kPatchPads) pad_[np_++] = p; }
    void step(float dt);                         // advances in fixed 1/60 s steps (the dt is clamped to 0.1)

    int n() const { return kPatchN; }
    float spacing() const { return spec_.spacing; }
    float half() const { return 0.5f * spec_.spacing * float(kPatchN - 1); }
    const PatchSpec& spec() const { return spec_; }
    int idx(int i, int j) const { return j * kPatchN + i; }
    // rest coordinates of node (i, j), the patch's centre at (0, 0)
    float restX(int i) const { return (float(i) - 0.5f * float(kPatchN - 1)) * spec_.spacing; }
    float a(int i, int j) const { return a_[size_t(idx(i, j))]; }
    float b(int i, int j) const { return b_[size_t(idx(i, j))]; }
    float c(int i, int j) const { return c_[size_t(idx(i, j))]; }
    float maxRise() const;                       // the highest c (metres)
    float maxSink() const;                       // the deepest c (negative: metres)
    float maxSlide() const;                      // the largest |(a, b)|
    float volume() const;                        // sum of c x node area (m^3)
    float areaRatio(int i, int j) const;         // J at the node
    bool asleep() const { return asleep_; }
    bool finite() const;
    int padCount() const { return np_; }
    // For the shader: nodes' (a, b, c) in centimetres and the pad's pressure (0..1) at the node, kPatchN x kPatchN texels of 4, i fastest.
    void exportField(float* rgba) const;

private:
    void substep(float h);
    float alpha(float hz, float h) const;
    PatchSpec spec_;
    bool built_ = false;
    std::vector<float> a_, b_, c_, va_, vb_, vc_, pa_, pb_, pc_, pw_, pk_, ring_, w_;
    PatchPad pad_[kPatchPads];
    int np_ = 0;
    struct Hold { bool on = false; uint32_t key = 0; float mx = 0, my = 0; uint32_t seen = 0; };
    Hold hold_[kPatchPads];
    uint32_t tick_ = 0;
    float acc_ = 0;
    bool asleep_ = true;
    int quiet_ = 0;
    std::vector<float> dxs_, dys_;   // per pad this step: the drag asked (the grip's)
};

} // namespace wc
