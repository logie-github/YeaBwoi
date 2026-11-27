// The Scene's side of the stockings (stockings.h): the shell built from the body as drawn, uploaded, drawn skinned by the body's own bones in
// the transparent pass (after the skin, under what the gear draws over it by depth).
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#include <GLES3/gl3.h>

#include "bodypick.h"
#include "glutil.h"
#include "scene.h"

namespace wc {
using gl::loc;
using gl::m2VertexLayout;

namespace {
uint64_t bodySig(const std::vector<M2Vertex>& v, const StockSpec& s) {
    uint64_t h = 1469598103934665603ull ^ v.size();
    auto mixf = [&](float f) { uint32_t u; std::memcpy(&u, &f, 4); h = (h ^ u) * 1099511628211ull; };
    const size_t step = std::max<size_t>(1, v.size() / 4000);
    for (size_t i = 0; i < v.size(); i += step) { mixf(v[i].pos[0]); mixf(v[i].pos[1]); mixf(v[i].pos[2]); }
    mixf(float(s.style)); mixf(float(s.denier)); mixf(s.r); mixf(s.g); mixf(s.b); mixf(s.seam ? 1.f : 0.f); mixf(s.lace ? 1.f : 0.f); mixf(s.offset);
    return h | 1ull;
}
unsigned makeTex(const std::vector<uint8_t>& px, int size) {
    unsigned t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glGenerateMipmap(GL_TEXTURE_2D);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    return t;
}
} // namespace

int Scene::setStockings(int id, const StockSpec* spec) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return -1;
    StockGl& s = it->second.stock;
    if (!spec) { s.on = false; return 0; }
    if (!s.on || !(s.spec == *spec)) s.sig = 0;
    s.on = true; s.spec = *spec;
    return int(s.triangles);
}

size_t Scene::stockingTriangles(int id) const {
    auto it = actors_.find(id);
    return it == actors_.end() ? 0 : it->second.stock.triangles;
}

void Scene::dropStockGl(Actor& a) {
    StockGl& s = a.stock;
    if (glReady_) {
        if (s.vbo) glDeleteBuffers(1, &s.vbo);
        if (s.ibo) glDeleteBuffers(1, &s.ibo);
        if (s.vao) glDeleteVertexArrays(1, &s.vao);
        if (s.tex) glDeleteTextures(1, &s.tex);
        if (s.bandTex) glDeleteTextures(1, &s.bandTex);
    }
    s.vao = s.vbo = s.ibo = s.tex = s.bandTex = 0;
    s.count = 0; s.sig = 0; s.triangles = 0; s.fabricReady = false; s.pulling = s.moving = false;
}

void Scene::drawStockings(Actor& a, const M4& world) {
    StockGl& s = a.stock;
    if (!s.on || !a.mesh || !a.mesh->model) return;
    const M2Model& m = *a.mesh->model;
    // the body as drawn: an HD (Liard) body's own, else the shaped (sculpted) one, else the game's default model's. (No Eve.)
    const bool hd = a.hd.active && !a.hd.fit.verts.empty();
    const BodyGl& b = a.body;
    const bool own = !hd && !b.shaped.empty() && !b.indices.empty() && !b.submeshes.empty();
    static const std::vector<M2Submesh> kNoSubs;
    const std::vector<M2Vertex>& verts = hd ? a.hd.fit.verts : own ? b.shaped : m.vertices;   // (an HD body's triangles reference only its own vertices)
    const std::vector<uint32_t>& idx = hd ? a.hd.fit.bodyIndices : own ? b.indices : m.indices;
    const std::vector<M2Submesh>& subs = hd ? kNoSubs : own ? b.submeshes : m.submeshes;
    if (hd && idx.empty()) return;
    const uint64_t sig = bodySig(verts, s.spec);
    if (sig != s.sig) {
        dropStockGl(a);
        s.sig = sig;
        // (an HD body is in its rig's bind space: the pivots are the fit's, else the model's)
        auto bone = [&](const char* n) {
            int i = m.findWowBone(n);
            if (i < 0) return -1e9f;
            if (hd && size_t(i) < a.hd.fit.rigPivots.size()) return a.hd.fit.rigPivots[size_t(i)].z;
            return m.bones[size_t(i)].pivot.z;
        };
        float floorZ = 1e9f;
        for (const M2Vertex& v : verts) floorZ = std::min(floorZ, v.pos[2]);
        float pelvis = bone("Pelvis"), knee = bone("L_Calf"), ankle = bone("L_Ankle");
        if (ankle < -1e8f) ankle = bone("L_Foot");
        if (knee < -1e8f || pelvis < -1e8f) return;
        if (ankle < -1e8f) ankle = floorZ + 0.1f * (knee - floorZ);
        std::vector<uint8_t> regions = boneRegionsOf(m);
        if (!buildStockings(verts, idx, subs, a.geosets, regions, s.spec, floorZ, ankle, knee, pelvis, s.shell)) return;
        const StockShell& sh = s.shell;
        glGenVertexArrays(1, &s.vao);
        glBindVertexArray(s.vao);
        glGenBuffers(1, &s.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, s.vbo);
        glBufferData(GL_ARRAY_BUFFER, sh.verts.size() * sizeof(M2Vertex), sh.verts.data(), GL_STATIC_DRAW);
        glGenBuffers(1, &s.ibo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, s.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, sh.indices.size() * 4, sh.indices.data(), GL_STATIC_DRAW);
        m2VertexLayout();
        glBindVertexArray(0);
        s.tex = makeTex(stockingWeave(s.spec, 512), 512);
        s.bandTex = makeTex(stockingBand(s.spec, 256, s.spec.style == kStockTights || s.spec.style == kStockGarter), 256);
        s.bandStart = sh.bandStart; s.count = uint32_t(sh.indices.size()); s.triangles = sh.indices.size() / 3;
        std::vector<uint32_t> wt;
        stockWeld(sh, s.weld, s.rest, wt);
        s.wtris = wt;
        s.fabric.init(s.rest, wt, 12345u);
        s.fabricReady = true; s.pulling = false; s.moving = false;
    }
    if (!s.vao) return;
    stockFabricFrame(a);
    if (!s.count) return;
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uWorld"), 1, GL_FALSE, world.m);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, a.boneTex);
    glUniform1i(loc(p, "uBones"), 1);
    // the stretch thins the yarn: the weave's alpha falls with the tension while it is pulled
    float one[4] = {1, 1, 1, 1.0f - 0.45f * (s.fabricReady ? s.fabric.tension() : 0.0f)};
    glUniform4fv(loc(p, "uColor"), 1, one);
    glUniform1f(loc(p, "uAlphaRef"), 0.004f);
    glUniform1i(loc(p, "uOpaque"), 0);
    glUniform1i(loc(p, "uUnlit"), 0);
    glUniform1f(loc(p, "uSheen"), s.sheen);
    glUniform1f(loc(p, "uSide"), s.side);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glBindVertexArray(s.vao);
    glActiveTexture(GL_TEXTURE0);
    if (s.bandStart) { glBindTexture(GL_TEXTURE_2D, s.tex); glDrawElements(GL_TRIANGLES, GLsizei(s.bandStart), GL_UNSIGNED_INT, nullptr); }
    if (s.count > s.bandStart) { glBindTexture(GL_TEXTURE_2D, s.bandTex); glDrawElements(GL_TRIANGLES, GLsizei(s.count - s.bandStart), GL_UNSIGNED_INT, (void*)(size_t(s.bandStart) * 4)); }
    glBindVertexArray(0);
    glUniform1f(loc(p, "uSheen"), 0.0f);
    glUniform1f(loc(p, "uSide"), 0.0f);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
}

} // namespace wc

// ------------------------------------------------------------------------------------------------ the fabric's finger

namespace wc {

namespace {
uint64_t nowMs() {
    return uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
}
}

bool Scene::stockState(int id, float* out) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.stock.fabricReady) return false;
    const StockGl& s = it->second.stock;
    out[0] = s.fabric.tension(); out[1] = float(s.fabric.tornCount()); out[2] = float(s.fabric.deadTriangles()); out[3] = s.fabric.grabbed() ? 1.f : 0.f;
    return true;
}

bool Scene::stockUndo(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.stock.fabricReady) return false;
    bool ok = it->second.stock.fabric.undo(); it->second.stock.moving = true; return ok;
}

void Scene::stockRepair(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.stock.fabricReady) return;
    it->second.stock.fabric.repairAll(); it->second.stock.moving = true;
}

void Scene::stockRelease(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.stock.fabricReady) return;
    it->second.stock.fabric.release(); it->second.stock.pulling = false; it->second.stock.moving = true;
}

namespace {
// the camera's ray through the screen point, in the actor's model space (as Scene::pickBodyDetail)
void modelRay(const V3& eye, const V3& target, float fovDeg, const V3& pos, float facing, float scale, float sx, float sy, int w, int h, V3& o, V3& d, V3& fwd) {
    V3 f = (target - eye).normalized();
    V3 s = f.cross(V3(0, 0, 1)).normalized();
    V3 u = s.cross(f);
    float th = std::tan(fovDeg * 3.14159265f / 360.0f), aspect = float(w) / float(h);
    float nx = 2.0f * sx / float(w) - 1.0f, ny = 1.0f - 2.0f * sy / float(h);
    V3 dir = (f + s * (nx * th * aspect) + u * (ny * th)).normalized();
    float c = std::cos(-facing), sn = std::sin(-facing);
    auto unturn = [&](const V3& v) { return V3(v.x * c - v.y * sn, v.x * sn + v.y * c, v.z); };
    float k = scale > 1e-6f ? scale : 1.0f;
    o = unturn(eye - pos) * (1.0f / k); d = unturn(dir); fwd = unturn(f);
}
V3 skinPoint(const M2Vertex& v, const std::vector<M4>& mats) {
    V3 p(v.pos[0], v.pos[1], v.pos[2]);
    if (mats.empty()) return p;
    V3 out; float total = 0;
    for (int k = 0; k < 4; ++k) { if (!v.weights[k] || v.bones[k] >= mats.size()) continue; float w = v.weights[k] / 255.0f; out = out + mats[v.bones[k]].point(p) * w; total += w; }
    return total > 0 ? out * (1.0f / total) : p;
}
}

bool Scene::stockGrab(int id, float sx, float sy, int w, int h, bool dry) {
    auto it = actors_.find(id);
    if (it == actors_.end() || w <= 0 || h <= 0) return false;
    Actor& a = it->second;
    StockGl& s = a.stock;
    if (!s.on || !s.fabricReady || s.shell.verts.empty()) return false;
    V3 o, d, fwd;
    modelRay(eye_, target_, fov_, a.position, a.facing, a.scale, sx, sy, w, h, o, d, fwd);
    // the shell as posed: the live vertex nearest the camera among those within 3 cm of the ray (the stocking's near side, not the leg's far
    // one), else the closest to the ray within 4 cm
    const auto& alive = s.fabric.alive();
    int best = -1, nearest = -1; float bd = 0.04f, bt = 0, nearAlong = 1e9f, nearT = 0;
    for (size_t t = 0; t < alive.size(); ++t) {
        if (!alive[t]) continue;
        for (int k = 0; k < 3; ++k) {
            uint32_t sv = s.shell.indices[t * 3 + k];
            V3 p = skinPoint(s.shell.verts[sv], a.mats);
            float along = (p - o).dot(d);
            if (along <= 0) continue;
            float miss = (o + d * along - p).length();
            if (miss < bd) { bd = miss; best = int(sv); bt = along; }
            if (miss < 0.03f && along < nearAlong) { nearAlong = along; nearest = int(sv); nearT = along; }
        }
    }
    if (nearest >= 0) { best = nearest; bt = nearT; }
    if (best < 0) return false;
    if (dry) return true;
    // (the finger's depth stays where the pinch is: it moves in the view plane)
    s.pulling = true; s.moving = true; s.lastMs = nowMs();
    const V3 rest = s.rest[s.weld[size_t(best)]];
    if (!s.fabric.grab(rest, 0.05f, 0.05f)) { s.pulling = false; return false; }
    s.posBuf.assign({float(best), bt, 0, 0, 0, 0});   // the grabbed shell vertex, the depth along the ray
    return true;
}

// the fabric's look and give (Interact mode's Stockings panel): elasticity 0..1 and the strain it tears at, the sheen 0..1 (a Fresnel highlight, glossier the sheerer),
// the side light 0..1 (a raking light from the camera's side that shows the weave)
void Scene::stockLook(int id, float elasticity, float tearStrain, float sheen, float side) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    StockGl& s = it->second.stock;
    s.fabric.params.elasticity = std::min(1.0f, std::max(0.0f, elasticity));
    s.fabric.params.tearStrain = std::min(0.9f, std::max(0.1f, tearStrain));
    s.sheen = std::min(1.0f, std::max(0.0f, sheen)); s.side = std::min(1.0f, std::max(0.0f, side));
}

void Scene::stockDrag(int id, float sx, float sy, int w, int h) {
    auto it = actors_.find(id);
    if (it == actors_.end() || w <= 0 || h <= 0) return;
    Actor& a = it->second;
    StockGl& s = a.stock;
    if (!s.pulling || s.posBuf.size() < 2) return;
    const uint32_t sv = uint32_t(s.posBuf[0]);
    if (sv >= s.shell.verts.size()) return;
    V3 o, d, fwd;
    modelRay(eye_, target_, fov_, a.position, a.facing, a.scale, sx, sy, w, h, o, d, fwd);
    // the same depth along the camera's forward as the pinch's, on the new ray: where the finger is in the model's space
    const V3 posedRest = skinPoint(s.shell.verts[sv], a.mats);
    const float depth = (posedRest - o).dot(fwd);
    const float denom = d.dot(fwd);
    if (denom < 1e-4f) return;
    const V3 posedTo = o + d * (depth / denom);
    V3 delta = posedTo - posedRest;
    // posed -> bind space: undo the pinched vertex's heaviest bone's turn (the 3x3 of its matrix, a rotation and a uniform scale)
    int bone = s.shell.verts[sv].bones[0];
    if (size_t(bone) < a.mats.size()) {
        const M4& m = a.mats[size_t(bone)];
        V3 c0(m.at(0, 0), m.at(1, 0), m.at(2, 0)), c1(m.at(0, 1), m.at(1, 1), m.at(2, 1)), c2(m.at(0, 2), m.at(1, 2), m.at(2, 2));
        float k = std::max(c0.dot(c0), 1e-6f);
        delta = V3(c0.dot(delta), c1.dot(delta), c2.dot(delta)) * (1.0f / k);
    }
    // a pinch lifts the fabric off the skin as it is pulled (a tent), not only along it: out along the pinched vertex's normal by 0.8 of the way it was dragged
    {
        const M2Vertex& pv = s.shell.verts[sv];
        V3 n(pv.normal[0], pv.normal[1], pv.normal[2]);
        if (n.length() > 1e-4f) delta = delta + n.normalized() * (0.8f * delta.length());
    }
    s.fabric.drag(s.rest[s.weld[sv]] + delta);
    s.moving = true;
}

// stepped and uploaded from drawStockings: the fabric's positions into the shell's vertices, the live triangles into the index buffer
void Scene::stockFabricFrame(Actor& a) {
    StockGl& s = a.stock;
    if (!s.fabricReady || !s.vao || !(s.moving || s.pulling)) return;
    const uint64_t now = nowMs();
    float dt = s.lastMs ? float(now - s.lastMs) / 1000.0f : 1.0f / 60.0f;
    s.lastMs = now;
    s.fabric.params.iterations = stockIter_;
    // fixed substeps of at most 1/60
    int steps = std::max(1, std::min(4, int(std::ceil(dt * 60.0f))));
    for (int i = 0; i < steps; ++i) s.fabric.step(dt / float(steps));
    // (the leg's own place: the fabric is bound to the bind-pose rest; the skinning in the shader carries it with the leg)
    const std::vector<V3>& p = s.fabric.positions();
    std::vector<M2Vertex>& v = s.shell.verts;
    // (the solver's stretch is millimetres before the yarn gives: drawn 2x so the pinch reads as a tent, the springback and the tension unchanged)
    const float gain = 2.0f;
    for (size_t i = 0; i < v.size(); ++i) { const V3& r0 = s.rest[s.weld[i]]; const V3 q = r0 + (p[s.weld[i]] - r0) * gain; v[i].pos[0] = q.x; v[i].pos[1] = q.y; v[i].pos[2] = q.z; }
    glBindBuffer(GL_ARRAY_BUFFER, s.vbo);
    glBufferSubData(GL_ARRAY_BUFFER, 0, GLsizeiptr(v.size() * sizeof(M2Vertex)), v.data());
    // the live triangles only: the weave's, then the band's (the dead ones leave the index list: skin shows through)
    const auto& alive = s.fabric.alive();
    std::vector<uint32_t> live;
    live.reserve(s.shell.indices.size());
    uint32_t bandAt = 0;
    for (int pass = 0; pass < 2; ++pass) {
        if (pass == 1) bandAt = uint32_t(live.size());
        size_t from = pass == 0 ? 0 : s.shell.bandStart / 3, to = pass == 0 ? s.shell.bandStart / 3 : s.shell.indices.size() / 3;
        for (size_t t = from; t < to; ++t) if (alive[t]) { live.push_back(s.shell.indices[t * 3]); live.push_back(s.shell.indices[t * 3 + 1]); live.push_back(s.shell.indices[t * 3 + 2]); }
    }
    glBindVertexArray(s.vao);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, s.ibo);
    glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, GLsizeiptr(live.size() * 4), live.data());
    glBindVertexArray(0);
    s.count = uint32_t(live.size()); s.bandStart = bandAt;
    // at rest again (let go, springing back finished): stop stepping
    if (!s.pulling && s.fabric.tension() < 0.01f) {
        float worst = 0;
        for (size_t i = 0; i < p.size(); ++i) worst = std::max(worst, (p[i] - s.rest[i]).length());
        if (worst < 0.0003f) s.moving = false;
    }
}

} // namespace wc
