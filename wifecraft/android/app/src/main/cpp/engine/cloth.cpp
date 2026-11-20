#include "cloth.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <map>
#include <string>

namespace wc {

namespace {

// The segments the cloth collides with, and which of them are the torso.
// (the arms first: a forearm or a hand resting on the lap presses the cloth down onto it, and the thighs under it have the
// last word)
constexpr int kClothSegs[] = {kFleshLForearm, kFleshRForearm, kFleshLHand, kFleshRHand, kFleshPelvis, kFleshSpine, kFleshChest,
                              kFleshLThigh, kFleshRThigh, kFleshLCalf, kFleshRCalf, kFleshLFoot, kFleshRFoot};

bool isFlap(uint16_t id) { return id >= 100 && id / 100 == kClothGroup; }

// A segment's tip past its last joint (model units): a hand runs on from the wrist (flesh.h's hand is the wrist and a tip),
// so a hand resting on the lap is a capsule the cloth goes under, not nothing.
float segTip(int seg) { return seg == kFleshLHand || seg == kFleshRHand ? 0.12f : 0.0f; }

V3 vpos(const M2Vertex& v) { return V3(v.pos[0], v.pos[1], v.pos[2]); }

// A vertex skinned by `bones` (model space).
V3 skinned(const M2Vertex& v, const std::vector<M4>& bones) {
    V3 p = vpos(v), s(0, 0, 0);
    float total = 0;
    for (int k = 0; k < 4; ++k) {
        if (!v.weights[k] || v.bones[k] >= bones.size()) continue;
        float w = v.weights[k] / 255.0f;
        s = s + bones[v.bones[k]].point(p) * w;
        total += w;
    }
    return total > 0 ? s * (1.0f / total) : p;
}

// The weighted share-q value of a list (0 when empty).
float pct(std::vector<float>& v, float q) {
    if (v.empty()) return 0;
    size_t k = std::min(v.size() - 1, size_t(q * float(v.size() - 1) + 0.5f));
    std::nth_element(v.begin(), v.begin() + long(k), v.end());
    return v[k];
}

constexpr float kTwoPi = 6.28318531f;

// The flesh's reach at `t` (0..1 along the segment) in the direction `angle` (radians from n toward m): the sections'
// and directions' measures blended.
float profileAt(const float r[kClothSections][kClothBins], float t, float angle) {
    float u = t * float(kClothSections) - 0.5f;
    int s0 = 0, s1 = 0;
    float fs = 0;
    if (u <= 0) s0 = s1 = 0;
    else if (u >= float(kClothSections - 1)) s0 = s1 = kClothSections - 1;
    else { s0 = int(u); s1 = s0 + 1; fs = u - float(s0); }
    float a = angle / kTwoPi * float(kClothBins);
    a -= std::floor(a / float(kClothBins)) * float(kClothBins);
    int b0 = int(a) % kClothBins, b1 = (b0 + 1) % kClothBins;
    float fb = a - std::floor(a);
    float r0 = r[s0][b0] + (r[s0][b1] - r[s0][b0]) * fb;
    float r1 = r[s1][b0] + (r[s1][b1] - r[s1][b0]) * fb;
    return r0 + (r1 - r0) * fs;
}

// The 2D barycentric weights of (s, h) in the triangle (a, b, c) of (y, z) pairs; false when the triangle has no area.
bool bary2(float s, float h, const float a[2], const float b[2], const float c[2], float w[3]) {
    float d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
    if (std::fabs(d) < 1e-10f) return false;
    w[0] = ((b[1] - c[1]) * (s - c[0]) + (c[0] - b[0]) * (h - c[1])) / d;
    w[1] = ((c[1] - a[1]) * (s - c[0]) + (a[0] - c[0]) * (h - c[1])) / d;
    w[2] = 1 - w[0] - w[1];
    return true;
}

// The point of the triangle p (y, z pairs) nearest (s, h), in q; false when the triangle has no area.
bool closest2(float s, float h, const float p[3][2], float q[2]) {
    float w[3];
    if (!bary2(s, h, p[0], p[1], p[2], w)) return false;
    if (w[0] >= 0 && w[1] >= 0 && w[2] >= 0) { q[0] = s; q[1] = h; return true; }
    float best = 1e18f;
    for (int e = 0; e < 3; ++e) {
        const float* a = p[e];
        const float* b = p[(e + 1) % 3];
        float ex = b[0] - a[0], ez = b[1] - a[1], l2 = ex * ex + ez * ez;
        float t = l2 > 0 ? std::clamp(((s - a[0]) * ex + (h - a[1]) * ez) / l2, 0.0f, 1.0f) : 0.0f;
        float cx = a[0] + ex * t, cz = a[1] + ez * t;
        float d = (cx - s) * (cx - s) + (cz - h) * (cz - h);
        if (d < best) { best = d; q[0] = cx; q[1] = cz; }
    }
    return true;
}

// The heights where the vertical line y = s crosses the triangle's (y, z) outline: false when it doesn't.
bool crossing(float s, const float p[3][2], float& lo, float& hi) {
    bool any = false;
    for (int e = 0; e < 3; ++e) {
        const float* a = p[e];
        const float* b = p[(e + 1) % 3];
        float y0 = a[0], y1 = b[0];
        if ((s < std::min(y0, y1)) || (s > std::max(y0, y1))) continue;
        float z;
        if (std::fabs(y1 - y0) < 1e-9f) { z = a[1]; float z2 = b[1]; lo = any ? std::min({lo, z, z2}) : std::min(z, z2); hi = any ? std::max({hi, z, z2}) : std::max(z, z2); any = true; continue; }
        z = a[1] + (b[1] - a[1]) * (s - y0) / (y1 - y0);
        lo = any ? std::min(lo, z) : z;
        hi = any ? std::max(hi, z) : z;
        any = true;
    }
    return any;
}

// The affine inverse of a world matrix (a turn, a scale and a move).
M4 affineInverse(const M4& w) {
    float a = w.at(0, 0), b = w.at(0, 1), c = w.at(0, 2);
    float d = w.at(1, 0), e = w.at(1, 1), f = w.at(1, 2);
    float g = w.at(2, 0), h = w.at(2, 1), i = w.at(2, 2);
    float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    float det = a * A + b * B + c * C;
    M4 r;
    if (std::fabs(det) < 1e-12f) return r;
    float k = 1.0f / det;
    r.at(0, 0) = A * k; r.at(0, 1) = -(b * i - c * h) * k; r.at(0, 2) = (b * f - c * e) * k;
    r.at(1, 0) = B * k; r.at(1, 1) = (a * i - c * g) * k;  r.at(1, 2) = -(a * f - c * d) * k;
    r.at(2, 0) = C * k; r.at(2, 1) = -(a * h - b * g) * k; r.at(2, 2) = (a * e - b * d) * k;
    V3 t(w.at(0, 3), w.at(1, 3), w.at(2, 3));
    for (int row = 0; row < 3; ++row) r.at(row, 3) = -(r.at(row, 0) * t.x + r.at(row, 1) * t.y + r.at(row, 2) * t.z);
    return r;
}

float scaleOf(const M4& w) { return V3(w.at(0, 0), w.at(1, 0), w.at(2, 0)).length(); }

// One piece's grid from its triangles (model vertex numbers, three each), seen from its front (y across, z up). Its
// columns run from its top edge to its bottom edge, spread across each (a flap wider at the hem than at the waist keeps
// every column its whole length); each column's rows from where its line enters the triangles to where it leaves them.
bool buildPiece(const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& tris, int side, int cols, ClothPiece& out) {
    if (tris.size() < 3) return false;
    float hmin = 1e9f, hmax = -1e9f;
    for (uint32_t v : tris) { hmin = std::min(hmin, verts[v].pos[2]); hmax = std::max(hmax, verts[v].pos[2]); }
    if (!(hmax - hmin > 1e-4f)) return false;
    // the top edge's and the hem's ends: the outermost vertices of the top and bottom sixths
    float band = (hmax - hmin) / 6.0f;
    float tl[2] = {1e9f, 0}, tr[2] = {-1e9f, 0}, bl[2] = {1e9f, 0}, br[2] = {-1e9f, 0};
    for (uint32_t v : tris) {
        float y = verts[v].pos[1], z = verts[v].pos[2];
        if (z >= hmax - band) { if (y < tl[0]) { tl[0] = y; tl[1] = z; } if (y > tr[0]) { tr[0] = y; tr[1] = z; } }
        if (z <= hmin + band) { if (y < bl[0]) { bl[0] = y; bl[1] = z; } if (y > br[0]) { br[0] = y; br[1] = z; } }
    }
    float W = std::max(tr[0] - tl[0], br[0] - bl[0]);
    if (!(W > 1e-4f)) return false;
    cols = std::clamp(cols, 3, 15);
    const size_t nt = tris.size() / 3;
    auto tri2 = [&](size_t t, float p[3][2]) {
        for (int k = 0; k < 3; ++k) { const M2Vertex& v = verts[tris[t * 3 + size_t(k)]]; p[k][0] = v.pos[1]; p[k][1] = v.pos[2]; }
    };
    // each column: its line (from the top edge's point down to the hem's), and how far along it the triangles reach
    struct Col { float o[2], d[2], lo, hi; };
    std::vector<Col> colv(static_cast<size_t>(cols));
    float hsum = 0;
    for (int c = 0; c < cols; ++c) {
        float f = 0.01f + 0.98f * float(c) / float(cols - 1);
        float top[2] = {tl[0] + (tr[0] - tl[0]) * f, tl[1] + (tr[1] - tl[1]) * f};
        float bot[2] = {bl[0] + (br[0] - bl[0]) * f, bl[1] + (br[1] - bl[1]) * f};
        float dy = bot[0] - top[0], dz = bot[1] - top[1], dl = std::sqrt(dy * dy + dz * dz);
        if (dl < 1e-6f) return false;
        Col& cl = colv[size_t(c)];
        cl.o[0] = top[0]; cl.o[1] = top[1]; cl.d[0] = dy / dl; cl.d[1] = dz / dl;
        // in the column's own frame (across it, along it) its line is a = 0
        float lo = 1e9f, hi = -1e9f;
        for (size_t t = 0; t < nt; ++t) {
            float p[3][2], q[3][2], l, h;
            tri2(t, p);
            for (int k = 0; k < 3; ++k) {
                float ry = p[k][0] - cl.o[0], rz = p[k][1] - cl.o[1];
                q[k][0] = ry * -cl.d[1] + rz * cl.d[0];
                q[k][1] = ry * cl.d[0] + rz * cl.d[1];
            }
            if (crossing(0.0f, q, l, h)) { lo = std::min(lo, l); hi = std::max(hi, h); }
        }
        if (lo > hi) return false;
        cl.lo = lo; cl.hi = hi;
        hsum += hi - lo;
    }
    float H = hsum / float(cols);
    int rows = std::clamp(int(std::lround(float(cols - 1) * H / W)) + 1, 4, 16);
    out.side = side;
    out.cols = cols;
    out.rows = rows;
    out.points.clear();
    std::vector<V3> rest;
    for (int r = 0; r < rows; ++r) {
        for (int c = 0; c < cols; ++c) {
            const Col& cl = colv[size_t(c)];
            float f = 0.005f + 0.99f * float(r) / float(rows - 1);
            float along = cl.lo + (cl.hi - cl.lo) * f;
            float s = cl.o[0] + cl.d[0] * along, h = cl.o[1] + cl.d[1] * along;
            // the triangle the point lies in most surely (its smallest weight largest); outside them all (a column
            // past a waisted side), the nearest point of the piece's outline
            float best = -1e9f, bw[3] = {1, 0, 0};
            size_t bt = 0;
            for (size_t t = 0; t < nt; ++t) {
                float p[3][2], w[3];
                tri2(t, p);
                if (!bary2(s, h, p[0], p[1], p[2], w)) continue;
                float mn = std::min({w[0], w[1], w[2]});
                if (mn > best) { best = mn; bt = t; std::copy(w, w + 3, bw); }
            }
            if (best < -1e8f) return false;
            if (best < -1e-4f) {
                float nearest = 1e18f;
                for (size_t t = 0; t < nt; ++t) {
                    float p[3][2], q[2] = {s, h}, w[3];
                    tri2(t, p);
                    if (!closest2(s, h, p, q) || !bary2(q[0], q[1], p[0], p[1], p[2], w)) continue;
                    float d = (q[0] - s) * (q[0] - s) + (q[1] - h) * (q[1] - h);
                    if (d < nearest) { nearest = d; bt = t; std::copy(w, w + 3, bw); }
                }
            }
            float sum = 0;
            for (float& x : bw) { x = std::max(0.0f, x); sum += x; }
            if (sum <= 0) { bw[0] = 1; sum = 1; }
            ClothPoint pt;
            V3 at(0, 0, 0);
            for (int k = 0; k < 3; ++k) {
                pt.v[k] = tris[bt * 3 + size_t(k)];
                pt.w[k] = bw[k] / sum;
                const M2Vertex& v = verts[pt.v[k]];
                at = at + vpos(v) * pt.w[k];
                pt.uv[0] += v.uv[0] * pt.w[k];
                pt.uv[1] += v.uv[1] * pt.w[k];
            }
            pt.pinned = r < kClothPinnedRows;
            out.points.push_back(pt);
            rest.push_back(at);
        }
    }
    clothLinks(out, rest);
    return true;
}

// A piece's rest distances up and to the right, from its links along the grid.
void indexRests(ClothPiece& p) {
    p.restUp.assign(p.points.size(), 0.0f);
    p.restRight.assign(p.points.size(), 0.0f);
    for (const ClothLink& l : p.links) {
        if (l.kind != 0) continue;
        if (l.b == l.a + p.cols) p.restUp[l.b] = l.rest;
        else if (l.b == l.a + 1) p.restRight[l.a] = l.rest;
    }
}

// Each bone's segment (its nearest named ancestor's), as flesh.h counts them.
std::vector<int> segmentsOf(const M2Model& m) {
    std::vector<int> named(m.bones.size(), -1);
    for (const auto& [name, seg] : kFleshBoneSegs) {
        int b = m.findWowBone(name);
        if (b >= 0 && size_t(b) < named.size()) named[size_t(b)] = seg;
    }
    std::vector<int> segOf(m.bones.size(), -1);
    for (size_t b = 0; b < m.bones.size(); ++b) {
        int c = int(b), guard = 0;
        while (c >= 0 && named[size_t(c)] < 0 && guard++ < 64) c = m.bones[size_t(c)].parent;
        segOf[b] = c >= 0 ? named[size_t(c)] : -1;
    }
    return segOf;
}

// The joints flesh.h frames its segments with, from bone matrices and a world.
std::map<std::string, V3> jointsOf(const M2Model& m, const int jointBone[], const std::vector<M4>* bones, const M4& world) {
    std::map<std::string, V3> J;
    for (int j = 0; j < kFleshJointCount; ++j) {
        int b = jointBone[j];
        if (b < 0 || size_t(b) >= m.bones.size()) continue;
        V3 p = m.bones[size_t(b)].pivot;
        if (bones) { if (size_t(b) >= bones->size()) continue; p = (*bones)[size_t(b)].point(p); }
        J[kFleshJoints[j]] = world.point(p);
    }
    return J;
}

// The hips' frame this frame from the capsules: `side` from the right hip joint to the left (the thighs' tops), `axis` up the
// spine (Spine1 to Chest) square to it. False without both thighs.
bool hipFrame(const std::vector<ClothCapsule>& bodies, V3& axis, V3& side) {
    const ClothCapsule *l = nullptr, *r = nullptr, *spine = nullptr;
    for (const ClothCapsule& c : bodies) {
        if (c.seg == kFleshLThigh) l = &c;
        else if (c.seg == kFleshRThigh) r = &c;
        else if (c.seg == kFleshSpine) spine = &c;
    }
    if (!l || !r) return false;
    side = l->a0 - r->a0;
    if (side.length() < 1e-5f) return false;
    side = side.normalized();
    V3 up = spine ? spine->axis : V3(0, 0, 1);
    axis = up - side * up.dot(side);
    if (axis.length() < 1e-4f) axis = V3(0, 0, 1) - side * side.z;
    axis = axis.normalized();
    return true;
}

} // namespace

void clothLinks(ClothPiece& out, const std::vector<V3>& rest) {
    const int cols = out.cols, rows = out.rows, side = out.side;
    out.links.clear();
    out.tethers.clear();
    out.tris.clear();
    if (cols < 2 || rows < 2 || rest.size() != out.points.size() || rest.size() != size_t(cols * rows)) return;
    auto id = [&](int r, int c) { return uint16_t(r * cols + c); };
    auto link = [&](int r0, int c0, int r1, int c1, float stiffness, uint8_t kind) {
        if (r1 < 0 || r1 >= rows || c1 < 0 || c1 >= cols) return;
        ClothLink l;
        l.a = id(r0, c0); l.b = id(r1, c1);
        l.rest = (rest[l.b] - rest[l.a]).length();
        l.stiffness = stiffness;
        l.kind = kind;
        if (out.points[l.a].pinned && out.points[l.b].pinned) return;   // the pins hold their own distances
        out.links.push_back(l);
    };
    for (int r = 0; r < rows; ++r)
        for (int c = 0; c < cols; ++c) {
            link(r, c, r, c + 1, 1.0f, 0);
            link(r, c, r + 1, c, 1.0f, 0);
            link(r, c, r + 1, c + 1, 0.5f, 1);
            link(r, c + 1, r + 1, c, 0.5f, 1);
            link(r, c, r, c + 2, 0.4f, 2);
            link(r, c, r + 2, c, 0.4f, 2);
        }
    // each point hangs at most its column's length below its pin
    for (int c = 0; c < cols; ++c) {
        float along = 0;
        for (int r = 1; r < rows; ++r) {
            along += (rest[id(r, c)] - rest[id(r - 1, c)]).length();
            ClothLink t;
            t.a = id(0, c); t.b = id(r, c); t.rest = along; t.kind = 0;
            out.tethers.push_back(t);
        }
    }
    // the grid's triangles, wound (and its normals signed) to face the piece's side at rest
    const V3 facing(float(side), 0, 0);
    int mr = rows / 2, mc = cols / 2;
    V3 dx = rest[id(mr, std::min(mc + 1, cols - 1))] - rest[id(mr, std::max(mc - 1, 0))];
    V3 dy = rest[id(std::min(mr + 1, rows - 1), mc)] - rest[id(std::max(mr - 1, 0), mc)];
    out.normalSign = dx.cross(dy).dot(facing) >= 0 ? 1.0f : -1.0f;
    for (int r = 0; r + 1 < rows; ++r)
        for (int c = 0; c + 1 < cols; ++c) {
            uint16_t a = id(r, c), b = id(r, c + 1), d = id(r + 1, c), e = id(r + 1, c + 1);
            V3 n = (rest[b] - rest[a]).cross(rest[d] - rest[a]);
            bool keep = n.dot(facing) >= 0;
            if (keep) out.tris.insert(out.tris.end(), {a, b, d, b, e, d});
            else out.tris.insert(out.tris.end(), {a, d, b, b, d, e});
        }
    indexRests(out);
    // each row's width, and which way it runs along the side (model y: the hips' line at rest; clothRig sets it again
    // from the pelvis's own frame)
    out.restWidth.assign(size_t(rows), 0.0f);
    for (int r = 0; r < rows; ++r) out.restWidth[size_t(r)] = (rest[id(r, cols - 1)] - rest[id(r, 0)]).length();
    out.sideSign = rest[id(0, cols - 1)].y >= rest[id(0, 0)].y ? 1.0f : -1.0f;
}

ClothRig clothRig(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                  const std::vector<M2Submesh>& submeshes, const std::vector<uint16_t>& geosets, int cols) {
    ClothRig rig;
    for (int j = 0; j < kFleshJointCount; ++j) rig.jointBone[j] = m.findWowBone(kFleshJoints[j]);
    for (uint16_t g : geosets) if (isFlap(g)) rig.geosets.push_back(g);
    if (rig.geosets.empty()) return rig;
    int pelvis = m.findWowBone("Pelvis");
    if (pelvis < 0) pelvis = m.findWowBone("Root");
    if (pelvis < 0) return rig;
    const float px = m.bones[size_t(pelvis)].pivot.x;
    std::vector<uint32_t> sideTris[2];
    float u0 = 1e9f, v0 = 1e9f, u1 = -1e9f, v1 = -1e9f;
    for (const M2Submesh& sm : submeshes) {
        if (!isFlap(sm.id) || std::find(rig.geosets.begin(), rig.geosets.end(), sm.id) == rig.geosets.end()) continue;
        for (uint32_t k = sm.indexStart; k + 2 < sm.indexStart + sm.indexCount && k + 2 < indices.size(); k += 3) {
            uint32_t a = indices[k], b = indices[k + 1], c = indices[k + 2];
            if (a >= verts.size() || b >= verts.size() || c >= verts.size()) continue;
            float cx = (verts[a].pos[0] + verts[b].pos[0] + verts[c].pos[0]) / 3.0f;
            sideTris[cx >= px ? 0 : 1].insert(sideTris[cx >= px ? 0 : 1].end(), {a, b, c});
            for (uint32_t v : {a, b, c}) {
                u0 = std::min(u0, verts[v].uv[0]); u1 = std::max(u1, verts[v].uv[0]);
                v0 = std::min(v0, verts[v].uv[1]); v1 = std::max(v1, verts[v].uv[1]);
            }
        }
    }
    for (int s = 0; s < 2; ++s) {
        ClothPiece p;
        if (buildPiece(verts, sideTris[s], s == 0 ? 1 : -1, cols, p)) {
            p.first = uint32_t(rig.particles());
            rig.pieces.push_back(std::move(p));
        }
    }
    if (rig.pieces.empty()) return rig;
    rig.uv0[0] = u0; rig.uv0[1] = v0; rig.uv1[0] = u1; rig.uv1[1] = v1;

    // the body it collides with: each segment's flesh around flesh.h's frame, in the bind pose, at three sections
    std::map<std::string, V3> J = jointsOf(m, rig.jointBone, nullptr, M4());
    fleshAddHips(J);
    std::vector<int> segOf = segmentsOf(m);
    std::vector<uint8_t> used(verts.size(), 0);
    for (const M2Submesh& sm : submeshes) {
        if (isFlap(sm.id) || sm.id / 100 == 15) continue;   // not the flap itself, nor a cape
        if (!geosets.empty() && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t k = sm.indexStart; k < sm.indexStart + sm.indexCount && k < indices.size(); ++k)
            if (indices[k] < used.size()) used[indices[k]] = 1;
    }
    for (size_t i = 0; i < verts.size(); ++i) {
        if (!used[i]) continue;
        const M2Vertex& v = verts[i];
        int top = -1, topW = 0;
        for (int k = 0; k < 4; ++k) if (v.weights[k] > topW && v.bones[k] < segOf.size()) { topW = v.weights[k]; top = v.bones[k]; }
        if (top < 0) continue;
        int sg = segOf[size_t(top)];
        if (std::find(std::begin(kClothSegs), std::end(kClothSegs), sg) != std::end(kClothSegs)) rig.surface.push_back(uint32_t(i));
    }
    for (int seg : kClothSegs) {
        ClothBodySeg b;
        b.seg = seg;
        FleshFrame f;
        if (!fleshFrame(seg, J, segTip(seg), f) || f.len < 1e-4f) { rig.body.push_back(b); continue; }
        std::vector<float> st, en;
        float far[kClothSections][kClothBins] = {};
        int count[kClothSections][kClothBins] = {};
        for (size_t i = 0; i < verts.size(); ++i) {
            if (!used[i]) continue;
            const M2Vertex& v = verts[i];
            int top = -1, topW = 0;
            for (int k = 0; k < 4; ++k) if (v.weights[k] > topW && v.bones[k] < segOf.size()) { topW = v.weights[k]; top = v.bones[k]; }
            if (top < 0 || segOf[size_t(top)] != seg) continue;
            V3 q = vpos(v) - f.a0;
            float al = q.dot(f.axis), dn = q.dot(f.n), dm = q.dot(f.m);
            int sec = std::clamp(int(std::clamp(al / f.len, 0.0f, 0.999f) * float(kClothSections)), 0, kClothSections - 1);
            float ang = std::atan2(dm, dn);
            if (ang < 0) ang += kTwoPi;
            int bin = std::clamp(int(ang / kTwoPi * float(kClothBins) + 0.5f) % kClothBins, 0, kClothBins - 1);
            far[sec][bin] = std::max(far[sec][bin], std::sqrt(dn * dn + dm * dm));
            ++count[sec][bin];
            st.push_back(-al); en.push_back(al - f.len);
        }
        if (st.size() < 24) { rig.body.push_back(b); continue; }
        // a direction without vertices takes its neighbours' (around the section), a section without any its neighbour's
        bool any[kClothSections] = {};
        for (int sc = 0; sc < kClothSections; ++sc) {
            for (int k = 0; k < kClothBins; ++k) if (count[sc][k]) any[sc] = true;
            if (!any[sc]) continue;
            for (int k = 0; k < kClothBins; ++k) {
                if (count[sc][k]) { b.r[sc][k] = far[sc][k]; continue; }
                int lo = 1, hi = 1;
                while (lo < kClothBins && !count[sc][(k - lo + kClothBins) % kClothBins]) ++lo;
                while (hi < kClothBins && !count[sc][(k + hi) % kClothBins]) ++hi;
                float a0 = far[sc][(k - lo + kClothBins) % kClothBins], a1 = far[sc][(k + hi) % kClothBins];
                b.r[sc][k] = a0 + (a1 - a0) * float(lo) / float(lo + hi);
            }
        }
        for (int pass = 0; pass < kClothSections; ++pass)
            for (int sc = 0; sc < kClothSections; ++sc) {
                if (any[sc]) continue;
                int nb = sc > 0 && any[sc - 1] ? sc - 1 : sc + 1 < kClothSections && any[sc + 1] ? sc + 1 : -1;
                if (nb < 0) continue;
                std::copy(b.r[nb], b.r[nb] + kClothBins, b.r[sc]);
                any[sc] = true;
            }
        b.start = std::max(0.0f, pct(st, 0.95f));
        b.end = std::max(0.0f, pct(en, 0.95f));
        b.ok = any[0];
        rig.body.push_back(b);
    }
    rig.ok = true;
    // fitted to the body as made: pushed out of it in the bind pose, without gravity; each length the longer of the two
    std::vector<M4> bind(std::max<size_t>(1, m.bones.size()));
    std::vector<V3> posed;
    std::vector<ClothCapsule> caps;
    ClothSurface skin;
    clothPosed(rig, verts, bind, M4(), posed);
    clothCapsules(m, rig, bind, M4(), caps);
    clothSurface(rig, verts, bind, M4(), skin);
    V3 hipAxis, hipSide;
    if (hipFrame(caps, hipAxis, hipSide))
        for (ClothPiece& p : rig.pieces)
            if (posed.size() >= p.first + size_t(p.cols)) p.sideSign = (posed[p.first + size_t(p.cols - 1)] - posed[p.first]).dot(hipSide) >= 0 ? 1.0f : -1.0f;
    ClothSim fit;
    fit.setRig(&rig);
    fit.setGravity(false);
    fit.step(posed, caps, &skin, 1.0f, 0);
    const std::vector<V3>& x = fit.positions();
    if (x.size() == rig.particles()) {
        for (ClothPiece& p : rig.pieces) {
            for (ClothLink& l : p.links) l.rest = std::max(l.rest, (x[p.first + l.b] - x[p.first + l.a]).length());
            for (ClothLink& t : p.tethers) {
                // the column's length from its pin down to it, along the fitted links
                float along = 0;
                const int c = t.b % p.cols, r = t.b / p.cols;
                for (int k = 1; k <= r; ++k) {
                    uint16_t a = uint16_t((k - 1) * p.cols + c), b = uint16_t(k * p.cols + c);
                    for (const ClothLink& l : p.links) if (l.kind == 0 && l.a == a && l.b == b) { along += l.rest; break; }
                }
                t.rest = std::max(t.rest, along);
            }
            indexRests(p);
        }
    }
    return rig;
}

bool clothUvRect(const M2Model& m, const std::vector<uint16_t>& geosets, int mw, int mh, int margin, int out[4]) {
    float u0 = 1e9f, v0 = 1e9f, u1 = -1e9f, v1 = -1e9f;
    for (const M2Submesh& sm : m.submeshes) {
        if (!isFlap(sm.id) || std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t k = sm.indexStart; k < sm.indexStart + sm.indexCount && k < m.indices.size(); ++k) {
            uint32_t v = m.indices[k];
            if (v >= m.vertices.size()) continue;
            u0 = std::min(u0, m.vertices[v].uv[0]); u1 = std::max(u1, m.vertices[v].uv[0]);
            v0 = std::min(v0, m.vertices[v].uv[1]); v1 = std::max(v1, m.vertices[v].uv[1]);
        }
    }
    if (u0 > u1 || mw <= 0 || mh <= 0) return false;
    int x0 = std::clamp(int(std::floor(u0 * float(mw))) - margin, 0, mw), x1 = std::clamp(int(std::ceil(u1 * float(mw))) + margin, 0, mw);
    int y0 = std::clamp(int(std::floor(v0 * float(mh))) - margin, 0, mh), y1 = std::clamp(int(std::ceil(v1 * float(mh))) + margin, 0, mh);
    if (x1 <= x0 || y1 <= y0) return false;
    out[0] = x0; out[1] = y0; out[2] = x1 - x0; out[3] = y1 - y0;
    return true;
}

void clothPosed(const ClothRig& rig, const std::vector<M2Vertex>& verts, const std::vector<M4>& bones, const M4& world,
                std::vector<V3>& out) {
    out.resize(rig.particles());
    size_t i = 0;
    for (const ClothPiece& p : rig.pieces)
        for (const ClothPoint& pt : p.points) {
            V3 s(0, 0, 0);
            for (int k = 0; k < 3; ++k) if (pt.w[k] > 0 && pt.v[k] < verts.size()) s = s + skinned(verts[pt.v[k]], bones) * pt.w[k];
            out[i++] = world.point(s);
        }
}

void clothSurface(const ClothRig& rig, const std::vector<M2Vertex>& verts, const std::vector<M4>& bones, const M4& world,
                  ClothSurface& out) {
    out.p.resize(rig.surface.size());
    out.n.resize(rig.surface.size());
    for (size_t i = 0; i < rig.surface.size(); ++i) {
        const M2Vertex& v = verts[rig.surface[i]];
        V3 p = vpos(v), nn(v.normal[0], v.normal[1], v.normal[2]), s(0, 0, 0), sn(0, 0, 0);
        float total = 0;
        for (int k = 0; k < 4; ++k) {
            if (!v.weights[k] || v.bones[k] >= bones.size()) continue;
            float w = v.weights[k] / 255.0f;
            const M4& b = bones[v.bones[k]];
            s = s + b.point(p) * w;
            sn = sn + V3(b.at(0, 0) * nn.x + b.at(0, 1) * nn.y + b.at(0, 2) * nn.z, b.at(1, 0) * nn.x + b.at(1, 1) * nn.y + b.at(1, 2) * nn.z,
                         b.at(2, 0) * nn.x + b.at(2, 1) * nn.y + b.at(2, 2) * nn.z) * w;
            total += w;
        }
        if (total <= 0) { s = p; sn = nn; } else s = s * (1.0f / total);
        out.p[i] = world.point(s);
        V3 wn(world.at(0, 0) * sn.x + world.at(0, 1) * sn.y + world.at(0, 2) * sn.z, world.at(1, 0) * sn.x + world.at(1, 1) * sn.y + world.at(1, 2) * sn.z,
              world.at(2, 0) * sn.x + world.at(2, 1) * sn.y + world.at(2, 2) * sn.z);
        out.n[i] = wn.normalized();
    }
}

void clothCapsules(const M2Model& m, const ClothRig& rig, const std::vector<M4>& bones, const M4& world,
                   std::vector<ClothCapsule>& out) {
    out.clear();
    std::map<std::string, V3> J = jointsOf(m, rig.jointBone, &bones, world);
    fleshAddHips(J);
    const float k = scaleOf(world);
    for (const ClothBodySeg& b : rig.body) {
        if (!b.ok) continue;
        FleshFrame f;
        if (!fleshFrame(b.seg, J, segTip(b.seg) * k, f) || f.len < 1e-5f) continue;
        ClothCapsule c;
        c.a0 = f.a0; c.a1 = f.a1; c.axis = f.axis; c.n = f.n; c.m = f.m; c.len = f.len;
        for (int sc = 0; sc < kClothSections; ++sc)
            for (int d = 0; d < kClothBins; ++d) { c.r[sc][d] = b.r[sc][d] * k; c.reach = std::max(c.reach, c.r[sc][d]); }
        c.start = b.start * k; c.end = b.end * k;
        c.seg = b.seg;
        c.torso = b.seg == kFleshPelvis || b.seg == kFleshSpine || b.seg == kFleshChest;
        // a thigh keeps the flaps on their sides of it while it hangs (standing, walking); raised (seated, kneeling) the
        // front flap lies over its top and the back flap goes round it; a shin or a foot lying near the level (kneeling,
        // sitting on the ground) has both laid over it
        const bool thigh = b.seg == kFleshLThigh || b.seg == kFleshRThigh;
        const bool lower = b.seg == kFleshLCalf || b.seg == kFleshRCalf || b.seg == kFleshLFoot || b.seg == kFleshRFoot;
        const bool arm = b.seg == kFleshLHand || b.seg == kFleshRHand || b.seg == kFleshLForearm || b.seg == kFleshRForearm;
        c.sided = c.torso || (thigh && c.axis.z < -0.6f);
        V3 up = V3(0, 0, 1) - c.axis * c.axis.z;
        float ul = up.length();
        c.up = ul > 1e-4f ? up * (1.0f / ul) : c.n;
        c.drape = thigh && !c.sided ? 1 : lower && std::fabs(c.axis.z) < 0.6f ? 3 : 0;
        // a forearm or a hand lying across (on the lap) has the front flap under it (pushed down, never over its back)
        if (arm && std::fabs(c.axis.z) < 0.6f) { c.up = c.up * -1.0f; c.drape = 1; }
        out.push_back(c);
    }
    // lying down, nothing is laid over the top of a limb (her top is her side or her front): the limbs are round
    V3 axis, side;
    if (hipFrame(out, axis, side) && axis.z < 0.5f)
        for (ClothCapsule& c : out) c.drape = 0;
}

float clothCapsuleDepth(const ClothCapsule& c, const V3& p, bool& within) {
    V3 q = p - c.a0;
    float al = q.dot(c.axis);
    within = al >= -c.start && al <= c.len + c.end;
    float t = c.len > 0 ? std::clamp(al / c.len, 0.0f, 1.0f) : 0.0f;
    float dn = q.dot(c.n), dm = q.dot(c.m);
    float ang = std::atan2(dm, dn);
    return std::sqrt(dn * dn + dm * dm) - profileAt(c.r, t, ang < 0 ? ang + kTwoPi : ang);
}

bool clothCollide(const ClothCapsule& c, int side, float standoff, V3& p, V3& normal) {
    V3 q = p - c.a0;
    float al = q.dot(c.axis);
    if (al < -c.start - standoff || al > c.len + c.end + standoff) return false;
    float dn = q.dot(c.n), dm = q.dot(c.m);
    float r2 = dn * dn + dm * dm, most = c.reach + standoff;
    if (r2 >= most * most) return false;
    float t = c.len > 0 ? std::clamp(al / c.len, 0.0f, 1.0f) : 0.0f;
    const bool drapes = side != 0 && (c.drape & (side > 0 ? 1 : 2));
    if (drapes || (side != 0 && c.sided)) {
        // the torso and the thighs keep a front piece in front of them and a back piece behind them: pushed straight out
        // to the front (or back) surface at the same place across it, so a flap lying over the belly isn't spread sideways
        // and a thigh swinging into it carries it along rather than passing between its points. A limb lying across lays
        // the cloth over its top the same way (`up`): over the lap, never squeezed out between the thighs.
        const V3 dir = drapes ? c.up : c.n * float(side);
        const V3 across = c.axis.cross(dir);
        float out = q.dot(dir), lat = q.dot(across);   // how far toward that side, and across it
        auto reach = [&](float o) {
            V3 d = dir * o + across * lat;
            float ang = std::atan2(d.dot(c.m), d.dot(c.n));
            return profileAt(c.r, t, ang < 0 ? ang + kTwoPi : ang) + standoff;
        };
        float guess = std::max(out, 0.0f);
        float want = 0;
        for (int k = 0; k < 3; ++k) {
            want = reach(guess);
            if (std::fabs(lat) >= want) return false;
            guess = std::sqrt(want * want - lat * lat);
        }
        if (out >= guess) return false;
        p = p + dir * (guess - out);
        normal = dir;
        return true;
    }
    bool turned = false;
    float ang = std::atan2(dm, dn);
    float R = profileAt(c.r, t, ang < 0 ? ang + kTwoPi : ang);
    if (R <= 0) return false;
    float r = std::sqrt(r2), want = R + standoff;
    if (r >= want && !turned) return false;
    V3 out;
    if (r < 1e-6f) out = c.n * (side < 0 ? -1.0f : 1.0f);
    else out = (c.n * dn + c.m * dm) * (1.0f / r);
    V3 target = c.a0 + c.axis * al + out * std::max(r, want);
    normal = out;
    p = target;
    return true;
}

// ----------------------------------------------------------------------------------------------------------- the sim

namespace {

ClothCapsule lerpCapsule(const ClothCapsule& a, const ClothCapsule& b, float f) {
    ClothCapsule c = b;
    c.a0 = lerp(a.a0, b.a0, f);
    c.a1 = lerp(a.a1, b.a1, f);
    V3 ax = c.a1 - c.a0;
    c.len = ax.length();
    c.axis = c.len > 1e-6f ? ax * (1.0f / c.len) : b.axis;
    V3 n = lerp(a.n, b.n, f);
    n = (n - c.axis * n.dot(c.axis)).normalized();
    c.n = n;
    c.m = c.axis.cross(n);
    return c;
}

} // namespace

namespace {
uint64_t cellKey(int x, int y, int z) {
    return (uint64_t(uint32_t(x + (1 << 20)) & 0x1FFFFF) << 42) | (uint64_t(uint32_t(y + (1 << 20)) & 0x1FFFFF) << 21) |
           uint64_t(uint32_t(z + (1 << 20)) & 0x1FFFFF);
}
} // namespace

namespace {

// How much each free point is drawn toward the game's posed flap: fully where that lies outside the body, not at all where
// it lies kFollowDeep in it (seated, the game's flap runs through the thighs: the cloth is left to lie over them).
void followWeights(const ClothRig& rig, const std::vector<V3>& posed, const std::vector<ClothCapsule>& bodies, float scale,
                   std::vector<float>& out) {
    out.assign(posed.size(), 1.0f);
    for (const ClothPiece& p : rig.pieces)
        for (size_t i = 0; i < p.points.size(); ++i) {
            if (p.points[i].pinned) continue;
            V3 x = posed[p.first + i], nrm;
            for (const ClothCapsule& c : bodies) clothCollide(c, p.side, 0.0f, x, nrm);
            float deep = (x - posed[p.first + i]).length() / std::max(scale, 1e-4f);
            out[p.first + i] = std::clamp(1.0f - (deep - ClothSim::kFollowDeep * 0.25f) / (ClothSim::kFollowDeep * 0.75f), 0.0f, 1.0f);
        }
}


// v turned by `angle` (radians) about the unit axis a.
V3 turned(const V3& v, const V3& a, float angle) {
    float c = std::cos(angle), s = std::sin(angle);
    return v * c + a.cross(v) * s + a * (a.dot(v) * (1 - c));
}

} // namespace

void ClothSim::step(const std::vector<V3>& posed, const std::vector<ClothCapsule>& bodies, const ClothSurface* skin, float scale,
                    uint32_t nowMs) {
    steps_ = 0;
    if (!rig_ || posed.size() != rig_->particles() || posed.empty()) return;
    // the skin, filed by cell for the nearest-vertex look-ups
    skin_ = skin && !skin->p.empty() && skin->p.size() == skin->n.size() ? skin : nullptr;
    cells_.clear();
    if (skin_) {
        cell_ = kSkinReach * scale;
        cells_.reserve(skin_->p.size());
        for (size_t i = 0; i < skin_->p.size(); ++i) {
            const V3& p = skin_->p[i];
            cells_.push_back({cellKey(int(std::floor(p.x / cell_)), int(std::floor(p.y / cell_)), int(std::floor(p.z / cell_))), uint32_t(i)});
        }
        std::sort(cells_.begin(), cells_.end());
    }
    const float dt = 1.0f / float(kHz);
    const size_t n = posed.size();
    target_ = posed;
    followWeights(*rig_, posed, bodies, scale, followW_);
    bool restart = !started_ || x_.size() != n || nowMs < last_ || nowMs - last_ > kMaxFrameMs;
    if (!restart) {
        for (const ClothPiece& p : rig_->pieces)
            for (size_t i = 0; i < p.points.size(); ++i)
                if (p.points[i].pinned && (posed[p.first + i] - lastPosed_[p.first + i]).length() > kTeleport * scale) { restart = true; break; }
    }
    if (restart || lastBodies_.size() != bodies.size()) {
        if (restart) {
            // hung from the game's flap as it lies out of the body
            x_ = target_;
            xp_ = target_;
            contact_.assign(n, V3());
            for (int k = 0; k < kSettleSteps; ++k) substep(posed, target_, bodies, scale, dt);
            for (size_t i = 0; i < n; ++i) xp_[i] = x_[i];   // settled: at rest
            started_ = true;
            last_ = nowMs;
            acc_ = 0;
            lastPosed_ = posed;
            lastTarget_ = target_;
            lastBodies_ = bodies;
            return;
        }
        lastBodies_ = bodies;
    }
    if (lastTarget_.size() != n) lastTarget_ = target_;
    acc_ += (nowMs - last_) * uint32_t(kHz);
    last_ = nowMs;
    int steps = int(acc_ / 1000u);
    acc_ %= 1000u;
    if (steps > kMaxSteps) { steps = kMaxSteps; acc_ = 0; }
    // far from the game's flap (a pose change the cloth can't follow by itself: sitting down, the thighs rise under it and
    // nothing carries it along them), it is drawn toward it harder until it is near again
    if (gravity_ && followOn_) {
        float far = 0;
        float wsum = 0;
        for (size_t i = 0; i < n; ++i) { far += (x_[i] - target_[i]).length() * followW_[i]; wsum += followW_[i]; }
        far /= std::max(wsum, 1.0f) * std::max(scale, 1e-3f);
        float k = std::clamp((far - kFarFrom) / (kFarTo - kFarFrom), 0.0f, 1.0f);
        follow_ = kFollow + (kFollowFar - kFollow) * k * k * (3 - 2 * k);
    }
    for (int s = 0; s < steps; ++s) {
        float f = float(s + 1) / float(steps);
        pinsNow_.resize(n);
        targetNow_.resize(n);
        for (size_t i = 0; i < n; ++i) { pinsNow_[i] = lerp(lastPosed_[i], posed[i], f); targetNow_[i] = lerp(lastTarget_[i], target_[i], f); }
        bodiesNow_.resize(bodies.size());
        for (size_t b = 0; b < bodies.size(); ++b) bodiesNow_[b] = lerpCapsule(lastBodies_[b], bodies[b], f);
        substep(pinsNow_, targetNow_, bodiesNow_, scale, dt);
    }
    steps_ = steps;
    lastPosed_ = posed;
    lastTarget_ = target_;
    lastBodies_ = bodies;
}

void ClothSim::substep(const std::vector<V3>& pins, const std::vector<V3>& targets, const std::vector<ClothCapsule>& bodies,
                       float scale, float dt) {
    const V3 g(0, 0, gravity_ ? -kGravity * dt * dt : 0.0f);
    V3 hipAxis, hipSide;
    const bool hips = hipFrame(bodies, hipAxis, hipSide);
    for (const ClothPiece& p : rig_->pieces)
        for (size_t i = 0; i < p.points.size(); ++i) {
            size_t k = p.first + i;
            if (p.points[i].pinned) {
                // the top edge goes where the skinned flap's top goes, but on the hips' surface rather than in them (and,
                // seated, on the raised thighs' top: the front flap's waist lies on the lap)
                V3 at = pins[k], nrm;
                for (const ClothCapsule& c : bodies)
                    if (c.torso || (c.drape & 1)) clothCollide(c, p.side, kStandoff * scale, at, nrm);
                xp_[k] = x_[k];
                x_[k] = at;
                continue;
            }
            V3 v = (x_[k] - xp_[k]) * (1.0f - kDamping);
            // a stride's sideways throw is lost faster than the rest: the flap swings with the legs, it isn't flicked out
            if (hips) v = v - hipSide * (v.dot(hipSide) * kSideDamping);
            xp_[k] = x_[k];
            // a little of the way toward where the game's own flap is this frame, out of the body (its bones' drape: over
            // the lap when seated), so a pose change carries the cloth with it instead of piling it at the waist
            x_[k] = x_[k] + v + g + (targets[k] - x_[k]) * (follow_ * followW_[k]);
        }
    std::fill(contact_.begin(), contact_.end(), V3());
    for (int it = 0; it < kIterations; ++it) solve(bodies, scale, it % 2 == 1);
    // the iterations leave the hanging cloth longer than it is (its weight on the links above): no length past its give,
    // down each column from its pin and out each row from its middle, then the body once more
    for (int k = 0; k < 2; ++k) {
        skinPass(scale);
        inextensible(scale);
        collide(bodies, scale);
    }
    // no row turned edge-on; the body; then no link past kMaxStrain, a point the body holds barely moving for it (the free
    // cloth gives way to the body, so the flap neither stretches over a lap nor sinks into it)
    twist(bodies);
    collide(bodies, scale);
    skinPass(scale);
    strainLimit(scale);
    // a point touching the body doesn't bounce off it (the push out of it is no speed) and loses some of its sliding
    // along it; no point moves faster than kMaxSpeed (a limb's sweep pushes the cloth aside, it doesn't throw it)
    const float most = kMaxSpeed * scale * dt;
    for (size_t k = 0; k < x_.size(); ++k) {
        V3 d = x_[k] - xp_[k];
        const V3& nrm = contact_[k];
        if (nrm.x != 0 || nrm.y != 0 || nrm.z != 0) {
            float out = d.dot(nrm);
            V3 tangent = d - nrm * out;
            d = tangent * (1.0f - kFriction) + nrm * std::min(out, 0.0f);
        }
        float len = d.length();
        if (len > most) d = d * (most / len);
        xp_[k] = x_[k] - d;
    }
}

// Every link along the grid no longer than (1 + kMaxStrain) of its rest: both ends drawn together, a pinned end not at all
// and an end touching the body a little (the free cloth gives way to the body, not the body to the cloth).
void ClothSim::strainLimit(float scale) {
    const float most = (1.0f + kMaxStrain) * scale;
    for (int pass = 0; pass < 4; ++pass)
        for (const ClothPiece& p : rig_->pieces) {
            const uint32_t o = p.first;
            for (const ClothLink& l : p.links) {
                if (l.kind != 0) continue;
                auto weight = [&](uint16_t i) {
                    if (p.points[i].pinned) return 0.0f;
                    const V3& c = contact_[o + i];
                    return c.x != 0 || c.y != 0 || c.z != 0 ? 0.02f : 1.0f;
                };
                float wa = weight(l.a), wb = weight(l.b);
                if (wa + wb <= 0) continue;
                V3& a = x_[o + l.a];
                V3& b = x_[o + l.b];
                V3 d = b - a;
                float len = d.length(), cap = l.rest * most;
                if (len <= cap || len < 1e-7f) continue;
                float corr = (len - cap) / len / (wa + wb);
                a = a + d * (corr * wa);
                b = b - d * (corr * wb);
            }
        }
}

// Each row (but the pinned top) turned back about the hips' up axis, round its middle, to within kTwistCone of the hips'
// side: a stride may swing the flap and turn it a little, never edge-on to the front.
void ClothSim::twist(const std::vector<ClothCapsule>& bodies) {
    V3 A, hipSide;
    if (!hipFrame(bodies, A, hipSide) || A.z < 0.5f) return;   // (lying down the flap lies where it falls)
    for (const ClothPiece& p : rig_->pieces) {
        const int C = p.cols;
        const V3 side = hipSide * p.sideSign;
        for (int r = 1; r < p.rows; ++r) {
            const size_t row = p.first + size_t(r * C);
            V3 L = x_[row + size_t(C - 1)] - x_[row];
            L = L - A * L.dot(A);
            float len = L.length();
            if (len < 1e-6f) continue;
            float ang = std::atan2(side.cross(L).dot(A), side.dot(L));
            if (std::fabs(ang) <= kTwistCone) continue;
            float back = ang > 0 ? kTwistCone - ang : -kTwistCone - ang;
            V3 mid(0, 0, 0);
            for (int c = 0; c < C; ++c) mid = mid + x_[row + size_t(c)];
            mid = mid * (1.0f / float(C));
            for (int c = 0; c < C; ++c) {
                if (p.points[size_t(r * C + c)].pinned) continue;
                V3& x = x_[row + size_t(c)];
                x = mid + turned(x - mid, A, back);
            }
        }
    }
}

float ClothSim::minWidthRatio(float worldScale) const {
    V3 A, side;
    if (!rig_ || x_.size() != rig_->particles() || !hipFrame(lastBodies_, A, side)) return 1.0f;
    float worst = 1e9f;
    for (const ClothPiece& p : rig_->pieces) {
        const int C = p.cols;
        for (int r = 1; r < p.rows && size_t(r) < p.restWidth.size(); ++r) {
            if (p.restWidth[size_t(r)] <= 0) continue;
            const size_t row = p.first + size_t(r * C);
            V3 L = x_[row + size_t(C - 1)] - x_[row];
            L = L - A * L.dot(A);
            worst = std::min(worst, std::fabs(L.dot(side)) / (p.restWidth[size_t(r)] * worldScale));
        }
    }
    return worst > 1e8f ? 1.0f : worst;
}

void ClothSim::solve(const std::vector<ClothCapsule>& bodies, float scale, bool withBodies) {
    for (const ClothPiece& p : rig_->pieces) {
        const uint32_t o = p.first;
        for (const ClothLink& l : p.links) {
            V3& a = x_[o + l.a];
            V3& b = x_[o + l.b];
            float wa = p.points[l.a].pinned ? 0.0f : 1.0f, wb = p.points[l.b].pinned ? 0.0f : 1.0f;
            if (wa + wb <= 0) continue;
            V3 d = b - a;
            float len = d.length();
            if (len < 1e-7f) continue;
            float corr = (len - l.rest * scale) / len * l.stiffness / (wa + wb);
            a = a + d * (corr * wa);
            b = b - d * (corr * wb);
        }
        for (const ClothLink& t : p.tethers) {
            V3 d = x_[o + t.b] - x_[o + t.a];
            float len = d.length(), most = t.rest * scale;
            if (len > most && len > 1e-7f) x_[o + t.b] = x_[o + t.b] - d * ((len - most) / len);
        }
    }
    if (withBodies) collide(bodies, scale);
}

void ClothSim::collide(const std::vector<ClothCapsule>& bodies, float scale) {
    for (const ClothPiece& p : rig_->pieces) {
        const uint32_t o = p.first;
        const float ground = (p.side < 0 ? floorBack_ : floor_) + kStandoff * scale;
        for (size_t i = 0; i < p.points.size(); ++i) {
            if (p.points[i].pinned) continue;
            V3& x = x_[o + i];
            for (const ClothCapsule& c : bodies) {
                V3 nrm;
                if (clothCollide(c, p.side, kStandoff * scale, x, nrm)) contact_[o + i] = nrm;
            }
            if (x.z < ground) { x.z = ground; contact_[o + i] = V3(0, 0, 1); }
        }
    }
}

// Follow the leader: each point pulled to within its give of the point above it (from the pins down), then of its
// neighbour toward the row's middle (from the middle out). Only ever shortens.
void ClothSim::inextensible(float scale) {
    const float give = (1.0f + kSlack) * scale;
    auto hold = [&](V3& b, const V3& a, float rest) {
        if (rest <= 0) return;
        V3 d = b - a;
        float len = d.length(), most = rest * give;
        if (len > most && len > 1e-7f) b = a + d * (most / len);
    };
    for (const ClothPiece& p : rig_->pieces) {
        if (p.restUp.size() != p.points.size() || p.restRight.size() != p.points.size()) continue;
        const uint32_t o = p.first;
        const int C = p.cols, mid = C / 2;
        for (int r = 1; r < p.rows; ++r) {
            for (int c = 0; c < C; ++c) {
                size_t b = size_t(r * C + c);
                if (!p.points[b].pinned) hold(x_[o + b], x_[o + b - size_t(C)], p.restUp[b]);
            }
            for (int c = mid + 1; c < C; ++c) {
                size_t b = size_t(r * C + c);
                if (!p.points[b].pinned) hold(x_[o + b], x_[o + b - 1], p.restRight[b - 1]);
            }
            for (int c = mid - 1; c >= 0; --c) {
                size_t b = size_t(r * C + c);
                if (!p.points[b].pinned) hold(x_[o + b], x_[o + b + 1], p.restRight[b]);
            }
        }
    }
}

// Every free point held kSkinGap off the tangent plane of its nearest skin vertex (within kSkinReach): the capsules keep
// the cloth out of the body in the large, this keeps it off the skin's own shape where the capsules are rounder or thinner.
void ClothSim::skinPass(float scale) {
    if (!skin_ || cells_.empty()) return;
    const float gap = kSkinGap * scale, reach2 = cell_ * cell_;
    for (const ClothPiece& p : rig_->pieces)
        for (size_t i = 0; i < p.points.size(); ++i) {
            if (p.points[i].pinned) continue;
            V3& x = x_[p.first + i];
            // within a cell's size of the point: the 2 x 2 x 2 cells around the corner nearest it
            float fx = x.x / cell_, fy = x.y / cell_, fz = x.z / cell_;
            int cx = int(std::floor(fx - 0.5f)), cy = int(std::floor(fy - 0.5f)), cz = int(std::floor(fz - 0.5f));
            float best = reach2;
            int bi = -1;
            for (int dx = 0; dx <= 1; ++dx)
                for (int dy = 0; dy <= 1; ++dy)
                    for (int dz = 0; dz <= 1; ++dz) {
                        uint64_t key = cellKey(cx + dx, cy + dy, cz + dz);
                        auto it = std::lower_bound(cells_.begin(), cells_.end(), std::make_pair(key, uint32_t(0)));
                        for (; it != cells_.end() && it->first == key; ++it) {
                            V3 d = x - skin_->p[it->second];
                            float dd = d.dot(d);
                            if (dd < best) { best = dd; bi = int(it->second); }
                        }
                    }
            if (bi < 0) continue;
            const V3& n = skin_->n[size_t(bi)];
            float h = (x - skin_->p[size_t(bi)]).dot(n);
            if (h >= gap) continue;
            x = x + n * (gap - h);
            contact_[p.first + i] = n;
        }
}

float ClothSim::maxStretch(float worldScale) const {
    float worst = 0;
    if (!rig_) return 0;
    for (const ClothPiece& p : rig_->pieces)
        for (const ClothLink& l : p.links) {
            if (l.kind != 0 || l.rest <= 0) continue;
            float len = (x_[p.first + l.b] - x_[p.first + l.a]).length();
            worst = std::max(worst, len / (l.rest * worldScale) - 1.0f);
        }
    return worst;
}

bool clothPanelRect(const std::vector<int>& sections, int mw, int mh, int out[4]) {
    int x0 = 1 << 30, y0 = 1 << 30, x1 = -(1 << 30), y1 = -(1 << 30);
    for (size_t i = 0; i + 3 < sections.size(); i += 4) {
        if (sections[i + 2] <= 0 || sections[i + 3] <= 0) continue;
        x0 = std::min(x0, sections[i]); y0 = std::min(y0, sections[i + 1]);
        x1 = std::max(x1, sections[i] + sections[i + 2]); y1 = std::max(y1, sections[i + 1] + sections[i + 3]);
    }
    if (mw <= 0 || mh <= 0) return false;
    x0 = std::clamp(x0, 0, mw); x1 = std::clamp(x1, 0, mw);
    y0 = std::clamp(y0, 0, mh); y1 = std::clamp(y1, 0, mh);
    if (x1 - x0 < 8 || y1 - y0 < 8) return false;
    int top = y0 + int(std::lround(float(y1 - y0) * kClothPanelTop));
    out[0] = x0; out[1] = top; out[2] = x1 - x0; out[3] = y1 - top;
    return out[3] > 0;
}

void clothPanelUv(int side, float across, float down, int texW, int texH, float uv[2]) {
    float a = std::min(std::fabs(across), 1.0f) * kClothPanelHalf;
    float u = side < 0 ? 1.0f - a : a;
    float iu = texW > 3 ? 1.5f / float(texW) : 0.0f, iv = texH > 3 ? 1.5f / float(texH) : 0.0f;
    uv[0] = std::clamp(u, iu, 1.0f - iu);
    uv[1] = std::clamp(down, iv, 1.0f - iv);
}

void clothVertices(const ClothRig& rig, const std::vector<V3>& world, const M4& worldMatrix, const float* uvBox,
                   std::vector<M2Vertex>& out, std::vector<uint16_t>& indices, const int* panelPx) {
    out.clear();
    indices.clear();
    if (world.size() != rig.particles()) return;
    const M4 inv = affineInverse(worldMatrix);
    std::vector<V3> local;
    for (const ClothPiece& p : rig.pieces) {
        local.resize(p.points.size());
        for (size_t i = 0; i < p.points.size(); ++i) local[i] = inv.point(world[p.first + i]);
        const int R = p.rows, C = p.cols;
        auto at = [&](int r, int c) { return local[size_t(std::clamp(r, 0, R - 1) * C + std::clamp(c, 0, C - 1))]; };
        const size_t base = out.size();
        for (int face = 0; face < 2; ++face) {
            for (int r = 0; r < R; ++r)
                for (int c = 0; c < C; ++c) {
                    const ClothPoint& pt = p.points[size_t(r * C + c)];
                    V3 nrm = (at(r, c + 1) - at(r, c - 1)).cross(at(r + 1, c) - at(r - 1, c)).normalized() * p.normalSign;
                    if (face) nrm = nrm * -1.0f;
                    M2Vertex v{};
                    V3 x = at(r, c);
                    v.pos[0] = x.x; v.pos[1] = x.y; v.pos[2] = x.z;
                    v.normal[0] = nrm.x; v.normal[1] = nrm.y; v.normal[2] = nrm.z;
                    float u = pt.uv[0], w = pt.uv[1];
                    if (panelPx) {
                        float uv[2];
                        clothPanelUv(p.side, C > 1 ? 2.0f * float(c) / float(C - 1) - 1.0f : 0.0f, R > 1 ? float(r) / float(R - 1) : 0.0f,
                                     panelPx[0], panelPx[1], uv);
                        u = uv[0]; w = uv[1];
                    } else if (uvBox && uvBox[2] > 0 && uvBox[3] > 0) { u = (u - uvBox[0]) / uvBox[2]; w = (w - uvBox[1]) / uvBox[3]; }
                    v.uv[0] = v.uv2[0] = u;
                    v.uv[1] = v.uv2[1] = w;
                    out.push_back(v);
                }
        }
        const size_t n = p.points.size();
        // the front face as the grid is wound, the back face the other way round
        for (size_t k = 0; k + 2 < p.tris.size(); k += 3)
            indices.insert(indices.end(), {uint16_t(base + p.tris[k]), uint16_t(base + p.tris[k + 1]), uint16_t(base + p.tris[k + 2])});
        for (size_t k = 0; k + 2 < p.tris.size(); k += 3)
            indices.insert(indices.end(), {uint16_t(base + n + p.tris[k]), uint16_t(base + n + p.tris[k + 2]), uint16_t(base + n + p.tris[k + 1])});
    }
}

} // namespace wc
