#include "sparkle.h"

#include <algorithm>
#include <cmath>

#ifndef WC_NO_GL
#include <GLES3/gl3.h>
#include "glutil.h"
#endif

namespace wc {

namespace {
constexpr float kPi = 3.14159265f;

// A small integer hash (lowbias32) and a 0..1 float from it.
uint32_t mix(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352dU; x ^= x >> 15; x *= 0x846ca68bU; x ^= x >> 16;
    return x;
}
float unit(uint32_t a, uint32_t b, uint32_t c) {
    return float(mix(a * 0x9E3779B1U + mix(b * 0x85EBCA77U + mix(c + 0x27D4EB2FU))) >> 8) / float(1u << 24);
}
}

void SparkleField::set(const float* data, int count, float density) {
    spots_.clear();
    density_ = std::isfinite(density) ? std::clamp(density, 0.0f, 1.0f) : 0.0f;
    if (!data || count <= 0) return;
    for (int i = 0; i < std::min(count, kMaxSpots); ++i) {
        const float* d = data + size_t(i) * kStride;
        bool finite = true;
        for (int k = 0; k < kStride; ++k) finite = finite && std::isfinite(d[k]);
        if (!finite || d[6] <= 0) continue;
        SparkleSpot s;
        s.centre = V3(d[0], d[1], d[2]);
        s.halfX = std::clamp(d[3], 0.05f, 6.0f);
        s.halfY = std::clamp(d[4], 0.05f, 6.0f);
        s.yaw = d[5];
        s.strength = std::clamp(d[6], 0.0f, 1.0f);
        s.seed = d[7];
        spots_.push_back(s);
    }
}

int SparkleField::motesFor(float halfX, float halfY, float density) {
    if (!(density > 0)) return 0;
    float area = 4.0f * std::max(halfX, 0.05f) * std::max(halfY, 0.05f);   // square yards
    int n = int(std::lround(3.0f + area * 3.0f));
    n = std::clamp(n, 4, kMaxPerSpot);
    return std::max(2, int(std::lround(float(n) * std::clamp(density, 0.0f, 1.0f))));
}

float SparkleField::lifeAlpha(float f, float seconds, float phase) {
    f = std::clamp(f, 0.0f, 1.0f);
    float fade = std::min(1.0f, std::min(f / 0.1f, (1.0f - f) / 0.9f));
    float twinkle = 0.75f + 0.25f * std::sin(seconds * 11.0f + phase);
    return std::clamp(fade * twinkle, 0.0f, 1.0f);
}

void SparkleField::generate(float seconds, std::vector<Spark>& out) const {
    if (!std::isfinite(seconds) || density_ <= 0) return;
    for (size_t si = 0; si < spots_.size(); ++si) {
        const SparkleSpot& s = spots_[si];
        const uint32_t seed = uint32_t(std::lround(s.seed)) * 2654435761U + uint32_t(si) * 97U;
        const int n = motesFor(s.halfX, s.halfY, density_);
        const float cy = std::cos(s.yaw), sy = std::sin(s.yaw);
        for (int i = 0; i < n; ++i) {
            const float life = 1.5f + unit(seed, uint32_t(i), 1) * 1.5f;
            const float u = (seconds + unit(seed, uint32_t(i), 2) * life) / life;
            const float cycle = std::floor(u);
            const float f = u - cycle;
            // where on the footprint it rises this time round
            const float lx = (unit(seed, uint32_t(i), 3 + uint32_t(cycle) * 7u) * 2 - 1) * s.halfX;
            const float ly = (unit(seed, uint32_t(i), 4 + uint32_t(cycle) * 7u) * 2 - 1) * s.halfY;
            Spark k;
            k.p = V3(s.centre.x + lx * cy - ly * sy, s.centre.y + lx * sy + ly * cy, s.centre.z + 0.04f + f * kRise);
            k.size = 0.09f + unit(seed, uint32_t(i), 5) * 0.07f;
            k.alpha = lifeAlpha(f, seconds, unit(seed, uint32_t(i), 6) * 2 * kPi) * s.strength;
            k.white = unit(seed, uint32_t(i), 7 + uint32_t(cycle) * 7u) < 0.4f ? 1.0f : 0.0f;
            if (k.alpha > 0.01f) out.push_back(k);
        }
    }
}

#ifndef WC_NO_GL
namespace {
const char* kSparkVs = R"(#version 300 es
layout(location = 0) in vec4 aPos;     // xyz, size (yards)
layout(location = 1) in vec4 aColor;   // rgb, alpha
uniform mat4 uViewProj;
uniform float uPixels;
out vec4 vColor;
void main() {
    gl_Position = uViewProj * vec4(aPos.xyz, 1.0);
    float w = max(gl_Position.w, 0.05);
    gl_PointSize = clamp(aPos.w * uPixels / w, 6.0, 96.0);
    vColor = aColor;
}
)";
// a soft core and four thin points, like the game's sparkle
const char* kSparkFs = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 frag;
void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(c, c);
    if (r2 > 1.0) discard;
    float core = exp(-r2 * 14.0);
    float cross = exp(-abs(c.x) * 22.0) * exp(-abs(c.y) * 2.6) + exp(-abs(c.y) * 22.0) * exp(-abs(c.x) * 2.6);
    float g = clamp(core * 1.2 + cross * 0.9, 0.0, 1.5) * (1.0 - r2);
    frag = vec4(vColor.rgb * vColor.a * g, 1.0);
}
)";
} // namespace

void SparkleDraw::draw(const std::vector<Spark>& sparks, const M4& viewProj, const V3& eye, float pixelsPerYard) {
    if (sparks.empty()) return;
    if (!prog_) {
        prog_ = gl::program(kSparkVs, kSparkFs);
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
    for (const Spark& s : sparks) {
        if ((s.p - eye).length() < 0.4f) continue;   // a blob over the lens
        const float w = s.white;
        data_.insert(data_.end(), {s.p.x, s.p.y, s.p.z, s.size, 1.0f, 0.80f + 0.20f * w, 0.35f + 0.65f * w, s.alpha});
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

void SparkleDraw::release() { prog_ = vao_ = vbo_ = 0; data_.clear(); }
#else
void SparkleDraw::draw(const std::vector<Spark>&, const M4&, const V3&, float) {}
void SparkleDraw::release() {}
#endif

} // namespace wc
