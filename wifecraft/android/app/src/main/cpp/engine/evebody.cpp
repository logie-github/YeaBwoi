#include "evebody.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <tuple>
#include <unordered_map>

#include <zlib.h>

#include "blp.h"
#include "bodypart.h"
#include "bytes.h"
#include "json.h"
#include "modelpart.h"

namespace wc {

// ================================================================================================ the .weve file

// Two more loops of vertices above each ankle ring (at `t` of the way to the next vertices up, then `t` again of that):
// Eve's shin has none for 10 cm above the ring, so it ran straight from the ring to there and couldn't follow the foot's
// edge flaring out (fitEve's ankleMatch). Each edge from a ring vertex to a vertex off the ring is split, its attributes
// (place, normal, both UV sets, bone weights) interpolated; the rings' indices and every other vertex are unchanged, the new
// vertices appended.
EveMesh eveRefineAnkles(const EveMesh& m, float t) {
    EveMesh o = m;
    char tag[32];
    std::snprintf(tag, sizeof tag, "|ankles%.2f", t);
    o.variant = m.variant + tag;
    auto key = [&](const EveMesh& e, uint32_t v) {
        return std::make_tuple(std::lround(e.pos[v * 3] * 1e5), std::lround(e.pos[v * 3 + 1] * 1e5), std::lround(e.pos[v * 3 + 2] * 1e5));
    };
    std::vector<uint32_t> ringIdx;
    for (const char* rn : {"L_ankle", "R_ankle"}) if (const std::vector<uint32_t>* r = m.ring(rn)) ringIdx.insert(ringIdx.end(), r->begin(), r->end());
    if (ringIdx.empty()) return o;
    std::vector<uint8_t> band(m.vertexCount(), 0);   // 1: the ring (by place, its UV copies too)
    {
        std::set<std::tuple<long, long, long>> places;
        for (uint32_t v : ringIdx) if (v < m.vertexCount()) places.insert(key(m, v));
        for (size_t v = 0; v < m.vertexCount(); ++v) if (places.count(key(m, uint32_t(v)))) band[v] = 1;
    }
    for (int pass = 0; pass < 2; ++pass) {
        std::map<std::pair<uint32_t, uint32_t>, uint32_t> made;
        auto split = [&](uint32_t a, uint32_t b) {   // a on the band's lower edge, b above
            auto it = made.find({a, b});
            if (it != made.end()) return it->second;
            uint32_t n = uint32_t(o.vertexCount());
            for (int k = 0; k < 3; ++k) o.pos.push_back(o.pos[a * 3 + k] + (o.pos[b * 3 + k] - o.pos[a * 3 + k]) * t);
            float nn[3], len = 0;
            for (int k = 0; k < 3; ++k) { nn[k] = o.nrm[a * 3 + k] + (o.nrm[b * 3 + k] - o.nrm[a * 3 + k]) * t; len += nn[k] * nn[k]; }
            len = std::sqrt(std::max(len, 1e-12f));
            for (int k = 0; k < 3; ++k) o.nrm.push_back(nn[k] / len);
            for (int k = 0; k < 2; ++k) o.uv.push_back(o.uv[a * 2 + k] + (o.uv[b * 2 + k] - o.uv[a * 2 + k]) * t);
            for (int k = 0; k < 2; ++k) o.uv2.push_back(o.uv2.size() >= size_t(b) * 2 + 2 ? o.uv2[a * 2 + k] + (o.uv2[b * 2 + k] - o.uv2[a * 2 + k]) * t : 0.0f);
            std::map<uint8_t, float> w;
            for (int k = 0; k < 4; ++k) { w[o.bi[a * 4 + k]] += o.bw[a * 4 + k] * (1 - t); w[o.bi[b * 4 + k]] += o.bw[b * 4 + k] * t; }
            std::vector<std::pair<float, uint8_t>> ws;
            for (auto& [bone, x] : w) if (x > 0) ws.push_back({x, bone});
            std::sort(ws.rbegin(), ws.rend());
            ws.resize(std::min<size_t>(ws.size(), 4));
            float tot = 0; for (auto& x : ws) tot += x.first;
            int given = 0;
            for (int k = 0; k < 4; ++k) {
                uint8_t bone = k < int(ws.size()) ? ws[size_t(k)].second : 0;
                int wv = k < int(ws.size()) && tot > 0 ? int(std::lround(ws[size_t(k)].first / tot * 255)) : 0;
                if (k == int(ws.size()) - 1) wv = 255 - given;
                given += wv;
                o.bi.push_back(bone); o.bw.push_back(uint8_t(std::clamp(wv, 0, 255)));
            }
            made[{a, b}] = n;
            return n;
        };
        std::vector<uint32_t> idx;
        for (size_t i = 0; i + 2 < o.idx.size(); i += 3) {
            uint32_t v[3] = {o.idx[i], o.idx[i + 1], o.idx[i + 2]};
            int on = band[v[0]] + band[v[1]] + band[v[2]];
            if (on == 0 || on == 3) { idx.insert(idx.end(), v, v + 3); continue; }
            // rotated so the odd one out is last: two on the band (a, b) and c above, or one (a) and b, c above
            int r = 0;
            if (on == 2) { while (band[v[(r + 2) % 3]]) ++r; } else { while (!band[v[r]]) ++r; }
            uint32_t a = v[r], b = v[(r + 1) % 3], c = v[(r + 2) % 3];
            if (on == 2) {
                uint32_t p = split(a, c), q = split(b, c);
                for (uint32_t x : {a, b, q, a, q, p, p, q, c}) idx.push_back(x);
            } else {
                uint32_t p = split(a, b), q = split(a, c);
                for (uint32_t x : {a, p, q, p, b, c, p, c, q}) idx.push_back(x);
            }
        }
        o.idx.swap(idx);
        band.resize(o.vertexCount(), 0);   // (the next pass: the ring again, its band now up to the new loop)
    }
    return o;
}

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
        num("split", f.split); num("transfer", f.transfer); num("stitch", f.stitch); num("detail", f.detail);
        num("rezone", f.rezone); num("limbs", f.limbs); num("rig", f.rig); num("lengths", f.lengths); num("spine", f.spine); num("ankleMatch", f.ankleMatch);
        if (k.has("bulk")) {
            const Json& b = k["bulk"];
            auto bn = [&](const char* key, float& v) { if (b.has(key)) v = float(b[key].num(v)); };
            bn("arm", f.bulk.arm); bn("forearm", f.bulk.forearm); bn("thigh", f.bulk.thigh); bn("calf", f.bulk.calf);
            bn("shoulders", f.bulk.shoulders); bn("chest", f.bulk.chest); bn("waist", f.bulk.waist); bn("hips", f.bulk.hips);
            bn("chestDepth", f.bulk.chestDepth); bn("waistDepth", f.bulk.waistDepth); bn("hipDepth", f.bulk.hipDepth); bn("follow", f.bulk.follow); bn("inset", f.bulk.inset);
        }
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

// The Sims 4 adult rig's joints (its bind pose, in Warcraft axes and Sims metres from the feet; read from the game's own rig
// resource, the one Eve and every CAS body is skinned for), by the Warcraft name her weights carry (EveProfile.wowBone); left
// side, the right mirrored. Twist and elbow helpers sit at their own joints.
const std::pair<const char*, V3> kEveSimsRig[] = {
    {"Pelvis", V3(0.0000f, 0.0000f, 1.1232f)}, {"Spine1", V3(0.0000f, 0.0000f, 1.1292f)}, {"Spine2", V3(0.0000f, 0.0000f, 1.2912f)},
    {"Chest", V3(0.0000f, 0.0000f, 1.4507f)}, {"Neck1", V3(-0.0361f, 0.0000f, 1.6066f)},
    {"L_Clavicle", V3(-0.0206f, 0.0200f, 1.5230f)}, {"L_UpperArm", V3(-0.0206f, 0.1600f, 1.5230f)}, {"L_Forearm", V3(-0.0366f, 0.3871f, 1.2967f)},
    {"L_Hand", V3(-0.0204f, 0.5902f, 1.0943f)}, {"L_Thumb1", V3(0.0090f, 0.5987f, 1.0776f)}, {"L_Thumb2", V3(0.0287f, 0.6041f, 1.0543f)},
    {"L_Index1", V3(0.0190f, 0.6396f, 1.0437f)}, {"L_Index2", V3(0.0238f, 0.6706f, 1.0128f)}, {"L_Middle1", V3(-0.0032f, 0.6444f, 1.0425f)},
    {"L_Middle2", V3(-0.0006f, 0.6760f, 1.0091f)}, {"L_Ring1", V3(-0.0253f, 0.6448f, 1.0419f)}, {"L_Ring2", V3(-0.0227f, 0.6746f, 1.0096f)},
    {"L_Pinky1", V3(-0.0443f, 0.6406f, 1.0433f)}, {"L_Pinky2", V3(-0.0426f, 0.6686f, 1.0147f)},
    {"L_Thigh", V3(0.0000f, 0.0660f, 1.0122f)}, {"L_Calf", V3(0.0126f, 0.0837f, 0.5287f)}, {"L_Foot", V3(-0.0097f, 0.0991f, 0.1106f)},
    {"L_Breast", V3(0.0919f, 0.0740f, 1.4190f)},
    {"L_UpperArmTwist", V3(-0.0206f, 0.1600f, 1.5230f)}, {"L_ForearmTwist", V3(-0.0287f, 0.4870f, 1.1971f)}, {"L_ElbowHelper", V3(-0.0366f, 0.3871f, 1.2967f)},
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
    if (!(H > 0.02f) || !(crown > H * 5)) return k;   // (a "head" over a fifth of her: no head measured)
    if (!(fit.heads > 0) && !(fit.hipRatio > 0)) return k;
    // her hand as fitted: the farthest of Eve's vertices the hand's bones carry, from the wrist
    float handLen = 0;
    for (const M2Vertex& v : body.verts) {
        int best = 0;
        for (int q = 1; q < 4; ++q) if (v.weights[q] > v.weights[best]) best = q;
        if (under(v.bones[best], hand)) handLen = std::max(handLen, (V3(v.pos[0], v.pos[1], v.pos[2]) - wrist).length());
    }
    // her height: `heads` of the model's head heights, or (0) the model's own: she stands as tall as the body she replaces
    const float total = fit.heads > 0 ? fit.heads * H : crown, hipZ = (hipL.z + hipR.z) * 0.5f, above = crown - neck.z;
    const float hip2 = fit.hipRatio > 0 ? std::clamp(fit.hipRatio, 0.3f, 0.7f) * total : hipZ * total / crown;
    k.legs = std::clamp(hip2 / std::max(hipZ, 1e-3f), 0.6f, 1.3f);
    const float neck2 = total - above;
    k.torso = std::clamp((neck2 - hipZ * k.legs) / std::max(neck.z - hipZ, 1e-3f), 0.7f, 1.3f);
    if (handLen > 1e-3f && fit.handHeads > 0) k.hands = std::clamp(fit.handHeads * H / handLen, 0.6f, 1.3f);
    const float upper = (elbow - shoulder).length() + (wrist - elbow).length();
    const float arm0 = upper + handLen;   // (the model's own reach, scaled with her height when no ratio is asked)
    const float armWant = fit.armRatio > 0 ? fit.armRatio * total : arm0 * total / crown;
    if (upper > 1e-3f && fit.armRatio > 0) k.arms = std::clamp((armWant - handLen * k.hands) / upper, 0.6f, 1.3f);
    k.spread = std::clamp(fit.spread, 0.7f, 1.4f);
    st.heightAfter = hipZ * k.legs + (neck.z - hipZ) * k.torso + above;
    return k;
}

EveBody fitEve(const M2Model& m, const std::vector<M2Vertex>& verts, const std::vector<uint32_t>& indices,
               const std::vector<M2Submesh>& subs, const std::vector<uint16_t>& geosets, const EveMesh& eve, const EveRaceFit& fit) {
    auto t0 = std::chrono::steady_clock::now();
    EveBody out;
    const size_t nv = eve.vertexCount();
    out.stats.eveVerts = nv;
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
    // the model's bones by their nearest ancestor among `named` (-1: none)
    auto ancestorIn = [&](int b, const std::vector<int>& named) {
        for (int c = b, g = 0; c >= 0 && size_t(c) < m.bones.size() && g < 256; c = m.bones[size_t(c)].parent, ++g)
            for (int n : named) if (n == c) return n;
        return -1;
    };
    auto mainBone = [](const M2Vertex& v) { int k = 0; for (int i = 1; i < 4; ++i) if (v.weights[i] > v.weights[k]) k = i; return int(v.bones[k]); };
    // ---- the limbs' girth toward the model's own: each segment's median radius (its middle 60%) on the body Eve replaces
    // against hers as fitted, both sides together; her vertices moved across the bone by their weight on it
    const bool rig = fit.rig > 0;
    // ---- the Eve rig: her body as made, scaled to the model's neck height and set on its pelvis line; each body bone's pivot
    // at her own joint (the Sims rig's, likewise scaled); what hangs under a moved bone moves with it, but for the head's and
    // the feet's chains, which keep the model's (its head, hair and feet are drawn on them, unchanged)
    if (rig) {
        V3 simsNeck;
        for (const auto& j : kEveSimsRig) if (std::string(j.first) == "Neck1") simsNeck = j.second;
        const float sr = neckW.z / std::max(simsNeck.z, 1e-3f);
        const V3 off(pelvisW.x, 0, 0);
        for (size_t v = 0; v < nv; ++v) {
            P[v] = P0[v] * sr + off;
            N[v] = V3(eve.nrm[v * 3], eve.nrm[v * 3 + 1], eve.nrm[v * 3 + 2]).normalized();
        }
        // her joints (scaled), by Warcraft name, both sides
        std::map<std::string, V3> J;
        for (const auto& jt : kEveSimsRig) {
            std::string n = jt.first;
            J[n] = jt.second * sr + off;
            if (n.compare(0, 2, "L_") == 0) J["R_" + n.substr(2)] = mirrorY(jt.second) * sr + off;
        }
        // ---- her arms and legs turned to the model's rest pose (each segment along the model's own: the model's animations
        // are turns from its rest, so her rest must point as its does), skinned by her own weights about her own joints: as a
        // Sims body is posed in the Sims. Parent first; helpers and fingers ride their limb's turn. Each upper arm, forearm,
        // thigh and shin is also stretched along itself toward the model's own length (`lengths`: her knees, elbows, hands
        // and hips where the model's are, so a kneel or a lie puts them where the model's would be; her cross-section kept),
        // and each clavicle along itself by the shoulders' width (`bulk.shoulders`).
        struct Turn { V3 from, to; M3 R, M; bool ok = false; };   // x -> to + M (x - from); R: M's turn alone (normals, children)
        auto stretchAlong = [](V3 d, float k) {   // I + (k - 1) d d^T
            d = d.normalized();
            M3 s;
            const float a[3] = {d.x, d.y, d.z};
            for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c) s.m[r * 3 + c] = (r == c ? 1.0f : 0.0f) + (k - 1.0f) * a[r] * a[c];
            return s;
        };
        const float lenShare = std::clamp(fit.lengths, 0.0f, 1.0f);
        auto lengthTo = [&](float eveLen, float modelLen) { return eveLen < 1e-5f || modelLen < 1e-5f ? 1.0f : 1.0f + lenShare * (modelLen / eveLen - 1.0f); };
        std::map<int, Turn> turn;   // by model bone
        std::map<std::string, V3> A = J;   // her joints as turned
        auto wowPiv = [&](const std::string& n, V3& p) { int b = m.findWowBone(n); if (b < 0) return false; p = m.bones[size_t(b)].pivot; return true; };
        // the legs exactly along the model's (its feet stay on its own ankles): her shin's length up from the model's ankle along
        // its shin, her thigh's up from there along its thigh (each toward the model's own by `lengths`); the hip moves with it
        // (her thigh's skin by its weight), and her trunk below the neck with the hips' height (`trunkZ` below)
        float hipDz = 0; int hipN = 0;
        V3 hipOwn[2], hipNew[2];
        bool legDone[2] = {false, false};
        for (int sd = 0; sd < 2; ++sd) {
            const std::string px = sd ? "R_" : "L_";
            V3 hipW, kneeW, ankW;
            const std::string tn = px + "Thigh", cn = px + "Calf", fn = px + "Foot";
            int tb = m.findWowBone(tn), cb = m.findWowBone(cn);
            if (tb < 0 || cb < 0 || !J.count(tn) || !J.count(cn) || !J.count(fn) || !wowPiv(tn, hipW) || !wowPiv(cn, kneeW) || !wowPiv(fn, ankW)) continue;
            V3 dT = (kneeW - hipW).normalized(), dC = (ankW - kneeW).normalized();
            float lT = (J[cn] - J[tn]).length(), lC = (J[fn] - J[cn]).length();
            float sT = lengthTo(lT, (kneeW - hipW).length()), sC = lengthTo(lC, (ankW - kneeW).length());
            V3 knee2 = ankW - dC * (lC * sC), hip2 = knee2 - dT * (lT * sT);
            Turn tt; tt.from = J[tn]; tt.to = hip2; tt.R = rotBetween(J[cn] - J[tn], dT); tt.M = tt.R * stretchAlong(J[cn] - J[tn], sT); tt.ok = true;
            Turn tc; tc.from = J[cn]; tc.to = knee2; tc.R = rotBetween(J[fn] - J[cn], dC); tc.M = tc.R * stretchAlong(J[fn] - J[cn], sC); tc.ok = true;
            turn[tb] = tt; turn[cb] = tc;
            A[tn] = hip2; A[cn] = knee2; A[fn] = ankW;
            hipOwn[sd] = J[tn]; hipNew[sd] = hip2; legDone[sd] = true;
            hipDz += hip2.z - J[tn].z; ++hipN;
        }
        if (hipN && lenShare > 0) hipDz /= float(hipN); else hipDz = 0;   // (lengths 0: her trunk as before, the hips where her legs end)
        // her trunk's height map: the hips' rise (or drop) in full at and below the hip joints, none at the neck (it meets the
        // model's neck there), linear between: her trunk keeps its shape, a little longer or shorter
        const float zHip0 = (J["L_Thigh"].z + J["R_Thigh"].z) * 0.5f, zNeck0 = J["Neck1"].z;
        auto trunkZ = [&](V3 p) {
            float w = std::clamp((zNeck0 - p.z) / std::max(zNeck0 - zHip0, 1e-4f), 0.0f, 1.0f);
            return V3(p.x, p.y, p.z + hipDz * w);
        };
        for (const char* n : {"Pelvis", "Spine1", "Spine2", "Chest", "L_Breast", "R_Breast"}) if (A.count(n)) A[n] = trunkZ(J[n]);
        // the shoulders' width: each clavicle stretched so her shoulder joint stands `bulk.shoulders` as far from her middle
        const float shoulderK = std::clamp(fit.bulk.shoulders, 0.7f, 1.6f);
        const char* chain[][3] = {{"Clavicle", "UpperArm", ""}, {"UpperArm", "Forearm", "Clavicle"}, {"Forearm", "Hand", "UpperArm"},
                                  {"Hand", "Middle1", "Forearm"}};
        for (int sd = 0; sd < 2; ++sd) {
            const std::string px = sd ? "R_" : "L_";
            for (const auto& c : chain) {
                const std::string bn = px + c[0], cn = px + c[1], pn = c[2][0] ? px + c[2] : "";
                int b = resolveBone(m, bn);
                if (b < 0 || m.findWowBone(bn) != b || !J.count(bn) || !J.count(cn)) continue;
                const Turn* par = !pn.empty() ? (turn.count(m.findWowBone(pn)) ? &turn[m.findWowBone(pn)] : nullptr) : nullptr;
                // where her joint and her child's are once the parent has turned (a clavicle: with her trunk)
                auto viaParent = [&](V3 x) { return par && par->ok ? par->to + par->M * (x - par->from) : trunkZ(x); };
                V3 jb = viaParent(J[bn]);
                V3 wb, wc;
                Turn t;
                t.from = J[bn]; t.to = jb;
                M3 G = par && par->ok ? par->R : M3();
                V3 rest = J[cn] - J[bn];
                const bool aimed = wowPiv(bn, wb) && wowPiv(cn, wc) && (wc - wb).length() > 1e-4f && rest.length() > 1e-4f;
                if (aimed) G = rotBetween(G * rest, wc - wb) * G;
                float k = 1;
                if (std::string(c[0]) == "UpperArm" || std::string(c[0]) == "Forearm") { if (aimed) k = lengthTo(rest.length(), (wc - wb).length()); }
                else if (std::string(c[0]) == "Clavicle" && std::fabs(shoulderK - 1) > 1e-4f) {
                    V3 v = G * rest;   // the clavicle as turned: her shoulder's distance from her middle line times shoulderK
                    float y0 = jb.y - off.y, want = (jb.y + v.y - off.y) * shoulderK;
                    if (std::fabs(v.y) > 1e-4f) k = std::clamp((want - y0) / v.y, 0.5f, 3.0f);
                }
                t.R = G; t.M = G * stretchAlong(rest, k); t.ok = true;
                turn[b] = t;
                A[bn] = jb; A[cn] = t.to + t.M * rest;
            }
            // helpers and fingers: their limb's turn
            const std::pair<const char*, const char*> ride[] = {{"UpperArmTwist", "UpperArm"}, {"ForearmTwist", "Forearm"}, {"ElbowHelper", "Forearm"},
                {"Thumb1", "Hand"}, {"Thumb2", "Hand"}, {"Index1", "Hand"}, {"Index2", "Hand"}, {"Middle1", "Hand"}, {"Middle2", "Hand"},
                {"Ring1", "Hand"}, {"Ring2", "Hand"}, {"Pinky1", "Hand"}, {"Pinky2", "Hand"}};
            for (const auto& rd : ride) {
                int limb = m.findWowBone(px + rd.second);
                if (limb < 0 || !turn.count(limb)) continue;
                const Turn& t = turn[limb];
                std::string hn = px + rd.first;
                int b = resolveBone(m, hn);
                if (J.count(hn)) A[hn] = t.to + t.M * (J[hn] - t.from);
                if (b >= 0 && !turn.count(b)) turn[b] = t;
            }
        }
        // ---- her build (`bulk`, rig 1): a bigger race's woman, from the standard body's own measured girths (wifecraft/README.md,
        // Her own rig): each limb segment's skin scaled across its bone about her joints (the axis through them stays, so the
        // joints stay centred), the trunk's width and depth per height (hips, waist, chest, shoulders) about her middle, and the
        // hips' sides following the hip joints where a race's legs are set wide (`follow`: no ledge where the thigh meets the
        // pelvis). Applied per bone and blended by her weights, as her skinning is: the seams and joints stay joined. Hands, feet,
        // the neck and the head keep their size.
        const EveBulk& bk = fit.bulk;
        auto cl = [](float v, float lo, float hi) { return std::isfinite(v) ? std::clamp(v, lo, hi) : 1.0f; };
        const float kArm = cl(bk.arm, 0.7f, 2.0f), kFore = cl(bk.forearm, 0.7f, 2.0f), kThigh = cl(bk.thigh, 0.7f, 2.0f), kCalf = cl(bk.calf, 0.7f, 2.0f);
        const float wHip = cl(bk.hips, 0.7f, 1.8f), wWaist = cl(bk.waist, 0.7f, 1.8f), wChest = cl(bk.chest, 0.7f, 1.8f);
        const float dHip = cl(bk.hipDepth, 0.7f, 1.8f), dWaist = cl(bk.waistDepth, 0.7f, 1.8f), dChest = cl(bk.chestDepth, 0.7f, 1.8f);
        const float follow = std::isfinite(bk.follow) ? std::clamp(bk.follow, 0.0f, 1.0f) : 0.0f;
        const float inset = std::isfinite(bk.inset) ? std::clamp(bk.inset, 0.0f, 1.0f) : 0.0f;
        const float zHip = (A["L_Thigh"].z + A["R_Thigh"].z) * 0.5f, zSh = (A["L_UpperArm"].z + A["R_UpperArm"].z) * 0.5f;
        auto smooth = [](float t) { t = std::clamp(t, 0.0f, 1.0f); return t * t * (3 - 2 * t); };
        // the trunk's factors by height (0 the hip joints, 1 the shoulder joints): hips to 0.1, waist at 0.45, chest at 0.75,
        // the shoulders' width from 0.95
        auto trunkK = [&](float z, float& kw, float& kd) {
            float f = (z - zHip) / std::max(zSh - zHip, 1e-4f);
            const float fs[4] = {0.1f, 0.45f, 0.75f, 0.95f};
            const float ws[4] = {wHip, wWaist, wChest, shoulderK}, ds[4] = {dHip, dWaist, dChest, dChest};
            if (f <= fs[0]) { kw = ws[0]; kd = ds[0]; return; }
            if (f >= fs[3]) { kw = ws[3]; kd = ds[3]; return; }
            int i = f < fs[1] ? 0 : f < fs[2] ? 1 : 2;
            float t = smooth((f - fs[i]) / (fs[i + 1] - fs[i]));
            kw = ws[i] + (ws[i + 1] - ws[i]) * t; kd = ds[i] + (ds[i + 1] - ds[i]) * t;
        };
        const bool bulky = std::fabs(kArm - 1) + std::fabs(kFore - 1) + std::fabs(kThigh - 1) + std::fabs(kCalf - 1) + std::fabs(wHip - 1) + std::fabs(wWaist - 1) +
                           std::fabs(wChest - 1) + std::fabs(dHip - 1) + std::fabs(dWaist - 1) + std::fabs(dChest - 1) + std::fabs(shoulderK - 1) + follow + inset > 1e-4f;
        // her trunk's middle (front to back) by height: the mid of its depth in 2 cm bands of the Sims body (her trunk as she stands)
        std::vector<float> midX;
        const float band = 0.02f * sr, zLo = zHip - 0.5f * sr;
        if (bulky) {
            std::vector<float> lo(80, 1e9f), hi(80, -1e9f);
            std::vector<uint8_t> trunkBone(m.bones.size(), 0);
            for (const char* n : {"Root", "Pelvis", "Waist", "Spine1", "Spine2", "Chest"}) { int b = m.findWowBone(n); if (b >= 0) trunkBone[size_t(b)] = 1; }
            for (size_t v = 0; v < nv; ++v) {
                float tw = 0;
                for (int i = 0; i < WM[v].n; ++i) if (trunkBone[size_t(WM[v].e[size_t(i)].first)]) tw += WM[v].e[size_t(i)].second;
                if (tw < 0.5f || std::fabs(P[v].y - off.y) > 0.06f * sr) continue;
                V3 q = trunkZ(P[v]);
                int k = int((q.z - zLo) / band);
                if (k < 0 || k >= 80) continue;
                lo[size_t(k)] = std::min(lo[size_t(k)], q.x); hi[size_t(k)] = std::max(hi[size_t(k)], q.x);
            }
            midX.assign(80, off.x);
            for (int k = 0; k < 80; ++k) {   // (a band with none: its nearest filled band's)
                float s = 0; int c = 0;
                for (int d = -2; d <= 2; ++d) { int q = k + d; if (q >= 0 && q < 80 && hi[size_t(q)] > lo[size_t(q)]) { s += (lo[size_t(q)] + hi[size_t(q)]) * 0.5f; ++c; } }
                if (c) midX[size_t(k)] = s / float(c);
                else if (k > 0) midX[size_t(k)] = midX[size_t(k - 1)];
            }
        }
        auto midAt = [&](float z) { if (midX.empty()) return off.x; int k = std::clamp(int((z - zLo) / band), 0, 79); return midX[size_t(k)]; };
        // what each model bone carries: 0 the trunk's map, 1..4 a limb segment (upper arm, forearm, thigh, calf) | side << 4
        std::vector<uint8_t> limbOf(m.bones.size(), 0);
        std::vector<uint8_t> trunkOf(m.bones.size(), 0);
        for (const char* n : {"Root", "Pelvis", "Waist", "Spine1", "Spine2", "Chest", "L_Breast", "R_Breast", "L_Clavicle", "R_Clavicle"}) {
            int b = m.findWowBone(n); if (b >= 0) trunkOf[size_t(b)] = 1;
        }
        for (int sd = 0; sd < 2; ++sd) {
            const std::string px = sd ? "R_" : "L_";
            const std::pair<const char*, int> segOf[] = {{"UpperArm", 1}, {"UpperArmTwist", 1}, {"Forearm", 2}, {"ForearmTwist", 2}, {"ElbowHelper", 2},
                                                         {"Thigh", 3}, {"Calf", 4}};
            for (const auto& so : segOf) {
                int b = resolveBone(m, px + so.first);
                if (b < 0) continue;
                bool own = m.findWowBone(px + so.first) == b;
                for (const Helper& h : kHelpers) if (px + so.first == h.name) own = m.bones[size_t(b)].keyBoneId == h.key;
                if (own) limbOf[size_t(b)] = uint8_t(so.second | (sd << 4));
            }
        }
        const char* segA[5] = {"", "UpperArm", "Forearm", "Thigh", "Calf"};
        const char* segB[5] = {"", "Forearm", "Hand", "Calf", "Foot"};
        // each segment's median radius as she stands (its middle 60%, the skin mostly its bone's)
        float limbR[2][5] = {{0, 0, 0, 0, 0}, {0, 0, 0, 0, 0}};
        if (bulky) {
            std::vector<float> rs[2][5];
            for (size_t v = 0; v < nv; ++v) {
                int best = -1; float bw = 0;
                for (int i = 0; i < WM[v].n; ++i) if (WM[v].e[size_t(i)].second > bw) { bw = WM[v].e[size_t(i)].second; best = WM[v].e[size_t(i)].first; }
                if (best < 0 || !limbOf[size_t(best)]) continue;
                int seg = limbOf[size_t(best)] & 15, sd = limbOf[size_t(best)] >> 4;
                const std::string px = sd ? "R_" : "L_";
                auto it = turn.find(best);
                auto ia = A.find(px + segA[seg]), ib = A.find(px + segB[seg]);
                if (it == turn.end() || ia == A.end() || ib == A.end()) continue;
                V3 q = it->second.to + it->second.M * (P[v] - it->second.from), d = ib->second - ia->second;
                float L = d.length();
                if (L < 1e-5f) continue;
                d = d * (1.0f / L);
                float a = (q - ia->second).dot(d);
                if (a < 0.2f * L || a > 0.8f * L) continue;
                rs[sd][seg].push_back(((q - ia->second) - d * a).length());
            }
            for (int sd = 0; sd < 2; ++sd) for (int sg = 1; sg < 5; ++sg) {
                std::vector<float>& r = rs[sd][sg];
                if (r.empty()) continue;
                std::nth_element(r.begin(), r.begin() + long(r.size() / 2), r.end());
                limbR[sd][sg] = r[r.size() / 2];
            }
        }
        // a limb's factor along it (0 at its first joint, 1 at its next): an arm's and a thigh's half of the change toward the next
        // segment's factor made over the last quarter; a thigh's growth eased in from the hip crease (her own hips' curve is
        // there: no ledge), a forearm's partly kept to the wrist, a shin's halfway back over its last 30% (the ankle
        // is then drawn onto the model's own foot top)
        auto limbK = [&](int seg, float t) {
            switch (seg) {
                case 1: return kArm + (0.5f * (kArm + kFore) - kArm) * smooth((t - 0.75f) / 0.35f);
                case 2: return kFore + (0.5f * (kArm + kFore) - kFore) * smooth((0.25f - t) / 0.35f) + (1 + 0.6f * (kFore - 1) - kFore) * smooth((t - 0.7f) / 0.35f);
                case 3: {
                    float top = 0.5f * (wHip + 1 + 0.4f * (kThigh - 1));   // (at the hip crease little more than the hips: no ledge there)
                    return top + (kThigh - top) * smooth(t / 0.35f) + (0.5f * (kThigh + kCalf) - kThigh) * smooth((t - 0.75f) / 0.35f);
                }
                case 4: return kCalf + (0.5f * (kThigh + kCalf) - kCalf) * smooth((0.25f - t) / 0.35f) + (0.5f * (1 - kCalf)) * smooth((t - 0.7f) / 0.3f);
                default: return 1.0f;
            }
        };
        auto bulkOf = [&](int b, V3 q, V3 n, V3& qo, V3& no) {
            qo = q; no = n;
            if (!bulky || b < 0 || size_t(b) >= m.bones.size()) return;
            if (uint8_t l = limbOf[size_t(b)]) {
                int seg = l & 15, sd = l >> 4;
                const std::string px = sd ? "R_" : "L_";
                auto ia = A.find(px + segA[seg]), ib = A.find(px + segB[seg]);
                if (ia == A.end() || ib == A.end()) return;
                V3 d = ib->second - ia->second;
                float L = d.length();
                if (L < 1e-5f) return;
                d = d * (1.0f / L);
                V3 r = q - ia->second;
                float a = r.dot(d);
                // thickened by the same depth all round (her median radius there times the factor's growth): a muscular
                // limb is hers with more on it, not her own taper magnified (a balloon thigh over a thin knee)
                float k = limbK(seg, a / L);
                V3 rad = r - d * a;
                float rl = rad.length(), add = (k - 1) * limbR[sd][seg];
                float kk = rl > 1e-5f ? std::clamp((rl + add) / rl, 0.5f, 2.5f) : 1.0f;   // (a crease near the bone: no more than 2.5 times)
                qo = ia->second + d * a + rad * kk;
                if (seg == 3 && inset > 0 && legDone[sd]) {   // the thigh's flesh drawn in toward her middle by her hip joint's move out
                    float dy = std::max(0.0f, (hipNew[sd].y - hipOwn[sd].y) * (sd ? -1.0f : 1.0f));
                    float in = inset * dy * (1 - smooth((a / L - 0.1f) / 0.6f));
                    qo.y += sd ? in : -in;
                }
                return;   // (an even thickening keeps the surface's normals)
            }
            if (!trunkOf[size_t(b)]) return;
            float kw, kd;
            trunkK(q.z, kw, kd);
            float cx = midAt(q.z), y = q.y - off.y;
            float fy = 0;
            if (follow > 0) {   // the hips' sides toward the hip joint's move: none in her middle, all of it past her hip joint
                const int sd = y < 0 ? 1 : 0;
                if (legDone[sd]) {
                    float dy = (hipNew[sd].y - hipOwn[sd].y) * (sd ? -1.0f : 1.0f);
                    float reach = std::max(std::fabs(hipOwn[sd].y - off.y), 1e-4f);
                    float f = (q.z - zHip) / std::max(zSh - zHip, 1e-4f);
                    fy = follow * dy * smooth(std::fabs(y) / (1.2f * reach)) * (1 - smooth((f - 0.05f) / 0.35f));
                }
            }
            qo = V3(cx + (q.x - cx) * kd, off.y + y * kw + (y < 0 ? -fy : fy), q.z);
            no = V3(n.x / kd, n.y / kw, n.z).normalized();
        };
        // her vertices by her own weights
        for (size_t v = 0; v < nv; ++v) {
            V3 acc, nacc; float tot = 0;
            for (int i = 0; i < WM[v].n; ++i) {
                int b = WM[v].e[size_t(i)].first;
                float w = WM[v].e[size_t(i)].second;
                auto it = turn.find(b);
                V3 q, n, qo, no;
                if (it == turn.end()) { q = trunkZ(P[v]); n = N[v]; }
                else { q = it->second.to + it->second.M * (P[v] - it->second.from); n = it->second.R * N[v]; }
                bulkOf(b, q, n, qo, no);
                acc = acc + qo * w; nacc = nacc + no * w;
                tot += w;
            }
            if (tot > 0) { P[v] = acc * (1.0f / tot); N[v] = nacc.normalized(); }
        }
        // her spine's bends at the model's own spine joints (`spine` of the way): the model's bows, kneels and lies bend its spine
        // there, and the head, the shoulders and the hands land where the model's do (her trunk is one piece of flesh round
        // them: her weights blend over 10 to 20 cm, a few centimetres' move of the pivot doesn't show)
        if (fit.spine > 0) {
            const float k = std::min(fit.spine, 1.0f);
            for (const char* n : {"Spine1", "Spine2", "Chest"}) { V3 w; if (A.count(n) && wowPiv(n, w)) A[n] = A[n] + (w - A[n]) * k; }
        }
        // the breasts' joints with the trunk's build (they sit in its flesh)
        for (const char* n : {"L_Breast", "R_Breast"}) {
            int b = m.findWowBone(n);
            if (b < 0 || !A.count(n)) continue;
            V3 qo, no;
            bulkOf(b, A[n], V3(1, 0, 0), qo, no);
            A[n] = qo;
        }
        out.rigPivots.resize(m.bones.size());
        std::vector<int8_t> moved(m.bones.size(), 0);   // 1 placed from the rig, -1 kept (head and feet chains)
        for (size_t b = 0; b < m.bones.size(); ++b) out.rigPivots[b] = m.bones[b].pivot;
        for (const auto& [name, p] : A) {
            // (the neck and feet are the model's; so is the pelvis's pivot: it bends nothing of hers, the spine and the hips
            // do, and on the model's own point her whole body follows the model's pelvis in every pose)
            if (name == "Neck1" || name == "L_Foot" || name == "R_Foot" || (name == "Pelvis" && fit.lengths > 0)) continue;
            int b = resolveBone(m, name);
            bool own = m.findWowBone(name) == b;
            for (const Helper& h : kHelpers) if (name == h.name) own = b >= 0 && m.bones[size_t(b)].keyBoneId == h.key;
            if (b < 0 || size_t(b) >= m.bones.size() || !own || moved[size_t(b)] == 1) continue;
            out.rigPivots[size_t(b)] = p;
            moved[size_t(b)] = 1;
        }
        for (const char* n : {"Root", "Neck1", "L_Foot", "R_Foot", "L_Ankle", "R_Ankle"}) { int b = m.findWowBone(n); if (b >= 0 && !moved[size_t(b)]) moved[size_t(b)] = -1; }
        if (fit.lengths > 0) { int b = m.findWowBone("Pelvis"); if (b >= 0 && !moved[size_t(b)]) moved[size_t(b)] = -1; }
        // the rest: by their nearest decided ancestor (moved: carried by its offset; kept, or none: as the model has them)
        for (size_t b = 0; b < m.bones.size(); ++b) {
            if (moved[b]) continue;
            for (int c = m.bones[b].parent, g = 0; c >= 0 && size_t(c) < m.bones.size() && g < 256; c = m.bones[size_t(c)].parent, ++g) {
                if (!moved[size_t(c)]) continue;
                if (moved[size_t(c)] == 1) out.rigPivots[b] = m.bones[b].pivot + (out.rigPivots[size_t(c)] - m.bones[size_t(c)].pivot);
                break;
            }
        }
        out.stats.rigScale = sr;
    }
    if (fit.limbs > 0 && !rig) {
        const char* segBone[4] = {"UpperArm", "Forearm", "Thigh", "Calf"};
        const int segRegion[4] = {kUpperArm, kForearm, kThigh, kCalf};
        std::vector<int> limbBones;
        int lb[2][4];
        for (int sd = 0; sd < 2; ++sd) for (int k = 0; k < 4; ++k) {
            lb[sd][k] = m.findWowBone(std::string(sd ? "R_" : "L_") + segBone[k]);
            if (lb[sd][k] >= 0) limbBones.push_back(lb[sd][k]);
        }
        for (const char* n : {"L_Hand", "R_Hand", "L_Foot", "R_Foot", "L_Ankle", "R_Ankle", "Pelvis", "Chest", "L_Clavicle", "R_Clavicle"}) {
            int b = m.findWowBone(n); if (b >= 0) limbBones.push_back(b);
        }
        auto ends = [&](int sd, int k, V3& a, V3& b) {
            const Side& w = W[sd];
            switch (k) { case 0: a = w.shoulder; b = w.elbow; break; case 1: a = w.elbow; b = w.wrist; break;
                         case 2: a = w.hip; b = w.knee; break; default: a = w.knee; b = w.ankle; }
        };
        auto radial = [](V3 p, V3 a, V3 b, float& t) {
            V3 ab = b - a; t = (p - a).dot(ab) / std::max(ab.dot(ab), 1e-8f);
            return p - (a + ab * t);
        };
        for (int k = 0; k < 4; ++k) {
            std::vector<float> rm, re;
            for (int sd = 0; sd < 2; ++sd) {
                if (lb[sd][k] < 0) continue;
                V3 a, b; ends(sd, k, a, b);
                for (size_t i = 0; i < verts.size(); ++i) {
                    if (!out.cut[i] || ancestorIn(mainBone(verts[i]), limbBones) != lb[sd][k]) continue;
                    float t; V3 r = radial(posOf(verts[i]), a, b, t);
                    if (t > 0.2f && t < 0.8f) rm.push_back(r.length());
                }
                for (size_t v = 0; v < nv; ++v) {
                    int best = 0;
                    for (int q = 1; q < 4; ++q) if (eve.bw[v * 4 + q] > eve.bw[v * 4 + best]) best = q;
                    if (regOf[eve.bi[v * 4 + best]] != sd * kRegionsPerSide + segRegion[k]) continue;
                    float t; V3 r = radial(P[v], a, b, t);
                    if (t > 0.2f && t < 0.8f) re.push_back(r.length());
                }
            }
            if (rm.size() < 8 || re.size() < 8) continue;
            std::nth_element(rm.begin(), rm.begin() + long(rm.size() / 2), rm.end());
            std::nth_element(re.begin(), re.begin() + long(re.size() / 2), re.end());
            float f = std::clamp(1 + fit.limbs * (rm[rm.size() / 2] / std::max(re[re.size() / 2], 1e-4f) - 1), 0.75f, 1.6f);
            out.stats.limbScale[k] = f;
            if (std::fabs(f - 1) < 1e-3f) continue;
            for (int sd = 0; sd < 2; ++sd) {
                V3 a, b; ends(sd, k, a, b);
                for (size_t v = 0; v < nv; ++v) {
                    float w = 0;
                    for (int q = 0; q < 4; ++q) if (regOf[eve.bi[v * 4 + q]] == sd * kRegionsPerSide + segRegion[k]) w += eve.bw[v * 4 + q] / 255.0f;
                    if (w <= 0) continue;
                    float t; V3 r = radial(P[v], a, b, t);
                    if (t < -0.15f || t > 1.15f) continue;
                    // (at the joints the move fades: the segment's ends are shared with its neighbours)
                    float edge = std::clamp(std::min(t + 0.15f, 1.15f - t) / 0.3f, 0.0f, 1.0f);
                    P[v] = P[v] + r * ((f - 1) * w * edge);
                }
            }
        }
    }
    // ---- the trunk's weights from the model's own body: each trunk vertex takes the bone weights of the model's skin around
    // it (Gaussian over 5 cm, facing the same way), blended in by how much of it the trunk drives (and, round the hips, the
    // thighs above the hip joints); arms, hands and legs below the hips keep Eve's
    if (fit.rezone > 0 && !rig) {
        std::vector<int> trunkNamed;
        for (const char* n : {"Root", "Pelvis", "Waist", "Spine1", "Spine2", "Chest", "L_Breast", "R_Breast", "L_Clavicle", "R_Clavicle"}) {
            int b = m.findWowBone(n); if (b >= 0) trunkNamed.push_back(b);
        }
        std::vector<int> stops = trunkNamed;   // (a limb's bones end the trunk's reach)
        for (const char* n : {"L_UpperArm", "R_UpperArm", "L_Thigh", "R_Thigh", "Neck1", "Head"}) { int b = m.findWowBone(n); if (b >= 0) stops.push_back(b); }
        std::vector<uint8_t> trunk(m.bones.size(), 0), thigh(m.bones.size(), 0);
        const int thighs[2] = {m.findWowBone("L_Thigh"), m.findWowBone("R_Thigh")};
        for (size_t b = 0; b < m.bones.size(); ++b) {
            int a = ancestorIn(int(b), stops);
            if (a < 0) continue;
            if (std::find(trunkNamed.begin(), trunkNamed.end(), a) != trunkNamed.end()) trunk[b] = 1;
            if (a == thighs[0] || a == thighs[1]) thigh[b] = 1;
        }
        const float r = 0.05f * unit, sig2 = r * r * 0.25f;
        std::map<std::array<int, 3>, std::vector<uint32_t>> grid;
        auto cell = [&](V3 p) { return std::array<int, 3>{int(std::floor(p.x / r)), int(std::floor(p.y / r)), int(std::floor(p.z / r))}; };
        for (size_t i = 0; i < verts.size(); ++i) if (out.cut[i]) grid[cell(posOf(verts[i]))].push_back(uint32_t(i));
        const float hipZ = (W[0].hip.z + W[1].hip.z) * 0.5f;
        for (size_t v = 0; v < nv; ++v) {
            float tw = 0, th = 0;
            for (int i = 0; i < WM[v].n; ++i) {
                int b = WM[v].e[size_t(i)].first;
                if (b < 0 || size_t(b) >= m.bones.size()) continue;
                if (trunk[size_t(b)]) tw += WM[v].e[size_t(i)].second;
                else if (thigh[size_t(b)]) th += WM[v].e[size_t(i)].second;
            }
            float tot = 0;
            for (int i = 0; i < WM[v].n; ++i) tot += WM[v].e[size_t(i)].second;
            if (tot <= 0) continue;
            // the thighs' share counts round the hips: fully above the hip joints, none 12 cm below them
            float hipBand = std::clamp((P[v].z - (hipZ - 0.12f * unit)) / (0.12f * unit), 0.0f, 1.0f);
            hipBand = hipBand * hipBand * (3 - 2 * hipBand);
            float alpha = std::clamp((tw + th * hipBand) / tot, 0.0f, 1.0f) * std::clamp(fit.rezone, 0.0f, 1.0f);
            if (alpha < 0.02f) continue;
            WMap game; float wsum = 0, best = 1e30f; uint32_t nearest = UINT32_MAX;
            auto c0 = cell(P[v]);
            for (int dx = -1; dx <= 1; ++dx) for (int dy = -1; dy <= 1; ++dy) for (int dz = -1; dz <= 1; ++dz) {
                auto it = grid.find({c0[0] + dx, c0[1] + dy, c0[2] + dz});
                if (it == grid.end()) continue;
                for (uint32_t i : it->second) {
                    V3 q = posOf(verts[i]);
                    float d2 = (q - P[v]).dot(q - P[v]);
                    V3 nq(verts[i].normal[0], verts[i].normal[1], verts[i].normal[2]);
                    float facing = nq.normalized().dot(N[v]);
                    if (facing > 0.0f && d2 < best) { best = d2; nearest = i; }
                    if (d2 > r * r || facing <= 0.2f) continue;
                    float wt = std::exp(-d2 / (2 * sig2)) * facing;
                    for (int k = 0; k < 4; ++k) if (verts[i].weights[k]) game.add(verts[i].bones[k], verts[i].weights[k] / 255.0f * wt);
                    wsum += wt;
                }
            }
            if (wsum <= 1e-6f) {
                if (nearest == UINT32_MAX) continue;
                game = WMap();
                for (int k = 0; k < 4; ++k) if (verts[nearest].weights[k]) game.add(verts[nearest].bones[k], verts[nearest].weights[k] / 255.0f);
                wsum = 1;
            }
            WMap own = WM[v].scaled(1.0f / tot);
            WM[v] = blend(own, game.scaled(1.0f / wsum), alpha);
            ++out.stats.rezoned;
        }
    }
    // ---- the shoulders' weights smoothed over the mesh (each place's weights toward its neighbours', most at the joint,
    // none past kShoulderReach): the Sims rig's shoulder helpers fold onto the model's upper arm and clavicle in a narrow
    // band, which pinched a ridge over the shoulder and a crease into the armpit with the arm raised (cheering, asleep
    // with an arm under the head). UV copies of a place move together.
    if (!rig) {
        constexpr float kShoulderReach = 0.13f;   // Sims metres from the shoulder joint
        constexpr int kPasses = 4;
        std::unordered_map<int64_t, uint32_t> rep;
        std::vector<uint32_t> grp(nv);
        for (size_t v = 0; v < nv; ++v) grp[v] = rep.emplace(weldKey(P0[v], 1e-5f), uint32_t(v)).first->second;
        std::vector<float> reach(nv, 0);
        bool any = false;
        for (size_t v = 0; v < nv; ++v) {
            if (grp[v] != v) continue;
            float d = std::min((P0[v] - S[0].shoulder).length(), (P0[v] - S[1].shoulder).length());
            if (d < kShoulderReach) { float f = 1 - d / kShoulderReach; reach[v] = f * f; any = true; }
        }
        if (any) {
            std::vector<std::vector<uint32_t>> nb(nv);
            for (size_t t = 0; t + 2 < eve.idx.size(); t += 3)
                for (int k = 0; k < 3; ++k) {
                    uint32_t a = grp[eve.idx[t + size_t(k)]], b = grp[eve.idx[t + size_t((k + 1) % 3)]];
                    if (a == b || (reach[a] <= 0 && reach[b] <= 0)) continue;
                    if (std::find(nb[a].begin(), nb[a].end(), b) == nb[a].end()) nb[a].push_back(b);
                    if (std::find(nb[b].begin(), nb[b].end(), a) == nb[b].end()) nb[b].push_back(a);
                }
            for (int pass = 0; pass < kPasses; ++pass) {
                std::vector<WMap> next = WM;
                for (size_t v = 0; v < nv; ++v) {
                    if (reach[v] <= 0 || nb[v].empty()) continue;
                    WMap avg;
                    const float s = 1.0f / float(nb[v].size());
                    for (uint32_t u : nb[v]) for (int i = 0; i < WM[u].n; ++i) avg.add(WM[u].e[size_t(i)].first, WM[u].e[size_t(i)].second * s);
                    next[v] = blend(WM[v], avg, 0.6f * reach[v]);
                }
                WM.swap(next);
            }
            for (size_t v = 0; v < nv; ++v) if (grp[v] != v) WM[v] = WM[grp[v]];
        }
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
    // ---- the shins' last centimetres onto the model's own lower leg (the skin Eve's cut took): each of her shin's vertices
    // within `band` of the model's cut edge (which slants over the foot's top) is moved along its horizontal radius from the
    // model's ankle joint to where that ray meets the model's removed skin (its triangles, at her vertex's own angle and
    // height: the model's legs are low in vertices, a profile read from them alone missed most of hers, which kept a cuff of
    // up to 1 cm where her slimmer or fuller shin met the foot's edge), fully up to 2 cm over the edge, eased to none at
    // `band`; her normals turn to the model's surface's there by as much. Her shin meets the foot as the model's own leg did.
    if (fit.ankleMatch > 0) for (int s = 0; s < 2 && !split; ++s) {
        if (ankleR[s].e.empty()) continue;
        constexpr int kAng = 24;
        const float band = 0.09f * unit, full = 0.02f * unit, share = std::min(fit.ankleMatch, 1.0f);
        const V3 c = ankleR[s].centre;
        auto angleOf = [&](V3 p) { return std::atan2(p.y - c.y, p.x - c.x); };
        auto bin = [&](V3 p) { int k = int(std::floor((angleOf(p) + 3.14159265f) / 6.2831853f * kAng)); return (k % kAng + kAng) % kAng; };
        // the cut edge's height per angle (its vertices'), the gaps filled from the neighbours; read between the bins' centres
        float edgeZ[kAng], ez[kAng]; int en[kAng] = {0};
        for (int k = 0; k < kAng; ++k) { edgeZ[k] = 0; ez[k] = 0; }
        for (auto& e : ankleR[s].e) for (uint32_t v : {e.first, e.second}) { int k = bin(posOf(verts[v])); ez[k] += verts[v].pos[2]; en[k]++; }
        bool anyEdge = false;
        for (int k = 0; k < kAng; ++k) if (en[k]) { edgeZ[k] = ez[k] / float(en[k]); anyEdge = true; }
        if (!anyEdge) continue;
        for (int pass = 0; pass < kAng; ++pass) for (int k = 0; k < kAng; ++k) if (!en[k]) {
            int a2 = (k + kAng - 1) % kAng, b2 = (k + 1) % kAng;
            if (en[a2]) { edgeZ[k] = edgeZ[a2]; en[k] = -1; } else if (en[b2]) { edgeZ[k] = edgeZ[b2]; en[k] = -1; }
        }
        auto edgeAt = [&](V3 p) {
            float f = (angleOf(p) + 3.14159265f) / 6.2831853f * kAng - 0.5f;
            int k0 = int(std::floor(f)); float w = f - float(k0);
            return edgeZ[(k0 % kAng + kAng) % kAng] * (1 - w) + edgeZ[((k0 + 1) % kAng + kAng) % kAng] * w;
        };
        float eLo = 1e9f, eHi = -1e9f;
        for (int k = 0; k < kAng; ++k) { eLo = std::min(eLo, edgeZ[k]); eHi = std::max(eHi, edgeZ[k]); }
        std::vector<uint8_t> onRing(nv, 0);
        if (const std::vector<uint32_t>* rr = eve.ring(s ? "R_ankle" : "L_ankle")) for (uint32_t v : *rr) if (v < nv) onRing[v] = 1;
        const V3 other = ankleR[s ^ 1].e.empty() ? W[s ^ 1].ankle : ankleR[s ^ 1].centre;
        // the rays' origin: the model's ankle joint (inside its leg, where the slanted edge's own centre may not be)
        const V3 o0(W[s].ankle.x, W[s].ankle.y, 0);
        // the model's removed skin round this ankle: the shown skin's triangles the cut drops (any corner cut), this leg's own
        std::vector<std::array<uint32_t, 3>> tris;
        for (size_t sI = 0; sI < subs.size(); ++sI) {
            if (!skinSub[sI]) continue;
            if (all ? (subs[sI].id % 100 > 1) : std::find(geosets.begin(), geosets.end(), subs[sI].id) == geosets.end()) continue;
            for (uint32_t t = subs[sI].indexStart; t + 2 < subs[sI].indexStart + subs[sI].indexCount && t + 2 < indices.size(); t += 3) {
                uint32_t i0 = indices[t], i1 = indices[t + 1], i2 = indices[t + 2];
                if (i0 >= verts.size() || i1 >= verts.size() || i2 >= verts.size()) continue;
                if (!out.cut[i0] && !out.cut[i1] && !out.cut[i2]) continue;
                V3 g = (posOf(verts[i0]) + posOf(verts[i1]) + posOf(verts[i2])) * (1.0f / 3);
                float dm = V3(g.x - c.x, g.y - c.y, 0).length();
                if (dm > 0.2f * unit || dm >= V3(g.x - other.x, g.y - other.y, 0).length()) continue;
                if (g.z < eLo - 0.03f * unit || g.z > eHi + band + 0.03f * unit) continue;
                tris.push_back({i0, i1, i2});
            }
        }
        if (tris.empty()) continue;
        // the farthest point of that skin along the horizontal ray from the ankle's axis at height z toward d (its distance
        // and the skin's normal there); false: none
        auto hit = [&](float z, V3 d, float& dist, V3& n) {
            const V3 org(o0.x, o0.y, z);
            bool any = false;
            for (const auto& t : tris) {
                V3 A = posOf(verts[t[0]]), B = posOf(verts[t[1]]), C = posOf(verts[t[2]]);
                V3 e1 = B - A, e2 = C - A, pv = d.cross(e2);
                float det = e1.dot(pv);
                if (std::fabs(det) < 1e-12f) continue;
                float inv = 1.0f / det;
                V3 tv = org - A;
                float u = tv.dot(pv) * inv;
                if (u < 0 || u > 1) continue;
                V3 qv = tv.cross(e1);
                float w = d.dot(qv) * inv;
                if (w < 0 || u + w > 1) continue;
                float tt = e2.dot(qv) * inv;
                if (tt <= 0 || tt > 0.25f * unit || (any && tt <= dist)) continue;
                dist = tt; any = true;
                n = (V3(verts[t[0]].normal[0], verts[t[0]].normal[1], verts[t[0]].normal[2]) * (1 - u - w) +
                     V3(verts[t[1]].normal[0], verts[t[1]].normal[1], verts[t[1]].normal[2]) * u +
                     V3(verts[t[2]].normal[0], verts[t[2]].normal[1], verts[t[2]].normal[2]) * w).normalized();
            }
            return any;
        };
        if (getenv("ANKLE_FIT_DIAG") && s == 0) {
            for (size_t v = 0; v < nv; ++v) {
                V3 q = P[v]; float a = std::atan2(q.y - o0.y, q.x - o0.x) * 57.2958f;
                if (std::fabs(a) > 25 || q.z > edgeAt(q) + 0.12f * unit || q.z < edgeAt(q) - 0.03f * unit || V3(q.x - o0.x, q.y - o0.y, 0).length() > 0.2f * unit) continue;
                if ((q.y > 0) != (s == 0)) continue;
                float want = -1; V3 n; V3 d(q.x - o0.x, q.y - o0.y, 0); d = d.normalized(); hit(q.z, d, want, n);
                std::fprintf(stderr, "AFD v %zu ang %.0f z %.4f dz %.4f r %.4f calfW %.2f ring %d want %.4f (unit %.3f)\n", v, a, q.z, q.z - edgeAt(q), V3(q.x - o0.x, q.y - o0.y, 0).length(), calfW[s][v], onRing[v], want, unit);
            }
        }
        for (size_t v = 0; v < nv; ++v) {
            if (calfW[s][v] < 0.3f || onRing[v]) continue;   // (the ring itself is snapped onto the edge below)
            V3 q = P[v];
            float dz = q.z - edgeAt(q);
            if (dz < 0 || dz >= band) continue;
            V3 d(q.x - o0.x, q.y - o0.y, 0);
            float rl = d.length();
            if (rl < 1e-5f) continue;
            d = d * (1.0f / rl);
            float want; V3 n;
            if (!hit(q.z, d, want, n)) continue;
            float u = std::clamp((dz - full) / (band - full), 0.0f, 1.0f);
            float t = share * (1 - u * u * (3 - 2 * u));
            float nr = rl + (want - rl) * t;
            P[v] = V3(o0.x + d.x * nr, o0.y + d.y * nr, q.z);
            N[v] = (N[v] * (1 - t) + n * t).normalized();
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
    // ---- the seams stitched: a thin strip from each Eve ring to the model's own cut-edge vertices (their places, weights and
    // normals exactly), triangulated round the ring by angle. The model's edge zigzags between Eve's ring vertices; without
    // the strip that zigzag shows as a hairline of light, wider in a stride.
    std::vector<V3> XP, XN;
    std::vector<WMap> XW;
    std::vector<float> XUV, XUV2;
    std::vector<uint32_t> XI;
    if (fit.stitch > 0) for (int r = 0; r < 3; ++r) {
        const std::vector<uint32_t>* ring = eve.ring(ringNames[r]);
        const RingEdges& we = *ringEdges[r];
        if (!ring || ring->size() < 3 || we.e.size() < 3) continue;
        // the ring's centre and axis: the model's edge (its centre; up for the neck and ankles, the thigh's line for a split)
        V3 c;
        std::vector<uint32_t> wv;
        for (const auto& e : we.e) for (uint32_t v : {e.first, e.second}) if (std::find(wv.begin(), wv.end(), v) == wv.end()) wv.push_back(v);
        for (uint32_t v : wv) c = c + posOf(verts[v]);
        c = c * (1.0f / float(wv.size()));
        auto angle = [&](V3 p) { return std::atan2(p.y - c.y, p.x - c.x); };
        // Eve's ring by place (UV copies grouped), each with its angle
        std::map<int64_t, std::vector<uint32_t>> groups;
        for (uint32_t v : *ring) groups[weldKey(P0[v], 1e-5f)].push_back(v);
        struct A { float a; std::vector<uint32_t> copies; };
        std::vector<A> ea;
        for (auto& [k, g] : groups) ea.push_back({angle(P[g[0]]), g});
        struct B { float a; uint32_t v; };
        std::vector<B> wb;
        std::sort(ea.begin(), ea.end(), [](const A& x, const A& y) { return x.a < y.a; });
        if (ea.size() < 3) continue;
        // the model's edge in its own order (walked edge to edge, so the strip's edges are the model's), its parameter the
        // share of the loop's length from the vertex nearest Eve's first, the way round her angles go; Eve's by her angles
        {
            std::map<uint32_t, std::vector<uint32_t>> adj;
            for (const auto& e : we.e) { adj[e.first].push_back(e.second); adj[e.second].push_back(e.first); }
            bool simple = true;
            for (auto& [v, nb] : adj) if (nb.size() != 2) { simple = false; break; }
            std::vector<uint32_t> loop;
            if (simple) {
                uint32_t prev = UINT32_MAX, cur = adj.begin()->first;
                for (size_t guard = 0; guard <= adj.size(); ++guard) {
                    loop.push_back(cur);
                    const auto& nb = adj[cur];
                    uint32_t nx = nb[0] != prev ? nb[0] : nb[1];
                    prev = cur; cur = nx;
                    if (cur == loop[0]) break;
                }
                simple = loop.size() == adj.size();
            }
            if (simple) {
                // the way round: as Eve's angles grow
                float turn = 0;
                for (size_t k = 0; k < loop.size(); ++k) {
                    float d = angle(posOf(verts[loop[(k + 1) % loop.size()]])) - angle(posOf(verts[loop[k]]));
                    while (d > 3.14159265f) d -= 6.2831853f;
                    while (d < -3.14159265f) d += 6.2831853f;
                    turn += d;
                }
                if (turn < 0) std::reverse(loop.begin(), loop.end());
                // start nearest Eve's first vertex's angle
                size_t s0 = 0; float bd = 1e30f;
                for (size_t k = 0; k < loop.size(); ++k) {
                    float d = std::fabs(std::remainder(angle(posOf(verts[loop[k]])) - ea[0].a, 6.2831853f));
                    if (d < bd) { bd = d; s0 = k; }
                }
                std::rotate(loop.begin(), loop.begin() + long(s0), loop.end());
                std::vector<float> len(loop.size() + 1, 0);
                for (size_t k = 0; k < loop.size(); ++k) len[k + 1] = len[k] + (posOf(verts[loop[(k + 1) % loop.size()]]) - posOf(verts[loop[k]])).length();
                for (size_t k = 0; k < loop.size(); ++k) wb.push_back({len[k] / std::max(len.back(), 1e-6f), loop[k]});
                // Eve's: her angles from her first, as shares of a turn
                for (A& x : ea) { float d = x.a - ea[0].a; if (d < 0) d += 6.2831853f; x.a = d / 6.2831853f; }
            } else {
                for (uint32_t v : wv) wb.push_back({angle(posOf(verts[v])), v});
                std::sort(wb.begin(), wb.end(), [](const B& x, const B& y) { return x.a < y.a; });
            }
            if (wb.size() < 3) continue;
            if (simple) { for (B& b : wb) b.a *= 6.2831853f; for (A& x : ea) x.a *= 6.2831853f; }   // (the walk below counts in turns of 2 pi)
        }
        // the copy of `g` nearest in UV to (u, v)
        auto copyNear = [&](const std::vector<uint32_t>& g, float u, float v) {
            uint32_t best = g[0]; float bd = 1e30f;
            for (uint32_t x : g) { float du = eve.uv[x * 2] - u, dv = eve.uv[x * 2 + 1] - v, d = du * du + dv * dv; if (d < bd) { bd = d; best = x; } }
            return best;
        };
        auto addW = [&](uint32_t mv, float u, float v, float u2, float v2) {
            const M2Vertex& w = verts[mv];
            XP.push_back(posOf(w));
            XN.push_back(V3(w.normal[0], w.normal[1], w.normal[2]).normalized());
            WMap m4;
            for (int k = 0; k < 4; ++k) m4.add(w.bones[k], w.weights[k] / 255.0f);
            XW.push_back(m4);
            XUV.insert(XUV.end(), {u, v}); XUV2.insert(XUV2.end(), {u2, v2});
            return uint32_t(nv + XP.size() - 1);
        };
        // walk both loops by angle (from the lowest of either, once round)
        const float two = 6.2831853f;
        size_t i = 0, j = 0;
        float start = std::min(ea[0].a, wb[0].a);   // (both lists ascend within one turn)
        auto aAt = [&](size_t k) { return ea[k % ea.size()].a + (k >= ea.size() ? two : 0.0f) - start; };
        auto bAt = [&](size_t k) { return wb[k % wb.size()].a + (k >= wb.size() ? two : 0.0f) - start; };
        uint32_t ca = ea[0].copies[0];
        while (i < ea.size() || j < wb.size()) {
            const A& a0 = ea[i % ea.size()];
            const B& b0 = wb[j % wb.size()];
            bool stepA = j >= wb.size() || (i < ea.size() && aAt(i + 1) <= bAt(j + 1));
            if (stepA) {
                const A& a1 = ea[(i + 1) % ea.size()];
                uint32_t c0 = copyNear(a0.copies, eve.uv[ca * 2], eve.uv[ca * 2 + 1]);
                uint32_t c1 = copyNear(a1.copies, eve.uv[c0 * 2], eve.uv[c0 * 2 + 1]);
                // the model's vertex takes the UV between the two by its angle
                float span = aAt(i + 1) - aAt(i), f = span > 1e-6f ? std::clamp((bAt(j) - aAt(i)) / span, 0.0f, 1.0f) : 0.5f;
                auto lerpUv = [&](const std::vector<float>& uv, uint32_t x, uint32_t y, int k) { return uv.size() > size_t(y) * 2 + 1 ? uv[x * 2 + k] + (uv[y * 2 + k] - uv[x * 2 + k]) * f : 0.0f; };
                uint32_t bw = addW(b0.v, lerpUv(eve.uv, c0, c1, 0), lerpUv(eve.uv, c0, c1, 1), lerpUv(eve.uv2, c0, c1, 0), lerpUv(eve.uv2, c0, c1, 1));
                XI.insert(XI.end(), {c0, bw, c1});
                ca = c1;
                ++i;
            } else {
                const B& b1 = wb[(j + 1) % wb.size()];
                uint32_t c0 = copyNear(a0.copies, eve.uv[ca * 2], eve.uv[ca * 2 + 1]);
                float u = eve.uv[c0 * 2], v = eve.uv[c0 * 2 + 1];
                float u2 = eve.uv2.size() > size_t(c0) * 2 + 1 ? eve.uv2[c0 * 2] : 0.0f, v2 = eve.uv2.size() > size_t(c0) * 2 + 1 ? eve.uv2[c0 * 2 + 1] : 0.0f;
                uint32_t w0 = addW(b0.v, u, v, u2, v2), w1 = addW(b1.v, u, v, u2, v2);
                XI.insert(XI.end(), {c0, w0, w1});
                ca = c0;
                ++j;
            }
        }
        ++out.stats.stitched;
    }
    // ---- the vertices: four weights at most (summing to 255)
    for (size_t x = 0; x < XP.size(); ++x) { P.push_back(XP[x]); N.push_back(XN[x]); WM.push_back(XW[x]); }
    const size_t nall = P.size();
    out.verts.resize(nall);
    for (size_t v = 0; v < nall; ++v) {
        M2Vertex& o = out.verts[v];
        o.pos[0] = P[v].x; o.pos[1] = P[v].y; o.pos[2] = P[v].z;
        o.normal[0] = N[v].x; o.normal[1] = N[v].y; o.normal[2] = N[v].z;
        if (v < nv) {
            o.uv[0] = eve.uv[v * 2]; o.uv[1] = eve.uv[v * 2 + 1];
            o.uv2[0] = eve.uv2.size() > v * 2 + 1 ? eve.uv2[v * 2] : 0.0f; o.uv2[1] = eve.uv2.size() > v * 2 + 1 ? eve.uv2[v * 2 + 1] : 0.0f;
        } else {
            size_t x = v - nv;
            o.uv[0] = XUV[x * 2]; o.uv[1] = XUV[x * 2 + 1]; o.uv2[0] = XUV2[x * 2]; o.uv2[1] = XUV2[x * 2 + 1];
        }
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
    out.indices.insert(out.indices.end(), XI.begin(), XI.end());
    out.scale = eveProportions(m, verts, indices, subs, geosets, out, fit, W[0].hip, W[1].hip, neckW, W[0].shoulder, W[0].elbow, W[0].wrist, out.stats);
    if (rig) { out.scale = BodyScale(); out.stats.heightAfter = out.stats.height; }   // (her rig is her own proportions: Leg Length alone acts on it)
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
                float opacity, V3 ovGain, const EvePrep& prep, uint32_t neckRgb, uint32_t ankleRgb, std::vector<uint8_t>& out,
                const EveGainMap* map, const EveDetailLayer* detail) {
    const Lut& L = lut();
    out.resize(size_t(w) * h * 4);
    uint8_t tab[3][256];
    float g[3] = {gain.x, gain.y, gain.z};
    for (int c = 0; c < 3; ++c) for (int i = 0; i < 256; ++i) tab[c][i] = L.toByte(L.lin[i] * g[c]);
    const size_t n = size_t(w) * h;
    if (map && !map->empty()) {
        // the colour transfer's gain, bilinear over its cells, on top of `gain`
        for (int y = 0; y < h; ++y) {
            float gy = (y + 0.5f) / float(h) * map->gh - 0.5f;
            int y0 = std::clamp(int(std::floor(gy)), 0, map->gh - 1), y1 = std::min(y0 + 1, map->gh - 1);
            float fy = std::clamp(gy - float(y0), 0.0f, 1.0f);
            for (int x = 0; x < w; ++x) {
                float gx = (x + 0.5f) / float(w) * map->gw - 0.5f;
                int x0 = std::clamp(int(std::floor(gx)), 0, map->gw - 1), x1 = std::min(x0 + 1, map->gw - 1);
                float fx = std::clamp(gx - float(x0), 0.0f, 1.0f);
                const uint8_t* s = &base[(size_t(y) * w + x) * 4];
                uint8_t* d = &out[(size_t(y) * w + x) * 4];
                for (int c = 0; c < 3; ++c) {
                    auto at = [&](int cx, int cy) { return map->rgb[(size_t(cy) * map->gw + cx) * 3 + c]; };
                    float m = (at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy;
                    d[c] = L.toByte(L.lin[s[c]] * g[c] * m);
                }
                d[3] = 255;
            }
        }
    } else {
        for (size_t i = 0; i < n; ++i) {
            const uint8_t* s = &base[i * 4];
            uint8_t* d = &out[i * 4];
            d[0] = tab[0][s[0]]; d[1] = tab[1][s[1]]; d[2] = tab[2][s[2]]; d[3] = 255;
        }
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
    if (detail && detail->w == w && detail->h == h) eveApplyDetail(*detail, out);   // (Keep undead bones: under the seams' fade)
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

namespace {
// The mean linear colour of `rgba` in a (2r+1)^2 box at (u, v); false when nothing there is opaque.
bool boxLinear(const uint8_t* rgba, int w, int h, float u, float v, int r, V3& out) {
    const Lut& L = lut();
    int cx = std::clamp(int(u * w), 0, w - 1), cy = std::clamp(int(v * h), 0, h - 1);
    double s[3] = {0, 0, 0}; int n = 0;
    for (int y = std::max(0, cy - r); y <= std::min(h - 1, cy + r); ++y)
        for (int x = std::max(0, cx - r); x <= std::min(w - 1, cx + r); ++x) {
            const uint8_t* p = &rgba[(size_t(y) * w + x) * 4];
            if (p[3] < 128) continue;
            s[0] += L.lin[p[0]]; s[1] += L.lin[p[1]]; s[2] += L.lin[p[2]]; ++n;
        }
    if (!n) return false;
    out = V3(float(s[0] / n), float(s[1] / n), float(s[2] / n));
    return true;
}
} // namespace

EveGainMap eveTransferMap(const EveBody& eve, const std::vector<uint8_t>& base, int w, int h, const std::vector<M2Vertex>& wow,
                          const std::vector<uint8_t>& use, const uint8_t* skin, int sw, int sh, const float tint[3], float radius,
                          const std::vector<EveGainAnchor>& anchors) {
    EveGainMap map;
    if (eve.verts.empty() || !skin || sw <= 0 || sh <= 0 || w <= 0 || h <= 0 || !(radius > 0)) return map;
    // the model's skin samples, in a grid of cells `radius` wide
    struct Smp { V3 p, n, c; };
    std::vector<Smp> smp;
    const int rb = std::max(2, sw / 96);   // (about 1% of the texture: its painted shading kept, its pores not)
    for (size_t i = 0; i < wow.size() && i < use.size(); ++i) {
        if (!use[i]) continue;
        const M2Vertex& v = wow[i];
        V3 c;
        if (!boxLinear(skin, sw, sh, v.uv[0], v.uv[1], rb, c)) continue;
        smp.push_back({V3(v.pos[0], v.pos[1], v.pos[2]), V3(v.normal[0], v.normal[1], v.normal[2]).normalized(),
                       V3(c.x * tint[0], c.y * tint[1], c.z * tint[2])});
    }
    if (smp.empty()) return map;
    auto cell = [&](V3 p) { return std::array<int, 3>{int(std::floor(p.x / radius)), int(std::floor(p.y / radius)), int(std::floor(p.z / radius))}; };
    std::map<std::array<int, 3>, std::vector<uint32_t>> grid;
    for (size_t i = 0; i < smp.size(); ++i) grid[cell(smp[i].p)].push_back(uint32_t(i));
    // each Eve vertex: the model's colour about it over Eve's own
    const size_t nv = eve.verts.size();
    std::vector<V3> gain(nv, V3(1, 1, 1));
    std::vector<uint8_t> have(nv, 0);
    const int re = std::max(2, w / 96);
    const float s2 = radius * radius * 0.25f;   // (sigma: half the radius)
    for (size_t v = 0; v < nv; ++v) {
        const M2Vertex& ev = eve.verts[v];
        V3 p(ev.pos[0], ev.pos[1], ev.pos[2]), n = V3(ev.normal[0], ev.normal[1], ev.normal[2]).normalized(), own;
        if (!boxLinear(base.data(), w, h, ev.uv[0], ev.uv[1], re, own)) continue;
        auto c0 = cell(p);
        V3 acc; float wsum = 0; float best = 1e30f; V3 nearest; bool any = false;
        for (int dx = -1; dx <= 1; ++dx) for (int dy = -1; dy <= 1; ++dy) for (int dz = -1; dz <= 1; ++dz) {
            auto it = grid.find({c0[0] + dx, c0[1] + dy, c0[2] + dz});
            if (it == grid.end()) continue;
            for (uint32_t i : it->second) {
                const Smp& q = smp[i];
                float d2 = (q.p - p).dot(q.p - p);
                if (d2 < best) { best = d2; nearest = q.c; any = true; }
                if (d2 > radius * radius) continue;
                float facing = q.n.dot(n);
                if (facing <= 0.2f) continue;
                float wt = std::exp(-d2 / (2 * s2)) * facing;
                acc = acc + q.c * wt; wsum += wt;
            }
        }
        V3 target = wsum > 1e-6f ? acc * (1.0f / wsum) : (any ? nearest : V3());
        if (!(wsum > 1e-6f) && !any) continue;
        gain[v] = V3(std::clamp(target.x / std::max(own.x, 1e-4f), 0.01f, 6.0f), std::clamp(target.y / std::max(own.y, 1e-4f), 0.01f, 6.0f),
                     std::clamp(target.z / std::max(own.z, 1e-4f), 0.01f, 6.0f));
        have[v] = 1;
    }
    // the seams' own gains near them (smoothstep over each anchor's reach)
    for (size_t v = 0; v < nv; ++v) {
        V3 p = posOf(eve.verts[v]);
        for (const EveGainAnchor& an : anchors) {
            if (!(an.reach > 0) || an.points.empty()) continue;
            float d = 1e30f;
            for (const V3& q : an.points) d = std::min(d, (q - p).length());
            if (d >= an.reach) continue;
            float t = d / an.reach;
            t = t * t * (3 - 2 * t);
            gain[v] = have[v] ? an.gain * (1 - t) + gain[v] * t : an.gain;
            have[v] = 1;
        }
    }
    // over Eve's texture: each triangle's corners' gains, interpolated into cells of 8 x 8 texels, then filled and smoothed
    map.gw = std::max(1, w / 8); map.gh = std::max(1, h / 8);
    std::vector<double> acc(size_t(map.gw) * map.gh * 3, 0.0), wts(size_t(map.gw) * map.gh, 0.0);
    for (size_t t = 0; t + 2 < eve.indices.size(); t += 3) {
        uint32_t a = eve.indices[t], b = eve.indices[t + 1], c = eve.indices[t + 2];
        if (a >= nv || b >= nv || c >= nv || !have[a] || !have[b] || !have[c]) continue;
        raster(eve.verts[a].uv[0] * map.gw, eve.verts[a].uv[1] * map.gh, eve.verts[b].uv[0] * map.gw, eve.verts[b].uv[1] * map.gh,
               eve.verts[c].uv[0] * map.gw, eve.verts[c].uv[1] * map.gh, map.gw, map.gh, [&](int x, int y, float b0, float b1, float b2) {
                   V3 g = gain[a] * b0 + gain[b] * b1 + gain[c] * b2;
                   size_t i = size_t(y) * map.gw + x;
                   acc[i * 3] += g.x; acc[i * 3 + 1] += g.y; acc[i * 3 + 2] += g.z; wts[i] += 1;
               });
        // (a triangle smaller than a cell still marks its corners' cells)
        for (uint32_t k : {a, b, c}) {
            int x = std::clamp(int(eve.verts[k].uv[0] * map.gw), 0, map.gw - 1), y = std::clamp(int(eve.verts[k].uv[1] * map.gh), 0, map.gh - 1);
            size_t i = size_t(y) * map.gw + x;
            acc[i * 3] += gain[k].x; acc[i * 3 + 1] += gain[k].y; acc[i * 3 + 2] += gain[k].z; wts[i] += 1;
        }
    }
    map.rgb.assign(size_t(map.gw) * map.gh * 3, 0.0f);
    std::vector<uint8_t> filled(size_t(map.gw) * map.gh, 0);
    for (size_t i = 0; i < wts.size(); ++i)
        if (wts[i] > 0) { for (int c = 0; c < 3; ++c) map.rgb[i * 3 + c] = float(acc[i * 3 + c] / wts[i]); filled[i] = 1; }
    // empty cells (between the UV islands): the mean of their filled neighbours, a ring at a time (bilinear reads stay sane)
    for (int pass = 0; pass < 6; ++pass) {
        std::vector<float> nrgb = map.rgb;
        std::vector<uint8_t> nf = filled;
        for (int y = 0; y < map.gh; ++y) for (int x = 0; x < map.gw; ++x) {
            size_t i = size_t(y) * map.gw + x;
            if (filled[i]) continue;
            float s3[3] = {0, 0, 0}; int k = 0;
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= map.gw || yy >= map.gh) continue;
                size_t j = size_t(yy) * map.gw + xx;
                if (!filled[j]) continue;
                for (int c = 0; c < 3; ++c) s3[c] += map.rgb[j * 3 + c];
                ++k;
            }
            if (k) { for (int c = 0; c < 3; ++c) nrgb[i * 3 + c] = s3[c] / k; nf[i] = 1; }
        }
        map.rgb.swap(nrgb); filled.swap(nf);
    }
    for (size_t i = 0; i < filled.size(); ++i) if (!filled[i]) for (int c = 0; c < 3; ++c) map.rgb[i * 3 + c] = 1.0f;
    // smoothed once within the islands (3 x 3, filled cells only): no triangle edges in it
    {
        std::vector<float> sm = map.rgb;
        for (int y = 0; y < map.gh; ++y) for (int x = 0; x < map.gw; ++x) {
            size_t i = size_t(y) * map.gw + x;
            if (!wts[i]) continue;
            float s3[3] = {0, 0, 0}; int k = 0;
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= map.gw || yy >= map.gh || !wts[size_t(yy) * map.gw + xx]) continue;
                for (int c = 0; c < 3; ++c) s3[c] += map.rgb[(size_t(yy) * map.gw + xx) * 3 + c];
                ++k;
            }
            for (int c = 0; c < 3; ++c) sm[i * 3 + c] = s3[c] / k;
        }
        map.rgb.swap(sm);
    }
    return map;
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

// ================================================================================================ the model's own details
// Keep undead bones: a Forsaken woman's bones (knee joints, the spine, the spurs at her shoulders, elbows and hips) are pieces
// of her skin's own mesh, textured from her skin's texture, and her wounds, stitches and torn rims are painted into it; Eve's
// cut takes them all. Here they are found on the model's texture and baked into Eve's skin where her surface lies over them.

namespace {
// The point of triangle (a, b, c) nearest p, as barycentric weights (Ericson, Real-Time Collision Detection, 5.1.5).
V3 closestBary(V3 p, V3 a, V3 b, V3 c) {
    V3 ab = b - a, ac = c - a, ap = p - a;
    float d1 = ab.dot(ap), d2 = ac.dot(ap);
    if (d1 <= 0 && d2 <= 0) return V3(1, 0, 0);
    V3 bp = p - b;
    float d3 = ab.dot(bp), d4 = ac.dot(bp);
    if (d3 >= 0 && d4 <= d3) return V3(0, 1, 0);
    float vc = d1 * d4 - d3 * d2;
    if (vc <= 0 && d1 >= 0 && d3 <= 0) { float v = d1 / (d1 - d3); return V3(1 - v, v, 0); }
    V3 cp = p - c;
    float d5 = ab.dot(cp), d6 = ac.dot(cp);
    if (d6 >= 0 && d5 <= d6) return V3(0, 0, 1);
    float vb = d5 * d2 - d1 * d6;
    if (vb <= 0 && d2 >= 0 && d6 <= 0) { float t = d2 / (d2 - d6); return V3(1 - t, 0, t); }
    float va = d3 * d6 - d5 * d4;
    if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) { float t = (d4 - d3) / ((d4 - d3) + (d5 - d6)); return V3(0, 1 - t, t); }
    float den = 1.0f / (va + vb + vc);
    float v = vb * den, t = vc * den;
    return V3(1 - v - t, v, t);
}
V3 nrmOf(const M2Vertex& v) { return V3(v.normal[0], v.normal[1], v.normal[2]); }
float smooth01(float e0, float e1, float x) { float t = std::clamp((x - e0) / (e1 - e0), 0.0f, 1.0f); return t * t * (3 - 2 * t); }
} // namespace

EveDetailMask eveDetailMask(const std::vector<M2Vertex>& wow, const std::vector<uint32_t>& tris, const uint8_t* skin, int sw, int sh) {
    EveDetailMask m;
    if (!skin || sw <= 0 || sh <= 0 || tris.size() < 3) return m;
    const size_t nv = wow.size();
    // the islands: triangles joined by their vertices (a texture seam splits the vertices, so an island is one piece of the map)
    std::vector<uint32_t> up(nv);
    for (size_t i = 0; i < nv; ++i) up[i] = uint32_t(i);
    auto root = [&](uint32_t x) { while (up[x] != x) { up[x] = up[up[x]]; x = up[x]; } return x; };
    for (size_t t = 0; t + 2 < tris.size(); t += 3) {
        if (tris[t] >= nv || tris[t + 1] >= nv || tris[t + 2] >= nv) continue;
        uint32_t a = root(tris[t]), b = root(tris[t + 1]), c = root(tris[t + 2]);
        up[b] = a; up[root(c)] = a;
    }
    // the texels under them, each with its island
    std::vector<int32_t> island(size_t(sw) * sh, -1);
    for (size_t t = 0; t + 2 < tris.size(); t += 3) {
        uint32_t a = tris[t], b = tris[t + 1], c = tris[t + 2];
        if (a >= nv || b >= nv || c >= nv) continue;
        int32_t id = int32_t(root(a));
        raster(wow[a].uv[0] * sw, wow[a].uv[1] * sh, wow[b].uv[0] * sw, wow[b].uv[1] * sh, wow[c].uv[0] * sw, wow[c].uv[1] * sh, sw, sh,
               [&](int x, int y, float, float, float) { island[size_t(y) * sw + x] = id; });
        // (a triangle thinner than a texel still marks the texel under its centre)
        int x = std::clamp(int((wow[a].uv[0] + wow[b].uv[0] + wow[c].uv[0]) / 3 * sw), 0, sw - 1);
        int y = std::clamp(int((wow[a].uv[1] + wow[b].uv[1] + wow[c].uv[1]) / 3 * sh), 0, sh - 1);
        if (island[size_t(y) * sw + x] < 0) island[size_t(y) * sw + x] = id;
    }
    // the skin's tone: the commonest chroma (2-unit bins of a*, b*), its median lightness, their mean colour
    std::vector<V3> lab(size_t(sw) * sh);
    std::vector<uint32_t> hist(64 * 64, 0);
    auto rgbAt = [&](size_t i) { const uint8_t* p = &skin[i * 4]; return uint32_t(p[0]) << 16 | uint32_t(p[1]) << 8 | p[2]; };
    auto bin = [](float v) { return std::clamp(int(std::floor((v + 64.0f) / 2.0f)), 0, 63); };
    for (size_t i = 0; i < island.size(); ++i) {
        if (island[i] < 0) continue;
        lab[i] = eveLab(rgbAt(i));
        ++hist[size_t(bin(lab[i].y)) * 64 + size_t(bin(lab[i].z))];
        ++m.texels;
    }
    if (!m.texels) return m;
    size_t mode = size_t(std::max_element(hist.begin(), hist.end()) - hist.begin());
    const float ma = float(mode / 64) * 2 - 63, mb = float(mode % 64) * 2 - 63;   // (the bin's centre)
    std::vector<float> ls;
    for (size_t i = 0; i < island.size(); ++i)
        if (island[i] >= 0 && std::fabs(lab[i].y - ma) <= 3 && std::fabs(lab[i].z - mb) <= 3) ls.push_back(lab[i].x);
    std::nth_element(ls.begin(), ls.begin() + ptrdiff_t(ls.size() / 2), ls.end());
    const float ml = ls.empty() ? 70.0f : ls[ls.size() / 2];
    const Lut& L = lut();
    double acc[3] = {0, 0, 0}; size_t na = 0;
    for (size_t i = 0; i < island.size(); ++i) {
        if (island[i] < 0 || std::fabs(lab[i].y - ma) > 3 || std::fabs(lab[i].z - mb) > 3 || std::fabs(lab[i].x - ml) > 8) continue;
        for (int c = 0; c < 3; ++c) acc[c] += L.lin[skin[i * 4 + c]];
        ++na;
    }
    m.tone = na ? eveRgb(V3(float(acc[0] / na), float(acc[1] / na), float(acc[2] / na))) : rgbAt(0);
    const V3 tl = eveLab(m.tone);
    // each texel's distance from it (darker counts half, lighter little: the painted shading and highlights are skin), soft
    m.w = sw; m.h = sh;
    m.a.assign(size_t(sw) * sh, 0);
    std::unordered_map<int32_t, std::pair<double, size_t>> mean;   // island: its texels' summed detail, their count
    for (size_t i = 0; i < island.size(); ++i) {
        if (island[i] < 0) continue;
        // (a texel is detail well off the tone; an island is a piece when most of it is even a little off: a bone's highlights)
        float dl = lab[i].x - tl.x, c2 = (lab[i].y - tl.y) * (lab[i].y - tl.y) + (lab[i].z - tl.z) * (lab[i].z - tl.z);
        float dt = dl * (dl < 0 ? 0.25f : 0.15f), dp = dl * (dl < 0 ? 0.5f : 0.15f);
        float a = smooth01(14.0f, 22.0f, std::sqrt(dt * dt + c2));
        m.a[i] = uint8_t(a * 255.0f + 0.5f);
        auto& s = mean[island[i]];
        s.first += smooth01(9.0f, 16.0f, std::sqrt(dp * dp + c2)); ++s.second;
    }
    // an island mostly off the tone is a piece (a bone): all of it is detail, its highlights too
    std::vector<uint8_t> pieceIsland(nv, 0);
    for (const auto& [id, s] : mean)
        if (s.second && s.first / double(s.second) >= 0.5) { pieceIsland[size_t(id)] = 1; ++m.pieces; }
    for (size_t i = 0; i < island.size(); ++i) {
        if (island[i] >= 0 && pieceIsland[size_t(island[i])]) m.a[i] = 255;
        if (m.a[i] >= 128) ++m.detailTexels;
    }
    m.piece.assign(nv, 0);
    for (size_t t = 0; t + 2 < tris.size(); t += 3)
        for (int k = 0; k < 3; ++k) if (tris[t + k] < nv && pieceIsland[root(tris[t + k])]) m.piece[tris[t + k]] = 1;
    return m;
}

EveDetailLayer eveDetailLayer(const EveBody& eve, int w, int h, const std::vector<M2Vertex>& wow, const std::vector<uint32_t>& tris,
                              const uint8_t* skin, int sw, int sh, const EveDetailMask& mask, const float tint[3], float reach) {
    auto t0 = std::chrono::steady_clock::now();
    EveDetailLayer out;
    if (eve.verts.empty() || mask.empty() || !skin || mask.w != sw || mask.h != sh || w <= 0 || h <= 0 || !(reach > 0)) return out;
    const size_t nv = wow.size();
    // the model's body triangles, each with a bounding sphere, in a grid of cells by their centres
    struct Tri { uint32_t v[3]; V3 c; float r; };
    std::vector<Tri> mt;
    for (size_t t = 0; t + 2 < tris.size(); t += 3) {
        Tri x{{tris[t], tris[t + 1], tris[t + 2]}, V3(), 0};
        if (x.v[0] >= nv || x.v[1] >= nv || x.v[2] >= nv) continue;
        V3 a = posOf(wow[x.v[0]]), b = posOf(wow[x.v[1]]), c = posOf(wow[x.v[2]]);
        x.c = (a + b + c) * (1.0f / 3);
        x.r = std::max({(a - x.c).length(), (b - x.c).length(), (c - x.c).length()});
        mt.push_back(x);
    }
    if (mt.empty()) return out;
    // (a dense grid, cells a quarter of the reach, each triangle in every cell its sphere reaches: a query looks at its own few)
    const float cs = reach / 4;
    V3 lo3 = mt[0].c, hi3 = mt[0].c;
    for (const Tri& x : mt) {
        lo3 = V3(std::min(lo3.x, x.c.x - x.r), std::min(lo3.y, x.c.y - x.r), std::min(lo3.z, x.c.z - x.r));
        hi3 = V3(std::max(hi3.x, x.c.x + x.r), std::max(hi3.y, x.c.y + x.r), std::max(hi3.z, x.c.z + x.r));
    }
    const int gx = int((hi3.x - lo3.x) / cs) + 1, gy = int((hi3.y - lo3.y) / cs) + 1, gz = int((hi3.z - lo3.z) / cs) + 1;
    if (size_t(gx) * gy * gz > (1u << 22)) return out;   // (a model this large for its reach: nothing sensible to do)
    auto cellOf = [&](V3 p, int k) { float f = k == 0 ? (p.x - lo3.x) : k == 1 ? (p.y - lo3.y) : (p.z - lo3.z); return int(std::floor(f / cs)); };
    std::vector<uint32_t> start(size_t(gx) * gy * gz + 1, 0), items;
    auto cells = [&](const Tri& t, auto f) {
        int x0 = std::max(0, cellOf(t.c - V3(t.r, t.r, t.r), 0)), x1 = std::min(gx - 1, cellOf(t.c + V3(t.r, t.r, t.r), 0));
        int y0 = std::max(0, cellOf(t.c - V3(t.r, t.r, t.r), 1)), y1 = std::min(gy - 1, cellOf(t.c + V3(t.r, t.r, t.r), 1));
        int z0 = std::max(0, cellOf(t.c - V3(t.r, t.r, t.r), 2)), z1 = std::min(gz - 1, cellOf(t.c + V3(t.r, t.r, t.r), 2));
        for (int z = z0; z <= z1; ++z) for (int y = y0; y <= y1; ++y) for (int x = x0; x <= x1; ++x) f((size_t(z) * gy + y) * gx + x);
    };
    for (const Tri& x : mt) cells(x, [&](size_t c) { ++start[c + 1]; });
    for (size_t i = 1; i < start.size(); ++i) start[i] += start[i - 1];
    items.resize(start.back());
    {
        std::vector<uint32_t> fill(start.begin(), start.end() - 1);
        for (size_t i = 0; i < mt.size(); ++i) cells(mt[i], [&](size_t c) { items[fill[c]++] = uint32_t(i); });
    }
    std::vector<uint32_t> stamp(mt.size(), 0);
    uint32_t query = 0;
    // the model's triangles whose spheres come within `rad` of p (each once)
    auto gather = [&](V3 p, float rad, std::vector<uint32_t>& outList) {
        outList.clear();
        ++query;
        float span = rad;
        int x0 = std::max(0, cellOf(p - V3(span, span, span), 0)), x1 = std::min(gx - 1, cellOf(p + V3(span, span, span), 0));
        int y0 = std::max(0, cellOf(p - V3(span, span, span), 1)), y1 = std::min(gy - 1, cellOf(p + V3(span, span, span), 1));
        int z0 = std::max(0, cellOf(p - V3(span, span, span), 2)), z1 = std::min(gz - 1, cellOf(p + V3(span, span, span), 2));
        for (int z = z0; z <= z1; ++z) for (int y = y0; y <= y1; ++y) for (int x = x0; x <= x1; ++x) {
            size_t c = (size_t(z) * gy + y) * gx + x;
            for (uint32_t k = start[c]; k < start[c + 1]; ++k) {
                uint32_t i = items[k];
                if (stamp[i] == query) continue;
                stamp[i] = query;
                if ((mt[i].c - p).length() - mt[i].r <= rad) outList.push_back(i);
            }
        }
    };
    // the nearest point among `cand` facing n, within `best` (squared; updated): its triangle and weights. With `keys` (each
    // candidate's sphere's distance from a centre within `slack` of p, ascending), it stops where none further can be nearer.
    auto nearest = [&](V3 p, V3 n, const std::vector<uint32_t>& cand, float& best, int& bt, V3& bb, const std::vector<float>* keys = nullptr,
                       float slack = 0) {
        for (size_t j = 0; j < cand.size(); ++j) {
            if (keys && (*keys)[j] - slack > 0 && ((*keys)[j] - slack) * ((*keys)[j] - slack) >= best) break;
            const Tri& x = mt[cand[j]];
            const uint32_t i = cand[j];
            float lim = std::sqrt(best) + x.r;
            if ((x.c - p).dot(x.c - p) > lim * lim) continue;
            const M2Vertex &A = wow[x.v[0]], &B = wow[x.v[1]], &C = wow[x.v[2]];
            V3 b = closestBary(p, posOf(A), posOf(B), posOf(C));
            V3 q = posOf(A) * b.x + posOf(B) * b.y + posOf(C) * b.z;
            float d2 = (q - p).dot(q - p);
            if (d2 >= best) continue;
            V3 qn = nrmOf(A) * b.x + nrmOf(B) * b.y + nrmOf(C) * b.z;
            if (qn.dot(n) <= 0.1f * qn.length()) continue;   // (the far side of a limb, the other thigh: not hers)
            best = d2; bt = int(i); bb = b;
        }
    };
    out.w = w; out.h = h;
    out.rgba.assign(size_t(w) * h * 4, 0);
    std::vector<uint8_t> done(size_t(w) * h, 0);
    const Lut& L = lut();
    const size_t ev = eve.verts.size();
    // each Eve corner's own nearest distance first: it bounds what her triangle's texels need to look at
    struct Own { int x, y; V3 b; };
    std::vector<Own> own;
    std::vector<uint32_t> hint;
    std::vector<int32_t> found(size_t(w) * h, -2);   // per lattice texel: the model triangle nearest it (-1 none, -2 not searched)
    std::vector<float> cornerD(ev, -1), keys;
    std::vector<uint32_t> cand;
    std::vector<std::pair<float, uint32_t>> order;
    for (size_t v = 0; v < ev; ++v) {
        V3 p = posOf(eve.verts[v]), n = nrmOf(eve.verts[v]).normalized();
        float best = 0; int bt = -1; V3 bb;
        for (float rr : {0.35f * reach, reach}) {   // (most lie within a third of the reach: a small gather first)
            gather(p, rr, cand);
            best = rr * rr;
            nearest(p, n, cand, best, bt, bb);
            if (bt >= 0) break;
        }
        if (bt >= 0) cornerD[v] = std::sqrt(best);
    }
    for (size_t t = 0; t + 2 < eve.indices.size(); t += 3) {
        uint32_t a = eve.indices[t], b = eve.indices[t + 1], c = eve.indices[t + 2];
        if (a >= ev || b >= ev || c >= ev) continue;
        const M2Vertex &A = eve.verts[a], &B = eve.verts[b], &C = eve.verts[c];
        V3 pa = posOf(A), pb = posOf(B), pc = posOf(C), centre = (pa + pb + pc) * (1.0f / 3);
        float r = std::max({(pa - centre).length(), (pb - centre).length(), (pc - centre).length()});
        float farD = std::max({cornerD[a], cornerD[b], cornerD[c]});
        bool all = cornerD[a] >= 0 && cornerD[b] >= 0 && cornerD[c] >= 0;
        float rad = r + (all ? std::min(reach, farD + 2 * r) : reach) + 0.005f * reach / 0.1f;
        gather(centre, rad, cand);
        if (cand.empty()) continue;
        // nearest first (by their spheres from her triangle's centre): each texel stops once the rest are all farther
        order.clear();
        for (uint32_t i : cand) order.push_back({(mt[i].c - centre).length() - mt[i].r, i});
        std::sort(order.begin(), order.end());
        keys.resize(order.size());
        for (size_t j = 0; j < order.size(); ++j) { keys[j] = order[j].first; cand[j] = order[j].second; }
        V3 na = nrmOf(A), nb = nrmOf(B), nc = nrmOf(C);
        // her texels this triangle owns; those on the even lattice searched in full first, then each of the others among the
        // nearest triangles its lattice neighbours found (the same answer but where a triangle is nearest to none of the four)
        own.clear();
        raster(A.uv[0] * w, A.uv[1] * h, B.uv[0] * w, B.uv[1] * h, C.uv[0] * w, C.uv[1] * h, w, h, [&](int x, int y, float b0, float b1, float b2) {
            size_t i = size_t(y) * w + x;
            if (done[i]) return;   // (a texel two triangles share: the first one's)
            done[i] = 1;
            ++out.texels;
            own.push_back({x, y, V3(b0, b1, b2)});
        });
        for (int pass = 0; pass < 2; ++pass)
            for (const Own& o : own) {
                bool lattice = !(o.x & 1) && !(o.y & 1);
                if (lattice != (pass == 0)) continue;
                size_t i = size_t(o.y) * w + o.x;
                V3 p = pa * o.b.x + pb * o.b.y + pc * o.b.z, n = (na * o.b.x + nb * o.b.y + nc * o.b.z).normalized();
                float best = reach * reach; int bt = -1; V3 bb;
                if (!lattice) {
                    hint.clear();
                    int x0 = o.x & ~1, y0 = o.y & ~1, have = 0;
                    for (int dy = 0; dy <= 2; dy += 2) for (int dx = 0; dx <= 2; dx += 2) {
                        int xx = x0 + dx, yy = y0 + dy;
                        if (xx >= w || yy >= h) continue;
                        int32_t f = found[size_t(yy) * w + xx];
                        if (f == -2) continue;
                        ++have;
                        if (f >= 0 && std::find(hint.begin(), hint.end(), uint32_t(f)) == hint.end()) hint.push_back(uint32_t(f));
                    }
                    if (have >= 2 && !hint.empty()) nearest(p, n, hint, best, bt, bb);
                    if (bt < 0) { best = reach * reach; nearest(p, n, cand, best, bt, bb, &keys, r); }
                } else {
                    nearest(p, n, cand, best, bt, bb, &keys, r);
                    found[i] = bt;
                }
                if (bt < 0) continue;
                const Tri& q = mt[size_t(bt)];
                float u = wow[q.v[0]].uv[0] * bb.x + wow[q.v[1]].uv[0] * bb.y + wow[q.v[2]].uv[0] * bb.z;
                float v = wow[q.v[0]].uv[1] * bb.x + wow[q.v[1]].uv[1] * bb.y + wow[q.v[2]].uv[1] * bb.z;
                int sx = std::clamp(int(u * sw), 0, sw - 1), sy = std::clamp(int(v * sh), 0, sh - 1);   // (the nearest texel: no blur)
                size_t si = size_t(sy) * sw + sx;
                float cover = mask.a[si] / 255.0f * (1 - smooth01(0.5f * reach, reach, std::sqrt(best)));
                uint8_t* d = &out.rgba[i * 4];
                for (int k = 0; k < 3; ++k) d[k] = L.toByte(L.lin[skin[si * 4 + k]] * tint[k]);
                d[3] = uint8_t(std::clamp(cover, 0.0f, 1.0f) * 255.0f + 0.5f);
                if (d[3]) ++out.covered;
            }
    }
    // her coverage's edges softened (3 x 3 over her own texels: separable sums), then the layer grown 3 texels past her
    // islands (a ring at a time from their edges: mip levels never read past them into nothing)
    {
        const size_t n = size_t(w) * h;
        std::vector<uint32_t> rs(n, 0);
        std::vector<uint8_t> rc(n, 0);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                uint32_t sum = 0; uint8_t k = 0;
                for (int dx = -1; dx <= 1; ++dx) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    size_t j = size_t(y) * w + xx;
                    if (done[j]) { sum += out.rgba[j * 4 + 3]; ++k; }
                }
                rs[size_t(y) * w + x] = sum; rc[size_t(y) * w + x] = k;
            }
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                size_t i = size_t(y) * w + x;
                if (!done[i]) continue;
                uint32_t sum = 0, k = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    sum += rs[size_t(yy) * w + x]; k += rc[size_t(yy) * w + x];
                }
                out.rgba[i * 4 + 3] = uint8_t((sum + k / 2) / k);
            }
    }
    {
        std::vector<size_t> ring, next;
        std::vector<uint8_t> queued(size_t(w) * h, 0);
        auto around = [&](size_t i, auto f) {
            int x = int(i % size_t(w)), y = int(i / size_t(w));
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                int xx = x + dx, yy = y + dy;
                if ((dx || dy) && xx >= 0 && yy >= 0 && xx < w && yy < h) f(size_t(yy) * w + xx);
            }
        };
        for (size_t i = 0; i < done.size(); ++i)
            if (done[i]) around(i, [&](size_t j) { if (!done[j] && !queued[j]) { queued[j] = 1; ring.push_back(j); } });
        std::sort(ring.begin(), ring.end());
        std::vector<uint8_t> grown;
        for (int pass = 0; pass < 3 && !ring.empty(); ++pass) {
            grown.assign(ring.size() * 4, 0);
            for (size_t r2 = 0; r2 < ring.size(); ++r2) {
                int sum[4] = {0, 0, 0, 0}, k = 0;
                around(ring[r2], [&](size_t j) { if (done[j]) { for (int c = 0; c < 4; ++c) sum[c] += out.rgba[j * 4 + c]; ++k; } });
                for (int c = 0; c < 4; ++c) grown[r2 * 4 + c] = uint8_t(k ? (sum[c] + k / 2) / k : 0);
            }
            next.clear();
            for (size_t r2 = 0; r2 < ring.size(); ++r2) { std::memcpy(&out.rgba[ring[r2] * 4], &grown[r2 * 4], 4); done[ring[r2]] = 1; }
            for (size_t i : ring) around(i, [&](size_t j) { if (!done[j] && !queued[j]) { queued[j] = 1; next.push_back(j); } });
            std::sort(next.begin(), next.end());
            out.grown += ring.size();
            ring.swap(next);
        }
    }
    out.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return out;
}

void eveApplyDetail(const EveDetailLayer& layer, std::vector<uint8_t>& rgba) {
    if (layer.empty() || rgba.size() < size_t(layer.w) * layer.h * 4) return;
    const Lut& L = lut();
    for (size_t i = 0, n = size_t(layer.w) * layer.h; i < n; ++i) {
        const uint8_t* s = &layer.rgba[i * 4];
        if (!s[3]) continue;
        float a = s[3] / 255.0f;
        uint8_t* d = &rgba[i * 4];
        for (int c = 0; c < 3; ++c) d[c] = L.toByte(L.lin[d[c]] * (1 - a) + L.lin[s[c]] * a);
    }
}

void eveDetailClean(uint8_t* skin, int sw, int sh, const EveDetailMask& mask) {
    if (!skin || mask.empty() || sw <= 0 || sh <= 0) return;
    const Lut& L = lut();
    V3 t = eveLinear(mask.tone);
    const float tc[3] = {t.x, t.y, t.z};
    for (int y = 0; y < sh; ++y) {
        int my = std::min(mask.h - 1, int((y + 0.5f) * mask.h / sh));
        for (int x = 0; x < sw; ++x) {
            int mx = std::min(mask.w - 1, int((x + 0.5f) * mask.w / sw));
            uint8_t m = mask.a[size_t(my) * mask.w + mx];
            if (!m) continue;
            float a = m / 255.0f;
            uint8_t* d = &skin[(size_t(y) * sw + x) * 4];
            for (int c = 0; c < 3; ++c) d[c] = L.toByte(L.lin[d[c]] * (1 - a) + tc[c] * a);
        }
    }
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

// The sculpt's rig (sculpt.h) on the Eve rig's joints: its regions about her own joints, not the model's.
static SculptRig eveSculptRig(const M2Model& m, const std::vector<V3>& pivots) {
    SculptBones b;
    auto find = [&](const char* a, const char* alt = nullptr) { int i = m.findWowBone(a); return i >= 0 || !alt ? i : m.findWowBone(alt); };
    b.pelvis = find("Pelvis", "Root"); b.waist = find("Spine1", "Waist"); b.chest = find("Chest", "Spine2");
    b.neck = find("Neck1"); b.head = find("Head");
    for (int k = 0; k < 2; ++k) {
        std::string sd = k ? "R_" : "L_";
        b.breast[k] = m.findWowBone(sd + "Breast"); b.clavicle[k] = m.findWowBone(sd + "Clavicle"); b.upperArm[k] = m.findWowBone(sd + "UpperArm");
        b.forearm[k] = m.findWowBone(sd + "Forearm"); b.hand[k] = m.findWowBone(sd + "Hand"); b.thigh[k] = m.findWowBone(sd + "Thigh");
        b.calf[k] = m.findWowBone(sd + "Calf"); b.ankle[k] = m.findWowBone(sd + "Ankle");
    }
    std::vector<int16_t> parents(m.bones.size());
    for (size_t i = 0; i < m.bones.size(); ++i) parents[i] = m.bones[i].parent;
    return sculptRig(pivots, parents, b);
}

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
    if (!splitMesh && job.fit.ankleMatch > 0) {   // two more loops above the ankle rings (made once per set)
        const std::string key = job.mesh->variant + "|ankles0.30";
        {
            std::lock_guard<std::mutex> lock(set.cacheMutex);
            for (auto& sp : set.splits) if (sp.first == key) splitMesh = sp.second;
        }
        if (!splitMesh) {
            splitMesh = std::make_shared<EveMesh>(eveRefineAnkles(*job.mesh, 0.3f));
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
        applySculpt(body.rigPivots.empty() ? job.rig : eveSculptRig(m, body.rigPivots), body.verts, body.indices, job.sculpt.data(), job.sculpt.size());
    auto t1 = std::chrono::steady_clock::now();
    // the actor's own skin where Eve meets it: its base skin layer sampled at the cut edges
    const EveSetIndex& ix = set.index;
    uint32_t neck = ix.skin.neck, ankle = ix.skin.ankle;
    Image img;   // (the model's base skin: the seams' colours, the colour transfer)
    bool haveImg = false;
    {
        Bytes blp;
        if (!job.skinPath.empty() && readFile(job.skinPath, blp) && decodeBlp(blp, 512, img)) {
            haveImg = true;
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
    // the model's shown skin (its type-1 sections in the shown geosets), cut or kept
    std::vector<uint8_t> use(verts.size(), 0);
    {
        bool all = job.geosets.empty() || (job.geosets.size() == 1 && job.geosets[0] == 0xFFFF);
        std::vector<uint8_t> skinSub(job.subs.size(), 0);
        for (const M2Batch& bt : m.batches) {
            if (bt.submesh >= job.subs.size() || bt.textureCombo >= m.textureCombos.size()) continue;
            uint16_t ti = m.textureCombos[bt.textureCombo];
            if (ti < m.textures.size() && m.textures[ti].type == 1) skinSub[bt.submesh] = 1;
        }
        for (size_t sI = 0; sI < job.subs.size(); ++sI) {
            if (!skinSub[sI] || (!all && std::find(job.geosets.begin(), job.geosets.end(), job.subs[sI].id) == job.geosets.end())) continue;
            for (uint32_t t = job.subs[sI].indexStart; t < job.subs[sI].indexStart + job.subs[sI].indexCount && t < job.indices.size(); ++t)
                if (job.indices[t] < use.size()) use[job.indices[t]] = 1;
        }
    }
    // Keep undead bones (her fit row's "detail", her options' bones): the model's own details found on its skin texture (read
    // whole: 1024 texels), baked into Eve's skin by where her surface lies over its body, and taken out of what the colour
    // transfer reads (no brown haze round a bone); with "detail" 2 its bone pieces are also kept, uncut
    std::shared_ptr<EveSet::Detail> keepDetail;   // (held while it is used: the cache may drop it meanwhile)
    const EveDetailMask* dmask = nullptr;
    const EveDetailLayer* dlayer = nullptr;
    std::vector<uint8_t> cleaned;
    if (job.opt.bones && job.fit.detail > 0 && haveImg) {
        auto td = std::chrono::steady_clock::now();
        // the body Eve takes the place of: the shown skin sections' triangles her cut removes
        std::vector<uint32_t> tris;
        {
            bool all = job.geosets.empty() || (job.geosets.size() == 1 && job.geosets[0] == 0xFFFF);
            std::vector<uint8_t> taken(job.subs.size(), 0);
            for (const M2Batch& bt : m.batches) {
                if (bt.submesh >= job.subs.size() || taken[bt.submesh] || bt.textureCombo >= m.textureCombos.size()) continue;
                uint16_t ti = m.textureCombos[bt.textureCombo];
                if (ti >= m.textures.size() || m.textures[ti].type != 1) continue;
                taken[bt.submesh] = 1;   // (a section two batches draw: once)
                const M2Submesh& sm = job.subs[bt.submesh];
                if (!all && std::find(job.geosets.begin(), job.geosets.end(), sm.id) == job.geosets.end()) continue;
                for (uint32_t t = sm.indexStart; t + 2 < sm.indexStart + sm.indexCount && t + 2 < job.indices.size(); t += 3) {
                    uint32_t a = job.indices[t], b = job.indices[t + 1], c = job.indices[t + 2];
                    if (a >= body.cut.size() || b >= body.cut.size() || c >= body.cut.size() || !(body.cut[a] || body.cut[b] || body.cut[c])) continue;
                    tris.push_back(a); tris.push_back(b); tris.push_back(c);
                }
            }
        }
        // made once per fitted body, skin, tint and texture (the set's cache: a look change that leaves her body alone is free)
        uint64_t dkey = uint64_t(eveCrc32(reinterpret_cast<const uint8_t*>(body.verts.data()), body.verts.size() * sizeof(M2Vertex))) << 32 |
                        eveCrc32(reinterpret_cast<const uint8_t*>(tris.data()), tris.size() * sizeof(uint32_t));
        dkey ^= uint64_t(eveCrc32(reinterpret_cast<const uint8_t*>(job.skinPath.data()), job.skinPath.size())) * 0x9E3779B97F4A7C15ull;
        dkey ^= uint64_t(eveCrc32(reinterpret_cast<const uint8_t*>(job.tint), sizeof job.tint)) << 17;
        dkey ^= (uint64_t(uint32_t(tex->w)) << 40) ^ (uint64_t(uint32_t(tex->h)) << 8) ^ uint64_t(job.fit.detail * 1000.0f) ^ (uint64_t(m.fileId) << 20);
        std::shared_ptr<EveSet::Detail> det;
        {
            std::lock_guard<std::mutex> lock(set.cacheMutex);
            for (auto& d : set.details) if (d.first == dkey) det = d.second;
        }
        if (!det) {
            Image whole;
            Bytes blp;
            if (readFile(job.skinPath, blp) && decodeBlp(blp, 1024, whole)) {
                det = std::make_shared<EveSet::Detail>();
                det->mask = eveDetailMask(verts, tris, whole.rgba.data(), whole.width, whole.height);
                if (!det->mask.empty())
                    det->layer = eveDetailLayer(body, tex->w, tex->h, verts, tris, whole.rgba.data(), whole.width, whole.height, det->mask, job.tint,
                                                0.10f * body.stats.unit);
                std::lock_guard<std::mutex> lock(set.cacheMutex);
                set.details.push_back({dkey, det});
                if (set.details.size() > 2) set.details.erase(set.details.begin());   // (5 MB each)
            }
        }
        if (det && !det->mask.empty()) {
            dmask = &det->mask;
            dlayer = det->layer.empty() ? nullptr : &det->layer;
            cleaned = img.rgba;
            eveDetailClean(cleaned.data(), img.width, img.height, *dmask);
            r.detail = true;
            r.detailPieces = dmask->pieces;
            r.detailCovered = det->layer.covered;
            keepDetail = det;
        }
        r.detailMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - td).count();
    }
    // the colour transfer (her own skin, tinted): Eve takes the model's skin tones where she lies
    EveGainMap map;
    if (!tone && haveImg && job.fit.transfer > 0) {
        // the neck and the ankles anchored to the gains that match the model's skin at their cut edges
        std::vector<EveGainAnchor> anchors;
        const char* rn[3] = {"neck", "L_ankle", "R_ankle"};
        for (int k = 0; k < 3; ++k) {
            const std::vector<uint32_t>* ring = mesh.ring(rn[k]);
            if (!ring || ring->empty()) continue;
            EveGainAnchor an;
            for (uint32_t v : *ring) if (v < body.verts.size()) an.points.push_back(posOf(body.verts[v]));
            uint32_t ref = k ? (tex->prep.ankle ? tex->prep.ankle : r.reference) : r.reference;
            an.gain = eveGain(k ? ankle : neck, ref);
            an.reach = 0.10f * body.stats.unit;
            anchors.push_back(an);
        }
        map = eveTransferMap(body, tex->rgba, tex->w, tex->h, verts, use, cleaned.empty() ? img.rgba.data() : cleaned.data(), img.width, img.height, job.tint,
                             job.fit.transfer * body.stats.unit, anchors);
        if (!map.empty()) gain = V3(1, 1, 1);
    }
    eveCompose(tex->rgba, tex->w, tex->h, gain, ov ? ov->rgba.data() : nullptr, ov ? ov->w : 0, ov ? ov->h : 0, shade ? shade->rect[0] : 0,
               shade ? shade->rect[1] : 0, std::clamp(job.opt.opacity, 0.0f, 1.0f), ovGain, tex->prep, neck, ankle, r.rgba, &map,
               dlayer);
    // the seams measured: Eve's skin 1.5 to 8 cm from each ring against the model's own at its cut edge there (what meets her)
    if (haveImg) {
        const char* rn[3] = {"neck", "L_ankle", "R_ankle"};
        const float u = body.stats.unit;
        for (int k = 0; k < 3; ++k) {
            const std::vector<uint32_t>* ring = mesh.ring(rn[k]);
            if (!ring || ring->empty()) continue;
            // her skin 4 cm in from the ring: along each edge from a ring vertex into the body (the mesh is sparse there)
            std::vector<uint8_t> onRing(body.verts.size(), 0);
            for (uint32_t v : *ring) if (v < onRing.size()) onRing[v] = 1;
            V3 e3; int ne = 0;
            for (size_t t = 0; t + 2 < body.indices.size(); t += 3)
                for (int a = 0; a < 3; ++a) {
                    uint32_t v0 = body.indices[t + a], v1 = body.indices[t + (a + 1) % 3];
                    if (v0 >= onRing.size() || v1 >= onRing.size() || !onRing[v0] || onRing[v1]) continue;
                    const M2Vertex& A = body.verts[v0];
                    const M2Vertex& B = body.verts[v1];
                    float len = (posOf(B) - posOf(A)).length();
                    if (len < 1e-5f) continue;
                    float f = std::min(1.0f, 0.04f * u / len);
                    V3 c;
                    if (boxLinear(r.rgba.data(), tex->w, tex->h, A.uv[0] + (B.uv[0] - A.uv[0]) * f, A.uv[1] + (B.uv[1] - A.uv[1]) * f, 2, c)) { e3 = e3 + c; ++ne; }
                }
            if (ne) r.seamDE[k] = (eveLab(eveRgb(e3 * (1.0f / ne))) - eveLab(k ? ankle : neck)).length();
        }
    }
    r.w = tex->w; r.h = tex->h;
    if (job.mips) r.mips = eveMips(r.rgba, r.w, r.h);
    r.neckRgb = neck; r.ankleRgb = ankle;
    r.composeMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();
    // the body under it cut (any corner on a body bone drops the triangle), and the two measured as one
    r.cutSubs = job.subs;
    if (r.detail && job.fit.detail >= 2) {   // (the bone pieces kept: their triangles stay uncut)
        std::vector<bool> keep = body.cut;
        for (size_t v = 0; v < keep.size() && v < dmask->piece.size(); ++v) if (dmask->piece[v] && keep[v]) { keep[v] = false; ++r.detailKept; }
        r.cutIndices = cutTriangles(job.indices, r.cutSubs, keep, 1);
    } else {
        r.cutIndices = cutTriangles(job.indices, r.cutSubs, body.cut, 1);
    }
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
