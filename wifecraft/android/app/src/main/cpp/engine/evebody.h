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

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "bodyrig.h"
#include "m2.h"
#include "sculpt.h"
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
    float ankleFeather = 0.012f;   // the ankle rings' colour feather (Sims metres; kEveFeather, wider on a split mesh's thighs)
    size_t vertexCount() const { return pos.size() / 3; }
    bool joint(const std::string& name, V3& out) const;
    const std::vector<uint32_t>* ring(const std::string& name) const;
};
bool parseEve(const uint8_t* data, size_t size, EveMesh& out, std::string& error);
std::vector<uint8_t> writeEve(const EveMesh& m);
// Eve cut across both thighs at `t` of the way from hip to knee (her joints): the triangles below dropped, the open edge
// left there her new "L_ankle" and "R_ankle" rings (so the fit, the seam feather and the tint treat it as the ankles were),
// the variant named "<variant>|split<t>". Vertices are kept (unused below the cut).
EveMesh eveSplitMesh(const EveMesh& m, float t);
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
    // Her proportions under the Warcraft head (the skeleton edited per actor while she wears Eve: bodyrig.h): the body made
    // `heads` of the model's own head heights tall (chin to crown; 0: the model's own lengths), the hip joints at `hipRatio`
    // of that height, shoulder to fingertip `armRatio` of it, a hand `handHeads` head heights long, the clavicles `spread`.
    float heads = 0, hipRatio = 0, armRatio = 0, handHeads = 0, spread = 1.0f;   // (0: the model's own)
    // The split fit (> 0): Eve from the neck to this share of the way down the thigh (hip to knee), the model's own legs
    // below it (a draenei's hooves): eveSplitMesh trims her there, the model keeps its thighs below the cut and its calves.
    float split = 0;
    // The colour transfer's reach (Sims metres, scaled; 0: off, the neutral skin tinted by one colour as before).
    float transfer = 0.06f;
    // The seams stitched to the model's own cut-edge vertices (1) or only snapped onto them (0).
    float stitch = 1;
    // The model's own painted details carried onto Eve (Keep undead bones, EveOptions::bones): 1 its bone pieces, wounds,
    // stitches and torn rims baked into her skin (eveDetailLayer); 2 the bone pieces also kept as the model's own geometry
    // (they stick out of her as they stick out of its body); 0 none.
    float detail = 0;
    // The trunk's weights taken from the model's own body (1) or kept as Eve's Sims rig had them (0): the Sims spine maps onto
    // the model's spine bones at the wrong heights (her ribcage on Spine2, her belly on the pelvis, the thighs pulling skin
    // above the hips); each trunk vertex takes the bone weights of the model's own skin around it, blended by how much of
    // it the trunk drives, the arms', hands' and legs' below the hips kept.
    float rezone = 0;
    // The limbs' girth from the model's own (0..1): each upper arm, forearm, thigh and calf scaled across its bone so its
    // median radius moves this share of the way to the model's own limb's (measured on the body Eve replaces, after the
    // factors above), so a bigger race keeps its arms and legs.
    float limbs = 0;
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
    bool bones = true;              // Keep undead bones: the model's details on her where its fit row has them (EveRaceFit::detail)
};

struct EveFitStats {
    size_t cutVertices = 0, cutTriangles = 0, keptTriangles = 0;
    size_t neckEdges = 0, ankleEdges[2] = {0, 0}, ringSnapped[3] = {0, 0, 0};
    float ringMoveMean[3] = {0, 0, 0}, ringMoveMax[3] = {0, 0, 0};
    float unit = 1;                 // model units per Sims metre (the hip height ratio)
    float head = 0, height = 0, heightAfter = 0;   // the model's head (chin to crown), its height, hers with the proportions
    int stitched = 0;               // seams stitched to the model's edge
    size_t rezoned = 0;             // trunk vertices that took the model's weights
    float limbScale[4] = {1, 1, 1, 1};   // the limbs' girth correction: upper arm, forearm, thigh, calf (left)
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
    BodyScale scale;                        // her proportions on the skeleton (bodyrig.h), the Leg Length slider's on top
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
// A gain per texel (linear, per channel) over the texture, held coarse (gw x gh cells, bilinear): eveTransferMap's.
struct EveDetailLayer;   // (below: the model's own details, Keep undead bones)
struct EveGainMap { int gw = 0, gh = 0; std::vector<float> rgb; bool empty() const { return rgb.empty(); } };
void eveCompose(const std::vector<uint8_t>& base, int w, int h, V3 gain, const uint8_t* ov, int ow, int oh, int ox, int oy,
                float opacity, V3 ovGain, const EvePrep& prep, uint32_t neckRgb, uint32_t ankleRgb, std::vector<uint8_t>& out,
                const EveGainMap* map = nullptr, const EveDetailLayer* detail = nullptr);   // (detail: over the overlay, under the seams)

// The skin's colour taken from her own Warcraft body (the colour transfer): each fitted Eve vertex gets the colour of the
// model's skin around the same place (its shown skin vertices within `radius` model units whose normals face the same way,
// Gaussian weighted, their texture `skin` (sw x sh RGBA) blurred and `tint`ed), over Eve's own colour there (`base`, w x h,
// blurred the same), as a per-channel gain map over Eve's texture. Eve keeps her detail; the body's tones (the tan of a
// leg, the darker feet, an orc's painted greens) come from the model's, so they meet its head, hands and feet. `wow`:
// the model's vertices; `use`: which of them are its shown skin.
// `anchors`: near each (within its reach) the gain eases to the anchor's own (the one that matches the model's colour at that
// seam's cut edge exactly), so the seams meet whatever the body's tones around them.
struct EveGainAnchor { std::vector<V3> points; V3 gain; float reach = 0; };
EveGainMap eveTransferMap(const EveBody& eve, const std::vector<uint8_t>& base, int w, int h, const std::vector<M2Vertex>& wow,
                          const std::vector<uint8_t>& use, const uint8_t* skin, int sw, int sh, const float tint[3], float radius,
                          const std::vector<EveGainAnchor>& anchors = {});

// The mean colour (0xRRGGBB, averaged in linear light) of `rgba` around each (u, v) of `uvs` (radius 2 px); -1 when none is opaque.
int eveSampleAt(const uint8_t* rgba, int w, int h, const std::vector<float>& uvs);

// ---- the model's own details on Eve (Keep undead bones)
// The model's skin texture (sw x sh RGBA) under its body's triangles (`tris`: vertex triples of `wow`) sorted into skin and
// detail: its skin tone (the commonest chroma of those texels, the lightness there), each texel's distance from it (darker
// counts more than lighter: painted shading is no detail), and whole islands (triangles joined by their vertices) mostly off
// the tone taken as detail pieces (a Forsaken's bone pieces: knees, spine, spurs), with their vertices (`piece`).
struct EveDetailMask {
    int w = 0, h = 0;
    std::vector<uint8_t> a;        // per texel: how much of it is detail (0..255)
    uint32_t tone = 0;             // the skin's tone (0xRRGGBB)
    std::vector<uint8_t> piece;    // per vertex of `wow`: on a detail piece
    size_t pieces = 0, texels = 0, detailTexels = 0;
    bool empty() const { return a.empty(); }
};
EveDetailMask eveDetailMask(const std::vector<M2Vertex>& wow, const std::vector<uint32_t>& tris, const uint8_t* skin, int sw, int sh);
// The details over Eve's texture (w x h): each of her texels (its point on the fitted body, its normal) takes the nearest
// point of the model's body (`tris`) facing her way within `reach` (model units), that point's texel of `skin` as it is (no
// blur: the nearest texel) tinted by `tint` (linear), its coverage the mask's there, fading out from half the reach to the reach;
// then grown 3 texels past her islands' edges (no mip bleed) and its coverage's edges softened (3 x 3).
struct EveDetailLayer {
    int w = 0, h = 0;
    std::vector<uint8_t> rgba;     // the detail's colour (sRGB) and its coverage (alpha)
    size_t texels = 0, covered = 0, grown = 0;
    double ms = 0;
    bool empty() const { return rgba.empty(); }
};
EveDetailLayer eveDetailLayer(const EveBody& eve, int w, int h, const std::vector<M2Vertex>& wow, const std::vector<uint32_t>& tris,
                              const uint8_t* skin, int sw, int sh, const EveDetailMask& mask, const float tint[3], float reach);
// `layer` over `rgba` (w x h, its own size) in linear light by its coverage.
void eveApplyDetail(const EveDetailLayer& layer, std::vector<uint8_t>& rgba);
// The skin (sw x sh, any size: the mask scaled onto it) with its details eased to the tone by the mask: what the colour
// transfer reads, so a bone's tan no longer browns her skin around it.
void eveDetailClean(uint8_t* skin, int sw, int sh, const EveDetailMask& mask);

// ------------------------------------------------------------------------------------------------ the scene's state
// The loaded set (Scene::loadEveSet): its index, the fit table, its meshes and the textures decoded so far (corrected once).
struct EveSet {
    std::string dir;
    EveSetIndex index;
    EveFitTable fits;
    std::vector<std::pair<std::string, std::shared_ptr<EveMesh>>> meshes;   // by variant
    std::vector<std::pair<std::string, std::shared_ptr<EveMesh>>> splits;   // eveSplitMesh's, by its variant (cacheMutex)
    struct Tex { std::vector<uint8_t> rgba; int w = 0, h = 0; EvePrep prep; };
    std::vector<std::pair<std::string, std::shared_ptr<Tex>>> textures;      // by "file|variant", the skins (corrected)
    std::vector<std::pair<std::string, std::shared_ptr<Tex>>> overlays;      // by file, as they are
    struct Detail { EveDetailMask mask; EveDetailLayer layer; };
    std::vector<std::pair<uint64_t, std::shared_ptr<Detail>>> details;     // Keep undead bones' layers, by the fitted body and skin
    uint64_t generation = 0;
    std::mutex cacheMutex;           // textures and overlays: the fit's worker and the GL thread both fill them
    std::shared_ptr<EveMesh> mesh(const std::string& variant) const;
};

// ------------------------------------------------------------------------------------------------ the fit off the GL thread
// Everything the fit, the cut and the skin need, taken from the actor on the GL thread (a snapshot: the worker never reads
// the actor), and what comes back. eveCompute is the whole CPU side, the same function for the worker and for a picture
// drawn at once (Scene::eveRefit with the actor's `sync`), so both give the same bytes.
struct EveJob {
    uint64_t serial = 0;                       // the actor's (EveDraw::serial)
    uint64_t key = 0, base = 0;                // the look it is for, the body it is cut from (EveDraw::key, baseGen)
    const M2Model* model = nullptr;            // the scene's (kept for its life: Scene::models_ is never pruned)
    std::shared_ptr<EveSet> set;
    std::shared_ptr<EveMesh> mesh;
    EveRaceFit fit;
    EveOptions opt;
    std::vector<M2Vertex> verts;               // the shaped body (empty: the model's own vertices)
    std::vector<uint32_t> indices;             // its triangles before Eve's cut, by `subs`
    std::vector<M2Submesh> subs;
    std::vector<uint16_t> geosets;
    std::vector<float> sculpt;                 // the creator's sliders (sculpt.h)
    SculptRig rig;                             // sculptRigOf(model), made on the GL thread
    std::string skinPath;                      // the actor's base skin layer's file ("": none)
    float tint[3] = {1, 1, 1};                 // its skin's tint (ActorParts::tint)
    bool mips = false;                         // the texture's mip levels made too (the GL thread then only uploads)
};
struct EveResult {
    uint64_t serial = 0, key = 0, base = 0;
    bool ok = false;
    std::string status;                        // why not ("" when ok)
    EveBody body;
    EveMeasureMesh measure;
    std::vector<uint32_t> cutIndices;          // the body's triangles with Eve's cut, by cutSubs
    std::vector<M2Submesh> cutSubs;
    std::vector<uint8_t> rgba;
    int w = 0, h = 0;
    std::vector<std::vector<uint8_t>> mips;    // its smaller levels (1, 2, ... down to 1 x 1; box filtered), when the job asked
    uint32_t neckRgb = 0, ankleRgb = 0, reference = 0;
    int shade = -1;
    float shadeDist = 0;
    double composeMs = 0, totalMs = 0;
    float seamDE[3] = {-1, -1, -1};            // the skin's colour across the neck and ankle seams (CIE76 ΔE: Eve 2 to 5 cm from
                                               // the ring against the model's skin as far on its side; -1: not measured)
    // Keep undead bones (EveRaceFit::detail with EveOptions::bones): the details carried over, the pieces kept, the time
    bool detail = false;
    size_t detailPieces = 0, detailKept = 0, detailCovered = 0;
    double detailMs = 0;
};
// The texture's mip levels below `rgba` (w x h): each the 2 x 2 mean of the one above, down to 1 x 1.
std::vector<std::vector<uint8_t>> eveMips(const std::vector<uint8_t>& rgba, int w, int h);
EveResult eveCompute(const EveJob& job);

// One worker thread for a scene: the newest job per actor (a job still queued for an actor is replaced by its newer one; one
// already running finishes and its result is dropped by key when it no longer matches), results taken by the GL thread.
class EveWorker {
public:
    EveWorker();
    ~EveWorker();                              // stops and joins (a running job finishes first)
    void submit(std::unique_ptr<EveJob> job);
    void cancel(uint64_t serial);              // drops its queued job and any finished result
    std::unique_ptr<EveResult> take(uint64_t serial);   // its newest finished result (null: none yet)
    bool idle();                               // nothing queued or running (tests)
private:
    void run();
    std::mutex mu_;
    std::condition_variable cv_;
    std::deque<std::unique_ptr<EveJob>> queue_;
    std::vector<std::unique_ptr<EveResult>> done_;
    uint64_t running_ = 0;
    bool stop_ = false;
    std::thread thread_;
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
    // the fit off the GL thread (EveWorker): the body as built before Eve's cut, kept so a new fit swaps the cut in one frame
    uint64_t serial = 0;             // this actor's (given at its first fit)
    std::vector<uint32_t> baseIndices;
    std::vector<M2Submesh> baseSubs;
    bool baseReady = false, baseOwn = false;   // baseOwn: BodyGl::shaped was copied from the model for the cut alone
    uint64_t baseGen = 0;            // a new base each time the body is built again
    uint64_t pending = 0;            // the key of the job in flight (0: none)
    uint32_t waitFrames = 0;         // frames drawn since it was sent
    double glMs = 0;                 // the GL thread's time for the last look change (snapshot, swap, upload)
    bool sync = false;               // fitted at once (Scene::setEveAsync off, a picture's actor)
    // a finished fit being uploaded a little each frame (Scene::eveStage) before it is swapped in
    std::shared_ptr<EveResult> staged;
    unsigned stTex = 0, stVao = 0, stVbo = 0, stIbo = 0;
    int stStep = 0;                  // 0: nothing made yet; then the texture's rows uploaded so far (all levels, in order)
    double stageMs = 0;              // the GL thread's most in one frame while staging
    void forgetGl() { tex = vao = vbo = ibo = 0; stTex = stVao = stVbo = stIbo = 0; stStep = 0; }
};

} // namespace wc
