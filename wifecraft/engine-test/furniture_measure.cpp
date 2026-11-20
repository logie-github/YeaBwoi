// Measures every piece of furniture a location's rows name (assets/locations.json "furniture") on its real model, placed
// as the engine places it: its own box, the surface people rest on (a bed's mattress, a seat, a table's top: its height
// and its stretch), its solid parts standing higher than that surface (a bed's headboard, footboard and posts, a chair's
// back and arms), which end of a bed its headboard is at, and how much room each side has to stand in. Everything is in
// the row's own frame: x its front (a bed: toward its foot), y its left, z up from the floor it stands on (the row's
// position), yards. Writes one JSON object (tools/locations.py --furniture-extents reads it into the rows) and, with
// RENDER_DIR, a picture of each piece with what was measured drawn over it (yellow its box, green its surface, red its
// solid parts, cyan the free sides' standing lines). Desktop only (Mesa EGL, surfaceless).
//
//   furniture_measure FILES_DIR LOCATIONS_JSON OUT_JSON [RENDER_DIR] [--only=ID,ID]
//
// Build (beside engine-test/build.sh's): g++ -std=c++17 -O2 -I$ENGINE engine-test/furniture_measure.cpp $ENGINE/*.cpp -lEGL -lGLESv2 -lz
//   -o engine-test/build/furniture_measure (ENGINE: android/app/src/main/cpp/engine). FM_DEBUG=1 prints each piece's middle
// line of rest heights. Then: python3 tools/locations.py --furniture-extents=OUT_JSON.
//
// The piece's model: the first model of the location's interaction for the row's object, found among the location's
// placed doodads by file id nearest the interaction's position (as engine-test/audit's `solid doodad` finds it).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <zlib.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <iterator>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>

// reads the scene's own placed doodads and groups
#define private public
#define protected public
#include "../android/app/src/main/cpp/engine/scene.h"
#undef private
#undef protected
#include "../android/app/src/main/cpp/engine/json.h"

using namespace wc;

static bool readText(const std::string& path, std::string& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

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

static bool hitTri(const V3& o, const V3& d, const V3& a, const V3& b, const V3& c, float& t) {
    V3 e1 = b - a, e2 = c - a, p = d.cross(e2);
    float det = e1.dot(p);
    if (std::fabs(det) < 1e-12f) return false;
    float inv = 1.0f / det;
    V3 s = o - a;
    float u = s.dot(p) * inv;
    if (u < 0 || u > 1) return false;
    V3 q = s.cross(e1);
    float v = d.dot(q) * inv;
    if (v < 0 || u + v > 1) return false;
    t = e2.dot(q) * inv;
    return t > 1e-4f;
}

static void modelTris(const M2Model& m, const M4& world, std::vector<V3>& out) {
    for (size_t k = 0; k + 2 < m.indices.size(); k += 3)
        for (int c = 0; c < 3; ++c) {
            const M2Vertex& v = m.vertices[m.indices[k + size_t(c)]];
            out.push_back(world.point(V3(v.pos[0], v.pos[1], v.pos[2])));
        }
}

// ---- the piece's height field (its own frame): per cell the solid stretches of its vertical line, lowest first, from
// its triangles (a stretch from an underside, a triangle facing down, to a top facing up; a top with no underside under it
// is solid down to the floor: a bed's frame, a seat's base), as engine-test/audit's Solid.
struct Field {
    float x0 = 0, y0 = 0, cell = 0.025f;
    int nx = 0, ny = 0;
    std::vector<std::vector<std::pair<float, float>>> spans;
    const std::vector<std::pair<float, float>>& at(int i, int j) const { return spans[size_t(j) * nx + i]; }
    float cx(int i) const { return x0 + (i + 0.5f) * cell; }
    float cy(int j) const { return y0 + (j + 0.5f) * cell; }
    // what a body coming down onto the line meets: the top of its highest stretch no higher than kReach over the floor (the
    // mattress under a canopy, a table's top over its legs' braces), NaN when there is none
    static constexpr float kReach = 1.6f;
    float restTop(int i, int j) const {
        float t = NAN;
        for (const auto& s : at(i, j)) if (s.second <= kReach) t = s.second;
        return t;
    }
    // the top of a stretch standing up from below `from` and reaching over `over` (a post, a headboard, a chair's back:
    // not a canopy hung over the surface), NaN when there is none
    float standingTop(int i, int j, float from, float over) const {
        float t = NAN;
        for (const auto& s : at(i, j)) if (s.first <= from && s.second >= over) t = std::isfinite(t) ? std::max(t, s.second) : s.second;
        return t;
    }
};

static Field makeField(const std::vector<V3>& tris, V3 lo, V3 hi) {
    Field s;
    s.x0 = lo.x; s.y0 = lo.y;
    s.nx = std::max(1, int(std::ceil((hi.x - lo.x) / s.cell))); s.ny = std::max(1, int(std::ceil((hi.y - lo.y) / s.cell)));
    std::vector<std::vector<std::pair<float, int>>> hits(size_t(s.nx) * s.ny);
    for (size_t t = 0; t + 2 < tris.size(); t += 3) {
        const V3 &A = tris[t], &B = tris[t + 1], &C = tris[t + 2];
        V3 nrm = (B - A).cross(C - A);
        float nl = nrm.length();
        if (nl < 1e-10f || std::fabs(nrm.z) < 0.15f * nl) continue;
        int dir = nrm.z > 0 ? 1 : -1;
        int i0 = std::max(0, int(std::floor((std::min({A.x, B.x, C.x}) - s.x0) / s.cell)));
        int i1 = std::min(s.nx - 1, int(std::floor((std::max({A.x, B.x, C.x}) - s.x0) / s.cell)));
        int j0 = std::max(0, int(std::floor((std::min({A.y, B.y, C.y}) - s.y0) / s.cell)));
        int j1 = std::min(s.ny - 1, int(std::floor((std::max({A.y, B.y, C.y}) - s.y0) / s.cell)));
        float den = (B.y - C.y) * (A.x - C.x) + (C.x - B.x) * (A.y - C.y);
        if (std::fabs(den) < 1e-12f) continue;
        for (int j = j0; j <= j1; ++j) for (int i = i0; i <= i1; ++i) {
            float px = s.cx(i), py = s.cy(j);
            float u = ((B.y - C.y) * (px - C.x) + (C.x - B.x) * (py - C.y)) / den;
            float v = ((C.y - A.y) * (px - C.x) + (A.x - C.x) * (py - C.y)) / den;
            float w = 1 - u - v;
            if (u < -1e-4f || v < -1e-4f || w < -1e-4f) continue;
            float z = u * A.z + v * B.z + w * C.z;
            if (z < 0.02f) continue;   // the floor under it (a rug, a base plate) isn't the piece
            hits[size_t(j) * s.nx + i].push_back({z, dir});
        }
    }
    s.spans.resize(hits.size());
    for (size_t k = 0; k < hits.size(); ++k) {
        auto& h = hits[k];
        if (h.empty()) continue;
        std::sort(h.begin(), h.end());
        bool in = false, lastTop = false; float start = 0, last = 0;
        for (auto& [z, d] : h) {
            if (d < 0) { if (!in) { in = true; start = z; } lastTop = false; }
            else {
                float b = in ? start : last;
                // a top with no underside far over another top (a canopy's single sheet over the mattress) is a sheet, not
                // solid down to the mattress
                if (!in && lastTop && z - last > 0.6f) b = z - 0.06f;
                if (z - b < 0.06f) b = z - 0.06f;
                lastTop = true;
                if (!s.spans[k].empty() && b <= s.spans[k].back().second + 0.01f) s.spans[k].back().second = std::max(z, s.spans[k].back().second);
                else s.spans[k].push_back({b, z});
                in = false;
            }
            last = z;
        }
    }
    return s;
}

struct Rect { float x0 = 0, x1 = 0, y0 = 0, y1 = 0, top = 0; };

static float percentile(std::vector<float> v, float q) {
    if (v.empty()) return NAN;
    std::sort(v.begin(), v.end());
    size_t k = std::min(v.size() - 1, size_t(q * (v.size() - 1) + 0.5f));
    return v[k];
}

struct Measured {
    bool ok = false;
    std::string why;
    Rect box;                 // the whole piece (height: its tallest part over the floor)
    Rect surface;             // the surface people rest on (top: its height at its middle)
    std::vector<Rect> parts;  // solid parts standing higher than the surface by more than a hand
    std::vector<Rect> rises;  // rises of the surface itself (a bolster, a cushion's back): lain on, drawn in blue
    int head = 0;             // a bed's headboard end along x: -1, +1, 0 when the parts don't say
    float sides[4] = {0, 0, 0, 0};   // free room (yards, at most 1.2) in front (+x), behind (-x), left (+y), right (-y)
    size_t triangles = 0;
    float rowSurface = 0;
};

static const char* kSideNames[4] = {"front", "back", "left", "right"};

int main(int argc, char** argv) {
    std::vector<std::string> args;
    std::set<std::string> only;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a.rfind("--only=", 0) == 0) { std::stringstream ss(a.substr(7)); std::string x; while (std::getline(ss, x, ',')) only.insert(x); }
        else args.push_back(a);
    }
    if (args.size() < 3) { std::fprintf(stderr, "usage: furniture_measure FILES_DIR LOCATIONS_JSON OUT_JSON [RENDER_DIR] [--only=ID,ID]\n"); return 2; }
    const std::string dir = args[0], jsonPath = args[1], outPath = args[2], renderDir = args.size() > 3 ? args[3] : "";

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

    FileLookup lookup = [&](uint32_t id) {
        std::string p = dir + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 ? p : std::string();
    };
    Scene scene(lookup);
    scene.initGl();
    scene.setSoftBody(kSoftOff);

    std::string text;
    if (!readText(jsonPath, text)) { std::fprintf(stderr, "no file %s\n", jsonPath.c_str()); return 1; }
    Json root;
    std::string err;
    if (!Json::parse(text, root, &err)) { std::fprintf(stderr, "%s: %s\n", jsonPath.c_str(), err.c_str()); return 1; }

    unsigned fbo = 0;
    const int W = 960, H = 720;
    if (!renderDir.empty()) {
        unsigned color, depth;
        glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glGenRenderbuffers(1, &color); glBindRenderbuffer(GL_RENDERBUFFER, color);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, W, H);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
        glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        glViewport(0, 0, W, H);
    }

    FILE* out = std::fopen(outPath.c_str(), "w");
    if (!out) { std::fprintf(stderr, "can't write %s\n", outPath.c_str()); return 1; }
    std::fprintf(out, "{\n \"comment\": \"engine-test/furniture_measure: each furniture row measured on its model, in the row's frame (x its front, y its left, z up from its floor; yards)\",\n \"locations\": {");
    bool firstLoc = true;

    const Json& locs = root["locations"];
    for (size_t li = 0; li < locs.size(); ++li) {
        const Json& loc = locs[li];
        const std::string id = loc["id"].str();
        if (!only.empty() && !only.count(id)) continue;
        const Json& rows = loc["furniture"];
        if (rows.size() == 0) continue;
        std::string view = loc["views"].kind == Json::Object && !loc["views"].o.empty() ? loc["views"].o.begin()->first : "";
        if (!scene.loadLocation(text, id, view)) { std::fprintf(stderr, "%s: %s\n", id.c_str(), scene.locationError().c_str()); continue; }
        std::fprintf(out, "%s\n  \"%s\": [", firstLoc ? "" : ",", id.c_str());
        firstLoc = false;
        for (size_t ri = 0; ri < rows.size(); ++ri) {
            const Json& row = rows[ri];
            const std::string object = row["object"].str();
            const V3 P(float(row["position"][0].num()), float(row["position"][1].num()), float(row["position"][2].num()));
            const float yaw = float(row["yaw"].num());
            Measured m;
            m.rowSurface = float(row["surface"].num());
            // the row's model: its interaction's first model
            uint32_t model = 0; V3 near;
            const Json& inter = loc["interactions"];
            for (size_t k = 0; k < inter.size(); ++k)
                if (inter[k]["object"].str() == object && inter[k]["models"].size()) {
                    const Json& md = inter[k]["models"][0];
                    model = uint32_t(md["id"].num());
                    near = V3(float(md["position"][0].num()), float(md["position"][1].num()), float(md["position"][2].num()));
                    break;
                }
            const Scene::Instance* hit = nullptr;
            float best = 1e9f;
            auto consider = [&](const std::vector<Scene::Instance>& list) {
                for (const auto& in : list) {
                    if (in.modelFileId != model || !in.mesh || !in.mesh->model) continue;
                    float d = (in.world.point(V3()) - near).length();
                    if (d < best) { best = d; hit = &in; }
                }
            };
            if (scene.location_) consider(scene.location_->doodads);
            consider(scene.locExtras_);
            std::vector<V3> tris;
            if (hit && best < 0.5f) modelTris(*hit->mesh->model, hit->world, tris);
            const float c = std::cos(yaw), s = std::sin(yaw);
            auto toLocal = [&](V3 p) { V3 d = p - P; return V3(d.x * c + d.y * s, -d.x * s + d.y * c, d.z); };
            auto toWorld = [&](float x, float y, float z) { return V3(P.x + x * c - y * s, P.y + x * s + y * c, P.z + z); };
            if (tris.empty()) m.why = "model " + std::to_string(model) + " not found near its interaction (" + std::to_string(best) + ")";
            else {
                m.triangles = tris.size() / 3;
                std::vector<V3> local;
                local.reserve(tris.size());
                for (const V3& p : tris) local.push_back(toLocal(p));
                // the box of what stands over the floor (a rug or base plate flush with it is not the piece)
                V3 lo(1e9f, 1e9f, 1e9f), hi(-1e9f, -1e9f, -1e9f);
                for (const V3& p : local) {
                    if (p.z < 0.02f) continue;
                    lo = V3(std::min(lo.x, p.x), std::min(lo.y, p.y), std::min(lo.z, p.z));
                    hi = V3(std::max(hi.x, p.x), std::max(hi.y, p.y), std::max(hi.z, p.z));
                }
                Field f = makeField(local, lo, hi);
                // its footprint: the cells with something in reach over the floor (a canopy's drapes high over the room
                // are not in the way of someone standing beside it); its height: its tallest part
                {
                    float x0 = 1e9f, x1 = -1e9f, y0 = 1e9f, y1 = -1e9f;
                    for (int j = 0; j < f.ny; ++j) for (int i = 0; i < f.nx; ++i) {
                        const auto& v = f.at(i, j);
                        if (v.empty() || v.front().first > Field::kReach) continue;
                        x0 = std::min(x0, f.cx(i) - f.cell / 2); x1 = std::max(x1, f.cx(i) + f.cell / 2);
                        y0 = std::min(y0, f.cy(j) - f.cell / 2); y1 = std::max(y1, f.cy(j) + f.cell / 2);
                    }
                    if (x0 > x1) { x0 = lo.x; x1 = hi.x; y0 = lo.y; y1 = hi.y; }
                    m.box = {x0, x1, y0, y1, hi.z};
                }
                // the surface's height: the lowest tops over the middle third of the box (the mattress under a canopy, the
                // seat, the table's top), their median; the row's own (the lying pose, the measured seat) when that is near
                std::vector<float> mids;
                for (int j = 0; j < f.ny; ++j) for (int i = 0; i < f.nx; ++i) {
                    float x = f.cx(i), y = f.cy(j);
                    if (std::fabs(x - (lo.x + hi.x) / 2) > (hi.x - lo.x) / 6 || std::fabs(y - (lo.y + hi.y) / 2) > (hi.y - lo.y) / 6) continue;
                    float t = f.restTop(i, j);
                    if (std::isfinite(t) && t > 0.15f) mids.push_back(t);
                }
                float guess = percentile(mids, 0.5f);
                if (std::getenv("FM_DEBUG")) {
                    int j = f.ny / 2;
                    std::fprintf(stderr, "debug %s %s row j=%d:", id.c_str(), object.c_str(), j);
                    for (int i = 0; i < f.nx; i += 2) std::fprintf(stderr, " %.3f", f.restTop(i, j));
                    std::fprintf(stderr, "\n");
                }
                if (!std::isfinite(guess)) { m.why = "no surface over its middle"; }
                else {
                    // the surface: grown from the cell nearest the middle at that height to its neighbours of about the same
                    // height (a step of at most 4 cm, within 15 cm of the middle's: a pillow, not a post or a headboard)
                    std::vector<char> in(size_t(f.nx) * f.ny, 0);
                    int si = -1, sj = -1; float sd = 1e9f;
                    for (int j = 0; j < f.ny; ++j) for (int i = 0; i < f.nx; ++i) {
                        float t = f.restTop(i, j);
                        if (!std::isfinite(t) || std::fabs(t - guess) > 0.03f) continue;
                        float d = std::hypot(f.cx(i) - (lo.x + hi.x) / 2, f.cy(j) - (lo.y + hi.y) / 2);
                        if (d < sd) { sd = d; si = i; sj = j; }
                    }
                    std::vector<std::pair<int, int>> stack{{si, sj}};
                    in[size_t(sj) * f.nx + si] = 1;
                    std::vector<float> xs, ys, tops;
                    while (!stack.empty()) {
                        auto [i, j] = stack.back(); stack.pop_back();
                        float t = f.restTop(i, j);
                        xs.push_back(f.cx(i)); ys.push_back(f.cy(j)); tops.push_back(t);
                        const int nb[4][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        for (auto& d : nb) {
                            int a = i + d[0], b = j + d[1];
                            if (a < 0 || b < 0 || a >= f.nx || b >= f.ny || in[size_t(b) * f.nx + a]) continue;
                            float u = f.restTop(a, b);
                            if (!std::isfinite(u) || std::fabs(u - t) > 0.04f || std::fabs(u - guess) > 0.15f) continue;
                            in[size_t(b) * f.nx + a] = 1;
                            stack.push_back({a, b});
                        }
                    }
                    m.surface = {percentile(xs, 0.01f) - f.cell / 2, percentile(xs, 0.99f) + f.cell / 2,
                                 percentile(ys, 0.01f) - f.cell / 2, percentile(ys, 0.99f) + f.cell / 2, guess};
                    // solid parts: cells off the surface with a stretch standing up from under it to more than a hand (0.12) over it, in
                    // connected groups (8 neighbours) of at least 6 cells, each as its box
                    std::vector<int> label(size_t(f.nx) * f.ny, -1);
                    std::vector<std::pair<Rect, int>> groups;
                    for (int j = 0; j < f.ny; ++j) for (int i = 0; i < f.nx; ++i) {
                        size_t k = size_t(j) * f.nx + i;
                        float t = f.standingTop(i, j, guess + 0.3f, guess + 0.12f);
                        if (in[k] || label[k] >= 0 || !std::isfinite(t)) continue;
                        Rect r{1e9f, -1e9f, 1e9f, -1e9f, 0};
                        int count = 0;
                        std::vector<std::pair<int, int>> st{{i, j}};
                        label[k] = int(groups.size());
                        while (!st.empty()) {
                            auto [a, b] = st.back(); st.pop_back();
                            ++count;
                            r.x0 = std::min(r.x0, f.cx(a) - f.cell / 2); r.x1 = std::max(r.x1, f.cx(a) + f.cell / 2);
                            r.y0 = std::min(r.y0, f.cy(b) - f.cell / 2); r.y1 = std::max(r.y1, f.cy(b) + f.cell / 2);
                            r.top = std::max(r.top, f.standingTop(a, b, guess + 0.3f, guess + 0.12f));
                            for (int db = -1; db <= 1; ++db) for (int da = -1; da <= 1; ++da) {
                                int a2 = a + da, b2 = b + db;
                                if (a2 < 0 || b2 < 0 || a2 >= f.nx || b2 >= f.ny) continue;
                                size_t k2 = size_t(b2) * f.nx + a2;
                                float t2 = f.standingTop(a2, b2, guess + 0.3f, guess + 0.12f);
                                if (in[k2] || label[k2] >= 0 || !std::isfinite(t2)) continue;
                                label[k2] = label[k];
                                st.push_back({a2, b2});
                            }
                        }
                        groups.push_back({r, count});
                    }
                    std::sort(groups.begin(), groups.end(), [](auto& a, auto& b) { return a.second > b.second; });
                    // a rise in the surface itself (its middle over the surface, under half a yard over it: a bolster, a
                    // domed cushion's back, a hammock's sides) is lain on, not a part; a taller part reaching into the
                    // surface's box (a headboard, a footboard) cuts the surface back to its edge, across its narrower way
                    std::vector<Rect> rises;
                    for (auto& [r, cnt] : groups) {
                        if (cnt < 6) continue;
                        Rect& sf = m.surface;
                        float cxr = (r.x0 + r.x1) / 2, cyr = (r.y0 + r.y1) / 2;
                        bool overSurface = cxr > sf.x0 && cxr < sf.x1 && cyr > sf.y0 && cyr < sf.y1;
                        if (overSurface && r.top < guess + 0.5f) { rises.push_back(r); continue; }
                        float ox = std::min(r.x1, sf.x1) - std::max(r.x0, sf.x0), oy = std::min(r.y1, sf.y1) - std::max(r.y0, sf.y0);
                        if (ox > 0 && oy > 0) {
                            if (oy >= 0.6f * (sf.y1 - sf.y0)) { if (cxr < (sf.x0 + sf.x1) / 2) sf.x0 = std::max(sf.x0, r.x1); else sf.x1 = std::min(sf.x1, r.x0); }
                            else if (ox >= 0.6f * (sf.x1 - sf.x0)) { if (cyr < (sf.y0 + sf.y1) / 2) sf.y0 = std::max(sf.y0, r.y1); else sf.y1 = std::min(sf.y1, r.y0); }
                        }
                        if (m.parts.size() < 10) m.parts.push_back(r);
                    }
                    m.rises = rises;
                    // a bed's head: the end whose parts stand taller over more of its width
                    if (object == "BED") {
                        float w[2] = {0, 0};
                        float mid = (m.surface.x0 + m.surface.x1) / 2;
                        for (const Rect& r : m.parts) {
                            float cxr = (r.x0 + r.x1) / 2;
                            w[cxr > mid ? 1 : 0] += (r.y1 - r.y0) * (r.top - guess);
                        }
                        for (const Rect& r : m.rises) {
                            float cxr = (r.x0 + r.x1) / 2;
                            if (std::fabs(cxr - mid) > 0.15f * (m.surface.x1 - m.surface.x0)) w[cxr > mid ? 1 : 0] += (r.y1 - r.y0) * (r.top - guess);
                        }
                        if (w[0] > 1.3f * w[1] && w[0] > 0.02f) m.head = -1;
                        else if (w[1] > 1.3f * w[0] && w[1] > 0.02f) m.head = 1;
                    }
                    // the free room on each side: rays out from just past the box, at shin and chest height, at three places
                    // along the side, against the building and the other pieces of the room; the floor 0.45 out must be
                    // the piece's own floor (within 0.25)
                    std::vector<V3> room;
                    if (scene.location_)
                        for (const auto& g : scene.location_->groups) {
                            if (g.boundsMax.x < P.x - 4 || g.boundsMin.x > P.x + 4 || g.boundsMax.y < P.y - 4 || g.boundsMin.y > P.y + 4) continue;
                            for (size_t q = 0; q + 8 < g.tris.size(); q += 9) {
                                V3 a(g.tris[q], g.tris[q + 1], g.tris[q + 2]), b(g.tris[q + 3], g.tris[q + 4], g.tris[q + 5]), cc(g.tris[q + 6], g.tris[q + 7], g.tris[q + 8]);
                                if (std::min({a.x, b.x, cc.x}) > P.x + 4 || std::max({a.x, b.x, cc.x}) < P.x - 4 || std::min({a.y, b.y, cc.y}) > P.y + 4 || std::max({a.y, b.y, cc.y}) < P.y - 4) continue;
                                room.push_back(a); room.push_back(b); room.push_back(cc);
                            }
                        }
                    auto others = [&](const std::vector<Scene::Instance>& list) {
                        for (const auto& in2 : list) {
                            if (&in2 == hit || !in2.mesh || !in2.mesh->model) continue;
                            if ((in2.centre - P).length() > 4 + in2.radius) continue;
                            modelTris(*in2.mesh->model, in2.world, room);
                        }
                    };
                    if (scene.location_) others(scene.location_->doodads);
                    others(scene.locExtras_);
                    for (int side = 0; side < 4; ++side) {
                        std::vector<float> free;
                        for (float along : {0.2f, 0.5f, 0.8f}) {
                            float lx, ly, dx, dy;
                            const Rect& b = m.box;
                            if (side < 2) { lx = side == 0 ? b.x1 + 0.02f : b.x0 - 0.02f; ly = b.y0 + (b.y1 - b.y0) * along; dx = side == 0 ? 1.f : -1.f; dy = 0; }
                            else { ly = side == 2 ? b.y1 + 0.02f : b.y0 - 0.02f; lx = b.x0 + (b.x1 - b.x0) * along; dx = 0; dy = side == 2 ? 1.f : -1.f; }
                            float room1 = 1.2f;
                            for (float hz : {0.3f, 1.0f}) {
                                V3 o = toWorld(lx, ly, hz);
                                V3 d = toWorld(lx + dx, ly + dy, hz) - o;
                                for (size_t q = 0; q + 2 < room.size(); q += 3) {
                                    float t;
                                    if (hitTri(o, d, room[q], room[q + 1], room[q + 2], t) && t < room1) room1 = t;
                                }
                            }
                            V3 stand = toWorld(lx + dx * 0.45f, ly + dy * 0.45f, 0.5f);
                            float g;
                            if (!scene.groundAt(stand.x, stand.y, stand.z, g) || std::fabs(g - P.z) > 0.25f) room1 = std::min(room1, 0.3f);
                            free.push_back(room1);
                        }
                        m.sides[side] = percentile(free, 0.5f);
                    }
                    m.ok = true;
                }
            }
            // the row out
            std::fprintf(out, "%s\n   {\"object\": \"%s\", \"model\": %u, \"triangles\": %zu, \"rowSurface\": %.3f", ri ? "," : "", object.c_str(), model, m.triangles, m.rowSurface);
            if (!m.ok) std::fprintf(out, ", \"error\": \"%s\"}", m.why.c_str());
            else {
                std::fprintf(out, ", \"box\": [%.3f, %.3f, %.3f, %.3f], \"height\": %.3f, \"surfaceRect\": [%.3f, %.3f, %.3f, %.3f], \"surfaceTop\": %.3f, \"head\": %d, \"parts\": [",
                             m.box.x0, m.box.x1, m.box.y0, m.box.y1, m.box.top, m.surface.x0, m.surface.x1, m.surface.y0, m.surface.y1, m.surface.top, m.head);
                for (size_t k = 0; k < m.parts.size(); ++k)
                    std::fprintf(out, "%s[%.3f, %.3f, %.3f, %.3f, %.3f]", k ? ", " : "", m.parts[k].x0, m.parts[k].x1, m.parts[k].y0, m.parts[k].y1, m.parts[k].top);
                std::fprintf(out, "], \"rises\": [");
                for (size_t k = 0; k < m.rises.size(); ++k)
                    std::fprintf(out, "%s[%.3f, %.3f, %.3f, %.3f, %.3f]", k ? ", " : "", m.rises[k].x0, m.rises[k].x1, m.rises[k].y0, m.rises[k].y1, m.rises[k].top);
                std::fprintf(out, "], \"sides\": {");
                for (int k = 0; k < 4; ++k) std::fprintf(out, "%s\"%s\": %.2f", k ? ", " : "", kSideNames[k], m.sides[k]);
                std::fprintf(out, "}}");
            }
            std::fflush(out);
            std::fprintf(stderr, "%s %s: %s\n", id.c_str(), object.c_str(), m.ok ? "measured" : m.why.c_str());

            // the picture: from the front-left, above, with what was measured drawn over it
            if (!renderDir.empty() && m.ok) {
                for (int pic = 0; pic < 2; ++pic) {
                    float r = std::max({m.box.x1 - m.box.x0, m.box.y1 - m.box.y0, m.box.top}) * 0.5f + 0.4f;
                    float ax = (m.box.x0 + m.box.x1) / 2, ay = (m.box.y0 + m.box.y1) / 2;
                    V3 target = toWorld(ax, ay, m.surface.top * 0.8f);
                    // 0: three-quarter from the front-left; 1: from the side (left), lower
                    float ang = pic == 0 ? 0.75f : 1.5708f, el = pic == 0 ? 0.75f : 0.35f, dist = r / std::sin(0.4363f) * 1.05f;
                    V3 eye = toWorld(ax + std::cos(ang) * std::cos(el) * dist, ay + std::sin(ang) * std::cos(el) * dist, m.surface.top * 0.8f + std::sin(el) * dist);
                    scene.setCamera(eye, target, 50);
                    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                    glViewport(0, 0, W, H);
                    for (int k = 0; k < 3; ++k) scene.render(W, H, 1000 + 100 * k);
                    std::vector<uint8_t> px(size_t(W) * H * 4), rgb(size_t(W) * H * 3);
                    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
                    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) for (int k = 0; k < 3; ++k)
                        rgb[(size_t(H - 1 - y) * W + x) * 3 + k] = px[(size_t(y) * W + x) * 4 + k];
                    const M4 vp = scene.proj_ * scene.view_;
                    auto project = [&](V3 p, float& sx, float& sy) {
                        float X = vp.at(0, 0) * p.x + vp.at(0, 1) * p.y + vp.at(0, 2) * p.z + vp.at(0, 3);
                        float Y = vp.at(1, 0) * p.x + vp.at(1, 1) * p.y + vp.at(1, 2) * p.z + vp.at(1, 3);
                        float Wc = vp.at(3, 0) * p.x + vp.at(3, 1) * p.y + vp.at(3, 2) * p.z + vp.at(3, 3);
                        if (Wc < 0.05f) return false;
                        sx = (X / Wc * 0.5f + 0.5f) * W; sy = (1 - (Y / Wc * 0.5f + 0.5f)) * H;
                        return true;
                    };
                    auto line = [&](V3 a, V3 b, uint8_t R, uint8_t G, uint8_t B) {
                        float ax2, ay2, bx2, by2;
                        if (!project(a, ax2, ay2) || !project(b, bx2, by2)) return;
                        int steps = int(std::max(std::fabs(bx2 - ax2), std::fabs(by2 - ay2))) + 1;
                        for (int k = 0; k <= steps; ++k) {
                            float t = float(k) / steps;
                            int x = int(ax2 + (bx2 - ax2) * t), y = int(ay2 + (by2 - ay2) * t);
                            for (int oy = 0; oy < 2; ++oy) for (int ox = 0; ox < 2; ++ox) {
                                int xx = x + ox, yy = y + oy;
                                if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                                uint8_t* q = &rgb[(size_t(yy) * W + xx) * 3];
                                q[0] = R; q[1] = G; q[2] = B;
                            }
                        }
                    };
                    auto box = [&](const Rect& b, float z0, uint8_t R, uint8_t G, uint8_t B) {
                        V3 c8[8];
                        for (int k = 0; k < 8; ++k) c8[k] = toWorld(k & 1 ? b.x1 : b.x0, k & 2 ? b.y1 : b.y0, k & 4 ? b.top : z0);
                        const int e[12][2] = {{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
                        for (auto& ed : e) line(c8[ed[0]], c8[ed[1]], R, G, B);
                    };
                    box(m.box, 0, 255, 230, 0);
                    Rect sr = m.surface;
                    box(sr, sr.top, 40, 255, 60);
                    for (const Rect& pr : m.parts) box(pr, m.surface.top, 255, 40, 40);
                    for (const Rect& pr : m.rises) box(pr, m.surface.top, 60, 120, 255);
                    // the front's arrow (x) at the surface, and each free side's standing line 0.45 out
                    line(toWorld(ax, ay, m.surface.top), toWorld(m.box.x1 + 0.3f, ay, m.surface.top), 255, 255, 255);
                    for (int side = 0; side < 4; ++side) {
                        if (m.sides[side] < 0.45f) continue;
                        V3 a, b;
                        if (side == 0) { a = toWorld(m.box.x1 + 0.45f, m.box.y0, 0.01f); b = toWorld(m.box.x1 + 0.45f, m.box.y1, 0.01f); }
                        else if (side == 1) { a = toWorld(m.box.x0 - 0.45f, m.box.y0, 0.01f); b = toWorld(m.box.x0 - 0.45f, m.box.y1, 0.01f); }
                        else if (side == 2) { a = toWorld(m.box.x0, m.box.y1 + 0.45f, 0.01f); b = toWorld(m.box.x1, m.box.y1 + 0.45f, 0.01f); }
                        else { a = toWorld(m.box.x0, m.box.y0 - 0.45f, 0.01f); b = toWorld(m.box.x1, m.box.y0 - 0.45f, 0.01f); }
                        line(a, b, 0, 220, 255);
                    }
                    writePng(renderDir + "/" + id + "_" + object + (pic ? "_side" : "_front") + ".png", W, H, rgb);
                }
            }
        }
        std::fprintf(out, "\n  ]");
    }
    std::fprintf(out, "\n }\n}\n");
    std::fclose(out);
    return 0;
}
