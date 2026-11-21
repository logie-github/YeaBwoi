// The live scene's build mode overlay (scene.h, "build mode's overlay"): the axis gizmo at a point of the world (three arrows
// along the thing's own x, y and z, and the yaw ring), the location markers (a ring on the floor, a pin, a facing arrow) and
// the selected item's box, drawn over the scene with the placement editor's gizmo program (editview.cpp makes the same one:
// whichever is drawn first creates it). The screens pick the handles themselves (simulation/BuildModeMath.kt projects them
// with the camera the renderer reports), so nothing here is picked. Also the location's doodads described for build mode
// (locationObjectInfo: which model, where, turned how, its box), so any prop can be selected and given an interaction.
#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "glutil.h"
#include "scene.h"

namespace wc {

using gl::loc;

namespace {

constexpr float kBuildPi = 3.14159265f;
// The gizmo's arrows are this share of the view's height long, wherever the thing stands (the editor's own share).
constexpr float kBuildShare = 0.19f;

const char* kBuildVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProj;
uniform vec3 uEye;
out vec4 vColor;
void main() {
    vec3 v = normalize(uEye - aPos);
    float lit = 0.55 + 0.45 * abs(dot(normalize(aNormal), v));
    vColor = vec4(aColor.rgb * lit, aColor.a);
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
)";

const char* kBuildFs = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() { fragColor = vColor; }
)";

// x red, y green, z blue, the yaw ring gold
const float kBuildColour[4][3] = {
    {0.95f, 0.25f, 0.22f}, {0.35f, 0.85f, 0.28f}, {0.25f, 0.50f, 1.00f}, {1.00f, 0.78f, 0.22f},
};

// Triangles of position, normal, colour (10 floats a vertex), as the editor's gizmo draws them.
struct BuildMesh {
    std::vector<float> v;
    float c[4] = {1, 1, 1, 1};
    void colour(float r, float g, float b, float a) { c[0] = r; c[1] = g; c[2] = b; c[3] = a; }
    void vert(const V3& p, const V3& n) { v.insert(v.end(), {p.x, p.y, p.z, n.x, n.y, n.z, c[0], c[1], c[2], c[3]}); }
    void tri(const V3& a, const V3& b, const V3& d, const V3& n) { vert(a, n); vert(b, n); vert(d, n); }
    static void basis(const V3& d, V3& u, V3& w) {
        u = d.cross(std::fabs(d.z) < 0.9f ? V3(0, 0, 1) : V3(1, 0, 0)).normalized();
        w = d.cross(u).normalized();
    }
    void tube(const V3& a, const V3& b, float r, int segs = 10) {
        V3 axis = b - a;
        if (axis.length() < 1e-5f) return;
        V3 d = axis.normalized(), u, w;
        basis(d, u, w);
        for (int i = 0; i < segs; ++i) {
            float t0 = 2 * kBuildPi * float(i) / float(segs), t1 = 2 * kBuildPi * float(i + 1) / float(segs);
            V3 n0 = u * std::cos(t0) + w * std::sin(t0), n1 = u * std::cos(t1) + w * std::sin(t1);
            vert(a + n0 * r, n0); vert(b + n0 * r, n0); vert(b + n1 * r, n1);
            vert(a + n0 * r, n0); vert(b + n1 * r, n1); vert(a + n1 * r, n1);
        }
    }
    void cone(const V3& base, const V3& tip, float r, int segs = 14) {
        V3 d = (tip - base).normalized(), u, w;
        basis(d, u, w);
        for (int i = 0; i < segs; ++i) {
            float t0 = 2 * kBuildPi * float(i) / float(segs), t1 = 2 * kBuildPi * float(i + 1) / float(segs);
            V3 e0 = u * std::cos(t0) + w * std::sin(t0), e1 = u * std::cos(t1) + w * std::sin(t1);
            V3 n0 = (e0 + d * 0.4f).normalized(), n1 = (e1 + d * 0.4f).normalized();
            vert(base + e0 * r, n0); vert(tip, (n0 + n1).normalized()); vert(base + e1 * r, n1);
            tri(base, base + e1 * r, base + e0 * r, d * -1.0f);
        }
    }
    // A ring about z through c0: radius R, tube r.
    void ring(const V3& c0, float R, float r, int segs = 48) {
        for (int i = 0; i < segs; ++i) {
            float a0 = 2 * kBuildPi * float(i) / float(segs), a1 = 2 * kBuildPi * float(i + 1) / float(segs);
            tube(c0 + V3(std::cos(a0), std::sin(a0), 0) * R, c0 + V3(std::cos(a1), std::sin(a1), 0) * R, r, 6);
        }
    }
};

}  // namespace

void Scene::setBuildGizmo(int flags, V3 pos, float yaw, int activeAxis) {
    buildFlags_ = flags; buildPos_ = pos; buildYaw_ = yaw; buildAxis_ = activeAxis;
}

void Scene::setBuildMarkers(const float* data, int count) {
    buildMarkers_.assign(data, data + (count > 0 && data ? size_t(count) * kBuildMarkerFloats : 0));
}

void Scene::setBuildBox(const float* data, int floats) {
    buildBox_.assign(data, data + (floats >= 7 && data ? 7 : 0));
}

bool Scene::locationObjectInfo(int index, float* out) const {
    if (!location_ || index < 0) return false;
    const std::vector<Instance>& list = index >= 1000000 ? locExtras_ : location_->doodads;
    const size_t i = size_t(index >= 1000000 ? index - 1000000 : index);
    if (i >= list.size()) return false;
    const Instance& in = list[i];
    V3 o = in.world.point(V3());
    V3 ax = in.world.point(V3(1, 0, 0)) - o;
    float scale = ax.length();
    V3 mn(-0.5f, -0.5f, 0), mx(0.5f, 0.5f, 1);
    if (in.mesh && in.mesh->model) { mn = in.mesh->model->extentMin; mx = in.mesh->model->extentMax; }
    const float v[12] = {float(in.modelFileId), o.x, o.y, o.z, std::atan2(ax.y, ax.x), scale, mn.x, mn.y, mn.z, mx.x, mx.y, mx.z};
    std::copy(v, v + 12, out);
    return true;
}

void Scene::drawBuild() {
    const bool gizmo = (buildFlags_ & (kBuildArrows | kBuildRing)) != 0;
    if (!gizmo && buildMarkers_.empty() && buildBox_.empty()) return;
    if (!gizmoProg_) {
        gizmoProg_ = gl::program(kBuildVs, kBuildFs);
        glGenVertexArrays(1, &gizmoVao_);
        glGenBuffers(1, &gizmoVbo_);
    }
    const V3 f = (target_ - eye_).normalized();
    const float tanHalf = std::tan(fov_ * kBuildPi / 360.0f);
    // a length that is the same share of the screen near or far
    auto screenLength = [&](const V3& p, float share) { return share * 2.0f * std::max(0.3f, (p - eye_).dot(f)) * tanHalf; };
    BuildMesh m;

    // the markers: a ring on the floor, a pin up from it with a knob, a flat arrow along its facing
    for (size_t k = 0; k + kBuildMarkerFloats <= buildMarkers_.size(); k += kBuildMarkerFloats) {
        const float* q = &buildMarkers_[k];
        V3 p(q[0], q[1], q[2]);
        const float yaw = q[3];
        const bool chosen = q[7] > 0.5f;
        const float s = std::clamp(screenLength(p, 0.05f), 0.08f, 0.45f) * (chosen ? 1.3f : 1.0f);
        m.colour(q[4], q[5], q[6], chosen ? 1.0f : 0.8f);
        m.ring(p + V3(0, 0, 0.01f), s, s * 0.08f);
        m.tube(p, p + V3(0, 0, s * 2.2f), s * 0.07f);
        m.cone(p + V3(0, 0, s * 2.2f), p + V3(0, 0, s * 2.7f), s * 0.22f);
        V3 dir(std::cos(yaw), std::sin(yaw), 0);
        m.tube(p + V3(0, 0, 0.02f), p + dir * (s * 1.3f) + V3(0, 0, 0.02f), s * 0.06f);
        m.cone(p + dir * (s * 1.3f) + V3(0, 0, 0.02f), p + dir * (s * 1.8f) + V3(0, 0, 0.02f), s * 0.2f);
    }

    // the selected item's box: twelve edges, turned by its yaw
    if (buildBox_.size() == 7) {
        const float* b = buildBox_.data();
        const V3 c(b[0], b[1], b[2]), h(b[3], b[4], b[5]);
        const float cy = std::cos(b[6]), sy = std::sin(b[6]);
        auto corner = [&](int i) {
            V3 l((i & 1) ? h.x : -h.x, (i & 2) ? h.y : -h.y, (i & 4) ? h.z : -h.z);
            return c + V3(l.x * cy - l.y * sy, l.x * sy + l.y * cy, l.z);
        };
        const float r = std::clamp(screenLength(c, 0.004f), 0.004f, 0.03f);
        m.colour(0.72f, 0.86f, 1.0f, 0.85f);   // the placing ghost's blue-white
        const int edges[12][2] = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (const auto& e : edges) m.tube(corner(e[0]), corner(e[1]), r, 6);
    }

    // the gizmo: arrows along the thing's own axes (x and y turned by its yaw, z up) and the yaw ring about z
    if (gizmo) {
        const V3 p = buildPos_;
        const float L = screenLength(p, kBuildShare);
        const float c = std::cos(buildYaw_), s = std::sin(buildYaw_);
        const V3 axes[3] = {V3(c, s, 0), V3(-s, c, 0), V3(0, 0, 1)};
        auto colour = [&](int axis) {
            const float* k = kBuildColour[axis];
            const bool active = buildAxis_ == axis;
            const float lift = active ? 0.45f : 0.0f;
            m.colour(k[0] + (1 - k[0]) * lift, k[1] + (1 - k[1]) * lift, k[2] + (0.75f - k[2]) * lift,
                     active ? 1.0f : buildAxis_ >= 0 ? 0.35f : 0.85f);
            return active ? 1.4f : 1.0f;
        };
        if (buildFlags_ & kBuildArrows) {
            for (int a = 0; a < 3; ++a) {
                const float g = colour(a);
                m.tube(p + axes[a] * (0.10f * L), p + axes[a] * (0.76f * L), 0.028f * L * g);
                m.cone(p + axes[a] * (0.76f * L), p + axes[a] * (1.0f * L), 0.085f * L * g);
            }
        }
        if (buildFlags_ & kBuildRing) {
            const float g = colour(3);
            m.ring(p, 0.9f * L, 0.03f * L * g);
            // the facing: a small cone on the ring where the thing looks
            m.cone(p + axes[0] * (0.84f * L), p + axes[0] * (1.02f * L), 0.06f * L * g);
        }
    }
    if (m.v.empty()) return;

    const M4 vp = proj_ * view_;
    // over everything (the frame's last drawing): the scene's depth let go, the handles still hide each other
    glClear(GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glDepthMask(GL_TRUE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glUseProgram(gizmoProg_);
    glUniformMatrix4fv(loc(gizmoProg_, "uViewProj"), 1, GL_FALSE, vp.m);
    glUniform3f(loc(gizmoProg_, "uEye"), eye_.x, eye_.y, eye_.z);
    glBindVertexArray(gizmoVao_);
    glBindBuffer(GL_ARRAY_BUFFER, gizmoVbo_);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(m.v.size() * 4), m.v.data(), GL_STREAM_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 40, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 40, (void*)12);
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, 40, (void*)24);
    glDrawArrays(GL_TRIANGLES, 0, GLsizei(m.v.size() / 10));
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

}  // namespace wc
