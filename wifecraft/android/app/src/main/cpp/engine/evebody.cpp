#include "evebody.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <unordered_map>

#include <zlib.h>

#include "blp.h"
#include "bodypart.h"
#include "bytes.h"
#include "json.h"
#include "modelpart.h"

namespace wc {

// ================================================================================================ the .weve file

EveMesh eveSplitMesh(const EveMesh& m, float t) {
    EveMesh o = m;
    char tag[32];
    std::snprintf(tag, sizeof tag, "|split%.3f", t);
    o.variant = m.variant + tag;
    V3 hip, knee;
    if (!m.joint("hip", hip) || !m.joint("knee", knee)) return o;
    const size_t nv = m.vertexCount();
    // each vertex: below the cut on its leg (mostly weighted to a leg's bones, past t along that leg's hip -> knee)
    std::vector<int> legBone(m.bones.size(), -1);
    for (size_t b = 0; b < m.bones.size(); ++b) {
        const std::string& n = m.bones[b];
        if (n == "L_Thigh" || n == "L_Calf" || n == "L_Foot" || n == "L_Toe") legBone[b] = 0;
        if (n == "R_Thigh" || n == "R_Calf" || n == "R_Foot" || n == "R_Toe") legBone[b] = 1;
    }
    std::vector<uint8_t> below(nv, 0);
    std::vector<float> along(nv, 0);
    std::vector<int8_t> side(nv, -1);
    for (size_t v = 0; v < nv; ++v) {
        float w[2] = {0, 0};
        for (int k = 0; k < 4; ++k) { int b = m.bi[v * 4 + k]; if (size_t(b) < legBone.size() && legBone[size_t(b)] >= 0) w[legBone[size_t(b)]] += m.bw[v * 4 + k] / 255.0f; }
        int s = w[0] >= w[1] ? 0 : 1;
        if (w[s] < 0.5f) continue;
        V3 h = s ? V3(hip.x, -hip.y, hip.z) : hip, kn = s ? V3(knee.x, -knee.y, knee.z) : knee, ax = kn - h;
        V3 p(m.pos[v * 3], m.pos[v * 3 + 1], m.pos[v * 3 + 2]);
        along[v] = (h.z - p.z) / std::max(h.z - kn.z, 1e-4f);   // (by height, as the fit cuts the model: fitEve)
        (void)ax;
        side[v] = int8_t(s);
        below[v] = along[v] > t;
    }
    o.idx.clear();
    for (size_t i = 0; i + 2 < m.idx.size(); i += 3)
        if (!below[m.idx[i]] && !below[m.idx[i + 1]] && !below[m.idx[i + 2]]) o.idx.insert(o.idx.end(), {m.idx[i], m.idx[i + 1], m.idx[i + 2]});
    // the open edge: edges (positions welded) of one kept triangle only, on a leg near the cut
    std::unordered_map<int64_t, uint32_t> weld;
    std::vector<uint32_t> wid(nv);
    for (size_t v = 0; v < nv; ++v) {
        V3 p(m.pos[v * 3], m.pos[v * 3 + 1], m.pos[v * 3 + 2]);
        auto c = [](float f) { return int64_t(std::llround(double(f) / 1e-5)) & 0x1FFFFF; };
        int64_t key = c(p.x) << 42 | c(p.y) << 21 | c(p.z);
        wid[v] = weld.emplace(key, uint32_t(weld.size())).first->second;
    }
    std::map<std::pair<uint32_t, uint32_t>, int> uses;
    for (size_t i = 0; i + 2 < o.idx.size(); i += 3)
        for (int k = 0; k < 3; ++k) {
            uint32_t a = wid[o.idx[i + k]], b = wid[o.idx[i + (k + 1) % 3]];
            if (a != b) uses[{std::min(a, b), std::max(a, b)}]++;
        }
    std::vector<uint8_t> open(weld.size(), 0);
    for (const auto& [e, c] : uses) if (c == 1) { open[e.first] = 1; open[e.second] = 1; }
    std::vector<uint32_t> ring[2];
    for (size_t v = 0; v < nv; ++v)
        if (side[v] >= 0 && open[wid[v]] && along[v] > t - 0.25f && along[v] <= t) ring[size_t(side[v])].push_back(uint32_t(v));
    std::vector<std::pair<std::string, std::vector<uint32_t>>> rings;
    for (const auto& r : m.rings) if (r.first == "neck") rings.push_back(r);
    rings.push_back({"L_ankle", ring[0]});
    rings.push_back({"R_ankle", ring[1]});
    o.rings = rings;
    o.ankleFeather = 0.06f;   // (a thigh's seam: a wider fade to the model's own skin)
    return o;
}

uint32_t eveCrc32(const uint8_t* data, size_t n) { return crc32(reinterpret_cast<const char*>(data), n); }

bool EveMesh::joint(const std::string& name, V3& out) const {
    for (const auto& j : joints) if (j.first == name) { out = j.second; return true; }
    return false;
}

const std::vector<uint32_t>* EveMesh::ring(const std::string& name) const {
    for (const auto& r : rings) if (r.first == name) return &r.second;
    return nullptr;
}

bool parseEve(const uint8_t* data, size_t size, EveMesh& out, std::string& error) {
    out = EveMesh();
    Reader r(data, size);
    if (!r.has(0, 54)) { error = "is too short for an Eve mesh"; return false; }
    if (r.u32(0) != kEveMagic) { error = "is not a Wifecraft Eve mesh"; return false; }
    if (r.u32(4) != kEveVersion) { error = "is version " + std::to_string(r.u32(4)) + "; this build reads " + std::to_string(kEveVersion); return false; }
    if (eveCrc32(data + 12, size - 12) != r.u32(8)) { error = "is damaged (its checksum doesn't match)"; return false; }
    uint32_t n = r.u32(16), ic = r.u32(20), bc = r.u32(24), jc = r.u32(28), rc = r.u32(32);
    if (ic % 3 || bc > 255 || jc > 256 || rc > 64 || uint64_t(n) * 48 > size || uint64_t(ic) * 4 > size) { error = "has bad counts"; return false; }
    out.texW = r.u32(36); out.texH = r.u32(40); out.cropV0 = r.f32(44); out.cropV1 = r.f32(48);
    size_t o = 52;
    auto str = [&](std::string& s) {
        if (!r.has(o, 2)) return false;
        uint16_t len = r.u16(o); o += 2;
        if (!r.has(o, len)) return false;
        s.assign(reinterpret_cast<const char*>(data + o), len); o += len;
        return true;
    };
    if (!str(out.variant)) { error = "ends inside its name"; return false; }
    out.bones.resize(bc);
    for (auto& b : out.bones) if (!str(b)) { error = "ends inside its bone names"; return false; }
    out.joints.resize(jc);
    for (auto& j : out.joints) {
        if (!str(j.first) || !r.has(o, 12)) { error = "ends inside its joints"; return false; }
        j.second = V3(r.f32(o), r.f32(o + 4), r.f32(o + 8)); o += 12;
    }
    out.rings.resize(rc);
    for (auto& g : out.rings) {
        if (!str(g.first) || !r.has(o, 4)) { error = "ends inside its rings"; return false; }
        uint32_t k = r.u32(o); o += 4;
        if (k > n || !r.has(o, size_t(k) * 4)) { error = "has a bad ring"; return false; }
        g.second.resize(k);
        for (uint32_t& v : g.second) { v = r.u32(o); o += 4; if (v >= n) { error = "rings a vertex it hasn't"; return false; } }
    }
    if (!r.has(o, size_t(n) * 48 + size_t(ic) * 4)) { error = "is shorter than its counts"; return false; }
    out.pos.resize(size_t(n) * 3); out.nrm.resize(size_t(n) * 3); out.uv.resize(size_t(n) * 2); out.uv2.resize(size_t(n) * 2);
    out.bi.resize(size_t(n) * 4); out.bw.resize(size_t(n) * 4);
    for (uint32_t v = 0; v < n; ++v) {
        std::memcpy(&out.pos[v * 3], data + o, 12); std::memcpy(&out.nrm[v * 3], data + o + 12, 12);
        std::memcpy(&out.uv[v * 2], data + o + 24, 8); std::memcpy(&out.uv2[v * 2], data + o + 32, 8);
        std::memcpy(&out.bi[v * 4], data + o + 40, 4); std::memcpy(&out.bw[v * 4], data + o + 44, 4);
        for (int k = 0; k < 4; ++k) if (out.bi[v * 4 + k] >= std::max<uint32_t>(bc, 1)) { error = "names a bone it hasn't"; return false; }
        o += 48;
    }
    out.idx.resize(ic);
    if (ic) std::memcpy(out.idx.data(), data + o, size_t(ic) * 4);
    for (uint32_t i : out.idx) if (i >= n) { error = "has an index past its vertices"; return false; }
    for (float f : out.pos) if (!std::isfinite(f)) { error = "has a position that is not a number"; return false; }
    return true;
}

std::vector<uint8_t> writeEve(const EveMesh& m) {
    std::vector<uint8_t> b;
    auto u32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back(uint8_t(v >> (8 * i))); };
    auto f32 = [&](float f) { uint32_t v; std::memcpy(&v, &f, 4); u32(v); };
    auto str = [&](const std::string& s) { size_t n = std::min<size_t>(s.size(), 0xFFFF); b.push_back(uint8_t(n)); b.push_back(uint8_t(n >> 8)); b.insert(b.end(), s.begin(), s.begin() + n); };
    size_t n = m.vertexCount();
    u32(kEveMagic); u32(kEveVersion); u32(0);
    u32(3); u32(uint32_t(n)); u32(uint32_t(m.idx.size())); u32(uint32_t(m.bones.size())); u32(uint32_t(m.joints.size())); u32(uint32_t(m.rings.size()));
    u32(m.texW); u32(m.texH); f32(m.cropV0); f32(m.cropV1);
    str(m.variant);
    for (const auto& s : m.bones) str(s);
    for (const auto& j : m.joints) { str(j.first); f32(j.second.x); f32(j.second.y); f32(j.second.z); }
    for (const auto& g : m.rings) { str(g.first); u32(uint32_t(g.second.size())); for (uint32_t v : g.second) u32(v); }
    for (size_t v = 0; v < n; ++v) {
        for (int a = 0; a < 3; ++a) f32(m.pos[v * 3 + a]);
        for (int a = 0; a < 3; ++a) f32(v * 3 + a < m.nrm.size() ? m.nrm[v * 3 + a] : 0.0f);
        for (int a = 0; a < 2; ++a) f32(v * 2 + a < m.uv.size() ? m.uv[v * 2 + a] : 0.0f);
        for (int a = 0; a < 2; ++a) f32(v * 2 + a < m.uv2.size() ? m.uv2[v * 2 + a] : 0.0f);
        for (int k = 0; k < 4; ++k) b.push_back(v * 4 + k < m.bi.size() ? m.bi[v * 4 + k] : 0);
        for (int k = 0; k < 4; ++k) b.push_back(v * 4 + k < m.bw.size() ? m.bw[v * 4 + k] : uint8_t(k == 0 ? 255 : 0));
    }
    for (uint32_t i : m.idx) u32(i);
    uint32_t c = eveCrc32(b.data() + 12, b.size() - 12);
    for (int i = 0; i < 4; ++i) b[8 + i] = uint8_t(c >> (8 * i));
    return b;
}

// ================================================================================================ the tables

const EveRaceFit* EveFitTable::forModel(uint32_t model) const {
    for (const EveRaceFit& r : rows) if (r.model == model) return &r;
    return nullptr;
}

bool parseEveFitTable(const std::string& json, EveFitTable& out, std::string& error) {
    out = EveFitTable();
    Json j;
    if (!Json::parse(json, j, &error)) return false;
    const Json& rows = j["races"];
    if (rows.kind != Json::Array) { error = "has no races"; return false; }
    // "defaults": the fit every row starts from (each row's own "fit" over it)
    EveRaceFit defaults;
    auto readFit = [](const Json& k, EveRaceFit& f) {
        auto num = [&](const char* key, float& v) { if (k.has(key)) v = float(k[key].num(v)); };
        num("girth", f.girth); num("torso", f.torso); num("hips", f.hips); num("shoulders", f.shoulders); num("thigh", f.thigh);
        num("calf", f.calf); num("arm", f.arm); num("forearm", f.forearm); num("neckTuck", f.neckTuck); num("neckLift", f.neckLift);
        num("neckBase", f.neckBase); num("ankleLift", f.ankleLift); num("ankleTuck", f.ankleTuck); num("ankleSink", f.ankleSink);
        num("heads", f.heads); num("hipRatio", f.hipRatio); num("armRatio", f.armRatio); num("handHeads", f.handHeads); num("spread", f.spread);
        num("split", f.split);
    };
    if (j.has("defaults")) readFit(j["defaults"]["fit"], defaults);
    for (const Json& r : rows.a) {
        EveRaceFit f = defaults;
        const Json& models = r["models"];
        f.race = int(r["race"].num());
        f.name = r["name"].str();
        f.quality = r["quality"].str();
        f.reason = r["reason"].str();
        f.enabled = r["enabled"].kind == Json::Bool ? r["enabled"].b : false;
        readFit(r["fit"], f);
        for (const Json& m : models.a) { EveRaceFit c = f; c.model = uint32_t(m.num()); if (c.model) out.rows.push_back(c); }
    }
    return true;
}

static uint32_t hexColour(const Json& j) { return j.kind == Json::String ? uint32_t(std::strtoul(j.s.c_str(), nullptr, 16)) & 0xFFFFFFu : 0u; }

bool parseEveSetIndex(const std::string& json, EveSetIndex& out, std::string& error) {
    out = EveSetIndex();
    Json j;
    if (!Json::parse(json, j, &error)) return false;
    if (int(j["format"].num()) != 1) { error = "is a format this build doesn't read"; return false; }
    for (const auto& [k, v] : j["meshes"].o) if (v.kind == Json::String) out.meshes.push_back({k, v.s});
    out.width = int(j["texture"]["width"].num());
    out.height = int(j["texture"]["height"].num());
    auto tex = [](const Json& t) {
        EveTexInfo i;
        i.id = t["id"].str(); i.name = t["name"].str(); i.file = t["file"].str();
        i.swatch = hexColour(t["swatch"]); i.mean = hexColour(t["mean"]); i.neck = hexColour(t["neck"]); i.ankle = hexColour(t["ankle"]);
        return i;
    };
    if (j.has("skin")) { out.skin = tex(j["skin"]); out.hasSkin = !out.skin.file.empty(); }
    for (const Json& t : j["tones"].a) out.tones.push_back(tex(t));
    for (const Json& s : j["overlays"].a) {
        EveSlotInfo slot;
        slot.slot = s["slot"].str();
        for (const Json& sh : s["shades"].a) {
            EveShadeInfo i;
            i.id = sh["id"].str(); i.file = sh["file"].str(); i.swatch = hexColour(sh["swatch"]); i.mean = hexColour(sh["mean"]);
            for (int k = 0; k < 4; ++k) i.rect[k] = int(sh["rect"][size_t(k)].num());
            slot.shades.push_back(i);
        }
        out.overlays.push_back(slot);
    }
    if (out.meshes.empty()) { error = "names no mesh"; return false; }
    return true;
}

// ================================================================================================ PNG

bool decodePng(const uint8_t* d, size_t size, std::vector<uint8_t>& rgba, int& w, int& h, std::string& error) {
    static const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    if (size < 33 || std::memcmp(d, sig, 8) != 0) { error = "is not a PNG"; return false; }
    auto be = [&](size_t o) { return uint32_t(d[o]) << 24 | uint32_t(d[o + 1]) << 16 | uint32_t(d[o + 2]) << 8 | d[o + 3]; };
    size_t o = 8;
    int depth = 0, ctype = 0, interlace = 0;
    std::vector<uint8_t> idat, pal, trns;
    w = h = 0;
    while (o + 12 <= size) {
        uint32_t len = be(o);
        if (o + 12 + uint64_t(len) > size) { error = "is cut short"; return false; }
        const uint8_t* c = d + o + 8;
        std::string type(reinterpret_cast<const char*>(d + o + 4), 4);
        if (type == "IHDR" && len >= 13) {
            w = int(be(o + 8)); h = int(be(o + 12)); depth = c[8]; ctype = c[9]; interlace = c[12];
        } else if (type == "PLTE") pal.assign(c, c + len);
        else if (type == "tRNS") trns.assign(c, c + len);
        else if (type == "IDAT") idat.insert(idat.end(), c, c + len);
        else if (type == "IEND") break;
        o += 12 + len;
    }
    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) { error = "has a bad size"; return false; }
    if (depth != 8 || interlace != 0) { error = "is not an 8-bit, non-interlaced PNG"; return false; }
    int ch = ctype == 0 ? 1 : ctype == 2 ? 3 : ctype == 3 ? 1 : ctype == 4 ? 2 : ctype == 6 ? 4 : 0;
    if (!ch) { error = "has an unknown colour type"; return false; }
    size_t stride = size_t(w) * ch;
    std::vector<uint8_t> raw((stride + 1) * size_t(h));
    z_stream zs{};
    if (inflateInit(&zs) != Z_OK) { error = "can't be inflated"; return false; }
    zs.next_in = idat.data(); zs.avail_in = uInt(idat.size());
    zs.next_out = raw.data(); zs.avail_out = uInt(raw.size());
    int zr = inflate(&zs, Z_FINISH);
    size_t got = raw.size() - zs.avail_out;
    inflateEnd(&zs);
    if ((zr != Z_STREAM_END && zr != Z_OK && zr != Z_BUF_ERROR) || got != raw.size()) { error = "has damaged image data"; return false; }
    std::vector<uint8_t> px(stride * size_t(h));
    for (int y = 0; y < h; ++y) {
        uint8_t f = raw[size_t(y) * (stride + 1)];
        const uint8_t* s = &raw[size_t(y) * (stride + 1) + 1];
        uint8_t* r = &px[size_t(y) * stride];
        const uint8_t* p = y ? &px[size_t(y - 1) * stride] : nullptr;
        for (size_t x = 0; x < stride; ++x) {
            int a = x >= size_t(ch) ? r[x - ch] : 0, b = p ? p[x] : 0, c = (p && x >= size_t(ch)) ? p[x - ch] : 0;
            int v = s[x];
            switch (f) {
                case 0: break;
                case 1: v += a; break;
                case 2: v += b; break;
                case 3: v += (a + b) / 2; break;
                case 4: { int pp = a + b - c, pa = std::abs(pp - a), pb = std::abs(pp - b), pc = std::abs(pp - c);
                          v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c); break; }
                default: error = "has an unknown filter"; return false;
            }
            r[x] = uint8_t(v);
        }
    }
    rgba.assign(size_t(w) * h * 4, 255);
    for (size_t i = 0; i < size_t(w) * h; ++i) {
        uint8_t* q = &rgba[i * 4];
        const uint8_t* s = &px[i * ch];
        switch (ctype) {
            case 0: q[0] = q[1] = q[2] = s[0]; break;
            case 4: q[0] = q[1] = q[2] = s[0]; q[3] = s[1]; break;
            case 2: q[0] = s[0]; q[1] = s[1]; q[2] = s[2]; break;
            case 6: std::memcpy(q, s, 4); break;
            case 3: {
                size_t k = s[0];
                if (k * 3 + 2 < pal.size()) { q[0] = pal[k * 3]; q[1] = pal[k * 3 + 1]; q[2] = pal[k * 3 + 2]; }
                if (k < trns.size()) q[3] = trns[k];
                break;
            }
        }
    }
    return true;
}

// ================================================================================================ colour

float eveToLinear(float c) { return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f); }
float eveToSrgb(float c) { c = std::max(c, 0.0f); return c <= 0.0031308f ? c * 12.92f : 1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f; }

V3 eveLinear(uint32_t rgb) {
    return V3(eveToLinear(float((rgb >> 16) & 255) / 255.0f), eveToLinear(float((rgb >> 8) & 255) / 255.0f), eveToLinear(float(rgb & 255) / 255.0f));
}

uint32_t eveRgb(V3 l) {
    auto b = [](float c) { return uint32_t(std::clamp(eveToSrgb(c), 0.0f, 1.0f) * 255.0f + 0.5f); };
    return b(l.x) << 16 | b(l.y) << 8 | b(l.z);
}

V3 eveLab(uint32_t rgb) {
    V3 c = eveLinear(rgb);
    float X = (0.4124f * c.x + 0.3576f * c.y + 0.1805f * c.z) / 0.9505f;
    float Y = 0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z;
    float Z = (0.0193f * c.x + 0.1192f * c.y + 0.9505f * c.z) / 1.089f;
    auto f = [](float t) { return t > 0.008856f ? std::cbrt(t) : 7.787f * t + 16.0f / 116.0f; };
    float fx = f(X), fy = f(Y), fz = f(Z);
    return V3(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz));
}

int eveNearestShade(uint32_t target, const std::vector<EveShadeInfo>& shades, float* dist) {
    V3 t = eveLab(target);
    int best = -1;
    float bd = 1e30f;
    for (size_t i = 0; i < shades.size(); ++i) {
        float d = (eveLab(shades[i].swatch) - t).length();
        if (d < bd) { bd = d; best = int(i); }
    }
    if (dist) *dist = best >= 0 ? bd : 0;
    return best;
}

V3 eveGain(uint32_t target, uint32_t reference) {
    V3 t = eveLinear(target), r = eveLinear(reference);
    auto g = [](float a, float b) { return std::clamp(a / std::max(b, 1e-4f), 0.05f, 8.0f); };
    return V3(g(t.x, r.x), g(t.y, r.y), g(t.z, r.z));
}

// ================================================================================================ the fit

namespace {

struct M3 {
    float m[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};   // row major
    V3 operator*(const V3& v) const { return V3(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z); }
    M3 operator*(const M3& o) const {
        M3 r;
        for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) { float s = 0; for (int k = 0; k < 3; ++k) s += m[i * 3 + k] * o.m[k * 3 + j]; r.m[i * 3 + j] = s; }
        return r;
    }
    M3 transposed() const { M3 r; for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) r.m[i * 3 + j] = m[j * 3 + i]; return r; }
    static M3 columns(V3 a, V3 b, V3 c) { M3 r; r.m[0] = a.x; r.m[3] = a.y; r.m[6] = a.z; r.m[1] = b.x; r.m[4] = b.y; r.m[7] = b.z; r.m[2] = c.x; r.m[5] = c.y; r.m[8] = c.z; return r; }
};

// The rotation turning direction a onto direction b (Rodrigues).
M3 rotBetween(V3 a, V3 b) {
    a = a.normalized(); b = b.normalized();
    V3 v = a.cross(b);
    float c = a.dot(b);
    M3 r;
    if (v.length() < 1e-7f) {
        if (c > 0) return r;
        // half turn about any axis normal to a
        V3 ax = std::fabs(a.x) < 0.9f ? V3(1, 0, 0).cross(a).normalized() : V3(0, 1, 0).cross(a).normalized();
        M3 h;
        for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) {
            float ai = i == 0 ? ax.x : i == 1 ? ax.y : ax.z, aj = j == 0 ? ax.x : j == 1 ? ax.y : ax.z;
            h.m[i * 3 + j] = 2 * ai * aj - (i == j ? 1.0f : 0.0f);
        }
        return h;
    }
    float k = 1.0f / (1.0f + c);
    M3 vx;
    vx.m[0] = 0; vx.m[1] = -v.z; vx.m[2] = v.y; vx.m[3] = v.z; vx.m[4] = 0; vx.m[5] = -v.x; vx.m[6] = -v.y; vx.m[7] = v.x; vx.m[8] = 0;
    M3 vx2 = vx * vx;
    for (int i = 0; i < 9; ++i) r.m[i] = (i % 4 == 0 ? 1.0f : 0.0f) + vx.m[i] + vx2.m[i] * k;
    return r;
}

// A limb segment: Sims points along As -> Bs onto Aw -> Bw, `ka` along it, `kr` across.
struct Seg {
    V3 As, Aw, ds;
    float ka = 1, kr = 1;
    M3 R;
    bool ok = false;
    static Seg make(V3 As, V3 Bs, V3 Aw, V3 Bw, float kr) {
        Seg s;
        float ls = (Bs - As).length(), lw = (Bw - Aw).length();
        if (ls < 1e-5f || lw < 1e-5f) return s;
        s.As = As; s.Aw = Aw; s.ds = (Bs - As) * (1.0f / ls); s.ka = lw / ls; s.kr = kr; s.R = rotBetween(Bs - As, Bw - Aw); s.ok = true;
        return s;
    }
    V3 apply(V3 p) const {
        V3 q = p - As;
        float a = q.dot(ds);
        V3 rad = q - ds * a;
        return Aw + R * (ds * (a * ka) + rad * kr);
    }
};

V3 mirrorY(V3 v) { return V3(v.x, -v.y, v.z); }
V3 posOf(const M2Vertex& v) { return V3(v.pos[0], v.pos[1], v.pos[2]); }

int64_t weldKey(V3 p, float cell) {
    auto c = [&](float f) { return int64_t(std::llround(double(f) / cell)) & 0x1FFFFF; };
    return c(p.x) << 42 | c(p.y) << 21 | c(p.z);
}

// The body bones (their triangles are cut) and the bones that stay (head, neck, feet).
const char* const kCutNames[] = {"Pelvis", "Waist", "Spine1", "Spine2", "Chest", "L_Clavicle", "R_Clavicle", "L_UpperArm", "R_UpperArm",
    "L_Forearm", "R_Forearm", "L_Hand", "R_Hand", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Breast", "R_Breast", "L_Thumb1", "R_Thumb1",
    "L_Thumb2", "R_Thumb2", "L_Index1", "R_Index1", "L_Index2", "R_Index2", "L_Middle1", "R_Middle1", "L_Middle2", "R_Middle2", "L_Ring1",
    "R_Ring1", "L_Ring2", "R_Ring2", "L_Pinky1", "R_Pinky1", "L_Pinky2", "R_Pinky2"};
// (the ankle twist bone is the calf's: on most models it carries the lower shin, not the foot)
const char* const kKeepNames[] = {"Root", "Neck1", "Neck2", "Head", "Jaw", "L_Foot", "R_Foot", "L_Toe", "R_Toe", "L_Eye", "R_Eye"};

// Eve's own helper names: a key bone of the model's (its upper-arm and forearm twists, its elbow helper), else the limb.
struct Helper { const char* name; int key; const char* fallback; };
const Helper kHelpers[] = {
    {"L_UpperArmTwist", 73, "L_UpperArm"}, {"R_UpperArmTwist", 72, "R_UpperArm"},
    {"L_ElbowHelper", 75, "L_Forearm"}, {"R_ElbowHelper", 74, "R_Forearm"},
    {"L_ForearmTwist", 77, "L_Forearm"}, {"R_ForearmTwist", 76, "R_Forearm"},
};

int resolveBone(const M2Model& m, const std::string& name) {
    for (const Helper& h : kHelpers) {
        if (name != h.name) continue;
        for (size_t i = 0; i < m.bones.size(); ++i) if (m.bones[i].keyBoneId == h.key) return int(i);
        return m.findWowBone(h.fallback);
    }
    int b = m.findWowBone(name);
    if (b >= 0) return b;
    // a bone the model lacks: its nearest stand-in
    if (name.find("Breast") != std::string::npos) return m.findWowBone("Chest");
    if (name.size() > 2 && (name[0] == 'L' || name[0] == 'R') && name[1] == '_') {
        std::string s = name.substr(0, 2);
        for (const char* f : {"Thumb", "Index", "Middle", "Ring", "Pinky"}) if (name.find(f) != std::string::npos) {
            int k = m.findWowBone(s + f + "1");
            return k >= 0 ? k : m.findWowBone(s + "Hand");
        }
    }
    int p = m.findWowBone("Pelvis");
    return p >= 0 ? p : 0;
}

// Which region of the fit carries a bone (by its Warcraft name).
enum Region : int {
    kTorso = 0, kUpperArm, kForearm, kHand, kThigh, kCalf,
    kFinger0,   // + finger * 2 + (0 proximal, 1 distal): thumb, index, middle, ring, pinky
    kRegionsPerSide = kFinger0 + 10
};
const char* const kFingers[] = {"Thumb", "Index", "Middle", "Ring", "Pinky"};
const char* const kFingerJoint[] = {"Thumb", "Index", "Mid", "Ring", "Pinky"};

// side * kRegionsPerSide + region; torso is 0 for both
int regionOf(const std::string& n) {
    if (n.size() < 3 || (n[0] != 'L' && n[0] != 'R') || n[1] != '_') return kTorso;
    int side = n[0] == 'L' ? 0 : 1;
    std::string s = n.substr(2);
    int r = -1;
    if (s == "UpperArm" || s == "UpperArmTwist") r = kUpperArm;
    else if (s == "Forearm" || s == "ForearmTwist" || s == "ElbowHelper") r = kForearm;
    else if (s == "Hand") r = kHand;
    else if (s == "Thigh") r = kThigh;
    else if (s == "Calf") r = kCalf;
    else for (int f = 0; f < 5; ++f) {
        std::string fn = kFingers[f];
        if (s == fn + "1") r = kFinger0 + f * 2;
        else if (s == fn + "2") r = kFinger0 + f * 2 + 1;
    }
    if (r < 0) return kTorso;
    return side * kRegionsPerSide + r;
}

struct WMap {   // a vertex's weights by model bone, up to 8
    std::array<std::pair<int, float>, 8> e{};
    int n = 0;
    void add(int b, float w) {
        if (w <= 0) return;
        for (int i = 0; i < n; ++i) if (e[size_t(i)].first == b) { e[size_t(i)].second += w; return; }
        if (n < 8) { e[size_t(n++)] = {b, w}; return; }
        int lo = 0;
        for (int i = 1; i < n; ++i) if (e[size_t(i)].second < e[size_t(lo)].second) lo = i;
        if (e[size_t(lo)].second < w) e[size_t(lo)] = {b, w};
    }
    WMap scaled(float s) const { WMap r = *this; for (int i = 0; i < n; ++i) r.e[size_t(i)].second *= s; return r; }
};
WMap blend(const WMap& a, const WMap& b, float t) {
    WMap r = a.scaled(1 - t);
    for (int i = 0; i < b.n; ++i) r.add(b.e[size_t(i)].first, b.e[size_t(i)].second * t);
    return r;
}

struct RingEdges { std::vector<std::pair<uint32_t, uint32_t>> e; V3 centre; float rx = 0, ry = 0, zmin = 0, zmax = 0, zmean = 0; };

void measureRing(const std::vector<M2Vertex>& verts, RingEdges& r) {
    if (r.e.empty()) return;
    V3 lo(1e9f, 1e9f, 1e9f), hi(-1e9f, -1e9f, -1e9f), sum;
    size_t n = 0;
    for (auto& e : r.e) for (uint32_t v : {e.first, e.second}) {
        V3 p = posOf(verts[v]);
        lo = V3(std::min(lo.x, p.x), std::min(lo.y, p.y), std::min(lo.z, p.z));
        hi = V3(std::max(hi.x, p.x), std::max(hi.y, p.y), std::max(hi.z, p.z));
        sum = sum + p; ++n;
    }
    r.centre = (lo + hi) * 0.5f;
    r.rx = (hi.x - lo.x) * 0.5f; r.ry = (hi.y - lo.y) * 0.5f;
    r.zmin = lo.z; r.zmax = hi.z; r.zmean = (sum * (1.0f / float(n))).z;
}

} // namespace

// Her proportions (EveRaceFit::heads ...) as factors on the skeleton: the model's head measured (its kept skin: the lowest
// vertex the head's bones carry to the highest), her height and the hip joints', the arms' and the hands' lengths set to the
// targets in head heights. The neck and head stay; the torso takes what the legs don't.
static BodyScale eveProportions(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
                         const std::vector<M2Submesh>& subs, const std::vector<uint16_t>& geosets, const EveBody& body, const EveRaceFit& fit,
                         V3 hipL, V3 hipR, V3 neck, V3 shoulder, V3 elbow, V3 wrist, EveFitStats& st) {
    BodyScale k;
    const int head = m.findWowBone("Head"), hand = m.findWowBone("L_Hand");
    if (head < 0 || hand < 0) return k;
    auto under = [&](int b, int top) { for (int c = b, g = 0; c >= 0 && size_t(c) < m.bones.size() && g < 256; c = m.bones[size_t(c)].parent, ++g) if (c == top) return true; return false; };
    // the shown skin the cut keeps
    bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    std::vector<uint8_t> skinSub(subs.size(), 0);
    for (const M2Batch& bt : m.batches) {
        if (bt.submesh >= subs.size() || bt.textureCombo >= m.textureCombos.size()) continue;
        uint16_t ti = m.textureCombos[bt.textureCombo];
        if (ti < m.textures.size() && m.textures[ti].type == 1) skinSub[bt.submesh] = 1;
    }
    float crown = -1e9f, chin = 1e9f;
    const float mid = m.bones[size_t(head)].pivot.y, band = 0.25f * std::fabs(shoulder.y - mid);   // (a quarter of the shoulder's reach)
    for (size_t s = 0; s < subs.size(); ++s) {
        if (!skinSub[s] || (!all && std::find(geosets.begin(), geosets.end(), subs[s].id) == geosets.end())) continue;
        for (uint32_t t = subs[s].indexStart; t < subs[s].indexStart + subs[s].indexCount && t < indices.size(); ++t) {
            uint32_t i = indices[t];
            if (i >= verts.size() || (i < body.cut.size() && body.cut[i])) continue;
            const M2Vertex& v = verts[i];
            // near the middle only: an elf's ears reach above her crown and beside her chin
            if (std::fabs(v.pos[1] - mid) > band) continue;
            crown = std::max(crown, v.pos[2]);
            int best = 0;
            for (int q = 1; q < 4; ++q) if (v.weights[q] > v.weights[best]) best = q;
            if (under(v.bones[best], head)) chin = std::min(chin, v.pos[2]);
        }
    }
    float H = crown - chin;
    st.head = H > 0 ? H : 0;
    st.height = crown;
    st.heightAfter = crown;
    if (!(H > 0.02f) || !(fit.heads > 0) || !(crown > H * 5)) return k;   // (a "head" over a fifth of her: no head measured)
    // her hand as fitted: the farthest of Eve's vertices the hand's bones carry, from the wrist
    float handLen = 0;
    for (const M2Vertex& v : body.verts) {
        int best = 0;
        for (int q = 1; q < 4; ++q) if (v.weights[q] > v.weights[best]) best = q;
        if (under(v.bones[best], hand)) handLen = std::max(handLen, (V3(v.pos[0], v.pos[1], v.pos[2]) - wrist).length());
    }
    const float total = fit.heads * H, hipZ = (hipL.z + hipR.z) * 0.5f, above = crown - neck.z;
    const float hip2 = std::clamp(fit.hipRatio, 0.3f, 0.7f) * total;
    k.legs = std::clamp(hip2 / std::max(hipZ, 1e-3f), 0.6f, 1.3f);
    const float neck2 = total - above;
    k.torso = std::clamp((neck2 - hipZ * k.legs) / std::max(neck.z - hipZ, 1e-3f), 0.7f, 1.3f);
    if (handLen > 1e-3f) k.hands = std::clamp(fit.handHeads * H / handLen, 0.6f, 1.3f);
    const float upper = (elbow - shoulder).length() + (wrist - elbow).length();
    if (upper > 1e-3f) k.arms = std::clamp((fit.armRatio * total - handLen * k.hands) / upper, 0.6f, 1.3f);
    k.spread = std::clamp(fit.spread, 0.7f, 1.4f);
    st.heightAfter = hipZ * k.legs + (neck.z - hipZ) * k.torso + above;
    return k;
}

EveBody fitEve(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
               const std::vector<M2Submesh>& subs, const std::vector<uint16_t>& geosets, const EveMesh& eve, const EveRaceFit& fit) {
    auto t0 = std::chrono::steady_clock::now();
    EveBody out;
    const size_t nv = eve.vertexCount();
    if (!nv || eve.idx.empty()) { out.error = "the Eve mesh is empty"; return out; }
    // ---- the model's joints (bind pose)
    auto piv = [&](const char* name, V3& p) { int b = m.findWowBone(name); if (b < 0 || size_t(b) >= m.bones.size()) return false; p = m.bones[size_t(b)].pivot; return true; };
    struct Side { V3 shoulder, elbow, wrist, hip, knee, ankle, K[5], P[5]; bool fingers[5] = {false, false, false, false, false}; } W[2];
    V3 neckW, pelvisW;
    if (!piv("Neck1", neckW) || !piv("Pelvis", pelvisW)) { out.error = "the model has no neck or pelvis bone"; return out; }
    for (int s = 0; s < 2; ++s) {
        std::string p = s ? "R_" : "L_";
        Side& w = W[s];
        if (!piv((p + "UpperArm").c_str(), w.shoulder) || !piv((p + "Forearm").c_str(), w.elbow) || !piv((p + "Hand").c_str(), w.wrist) ||
            !piv((p + "Thigh").c_str(), w.hip) || !piv((p + "Calf").c_str(), w.knee)) { out.error = "the model lacks a limb bone"; return out; }
        if (!piv((p + "Ankle").c_str(), w.ankle) && !piv((p + "Foot").c_str(), w.ankle)) { out.error = "the model has no ankle"; return out; }
        for (int f = 0; f < 5; ++f) w.fingers[f] = piv((p + kFingers[f] + "1").c_str(), w.K[f]) && piv((p + kFingers[f] + "2").c_str(), w.P[f]);
    }
    // ---- the Sims joints (left; the right mirrored)
    auto js = [&](const char* name, V3& v) { return eve.joint(name, v); };
    struct SSide { V3 shoulder, elbow, wrist, hip, knee, ankle, K[5], P[5], T[5]; } S[2];
    V3 neckS;
    bool okJ = js("neck", neckS) && js("shoulder", S[0].shoulder) && js("elbow", S[0].elbow) && js("wrist", S[0].wrist) &&
               js("hip", S[0].hip) && js("knee", S[0].knee) && js("ankle", S[0].ankle);
    for (int f = 0; f < 5 && okJ; ++f) {
        std::string n = kFingerJoint[f];
        okJ = js((n + "K").c_str(), S[0].K[f]) && js((n + "P").c_str(), S[0].P[f]) && js((n + "T").c_str(), S[0].T[f]);
    }
    if (!okJ) { out.error = "the Eve mesh lacks its joints"; return out; }
    S[1].shoulder = mirrorY(S[0].shoulder); S[1].elbow = mirrorY(S[0].elbow); S[1].wrist = mirrorY(S[0].wrist);
    S[1].hip = mirrorY(S[0].hip); S[1].knee = mirrorY(S[0].knee); S[1].ankle = mirrorY(S[0].ankle);
    for (int f = 0; f < 5; ++f) { S[1].K[f] = mirrorY(S[0].K[f]); S[1].P[f] = mirrorY(S[0].P[f]); S[1].T[f] = mirrorY(S[0].T[f]); }
    const float unit = (W[0].hip.z + W[1].hip.z) * 0.5f / S[0].hip.z;
    out.stats.unit = unit;

    // ---- the cut: each drawn vertex by its main bone's nearest named ancestor
    std::vector<int8_t> named(m.bones.size(), 0);
    for (const char* n : kCutNames) { int b = m.findWowBone(n); if (b >= 0) named[size_t(b)] = 1; }
    for (const char* n : kKeepNames) { int b = m.findWowBone(n); if (b >= 0) named[size_t(b)] = 2; }
    const bool split = fit.split > 0;   // (the split fit: the model's own legs from mid-thigh down)
    if (split) for (const char* n : {"L_Calf", "R_Calf"}) { int b = m.findWowBone(n); if (b >= 0) named[size_t(b)] = 2; }
    std::vector<int8_t> boneCut(m.bones.size(), 0);
    for (size_t b = 0; b < m.bones.size(); ++b) {
        int c = int(b), guard = 0;
        while (c >= 0 && size_t(c) < m.bones.size() && !named[size_t(c)] && guard++ < 128) c = m.bones[size_t(c)].parent;
        boneCut[b] = (c >= 0 && size_t(c) < m.bones.size() && named[size_t(c)] == 1) ? 1 : 0;
    }
    out.cut.assign(verts.size(), false);
    for (size_t i = 0; i < verts.size(); ++i) {
        const M2Vertex& v = verts[i];
        int best = 0;
        for (int k = 1; k < 4; ++k) if (v.weights[k] > v.weights[best]) best = k;
        uint8_t b = v.bones[best];
        if (v.weights[best] && b < boneCut.size() && boneCut[b]) { out.cut[i] = true; ++out.stats.cutVertices; }
    }
    // a tail stays: what hangs on an unnamed bone under the pelvis, its pivot well behind the hips (a draenei's, a worgen's)
    {
        const int pelvis = m.findWowBone("Pelvis"), root = m.findWowBone("Root");
        std::vector<uint8_t> tail(m.bones.size(), 0);
        for (size_t b = 0; b < m.bones.size(); ++b) {
            if (named[b]) continue;
            int c = int(b), guard = 0;
            while (c >= 0 && size_t(c) < m.bones.size() && !named[size_t(c)] && guard++ < 128) c = m.bones[size_t(c)].parent;
            if ((c == pelvis || c == root) && c >= 0 && m.bones[b].pivot.x < pelvisW.x - 0.1f * unit) tail[b] = 1;
        }
        // a chain of three at least (a tail; a buttock's single helper bone is no tail)
        for (size_t b = 0; b < m.bones.size(); ++b) {
            if (!tail[b]) continue;
            bool chain = false;
            for (size_t c = 0; c < m.bones.size() && !chain; ++c)
                if (m.bones[c].parent == int(b)) for (size_t d = 0; d < m.bones.size() && !chain; ++d) chain = m.bones[d].parent == int(c);
            if (!chain && (m.bones[b].parent < 0 || !tail[size_t(m.bones[b].parent)])) tail[b] = 0;
        }
        for (size_t b = 0; b < m.bones.size(); ++b)   // (and what hangs under a tail bone)
            for (int c = m.bones[b].parent, guard = 0; c >= 0 && size_t(c) < m.bones.size() && guard < 128; c = m.bones[size_t(c)].parent, ++guard)
                if (tail[size_t(c)]) { tail[b] = 1; break; }
        for (size_t i = 0; i < verts.size(); ++i) {
            if (!out.cut[i]) continue;
            const M2Vertex& v = verts[i];
            int best = 0;
            for (int k = 1; k < 4; ++k) if (v.weights[k] > v.weights[best]) best = k;
            if (v.bones[best] < tail.size() && tail[v.bones[best]]) { out.cut[i] = false; --out.stats.cutVertices; }
        }
    }
    // the split fit: what the thighs carry below the cut (along hip -> knee, past `split` of it) stays the model's
    V3 splitW[2];
    for (int s = 0; s < 2; ++s) splitW[s] = W[s].hip + (W[s].knee - W[s].hip) * fit.split;
    if (split) {
        int thighs[2] = {m.findWowBone("L_Thigh"), m.findWowBone("R_Thigh")};
        for (size_t i = 0; i < verts.size(); ++i) {
            if (!out.cut[i]) continue;
            const M2Vertex& v = verts[i];
            int best = 0;
            for (int k = 1; k < 4; ++k) if (v.weights[k] > v.weights[best]) best = k;
            int c = v.bones[best], guard = 0;
            while (c >= 0 && size_t(c) < m.bones.size() && !named[size_t(c)] && guard++ < 128) c = m.bones[size_t(c)].parent;
            for (int s = 0; s < 2; ++s) {
                if (c != thighs[s]) continue;
                // (by height: a draenei's thigh leans forward, and a cut across its axis would run up into the buttock behind)
                float t = (W[s].hip.z - v.pos[2]) / std::max(W[s].hip.z - W[s].knee.z, 1e-4f);
                if (t > fit.split) { out.cut[i] = false; --out.stats.cutVertices; }
            }
        }
    }
    // ---- the cut edge: kept triangles' edges (positions welded) that a cut triangle shares
    bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    std::unordered_map<int64_t, uint32_t> weld;
    std::vector<uint32_t> wid(verts.size()), rep;
    for (size_t i = 0; i < verts.size(); ++i) {
        auto [it, fresh] = weld.emplace(weldKey(posOf(verts[i]), 1e-4f), uint32_t(rep.size()));
        if (fresh) rep.push_back(uint32_t(i));
        wid[i] = it->second;
    }
    // only the skin's sections are cut (texture type 1): hair, ears and the like hang on body bones too and stay
    std::vector<uint8_t> skinSub(subs.size(), 0);
    for (const M2Batch& bt : m.batches) {
        if (bt.submesh >= subs.size() || bt.textureCombo >= m.textureCombos.size()) continue;
        uint16_t ti = m.textureCombos[bt.textureCombo];
        if (ti < m.textures.size() && m.textures[ti].type == 1) skinSub[bt.submesh] = 1;
    }
    {
        std::vector<uint8_t> onSkin(verts.size(), 0), offSkin(verts.size(), 0);
        for (size_t s = 0; s < subs.size(); ++s)
            for (uint32_t t = subs[s].indexStart; t < subs[s].indexStart + subs[s].indexCount && t < indices.size(); ++t)
                if (indices[t] < verts.size()) (skinSub[s] ? onSkin : offSkin)[indices[t]] = 1;
        for (size_t i = 0; i < verts.size(); ++i) if (out.cut[i] && !onSkin[i]) { out.cut[i] = false; --out.stats.cutVertices; }
    }
    std::map<std::pair<uint32_t, uint32_t>, int> keptE;
    std::map<std::pair<uint32_t, uint32_t>, int> goneE;
    for (const M2Submesh& sm : subs) {
        if (!sm.indexCount) continue;
        if (!all && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount && t + 2 < indices.size(); t += 3) {
            uint32_t a[3] = {indices[t], indices[t + 1], indices[t + 2]};
            if (a[0] >= verts.size() || a[1] >= verts.size() || a[2] >= verts.size()) continue;
            bool gone = out.cut[a[0]] || out.cut[a[1]] || out.cut[a[2]];
            if (gone) ++out.stats.cutTriangles; else ++out.stats.keptTriangles;
            for (int k = 0; k < 3; ++k) {
                uint32_t p = wid[a[k]], q = wid[a[(k + 1) % 3]];
                if (p == q) continue;
                auto key = std::make_pair(std::min(p, q), std::max(p, q));
                (gone ? goneE : keptE)[key]++;
            }
        }
    }
    RingEdges neckR, ankleR[2];
    const float near = 0.25f * unit;
    for (const auto& [e, c] : keptE) {
        if (!c || !goneE.count(e)) continue;   // (shared with a cut triangle: on the edge, whether or not a texture seam doubles it)
        uint32_t a = rep[e.first], b = rep[e.second];
        V3 mid = (posOf(verts[a]) + posOf(verts[b])) * 0.5f;
        float dn = (mid - neckW).length(), d0 = (mid - (split ? splitW[0] : W[0].ankle)).length(), d1 = (mid - (split ? splitW[1] : W[1].ankle)).length();
        if (split && std::fabs(mid.z - splitW[0].z) > 0.1f * unit) { d0 = d1 = 1e9f; }   // (a thigh's ring lies at the cut's height)
        if (dn < near && dn <= d0 && dn <= d1) neckR.e.push_back({a, b});
        else if (d0 < near && d0 <= d1) ankleR[0].e.push_back({a, b});
        else if (d1 < near) ankleR[1].e.push_back({a, b});
    }
    out.stats.neckEdges = neckR.e.size(); out.stats.ankleEdges[0] = ankleR[0].e.size(); out.stats.ankleEdges[1] = ankleR[1].e.size();
    if (neckR.e.size() < 4) { out.error = "the model's neck has no cut edge to meet"; return out; }
    measureRing(verts, neckR);
    for (int s = 0; s < 2; ++s) measureRing(verts, ankleR[s]);
    for (auto& e : neckR.e) for (uint32_t v : {e.first, e.second}) { out.neckUvs.push_back(verts[v].uv[0]); out.neckUvs.push_back(verts[v].uv[1]); }
    for (int s = 0; s < 2; ++s) for (auto& e : ankleR[s].e) for (uint32_t v : {e.first, e.second}) { out.ankleUvs.push_back(verts[v].uv[0]); out.ankleUvs.push_back(verts[v].uv[1]); }

    // ---- the transforms
    // the hips' width as the skin has it (the widest body vertex at the hip joints' height), not the joints' spacing: a race
    // whose legs are set wide (an orc, a Kul Tiran) would get hips far wider than its own
    float kHip = W[0].hip.y / S[0].hip.y;
    {
        float hz = (W[0].hip.z + W[1].hip.z) * 0.5f, band = 0.04f * unit, wowW = 0, eveW = 0;
        for (size_t i = 0; i < verts.size(); ++i)
            if (out.cut[i] && std::fabs(verts[i].pos[2] - hz) < band) wowW = std::max(wowW, std::fabs(verts[i].pos[1]));
        for (size_t v = 0; v < nv; ++v)
            if (std::fabs(eve.pos[v * 3 + 2] - S[0].hip.z) < 0.04f) eveW = std::max(eveW, std::fabs(eve.pos[v * 3 + 1]));
        if (wowW > 0 && eveW > 0) kHip = std::clamp(wowW / (eveW * unit) * unit, 0.6f * kHip, kHip);
    }
    const float kSh = W[0].shoulder.y / S[0].shoulder.y;
    out.stats.kHip = kHip; out.stats.kShoulder = kSh;
    const float neckBaseW = neckR.zmin - fit.neckBase * unit;
    const float zs[3] = {S[0].hip.z, S[0].shoulder.z, neckS.z};
    const float zw[3] = {(W[0].hip.z + W[1].hip.z) * 0.5f, (W[0].shoulder.z + W[1].shoulder.z) * 0.5f, neckBaseW};
    const float xs[3] = {S[0].hip.x, S[0].shoulder.x, neckS.x};
    const float xw[3] = {pelvisW.x, (W[0].shoulder.x + W[1].shoulder.x) * 0.5f, neckW.x};
    const float kyHip = kHip * fit.hips * fit.girth * fit.torso, kySh = kSh * fit.shoulders * fit.girth * fit.torso;
    auto interp = [](float z, const float* a, const float* b) {
        if (z <= a[0]) return b[0];
        if (z >= a[2]) return b[2];
        int i = z < a[1] ? 0 : 1;
        return b[i] + (b[i + 1] - b[i]) * (z - a[i]) / (a[i + 1] - a[i]);
    };
    auto torso = [&](V3 p) {
        float s01 = (zw[1] - zw[0]) / (zs[1] - zs[0]), s12 = (zw[2] - zw[1]) / (zs[2] - zs[1]);
        float z = p.z < zs[0] ? zw[0] + (p.z - zs[0]) * s01 : p.z > zs[2] ? zw[2] + (p.z - zs[2]) * s12 : interp(p.z, zs, zw);
        float t = std::clamp((p.z - zs[0]) / (zs[1] - zs[0]), 0.0f, 1.0f);
        float ky = kyHip + (kySh - kyHip) * t;
        float x0 = interp(p.z, zs, xs), x1 = interp(p.z, zs, xw);
        return V3(x1 + (p.x - x0) * ky, p.y * ky, z);
    };
    Seg segs[2][kRegionsPerSide];
    for (int s = 0; s < 2; ++s) {
        const Side& w = W[s];
        const SSide& j = S[s];
        float kra = kSh * fit.girth, krl = kHip * fit.girth;
        segs[s][kUpperArm] = Seg::make(j.shoulder, j.elbow, w.shoulder, w.elbow, kra * fit.arm);
        segs[s][kForearm] = Seg::make(j.elbow, j.wrist, w.elbow, w.wrist, kra * fit.forearm);
        segs[s][kThigh] = Seg::make(j.hip, j.knee, w.hip, w.knee, krl * fit.thigh);
        segs[s][kCalf] = Seg::make(j.knee, j.ankle, w.knee, w.ankle, krl * fit.calf);
        // the hand: a frame along the wrist to the middle knuckle and across the knuckles (index to pinky)
        Seg& hand = segs[s][kHand];
        if (w.fingers[2] && w.fingers[1] && w.fingers[4]) {
            auto frame = [](V3 wr, V3 mid, V3 idx, V3 pk) {
                V3 e1 = (mid - wr).normalized(), a = idx - pk;
                V3 e2 = (a - e1 * a.dot(e1)).normalized();
                return M3::columns(e1, e2, e1.cross(e2));
            };
            M3 Fs = frame(j.wrist, j.K[2], j.K[1], j.K[4]), Fw = frame(w.wrist, w.K[2], w.K[1], w.K[4]);
            float kh = (w.K[2] - w.wrist).length() / std::max((j.K[2] - j.wrist).length(), 1e-5f);
            hand.As = j.wrist; hand.Aw = w.wrist; hand.ds = (j.K[2] - j.wrist).normalized(); hand.ka = kh; hand.kr = kh; hand.R = Fw * Fs.transposed(); hand.ok = true;
            for (int f = 0; f < 5; ++f) {
                if (!w.fingers[f]) { segs[s][kFinger0 + f * 2] = hand; segs[s][kFinger0 + f * 2 + 1] = hand; continue; }
                segs[s][kFinger0 + f * 2] = Seg::make(j.K[f], j.P[f], w.K[f], w.P[f], kh);
                float ls = (j.T[f] - j.P[f]).length();
                V3 dir = (w.P[f] - w.K[f]).normalized();
                segs[s][kFinger0 + f * 2 + 1] = Seg::make(j.P[f], j.T[f], w.P[f], w.P[f] + dir * (ls * kh), kh);
            }
        } else {
            hand = Seg::make(j.wrist, j.K[2], w.wrist, w.wrist + (w.wrist - w.elbow).normalized() * ((j.K[2] - j.wrist).length() * unit), kra);
            for (int f = 0; f < 10; ++f) segs[s][kFinger0 + f] = hand;
        }
    }
    // each Eve bone: its model bone and its region
    std::vector<int> boneOf(eve.bones.size()), regOf(eve.bones.size());
    std::vector<uint8_t> neckish(eve.bones.size(), 0), calfSide(eve.bones.size(), 0);
    for (size_t b = 0; b < eve.bones.size(); ++b) {
        boneOf[b] = resolveBone(m, eve.bones[b]);
        regOf[b] = regionOf(eve.bones[b]);
        neckish[b] = eve.bones[b] == "Neck1" || eve.bones[b] == "Head";
        calfSide[b] = eve.bones[b] == "L_Calf" ? 1 : eve.bones[b] == "R_Calf" ? 2 : 0;
    }
    std::vector<V3> P(nv), N(nv), P0(nv);
    std::vector<WMap> WM(nv);
    std::vector<float> neckW8(nv, 0), calfW[2] = {std::vector<float>(nv, 0), std::vector<float>(nv, 0)};
    for (size_t v = 0; v < nv; ++v) {
        V3 p(eve.pos[v * 3], eve.pos[v * 3 + 1], eve.pos[v * 3 + 2]), n(eve.nrm[v * 3], eve.nrm[v * 3 + 1], eve.nrm[v * 3 + 2]);
        P0[v] = p;
        V3 acc, nacc;
        float tot = 0;
        V3 tp = torso(p);
        for (int k = 0; k < 4; ++k) {
            float w = eve.bw[v * 4 + k] / 255.0f;
            if (w <= 0) continue;
            size_t b = eve.bi[v * 4 + k];
            int rg = regOf[b];
            const Seg* sg = rg == kTorso ? nullptr : &segs[rg / kRegionsPerSide][rg % kRegionsPerSide];
            if (sg && sg->ok) { acc = acc + sg->apply(p) * w; nacc = nacc + (sg->R * n) * w; }
            else { acc = acc + tp * w; nacc = nacc + n * w; }
            tot += w;
            WM[v].add(boneOf[b], w);
            if (neckish[b]) neckW8[v] += w;
            if (calfSide[b]) calfW[calfSide[b] - 1][v] += w;
        }
        P[v] = tot > 0 ? acc * (1.0f / tot) : tp;
        N[v] = nacc.normalized();
    }
    // ---- the neck onto the model's neck tube: its section scaled to the cut edge's, its top raised inside it
    {
        float nz1 = neckS.z, hwS = 0, x0 = 1e9f, x1 = -1e9f;
        for (size_t v = 0; v < nv; ++v) {
            if (neckW8[v] > 0.5f) nz1 = std::max(nz1, P0[v].z);
            if (neckW8[v] > 0.8f) { hwS = std::max(hwS, std::fabs(P0[v].y)); x0 = std::min(x0, P0[v].x); x1 = std::max(x1, P0[v].x); }
        }
        float hdS = x1 > x0 ? (x1 - x0) * 0.5f : 0;
        if (hwS > 1e-4f && hdS > 1e-4f && nz1 > neckS.z + 1e-3f) {
            float sy = neckR.ry * 0.97f / hwS, sx = neckR.rx * 0.97f / hdS;
            float cS = (x0 + x1) * 0.5f;
            float tz0 = neckBaseW, tz1 = neckR.zmax;
            for (size_t v = 0; v < nv; ++v) {
                float a = std::clamp(neckW8[v], 0.0f, 1.0f);
                if (a <= 0) continue;
                float u = std::clamp((P0[v].z - neckS.z) / (nz1 - neckS.z), 0.0f, 1.0f);
                V3 q(neckR.centre.x + (P0[v].x - cS) * sx, neckR.centre.y + P0[v].y * sy, tz0 + u * (tz1 - tz0));
                P[v] = P[v] * (1 - a) + q * a;
            }
        }
    }
    // ---- the ankles: the calf's last 12 cm drawn onto the model's foot top (centre, radii), the ring a little inside it
    for (int s = 0; s < 2 && !split; ++s) {
        if (ankleR[s].e.empty()) continue;
        float yb = 1e9f;
        for (size_t v = 0; v < nv; ++v) if (calfW[s][v] > 0.3f && P0[v].z < 0.30f) yb = std::min(yb, P0[v].z);
        if (yb > 0.29f) continue;
        V3 cs; float lo[2] = {1e9f, 1e9f}, hi[2] = {-1e9f, -1e9f}; int cnt = 0;
        for (size_t v = 0; v < nv; ++v) if (calfW[s][v] > 0.3f && P0[v].z < yb + 0.004f) {
            cs = cs + P[v]; ++cnt;
            lo[0] = std::min(lo[0], P[v].x); hi[0] = std::max(hi[0], P[v].x); lo[1] = std::min(lo[1], P[v].y); hi[1] = std::max(hi[1], P[v].y);
        }
        if (!cnt) continue;
        cs = cs * (1.0f / float(cnt));
        float rxs = std::max((hi[0] - lo[0]) * 0.5f, 1e-4f), rys = std::max((hi[1] - lo[1]) * 0.5f, 1e-4f);
        float zt = ankleR[s].zmean + fit.ankleLift * unit;
        for (size_t v = 0; v < nv; ++v) {
            if (!(calfW[s][v] > 0.3f && P0[v].z < 0.30f)) continue;
            float t = std::clamp((0.30f - P0[v].z) / (0.30f - yb), 0.0f, 1.0f);
            t = t * t * (3 - 2 * t);
            V3 q = P[v];
            V3 f(ankleR[s].centre.x + (q.x - cs.x) * ankleR[s].rx * 0.97f / rxs, ankleR[s].centre.y + (q.y - cs.y) * ankleR[s].ry * 0.97f / rys, zt + (q.z - cs.z));
            P[v] = q * (1 - t) + f * t;
        }
    }
    // ---- the split fit: each thigh's last 25% above the cut drawn onto the model's cut section (its centre and radii), so the
    // rings meet without a step
    for (int sd = 0; sd < 2 && split; ++sd) {
        if (ankleR[sd].e.empty()) continue;
        const std::vector<uint32_t>* ring = eve.ring(sd ? "R_ankle" : "L_ankle");
        if (!ring || ring->empty()) continue;
        V3 h = sd ? mirrorY(S[0].hip) : S[0].hip, kn = sd ? mirrorY(S[0].knee) : S[0].knee, ax = kn - h;
        // Eve's section at the cut (her ring as fitted) against the model's
        V3 cs; float lo[3] = {1e9f, 1e9f, 1e9f}, hi[3] = {-1e9f, -1e9f, -1e9f};
        for (uint32_t v : *ring) {
            cs = cs + P[v];
            lo[0] = std::min(lo[0], P[v].x); hi[0] = std::max(hi[0], P[v].x); lo[1] = std::min(lo[1], P[v].y); hi[1] = std::max(hi[1], P[v].y);
        }
        cs = cs * (1.0f / float(ring->size()));
        float rxs = std::max((hi[0] - lo[0]) * 0.5f, 1e-4f), rys = std::max((hi[1] - lo[1]) * 0.5f, 1e-4f);
        V3 cw = ankleR[sd].centre;
        cw.z = ankleR[sd].zmean;
        for (size_t v = 0; v < nv; ++v) {
            if ((P0[v].y > 0) != (sd == 0)) continue;
            float lw = 0;   // (her leg only: a hand hanging beside the thigh stays)
            for (int k = 0; k < 4; ++k) {
                int rg = regOf[eve.bi[v * 4 + k]];
                if (rg == sd * kRegionsPerSide + kThigh || rg == sd * kRegionsPerSide + kCalf) lw += eve.bw[v * 4 + k] / 255.0f;
            }
            if (lw < 0.5f) continue;
            float a = (h.z - P0[v].z) / std::max(h.z - kn.z, 1e-4f);
            (void)ax;
            if (a < fit.split - 0.25f) continue;
            float t = std::clamp((a - (fit.split - 0.25f)) / 0.25f, 0.0f, 1.0f);
            t = t * t * (3 - 2 * t);
            V3 q = P[v];
            V3 f(cw.x + (q.x - cs.x) * ankleR[sd].rx * 0.97f / rxs, cw.y + (q.y - cs.y) * ankleR[sd].ry * 0.97f / rys, cw.z + (q.z - cs.z));
            P[v] = q * (1 - t) + f * t;
        }
    }
    // ---- the rings snapped onto the cut edge (its weights and normals), the move spread over the skin near them
    const char* ringNames[3] = {"neck", "L_ankle", "R_ankle"};
    const RingEdges* ringEdges[3] = {&neckR, &ankleR[0], &ankleR[1]};
    std::vector<uint8_t> anyRing(nv, 0);   // a ring's vertices are moved by their own ring only
    for (const char* rn : ringNames) if (const std::vector<uint32_t>* rr = eve.ring(rn)) for (uint32_t v : *rr) anyRing[v] = 1;
    for (int r = 0; r < 3; ++r) {
        const std::vector<uint32_t>* ring = eve.ring(ringNames[r]);
        const RingEdges& we = *ringEdges[r];
        if (!ring || ring->empty() || we.e.empty()) continue;
        // the ring's copies grouped by place
        std::map<int64_t, std::vector<uint32_t>> groups;
        for (uint32_t v : *ring) groups[weldKey(P0[v], 1e-5f)].push_back(v);
        std::vector<std::vector<uint32_t>> gs;
        for (auto& [k, g] : groups) gs.push_back(g);
        std::vector<V3> disp(gs.size()), nn(gs.size());
        std::vector<WMap> wm(gs.size());
        std::vector<uint8_t> isRing(nv, 0);
        float sum = 0, mx = 0;
        for (size_t gi = 0; gi < gs.size(); ++gi) {
            V3 p = P[gs[gi][0]];
            float best = 1e30f; V3 bq; size_t be = 0; float bt = 0;
            for (size_t ei = 0; ei < we.e.size(); ++ei) {
                V3 A = posOf(verts[we.e[ei].first]), B = posOf(verts[we.e[ei].second]), ab = B - A;
                float t = std::clamp((p - A).dot(ab) / std::max(ab.dot(ab), 1e-12f), 0.0f, 1.0f);
                V3 q = A + ab * t;
                float d = (q - p).length();
                if (d < best) { best = d; bq = q; be = ei; bt = t; }
            }
            disp[gi] = bq - p;
            sum += best; mx = std::max(mx, best);
            const M2Vertex& va = verts[we.e[be].first];
            const M2Vertex& vb = verts[we.e[be].second];
            WMap w;
            for (int k = 0; k < 4; ++k) { w.add(va.bones[k], va.weights[k] / 255.0f * (1 - bt)); w.add(vb.bones[k], vb.weights[k] / 255.0f * bt); }
            wm[gi] = w;
            nn[gi] = (V3(va.normal[0], va.normal[1], va.normal[2]) * (1 - bt) + V3(vb.normal[0], vb.normal[1], vb.normal[2]) * bt).normalized();
            for (uint32_t c : gs[gi]) isRing[c] = 1;
        }
        out.stats.ringSnapped[r] = gs.size();
        out.stats.ringMoveMean[r] = sum / float(gs.size()); out.stats.ringMoveMax[r] = mx;
        const float R = (r == 0 ? 0.05f : 0.12f) * unit / 1.15f;
        std::vector<V3> base(gs.size());
        for (size_t gi = 0; gi < gs.size(); ++gi) base[gi] = P[gs[gi][0]];
        for (size_t v = 0; v < nv; ++v) {
            if (isRing[v] || anyRing[v]) continue;
            if (r > 0 && (P0[v].y > 0) != (r == 1)) continue;   // an ankle's move stays on its own leg
            float bd = 1e30f; size_t bj = 0;
            for (size_t gi = 0; gi < gs.size(); ++gi) { float d = (base[gi] - P[v]).length(); if (d < bd) { bd = d; bj = gi; } }
            if (bd >= R) continue;
            float f = (1 - bd / R) * (1 - bd / R);
            P[v] = P[v] + disp[bj] * f;
            N[v] = (N[v] * (1 - f) + nn[bj] * f).normalized();
            WM[v] = blend(WM[v], wm[bj], f);
        }
        V3 cc; size_t cn = 0;
        for (size_t gi = 0; gi < gs.size(); ++gi) for (uint32_t c : gs[gi]) { P[c] = base[gi] + disp[gi]; N[c] = nn[gi]; WM[c] = wm[gi]; cc = cc + P[c]; ++cn; }
        if (cn) {
            // the ring tucked inside the Warcraft edge (the neck's tube; the foot's top, a little down into it): no hairline
            // where the two outlines differ between their vertices
            cc = cc * (1.0f / float(cn));
            float tuck = r == 0 ? fit.neckTuck : fit.ankleTuck;
            V3 lift = r == 0 ? V3(0, 0, fit.neckLift * unit) : V3(0, 0, -fit.ankleSink * unit);
            for (size_t gi = 0; gi < gs.size(); ++gi) for (uint32_t c : gs[gi]) {
                V3 rr = P[c] - cc; rr.z = 0;
                P[c] = P[c] - rr * tuck + lift;
            }
        }
    }
    // ---- the vertices: four weights at most (summing to 255)
    out.verts.resize(nv);
    for (size_t v = 0; v < nv; ++v) {
        M2Vertex& o = out.verts[v];
        o.pos[0] = P[v].x; o.pos[1] = P[v].y; o.pos[2] = P[v].z;
        o.normal[0] = N[v].x; o.normal[1] = N[v].y; o.normal[2] = N[v].z;
        o.uv[0] = eve.uv[v * 2]; o.uv[1] = eve.uv[v * 2 + 1];
        o.uv2[0] = eve.uv2.size() > v * 2 + 1 ? eve.uv2[v * 2] : 0.0f; o.uv2[1] = eve.uv2.size() > v * 2 + 1 ? eve.uv2[v * 2 + 1] : 0.0f;
        WMap w = WM[v];
        std::sort(w.e.begin(), w.e.begin() + w.n, [](auto& a, auto& b) { return a.second > b.second; });
        int k = std::min(w.n, 4);
        float tot = 0;
        for (int i = 0; i < k; ++i) tot += w.e[size_t(i)].second;
        int q[4] = {0, 0, 0, 0}, s = 0;
        for (int i = 0; i < k; ++i) { q[i] = tot > 0 ? int(std::floor(w.e[size_t(i)].second / tot * 255.0f + 0.5f)) : 0; s += q[i]; }
        if (k == 0) { q[0] = 255; s = 255; }
        q[0] += 255 - s;
        for (int i = 0; i < 4; ++i) {
            o.bones[i] = uint8_t(i < k ? std::clamp(w.e[size_t(i)].first, 0, 255) : 0);
            o.weights[i] = uint8_t(std::clamp(q[i], 0, 255));
        }
    }
    out.indices = eve.idx;
    out.scale = eveProportions(m, verts, indices, subs, geosets, out, fit, W[0].hip, W[1].hip, neckW, W[0].shoulder, W[0].elbow, W[0].wrist, out.stats);
    out.ok = true;
    out.stats.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return out;
}

EveMeasureMesh eveMeasureMesh(const M2Model& m, const std::vector<M2Vertex>& bodyVerts, const std::vector<uint32_t>& cutIndices,
                              const std::vector<M2Submesh>& cutSubs, const std::vector<uint16_t>& geosets, const EveBody& eve) {
    EveMeasureMesh out;
    bool all = geosets.empty() || (geosets.size() == 1 && geosets[0] == 0xFFFF);
    // the host: the largest shown section drawn opaque with the skin (texture type 1)
    size_t best = 0;
    for (const M2Batch& bt : m.batches) {
        if (bt.submesh >= cutSubs.size() || bt.textureCombo >= m.textureCombos.size()) continue;
        uint16_t ti = m.textureCombos[bt.textureCombo];
        if (ti >= m.textures.size() || m.textures[ti].type != 1) continue;
        if (bt.material < m.materials.size() && m.materials[bt.material].blend >= 2) continue;
        const M2Submesh& sm = cutSubs[bt.submesh];
        if (!all && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        size_t c = bt.submesh < m.submeshes.size() ? m.submeshes[bt.submesh].indexCount : sm.indexCount;
        if (out.host < 0 || c > best) { best = c; out.host = bt.submesh; }
    }
    if (out.host < 0) return out;
    out.verts = bodyVerts;
    out.verts.insert(out.verts.end(), eve.verts.begin(), eve.verts.end());
    const uint32_t base = uint32_t(bodyVerts.size());
    out.subs = cutSubs;
    for (size_t s = 0; s < cutSubs.size(); ++s) {
        const M2Submesh& sm = cutSubs[s];
        out.subs[s].indexStart = uint32_t(out.indices.size());
        for (uint32_t i = sm.indexStart; i < sm.indexStart + sm.indexCount && i < cutIndices.size(); ++i) out.indices.push_back(cutIndices[i]);
        if (int(s) == out.host) for (uint32_t i : eve.indices) out.indices.push_back(base + i);
        out.subs[s].indexCount = uint32_t(out.indices.size()) - out.subs[s].indexStart;
    }
    return out;
}

size_t eveBreastRegions(const M2Model& m, const EveBody& eve, std::vector<SoftRegion>& regions) {
    int bL = m.findWowBone("L_Breast"), bR = m.findWowBone("R_Breast");
    size_t changed = 0;
    for (SoftRegion& r : regions) {
        if (r.kind != SoftKind::Chest) continue;
        int b = r.centre.y >= 0 ? bL : bR;
        if (b < 0) continue;
        V3 lo(1e9f, 1e9f, 1e9f), hi(-1e9f, -1e9f, -1e9f), sum;
        float wsum = 0;
        for (const M2Vertex& v : eve.verts) {
            float w = 0;
            for (int k = 0; k < 4; ++k) if (v.bones[k] == b) w += v.weights[k] / 255.0f;
            if (w < 0.3f) continue;
            V3 p = posOf(v);
            lo = V3(std::min(lo.x, p.x), std::min(lo.y, p.y), std::min(lo.z, p.z));
            hi = V3(std::max(hi.x, p.x), std::max(hi.y, p.y), std::max(hi.z, p.z));
            sum = sum + p * w; wsum += w;
        }
        if (wsum <= 0) continue;
        V3 mean = sum * (1.0f / wsum);
        float depth = std::max(hi.x - lo.x, 0.01f);
        r.centre = V3(hi.x - depth, mean.y, mean.z);
        r.radii = V3(depth, std::max((hi.y - lo.y) * 0.55f, 0.01f), std::max((hi.z - lo.z) * 0.55f, 0.01f));
        r.mass = V3(hi.x - 0.45f * depth, mean.y, mean.z);
        r.size = r.radii.y;
        ++changed;
    }
    return changed;
}

// ================================================================================================ the texture

namespace {
struct Lut {
    float lin[256];
    uint8_t srgb[4096];
    Lut() {
        for (int i = 0; i < 256; ++i) lin[i] = eveToLinear(float(i) / 255.0f);
        for (int i = 0; i < 4096; ++i) srgb[i] = uint8_t(std::clamp(eveToSrgb(float(i) / 4095.0f), 0.0f, 1.0f) * 255.0f + 0.5f);
    }
    uint8_t toByte(float l) const { return srgb[std::clamp(int(l * 4095.0f + 0.5f), 0, 4095)]; }
};
const Lut& lut() { static const Lut l; return l; }

float lumAt(const std::vector<uint8_t>& rgba, int w, int h, float u, float v, int r) {
    const Lut& L = lut();
    int cx = std::clamp(int(u * w), 0, w - 1), cy = std::clamp(int(v * h), 0, h - 1);
    float s = 0; int n = 0;
    for (int y = std::max(0, cy - r); y <= std::min(h - 1, cy + r); ++y)
        for (int x = std::max(0, cx - r); x <= std::min(w - 1, cx + r); ++x) {
            const uint8_t* p = &rgba[(size_t(y) * w + x) * 4];
            s += 0.2126f * L.lin[p[0]] + 0.7152f * L.lin[p[1]] + 0.0722f * L.lin[p[2]]; ++n;
        }
    return n ? s / float(n) : 0;
}

// Calls f(x, y, b0, b1, b2) for each pixel centre of a w x h grid inside the triangle (uv a, b, c).
template <typename F> void raster(float ax, float ay, float bx, float by, float cx, float cy, int w, int h, F f) {
    float minx = std::floor(std::min({ax, bx, cx})), maxx = std::ceil(std::max({ax, bx, cx}));
    float miny = std::floor(std::min({ay, by, cy})), maxy = std::ceil(std::max({ay, by, cy}));
    float den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
    if (std::fabs(den) < 1e-12f) return;
    for (int y = std::max(0, int(miny)); y <= std::min(h - 1, int(maxy)); ++y)
        for (int x = std::max(0, int(minx)); x <= std::min(w - 1, int(maxx)); ++x) {
            float px = x + 0.5f, py = y + 0.5f;
            float b0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den;
            float b1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den;
            float b2 = 1 - b0 - b1;
            if (b0 < -1e-4f || b1 < -1e-4f || b2 < -1e-4f) continue;
            f(x, y, b0, b1, b2);
        }
}
} // namespace

void eveFrontBack(const EveMesh& m, const std::vector<uint8_t>& rgba, int w, int h, float& front, float& back) {
    float fs = 0, bs = 0; int fn = 0, bn = 0;
    for (size_t v = 0; v < m.vertexCount(); ++v) {
        float z = m.pos[v * 3 + 2], nx = m.nrm[v * 3];
        if (z < 1.0f || z > 1.45f) continue;
        float l = lumAt(rgba, w, h, m.uv[v * 2], m.uv[v * 2 + 1], 2);
        if (nx > 0.6f) { fs += l; ++fn; } else if (nx < -0.6f) { bs += l; ++bn; }
    }
    front = fn ? fs / float(fn) : 0; back = bn ? bs / float(bn) : 0;
}

EvePrep evePrepare(const EveMesh& m, const std::vector<uint8_t>& rgba, int w, int h) {
    EvePrep p;
    p.w = w; p.h = h;
    const size_t n = m.vertexCount();
    if (!n || rgba.size() < size_t(w) * h * 4) return p;
    eveFrontBack(m, rgba, w, h, p.frontBefore, p.backBefore);
    // each vertex's skin brightness, then its 6 cm neighbourhood's
    std::vector<float> L(n), S(n);
    for (size_t v = 0; v < n; ++v) L[v] = lumAt(rgba, w, h, m.uv[v * 2], m.uv[v * 2 + 1], 2);
    const float R = 0.06f;
    std::unordered_map<int64_t, std::vector<uint32_t>> grid;
    auto cell = [&](float f) { return int64_t(std::floor(f / R)); };
    auto key = [](int64_t x, int64_t y, int64_t z) { return (x & 0x1FFFFF) << 42 | (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF); };
    for (size_t v = 0; v < n; ++v) grid[key(cell(m.pos[v * 3]), cell(m.pos[v * 3 + 1]), cell(m.pos[v * 3 + 2]))].push_back(uint32_t(v));
    double G = 0;
    for (size_t v = 0; v < n; ++v) {
        V3 pv(m.pos[v * 3], m.pos[v * 3 + 1], m.pos[v * 3 + 2]);
        float s = 0; int c = 0;
        int64_t cx = cell(pv.x), cy = cell(pv.y), cz = cell(pv.z);
        for (int64_t dx = -1; dx <= 1; ++dx) for (int64_t dy = -1; dy <= 1; ++dy) for (int64_t dz = -1; dz <= 1; ++dz) {
            auto it = grid.find(key(cx + dx, cy + dy, cz + dz));
            if (it == grid.end()) continue;
            for (uint32_t u : it->second) {
                V3 q(m.pos[u * 3], m.pos[u * 3 + 1], m.pos[u * 3 + 2]);
                if ((q - pv).length() <= R) { s += L[u]; ++c; }
            }
        }
        S[v] = c ? s / float(c) : L[v];
        G += S[v];
    }
    G /= double(n);
    std::vector<float> g(n);
    for (size_t v = 0; v < n; ++v) g[v] = std::clamp(std::pow(float(G) / std::max(S[v], 1e-4f), 0.8f), 0.85f, 1.3f);
    // the factors over the texture: an eighth of its size, each cell the mean of the triangles' interpolated factors
    p.gw = std::max(1, w / 8); p.gh = std::max(1, h / 8);
    std::vector<float> acc(size_t(p.gw) * p.gh, 0), cnt(size_t(p.gw) * p.gh, 0);
    for (size_t t = 0; t + 2 < m.idx.size(); t += 3) {
        uint32_t a = m.idx[t], b = m.idx[t + 1], c = m.idx[t + 2];
        raster(m.uv[a * 2] * p.gw, m.uv[a * 2 + 1] * p.gh, m.uv[b * 2] * p.gw, m.uv[b * 2 + 1] * p.gh, m.uv[c * 2] * p.gw, m.uv[c * 2 + 1] * p.gh, p.gw, p.gh,
               [&](int x, int y, float b0, float b1, float b2) { size_t i = size_t(y) * p.gw + x; acc[i] += g[a] * b0 + g[b] * b1 + g[c] * b2; cnt[i] += 1; });
        // tiny triangles that cover no cell centre still mark their cell
        int x = std::clamp(int((m.uv[a * 2] + m.uv[b * 2] + m.uv[c * 2]) / 3 * p.gw), 0, p.gw - 1);
        int y = std::clamp(int((m.uv[a * 2 + 1] + m.uv[b * 2 + 1] + m.uv[c * 2 + 1]) / 3 * p.gh), 0, p.gh - 1);
        size_t i = size_t(y) * p.gw + x;
        if (cnt[i] == 0) { acc[i] += (g[a] + g[b] + g[c]) / 3; cnt[i] += 1; }
    }
    p.gain.assign(size_t(p.gw) * p.gh, 0);
    std::vector<uint8_t> have(p.gain.size(), 0);
    for (size_t i = 0; i < p.gain.size(); ++i) if (cnt[i] > 0) { p.gain[i] = acc[i] / cnt[i]; have[i] = 1; }
    for (int pass = 0; pass < 6; ++pass) {   // grown past the islands' edges, so the bilinear lookup never reaches an empty cell
        std::vector<float> ng = p.gain;
        std::vector<uint8_t> nh = have;
        for (int y = 0; y < p.gh; ++y) for (int x = 0; x < p.gw; ++x) {
            size_t i = size_t(y) * p.gw + x;
            if (have[i]) continue;
            float s = 0; int c = 0;
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= p.gw || yy >= p.gh) continue;
                size_t j = size_t(yy) * p.gw + xx;
                if (have[j]) { s += p.gain[j]; ++c; }
            }
            if (c) { ng[i] = s / float(c); nh[i] = 1; }
        }
        p.gain.swap(ng); have.swap(nh);
    }
    for (size_t i = 0; i < p.gain.size(); ++i) if (!have[i]) p.gain[i] = 1;
    // the seams' texels: each vertex's distance to its nearest ring vertex, interpolated over the triangles near a ring
    std::vector<float> dist(n, 1e9f);
    std::vector<uint8_t> which(n, 0);
    for (const auto& rg : m.rings) {
        uint8_t ri = rg.first == "neck" ? 0 : 1;
        for (size_t v = 0; v < n; ++v) {
            V3 pv(m.pos[v * 3], m.pos[v * 3 + 1], m.pos[v * 3 + 2]);
            for (uint32_t r : rg.second) {
                float d = (V3(m.pos[r * 3], m.pos[r * 3 + 1], m.pos[r * 3 + 2]) - pv).length();
                if (d < dist[v]) { dist[v] = d; which[v] = ri; }
            }
        }
    }
    std::unordered_map<uint32_t, std::pair<float, uint8_t>> seam;
    for (size_t t = 0; t + 2 < m.idx.size(); t += 3) {
        uint32_t a = m.idx[t], b = m.idx[t + 1], c = m.idx[t + 2];
        if (std::min({dist[a], dist[b], dist[c]}) > std::max(kEveFeather, m.ankleFeather)) continue;
        uint8_t ri = which[dist[a] <= dist[b] && dist[a] <= dist[c] ? a : dist[b] <= dist[c] ? b : c];
        const float fr = ri ? m.ankleFeather : kEveFeather;   // (kept as a share of kEveFeather: eveCompose divides by it)
        raster(m.uv[a * 2] * w, m.uv[a * 2 + 1] * h, m.uv[b * 2] * w, m.uv[b * 2 + 1] * h, m.uv[c * 2] * w, m.uv[c * 2 + 1] * h, w, h,
               [&](int x, int y, float b0, float b1, float b2) {
                   float d = std::min(dist[a], 1.0f) * b0 + std::min(dist[b], 1.0f) * b1 + std::min(dist[c], 1.0f) * b2;
                   if (d >= fr) return;
                   d *= kEveFeather / fr;
                   uint32_t i = uint32_t(y) * uint32_t(w) + uint32_t(x);
                   auto it = seam.find(i);
                   if (it == seam.end() || d < it->second.first) seam[i] = {d, ri};
               });
    }
    p.seam.reserve(seam.size());
    for (auto& [i, dv] : seam) p.seam.push_back({i, dv.first, dv.second});
    std::sort(p.seam.begin(), p.seam.end(), [](const EveSeamTexel& a, const EveSeamTexel& b) { return a.texel < b.texel; });
    std::vector<uint8_t> after = rgba;
    eveApplyCorrection(p, after);
    eveFrontBack(m, after, w, h, p.frontAfter, p.backAfter);
    // the references the tint is measured against: the skin as drawn near each ring
    for (int ri = 0; ri < 2; ++ri) {
        std::vector<float> uvs;
        for (size_t v = 0; v < n; ++v) if (dist[v] < 0.04f && which[v] == ri) { uvs.push_back(m.uv[v * 2]); uvs.push_back(m.uv[v * 2 + 1]); }
        int c = eveSampleAt(after.data(), w, h, uvs);
        (ri ? p.ankle : p.neck) = c >= 0 ? uint32_t(c) : 0;
    }
    return p;
}

void eveApplyCorrection(const EvePrep& p, std::vector<uint8_t>& rgba) {
    if (p.gain.empty() || rgba.size() < size_t(p.w) * p.h * 4) return;
    const Lut& L = lut();
    for (int y = 0; y < p.h; ++y) {
        float gy = (y + 0.5f) / float(p.h) * p.gh - 0.5f;
        int y0 = std::clamp(int(std::floor(gy)), 0, p.gh - 1), y1 = std::min(y0 + 1, p.gh - 1);
        float fy = std::clamp(gy - float(y0), 0.0f, 1.0f);
        for (int x = 0; x < p.w; ++x) {
            float gx = (x + 0.5f) / float(p.w) * p.gw - 0.5f;
            int x0 = std::clamp(int(std::floor(gx)), 0, p.gw - 1), x1 = std::min(x0 + 1, p.gw - 1);
            float fx = std::clamp(gx - float(x0), 0.0f, 1.0f);
            float g = (p.gain[size_t(y0) * p.gw + x0] * (1 - fx) + p.gain[size_t(y0) * p.gw + x1] * fx) * (1 - fy) +
                      (p.gain[size_t(y1) * p.gw + x0] * (1 - fx) + p.gain[size_t(y1) * p.gw + x1] * fx) * fy;
            uint8_t* q = &rgba[(size_t(y) * p.w + x) * 4];
            for (int c = 0; c < 3; ++c) q[c] = L.toByte(L.lin[q[c]] * g);
        }
    }
}

void eveCompose(const std::vector<uint8_t>& base, int w, int h, V3 gain, const uint8_t* ov, int ow, int oh, int ox, int oy,
                float opacity, V3 ovGain, const EvePrep& prep, uint32_t neckRgb, uint32_t ankleRgb, std::vector<uint8_t>& out) {
    const Lut& L = lut();
    out.resize(size_t(w) * h * 4);
    uint8_t tab[3][256];
    float g[3] = {gain.x, gain.y, gain.z};
    for (int c = 0; c < 3; ++c) for (int i = 0; i < 256; ++i) tab[c][i] = L.toByte(L.lin[i] * g[c]);
    const size_t n = size_t(w) * h;
    for (size_t i = 0; i < n; ++i) {
        const uint8_t* s = &base[i * 4];
        uint8_t* d = &out[i * 4];
        d[0] = tab[0][s[0]]; d[1] = tab[1][s[1]]; d[2] = tab[2][s[2]]; d[3] = 255;
    }
    if (ov && ow > 0 && oh > 0 && opacity > 0) {
        for (int y = 0; y < oh; ++y) {
            int ty = oy + y;
            if (ty < 0 || ty >= h) continue;
            for (int x = 0; x < ow; ++x) {
                int tx = ox + x;
                if (tx < 0 || tx >= w) continue;
                const uint8_t* o = &ov[(size_t(y) * ow + x) * 4];
                float a = o[3] / 255.0f * opacity;
                if (a <= 0) continue;
                uint8_t* d = &out[(size_t(ty) * w + tx) * 4];
                const float og[3] = {ovGain.x, ovGain.y, ovGain.z};
                for (int c = 0; c < 3; ++c) d[c] = L.toByte(L.lin[d[c]] * (1 - a) + L.lin[o[c]] * og[c] * a);
            }
        }
    }
    V3 tl[2] = {eveLinear(neckRgb), eveLinear(ankleRgb)};
    for (const EveSeamTexel& t : prep.seam) {
        if (t.texel >= n) continue;
        float f = std::clamp(t.d / kEveFeather, 0.0f, 1.0f);
        f = f * f * (3 - 2 * f);
        uint8_t* d = &out[size_t(t.texel) * 4];
        const V3& c = tl[t.ring ? 1 : 0];
        d[0] = L.toByte(c.x * (1 - f) + L.lin[d[0]] * f);
        d[1] = L.toByte(c.y * (1 - f) + L.lin[d[1]] * f);
        d[2] = L.toByte(c.z * (1 - f) + L.lin[d[2]] * f);
    }
}

int eveSampleAt(const uint8_t* rgba, int w, int h, const std::vector<float>& uvs) {
    const Lut& L = lut();
    double r = 0, g = 0, b = 0; size_t n = 0;
    for (size_t i = 0; i + 1 < uvs.size(); i += 2) {
        int cx = int(uvs[i] * w), cy = int(uvs[i + 1] * h);
        for (int y = std::max(0, cy - 2); y <= std::min(h - 1, cy + 2); ++y)
            for (int x = std::max(0, cx - 2); x <= std::min(w - 1, cx + 2); ++x) {
                const uint8_t* p = &rgba[(size_t(y) * w + x) * 4];
                if (p[3] < 128) continue;
                r += L.lin[p[0]]; g += L.lin[p[1]]; b += L.lin[p[2]]; ++n;
            }
    }
    if (!n) return -1;
    return int(eveRgb(V3(float(r / n), float(g / n), float(b / n))));
}

// ================================================================================================ the fit off the GL thread

namespace {

bool readPng(const std::string& path, std::vector<uint8_t>& rgba, int& w, int& h, std::string& error) {
    Bytes b;
    if (!readFile(path, b)) { error = "can't be read"; return false; }
    return decodePng(b.data(), b.size(), rgba, w, h, error);
}

// A skin texture of the set (corrected once per file and mesh) or an overlay (as it is), from the set's cache or read now.
std::shared_ptr<EveSet::Tex> eveSkinTex(EveSet& set, const EveMesh& mesh, const EveTexInfo& skin, std::string& why) {
    const std::string key = skin.file + "|" + mesh.variant;
    {
        std::lock_guard<std::mutex> lock(set.cacheMutex);
        for (auto& t : set.textures) if (t.first == key) return t.second;
    }
    auto tex = std::make_shared<EveSet::Tex>();
    if (!readPng(set.dir + "/" + skin.file, tex->rgba, tex->w, tex->h, why)) return nullptr;
    tex->prep = evePrepare(mesh, tex->rgba, tex->w, tex->h);
    eveApplyCorrection(tex->prep, tex->rgba);
    std::fprintf(stderr, "eve skin %s: front/back brightness %.3f/%.3f before the correction, %.3f/%.3f after\n", skin.file.c_str(),
                 tex->prep.frontBefore, tex->prep.backBefore, tex->prep.frontAfter, tex->prep.backAfter);
    std::lock_guard<std::mutex> lock(set.cacheMutex);
    for (auto& t : set.textures) if (t.first == key) return t.second;   // (made meanwhile by the other thread: the same bytes)
    set.textures.push_back({key, tex});
    if (set.textures.size() > 3) set.textures.erase(set.textures.begin());   // (5 MB each)
    return tex;
}

std::shared_ptr<EveSet::Tex> eveOverlayTex(EveSet& set, const std::string& file) {
    {
        std::lock_guard<std::mutex> lock(set.cacheMutex);
        for (auto& t : set.overlays) if (t.first == file) return t.second;
    }
    auto ov = std::make_shared<EveSet::Tex>();
    std::string why;
    if (!readPng(set.dir + "/" + file, ov->rgba, ov->w, ov->h, why)) { std::fprintf(stderr, "eve overlay %s %s\n", file.c_str(), why.c_str()); return nullptr; }
    std::lock_guard<std::mutex> lock(set.cacheMutex);
    for (auto& t : set.overlays) if (t.first == file) return t.second;
    set.overlays.push_back({file, ov});
    if (set.overlays.size() > 8) set.overlays.erase(set.overlays.begin());
    return ov;
}

} // namespace

EveResult eveCompute(const EveJob& job) {
    auto t0 = std::chrono::steady_clock::now();
    EveResult r;
    r.serial = job.serial; r.key = job.key; r.base = job.base;
    if (!job.model || !job.set || !job.mesh) { r.status = "nothing to fit"; return r; }
    const M2Model& m = *job.model;
    EveSet& set = *job.set;
    std::shared_ptr<EveMesh> splitMesh;
    if (job.fit.split > 0) {   // the split fit: Eve trimmed at mid-thigh (made once per set, mesh and cut)
        char tag[32];
        std::snprintf(tag, sizeof tag, "|split%.3f", job.fit.split);
        const std::string key = job.mesh->variant + tag;
        {
            std::lock_guard<std::mutex> lock(set.cacheMutex);
            for (auto& sp : set.splits) if (sp.first == key) splitMesh = sp.second;
        }
        if (!splitMesh) {
            splitMesh = std::make_shared<EveMesh>(eveSplitMesh(*job.mesh, job.fit.split));
            std::lock_guard<std::mutex> lock(set.cacheMutex);
            set.splits.push_back({key, splitMesh});
            if (set.splits.size() > 4) set.splits.erase(set.splits.begin());
        }
    }
    const EveMesh& mesh = splitMesh ? *splitMesh : *job.mesh;
    const std::vector<M2Vertex>& verts = job.verts.empty() ? m.vertices : job.verts;
    EveBody body = fitEve(m, verts, job.indices, job.subs, job.geosets, mesh, job.fit);
    if (!body.ok) { r.status = body.error; return r; }
    // the creator's sculpt sliders (sculpt.h) on the fitted body, before it is measured, cut and drawn
    if (sculptActive(job.sculpt.data(), job.sculpt.size()))
        applySculpt(job.rig, body.verts, body.indices, job.sculpt.data(), job.sculpt.size());
    auto t1 = std::chrono::steady_clock::now();
    // the actor's own skin where Eve meets it: its base skin layer sampled at the cut edges
    const EveSetIndex& ix = set.index;
    uint32_t neck = ix.skin.neck, ankle = ix.skin.ankle;
    {
        Bytes blp;
        Image img;
        if (!job.skinPath.empty() && readFile(job.skinPath, blp) && decodeBlp(blp, 512, img)) {
            int n = eveSampleAt(img.rgba.data(), img.width, img.height, body.neckUvs);
            int k = eveSampleAt(img.rgba.data(), img.width, img.height, body.ankleUvs);
            auto tinted = [&](int c) {
                float rr = float((c >> 16) & 255) * job.tint[0], g = float((c >> 8) & 255) * job.tint[1], bl = float(c & 255) * job.tint[2];
                return uint32_t(std::clamp(rr, 0.0f, 255.0f)) << 16 | uint32_t(std::clamp(g, 0.0f, 255.0f)) << 8 | uint32_t(std::clamp(bl, 0.0f, 255.0f));
            };
            if (n >= 0) neck = tinted(n);
            if (k >= 0) ankle = tinted(k); else if (n >= 0) ankle = neck;
        }
    }
    // the skin texture: the neutral skin tinted to that colour, or a chosen tone as it is (corrected once per file)
    const EveTexInfo* skin = ix.hasSkin ? &ix.skin : nullptr;
    const bool tone = job.opt.tone >= 0 && size_t(job.opt.tone) < ix.tones.size();
    if (tone) skin = &ix.tones[size_t(job.opt.tone)];
    if (!skin) { r.status = "the Eve set has no skin"; return r; }
    std::string why;
    std::shared_ptr<EveSet::Tex> tex = eveSkinTex(set, mesh, *skin, why);
    if (!tex) { r.status = "the skin " + skin->file + " " + why; return r; }
    // the reference: the corrected skin's own colour near its neck ring (else set.json's)
    r.reference = tex->prep.neck ? tex->prep.neck : (skin->neck ? skin->neck : skin->mean);
    V3 gain = tone ? V3(1, 1, 1) : eveGain(neck, r.reference);
    // the overlay: its shade nearest the skin (Lab), its brightness scaled to the skin's
    std::shared_ptr<EveSet::Tex> ov;
    const EveShadeInfo* shade = nullptr;
    if (job.opt.overlaySlot >= 0 && size_t(job.opt.overlaySlot) < ix.overlays.size()) {
        const EveSlotInfo& slot = ix.overlays[size_t(job.opt.overlaySlot)];
        uint32_t skinRgb = tone ? skin->mean : neck;
        int sh = job.opt.overlayShade >= 0 && size_t(job.opt.overlayShade) < slot.shades.size() ? job.opt.overlayShade
                                                                                                 : eveNearestShade(skinRgb, slot.shades, &r.shadeDist);
        if (sh >= 0) {
            shade = &slot.shades[size_t(sh)];
            r.shade = sh;
            ov = eveOverlayTex(set, shade->file);
        }
    }
    // its colour: brightness only when a shade was made for a skin like this one (Lab distance under 12), so its own pinks and
    // browns stay; for a skin no shade was made for (green, blue, grey), the full per-channel tint, fading in up to 30
    V3 ovGain(1, 1, 1);
    if (shade) {
        uint32_t want = tone ? skin->mean : neck;
        V3 t = eveLinear(want), sw = eveLinear(shade->swatch);
        float yt = 0.2126f * t.x + 0.7152f * t.y + 0.0722f * t.z, ys = 0.2126f * sw.x + 0.7152f * sw.y + 0.0722f * sw.z;
        float bright = std::clamp(yt / std::max(ys, 1e-4f), 0.5f, 2.0f);
        V3 full = eveGain(want, shade->swatch);
        float d = (eveLab(want) - eveLab(shade->swatch)).length();
        float f = std::clamp((d - 12.0f) / 18.0f, 0.0f, 1.0f);
        ovGain = V3(bright, bright, bright) * (1 - f) + full * f;
    }
    eveCompose(tex->rgba, tex->w, tex->h, gain, ov ? ov->rgba.data() : nullptr, ov ? ov->w : 0, ov ? ov->h : 0, shade ? shade->rect[0] : 0,
               shade ? shade->rect[1] : 0, std::clamp(job.opt.opacity, 0.0f, 1.0f), ovGain, tex->prep, neck, ankle, r.rgba);
    r.w = tex->w; r.h = tex->h;
    if (job.mips) r.mips = eveMips(r.rgba, r.w, r.h);
    r.neckRgb = neck; r.ankleRgb = ankle;
    r.composeMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();
    // the body under it cut (any corner on a body bone drops the triangle), and the two measured as one
    r.cutSubs = job.subs;
    r.cutIndices = cutTriangles(job.indices, r.cutSubs, body.cut, 1);
    r.measure = eveMeasureMesh(m, verts, r.cutIndices, r.cutSubs, job.geosets, body);
    r.body = std::move(body);
    r.ok = true;
    r.totalMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return r;
}

std::vector<std::vector<uint8_t>> eveMips(const std::vector<uint8_t>& rgba, int w, int h) {
    std::vector<std::vector<uint8_t>> out;
    const std::vector<uint8_t>* src = &rgba;
    while ((w > 1 || h > 1) && src->size() >= size_t(w) * h * 4) {
        int nw = std::max(1, w / 2), nh = std::max(1, h / 2);
        std::vector<uint8_t> d(size_t(nw) * nh * 4);
        for (int y = 0; y < nh; ++y)
            for (int x = 0; x < nw; ++x) {
                int x0 = std::min(2 * x, w - 1), x1 = std::min(2 * x + 1, w - 1), y0 = std::min(2 * y, h - 1), y1 = std::min(2 * y + 1, h - 1);
                for (int c = 0; c < 4; ++c) {
                    int sum = (*src)[(size_t(y0) * w + x0) * 4 + c] + (*src)[(size_t(y0) * w + x1) * 4 + c] + (*src)[(size_t(y1) * w + x0) * 4 + c] +
                              (*src)[(size_t(y1) * w + x1) * 4 + c];
                    d[(size_t(y) * nw + x) * 4 + c] = uint8_t((sum + 2) / 4);
                }
            }
        out.push_back(std::move(d));
        src = &out.back();
        w = nw; h = nh;
    }
    return out;
}

EveWorker::EveWorker() : thread_([this] { run(); }) {}

EveWorker::~EveWorker() {
    {
        std::lock_guard<std::mutex> lock(mu_);
        stop_ = true;
        queue_.clear();
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void EveWorker::submit(std::unique_ptr<EveJob> job) {
    if (!job) return;
    {
        std::lock_guard<std::mutex> lock(mu_);
        for (auto& q : queue_) if (q->serial == job->serial) { q = std::move(job); break; }
        if (job) queue_.push_back(std::move(job));
    }
    cv_.notify_one();
}

void EveWorker::cancel(uint64_t serial) {
    std::lock_guard<std::mutex> lock(mu_);
    queue_.erase(std::remove_if(queue_.begin(), queue_.end(), [&](const std::unique_ptr<EveJob>& q) { return q->serial == serial; }), queue_.end());
    done_.erase(std::remove_if(done_.begin(), done_.end(), [&](const std::unique_ptr<EveResult>& d) { return d->serial == serial; }), done_.end());
}

std::unique_ptr<EveResult> EveWorker::take(uint64_t serial) {
    std::lock_guard<std::mutex> lock(mu_);
    std::unique_ptr<EveResult> out;
    for (auto it = done_.begin(); it != done_.end();) {
        if ((*it)->serial == serial) { out = std::move(*it); it = done_.erase(it); } else ++it;   // (the newest wins)
    }
    return out;
}

bool EveWorker::idle() {
    std::lock_guard<std::mutex> lock(mu_);
    return queue_.empty() && !running_;
}

void EveWorker::run() {
    for (;;) {
        std::unique_ptr<EveJob> job;
        {
            std::unique_lock<std::mutex> lock(mu_);
            cv_.wait(lock, [&] { return stop_ || !queue_.empty(); });
            if (stop_) return;
            job = std::move(queue_.front());
            queue_.pop_front();
            running_ = job->serial;
        }
        auto res = std::make_unique<EveResult>(eveCompute(*job));
        job.reset();
        std::lock_guard<std::mutex> lock(mu_);
        running_ = 0;
        // one result kept per actor (the newest), and a few at most: an actor gone never takes its own
        const uint64_t serial = res->serial;
        done_.erase(std::remove_if(done_.begin(), done_.end(), [&](const std::unique_ptr<EveResult>& d) { return d->serial == serial; }), done_.end());
        if (done_.size() >= 8) done_.erase(done_.begin());
        done_.push_back(std::move(res));
    }
}

} // namespace wc
