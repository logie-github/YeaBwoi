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

#include "bodypart.h"
#include "bodyrig.h"
#include "boneanim.h"
#include "cloth.h"
#include "evebody.h"
#include "grade.h"
#include "location.h"
#include "m2.h"
#include "modelpart.h"
#include "motionlayer.h"
#include "particles.h"
#include "postfx.h"
#include "skin.h"
#include "softbody.h"
#include "wmath.h"
#include "wmo.h"
#include "glutil.h"

namespace wc {

struct GlTexture {
    unsigned id = 0;
    bool hasAlpha = false;
    uint32_t bytes = 0;   // on the GPU, its mips included (0: the shared white)
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

    bool creatorBackdrop(uint32_t fileId);

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
    // kRaised: on the floor pose but at the given height, not stood on the floor under it: a clip raised onto a bed or a
    // seat, or off a floor its joints would reach under (the app's Pose.lift); placeActor stores it as kOnFloor.
    enum FurniturePose { kOnFloor = 0, kSitting = 1, kLying = 2, kRaised = 3 };
    void placeActor(int id, V3 position, float facing, int furniture = kOnFloor, V3 surface = {});
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
    // Whether an actor is a character (a playable race's model, the creator's or an NPC of those races): only a character
    // has a soft body; a creature (a boar, a wolf) never does, whatever its bones and skin look like. Actors start as not.
    void setCharacter(int id, bool character);
    // An actor's soft body (the desktop check reads its regions and displacements), or null.
    const SoftSim* softSim(int id) const { auto it = actors_.find(id); return it == actors_.end() ? nullptr : &it->second.soft.sim; }
    // An actor's tabard flap hung as cloth (cloth.h; the desktop check reads its points, its stretch and the body it
    // collides with), or null when it wears none. `rig`, `caps` and `skin` as last stepped.
    const ClothSim* clothSim(int id, const ClothRig** rig = nullptr, const std::vector<ClothCapsule>** caps = nullptr) const {
        auto it = actors_.find(id);
        if (it == actors_.end() || !it->second.cloth.active()) return nullptr;
        if (rig) *rig = it->second.cloth.rig.get();
        if (caps) *caps = &it->second.cloth.caps;
        return &it->second.cloth.sim;
    }
    void showActor(int id, bool visible);
    // Replaces an actor's scale (addActor's): the placement editor sizes a placed person.
    void setActorScale(int id, float scale);
    // Human scale (anim/HumanScale.kt): the actor's standing height in model units (humanscale.h: the Head joint, the mean
    // of the model's own stance and the clips' straightened one; 0 when the model can't be measured: no humanoid skeleton).
    float actorStandHeight(int id) const;
    // Human scale's root motion for the actor's imported animations: `xy` world yards per reference unit horizontally
    // (0: off, every axis by the model's own Root height as before); vertically its own pelvis, feet on its floor.
    void setHumanRootMotion(int id, float xy);
    bool actorReady(int id) const;
    // How tall an actor stands (for the camera's framing).
    float actorHeight(int id) const;
    // The box an actor's body takes standing as modelled (its vertices, scaled), around its feet.
    bool actorBounds(int id, V3& min, V3& max) const;
    // What the body options made of an actor (the desktop check): vertices the shape moved, the layer's triangles.
    bool bodyStats(int id, size_t& moved, size_t& layerTriangles) const;
    // The actor's flesh around its main bones as dressed (flesh.h: its shown geosets, its body shape, in the clips' stance;
    // FleshMeasure::pack's layout), measured once per look and kept until the look changes. False while it can't be
    // measured yet (no such actor, its shape waiting for the next frame, a skeleton that isn't a person's).
    bool actorFlesh(int id, std::vector<float>& out);

    // ---------------------------------------------------------------- the Eve body (evebody.h; evescene.cpp)
    // The converted Eve set in `dir` (files/bodyparts/eve: set.json, its meshes, skins and overlays) with the fit table
    // (assets/eve_fit.json's text), kept for the scene's life; a new call replaces it (every actor wearing Eve is fitted
    // again). False and `error` when it can't be read; the actors then keep their own bodies.
    bool loadEveSet(const std::string& dir, const std::string& fitJson, std::string& error);
    // Eve on actor `id` with these options (on false: its own body). Applied at the next frame and again whenever its look
    // changes: only on a model the fit table has a row for, enabled, and only while the caller says the look is unclothed
    // (the caller decides: bodyparts/EveOptions.kt EveRule). Kept across dress().
    void setEveBody(int id, const EveOptions& options);
    void clearEveBody(int id);
    // What became of it (the desktop check, the menus' note): drawn or why not, the fit's numbers, the colours matched.
    struct EveStatus {
        bool requested = false, active = false;
        std::string status;
        EveFitStats stats;
        uint32_t neck = 0, ankle = 0, reference = 0;
        int shade = -1;
        float shadeDist = 0;
        double composeMs = 0;
        size_t eveTriangles = 0, bodyTriangles = 0;
        double glMs = 0;           // the GL thread's share of the last fit (the snapshot and the swap; the uploads are the frame's)
        bool pending = false;      // a fit is on the worker; what is drawn is the last one (or her own body)
        uint32_t waitFrames = 0;   // frames it has waited
    };
    bool eveStatus(int id, EveStatus& out) const;
    // The fit on a worker thread (default) or at once on the GL thread (the desktop's checks; pictures always are).
    void setEveAsync(bool on);

    // ---------------------------------------------------------------- body parts (bodypart.h)
    // A part file (.wcbp) read and kept: its handle (> 0), the same one again for a path already loaded; 0 and partError()
    // when it can't be read. Parts stay loaded for the scene's life (a set is 36 small meshes).
    int loadPart(const std::string& path);
    const std::string& partError() const { return partError_; }
    // Part `part` on actor `id`, in the slot its state names (DEFAULT: 1, ON: 2; one part per slot, a new one replaces it),
    // drawn with `rgba` (w x h, top row first: the part's diffuse already tinted to the actor's skin by the caller). Rigid on
    // the actor's Pelvis bone (bodypart.h); not drawn until setPartState picks its slot. False: no such actor or part, or
    // the texture's size doesn't match its bytes.
    bool attachPart(int id, int part, const uint8_t* rgba, int w, int h);
    // Which slot is drawn: 0 none, 1 the default part, 2 the "on" part (switching is instant). Kept across dress().
    void setPartState(int id, int state);
    int partState(int id) const;
    // The race's fit (bodyparts/PartFit.kt): offsets in pelvis heights forward and up, a factor on the size.
    void setPartFit(int id, const PartFit& fit);
    // Takes every part off the actor (their textures freed).
    void detachParts(int id);
    // Where to sample the actor's skin colour for its parts' tint: the skin texture coordinates (u, v pairs, v 0 at the top)
    // of its shown lower body around the parts' root (bodySkinUvs: within 0.12 pelvis heights, at most 256). Empty: no actor,
    // or a model without a Pelvis.
    std::vector<float> partSkinUvs(int id);
    // The desktop check: the matrix a slot's part was last drawn with (model space, before the actor's world), and its
    // placement. False: nothing in that slot, or not drawn yet.
    bool partPose(int id, int slot, M4& matrix, PartPlacement& placement) const;

    // ---------------------------------------------------------------- model parts (modelpart.h)
    // A model part (a version 2 part file, loadPart) on actor `id`, rigid on the bone its file names (Head, L_Clavicle...),
    // fitted to the actor's skeleton (placeBonePart) and drawn with its own texture file and blend; any number per actor,
    // shown at once. Returns its handle on the actor (> 0); 0: no such actor or part, or the part isn't a model part.
    int attachBonePart(int id, int part, const BonePartFit& fit);
    // Shows or hides one (`handle`) or every (0) model part of the actor; changes one's fit (0: every one's).
    void setBonePartVisible(int id, int handle, bool visible);
    void setBonePartFit(int id, int handle, const BonePartFit& fit);
    // Takes every model part off the actor.
    void detachBoneParts(int id);
    // The stand-in's cut: the actor's triangles whose corners all belong to these bones' subtrees (WoW-side names, "#N"
    // a key bone id; empty: none) aren't drawn (its own head and hair under a model part's). Kept across dress().
    void setBodyCut(int id, const std::vector<std::string>& bones);
    // The stand-in's skin matched to the pieces' (set.json's skinColour): its body and skinned models drawn times `rgb`
    // (1, 1, 1: as they are). Not its attached items or parts.
    void setBodyTint(int id, float r, float g, float b);
    // Where to sample the stand-in's own skin for that match: the skin texture coordinates (u, v pairs, v 0 at the top) of
    // its shown skin (texture type 1) on its face (modelpart.h faceVertices: cut away, but its colour is the body's own).
    // Empty: can't say.
    std::vector<float> faceSkinUvs(int id) const;
    // The desktop check: the matrix a model part was last drawn with and its placement. False: none, or not drawn yet.
    bool bonePartPose(int id, int handle, M4& matrix, BonePartPlacement& placement) const;
    // The floor under (x, y) nearest below z (a little above counts: a step up), or z itself when
    // the city has no floor there.
    float floorAt(float x, float y, float z) const;

    void setCamera(V3 eye, V3 target, float fovDegrees);
    // The light as given (a stage's preset, or what a location's view made of it); with a time of day set, the light
    // drawn is this graded by the hour (setTimeOfDay).
    void setLight(const Light& l) { baseLight_ = l; light_ = l; applyTimeOfDay(); }
    const Light& light() const { return light_; }
    // The world clock's hour (0..24) on the loaded location's light (daylight.h: its dayCycle, else the default one): the
    // sky, sun or moon, ambient and fog of that hour, and its rooms' own light dimmed and warmed at night. Negative: off,
    // the light as given. Cheap: call it as often as the clock moves (a few times a second). No location: no change.
    void setTimeOfDay(float hours);
    float timeOfDay() const { return timeOfDay_; }
    // Cinematic mode's look (daylight.h's mood, grade.h's last pass): `mood` 0..1 a warm, low-key light over the hour's (the
    // location's ambient lowered and warmed, the people lit by a warm key from a low side angle and a soft rim, a slow
    // candle-like flicker) with the picture's grade; `vignette`, `grain` 0..1; `bars` each letterbox bar's share of the
    // height (0..0.5). All 0: off, the frame drawn straight to the screen as before. Cheap; the caller eases them in and out.
    void setCinematic(float mood, float vignette, float grain, float bars);
    // Cinematic mode's zone mood (daylight.h ZoneLight, cineProfile's zone part): `strength` 0..1 how far the location's
    // light, fog and grade are pulled toward the zone's own light (the game's ZoneLight/LightData for the place: Tirisfal's
    // mauve and teal gloom, Teldrassil's violet), 0 none of it; `boost` its colours exaggerated (saturation, contrast).
    // Only while the cinematic look is on (setCinematic's mood scales it). Cheap; default 0.7, no boost.
    void setZoneMood(float strength, bool boost);
    // Options' Graphics page (graphics_settings.h: the array in its order; scene_gfx.cpp) and the last frame's timings
    // (cpu ms, each pass's GPU ms, timers available, the scene's drawn width and height: PostStats::kStages + 4 floats).
    void setGraphics(const float* values, int count);
    void graphicsStats(float* out, int count) const;
    // The harness: each pass timed with glFinish round it (software GL has no timer queries).
    void setGraphicsSyncTiming(bool on) { postfx_.setSyncTiming(on); }
    // Actor `id`'s skin sheen 0..1 (wet: glossier, a little darker; the app drives it from the moment's intensity, eased).
    // 0: as before. Drawn with the skin's shading whatever Options' Skin shading is.
    void setSheen(int id, float sheen);
    // The textures loaded from the game now and the bytes they hold on the GPU (mips included): texture detail's cost.
    void textureMemory(size_t& count, size_t& bytes) const;

    // ---------------------------------------------------------------- body language and atmosphere (additive layers)
    // The body's additive motion (motionlayer.h) for everyone it is on: Options' breathing and idle shares, eye contact.
    void setMotionParams(const MotionParams& p) { motionParams_ = p; }
    // Actor `id`'s layer on, its intensity 0..1 (breathing's rate and depth; the idle motions quieten as it rises) and who
    // it looks at (another actor's id, -1 the camera, 0 nobody). Laid over its clip or emote, never replacing it.
    void setMotion(int id, float intensity, int target);
    // Its layer off (the pose as the animation alone makes it).
    void clearMotion(int id);
    // The desktop check: the layer's state (its eased intensity, look-at angles, the clip's weight on the head), or null.
    const MotionState* motionState(int id) const { auto it = actors_.find(id); return it == actors_.end() ? nullptr : &it->second.motion; }
    // The atmosphere's glowing motes (particles.h): `count` of them (0: none) at `strength` 0..1, in a box round the camera's
    // target; deterministic from `seed`.
    void setParticles(int count, float strength, uint32_t seed = 7);
    const ParticleField& particleField() const { return particles_; }

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
    bool propContact(int id, int furniture, V3& point, float& facing, float& halfDepth) const;
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
    // The part of actor `id`'s body the screen point lands on (bodypick.h's BodyRegion; 0: none): its shown triangles as
    // posed this frame, the region of the bones the triangle hit is weighted to most (bodyrig.cpp).
    int pickBodyRegion(int id, float sx, float sy, int width, int height) const;
    // The creator's sculpt sliders (sculpt.h's order, -1..1) on actor `id`: the Eve body is fitted again with them.
    void setSculpt(int id, const float* values, size_t count);
    int locationObject(uint32_t model, V3 origin) const;
    bool walkSegment(V3 from, V3 to) const;
    // The floor under (x, y) nearest below z (a little above counts: a step up): the location's groups and WMO props,
    // else its plain ground. False when there is none.
    bool groundAt(float x, float y, float z, float& out) const;
    void render(int width, int height, uint32_t nowMs);

    // ---------------------------------------------------------------- the placement editor's view (editview.cpp)
    // The gizmo: handles drawn over everything at the pivot (the feet) of the selected prop (kind 1) or actor (2), the same
    // size on the screen however near or far, along the world's axes. Move: three arrows (x red, y green, z blue) and a
    // square on the floor between x and y (free movement on the floor); Rotate: the yaw ring about z; Scale: a box at the
    // pivot (uniform) and a box on each axis. Handles are see-through but the active one (activeAxis), drawn bright.
    enum GizmoMode { kGizmoOff = 0, kGizmoMove = 1, kGizmoRotate = 2, kGizmoScale = 3 };
    enum GizmoAxis { kAxisNone = -1, kAxisX = 0, kAxisY = 1, kAxisZ = 2, kAxisYaw = 3, kAxisUniform = 4, kAxisFloor = 5 };
    void setGizmo(int mode, int kind, int id, int activeAxis);
    // The handle under the screen point (sx, sy) of a width x height view of the last camera, within slopPx pixels (the
    // nearest one), or kAxisNone.
    int gizmoHit(float sx, float sy, int width, int height, float slopPx) const;
    // The gizmo's pivot and its length in yards this frame (false: no gizmo, or its thing is gone).
    bool gizmoFrame(V3& pivot, float& length, int width, int height) const;
    // The camera as last drawn: eye, target, up, vertical field of view (degrees), aspect, and its matrices.
    struct CameraInfo { V3 eye, target, up; float fovY = 45, aspect = 1; M4 view, proj; };
    CameraInfo cameraInfo() const;
    // The loaded location's view's camera (false: no location, or the view has none).
    bool viewCamera(const std::string& view, V3& eye, V3& target, float& fov) const;

    // The ghost: what is about to be placed, drawn half see-through and tinted blue-white over the scene (depth-tested, each
    // pixel once), never picked, never saved. A prop by file id (an M2 or a WMO, its files here), or an actor already
    // added (ghostActor: it is drawn as the ghost and left out of picking until removed). One at a time: a new one replaces it.
    bool ghostProp(uint32_t fileId, V3 pos, float yaw, float scale);
    void ghostActor(int id);
    void ghostMove(V3 pos, float yaw);
    void ghostClear();            // the ghost prop is forgotten; a ghost actor stays an actor (its owner removes it)
    bool ghostBounds(V3& mn, V3& mx) const;
    int ghostActorId() const { return ghost_.actor; }

    // A thumbnail: a prop (propFile, an M2 or a WMO) or a look (an actor dressed as `look` says), alone in an off-screen
    // framebuffer of this context, framed from three quarters in front, softly lit on a neutral gradient; RGBA, size x size,
    // top row first. Nothing of the live scene is changed (its camera, light and things are put back; the temporary actor,
    // WMO and any model or texture that was loaded only for it are released). False and `error` when it can't be drawn.
    struct ThumbLook {
        uint32_t model = 0;
        float scale = 1;
        std::map<uint32_t, uint32_t> textures;   // by texture type, as addActor takes them (a look's own: none)
        std::map<int, int> geo;
        std::vector<SkinLayer> layers;
        std::vector<DressModel> extras;
        std::vector<float> morph;
        float bodyK = 0, bodyFold = 0;
        EveOptions eve;                          // the Eve body on the look (setEveBody's; `on` false: her own body)
    };
    bool thumbnail(uint32_t propFile, const ThumbLook* look, int size, std::vector<uint8_t>& rgba, std::string& error);
    // How a look's picture is framed instead (the Transmog window's cards, creator/TransmogCards.kt): the band of the body
    // from `lo` to `hi` (fractions of the body's drawn height, feet 0, crown 1) fills the picture's height, seen from
    // azimuth `az` (radians about the vertical; models face +x, 0 is straight in front) and elevation `el`; `fitWidth`
    // also keeps the band's whole width in the picture. `clear`: no backdrop, the picture's alpha is what was drawn
    // (found by drawing it over black and over white: blended edges keep their coverage), straight (not premultiplied).
    struct ThumbFrame {
        float lo = -0.02f, hi = 1.02f;
        float az = -0.62f, el = 0.10f;
        bool fitWidth = true;
        bool clear = false;
    };
    // A look's picture, `w` x `h` (16 to 512 each), framed by `frame` (null: as thumbnail frames it, square or not).
    bool thumbnail(uint32_t propFile, const ThumbLook* look, const ThumbFrame* frame, int w, int h, std::vector<uint8_t>& rgba,
                   std::string& error);

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
    // A loaded part file: its data and, once a context has it, its buffers (M2Vertex, no bone weights: rigid, the positions
    // in the Sims body's frame, partSimsPoint).
    struct PartGl {
        PartData data;
        std::string path;
        unsigned vao = 0, vbo = 0, ibo = 0;
    };
    // One slot of an actor's parts: the part and its tinted texture (kept in memory to upload again after a lost context).
    struct PartSlot {
        int part = 0;
        std::vector<uint8_t> rgba;
        int w = 0, h = 0;
        unsigned tex = 0;
        M4 drawn;                // the matrix last drawn with (model space)
        bool wasDrawn = false;
    };
    // A model part on an actor (attachBonePart): fitted to its skeleton once, drawn rigid on its bone.
    struct BonePart {
        int handle = 0, part = 0;
        BonePartFit fit;
        BonePartPlacement place;
        bool placed = false, visible = true, wasDrawn = false;
        M4 drawn;
    };
    struct ActorParts {
        std::vector<BonePart> bone;
        int nextBone = 1;
        std::vector<std::string> cut;    // setBodyCut's subtrees
        float tint[4] = {1, 1, 1, 1};    // setBodyTint
        PartSlot slots[2];
        int state = 0;           // 0 none, 1 slot 0, 2 slot 1
        PartFit fit;
        PartPlacement place;
        bool placed = false;     // place measured for the actor's model and fit
    };
    // A tabard flap hung as cloth (cloth.h): its rig for the look, the sim, this frame's inputs and what is drawn.
    struct ClothGl {
        std::unique_ptr<ClothRig> rig;   // null: no flap shown (or not built yet)
        ClothSim sim;
        bool dirty = true;               // the look or the shape changed: built again on the next frame
        std::vector<uint16_t> bodyGeosets;   // the actor's geosets without the flap's (drawn skinned as before)
        std::vector<V3> posed;
        std::vector<ClothCapsule> caps;
        ClothSurface skin;
        std::vector<M2Vertex> verts;
        std::vector<uint16_t> indices;
        unsigned vao = 0, vbo = 0, ibo = 0;
        unsigned tex = 0;                // the cloth's own texture (the look's cloth layers over the skin), 0: the body's skin
        float uvBox[4] = {0, 0, 0, 0};   // that texture's rectangle of the material (x, y, w, h as fractions)
        bool panel = false;              // it is the tabard's panel (cloth.h clothPanelRect), not the flap's own strip
        int panelPx[2] = {0, 0};         // its window's size (material pixels)
        bool active() const { return rig && rig->ok; }
    };
    struct Actor {
        ModelGl* mesh = nullptr;
        BodyGl body;
        ActorParts parts;
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
        /** Its skin not composed yet since it was added (Scene::render composes a skin or so a frame): not drawn till then. */
        bool firstSkin = true;
        std::vector<DressModel> models, pendingModels;
        std::vector<Attached> hung;
        std::vector<Skinned> skinned;
        float scale = 1;
        V3 position;
        float facing = 0;
        int furniture = kOnFloor;
        V3 surface;
        float furnitureFacing = 0;
        float lyingHalfLength = 0;
        int pelvisBone = -2, headBone = -2, chestBone = -2, leftFootBone = -2, rightFootBone = -2;
        bool visible = true;
        int seq = -1, prevSeq = -1;
        uint16_t animId = 0, idleAnim = 0;
        bool loop = true;
        uint32_t start = 0, prevStart = 0, fadeStart = 0, fadeMs = 0;
        uint32_t animTime = 0;         // how far into `seq` this frame is (ms)
        uint32_t replays = 0;          // times the playing variation plays again before another is chosen
        uint32_t rng = 0;              // the actor's own random numbers (variations), seeded by its id
        // An animation asked for whose keys aren't here yet (its .anim file is on its way): the actor goes on with what it
        // was playing (never the bind pose) and it starts the moment the file is in (looked for every kWaitRetryMs).
        int waitSeq = -1;
        uint16_t waitAnim = 0;
        bool waitLoop = false;
        uint32_t waitTry = 0;
        unsigned boneTex = 0;
        // pa: this frame's local pose; pb: the pose a change of animation blends from (the last one drawn, held), for
        // fadeMs from fadeStart.
        std::vector<BonePose> pa, pb;
        bool posed = false;            // pa holds a drawn pose (something to blend from)
        std::vector<BonePose> pl;      // what a layer (M2Model::isLayer) is played over: the idle's pose this frame
        std::vector<M4> mats;
        // The imported animation (setBoneAnim): its tracks' bones (-1: none here), the bones held at rest under it.
        std::shared_ptr<BoneAnim> boneAnim;
        std::vector<int> boneAnimBones;
        std::vector<uint8_t> boneAnimRest;
        uint32_t boneAnimStart = 0;
        float boneAnimScale = 1;
        // Human scale (setHumanRootMotion; 0: off, as the creator's and the previews' actors): a clip's horizontal root
        // motion in world yards per reference unit, the same for everyone (the pair offsets' scale), so it is divided by
        // the actor's scale; its vertical motion by the body's own pelvis in the clips' stance (boneAnimScaleZ) and the
        // body lifted by boneAnimLift (model units) where straightened legs reach under its floor (humanscale.h).
        float humanRootXY = 0;
        float boneAnimScaleZ = 1, boneAnimLift = 0;
        int boneAnimRoot = -1;
        std::vector<std::pair<int, float>> breath;   // a held imported pose breathes: these bones, by these shares
        SoftDraw soft;                 // the soft body (softbody.h)
        ClothGl cloth;                 // a tabard flap hung as cloth (cloth.h)
        bool character = false;        // setCharacter: the soft body applies (a creature's never does)
        MotionState motion;            // the additive body language (setMotion; motionlayer.h)
        bool ghost = false;            // ghostActor: drawn as the ghost, never picked
        // actorFlesh's measure and the look it was made for (0: none yet)
        std::vector<float> flesh;
        uint64_t fleshKey = 0;
        EveDraw eve;                   // the Eve body (evescene.cpp)
        std::vector<float> sculpt;     // the creator's sculpt sliders (setSculpt; sculpt.h): the Eve body fitted with them
        LegEdit legEdit;               // its Leg Length on the skeleton (bodyrig.h): made again when the slider or model changes
        float sheen = 0;               // setSheen: the skin's wet sheen 0..1
        int fxBones[5] = {-2, -2, -2, -2, -2};   // L_Eye, R_Eye, Head, L_Hand, R_Hand (-1: none) for fxModel (skinOf)
        const void* fxModel = nullptr;
    };
    struct GroupGl {
        unsigned vao = 0, vbo = 0, ibo = 0;
        V3 boundsMin, boundsMax;
        std::vector<WmoBatch> batches;
        bool outdoors = true;
    };
    ModelGl* creatorBackdrop_ = nullptr;
    void drawCreatorBackdrop(float aspect, uint32_t nowMs);

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
        uint32_t modelFileId = 0;
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
    // The person drawModel is drawing (scene_gfx.cpp skinOf, each frame before they are drawn): kLitFs's uSkinActor (their
    // sheen; 1 in w when their thin parts can be found), uEyeC (the eyeballs' centres, world; far away: none), uThinHead
    // (world -> the head's frame, its flesh's front and side reach to 1) and uThinHands (each hand's joint, 1 / its length).
    float drawSkin_[4] = {0, 0, 0, 0};
    float drawEyes_[6] = {0, 0, -1e6f, 0, 0, -1e6f};
    float drawThin_[24] = {};
    void skinOf(Actor& a, const M4& world);

    V3 eye_{0, -10, 2}, target_{0, 0, 1};
    // pickBodyRegion's bone regions, for the model they were made for
    mutable std::vector<uint8_t> boneRegions_;
    mutable const M2Model* boneRegionsModel_ = nullptr;
    float fov_ = 45;
    Light light_;
    Light baseLight_;                    // light_ before the time of day (setLight's, the location view's look over it)
    float timeOfDay_ = -1;               // the hour, negative: off
    float roomTint_[3] = {1, 1, 1};      // a room's own light (interior ambient, baked vertex light) times this
    void applyTimeOfDay();               // light_ and roomTint_ from baseLight_ and timeOfDay_ (location.cpp)
    float cineMood_ = 0;                 // setCinematic's: the mood's strength, its flicker now, the grade and its pass
    float cineFlicker_ = 0;
    GradeParams cineGrade_;
    Grade grade_;
    // the graphics options (scene_gfx.cpp): the settings, the offscreen chain, the key light's shadow pass
    GraphicsSettings gfx_;
    PostFx postfx_;
    V3 keyLightTravel() const;
    void renderShadows(uint32_t nowMs);
    bool gfxBegin(int& width, int& height, uint32_t nowMs);   // true: the scene is drawn into the chain at width x height
    void gfxEnd(int outW, int outH, uint32_t nowMs);
    // the area's cinematic profile (daylight.h cineProfile): the one shown, eased toward the place's and hour's each frame
    // (updateCinematic, location.cpp), the light before it is laid on, the vignette option, the practicals of the side the
    // camera's target stands on
    CineProfile cineNow_;
    bool cineHas_ = false;
    uint32_t cineClock_ = 0;
    float cineVignette_ = 0;
    Light dayLight_;
    float dayRoomTint_[3] = {1, 1, 1};
    CinePracticals cinePractical_;
    float zoneMood_ = 0.7f;              // setZoneMood's
    bool zoneBoost_ = false;
    void updateCinematic(uint32_t nowMs);
    // the additive layers: the body's motion (Options'), the motes and how bright, the clock they were last stepped at
    MotionParams motionParams_;
    ParticleField particles_;
    ParticleDraw particleDraw_;
    float particleStrength_ = 0;
    uint32_t particleClock_ = 0;
    void layMotion(Actor& a, uint32_t now);   // motionlayer.h over the posed matrices, before they are uploaded
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
    void alignWithFurniture(Actor& a);
    void applyBoneAnim(Actor& a, uint32_t now);
    void blendFromCurrent(Actor& a, uint32_t ms);   // the next frames blend from the pose last drawn
    void nextLoop(Actor& a);                         // a looping animation came round: again, or another variation
    void startSeq(Actor& a, int seq, uint16_t animId, bool loop);   // plays `seq` from its start, blending from the pose drawn
    void checkWaiting(int id, Actor& a, uint32_t now);              // the waiting animation's file came: it starts
    void drawAttached(Actor& a, const M4& world, uint32_t now, bool transparent);
    void drawActor(Actor& a, uint32_t now, bool transparent);
    void refreshSkin(Actor& a);
    void dressModels(Actor& a);
    void dropDressGl(Actor& a);
    void dropBodyGl(Actor& a);
    void refreshBody(Actor& a);
    void drawBodyLayer(Actor& a, const M4& world);
    void stepSoft(Actor& a, uint32_t now);
    // the tabard flap as cloth (scene.cpp): built for the look and shape, stepped after the bones, drawn after the body
    void refreshCloth(Actor& a);
    void stepCloth(Actor& a, uint32_t now);
    void drawCloth(Actor& a, const M4& world, const float tint[4]);
    void dropClothGl(Actor& a, bool texture);
    // body parts (scene.cpp)
    std::map<int, std::unique_ptr<PartGl>> parts_;
    std::map<std::string, int> partPaths_;
    int nextPart_ = 1;
    std::string partError_;
    void uploadPart(PartGl& p);
    void dropPartGl(ActorParts& ap);
    void drawParts(Actor& a, const M4& world, bool transparent);
    void drawBoneParts(Actor& a, const M4& world, bool transparent);
    // the Eve body (evescene.cpp)
    std::shared_ptr<EveSet> eveSet_;               // (shared with the worker's jobs)
    void eveCheck(Actor& a);                       // before refreshBody: the look or options changed, so fit again
    void eveRefit(Actor& a);                       // in refreshBody: the body's base kept, a fit sent
    void eveOff(Actor& a);
    bool eveOffered(Actor& a);
    void eveStart(Actor& a, bool inRefresh);       // a fit sent to the worker (or computed at once)
    void eveTake(Actor& a);                        // a finished fit swapped in
    void eveSwap(Actor& a, EveResult& r, bool inRefresh, bool staged = false);
    void eveStage(Actor& a);                       // a finished fit uploaded a slice a frame, then swapped in
    void eveDropStage(Actor& a);
    bool eveAsync_ = true;
    std::unique_ptr<EveWorker> eveWorker_;         // (declared after the models and actors: stopped before they go)
    void drawEve(Actor& a, const M4& world, const float tint[4]);
    void dropEveGl(Actor& a);

    // ---- the placement editor's view (editview.cpp)
    struct Ghost {
        bool on = false;
        uint32_t fileId = 0;
        V3 pos0, pos;                     // where it was placed (a WMO's vertices are there) and where it is drawn
        float yaw0 = 0, yaw = 0, scale = 1;
        ModelGl* mesh = nullptr;          // an M2's
        std::unique_ptr<PlacedWmo> wmo;   // a WMO's
        int actor = 0;                    // an actor's
    };
    Ghost ghost_;
    float ghostTint_[4] = {1, 1, 1, 1};   // multiplies what is drawn (the ghost's tint while it is drawn; else white)
    int gizmoMode_ = 0, gizmoKind_ = 0, gizmoId_ = 0, gizmoAxis_ = -1;
    unsigned gizmoProg_ = 0, gizmoVao_ = 0, gizmoVbo_ = 0;
    int viewW_ = 1, viewH_ = 1;           // the last frame's size
    M4 ghostDelta() const;                // a WMO ghost: from where it was placed to where it is drawn
    void drawGhost(const M4& viewProj, uint32_t now);
    void drawGizmo();
    void freeWmoGl(PlacedWmo& w);
    void litSetup(unsigned prog, const M4& viewProj);                            // scene.cpp
    void staticSetup(unsigned prog, const M4& viewProj, const float intAmbient[3], const float* roomTint = nullptr); // location.cpp
};

} // namespace wc
