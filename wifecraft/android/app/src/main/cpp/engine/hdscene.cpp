// HD Models in the scene (hdbody.h): the sets loaded, an actor's HD figure fitted to its model and drawn instead of it.
#include <GLES3/gl3.h>
#include <algorithm>
#include <cstdio>
#include <cstring>

#include "glutil.h"
#include "scene.h"
#include "sculpt.h"
#include "springdraw.h"

namespace wc {

namespace {
bool readAll(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (n <= 0) { std::fclose(f); return false; }
    out.resize(size_t(n));
    bool ok = std::fread(out.data(), 1, out.size(), f) == out.size();
    std::fclose(f);
    return ok;
}
} // namespace

bool Scene::loadHdSet(const std::string& path, std::string& error) {
    std::vector<uint8_t> raw;
    if (!readAll(path, raw)) { error = "can't be read"; return false; }
    auto set = std::make_shared<HdSet>();
    if (!parseWhd(raw.data(), raw.size(), *set, error)) return false;
    hdSets_[set->race * 10 + set->sex] = std::move(set);
    ++hdGen_;
    return true;
}

void Scene::clearHdSets() {
    hdSets_.clear();
    ++hdGen_;
    if (glReady_) for (auto& [k, t] : hdTex_) glDeleteTextures(1, &t);
    hdTex_.clear();
    for (auto& [id, a] : actors_) a.hd.tex.clear();
}

void Scene::setHdBody(int id, int race, int sex, const HdOptions& options) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    HdDraw& h = it->second.hd;
    h.opt = options;
    h.race = race; h.sex = sex;
    h.requested = options.on;
}

void Scene::clearHdBody(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    it->second.hd.requested = false;
}

bool Scene::hdStatus(int id, HdStatus& out) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    const HdDraw& h = it->second.hd;
    out = HdStatus();
    out.requested = h.requested;
    out.active = h.active;
    out.status = h.status;
    out.set = h.set ? h.set->label : "";
    out.stats = h.fit.stats;
    return true;
}

void Scene::useHdGenitalMask(int id, bool on) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    HdDraw& h = it->second.hd;
    if (h.hideGenitals == on) return;
    h.hideGenitals = on;
    if (!on) it->second.parts.hdGenitalMask.clear();
    h.key = 0;   // (fitted again at the next frame)
}

const std::vector<uint32_t>* Scene::hdBodyGenitals(int id) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.hd.active || !it->second.hd.set) return nullptr;
    const HdSet& set = *it->second.hd.set;
    if (set.bodies.empty()) return nullptr;
    const HdBodyOption& b = set.bodies[size_t(std::clamp(it->second.hd.opt.body, 0, int(set.bodies.size()) - 1))];
    return b.genitals.empty() ? nullptr : &b.genitals;
}

void Scene::dropHdGl(Actor& a) {
    HdDraw& h = a.hd;
    if (glReady_) {
        if (h.vao) glDeleteVertexArrays(1, &h.vao);
        if (h.vbo) glDeleteBuffers(1, &h.vbo);
        if (h.ibo) glDeleteBuffers(1, &h.ibo);
    }
    h.forgetGl();
}

// Fitted again when the set, the model or the options change; taken off (the model drawn) when it is no longer asked for or
// no set of the actor's race and sex is loaded.
void Scene::hdCheck(Actor& a) {
    HdDraw& h = a.hd;
    if (!h.requested || !a.mesh || !a.mesh->model) {
        if (h.active) { dropHdGl(a); h.active = false; h.fit = HdFit(); a.soft.dirty = true; a.fleshKey = 0; }
        h.status = h.requested ? "no model" : "off";
        h.key = 0;
        return;
    }
    auto it = hdSets_.find(h.race * 10 + h.sex);
    if (it == hdSets_.end()) {
        if (h.active) { dropHdGl(a); h.active = false; h.fit = HdFit(); a.soft.dirty = true; a.fleshKey = 0; }
        h.status = "no HD set for this race and sex";
        return;
    }
    const M2Model& m = *a.mesh->model;
    uint64_t key = 1469598103934665603ull;
    auto mix = [&key](uint64_t v) { key = (key ^ v) * 1099511628211ull; };
    mix(uint64_t(reinterpret_cast<uintptr_t>(&m)));
    mix(uint64_t(reinterpret_cast<uintptr_t>(it->second.get())));
    // the body's own genitals left out while the add-on parts are drawn in their place (setHdGenitalMask, bodypart.h)
    HdOptions opt = h.opt;
    opt.hideGenitals = h.hideGenitals || !a.parts.hdGenitalMask.empty();
    mix(opt.key());
    mix(uint64_t(tessEdge_ * 1e6f)); mix(uint64_t(tessCap_));   // (the tessellation of the soft regions)
    for (float f : a.sculpt) { uint32_t bits; std::memcpy(&bits, &f, 4); mix(bits); }   // (the creator's sculpt sliders)
    if (key == 0) key = 1;
    if (key == h.key) return;
    std::string err;
    HdFit fit = fitHd(m, *it->second, opt, err);
    h.key = key;
    if (fit.verts.empty()) {
        if (h.active) dropHdGl(a);
        h.active = false;
        h.status = err.empty() ? "it can't be fitted" : err;
        return;
    }
    // the creator's sculpt sliders (sculpt.h) on the HD figure about its own joints: the body and what it wears with it
    if (sculptActive(a.sculpt.data(), a.sculpt.size())) {
        SculptBones b;
        auto find = [&](const char* n, const char* alt = nullptr) { int i = m.findWowBone(n); return i >= 0 || !alt ? i : m.findWowBone(alt); };
        b.pelvis = find("Pelvis", "Root"); b.waist = find("Spine1", "Waist"); b.chest = find("Chest", "Spine2");
        b.neck = find("Neck1"); b.head = find("Head");
        for (int k = 0; k < 2; ++k) {
            std::string sd = k ? "R_" : "L_";
            b.breast[k] = m.findWowBone(sd + "Breast"); b.clavicle[k] = m.findWowBone(sd + "Clavicle"); b.upperArm[k] = m.findWowBone(sd + "UpperArm");
            b.forearm[k] = m.findWowBone(sd + "Forearm"); b.hand[k] = m.findWowBone(sd + "Hand"); b.thigh[k] = m.findWowBone(sd + "Thigh");
            b.calf[k] = m.findWowBone(sd + "Calf"); b.ankle[k] = m.findWowBone(sd + "Ankle");
        }
        std::vector<int16_t> parents(m.bones.size());
        for (size_t i = 0; i < m.bones.size(); ++i) parents[i] = m.bones[i].parent;
        applySculpt(sculptRig(fit.rigPivots, parents, b), fit.verts, fit.indices, a.sculpt.data(), a.sculpt.size());
    }
    if (tessEdge_ > 0) {
        TessStats ts;
        const size_t added = hdTessellateSoft(m, fit, tessEdge_, size_t(tessCap_), &ts);
        if (added) std::fprintf(stderr, "tessellation: %zu -> %zu vertices, %zu -> %zu triangles, soft edges p50 %.2f -> %.2f mm, p99 %.2f -> %.2f mm (HD units), %d passes%s, %.1f ms\n",
                                ts.verticesBefore, ts.verticesAfter, ts.trianglesBefore, ts.trianglesAfter, ts.edgeP50Before * 1000, ts.edgeP50After * 1000,
                                ts.edgeP99Before * 1000, ts.edgeP99After * 1000, ts.passes, ts.capped ? " (capped)" : "", ts.ms);
    }
    dropHdGl(a);
    h.set = it->second;
    h.fit = std::move(fit);
    const HdSet& set = *h.set;
    const int tint = std::clamp(h.opt.tint, 0, std::max(0, int(set.tints.size()) - 1));
    uint32_t rgb = set.tints.empty() ? 0xFFFFFF : set.tints[size_t(tint)].rgb;
    h.skin[0] = float((rgb >> 16) & 255) / 255.0f;
    h.skin[1] = float((rgb >> 8) & 255) / 255.0f;
    h.skin[2] = float(rgb & 255) / 255.0f;
    h.active = true;
    h.status = "";
    // the parts drawn in place of the body's own genitals (useHdGenitalMask): the mask the parts' drawing looks for
    if (h.hideGenitals) {
        const HdSet& hs = *h.set;
        const HdBodyOption* bo = hs.bodies.empty() ? nullptr : &hs.bodies[size_t(std::clamp(h.opt.body, 0, int(hs.bodies.size()) - 1))];
        a.parts.hdGenitalMask = bo ? bo->genitals : std::vector<uint32_t>();
    }
    a.soft.dirty = true;     // (the soft body's regions found again on the HD body)
    a.fleshKey = 0;          // (and the flesh measured on it)
}

void Scene::drawHd(Actor& a, const M4& world, const float tint[4]) {
    HdDraw& h = a.hd;
    if (!h.active || h.fit.verts.empty() || !h.set) return;
    const HdSet& set = *h.set;
    if (!h.vao) {
        glGenVertexArrays(1, &h.vao);
        glBindVertexArray(h.vao);
        glGenBuffers(1, &h.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, h.vbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(h.fit.verts.size() * sizeof(M2Vertex)), h.fit.verts.data(), GL_STATIC_DRAW);
        glGenBuffers(1, &h.ibo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, h.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(h.fit.indices.size() * 4), h.fit.indices.data(), GL_STATIC_DRAW);
        gl::m2VertexLayout();
        glBindVertexArray(0);
    }
    if (h.tex.size() != h.fit.parts.size()) {
        // each part's texture: the set's, with the chosen mark laid over the skin it belongs to, the hair recoloured; shared
        // by every actor drawn with the same (decoded once: the body's 2048 px skins take tens of ms each)
        h.tex.assign(h.fit.parts.size(), 0);
        int hairTint = h.opt.hairTint >= 0 && size_t(h.opt.hairTint) < set.hairTints.size() ? h.opt.hairTint : -1;
        // (a tint of white with no lift is the hair as made: not recoloured)
        if (hairTint >= 0 && set.hairTints[size_t(hairTint)].rgb == 0xFFFFFF && set.hairTints[size_t(hairTint)].lift <= 0) hairTint = -1;
        if (hairTint >= 0 && set.hairTints[size_t(hairTint)].texture >= 0) hairTint = -1;   // (a colour of its own: its texture, as made)
        for (size_t i = 0; i < h.fit.parts.size(); ++i) {
            const HdDrawPart& p = h.fit.parts[i];
            if (p.texture < 0) continue;
            int overlay = -1;
            for (size_t k = 0; k < set.overlays.size(); ++k) {
                if (set.overlays[k].onto != p.texture) continue;
                const std::string& slot = set.overlays[k].slot;
                const bool face = slot == "face";
                int faceIdx = -1;
                if (face) { int n = 0; for (size_t q = 0; q <= k; ++q) if (set.overlays[q].slot == "face") ++n; faceIdx = n - 1; }
                if ((face && faceIdx == h.opt.faceMark) || (!face && h.opt.bodyMarks > 0)) { overlay = int(k); break; }
            }
            const bool hair = p.role == 1 && hairTint >= 0;
            char key[160];
            std::snprintf(key, sizeof key, "%s|%llu|%d|%d|%d", set.id.c_str(), (unsigned long long)hdGen_, p.texture, overlay, hair ? hairTint : -1);
            auto found = hdTex_.find(key);
            if (found != hdTex_.end()) { h.tex[i] = found->second; continue; }
            std::vector<uint8_t> rgba; int w = 0, ht = 0; std::string err;
            if (!hdTexture(set, p.texture, overlay, hair ? &set.hairTints[size_t(hairTint)] : nullptr, rgba, w, ht, err)) continue;
            unsigned t = 0;
            glGenTextures(1, &t);
            glBindTexture(GL_TEXTURE_2D, t);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, ht, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            hdTex_[key] = t;
            h.tex[i] = t;
        }
    }
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(gl::loc(p, "uWorld"), 1, GL_FALSE, world.m);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, a.boneTex);
    glUniform1i(gl::loc(p, "uBones"), 1);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(gl::loc(p, "uTex"), 0);
    glUniform1i(gl::loc(p, "uUnlit"), 0);
    if (a.soft.active()) SoftDraw::setGain(p, 1.0f);
    const bool skinFx = gfx_.skin > 0 || drawSkin_[0] > 0;
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
    glBindVertexArray(h.vao);
    for (size_t i = 0; i < h.fit.parts.size(); ++i) {
        const HdDrawPart& part = h.fit.parts[i];
        if (!h.tex[i]) continue;
        glBindTexture(GL_TEXTURE_2D, h.tex[i]);
        float c[4] = {tint[0], tint[1], tint[2], tint[3]};
        if (part.role == 0) for (int k = 0; k < 3; ++k) c[k] *= h.skin[k];
        glUniform4fv(gl::loc(p, "uColor"), 1, c);
        glUniform1f(gl::loc(p, "uAlphaRef"), part.alphaTest ? 0.5f : -1.0f);
        glUniform1i(gl::loc(p, "uOpaque"), part.alphaTest ? 0 : 1);
        if (contactDraw_) ContactDraw::layer(p, part.role == 0 ? 1.0f : part.role == 1 ? 0.0f : kGarmentLayer, part.role == 0 ? 1.0f : 0.0f);   // (flesh contact: the skin's dent in full, a garment's at 85%, hair none)
        // the skin shaded as a person's skin is (the model's own skin: drawModel's skinKind 1), its texture its own layout
        const bool asSkin = skinFx && part.role == 0;
        if (asSkin) {
            const float actorSkin[4] = {drawSkin_[0], drawSkin_[1], drawSkin_[2], 0.0f};
            glUniform4fv(gl::loc(p, "uSkinActor"), 1, actorSkin);
            glUniform1i(gl::loc(p, "uSkinKind"), 1);
        } else if (part.role == 1 && gfx_.hair > 0) {
            glUniform1i(gl::loc(p, "uSkinKind"), 2);
        }
        glDrawElements(GL_TRIANGLES, GLsizei(part.count), GL_UNSIGNED_INT, (void*)(size_t(part.start) * 4));
        glUniform1i(gl::loc(p, "uSkinKind"), 0);
    }
    glBindVertexArray(0);
}

} // namespace wc
