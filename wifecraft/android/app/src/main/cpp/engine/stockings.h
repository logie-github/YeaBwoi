// Procedural stockings: a skin-tight shell made from the actual leg triangles of whatever body is drawn (the game's own, the shaped
// or sculpted one, Eve's, an HD body's: any M2Vertex mesh skinned by the model's bones). Each leg vertex is moved along its normal by
// `offset` and keeps its bone indices and weights, so the shell is skinned by the very matrices the body is (one draw, no extra bones).
// The triangles are clipped at the style's top (a clean edge, not a ragged ring), the lace/elastic band at the top is its own range
// (drawn with its own texture), and each vertex carries UVs for a fine weave. Pure maths: no GL (engine-test/stockings_test.cpp).
#pragma once

#include <cstdint>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

enum StockStyle : int { kStockAnkle = 0, kStockKnee = 1, kStockThigh = 2, kStockTights = 3, kStockGarter = 4, kStockStyles = 5 };   // garter: thigh-highs held by thin elastic straps from a waist belt

struct StockSpec {
    int style = kStockThigh;
    int denier = 20;              // 8 (sheer) to 80 (opaque)
    float r = 0.05f, g = 0.05f, b = 0.06f;   // the yarn's colour
    bool seam = false;            // a dark seam up the back of each leg
    bool lace = true;             // the top band is lace (else a plain elastic band)
    float offset = 0.003f;        // model units (yards) out along the normal
    bool operator==(const StockSpec& o) const {
        return style == o.style && denier == o.denier && r == o.r && g == o.g && b == o.b && seam == o.seam && lace == o.lace && offset == o.offset;
    }
};

// Where along the body the style reaches (model z, the body standing in its bind pose): from `bottom` (the sole) up to `top`.
// The top is a plane tilted front to back: a point is under it while z - slope * x <= top (x forward), so the cut runs smoothly along the hip's contour
// (lower at the front, higher at the back) and is made by clipping the triangles on it, not along their edges.
struct StockExtent { float bottom = 0, top = 0, bandHeight = 0, slope = 0; };

// The extent of `style` from the legs' own measures: the floor, the ankle, the knee and the hip joint (the pelvis bone's pivot).
StockExtent stockExtent(int style, float floorZ, float ankleZ, float kneeZ, float pelvisZ);

struct StockShell {
    std::vector<M2Vertex> verts;
    std::vector<uint32_t> indices;       // the weave's triangles first, then the band's
    uint32_t bandStart = 0;              // the first band index
    std::vector<uint32_t> source;        // the body vertex each shell vertex came from (the nearest of a clipped one's corners)
    StockExtent extent;
    float minGap = 0, maxGap = 0;        // the offset actually made (a check)
};

// The shell for `spec` on the body `verts`/`indices` (`subs`/`geosets`: the shown submeshes, empty: all). `regions` are the bones' body
// regions (bodypick.h), which pick the legs' (and for tights the hips') triangles. The measures: see stockExtent.
bool buildStockings(const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices, const std::vector<M2Submesh>& subs,
                    const std::vector<uint16_t>& geosets, const std::vector<uint8_t>& regions, const StockSpec& spec,
                    float floorZ, float ankleZ, float kneeZ, float pelvisZ, StockShell& out);

// The shell's vertices are per clipped triangle (their UVs differ across the back's wrap), so the fabric works on the welded mesh: `weld[i]` is
// shell vertex i's unique vertex (equal positions are one), `unique` their positions and `tris` the shell's triangles on them.
void stockWeld(const StockShell& s, std::vector<uint32_t>& weld, std::vector<V3>& unique, std::vector<uint32_t>& tris);

// The weave texture (size x size RGBA, tiled) and the band's (lace or elastic): alpha from the denier (8 D very sheer .. 80 D opaque), the
// yarn's colour, threads visible, a darker seam column when asked (the weave's `u` runs once round the leg: see the shell's UVs).
std::vector<uint8_t> stockingWeave(const StockSpec& spec, int size);
// (`elastic`: a plain ribbed opaque waistband, whatever spec.lace says: the pantyhose's)
std::vector<uint8_t> stockingBand(const StockSpec& spec, int size, bool elastic = false);

// The alpha 0..1 a denier gives at its sheerest part of the weave (monotonic in the denier), and the colour presets.
float denierAlpha(int denier);
struct StockColour { const char* name; float r, g, b; };
const std::vector<StockColour>& stockColours();

} // namespace wc
