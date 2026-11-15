// WMO, the game's building format: a root file (materials, groups, props) and one file per group.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "bytes.h"
#include "m2.h"
#include "wmath.h"

namespace wc {

struct WmoMaterial {
    uint32_t flags = 0;      // 1 unlit, 4 two-sided, 0x10 unfogged ...
    uint32_t shader = 0;
    uint32_t blend = 0;      // as M2Material::blend
    uint32_t texture1 = 0, texture2 = 0, texture3 = 0;
};

struct WmoGroupInfo {
    uint32_t flags = 0;      // 0x8 outdoors, 0x2000 indoors
    V3 boundsMin, boundsMax;
    std::string name;
    uint32_t fileId = 0;
};

struct WmoDoodad {
    uint32_t modelFileId = 0;
    V3 position;
    Quat rotation;
    float scale = 1;
    uint32_t color = 0xFFFFFFFF;   // BGRA
};

struct WmoBatch {
    uint32_t indexStart = 0, indexCount = 0;
    uint16_t material = 0;
    uint16_t lastVertex = 0;   // the highest vertex its triangles use
};

struct WmoGroup {
    uint32_t flags = 0;
    // MOGP's batch counts: the first transBatches are the "trans" ones (lit between inside and outside by the vertex
    // colours' alpha), then the interior's, then the exterior's.
    uint16_t transBatches = 0, intBatches = 0, extBatches = 0;
    std::vector<float> positions;   // xyz
    std::vector<float> normals;
    std::vector<float> uvs;         // first uv set
    std::vector<uint8_t> colors;    // BGRA per vertex, or empty
    std::vector<uint16_t> indices;
    std::vector<WmoBatch> batches;
    bool load(const Bytes& file);
};

struct WmoRoot {
    std::vector<WmoMaterial> materials;
    std::vector<WmoGroupInfo> groups;
    std::vector<WmoDoodad> doodads;
    struct Set { std::string name; uint32_t first = 0, count = 0; };
    std::vector<Set> sets;
    uint32_t ambient = 0;
    uint16_t flags = 0;      // MOHD: 0x8 the vertex colours' alpha is used as stored (else the game fixes it up)
    V3 boundsMin, boundsMax; // MOHD's box, in the WMO's own coordinates
    bool load(const Bytes& file);
};

} // namespace wc
