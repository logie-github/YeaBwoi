// Flesh contact in the scene (contact.h, contactdraw.h): the proxies of each body from its posed bones and measured flesh, the tools
// that press (hands, mouth, pelvis, thighs...), the contacts derived between every pair of people close enough (no per-stage data),
// Interact's touch, the insertion's entry, and the uniform block the vertex shader takes.
#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <unordered_map>

#include "bodypick.h"
#include "contactdraw.h"
#include "glutil.h"
#include "scene.h"

namespace wc {

void ContactDraw::uniforms(unsigned p) const {
    const int n = std::min(solver.count(), kContactMax);
    glUniform1i(gl::loc(p, "uCtN"), n);
    glUniform1f(gl::loc(p, "uCtLayer"), 1.0f);
    glUniform1f(gl::loc(p, "uCtShade"), 1.0f);
    if (n <= 0) return;
    float a[kContactMax * 4] = {}, b[kContactMax * 4] = {}, c[kContactMax * 4] = {};
    for (int i = 0; i < n; ++i) {
        const ContactOut& o = solver.out()[i];
        a[i * 4] = o.pos.x; a[i * 4 + 1] = o.pos.y; a[i * 4 + 2] = o.pos.z; a[i * 4 + 3] = o.radius * radiusScale;
        b[i * 4] = o.normal.x; b[i * 4 + 1] = o.normal.y; b[i * 4 + 2] = o.normal.z; b[i * 4 + 3] = o.depth;
        c[i * 4] = o.bulge; c[i * 4 + 1] = o.squeeze; c[i * 4 + 2] = o.flush; c[i * 4 + 3] = o.blanch;
    }
    glUniform4fv(gl::loc(p, "uCtA"), n, a);
    glUniform4fv(gl::loc(p, "uCtB"), n, b);
    glUniform4fv(gl::loc(p, "uCtC"), n, c);
}

void ContactDraw::off(unsigned p) { glUniform1i(gl::loc(p, "uCtN"), 0); }

void ContactDraw::layer(unsigned p, float scale, float shade) {
    glUniform1f(gl::loc(p, "uCtLayer"), scale);
    glUniform1f(gl::loc(p, "uCtShade"), shade);
}

void Scene::setFleshContact(int level, float softness) {
    contactLevel_ = std::clamp(level, int(kContactOff), int(kContactHigh));
    contactSoftness_ = std::isfinite(softness) ? std::clamp(softness, 0.0f, 2.0f) : 1.0f;
}
void Scene::setContactLoad(int load) { contactLoad_ = std::clamp(load, 0, 2); }

const ContactDraw* Scene::contactOf(int id) const {
    auto it = actors_.find(id);
    return it == actors_.end() ? nullptr : &it->second.contact;
}

void Scene::setContactManual(int id, const std::vector<ContactIn>& list) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.contact.manual = list;
}

void Scene::resetContacts(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    it->second.contact.solver.clear();
    it->second.contact.touch.on = false;
    it->second.contact.manual.clear();
}

void Scene::releaseTouch(int id) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.contact.touch.on = false;
}

int Scene::pushTouch(int id, float sx, float sy, int width, int height, float pressure, int holdMs) {
    auto it = actors_.find(id);
    if (it == actors_.end() || width <= 0 || height <= 0) return -1;
    Actor& a = it->second;
    if (!a.mesh || !a.mesh->model || !a.visible || !a.character) return -1;
    const M2Model& m = *a.mesh->model;
    // the camera's ray through the point, into the actor's model space (as pickBodyDetail)
    V3 f = (target_ - eye_).normalized();
    V3 s = f.cross(V3(0, 0, 1)).normalized();
    V3 u = s.cross(f);
    float th = std::tan(fov_ * 3.14159265f / 360.0f), aspect = float(width) / float(height);
    float nx = 2.0f * sx / float(width) - 1.0f, ny = 1.0f - 2.0f * sy / float(height);
    V3 d = (f + s * (nx * th * aspect) + u * (ny * th)).normalized();
    V3 rel = eye_ - a.position;
    float c = std::cos(-a.facing), sn = std::sin(-a.facing);
    auto unturn = [&](const V3& v) { return V3(v.x * c - v.y * sn, v.x * sn + v.y * c, v.z); };
    float scale = a.scale > 1e-6f ? a.scale : 1.0f;
    V3 o = unturn(rel) * (1.0f / scale), dm = unturn(d);
    const bool hd = a.hd.active && a.hd.fit.bodyVertexEnd > 0;
    const BodyGl& b = a.body;
    const bool own = !b.shaped.empty() && !b.indices.empty() && !b.submeshes.empty();
    std::vector<M2Submesh> hdSubs;
    if (hd) { hdSubs.resize(1); hdSubs[0].indexCount = uint32_t(a.hd.fit.bodyIndices.size()); }
    const std::vector<M2Vertex>& verts = hd ? a.hd.fit.verts : own ? b.shaped : m.vertices;
    const std::vector<uint32_t>& idx = hd ? a.hd.fit.bodyIndices : own ? b.indices : m.indices;
    const std::vector<M2Submesh>& subs = hd ? hdSubs : own ? b.submeshes : m.submeshes;
    if (boneRegionsModel_ != &m) { boneRegions_ = boneRegionsOf(m); boneRegionsModel_ = &m; }
    BodyPickHit hit = pickBody(verts, idx, subs, hd ? std::vector<uint16_t>{0} : a.geosets, a.mats, boneRegions_, o, dm);
    if (hit.region == kRegionNone || hit.region == kRegionHair) return -1;
    const int head = m.findWowBone("Head"), pelvis = m.findWowBone("Pelvis");
    const float unit = std::max(pelvis >= 0 ? m.bones[size_t(pelvis)].pivot.z : 1.0f, 0.1f);
    const float top = head >= 0 ? std::max(m.bones[size_t(head)].pivot.z, 0.1f) : 1.7f * unit;
    const M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(scale, scale, scale));
    const bool front = dm.x < 0;
    const int region = contactRegionForPick(int(hit.region), hit.point.z / top, hit.point.y / unit, front);
    ContactIn in;
    in.key = 0x7001u;
    in.pos = w.point(hit.point);
    // pressed along the skin's own normal there (the hit triangle's, turned toward the eye): along the view it would slide a grazing
    // surface sideways instead of pressing it in
    in.normal = (w.point(hit.point + hit.normal) - in.pos).normalized();
    in.radius = 0.035f * (unit * scale / 0.9f);
    in.depth = std::clamp(pressure, 0.0f, 1.0f) * 0.03f;
    in.region = region;
    a.contact.touch.on = true;
    a.contact.touch.in = in;
    a.contact.touch.lastMs = animClock_;
    a.contact.touch.holdMs = uint32_t(std::clamp(holdMs, 50, 5000));
    return region;
}

namespace {

enum Bn { B_ROOT, B_PELVIS, B_SPINE1, B_SPINE2, B_CHEST, B_NECK, B_HEAD,
          B_LTHIGH, B_RTHIGH, B_LCALF, B_RCALF, B_LFOOT, B_RFOOT, B_LUARM, B_RUARM, B_LFARM, B_RFARM, B_LHAND, B_RHAND,
          B_LBREAST, B_RBREAST, B_LMID, B_RMID, B_LTHUMB, B_RTHUMB, B_COUNT };
const char* const kBoneNames[B_COUNT] = {"Root", "Pelvis", "Spine1", "Spine2", "Chest", "Neck1", "Head",
    "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Foot", "R_Foot", "L_UpperArm", "R_UpperArm", "L_Forearm", "R_Forearm", "L_Hand", "R_Hand",
    "L_Breast", "R_Breast", "L_Middle2", "R_Middle2", "L_Thumb2", "R_Thumb2"};

struct Proxies {
    bool ok = false;
    V3 pelvis;
    ContactCapsule caps[24]; int nc = 0;
    ContactTool tools[24]; int nt = 0;
};

// FleshMeasure::pack's layout: 4 header floats, then 7 per segment (ok, front, back, side, start, end, tip).
struct Seg { bool ok; float front, back, side; };
Seg segOf(const std::vector<float>& f, int s) {
    const size_t o = 4 + size_t(s) * 7;
    if (f.size() < o + 7) return {false, 0, 0, 0};
    return {f[o] > 0.5f, f[o + 1], f[o + 2], f[o + 3]};
}

} // namespace

void Scene::stepContacts(uint32_t clock) {
    const float dt = contactStarted_ && clock >= contactClock_ ? std::min(float(clock - contactClock_) / 1000.0f, 0.25f) : 0.0f;
    contactClock_ = clock; contactStarted_ = true;
    static std::unordered_map<const M2Model*, std::array<int, B_COUNT>> boneCache;
    std::unordered_map<int, Proxies> prox;
    for (auto& [id, a] : actors_) {
        ContactDraw& cd = a.contact;
        cd.solver.setLevel(contactLevel_);
        cd.solver.setSoftness(contactSoftness_);
        cd.solver.setLoad(contactLoad_);
        cd.solver.begin();
        cd.derived = 0;
        cd.radiusScale = a.hd.active ? 1.0f : 2.0f;
    }
    if (contactLevel_ != kContactOff && contactLoad_ < 2) {
        // proxies of everyone who is a posed character
        for (auto& [id, a] : actors_) {
            if (!a.visible || !a.character || !a.mesh || !a.mesh->model || a.mats.empty() || a.ghost) continue;
            const M2Model& m = *a.mesh->model;
            auto bc = boneCache.find(&m);
            if (bc == boneCache.end()) {
                std::array<int, B_COUNT> idx;
                for (int i = 0; i < B_COUNT; ++i) idx[size_t(i)] = m.findWowBone(kBoneNames[i]);
                bc = boneCache.emplace(&m, idx).first;
            }
            const auto& bi = bc->second;
            if (bi[B_PELVIS] < 0 || bi[B_HEAD] < 0 || bi[B_LTHIGH] < 0 || bi[B_RTHIGH] < 0) continue;
            std::vector<float> flesh;
            if (!actorFlesh(id, flesh) || flesh.size() < 4) continue;
            const float k = flesh[1] * a.scale;      // one body unit (the standing pelvis height) in the world
            if (!(k > 0.05f)) continue;
            const M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
            auto have = [&](int b) { return b >= 0 && size_t(b) < a.mats.size(); };
            auto P = [&](int name, V3 off = V3()) { int b = bi[size_t(name)]; return w.point(a.mats[size_t(b)].point(m.bones[size_t(b)].pivot + off * flesh[1])); };
            Proxies& px = prox[id];
            px.ok = true;
            px.pelvis = P(B_PELVIS);
            // the body's frame at the pelvis: forward and up in the world (the bone's turn applied to the bind axes)
            const V3 fwd = (P(B_PELVIS, V3(1, 0, 0)) - P(B_PELVIS)).normalized();
            const V3 up = (P(B_PELVIS, V3(0, 0, 1)) - P(B_PELVIS)).normalized();
            auto addCap = [&](V3 p0, V3 p1, float r, int region) {
                if (px.nc < 24 && r > 0.004f) px.caps[px.nc++] = ContactCapsule{p0, p1, r, region};
            };
            auto addTool = [&](V3 p, float radius, float spread, float press, uint32_t tid) {
                if (px.nt < 24) { ContactTool t; t.p = p; t.radius = radius; t.spread = spread; t.press = press; t.id = tid; px.tools[px.nt++] = t; }
            };
            const Seg pel = segOf(flesh, kFleshPelvis), spine = segOf(flesh, kFleshSpine), chest = segOf(flesh, kFleshChest);
            const V3 lt = P(B_LTHIGH), rt = P(B_RTHIGH);
            // ---- regions (nearest-first: the first of equal reach wins)
            if (have(bi[B_LBREAST]) && have(bi[B_RBREAST])) {
                addCap(P(B_LBREAST), P(B_LBREAST), 0.1f * k, kCrBreast);
                addCap(P(B_RBREAST), P(B_RBREAST), 0.1f * k, kCrBreast);
            }
            if (pel.ok) {
                const float rb = 0.55f * pel.back * k;
                addCap(lt - fwd * (pel.back * k - rb) + up * (0.05f * k), rt - fwd * (pel.back * k - rb) + up * (0.05f * k), rb, kCrButt);
                const float rh = 0.5f * (pel.front + pel.back) * k;
                const V3 shift = fwd * (0.5f * (pel.front - pel.back) * k);
                addCap(lt + shift + up * (0.12f * k), rt + shift + up * (0.12f * k), rh, kCrHip);
                addCap(px.pelvis + fwd * (pel.front * 0.55f * k) - up * (0.2f * k), px.pelvis + fwd * (pel.front * 0.55f * k) - up * (0.2f * k), 0.07f * k, kCrGroin);
            }
            if (spine.ok && have(bi[B_SPINE1]) && have(bi[B_CHEST])) {
                const float r = 0.5f * spine.front * k;
                addCap(P(B_SPINE1) + fwd * r, P(B_CHEST) + fwd * r, std::max(r, 0.08f * k), kCrBelly);
            }
            if (chest.ok && have(bi[B_CHEST]) && have(bi[B_NECK])) {
                const float r = 0.5f * (chest.front + chest.back) * k;
                addCap(P(B_CHEST) + fwd * (0.5f * (chest.front - chest.back) * k), P(B_NECK) + fwd * (0.5f * (chest.front - chest.back) * k), r, kCrRibs);
            }
            for (int sd = 0; sd < 2; ++sd) {
                const int th = sd ? B_RTHIGH : B_LTHIGH, ca = sd ? B_RCALF : B_LCALF, ft = sd ? B_RFOOT : B_LFOOT;
                const int ua = sd ? B_RUARM : B_LUARM, fa = sd ? B_RFARM : B_LFARM, hd = sd ? B_RHAND : B_LHAND;
                const Seg ts = segOf(flesh, sd ? kFleshRThigh : kFleshLThigh), cs = segOf(flesh, sd ? kFleshRCalf : kFleshLCalf);
                const Seg us = segOf(flesh, sd ? kFleshRUpperArm : kFleshLUpperArm), fs = segOf(flesh, sd ? kFleshRForearm : kFleshLForearm);
                if (have(bi[size_t(th)]) && have(bi[size_t(ca)]) && ts.ok) addCap(P(th), P(ca), 0.5f * (ts.front + ts.back) * k * 0.9f, kCrThigh);
                if (have(bi[size_t(ca)]) && have(bi[size_t(ft)]) && cs.ok) addCap(P(ca), P(ft), 0.5f * (cs.front + cs.back) * k * 0.9f, kCrShin);
                if (have(bi[size_t(ua)]) && have(bi[size_t(fa)]) && us.ok) addCap(P(ua), P(fa), us.side * k, kCrArm);
                if (have(bi[size_t(fa)]) && have(bi[size_t(hd)]) && fs.ok) addCap(P(fa), P(hd), fs.side * k, kCrArm);
                // ---- tools
                if (have(bi[size_t(hd)]) && have(bi[size_t(fa)])) {
                    const V3 hp = P(hd), dir = (hp - P(fa)).normalized();
                    addTool(hp + dir * (0.07f * k), 0.06f * k, 0.09f * k, 1.0f, 100u + uint32_t(sd));                  // the palm
                    const int mid = sd ? B_RMID : B_LMID, thu = sd ? B_RTHUMB : B_LTHUMB;
                    if (have(bi[size_t(mid)])) addTool(P(mid) + dir * (0.03f * k), 0.018f * k, 0.04f * k, 1.0f, 110u + uint32_t(sd));   // the fingers
                    if (have(bi[size_t(thu)])) addTool(P(thu), 0.02f * k, 0.04f * k, 1.0f, 120u + uint32_t(sd));       // the thumb
                }
                if (have(bi[size_t(th)]) && have(bi[size_t(ca)])) {
                    addTool(lerp(P(th), P(ca), 0.35f), 0.07f * k, 0.08f * k, 0.6f, 130u + uint32_t(sd));               // the thigh
                    addTool(P(ca), 0.05f * k, 0.06f * k, 0.5f, 140u + uint32_t(sd));                                    // the knee
                }
                if (have(bi[size_t(sd ? B_RBREAST : B_LBREAST)])) addTool(P(sd ? B_RBREAST : B_LBREAST, V3(0.07f, 0, 0)), 0.08f * k, 0.09f * k, 0.5f, 150u + uint32_t(sd));
            }
            // the head (a stiff region) and the mouth, the pelvis and the chest as tools
            if (have(bi[B_NECK]) && have(bi[B_HEAD])) {
                const Seg hs = segOf(flesh, kFleshHead);
                const V3 hp = P(B_HEAD);
                addCap(P(B_NECK), hp + (hp - P(B_NECK)).normalized() * (0.12f * k), std::max(0.5f * (hs.front + hs.back), 0.12f) * k * 0.9f, kCrSkull);
                const V3 hf = (P(B_HEAD, V3(1, 0, 0)) - hp).normalized(), hu = (P(B_HEAD, V3(0, 0, 1)) - hp).normalized();
                addTool(hp + hf * (0.105f * k) - hu * (0.07f * k), 0.035f * k, 0.04f * k, 0.8f, 160u);     // the mouth
            }
            if (pel.ok) addTool(px.pelvis + fwd * (pel.front * 0.7f * k) - up * (0.2f * k), 0.1f * k, 0.09f * k, 0.6f, 170u);   // the pelvis
            if (spine.ok && have(bi[B_SPINE2])) addTool(P(B_SPINE2) + fwd * (spine.front * 0.8f * k), 0.1f * k, 0.1f * k, 0.5f, 180u);   // the belly and chest
        }
        // ---- contacts between every pair close enough: a's tools on b's regions and b's on a's
        for (auto& [ia, pa] : prox) {
            for (auto& [ib, pb] : prox) {
                if (ia == ib || (pa.pelvis - pb.pelvis).length() > 1.6f) continue;
                Actor& target = actors_.at(ib);
                ContactIn buf[24];
                const int n = deriveContacts(pa.tools, pa.nt, pb.caps, pb.nc, uint32_t(ib), buf, 24);
                for (int i = 0; i < n; ++i) { buf[i].key ^= uint32_t(ia) * 0x9E3779B1u; target.contact.solver.add(buf[i]); }
                target.contact.derived += n;
            }
        }
    }
    // ---- touch, the desktop check's presses, the insertion's entry
    for (auto& [id, a] : actors_) {
        ContactDraw& cd = a.contact;
        if (cd.touch.on && clock - cd.touch.lastMs > cd.touch.holdMs) cd.touch.on = false;
        if (contactLevel_ != kContactOff && contactLoad_ < 2) {
            if (cd.touch.on) cd.solver.add(cd.touch.in);
            for (const ContactIn& c : cd.manual) cd.solver.add(c);
            // the shaft pushing in: a dimple at the partner's entry, deeper with the insertion, a ring round it (the solver's bulge)
            const ActorParts& ap = a.parts;
            if (ap.partner && ap.entry != kEntryNone && ap.level >= kPartSwapDown && ap.distance >= 0 && ap.depth > 0.0005f) {
                auto pt = actors_.find(ap.partner);
                V3 at, inward;
                if (pt != actors_.end() && pt->second.character && entryWorld(ap.partner, ap.entry, at, &inward)) {
                    const PartSlot& sl = ap.slots[ap.slotOn ? 1 : 0];
                    const M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
                    const M4 dm = w * sl.drawn;
                    float sc = (dm.point(V3(1, 0, 0)) - dm.point(V3())).length();
                    if (!(sc > 1e-4f)) sc = a.scale;
                    const float depthW = ap.depth * sc;
                    int region = ap.entry == kEntryVaginal ? kCrGroin : ap.entry == kEntryAnal ? kCrButt : ap.entry == kEntryThigh ? kCrThigh : ap.entry == kEntryChest ? kCrBreast : -1;
                    if (region >= 0) {
                        ContactIn c;
                        c.key = 0xE000u + uint32_t(id);
                        c.pos = at; c.normal = inward * -1.0f;
                        const EntryAnchor& ea = pt->second.parts.entries.at[ap.entry];
                        c.radius = std::max(0.03f, 2.2f * ea.radius * pt->second.scale);
                        c.depth = std::min(0.006f + 0.3f * depthW, 0.03f);
                        c.region = region;
                        pt->second.contact.solver.add(c);
                    }
                }
            }
        }
    }
    for (auto& [id, a] : actors_) a.contact.solver.step(dt);
}

} // namespace wc
