// Flesh contact: the skin dents where a hand, a mouth or another body presses, bulges a little round the dent, springs back when
// the pressure goes, and shows the pressure (blanching in the middle, a flush ring that fades over a second or two).
//
// This is the pure part (no GL; engine-test/contact_test.cpp):
//   - the falloff maths the vertex shader mirrors (contactDisplace: the dent, the volume-keeping bulge ring, the squeeze toward the
//     contact), so the shader's numbers are tested here;
//   - ContactSolver: the contacts of one body, each a critically damped spring on its depth (a press settles in about 120 ms; a
//     release springs back in 200 to 400 ms with a slight rebound), softer regions dent deeper and slower, at most kContactMax
//     drawn at once (the rest fade out and in, never pop), a flush that outlives the touch;
//   - the derivation of contacts from proxies (deriveContacts: capsules of the other body's regions, the tools that press: hands,
//     fingers, mouth, pelvis, thighs): a stage needs no data of its own.
// Scene (contactdraw.cpp) feeds it from the playing stage and the Interact touch, and hands the shader the contacts as a uniform
// block (ContactDraw::uniforms). A dent is displacement, not a soft-body simulation: two bodies pressing hard into each other are
// approximated by each one's dent, not by a true two-way squash.
#pragma once

#include <cstdint>
#include <vector>

#include "wmath.h"

namespace wc {

constexpr int kContactMax = 8;       // what the vertex shader's block holds
constexpr int kContactSlots = 12;    // what a solver keeps (the ones beyond the drawn eight are fading out or in)
constexpr int kPinchMax = 2;         // pinches drawn at once (each costs two of the eight: its two fingertips)
constexpr int kTouchPointers = 5;    // fingers one person's skin takes at once (pointer ids 0..4)

// ---- The finger: the player's own fingertip, a fixed size in the world whatever the body it touches ----
// World units are yards at Character size 1 (1 yard = 0.9144 m); Character size c draws people (and so the real world they stand
// in) c times bigger, so a metre of finger is c / 0.9144 world units. The caller (Kotlin FingerSize) turns metres into world units;
// the engine only sees the pad radius in world units and works in multiples of it, so the mapping lives in one place.
constexpr float kYardM = 0.9144f;
constexpr float kFingerPadM = 0.011f;        // the fingertip pad's radius at the default Finger size (1.1 cm: a tip about 2.2 cm across)
constexpr float kFingerFalloffMul = 1.5f;    // the dent's radius (falloff to nothing) over the pad radius; the bulge ring then reaches 1.6 times that
constexpr float kFingerDepthMul = 0.65f;     // the deepest dent at full pressure on soft flesh: pad radius times this (7 mm at 1x, before the region's softness)
constexpr float kFingerCapMul = 1.4f;        // never deeper than this many pad radii (1.5 cm at 1x), whatever the softness slider says
constexpr float kPadDefaultWorld = kFingerPadM / kYardM;   // the pad radius in world units at Character size 1, Finger size 100%
// The dent's radius, depth and its cap for a pad of `pad` world units and a press of 0..1.
float fingerDentRadius(float pad);
float fingerDentDepth(float pad, float pressure);
float fingerDentCap(float pad);

// Options' Flesh contact.
enum ContactLevel : int { kContactOff = 0, kContactLow = 1, kContactHigh = 2 };
// How many contacts a level draws at most; `load` (0 none .. 2 heavy: Scene's governor) lowers it. 0: none.
int contactCap(int level, int load);

enum ContactRegion : int {
    kCrBreast = 0, kCrButt, kCrThigh, kCrBelly, kCrHip, kCrRibs, kCrShin, kCrSkull, kCrGroin, kCrArm, kCrOther, kCrCount
};
struct ContactRegionInfo {
    float softness;     // 0 stiff .. 1 soft: scales the dent's depth and slows its spring
    float maxDepth;     // metres: the deepest dent (at softness 1 of the option)
};
ContactRegionInfo contactRegion(int region);

// Falloff of the dent at q = distance / radius: (1-q^2)^2 inside, 0 from 1 on.
float contactFalloff(float q);
// The bulge ring's profile: 0 inside 0.7, up to 1 at 1.0, back to 0 by 1.6.
float contactRing(float q);
// The ring's amplitude (as a share of the depth) that keeps `volumeShare` (0.3: 30%) of the dent's volume: dent volume pi R^2 d / 3,
// ring volume 2 pi R^2 b * (integral of ring(q) q dq), computed once.
float contactBulgeGain(float volumeShare);

struct ContactIn {
    uint32_t key = 0;          // the same contact from one frame to the next (the tool's id): its spring goes on
    V3 pos;                    // the surface point (world)
    V3 normal{0, 0, 1};        // outward from the pressed body (toward the presser)
    float radius = 0.05f;      // of the dent (metres)
    float depth = 0;           // pressure, in metres of penetration asked for (the region's softness and the option shape it)
    int region = kCrOther;
    bool touch = false;        // a player's finger: drawn before the stage's contacts, merges only when nearly on another finger
    float cap = 0;             // > 0: the dent never goes deeper than this (the finger's), whatever the region says
};

// One contact as the shader takes it (world space).
struct ContactOut {
    uint32_t key = 0;          // the input's (Slot's) key: the same contact from frame to frame
    V3 pos, normal;
    float radius = 0.05f;
    float depth = 0;           // the dent now (metres, spring-smoothed; negative never)
    float bulge = 0;           // the ring's amplitude (metres)
    float squeeze = 0;         // the tangential pull toward the contact, a share of the distance (0..0.3)
    float flush = 0;           // 0..1: the pressure's colour, the ring (outlives the touch)
    float blanch = 0;          // 0..1: the pressure's pale middle (follows the dent)
    float softness = 0;
    bool touch = false;
};

// The displacement of a point `p` (world) with the skin normal `n` there from one contact: what the vertex shader adds. `shade`
// (optional): the blanching and the flush at that point (what the shader passes the fragment stage).
V3 contactDisplace(const ContactOut& c, const V3& p, const V3& n, float* blanch = nullptr, float* flush = nullptr);

// ---- The pinch: two fingertips on one skin close in on each other and lift a fold between them ----
// A new contact kind: two anchors (the fingertips), a fold height and a normal (the skin's, shared). What it draws is skin gathered between
// two pads: a FOLD, a wall whose crest runs across the line joining the fingers (along the pad width), not a round bump. In the frame
// of the pinch (s along the line joining the pads, t across it, from the middle of the two):
//     h(s,t) = H * (1 - |2s / L|^p)^q * (1 - (t / w)^2)^2        (p 2.0: a rounded, flattish crest; q 1: straight, steep flanks)
// L the fingers' distance on the skin, w the fold's half-width (1.6 pad radii: a fingertip's width), H the height, which saturates at the
// thickness of skin and fat there (soft regions fold higher) and cannot exceed what the gap holds. The fold stands on the pads' centres, so a
// finger's own small dent sits at the foot of each flank. The skin round is drawn toward the crest line along s (strongest at the pads,
// fading over ~3 pad radii outside them, and across over ~2 more), the fold leans a little toward gravity (softer: more), and the fragment
// stage adds a paler crest, a flush at the pads, a darker crease at the foot of each flank, and faint ripples across the pull (tension). Pulled
// apart the skin goes taut (a faint lighter tone, no fold).
constexpr float kPinchMinM = 0.010f;     // the nearest two fingertips are a pinch when they land (below it they are one blob)
constexpr float kPinchMaxM = 0.080f;     // the farthest
constexpr float kPinchKeepM = 0.100f;    // a pinch in progress is let go past this (hysteresis)
constexpr float kPinchOnsetM = 0.004f;   // the fold starts once they have closed by this much
constexpr float kPinchSoftMin = 0.30f;   // only on a soft region (breast, butt, thigh, belly, groin, arm, other; not the ribs, shins, skull, hip's bone)
constexpr float kPinchWidthMul = 2.1f;   // the fold's half-width over the pad radius (4.6 cm across its base at 1x, about 2.6 cm at half its height)
constexpr float kPinchCrestP = 2.0f;     // the crest's rounding (higher: flatter top, steeper walls)
constexpr float kPinchCrestQ = 1.4f;     // the foot's softness (1: a kink where the flank meets the skin; higher: it eases in, no hard edge in the light)
constexpr float kPinchReachMul = 3.0f;   // the skin outside the pads is drawn in over this many pad radii
constexpr float kPinchVertexH = 0.7f;    // the mesh is displaced by this share of the fold, over a broader footprint (a body's triangles are centimetres wide: a sharp fold on
constexpr float kPinchVertexReach = 1.2f; // so few vertices facets); the fragment stage's normals carry the sharp fold. The footprint: the gap plus this many pad radii on each side
constexpr float kPinchVertexWide = 1.3f; // and the width times this
constexpr float kPinchLean = 0.22f;      // the fold's lean toward gravity (a share of its height, times the region's softness)

struct PinchIn {
    uint32_t key = 0;
    V3 a, b;                  // the fingertips on the skin (world)
    V3 normal{0, 0, 1};       // the skin's outward normal between them
    float pad = 0.012f;       // the finger's pad radius (world)
    float l0 = 0.04f;         // the fingertips' distance when the fold began (the most they were apart)
    float pressure = 1;       // 0..1
    int region = kCrThigh;
};
struct PinchOut {
    V3 a, b, normal;
    float radius = 0.019f;    // the fold's half-width w
    float pad = 0.012f;
    float ridge = 0;          // the fold's height H now (world; spring-smoothed)
    float dent = 0;           // each fingertip's own dent (small)
    float squeeze = 0;        // the skin round drawn toward the crest line, as a share of kPinchReachMul pad radii at the pads (0..0.3)
    float flush = 0;          // the fingertip pads
    float blanch = 0;         // the crest
    float taut = 0;           // 0..1: stretched (faint light tone)
    float lean = 0;           // the fold's lean toward gravity (0..0.3)
    float ripple = 0;         // 0..1: the creases across the pull (tension)
};
// The tallest a fold gets in a region (world units): the skin and fat there, pad radii * (0.55 + 1.2 * softness), softness the region's times the option's (clamped to 1.4).
float pinchHeightMax(float pad, float softness);
// The fold a pinch asks for at fingertip distance `l` from `l0`: the skin gathered, 0.5 sqrt(l0^2 - l^2) (the tent's height that keeps its length)
// but no more than 1.2 of what has closed (so it starts gently), saturating smoothly (tanh) at pinchHeightMax; zero (and `taut` rising) as they pull apart.
float pinchRidgeTarget(float l, float l0, float pad, float softness, float* taut = nullptr);
// The fold's height cap for fingertips `l` apart: no taller than the gap can hold (1.1 l + 0.3 pad).
float pinchFoldHeight(float ridge, float l, float pad);
// The fold's height at (s, t) in the pinch's frame (what the fragment stage's normals follow), for a gap `l`, half-width `w`, height `h` (>= 0): the formula above (0 outside the fold).
float pinchFold(float s, float t, float l, float w, float h);
// What the vertices follow: the same fold, lower (kPinchVertexH) and broader (kPinchVertexReach, kPinchVertexWide).
float pinchFoldVertex(float s, float t, float l, float w, float h, float pad);
// Displacement of a point `p` (skin normal `n`) by a pinch: what the vertex shader adds; `blanch`, `flush`, `taut` the shading there.
V3 pinchDisplace(const PinchOut& c, const V3& p, const V3& n, float* blanch = nullptr, float* flush = nullptr, float* taut = nullptr);

// One finger on one body, as the tracker takes it.
struct PinchTouch {
    int pointer = 0;
    V3 pos, normal{0, 0, 1};
    float pad = 0.012f;
    float pressure = 1;
    int region = kCrOther;
};
// Decides which of one body's fingers are pinching which. A pair forms when two fingers are within kPinchMinM..kPinchMaxM of each other
// (in `metre` world units: world units per metre) on soft flesh facing alike, and becomes a pinch once they have closed by kPinchOnsetM;
// it then holds until a finger lifts or they go past kPinchKeepM. While it is only a candidate the fingers are plain touches.
class PinchTracker {
public:
    struct Pair { bool used = false, latched = false; int pa = 0, pb = 0; float l0 = 0; };
    // `worldPerMetre`: the units the distances are in. Returns the pinches this frame (latched pairs) in `out` (at most kPinchMax).
    int update(const PinchTouch* t, int n, float worldPerMetre, PinchIn* out, uint32_t keyBase);
    // Whether `pointer` is part of a latched pinch (its own dent is the pinch's).
    bool pinching(int pointer) const;
    int pairs() const { int n = 0; for (const Pair& p : pair_) if (p.used) ++n; return n; }
    const Pair* pair(int i) const { return &pair_[i]; }
    void clear() { for (Pair& p : pair_) p = Pair(); }
private:
    Pair pair_[kPinchMax];
};

class ContactSolver {
public:
    void setLevel(int level) { level_ = level; }
    void setSoftness(float s);       // the Softness slider: 0 .. 2 (1: as the regions say)
    void setLoad(int load) { load_ = load; }
    int level() const { return level_; }
    float softness() const { return softness_; }

    void begin();                    // this frame's inputs follow (add), then step
    void add(const ContactIn& c);
    void addPinch(const PinchIn& p);
    // Advances by dtSeconds (clamped to 0.25): the springs, the fades, the merge and the cap.
    void step(float dtSeconds);
    void clear();

    int count() const { return outN_; }
    const ContactOut* out() const { return out_; }
    int pinchCount() const { return pinchN_; }
    const PinchOut* pinchOut() const { return pinchOut_; }
    // For the tests: the slots alive.
    int alive() const;
    float depthOf(uint32_t key) const;
    float flushOf(uint32_t key) const;
    float ridgeOf(uint32_t key) const;

private:
    struct Slot {
        bool alive = false;
        bool fed = false;            // asked for this frame
        uint32_t key = 0;
        V3 pos, normal{0, 0, 1};
        float radius = 0.05f, target = 0, x = 0, v = 0, peak = 0, fade = 0, flush = 0, soft = 0, maxDepth = 0.02f;
        int region = kCrOther;
        bool touch = false;
    };
    struct PSlot {
        bool alive = false, fed = false;
        uint32_t key = 0;
        V3 a, b, normal{0, 0, 1};
        float pad = 0.012f, target = 0, x = 0, v = 0, fade = 0, flush = 0, soft = 0, taut = 0, tautTarget = 0, press = 0;
        int region = kCrThigh;
    };
    void stepPinches(float dt, int capAll);
    std::vector<ContactIn> in_;
    std::vector<PinchIn> pin_;
    Slot slot_[kContactSlots];
    PSlot pslot_[kPinchMax];
    ContactOut out_[kContactMax];
    PinchOut pinchOut_[kPinchMax];
    int pinchN_ = 0;
    int outN_ = 0;
    int level_ = kContactHigh, load_ = 0;
    float softness_ = 1.0f;
};

// ---- Deriving contacts from two bodies' proxies ----

struct ContactCapsule { V3 a, b; float r = 0.05f; int region = kCrOther; };   // a region of the pressed body (world)
struct ContactTool {
    V3 p;                         // the pressing point (world): a palm, a fingertip, the mouth, the pelvis, a thigh
    float radius = 0.02f;         // its own size (the palm 0.035, a fingertip 0.008, the mouth 0.02, the pelvis 0.07)
    float spread = 0.05f;         // the dent's radius it makes
    float press = 1;              // 0..1.5: how firmly (the stage's grip)
    uint32_t id = 0;
};
// For each tool the capsule it is nearest to, if within reach (gap under kContactReach): a ContactIn for the pressed body. `bodyKey`
// separates the pressed bodies' key spaces. Returns how many were written (at most `max`).
constexpr float kContactReach = 0.012f;
int deriveContacts(const ContactTool* tools, int nt, const ContactCapsule* caps, int nc, uint32_t bodyKey, ContactIn* out, int max);

// The contact region of a body-pick hit (bodypick.h's coarse region code, the hit's height over the head bone's pivot, its sideways offset
// in pelvis heights, whether it was touched from in front): the same bands as simulation/InteractRegions.kt, with the groin's own.
constexpr float kGroinLow = 0.555f, kGroinHigh = 0.607f, kGroinHalfWidth = 0.04f;   // (the bands in the hit's height and across)
int contactRegionForPick(int code, float height, float across, bool front);

// The distance from a point to a capsule's axis segment (and the nearest point on it).
float contactSegment(const V3& p, const V3& a, const V3& b, V3* nearest);

} // namespace wc
