// Flesh contact, the pure part (contact.h).
#include "contact.h"

#include <algorithm>
#include <cmath>

namespace wc {

namespace {

float sstep(float a, float b, float x) {
    float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3 - 2 * t);
}
bool finite3(const V3& v) { return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z); }

constexpr ContactRegionInfo kRegions[kCrCount] = {
    {1.00f, 0.040f},   // breast
    {1.00f, 0.040f},   // butt
    {0.80f, 0.032f},   // thigh
    {0.90f, 0.032f},   // belly
    {0.35f, 0.014f},   // hip (bone near)
    {0.15f, 0.007f},   // ribs
    {0.10f, 0.005f},   // shin
    {0.05f, 0.003f},   // skull
    {0.70f, 0.022f},   // groin
    {0.50f, 0.020f},   // arm
    {0.40f, 0.015f},   // other
};

} // namespace

int contactCap(int level, int load) {
    int cap = level <= kContactOff ? 0 : level == kContactLow ? 4 : kContactMax;
    if (load >= 2) return 0;
    if (load == 1) cap = std::min(cap, 4);
    return cap;
}

float fingerDentRadius(float pad) { return kFingerFalloffMul * std::max(pad, 1e-4f); }
float fingerDentDepth(float pad, float pressure) { return kFingerDepthMul * std::max(pad, 0.0f) * std::clamp(std::isfinite(pressure) ? pressure : 0.0f, 0.0f, 1.0f); }
float fingerDentCap(float pad) { return kFingerCapMul * std::max(pad, 0.0f); }

ContactRegionInfo contactRegion(int region) { return kRegions[std::clamp(region, 0, int(kCrCount) - 1)]; }

float contactFalloff(float q) {
    if (!(q < 1.0f)) return 0;
    float k = 1 - q * q;
    return k * k;
}

float contactRing(float q) { return sstep(0.7f, 1.0f, q) * (1 - sstep(1.0f, 1.6f, q)); }

float contactBulgeGain(float volumeShare) {
    static const float ringIntegral = [] {   // the integral of ring(q) q dq, midpoint rule
        double s = 0;
        const int n = 4000;
        for (int i = 0; i < n; ++i) { double q = 0.7 + 0.9 * (i + 0.5) / n; s += contactRing(float(q)) * q * (0.9 / n); }
        return float(s);
    }();
    // dent volume = pi R^2 d / 3; ring volume = 2 pi R^2 b I
    return volumeShare / (6.0f * ringIntegral);
}

V3 contactDisplace(const ContactOut& c, const V3& p, const V3& n, float* blanch, float* flush) {
    if (blanch) *blanch = 0;
    if (flush) *flush = 0;
    V3 d = p - c.pos;
    float r = d.length();
    float q = r / std::max(c.radius, 1e-4f);
    if (q >= 1.6f) return V3();
    float nd = n.dot(c.normal);
    float facing = sstep(-0.1f, 0.45f, nd);     // the far side of a thin part does not move
    float facingRing = sstep(-0.3f, 0.3f, nd);
    float fo = contactFalloff(q);
    V3 out = c.normal * (-(c.depth * fo * facing));
    out = out + c.normal * (c.bulge * contactRing(q) * facingRing);
    // squeeze: the skin round the dent draws toward it, along the surface
    V3 tang = d - c.normal * d.dot(c.normal);
    float press = std::clamp(c.depth / 0.02f, 0.0f, 1.0f);
    out = out + tang * (-(c.squeeze * press * fo * facing));
    if (blanch) *blanch = c.blanch * contactFalloff(q * 1.25f) * facing;
    if (flush) *flush = c.flush * (1 - sstep(0.9f, 1.5f, q)) * (0.45f + 0.55f * sstep(0.1f, 0.7f, q)) * facingRing;
    return out;
}

// ---- the solver ----

void ContactSolver::setSoftness(float s) { softness_ = std::isfinite(s) ? std::clamp(s, 0.0f, 2.0f) : 1.0f; }

void ContactSolver::begin() { in_.clear(); pin_.clear(); }

void ContactSolver::add(const ContactIn& c) {
    if (!finite3(c.pos) || !finite3(c.normal) || !std::isfinite(c.depth) || !std::isfinite(c.radius)) return;
    if (in_.size() < 64) in_.push_back(c);
}

void ContactSolver::addPinch(const PinchIn& p) {
    if (!finite3(p.a) || !finite3(p.b) || !finite3(p.normal) || !std::isfinite(p.pad) || !std::isfinite(p.l0) || !std::isfinite(p.pressure)) return;
    if (pin_.size() < 8) pin_.push_back(p);
}

void ContactSolver::clear() {
    for (Slot& s : slot_) s = Slot();
    for (PSlot& s : pslot_) s = PSlot();
    in_.clear();
    pin_.clear();
    outN_ = 0;
    pinchN_ = 0;
}
float ContactSolver::ridgeOf(uint32_t key) const { for (const PSlot& s : pslot_) if (s.alive && s.key == key) return s.x; return 0; }

int ContactSolver::alive() const { int n = 0; for (const Slot& s : slot_) if (s.alive) ++n; return n; }
float ContactSolver::depthOf(uint32_t key) const { for (const Slot& s : slot_) if (s.alive && s.key == key) return s.x; return 0; }
float ContactSolver::flushOf(uint32_t key) const { for (const Slot& s : slot_) if (s.alive && s.key == key) return s.flush; return 0; }

void ContactSolver::step(float dtSeconds) {
    float dt = std::isfinite(dtSeconds) ? std::clamp(dtSeconds, 0.0f, 0.25f) : 0.0f;
    const int capAll = contactCap(level_, load_);
    for (Slot& s : slot_) s.fed = false;

    // this frame's inputs onto slots (by key; a new one takes a free slot, else the weakest idle one)
    for (const ContactIn& c : in_) {
        Slot* s = nullptr;
        for (Slot& q : slot_) if (q.alive && q.key == c.key) { s = &q; break; }
        const ContactRegionInfo ri = contactRegion(c.region);
        const float radius = std::clamp(c.radius, 0.01f, 0.25f);
        float want = std::min(std::max(c.depth, 0.0f) * ri.softness * softness_, ri.maxDepth * softness_);
        if (c.cap > 0) want = std::min(want, c.cap * std::max(softness_, 0.25f));   // a finger's dent: a few millimetres to 1.5 cm, never more
        if (!s) {
            for (Slot& q : slot_) if (!q.alive) { s = &q; break; }
            if (!s) {
                float weakest = 1e9f;
                for (Slot& q : slot_) if (!q.fed && q.x + q.flush * 0.002f < weakest) { weakest = q.x + q.flush * 0.002f; s = &q; }
                if (!s) continue;
            }
            *s = Slot();
            s->alive = true; s->key = c.key; s->touch = c.touch; s->pos = c.pos; s->normal = c.normal.normalized(); s->radius = radius;
        }
        if (s->fed) { s->target = std::max(s->target, want); continue; }   // two inputs of one key: the deeper
        s->fed = true;
        s->target = want;
        s->region = c.region; s->soft = ri.softness; s->maxDepth = std::max(ri.maxDepth * softness_, 1e-4f);
        const float a = 1 - std::exp(-dt * 35.0f);   // the point follows the tool, a little behind
        s->pos = lerp(s->pos, c.pos, a);
        s->normal = lerp(s->normal, c.normal.normalized(), a).normalized();
        s->radius += (radius - s->radius) * a;
    }

    stepPinches(dt, capAll);

    // the springs
    for (Slot& s : slot_) {
        if (!s.alive) continue;
        if (!s.fed) s.target = 0;
        const bool releasing = s.target < s.x;
        const float hz = releasing ? 3.4f - 0.8f * s.soft : 14.0f - 6.0f * s.soft;
        const float zeta = releasing ? 0.65f + 0.1f * (1 - s.soft) : 1.0f;
        const float w = 6.2831853f * hz;
        const int n = std::max(1, int(std::ceil(dt / 0.004f)));
        const float h = dt / float(n);
        for (int i = 0; i < n; ++i) {
            s.v += (-w * w * (s.x - s.target) - 2 * zeta * w * s.v) * h;
            s.x += s.v * h;
        }
        s.x = std::clamp(s.x, -0.2f * std::max(s.peak, 0.0f), s.maxDepth * 1.5f);   // (the rebound is slight; never past the limit)
        if (!std::isfinite(s.x) || !std::isfinite(s.v)) { s.x = 0; s.v = 0; }
        s.peak = std::max(s.x, s.peak * std::exp(-dt / 0.5f));
        const float p = std::clamp(s.x / (0.6f * s.maxDepth), 0.0f, 1.0f);
        s.flush = std::max(s.flush * std::exp(-dt / 1.4f), p * 0.9f);
        if (!s.fed && std::fabs(s.x) < 0.0004f && std::fabs(s.v) < 0.004f && s.flush < 0.03f) s.alive = false;
    }

    // which are drawn: the strongest `cap`, the rest fading out, the chosen fading in
    int order[kContactSlots], n = 0;
    float pri[kContactSlots];
    for (int i = 0; i < kContactSlots; ++i) {
        if (!slot_[i].alive) continue;
        pri[i] = (std::max(slot_[i].x, 0.0f) + 0.002f) * slot_[i].radius + slot_[i].flush * 0.0005f + (slot_[i].touch ? 1000.0f : 0.0f);   // (a finger first)
        order[n++] = i;
    }
    for (int i = 1; i < n; ++i) {   // (insertion sort, strongest first)
        int v = order[i], j = i - 1;
        while (j >= 0 && pri[order[j]] < pri[v]) { order[j + 1] = order[j]; --j; }
        order[j + 1] = v;
    }
    const float fadeStep = 1 - std::exp(-dt * 8.0f);
    const int cap = std::max(capAll - 2 * pinchN_, 0);   // (a pinch is two fingertips)
    outN_ = 0;
    for (int k = 0; k < n; ++k) {
        Slot& s = slot_[order[k]];
        const bool chosen = k < cap;
        s.fade += ((chosen ? 1.0f : 0.0f) - s.fade) * fadeStep;
        if (cap == 0) s.fade = 0;
        if (s.fade < 0.01f || outN_ >= kContactMax) continue;
        ContactOut o;
        o.pos = s.pos; o.normal = s.normal; o.radius = s.radius;
        o.depth = s.x * s.fade;
        o.softness = s.soft;
        o.touch = s.touch;
        o.bulge = std::max(o.depth, 0.0f) * contactBulgeGain(0.1f + 0.3f * s.soft);   // soft flesh gives its volume to the ring; stiff keeps its own
        o.squeeze = 0.25f * s.soft;
        o.flush = s.flush * s.fade;
        o.blanch = std::clamp(s.x / (0.7f * s.maxDepth), 0.0f, 1.0f) * s.fade;
        // merge into a nearer, stronger one of the same region (two contacts one on the other make one dent)
        bool merged = false;
        for (int j = 0; j < outN_; ++j) {
            ContactOut& m = out_[j];
            // two fingers are two dents until they are nearly one on the other (a pinch's fingertips must not blur into a blob)
            const float mergeAt = (m.touch || o.touch) ? 0.12f : 0.4f;
            if ((m.pos - o.pos).length() < mergeAt * std::min(m.radius, o.radius) && std::fabs(m.softness - o.softness) < 1e-4f) {
                m.depth += 0.6f * o.depth; m.bulge += 0.6f * o.bulge; m.flush = std::max(m.flush, o.flush); m.blanch = std::max(m.blanch, o.blanch);
                m.radius = std::max(m.radius, o.radius);
                merged = true;
                break;
            }
        }
        if (!merged) out_[outN_++] = o;
    }
}


// ---- the pinch ----

float pinchHeightMax(float pad, float softness) {
    return std::max(pad, 0.0f) * (0.55f + 1.2f * std::clamp(std::isfinite(softness) ? softness : 0.0f, 0.0f, 1.4f));
}

float pinchRidgeTarget(float l, float l0, float pad, float softness, float* taut) {
    if (taut) *taut = 0;
    if (!std::isfinite(l) || !std::isfinite(l0) || !std::isfinite(pad) || l0 <= 1e-6f) return 0;
    if (l > l0) { if (taut) *taut = std::clamp((l - l0) / l0 * 3.0f, 0.0f, 1.0f); return 0; }
    const float lc = std::max(l, 0.0f);
    const float gathered = l0 - lc;
    const float tent = 0.5f * std::sqrt(std::max(l0 * l0 - lc * lc, 0.0f));
    const float hGeom = std::min(tent, 1.2f * gathered);
    const float hMax = pinchHeightMax(pad, softness);
    if (!(hMax > 1e-9f)) return 0;
    return hMax * std::tanh(hGeom / hMax);
}

float pinchFoldHeight(float ridge, float l, float pad) { return std::min(std::max(ridge, 0.0f), 1.1f * std::max(l, 0.0f) + 0.3f * pad); }

float pinchFold(float s, float t, float l, float w, float h) {
    const float half = 0.5f * std::max(l, 1e-6f);
    const float x = std::fabs(s) / half, y = t / std::max(w, 1e-6f);
    if (x >= 1.0f || std::fabs(y) >= 1.0f) return 0;
    const float k = 1 - y * y;
    return h * std::pow(1 - std::pow(x, kPinchCrestP), kPinchCrestQ) * k * k;
}

float pinchFoldVertex(float s, float t, float l, float w, float h, float pad) {
    const float half = 0.5f * std::max(l, 1e-6f) + kPinchVertexReach * pad;
    const float x = std::fabs(s) / half, y = t / (kPinchVertexWide * std::max(w, 1e-6f));
    if (x >= 1.0f || std::fabs(y) >= 1.0f) return 0;
    const float k = 1 - y * y;
    return kPinchVertexH * h * std::pow(1 - std::pow(x, kPinchCrestP), kPinchCrestQ) * k * k;
}

V3 pinchDisplace(const PinchOut& c, const V3& p, const V3& n, float* blanch, float* flush, float* taut) {
    if (blanch) *blanch = 0;
    if (flush) *flush = 0;
    if (taut) *taut = 0;
    const V3 N = c.normal;
    const V3 ab0 = c.b - c.a;
    const V3 ab = ab0 - N * ab0.dot(N);                 // the line joining the pads, along the skin
    const float lt = ab.length();
    V3 u = lt > 1e-7f ? ab * (1.0f / lt) : V3();
    if (lt <= 1e-7f) { V3 any = std::fabs(N.x) < 0.9f ? V3(1, 0, 0) : V3(0, 1, 0); u = (any - N * any.dot(N)).normalized(); }
    const V3 v = N.cross(u).normalized();
    const float pad = std::max(c.pad, 1e-5f);
    const float L = std::max(lt, 0.5f * pad);           // (fingertips on one point: a fold half a pad long)
    const V3 mid = (c.a + c.b) * 0.5f;
    const V3 r = p - mid;
    const float s = r.dot(u), t = r.dot(v);
    const float w = std::max(c.radius, 1e-5f);
    const float Re = kPinchReachMul * pad, Rt = w + 2.0f * pad;
    const float e = std::max(std::fabs(s) - 0.5f * L, 0.0f);
    if (e >= Re && std::fabs(s) > 0.5f * L) return V3();
    if (std::fabs(t) >= Rt) return V3();
    const float facing = sstep(-0.1f, 0.45f, n.dot(N));
    const float H = pinchFoldHeight(c.ridge, L, pad);
    float height = pinchFoldVertex(s, t, L, w, H, pad);
    const float Rf = 0.9f * pad;
    height -= c.dent * (contactFalloff((p - c.a).length() / Rf) + contactFalloff((p - c.b).length() / Rf));
    V3 out = N * (height * facing);
    // the skin drawn toward the crest line along s: strongest at the pads, fading outside them and across
    const float press = std::clamp(c.ridge / std::max(2.0f * pad, 1e-5f), 0.0f, 1.0f);
    const float m0 = c.squeeze * press * Re;
    const float D = std::fabs(s) <= 0.5f * L ? m0 * std::fabs(s) / (0.5f * L) : m0 * contactFalloff(e / Re);
    out = out - u * ((s >= 0 ? 1.0f : -1.0f) * D * contactFalloff(std::fabs(t) / Rt) * facing);
    // the fold leans toward gravity (as far as the skin lets it): along the surface, by its height
    V3 g(0, 0, -1);
    g = g - N * g.dot(N);
    out = out + g * (std::max(height, 0.0f) * c.lean * facing);
    const float fs = std::fabs(s) < 0.5f * L ? std::pow(1 - std::pow(std::fabs(s) / (0.5f * L), kPinchCrestP), kPinchCrestQ) : 0.0f;
    const float ft = std::fabs(t) < w ? (1 - (t / w) * (t / w)) : 0.0f;
    if (blanch) *blanch = std::clamp(c.blanch, 0.0f, 1.0f) * sstep(0.30f, 0.85f, fs) * std::sqrt(std::max(ft, 0.0f)) * facing;
    if (flush) {
        const float Rp = 0.9f * pad;
        *flush = c.flush * std::max(contactFalloff((p - c.a).length() / Rp), contactFalloff((p - c.b).length() / Rp)) * facing;
    }
    if (taut) *taut = c.taut * contactFalloff(std::fabs(t) / (1.4f * Rt)) * (fs > 0 || e < Re ? 1.0f : 0.0f) * facing;
    return out;
}

bool PinchTracker::pinching(int pointer) const {
    for (const Pair& p : pair_) if (p.used && p.latched && (p.pa == pointer || p.pb == pointer)) return true;
    return false;
}

int PinchTracker::update(const PinchTouch* t, int n, float wpm, PinchIn* out, uint32_t keyBase) {
    if (!(wpm > 0) || !std::isfinite(wpm)) wpm = 1.0f / kYardM;
    auto find = [&](int pointer) -> const PinchTouch* { for (int i = 0; i < n; ++i) if (t[i].pointer == pointer) return &t[i]; return nullptr; };
    auto taken = [&](int pointer) { for (const Pair& p : pair_) if (p.used && (p.pa == pointer || p.pb == pointer)) return true; return false; };
    for (Pair& p : pair_) {
        if (!p.used) continue;
        const PinchTouch* a = find(p.pa);
        const PinchTouch* b = find(p.pb);
        if (!a || !b || !finite3(a->pos) || !finite3(b->pos)) { p = Pair(); continue; }
        const float d = (a->pos - b->pos).length();
        if (d > kPinchKeepM * wpm) { p = Pair(); continue; }
        if (!p.latched) {
            p.l0 = std::max(p.l0, d);
            if (d <= p.l0 - kPinchOnsetM * wpm) p.latched = true;
        }
    }
    // new candidates, nearest first
    for (int round = 0; round < kPinchMax; ++round) {
        int slot = -1;
        for (int i = 0; i < kPinchMax; ++i) if (!pair_[i].used) { slot = i; break; }
        if (slot < 0) break;
        float best = 1e9f;
        int bi = -1, bj = -1;
        for (int i = 0; i < n; ++i) {
            if (taken(t[i].pointer) || !finite3(t[i].pos) || contactRegion(t[i].region).softness < kPinchSoftMin) continue;
            for (int j = i + 1; j < n; ++j) {
                if (taken(t[j].pointer) || !finite3(t[j].pos) || contactRegion(t[j].region).softness < kPinchSoftMin) continue;
                const float d = (t[i].pos - t[j].pos).length();
                if (d < kPinchMinM * wpm || d > kPinchMaxM * wpm) continue;
                if (t[i].normal.normalized().dot(t[j].normal.normalized()) < 0.3f) continue;
                if (d < best) { best = d; bi = i; bj = j; }
            }
        }
        if (bi < 0) break;
        Pair& p = pair_[slot];
        p.used = true; p.latched = false;
        p.pa = std::min(t[bi].pointer, t[bj].pointer); p.pb = std::max(t[bi].pointer, t[bj].pointer);
        p.l0 = best;
    }
    int m = 0;
    for (int i = 0; i < kPinchMax; ++i) {
        const Pair& p = pair_[i];
        if (!p.used || !p.latched) continue;
        const PinchTouch* a = find(p.pa);
        const PinchTouch* b = find(p.pb);
        if (!a || !b) continue;
        PinchIn in;
        in.key = keyBase + uint32_t(i);
        in.a = a->pos; in.b = b->pos;
        V3 nn = a->normal.normalized() + b->normal.normalized();
        in.normal = nn.length() > 1e-4f ? nn.normalized() : a->normal.normalized();
        in.pad = 0.5f * (a->pad + b->pad);
        in.l0 = p.l0;
        in.pressure = 0.5f * (a->pressure + b->pressure);
        in.region = contactRegion(a->region).softness >= contactRegion(b->region).softness ? a->region : b->region;
        out[m++] = in;
    }
    return m;
}

void ContactSolver::stepPinches(float dt, int capAll) {
    for (PSlot& s : pslot_) s.fed = false;
    for (const PinchIn& c : pin_) {
        PSlot* s = nullptr;
        for (PSlot& q : pslot_) if (q.alive && q.key == c.key) { s = &q; break; }
        if (!s) {
            for (PSlot& q : pslot_) if (!q.alive) { s = &q; break; }
            if (!s) continue;
            *s = PSlot();
            s->alive = true; s->key = c.key; s->a = c.a; s->b = c.b; s->normal = c.normal.normalized(); s->pad = c.pad;
        }
        if (s->fed) continue;
        s->fed = true;
        const ContactRegionInfo ri = contactRegion(c.region);
        s->soft = ri.softness;
        s->region = c.region;
        s->press += (std::clamp(c.pressure, 0.0f, 1.0f) - s->press) * (1 - std::exp(-dt * 14.0f));
        const float a = 1 - std::exp(-dt * 35.0f);
        s->a = lerp(s->a, c.a, a); s->b = lerp(s->b, c.b, a);
        s->normal = lerp(s->normal, c.normal.normalized(), a).normalized();
        s->pad += (c.pad - s->pad) * a;
        float taut = 0;
        s->target = pinchRidgeTarget((c.a - c.b).length(), c.l0, c.pad, ri.softness * softness_, &taut);
        s->tautTarget = taut;
    }
    pinchN_ = 0;
    PSlot* drawn[kPinchMax];
    for (PSlot& s : pslot_) {
        if (!s.alive) continue;
        if (!s.fed) { s.target = 0; s.tautTarget = 0; s.press += (0 - s.press) * (1 - std::exp(-dt * 14.0f)); }
        const bool releasing = s.target < s.x;
        const float hz = releasing ? 3.4f - 0.8f * s.soft : 14.0f - 6.0f * s.soft;
        const float zeta = releasing ? 0.65f + 0.1f * (1 - s.soft) : 1.0f;
        const float w = 6.2831853f * hz;
        const int n = std::max(1, int(std::ceil(dt / 0.004f)));
        const float h = dt / float(n);
        for (int i = 0; i < n; ++i) {
            s.v += (-w * w * (s.x - s.target) - 2 * zeta * w * s.v) * h;
            s.x += s.v * h;
        }
        s.x = std::clamp(s.x, -0.1f * s.pad, 2.8f * s.pad);
        if (!std::isfinite(s.x) || !std::isfinite(s.v)) { s.x = 0; s.v = 0; }
        s.taut += (s.tautTarget - s.taut) * (1 - std::exp(-dt * 10.0f));
        const float p = std::clamp(s.x / (1.5f * s.pad), 0.0f, 1.0f);
        s.flush = std::max(s.flush * std::exp(-dt / 1.4f), std::max(p * 0.9f, s.fed ? 0.35f * s.press : 0.0f));
        if (!s.fed && std::fabs(s.x) < 0.03f * s.pad && std::fabs(s.v) < 0.1f * s.pad && s.flush < 0.03f && s.taut < 0.02f) s.alive = false;
        const bool chosen = capAll >= 2 * (pinchN_ + 1);
        s.fade += ((chosen ? 1.0f : 0.0f) - s.fade) * (1 - std::exp(-dt * 8.0f));
        if (s.fade < 0.01f) continue;
        if (pinchN_ < kPinchMax) drawn[pinchN_++] = &s;
    }
    for (int i = 0; i < pinchN_; ++i) {
        const PSlot& s = *drawn[i];
        PinchOut& o = pinchOut_[i];
        o.a = s.a; o.b = s.b; o.normal = s.normal; o.pad = s.pad;
        o.radius = kPinchWidthMul * s.pad;
        o.ridge = std::max(s.x, 0.0f) * s.fade;
        o.dent = 0.25f * s.pad * s.press * std::clamp(s.soft * softness_, 0.0f, 1.2f) * s.fade;
        o.squeeze = 0.22f * s.soft;
        o.lean = kPinchLean * s.soft;
        o.ripple = std::clamp(s.x / std::max(pinchHeightMax(s.pad, 1.0f), 1e-6f), 0.0f, 1.0f) * s.fade;
        o.flush = s.flush * s.fade;
        o.blanch = std::clamp(s.x / (1.5f * s.pad), 0.0f, 1.0f) * s.fade;
        o.taut = s.taut * s.fade;
    }
}

// ---- the pick's region ----

int contactRegionForPick(int code, float h, float across, bool front) {
    switch (code) {
        case 1: case 2: return kCrSkull;              // head, hair
        case 3: return kCrOther;                      // neck
        case 4: return front ? kCrBreast : kCrRibs;   // chest
        case 5: return front ? kCrBelly : kCrRibs;    // waist
        case 6:                                       // hips
            if (!front) return kCrButt;
            if (h >= kGroinLow && h < kGroinHigh && std::fabs(across) < kGroinHalfWidth) return kCrGroin;
            return h >= 0.56f ? kCrBelly : kCrHip;
        case 7:                                       // legs
            if (!front && h >= 0.43f) return kCrButt;
            if (front && h >= kGroinLow && h < kGroinHigh && std::fabs(across) < kGroinHalfWidth) return kCrGroin;
            return h >= 0.33f ? kCrThigh : kCrShin;
        case 8: return kCrArm;
        case 9: return kCrOther;
        case 10: return kCrShin;
        default: return kCrOther;
    }
}

// ---- derivation ----

float contactSegment(const V3& p, const V3& a, const V3& b, V3* nearest) {
    V3 ab = b - a;
    float l2 = ab.dot(ab);
    float t = l2 > 1e-10f ? std::clamp((p - a).dot(ab) / l2, 0.0f, 1.0f) : 0.0f;
    V3 q = a + ab * t;
    if (nearest) *nearest = q;
    return (p - q).length();
}

int deriveContacts(const ContactTool* tools, int nt, const ContactCapsule* caps, int nc, uint32_t bodyKey, ContactIn* out, int max) {
    int n = 0;
    for (int i = 0; i < nt && n < max; ++i) {
        const ContactTool& t = tools[i];
        if (!finite3(t.p)) continue;
        float best = 1e9f;
        int bi = -1;
        V3 bq;
        for (int k = 0; k < nc; ++k) {
            V3 q;
            float d = contactSegment(t.p, caps[k].a, caps[k].b, &q);
            float gap = d - caps[k].r - t.radius;
            if (gap < best) { best = gap; bi = k; bq = q; }
        }
        if (bi < 0 || best >= kContactReach) continue;
        V3 dir = t.p - bq;
        dir = dir.length() > 1e-6f ? dir.normalized() : V3(0, 0, 1);
        ContactIn c;
        c.key = bodyKey * 2654435761u ^ (t.id + 1) * 40503u;
        c.pos = bq + dir * caps[bi].r;
        c.normal = dir;
        c.radius = t.spread;
        c.depth = (kContactReach - best) * std::clamp(t.press, 0.0f, 1.5f);
        c.region = caps[bi].region;
        out[n++] = c;
    }
    return n;
}

} // namespace wc
