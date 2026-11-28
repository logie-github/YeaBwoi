// Tessellation of the soft regions (README "Tessellation"): the butt, breasts, thighs and belly of a body mesh split at load so that no edge there
// is longer than `maxEdge`, for a smoother silhouette under the spring bones (springbone.h) and the tissue field. Standalone (no GL, no files):
// engine-test/tessellate_test.cpp.
//   - Edges are split at their midpoints, a shared midpoint per edge, and every triangle re-cut by how many of its edges were split (1: two
//     triangles, 2: three, 3: four), so the mesh stays watertight (no T-junction cracks). Passes repeat until the soft edges are short enough or
//     `cap` new vertices have been added (the longest edges first).
//   - Each new vertex interpolates position, normal, both UV sets, the four bone indices and weights (the two ends' influences merged, the four
//     strongest kept, normalised to 255) and the soft mask. The position is the cubic edge curve of the two ends' normals (as PN triangles do:
//     mid = (a + b) / 2 - ((b - a).na * na + (a - b).nb * nb) / 8, scaled by `curve`, at most a quarter of the edge long), so a rounded region
//     (a breast, a cheek) gets rounder instead of faceted; the normals used for it are welded across UV seams (vertices at one place), so a seam
//     does not crack.
//   - The index ranges (the model's submeshes, the HD figure's parts) are kept in order and updated.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "m2.h"

namespace wc {

struct TessRange { uint32_t start = 0, count = 0; };   // into the index array (a submesh's or a part's triangles)

struct TessStats {
    size_t verticesBefore = 0, verticesAfter = 0, trianglesBefore = 0, trianglesAfter = 0;
    size_t softEdgesBefore = 0, softEdgesAfter = 0;   // edges that qualify as in a soft region (mask) and their count over maxEdge afterward
    float edgeP50Before = 0, edgeP99Before = 0, edgeP50After = 0, edgeP99After = 0;   // lengths of the qualifying edges (mesh units)
    int passes = 0;
    bool capped = false;
    double ms = 0;
};

// `soft`: one value per vertex, 0..1 (the share in a soft region); an edge qualifies where the mean of its ends is at least `threshold`.
// New vertices are appended to `verts` and `soft`; `indices` is rebuilt and `ranges` updated. Returns the vertices added.
size_t tessellateSoftRegions(std::vector<M2Vertex>& verts, std::vector<uint32_t>& indices, std::vector<TessRange>& ranges,
                             std::vector<float>& soft, float maxEdge, size_t cap, TessStats* stats = nullptr, float curve = 1.0f,
                             float threshold = 0.3f);

} // namespace wc
