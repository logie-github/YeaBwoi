#include "skinpatch.h"

#include <algorithm>
#include <cmath>

namespace wc {

namespace {
constexpr float kPi = 3.14159265f;
#ifndef SP_DAMP
#define SP_DAMP 40.0f
#endif
constexpr float kDamp = SP_DAMP;
constexpr int kBorder = 3;      // the outer rings held at rest
float sstep(float a, float b, float x) { const float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f); return t * t * (3 - 2 * t); }
float ringProfile(float q) { return sstep(0.7f, 1.0f, q) * (1 - sstep(1.0f, 1.6f, q)); }
float ringIntegral() {          // integral of ring(q) q dq
    float s = 0;
    for (int i = 0; i < 400; ++i) { const float q = (float(i) + 0.5f) / 400.0f * 1.6f; s += ringProfile(q) * q * 1.6f / 400.0f; }
    return s;
}
bool fin(float v) { return std::isfinite(v); }
} // namespace

float SkinPatch::alpha(float hz, float h) const { const float w = 2 * kPi * hz; return 1.0f / (w * w * h * h); }

void SkinPatch::build(const PatchSpec& s) {
    spec_ = s;
    spec_.spacing = std::clamp(s.spacing, 0.002f, 0.012f);
    spec_.thickness = std::clamp(s.thickness, 0.004f, 0.06f);
    spec_.softness = std::clamp(s.softness, 0.4f, 1.5f);
    spec_.substeps = std::clamp(s.substeps, 2, 12);
    const size_t n = size_t(kPatchN) * kPatchN;
    a_.assign(n, 0); b_ = a_; c_ = a_; va_ = a_; vb_ = a_; vc_ = a_; pa_ = a_; pb_ = a_; pc_ = a_; pw_ = a_; pk_ = a_; ring_ = a_;
    w_.assign(n, 1.0f);
    for (int j = 0; j < kPatchN; ++j)
        for (int i = 0; i < kPatchN; ++i)
            if (i < kBorder || j < kBorder || i >= kPatchN - kBorder || j >= kPatchN - kBorder) w_[size_t(idx(i, j))] = 0;
    for (Hold& h : hold_) h = Hold();
    np_ = 0; acc_ = 0; tick_ = 0; asleep_ = true; quiet_ = 0;
    built_ = true;
}

void SkinPatch::reset() {
    std::fill(a_.begin(), a_.end(), 0.0f); b_ = a_; c_ = a_; va_ = a_; vb_ = a_; vc_ = a_; pw_ = a_; pk_ = a_; ring_ = a_;
    for (Hold& h : hold_) h = Hold();
    np_ = 0; acc_ = 0; asleep_ = true; quiet_ = 0;
}

float SkinPatch::areaRatio(int i, int j) const {
    i = std::clamp(i, 1, kPatchN - 2); j = std::clamp(j, 1, kPatchN - 2);
    const float h2 = 2 * spec_.spacing;
    const float jxx = 1 + (a_[size_t(idx(i + 1, j))] - a_[size_t(idx(i - 1, j))]) / h2;
    const float jxy = (a_[size_t(idx(i, j + 1))] - a_[size_t(idx(i, j - 1))]) / h2;
    const float jyx = (b_[size_t(idx(i + 1, j))] - b_[size_t(idx(i - 1, j))]) / h2;
    const float jyy = 1 + (b_[size_t(idx(i, j + 1))] - b_[size_t(idx(i, j - 1))]) / h2;
    return jxx * jyy - jxy * jyx;
}

float SkinPatch::maxRise() const { float m = 0; for (float v : c_) m = std::max(m, v); return m; }
float SkinPatch::maxSink() const { float m = 0; for (float v : c_) m = std::min(m, v); return m; }
float SkinPatch::maxSlide() const { float m = 0; for (size_t i = 0; i < a_.size(); ++i) m = std::max(m, std::sqrt(a_[i] * a_[i] + b_[i] * b_[i])); return m; }
float SkinPatch::volume() const { float s = 0; for (float v : c_) s += v; return s * spec_.spacing * spec_.spacing; }
bool SkinPatch::finite() const {
    for (size_t i = 0; i < a_.size(); ++i) if (!fin(a_[i]) || !fin(b_[i]) || !fin(c_[i])) return false;
    return true;
}

void SkinPatch::exportField(float* o) const {
    // what is drawn is the field smoothed once ([1 2 1] each way): the vertices and the fragments sample it at about the nodes' own spacing,
    // and an unsmoothed crest would show the grid
    auto at = [&](const std::vector<float>& f, int i, int j) { return f[size_t(idx(std::clamp(i, 0, kPatchN - 1), std::clamp(j, 0, kPatchN - 1)))]; };
    for (int j = 0; j < kPatchN; ++j)
        for (int i = 0; i < kPatchN; ++i) {
            float sa = 0, sb = 0, sc = 0;
            for (int dj = -1; dj <= 1; ++dj)
                for (int di = -1; di <= 1; ++di) {
                    const float w = float((2 - std::abs(di)) * (2 - std::abs(dj)));
                    sa += w * at(a_, i + di, j + dj); sb += w * at(b_, i + di, j + dj); sc += w * at(c_, i + di, j + dj);
                }
            const size_t n = size_t(idx(i, j));
            o[n * 4] = sa / 16.0f * 100.0f; o[n * 4 + 1] = sb / 16.0f * 100.0f; o[n * 4 + 2] = sc / 16.0f * 100.0f;
            o[n * 4 + 3] = std::clamp(pw_[n], 0.0f, 1.0f);
        }
}

void SkinPatch::step(float dt) {
    if (!built_) return;
    if (!fin(dt) || dt < 0) dt = 0;
    acc_ += std::min(dt, 0.1f);
    const float hs = 1.0f / 60.0f;
    while (acc_ >= hs) {
        acc_ -= hs;
        ++tick_;
        const float S = float(spec_.substeps);
        // the pads of this step: the holds (a grip's point of the skin it holds), the kernel weights on the nodes
        const float sp = spec_.spacing;
        std::fill(pw_.begin(), pw_.end(), 0.0f);
        std::fill(pk_.begin(), pk_.end(), 0.0f);
        std::fill(ring_.begin(), ring_.end(), 0.0f);
        struct PN { int node; int pad; float k; };
        static thread_local std::vector<PN> pn;
        pn.clear();
        float dragX[kPatchPads] = {}, dragY[kPatchPads] = {}, gripK[kPatchPads] = {}, padCx[kPatchPads] = {}, padCy[kPatchPads] = {};
        static const float I = ringIntegral();
        for (int p = 0; p < np_; ++p) {
            PatchPad& d = pad_[p];
            if (!fin(d.x) || !fin(d.y) || !fin(d.radius) || !fin(d.push)) { d.push = 0; d.grip = 0; d.x = d.y = 0; }
            d.radius = std::clamp(d.radius, 0.003f, 0.05f);
            Hold* h = nullptr;
            for (Hold& q : hold_) if (q.on && q.key == d.key) h = &q;
            if (!h) for (Hold& q : hold_) if (!q.on) { h = &q; q.on = true; q.key = d.key; q.mx = d.x; q.my = d.y; break; }
            if (h) {
                h->seen = tick_;
                float dx = d.x - h->mx, dy = d.y - h->my;
                const float L = std::sqrt(dx * dx + dy * dy), R = std::max(d.reach, 1e-4f);
                if (L > R) { h->mx += dx * (L - R) / L; h->my += dy * (L - R) / L; dx = d.x - h->mx; dy = d.y - h->my; }
                dragX[p] = dx; dragY[p] = dy; gripK[p] = std::clamp(d.grip, 0.0f, 1.0f);
                if (d.grip <= 0) { h->mx = d.x; h->my = d.y; dragX[p] = dragY[p] = 0; }
            }
            // the kernel is on the material the pad holds (its point on the undeformed skin), not on where the finger now is
            const float cx = h ? h->mx : d.x, cy = h ? h->my : d.y;
            padCx[p] = cx; padCy[p] = cy;
            const float Rk = 1.5f * d.radius * std::max(d.aspect, 1.0f);   // (the bounding radius)
            const float Rx = 1.5f * d.radius / std::sqrt(std::max(d.aspect, 1.0f)), Ry = Rx * std::max(d.aspect, 1.0f);
            {
                const float dl = std::sqrt(d.dirx * d.dirx + d.diry * d.diry);
                if (dl < 1e-4f) { d.dirx = 1; d.diry = 0; } else { d.dirx /= dl; d.diry /= dl; }
            }
            const int i0 = std::max(int((cx - Rk) / sp + 0.5f * (kPatchN - 1)) - 1, 0), i1 = std::min(int((cx + Rk) / sp + 0.5f * (kPatchN - 1)) + 2, kPatchN - 1);
            const int j0 = std::max(int((cy - Rk) / sp + 0.5f * (kPatchN - 1)) - 1, 0), j1 = std::min(int((cy + Rk) / sp + 0.5f * (kPatchN - 1)) + 2, kPatchN - 1);
            for (int j = j0; j <= j1; ++j)
                for (int i = i0; i <= i1; ++i) {
                    const float x = restX(i) - cx, y = restX(j) - cy;
                    const float lx = x * d.dirx + y * d.diry, ly = -x * d.diry + y * d.dirx;   // (along the join of the fingers, across it)
                    const float q = std::sqrt((lx / Rx) * (lx / Rx) + (ly / Ry) * (ly / Ry));
                    if (q < 1.0f) {
                        const float k = (1 - q * q) * (1 - q * q);
                        pn.push_back({idx(i, j), p, k});
                        if (d.push > 0.0005f) pk_[size_t(idx(i, j))] = std::max(pk_[size_t(idx(i, j))], k * std::min(d.push / 0.006f, 1.0f));
                        pw_[size_t(idx(i, j))] = std::max(pw_[size_t(idx(i, j))], k * std::max(gripK[p], std::min(d.push / 0.004f, 1.0f)));
                    }
                }
            // the dent's volume comes back as a ring round it
            if (d.push > 0) {
                const float amp = 0.8f * d.push * spec_.softness / (6.0f * I);
                const float R = Rk * 1.0f;
                const int ri0 = std::max(int((cx - 1.7f * R) / sp + 0.5f * (kPatchN - 1)), 0), ri1 = std::min(int((cx + 1.7f * R) / sp + 0.5f * (kPatchN - 1)) + 1, kPatchN - 1);
                const int rj0 = std::max(int((cy - 1.7f * R) / sp + 0.5f * (kPatchN - 1)), 0), rj1 = std::min(int((cy + 1.7f * R) / sp + 0.5f * (kPatchN - 1)) + 1, kPatchN - 1);
                for (int j = rj0; j <= rj1; ++j)
                    for (int i = ri0; i <= ri1; ++i) {
                        const float x = restX(i) - cx, y = restX(j) - cy;
                        ring_[size_t(idx(i, j))] += amp * ringProfile(std::sqrt(x * x + y * y) / R);
                    }
            }
        }
        // a pinch's two fingertips: the skin between them follows the two drags, side to side (a fold across the gap, not two piles at the pads)
        struct LN { int node; float wgt, f, d1x, d1y, d2x, d2y; };
        static thread_local std::vector<LN> ln;
        ln.clear();
        for (int p = 0; p < np_; ++p) {
            const PatchPad& d = pad_[p];
            if (!d.mate || d.grip <= 0) continue;
            int q = -1;
            for (int o = 0; o < np_; ++o) if (pad_[o].key == d.mate && pad_[o].mate == d.key) q = o;
            if (q < p) continue;
            const PatchPad& e = pad_[q];
            const float m1x = padCx[p], m1y = padCy[p], m2x = padCx[q], m2y = padCy[q];
            float jx = m2x - m1x, jy = m2y - m1y;
            const float L0 = std::sqrt(jx * jx + jy * jy);
            if (L0 < 1e-4f) continue;
            jx /= L0; jy /= L0;
            const float d1x = dragX[p], d1y = dragY[p], d2x = dragX[q], d2y = dragY[q];
            const float Ry = 1.5f * std::max(d.radius, e.radius) * std::max(std::max(d.aspect, e.aspect), 1.0f);
            const float mx = 0.5f * (m1x + m2x), my = 0.5f * (m1y + m2y);
            const float R = std::sqrt(0.25f * L0 * L0 + Ry * Ry);
            const int i0 = std::max(int((mx - R) / sp + 0.5f * (kPatchN - 1)), 0), i1 = std::min(int((mx + R) / sp + 0.5f * (kPatchN - 1)) + 1, kPatchN - 1);
            const int j0 = std::max(int((my - R) / sp + 0.5f * (kPatchN - 1)), 0), j1 = std::min(int((my + R) / sp + 0.5f * (kPatchN - 1)) + 1, kPatchN - 1);
            for (int j = j0; j <= j1; ++j)
                for (int i = i0; i <= i1; ++i) {
                    const float x = restX(i) - mx, y = restX(j) - my;
                    const float sS = x * jx + y * jy, tT = -x * jy + y * jx;
                    if (std::fabs(sS) > 0.5f * L0 || std::fabs(tT) >= Ry) continue;
                    const float qt = tT / Ry;
                    const float wt = (1 - qt * qt) * (1 - qt * qt);
                    ln.push_back({idx(i, j), wt, sS / L0 + 0.5f, d1x, d1y, d2x, d2y});
                }
        }
        for (Hold& q : hold_) if (q.on && q.seen != tick_) q.on = false;
        const float h = hs / S;
        // the pads' drag target and per-substep pass
        for (int s = 0; s < spec_.substeps; ++s) {
            // inertia (damped)
            const float damp = std::exp(-kDamp * h);
            for (size_t i = 0; i < a_.size(); ++i) {
                pa_[i] = a_[i]; pb_[i] = b_[i]; pc_[i] = c_[i];
                if (w_[i] <= 0) continue;
                va_[i] *= damp; vb_[i] *= damp; vc_[i] = 0;   // (the rise has no momentum: it settles, it does not ring)
                a_[i] += va_[i] * h; b_[i] += vb_[i] * h;
            }
            // pads: the grip drags the skin along, a press sinks it
            const float aPad = 1.0f / (1.0f + alpha(28.0f, h));
            for (const PN& e : pn) {
                const size_t n = size_t(e.node);
                if (w_[n] <= 0) continue;
                const PatchPad& d = pad_[e.pad];
                const float kk = e.k;
                const float pcx = padCx[e.pad], pcy = padCy[e.pad];
                if (gripK[e.pad] > 0) {
                    // nodes ahead of the pad (in the way it drags) are pushed by the squeeze, not carried: the skin between two pads gathers
                    float ahead = 1.0f;
                    const float dl = std::sqrt(dragX[e.pad] * dragX[e.pad] + dragY[e.pad] * dragY[e.pad]);
                    if (dl > 1e-5f) {
                        const float rel = ((restX(e.node % kPatchN) - pcx) * dragX[e.pad] + (restX(e.node / kPatchN) - pcy) * dragY[e.pad]) / dl;
                        ahead = 1.0f - sstep(-0.2f * d.radius, 0.3f * d.radius, rel);
                    }
                    const float g = std::min(1.0f, kk * 1.6f) * gripK[e.pad] * (0.6f + 0.4f * aPad) * ahead;
                    a_[n] += (dragX[e.pad] - a_[n]) * g * 0.6f;
                    b_[n] += (dragY[e.pad] - b_[n]) * g * 0.6f;
                }
                if (d.push > 0) {
                    const float tgt = -d.push * spec_.softness * kk;
                    if (c_[n] > tgt) c_[n] += (tgt - c_[n]) * std::min(1.0f, kk * 1.4f) * 0.8f;
                }
            }
            for (const LN& e : ln) {
                const size_t n = size_t(e.node);
                if (w_[n] <= 0) continue;
                const float ta = e.d1x + (e.d2x - e.d1x) * e.f, tb = e.d1y + (e.d2y - e.d1y) * e.f;
                a_[n] += (ta - a_[n]) * e.wgt * 0.5f; b_[n] += (tb - b_[n]) * e.wgt * 0.5f;
            }
            // skin tension and gathering: each edge and diagonal, on the 3D lengths
            const float T = spec_.thickness;
            const float aT = alpha(30.0f, h), aC = alpha(7.0f / spec_.softness, h);
            auto edge = [&](int i0, int j0, int i1, int j1, float l0) {
                const size_t p = size_t(idx(i0, j0)), q = size_t(idx(i1, j1));
                const float wp = w_[p], wq = w_[q];
                if (wp + wq <= 0) return;
                const float dx = restX(i1) - restX(i0) + a_[q] - a_[p], dy = restX(j1) - restX(j0) + b_[q] - b_[p], dz = 0.0f;   // (in the plane: the rise follows from the flow, below)
                const float len = std::sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-7f) return;
                const float e = (len - l0) / l0;
                float C, al, gx = dx / len, gy = dy / len, gz = dz / len;
                const float rl = std::sqrt((restX(i1) - restX(i0)) * (restX(i1) - restX(i0)) + (restX(j1) - restX(j0)) * (restX(j1) - restX(j0)));
                const float rx = (restX(i1) - restX(i0)) / rl, ry = (restX(j1) - restX(j0)) / rl;
                const float sproj = dx * rx + dy * ry;      // signed: an edge turned inside out is the worst squeeze
                if (sproj < 0.35f * l0) { C = sproj - 0.35f * l0; al = aT * 0.3f; gx = rx; gy = ry; gz = 0; }
                else if (e > 0) { C = len - l0; al = aT / (1.0f + 6.0f * e * e); }
                else { C = len - l0; al = aC / (1.0f + 40.0f * e * e); }
                const float lam = -C / (wp + wq + al);
                a_[p] -= wp * lam * gx; b_[p] -= wp * lam * gy; c_[p] -= wp * lam * gz;
                a_[q] += wq * lam * gx; b_[q] += wq * lam * gy; c_[q] += wq * lam * gz;
            };
            for (int j = 1; j < kPatchN - 1; ++j)
                for (int i = 1; i < kPatchN - 1; ++i) {
                    edge(i, j, i + 1, j, sp); edge(i, j, i, j + 1, sp);
                    edge(i, j, i + 1, j + 1, sp * 1.41421356f); edge(i + 1, j, i, j + 1, sp * 1.41421356f);
                }
            // the slab's volume: the surface rises where the flow gathers the skin
            const float aV = 1.0f / (1.0f + alpha(10.0f, h));
            const float aB = alpha(14.0f, h), aBt = alpha(18.0f, h);
            const float cmax = spec_.riseCap * T * (0.7f + 0.3f * spec_.softness);   // riseCap 0.85
            for (int j = kBorder; j < kPatchN - kBorder; ++j)
                for (int i = kBorder; i < kPatchN - kBorder; ++i) {
                    const size_t n = size_t(idx(i, j));
                    const float Jr = std::max(areaRatio(i, j), 0.3f);
                    const float gath = 1.0f / Jr - 1.0f;   // the share of the slab's volume the flow has pushed out of this node's patch of skin
                    float ct = gath >= 0 ? cmax * std::tanh(T * std::pow(gath, 1.3f) / cmax) : T * gath;   // (a little gathering lifts a little; a lot piles up to the tissue's limit, softly)
                    ct = std::max(ct, -0.05f * T) + ring_[n];
                    // a pad's own press wins under it
                    const float free = 1.0f - std::min(pk_[n] * 1.5f, 1.0f);
                    c_[n] += (ct - c_[n]) * aV * free;
                }
            // bending (the crest and the foot of a fold are rounded), the tangential smoothing, the tie to the body
            auto bend = [&](float* f, int i, int j, int di, int dj, float al) {
                const size_t p = size_t(idx(i - di, j - dj)), m = size_t(idx(i, j)), q = size_t(idx(i + di, j + dj));
                const float wp = w_[p], wm = w_[m], wq = w_[q];
                const float C = f[p] - 2 * f[m] + f[q];
                const float den = wp + 4 * wm + wq + al;
                if (den <= 0) return;
                const float lam = -C / den;
                f[p] += wp * lam; f[m] -= 2 * wm * lam; f[q] += wq * lam;
            };
            for (int j = 1; j < kPatchN - 1; ++j)
                for (int i = 1; i < kPatchN - 1; ++i) {
                    bend(c_.data(), i, j, 1, 0, aB); bend(c_.data(), i, j, 0, 1, aB);
                    bend(a_.data(), i, j, 1, 0, aBt); bend(a_.data(), i, j, 0, 1, aBt);
                    bend(b_.data(), i, j, 1, 0, aBt); bend(b_.data(), i, j, 0, 1, aBt);
                }
            const float aA = 1.0f / (1.0f + alpha(2.6f, h));
            const float floorC = -0.9f * T;
            for (size_t i = 0; i < a_.size(); ++i) {
                if (w_[i] <= 0) { a_[i] = b_[i] = c_[i] = 0; va_[i] = vb_[i] = vc_[i] = 0; continue; }
                a_[i] -= a_[i] * aA; b_[i] -= b_[i] * aA;
                if (c_[i] < floorC) c_[i] = floorC;
                if (!fin(a_[i]) || !fin(b_[i]) || !fin(c_[i])) { a_[i] = b_[i] = c_[i] = 0; va_[i] = vb_[i] = vc_[i] = 0; pa_[i] = pb_[i] = pc_[i] = 0; }
                va_[i] = (a_[i] - pa_[i]) / h; vb_[i] = (b_[i] - pb_[i]) / h; vc_[i] = (c_[i] - pc_[i]) / h;
                const float vm = 8.0f;
                va_[i] = std::clamp(va_[i], -vm, vm); vb_[i] = std::clamp(vb_[i], -vm, vm); vc_[i] = std::clamp(vc_[i], -vm, vm);
            }
        }
        // asleep: nothing on it and nothing moving
        float mv = 0;
        for (size_t i = 0; i < a_.size(); ++i) mv = std::max(mv, std::max(std::fabs(a_[i]), std::max(std::fabs(b_[i]), std::fabs(c_[i]))));
        if (np_ == 0 && mv < 0.00008f) {
            if (++quiet_ > 6) {
                if (!asleep_) { std::fill(a_.begin(), a_.end(), 0.0f); b_ = a_; c_ = a_; va_ = a_; vb_ = a_; vc_ = a_; }
                asleep_ = true;
            }
        } else { quiet_ = 0; asleep_ = false; }
    }
}

} // namespace wc
