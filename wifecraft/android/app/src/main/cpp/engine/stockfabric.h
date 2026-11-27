// The stockings' fabric: a position-based-dynamics layer over the stocking shell (stockings.h). Each shell vertex is bound to where the skinned
// leg puts it (its target) by a damped spring; the shell's edges are distance constraints with a rest length; a finger grabs the fabric at a
// vertex and drags a falloff of it sideways (in the view plane), which stretches the edges between the dragged part and the part held by
// the leg: the strain of the worst edge is the tension (0 slack .. 1 where the yarn gives). At full tension the stretched edges break and a
// run (a ladder) starts: a stochastic path of edges broken along the leg's length (the weave's direction), a few centimetres, which lengthens
// if pulled again; the triangles on a broken edge are dropped (skin shows) and the ones beside them are the rim. On release the fabric springs
// back, damped, to fit the leg again. Undo takes back the last damage, repairAll all of it. Deterministic for a seed. Pure maths, no GL
// (engine-test/stockfabric_test.cpp); the Scene steps it and uploads its positions (stockdraw.cpp).
#pragma once

#include <cstdint>
#include <vector>

#include "wmath.h"

namespace wc {

struct FabricParams {
    float elasticity = 0.6f;      // 0 stiff .. 1 very stretchy: how easily the edges give before the yarn does
    float tearStrain = 0.35f;     // an edge stretched this much (0.35: 35% longer) is at full tension
    float bindStiffness = 60.0f;  // the spring to the leg (per second squared)
    float damping = 6.0f;         // velocity damping per second
    int iterations = 6;           // constraint passes per step (the governor lowers it)
    float runLength = 0.06f;      // how far a new run spreads along the leg, model units (yards: ~5 cm)
    float runGrow = 0.03f;        // how much further it goes each time it is pulled again at full tension
};

class StockFabric {
public:
    // The shell: rest positions (the skinned leg's, in the bind pose), the triangles; seed for the runs.
    void init(const std::vector<V3>& rest, const std::vector<uint32_t>& tris, uint32_t seed);

    // Where the leg puts each vertex this frame (the skinned shell positions, model space): the fabric is bound to them.
    void setTargets(const std::vector<V3>& targets);

    // A finger on the fabric near `point` (model space, the nearest vertex of the live triangles within `reach`): false when nothing is there.
    bool grab(const V3& point, float reach, float radius);
    // The grabbed fabric follows `to` (the finger's place, model space; the view plane is the caller's): the falloff around the grab point.
    void drag(const V3& to);
    void release();
    bool grabbed() const { return grabbed_ >= 0; }

    void step(float dt);

    float tension() const { return tension_; }            // 0..1 (the worst edge's strain over the tearing strain)
    int tornCount() const { return runs_; }                // runs made (undo takes them back)
    size_t deadTriangles() const;
    // damage kept for undo: each tear (a run) is one step
    bool undo();                                           // takes back the latest tear; false: none
    void repairAll();

    const std::vector<V3>& positions() const { return p_; }
    const std::vector<uint8_t>& alive() const { return alive_; }     // per triangle
    const std::vector<uint8_t>& rim() const { return rim_; }          // per vertex: on the edge of a tear
    const std::vector<float>& strain() const { return vstrain_; }     // per vertex: its worst edge's strain (the weave thins and stretches by it)
    size_t vertexCount() const { return p_.size(); }
    size_t triangleCount() const { return tris_.size() / 3; }
    FabricParams params;

    // the edge constraints' worst violation after the last step (a check): the largest (length / rest) over the unbroken edges
    float worstRatio() const;

private:
    struct Edge { uint32_t a, b; float rest; bool broken = false; };
    std::vector<V3> rest_, p_, v_, target_;
    std::vector<uint32_t> tris_;
    std::vector<Edge> edges_;
    std::vector<std::vector<uint32_t>> vertEdges_;       // per vertex: its edges
    std::vector<std::vector<uint32_t>> edgeTris_;        // per edge: the triangles on it
    std::vector<uint8_t> alive_, rim_;
    std::vector<float> weight_, vstrain_;
    struct Damage { std::vector<uint32_t> edges; };
    std::vector<Damage> history_;
    int grabbed_ = -1;
    V3 grabRest_, grabTo_;
    float tension_ = 0;
    int runs_ = 0;
    uint32_t seed_ = 1, rng_ = 1;
    float rnd();
    void tear(uint32_t from);
    void refresh();
};

} // namespace wc
