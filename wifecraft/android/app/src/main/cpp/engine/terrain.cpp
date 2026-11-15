// The map's ground under a location: the ADT tile's chunks (root ADT: MCNK's MCVT heights, MCCV shading, holes; tex0:
// MDID's textures, MCLY's layers, MCAL's alpha maps), moved into the location's coordinates by its frame, drawn in the
// location's static pass with its fog. Up to four layers per chunk, each over the last by its alpha (the game's order).
#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "glutil.h"
#include "scene.h"

namespace wc {

using gl::loc;

namespace {

constexpr float kChunk = 533.33333f / 16.0f;
constexpr float kUnit = kChunk / 8.0f;
constexpr int kTerrainTex = 512;

const char* kTerrainVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aShade;
uniform mat4 uViewProj;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
out vec3 vShade;
void main() {
    vWorld = aPos;
    vNormal = aNormal;
    vUv = aUv;
    vShade = aShade.rgb * 2.0;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
)";

// Layers: each texture repeats once per cell (eight times a chunk); the alpha map spans the chunk.
const char* kTerrainFs = R"(#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
in vec3 vShade;
uniform sampler2D uL0;
uniform sampler2D uL1;
uniform sampler2D uL2;
uniform sampler2D uL3;
uniform sampler2D uAlpha;
uniform int uLayers;
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
    vec4 a = texture(uAlpha, vUv / 8.0);
    vec3 c = texture(uL0, vUv).rgb;
    if (uLayers > 1) c = mix(c, texture(uL1, vUv).rgb, a.r);
    if (uLayers > 2) c = mix(c, texture(uL2, vUv).rgb, a.g);
    if (uLayers > 3) c = mix(c, texture(uL3, vUv).rgb, a.b);
    vec3 n = normalize(vNormal);
    float ndl = max(dot(n, -uSunDir), 0.0);
    vec3 sky = uAmbient * mix(1.0, 1.1, max(n.z, 0.0));
    c *= (sky + uSunColor * ndl) * vShade;
    c = mix(c, uFogColor, smoothstep(uFog.x, uFog.y, d));
    fragColor = vec4(c, 1.0);
}
)";

struct ChunkRef { size_t at = 0, size = 0; };

std::vector<std::pair<uint32_t, ChunkRef>> topChunks(const Bytes& b) {
    std::vector<std::pair<uint32_t, ChunkRef>> out;
    Reader r(b);
    for (const Chunk& c : chunks(r, 0, b.size())) out.push_back({c.tag, {c.offset, c.size}});
    return out;
}

// One alpha map (64 x 64, 0..255) from MCAL at `off` (`size` bytes there): compressed (MCLY 0x200), 4096 bytes, or 2048
// (four bits each).
void readAlpha(const uint8_t* p, size_t size, bool compressed, uint8_t out[4096]) {
    std::memset(out, 0, 4096);
    if (compressed) {
        size_t i = 0, o = 0;
        while (o < 4096 && i < size) {
            uint8_t h = p[i++];
            size_t n = h & 0x7F;
            if (h & 0x80) {
                uint8_t v = i < size ? p[i++] : 0;
                for (size_t k = 0; k < n && o < 4096; ++k) out[o++] = v;
            } else {
                for (size_t k = 0; k < n && o < 4096 && i < size; ++k) out[o++] = p[i++];
            }
        }
    } else if (size >= 4096) {
        std::memcpy(out, p, 4096);
    } else {
        for (size_t i = 0; i < 2048 && i < size; ++i) {
            out[i * 2] = uint8_t((p[i] & 0x0F) * 17);
            out[i * 2 + 1] = uint8_t((p[i] >> 4) * 17);
        }
    }
}

} // namespace

void Scene::loadTerrain(const LocationDesc& d) {
    terrain_.clear();
    if (!d.terrainRoot) return;
    Bytes root, tex;
    std::string rp = files_(d.terrainRoot), tp = d.terrainTex0 ? files_(d.terrainTex0) : "";
    if (rp.empty() || !readFile(rp, root)) { std::fprintf(stderr, "terrain: root ADT %u isn't here\n", d.terrainRoot); return; }
    if (!tp.empty()) readFile(tp, tex);
    // tex0: the textures (MDID) and each chunk's layers and alpha, in the root's chunk order
    std::vector<uint32_t> mdid;
    std::vector<ChunkRef> texChunks;
    for (auto& [tag, c] : topChunks(tex)) {
        if (tag == rtag("MDID")) for (size_t o = 0; o + 4 <= c.size; o += 4) mdid.push_back(Reader(tex).u32(c.at + o));
        if (tag == rtag("MCNK")) texChunks.push_back(c);
    }
    float yaw = d.frameYawDeg * 3.14159265f / 180.0f, cy = std::cos(-yaw), sy = std::sin(-yaw);
    auto toLocal = [&](V3 w) {
        V3 r = w - d.frameOrigin;
        return V3(cy * r.x - sy * r.y, sy * r.x + cy * r.y, r.z);
    };
    // the reach of the views (chunks not listed: those within fog's end of an eye or of the location)
    std::vector<V3> eyes;
    for (const auto& [n, v] : d.views) if (v.hasCamera) eyes.push_back(v.eye);
    float reach = std::max(d.look.hasFog ? d.look.fogEnd : 0.0f, 60.0f);
    for (const auto& [n, v] : d.views) if (v.look.hasFog) reach = std::max(reach, std::min(v.look.fogEnd, 200.0f));

    Reader rr(root);
    size_t k = 0;
    for (auto& [tag, c] : topChunks(root)) {
        if (tag != rtag("MCNK")) continue;
        size_t index = k++;
        int ix = int(rr.u32(c.at + 4)), iy = int(rr.u32(c.at + 8));
        uint32_t flags = rr.u32(c.at);
        V3 pos(rr.f32(c.at + 0x68), rr.f32(c.at + 0x6C), rr.f32(c.at + 0x70));
        if (!d.terrainChunks.empty()) {
            if (std::find(d.terrainChunks.begin(), d.terrainChunks.end(), std::make_pair(ix, iy)) == d.terrainChunks.end()) continue;
        } else {
            V3 centre = toLocal(V3(pos.x - kChunk / 2, pos.y - kChunk / 2, pos.z));
            bool near = (V3(centre.x, centre.y, 0) - V3(0, 0, 0)).length() < reach + kChunk;
            for (const V3& e : eyes) near = near || (V3(centre.x - e.x, centre.y - e.y, 0)).length() < reach + kChunk;
            if (!near) continue;
        }
        const float* heights = nullptr;
        const uint8_t* mccv = nullptr;
        Reader cr(root.data() + c.at, c.size);
        for (const Chunk& sc : chunks(cr, 128, c.size)) {
            if (sc.tag == rtag("MCVT") && sc.size >= 145 * 4) heights = reinterpret_cast<const float*>(root.data() + c.at + sc.offset);
            if (sc.tag == rtag("MCCV") && sc.size >= 145 * 4) mccv = root.data() + c.at + sc.offset;
        }
        if (!heights) continue;
        TerrainChunk t;
        // 145 vertices: 9 outer then 8 inner per row, rows along -x, columns along -y (the map's)
        std::vector<V3> p(145);
        std::vector<float> uv(290);
        for (int r = 0, v = 0; r < 17; ++r) {
            bool inner = r & 1;
            for (int col = 0; col < (inner ? 8 : 9); ++col, ++v) {
                float h;
                std::memcpy(&h, &heights[v], 4);
                float row = r * 0.5f, cc = col + (inner ? 0.5f : 0.0f);
                p[size_t(v)] = toLocal(V3(pos.x - row * kUnit, pos.y - cc * kUnit, pos.z + h));
                uv[size_t(v) * 2] = cc;
                uv[size_t(v) * 2 + 1] = row;
            }
        }
        // holes: high resolution (8 x 8 bits) or low (4 x 4)
        uint64_t holes = 0;
        bool hiRes = flags & 0x10000;
        if (hiRes) { std::memcpy(&holes, root.data() + c.at + 0x14, 8); }
        else holes = rr.u16(c.at + 0x3C);
        auto hole = [&](int r, int col) {
            if (hiRes) return ((holes >> (r * 8 + col)) & 1) != 0;
            return ((holes >> ((r / 2) * 4 + col / 2)) & 1) != 0;
        };
        std::vector<V3> normals(145);
        for (int r = 0; r < 8; ++r)
            for (int col = 0; col < 8; ++col) {
                if (hole(r, col)) continue;
                uint16_t o00 = uint16_t(r * 17 + col), o01 = uint16_t(o00 + 1), o10 = uint16_t(o00 + 17), o11 = uint16_t(o10 + 1);
                uint16_t m = uint16_t(r * 17 + 9 + col);
                uint16_t tri[12] = {m, o00, o10, m, o10, o11, m, o11, o01, m, o01, o00};
                for (int q = 0; q < 12; q += 3) {
                    V3 a = p[tri[q]], b = p[tri[q + 1]], e = p[tri[q + 2]];
                    V3 n = (b - a).cross(e - a);
                    if (n.z < 0) { n = n * -1.0f; }
                    for (int z = 0; z < 3; ++z) normals[tri[q + z]] = normals[tri[q + z]] + n;
                    t.indices.insert(t.indices.end(), {tri[q], tri[q + 1], tri[q + 2]});
                    t.tris.insert(t.tris.end(), {a.x, a.y, a.z, b.x, b.y, b.z, e.x, e.y, e.z});
                }
            }
        t.verts.resize(145 * 36);
        t.boundsMin = V3(1e30f, 1e30f, 1e30f);
        t.boundsMax = V3(-1e30f, -1e30f, -1e30f);
        for (size_t v = 0; v < 145; ++v) {
            V3 n = normals[v].normalized();
            float f[8] = {p[v].x, p[v].y, p[v].z, n.x, n.y, n.z, uv[v * 2], uv[v * 2 + 1]};
            std::memcpy(&t.verts[v * 36], f, 32);
            uint8_t shade[4] = {127, 127, 127, 255};
            if (mccv) { shade[0] = mccv[v * 4 + 2]; shade[1] = mccv[v * 4 + 1]; shade[2] = mccv[v * 4]; }
            std::memcpy(&t.verts[v * 36 + 32], shade, 4);
            t.boundsMin = V3(std::min(t.boundsMin.x, p[v].x), std::min(t.boundsMin.y, p[v].y), std::min(t.boundsMin.z, p[v].z));
            t.boundsMax = V3(std::max(t.boundsMax.x, p[v].x), std::max(t.boundsMax.y, p[v].y), std::max(t.boundsMax.z, p[v].z));
        }
        // its layers and their alpha (tex0's chunk of the same index)
        t.alpha.assign(64 * 64 * 4, 0);
        if (index < texChunks.size()) {
            const ChunkRef& tc = texChunks[index];
            Reader tr(tex.data() + tc.at, tc.size);
            const uint8_t* mcal = nullptr;
            size_t mcalSize = 0;
            std::vector<uint32_t> ly;
            for (const Chunk& sc : chunks(tr, 0, tc.size)) {
                if (sc.tag == rtag("MCLY"))
                    for (size_t o = 0; o + 16 <= sc.size && ly.size() < 16; o += 16)
                        for (int q = 0; q < 4; ++q) ly.push_back(tr.u32(sc.offset + o + size_t(q) * 4));
                if (sc.tag == rtag("MCAL")) { mcal = tex.data() + tc.at + sc.offset; mcalSize = sc.size; }
            }
            t.layerCount = int(std::min<size_t>(4, ly.size() / 4));
            for (int l = 0; l < t.layerCount; ++l) {
                uint32_t texId = ly[size_t(l) * 4], lflags = ly[size_t(l) * 4 + 1], off = ly[size_t(l) * 4 + 2];
                t.layers[l] = texId < mdid.size() ? mdid[texId] : 0;
                if (l == 0 || !mcal || !(lflags & 0x100) || off >= mcalSize) continue;
                size_t end = mcalSize;
                for (int q = l + 1; q < t.layerCount; ++q) {
                    uint32_t o2 = ly[size_t(q) * 4 + 2];
                    if ((ly[size_t(q) * 4 + 1] & 0x100) && o2 > off) { end = std::min<size_t>(end, o2); break; }
                }
                uint8_t a[4096];
                readAlpha(mcal + off, end - off, (lflags & 0x200) != 0, a);
                for (size_t i = 0; i < 4096; ++i) t.alpha[i * 4 + size_t(l - 1)] = a[i];
            }
        }
        uploadTerrain(t);
        terrain_.push_back(std::move(t));
    }
    std::fprintf(stderr, "terrain: %zu chunks, %zu textures named\n", terrain_.size(), mdid.size());
}

void Scene::uploadTerrain(TerrainChunk& t) {
    if (!glReady_ || t.verts.empty()) return;
    if (!terrainProg_) terrainProg_ = gl::program(kTerrainVs, kTerrainFs);
    glGenVertexArrays(1, &t.vao);
    glBindVertexArray(t.vao);
    glGenBuffers(1, &t.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, t.vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(t.verts.size()), t.verts.data(), GL_STATIC_DRAW);
    glGenBuffers(1, &t.ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, t.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(t.indices.size() * 2), t.indices.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 36, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 36, (void*)12);
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 36, (void*)24);
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, GL_TRUE, 36, (void*)32);
    glBindVertexArray(0);
    glGenTextures(1, &t.alphaTex);
    glBindTexture(GL_TEXTURE_2D, t.alphaTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, t.alpha.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    for (int l = 0; l < t.layerCount; ++l) texture(t.layers[l], kTerrainTex);
}

void Scene::drawTerrain(const M4& viewProj, const gl::Frustum& f) {
    if (!terrainProg_) return;
    unsigned p = terrainProg_;
    glUseProgram(p);
    glUniformMatrix4fv(loc(p, "uViewProj"), 1, GL_FALSE, viewProj.m);
    V3 sd = V3(light_.sunDir[0], light_.sunDir[1], light_.sunDir[2]).normalized();
    glUniform3f(loc(p, "uSunDir"), sd.x, sd.y, sd.z);
    glUniform3fv(loc(p, "uSunColor"), 1, light_.sunColor);
    glUniform3fv(loc(p, "uAmbient"), 1, light_.ambient);
    glUniform3fv(loc(p, "uFogColor"), 1, light_.fogColor);
    float fogEnd = std::max(light_.fogEnd, light_.fogStart + 1.0f);
    glUniform2f(loc(p, "uFog"), light_.fogStart, fogEnd);
    glUniform3f(loc(p, "uEye"), eye_.x, eye_.y, eye_.z);
    const char* names[4] = {"uL0", "uL1", "uL2", "uL3"};
    for (int l = 0; l < 4; ++l) glUniform1i(loc(p, names[l]), l);
    glUniform1i(loc(p, "uAlpha"), 4);
    for (const TerrainChunk& t : terrain_) {
        if (!t.vao || t.indices.empty() || !f.box(t.boundsMin, t.boundsMax)) continue;
        V3 c = (t.boundsMin + t.boundsMax) * 0.5f;
        if ((c - eye_).length() - (t.boundsMax - t.boundsMin).length() * 0.5f > fogEnd) continue;
        for (int l = 0; l < 4; ++l) {
            glActiveTexture(GLenum(GL_TEXTURE0 + l));
            glBindTexture(GL_TEXTURE_2D, texture(t.layers[std::min(l, std::max(0, t.layerCount - 1))], kTerrainTex).id);
        }
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, t.alphaTex);
        glUniform1i(loc(p, "uLayers"), t.layerCount);
        glBindVertexArray(t.vao);
        glDrawElements(GL_TRIANGLES, GLsizei(t.indices.size()), GL_UNSIGNED_SHORT, nullptr);
        ++frameStats_.drawCalls;
    }
    glBindVertexArray(0);
    glActiveTexture(GL_TEXTURE0);
}

} // namespace wc
