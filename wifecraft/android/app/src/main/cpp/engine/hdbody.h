// HD Models (README "HD Models"): the user's own high-detail character, converted on the desktop (tools/hd/whd_convert.py)
// into a .whd set and imported on the phone (bodyparts/HdModels.kt), drawn INSTEAD of the game's model of that race and sex:
// its body, head, ears, eyes, brows, hair, outfits and jewellery. The actor keeps its game model underneath (its skeleton,
// animations, attachments, scale, the soft body and flesh measures): the HD meshes are skinned to that skeleton, the game's
// own mesh is never drawn.
//
// The fit (fitHd), per model and options:
//   - the set's weights already name the game's bones (the converter mapped the HD rig's authored weights onto them by the
//     recipe's table): each is looked up on the model (M2Model::findWowBone); a name the model lacks falls back to the
//     nearest vertex of the model's own skin (hdTransferWeights: the game body's weights where the HD vertex lies);
//   - the HD figure is scaled so its neck joint stands as high as the model's (as the Eve body is: the same height to the
//     shoulders), its pelvis over the model's;
//   - its limbs are turned to point as the model's rest pose does (upper arm, forearm, hand by its middle and index-to-pinky
//     line, fingers, thigh, shin, foot), skinned by its own weights about its own joints (linear blend), so the game's
//     animations, made for that rest pose, turn them the same way;
//   - every bone's pivot is put at the HD figure's joint (rigPivots: bodyrig.h bodyEditFrom runs the model's animations about
//     them, as for the Eve rig); bones without one move with their nearest placed ancestor.
// Drawn (hdscene.cpp) with the m2 program on the actor's bones, like the Eve body: the body (less the triangles the chosen
// outfit covers), the eyes and brows, the chosen hair, outfit and jewellery, each part with its texture: the skin tinted by
// the chosen tint, the face and body marks laid over it, the hair recoloured by its tint.
//
// The .whd layout (README "HD Models": the set): "WHD1", u32 version, u32 JSON length, u32 blob count; the JSON; padding to 16; the
// blob table (u64 offset, u64 size per blob); the blobs. Vertices are 40 bytes: position f32 x3 (metres, Warcraft axes,
// feet at z 0), normal f32 x3, uv f32 x2 (top-left origin, repeating), four bone indices into the JSON's "bones" and four
// weights u8 (summing to 255); indices u32; textures PNG.
#pragma once

#include <array>
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "m2.h"
#include "softbody.h"
#include "wmath.h"

namespace wc {

constexpr uint32_t kWhdVersion = 1;
constexpr size_t kWhdVertexBytes = 40;

struct HdPart { uint32_t start = 0, count = 0; int texture = -1; bool alphaTest = false; };

struct HdMesh {
    std::string id, kind;                 // kind: body, eyes, brows, hair, outfit, jewel
    std::vector<float> pos, nrm, uv;      // 3, 3, 2 per vertex
    std::vector<uint8_t> bi, bw;          // 4, 4 per vertex (bi into HdSet::bones)
    std::vector<uint32_t> idx;
    std::vector<HdPart> parts;
    size_t vertexCount() const { return pos.size() / 3; }
};

struct HdTexInfo { std::string id; int w = 0, h = 0; bool alpha = false; int blob = -1; };
struct HdChoice {
    std::string id, label;
    std::vector<int> meshes;              // into HdSet::meshes
    std::vector<int> classes;             // an outfit's classes (ChrClasses ids, 0 Civilian)
    std::vector<uint32_t> hidden;         // an outfit's: body triangles it covers (not drawn under it)
};
struct HdEyeTex { std::string id, label; int texture = -1; };
struct HdOverlay { std::string id, label, slot; int onto = -1; int blob = -1; };
// A tint preset: a colour gain (`rgb`), for hair a lift toward white, and optionally a texture of its own that replaces the
// set's base skin (a skin tint) or base hair texture (a hair tint): the model's own other skin tones and hair colours.
struct HdTint { std::string id, label; uint32_t rgb = 0xFFFFFF; float lift = 0; int texture = -1; };

// One of the figure's bodies (the first is its own; others: a tail's length, bones showing, male parts...), with its own
// genitals' vertices (indices into that body mesh: hidden while the parts are drawn instead) and its shape keys' blobs (per
// HdSet::shapes, -1 where it hasn't that key).
struct HdBodyOption { std::string label; int mesh = -1; std::vector<uint32_t> genitals; std::vector<int> shapeBlobs; };
struct HdShape { std::string key, label; };

struct HdSet {
    std::string id, label;
    int race = 0, sex = 0;
    std::vector<std::string> bones;
    std::vector<std::pair<std::string, V3>> joints;   // the game bone's name -> the HD figure's joint (metres)
    std::vector<HdTexInfo> textures;
    std::vector<HdMesh> meshes;
    std::vector<int> always;               // meshes always drawn (eyes, brows)
    std::vector<HdEyeTex> eyeTextures;
    std::vector<HdChoice> hair, outfits, jewelry;
    std::vector<HdOverlay> overlays;
    std::vector<HdTint> tints, hairTints;
    std::vector<HdBodyOption> bodies;      // at least the body itself
    std::vector<HdShape> shapes;           // the shape keys offered as sliders (their changes per body: HdBodyOption::shapeBlobs)
    int skinTexture = -1, hairTexture = -1;   // the textures a tint with a texture of its own replaces (-1: none)
    std::string credit;                       // who made the model (shown with it)
    uint64_t gpuBytes = 0;
    std::vector<uint8_t> file;             // the whole file (the textures' PNGs are decoded from it when first drawn)
    std::vector<std::pair<uint64_t, uint64_t>> blobs;
    int body = -1;                         // the body mesh's index
    const V3* joint(const std::string& name) const;
    int mesh(const std::string& id) const;
    // a texture's PNG bytes (null: none)
    const uint8_t* blob(int i, size_t& size) const;
};

bool parseWhd(const uint8_t* data, size_t size, HdSet& out, std::string& error);
// The set written back (its JSON from its fields, its blobs as they are): the round trip's test, a set made in a test.
std::vector<uint8_t> writeWhd(const HdSet& set);

// One actor's choices (Native.setHdBody; bodyparts/HdModels.kt HdOptions). -1: the set's default (hair: its first;
// outfit: none, the body bare; eyes: the first; marks: none; tints: the first, "her own").
struct HdOptions {
    bool on = false;
    int hair = -1, outfit = -1, eyes = -1, faceMark = -1, bodyMarks = 0, tint = 0, hairTint = 0;
    int body = 0;                          // HdSet::bodies' index
    bool hideGenitals = false;             // the body's own genitals left out (the add-on parts drawn instead: Scene's setHdGenitalMask)
    std::vector<int> jewelry;
    std::vector<float> shapes;             // per HdSet::shapes, 0..1 (more or less is allowed: -1..2)
    uint64_t key() const;
};

struct HdFitStats {
    float scale = 1;                       // HD metres -> model units
    float jointError = 0;                  // the largest distance (model units) of an aligned limb joint from the model's direction line
    size_t vertices = 0, triangles = 0, unmapped = 0;   // unmapped: vertices whose bones the model lacks (weights transferred)
    double ms = 0;
};

struct HdDrawPart { uint32_t start = 0, count = 0; int texture = -1; bool alphaTest = false; uint8_t role = 0; };   // role 0 skin, 1 hair, 2 other
struct HdFit {
    std::vector<M2Vertex> verts;           // in the HD rig's bind space (rigPivots), the model's bone indices
    std::vector<uint32_t> indices;
    std::vector<HdDrawPart> parts;
    std::vector<uint32_t> bodyIndices;     // the body's own triangles as drawn (the soft body and the flesh measure)
    uint32_t bodyVertexEnd = 0;            // verts [0, bodyVertexEnd) are the body's
    std::vector<V3> rigPivots;             // per model bone
    HdFitStats stats;
};

// One actor's HD figure (Scene: setHdBody, its fit at the next frame, its drawing: hdscene.cpp).
struct HdDraw {
    HdOptions opt;
    int race = 0, sex = 0;
    bool requested = false;          // setHdBody with `on`
    bool active = false;             // fitted and drawn instead of the model
    uint64_t key = 0;                // the set, model and options it was fitted for
    std::string status;              // why it isn't drawn ("" when it is)
    std::shared_ptr<const HdSet> set;
    HdFit fit;
    bool hideGenitals = false;       // useHdGenitalMask: the body's own genitals hidden, the add-on parts drawn instead
    float skin[3] = {1, 1, 1};       // the skin's tint (linear gain on the skin's parts)
    unsigned vao = 0, vbo = 0, ibo = 0;
    std::vector<unsigned> tex;       // per drawn part (the scene's shared textures: not owned)
    void forgetGl() { vao = vbo = ibo = 0; tex.clear(); }
};

// The set fitted to model `m` with options `o` (an empty fit and `error` when it can't be).
HdFit fitHd(const M2Model& m, const HdSet& set, const HdOptions& o, std::string& error);

// The game body's weights at each of `points` (model space): its nearest skin vertex's (the model's geoset 0 group, the
// skin; `geosets` the shown ones, empty: all), the nearest of the shown vertices within a 3 cm grid search widened as needed.
void hdTransferWeights(const M2Model& m, const std::vector<uint16_t>& geosets, const std::vector<V3>& points,
                       std::vector<std::array<uint8_t, 4>>& bones, std::vector<std::array<uint8_t, 4>>& weights);

// The breasts' soft-body regions measured on the HD body (as eveBreastRegions does on Eve's).
size_t hdBreastRegions(const M2Model& m, const std::vector<M2Vertex>& verts, uint32_t end, std::vector<SoftRegion>& regions);

// A texture of the set as drawn: decoded, `overlay` (-1 none) laid over it, recoloured for hair by `hairTint` (null none).
bool hdTexture(const HdSet& set, int texture, int overlay, const HdTint* hairTint, std::vector<uint8_t>& rgba, int& w, int& h,
               std::string& error);

} // namespace wc
