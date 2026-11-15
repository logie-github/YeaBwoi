#include "bodyshape.h"

#include <algorithm>
#include <cmath>
#include <unordered_map>
#include <unordered_set>

namespace wc {

namespace {

constexpr float kCell = 0.05f;        // the records' grid (a place reads the 27 cells around its own)
constexpr float kReach = 0.05f;       // the farthest record a place is read from
constexpr float kExact = 1e-5f;       // a record at a vertex's own place
constexpr float kMoves = 2e-5f;       // a record that moves its place (20 microns)
constexpr float kFacing = 0.5f;       // records read only from the surface facing the same way
constexpr int kNear = 6;              // the fit is as wide as the 6th nearest record is far ...
constexpr float kWidth = 0.75f;       // ... times this,
constexpr float kMinSigma = 0.004f, kMaxSigma = 0.02f;   // within these
constexpr float kChart = 0.08f;       // a skin vertex reads the records of its own part of the skin's texture (not a hand's)

int64_t cellOf(float v) { return int64_t(std::floor(v / kCell)); }

// Solves the n x n system a x = b (row-major, n <= 6) by elimination with partial pivoting. False: singular.
bool solve(double* a, double* b, int n) {
    for (int c = 0; c < n; ++c) {
        int p = c;
        for (int r = c + 1; r < n; ++r) if (std::fabs(a[r * n + c]) > std::fabs(a[p * n + c])) p = r;
        if (std::fabs(a[p * n + c]) < 1e-12) return false;
        if (p != c) { for (int k = 0; k < n; ++k) std::swap(a[c * n + k], a[p * n + k]); std::swap(b[c], b[p]); }
        for (int r = c + 1; r < n; ++r) {
            double f = a[r * n + c] / a[c * n + c];
            if (f == 0) continue;
            for (int k = c; k < n; ++k) a[r * n + k] -= f * a[c * n + k];
            b[r] -= f * b[c];
        }
    }
    for (int c = n - 1; c >= 0; --c) {
        double s = b[c];
        for (int k = c + 1; k < n; ++k) s -= a[c * n + k] * b[k];
        b[c] = s / a[c * n + c];
    }
    return true;
}

// A place, to the hundredth of a millimetre (refine.py's: vertices there are one place of the surface).
struct PlaceKey {
    int64_t x, y, z;
    bool operator==(const PlaceKey& o) const { return x == o.x && y == o.y && z == o.z; }
};
struct PlaceHash {
    size_t operator()(const PlaceKey& k) const {
        return size_t(k.x * 73856093LL) ^ size_t(k.y * 19349663LL) ^ size_t(k.z * 83492791LL);
    }
};
PlaceKey placeOf(const float* p) {
    return {int64_t(std::llround(double(p[0]) * 1e5)), int64_t(std::llround(double(p[1]) * 1e5)), int64_t(std::llround(double(p[2]) * 1e5))};
}

V3 posOf(const M2Vertex& v) { return V3(v.pos[0], v.pos[1], v.pos[2]); }
V3 normalOf(const M2Vertex& v) { return V3(v.normal[0], v.normal[1], v.normal[2]); }

// refine.py's mix_bones: half of each corner's weight per bone, the 4 heaviest, bytes summing to 255.
void mixBones(const M2Vertex& a, const M2Vertex& b, M2Vertex& out) {
    int bone[8]; double w[8]; int n = 0;
    for (const M2Vertex* v : {&a, &b})
        for (int k = 0; k < 4; ++k) {
            if (!v->weights[k]) continue;
            int j = 0;
            while (j < n && bone[j] != v->bones[k]) ++j;
            if (j == n) { bone[n] = v->bones[k]; w[n] = 0; ++n; }
            w[j] += 0.5 * v->weights[k];
        }
    int order[8];
    for (int i = 0; i < n; ++i) order[i] = i;
    std::stable_sort(order, order + n, [&](int x, int y) { return w[x] > w[y]; });
    int top = std::min(n, 4);
    double tot = 0;
    for (int i = 0; i < top; ++i) tot += w[order[i]];
    for (int k = 0; k < 4; ++k) { out.weights[k] = 0; out.bones[k] = 0; }
    if (tot <= 0) { out.weights[0] = 255; out.bones[0] = a.bones[0]; return; }
    double raw[4]; int q[4], sum = 0;
    for (int i = 0; i < top; ++i) { raw[i] = w[order[i]] / tot * 255; q[i] = int(std::floor(raw[i])); sum += q[i]; }
    int rem[4];
    for (int i = 0; i < top; ++i) rem[i] = i;
    std::stable_sort(rem, rem + top, [&](int x, int y) { return raw[x] - q[x] > raw[y] - q[y]; });
    for (int i = 0; i < 255 - sum && i < top; ++i) q[rem[i]]++;
    for (int i = 0; i < top; ++i) { out.weights[i] = uint8_t(q[i]); out.bones[i] = uint8_t(bone[order[i]]); }
}

} // namespace

// ------------------------------------------------------------------------------------------------ the shape's field

size_t BodyField::bucket(int64_t x, int64_t y, int64_t z) const {
    uint64_t h = uint64_t(x) * 73856093ULL ^ uint64_t(y) * 19349663ULL ^ uint64_t(z) * 83492791ULL;
    return size_t(h & (buckets_ - 1));
}

BodyField::BodyField(const float* morph, size_t count) : m_(morph), count_(morph ? count : 0) {
    while (buckets_ < count_ * 2) buckets_ <<= 1;
    cellStart_.assign(buckets_ + 1, 0);
    movingCell_.assign(buckets_, 0);
    std::vector<size_t> where(count_);
    for (size_t i = 0; i < count_; ++i) {
        const float* r = m_ + i * kBodyMorphStride;
        where[i] = bucket(cellOf(r[0]), cellOf(r[1]), cellOf(r[2]));
        cellStart_[where[i] + 1]++;
        bool moves = std::fabs(r[3]) > kMoves || std::fabs(r[4]) > kMoves || std::fabs(r[5]) > kMoves ||
                     std::fabs(r[14] - r[12]) > 1e-6f || std::fabs(r[15] - r[13]) > 1e-6f;
        if (moves) movingCell_[where[i]] = 1;
    }
    for (size_t b = 0; b < buckets_; ++b) cellStart_[b + 1] += cellStart_[b];
    cellItems_.resize(count_);
    std::vector<uint32_t> fill(cellStart_.begin(), cellStart_.end() - 1);
    for (size_t i = 0; i < count_; ++i) cellItems_[fill[where[i]]++] = uint32_t(i);
}

bool BodyField::near(const V3& p) const {
    if (!count_) return false;
    int64_t cx = cellOf(p.x), cy = cellOf(p.y), cz = cellOf(p.z);
    for (int64_t dx = -1; dx <= 1; ++dx)
        for (int64_t dy = -1; dy <= 1; ++dy)
            for (int64_t dz = -1; dz <= 1; ++dz)
                if (movingCell_[bucket(cx + dx, cy + dy, cz + dz)]) return true;
    return false;
}

bool BodyField::at(const V3& p, const V3& n, const float uv[2], V3& d, V3& dn, float duv[2], bool* exact) const {
    d = V3(); dn = V3(); duv[0] = duv[1] = 0;
    if (exact) *exact = false;
    if (!near(p)) return false;
    struct Hit { float dist; uint32_t i; };
    thread_local std::vector<Hit> hits;
    hits.clear();
    int64_t cx = cellOf(p.x), cy = cellOf(p.y), cz = cellOf(p.z);
    const float* best = nullptr;
    int bestRank = -1;
    // the records within reach (the cells around this one)
    {
    const int ring = 1;
    const float reach = kReach;
    size_t cells[27];
    int nCells = 0;
    for (int64_t dx = -ring; dx <= ring; ++dx)
        for (int64_t dy = -ring; dy <= ring; ++dy)
            for (int64_t dz = -ring; dz <= ring; ++dz) cells[nCells++] = bucket(cx + dx, cy + dy, cz + dz);
    std::sort(cells, cells + nCells);
    nCells = int(std::unique(cells, cells + nCells) - cells);     // (two cells hashed alike: read once)
    for (int c = 0; c < nCells; ++c) {
                size_t b = cells[c];
                for (uint32_t k = cellStart_[b]; k < cellStart_[b + 1]; ++k) {
                    uint32_t i = cellItems_[k];
                    const float* r = m_ + size_t(i) * kBodyMorphStride;
                    V3 off = V3(r[0], r[1], r[2]) - p;
                    float d2 = off.dot(off);
                    if (d2 > reach * reach) continue;
                    float dist = std::sqrt(d2);
                    V3 rn(r[6], r[7], r[8]);
                    if (dist < kExact) {
                        // several looks vertices at one place (a seam, two sections): one the shape moves first (one it
                        // leaves is a piece the shape hides, its cap; the place moves as the surface there does), then
                        // the one this vertex is, by its texture coordinates (the looks' body ones are today's with u
                        // doubled) and its normal
                        bool same = uv && ((std::fabs(r[12] - uv[0] * 2) < 1e-4f && std::fabs(r[13] - uv[1]) < 1e-4f) ||
                                           (std::fabs(r[12] - uv[0]) < 1e-4f && std::fabs(r[13] - uv[1]) < 1e-4f));
                        bool moves = std::fabs(r[3]) > kMoves || std::fabs(r[4]) > kMoves || std::fabs(r[5]) > kMoves;
                        int rank = (moves ? 4 : 0) + (same ? 2 : 0) + (rn.dot(n) > 0.99f ? 1 : 0);
                        if (rank > bestRank) { bestRank = rank; best = r; }
                        continue;
                    }
                    if (rn.dot(n) < kFacing) continue;
                    if (uv && !(std::fabs(r[12] * 0.5f - uv[0]) < kChart && std::fabs(r[13] - uv[1]) < kChart * 2) &&
                        !(std::fabs(r[12] - uv[0]) < kChart && std::fabs(r[13] - uv[1]) < kChart * 2)) continue;
                    hits.push_back({dist, i});
                }
            }
    }
    auto uvShift = [&](const float* r, float out[2]) -> bool {
        float du = r[14] - r[12], dv = r[15] - r[13];
        if (!uv || (du == 0 && dv == 0)) return false;
        if (std::fabs(r[12] - uv[0] * 2) < 0.02f && std::fabs(r[13] - uv[1]) < 0.02f) { out[0] = du * 0.5f; out[1] = dv; return true; }
        if (std::fabs(r[12] - uv[0]) < 0.02f && std::fabs(r[13] - uv[1]) < 0.02f) { out[0] = du; out[1] = dv; return true; }
        return false;
    };
    if (best) {
        d = V3(best[3], best[4], best[5]);
        dn = V3(best[9], best[10], best[11]) - V3(best[6], best[7], best[8]);
        float s[2];
        if (uv && (bestRank & 2) && uvShift(best, s)) { duv[0] = s[0]; duv[1] = s[1]; }
        if (exact) *exact = true;
        return true;
    }
    if (hits.empty()) return false;
    // as wide as the records are dense here
    size_t k = std::min<size_t>(kNear, hits.size()) - 1;
    std::nth_element(hits.begin(), hits.begin() + k, hits.end(), [](const Hit& a, const Hit& b) { return a.dist < b.dist; });
    float sigma = std::clamp(hits[k].dist * kWidth, kMinSigma, kMaxSigma);
    // the surface's own two directions here
    V3 nn = n.length() > 1e-6f ? n.normalized() : V3(1, 0, 0);
    V3 e1 = (std::fabs(nn.z) < 0.9f ? V3(0, 0, 1) : V3(1, 0, 0)).cross(nn).normalized(), e2 = nn.cross(e1);
    // a quadratic over the surface (1 u v uu uv vv) fitted to the records' moves, nearer ones weighing more
    double A[36] = {0}, B[3][6] = {{0}}, wsum = 0, nsum[3] = {0, 0, 0}, uvsum[2] = {0, 0}, uvw = 0;
    double lo[3] = {1e9, 1e9, 1e9}, hi[3] = {-1e9, -1e9, -1e9};
    for (const Hit& h : hits) {
        if (h.dist > 2.5f * sigma) continue;
        const float* r = m_ + size_t(h.i) * kBodyMorphStride;
        double w = std::exp(-double(h.dist / sigma) * double(h.dist / sigma));
        V3 off = V3(r[0], r[1], r[2]) - p;
        double u = off.dot(e1) / sigma, v = off.dot(e2) / sigma;
        double f[6] = {1, u, v, u * u, u * v, v * v};
        for (int a = 0; a < 6; ++a) {
            for (int b = 0; b < 6; ++b) A[a * 6 + b] += w * f[a] * f[b];
            for (int c = 0; c < 3; ++c) B[c][a] += w * f[a] * r[3 + c];
        }
        wsum += w;
        for (int c = 0; c < 3; ++c) { lo[c] = std::min(lo[c], double(r[3 + c])); hi[c] = std::max(hi[c], double(r[3 + c])); }
        nsum[0] += w * (r[9] - r[6]); nsum[1] += w * (r[10] - r[7]); nsum[2] += w * (r[11] - r[8]);
        float s[2];
        if (uvShift(r, s)) { uvsum[0] += w * s[0]; uvsum[1] += w * s[1]; uvw += w; }
    }
    if (wsum <= 1e-9) return false;
    // a little held back where the records don't say much (a line of them, a few): towards the flat fit, then the mean
    for (int a = 1; a < 3; ++a) A[a * 6 + a] += 1e-3 * wsum;
    for (int a = 3; a < 6; ++a) A[a * 6 + a] += 2e-2 * wsum;
    // only the fit's value here is wanted: its first term, e0 . A^-1 B (A symmetric: solve A x = e0 once)
    double x[6] = {1, 0, 0, 0, 0, 0};
    bool solved = solve(A, x, 6);
    double dv[3];
    for (int c = 0; c < 3; ++c) {
        double v = 0;
        for (int k = 0; k < 6; ++k) v += x[k] * B[c][k];
        dv[c] = solved ? v : B[c][0] / wsum;
        // never past what the records around say (a fit leaning over a gap in them would)
        dv[c] = std::clamp(dv[c], lo[c], hi[c]);
    }
    d = V3(float(dv[0]), float(dv[1]), float(dv[2]));
    dn = V3(float(nsum[0] / wsum), float(nsum[1] / wsum), float(nsum[2] / wsum));
    if (uvw > 0) { duv[0] = float(uvsum[0] / uvw); duv[1] = float(uvsum[1] / uvw); }
    return d.length() > 1e-7f || dn.length() > 1e-6f || duv[0] != 0 || duv[1] != 0;
}

// ------------------------------------------------------------------------------------------------ the shape on a model

bool shapeBody(const M2Model& m, const std::vector<bool>& splittable, const float* morph, size_t count, int levels,
               ShapedBody& out) {
    out = ShapedBody();
    if (!morph || !count || m.vertices.empty()) return false;
    BodyField field(morph, count);

    // where the moving records are: nothing far from them is looked at
    V3 lo(1e9f, 1e9f, 1e9f), hi(-1e9f, -1e9f, -1e9f);
    bool any = false;
    for (size_t i = 0; i < count; ++i) {
        const float* r = morph + i * kBodyMorphStride;
        bool moves = std::fabs(r[3]) > kMoves || std::fabs(r[4]) > kMoves || std::fabs(r[5]) > kMoves ||
                     std::fabs(r[14] - r[12]) > 1e-6f || std::fabs(r[15] - r[13]) > 1e-6f;
        if (!moves) continue;
        any = true;
        lo = V3(std::min(lo.x, r[0]), std::min(lo.y, r[1]), std::min(lo.z, r[2]));
        hi = V3(std::max(hi.x, r[0]), std::max(hi.y, r[1]), std::max(hi.z, r[2]));
    }
    if (!any) return false;
    const float pad = kReach + 0.01f;
    lo = lo - V3(pad, pad, pad); hi = hi + V3(pad, pad, pad);
    auto inBox = [&](const float* p) { return p[0] >= lo.x && p[0] <= hi.x && p[1] >= lo.y && p[1] <= hi.y && p[2] >= lo.z && p[2] <= hi.z; };

    std::vector<M2Vertex>& V = out.vertices;
    V = m.vertices;
    // each section's triangles, and which vertices are the skin's (their texture coordinates say where on the body)
    const size_t ns = m.submeshes.size();
    std::vector<std::vector<uint32_t>> tris(ns);
    std::vector<uint8_t> skin(V.size(), 0);
    for (size_t s = 0; s < ns; ++s) {
        const M2Submesh& sm = m.submeshes[s];
        if (sm.indexStart + sm.indexCount > m.indices.size()) continue;
        tris[s].assign(m.indices.begin() + sm.indexStart, m.indices.begin() + sm.indexStart + sm.indexCount);
        tris[s].resize(tris[s].size() / 3 * 3);
        if (s < splittable.size() && splittable[s]) for (uint32_t i : tris[s]) if (i < skin.size()) skin[i] = 1;
    }
    // each vertex's move, read once at its place before the shape (computed[i]: 0 not yet, 1 none, 2 moves)
    struct Move { V3 d, dn; float duv[2] = {0, 0}; bool exact = false; };
    std::vector<uint8_t> computed(V.size(), 0);
    std::vector<Move> moves(V.size());
    auto moveOf = [&](uint32_t i) -> const Move* {
        if (i >= computed.size()) { computed.resize(V.size(), 0); moves.resize(V.size()); }
        if (!computed[i]) {
            computed[i] = 1;
            const M2Vertex& v = V[i];
            if (inBox(v.pos)) {
                Move mv;
                const float* uv = i >= skin.size() || skin[i] ? v.uv : nullptr;   // (the added ones are all the skin's)
                if (field.at(posOf(v), normalOf(v), uv, mv.d, mv.dn, mv.duv, &mv.exact)) { moves[i] = mv; computed[i] = 2; }
            }
        }
        return computed[i] == 2 ? &moves[i] : nullptr;
    };


    // split where the shape moves the skin, each level on the last's triangles
    for (int level = 0; level < levels; ++level) {
        using EdgeKey = std::pair<PlaceKey, PlaceKey>;
        struct EdgeHash { size_t operator()(const EdgeKey& e) const { PlaceHash h; return h(e.first) * 31 ^ h(e.second); } };
        auto edgeOf = [&](uint32_t a, uint32_t b) {
            PlaceKey ka = placeOf(V[a].pos), kb = placeOf(V[b].pos);
            auto less = [](const PlaceKey& x, const PlaceKey& y) { return x.x != y.x ? x.x < y.x : x.y != y.y ? x.y < y.y : x.z < y.z; };
            return less(kb, ka) ? EdgeKey(kb, ka) : EdgeKey(ka, kb);
        };
        std::unordered_set<EdgeKey, EdgeHash> marked;
        for (size_t s = 0; s < ns; ++s) {
            if (s >= splittable.size() || !splittable[s]) continue;
            const std::vector<uint32_t>& T = tris[s];
            for (size_t t = 0; t + 2 < T.size(); t += 3) {
                bool chosen = false;
                for (int j = 0; j < 3 && !chosen; ++j) {
                    const Move* mv = moveOf(T[t + j]);
                    chosen = mv && mv->d.length() > 5e-4f;
                }
                if (!chosen) continue;
                for (int j = 0; j < 3; ++j) marked.insert(edgeOf(T[t + j], T[t + (j + 1) % 3]));
            }
        }
        if (marked.empty()) break;
        // the normal at each place the split reads (the vertices there, averaged: twins along a seam split alike)
        std::unordered_map<PlaceKey, V3, PlaceHash> placeNormal;
        for (const EdgeKey& e : marked) { placeNormal.emplace(e.first, V3()); placeNormal.emplace(e.second, V3()); }
        for (const M2Vertex& v : V) {
            if (!inBox(v.pos)) continue;
            auto it = placeNormal.find(placeOf(v.pos));
            if (it != placeNormal.end()) it->second = it->second + normalOf(v);
        }
        for (auto& [k, nrm] : placeNormal) nrm = nrm.normalized();
        std::unordered_map<uint64_t, uint32_t> mids;
        auto midpoint = [&](uint32_t a, uint32_t b) -> uint32_t {
            uint64_t key = uint64_t(std::min(a, b)) << 32 | std::max(a, b);
            auto f = mids.find(key);
            if (f != mids.end()) return f->second;
            const M2Vertex& va = V[a];
            const M2Vertex& vb = V[b];
            V3 pa = posOf(va), pb = posOf(vb);
            V3 na = placeNormal[placeOf(va.pos)], nb = placeNormal[placeOf(vb.pos)];
            // Phong tessellation's midpoint: the edge's middle pulled onto both corners' tangent planes, halfway
            V3 mid = (pa + pb) * 0.5f;
            V3 qa = mid - na * (mid - pa).dot(na), qb = mid - nb * (mid - pb).dot(nb);
            V3 q = (qa + qb) * 0.5f;
            M2Vertex v;
            v.pos[0] = q.x; v.pos[1] = q.y; v.pos[2] = q.z;
            V3 n = (normalOf(va) + normalOf(vb)).normalized();
            v.normal[0] = n.x; v.normal[1] = n.y; v.normal[2] = n.z;
            for (int k = 0; k < 2; ++k) { v.uv[k] = (va.uv[k] + vb.uv[k]) * 0.5f; v.uv2[k] = (va.uv2[k] + vb.uv2[k]) * 0.5f; }
            mixBones(va, vb, v);
            V.push_back(v);
            ++out.added;
            uint32_t id = uint32_t(V.size() - 1);
            mids.emplace(key, id);
            return id;
        };
        for (size_t s = 0; s < ns; ++s) {
            if (s >= splittable.size() || !splittable[s]) continue;
            std::vector<uint32_t> o;
            const std::vector<uint32_t> T = tris[s];
            o.reserve(T.size() * 2);
            for (size_t t = 0; t + 2 < T.size(); t += 3) {
                uint32_t a = T[t], b = T[t + 1], c = T[t + 2];
                bool ab = marked.count(edgeOf(a, b)) > 0, bc = marked.count(edgeOf(b, c)) > 0, ca = marked.count(edgeOf(c, a)) > 0;
                int n = ab + bc + ca;
                if (!n) { o.insert(o.end(), {a, b, c}); continue; }
                ++out.split;
                if (n == 3) {
                    uint32_t x = midpoint(a, b), y = midpoint(b, c), z = midpoint(c, a);
                    o.insert(o.end(), {a, x, z, x, b, y, z, y, c, x, y, z});
                } else if (n == 2) {
                    // the corner between the two split edges cut off, the rest in two by the shorter diagonal
                    if (!ab) { uint32_t t0 = a; a = c; c = b; b = t0; }          // turned (keeping the winding) so (b, c) isn't split
                    else if (!ca) { uint32_t t0 = a; a = b; b = c; c = t0; }
                    uint32_t x = midpoint(a, b), z = midpoint(c, a);
                    o.insert(o.end(), {a, x, z});
                    if ((posOf(V[x]) - posOf(V[c])).length() < (posOf(V[z]) - posOf(V[b])).length()) o.insert(o.end(), {x, b, c, x, c, z});
                    else o.insert(o.end(), {x, b, z, z, b, c});
                } else if (ab) {
                    uint32_t x = midpoint(a, b); o.insert(o.end(), {a, x, c, x, b, c});
                } else if (bc) {
                    uint32_t y = midpoint(b, c); o.insert(o.end(), {a, b, y, a, y, c});
                } else {
                    uint32_t z = midpoint(c, a); o.insert(o.end(), {a, b, z, z, b, c});
                }
            }
            tris[s].swap(o);
        }
    }

    // the move itself, every vertex the field reaches (the model's own: any section; the added ones)
    std::vector<V3> rest(V.size());
    for (size_t i = 0; i < V.size(); ++i) rest[i] = posOf(V[i]);
    out.addedRest.assign(rest.begin() + m.vertices.size(), rest.end());
    for (size_t i = 0; i < V.size(); ++i) if (inBox(V[i].pos)) moveOf(uint32_t(i));

    // One move per place: the vertices there (a seam's twins, a section's edge, a cap) go together, or the skin opens. A
    // place whose move stands out of its neighbours' on the skin (read off a cap's or a fold's records) takes theirs; a
    // place at a looks vertex keeps the package's own.
    {
        std::unordered_map<PlaceKey, uint32_t, PlaceHash> placeId;
        std::vector<uint32_t> placeOfVertex(V.size(), UINT32_MAX);
        std::vector<V3> pd;
        std::vector<float> pw;
        std::vector<uint8_t> pexact;
        for (size_t i = 0; i < V.size(); ++i) {
            // what the field moved, and the skin around it it didn't (a place it read nothing at)
            if (computed[i] == 0 || (computed[i] == 1 && i < skin.size() && !skin[i])) continue;
            auto f = placeId.emplace(placeOf(&rest[i].x), uint32_t(pd.size()));
            if (f.second) { pd.push_back(V3()); pw.push_back(0); pexact.push_back(0); }
            uint32_t p = f.first->second;
            placeOfVertex[i] = p;
            if (moves[i].exact && !pexact[p]) { pexact[p] = 1; pd[p] = V3(); pw[p] = 0; }
            if (moves[i].exact || !pexact[p]) { pd[p] = pd[p] + moves[i].d; pw[p] += 1; }
        }
        for (size_t p = 0; p < pd.size(); ++p) if (pw[p] > 0) pd[p] = pd[p] * (1.0f / pw[p]);
        // the places' neighbours along the skin's edges (a place no move reaches counts as still)
        std::vector<std::vector<uint32_t>> nbr(pd.size());
        std::unordered_map<PlaceKey, uint32_t, PlaceHash> still;
        auto idOf = [&](uint32_t v) -> uint32_t {
            if (placeOfVertex[v] != UINT32_MAX) return placeOfVertex[v];
            PlaceKey k = placeOf(&rest[v].x);
            auto f = placeId.find(k);
            if (f != placeId.end()) return f->second;
            auto g = still.emplace(k, uint32_t(pd.size() + still.size()));
            return g.first->second;
        };
        for (size_t s = 0; s < ns; ++s) {
            if (s >= splittable.size() || !splittable[s]) continue;
            const std::vector<uint32_t>& T = tris[s];
            for (size_t t = 0; t + 2 < T.size(); t += 3) {
                if (placeOfVertex[T[t]] == UINT32_MAX && placeOfVertex[T[t + 1]] == UINT32_MAX && placeOfVertex[T[t + 2]] == UINT32_MAX) continue;
                uint32_t ids[3] = {idOf(T[t]), idOf(T[t + 1]), idOf(T[t + 2])};
                for (int j = 0; j < 3; ++j)
                    if (ids[j] < pd.size())
                        for (int o = 1; o <= 2; ++o) {
                            uint32_t q = ids[(j + o) % 3];
                            if (q != ids[j] && std::find(nbr[ids[j]].begin(), nbr[ids[j]].end(), q) == nbr[ids[j]].end()) nbr[ids[j]].push_back(q);
                        }
            }
        }
        for (int pass = 0; pass < 3; ++pass) {
            std::vector<V3> next = pd;
            for (size_t p = 0; p < pd.size(); ++p) {
                if (pexact[p] || nbr[p].size() < 3) continue;
                V3 mean;
                for (uint32_t q : nbr[p]) mean = mean + (q < pd.size() ? pd[q] : V3());
                mean = mean * (1.0f / float(nbr[p].size()));
                float off = (pd[p] - mean).length();
                if (off > std::max(0.004f, 0.4f * mean.length())) next[p] = mean;
            }
            pd.swap(next);
        }
        for (size_t i = 0; i < V.size(); ++i) {
            if (placeOfVertex[i] == UINT32_MAX) continue;
            moves[i].d = pd[placeOfVertex[i]];
            if (computed[i] == 1 && moves[i].d.length() > 1e-7f) computed[i] = 2;
        }
    }

    std::vector<uint8_t> moved(V.size(), 0);
    for (size_t i = 0; i < V.size(); ++i) {
        if (!inBox(V[i].pos)) continue;
        const Move* mv = moveOf(uint32_t(i));
        if (!mv) continue;
        M2Vertex& v = V[i];
        if (mv->d.length() > 1e-7f) { v.pos[0] += mv->d.x; v.pos[1] += mv->d.y; v.pos[2] += mv->d.z; moved[i] = 1; }
        if (mv->duv[0] != 0 || mv->duv[1] != 0) { v.uv[0] += mv->duv[0]; v.uv[1] += mv->duv[1]; ++out.uvs; moved[i] = 1; }
        if (mv->dn.length() > 1e-6f) moved[i] = 1;
        if (moved[i]) { ++out.moved; if (mv->exact) ++out.exact; }
    }
    if (!out.moved) { out = ShapedBody(); return false; }

    // the normals: turned by how much the shape turned the surface at each place (every skin section meeting there)
    struct Turn { V3 before, after; };
    std::unordered_map<PlaceKey, Turn, PlaceHash> turn;
    for (size_t i = 0; i < V.size(); ++i) if (moved[i]) turn.emplace(placeOf(&rest[i].x), Turn());
    for (size_t s = 0; s < ns; ++s) {
        if (s >= splittable.size() || !splittable[s]) continue;
        const std::vector<uint32_t>& T = tris[s];
        for (size_t t = 0; t + 2 < T.size(); t += 3) {
            uint32_t ix[3] = {T[t], T[t + 1], T[t + 2]};
            if (!inBox(&rest[ix[0]].x) && !inBox(&rest[ix[1]].x) && !inBox(&rest[ix[2]].x)) continue;
            Turn* at[3];
            bool some = false;
            for (int j = 0; j < 3; ++j) {
                auto it = turn.find(placeOf(&rest[ix[j]].x));
                at[j] = it == turn.end() ? nullptr : &it->second;
                some |= at[j] != nullptr;
            }
            if (!some) continue;
            V3 fr = (rest[ix[1]] - rest[ix[0]]).cross(rest[ix[2]] - rest[ix[0]]);
            V3 fs = (posOf(V[ix[1]]) - posOf(V[ix[0]])).cross(posOf(V[ix[2]]) - posOf(V[ix[0]]));
            for (int j = 0; j < 3; ++j) if (at[j]) { at[j]->before = at[j]->before + fr; at[j]->after = at[j]->after + fs; }
        }
    }
    for (size_t i = 0; i < V.size(); ++i) {
        if (!moved[i]) continue;
        M2Vertex& v = V[i];
        V3 n = normalOf(v);
        auto it = turn.find(placeOf(&rest[i].x));
        if (it != turn.end() && it->second.before.length() > 1e-12f && it->second.after.length() > 1e-12f)
            n = n + it->second.after.normalized() - it->second.before.normalized();
        else
            n = n + moves[i].dn;
        n = n.normalized();
        v.normal[0] = n.x; v.normal[1] = n.y; v.normal[2] = n.z;
    }

    // the index list again, section by section
    out.submeshes = m.submeshes;
    for (size_t s = 0; s < ns; ++s) {
        out.submeshes[s].indexStart = uint32_t(out.indices.size());
        out.submeshes[s].indexCount = uint32_t(tris[s].size());
        out.indices.insert(out.indices.end(), tris[s].begin(), tris[s].end());
    }
    return true;
}

} // namespace wc
