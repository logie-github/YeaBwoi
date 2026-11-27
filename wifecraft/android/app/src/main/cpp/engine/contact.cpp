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

void ContactSolver::begin() { in_.clear(); }

void ContactSolver::add(const ContactIn& c) {
    if (!finite3(c.pos) || !finite3(c.normal) || !std::isfinite(c.depth) || !std::isfinite(c.radius)) return;
    if (in_.size() < 64) in_.push_back(c);
}

void ContactSolver::clear() {
    for (Slot& s : slot_) s = Slot();
    in_.clear();
    outN_ = 0;
}

int ContactSolver::alive() const { int n = 0; for (const Slot& s : slot_) if (s.alive) ++n; return n; }
float ContactSolver::depthOf(uint32_t key) const { for (const Slot& s : slot_) if (s.alive && s.key == key) return s.x; return 0; }
float ContactSolver::flushOf(uint32_t key) const { for (const Slot& s : slot_) if (s.alive && s.key == key) return s.flush; return 0; }

void ContactSolver::step(float dtSeconds) {
    float dt = std::isfinite(dtSeconds) ? std::clamp(dtSeconds, 0.0f, 0.25f) : 0.0f;
    const int cap = contactCap(level_, load_);
    for (Slot& s : slot_) s.fed = false;

    // this frame's inputs onto slots (by key; a new one takes a free slot, else the weakest idle one)
    for (const ContactIn& c : in_) {
        Slot* s = nullptr;
        for (Slot& q : slot_) if (q.alive && q.key == c.key) { s = &q; break; }
        const ContactRegionInfo ri = contactRegion(c.region);
        const float radius = std::clamp(c.radius, 0.01f, 0.25f);
        const float want = std::min(std::max(c.depth, 0.0f) * ri.softness * softness_, ri.maxDepth * softness_);
        if (!s) {
            for (Slot& q : slot_) if (!q.alive) { s = &q; break; }
            if (!s) {
                float weakest = 1e9f;
                for (Slot& q : slot_) if (!q.fed && q.x + q.flush * 0.002f < weakest) { weakest = q.x + q.flush * 0.002f; s = &q; }
                if (!s) continue;
            }
            *s = Slot();
            s->alive = true; s->key = c.key; s->pos = c.pos; s->normal = c.normal.normalized(); s->radius = radius;
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
        pri[i] = (std::max(slot_[i].x, 0.0f) + 0.002f) * slot_[i].radius + slot_[i].flush * 0.0005f;
        order[n++] = i;
    }
    for (int i = 1; i < n; ++i) {   // (insertion sort, strongest first)
        int v = order[i], j = i - 1;
        while (j >= 0 && pri[order[j]] < pri[v]) { order[j + 1] = order[j]; --j; }
        order[j + 1] = v;
    }
    const float fadeStep = 1 - std::exp(-dt * 8.0f);
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
        o.bulge = std::max(o.depth, 0.0f) * contactBulgeGain(0.1f + 0.3f * s.soft);   // soft flesh gives its volume to the ring; stiff keeps its own
        o.squeeze = 0.25f * s.soft;
        o.flush = s.flush * s.fade;
        o.blanch = std::clamp(s.x / (0.7f * s.maxDepth), 0.0f, 1.0f) * s.fade;
        // merge into a nearer, stronger one of the same region (two contacts one on the other make one dent)
        bool merged = false;
        for (int j = 0; j < outN_; ++j) {
            ContactOut& m = out_[j];
            if ((m.pos - o.pos).length() < 0.4f * std::min(m.radius, o.radius) && std::fabs(m.softness - o.softness) < 1e-4f) {
                m.depth += 0.6f * o.depth; m.bulge += 0.6f * o.bulge; m.flush = std::max(m.flush, o.flush); m.blanch = std::max(m.blanch, o.blanch);
                m.radius = std::max(m.radius, o.radius);
                merged = true;
                break;
            }
        }
        if (!merged) out_[outN_++] = o;
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
