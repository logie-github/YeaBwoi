// The placement editor's view (scene.h, "the placement editor's view"): the gizmo's handles over the selected thing and
// what a finger is on, the camera as drawn (for the screens' own maths), the ghost of what is about to be placed, and the
// library's thumbnails, drawn in an off-screen framebuffer of the same context between frames.
#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <set>

#include "glutil.h"
#include "scene.h"

namespace wc {

using gl::Frustum;
using gl::loc;

namespace {

constexpr float kPi = 3.14159265f;
// The gizmo's arrows are this share of the view's height long, wherever the thing stands.
constexpr float kGizmoShare = 0.19f;
// The ghost: how much of it shows over what is behind it, and its tint (blue-white).
constexpr float kGhostAlpha = 0.55f;
constexpr float kGhostTint[4] = {1.0f, 1.05f, 1.15f, 1.0f};
constexpr float kGhostColour[4] = {0.72f, 0.86f, 1.0f, 0.42f};   // mixed in by its alpha (the shaders' uGhost)

// The ghost's colour on a program (off: none).
void ghostUniform(unsigned prog, bool on) {
    if (!prog) return;
    glUseProgram(prog);
    const float off[4] = {0, 0, 0, 0};
    glUniform4fv(loc(prog, "uGhost"), 1, on ? kGhostColour : off);
}
constexpr int kThumbTex = 256;

const char* kGizmoVs = R"(#version 300 es
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

const char* kGizmoFs = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() { fragColor = vColor; }
)";

// The handles' colours: x, y, z, the yaw ring, the uniform box, the floor square.
const float kAxisColour[6][3] = {
    {0.95f, 0.25f, 0.22f}, {0.35f, 0.85f, 0.28f}, {0.25f, 0.50f, 1.00f},
    {1.00f, 0.78f, 0.22f}, {0.93f, 0.93f, 0.93f}, {1.00f, 0.90f, 0.55f},
};
const V3 kAxisDir[3] = {V3(1, 0, 0), V3(0, 1, 0), V3(0, 0, 1)};

// Triangles of position, normal, colour (10 floats a vertex).
struct GizmoMesh {
    std::vector<float> v;
    float c[4] = {1, 1, 1, 1};
    void vert(const V3& p, const V3& n) { v.insert(v.end(), {p.x, p.y, p.z, n.x, n.y, n.z, c[0], c[1], c[2], c[3]}); }
    void tri(const V3& a, const V3& b, const V3& c2, const V3& n) { vert(a, n); vert(b, n); vert(c2, n); }
    void quad(const V3& a, const V3& b, const V3& c2, const V3& d, const V3& n) { tri(a, b, c2, n); tri(a, c2, d, n); }
    static void basis(const V3& d, V3& u, V3& w) {
        u = d.cross(std::fabs(d.z) < 0.9f ? V3(0, 0, 1) : V3(1, 0, 0)).normalized();
        w = d.cross(u).normalized();
    }
    void cylinder(const V3& a, const V3& b, float r, int segs = 12) {
        V3 d = (b - a).normalized(), u, w;
        basis(d, u, w);
        for (int i = 0; i < segs; ++i) {
            float t0 = 2 * kPi * float(i) / float(segs), t1 = 2 * kPi * float(i + 1) / float(segs);
            V3 n0 = u * std::cos(t0) + w * std::sin(t0), n1 = u * std::cos(t1) + w * std::sin(t1);
            vert(a + n0 * r, n0); vert(b + n0 * r, n0); vert(b + n1 * r, n1);
            vert(a + n0 * r, n0); vert(b + n1 * r, n1); vert(a + n1 * r, n1);
        }
    }
    void cone(const V3& base, const V3& tip, float r, int segs = 16) {
        V3 d = (tip - base).normalized(), u, w;
        basis(d, u, w);
        for (int i = 0; i < segs; ++i) {
            float t0 = 2 * kPi * float(i) / float(segs), t1 = 2 * kPi * float(i + 1) / float(segs);
            V3 e0 = u * std::cos(t0) + w * std::sin(t0), e1 = u * std::cos(t1) + w * std::sin(t1);
            V3 n0 = (e0 + d * 0.4f).normalized(), n1 = (e1 + d * 0.4f).normalized();
            vert(base + e0 * r, n0); vert(tip, (n0 + n1).normalized()); vert(base + e1 * r, n1);
            tri(base, base + e1 * r, base + e0 * r, d * -1.0f);
        }
    }
    void box(const V3& c0, const V3& h) {
        V3 p[8];
        for (int k = 0; k < 8; ++k) p[k] = c0 + V3((k & 1) ? h.x : -h.x, (k & 2) ? h.y : -h.y, (k & 4) ? h.z : -h.z);
        quad(p[0], p[2], p[3], p[1], V3(0, 0, -1)); quad(p[4], p[5], p[7], p[6], V3(0, 0, 1));
        quad(p[0], p[1], p[5], p[4], V3(0, -1, 0)); quad(p[2], p[6], p[7], p[3], V3(0, 1, 0));
        quad(p[0], p[4], p[6], p[2], V3(-1, 0, 0)); quad(p[1], p[3], p[7], p[5], V3(1, 0, 0));
    }
    // A ring about z (in the floor's plane): radius R, tube r.
    void ring(const V3& c0, float R, float r, int segs = 72, int sides = 8) {
        for (int i = 0; i < segs; ++i)
            for (int j = 0; j < sides; ++j) {
                auto at = [&](int ii, int jj, V3& n) {
                    float a = 2 * kPi * float(ii) / float(segs), b = 2 * kPi * float(jj) / float(sides);
                    V3 out(std::cos(a), std::sin(a), 0);
                    n = out * std::cos(b) + V3(0, 0, 1) * std::sin(b);
                    return c0 + out * R + n * r;
                };
                V3 n00, n10, n11, n01;
                V3 p00 = at(i, j, n00), p10 = at(i + 1, j, n10), p11 = at(i + 1, j + 1, n11), p01 = at(i, j + 1, n01);
                vert(p00, n00); vert(p10, n10); vert(p11, n11);
                vert(p00, n00); vert(p11, n11); vert(p01, n01);
            }
    }
};

// A screen point of a world point (false: behind the eye).
bool toScreen(const M4& vp, const V3& p, float w, float h, float& sx, float& sy) {
    float cx = vp.at(0, 0) * p.x + vp.at(0, 1) * p.y + vp.at(0, 2) * p.z + vp.at(0, 3);
    float cy = vp.at(1, 0) * p.x + vp.at(1, 1) * p.y + vp.at(1, 2) * p.z + vp.at(1, 3);
    float cw = vp.at(3, 0) * p.x + vp.at(3, 1) * p.y + vp.at(3, 2) * p.z + vp.at(3, 3);
    if (cw <= 1e-4f) return false;
    sx = (cx / cw + 1) * 0.5f * w;
    sy = (1 - cy / cw) * 0.5f * h;
    return true;
}

float segDistance(float px, float py, float ax, float ay, float bx, float by) {
    float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
    float t = l2 > 1e-6f ? std::clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0.0f, 1.0f) : 0.0f;
    return std::hypot(px - (ax + dx * t), py - (ay + dy * t));
}

// Whether (px, py) is inside the convex polygon (either winding).
bool insidePoly(float px, float py, const float* xs, const float* ys, int n) {
    int sign = 0;
    for (int i = 0; i < n; ++i) {
        int j = (i + 1) % n;
        float c = (xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i]);
        int s = c > 0 ? 1 : c < 0 ? -1 : 0;
        if (s && sign && s != sign) return false;
        if (s) sign = s;
    }
    return true;
}

M4 placed(const V3& pos, float yaw, float scale) {
    return M4::translation(pos) * M4::rotationZ(yaw) * M4::scaling(V3(scale, scale, scale));
}

void boxOf(const M4& m, const V3& mn0, const V3& mx0, V3& mn, V3& mx) {
    mn = V3(1e30f, 1e30f, 1e30f); mx = V3(-1e30f, -1e30f, -1e30f);
    for (int k = 0; k < 8; ++k) {
        V3 p = m.point(V3((k & 1) ? mx0.x : mn0.x, (k & 2) ? mx0.y : mn0.y, (k & 4) ? mx0.z : mn0.z));
        mn = V3(std::min(mn.x, p.x), std::min(mn.y, p.y), std::min(mn.z, p.z));
        mx = V3(std::max(mx.x, p.x), std::max(mx.y, p.y), std::max(mx.z, p.z));
    }
}

// The box of what an M2 draws (its batches' triangles, of the shown geosets): tighter than its extents, which count
// every geoset and the hidden ones too. False when it draws nothing.
bool drawnBox(const M2Model& m, const std::vector<uint16_t>& geosets, float scale, V3& mn, V3& mx) {
    mn = V3(1e30f, 1e30f, 1e30f); mx = V3(-1e30f, -1e30f, -1e30f);
    bool any = false;
    std::vector<uint8_t> seen(m.submeshes.size(), 0);
    for (const M2Batch& b : m.batches) {
        if (b.submesh >= m.submeshes.size() || seen[b.submesh]) continue;
        seen[b.submesh] = 1;
        const M2Submesh& sm = m.submeshes[b.submesh];
        if (!geosets.empty() && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t i = sm.indexStart; i < sm.indexStart + sm.indexCount && i < m.indices.size(); ++i) {
            uint32_t vi = m.indices[i];
            if (vi >= m.vertices.size()) continue;
            const float* q = m.vertices[vi].pos;
            mn = V3(std::min(mn.x, q[0]), std::min(mn.y, q[1]), std::min(mn.z, q[2]));
            mx = V3(std::max(mx.x, q[0]), std::max(mx.y, q[1]), std::max(mx.z, q[2]));
            any = true;
        }
    }
    mn = mn * scale; mx = mx * scale;
    return any;
}

// The box of what an M2 draws as posed by `mats` (its bones' matrices, model space): each shown vertex skinned on the CPU, as
// the shader does; a card frames the body as it stands, not its bind pose. False when it draws nothing.
bool posedBox(const M2Model& m, const std::vector<uint16_t>& geosets, const std::vector<M4>& mats, float scale, V3& mn, V3& mx) {
    mn = V3(1e30f, 1e30f, 1e30f); mx = V3(-1e30f, -1e30f, -1e30f);
    bool any = false;
    std::vector<uint8_t> seen(m.submeshes.size(), 0), done(m.vertices.size(), 0);
    for (const M2Batch& b : m.batches) {
        if (b.submesh >= m.submeshes.size() || seen[b.submesh]) continue;
        seen[b.submesh] = 1;
        const M2Submesh& sm = m.submeshes[b.submesh];
        if (!geosets.empty() && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t i = sm.indexStart; i < sm.indexStart + sm.indexCount && i < m.indices.size(); ++i) {
            uint32_t vi = m.indices[i];
            if (vi >= m.vertices.size() || done[vi]) continue;
            done[vi] = 1;
            const M2Vertex& v = m.vertices[vi];
            V3 rest(v.pos[0], v.pos[1], v.pos[2]), p;
            float total = 0;
            for (int k = 0; k < 4; ++k) {
                if (!v.weights[k] || v.bones[k] >= mats.size()) continue;
                float w = v.weights[k] / 255.0f;
                p = p + mats[v.bones[k]].point(rest) * w;
                total += w;
            }
            p = total > 0.01f ? p * (1.0f / total) : rest;
            mn = V3(std::min(mn.x, p.x), std::min(mn.y, p.y), std::min(mn.z, p.z));
            mx = V3(std::max(mx.x, p.x), std::max(mx.y, p.y), std::max(mx.z, p.z));
            any = true;
        }
    }
    mn = mn * scale; mx = mx * scale;
    return any;
}

bool isWmoFile(const std::string& path) {
    uint8_t magic[4] = {0, 0, 0, 0};
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    size_t got = std::fread(magic, 1, 4, f);
    std::fclose(f);
    return got == 4 && std::memcmp(magic, "REVM", 4) == 0;
}

} // namespace

// ------------------------------------------------------------------------------------------------ the camera

Scene::CameraInfo Scene::cameraInfo() const {
    CameraInfo c;
    c.eye = eye_; c.target = target_; c.fovY = fov_;
    V3 f = (target_ - eye_).normalized();
    V3 s = f.cross(V3(0, 0, 1)).normalized();
    c.up = s.cross(f);
    c.aspect = float(viewW_) / float(std::max(1, viewH_));
    c.view = M4::lookAt(eye_, target_, V3(0, 0, 1));
    c.proj = M4::perspective(fov_ * kPi / 180.0f, c.aspect, 0.2f, 4000.0f);
    return c;
}

bool Scene::viewCamera(const std::string& view, V3& eye, V3& target, float& fov) const {
    if (!location_) return false;
    auto it = locDesc_.views.find(view);
    if (it == locDesc_.views.end() || !it->second.hasCamera) return false;
    eye = it->second.eye; target = it->second.target; fov = it->second.fov;
    return true;
}

// ------------------------------------------------------------------------------------------------ the gizmo

void Scene::setGizmo(int mode, int kind, int id, int activeAxis) {
    gizmoMode_ = mode; gizmoKind_ = kind; gizmoId_ = id; gizmoAxis_ = activeAxis;
}

bool Scene::gizmoFrame(V3& pivot, float& length, int width, int height) const {
    if (gizmoMode_ == kGizmoOff) return false;
    if (gizmoKind_ == kPickProp) {
        auto it = editorProps_.find(gizmoId_);
        if (it == editorProps_.end()) return false;
        pivot = it->second.pos;
    } else if (gizmoKind_ == kPickActor) {
        auto it = actors_.find(gizmoId_);
        if (it == actors_.end() || !it->second.visible) return false;
        pivot = it->second.position;
    } else {
        return false;
    }
    V3 f = (target_ - eye_).normalized();
    float depth = std::max(0.3f, (pivot - eye_).dot(f));
    length = kGizmoShare * 2.0f * depth * std::tan(fov_ * kPi / 360.0f);
    return true;
}

void Scene::drawGizmo() {
    V3 p;
    float L;
    if (!gizmoFrame(p, L, viewW_, viewH_)) return;
    if (!gizmoProg_) {
        gizmoProg_ = gl::program(kGizmoVs, kGizmoFs);
        glGenVertexArrays(1, &gizmoVao_);
        glGenBuffers(1, &gizmoVbo_);
    }
    GizmoMesh m;
    auto colour = [&](int axis, float& grow) {
        const float* c = kAxisColour[axis];
        bool active = gizmoAxis_ == axis;
        grow = active ? 1.4f : 1.0f;
        float k = active ? 0.45f : 0.0f;
        m.c[0] = c[0] + (1.0f - c[0]) * k; m.c[1] = c[1] + (1.0f - c[1]) * k; m.c[2] = c[2] + (0.75f - c[2]) * k;
        m.c[3] = active ? 1.0f : gizmoAxis_ >= 0 ? 0.35f : 0.8f;
    };
    float g = 1;
    if (gizmoMode_ == kGizmoMove) {
        for (int a = 0; a < 3; ++a) {
            colour(a, g);
            V3 d = kAxisDir[a];
            m.cylinder(p + d * (0.10f * L), p + d * (0.76f * L), 0.028f * L * g);
            m.cone(p + d * (0.76f * L), p + d * (1.0f * L), 0.085f * L * g);
        }
        colour(kAxisFloor, g);
        m.box(p + V3(0.35f * L, 0.35f * L, 0), V3(0.13f * L * g, 0.13f * L * g, 0.006f * L));
        m.box(p, V3(0.05f * L, 0.05f * L, 0.05f * L));   // the pivot (it moves on the floor too)
    } else if (gizmoMode_ == kGizmoRotate) {
        colour(kAxisYaw, g);
        m.ring(p, 0.9f * L, 0.036f * L * g);
        // the grips: where a finger takes the ring, at its four quarters
        for (int i = 0; i < 4; ++i) {
            float a = kPi * 0.5f * float(i) + kPi * 0.25f;
            m.box(p + V3(std::cos(a), std::sin(a), 0) * (0.9f * L), V3(0.055f * L * g, 0.055f * L * g, 0.055f * L * g));
        }
    } else if (gizmoMode_ == kGizmoScale) {
        for (int a = 0; a < 3; ++a) {
            colour(a, g);
            V3 d = kAxisDir[a];
            m.cylinder(p + d * (0.12f * L), p + d * (0.82f * L), 0.024f * L * g);
            float b = 0.065f * L * g;
            m.box(p + d * (0.88f * L), V3(b, b, b));
        }
        colour(kAxisUniform, g);
        float b = 0.09f * L * g;
        m.box(p, V3(b, b, b));
    }
    if (m.v.empty()) return;

    M4 vp = proj_ * view_;
    // over everything: the scene's depth is let go (it is the frame's last drawing), the handles still hide each other
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

int Scene::gizmoHit(float sx, float sy, int width, int height, float slopPx) const {
    V3 p;
    float L;
    if (width <= 0 || height <= 0 || !gizmoFrame(p, L, width, height)) return kAxisNone;
    float w = float(width), h = float(height);
    M4 vp = M4::perspective(fov_ * kPi / 180.0f, w / h, 0.2f, 4000.0f) * M4::lookAt(eye_, target_, V3(0, 0, 1));
    int best = kAxisNone;
    float bestD = slopPx;
    auto take = [&](int axis, float d) { if (d <= bestD) { bestD = d; best = axis; } };
    auto segment = [&](int axis, const V3& a, const V3& b) {
        float ax, ay, bx, by;
        if (toScreen(vp, a, w, h, ax, ay) && toScreen(vp, b, w, h, bx, by)) take(axis, segDistance(sx, sy, ax, ay, bx, by));
    };
    // a box handle: its centre, less how big it is on the screen
    auto knob = [&](int axis, const V3& c, float half) {
        float cx, cy, ex, ey;
        if (!toScreen(vp, c, w, h, cx, cy)) return;
        float r = toScreen(vp, c + V3(0, 0, half), w, h, ex, ey) ? std::hypot(ex - cx, ey - cy) : 0.0f;
        take(axis, std::max(0.0f, std::hypot(sx - cx, sy - cy) - r));
    };
    if (gizmoMode_ == kGizmoMove) {
        for (int a = 0; a < 3; ++a) segment(a, p + kAxisDir[a] * (0.10f * L), p + kAxisDir[a] * L);
        // the floor square (inside it counts as on it), and the pivot
        float xs[4], ys[4];
        bool all = true;
        V3 c = p + V3(0.35f * L, 0.35f * L, 0);
        const float q = 0.13f * L;
        V3 corners[4] = {c + V3(-q, -q, 0), c + V3(q, -q, 0), c + V3(q, q, 0), c + V3(-q, q, 0)};
        for (int i = 0; i < 4; ++i) all = all && toScreen(vp, corners[i], w, h, xs[i], ys[i]);
        if (all) {
            if (insidePoly(sx, sy, xs, ys, 4)) take(kAxisFloor, 0);
            else for (int i = 0; i < 4; ++i) take(kAxisFloor, segDistance(sx, sy, xs[i], ys[i], xs[(i + 1) % 4], ys[(i + 1) % 4]));
        }
        knob(kAxisFloor, p, 0.10f * L);
    } else if (gizmoMode_ == kGizmoRotate) {
        const int n = 72;
        float px = 0, py = 0;
        bool have = false;
        for (int i = 0; i <= n; ++i) {
            float a = 2 * kPi * float(i) / float(n), x, y;
            if (!toScreen(vp, p + V3(std::cos(a), std::sin(a), 0) * (0.9f * L), w, h, x, y)) { have = false; continue; }
            if (have) take(kAxisYaw, segDistance(sx, sy, px, py, x, y));
            px = x; py = y; have = true;
        }
    } else if (gizmoMode_ == kGizmoScale) {
        for (int a = 0; a < 3; ++a) {
            segment(a, p + kAxisDir[a] * (0.12f * L), p + kAxisDir[a] * (0.82f * L));
            knob(a, p + kAxisDir[a] * (0.88f * L), 0.06f * L);
        }
        knob(kAxisUniform, p, 0.09f * L);
        // the uniform box wins over the axes where both are under the finger (it sits where they start)
        float cx, cy, ex, ey;
        if (toScreen(vp, p, w, h, cx, cy) && toScreen(vp, p + V3(0, 0, 0.09f * L), w, h, ex, ey) &&
            std::hypot(sx - cx, sy - cy) <= std::hypot(ex - cx, ey - cy) + slopPx * 0.5f)
            best = kAxisUniform;
    }
    return best;
}

// ------------------------------------------------------------------------------------------------ the ghost

bool Scene::ghostProp(uint32_t fileId, V3 pos, float yaw, float scale) {
    ghostClear();
    std::string path = files_(fileId);
    if (path.empty()) { modelErrors_[fileId] = "file " + std::to_string(fileId) + " isn't downloaded"; return false; }
    if (scale <= 0) scale = 1;
    if (isWmoFile(path)) {
        auto w = std::make_unique<PlacedWmo>();
        std::string err;
        if (!placeWmo(fileId, placed(pos, yaw, scale), scale, {}, {}, {}, {}, *w, err)) { modelErrors_[fileId] = err; return false; }
        ghost_.wmo = std::move(w);
    } else {
        ghost_.mesh = model(fileId);
        if (!ghost_.mesh) return false;
    }
    ghost_.on = true;
    ghost_.fileId = fileId;
    ghost_.pos0 = ghost_.pos = pos;
    ghost_.yaw0 = ghost_.yaw = yaw;
    ghost_.scale = scale;
    return true;
}

void Scene::ghostActor(int id) {
    ghostClear();
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    it->second.ghost = true;
    ghost_.on = true;
    ghost_.actor = id;
    ghost_.pos0 = ghost_.pos = it->second.position;
    ghost_.yaw0 = ghost_.yaw = it->second.facing;
}

void Scene::ghostMove(V3 pos, float yaw) {
    ghost_.pos = pos;
    ghost_.yaw = yaw;
    if (ghost_.actor) {
        auto it = actors_.find(ghost_.actor);
        if (it != actors_.end()) { it->second.position = pos; it->second.facing = yaw; }
    }
}

void Scene::freeWmoGl(PlacedWmo& w) {
    if (!glReady_) return;
    for (StaticGroup& g : w.groups) {
        if (g.vao) glDeleteVertexArrays(1, &g.vao);
        if (g.vbo) glDeleteBuffers(1, &g.vbo);
        if (g.ibo) glDeleteBuffers(1, &g.ibo);
        g.vao = g.vbo = g.ibo = 0;
    }
}

void Scene::ghostClear() {
    if (ghost_.wmo) freeWmoGl(*ghost_.wmo);
    // a ghost actor stays one (left out of drawing and picking) until its owner takes it off the stage
    ghost_ = Ghost();
}

M4 Scene::ghostDelta() const {
    return M4::translation(ghost_.pos) * M4::rotationZ(ghost_.yaw - ghost_.yaw0) * M4::translation(ghost_.pos0 * -1.0f);
}

bool Scene::ghostBounds(V3& mn, V3& mx) const {
    if (!ghost_.on) return false;
    if (ghost_.mesh) { boxOf(placed(ghost_.pos, ghost_.yaw, ghost_.scale), ghost_.mesh->model->extentMin, ghost_.mesh->model->extentMax, mn, mx); return true; }
    if (ghost_.wmo) { boxOf(ghostDelta(), ghost_.wmo->boundsMin, ghost_.wmo->boundsMax, mn, mx); return true; }
    V3 a, b;
    if (ghost_.actor && actorBounds(ghost_.actor, a, b)) { boxOf(placed(ghost_.pos, ghost_.yaw, 1), a, b, mn, mx); return true; }
    return false;
}

// Twice: first its depth alone, then its colour where it is the nearest of itself, blended at kGhostAlpha (so where
// its parts overlap it is blended once, not twice). Only its solid parts: what blends of itself (glows, glass) is left out.
void Scene::drawGhost(const M4& viewProj, uint32_t now) {
    if (!ghost_.on) return;
    const Actor* actor = nullptr;
    if (ghost_.actor) {
        auto it = actors_.find(ghost_.actor);
        if (it == actors_.end()) return;
        actor = &it->second;
    }
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    for (int pass = 0; pass < 2; ++pass) {
        if (pass == 0) {
            glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
            glDisable(GL_BLEND);
        } else {
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
            glEnable(GL_BLEND);
            glBlendColor(0, 0, 0, kGhostAlpha);
            glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
        }
        std::copy(kGhostTint, kGhostTint + 4, ghostTint_);
        if (ghost_.mesh) {
            litSetup(m2Prog_, viewProj);
            ghostUniform(m2Prog_, true);
            drawModel(*ghost_.mesh, placed(ghost_.pos, ghost_.yaw, ghost_.scale), nullptr, nullptr, ghost_.mesh->boneTex, -1, 0,
                      now, ghostTint_, false, kThumbTex);
        } else if (ghost_.wmo && locProg_) {
            M4 vp = viewProj * ghostDelta();
            Frustum f(vp);
            staticSetup(locProg_, vp, ghost_.wmo->intAmbient);
            ghostUniform(locProg_, true);
            drawWmoGroups(*ghost_.wmo, false, f, 0);
            std::vector<const Instance*> list;
            for (const Instance& in : ghost_.wmo->doodads) list.push_back(&in);
            staticSetup(instProg_, vp, ghost_.wmo->intAmbient);
            ghostUniform(instProg_, true);
            drawInstances(list, false, nullptr, 0, 0);
        } else if (actor) {
            Actor& a = actors_[ghost_.actor];
            ghostUniform(m2Prog_, true);
            lightActor(a.position, false);
            drawActor(a, now, false);
            lightActor(V3(), true);
        }
    }
    for (float& t : ghostTint_) t = 1;
    ghostUniform(m2Prog_, false);
    ghostUniform(locProg_, false);
    ghostUniform(instProg_, false);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDisable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_TRUE);
}

// ------------------------------------------------------------------------------------------------ thumbnails

bool Scene::thumbnail(uint32_t propFile, const ThumbLook* look, int size, std::vector<uint8_t>& out, std::string& error) {
    size = std::clamp(size, 16, 512);
    return thumbnail(propFile, look, nullptr, size, size, out, error);
}

bool Scene::thumbnail(uint32_t propFile, const ThumbLook* look, const ThumbFrame* frame, int w, int h, std::vector<uint8_t>& out,
                      std::string& error) {
    if (!glReady_) { error = "the 3D view isn't ready"; return false; }
    w = std::clamp(w, 16, 512);
    h = std::clamp(h, 16, 512);
    if (!look) frame = nullptr;   // a prop is framed whole
    const int SW = w * 2, SH = h * 2;   // drawn at twice the size, then each 2 x 2 averaged: smooth edges
    // what the caches hold now: whatever is loaded only for the thumbnail is let go after it
    std::set<uint32_t> modelsBefore;
    for (const auto& [k, m] : models_) modelsBefore.insert(k);
    std::set<uint64_t> texBefore;
    for (const auto& [k, t] : textures_) texBefore.insert(k);

    // the thing, alone
    ModelGl* mesh = nullptr;
    std::unique_ptr<PlacedWmo> wmo;
    int actor = 0;
    V3 mn, mx;
    bool ok = true;
    if (look) {
        actor = addActor(look->model, look->textures, look->geo, look->scale > 0 ? look->scale : 1);
        if (actor <= 0) {
            actor = 0; ok = false;
            error = modelError(look->model);
            if (error.empty()) error = "model " + std::to_string(look->model) + " couldn't be read";
        } else {
            Actor& a = actors_[actor];
            a.ghost = true;   // never picked nor drawn by the frame (it is gone before the next)
            dress(actor, look->geo, look->layers, look->extras);
            if (!look->morph.empty() || look->bodyK > 0) setBody(actor, look->morph, look->bodyK, look->bodyFold);
            // and the HD figure (hdscene.cpp), drawn instead of the model
            if (look->hd.on) { setHdBody(actor, look->hdRace, look->hdSex, look->hd); hdCheck(a); }
            refreshSkin(a);
            if (a.body.dirty) refreshBody(a);
            a.position = V3(); a.facing = 0;
            animate(a, animClock_);
            for (Skinned& e : a.skinned) {
                e.mats.resize(std::max<size_t>(1, e.boneMap.size()));
                for (size_t i = 0; i < e.boneMap.size(); ++i)
                    e.mats[i] = e.boneMap[i] >= 0 && size_t(e.boneMap[i]) < a.mats.size() ? a.mats[size_t(e.boneMap[i])] : M4();
                glGenTextures(1, &e.boneTex);
                glBindTexture(GL_TEXTURE_2D, e.boneTex);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, GLsizei(e.mats.size()), 0, GL_RGBA, GL_FLOAT, e.mats.data());
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            }
            if (!(frame && posedBox(*a.mesh->model, a.geosets, a.mats, a.scale, mn, mx)) &&
                !drawnBox(*a.mesh->model, a.geosets, a.scale, mn, mx)) actorBounds(actor, mn, mx);
        }
    } else {
        std::string path = files_(propFile);
        if (path.empty()) {
            ok = false; error = "file " + std::to_string(propFile) + " isn't downloaded";
        } else if (isWmoFile(path)) {
            wmo = std::make_unique<PlacedWmo>();
            if (!placeWmo(propFile, M4(), 1, {}, {}, {}, {}, *wmo, error)) { ok = false; freeWmoGl(*wmo); wmo.reset(); }
            else { mn = wmo->boundsMin; mx = wmo->boundsMax; }
        } else {
            mesh = model(propFile);
            if (!mesh) { ok = false; error = modelError(propFile); }
            else if (!drawnBox(*mesh->model, {}, 1, mn, mx)) { mn = mesh->model->extentMin; mx = mesh->model->extentMax; }
        }
    }
    if (ok && !(mx.x >= mn.x && mx.y >= mn.y && mx.z >= mn.z)) { ok = false; error = "it has nothing to draw"; }

    if (ok) {
        // the live scene's camera, light and GL state, put back after
        V3 eye0 = eye_, target0 = target_;
        float fov0 = fov_;
        M4 view0 = view_, proj0 = proj_;
        Light light0 = light_;
        GLint fbo0 = 0, vp0[4] = {0, 0, 1, 1};
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo0);
        glGetIntegerv(GL_VIEWPORT, vp0);

        unsigned fbo = 0, colour = 0, depth = 0;
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glGenRenderbuffers(1, &colour);
        glBindRenderbuffer(GL_RENDERBUFFER, colour);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, SW, SH);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colour);
        glGenRenderbuffers(1, &depth);
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SW, SH);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { ok = false; error = "the off-screen picture couldn't be made"; }

        if (ok) {
            // three quarters in front (models face +x), a little from above; near enough that its box fills the square
            bool person = actor != 0;
            float az = frame ? frame->az : -0.62f, el = frame ? frame->el : person ? 0.10f : 0.36f;
            const float aspect = float(w) / float(h);
            V3 dir(std::cos(el) * std::cos(az), std::cos(el) * std::sin(az), std::sin(el));
            // the band that is framed: the whole box, or (a card) a slice of the body's height
            V3 bmn = mn, bmx = mx;
            if (frame) {
                float H = mx.z - mn.z;
                bmn.z = mn.z + frame->lo * H;
                bmx.z = mn.z + frame->hi * H;
            }
            V3 c = (bmn + bmx) * 0.5f;
            V3 f = dir * -1.0f, s = f.cross(V3(0, 0, 1)).normalized(), u = s.cross(f);
            fov_ = 30;
            float t = std::tan(fov_ * kPi / 360.0f) * 0.86f;
            bool fitWidth = !frame || frame->fitWidth;
            // a box's near corners reach its whole height; a body's front doesn't (a card's band is framed on its middle):
            // a card counts a third of the depth toward the camera
            const float nearShare = frame ? 0.35f : 1.0f;
            float D = 0.05f, radius = (mx - mn).length() * 0.5f;
            for (int k = 0; k < 8; ++k) {
                V3 rel = V3((k & 1) ? bmx.x : bmn.x, (k & 2) ? bmx.y : bmn.y, (k & 4) ? bmx.z : bmn.z) - c;
                float across = fitWidth ? std::fabs(rel.dot(s)) / (t * aspect) : 0.0f;
                D = std::max(D, rel.dot(dir) * nearShare + std::max(across, std::fabs(rel.dot(u)) / t));
            }
            eye_ = c + dir * D;
            target_ = c;
            view_ = M4::lookAt(eye_, target_, V3(0, 0, 1));
            proj_ = M4::perspective(fov_ * kPi / 180.0f, aspect, std::max(0.01f, (D - radius) * 0.5f), D + radius * 2 + 10);
            M4 vp = proj_ * view_;
            // soft light from the front left and above, a cool fill; a quiet slate gradient behind
            Light l;
            const float top[3] = {0.34f, 0.35f, 0.40f}, hor[3] = {0.22f, 0.22f, 0.26f}, below[3] = {0.12f, 0.12f, 0.14f};
            V3 sun = (f * 0.55f + s * 0.45f + V3(0, 0, -0.75f)).normalized();
            const float sc[3] = {0.80f, 0.77f, 0.72f}, amb[3] = {0.60f, 0.61f, 0.66f};
            std::copy(top, top + 3, l.skyTop); std::copy(hor, hor + 3, l.skyHorizon); std::copy(below, below + 3, l.skyBelow);
            l.sunDir[0] = sun.x; l.sunDir[1] = sun.y; l.sunDir[2] = sun.z;
            std::copy(sc, sc + 3, l.sunColor); std::copy(amb, amb + 3, l.ambient); std::copy(hor, hor + 3, l.fogColor);
            l.fogStart = 1e5f; l.fogEnd = 2e5f;
            light_ = l;

            glViewport(0, 0, SW, SH);
            const float white[4] = {1, 1, 1, 1};
            // drawn once on the slate (or, clear, on black and again on white: what differs is the backdrop showing through)
            auto drawOn = [&](float bg, std::vector<uint8_t>& px) {
                if (frame && frame->clear) glClearColor(bg, bg, bg, 1);
                else glClearColor(hor[0], hor[1], hor[2], 1);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glDisable(GL_CULL_FACE);
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(GL_LEQUAL);
                glDepthMask(GL_TRUE);
                glDisable(GL_BLEND);
                if (!(frame && frame->clear)) drawSky();
                for (int pass = 0; pass < 2; ++pass) {
                    bool transparent = pass == 1;
                    if (mesh) {
                        litSetup(m2Prog_, vp);
                        drawModel(*mesh, M4(), nullptr, nullptr, mesh->boneTex, -1, 0, animClock_, white, transparent, kThumbTex);
                    } else if (wmo && locProg_) {
                        Frustum fr(vp);
                        staticSetup(locProg_, vp, wmo->intAmbient);
                        drawWmoGroups(*wmo, transparent, fr, 0);
                        std::vector<const Instance*> list;
                        for (const Instance& in : wmo->doodads) list.push_back(&in);
                        staticSetup(instProg_, vp, wmo->intAmbient);
                        drawInstances(list, transparent, nullptr, 0, 0);
                    } else if (actor) {
                        litSetup(m2Prog_, vp);
                        drawActor(actors_[actor], animClock_, transparent);
                    }
                }
                px.resize(size_t(SW) * SH * 4);
                glPixelStorei(GL_PACK_ALIGNMENT, 4);
                glReadPixels(0, 0, SW, SH, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
            };
            std::vector<uint8_t> onBlack, onWhite;
            drawOn(0.0f, onBlack);
            const bool clear = frame && frame->clear;
            if (clear) drawOn(1.0f, onWhite);
            out.assign(size_t(w) * h * 4, 0);
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    // GL's rows are bottom first; each picture pixel is 2 x 2 drawn ones
                    int sy = SH - 2 - 2 * y;
                    float rgb[3] = {0, 0, 0}, cover = 0;
                    for (int dy = 0; dy < 2; ++dy)
                        for (int dx = 0; dx < 2; ++dx) {
                            size_t at = (size_t(sy + dy) * SW + size_t(2 * x + dx)) * 4;
                            if (!clear) { for (int ch = 0; ch < 3; ++ch) rgb[ch] += onBlack[at + size_t(ch)]; cover += 255; continue; }
                            // over black: colour x alpha; over white: that plus (1 - alpha)
                            float a = 0;
                            for (int ch = 0; ch < 3; ++ch) a += 255.0f - (float(onWhite[at + size_t(ch)]) - float(onBlack[at + size_t(ch)]));
                            a = std::clamp(a / 3.0f, 0.0f, 255.0f);
                            for (int ch = 0; ch < 3; ++ch) rgb[ch] += onBlack[at + size_t(ch)];   // premultiplied
                            cover += a;
                        }
                    uint8_t* o = &out[(size_t(y) * w + size_t(x)) * 4];
                    if (!clear) {
                        for (int ch = 0; ch < 3; ++ch) o[ch] = uint8_t((rgb[ch] + 2) / 4);
                        o[3] = 255;
                    } else if (cover > 0.5f) {
                        // straight colour: the premultiplied sum over the coverage's sum
                        for (int ch = 0; ch < 3; ++ch) o[ch] = uint8_t(std::clamp(rgb[ch] * 255.0f / cover, 0.0f, 255.0f) + 0.5f);
                        o[3] = uint8_t(std::clamp(cover / 4.0f, 0.0f, 255.0f) + 0.5f);
                    }
                }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, GLuint(fbo0));
        glDeleteFramebuffers(1, &fbo);
        glDeleteRenderbuffers(1, &colour);
        glDeleteRenderbuffers(1, &depth);
        glViewport(vp0[0], vp0[1], vp0[2], vp0[3]);
        eye_ = eye0; target_ = target0; fov_ = fov0; view_ = view0; proj_ = proj0; light_ = light0;
        glDisable(GL_BLEND);
        glDepthMask(GL_TRUE);
    }

    // let go of the thing and of what was loaded only for it
    if (actor) removeActor(actor);
    if (wmo) freeWmoGl(*wmo);
    wmo.reset();
    for (auto it = models_.begin(); it != models_.end();) {
        if (modelsBefore.count(it->first)) { ++it; continue; }
        ModelGl* m = it->second.get();
        auto iv = instVaos_.find(m);
        if (iv != instVaos_.end()) { if (iv->second) glDeleteVertexArrays(1, &iv->second); instVaos_.erase(iv); }
        if (m->vao) glDeleteVertexArrays(1, &m->vao);
        if (m->vbo) glDeleteBuffers(1, &m->vbo);
        if (m->ibo) glDeleteBuffers(1, &m->ibo);
        if (m->boneTex) glDeleteTextures(1, &m->boneTex);
        it = models_.erase(it);
    }
    for (auto it = textures_.begin(); it != textures_.end();) {
        if (texBefore.count(it->first)) { ++it; continue; }
        if (it->second.id && it->second.id != white_) glDeleteTextures(1, &it->second.id);
        it = textures_.erase(it);
    }
    return ok;
}

} // namespace wc
