#include "particles.h"

#include <algorithm>
#include <cmath>

#ifndef WC_NO_GL
#include <GLES3/gl3.h>
#include "glutil.h"
#endif

namespace wc {

namespace {
constexpr float kTau = 6.2831853f;
}

float ParticleField::rand01() {
    // xorshift32: small, fast and the same on every machine
    uint32_t x = rng_ ? rng_ : 0x9E3779B9u;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    rng_ = x;
    return float(x >> 8) / float(1u << 24);
}

bool ParticleField::inside(const V3& p) const {
    V3 d = p - centre_;
    return std::fabs(d.x) <= half_.x * 1.05f && std::fabs(d.y) <= half_.y * 1.05f && std::fabs(d.z) <= half_.z * 1.05f;
}

void ParticleField::spawn(Mote& m, bool anywhere) {
    float x = rand01() * 2 - 1, y = rand01() * 2 - 1, z = rand01() * 2 - 1;
    // a new mote rises from the lower part of the box (embers come up); the first ones are anywhere in it
    if (!anywhere) z = -1.0f + rand01() * 0.6f;
    m.p = centre_ + V3(x * half_.x, y * half_.y, z * half_.z);
    float heading = rand01() * kTau;
    float drift = 0.01f + rand01() * 0.03f;
    m.v = V3(std::cos(heading) * drift, std::sin(heading) * drift, 0.02f + rand01() * 0.05f);
    m.life = 6.0f + rand01() * 8.0f;
    m.age = anywhere ? rand01() * m.life : 0.0f;
    m.size = 0.02f + rand01() * 0.035f;
    m.warmth = rand01() < 0.65f ? 0.7f + rand01() * 0.3f : rand01() * 0.3f;
    m.phase = rand01() * kTau;
}

void ParticleField::configure(int count, uint32_t seed) {
    rng_ = seed ? seed : 1u;
    time_ = 0;
    motes_.assign(size_t(std::clamp(count, 0, kMaxCount)), Mote());
    for (Mote& m : motes_) spawn(m, true);
}

void ParticleField::setBox(const V3& centre, const V3& half) {
    if (!std::isfinite(centre.x) || !std::isfinite(centre.y) || !std::isfinite(centre.z)) return;
    centre_ = centre;
    half_ = V3(std::max(0.2f, half.x), std::max(0.2f, half.y), std::max(0.2f, half.z));
    for (Mote& m : motes_) if (!inside(m.p)) spawn(m, true);
}

void ParticleField::step(float dt) {
    if (!(dt > 0) || !std::isfinite(dt)) return;
    dt = std::min(dt, 0.1f);
    time_ += dt;
    for (Mote& m : motes_) {
        m.age += dt;
        // a slow sway on the drift, so the paths curl a little
        float sway = std::sin(time_ * 0.7f + m.phase) * 0.01f;
        m.p = m.p + (m.v + V3(sway, -sway * 0.5f, 0)) * dt;
        if (m.age >= m.life || !inside(m.p)) spawn(m, false);
    }
}

float ParticleField::alpha(const Mote& m) {
    if (!(m.life > 0)) return 0;
    float t = std::clamp(m.age / m.life, 0.0f, 1.0f);
    float fade = std::min(1.0f, std::min(t / 0.2f, (1.0f - t) / 0.3f));
    float twinkle = 0.8f + 0.2f * std::sin(m.age * 3.1f + m.phase);
    return std::clamp(fade * twinkle, 0.0f, 1.0f);
}

#ifndef WC_NO_GL
namespace {
const char* kMoteVs = R"(#version 300 es
layout(location = 0) in vec4 aPos;     // xyz, size (yards)
layout(location = 1) in vec4 aColor;   // rgb, alpha
uniform mat4 uViewProj;
uniform float uPixels;
out vec4 vColor;
void main() {
    gl_Position = uViewProj * vec4(aPos.xyz, 1.0);
    float w = max(gl_Position.w, 0.05);
    gl_PointSize = clamp(aPos.w * uPixels / w, 1.0, 48.0);
    vColor = aColor;
}
)";
const char* kMoteFs = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 frag;
void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    float r = dot(c, c);
    if (r > 1.0) discard;
    float glow = exp(-r * 4.0) * (1.0 - r);
    frag = vec4(vColor.rgb * vColor.a * glow, 1.0);
}
)";
} // namespace

void ParticleDraw::draw(const ParticleField& field, const M4& viewProj, const V3& eye, float pixelsPerYard, float strength) {
    const auto& ms = field.motes();
    if (ms.empty() || !(strength > 0)) return;
    if (!prog_) {
        prog_ = gl::program(kMoteVs, kMoteFs);
        if (!prog_) return;
        glGenVertexArrays(1, &vao_);
        glGenBuffers(1, &vbo_);
        glBindVertexArray(vao_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), reinterpret_cast<void*>(4 * sizeof(float)));
        glBindVertexArray(0);
    }
    data_.clear();
    float s = std::clamp(strength, 0.0f, 1.0f);
    for (const Mote& m : ms) {
        float a = ParticleField::alpha(m) * s;
        // nearer than half a yard to the eye: left out (a blob over the lens)
        if (a <= 0.01f || (m.p - eye).length() < 0.5f) continue;
        float w = m.warmth;
        data_.insert(data_.end(), {m.p.x, m.p.y, m.p.z, m.size,
                                   1.0f, 0.62f + 0.3f * (1 - w), 0.32f + 0.55f * (1 - w), a * (0.55f + 0.25f * w)});
    }
    if (data_.empty()) return;
    glUseProgram(prog_);
    glUniformMatrix4fv(gl::loc(prog_, "uViewProj"), 1, GL_FALSE, viewProj.m);
    glUniform1f(gl::loc(prog_, "uPixels"), pixelsPerYard);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(data_.size() * sizeof(float)), data_.data(), GL_STREAM_DRAW);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    glDepthMask(GL_FALSE);
    glDrawArrays(GL_POINTS, 0, GLsizei(data_.size() / 8));
    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
}

void ParticleDraw::release() { prog_ = vao_ = vbo_ = 0; data_.clear(); }
#else
void ParticleDraw::draw(const ParticleField&, const M4&, const V3&, float, float) {}
void ParticleDraw::release() {}
#endif

} // namespace wc
