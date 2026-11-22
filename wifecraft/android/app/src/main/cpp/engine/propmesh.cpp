#include "propmesh.h"

#include <cmath>
#include <cstring>

#include <zlib.h>

#ifndef WC_NO_GL
#include <GLES3/gl3.h>
#include "glutil.h"
#endif

namespace wc {

namespace {
struct Reader {
    const uint8_t* d;
    size_t n, p = 0;
    bool ok = true;
    bool need(size_t k) { if (p + k > n) ok = false; return ok; }
    uint32_t u32() { if (!need(4)) return 0; uint32_t v; std::memcpy(&v, d + p, 4); p += 4; return v; }
    int32_t i32() { return int32_t(u32()); }
    float f32() { uint32_t v = u32(); float f; std::memcpy(&f, &v, 4); return f; }
    uint16_t u16() { if (!need(2)) return 0; uint16_t v; std::memcpy(&v, d + p, 2); p += 2; return v; }
};
} // namespace

bool parseProp(const uint8_t* data, size_t size, PropData& out, std::string& error) {
    auto fail = [&](const char* why) { error = std::string("The prop file is damaged (") + why + ")."; return false; };
    if (size < 56) return fail("too short");
    uint32_t crc = uint32_t(crc32(0, data, uInt(size - 4)));
    uint32_t want; std::memcpy(&want, data + size - 4, 4);
    if (crc != want) return fail("checksum");
    Reader r{data, size - 4};
    if (r.u32() != kPropMagic) return fail("not a prop file");
    if (r.u32() != kPropVersion) return fail("version");
    uint64_t lo = r.u32(), hi = r.u32();
    out = PropData();
    out.guid = lo | (hi << 32);
    for (float& b : out.bounds) b = r.f32();
    uint32_t nb = r.u32();
    if (!r.ok || nb > 4096) return fail("bones");
    for (uint32_t i = 0; i < nb; ++i) {
        uint16_t l = r.u16();
        if (!r.need(l)) return fail("bone names");
        out.bones.emplace_back(reinterpret_cast<const char*>(data + r.p), l);
        r.p += l;
    }
    uint32_t nm = r.u32();
    if (!r.ok || nm > 256) return fail("meshes");
    for (uint32_t i = 0; i < nm; ++i) {
        PropMeshData m;
        uint32_t nv = r.u32(), ni = r.u32(), flags = r.u32();
        for (float& c : m.color) c = r.f32();
        m.texture = r.i32();
        if (!r.ok || ni % 3 != 0 || nv > 2000000 || ni > 6000000) return fail("mesh size");
        if (!r.need(size_t(nv) * 12)) return fail("positions");
        m.pos.resize(size_t(nv) * 3);
        std::memcpy(m.pos.data(), data + r.p, size_t(nv) * 12); r.p += size_t(nv) * 12;
        if (flags & 1) {
            if (!r.need(size_t(nv) * 12)) return fail("normals");
            m.nrm.resize(size_t(nv) * 3);
            std::memcpy(m.nrm.data(), data + r.p, size_t(nv) * 12); r.p += size_t(nv) * 12;
        }
        if (flags & 2) {
            if (!r.need(size_t(nv) * 8)) return fail("uvs");
            m.uv.resize(size_t(nv) * 2);
            std::memcpy(m.uv.data(), data + r.p, size_t(nv) * 8); r.p += size_t(nv) * 8;
        }
        if (!r.need(size_t(ni) * 4)) return fail("indices");
        m.idx.resize(ni);
        std::memcpy(m.idx.data(), data + r.p, size_t(ni) * 4); r.p += size_t(ni) * 4;
        for (uint32_t k : m.idx) if (k >= nv) return fail("index outside the vertices");
        out.meshes.push_back(std::move(m));
    }
    uint32_t nt = r.u32();
    if (!r.ok || nt > 64) return fail("textures");
    for (uint32_t i = 0; i < nt; ++i) {
        PropTextureData t;
        t.w = r.u32(); t.h = r.u32();
        uint32_t len = r.u32();
        if (!r.ok || t.w < 1 || t.h < 1 || t.w > 4096 || t.h > 4096 || !r.need(len)) return fail("texture");
        t.rgba.resize(size_t(t.w) * t.h * 4);
        uLongf got = uLongf(t.rgba.size());
        if (uncompress(t.rgba.data(), &got, data + r.p, len) != Z_OK || got != t.rgba.size()) return fail("texture data");
        r.p += len;
        out.textures.push_back(std::move(t));
    }
    for (const auto& m : out.meshes) if (m.texture >= int(out.textures.size())) return fail("texture index");
    return true;
}

bool PropDraw::load(uint64_t guid, const uint8_t* data, size_t size, std::string& error) {
    PropData d;
    if (!parseProp(data, size, d, error)) return false;
    auto it = props_.find(guid);
    if (it != props_.end()) free(it->second);
    Loaded& l = props_[guid];
    l = Loaded();
    l.data = std::move(d);
    return true;
}

void PropDraw::unload(uint64_t guid) {
    auto it = props_.find(guid);
    if (it == props_.end()) return;
    free(it->second);
    props_.erase(it);
}

#ifndef WC_NO_GL
namespace {
const char* kPropVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
uniform mat4 uViewProj;
uniform mat4 uModel;
out vec3 vNormal;
out vec2 vUv;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    gl_Position = uViewProj * w;
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
}
)";
const char* kPropFs = R"(#version 300 es
precision mediump float;
in vec3 vNormal;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform vec4 uTint;
uniform float uTextured;
out vec4 frag;
void main() {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    // a hemisphere light from above and a sun from up and a little behind: bright enough to read a small toy
    float up = n.z * 0.5 + 0.5;
    float sun = max(dot(n, normalize(vec3(-0.35, 0.25, 0.9))), 0.0);
    float light = 0.38 + 0.30 * up + 0.55 * sun;
    vec4 base = uTextured > 0.5 ? texture(uTex, vUv) : uColor;
    frag = vec4(base.rgb * uTint.rgb * light, 1.0);
}
)";
} // namespace

void PropDraw::free(Loaded& p) {
    for (auto& g : p.gl) { if (g.vao) glDeleteVertexArrays(1, &g.vao); if (g.vbo) glDeleteBuffers(1, &g.vbo); if (g.ibo) glDeleteBuffers(1, &g.ibo); }
    if (!p.tex.empty()) glDeleteTextures(GLsizei(p.tex.size()), p.tex.data());
    p.gl.clear();
    p.tex.clear();
    p.uploaded = false;
}

void PropDraw::upload(Loaded& p) {
    p.gl.clear();
    for (const auto& m : p.data.meshes) {
        GlMesh g;
        const size_t n = m.vertexCount();
        std::vector<float> v(n * 8);
        for (size_t i = 0; i < n; ++i) {
            float* o = &v[i * 8];
            o[0] = m.pos[i * 3]; o[1] = m.pos[i * 3 + 1]; o[2] = m.pos[i * 3 + 2];
            if (!m.nrm.empty()) { o[3] = m.nrm[i * 3]; o[4] = m.nrm[i * 3 + 1]; o[5] = m.nrm[i * 3 + 2]; } else { o[3] = 0; o[4] = 0; o[5] = 1; }
            if (!m.uv.empty()) { o[6] = m.uv[i * 2]; o[7] = m.uv[i * 2 + 1]; } else { o[6] = 0; o[7] = 0; }
        }
        glGenVertexArrays(1, &g.vao);
        glGenBuffers(1, &g.vbo);
        glGenBuffers(1, &g.ibo);
        glBindVertexArray(g.vao);
        glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(v.size() * sizeof(float)), v.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, nullptr);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(12));
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(24));
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(m.idx.size() * sizeof(uint32_t)), m.idx.data(), GL_STATIC_DRAW);
        glBindVertexArray(0);
        g.count = int(m.idx.size());
        p.gl.push_back(g);
    }
    p.tex.assign(p.data.textures.size(), 0);
    for (size_t i = 0; i < p.data.textures.size(); ++i) {
        const auto& t = p.data.textures[i];
        glGenTextures(1, &p.tex[i]);
        glBindTexture(GL_TEXTURE_2D, p.tex[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GLsizei(t.w), GLsizei(t.h), 0, GL_RGBA, GL_UNSIGNED_BYTE, t.rgba.data());
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }
    p.uploaded = true;
}

void PropDraw::draw(const std::vector<PropInstance>& list, const M4& viewProj, const V3& eye) {
    (void)eye;
    if (list.empty() || props_.empty()) return;
    if (!prog_) {
        prog_ = gl::program(kPropVs, kPropFs);
        if (!prog_) return;
    }
    glUseProgram(prog_);
    glUniformMatrix4fv(gl::loc(prog_, "uViewProj"), 1, GL_FALSE, viewProj.m);
    glUniform1i(gl::loc(prog_, "uTex"), 0);
    const GLboolean cull = glIsEnabled(GL_CULL_FACE);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
    glActiveTexture(GL_TEXTURE0);
    for (const PropInstance& inst : list) {
        auto it = props_.find(inst.guid);
        if (it == props_.end()) continue;
        Loaded& p = it->second;
        if (!p.uploaded) upload(p);
        glUniformMatrix4fv(gl::loc(prog_, "uModel"), 1, GL_FALSE, inst.model.m);
        glUniform4fv(gl::loc(prog_, "uTint"), 1, inst.tint);
        for (size_t i = 0; i < p.gl.size(); ++i) {
            const PropMeshData& m = p.data.meshes[i];
            const bool textured = m.texture >= 0 && size_t(m.texture) < p.tex.size() && p.tex[size_t(m.texture)];
            glUniform1f(gl::loc(prog_, "uTextured"), textured ? 1.0f : 0.0f);
            glUniform4fv(gl::loc(prog_, "uColor"), 1, m.color);
            if (textured) glBindTexture(GL_TEXTURE_2D, p.tex[size_t(m.texture)]);
            glBindVertexArray(p.gl[i].vao);
            glDrawElements(GL_TRIANGLES, p.gl[i].count, GL_UNSIGNED_INT, nullptr);
        }
    }
    glBindVertexArray(0);
    if (cull) glEnable(GL_CULL_FACE);
}

void PropDraw::release() {
    prog_ = 0;
    white_ = 0;
    for (auto& [id, p] : props_) { p.gl.clear(); p.tex.clear(); p.uploaded = false; }
}
#else
void PropDraw::free(Loaded&) {}
void PropDraw::upload(Loaded&) {}
void PropDraw::draw(const std::vector<PropInstance>&, const M4&, const V3&) {}
void PropDraw::release() {}
#endif

} // namespace wc
