// SPDX-License-Identifier: GPL-3.0-or-later
// Bottom Features' model and texture changes on their own. With no arguments: a made-up model, skin and
// texture changed and checked (no game files, so CI runs it). With a folder of the game's female models
// (GAME: <GAME>/Character/<Race>/Female/<Race>Female.m2, 00.skin, Skin00_00_Extra.blp, as the HD looks
// have them): each race's model changed with a small mesh (the body's triangles in a box of its texture,
// or RACE's from the mesh file MESH) and its tall extra texture with a test detail and without, written
// to OUT in the same folders, to check with mod-allied-races/tools/check_m2.py and draw with render_m2.py.
//
//   g++ -std=c++17 -O2 -I../src test_model.cpp ../src/SkyborneFiles.cpp ../src/SkyborneModel.cpp -lz
//   ./a.out [GAME OUT [RACE [MESH]]]

#include "SkyborneModel.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <map>
#include <set>
#include <zlib.h>

using namespace Skyborne;

static int failures = 0;

static void Check(bool ok, std::string const& what)
{
    std::cout << (ok ? "ok   " : "FAIL ") << what << "\n";
    if (!ok)
        ++failures;
}

static uint32_t Get32(uint8_t const* p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }
static uint16_t Get16(uint8_t const* p) { return uint16_t(p[0] | p[1] << 8); }
static void Put32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; ++i) p[i] = uint8_t(v >> (8 * i)); }
static float GetF(uint8_t const* p) { float f; std::memcpy(&f, p, 4); return f; }
static void Add16(Bytes& b, uint16_t v) { b.push_back(uint8_t(v)); b.push_back(uint8_t(v >> 8)); }
static void Add32(Bytes& b, uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back(uint8_t(v >> (8 * i))); }
static void AddF(Bytes& b, float f) { uint32_t v; std::memcpy(&v, &f, 4); Add32(b, v); }

// A skin file read back: its arrays as (count, offset).
struct SkinView
{
    Bytes const& d;
    explicit SkinView(Bytes const& data) : d(data) {}
    uint32_t Count(int i) const { return Get32(&d[4 + i * 8]); }
    uint8_t const* At(int i) const { return &d[Get32(&d[8 + i * 8])]; }
    uint16_t Lookup(size_t k) const { return Get16(At(0) + k * 2); }
    uint16_t Index(size_t k) const { return Get16(At(1) + k * 2); }
    uint8_t const* Bones(size_t k) const { return At(2) + k * 4; }
    uint8_t const* Section(size_t k) const { return At(3) + k * 48; }
    uint8_t const* Batch(size_t k) const { return At(4) + k * 24; }
};

static uint8_t const* Vertex(Bytes const& m2, size_t i) { return &m2[Get32(&m2[64]) + i * 48]; }

static std::string Hex(uint32_t v)
{
    char text[16];
    std::snprintf(text, sizeof(text), "%08x", v);
    return text;
}

static std::string ModelLine(Bytes const& m2, Bytes const& skin, size_t vertices, size_t tris)
{
    uint32_t a = 0, b = 0;
    ModelPrint(m2, skin, a, b);
    return "model " + Hex(a) + " " + Hex(b) + " " + std::to_string(vertices) + " " + std::to_string(tris * 3) + "\n";
}

// The made-up model --------------------------------------------------------------------------------
// 17 vertices: a body of 9 (a 3 by 3 grid, the body texture), a face of 4 (the extra texture) and a
// leg of 4 (the body texture again, the section that starts last); the skin's lookup runs the body's
// backwards. Then a tail the change must keep.

static void Section(Bytes& s, uint16_t geoset, uint16_t vStart, uint16_t vCount, uint16_t iStart, uint16_t iCount,
                    uint16_t bones, uint16_t combo, float centre)
{
    Add16(s, geoset); Add16(s, 0); Add16(s, vStart); Add16(s, vCount); Add16(s, iStart); Add16(s, iCount);
    Add16(s, bones); Add16(s, combo); Add16(s, 1); Add16(s, 0);
    for (int i = 0; i < 6; ++i)
        AddF(s, centre + i);
    AddF(s, centre / 10);
}

static void Batch(Bytes& s, int8_t priority, uint16_t section, uint16_t material, uint16_t texture)
{
    s.push_back(16); s.push_back(uint8_t(priority)); Add16(s, 0); Add16(s, section); Add16(s, 0); Add16(s, 0xFFFF);
    Add16(s, material); Add16(s, 0); Add16(s, 1); Add16(s, texture); Add16(s, 0); Add16(s, 0); Add16(s, 0);
}

static Bytes MakeModel(uint16_t blend = 2)
{
    Bytes m(304, 0);
    std::memcpy(m.data(), "MD20", 4);
    Put32(&m[4], 264);
    Put32(&m[68], 1);
    auto place = [&](size_t at, uint32_t count, Bytes const& data)
    {
        while (m.size() % 16)
            m.push_back(0);
        Put32(&m[at], count);
        Put32(&m[at + 4], uint32_t(m.size()));
        m.insert(m.end(), data.begin(), data.end());
    };
    place(8, 5, Bytes{ 'T', 'e', 's', 't', 0 });
    Bytes v;
    for (int i = 0; i < 17; ++i)
    {
        AddF(v, 0.1f + 0.01f * i); AddF(v, 0.1f * (i % 3)); AddF(v, 1.0f + 0.1f * (i / 3));
        v.push_back(255); v.push_back(0); v.push_back(0); v.push_back(0);
        v.push_back(uint8_t(i % 2)); v.push_back(0); v.push_back(0); v.push_back(0);
        AddF(v, 2.0f); AddF(v, 0); AddF(v, 0);                 // a normal of length 2: the lift is along it, still
        AddF(v, 0.1f + 0.05f * (i % 3)); AddF(v, 0.2f + 0.05f * (i / 3));
        AddF(v, 0.9f); AddF(v, 0.9f);
    }
    place(60, 17, v);
    Bytes t;
    for (uint32_t kind : { 1u, 8u })
    {
        Add32(t, kind); Add32(t, 0); Add32(t, 0); Add32(t, 0);
    }
    place(80, 2, t);
    Bytes mats;
    Add16(mats, 0); Add16(mats, 0); Add16(mats, 16); Add16(mats, blend);
    place(112, 2, mats);
    Bytes bones;
    Add16(bones, 0); Add16(bones, 1); Add16(bones, 1);
    place(120, 3, bones);
    Bytes lookup;
    Add16(lookup, 0); Add16(lookup, 1);
    place(128, 2, lookup);
    Bytes unit;
    Add16(unit, 0);
    place(136, 1, unit);
    m.insert(m.end(), { 'T', 'A', 'I', 'L' });
    return m;
}

// [leg]: where the leg's indices start (padded with zeros up to it); [faceVertex]: the M2 vertex of
// the face's first lookup entry.
static Bytes MakeSkin(uint32_t leg = 30, uint16_t faceVertex = 9, uint16_t legGeoset = 1301, uint16_t faceIndices = 6)
{
    Bytes lookup, indices, props, sections, batches;
    for (int k = 0; k < 17; ++k)
    {
        Add16(lookup, uint16_t(k < 9 ? 8 - k : (k == 9 ? faceVertex : k)));
        props.push_back(uint8_t(k % 2)); props.push_back(uint8_t(k)); props.push_back(0); props.push_back(0);
    }
    for (int y = 0; y < 2; ++y)
        for (int x = 0; x < 2; ++x)
        {
            uint16_t a = uint16_t(y * 3 + x), b = uint16_t(a + 1), c = uint16_t(a + 3), d = uint16_t(a + 4);
            for (uint16_t i : { a, c, b, b, c, d })
                Add16(indices, i);
        }
    for (uint16_t i : { 9, 11, 10, 10, 11, 12 })
        Add16(indices, i);
    while (indices.size() / 2 < leg)
        Add16(indices, 0);
    for (uint16_t i : { 13, 15, 14, 14, 15, 16 })
        Add16(indices, i);
    Section(sections, 0, 0, 9, 0, 24, 2, 0, 1.0f);
    Section(sections, 0, 9, 4, 24, faceIndices, 1, 1, 7.0f);
    Section(sections, legGeoset, 13, 4, uint16_t(leg), 6, 1, 2, 4.0f);
    Batch(batches, 0, 0, 0, 0);
    Batch(batches, 3, 1, 0, 1);
    Batch(batches, 0, 2, 0, 0);
    Bytes s(48, 0);
    std::memcpy(s.data(), "SKIN", 4);
    Bytes const* parts[5] = { &lookup, &indices, &props, &sections, &batches };
    uint32_t counts[5] = { 17, uint32_t(indices.size() / 2), 17, 3, 3 };
    for (int i = 0; i < 5; ++i)
    {
        Put32(&s[4 + i * 8], counts[i]);
        Put32(&s[8 + i * 8], uint32_t(s.size()));
        s.insert(s.end(), parts[i]->begin(), parts[i]->end());
    }
    return s;
}

static std::string MakeMesh(Bytes const& m2, Bytes const& skin, std::string const& vertexLines = "", std::string const& triLines = "")
{
    std::string vertices = vertexLines.empty()
        ? "vertex 0 0.1 0.6\nvertex 1 0.2 0.6\nvertex 3 0.1 0.7\nvertex 4 0.2 0.7\n"
          "vertex 13 0.5 0.8\nvertex 14 0.6 0.8\nvertex 15 0.5 0.9\n" : vertexLines;
    std::string tris = triLines.empty() ? "tri 2 4 5 6\ntri 0 0 2 1\ntri 0 1 2 3\n" : triLines;
    size_t nv = 0, nt = 0;
    for (size_t at = 0; (at = vertices.find("vertex", at)) != std::string::npos; ++at)
        ++nv;
    for (size_t at = 0; (at = tris.find("tri", at)) != std::string::npos; ++at)
        ++nt;
    return "# a test mesh\n" + ModelLine(m2, skin, nv, nt) + "lift 0.002\n" + vertices + tris;
}

static bool Fails(Bytes m2, Bytes skin, std::string const& mesh, std::string const& what)
{
    Bytes m0 = m2, s0 = skin;
    std::string error = AddBodyDetail(m2, skin, mesh);
    std::cout << "       (" << what << ": " << (error.empty() ? "no error" : error) << ")\n";
    return !error.empty() && m2 == m0 && skin == s0;
}

static void TestModel()
{
    Bytes m2 = MakeModel(), skin = MakeSkin(), m0 = m2, s0 = skin;
    std::string error = AddBodyDetail(m2, skin, MakeMesh(m0, s0));
    Check(error.empty(), "the made-up model takes the mesh" + (error.empty() ? "" : ": " + error));
    if (!error.empty())
        return;
    // The model: its vertices moved to the end, the face's V halved, the copies after them.
    Check(Get32(&m2[60]) == 24 && Get32(&m2[64]) % 16 == 0 && Get32(&m2[64]) >= m0.size(), "24 vertices, at the end of the file");
    Check(m2.size() == Get32(&m2[64]) + 24 * 48, "...and nothing after them");
    Check(std::equal(m0.begin(), m0.begin() + 60, m2.begin()) && std::equal(m0.begin() + 68, m0.begin() + 304, m2.begin() + 68),
          "the rest of the header as it was");
    size_t oldAt = Get32(&m0[64]);
    Check(std::equal(m0.begin() + oldAt + 17 * 48, m0.end(), m2.begin() + oldAt + 17 * 48), "the arrays after the old vertices (and the tail) as they were");
    bool same = true, halved = true;
    for (size_t i = 0; i < 17; ++i)
    {
        uint8_t const* a = &m0[oldAt + i * 48];
        uint8_t const* b = Vertex(m2, i);
        bool face = i >= 9 && i <= 12;
        same = same && std::memcmp(a, b, 36) == 0 && std::memcmp(a + 40, b + 40, 8) == 0 && (face || std::memcmp(a + 36, b + 36, 4) == 0);
        halved = halved && (!face || GetF(b + 36) == GetF(a + 36) * 0.5f);
    }
    Check(same, "the model's vertices as they were but for the face's V");
    Check(halved, "the face's V halved");
    // Copies: lines 0, 2, 1, 3 (section 0, lookup 0, 3, 1, 4: vertices 8, 5, 7, 4), then 4, 5, 6 (13, 14, 15).
    int const from[7] = { 8, 5, 7, 4, 13, 14, 15 };
    float const uv[7][2] = { { 0.1f, 0.6f }, { 0.1f, 0.7f }, { 0.2f, 0.6f }, { 0.2f, 0.7f }, { 0.5f, 0.8f }, { 0.6f, 0.8f }, { 0.5f, 0.9f } };
    bool copies = true;
    for (int i = 0; i < 7; ++i)
    {
        uint8_t const* a = &m0[oldAt + from[i] * 48];
        uint8_t const* b = Vertex(m2, 17 + i);
        copies = copies && std::fabs(GetF(b) - (GetF(a) + 0.002f)) < 1e-6 && GetF(b + 4) == GetF(a + 4) && GetF(b + 8) == GetF(a + 8)
            && std::memcmp(a + 12, b + 12, 20) == 0 && GetF(b + 32) == uv[i][0] && GetF(b + 36) == uv[i][1]
            && GetF(b + 40) == uv[i][0] && GetF(b + 44) == uv[i][1];
    }
    Check(copies, "the copies: the vertices' bones and normals, lifted along the normal, with the mesh's coordinates");
    // The skin.
    SkinView s(skin), o(s0);
    Check(s.Count(0) == 24 && s.Count(2) == 24 && s.Count(1) == 45 && s.Count(3) == 5 && s.Count(4) == 5, "the skin's counts");
    bool lookup = true;
    for (size_t k = 0; k < 17; ++k)
        lookup = lookup && s.Lookup(k) == o.Lookup(k) && std::memcmp(s.Bones(k), o.Bones(k), 4) == 0;
    int const fromLookup[7] = { 0, 3, 1, 4, 13, 14, 15 };
    for (size_t k = 17; k < 24; ++k)
        lookup = lookup && s.Lookup(k) == k && std::memcmp(s.Bones(k), o.Bones(fromLookup[k - 17]), 4) == 0;
    Check(lookup, "the skin's vertices and their bones: the copies take their originals' slots");
    uint16_t const added[9] = { 17, 18, 19, 19, 18, 20, 21, 22, 23 };
    bool indices = true;
    for (size_t k = 0; k < 45; ++k)
        indices = indices && s.Index(k) == (k < 30 ? o.Index(k) : k < 39 ? added[k - 30] : o.Index(k - 9));
    Check(indices, "the new indices before the leg's");
    Check(std::memcmp(s.Section(0), o.Section(0), 48) == 0 && std::memcmp(s.Section(1), o.Section(1), 48) == 0, "the body's and the face's sections as they were");
    Check(Get16(s.Section(2) + 8) == 39 && std::memcmp(s.Section(2), o.Section(2), 8) == 0 && std::memcmp(s.Section(2) + 10, o.Section(2) + 10, 38) == 0,
          "the leg's section moved on by 9");
    auto section = [&](size_t i, uint16_t vStart, uint16_t vCount, uint16_t iStart, uint16_t iCount, size_t source)
    {
        uint8_t const* n = s.Section(i);
        return Get16(n) == 1101 && Get16(n + 2) == 0 && Get16(n + 4) == vStart && Get16(n + 6) == vCount && Get16(n + 8) == iStart
            && Get16(n + 10) == iCount && std::memcmp(n + 12, o.Section(source) + 12, 36) == 0;
    };
    Check(section(3, 17, 4, 30, 6, 0), "a section of geoset 1101 for the body's triangles, with the body's bones, centre and radius");
    Check(section(4, 21, 3, 36, 3, 2), "...and one for the leg's");
    bool batches = std::memcmp(s.Batch(0), o.Batch(0), 72) == 0;
    for (size_t i = 3; i < 5; ++i)
    {
        uint8_t const* b = s.Batch(i);
        uint8_t const* f = o.Batch(1);
        batches = batches && Get16(b + 4) == i && Get16(b + 10) == 1 && std::memcmp(b, f, 4) == 0 && std::memcmp(b + 6, f + 6, 4) == 0
            && std::memcmp(b + 12, f + 12, 12) == 0;
    }
    Check(batches, "the face's batch copied for each, with the blended material");
    Bytes m3 = m2, s3 = skin;
    Check(!AddBodyDetail(m3, s3, MakeMesh(m2, skin)).empty(), "a model changed already isn't changed again");

    // What it refuses, leaving the files as they were.
    std::string other = MakeMesh(m0, s0);
    size_t crc = other.find("model ") + 6;
    other.replace(crc, 8, other.compare(crc, 8, "00000000") ? "00000000" : "00000001");
    Check(Fails(m0, s0, other, "another model"), "a mesh made for another model");
    std::string empty = MakeMesh(m0, s0);
    Check(Fails(m0, s0, empty.substr(0, empty.find(" 7 9\n")) + " 0 0\nlift 0.002\n", "empty"), "a mesh without triangles");
    Bytes shared = MakeSkin(30, 8);
    Check(Fails(m0, shared, MakeMesh(m0, shared), "shared"), "a vertex of the face's that the body has too");
    Check(Fails(m0, s0, MakeMesh(m0, s0, "", "tri 2 4 5 6\ntri 0 0 2 1\ntri 0 1 2 4\n"), "another section"), "a triangle with a vertex of another section");
    Check(Fails(m0, s0, MakeMesh(m0, s0, "vertex 0 0.1 0.51\nvertex 1 0.2 0.6\nvertex 3 0.1 0.7\n", "tri 0 0 2 1\n"), "gutter"), "a vertex in the gutter");
    Check(Fails(m0, s0, MakeMesh(m0, s0, "vertex 0 1.1 0.6\nvertex 1 0.2 0.6\nvertex 3 0.1 0.7\n", "tri 0 0 2 1\n"), "u"), "a vertex outside the texture");
    Check(Fails(m0, s0, MakeMesh(m0, s0, "vertex 99 0.1 0.6\nvertex 1 0.2 0.6\nvertex 3 0.1 0.7\n", "tri 0 0 2 1\n"), "lookup"), "a vertex the skin hasn't");
    Check(Fails(m0, s0, MakeMesh(m0, s0, "", "tri 0 0 2 1\ntri 0 1 2 9\n"), "line"), "a triangle naming a vertex line the mesh hasn't");
    std::string counted = MakeMesh(m0, s0);
    Check(Fails(m0, s0, counted.replace(counted.find(" 7 9\n"), 5, " 6 9\n"), "count"), "a model line counting other lines");
    Check(Fails(m0, s0, MakeMesh(m0, s0) + "colour 1 2 3\n", "record"), "a line it doesn't know");
    Check(Fails(m0, s0, MakeMesh(m0, s0) + "lift 0.001\n", "lift"), "a second lift line");
    Bytes far = MakeSkin(65530);
    Check(Fails(m0, far, MakeMesh(m0, far), "65530"), "the last section pushed past 65535");
    Bytes edge = MakeSkin(65526), me = m0;
    Check(AddBodyDetail(me, edge, MakeMesh(m0, edge)).empty() && Get16(SkinView(edge).Section(2) + 8) == 65535, "...but not when it then starts at 65535");
    Bytes into = MakeSkin(30, 9, 1301, 9);
    Check(Fails(m0, into, MakeMesh(m0, into), "overlap"), "a section running into the last one");
    Bytes has = MakeSkin(30, 9, 1101);
    Check(Fails(m0, has, MakeMesh(m0, has), "1101"), "a model with geoset 1101 already");
    Bytes opaque = MakeModel(0);
    Check(Fails(opaque, s0, MakeMesh(opaque, s0), "material"), "a model without a blended material");
    Check(Fails(m0, Bytes(s0.begin(), s0.begin() + 40), MakeMesh(m0, s0), "skin"), "a skin file cut short");
}

// The textures ---------------------------------------------------------------------------------------

static Bytes MakeExtra()
{
    Bytes b(148 + 1024, 0);
    std::memcpy(b.data(), "BLP2", 4);
    Put32(&b[4], 1);
    b[8] = 1; b[9] = 0; b[10] = 8; b[11] = 1;
    Put32(&b[12], 512);
    Put32(&b[16], 512);
    for (int i = 0; i < 256; ++i)
    {
        b[148 + i * 4] = uint8_t(i * 37); b[148 + i * 4 + 1] = uint8_t(255 - i); b[148 + i * 4 + 2] = uint8_t(i);
    }
    for (int k = 0; k < 10; ++k)
    {
        uint32_t w = 512 >> k;
        Put32(&b[20 + k * 4], uint32_t(b.size()));
        Put32(&b[84 + k * 4], w * w);
        for (uint32_t y = 0; y < w; ++y)
            for (uint32_t x = 0; x < w; ++x)
                b.push_back(k ? uint8_t(x * 3 + y) : (x < 8 && y < 8) ? uint8_t(250 + (x + y) % 6) : uint8_t((x + 2 * y) % 250));
    }
    return b;
}

static Image MakeDetail()
{
    Image d;
    d.width = d.height = 512;
    d.rgba.assign(512 * 512 * 4, 0);
    for (uint32_t y = 0; y < 512; ++y)
        for (uint32_t x = 0; x < 512; ++x)
        {
            uint8_t* p = &d.rgba[(y * 512 + x) * 4];
            double r = std::hypot(double(x) - 256, double(y) - 300);
            if (y < 16)
            {
                p[1] = 255; p[3] = 255;                                 // in the gutter: to go
            }
            else if (r < 100)
            {
                p[0] = 200; p[1] = 40; p[2] = 40; p[3] = 255;
            }
            else if (r < 130)
            {
                p[0] = 40; p[1] = 40; p[2] = 200; p[3] = 128;
            }
            else if (y >= 440 && y < 480)
            {
                p[0] = uint8_t(x / 2); p[1] = 255; p[2] = uint8_t(255 - x / 2); p[3] = 255;   // more colours than it has room for
            }
        }
    return d;
}

static Bytes MakeBdet(Image const& image)
{
    uLongf size = compressBound(uLong(image.rgba.size()));
    Bytes z(size);
    compress(z.data(), &size, image.rgba.data(), uLong(image.rgba.size()));
    Bytes out = { 'B', 'D', 'E', 'T' };
    Add32(out, 1); Add32(out, image.width); Add32(out, image.height);
    out.insert(out.end(), z.begin(), z.begin() + size);
    return out;
}

// The level [k] of a palette texture with 8 bits of alpha: its entries, then its alpha.
static uint8_t const* Level(Bytes const& b, int k) { return &b[Get32(&b[20 + k * 4])]; }

static void TestTextures()
{
    Bytes extra = MakeExtra(), tall;
    Image detail = MakeDetail();
    Image read;
    Check(ReadDetail(MakeBdet(detail), read) && read.width == 512 && read.height == 512 && read.rgba == detail.rgba, "a style's art read back");
    Bytes bad = MakeBdet(detail);
    bad[0] = 'X';
    Check(!ReadDetail(bad, read), "...but not without its name");
    Bytes cut = MakeBdet(detail);
    cut.resize(cut.size() - 10);
    Check(!ReadDetail(cut, read), "...nor cut short");

    auto started = std::chrono::steady_clock::now();
    bool made = TallExtra(extra, detail, tall);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    Check(made, "a tall texture with a detail (" + std::to_string(int(ms)) + " ms)");
    if (!made)
        return;
    Check(std::memcmp(tall.data(), "BLP2", 4) == 0 && tall[8] == 1 && tall[9] == 8 && tall[10] == 8 && Get32(&tall[12]) == 512 && Get32(&tall[16]) == 1024,
          "a palette texture with 8 bits of alpha, 512 by 1024");
    bool levels = Get32(&tall[20 + 11 * 4]) == 0;
    for (int k = 0; k <= 10; ++k)
    {
        uint32_t w = std::max(1u, 512u >> k), h = std::max(1u, 1024u >> k);
        levels = levels && Get32(&tall[84 + k * 4]) == 2 * w * h && Get32(&tall[20 + k * 4]) + 2 * w * h <= tall.size();
    }
    Check(levels, "11 levels, 512 by 1024 to 1 by 1");
    std::set<int> changed;
    for (int i = 0; i < 256; ++i)
        if (std::memcmp(&tall[148 + i * 4], &extra[148 + i * 4], 3) != 0)
            changed.insert(i);
    std::vector<size_t> uses(256, 0);
    for (size_t i = 0; i < 512 * 512; ++i)
        ++uses[Level(extra, 0)[i]];
    size_t most = 0, least = SIZE_MAX;
    for (int i = 0; i < 256; ++i)
        if (changed.count(i))
            most = std::max(most, uses[i]);
        else
            least = std::min(least, uses[i]);
    Check(changed.size() == 32 && changed.count(250) && changed.count(255) && most <= least, "32 colours given to the detail, the least used");
    bool top = true, opaque = true;
    size_t moved = 0;
    for (int k = 0; k < 10; ++k)
    {
        uint32_t w = 512 >> k;
        uint8_t const* in = Level(extra, k);
        uint8_t const* t = Level(tall, k);
        for (size_t i = 0; i < size_t(w) * w; ++i)
        {
            if (changed.count(in[i]))
            {
                ++moved;
                top = top && !changed.count(t[i]);
            }
            else
                top = top && t[i] == in[i];
            opaque = opaque && t[size_t(w) * w * 2 + i] == 255;
        }
    }
    Check(top, "the top half's entries as they were at every level (" + std::to_string(moved) + " of the freed colours' moved to kept ones)");
    Check(opaque, "...and opaque");
    uint8_t const* t0 = Level(tall, 0);
    bool gutter = true;
    for (uint32_t y = 512; y < 512 + DETAIL_GUTTER; ++y)
        for (uint32_t x = 0; x < 512; ++x)
            gutter = gutter && t0[512 * 1024 + y * 512 + x] == 0 && t0[y * 512 + x] == t0[511 * 512 + x];
    Check(gutter, "the gutter transparent, with the colours of the top half's last row");
    Image image;
    Check(Decode(tall, image) && image.width == 512 && image.height == 1024, "the game's decoder reads it");
    auto near = [&](uint32_t x, uint32_t y, int r, int g, int b, int a)
    {
        uint8_t const* p = &image.rgba[((512 + y) * 512 + x) * 4];
        return std::abs(p[0] - r) <= 8 && std::abs(p[1] - g) <= 8 && std::abs(p[2] - b) <= 8 && p[3] == a;
    };
    Check(near(256, 300, 200, 40, 40, 255) && near(300, 330, 200, 40, 40, 255), "the detail in the lower half");
    Check(near(256, 185, 40, 40, 200, 128) && near(141, 300, 40, 40, 200, 128), "...its colours and alpha");
    int worst = 0;
    for (uint32_t x = 0; x < 512; ++x)
    {
        uint8_t const* p = &image.rgba[((512 + 460) * 512 + x) * 4];
        worst = std::max({ worst, std::abs(p[0] - int(x / 2)), std::abs(p[1] - 255), std::abs(p[2] - (255 - int(x / 2))) });
    }
    Check(worst <= 24 && image.rgba[((512 + 460) * 512 + 100) * 4 + 3] == 255, "...many colours near enough (" + std::to_string(worst) + " at most off)");
    Check(image.rgba[((512 + 600 - 512) * 512 + 10) * 4 + 3] == 0 && image.rgba[((512 + 5) * 512 + 256) * 4 + 3] == 0, "...transparent around it and in the gutter");

    Bytes plain;
    Check(TallExtra(extra, Image(), plain), "a tall texture without a detail");
    bool verbatim = std::memcmp(&plain[148], &extra[148], 1024) == 0, clear = true;
    for (int k = 0; k < 10; ++k)
    {
        uint32_t w = 512 >> k;
        verbatim = verbatim && std::memcmp(Level(plain, k), Level(extra, k), size_t(w) * w) == 0;
        uint8_t const* a = Level(plain, k) + size_t(w) * w * 2;
        for (size_t i = size_t(w) * w; i < size_t(w) * w * 2; ++i)
            clear = clear && a[i] == 0;
    }
    Check(verbatim, "...its palette and top half the texture's at every level");
    Check(clear, "...its lower half transparent");
    Image none;
    none.width = none.height = 512;
    none.rgba.assign(512 * 512 * 4, 0);
    Bytes empty;
    Check(TallExtra(extra, none, empty) && empty == plain, "a detail with nothing in it the same");

    Image square;
    square.width = square.height = 512;
    square.rgba.assign(512 * 512 * 4, 128);
    Check(!TallExtra(EncodeBlp(square, true), detail, tall), "not from a compressed texture");
    Image small = detail;
    small.width = small.height = 256;
    Check(!TallExtra(extra, small, tall), "not with a detail of another size");
    Bytes short_ = extra;
    Put32(&short_[84 + 5 * 4], 0);
    Check(!TallExtra(short_, detail, tall), "not from a texture without all its levels");
}

// A game's models -------------------------------------------------------------------------------------

static bool ReadFile(fs::path const& path, Bytes& out)
{
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return false;
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

static void WriteFile(fs::path const& path, Bytes const& data)
{
    fs::create_directories(path.parent_path());
    std::ofstream(path, std::ios::binary).write(reinterpret_cast<char const*>(data.data()), std::streamsize(data.size()));
}

// A test mesh for a model: the triangles of the sections of geosets 0, 1301 and 1801 drawn with the
// body texture whose coordinates touch [box] (pixels of the 512 by 512 body texture: x0, y0, x1, y1),
// mapped on to the tall texture's lower half.
static std::string BoxMesh(Bytes const& m2, Bytes const& skin, std::array<int, 4> const& box, size_t& tris)
{
    double const x0 = box[0] / 512.0, y0 = box[1] / 512.0, x1 = box[2] / 512.0, y1 = box[3] / 512.0, top = 0.5 + DETAIL_GUTTER / 1024.0;
    SkinView s(skin);
    uint32_t textures = Get32(&m2[84]), lookups = Get32(&m2[132]);
    std::set<uint32_t> body;
    for (size_t i = 0; i < s.Count(4); ++i)
    {
        uint8_t const* b = s.Batch(i);
        uint32_t texture = Get16(&m2[lookups + Get16(b + 16) * 2]), geoset = Get16(s.Section(Get16(b + 4)));
        if (Get32(&m2[textures + texture * 16]) == 1 && (geoset == 0 || geoset == 1301 || geoset == 1801))
            body.insert(Get16(b + 4));
    }
    std::map<uint32_t, size_t> lines;
    std::string vertices, triangles;
    tris = 0;
    for (uint32_t section : body)
    {
        uint8_t const* sec = s.Section(section);
        for (uint32_t i = Get16(sec + 8); i + 2 < uint32_t(Get16(sec + 8)) + Get16(sec + 10); i += 3)
        {
            double u[3], v[3];
            for (int j = 0; j < 3; ++j)
            {
                uint8_t const* p = Vertex(m2, s.Lookup(s.Index(i + j)));
                u[j] = GetF(p + 32);
                v[j] = GetF(p + 36);
            }
            if (std::max({ u[0], u[1], u[2] }) < x0 || std::min({ u[0], u[1], u[2] }) > x1 || std::max({ v[0], v[1], v[2] }) < y0
                || std::min({ v[0], v[1], v[2] }) > y1)
                continue;
            triangles += "tri " + std::to_string(section);
            for (int j = 0; j < 3; ++j)
            {
                uint32_t k = s.Index(i + j);
                auto it = lines.find(k);
                if (it == lines.end())
                {
                    it = lines.emplace(k, lines.size()).first;
                    double mu = std::clamp((u[j] - x0) / (x1 - x0), 0.0, 1.0), mv = top + std::clamp((v[j] - y0) / (y1 - y0), 0.0, 1.0) * (1 - top);
                    char line[96];
                    std::snprintf(line, sizeof(line), "vertex %u %.6f %.6f\n", k, mu, mv);
                    vertices += line;
                }
                triangles += " " + std::to_string(it->second);
            }
            triangles += "\n";
            ++tris;
        }
    }
    return "# a test mesh: the body's triangles in a box of its texture\n" + ModelLine(m2, skin, lines.size(), tris)
        + "lift 0.0015\n" + vertices + triangles;
}

// A test detail: a disc in the middle of the lower half, its colour running across, with stripes.
static Image TestDetail()
{
    Image d;
    d.width = d.height = 512;
    d.rgba.assign(512 * 512 * 4, 0);
    for (uint32_t y = 0; y < 512; ++y)
        for (uint32_t x = 0; x < 512; ++x)
        {
            double r = std::hypot((double(x) - 256) / 240, (double(y) - 264) / 230);
            if (r > 1)
                continue;
            uint8_t* p = &d.rgba[(y * 512 + x) * 4];
            p[0] = uint8_t(60 + x * 190 / 512);
            p[1] = uint8_t((x / 32 + y / 32) % 2 ? 40 : 160);
            p[2] = uint8_t(200 - y * 160 / 512);
            p[3] = uint8_t(r > 0.9 ? (1 - r) * 10 * 255 : 255);
        }
    return d;
}

static void TestGame(fs::path const& game, fs::path const& out, std::string const& only, fs::path const& meshFile)
{
    // Each race's box, a little wider than its art's.
    std::map<std::string, std::array<int, 4>> const boxes = {
        { "Human", { 254, 223, 318, 261 } }, { "Orc", { 252, 224, 315, 261 } }, { "Dwarf", { 254, 220, 302, 260 } },
        { "NightElf", { 254, 220, 324, 254 } }, { "Scourge", { 252, 210, 339, 256 } }, { "Gnome", { 254, 220, 320, 247 } },
        { "Troll", { 252, 226, 313, 256 } }, { "BloodElf", { 254, 220, 320, 255 } }, { "Draenei", { 254, 222, 329, 258 } } };
    for (auto const& [race, box] : boxes)
    {
        if (!only.empty() && race != only)
            continue;
        fs::path folder = fs::path("Character") / race / "Female";
        Bytes m2, skin, extra;
        if (!ReadFile(game / folder / (race + "Female.m2"), m2) || !ReadFile(game / folder / (race + "Female00.skin"), skin))
            continue;
        uint32_t crcVertices = 0, crcSkin = 0;
        ModelPrint(m2, skin, crcVertices, crcSkin);
        std::cout << race << ": model " << Hex(crcVertices) << " " << Hex(crcSkin) << "\n";
        size_t tris = 0;
        std::string mesh = BoxMesh(m2, skin, box, tris);
        if (!meshFile.empty())
        {
            std::ifstream in(meshFile);
            mesh.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
            tris = 0;
            for (size_t at = 0; (at = mesh.find("\ntri ", at)) != std::string::npos; ++at)
                ++tris;
        }
        Bytes before = skin;
        SkinView o(before);
        uint32_t lastBefore = 0;
        for (size_t i = 0; i < o.Count(3); ++i)
            lastBefore = std::max<uint32_t>(lastBefore, Get16(o.Section(i) + 8));
        uint32_t vertices = Get32(&m2[60]), indices = o.Count(1);
        std::string error = AddBodyDetail(m2, skin, mesh);
        Check(error.empty(), race + ": the model takes a mesh of " + std::to_string(tris) + " triangles" + (error.empty() ? "" : ": " + error));
        if (!error.empty())
            continue;
        SkinView s(skin);
        uint32_t last = 0, detail = 0;
        bool levels = true;
        for (size_t i = 0; i < s.Count(3); ++i)
        {
            last = std::max<uint32_t>(last, Get16(s.Section(i) + 8));
            levels = levels && Get16(s.Section(i) + 2) == 0;
            detail += Get16(s.Section(i)) == 1101;
        }
        Check(levels && last <= 65535 && last == lastBefore + tris * 3, race + ": every section starts within 65535 (the last at " + std::to_string(last)
              + ", " + std::to_string(65535 - last) + " left)");
        Check(detail > 0 && s.Count(1) == indices + tris * 3, race + ": " + std::to_string(detail) + " sections of geoset 1101, "
              + std::to_string(s.Count(1) - indices) + " indices added");
        bool inside = true;
        for (size_t k = 0; k < s.Count(0); ++k)
            inside = inside && s.Lookup(k) < Get32(&m2[60]);
        Check(inside && Get32(&m2[60]) > vertices, race + ": " + std::to_string(Get32(&m2[60]) - vertices) + " vertices added, the skin's within them");
        // The extra texture's coordinates (the batches of texture type 8) in the top half, the copies' in the lower.
        uint32_t textures = Get32(&m2[84]), lookups = Get32(&m2[132]);
        float faceMax = 0, detailMin = 1;
        for (size_t i = 0; i < s.Count(4); ++i)
        {
            uint8_t const* b = s.Batch(i);
            uint8_t const* sec = s.Section(Get16(b + 4));
            if (Get32(&m2[textures + Get16(&m2[lookups + Get16(b + 16) * 2]) * 16]) != 8)
                continue;
            for (uint32_t k = Get16(sec + 4); k < uint32_t(Get16(sec + 4)) + Get16(sec + 6); ++k)
                if (Get16(sec) == 1101)
                    detailMin = std::min(detailMin, GetF(Vertex(m2, s.Lookup(k)) + 36));
                else
                    faceMax = std::max(faceMax, GetF(Vertex(m2, s.Lookup(k)) + 36));
        }
        Check(faceMax <= 0.5f && detailMin >= 0.5f + DETAIL_GUTTER / 1024.0f, race + ": the face's V up to " + std::to_string(faceMax)
              + ", the detail's from " + std::to_string(detailMin));
        WriteFile(out / folder / (race + "Female.m2"), m2);
        WriteFile(out / folder / (race + "Female00.skin"), skin);
        std::ofstream(out / folder / (race + "Female.mesh")) << mesh;

        if (!ReadFile(game / folder / (race + "FemaleSkin00_00_Extra.blp"), extra))
            continue;
        Bytes tall, plain;
        auto started = std::chrono::steady_clock::now();
        bool made = TallExtra(extra, TestDetail(), tall);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
        uLongf packed = compressBound(uLong(tall.size()));
        Bytes z(packed);
        compress2(z.data(), &packed, tall.data(), uLong(tall.size()), 9);
        Check(made && TallExtra(extra, Image(), plain), race + ": its tall extra textures (" + std::to_string(int(ms)) + " ms, "
              + std::to_string(tall.size() / 1024) + " KB, " + std::to_string(packed / 1024) + " KB packed)");
        if (!made)
            continue;
        Image top, original, styled;
        Decode(plain, top);
        Decode(extra, original);
        Decode(tall, styled);
        bool same = top.width == 512 && top.height == 1024 && std::equal(original.rgba.begin(), original.rgba.end(), top.rgba.begin());
        Check(same, race + ": the face as it was in the top half");
        size_t moved = 0;
        int worst = 0;
        for (size_t i = 0; i < original.rgba.size(); i += 4)
        {
            int d = std::max({ std::abs(original.rgba[i] - styled.rgba[i]), std::abs(original.rgba[i + 1] - styled.rgba[i + 1]),
                               std::abs(original.rgba[i + 2] - styled.rgba[i + 2]) });
            moved += d != 0;
            worst = std::max(worst, d);
        }
        std::cout << "       (" << race << ": with the detail, " << moved << " of the face's 262144 pixels another colour, " << worst << " at most off)\n";
        WriteFile(out / folder / (race + "FemaleSkin00_00_Extra_D01.blp"), tall);
        WriteFile(out / folder / (race + "FemaleSkin00_00_Extra_D00.blp"), plain);
    }
}

int main(int argc, char** argv)
{
    if (argc == 1)
    {
        TestModel();
        TestTextures();
    }
    else if (argc >= 3 && argc <= 5)
        TestGame(argv[1], argv[2], argc > 3 ? argv[3] : "", argc > 4 ? argv[4] : "");
    else
    {
        std::cerr << "test_model [GAME OUT [RACE [MESH]]]\n";
        return 2;
    }
    std::cout << (failures ? std::to_string(failures) + " failed\n" : "all passed\n");
    return failures ? 1 : 0;
}
