#include "skin.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace wc {

namespace {

const char* kVs = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
)";

// The base is a copy of what the target held under this layer's section, at the same pixels.
const char* kFs = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uLayer;
uniform sampler2D uBase;
uniform int uBlend;
uniform vec4 uTint1;
uniform vec4 uTint2;
out vec4 fragColor;
vec3 overlay(vec3 b, vec3 l) {
    return mix(2.0 * b * l, 1.0 - 2.0 * (1.0 - b) * (1.0 - l), step(0.5, b));
}
void main() {
    vec4 l = texture(uLayer, vUv);
    vec4 b = texelFetch(uBase, ivec2(gl_FragCoord.xy), 0);
    if (uTint1.a > 0.0) l.rgb = uTint2.a > 0.0 ? mix(uTint2.rgb, uTint1.rgb, l.rgb) : l.rgb * uTint1.rgb;
    float a = l.a;
    vec4 o;
    if (uBlend == 0) o = vec4(l.rgb, 1.0);                                   // none
    else if (uBlend == 1) o = l;                                              // blit
    else if (uBlend == 2) o = a >= 0.5 ? vec4(l.rgb, 1.0) : b;                // blit with an alpha mask
    else if (uBlend == 3) o = vec4(min(b.rgb + l.rgb * a, 1.0), b.a);         // add
    else if (uBlend == 4) o = vec4(mix(b.rgb, b.rgb * l.rgb, a), b.a);        // multiply
    else if (uBlend == 5) o = vec4(mix(b.rgb, min(b.rgb * l.rgb * 2.0, 1.0), a), b.a);   // mod2x
    else if (uBlend == 6) o = vec4(mix(b.rgb, overlay(b.rgb, l.rgb), a), b.a);           // overlay
    else if (uBlend == 7) o = vec4(mix(b.rgb, 1.0 - (1.0 - b.rgb) * (1.0 - l.rgb), a), b.a);   // screen
    else if (uBlend == 8) o = vec4(mix(b.rgb, overlay(l.rgb, b.rgb), a), b.a);           // hard light
    else o = vec4(mix(b.rgb, l.rgb, a), a + b.a * (1.0 - a));                  // 9 alpha straight, 15 infer alpha, others
    fragColor = o;
}
)";

unsigned shader(unsigned kind, const char* src) {
    unsigned s = glCreateShader(kind);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    int ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        std::fprintf(stderr, "skin shader: %s\n", log);
    }
    return s;
}

void colour(uint32_t argb, float out[4]) {
    out[0] = float(argb >> 16 & 255) / 255.0f;
    out[1] = float(argb >> 8 & 255) / 255.0f;
    out[2] = float(argb & 255) / 255.0f;
    out[3] = float(argb >> 24 & 255) / 255.0f;
}

bool whole(const SkinLayer& l) {
    return l.w <= 0 || l.h <= 0 || (l.x == 0 && l.y == 0 && l.w >= l.materialW && l.h >= l.materialH);
}

} // namespace

bool directLayer(const std::vector<SkinLayer>& layers) {
    if (layers.size() != 1) return false;
    const SkinLayer& l = layers[0];
    return (l.blend == 0 || l.blend == 1) && whole(l) && !(l.tint1 >> 24) && !(l.tint2 >> 24);
}

bool SkinCompositor::initGl() {
    prog_ = glCreateProgram();
    unsigned v = shader(GL_VERTEX_SHADER, kVs), f = shader(GL_FRAGMENT_SHADER, kFs);
    glAttachShader(prog_, v);
    glAttachShader(prog_, f);
    glLinkProgram(prog_);
    glDeleteShader(v);
    glDeleteShader(f);
    int ok = 0;
    glGetProgramiv(prog_, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[2048];
        glGetProgramInfoLog(prog_, sizeof log, nullptr, log);
        std::fprintf(stderr, "skin program: %s\n", log);
        return false;
    }
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glGenFramebuffers(1, &fbo_);
    ready_ = true;
    return true;
}

void SkinCompositor::releaseGl() {
    prog_ = vao_ = vbo_ = fbo_ = 0;
    ready_ = false;
}

unsigned SkinCompositor::compose(const std::vector<SkinLayer>& input, const std::function<unsigned(uint32_t)>& texture, int maxSize) {
    if (!ready_ || input.empty()) return 0;
    std::vector<SkinLayer> layers = input;
    std::stable_sort(layers.begin(), layers.end(), [](const SkinLayer& a, const SkinLayer& b) { return a.layer < b.layer; });
    int mw = 0, mh = 0;
    for (const SkinLayer& l : layers) { mw = std::max(mw, l.materialW); mh = std::max(mh, l.materialH); }
    if (mw <= 0 || mh <= 0) return 0;
    float scale = std::min(1.0f, float(maxSize) / float(std::max(mw, mh)));
    int tw = std::max(1, int(std::lround(mw * scale))), th = std::max(1, int(std::lround(mh * scale)));

    GLint prevFbo = 0, prevVp[4];
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, prevVp);
    GLboolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE);

    unsigned target = 0, base = 0, sampler = 0;
    glGenTextures(1, &target);
    glBindTexture(GL_TEXTURE_2D, target);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, tw, th, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glGenTextures(1, &base);
    glBindTexture(GL_TEXTURE_2D, base);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, tw, th, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    // Layer textures are the scene's own (repeating); here each is clamped to its section.
    glGenSamplers(1, &sampler);
    glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
    glViewport(0, 0, tw, th);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(prog_);
    glUniform1i(glGetUniformLocation(prog_, "uLayer"), 0);
    glUniform1i(glGetUniformLocation(prog_, "uBase"), 1);
    int uBlend = glGetUniformLocation(prog_, "uBlend");
    int uTint1 = glGetUniformLocation(prog_, "uTint1"), uTint2 = glGetUniformLocation(prog_, "uTint2");
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (void*)8);

    for (const SkinLayer& l : layers) {
        unsigned tex = l.fileId ? texture(l.fileId) : 0;
        if (!tex) continue;
        // Its section, in the material's pixels (top row first, as the model's UVs read the texture),
        // then in the target's.
        float x0 = 0, y0 = 0, x1 = float(mw), y1 = float(mh);
        if (!whole(l)) {
            float sx = l.materialW > 0 ? float(mw) / float(l.materialW) : 1.0f;
            float sy = l.materialH > 0 ? float(mh) / float(l.materialH) : 1.0f;
            x0 = l.x * sx; y0 = l.y * sy; x1 = (l.x + l.w) * sx; y1 = (l.y + l.h) * sy;
        }
        int px0 = std::max(0, int(std::floor(x0 * scale))), py0 = std::max(0, int(std::floor(y0 * scale)));
        int px1 = std::min(tw, int(std::ceil(x1 * scale))), py1 = std::min(th, int(std::ceil(y1 * scale)));
        if (px1 <= px0 || py1 <= py0) continue;
        // What lies under the section now, for the blend to read.
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, base);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, px0, py0, px0, py0, px1 - px0, py1 - py0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindSampler(0, sampler);
        // Row 0 of the target is the texture's top: the framebuffer's bottom row.
        float nx0 = x0 * scale / tw * 2 - 1, nx1 = x1 * scale / tw * 2 - 1;
        float ny0 = y0 * scale / th * 2 - 1, ny1 = y1 * scale / th * 2 - 1;
        float quad[4][4] = {{nx0, ny0, 0, 0}, {nx1, ny0, 1, 0}, {nx0, ny1, 0, 1}, {nx1, ny1, 1, 1}};
        glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_DYNAMIC_DRAW);
        glUniform1i(uBlend, l.blend);
        float t1[4], t2[4];
        colour(l.tint1, t1);
        colour(l.tint2, t2);
        glUniform4fv(uTint1, 1, t1);
        glUniform4fv(uTint2, 1, t2);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glBindSampler(0, 0);
    glBindVertexArray(0);

    glBindTexture(GL_TEXTURE_2D, target);
    glGenerateMipmap(GL_TEXTURE_2D);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

    glDeleteTextures(1, &base);
    glDeleteSamplers(1, &sampler);
    glBindFramebuffer(GL_FRAMEBUFFER, GLuint(prevFbo));
    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
    if (depth) glEnable(GL_DEPTH_TEST);
    if (blend) glEnable(GL_BLEND);
    if (cull) glEnable(GL_CULL_FACE);
    glActiveTexture(GL_TEXTURE0);
    return target;
}

} // namespace wc
