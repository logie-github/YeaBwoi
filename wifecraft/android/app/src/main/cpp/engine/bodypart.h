// Body parts: meshes attached to an actor's Pelvis bone (bodyparts/PartPackage.kt converts them at import time and writes
// them as part files, ".wcbp", documented in bodyparts/PartMesh.kt's PartFile). Here: the file read back (no GL), and the
// matrix that puts a part on a body. Scene draws them (scene.cpp: loadPart, attachPart, setPartState).
//
// How a part sits on a body. A part file's positions are Warcraft axes (x forward, y left, z up), Sims metres from the
// part's anchor; the anchor itself is in Sims model space (y up, z forward, feet at 0). Every part of a set is cut from
// the same Sims body, so anchor + position puts all of them, whatever their state, in one frame: the Sims body's, feet at
// 0 (partSimsPoint). One point of that frame, kPartRoot (where the part meets the body: the shaft's root at the front
// surface), is placed on the Warcraft body:
//   - its height: kPartRootAboveCrotch (scaled) above where the body's legs part on its midline (bodyCrotchZ: the shown
//     body and lower-body geosets' vertices, bind pose), plus `dz` (times the pelvis pivot height: PartFit),
//   - its depth: the body's own front surface at that height on the midline (bodyFrontX), plus `dx` (pelvis heights),
//   - its size: scale = the pelvis pivot height / kPartReferenceHeight (Sims metres to model units, the Sims anchor
//     height standing for the Sims pelvis) times `scale`.
// The part is then rigid on the Pelvis bone's animated matrix: partMatrix = pelvis bone matrix * that placement, so it
// follows every animation (Warcraft's and imported); the actor's own world matrix (its scale, human scale included) is
// applied over it as over the body. Parts are rigid: the converted weights put over 99% on Pelvis (the rest on the
// thighs, at the scrotum's sides), so skinning them would cost a bone texture per part for no visible difference.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

constexpr uint32_t kPartMagic = 0x50424357;   // "WCBP"
constexpr uint32_t kPartVersion = 1;
// Version 2 (modelpart.h: a piece cut from a game model, drawn rigid on a named bone of another body): version 1's layout
// with, after the bone names, the piece's material (its texture file, blend, flags) and its source's reference bones; its
// flags carry kPartModelFrame. Version 1 files read as before.
constexpr uint32_t kPartVersion2 = 2;
constexpr uint32_t kPartModelFrame = 8;   // anchor and positions in the source model's own space (Warcraft axes, its units)

// The Sims frame's point placed on the Warcraft body (metres, Warcraft axes: x forward, y left, z up from the feet).
constexpr float kPartRootX = 0.100f, kPartRootZ = 0.955f;
// Sims metres the Warcraft pelvis pivot height stands for (the parts' anchors sit at 0.918 to 0.932).
constexpr float kPartReferenceHeight = 0.930f;
// How far kPartRoot sits above where the legs part, in Sims metres (fitted on the human man, scaled with the part).
constexpr float kPartRootAboveCrotch = 0.164f;

struct PartData {
    uint32_t flags = 0;                 // 1 normals, 2 UVs, 4 skin
    std::string attachBone, id, state, variant;
    std::vector<std::string> bones;
    V3 anchorSims;                      // Sims model space (y up)
    float defaultScale = 1;
    V3 attachOffset;
    uint32_t textureWidth = 0, textureHeight = 0;
    std::vector<float> positions;       // 3 per vertex, Warcraft axes, metres from the anchor
    std::vector<float> normals;         // 3 per vertex (empty: none)
    std::vector<float> uvs;             // 2 per vertex, v 0 at the texture's top row (empty: none)
    std::vector<uint8_t> boneIndices, boneWeights;   // 4 per vertex (empty: none)
    std::vector<uint32_t> indices;
    // version 2 (model parts): the texture drawn (a game file id; 0: none), its M2 blend and material flags, and the source
    // model's bones the fit measures against (WoW-side names and their bind pivots, source model units)
    uint32_t version = kPartVersion;
    uint32_t textureFileId = 0;
    uint16_t blend = 0, materialFlags = 0;
    std::vector<std::pair<std::string, V3>> refs;
    bool modelFrame() const { return (flags & kPartModelFrame) != 0; }
    const V3* ref(const std::string& bone) const {
        for (const auto& r : refs) if (r.first == bone) return &r.second;
        return nullptr;
    }
    size_t vertexCount() const { return positions.size() / 3; }
    // "ON" is the second slot; anything else ("DEFAULT") the first.
    int slot() const { return state == "ON" ? 1 : 0; }
};

// Reads a part file (version 1 or 2); false and `error` when it is not one this build reads, or its counts or indices are bad.
bool parsePart(const uint8_t* data, size_t size, PartData& out, std::string& error);
// Writes a part file: version 2 when the part is in a model's frame (kPartModelFrame), else version 1 (as PartFile does).
std::vector<uint8_t> writePart(const PartData& p);

// Vertex `v` of a model-frame part in its source model's space: anchor + position, axes as they are.
inline V3 partModelPoint(const PartData& p, size_t v) {
    return V3(p.anchorSims.x + p.positions[v * 3], p.anchorSims.y + p.positions[v * 3 + 1], p.anchorSims.z + p.positions[v * 3 + 2]);
}

// Vertex `v` in the Sims body's frame (Warcraft axes, metres from the Sims feet): anchor (axes changed) + position.
inline V3 partSimsPoint(const PartData& p, size_t v) {
    return V3(p.anchorSims.z + p.positions[v * 3], p.anchorSims.x + p.positions[v * 3 + 1], p.anchorSims.y + p.positions[v * 3 + 2]);
}

// How a race's body takes the parts (bodyparts/PartFit.kt per race): offsets in pelvis heights, a factor on the size.
struct PartFit {
    float dx = 0, dz = 0, scale = 1;
};

// Where the parts go on one model, measured once (its bind pose): the pelvis pivot, and the front surface of the body.
struct PartPlacement {
    bool ok = false;
    int pelvis = -1;          // the bone the parts ride
    V3 pivot;                 // its bind pivot (model units)
    V3 root;                  // where kPartRoot lands (model units, bind pose)
    float crotch = 0;         // where the legs part on the midline (model units; bodyCrotchZ)
    float scale = 1;          // model units per Sims metre
};

// The lower body as shown (`geosets`: the actor's drawn submesh ids; empty: all): its vertices of the body geoset (id 0)
// and the lower body's group (13xx). bodyFrontX: their largest x within `halfWidth` of y 0 and `band` of `z` (the body's
// front on its midline), `fallback` when none is. bodyCrotchZ: the lowest z of those on the midline between `above` and
// `below` and not behind `minX` (where the legs part; a tauren's tail hangs behind), -1 when none is.
float bodyFrontX(const M2Model& m, const std::vector<uint16_t>& geosets, float z, float halfWidth, float band, float fallback);
float bodyCrotchZ(const M2Model& m, const std::vector<uint16_t>& geosets, float halfWidth, float above, float below, float minX);

// The skin texture coordinates (u, v pairs; v 0 at the texture's top) of the shown lower body's vertices within `radius` of
// `centre` (model units, bind pose), at most `maxPoints` of them spread over all: where the body's skin meets a part, for the
// caller to sample its skin colour there (bodyparts/PartSkin.kt).
std::vector<float> bodySkinUvs(const M2Model& m, const std::vector<uint16_t>& geosets, V3 centre, float radius, size_t maxPoints);

// The placement on a model as shown (its Pelvis, else Root; ok false without either).
PartPlacement placeParts(const M2Model& m, const std::vector<uint16_t>& geosets, const PartFit& fit);

// The matrix taking a part's Sims-frame points (partSimsPoint) to model space as drawn: pelvis bone matrix (as animated)
// * translation(root) * scaling(scale) * translation(-kPartRoot).
M4 partMatrix(const M4& pelvisBone, const PartPlacement& pl);

} // namespace wc
