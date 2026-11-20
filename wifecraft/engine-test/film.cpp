// Draws a recorded live scene with the engine, frame by frame (tools/film: the JVM recorder runs the app's own simulation,
// LiveScene with its life simulation, chatter, furniture and world clock, and writes a track; this places and animates
// everyone as the app's SceneSessionScreen sink would, lights the room by the clock's hour and renders each frame to PNG).
// Desktop only (Mesa EGL, surfaceless); the phone's GPU and timing may differ.
//
//   film --serve LOCATIONS_JSON ID FILES_DIR [VIEW]
//       loads the location and answers the recorder's walk tests on stdin/stdout, one per line, as the app's
//       SceneView.glWalkRoute asks them of the engine: "seg AX AY AZ BX BY BZ" -> 1|0 (Scene::walkSegment),
//       "ground X Y Z" -> the floor height or nan (Scene::groundAt, Native.groundAt's answer). Prints "ready" first.
//   film --track TRACK.jsonl --files DIR --locations JSON --looks DIR --out OUT_DIR
//        [--size WxH] [--camera app|orbit|fixed:EX,EY,EZ,TX,TY,TZ,FOV] [--every N] [--from MS] [--to MS]
//        [--soft off|subtle|natural|strong]
//       draws every frame record (from 3 s before --from) and writes OUT_DIR/f_NNNNN.png for every Nth one and OUT_DIR/frames.jsonl (each frame's record index, time,
//       each person's head on screen, how far their feet are over the floor under them).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <zlib.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>

#include "../android/app/src/main/cpp/engine/json.h"
#include "../android/app/src/main/cpp/engine/scene.h"

using namespace wc;

static std::vector<std::string> split(const std::string& s, char c) {
    std::vector<std::string> out;
    std::stringstream ss(s);
    std::string item;
    while (std::getline(ss, item, c)) if (!item.empty()) out.push_back(item);
    return out;
}

static uint32_t number(const std::string& s) { return uint32_t(std::strtoul(s.c_str(), nullptr, 0)); }

// A look file (the harness's format: engine-test/harness.cpp documents it).
struct Look {
    uint32_t model = 0;
    float scale = 1;
    bool character = true;
    std::map<uint32_t, uint32_t> tex;
    std::map<int, int> geo;
    std::vector<SkinLayer> layers;
    std::vector<DressModel> extras;
};

static bool readLook(const std::string& path, Look& out) {
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
                if (k == "tex") out.tex[number(p[0])] = number(p[1]); else out.geo[std::atoi(p[0].c_str())] = std::atoi(p[1].c_str());
            }
        } else if (k == "layer") {
            SkinLayer l; std::string t1, t2;
            ss >> l.textureType >> l.layer >> l.blend >> l.x >> l.y >> l.w >> l.h >> l.materialW >> l.materialH >> l.fileId >> t1 >> t2;
            if (!t1.empty()) l.tint1 = number(t1);
            if (!t2.empty()) l.tint2 = number(t2);
            out.layers.push_back(l);
        } else if (k == "extra") {
            DressModel d; ss >> d.attachment >> d.model >> d.texture >> d.geoset; out.extras.push_back(d);
        }
    }
    return out.model != 0;
}

// An anim file (the harness's format).
static bool readBoneAnim(const std::string& path, BoneAnim& out) {
    std::ifstream in(path);
    if (!in) { std::fprintf(stderr, "no anim file %s\n", path.c_str()); return false; }
    std::map<std::pair<std::string, bool>, size_t> index;
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string k; ss >> k;
        if (k.empty() || k[0] == '#') continue;
        if (k == "duration") ss >> out.duration;
        else if (k == "loop") out.loop = true;
        else if (k == "rot" || k == "pos") {
            std::string bone; float t; ss >> bone >> t;
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

// PNG with zlib (the frames are many: stored blocks would be ~0.7 MB each).
static void writePng(const std::string& path, int w, int h, const std::vector<uint8_t>& rgb) {
    std::vector<uint8_t> raw;
    raw.reserve(size_t(h) * (w * 3 + 1));
    for (int y = 0; y < h; ++y) { raw.push_back(0); raw.insert(raw.end(), rgb.begin() + size_t(y) * w * 3, rgb.begin() + size_t(y + 1) * w * 3); }
    uLongf zn = compressBound(uLong(raw.size()));
    std::vector<uint8_t> z(zn);
    compress2(z.data(), &zn, raw.data(), uLong(raw.size()), 6);
    z.resize(zn);
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    auto be = [&](uint32_t v) { uint8_t q[4] = {uint8_t(v >> 24), uint8_t(v >> 16), uint8_t(v >> 8), uint8_t(v)}; std::fwrite(q, 1, 4, f); };
    auto chunk = [&](const char* type, const std::vector<uint8_t>& data) {
        be(uint32_t(data.size()));
        std::vector<uint8_t> td(type, type + 4);
        td.insert(td.end(), data.begin(), data.end());
        std::fwrite(td.data(), 1, td.size(), f);
        be(uint32_t(crc32(0, td.data(), uInt(td.size()))));
    };
    const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    std::fwrite(sig, 1, 8, f);
    chunk("IHDR", {uint8_t(w >> 24), uint8_t(w >> 16), uint8_t(w >> 8), uint8_t(w), uint8_t(h >> 24), uint8_t(h >> 16), uint8_t(h >> 8), uint8_t(h), 8, 2, 0, 0, 0});
    chunk("IDAT", z);
    chunk("IEND", {});
    std::fclose(f);
}

static bool readText(const std::string& path, std::string& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "no file %s\n", path.c_str()); return false; }
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

static int softLevel(const std::string& s) {
    return s == "off" ? kSoftOff : s == "subtle" ? kSoftSubtle : s == "strong" ? kSoftStrong : kSoftNatural;
}

// What the engine was last told to play for a person (as the app's sink tells it): a game animation, or an imported clip.
struct Applied {
    int anim = -1; bool loop = true; double start = -1;
    std::string clip; double clipStart = -1;
};

int main(int argc, char** argv) {
    std::map<std::string, std::string> opt;
    for (int i = 1; i + 1 < argc; i += 2) opt[argv[i]] = argv[i + 1];
    bool serve = argc >= 2 && std::string(argv[1]) == "--serve";
    if (!serve && !opt.count("--track")) {
        std::fprintf(stderr, "usage: film --serve LOCATIONS_JSON ID FILES_DIR [VIEW] | film --track TRACK --files DIR --locations JSON --looks DIR --out DIR\n");
        return 2;
    }

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
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) { std::fprintf(stderr, "no GLES 3 context\n"); return 1; }

    std::string dir = serve ? (argc > 4 ? argv[4] : "") : opt["--files"];
    FileLookup lookup = [&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 ? p : std::string();
    };
    Scene scene(lookup);
    scene.initGl();

    if (serve) {
        if (argc < 5) return 2;
        std::string json;
        if (!readText(argv[2], json)) return 1;
        std::string view = argc > 5 ? argv[5] : "inside";
        if (!scene.loadLocation(json, argv[3], view)) { std::fprintf(stderr, "location: %s\n", scene.locationError().c_str()); return 1; }
        std::printf("ready\n");
        std::fflush(stdout);
        std::string line;
        while (std::getline(std::cin, line)) {
            std::istringstream ss(line);
            std::string cmd; ss >> cmd;
            if (cmd == "seg") {
                float a[6] = {0}; for (float& v : a) ss >> v;
                std::printf("%d\n", scene.walkSegment(V3(a[0], a[1], a[2]), V3(a[3], a[4], a[5])) ? 1 : 0);
            } else if (cmd == "ground") {
                float x = 0, y = 0, z = 0, out = 0; ss >> x >> y >> z;
                if (scene.groundAt(x, y, z, out)) std::printf("%.5f\n", out); else std::printf("nan\n");
            } else if (cmd == "quit") break;
            else std::printf("?\n");
            std::fflush(stdout);
        }
        return 0;
    }

    // ---- the track
    std::ifstream track(opt["--track"]);
    if (!track) { std::fprintf(stderr, "no track %s\n", opt["--track"].c_str()); return 1; }
    std::string line;
    Json header;
    if (!std::getline(track, line) || !Json::parse(line, header) || header["type"].str() != "header") { std::fprintf(stderr, "no header\n"); return 1; }
    std::string json;
    if (!readText(opt["--locations"], json)) return 1;
    std::string view = header["view"].str("inside");
    if (!scene.loadLocation(json, header["location"].str(), view)) { std::fprintf(stderr, "location: %s\n", scene.locationError().c_str()); return 1; }
    scene.setSoftBody(softLevel(opt.count("--soft") ? opt["--soft"] : "natural"));

    const Json& cast = header["cast"];
    std::vector<int> ids;
    for (size_t i = 0; i < cast.size(); ++i) {
        Look lf;
        std::string path = opt["--looks"] + "/" + cast[i]["look"].str() + ".look";
        int id = readLook(path, lf) ? scene.addActor(lf.model, lf.tex, lf.geo, lf.scale) : 0;
        if (id <= 0) { std::fprintf(stderr, "cast %zu (%s): FAILED\n", i, path.c_str()); return 1; }
        scene.setCharacter(id, lf.character);
        scene.dress(id, lf.geo, lf.layers, lf.extras);
        // human scale as SceneView.dress sizes a live NPC: a dwarf's or gnome's own size, everyone else to their sex's height
        float stand = scene.actorStandHeight(id);
        if (!cast[i]["ownSize"].b && stand > 0.15f && stand < 12.f) scene.setActorScale(id, std::clamp(float(cast[i]["height"].num()) / stand, 0.2f, 4.0f));
        scene.setHumanRootMotion(id, float(cast[i]["rootXY"].num()));
        std::fprintf(stderr, "cast %zu %s: stands %.2f\n", i, cast[i]["look"].str().c_str(), stand);
        ids.push_back(id);
    }
    std::map<std::string, BoneAnim> clips;
    const Json& cl = header["clips"];
    for (auto& [key, file] : cl.o) { BoneAnim ba; if (readBoneAnim(file.str(), ba)) clips[key] = ba; }

    int w = 640, h = 360;
    if (opt.count("--size")) std::sscanf(opt["--size"].c_str(), "%dx%d", &w, &h);
    int every = opt.count("--every") ? std::max(1, std::atoi(opt["--every"].c_str())) : 1;
    double from = opt.count("--from") ? std::atof(opt["--from"].c_str()) : -1, to = opt.count("--to") ? std::atof(opt["--to"].c_str()) : 1e18;
    std::string camMode = opt.count("--camera") ? opt["--camera"] : "app";
    float fixedCam[7] = {0};
    if (camMode.rfind("fixed:", 0) == 0)
        std::sscanf(camMode.c_str() + 6, "%f,%f,%f,%f,%f,%f,%f", &fixedCam[0], &fixedCam[1], &fixedCam[2], &fixedCam[3], &fixedCam[4], &fixedCam[5], &fixedCam[6]);
    const Json& orbit = header["orbit"];   // centre x y z, radius, height, seconds a turn
    std::string out = opt["--out"];
    mkdir(out.c_str(), 0755);
    FILE* side = std::fopen((out + "/frames.jsonl").c_str(), "w");
    if (!side) { std::fprintf(stderr, "can't write %s/frames.jsonl\n", out.c_str()); return 1; }

    unsigned fbo, color, depth;
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
    glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    std::vector<uint8_t> px(size_t(w) * h * 4), rgb(size_t(w) * h * 3);

    std::vector<Applied> applied(ids.size());
    long record = -1;
    int written = 0;
    while (std::getline(track, line)) {
        if (line.find("\"type\":\"frame\"") == std::string::npos) continue;
        ++record;
        Json fr;
        if (!Json::parse(line, fr)) continue;
        double t = fr["t"].num();
        // every record's animation changes are applied (they carry their own start times), only every Nth is drawn
        const Json& people = fr["people"];
        for (size_t i = 0; i < ids.size() && i < people.size(); ++i) {
            const Json& p = people[i];
            int id = ids[i];
            Applied& a = applied[i];
            std::string clip = p["clip"].str();
            double clipStart = p["clipStart"].num(-1);
            if (clip != a.clip || (!clip.empty() && clipStart != a.clipStart)) {
                if (clip.empty() || !clips.count(clip)) scene.clearBoneAnim(id);
                else {
                    int found = scene.setBoneAnim(id, clips[clip], uint32_t(clipStart));
                    static std::map<std::string, bool> told;
                    if (!told[std::to_string(i) + clip]) {
                        told[std::to_string(i) + clip] = true;
                        std::fprintf(stderr, "cast %zu: %s: %d of %zu tracks found their bone\n", i, clip.c_str(), found, clips[clip].tracks.size());
                    }
                }
                a.clip = clip; a.clipStart = clipStart;
            }
            int anim = int(p["anim"].num(-1));
            bool loop = p["loop"].b;
            double start = p["animStart"].num(0);
            if (anim >= 0 && (anim != a.anim || loop != a.loop || start != a.start)) {
                scene.playAnim(id, uint16_t(anim), loop, uint32_t(start));
                a.anim = anim; a.loop = loop; a.start = start;
            }
            scene.placeActor(id, V3(float(p["x"].num()), float(p["y"].num()), float(p["z"].num())), float(p["f"].num()), int(p["pose"].num()),
                             V3(float(p["sx"].num()), float(p["sy"].num()), float(p["sz"].num())));
        }
        // every record from 3 s before --from is drawn (the engine's poses, blends and soft body move on frame by frame, as
        // on the phone; drawn only now and then a person can come out wrong), only every Nth one is written
        bool wanted = record % every == 0 && t >= from && t <= to;
        if (t < from - 3000 || t > to) continue;
        scene.setTimeOfDay(float(fr["light"].num(fr["hour"].num())));
        if (camMode == "app") {
            const Json& c = fr["camera"];
            scene.setCamera(V3(float(c[0].num()), float(c[1].num()), float(c[2].num())), V3(float(c[3].num()), float(c[4].num()), float(c[5].num())), float(c[6].num(50)));
        } else if (camMode == "orbit") {
            float cx = float(orbit[0].num()), cy = float(orbit[1].num()), cz = float(orbit[2].num()), r = float(orbit[3].num(6)), hz = float(orbit[4].num(2.5)), per = float(orbit[5].num(60));
            float ang = float(t / 1000.0 / per * 2 * M_PI);
            scene.setCamera(V3(cx + r * std::cos(ang), cy + r * std::sin(ang), cz + hz), V3(cx, cy, cz + 0.9f), 55);
        } else {
            scene.setCamera(V3(fixedCam[0], fixedCam[1], fixedCam[2]), V3(fixedCam[3], fixedCam[4], fixedCam[5]), fixedCam[6]);
        }
        scene.render(w, h, uint32_t(t));
        if (!wanted) continue;
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
                std::copy(&px[(size_t(h - 1 - y) * w + x) * 4], &px[(size_t(h - 1 - y) * w + x) * 4] + 3, &rgb[(size_t(y) * w + x) * 3]);
        char name[64];
        std::snprintf(name, sizeof name, "f_%05d.png", written);
        writePng(out + "/" + name, w, h, rgb);
        // each head on screen (the bubbles' anchors) and each person's feet over the floor under them
        Scene::CameraInfo ci = scene.cameraInfo();
        M4 pv = ci.proj * ci.view;
        std::fprintf(side, "{\"png\":\"%s\",\"record\":%ld,\"t\":%.0f,\"heads\":[", name, record, t);
        for (size_t i = 0; i < ids.size() && i < people.size(); ++i) {
            const Json& p = people[i];
            V3 q(float(p["hx"].num()), float(p["hy"].num()), float(p["hz"].num()));
            float cx = pv.at(0, 0) * q.x + pv.at(0, 1) * q.y + pv.at(0, 2) * q.z + pv.at(0, 3);
            float cy = pv.at(1, 0) * q.x + pv.at(1, 1) * q.y + pv.at(1, 2) * q.z + pv.at(1, 3);
            float cw = pv.at(3, 0) * q.x + pv.at(3, 1) * q.y + pv.at(3, 2) * q.z + pv.at(3, 3);
            bool vis = cw > 0.05f;
            float sx = vis ? (cx / cw * 0.5f + 0.5f) * w : -1, sy = vis ? (1 - (cy / cw * 0.5f + 0.5f)) * h : -1;
            std::fprintf(side, "%s[%.1f,%.1f,%d]", i ? "," : "", sx, sy, vis ? 1 : 0);
        }
        std::fprintf(side, "],\"overFloor\":[");
        for (size_t i = 0; i < ids.size() && i < people.size(); ++i) {
            const Json& p = people[i];
            float x = float(p["x"].num()), y = float(p["y"].num()), z = float(p["z"].num()), g = 0;
            bool onFloor = int(p["pose"].num()) == 0 && p["clip"].str().empty();
            bool ok = onFloor && scene.groundAt(x, y, z + 0.6f, g);
            if (ok) std::fprintf(side, "%s%.3f", i ? "," : "", z - g); else std::fprintf(side, "%snull", i ? "," : "");
        }
        std::fprintf(side, "]}\n");
        ++written;
        if (written % 50 == 0) std::fprintf(stderr, "%d frames (t %.1f s)\n", written, t / 1000);
    }
    std::fclose(side);
    std::fprintf(stderr, "wrote %d frames to %s (GL error %#x)\n", written, out.c_str(), glGetError());
    return 0;
}
