// Toy interaction in the scene (orificedraw.h).
#include <GLES3/gl3.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "bodypart.h"
#include "glutil.h"
#include "orificedraw.h"
#include "scene.h"

namespace wc {

namespace {

float smoothstep(float a, float b, float x) { float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f); return t * t * (3 - 2 * t); }

// The inverse of a general 4x4 (column-major); false when singular.
bool invert4(const M4& in, M4& out) {
    const float* m = in.m;
    float inv[16];
    inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
    inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
    inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
    inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
    inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
    inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
    inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
    inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
    inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
    inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
    inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
    inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
    inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
    inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
    inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
    inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
    float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
    if (std::fabs(det) < 1e-20f) return false;
    det = 1.0f / det;
    for (int i = 0; i < 16; ++i) out.m[i] = inv[i] * det;
    return true;
}

V3 projectPoint(const M4& vp, const V3& p, int w, int h, bool* ok) {
    const float x = vp.at(0, 0) * p.x + vp.at(0, 1) * p.y + vp.at(0, 2) * p.z + vp.at(0, 3);
    const float y = vp.at(1, 0) * p.x + vp.at(1, 1) * p.y + vp.at(1, 2) * p.z + vp.at(1, 3);
    const float ww = vp.at(3, 0) * p.x + vp.at(3, 1) * p.y + vp.at(3, 2) * p.z + vp.at(3, 3);
    if (ok) *ok = ww > 1e-6f;
    const float iw = std::fabs(ww) > 1e-9f ? 1.0f / ww : 0.0f;
    return V3((x * iw * 0.5f + 0.5f) * float(w), (1.0f - (y * iw * 0.5f + 0.5f)) * float(h), 0);
}

V3 dirOf(const M4& m, const V3& d) { return m.point(d) - m.point(V3()); }

const char* kSleeveVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aN;
layout(location = 2) in float aT;
uniform mat4 uVP;
uniform mat4 uModel;
out vec3 vN;
out vec3 vW;
out float vT;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    gl_Position = uVP * w;
    vW = w.xyz;
    vN = mat3(uModel) * aN;
    vT = aT;
}
)";
const char* kSleeveFs = R"(#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vW;
in float vT;
uniform vec3 uEye;
uniform float uWet;
uniform float uFlush;
uniform vec3 uSkin;
out vec4 frag;
void main() {
    vec3 n = normalize(vN);
    vec3 v = normalize(uEye - vW);
    if (dot(n, v) < 0.0) n = -n;
    vec3 shallow = mix(vec3(0.90, 0.50, 0.48), uSkin * vec3(1.0, 0.62, 0.58), 0.25);
    vec3 deep = vec3(0.66, 0.20, 0.22);
    vec3 base = mix(shallow, deep, smoothstep(0.0, 0.8, vT));
    base = mix(base, base * vec3(1.12, 0.82, 0.84), uFlush);
    vec3 L = normalize(v * 0.75 + vec3(-0.25, 0.2, 0.65));
    float diff = dot(n, L) * 0.5 + 0.5;
    float occl = 1.0 - 0.50 * smoothstep(0.05, 0.9, vT);
    vec3 h = normalize(v + L);
    float sp = pow(max(dot(n, h), 0.0), mix(14.0, 70.0, uWet)) * (0.18 + 0.95 * uWet) * (1.0 - 0.6 * vT);
    float rimLight = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.12 * uWet;
    vec3 col = base * (0.52 + 0.55 * diff) * occl + vec3(sp + rimLight);
    frag = vec4(col, 1.0);
}
)";
const char* kRimVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aA;
layout(location = 2) in float aW;
uniform mat4 uVP;
uniform mat4 uModel;
out float vA;
out float vW;
void main() {
    gl_Position = uVP * (uModel * vec4(aPos, 1.0));
    vA = aA;
    vW = aW;
}
)";
const char* kRimFs = R"(#version 300 es
precision mediump float;
in float vA;
in float vW;
uniform float uWet;
uniform float uFlush;
uniform vec3 uSkin;
uniform float uDisc;
out vec4 frag;
void main() {
    if (uDisc > 0.5) {
        vec3 dc = mix(vec3(0.34, 0.06, 0.09), vec3(0.78, 0.34, 0.36), smoothstep(0.35, 1.0, vW));
        frag = vec4(dc + vec3(0.1 * uWet * smoothstep(0.6, 1.0, vW)), vA);
        return;
    }
    vec3 c = mix(uSkin * vec3(1.0, 0.70, 0.66), vec3(0.84, 0.36, 0.40), 0.35 + 0.5 * uFlush);
    float gloss = smoothstep(0.55, 0.0, abs(vW - 0.35)) * uWet * 0.45;
    frag = vec4(c + vec3(gloss), vA * (0.34 + 0.40 * uFlush) * (0.5 + 0.5 * (1.0 - vW)));
}
)";

} // namespace

// ------------------------------------------------------------------------------------------------------------------------------- API
void Scene::setToy(int actor, uint64_t guid, bool doubleEnded, const ToyInput& in) {
    ToyState& t = toy_;
    if (in.mode <= 0) { clearToy(); return; }
    if (t.actor != actor || t.guid != guid) { t.frameKey = 0; t.frameOk = false; t.shapeGuid = 0; t.dyn = orifice::Dyn(); t.started = false; t.placed = false; }
    t.active = true;
    t.actor = actor; t.guid = guid; t.doubleEnded = doubleEnded;
    t.in = in;
    auto fin = [](float v) { return std::isfinite(v) ? v : 0.0f; };
    t.in.fx = fin(in.fx); t.in.fy = fin(in.fy); t.in.depth = fin(in.depth);
    t.in.vibration = std::clamp(fin(in.vibration), 0.0f, 1.0f);
    t.in.roll = fin(in.roll);
    t.in.hidden = in.hidden;
}

void Scene::clearToy() {
    toy_.active = false;
    toy_.placed = false;
    toy_.in = ToyInput();
}

bool Scene::toyInfo(ToyInfo& out) const {
    const ToyState& t = toy_;
    out = ToyInfo();
    if (!t.active || !t.frameOk || !t.haveCam) return false;
    auto it = actors_.find(t.actor);
    if (it == actors_.end() || t.frameBone < 0 || size_t(t.frameBone) >= it->second.mats.size()) return false;
    const Actor& a = it->second;
    const M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    const M4 bw = w * a.mats[size_t(t.frameBone)];
    const V3 c = bw.point(t.frame.centre);
    const V3 ax = dirOf(bw, t.frame.axis).normalized();
    const int W = std::max(t.in.width, 1), H = std::max(t.in.height, 1);
    bool ok1 = true, ok2 = true;
    const V3 s0 = projectPoint(t.vp, c, W, H, &ok1);
    const float step = 0.05f * t.frame.u * a.scale;
    const V3 s1 = projectPoint(t.vp, c + ax * step, W, H, &ok2);
    if (!ok1 || !ok2) return false;
    V3 d = s1 - s0;
    d.z = 0;
    const float len = d.length();
    out.ready = true;
    out.hd = t.frameHd;
    out.entryX = s0.x; out.entryY = s0.y;
    if (len > 1e-4f) { out.axisX = d.x / len; out.axisY = d.y / len; }
    // pixels per Sims metre along the way in: the step is 0.05 Sims metres of model units, times the actor's scale
    out.pxPerMetre = len / 0.05f;
    out.insertLength = t.shape.ok ? t.shape.length : 0.0f;
    out.radius = t.frame.u > 0 ? t.dyn.radius / t.frame.u : 0.0f;
    out.wet = t.dyn.wet;
    out.depth = t.frame.u > 0 ? t.dyn.depth / t.frame.u : 0.0f;
    if (t.placed) { const V3 tp = projectPoint(t.vp, t.tipWorld, W, H, nullptr); out.tipX = tp.x; out.tipY = tp.y; }
    return true;
}

// -------------------------------------------------------------------------------------------------------------------- the HD region
namespace {

void buildRegion(ToyState& t, const HdFit& fit) {
    t.region.clear(); t.restPos.clear(); t.restN.clear(); t.restCalc.clear(); t.triStart.clear(); t.triList.clear(); t.runs.clear(); t.bodyTris.clear();
    const size_t nv = fit.verts.size();
    t.slot.assign(nv, -1);
    const size_t end = std::min<size_t>(fit.bodyVertexEnd, nv);
    for (size_t i = 0; i < end; ++i) {
        const M2Vertex& v = fit.verts[i];
        if (orifice::regionWeight(t.frame, V3(v.pos[0], v.pos[1], v.pos[2])) > 0) {
            t.slot[i] = int32_t(t.region.size());
            t.region.push_back(uint32_t(i));
            t.restPos.push_back(V3(v.pos[0], v.pos[1], v.pos[2]));
            t.restN.push_back(V3(v.normal[0], v.normal[1], v.normal[2]));
        }
    }
    // the body's triangles that touch the region
    std::vector<uint32_t> count(t.region.size() + 1, 0);
    const std::vector<uint32_t>& idx = fit.bodyIndices.empty() ? fit.indices : fit.bodyIndices;
    for (size_t k = 0; k + 2 < idx.size(); k += 3) {
        bool touch = false;
        for (int q = 0; q < 3; ++q) if (idx[k + size_t(q)] < nv && t.slot[idx[k + size_t(q)]] >= 0) touch = true;
        if (!touch) continue;
        t.bodyTris.push_back(idx[k]); t.bodyTris.push_back(idx[k + 1]); t.bodyTris.push_back(idx[k + 2]);
        for (int q = 0; q < 3; ++q) { const int32_t s = t.slot[idx[k + size_t(q)]]; if (s >= 0) ++count[size_t(s) + 1]; }
    }
    t.triStart.assign(t.region.size() + 1, 0);
    for (size_t i = 0; i < t.region.size(); ++i) t.triStart[i + 1] = t.triStart[i] + count[i + 1];
    t.triList.assign(t.triStart.back(), 0);
    std::vector<uint32_t> fill(t.region.size(), 0);
    for (size_t k = 0; k + 2 < t.bodyTris.size(); k += 3)
        for (int q = 0; q < 3; ++q) {
            const int32_t s = t.slot[t.bodyTris[k + size_t(q)]];
            if (s >= 0) t.triList[t.triStart[size_t(s)] + fill[size_t(s)]++] = uint32_t(k / 3);
        }
    // vertices at rest: the normals the triangles give, to tell a change of shape from the authored smoothing
    t.restCalc.assign(t.region.size(), V3());
    auto restOf = [&](uint32_t vi) { const int32_t s = t.slot[vi]; return s >= 0 ? t.restPos[size_t(s)] : V3(fit.verts[vi].pos[0], fit.verts[vi].pos[1], fit.verts[vi].pos[2]); };
    for (size_t i = 0; i < t.region.size(); ++i) {
        V3 n;
        for (uint32_t q = t.triStart[i]; q < t.triStart[i + 1]; ++q) {
            const size_t k = size_t(t.triList[q]) * 3;
            const V3 a = restOf(t.bodyTris[k]), b = restOf(t.bodyTris[k + 1]), c = restOf(t.bodyTris[k + 2]);
            n = n + (b - a).cross(c - a);
        }
        t.restCalc[i] = n.length() > 1e-14f ? n.normalized() : V3();
    }
    // runs of consecutive indices for the sub-uploads
    std::vector<uint32_t> sorted = t.region;
    std::sort(sorted.begin(), sorted.end());
    for (size_t i = 0; i < sorted.size();) {
        size_t j = i;
        while (j + 1 < sorted.size() && sorted[j + 1] == sorted[j] + 1) ++j;
        t.runs.push_back({sorted[i], uint32_t(j - i + 1)});
        i = j + 1;
    }
    t.stage = fit.verts;
    t.work.assign(t.region.size(), V3());
}

} // namespace

// Where the toy is: the frame found for this body (the HD figure's modelled slit, else the game body's measured entry), the
// toy measured, the dynamics stepped, the toy's matrix and (HD) the region deformed.
void Scene::stepToy(uint32_t clock) {
    ToyState& t = toy_;
    if (!t.active) {
        // (a toy put away: the body's vertices back as they were)
        if (t.gpuDeformed) { t.dyn = orifice::Dyn(); t.started = false; }
        return;
    }
    auto it = actors_.find(t.actor);
    if (it == actors_.end() || !it->second.mesh || !it->second.mesh->model) return;
    Actor& a = it->second;
    const M2Model& m = *a.mesh->model;
    const int pelvis = m.findWowBone("Pelvis");
    if (pelvis < 0 || size_t(pelvis) >= a.mats.size()) return;
    const float u = std::max(m.bones[size_t(pelvis)].pivot.z, 0.05f) / kPartReferenceHeight;
    // the frame, found again when the figure changes
    uint64_t key = a.hd.active ? (a.hd.key | 1) : 2;
    key ^= uint64_t(reinterpret_cast<uintptr_t>(&m)) * 31;
    if (key != t.frameKey || !t.frameOk) {
        t.frameKey = key;
        t.frameOk = false; t.frameHd = false;
        if (a.hd.active && !a.hd.fit.verts.empty()) {
            const V3 piv = pelvis < int(a.hd.fit.rigPivots.size()) ? a.hd.fit.rigPivots[size_t(pelvis)] : m.bones[size_t(pelvis)].pivot;
            const float uh = std::max(piv.z, 0.05f) / kPartReferenceHeight;
            orifice::Frame f;
            if (orifice::findHdVulva(a.hd.fit.verts.data(), std::min<size_t>(a.hd.fit.bodyVertexEnd, a.hd.fit.verts.size()), pelvis, uh, 0.0f, f)) {
                t.frame = f; t.frameBone = pelvis; t.frameHd = true; t.frameOk = true;
                buildRegion(t, a.hd.fit);
                t.uploadedFor = 0;
                std::fprintf(stderr, "toy: HD slit found: centre (%.4f %.4f %.4f) axis (%.2f %.2f %.2f) slit %.4f back %.4f front, region %zu vertices, %zu triangles, u %.3f\n",
                             f.centre.x, f.centre.y, f.centre.z, f.axis.x, f.axis.y, f.axis.z, f.slitBack, f.slitFront, t.region.size(), t.bodyTris.size() / 3, uh);
            }
        }
        if (!t.frameOk) {
            if (!a.parts.entries.measured) a.parts.entries = measureEntries(m, a.geosets, true);
            const EntryAnchor& e = a.parts.entries.at[kEntryVaginal];
            if (e.ok && e.bone >= 0 && size_t(e.bone) < a.mats.size()) {
                t.frame = orifice::frameFromAnchor(e.point, e.inward, e.radius, u);
                t.frameBone = e.bone; t.frameOk = true;
                t.region.clear(); t.slot.clear();
            }
        }
    }
    if (!t.frameOk) return;
    // the toy measured once per prop
    if (t.shapeGuid != t.guid) {
        t.shapeGuid = t.guid;
        t.shape = orifice::ToyShape();
        if (const PropData* pd = propDraw_.data(t.guid)) {
            std::vector<float> all;
            for (const PropMeshData& mm : pd->meshes) all.insert(all.end(), mm.pos.begin(), mm.pos.end());
            t.shape = orifice::measureToy(all.data(), all.size() / 3, t.doubleEnded);
        }
    }
    if (!t.shape.ok || t.frameBone < 0 || size_t(t.frameBone) >= a.mats.size()) return;
    float dt = t.started ? float(int32_t(clock - t.lastMs)) / 1000.0f : 0.0f;
    if (!(dt > 0)) dt = t.started ? 0.0f : 1.0f / 60.0f;
    t.lastMs = clock; t.started = true;
    const float uf = t.frame.u;
    // the toy's place in the frame's bone space: free in front of the body, or on the axis
    const M4 world = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    const M4 bw = world * a.mats[size_t(t.frameBone)];
    const M4 inv = affineInverse(bw);
    V3 tipB, axisB;
    float depthModel = 0;
    bool present = false;
    if (t.in.mode == 2) {
        depthModel = t.in.depth * uf;
        tipB = t.frame.centre + t.frame.axis * depthModel;
        axisB = t.frame.axis;
        present = depthModel > -0.2f * uf;
    } else if (t.haveCam) {
        const int W = std::max(t.in.width, 1), H = std::max(t.in.height, 1);
        const float nx = t.in.fx / float(W) * 2.0f - 1.0f, ny = 1.0f - t.in.fy / float(H) * 2.0f;
        auto unproject = [&](float z) {
            const float* q = t.invVp.m;
            const float x = q[0] * nx + q[4] * ny + q[8] * z + q[12], y = q[1] * nx + q[5] * ny + q[9] * z + q[13];
            const float zz = q[2] * nx + q[6] * ny + q[10] * z + q[14], ww = q[3] * nx + q[7] * ny + q[11] * z + q[15];
            return V3(x / ww, y / ww, zz / ww);
        };
        const V3 o = unproject(-1.0f), fa = unproject(1.0f);
        const V3 dir = (fa - o).normalized();
        const V3 ew = bw.point(t.frame.centre);
        const V3 toEntry = (ew - t.eye);
        const V3 nview = toEntry.normalized();
        const float hover = 0.05f * uf * a.scale;
        const V3 pp = ew - nview * hover;
        const float den = dir.dot(nview);
        const float tt = std::fabs(den) > 1e-5f ? (pp - o).dot(nview) / den : 0.0f;
        const V3 tipW = o + dir * tt;
        const V3 axisW = dirOf(bw, t.frame.axis).normalized();
        const V3 toward = (ew - tipW);
        const float dist = toward.length();
        const float un = uf * a.scale;
        const float near = 1.0f - smoothstep(0.035f * un, 0.12f * un, dist);
        V3 dirW = dist > 1e-5f ? toward * (1.0f / dist) : axisW;
        dirW = (dirW * (1.0f - near) + axisW * near).normalized();
        tipB = inv.point(tipW);
        axisB = dirOf(inv, dirW).normalized();
        depthModel = -0.05f * uf;
        present = false;
    } else {
        return;
    }
    orifice::stepDyn(t.dyn, t.frame, &t.shape, present && t.in.mode == 2, depthModel, t.in.vibration, dt);
    // the toy's matrix: its tip on tipB, its axis along axisB, turned `roll` about it, scaled to model units
    Quat q = quatMul(quatAxisAngle(axisB, t.in.roll), quatFromTo(t.shape.axis.normalized(), axisB));
    t.propModel = bw * M4::translation(tipB) * M4::rotation(q) * M4::scaling(V3(uf, uf, uf)) * M4::translation(t.shape.tip * -1.0f);
    t.tipWorld = bw.point(tipB);
    t.placed = true;
}

// Called by drawHd once the HD vertex buffer is bound: the region's deformed vertices uploaded (or the rest ones put back).
void Scene::toyBody(Actor& a) {
    ToyState& t = toy_;
    if (!t.frameHd || t.region.empty() || a.hd.vbo == 0 || !a.hd.active) return;
    if (t.actor < 0 || actors_.find(t.actor) == actors_.end() || &actors_.find(t.actor)->second != &a) return;
    if (t.uploadedVbo != a.hd.vbo || t.uploadedFor != a.hd.key) { t.uploadedVbo = a.hd.vbo; t.uploadedFor = a.hd.key; t.gpuDeformed = false; t.lastRadius = -1; }
    const bool want = t.active && (t.dyn.radius > 0 || t.dyn.tremor > 0);
    if (!want && !t.gpuDeformed) return;
    const bool unchanged = want && t.dyn.radius == t.lastRadius && t.dyn.drag == t.lastDrag && t.dyn.tremor == t.lastTremor && t.dyn.tremor == 0 && t.gpuDeformed;
    if (unchanged) return;
    const orifice::Dyn restDyn;
    const orifice::Dyn& d = want ? t.dyn : restDyn;
    const orifice::ToyShape* shape = want ? &t.shape : nullptr;
    // positions
    for (size_t i = 0; i < t.region.size(); ++i) t.work[i] = orifice::deformPoint(t.frame, shape, d, t.restPos[i], nullptr);
    // normals: the change of the triangles' normals added to the authored ones
    for (size_t i = 0; i < t.region.size(); ++i) {
        V3 n;
        for (uint32_t q = t.triStart[i]; q < t.triStart[i + 1]; ++q) {
            const size_t k = size_t(t.triList[q]) * 3;
            V3 p[3];
            for (int c = 0; c < 3; ++c) {
                const uint32_t vi = t.bodyTris[k + size_t(c)];
                const int32_t s = t.slot[vi];
                p[c] = s >= 0 ? t.work[size_t(s)] : V3(a.hd.fit.verts[vi].pos[0], a.hd.fit.verts[vi].pos[1], a.hd.fit.verts[vi].pos[2]);
            }
            n = n + (p[1] - p[0]).cross(p[2] - p[0]);
        }
        const V3 calc = n.length() > 1e-14f ? n.normalized() : t.restCalc[i];
        const V3 nn = (t.restN[i] + (calc - t.restCalc[i])).normalized();
        M2Vertex& v = t.stage[t.region[i]];
        v.pos[0] = t.work[i].x; v.pos[1] = t.work[i].y; v.pos[2] = t.work[i].z;
        v.normal[0] = nn.x; v.normal[1] = nn.y; v.normal[2] = nn.z;
    }
    glBindBuffer(GL_ARRAY_BUFFER, a.hd.vbo);
    for (const auto& r : t.runs)
        glBufferSubData(GL_ARRAY_BUFFER, GLintptr(size_t(r.first) * sizeof(M2Vertex)), GLsizeiptr(size_t(r.second) * sizeof(M2Vertex)), &t.stage[r.first]);
    t.gpuDeformed = want;
    t.lastRadius = t.dyn.radius; t.lastDrag = t.dyn.drag; t.lastTremor = t.dyn.tremor;
}

void Scene::drawRimMesh(ToyState& t, const M4& viewProj, const M4& bw, float flush, const float* skin, bool disc) {
    if (!t.rVao) { glGenVertexArrays(1, &t.rVao); glGenBuffers(1, &t.rVbo); glGenBuffers(1, &t.rIbo);
        glBindVertexArray(t.rVao); glBindBuffer(GL_ARRAY_BUFFER, t.rVbo);
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 20, nullptr);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 1, GL_FLOAT, GL_FALSE, 20, reinterpret_cast<void*>(12));
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 20, reinterpret_cast<void*>(16));
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, t.rIbo); }
    glBindVertexArray(t.rVao);
    glBindBuffer(GL_ARRAY_BUFFER, t.rVbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(t.rim.v.size() * 4), t.rim.v.data(), GL_STREAM_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, t.rIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(t.rim.idx.size() * 4), t.rim.idx.data(), GL_STREAM_DRAW);
    glUseProgram(t.rimProg);
    glUniformMatrix4fv(gl::loc(t.rimProg, "uVP"), 1, GL_FALSE, viewProj.m);
    glUniformMatrix4fv(gl::loc(t.rimProg, "uModel"), 1, GL_FALSE, bw.m);
    glUniform1f(gl::loc(t.rimProg, "uWet"), t.dyn.wet);
    glUniform1f(gl::loc(t.rimProg, "uFlush"), flush);
    glUniform1f(gl::loc(t.rimProg, "uDisc"), disc ? 1.0f : 0.0f);
    glUniform3fv(gl::loc(t.rimProg, "uSkin"), 1, skin);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glEnable(GL_POLYGON_OFFSET_FILL);
    glPolygonOffset(-2.0f, -2.0f);
    const GLboolean cull = glIsEnabled(GL_CULL_FACE);
    glDisable(GL_CULL_FACE);
    glDrawElements(GL_TRIANGLES, GLsizei(t.rim.idx.size()), GL_UNSIGNED_INT, nullptr);
    if (cull) glEnable(GL_CULL_FACE);
    glDisable(GL_POLYGON_OFFSET_FILL);
    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
}

void Scene::drawToy(const M4& viewProj) {
    ToyState& t = toy_;
    t.vp = viewProj;
    t.eye = eye_;
    t.haveCam = invert4(viewProj, t.invVp);
    if (!t.active || !t.placed) return;
    auto it = actors_.find(t.actor);
    if (it == actors_.end() || !it->second.visible) return;
    Actor& a = it->second;
    if (t.frameBone < 0 || size_t(t.frameBone) >= a.mats.size()) return;
    const M4 world = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    const M4 bw = world * a.mats[size_t(t.frameBone)];
    const float flush = std::clamp(t.dyn.radius / (0.022f * t.frame.u) * 0.7f + t.dyn.wet * 0.4f, 0.0f, 1.0f);
    const float skin[3] = {a.hd.skin[0], a.hd.skin[1], a.hd.skin[2]};
    if (t.frameHd && t.dyn.radius > 0) {
        orifice::buildSleeve(t.frame, &t.shape, t.dyn, t.sleeve);
        orifice::buildRim(t.frame, t.dyn, t.rim);
        if (!t.sleeveProg) t.sleeveProg = gl::program(kSleeveVs, kSleeveFs);
        if (!t.rimProg) t.rimProg = gl::program(kRimVs, kRimFs);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(GL_TRUE);
        glDisable(GL_BLEND);
        const GLboolean cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE);
        if (t.sleeveProg && !t.sleeve.empty() && !std::getenv("WC_NOSLEEVE")) {
            if (!t.sVao) { glGenVertexArrays(1, &t.sVao); glGenBuffers(1, &t.sVbo); glGenBuffers(1, &t.sIbo);
                glBindVertexArray(t.sVao); glBindBuffer(GL_ARRAY_BUFFER, t.sVbo);
                glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 28, nullptr);
                glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 28, reinterpret_cast<void*>(12));
                glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 28, reinterpret_cast<void*>(24));
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, t.sIbo); }
            glBindVertexArray(t.sVao);
            glBindBuffer(GL_ARRAY_BUFFER, t.sVbo);
            glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(t.sleeve.v.size() * 4), t.sleeve.v.data(), GL_STREAM_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, t.sIbo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(t.sleeve.idx.size() * 4), t.sleeve.idx.data(), GL_STREAM_DRAW);
            glUseProgram(t.sleeveProg);
            glUniformMatrix4fv(gl::loc(t.sleeveProg, "uVP"), 1, GL_FALSE, viewProj.m);
            glUniformMatrix4fv(gl::loc(t.sleeveProg, "uModel"), 1, GL_FALSE, bw.m);
            glUniform3f(gl::loc(t.sleeveProg, "uEye"), eye_.x, eye_.y, eye_.z);
            glUniform1f(gl::loc(t.sleeveProg, "uWet"), t.dyn.wet);
            glUniform1f(gl::loc(t.sleeveProg, "uFlush"), flush);
            glUniform3fv(gl::loc(t.sleeveProg, "uSkin"), 1, skin);
            glDrawElements(GL_TRIANGLES, GLsizei(t.sleeve.idx.size()), GL_UNSIGNED_INT, nullptr);
        }
        if (cull) glEnable(GL_CULL_FACE);
    }
    // the game's body has nothing to open: a dark pink oval on the skin where the toy goes in (its stand-in)
    if (!t.frameHd && t.dyn.radius > 0) {
        orifice::buildDisc(t.frame, t.dyn, t.rim);
        if (!t.rimProg) t.rimProg = gl::program(kRimVs, kRimFs);
        if (t.rimProg && !t.rim.empty()) drawRimMesh(t, viewProj, bw, flush, skin, true);
    }
    // the toy
    PropInstance inst;
    inst.guid = t.guid;
    inst.model = t.propModel;
    if (!t.in.hidden) propDraw_.draw({inst}, viewProj, eye_);
    // the rim's overlay over the toy's base (it is a thin film on the lips)
    if (t.frameHd && t.dyn.radius > 0 && t.rimProg && !std::getenv("WC_NORIM")) {
        orifice::buildRim(t.frame, t.dyn, t.rim);
        if (!t.rim.empty()) drawRimMesh(t, viewProj, bw, flush, skin, false);
    }
    glBindVertexArray(0);
}


// ------------------------------------------------------------------------------------------------------------- the reactions' layer
void Scene::setToyPose(int id, const float* v, int n) {
    if (!v || n < 12) return;
    toyrig::State& st = toyRig_[id];
    st.on = true;
    toyrig::Pose& p = st.want;
    p.breath = v[0]; p.breathHz = v[1]; p.flutter = v[2]; p.arch = v[3]; p.hipTilt = v[4]; p.thighPart = v[5];
    p.kneeFlex = v[6]; p.headBack = v[7]; p.shoulder = v[8]; p.tremor = v[9]; p.reachL = v[10]; p.reachR = v[11];
}

void Scene::clearToyPose(int id) { toyRig_.erase(id); }

void Scene::layToyRig(Actor& a, uint32_t now) {
    if (std::getenv("WC_TOYDBG")) std::fprintf(stderr, "layToyRig: rigs %zu mesh %d ghost %d\n", toyRig_.size(), a.mesh && a.mesh->model ? 1 : 0, a.ghost ? 1 : 0);
    if (toyRig_.empty() || !a.mesh || !a.mesh->model || a.ghost) return;
    int id = -1;
    for (auto& [k, v] : actors_) if (&v == &a) { id = k; break; }
    auto it = toyRig_.find(id);
    if (it == toyRig_.end()) return;
    const M2Model& m = *a.mesh->model;
    V3 entry;
    if (toy_.frameOk && toy_.actor == id && toy_.frameBone >= 0 && size_t(toy_.frameBone) < a.mats.size()) entry = a.mats[size_t(toy_.frameBone)].point(toy_.frame.centre);
    else { const int p = m.findWowBone("Pelvis"); if (p >= 0 && size_t(p) < a.mats.size()) entry = a.mats[size_t(p)].point(m.bones[size_t(p)].pivot + V3(0.05f, 0, -0.1f)); }
    toyrig::apply(it->second, m, a.mats, now, entry);
}

} // namespace wc
