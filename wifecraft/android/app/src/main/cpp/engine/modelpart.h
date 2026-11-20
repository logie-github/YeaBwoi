// Model parts: pieces cut from a game model and drawn rigid on a named bone of another body. A character the game draws
// with her own creature model has her armour baked into that model (Sylvanas, Calia, Jaina...): nothing of it can be
// taken off. Her partly undressed look is made of two models instead: a stand-in body (the playable race's own model,
// bare: the creator's "bare" look) with its own head cut away (setBodyCut), and the pieces kept from her model (her head
// with its hair, helm and face; her pauldrons) cut out here and put on the stand-in's bones (Scene::attachBonePart).
//
// Cutting. Every vertex belongs to the bone that weighs most on it (dominantBone). A region (PartRegion) takes the
// subtrees of its root bones, less its stops' subtrees: of those, the unnamed helper bones (key id -1: a crown's horns,
// a pauldron's own bones, the hair's and the face's) count wholly; the named bones themselves (the clavicle, the upper
// arm) count only where the region says so: a vertex standing off the bone's axis by `standoff` (a pauldron's shell, not
// the arm's skin under it) and not lower than `reach` below the attach bone (the shoulder, not the sleeve). Lengths are in
// the source's torso lengths (its Head pivot to its Pelvis pivot), so one rule fits a short and a tall model. A triangle
// is taken when `corners` of its corners count (1 for the pieces: they overlap the stand-in at the seam rather than leave
// a gap; the stand-in's cut drops a triangle only when all three do). Each batch (texture and blend) of the source becomes
// one part (version 2 part file, kPartModelFrame): the source's own positions about its attach bone's pivot, its UVs and
// normals, its texture file and blend, and the source's pivots of the bones the fit measures with.
//
// Fitting (placeBonePart). The part's attach-bone pivot goes onto the stand-in's same bone's pivot; its size is the
// stand-in's distance between the part's two scale bones over the source's (the head: Head to Pelvis, so a tall model's
// head keeps its share of the stand-in's height; the pauldrons: across the upper arms, so they sit on its shoulders),
// times the fit's factor; the fit's offset moves it (in the stand-in's scale-bone lengths, model axes). Both models stand
// in their bind pose facing +x, so no turn is needed. Drawn: bone matrix (as animated) * that placement, as the Pelvis
// parts are (bodypart.h).
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <vector>

#include "bodypart.h"
#include "json.h"
#include "m2.h"
#include "wmath.h"

namespace wc {

struct PartRegion {
    std::string name;                     // the piece's name in its set: head, shoulder_l ...
    std::string attach;                   // the bone it rides (WoW-side name: Head, L_Clavicle ...)
    std::vector<std::string> roots;       // the subtrees it takes
    std::vector<std::string> stops;       // subtrees under them left out
    bool helpers = true;                  // the unnamed bones under the roots (and everything under those) count
    bool named = true;                    // the named bones under the roots count (with standoff and reach)
    float standoff = 0;                   // > 0: a named bone's vertex counts only this far off its bone's axis (torso lengths)
    float reach = -1;                     // >= 0: and only this far below the attach bone's pivot at most (torso lengths);
                                          // a helper bone whose pivot hangs lower doesn't count, nor any under it
    float side = 0;                       // > 0: a named bone's vertex counts only this far out from the midline (|y|, torso
                                          // lengths): a pauldron, not the chest piece's straps and collar by the neck
    std::vector<float> box;               // 6 numbers: x, y, z ranges (min, max each) about the attach bone's pivot (torso
                                          // lengths) every counted vertex must be in (empty: anywhere): a back-piece behind
                                          // the shoulders, not the back itself
    int islands = 12;                     // pieces of a part (triangles joined by corners) smaller than this are dropped
                                          // (the edges of a cut: a strap's end, a sliver of skin)
    int corners = 1;                      // a triangle is taken when this many of its corners count
    std::string scaleA = "Head", scaleB = "Pelvis";   // the two bones whose distance sizes the piece on a body
};

// The regions a manifest names when it doesn't define them: head, shoulder_l, shoulder_r, hand_l, hand_r, cloak.
const std::map<std::string, PartRegion>& defaultPartRegions();

// The bone weighing most on a vertex (its first when they tie).
inline int dominantBone(const M2Vertex& v) {
    int best = 0;
    for (int k = 1; k < 4; ++k) if (v.weights[k] > v.weights[best]) best = k;
    return v.bones[best];
}

// The bones `names` (WoW-side) stand for, and every bone under each (true per bone index).
std::vector<bool> boneSubtrees(const M2Model& m, const std::vector<std::string>& names);
// Per vertex of `m` (or of `vertices`, the model's own reshaped: a shaped body's): whether it counts for `r` (the rules
// above). `torso`: the source's torso length (0: measured).
std::vector<bool> regionVertices(const M2Model& m, const PartRegion& r, float torso = 0, const std::vector<M2Vertex>* vertices = nullptr);
// The model's torso length: Head pivot to Pelvis pivot (else Root), 1 when it has neither.
float torsoLength(const M2Model& m);

// The region cut from `m` as shown (`geosets`: the shown submesh ids; empty: all), one part per batch with triangles in
// it, ids "<prefix><region>_<n>". `textures`: type -> file for the model's texture slots (a creature's skins), when
// they aren't files of its own. Returns how many triangles were taken in all.
// `pose` (the source's bone matrices, M2Model::bonesToMatrices; null: its bind pose): each vertex skinned as the source
// stands, then taken back into its attach bone's bind frame (that bone's matrix inverted), so helper bones the standing
// animation lifts (Xal'atath's shoulder wings) are kept where they are seen.
size_t extractRegion(const M2Model& m, const std::vector<uint16_t>& geosets, const PartRegion& r,
                     const std::map<uint32_t, uint32_t>& textures, const std::string& prefix, std::vector<PartData>& out,
                     const std::vector<M4>* pose = nullptr);
// A 4x4 matrix inverted (the identity when it can't be).
M4 invertMatrix(const M4& m);

// The stand-in's cut: `indices` (triangles, by submesh `subs`) without those whose corners all count in `mask` (per
// vertex); `subs` gets the new ranges. `corners`: how many counting corners drop a triangle (3: all).
std::vector<uint32_t> cutTriangles(const std::vector<uint32_t>& indices, std::vector<M2Submesh>& subs,
                                   const std::vector<bool>& mask, int corners = 3);

// Where the skin match samples a model's skin: its face's front below the eyes (cheeks, mouth, chin), the vertices the
// Head or Jaw bone dominates within a band about the Head pivot's height, in front of it, facing forward. The face is
// skin on every model (a neck often wears a collar, the hair hangs by it), and the stand-in's face is in the same place.
std::vector<bool> faceVertices(const M2Model& m);
// The texture coordinates (u, v pairs, v 0 at the top) of `m`'s vertices in `mask` (per vertex) drawn by an opaque batch
// whose texture `accept` takes (returns a key: its file, or a slot; 0 refuses): those of the key with the most such
// vertices, which is returned (0: none).
uint32_t maskUvs(const M2Model& m, const std::vector<uint16_t>& geosets, const std::vector<bool>& mask,
                 const std::function<uint32_t(const M2Texture&)>& accept, std::vector<float>& uvs);
// The mean colour (0xRRGGBB, in linear light) of an RGBA image's texels at `uvs` (and the 8 around each); -1 when none.
int meanColourAt(const uint8_t* rgba, int w, int h, const std::vector<float>& uvs);

// How a part sits on a body (the manifest's fit; Scene::attachBonePart).
struct BonePartFit {
    V3 offset;            // in the body's scale-bone lengths, model axes (x forward, y left, z up)
    float scale = 1;      // a factor on the measured size
};
struct BonePartPlacement {
    bool ok = false;
    int bone = -1;        // the body's bone it rides
    V3 source, target;    // the attach bone's pivot on the source (the part's anchor) and on the body (offset included)
    float scale = 1;      // body units per source unit
};
BonePartPlacement placeBonePart(const PartData& p, const M2Model& body, const BonePartFit& fit);
// bone matrix (as animated) * translation(target) * scaling(scale) * translation(-source): the part's source-space points
// (partModelPoint) to the body's model space as drawn.
M4 bonePartMatrix(const M4& bone, const BonePartPlacement& pl);

// The manifest (assets/model_parts.json): which characters' models give which pieces to which stand-in.
struct ModelPartSet {
    std::string set;                      // the folder: files/bodyparts/<set>/
    std::string character, look;          // the roster's character and the look it stands for (informative)
    uint32_t display = 0, model = 0;      // the source creature display and its model file
    std::map<uint32_t, uint32_t> textures;   // its texture slots (type -> file), when the model's own aren't files
    std::vector<uint16_t> geosets;        // the source's shown submesh ids (empty: all)
    uint32_t standIn = 0;                 // the stand-in body's model file
    std::string standInLook;              // the roster's look for it (the character's "bare" look)
    std::vector<std::string> regions;     // the pieces kept
    std::vector<std::string> cut;         // the stand-in's subtrees cut away (default: Head)
    bool skinMatch = true;                // the stand-in tinted to the source's face (false: a face that isn't skin, glowing)
    std::map<std::string, BonePartFit> fits;   // per region
};
struct ModelPartManifest {
    std::map<std::string, PartRegion> regions;   // defaultPartRegions() with the manifest's own over them
    std::vector<ModelPartSet> sets;
};
bool parseModelPartManifest(const std::string& text, ModelPartManifest& out, std::string& error);

// Cuts one set's pieces from its source model (found with `files`) into `dir`/<set>/: <region>_<n>.wcbp each, and
// set.json (the stand-in, the cut, the source's face colour for the skin match, each part's file, region, bone, texture,
// blend and fit). False and `report` says why
// (a model not downloaded, a region not in the manifest); `report` also lists what was written.
bool buildModelPartSet(const ModelPartSet& s, const ModelPartManifest& man, const FileLookup& files, const std::string& dir,
                       std::string& report);

} // namespace wc
