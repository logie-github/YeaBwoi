// The Scene's side of the Eve body (evebody.h): the set loaded, each actor's options, its refit when its look changes (the
// body cut, the skin matched and composed) and its drawing in the body pass, skinned on the actor's own bones.
#include <GLES3/gl3.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
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

bool readPngFile(const std::string& path, std::vector<uint8_t>& rgba, int& w, int& h, std::string& error) {
    Bytes b;
    if (!readFile(path, b)) { error = "can't be read"; return false; }
    return decodePng(b.data(), b.size(), rgba, w, h, error);
}

uint64_t mixKey(uint64_t k, uint64_t v) { return (k ^ v) * 1099511628211ull; }

} // namespace

bool Scene::loadEveSet(const std::string& dir, const std::string& fitJson, std::string& error) {
    auto set = std::make_unique<EveSet>();
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
    }
    e.forgetGl();
}

// The key of what the body is fitted for: the set, the model, its shown geosets, its skin's layers, its shape and the options.
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
        for (float f : a.parts.tint) { uint32_t bits; std::memcpy(&bits, &f, 4); k = mixKey(k, bits); }
        for (float f : a.sculpt) { uint32_t bits; std::memcpy(&bits, &f, 4); k = mixKey(k, bits); }   // the creator's sculpt (sculpt.h)
        if (k == 0) k = 1;
    }
    if (k == e.key) return;
    e.key = k;
    a.body.shapeDirty = true;
    a.body.dirty = true;
    a.soft.dirty = true;
    a.cloth.dirty = true;
}

void Scene::eveRefit(Actor& a) {
    EveDraw& e = a.eve;
    dropEveGl(a);
    e.active = false;
    e.body = EveBody();
    e.measure = EveMeasureMesh();
    e.rgba.clear();
    e.shade = -1;
    if (!e.requested) { e.status = "off"; return; }
    if (!eveSet_) { e.status = "no Eve set is loaded"; return; }
    const M2Model& m = *a.mesh->model;
    const EveRaceFit* fit = eveSet_->fits.forModel(m.fileId);
    if (!fit) { e.status = "this model has no row in the fit table"; return; }
    if (!fit->enabled) { e.status = "not offered for " + fit->name + ": " + fit->reason; return; }
    std::shared_ptr<EveMesh> mesh = eveSet_->mesh(e.opt.bottom);
    if (!mesh) { e.status = "the Eve set has no mesh"; return; }
    BodyGl& b = a.body;
    const bool own = !b.shaped.empty() && !b.indices.empty() && !b.submeshes.empty();
    EveBody body = fitEve(m, own ? b.shaped : m.vertices, own ? b.indices : m.indices, own ? b.submeshes : m.submeshes, a.geosets, *mesh, *fit);
    if (!body.ok) { e.status = body.error; return; }
    // the creator's sculpt sliders (sculpt.h) on the fitted body, before it is measured, cut and drawn
    if (sculptActive(a.sculpt.data(), a.sculpt.size()))
        applySculpt(sculptRigOf(m), body.verts, body.indices, a.sculpt.data(), a.sculpt.size());
    auto t0 = std::chrono::steady_clock::now();
    // the actor's own skin where Eve meets it: its base skin layer (the whole material, lowest) sampled at the cut edges
    const EveSetIndex& ix = eveSet_->index;
    uint32_t neck = ix.skin.neck, ankle = ix.skin.ankle;
    {
        const SkinLayer* base = nullptr;
        for (const SkinLayer& l : a.layers)
            if (l.textureType == 1 && l.fileId && l.layer != kBodyLayer && !isClothLayer(l) && (!base || l.layer < base->layer)) base = &l;
        uint32_t fid = base ? base->fileId : 0;
        if (!fid) { auto t = a.textures.find(1); if (t != a.textures.end()) fid = t->second; }
        Bytes blp;
        Image img;
        std::string path = fid ? files_(fid) : "";
        if (!path.empty() && readFile(path, blp) && decodeBlp(blp, 512, img)) {
            int n = eveSampleAt(img.rgba.data(), img.width, img.height, body.neckUvs);
            int k = eveSampleAt(img.rgba.data(), img.width, img.height, body.ankleUvs);
            auto tinted = [&](int c) {
                float r = float((c >> 16) & 255) * a.parts.tint[0], g = float((c >> 8) & 255) * a.parts.tint[1], bl = float(c & 255) * a.parts.tint[2];
                return uint32_t(std::clamp(r, 0.0f, 255.0f)) << 16 | uint32_t(std::clamp(g, 0.0f, 255.0f)) << 8 | uint32_t(std::clamp(bl, 0.0f, 255.0f));
            };
            if (n >= 0) neck = tinted(n);
            if (k >= 0) ankle = tinted(k); else if (n >= 0) ankle = neck;
        }
    }
    // the skin texture: the neutral skin tinted to that colour, or a chosen tone as it is (corrected once per file)
    const EveTexInfo* skin = ix.hasSkin ? &ix.skin : nullptr;
    bool tone = e.opt.tone >= 0 && size_t(e.opt.tone) < ix.tones.size();
    if (tone) skin = &ix.tones[size_t(e.opt.tone)];
    if (!skin) { e.status = "the Eve set has no skin"; return; }
    std::string texKey = skin->file + "|" + mesh->variant;
    std::shared_ptr<EveSet::Tex> tex;
    for (auto& t : eveSet_->textures) if (t.first == texKey) tex = t.second;
    if (!tex) {
        tex = std::make_shared<EveSet::Tex>();
        std::string why;
        if (!readPngFile(eveSet_->dir + "/" + skin->file, tex->rgba, tex->w, tex->h, why)) { e.status = "the skin " + skin->file + " " + why; return; }
        tex->prep = evePrepare(*mesh, tex->rgba, tex->w, tex->h);
        eveApplyCorrection(tex->prep, tex->rgba);
        eveSet_->textures.push_back({texKey, tex});
        if (eveSet_->textures.size() > 3) eveSet_->textures.erase(eveSet_->textures.begin());   // (5 MB each)
        std::fprintf(stderr, "eve skin %s: front/back brightness %.3f/%.3f before the correction, %.3f/%.3f after\n", skin->file.c_str(),
                     tex->prep.frontBefore, tex->prep.backBefore, tex->prep.frontAfter, tex->prep.backAfter);
    }
    // the reference: the corrected skin's own colour near its neck ring (else set.json's)
    e.reference = tex->prep.neck ? tex->prep.neck : (skin->neck ? skin->neck : skin->mean);
    V3 gain = tone ? V3(1, 1, 1) : eveGain(neck, e.reference);
    // the overlay: its shade nearest the skin (Lab), its brightness scaled to the skin's
    std::shared_ptr<EveSet::Tex> ov;
    const EveShadeInfo* shade = nullptr;
    if (e.opt.overlaySlot >= 0 && size_t(e.opt.overlaySlot) < ix.overlays.size()) {
        const EveSlotInfo& slot = ix.overlays[size_t(e.opt.overlaySlot)];
        uint32_t skinRgb = tone ? skin->mean : neck;
        int s = e.opt.overlayShade >= 0 && size_t(e.opt.overlayShade) < slot.shades.size() ? e.opt.overlayShade
                                                                                            : eveNearestShade(skinRgb, slot.shades, &e.shadeDist);
        if (s >= 0) {
            shade = &slot.shades[size_t(s)];
            e.shade = s;
            for (auto& t : eveSet_->overlays) if (t.first == shade->file) ov = t.second;
            if (!ov) {
                ov = std::make_shared<EveSet::Tex>();
                std::string why;
                if (readPngFile(eveSet_->dir + "/" + shade->file, ov->rgba, ov->w, ov->h, why)) {
                    eveSet_->overlays.push_back({shade->file, ov});
                    if (eveSet_->overlays.size() > 8) eveSet_->overlays.erase(eveSet_->overlays.begin());
                } else { ov.reset(); std::fprintf(stderr, "eve overlay %s %s\n", shade->file.c_str(), why.c_str()); }
            }
        }
    }
    // its colour: brightness only when a shade was made for a skin like this one (Lab distance under 12), so its own pinks and
    // browns stay; for a skin no shade was made for (green, blue, grey), the full per-channel tint, fading in up to 30
    V3 ovGain(1, 1, 1);
    if (shade) {
        uint32_t want = tone ? skin->mean : neck;
        V3 t = eveLinear(want), s = eveLinear(shade->swatch);
        float yt = 0.2126f * t.x + 0.7152f * t.y + 0.0722f * t.z, ys = 0.2126f * s.x + 0.7152f * s.y + 0.0722f * s.z;
        float bright = std::clamp(yt / std::max(ys, 1e-4f), 0.5f, 2.0f);
        V3 full = eveGain(want, shade->swatch);
        float d = (eveLab(want) - eveLab(shade->swatch)).length();
        float f = std::clamp((d - 12.0f) / 18.0f, 0.0f, 1.0f);
        ovGain = V3(bright, bright, bright) * (1 - f) + full * f;
    }
    eveCompose(tex->rgba, tex->w, tex->h, gain, ov ? ov->rgba.data() : nullptr, ov ? ov->w : 0, ov ? ov->h : 0, shade ? shade->rect[0] : 0,
               shade ? shade->rect[1] : 0, std::clamp(e.opt.opacity, 0.0f, 1.0f), ovGain, tex->prep, neck, ankle, e.rgba);
    e.w = tex->w; e.h = tex->h;
    e.neckRgb = neck; e.ankleRgb = ankle;
    e.composeMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    // the body under it cut (any corner on a body bone drops the triangle)
    if (b.shaped.empty()) { b.shaped = m.vertices; b.indices = m.indices; b.submeshes = m.submeshes; }
    b.indices = cutTriangles(b.indices, b.submeshes, body.cut, 1);
    e.measure = eveMeasureMesh(m, b.shaped, b.indices, b.submeshes, a.geosets, body);
    e.body = std::move(body);
    e.active = true;
    e.status.clear();
    std::fprintf(stderr, "eve: fitted in %.1f ms (skin %.1f ms): %zu triangles over %zu of the body's; neck %06X ankle %06X, shade %d\n",
                 e.body.stats.ms, e.composeMs, e.body.indices.size() / 3, b.indices.size() / 3, neck, ankle, e.shade);
}

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
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
    glBindVertexArray(e.vao);
    glDrawElements(GL_TRIANGLES, GLsizei(e.body.indices.size()), GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);
}

} // namespace wc
