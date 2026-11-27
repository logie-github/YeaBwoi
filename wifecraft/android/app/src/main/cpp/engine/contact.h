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
};

// One contact as the shader takes it (world space).
struct ContactOut {
    V3 pos, normal;
    float radius = 0.05f;
    float depth = 0;           // the dent now (metres, spring-smoothed; negative never)
    float bulge = 0;           // the ring's amplitude (metres)
    float squeeze = 0;         // the tangential pull toward the contact, a share of the distance (0..0.3)
    float flush = 0;           // 0..1: the pressure's colour, the ring (outlives the touch)
    float blanch = 0;          // 0..1: the pressure's pale middle (follows the dent)
    float softness = 0;
};

// The displacement of a point `p` (world) with the skin normal `n` there from one contact: what the vertex shader adds. `shade`
// (optional): the blanching and the flush at that point (what the shader passes the fragment stage).
V3 contactDisplace(const ContactOut& c, const V3& p, const V3& n, float* blanch = nullptr, float* flush = nullptr);

class ContactSolver {
public:
    void setLevel(int level) { level_ = level; }
    void setSoftness(float s);       // the Softness slider: 0 .. 2 (1: as the regions say)
    void setLoad(int load) { load_ = load; }
    int level() const { return level_; }
    float softness() const { return softness_; }

    void begin();                    // this frame's inputs follow (add), then step
    void add(const ContactIn& c);
    // Advances by dtSeconds (clamped to 0.25): the springs, the fades, the merge and the cap.
    void step(float dtSeconds);
    void clear();

    int count() const { return outN_; }
    const ContactOut* out() const { return out_; }
    // For the tests: the slots alive.
    int alive() const;
    float depthOf(uint32_t key) const;
    float flushOf(uint32_t key) const;

private:
    struct Slot {
        bool alive = false;
        bool fed = false;            // asked for this frame
        uint32_t key = 0;
        V3 pos, normal{0, 0, 1};
        float radius = 0.05f, target = 0, x = 0, v = 0, peak = 0, fade = 0, flush = 0, soft = 0, maxDepth = 0.02f;
        int region = kCrOther;
    };
    std::vector<ContactIn> in_;
    Slot slot_[kContactSlots];
    ContactOut out_[kContactMax];
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
