// Draws Wifecraft's scenes on a desktop, without a window, to check the renderer against the
// game's files. Reads commands (one per line) from a file:
//
//   files DIR                                  the downloaded files, each named by its id
//   city ROOT GROUP,GROUP,...  X Y Z RADIUS [X Y Z ...]   Dalaran's groups and the props near the points
//   actor N MODEL SCALE X Y Z FACING [tex T=F,T=F] [geo GROUP=VALUE,...]
//   attach N ATTACHMENT MODEL T=F,...
//   anim N ANIM [once]
//   camera EX EY EZ TX TY TZ FOV
//   light day|dusk|night
//   fog START END
//   lightv V1 ... V23                          a light as the app passes it (Lights.of)
//   clear                                      takes everyone off stage
//   look N LOOKFILE X Y Z FACING               a person from a look file (below), dressed
//   dress N LOOKFILE                           dresses person N again (Native.dress): geosets, layers, extras
//   boneanim N ANIMFILE                        an imported animation on person N (Scene::setBoneAnim; file below)
//   bonestop N                                 takes it off (Scene::clearBoneAnim)
//   soft off|subtle|natural|strong             Options' Soft body (Scene::setSoftBody; the scene's default: natural)
//   render OUT.ppm|OUT.png WIDTH HEIGHT TIME_MS
//   location JSONFILE [ID [VIEW]]              a location (Scene::loadLocation; ID chooses the entry, VIEW its light/fog)
//   view NAME                                  the loaded location's view: its light and fog, and its camera
//   prop N MODEL X Y Z YAW SCALE               an editor prop (Scene::addProp: an M2 or a WMO by file id)
//   moveprop N X Y Z YAW SCALE | removeprop N | clearprops
//   select N                                   prop N drawn selected (0: none)
//   pick SX SY WIDTH HEIGHT                    prints what that screen point hits (Scene::pick)
//   ground X Y Z                               prints the floor under the point (Scene::groundAt)
//   stats                                      prints the location's counts and the last frame's draws
//
// An anim file (anim/AnimArrays as text; one key per line, # comments; keys of a bone and channel in time order):
//   duration SECONDS
//   loop
//   rot BONE TIME X Y Z W                      a local rotation key, by WoW-side bone name (Root, Head, L_UpperArm ...)
//   pos BONE TIME X Y Z                        a local translation key
//
// A look file (what the creator resolves a look to; one per line, # comments):
//   model FILE_ID                              the body (look only)
//   scale S
//   character 0|1                              a character (1, the default: a playable race's model, it has a soft body)
//                                              or a creature (0: none; Scene::setCharacter, as Dresser decides it)
//   tex TYPE=FILE,...                          textures as addActor takes them
//   geo GROUP=VALUE,...                        geoset groups
//   layer TYPE LAYER BLEND X Y W H MAT_W MAT_H FILE [TINT1 [TINT2]]   a skin layer (tints 0xAARRGGBB)
//   extra ATTACHMENT MODEL TEXTURE [GEOSET]    an added model (attachment -1: skinned to the body; GEOSET its own)
//   body K FOLD [MORPHFILE]                    Wrath++'s body options (Scene::setBody): the layer's square (the race's
//                                              k and fold, BodyShapes.Frame) and a shape (raw little-endian floats,
//                                              BodyShapes.morph's records); the design is the look's `layer 1 100 ...`
//
// Build: engine-test/build.sh; run: build/harness commands.txt
// Or, for the creator, one person on a plain stage straight to a picture:
//   build/harness --look LOOKFILE --files DIR --out OUT.png [--size WxH] [--shot body|torso|face] [--facing RADIANS] [--time MS] [--anim ANIMFILE]
//                 [--soft off|subtle|natural|strong] [--strip N:STEP_MS] [--camera EX,EY,EZ,TX,TY,TZ,FOV]
// Or a location (assets/locations.json's entry) seen from one of its views:
//   build/harness --location JSONFILE --files DIR --out OUT.png [--id ID] [--view inside|outside|...] [--size WxH]
//                 [--camera EX,EY,EZ,TX,TY,TZ,FOV] [--props "MODEL,X,Y,Z,YAW,SCALE;..."] [--select N] [--pick SX,SY]
//                 [--look LOOKFILE]   (a person standing at the view's spawn)
// --strip draws N pictures side by side, from --time every STEP_MS, the scene stepped at 60 frames a second between them
// (as the phone draws), and prints each soft body region's displacement at each.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>

#include "../android/app/src/main/cpp/engine/scene.h"

using namespace wc;

static std::vector<std::string> split(const std::string& s, char c) {
    std::vector<std::string> out;
    std::stringstream ss(s);
    std::string item;
    while (std::getline(ss, item, c)) if (!item.empty()) out.push_back(item);
    return out;
}

struct LookFile {
    uint32_t model = 0;
    float scale = 1;
    std::map<uint32_t, uint32_t> tex;
    std::map<int, int> geo;
    std::vector<SkinLayer> layers;
    std::vector<DressModel> extras;
    float bodyK = 0, bodyFold = 0;
    std::vector<float> morph;
    bool body = false;
    bool character = true;      // `character 0`: a creature (no soft body); a look file is a character unless it says so
};

static bool readBoneAnim(const std::string& path, BoneAnim& out) {
    std::ifstream in(path);
    if (!in) { std::fprintf(stderr, "no anim file %s\n", path.c_str()); return false; }
    std::map<std::pair<std::string, bool>, size_t> index;
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string k;
        ss >> k;
        if (k.empty() || k[0] == '#') continue;
        if (k == "duration") ss >> out.duration;
        else if (k == "loop") out.loop = true;
        else if (k == "rot" || k == "pos") {
            std::string bone; float t;
            ss >> bone >> t;
            bool rot = k == "rot";
            auto key = std::make_pair(bone, rot);
            if (!index.count(key)) { index[key] = out.tracks.size(); out.tracks.push_back({bone, rot, {}, {}}); }
            BoneAnimTrack& tr = out.tracks[index[key]];
            tr.times.push_back(t);
            for (int i = 0; i < (rot ? 4 : 3); ++i) { float v = 0; ss >> v; tr.values.push_back(v); }
        }
    }
    return true;
}

static uint32_t number(const std::string& s) { return uint32_t(std::strtoul(s.c_str(), nullptr, 0)); }

static bool readLook(const std::string& path, LookFile& out) {
    std::ifstream in(path);
    if (!in) { std::fprintf(stderr, "no look file %s\n", path.c_str()); return false; }
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string k;
        ss >> k;
        if (k.empty() || k[0] == '#') continue;
        if (k == "model") ss >> out.model;
        else if (k == "scale") ss >> out.scale;
        else if (k == "character") { int c = 1; ss >> c; out.character = c != 0; }
        else if (k == "tex" || k == "geo") {
            std::string v; ss >> v;
            for (auto& kv : split(v, ',')) {
                auto p = split(kv, '=');
                if (p.size() != 2) continue;
                if (k == "tex") out.tex[number(p[0])] = number(p[1]);
                else out.geo[std::atoi(p[0].c_str())] = std::atoi(p[1].c_str());
            }
        } else if (k == "layer") {
            SkinLayer l;
            std::string t1, t2;
            ss >> l.textureType >> l.layer >> l.blend >> l.x >> l.y >> l.w >> l.h >> l.materialW >> l.materialH >> l.fileId >> t1 >> t2;
            if (!t1.empty()) l.tint1 = number(t1);
            if (!t2.empty()) l.tint2 = number(t2);
            out.layers.push_back(l);
        } else if (k == "body") {
            std::string morphFile;
            ss >> out.bodyK >> out.bodyFold >> morphFile;
            out.body = true;
            if (!morphFile.empty()) {
                std::ifstream mf(morphFile, std::ios::binary);
                if (!mf) { std::fprintf(stderr, "no morph file %s\n", morphFile.c_str()); return false; }
                std::vector<char> raw((std::istreambuf_iterator<char>(mf)), std::istreambuf_iterator<char>());
                out.morph.resize(raw.size() / 4);
                std::memcpy(out.morph.data(), raw.data(), out.morph.size() * 4);
            }
        } else if (k == "extra") {
            DressModel d;
            ss >> d.attachment >> d.model >> d.texture >> d.geoset;
            out.extras.push_back(d);
        }
    }
    return true;
}

// PNG, uncompressed (stored deflate blocks): no library needed to look at a render.
static uint32_t crc32(const uint8_t* p, size_t n, uint32_t c = 0) {
    c = ~c;
    for (size_t i = 0; i < n; ++i) {
        c ^= p[i];
        for (int k = 0; k < 8; ++k) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
    }
    return ~c;
}

static void writePng(const std::string& path, int w, int h, const std::vector<uint8_t>& rgbTopDown) {
    std::vector<uint8_t> raw;
    for (int y = 0; y < h; ++y) {
        raw.push_back(0);
        raw.insert(raw.end(), rgbTopDown.begin() + size_t(y) * w * 3, rgbTopDown.begin() + size_t(y + 1) * w * 3);
    }
    std::vector<uint8_t> z{0x78, 0x01};
    uint32_t a = 1, b = 0;
    for (uint8_t v : raw) { a = (a + v) % 65521; b = (b + a) % 65521; }
    for (size_t off = 0; off < raw.size() || off == 0; off += 65535) {
        size_t n = std::min<size_t>(65535, raw.size() - off);
        bool last = off + n >= raw.size();
        z.push_back(last ? 1 : 0);
        z.push_back(uint8_t(n)); z.push_back(uint8_t(n >> 8));
        z.push_back(uint8_t(~n)); z.push_back(uint8_t(~n >> 8));
        z.insert(z.end(), raw.begin() + off, raw.begin() + off + n);
        if (last) break;
    }
    uint32_t adler = b << 16 | a;
    for (int i = 3; i >= 0; --i) z.push_back(uint8_t(adler >> (i * 8)));
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    auto be = [&](uint32_t v) { uint8_t q[4] = {uint8_t(v >> 24), uint8_t(v >> 16), uint8_t(v >> 8), uint8_t(v)}; std::fwrite(q, 1, 4, f); };
    auto chunk = [&](const char* type, const std::vector<uint8_t>& data) {
        be(uint32_t(data.size()));
        std::vector<uint8_t> td(type, type + 4);
        td.insert(td.end(), data.begin(), data.end());
        std::fwrite(td.data(), 1, td.size(), f);
        be(crc32(td.data(), td.size()));
    };
    const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    std::fwrite(sig, 1, 8, f);
    std::vector<uint8_t> ihdr = {uint8_t(w >> 24), uint8_t(w >> 16), uint8_t(w >> 8), uint8_t(w),
                                 uint8_t(h >> 24), uint8_t(h >> 16), uint8_t(h >> 8), uint8_t(h), 8, 2, 0, 0, 0};
    chunk("IHDR", ihdr);
    chunk("IDAT", z);
    chunk("IEND", {});
    std::fclose(f);
}

// Draws the scene at `t` into an off-screen picture and writes it (.png, else .ppm).
static void renderTo(Scene& scene, const std::string& out, int w, int h, uint32_t t) {
    unsigned fbo, color, depth;
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
    glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    // Animations are stepped through to the asked time, as frames would, from the time last drawn (the scene's animation
    // clock only moves forward, a frame at a time: a time before the last one drawn moves it on by that time again).
    static uint32_t last = 0;
    static bool drawn = false;
    uint32_t from = drawn && t >= last ? last : 0;
    if (!drawn || t < last) scene.render(w, h, 0);
    for (uint32_t s = from + 33; s < t; s += 33) scene.render(w, h, s);
    scene.render(w, h, t);
    last = t;
    drawn = true;
    std::vector<uint8_t> px(size_t(w) * h * 4);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    std::vector<uint8_t> rgb(size_t(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            std::copy(&px[(size_t(h - 1 - y) * w + x) * 4], &px[(size_t(h - 1 - y) * w + x) * 4] + 3, &rgb[(size_t(y) * w + x) * 3]);
    if (out.size() > 4 && out.substr(out.size() - 4) == ".png") {
        writePng(out, w, h, rgb);
    } else {
        FILE* f = std::fopen(out.c_str(), "wb");
        std::fprintf(f, "P6\n%d %d\n255\n", w, h);
        std::fwrite(rgb.data(), 1, rgb.size(), f);
        std::fclose(f);
    }
    std::fprintf(stderr, "wrote %s (GL error %#x)\n", out.c_str(), glGetError());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteRenderbuffers(1, &color);
    glDeleteRenderbuffers(1, &depth);
}

static int softLevel(const std::string& s) {
    return s == "off" ? kSoftOff : s == "subtle" ? kSoftSubtle : s == "strong" ? kSoftStrong : kSoftNatural;
}

// N pictures side by side: from t0 every `step` ms, the scene drawn every 16 ms from 0 between them.
static void renderStrip(Scene& scene, int actor, const std::string& out, int w, int h, uint32_t t0, uint32_t step, int n) {
    unsigned fbo, color, depth;
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
    glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    int W = w * n;
    std::vector<uint8_t> strip(size_t(W) * h * 3), px(size_t(w) * h * 4);
    uint32_t t = 0;
    for (int i = 0; i < n; ++i) {
        uint32_t at = t0 + uint32_t(i) * step;
        for (; t + 16 < at; t += 16) scene.render(w, h, t);
        t = at;
        scene.render(w, h, t);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
                std::copy(&px[(size_t(h - 1 - y) * w + x) * 4], &px[(size_t(h - 1 - y) * w + x) * 4] + 3,
                          &strip[(size_t(y) * W + size_t(i) * w + x) * 3]);
        if (const SoftSim* sim = scene.softSim(actor)) {
            std::fprintf(stderr, "t %5u ms soft regions=%zu (mm, model space):", at, sim->regions().size());
            for (size_t r = 0; r < sim->regions().size(); ++r) {
                V3 d = sim->offset(r);
                std::fprintf(stderr, " %d:[%+.1f %+.1f %+.1f]", int(sim->regions()[r].kind), d.x * 1000, d.y * 1000, d.z * 1000);
            }
            std::fprintf(stderr, "\n");
        }
    }
    writePng(out, W, h, strip);
    std::fprintf(stderr, "wrote %s (GL error %#x)\n", out.c_str(), glGetError());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteRenderbuffers(1, &color);
    glDeleteRenderbuffers(1, &depth);
}


static const char* pickName(int k) {
    return k == Scene::kPickProp ? "prop" : k == Scene::kPickActor ? "actor" : k == Scene::kPickGround ? "ground" :
           k == Scene::kPickLocation ? "location" : "nothing";
}

static void printStats(const Scene& scene) {
    Scene::LocationStats st = scene.locationStats();
    std::fprintf(stderr, "location: %zu groups (%zu missing), %zu triangles, %zu doodads (%zu missing) of %zu models; "
                 "bounds %.1f %.1f %.1f .. %.1f %.1f %.1f; last frame: %zu draw calls, %zu groups, %zu instances\n",
                 st.groups, st.groupsMissing, st.triangles, st.doodads, st.doodadsMissing, st.models,
                 st.boundsMin.x, st.boundsMin.y, st.boundsMin.z, st.boundsMax.x, st.boundsMax.y, st.boundsMax.z,
                 st.drawCalls, st.groupsDrawn, st.instancesDrawn);
}

static bool readText(const std::string& path, std::string& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "no file %s\n", path.c_str()); return false; }
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

// A view's camera from the loaded location (false: it has none).
static bool viewCamera(Scene& scene, const std::string& view) {
    const LocationDesc* d = scene.location();
    if (!d) return false;
    auto it = d->views.find(view);
    if (it == d->views.end() || !it->second.hasCamera) return false;
    scene.setCamera(it->second.eye, it->second.target, it->second.fov);
    return true;
}

static Light preset(const std::string& name) {
    Light l;
    if (name == "dusk") {
        float top[3] = {0.20f, 0.18f, 0.42f}, hor[3] = {0.93f, 0.58f, 0.45f}, below[3] = {0.30f, 0.22f, 0.35f};
        float sun[3] = {0.75f, 0.35f, -0.25f}, sc[3] = {1.0f, 0.70f, 0.50f}, amb[3] = {0.42f, 0.36f, 0.48f};
        std::copy(top, top + 3, l.skyTop); std::copy(hor, hor + 3, l.skyHorizon); std::copy(below, below + 3, l.skyBelow);
        std::copy(sun, sun + 3, l.sunDir); std::copy(sc, sc + 3, l.sunColor); std::copy(amb, amb + 3, l.ambient);
        std::copy(hor, hor + 3, l.fogColor);
    } else if (name == "night") {
        float top[3] = {0.03f, 0.04f, 0.12f}, hor[3] = {0.16f, 0.17f, 0.32f}, below[3] = {0.06f, 0.06f, 0.12f};
        float sun[3] = {0.3f, -0.5f, -0.8f}, sc[3] = {0.45f, 0.50f, 0.75f}, amb[3] = {0.28f, 0.28f, 0.42f};
        std::copy(top, top + 3, l.skyTop); std::copy(hor, hor + 3, l.skyHorizon); std::copy(below, below + 3, l.skyBelow);
        std::copy(sun, sun + 3, l.sunDir); std::copy(sc, sc + 3, l.sunColor); std::copy(amb, amb + 3, l.ambient);
        std::copy(hor, hor + 3, l.fogColor);
    }
    return l;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: harness COMMANDS | harness --look LOOKFILE --files DIR --out OUT.png [--size WxH] [--shot body|torso|face] [--facing RADIANS] [--time MS]\n");
        return 2;
    }
    std::map<std::string, std::string> opt;
    if (std::string(argv[1]).rfind("--", 0) == 0)
        for (int i = 1; i + 1 < argc; i += 2) opt[argv[i]] = argv[i + 1];

    EGLDisplay dpy = EGL_NO_DISPLAY;
    auto getPlatformDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (getPlatformDisplay) dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (dpy == EGL_NO_DISPLAY) dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(dpy, nullptr, nullptr)) { std::fprintf(stderr, "eglInitialize failed\n"); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n);
    EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, n ? cfg : nullptr, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
        std::fprintf(stderr, "no GLES 3 context\n"); return 1;
    }
    std::fprintf(stderr, "GL: %s / %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

    std::string dir;
    Scene scene([&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 ? p : std::string();
    });
    scene.initGl();
    std::map<int, int> actors, props;
    Light light;

    if (opt.count("--location")) {
        dir = opt["--files"];
        std::string json;
        if (!readText(opt["--location"], json)) return 1;
        std::string view = opt.count("--view") ? opt["--view"] : "";
        if (!scene.loadLocation(json, opt["--id"], view)) { std::fprintf(stderr, "location: FAILED: %s\n", scene.locationError().c_str()); return 1; }
        if (!viewCamera(scene, view)) std::fprintf(stderr, "view \"%s\": no camera in the entry\n", view.c_str());
        float cam[7];
        if (opt.count("--camera") && std::sscanf(opt["--camera"].c_str(), "%f,%f,%f,%f,%f,%f,%f", &cam[0], &cam[1], &cam[2],
                                                 &cam[3], &cam[4], &cam[5], &cam[6]) == 7)
            scene.setCamera(V3(cam[0], cam[1], cam[2]), V3(cam[3], cam[4], cam[5]), cam[6]);
        std::vector<int> props;
        for (const std::string& one : split(opt["--props"], ';')) {
            float v[6] = {0, 0, 0, 0, 0, 1};
            if (std::sscanf(one.c_str(), "%f,%f,%f,%f,%f,%f", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) < 4) continue;
            int id = scene.addProp(uint32_t(v[0]), V3(v[1], v[2], v[3]), v[4], v[5]);
            std::fprintf(stderr, "prop %u: %s\n", uint32_t(v[0]), id ? "ok" : scene.modelError(uint32_t(v[0])).c_str());
            props.push_back(id);
        }
        if (opt.count("--select")) {
            size_t n = size_t(std::atoi(opt["--select"].c_str()));
            if (n >= 1 && n <= props.size()) scene.select(1, props[n - 1]);
        }
        if (opt.count("--look")) {
            LookFile lf;
            const LocationDesc* d = scene.location();
            auto it = d->views.find(view);
            if (readLook(opt["--look"], lf) && it != d->views.end() && it->second.hasSpawn) {
                int id = scene.addActor(lf.model, lf.tex, lf.geo, lf.scale);
                if (id > 0) {
                    scene.setCharacter(id, lf.character);
                    scene.dress(id, lf.geo, lf.layers, lf.extras);
                    V3 sp = it->second.spawn;
                    sp.z = scene.floorAt(sp.x, sp.y, sp.z + 1.0f);
                    V3 e = it->second.eye;
                    scene.placeActor(id, sp, std::atan2(e.y - sp.y, e.x - sp.x));
                    std::fprintf(stderr, "person at %.2f %.2f %.2f\n", sp.x, sp.y, sp.z);
                }
            }
        }
        int w = 1024, ht = 640;
        if (opt.count("--size")) std::sscanf(opt["--size"].c_str(), "%dx%d", &w, &ht);
        renderTo(scene, opt.count("--out") ? opt["--out"] : "location.png", w, ht, 0);
        printStats(scene);
        float px, py;
        if (opt.count("--pick") && std::sscanf(opt["--pick"].c_str(), "%f,%f", &px, &py) == 2) {
            Scene::PickHit h = scene.pick(px, py, w, ht);
            std::fprintf(stderr, "pick %.0f,%.0f: %s %d at %.2f %.2f %.2f (%.2f yards)\n", px, py, pickName(h.kind), h.id,
                         h.point.x, h.point.y, h.point.z, h.distance);
        }
        return 0;
    }

    if (!opt.empty()) {
        // One dressed person on a plain stage, framed as the creator frames them.
        dir = opt["--files"];
        LookFile lf;
        if (!readLook(opt["--look"], lf)) return 1;
        int id = scene.addActor(lf.model, lf.tex, lf.geo, lf.scale);
        if (id <= 0) { std::fprintf(stderr, "model %u: FAILED\n", lf.model); return 1; }
        scene.setCharacter(id, lf.character);
        scene.dress(id, lf.geo, lf.layers, lf.extras);
        if (lf.body) scene.setBody(id, lf.morph, lf.bodyK, lf.bodyFold);
        if (opt.count("--soft")) scene.setSoftBody(softLevel(opt["--soft"]));
        if (opt.count("--anim")) {
            BoneAnim ba;
            if (!readBoneAnim(opt["--anim"], ba)) return 1;
            int found = scene.setBoneAnim(id, ba, 0);
            std::fprintf(stderr, "anim: %d of %zu tracks found their bone\n", found, ba.tracks.size());
        }
        float facing = opt.count("--facing") ? std::strtof(opt["--facing"].c_str(), nullptr) : 0.0f;
        scene.placeActor(id, V3(0, 0, 0), facing);
        V3 mn, mx;
        scene.actorBounds(id, mn, mx);
        float h = mx.z;
        bool face = opt["--shot"] == "face", torso = opt["--shot"] == "torso";
        float tz = face ? h * 0.88f : torso ? h * 0.58f : h * 0.5f, dist = face ? h * 0.35f : torso ? h * 0.75f : h * 1.6f;
        scene.setCamera(V3(dist, 0, tz), V3(0, 0, tz), face || torso ? 30.0f : 40.0f);
        float cam[7];
        if (opt.count("--camera") && std::sscanf(opt["--camera"].c_str(), "%f,%f,%f,%f,%f,%f,%f", &cam[0], &cam[1], &cam[2],
                                                 &cam[3], &cam[4], &cam[5], &cam[6]) == 7)
            scene.setCamera(V3(cam[0], cam[1], cam[2]), V3(cam[3], cam[4], cam[5]), cam[6]);
        Light studio;
        float sun[3] = {-0.6f, 0.3f, -0.5f}, hor[3] = {0.16f, 0.15f, 0.18f}, amb[3] = {0.55f, 0.55f, 0.58f};
        std::copy(sun, sun + 3, studio.sunDir);
        std::copy(hor, hor + 3, studio.skyHorizon); std::copy(hor, hor + 3, studio.skyTop); std::copy(hor, hor + 3, studio.skyBelow);
        std::copy(hor, hor + 3, studio.fogColor);
        std::copy(amb, amb + 3, studio.ambient);
        studio.fogStart = 1000; studio.fogEnd = 2000;
        scene.setLight(studio);
        int w = 512, ht = 768;
        if (opt.count("--size")) std::sscanf(opt["--size"].c_str(), "%dx%d", &w, &ht);
        uint32_t t0 = opt.count("--time") ? uint32_t(std::atoi(opt["--time"].c_str())) : 0;
        int frames = 0, step = 0;
        if (opt.count("--strip") && std::sscanf(opt["--strip"].c_str(), "%d:%d", &frames, &step) == 2 && frames > 0)
            renderStrip(scene, id, opt.count("--out") ? opt["--out"] : "strip.png", w, ht, t0, uint32_t(std::max(1, step)), frames);
        else
            renderTo(scene, opt.count("--out") ? opt["--out"] : "look.png", w, ht, t0);
        if (const SoftSim* sim = scene.softSim(id))
            for (size_t r = 0; r < sim->regions().size(); ++r) {
                const SoftRegion& g = sim->regions()[r];
                std::fprintf(stderr, "soft region %zu: kind %d bone %d base %.3f %.3f %.3f radii %.3f %.3f %.3f weight %.2f\n", r,
                             int(g.kind), g.bone, g.centre.x, g.centre.y, g.centre.z, g.radii.x, g.radii.y, g.radii.z, g.weight);
            }
        size_t moved = 0, layer = 0;
        if (lf.body && scene.bodyStats(id, moved, layer)) std::fprintf(stderr, "body: %zu vertices shaped, %zu layer triangles\n", moved, layer);
        return 0;
    }

    std::ifstream in(argv[1]);
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string cmd;
        ss >> cmd;
        if (cmd.empty() || cmd[0] == '#') continue;
        if (cmd == "files") ss >> dir;
        else if (cmd == "city") {
            uint32_t root; std::string gs; float x, y, z, r;
            ss >> root >> gs >> x >> y >> z >> r;
            std::vector<int> groups;
            for (auto& g : split(gs, ',')) groups.push_back(std::atoi(g.c_str()));
            std::vector<V3> centres{V3(x, y, z)};
            float cx, cy, cz;
            while (ss >> cx >> cy >> cz) centres.emplace_back(cx, cy, cz);
            bool ok = scene.loadCity(root, groups, centres, r);
            std::fprintf(stderr, "city %u: %s\n", root, ok ? "ok" : "FAILED");
        } else if (cmd == "actor") {
            int name; uint32_t model; float scale, x, y, z, facing;
            ss >> name >> model >> scale >> x >> y >> z >> facing;
            std::map<uint32_t, uint32_t> tex;
            std::map<int, int> geo;
            std::string key, val;
            while (ss >> key >> val) {
                if (key == "tex") for (auto& kv : split(val, ',')) {
                    auto p = split(kv, '=');
                    if (p.size() == 2) tex[uint32_t(std::atoi(p[0].c_str()))] = uint32_t(std::strtoul(p[1].c_str(), nullptr, 10));
                } else if (key == "geo") for (auto& kv : split(val, ',')) {
                    auto p = split(kv, '=');
                    if (p.size() == 2) geo[std::atoi(p[0].c_str())] = std::atoi(p[1].c_str());
                }
            }
            int id = scene.addActor(model, tex, geo, scale);
            std::fprintf(stderr, "actor %d (model %u): %s\n", name, model, id > 0 ? "ok" : "FAILED");
            if (id > 0) { scene.placeActor(id, V3(x, y, z), facing); actors[name] = id; }
        } else if (cmd == "attach") {
            int name; uint32_t att, model; std::string texs;
            ss >> name >> att >> model >> texs;
            std::map<uint32_t, uint32_t> tex;
            for (auto& kv : split(texs, ',')) {
                auto p = split(kv, '=');
                if (p.size() == 2) tex[uint32_t(std::atoi(p[0].c_str()))] = uint32_t(std::strtoul(p[1].c_str(), nullptr, 10));
            }
            if (actors.count(name)) scene.attach(actors[name], att, model, tex);
        } else if (cmd == "anim") {
            int name, anim; std::string once;
            ss >> name >> anim >> once;
            if (actors.count(name)) scene.playAnim(actors[name], uint16_t(anim), once != "once", 0);
        } else if (cmd == "camera") {
            float ex, ey, ez, tx, ty, tz, fov;
            ss >> ex >> ey >> ez >> tx >> ty >> tz >> fov;
            scene.setCamera(V3(ex, ey, ez), V3(tx, ty, tz), fov);
        } else if (cmd == "light") {
            std::string p; ss >> p; light = preset(p); scene.setLight(light);
        } else if (cmd == "lightv") {
            float v[23];
            for (float& f : v) ss >> f;
            float* dst[] = {light.skyTop, light.skyHorizon, light.skyBelow, light.sunDir, light.sunColor, light.ambient, light.fogColor};
            for (int i = 0; i < 7; ++i) for (int k = 0; k < 3; ++k) dst[i][k] = v[i * 3 + k];
            light.fogStart = v[21]; light.fogEnd = v[22];
            scene.setLight(light);
        } else if (cmd == "clear") {
            for (auto& [name, id] : actors) scene.removeActor(id);
            actors.clear();
        } else if (cmd == "fog") {
            ss >> light.fogStart >> light.fogEnd; scene.setLight(light);
        } else if (cmd == "look") {
            int name; std::string path; float x, y, z, facing;
            ss >> name >> path >> x >> y >> z >> facing;
            LookFile lf;
            if (!readLook(path, lf)) continue;
            int id = scene.addActor(lf.model, lf.tex, lf.geo, lf.scale);
            std::fprintf(stderr, "look %d (model %u): %s\n", name, lf.model, id > 0 ? "ok" : "FAILED");
            if (id <= 0) continue;
            scene.setCharacter(id, lf.character);
            scene.dress(id, lf.geo, lf.layers, lf.extras);
            if (lf.body) scene.setBody(id, lf.morph, lf.bodyK, lf.bodyFold);
            scene.placeActor(id, V3(x, y, z), facing);
            actors[name] = id;
        } else if (cmd == "dress") {
            int name; std::string path;
            ss >> name >> path;
            LookFile lf;
            if (actors.count(name) && readLook(path, lf)) scene.dress(actors[name], lf.geo, lf.layers, lf.extras);
        } else if (cmd == "boneanim") {
            int name; std::string path;
            ss >> name >> path;
            BoneAnim ba;
            if (actors.count(name) && readBoneAnim(path, ba))
                std::fprintf(stderr, "boneanim %d: %d of %zu tracks found their bone\n", name, scene.setBoneAnim(actors[name], ba, 0), ba.tracks.size());
        } else if (cmd == "soft") {
            std::string l; ss >> l;
            scene.setSoftBody(softLevel(l));
        } else if (cmd == "bonestop") {
            int name; ss >> name;
            if (actors.count(name)) scene.clearBoneAnim(actors[name]);
        } else if (cmd == "location") {
            std::string path, id, view, json;
            ss >> path >> id >> view;
            if (id == "-") id.clear();
            if (readText(path, json)) {
                bool ok = scene.loadLocation(json, id, view);
                std::fprintf(stderr, "location %s: %s\n", path.c_str(), ok ? "ok" : scene.locationError().c_str());
                if (ok) viewCamera(scene, view);
            }
        } else if (cmd == "view") {
            std::string view; ss >> view;
            scene.locationView(view);
            viewCamera(scene, view);
        } else if (cmd == "prop") {
            int name; uint32_t model; float x, y, z, yaw, sc;
            ss >> name >> model >> x >> y >> z >> yaw >> sc;
            int id = scene.addProp(model, V3(x, y, z), yaw, sc);
            std::fprintf(stderr, "prop %d (%u): %s\n", name, model, id ? "ok" : scene.modelError(model).c_str());
            if (id) props[name] = id;
        } else if (cmd == "moveprop") {
            int name; float x, y, z, yaw, sc;
            ss >> name >> x >> y >> z >> yaw >> sc;
            if (props.count(name)) scene.moveProp(props[name], V3(x, y, z), yaw, sc);
        } else if (cmd == "removeprop") {
            int name; ss >> name;
            if (props.count(name)) { scene.removeProp(props[name]); props.erase(name); }
        } else if (cmd == "clearprops") {
            scene.clearProps(); props.clear();
        } else if (cmd == "select") {
            int name = 0; ss >> name;
            scene.select(props.count(name) ? 1 : 0, props.count(name) ? props[name] : 0);
        } else if (cmd == "pick") {
            float sx, sy; int w, h;
            ss >> sx >> sy >> w >> h;
            Scene::PickHit hit = scene.pick(sx, sy, w, h);
            int shown = hit.id;
            for (auto& [n, id] : props) if (hit.kind == Scene::kPickProp && id == hit.id) shown = n;
            for (auto& [n, id] : actors) if (hit.kind == Scene::kPickActor && id == hit.id) shown = n;
            std::fprintf(stderr, "pick %.0f,%.0f: %s %d at %.2f %.2f %.2f (%.2f yards)\n", sx, sy, pickName(hit.kind), shown,
                         hit.point.x, hit.point.y, hit.point.z, hit.distance);
        } else if (cmd == "ground") {
            float x, y, z, out = 0;
            ss >> x >> y >> z;
            bool ok = scene.groundAt(x, y, z, out);
            std::fprintf(stderr, "ground at %.2f %.2f below %.2f: %s %.3f\n", x, y, z, ok ? "floor" : "none", out);
        } else if (cmd == "stats") {
            printStats(scene);
        } else if (cmd == "render") {
            std::string out; int w, h; uint32_t t;
            ss >> out >> w >> h >> t;
            renderTo(scene, out, w, h, t);
        }
    }
    return 0;
}
