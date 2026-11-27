// Soft tissue: the lattice, its XPBD solver and the handles (tissue.h).
#include "tissue.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace wc {

namespace {

constexpr float kStep = 1.0f / 60.0f;
constexpr float kPi = 3.14159265f;
constexpr float kHandleAlpha = 1e-7f;       // the finger's compliance (very stiff beside the flesh)
constexpr float kGripReach = 0.04f;         // metres a grip of 1 holds before the skin slides under the finger
constexpr float kMaxDisp = 0.25f;

inline V3 comp(const V3& v, int a) { return a == 0 ? V3(v.x, 0, 0) : a == 1 ? V3(0, v.y, 0) : V3(0, 0, v.z); }
inline float at3(const V3& v, int a) { return a == 0 ? v.x : a == 1 ? v.y : v.z; }
inline bool fin(const V3& v) { return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z); }

// the five tetrahedra of a cell (corner bits: x 1, y 2, z 4); odd cells mirrored in x so neighbours' faces agree
const int kTets[5][4] = {{0, 1, 2, 4}, {3, 1, 2, 7}, {5, 1, 4, 7}, {6, 2, 4, 7}, {1, 2, 4, 7}};

float det3(const V3& a, const V3& b, const V3& c) { return a.dot(b.cross(c)); }

} // namespace

uint16_t tissueHalf(float f) {
    if (!std::isfinite(f)) return 0;
    uint32_t x; std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    int e = int((x >> 23) & 0xFF) - 127 + 15;
    uint32_t m = x & 0x7FFFFFu;
    if (e <= 0) {
        if (e < -10) return uint16_t(sign);
        m |= 0x800000u;
        const int sh = 14 - e;
        uint32_t r = m >> sh;
        if ((m >> (sh - 1)) & 1u) ++r;
        return uint16_t(sign | r);
    }
    if (e >= 31) return uint16_t(sign | 0x7BFF);
    uint32_t r = (uint32_t(e) << 10) | (m >> 13);
    if (m & 0x1000u) ++r;
    return uint16_t(sign | r);
}

void TissueRegion::build(const TissueSpec& s) {
    spec_ = s;
    const float sp = std::max(s.spacing, 0.008f);
    nu_ = std::clamp(int(std::ceil(2 * s.halfU / sp)) + 1, 5, 14);
    nv_ = std::clamp(int(std::ceil(2 * s.halfV / sp)) + 1, 5, 14);
    du_ = 2 * s.halfU / float(nu_ - 1);
    dv_ = 2 * s.halfV / float(nv_ - 1);
    dw_ = 0.5f * std::max(s.depth, 0.02f);
    const int N = nu_ * nv_ * kTissueLayers;
    X_.assign(size_t(N), V3()); x_ = v_ = prev_ = A0_ = A1_ = Acur_ = X_;
    w_.assign(size_t(N), 0.0f); alphaT_ = alphaN_ = damp_ = w_;
    for (int k = 0; k < kTissueLayers; ++k)
        for (int j = 0; j < nv_; ++j)
            for (int i = 0; i < nu_; ++i)
                X_[size_t(index(i, j, k))] = V3(-s.halfU + float(i) * du_, -s.halfV + float(j) * dv_, float(k) * dw_);
    // free nodes: inside the footprint's ellipse, above the bone's layer
    nfree_ = 0;
    const float cellVol = du_ * dv_ * dw_;
    const float m = std::max(s.mat.density * cellVol, 1e-4f);
    for (int k = 1; k < kTissueLayers; ++k)
        for (int j = 1; j < nv_ - 1; ++j)
            for (int i = 1; i < nu_ - 1; ++i) {
                const V3& p = X_[size_t(index(i, j, k))];
                const float e = (p.x / s.halfU) * (p.x / s.halfU) + (p.y / s.halfV) * (p.y / s.halfV);
                if (e > 0.82f) continue;
                const size_t n = size_t(index(i, j, k));
                w_[n] = 1.0f / (k == kTissueLayers - 1 ? 0.5f * m : m);
                ++nfree_;
                // deeper nodes are tied to the body harder
                const float deep = 1.0f - float(k) / float(kTissueLayers - 1);
                const float wt = 2 * kPi * s.mat.freqTan, wn = 2 * kPi * s.mat.freqNorm;
                alphaT_[n] = wt * wt * (1.0f + 2.5f * deep);
                alphaN_[n] = wn * wn * (1.0f + 2.5f * deep);
                damp_[n] = s.mat.zeta;
            }
    // the tetrahedra (only those with a free node)
    tet_.clear();
    const float E = s.mat.youngs, nu = s.mat.poisson;
    const float mu = E / (2 * (1 + nu)), lambda = E * nu / ((1 + nu) * (1 - 2 * nu));
    for (int k = 0; k < kTissueLayers - 1; ++k)
        for (int j = 0; j < nv_ - 1; ++j)
            for (int i = 0; i < nu_ - 1; ++i) {
                const bool odd = ((i + j + k) & 1) != 0;
                for (int t = 0; t < 5; ++t) {
                    Tet T{};
                    bool any = false;
                    for (int c = 0; c < 4; ++c) {
                        int bits = kTets[t][c];
                        if (odd) bits ^= 1;
                        T.n[c] = index(i + (bits & 1), j + ((bits >> 1) & 1), k + ((bits >> 2) & 1));
                        if (w_[size_t(T.n[c])] > 0) any = true;
                    }
                    if (!any) continue;
                    V3 e1 = X_[size_t(T.n[1])] - X_[size_t(T.n[0])], e2 = X_[size_t(T.n[2])] - X_[size_t(T.n[0])], e3 = X_[size_t(T.n[3])] - X_[size_t(T.n[0])];
                    float d = det3(e1, e2, e3);
                    if (d < 0) { std::swap(T.n[1], T.n[2]); std::swap(e1, e2); d = -d; }
                    if (d < 1e-12f) continue;
                    const V3 r0 = e2.cross(e3) * (1.0f / d), r1 = e3.cross(e1) * (1.0f / d), r2 = e1.cross(e2) * (1.0f / d);
                    const V3 rows[3] = {r0, r1, r2};
                    for (int r = 0; r < 3; ++r) { T.B[0 * 3 + r] = rows[r].x; T.B[1 * 3 + r] = rows[r].y; T.B[2 * 3 + r] = rows[r].z; }
                    T.V0 = d / 6.0f; T.mu = mu; T.lambda = lambda;
                    tet_.push_back(T);
                }
            }
    reset();
}

void TissueRegion::reset() {
    for (size_t n = 0; n < x_.size(); ++n) { x_[n] = anchor(int(n)); v_[n] = V3(); }
    acc_ = 0; asleep_ = true; quiet_ = 0;
    for (Grip& g : grips_) g = Grip();
}

V3 TissueRegion::anchor(int n) const {
    const V3& p = X_[size_t(n)];
    return o1_ + u1_ * p.x + v1_ * p.y + w1_ * p.z;
}

void TissueRegion::setJiggle(float a) { jiggle_ = std::isfinite(a) ? std::clamp(a, 0.05f, 3.0f) : 1.0f; }

void TissueRegion::setFrame(const V3& o, const V3& u, const V3& v, const V3& w, bool snap) {
    if (!fin(o) || !fin(u) || !fin(v) || !fin(w)) return;
    if (!haveFrame_ || snap) {
        o0_ = o1_ = oPrev_ = oPrev2_ = o; u0_ = u1_ = u; v0_ = v1_ = v; w0_ = w1_ = w;
        haveFrame_ = true;
        for (size_t n = 0; n < x_.size(); ++n) { x_[n] = anchor(int(n)); v_[n] = V3(); }
        asleep_ = true; quiet_ = 0;
        return;
    }
    const V3 sigNew = o + (u + v + w) * 0.1f, sigCur = o1_ + (u1_ + v1_ + w1_) * 0.1f, sigPrev = oPrev_;
    const V3 acc = sigNew - sigCur * 2.0f + sigPrev;
    oPrev_ = sigCur;
    if (acc.length() > 1.0e-4f) { asleep_ = false; quiet_ = 0; }
    o1_ = o; u1_ = u; v1_ = v; w1_ = w;
}

void TissueRegion::anchorsAt(float t, V3* out) const {
    const V3 o = lerp(o0_, o1_, t), u = lerp(u0_, u1_, t), v = lerp(v0_, v1_, t), w = lerp(w0_, w1_, t);
    for (size_t n = 0; n < X_.size(); ++n) { const V3& p = X_[n]; out[n] = o + u * p.x + v * p.y + w * p.z; }
}

bool TissueRegion::gripOf(uint32_t key, V3* lp) const {
    for (const Grip& g : grips_) if (g.on && g.key == key) { if (lp) *lp = g.m; return true; }
    return false;
}

void TissueRegion::gather() {
    ++tick_;
    for (int h = 0; h < kTissueHandles; ++h) hn_[h].live = false;
    for (int h = 0; h < nh_; ++h) {
        const TissueHandle& in = hin_[h];
        if (!fin(in.rest) || !fin(in.normal) || !std::isfinite(in.push)) continue;
        const V3 d = in.rest - o1_;
        const V3 q(d.dot(u1_), d.dot(v1_), d.dot(w1_));
        const V3 nw = in.normal.normalized();
        const V3 nl(nw.dot(u1_), nw.dot(v1_), nw.dot(w1_));
        // inside the lattice's reach?
        const float reach = std::max(in.radius, 0.02f);
        if (std::fabs(q.x) > spec_.halfU + reach || std::fabs(q.y) > spec_.halfV + reach || q.z < -reach || q.z > 1.8f * spec_.depth + reach) continue;
        V3 M = q;
        HandleNodes& H = hn_[h];
        H.vec = in.grip > 0.001f;
        if (H.vec) {
            Grip* g = nullptr;
            for (Grip& s : grips_) if (s.on && s.key == in.key) { g = &s; break; }
            if (!g) for (Grip& s : grips_) if (!s.on) { g = &s; break; }
            if (!g) { H.vec = false; }
            else {
                if (!g->on || g->key != in.key) { *g = Grip(); g->on = true; g->key = in.key; g->m = q; }
                g->seen = tick_;
                V3 delta = q - g->m;
                const float dn = delta.dot(nl);
                V3 tang = delta - nl * dn;
                const float lim = std::clamp(in.grip, 0.0f, 1.5f) * kGripReach, len = tang.length();
                if (len > lim) g->m = g->m + tang * (1.0f - lim / len);
                g->m = g->m + nl * ((q - g->m).dot(nl));      // it stays on the surface
                M = g->m;
                const V3 dr = q - M;                          // the drag, local (no normal part)
                H.target = u1_ * dr.x + v1_ * dr.y + w1_ * dr.z - nw * in.push + in.pull;
            }
        }
        H.axisN = nw; H.push = in.push;
        // the kernel's nodes
        const float rho = std::max(in.radius, 1.25f * std::max(du_, dv_));
        H.count = 0;
        float sum = 0;
        for (int n = 0; n < int(X_.size()) && H.count < 48; ++n) {
            if (w_[size_t(n)] <= 0) continue;
            const float r2 = (X_[size_t(n)] - M).dot(X_[size_t(n)] - M) / (rho * rho);
            if (r2 >= 1) continue;
            const float k = (1 - r2) * (1 - r2);
            H.n[H.count] = n; H.wgt[H.count] = k; sum += k; ++H.count;
        }
        if (H.count == 0 || sum <= 1e-6f) continue;
        for (int i = 0; i < H.count; ++i) H.wgt[i] /= sum;
        H.live = true;
    }
    for (Grip& g : grips_) if (g.on && g.seen != tick_) g = Grip();
}

void TissueRegion::step(float dt) {
    if (!built() || !haveFrame_) return;
    if (!std::isfinite(dt) || dt <= 0) dt = 0;
    acc_ += std::min(dt, 0.1f);
    int steps = int(acc_ / kStep + 1e-3f);
    if (steps > 0) acc_ = std::max(acc_, float(steps) * kStep);
    if (steps <= 0) return;
    acc_ -= float(steps) * kStep;
    if (steps > 6) steps = 6;
    if (nh_ > 0) { asleep_ = false; quiet_ = 0; }
    ran_ = 0;
    if (asleep_) {
        // nothing moves: the nodes ride their anchors
        o0_ = o1_; u0_ = u1_; v0_ = v1_; w0_ = w1_;
        for (size_t n = 0; n < x_.size(); ++n) { x_[n] = anchor(int(n)); v_[n] = V3(); }
        for (Grip& g : grips_) g = Grip();
        return;
    }
    gather();
    for (int s = 0; s < steps; ++s) {
        anchorsAt(float(s) / float(steps), A0_.data());
        anchorsAt(float(s + 1) / float(steps), A1_.data());
        fixedStep(kStep);
        ran_ += substeps_;
    }
    o0_ = o1_; u0_ = u1_; v0_ = v1_; w0_ = w1_;
    // settled?
    float dmax = 0, vmax = 0;
    for (size_t n = 0; n < x_.size(); ++n) {
        if (w_[n] <= 0) continue;
        dmax = std::max(dmax, (x_[n] - A1_[n]).length());
        vmax = std::max(vmax, (v_[n] - (A1_[n] - A0_[n]) * (1.0f / kStep)).length());
    }
    if (nh_ == 0 && dmax < 6e-5f && vmax < 6e-4f) { if (++quiet_ >= 4) asleep_ = true; } else quiet_ = 0;
}

void TissueRegion::fixedStep(float h) {
    const int N = substeps_;
    const float dt = h / float(N);
    const float dt2 = dt * dt;
    const size_t cnt = x_.size();
    const float jig = 1.0f / jiggle_;
    // gravity's change from the rest pose
    const V3 rd = u1_ * restDown_.x + v1_ * restDown_.y + w1_ * restDown_.z;
    const V3 g = (V3(0, 0, -1) - rd) * (9.81f * spec_.mat.sag);
    for (int s = 0; s < N; ++s) {
        const float t = float(s + 1) / float(N);
        for (size_t n = 0; n < cnt; ++n) Acur_[n] = A0_[n] + (A1_[n] - A0_[n]) * t;
        // predict
        for (size_t n = 0; n < cnt; ++n) {
            if (w_[n] <= 0) { prev_[n] = x_[n]; x_[n] = Acur_[n]; v_[n] = (A1_[n] - A0_[n]) * (1.0f / h); continue; }
            v_[n] = v_[n] + g * dt;
            prev_[n] = x_[n];
            x_[n] = x_[n] + v_[n] * dt;
        }
        // the anchoring: softer along the skin than into the body
        for (size_t n = 0; n < cnt; ++n) {
            if (w_[n] <= 0) continue;
            const V3 d = x_[n] - Acur_[n];
            const float cu = d.dot(u1_), cv = d.dot(v1_), cw = d.dot(w1_);
            const float bt = alphaT_[n] * jig * dt2, bn = alphaN_[n] * jig * dt2;
            const float ft = bt / (1 + bt), fn = bn / (1 + bn);
            x_[n] = x_[n] - u1_ * (cu * ft) - v1_ * (cv * ft) - w1_ * (cw * fn);
        }
        // the elasticity
        for (const Tet& T : tet_) {
            V3* p[4] = {&x_[size_t(T.n[0])], &x_[size_t(T.n[1])], &x_[size_t(T.n[2])], &x_[size_t(T.n[3])]};
            const float iw[4] = {w_[size_t(T.n[0])], w_[size_t(T.n[1])], w_[size_t(T.n[2])], w_[size_t(T.n[3])]};
            for (int pass = 0; pass < 2; ++pass) {
                const V3 s1 = *p[1] - *p[0], s2 = *p[2] - *p[0], s3 = *p[3] - *p[0];
                V3 f[3];
                for (int j = 0; j < 3; ++j) f[j] = s1 * T.B[j * 3 + 0] + s2 * T.B[j * 3 + 1] + s3 * T.B[j * 3 + 2];
                V3 P[3];
                float C, alpha;
                if (pass == 0) {
                    const float rs = std::sqrt(f[0].dot(f[0]) + f[1].dot(f[1]) + f[2].dot(f[2]));
                    if (rs < 1e-6f) continue;
                    const float e = rs * 0.57735027f - 1.0f;
                    const float mu = T.mu * (1.0f + spec_.mat.stiffen * e * e);
                    C = rs; alpha = 1.0f / (mu * T.V0);
                    for (int j = 0; j < 3; ++j) P[j] = f[j] * (1.0f / rs);
                } else {
                    const float J = det3(f[0], f[1], f[2]);
                    const float gamma = 1.0f + T.mu / T.lambda;
                    const float lam = T.lambda * (1.0f + spec_.mat.stiffen * (J - 1) * (J - 1));
                    C = J - gamma; alpha = 1.0f / (lam * T.V0);
                    P[0] = f[1].cross(f[2]); P[1] = f[2].cross(f[0]); P[2] = f[0].cross(f[1]);
                }
                V3 gr[4];
                for (int r = 0; r < 3; ++r) gr[r + 1] = P[0] * T.B[0 * 3 + r] + P[1] * T.B[1 * 3 + r] + P[2] * T.B[2 * 3 + r];
                gr[0] = (gr[1] + gr[2] + gr[3]) * -1.0f;
                float den = alpha / dt2;
                for (int c = 0; c < 4; ++c) den += iw[c] * gr[c].dot(gr[c]);
                if (den < 1e-12f) continue;
                const float dl = -C / den;
                for (int c = 0; c < 4; ++c) if (iw[c] > 0) *p[c] = *p[c] + gr[c] * (iw[c] * dl);
            }
        }
        // the handles
        for (int hI = 0; hI < nh_; ++hI) {
            const HandleNodes& H = hn_[hI];
            if (!H.live) continue;
            V3 mean;
            for (int i = 0; i < H.count; ++i) mean = mean + (x_[size_t(H.n[i])] - Acur_[size_t(H.n[i])]) * H.wgt[i];
            float den = kHandleAlpha / dt2;
            for (int i = 0; i < H.count; ++i) den += H.wgt[i] * H.wgt[i] * w_[size_t(H.n[i])];
            if (H.vec) {
                for (int a = 0; a < 3; ++a) {
                    const float Cc = at3(mean, a) - at3(H.target, a);
                    const float dl = -Cc / den;
                    for (int i = 0; i < H.count; ++i) x_[size_t(H.n[i])] = x_[size_t(H.n[i])] + comp(V3(1, 1, 1), a) * (H.wgt[i] * w_[size_t(H.n[i])] * dl);
                }
            } else {
                const float Cc = mean.dot(H.axisN) + H.push;
                if (Cc <= 0) continue;
                const float dl = -Cc / den;
                for (int i = 0; i < H.count; ++i) x_[size_t(H.n[i])] = x_[size_t(H.n[i])] + H.axisN * (H.wgt[i] * w_[size_t(H.n[i])] * dl);
            }
        }
        // velocities, damped against the anchor's own motion
        for (size_t n = 0; n < cnt; ++n) {
            if (w_[n] <= 0) continue;
            V3 d = x_[n] - Acur_[n];
            if (!fin(d) || d.length() > kMaxDisp) { x_[n] = Acur_[n]; v_[n] = (A1_[n] - A0_[n]) * (1.0f / h); prev_[n] = x_[n]; continue; }
            V3 vel = (x_[n] - prev_[n]) * (1.0f / dt);
            const V3 va = (A1_[n] - A0_[n]) * (1.0f / h);
            const float om = 2 * kPi * spec_.mat.freqTan * std::sqrt(jig > 0 ? 1.0f / jig : 1.0f);
            const float k = 1.0f - std::exp(-2.0f * damp_[n] * om * dt);
            v_[n] = vel - (vel - va) * k;
        }
        // the lattice's own deformation: each tetrahedron's nodes drawn toward their mean velocity
        const float ki = 1.0f - std::exp(-spec_.mat.internal * dt);
        for (const Tet& T : tet_) {
            V3 vb = (v_[size_t(T.n[0])] + v_[size_t(T.n[1])] + v_[size_t(T.n[2])] + v_[size_t(T.n[3])]) * 0.25f;
            for (int c = 0; c < 4; ++c) { const size_t n = size_t(T.n[c]); if (w_[n] > 0) v_[n] = v_[n] + (vb - v_[n]) * ki; }
        }
    }
}

float TissueRegion::volumeRatio() const {
    double now = 0, rest = 0;
    for (const Tet& T : tet_) {
        const V3 e1 = x_[size_t(T.n[1])] - x_[size_t(T.n[0])], e2 = x_[size_t(T.n[2])] - x_[size_t(T.n[0])], e3 = x_[size_t(T.n[3])] - x_[size_t(T.n[0])];
        now += det3(e1, e2, e3) / 6.0; rest += T.V0;
    }
    return rest > 0 ? float(now / rest) : 1.0f;
}

float TissueRegion::cellVolumeRatio(int ci, int cj, int ck) const {
    // the tetrahedra of one cell are found by their nodes' corner
    double now = 0, rest = 0;
    const int lo = index(ci, cj, ck), hi = index(ci + 1, cj + 1, ck + 1);
    for (const Tet& T : tet_) {
        bool in = true;
        for (int c = 0; c < 4; ++c) {
            const V3& p = X_[size_t(T.n[c])];
            const V3& a = X_[size_t(lo)]; const V3& b = X_[size_t(hi)];
            if (p.x < a.x - 1e-6f || p.x > b.x + 1e-6f || p.y < a.y - 1e-6f || p.y > b.y + 1e-6f || p.z < a.z - 1e-6f || p.z > b.z + 1e-6f) in = false;
        }
        if (!in) continue;
        const V3 e1 = x_[size_t(T.n[1])] - x_[size_t(T.n[0])], e2 = x_[size_t(T.n[2])] - x_[size_t(T.n[0])], e3 = x_[size_t(T.n[3])] - x_[size_t(T.n[0])];
        now += det3(e1, e2, e3) / 6.0; rest += T.V0;
    }
    return rest > 0 ? float(now / rest) : 1.0f;
}

float TissueRegion::maxDisplacement() const {
    float m = 0;
    for (size_t n = 0; n < x_.size(); ++n) if (w_[n] > 0) m = std::max(m, (x_[n] - anchor(int(n))).length());
    return m;
}

float TissueRegion::kineticEnergy() const {
    double e = 0;
    for (size_t n = 0; n < x_.size(); ++n) if (w_[n] > 0) {
        const V3 va = (A1_[n] - A0_[n]) * (1.0f / kStep);
        const V3 r = v_[n] - va;
        e += 0.5 * r.dot(r) / w_[n];
    }
    return float(e);
}

void TissueRegion::exportField(float* out) const {
    const size_t N = x_.size();
    static thread_local std::vector<float> sacc, scnt;
    sacc.assign(N, 0.0f); scnt.assign(N, 0.0f);
    for (const Tet& T : tet_) {
        const V3 e1 = x_[size_t(T.n[1])] - x_[size_t(T.n[0])], e2 = x_[size_t(T.n[2])] - x_[size_t(T.n[0])], e3 = x_[size_t(T.n[3])] - x_[size_t(T.n[0])];
        const float J = det3(e1, e2, e3) / (6.0f * T.V0) - 1.0f;
        for (int c = 0; c < 4; ++c) { sacc[size_t(T.n[c])] += J; scnt[size_t(T.n[c])] += 1.0f; }
    }
    for (size_t n = 0; n < N; ++n) {
        V3 d = w_[n] > 0 ? x_[n] - anchor(int(n)) : V3();
        if (!fin(d)) d = V3();
        out[n * 4] = d.x; out[n * 4 + 1] = d.y; out[n * 4 + 2] = d.z;
        out[n * 4 + 3] = std::clamp(scnt[n] > 0 ? sacc[n] / scnt[n] : 0.0f, -0.5f, 0.5f);
    }
}

} // namespace wc
