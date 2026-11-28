// Tessellation of the soft regions (breasts, belly, buttocks, thighs, upper arms) of a body mesh, standalone (no GL): the skin patch
// (skinpatch.h) needs triangles about 4 mm across to show a 1 cm fold, and a body's own are 1 to 3 cm there (the HD human woman:
// about 10 mm on the belly and breasts, 20 to 28 mm on the thighs). Edges longer than `maxEdge` whose two ends are in the mask are
// split at their midpoints, repeatedly; a triangle with two or one split edges is cut in three or two (no T-junctions, no cracks,
// whichever triangles the neighbours are). The new vertex takes the mean of its ends' position, normal and texture coordinates and
// the union of their bone weights (the four heaviest kept), and is in the mask itself.
#pragma once

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "m2.h"

namespace wc {

struct SoftTess {
    std::vector<uint8_t> mask;                      // per vertex: may be split (grown with the vertices)
    std::unordered_map<uint64_t, uint32_t> mid;     // edge (low, high) -> its midpoint vertex
    size_t added = 0;                               // vertices added so far
    size_t vertexCap = 0;                           // never grow the vertex list past this many (0: no cap)
    int passes = 5;
};

// Splits the triangles of `tris` (a list of vertex indices, three per triangle) and appends to `out`. May add vertices to `verts`.
void tessellateTriangles(std::vector<M2Vertex>& verts, const uint32_t* tris, size_t count, float maxEdge, SoftTess& st,
                         std::vector<uint32_t>& out);

// The mask of the vertices on a model's soft regions: the bones that carry the chest, belly, pelvis, thighs and upper arms and
// the breasts, by the bones' own weights (at least `minShare` of a vertex's weight on them).
std::vector<uint8_t> softRegionMask(const M2Model& m, const std::vector<M2Vertex>& verts, size_t count, float minShare = 0.5f);

} // namespace wc
