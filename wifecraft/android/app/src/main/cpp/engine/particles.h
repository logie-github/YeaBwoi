// Atmosphere: a few dozen glowing motes (embers and dust in the warm light) drifting slowly up and across a box round what
// the camera looks at, each fading in and out over its life; drawn as soft additive points over the scene (before the
// cinematic grade, so they take it). The field is plain, seeded and deterministic (ParticleField: engine-test/
// particles_test steps it without GL); ParticleDraw holds its GL program and buffer.
#pragma once

#include <cstdint>
#include <vector>

#include "wmath.h"

namespace wc {

struct Mote {
    V3 p, v;              // where (world, yards) and drifting how (yards a second)
    float age = 0, life = 1;
    float size = 0.03f;   // its glow's diameter (yards)
    float warmth = 1;     // 1 an ember's orange .. 0 a pale dust mote
    float phase = 0;      // its twinkle's
};

class ParticleField {
public:
    static constexpr int kMaxCount = 96;
    // [count] motes (0..kMaxCount), seeded: the same seed and steps give the same motes everywhere.
    void configure(int count, uint32_t seed);
    // The box they live in (world): its centre and half sizes. Motes outside a moved box are made again inside it.
    void setBox(const V3& centre, const V3& half);
    void step(float dt);
    const std::vector<Mote>& motes() const { return motes_; }
    // A mote's brightness now 0..1 (faded in and out over its life, twinkling a little).
    static float alpha(const Mote& m);
    uint32_t seed() const { return rng_; }
    const V3& centre() const { return centre_; }
    const V3& half() const { return half_; }

private:
    float rand01();
    void spawn(Mote& m, bool anywhere);
    bool inside(const V3& p) const;
    std::vector<Mote> motes_;
    uint32_t rng_ = 1;
    V3 centre_, half_{1.5f, 1.5f, 1.0f};
    float time_ = 0;
};

class ParticleDraw {
public:
    // Draws [field] with additive blending, depth-tested against the scene but not writing depth; [strength] 0..1 its
    // overall brightness. [pixelsPerYard] how big a yard at one yard away is on the screen (the projection's scale).
    void draw(const ParticleField& field, const M4& viewProj, const V3& eye, float pixelsPerYard, float strength);
    void release();   // the GL objects forgotten (the context is gone)

private:
    unsigned prog_ = 0, vao_ = 0, vbo_ = 0;
    std::vector<float> data_;
};

} // namespace wc
