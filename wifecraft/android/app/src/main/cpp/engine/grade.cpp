// The cinematic look's last pass (grade.h).
#include "grade.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>

#include "glutil.h"

namespace wc {

namespace {

// One triangle over the whole view, from gl_VertexID (no buffer).
const char* kGradeVs = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kGradeFs = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uMood;
uniform float uVignette;
uniform float uGrain;
uniform float uBars;
uniform float uFrame;
uniform vec2 uSize;
out vec4 fragColor;
float hash(vec2 p) {
    p = fract(p * vec2(443.897, 441.423));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}
void main() {
    vec3 c = texture(uTex, vUv).rgb;
    // deeper shadows and fuller highlights: a gentle S-curve
    vec3 s = c * c * (3.0 - 2.0 * c);
    c = mix(c, s, 0.35 * uMood);
    // a warm lift where the picture is neither dark nor bright (the skin, the wood), a little more colour
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    float mids = 4.0 * l * (1.0 - l);
    c += vec3(0.040, 0.016, -0.010) * mids * uMood;
    c = mix(vec3(l), c, 1.0 + 0.12 * uMood);
    // the corners darker
    float aspect = uSize.x / max(uSize.y, 1.0);
    vec2 q = (vUv - 0.5) * vec2(aspect, 1.0);
    float d = length(q) / length(vec2(aspect, 1.0) * 0.5);
    c *= 1.0 - 0.6 * uVignette * smoothstep(0.35, 1.05, d);
    // film grain: strongest on the mid-tones, new 24 times a second
    float n = hash(floor(gl_FragCoord.xy) + uFrame * vec2(37.0, 17.0)) - 0.5;
    c += n * 0.08 * uGrain * (0.4 + mids);
    // the bars, their edges a pixel soft
    float px = 1.0 / max(uSize.y, 1.0);
    float bar = smoothstep(uBars - px, uBars, vUv.y) * smoothstep(uBars - px, uBars, 1.0 - vUv.y);
    c *= uBars > 0.0 ? bar : 1.0;
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
)";

} // namespace

float letterboxBars(int width, int height, float aspect) {
    if (width <= 0 || height <= 0 || !(aspect > 0)) return 0;
    float a = float(width) / float(height);
    return a >= aspect ? 0.0f : 0.5f * (1.0f - a / aspect);
}

bool Grade::begin(int width, int height) {
    if (failed_ || width <= 0 || height <= 0) return false;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo_);
    if (!prog_) {
        prog_ = gl::program(kGradeVs, kGradeFs);
        if (!prog_) { failed_ = true; return false; }
        glGenVertexArrays(1, &vao_);
    }
    if (!fbo_ || width != w_ || height != h_) {
        if (!fbo_) { glGenFramebuffers(1, &fbo_); glGenTextures(1, &colour_); glGenRenderbuffers(1, &depth_); }
        glBindTexture(GL_TEXTURE_2D, colour_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindRenderbuffer(GL_RENDERBUFFER, depth_);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colour_, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth_);
        bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        w_ = width; h_ = height;
        if (!ok) {
            glBindFramebuffer(GL_FRAMEBUFFER, GLuint(prevFbo_));
            release();
            failed_ = true;
            return false;
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    return true;
}

void Grade::end(int width, int height, const GradeParams& p) {
    glBindFramebuffer(GL_FRAMEBUFFER, GLuint(prevFbo_));
    glViewport(0, 0, width, height);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDepthMask(GL_TRUE);
    glUseProgram(prog_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, colour_);
    glUniform1i(gl::loc(prog_, "uTex"), 0);
    glUniform1f(gl::loc(prog_, "uMood"), std::clamp(p.mood, 0.0f, 1.0f));
    glUniform1f(gl::loc(prog_, "uVignette"), std::clamp(p.vignette, 0.0f, 1.0f));
    glUniform1f(gl::loc(prog_, "uGrain"), std::clamp(p.grain, 0.0f, 1.0f));
    glUniform1f(gl::loc(prog_, "uBars"), std::clamp(p.bars, 0.0f, 0.5f));
    glUniform1f(gl::loc(prog_, "uFrame"), std::floor(std::fmod(std::max(p.seconds, 0.0f), 1000.0f) * 24.0f));
    glUniform2f(gl::loc(prog_, "uSize"), float(width), float(height));
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glEnable(GL_DEPTH_TEST);
}

void Grade::release() {
    if (fbo_) glDeleteFramebuffers(1, &fbo_);
    if (colour_) glDeleteTextures(1, &colour_);
    if (depth_) glDeleteRenderbuffers(1, &depth_);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    if (prog_) glDeleteProgram(prog_);
    forget();
}

void Grade::forget() {
    fbo_ = colour_ = depth_ = prog_ = vao_ = 0;
    w_ = h_ = 0;
    failed_ = false;
}

} // namespace wc
