// Hanging cloth: a character's tabard flap (geoset group 12: 1202 front and back, 1203 back, 1204 front) simulated as
// cloth instead of skinned to the game's flap bones, so it hangs, swings and drapes over the body as fabric does and
// never cuts through the belly, the hips or the thighs.
//
// The flap's mesh is resampled to a grid per piece (the front flap and the back flap, told apart by which side of the
// pelvis they hang; 7 columns, rows by its height: 182 points on the human woman), each grid point a particle lying on the
// model's own triangles (its corners' skinning and texture coordinates, weighted). The top two rows are pinned: they go
// where the skinned flap's waist goes (the pelvis, the hips and the spine carry it, as the game weights it), set on the
// hips' surface (seated, on the lap's). The rest is position based dynamics at a fixed 120 Hz in the world: gravity, air damping, a weak pull toward
// the game's own posed flap (stronger when the cloth is far from it: a pose change it can't follow alone), distance links
// along the grid (stretch), across its cells (shear) and two cells apart (bend), a tether from each point to its column's
// pin, then follow-the-leader (no length past its rest by more than kSlack), and collision with the body: per bone
// segment (pelvis, spine, chest, thighs, calves, feet, forearms, hands) the flesh around flesh.h's frame of the segment,
// measured on the dressed body's own vertices (how far it reaches in sixteen directions around the axis at four sections
// along it), held a standoff outside it; the torso, and a thigh while it hangs, keep a front piece in front and a back
// piece behind; a limb lying across (a raised thigh, a shin or foot near the level) has the cloth laid over its top, a
// forearm or hand resting on the lap presses it down; each point is held off the tangent plane of its nearest skin vertex;
// each row is turned back to within kTwistCone of the hips' side (never edge-on); last, no link along the grid is left
// longer than kMaxStrain past its rest (the free cloth gives way, not the points the body holds). The pull toward the
// game's flap fades where that flap lies inside the body (seated, it runs through the thighs). Touching points don't
// bounce and lose some of their sliding; nothing moves faster than kMaxSpeed; sideways speed is damped harder; the floor
// (or, seated, the seat for the back flap) holds it up. The cloth's lengths are fitted once to the body as made.
//
// ClothRig and ClothSim are pure maths (no GL; engine-test/cloth_test.cpp, cloth_check.cpp); Scene steps and draws it
// (scene.cpp: refreshCloth, stepCloth, drawCloth).
#pragma once

#include <cstdint>
#include <vector>

#include "flesh.h"
#include "m2.h"
#include "wmath.h"

namespace wc {

constexpr int kClothGroup = 12;   // the tabard flap's geoset group
constexpr int kClothPinnedRows = 2;   // each piece's top rows that go where the skinned flap's waist goes (on the hips)

constexpr int kClothSections = 4;   // a collision segment's cross sections along it
constexpr int kClothBins = 16;      // and the directions around it each is measured in

// The body's flesh around a bone segment, in the world: the axis from a0 to a1 (length len, unit axis), the cross
// section's front n and side m (unit, square to the axis); r[s][b] how far the flesh reaches from the axis at section s
// (centred at (s + 0.5) / kClothSections along it, blended between, held past the ends) in direction b (the angle
// atan2(along m, along n) in kClothBins steps, blended between); it reaches start past a0 and end past a1 along the axis.
// sided (the torso and the thighs): a front piece is held in front of it and a back piece behind it whatever side it
// is pushed to; elsewhere (the calves) it is pushed straight out from the axis.
struct ClothCapsule {
    V3 a0, a1, axis, n, m;
    float len = 0;
    float r[kClothSections][kClothBins] = {};
    float reach = 0;               // the largest of r (a quick test)
    float start = 0, end = 0;
    int seg = -1;                  // its FleshSegment
    bool torso = false;
    bool sided = false;            // the torso and the thighs: a piece is kept on its own side of them
    // A limb lying across (a raised thigh seated or kneeling: the front piece; a shin or a foot near the level: both): the
    // cloth is laid over its top (`up`, the world's up square to its axis), never pushed out at its side or under it, so
    // the flap drapes over the lap and the shins and falls between the legs. Bit 0 the front piece, bit 1 the back.
    uint8_t drape = 0;
    V3 up;
};

// A segment's measured flesh (model units, the dressed body in its bind pose, around flesh.h's frame of the segment):
// each section's and direction's farthest vertex of the segment (its vertices: those whose heaviest bone belongs to it),
// what clothCapsules turns into capsules.
struct ClothBodySeg {
    int seg = -1;                  // FleshSegment
    float r[kClothSections][kClothBins] = {};
    float start = 0, end = 0;
    bool ok = false;
};

// One grid point of a piece: on the model's triangle v[0..2] at weights w (its rest place, its skinning and its texture
// coordinates uv, in the material's fractions, v 0 at the top), pinned or free.
struct ClothPoint {
    uint32_t v[3] = {0, 0, 0};
    float w[3] = {1, 0, 0};
    float uv[2] = {0, 0};
    bool pinned = false;
};

// kind: 0 along the grid (stretch), 1 across a cell (shear), 2 two cells apart (bend); a tether is its own list.
struct ClothLink {
    uint16_t a = 0, b = 0;
    float rest = 0;
    float stiffness = 1;
    uint8_t kind = 0;
};

struct ClothPiece {
    int side = 1;                  // +1 the front flap, -1 the back flap
    float normalSign = 1;          // the grid's (right x down) normal times this faces `side` at rest
    int cols = 0, rows = 0;        // the grid: point (r, c) is points[r * cols + c], row 0 the pinned top
    uint32_t first = 0;            // its first point in the rig's whole list (ClothSim positions)
    std::vector<ClothPoint> points;
    std::vector<ClothLink> links;
    std::vector<ClothLink> tethers;   // point b may hang at most `rest` from pin a
    std::vector<uint16_t> tris;    // the grid's triangles (its own point numbers), wound to face `side` at rest
    // each point's rest distance to the point above it and to the point on its right (0: none), for the sim's last pass
    std::vector<float> restUp, restRight;
    // each row's width at rest (its first point to its last, straight), and which way along the hips' side (the pelvis
    // capsule's m) the row runs from its first point: the twist limit and the width check
    std::vector<float> restWidth;
    float sideSign = 1;
};

struct ClothRig {
    bool ok = false;
    std::vector<ClothPiece> pieces;
    std::vector<uint16_t> geosets;     // the shown geosets it stands for (drawn as cloth, not skinned)
    std::vector<ClothBodySeg> body;    // the collision segments
    int jointBone[kFleshJointCount];   // kFleshJoints' bones on the model (-1: none)
    std::vector<uint32_t> surface;     // the body's skin the cloth is finally kept off: the collision segments' vertices
    // the flap's texture rectangle: its triangles' texture coordinates' box, in the material's fractions (v 0 at the top)
    float uv0[2] = {0, 0}, uv1[2] = {0, 0};
    size_t particles() const { size_t n = 0; for (const ClothPiece& p : pieces) n += p.points.size(); return n; }
};

// The rig of a dressed model: the shown geosets of group 12 among `geosets` (empty: none is shown, no rig), its triangles
// from `indices` and `submeshes`, `verts` its vertices (a shaped body's own, or the model's). `cols` across each piece; its
// rows follow from the piece's height. The game's flap lies partly inside the hips and thighs it hangs over (on the human
// woman, up to 11 mm standing): the cloth is fitted to the body as made, pushed out of it in the bind pose without gravity,
// and each of its lengths is the longer of the flap's own and the fitted one (cut to go round the body, not through it).
// ok false: no flap shown, or no pelvis to tell front from back.
ClothRig clothRig(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                  const std::vector<M2Submesh>& submeshes, const std::vector<uint16_t>& geosets, int cols = 7);

// A piece's links, tethers, triangles and rest distances from its grid's rest places (rest[r * cols + c]; its points,
// side, cols and rows already set, row 0 pinned): what clothRig makes of each piece, and what the tests make of a grid.
void clothLinks(ClothPiece& piece, const std::vector<V3>& rest);

// The texture rectangle of the shown flap (clothRig's uv0 and uv1) alone, from the model's own vertices: false when none
// is shown. In the material's pixels (mw x mh), widened by `margin` pixels and clamped to it: x, y, w, h.
bool clothUvRect(const M2Model& m, const std::vector<uint16_t>& geosets, int mw, int mh, int margin, int out[4]);

// Every point's posed place this frame: the skinned place of its triangle's corners (`bones`: model space), weighted,
// through `world`.
void clothPosed(const ClothRig& rig, const std::vector<M2Vertex>& verts, const std::vector<M4>& bones, const M4& world,
                std::vector<V3>& out);

// The rig's collision segments as capsules this frame (the joints from `bones`, through `world`); none for a segment
// the model hasn't the joints of.
void clothCapsules(const M2Model& m, const ClothRig& rig, const std::vector<M4>& bones, const M4& world,
                   std::vector<ClothCapsule>& out);

// The rig's surface vertices skinned this frame (`bones`: model space, through `world`), their places and normals.
struct ClothSurface {
    std::vector<V3> p, n;
};
void clothSurface(const ClothRig& rig, const std::vector<M2Vertex>& verts, const std::vector<M4>& bones, const M4& world,
                  ClothSurface& out);

// Pushes p out of capsule c (piece side `side`: +1 front, -1 back, 0 neither) to `standoff` outside its surface. Returns
// whether it moved; `normal` the way it was pushed.
bool clothCollide(const ClothCapsule& c, int side, float standoff, V3& p, V3& normal);

// How far p is outside capsule c's surface, out from its axis (negative: inside), and whether it lies within the
// capsule's reach along its axis.
float clothCapsuleDepth(const ClothCapsule& c, const V3& p, bool& within);

class ClothSim {
public:
    static constexpr int kHz = 120;                 // the fixed step
    static constexpr int kIterations = 6;           // constraint passes a step
    static constexpr int kMaxSteps = 8;             // at most this much simulated time per frame (66 ms)
    static constexpr uint32_t kMaxFrameMs = 250;    // a longer gap: the cloth starts again from the pose
    static constexpr int kSettleSteps = 60;         // a fresh start hangs this long against the pose before it is drawn
    static constexpr float kGravity = 9.81f;        // world units (about a metre) per second squared
    static constexpr float kDamping = 0.10f;        // the share of its speed a point loses a step (the air: fabric settles fast)
    static constexpr float kFollow = 0.02f;         // the share of the way to the game's posed flap a point goes a step
    static constexpr float kFollowFar = 0.15f;      // and when the cloth is far from it (its mean distance kFarFrom..kFarTo)
    static constexpr float kFarFrom = 0.06f, kFarTo = 0.15f;
    static constexpr float kFollowDeep = 0.04f;     // a point of the posed flap this deep in the body draws nothing (model units)
    static constexpr float kFriction = 0.45f;       // the share of its sliding a touching point loses a step
    static constexpr float kStandoff = 0.015f;      // how far outside the capsules it is held (model units)
    static constexpr float kSkinGap = 0.006f;       // and outside the skin itself, at its nearest vertex (model units)
    static constexpr float kSkinReach = 0.04f;      // the nearest skin vertex counts within this (model units)
    static constexpr float kSlack = 0.01f;          // the cloth's give: no length grows past its rest by more
    static constexpr float kMaxSpeed = 2.0f;        // world units a second, at most
    static constexpr float kTeleport = 0.5f;        // a pin moving farther in one frame was put there, not moved
    static constexpr float kMaxStrain = 0.06f;      // no link along the grid longer than its rest by more, after the body
    static constexpr float kTwistCone = 1.0472f;    // a row turns at most this far (60 degrees) from the hips' side, about
                                                    // the pelvis's axis: the flap never swings round edge-on
    static constexpr float kSideDamping = 0.15f;    // the share of its speed along the hips' side a free point loses a
                                                    // step beyond kDamping (a stride doesn't flick it out sideways)

    void setRig(const ClothRig* rig) { rig_ = rig; started_ = false; }
    void setGravity(bool on) { gravity_ = on; follow_ = on ? kFollow : 0.0f; }   // (clothRig fits the cloth without either)
    // How much it is drawn toward the posed flap (kFollow, and harder when far from it); off: free cloth (the tests).
    void setFollow(bool on) { followOn_ = on; follow_ = on && gravity_ ? kFollow : 0.0f; }
    // The floor under the actor (world z; -inf: none): the cloth lies on it rather than through it. `back` the back piece's
    // (a seat's top: the back flap is sat on and lies over the seat, not through it), the front's `z`.
    void setFloor(float z, float back) { floor_ = z; floorBack_ = back; }
    void setFloor(float z) { setFloor(z, z); }
    void reset() { started_ = false; }
    // Advances to `nowMs`: `posed` every point's posed place this frame (clothPosed), `bodies` the capsules (world),
    // `skin` the body's skin near the flap this frame (clothSurface; null: the capsules alone): after each step every free
    // point is held kSkinGap off the tangent plane of its nearest skin vertex. `scale` the actor's (the standoff and
    // teleport distance scale with it). Time going backwards, a long gap or a pin put far away starts again from the pose
    // (settled kSettleSteps against it): the same frames give the same result.
    void step(const std::vector<V3>& posed, const std::vector<ClothCapsule>& bodies, const ClothSurface* skin, float scale,
              uint32_t nowMs);
    bool started() const { return started_; }

    const std::vector<V3>& positions() const { return x_; }   // world, the rig's points in order
    // The largest stretch of a structural link now (its length over its rest, less 1): the tests' and the harness's check.
    float maxStretch(float worldScale) const;
    // The narrowest row now (the free ones and the pinned second), as a share of its width at rest, measured along the
    // hips' side (right hip joint to left, the spine's axis taken out) in the last step's body: a flap turned edge-on or
    // bunched up reads low. 1 without both thighs.
    float minWidthRatio(float worldScale) const;
    int stepsTaken() const { return steps_; }                // fixed steps taken in the last call

private:
    void substep(const std::vector<V3>& pins, const std::vector<V3>& targets, const std::vector<ClothCapsule>& bodies, float scale,
                 float dt);
    void skinPass(float scale);
    void inextensible(float scale);
    void strainLimit(float scale);
    void twist(const std::vector<ClothCapsule>& bodies);
    void collide(const std::vector<ClothCapsule>& bodies, float scale);
    // where the game's posed flap is (what the cloth is drawn toward), this frame and the last, and how much each point is
    std::vector<V3> target_, lastTarget_, targetNow_;
    std::vector<float> followW_;
    // the skin this frame, filed by cell (its vertices sorted by their cell's key)
    const ClothSurface* skin_ = nullptr;
    float cell_ = 0.04f;
    std::vector<std::pair<uint64_t, uint32_t>> cells_;
    void solve(const std::vector<ClothCapsule>& bodies, float scale, bool withBodies);
    const ClothRig* rig_ = nullptr;
    std::vector<V3> x_, xp_;          // positions and last step's (world)
    std::vector<V3> lastPosed_;
    std::vector<ClothCapsule> lastBodies_;
    std::vector<V3> contact_;         // a touching point's push direction this step (0: none)
    std::vector<V3> pinsNow_;
    std::vector<ClothCapsule> bodiesNow_;
    bool started_ = false;
    bool gravity_ = true;
    float follow_ = kFollow;
    bool followOn_ = true;
    float floor_ = -1e30f, floorBack_ = -1e30f;
    uint32_t last_ = 0;
    uint32_t acc_ = 0;                // carried time, in 1/120000 s
    int steps_ = 0;
};

// The tabard's own panel, what a tabard-only look's flap shows (skin.h kClothLayer): the game draws the flap from a strip of
// the torso's texture by the model's own coordinates, mirrored across the flap and upside down (the human woman's 1202:
// its top on the bottom of the torso's lower section, its hem a quarter up the upper one; x 744..916, y 176..380 of 2048 x
// 1024), and with the tabard's front painted on the chest that strip is its side and its stains, stretched four times
// down: alone, under a bare chest, it reads as fur. The cloth is cut from the panel instead, as a tabard's flap is the
// rest of its front: the front piece from the torso's front middle (the sections' left edge: the body's texture is
// mirrored about it, and the tabard's emblem sits there) out kClothPanelHalf of the panel's width to each side, the back
// piece from its back middle (the right edge), each from kClothPanelTop down the panel to the torso's lower section's
// bottom, the right way up. The cloth layers' sections (x, y, w, h each, the material's pixels: the torso's upper and lower
// sections, 512, 0, 512, 256 and 512, 256, 512, 128 on every layout) give the panel; `out` the window of the material the
// cloth's texture is composed for (x, y, w, h). False: no section, or a panel of no size.
constexpr float kClothPanelHalf = 0.38f;
constexpr float kClothPanelTop = 0.39f;
bool clothPanelRect(const std::vector<int>& sections, int mw, int mh, int out[4]);
// A grid point's coordinates on that window's texture (texW x texH pixels): `side` +1 front, -1 back; `across` -1..1 from
// its piece's first column to its last; `down` 0 its top row .. 1 its hem. Held 1.5 pixels inside the window's edges (with
// the texture clamped there, nothing of the window's other side bleeds in at the flap's middle).
void clothPanelUv(int side, float across, float down, int texW, int texH, float uv[2]);

// The simulated points (world) as the model's vertices drawn without skinning (model space, through the inverse of
// `world`): for each piece, its grid twice, the front face and the back face (normals the other way, triangles wound the
// other way), normals from the grid's neighbours. `uvBox` (x, y, w, h in the material's fractions; null: as they are)
// maps the texture coordinates into that rectangle's own texture; `panelPx` (the cloth texture's width and height; null:
// not the panel) has them from clothPanelUv instead, on a texture composed for clothPanelRect's window. Indices into `out`,
// triangles.
void clothVertices(const ClothRig& rig, const std::vector<V3>& world, const M4& worldMatrix, const float* uvBox,
                   std::vector<M2Vertex>& out, std::vector<uint16_t>& indices, const int* panelPx = nullptr);

} // namespace wc
