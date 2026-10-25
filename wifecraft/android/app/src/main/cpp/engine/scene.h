// The 3D scene behind the story: Dalaran's buildings and props, the people on stage, the sky,
// the light and the camera, drawn with OpenGL ES 3.0. Everything is in WoW's own coordinates
// (z up, yards), the city's at its own origin.
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "m2.h"
#include "wmath.h"
#include "wmo.h"

namespace wc {

struct GlTexture {
    unsigned id = 0;
    bool hasAlpha = false;
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

    // A person. `geosetGroups` (group -> value) makes the model a character race's: its default
    // geosets, then each listed group showing only that value (0: none). Empty: every geoset.
    int addActor(uint32_t modelFileId, const std::map<uint32_t, uint32_t>& textures,
                 const std::map<int, int>& geosetGroups, float scale);
    // A model hung on one of an actor's attachment points (shoulders, a helm, a weapon).
    void attach(int id, uint32_t attachmentId, uint32_t modelFileId, const std::map<uint32_t, uint32_t>& textures);
    void removeActor(int id);
    void placeActor(int id, V3 position, float facing);
    void playAnim(int id, uint16_t animId, bool loop, uint32_t nowMs);
    void setIdleAnim(int id, uint16_t animId);
    void showActor(int id, bool visible);
    bool actorReady(int id) const;
    // How tall an actor stands (for the camera's framing).
    float actorHeight(int id) const;

    void setCamera(V3 eye, V3 target, float fovDegrees);
    void setLight(const Light& l) { light_ = l; }
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
    struct Actor {
        ModelGl* mesh = nullptr;
        std::vector<Attached> attached;
        std::map<uint32_t, uint32_t> textures;
        std::vector<uint16_t> geosets;
        float scale = 1;
        V3 position;
        float facing = 0;
        bool visible = true;
        int seq = -1, prevSeq = -1;
        uint16_t animId = 0, idleAnim = 0;
        bool loop = true;
        uint32_t start = 0, prevStart = 0, fadeStart = 0, fadeMs = 0;
        unsigned boneTex = 0;
        std::vector<BonePose> pa, pb;
        std::vector<M4> mats;
    };
    struct GroupGl {
        unsigned vao = 0, vbo = 0, ibo = 0;
        std::vector<WmoBatch> batches;
        bool outdoors = true;
    };
    struct Prop {
        ModelGl* mesh = nullptr;
        M4 world;
        float rgba[4] = {1, 1, 1, 1};
    };

    FileLookup files_;
    bool glReady_ = false;
    unsigned m2Prog_ = 0, wmoProg_ = 0, skyProg_ = 0, skyVao_ = 0, skyVbo_ = 0, white_ = 0;
    std::unordered_map<uint64_t, GlTexture> textures_;
    std::unordered_map<uint32_t, std::unique_ptr<ModelGl>> models_;
    std::map<int, Actor> actors_;
    int nextActor_ = 1;

    WmoRoot city_;
    std::vector<GroupGl> groups_;
    std::vector<int> groupIds_;
    std::vector<Prop> props_;
    std::vector<V3> propCentres_;
    float propRadius_ = 0;
    bool cityLoaded_ = false;

    V3 eye_{0, -10, 2}, target_{0, 0, 1};
    float fov_ = 45;
    Light light_;
    M4 view_, proj_;

    GlTexture texture(uint32_t fileId, int maxSize);
    ModelGl* model(uint32_t fileId);
    void uploadModel(ModelGl& m);
    void uploadGroup(const WmoGroup& g, GroupGl& out);
    void uploadCityGl();
    void warmCity();
    void drawSky();
    void drawCity(bool transparent);
    void drawModel(ModelGl& m, const M4& world, const std::map<uint32_t, uint32_t>* textures,
                   const std::vector<uint16_t>* geosets, unsigned boneTex, int seq, uint32_t t,
                   uint32_t now, const float tint[4], bool transparent, int texSize);
    void animate(Actor& a, uint32_t now);
    void drawAttached(Actor& a, const M4& world, uint32_t now, bool transparent);
};

} // namespace wc
