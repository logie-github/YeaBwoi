// Draws Wifecraft's scenes on a desktop, without a window, to check the renderer against the
// game's files. Reads commands (one per line) from a file:
//
//   files DIR                                  the downloaded files, each named by its id
//   time HOURS                                 the hour (0..24) on the location's light (daylight.h; negative: off)
//   gfx [off] [NAME=VALUE...]                  Options' Graphics (Scene::setGraphics; names: scale msaa aniso texture
//                                              tonemap exposure bloom dof aperture dofeditor ao aoquality contact shadows
//                                              shadowquality skin hair shafts haze vignette grain chroma sharpen timers)
//   gfxtiming on|off | gfxstats                each pass timed with glFinish round it, and the last frame's timings printed
//   cinematic MOOD VIGNETTE GRAIN LETTERBOX    cinematic mode's look (Scene::setCinematic), each 0..1; LETTERBOX 1: the
//                                              2.39:1 bars of the next render's size (grade.h letterboxBars)
//   zonemood STRENGTH [boost]                  cinematic mode's zone mood (Scene::setZoneMood): 0..1, boost its colours
//   city ROOT GROUP,GROUP,...  X Y Z RADIUS [X Y Z ...]   Dalaran's groups and the props near the points
//   actor N MODEL SCALE X Y Z FACING [tex T=F,T=F] [geo GROUP=VALUE,...]
//   attach N ATTACHMENT MODEL T=F,...
//   anim N ANIM [once]
//   camera EX EY EZ TX TY TZ FOV
//   focus NEAR FAR [STRENGTH]                  depth of field's sharp band as the app's FocusTarget gives it (Scene::setFocus; FAR 0: the scene's own)
//   light day|dusk|night
//   fog START END
//   lightv V1 ... V23                          a light as the app passes it (Lights.of)
//   clear                                      takes everyone off stage
//   look N LOOKFILE X Y Z FACING               a person from a look file (below), dressed
//   dress N LOOKFILE                           dresses person N again (Native.dress): geosets, layers, extras
//   boneanim N ANIMFILE                        an imported animation on person N (Scene::setBoneAnim; file below)
//   bonestop N                                 takes it off (Scene::clearBoneAnim)
//   stockings N ankle|knee|thigh|tights DENIER R G B [seam] [elastic] | stockings N off   procedural stockings (stockings.h)
//   stocklook N ELASTICITY TEAR_STRAIN SHEEN SIDE   the fabric's give and look; stockpull / stockpullto N SX SY DX DY STEPS W H [TENSION] / stockrelease / stockundo
//                                              pull, hold at a tension, let go and mend (stockfabric.h)
//   layer N ANIMFILE | layerstop N             an additive bone layer on person N (Scene::setBoneLayer; the anim file's rot
//                                              tracks, bones by WoW-side name or "#N": the model's bone N)
//   reach N TARGET BONE L|R ANIMFILE | reachstop N   person N's left or right arm on a point of person TARGET's bone BONE (an index of
//                                              TARGET's model): Scene::setReach; the file's pos tracks reach.w, reach.off, reach.along, reach.pole
//   handhead OUT.csv PERSON PMODEL TARGET TMODEL HEADBONE T0 T1 STEP_MS   steps the scene and writes, per time, the weight, how far the
//                                              reach was from its point, and the hand's distance to the TARGET's skinned head
//                                              (the triangles under HEADBONE: palm, wrist, finger root; negative: inside)
//   humanscale N TARGET|own XY                 person N at human scale (TARGET: the sex's standing height; own: the look's
//                                              scale, a dwarf or gnome) and its root motion across the floor (HumanScale.PAIR)
//   furnish N sit|lie SX SY SZ FACING          person N's pelvis on a seat or bed at that surface point (Scene::placeActor)
//   soft off|subtle|natural|strong             Options' Soft body (Scene::setSoftBody; the scene's default: natural)
//   wcprop FILE.wcprop GUID X Y Z YAWDEG SCALE [FILE GUID ...]   Sims object props (propmesh.h): each file loaded under its id and drawn
//                                              at that place (yaw about up, a uniform scale; the prop's own axes are Warcraft's); "wcprop off" clears
//   render OUT.ppm|OUT.png WIDTH HEIGHT TIME_MS
//   bonepoints N                              prints person N's head, chest and pelvis in world coordinates as last drawn (Scene::actorBonePoints: JOI mode's camera)
//   bonepos N MODEL BONE...                   prints where those bones' pivots are as person N was last drawn (WoW-side names)
//   location JSONFILE [ID [VIEW]]              a location (Scene::loadLocation; ID chooses the entry, VIEW its light/fog)
//   view NAME                                  the loaded location's view: its light and fog, and its camera
//   prop N MODEL X Y Z YAW SCALE               an editor prop (Scene::addProp: an M2 or a WMO by file id)
//   moveprop N X Y Z YAW SCALE | removeprop N | clearprops
//   select N                                   prop N drawn selected (0: none)
//   pick SX SY WIDTH HEIGHT                    prints what that screen point hits (Scene::pick)
//   ground X Y Z                               prints the floor under the point (Scene::groundAt)
//   stats                                      prints the location's counts and the last frame's draws
//   gizmo off|move|rotate|scale [prop N | actor N] [AXIS]   the placement editor's handles on a prop or person (AXIS lit)
//   gizmohit SX SY WIDTH HEIGHT [SLOP]         prints the handle under that screen point (Scene::gizmoHit; -1 none)
//   ghost MODEL X Y Z YAW SCALE | ghostlook LOOKFILE X Y Z YAW   the ghost of what is about to be placed (a prop, a look)
//   ghostmove X Y Z YAW | ghostclear
//   thumb prop FILE_ID|look LOOKFILE SIZE OUT.png   a library thumbnail (Scene::thumbnail), drawn between frames
//   card LOOKFILE W H LO HI AZ EL FITWIDTH CLEAR OUT.png   a Transmog card (Scene::ThumbFrame; RGBA PNG when CLEAR), timed
//   camerainfo                                 prints the camera as last drawn (Scene::cameraInfo)
//   timing on|off                              prints how long each later command took (what the GL thread would stall)
//   part N WCBP PNG SKIN                       a body part file on person N (Scene::loadPart, attachPart: its slot by its
//                                              state), its PNG tinted to SKIN as the app's Recolor does (RRGGBB hex, or
//                                              auto: the person's look's base skin layer averaged over its body half)
//   partstate N 0|1|2                          which part is drawn (Scene::setPartState: none, the default one, the on one)
//   partfit N DX DZ SCALE                      the race's fit (Scene::setPartFit; bodyparts/PartFit.kt's numbers)
//   partpose N                                 prints where the drawn part's root is (model space) and its scale
//   modelset N SETJSON [REGION...]             a model part set (modelpart.h; build/modelparts writes it) on person N: its
//                                              cut (Scene::setBodyCut) and its parts (Scene::attachBonePart, each with its
//                                              fit), only those of the REGIONs when named
//   modelpart N WCBP [DX DY DZ SCALE]          one model part on person N, with that fit
//   modelfit N REGION|all DX DY DZ SCALE       changes the fit of person N's parts of that region (Scene::setBonePartFit)
//   modelshow N REGION|all 0|1                 hides or shows them (Scene::setBonePartVisible)
//   bodycut N [BONE...]                        person N's own triangles under those bones left out (none: the cut taken off)
//   modelpose N                                prints each drawn model part's bone, pivots and scale
//   bodytint N R G B                           person N's body drawn times that colour (Scene::setBodyTint)
//   skinmatch N [RRGGBB]                       person N's body tinted so its face's skin (Scene::faceSkinUvs, sampled on the
//                                              look's base skin layer) comes to RRGGBB (default: the last modelset's
//                                              skinColour), as the app does with its composed skin
//   bench N W H STEP_MS [walk X Y Z]      draws N frames STEP_MS apart into one target, each timed to glFinish:
//                                              p50/p95/max and the slowest frames (walk: everyone circles as they walk)
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
// Or one BLP decoded by the app's own decoder (engine/blp.cpp, what Native.decodeBlp runs; no GL), to an RGBA picture:
//   build/harness --decode BLPFILE --out OUT.png [--max N]   prints "W H" (exit 1: not a BLP2 the decoder takes)
// Or a library thumbnail alone:
//   build/harness --thumb prop|look FILE_ID|LOOKFILE --files DIR --out OUT.png [--tsize N]
// --location also takes --gizmo move|rotate|scale (on --select's prop, or the --look person with --gizmoactor 1), --axis N,
// --gizmohit "SX,SY;..." (prints each point's handle), --ghost "MODEL,X,Y,Z,YAW,SCALE", and --thumbcheck "prop:ID" or
// "look:FILE" (draws the scene, the thumbnail, the scene again, and says whether the two scenes are the same pixels).
// --strip draws N pictures side by side, from --time every STEP_MS, the scene stepped at 60 frames a second between them
// (as the phone draws), and prints each soft body region's displacement at each.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <chrono>
#include <thread>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <tuple>
#include <sys/stat.h>
#include <vector>

#include "../android/app/src/main/cpp/engine/blp.h"
#include "../android/app/src/main/cpp/engine/json.h"
#include "../android/app/src/main/cpp/engine/scene.h"
#include "partpaint.h"

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

static void writePng(const std::string& path, int w, int h, const std::vector<uint8_t>& rgbTopDown, int channels = 3) {
    std::vector<uint8_t> raw;
    for (int y = 0; y < h; ++y) {
        raw.push_back(0);
        raw.insert(raw.end(), rgbTopDown.begin() + size_t(y) * w * channels, rgbTopDown.begin() + size_t(y + 1) * w * channels);
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
                                 uint8_t(h >> 24), uint8_t(h >> 16), uint8_t(h >> 8), uint8_t(h), 8, uint8_t(channels == 4 ? 6 : 2), 0, 0, 0};
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


// A moving person's strip: the actor placed every 16 ms along MODE (hop: jumps and lands; bounce: a quick small bob; sway: hips side to
// side with the turn; walk: its own animation, the placement still), the scene drawn at each; N pictures from `warm` ms every `step` ms.
static void renderMotion(Scene& scene, int actor, V3 base, float facing, const std::string& mode, float amp, float hz, const std::string& out, int w, int h,
                         uint32_t warm, uint32_t step, int n) {
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
    int shot = 0;
    uint32_t nextShot = warm;
    while (shot < n) {
        float s = float(t) / 1000.0f, ph = 2.0f * 3.14159265f * hz * s;
        V3 p = base; float f = facing;
        if (mode == "hop") { float u = std::fmod(hz * s, 1.0f); p.z += amp * std::max(0.0f, std::sin(u * 3.14159265f * 1.0f)) * (u < 0.55f ? 1.0f : 0.0f); }
        else if (mode == "bounce") p.z += amp * 0.5f * (1.0f - std::cos(ph));
        else if (mode == "sway") { p.y += amp * std::sin(ph); f += 0.35f * std::sin(ph + 1.2f); }
        else if (mode == "stomp") { float u = std::fmod(hz * s, 1.0f); p.z += u < 0.25f ? amp * std::sin(u / 0.25f * 3.14159265f) : 0.0f; }
        scene.placeActor(actor, p, f);
        scene.render(w, h, t);
        if (t >= nextShot) {
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x)
                    std::copy(&px[(size_t(h - 1 - y) * w + x) * 4], &px[(size_t(h - 1 - y) * w + x) * 4] + 3,
                              &strip[(size_t(y) * W + size_t(shot) * w + x) * 3]);
            ++shot; nextShot += step;
        }
        t += 16;
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


// A look file as Scene::thumbnail takes it.
static Scene::ThumbLook thumbLook(const LookFile& lf) {
    Scene::ThumbLook t;
    t.model = lf.model; t.scale = lf.scale; t.textures = lf.tex; t.geo = lf.geo; t.layers = lf.layers; t.extras = lf.extras;
    if (lf.body) { t.morph = lf.morph; t.bodyK = lf.bodyK; t.bodyFold = lf.bodyFold; }
    return t;
}

// "prop:ID" or "look:FILE": the thumbnail, written to `out` (false: why, printed).
static bool thumbTo(Scene& scene, const std::string& what, int size, const std::string& out) {
    size_t colon = what.find(':');
    std::string kind = what.substr(0, colon), asset = colon == std::string::npos ? "" : what.substr(colon + 1);
    std::vector<uint8_t> rgba;
    std::string err;
    bool ok;
    if (kind == "look") {
        LookFile lf;
        if (!readLook(asset, lf)) return false;
        Scene::ThumbLook t = thumbLook(lf);
        ok = scene.thumbnail(0, &t, size, rgba, err);
    } else {
        ok = scene.thumbnail(uint32_t(std::strtoul(asset.c_str(), nullptr, 10)), nullptr, size, rgba, err);
    }
    if (!ok) { std::fprintf(stderr, "thumb %s: FAILED: %s\n", what.c_str(), err.c_str()); return false; }
    std::vector<uint8_t> rgb(size_t(size) * size * 3);
    for (size_t i = 0; i < size_t(size) * size; ++i) std::copy(&rgba[i * 4], &rgba[i * 4] + 3, &rgb[i * 3]);
    writePng(out, size, size, rgb);
    std::fprintf(stderr, "thumb %s: wrote %s (%dx%d, GL error %#x)\n", what.c_str(), out.c_str(), size, size, glGetError());
    return true;
}

static int gizmoMode(const std::string& s) {
    return s == "move" ? Scene::kGizmoMove : s == "rotate" ? Scene::kGizmoRotate : s == "scale" ? Scene::kGizmoScale : Scene::kGizmoOff;
}

// The pixels of the scene drawn now (RGBA, bottom row first).
static std::vector<uint8_t> grab(Scene& scene, int w, int h, uint32_t t) {
    unsigned fbo, color, depth;
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
    glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    scene.render(w, h, t);
    std::vector<uint8_t> px(size_t(w) * h * 4);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo); glDeleteRenderbuffers(1, &color); glDeleteRenderbuffers(1, &depth);
    return px;
}

// Frame pacing on the desktop (llvmpipe: the absolute times mean little, the spikes do): N frames into one target.
static void bench(Scene& scene, const std::map<int, int>& actors, int n, int w, int h, uint32_t stepMs, bool walk, V3 origin) {
    unsigned fbo, color, depth;
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
    glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    std::vector<double> ms;
    std::map<int, V3> home;
    for (const auto& [name, id] : actors)
        home[name] = origin + V3(float(name % 3) - 1.0f, float(name / 3) * 1.2f - 0.6f, 0.0f);
    static uint32_t clock = 100000;
    for (int i = 0; i < n; ++i) {
        clock += stepMs;
        auto t0 = std::chrono::steady_clock::now();
        if (walk) for (const auto& [name, id] : actors) {
            float a = float(clock) * 0.0008f + float(name);
            scene.placeActor(id, home[name] + V3(std::cos(a) * 0.8f, std::sin(a) * 0.8f, 0), a + 1.5708f);
        }
        scene.render(w, h, clock);
        glFinish();
        ms.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    }
    std::vector<double> sorted = ms;
    std::sort(sorted.begin(), sorted.end());
    auto at = [&](double q) { return sorted[std::min(sorted.size() - 1, size_t(q * double(sorted.size())))]; };
    std::fprintf(stderr, "bench %d frames %dx%d: p50 %.2f ms  p95 %.2f ms  max %.2f ms (first %.2f ms)\n", n, w, h, at(0.5), at(0.95),
                 sorted.back(), ms.front());
    std::vector<size_t> order(ms.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return ms[a] > ms[b]; });
    for (size_t k = 0; k < std::min<size_t>(5, order.size()); ++k)
        if (ms[order[k]] > at(0.5) * 2.0) std::fprintf(stderr, "  spike: frame %zu %.2f ms (%.1fx p50)\n", order[k], ms[order[k]], ms[order[k]] / at(0.5));
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo); glDeleteRenderbuffers(1, &color); glDeleteRenderbuffers(1, &depth);
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: harness COMMANDS | harness --look LOOKFILE --files DIR --out OUT.png [--size WxH] [--shot body|torso|face] [--facing RADIANS] [--time MS]\n");
        return 2;
    }
    std::map<std::string, std::string> opt;
    if (std::string(argv[1]).rfind("--", 0) == 0)
        for (int i = 1; i + 1 < argc; i += 2) {
            if (std::string(argv[i]) == "--thumb" && i + 2 < argc) { opt["--thumb"] = std::string(argv[i + 1]) + ":" + argv[i + 2]; ++i; continue; }
            opt[argv[i]] = argv[i + 1];
        }

    if (opt.count("--decode")) {
        Bytes file;
        Image img;
        int maxSize = opt.count("--max") ? std::atoi(opt["--max"].c_str()) : 4096;
        if (!readFile(opt["--decode"], file) || !decodeBlp(file, maxSize, img)) { std::fprintf(stderr, "decode %s: FAILED\n", opt["--decode"].c_str()); return 1; }
        std::printf("%d %d\n", img.width, img.height);
        if (opt.count("--out")) writePng(opt["--out"], img.width, img.height, img.rgba, 4);
        return 0;
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
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
        std::fprintf(stderr, "no GLES 3 context\n"); return 1;
    }
    std::fprintf(stderr, "GL: %s / %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

    std::string dir;
    FileLookup lookup = [&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 ? p : std::string();
    };
    Scene scene(lookup);
    scene.setSculptThrottle(false);   // (each still shows the sculpt it was given)
    scene.initGl();
    std::map<int, int> actors, props;
    std::map<int, V3> basePos; std::map<int, float> baseFacing;
    float fingerSize = 1.0f, fingerChar = 1.0f;   // the `finger` command
    Light light;

    if (opt.count("--thumb") && !opt.count("--location")) {
        dir = opt["--files"];
        int size = opt.count("--tsize") ? std::atoi(opt["--tsize"].c_str()) : 160;
        return thumbTo(scene, opt["--thumb"], size, opt.count("--out") ? opt["--out"] : "thumb.png") ? 0 : 1;
    }

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
            if (n >= 1 && n <= props.size() && opt.count("--gizmo"))
                scene.setGizmo(gizmoMode(opt["--gizmo"]), Scene::kPickProp, props[n - 1], opt.count("--axis") ? std::atoi(opt["--axis"].c_str()) : -1);
        }
        {
            float g[6] = {0, 0, 0, 0, 0, 1};
            if (opt.count("--ghost") && std::sscanf(opt["--ghost"].c_str(), "%f,%f,%f,%f,%f,%f", &g[0], &g[1], &g[2], &g[3], &g[4], &g[5]) >= 4)
                std::fprintf(stderr, "ghost %u: %s\n", uint32_t(g[0]), scene.ghostProp(uint32_t(g[0]), V3(g[1], g[2], g[3]), g[4], g[5]) ? "ok" : scene.modelError(uint32_t(g[0])).c_str());
            V3 a, b;
            if (scene.ghostBounds(a, b)) std::fprintf(stderr, "ghost bounds %.2f %.2f %.2f .. %.2f %.2f %.2f\n", a.x, a.y, a.z, b.x, b.y, b.z);
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
                    if (opt.count("--gizmo") && opt.count("--gizmoactor"))
                        scene.setGizmo(gizmoMode(opt["--gizmo"]), Scene::kPickActor, id, opt.count("--axis") ? std::atoi(opt["--axis"].c_str()) : -1);
                }
            }
        }
        int w = 1024, ht = 640;
        if (opt.count("--size")) std::sscanf(opt["--size"].c_str(), "%dx%d", &w, &ht);
        renderTo(scene, opt.count("--out") ? opt["--out"] : "location.png", w, ht, 0);
        printStats(scene);
        for (const std::string& one : split(opt["--gizmohit"], ';')) {
            float hx, hy;
            if (std::sscanf(one.c_str(), "%f,%f", &hx, &hy) == 2)
                std::fprintf(stderr, "gizmohit %.0f,%.0f: %d\n", hx, hy, scene.gizmoHit(hx, hy, w, ht, 40));
        }
        if (opt.count("--thumbcheck")) {
            // the live scene before and after a thumbnail: the same pixels (nothing of it was changed)
            std::vector<uint8_t> before = grab(scene, w, ht, 0);
            Scene::CameraInfo c0 = scene.cameraInfo();
            thumbTo(scene, opt["--thumbcheck"], 160, opt.count("--thumbout") ? opt["--thumbout"] : "thumbcheck.png");
            std::vector<uint8_t> after = grab(scene, w, ht, 0);
            Scene::CameraInfo c1 = scene.cameraInfo();
            size_t diff = 0;
            for (size_t i = 0; i < before.size(); ++i) diff += before[i] != after[i];
            bool sameCam = std::memcmp(c0.view.m, c1.view.m, 64) == 0 && std::memcmp(c0.proj.m, c1.proj.m, 64) == 0;
            std::fprintf(stderr, "thumbcheck: %zu of %zu bytes differ after the thumbnail; camera %s\n", diff, before.size(), sameCam ? "unchanged" : "CHANGED");
        }
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
    std::map<int, LookFile> looks;   // each person's look file (part's auto skin)
    std::map<int, std::vector<std::pair<int, std::string>>> modelParts;   // each person's model parts: handle, region
    std::map<int, std::string> skinColours;   // each person's last model set's skinColour
    bool timing = false;
    float cine[4] = {0, 0, 0, 0};   // cinematic MOOD VIGNETTE GRAIN LETTERBOX
    std::string timed;
    auto started = std::chrono::steady_clock::now();
    auto report = [&]() {
        if (timing && !timed.empty())
            std::fprintf(stderr, "  [%.1f ms] %s\n", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count(), timed.c_str());
        timed.clear();
    };
    while (std::getline(in, line)) {
        report();
        std::istringstream ss(line);
        std::string cmd;
        ss >> cmd;
        if (cmd.empty() || cmd[0] == '#') continue;
        glFinish();
        started = std::chrono::steady_clock::now();
        timed = line.substr(0, 60);
        if (cmd == "files") ss >> dir;
        else if (cmd == "timing") { std::string v; ss >> v; timing = v == "on"; }
        else if (cmd == "bench") {
            int n = 120, w = 1024, h = 640; uint32_t step = 16; std::string walk; float ox = 0, oy = 0, oz = 0;
            ss >> n >> w >> h >> step >> walk >> ox >> oy >> oz;
            bench(scene, actors, n, w, h, step, walk == "walk", V3(ox, oy, oz));
            timed.clear();
        }
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
            if (id > 0) { scene.placeActor(id, V3(x, y, z), facing); actors[name] = id; basePos[name] = V3(x, y, z); baseFacing[name] = facing; }
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
        } else if (cmd == "focus") {
            float n = 0, f = 0, k = 1;
            ss >> n >> f >> k;
            scene.setFocus(n, f, k);
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
            modelParts.clear();
        } else if (cmd == "time") {
            float hours; ss >> hours; scene.setTimeOfDay(hours);   // the world clock's hour on the location (negative: off)
        } else if (cmd == "cinematic") {
            ss >> cine[0] >> cine[1] >> cine[2] >> cine[3];
        } else if (cmd == "zonemood") {   // zonemood STRENGTH [boost]: cinematic mode's zone mood (Scene::setZoneMood)
            float z = 0.7f; std::string b; ss >> z >> b; scene.setZoneMood(z, b == "boost" || b == "1");
        } else if (cmd == "gfx") {   // gfx [off] [NAME=VALUE...]: Options' Graphics (graphics_settings.h's names, lower case)
            static const char* kNames[GraphicsSettings::kCount] = {"scale", "msaa", "aniso", "texture", "tonemap", "exposure",
                "bloom", "dof", "aperture", "dofeditor", "ao", "aoquality", "contact", "shadows", "shadowquality", "skin", "hair",
                "shafts", "haze", "vignette", "grain", "chroma", "sharpen", "timers", "eyes", "thinskin", "fleshcontact", "fleshsoftness", "softtissue", "jiggle", "jigglestyle", "lattice"};
            static float gfxv[GraphicsSettings::kCount];
            static bool gfxInit = false;
            auto reset = [] { GraphicsSettings d; for (float& v : gfxv) v = 0; gfxv[GraphicsSettings::kRenderScale] = 1;
                gfxv[GraphicsSettings::kAnisotropy] = 1; gfxv[GraphicsSettings::kTextureDetail] = 1;
                gfxv[GraphicsSettings::kAperture] = d.aperture; gfxv[GraphicsSettings::kFleshContact] = 2; gfxv[GraphicsSettings::kFleshSoftness] = 1; gfxv[GraphicsSettings::kSoftTissue] = 2; gfxv[GraphicsSettings::kJiggle] = 1; gfxv[GraphicsSettings::kJiggleStyle] = 1; gfxv[GraphicsSettings::kLattice] = 1; gfxv[GraphicsSettings::kAoQuality] = 1; gfxv[GraphicsSettings::kShadowQuality] = 1; };
            if (!gfxInit) { reset(); gfxInit = true; }
            std::string kv;
            while (ss >> kv) {
                if (kv == "off") { reset(); continue; }
                size_t eq = kv.find('=');
                std::string k = kv.substr(0, eq);
                for (int i = 0; i < GraphicsSettings::kCount; ++i)
                    if (eq != std::string::npos && k == kNames[i]) gfxv[i] = std::stof(kv.substr(eq + 1));
            }
            scene.setGraphics(gfxv, GraphicsSettings::kCount);
        } else if (cmd == "sheen") {       // sheen NAME VALUE: the person's skin sheen 0..1 (Scene::setSheen)
            int name = 0; float v = 0; ss >> name >> v;
            if (actors.count(name)) scene.setSheen(actors[name], v);
        } else if (cmd == "sun") {         // prints where the sun is (the way toward it) and its colour: aim the camera at it
            const Light& l = scene.light();
            std::printf("sun toward %.3f %.3f %.3f colour %.2f %.2f %.2f\n", -l.sunDir[0], -l.sunDir[1], -l.sunDir[2],
                        l.sunColor[0], l.sunColor[1], l.sunColor[2]);
        } else if (cmd == "texmem") {      // prints the game textures loaded and their GPU bytes (texture detail's cost)
            size_t n = 0, bytes = 0;
            scene.textureMemory(n, bytes);
            std::printf("texmem %zu textures %.1f MB\n", n, bytes / 1048576.0);
        } else if (cmd == "gfxtiming") {   // gfxtiming on|off: each pass timed with glFinish (software GL: not the phone's cost)
            std::string on; ss >> on;
            scene.setGraphicsSyncTiming(on == "on");
        } else if (cmd == "gfxstats") {    // prints the last frame's pass timings (ms)
            float v[PostStats::kStages + 4];
            scene.graphicsStats(v, PostStats::kStages + 4);
            std::printf("gfxstats cpu %.2f shadow %.2f scene %.2f ao %.2f lighting %.2f bloom %.2f dof %.2f shafts %.2f final %.2f timers %d size %dx%d\n",
                v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], int(v[9]), int(v[10]), int(v[11]));
        } else if (cmd == "motionparams") {   // motionparams BREATHING IDLE LOOKAT(0|1): Options' shares (motionlayer.h)
            MotionParams p; int look = 1;
            ss >> p.breathing >> p.idle >> look; p.lookAt = look != 0;
            scene.setMotionParams(p);
        } else if (cmd == "motion") {         // motion NAME INTENSITY TARGET_NAME (0 nobody, -1 the camera): the body's layer on
            int name, target = 0; float intensity = 0.2f;
            ss >> name >> intensity >> target;
            int tid = target > 0 ? (actors.count(target) ? actors[target] : 0) : target;
            if (actors.count(name)) scene.setMotion(actors[name], intensity, tid);
        } else if (cmd == "nomotion") {       // nomotion NAME: the layer off
            int name; ss >> name;
            if (actors.count(name)) scene.clearMotion(actors[name]);
        } else if (cmd == "particles") {      // particles COUNT STRENGTH [SEED]: the atmosphere's motes (particles.h)
            int n = 0; float s = 0; uint32_t seed = 7;
            ss >> n >> s >> seed;
            scene.setParticles(n, s, seed);
        } else if (cmd == "motionstate") {    // motionstate NAME: prints its eased intensity and look-at angles (degrees)
            int name; ss >> name;
            const MotionState* st = actors.count(name) ? scene.motionState(actors[name]) : nullptr;
            if (st) std::printf("motion %d: intensity %.2f yaw %.1f pitch %.1f clip %.2f target %d\n", name, st->shown,
                                st->yaw * 57.2958f, st->pitch * 57.2958f, st->clipWeight, st->hasTarget ? 1 : 0);
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
            looks[name] = lf;
            modelParts.erase(name);
        } else if (cmd == "part") {
            int name; std::string wcbp, png, skin;
            ss >> name >> wcbp >> png >> skin;
            if (!actors.count(name)) continue;
            int part = scene.loadPart(wcbp);
            if (!part) { std::fprintf(stderr, "part: FAILED: %s\n", scene.partError().c_str()); continue; }
            Bytes raw;
            PartData pd;
            std::string err;
            readFile(wcbp, raw);
            parsePart(raw.data(), raw.size(), pd, err);
            partpaint::Rgba img;
            if (!partpaint::readPng(png, img)) { std::fprintf(stderr, "part: no PNG %s\n", png.c_str()); continue; }
            int target = -1;
            if (skin == "auto") {
                // the look's base skin (type 1, its lowest layer: the whole material) sampled where the body meets the parts
                // (Scene::partSkinUvs), as the app does with the skin it composed; the material's right half when it can't be
                const SkinLayer* base = nullptr;
                for (const SkinLayer& l : looks[name].layers) if (l.textureType == 1 && l.layer != kBodyLayer && (!base || l.layer < base->layer)) base = &l;
                Bytes blp; Image sk;
                if (base && readFile(lookup(base->fileId), blp) && decodeBlp(blp, 1024, sk)) {
                    std::vector<float> uvs = scene.partSkinUvs(actors[name]);
                    target = partpaint::sampleAt(sk.rgba.data(), sk.width, sk.height, uvs, 2);
                    if (target < 0) target = partpaint::averageColor(sk.rgba.data(), sk.width, sk.height, {}, sk.width / 2, 0, sk.width, sk.height);
                    std::fprintf(stderr, "part %d: skin sampled at %zu points\n", name, uvs.size() / 2);
                }
            } else {
                target = int(std::strtol(skin.c_str(), nullptr, 16));
            }
            if (target >= 0) partpaint::recolor(img, target, partpaint::uvMask(pd, img.w, img.h));
            if (target >= 0) scene.setPartSkin(actors[name], target);   // (the root blended to it: bodypart.h blendRootToSkin)
            bool ok = scene.attachPart(actors[name], part, img.px.data(), img.w, img.h);
            std::fprintf(stderr, "part %d %s (%s %s): %s, skin %06X\n", name, pd.id.c_str(), pd.state.c_str(), pd.variant.c_str(), ok ? "ok" : "FAILED", target < 0 ? 0 : target);
        } else if (cmd == "hdset") {
            // hdset PATH: an HD model set (.whd: tools/hd/whd_convert.py), kept under its race and sex
            std::string path, err; ss >> path;
            bool ok = scene.loadHdSet(path, err);
            std::fprintf(stderr, "hdset %s: %s\n", path.c_str(), ok ? "ok" : err.c_str());
        } else if (cmd == "hd") {
            // hd N RACE SEX ON [HAIR OUTFIT EYES FACEMARK BODYMARKS TINT HAIRTINT JEWEL...]: person N drawn as the HD figure
            // (Scene::setHdBody; -1: the set's default; HAIR -2 none; OUTFIT -1 bare)
            int name, race, sex, on; HdOptions o;
            ss >> name >> race >> sex >> on;
            if (ss >> o.hair) { ss >> o.outfit >> o.eyes >> o.faceMark >> o.bodyMarks >> o.tint >> o.hairTint; int j; while (ss >> j) o.jewelry.push_back(j); }
            o.on = on != 0;
            if (actors.count(name)) scene.setHdBody(actors[name], race, sex, o);
        } else if (cmd == "hdstatus") {
            // hdstatus N: whether person N is drawn as its HD figure, and the fit's numbers
            int name; ss >> name;
            Scene::HdStatus st;
            if (actors.count(name) && scene.hdStatus(actors[name], st))
                std::printf("hdstatus %d: requested %d active %d set '%s' status '%s' scale %.3f limbDeg %.2f verts %zu tris %zu unmapped %zu fit %.1f ms\n", name,
                            st.requested, st.active, st.set.c_str(), st.status.c_str(), st.stats.scale, st.stats.jointError, st.stats.vertices,
                            st.stats.triangles, st.stats.unmapped, st.stats.ms);
        } else if (cmd == "frametime") {
            // frametime N W H: N frames drawn (the clock held), their mean and worst time on this thread
            int n, w, h; ss >> n >> w >> h;
            double worst = 0, sum = 0;
            for (int f = 0; f < n; ++f) {
                auto t0 = std::chrono::steady_clock::now();
                scene.render(w, h, 0); glFinish();
                double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                worst = std::max(worst, ms); sum += ms;
            }
            std::printf("frametime: %d frames mean %.2f ms worst %.2f ms\n", n, sum / std::max(n, 1), worst);
        } else if (cmd == "sculpt") {
            // sculpt N V0 .. V21: person N's sculpt sliders (Scene::setSculpt; creator/BodySculpt.kt's order, -1..1)
            int name; ss >> name;
            std::vector<float> v; float x;
            while (ss >> x) v.push_back(x);
            if (actors.count(name)) scene.setSculpt(actors[name], v.data(), v.size());
        } else if (cmd == "sculptlive") {
            // sculptlive on|off: the creator's live sculpt as the phone has it (Scene::setSculptThrottle on) with the CPU sculpt
            // held back (setSculptRefitHold): each later sculpt is drawn by the GPU fields alone until "sculptlive off" (the
            // stills' way again: every sculpt made on the CPU at the next frame)
            std::string on; ss >> on;
            scene.setSculptThrottle(on == "on");
            scene.setSculptRefitHold(on == "on");
        } else if (cmd == "sculptstat") {
            // sculptstat N: person N's live sculpt fields (GPU MB, the ms they took) and whether a change is drawn by them now
            int name; ss >> name;
            float ms = 0; bool drawing = false;
            size_t bytes = actors.count(name) ? scene.sculptMorphBytes(actors[name], &ms, &drawing) : 0;
            std::printf("sculptstat %d: fields %.2f MB, made in %.1f ms, drawing %s; last CPU sculpt %.1f ms\n", name, bytes / 1048576.0, ms,
                        drawing ? "yes" : "no", actors.count(name) ? scene.sculptMs(actors[name]) : 0.0f);
        } else if (cmd == "sculpttime") {
            // sculpttime N FRAMES SLIDER: a drag of SLIDER from -1 to 1 over FRAMES frames (320 x 540), drawn twice: by the live
            // sculpt (the GPU fields, the CPU sculpt held back) and by the CPU sculpt at every change (as before it); then the
            // same frames with nothing changing. Each frame's time: setSculpt and the frame, glFinish'd (llvmpipe: the GPU's work
            // is this machine's CPU too).
            int name, n, k; ss >> name >> n >> k;
            if (!actors.count(name) || n < 1 || k < 0 || k >= 64) continue;
            unsigned fbo, rb[2];   // (an off-screen target as renderTo's)
            glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glGenRenderbuffers(2, rb);
            glBindRenderbuffer(GL_RENDERBUFFER, rb[0]); glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, 320, 540);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb[0]);
            glBindRenderbuffer(GL_RENDERBUFFER, rb[1]); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, 320, 540);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb[1]);
            auto run = [&](bool live, bool still) {
                scene.setSculptThrottle(live);
                scene.setSculptRefitHold(live);
                std::vector<float> v(size_t(k + 1), 0.0f);
                double sum = 0, worst = 0;
                for (int i = 0; i < n; ++i) {
                    v[size_t(k)] = still ? 0.5f : -1.0f + 2.0f * float(i + 1) / float(n);
                    auto t0 = std::chrono::steady_clock::now();
                    scene.setSculpt(actors[name], v.data(), v.size());
                    scene.render(320, 540, 0); glFinish();
                    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                    if (i > 0) { sum += ms; worst = std::max(worst, ms); }   // (the first: the fields made, or a first sculpt)
                }
                std::printf("sculpttime %s: %d frames mean %.2f ms worst %.2f ms\n", still ? "still" : live ? "live (GPU fields)" : "CPU sculpt",
                            n - 1, sum / std::max(1, n - 1), worst);
            };
            run(true, false);
            run(false, false);
            run(false, true);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &fbo); glDeleteRenderbuffers(2, rb);
            scene.setSculptThrottle(false);
            scene.setSculptRefitHold(false);
        } else if (cmd == "bodypick") {
            // bodypick N X Y W H: the part of person N's body at pixel (X, Y) of a W x H view of the last camera (bodypick.h)
            int name, w, h; float x, y; ss >> name >> x >> y >> w >> h;
            std::printf("bodypick %d %.0f %.0f: %d\n", name, x, y, actors.count(name) ? scene.pickBodyRegion(actors[name], x, y, w, h) : -1);
        } else if (cmd == "fleshmeasure") {
            // fleshmeasure N: person N's flesh measure (Scene::actorFlesh, as the simulation reads it) and soft-body regions
            int name; ss >> name;
            if (!actors.count(name)) continue;
            std::vector<float> fl;
            auto t0 = std::chrono::steady_clock::now();
            bool ok = scene.actorFlesh(actors[name], fl);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::printf("flesh %d: %s (%.1f ms) %zu floats:", name, ok ? "ok" : "not yet", ms, fl.size());
            for (size_t i = 0; i < fl.size() && i < 48; ++i) std::printf(" %.3f", fl[i]);
            std::printf("\n");
            if (const SoftSim* sim = scene.softSim(actors[name]))
                for (size_t r = 0; r < sim->regions().size(); ++r) {
                    const SoftRegion& g = sim->regions()[r];
                    std::printf("soft %d region %zu: kind %d centre %.3f %.3f %.3f radii %.3f %.3f %.3f weight %.2f\n", name, r, int(g.kind),
                                g.centre.x, g.centre.y, g.centre.z, g.radii.x, g.radii.y, g.radii.z, g.weight);
                }
        } else if (cmd == "partstate") {
            int name, st; ss >> name >> st;
            if (actors.count(name)) scene.setPartState(actors[name], st);
        } else if (cmd == "partfit") {
            int name; PartFit f; ss >> name >> f.dx >> f.dz >> f.scale;
            if (actors.count(name)) scene.setPartFit(actors[name], f);
        } else if (cmd == "partdrive") {
            // partdrive N LEVEL [PARTNER ENTRY REST]: arousal 0..1 (-1 off) and the insertion (Scene::setPartDrive; ENTRY
            // bodypart.h PartEntry: 1 vaginal, 2 anal, 3 oral, 5 thigh, 6 chest; REST Sims metres)
            int name, partner = 0, entry = 0; float level = -1, rest = 0;
            ss >> name >> level >> partner >> entry >> rest;
            if (actors.count(name)) scene.setPartDrive(actors[name], level, actors.count(partner) ? actors[partner] : 0, entry, rest);
        } else if (cmd == "partinfo") {
            int name; ss >> name;
            Scene::PartDriveInfo pi;
            if (actors.count(name) && scene.partDriveInfo(actors[name], pi))
                std::printf("partinfo %d: level %.2f slot %s depth %.3f distance %.3f aim %.1f deg weight %.2f root (%.3f,%.3f,%.3f) entry (%.3f,%.3f,%.3f) forward (%.3f,%.3f,%.3f)\n",
                            name, pi.level, pi.slotOn ? "on" : "default", pi.depth, pi.distance, pi.aimDeg, pi.aimWeight,
                            pi.rootWorld.x, pi.rootWorld.y, pi.rootWorld.z, pi.entryWorld.x, pi.entryWorld.y, pi.entryWorld.z,
                            pi.pelvisForward.x, pi.pelvisForward.y, pi.pelvisForward.z);
        } else if (cmd == "entry") {
            int name, kind; ss >> name >> kind;
            V3 e;
            if (actors.count(name) && scene.entryWorld(actors[name], kind, e)) std::printf("entry %d kind %d: (%.3f,%.3f,%.3f)\n", name, kind, e.x, e.y, e.z);
            else std::printf("entry %d kind %d: none\n", name, kind);
        } else if (cmd == "placeraw") {
            // placeraw N X Y Z FACING: at Z as given (Scene::kRaised, as the app places a clip raised onto furniture)
            int name; float x, y, z, f; ss >> name >> x >> y >> z >> f;
            if (actors.count(name)) scene.placeActor(actors[name], V3(x, y, z), f, Scene::kRaised);
        } else if (cmd == "partpose") {
            int name; ss >> name;
            M4 m; PartPlacement pl;
            int st = actors.count(name) ? scene.partState(actors[name]) : 0;
            if (st > 0 && scene.partPose(actors[name], st - 1, m, pl)) {
                V3 r = m.point(V3(kPartRootX, 0, kPartRootZ));
                std::printf("partpose %d slot %d: pelvis pivot (%.3f,%.3f,%.3f) crotch %.3f root bind (%.3f,%.3f,%.3f) drawn (%.3f,%.3f,%.3f) scale %.3f\n",
                            name, st - 1, pl.pivot.x, pl.pivot.y, pl.pivot.z, pl.crotch, pl.root.x, pl.root.y, pl.root.z, r.x, r.y, r.z, pl.scale);
            } else {
                std::printf("partpose %d: none drawn\n", name);
            }
        } else if (cmd == "modelset" || cmd == "modelpart") {
            int name; std::string path; ss >> name >> path;
            if (!actors.count(name)) continue;
            int id = actors[name];
            // (file, region, fit) of each part to put on
            std::vector<std::tuple<std::string, std::string, BonePartFit>> todo;
            if (cmd == "modelpart") {
                BonePartFit f; float dx = 0, dy = 0, dz = 0, sc = 1;
                ss >> dx >> dy >> dz >> sc;
                f.offset = V3(dx, dy, dz); f.scale = sc;
                todo.emplace_back(path, "", f);
            } else {
                std::vector<std::string> only; std::string r;
                while (ss >> r) only.push_back(r);
                Bytes raw; Json j; std::string err;
                if (!readFile(path, raw) || !Json::parse(std::string(raw.begin(), raw.end()), j, &err)) { std::fprintf(stderr, "modelset: can't read %s %s\n", path.c_str(), err.c_str()); continue; }
                std::string dirOf = path.substr(0, path.find_last_of('/'));
                std::vector<std::string> cut;
                for (size_t i = 0; i < j["cut"].size(); ++i) cut.push_back(j["cut"][i].str());
                skinColours[name] = j["skinColour"].str();
                scene.setBodyCut(id, cut);
                const Json& ps = j["parts"];
                for (size_t i = 0; i < ps.size(); ++i) {
                    std::string region = ps[i]["region"].str();
                    if (!only.empty() && std::find(only.begin(), only.end(), region) == only.end()) continue;
                    BonePartFit f;
                    const Json& o = ps[i]["offset"];
                    f.offset = V3(float(o[0].num()), float(o[1].num()), float(o[2].num()));
                    f.scale = float(ps[i]["scale"].num(1));
                    todo.emplace_back(dirOf + "/" + ps[i]["file"].str(), region, f);
                }
            }
            for (auto& [file, region, f] : todo) {
                int part = scene.loadPart(file);
                int h = part ? scene.attachBonePart(id, part, f) : 0;
                if (h) modelParts[name].emplace_back(h, region);
                std::fprintf(stderr, "modelpart %d %s: %s\n", name, file.c_str(), h ? "ok" : (part ? "not a model part" : scene.partError().c_str()));
            }
        } else if (cmd == "modelfit" || cmd == "modelshow") {
            int name; std::string region; ss >> name >> region;
            if (!actors.count(name)) continue;
            BonePartFit f; float dx = 0, dy = 0, dz = 0, sc = 1; int show = 1;
            if (cmd == "modelfit") { ss >> dx >> dy >> dz >> sc; f.offset = V3(dx, dy, dz); f.scale = sc; } else ss >> show;
            for (auto& [h, r] : modelParts[name])
                if (region == "all" || region == r) {
                    if (cmd == "modelfit") scene.setBonePartFit(actors[name], h, f); else scene.setBonePartVisible(actors[name], h, show != 0);
                }
        } else if (cmd == "bodytint") {
            int name; float r = 1, g = 1, b = 1; ss >> name >> r >> g >> b;
            if (actors.count(name)) scene.setBodyTint(actors[name], r, g, b);
        } else if (cmd == "skinmatch") {
            int name; std::string hex; ss >> name >> hex;
            if (!actors.count(name)) continue;
            if (hex.empty()) hex = skinColours[name];
            const SkinLayer* base = nullptr;
            for (const SkinLayer& l : looks[name].layers) if (l.textureType == 1 && l.layer != kBodyLayer && (!base || l.layer < base->layer)) base = &l;
            Bytes blp; Image sk;
            std::vector<float> uvs = scene.faceSkinUvs(actors[name]);
            int own = base && readFile(lookup(base->fileId), blp) && decodeBlp(blp, 1024, sk) ? meanColourAt(sk.rgba.data(), sk.width, sk.height, uvs) : -1;
            if (hex.empty() || own < 0) { std::fprintf(stderr, "skinmatch %d: can't (target '%s', own %d, %zu points)\n", name, hex.c_str(), own, uvs.size() / 2); continue; }
            int want = int(std::strtol(hex.c_str(), nullptr, 16));
            float t[3];
            for (int k = 0; k < 3; ++k) {
                float o = float((own >> (16 - 8 * k)) & 255), w = float((want >> (16 - 8 * k)) & 255);
                t[k] = std::clamp(o > 1 ? w / o : 1.0f, 0.25f, 2.0f);
            }
            scene.setBodyTint(actors[name], t[0], t[1], t[2]);
            std::fprintf(stderr, "skinmatch %d: face %06X -> %06X (%zu points): tint %.2f %.2f %.2f\n", name, own, want, uvs.size() / 2, t[0], t[1], t[2]);
        } else if (cmd == "bodycut") {
            int name; ss >> name;
            std::vector<std::string> bones; std::string b;
            while (ss >> b) bones.push_back(b);
            if (actors.count(name)) scene.setBodyCut(actors[name], bones);
        } else if (cmd == "modelpose") {
            int name; ss >> name;
            for (auto& [h, r] : modelParts[name]) {
                M4 m; BonePartPlacement pl;
                if (actors.count(name) && scene.bonePartPose(actors[name], h, m, pl))
                    std::printf("modelpose %d #%d %s: bone %d source (%.3f,%.3f,%.3f) target (%.3f,%.3f,%.3f) scale %.3f\n", name, h, r.c_str(), pl.bone,
                                pl.source.x, pl.source.y, pl.source.z, pl.target.x, pl.target.y, pl.target.z, pl.scale);
                else std::printf("modelpose %d #%d %s: not drawn\n", name, h, r.c_str());
            }
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
        } else if (cmd == "fleshcontact") {
            // fleshcontact LEVEL SOFTNESS: Options' Flesh contact (Scene::setFleshContact: 0 off, 1 low, 2 high; softness 0..2) and load N (the governor's step)
            int level = 2, load = 0; float soft = 1; ss >> level >> soft; ss >> load;
            scene.setFleshContact(level, soft);
            scene.setContactLoad(load);
        } else if (cmd == "tissue") {
            // tissue LEVEL JIGGLE: Options' Soft tissue (Scene::setSoftTissue: 0 off, 1 low, 2 high; jiggle 0..2)
            int level = 2; float jig = 1; ss >> level >> jig;
            scene.setSoftTissue(level, jig);
        } else if (cmd == "spring") {
            // spring LEVEL AMOUNT PRESET: Options' spring-bone jiggle (Scene::setSpringBones: level 0 off, 1 low, 2 high; amount 0..2; preset 0 Natural, 1 ZZZ, 2 Subtle)
            int level = 2, preset = 0; float amount = 1; ss >> level >> amount >> preset;
            scene.setSpringBones(level, amount, preset);
        } else if (cmd == "tess") {
            // tess EDGESHARE CAP: the HD soft regions' tessellation (Scene::setTessellation; 0 off)
            float e = 0.0095f; int cap = 40000; ss >> e >> cap;
            scene.setTessellation(e, cap);
        } else if (cmd == "sbrun") {
            // sbrun NAME MODE AMP HZ OUT.png N STEPMS W H WARMMS: the person moves (hop, bounce, sway, stomp, walk) and N pictures are taken
            int name; std::string mode, out; float amp, hz; int n, w, h; uint32_t step, warm;
            ss >> name >> mode >> amp >> hz >> out >> n >> step >> w >> h >> warm;
            if (actors.count(name)) renderMotion(scene, actors[name], basePos[name], baseFacing[name], mode, amp, hz, out, w, h, warm, step, n);
        } else if (cmd == "sbinfo") {
            int name; ss >> name;
            int hp = 0, ch = 0; size_t vv = 0; bool sl = false;
            if (actors.count(name) && scene.springStats(actors[name], hp, ch, vv, sl)) std::printf("spring %d: %d chains, %d helpers, %zu vertices weighted%s\n", name, ch, hp, vv, sl ? ", asleep" : "");
            else std::printf("spring %d: none\n", name);
        } else if (cmd == "tinfo") {
            int name; ss >> name;
            if (actors.count(name))
                if (const TissueDraw* t = scene.tissueOf(actors[name])) {
                    std::printf("tissue %d: %d regions in the field, %d handles;", name, t->usedN, t->handles);
                    for (int r = 0; r < kTissueRegions; ++r) if (t->on[r]) std::printf(" [%d @%.3f,%.3f,%.3f u%.2f,%.2f,%.2f w%.2f,%.2f,%.2f: %d nodes %d free, max %.1f mm%s]", r, t->reg[r].frameOrigin().x, t->reg[r].frameOrigin().y, t->reg[r].frameOrigin().z, t->reg[r].frameU().x, t->reg[r].frameU().y, t->reg[r].frameU().z, t->reg[r].frameW().x, t->reg[r].frameW().y, t->reg[r].frameW().z, t->reg[r].count(), t->reg[r].freeCount(), t->reg[r].maxDisplacement() * 1000, t->reg[r].asleep() ? " asleep" : "");
                    std::printf("\n");
                }
        } else if (cmd == "tmanual") {
            // tmanual N KEY X Y Z NX NY NZ RADIUS PUSH GRIP PX PY PZ: a hand on person N's tissue (world), fed every frame; tmreset N clears them
            int name; unsigned key; float x, y, z, nx, ny, nz, r, d, g, px, py, pz;
            ss >> name >> key >> x >> y >> z >> nx >> ny >> nz >> r >> d >> g >> px >> py >> pz;
            if (actors.count(name)) {
                std::vector<TissueHandle> list;
                if (const TissueDraw* t = scene.tissueOf(actors[name])) list = t->manual;
                TissueHandle h; h.key = key; h.rest = V3(x, y, z); h.normal = V3(nx, ny, nz).normalized(); h.radius = r; h.push = d; h.grip = g; h.pull = V3(px, py, pz);
                bool found = false;
                for (auto& q : list) if (q.key == key) { q = h; found = true; }
                if (!found) list.push_back(h);
                scene.setTissueManual(actors[name], list);
            }
        } else if (cmd == "tmreset") {
            int name; ss >> name;
            if (actors.count(name)) scene.setTissueManual(actors[name], {});
        } else if (cmd == "finger") {
            // finger SIZE CHARSIZE: the player's finger size (Finger size slider as a multiple, 1 default) and Character size (1.25 is "Match the room"):
            // the touches' pad radius is 1.1 cm * SIZE * CHARSIZE / 0.9144 world units, whatever body they touch
            ss >> fingerSize >> fingerChar;
        } else if (cmd == "touch") {
            // touch N SX SY W H PRESSURE [POINTER]: a finger at the screen point presses person N's skin (Scene::pushTouch); prints the region hit
            int name, w, h, ptr = 0; float sx, sy, pr; ss >> name >> sx >> sy >> w >> h >> pr; ss >> ptr;
            if (actors.count(name)) std::printf("touch %d.%d: region %d\n", name, ptr, scene.pushTouch(actors[name], ptr, sx, sy, w, h, pr, 5000, kFingerPadM * fingerSize * fingerChar / kYardM));
        } else if (cmd == "untouch") {
            int name, ptr = -1; ss >> name; ss >> ptr;
            if (actors.count(name)) scene.releaseTouch(actors[name], ptr);
        } else if (cmd == "pinchat") {
            // pinchat N SX SY W H DIRDEG DISTCM PRESSURE: finger 0 at the screen point, finger 1 along the screen direction DIRDEG so that the two fingertips are
            // DISTCM centimetres apart on the skin (a bisection on the second's pixel offset against the world distance); prints both, the distance and the pinch
            int name, w, h; float sx, sy, dir, cm, pr; ss >> name >> sx >> sy >> w >> h >> dir >> cm >> pr;
            if (actors.count(name)) {
                const float pad = kFingerPadM * fingerSize * fingerChar / kYardM, wpm = fingerChar / kYardM;
                int r0 = scene.pushTouch(actors[name], 0, sx, sy, w, h, pr, 5000, pad);
                const ContactDraw* cd = scene.contactOf(actors[name]);
                float lo = 0, hi = 0.35f * w, best = 0;
                const float want = cm * 0.01f * wpm, c = std::cos(dir * 3.14159265f / 180), sn = std::sin(dir * 3.14159265f / 180);
                int r1 = -1;
                for (int it = 0; it < 24 && cd; ++it) {
                    float mid = 0.5f * (lo + hi);
                    r1 = scene.pushTouch(actors[name], 1, sx + c * mid, sy + sn * mid, w, h, pr, 5000, pad);
                    float d = r1 >= 0 ? (cd->touches[1].in.pos - cd->touches[0].in.pos).length() : 1e9f;
                    if (d < want) lo = mid; else hi = mid;
                    best = mid;
                }
                r1 = scene.pushTouch(actors[name], 1, sx + c * best, sy + sn * best, w, h, pr, 5000, pad);
                if (cd) std::printf("pinchat %d: regions %d %d, fingers %.2f cm apart (asked %.2f), second at %.1f px\n", name, r0, r1,
                                    (cd->touches[1].in.pos - cd->touches[0].in.pos).length() / wpm * 100, cm, best);
            }
        } else if (cmd == "ctpress") {
            // ctpress N KEY X Y Z NX NY NZ RADIUS DEPTH REGION: a press on person N fed every frame (world); ctclear N empties them
            int name, region; unsigned key; float x, y, z, nx, ny, nz, r, d;
            ss >> name >> key >> x >> y >> z >> nx >> ny >> nz >> r >> d >> region;
            if (actors.count(name)) {
                std::vector<ContactIn> list;
                if (const ContactDraw* cd = scene.contactOf(actors[name])) list = cd->manual;
                ContactIn c; c.key = key; c.pos = V3(x, y, z); c.normal = V3(nx, ny, nz).normalized(); c.radius = r; c.depth = d; c.region = region;
                bool found = false;
                for (auto& q : list) if (q.key == key) { q = c; found = true; }
                if (!found) list.push_back(c);
                scene.setContactManual(actors[name], list);
            }
        } else if (cmd == "ctreset") {
            int name; ss >> name;
            if (actors.count(name)) scene.resetContacts(actors[name]);
        } else if (cmd == "ctclear") {
            int name; ss >> name;
            if (actors.count(name)) scene.setContactManual(actors[name], {});
        } else if (cmd == "pninfo") {
            // pninfo N: the pinches drawn on person N now (ridge, dent, flush, blanch, taut) and the pairs
            int name; ss >> name;
            if (actors.count(name)) if (const ContactDraw* cd = scene.contactOf(actors[name])) {
                std::printf("pninfo %d: drawn %d (tracker pairs %d)\n", name, cd->solver.pinchCount(), cd->pinches.pairs());
                for (int i = 0; i < cd->solver.pinchCount(); ++i) {
                    const PinchOut& o = cd->solver.pinchOut()[i];
                    std::printf("  fingers %.2f cm apart, ridge %.1f mm dent %.1f mm squeeze %.2f flush %.2f blanch %.2f taut %.2f\n", (o.a - o.b).length() * 100 / (fingerChar / kYardM), o.ridge * 1000, o.dent * 1000, o.squeeze, o.flush, o.blanch, o.taut);
                }
            }
        } else if (cmd == "ctinfo") {
            // ctinfo N: the contacts drawn on person N now (count, derived this frame, each depth/bulge/flush/blanch in mm)
            int name; ss >> name;
            if (actors.count(name)) if (const ContactDraw* cd = scene.contactOf(actors[name])) {
                std::printf("ctinfo %d: drawn %d derived %d\n", name, cd->solver.count(), cd->derived);
                for (int i = 0; i < cd->solver.count(); ++i) {
                    const ContactOut& o = cd->solver.out()[i];
                    std::printf("  at %.3f %.3f %.3f n %.2f %.2f %.2f r %.3f depth %.1f mm bulge %.1f mm flush %.2f blanch %.2f\n", o.pos.x, o.pos.y, o.pos.z, o.normal.x, o.normal.y, o.normal.z, o.radius, o.depth * 1000, o.bulge * 1000, o.flush, o.blanch);
                }
            }
        } else if (cmd == "soft") {
            std::string l; ss >> l;
            scene.setSoftBody(softLevel(l));
        } else if (cmd == "bonestop") {
            int name; ss >> name;
            if (actors.count(name)) scene.clearBoneAnim(actors[name]);
        } else if (cmd == "humanscale") {
            // humanscale N TARGET|own XY: person N at human scale as SceneView.dress sizes a live NPC (anim/HumanScale.kt):
            // TARGET the sex's standing height (scale = TARGET / Scene::actorStandHeight), `own` keeps the look's scale (a dwarf,
            // a gnome); XY the imported animations' root motion across the floor (Scene::setHumanRootMotion: HumanScale.PAIR
            // times pairScale)
            int name; std::string target; float xy = 0;
            ss >> name >> target >> xy;
            if (!actors.count(name)) continue;
            int id = actors[name];
            float stand = scene.actorStandHeight(id), sc = 0;
            if (target != "own" && stand > 0.15f) { sc = std::clamp(std::strtof(target.c_str(), nullptr) / stand, 0.2f, 4.0f); scene.setActorScale(id, sc); }
            scene.setHumanRootMotion(id, xy);
            std::fprintf(stderr, "humanscale %d: stands %.3f, scale %s, root motion %.4f\n", name, stand, sc > 0 ? std::to_string(sc).c_str() : "own", xy);
        } else if (cmd == "furnish") {
            // furnish N sit|lie SX SY SZ FACING: person N on a piece of furniture (Scene::placeActor with kSitting or kLying):
            // the pelvis put on the surface point SX SY SZ, facing FACING radians (the slot's)
            int name; std::string pose; float sx, sy, sz, facing;
            ss >> name >> pose >> sx >> sy >> sz >> facing;
            if (actors.count(name))
                scene.placeActor(actors[name], V3(sx, sy, 0), facing, pose == "lie" ? Scene::kLying : Scene::kSitting, V3(sx, sy, sz));
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
        } else if (cmd == "pickbody") {
            // pickbody N SX SY W H: Scene::pickBodyDetail's numbers for the point (Interact mode's region input: code, side, height, forward, across, z, front, geoset)
            int name, w, h; float sx, sy; ss >> name >> sx >> sy >> w >> h;
            if (actors.count(name)) {
                float d[8];
                int code = scene.pickBodyDetail(actors[name], sx, sy, w, h, d);
                std::printf("pickbody %.0f,%.0f: code %d h %.3f across %.3f front %.0f z %.3f\n", sx, sy, code & 255, d[2], d[4], d[6], d[5]);
            }
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
        } else if (cmd == "gizmo") {
            std::string mode, kind; int n = 0, axis = -1;
            ss >> mode >> kind >> n >> axis;
            int id = kind == "actor" ? (actors.count(n) ? actors[n] : 0) : (props.count(n) ? props[n] : 0);
            scene.setGizmo(gizmoMode(mode), kind == "actor" ? Scene::kPickActor : Scene::kPickProp, id, axis);
        } else if (cmd == "gizmohit") {
            float sx, sy, slop = 40; int w, h;
            ss >> sx >> sy >> w >> h >> slop;
            std::fprintf(stderr, "gizmohit %.0f,%.0f: %d\n", sx, sy, scene.gizmoHit(sx, sy, w, h, slop));
        } else if (cmd == "ghost") {
            uint32_t model; float x, y, z, yaw, sc;
            ss >> model >> x >> y >> z >> yaw >> sc;
            bool ok = scene.ghostProp(model, V3(x, y, z), yaw, sc);
            V3 a, b;
            if (ok) scene.ghostBounds(a, b);
            std::fprintf(stderr, "ghost %u: %s (bounds %.2f %.2f %.2f .. %.2f %.2f %.2f)\n", model, ok ? "ok" : scene.modelError(model).c_str(), a.x, a.y, a.z, b.x, b.y, b.z);
        } else if (cmd == "ghostlook") {
            std::string path; float x, y, z, yaw;
            ss >> path >> x >> y >> z >> yaw;
            LookFile lf;
            if (!readLook(path, lf)) continue;
            int id = scene.addActor(lf.model, lf.tex, lf.geo, lf.scale);
            if (id <= 0) { std::fprintf(stderr, "ghostlook: FAILED\n"); continue; }
            scene.setCharacter(id, lf.character);
            scene.dress(id, lf.geo, lf.layers, lf.extras);
            scene.placeActor(id, V3(x, y, z), yaw);
            scene.ghostActor(id);
            scene.ghostMove(V3(x, y, z), yaw);
        } else if (cmd == "ghostmove") {
            float x, y, z, yaw;
            ss >> x >> y >> z >> yaw;
            scene.ghostMove(V3(x, y, z), yaw);
            V3 a, b;
            if (scene.ghostBounds(a, b)) std::fprintf(stderr, "ghost bounds %.2f %.2f %.2f .. %.2f %.2f %.2f\n", a.x, a.y, a.z, b.x, b.y, b.z);
        } else if (cmd == "ghostclear") {
            int actor = scene.ghostActorId();
            scene.ghostClear();
            if (actor) scene.removeActor(actor);
        } else if (cmd == "thumb") {
            std::string kind, asset, out; int size = 160;
            ss >> kind >> asset >> size >> out;
            thumbTo(scene, kind + ":" + asset, size, out);
        } else if (cmd == "card") {
            // card LOOKFILE W H LO HI AZ EL FITWIDTH CLEAR OUT.png: a Transmog card (Native.thumbCard), timed
            std::string lookPath, out; int w = 128, h = 168, fitWidth = 1, clearBg = 1; float lo, hi, az, el;
            ss >> lookPath >> w >> h >> lo >> hi >> az >> el >> fitWidth >> clearBg >> out;
            LookFile lf;
            if (!readLook(lookPath, lf)) continue;
            Scene::ThumbLook t = thumbLook(lf);
            Scene::ThumbFrame frame;
            frame.lo = lo; frame.hi = hi; frame.az = az; frame.el = el; frame.fitWidth = fitWidth != 0; frame.clear = clearBg != 0;
            std::vector<uint8_t> rgba;
            std::string err;
            auto t0 = std::chrono::steady_clock::now();
            bool ok = scene.thumbnail(0, &t, &frame, w, h, rgba, err);
            glFinish();
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            if (!ok) { std::fprintf(stderr, "card %s: FAILED: %s\n", lookPath.c_str(), err.c_str()); continue; }
            writePng(out, w, h, rgba, 4);
            std::fprintf(stderr, "card %s: wrote %s (%dx%d) in %.1f ms\n", lookPath.c_str(), out.c_str(), w, h, ms);
        } else if (cmd == "camerainfo") {
            Scene::CameraInfo c = scene.cameraInfo();
            std::fprintf(stderr, "camera eye %.2f %.2f %.2f target %.2f %.2f %.2f up %.2f %.2f %.2f fov %.1f aspect %.3f\n",
                         c.eye.x, c.eye.y, c.eye.z, c.target.x, c.target.y, c.target.z, c.up.x, c.up.y, c.up.z, c.fovY, c.aspect);
        } else if (cmd == "layer") {
            int name; std::string path; ss >> name >> path;
            BoneAnim ba;
            if (actors.count(name) && readBoneAnim(path, ba))
                std::fprintf(stderr, "layer %d: %d of %zu tracks found their bone\n", name, scene.setBoneLayer(actors[name], ba, 0), ba.tracks.size());
        } else if (cmd == "stockings") {
            // stockings N STYLE(ankle|knee|thigh|tights|off) DENIER R G B [seam] [elastic]: stockings.h on person N
            int name; std::string style; ss >> name >> style;
            if (actors.count(name)) {
                if (style == "off") scene.setStockings(actors[name], nullptr);
                else {
                    StockSpec sp; std::string w; ss >> sp.denier >> sp.r >> sp.g >> sp.b;
                    sp.style = style == "ankle" ? kStockAnkle : style == "knee" ? kStockKnee : style == "tights" ? kStockTights : style == "garter" ? kStockGarter : kStockThigh;
                    while (ss >> w) { if (w == "seam") sp.seam = true; if (w == "elastic") sp.lace = false; }
                    scene.setStockings(actors[name], &sp);
                }
            }
        } else if (cmd == "stockpull") {
            // stockpull N SX SY DX DY STEPS W H: a finger down on person N's stocking at the screen point, dragged DX DY pixels in STEPS frames
            // (each stepped 1/60 s), held; prints the tension and the runs. "stockrelease N [FRAMES]" lets go and steps; "stockundo N|all"
            int name, steps, w, h; float sx, sy, dx, dy; ss >> name >> sx >> sy >> dx >> dy >> steps >> w >> h;
            if (actors.count(name)) {
                bool ok = scene.stockGrab(actors[name], sx, sy, w, h);
                std::fprintf(stderr, "stockpull grab at %.0f,%.0f: %s\n", sx, sy, ok ? "took hold" : "nothing there");
                for (int i = 1; ok && i <= steps; ++i) { scene.stockDrag(actors[name], sx + dx * i / steps, sy + dy * i / steps, w, h); renderTo(scene, "/dev/null", w, h, 5000 + 16 * uint32_t(i)); }
                float st[4]; if (scene.stockState(actors[name], st)) std::fprintf(stderr, "stockpull tension %.2f runs %.0f dead %.0f\n", st[0], st[1], st[2]);
            }
        } else if (cmd == "stocklook") {
            // stocklook N ELASTICITY TEAR_STRAIN SHEEN SIDE: the fabric's give and look (Scene::stockLook)
            int name; float e, t, sh, sd; ss >> name >> e >> t >> sh >> sd;
            if (actors.count(name)) scene.stockLook(actors[name], e, t, sh, sd);
        } else if (cmd == "stockpullto") {
            // stockpullto N SX SY DX DY MAXSTEPS W H TENSION: as stockpull, but stops dragging once the tension reaches TENSION (the finger stays held)
            int name, steps, w, h; float sx, sy, dx, dy, goal; ss >> name >> sx >> sy >> dx >> dy >> steps >> w >> h >> goal;
            if (actors.count(name)) {
                bool ok = scene.stockGrab(actors[name], sx, sy, w, h);
                std::fprintf(stderr, "stockpullto grab at %.0f,%.0f: %s\n", sx, sy, ok ? "took hold" : "nothing there");
                float st[4] = {0, 0, 0, 0};
                int i = 1;
                for (; ok && i <= steps; ++i) {
                    scene.stockDrag(actors[name], sx + dx * i / steps, sy + dy * i / steps, w, h);
                    renderTo(scene, "/dev/null", w, h, 5000 + 16 * uint32_t(i));
                    scene.stockState(actors[name], st);
                    if (st[0] >= goal || st[1] > 0) break;
                }
                std::fprintf(stderr, "stockpullto step %d tension %.2f runs %.0f dead %.0f\n", i, st[0], st[1], st[2]);
            }
        } else if (cmd == "stockrelease") {
            static uint32_t relCount = 0;
            int name, frames = 60; ss >> name >> frames;
            if (actors.count(name)) { scene.stockRelease(actors[name]); for (int i = 0; i < frames; ++i) renderTo(scene, "/dev/null", 140, 180, 9000 + 16 * uint32_t(i) + 20000 * (++relCount)); }
        } else if (cmd == "stockundo") {
            std::string w; int name; ss >> name >> w;
            if (actors.count(name)) { if (w == "all") scene.stockRepair(actors[name]); else scene.stockUndo(actors[name]); }
        } else if (cmd == "layerstop") {
            int name; ss >> name;
            if (actors.count(name)) scene.clearBoneLayer(actors[name]);
        } else if (cmd == "reach") {
            int name, target, bone; std::string hand, path; ss >> name >> target >> bone >> hand >> path;
            BoneAnim ba;
            if (actors.count(name) && actors.count(target) && readBoneAnim(path, ba))
                std::fprintf(stderr, "reach %d: %s\n", name, scene.setReach(actors[name], actors[target], bone, hand == "L", ba, 0) ? "ok" : "arm not found");
        } else if (cmd == "reachstop") {
            int name; ss >> name;
            if (actors.count(name)) scene.clearReach(actors[name]);
        } else if (cmd == "handhead") {
            // handhead OUT.csv PERSON PMODEL TARGET TMODEL HEADBONE T0 T1 STEP_MS
            std::string out; int pn, tn, headBone; uint32_t pm, tm, t0, t1, step;
            ss >> out >> pn >> pm >> tn >> tm >> headBone >> t0 >> t1 >> step;
            if (!actors.count(pn) || !actors.count(tn) || step == 0) continue;
            static std::map<uint32_t, std::unique_ptr<M2Model>> mods;
            for (uint32_t id : {pm, tm}) { auto& mm = mods[id]; if (!mm) { mm = std::make_unique<M2Model>(); if (!mm->load(id, lookup)) std::fprintf(stderr, "handhead: %s\n", mm->error.c_str()); } }
            const M2Model& person = *mods[pm]; const M2Model& dragon = *mods[tm];
            // the target's head triangles: those whose three vertices are mostly weighted to the head bone or under it
            std::vector<uint8_t> inHead(dragon.bones.size(), 0);
            if (headBone >= 0 && size_t(headBone) < inHead.size()) inHead[size_t(headBone)] = 1;
            for (bool grew = true; grew;) { grew = false; for (size_t i = 0; i < dragon.bones.size(); ++i) { int p = dragon.bones[i].parent; if (!inHead[i] && p >= 0 && inHead[size_t(p)]) { inHead[i] = 1; grew = true; } } }
            auto headVert = [&](const M2Vertex& v) { int best = 0; for (int k = 1; k < 4; ++k) if (v.weights[k] > v.weights[best]) best = k; return inHead[v.bones[best]] && v.weights[best] >= 128; };
            std::vector<uint32_t> tris;
            for (size_t i = 0; i + 2 < dragon.indices.size(); i += 3)
                if (headVert(dragon.vertices[dragon.indices[i]]) && headVert(dragon.vertices[dragon.indices[i + 1]]) && headVert(dragon.vertices[dragon.indices[i + 2]]))
                    for (int k = 0; k < 3; ++k) tris.push_back(dragon.indices[i + k]);
            FILE* f = std::fopen(out.c_str(), "w");
            std::fprintf(f, "time_ms,weight,reachable,palm_to_aim,palm_signed,wrist_signed,fingers_signed,min_signed,palm_x,palm_y,palm_z,aim_x,aim_y,aim_z,shoulder_x,shoulder_y,shoulder_z\n");
            std::vector<double> dAim, dPalm, dMin;
            for (uint32_t t = t0; t <= t1; t += step) {
                renderTo(scene, "/dev/null", 96, 64, t);
                V3 palmW, aim; float w = 0; bool reachable = true;
                if (!scene.reachState(actors[pn], palmW, aim, w, reachable)) { std::fprintf(f, "%u,0,1,nan,nan,nan,nan,nan,0,0,0,0,0,0,0,0,0\n", t); continue; }
                std::vector<M4> dm, pmats; int seq; uint32_t at;
                scene.actorPose(actors[tn], dm, seq, at); scene.actorPose(actors[pn], pmats, seq, at);
                V3 dp, pp; float df, pf, ds, ps;
                scene.actorPlacement(actors[tn], dp, df, ds); scene.actorPlacement(actors[pn], pp, pf, ps);
                const M4 dw = M4::translation(dp) * M4::rotationZ(df) * M4::scaling(V3(ds, ds, ds));
                const M4 pw = M4::translation(pp) * M4::rotationZ(pf) * M4::scaling(V3(ps, ps, ps));
                struct SV { V3 p, n; };
                auto skin = [&](uint32_t vi) {
                    const M2Vertex& v = dragon.vertices[vi]; V3 p, n;
                    for (int k = 0; k < 4; ++k) {
                        float wt = v.weights[k] / 255.0f; if (wt <= 0 || v.bones[k] >= dm.size()) continue;
                        const M4& m4 = dm[v.bones[k]];
                        V3 q = m4.point(V3(v.pos[0], v.pos[1], v.pos[2])); p = p + q * wt;
                        V3 nn(m4.at(0, 0) * v.normal[0] + m4.at(0, 1) * v.normal[1] + m4.at(0, 2) * v.normal[2],
                              m4.at(1, 0) * v.normal[0] + m4.at(1, 1) * v.normal[1] + m4.at(1, 2) * v.normal[2],
                              m4.at(2, 0) * v.normal[0] + m4.at(2, 1) * v.normal[1] + m4.at(2, 2) * v.normal[2]);
                        n = n + nn * wt;
                    }
                    return SV{dw.point(p), M4::rotationZ(df).point(n)};
                };
                std::vector<SV> sk(dragon.vertices.size()); std::vector<uint8_t> have(dragon.vertices.size(), 0);
                for (uint32_t vi : tris) if (!have[vi]) { sk[vi] = skin(vi); have[vi] = 1; }
                auto signedDist = [&](const V3& P) {
                    float best = 1e9f, sign = 1;
                    for (size_t i = 0; i + 2 < tris.size(); i += 3) {
                        const SV &A = sk[tris[i]], &B = sk[tris[i + 1]], &C = sk[tris[i + 2]];
                        // closest point on the triangle (Ericson)
                        V3 ab = B.p - A.p, ac = C.p - A.p, ap = P - A.p;
                        float d1 = ab.dot(ap), d2 = ac.dot(ap); V3 q;
                        if (d1 <= 0 && d2 <= 0) q = A.p;
                        else {
                            V3 bp = P - B.p; float d3 = ab.dot(bp), d4 = ac.dot(bp);
                            if (d3 >= 0 && d4 <= d3) q = B.p;
                            else {
                                float vc = d1 * d4 - d3 * d2;
                                if (vc <= 0 && d1 >= 0 && d3 <= 0) q = A.p + ab * (d1 / (d1 - d3));
                                else {
                                    V3 cp = P - C.p; float d5 = ab.dot(cp), d6 = ac.dot(cp);
                                    if (d6 >= 0 && d5 <= d6) q = C.p;
                                    else {
                                        float vb = d5 * d2 - d1 * d6;
                                        if (vb <= 0 && d2 >= 0 && d6 <= 0) q = A.p + ac * (d2 / (d2 - d6));
                                        else {
                                            float va = d3 * d6 - d5 * d4;
                                            if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) q = B.p + (C.p - B.p) * ((d4 - d3) / ((d4 - d3) + (d5 - d6)));
                                            else { float den = 1.0f / (va + vb + vc); q = A.p + ab * (vb * den) + ac * (vc * den); }
                                        }
                                    }
                                }
                            }
                        }
                        float d = (P - q).length();
                        if (d < best) {
                            best = d;
                            V3 n = ab.cross(ac); V3 avg = A.n + B.n + C.n;
                            if (n.dot(avg) < 0) n = n * -1.0f;
                            sign = (P - q).dot(n) >= 0 ? 1.0f : -1.0f;
                        }
                    }
                    return best * sign;
                };
                const bool left = false; (void)left;
                int bw = person.findWowBone("R_Hand"), bf = person.findWowBone("R_Middle1");
                if (bw < 0 || bf < 0 || size_t(bf) >= pmats.size()) continue;
                V3 wrist = pw.point(pmats[size_t(bw)].point(person.bones[size_t(bw)].pivot));
                V3 fing = pw.point(pmats[size_t(bf)].point(person.bones[size_t(bf)].pivot));
                V3 palm = (wrist + fing) * 0.5f;
                float sp = signedDist(palm), sw = signedDist(wrist), sf = signedDist(fing);
                float mn = std::min(sp, std::min(sw, sf));
                float aimErr = (palmW - aim).length();
                int bs = person.findWowBone("R_UpperArm");
                V3 sh = bs >= 0 ? pw.point(pmats[size_t(bs)].point(person.bones[size_t(bs)].pivot)) : V3();
                std::fprintf(f, "%u,%.3f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n", t, w, reachable ? 1 : 0, aimErr, sp, sw, sf, mn, palm.x, palm.y, palm.z, aim.x, aim.y, aim.z, sh.x, sh.y, sh.z);
                if (w > 0.99f) { dAim.push_back(aimErr); dPalm.push_back(sp); dMin.push_back(mn); }
            }
            std::fclose(f);
            auto stats = [](const char* what, std::vector<double> v) {
                if (v.empty()) return;
                double mx = -1e9, mn = 1e9, sum = 0; for (double d : v) { mx = std::max(mx, d); mn = std::min(mn, d); sum += d; }
                std::printf("handhead %s over %zu frames at full weight: mean %.4f min %.4f max %.4f yards\n", what, v.size(), sum / v.size(), mn, mx);
            };
            stats("palm-to-aim", dAim); stats("palm-to-skull (signed)", dPalm); stats("lowest of palm/wrist/fingers (signed)", dMin);
        } else if (cmd == "bonepoints") {
            int name; ss >> name;
            float p[9];
            if (actors.count(name) && scene.actorBonePoints(actors[name], p))
                std::printf("bonepoints %d head=(%.3f,%.3f,%.3f) chest=(%.3f,%.3f,%.3f) pelvis=(%.3f,%.3f,%.3f)\n", name, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]);
            else std::printf("bonepoints %d: none\n", name);
        } else if (cmd == "bonepos") {
            // bonepos N MODEL BONE...: where each WoW-side bone's pivot is as person N was last drawn (model space)
            int name; uint32_t model; ss >> name >> model;
            static std::map<uint32_t, std::unique_ptr<M2Model>> models;
            auto& mm = models[model];
            if (!mm) { mm = std::make_unique<M2Model>(); if (!mm->load(model, lookup)) std::fprintf(stderr, "bonepos: %s\n", mm->error.c_str()); }
            std::vector<M4> mats; int seq = -1; uint32_t at = 0;
            std::string bone;
            if (actors.count(name) && scene.actorPose(actors[name], mats, seq, at)) {
                std::printf("bonepos %d seq %d t %u:", name, seq, at);
                while (ss >> bone) {
                    int b = mm->findWowBone(bone);
                    if (b < 0 || size_t(b) >= mats.size()) { std::printf(" %s=?", bone.c_str()); continue; }
                    V3 p = mats[size_t(b)].point(mm->bones[size_t(b)].pivot);
                    std::printf(" %s[%d]=(%.3f,%.3f,%.3f)", bone.c_str(), b, p.x, p.y, p.z);
                }
                std::printf("\n");
            }
        } else if (cmd == "wcprop") {
            // wcprop off | wcprop FILE GUID X Y Z YAWDEG SCALE [FILE GUID X Y Z YAWDEG SCALE ...]: Scene::loadProp and Scene::setProps
            std::string first; ss >> first;
            std::vector<uint64_t> ids;
            std::vector<float> mats;
            if (first != "off") {
                std::string file = first;
                do {
                    unsigned long long guid; float x, y, z, yaw, sc;
                    if (!(ss >> guid >> x >> y >> z >> yaw >> sc)) break;
                    std::ifstream f(file, std::ios::binary);
                    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
                    std::string err;
                    if (!scene.loadProp(guid, bytes.data(), bytes.size(), err)) { std::printf("wcprop %s: %s\n", file.c_str(), err.c_str()); continue; }
                    const float a = yaw * 3.14159265f / 180.0f, c = std::cos(a), sn = std::sin(a);
                    M4 m;   // column-major: scale, then yaw about z, then the place
                    m.m[0] = c * sc; m.m[1] = sn * sc; m.m[4] = -sn * sc; m.m[5] = c * sc; m.m[10] = sc;
                    m.m[12] = x; m.m[13] = y; m.m[14] = z;
                    ids.push_back(guid);
                    mats.insert(mats.end(), m.m, m.m + 16);
                } while (ss >> file);
            }
            scene.setProps(ids.data(), mats.data(), int(ids.size()));
        } else if (cmd == "sparkles") {
            // sparkles off | sparkles DENSITY X Y Z HALFX HALFY YAW [X Y Z ...]: interactable sparkles over spots (Scene::setSparkles)
            std::string first; ss >> first;
            std::vector<float> v;
            float density = 0;
            if (first != "off") {
                density = std::stof(first);
                float a[7];
                while (ss >> a[0] >> a[1] >> a[2] >> a[3] >> a[4] >> a[5]) { v.insert(v.end(), {a[0], a[1], a[2], a[3], a[4], a[5], 1.0f, float(v.size())}); }
            }
            scene.setSparkles(v.data(), int(v.size()) / SparkleField::kStride, density);
        } else if (cmd == "render") {
            std::string out; int w, h; uint32_t t;
            ss >> out >> w >> h >> t;
            scene.setCinematic(cine[0], cine[1], cine[2], cine[3] * letterboxBars(w, h));
            renderTo(scene, out, w, h, t);
        }
        glFinish();
    }
    report();
    return 0;
}
