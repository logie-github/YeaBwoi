// Soft tissue: a real-time soft-body simulation of the soft regions of a body (breasts, buttocks, belly, outer thighs), the pure part
// (no GL; engine-test/tissue_test.cpp). Replaces the analytic dent of contact.h on those regions (the shader falls back to it where no
// lattice covers the skin).
//
// Each region is a coarse volumetric lattice of nodes in the region's own frame (a rigid frame riding a bone: origin, u and v across the
// skin, w outward from the bone; w = 0 is the bone's side, w = depth the skin, one layer more outside it), solved by XPBD
// ("small steps": one constraint pass per substep, no iteration count to tune):
//   - per tetrahedron (five to a cell) the stable Neo-Hookean pair of Macklin and Mueller: a deviatoric constraint (shear and
//     stretch) and a hydrostatic one (volume): squeezing one part bulges its neighbours;
//   - nonlinear stiffness: both go stiffer with strain (soft at a small strain, hard near the limit);
//   - each free node is held to its anchor (where a rigid body would carry it) by a spring that is softer across the skin than
//     into the body (the skin slides over the fat; anisotropy), so the bone's motion moves the lattice by inertia and it jiggles;
//   - velocity damping relative to the anchor's own motion (so a walk does not drag the tissue), gravity only as the change from
//     the rest pose's (the rest mesh already stands under it);
//   - handles: fingers, palms, other bodies, as kernel-weighted constraints on the nodes round a material point: a poke pushes the
//     nodes in, a grip sticks to the point and drags it (friction: it slides once the finger is further than the grip's reach),
//     a pinch is two grips pulled toward each other.
// A region's field (node displacement from its anchor, and the volume change) is what the vertex shader interpolates.
// Deterministic: fixed 1/60 s steps with an accumulator, no allocation after build.
#pragma once

#include <cstdint>
#include <vector>

#include "wmath.h"

namespace wc {

struct TissueMaterial {
    float youngs = 1100;       // Pa (fat: a few kPa)
    float poisson = 0.485f;    // nearly incompressible
    float density = 1000;      // kg/m^3
    float freqTan = 2.6f;      // Hz of the anchoring along the skin (softest)
    float freqNorm = 4.5f;     // Hz of the anchoring across it (the skin's ties to the body: stiffer)
    float zeta = 0.14f;        // damping ratio of the jiggle
    float internal = 9.0f;    // 1/s: damping of the lattice's own deformation (a dent settles with little rebound)
    float stiffen = 8.0f;      // how much the elasticity grows with strain squared
    float sag = 1.0f;          // gravity's change from the rest pose, as a share
};

struct TissueSpec {
    float halfU = 0.12f, halfV = 0.12f;   // metres: the footprint (a half width and height)
    float depth = 0.08f;                  // metres: from the bone side (w 0) to the skin (w = depth); the lattice runs to 1.5 x depth
    float spacing = 0.035f;               // metres between nodes across the skin
    TissueMaterial mat;
};

// One finger, palm or other body on the tissue (world metres).
struct TissueHandle {
    uint32_t key = 0;          // the same touch from frame to frame (its grip goes on)
    V3 rest;                   // the point on the skin it touches, on the undeformed skin (world)
    V3 normal{0, 0, 1};        // outward
    float radius = 0.02f;      // the pad's reach
    float push = 0;            // metres pressed in along -normal
    V3 pull;                   // extra drag of a gripped point (world metres): a pinch's closing
    float grip = 0;            // 0: only pushes; > 0: sticks to the skin (the reach, in 0..1 x 4 cm, before it slides)
};

constexpr int kTissueHandles = 8;
constexpr int kTissueLayers = 4;       // across the depth: 0 (bone), 0.5, 1 (skin), 1.5

class TissueRegion {
public:
    void build(const TissueSpec& s);
    bool built() const { return nu_ > 0; }
    // The rigid frame now (orthonormal axes u, v, w, origin: the lattice's (0, 0, 0) is its centre on the bone side). `snap`: the nodes
    // jump to it (a teleport, the first frame) instead of lagging.
    void setFrame(const V3& origin, const V3& u, const V3& v, const V3& w, bool snap = false);
    // Gravity's direction in the rest pose, in the frame's own axes (unit; the sag is gravity's change from it).
    void setRestDown(const V3& d) { restDown_ = d; }
    void setJiggle(float amount);                  // 0 .. 2 (1: the material's): scales the anchoring's stiffness by 1 / amount
    void setSubsteps(int n) { substeps_ = n < 1 ? 1 : n > 10 ? 10 : n; }

    void beginHandles() { nh_ = 0; }
    void addHandle(const TissueHandle& h) { if (nh_ < kTissueHandles) hin_[nh_++] = h; }
    // Advances by dt seconds (clamped to 0.1) in fixed 1/60 s steps; the frame blends from the last call's to the one set now.
    void step(float dt);
    void reset();                                  // back to the rest pose, still

    // ---- the lattice
    int nu() const { return nu_; }
    int nv() const { return nv_; }
    int nw() const { return kTissueLayers; }
    float du() const { return du_; }
    float dv() const { return dv_; }
    float dw() const { return dw_; }
    float halfU() const { return spec_.halfU; }
    float halfV() const { return spec_.halfV; }
    int count() const { return int(x_.size()); }
    int freeCount() const { return nfree_; }
    int tetCount() const { return int(tet_.size()); }
    int index(int i, int j, int k) const { return (k * nv_ + j) * nu_ + i; }
    const V3& node(int n) const { return x_[size_t(n)]; }
    V3 anchor(int n) const;                        // where a rigid body would have it now
    V3 disp(int n) const { return x_[size_t(n)] - anchor(n); }
    V3 local(int n) const { return X_[size_t(n)]; }
    bool isFree(int n) const { return w_[size_t(n)] > 0; }
    // Writes the field for the shader: nu x nv x nw texels of 4 floats (displacement from the anchor, world metres; the volume change
    // of the tissue round the node, -0.5 .. 0.5), i fastest, then j, then k.
    void exportField(float* rgba) const;
    // The volume of the tetrahedra now over their rest volume (the tests: 1 when nothing is squeezed).
    float volumeRatio() const;
    float cellVolumeRatio(int i, int j, int k) const;   // the cell's five tetrahedra
    float maxDisplacement() const;
    float kineticEnergy() const;
    bool asleep() const { return asleep_; }
    // The material point a gripping handle holds (local), for the tests; false when it holds none.
    bool gripOf(uint32_t key, V3* localPoint) const;
    int substepsRun() const { return ran_; }

private:
    struct Tet { int n[4]; float V0; float B[9]; float mu, lambda; };   // B: the rest shape matrix's inverse, column-major
    struct Grip { bool on = false; uint32_t key = 0; V3 m; uint32_t seen = 0; };
    void anchorsAt(float t, V3* out) const;
    void fixedStep(float h);
    void gather();

    TissueSpec spec_;
    int nu_ = 0, nv_ = 0, nfree_ = 0;
    float du_ = 0, dv_ = 0, dw_ = 0;
    std::vector<V3> X_;              // rest, local (metres)
    std::vector<V3> x_, v_, prev_, A0_, A1_, Acur_;
    std::vector<float> w_;           // inverse mass (0: pinned)
    std::vector<float> alphaT_, alphaN_;   // anchoring compliances per node
    std::vector<float> damp_;
    std::vector<Tet> tet_;
    // frame
    V3 o0_, u0_{1, 0, 0}, v0_{0, 1, 0}, w0_{0, 0, 1};   // at the last call
    V3 o1_, u1_{1, 0, 0}, v1_{0, 1, 0}, w1_{0, 0, 1};   // now
    V3 restDown_{0, 0, -1};
    float jiggle_ = 1;
    int substeps_ = 5;
    float acc_ = 0;
    bool haveFrame_ = false;
    TissueHandle hin_[kTissueHandles];
    int nh_ = 0;
    // per step working state
    struct HandleNodes { int n[48]; float wgt[48]; int count = 0; V3 target; V3 axisN; bool vec = false; float push = 0; bool live = false; };
    HandleNodes hn_[kTissueHandles];
    Grip grips_[kTissueHandles];
    uint32_t tick_ = 0;
    bool asleep_ = true;
    int quiet_ = 0, ran_ = 0;
    V3 oPrev_, oPrev2_;
};

// float to IEEE half bits (round to nearest), for the shader's RGBA16F field.
uint16_t tissueHalf(float f);

} // namespace wc
