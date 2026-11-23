// Measures each place's architecture against its people (README, "Character size"): the ceiling over the floor, the doorways
// (their width and height) and the furniture's heights, all in yards over the floor, from the location's own wall triangles
// (the scene's groups, in the building's frame) and the locations.json rows (`furniture`: engine-test/furniture_measure's).
//
//   scale_measure FILES_DIR LOCATIONS_JSON [--only=ID,ID]      prints one line per fact: PLACE KIND VALUE...
//
// Facts: ceiling (the median over a grid round the inside spawn, and the lowest/highest), door (an opening found by the floor
// ray fan from the spawn: its centre azimuth, width, height and the distance to it), bed/chair/table (the surface and the
// tallest part). Build: g++ -std=c++17 -O2 -I$ENGINE engine-test/scale_measure.cpp $ENGINE/*.cpp -lEGL -lGLESv2 -lz -lpthread.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <set>
#include <sstream>
#include <sys/stat.h>
#include <vector>

#define private public
#define protected public
#include "../android/app/src/main/cpp/engine/scene.h"
#undef private
#undef protected
#include "../android/app/src/main/cpp/engine/json.h"

using namespace wc;

static bool rayTriangle(const V3& o, const V3& d, const V3& a, const V3& b, const V3& c, float& t) {
    V3 e1 = b - a, e2 = c - a, p = d.cross(e2);
    float det = e1.dot(p);
    if (std::fabs(det) < 1e-12f) return false;
    float inv = 1.f / det;
    V3 s = o - a;
    float u = s.dot(p) * inv;
    if (u < 0 || u > 1) return false;
    V3 q = s.cross(e1);
    float v = d.dot(q) * inv;
    if (v < 0 || u + v > 1) return false;
    t = e2.dot(q) * inv;
    return t > 1e-4f;
}

static const Scene* gScene = nullptr;

// The first hit of the walls (every drawn triangle of the location's groups) along d (unit), within maxT; -1 when none.
static float cast(V3 o, V3 d, float maxT) {
    float best = maxT;
    bool hit = false;
    for (const auto& g : gScene->location_->groups) {
        // the group's box along the ray (slab test)
        float t0 = 0, t1 = best;
        bool in = true;
        for (int ax = 0; ax < 3 && in; ++ax) {
            float oo = ax == 0 ? o.x : ax == 1 ? o.y : o.z, dd = ax == 0 ? d.x : ax == 1 ? d.y : d.z;
            float lo = ax == 0 ? g.boundsMin.x : ax == 1 ? g.boundsMin.y : g.boundsMin.z;
            float hi = ax == 0 ? g.boundsMax.x : ax == 1 ? g.boundsMax.y : g.boundsMax.z;
            if (std::fabs(dd) < 1e-9f) { if (oo < lo || oo > hi) in = false; continue; }
            float a = (lo - oo) / dd, b = (hi - oo) / dd;
            if (a > b) std::swap(a, b);
            t0 = std::max(t0, a); t1 = std::min(t1, b);
            if (t0 > t1) in = false;
        }
        if (!in) continue;
        for (size_t i = 0; i + 8 < g.tris.size(); i += 9) {
            float t;
            if (rayTriangle(o, d, V3(g.tris[i], g.tris[i+1], g.tris[i+2]), V3(g.tris[i+3], g.tris[i+4], g.tris[i+5]),
                            V3(g.tris[i+6], g.tris[i+7], g.tris[i+8]), t) && t < best) { best = t; hit = true; }
        }
    }
    return hit ? best : -1.f;
}

static bool readText(const std::string& path, std::string& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

static float median(std::vector<float> v) {
    if (v.empty()) return NAN;
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

int main(int argc, char** argv) {
    std::vector<std::string> args;
    std::set<std::string> only;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a.rfind("--only=", 0) == 0) { std::stringstream ss(a.substr(7)); std::string x; while (std::getline(ss, x, ',')) only.insert(x); }
        else args.push_back(a);
    }
    if (args.size() < 2) { std::fprintf(stderr, "usage: scale_measure FILES_DIR LOCATIONS_JSON [--only=ID,ID]\n"); return 2; }
    const std::string dir = args[0];

    EGLDisplay dpy = EGL_NO_DISPLAY;
    auto getPlatformDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (getPlatformDisplay) dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (dpy == EGL_NO_DISPLAY) dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(dpy, nullptr, nullptr)) return 1;
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n);
    EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, n ? cfg : nullptr, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) return 1;

    FileLookup lookup = [&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 ? p : std::string();
    };
    Scene scene(lookup);
    scene.initGl();
    gScene = &scene;
    std::string text;
    if (!readText(args[1], text)) return 1;
    Json root;
    std::string err;
    if (!Json::parse(text, root, &err)) { std::fprintf(stderr, "%s\n", err.c_str()); return 1; }

    const Json& locs = root["locations"];
    for (size_t li = 0; li < locs.size(); ++li) {
        const Json& loc = locs[li];
        const std::string id = loc["id"].str();
        if (!only.empty() && !only.count(id)) continue;
        std::string view = loc["views"].kind == Json::Object && !loc["views"].o.empty() ? loc["views"].o.begin()->first : "";
        if (!scene.loadLocation(text, id, view)) { std::fprintf(stderr, "%s: %s\n", id.c_str(), scene.locationError().c_str()); continue; }
        const Json& sp = loc["spawns"]["inside"];
        V3 P(float(sp["pos"][0].num()), float(sp["pos"][1].num()), float(sp["pos"][2].num()));
        // the interior spawn of the first interior (a place with none: the first spawn)
        if (sp.kind != Json::Object) { std::printf("%s nospawn\n", id.c_str()); continue; }
        const float floorZ = P.z;
        // ceiling: rays up from 1.2 over the floor on a grid (3 yards round the spawn and each bed/chair/table)
        std::vector<float> ceil;
        auto up = [&](float x, float y) { float t = cast(V3(x, y, floorZ + 1.2f), V3(0, 0, 1), 12.f); if (t > 0) ceil.push_back(1.2f + t); return t > 0 ? 1.2f + t : NAN; };
        for (float dx = -3; dx <= 3; dx += 0.75f) for (float dy = -3; dy <= 3; dy += 0.75f) up(P.x + dx, P.y + dy);
        if (!ceil.empty()) std::printf("%s ceiling median %.2f min %.2f max %.2f (n %zu)\n", id.c_str(), median(ceil), *std::min_element(ceil.begin(), ceil.end()), *std::max_element(ceil.begin(), ceil.end()), ceil.size());
        const Json& fur = loc["furniture"];
        for (size_t i = 0; i < fur.size(); ++i) {
            const Json& f = fur[i];
            const Json& e = f["extent"];
            float cz = up(float(f["position"][0].num()), float(f["position"][1].num()));
            std::printf("%s %s surface %.3f top %.3f height %.3f ceilingOver %.2f (floor row z %.2f)\n", id.c_str(), f["object"].str().c_str(),
                        e["surfaceTop"].kind == Json::Number ? float(e["surfaceTop"].num()) : float(f["surface"].num()), float(f["surface"].num()),
                        e["height"].kind == Json::Number ? float(e["height"].num()) : 0.f, cz, f["position"][2].num());
        }
        // doorways: the floor ray fan (0.3 over the floor), the places where the distance jumps by more than 0.7 yards
        const float z0 = floorZ + 0.3f;
        const int N = 1440;   // a quarter degree
        std::vector<float> r(N);
        for (int k = 0; k < N; ++k) {
            float a = 2.f * float(M_PI) * k / N;
            float t = cast(V3(P.x, P.y, z0), V3(std::cos(a), std::sin(a), 0), 60.f);
            r[size_t(k)] = t < 0 ? 60.f : t;
        }
        for (int k = 0; k < N; ++k) {
            int k1 = (k + 1) % N;
            if (r[size_t(k1)] - r[size_t(k)] < 0.7f) continue;     // an up-jump: the left edge of an opening is at k, then the far side
            // the opening runs while the distance stays far, until a down-jump
            int e = k1; int len = 0;
            while (len < N / 3 && r[size_t((e + 1) % N)] - 0.f > r[size_t(k)] + 0.5f) { e = (e + 1) % N; ++len; }
            int e1 = (e + 1) % N;
            if (r[size_t(e)] - r[size_t(e1)] < 0.7f) continue;
            float a0 = 2.f * float(M_PI) * k / N, a1 = 2.f * float(M_PI) * e1 / N;
            // the jambs: the wall hit points left and right of the opening
            V3 l(P.x + std::cos(a0) * r[size_t(k)], P.y + std::sin(a0) * r[size_t(k)], 0), rr(P.x + std::cos(a1) * r[size_t(e1)], P.y + std::sin(a1) * r[size_t(e1)], 0);
            float width = std::hypot(rr.x - l.x, rr.y - l.y);
            if (width < 0.5f || width > 4.f) continue;
            float am = 0.5f * (a0 + a1 + (a1 < a0 ? 2.f * float(M_PI) : 0.f));
            V3 d(std::cos(am), std::sin(am), 0);
            float far = cast(V3(P.x, P.y, z0), d, 60.f); far = far < 0 ? 60.f : far;
            float wall = 0.5f * (r[size_t(k)] + r[size_t(e1)]);
            // lintel: straight up through the doorway's plane at five points across it, the lowest roof of the opening
            float top = NAN;
            for (int q = 1; q <= 5; ++q) {
                float f = q / 6.f;
                V3 m(l.x + (rr.x - l.x) * f, l.y + (rr.y - l.y) * f, 0);
                float z = 0.3f, h = NAN;
                for (int guard = 0; guard < 20; ++guard) {   // (a sill, a step or a shelf under 1.8 yards is no lintel)
                    float t = cast(V3(m.x, m.y, floorZ + z), V3(0, 0, 1), 12.f);
                    if (t < 0) break;
                    if (z + t >= 1.8f) { h = z + t; break; }
                    z += t + 0.05f;
                }
                if (!std::isnan(h) && (std::isnan(top) || h < top)) top = h;
            }
            std::printf("%s door azimuth %.1f width %.2f height %.2f distance %.2f (open to %.1f)\n", id.c_str(), am * 180.f / float(M_PI), width, top, wall, far);
        }
    }
    return 0;
}
