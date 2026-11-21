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
// Where kPartRoot sits between where the legs part (0) and the hip joints' height (1): the pubic root on the body's front,
// fitted on the human man (desktop harness, engine-test/bodypart_test's races) and used for every race's men.
constexpr float kPartRootShare = 0.87f;
// The base and scrotum's half width in the Sims frame (the user's set: 3.5 cm), narrowed to the gap between the inner thighs.
constexpr float kPartBaseHalfWidth = 0.035f;
constexpr float kPartSqueezeMin = 0.45f;
// How far the root sits inside the body's front surface (Sims metres, scaled with the part): the base ring under the skin,
// so the body's own skin hides the seam and the shaft emerges from it.
constexpr float kPartSink = 0.023f;
// The erect shaft's least pitch above the pelvis' forward (radians, 30 degrees): forward and up, never level or down at rest.
constexpr float kErectPitchMin = 0.5236f;

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
    float squeeze = 1;        // the base and scrotum's width as drawn (kPartSqueezeMin..1): narrowed to the gap between the inner thighs
    float front = 0;          // the body's front surface on the midline at the root's height (the root sits kPartSink inside it)
};

// The lower body as shown (`geosets`: the actor's drawn submesh ids; empty: all): its vertices of the body geoset (id 0)
// and the lower body's group (13xx). bodyFrontX: their largest x within `halfWidth` of y 0 and `band` of `z` (the body's
// front on its midline), `fallback` when none is. bodyCrotchZ: the lowest z of those on the midline between `above` and
// `below` and not behind `minX` (where the legs part; a tauren's tail hangs behind), -1 when none is.
float bodyFrontX(const M2Model& m, const std::vector<uint16_t>& geosets, float z, float halfWidth, float band, float fallback);
float bodyCrotchZ(const M2Model& m, const std::vector<uint16_t>& geosets, float halfWidth, float above, float below, float minX);
// Which bones are a leg's (a thigh and everything under it: 1), and how much of a vertex's weight they carry (0..1).
constexpr float kCrotchLegShare = 0.9f;   // a midline vertex the legs carry more of than this is a knee or a thigh, not the crotch
std::vector<uint8_t> legBones(const M2Model& m);
float legWeight(const M2Vertex& v, const std::vector<uint8_t>& leg);

// The skin texture coordinates (u, v pairs; v 0 at the texture's top) of the shown lower body's vertices within `radius` of
// `centre` (model units, bind pose), at most `maxPoints` of them spread over all: where the body's skin meets a part, for the
// caller to sample its skin colour there (bodyparts/PartSkin.kt).
std::vector<float> bodySkinUvs(const M2Model& m, const std::vector<uint16_t>& geosets, V3 centre, float radius, size_t maxPoints);

// The placement on a model as shown (its Pelvis, else Root; ok false without either).
PartPlacement placeParts(const M2Model& m, const std::vector<uint16_t>& geosets, const PartFit& fit);

// The matrix taking a part's Sims-frame points (partSimsPoint) to model space as drawn: pelvis bone matrix (as animated)
// * translation(root) * scaling(scale) * translation(-kPartRoot).
M4 partMatrix(const M4& pelvisBone, const PartPlacement& pl);

// ------------------------------------------------------------------------------------------------ the shaft, arousal, insertion
//
// Arousal is a level 0 (flaccid) .. 1 (erect) the app sets per wearer (bodyparts/Arousal.kt). The two meshes of a set
// (DEFAULT and ON) don't share a vertex layout, so the level is drawn as a bend: each mesh's shaft (measureShaft: the
// vertices the two meshes don't share, weighted from 0 at the root to 1 a little along it) is turned about kPartRoot to
// the pitch and stretched to the length the eased level gives (levelBend: between the DEFAULT's own and the ON's own), the
// DEFAULT mesh drawn below the swap and the ON mesh above it (with hysteresis, kPartSwapUp / kPartSwapDown): both stand at
// the same pitch and length at the swap, so it doesn't jump. The scrotum and the base (shared by both meshes) never move.
//
// Flaccid, the shaft hangs with the thighs (thighPitch: their mean turn forward from the bind, in the pelvis' frame), so a
// seated or kneeling man's part lies over his thighs, not inside them.
//
// Insertion: during a partner stage with an entry (vaginal, anal, oral...), the erect shaft is aimed from its root at the
// partner's entry anchor (measureEntries: from the partner's own mesh, ridden on a bone so it follows the pose), within
// kAimLimitDeg of its rest direction, the turn eased at most kAimRateDeg a second. The depth is the length past the entry:
// insertionDepth(length, distance, rest) = clamp(length - distance + rest, 0, length): as the pelvis moves in the clip, the
// shaft slides in and out with no extra keys. The shaft is drawn straight through the partner's skin, depth tested: the part
// inside is hidden by her body. Units: Sims metres in the part's frame (the size options' own lengths).

constexpr float kPartSwapUp = 0.55f, kPartSwapDown = 0.45f;
constexpr float kAimLimitDeg = 50.0f;     // beyond this from the rest direction the aim lets go (the bodies don't line up)
constexpr float kAimReach = 1.3f;         // nor when the entry is farther than this many shaft lengths
constexpr float kAimSlack = 0.03f;        //   plus this (Sims metres): the shaft keeps its authored line (a hand on it)
constexpr float kAimRateDeg = 240.0f;     // the aim's turn per second at most
constexpr float kFlaccidThighShare = 0.85f;

enum PartEntry : int { kEntryNone = 0, kEntryVaginal = 1, kEntryAnal = 2, kEntryOral = 3, kEntryManual = 4, kEntryThigh = 5, kEntryChest = 6, kEntryCount = 7 };

// One part mesh's shaft in the Sims frame: its root (kPartRoot), unit axis root -> tip, length, and each vertex's weight in
// the bend (0 the base and scrotum, 1 the shaft past its first 2.5 cm).
struct PartShaft {
    bool ok = false;
    V3 root, axis;
    float length = 0;
    std::vector<float> weights;
    float pitch() const { return std::atan2(axis.z, axis.x); }   // radians, 0 forward, + up
};
// `other`: the set's mesh of the other state (its shared vertices are the base); null: the shaft is told by its place alone
// (in front of the root).
PartShaft measureShaft(const PartData& d, const PartData* other);

// A turn about the shaft's root and a stretch along it, weighted per vertex by the shaft's weights.
struct PartBend {
    Quat turn;
    float stretch = 1;
    float squeeze = 1;   // across (y), on the base and scrotum only (1 - weight): PartPlacement::squeeze
};
// The part's vertices posed by `b` (Sims frame, as uploadPart writes them: positions and normals; uvs kept).
void posePart(const PartData& d, const PartShaft& s, const PartBend& b, std::vector<M2Vertex>& out);

// The part's texture (`rgba`, w x h, top row first) blended toward the body's skin colour `rgb` (0xRRGGBB, sampled where the
// part meets the body) at its root: each texel by its triangles' vertices' distance from kPartRoot (Sims frame): all skin
// within kRootSkinFull, the part's own colour from kRootSkinEnd; and a soft contact shade (kRootShade darker at most) on the
// ring where the shaft leaves the skin (kPartSink from the root). So the root is the body's colour and lit as it is, and the
// part comes out of the skin rather than standing on it. Returns how many texels changed.
constexpr float kRootSkinFull = 0.026f, kRootSkinEnd = 0.05f, kRootShade = 0.12f;
size_t blendRootToSkin(const PartData& d, uint8_t* rgba, int w, int h, uint32_t rgb);
// The blend's weight toward the skin and the shade at a point (Sims frame).
float rootSkinWeight(const V3& p);
float rootShade(const V3& p);

// A triangle list (`indices`, 3 per triangle) without the triangles that touch a masked vertex (`mask`: vertex indices, any
// order): an HD body's own genitals left out where the parts are drawn instead.
std::vector<uint32_t> maskTriangles(const std::vector<uint32_t>& indices, const std::vector<uint32_t>& mask);

// Quaternion helpers (unit quaternions; the turn taking unit `from` onto unit `to`, the shortest; a turn about an axis).
Quat quatFromTo(const V3& from, const V3& to);
Quat quatAxisAngle(const V3& axis, float radians);
V3 quatRotate(const Quat& q, const V3& v);
Quat quatMul(const Quat& a, const Quat& b);
float quatAngle(const Quat& q);   // radians, 0..pi
// The affine inverse of a matrix (rotation, uniform or not, scale and translation).
M4 affineInverse(const M4& m);

// The eased level (smoothstep) and which mesh a level draws (hysteresis: `on` the last frame's choice).
float easeLevel(float level);
bool levelSlotOn(float level, bool wasOn);
// The bend that draws `level` with the mesh of `slotOn`: pitch and length between `flaccid`'s and `erect`'s at the eased
// level; plus `hang` radians of extra pitch (the thighs' turn, flaccid only: weighed by 1 - eased level).
PartBend levelBend(float level, bool slotOn, const PartShaft& flaccid, const PartShaft& erect, float hang);
// The shaft's axis in the Sims frame as the level draws it (before an aim).
V3 levelAxis(float level, const PartShaft& flaccid, const PartShaft& erect, float hang);

// How far the shaft is in: the length past the entry plus the stage's rest depth, 0..length.
float insertionDepth(float length, float distance, float rest);
// The aim: from the current turn `current` toward `target` (both turns of the shaft's own axis), at most `maxRadians`.
Quat stepAim(const Quat& current, const Quat& target, float maxRadians);

// The thighs' mean turn forward from the bind in the pelvis' frame (radians; + forward/up), from the posed matrices:
// sitting about +pi/2, standing 0. 0 without the bones.
float thighPitch(const M2Model& m, const std::vector<M4>& mats, int pelvis);

// A partner's entry anchors, measured once on its model as shown (bind pose): each a point on a bone (posed: the bone's
// matrix times `point`), the way in (unit, bind) and the opening's radius (model units). Vaginal (women: the vulva on the
// midline, between the crotch and the front, the way in up and back), anal (between the glutes at the crotch's back, the
// way in up and forward), oral (the Breath attachment at the mouth, else in front of the head below its pivot; the way in
// backward into the head), thigh (between the thighs below the crotch, forward), chest (between the breasts on the front
// surface, down). Manual has none (hands).
struct EntryAnchor {
    bool ok = false;
    int bone = -1;
    V3 point, inward;
    float radius = 0;
};
struct EntryAnchors {
    bool measured = false;
    EntryAnchor at[kEntryCount];
    float crotch = 0, front = 0, back = 0;   // the measurements (model units), for the tests
};
EntryAnchors measureEntries(const M2Model& m, const std::vector<uint16_t>& geosets, bool female);
// The body's back on its midline at height z (the smallest x of its lower body within `halfWidth` of y 0 and `band` of z).
float bodyBackX(const M2Model& m, const std::vector<uint16_t>& geosets, float z, float halfWidth, float band, float fallback);

} // namespace wc
