// Locations (location.h) and the editor's props: a WMO placed in the world with its doodads, drawn as one static chunk
// (its groups' buffers in world coordinates, culled group by group; every static M2 drawn instanced, one draw per model
// and batch for all its placements), lit inside by the WMO's own vertex light and outside by the scene's sun, fading into
// the fog at the area's edge (nothing past it is drawn), standing on a plain ground; the floors under a point, and what a
// screen point hits.
#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <set>

#include "glutil.h"
#include "scene.h"

namespace wc {

using gl::Frustum;
using gl::loc;

namespace {

constexpr int kLocTex = 512;          // the biggest texture size kept for the location's walls and doodads
constexpr float kMocvScale = 2.0f;    // WMO vertex light: 0x7F is the light as lit (the game's colours are halved)
constexpr float kFloorCellL = 4.0f;
constexpr uint32_t kAntiportal = 0x4000000;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

int cellOf(float v) { return int(std::floor(v / kFloorCellL)); }

// The location's walls: positions and normals in the world, uv, the precomputed light (rgb) and how much the outside's
// light counts (a).
const char* kLocVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aLight;
uniform mat4 uViewProj;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
out vec4 vLight;
void main() {
    vWorld = aPos;
    vNormal = aNormal;
    vUv = aUv;
    vLight = vec4(aLight.rgb * 2.0, aLight.a);
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
)";

// A static M2, instanced: each placement's matrix (four columns) and light. uInflate pushes it out along its normals
// (the selection's outline).
const char* kInstVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 5) in vec4 iCol0;
layout(location = 6) in vec4 iCol1;
layout(location = 7) in vec4 iCol2;
layout(location = 8) in vec4 iCol3;
layout(location = 9) in vec4 iLight;
uniform mat4 uViewProj;
uniform float uInflate;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
out vec4 vLight;
void main() {
    mat4 w = mat4(iCol0, iCol1, iCol2, iCol3);
    vec3 n = normalize(mat3(w) * aNormal);
    vec4 p = w * vec4(aPos, 1.0);
    p.xyz += n * uInflate;
    vWorld = p.xyz;
    vNormal = n;
    vUv = aUv;
    vLight = iLight;
    gl_Position = uViewProj * p;
}
)";

// Inside: the interior's ambient and the precomputed light; outside: the sky's ambient (more from above, less from below)
// and the sun; between them by vLight.a. Past the fog's start everything fades into its colour, smoothly, and past its
// end nothing is drawn: the area's edge.
const char* kStaticFs = R"(#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
in vec4 vLight;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform float uAlphaRef;
uniform int uUnlit;
uniform int uOpaque;
uniform int uExtLight;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uAmbient;
uniform vec3 uIntAmbient;
uniform vec3 uRoomTint;   // the time of day on a room's own light (1 by day; darker and warmer at night)
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec3 uEye;
uniform vec4 uFlat;
uniform float uGlow;
uniform vec4 uGhost;   // the placement editor's ghost: its colour mixed toward rgb by a (a 0: none)
out vec4 fragColor;
void main() {
    float d = distance(vWorld, uEye);
    if (d > uFog.y) discard;
    vec4 c = texture(uTex, vUv) * uColor;
    if (c.a < uAlphaRef) discard;
    if (uFlat.a > 0.0) { fragColor = vec4(uFlat.rgb, 1.0); return; }
    vec3 rgb = c.rgb;
    if (uUnlit == 0) {
        vec3 n = normalize(vNormal);
        if (dot(n, uEye - vWorld) < 0.0) n = -n;       // seen from behind (two-sided): lit as its side facing us
        float ndl = max(dot(n, -uSunDir), 0.0);
        vec3 sky = uAmbient * (n.z >= 0.0 ? mix(1.0, 1.1, n.z) : mix(1.0, 0.7, -n.z));
        vec3 outside = sky + uSunColor * ndl + vLight.rgb * 0.5 * uRoomTint;
        vec3 inside = (uIntAmbient + vLight.rgb) * uRoomTint;
        float blend = uExtLight == 1 ? 1.0 : clamp(vLight.a, 0.0, 1.0);
        rgb *= mix(inside, outside, blend);
    }
    rgb += vec3(1.0, 0.85, 0.45) * uGlow;
    // the fog is the outside's: a room seen through its door from outside is only touched by it near its end
    float f = smoothstep(uFog.x, uFog.y, d);
    f = mix(f * f * f, f, uExtLight == 1 ? 1.0 : clamp(vLight.a, 0.0, 1.0));
    rgb = mix(rgb, uFogColor, f);
    rgb = mix(rgb, uGhost.rgb, uGhost.a);
    fragColor = vec4(rgb, uOpaque == 1 ? 1.0 : c.a);
}
)";

// The plain ground under an exterior: its colour in the sky's light, into the fog.
const char* kGroundVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
out vec3 vWorld;
void main() { vWorld = aPos; gl_Position = uViewProj * vec4(aPos, 1.0); }
)";

const char* kGroundFs = R"(#version 300 es
precision highp float;
in vec3 vWorld;
uniform vec3 uGround;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uAmbient;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec3 uEye;
out vec4 fragColor;
void main() {
    float d = distance(vWorld, uEye);
    if (d > uFog.y) discard;
    vec3 rgb = uGround * (uAmbient * 1.1 + uSunColor * max(-uSunDir.z, 0.0));
    rgb = mix(rgb, uFogColor, smoothstep(uFog.x, uFog.y, d));
    fragColor = vec4(rgb, 1.0);
}
)";

// ---- reading the entry

bool vec3(const Json& j, V3& out) {
    if (j.kind != Json::Array || j.size() < 3) return false;
    out = V3(float(j[0].num()), float(j[1].num()), float(j[2].num()));
    return true;
}

bool quat(const Json& j, Quat& out) {
    if (j.kind != Json::Array || j.size() < 4) return false;
    out = Quat(float(j[0].num()), float(j[1].num()), float(j[2].num()), float(j[3].num())).normalized();
    return true;
}

bool colour(const Json& j, float out[3]) {
    if (j.kind != Json::Array || j.size() < 3) return false;
    float c[3] = {float(j[0].num()), float(j[1].num()), float(j[2].num())};
    bool bytes = c[0] > 1.5f || c[1] > 1.5f || c[2] > 1.5f;
    for (int i = 0; i < 3; ++i) out[i] = bytes ? c[i] / 255.0f : c[i];
    return true;
}

const Json& first(const Json& o, std::initializer_list<const char*> keys) {
    for (const char* k : keys) if (o.has(k)) return o[k];
    return o["\x01"];   // null
}

void readLook(const Json& o, LocationLook& look) {
    const Json& light = o["light"];
    if (light.kind == Json::Array && light.size() >= 23) {
        look.hasLight = true;
        for (int i = 0; i < 23; ++i) look.light[i] = float(light[size_t(i)].num());
        if (light.size() >= 26) {
            look.hasInterior = true;
            for (int i = 0; i < 3; ++i) look.interiorAmbient[i] = float(light[size_t(23 + i)].num());
        }
    } else if (light.kind == Json::Object && light["vertexColours"].kind == Json::Bool && light["vertexColours"].b) {
        // a room's own light (the WMO's ambient over its baked vertex colours): the interior's, not the sky's; what is
        // seen through its door keeps the map's light
        float c[3];
        if (colour(first(light, {"ambient", "interiorAmbient"}), c)) {
            look.hasInterior = true;
            std::copy(c, c + 3, look.interiorAmbient);
        }
    } else if (light.kind == Json::Object) {
        look.hasLight = true;
        float c[3];
        auto put = [&](int at, std::initializer_list<const char*> keys, bool isColour) {
            const Json& v = first(light, keys);
            if (isColour ? colour(v, c) : (v.kind == Json::Array && v.size() >= 3)) {
                for (int i = 0; i < 3; ++i) look.light[at + i] = isColour ? c[i] : float(v[size_t(i)].num());
            }
        };
        put(0, {"skyTop", "top", "sky"}, true);
        put(3, {"skyHorizon", "horizon"}, true);
        put(6, {"skyBelow", "below"}, true);
        put(9, {"sunDir", "sunDirection", "direction"}, false);
        put(12, {"sunColor", "sunColour", "direct", "directColor", "directColour"}, true);
        put(15, {"ambient", "ambientColor", "ambientColour"}, true);
        if (colour(first(light, {"interiorAmbient", "interior", "indoorAmbient"}), c)) {
            look.hasInterior = true;
            std::copy(c, c + 3, look.interiorAmbient);
        }
    }
    const Json& fog = o["fog"];
    if (fog.kind == Json::Object) {
        look.hasFog = true;
        colour(first(fog, {"colour", "color"}), look.fogColor);
        look.fogStart = float(fog["start"].num(0));
        look.fogEnd = float(fog["end"].num(look.fogStart + 1));
        if (look.fogEnd <= look.fogStart) look.fogEnd = look.fogStart + 1;
    }
    const Json& ground = o["ground"];
    if (ground.kind == Json::Bool) { look.hasGround = true; look.ground = ground.b; }
    else if (ground.kind == Json::Object) {
        look.hasGround = true;
        look.ground = !(ground.has("show") && ground["show"].kind == Json::Bool && !ground["show"].b);
        if (ground.has("z")) { look.groundZSet = true; look.groundZ = float(ground["z"].num()); }
        if (colour(first(ground, {"colour", "color"}), look.groundColor)) look.groundColorSet = true;
    }
}

// `top` over `base`: what it gives replaces base's.
LocationLook overlay(const LocationLook& base, const LocationLook& top) {
    LocationLook out = base;
    if (top.hasLight) {
        out.hasLight = true;
        for (int i = 0; i < 23; ++i) if (!std::isnan(top.light[i])) out.light[i] = top.light[i];
    }
    if (top.hasInterior) { out.hasInterior = true; std::copy(top.interiorAmbient, top.interiorAmbient + 3, out.interiorAmbient); }
    if (top.hasFog) {
        out.hasFog = true;
        std::copy(top.fogColor, top.fogColor + 3, out.fogColor);
        out.fogStart = top.fogStart; out.fogEnd = top.fogEnd;
    }
    if (top.hasGround) {
        out.hasGround = true;
        out.ground = top.ground;
        if (top.groundZSet) { out.groundZSet = true; out.groundZ = top.groundZ; }
        if (top.groundColorSet) { out.groundColorSet = true; std::copy(top.groundColor, top.groundColor + 3, out.groundColor); }
    }
    return out;
}

void unsetLight(LocationLook& l) { for (float& v : l.light) v = kNaN; }

// The inverse of an affine matrix (rotation, scale, translation).
M4 invertAffine(const M4& m) {
    float a = m.at(0, 0), b = m.at(0, 1), c = m.at(0, 2), d = m.at(1, 0), e = m.at(1, 1), f = m.at(1, 2);
    float g = m.at(2, 0), h = m.at(2, 1), i = m.at(2, 2);
    float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    float det = a * A + b * B + c * C;
    M4 r;
    if (std::fabs(det) < 1e-12f) return r;
    float s = 1.0f / det;
    r.at(0, 0) = A * s; r.at(0, 1) = -(b * i - c * h) * s; r.at(0, 2) = (b * f - c * e) * s;
    r.at(1, 0) = B * s; r.at(1, 1) = (a * i - c * g) * s;  r.at(1, 2) = -(a * f - c * d) * s;
    r.at(2, 0) = C * s; r.at(2, 1) = -(a * h - b * g) * s; r.at(2, 2) = (a * e - b * d) * s;
    V3 t(m.at(0, 3), m.at(1, 3), m.at(2, 3));
    for (int row = 0; row < 3; ++row)
        r.at(row, 3) = -(r.at(row, 0) * t.x + r.at(row, 1) * t.y + r.at(row, 2) * t.z);
    return r;
}

V3 linear(const M4& m, const V3& v) {
    return {m.at(0, 0) * v.x + m.at(0, 1) * v.y + m.at(0, 2) * v.z,
            m.at(1, 0) * v.x + m.at(1, 1) * v.y + m.at(1, 2) * v.z,
            m.at(2, 0) * v.x + m.at(2, 1) * v.y + m.at(2, 2) * v.z};
}

// Möller–Trumbore: the ray's parameter at the triangle, or false.
bool rayTri(const V3& o, const V3& d, const V3& a, const V3& b, const V3& c, float& t) {
    V3 e1 = b - a, e2 = c - a, p = d.cross(e2);
    float det = e1.dot(p);
    if (std::fabs(det) < 1e-12f) return false;
    float inv = 1.0f / det;
    V3 s = o - a;
    float u = s.dot(p) * inv;
    if (u < 0 || u > 1) return false;
    V3 q = s.cross(e1);
    float v = d.dot(q) * inv;
    if (v < 0 || u + v > 1) return false;
    t = e2.dot(q) * inv;
    return t > 1e-4f;
}

// The ray's entry into a box (its parameter), or false.
bool rayBox(const V3& o, const V3& d, const V3& mn, const V3& mx, float& t) {
    float t0 = 0, t1 = 1e30f;
    const float oo[3] = {o.x, o.y, o.z}, dd[3] = {d.x, d.y, d.z}, lo[3] = {mn.x, mn.y, mn.z}, hi[3] = {mx.x, mx.y, mx.z};
    for (int k = 0; k < 3; ++k) {
        if (std::fabs(dd[k]) < 1e-12f) { if (oo[k] < lo[k] || oo[k] > hi[k]) return false; continue; }
        float a = (lo[k] - oo[k]) / dd[k], b = (hi[k] - oo[k]) / dd[k];
        if (a > b) std::swap(a, b);
        t0 = std::max(t0, a); t1 = std::min(t1, b);
        if (t0 > t1) return false;
    }
    t = t0;
    return true;
}

float boxDistance(const V3& p, const V3& mn, const V3& mx) {
    float dx = std::max({mn.x - p.x, 0.0f, p.x - mx.x});
    float dy = std::max({mn.y - p.y, 0.0f, p.y - mx.y});
    float dz = std::max({mn.z - p.z, 0.0f, p.z - mx.z});
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

bool inside(const V3& p, const V3& mn, const V3& mx) {
    return p.x >= mn.x && p.y >= mn.y && p.z >= mn.z && p.x <= mx.x && p.y <= mx.y && p.z <= mx.z;
}

// An M2's nearest triangle along a ray given in the model's own space.
bool rayModel(const M2Model& m, const V3& o, const V3& d, float& best) {
    float tb;
    if (!rayBox(o, d, m.extentMin - V3(0.01f, 0.01f, 0.01f), m.extentMax + V3(0.01f, 0.01f, 0.01f), tb)) return false;
    bool hit = false;
    for (size_t i = 0; i + 2 < m.indices.size(); i += 3) {
        const float* a = m.vertices[m.indices[i]].pos;
        const float* b = m.vertices[m.indices[i + 1]].pos;
        const float* c = m.vertices[m.indices[i + 2]].pos;
        float t;
        if (rayTri(o, d, V3(a[0], a[1], a[2]), V3(b[0], b[1], b[2]), V3(c[0], c[1], c[2]), t) && t < best) { best = t; hit = true; }
    }
    return hit;
}

} // namespace

// ------------------------------------------------------------------------------------------------ the entry

bool parseLocation(const std::string& json, const std::string& id, LocationDesc& out, std::string& error) {
    Json root;
    std::string why;
    if (!Json::parse(json, root, &why)) { error = "the location's JSON can't be read: " + why; return false; }
    const Json* e = &root;
    if (!root.has("wmo")) {
        const Json& list = root["locations"];
        e = nullptr;
        if (list.kind == Json::Array) {
            for (const Json& x : list.a) if (id.empty() || x["id"].str() == id) { e = &x; break; }
        } else if (list.kind == Json::Object) {
            e = id.empty() ? (list.o.empty() ? nullptr : &list.o.begin()->second) : (list.has(id) ? &list[id] : nullptr);
        } else if (!id.empty() && root.has(id)) {
            e = &root[id];
        }
        if (!e) { error = "no location \"" + id + "\" in the file"; return false; }
    }
    const Json& wmo = (*e)["wmo"];
    out = LocationDesc();
    out.id = (*e)["id"].str(id);
    out.name = (*e)["name"].str(out.id);
    out.root = uint32_t(wmo.kind == Json::Object ? wmo["root"].num() : wmo.num());
    if (!out.root) {
        error = "the location \"" + out.id + "\" has no building";
        return false;
    }
    const Json& groupList = wmo.has("groupIndices") ? wmo["groupIndices"] : wmo["groups"];
    for (const Json& g : groupList.a) {
        double v = g.kind == Json::Object ? first(g, {"index", "group"}).num(-1) : g.num(-1);
        if (g.kind == Json::Object && g.has("fileId") && !g.has("index")) out.groupFiles.push_back(uint32_t(g["fileId"].num()));
        else if (v >= 100000) out.groupFiles.push_back(uint32_t(v));
        else if (v >= 0) out.groups.push_back(int(v));
    }
    for (const Json& s : first(wmo, {"doodadSets", "sets", "doodadSet"}).kind == Json::Array
                             ? first(wmo, {"doodadSets", "sets", "doodadSet"}).a : std::vector<Json>{first(wmo, {"doodadSets", "sets", "doodadSet"})}) {
        if (s.kind == Json::String) out.setNames.push_back(s.s);
        else if (s.kind == Json::Number) out.sets.push_back(int(s.n));
        else if (s.kind == Json::Object) {
            if (s.has("index")) out.sets.push_back(int(s["index"].num()));
            else if (s.has("name")) out.setNames.push_back(s["name"].str());
        }
    }
    vec3(first(wmo, {"position", "pos"}), out.position);
    quat(first(wmo, {"rotation", "rot"}), out.rotation);
    out.scale = float(wmo["scale"].num(1));
    if (out.scale <= 0) out.scale = 1;
    for (const Json& d : (*e)["doodads"].a) {
        LocationPlacement p;
        p.model = uint32_t(first(d, {"model", "fileId", "file"}).num());
        if (!p.model || d["set"].num(-1) >= 0) continue;   // the WMO's own: drawn from its doodad sets
        vec3(first(d, {"pos", "position"}), p.position);
        quat(first(d, {"rot", "rotation"}), p.rotation);
        p.scale = float(d["scale"].num(1));
        out.doodads.push_back(p);
    }
    const Json& frame = (*e)["frame"];
    vec3(frame["origin"], out.frameOrigin);
    out.frameYawDeg = float(frame["yawDeg"].num(0));
    const Json& terrain = (*e)["terrain"];
    if (terrain.kind == Json::Object) {
        out.terrainRoot = uint32_t(terrain["root"].num());
        out.terrainTex0 = uint32_t(terrain["tex0"].num());
        for (const Json& c : terrain["chunks"].a)
            if (c.size() >= 2) out.terrainChunks.emplace_back(int(c[0].num()), int(c[1].num()));
    }
    unsetLight(out.look);
    readLook(*e, out.look);
    parseDayCycle((*e)["dayCycle"], out.dayCycle);
    parseCineArea((*e)["cinematic"], out.cinematic);
    for (const auto& [name, v] : (*e)["views"].o) {
        LocationView view;
        unsetLight(view.look);
        const Json& cam = first(v, {"camera", "cam"});
        if (cam.kind != Json::Object && v.has("eye")) {
            view.hasCamera = vec3(v["eye"], view.eye) && vec3(first(v, {"at", "target", "lookAt"}), view.target);
            view.fov = float(v["fov"].num(50));
        } else if (cam.kind == Json::Object) {
            view.hasCamera = vec3(cam["eye"], view.eye) && vec3(first(cam, {"at", "target", "lookAt"}), view.target);
            view.fov = float(cam["fov"].num(50));
        }
        const Json& sp = v.has("spawn") ? v["spawn"] : (*e)["spawns"][name];
        if (sp.kind == Json::Object) {
            view.hasSpawn = vec3(first(sp, {"pos", "position"}), view.spawn);
            view.facing = float(sp["facing"].num(0));
        } else {
            view.hasSpawn = vec3(sp, view.spawn);
            if (sp.size() >= 4) view.facing = float(sp[3].num());
        }
        readLook(v, view.look);
        out.views[name] = view;
    }
    return true;
}

void applyLook(const LocationLook& look, Light& l) {
    float* dst[] = {l.skyTop, l.skyHorizon, l.skyBelow, l.sunDir, l.sunColor, l.ambient, l.fogColor};
    if (look.hasLight) {
        for (int i = 0; i < 7; ++i)
            for (int k = 0; k < 3; ++k) if (!std::isnan(look.light[i * 3 + k])) dst[i][k] = look.light[i * 3 + k];
        if (!std::isnan(look.light[21])) l.fogStart = look.light[21];
        if (!std::isnan(look.light[22])) l.fogEnd = look.light[22];
    }
    if (look.hasFog) {
        std::copy(look.fogColor, look.fogColor + 3, l.fogColor);
        l.fogStart = look.fogStart;
        l.fogEnd = look.fogEnd;
        // the area's edge: the sky at and below the horizon is the fog, so the last of the world fades into it
        std::copy(look.fogColor, look.fogColor + 3, l.skyHorizon);
        std::copy(look.fogColor, look.fogColor + 3, l.skyBelow);
    }
}

// ------------------------------------------------------------------------------------------------ GL

void Scene::staticGl() {
    locProg_ = gl::program(kLocVs, kStaticFs);
    instProg_ = gl::program(kInstVs, kStaticFs);
    groundProg_ = gl::program(kGroundVs, kGroundFs);
    glGenBuffers(1, &instVbo_);
    instCapacity_ = 0;
    glGenVertexArrays(1, &groundVao_);
    glGenBuffers(1, &groundVbo_);
    instVaos_.clear();
    if (location_) for (StaticGroup& g : location_->groups) uploadStatic(g);
    for (auto& [id, p] : editorProps_) if (p.wmo) for (StaticGroup& g : p.wmo->groups) uploadStatic(g);
    for (TerrainChunk& c : terrain_) uploadTerrain(c);
}

void Scene::forgetStaticGl() {
    locProg_ = instProg_ = groundProg_ = instVbo_ = groundVao_ = groundVbo_ = 0;
    instCapacity_ = 0;
    instVaos_.clear();
    if (location_) for (StaticGroup& g : location_->groups) g.vao = g.vbo = g.ibo = 0;
    for (auto& [id, p] : editorProps_) if (p.wmo) for (StaticGroup& g : p.wmo->groups) g.vao = g.vbo = g.ibo = 0;
    for (TerrainChunk& c : terrain_) c.vao = c.vbo = c.ibo = c.alphaTex = 0;
    terrainProg_ = 0;
}

void Scene::uploadStatic(StaticGroup& g) {
    if (!glReady_ || g.verts.empty()) return;
    glGenVertexArrays(1, &g.vao);
    glBindVertexArray(g.vao);
    glGenBuffers(1, &g.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(g.verts.size()), g.verts.data(), GL_STATIC_DRAW);
    glGenBuffers(1, &g.ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(g.indices.size() * 2), g.indices.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 36, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 36, (void*)12);
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 36, (void*)24);
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, GL_TRUE, 36, (void*)32);
    glBindVertexArray(0);
}

// ------------------------------------------------------------------------------------------------ placing a WMO

bool Scene::placeWmo(uint32_t fileId, const M4& world, float scale, const std::vector<int>& groupsIn,
                     const std::vector<uint32_t>& groupFiles, const std::vector<int>& setsIn,
                     const std::vector<std::string>& setNames, PlacedWmo& out, std::string& error) {
    Bytes b;
    std::string path = files_(fileId);
    if (path.empty()) { error = "the WMO's root file " + std::to_string(fileId) + " isn't downloaded"; return false; }
    if (!readFile(path, b) || !out.root.load(b)) { error = "the WMO's root file " + std::to_string(fileId) + " can't be read"; return false; }
    out.fileId = fileId;
    out.world = world;
    out.scale = scale;
    const WmoRoot& r = out.root;
    uint32_t amb = r.ambient;
    out.intAmbient[0] = float((amb >> 16) & 255) / 255.0f;
    out.intAmbient[1] = float((amb >> 8) & 255) / 255.0f;
    out.intAmbient[2] = float(amb & 255) / 255.0f;

    std::vector<int> groups = groupsIn;
    for (uint32_t f : groupFiles)
        for (size_t i = 0; i < r.groups.size(); ++i) if (r.groups[i].fileId == f) groups.push_back(int(i));
    if (groups.empty())
        for (size_t i = 0; i < r.groups.size(); ++i) if (!(r.groups[i].flags & kAntiportal)) groups.push_back(int(i));
    std::sort(groups.begin(), groups.end());
    groups.erase(std::unique(groups.begin(), groups.end()), groups.end());

    out.boundsMin = V3(1e30f, 1e30f, 1e30f);
    out.boundsMax = V3(-1e30f, -1e30f, -1e30f);
    // its rotation alone, for normals (the scale is uniform)
    M4 turn = world;
    for (int k = 0; k < 3; ++k) turn.at(k, 3) = 0;
    std::vector<int> loadedGroups;
    for (int gi : groups) {
        if (gi < 0 || size_t(gi) >= r.groups.size()) continue;
        Bytes gb;
        std::string gp = files_(r.groups[size_t(gi)].fileId);
        WmoGroup g;
        if (gp.empty() || !readFile(gp, gb) || !g.load(gb)) { ++out.groupsMissing; continue; }
        StaticGroup s;
        s.interior = (g.flags & 0x2000) != 0 && (g.flags & 0x8) == 0;
        bool exterior = (g.flags & 0x8) != 0;
        size_t nv = g.positions.size() / 3;
        // the vertex light's alpha, as the game fixes it (CMapObjGroup::FixColorVertexAlpha): the "trans" batches' vertices
        // keep theirs (inside to outside), the rest are inside (0) or outside (255) by the group
        std::vector<uint8_t> col(nv * 4, 0);
        size_t secondFix = 0;
        if (g.transBatches && g.transBatches <= g.batches.size()) secondFix = size_t(g.batches[g.transBatches - 1].lastVertex) + 1;
        bool fix = !(r.flags & 0x8);
        int ar = int((amb >> 16) & 255), ag = int((amb >> 8) & 255), ab = int(amb & 255);
        V3 sum;
        for (size_t i = 0; i < nv; ++i) {
            uint8_t* c = &col[i * 4];
            if (!g.colors.empty()) {
                int B = g.colors[i * 4], G = g.colors[i * 4 + 1], R = g.colors[i * 4 + 2], A = g.colors[i * 4 + 3];
                if (fix) {
                    if (i < secondFix) {
                        float a = float(A) / 255.0f;
                        R = std::max(0, R - ar); G = std::max(0, G - ag); B = std::max(0, B - ab);
                        R = int((R - a * R) / 2); G = int((G - a * G) / 2); B = int((B - a * B) / 2);
                    } else {
                        R = std::clamp((R * A / 64 + R - ar) / 2, 0, 255);
                        G = std::clamp((G * A / 64 + G - ag) / 2, 0, 255);
                        B = std::clamp((B * A / 64 + B - ab) / 2, 0, 255);
                        A = exterior ? 255 : 0;
                    }
                } else if (i >= secondFix) {
                    A = exterior ? 255 : 0;
                }
                c[0] = uint8_t(R); c[1] = uint8_t(G); c[2] = uint8_t(B); c[3] = uint8_t(A);
            } else {
                c[3] = s.interior ? 0 : 255;
            }
            sum = sum + V3(c[0], c[1], c[2]);
        }
        s.meanLight = nv ? sum * (kMocvScale / (255.0f * float(nv))) : V3();
        s.verts.resize(nv * 36);
        s.boundsMin = V3(1e30f, 1e30f, 1e30f);
        s.boundsMax = V3(-1e30f, -1e30f, -1e30f);
        std::vector<V3> wp(nv);
        for (size_t i = 0; i < nv; ++i) {
            V3 p = world.point(V3(g.positions[i * 3], g.positions[i * 3 + 1], g.positions[i * 3 + 2]));
            V3 n = linear(turn, V3(g.normals[i * 3], g.normals[i * 3 + 1], g.normals[i * 3 + 2])).normalized();
            wp[i] = p;
            float v[8] = {p.x, p.y, p.z, n.x, n.y, n.z, g.uvs[i * 2], g.uvs[i * 2 + 1]};
            std::memcpy(&s.verts[i * 36], v, 32);
            std::memcpy(&s.verts[i * 36 + 32], &col[i * 4], 4);
            s.boundsMin = V3(std::min(s.boundsMin.x, p.x), std::min(s.boundsMin.y, p.y), std::min(s.boundsMin.z, p.z));
            s.boundsMax = V3(std::max(s.boundsMax.x, p.x), std::max(s.boundsMax.y, p.y), std::max(s.boundsMax.z, p.z));
        }
        s.indices = g.indices;
        s.batches = g.batches;
        for (const WmoBatch& bt : g.batches)
            for (uint32_t k = bt.indexStart; k + 2 < bt.indexStart + bt.indexCount && k + 2 < g.indices.size(); k += 3)
                for (int q = 0; q < 3; ++q) {
                    const V3& p = wp[g.indices[k + q]];
                    s.tris.insert(s.tris.end(), {p.x, p.y, p.z});
                }
        if (nv) {
            out.boundsMin = V3(std::min(out.boundsMin.x, s.boundsMin.x), std::min(out.boundsMin.y, s.boundsMin.y), std::min(out.boundsMin.z, s.boundsMin.z));
            out.boundsMax = V3(std::max(out.boundsMax.x, s.boundsMax.x), std::max(out.boundsMax.y, s.boundsMax.y), std::max(out.boundsMax.z, s.boundsMax.z));
        }
        uploadStatic(s);
        for (const WmoBatch& bt : s.batches)
            if (bt.material < r.materials.size() && glReady_) texture(r.materials[bt.material].texture1, kLocTex);
        out.groups.push_back(std::move(s));
        loadedGroups.push_back(gi);
    }
    if (out.groups.empty()) { error = "none of the WMO's groups could be read (are they downloaded?)"; return false; }

    // its doodads: the default set and the chosen ones
    std::vector<int> sets = setsIn;
    sets.push_back(0);
    for (const std::string& n : setNames)
        for (size_t i = 0; i < r.sets.size(); ++i) if (r.sets[i].name == n) sets.push_back(int(i));
    std::sort(sets.begin(), sets.end());
    sets.erase(std::unique(sets.begin(), sets.end()), sets.end());
    for (int si : sets) {
        uint32_t firstD = 0, count = 0;
        if (r.sets.empty()) { if (si == 0) count = uint32_t(r.doodads.size()); }
        else if (si >= 0 && size_t(si) < r.sets.size()) { firstD = r.sets[size_t(si)].first; count = r.sets[size_t(si)].count; }
        for (uint32_t i = firstD; i < firstD + count && i < r.doodads.size(); ++i) {
            const WmoDoodad& d = r.doodads[i];
            if (!d.modelFileId) continue;
            ModelGl* m = files_(d.modelFileId).empty() ? nullptr : model(d.modelFileId);
            if (!m) { ++out.doodadsMissing; continue; }
            Instance in;
            in.modelFileId = d.modelFileId;
            in.mesh = m;
            in.world = world * M4::translation(d.position) * M4::rotation(d.rotation) * M4::scaling(V3(d.scale, d.scale, d.scale));
            in.centre = in.world.point((m->model->extentMin + m->model->extentMax) * 0.5f);
            in.radius = (m->model->extentMax - m->model->extentMin).length() * 0.5f * d.scale * scale;
            // inside: the light of the smallest interior group it stands in (the WMO's own coordinates)
            float bestVol = 1e30f;
            in.light[0] = in.light[1] = in.light[2] = 0; in.light[3] = 1;
            for (size_t k = 0; k < loadedGroups.size(); ++k) {
                const WmoGroupInfo& gi = r.groups[size_t(loadedGroups[k])];
                if (!out.groups[k].interior || !inside(d.position, gi.boundsMin, gi.boundsMax)) continue;
                V3 e = gi.boundsMax - gi.boundsMin;
                float vol = e.x * e.y * e.z;
                if (vol < bestVol) {
                    bestVol = vol;
                    in.light[0] = out.groups[k].meanLight.x; in.light[1] = out.groups[k].meanLight.y;
                    in.light[2] = out.groups[k].meanLight.z; in.light[3] = 0;
                }
            }
            out.doodads.push_back(in);
            if (glReady_) for (const M2Texture& t : m->model->textures) if (t.type == 0 && t.fileId) texture(t.fileId, kLocTex);
        }
    }
    return true;
}

void Scene::lightProp(Instance& in) const {
    in.light[0] = in.light[1] = in.light[2] = 0; in.light[3] = 1;
    if (!location_) return;
    float bestVol = 1e30f;
    for (const StaticGroup& g : location_->groups) {
        if (!g.interior || !inside(in.centre, g.boundsMin, g.boundsMax)) continue;
        V3 e = g.boundsMax - g.boundsMin;
        float vol = e.x * e.y * e.z;
        if (vol < bestVol) {
            bestVol = vol;
            in.light[0] = g.meanLight.x; in.light[1] = g.meanLight.y; in.light[2] = g.meanLight.z; in.light[3] = 0;
        }
    }
}

// ------------------------------------------------------------------------------------------------ the location

bool Scene::loadLocation(const std::string& json, const std::string& id, const std::string& view) {
    clearLocation();
    LocationDesc d;
    if (!parseLocation(json, id, d, locError_)) return false;
    M4 world = M4::translation(d.position) * M4::rotation(d.rotation) * M4::scaling(V3(d.scale, d.scale, d.scale));
    auto w = std::make_unique<PlacedWmo>();
    if (!placeWmo(d.root, world, d.scale, d.groups, d.groupFiles, d.sets, d.setNames, *w, locError_)) {
        locError_ = d.name + ": " + locError_;
        return false;
    }
    location_ = std::move(w);
    locDesc_ = d;
    for (const LocationPlacement& p : d.doodads) {
        ModelGl* m = files_(p.model).empty() ? nullptr : model(p.model);
        if (!m) { ++locExtrasMissing_; continue; }
        Instance in;
        in.modelFileId = p.model;
        in.mesh = m;
        in.world = M4::translation(p.position) * M4::rotation(p.rotation) * M4::scaling(V3(p.scale, p.scale, p.scale));
        in.centre = in.world.point((m->model->extentMin + m->model->extentMax) * 0.5f);
        in.radius = (m->model->extentMax - m->model->extentMin).length() * 0.5f * p.scale;
        lightProp(in);
        locExtras_.push_back(in);
        if (glReady_) for (const M2Texture& t : m->model->textures) if (t.type == 0 && t.fileId) texture(t.fileId, kLocTex);
    }
    loadTerrain(d);
    rebuildLocFloor();
    locError_.clear();
    locationView(view);
    std::fprintf(stderr, "location %s: %zu groups (%zu missing), %zu doodads (%zu missing), %zu extra (%zu missing), %zu terrain chunks\n",
                 d.id.c_str(), location_->groups.size(), location_->groupsMissing, location_->doodads.size(),
                 location_->doodadsMissing, locExtras_.size(), locExtrasMissing_, terrain_.size());
    return true;
}

bool Scene::locationView(const std::string& view) {
    if (!location_) return false;
    locLook_ = locDesc_.look;
    auto it = locDesc_.views.find(view);
    if (it != locDesc_.views.end()) locLook_ = overlay(locLook_, it->second.look);
    if (locLook_.hasInterior) std::copy(locLook_.interiorAmbient, locLook_.interiorAmbient + 3, location_->intAmbient);
    light_ = baseLight_;                 // the look over the light as given, not over an hour's grading of it
    applyLook(locLook_, light_);
    baseLight_ = light_;
    if (!terrain_.empty() && !locLook_.hasGround) locLook_.ground = false;   // the map's own ground is there
    // the plain ground: given, else the floor under the outside's spawn, else the WMO's lowest point
    if (!locLook_.groundZSet) {
        // (the lowest of its outdoor groups: a cellar under the building stays under the ground)
        float z = 1e30f;
        for (const StaticGroup& g : location_->groups) if (!g.interior) z = std::min(z, g.boundsMin.z);
        if (z > 1e29f) z = location_->boundsMin.z;
        for (const auto& [name, v] : locDesc_.views) {
            if (!v.hasSpawn || (name != "outside" && name != "exterior")) continue;
            float f = floorIn(locFloor_, v.spawn.x, v.spawn.y, v.spawn.z + 1.0f);
            if (f > -1e9f) z = f;
        }
        locLook_.groundZ = z;
    }
    if (!locLook_.groundColorSet) {
        // a dark earth, a little of the fog in it
        const float earth[3] = {0.26f, 0.23f, 0.19f};
        for (int i = 0; i < 3; ++i) locLook_.groundColor[i] = earth[i] * 0.85f + light_.fogColor[i] * 0.15f;
    }
    applyTimeOfDay();
    return it != locDesc_.views.end() || view.empty();
}

// ------------------------------------------------------------------------------------------------ the time of day

void Scene::setTimeOfDay(float hours) {
    float was = timeOfDay_;
    timeOfDay_ = std::isfinite(hours) && hours >= 0 ? wrapHour(hours) : -1.0f;
    // the clock jumped (not the world's slow hour): the cinematic profile takes the new hour's at once, as the light does
    float jump = std::fabs(timeOfDay_ - was);
    if ((was < 0) != (timeOfDay_ < 0) || std::min(jump, 24.0f - jump) > 0.5f) cineHas_ = false;
    applyTimeOfDay();
}

// The hour's change from noon added to the light as given: the entries' light is the map's LightData at noon, so at
// noon nothing changes, and a view's own colours (a hand-made fog, say) keep their difference from the game's. The
// sun's direction is the hour's; a room's own light is tinted (roomTint_), the baked vertex light kept as it is.
void Scene::applyTimeOfDay() {
    roomTint_[0] = roomTint_[1] = roomTint_[2] = 1.0f;
    // cinematic mode's profile over whatever light there is (daylight.h: cineScene; strength 0 changes nothing); the light
    // before it is kept (dayLight_: what the profile is worked out from, updateCinematic)
    auto mood = [this] {
        dayLight_ = light_;
        std::copy(roomTint_, roomTint_ + 3, dayRoomTint_);
        if (cineMood_ <= 0) return;
        cineScene(cineNow_, cineMood_, cineFlicker_, light_.ambient, light_.sunColor, light_.skyTop, light_.fogColor, roomTint_);
        for (int i = 0; i < 3; ++i) {
            float k = 1.0f + (cineNow_.sceneSky[i] - 1.0f) * cineMood_;
            light_.skyHorizon[i] *= k;
            light_.skyBelow[i] *= k;
        }
    };
    if (timeOfDay_ < 0 || !location_) { light_ = baseLight_; mood(); return; }
    const DayCycle& c = locDesc_.dayCycle.empty() ? defaultDayCycle() : locDesc_.dayCycle;
    DaySample now = sampleDay(c, timeOfDay_, locDesc_.frameYawDeg);
    DayKey noon;
    sampleKeys(c, 12.0f, noon);
    Light l = baseLight_;
    auto shift = [](float* dst, const float* base, const float* at, const float* ref) {
        for (int i = 0; i < 3; ++i) dst[i] = std::clamp(base[i] + at[i] - ref[i], 0.0f, 1.5f);
    };
    shift(l.ambient, baseLight_.ambient, now.ambient, noon.ambient);
    shift(l.sunColor, baseLight_.sunColor, now.direct, noon.direct);
    shift(l.fogColor, baseLight_.fogColor, now.fog, noon.fog);
    shift(l.skyTop, baseLight_.skyTop, now.sky, noon.sky);
    shift(l.skyHorizon, baseLight_.skyHorizon, now.fog, noon.fog);
    shift(l.skyBelow, baseLight_.skyBelow, now.fog, noon.fog);
    l.sunDir[0] = now.lightDir.x; l.sunDir[1] = now.lightDir.y; l.sunDir[2] = now.lightDir.z;
    std::copy(now.interior, now.interior + 3, roomTint_);
    light_ = l;
    mood();
}

// Cinematic mode's profile for the frame (daylight.h): the place's light at the hour (dayLight_, before the profile), the
// room the camera's target stands in (its own ambient and baked light) or the outside, the practical lights of that side
// and the location's hand-set parts; the profile shown eased toward it (a second or so), snapped when the look comes on or
// the hour jumps (setTimeOfDay); then the light and the grade made from it.
void Scene::updateCinematic(uint32_t nowMs) {
    CineInput in;
    std::copy(dayLight_.ambient, dayLight_.ambient + 3, in.ambient);
    std::copy(dayLight_.sunColor, dayLight_.sunColor + 3, in.direct);
    std::copy(dayLight_.skyTop, dayLight_.skyTop + 3, in.sky);
    std::copy(dayLight_.fogColor, dayLight_.fogColor + 3, in.fog);
    in.hour = timeOfDay_;
    const StaticGroup* room = nullptr;
    if (location_) {
        float bestVol = 1e30f;
        for (const StaticGroup& g : location_->groups) {
            if (!g.interior || !inside(target_, g.boundsMin, g.boundsMax)) continue;
            V3 e = g.boundsMax - g.boundsMin;
            float vol = e.x * e.y * e.z;
            if (vol < bestVol) { bestVol = vol; room = &g; }
        }
    }
    in.interior = room != nullptr;
    if (room) {
        std::copy(location_->intAmbient, location_->intAmbient + 3, in.roomAmbient);
        in.roomLight[0] = room->meanLight.x * dayRoomTint_[0];
        in.roomLight[1] = room->meanLight.y * dayRoomTint_[1];
        in.roomLight[2] = room->meanLight.z * dayRoomTint_[2];
    }
    const CineArea& area = locDesc_.cinematic;
    in.practicals = location_ ? (room ? area.inside : area.outside) : CinePracticals();
    cinePractical_ = in.practicals;
    const CineOverride* over = location_ ? (room ? &area.overInside : &area.overOutside) : nullptr;
    CineProfile target = cineProfile(in, over && !over->empty() ? over : nullptr);
    if (!cineHas_) {
        cineNow_ = target;
        cineHas_ = true;
    } else {
        float dt = nowMs > cineClock_ ? std::min(0.1f, float(nowMs - cineClock_) / 1000.0f) : 0.0f;
        cineStep(cineNow_, target, dt, 1.0f);
    }
    cineClock_ = nowMs;
    cineFlicker_ = moodFlicker(float(nowMs % 3600000u) / 1000.0f);
    applyTimeOfDay();
    // the grade at the strength (grade.h): its difference from neutral times the lighting option
    CineProfile g = cineScaled(cineNow_, cineMood_);
    std::copy(g.lift, g.lift + 3, cineGrade_.lift);
    std::copy(g.gain, g.gain + 3, cineGrade_.gain);
    cineGrade_.gamma = g.gamma;
    cineGrade_.contrast = g.contrast;
    cineGrade_.saturation = g.saturation;
    cineGrade_.vignette = std::clamp(cineVignette_ * cineNow_.vignette, 0.0f, 1.0f);
}

void Scene::clearLocation() {
    location_.reset();
    light_ = baseLight_;
    roomTint_[0] = roomTint_[1] = roomTint_[2] = 1.0f;
    terrain_.clear();
    locExtras_.clear();
    locExtrasMissing_ = 0;
    locDesc_ = LocationDesc();
    locLook_ = LocationLook();
    locError_.clear();
    rebuildLocFloor();
}

Scene::LocationStats Scene::locationStats() const {
    LocationStats s = frameStats_;
    s.groups = s.groupsMissing = s.triangles = s.doodads = s.doodadsMissing = s.models = 0;
    if (!location_) return s;
    s.groups = location_->groups.size();
    s.groupsMissing = location_->groupsMissing;
    for (const StaticGroup& g : location_->groups) s.triangles += g.tris.size() / 9;
    for (const TerrainChunk& c : terrain_) s.triangles += c.tris.size() / 9;
    s.doodads = location_->doodads.size() + locExtras_.size();
    s.doodadsMissing = location_->doodadsMissing + locExtrasMissing_;
    std::set<const ModelGl*> models;
    for (const Instance& i : location_->doodads) models.insert(i.mesh);
    for (const Instance& i : locExtras_) models.insert(i.mesh);
    s.models = models.size();
    s.boundsMin = location_->boundsMin;
    s.boundsMax = location_->boundsMax;
    return s;
}

void Scene::rebuildLocFloor() {
    locFloor_.clear();
    auto add = [&](const StaticGroup& g) {
        const std::vector<float>& t = g.tris;
        for (size_t i = 0; i + 8 < t.size(); i += 9) {
            V3 a(t[i], t[i + 1], t[i + 2]), b(t[i + 3], t[i + 4], t[i + 5]), c(t[i + 6], t[i + 7], t[i + 8]);
            V3 n = (b - a).cross(c - a);
            float len = n.length();
            if (len < 1e-6f || std::fabs(n.z) / len < 0.5f) continue;
            int x0 = cellOf(std::min({a.x, b.x, c.x})), x1 = cellOf(std::max({a.x, b.x, c.x}));
            int y0 = cellOf(std::min({a.y, b.y, c.y})), y1 = cellOf(std::max({a.y, b.y, c.y}));
            if ((x1 - x0 + 1) * (y1 - y0 + 1) > 400) continue;
            for (int x = x0; x <= x1; ++x)
                for (int y = y0; y <= y1; ++y) {
                    auto& v = locFloor_[{x, y}];
                    v.insert(v.end(), &t[i], &t[i] + 9);
                }
        }
    };
    if (location_) for (const StaticGroup& g : location_->groups) add(g);
    for (const auto& [id, p] : editorProps_) if (p.wmo) for (const StaticGroup& g : p.wmo->groups) add(g);
    for (const TerrainChunk& c : terrain_) { StaticGroup g; g.tris = c.tris; add(g); }
}

float Scene::floorIn(const std::map<std::pair<int, int>, std::vector<float>>& grid, float x, float y, float z) const {
    auto it = grid.find({cellOf(x), cellOf(y)});
    float best = -1e9f;
    if (it == grid.end()) return best;
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
    return best;
}

bool Scene::groundAt(float x, float y, float z, float& out) const {
    float f = floorIn(locFloor_, x, y, z);
    if (f > -1e9f) { out = f; return true; }
    if (location_ && locLook_.ground && locLook_.groundZ <= z + 1.2f) { out = locLook_.groundZ; return true; }
    return false;
}

// ------------------------------------------------------------------------------------------------ props

int Scene::addProp(uint32_t fileId, V3 pos, float yaw, float scale) {
    std::string path = files_(fileId);
    if (path.empty()) { modelErrors_[fileId] = "file " + std::to_string(fileId) + " isn't downloaded"; return 0; }
    Bytes head;
    FILE* f = std::fopen(path.c_str(), "rb");
    uint8_t magic[4] = {0, 0, 0, 0};
    if (f) { size_t got = std::fread(magic, 1, 4, f); std::fclose(f); (void)got; }
    if (scale <= 0) scale = 1;
    EditorProp p;
    p.fileId = fileId; p.pos = pos; p.yaw = yaw; p.scale = scale;
    M4 world = M4::translation(pos) * M4::rotationZ(yaw) * M4::scaling(V3(scale, scale, scale));
    if (std::memcmp(magic, "REVM", 4) == 0) {        // a WMO's root (MVER, stored back to front)
        auto w = std::make_unique<PlacedWmo>();
        std::string err;
        if (!placeWmo(fileId, world, scale, {}, {}, {}, {}, *w, err)) { modelErrors_[fileId] = err; return 0; }
        p.wmo = std::move(w);
    } else {
        ModelGl* m = model(fileId);
        if (!m) return 0;
        p.inst.mesh = m;
        p.inst.world = world;
        p.inst.centre = world.point((m->model->extentMin + m->model->extentMax) * 0.5f);
        p.inst.radius = (m->model->extentMax - m->model->extentMin).length() * 0.5f * scale;
        lightProp(p.inst);
        if (glReady_) for (const M2Texture& t : m->model->textures) if (t.type == 0 && t.fileId) texture(t.fileId, kLocTex);
    }
    int id = nextProp_++;
    bool wmo = p.wmo != nullptr;
    editorProps_[id] = std::move(p);
    if (wmo) rebuildLocFloor();
    return id;
}

bool Scene::moveProp(int id, V3 pos, float yaw, float scale) {
    auto it = editorProps_.find(id);
    if (it == editorProps_.end()) return false;
    EditorProp& p = it->second;
    if (scale <= 0) scale = 1;
    if (p.wmo) {
        // a WMO's vertices are in the world: placed again
        auto w = std::make_unique<PlacedWmo>();
        std::string err;
        M4 world = M4::translation(pos) * M4::rotationZ(yaw) * M4::scaling(V3(scale, scale, scale));
        if (!placeWmo(p.fileId, world, scale, {}, {}, {}, {}, *w, err)) return false;
        p.wmo = std::move(w);
        rebuildLocFloor();
    } else {
        ModelGl* m = p.inst.mesh;
        p.inst.world = M4::translation(pos) * M4::rotationZ(yaw) * M4::scaling(V3(scale, scale, scale));
        p.inst.centre = p.inst.world.point((m->model->extentMin + m->model->extentMax) * 0.5f);
        p.inst.radius = (m->model->extentMax - m->model->extentMin).length() * 0.5f * scale;
        lightProp(p.inst);
    }
    p.pos = pos; p.yaw = yaw; p.scale = scale;
    return true;
}

void Scene::removeProp(int id) {
    auto it = editorProps_.find(id);
    if (it == editorProps_.end()) return;
    bool wmo = it->second.wmo != nullptr;
    editorProps_.erase(it);
    if (selKind_ == 1 && selId_ == id) selKind_ = selId_ = 0;
    if (wmo) rebuildLocFloor();
}

void Scene::clearProps() {
    editorProps_.clear();
    if (selKind_ == 1) selKind_ = selId_ = 0;
    rebuildLocFloor();
}

bool Scene::propBounds(int id, V3& mn, V3& mx) const {
    auto it = editorProps_.find(id);
    if (it == editorProps_.end()) return false;
    const EditorProp& p = it->second;
    if (p.wmo) { mn = p.wmo->boundsMin; mx = p.wmo->boundsMax; return true; }
    const M2Model& m = *p.inst.mesh->model;
    mn = V3(1e30f, 1e30f, 1e30f); mx = V3(-1e30f, -1e30f, -1e30f);
    for (int k = 0; k < 8; ++k) {
        V3 c((k & 1) ? m.extentMax.x : m.extentMin.x, (k & 2) ? m.extentMax.y : m.extentMin.y, (k & 4) ? m.extentMax.z : m.extentMin.z);
        V3 w = p.inst.world.point(c);
        mn = V3(std::min(mn.x, w.x), std::min(mn.y, w.y), std::min(mn.z, w.z));
        mx = V3(std::max(mx.x, w.x), std::max(mx.y, w.y), std::max(mx.z, w.z));
    }
    return true;
}

// Sample the drawn mesh from above, in model space. Measuring near the middle keeps the headboard
// and chair backs from becoming the support height.
bool Scene::propContact(int id, int furniture, V3& point, float& facing, float& halfDepth) const {
    auto it = editorProps_.find(id);
    if (it == editorProps_.end() || it->second.wmo) return false;
    const auto& inst = it->second.inst;
    const M2Model& m = *inst.mesh->model;
    const V3 span = m.extentMax - m.extentMin;
    const V3 center = (m.extentMin + m.extentMax) * 0.5f;
    const bool alongX = furniture == kSitting ? span.x <= span.y : span.x >= span.y;
    const V3 axis = alongX ? V3(1, 0, 0) : V3(0, 1, 0);
    const float length = alongX ? span.x : span.y;
    const auto heightAt = [&](V3 p) {
        p.z = m.extentMax.z + 0.1f;
        float t = 1e30f;
        return rayModel(m, p, V3(0, 0, -1), t) ? p.z - t : m.extentMin.z;
    };

    V3 contact = center;
    const float low = std::max(heightAt(center - axis * (length * 0.39f)), heightAt(center - axis * (length * 0.48f)));
    const float high = std::max(heightAt(center + axis * (length * 0.39f)), heightAt(center + axis * (length * 0.48f)));
    const bool positive = furniture == kSitting ? high <= low : high >= low;
    if (furniture == kSitting) contact = center + axis * ((positive ? 1 : -1) * length * 0.16f);
    contact.z = heightAt(contact);
    if (furniture == kLying) {
        float heights[] = {contact.z, heightAt(center - axis * (length * 0.22f)), heightAt(center + axis * (length * 0.22f))};
        std::sort(std::begin(heights), std::end(heights));
        contact.z = heights[1];
    }
    if (contact.z < m.extentMin.z + 0.04f) return false;
    point = inst.world.point(contact);
    V3 dir = linear(inst.world, axis * (positive ? 1.0f : -1.0f));
    facing = std::atan2(dir.y, dir.x);
    const float depth = furniture == kLying ? (alongX ? span.y : span.x) : length;
    halfDepth = depth * dir.length() * 0.5f;
    return true;
}

int Scene::locationObject(uint32_t model, V3 origin) const {
    if (!location_) return -1;
    const auto find = [&](const std::vector<Instance>& instances, int base) {
        for (size_t i = 0; i < instances.size(); ++i) {
            const auto& instance = instances[i];
            if (instance.modelFileId == model && (instance.world.point(V3()) - origin).length() < 0.12f)
                return base + int(i);
        }
        return -1;
    };
    const int native = find(location_->doodads, 0);
    return native >= 0 ? native : find(locExtras_, 1000000);
}

bool Scene::walkSegment(V3 from, V3 to) const {
    if (!location_) return false;
    const V3 delta = to - from;
    const float distance = std::hypot(delta.x, delta.y);
    if (!std::isfinite(distance) || distance > 55) return false;
    float previous = from.z;
    // Stay on the floor the actor is already on, including gentle stairs.
    const int steps = std::max(1, int(std::ceil(distance / 0.4f)));
    for (int step = 0; step <= steps; ++step) {
        const V3 p = from + delta * (float(step) / steps);
        const float ground = floorIn(locFloor_, p.x, p.y, previous - 0.85f);
        if (ground < -1e8f || std::fabs(ground - previous) > 0.4f) return false;
        previous = ground;
    }
    if (std::fabs(previous - to.z) > 0.4f) return false;
    if (distance < 0.025f) return true;

    const V3 eye = from + V3(0, 0, 0.65f);
    for (const StaticGroup& group : location_->groups) {
        float boundsDistance;
        if (!rayBox(eye, delta, group.boundsMin, group.boundsMax, boundsDistance) || boundsDistance > 1) continue;
        for (size_t i = 0; i + 8 < group.tris.size(); i += 9) {
            const V3 a(group.tris[i], group.tris[i+1], group.tris[i+2]);
            const V3 b(group.tris[i+3], group.tris[i+4], group.tris[i+5]);
            const V3 c(group.tris[i+6], group.tris[i+7], group.tris[i+8]);
            const V3 normal = (b - a).cross(c - a);
            if (std::fabs(normal.z) >= normal.length() * 0.5f) continue;
            float hit;
            if (rayTri(eye, delta, a, b, c, hit) && hit < 1) return false;
        }
    }
    const auto blocked = [&](const std::vector<Instance>& instances) {
        for (const Instance& in : instances) {
            const V3 rel = in.centre - eye;
            const float at = std::max(0.0f, std::min(1.0f, rel.dot(delta) / delta.dot(delta)));
            if ((rel - delta * at).length() > in.radius) continue;
            const M4 inverse = invertAffine(in.world);
            float hit = 1.0f;
            if (rayModel(*in.mesh->model, inverse.point(eye), linear(inverse, delta), hit)) return true;
        }
        return false;
    };
    return !blocked(location_->doodads) && !blocked(locExtras_);
}

// ------------------------------------------------------------------------------------------------ picking

Scene::PickHit Scene::pick(float sx, float sy, int width, int height) const {
    PickHit hit;
    if (width <= 0 || height <= 0) return hit;
    V3 f = (target_ - eye_).normalized();
    V3 s = f.cross(V3(0, 0, 1)).normalized();
    V3 u = s.cross(f);
    float th = std::tan(fov_ * 3.14159265f / 360.0f), aspect = float(width) / float(height);
    float nx = 2.0f * sx / float(width) - 1.0f, ny = 1.0f - 2.0f * sy / float(height);
    V3 o = eye_, d = (f + s * (nx * th * aspect) + u * (ny * th)).normalized();
    float best = light_.fogEnd > 0 ? light_.fogEnd : 4000.0f;
    auto take = [&](int kind, int id, float t) { if (t < best) { best = t; hit.kind = kind; hit.id = id; } };

    // the editor's props
    for (const auto& [id, p] : editorProps_) {
        if (p.wmo) {
            for (const StaticGroup& g : p.wmo->groups) {
                float tb;
                if (!rayBox(o, d, g.boundsMin, g.boundsMax, tb) || tb > best) continue;
                for (size_t i = 0; i + 8 < g.tris.size(); i += 9) {
                    float t;
                    if (rayTri(o, d, V3(g.tris[i], g.tris[i + 1], g.tris[i + 2]), V3(g.tris[i + 3], g.tris[i + 4], g.tris[i + 5]),
                               V3(g.tris[i + 6], g.tris[i + 7], g.tris[i + 8]), t)) take(kPickProp, id, t);
                }
            }
        } else {
            M4 inv = invertAffine(p.inst.world);
            float t = best;
            if (rayModel(*p.inst.mesh->model, inv.point(o), linear(inv, d), t)) take(kPickProp, id, t);
        }
    }
    for (const auto& [id, a] : actors_) {
        if (!a.visible || a.ghost) continue;
        if (a.lyingHalfLength > 0) {
            const float halfWidth = 0.38f * a.scale;
            M4 inv = invertAffine(M4::translation(a.surface) * M4::rotationZ(a.furnitureFacing));
            float t;
            if (rayBox(inv.point(o), linear(inv, d), V3(-a.lyingHalfLength, -halfWidth, 0),
                       V3(a.lyingHalfLength, halfWidth, 0.55f * a.scale), t)) take(kPickActor, id, std::max(t, 1e-3f));
            continue;
        }
        V3 mn, mx;
        if (!actorBounds(id, mn, mx)) continue;
        M4 inv = invertAffine(M4::translation(a.position) * M4::rotationZ(a.facing));
        float t;
        if (rayBox(inv.point(o), linear(inv, d), mn, mx, t)) take(kPickActor, id, std::max(t, 1e-3f));
    }
    // the location: its walls and floors, its doodads
    if (location_) {
        for (const StaticGroup& g : location_->groups) {
            float tb;
            if (!rayBox(o, d, g.boundsMin, g.boundsMax, tb) || tb > best) continue;
            for (size_t i = 0; i + 8 < g.tris.size(); i += 9) {
                V3 a(g.tris[i], g.tris[i + 1], g.tris[i + 2]), b(g.tris[i + 3], g.tris[i + 4], g.tris[i + 5]), c(g.tris[i + 6], g.tris[i + 7], g.tris[i + 8]);
                float t;
                if (!rayTri(o, d, a, b, c, t) || t >= best) continue;
                V3 n = (b - a).cross(c - a).normalized();
                take(std::fabs(n.z) >= 0.5f ? kPickGround : kPickLocation, -1, t);
            }
        }
        auto doodads = [&](const std::vector<Instance>& list, int base) {
            for (size_t i = 0; i < list.size(); ++i) {
                const Instance& in = list[i];
                V3 rel = in.centre - o;
                float along = rel.dot(d);
                if (along + in.radius < 0 || along - in.radius > best) continue;
                if ((rel - d * along).length() > in.radius) continue;
                M4 inv = invertAffine(in.world);
                float t = best;
                if (rayModel(*in.mesh->model, inv.point(o), linear(inv, d), t)) take(kPickLocation, base + int(i), t);
            }
        };
        for (const TerrainChunk& c : terrain_) {
            float tb;
            if (!rayBox(o, d, c.boundsMin, c.boundsMax, tb) || tb > best) continue;
            for (size_t i = 0; i + 8 < c.tris.size(); i += 9) {
                float t;
                if (rayTri(o, d, V3(c.tris[i], c.tris[i + 1], c.tris[i + 2]), V3(c.tris[i + 3], c.tris[i + 4], c.tris[i + 5]),
                           V3(c.tris[i + 6], c.tris[i + 7], c.tris[i + 8]), t)) take(kPickGround, -1, t);
            }
        }
        doodads(location_->doodads, 0);
        doodads(locExtras_, 1000000);
        // the plain ground
        if (locLook_.ground && std::fabs(d.z) > 1e-6f) {
            float t = (locLook_.groundZ - o.z) / d.z;
            if (t > 0) take(kPickGround, -1, t);
        }
    }
    if (hit.kind != kPickNone) { hit.point = o + d * best; hit.distance = best; }
    return hit;
}

// ------------------------------------------------------------------------------------------------ drawing

namespace {

void staticUniforms(unsigned p, const Light& l, const V3& eye, const M4& viewProj, const float intAmbient[3],
                    const float* roomTint = nullptr) {
    static const float kNoTint[3] = {1, 1, 1};
    glUseProgram(p);
    glUniform3fv(loc(p, "uRoomTint"), 1, roomTint ? roomTint : kNoTint);
    glUniformMatrix4fv(loc(p, "uViewProj"), 1, GL_FALSE, viewProj.m);
    V3 sd = V3(l.sunDir[0], l.sunDir[1], l.sunDir[2]).normalized();
    float sdv[3] = {sd.x, sd.y, sd.z};
    glUniform3fv(loc(p, "uSunDir"), 1, sdv);
    glUniform3fv(loc(p, "uSunColor"), 1, l.sunColor);
    glUniform3fv(loc(p, "uAmbient"), 1, l.ambient);
    glUniform3fv(loc(p, "uIntAmbient"), 1, intAmbient);
    glUniform3fv(loc(p, "uFogColor"), 1, l.fogColor);
    glUniform2f(loc(p, "uFog"), l.fogStart, std::max(l.fogEnd, l.fogStart + 1.0f));
    float e[3] = {eye.x, eye.y, eye.z};
    glUniform3fv(loc(p, "uEye"), 1, e);
    glUniform1i(loc(p, "uTex"), 0);
    glUniform4f(loc(p, "uFlat"), 0, 0, 0, 0);
    glUniform1f(loc(p, "uGlow"), 0);
    glUniform1f(loc(p, "uInflate"), 0);
    glUniform1i(loc(p, "uExtLight"), 0);
}

} // namespace

void Scene::staticSetup(unsigned prog, const M4& viewProj, const float intAmbient[3], const float* roomTint) {
    staticUniforms(prog, light_, eye_, viewProj, intAmbient, roomTint);
}

void Scene::drawWmoGroups(const PlacedWmo& w, bool transparent, const Frustum& f, float glow) {
    unsigned p = locProg_;
    glUniform3fv(loc(p, "uIntAmbient"), 1, w.intAmbient);
    glUniform1f(loc(p, "uGlow"), glow);
    glUniform4fv(loc(p, "uColor"), 1, ghostTint_);   // (white but for the ghost)
    float fogEnd = std::max(light_.fogEnd, light_.fogStart + 1.0f);
    for (const StaticGroup& g : w.groups) {
        if (!g.vao || !f.box(g.boundsMin, g.boundsMax) || boxDistance(eye_, g.boundsMin, g.boundsMax) > fogEnd) continue;
        if (!transparent) ++frameStats_.groupsDrawn;
        glBindVertexArray(g.vao);
        for (const WmoBatch& b : g.batches) {
            if (!b.indexCount || b.material >= w.root.materials.size()) continue;
            const WmoMaterial& m = w.root.materials[b.material];
            bool blend = m.blend >= 2;
            if (blend != transparent) continue;
            GlTexture t = texture(m.texture1, kLocTex);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, t.id);
            glUniform1f(loc(p, "uAlphaRef"), m.blend == 1 ? 0.5f : -1.0f);
            glUniform1i(loc(p, "uOpaque"), m.blend == 0 ? 1 : 0);
            glUniform1i(loc(p, "uUnlit"), (m.flags & 1) ? 1 : 0);
            glUniform1i(loc(p, "uExtLight"), (m.flags & 8) ? 1 : 0);
            if (blend) { glEnable(GL_BLEND); gl::blendFor(m.blend); glDepthMask(GL_FALSE); }
            glDrawElements(GL_TRIANGLES, GLsizei(b.indexCount), GL_UNSIGNED_SHORT, (void*)(size_t(b.indexStart) * 2));
            ++frameStats_.drawCalls;
            if (blend) { glDisable(GL_BLEND); glDepthMask(GL_TRUE); }
        }
    }
    glBindVertexArray(0);
    glUniform1f(loc(p, "uGlow"), 0);
}

void Scene::drawInstances(const std::vector<const Instance*>& listIn, bool transparent, const float* flat, float inflate, float glow) {
    if (listIn.empty()) return;
    std::vector<const Instance*> list = listIn;
    std::stable_sort(list.begin(), list.end(), [](const Instance* a, const Instance* b) { return a->mesh < b->mesh; });
    std::vector<float> data(list.size() * 20);
    for (size_t i = 0; i < list.size(); ++i) {
        std::memcpy(&data[i * 20], list[i]->world.m, 64);
        std::memcpy(&data[i * 20 + 16], list[i]->light, 16);
    }
    glBindBuffer(GL_ARRAY_BUFFER, instVbo_);
    size_t bytes = data.size() * 4;
    if (bytes > instCapacity_) {
        instCapacity_ = std::max(bytes, instCapacity_ * 2);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(instCapacity_), nullptr, GL_STREAM_DRAW);
    }
    glBufferSubData(GL_ARRAY_BUFFER, 0, GLsizeiptr(bytes), data.data());

    unsigned p = instProg_;
    glUniform1f(loc(p, "uInflate"), inflate);
    glUniform1f(loc(p, "uGlow"), glow);
    if (flat) glUniform4fv(loc(p, "uFlat"), 1, flat);
    glUniform1i(loc(p, "uExtLight"), 0);
    for (size_t at = 0; at < list.size();) {
        ModelGl* mg = list[at]->mesh;
        size_t n = 1;
        while (at + n < list.size() && list[at + n]->mesh == mg) ++n;
        const M2Model& m = *mg->model;
        unsigned& vao = instVaos_[mg];
        if (!vao) {
            glGenVertexArrays(1, &vao);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, mg->vbo);
            gl::m2VertexLayout();
            glDisableVertexAttribArray(3);
            glDisableVertexAttribArray(4);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mg->ibo);
            for (int k = 5; k <= 9; ++k) { glEnableVertexAttribArray(GLuint(k)); glVertexAttribDivisor(GLuint(k), 1); }
        }
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, instVbo_);
        for (int k = 0; k < 5; ++k)
            glVertexAttribPointer(GLuint(5 + k), 4, GL_FLOAT, GL_FALSE, 80, (void*)(at * 80 + size_t(k) * 16));
        for (const M2Batch& b : m.batches) {
            if (b.submesh >= m.submeshes.size()) continue;
            const M2Submesh& sm = m.submeshes[b.submesh];
            if (!sm.indexCount) continue;
            M2Material mat = b.material < m.materials.size() ? m.materials[b.material] : M2Material();
            bool blend = mat.blend >= 2;
            if (blend != transparent) continue;
            if (b.textureCombo >= m.textureCombos.size()) continue;
            uint16_t ti = m.textureCombos[b.textureCombo];
            if (ti >= m.textures.size()) continue;
            const M2Texture& tex = m.textures[ti];
            if (tex.type != 0 || !tex.fileId) continue;      // a slot a look fills: nothing to fill it with here
            float c[4];
            m.evalColor(b.colorIndex == 0xFFFF ? -1 : int(b.colorIndex), -1, 0, 0, c);
            c[3] *= m.evalWeight(b.weightCombo == 0xFFFF ? -1 : int(b.weightCombo), -1, 0, 0);
            if (c[3] <= 0.004f) continue;
            for (int i = 0; i < 4; ++i) c[i] *= ghostTint_[i];   // (white but for the ghost)
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture(tex.fileId, kLocTex).id);
            glUniform4fv(loc(p, "uColor"), 1, c);
            glUniform1f(loc(p, "uAlphaRef"), mat.blend == 1 ? 0.5f : (mat.blend == 0 ? -1.0f : 0.004f));
            glUniform1i(loc(p, "uOpaque"), mat.blend == 0 ? 1 : 0);
            glUniform1i(loc(p, "uUnlit"), (mat.flags & 1) ? 1 : 0);
            if (blend) { glEnable(GL_BLEND); gl::blendFor(mat.blend); glDepthMask(GL_FALSE); }
            if ((mat.flags & 16) || flat) glDepthMask(GL_FALSE);
            glDrawElementsInstanced(GL_TRIANGLES, GLsizei(sm.indexCount), GL_UNSIGNED_INT, (void*)(size_t(sm.indexStart) * 4), GLsizei(n));
            ++frameStats_.drawCalls;
            if (blend) glDisable(GL_BLEND);
            glDepthMask(GL_TRUE);
        }
        at += n;
    }
    glBindVertexArray(0);
    glUniform4f(loc(p, "uFlat"), 0, 0, 0, 0);
    glUniform1f(loc(p, "uInflate"), 0);
    glUniform1f(loc(p, "uGlow"), 0);
}

void Scene::drawStatic(bool transparent, const M4& viewProj) {
    if ((!location_ && editorProps_.empty()) || !locProg_) return;
    Frustum f(viewProj);
    float zero[3] = {0, 0, 0};
    const float* intAmb = location_ ? location_->intAmbient : zero;
    if (!transparent) frameStats_.drawCalls = frameStats_.groupsDrawn = frameStats_.instancesDrawn = 0;
    staticUniforms(locProg_, light_, eye_, viewProj, intAmb, roomTint_);
    if (location_) drawWmoGroups(*location_, transparent, f, 0);
    if (!transparent && !terrain_.empty()) {
        drawTerrain(viewProj, f);
        staticUniforms(locProg_, light_, eye_, viewProj, intAmb, roomTint_);
    }
    for (const auto& [id, p] : editorProps_)
        if (p.wmo) drawWmoGroups(*p.wmo, transparent, f, selKind_ == 1 && selId_ == id ? 0.12f : 0.0f);

    // every static M2 in view, this side of the fog's end, one instanced draw per model and batch
    float fogEnd = std::max(light_.fogEnd, light_.fogStart + 1.0f);
    std::vector<const Instance*> list;
    const Instance* selected = nullptr;
    auto consider = [&](const Instance& in) {
        if ((in.centre - eye_).length() - in.radius > fogEnd || !f.sphere(in.centre, in.radius)) return;
        list.push_back(&in);
    };
    if (location_) { for (const Instance& in : location_->doodads) consider(in); }
    for (const Instance& in : locExtras_) consider(in);
    for (const auto& [id, p] : editorProps_) {
        if (p.wmo) { for (const Instance& in : p.wmo->doodads) consider(in); continue; }
        if (selKind_ == 1 && selId_ == id) { selected = &p.inst; continue; }
        consider(p.inst);
    }
    if (!transparent) frameStats_.instancesDrawn = list.size() + (selected ? 1 : 0);
    staticUniforms(instProg_, light_, eye_, viewProj, intAmb, roomTint_);
    drawInstances(list, transparent, nullptr, 0, 0);
    if (selected) drawInstances({selected}, transparent, nullptr, 0, 0.12f);
}

void Scene::drawGround(const M4& viewProj) {
    if (!location_ || !locLook_.ground || !groundProg_) return;
    V3 c = (location_->boundsMin + location_->boundsMax) * 0.5f;
    float r = std::max(light_.fogEnd, (location_->boundsMax - location_->boundsMin).length()) * 1.5f + 50.0f;
    float z = locLook_.groundZ - 0.04f;   // under the WMO's own floors at the same height
    float v[18] = {c.x - r, c.y - r, z, c.x + r, c.y - r, z, c.x + r, c.y + r, z,
                   c.x - r, c.y - r, z, c.x + r, c.y + r, z, c.x - r, c.y + r, z};
    unsigned p = groundProg_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uViewProj"), 1, GL_FALSE, viewProj.m);
    glUniform3fv(loc(p, "uGround"), 1, locLook_.groundColor);
    V3 sd = V3(light_.sunDir[0], light_.sunDir[1], light_.sunDir[2]).normalized();
    glUniform3f(loc(p, "uSunDir"), sd.x, sd.y, sd.z);
    glUniform3fv(loc(p, "uSunColor"), 1, light_.sunColor);
    glUniform3fv(loc(p, "uAmbient"), 1, light_.ambient);
    glUniform3fv(loc(p, "uFogColor"), 1, light_.fogColor);
    glUniform2f(loc(p, "uFog"), light_.fogStart, std::max(light_.fogEnd, light_.fogStart + 1.0f));
    glUniform3f(loc(p, "uEye"), eye_.x, eye_.y, eye_.z);
    glBindVertexArray(groundVao_);
    glBindBuffer(GL_ARRAY_BUFFER, groundVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof v, v, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, (void*)0);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    ++frameStats_.drawCalls;
    glBindVertexArray(0);
}

void Scene::lightActor(const V3& feet, bool restore) {
    if (!location_ || !m2Prog_) return;
    unsigned p = m2Prog_;
    glUseProgram(p);
    const StaticGroup* room = nullptr;
    float bestVol = 1e30f;
    V3 at = feet + V3(0, 0, 1.0f);
    if (!restore)
        for (const StaticGroup& g : location_->groups) {
            if (!g.interior || !inside(at, g.boundsMin, g.boundsMax)) continue;
            V3 e = g.boundsMax - g.boundsMin;
            float vol = e.x * e.y * e.z;
            if (vol < bestVol) { bestVol = vol; room = &g; }
        }
    V3 sun = V3(light_.sunDir[0], light_.sunDir[1], light_.sunDir[2]).normalized();
    float amb[3], dir[3];
    if (!room) {
        std::copy(light_.ambient, light_.ambient + 3, amb);
        std::copy(light_.sunColor, light_.sunColor + 3, dir);
    } else {
        // the room's ambient and its mean baked light, a little of it from the sun's side so the body keeps its shape
        const float* ia = location_->intAmbient;
        const float* t = roomTint_;
        for (int i = 0; i < 3; ++i) {
            float mean = i == 0 ? room->meanLight.x : i == 1 ? room->meanLight.y : room->meanLight.z;
            amb[i] = (ia[i] + mean * 0.8f) * t[i];
            dir[i] = mean * 0.5f * t[i];
        }
    }
    static const float kNone[3] = {0, 0, 0};
    float sdv[3] = {sun.x, sun.y, sun.z};
    if (cineMood_ > 0 && !restore) {
        // cinematic mode: the area's profile (daylight.h): less ambient, a key in the area's colour from the camera's side,
        // low, pulled toward the sun or the practical lights, and a rim from behind
        MoodActorLight m = cineActor(cineNow_, cineMood_, cineFlicker_, amb, dir);
        V3 key, rim;
        moodDirections(eye_, target_, key, rim);
        V3 sunTravel(dayLight_.sunDir[0], dayLight_.sunDir[1], dayLight_.sunDir[2]);
        key = cineKeyDirection(eye_, target_, sunTravel, cineNow_.sunBias, cinePractical_.hasAt, cinePractical_.at,
                               cineNow_.practicalBias);
        V3 d = sun * (1.0f - cineMood_) + key * cineMood_;
        d = d.length() > 1e-3f ? d.normalized() : key;
        float dv[3] = {d.x, d.y, d.z}, rv[3] = {rim.x, rim.y, rim.z};
        glUniform3fv(loc(p, "uAmbient"), 1, m.ambient);
        glUniform3fv(loc(p, "uSunColor"), 1, m.key);
        glUniform3fv(loc(p, "uSunDir"), 1, dv);
        glUniform3fv(loc(p, "uRimColor"), 1, m.rim);
        glUniform3fv(loc(p, "uRimDir"), 1, rv);
        return;
    }
    glUniform3fv(loc(p, "uAmbient"), 1, amb);
    glUniform3fv(loc(p, "uSunColor"), 1, dir);
    glUniform3fv(loc(p, "uSunDir"), 1, sdv);
    glUniform3fv(loc(p, "uRimColor"), 1, kNone);
}

void Scene::drawSelection(const M4& viewProj) {
    if (selKind_ != 1 || !instProg_) return;
    auto it = editorProps_.find(selId_);
    if (it == editorProps_.end() || it->second.wmo) return;   // a WMO prop glows (drawStatic), it has no outline
    const Instance& in = it->second.inst;
    float dist = std::max(0.5f, (in.centre - eye_).length());
    float flat[4] = {1.0f, 0.82f, 0.30f, 1.0f};
    const float* intAmb = location_ ? location_->intAmbient : flat;
    staticUniforms(instProg_, light_, eye_, viewProj, intAmb, roomTint_);
    // the shell, pushed out a few pixels' worth, behind the prop: its rim shows round the prop's edge
    drawInstances({&in}, false, flat, dist * 0.006f, 0);
    // the prop again over the shell's inside (the same depth: LEQUAL lets it through)
    drawInstances({&in}, false, nullptr, 0, 0.12f);
}

} // namespace wc
