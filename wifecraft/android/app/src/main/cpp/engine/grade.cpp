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
uniform vec3 uLift;
uniform vec3 uGain;
uniform float uGamma;
uniform float uContrast;
uniform float uSat;
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
    vec3 c = clamp(texture(uTex, vUv).rgb, 0.0, 1.0);
    // the area's grade (daylight.cpp cineGradeColour): black to the lift, white to the gain, the mid-tones' power, a gentle
    // S-curve, the saturation
    c = uLift + c * (uGain - uLift);
    c = pow(max(c, vec3(0.0)), vec3(uGamma));
    vec3 s = c * c * (3.0 - 2.0 * c);
    c = mix(c, s, uContrast);
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, uSat);
    float mids = 4.0 * l * (1.0 - l);
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
    // the grade drawn only while the look is on (mood over 0): otherwise neutral
    const bool on = p.mood > 0.001f;
    float lift[3], gain[3];
    for (int i = 0; i < 3; ++i) {
        lift[i] = on && std::isfinite(p.lift[i]) ? std::clamp(p.lift[i], 0.0f, 0.2f) : 0.0f;
        gain[i] = on && std::isfinite(p.gain[i]) ? std::clamp(p.gain[i], 0.5f, 1.5f) : 1.0f;
    }
    glUniform3fv(gl::loc(prog_, "uLift"), 1, lift);
    glUniform3fv(gl::loc(prog_, "uGain"), 1, gain);
    glUniform1f(gl::loc(prog_, "uGamma"), on && std::isfinite(p.gamma) ? std::clamp(p.gamma, 0.5f, 2.0f) : 1.0f);
    glUniform1f(gl::loc(prog_, "uContrast"), on && std::isfinite(p.contrast) ? std::clamp(p.contrast, 0.0f, 1.0f) : 0.0f);
    glUniform1f(gl::loc(prog_, "uSat"), on && std::isfinite(p.saturation) ? std::clamp(p.saturation, 0.0f, 2.0f) : 1.0f);
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
