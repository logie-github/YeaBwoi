// The graphics options' offscreen chain (postfx.h).
#include "postfx.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>

#include "glutil.h"

namespace wc {

namespace {

// EXT_disjoint_timer_query and EXT_texture_filter_anisotropic's enums (not in gl3.h)
constexpr GLenum kTimeElapsed = 0x88BF, kGpuDisjoint = 0x8FBB;
constexpr GLenum kMaxAnisotropy = 0x84FE, kMaxMaxAnisotropy = 0x84FF;
typedef void (*GetQueryObjectui64v)(GLuint, GLenum, uint64_t*);

bool hasExtension(const char* name) {
    GLint n = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &n);
    for (GLint i = 0; i < n; ++i) {
        const char* e = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, GLuint(i)));
        if (e && std::strcmp(e, name) == 0) return true;
    }
    return false;
}

double nowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// ---- shaders (OpenGL ES 3.0)

const char* kVs = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

// the depth texture as view space (uProj: 1/P00, 1/P11, near, far)
const char* kHead = R"(#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform highp sampler2D uDepth;
uniform vec4 uProj;
float linDepth(float d) { float z = d * 2.0 - 1.0; return 2.0 * uProj.z * uProj.w / (uProj.w + uProj.z - z * (uProj.w - uProj.z)); }
vec3 viewPos(vec2 uv) { float z = linDepth(texture(uDepth, uv).r); return vec3((uv * 2.0 - 1.0) * uProj.xy * z, -z); }
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
)";

const char* kAoFs = R"(
uniform vec2 uTexel;
uniform float uRadius;
uniform float uIntensity;
uniform int uSamples;
uniform vec3 uLightV;
uniform float uContact;
void main() {
    float d = texture(uDepth, vUv).r;
    if (d >= 0.99999) { o = vec4(1.0, 1.0, 0.0, 1.0); return; }
    vec3 p = viewPos(vUv);
    vec3 px1 = viewPos(vUv + vec2(uTexel.x, 0.0)), px0 = viewPos(vUv - vec2(uTexel.x, 0.0));
    vec3 py1 = viewPos(vUv + vec2(0.0, uTexel.y)), py0 = viewPos(vUv - vec2(0.0, uTexel.y));
    vec3 dx = abs(px1.z - p.z) < abs(p.z - px0.z) ? px1 - p : p - px0;
    vec3 dy = abs(py1.z - p.z) < abs(p.z - py0.z) ? py1 - p : p - py0;
    vec3 n = normalize(cross(dx, dy));
    if (dot(n, p) > 0.0) n = -n;
    float z = -p.z;
    float noise = ign(gl_FragCoord.xy);
    float ang = noise * 6.2831853;
    vec2 rUv = vec2(uRadius / (z * uProj.x), uRadius / (z * uProj.y)) * 0.5;
    float occ = 0.0;
    float r2 = uRadius * uRadius;
    for (int i = 0; i < 16; ++i) {
        if (i >= uSamples) break;
        float t = (float(i) + 0.5 + 0.5 * noise) / float(uSamples);
        float a = ang + float(i) * 2.39996323;
        vec3 s = viewPos(vUv + vec2(cos(a), sin(a)) * rUv * t);
        vec3 v = s - p;
        float vv = dot(v, v);
        occ += max(dot(v, n) - 0.004 * z, 0.0) / (vv + 0.004) * max(1.0 - vv / r2, 0.0);
    }
    float ao = pow(clamp(1.0 - uIntensity * uRadius * occ / float(uSamples), 0.0, 1.0), 1.5);
    float cs = 1.0;
    if (uContact > 0.0) {
        const float len = 0.35;
        vec3 start = p + n * (0.004 * z);
        for (int i = 1; i <= 12; ++i) {
            vec3 q = start + uLightV * (len * (float(i) - noise) / 12.0);
            vec2 quv = (q.xy / -q.z) / uProj.xy * 0.5 + 0.5;
            if (quv.x < 0.0 || quv.y < 0.0 || quv.x > 1.0 || quv.y > 1.0) break;
            float dz = -q.z - linDepth(texture(uDepth, quv).r);
            if (dz > 0.012 + 0.004 * z && dz < 0.25) { cs = 0.0; break; }
        }
    }
    o = vec4(ao, cs, 0.0, 1.0);
}
)";

const char* kBlurFs = R"(
uniform sampler2D uSrc;
uniform vec2 uDir;
void main() {
    float zc = linDepth(texture(uDepth, vUv).r);
    vec2 acc = texture(uSrc, vUv).rg;
    float wsum = 1.0;
    for (int i = -3; i <= 3; ++i) {
        if (i == 0) continue;
        vec2 uv = vUv + uDir * float(i);
        float z = linDepth(texture(uDepth, uv).r);
        float w = exp(-float(i * i) / 8.0) * max(0.0, 1.0 - abs(z - zc) / (0.03 + 0.03 * zc));
        acc += texture(uSrc, uv).rg * w;
        wsum += w;
    }
    o = vec4(acc / wsum, 0.0, 1.0);
}
)";

const char* kLightFs = R"(
uniform sampler2D uColor;
uniform sampler2D uAo;
uniform highp sampler2DShadow uShadow;
uniform highp sampler2D uShadowRaw;
uniform int uAoOn;
uniform int uShadowOn;
uniform int uSoft;
uniform float uAoK;
uniform float uContactK;
uniform float uShadowK;
uniform float uHaze;
uniform vec3 uLightV;
uniform float uAmbLum;
uniform float uSunLum;
uniform mat4 uViewToShadow;
uniform vec4 uShadowCentre;
uniform float uShadowTexel;
uniform float uShadowRange;
uniform float uNormalOffset;
uniform vec3 uFogColor;
const vec2 kDisk[12] = vec2[](vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
    vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893), vec2(0.507, 0.064),
    vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598));
float shadowAt(vec3 pv, vec3 n, float noise) {
    vec4 s = uViewToShadow * vec4(pv + n * uNormalOffset, 1.0);
    vec3 c = s.xyz / s.w;
    if (c.x <= 0.0 || c.y <= 0.0 || c.x >= 1.0 || c.y >= 1.0 || c.z >= 1.0) return 1.0;
    const float bias = 0.0004;
    float radius = uShadowTexel * 1.5;
    float a = noise * 6.2831853;
    mat2 rot = mat2(cos(a), sin(a), -sin(a), cos(a));
    if (uSoft == 1) {
        float sum = 0.0, nb = 0.0;
        for (int i = 0; i < 8; ++i) {
            float d = texture(uShadowRaw, c.xy + rot * kDisk[i] * uShadowTexel * 8.0).r;
            if (d < c.z - bias) { sum += d; nb += 1.0; }
        }
        if (nb == 0.0) return 1.0;
        float pen = (c.z - sum / nb) * uShadowRange;
        // the penumbra widens with the blocker's distance (a light about 0.06 yards across, seen from the map's box)
        radius = clamp(pen * 0.06 / (2.0 * uShadowCentre.w), uShadowTexel, uShadowTexel * 9.0);
    }
    float v = 0.0;
    for (int i = 0; i < 12; ++i) v += texture(uShadow, vec3(c.xy + rot * kDisk[i] * radius, c.z - bias));
    return v / 12.0;
}
void main() {
    vec3 c = texture(uColor, vUv).rgb;
    float d = texture(uDepth, vUv).r;
    if (d >= 0.99999) { o = vec4(c, 1.0); return; }
    vec3 p = viewPos(vUv);
    vec3 n = normalize(cross(dFdx(p), dFdy(p)));
    if (dot(n, p) > 0.0) n = -n;
    float ndl = max(dot(n, uLightV), 0.0);
    float direct = uSunLum * ndl;
    float share = direct / (direct + uAmbLum + 1e-4);
    float f = 1.0;
    float contact = 1.0;
    if (uAoOn == 1) {
        vec2 a = texture(uAo, vUv).rg;
        f *= mix(1.0, a.r, uAoK * (0.45 + 0.55 * (1.0 - share)));
        contact = a.g;
    }
    float vis = 1.0;
    if (uShadowOn == 1) {
        vis = shadowAt(p, n, ign(gl_FragCoord.xy));
        vis = mix(1.0, vis, uShadowK * (1.0 - smoothstep(0.8, 1.0, length(p - uShadowCentre.xyz) / uShadowCentre.w)));
    }
    vis *= mix(1.0, contact, uContactK);
    float lit = max(share, 0.38 * smoothstep(0.0, 0.25, ndl));
    f *= 1.0 - clamp(lit, 0.0, 0.8) * (1.0 - vis);
    c *= f;
    if (uHaze > 0.0) c = mix(c, uFogColor, uHaze * 0.55 * (1.0 - exp(-max(-p.z - 1.5, 0.0) * 0.045)));
    o = vec4(c, 1.0);
}
)";

const char* kDownFs = R"(
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform int uPrefilter;
uniform float uThreshold;
void main() {
    vec3 s = texture(uSrc, vUv).rgb * 4.0;
    s += texture(uSrc, vUv + vec2(-1.0, -1.0) * uTexel).rgb;
    s += texture(uSrc, vUv + vec2(1.0, -1.0) * uTexel).rgb;
    s += texture(uSrc, vUv + vec2(-1.0, 1.0) * uTexel).rgb;
    s += texture(uSrc, vUv + vec2(1.0, 1.0) * uTexel).rgb;
    s *= 0.125;
    if (uPrefilter == 1) {
        float br = max(s.r, max(s.g, s.b));
        const float knee = 0.2;
        float soft = clamp(br - uThreshold + knee, 0.0, 2.0 * knee);
        soft = soft * soft / (4.0 * knee + 1e-4);
        s *= max(soft, br - uThreshold) / max(br, 1e-4);
    }
    o = vec4(max(s, 0.0), 1.0);
}
)";

const char* kUpFs = R"(
uniform sampler2D uSrc;
uniform vec2 uTexel;
void main() {
    vec3 s = texture(uSrc, vUv + vec2(-2.0, 0.0) * uTexel).rgb;
    s += texture(uSrc, vUv + vec2(2.0, 0.0) * uTexel).rgb;
    s += texture(uSrc, vUv + vec2(0.0, -2.0) * uTexel).rgb;
    s += texture(uSrc, vUv + vec2(0.0, 2.0) * uTexel).rgb;
    s += texture(uSrc, vUv + vec2(-1.0, 1.0) * uTexel).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(1.0, 1.0) * uTexel).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(1.0, -1.0) * uTexel).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(-1.0, -1.0) * uTexel).rgb * 2.0;
    o = vec4(s / 12.0, 1.0);
}
)";

// the circle of confusion: 0 over the band kept sharp, toward uCocScale far behind it (half-size pixels; negative: nearer)
const char* kCocFn = R"(
uniform vec2 uFocus;
uniform float uCocScale;
uniform float uMaxCoc;
float cocAt(float z) {
    float c = z < uFocus.x ? -(uFocus.x - z) / max(z, 0.05) : z > uFocus.y ? (z - uFocus.y) / z : 0.0;
    return clamp(c * uCocScale, -uMaxCoc, uMaxCoc);
}
)";

const char* kCocFs = R"(
uniform sampler2D uSrc;
uniform vec2 uTexel;
void main() {
    vec3 c = texture(uSrc, vUv + vec2(-0.5, -0.5) * uTexel).rgb + texture(uSrc, vUv + vec2(0.5, -0.5) * uTexel).rgb +
             texture(uSrc, vUv + vec2(-0.5, 0.5) * uTexel).rgb + texture(uSrc, vUv + vec2(0.5, 0.5) * uTexel).rgb;
    o = vec4(c * 0.25, cocAt(linDepth(texture(uDepth, vUv).r)));
}
)";

const char* kDofFs = R"(
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uMaxCoc;
void main() {
    vec4 c0 = texture(uSrc, vUv);
    float r0 = abs(c0.a);
    vec3 acc = c0.rgb;
    float wsum = 1.0;
    if (uMaxCoc > 0.5) {
        for (int i = 0; i < 24; ++i) {
            float t = sqrt((float(i) + 0.5) / 24.0);
            float a = float(i) * 2.39996323;
            float dist = t * uMaxCoc;
            vec4 s = texture(uSrc, vUv + vec2(cos(a), sin(a)) * dist * uTexel);
            float rs = abs(s.a);
            float reach = s.a > c0.a ? min(rs, r0) : rs;
            float w = clamp(reach - dist + 1.0, 0.0, 1.0);
            acc += s.rgb * w;
            wsum += w;
        }
    }
    o = vec4(acc / wsum, r0);
}
)";

const char* kShaftsFs = R"(
uniform sampler2D uSrc;
uniform vec2 uSun;
uniform float uThreshold;
float mask(vec2 uv) {
    vec3 c = texture(uSrc, uv).rgb;
    float sky = texture(uDepth, uv).r >= 0.99999 ? 1.0 : 0.3;
    return max(max(c.r, max(c.g, c.b)) - uThreshold, 0.0) * sky;
}
void main() {
    vec2 delta = (uSun - vUv) * (0.85 / 32.0);
    vec2 uv = vUv;
    float decay = 1.0, acc = 0.0;
    float j = ign(gl_FragCoord.xy);
    uv += delta * j;
    for (int i = 0; i < 32; ++i) {
        acc += mask(uv) * decay;
        decay *= 0.95;
        uv += delta;
    }
    o = vec4(vec3(acc / 12.0), 1.0);
}
)";

const char* kFinalFs = R"(
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform sampler2D uDof;
uniform sampler2D uShafts;
uniform vec2 uSrcTexel;
uniform float uSharpen;
uniform float uChroma;
uniform int uDofOn;
uniform int uBloomOn;
uniform float uBloomK;
uniform int uShaftsOn;
uniform vec3 uShaftColor;
uniform int uTonemap;
uniform float uExposure;
uniform vec2 uAces;
uniform float uVignette;
uniform float uGrain;
uniform float uFrame;
uniform vec2 uSize;
vec3 sceneAt(vec2 uv) {
    vec3 c = texture(uScene, uv).rgb;
    if (uSharpen > 0.0) {
        vec3 a = texture(uScene, uv + vec2(0.0, -uSrcTexel.y)).rgb;
        vec3 b = texture(uScene, uv + vec2(-uSrcTexel.x, 0.0)).rgb;
        vec3 d = texture(uScene, uv + vec2(uSrcTexel.x, 0.0)).rgb;
        vec3 e = texture(uScene, uv + vec2(0.0, uSrcTexel.y)).rgb;
        vec3 mn = min(min(min(a, b), min(d, e)), c);
        vec3 mx = max(max(max(a, b), max(d, e)), c);
        vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
        vec3 w = -amp / mix(8.0, 5.0, uSharpen);
        c = max((c + (a + b + d + e) * w) / (1.0 + 4.0 * w), 0.0);
    }
    return c;
}
vec3 tone(vec3 c) {
    if (uTonemap == 0 && uExposure == 0.0) return c;
    vec3 lin = pow(max(c, 0.0), vec3(2.2)) * exp2(uExposure);
    if (uTonemap == 1) {
        float m = max(lin.r, max(lin.g, lin.b));
        const float k = 0.6;
        if (m > k) {
            float t = (m - k) / (1.0 - k);
            float y = k + (1.0 - k) * t / (1.0 + t);
            lin *= y / m;
            lin = mix(lin, vec3(y), clamp((m - 1.0) * 0.15, 0.0, 0.5));
        }
    } else if (uTonemap == 2) {
        vec3 x = lin * uAces.x;
        lin = clamp(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0) * uAces.y;
    }
    return pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));
}
void main() {
    vec2 uv = vUv;
    vec3 c = sceneAt(uv);
    if (uChroma > 0.0) {
        vec2 off = (uv - 0.5) * uChroma * 0.006;
        c.r = texture(uScene, uv + off).r;
        c.b = texture(uScene, uv - off).b;
    }
    if (uDofOn == 1) {
        vec4 b = texture(uDof, uv);
        float r = abs(cocAt(linDepth(texture(uDepth, uv).r)));
        c = mix(c, b.rgb, smoothstep(0.35, 1.35, r));
    }
    if (uBloomOn == 1) c += texture(uBloom, uv).rgb * uBloomK;
    if (uShaftsOn == 1) c += texture(uShafts, uv).r * uShaftColor;
    c = tone(c);
    if (uVignette > 0.0) {
        float aspect = uSize.x / max(uSize.y, 1.0);
        vec2 q = (uv - 0.5) * vec2(aspect, 1.0);
        float dd = length(q) / length(vec2(aspect, 1.0) * 0.5);
        c *= 1.0 - 0.45 * uVignette * smoothstep(0.4, 1.1, dd);
    }
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    if (uGrain > 0.0) c += (ign(gl_FragCoord.xy + uFrame * vec2(37.0, 17.0)) - 0.5) * 0.07 * uGrain * (0.4 + 4.0 * l * (1.0 - l));
    c += (ign(gl_FragCoord.xy + vec2(13.0, 7.0)) - 0.5) / 255.0;
    o = vec4(clamp(c, 0.0, 1.0), 1.0);
}
)";

unsigned make(const char* body, bool coc = false) {
    std::string fs = std::string(kHead) + (coc ? kCocFn : "") + body;
    return gl::program(kVs, fs.c_str());
}

bool linked(unsigned p) {
    if (!p) return false;
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    return ok != 0;
}

// ACES fitted (Narkowicz): the input scale that keeps mid-grey (0.18) and maps 1.0 to white, and the output scale.
float aces(float x) { return x * (2.51f * x + 0.03f) / (x * (2.43f * x + 0.59f) + 0.14f); }
void acesFit(float& k, float& s) {
    float lo = 0.05f, hi = 4.0f;
    for (int i = 0; i < 40; ++i) {
        float m = 0.5f * (lo + hi);
        if (aces(m) / aces(0.18f * m) > 1.0f / 0.18f) lo = m; else hi = m;
    }
    k = 0.5f * (lo + hi);
    s = 1.0f / aces(k);
}

// a rigid view matrix's inverse
M4 rigidInverse(const M4& v) {
    M4 r;
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) r.at(i, j) = v.at(j, i);
    V3 t(v.at(0, 3), v.at(1, 3), v.at(2, 3));
    for (int i = 0; i < 3; ++i) r.at(i, 3) = -(r.at(i, 0) * t.x + r.at(i, 1) * t.y + r.at(i, 2) * t.z);
    return r;
}

V3 rotate(const M4& m, const V3& v) {
    return {m.at(0, 0) * v.x + m.at(0, 1) * v.y + m.at(0, 2) * v.z, m.at(1, 0) * v.x + m.at(1, 1) * v.y + m.at(1, 2) * v.z,
            m.at(2, 0) * v.x + m.at(2, 1) * v.y + m.at(2, 2) * v.z};
}

float lum(const float c[3]) { return 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]; }

int gAnisoMax = -1;

} // namespace

void applyAnisotropy(int level) {
    if (gAnisoMax < 0) {
        gAnisoMax = 0;
        if (hasExtension("GL_EXT_texture_filter_anisotropic")) {
            GLfloat m = 0;
            glGetFloatv(kMaxMaxAnisotropy, &m);
            gAnisoMax = int(m);
        }
    }
    if (gAnisoMax <= 1) return;
    glTexParameterf(GL_TEXTURE_2D, kMaxAnisotropy, float(std::clamp(level, 1, gAnisoMax)));
}

PostFx::PostFx() {
    for (float& g : stats_.gpu) g = NAN;
}

bool PostFx::ensure(Target& t, int w, int h, unsigned internalFormat, bool linear) {
    w = std::max(1, w); h = std::max(1, h);
    if (t.fbo && t.w == w && t.h == h && t.fmt == internalFormat) return true;
    if (!t.fbo) { glGenFramebuffers(1, &t.fbo); glGenTextures(1, &t.tex); }
    glBindTexture(GL_TEXTURE_2D, t.tex);
    GLenum format = GL_RGBA, type = GL_UNSIGNED_BYTE;
    if (internalFormat == GL_RGBA16F) type = GL_HALF_FLOAT;
    else if (internalFormat == GL_RG8) format = GL_RG;
    glTexImage2D(GL_TEXTURE_2D, 0, GLint(internalFormat), w, h, 0, format, type, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
    t.w = w; t.h = h; t.fmt = internalFormat;
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

void PostFx::drop(Target& t) {
    if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
    if (t.tex) glDeleteTextures(1, &t.tex);
    t = Target();
}

bool PostFx::ensurePrograms() {
    if (progsTried_) return pFinal_ != 0;
    progsTried_ = true;
    pAo_ = make(kAoFs);
    pBlur_ = make(kBlurFs);
    pLight_ = make(kLightFs);
    pDown_ = make(kDownFs);
    pUp_ = make(kUpFs);
    pCoc_ = make(kCocFs, true);
    pDof_ = make(kDofFs);
    pShafts_ = make(kShaftsFs);
    pFinal_ = make(kFinalFs, true);
    for (unsigned p : {pAo_, pBlur_, pLight_, pDown_, pUp_, pCoc_, pDof_, pShafts_, pFinal_})
        if (!linked(p)) { failed_ = true; pFinal_ = 0; return false; }
    glGenVertexArrays(1, &vao_);
    colourFmt_ = hasExtension("GL_EXT_color_buffer_float") || hasExtension("GL_EXT_color_buffer_half_float") ? GL_RGBA16F : GL_RGBA8;
    return true;
}

void PostFx::bindTarget(const Target& t) {
    glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
    glViewport(0, 0, t.w, t.h);
}

void PostFx::quad() {
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

// ---- timers

void PostFx::cpuBegin() {
    cpuStart_ = nowMs();
    if (!s_.timers && !syncTiming_) return;
    if (!timersTried_) {
        timersTried_ = true;
        timersOk_ = hasExtension("GL_EXT_disjoint_timer_query") && eglGetProcAddress("glGetQueryObjectui64vEXT");
        if (timersOk_) for (auto& f : queries_) glGenQueries(PostStats::kStages, f);
    }
    collectTimers();
}

void PostFx::collectTimers() {
    if (!timersOk_ || syncTiming_) return;
    // the oldest frame in the ring: read what is ready (a frame still running is read next time round)
    int r = (ring_ + 1) % kRing;
    static GetQueryObjectui64v get64 = reinterpret_cast<GetQueryObjectui64v>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
    GLint disjoint = 0;
    glGetIntegerv(kGpuDisjoint, &disjoint);
    bool any = false;
    for (int s = 0; s < PostStats::kStages; ++s) {
        if (!queryUsed_[r][s]) { stats_.gpu[s] = NAN; continue; }
        GLuint ready = 0;
        glGetQueryObjectuiv(queries_[r][s], GL_QUERY_RESULT_AVAILABLE, &ready);
        if (!ready) continue;
        uint64_t ns = 0;
        get64(queries_[r][s], GL_QUERY_RESULT, &ns);
        if (!disjoint) stats_.gpu[s] = float(double(ns) / 1e6);
        queryUsed_[r][s] = false;
        any = true;
    }
    stats_.timersAvailable = any || stats_.timersAvailable;
    ring_ = r;
}

void PostFx::stageBegin(int stage) {
    if (syncTiming_) { glFinish(); stageStart_ = nowMs(); openStage_ = stage; return; }
    if (!s_.timers || !timersOk_ || openStage_ >= 0) return;
    if (queryUsed_[ring_][stage]) return;   // (not read back yet: skipped this frame)
    glBeginQuery(kTimeElapsed, queries_[ring_][stage]);
    queryUsed_[ring_][stage] = true;
    openStage_ = stage;
}

void PostFx::stageEnd() {
    if (openStage_ < 0) return;
    if (syncTiming_) {
        glFinish();
        stats_.gpu[openStage_] = float(nowMs() - stageStart_);
        stats_.timersAvailable = true;
    } else {
        glEndQuery(kTimeElapsed);
    }
    openStage_ = -1;
}

// ---- the shadow map

bool PostFx::shadowBegin() {
    shadowValid_ = false;
    if (!wantsShadows() || !ensurePrograms()) return false;
    int size = s_.shadowQuality >= 1 ? 2048 : 1024;
    if (!shadowFbo_ || shadowSize_ != size) {
        if (!shadowFbo_) {
            glGenFramebuffers(1, &shadowFbo_);
            glGenTextures(1, &shadowTex_);
            glGenSamplers(1, &samplerCmp_);
            glGenSamplers(1, &samplerRaw_);
            glSamplerParameteri(samplerCmp_, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glSamplerParameteri(samplerCmp_, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glSamplerParameteri(samplerCmp_, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
            glSamplerParameteri(samplerCmp_, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
            glSamplerParameteri(samplerCmp_, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(samplerCmp_, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(samplerRaw_, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glSamplerParameteri(samplerRaw_, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(samplerRaw_, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(samplerRaw_, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        glBindTexture(GL_TEXTURE_2D, shadowTex_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &shadowPrevFbo_);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex_, 0);
        GLenum none = GL_NONE;
        glDrawBuffers(1, &none);
        glReadBuffer(GL_NONE);
        bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_FRAMEBUFFER, GLuint(shadowPrevFbo_));
        shadowSize_ = size;
        if (!ok) { s_.shadows = 0; return false; }
    }
    stageBegin(PostStats::kShadow);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &shadowPrevFbo_);
    glGetIntegerv(GL_VIEWPORT, shadowViewport_);
    glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
    glViewport(0, 0, shadowSize_, shadowSize_);
    glDepthMask(GL_TRUE);
    glClear(GL_DEPTH_BUFFER_BIT);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_POLYGON_OFFSET_FILL);
    glPolygonOffset(2.0f, 4.0f);
    return true;
}

void PostFx::shadowEnd(const M4& lightViewProj, const V3& centre, float radius, float depthRange) {
    glDisable(GL_POLYGON_OFFSET_FILL);
    glPolygonOffset(0, 0);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, GLuint(shadowPrevFbo_));
    glViewport(shadowViewport_[0], shadowViewport_[1], shadowViewport_[2], shadowViewport_[3]);
    stageEnd();
    lightViewProj_ = lightViewProj;
    shadowCentre_ = centre;
    shadowRadius_ = radius;
    shadowRange_ = depthRange;
    shadowValid_ = true;
}

// ---- the scene's target

bool PostFx::begin(int width, int height) {
    if (!active() || width <= 0 || height <= 0 || !ensurePrograms()) return false;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo_);
    int w = std::max(16, int(std::lround(width * s_.renderScale)));
    int h = std::max(16, int(std::lround(height * s_.renderScale)));
    int samples = 0;
    if (s_.msaa > 0) {
        GLint maxS = 0;
        glGetInternalformativ(GL_RENDERBUFFER, colourFmt_, GL_SAMPLES, 1, &maxS);
        samples = std::min(s_.msaa, int(maxS));
        if (samples < 2) samples = 0;
    }
    if (!fbo_ || w != sw_ || h != sh_) {
        if (!fbo_) { glGenFramebuffers(1, &fbo_); glGenTextures(1, &colour_); glGenTextures(1, &depth_); }
        glBindTexture(GL_TEXTURE_2D, colour_);
        glTexImage2D(GL_TEXTURE_2D, 0, GLint(colourFmt_), w, h, 0, GL_RGBA, colourFmt_ == GL_RGBA16F ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, depth_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colour_, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth_, 0);
        bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        if (!ok && colourFmt_ != GL_RGBA8) {
            // a float target the driver won't draw into after all: plain 8 bits
            colourFmt_ = GL_RGBA8;
            glBindTexture(GL_TEXTURE_2D, colour_);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        }
        sw_ = w; sh_ = h;
        msSamples_ = -1;   // (made again below at the new size)
        if (!ok) {
            glBindFramebuffer(GL_FRAMEBUFFER, GLuint(prevFbo_));
            release();
            failed_ = true;
            return false;
        }
    }
    if (samples != msSamples_) {
        if (msFbo_) { glDeleteFramebuffers(1, &msFbo_); glDeleteRenderbuffers(1, &msColour_); glDeleteRenderbuffers(1, &msDepth_); }
        msFbo_ = msColour_ = msDepth_ = 0;
        msSamples_ = samples;
        if (samples > 0) {
            glGenFramebuffers(1, &msFbo_);
            glGenRenderbuffers(1, &msColour_);
            glGenRenderbuffers(1, &msDepth_);
            glBindRenderbuffer(GL_RENDERBUFFER, msColour_);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, colourFmt_, w, h);
            glBindRenderbuffer(GL_RENDERBUFFER, msDepth_);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, w, h);
            glBindFramebuffer(GL_FRAMEBUFFER, msFbo_);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msColour_);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msDepth_);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                glDeleteFramebuffers(1, &msFbo_); glDeleteRenderbuffers(1, &msColour_); glDeleteRenderbuffers(1, &msDepth_);
                msFbo_ = msColour_ = msDepth_ = 0;
            }
        }
    }
    stats_.sceneW = w; stats_.sceneH = h;
    glBindFramebuffer(GL_FRAMEBUFFER, msFbo_ ? msFbo_ : fbo_);
    stageBegin(PostStats::kScene);
    return true;
}

void PostFx::end(int outW, int outH, const PostFrame& f) {
    stageEnd();
    if (msFbo_) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msFbo_);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo_);
        glBlitFramebuffer(0, 0, sw_, sh_, 0, 0, sw_, sh_, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBlitFramebuffer(0, 0, sw_, sh_, 0, 0, sw_, sh_, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        GLenum gone[2] = {GL_COLOR_ATTACHMENT0, GL_DEPTH_ATTACHMENT};
        glInvalidateFramebuffer(GL_READ_FRAMEBUFFER, 2, gone);
    }
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDepthMask(GL_FALSE);
    const GraphicsSettings& s = s_;
    const float proj[4] = {1.0f / f.proj.at(0, 0), 1.0f / f.proj.at(1, 1), f.nearZ, f.farZ};
    const int hw = std::max(1, sw_ / 2), hh = std::max(1, sh_ / 2);
    V3 lightW = V3(-f.sunDir[0], -f.sunDir[1], -f.sunDir[2]).normalized();
    V3 lightV = rotate(f.view, lightW).normalized();
    auto common = [&](unsigned p) {
        glUseProgram(p);
        glUniform4fv(gl::loc(p, "uProj"), 1, proj);
        glUniform1i(gl::loc(p, "uDepth"), 1);
    };
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, depth_);
    const bool aoOn = s.ao > 0 || s.contact > 0;
    const bool shadowOn = s.shadows > 0 && shadowValid_;

    // AO and contact shadows, half size, blurred each way
    if (aoOn) {
        stageBegin(PostStats::kAo);
        ensure(aoA_, hw, hh, GL_RG8, true);
        ensure(aoB_, hw, hh, GL_RG8, true);
        bindTarget(aoA_);
        common(pAo_);
        static const int kSamples[3] = {6, 10, 16};
        glUniform2f(gl::loc(pAo_, "uTexel"), 1.0f / float(sw_), 1.0f / float(sh_));
        glUniform1f(gl::loc(pAo_, "uRadius"), 0.45f);
        glUniform1f(gl::loc(pAo_, "uIntensity"), s.ao > 0 ? 0.55f : 0.0f);
        glUniform1i(gl::loc(pAo_, "uSamples"), kSamples[std::clamp(s.aoQuality, 0, 2)]);
        glUniform3f(gl::loc(pAo_, "uLightV"), lightV.x, lightV.y, lightV.z);
        glUniform1f(gl::loc(pAo_, "uContact"), s.contact);
        quad();
        common(pBlur_);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(gl::loc(pBlur_, "uSrc"), 0);
        bindTarget(aoB_);
        glBindTexture(GL_TEXTURE_2D, aoA_.tex);
        glUniform2f(gl::loc(pBlur_, "uDir"), 1.0f / float(hw), 0);
        quad();
        bindTarget(aoA_);
        glBindTexture(GL_TEXTURE_2D, aoB_.tex);
        glUniform2f(gl::loc(pBlur_, "uDir"), 0, 1.0f / float(hh));
        quad();
        stageEnd();
    }

    // the lit picture: AO, shadows, contact shadows, haze
    unsigned sceneTex = colour_;
    if (aoOn || shadowOn || s.haze > 0) {
        stageBegin(PostStats::kLighting);
        if (ensure(lit_, sw_, sh_, colourFmt_, true)) {
            bindTarget(lit_);
            common(pLight_);
            unsigned p = pLight_;
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, colour_);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, aoOn ? aoA_.tex : 0);
            glUniform1i(gl::loc(p, "uColor"), 0);
            glUniform1i(gl::loc(p, "uAo"), 2);
            glUniform1i(gl::loc(p, "uShadow"), 3);
            glUniform1i(gl::loc(p, "uShadowRaw"), 4);
            glUniform1i(gl::loc(p, "uAoOn"), aoOn ? 1 : 0);
            glUniform1f(gl::loc(p, "uAoK"), s.ao);
            glUniform1f(gl::loc(p, "uContactK"), s.contact);
            glUniform1i(gl::loc(p, "uShadowOn"), shadowOn ? 1 : 0);
            glUniform1i(gl::loc(p, "uSoft"), s.shadowQuality >= 1 ? 1 : 0);
            glUniform1f(gl::loc(p, "uShadowK"), s.shadows);
            glUniform1f(gl::loc(p, "uHaze"), s.haze);
            glUniform3f(gl::loc(p, "uLightV"), lightV.x, lightV.y, lightV.z);
            glUniform1f(gl::loc(p, "uAmbLum"), lum(f.ambient));
            glUniform1f(gl::loc(p, "uSunLum"), lum(f.sunColor));
            glUniform3fv(gl::loc(p, "uFogColor"), 1, f.fogColor);
            // texture units 3 and 4: the shadow map compared, and raw (PCSS-lite's blocker search); a 1x1 stand-in otherwise
            glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, shadowOn ? shadowTex_ : 0);
            glBindSampler(3, samplerCmp_);
            glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, shadowOn ? shadowTex_ : 0);
            glBindSampler(4, samplerRaw_);
            if (shadowOn) {
                M4 bias;
                bias.at(0, 0) = bias.at(1, 1) = bias.at(2, 2) = 0.5f;
                bias.at(0, 3) = bias.at(1, 3) = bias.at(2, 3) = 0.5f;
                M4 viewToShadow = bias * lightViewProj_ * rigidInverse(f.view);
                glUniformMatrix4fv(gl::loc(p, "uViewToShadow"), 1, GL_FALSE, viewToShadow.m);
                V3 c = f.view.point(shadowCentre_);
                glUniform4f(gl::loc(p, "uShadowCentre"), c.x, c.y, c.z, shadowRadius_);
                glUniform1f(gl::loc(p, "uShadowTexel"), 1.0f / float(shadowSize_));
                glUniform1f(gl::loc(p, "uShadowRange"), shadowRange_);
                glUniform1f(gl::loc(p, "uNormalOffset"), 2.0f * shadowRadius_ / float(shadowSize_) * 1.5f);
            }
            quad();
            glBindSampler(3, 0);
            glBindSampler(4, 0);
            glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, 0);
            sceneTex = lit_.tex;
        }
        stageEnd();
    }

    // bloom: a dual-filter pyramid from half size down, and back up to half size
    const bool bloomOn = s.bloom > 0;
    if (bloomOn) {
        stageBegin(PostStats::kBloom);
        int w = sw_, h = sh_;
        unsigned src = sceneTex;
        glUseProgram(pDown_);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(gl::loc(pDown_, "uSrc"), 0);
        glUniform1f(gl::loc(pDown_, "uThreshold"), 0.82f);
        for (int i = 0; i < kBloomLevels; ++i) {
            int nw = std::max(1, w / 2), nh = std::max(1, h / 2);
            ensure(bloom_[i], nw, nh, colourFmt_, true);
            bindTarget(bloom_[i]);
            glBindTexture(GL_TEXTURE_2D, src);
            glUniform2f(gl::loc(pDown_, "uTexel"), 1.0f / float(w), 1.0f / float(h));
            glUniform1i(gl::loc(pDown_, "uPrefilter"), i == 0 ? 1 : 0);
            quad();
            src = bloom_[i].tex; w = nw; h = nh;
        }
        glUseProgram(pUp_);
        glUniform1i(gl::loc(pUp_, "uSrc"), 0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        for (int i = kBloomLevels - 1; i > 0; --i) {
            bindTarget(bloom_[i - 1]);
            glBindTexture(GL_TEXTURE_2D, bloom_[i].tex);
            glUniform2f(gl::loc(pUp_, "uTexel"), 0.5f / float(bloom_[i].w), 0.5f / float(bloom_[i].h));
            quad();
        }
        glDisable(GL_BLEND);
        stageEnd();
    }

    // depth of field: half-size circle of confusion and gather
    const bool dofOn = s.dof > 0 && f.focusFar > 0 && (!f.editor || s.dofInEditor);
    const float maxCoc = 0.012f * float(hh) * (0.6f + 0.8f * s.aperture);
    const float cocScale = maxCoc * (0.6f + 1.6f * s.aperture) * s.dof;
    if (dofOn) {
        stageBegin(PostStats::kDof);
        ensure(coc_, hw, hh, colourFmt_ == GL_RGBA16F ? GL_RGBA16F : GL_RGBA8, true);
        ensure(dof_, hw, hh, colourFmt_ == GL_RGBA16F ? GL_RGBA16F : GL_RGBA8, true);
        bindTarget(coc_);
        common(pCoc_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneTex);
        glUniform1i(gl::loc(pCoc_, "uSrc"), 0);
        glUniform2f(gl::loc(pCoc_, "uTexel"), 1.0f / float(sw_), 1.0f / float(sh_));
        glUniform2f(gl::loc(pCoc_, "uFocus"), f.focusNear, f.focusFar);
        glUniform1f(gl::loc(pCoc_, "uCocScale"), cocScale);
        glUniform1f(gl::loc(pCoc_, "uMaxCoc"), maxCoc);
        quad();
        bindTarget(dof_);
        glUseProgram(pDof_);
        glBindTexture(GL_TEXTURE_2D, coc_.tex);
        // (the gather reads the circle's sign: point sampling keeps an edge's near and far apart)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glUniform1i(gl::loc(pDof_, "uSrc"), 0);
        glUniform2f(gl::loc(pDof_, "uTexel"), 1.0f / float(hw), 1.0f / float(hh));
        glUniform1f(gl::loc(pDof_, "uMaxCoc"), maxCoc);
        quad();
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        stageEnd();
    }

    // light shafts toward the sun's place on the screen (only when it is ahead of the camera)
    float shaftK = 0;
    float sunUv[2] = {0.5f, 0.5f};
    if (s.shafts > 0) {
        V3 toward = rotate(f.view, lightW);   // view space
        if (toward.z < -0.05f) {
            float sx = -toward.x / toward.z / proj[0], sy = -toward.y / toward.z / proj[1];
            sunUv[0] = sx * 0.5f + 0.5f; sunUv[1] = sy * 0.5f + 0.5f;
            float off = std::max(std::fabs(sx), std::fabs(sy));
            shaftK = s.shafts * std::clamp(1.6f - off * 0.6f, 0.0f, 1.0f) * std::clamp(-toward.z * 3.0f, 0.0f, 1.0f);
        }
    }
    if (shaftK > 0.001f) {
        stageBegin(PostStats::kShafts);
        ensure(shafts_, std::max(1, sw_ / 4), std::max(1, sh_ / 4), colourFmt_, true);
        bindTarget(shafts_);
        common(pShafts_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneTex);
        glUniform1i(gl::loc(pShafts_, "uSrc"), 0);
        glUniform2f(gl::loc(pShafts_, "uSun"), sunUv[0], sunUv[1]);
        glUniform1f(gl::loc(pShafts_, "uThreshold"), 0.7f);
        quad();
        stageEnd();
    }

    // the final pass, onto the framebuffer bound at begin
    stageBegin(PostStats::kFinal);
    glBindFramebuffer(GL_FRAMEBUFFER, GLuint(prevFbo_));
    glViewport(0, 0, outW, outH);
    unsigned p = pFinal_;
    common(p);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneTex);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, bloomOn ? bloom_[0].tex : 0);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, dofOn ? dof_.tex : 0);
    glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, shaftK > 0.001f ? shafts_.tex : 0);
    glUniform1i(gl::loc(p, "uScene"), 0);
    glUniform1i(gl::loc(p, "uBloom"), 2);
    glUniform1i(gl::loc(p, "uDof"), 3);
    glUniform1i(gl::loc(p, "uShafts"), 4);
    glUniform2f(gl::loc(p, "uSrcTexel"), 1.0f / float(sw_), 1.0f / float(sh_));
    // sharpening: as asked, and a little more where the picture was drawn smaller than the view
    float sharpen = s.sharpen;
    if (s.renderScale < 0.99f) sharpen = std::max(sharpen, std::clamp((1.0f - s.renderScale) * 1.5f, 0.0f, 0.6f));
    glUniform1f(gl::loc(p, "uSharpen"), sharpen);
    glUniform1f(gl::loc(p, "uChroma"), s.chroma);
    glUniform1i(gl::loc(p, "uDofOn"), dofOn ? 1 : 0);
    glUniform2f(gl::loc(p, "uFocus"), f.focusNear, f.focusFar);
    glUniform1f(gl::loc(p, "uCocScale"), cocScale);
    glUniform1f(gl::loc(p, "uMaxCoc"), maxCoc);
    glUniform1i(gl::loc(p, "uBloomOn"), bloomOn ? 1 : 0);
    glUniform1f(gl::loc(p, "uBloomK"), 0.16f * s.bloom);
    glUniform1i(gl::loc(p, "uShaftsOn"), shaftK > 0.001f ? 1 : 0);
    float sc = shaftK * 0.5f;
    glUniform3f(gl::loc(p, "uShaftColor"), f.sunColor[0] * sc, f.sunColor[1] * sc, f.sunColor[2] * sc);
    glUniform1i(gl::loc(p, "uTonemap"), s.tonemap);
    glUniform1f(gl::loc(p, "uExposure"), s.exposure);
    static float aK = 0, aS = 0;
    if (aK == 0) acesFit(aK, aS);
    glUniform2f(gl::loc(p, "uAces"), aK, aS);
    glUniform1f(gl::loc(p, "uVignette"), f.gradeActive ? 0.0f : s.vignette);
    glUniform1f(gl::loc(p, "uGrain"), f.gradeActive ? 0.0f : s.grain);
    glUniform1f(gl::loc(p, "uFrame"), std::floor(std::fmod(std::max(f.seconds, 0.0f), 1000.0f) * 24.0f));
    glUniform2f(gl::loc(p, "uSize"), float(outW), float(outH));
    quad();
    glBindVertexArray(0);
    glActiveTexture(GL_TEXTURE0);
    // the scene's pictures aren't needed again: a tiler needn't write them back
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    GLenum gone[2] = {GL_COLOR_ATTACHMENT0, GL_DEPTH_ATTACHMENT};
    glInvalidateFramebuffer(GL_FRAMEBUFFER, 2, gone);
    glBindFramebuffer(GL_FRAMEBUFFER, GLuint(prevFbo_));
    stageEnd();
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
    stats_.cpuMs = float(nowMs() - cpuStart_);
}

void PostFx::release() {
    if (fbo_) glDeleteFramebuffers(1, &fbo_);
    if (colour_) glDeleteTextures(1, &colour_);
    if (depth_) glDeleteTextures(1, &depth_);
    if (msFbo_) glDeleteFramebuffers(1, &msFbo_);
    if (msColour_) glDeleteRenderbuffers(1, &msColour_);
    if (msDepth_) glDeleteRenderbuffers(1, &msDepth_);
    if (shadowFbo_) glDeleteFramebuffers(1, &shadowFbo_);
    if (shadowTex_) glDeleteTextures(1, &shadowTex_);
    if (samplerCmp_) glDeleteSamplers(1, &samplerCmp_);
    if (samplerRaw_) glDeleteSamplers(1, &samplerRaw_);
    for (Target* t : {&aoA_, &aoB_, &lit_, &coc_, &dof_, &shafts_}) drop(*t);
    for (Target& t : bloom_) drop(t);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    for (unsigned p : {pAo_, pBlur_, pLight_, pDown_, pUp_, pCoc_, pDof_, pShafts_, pFinal_}) if (p) glDeleteProgram(p);
    if (timersOk_) for (auto& f : queries_) glDeleteQueries(PostStats::kStages, f);
    forget();
}

void PostFx::forget() {
    fbo_ = colour_ = depth_ = msFbo_ = msColour_ = msDepth_ = 0;
    shadowFbo_ = shadowTex_ = samplerCmp_ = samplerRaw_ = 0;
    shadowSize_ = 0;
    shadowValid_ = false;
    for (Target* t : {&aoA_, &aoB_, &lit_, &coc_, &dof_, &shafts_}) *t = Target();
    for (Target& t : bloom_) t = Target();
    vao_ = pAo_ = pBlur_ = pLight_ = pDown_ = pUp_ = pCoc_ = pDof_ = pShafts_ = pFinal_ = 0;
    std::memset(queries_, 0, sizeof queries_);
    std::memset(queryUsed_, 0, sizeof queryUsed_);
    openStage_ = -1;
    timersTried_ = timersOk_ = false;
    sw_ = sh_ = 0;
    msSamples_ = 0;
    failed_ = progsTried_ = false;
    gAnisoMax = -1;
}

} // namespace wc
