// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Skyborne's patch for the game. They're races the game doesn't have, on the blood elf's body:
 * the game needs its tables to know them (ChrRaces, CharSections... with their rows: the module's
 * data/client/dbc, made by tools/build_data.py), their blue skins, new hair colours and blue or
 * yellow eyes (textures made from the blood elf's, data/client/textures.txt), their place on the
 * character creation screen (Interface/GlueXML/SkyborneCreate.*, the race icons), and their intro's
 * narration. The textures are the game's own, so the patch is made on the phone from the client's
 * files: as the realm starts, when the client or the module changed since.
 *
 * It's Data/patch-Z.MPQ, read after every other patch (HD looks' character tables included: its
 * tables are those with the Skyborne's rows added). Wrath Remix's patch-Y.MPQ changes other tables.
 */

#include "SkybornePatch.h"
#include "SkyborneFiles.h"
#include "SkyborneShade.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <chrono>
#include <map>
#include <set>
#include <tuple>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <zlib.h>

namespace Skyborne
{
    namespace
    {
        constexpr char const* VERSION = "skyborne-12";

        Bytes ReadFile(fs::path const& path)
        {
            std::ifstream in(path, std::ios::binary);
            return Bytes(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
        }

        std::string Text(fs::path const& path)
        {
            Bytes b = ReadFile(path);
            return std::string(b.begin(), b.end());
        }

        // FNV-1a: what the patch is made from, to know when to make it again.
        struct Stamp
        {
            uint64_t value = 1469598103934665603ull;
            void Add(std::string const& text)
            {
                for (unsigned char c : text)
                {
                    value ^= c;
                    value *= 1099511628211ull;
                }
                value ^= 0xFF;
                value *= 1099511628211ull;
            }
            std::string Hex() const
            {
                char buf[17];
                std::snprintf(buf, sizeof(buf), "%016llx", static_cast<unsigned long long>(value));
                return buf;
            }
        };

        std::string Extra(std::string const& path)
        {
            if (path.size() < 4)
                return "";
            return path.substr(0, path.size() - 4) + "_Extra" + path.substr(path.size() - 4);
        }

        std::string Lower(std::string text)
        {
            for (char& c : text)
                c = char(std::tolower(static_cast<unsigned char>(c)));
            return text;
        }

        // Per-race texture settings (HD, 512 by 512).
        struct Tweak
        {
            char const* race;
            double x, y, strength, across, down;
            bool lightOnly;     // shading in the skin's own colour
            double warm, smooth;
        };
        constexpr Tweak TWEAKS[] = {
            { "bloodelf", 314, 86, 1.0, 1.00, 1.00, false, 0, 0 },
            { "human", 312, 86, 1.0, 1.15, 0.70, false, 0, 0 },
            { "dwarf", 318, 90, 1.0, 1.00, 1.00, false, 0, 0 },
            { "draenei", 310, 86, 1.0, 1.10, 0.97, false, 0, 0 },
            { "orc", 316, 88, 1.0, 0.75, 0.94, false, 0, 0 },
            { "nightelf", 314, 91, 1.0, 0.86, 0.84, false, 0, 0 },
            { "scourge", 314, 92, 1.0, 1.27, 0.63, false, 0, 0 },
            { "troll", 314, 94, 1.6, 1.00, 1.00, false, 0, 0 },
            { "gnome", 313, 99, 1.6, 0.91, 0.61, false, 1.0, 0 },
            { "tauren", 316, 95, 1.6, 1.00, 1.00, false, 0, 0 },
            { "goblin", 306, 86, 1.6, 0.98, 0.70, true, 0, 2.0 },
        };
        constexpr double SOURCE_X = 337.5, SOURCE_Y = 89, RADIUS = 9;

        // The settings for [path], if it has any.
        Tweak const* TweakFor(std::string const& path)
        {
            std::string lower = Lower(path);
            for (auto const& tweak : TWEAKS)
            {
                std::string race = tweak.race;
                if (lower.find("character\\" + race + "\\female\\" + race + "femaleskin00_") == 0
                    && lower.find("_extra") == std::string::npos)
                    return &tweak;
            }
            return nullptr;
        }

        // A Gaussian blur of one channel of [im] over the rectangle [x0, x1) x [y0, y1), its edges mirrored.
        void Blur(std::vector<double>& plane, uint32_t width, int x0, int y0, int x1, int y1, double sigma)
        {
            int radius = int(std::ceil(sigma * 4));
            std::vector<double> kernel(2 * radius + 1);
            double sum = 0;
            for (int i = -radius; i <= radius; ++i)
                sum += kernel[i + radius] = std::exp(-0.5 * i * i / (sigma * sigma));
            for (double& k : kernel)
                k /= sum;
            auto mirror = [](int v, int lo, int hi)
            {
                int n = hi - lo;
                v -= lo;
                v = ((v % (2 * n)) + 2 * n) % (2 * n);
                return lo + (v < n ? v : 2 * n - 1 - v);
            };
            std::vector<double> tmp(plane);
            for (int y = y0; y < y1; ++y)
                for (int x = x0; x < x1; ++x)
                {
                    double v = 0;
                    for (int i = -radius; i <= radius; ++i)
                        v += kernel[i + radius] * plane[size_t(y) * width + mirror(x + i, x0, x1)];
                    tmp[size_t(y) * width + x] = v;
                }
            for (int y = y0; y < y1; ++y)
                for (int x = x0; x < x1; ++x)
                {
                    double v = 0;
                    for (int i = -radius; i <= radius; ++i)
                        v += kernel[i + radius] * tmp[size_t(mirror(y + i, y0, y1)) * width + x];
                    plane[size_t(y) * width + x] = v;
                }
        }

        // The shading: the man's texture over its own blur, per channel (and its lightness), around the source point.
        struct Shading
        {
            bool ok = false;
            int x0 = 0, y0 = 0, size = 0;
            std::vector<double> ratio[4];     // r, g, b, lightness

            double At(int c, double x, double y) const
            {
                x -= x0;
                y -= y0;
                int ix = int(std::floor(x)), iy = int(std::floor(y));
                double fx = x - ix, fy = y - iy;
                auto v = [&](int px, int py) { return ratio[c][size_t(std::clamp(py, 0, size - 1)) * size + std::clamp(px, 0, size - 1)]; };
                return v(ix, iy) * (1 - fx) * (1 - fy) + v(ix + 1, iy) * fx * (1 - fy) + v(ix, iy + 1) * (1 - fx) * fy + v(ix + 1, iy + 1) * fx * fy;
            }
        };

        Shading MakeShading(Game const& game)
        {
            Shading s;
            Bytes data;
            Image man;
            if (!game.Read("Character\\BloodElf\\Male\\BloodElfMaleSkin00_00.blp", data) || !Decode(data, man) || man.width != 512 || man.height != 512)
                return s;
            // The source's neighbourhood, blurred as part of the whole texture.
            std::vector<double> planes[4];
            for (int c = 0; c < 4; ++c)
                planes[c].assign(size_t(512) * 512, 0);
            for (size_t i = 0; i < size_t(512) * 512; ++i)
            {
                double r = man.rgba[i * 4] / 255.0, g = man.rgba[i * 4 + 1] / 255.0, b = man.rgba[i * 4 + 2] / 255.0;
                planes[0][i] = r;
                planes[1][i] = g;
                planes[2][i] = b;
                planes[3][i] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
            s.size = 40;
            s.x0 = int(SOURCE_X) - s.size / 2;
            s.y0 = int(SOURCE_Y) - s.size / 2;
            for (int c = 0; c < 4; ++c)
            {
                std::vector<double> blurred = planes[c];
                Blur(blurred, 512, 0, 0, 512, 512, 5);
                s.ratio[c].resize(size_t(s.size) * s.size);
                for (int y = 0; y < s.size; ++y)
                    for (int x = 0; x < s.size; ++x)
                    {
                        size_t i = size_t(s.y0 + y) * 512 + (s.x0 + x);
                        s.ratio[c][size_t(y) * s.size + x] = planes[c][i] / std::max(blurred[i], 1e-3);
                    }
            }
            s.ok = true;
            return s;
        }

        // [blp] retouched with [tweak]'s settings; false when it isn't HD.
        bool Retouch(Bytes& blp, Tweak const& tweak, Shading const& shading, BodyShade const& body, std::string const& path)
        {
            Image im;
            if (!shading.ok || !Decode(blp, im) || im.width != 512 || im.height != 512)
                return false;
            size_t n = size_t(512) * 512;
            std::vector<double> plane[3];
            for (int c = 0; c < 3; ++c)
            {
                plane[c].resize(n);
                for (size_t i = 0; i < n; ++i)
                    plane[c][i] = im.rgba[i * 4 + c] / 255.0;
            }
            if (tweak.smooth > 0)
            {
                // Each region of the texture on its own, so nothing bleeds across a seam.
                static constexpr int REGIONS[][4] = { { 0, 0, 256, 128 }, { 0, 128, 256, 128 }, { 0, 256, 256, 64 }, { 0, 320, 256, 64 },
                    { 0, 384, 256, 128 }, { 256, 0, 256, 128 }, { 256, 128, 256, 64 }, { 256, 192, 256, 128 }, { 256, 320, 256, 128 },
                    { 256, 448, 256, 64 } };
                for (auto const& r : REGIONS)
                    for (int c = 0; c < 3; ++c)
                        Blur(plane[c], 512, r[0], r[1], r[0] + r[2], r[1] + r[3], tweak.smooth);
            }
            int ylo = int(tweak.y - RADIUS * tweak.down - 3), yhi = int(tweak.y + RADIUS * tweak.down + 4);
            int xlo = int(tweak.x - RADIUS * tweak.across - 3), xhi = int(tweak.x + RADIUS * tweak.across + 4);
            for (int y = std::max(0, ylo); y < std::min(512, yhi); ++y)
                for (int x = std::max(0, xlo); x < std::min(512, xhi); ++x)
                {
                    double dx = (x - tweak.x) / tweak.across, dy = (y - tweak.y) / tweak.down;
                    double w = std::clamp((RADIUS - std::sqrt(dx * dx + dy * dy)) / (RADIUS * 0.45), 0.0, 1.0);
                    if (w <= 0)
                        continue;
                    size_t i = size_t(y) * 512 + x;
                    double r[3], in[3], out[3];
                    for (int c = 0; c < 3; ++c)
                    {
                        r[c] = shading.At(tweak.lightOnly ? 3 : c, SOURCE_X + dx, SOURCE_Y + dy);
                        in[c] = plane[c][i];
                        out[c] = std::clamp(in[c] * (1 + (r[c] - 1) * tweak.strength), 0.0, 1.0);
                    }
                    if (tweak.warm > 0)
                    {
                        double dark = std::clamp(1 - (r[0] + r[1] + r[2]) / 3, 0.0, 1.0) * 4;
                        double t = std::min(1.0, tweak.warm * dark);
                        double lum = (0.299 * out[0] + 0.587 * out[1] + 0.114 * out[2]) / 0.80;
                        double warm[3] = { lum, 0.72 * lum, 0.78 * lum };
                        for (int c = 0; c < 3; ++c)
                            out[c] = out[c] * (1 - t) + std::clamp(warm[c], 0.0, 1.0) * t;
                    }
                    for (int c = 0; c < 3; ++c)
                        plane[c][i] = in[c] * (1 - w) + out[c] * w;
                }
            for (int c = 0; c < 3; ++c)
                for (size_t i = 0; i < n; ++i)
                    im.rgba[i * 4 + c] = uint8_t(std::lround(std::clamp(plane[c][i], 0.0, 1.0) * 255));
            body.Apply(path, im);
            return ReplacePixels(blp, im);
        }

        // Every texture the table names that has settings (those the patch made already have them).
        size_t RetouchAll(Dbc const& dbc, Game const& game, Shading const& shading, BodyShade const& body, std::set<std::string>& made, MpqWriter& writer)
        {
            if (dbc.fields < 10 || !shading.ok)
                return 0;
            size_t count = 0;
            for (size_t row = 0; row < dbc.records.size(); ++row)
            {
                if (dbc.Get(row, 2) != 1 || dbc.Get(row, 3) != 0)
                    continue;
                uint32_t at = dbc.Get(row, 4);
                std::string path = at < dbc.strings.size() ? reinterpret_cast<char const*>(&dbc.strings[at]) : "";
                Tweak const* tweak = TweakFor(path);
                Bytes data;
                if (!tweak || made.count(Lower(path)) || !game.Read(path, data) || !Retouch(data, *tweak, shading, body, path))
                    continue;
                writer.Add(path, data);
                made.insert(Lower(path));
                ++count;
            }
            return count;
        }

        std::string StringAt(Dbc const& dbc, size_t row, uint32_t field)
        {
            uint32_t at = dbc.Get(row, field);
            return at < dbc.strings.size() ? reinterpret_cast<char const*>(&dbc.strings[at]) : "";
        }

        // CharSections' section 4 rows stay, with no textures, as the game's own rows for race 20 have;
        // but for the Features' (the first texture is a design's, where MakeFeatures made it: a race
        // whose body files aren't the ones the designs were made for has its design rows empty too).
        void EmptyOverlayRows(Dbc& dbc, std::set<std::string> const& made)
        {
            if (dbc.fields < 10)
                return;
            for (size_t row = 0; row < dbc.records.size(); ++row)
            {
                if (dbc.Get(row, 3) != 4)
                    continue;
                std::string first = Lower(StringAt(dbc, row, 4));
                if (first.find("femalefeature") != std::string::npos && made.count(first))
                    continue;
                for (uint32_t field : { 4u, 5u, 6u })
                    dbc.Set(row, field, dbc.String(""));
            }
        }

        // The Features' textures (data/client/features, tools/art/races/features_export.py): each race's
        // body-hair designs as the pelvis texture the game puts under the legs' armour (the first
        // texture of a CharSections section 4 row: tools/build_data.py gives the rows of a design
        // Character\<Race>\Female\<Race>FemaleFeature<NN>.blp, NN its place in the race's file). A race
        // whose model or body texture isn't the one the designs were made for gets none.
        size_t MakeFeatures(fs::path const& dir, Game const& game, BodyShade const& body, std::set<std::string>& made,
                            MpqWriter& writer, std::vector<std::string>& problems)
        {
            std::istringstream lines(Text(dir / "features.txt"));
            std::vector<std::tuple<std::string, uint32_t, uint32_t>> races;       // name, pelvis width and height
            std::string line, name;
            while (std::getline(lines, line))
            {
                if (!line.empty() && line.back() == '\r')
                    line.pop_back();
                if (line.empty() || line[0] == '#')
                    continue;
                if (line[0] == '[')
                {
                    name = line.substr(1, line.find(']') - 1);
                    continue;
                }
                std::istringstream fields(line);
                std::string key;
                uint32_t w = 0, h = 0;
                fields >> key >> w >> h;
                if (key == "pelvis" && !name.empty() && w && h)
                    races.emplace_back(name, w, h);
            }
            size_t count = 0;
            for (auto const& [race, pw, ph] : races)
            {
                if (!body.Has(race))
                {
                    problems.push_back("Features: the " + race + "'s model isn't the one its designs were made for");
                    continue;
                }
                Bytes skin;
                Image skinImage;
                if (!game.Read("Character\\" + race + "\\Female\\" + race + "FemaleSkin00_00.blp", skin) || !Decode(skin, skinImage)
                    || skinImage.width != pw * 2 || skinImage.height != ph * 4)
                {
                    problems.push_back("Features: the " + race + "'s body texture isn't the size its designs were made for");
                    continue;
                }
                Bytes data = ReadFile(dir / (race + ".bin"));
                auto get = [&data](size_t at) -> uint32_t
                {
                    return at + 4 <= data.size() ? uint32_t(data[at]) | uint32_t(data[at + 1]) << 8 | uint32_t(data[at + 2]) << 16 | uint32_t(data[at + 3]) << 24 : 0;
                };
                if (data.size() < 36 || std::memcmp(data.data(), "FEAT", 4) != 0 || get(4) != 1 || get(8) != pw || get(12) != ph)
                {
                    problems.push_back("Features: " + race + ".bin isn't the designs of a " + std::to_string(pw) + " by " + std::to_string(ph) + " pelvis");
                    continue;
                }
                uint32_t x0 = get(16), y0 = get(20), w = get(24), h = get(28), n = get(32);
                if (uint64_t(x0) + w > pw || uint64_t(y0) + h > ph)
                {
                    problems.push_back("Features: " + race + ".bin's box is outside the pelvis");
                    continue;
                }
                size_t at = 36;
                for (uint32_t d = 0; d < n && at + 8 <= data.size(); ++d)
                {
                    uint32_t size = get(at + 4);
                    at += 8;
                    if (at + size > data.size())
                        break;
                    std::vector<uint8_t> raw(size_t(w) * h * 4);
                    uLongf got = uLongf(raw.size());
                    bool good = uncompress(raw.data(), &got, &data[at], uLong(size)) == Z_OK && got == raw.size();
                    at += size;
                    if (!good)
                    {
                        problems.push_back("Features: a design of " + race + ".bin is cut short");
                        continue;
                    }
                    // Transparent texels take the design's average colour, so the smaller copies of the texture
                    // don't darken its edges.
                    size_t cells = size_t(w) * h;
                    double sum[3] = { 0, 0, 0 }, weight = 0;
                    for (size_t i = 0; i < cells; ++i)
                        for (int c = 0; c < 3; ++c)
                            sum[c] += double(raw[cells + i * 3 + c]) * raw[i];
                    for (size_t i = 0; i < cells; ++i)
                        weight += raw[i];
                    Image out;
                    out.width = pw;
                    out.height = ph;
                    out.rgba.assign(size_t(pw) * ph * 4, 0);
                    for (size_t i = 0; i < size_t(pw) * ph; ++i)
                        for (int c = 0; c < 3; ++c)
                            out.rgba[i * 4 + c] = weight > 0 ? uint8_t(std::lround(sum[c] / weight)) : 40;
                    for (uint32_t y = 0; y < h; ++y)
                        for (uint32_t x = 0; x < w; ++x)
                        {
                            size_t from = size_t(y) * w + x, to = (size_t(y0 + y) * pw + x0 + x) * 4;
                            if (raw[from])
                            {
                                for (int c = 0; c < 3; ++c)
                                    out.rgba[to + c] = raw[cells + from * 3 + c];
                                out.rgba[to + 3] = raw[from];
                            }
                        }
                    char number[8];
                    std::snprintf(number, sizeof(number), "%02u", d + 1);
                    std::string path = "Character\\" + race + "\\Female\\" + race + "FemaleFeature" + number + ".blp";
                    writer.Add(path, EncodeBlp(out, true));
                    made.insert(Lower(path));
                    ++count;
                }
            }
            return count;
        }

        // A skin the game gives only its NPCs (the face painted into it) has no face or overlay rows:
        // the game always draws such an NPC from a texture baked beforehand. A look the game puts
        // together itself (Wrath Remix's Moratari in her dress and in her tent: her own skin, no baked
        // texture) asks for those rows, and the game fails without them. Every NPC skin gets a face
        // row for each face its race and sex have, and an overlay row, with no textures (as the
        // overlays' are): the skin's own face shows.
        // The designs' skin colours (tools/build_data.py) come after the highest colour of the server's
        // tables, whose NPC and death knight skins some HD looks' tables haven't: there, colours below
        // the designs had no row at all (Night Elf women's 12, Undead's 6 to 9, Gnome's 10 and 11, the
        // Skyborne's 10 to 15), and the character creation screen, as the race changed, read a missing
        // row's texture and crashed (crash reports #44, #45, #47). Every colour below a race and sex's
        // highest gets a row: an NPC's skin (flag 8, as the game's own colours between), its first
        // player skin's textures; CompleteSkins gives it its faces and overlay.
        void FillSkinHoles(Dbc& dbc)
        {
            if (dbc.fields < 10 || dbc.size != dbc.fields * 4)
                return;
            using Who = std::pair<uint32_t, uint32_t>;                      // race, sex
            std::map<Who, std::set<uint32_t>> colours;
            std::map<Who, std::pair<uint32_t, size_t>> first;               // its lowest player skin: colour, row
            uint32_t next = 0;
            for (size_t row = 0; row < dbc.records.size(); ++row)
            {
                next = std::max(next, dbc.Get(row, 0) + 1);
                if (dbc.Get(row, 3) != 0)
                    continue;
                Who who{ dbc.Get(row, 1), dbc.Get(row, 2) };
                uint32_t colour = dbc.Get(row, 9), flags = dbc.Get(row, 7);
                colours[who].insert(colour);
                if ((flags & 1) && !(flags & 4) && (!first.count(who) || colour < first[who].first))
                    first[who] = { colour, row };
            }
            for (auto const& [who, has] : colours)
            {
                auto source = first.find(who);
                if (source == first.end() || has.empty())
                    continue;
                for (uint32_t colour = 0; colour < *has.rbegin(); ++colour)
                {
                    if (has.count(colour))
                        continue;
                    Bytes record = dbc.records[source->second.second];
                    dbc.records.push_back(record);
                    size_t row = dbc.records.size() - 1;
                    dbc.Set(row, 0, next++);
                    dbc.Set(row, 7, 8);
                    dbc.Set(row, 8, 0);
                    dbc.Set(row, 9, colour);
                }
            }
        }

        void CompleteSkins(Dbc& dbc)
        {
            if (dbc.fields < 10 || dbc.size != dbc.fields * 4)
                return;
            using Who = std::pair<uint32_t, uint32_t>;                      // race, sex
            using Colour = std::tuple<uint32_t, uint32_t, uint32_t>;        // race, sex, skin colour
            std::map<Who, std::set<uint32_t>> faces;
            std::set<Who> overlays;
            std::map<Colour, std::set<uint32_t>> facesOf;
            std::set<Colour> overlaid;
            std::map<Colour, uint32_t> skins;                               // a skin colour: its row's flags
            uint32_t next = 0;
            for (size_t row = 0; row < dbc.records.size(); ++row)
            {
                uint32_t race = dbc.Get(row, 1), sex = dbc.Get(row, 2), section = dbc.Get(row, 3);
                uint32_t variation = dbc.Get(row, 8), colour = dbc.Get(row, 9);
                next = std::max(next, dbc.Get(row, 0) + 1);
                if (section == 0)
                    skins[{ race, sex, colour }] = dbc.Get(row, 7);
                else if (section == 1)
                {
                    faces[{ race, sex }].insert(variation);
                    facesOf[{ race, sex, colour }].insert(variation);
                }
                else if (section == 4)
                {
                    overlays.insert({ race, sex });
                    overlaid.insert({ race, sex, colour });
                }
            }
            uint32_t none = dbc.String("");
            auto add = [&](uint32_t race, uint32_t sex, uint32_t section, uint32_t flags, uint32_t variation, uint32_t colour)
            {
                dbc.records.emplace_back(dbc.size, 0);
                size_t row = dbc.records.size() - 1;
                for (uint32_t field : { 4u, 5u, 6u })
                    dbc.Set(row, field, none);
                dbc.Set(row, 0, next++);
                dbc.Set(row, 1, race);
                dbc.Set(row, 2, sex);
                dbc.Set(row, 3, section);
                dbc.Set(row, 7, flags);
                dbc.Set(row, 8, variation);
                dbc.Set(row, 9, colour);
            };
            for (auto const& [skin, flags] : skins)
            {
                // (only the NPCs': a skin a player can choose, flag 1, keeps the faces it has)
                if (flags & 1)
                    continue;
                auto [race, sex, colour] = skin;
                std::set<uint32_t> const& has = facesOf[skin];
                for (uint32_t face : faces[{ race, sex }])
                    if (!has.count(face))
                        add(race, sex, 1, flags, face, colour);
                if (overlays.count({ race, sex }) && !overlaid.count(skin))
                    add(race, sex, 4, flags, 0, colour);
            }
        }

        // HD looks (blood elves' among them) give a skin a second texture, <skin>_Extra.blp, with the
        // ears, jaw and eyes: CharSections' second texture of the skin's row. The rows put here come
        // from the server's tables, which haven't it: a skin row without one gets its _Extra where the
        // game or the patch has it, or the ears and eyes keep another skin's colours.
        void AddExtraSkins(Dbc& dbc, Game const& game, std::set<std::string> const& made)
        {
            if (dbc.fields < 10)
                return;
            auto text = [&](uint32_t at) { return at < dbc.strings.size() ? std::string(reinterpret_cast<char const*>(&dbc.strings[at])) : std::string(); };
            for (size_t row = 0; row < dbc.records.size(); ++row)
            {
                if (dbc.Get(row, 3) != 0 || !text(dbc.Get(row, 5)).empty())
                    continue;
                std::string skin = text(dbc.Get(row, 4));
                std::string extra = Extra(skin);
                if (extra.empty())
                    continue;
                Bytes data;
                if (made.count(Lower(extra)) || game.Read(extra, data))
                    dbc.Set(row, 5, dbc.String(extra));
            }
        }
    }

    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        if (!fs::exists(clientData / MARK, error))
            return;
        fs::remove(clientData / PATCH, error);
        fs::remove(clientData / MARK, error);
        log("The Skyborne's patch is out of the game");
    }

    bool UpdatePatch(fs::path const& moduleData, fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        fs::path patch = clientData / PATCH, mark = clientData / MARK;
        bool ours = fs::exists(mark, error);
        if (!ours && fs::exists(patch, error))
        {
            log(patch.string() + " isn't the Skyborne's: left alone, so the game won't know them");
            return false;
        }
        auto start = std::chrono::steady_clock::now();
        Game game(clientData, { "patch-z.mpq", "patch-y.mpq" });
        if (game.archives.empty())
        {
            log("No game in " + clientData.string());
            return false;
        }

        // What it's made from: the module's files and the game's archives.
        Stamp stamp;
        stamp.Add(VERSION);
        stamp.Add(game.Describe());
        std::vector<fs::path> files;
        for (auto it = fs::recursive_directory_iterator(moduleData, error); !error && it != fs::recursive_directory_iterator(); it.increment(error))
            if (it->is_regular_file(error))
                files.push_back(it->path());
        std::sort(files.begin(), files.end());
        for (auto const& f : files)
        {
            stamp.Add(f.lexically_relative(moduleData).generic_string());
            stamp.Add(Text(f));
        }
        if (ours && fs::exists(patch, error) && Text(mark) == stamp.Hex() + "\n")
            return true;

        log("Making the Skyborne's patch for the game (" + patch.string() + ")");
        fs::path part = patch;
        part += ".part";
        MpqWriter writer(part);
        size_t tables = 0, textures = 0, missing = 0, bad = 0;
        std::vector<std::string> problems;

        // Textures (first: the tables name the ones made).
        std::set<std::string> made;
        Shading shading = MakeShading(game);
        BodyShade body;
        body.Load(moduleData / "shade", game, log);
        std::istringstream recipes(Text(moduleData / "textures.txt"));
        std::string line;
        while (std::getline(recipes, line))
        {
            if (!line.empty() && line.back() == '\r')
                line.pop_back();
            if (line.empty() || line[0] == '#')
                continue;
            std::vector<std::string> f;
            std::istringstream fields(line);
            for (std::string part2; std::getline(fields, part2, '\t');)
                f.push_back(part2);
            Recipe recipe;
            if (f.size() != 4 || !ParseRecipe(f[0], f[1], recipe))
            {
                ++bad;
                continue;
            }
            for (bool extra : { false, true })
            {
                std::string from = extra ? Extra(f[2]) : f[2], to = extra ? Extra(f[3]) : f[3];
                Bytes data;
                if (!game.Read(from, data))
                {
                    if (!extra)
                        ++missing;
                    continue;
                }
                if (!extra)
                    if (Tweak const* tweak = TweakFor(from))
                        Retouch(data, *tweak, shading, body, from);
                if (!Recolor(data, recipe))
                {
                    ++bad;
                    continue;
                }
                writer.Add(to, data);
                made.insert(Lower(to));
                ++textures;
            }
        }

        // The Features' pelvis textures (the tables' section 4 rows name them).
        textures += MakeFeatures(moduleData / "features", game, body, made, writer, problems);

        // Tables.
        for (auto it = fs::directory_iterator(moduleData / "dbc", error); !error && it != fs::directory_iterator(); it.increment(error))
        {
            std::string name = it->path().stem().string();
            Bytes data;
            Dbc dbc;
            if (!game.Read("DBFilesClient\\" + name + ".dbc", data) || !dbc.Parse(data))
            {
                problems.push_back(name + ".dbc isn't in the game");
                continue;
            }
            std::string problem = ApplyChanges(dbc, Text(it->path()));
            if (!problem.empty())
            {
                problems.push_back(name + ".dbc: " + problem);
                continue;
            }
            if (Lower(name) == "charsections")
                AddExtraSkins(dbc, game, made);
            if (Lower(name) == "charsections")
                EmptyOverlayRows(dbc, made);
            if (Lower(name) == "charsections")
                FillSkinHoles(dbc);
            if (Lower(name) == "charsections")
                CompleteSkins(dbc);
            if (Lower(name) == "charsections")
                textures += RetouchAll(dbc, game, shading, body, made, writer);
            writer.Add("DBFilesClient\\" + name + ".dbc", dbc.Write());
            ++tables;
        }

        // The character creation screen: the race icons, the screen's own file list with theirs.
        for (char const* icons : { "Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-Races.blp",
                                   "Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-RacesRound.blp" })
        {
            Bytes data;
            if (game.Read(icons, data) && SkyborneIcons(data))
                writer.Add(icons, data);
            else
                problems.push_back(std::string(icons) + ": no race icons to add theirs to");
        }
        Bytes toc;
        if (game.Read("Interface\\GlueXML\\GlueXML.toc", toc))
        {
            std::string text(toc.begin(), toc.end());
            if (text.find("SkyborneCreate.xml") == std::string::npos)
            {
                if (!text.empty() && text.back() != '\n')
                    text += "\r\n";
                text += "SkyborneCreate.xml\r\nSkyborneCreate.lua\r\n";
            }
            // The gear button, after the race and class buttons it sits by: its data, the button, its code.
            if (text.find("SkyborneGear.xml") == std::string::npos)
            {
                if (!text.empty() && text.back() != '\n')
                    text += "\r\n";
                text += "SkyborneGearData.lua\r\nSkyborneGear.xml\r\nSkyborneGear.lua\r\n";
            }
            // The preview's zoom (the mouse wheel: the app's pinch).
            if (text.find("SkyborneZoom.lua") == std::string::npos)
            {
                if (!text.empty() && text.back() != '\n')
                    text += "\r\n";
                text += "SkyborneZoom.lua\r\n";
            }
            // The Features row (the body-hair designs): how many skins a race has, the row, its code.
            if (text.find("SkyborneFeatures.xml") == std::string::npos)
            {
                if (!text.empty() && text.back() != '\n')
                    text += "\r\n";
                text += "SkyborneFeaturesData.lua\r\nSkyborneFeatures.xml\r\nSkyborneFeatures.lua\r\n";
            }
            writer.Add("Interface\\GlueXML\\GlueXML.toc", Bytes(text.begin(), text.end()));
            for (char const* name : { "SkyborneCreate.xml", "SkyborneCreate.lua", "SkyborneGearData.lua", "SkyborneGear.xml", "SkyborneGear.lua",
                                      "SkyborneZoom.lua", "SkyborneFeaturesData.lua", "SkyborneFeatures.xml", "SkyborneFeatures.lua" })
                writer.Add(std::string("Interface\\GlueXML\\") + name, ReadFile(moduleData / "Interface" / "GlueXML" / name));
        }
        else
            problems.push_back("GlueXML.toc isn't in the game: the character screen won't show them");
        // The intro's narration (the user's, data/client/intro.mp3), where the game's sound table has it.
        writer.Add("Sound\\CinematicVoices\\SkyborneNarration.mp3", ReadFile(moduleData / "intro.mp3"));

        if (!writer.Finish())
        {
            fs::remove(part, error);
            log("Couldn't write " + part.string());
            return false;
        }
        fs::rename(part, patch, error);
        if (error)
        {
            fs::remove(part, error);
            log("Couldn't put " + patch.string() + " in place: " + error.message());
            return false;
        }
        {
            std::ofstream out(mark, std::ios::trunc);
            out << stamp.Hex() << "\n";
        }
        for (auto const& p : problems)
            log("  " + p);
        auto seconds = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - start).count();
        log("The Skyborne's patch is in the game: " + std::to_string(tables) + " tables, " + std::to_string(textures)
            + " textures" + (missing ? ", " + std::to_string(missing) + " the game hasn't" : "")
            + (bad ? ", " + std::to_string(bad) + " that couldn't be made" : "") + " (" + std::to_string(seconds) + " s)");
        return true;
    }
}
