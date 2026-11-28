// The orifice deformer's maths (orifice.h). No GL.
#include "orifice.h"

#include <algorithm>
#include <cmath>

namespace wc {
namespace orifice {

namespace {
constexpr float kPi = 3.14159265f;
float clamp01(float v) { return std::isfinite(v) ? std::clamp(v, 0.0f, 1.0f) : 0.0f; }
float smooth(float a, float b, float x) {
    if (!(b > a)) return x >= b ? 1.0f : 0.0f;
    float t = clamp01((x - a) / (b - a));
    return t * t * (3 - 2 * t);
}
float ease(float dt, float tau) { return 1.0f - std::exp(-std::max(dt, 0.0f) / std::max(tau, 1e-4f)); }
} // namespace

float ToyShape::radiusAt(float s) const {
    if (!ok) return 0;
    const float step = length / kProfile;
    const float c0 = 0.5f * step;
    if (!(s > 0)) return 0;
    if (s < c0) return radius[0] * std::sqrt(clamp01(s / c0));   // the tip's round end
    const float x = (s - c0) / step;
    const int i = int(x);
    if (i >= kProfile - 1) return radius[kProfile - 1];
    const float t = x - float(i);
    return radius[i] + (radius[i + 1] - radius[i]) * t;
}

ToyShape measureToy(const float* pos, size_t n, bool doubleEnded) {
    ToyShape out;
    out.doubleEnded = doubleEnded;
    if (!pos || n < 8) return out;
    V3 c;
    for (size_t i = 0; i < n; ++i) c = c + V3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
    c = c * (1.0f / float(n));
    double cov[3][3] = {};
    for (size_t i = 0; i < n; ++i) {
        const V3 d = V3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]) - c;
        const double dv[3] = {d.x, d.y, d.z};
        for (int a = 0; a < 3; ++a) for (int b = 0; b < 3; ++b) cov[a][b] += dv[a] * dv[b];
    }
    double v[3] = {0.577, 0.37, 0.73};
    for (int it = 0; it < 60; ++it) {
        double w[3];
        for (int a = 0; a < 3; ++a) w[a] = cov[a][0] * v[0] + cov[a][1] * v[1] + cov[a][2] * v[2];
        double l = std::sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
        if (l < 1e-18) return out;
        for (int a = 0; a < 3; ++a) v[a] = w[a] / l;
    }
    const V3 u0 = V3(float(v[0]), float(v[1]), float(v[2])).normalized();
    V3 ax = u0;
    float smin = 1e30f, smax = -1e30f;
    std::vector<float> s(n);
    for (size_t i = 0; i < n; ++i) {
        s[i] = (V3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]) - c).dot(ax);
        smin = std::min(smin, s[i]); smax = std::max(smax, s[i]);
    }
    const float total = smax - smin;
    if (!(total > 1e-4f)) return out;
    constexpr int kSlabs = 40;
    std::vector<std::vector<float>> dist(kSlabs);
    for (size_t i = 0; i < n; ++i) {
        const V3 d = V3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]) - c;
        const float r = (d - ax * s[i]).length();
        const int k = std::clamp(int((s[i] - smin) / total * kSlabs), 0, kSlabs - 1);
        dist[size_t(k)].push_back(r);
    }
    std::vector<float> rad(kSlabs, -1.0f);
    for (int k = 0; k < kSlabs; ++k) {
        auto& dv = dist[size_t(k)];
        if (dv.size() < 3) continue;
        std::sort(dv.begin(), dv.end());
        const size_t from = dv.size() * 7 / 10;
        float sum = 0;
        for (size_t q = from; q < dv.size(); ++q) sum += dv[q];
        rad[size_t(k)] = sum / float(dv.size() - from);
    }
    // gaps filled from the neighbours
    for (int k = 0; k < kSlabs; ++k) if (rad[size_t(k)] < 0) {
        int a = k - 1, b = k + 1;
        while (a >= 0 && rad[size_t(a)] < 0) --a;
        while (b < kSlabs && rad[size_t(b)] < 0) ++b;
        if (a >= 0 && b < kSlabs) rad[size_t(k)] = 0.5f * (rad[size_t(a)] + rad[size_t(b)]);
        else if (a >= 0) rad[size_t(k)] = rad[size_t(a)];
        else if (b < kSlabs) rad[size_t(k)] = rad[size_t(b)];
        else rad[size_t(k)] = 0;
    }
    auto endMean = [&](bool high) { float m = 0; for (int k = 0; k < 5; ++k) m += rad[size_t(high ? kSlabs - 1 - k : k)]; return m / 5.0f; };
    const bool tipHigh = endMean(true) <= endMean(false);   // the narrower end is the tip
    if (!tipHigh) ax = ax * -1.0f;
    // radii from the tip
    std::vector<float> fromTip(kSlabs);
    for (int k = 0; k < kSlabs; ++k) fromTip[size_t(k)] = tipHigh ? rad[size_t(kSlabs - 1 - k)] : rad[size_t(k)];
    const float slab = total / kSlabs;
    float length;
    if (doubleEnded) {
        length = total * 0.5f;
    } else {
        std::vector<float> ref(fromTip.begin() + kSlabs / 10, fromTip.begin() + kSlabs / 2);
        std::sort(ref.begin(), ref.end());
        const float med = ref[ref.size() / 2];
        length = total * 0.92f;
        for (int k = kSlabs / 4; k < kSlabs; ++k) if (fromTip[size_t(k)] > 1.45f * med) { length = slab * float(k); break; }
    }
    length = std::max(length, 0.02f);
    out.length = length;
    out.totalLength = total;
    out.axis = ax;
    const float tipS = tipHigh ? smax : smin;
    out.tip = c + u0 * tipS;   // the axis through the centroid, at the tip's extreme
    float mx = 0;
    for (int i = 0; i < kProfile; ++i) {
        const float sc = (float(i) + 0.5f) / kProfile * length;   // metres from the tip
        const float x = sc / slab - 0.5f;
        const int a = std::clamp(int(std::floor(x)), 0, kSlabs - 1), b = std::min(a + 1, kSlabs - 1);
        const float t = std::clamp(x - float(a), 0.0f, 1.0f);
        out.radius[i] = fromTip[size_t(a)] + (fromTip[size_t(b)] - fromTip[size_t(a)]) * t;
        mx = std::max(mx, out.radius[i]);
    }
    out.maxRadius = mx;
    out.ok = mx > 1e-4f;
    return out;
}

// ---------------------------------------------------------------------------------------------------------------- the frame
namespace {
void completeFrame(Frame& f) {
    V3 y(0, 1, 0);
    f.lateral = (y - f.axis * y.dot(f.axis)).normalized();
    f.fwd = f.lateral.cross(f.axis).normalized();
}
} // namespace

Frame frameFromAnchor(const V3& point, const V3& inward, float radius, float u) {
    Frame f;
    f.centre = point;
    f.axis = inward.normalized();
    f.u = u;
    completeFrame(f);
    f.slitBack = f.slitFront = std::max(0.018f * u, radius * 1.2f);
    f.cleftDepth = 0.004f * u;
    f.ok = true;
    return f;
}

bool findHdVulva(const M2Vertex* verts, size_t bodyEnd, int pelvis, float u, float midY, Frame& out) {
    if (!verts || pelvis < 0 || !(u > 1e-4f)) return false;
    const float tol = 0.0010f * u;
    struct W { float x, z; };
    std::vector<W> walls;
    for (size_t i = 0; i < bodyEnd; ++i) {
        const M2Vertex& v = verts[i];
        float w = 0;
        for (int k = 0; k < 4; ++k) if (v.bones[k] == pelvis) w += float(v.weights[k]) / 255.0f;
        if (w < 0.98f) continue;
        if (std::fabs(v.pos[1] - midY) > tol || std::fabs(v.normal[1]) < 0.85f) continue;
        walls.push_back({v.pos[0], v.pos[2]});
    }
    if (walls.size() < 20) return false;
    std::sort(walls.begin(), walls.end(), [](const W& a, const W& b) { return a.x < b.x; });
    const float gap = 0.006f * u;
    size_t bestA = 0, bestB = 0;
    float bestExt = -1;
    size_t a = 0;
    for (size_t i = 1; i <= walls.size(); ++i) {
        if (i == walls.size() || walls[i].x - walls[i - 1].x > gap) {
            const float ext = walls[i - 1].x - walls[a].x;
            if (i - a >= 20 && ext > bestExt) { bestExt = ext; bestA = a; bestB = i; }
            a = i;
        }
    }
    if (bestExt < 0.012f * u) return false;
    float minX = 1e30f, maxX = -1e30f, minZ = 1e30f, maxZ = -1e30f;
    for (size_t i = bestA; i < bestB; ++i) {
        minX = std::min(minX, walls[i].x); maxX = std::max(maxX, walls[i].x);
        minZ = std::min(minZ, walls[i].z); maxZ = std::max(maxZ, walls[i].z);
    }
    Frame f;
    f.u = u;
    f.axis = V3(-0.17f, 0, 0.985f).normalized();   // up and a little back: the canal's line in a standing body
    f.centre = V3(0.5f * (minX + maxX), midY, minZ);
    f.slitBack = f.centre.x - minX;
    f.slitFront = maxX - f.centre.x;
    f.cleftDepth = std::max(maxZ - minZ, 0.003f * u);
    completeFrame(f);
    f.ok = true;
    out = f;
    return true;
}

// ---------------------------------------------------------------------------------------------------------------- dynamics
float entryRadius(const Frame& f, const ToyShape& toy, float depth) {
    if (!toy.ok || !(depth > 0)) return 0;
    const float r = toy.radiusAt(depth / f.u) * f.u;
    return r * smooth(0.0f, 0.008f * f.u, depth);
}

void stepDyn(Dyn& d, const Frame& f, const ToyShape* toy, bool present, float depth, float vibration, float dt) {
    if (!(dt > 0) || !std::isfinite(depth)) return;
    dt = std::min(dt, 0.1f);
    d.time += dt;
    const float raw = d.primed ? (depth - d.depth) / dt : 0.0f;
    d.speed += (raw - d.speed) * ease(dt, 0.05f);
    d.depth = depth;
    d.primed = true;
    const float target = present && toy ? entryRadius(f, *toy, depth) : 0.0f;
    d.radius += (target - d.radius) * ease(dt, target > d.radius ? kOpenTau : kCloseTau);
    if (target == 0 && d.radius < 0.0004f * f.u) d.radius = 0;
    float tdrag = 0;
    if (present && d.speed < 0) tdrag = std::min(-d.speed * 0.07f, 0.010f * f.u) * std::clamp(d.radius / (0.010f * f.u), 0.2f, 1.0f);
    d.drag += (tdrag - d.drag) * ease(dt, tdrag > d.drag ? 0.05f : kDragTau);
    if (d.drag < 1e-4f * f.u) d.drag = 0;
    if (present && depth > 0) {
        const float act = std::min(std::fabs(d.speed) / (0.10f * f.u), 1.0f);
        d.wet += (0.030f + 0.050f * act) * dt;
    } else {
        d.wet -= 0.004f * dt;
    }
    d.wet = clamp01(d.wet);
    const float vib = present ? clamp01(vibration) : 0.0f;
    d.tremor += (vib - d.tremor) * ease(dt, 0.12f);
    if (d.tremor < 1e-4f) d.tremor = 0;
}

// -------------------------------------------------------------------------------------------------------------------- deformer
Oval ovalFor(const Frame& f, float radius) {
    Oval o;
    const float skin = kSkinThickness * f.u;
    o.half = radius + skin;
    o.back = std::max(radius * 1.15f + skin, f.slitBack * 0.7f);
    o.front = std::max(radius * 1.15f + skin, f.slitFront * 0.7f);
    return o;
}

float regionWeight(const Frame& f, const V3& p) {
    const float R = kRegionRadius * f.u;
    const float d = (p - f.centre).length();
    return 1.0f - smooth(0.55f * R, R, d);
}

V3 deformPoint(const Frame& f, const ToyShape* toy, const Dyn& d, const V3& p, float* stretchOut) {
    if (stretchOut) *stretchOut = 0;
    const float wr = regionWeight(f, p);
    if (!(wr > 0) || !f.ok) return p;
    const V3 rel = p - f.centre;
    const float l = rel.dot(f.lateral), fw = rel.dot(f.fwd), h = rel.dot(f.axis);
    const float u = f.u;
    const float absl = std::fabs(l);
    const float side = std::clamp(l / std::max(absl, 0.00005f * u), -1.0f, 1.0f);   // (smooth through the midline)
    V3 out = p;
    float stretch = 0;
    if (d.radius > 1e-5f) {
        const Oval o = ovalFor(f, d.radius);
        const float E = fw >= 0 ? o.front : o.back;
        const float q = std::fabs(fw) < E ? 1.0f - (fw / E) * (fw / E) : 0.0f;
        const float g = std::sqrt(std::max(q, 0.0f));
        const float Rf = o.half + 0.022f * u;
        const float k = 1.0f - smooth(0.0f, Rf, absl);
        const float m = h <= f.cleftDepth ? 1.0f : 1.0f - smooth(f.cleftDepth, f.cleftDepth + 0.012f * u, h);
        const float dl = side * o.half * g * k * m * wr;
        const float x = clamp01(absl / Rf);
        const float bump = std::sin(kPi * x);
        const float bl = side * 0.30f * d.radius * g * bump * m * wr;      // the lips bulge outward: the volume the opening displaced
        const float bh = -0.16f * d.radius * g * bump * m * wr;            // and out of the body
        const float df = fw * 0.10f * std::min(d.radius / (0.02f * u), 1.5f) * k * wr;   // the ends of the slit are drawn out
        float dr = 0;
        if (d.drag > 0) {
            const float sg = o.half * 1.6f;
            const float wh = h < 0 ? 1.0f : 1.0f - smooth(0.0f, 0.02f * u, h);
            const float wf = std::fabs(fw) < E * 1.3f ? 1.0f : 0.0f;
            dr = -d.drag * std::exp(-(absl * absl) / (2 * sg * sg)) * wh * wf * wr;
        }
        out = p + f.lateral * (dl + bl) + f.fwd * df + f.axis * (bh + dr);
        stretch = clamp01((std::fabs(dl) + std::fabs(bl)) / (0.02f * u));
    }
    // the rim hugs the shaft: nothing stays inside it
    if (toy && toy->ok && d.depth > 0) {
        const V3 r2 = out - f.centre;
        const float h2 = r2.dot(f.axis);
        const float s = d.depth - h2;                    // along the toy from its tip
        if (s >= 0 && s <= toy->length * u) {
            const V3 rad = r2 - f.axis * h2;
            const float rl = rad.length();
            const float need = toy->radiusAt(s / u) * u + kSkinThickness * u;
            if (rl < need) {
                const V3 dir = rl > 1e-7f ? rad * (1.0f / rl) : f.lateral * (l >= 0 ? 1.0f : -1.0f);
                out = f.centre + f.axis * h2 + dir * need;
                stretch = std::max(stretch, 0.5f);
            }
        }
    }
    if (d.tremor > 0) {
        const float ph = d.time * 2.0f * kPi * 38.0f + (p.x * 211.0f + p.y * 173.0f + p.z * 97.0f);
        const float a = 0.00045f * u * d.tremor * wr;
        out = out + f.lateral * (std::sin(ph) * a) + f.axis * (std::cos(ph * 1.31f + 1.0f) * a * 0.6f);
    }
    if (stretchOut) *stretchOut = stretch;
    return out;
}

// -------------------------------------------------------------------------------------------------------------------- interior
void buildSleeve(const Frame& f, const ToyShape* toy, const Dyn& d, Sleeve& out, int rings, int segs) {
    out.v.clear(); out.idx.clear(); out.rings = 0; out.segs = 0;
    if (!f.ok || !(d.radius > 1e-4f)) return;
    rings = std::max(rings, 4); segs = std::max(segs, 8);
    const Oval o = ovalFor(f, d.radius);
    const float u = f.u;
    const float len = std::max(kSleeveDepth * u, d.depth + 0.02f * u);
    const float start = 0.004f * u;   // (the mouth just inside the plane: the skin hides its edge)
    out.rings = rings; out.segs = segs;
    for (int i = 0; i <= rings; ++i) {
        const float t = float(i) / float(rings);
        const float ax = start + (len - start) * t;
        float rl = o.half * (1.0f - 0.18f * t);                 // across: tapers a little
        float rf = o.front * 0.5f + o.back * 0.5f;
        rf = rf * (1.0f - 0.32f * t);
        if (toy && toy->ok && d.depth > 0) {
            // the lining lies just inside the skin's own walls (which the rim's push left at the shaft plus a skin thickness)
            const float s = d.depth - ax;
            if (s >= 0) { const float tr = toy->radiusAt(s / u) * u + 0.5f * kSkinThickness * u; rl = tr; rf = tr; }
            else { rl = std::max(rl * 0.8f, 0.5f * o.half); rf = rl; }
        }
        // the closed end: the last fifth rounds off
        const float cap = t > 0.8f ? std::sqrt(std::max(0.0f, 1.0f - ((t - 0.8f) / 0.2f) * ((t - 0.8f) / 0.2f))) : 1.0f;
        rl *= cap; rf *= cap;
        for (int j = 0; j < segs; ++j) {
            const float a = 2.0f * kPi * float(j) / float(segs);
            const float cl = std::cos(a), sf = std::sin(a);
            const V3 pos = f.centre + f.axis * ax + f.lateral * (cl * rl) + f.fwd * (sf * rf);
            V3 inward = (f.lateral * (-cl / std::max(rl, 1e-6f)) + f.fwd * (-sf / std::max(rf, 1e-6f))).normalized();
            out.v.insert(out.v.end(), {pos.x, pos.y, pos.z, inward.x, inward.y, inward.z, t});
        }
    }
    for (int i = 0; i < rings; ++i)
        for (int j = 0; j < segs; ++j) {
            const uint32_t a = uint32_t(i * segs + j), b = uint32_t(i * segs + (j + 1) % segs);
            const uint32_t c = uint32_t((i + 1) * segs + j), e = uint32_t((i + 1) * segs + (j + 1) % segs);
            out.idx.insert(out.idx.end(), {a, c, b, b, c, e});
        }
}

void buildRim(const Frame& f, const Dyn& d, Rim& out, int segs) {
    out.v.clear(); out.idx.clear();
    if (!f.ok || !(d.radius > 1e-4f)) return;
    segs = std::max(segs, 12);
    const Oval o = ovalFor(f, d.radius);
    const float u = f.u;
    const float bw = (0.0040f + 0.0040f * clamp01(d.radius / (0.02f * u))) * u;
    const float lift = -0.0008f * u;                              // a hair outside the plane
    for (int ring = 0; ring < 2; ++ring)
        for (int j = 0; j < segs; ++j) {
            const float a = 2.0f * kPi * float(j) / float(segs);
            const float cl = std::cos(a), sf = std::sin(a);
            const float E = sf >= 0 ? o.front : o.back;
            const float grow = ring ? bw : 0.0f;
            const V3 pos = f.centre + f.axis * lift + f.lateral * (cl * (o.half + grow)) + f.fwd * (sf * (E + grow));
            out.v.insert(out.v.end(), {pos.x, pos.y, pos.z, ring ? 0.0f : 0.9f, float(ring)});
        }
    for (int j = 0; j < segs; ++j) {
        const uint32_t a = uint32_t(j), b = uint32_t((j + 1) % segs), c = uint32_t(segs + j), e = uint32_t(segs + (j + 1) % segs);
        out.idx.insert(out.idx.end(), {a, c, b, b, c, e});
    }
}

void buildDisc(const Frame& f, const Dyn& d, Rim& out, int segs) {
    out.v.clear(); out.idx.clear();
    if (!f.ok || !(d.radius > 1e-4f)) return;
    segs = std::max(segs, 12);
    const Oval o = ovalFor(f, d.radius);
    const V3 c = f.centre + f.axis * (-0.0006f * f.u);
    out.v.insert(out.v.end(), {c.x, c.y, c.z, 0.97f, 0.0f});
    for (int j = 0; j < segs; ++j) {
        const float a = 2.0f * kPi * float(j) / float(segs);
        const float cl = std::cos(a), sf = std::sin(a);
        const float E = sf >= 0 ? o.front : o.back;
        const V3 p = c + f.lateral * (cl * o.half) + f.fwd * (sf * E);
        out.v.insert(out.v.end(), {p.x, p.y, p.z, 0.9f, 1.0f});
    }
    for (int j = 0; j < segs; ++j) out.idx.insert(out.idx.end(), {0u, uint32_t(1 + j), uint32_t(1 + (j + 1) % segs)});
}

} // namespace orifice
} // namespace wc
