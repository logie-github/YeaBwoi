// The Scene's side of the Eve body (evebody.h): the set loaded, each actor's options, its refit when its look changes (the
// body cut, the skin matched and composed) and its drawing in the body pass, skinned on the actor's own bones.
#include <GLES3/gl3.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "blp.h"
#include "glutil.h"
#include "modelpart.h"
#include "scene.h"
#include "sculpt.h"

namespace wc {

std::shared_ptr<EveMesh> EveSet::mesh(const std::string& variant) const {
    for (const auto& m : meshes) if (m.first == variant) return m.second;
    return meshes.empty() ? nullptr : meshes.front().second;
}

namespace {

uint64_t mixKey(uint64_t k, uint64_t v) { return (k ^ v) * 1099511628211ull; }

} // namespace

bool Scene::loadEveSet(const std::string& dir, const std::string& fitJson, std::string& error) {
    auto set = std::make_shared<EveSet>();
    set->dir = dir;
    Bytes b;
    if (!readFile(dir + "/set.json", b)) { error = "The Eve set (" + dir + "/set.json) can't be read."; return false; }
    if (!parseEveSetIndex(std::string(b.begin(), b.end()), set->index, error)) { error = "The Eve set's index " + error + "."; return false; }
    if (!parseEveFitTable(fitJson, set->fits, error)) { error = "The Eve fit table " + error + "."; return false; }
    for (const auto& [variant, file] : set->index.meshes) {
        Bytes mb;
        auto mesh = std::make_shared<EveMesh>();
        std::string why;
        if (!readFile(dir + "/" + file, mb) || !parseEve(mb.data(), mb.size(), *mesh, why)) {
            error = "The Eve mesh " + file + (why.empty() ? " can't be read" : " " + why) + ".";
            return false;
        }
        set->meshes.push_back({variant, mesh});
    }
    set->generation = (eveSet_ ? eveSet_->generation : 0) + 1;
    eveSet_ = std::move(set);
    return true;
}

void Scene::setEveBody(int id, const EveOptions& options) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    it->second.eve.opt = options;
    it->second.eve.requested = options.on;
}

void Scene::clearEveBody(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    it->second.eve.requested = false;
}

bool Scene::eveStatus(int id, EveStatus& out) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    const EveDraw& e = it->second.eve;
    out = EveStatus();
    out.requested = e.requested;
    out.active = e.active;
    out.status = e.status;
    out.stats = e.body.stats;
    out.neck = e.neckRgb; out.ankle = e.ankleRgb; out.reference = e.reference;
    out.shade = e.shade; out.shadeDist = e.shadeDist; out.composeMs = e.composeMs;
    out.eveTriangles = e.body.indices.size() / 3;
    out.glMs = e.glMs; out.pending = e.pending != 0 || e.staged != nullptr; out.waitFrames = e.waitFrames;
    out.bodyTriangles = it->second.body.indices.size() / 3;
    return true;
}

void Scene::dropEveGl(Actor& a) {
    EveDraw& e = a.eve;
    if (glReady_) {
        if (e.tex) glDeleteTextures(1, &e.tex);
        if (e.vao) glDeleteVertexArrays(1, &e.vao);
        if (e.vbo) glDeleteBuffers(1, &e.vbo);
        if (e.ibo) glDeleteBuffers(1, &e.ibo);
        if (e.stTex) glDeleteTextures(1, &e.stTex);   // (and a fit still uploading)
        if (e.stVao) glDeleteVertexArrays(1, &e.stVao);
        if (e.stVbo) glDeleteBuffers(1, &e.stVbo);
        if (e.stIbo) glDeleteBuffers(1, &e.stIbo);
    }
    e.forgetGl();
}

// The key of what the body is fitted for: the set, the model, its shown geosets, its skin's layers, its shape and the options.
// A change sends a new fit (eveStart) and keeps what is drawn until it is ready (eveTake); the first fit of a body, or one
// whose shape is being made again, is sent from refreshBody (eveRefit), the body drawn whole until it comes.
void Scene::eveCheck(Actor& a) {
    EveDraw& e = a.eve;
    uint64_t k = 0;
    if (e.requested && eveSet_) {
        k = 1469598103934665603ull;
        k = mixKey(k, eveSet_->generation);
        k = mixKey(k, uint64_t(reinterpret_cast<uintptr_t>(a.mesh)));
        for (uint16_t g : a.geosets) k = mixKey(k, g);
        k = mixKey(k, a.geosets.size());
        for (const SkinLayer& l : a.layers) if (l.textureType == 1) { k = mixKey(k, l.fileId); k = mixKey(k, uint64_t(l.layer)); }
        auto t = a.textures.find(1);
        if (t != a.textures.end()) k = mixKey(k, t->second);
        k = mixKey(k, a.body.morph.size());
        for (size_t i = 0; i < a.body.morph.size(); i += 97) { uint32_t bits; std::memcpy(&bits, &a.body.morph[i], 4); k = mixKey(k, bits); }
        for (char c : e.opt.bottom) k = mixKey(k, uint8_t(c));
        uint32_t op; std::memcpy(&op, &e.opt.opacity, 4);
        k = mixKey(k, uint64_t(uint32_t(e.opt.tone)) << 32 | uint32_t(e.opt.overlaySlot));
        k = mixKey(k, uint64_t(uint32_t(e.opt.overlayShade)) << 32 | op);
        if (!e.opt.bones) k = mixKey(k, 0xB0E5);   // (Keep undead bones off; on keeps the key it always had)
        for (float f : a.parts.tint) { uint32_t bits; std::memcpy(&bits, &f, 4); k = mixKey(k, bits); }
        for (size_t i = 0; i < a.sculpt.size(); ++i) {   // the creator's sculpt (sculpt.h; Leg Length is the skeleton's, bodyrig.h)
            if (i == size_t(kSculptLegLength) || i == size_t(kSculptHeight)) continue;
            uint32_t bits; std::memcpy(&bits, &a.sculpt[i], 4); k = mixKey(k, bits);
        }
        if (k == 0) k = 1;
    }
    if (k != e.key) {
        e.key = k;
        e.sync = !eveAsync_ || a.ghost;   // (a picture's actor and the placing ghost: drawn at once)
        if (!k) eveOff(a);
        else if (!e.baseReady || a.body.shapeDirty) { a.body.shapeDirty = true; a.body.dirty = true; }   // (refreshBody: eveRefit)
        else eveStart(a, false);
    }
    if (e.pending) eveTake(a);
    if (e.staged) eveStage(a);
}

// A staged fit's GL objects deleted (a newer look, Eve off, the body made again).
void Scene::eveDropStage(Actor& a) {
    EveDraw& e = a.eve;
    if (glReady_) {
        if (e.stTex) glDeleteTextures(1, &e.stTex);
        if (e.stVao) glDeleteVertexArrays(1, &e.stVao);
        if (e.stVbo) glDeleteBuffers(1, &e.stVbo);
        if (e.stIbo) glDeleteBuffers(1, &e.stIbo);
    }
    e.stTex = e.stVao = e.stVbo = e.stIbo = 0;
    e.stStep = 0;
    e.staged.reset();
}

// A finished fit uploaded a little each frame (its mesh, then its texture's rows, level by level, about 640 KB a frame), then
// swapped in whole: no frame of the GL thread carries the fit, nor more than a slice of its upload.
void Scene::eveStage(Actor& a) {
    EveDraw& e = a.eve;
    if (!glReady_ || !e.staged) return;
    ++e.waitFrames;
    auto t0 = std::chrono::steady_clock::now();
    EveResult& r = *e.staged;
    constexpr size_t kBudget = 640 * 1024;
    size_t used = 0;
    // the levels: the texture, then its mips
    std::vector<std::pair<const std::vector<uint8_t>*, std::pair<int, int>>> levels;
    levels.push_back({&r.rgba, {r.w, r.h}});
    {
        int lw = r.w, lh = r.h;
        for (const auto& m : r.mips) { lw = std::max(1, lw / 2); lh = std::max(1, lh / 2); levels.push_back({&m, {lw, lh}}); }
    }
    if (e.stStep == 0) {
        glGenVertexArrays(1, &e.stVao);
        glBindVertexArray(e.stVao);
        glGenBuffers(1, &e.stVbo);
        glBindBuffer(GL_ARRAY_BUFFER, e.stVbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(r.body.verts.size() * sizeof(M2Vertex)), r.body.verts.data(), GL_STATIC_DRAW);
        glGenBuffers(1, &e.stIbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, e.stIbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(r.body.indices.size() * 4), r.body.indices.data(), GL_STATIC_DRAW);
        gl::m2VertexLayout();
        glBindVertexArray(0);
        used += r.body.verts.size() * sizeof(M2Vertex) + r.body.indices.size() * 4;
        glGenTextures(1, &e.stTex);
        glBindTexture(GL_TEXTURE_2D, e.stTex);
        glTexStorage2D(GL_TEXTURE_2D, GLsizei(levels.size()), GL_RGBA8, r.w, r.h);   // (every level at once, its pixels sent below)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, levels.size() > 1 ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        e.stStep = 1;
    }
    // the rows: numbered through the levels in order (stStep - 1 of them sent)
    glBindTexture(GL_TEXTURE_2D, e.stTex);
    int cursor = e.stStep - 1, base = 0;
    bool done = true;
    for (size_t l = 0; l < levels.size(); ++l) {
        const int lw = levels[l].second.first, lh = levels[l].second.second;
        if (cursor >= base + lh) { base += lh; continue; }
        const size_t rowBytes = size_t(lw) * 4;
        int y = cursor - base;
        int rows = int(std::max<size_t>(1, (kBudget > used ? kBudget - used : 0) / rowBytes));
        if (used >= kBudget) { done = false; break; }
        rows = std::min(rows, lh - y);
        glTexSubImage2D(GL_TEXTURE_2D, GLint(l), 0, y, lw, rows, GL_RGBA, GL_UNSIGNED_BYTE, levels[l].first->data() + size_t(y) * rowBytes);
        used += size_t(rows) * rowBytes;
        cursor += rows;
        base += lh;
        if (y + rows < lh) { done = false; break; }
    }
    e.stStep = cursor + 1;
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    e.stageMs = std::max(e.stageMs, ms);
    if (!done) return;
    std::shared_ptr<EveResult> keep = e.staged;
    eveSwap(a, *keep, false, true);
}

// Eve taken off: the body's own triangles back, the worker's job for it dropped.
void Scene::eveOff(Actor& a) {
    EveDraw& e = a.eve;
    if (e.pending && eveWorker_) eveWorker_->cancel(e.serial);
    e.pending = 0;
    eveDropStage(a);
    if (e.active && e.baseReady) {
        a.body.indices = e.baseIndices;
        a.body.submeshes = e.baseSubs;
        a.body.dirty = true;
        a.soft.dirty = true;
        a.cloth.dirty = true;
    }
    dropEveGl(a);
    e.active = false;
    e.body = EveBody();
    e.measure = EveMeasureMesh();
    e.rgba.clear();
    e.shade = -1;
    e.status = e.requested ? (eveSet_ ? "" : "no Eve set is loaded") : "off";
}

// In refreshBody, the body just built (its shape, the stand-in's cut) and not yet cut for Eve: kept as the base each fit cuts,
// and a fit sent. Until it comes the body is drawn whole.
void Scene::eveRefit(Actor& a) {
    EveDraw& e = a.eve;
    if (e.pending && eveWorker_) eveWorker_->cancel(e.serial);
    e.pending = 0;
    eveDropStage(a);
    dropEveGl(a);
    e.active = false;
    e.body = EveBody();
    e.measure = EveMeasureMesh();
    e.rgba.clear();
    e.shade = -1;
    e.baseReady = false;
    e.baseIndices.clear();
    e.baseSubs.clear();
    if (!e.requested) { e.status = "off"; return; }
    if (!eveSet_) { e.status = "no Eve set is loaded"; return; }
    if (!eveOffered(a)) return;
    BodyGl& b = a.body;
    const M2Model& m = *a.mesh->model;
    e.baseOwn = b.shaped.empty();
    if (e.baseOwn) { b.shaped = m.vertices; b.indices = m.indices; b.submeshes = m.submeshes; }
    e.baseIndices = b.indices;
    e.baseSubs = b.submeshes;
    e.baseReady = true;
    ++e.baseGen;
    e.sync = !eveAsync_ || a.ghost;
    eveStart(a, true);
}

// Whether the fit table offers Eve on this model (else `status` says why).
bool Scene::eveOffered(Actor& a) {
    EveDraw& e = a.eve;
    const EveRaceFit* fit = eveSet_->fits.forModel(a.mesh->model->fileId);
    if (!fit) { e.status = "this model has no row in the fit table"; return false; }
    if (!fit->enabled) { e.status = "not offered for " + fit->name + ": " + fit->reason; return false; }
    if (!eveSet_->mesh(e.opt.bottom)) { e.status = "the Eve set has no mesh"; return false; }
    return true;
}

// A fit sent: the actor's body and look taken as they are now (EveJob), computed on the worker (or at once when `sync`).
void Scene::eveStart(Actor& a, bool inRefresh) {
    EveDraw& e = a.eve;
    auto t0 = std::chrono::steady_clock::now();
    if (!eveOffered(a)) {
        if (e.active) eveOff(a);
        return;
    }
    static uint64_t serials = 0;
    if (!e.serial) e.serial = ++serials;
    const M2Model& m = *a.mesh->model;
    const BodyGl& b = a.body;
    auto job = std::make_unique<EveJob>();
    job->serial = e.serial;
    job->key = e.key;
    job->base = e.baseGen;
    job->model = &m;
    job->set = eveSet_;
    job->mesh = eveSet_->mesh(e.opt.bottom);
    job->fit = *eveSet_->fits.forModel(m.fileId);
    job->opt = e.opt;
    if (!e.baseOwn) job->verts = b.shaped;   // (else the model's own, which never change)
    job->indices = e.baseIndices;
    job->subs = e.baseSubs;
    job->geosets = a.geosets;
    job->sculpt = a.sculpt;
    if (sculptActive(a.sculpt.data(), a.sculpt.size())) job->rig = sculptRigOf(m);
    // the actor's base skin layer (the whole material, lowest): its file found here, read by the worker
    {
        const SkinLayer* base = nullptr;
        for (const SkinLayer& l : a.layers)
            if (l.textureType == 1 && l.fileId && l.layer != kBodyLayer && !isClothLayer(l) && (!base || l.layer < base->layer)) base = &l;
        uint32_t fid = base ? base->fileId : 0;
        if (!fid) { auto t = a.textures.find(1); if (t != a.textures.end()) fid = t->second; }
        job->skinPath = fid ? files_(fid) : "";
    }
    for (int i = 0; i < 3; ++i) job->tint[i] = a.parts.tint[i];
    job->mips = !e.sync;
    eveDropStage(a);   // (a fit of an older look still uploading)
    e.glMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (e.sync) {
        EveResult r = eveCompute(*job);
        eveSwap(a, r, inRefresh);
        return;
    }
    if (!eveWorker_) eveWorker_ = std::make_unique<EveWorker>();
    e.pending = e.key;
    e.waitFrames = 0;
    if (!e.active) e.status = "fitting";
    eveWorker_->submit(std::move(job));
}

// A finished fit, if there is one for this actor's look now (an older look's is dropped).
void Scene::eveTake(Actor& a) {
    EveDraw& e = a.eve;
    ++e.waitFrames;
    if (!eveWorker_) { e.pending = 0; return; }
    std::unique_ptr<EveResult> r = eveWorker_->take(e.serial);
    if (!r || r->key != e.key || r->base != e.baseGen) return;
    e.pending = 0;
    if (!r->ok || e.sync) { eveSwap(a, *r, false); return; }
    e.staged = std::shared_ptr<EveResult>(std::move(r));   // uploaded over the next frames (eveStage), then swapped in
    e.stStep = 0;
    e.stageMs = 0;
}

// The fit put in place in one frame: Eve's mesh and skin, the body's triangles with her cut (uploaded by refreshBody and drawEve).
void Scene::eveSwap(Actor& a, EveResult& r, bool inRefresh, bool staged) {
    auto t0 = std::chrono::steady_clock::now();
    EveDraw& e = a.eve;
    BodyGl& b = a.body;
    const unsigned st[4] = {e.stTex, e.stVao, e.stVbo, e.stIbo};
    if (staged) { e.stTex = e.stVao = e.stVbo = e.stIbo = 0; e.stStep = 0; }   // (kept from dropEveGl: they become hers)
    dropEveGl(a);
    if (staged) { e.tex = st[0]; e.vao = st[1]; e.vbo = st[2]; e.ibo = st[3]; }   // (uploaded already: eveStage)
    if (!r.ok) {
        e.status = r.status;
        if (e.active) { b.indices = e.baseIndices; b.submeshes = e.baseSubs; if (!inRefresh) b.dirty = true; }
        e.active = false;
        e.body = EveBody();
        e.measure = EveMeasureMesh();
        e.rgba.clear();
        a.soft.dirty = true;
        a.cloth.dirty = true;
        return;
    }
    e.body = std::move(r.body);
    e.measure = std::move(r.measure);
    e.rgba = std::move(r.rgba);
    e.w = r.w; e.h = r.h;
    e.neckRgb = r.neckRgb; e.ankleRgb = r.ankleRgb; e.reference = r.reference;
    e.shade = r.shade; e.shadeDist = r.shadeDist; e.composeMs = r.composeMs;
    b.indices = std::move(r.cutIndices);
    b.submeshes = std::move(r.cutSubs);
    e.active = true;
    e.status.clear();
    if (!inRefresh) {
        // the body's vertices are as they were: only its triangles change (no layer over it: refreshBody's whole work else)
        if (glReady_ && b.vao && b.ibo && !b.shaped.empty() && (!b.design || b.k <= 0)) {
            glBindVertexArray(b.vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, b.ibo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(b.indices.size() * 4), b.indices.data(), GL_STATIC_DRAW);
            glBindVertexArray(0);
        } else {
            b.dirty = true;   // (refreshBody uploads the cut body this same frame)
        }
    }
    a.soft.dirty = true;
    a.cloth.dirty = true;
    e.glMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    e.staged.reset();
    std::fprintf(stderr, "eve: fitted in %.1f ms (skin %.1f ms, all %.1f ms %s): %zu triangles over %zu of the body's; neck %06X ankle %06X, shade %d; "
                 "GL thread %.2f ms (snapshot and swap; the upload's slices at most %.2f ms a frame), swapped in after %u frames\n",
                 e.body.stats.ms, e.composeMs, r.totalMs, e.sync ? "at once" : "on the worker", e.body.indices.size() / 3, b.indices.size() / 3,
                 e.neckRgb, e.ankleRgb, e.shade, e.glMs, staged ? e.stageMs : 0.0, e.sync ? 0u : e.waitFrames);
    std::fprintf(stderr, "eve seams: colour across the neck %.1f, ankles %.1f / %.1f (CIE76 ΔE)\n", r.seamDE[0], r.seamDE[1], r.seamDE[2]);
    if (r.detail)
        std::fprintf(stderr, "eve details (Keep undead bones): %zu pieces (%zu vertices kept), %zu texels covered, %.1f ms\n", r.detailPieces,
                     r.detailKept, r.detailCovered, r.detailMs);
    const BodyScale& k = e.body.scale;
    std::fprintf(stderr, "eve proportions: head %.3f, height %.3f (%.2f heads) -> %.3f (%.2f heads); legs %.3f torso %.3f arms %.3f hands %.3f spread %.3f\n",
                 e.body.stats.head, e.body.stats.height, e.body.stats.height / std::max(e.body.stats.head, 1e-3f), e.body.stats.heightAfter,
                 e.body.stats.heightAfter / std::max(e.body.stats.head, 1e-3f), k.legs, k.torso, k.arms, k.hands, k.spread);
}

void Scene::setEveAsync(bool on) { eveAsync_ = on; }

void Scene::drawEve(Actor& a, const M4& world, const float tint[4]) {
    EveDraw& e = a.eve;
    if (!e.active || e.body.verts.empty()) return;
    if (!e.vao) {
        glGenVertexArrays(1, &e.vao);
        glBindVertexArray(e.vao);
        glGenBuffers(1, &e.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, e.vbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(e.body.verts.size() * sizeof(M2Vertex)), e.body.verts.data(), GL_STATIC_DRAW);
        glGenBuffers(1, &e.ibo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, e.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(e.body.indices.size() * 4), e.body.indices.data(), GL_STATIC_DRAW);
        gl::m2VertexLayout();
        glBindVertexArray(0);
    }
    if (!e.tex && !e.rgba.empty()) {
        glGenTextures(1, &e.tex);
        glBindTexture(GL_TEXTURE_2D, e.tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, e.w, e.h, 0, GL_RGBA, GL_UNSIGNED_BYTE, e.rgba.data());
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    if (!e.tex) return;
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(gl::loc(p, "uWorld"), 1, GL_FALSE, world.m);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, a.boneTex);
    glUniform1i(gl::loc(p, "uBones"), 1);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, e.tex);
    glUniform1i(gl::loc(p, "uTex"), 0);
    glUniform4fv(gl::loc(p, "uColor"), 1, tint);
    glUniform1f(gl::loc(p, "uAlphaRef"), -1.0f);
    glUniform1i(gl::loc(p, "uOpaque"), 1);
    glUniform1i(gl::loc(p, "uUnlit"), 0);
    if (a.soft.active()) SoftDraw::setGain(p, 1.0f);
    // Her skin is the person's skin: shaded as the model's own skin is (drawModel's skinKind 1) whenever that is, so the
    // light doesn't change across the neck, wrist and ankle seams (the wrap, the scatter at the terminator, the sheen). Her
    // texture has its own layout, not the HD skin's, so no thin parts are looked for on it (w 0). At Standard with no
    // sheen nothing is set: drawn exactly as before.
    const bool skinFx = gfx_.skin > 0 || (drawSkin_[3] > 0.5f && gfx_.thinSkin > 0) || drawSkin_[0] > 0;
    if (skinFx) {
        const float actorSkin[4] = {drawSkin_[0], drawSkin_[1], drawSkin_[2], 0.0f};
        glUniform4fv(gl::loc(p, "uSkinActor"), 1, actorSkin);
        glUniform1i(gl::loc(p, "uSkinKind"), 1);
    }
    // WC_EVE_DEBUG=1 (the desktop's seam measures): her mesh drawn solid green over its light, so a still finds where she ends
    static const bool debugMask = [] { const char* v = std::getenv("WC_EVE_DEBUG"); return v && v[0] == '1'; }();
    if (debugMask) glUniform4f(gl::loc(p, "uGhost"), 0.0f, 1.0f, 0.0f, 1.0f);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
    glBindVertexArray(e.vao);
    glDrawElements(GL_TRIANGLES, GLsizei(e.body.indices.size()), GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);
    if (debugMask) glUniform4f(gl::loc(p, "uGhost"), 0.0f, 0.0f, 0.0f, 0.0f);
    if (skinFx) glUniform1i(gl::loc(p, "uSkinKind"), 0);
}

} // namespace wc
