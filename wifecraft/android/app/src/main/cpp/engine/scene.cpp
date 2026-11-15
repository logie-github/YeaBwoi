#include "scene.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <unordered_map>

#include "blp.h"

namespace wc {

namespace {

// The biggest texture size kept for each kind of thing (a smaller mip level otherwise).
constexpr int kCityTex = 512, kPropTex = 256, kActorTex = 1024;
// The biggest a composed skin is made (the HD models' materials are 2048 x 1024).
constexpr int kSkinTex = 2048;

const char* kM2Vs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aBones;
layout(location = 4) in vec4 aWeights;
uniform mat4 uWorld;
uniform mat4 uViewProj;
uniform highp sampler2D uBones;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
mat4 bone(float i) {
    int b = int(i + 0.5);
    return mat4(texelFetch(uBones, ivec2(0, b), 0), texelFetch(uBones, ivec2(1, b), 0),
                texelFetch(uBones, ivec2(2, b), 0), texelFetch(uBones, ivec2(3, b), 0));
}
void main() {
    float total = aWeights.x + aWeights.y + aWeights.z + aWeights.w;
    mat4 skin = total > 0.0
        ? bone(aBones.x) * aWeights.x + bone(aBones.y) * aWeights.y + bone(aBones.z) * aWeights.z + bone(aBones.w) * aWeights.w
        : mat4(1.0);
    if (total > 0.0) skin = skin * (1.0 / total);
    vec4 p = uWorld * (skin * vec4(aPos, 1.0));
    vWorld = p.xyz;
    vNormal = mat3(uWorld) * (mat3(skin) * aNormal);
    vUv = aUv;
    gl_Position = uViewProj * p;
}
)";

const char* kLitFs = R"(#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform float uAlphaRef;
uniform int uUnlit;
uniform int uOpaque;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uAmbient;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec3 uEye;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv) * uColor;
    if (c.a < uAlphaRef) discard;
    vec3 rgb = c.rgb;
    if (uUnlit == 0) {
        vec3 n = normalize(vNormal);
        float d = max(dot(n, -uSunDir), 0.0);
        rgb *= uAmbient + uSunColor * d;
    }
    float f = clamp((distance(vWorld, uEye) - uFog.x) / max(uFog.y - uFog.x, 1.0), 0.0, 1.0);
    rgb = mix(rgb, uFogColor, f);
    fragColor = vec4(rgb, uOpaque == 1 ? 1.0 : c.a);
}
)";

const char* kM2Fs = kLitFs;

const char* kWmoVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
uniform mat4 uViewProj;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
void main() {
    vWorld = aPos;
    vNormal = aNormal;
    vUv = aUv;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
)";

const char* kSkyVs = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec3 aDir;
out vec3 vDir;
void main() { vDir = aDir; gl_Position = vec4(aPos, 0.9999, 1.0); }
)";

const char* kSkyFs = R"(#version 300 es
precision highp float;
in vec3 vDir;
uniform vec3 uTop;
uniform vec3 uHorizon;
uniform vec3 uBelow;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
out vec4 fragColor;
void main() {
    vec3 d = normalize(vDir);
    vec3 c = d.z >= 0.0 ? mix(uHorizon, uTop, pow(clamp(d.z, 0.0, 1.0), 0.55))
                        : mix(uHorizon, uBelow, pow(clamp(-d.z * 3.0, 0.0, 1.0), 0.7));
    float s = max(dot(d, -uSunDir), 0.0);
    c += uSunColor * (pow(s, 64.0) * 0.6 + pow(s, 6.0) * 0.12);
    fragColor = vec4(c, 1.0);
}
)";

unsigned compile(unsigned kind, const char* src) {
    unsigned s = glCreateShader(kind);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    int ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048]; glGetShaderInfoLog(s, sizeof log, nullptr, log);
        std::fprintf(stderr, "shader: %s\n", log);
    }
    return s;
}

unsigned program(const char* vs, const char* fs) {
    unsigned p = glCreateProgram();
    unsigned v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    glAttachShader(p, v); glAttachShader(p, f);
    glLinkProgram(p);
    int ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[2048]; glGetProgramInfoLog(p, sizeof log, nullptr, log);
        std::fprintf(stderr, "program: %s\n", log);
    }
    glDeleteShader(v); glDeleteShader(f);
    return p;
}

// Uniform locations, looked up once per program and name (the names are the literals below).
std::unordered_map<uint64_t, int> gLocations;

int loc(unsigned p, const char* name) {
    uint64_t key = uint64_t(p) << 44 ^ uint64_t(reinterpret_cast<uintptr_t>(name));
    auto it = gLocations.find(key);
    if (it != gLocations.end()) return it->second;
    int l = glGetUniformLocation(p, name);
    gLocations[key] = l;
    return l;
}

// The six planes of the camera's view (ax + by + cz + d >= 0 inside), from its matrix.
struct Frustum {
    float p[6][4];
    explicit Frustum(const M4& m) {
        for (int i = 0; i < 3; ++i)
            for (int s = 0; s < 2; ++s) {
                float* q = p[i * 2 + s];
                float sign = s ? -1.0f : 1.0f;
                for (int c = 0; c < 4; ++c) q[c] = m.at(3, c) + sign * m.at(i, c);
                float n = std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
                if (n > 0) for (int c = 0; c < 4; ++c) q[c] /= n;
            }
    }
    bool sphere(const V3& c, float r) const {
        for (const auto& q : p) if (q[0] * c.x + q[1] * c.y + q[2] * c.z + q[3] < -r) return false;
        return true;
    }
    bool box(const V3& mn, const V3& mx) const {
        for (const auto& q : p) {
            V3 v(q[0] >= 0 ? mx.x : mn.x, q[1] >= 0 ? mx.y : mn.y, q[2] >= 0 ? mx.z : mn.z);
            if (q[0] * v.x + q[1] * v.y + q[2] * v.z + q[3] < 0) return false;
        }
        return true;
    }
};

// M2Vertex as the m2 program reads it, for the bound vertex buffer.
void m2VertexLayout() {
    const GLsizei st = sizeof(M2Vertex);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, st, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, st, (void*)20);
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, st, (void*)32);
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, GL_FALSE, st, (void*)16);
    glEnableVertexAttribArray(4); glVertexAttribPointer(4, 4, GL_UNSIGNED_BYTE, GL_TRUE, st, (void*)12);
}

void blendFor(uint32_t mode) {
    switch (mode) {
        case 3: glBlendFunc(GL_ONE, GL_ONE); break;
        case 4: glBlendFunc(GL_SRC_ALPHA, GL_ONE); break;
        case 5: glBlendFunc(GL_DST_COLOR, GL_ZERO); break;
        case 6: glBlendFunc(GL_DST_COLOR, GL_SRC_COLOR); break;
        default: glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); break;
    }
}

} // namespace

Scene::~Scene() { /* GL objects die with the context */ }

namespace {
// The body's GL objects are gone with the context: made again at the next frame.
template <typename B> void forgetBodyGl(B& b) {
    b.vao = b.vbo = b.layerVao = b.layerVbo = b.layerIbo = b.layerTex = 0;
    b.layerIndices = 0;
    b.dirty = !b.morph.empty() || b.design != 0;
}
} // namespace

bool Scene::initGl() {
    gLocations.clear();
    m2Prog_ = program(kM2Vs, kM2Fs);
    wmoProg_ = program(kWmoVs, kLitFs);
    skyProg_ = program(kSkyVs, kSkyFs);
    glGenVertexArrays(1, &skyVao_);
    glGenBuffers(1, &skyVbo_);
    glGenTextures(1, &white_);
    uint8_t px[4] = {255, 255, 255, 255};
    glBindTexture(GL_TEXTURE_2D, white_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    compositor_.initGl();
    glReady_ = true;
    // A context that was lost takes everything with it: build again what the scene holds.
    for (auto& [id, m] : models_) if (m->ok) uploadModel(*m);
    for (auto& [id, a] : actors_) {
        a.boneTex = 0;
        a.composed.clear();
        a.skinDirty = !a.layers.empty();
        for (Skinned& e : a.skinned) e.boneTex = 0;
        forgetBodyGl(a.body);
    }
    if (cityLoaded_) { uploadCityGl(); warmCity(); }
    return true;
}

void Scene::releaseGl() {
    glReady_ = false;
    compositor_.releaseGl();
    textures_.clear();
    for (auto& [id, m] : models_) { m->vao = m->vbo = m->ibo = m->boneTex = 0; }
    for (auto& [id, a] : actors_) {
        a.boneTex = 0;
        a.composed.clear();
        a.skinDirty = !a.layers.empty();
        for (Skinned& e : a.skinned) e.boneTex = 0;
        forgetBodyGl(a.body);
    }
    groups_.clear();
}

GlTexture Scene::texture(uint32_t fileId, int maxSize) {
    uint64_t key = uint64_t(fileId) << 16 | uint32_t(maxSize);
    auto it = textures_.find(key);
    if (it != textures_.end()) return it->second;
    GlTexture t;
    Bytes b;
    Image img;
    std::string path = fileId ? files_(fileId) : "";
    if (!path.empty() && readFile(path, b) && decodeBlp(b, maxSize, img)) {
        size_t n = size_t(img.width) * img.height;
        bool opaque = true;
        for (size_t i = 0; i < n && opaque; ++i) opaque = img.rgba[i * 4 + 3] == 255;
        glGenTextures(1, &t.id);
        glBindTexture(GL_TEXTURE_2D, t.id);
        if (opaque) {
            // Half the memory for textures without transparency: Dalaran has hundreds.
            std::vector<uint16_t> px(n);
            for (size_t i = 0; i < n; ++i) {
                const uint8_t* c = &img.rgba[i * 4];
                px[i] = uint16_t((c[0] >> 3) << 11 | (c[1] >> 2) << 5 | (c[2] >> 3));
            }
            glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB565, img.width, img.height, 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, px.data());
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, img.width, img.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, img.rgba.data());
        }
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        t.hasAlpha = img.hasAlpha;
    } else {
        t.id = white_;
    }
    textures_[key] = t;
    return t;
}

Scene::ModelGl* Scene::model(uint32_t fileId) {
    auto it = models_.find(fileId);
    if (it != models_.end()) return it->second->ok ? it->second.get() : nullptr;
    auto m = std::make_unique<ModelGl>();
    m->model = std::make_unique<M2Model>();
    m->ok = m->model->load(fileId, files_);
    if (m->ok && glReady_) uploadModel(*m);
    ModelGl* raw = m->ok ? m.get() : nullptr;
    models_[fileId] = std::move(m);
    return raw;
}

void Scene::uploadModel(ModelGl& m) {
    const M2Model& md = *m.model;
    glGenVertexArrays(1, &m.vao);
    glBindVertexArray(m.vao);
    glGenBuffers(1, &m.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, m.vbo);
    glBufferData(GL_ARRAY_BUFFER, md.vertices.size() * sizeof(M2Vertex), md.vertices.data(), GL_STATIC_DRAW);
    glGenBuffers(1, &m.ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, md.indices.size() * 4, md.indices.data(), GL_STATIC_DRAW);
    m2VertexLayout();
    glBindVertexArray(0);
    // Props are drawn as they were modelled: every bone where it was built.
    size_t n = std::max<size_t>(1, md.bones.size());
    std::vector<float> ident(n * 16, 0.0f);
    for (size_t i = 0; i < n; ++i) ident[i * 16] = ident[i * 16 + 5] = ident[i * 16 + 10] = ident[i * 16 + 15] = 1;
    glGenTextures(1, &m.boneTex);
    glBindTexture(GL_TEXTURE_2D, m.boneTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, GLsizei(n), 0, GL_RGBA, GL_FLOAT, ident.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
}

void Scene::uploadGroup(const WmoGroup& g, GroupGl& out) {
    size_t nv = g.positions.size() / 3;
    std::vector<float> v(nv * 8);
    for (size_t i = 0; i < nv; ++i) {
        std::copy(&g.positions[i * 3], &g.positions[i * 3] + 3, &v[i * 8]);
        std::copy(&g.normals[i * 3], &g.normals[i * 3] + 3, &v[i * 8 + 3]);
        std::copy(&g.uvs[i * 2], &g.uvs[i * 2] + 2, &v[i * 8 + 6]);
    }
    glGenVertexArrays(1, &out.vao);
    glBindVertexArray(out.vao);
    glGenBuffers(1, &out.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, out.vbo);
    glBufferData(GL_ARRAY_BUFFER, v.size() * 4, v.data(), GL_STATIC_DRAW);
    glGenBuffers(1, &out.ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, out.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, g.indices.size() * 2, g.indices.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 32, (void*)12);
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 32, (void*)24);
    glBindVertexArray(0);
    out.batches = g.batches;
    out.outdoors = (g.flags & 0x2000) == 0;
}

namespace {
constexpr float kFloorCell = 4.0f;
int floorCell(float v) { return int(std::floor(v / kFloorCell)); }
}

void Scene::addFloor(const WmoGroup& g) {
    const float* p = g.positions.data();
    for (size_t i = 0; i + 2 < g.indices.size(); i += 3) {
        const float* a = p + g.indices[i] * 3;
        const float* b = p + g.indices[i + 1] * 3;
        const float* c = p + g.indices[i + 2] * 3;
        V3 n = (V3(b[0], b[1], b[2]) - V3(a[0], a[1], a[2])).cross(V3(c[0], c[1], c[2]) - V3(a[0], a[1], a[2]));
        float len = n.length();
        if (len < 1e-6f || std::fabs(n.z) / len < 0.5f) continue;   // walls and overhangs
        float tri[9] = {a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]};
        int x0 = floorCell(std::min({a[0], b[0], c[0]})), x1 = floorCell(std::max({a[0], b[0], c[0]}));
        int y0 = floorCell(std::min({a[1], b[1], c[1]})), y1 = floorCell(std::max({a[1], b[1], c[1]}));
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 400) continue;              // huge sheets: skip
        for (int x = x0; x <= x1; ++x)
            for (int y = y0; y <= y1; ++y) {
                auto& v = floor_[{x, y}];
                v.insert(v.end(), tri, tri + 9);
            }
    }
}

float Scene::floorAt(float x, float y, float z) const {
    if (!cityShown_) return z;
    auto it = floor_.find({floorCell(x), floorCell(y)});
    if (it == floor_.end()) return z;
    float best = -1e9f;
    const std::vector<float>& t = it->second;
    for (size_t i = 0; i + 8 < t.size(); i += 9) {
        float ax = t[i], ay = t[i + 1], bx = t[i + 3], by = t[i + 4], cx = t[i + 6], cy = t[i + 7];
        float d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (std::fabs(d) < 1e-9f) continue;
        float u = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / d;
        float v = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / d;
        if (u < -1e-4f || v < -1e-4f || u + v > 1.0001f) continue;
        float h = u * t[i + 2] + v * t[i + 5] + (1 - u - v) * t[i + 8];
        if (h <= z + 1.2f && h > best) best = h;
    }
    return best > z - 3.0f ? best : z;
}

void Scene::uploadCityGl() {
    groups_.clear();
    floor_.clear();
    for (int gi : groupIds_) {
        if (gi < 0 || size_t(gi) >= city_.groups.size()) continue;
        Bytes b;
        std::string path = files_(city_.groups[gi].fileId);
        WmoGroup g;
        if (path.empty() || !readFile(path, b) || !g.load(b)) continue;
        addFloor(g);
        GroupGl gl;
        uploadGroup(g, gl);
        gl.boundsMin = city_.groups[gi].boundsMin;
        gl.boundsMax = city_.groups[gi].boundsMax;
        groups_.push_back(std::move(gl));
    }
}

bool Scene::loadCity(uint32_t rootFileId, const std::vector<int>& groups, const std::vector<V3>& centres, float radius) {
    clearCity();
    Bytes b;
    std::string path = files_(rootFileId);
    if (path.empty() || !readFile(path, b) || !city_.load(b)) return false;
    groupIds_ = groups;
    propCentres_ = centres;
    propRadius_ = radius;
    if (glReady_) uploadCityGl();
    // Props of the city's default set near the story's places.
    uint32_t first = 0, count = uint32_t(city_.doodads.size());
    if (!city_.sets.empty()) { first = city_.sets[0].first; count = city_.sets[0].count; }
    for (uint32_t i = first; i < first + count && i < city_.doodads.size(); ++i) {
        const WmoDoodad& d = city_.doodads[i];
        bool near = centres.empty();
        for (const V3& c : centres) if ((d.position - c).length() < radius) { near = true; break; }
        if (!near || !d.modelFileId || files_(d.modelFileId).empty()) continue;
        ModelGl* m = model(d.modelFileId);
        if (!m) continue;
        Prop p;
        p.mesh = m;
        p.world = M4::translation(d.position) * M4::rotation(d.rotation) * M4::scaling(V3(d.scale, d.scale, d.scale));
        p.centre = p.world.point((m->model->extentMin + m->model->extentMax) * 0.5f);
        p.radius = (m->model->extentMax - m->model->extentMin).length() * 0.5f * d.scale;
        props_.push_back(p);
    }
    cityLoaded_ = true;
    if (glReady_) warmCity();
    return true;
}

void Scene::warmCity() {
    for (const GroupGl& g : groups_)
        for (const WmoBatch& b : g.batches)
            if (b.material < city_.materials.size()) texture(city_.materials[b.material].texture1, kCityTex);
    for (const Prop& p : props_)
        for (const M2Texture& t : p.mesh->model->textures)
            if (t.type == 0 && t.fileId) texture(t.fileId, kPropTex);
}

void Scene::clearCity() {
    groups_.clear();
    floor_.clear();
    props_.clear();
    city_ = WmoRoot();
    cityLoaded_ = false;
}

namespace {

// Which of a character model's geosets show: the defaults (body, the first of most groups),
// then the look's choices and gear, group by group.
std::vector<uint16_t> characterGeosets(const M2Model& m, const std::map<int, int>& groups) {
    std::vector<uint16_t> out;
    for (const M2Submesh& sm : m.submeshes) {
        int id = sm.id, group = id < 100 ? 0 : id / 100, value = id < 100 ? id : id % 100;
        bool show = id == 0 || (id >= 100 && value == 1) || group == 32;
        if (group == 17 || group == 35) show = false;
        auto it = groups.find(group);
        if (id != 0 && it != groups.end()) show = value == it->second;
        if (show && std::find(out.begin(), out.end(), sm.id) == out.end()) out.push_back(sm.id);
    }
    if (out.empty()) out.push_back(0xFFFF);   // nothing to show rather than everything
    return out;
}

// A skinned model a look adds (ChrCustomizationSkinnedModel) shows only what the look asks of it:
// the geosets whose group the look sets, at the value it sets. Nothing shows by default (a horns
// collection with no horns chosen draws nothing).
std::vector<uint16_t> skinnedGeosets(const M2Model& m, const std::map<int, int>& groups) {
    std::vector<uint16_t> out;
    for (const M2Submesh& sm : m.submeshes) {
        int id = sm.id, group = id < 100 ? 0 : id / 100, value = id < 100 ? id : id % 100;
        auto it = groups.find(group);
        if (it != groups.end() && it->second == value && std::find(out.begin(), out.end(), sm.id) == out.end())
            out.push_back(sm.id);
    }
    if (out.empty()) out.push_back(0xFFFF);
    return out;
}

} // namespace

int Scene::addActor(uint32_t modelFileId, const std::map<uint32_t, uint32_t>& textures,
                    const std::map<int, int>& geosetGroups, float scale) {
    ModelGl* m = model(modelFileId);
    if (!m) return -1;
    Actor a;
    a.mesh = m;
    a.textures = textures;
    a.drawTextures = textures;
    a.groups = geosetGroups;
    if (!geosetGroups.empty()) a.geosets = characterGeosets(*m->model, geosetGroups);
    a.scale = scale;
    int id = nextActor_++;
    actors_[id] = std::move(a);
    playAnim(id, 0, true, 0);
    return id;
}

void Scene::attach(int id, uint32_t attachmentId, uint32_t modelFileId, const std::map<uint32_t, uint32_t>& textures) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    ModelGl* m = model(modelFileId);
    if (!m) return;
    it->second.attached.push_back({m, attachmentId, textures});
}

void Scene::removeActor(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    if (it->second.boneTex && glReady_) glDeleteTextures(1, &it->second.boneTex);
    dropDressGl(it->second);
    dropBodyGl(it->second);
    actors_.erase(it);
}

void Scene::dropDressGl(Actor& a) {
    if (glReady_) {
        for (auto& [type, tex] : a.composed) glDeleteTextures(1, &tex);
        for (Skinned& e : a.skinned) if (e.boneTex) glDeleteTextures(1, &e.boneTex);
    }
    a.composed.clear();
    for (Skinned& e : a.skinned) e.boneTex = 0;
}

void Scene::dress(int id, const std::map<int, int>& geosetGroups, const std::vector<SkinLayer>& layers,
                  const std::vector<DressModel>& models) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    Actor& a = it->second;
    a.groups = geosetGroups;
    a.geosets = geosetGroups.empty() ? std::vector<uint16_t>() : characterGeosets(*a.mesh->model, geosetGroups);
    // The body options' design isn't a layer of the skin: drawn over the body (setBody).
    a.layers.clear();
    uint32_t design = 0, hair = 0;
    int hairLayer = 1 << 30;
    for (const SkinLayer& l : layers) {
        if (l.textureType == 1 && l.layer == kBodyLayer) { design = l.fileId; continue; }
        if (l.textureType == 6 && l.fileId && l.layer < hairLayer) { hair = l.fileId; hairLayer = l.layer; }
        a.layers.push_back(l);
    }
    if (!hair) { auto h = a.textures.find(6); if (h != a.textures.end()) hair = h->second; }
    a.body.design = design;
    a.body.hair = hair;
    a.body.dirty = true;     // (the geosets the layer lies on may have changed too)
    a.skinDirty = true;
    a.missing.clear();
    dropDressGl(a);
    a.drawTextures = a.textures;
    a.models = models;
    dressModels(a);
}

// The look's added models: hung on their points, or skinned to the body's bones by their names.
// One whose file isn't downloaded yet waits (render tries it again once it is there).
void Scene::dressModels(Actor& a) {
    for (Skinned& e : a.skinned) if (e.boneTex && glReady_) glDeleteTextures(1, &e.boneTex);
    a.hung.clear();
    a.skinned.clear();
    a.pendingModels.clear();
    const M2Model& body = *a.mesh->model;
    std::unordered_map<uint32_t, int> byName;
    for (size_t i = 0; i < body.bones.size(); ++i) byName.emplace(body.bones[i].nameCrc, int(i));
    std::set<uint32_t> skinnedSeen;
    for (const DressModel& d : a.models) {
        if (!d.model) continue;
        // Several choices can name one collection model (horns and blindfolds share one): once.
        if (d.attachment < 0 && !skinnedSeen.insert(d.model).second) continue;
        if (files_(d.model).empty()) { a.pendingModels.push_back(d); continue; }
        ModelGl* m = model(d.model);
        if (!m) continue;
        if (d.attachment >= 0) {
            std::map<uint32_t, uint32_t> tex;
            if (d.texture) for (const M2Texture& t : m->model->textures) if (t.type) tex[t.type] = d.texture;
            a.hung.push_back({m, uint32_t(d.attachment), tex});
            continue;
        }
        Skinned e;
        e.mesh = m;
        e.texture = d.texture;
        for (const M2Bone& b : m->model->bones) {
            auto f = byName.find(b.nameCrc);
            e.boneMap.push_back(b.nameCrc && f != byName.end() ? f->second : -1);
        }
        e.geosets = skinnedGeosets(*m->model, a.groups);
        a.skinned.push_back(std::move(e));
    }
}

// Composes the look's layers, texture type by texture type. A type that is one texture as it is
// gets that file bound directly; a layer whose file isn't downloaded yet is left out until it is.
void Scene::refreshSkin(Actor& a) {
    a.skinDirty = false;
    if (glReady_) for (auto& [type, tex] : a.composed) glDeleteTextures(1, &tex);
    a.composed.clear();
    a.drawTextures = a.textures;
    a.missing.clear();
    std::map<int, std::vector<SkinLayer>> byType;
    for (const SkinLayer& l : a.layers) if (l.fileId) byType[l.textureType].push_back(l);
    for (auto& [type, layers] : byType) {
        if (directLayer(layers)) {
            if (files_(layers[0].fileId).empty()) a.missing.insert(layers[0].fileId);
            else a.drawTextures[uint32_t(type)] = layers[0].fileId;
            continue;
        }
        unsigned tex = compositor_.compose(layers, [&](uint32_t fid) -> unsigned {
            if (files_(fid).empty()) { a.missing.insert(fid); return 0; }
            GlTexture t = texture(fid, kActorTex);
            return t.id == white_ ? 0 : t.id;
        }, kSkinTex);
        if (tex) a.composed[uint32_t(type)] = tex;
    }
}

void Scene::placeActor(int id, V3 position, float facing) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    position.z = floorAt(position.x, position.y, position.z);
    it->second.position = position;
    it->second.facing = facing;
}

void Scene::showActor(int id, bool visible) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.visible = visible;
}

bool Scene::actorReady(int id) const { return actors_.count(id) > 0; }

float Scene::actorHeight(int id) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return 2;
    const M2Model& m = *it->second.mesh->model;
    return (m.boundsMax.z - m.boundsMin.z) * it->second.scale;
}

bool Scene::actorBounds(int id, V3& min, V3& max) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    const M2Model& m = *it->second.mesh->model;
    min = m.extentMin * it->second.scale;
    max = m.extentMax * it->second.scale;
    return true;
}

void Scene::setIdleAnim(int id, uint16_t animId) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.idleAnim = animId;
}

void Scene::playAnim(int id, uint16_t animId, bool loop, uint32_t nowMs) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    Actor& a = it->second;
    M2Model& m = *a.mesh->model;
    int seq = m.findSequence(animId);
    if (seq < 0) { seq = m.findSequence(a.idleAnim); loop = true; }
    if (seq < 0) seq = m.findSequence(0);
    if (seq < 0 && !m.sequences.empty()) seq = 0;
    if (seq >= 0) m.ensureSequence(seq, files_);
    if (a.seq >= 0 && seq != a.seq) {
        a.prevSeq = a.seq; a.prevStart = a.start;
        a.fadeStart = nowMs; a.fadeMs = 200;
    }
    a.seq = seq;
    a.animId = animId;
    a.loop = loop;
    a.start = nowMs;
}

void Scene::setCamera(V3 eye, V3 target, float fovDegrees) {
    eye_ = eye; target_ = target; fov_ = fovDegrees;
}

void Scene::animate(Actor& a, uint32_t now) {
    M2Model& m = *a.mesh->model;
    if (a.seq < 0 || size_t(a.seq) >= m.sequences.size()) { a.mats.assign(std::max<size_t>(1, m.bones.size()), M4()); return; }
    uint32_t dur = std::max<uint32_t>(1, m.sequences[a.seq].duration);
    uint32_t t = now - a.start;
    if (a.loop) t %= dur; else t = std::min(t, dur - 1);
    m.pose(a.seq, t, now, a.pa);
    if (a.prevSeq >= 0 && now - a.fadeStart < a.fadeMs) {
        uint32_t pd = std::max<uint32_t>(1, m.sequences[a.prevSeq].duration);
        m.pose(a.prevSeq, (now - a.prevStart) % pd, now, a.pb);
        float f = float(now - a.fadeStart) / float(a.fadeMs);
        for (size_t i = 0; i < a.pa.size() && i < a.pb.size(); ++i) {
            a.pa[i].t = lerp(a.pb[i].t, a.pa[i].t, f);
            a.pa[i].r = nlerp(a.pb[i].r, a.pa[i].r, f);
            a.pa[i].s = lerp(a.pb[i].s, a.pa[i].s, f);
        }
    }
    m.bonesToMatrices(a.pa, a.mats);
    if (a.mats.empty()) a.mats.push_back(M4());
    if (!a.boneTex) {
        glGenTextures(1, &a.boneTex);
        glBindTexture(GL_TEXTURE_2D, a.boneTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, GLsizei(a.mats.size()), 0, GL_RGBA, GL_FLOAT, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    glBindTexture(GL_TEXTURE_2D, a.boneTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, GLsizei(a.mats.size()), GL_RGBA, GL_FLOAT, a.mats.data());
}

void Scene::drawSky() {
    // A ray for each corner of the screen; the sky's colour follows how high each one points.
    V3 f = (target_ - eye_).normalized();
    V3 s = f.cross(V3(0, 0, 1)).normalized();
    V3 u = s.cross(f);
    float th = std::tan(fov_ * 3.14159265f / 360.0f);
    GLint vp[4];
    glGetIntegerv(GL_VIEWPORT, vp);
    float aspect = vp[3] ? float(vp[2]) / float(vp[3]) : 1;
    float corners[4][2] = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    float data[4][5];
    for (int i = 0; i < 4; ++i) {
        V3 d = f + s * (corners[i][0] * th * aspect) + u * (corners[i][1] * th);
        data[i][0] = corners[i][0]; data[i][1] = corners[i][1];
        data[i][2] = d.x; data[i][3] = d.y; data[i][4] = d.z;
    }
    glUseProgram(skyProg_);
    glBindVertexArray(skyVao_);
    glBindBuffer(GL_ARRAY_BUFFER, skyVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof data, data, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 20, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 20, (void*)8);
    glUniform3fv(loc(skyProg_, "uTop"), 1, light_.skyTop);
    glUniform3fv(loc(skyProg_, "uHorizon"), 1, light_.skyHorizon);
    glUniform3fv(loc(skyProg_, "uBelow"), 1, light_.skyBelow);
    V3 sd = V3(light_.sunDir[0], light_.sunDir[1], light_.sunDir[2]).normalized();
    float sdv[3] = {sd.x, sd.y, sd.z};
    glUniform3fv(loc(skyProg_, "uSunDir"), 1, sdv);
    glUniform3fv(loc(skyProg_, "uSunColor"), 1, light_.sunColor);
    glDisable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
    glBindVertexArray(0);
}

namespace {

void litUniforms(unsigned p, const Light& l, const V3& eye, const M4& viewProj) {
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uViewProj"), 1, GL_FALSE, viewProj.m);
    V3 sd = V3(l.sunDir[0], l.sunDir[1], l.sunDir[2]).normalized();
    float sdv[3] = {sd.x, sd.y, sd.z};
    glUniform3fv(loc(p, "uSunDir"), 1, sdv);
    glUniform3fv(loc(p, "uSunColor"), 1, l.sunColor);
    glUniform3fv(loc(p, "uAmbient"), 1, l.ambient);
    glUniform3fv(loc(p, "uFogColor"), 1, l.fogColor);
    glUniform2f(loc(p, "uFog"), l.fogStart, l.fogEnd);
    float e[3] = {eye.x, eye.y, eye.z};
    glUniform3fv(loc(p, "uEye"), 1, e);
    glUniform1i(loc(p, "uTex"), 0);
}

} // namespace

void Scene::drawCity(bool transparent) {
    if (groups_.empty() || !cityShown_) return;
    Frustum f(proj_ * view_);
    unsigned p = wmoProg_;
    glUseProgram(p);
    float white[4] = {1, 1, 1, 1};
    glUniform4fv(loc(p, "uColor"), 1, white);
    for (const GroupGl& g : groups_) {
        if (!f.box(g.boundsMin, g.boundsMax)) continue;
        glBindVertexArray(g.vao);
        for (const WmoBatch& b : g.batches) {
            if (!b.indexCount || b.material >= city_.materials.size()) continue;
            const WmoMaterial& m = city_.materials[b.material];
            bool blend = m.blend >= 2;
            if (blend != transparent) continue;
            GlTexture t = texture(m.texture1, kCityTex);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, t.id);
            glUniform1f(loc(p, "uAlphaRef"), m.blend == 1 ? 0.5f : -1.0f);
            glUniform1i(loc(p, "uOpaque"), m.blend == 0 ? 1 : 0);
            glUniform1i(loc(p, "uUnlit"), (m.flags & 1) ? 1 : 0);
            if (blend) { glEnable(GL_BLEND); blendFor(m.blend); glDepthMask(GL_FALSE); }
            glDrawElements(GL_TRIANGLES, GLsizei(b.indexCount), GL_UNSIGNED_SHORT, (void*)(size_t(b.indexStart) * 2));
            if (blend) { glDisable(GL_BLEND); glDepthMask(GL_TRUE); }
        }
    }
    glBindVertexArray(0);
}

void Scene::drawModel(ModelGl& mg, const M4& world, const std::map<uint32_t, uint32_t>* overrides,
                      const std::vector<uint16_t>* geosets, unsigned boneTex, int seq, uint32_t t,
                      uint32_t now, const float tint[4], bool transparent, int texSize,
                      const std::map<uint32_t, unsigned>* glTextures, unsigned vao) {
    const M2Model& m = *mg.model;
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uWorld"), 1, GL_FALSE, world.m);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, boneTex);
    glUniform1i(loc(p, "uBones"), 1);
    glBindVertexArray(vao ? vao : mg.vao);
    for (const M2Batch& b : m.batches) {
        if (b.submesh >= m.submeshes.size()) continue;
        const M2Submesh& sm = m.submeshes[b.submesh];
        if (!sm.indexCount) continue;
        if (geosets && !geosets->empty() && std::find(geosets->begin(), geosets->end(), sm.id) == geosets->end()) continue;
        M2Material mat = b.material < m.materials.size() ? m.materials[b.material] : M2Material();
        bool blend = mat.blend >= 2;
        if (blend != transparent) continue;
        if (b.textureCombo >= m.textureCombos.size()) continue;
        uint16_t ti = m.textureCombos[b.textureCombo];
        if (ti >= m.textures.size()) continue;
        const M2Texture& tex = m.textures[ti];
        uint32_t fid = tex.fileId;
        unsigned glId = 0;
        if (tex.type != 0) {
            fid = 0;
            if (glTextures) { auto it = glTextures->find(tex.type); if (it != glTextures->end()) glId = it->second; }
            if (!glId && overrides) { auto it = overrides->find(tex.type); if (it != overrides->end()) fid = it->second; }
        }
        if (!fid && !glId) continue;
        float c[4];
        m.evalColor(b.colorIndex == 0xFFFF ? -1 : int(b.colorIndex), seq, t, now, c);
        c[3] *= m.evalWeight(b.weightCombo == 0xFFFF ? -1 : int(b.weightCombo), seq, t, now);
        for (int i = 0; i < 4; ++i) c[i] *= tint[i];
        if (c[3] <= 0.004f) continue;
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, glId ? glId : texture(fid, texSize).id);
        glUniform4fv(loc(p, "uColor"), 1, c);
        glUniform1f(loc(p, "uAlphaRef"), mat.blend == 1 ? 0.5f : (mat.blend == 0 ? -1.0f : 0.004f));
        glUniform1i(loc(p, "uOpaque"), mat.blend == 0 ? 1 : 0);
        glUniform1i(loc(p, "uUnlit"), (mat.flags & 1) ? 1 : 0);
        if (blend) { glEnable(GL_BLEND); blendFor(mat.blend); glDepthMask(GL_FALSE); }
        if (mat.flags & 16) glDepthMask(GL_FALSE);
        glDrawElements(GL_TRIANGLES, GLsizei(sm.indexCount), GL_UNSIGNED_INT, (void*)(size_t(sm.indexStart) * 4));
        if (blend) glDisable(GL_BLEND);
        glDepthMask(GL_TRUE);
    }
    glBindVertexArray(0);
}

void Scene::drawAttached(Actor& a, const M4& world, uint32_t now, bool transparent) {
    const M2Model& m = *a.mesh->model;
    float one[4] = {1, 1, 1, 1};
    for (int list = 0; list < 2; ++list)
    for (Attached& at : list ? a.hung : a.attached) {
        if (at.attachment >= m.attachmentLookup.size()) continue;
        int idx = m.attachmentLookup[at.attachment];
        if (idx < 0 || size_t(idx) >= m.attachments.size()) continue;
        const M2Attachment& p = m.attachments[idx];
        M4 bone = p.bone < a.mats.size() ? a.mats[p.bone] : M4();
        M4 w = world * bone * M4::translation(p.position);
        // An item's own textures are named by type; every replaceable slot takes the item's.
        drawModel(*at.mesh, w, &at.textures, nullptr, at.mesh->boneTex, -1, 0, now, one, transparent, kActorTex);
    }
}

void Scene::drawActor(Actor& a, uint32_t nowMs, bool transparent) {
    float one[4] = {1, 1, 1, 1};
    M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    uint32_t dur = std::max<uint32_t>(1, a.seq >= 0 ? a.mesh->model->sequences[a.seq].duration : 1);
    drawModel(*a.mesh, w, &a.drawTextures, &a.geosets, a.boneTex, a.seq, (nowMs - a.start) % dur, nowMs, one,
              transparent, kActorTex, &a.composed, a.body.vao);
    if (transparent) drawBodyLayer(a, w);
    for (Skinned& e : a.skinned) {
        std::map<uint32_t, uint32_t> tex = a.drawTextures;
        if (e.texture)
            for (const M2Texture& t : e.mesh->model->textures)
                if (t.type && !tex.count(t.type) && !a.composed.count(t.type)) tex[t.type] = e.texture;
        drawModel(*e.mesh, w, &tex, &e.geosets, e.boneTex, -1, 0, nowMs, one, transparent, kActorTex, &a.composed);
    }
    drawAttached(a, w, nowMs, transparent);
}

void Scene::render(int width, int height, uint32_t nowMs) {
    if (!glReady_) return;
    // Looks dressed since the last frame, and files a look was waiting for that have come.
    for (auto& [id, a] : actors_) {
        if (!a.skinDirty && !a.missing.empty())
            for (uint32_t f : a.missing) if (!files_(f).empty()) { a.skinDirty = true; break; }
        if (a.skinDirty) refreshSkin(a);
        if (!a.body.dirty && !a.body.missing.empty())
            for (uint32_t f : a.body.missing) if (!files_(f).empty()) { a.body.dirty = true; break; }
        if (a.body.dirty) refreshBody(a);
        bool arrived = false;
        for (const DressModel& d : a.pendingModels) if (!files_(d.model).empty()) { arrived = true; break; }
        if (arrived) dressModels(a);
    }
    glViewport(0, 0, width, height);
    glClearColor(light_.skyHorizon[0], light_.skyHorizon[1], light_.skyHorizon[2], 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);

    view_ = M4::lookAt(eye_, target_, V3(0, 0, 1));
    proj_ = M4::perspective(fov_ * 3.14159265f / 180.0f, height ? float(width) / float(height) : 1, 0.2f, 4000.0f);
    M4 viewProj = proj_ * view_;

    drawSky();

    // Actors' skeletons for this frame (non-looping animations return to the idle one).
    for (auto& [id, a] : actors_) {
        if (!a.visible) continue;
        M2Model& m = *a.mesh->model;
        if (!a.loop && a.seq >= 0 && size_t(a.seq) < m.sequences.size() &&
            nowMs - a.start >= std::max<uint32_t>(1, m.sequences[a.seq].duration))
            playAnim(id, a.idleAnim, true, nowMs);
        animate(a, nowMs);
        // Skinned models move with the body's bones.
        for (Skinned& e : a.skinned) {
            e.mats.resize(std::max<size_t>(1, e.boneMap.size()));
            for (size_t i = 0; i < e.boneMap.size(); ++i)
                e.mats[i] = e.boneMap[i] >= 0 && size_t(e.boneMap[i]) < a.mats.size() ? a.mats[size_t(e.boneMap[i])] : M4();
            if (!e.boneTex) {
                glGenTextures(1, &e.boneTex);
                glBindTexture(GL_TEXTURE_2D, e.boneTex);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, GLsizei(e.mats.size()), 0, GL_RGBA, GL_FLOAT, nullptr);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            }
            glBindTexture(GL_TEXTURE_2D, e.boneTex);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, GLsizei(e.mats.size()), GL_RGBA, GL_FLOAT, e.mats.data());
        }
    }

    litUniforms(wmoProg_, light_, eye_, viewProj);
    drawCity(false);
    litUniforms(m2Prog_, light_, eye_, viewProj);
    float one[4] = {1, 1, 1, 1};
    Frustum frustum(viewProj);
    for (Prop& pr : props_) pr.seen = cityShown_ && frustum.sphere(pr.centre, pr.radius) && (pr.centre - eye_).length() - pr.radius < light_.fogEnd;
    for (Prop& pr : props_) if (pr.seen) drawModel(*pr.mesh, pr.world, nullptr, nullptr, pr.mesh->boneTex, -1, 0, nowMs, one, false, kPropTex);
    for (auto& [id, a] : actors_) if (a.visible) drawActor(a, nowMs, false);
    litUniforms(wmoProg_, light_, eye_, viewProj);
    drawCity(true);
    litUniforms(m2Prog_, light_, eye_, viewProj);
    for (Prop& pr : props_) if (pr.seen) drawModel(*pr.mesh, pr.world, nullptr, nullptr, pr.mesh->boneTex, -1, 0, nowMs, one, true, kPropTex);
    for (auto& [id, a] : actors_) if (a.visible) drawActor(a, nowMs, true);
}

// ------------------------------------------------------------------------------------------------ Wrath++'s body options

void Scene::setBody(int id, const std::vector<float>& morph, float k, float fold) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    BodyGl& b = it->second.body;
    if (morph != b.morph) { b.morph = morph; b.shapeDirty = true; }
    b.k = k;
    b.fold = fold;
    b.dirty = true;
}

bool Scene::bodyStats(int id, size_t& moved, size_t& layerTriangles) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    moved = it->second.body.moved;
    layerTriangles = it->second.body.layerTriangles;
    return true;
}

void Scene::dropBodyGl(Actor& a) {
    BodyGl& b = a.body;
    if (glReady_) {
        unsigned buffers[3] = {b.vbo, b.layerVbo, b.layerIbo};
        glDeleteBuffers(3, buffers);
        unsigned arrays[2] = {b.vao, b.layerVao};
        glDeleteVertexArrays(2, arrays);
        if (b.layerTex) glDeleteTextures(1, &b.layerTex);
    }
    b.vao = b.vbo = b.layerVao = b.layerVbo = b.layerIbo = b.layerTex = 0;
    b.layerIndices = 0;
}

namespace {

// The mean colour of a texture's opaque pixels (the hair's, for the layer), 0..1.
V3 meanColour(const Image& img) {
    double r = 0, g = 0, bl = 0, n = 0;
    size_t count = size_t(img.width) * img.height;
    for (int pass = 0; pass < 2 && n == 0; ++pass)
        for (size_t i = 0; i < count; ++i) {
            const uint8_t* c = &img.rgba[i * 4];
            if (pass == 0 && c[3] < 128) continue;
            r += c[0]; g += c[1]; bl += c[2]; n += 1;
        }
    if (n == 0) return V3(0.5f, 0.5f, 0.5f);
    return V3(float(r / n / 255), float(g / n / 255), float(bl / n / 255));
}

} // namespace

// The shape's vertices (once per shape) and the layer's triangles and texture (per look: the geosets it lies on, the hair).
void Scene::refreshBody(Actor& a) {
    BodyGl& b = a.body;
    b.dirty = false;
    b.missing.clear();
    if (!glReady_) { b.dirty = true; return; }
    dropBodyGl(a);
    const M2Model& m = *a.mesh->model;
    if (b.shapeDirty) {
        b.shaped.clear();
        b.moved = 0;
        if (b.morph.size() >= kBodyMorphStride) {
            b.shaped = m.vertices;
            BodyMorphStats st;
            b.moved = applyBodyMorph(b.shaped, b.morph.data(), b.morph.size() / kBodyMorphStride, &st);
            std::fprintf(stderr, "body shape: %zu of %zu vertices moved (%zu at a looks vertex, %zu between, %zu texture coordinates)\n",
                         b.moved, b.shaped.size(), st.exact, st.blended, st.uvs);
            if (!b.moved) b.shaped.clear();
        }
        b.shapeDirty = false;
    }
    if (!b.shaped.empty() && a.mesh->ibo) {
        glGenVertexArrays(1, &b.vao);
        glBindVertexArray(b.vao);
        glGenBuffers(1, &b.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, b.vbo);
        glBufferData(GL_ARRAY_BUFFER, b.shaped.size() * sizeof(M2Vertex), b.shaped.data(), GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, a.mesh->ibo);
        m2VertexLayout();
        glBindVertexArray(0);
    }

    // The layer (layer.py): the skin's triangles shown, facing the front, under the square; lifted off the skin.
    b.layerTriangles = 0;
    if (!b.design || b.k <= 0) return;
    if (files_(b.design).empty()) { b.missing.insert(b.design); return; }
    if (b.hair && files_(b.hair).empty()) { b.missing.insert(b.hair); return; }
    const std::vector<M2Vertex>& verts = b.shaped.empty() ? m.vertices : b.shaped;
    const float span = 0.21f * b.k, top = b.fold + 0.16f * b.k, bottom = top - span;
    constexpr float kLift = 0.0004f, kMinNx = 0.12f;
    std::set<uint16_t> sections;
    for (const M2Batch& bt : m.batches) {
        if (bt.submesh >= m.submeshes.size() || bt.textureCombo >= m.textureCombos.size()) continue;
        uint16_t ti = m.textureCombos[bt.textureCombo];
        if (ti >= m.textures.size() || m.textures[ti].type != 1) continue;
        if (bt.material < m.materials.size() && m.materials[bt.material].blend >= 2) continue;
        uint16_t id = m.submeshes[bt.submesh].id;
        if (!a.geosets.empty() && std::find(a.geosets.begin(), a.geosets.end(), id) == a.geosets.end()) continue;
        sections.insert(bt.submesh);
    }
    std::vector<M2Vertex> lv;
    std::vector<uint32_t> li;
    std::unordered_map<uint32_t, uint32_t> place;
    for (uint16_t si : sections) {
        const M2Submesh& sm = m.submeshes[si];
        for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount && t + 2 < m.indices.size(); t += 3) {
            uint32_t ix[3] = {m.indices[t], m.indices[t + 1], m.indices[t + 2]};
            if (ix[0] >= verts.size() || ix[1] >= verts.size() || ix[2] >= verts.size()) continue;
            V3 p[3];
            for (int k = 0; k < 3; ++k) p[k] = V3(verts[ix[k]].pos[0], verts[ix[k]].pos[1], verts[ix[k]].pos[2]);
            float zmax = std::max({p[0].z, p[1].z, p[2].z}), zmin = std::min({p[0].z, p[1].z, p[2].z});
            float ymin = std::min({std::fabs(p[0].y), std::fabs(p[1].y), std::fabs(p[2].y)});
            if (zmax < bottom || zmin > top || ymin > span / 2) continue;
            V3 fn = (p[1] - p[0]).cross(p[2] - p[0]).normalized();
            if (fn.x < kMinNx || (p[0].x + p[1].x + p[2].x) < 0) continue;
            for (uint32_t g : ix) {
                auto f = place.find(g);
                if (f == place.end()) {
                    M2Vertex v = verts[g];
                    V3 n = V3(v.normal[0], v.normal[1], v.normal[2]).normalized();
                    V3 q = V3(v.pos[0], v.pos[1], v.pos[2]) + n * kLift;
                    v.pos[0] = q.x; v.pos[1] = q.y; v.pos[2] = q.z;
                    v.uv[0] = (q.y + span / 2) / span;     // her right to her left, seen from the front
                    v.uv[1] = (top - q.z) / span;
                    f = place.emplace(g, uint32_t(lv.size())).first;
                    lv.push_back(v);
                }
                li.push_back(f->second);
            }
        }
    }
    if (li.empty()) return;
    // The design (grey: how dark the hair is drawn there; alpha: coverage x density) times the hair's colour.
    Bytes file;
    Image design;
    if (!readFile(files_(b.design), file) || !decodeBlp(file, 512, design)) return;
    V3 hair(0.35f, 0.25f, 0.18f);
    Image hairImg;
    Bytes hairFile;
    if (b.hair && readFile(files_(b.hair), hairFile) && decodeBlp(hairFile, 64, hairImg)) hair = meanColour(hairImg);
    std::vector<uint8_t> px(design.rgba.size());
    for (size_t i = 0; i + 3 < design.rgba.size(); i += 4) {
        const uint8_t* c = &design.rgba[i];
        px[i] = uint8_t(std::min(255.0f, c[0] * hair.x));
        px[i + 1] = uint8_t(std::min(255.0f, c[1] * hair.y));
        px[i + 2] = uint8_t(std::min(255.0f, c[2] * hair.z));
        px[i + 3] = c[3];
    }
    glGenTextures(1, &b.layerTex);
    glBindTexture(GL_TEXTURE_2D, b.layerTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, design.width, design.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glGenerateMipmap(GL_TEXTURE_2D);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenVertexArrays(1, &b.layerVao);
    glBindVertexArray(b.layerVao);
    glGenBuffers(1, &b.layerVbo);
    glBindBuffer(GL_ARRAY_BUFFER, b.layerVbo);
    glBufferData(GL_ARRAY_BUFFER, lv.size() * sizeof(M2Vertex), lv.data(), GL_STATIC_DRAW);
    glGenBuffers(1, &b.layerIbo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, b.layerIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, li.size() * 4, li.data(), GL_STATIC_DRAW);
    m2VertexLayout();
    glBindVertexArray(0);
    b.layerIndices = li.size();
    b.layerTriangles = li.size() / 3;
}

// The layer, alpha blended over the body (Wrath++'s: material blend 2), with the body's bones.
void Scene::drawBodyLayer(Actor& a, const M4& world) {
    BodyGl& b = a.body;
    if (!b.layerVao || !b.layerIndices || !b.layerTex) return;
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uWorld"), 1, GL_FALSE, world.m);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, a.boneTex);
    glUniform1i(loc(p, "uBones"), 1);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, b.layerTex);
    float one[4] = {1, 1, 1, 1};
    glUniform4fv(loc(p, "uColor"), 1, one);
    glUniform1f(loc(p, "uAlphaRef"), 0.004f);
    glUniform1i(loc(p, "uOpaque"), 0);
    glUniform1i(loc(p, "uUnlit"), 0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glBindVertexArray(b.layerVao);
    glDrawElements(GL_TRIANGLES, GLsizei(b.layerIndices), GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
}

} // namespace wc
