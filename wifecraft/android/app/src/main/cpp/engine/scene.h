// The 3D scene behind the story: Dalaran's buildings and props, the people on stage, the sky,
// the light and the camera, drawn with OpenGL ES 3.0. Everything is in WoW's own coordinates
// (z up, yards), the city's at its own origin.
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

#include "location.h"
#include "m2.h"
#include "skin.h"
#include "softbody.h"
#include "wmath.h"
#include "wmo.h"
#include "glutil.h"

namespace wc {

struct GlTexture {
    unsigned id = 0;
    bool hasAlpha = false;
};

// A model a look adds to a person: hung on an attachment point, or (attachment -1) skinned to the
// body's own bones (ChrCustomizationSkinnedModel's collection models: hair, horns, ears...).
struct DressModel {
    int attachment = -1;
    uint32_t model = 0;
    uint32_t texture = 0;     // fills the model's replaceable textures the person's don't (0: none)
    int geoset = 0;           // a skinned model's own geoset (group * 100 + id; 0: none), shown whatever the groups say
};

// The look's layer that is the body options' design (creator/LookResolver.BODY_LAYER), not a layer of the skin.
constexpr int kBodyLayer = 100;

// An imported animation on a person (anim/Anim, by WoW-side bone names): each track replaces one bone's local
// rotation (x,y,z,w about its pivot, in its parent's frame: at rest every M2 bone's is the identity, so the model's
// axes) or translation (model units, given for kAnimRootHeight and scaled to the model's own Root). Times in seconds.
struct BoneAnimTrack {
    std::string bone;
    bool rotation = true;
    std::vector<float> times;
    std::vector<float> values;     // 4 per time (rotation) or 3 (position)
};
struct BoneAnim {
    std::vector<BoneAnimTrack> tracks;
    float duration = 0;            // seconds; 0 or one key: a held pose
    bool loop = false;             // otherwise the last key holds
};

struct Light {
    float skyTop[3] = {0.25f, 0.38f, 0.66f};
    float skyHorizon[3] = {0.72f, 0.78f, 0.86f};
    float skyBelow[3] = {0.35f, 0.42f, 0.52f};
    float sunDir[3] = {-0.4f, 0.3f, -0.85f};   // the direction the light travels
    float sunColor[3] = {1.0f, 0.95f, 0.85f};
    float ambient[3] = {0.45f, 0.47f, 0.55f};
    float fogColor[3] = {0.72f, 0.78f, 0.86f};
    float fogStart = 120, fogEnd = 900;
};

class Scene {
public:
    explicit Scene(FileLookup files) : files_(std::move(files)) {}
    ~Scene();

    bool initGl();             // after a context is current; again after it was lost
    void releaseGl();          // forget GL objects (the context is gone)

    // Dalaran: the root file, the groups to draw, and props within `radius` of any of `centres`.
    bool loadCity(uint32_t rootFileId, const std::vector<int>& groups, const std::vector<V3>& centres, float radius);
    void clearCity();
    // Whether the city is drawn and stood on (the editor's studio and the character creator: people alone, in the
    // void). It stays loaded either way.
    void showCity(bool shown) { cityShown_ = shown; }

    // A person. `geosetGroups` (group -> value) makes the model a character race's: its default
    // geosets, then each listed group showing only that value (0: none). Empty: every geoset.
    // Why the model couldn't be read the last time it was asked for ("" when it could, or wasn't asked for).
    std::string modelError(uint32_t fileId) const;
    int addActor(uint32_t modelFileId, const std::map<uint32_t, uint32_t>& textures,
                 const std::map<int, int>& geosetGroups, float scale);
    // A model hung on one of an actor's attachment points (shoulders, a helm, a weapon).
    void attach(int id, uint32_t attachmentId, uint32_t modelFileId, const std::map<uint32_t, uint32_t>& textures);
    // Changes a standing person's look without taking them off stage (the creator, per tap): the
    // geoset groups as addActor takes them, the skin's layers (composed into one texture per
    // texture type; replacing addActor's textures of those types) and the models the look adds.
    // Each call gives the whole look: what it leaves out is gone (attach()'s items stay).
    void dress(int id, const std::map<int, int>& geosetGroups, const std::vector<SkinLayer>& layers,
               const std::vector<DressModel>& models);
    // Wrath++'s body options on a standing person: `morph` the shape (BodyShapes.morph's records: bodyshape.h shapeBody, empty: as drawn) and the
    // body layer's square (creator/BodyShapes.kt Frame: the race's scale `k` and fold height, k 0: no layer). The layer's
    // design is the look's skin layer of texture type 1 at kBodyLayer (dress takes it out of the skin's composing): drawn as
    // Wrath++'s native mod draws it, over the body's own front triangles under the square, lifted off the skin, the
    // character's hair colour times the design. Kept until changed; dress() leaves it.
    void setBody(int id, const std::vector<float>& morph, float k, float fold);
    void removeActor(int id);
    void placeActor(int id, V3 position, float facing);
    void playAnim(int id, uint16_t animId, bool loop, uint32_t nowMs);
    void setIdleAnim(int id, uint16_t animId);
    // Plays an imported animation over the actor's own one (setBoneAnim): its bones, and every bone above them, follow
    // it (those it leaves out stand at rest); the other bones (face, hair, cloth) keep the actor's animation. It holds
    // or loops until cleared; dress() keeps it. Returns how many of its tracks name a bone this model has.
    int setBoneAnim(int id, const BoneAnim& anim, uint32_t nowMs);
    void clearBoneAnim(int id);
    // The desktop check: an actor's bone matrices as last drawn, the sequence and how far into it; the animations' clock.
    bool actorPose(int id, std::vector<M4>& mats, int& seq, uint32_t& timeMs) const;
    uint32_t animClock() const { return animClock_; }
    // Options' Soft body for everyone on stage (softbody.h: kSoftOff .. kSoftStrong); applies from the next frame.
    void setSoftBody(int level);
    // An actor's soft body (the desktop check reads its regions and displacements), or null.
    const SoftSim* softSim(int id) const { auto it = actors_.find(id); return it == actors_.end() ? nullptr : &it->second.soft.sim; }
    void showActor(int id, bool visible);
    bool actorReady(int id) const;
    // How tall an actor stands (for the camera's framing).
    float actorHeight(int id) const;
    // The box an actor's body takes standing as modelled (its vertices, scaled), around its feet.
    bool actorBounds(int id, V3& min, V3& max) const;
    // What the body options made of an actor (the desktop check): vertices the shape moved, the layer's triangles.
    bool bodyStats(int id, size_t& moved, size_t& layerTriangles) const;
    // The floor under (x, y) nearest below z (a little above counts: a step up), or z itself when
    // the city has no floor there.
    float floorAt(float x, float y, float z) const;

    void setCamera(V3 eye, V3 target, float fovDegrees);
    void setLight(const Light& l) { light_ = l; }
    const Light& light() const { return light_; }

    // ---------------------------------------------------------------- locations and props (location.cpp)
    // A location (location.h: an entry of assets/locations.json): its WMO's groups in world coordinates, its doodads and
    // extra placements drawn instanced by model, its light and fog (with `view`'s own over them; "" none). Its files must be
    // here (LocationLoader fetched them); a group or model that isn't is left out and counted in locationStats. Replaces
    // the location before. False and locationError() when the entry or its root can't be read.
    bool loadLocation(const std::string& json, const std::string& id, const std::string& view);
    // A view of the loaded location: its light, fog and ground (the camera is the caller's: the view's is in the entry).
    bool locationView(const std::string& view);
    void clearLocation();
    bool locationLoaded() const { return location_ != nullptr; }
    const std::string& locationError() const { return locError_; }
    const LocationDesc* location() const { return location_ ? &locDesc_ : nullptr; }
    struct LocationStats {
        size_t groups = 0, groupsMissing = 0, triangles = 0, doodads = 0, doodadsMissing = 0, models = 0;
        size_t drawCalls = 0, groupsDrawn = 0, instancesDrawn = 0;   // the last frame's
        V3 boundsMin, boundsMax;
    };
    LocationStats locationStats() const;

    // An editor prop: a static M2 or a WMO (with its default doodad set) by file id, standing at `pos` turned `yaw`
    // (radians about z) at `scale`. Its files must be here (PropLoader). Returns its id, 0 when it can't be read
    // (modelError(fileId) says why).
    int addProp(uint32_t fileId, V3 pos, float yaw, float scale);
    bool moveProp(int id, V3 pos, float yaw, float scale);
    void removeProp(int id);
    void clearProps();
    // A prop's box in the world.
    bool propBounds(int id, V3& min, V3& max) const;
    // What is selected: drawn with an outline (kind 1 a prop) or nothing (0). An actor (2) is remembered, not outlined.
    void select(int kind, int id) { selKind_ = kind; selId_ = id; }

    enum PickKind { kPickNone = 0, kPickProp = 1, kPickActor = 2, kPickGround = 3, kPickLocation = 4 };
    struct PickHit {
        int kind = kPickNone;
        int id = 0;          // the prop's or actor's id; a location's doodad index (-1: its WMO)
        V3 point;
        float distance = 0;
    };
    // What the screen point (sx, sy) of a `width` x `height` view of the last camera hits first: a prop, an actor, the
    // location (a floor facing up is the ground) or the plain ground.
    PickHit pick(float sx, float sy, int width, int height) const;
    // The floor under (x, y) nearest below z (a little above counts: a step up): the location's groups and WMO props,
    // else its plain ground. False when there is none.
    bool groundAt(float x, float y, float z, float& out) const;
    void render(int width, int height, uint32_t nowMs);

private:
    struct ModelGl {
        std::unique_ptr<M2Model> model;
        unsigned vao = 0, vbo = 0, ibo = 0;
        unsigned boneTex = 0;            // identity bones, for props
        bool ok = false;
    };
    struct Attached {
        ModelGl* mesh = nullptr;
        uint32_t attachment = 0;
        std::map<uint32_t, uint32_t> textures;
    };
    struct Skinned {
        ModelGl* mesh = nullptr;
        uint32_t texture = 0;
        std::vector<int> boneMap;          // its bone -> the body's (-1: none)
        std::vector<uint16_t> geosets;
        std::vector<M4> mats;
        unsigned boneTex = 0;
    };
    // An actor's own copy of its body where the body options change it: the shape's vertices (drawn with the shared
    // model's indices) and the layer's triangles.
    struct BodyGl {
        std::vector<float> morph;
        std::vector<M2Vertex> shaped;    // the model's vertices with the shape applied, then the split's (empty: none)
        std::vector<uint32_t> indices;   // the shaped body's own triangles (bodyshape.h: the split sections' finer)
        std::vector<M2Submesh> submeshes;     // its sections' ranges in them
        bool shapeDirty = false;
        float k = 0, fold = 0;
        uint32_t design = 0;             // the design's file (dress: the type-1 layer at kBodyLayer)
        uint32_t hair = 0;               // the hair texture its colour comes from
        bool dirty = false;
        std::set<uint32_t> missing;
        unsigned vao = 0, vbo = 0, ibo = 0;   // the shaped vertices and triangles (0: the shared model's)
        unsigned layerVao = 0, layerVbo = 0, layerIbo = 0, layerTex = 0;
        size_t layerIndices = 0;
        size_t moved = 0, layerTriangles = 0;
    };
    struct Actor {
        ModelGl* mesh = nullptr;
        BodyGl body;
        std::vector<Attached> attached;
        std::map<uint32_t, uint32_t> textures;
        std::map<int, int> groups;
        std::vector<uint16_t> geosets;
        // The dressed look: its layers, what they made, and its added models.
        std::vector<SkinLayer> layers;
        std::map<uint32_t, unsigned> composed;      // texture type -> composed GL texture
        std::map<uint32_t, uint32_t> drawTextures;  // textures, with the layers' single files over them
        std::set<uint32_t> missing;                 // files the look waits for (not downloaded yet)
        bool skinDirty = false;
        std::vector<DressModel> models, pendingModels;
        std::vector<Attached> hung;
        std::vector<Skinned> skinned;
        float scale = 1;
        V3 position;
        float facing = 0;
        bool visible = true;
        int seq = -1, prevSeq = -1;
        uint16_t animId = 0, idleAnim = 0;
        bool loop = true;
        uint32_t start = 0, prevStart = 0, fadeStart = 0, fadeMs = 0;
        uint32_t animTime = 0;         // how far into `seq` this frame is (ms)
        uint32_t replays = 0;          // times the playing variation plays again before another is chosen
        uint32_t rng = 0;              // the actor's own random numbers (variations), seeded by its id
        unsigned boneTex = 0;
        // pa: this frame's local pose; pb: the pose a change of animation blends from (the last one drawn, held), for
        // fadeMs from fadeStart.
        std::vector<BonePose> pa, pb;
        bool posed = false;            // pa holds a drawn pose (something to blend from)
        std::vector<M4> mats;
        // The imported animation (setBoneAnim): its tracks' bones (-1: none here), the bones held at rest under it.
        std::shared_ptr<BoneAnim> boneAnim;
        std::vector<int> boneAnimBones;
        std::vector<uint8_t> boneAnimRest;
        uint32_t boneAnimStart = 0;
        float boneAnimScale = 1;
        std::vector<std::pair<int, float>> breath;   // a held imported pose breathes: these bones, by these shares
        SoftDraw soft;                 // the soft body (softbody.h)
    };
    struct GroupGl {
        unsigned vao = 0, vbo = 0, ibo = 0;
        V3 boundsMin, boundsMax;
        std::vector<WmoBatch> batches;
        bool outdoors = true;
    };
    struct Prop {
        ModelGl* mesh = nullptr;
        M4 world;
        V3 centre;
        float radius = 1;
        bool seen = true;
        float rgba[4] = {1, 1, 1, 1};
    };

    // ---- locations and props (location.cpp)
    struct StaticGroup {
        unsigned vao = 0, vbo = 0, ibo = 0;
        V3 boundsMin, boundsMax;          // in the world
        std::vector<WmoBatch> batches;
        bool interior = false;
        std::vector<uint8_t> verts;       // the vertex buffer's bytes (made again after the context is lost)
        std::vector<uint16_t> indices;
        std::vector<float> tris;          // its drawn triangles in the world, nine floats each (picking)
        V3 meanLight;                     // its vertices' mean precomputed light (props standing in it take it)
    };
    struct Instance {
        ModelGl* mesh = nullptr;
        M4 world;
        V3 centre;
        float radius = 1;
        float light[4] = {0, 0, 0, 1};    // precomputed light (rgb) and how much the outside's light counts (a)
    };
    struct PlacedWmo {
        uint32_t fileId = 0;
        WmoRoot root;
        M4 world;
        float scale = 1;
        std::vector<StaticGroup> groups;
        std::vector<Instance> doodads;
        V3 boundsMin, boundsMax;
        float intAmbient[3] = {0, 0, 0};
        size_t groupsMissing = 0, doodadsMissing = 0;
    };
    struct EditorProp {
        uint32_t fileId = 0;
        V3 pos;
        float yaw = 0, scale = 1;
        Instance inst;                    // an M2's
        std::unique_ptr<PlacedWmo> wmo;   // a WMO's
    };
    // The map's ground under a location (terrain.cpp): one ADT chunk each, in the location's coordinates.
    struct TerrainChunk {
        unsigned vao = 0, vbo = 0, ibo = 0, alphaTex = 0;
        std::vector<uint8_t> verts;       // 145 vertices as StaticGroup's (36 bytes: position, normal, uv in cells, MCCV)
        std::vector<uint16_t> indices;    // its cells' triangles, holes left out
        std::vector<uint8_t> alpha;       // 64 x 64 RGBA: layers 1..3's alpha
        uint32_t layers[4] = {0, 0, 0, 0};
        int layerCount = 0;
        std::vector<float> tris;          // in the location's coordinates (floors, picking)
        V3 boundsMin, boundsMax;
    };
    std::vector<TerrainChunk> terrain_;
    unsigned terrainProg_ = 0;
    void loadTerrain(const LocationDesc& d);
    void uploadTerrain(TerrainChunk& c);
    void drawTerrain(const M4& viewProj, const gl::Frustum& f);

    std::unique_ptr<PlacedWmo> location_;
    LocationDesc locDesc_;
    LocationLook locLook_;               // the entry's look with the view's over it
    std::vector<Instance> locExtras_;
    size_t locExtrasMissing_ = 0;
    std::string locError_;
    std::map<std::pair<int, int>, std::vector<float>> locFloor_;
    std::map<int, EditorProp> editorProps_;
    int nextProp_ = 1;
    int selKind_ = 0, selId_ = 0;
    unsigned locProg_ = 0, instProg_ = 0, groundProg_ = 0, instVbo_ = 0, groundVao_ = 0, groundVbo_ = 0;
    size_t instCapacity_ = 0;
    mutable LocationStats frameStats_;
    std::unordered_map<ModelGl*, unsigned> instVaos_;

    bool placeWmo(uint32_t fileId, const M4& world, float scale, const std::vector<int>& groups,
                  const std::vector<uint32_t>& groupFiles, const std::vector<int>& sets,
                  const std::vector<std::string>& setNames, PlacedWmo& out, std::string& error);
    void uploadStatic(StaticGroup& g);
    void staticGl();                     // after initGl: programs, and the buffers of what is loaded
    void forgetStaticGl();
    void rebuildLocFloor();
    void lightProp(Instance& in) const;  // inside a location's interior group: its light
    void drawStatic(bool transparent, const M4& viewProj);
    void drawGround(const M4& viewProj);
    void drawSelection(const M4& viewProj);
    // A person standing in a location's room is lit by the room (its ambient and baked light), not the sky; `restore`
    // puts the scene's light back on the m2 program.
    void lightActor(const V3& feet, bool restore);
    void drawWmoGroups(const PlacedWmo& w, bool transparent, const gl::Frustum& f, float glow);
    void drawInstances(const std::vector<const Instance*>& list, bool transparent, const float* flat, float inflate, float glow);
    float floorIn(const std::map<std::pair<int, int>, std::vector<float>>& grid, float x, float y, float z) const;

    FileLookup files_;
    bool glReady_ = false;
    SkinCompositor compositor_;
    unsigned m2Prog_ = 0, wmoProg_ = 0, skyProg_ = 0, skyVao_ = 0, skyVbo_ = 0, white_ = 0;
    std::unordered_map<uint64_t, GlTexture> textures_;
    std::unordered_map<uint32_t, std::unique_ptr<ModelGl>> models_;
    std::unordered_map<uint32_t, std::string> modelErrors_;   // why a model couldn't be read (tried again on next use)
    std::map<int, Actor> actors_;
    int nextActor_ = 1;
    // The animations' clock (ms): it moves by each frame's time, at most kMaxFrameStep, so a pause, a stall or the app
    // coming back moves nothing on by more than a frame's worth, and time never runs backwards.
    uint32_t animClock_ = 0, lastNow_ = 0, lastStep_ = 16;
    bool haveNow_ = false;

    WmoRoot city_;
    std::vector<GroupGl> groups_;
    // The city's walkable triangles (facing up), nine floats each, filed by 4-yard cell.
    std::map<std::pair<int, int>, std::vector<float>> floor_;
    std::vector<int> groupIds_;
    std::vector<Prop> props_;
    std::vector<V3> propCentres_;
    float propRadius_ = 0;
    bool cityLoaded_ = false;
    bool cityShown_ = true;
    int softLevel_ = kSoftNatural;
    bool softBodyDraw_ = false;        // drawModel is drawing an actor's body with its soft body on

    V3 eye_{0, -10, 2}, target_{0, 0, 1};
    float fov_ = 45;
    Light light_;
    M4 view_, proj_;

    GlTexture texture(uint32_t fileId, int maxSize);
    ModelGl* model(uint32_t fileId);
    void uploadModel(ModelGl& m);
    void uploadGroup(const WmoGroup& g, GroupGl& out);
    void uploadCityGl();
    void addFloor(const WmoGroup& g);
    void warmCity();
    void drawSky();
    void drawCity(bool transparent);
    void drawModel(ModelGl& m, const M4& world, const std::map<uint32_t, uint32_t>* textures,
                   const std::vector<uint16_t>* geosets, unsigned boneTex, int seq, uint32_t t,
                   uint32_t now, const float tint[4], bool transparent, int texSize,
                   const std::map<uint32_t, unsigned>* glTextures = nullptr, unsigned vao = 0,
                   const std::vector<M2Submesh>* submeshes = nullptr);
    void animate(Actor& a, uint32_t now);
    void applyBoneAnim(Actor& a, uint32_t now);
    void blendFromCurrent(Actor& a, uint32_t ms);   // the next frames blend from the pose last drawn
    void nextLoop(Actor& a);                         // a looping animation came round: again, or another variation
    void drawAttached(Actor& a, const M4& world, uint32_t now, bool transparent);
    void drawActor(Actor& a, uint32_t now, bool transparent);
    void refreshSkin(Actor& a);
    void dressModels(Actor& a);
    void dropDressGl(Actor& a);
    void dropBodyGl(Actor& a);
    void refreshBody(Actor& a);
    void drawBodyLayer(Actor& a, const M4& world);
    void stepSoft(Actor& a, uint32_t now);

};

} // namespace wc
