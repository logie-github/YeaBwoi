// Props: Sims 4 object models (toys, dolls, handcuffs, a bed, the cum mesh) drawn as rigid, lit, textured meshes at a matrix the
// screen gives each frame. bodyparts-side Kotlin converts a Sims object into a ".wcprop" file (props/PropFile.kt documents the
// layout; parseProp here keeps in step with it): meshes in Warcraft axes (x forward, y left, z up; metres), a flat colour or a
// texture each, and the bone names of the object's own rig (not used: a prop is drawn rigid at its root's matrix).
//
// parseProp is GL-free (engine-test/prop_test runs it). PropDraw uploads a prop on its first draw and draws every instance of it
// with one small program: a hemisphere light and a sun from above and behind the camera, the texture over the colour, two-sided
// (the models are thin: handcuff links, a vibrator's shell). Nothing is drawn for an id whose model was not loaded.
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "wmath.h"

namespace wc {

constexpr uint32_t kPropMagic = 0x52504357;   // "WCPR"
constexpr uint32_t kPropVersion = 1;

struct PropMeshData {
    std::vector<float> pos, nrm, uv;     // 3, 3 (maybe empty), 2 (maybe empty) per vertex
    std::vector<uint32_t> idx;
    float color[4] = {0.78f, 0.78f, 0.8f, 1.0f};
    int texture = -1;
    size_t vertexCount() const { return pos.size() / 3; }
};

struct PropTextureData {
    uint32_t w = 0, h = 0;
    std::vector<uint8_t> rgba;           // row 0 at the top (v 0)
};

struct PropData {
    uint64_t guid = 0;
    float bounds[6] = {0, 0, 0, 0, 0, 0};   // min x, y, z, max x, y, z
    std::vector<std::string> bones;
    std::vector<PropMeshData> meshes;
    std::vector<PropTextureData> textures;
    size_t triangleCount() const { size_t n = 0; for (const auto& m : meshes) n += m.idx.size() / 3; return n; }
};

// Reads a prop file; false and `error` when it is not one this build reads or its counts, checksum or indices are bad.
bool parseProp(const uint8_t* data, size_t size, PropData& out, std::string& error);

// One drawn copy of a prop: its id and where (a column-major model matrix: the prop's Warcraft axes to the scene's) and a tint.
struct PropInstance {
    uint64_t guid = 0;
    M4 model;
    float tint[4] = {1, 1, 1, 1};
};

class PropDraw {
public:
    // Keeps a parsed prop under its guid (replacing one); false and `error` when `data` is not a prop file.
    bool load(uint64_t guid, const uint8_t* data, size_t size, std::string& error);
    bool has(uint64_t guid) const { return props_.count(guid) != 0; }
    void unload(uint64_t guid);
    size_t count() const { return props_.size(); }
    const PropData* data(uint64_t guid) const { auto it = props_.find(guid); return it == props_.end() ? nullptr : &it->second.data; }
    // Draws `list` (an id with no model is left out); the caller has set depth testing and the viewport.
    void draw(const std::vector<PropInstance>& list, const M4& viewProj, const V3& eye);
    void release();   // the GL objects forgotten (the context is gone); the parsed props are kept and upload again

private:
    struct GlMesh { unsigned vao = 0, vbo = 0, ibo = 0; int count = 0; };
    struct Loaded {
        PropData data;
        std::vector<GlMesh> gl;
        std::vector<unsigned> tex;
        bool uploaded = false;
    };
    std::map<uint64_t, Loaded> props_;
    unsigned prog_ = 0;
    unsigned white_ = 0;
    void upload(Loaded& p);
    void free(Loaded& p);
};

} // namespace wc
