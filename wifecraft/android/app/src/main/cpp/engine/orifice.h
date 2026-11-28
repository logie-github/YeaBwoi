// The orifice deformer (README "Toy interaction"): the maths that opens a body's entry around a toy, with no GL and no game
// file, so engine-test/orifice_test checks it and another orifice (the mouth) can reuse it with its own Frame.
//
// Three pieces:
//   * ToyShape: a prop's mesh measured along its own axis (its principal axis, the narrower end the tip): the insertable
//     length and the cross-section radius at kProfile steps from the tip (measureToy).
//   * Dyn: the opening's dynamics (stepDyn): the eased radius at the entry plane (it opens quickly and closes over about 300 ms:
//     the hysteresis), the drag of the lips outward on a withdrawal, the wetness 0..1, the vibration's tremor.
//   * deformPoint: one rest vertex moved. In the entry's frame (centre C, inward axis a, lateral l, forward f) the slit's two
//     walls move apart by the shaft's radius with a lens-shaped profile along f (so the opening is an oval, closed at its ends),
//     the lips beyond them follow with a falloff and bulge outward (the volume the opening displaced), the ends of the slit are
//     stretched, the rim is dragged along the shaft when it is withdrawn, and any vertex that would end up inside the shaft is
//     pushed out to its surface plus a skin thickness (the rim hugs the shaft). Everything is a pure function of the rest
//     position, so it is the same on the CPU that uploads the HD body's vertices (orificedraw.cpp) as it would be in a shader.
//   * buildSleeve / buildRim: the interior (the closed body has no inside: a tapering elliptical tube from the entry plane, about
//     6 cm deep, scaled by the opening, closed at its end) and the thin annulus laid on the rim.
//
// Units: model units (the body's bind space); the Sims metre of the props is `Frame::u` of them (the pelvis pivot's height over
// 0.93, as the male parts are sized: bodypart.h). Constants below are in Sims metres and multiplied by u.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {
namespace orifice {

constexpr int kProfile = 16;

// What the deformer takes of a toy: its axis in the prop's own space, the insertable length and the radius profile.
struct ToyShape {
    bool ok = false;
    V3 tip;                       // the tip, in the prop's space (Warcraft axes, metres)
    V3 axis;                      // unit, base to tip, in the prop's space
    float length = 0;             // the insertable length, tip to the base's flare (metres)
    float totalLength = 0;        // the whole prop along its axis
    float radius[kProfile] = {};  // the cross-section's radius at s = (i + 0.5) / kProfile * length from the tip (metres)
    float maxRadius = 0;
    bool doubleEnded = false;
    // The radius at `s` metres from the tip (linear between the samples; the first at the tip's round end; the last held beyond).
    float radiusAt(float s) const;
};

// A prop's vertices (Warcraft axes, metres) measured: the principal axis (power iteration on the covariance), the end with the
// smaller cross-section the tip, the length to the flare (the first sample whose radius is above 1.45 times the shaft's median), or
// half the prop when it is `doubleEnded` (either end goes in; the middle is the stop).
ToyShape measureToy(const float* positions, size_t vertexCount, bool doubleEnded);

// ---------------------------------------------------------------------------------------------------------- the entry's frame
struct Frame {
    bool ok = false;
    V3 centre;                    // the opening's centre on the entry plane (model units)
    V3 axis;                      // the way in (unit)
    V3 lateral;                   // across the slit (unit; the body's left)
    V3 fwd;                       // along the slit toward the front (unit)
    float u = 1;                  // model units per Sims metre
    float slitBack = 0.01f, slitFront = 0.01f;   // the rest slit's reach behind and in front of the centre (model units)
    float cleftDepth = 0.01f;     // how far the slit's walls run along the axis from the entry plane (model units)
};

// The vulva of an HD body as fitted: `verts` (the body's, model units, bind space), the vertices carried (nearly) all by `pelvis`
// whose normals and positions tell the slit: its two walls (|y| under 0.7 mm of the midline, facing sideways) cluster along x;
// the longest cluster (the anus is a short one) is the slit. `u` the model units per Sims metre, `midY` the body's midline.
// False when the body has no such modelled slit (the game's own body: use measureEntries and frameFromAnchor).
bool findHdVulva(const M2Vertex* verts, size_t bodyEnd, int pelvis, float u, float midY, Frame& out);

// A frame from a measured anchor (the game's body: bodypart.h EntryAnchor: point, the way in, the opening's radius).
Frame frameFromAnchor(const V3& point, const V3& inward, float radius, float u);

// ------------------------------------------------------------------------------------------------------------------ dynamics
struct Dyn {
    float depth = 0;       // how far the tip is past the entry plane (model units; negative: still outside)
    float speed = 0;       // model units a second, + inward (smoothed)
    float radius = 0;      // the opening's eased radius at the entry plane (model units)
    float drag = 0;        // how far the rim is dragged outward along the shaft (model units, >= 0)
    float wet = 0;         // 0..1
    float tremor = 0;      // 0..1: the vibration's strength reaching the skin
    float time = 0;        // seconds, for the tremor's phase
    bool primed = false;
};

constexpr float kOpenTau = 0.07f;      // the opening's rise, seconds
constexpr float kCloseTau = 0.30f;     // and its fall: the lips relax in about 300 ms
constexpr float kDragTau = 0.30f;
constexpr float kSkinThickness = 0.0016f;   // Sims metres: the rim sits this far off the shaft
constexpr float kRegionRadius = 0.036f;     // Sims metres: nothing further from the centre moves
constexpr float kSleeveDepth = 0.065f;      // Sims metres

// One step of `dt` seconds: the tip at `depth` (model units past the plane), the toy `present` (a toy at the entry), `vibration` 0..1.
void stepDyn(Dyn& d, const Frame& f, const ToyShape* toy, bool present, float depth, float vibration, float dt);
// The radius the opening is heading for at `depth`: the toy's cross-section at the entry plane, eased in over the first 8 mm.
float entryRadius(const Frame& f, const ToyShape& toy, float depth);

// ------------------------------------------------------------------------------------------------------------------- deformer
// Where the rest vertex `p` goes (and, in `*stretch` (may be null), how stretched its skin is, 0..1).
V3 deformPoint(const Frame& f, const ToyShape* toy, const Dyn& d, const V3& p, float* stretch);
// The weight 0..1 of the whole deformation at `p` (0: the vertex never moves: outside the region).
float regionWeight(const Frame& f, const V3& p);
// The oval at the entry plane: half width (across) and half lengths (behind, in front) for an opening of `radius`.
struct Oval { float half = 0, back = 0, front = 0; };
Oval ovalFor(const Frame& f, float radius);

// ---------------------------------------------------------------------------------------------------------------- the interior
struct Sleeve {
    std::vector<float> v;          // per vertex: position (3), normal (3, toward the axis), depth 0 (the mouth) .. 1 (the end)
    std::vector<uint32_t> idx;
    int rings = 0, segs = 0;
    bool empty() const { return idx.empty(); }
};
void buildSleeve(const Frame& f, const ToyShape* toy, const Dyn& d, Sleeve& out, int rings = 14, int segs = 24);
// The annulus on the rim: position (3), alpha (1), depth along the ring's width 0 (inner) .. 1 (outer).
struct Rim {
    std::vector<float> v;
    std::vector<uint32_t> idx;
    bool empty() const { return idx.empty(); }
};
void buildRim(const Frame& f, const Dyn& d, Rim& out, int segs = 36);
// The game body's stand-in for an opening (its mesh is a smooth mons with a painted cleft: nothing to open): the oval filled, a dark pink
// gradient to its edge, laid on the skin (the first vertex its centre).
void buildDisc(const Frame& f, const Dyn& d, Rim& out, int segs = 32);

// ---------------------------------------------------------------------------------------------------------------- the toy's place
// The toy's tip and unit axis (base to tip) in the entry's frame at `depth` (model units past the plane): on the axis.
inline V3 tipAt(const Frame& f, float depth) { return f.centre + f.axis * depth; }

} // namespace orifice
} // namespace wc
