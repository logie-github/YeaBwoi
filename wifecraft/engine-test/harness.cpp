// Draws Wifecraft's scenes on a desktop, without a window, to check the renderer against the
// game's files. Reads commands (one per line) from a file:
//
//   files DIR                                  the downloaded files, each named by its id
//   city ROOT GROUP,GROUP,...  X Y Z RADIUS    Dalaran's groups and the props near X Y Z
//   actor N MODEL SCALE X Y Z FACING [tex T=F,T=F] [geo GROUP=VALUE,...]
//   attach N ATTACHMENT MODEL T=F,...
//   anim N ANIM [once]
//   camera EX EY EZ TX TY TZ FOV
//   light day|dusk|night
//   fog START END
//   render OUT.ppm WIDTH HEIGHT TIME_MS
//
// Build: engine-test/build.sh; run: build/harness commands.txt
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <cstdio>
#include <cstdlib>
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
    if (argc < 2) { std::fprintf(stderr, "usage: harness COMMANDS\n"); return 2; }

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
    std::map<int, int> actors;
    Light light;

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
            bool ok = scene.loadCity(root, groups, {V3(x, y, z)}, r);
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
        } else if (cmd == "fog") {
            ss >> light.fogStart >> light.fogEnd; scene.setLight(light);
        } else if (cmd == "render") {
            std::string out; int w, h; uint32_t t;
            ss >> out >> w >> h >> t;
            unsigned fbo, color, depth;
            glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
            glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            // Animations are stepped through to the asked time, as frames would.
            for (uint32_t s = 0; s + 33 < t; s += 33) scene.render(w, h, s);
            scene.render(w, h, t);
            std::vector<uint8_t> px(size_t(w) * h * 4);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
            FILE* f = std::fopen(out.c_str(), "wb");
            std::fprintf(f, "P6\n%d %d\n255\n", w, h);
            for (int y = h - 1; y >= 0; --y)
                for (int x = 0; x < w; ++x) std::fwrite(&px[(size_t(y) * w + x) * 4], 1, 3, f);
            std::fclose(f);
            std::fprintf(stderr, "wrote %s (GL error %#x)\n", out.c_str(), glGetError());
            glDeleteFramebuffers(1, &fbo);
            glDeleteRenderbuffers(1, &color);
            glDeleteRenderbuffers(1, &depth);
        }
    }
    return 0;
}
