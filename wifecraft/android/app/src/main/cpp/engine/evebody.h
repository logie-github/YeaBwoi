// The Eve body: a user's own Sims 4 body replacer (Eve), converted on the phone (bodyparts/EveConvert.kt) into our files in
// files/bodyparts/eve/ and put in place of a woman's Warcraft body at dress time, the head, neck, hair and feet kept.
//
// The pieces (this file is GL-free; evescene.cpp is the Scene's side: loading, refitting, drawing):
//   - EveMesh: a .weve file (bodyparts/EveFormat.kt EveFile documents the layout; parseEve here keeps in step with it): the
//     Eve mesh in Warcraft axes and Sims metres, skinned by Warcraft bone names, the Sims adult woman's joints (estimates)
//     and the open rings (neck, ankles) where the Warcraft body takes over.
//   - EveFitTable: assets/eve_fit.json, one row per Warcraft female model: whether the swap is allowed (and why not) and the
//     race's factors on the fit.
//   - fitEve: the mesh fitted to one model as dressed (its skeleton's joints, its own neck and ankle edges): each region of
//     the Sims body mapped onto the model's bones (the torso by height between hip, shoulder and neck, each limb segment
//     along its bone with its girth, the hands and fingers by their knuckles), the neck and ankles blended onto the model's
//     own neck tube and foot tops, then the rings snapped onto the model's cut edge, taking its bone weights and normals so
//     the seam stays shut in every pose. The model's own body triangles under the body bones are cut (a triangle goes when
//     any corner is weighted mostly to a body bone; head, neck, hair and feet stay).
//   - the skin: the neutral skin tinted to the actor's own skin at the neck (Recolor's maths: a per-channel gain in linear
//     light, the reference the Eve skin's own colour near its neck ring), or a chosen skin tone as it is; a breast overlay's
//     shade chosen nearest the skin by Lab distance and tinted in brightness only, blended at its opacity; and the seams
//     feathered: within ~1 cm of the neck and ankle rings the texture fades to the Warcraft skin's colour there.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "m2.h"
#include "softbody.h"
#include "wmath.h"

namespace wc {

constexpr uint32_t kEveMagic = 0x45564557;   // "WEVE"
constexpr uint32_t kEveVersion = 1;

struct EveMesh {
    std::string variant;
    std::vector<std::string> bones;                        // Warcraft-side names
    std::vector<std::pair<std::string, V3>> joints;        // Sims woman's joints (Warcraft axes, Sims metres; left side)
    std::vector<std::pair<std::string, std::vector<uint32_t>>> rings;   // "neck", "L_ankle", "R_ankle"
    std::vector<float> pos, nrm, uv, uv2;                  // 3, 3, 2, 2 per vertex
    std::vector<uint8_t> bi, bw;                           // 4 each per vertex
    std::vector<uint32_t> idx;
    uint32_t texW = 0, texH = 0;
    float cropV0 = 0, cropV1 = 1;
    size_t vertexCount() const { return pos.size() / 3; }
    bool joint(const std::string& name, V3& out) const;
    const std::vector<uint32_t>* ring(const std::string& name) const;
};
bool parseEve(const uint8_t* data, size_t size, EveMesh& out, std::string& error);
std::vector<uint8_t> writeEve(const EveMesh& m);
uint32_t eveCrc32(const uint8_t* data, size_t n);

// One row of assets/eve_fit.json: a Warcraft female model and how Eve fits it.
struct EveRaceFit {
    uint32_t model = 0;
    int race = 0;
    std::string name, quality, reason;
    bool enabled = false;
    float girth = 1.15f;      // every width (over the measured hip and shoulder ratios)
    float torso = 1, hips = 1, shoulders = 1, thigh = 1, calf = 1, arm = 1, forearm = 1;
    float neckTuck = 0.06f;   // the neck ring drawn in toward its centre (a share of its radius)
    float neckLift = 0.006f;  // and up into the Warcraft neck (Sims metres, scaled)
    float neckBase = 0.008f;  // where Eve's neck base sits under the Warcraft neck's lowest edge (Sims metres, scaled)
    float ankleLift = 0.0f;   // Eve's ankle ring moved up (+) or down on the foot (Sims metres, scaled)
    float ankleTuck = -0.05f; // the ankle rings pushed out from their centre (a share of their radius: over the foot's edge)
    float ankleSink = 0.006f; // and down over the foot's top (Sims metres, scaled)
};
struct EveFitTable {
    std::vector<EveRaceFit> rows;
    const EveRaceFit* forModel(uint32_t model) const;
};
bool parseEveFitTable(const std::string& json, EveFitTable& out, std::string& error);

// set.json (bodyparts/EveFormat.kt EveSetIndex).
struct EveTexInfo { std::string id, name, file; uint32_t swatch = 0, mean = 0, neck = 0, ankle = 0; };
struct EveShadeInfo { std::string id, file; uint32_t swatch = 0, mean = 0; int rect[4] = {0, 0, 0, 0}; };
struct EveSlotInfo { std::string slot; std::vector<EveShadeInfo> shades; };
struct EveSetIndex {
    std::vector<std::pair<std::string, std::string>> meshes;   // variant -> file
    int width = 0, height = 0;
    bool hasSkin = false;
    EveTexInfo skin;
    std::vector<EveTexInfo> tones;
    std::vector<EveSlotInfo> overlays;
};
bool parseEveSetIndex(const std::string& json, EveSetIndex& out, std::string& error);

// A PNG (8-bit greyscale, grey+alpha, RGB, RGBA or palette; not interlaced) as RGBA. False and `error` otherwise.
bool decodePng(const uint8_t* data, size_t size, std::vector<uint8_t>& rgba, int& w, int& h, std::string& error);

// ------------------------------------------------------------------------------------------------ colour maths
float eveToLinear(float c);   // sRGB 0..1 -> linear
float eveToSrgb(float c);
V3 eveLinear(uint32_t rgb);   // 0xRRGGBB -> linear
uint32_t eveRgb(V3 linear);
V3 eveLab(uint32_t rgb);      // CIE L*a*b* (D65)
// The shade whose swatch is nearest `target` by Lab distance (-1: none); its distance in `dist`.
int eveNearestShade(uint32_t target, const std::vector<EveShadeInfo>& shades, float* dist = nullptr);
// Recolor's gain: per channel, linear target over linear reference.
V3 eveGain(uint32_t target, uint32_t reference);

// ------------------------------------------------------------------------------------------------ the fit
struct EveOptions {
    bool on = false;
    std::string bottom = "plain";   // the lower-body variant (a mesh of the set; the first one when it lacks this one)
    int tone = -1;                  // -1: the neutral skin tinted to the actor's own; else that skin tone, as it is
    int overlaySlot = -1;           // -1: no overlay
    int overlayShade = -1;          // -1: the shade nearest the skin (Lab)
    float opacity = 0.6f;
};

struct EveFitStats {
    size_t cutVertices = 0, cutTriangles = 0, keptTriangles = 0;
    size_t neckEdges = 0, ankleEdges[2] = {0, 0}, ringSnapped[3] = {0, 0, 0};
    float ringMoveMean[3] = {0, 0, 0}, ringMoveMax[3] = {0, 0, 0};
    float unit = 1;                 // model units per Sims metre (the hip height ratio)
    float kHip = 1, kShoulder = 1;  // the measured width ratios
    double ms = 0;
};

// The fitted body on one model.
struct EveBody {
    bool ok = false;
    std::string error;
    std::vector<M2Vertex> verts;            // the model's bind space, its bone indices
    std::vector<uint32_t> indices;
    std::vector<bool> cut;                  // per drawn body vertex: under a body bone (its triangles go)
    // where the Warcraft skin meets Eve: its texture coordinates at the neck's and the ankles' cut edge (u, v pairs)
    std::vector<float> neckUvs, ankleUvs;
    EveFitStats stats;
};

// `verts`, `indices`, `subs`: the body as drawn (a shaped body's own or the model's); `geosets` those shown (empty: all).
EveBody fitEve(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
               const std::vector<M2Submesh>& subs, const std::vector<uint16_t>& geosets, const EveMesh& eve, const EveRaceFit& fit);

// The body as measured with Eve on (the soft body's regions, the flesh measure): the cut body's own triangles and Eve's in
// one list, Eve's in the largest shown skin section (`host`, -1 when there is none: nothing to measure).
struct EveMeasureMesh {
    std::vector<M2Vertex> verts;
    std::vector<uint32_t> indices;
    std::vector<M2Submesh> subs;
    int host = -1;
};
EveMeasureMesh eveMeasureMesh(const M2Model& m, const std::vector<M2Vertex>& bodyVerts, const std::vector<uint32_t>& cutIndices,
                              const std::vector<M2Submesh>& cutSubs, const std::vector<uint16_t>& geosets, const EveBody& eve);

// The soft body's breast regions measured on Eve: the vertices weighted to the breast bones (at least 30%) give each chest
// region's centre, depth and extent in place of what softRig found on the combined skin. Returns how many were changed.
size_t eveBreastRegions(const M2Model& m, const EveBody& eve, std::vector<SoftRegion>& regions);

// ------------------------------------------------------------------------------------------------ the texture
// What a set texture needs once per mesh: the low-frequency shading correction (the baked shading flattened: each vertex's
// skin brightness over a 6 cm neighbourhood against the body's mean, 80% of the difference taken out, at most -15% .. +30%),
// and the seams' texels (within kEveFeather of a ring, with their distance).
constexpr float kEveFeather = 0.012f;   // Sims metres
struct EveSeamTexel { uint32_t texel; float d; uint8_t ring; };   // ring 0 neck, 1 ankles
struct EvePrep {
    int w = 0, h = 0;
    std::vector<float> gain;         // (w/8) x (h/8) correction factors, bilinear over the texture
    int gw = 0, gh = 0;
    std::vector<EveSeamTexel> seam;
    float frontBefore = 0, backBefore = 0, frontAfter = 0, backAfter = 0;   // mean luminance of the torso's front and back
    uint32_t neck = 0, ankle = 0;    // the corrected skin's own colour within 4 cm of the neck ring and of the ankle rings
};
EvePrep evePrepare(const EveMesh& m, const std::vector<uint8_t>& rgba, int w, int h);
void eveApplyCorrection(const EvePrep& p, std::vector<uint8_t>& rgba);
// The luminance of `rgba` (linear) at the torso's front and back vertices (front: normal x > 0.6, back < -0.6, 1.0 to 1.45 up).
void eveFrontBack(const EveMesh& m, const std::vector<uint8_t>& rgba, int w, int h, float& front, float& back);

// One actor's texture: `base` (the corrected neutral skin or tone, w x h) times `gain` (linear; 1,1,1 for a tone), the
// overlay `ov` (ow x oh at rect x, y) blended at `opacity` after its colour is scaled by `ovGain` (linear, per channel), then the
// seams faded to `neckRgb` / `ankleRgb` (0xRRGGBB; tinted space, as drawn). `ov` may be null.
void eveCompose(const std::vector<uint8_t>& base, int w, int h, V3 gain, const uint8_t* ov, int ow, int oh, int ox, int oy,
                float opacity, V3 ovGain, const EvePrep& prep, uint32_t neckRgb, uint32_t ankleRgb, std::vector<uint8_t>& out);

// The mean colour (0xRRGGBB, averaged in linear light) of `rgba` around each (u, v) of `uvs` (radius 2 px); -1 when none is opaque.
int eveSampleAt(const uint8_t* rgba, int w, int h, const std::vector<float>& uvs);

// ------------------------------------------------------------------------------------------------ the scene's state
// The loaded set (Scene::loadEveSet): its index, the fit table, its meshes and the textures decoded so far (corrected once).
struct EveSet {
    std::string dir;
    EveSetIndex index;
    EveFitTable fits;
    std::vector<std::pair<std::string, std::shared_ptr<EveMesh>>> meshes;   // by variant
    struct Tex { std::vector<uint8_t> rgba; int w = 0, h = 0; EvePrep prep; };
    std::vector<std::pair<std::string, std::shared_ptr<Tex>>> textures;      // by "file|variant", the skins (corrected)
    std::vector<std::pair<std::string, std::shared_ptr<Tex>>> overlays;      // by file, as they are
    uint64_t generation = 0;
    std::shared_ptr<EveMesh> mesh(const std::string& variant) const;
};

// One actor's Eve body (Scene: setEveBody, its refit at the next frame, its drawing).
struct EveDraw {
    EveOptions opt;
    bool requested = false;          // setEveBody with `on`
    bool active = false;             // fitted, its body cut, drawn
    uint64_t key = 0;                // the look and options it was fitted for
    std::string status;              // why it isn't drawn ("" when it is)
    EveBody body;
    EveMeasureMesh measure;          // the cut body and Eve as one (the soft body, the flesh measure)
    std::vector<uint8_t> rgba;       // its texture (kept to upload again after a lost context)
    int w = 0, h = 0;
    unsigned tex = 0, vao = 0, vbo = 0, ibo = 0;
    uint32_t neckRgb = 0, ankleRgb = 0, reference = 0;
    int shade = -1;
    float shadeDist = 0;
    double composeMs = 0;
    void forgetGl() { tex = vao = vbo = ibo = 0; }
};

} // namespace wc
