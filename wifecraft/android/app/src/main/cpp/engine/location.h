// A location (assets/locations.json's entry: the Stormwind Embassy first): a WMO placed in the world with the groups and
// doodad sets it shows, the entry's own extra placements, and its light and fog, outside and per view. Read from the
// entry's JSON as the app keeps it (location.cpp: parseLocation); Scene::loadLocation draws it.
//
// The entry (WoW's own axes: x and y across the ground, z up, yards; rotations as quaternions x, y, z, w):
//   wmo:      {root: file id, groupIndices: [index in the root's group list] (or groups: [index or group file id]),
//              doodadSets: [index or name], position: [x,y,z], rotation: [x,y,z,w], scale}   the default set (0) always shows
//   doodads:  [{model: file id, pos: [x,y,z], rot: [x,y,z,w], scale, set}] placements in the location's coordinates; those
//              with set >= 0 are the WMO's own (drawn from its doodad sets) and skipped, the rest (set -1: the map's) drawn
//   frame:    {origin: [x,y,z], yawDeg}                                   the location's coordinates in the map's:
//                                                                         world = origin + Rz(yawDeg) * local
//   terrain:  {root: ADT file id, tex0: its tex0 file id, chunks: [[ix,iy]...]}  the map's ground (MCNK heights, MCLY
//              layers with their MCAL alpha, MCCV shading), moved into the location's coordinates by `frame`
//   spawns:   {name: {pos: [x,y,z], facing}}
//   light:    [23 numbers as Lights.of] | {sunDir, sunColor|direct, ambient, interiorAmbient, skyTop, skyHorizon, skyBelow}
//             ({ambient, vertexColours: true}: a room's own light, its ambient the interior's; the map's light stays)
//   fog:      {colour: [r,g,b], start, end}                              the area's edge: nothing is drawn past `end`
//   ground:   {z, colour} | false                                        the plain ground under an exterior (the terrain
//                                                                        isn't loaded): at z, fading into the fog
//   views:    {name: {eye, at, fov (or camera: {eye, at|target, fov}), spawn, light, fog, ground}}  a view's own light/fog
// Colours are 0..1 (or 0..255 when any is over 1.5).
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "json.h"
#include "wmath.h"

namespace wc {

struct Light;

struct LocationPlacement {
    uint32_t model = 0;
    V3 position;
    Quat rotation;
    float scale = 1;
};

struct LocationLook {
    bool hasLight = false, hasFog = false, hasGround = false;
    float light[23] = {};           // Lights.of's order (sky top, horizon, below, sun dir, sun colour, ambient, fog colour, start, end)
    bool hasInterior = false;
    float interiorAmbient[3] = {};
    float fogColor[3] = {0, 0, 0};
    float fogStart = 0, fogEnd = 0;
    bool ground = true;             // false: no plain ground
    bool groundZSet = false;
    float groundZ = 0;
    bool groundColorSet = false;
    float groundColor[3] = {0, 0, 0};
};

struct LocationView {
    bool hasCamera = false;
    V3 eye, target;
    float fov = 50;
    bool hasSpawn = false;
    V3 spawn;
    float facing = 0;
    LocationLook look;
};

struct LocationDesc {
    std::string id, name;
    uint32_t root = 0;
    std::vector<int> groups;               // empty: every group the root lists (but antiportals and the unreachable)
    std::vector<uint32_t> groupFiles;      // groups named by their file id (mapped once the root is read)
    std::vector<int> sets;                 // doodad sets (0 always)
    std::vector<std::string> setNames;
    V3 position;
    Quat rotation;
    float scale = 1;
    std::vector<LocationPlacement> doodads;
    // the map's ground under it, and where the location's coordinates are in the map's
    uint32_t terrainRoot = 0, terrainTex0 = 0;
    std::vector<std::pair<int, int>> terrainChunks;   // [ix, iy] of the tile (empty: all within reach of the views)
    V3 frameOrigin;
    float frameYawDeg = 0;
    LocationLook look;
    std::map<std::string, LocationView> views;
};

// Reads an entry (the object itself, or {"locations": [...]} / {"id": entry...} with `id` choosing it). False and
// `error` when it can't.
bool parseLocation(const std::string& json, const std::string& id, LocationDesc& out, std::string& error);

// The light a look makes over `base` (the scene's light): the sky's horizon and below take the fog's colour so the
// area's edge fades into it.
void applyLook(const LocationLook& look, Light& base);

} // namespace wc
