// Fine skin patches in the scene (skinpatch.h, skinpatchdraw.h): a patch is made on the skin where a finger, a grip or a pinch lands,
// carried on a bone, fed the pads, drawn as a texture the m2 program's vertex and fragment stages read, and let go once it has settled.
#include <GLES3/gl3.h>
#include <algorithm>
#include <chrono>
#include <cmath>

#include "flesh.h"
#include "glutil.h"
#include "scene.h"

namespace wc {

namespace {
unsigned gEmptyPs = 0;

enum Pb { P_CHEST, P_SPINE1, P_PELVIS, P_LTHIGH, P_RTHIGH, P_LARM, P_RARM, P_COUNT };
const char* const kPNames[P_COUNT] = {"Chest", "Spine1", "Pelvis", "L_Thigh", "R_Thigh", "L_UpperArm", "R_UpperArm"};

// skin and fat by region (metres): what a fold there can hold
float regionThickness(int r) {
    switch (r) {
        case kCrBreast: return 0.032f;
        case kCrButt: return 0.032f;
        case kCrThigh: return 0.026f;
        case kCrBelly: return 0.024f;
        case kCrHip: return 0.016f;
        case kCrGroin: return 0.020f;
        case kCrArm: return 0.015f;
        case kCrRibs: return 0.009f;
        case kCrShin: case kCrSkull: return 0.005f;
        default: return 0.018f;
    }
}

struct Hnd {
    uint32_t key = 0;
    V3 pos, n{0, 0, 1};
    float padW = 0.012f;       // world units
    float push = 0;            // metres
    float grip = 0;
    int region = kCrOther;
    bool pinch = false;
    V3 mate;                   // a pinch's other fingertip (world)
    uint32_t mateKey = 0;
};


V3 dirOf(const M4& M, const V3& o, const V3& d) { return (M.point(o + d) - M.point(o)); }
} // namespace

void SkinPatchDraw::initGl(unsigned p) {
    glUseProgram(p);
    if (!gEmptyPs) {
        glGenTextures(1, &gEmptyPs);
        glActiveTexture(GL_TEXTURE0 + kPatchUnit);
        glBindTexture(GL_TEXTURE_2D, gEmptyPs);
        const uint16_t z[4] = {0, 0, 0, 0};
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1, 1, 0, GL_RGBA, GL_HALF_FLOAT, z);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glActiveTexture(GL_TEXTURE0);
    }
    glUniform1i(gl::loc(p, "uPsTex"), kPatchUnit);
    glUniform1i(gl::loc(p, "uPsN"), 0);
}

void SkinPatchDraw::off(unsigned p) { glUniform1i(gl::loc(p, "uPsN"), 0); }

void SkinPatchDraw::upload() {
    if (!tex) {
        glGenTextures(1, &tex);
        glActiveTexture(GL_TEXTURE0 + kPatchUnit);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        half.assign(size_t(kPatchN) * kPatchN * kMaxPatches * 4, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, kPatchN, kPatchN * kMaxPatches, 0, GL_RGBA, GL_HALF_FLOAT, half.data());
    } else {
        glActiveTexture(GL_TEXTURE0 + kPatchUnit);
        glBindTexture(GL_TEXTURE_2D, tex);
    }
    static thread_local std::vector<float> buf;
    buf.resize(size_t(kPatchN) * kPatchN * 4);
    for (int k = 0; k < kMaxPatches; ++k) {
        const PatchSlot& s = slot[k];
        if (!s.on) continue;
        s.sim.exportField(buf.data());
        uint16_t* dst = half.data() + size_t(k) * kPatchN * kPatchN * 4;
        for (size_t i = 0; i < buf.size(); ++i) dst[i] = tissueHalf(buf[i]);
    }
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kPatchN, kPatchN * kMaxPatches, GL_RGBA, GL_HALF_FLOAT, half.data());
    glActiveTexture(GL_TEXTURE0);
    uploaded = true;
    needUpload = false;
}

void SkinPatchDraw::uniforms(unsigned p) const {
    if (usedN <= 0 || !uploaded || !tex) { glUniform1i(gl::loc(p, "uPsN"), 0); return; }
    glActiveTexture(GL_TEXTURE0 + kPatchUnit);
    glBindTexture(GL_TEXTURE_2D, tex);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(gl::loc(p, "uPsTex"), kPatchUnit);
    glUniform1i(gl::loc(p, "uPsN"), usedN);
    glUniform1f(gl::loc(p, "uCtLayer"), 1.0f);
    glUniform1f(gl::loc(p, "uCtShade"), 1.0f);
    glUniform4fv(gl::loc(p, "uPsC"), usedN, uC);
    glUniform4fv(gl::loc(p, "uPsU"), usedN, uU);
    glUniform4fv(gl::loc(p, "uPsV"), usedN, uV);
    glUniform4fv(gl::loc(p, "uPsW"), usedN, uW);
}

void Scene::setSkinPatchQuality(int level) { skinPatchLevel_ = std::clamp(level, 0, 2); }

const SkinPatchDraw* Scene::skinPatchOf(int id) const {
    auto it = actors_.find(id);
    return it == actors_.end() ? nullptr : &it->second.skinPatch;
}

void Scene::stepSkinPatches(uint32_t clock) {
    const float dt = skinPatchStarted_ && clock >= skinPatchClock_ ? std::min(float(clock - skinPatchClock_) / 1000.0f, 0.25f) : 0.0f;
    skinPatchClock_ = clock; skinPatchStarted_ = true;
    const int maxPatches = skinPatchLevel_ >= 2 ? 2 : 1;   // (about a millisecond of a big core each)
    for (auto& [id, a] : actors_) {
        SkinPatchDraw& sd = a.skinPatch;
        sd.usedN = 0; sd.pads = 0; sd.stepMs = 0;
        auto clearAll = [&]() { for (PatchSlot& s : sd.slot) s.on = false; };
        if (skinPatchLevel_ <= 0 || !a.visible || !a.character || !a.mesh || !a.mesh->model || a.mats.empty() || a.ghost) { clearAll(); continue; }
        if ((a.position - eye_).length() > 18.0f) { clearAll(); continue; }
        const M2Model& m = *a.mesh->model;
        const M4 W = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
        const float wpm = std::max(a.scale, 0.05f) / kYardM;     // world units per metre (the finger's size is in world units: contact.h)
        const float slider = contactSoftness_;
        // ---- the hands on this body this frame (the same sources as the lattices')
        std::vector<Hnd> hs;
        auto addHandle = [&](uint32_t key, const V3& pos, const V3& n, float padW, float depthM, int region, float grip) {
            const ContactRegionInfo ri = contactRegion(region);
            Hnd h;
            h.key = key; h.pos = pos; h.n = n; h.padW = padW; h.region = region; h.grip = grip;
            h.push = std::min(std::max(depthM, 0.0f) * ri.softness * slider, ri.maxDepth * slider);
            hs.push_back(h);
        };
        const bool contacts = contactLevel_ != kContactOff;
        for (int k = 0; k < kTouchPointers; ++k) {
            const ContactDraw::Touch& t = a.contact.touches[k];
            if (!t.on || a.contact.pinches.pinching(k)) continue;
            addHandle(0x7001u + uint32_t(k), t.in.pos, t.in.normal, std::max(t.pad, 0.008f), t.pressure * 0.04f, t.in.region, 0.7f);
        }
        if (contacts)
            for (int i = 0; i < a.contact.solver.pinchCount(); ++i) {
                const PinchOut& po = a.contact.solver.pinchOut()[i];
                int region = kCrOther; float best = 1e30f;
                for (int k = 0; k < kTouchPointers; ++k) {
                    const ContactDraw::Touch& t = a.contact.touches[k];
                    if (!t.on) continue;
                    const float d = (t.in.pos - po.a).length();
                    if (d < best) { best = d; region = t.in.region; }
                }
                for (int e = 0; e < 2; ++e) {
                    Hnd h;
                    // (the fingertip's own grip goes on from its touch: the pinch's pad takes its pointer's key)
                    int near = -1; float nd = 1e30f;
                    for (int k = 0; k < kTouchPointers; ++k) {
                        const ContactDraw::Touch& t = a.contact.touches[k];
                        if (!t.on) continue;
                        const float d = (t.in.pos - (e ? po.b : po.a)).length();
                        if (d < nd) { nd = d; near = k; }
                    }
                    h.key = near >= 0 ? 0x7001u + uint32_t(near) : 0x7200u + uint32_t(i * 2 + e); h.pos = e ? po.b : po.a; h.mate = e ? po.a : po.b; h.n = po.normal;
                    h.padW = std::max(po.pad, 0.008f); h.push = 0.0025f; h.grip = 1.0f; h.region = region; h.pinch = true;
                    hs.push_back(h);
                }
                hs[hs.size() - 1].mateKey = hs[hs.size() - 2].key; hs[hs.size() - 2].mateKey = hs[hs.size() - 1].key;
            }
        for (const TissueHandle& th : a.tissue.manual) {
            Hnd h;
            h.key = th.key | 0x20000000u; h.pos = th.rest + th.pull; h.n = th.normal; h.padW = th.radius / 1.4f; h.push = th.push; h.grip = th.grip; h.region = kCrBelly;
            hs.push_back(h);
        }
        if (contacts)
            for (int i = 0; i < a.contact.solver.count(); ++i) {
                const ContactOut& o = a.contact.solver.out()[i];
                if ((o.key & 0xFFFFFFF0u) == 0x7000u) continue;
                addHandle(o.key | 0x40000000u, o.pos, o.normal, std::max(o.radius * 0.5f, 0.01f), o.depth, int(o.softness > 0.5f ? kCrBreast : kCrOther), 0.25f);
            }
        // ---- the frames of the live patches now
        std::array<int, P_COUNT> bi;
        for (int i = 0; i < P_COUNT; ++i) bi[size_t(i)] = m.findWowBone(kPNames[i]);
        auto frameNow = [&](PatchSlot& s) -> bool {
            if (s.bone < 0 || size_t(s.bone) >= a.mats.size()) return false;
            const M4 M = W * a.mats[size_t(s.bone)];
            s.oW = M.point(s.oB);
            s.t1W = dirOf(M, s.oB, s.t1B).normalized();
            s.t2W = dirOf(M, s.oB, s.t2B).normalized();
            s.nW = dirOf(M, s.oB, s.nB).normalized();
            return true;
        };
        for (PatchSlot& s : sd.slot) if (s.on && !frameNow(s)) s.on = false;
        // ---- each hand to a patch (a pinch makes its own, along the line between its fingers)
        std::vector<std::vector<int>> mine(kMaxPatches);
        for (size_t hi = 0; hi < hs.size(); ++hi) {
            const Hnd& h = hs[hi];
            int found = -1;
            for (int k = 0; k < kMaxPatches && found < 0; ++k) {
                PatchSlot& s = sd.slot[k];
                if (!s.on) continue;
                const V3 r = h.pos - s.oW;
                const float x = r.dot(s.t1W) / s.unit, y = r.dot(s.t2W) / s.unit, z = r.dot(s.nW) / s.unit;
                const float lim = s.sim.half() * 0.62f;
                if (std::fabs(x) < lim && std::fabs(y) < lim && std::fabs(z) < 0.03f && h.n.dot(s.nW) > 0.5f) found = k;
            }
            if (found < 0) {
                int freeK = -1;
                for (int k = 0; k < maxPatches; ++k) if (!sd.slot[k].on) { freeK = k; break; }
                if (freeK < 0) continue;
                // the nearest bone that carries soft flesh
                int bone = -1; float bd = 1e30f;
                for (int i = 0; i < P_COUNT; ++i) {
                    const int b = bi[size_t(i)];
                    if (b < 0 || size_t(b) >= a.mats.size()) continue;
                    const float d = ((W * a.mats[size_t(b)]).point(m.bones[size_t(b)].pivot) - h.pos).length();
                    if (d < bd) { bd = d; bone = b; }
                }
                if (bone < 0) continue;
                PatchSlot& s = sd.slot[freeK];
                const V3 n = h.n.normalized();
                V3 c = h.pos;
                V3 t1;
                if (h.pinch) { c = (h.pos + h.mate) * 0.5f; t1 = h.mate - h.pos; if (hi % 2 == 1) t1 = t1 * -1.0f; }
                else {
                    t1 = V3(0, 0, 1);
                    // the fingers near it that are not on a patch yet: the patch is centred between them, so a pinch that is still two touches stays on one
                    V3 sum = h.pos; int cnt = 1;
                    for (size_t o = 0; o < hs.size(); ++o) {
                        if (o == hi || hs[o].pinch != h.pinch) continue;
                        if ((hs[o].pos - h.pos).length() < 0.075f * wpm && hs[o].n.dot(h.n) > 0.5f) { sum = sum + hs[o].pos; ++cnt; }
                    }
                    c = sum * (1.0f / float(cnt));
                }
                t1 = t1 - n * t1.dot(n);
                if (t1.length() < 1e-5f) t1 = V3(1, 0, 0) - n * n.x;
                if (t1.length() < 1e-5f) t1 = V3(0, 1, 0) - n * n.y;
                t1 = t1.normalized();
                const V3 t2 = n.cross(t1).normalized();
                s.unit = wpm;
                PatchSpec sp;
                const float padM = h.padW / wpm;
                sp.spacing = std::clamp(padM * 0.30f, 0.003f, 0.0055f);
                sp.thickness = regionThickness(h.region);
                sp.softness = std::clamp(contactRegion(h.region).softness * slider, 0.5f, 1.4f);
                sp.substeps = skinPatchLevel_ >= 2 ? 5 : 3;
                s.sim.build(sp);
                s.thickness = sp.thickness;
                const M4 Mi = affineInverse(W * a.mats[size_t(bone)]);
                s.bone = bone;
                s.oB = Mi.point(c);
                s.t1B = (Mi.point(c + t1) - s.oB);
                s.t2B = (Mi.point(c + t2) - s.oB);
                s.nB = (Mi.point(c + n) - s.oB);
                s.on = true; s.lastPad = 0; s.dirty = true; s.born = clock;
                frameNow(s);
                found = freeK;
            }
            mine[size_t(found)].push_back(int(hi));
        }
        // ---- step the patches
        const auto t0 = std::chrono::steady_clock::now();
        bool anyAwake = false;
        int used = 0;
        for (int k = 0; k < kMaxPatches; ++k) {
            PatchSlot& s = sd.slot[k];
            if (!s.on) continue;
            s.sim.beginPads();
            for (int hi : mine[size_t(k)]) {
                const Hnd& h = hs[size_t(hi)];
                const V3 r = h.pos - s.oW;
                PatchPad pd;
                pd.key = h.key;
                pd.x = r.dot(s.t1W) / s.unit; pd.y = r.dot(s.t2W) / s.unit;
                pd.radius = std::clamp(h.padW / s.unit, 0.004f, 0.04f);
                pd.push = h.pinch ? h.push : std::min(h.push * 0.65f, 0.0075f * std::max(0.6f, contactRegion(h.region).softness)); pd.grip = h.grip;
                pd.reach = 0.045f;
                if (h.pinch) {
                    const V3 j = h.mate - h.pos;
                    pd.dirx = j.dot(s.t1W); pd.diry = j.dot(s.t2W);
                    pd.aspect = 1.15f; pd.mate = h.mateKey;
                }
                s.sim.addPad(pd);
            }
            sd.pads += int(mine[size_t(k)].size());
            s.lastPad = mine[size_t(k)].empty() ? s.lastPad + dt : 0.0f;
            s.sim.step(dt);
            if (s.sim.asleep() && s.lastPad > 0.3f) { s.on = false; continue; }
            if (!s.sim.asleep() || !mine[size_t(k)].empty() || s.dirty) anyAwake = true;
            s.dirty = false;
            const int e = used++;
            sd.mapped[e] = k;
            const float sp = s.sim.spacing();
            sd.uC[e * 4] = s.oW.x; sd.uC[e * 4 + 1] = s.oW.y; sd.uC[e * 4 + 2] = s.oW.z; sd.uC[e * 4 + 3] = s.unit;
            const float ig = 1.0f / (sp * s.unit);
            sd.uU[e * 4] = s.t1W.x; sd.uU[e * 4 + 1] = s.t1W.y; sd.uU[e * 4 + 2] = s.t1W.z; sd.uU[e * 4 + 3] = ig;
            sd.uV[e * 4] = s.t2W.x; sd.uV[e * 4 + 1] = s.t2W.y; sd.uV[e * 4 + 2] = s.t2W.z; sd.uV[e * 4 + 3] = float(k * kPatchN);
            sd.uW[e * 4] = s.nW.x; sd.uW[e * 4 + 1] = s.nW.y; sd.uW[e * 4 + 2] = s.nW.z; sd.uW[e * 4 + 3] = sp * 100.0f;
        }
        sd.stepMs = float(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
        sd.usedN = used;
        if (used > 0 && (anyAwake || sd.needUpload || !sd.uploaded)) sd.upload();
    }
}

} // namespace wc
