#include "scene.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <unordered_map>

#include "blp.h"
#include "bodyshape.h"
#include "daylight.h"
#include "flesh.h"
#include "glutil.h"
#include "humanscale.h"
#include "sculpt.h"

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
// The soft body (softbody.h): uSoftN regions (0: off); each moves the skin inside its ellipsoid (rest centre, 1/radii)
// by its displacement, fading to its edges and, with an axis, to the body behind it; only as much as the vertex's bones'
// classes (uSoftGate, per bone: chest, lower torso, thigh, upper arm) count for it (uSoftMask). Its sides draw in a little.
uniform int uSoftN;
uniform float uSoftGain;
uniform highp sampler2D uSoftGate;
uniform vec4 uSoftRest[10];
uniform vec4 uSoftInvR[10];
uniform vec4 uSoftAxis[10];
uniform vec4 uSoftMask[10];
uniform vec4 uSoftDisp[10];
uniform vec4 uSoftCtr[10];
// The live sculpt (sculpt.h SculptMorph; bodyrig.cpp): while a slider moves, each vertex's move fields for a value of 1 (uMorphIdx
// per vertex: its first entry, how many, its side; uMorphData per entry: the slider, the move) blended by the sliders' change
// since the body was last sculpted (uMorphW per slider; uMorphB per slider times the vertex's side, for a balance), in the bind
// pose before the skinning. uMorphOn 0: off (every other draw).
uniform int uMorphOn;
uniform highp sampler2D uMorphIdx;
uniform highp sampler2D uMorphData;
uniform vec4 uMorphW[16];
uniform vec4 uMorphB[16];
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
mat4 bone(float i) {
    int b = int(i + 0.5);
    return mat4(texelFetch(uBones, ivec2(0, b), 0), texelFetch(uBones, ivec2(1, b), 0),
                texelFetch(uBones, ivec2(2, b), 0), texelFetch(uBones, ivec2(3, b), 0));
}
vec4 gate(float i) { return texelFetch(uSoftGate, ivec2(0, int(i + 0.5)), 0); }
vec3 morphed() {
    if (uMorphOn == 0) return aPos;
    int iw = textureSize(uMorphIdx, 0).x;
    int dw = textureSize(uMorphData, 0).x;
    vec4 h = texelFetch(uMorphIdx, ivec2(gl_VertexID % iw, gl_VertexID / iw), 0);
    int e0 = int(h.x + 0.5);
    int n = int(h.y + 0.5);
    vec3 p = aPos;
    for (int i = 0; i < 64; ++i) {
        if (i >= n) break;
        int e = e0 + i;
        vec4 d = texelFetch(uMorphData, ivec2(e % dw, e / dw), 0);
        int c = int(d.x + 0.5);
        int q = c / 4;
        int r = c - 4 * q;
        p += (uMorphW[q][r] + h.z * uMorphB[q][r]) * d.yzw;
    }
    return p;
}
void main() {
    vec3 pos = morphed();
    float total = aWeights.x + aWeights.y + aWeights.z + aWeights.w;
    mat4 skin = total > 0.0
        ? bone(aBones.x) * aWeights.x + bone(aBones.y) * aWeights.y + bone(aBones.z) * aWeights.z + bone(aBones.w) * aWeights.w
        : mat4(1.0);
    if (total > 0.0) skin = skin * (1.0 / total);
    vec4 sp = skin * vec4(pos, 1.0);
    if (uSoftN > 0 && uSoftGain > 0.0 && total > 0.0) {
        vec4 g = (gate(aBones.x) * aWeights.x + gate(aBones.y) * aWeights.y + gate(aBones.z) * aWeights.z +
                  gate(aBones.w) * aWeights.w) * (1.0 / total);
        vec3 off = vec3(0.0);
        for (int r = 0; r < 10; ++r) {
            if (r >= uSoftN) break;
            float m = clamp(dot(g, uSoftMask[r]), 0.0, 1.0);
            if (m <= 0.0) continue;
            vec3 q = (pos - uSoftRest[r].xyz) * uSoftInvR[r].xyz;
            float w;
            if (uSoftAxis[r].w > 0.5) {
                float qa = dot(q, uSoftAxis[r].xyz);
                float qp = dot(q, q) - qa * qa;
                if (qp >= 1.0) continue;
                w = (1.0 - qp) * (1.0 - qp) * smoothstep(-1.0, 0.25, qa) * (1.0 - smoothstep(1.3, 1.9, qa));
            } else {
                float d2 = dot(q, q);
                if (d2 >= 1.0) continue;
                w = (1.0 - d2) * (1.0 - d2);
            }
            vec3 d = uSoftDisp[r].xyz;
            float dd = dot(d, d);
            vec3 rel = sp.xyz - uSoftCtr[r].xyz;
            vec3 side = dd > 1e-12 ? rel - d * (dot(rel, d) / dd) : vec3(0.0);
            off += (w * m) * (d - side * uSoftDisp[r].w);
        }
        sp.xyz += off * uSoftGain;
    }
    vec4 p = uWorld * sp;
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
uniform vec4 uGhost;   // the placement editor's ghost: its colour mixed toward rgb by a (a 0: none)
uniform vec3 uRimColor;   // cinematic mode's rim light on a person (0: none) and the way toward it
uniform vec3 uRimDir;
// Options' Graphics (scene_gfx.cpp): a person's skin (uSkinKind 1) and hair (2) shaded with uSkinFx (wrap, sheen, warm
// scatter, hair's highlight); 0 everywhere else, and all 0 at Standard: the light as before. uSkinFx2: the eyes' highlights
// (uSkinKind 3, the eyes' own mesh: texture type 19) and the thin-skin tint. uSkinActor, the person drawn: their sheen
// (0..1) and 1 in w when their skin has the HD layout (the face on the texture's right half, the hands in the band under
// the arms: CharComponentTextureSections), where their thin parts are found by the flesh profile (uThinHead, uThinHands:
// scene_gfx.cpp skinOf).
uniform int uSkinKind;
uniform vec4 uSkinFx;
uniform vec4 uSkinFx2;
uniform vec4 uSkinActor;
uniform mat4 uThinHead;
uniform vec4 uThinHands[2];
uniform vec3 uEyeC[2];   // the eyeballs' centres (world; their bones' pivots): the eyes' mesh is a flat lens, its normal not
                         // the ball's
out vec4 fragColor;
vec3 skinShade(vec3 albedo, vec3 lit, vec3 n, float d) {
    vec3 v = normalize(uEye - vWorld);
    vec3 l = -uSunDir;
    float nl = dot(n, l);
    if (uSkinKind == 1) {
        float sheen = uSkinActor.x;
        vec3 alb = albedo * (1.0 - 0.1 * sheen);   // wet skin reads a little darker
        // thin parts: in the face, past the head's measured flesh in front (the nose, the lips) or at the sides (the ears);
        // in the hands, past the middle of the hand's length (the fingers)
        float thin = 0.0;
        if (uSkinFx2.y > 0.0 && uSkinActor.w > 0.5) {
            if (vUv.x > 0.5) {
                vec3 q = (uThinHead * vec4(vWorld, 1.0)).xyz;
                thin = max(smoothstep(0.9, 1.3, abs(q.y)), smoothstep(0.98, 1.25, q.x));
            } else if (vUv.x < 0.25 && vUv.y > 0.5 && vUv.y < 0.625) {
                float r = min(distance(vWorld, uThinHands[0].xyz), distance(vWorld, uThinHands[1].xyz)) * uThinHands[0].w;
                thin = smoothstep(0.45, 0.95, r);
            }
            thin *= uSkinFx2.y;
        }
        float w = uSkinFx.x + 0.35 * thin;
        float wrap = max((nl + w) / (1.0 + w), 0.0);
        vec3 c = alb * (uAmbient + uSunColor * wrap);
        c += alb * uSunColor * vec3(0.85, 0.28, 0.14) * max(wrap - d, 0.0) * max(uSkinFx.z, thin);
        // the key light through a thin part, toward the eye (from behind it)
        c += alb * uSunColor * vec3(1.0, 0.3, 0.16) * pow(clamp(dot(v, uSunDir), 0.0, 1.0), 3.0) * thin * 0.5;
        float fres = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 4.0);
        c += (uAmbient * 0.6 + uSunColor * 0.4) * fres * (uSkinFx.y + 0.6 * sheen) * 0.12;
        vec3 h = normalize(l + v);
        c += uSunColor * pow(max(dot(n, h), 0.0), mix(28.0, 96.0, sheen)) * (0.05 * uSkinFx.y + 0.2 * sheen) * d;
        // wet skin also mirrors the room's light from above, whatever side the key is on
        float hu = max(dot(n, normalize(vec3(0.0, 0.0, 1.0) + v)), 0.0);
        c += (uAmbient + uSunColor * 0.3) * pow(hu, 24.0) * 0.4 * sheen;
        return c;
    }
    if (uSkinKind == 3) {
        // the eyes: a catchlight on the eyeball (its normal from the ball's centre), on the key light's side of the point
        // that faces the camera and a little under it (where the lids leave it seen), as a film's eye light puts it: shown
        // whichever way the key comes from, brighter as it faces the eye; and a broad wet sheen on the cornea
        vec3 e0 = vWorld - uEyeC[0], e1 = vWorld - uEyeC[1];
        vec3 e = dot(e0, e0) < dot(e1, e1) ? e0 : e1;
        if (dot(e, e) > 1e-10) n = normalize(e);
        vec3 across = l - v * dot(l, v);
        float al = length(across);
        vec3 up = vec3(0.0, 0.0, 1.0) - v * v.z;
        float ul = length(up);
        vec3 off = (al > 1e-3 ? across / al : vec3(0.0)) * 0.65 - (ul > 1e-3 ? up / ul : vec3(0.0)) * 0.75;
        float ol = length(off);
        vec3 lc = normalize(v + (ol > 1e-3 ? off / ol : vec3(0.0)) * 0.32);
        float nh = max(dot(n, normalize(lc + v)), 0.0);
        float nv = clamp(dot(n, v), 0.0, 1.0);
        float glint = pow(nh, 900.0) * 1.6 + pow(nh, 160.0) * 0.22;
        float wet = pow(nh, 14.0) * 0.05 + pow(1.0 - nv, 3.0) * 0.06;
        vec3 key = uSunColor + uAmbient * 0.6;
        return lit + key * (glint + wet) * uSkinFx2.x * (0.55 + 0.45 * smoothstep(-0.4, 0.4, dot(l, v)));
    }
    vec3 t = vec3(0.0, 0.0, 1.0) - n * n.z;
    t = dot(t, t) > 1e-6 ? normalize(t) : vec3(1.0, 0.0, 0.0);
    vec3 h = normalize(l + v);
    float th = dot(t, h);
    float s1 = pow(sqrt(max(1.0 - th * th, 0.0)), 90.0);
    vec3 t2 = normalize(t + n * 0.3);
    float th2 = dot(t2, h);
    float s2 = pow(sqrt(max(1.0 - th2 * th2, 0.0)), 18.0);
    return lit + uSunColor * (s1 * 0.10 + s2 * albedo * 0.22) * uSkinFx.w * smoothstep(-0.2, 0.3, nl);
}
void main() {
    vec4 c = texture(uTex, vUv) * uColor;
    if (c.a < uAlphaRef) discard;
    vec3 rgb = c.rgb;
    if (uUnlit == 0) {
        vec3 n = normalize(vNormal);
        float d = max(dot(n, -uSunDir), 0.0);
        rgb *= uAmbient + uSunColor * d;
        if (uSkinKind > 0) rgb = skinShade(c.rgb, rgb, n, d);
        if (dot(uRimColor, uRimColor) > 0.0) {
            float edge = 1.0 - clamp(dot(n, normalize(uEye - vWorld)), 0.0, 1.0);
            rgb += c.rgb * uRimColor * (edge * edge) * (0.35 + 0.65 * max(dot(n, uRimDir), 0.0));
        }
    }
    float f = clamp((distance(vWorld, uEye) - uFog.x) / max(uFog.y - uFog.x, 1.0), 0.0, 1.0);
    rgb = mix(rgb, uFogColor, f);
    rgb = mix(rgb, uGhost.rgb, uGhost.a);
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

using gl::Frustum;

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

// The helpers above, for location.cpp (glutil.h).
namespace gl {
unsigned program(const char* vs, const char* fs) { return wc::program(vs, fs); }
int loc(unsigned p, const char* name) { return wc::loc(p, name); }
void blendFor(uint32_t mode) { wc::blendFor(mode); }
void m2VertexLayout() { wc::m2VertexLayout(); }
} // namespace gl

Scene::~Scene() { /* GL objects die with the context */ }

namespace {
// The body's GL objects are gone with the context: made again at the next frame.
template <typename B> void forgetBodyGl(B& b) {
    b.vao = b.vbo = b.ibo = b.layerVao = b.layerVbo = b.layerIbo = b.layerTex = 0;
    b.layerIndices = 0;
    b.dirty = !b.morph.empty() || b.design != 0 || !b.shaped.empty();   // (a sculpted body's own buffers made again too)
    b.morphIdxTex = b.morphDataTex = 0;   // (the live sculpt's fields: made again when next needed)
    b.morphGen = 0;
    b.morphBytes = 0;
}
} // namespace

bool Scene::initGl() {
    gLocations.clear();
    m2Prog_ = program(kM2Vs, kM2Fs);
    if (m2Prog_) {   // (the live sculpt's textures on units 6 and 7, off until a body's draw turns it on: bodyrig.cpp)
        glUseProgram(m2Prog_);
        glUniform1i(loc(m2Prog_, "uMorphIdx"), 6);
        glUniform1i(loc(m2Prog_, "uMorphData"), 7);
        glUniform1i(loc(m2Prog_, "uMorphOn"), 0);
    }
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
    grade_.forget();
    postfx_.forget();
    glReady_ = true;
    // A context that was lost takes everything with it: build again what the scene holds.
    for (auto& [id, m] : models_) if (m->ok) uploadModel(*m);
    for (auto& [id, a] : actors_) {
        a.boneTex = 0;
        a.composed.clear();
        a.skinDirty = !a.layers.empty();
        a.firstSkin = a.skinDirty;   // (drawn again once its skin is)
        for (Skinned& e : a.skinned) e.boneTex = 0;
        forgetBodyGl(a.body);
        a.soft.forget();
        a.cloth.vao = a.cloth.vbo = a.cloth.ibo = a.cloth.tex = 0;   // (its texture is composed again with the skin)
        for (PartSlot& s : a.parts.slots) s.tex = s.vao = s.vbo = 0;   // (uploaded again from its kept pixels when next drawn)
        a.eve.forgetGl();                              // (and the Eve body's, from its kept vertices and pixels)
        a.hd.forgetGl();                               // (and the HD figure's, from its kept fit)
    }
    hdTex_.clear();   // (the HD figures' textures decoded again when drawn)
    for (auto& [id, p] : parts_) { p->vao = p->vbo = p->ibo = 0; uploadPart(*p); }
    if (cityLoaded_) { uploadCityGl(); warmCity(); }
    staticGl();
    if (ghost_.wmo) for (StaticGroup& g : ghost_.wmo->groups) uploadStatic(g);
    return true;
}

void Scene::releaseGl() {
    glReady_ = false;
    compositor_.releaseGl();
    grade_.forget();
    postfx_.forget();
    particleDraw_.release();
    sparkleDraw_.release();
    textures_.clear();
    for (auto& [id, m] : models_) { m->vao = m->vbo = m->ibo = m->boneTex = 0; }
    for (auto& [id, a] : actors_) {
        a.boneTex = 0;
        a.composed.clear();
        a.skinDirty = !a.layers.empty();
        for (Skinned& e : a.skinned) e.boneTex = 0;
        forgetBodyGl(a.body);
        a.soft.forget();
        a.cloth.vao = a.cloth.vbo = a.cloth.ibo = a.cloth.tex = 0;   // (its texture is composed again with the skin)
        for (PartSlot& s : a.parts.slots) s.tex = s.vao = s.vbo = 0;
        a.eve.forgetGl();
        a.hd.forgetGl();
    }
    hdTex_.clear();
    for (auto& [id, p] : parts_) p->vao = p->vbo = p->ibo = 0;
    groups_.clear();
    forgetStaticGl();
    gizmoProg_ = gizmoVao_ = gizmoVbo_ = 0;
    if (ghost_.wmo) for (StaticGroup& g : ghost_.wmo->groups) g.vao = g.vbo = g.ibo = 0;
}

GlTexture Scene::texture(uint32_t fileId, int maxSize) {
    maxSize = gfx_.textureCap(maxSize);   // (Options' Graphics: texture detail picks a larger mip of the file)
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
        if (gfx_.anisotropy > 1) applyAnisotropy(gfx_.anisotropy);
        t.hasAlpha = img.hasAlpha;
        t.bytes = uint32_t(std::min<size_t>(n * (opaque ? 2 : 4) * 4 / 3, UINT32_MAX));
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
    if (!m->ok) {
        // not kept: a file still on its way (the skin, the skeleton) lets the next try read it
        modelErrors_[fileId] = m->model->error.empty() ? "model file " + std::to_string(fileId) + " couldn't be read" : m->model->error;
        std::fprintf(stderr, "model %u: %s\n", fileId, modelErrors_[fileId].c_str());
        return nullptr;
    }
    modelErrors_.erase(fileId);
    if (glReady_) uploadModel(*m);
    ModelGl* raw = m.get();
    models_[fileId] = std::move(m);
    return raw;
}

std::string Scene::modelError(uint32_t fileId) const {
    auto it = modelErrors_.find(fileId);
    return it == modelErrors_.end() ? std::string() : it->second;
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
    float best = -1e9f;
    float lz;
    if (groundAt(x, y, z, lz)) best = lz;   // the location's and the WMO props' floors (location.cpp)
    if (!cityShown_) return best > -1e9f ? best : z;
    auto it = floor_.find({floorCell(x), floorCell(y)});
    if (it == floor_.end()) return best > -1e9f ? best : z;
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
    a.rng = 0x9E3779B9u ^ (uint32_t(id) * 0x85EBCA6Bu);
    actors_[id] = std::move(a);
    playAnim(id, 0, true, animClock_);
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
    dropSculptMorph(it->second);
    dropPartGl(it->second.parts);
    it->second.soft.release(glReady_);
    dropClothGl(it->second, true);
    dropEveGl(it->second);
    dropHdGl(it->second);
    actors_.erase(it);
}

void Scene::dropDressGl(Actor& a) {
    dropClothGl(a, true);
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
    a.parts.placed = false;  // (and the lower body the parts are fitted to)
    a.parts.entries = EntryAnchors();   // (and its entry anchors as a partner)
    a.soft.dirty = true;     // (and the skin the soft body finds its regions on)
    a.cloth.dirty = true;    // (and whether a flap hangs, and its shape)
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
        // the choices' own geosets on it (several choices name one collection: each its own), whatever the groups say
        for (const DressModel& o : a.models) {
            if (o.model != d.model || o.attachment >= 0 || o.geoset <= 0 || o.geoset % 100 == 0) continue;
            uint16_t id = uint16_t(o.geoset);
            bool has = false;
            for (const M2Submesh& sm : m->model->submeshes) if (sm.id == id) { has = true; break; }
            if (!has || std::find(e.geosets.begin(), e.geosets.end(), id) != e.geosets.end()) continue;
            if (e.geosets.size() == 1 && e.geosets[0] == 0xFFFF) e.geosets.clear();
            e.geosets.push_back(id);
        }
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
    if (glReady_ && a.cloth.tex) glDeleteTextures(1, &a.cloth.tex);
    a.cloth.tex = 0;
    std::map<int, std::vector<SkinLayer>> byType;
    std::vector<SkinLayer> clothLayers;   // a hanging cloth's own (skin.h isClothLayer): not on the body's skin
    for (const SkinLayer& l : a.layers) {
        if (!l.fileId) continue;
        if (isClothLayer(l)) clothLayers.push_back(l);
        else byType[l.textureType].push_back(l);
    }
    auto layerTexture = [&](uint32_t fid) -> unsigned {
        if (files_(fid).empty()) { a.missing.insert(fid); return 0; }
        GlTexture t = texture(fid, kActorTex);
        return t.id == white_ ? 0 : t.id;
    };
    for (auto& [type, layers] : byType) {
        if (directLayer(layers)) {
            if (files_(layers[0].fileId).empty()) a.missing.insert(layers[0].fileId);
            else a.drawTextures[uint32_t(type)] = layers[0].fileId;
            continue;
        }
        unsigned tex = compositor_.compose(layers, layerTexture, kSkinTex);
        if (tex) a.composed[uint32_t(type)] = tex;
    }
    // The cloth's own texture: the skin with the cloth's layers over it, composed for the tabard's panel alone (cloth.h
    // clothPanelRect: the rest of its front and back, cut from its torso sections the right way up; or, without sections,
    // the rectangle the shown flap's own texture coordinates cover), each layer masked to that window. Nothing of the
    // cloth's layers is painted on the body: the chest and the belly stay bare. Clamped at its edges: the flap's middle
    // sits on the window's edge.
    a.cloth.panel = false;
    if (!clothLayers.empty()) {
        int mw = 0, mh = 0;
        std::vector<int> sections;
        for (const SkinLayer& l : clothLayers) {
            mw = std::max(mw, l.materialW); mh = std::max(mh, l.materialH);
            sections.insert(sections.end(), {l.x, l.y, l.w, l.h});
        }
        int rect[4];
        const bool panel = mw > 0 && mh > 0 && clothPanelRect(sections, mw, mh, rect);
        if (panel || (mw > 0 && mh > 0 && clothUvRect(*a.mesh->model, a.geosets, mw, mh, 4, rect))) {
            std::vector<SkinLayer> layers = byType[1];
            for (SkinLayer l : clothLayers) {
                l.maskX = rect[0]; l.maskY = rect[1]; l.maskW = rect[2]; l.maskH = rect[3];
                layers.push_back(l);
            }
            a.cloth.tex = compositor_.compose(layers, layerTexture, kSkinTex, rect);
            if (a.cloth.tex) {
                glBindTexture(GL_TEXTURE_2D, a.cloth.tex);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glBindTexture(GL_TEXTURE_2D, 0);
            }
            a.cloth.panel = panel;
            a.cloth.panelPx[0] = rect[2]; a.cloth.panelPx[1] = rect[3];
            a.cloth.uvBox[0] = float(rect[0]) / float(mw); a.cloth.uvBox[1] = float(rect[1]) / float(mh);
            a.cloth.uvBox[2] = float(rect[2]) / float(mw); a.cloth.uvBox[3] = float(rect[3]) / float(mh);
        }
    }
}

void Scene::placeActor(int id, V3 position, float facing, int furniture, V3 surface) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    // a raised actor keeps its height (the clip is lifted onto furniture); everyone else stands on the floor under them
    if (furniture == kRaised) furniture = kOnFloor;
    else position.z = floorAt(position.x, position.y, position.z);
    it->second.position = position;
    it->second.facing = facing;
    it->second.furniture = furniture;
    it->second.surface = surface;
    it->second.furnitureFacing = facing;
    it->second.lyingHalfLength = 0;
    it->second.cloth.sim.reset();   // put there, not moved there: the cloth hangs again from the pose
}

void Scene::showActor(int id, bool visible) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.visible = visible;
}

void Scene::setActorScale(int id, float scale) {
    auto it = actors_.find(id);
    if (it != actors_.end() && scale > 0) it->second.scale = scale;
}

bool Scene::actorReady(int id) const { return actors_.count(id) > 0; }

bool Scene::actorFlesh(int id, std::vector<float>& out) {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.mesh || !it->second.mesh->model) return false;
    Actor& a = it->second;
    if (a.body.shapeDirty) return false;   // the shape is made on the next frame
    const M2Model& m = *a.mesh->model;
    const bool shaped = !a.body.shaped.empty() && !a.body.indices.empty();
    // the look: the model, its shown geosets, its shaped body
    uint64_t key = 1469598103934665603ull;
    auto mix = [&key](uint64_t v) { key = (key ^ v) * 1099511628211ull; };
    mix(uint64_t(reinterpret_cast<uintptr_t>(&m)));
    for (uint16_t g : a.geosets) mix(g);
    mix(a.geosets.size());
    mix(shaped ? a.body.shaped.size() : 0);
    mix(shaped ? a.body.moved : 0);
    mix(shaped ? a.body.indices.size() : 0);
    for (size_t i = 0; i < a.body.morph.size(); i += 97) { uint32_t bits; std::memcpy(&bits, &a.body.morph[i], 4); mix(bits); }
    mix(a.eve.active ? a.eve.key : 0);   // (the Eve body: measured on its own skin)
    mix(a.hd.active ? a.hd.key : 0);     // (the HD figure: measured on its own body)
    for (float f : a.body.sculpted) { uint32_t bits; std::memcpy(&bits, &f, 4); mix(bits); }   // (the game's body sculpted)
    // the skeleton as she is drawn (bodyrig.h: Eve's proportions, Leg Length)
    BodyScale legK = a.eve.active ? a.eve.body.scale : BodyScale();
    if (a.sculpt.size() > size_t(kSculptLegLength)) legK.legs *= legLengthFactor(a.sculpt[size_t(kSculptLegLength)]);
    for (float f : {legK.legs, legK.torso, legK.arms, legK.hands, legK.spread}) { uint32_t bits; std::memcpy(&bits, &f, 4); mix(bits); }
    if (key == 0) key = 1;
    if (a.fleshKey != key) {
        const bool eve = a.eve.active && a.eve.measure.host >= 0;
        const LegEdit legs = a.hd.active && !a.hd.fit.rigPivots.empty() ? bodyEditFrom(m, a.hd.fit.rigPivots, legK)
                           : a.eve.active && !a.eve.body.rigPivots.empty() ? bodyEditFrom(m, a.eve.body.rigPivots, legK) : bodyEditOf(m, legK);
        const LegEdit* lp = legs.active ? &legs : nullptr;
        // (in Eve: her trunk and thighs read by the segments the model's own skin has where she is: flesh.h FleshGuide)
        FleshGuide guide;
        guide.byArea = eve;
        if (eve && a.eve.baseReady && a.eve.measure.verts.size() >= a.eve.body.verts.size()) {
            guide.from = a.eve.measure.verts.size() - a.eve.body.verts.size();
            guide.idx = &a.eve.baseIndices; guide.subs = &a.eve.baseSubs;
        }
        std::vector<M2Submesh> hdSubs;
        if (a.hd.active) { hdSubs.resize(1); hdSubs[0].indexCount = uint32_t(a.hd.fit.bodyIndices.size()); }
        FleshMeasure f = a.hd.active ? measureFlesh(m, a.hd.fit.verts, a.hd.fit.bodyIndices, hdSubs, std::vector<uint16_t>{0}, nullptr, lp)
                       : eve ? measureFlesh(m, a.eve.measure.verts, a.eve.measure.indices, a.eve.measure.subs, a.geosets, nullptr, lp, &guide)
                             : measureFlesh(m, shaped ? a.body.shaped : m.vertices, shaped ? a.body.indices : m.indices,
                                            shaped && !a.body.submeshes.empty() ? a.body.submeshes : m.submeshes, a.geosets, nullptr, lp);
        a.flesh = f.ok ? f.pack() : std::vector<float>();
        a.fleshKey = key;
    }
    if (a.flesh.empty()) return false;
    out = a.flesh;
    return true;
}

float Scene::actorStandHeight(int id) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return 0;
    return measureStand(*it->second.mesh->model).height();
}

// The root motion's factors for the actor's imported animation (setBoneAnim, and again when human scale is set on it).
static void fitRootMotion(float& scaleZ, float& lift, float humanXY, const M2Model& m, int root) {
    float h = root >= 0 ? m.bones[size_t(root)].pivot.z : 0;
    scaleZ = h > 0.05f ? h / kAnimRootHeight : 1.0f;
    lift = 0;
    if (humanXY <= 0) return;
    StandFit f = measureStand(m);
    if (!f.ok) return;
    scaleZ = f.pelvis / kAnimRootHeight;
    lift = f.ground < 0 ? -f.ground : 0.0f;
}

void Scene::setHumanRootMotion(int id, float xy) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    Actor& a = it->second;
    a.humanRootXY = xy > 0 && std::isfinite(xy) ? xy : 0.0f;
    if (a.boneAnim) fitRootMotion(a.boneAnimScaleZ, a.boneAnimLift, a.humanRootXY, *a.mesh->model, a.boneAnimRoot);
}

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

namespace {

uint32_t nextRandom(uint32_t& s) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return s;
}

// How long a change to sequence `seq` blends: its own blend time (the game's are 150 to 300 ms), kept within reason.
uint32_t blendTime(const M2Model& m, int seq) {
    uint32_t b = seq >= 0 && size_t(seq) < m.sequences.size() ? m.sequences[size_t(seq)].blendIn : 0;
    return b ? std::clamp<uint32_t>(b, 120, 400) : 200;
}

// How many times more a chosen variation plays (the sequence's replay range; 0 or 1: just this once).
uint32_t replaysOf(const M2Model& m, int seq, uint32_t& rng) {
    const M2Sequence& s = m.sequences[size_t(seq)];
    uint32_t lo = s.replayMin, hi = std::max(s.replayMax, s.replayMin);
    uint32_t n = std::min<uint32_t>(lo + (hi > lo ? nextRandom(rng) % (hi - lo + 1) : 0), 64);
    return n > 1 ? n - 1 : 0;
}

} // namespace

// Each change of animation blends from the pose last drawn (held, so a change in the middle of another's blend, or the
// same emote again, never jumps); `nowMs` is the caller's clock and isn't used: the animations keep their own.
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
    a.waitSeq = -1;
    // a loop asked for again goes on as it is (walking, then walking on: no restart)
    if (loop && a.loop && a.seq >= 0 && seq >= 0 && size_t(a.seq) < m.sequences.size() &&
        m.sequences[size_t(a.seq)].id == m.sequences[size_t(seq)].id) {
        a.animId = animId;
        return;
    }
    // Keys not here yet (an emote's .anim file still being fetched): drawing it now would be the bind pose, arms out. The
    // actor goes on with what it plays (or its idle) and the animation starts once the file is in (checkWaiting).
    if (seq >= 0 && !m.sequenceReady(seq)) {
        a.waitSeq = seq; a.waitAnim = animId; a.waitLoop = loop; a.waitTry = animClock_;
        bool playing = a.seq >= 0 && size_t(a.seq) < m.sequences.size() && m.sequenceReady(a.seq) && (a.loop || a.seq == seq);
        if (!playing) {
            int idle = m.findSequence(a.idleAnim);
            if (idle < 0) idle = m.findSequence(0);
            if (idle >= 0) m.ensureSequence(idle, files_);
            if (idle >= 0 && idle != seq && m.sequenceReady(idle)) startSeq(a, idle, a.idleAnim, true);
        }
        return;
    }
    startSeq(a, seq, animId, loop);
}

void Scene::startSeq(Actor& a, int seq, uint16_t animId, bool loop) {
    M2Model& m = *a.mesh->model;
    if (a.seq >= 0 && seq >= 0) blendFromCurrent(a, blendTime(m, seq));
    a.seq = seq;
    a.animId = animId;
    a.loop = loop;
    a.start = animClock_;
    a.animTime = 0;
    a.replays = seq >= 0 && loop ? replaysOf(m, seq, a.rng) : 0;
}

void Scene::blendFromCurrent(Actor& a, uint32_t ms) {
    if (!a.posed || a.pa.empty()) return;
    a.pb = a.pa;
    a.fadeStart = animClock_;
    a.fadeMs = ms;
}

void Scene::checkWaiting(int id, Actor& a, uint32_t now) {
    constexpr uint32_t kWaitRetryMs = 200;
    if (a.waitSeq < 0 || now - a.waitTry < kWaitRetryMs) return;
    a.waitTry = now;
    M2Model& m = *a.mesh->model;
    if (size_t(a.waitSeq) >= m.sequences.size()) { a.waitSeq = -1; return; }
    m.ensureSequence(a.waitSeq, files_);
    if (!m.sequenceReady(a.waitSeq)) return;
    int seq = a.waitSeq;
    a.waitSeq = -1;
    startSeq(a, seq, a.waitAnim, a.waitLoop);
}

void Scene::nextLoop(Actor& a) {
    M2Model& m = *a.mesh->model;
    if (a.replays > 0) { --a.replays; return; }
    // another variation, by how often the game plays each (frequency, of 0x7FFF): the idle's fidgets and looks round
    std::vector<int> vars = m.variations(a.seq);
    if (vars.size() < 2) return;
    uint32_t total = 0;
    std::vector<int> ready;
    for (int v : vars) {
        m.ensureSequence(v, files_);
        if (!m.sequenceReady(v)) continue;
        ready.push_back(v);
        total += m.sequences[size_t(v)].frequency;
    }
    if (ready.empty()) return;
    int pick = ready[0];
    if (total == 0) {
        pick = ready[nextRandom(a.rng) % ready.size()];
    } else {
        uint32_t r = nextRandom(a.rng) % total;
        for (int v : ready) {
            uint32_t f = m.sequences[size_t(v)].frequency;
            if (r < f) { pick = v; break; }
            r -= f;
        }
    }
    if (pick != a.seq) {
        blendFromCurrent(a, blendTime(m, pick));
        a.seq = pick;
    }
    a.replays = replaysOf(m, pick, a.rng);
}

int Scene::setBoneAnim(int id, const BoneAnim& anim, uint32_t nowMs) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return 0;
    Actor& a = it->second;
    const M2Model& m = *a.mesh->model;
    a.boneAnim = std::make_shared<BoneAnim>(anim);
    fitBoneAnim(*a.boneAnim, m);   // converted for the reference skeleton's rest pose: turned to this model's
    a.boneAnimBones.clear();
    a.boneAnimRest.assign(m.bones.size(), 0);
    int found = 0;
    for (const BoneAnimTrack& t : anim.tracks) {
        int b = m.findWowBone(t.bone);
        size_t per = t.rotation ? 4 : 3;
        if (t.times.empty() || t.values.size() < t.times.size() * per) b = -1;
        a.boneAnimBones.push_back(b);
        if (b < 0) continue;
        ++found;
        // the bone and every bone above it stand at rest unless a track moves them
        for (int i = b, guard = 0; i >= 0 && size_t(i) < m.bones.size() && guard < 256; i = m.bones[i].parent, ++guard)
            a.boneAnimRest[size_t(i)] = 1;
    }
    blendFromCurrent(a, 250);
    a.boneAnimStart = animClock_;
    int root = m.findWowBone("Root");
    float h = root >= 0 ? m.bones[size_t(root)].pivot.z : 0;
    a.boneAnimScale = h > 0.05f ? h / kAnimRootHeight : 1.0f;
    a.boneAnimRoot = root;
    fitRootMotion(a.boneAnimScaleZ, a.boneAnimLift, a.humanRootXY, m, root);
    // A held pose (no time to it) would stand as still as a statue: it breathes, the chest rising a little and the
    // neck taking it back so the head stays where the pose put it.
    a.breath.clear();
    bool held = !(anim.duration > 0);
    if (held) {
        const std::pair<const char*, float> parts[] = {{"Spine2", 0.45f}, {"Chest", 0.55f}, {"Neck1", -0.6f}, {"Head", -0.4f}};
        for (const auto& p : parts) {
            int b = m.findWowBone(p.first);
            if (b >= 0) a.breath.push_back({b, p.second});
        }
    }
    // how strongly the clip drives the head (its Head and Neck turns): eye contact gives way to it (motionlayer.h)
    float headTurn = 0;
    for (const BoneAnimTrack& t : a.boneAnim->tracks) {
        if (!t.rotation || (t.bone != "Head" && t.bone != "Neck1" && t.bone != "Neck2")) continue;
        size_t n = std::min(t.times.size(), t.values.size() / 4);
        if (n < 2) continue;
        Quat first(t.values[0], t.values[1], t.values[2], t.values[3]);
        for (size_t k = 1; k < n; ++k)
            headTurn = std::max(headTurn, motion::angleBetween(first.normalized(),
                Quat(t.values[k * 4], t.values[k * 4 + 1], t.values[k * 4 + 2], t.values[k * 4 + 3]).normalized()));
    }
    a.motion.clipWeight = motion::clipHeadWeight(headTurn);
    a.motion.idleWeight = 0.35f;
    return found;
}

void Scene::clearBoneAnim(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    blendFromCurrent(it->second, 250);
    it->second.breath.clear();
    it->second.motion.clipWeight = 1;
    it->second.motion.idleWeight = 1;
    it->second.boneAnim.reset();
    it->second.boneAnimBones.clear();
    it->second.boneAnimRest.clear();
}

void Scene::applyBoneAnim(Actor& a, uint32_t now) {
    if (!a.boneAnim) return;
    const BoneAnim& an = *a.boneAnim;
    for (size_t i = 0; i < a.pa.size() && i < a.boneAnimRest.size(); ++i)
        if (a.boneAnimRest[i]) a.pa[i] = BonePose();
    float t = float(now - a.boneAnimStart) / 1000.0f;
    if (an.loop && an.duration > 0) t = std::fmod(t, an.duration);
    for (size_t k = 0; k < an.tracks.size() && k < a.boneAnimBones.size(); ++k) {
        int b = a.boneAnimBones[k];
        if (b < 0 || size_t(b) >= a.pa.size()) continue;
        const BoneAnimTrack& tr = an.tracks[k];
        size_t ka, kb; float f;
        boneKeys(tr.times, t, an.loop ? an.duration : 0.0f, ka, kb, f);
        if (tr.rotation) {
            const float* qa = &tr.values[ka * 4];
            const float* qb = &tr.values[kb * 4];
            a.pa[size_t(b)].r = slerp(Quat(qa[0], qa[1], qa[2], qa[3]).normalized(), Quat(qb[0], qb[1], qb[2], qb[3]).normalized(), f);
        } else {
            const float* pa = &tr.values[ka * 3];
            const float* pb = &tr.values[kb * 3];
            V3 p = lerp(V3(pa[0], pa[1], pa[2]), V3(pb[0], pb[1], pb[2]), f);
            if (a.humanRootXY > 0 && b == a.boneAnimRoot) {
                // human scale: across the floor in the pair's shared yards (whatever this body's size), up and down by
                // its own pelvis, and lifted where its straightened legs would reach under the floor
                float xy = a.humanRootXY / (a.scale > 1e-4f ? a.scale : 1.0f);
                a.pa[size_t(b)].t = V3(p.x * xy, p.y * xy, p.z * a.boneAnimScaleZ + a.boneAnimLift);
            } else {
                a.pa[size_t(b)].t = p * a.boneAnimScale;
            }
        }
    }
    // breathing: about 14 breaths a minute, the chest turning by about a degree about the body's side-to-side axis
    if (!a.breath.empty() && !a.motion.on) {   // (the motion layer breathes for it when it is on)
        float s = float(now - a.boneAnimStart) / 1000.0f;
        float angle = 0.018f * std::sin(s * 6.2831853f / 4.3f);
        for (const auto& [b, share] : a.breath) {
            if (b < 0 || size_t(b) >= a.pa.size()) continue;
            float h = angle * share * 0.5f;
            Quat q(0, std::sin(h), 0, std::cos(h));
            const Quat& r = a.pa[size_t(b)].r;
            a.pa[size_t(b)].r = Quat(r.w * q.x + r.x * q.w + r.y * q.z - r.z * q.y, r.w * q.y - r.x * q.z + r.y * q.w + r.z * q.x,
                                     r.w * q.z + r.x * q.y - r.y * q.x + r.z * q.w, r.w * q.w - r.x * q.x - r.y * q.y - r.z * q.z).normalized();
        }
    }
}

void Scene::setSoftBody(int level) {
    softLevel_ = std::clamp(level, int(kSoftOff), int(kSoftStrong));
}

void Scene::setCharacter(int id, bool character) {
    auto it = actors_.find(id);
    if (it == actors_.end() || it->second.character == character) return;
    Actor& a = it->second;
    a.character = character;
    a.soft.dirty = true;                                   // a character finds its regions again
    if (!character) { a.soft.sim.setRegions({}); a.soft.sim.reset(); }
}

// The soft body's regions (again after a new look or shape, once the shape's vertices are made) and this frame's step.
// A creature has none: its regions are never looked for (a boar's or a wolf's bones and skin can look like a chest's).
void Scene::stepSoft(Actor& a, uint32_t now) {
    if (!a.character) return;
    if (a.soft.sim.level() != softLevel_) { a.soft.sim.setLevel(softLevel_); a.soft.sim.reset(); }
    if (softLevel_ == kSoftOff) return;
    const M2Model& m = *a.mesh->model;
    if (a.soft.dirty && !a.body.dirty && !a.body.shapeDirty) {
        const BodyGl& b = a.body;
        bool own = !b.shaped.empty() && !b.indices.empty() && !b.submeshes.empty();
        if (a.hd.active && a.hd.fit.bodyVertexEnd > 0) {
            // the HD body's skin is what moves: its regions found on it, the breasts measured on its own vertices
            std::vector<M2Submesh> one(1);
            one[0].indexCount = uint32_t(a.hd.fit.bodyIndices.size());
            a.soft.rebuild(m, a.hd.fit.verts, a.hd.fit.bodyIndices, one, std::vector<uint16_t>{0}, glReady_);
            std::vector<SoftRegion> rg = a.soft.sim.regions();
            if (hdBreastRegions(m, a.hd.fit.verts, a.hd.fit.bodyVertexEnd, rg)) a.soft.sim.setRegions(rg);
        } else if (a.eve.active && a.eve.measure.host >= 0) {
            // the Eve body's skin is what moves: its regions found on it, the breasts measured on its own vertices
            a.soft.rebuild(m, a.eve.measure.verts, a.eve.measure.indices, a.eve.measure.subs, a.geosets, glReady_);
            std::vector<SoftRegion> rg = a.soft.sim.regions();
            if (eveBreastRegions(m, a.eve.body, rg)) a.soft.sim.setRegions(rg);
        } else {
            a.soft.rebuild(m, b.shaped.empty() ? m.vertices : b.shaped, own ? b.indices : m.indices,
                           own ? b.submeshes : m.submeshes, a.geosets, glReady_);
        }
    }
    M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    a.soft.sim.step(a.mats, w, now);
}

void Scene::setCamera(V3 eye, V3 target, float fovDegrees) {
    eye_ = eye; target_ = target; fov_ = fovDegrees;
}

void Scene::animate(Actor& a, uint32_t now) {
    if (a.animating) return;     // (a reach and its target reaching at each other)
    a.animating = true;
    struct Done { Actor& a; uint32_t now; ~Done() { a.animating = false; a.animatedAt = now; } } done{a, now};
    M2Model& m = *a.mesh->model;
    bool hasSeq = a.seq >= 0 && size_t(a.seq) < m.sequences.size();
    // the skeleton edited for this actor (bodyrig.h): the Eve body's proportions and the sculpt's Leg Length on them, made
    // again when either changes
    {
        BodyScale k = a.eve.active ? a.eve.body.scale : BodyScale();
        if (a.sculpt.size() > size_t(kSculptLegLength)) k.legs *= legLengthFactor(a.sculpt[size_t(kSculptLegLength)]);
        // (an Eve body with her own rig: the animation runs about her joints, evebody.h EveBody::rigPivots)
        // (an HD figure: the animation runs about its joints, hdbody.h HdFit::rigPivots)
        const std::vector<V3>* rig = a.hd.active && !a.hd.fit.rigPivots.empty() ? &a.hd.fit.rigPivots
                                   : a.eve.active && !a.eve.body.rigPivots.empty() ? &a.eve.body.rigPivots : nullptr;
        const void* base = rig ? rig->data() : nullptr;
        if (a.legEdit.model != &m || !(a.legEdit.scale == k) || a.legEdit.base != base) a.legEdit = rig ? bodyEditFrom(m, *rig, k) : bodyEditOf(m, k);
    }
    if (!hasSeq && !a.boneAnim) {
        if (a.legEdit.active) legEditMatrices(m.bones, std::vector<BonePose>(), a.legEdit, a.mats);
        else a.mats.assign(std::max<size_t>(1, m.bones.size()), M4());
        if (a.mats.empty()) a.mats.push_back(M4());
        return;
    }
    if (!hasSeq) {
        a.pa.assign(m.bones.size(), BonePose());
    } else {
        uint32_t dur = std::max<uint32_t>(1, m.sequences[a.seq].duration);
        uint32_t t = now - a.start;
        if (a.loop) {
            // each time round: the same again, or (its replays done) another of its variations
            for (int guard = 0; t >= dur && guard < 64; ++guard) {
                a.start += dur;
                nextLoop(a);
                dur = std::max<uint32_t>(1, m.sequences[a.seq].duration);
                t = now - a.start;
            }
            t %= dur;
        } else {
            t = std::min(t, dur - 1);
        }
        a.animTime = t;
        // a sequence without its keys would be the bind pose: the pose last drawn holds instead
        if (m.sequenceReady(a.seq) && m.isLayer(a.seq)) {
            // a layer (FacePose, HandsClosed...) keys part of the body: the rest stands as the idle does (else the pose
            // last drawn), never in the bind pose with its arms out
            int idle = m.findSequence(a.idleAnim);
            if (idle < 0) idle = m.findSequence(0);
            if (idle >= 0) m.ensureSequence(idle, files_);
            if (idle >= 0 && idle != a.seq && m.sequenceReady(idle) && !m.isLayer(idle)) {
                uint32_t idur = std::max<uint32_t>(1, m.sequences[size_t(idle)].duration);
                m.pose(idle, (now - a.start) % idur, now, a.pl, true);
            } else if (a.posed && a.pa.size() == m.bones.size()) {
                a.pl = a.pa;
            } else {
                a.pl.assign(m.bones.size(), BonePose());
            }
            m.pose(a.seq, t, now, a.pa, a.loop, &a.pl);
        } else if (m.sequenceReady(a.seq) || !a.posed || a.pa.size() != m.bones.size()) {
            m.pose(a.seq, t, now, a.pa, a.loop);
        }
    }
    applyBoneAnim(a, now);
    // a change of animation: from the pose last drawn to this one, eased in and out; turns by slerp, the rest linearly
    if (a.fadeMs && now - a.fadeStart < a.fadeMs && a.pb.size() == a.pa.size()) {
        float f = float(now - a.fadeStart) / float(a.fadeMs);
        f = f * f * (3 - 2 * f);
        for (size_t i = 0; i < a.pa.size(); ++i) {
            a.pa[i].t = lerp(a.pb[i].t, a.pa[i].t, f);
            a.pa[i].r = slerp(a.pb[i].r, a.pa[i].r, f);
            a.pa[i].s = lerp(a.pb[i].s, a.pa[i].s, f);
        }
    } else {
        a.fadeMs = 0;
    }
    a.posed = true;
    holdBoneLayer(a, now);
    if (a.legEdit.active) legEditMatrices(m.bones, a.pa, a.legEdit, a.mats);
    else m.bonesToMatrices(a.pa, a.mats);
    if (a.mats.empty()) a.mats.push_back(M4());
    layMotion(a, now);
    layBoneLayer(a, now);
    layReach(a, now);
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

void Scene::alignWithFurniture(Actor& a) {
    if (a.furniture == kOnFloor || a.mats.empty()) return;
    const M2Model& model = *a.mesh->model;
    constexpr uint16_t sleep = 100, sitChairMed = 103;
    const uint16_t desired = a.furniture == kLying ? sleep : sitChairMed;
    if (!a.boneAnim && (a.waitSeq >= 0 || a.seq < 0 || size_t(a.seq) >= model.sequences.size() ||
                       model.sequences[a.seq].id != desired)) return;
    const auto bone = [&](int& index, const char* name) {
        if (index == -2) index = model.findWowBone(name);
        return index >= 0 && size_t(index) < a.mats.size();
    };
    const auto point = [&](int index) { return a.mats[index].point(model.bones[index].pivot); };
    if (a.pelvisBone == -2) {
        a.pelvisBone = model.findWowBone("Pelvis");
        if (a.pelvisBone < 0) a.pelvisBone = model.findWowBone("Root");
    }
    if (a.pelvisBone < 0 || size_t(a.pelvisBone) >= a.mats.size()) return;
    const V3 pelvis = point(a.pelvisBone);
    if (a.furniture == kSitting) {
        // A standing or still-blending pose would put the actor's feet below the floor.
        if (model.bones[a.pelvisBone].pivot.z + a.legEdit.lift - pelvis.z < 0.12f) return;   // (Leg Length's lift: bodyrig.h)
        V3 offset = M4::rotationZ(a.furnitureFacing).point(pelvis) * a.scale;
        a.position = a.surface - offset + V3(0, 0, a.scale * 0.12f);
        return;
    }

    if (!bone(a.headBone, "Head")) return;
    const V3 head = point(a.headBone);
    const V3 chest = bone(a.chestBone, "Chest") ? point(a.chestBone) : lerp(pelvis, head, 0.55f);
    const V3 torso = head - pelvis;
    const float horizontal = std::hypot(torso.x, torso.y);
    if (horizontal < 0.1f || std::fabs(torso.z) > horizontal * 0.7f) return;
    const V3 left = bone(a.leftFootBone, "L_Foot") ? point(a.leftFootBone) : pelvis - torso;
    const V3 right = bone(a.rightFootBone, "R_Foot") ? point(a.rightFootBone) : pelvis - torso;
    const V3 feet = (left + right) * 0.5f;
    V3 center = (feet + head) * 0.5f;
    center.z = std::min({pelvis.z - 0.13f, chest.z - 0.18f, head.z - 0.14f, left.z - 0.07f, right.z - 0.07f});
    a.lyingHalfLength = ((head - feet).length() * 0.5f + 0.25f) * a.scale;
    a.facing = a.furnitureFacing - std::atan2(torso.y, torso.x);
    a.position = a.surface - M4::rotationZ(a.facing).point(center) * a.scale + V3(0, 0, 0.015f);
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

// The tabard flap's geoset group and its depth offset (glPolygonOffset: negative is nearer the eye).
constexpr int kTabardGroup = 12;
constexpr float kTabardOffsetFactor = -2.0f, kTabardOffsetUnits = -32.0f;

void Scene::drawModel(ModelGl& mg, const M4& world, const std::map<uint32_t, uint32_t>* overrides,
                      const std::vector<uint16_t>* geosets, unsigned boneTex, int seq, uint32_t t,
                      uint32_t now, const float tint[4], bool transparent, int texSize,
                      const std::map<uint32_t, unsigned>* glTextures, unsigned vao,
                      const std::vector<M2Submesh>* submeshes) {
    const M2Model& m = *mg.model;
    const std::vector<M2Submesh>& subs = submeshes ? *submeshes : m.submeshes;   // (a shaped body's own ranges)
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uWorld"), 1, GL_FALSE, world.m);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, boneTex);
    glUniform1i(loc(p, "uBones"), 1);
    glBindVertexArray(vao ? vao : mg.vao);
    for (const M2Batch& b : m.batches) {
        if (b.submesh >= subs.size()) continue;
        const M2Submesh& sm = subs[b.submesh];
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
        if (softBodyDraw_) SoftDraw::setGain(p, softGeosetGain(sm.id));
        if (blend) { glEnable(GL_BLEND); blendFor(mat.blend); glDepthMask(GL_FALSE); }
        if (mat.flags & 16) glDepthMask(GL_FALSE);
        // A character's tabard flap (geoset group 12: 1202..1204) lies on the hips' own surface at the waist: drawn a little
        // nearer the eye, it stays over the skin and the trousers there instead of fighting them in steps.
        const bool flap = geosets && sm.id / 100 == kTabardGroup;
        if (flap) { glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(kTabardOffsetFactor, kTabardOffsetUnits); }
        // Options' Graphics: a person's skin (texture type 1), hair (6) and eyes (19) shaded as such (kLitFs's skinShade);
        // the skin also while the person drawn has a sheen or the thin-skin tint can find their thin parts
        const bool skinFx = gfx_.skin > 0 || (drawSkin_[3] > 0.5f && gfx_.thinSkin > 0) || drawSkin_[0] > 0;
        const int skinKind = skinFx && tex.type == 1 ? 1 : gfx_.hair > 0 && tex.type == 6 ? 2 : gfx_.eyes > 0 && tex.type == 19 ? 3 : 0;
        if (skinKind == 1) {
            glUniform4fv(loc(p, "uSkinActor"), 1, drawSkin_);
            if (drawSkin_[3] > 0.5f) {
                glUniformMatrix4fv(loc(p, "uThinHead"), 1, GL_FALSE, drawThin_);
                glUniform4fv(loc(p, "uThinHands"), 2, drawThin_ + 16);
            }
        }
        if (skinKind == 3) glUniform3fv(loc(p, "uEyeC"), 2, drawEyes_);
        if (skinKind) glUniform1i(loc(p, "uSkinKind"), skinKind);
        glDrawElements(GL_TRIANGLES, GLsizei(sm.indexCount), GL_UNSIGNED_INT, (void*)(size_t(sm.indexStart) * 4));
        if (skinKind) glUniform1i(loc(p, "uSkinKind"), 0);
        if (flap) { glPolygonOffset(0, 0); glDisable(GL_POLYGON_OFFSET_FILL); }
        if (blend) glDisable(GL_BLEND);
        glDepthMask(GL_TRUE);
    }
    glBindVertexArray(0);
}

void Scene::drawAttached(Actor& a, const M4& world, uint32_t now, bool transparent) {
    const M2Model& m = *a.mesh->model;
    const float* one = ghostTint_;   // (white but for the ghost)
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
    float bodyTint[4];   // (white but for the ghost)
    for (int k = 0; k < 4; ++k) bodyTint[k] = ghostTint_[k] * a.parts.tint[k];   // (the stand-in's skin match: setBodyTint)
    M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    uint32_t dur = std::max<uint32_t>(1, a.seq >= 0 ? a.mesh->model->sequences[a.seq].duration : 1);
    // The soft body: the body, its layer and its skinned models move with it; hung items don't.
    bool soft = a.soft.active();
    if (soft) { glUseProgram(m2Prog_); a.soft.uniforms(m2Prog_, a.soft.bodyGate()); }
    softBodyDraw_ = soft;
    skinOf(a, w);   // (scene_gfx.cpp: the sheen, the eyes, the thin parts)
    // (a flap hung as cloth is drawn from its simulated points instead of skinned: drawCloth)
    if (a.hd.active) {
        // the HD figure instead of the game's model, its clothing and parts (hdscene.cpp); what it holds still hangs on it
        if (!transparent) drawHd(a, w, bodyTint);   // (with the soft body's uniforms still set)
        // an HD body whose own genitals are hidden (setHdGenitalMask): the parts drawn in their place, on the game skeleton
        // the HD figure is fitted to (hdscene.cpp hdCheck fits the HD body again without its own genitals' triangles)
        if (!a.parts.hdGenitalMask.empty()) drawParts(a, w, transparent, nowMs, bodyTint);
        softBodyDraw_ = false;
        if (soft) SoftDraw::off(m2Prog_);
        std::fill(drawSkin_, drawSkin_ + 4, 0.0f);
        drawAttached(a, w, nowMs, transparent);
        return;
    }
    const bool cloth = a.cloth.active();
    const bool morph = sculptMorphDraw(a);   // (the live sculpt's change on the GPU: bodyrig.cpp)
    drawModel(*a.mesh, w, &a.drawTextures, cloth ? &a.cloth.bodyGeosets : &a.geosets, a.boneTex, a.seq, a.animTime % dur, nowMs, bodyTint,
              transparent, kActorTex, &a.composed, a.body.vao, a.body.vao ? &a.body.submeshes : nullptr);
    if (morph) { glUseProgram(m2Prog_); glUniform1i(loc(m2Prog_, "uMorphOn"), 0); }
    if (cloth && !transparent) drawCloth(a, w, bodyTint);
    if (!transparent && a.eve.active) drawEve(a, w, bodyTint);   // (with the soft body's uniforms still set)
    softBodyDraw_ = false;
    if (soft) SoftDraw::setGain(m2Prog_, 1.0f);
    if (transparent) drawBodyLayer(a, w);   // (the skinned models below are the person's too: drawSkin_ still theirs)
    for (size_t i = 0; i < a.skinned.size(); ++i) {
        Skinned& e = a.skinned[i];
        std::map<uint32_t, uint32_t> tex = a.drawTextures;
        if (e.texture)
            for (const M2Texture& t : e.mesh->model->textures)
                if (t.type && !tex.count(t.type) && !a.composed.count(t.type)) tex[t.type] = e.texture;
        if (soft) a.soft.uniforms(m2Prog_, a.soft.skinnedGate(i, e.mesh, e.boneMap));
        drawModel(*e.mesh, w, &tex, &e.geosets, e.boneTex, -1, 0, nowMs, bodyTint, transparent, kActorTex, &a.composed);
    }
    // the body parts with the body's own tint and skin shading (drawSkin_ still the person's)
    drawParts(a, w, transparent, nowMs, bodyTint);
    if (soft) SoftDraw::off(m2Prog_);
    std::fill(drawSkin_, drawSkin_ + 4, 0.0f);
    drawAttached(a, w, nowMs, transparent);
}

bool Scene::creatorBackdrop(uint32_t fileId) {
    creatorBackdrop_ = nullptr;
    if (fileId == 0) return true;
    auto* loaded = model(fileId);
    if (!loaded || !loaded->model->previewCamera) return false;
    creatorBackdrop_ = loaded;
    return true;
}

void Scene::drawCreatorBackdrop(float aspect, uint32_t nowMs) {
    if (!creatorBackdrop_) return;
    auto& mesh = *creatorBackdrop_;
    const auto& camera = *mesh.model->previewCamera;
    const float fovY = 2.0f * std::atan(std::tan(camera.diagonalFov * 0.5f) / std::sqrt(1.0f + aspect * aspect));
    const float nearClip = camera.nearClip > 0 ? camera.nearClip : 0.1f;
    const float farClip = camera.farClip > nearClip ? camera.farClip : 4000.0f;
    const V3 forward = (camera.target - camera.eye).normalized();
    const V3 worldUp = std::abs(forward.z) < 0.999f ? V3(0, 0, 1) : V3(0, 1, 0);
    const V3 right = forward.cross(worldUp).normalized();
    const V3 up = right.cross(forward);
    const float roll = std::isfinite(camera.roll) ? camera.roll : 0.0f;
    const V3 cameraUp = up * std::cos(roll) + right * std::sin(roll);
    const M4 viewProj = M4::perspective(fovY, aspect, nearClip, farClip) * M4::lookAt(camera.eye, camera.target, cameraUp);
    Light light;
    light.ambient[0] = light.ambient[1] = light.ambient[2] = 1.0f;
    light.sunColor[0] = light.sunColor[1] = light.sunColor[2] = 0.0f;
    light.fogStart = farClip;
    light.fogEnd = farClip + 1.0f;
    litUniforms(m2Prog_, light, camera.eye, viewProj);
    const float white[] = {1, 1, 1, 1};
    drawModel(mesh, M4(), nullptr, nullptr, mesh.boneTex, -1, 0, nowMs, white, false, kActorTex);
    drawModel(mesh, M4(), nullptr, nullptr, mesh.boneTex, -1, 0, nowMs, white, true, kActorTex);
    // The actor has a separate camera so zooming or turning it leaves the backdrop in place.
    glClear(GL_DEPTH_BUFFER_BIT);
}

void Scene::render(int width, int height, uint32_t nowMs) {
    if (!glReady_) return;
    // Looks dressed since the last frame, and files a look was waiting for that have come. Composing a skin is the
    // frame's heaviest work (its layers drawn and mipmapped: tens of ms on a slow GPU), so at most kSkinsPerFrame are
    // composed a frame: six NPCs dressed at once are spread over six frames, not one long hitch. An actor whose skin was
    // never composed waits unseen (Actor::firstSkin) rather than being drawn bare for those frames.
    constexpr int kSkinsPerFrame = 1;
    int skinsLeft = kSkinsPerFrame;
    for (auto& [id, a] : actors_) {
        if (!a.skinDirty && !a.missing.empty())
            for (uint32_t f : a.missing) if (!files_(f).empty()) { a.skinDirty = true; break; }
        if (a.skinDirty && skinsLeft > 0) { refreshSkin(a); --skinsLeft; }
        if (!a.skinDirty) a.firstSkin = false;
        if (!a.body.dirty && !a.body.missing.empty())
            for (uint32_t f : a.body.missing) if (!files_(f).empty()) { a.body.dirty = true; break; }
        sculptSync(a); // (the creator's sculpt taken: bodyrig.cpp)
        eveCheck(a);   // (the Eve body's look or options changed: the body is cut and fitted again)
        hdCheck(a);    // (the HD figure's model, set or options changed: fitted again; hdscene.cpp)
        if (a.body.dirty) refreshBody(a);
        bool arrived = false;
        for (const DressModel& d : a.pendingModels) if (!files_(d.model).empty()) { arrived = true; break; }
        if (arrived) dressModels(a);
    }
    viewW_ = std::max(1, width); viewH_ = std::max(1, height);
    // cinematic mode (setCinematic): the area's profile for the frame, its flicker, and the frame drawn offscreen for the grade
    if (cineMood_ > 0) updateCinematic(nowMs);
    cineGrade_.seconds = float(nowMs % 1000000u) / 1000.0f;
    const bool graded = cineGrade_.active() && grade_.begin(width, height);
    // Options' Graphics (scene_gfx.cpp): the key light's shadow pass, and the scene drawn into the offscreen chain at the
    // render scale's size (width and height become that size until the chain's last pass draws it at the view's)
    const int outW = width, outH = height;
    const bool post = gfxBegin(width, height, nowMs);
    glViewport(0, 0, width, height);
    glClearColor(light_.skyHorizon[0], light_.skyHorizon[1], light_.skyHorizon[2], 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);

    view_ = M4::lookAt(eye_, target_, V3(0, 0, 1));
    proj_ = M4::perspective(fov_ * 3.14159265f / 180.0f, height ? float(width) / float(height) : 1, 0.2f, 4000.0f);
    M4 viewProj = proj_ * view_;

    if (creatorBackdrop_) drawCreatorBackdrop(height ? float(width) / float(height) : 1.0f, nowMs);
    else drawSky();

    // The animations' clock: this frame's time since the last, never backwards, at most kMaxFrameStep (a slow frame), and
    // a gap longer than kPause (the app paused or the screen off) counts as one frame like the last: the animations go
    // on from where they were, not jumping ahead. A 60 Hz and a 120 Hz screen move it by the same time a second.
    constexpr uint32_t kMaxFrameStep = 100, kPause = 250;
    if (haveNow_ && nowMs > lastNow_) {
        uint32_t step = nowMs - lastNow_;
        if (step > kPause) step = lastStep_;
        step = std::min(step, kMaxFrameStep);
        animClock_ += step;
        lastStep_ = step;
    }
    lastNow_ = nowMs;
    haveNow_ = true;
    const uint32_t clock = animClock_;

    // Actors' skeletons for this frame (non-looping animations return to the idle one).
    for (auto& [id, a] : actors_) {
        if (!a.visible) continue;
        M2Model& m = *a.mesh->model;
        checkWaiting(id, a, clock);
        if (!a.loop && a.seq >= 0 && size_t(a.seq) < m.sequences.size() &&
            clock - a.start >= std::max<uint32_t>(1, m.sequences[a.seq].duration)) {
            // (an animation still waiting for its file keeps waiting: the idle meanwhile)
            int idle = a.waitSeq >= 0 ? m.findSequence(a.idleAnim) : -1;
            if (a.waitSeq < 0) playAnim(id, a.idleAnim, true, clock);
            else if (idle >= 0 && m.sequenceReady(idle)) startSeq(a, idle, a.idleAnim, true);
            else a.loop = true;
        }
        if (a.animatedAt != clock) animate(a, clock);   // (a reach's target is posed before the one reaching: layReach)
        alignWithFurniture(a);
        stepSoft(a, clock);   // the pause-proof animation clock, not the wall clock
        stepCloth(a, clock);
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
    for (auto& [id, a] : actors_) if (a.visible && !a.ghost && !a.firstSkin) { lightActor(a.position, false); drawActor(a, nowMs, false); }
    lightActor(V3(), true);
    drawStatic(false, viewProj);    // the location, its doodads and the editor's props (location.cpp)
    drawGround(viewProj);
    drawSelection(viewProj);
    litUniforms(wmoProg_, light_, eye_, viewProj);
    drawCity(true);
    litUniforms(m2Prog_, light_, eye_, viewProj);
    for (Prop& pr : props_) if (pr.seen) drawModel(*pr.mesh, pr.world, nullptr, nullptr, pr.mesh->boneTex, -1, 0, nowMs, one, true, kPropTex);
    drawStatic(true, viewProj);
    for (auto& [id, a] : actors_) if (a.visible && !a.ghost && !a.firstSkin) { lightActor(a.position, false); drawActor(a, nowMs, true); }
    lightActor(V3(), true);
    // the atmosphere's motes (particles.h): stepped on the animations' clock round what the camera looks at, drawn soft and
    // additive under the grade
    if (particleStrength_ > 0 && !particles_.motes().empty()) {
        particles_.setBox(target_ + V3(0, 0, 0.4f), V3(2.2f, 2.2f, 1.1f));
        particles_.step(float(clock - particleClock_) / 1000.0f);
        float pixelsPerYard = float(height) * 0.5f / std::tan(fov_ * 3.14159265f / 360.0f);
        particleDraw_.draw(particles_, viewProj, eye_, pixelsPerYard, particleStrength_);
    }
    particleClock_ = clock;
    // interactable sparkles (sparkle.h): made from the clock alone, over the spots the screen lists
    if (!sparkles_.empty()) {
        sparkBuf_.clear();
        sparkles_.generate(float(clock) / 1000.0f, sparkBuf_);
        float pixelsPerYard = float(height) * 0.5f / std::tan(fov_ * 3.14159265f / 360.0f);
        sparkleDraw_.draw(sparkBuf_, viewProj, eye_, pixelsPerYard);
    }
    // the placement editor's ghost and gizmo (editview.cpp), over the scene
    litUniforms(m2Prog_, light_, eye_, viewProj);
    drawGhost(viewProj, nowMs);
    drawGizmo();
    drawBuild();   // the live scene's build mode: its gizmo, markers and selected box (buildview.cpp)
    if (post) { gfxEnd(outW, outH, nowMs); width = outW; height = outH; }
    if (graded) grade_.end(width, height, cineGrade_);
}

// ---------------------------------------------------------------------------------------------- additive layers

void Scene::setMotion(int id, float intensity, int target) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    MotionState& st = it->second.motion;
    if (!st.on) { st.on = true; st.seed = uint32_t(id) * 7919u + 17u; st.started = false; }
    st.intensity = std::isfinite(intensity) ? std::clamp(intensity, 0.0f, 1.0f) : st.intensity;
    st.target = target == id ? 0 : target;
}

void Scene::clearMotion(int id) {
    auto it = actors_.find(id);
    if (it != actors_.end()) { it->second.motion.on = false; it->second.motion.yaw = it->second.motion.pitch = 0; }
}

// ---------------------------------------------------------------------------------------------- bone layer and reach

namespace {
// A track's bone in `m`: a WoW-side name, or "#N" (the model's bone N).
int layerBoneOf(const M2Model& m, const std::string& name) {
    if (!name.empty() && name[0] == '#') {
        int i = std::atoi(name.c_str() + 1);
        return i >= 0 && size_t(i) < m.bones.size() ? i : -1;
    }
    return m.findWowBone(name);
}
// The bone and every bone under it.
std::vector<int> subtreeOf(const M2Model& m, int root) {
    std::vector<int> out;
    if (root < 0 || size_t(root) >= m.bones.size()) return out;
    std::vector<uint8_t> in(m.bones.size(), 0);
    in[size_t(root)] = 1;
    // (parents come before their children in the file's order, but not always: repeat until nothing is added)
    for (bool grew = true; grew;) {
        grew = false;
        for (size_t i = 0; i < m.bones.size(); ++i) {
            int p = m.bones[i].parent;
            if (!in[i] && p >= 0 && size_t(p) < in.size() && in[size_t(p)]) { in[i] = 1; grew = true; }
        }
    }
    for (size_t i = 0; i < in.size(); ++i) if (in[i]) out.push_back(int(i));
    return out;
}
}

int Scene::setBoneLayer(int id, const BoneAnim& layer, uint32_t nowMs) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return 0;
    Actor& a = it->second;
    const M2Model& m = *a.mesh->model;
    a.layer = std::make_shared<BoneAnim>(layer);
    a.layerBones.clear();
    a.layerSubtrees.clear();
    a.layerFreeze.clear();
    a.layerFrozen.clear();
    a.layerHold = -1;
    a.layerCaptured = false;
    a.layerLastT = 0;
    int found = 0;
    for (size_t k = 0; k < layer.tracks.size(); ++k) {
        const BoneAnimTrack& t = layer.tracks[k];
        if (!t.rotation && t.bone == "layer.hold") a.layerHold = int(k);
        const bool freeze = t.rotation && !t.bone.empty() && t.bone[0] == '!';
        int b = t.rotation && !t.times.empty() && t.values.size() >= t.times.size() * 4 ? layerBoneOf(m, freeze ? t.bone.substr(1) : t.bone) : -1;
        if (freeze && b >= 0) { a.layerFreeze.push_back(b); a.layerFrozen.push_back(Quat()); ++found; b = -1; }
        a.layerBones.push_back(b);
        a.layerSubtrees.push_back(b >= 0 ? subtreeOf(m, b) : std::vector<int>());
        if (b >= 0) ++found;
    }
    a.layerStart = nowMs;
    return found;
}

void Scene::clearBoneLayer(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    it->second.layer.reset();
    it->second.layerBones.clear();
    it->second.layerSubtrees.clear();
    it->second.layerFreeze.clear();
    it->second.layerFrozen.clear();
}

void Scene::holdBoneLayer(Actor& a, uint32_t now) {
    if (!a.layer || a.ghost || a.layerFreeze.empty() || a.pa.size() != a.mesh->model->bones.size()) return;
    const float t = layer::layerTime(now, a.layerStart, a.layer->duration, a.layer->loop);
    // each loop's start (and the first frame): the held bones' pose as their own animation has it now
    if (!a.layerCaptured || t < a.layerLastT - 0.05f) {
        for (size_t i = 0; i < a.layerFreeze.size(); ++i) a.layerFrozen[i] = a.pa[size_t(a.layerFreeze[i])].r;
        a.layerCaptured = true;
    }
    a.layerLastT = t;
    float h = 1.0f;
    if (a.layerHold >= 0) h = std::clamp(layer::samplePosition(a.layer->tracks[size_t(a.layerHold)], t, a.layer->loop ? a.layer->duration : 0.0f, V3(0, 0, 0)).x, 0.0f, 1.0f);
    if (!(h > 1e-4f)) return;
    for (size_t i = 0; i < a.layerFreeze.size(); ++i) {
        BonePose& p = a.pa[size_t(a.layerFreeze[i])];
        p.r = slerp(p.r, a.layerFrozen[i], h);
    }
}

void Scene::layBoneLayer(Actor& a, uint32_t now) {
    if (!a.layer || a.ghost || a.mats.size() != a.mesh->model->bones.size()) return;
    const M2Model& m = *a.mesh->model;
    const float t = layer::layerTime(now, a.layerStart, a.layer->duration, a.layer->loop);
    const float loopLen = a.layer->loop ? a.layer->duration : 0.0f;
    for (size_t k = 0; k < a.layer->tracks.size() && k < a.layerBones.size(); ++k) {
        int b = a.layerBones[k];
        if (b < 0 || size_t(b) >= a.mats.size()) continue;
        Quat q = layer::sampleRotation(a.layer->tracks[k], t, loopLen);
        if (std::fabs(q.w) > 0.999999f) continue;
        M4 d = layer::rotateAbout(a.mats[size_t(b)].point(m.bones[size_t(b)].pivot), q);
        for (int i : a.layerSubtrees[k]) if (size_t(i) < a.mats.size()) a.mats[size_t(i)] = d * a.mats[size_t(i)];
    }
}

int Scene::setReach(int id, int targetId, int targetBone, bool left, const BoneAnim& keys, uint32_t nowMs) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return 0;
    Actor& a = it->second;
    const M2Model& m = *a.mesh->model;
    Actor::Reach r;
    const char* side = left ? "L_" : "R_";
    r.upper = m.findWowBone(std::string(side) + "UpperArm");
    r.fore = m.findWowBone(std::string(side) + "Forearm");
    r.hand = m.findWowBone(std::string(side) + "Hand");
    r.fingers = m.findWowBone(std::string(side) + "Middle1");
    if (r.upper < 0 || r.fore < 0 || r.hand < 0 || r.fingers < 0) { a.reach = Actor::Reach(); return 0; }
    r.on = true;
    r.target = targetId;
    r.targetBone = targetBone;
    r.left = left;
    r.keys = std::make_shared<BoneAnim>(keys);
    r.start = nowMs;
    r.upperSub = subtreeOf(m, r.upper);
    r.foreSub = subtreeOf(m, r.fore);
    r.handSub = subtreeOf(m, r.hand);
    a.reach = std::move(r);
    return 1;
}

void Scene::clearReach(int id) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.reach = Actor::Reach();
}

bool Scene::actorPlacement(int id, V3& position, float& facing, float& scale) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    position = it->second.position; facing = it->second.facing; scale = it->second.scale;
    return true;
}

bool Scene::reachState(int id, V3& palm, V3& target, float& weight, bool& reachable) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.reach.on || !it->second.reach.solved) return false;
    palm = it->second.reach.palm; target = it->second.reach.aimed; weight = it->second.reach.weight; reachable = it->second.reach.reachable;
    return true;
}

void Scene::layReach(Actor& a, uint32_t now) {
    Actor::Reach& r = a.reach;
    r.solved = false;
    if (!r.on || !r.keys || a.ghost || a.mats.size() != a.mesh->model->bones.size()) return;
    auto tit = actors_.find(r.target);
    if (tit == actors_.end() || !tit->second.visible || !tit->second.mesh || !tit->second.mesh->model) return;
    Actor& o = tit->second;
    const M2Model& om = *o.mesh->model;
    if (r.targetBone < 0 || size_t(r.targetBone) >= om.bones.size()) return;
    if (o.animatedAt != now) animate(o, now);    // the target as it is posed this very frame
    if (o.mats.size() != om.bones.size()) return;
    const M2Model& m = *a.mesh->model;
    const float t = layer::layerTime(now, r.start, r.keys->duration, r.keys->loop);
    const float loopLen = r.keys->loop ? r.keys->duration : 0.0f;
    V3 wk(0, 0, 0), off(0, 0, 0), along(1, 0, 0), pole(0, 0, 0);
    for (const BoneAnimTrack& tr : r.keys->tracks) {
        if (tr.rotation) continue;
        if (tr.bone == "reach.w") wk = layer::samplePosition(tr, t, loopLen, wk);
        else if (tr.bone == "reach.off") off = layer::samplePosition(tr, t, loopLen, off);
        else if (tr.bone == "reach.along") along = layer::samplePosition(tr, t, loopLen, along);
        else if (tr.bone == "reach.pole") pole = layer::samplePosition(tr, t, loopLen, pole);
    }
    const float w = std::clamp(wk.x, 0.0f, 1.0f);
    r.weight = w;
    // the target in the person's model space: the bone's pivot plus the offset (the target model's rest axes) as posed,
    // through the two placements
    const M4 ow = M4::translation(o.position) * M4::rotationZ(o.facing) * M4::scaling(V3(o.scale, o.scale, o.scale));
    const M4 inv = motion::placementInverse(a.position, a.facing, a.scale);
    const V3 world = ow.point(o.mats[size_t(r.targetBone)].point(om.bones[size_t(r.targetBone)].pivot + off));
    const V3 target = inv.point(world);
    // the direction the fingers lie along: the target bone's rest axes as posed, through the placements
    const M4& tb = o.mats[size_t(r.targetBone)];
    V3 dirT(tb.at(0, 0) * along.x + tb.at(0, 1) * along.y + tb.at(0, 2) * along.z,
            tb.at(1, 0) * along.x + tb.at(1, 1) * along.y + tb.at(1, 2) * along.z,
            tb.at(2, 0) * along.x + tb.at(2, 1) * along.y + tb.at(2, 2) * along.z);
    const V3 dirW = (M4::rotationZ(o.facing)).point(dirT.normalized());
    const V3 dirP = M4::rotationZ(-a.facing).point(dirW);
    if (!(w > 1e-4f)) { r.palm = V3(); r.aimed = target; r.reachable = true; return; }
    auto pivotAt = [&](int b) { return a.mats[size_t(b)].point(m.bones[size_t(b)].pivot); };
    layer::ArmPose arm{pivotAt(r.upper), pivotAt(r.fore), pivotAt(r.hand), pivotAt(r.fingers)};
    layer::ReachGoal goal;
    goal.target = target;
    goal.along = dirP;
    goal.pole = pole;
    goal.weight = w;
    layer::ReachTurns turns = layer::solveReach(arm, goal);
    auto lay = [&](const std::vector<int>& sub, const M4& d) { for (int i : sub) if (size_t(i) < a.mats.size()) a.mats[size_t(i)] = d * a.mats[size_t(i)]; };
    lay(r.upperSub, layer::rotateAbout(arm.shoulder, turns.upper));
    lay(r.foreSub, layer::rotateAbout(turns.elbow, turns.fore));
    lay(r.handSub, layer::rotateAbout(turns.wrist, turns.hand));
    const M4 aw = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    r.palm = aw.point(turns.palm);
    r.aimed = world;
    r.reachable = turns.reachable;
    r.solved = true;
}

void Scene::layMotion(Actor& a, uint32_t now) {
    MotionState& st = a.motion;
    if (!st.on || !a.character || a.ghost) return;
    // who it looks at: the other's head as last drawn (world), or the camera's eye
    st.hasTarget = false;
    if (st.target == -1) { st.targetPoint = eye_; st.hasTarget = true; }
    else if (st.target > 0) {
        auto t = actors_.find(st.target);
        if (t != actors_.end() && t->second.visible && t->second.mesh && t->second.mesh->model) {
            const Actor& o = t->second;
            const M2Model& om = *o.mesh->model;
            int h = om.findWowBone("Head");
            V3 local = h >= 0 && size_t(h) < o.mats.size() ? o.mats[size_t(h)].point(om.bones[size_t(h)].pivot) : V3(0, 0, actorHeight(t->first) * 0.9f / std::max(o.scale, 1e-3f));
            st.targetPoint = (M4::translation(o.position) * M4::rotationZ(o.facing) * M4::scaling(V3(o.scale, o.scale, o.scale))).point(local);
            st.hasTarget = true;
        }
    }
    // a game animation other than standing (an emote, a seat) moves the body itself: the idle motions quieten
    if (!a.boneAnim) {
        const M2Model& m = *a.mesh->model;
        bool standing = a.seq < 0 || size_t(a.seq) >= m.sequences.size() || m.sequences[size_t(a.seq)].id == 0 || m.sequences[size_t(a.seq)].id == a.idleAnim;
        st.idleWeight = standing && a.furniture == kOnFloor ? 1.0f : 0.4f;
    }
    motion::apply(st, *a.mesh->model, a.mats, motion::placementInverse(a.position, a.facing, a.scale), motionParams_, now);
}

void Scene::setParticles(int count, float strength, uint32_t seed) {
    int n = std::clamp(count, 0, ParticleField::kMaxCount);
    if (n != int(particles_.motes().size()) || seed != particles_.seed()) {
        particles_.setBox(target_, V3(2.2f, 2.2f, 1.1f));
        particles_.configure(n, seed);
    }
    particleStrength_ = std::isfinite(strength) ? std::clamp(strength, 0.0f, 1.0f) : 0.0f;
}

void Scene::setSparkles(const float* data, int count, float density) { sparkles_.set(data, count, density); }

void Scene::setCinematic(float mood, float vignette, float grain, float bars) {
    auto unit = [](float v) { return std::isfinite(v) ? std::clamp(v, 0.0f, 1.0f) : 0.0f; };
    float m = unit(mood);
    cineGrade_.mood = m;
    cineVignette_ = unit(vignette);   // the option's; the profile scales it (updateCinematic)
    cineGrade_.vignette = cineHas_ ? std::clamp(cineVignette_ * cineNow_.vignette, 0.0f, 1.0f) : cineVignette_;
    cineGrade_.grain = unit(grain);
    cineGrade_.bars = std::isfinite(bars) ? std::clamp(bars, 0.0f, 0.5f) : 0.0f;
    if (m != cineMood_) {
        cineMood_ = m;
        // off: the profile is worked out afresh when the look comes on again (the fade in eases it)
        if (m <= 0) { cineFlicker_ = 0; cineHas_ = false; GradeParams n; n.grain = cineGrade_.grain; n.bars = cineGrade_.bars;
                      n.vignette = cineGrade_.vignette; cineGrade_ = n; }
        applyTimeOfDay();
    }
}

void Scene::litSetup(unsigned prog, const M4& viewProj) { litUniforms(prog, light_, eye_, viewProj); }

// ---------------------------------------------------------------------------------------------- the flap as cloth

void Scene::dropClothGl(Actor& a, bool tex) {
    ClothGl& c = a.cloth;
    if (glReady_) {
        if (c.vao) glDeleteVertexArrays(1, &c.vao);
        if (c.vbo) glDeleteBuffers(1, &c.vbo);
        if (c.ibo) glDeleteBuffers(1, &c.ibo);
        if (tex && c.tex) glDeleteTextures(1, &c.tex);
    }
    c.vao = c.vbo = c.ibo = 0;
    if (tex) c.tex = 0;
}

// The rig for the look as dressed and shaped: the shown flap's geosets (none: no cloth, the flap is drawn skinned as
// before, if at all), the body's geosets without them, and the sim on it from the pose.
void Scene::refreshCloth(Actor& a) {
    ClothGl& c = a.cloth;
    c.dirty = false;
    c.sim.setRig(nullptr);
    c.rig.reset();
    c.bodyGeosets.clear();
    const M2Model& m = *a.mesh->model;
    bool flap = false;
    for (uint16_t g : a.geosets) if (g >= 100 && g / 100 == kClothGroup) flap = true;
    if (!flap) return;
    const BodyGl& b = a.body;
    const bool own = !b.shaped.empty() && !b.indices.empty() && !b.submeshes.empty();
    auto rig = std::make_unique<ClothRig>(clothRig(m, b.shaped.empty() ? m.vertices : b.shaped, own ? b.indices : m.indices,
                                                    own ? b.submeshes : m.submeshes, a.geosets));
    if (!rig->ok) return;
    for (uint16_t g : a.geosets) if (std::find(rig->geosets.begin(), rig->geosets.end(), g) == rig->geosets.end()) c.bodyGeosets.push_back(g);
    if (c.bodyGeosets.empty()) c.bodyGeosets.push_back(0xFFFF);
    c.rig = std::move(rig);
    c.sim.setRig(c.rig.get());
}

// This frame's step: the flap's posed top edge and the body (its segments' flesh and its skin near the flap) from the
// bones as drawn, the floor under the actor; the rig made again first when the look or the shape changed.
void Scene::stepCloth(Actor& a, uint32_t now) {
    ClothGl& c = a.cloth;
    if (c.dirty && !a.body.dirty && !a.body.shapeDirty) refreshCloth(a);
    if (!c.active() || a.mats.empty()) return;
    const M2Model& m = *a.mesh->model;
    const std::vector<M2Vertex>& verts = a.body.shaped.empty() ? m.vertices : a.body.shaped;
    M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    clothPosed(*c.rig, verts, a.mats, w, c.posed);
    clothCapsules(m, *c.rig, a.mats, w, c.caps);
    clothSurface(*c.rig, verts, a.mats, w, c.skin);
    // the floor under her; seated, the back flap lies on the seat she sits on
    c.sim.setFloor(a.position.z, a.furniture == kSitting ? a.surface.z : a.position.z);
    c.sim.step(c.posed, c.caps, &c.skin, a.scale, now);
}

// The simulated flap: its points as vertices without skinning (model space, the actor's world), both faces, with the
// cloth's own texture (its rectangle's coordinates) or the body's skin; opaque, as the game's flap is.
void Scene::drawCloth(Actor& a, const M4& world, const float tint[4]) {
    ClothGl& c = a.cloth;
    if (!c.sim.started()) stepCloth(a, animClock_);   // (a thumbnail's actor: hung from its pose)
    if (!c.active() || !c.sim.started()) return;
    clothVertices(*c.rig, c.sim.positions(), world, c.tex ? c.uvBox : nullptr, c.verts, c.indices, c.tex && c.panel ? c.panelPx : nullptr);
    if (c.indices.empty()) return;
    unsigned tex = c.tex;
    if (!tex) {
        auto comp = a.composed.find(1);
        if (comp != a.composed.end()) tex = comp->second;
        else { auto d = a.drawTextures.find(1); if (d != a.drawTextures.end()) tex = texture(d->second, kActorTex).id; }
    }
    if (!tex) return;
    if (!c.vao) {
        glGenVertexArrays(1, &c.vao);
        glGenBuffers(1, &c.vbo);
        glGenBuffers(1, &c.ibo);
        glBindVertexArray(c.vao);
        glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, c.ibo);
        m2VertexLayout();
    }
    glBindVertexArray(c.vao);
    glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(c.verts.size() * sizeof(M2Vertex)), c.verts.data(), GL_STREAM_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, c.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(c.indices.size() * 2), c.indices.data(), GL_STREAM_DRAW);
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uWorld"), 1, GL_FALSE, world.m);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform4fv(loc(p, "uColor"), 1, tint);
    glUniform1f(loc(p, "uAlphaRef"), -1.0f);
    glUniform1i(loc(p, "uOpaque"), 1);
    glUniform1i(loc(p, "uUnlit"), 0);
    // each face drawn from its own side (the grid twice, wound both ways, normals both ways)
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glDrawElements(GL_TRIANGLES, GLsizei(c.indices.size()), GL_UNSIGNED_SHORT, nullptr);
    glDisable(GL_CULL_FACE);
    glBindVertexArray(0);
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
    it->second.soft.dirty = true;
    it->second.cloth.dirty = true;
}

bool Scene::bodyStats(int id, size_t& moved, size_t& layerTriangles) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    moved = it->second.body.moved;
    layerTriangles = it->second.body.layerTriangles;
    return true;
}

bool Scene::actorPose(int id, std::vector<M4>& mats, int& seq, uint32_t& timeMs) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    mats = it->second.mats;
    seq = it->second.seq;
    timeMs = it->second.animTime;
    return true;
}

bool Scene::actorBonePoints(int id, float out[9]) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    const Actor& a = it->second;
    if (!a.mesh || !a.mesh->model) return false;
    const M2Model& m = *a.mesh->model;
    const int head = m.findWowBone("Head"), pelvis = m.findWowBone("Pelvis");
    int chest = m.findWowBone("Spine2");
    if (chest < 0) chest = m.findWowBone("Spine1");
    if (head < 0 || pelvis < 0 || chest < 0) return false;
    const size_t n = a.mats.size();
    if (size_t(head) >= n || size_t(pelvis) >= n || size_t(chest) >= n) return false;
    const M4 w = M4::translation(a.position) * M4::rotationZ(a.facing) * M4::scaling(V3(a.scale, a.scale, a.scale));
    const int bones[3] = {head, chest, pelvis};
    for (int k = 0; k < 3; ++k) {
        const V3 p = w.point(a.mats[size_t(bones[k])].point(m.bones[size_t(bones[k])].pivot));
        out[3 * k] = p.x; out[3 * k + 1] = p.y; out[3 * k + 2] = p.z;
    }
    return true;
}

void Scene::dropBodyGl(Actor& a) {
    BodyGl& b = a.body;
    if (glReady_) {
        unsigned buffers[4] = {b.vbo, b.ibo, b.layerVbo, b.layerIbo};
        glDeleteBuffers(4, buffers);
        unsigned arrays[2] = {b.vao, b.layerVao};
        glDeleteVertexArrays(2, arrays);
        if (b.layerTex) glDeleteTextures(1, &b.layerTex);
    }
    b.vao = b.vbo = b.ibo = b.layerVao = b.layerVbo = b.layerIbo = b.layerTex = 0;
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
        // a sculpt-only change (bodyrig.cpp sculptSync) starts from the body as it was before the sculpt, if nothing else changed
        const bool sculptOnly = b.sculptOnly && !b.sculptBase.empty() && b.baseModel == static_cast<const void*>(&m) &&
                                b.baseMorph == b.morph && b.baseCut == a.parts.cut;
        b.sculptOnly = false;
        if (sculptOnly) {
            b.shaped = b.sculptBase;
        } else {
            if (b.genModel != static_cast<const void*>(&m) || b.genMorph != b.morph || b.genCut != a.parts.cut) {
                ++b.baseGen;   // (another body before the sculpt: the live sculpt's fields are made again for it, bodyrig.cpp)
                b.genModel = &m; b.genMorph = b.morph; b.genCut = a.parts.cut;
            }
            b.sculptBase.clear();
            b.shaped.clear();
            b.indices.clear();
            b.submeshes.clear();
            b.moved = 0;
            if (b.morph.size() >= kBodyMorphStride) {
                // the skin's sections (texture type 1) are split where the shape moves them: its curve has vertices to show on
                std::vector<bool> splittable(m.submeshes.size(), false);
                for (const M2Batch& bt : m.batches) {
                    if (bt.submesh >= m.submeshes.size() || bt.textureCombo >= m.textureCombos.size()) continue;
                    uint16_t ti = m.textureCombos[bt.textureCombo];
                    if (ti < m.textures.size() && m.textures[ti].type == 1) splittable[bt.submesh] = true;
                }
                ShapedBody sb;
                if (shapeBody(m, splittable, b.morph.data(), b.morph.size() / kBodyMorphStride, kBodySplitLevels, sb)) {
                    b.shaped.swap(sb.vertices);
                    b.indices.swap(sb.indices);
                    b.submeshes.swap(sb.submeshes);
                    b.moved = sb.moved;
                }
                std::fprintf(stderr, "body shape: %zu of %zu vertices moved (%zu at a looks vertex, %zu texture coordinates), %zu triangles split, %zu vertices added\n",
                             sb.moved, m.vertices.size(), sb.exact, sb.uvs, sb.split, sb.added);
            }
            // the stand-in's cut (setBodyCut): its own head under a model part's, from the shaped body or the model's own
            if (!a.parts.cut.empty()) {
                PartRegion r;
                r.roots = a.parts.cut;
                r.attach = a.parts.cut[0];
                if (b.shaped.empty()) { b.shaped = m.vertices; b.indices = m.indices; b.submeshes = m.submeshes; }
                std::vector<bool> mask = regionVertices(m, r, 0, &b.shaped);
                size_t before = b.indices.size();
                b.indices = cutTriangles(b.indices, b.submeshes, mask, 3);
                std::fprintf(stderr, "body cut: %zu of %zu triangles left out\n", (before - b.indices.size()) / 3, before / 3);
            }
        }
        // the creator's sculpt (sculpt.h) on the game's own body: every vertex of the model (the skin and the clothing geosets
        // on it), in the bind pose, so it bends with its bones as before; the body before it kept for the next change
        b.sculptMs = 0;
        b.sculptMoved = 0;
        if (sculptActive(b.sculpted.data(), b.sculpted.size())) {
            auto t0 = std::chrono::steady_clock::now();
            if (b.shaped.empty()) { b.shaped = m.vertices; b.indices = m.indices; b.submeshes = m.submeshes; }
            if (!sculptOnly) {
                b.sculptBase = b.shaped;
                b.baseMorph = b.morph;
                b.baseCut = a.parts.cut;
                b.baseModel = &m;
            }
            SculptOptions opt;
            opt.gameBody = true;
            SculptStats st;
            applySculpt(sculptRigOf(m), b.shaped, b.indices, b.sculpted.data(), b.sculpted.size(), opt, &st);
            b.sculptMoved = st.moved;
            b.sculptMs = float(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
            std::fprintf(stderr, "body sculpt: %zu of %zu vertices moved (most %.4f), %zu triangles eased, %zu turned over, %.1f ms\n",
                         st.moved, b.shaped.size(), st.maxMove, st.eased, st.flipped, b.sculptMs);
        }
        eveRefit(a);   // the Eve body (evescene.cpp): fitted to the body as shaped, the body under it cut
        b.shapeDirty = false;
    }
    if (!b.shaped.empty()) {
        glGenVertexArrays(1, &b.vao);
        glBindVertexArray(b.vao);
        glGenBuffers(1, &b.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, b.vbo);
        glBufferData(GL_ARRAY_BUFFER, b.shaped.size() * sizeof(M2Vertex), b.shaped.data(), GL_STATIC_DRAW);
        glGenBuffers(1, &b.ibo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, b.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, b.indices.size() * 4, b.indices.data(), GL_STATIC_DRAW);
        m2VertexLayout();
        glBindVertexArray(0);
    }

    // The layer (layer.py): the skin's triangles shown, facing the front, under the square; lifted off the skin.
    b.layerTriangles = 0;
    if (!b.design || b.k <= 0) return;
    if (files_(b.design).empty()) { b.missing.insert(b.design); return; }
    if (b.hair && files_(b.hair).empty()) { b.missing.insert(b.hair); return; }
    const std::vector<M2Vertex>& verts = b.shaped.empty() ? m.vertices : b.shaped;
    const std::vector<uint32_t>& tris = b.shaped.empty() ? m.indices : b.indices;
    const std::vector<M2Submesh>& subs = b.shaped.empty() ? m.submeshes : b.submeshes;
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
        const M2Submesh& sm = subs[si];
        for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount && t + 2 < tris.size(); t += 3) {
            uint32_t ix[3] = {tris[t], tris[t + 1], tris[t + 2]};
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

// ------------------------------------------------------------------------------------------------ body parts (bodypart.h)

int Scene::loadPart(const std::string& path) {
    auto known = partPaths_.find(path);
    if (known != partPaths_.end()) return known->second;
    Bytes b;
    auto p = std::make_unique<PartGl>();
    std::string err;
    if (!readFile(path, b)) { partError_ = "part file " + path + " couldn't be read"; return 0; }
    if (!parsePart(b.data(), b.size(), p->data, err)) { partError_ = "part file " + path + " " + err; return 0; }
    partError_.clear();
    p->path = path;
    if (glReady_) uploadPart(*p);
    int id = nextPart_++;
    parts_[id] = std::move(p);
    partPaths_[path] = id;
    return id;
}

void Scene::uploadPart(PartGl& p) {
    const PartData& d = p.data;
    size_t n = d.vertexCount();
    std::vector<M2Vertex> v(n);
    for (size_t i = 0; i < n; ++i) {
        M2Vertex& o = v[i];
        std::memset(&o, 0, sizeof o);   // no bone weights: the shader's skin is the identity, uWorld places the part
        V3 q = d.modelFrame() ? partModelPoint(d, i) : partSimsPoint(d, i);
        o.pos[0] = q.x; o.pos[1] = q.y; o.pos[2] = q.z;
        if (d.normals.size() >= (i + 1) * 3) { o.normal[0] = d.normals[i * 3]; o.normal[1] = d.normals[i * 3 + 1]; o.normal[2] = d.normals[i * 3 + 2]; }
        else o.normal[2] = 1;
        if (d.uvs.size() >= (i + 1) * 2) { o.uv[0] = d.uvs[i * 2]; o.uv[1] = d.uvs[i * 2 + 1]; }
    }
    glGenVertexArrays(1, &p.vao);
    glBindVertexArray(p.vao);
    glGenBuffers(1, &p.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, p.vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(v.size() * sizeof(M2Vertex)), v.data(), GL_STATIC_DRAW);
    glGenBuffers(1, &p.ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, p.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(d.indices.size() * 4), d.indices.data(), GL_STATIC_DRAW);
    m2VertexLayout();
    glBindVertexArray(0);
}

bool Scene::attachPart(int id, int part, const uint8_t* rgba, int w, int h) {
    auto it = actors_.find(id);
    auto pt = parts_.find(part);
    if (it == actors_.end() || pt == parts_.end() || w <= 0 || h <= 0 || !rgba) return false;
    PartSlot& s = it->second.parts.slots[pt->second->data.slot()];
    if (s.tex && glReady_) glDeleteTextures(1, &s.tex);
    s.tex = 0;
    s.part = part;
    for (PartSlot& o : it->second.parts.slots) o.shaftMeasured = false;   // (each slot's shaft is told against the other's mesh)
    s.rgba.assign(rgba, rgba + size_t(w) * size_t(h) * 4);
    s.w = w; s.h = h;
    s.wasDrawn = false;
    return true;
}

void Scene::setPartState(int id, int state) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.parts.state = state >= 0 && state <= 2 ? state : 0;
}

int Scene::partState(int id) const {
    auto it = actors_.find(id);
    return it == actors_.end() ? 0 : it->second.parts.state;
}

void Scene::setPartFit(int id, const PartFit& fit) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    it->second.parts.fit = fit;
    it->second.parts.placed = false;
}

void Scene::dropPartGl(ActorParts& ap) {
    for (PartSlot& s : ap.slots) {
        if (s.tex && glReady_) glDeleteTextures(1, &s.tex);
        if (s.vbo && glReady_) glDeleteBuffers(1, &s.vbo);
        if (s.vao && glReady_) glDeleteVertexArrays(1, &s.vao);
        s = PartSlot();
    }
}

void Scene::setPartDrive(int id, float level, int partner, int entry, float rest) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    ActorParts& ap = it->second.parts;
    ap.level = std::isfinite(level) ? (level < 0 ? -1.0f : std::min(level, 1.0f)) : -1.0f;
    ap.partner = partner != id ? partner : 0;
    ap.entry = entry > kEntryNone && entry < kEntryCount ? entry : kEntryNone;
    ap.rest = std::isfinite(rest) ? std::clamp(rest, -0.1f, 0.2f) : 0.0f;
}

void Scene::setPartSkin(int id, int rgb) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    ActorParts& ap = it->second.parts;
    int v = rgb < 0 ? -1 : (rgb & 0xFFFFFF);
    if (v == ap.skinRgb) return;
    ap.skinRgb = v;
    for (PartSlot& s : ap.slots) { if (s.tex && glReady_) glDeleteTextures(1, &s.tex); s.tex = 0; }
}

void Scene::setHdGenitalMask(int id, const std::vector<uint32_t>& vertices) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.parts.hdGenitalMask = vertices;
}

const std::vector<uint32_t>* Scene::hdGenitalMask(int id) const {
    auto it = actors_.find(id);
    return it == actors_.end() ? nullptr : &it->second.parts.hdGenitalMask;
}

bool Scene::partDriveInfo(int id, PartDriveInfo& out) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    const ActorParts& ap = it->second.parts;
    out.level = ap.level; out.depth = ap.depth; out.distance = ap.distance;
    out.aimDeg = quatAngle(ap.aim) * 57.29578f; out.aimWeight = ap.aimWeight; out.slotOn = ap.slotOn;
    out.entryWorld = ap.lastEntry; out.rootWorld = ap.lastRoot; out.pelvisForward = ap.lastForward;
    return true;
}

bool Scene::entryWorld(int id, int entry, V3& out, V3* inward) {
    auto it = actors_.find(id);
    if (it == actors_.end() || entry <= kEntryNone || entry >= kEntryCount) return false;
    Actor& o = it->second;
    if (!o.mesh || !o.mesh->model) return false;
    // (a partner who wears a part is a man here: no vaginal entry, the anal one instead)
    bool wears = o.parts.slots[0].part || o.parts.slots[1].part;
    if (!o.parts.entries.measured) o.parts.entries = measureEntries(*o.mesh->model, o.geosets, true);
    if (entry == kEntryVaginal && wears) entry = kEntryAnal;
    const EntryAnchor& e = o.parts.entries.at[entry];
    if (!e.ok || e.bone < 0 || size_t(e.bone) >= o.mats.size()) return false;
    M4 w = M4::translation(o.position) * M4::rotationZ(o.facing) * M4::scaling(V3(o.scale, o.scale, o.scale));
    M4 bw = w * o.mats[size_t(e.bone)];
    out = bw.point(e.point);
    if (inward) *inward = (bw.point(e.point + e.inward) - out).normalized();
    return true;
}

void Scene::detachParts(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    dropPartGl(it->second.parts);
    it->second.parts.state = 0;
}

std::vector<float> Scene::partSkinUvs(int id) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return {};
    Actor& a = it->second;
    if (!a.parts.placed) { a.parts.place = placeParts(*a.mesh->model, a.geosets, a.parts.fit); a.parts.placed = true; }
    if (!a.parts.place.ok) return {};
    float h = a.parts.place.pivot.z > 0.05f ? a.parts.place.pivot.z : 1.0f;
    return bodySkinUvs(*a.mesh->model, a.geosets, a.parts.place.root, 0.12f * h, 256);
}

bool Scene::partPose(int id, int slot, M4& matrix, PartPlacement& placement) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || slot < 0 || slot > 1) return false;
    const PartSlot& s = it->second.parts.slots[slot];
    if (!s.part || !s.wasDrawn) return false;
    matrix = s.drawn;
    placement = it->second.parts.place;
    return true;
}

// The slot the actor's state picks, rigid on its Pelvis as animated this frame (bodypart.h), lit and opaque as the body
// is (no culling: the body's isn't either). Drawn in the opaque pass, after the soft body's uniforms are off.
void Scene::drawParts(Actor& a, const M4& world, bool transparent, uint32_t nowMs, const float tint[4]) {
    ActorParts& ap = a.parts;
    drawBoneParts(a, world, transparent);
    if (transparent || ap.state < 1 || ap.state > 2) return;
    if (!ap.placed) { ap.place = placeParts(*a.mesh->model, a.geosets, ap.fit); ap.placed = true; }
    if (!ap.place.ok || size_t(ap.place.pelvis) >= a.mats.size()) return;
    // the shafts, measured once per pair of meshes (each against the other's: their shared vertices are the base)
    const PartData* datas[2] = {nullptr, nullptr};
    for (int k = 0; k < 2; ++k) {
        auto f = parts_.find(ap.slots[k].part);
        if (ap.slots[k].part && f != parts_.end()) datas[k] = &f->second->data;
    }
    for (int k = 0; k < 2; ++k) {
        if (!datas[k] || ap.slots[k].shaftMeasured) continue;
        ap.slots[k].shaft = measureShaft(*datas[k], datas[1 - k]);
        ap.slots[k].shaftMeasured = true;
    }
    float dt = ap.lastMs && nowMs > ap.lastMs ? std::min(0.1f, float(nowMs - ap.lastMs) / 1000.0f) : 0.0f;
    ap.lastMs = nowMs;
    // which mesh: driven, the level with hysteresis; not driven, the state as it is
    bool driven = ap.level >= 0;
    float level = driven ? ap.level : (ap.state == 2 ? 1.0f : 0.0f);
    if (driven) ap.slotOn = levelSlotOn(level, ap.slotOn); else ap.slotOn = ap.state == 2;
    int slot = ap.slotOn ? 1 : 0;
    if (!datas[slot]) slot = 1 - slot;   // (a set with one state only: that one, bent)
    PartSlot& s = ap.slots[slot];
    auto pt = parts_.find(s.part);
    if (!s.part || pt == parts_.end() || !pt->second->ibo) return;
    const PartData& d = pt->second->data;
    if (!s.tex) {
        if (s.rgba.empty()) return;
        // the root blended to the body's skin colour with its contact shade (blendRootToSkin), on a copy: the kept pixels stay
        std::vector<uint8_t> px = s.rgba;
        if (ap.skinRgb >= 0) blendRootToSkin(d, px.data(), s.w, s.h, uint32_t(ap.skinRgb));
        glGenTextures(1, &s.tex);
        glBindTexture(GL_TEXTURE_2D, s.tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, s.w, s.h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    s.drawn = partMatrix(a.mats[size_t(ap.place.pelvis)], ap.place);
    s.wasDrawn = true;
    // the bend: the level's pitch and length, the thighs' hang while flaccid, the aim at the partner's entry while erect
    const PartShaft& flaccid = ap.slots[0].shaft;
    const PartShaft& erect = ap.slots[1].shaftMeasured && ap.slots[1].shaft.ok ? ap.slots[1].shaft : flaccid;
    float hang = std::clamp(thighPitch(*a.mesh->model, a.mats, ap.place.pelvis), -0.35f, 1.75f) * kFlaccidThighShare;
    PartBend bend = levelBend(level, slot == 1, flaccid.ok ? flaccid : erect, erect, hang);
    bend.squeeze = ap.place.squeeze;
    Quat aimTarget;
    bool aiming = false;
    ap.distance = -1;
    ap.depth = 0;
    ap.lastRoot = (world * s.drawn).point(V3(kPartRootX, 0.0f, kPartRootZ));
    ap.lastForward = ((world * s.drawn).point(V3(kPartRootX + 1.0f, 0.0f, kPartRootZ)) - ap.lastRoot).normalized();
    V3 entryW;
    V3 inwardW;
    if (driven && ap.partner && ap.entry != kEntryNone && level >= kPartSwapDown && entryWorld(ap.partner, ap.entry, entryW, &inwardW)) {
        // the entry in the part's own (Sims) frame: the world back into the wearer's model space, then through the part matrix
        M4 toSims = affineInverse(world * s.drawn);
        V3 e = toSims.point(entryW);
        V3 root(kPartRootX, 0.0f, kPartRootZ);
        V3 want = e - root;
        V3 have = levelAxis(level, flaccid.ok ? flaccid : erect, erect, hang);
        float dist = want.length();
        float cosA = dist > 1e-5f ? want.normalized().dot(have) : 1.0f;
        ap.lastEntry = entryW;
        ap.lastRoot = (world * s.drawn).point(root);
        float length0 = erect.ok ? erect.length : 0.15f;
        float reachable = length0 * kAimReach + kAimSlack;
        if (dist > 1e-4f && dist <= reachable) {
            // the way: at the entry from in front, root to entry; where the entry is at the root or behind it (the bodies
            // pressed together), along the partner's own way in. Turned at most kAimLimitDeg from the shaft's rest line.
            V3 way = want.normalized();
            if (dist < 0.5f * length0 || cosA < 0.0f) way = (toSims.point(entryW + inwardW) - e).normalized();
            float lim = kAimLimitDeg * 0.0174533f;
            float ang = std::acos(std::clamp(way.dot(have), -1.0f, 1.0f));
            Quat full = quatFromTo(have, way);
            aimTarget = ang > lim ? slerp(Quat(), full, lim / ang) : full;
            aiming = true;
            ap.distance = dist;
            float length = length0 * ap.reach;
            ap.depth = insertionDepth(length, std::max(0.0f, want.dot(quatRotate(aimTarget, have))), ap.rest);
            // the reach: the drawn length eased (over seconds) toward meeting the stage's rest depth on average, at most +25%
            float wantReach = std::clamp((dist + std::max(ap.rest, 0.0f)) / std::max(1e-3f, length0), 1.0f, 1.25f);
            ap.reach += (wantReach - ap.reach) * std::min(1.0f, dt * 0.5f);
        }
    }
    float maxTurn = kAimRateDeg * 0.0174533f * (dt > 0 ? dt : 1.0f / 30.0f);
    ap.aim = stepAim(ap.aim, aiming ? aimTarget : Quat(), maxTurn);
    ap.aimWeight = std::clamp(ap.aimWeight + (aiming ? 1.0f : -1.0f) * (dt > 0 ? dt : 1.0f / 30.0f) * 2.5f, 0.0f, 1.0f);
    if (!aiming && ap.aimWeight <= 0) ap.reach += (1.0f - ap.reach) * std::min(1.0f, dt * 0.5f);
    if (ap.aimWeight > 0) {
        bend.turn = quatMul(slerp(Quat(), ap.aim, ap.aimWeight), bend.turn);
        bend.stretch *= 1.0f + (ap.reach - 1.0f) * ap.aimWeight;
    }
    posePart(d, s.shaft.ok ? s.shaft : PartShaft(), bend, s.posed);
    if (!s.vao) {
        glGenVertexArrays(1, &s.vao);
        glBindVertexArray(s.vao);
        glGenBuffers(1, &s.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, s.vbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(s.posed.size() * sizeof(M2Vertex)), s.posed.data(), GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, pt->second->ibo);
        m2VertexLayout();
    } else {
        glBindVertexArray(s.vao);
        glBindBuffer(GL_ARRAY_BUFFER, s.vbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(s.posed.size() * sizeof(M2Vertex)), s.posed.data(), GL_DYNAMIC_DRAW);
    }
    M4 w = world * s.drawn;
    unsigned p = m2Prog_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uWorld"), 1, GL_FALSE, w.m);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, a.boneTex);   // (not read: the part's vertices have no weights)
    glUniform1i(loc(p, "uBones"), 1);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s.tex);
    glUniform4fv(loc(p, "uColor"), 1, tint);
    glUniform1f(loc(p, "uAlphaRef"), -1.0f);
    glUniform1i(loc(p, "uOpaque"), 1);
    glUniform1i(loc(p, "uUnlit"), 0);
    // shaded as the body's skin is (drawModel's skin kind 1: the same sheen and light), so the part reads as his
    const bool skinFx = gfx_.skin > 0 || (drawSkin_[3] > 0.5f && gfx_.thinSkin > 0) || drawSkin_[0] > 0;
    if (skinFx) {
        glUniform4fv(loc(p, "uSkinActor"), 1, drawSkin_);
        if (drawSkin_[3] > 0.5f) {
            glUniformMatrix4fv(loc(p, "uThinHead"), 1, GL_FALSE, drawThin_);
            glUniform4fv(loc(p, "uThinHands"), 2, drawThin_ + 16);
        }
        glUniform1i(loc(p, "uSkinKind"), 1);
    }
    glDrawElements(GL_TRIANGLES, GLsizei(d.indices.size()), GL_UNSIGNED_INT, nullptr);
    if (skinFx) glUniform1i(loc(p, "uSkinKind"), 0);
    glBindVertexArray(0);
}

// ------------------------------------------------------------------------------------------------ model parts (modelpart.h)

int Scene::attachBonePart(int id, int part, const BonePartFit& fit) {
    auto it = actors_.find(id);
    auto pt = parts_.find(part);
    if (it == actors_.end() || pt == parts_.end() || !pt->second->data.modelFrame()) return 0;
    ActorParts& ap = it->second.parts;
    BonePart bp;
    bp.handle = ap.nextBone++;
    bp.part = part;
    bp.fit = fit;
    ap.bone.push_back(bp);
    return bp.handle;
}

void Scene::setBonePartVisible(int id, int handle, bool visible) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    for (BonePart& bp : it->second.parts.bone) if (!handle || bp.handle == handle) bp.visible = visible;
}

void Scene::setBonePartFit(int id, int handle, const BonePartFit& fit) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    for (BonePart& bp : it->second.parts.bone) if (!handle || bp.handle == handle) { bp.fit = fit; bp.placed = false; }
}

void Scene::detachBoneParts(int id) {
    auto it = actors_.find(id);
    if (it != actors_.end()) it->second.parts.bone.clear();
}

void Scene::setBodyCut(int id, const std::vector<std::string>& bones) {
    auto it = actors_.find(id);
    if (it == actors_.end() || it->second.parts.cut == bones) return;
    Actor& a = it->second;
    a.parts.cut = bones;
    a.body.shapeDirty = true;   // (the body's triangles made again from the model's, then cut)
    a.body.dirty = true;
    a.soft.dirty = true;
}

void Scene::setBodyTint(int id, float r, float g, float b) {
    auto it = actors_.find(id);
    if (it == actors_.end()) return;
    float* t = it->second.parts.tint;
    t[0] = r; t[1] = g; t[2] = b; t[3] = 1;
}

std::vector<float> Scene::faceSkinUvs(int id) const {
    auto it = actors_.find(id);
    if (it == actors_.end() || !it->second.mesh) return {};
    const Actor& a = it->second;
    const M2Model& m = *a.mesh->model;
    std::vector<float> uvs;
    maskUvs(m, a.geosets, faceVertices(m), [](const M2Texture& t) { return t.type == 1 ? 1u : 0u; }, uvs);
    return uvs;
}

bool Scene::bonePartPose(int id, int handle, M4& matrix, BonePartPlacement& placement) const {
    auto it = actors_.find(id);
    if (it == actors_.end()) return false;
    for (const BonePart& bp : it->second.parts.bone)
        if (bp.handle == handle && bp.wasDrawn) { matrix = bp.drawn; placement = bp.place; return true; }
    return false;
}

// Each shown model part rigid on its bone as animated this frame, with its own texture and blend: the opaque and
// alpha-keyed ones in the opaque pass, the blended ones in the transparent pass (as drawModel sorts a model's batches).
void Scene::drawBoneParts(Actor& a, const M4& world, bool transparent) {
    ActorParts& ap = a.parts;
    if (ap.bone.empty()) return;
    unsigned p = m2Prog_;
    bool used = false;
    for (BonePart& bp : ap.bone) {
        auto pt = parts_.find(bp.part);
        if (!bp.visible || pt == parts_.end() || !pt->second->vao) continue;
        const PartData& d = pt->second->data;
        bool blend = d.blend >= 2;
        if (blend != transparent) continue;
        if (!bp.placed) { bp.place = placeBonePart(d, *a.mesh->model, bp.fit); bp.placed = true; }
        if (!bp.place.ok || size_t(bp.place.bone) >= a.mats.size()) continue;
        GlTexture tex = texture(d.textureFileId, kActorTex);
        if (!tex.id) continue;
        bp.drawn = bonePartMatrix(a.mats[size_t(bp.place.bone)], bp.place);
        bp.wasDrawn = true;
        M4 w = world * bp.drawn;
        if (!used) {
            glUseProgram(p);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, a.boneTex);   // (not read: the part's vertices have no weights)
            glUniform1i(loc(p, "uBones"), 1);
            glUniform4fv(loc(p, "uColor"), 1, ghostTint_);
            used = true;
        }
        glUniformMatrix4fv(loc(p, "uWorld"), 1, GL_FALSE, w.m);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex.id);
        glUniform1f(loc(p, "uAlphaRef"), d.blend == 1 ? 0.5f : (d.blend == 0 ? -1.0f : 0.004f));
        glUniform1i(loc(p, "uOpaque"), d.blend == 0 ? 1 : 0);
        glUniform1i(loc(p, "uUnlit"), (d.materialFlags & 1) ? 1 : 0);
        if (blend) { glEnable(GL_BLEND); blendFor(d.blend); glDepthMask(GL_FALSE); }
        if (d.materialFlags & 16) glDepthMask(GL_FALSE);
        glBindVertexArray(pt->second->vao);
        glDrawElements(GL_TRIANGLES, GLsizei(d.indices.size()), GL_UNSIGNED_INT, nullptr);
        glBindVertexArray(0);
        if (blend) glDisable(GL_BLEND);
        glDepthMask(GL_TRUE);
    }
}

} // namespace wc
