// Interactable sparkles: the small gold and white twinkling motes that rise and fade over a spot a body can use (a bed, a
// seat, a table, a user-made spot), as the game's own interactable objects sparkle. Procedural and cheap: each spot makes a
// handful of motes from the clock alone (SparkleField::generate: no state, the same everywhere, engine-test/sparkle_test
// runs it without GL), drawn as additive four-point-star point sprites (SparkleDraw) with no texture, depth-tested against
// the scene and not writing depth. The screen decides which spots, how many (the Graphics page's governor lowers the density)
// and whether any at all (a switch; off in build mode's own look and in cinematic mode), the renderer only draws.
#pragma once

#include <cstdint>
#include <vector>

#include "wmath.h"

namespace wc {

// A spot's sparkles: the surface's middle ([centre], z the surface's height), the footprint's half sizes and turn, how
// strongly it shows now (0..1: 0 while it is occupied or switched off) and a seed that keeps its motes its own.
struct SparkleSpot {
    V3 centre;
    float halfX = 0.5f, halfY = 0.5f, yaw = 0, strength = 1, seed = 0;
};

struct Spark {
    V3 p;
    float size = 0.05f;    // the star's diameter (yards)
    float alpha = 0;       // 0..1
    float white = 0;       // 0 gold .. 1 white
};

class SparkleField {
public:
    static constexpr int kMaxSpots = 48;
    static constexpr int kStride = 8;           // floats a spot: x, y, z, halfX, halfY, yaw (radians), strength, seed
    static constexpr int kMaxPerSpot = 14;
    static constexpr float kRise = 0.55f;       // how far up a mote drifts over its life (yards)

    // [count] spots of kStride floats (more than kMaxSpots: the first ones); [density] 0..1 scales the motes of each.
    void set(const float* data, int count, float density);
    bool empty() const { return spots_.empty() || density_ <= 0; }
    const std::vector<SparkleSpot>& spots() const { return spots_; }
    float density() const { return density_; }

    // How many motes a spot of this footprint makes at [density] (a small one 4, the largest kMaxPerSpot; none at 0).
    static int motesFor(float halfX, float halfY, float density);
    // The motes at [seconds] on the animations' clock, appended to [out].
    void generate(float seconds, std::vector<Spark>& out) const;
    // A mote's brightness over its life [f] 0..1 (in over the first tenth, out over the rest, twinkling [phase]).
    static float lifeAlpha(float f, float seconds, float phase);

private:
    std::vector<SparkleSpot> spots_;
    float density_ = 0;
};

class SparkleDraw {
public:
    // Draws [sparks] as additive star sprites; [pixelsPerYard] as the atmosphere's motes take it.
    void draw(const std::vector<Spark>& sparks, const M4& viewProj, const V3& eye, float pixelsPerYard);
    void release();   // the GL objects forgotten (the context is gone)

private:
    unsigned prog_ = 0, vao_ = 0, vbo_ = 0;
    std::vector<float> data_;
};

} // namespace wc
