#pragma once
// Wrath++'s body shapes (creator/BodyShapes.kt) drawn on today's model, smooth: no GL here, so the desktop tests run it.

#include <cstddef>
#include <cstdint>
#include <vector>

#include "m2.h"

namespace wc {

// How many times the skin is split where a shape moves it (each halves its edges: today's chest, 2 to 3 cm, to 1 to 1.5).
#ifndef WC_BODY_SPLIT_LEVELS
#define WC_BODY_SPLIT_LEVELS 1
#endif
constexpr int kBodySplitLevels = WC_BODY_SPLIT_LEVELS;

// An actor's own copy of a character model with a body shape on it: the model's vertices (moved where the shape moves
// them) and, after them, the ones the split added; the whole index list again, each section's triangles where they were
// (split ones in place of theirs); the sections with their new ranges (same order, ids and sort centres).
struct ShapedBody {
    std::vector<M2Vertex> vertices;
    std::vector<uint32_t> indices;
    std::vector<M2Submesh> submeshes;
    size_t moved = 0;     // vertices the shape moved (the model's own and the added)
    size_t added = 0;     // vertices the split added
    size_t split = 0;     // triangles split (each level's)
    size_t exact = 0;     // vertices at a looks vertex, moved exactly as it is
    size_t uvs = 0;       // vertices whose texture coordinates the shape changed
    std::vector<V3> addedRest;   // where the added vertices were before the shape (the desktop check measures them)
};

// The shape's field: where the records (`count` of kBodyMorphStride floats: m2.h) say the body's surface goes, read at any
// place on it. At a record's own place (within 0.01 mm) exactly that record; elsewhere a moving least squares fit (linear,
// Gaussian weights as wide as the records are dense there) of the records facing the same way. Smooth wherever the records
// are, so the model's vertices between the looks' (and the split's new ones) follow the shape's surface, not the nearest.
class BodyField {
public:
    BodyField(const float* morph, size_t count);
    // How far the shape moves the surface at `p` (facing `n`), how it turns its normal, and (a body vertex's texture
    // coordinates `uv`) how it moves them. False: no record moves anything near here (d, dn, duv are zero).
    bool at(const V3& p, const V3& n, const float uv[2], V3& d, V3& dn, float duv[2], bool* exact = nullptr) const;
    // Whether any record that moves lies within reach of `p` (cheap: the moving records' cells).
    bool near(const V3& p) const;
    size_t count() const { return count_; }

private:
    const float* m_;
    size_t count_;
    std::vector<uint32_t> cellStart_, cellItems_;   // records by cell (a hashed grid of kCell)
    std::vector<uint8_t> movingCell_;
    size_t buckets_ = 1;
    size_t bucket(int64_t x, int64_t y, int64_t z) const;
};

// The shape on model `m`: every vertex the field moves is moved by it; the triangles of the sections `splittable` marks
// (by submesh: the skin's, texture type 1) where the shape moves them are split `levels` times first (red-green, edges
// matched by place so nothing opens; new vertices at Phong tessellation's edge midpoint, their normal, texture coordinates
// and bone weights the two corners' mixed), so the shape's curve has vertices enough to show; then the normals of what
// moved are turned by how much the shape turned the surface (face normals averaged over every section meeting at a place).
// The model's own vertices keep their numbers, the bones and the texture coordinates go with the vertex: the shape follows
// the skeleton and the textures as the model does. False (out empty) when the shape moves nothing.
bool shapeBody(const M2Model& m, const std::vector<bool>& splittable, const float* morph, size_t count, int levels,
               ShapedBody& out);

} // namespace wc
