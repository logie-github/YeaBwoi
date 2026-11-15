// The renderer's small GL helpers, shared by scene.cpp and location.cpp (defined in scene.cpp).
#pragma once

#include <cmath>
#include <cstdint>

#include "wmath.h"

namespace wc {
namespace gl {

unsigned program(const char* vs, const char* fs);
// A uniform's location, looked up once per program and name (the name must be a string literal).
int loc(unsigned p, const char* name);
void blendFor(uint32_t mode);
// M2Vertex as the m2 programs read it (positions 0, normals 1, uvs 2, bones 3, weights 4), for the bound vertex buffer.
void m2VertexLayout();

// The six planes of the camera's view (ax + by + cz + d >= 0 inside), from its matrix.
struct Frustum {
    float p[6][4];
    explicit Frustum(const M4& m) {
        for (int i = 0; i < 3; ++i)
            for (int s = 0; s < 2; ++s) {
                float* q = p[i * 2 + s];
                float sign = s ? -1.0f : 1.0f;
                for (int c = 0; c < 4; ++c) q[c] = m.at(3, c) + sign * m.at(i, c);
                float n = std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
                if (n > 0) for (int c = 0; c < 4; ++c) q[c] /= n;
            }
    }
    bool sphere(const V3& c, float r) const {
        for (const auto& q : p) if (q[0] * c.x + q[1] * c.y + q[2] * c.z + q[3] < -r) return false;
        return true;
    }
    bool box(const V3& mn, const V3& mx) const {
        for (const auto& q : p) {
            V3 v(q[0] >= 0 ? mx.x : mn.x, q[1] >= 0 ? mx.y : mn.y, q[2] >= 0 ? mx.z : mn.z);
            if (q[0] * v.x + q[1] * v.y + q[2] * v.z + q[3] < 0) return false;
        }
        return true;
    }
};

} // namespace gl
} // namespace wc
