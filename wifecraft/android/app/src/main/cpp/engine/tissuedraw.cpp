// Soft tissue in the scene (tissue.h, tissuedraw.h): each posed body's lattices on its bones, fed by the contacts (a finger's touch,
// the stages' hands and bodies, the desktop check's grips and a pinch), the field uploaded as a 3D texture, and the uniform block the
// vertex shader takes.
#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <unordered_map>

#include "flesh.h"
#include "glutil.h"
#include "scene.h"

namespace wc {

namespace {
unsigned gEmptyTex = 0;
constexpr int kUnit = 5;

enum Tb { T_CHEST, T_SPINE1, T_PELVIS, T_LTHIGH, T_RTHIGH, T_LCALF, T_RCALF, T_LBREAST, T_RBREAST, T_COUNT };
const char* const kNames[T_COUNT] = {"Chest", "Spine1", "Pelvis", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Breast", "R_Breast"};

// the region's lattice as the body gives it: model-space frame at the bind pose (a bone carries it), extents in model units
struct RegionDef {
    bool ok = false;
    int bone = -1;
    V3 o, u, v, w;
    float hu = 0, hv = 0, depth = 0;
    TissueMaterial mat;
};

float segF(const std::vector<float>& f, int seg, int field) {   // FleshMeasure::pack: 4 header floats, then 7 per segment
    const size_t o = 4 + size_t(seg) * 7;
    return f.size() >= o + 7 ? f[o + field] : 0.0f;
}

void defs(const M2Model& m, const std::array<int, T_COUNT>& bi, const std::vector<float>& fl, RegionDef* out) {
    auto has = [&](int t) { return bi[size_t(t)] >= 0; };
    auto piv = [&](int t) { return m.bones[size_t(bi[size_t(t)])].pivot; };
    if (!has(T_PELVIS) || !has(T_LTHIGH) || !has(T_RTHIGH) || !has(T_CHEST) || !has(T_SPINE1)) return;
    const float P = fl[1];
    const V3 fwd(1, 0, 0), up(0, 0, 1);
    V3 lat = piv(T_LTHIGH) - piv(T_RTHIGH); lat.z = 0; lat = lat.normalized();
    const V3 pv = piv(T_PELVIS), s1 = piv(T_SPINE1);
    const bool pelOk = segF(fl, kFleshPelvis, 0) > 0.5f, spOk = segF(fl, kFleshSpine, 0) > 0.5f;
    // breasts
    for (int s = 0; s < 2; ++s) {
        const int bt = s ? T_RBREAST : T_LBREAST;
        if (!has(bt)) continue;
        RegionDef& d = out[s];
        d.ok = true; d.bone = bi[T_CHEST];
        d.o = piv(bt) - fwd * (0.07f * P); d.u = lat * (s ? -1.0f : 1.0f); d.v = up; d.w = fwd;
        d.hu = 0.125f * P; d.hv = 0.125f * P; d.depth = 0.17f * P;
        d.mat.youngs = 1100; d.mat.freqTan = 2.6f; d.mat.freqNorm = 4.5f; d.mat.zeta = 0.14f;
    }
    // buttocks
    const float back = pelOk ? segF(fl, kFleshPelvis, 2) : 0.12f;
    for (int s = 0; s < 2; ++s) {
        const V3 tp = piv(s ? T_RTHIGH : T_LTHIGH);
        RegionDef& d = out[2 + s];
        d.ok = true; d.bone = bi[T_PELVIS];
        d.o = V3(pv.x + 0.03f * P, pv.y + (tp.y - pv.y) * 0.55f, tp.z + 0.03f * P);
        d.u = lat * (s ? -1.0f : 1.0f); d.v = up; d.w = fwd * -1.0f;
        d.hu = 0.11f * P; d.hv = 0.13f * P; d.depth = std::max(back + 0.03f, 0.17f) * P;
        d.mat.youngs = 2200; d.mat.freqTan = 3.2f; d.mat.freqNorm = 5.0f; d.mat.zeta = 0.16f;
    }
    // belly
    {
        RegionDef& d = out[4];
        const float front = spOk ? segF(fl, kFleshSpine, 1) : 0.11f;
        d.ok = true; d.bone = bi[T_SPINE1];
        d.o = V3(s1.x - 0.02f * P, 0, pv.z + (s1.z - pv.z) * 0.5f);
        d.u = lat; d.v = up; d.w = fwd;
        d.hu = 0.12f * P; d.hv = 0.09f * P; d.depth = (front + 0.02f) * P;
        d.mat.youngs = 1800; d.mat.freqTan = 2.4f; d.mat.freqNorm = 4.5f; d.mat.zeta = 0.15f;
    }
    // outer thighs
    for (int s = 0; s < 2; ++s) {
        const int tt = s ? T_RTHIGH : T_LTHIGH, ct = s ? T_RCALF : T_LCALF;
        if (!has(ct)) continue;
        const V3 tp = piv(tt), cp = piv(ct);
        const int seg = s ? kFleshRThigh : kFleshLThigh;
        RegionDef& d = out[5 + s];
        const float r = segF(fl, seg, 0) > 0.5f ? 0.45f * (segF(fl, seg, 1) + segF(fl, seg, 2)) : 0.1f;
        d.ok = true; d.bone = bi[tt];
        d.o = lerp(tp, cp, 0.33f); d.u = fwd; d.v = (tp - cp).normalized(); d.w = lat * (s ? -1.0f : 1.0f);
        d.hu = 0.09f * P; d.hv = 0.13f * P; d.depth = 1.25f * r * P;
        d.mat.youngs = 3000; d.mat.freqTan = 3.4f; d.mat.freqNorm = 5.5f; d.mat.zeta = 0.16f;
    }
}

} // namespace

void TissueDraw::initGl(unsigned p) {
    glUseProgram(p);
    if (!gEmptyTex) {
        glGenTextures(1, &gEmptyTex);
        glActiveTexture(GL_TEXTURE0 + kUnit);
        glBindTexture(GL_TEXTURE_3D, gEmptyTex);
        const uint16_t z[4] = {0, 0, 0, 0};
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, 1, 1, 1, 0, GL_RGBA, GL_HALF_FLOAT, z);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glActiveTexture(GL_TEXTURE0);
    }
    glUniform1i(gl::loc(p, "uTsTex"), kUnit);
    glUniform1i(gl::loc(p, "uTsN"), 0);
    glUniform3f(gl::loc(p, "uTsDim"), float(kTissueTexXY), float(kTissueTexXY), float(kTissueTexZ));
}

void TissueDraw::off(unsigned p) { glUniform1i(gl::loc(p, "uTsN"), 0); }

void TissueDraw::upload() {
    if (!tex) {
        glGenTextures(1, &tex);
        glActiveTexture(GL_TEXTURE0 + kUnit);
        glBindTexture(GL_TEXTURE_3D, tex);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        half.assign(size_t(kTissueTexXY) * kTissueTexXY * kTissueTexZ * 4, 0);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, kTissueTexXY, kTissueTexXY, kTissueTexZ, 0, GL_RGBA, GL_HALF_FLOAT, half.data());
    } else {
        glActiveTexture(GL_TEXTURE0 + kUnit);
        glBindTexture(GL_TEXTURE_3D, tex);
    }
    static thread_local std::vector<float> buf;
    for (int r = 0; r < kTissueRegions; ++r) {
        if (!on[r]) continue;
        const TissueRegion& g = reg[r];
        buf.resize(size_t(g.count()) * 4);
        g.exportField(buf.data());
        for (int k = 0; k < g.nw(); ++k)
            for (int j = 0; j < g.nv(); ++j)
                for (int i = 0; i < g.nu(); ++i) {
                    const size_t dst = ((size_t(slice[r] + k) * kTissueTexXY + size_t(j)) * kTissueTexXY + size_t(i)) * 4;
                    const size_t src = size_t(g.index(i, j, k)) * 4;
                    for (int c = 0; c < 4; ++c) half[dst + size_t(c)] = tissueHalf(buf[src + size_t(c)]);
                }
    }
    glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, kTissueTexXY, kTissueTexXY, kTissueTexZ, GL_RGBA, GL_HALF_FLOAT, half.data());
    glActiveTexture(GL_TEXTURE0);
    uploaded = true;
    needUpload = false;
}

void TissueDraw::uniforms(unsigned p) const {
    if (usedN <= 0 || !uploaded || !tex) { glUniform1i(gl::loc(p, "uTsN"), 0); return; }
    glActiveTexture(GL_TEXTURE0 + kUnit);
    glBindTexture(GL_TEXTURE_3D, tex);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(gl::loc(p, "uTsTex"), kUnit);
    glUniform1i(gl::loc(p, "uTsN"), usedN);
    glUniform1f(gl::loc(p, "uCtLayer"), 1.0f);
    glUniform1f(gl::loc(p, "uCtShade"), 1.0f);
    glUniform4fv(gl::loc(p, "uTsA"), usedN * 3, uA);
    glUniform4fv(gl::loc(p, "uTsI"), usedN, uI);
}

void Scene::setSoftTissue(int level, float jiggle) {
    tissueLevel_ = std::clamp(level, 0, 2);
    tissueJiggle_ = std::isfinite(jiggle) ? std::clamp(jiggle, 0.0f, 2.0f) : 1.0f;
}

void Scene::setTissueManual(int id, const std::vector<TissueHandle>& list) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.tissue.manual = list;
}

const TissueDraw* Scene::tissueOf(int id) const {
    auto it = actors_.find(id);
    return it == actors_.end() ? nullptr : &it->second.tissue;
}

void Scene::stepTissue(uint32_t clock) {
    const float dt = tissueStarted_ && clock >= tissueClock_ ? std::min(float(clock - tissueClock_) / 1000.0f, 0.25f) : 0.0f;
    tissueClock_ = clock; tissueStarted_ = true;
    static std::unordered_map<const M2Model*, std::array<int, T_COUNT>> cache;
    for (auto& [id, a] : actors_) {
        TissueDraw& td = a.tissue;
        td.usedN = 0; td.handles = 0;
        if (tissueLevel_ <= 0 || !a.visible || !a.character || !a.mesh || !a.mesh->model || a.mats.empty() || a.ghost) continue;
        if ((a.position - eye_).length() > 18.0f) continue;     // too far to see it move
        const M2Model& m = *a.mesh->model;
        auto bc = cache.find(&m);
        if (bc == cache.end()) {
            std::array<int, T_COUNT> idx;
            for (int i = 0; i < T_COUNT; ++i) idx[size_t(i)] = m.findWowBone(kNames[i]);
            bc = cache.emplace(&m, idx).first;
        }
        std::vector<float> fl;
        if (!actorFlesh(id, fl) || fl.size() < 4 || !(fl[1] > 0.05f)) continue;
        RegionDef rd[kTissueRegions];
        defs(m, bc->second, fl, rd);
        const float unit = fl[1] * a.scale;
        const bool rebuild = td.builtLevel != tissueLevel_ || std::fabs(unit - td.builtUnit) > 0.03f * std::max(td.builtUnit, 1e-3f);
        const M4 W = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
        const float sc = a.scale;
        const bool high = tissueLevel_ >= 2;
        // handles of this frame: the touch (raw, so a release lets the tissue ring), the desktop check's grips, and the contacts the
        // stages make (their springs already smooth them)
        TissueHandle hs[16]; int nh = 0;
        const float slider = contactSoftness_;
        auto push = [&](uint32_t key, const V3& pos, const V3& n, float radius, float depth, int region, float grip) {
            if (nh >= 16) return;
            const ContactRegionInfo ri = contactRegion(region);
            TissueHandle h;
            h.key = key; h.rest = pos; h.normal = n; h.radius = radius;
            h.push = std::min(std::max(depth, 0.0f) * ri.softness * slider, ri.maxDepth * slider);
            h.grip = grip;
            if (h.push > 0.0006f) hs[nh++] = h;
        };
        for (int k = 0; k < kTouchPointers; ++k) {
            const ContactDraw::Touch& t = a.contact.touches[k];
            if (!t.on || a.contact.pinches.pinching(k)) continue;
            push(0x7001u + uint32_t(k), t.in.pos, t.in.normal, std::max(t.pad, 0.008f) * 1.4f, t.pressure * 0.04f, t.in.region, 0.6f);   // (a finger of full pressure sinks about 2 cm into the softest flesh)
        }
        // a pinch: the two fingertips are grips that the fold's height lifts and the gathering of the skin between them follows
        if (contactLevel_ != kContactOff)
            for (int i = 0; i < a.contact.solver.pinchCount(); ++i) {
                const PinchOut& po = a.contact.solver.pinchOut()[i];
                for (int e = 0; e < 2 && nh < 16; ++e) {
                    TissueHandle h;
                    h.key = 0x7200u + uint32_t(i * 2 + e); h.rest = e ? po.b : po.a; h.normal = po.normal;
                    h.radius = std::max(po.pad, 0.008f) * 1.4f; h.push = std::max(po.dent, 0.002f);
                    h.pull = po.normal * (po.ridge * 1.0f); h.grip = 1.0f;
                    hs[nh++] = h;
                }
            }
        for (const TissueHandle& h : td.manual) if (nh < 16) hs[nh++] = h;
        if (contactLevel_ != kContactOff)
            for (int i = 0; i < a.contact.solver.count(); ++i) {
                const ContactOut& o = a.contact.solver.out()[i];
                if ((o.key & 0xFFFFFFF0u) == 0x7000u) continue;      // (the fingers are the raw touches above)
                push(o.key | 0x40000000u, o.pos, o.normal, o.radius * 0.5f * (a.hd.active ? 1.0f : 1.4f), o.depth, int(o.softness > 0.5f ? kCrBreast : kCrOther), 0.25f);
            }
        int used = 0;
        bool anyAwake = false;
        for (int r = 0; r < kTissueRegions; ++r) {
            const RegionDef& d = rd[r];
            if (rebuild) td.on[r] = false;
            if (!d.ok) { td.on[r] = false; continue; }
            if (!high && r >= 5) { td.on[r] = false; continue; }     // Low: no thighs
            TissueRegion& g = td.reg[r];
            if (rebuild || !td.on[r]) {
                TissueSpec sp;
                sp.halfU = d.hu * sc; sp.halfV = d.hv * sc; sp.depth = d.depth * sc;
                sp.spacing = high ? 0.036f : 0.05f; sp.mat = d.mat;
                g.build(sp);
                g.setSubsteps(high ? 4 : 3);
                td.on[r] = true;
                td.needUpload = true;
                td.slice[r] = r * kTissueLayers;
                g.setRestDown(V3(-d.u.z, -d.v.z, -d.w.z));
            }
            g.setJiggle(tissueJiggle_ < 0.05f ? 0.05f : tissueJiggle_);
            // the frame: the bone's pose on the region's bind frame
            const M4 M = W * a.mats[size_t(d.bone)];
            const V3 o0 = M.point(d.o);
            V3 o = o0;
            if (a.spring.active()) {
                // the spring bones carry the secondary motion (springbone.h): the lattice rides their deformed rest, so nothing moves twice
                static const SbRegion kOf[kTissueRegions] = {SbRegion::Breast, SbRegion::Breast, SbRegion::Butt, SbRegion::Butt, SbRegion::Belly, SbRegion::Thigh, SbRegion::Thigh};
                static const int kSide[kTissueRegions] = {1, -1, 1, -1, 0, 1, -1};
                V3 so;
                if (a.spring.sim.regionOffset(kOf[r], kSide[r], &so)) o = o + so;
            }
            auto ax = [&](const V3& v) { return (M.point(d.o + v) - o0).normalized(); };
            V3 fu = ax(d.u); const V3 fv = ax(d.v), fw = ax(d.w);
            if (fu.cross(fv).dot(fw) < 0) fu = fu * -1.0f;     // (right-handed: a mirrored frame would turn the cells inside out; the footprint is symmetric)
            g.setFrame(o, fu, fv, fw);
            g.beginHandles();
            for (int i = 0; i < nh; ++i) g.addHandle(hs[i]);
            g.step(dt);
            td.handles += g.asleep() ? 0 : nh;
            if (!g.asleep() || nh > 0) anyAwake = true;
            // the uniforms
            const int e = used++;
            const V3& gu = g.frameU(); const V3& gv = g.frameV(); const V3& gw = g.frameW(); const V3& fo = g.frameOrigin();
            const float iu = 1.0f / g.du(), iv = 1.0f / g.dv(), iw = 1.0f / g.dw();
            float* A = &td.uA[e * 12];
            A[0] = gu.x * iu; A[1] = gu.y * iu; A[2] = gu.z * iu; A[3] = (-fo.dot(gu) + g.halfU()) * iu;
            A[4] = gv.x * iv; A[5] = gv.y * iv; A[6] = gv.z * iv; A[7] = (-fo.dot(gv) + g.halfV()) * iv;
            A[8] = gw.x * iw; A[9] = gw.y * iw; A[10] = gw.z * iw; A[11] = -fo.dot(gw) * iw;
            td.uI[e * 4] = float(g.nu() - 1); td.uI[e * 4 + 1] = float(g.nv() - 1); td.uI[e * 4 + 2] = float(g.nw() - 1);
            td.uI[e * 4 + 3] = float(td.slice[r]);
        }
        td.builtLevel = tissueLevel_; td.builtUnit = unit;
        td.usedN = used;
        if (used > 0 && (anyAwake || td.needUpload || !td.uploaded)) { td.upload(); }
    }
}

} // namespace wc
