#include "wmo.h"

namespace wc {

bool WmoRoot::load(const Bytes& file) {
    Reader r(file);
    std::vector<uint32_t> gfid, modi;
    const char* names = nullptr;
    size_t namesLen = 0;
    for (const Chunk& c : chunks(r, 0, file.size())) {
        Reader cr(file.data() + c.offset, c.size);
        if (c.tag == rtag("MOHD")) {
            ambient = cr.u32(28);
        } else if (c.tag == rtag("MOMT")) {
            for (uint32_t o = 0; o + 64 <= c.size; o += 64) {
                WmoMaterial m;
                m.flags = cr.u32(o); m.shader = cr.u32(o + 4); m.blend = cr.u32(o + 8);
                m.texture1 = cr.u32(o + 12); m.texture2 = cr.u32(o + 24); m.texture3 = cr.u32(o + 36);
                materials.push_back(m);
            }
        } else if (c.tag == rtag("MOGN")) {
            names = reinterpret_cast<const char*>(file.data() + c.offset); namesLen = c.size;
        } else if (c.tag == rtag("MOGI")) {
            for (uint32_t o = 0; o + 32 <= c.size; o += 32) {
                WmoGroupInfo g;
                g.flags = cr.u32(o);
                g.boundsMin = V3(cr.f32(o + 4), cr.f32(o + 8), cr.f32(o + 12));
                g.boundsMax = V3(cr.f32(o + 16), cr.f32(o + 20), cr.f32(o + 24));
                int32_t n = cr.i32(o + 28);
                if (names && n >= 0 && size_t(n) < namesLen)
                    g.name = std::string(names + n, strnlen(names + n, namesLen - size_t(n)));
                groups.push_back(g);
            }
        } else if (c.tag == rtag("MODS")) {
            for (uint32_t o = 0; o + 32 <= c.size; o += 32) {
                Set s;
                s.name = std::string(reinterpret_cast<const char*>(file.data() + c.offset + o), strnlen(reinterpret_cast<const char*>(file.data() + c.offset + o), 20));
                s.first = cr.u32(o + 20); s.count = cr.u32(o + 24);
                sets.push_back(s);
            }
        } else if (c.tag == rtag("MODI")) {
            for (uint32_t o = 0; o + 4 <= c.size; o += 4) modi.push_back(cr.u32(o));
        } else if (c.tag == rtag("MODD")) {
            for (uint32_t o = 0; o + 40 <= c.size; o += 40) {
                WmoDoodad d;
                uint32_t ref = cr.u32(o) & 0xFFFFFF;
                d.modelFileId = ref;   // an index into MODI, resolved below
                d.position = V3(cr.f32(o + 4), cr.f32(o + 8), cr.f32(o + 12));
                d.rotation = Quat(cr.f32(o + 16), cr.f32(o + 20), cr.f32(o + 24), cr.f32(o + 28));
                d.scale = cr.f32(o + 32);
                d.color = cr.u32(o + 36);
                doodads.push_back(d);
            }
        } else if (c.tag == rtag("GFID")) {
            for (uint32_t o = 0; o + 4 <= c.size; o += 4) gfid.push_back(cr.u32(o));
        }
    }
    for (auto& d : doodads) d.modelFileId = d.modelFileId < modi.size() ? modi[d.modelFileId] : 0;
    for (size_t i = 0; i < groups.size() && i < gfid.size(); ++i) groups[i].fileId = gfid[i];
    return !groups.empty();
}

bool WmoGroup::load(const Bytes& file) {
    Reader r(file);
    for (const Chunk& top : chunks(r, 0, file.size())) {
        if (top.tag != rtag("MOGP")) continue;
        Reader hr(file.data() + top.offset, top.size);
        flags = hr.u32(8);
        bool firstUv = true, firstColor = true;
        for (const Chunk& c : chunks(r, top.offset + 0x44, top.offset + top.size)) {
            Reader cr(file.data() + c.offset, c.size);
            if (c.tag == rtag("MOVT")) {
                positions.resize(c.size / 4);
                std::memcpy(positions.data(), file.data() + c.offset, positions.size() * 4);
            } else if (c.tag == rtag("MONR")) {
                normals.resize(c.size / 4);
                std::memcpy(normals.data(), file.data() + c.offset, normals.size() * 4);
            } else if (c.tag == rtag("MOTV") && firstUv) {
                uvs.resize(c.size / 4);
                std::memcpy(uvs.data(), file.data() + c.offset, uvs.size() * 4);
                firstUv = false;
            } else if (c.tag == rtag("MOCV") && firstColor) {
                colors.assign(file.data() + c.offset, file.data() + c.offset + c.size);
                firstColor = false;
            } else if (c.tag == rtag("MOVI")) {
                indices.resize(c.size / 2);
                std::memcpy(indices.data(), file.data() + c.offset, indices.size() * 2);
            } else if (c.tag == rtag("MOBA")) {
                for (uint32_t o = 0; o + 24 <= c.size; o += 24) {
                    WmoBatch b;
                    b.indexStart = cr.u32(o + 12);
                    b.indexCount = cr.u16(o + 16);
                    uint8_t f = cr.u8(o + 22);
                    b.material = (f & 2) ? cr.u16(o + 10) : cr.u8(o + 23);
                    batches.push_back(b);
                }
            }
        }
        size_t nv = positions.size() / 3;
        if (normals.size() != nv * 3) normals.assign(nv * 3, 0.0f);
        if (uvs.size() != nv * 2) uvs.assign(nv * 2, 0.0f);
        if (!colors.empty() && colors.size() != nv * 4) colors.clear();
        for (auto& b : batches)
            if (b.indexStart + b.indexCount > indices.size()) b.indexCount = 0;
        for (auto& i : indices) if (i >= nv) i = 0;
        return nv > 0;
    }
    return false;
}

} // namespace wc
