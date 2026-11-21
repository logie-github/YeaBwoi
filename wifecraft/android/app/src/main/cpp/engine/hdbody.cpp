// HD Models: the .whd set, its fit to a game model, its textures (hdbody.h). GL-free (engine-test/hdbody_test.cpp).
#include "hdbody.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <functional>
#include <sstream>
#include <unordered_map>

#include "evebody.h"   // decodePng
#include "json.h"

namespace wc {

namespace {

uint32_t rd32(const uint8_t* p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }
uint64_t rd64(const uint8_t* p) { return uint64_t(rd32(p)) | uint64_t(rd32(p + 4)) << 32; }

uint32_t hexRgb(const std::string& s) {
    uint32_t v = 0;
    for (char c : s) {
        int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        if (d < 0) return 0xFFFFFF;
        v = v << 4 | uint32_t(d);
    }
    return v & 0xFFFFFF;
}

std::vector<int> intList(const Json& j) {
    std::vector<int> out;
    for (size_t i = 0; i < j.size(); ++i) out.push_back(int(j[i].num(-1)));
    return out;
}

// a rotation taking unit vector a onto unit vector b (the shortest arc)
M4 rotateOnto(V3 a, V3 b) {
    a = a.normalized(); b = b.normalized();
    V3 ax = a.cross(b);
    float s = ax.length(), c = std::clamp(a.dot(b), -1.0f, 1.0f);
    M4 r;
    if (s < 1e-6f) {
        if (c > 0) return r;
        // opposite: half a turn about any axis across a
        V3 o = std::fabs(a.x) < 0.9f ? V3(1, 0, 0) : V3(0, 1, 0);
        ax = a.cross(o).normalized();
        s = 0; c = -1;
    } else ax = ax * (1.0f / s);
    float t = 1 - c;
    float x = ax.x, y = ax.y, z = ax.z;
    r.at(0, 0) = t * x * x + c;     r.at(0, 1) = t * x * y - s * z; r.at(0, 2) = t * x * z + s * y;
    r.at(1, 0) = t * x * y + s * z; r.at(1, 1) = t * y * y + c;     r.at(1, 2) = t * y * z - s * x;
    r.at(2, 0) = t * x * z - s * y; r.at(2, 1) = t * y * z + s * x; r.at(2, 2) = t * z * z + c;
    return r;
}

V3 dirOf(const M4& m, V3 v) { return V3(m.at(0, 0) * v.x + m.at(0, 1) * v.y + m.at(0, 2) * v.z, m.at(1, 0) * v.x + m.at(1, 1) * v.y + m.at(1, 2) * v.z,
                                     m.at(2, 0) * v.x + m.at(2, 1) * v.y + m.at(2, 2) * v.z); }

M4 about(V3 c, const M4& r) { return M4::translation(c) * r * M4::translation(c * -1.0f); }

} // namespace

const V3* HdSet::joint(const std::string& name) const {
    for (const auto& [n, p] : joints) if (n == name) return &p;
    return nullptr;
}

int HdSet::mesh(const std::string& id) const {
    for (size_t i = 0; i < meshes.size(); ++i) if (meshes[i].id == id) return int(i);
    return -1;
}

const uint8_t* HdSet::blob(int i, size_t& size) const {
    if (i < 0 || size_t(i) >= blobs.size()) { size = 0; return nullptr; }
    size = size_t(blobs[size_t(i)].second);
    return file.data() + blobs[size_t(i)].first;
}

bool parseWhd(const uint8_t* d, size_t size, HdSet& out, std::string& error) {
    out = HdSet();
    if (size < 16 || std::memcmp(d, "WHD1", 4) != 0) { error = "not an HD model set (.whd)"; return false; }
    uint32_t version = rd32(d + 4), jl = rd32(d + 8), nb = rd32(d + 12);
    if (version > kWhdVersion) { error = "made by a newer converter (version " + std::to_string(version) + ")"; return false; }
    if (16ull + jl > size) { error = "cut short"; return false; }
    Json j;
    std::string jerr;
    if (!Json::parse(std::string(reinterpret_cast<const char*>(d + 16), jl), j, &jerr)) { error = "its index can't be read: " + jerr; return false; }
    size_t table = (16 + size_t(jl) + 15) & ~size_t(15);
    if (table + 16ull * nb > size) { error = "cut short"; return false; }
    out.file.assign(d, d + size);
    for (uint32_t i = 0; i < nb; ++i) {
        uint64_t o = rd64(d + table + 16 * i), s = rd64(d + table + 16 * i + 8);
        if (o > size || s > size - o) { error = "a part lies past its end"; return false; }
        out.blobs.push_back({o, s});
    }
    out.id = j["id"].str(); out.label = j["label"].str();
    out.race = int(j["race"].num()); out.sex = int(j["sex"].num());
    out.gpuBytes = uint64_t(j["gpuBytes"].num());
    for (size_t i = 0; i < j["bones"].size(); ++i) out.bones.push_back(j["bones"][i].str());
    if (out.bones.empty() || out.bones.size() > 255) { error = "no bones"; return false; }
    for (const auto& [name, v] : j["joints"].o) out.joints.push_back({name, V3(float(v[0].num()), float(v[1].num()), float(v[2].num()))});
    for (size_t i = 0; i < j["textures"].size(); ++i) {
        const Json& t = j["textures"][i];
        HdTexInfo ti{t["id"].str(), int(t["w"].num()), int(t["h"].num()), t["alpha"].kind == Json::Bool && t["alpha"].b, int(t["blob"].num(-1))};
        out.textures.push_back(ti);
    }
    auto blobOk = [&](int b) { return b >= 0 && size_t(b) < out.blobs.size(); };
    for (size_t i = 0; i < j["meshes"].size(); ++i) {
        const Json& mj = j["meshes"][i];
        HdMesh m;
        m.id = mj["id"].str(); m.kind = mj["kind"].str();
        size_t nv = size_t(mj["vertices"].num()), nt = size_t(mj["triangles"].num());
        int vb = int(mj["vertexBlob"].num(-1)), ib = int(mj["indexBlob"].num(-1));
        if (!blobOk(vb) || !blobOk(ib) || out.blobs[size_t(vb)].second != nv * kWhdVertexBytes || out.blobs[size_t(ib)].second != nt * 12) {
            error = "mesh " + m.id + " doesn't match its data"; return false;
        }
        const uint8_t* v = d + out.blobs[size_t(vb)].first;
        m.pos.resize(nv * 3); m.nrm.resize(nv * 3); m.uv.resize(nv * 2); m.bi.resize(nv * 4); m.bw.resize(nv * 4);
        for (size_t k = 0; k < nv; ++k, v += kWhdVertexBytes) {
            std::memcpy(&m.pos[k * 3], v, 12); std::memcpy(&m.nrm[k * 3], v + 12, 12); std::memcpy(&m.uv[k * 2], v + 24, 8);
            std::memcpy(&m.bi[k * 4], v + 32, 4); std::memcpy(&m.bw[k * 4], v + 36, 4);
            for (int q = 0; q < 4; ++q) if (m.bw[k * 4 + q] && m.bi[k * 4 + q] >= out.bones.size()) { error = "mesh " + m.id + ": a bone past the list"; return false; }
        }
        m.idx.resize(nt * 3);
        std::memcpy(m.idx.data(), d + out.blobs[size_t(ib)].first, nt * 12);
        for (uint32_t x : m.idx) if (x >= nv) { error = "mesh " + m.id + ": a triangle past its vertices"; return false; }
        for (size_t p = 0; p < mj["parts"].size(); ++p) {
            const Json& pj = mj["parts"][p];
            HdPart part{uint32_t(pj["start"].num()), uint32_t(pj["count"].num()), int(pj["texture"].num(-1)), pj["alpha"].str() == "test"};
            if (size_t(part.start) + part.count > m.idx.size()) { error = "mesh " + m.id + ": a part past its triangles"; return false; }
            if (part.texture >= int(out.textures.size())) part.texture = -1;
            m.parts.push_back(part);
        }
        if (m.kind == "body" && out.body < 0) out.body = int(out.meshes.size());
        out.meshes.push_back(std::move(m));
    }
    if (out.body < 0) { error = "no body mesh"; return false; }
    for (size_t i = 0; i < j["always"].size(); ++i) { int k = out.mesh(j["always"][i].str()); if (k >= 0) out.always.push_back(k); }
    for (size_t i = 0; i < j["eyeTextures"].size(); ++i) {
        const Json& e = j["eyeTextures"][i];
        out.eyeTextures.push_back({e["id"].str(), e["label"].str(), int(e["texture"].num(-1))});
    }
    auto choices = [&](const Json& a, std::vector<HdChoice>& dst) -> bool {
        for (size_t i = 0; i < a.size(); ++i) {
            const Json& c = a[i];
            HdChoice ch;
            ch.id = c["id"].str(); ch.label = c["label"].str();
            for (size_t k = 0; k < c["meshes"].size(); ++k) { int mi = out.mesh(c["meshes"][k].str()); if (mi >= 0) ch.meshes.push_back(mi); }
            ch.classes = intList(c["classes"]);
            int hb = int(c["hideBlob"].num(-1));
            if (blobOk(hb)) {
                size_t n = size_t(out.blobs[size_t(hb)].second / 4);
                ch.hidden.resize(n);
                std::memcpy(ch.hidden.data(), d + out.blobs[size_t(hb)].first, n * 4);
                const size_t bodyTris = out.meshes[size_t(out.body)].idx.size() / 3;
                for (uint32_t t : ch.hidden) if (t >= bodyTris) { error = "outfit " + ch.id + " hides a triangle the body hasn't"; return false; }
            }
            dst.push_back(std::move(ch));
        }
        return true;
    };
    if (!choices(j["hair"], out.hair) || !choices(j["outfits"], out.outfits) || !choices(j["jewelry"], out.jewelry)) return false;
    for (size_t i = 0; i < j["overlays"].size(); ++i) {
        const Json& o = j["overlays"][i];
        out.overlays.push_back({o["id"].str(), o["label"].str(), o["slot"].str(), int(o["onto"].num(-1)), int(o["blob"].num(-1))});
    }
    auto tints = [&](const Json& a, std::vector<HdTint>& dst) {
        for (size_t i = 0; i < a.size(); ++i) dst.push_back({a[i]["id"].str(), a[i]["label"].str(), hexRgb(a[i]["rgb"].str("FFFFFF")), float(a[i]["lift"].num(0))});
    };
    tints(j["tints"], out.tints);
    tints(j["hairTints"], out.hairTints);
    return true;
}

namespace {
std::string jsonStr(const std::string& s) {
    std::string o = "\"";
    for (char c : s) { if (c == '"' || c == '\\') o += '\\'; o += c; }
    return o + "\"";
}
void put32(std::vector<uint8_t>& b, uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back(uint8_t(v >> (8 * i))); }
void put64(std::vector<uint8_t>& b, uint64_t v) { for (int i = 0; i < 8; ++i) b.push_back(uint8_t(v >> (8 * i))); }
} // namespace

std::vector<uint8_t> writeWhd(const HdSet& s) {
    std::vector<std::vector<uint8_t>> blobs;
    auto add = [&](const uint8_t* p, size_t n) { blobs.emplace_back(p, p + n); return int(blobs.size() - 1); };
    std::ostringstream js;
    js.imbue(std::locale::classic());
    js << "{\"format\":\"whd\",\"version\":" << kWhdVersion << ",\"id\":" << jsonStr(s.id) << ",\"label\":" << jsonStr(s.label)
       << ",\"race\":" << s.race << ",\"sex\":" << s.sex << ",\"gpuBytes\":" << s.gpuBytes << ",\"bones\":[";
    for (size_t i = 0; i < s.bones.size(); ++i) js << (i ? "," : "") << jsonStr(s.bones[i]);
    js << "],\"joints\":{";
    for (size_t i = 0; i < s.joints.size(); ++i)
        js << (i ? "," : "") << jsonStr(s.joints[i].first) << ":[" << s.joints[i].second.x << "," << s.joints[i].second.y << "," << s.joints[i].second.z << "]";
    js << "},\"textures\":[";
    for (size_t i = 0; i < s.textures.size(); ++i) {
        size_t n = 0; const uint8_t* p = s.blob(s.textures[i].blob, n);
        int b = add(p, n);
        js << (i ? "," : "") << "{\"id\":" << jsonStr(s.textures[i].id) << ",\"w\":" << s.textures[i].w << ",\"h\":" << s.textures[i].h
           << ",\"alpha\":" << (s.textures[i].alpha ? "true" : "false") << ",\"blob\":" << b << "}";
    }
    js << "],\"meshes\":[";
    for (size_t i = 0; i < s.meshes.size(); ++i) {
        const HdMesh& m = s.meshes[i];
        std::vector<uint8_t> vb(m.vertexCount() * kWhdVertexBytes);
        for (size_t k = 0; k < m.vertexCount(); ++k) {
            uint8_t* v = vb.data() + k * kWhdVertexBytes;
            std::memcpy(v, &m.pos[k * 3], 12); std::memcpy(v + 12, &m.nrm[k * 3], 12); std::memcpy(v + 24, &m.uv[k * 2], 8);
            std::memcpy(v + 32, &m.bi[k * 4], 4); std::memcpy(v + 36, &m.bw[k * 4], 4);
        }
        int vbi = add(vb.data(), vb.size());
        int ibi = add(reinterpret_cast<const uint8_t*>(m.idx.data()), m.idx.size() * 4);
        js << (i ? "," : "") << "{\"id\":" << jsonStr(m.id) << ",\"kind\":" << jsonStr(m.kind) << ",\"vertices\":" << m.vertexCount()
           << ",\"triangles\":" << m.idx.size() / 3 << ",\"vertexBlob\":" << vbi << ",\"indexBlob\":" << ibi << ",\"parts\":[";
        for (size_t p = 0; p < m.parts.size(); ++p)
            js << (p ? "," : "") << "{\"start\":" << m.parts[p].start << ",\"count\":" << m.parts[p].count << ",\"texture\":" << m.parts[p].texture
               << ",\"alpha\":\"" << (m.parts[p].alphaTest ? "test" : "opaque") << "\"}";
        js << "]}";
    }
    js << "],\"always\":[";
    for (size_t i = 0; i < s.always.size(); ++i) js << (i ? "," : "") << jsonStr(s.meshes[size_t(s.always[i])].id);
    js << "],\"eyeTextures\":[";
    for (size_t i = 0; i < s.eyeTextures.size(); ++i)
        js << (i ? "," : "") << "{\"id\":" << jsonStr(s.eyeTextures[i].id) << ",\"label\":" << jsonStr(s.eyeTextures[i].label) << ",\"texture\":" << s.eyeTextures[i].texture << "}";
    js << "]";
    auto choices = [&](const char* name, const std::vector<HdChoice>& cs) {
        js << ",\"" << name << "\":[";
        for (size_t i = 0; i < cs.size(); ++i) {
            const HdChoice& c = cs[i];
            js << (i ? "," : "") << "{\"id\":" << jsonStr(c.id) << ",\"label\":" << jsonStr(c.label) << ",\"meshes\":[";
            for (size_t k = 0; k < c.meshes.size(); ++k) js << (k ? "," : "") << jsonStr(s.meshes[size_t(c.meshes[k])].id);
            js << "],\"classes\":[";
            for (size_t k = 0; k < c.classes.size(); ++k) js << (k ? "," : "") << c.classes[k];
            js << "]";
            if (!c.hidden.empty()) js << ",\"hideBlob\":" << add(reinterpret_cast<const uint8_t*>(c.hidden.data()), c.hidden.size() * 4);
            js << "}";
        }
        js << "]";
    };
    choices("hair", s.hair); choices("outfits", s.outfits); choices("jewelry", s.jewelry);
    js << ",\"overlays\":[";
    for (size_t i = 0; i < s.overlays.size(); ++i) {
        size_t n = 0; const uint8_t* p = s.blob(s.overlays[i].blob, n);
        int b = add(p, n);
        js << (i ? "," : "") << "{\"id\":" << jsonStr(s.overlays[i].id) << ",\"label\":" << jsonStr(s.overlays[i].label) << ",\"slot\":"
           << jsonStr(s.overlays[i].slot) << ",\"onto\":" << s.overlays[i].onto << ",\"blob\":" << b << "}";
    }
    js << "]";
    auto tints = [&](const char* name, const std::vector<HdTint>& ts) {
        js << ",\"" << name << "\":[";
        char hex[8];
        for (size_t i = 0; i < ts.size(); ++i) {
            std::snprintf(hex, sizeof hex, "%06X", ts[i].rgb & 0xFFFFFF);
            js << (i ? "," : "") << "{\"id\":" << jsonStr(ts[i].id) << ",\"label\":" << jsonStr(ts[i].label) << ",\"rgb\":\"" << hex << "\",\"lift\":" << ts[i].lift << "}";
        }
        js << "]";
    };
    tints("tints", s.tints); tints("hairTints", s.hairTints);
    js << "}";
    const std::string j = js.str();
    std::vector<uint8_t> out;
    out.insert(out.end(), {'W', 'H', 'D', '1'});
    put32(out, kWhdVersion); put32(out, uint32_t(j.size())); put32(out, uint32_t(blobs.size()));
    out.insert(out.end(), j.begin(), j.end());
    while (out.size() % 16) out.push_back(0);
    size_t table = out.size();
    size_t off = (table + 16 * blobs.size() + 15) & ~size_t(15);
    std::vector<uint64_t> offs;
    for (const auto& b : blobs) { offs.push_back(off); off = (off + b.size() + 15) & ~size_t(15); }
    for (size_t i = 0; i < blobs.size(); ++i) { put64(out, offs[i]); put64(out, blobs[i].size()); }
    for (size_t i = 0; i < blobs.size(); ++i) {
        out.resize(offs[i], 0);
        out.insert(out.end(), blobs[i].begin(), blobs[i].end());
    }
    return out;
}

uint64_t HdOptions::key() const {
    uint64_t k = 1469598103934665603ull;
    auto mix = [&k](uint64_t v) { k = (k ^ v) * 1099511628211ull; };
    for (int v : {int(on), hair, outfit, eyes, faceMark, bodyMarks, tint, hairTint}) mix(uint64_t(uint32_t(v)));
    for (int v : jewelry) mix(uint64_t(uint32_t(v)) + 77);
    return k;
}

void hdTransferWeights(const M2Model& m, const std::vector<uint16_t>& geosets, const std::vector<V3>& points,
                       std::vector<std::array<uint8_t, 4>>& bones, std::vector<std::array<uint8_t, 4>>& weights) {
    bones.assign(points.size(), {0, 0, 0, 0});
    weights.assign(points.size(), {255, 0, 0, 0});
    // the skin: the shown submeshes' vertices, but no hairstyle (group 0's other ids)
    std::vector<uint8_t> use(m.vertices.size(), 0);
    for (const M2Submesh& sm : m.submeshes) {
        if (sm.id != 0 && sm.id < 100) continue;
        if (!geosets.empty() && std::find(geosets.begin(), geosets.end(), sm.id) == geosets.end()) continue;
        for (uint32_t i = sm.indexStart; i < sm.indexStart + sm.indexCount && i < m.indices.size(); ++i)
            if (m.indices[i] < use.size()) use[m.indices[i]] = 1;
    }
    constexpr float kCell = 0.03f;
    auto cell = [](float v) { return int(std::floor(v / kCell)); };
    auto key = [](int x, int y, int z) { return (uint64_t(uint32_t(x) & 0x1FFFFF) << 42) | (uint64_t(uint32_t(y) & 0x1FFFFF) << 21) | (uint32_t(z) & 0x1FFFFF); };
    std::unordered_map<uint64_t, std::vector<uint32_t>> grid;
    for (size_t i = 0; i < m.vertices.size(); ++i)
        if (use[i]) grid[key(cell(m.vertices[i].pos[0]), cell(m.vertices[i].pos[1]), cell(m.vertices[i].pos[2]))].push_back(uint32_t(i));
    if (grid.empty()) return;
    for (size_t p = 0; p < points.size(); ++p) {
        const V3 q = points[p];
        int cx = cell(q.x), cy = cell(q.y), cz = cell(q.z);
        float best = 1e30f; int bi = -1;
        for (int r = 1; r <= 24 && bi < 0; r *= 2)
            for (int x = cx - r; x <= cx + r; ++x) for (int y = cy - r; y <= cy + r; ++y) for (int z = cz - r; z <= cz + r; ++z) {
                auto it = grid.find(key(x, y, z));
                if (it == grid.end()) continue;
                for (uint32_t i : it->second) {
                    const float* v = m.vertices[i].pos;
                    float d = (v[0] - q.x) * (v[0] - q.x) + (v[1] - q.y) * (v[1] - q.y) + (v[2] - q.z) * (v[2] - q.z);
                    if (d < best) { best = d; bi = int(i); }
                }
            }
        if (bi < 0) continue;
        const M2Vertex& v = m.vertices[size_t(bi)];
        for (int k = 0; k < 4; ++k) {
            bones[p][size_t(k)] = v.bones[k];
            weights[p][size_t(k)] = v.weights[k];
        }
    }
}

HdFit fitHd(const M2Model& m, const HdSet& set, const HdOptions& o, std::string& error) {
    auto t0 = std::chrono::steady_clock::now();
    HdFit fit;
    const size_t nb = m.bones.size();
    if (nb == 0 || set.body < 0) { error = "no skeleton"; return fit; }
    auto P = [&](const char* n, V3& out) { int b = m.findWowBone(n); if (b < 0) return false; out = m.bones[size_t(b)].pivot; return true; };
    V3 mNeck, mPelvis;
    const V3 *hNeck = set.joint("Neck1"), *hPelvis = set.joint("Pelvis");
    if (!P("Neck1", mNeck) || !P("Pelvis", mPelvis) || !hNeck || !hPelvis || hNeck->z < 0.1f) { error = "the skeleton has no neck or pelvis"; return fit; }
    const float s = mNeck.z / hNeck->z;
    const V3 t(mPelvis.x - hPelvis->x * s, mPelvis.y - hPelvis->y * s, 0);
    auto S = [&](V3 p) { return p * s + t; };
    fit.stats.scale = s;
    // the HD joints per model bone (scaled)
    std::vector<int8_t> has(nb, 0);
    std::vector<V3> J(nb);
    for (const auto& [name, p] : set.joints) {
        int b = m.findWowBone(name);
        if (b < 0 || size_t(b) >= nb) continue;
        J[size_t(b)] = S(p); has[size_t(b)] = 1;
    }
    // each bone's transform turning the HD limbs to the model's rest directions (parents first)
    std::vector<M4> G(nb);
    std::vector<uint8_t> done(nb, 0);
    struct Align { int bone, child, a = -1, b = -1; };   // a/b: the second direction (index -> pinky), for the hand's roll
    std::vector<Align> aligns;
    for (const char* side : {"L_", "R_"}) {
        std::string p = side;
        auto B = [&](const char* n) { return m.findWowBone(p + n); };
        aligns.push_back({B("UpperArm"), B("Forearm")});
        aligns.push_back({B("Forearm"), B("Hand")});
        aligns.push_back({B("Hand"), B("Middle1"), B("Index1"), B("Pinky1")});
        for (const char* f : {"Thumb", "Index", "Middle", "Ring", "Pinky"}) aligns.push_back({B((std::string(f) + "1").c_str()), B((std::string(f) + "2").c_str())});
        aligns.push_back({B("Thigh"), B("Calf")});
        aligns.push_back({B("Calf"), B("Foot")});
        aligns.push_back({B("Foot"), B("Toe")});
    }
    std::vector<int> alignOf(nb, -1);
    for (size_t i = 0; i < aligns.size(); ++i) {
        const Align& a = aligns[i];
        if (a.bone >= 0 && a.child >= 0 && has[size_t(a.bone)] && has[size_t(a.child)]) alignOf[size_t(a.bone)] = int(i);
    }
    float worstDeg = 0;
    for (int pass = 0; pass < 64; ++pass) {
        bool all = true;
        for (size_t b = 0; b < nb; ++b) {
            if (done[b]) continue;
            int par = m.bones[b].parent;
            if (par >= 0 && size_t(par) < nb && !done[size_t(par)]) { all = false; continue; }
            G[b] = par >= 0 && size_t(par) < nb ? G[size_t(par)] : M4();
            if (alignOf[b] >= 0) {
                const Align& a = aligns[size_t(alignOf[b])];
                V3 c = G[b].point(J[b]);
                V3 dh = G[b].point(J[size_t(a.child)]) - c;
                V3 dm = m.bones[size_t(a.child)].pivot - m.bones[b].pivot;
                if (dh.length() > 1e-4f && dm.length() > 1e-4f) {
                    M4 R = rotateOnto(dh, dm);
                    // the hand's roll: its index-to-pinky line turned about the hand's direction to the model's
                    if (a.a >= 0 && a.b >= 0 && has[size_t(a.a)] && has[size_t(a.b)]) {
                        V3 ax = dm.normalized();
                        V3 sh = dirOf(R, G[b].point(J[size_t(a.b)]) - G[b].point(J[size_t(a.a)]));
                        V3 sm = m.bones[size_t(a.b)].pivot - m.bones[size_t(a.a)].pivot;
                        sh = (sh - ax * sh.dot(ax)); sm = (sm - ax * sm.dot(ax));
                        if (sh.length() > 1e-4f && sm.length() > 1e-4f) R = rotateOnto(sh, sm) * R;
                    }
                    G[b] = about(c, R) * G[b];
                    V3 after = G[b].point(J[size_t(a.child)]) - G[b].point(J[b]);
                    float cosv = std::clamp(after.normalized().dot(dm.normalized()), -1.0f, 1.0f);
                    worstDeg = std::max(worstDeg, std::acos(cosv) * 57.2958f);
                }
            }
            done[b] = 1;
        }
        if (all) break;
    }
    fit.stats.jointError = worstDeg;
    // the rig's pivots: the HD joints as aligned, the rest carried by their nearest placed ancestor
    fit.rigPivots.resize(nb);
    std::vector<int8_t> placed(nb, 0);
    for (size_t b = 0; b < nb; ++b) {
        fit.rigPivots[b] = m.bones[b].pivot;
        if (has[b]) { fit.rigPivots[b] = G[b].point(J[b]); placed[b] = 1; }
    }
    for (size_t b = 0; b < nb; ++b) {
        if (placed[b]) continue;
        for (int c = m.bones[b].parent, g = 0; c >= 0 && size_t(c) < nb && g < 256; c = m.bones[size_t(c)].parent, ++g) {
            if (!placed[size_t(c)]) continue;
            fit.rigPivots[b] = m.bones[b].pivot + (fit.rigPivots[size_t(c)] - m.bones[size_t(c)].pivot);
            break;
        }
    }
    // the set's bones on the model
    std::vector<int> boneOf(set.bones.size(), -1);
    for (size_t i = 0; i < set.bones.size(); ++i) {
        int b = m.findWowBone(set.bones[i]);
        boneOf[i] = b >= 0 && b < 256 ? b : -1;
    }
    // what is drawn: the body (less the outfit's covered triangles), the eyes and brows, the hair, the outfit, the jewellery
    const HdChoice* outfit = o.outfit >= 0 && size_t(o.outfit) < set.outfits.size() ? &set.outfits[size_t(o.outfit)] : nullptr;
    const int hairIdx = o.hair == -2 ? -1 : o.hair < 0 ? (set.hair.empty() ? -1 : 0) : o.hair;
    const HdChoice* hair = hairIdx >= 0 && size_t(hairIdx) < set.hair.size() ? &set.hair[size_t(hairIdx)] : nullptr;
    const int eyeTex = set.eyeTextures.empty() ? -1 : set.eyeTextures[size_t(std::clamp(o.eyes < 0 ? 0 : o.eyes, 0, int(set.eyeTextures.size()) - 1))].texture;
    struct Use { int mesh; uint8_t role; };
    std::vector<Use> uses{{set.body, 0}};
    for (int k : set.always) uses.push_back({k, uint8_t(set.meshes[size_t(k)].kind == "brows" ? 1 : 2)});   // (the brows take the hair's tint)
    if (hair) for (int k : hair->meshes) uses.push_back({k, 1});
    if (outfit) for (int k : outfit->meshes) uses.push_back({k, 2});
    for (int jv : o.jewelry) if (jv >= 0 && size_t(jv) < set.jewelry.size()) for (int k : set.jewelry[size_t(jv)].meshes) uses.push_back({k, 2});
    std::vector<uint8_t> hiddenTri;
    if (outfit && !outfit->hidden.empty()) {
        hiddenTri.assign(set.meshes[size_t(set.body)].idx.size() / 3, 0);
        for (uint32_t tt : outfit->hidden) hiddenTri[tt] = 1;
    }
    std::vector<V3> unmappedPts;
    std::vector<size_t> unmappedAt;
    for (const Use& u : uses) {
        const HdMesh& hm = set.meshes[size_t(u.mesh)];
        const uint32_t base = uint32_t(fit.verts.size());
        for (size_t k = 0; k < hm.vertexCount(); ++k) {
            M2Vertex v{};
            V3 p = S(V3(hm.pos[k * 3], hm.pos[k * 3 + 1], hm.pos[k * 3 + 2]));
            V3 n(hm.nrm[k * 3], hm.nrm[k * 3 + 1], hm.nrm[k * 3 + 2]);
            V3 pa, na;
            int wsum = 0;
            bool lost = false;
            for (int q = 0; q < 4; ++q) {
                int w = hm.bw[k * 4 + size_t(q)];
                if (!w) continue;
                int b = boneOf[hm.bi[k * 4 + size_t(q)]];
                if (b < 0) { lost = true; continue; }
                pa = pa + G[size_t(b)].point(p) * float(w);
                na = na + dirOf(G[size_t(b)], n) * float(w);
                v.bones[q] = uint8_t(b);
                v.weights[q] = uint8_t(w);
                wsum += w;
            }
            if (wsum > 0) { p = pa * (1.0f / float(wsum)); n = na.normalized(); }
            if (lost || wsum == 0) { unmappedPts.push_back(p); unmappedAt.push_back(fit.verts.size()); }
            else if (wsum != 255) {
                // the weights the model has, normalised to 255
                int acc = 0, top = 0;
                for (int q = 0; q < 4; ++q) { v.weights[q] = uint8_t(std::lround(v.weights[q] * 255.0 / wsum)); acc += v.weights[q]; if (v.weights[q] > v.weights[top]) top = q; }
                v.weights[top] = uint8_t(v.weights[top] + (255 - acc));
            }
            v.pos[0] = p.x; v.pos[1] = p.y; v.pos[2] = p.z;
            v.normal[0] = n.x; v.normal[1] = n.y; v.normal[2] = n.z;
            v.uv[0] = hm.uv[k * 2]; v.uv[1] = hm.uv[k * 2 + 1];
            fit.verts.push_back(v);
        }
        for (const HdPart& part : hm.parts) {
            HdDrawPart dp;
            dp.start = uint32_t(fit.indices.size());
            dp.texture = hm.kind == "eyes" && eyeTex >= 0 ? eyeTex : part.texture;
            dp.alphaTest = part.alphaTest;
            dp.role = u.role;
            for (uint32_t i = part.start; i + 2 < part.start + part.count && i + 2 < hm.idx.size(); i += 3) {
                if (u.mesh == set.body && !hiddenTri.empty() && hiddenTri[i / 3]) continue;
                for (int c = 0; c < 3; ++c) fit.indices.push_back(base + hm.idx[i + size_t(c)]);
            }
            dp.count = uint32_t(fit.indices.size()) - dp.start;
            if (dp.count) fit.parts.push_back(dp);
        }
        if (u.mesh == set.body) {
            fit.bodyVertexEnd = uint32_t(fit.verts.size());
            fit.bodyIndices.assign(fit.indices.begin(), fit.indices.end());
        }
    }
    if (!unmappedPts.empty()) {
        // (the model's bind space: the HD figure's places are near enough the model's skin for its nearest vertex)
        std::vector<std::array<uint8_t, 4>> bi, bw;
        hdTransferWeights(m, {}, unmappedPts, bi, bw);
        for (size_t i = 0; i < unmappedAt.size(); ++i)
            for (int q = 0; q < 4; ++q) { fit.verts[unmappedAt[i]].bones[q] = bi[i][size_t(q)]; fit.verts[unmappedAt[i]].weights[q] = bw[i][size_t(q)]; }
    }
    fit.stats.unmapped = unmappedPts.size();
    fit.stats.vertices = fit.verts.size();
    fit.stats.triangles = fit.indices.size() / 3;
    fit.stats.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return fit;
}

size_t hdBreastRegions(const M2Model& m, const std::vector<M2Vertex>& verts, uint32_t end, std::vector<SoftRegion>& regions) {
    int bL = m.findWowBone("L_Breast"), bR = m.findWowBone("R_Breast");
    size_t changed = 0;
    for (SoftRegion& r : regions) {
        if (r.kind != SoftKind::Chest) continue;
        int b = r.centre.y >= 0 ? bL : bR;
        if (b < 0) continue;
        V3 lo(1e9f, 1e9f, 1e9f), hi(-1e9f, -1e9f, -1e9f), sum;
        float wsum = 0;
        for (uint32_t i = 0; i < end && i < verts.size(); ++i) {
            const M2Vertex& v = verts[i];
            float w = 0;
            for (int k = 0; k < 4; ++k) if (v.bones[k] == b) w += v.weights[k] / 255.0f;
            if (w < 0.3f) continue;
            V3 p(v.pos[0], v.pos[1], v.pos[2]);
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

bool hdTexture(const HdSet& set, int texture, int overlay, const HdTint* hairTint, std::vector<uint8_t>& rgba, int& w, int& h,
               std::string& error) {
    if (texture < 0 || size_t(texture) >= set.textures.size()) { error = "no such texture"; return false; }
    size_t n = 0;
    const uint8_t* p = set.blob(set.textures[size_t(texture)].blob, n);
    if (!p || !decodePng(p, n, rgba, w, h, error)) { if (error.empty()) error = "texture missing"; return false; }
    if (overlay >= 0 && size_t(overlay) < set.overlays.size() && set.overlays[size_t(overlay)].onto == texture) {
        std::vector<uint8_t> ov; int ow = 0, oh = 0;
        size_t on = 0;
        const uint8_t* op = set.blob(set.overlays[size_t(overlay)].blob, on);
        std::string oerr;
        if (op && decodePng(op, on, ov, ow, oh, oerr) && ow > 0 && oh > 0) {
            for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
                const uint8_t* s = &ov[(size_t(y * oh / h) * size_t(ow) + size_t(x * ow / w)) * 4];
                uint8_t* d = &rgba[(size_t(y) * size_t(w) + size_t(x)) * 4];
                const int a = s[3];
                for (int c = 0; c < 3; ++c) d[c] = uint8_t((s[c] * a + d[c] * (255 - a) + 127) / 255);
            }
        }
    }
    if (hairTint) {
        // the hair recoloured: its lightness (lifted toward white by `lift`) times the tint, its strands' contrast kept
        const float tr = float((hairTint->rgb >> 16) & 255) / 255.0f, tg = float((hairTint->rgb >> 8) & 255) / 255.0f, tb = float(hairTint->rgb & 255) / 255.0f;
        double mean = 0;
        const size_t px = size_t(w) * size_t(h);
        for (size_t i = 0; i < px; ++i) mean += 0.3 * rgba[i * 4] + 0.59 * rgba[i * 4 + 1] + 0.11 * rgba[i * 4 + 2];
        mean = std::max(1.0, mean / double(std::max<size_t>(px, 1)));
        const float gain = float(150.0 / mean);
        for (size_t i = 0; i < px; ++i) {
            uint8_t* d = &rgba[i * 4];
            float l = (0.3f * d[0] + 0.59f * d[1] + 0.11f * d[2]) / 255.0f * gain;
            l = std::pow(std::clamp(l, 0.0f, 1.5f), 1.0f - 0.6f * hairTint->lift);
            d[0] = uint8_t(std::clamp(l * tr * 255.0f, 0.0f, 255.0f));
            d[1] = uint8_t(std::clamp(l * tg * 255.0f, 0.0f, 255.0f));
            d[2] = uint8_t(std::clamp(l * tb * 255.0f, 0.0f, 255.0f));
        }
    }
    return true;
}

} // namespace wc
