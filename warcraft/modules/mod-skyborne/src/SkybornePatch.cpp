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
#include <algorithm>
#include <cctype>
#include <cmath>
#include <chrono>
#include <set>
#include <cstdio>
#include <sstream>

namespace Skyborne
{
    namespace
    {
        constexpr char const* VERSION = "skyborne-6";

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

        // Female body textures (HD, 512 by 512): each race's settings, the shading taken from the
        // blood elf man's texture around (337.5, 89).
        struct BodySpot
        {
            char const* race;
            double x, y, strength, across, down;
            bool lightOnly;     // shading in the skin's own colour
            double pink, smooth;
        };
        constexpr BodySpot BODY_SPOTS[] = {
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

        // The settings for [path] when it's a race's female body texture (...\<Race>\Female\<Race>FemaleSkin00_NN.blp).
        BodySpot const* BodySpotFor(std::string const& path)
        {
            std::string lower = Lower(path);
            for (auto const& spot : BODY_SPOTS)
            {
                std::string race = spot.race;
                if (lower.find("character\\" + race + "\\female\\" + race + "femaleskin00_") == 0
                    && lower.find("_extra") == std::string::npos)
                    return &spot;
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

        // The shading: the man's texture over its own blur, per channel (and its lightness), around the source spot.
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
            // The blur reaches 20 texels: the spot's neighbourhood blurred as part of the whole texture.
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

        // [blp] (a female body texture, [spot]'s race) with the details added; false when it isn't HD.
        bool AddBodyDetails(Bytes& blp, BodySpot const& spot, Shading const& shading)
        {
            Image im;
            if (!shading.ok || !Decode(blp, im) || im.width != 512 || im.height != 512)
                return false;
            size_t n = size_t(512) * 512;
            std::vector<double> plane[3];
            bool alpha = false;
            for (int c = 0; c < 3; ++c)
            {
                plane[c].resize(n);
                for (size_t i = 0; i < n; ++i)
                    plane[c][i] = im.rgba[i * 4 + c] / 255.0;
            }
            for (size_t i = 0; i < n; ++i)
                alpha = alpha || im.rgba[i * 4 + 3] != 255;
            if (spot.smooth > 0)
            {
                // Each region of the body texture on its own, so nothing bleeds across a seam.
                static constexpr int REGIONS[][4] = { { 0, 0, 256, 128 }, { 0, 128, 256, 128 }, { 0, 256, 256, 64 }, { 0, 320, 256, 64 },
                    { 0, 384, 256, 128 }, { 256, 0, 256, 128 }, { 256, 128, 256, 64 }, { 256, 192, 256, 128 }, { 256, 320, 256, 128 },
                    { 256, 448, 256, 64 } };
                for (auto const& r : REGIONS)
                    for (int c = 0; c < 3; ++c)
                        Blur(plane[c], 512, r[0], r[1], r[0] + r[2], r[1] + r[3], spot.smooth);
            }
            int ylo = int(spot.y - RADIUS * spot.down - 3), yhi = int(spot.y + RADIUS * spot.down + 4);
            int xlo = int(spot.x - RADIUS * spot.across - 3), xhi = int(spot.x + RADIUS * spot.across + 4);
            for (int y = std::max(0, ylo); y < std::min(512, yhi); ++y)
                for (int x = std::max(0, xlo); x < std::min(512, xhi); ++x)
                {
                    double dx = (x - spot.x) / spot.across, dy = (y - spot.y) / spot.down;
                    double w = std::clamp((RADIUS - std::sqrt(dx * dx + dy * dy)) / (RADIUS * 0.45), 0.0, 1.0);
                    if (w <= 0)
                        continue;
                    size_t i = size_t(y) * 512 + x;
                    double r[3], in[3], out[3];
                    for (int c = 0; c < 3; ++c)
                    {
                        r[c] = shading.At(spot.lightOnly ? 3 : c, SOURCE_X + dx, SOURCE_Y + dy);
                        in[c] = plane[c][i];
                        out[c] = std::clamp(in[c] * (1 + (r[c] - 1) * spot.strength), 0.0, 1.0);
                    }
                    if (spot.pink > 0)
                    {
                        double dark = std::clamp(1 - (r[0] + r[1] + r[2]) / 3, 0.0, 1.0) * 4;
                        double t = std::min(1.0, spot.pink * dark);
                        double lum = (0.299 * out[0] + 0.587 * out[1] + 0.114 * out[2]) / 0.80;
                        double pink[3] = { lum, 0.72 * lum, 0.78 * lum };
                        for (int c = 0; c < 3; ++c)
                            out[c] = out[c] * (1 - t) + std::clamp(pink[c], 0.0, 1.0) * t;
                    }
                    for (int c = 0; c < 3; ++c)
                        plane[c][i] = in[c] * (1 - w) + out[c] * w;
                }
            for (int c = 0; c < 3; ++c)
                for (size_t i = 0; i < n; ++i)
                    im.rgba[i * 4 + c] = uint8_t(std::lround(std::clamp(plane[c][i], 0.0, 1.0) * 255));
            blp = EncodeBlp(im, alpha);
            return true;
        }

        // Every female body texture the table names, for the races above, with the details (those
        // the patch made already have them).
        size_t BodyTextures(Dbc const& dbc, Game const& game, Shading const& shading, std::set<std::string>& made, MpqWriter& writer)
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
                BodySpot const* spot = BodySpotFor(path);
                Bytes data;
                if (!spot || made.count(Lower(path)) || !game.Read(path, data) || !AddBodyDetails(data, *spot, shading))
                    continue;
                writer.Add(path, data);
                made.insert(Lower(path));
                ++count;
            }
            return count;
        }

        // No race or sex wears the underwear the game paints over a bare body (CharSections'
        // section 4, NakedPelvisSkin/NakedTorsoSkin): its rows stay, with no textures, as the
        // game's own rows for race 20 have; the body's skin shows.
        void NoUnderwear(Dbc& dbc)
        {
            if (dbc.fields < 10)
                return;
            for (size_t row = 0; row < dbc.records.size(); ++row)
                if (dbc.Get(row, 3) == 4)
                    for (uint32_t field : { 4u, 5u, 6u })
                        dbc.Set(row, field, dbc.String(""));
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
                    if (BodySpot const* spot = BodySpotFor(from))
                        AddBodyDetails(data, *spot, shading);
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
                NoUnderwear(dbc);
            if (Lower(name) == "charsections")
                textures += BodyTextures(dbc, game, shading, made, writer);
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
            writer.Add("Interface\\GlueXML\\GlueXML.toc", Bytes(text.begin(), text.end()));
            for (char const* name : { "SkyborneCreate.xml", "SkyborneCreate.lua", "SkyborneGearData.lua", "SkyborneGear.xml", "SkyborneGear.lua" })
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
