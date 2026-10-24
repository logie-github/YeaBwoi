// SPDX-License-Identifier: GPL-3.0-or-later
#include "SkyborneModel.h"
#include <algorithm>
#include <array>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <numeric>
#include <sstream>
#include <zlib.h>

namespace Skyborne
{
    namespace
    {
        uint32_t Get32(uint8_t const* p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }
        uint16_t Get16(uint8_t const* p) { return uint16_t(p[0] | p[1] << 8); }
        void Put32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; ++i) p[i] = uint8_t(v >> (8 * i)); }
        void Put16(uint8_t* p, uint16_t v) { p[0] = uint8_t(v); p[1] = uint8_t(v >> 8); }
        float GetF(uint8_t const* p) { float f; std::memcpy(&f, p, 4); return f; }
        void PutF(uint8_t* p, float f) { std::memcpy(p, &f, 4); }

        // Models ---------------------------------------------------------------------------------
        // The 3.3.5 format (264), as mod-allied-races/tools/m2.py writes it: where the header keeps
        // its arrays (a count and an offset each) and the sizes of their entries.

        constexpr size_t M2_HEADER = 304, M2_VERTICES = 60, M2_TEXTURES = 80, M2_MATERIALS = 112,
            M2_TEXTURE_LOOKUP = 128, M2_UNIT_LOOKUP = 136;
        constexpr size_t VERTEX = 48, SECTION = 48, BATCH = 24, SKIN_HEADER = 48;
        // A vertex's position, normal and two pairs of texture coordinates; a section's geoset, the
        // high word of its first index (later formats'), its vertices and indices (first, count); a
        // batch's section, material, texture count, texture (its lookup entry) and coordinates.
        constexpr size_t V_POSITION = 0, V_NORMAL = 20, V_UV = 32, V_UV2 = 40;
        constexpr size_t S_GEOSET = 0, S_LEVEL = 2, S_VSTART = 4, S_VCOUNT = 6, S_ISTART = 8, S_ICOUNT = 10;
        constexpr size_t B_SECTION = 4, B_MATERIAL = 10, B_TEXTURES = 14, B_TEXTURE = 16, B_UNIT = 18;
        constexpr uint32_t EXTRA_TEXTURE = 8, BLEND_ALPHA = 2;
        constexpr uint16_t DETAIL_GEOSET = 1101;
        constexpr uint32_t MAX_16 = 65535;

        bool IsModel(Bytes const& m2)
        {
            return m2.size() >= M2_HEADER && std::memcmp(m2.data(), "MD20", 4) == 0 && Get32(&m2[4]) == 264;
        }

        // The header's array at [at], of entries of [size] bytes: false when it isn't in the file.
        bool ArrayAt(Bytes const& m2, size_t at, size_t size, uint32_t& count, uint32_t& offset)
        {
            count = Get32(&m2[at]);
            offset = Get32(&m2[at + 4]);
            return count == 0 || uint64_t(offset) + uint64_t(count) * size <= m2.size();
        }

        // A skin file: the model's vertices it uses (the lookup), its triangles (three of those each),
        // each vertex's bones (slots of its section's), its sections, and what draws them (batches).
        struct Skin
        {
            std::vector<uint16_t> lookup, indices;
            std::vector<std::array<uint8_t, 4>> bones;
            std::vector<std::array<uint8_t, SECTION>> sections;
            std::vector<std::array<uint8_t, BATCH>> batches;
            uint32_t boneMax = 0;

            bool Parse(Bytes const& data)
            {
                if (data.size() < SKIN_HEADER || std::memcmp(data.data(), "SKIN", 4) != 0)
                    return false;
                size_t const sizes[5] = { 2, 2, 4, SECTION, BATCH };
                uint32_t count[5], offset[5];
                for (int i = 0; i < 5; ++i)
                {
                    count[i] = Get32(&data[4 + i * 8]);
                    offset[i] = Get32(&data[8 + i * 8]);
                    if (count[i] && uint64_t(offset[i]) + uint64_t(count[i]) * sizes[i] > data.size())
                        return false;
                }
                lookup.resize(count[0]);
                for (uint32_t i = 0; i < count[0]; ++i)
                    lookup[i] = Get16(&data[offset[0] + i * 2]);
                indices.resize(count[1]);
                for (uint32_t i = 0; i < count[1]; ++i)
                    indices[i] = Get16(&data[offset[1] + i * 2]);
                bones.resize(count[2]);
                for (uint32_t i = 0; i < count[2]; ++i)
                    std::memcpy(bones[i].data(), &data[offset[2] + i * 4], 4);
                sections.resize(count[3]);
                for (uint32_t i = 0; i < count[3]; ++i)
                    std::memcpy(sections[i].data(), &data[offset[3] + i * SECTION], SECTION);
                batches.resize(count[4]);
                for (uint32_t i = 0; i < count[4]; ++i)
                    std::memcpy(batches[i].data(), &data[offset[4] + i * BATCH], BATCH);
                boneMax = Get32(&data[44]);
                return true;
            }

            // Written as the game's are: the header, then the arrays one after another.
            Bytes Write() const
            {
                Bytes out(SKIN_HEADER, 0);
                std::memcpy(out.data(), "SKIN", 4);
                size_t at = 4;
                auto place = [&](size_t count)
                {
                    Put32(&out[at], uint32_t(count));
                    Put32(&out[at + 4], uint32_t(out.size()));
                    at += 8;
                };
                auto words = [&](std::vector<uint16_t> const& values)
                {
                    place(values.size());
                    for (uint16_t v : values)
                    {
                        out.push_back(uint8_t(v));
                        out.push_back(uint8_t(v >> 8));
                    }
                };
                words(lookup);
                words(indices);
                place(bones.size());
                for (auto const& b : bones)
                    out.insert(out.end(), b.begin(), b.end());
                place(sections.size());
                for (auto const& s : sections)
                    out.insert(out.end(), s.begin(), s.end());
                place(batches.size());
                for (auto const& b : batches)
                    out.insert(out.end(), b.begin(), b.end());
                Put32(&out[44], boneMax);
                out.resize((out.size() + 15) / 16 * 16, 0);
                return out;
            }
        };

        // A mesh file's records (SkyborneModel.h).
        struct Mesh
        {
            struct Vertex { uint32_t lookup; float u, v; };
            struct Tri { uint32_t section, v[3]; };
            uint32_t crcVertices = 0, crcSkin = 0, vertexLines = 0, indexCount = 0;
            double lift = 0;
            std::vector<Vertex> vertices;
            std::vector<Tri> tris;
        };

        bool Number(std::istream& in, uint32_t& value)
        {
            long long v;
            if (!(in >> v) || v < 0 || v > UINT32_MAX)
                return false;
            value = uint32_t(v);
            return true;
        }

        bool Hex(std::istream& in, uint32_t& value)
        {
            std::string text;
            if (!(in >> text) || text[0] == '-' || text[0] == '+')
                return false;
            char* end = nullptr;
            unsigned long long v = std::strtoull(text.c_str(), &end, 16);
            if (*end || v > UINT32_MAX)
                return false;
            value = uint32_t(v);
            return true;
        }

        std::string ParseMesh(std::string const& text, Mesh& mesh)
        {
            std::istringstream in(text);
            std::string line;
            bool model = false, lift = false;
            double const top = 0.5 + DETAIL_GUTTER / 1024.0;
            for (int n = 1; std::getline(in, line); ++n)
            {
                line = line.substr(0, line.find('#'));
                std::istringstream fields(line);
                std::string key, rest;
                if (!(fields >> key))
                    continue;
                bool ok = false;
                if (key == "model" && !model)
                    ok = model = Hex(fields, mesh.crcVertices) && Hex(fields, mesh.crcSkin)
                        && Number(fields, mesh.vertexLines) && Number(fields, mesh.indexCount);
                else if (key == "lift" && !lift)
                    ok = lift = bool(fields >> mesh.lift) && std::isfinite(mesh.lift);
                else if (key == "vertex")
                {
                    Mesh::Vertex v;
                    double u = -1, tv = -1;
                    ok = Number(fields, v.lookup) && fields >> u >> tv && u >= 0 && u <= 1 && tv >= top && tv <= 1;
                    v.u = float(u);
                    v.v = float(tv);
                    mesh.vertices.push_back(v);
                }
                else if (key == "tri")
                {
                    Mesh::Tri t;
                    ok = Number(fields, t.section) && Number(fields, t.v[0]) && Number(fields, t.v[1]) && Number(fields, t.v[2]);
                    mesh.tris.push_back(t);
                }
                if (!ok || fields >> rest)
                    return "the mesh's line " + std::to_string(n) + " isn't one it can have";
            }
            if (!model || !lift)
                return "the mesh has no model or lift line";
            if (mesh.vertices.size() != mesh.vertexLines || mesh.tris.size() * 3 != mesh.indexCount)
                return "the mesh hasn't the vertices and triangles its model line says";
            for (auto const& t : mesh.tris)
                for (uint32_t k : t.v)
                    if (k >= mesh.vertices.size())
                        return "a triangle of the mesh names a vertex line it hasn't";
            return "";
        }
    }

    bool ModelPrint(Bytes const& m2, Bytes const& skin, uint32_t& vertices, uint32_t& skinCrc)
    {
        uint32_t count, offset;
        if (!IsModel(m2) || !ArrayAt(m2, M2_VERTICES, VERTEX, count, offset))
            return false;
        vertices = uint32_t(crc32(0, count ? &m2[offset] : nullptr, uInt(size_t(count) * VERTEX)));
        skinCrc = uint32_t(crc32(0, skin.data(), uInt(skin.size())));
        return true;
    }

    std::string AddBodyDetail(Bytes& m2, Bytes& skinFile, std::string const& text)
    {
        uint32_t crcVertices = 0, crcSkin = 0;
        if (!ModelPrint(m2, skinFile, crcVertices, crcSkin))
            return "not a model of the 3.3.5 format";
        Skin skin;
        if (!skin.Parse(skinFile))
            return "not a skin file";
        Mesh mesh;
        std::string error = ParseMesh(text, mesh);
        if (!error.empty())
            return error;
        if (mesh.crcVertices != crcVertices || mesh.crcSkin != crcSkin)
            return "the mesh was made for another model";

        uint32_t vertexCount, vertexAt, textureCount, textureAt, materialCount, materialAt, lookupCount, lookupAt, unitCount, unitAt;
        if (!ArrayAt(m2, M2_VERTICES, VERTEX, vertexCount, vertexAt) || !ArrayAt(m2, M2_TEXTURES, 16, textureCount, textureAt)
            || !ArrayAt(m2, M2_MATERIALS, 4, materialCount, materialAt) || !ArrayAt(m2, M2_TEXTURE_LOOKUP, 2, lookupCount, lookupAt)
            || !ArrayAt(m2, M2_UNIT_LOOKUP, 2, unitCount, unitAt))
            return "the model's arrays aren't in its file";
        if (skin.bones.size() != skin.lookup.size())
            return "the skin's vertices and their bones don't match";
        for (uint16_t v : skin.lookup)
            if (v >= vertexCount)
                return "the skin names a vertex the model hasn't";
        for (auto const& s : skin.sections)
        {
            if (Get16(&s[S_LEVEL]) != 0)
                return "a section of the skin starts past the 16 bits the game reads";
            if (Get16(&s[S_GEOSET]) == DETAIL_GEOSET)
                return "the model has geoset 1101 already";
            if (uint32_t(Get16(&s[S_VSTART])) + Get16(&s[S_VCOUNT]) > skin.lookup.size()
                || uint32_t(Get16(&s[S_ISTART])) + Get16(&s[S_ICOUNT]) > skin.indices.size())
                return "a section is outside the skin's arrays";
        }

        // The sections drawn with the extra texture and those drawn with others, and the face's batch
        // (the extra texture's on geoset 0) for the new sections to copy.
        std::vector<bool> extra(skin.sections.size(), false), other(skin.sections.size(), false);
        int face = -1;
        for (size_t i = 0; i < skin.batches.size(); ++i)
        {
            auto const& b = skin.batches[i];
            uint32_t section = Get16(&b[B_SECTION]), entry = Get16(&b[B_TEXTURE]);
            if (section >= skin.sections.size())
                return "a batch draws a section the skin hasn't";
            uint32_t texture = entry < lookupCount ? Get16(&m2[lookupAt + entry * 2]) : UINT32_MAX;
            if (texture >= textureCount || Get32(&m2[textureAt + texture * 16]) != EXTRA_TEXTURE)
            {
                other[section] = true;
                continue;
            }
            uint32_t unit = Get16(&b[B_UNIT]);
            if (Get16(&b[B_TEXTURES]) != 1 || unit >= unitCount || Get16(&m2[unitAt + unit * 2]) != 0)
                return "the extra texture isn't drawn alone with the first texture coordinates";
            extra[section] = true;
            auto geoset = [&](int batch) { return Get16(&skin.sections[Get16(&skin.batches[batch][B_SECTION])][S_GEOSET]); };
            if (face < 0 || (geoset(face) != 0 && geoset(int(i)) == 0))
                face = int(i);
        }
        if (face < 0)
            return "the model has no extra texture";
        int blended = -1;
        for (uint32_t i = 0; i < materialCount && blended < 0; ++i)
            if (Get16(&m2[materialAt + i * 4 + 2]) == BLEND_ALPHA)
                blended = int(i);
        if (blended < 0)
            return "the model has no blended material";

        // The extra texture's vertices, none of them another texture's too.
        std::vector<uint8_t> use(vertexCount, 0);                 // 1: the extra texture's; 2: another's
        for (size_t i = 0; i < skin.sections.size(); ++i)
        {
            auto const& s = skin.sections[i];
            for (uint32_t k = Get16(&s[S_VSTART]), end = k + Get16(&s[S_VCOUNT]); k < end; ++k)
                use[skin.lookup[k]] |= uint8_t((extra[i] ? 1 : 0) | (other[i] ? 2 : 0));
        }
        Bytes vertices(m2.begin() + vertexAt, m2.begin() + vertexAt + size_t(vertexCount) * VERTEX);
        for (uint32_t v = 0; v < vertexCount; ++v)
        {
            if (use[v] == 3)
                return "a vertex of the extra texture is another texture's too";
            if (use[v] != 1)
                continue;
            uint8_t* uv = &vertices[size_t(v) * VERTEX + V_UV];
            float tv = GetF(uv + 4);
            if (!(tv >= 0 && tv <= 1))
                return "the extra texture's coordinates aren't all within it";
            PutF(uv + 4, tv * 0.5f);
        }

        // The mesh's triangles, each of its section's vertices.
        for (auto const& v : mesh.vertices)
            if (v.lookup >= skin.lookup.size())
                return "the mesh names a vertex the skin hasn't";
        std::map<uint32_t, std::vector<size_t>> copied;           // section: its triangles in the mesh
        for (size_t i = 0; i < mesh.tris.size(); ++i)
        {
            auto const& t = mesh.tris[i];
            if (t.section >= skin.sections.size())
                return "the mesh names a section the skin hasn't";
            auto const& s = skin.sections[t.section];
            uint32_t first = Get16(&s[S_VSTART]), end = first + Get16(&s[S_VCOUNT]);
            for (uint32_t k : t.v)
                if (mesh.vertices[k].lookup < first || mesh.vertices[k].lookup >= end)
                    return "a triangle of the mesh has a vertex of another section";
            copied[t.section].push_back(i);
        }

        // A section for each section copied from, with its bones, centre and radius, its vertices
        // together at the end of the skin's (and their copies at the end of the model's), lifted off
        // the body and with their places in the texture's lower half.
        Skin out = skin;
        std::vector<uint16_t> added;                                // the new sections' indices
        std::vector<std::array<uint8_t, SECTION>> sections;
        for (auto const& [from, tris] : copied)
        {
            std::map<uint32_t, uint16_t> made;                      // vertex line: its vertex in the skin
            size_t vStart = out.lookup.size(), iStart = added.size();
            for (size_t t : tris)
                for (uint32_t k : mesh.tris[t].v)
                {
                    auto it = made.find(k);
                    if (it == made.end())
                    {
                        auto const& mv = mesh.vertices[k];
                        size_t at = vertices.size();
                        if (at / VERTEX > MAX_16 || out.lookup.size() > MAX_16)
                            return "the mesh has more vertices than the model can";
                        auto source = m2.begin() + vertexAt + size_t(skin.lookup[mv.lookup]) * VERTEX;
                        vertices.insert(vertices.end(), source, source + VERTEX);
                        uint8_t* p = &vertices[at];
                        float n[3] = { GetF(p + V_NORMAL), GetF(p + V_NORMAL + 4), GetF(p + V_NORMAL + 8) };
                        double length = std::sqrt(double(n[0]) * n[0] + double(n[1]) * n[1] + double(n[2]) * n[2]);
                        for (int c = 0; c < 3 && length > 1e-6; ++c)
                            PutF(p + V_POSITION + c * 4, float(GetF(p + V_POSITION + c * 4) + n[c] / length * mesh.lift));
                        PutF(p + V_UV, mv.u);
                        PutF(p + V_UV + 4, mv.v);
                        PutF(p + V_UV2, mv.u);
                        PutF(p + V_UV2 + 4, mv.v);
                        it = made.emplace(k, uint16_t(out.lookup.size())).first;
                        out.lookup.push_back(uint16_t(at / VERTEX));
                        out.bones.push_back(skin.bones[mv.lookup]);
                    }
                    added.push_back(it->second);
                }
            if (added.size() - iStart > MAX_16)
                return "a section of the mesh has more indices than the game reads";
            auto s = skin.sections[from];
            Put16(&s[S_GEOSET], DETAIL_GEOSET);
            Put16(&s[S_LEVEL], 0);
            Put16(&s[S_VSTART], uint16_t(vStart));
            Put16(&s[S_VCOUNT], uint16_t(out.lookup.size() - vStart));
            Put16(&s[S_ISTART], uint16_t(iStart));               // from the mesh's place, below
            Put16(&s[S_ICOUNT], uint16_t(added.size() - iStart));
            sections.push_back(s);
        }

        // The indices go in before the section that starts last, so every section still starts
        // within the 16 bits the game reads.
        uint32_t last = 0;
        for (auto const& s : skin.sections)
            last = std::max<uint32_t>(last, Get16(&s[S_ISTART]));
        for (auto const& s : skin.sections)
            if (Get16(&s[S_ISTART]) < last && uint32_t(Get16(&s[S_ISTART])) + Get16(&s[S_ICOUNT]) > last)
                return "a section runs into the one that starts last";
        if (last + added.size() > MAX_16)
            return "the mesh has more indices than fit before the last section";
        if (skin.sections.size() + sections.size() > MAX_16)
            return "the mesh has more sections than the skin can";
        out.indices.insert(out.indices.begin() + last, added.begin(), added.end());
        for (auto& s : out.sections)
            if (Get16(&s[S_ISTART]) >= last)
                Put16(&s[S_ISTART], uint16_t(Get16(&s[S_ISTART]) + added.size()));
        for (auto& s : sections)
        {
            Put16(&s[S_ISTART], uint16_t(last + Get16(&s[S_ISTART])));
            auto b = skin.batches[face];
            Put16(&b[B_SECTION], uint16_t(out.sections.size()));
            Put16(&b[B_MATERIAL], uint16_t(blended));
            out.sections.push_back(s);
            out.batches.push_back(b);
        }

        // The model with its vertices at the end of its file (their old place cleared), the rest as it was.
        Bytes model(m2);
        std::fill(model.begin() + vertexAt, model.begin() + vertexAt + size_t(vertexCount) * VERTEX, 0);
        model.resize((model.size() + 15) / 16 * 16, 0);
        Put32(&model[M2_VERTICES], uint32_t(vertices.size() / VERTEX));
        Put32(&model[M2_VERTICES + 4], uint32_t(model.size()));
        model.insert(model.end(), vertices.begin(), vertices.end());
        m2.swap(model);
        skinFile = out.Write();
        return "";
    }

    bool ReadDetail(Bytes const& data, Image& image)
    {
        if (data.size() < 16 || std::memcmp(data.data(), "BDET", 4) != 0 || Get32(&data[4]) != 1)
            return false;
        uint32_t w = Get32(&data[8]), h = Get32(&data[12]);
        if (!w || !h || w > 4096 || h > 4096)
            return false;
        Bytes rgba(size_t(w) * h * 4);
        uLongf got = uLongf(rgba.size());
        if (uncompress(rgba.data(), &got, &data[16], uLong(data.size() - 16)) != Z_OK || got != rgba.size())
            return false;
        image.width = w;
        image.height = h;
        image.rgba.swap(rgba);
        return true;
    }

    namespace
    {
        // Textures -------------------------------------------------------------------------------

        constexpr size_t BLP_HEADER = 148, BLP_PALETTE = 1024;
        constexpr uint32_t EXTRA_SIZE = 512, EXTRA_LEVELS = 10;      // 512 by 512 down to 1 by 1

        using Rgb = std::array<uint8_t, 3>;

        int Distance(uint8_t const* a, uint8_t const* b)
        {
            int dr = int(a[0]) - b[0], dg = int(a[1]) - b[1], db = int(a[2]) - b[2];
            return dr * dr + dg * dg + db * db;
        }

        // The palette entry nearest [rgb], among those [allowed] lets (all if empty).
        uint8_t Nearest(std::vector<Rgb> const& palette, uint8_t const* rgb, std::vector<bool> const& allowed = {})
        {
            int best = INT_MAX;
            uint8_t pick = 0;
            for (size_t i = 0; i < palette.size(); ++i)
            {
                if (!allowed.empty() && !allowed[i])
                    continue;
                int d = Distance(palette[i].data(), rgb);
                if (d < best)
                {
                    best = d;
                    pick = uint8_t(i);
                }
            }
            return pick;
        }

        // A colour's place in a table of colours of 5 bits a channel.
        uint32_t Bin(uint8_t const* rgb) { return uint32_t(rgb[0] >> 3) << 10 | uint32_t(rgb[1] >> 3) << 5 | uint32_t(rgb[2] >> 3); }
    }

    bool TallExtra(Bytes const& blp, Image const& detail, Bytes& out)
    {
        uint32_t const size = EXTRA_SIZE;
        if (blp.size() < BLP_HEADER + BLP_PALETTE || std::memcmp(blp.data(), "BLP2", 4) != 0 || Get32(&blp[4]) != 1
            || blp[8] != 1 || Get32(&blp[12]) != size || Get32(&blp[16]) != size)
            return false;
        uint8_t depth = blp[9];
        if (depth != 0 && depth != 1 && depth != 4 && depth != 8)
            return false;
        if (detail.width && (detail.width != size || detail.height != size || detail.rgba.size() < size_t(size) * size * 4))
            return false;

        // The top half: the texture's entries and alpha at each level.
        std::vector<Bytes> entries(EXTRA_LEVELS), alphas(EXTRA_LEVELS);
        for (uint32_t k = 0; k < EXTRA_LEVELS; ++k)
        {
            size_t n = size_t(size >> k) * (size >> k);
            size_t bytes = n + (depth * n + 7) / 8;
            uint32_t at = Get32(&blp[20 + k * 4]);
            if (Get32(&blp[84 + k * 4]) < bytes || size_t(at) + bytes > blp.size())
                return false;
            entries[k].assign(blp.begin() + at, blp.begin() + at + n);
            alphas[k].assign(n, 255);
            uint8_t const* a = &blp[at + n];
            for (size_t i = 0; i < n && depth; ++i)
                alphas[k][i] = depth == 8 ? a[i] : depth == 4 ? uint8_t(((a[i / 2] >> ((i % 2) * 4)) & 15) * 17)
                                                              : ((a[i / 8] >> (i % 8)) & 1) ? 255 : 0;
        }
        std::vector<Rgb> palette(256);
        for (int i = 0; i < 256; ++i)
            palette[i] = { blp[BLP_HEADER + i * 4 + 2], blp[BLP_HEADER + i * 4 + 1], blp[BLP_HEADER + i * 4] };

        // The lower half at each level: the detail with its gutter transparent, then halved level by
        // level (its colours by their alpha, so the transparent ones don't run into it).
        std::vector<Bytes> lower(EXTRA_LEVELS);
        lower[0].assign(size_t(size) * size * 4, 0);
        bool any = false;
        if (detail.width)
        {
            std::copy(detail.rgba.begin(), detail.rgba.begin() + size_t(size) * size * 4, lower[0].begin());
            for (size_t i = 0; i < size_t(size) * DETAIL_GUTTER; ++i)
                lower[0][i * 4 + 3] = 0;
            for (size_t i = 0; i < size_t(size) * size && !any; ++i)
                any = lower[0][i * 4 + 3] != 0;
        }
        for (uint32_t k = 1; k < EXTRA_LEVELS && any; ++k)
        {
            uint32_t w = size >> k;
            Bytes const& from = lower[k - 1];
            lower[k].assign(size_t(w) * w * 4, 0);
            for (uint32_t y = 0; y < w; ++y)
                for (uint32_t x = 0; x < w; ++x)
                {
                    uint32_t sum[4] = {};
                    for (uint32_t d = 0; d < 4; ++d)
                    {
                        uint8_t const* p = &from[((size_t(y) * 2 + d / 2) * w * 2 + x * 2 + d % 2) * 4];
                        for (int c = 0; c < 3; ++c)
                            sum[c] += uint32_t(p[c]) * p[3];
                        sum[3] += p[3];
                    }
                    uint8_t* q = &lower[k][(size_t(y) * w + x) * 4];
                    for (int c = 0; c < 3 && sum[3]; ++c)
                        q[c] = uint8_t((sum[c] + sum[3] / 2) / sum[3]);
                    q[3] = uint8_t((sum[3] + 2) / 4);
                }
        }

        // The detail's colours: the least used entries freed for them (what used those takes its nearest
        // kept colour, as ReplacePixels does), a k-means of the detail's colours (by alpha) in their place.
        std::vector<uint8_t> moved(256);
        std::iota(moved.begin(), moved.end(), 0);
        std::vector<int> fresh;
        uint8_t clear = 0;                                          // where the detail is transparent
        if (any)
        {
            std::vector<std::array<double, 4>> bins(1 << 15, { 0, 0, 0, 0 });
            for (size_t i = 0; i < size_t(size) * size; ++i)
            {
                uint8_t const* p = &lower[0][i * 4];
                if (!p[3])
                    continue;
                auto& b = bins[Bin(p)];
                for (int c = 0; c < 3; ++c)
                    b[c] += double(p[c]) * p[3];
                b[3] += p[3];
            }
            std::vector<std::array<double, 4>> colours;            // mean colour, weight
            double total[4] = { 0, 0, 0, 0 };
            for (auto const& b : bins)
                if (b[3] > 0)
                {
                    colours.push_back({ b[0] / b[3], b[1] / b[3], b[2] / b[3], b[3] });
                    for (int c = 0; c < 4; ++c)
                        total[c] += b[c];
                }
            size_t k = std::min<size_t>(32, colours.size());
            std::vector<size_t> uses(256, 0);
            for (uint8_t e : entries[0])
                ++uses[e];
            std::vector<int> order(256);
            std::iota(order.begin(), order.end(), 0);
            std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return uses[a] < uses[b]; });
            std::vector<bool> kept(256, true);
            for (size_t j = 0; j < k; ++j)
                kept[order[j]] = false;
            for (int i = 0; i < 256; ++i)
                if (!kept[i])
                    moved[i] = Nearest(palette, palette[i].data(), kept);
            // From k colours spread over the detail by lightness, then eight rounds.
            std::sort(colours.begin(), colours.end(), [](auto const& a, auto const& b)
                { return 299 * a[0] + 587 * a[1] + 114 * a[2] < 299 * b[0] + 587 * b[1] + 114 * b[2]; });
            std::vector<std::array<double, 3>> centres(k);
            double seen = 0;
            for (size_t i = 0, j = 0; i < colours.size() && j < k; ++i)
            {
                seen += colours[i][3];
                while (j < k && seen >= (j + 0.5) * total[3] / k)
                    centres[j++] = { colours[i][0], colours[i][1], colours[i][2] };
            }
            std::vector<size_t> nearest(colours.size());
            for (int pass = 0; pass < 8; ++pass)
            {
                std::vector<std::array<double, 4>> sums(k, { 0, 0, 0, 0 });
                for (size_t i = 0; i < colours.size(); ++i)
                {
                    double best = 1e30;
                    for (size_t j = 0; j < k; ++j)
                    {
                        double d = 0;
                        for (int c = 0; c < 3; ++c)
                            d += (colours[i][c] - centres[j][c]) * (colours[i][c] - centres[j][c]);
                        if (d < best)
                        {
                            best = d;
                            nearest[i] = j;
                        }
                    }
                    for (int c = 0; c < 3; ++c)
                        sums[nearest[i]][c] += colours[i][c] * colours[i][3];
                    sums[nearest[i]][3] += colours[i][3];
                }
                for (size_t j = 0; j < k; ++j)
                    for (int c = 0; c < 3 && sums[j][3] > 0; ++c)
                        centres[j][c] = sums[j][c] / sums[j][3];
            }
            for (size_t j = 0; j < k; ++j)
            {
                for (int c = 0; c < 3; ++c)
                    palette[order[j]][c] = uint8_t(std::lround(std::clamp(centres[j][c], 0.0, 255.0)));
                fresh.push_back(order[j]);
            }
            uint8_t rgb[3];
            for (int c = 0; c < 3; ++c)
                rgb[c] = uint8_t(std::lround(total[c] / total[3]));
            clear = Nearest(palette, rgb);
        }
        std::vector<int> table(1 << 15, -1);                        // a colour (5 bits a channel): its entry
        auto entry = [&](uint8_t const* rgb)
        {
            int& e = table[Bin(rgb)];
            if (e < 0)
            {
                uint8_t centre[3] = { uint8_t((rgb[0] & 0xF8) | 4), uint8_t((rgb[1] & 0xF8) | 4), uint8_t((rgb[2] & 0xF8) | 4) };
                e = Nearest(palette, centre);
            }
            return uint8_t(e);
        };

        // Written as the texture was, twice as tall with 8 bits of alpha: each level the top half's
        // entries, the lower half's (the gutter's those of the top half's last row, as its colours run
        // on), then the alpha; the last level (1 by 1) the two halves mixed.
        out.assign(blp.begin(), blp.begin() + BLP_HEADER + BLP_PALETTE);
        out[9] = 8;
        out[11] = 1;
        Put32(&out[16], size * 2);
        std::fill(out.begin() + 20, out.begin() + BLP_HEADER, 0);
        for (int i : fresh)
        {
            out[BLP_HEADER + i * 4] = palette[i][2];
            out[BLP_HEADER + i * 4 + 1] = palette[i][1];
            out[BLP_HEADER + i * 4 + 2] = palette[i][0];
            out[BLP_HEADER + i * 4 + 3] = 0;
        }
        for (uint32_t k = 0; k <= EXTRA_LEVELS; ++k)
        {
            Bytes e, a;
            if (k < EXTRA_LEVELS)
            {
                uint32_t w = size >> k, gutter = DETAIL_GUTTER >> k;
                for (uint8_t i : entries[k])
                    e.push_back(moved[i]);
                a = alphas[k];
                for (uint32_t y = 0; y < w; ++y)
                    for (uint32_t x = 0; x < w; ++x)
                    {
                        uint8_t const* p = any ? &lower[k][(size_t(y) * w + x) * 4] : nullptr;
                        uint8_t value = (!any || y < gutter) ? e[size_t(w) * (w - 1) + x] : (p[3] ? entry(p) : clear);
                        e.push_back(value);
                        a.push_back(p && y >= gutter ? p[3] : 0);
                    }
            }
            else
            {
                Rgb const& top = palette[moved[entries[k - 1][0]]];
                uint32_t ta = alphas[k - 1][0], ba = any ? lower[k - 1][3] : 0;
                uint8_t rgb[3];
                for (int c = 0; c < 3; ++c)
                    rgb[c] = ta + ba ? uint8_t((top[c] * ta + (any ? lower[k - 1][c] : 0) * ba + (ta + ba) / 2) / (ta + ba)) : top[c];
                e.push_back(Nearest(palette, rgb));
                a.push_back(uint8_t((ta + ba + 1) / 2));
            }
            size_t at = out.size();
            out.insert(out.end(), e.begin(), e.end());
            out.insert(out.end(), a.begin(), a.end());
            Put32(&out[20 + k * 4], uint32_t(at));
            Put32(&out[84 + k * 4], uint32_t(out.size() - at));
        }
        return true;
    }
}
