// Which part of a character's body a screen point lands on (the creator's tap-to-edit): the ray tried against the body's
// shown triangles as posed this frame (each vertex skinned by its bone matrices), the nearest triangle hit, and the part
// its corners are weighted to most (their bones' regions, summed by weight); a hair geoset's triangle is the hair.
// Codes as the app's (creator/CasTabs.kt BodyRegion).
#pragma once

#include <cstdint>
#include <vector>

#include "m2.h"
#include "wmath.h"

namespace wc {

enum BodyRegion : uint8_t {
    kRegionNone = 0, kRegionHead = 1, kRegionHair = 2, kRegionNeck = 3, kRegionChest = 4, kRegionWaist = 5, kRegionHips = 6,
    kRegionLegs = 7, kRegionArms = 8, kRegionHands = 9, kRegionFeet = 10, kRegionCount = 11
};

// Each bone's region from its parents and the bones named for a region (`seeds`: bone index, region): a bone with none
// takes its nearest seeded ancestor's (kRegionNone where there is none).
std::vector<uint8_t> boneRegionsFrom(const std::vector<int16_t>& parents, const std::vector<std::pair<int, uint8_t>>& seeds);
std::vector<uint8_t> boneRegionsOf(const M2Model& m);   // bodyrig.cpp: the WoW-side names (findWowBone)

// The region three corners are weighted to most (their four weights each, summed per region); kRegionHair for a hair geoset.
uint8_t triangleRegion(const M2Vertex& a, const M2Vertex& b, const M2Vertex& c, const std::vector<uint8_t>& regions, bool hair);

// Whether geoset `id` is a hair style's (group 0, ids 1 to 99: the hair; 0 is the body).
inline bool hairGeoset(uint16_t id) { return id >= 1 && id <= 99; }

struct BodyPickHit {
    uint8_t region = kRegionNone;
    float t = 0;      // along the ray (model space)
    V3 point;         // model space, posed
};

// The ray (o, d: model space) against the shown submeshes of `verts`/`indices` (`geosets` shown; empty: all), each vertex
// skinned by `mats` (empty: the bind pose). The nearest hit's region, or none.
BodyPickHit pickBody(const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices, const std::vector<M2Submesh>& subs,
                     const std::vector<uint16_t>& geosets, const std::vector<M4>& mats, const std::vector<uint8_t>& regions,
                     const V3& o, const V3& d);

} // namespace wc
