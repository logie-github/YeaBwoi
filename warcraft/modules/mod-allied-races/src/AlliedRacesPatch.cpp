// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The void elves' and the nightborne's patch for the game (mod-allied-races). The game needs
 * its tables to know the two races (ChrRaces, CharSections, CharHairGeosets... with their rows:
 * the module's data/client/dbc, made by tools/build_data.py), their models, textures and voices
 * (Blizzard's own of today, made 3.3.5's by tools/package.py as the server was packaged: never in
 * this repository), their place on the character creation screen (Interface/GlueXML/
 * AlliedRacesCreate.*, their portraits in the race icons) and their intros' narration.
 *
 * It's Data/patch-V.MPQ: read after the HD looks' patches (whose character tables it builds on)
 * and before Wrath Remix's patch-Y.MPQ and the Skyborne's patch-Z.MPQ, which read the client with
 * this patch in it and so carry its rows in the tables they write (CharSections among them).
 *
 * The archive reading and writing, the tables and the textures are the Skyborne's (SkyborneFiles).
 */

#include "AlliedRacesPatch.h"
#include "../../mod-skyborne/src/SkyborneFiles.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <set>
#include <sstream>

namespace AlliedRaces
{
    using Skyborne::Bytes;
    using Skyborne::Dbc;
    using Skyborne::Game;
    using Skyborne::Image;
    using Skyborne::MpqWriter;

    namespace
    {
        constexpr char const* PATCH = "patch-V.MPQ";
        constexpr char const* MARK = "patch-V.MPQ.alliedraces";
        constexpr char const* MANIFEST = "alliedraces.files";
        constexpr char const* VERSION = "allied-races-2";   // 2: the models' bone combos name the vertices' bones

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

        fs::path LocalPath(fs::path const& root, std::string const& gamePath)
        {
            std::string p = gamePath;
            std::replace(p.begin(), p.end(), '\\', '/');
            return root / p;
        }

        // The portrait [icon] (any size) into cell ([column], [row]) of the icon sheet [sheet]
        // (8 a row, 4 rows), scaled with bilinear sampling.
        void Paint(Image& sheet, Image const& icon, uint32_t column, uint32_t row)
        {
            uint32_t cell = sheet.width / 8;
            for (uint32_t y = 0; y < cell; ++y)
                for (uint32_t x = 0; x < cell; ++x)
                {
                    double sx = (x + 0.5) * icon.width / cell - 0.5, sy = (y + 0.5) * icon.height / cell - 0.5;
                    int x0 = int(std::floor(sx)), y0 = int(std::floor(sy));
                    double fx = sx - x0, fy = sy - y0;
                    auto at = [&](int px, int py, int c) -> double
                    {
                        px = std::clamp(px, 0, int(icon.width) - 1);
                        py = std::clamp(py, 0, int(icon.height) - 1);
                        return icon.rgba[(size_t(py) * icon.width + px) * 4 + c];
                    };
                    uint8_t* out = &sheet.rgba[(size_t(row * cell + y) * sheet.width + column * cell + x) * 4];
                    for (int c = 0; c < 3; ++c)
                    {
                        double v = at(x0, y0, c) * (1 - fx) * (1 - fy) + at(x0 + 1, y0, c) * fx * (1 - fy)
                            + at(x0, y0 + 1, c) * (1 - fx) * fy + at(x0 + 1, y0 + 1, c) * fx * fy;
                        out[c] = uint8_t(std::clamp(v + 0.5, 0.0, 255.0));
                    }
                    out[3] = 255;
                }
        }

        // The races' portraits painted into the last column of the game's icon sheet: void elves
        // in the Alliance's rows (0: men, 2: women), nightborne in the Horde's (1, 3).
        bool AlliedIcons(Bytes& blp, fs::path const& icons, std::vector<std::string>& problems)
        {
            Image sheet;
            if (!Skyborne::Decode(blp, sheet) || sheet.width < 64 || sheet.height < 32 || sheet.width != sheet.height * 2)
                return false;
            struct Portrait { char const* file; uint32_t row; };
            for (Portrait const& p : { Portrait{ "voidelf_male.blp", 0 }, Portrait{ "nightborne_male.blp", 1 },
                                       Portrait{ "voidelf_female.blp", 2 }, Portrait{ "nightborne_female.blp", 3 } })
            {
                Image icon;
                if (!Skyborne::Decode(ReadFile(icons / p.file), icon))
                {
                    problems.push_back(std::string("the portrait ") + p.file + " couldn't be read");
                    continue;
                }
                Paint(sheet, icon, 7, p.row);
            }
            blp = Skyborne::EncodeDxt5(sheet);
            return true;
        }
    }

    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        if (!fs::exists(clientData / MARK, error))
            return;
        fs::remove(clientData / PATCH, error);
        fs::remove(clientData / MARK, error);
        log("The races' patch is out of the game");
    }

    bool UpdatePatch(fs::path const& moduleData, fs::path const& files, fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        fs::path patch = clientData / PATCH, mark = clientData / MARK;
        bool ours = fs::exists(mark, error);
        if (!ours && fs::exists(patch, error))
        {
            log(patch.string() + " isn't this module's: left alone, so the game won't know the races");
            return false;
        }
        auto start = std::chrono::steady_clock::now();
        Game game(clientData, { "patch-v.mpq", "patch-y.mpq", "patch-z.mpq" });
        if (game.archives.empty())
        {
            log("No game in " + clientData.string());
            return false;
        }
        std::string manifest = Text(files / MANIFEST);
        if (manifest.empty())
        {
            log("The races' models, textures and voices aren't in " + files.string() + " (no " + MANIFEST + "): the patch isn't made");
            RemovePatch(clientData, log);
            return false;
        }

        // What it's made from: the module's files, the package's (by name and size) and the game's archives.
        Stamp stamp;
        stamp.Add(VERSION);
        stamp.Add(game.Describe());
        std::vector<fs::path> moduleFiles;
        for (auto it = fs::recursive_directory_iterator(moduleData, error); !error && it != fs::recursive_directory_iterator(); it.increment(error))
            if (it->is_regular_file(error))
                moduleFiles.push_back(it->path());
        std::sort(moduleFiles.begin(), moduleFiles.end());
        for (auto const& f : moduleFiles)
        {
            stamp.Add(f.lexically_relative(moduleData).generic_string());
            stamp.Add(Text(f));
        }
        std::vector<std::string> listed;
        {
            std::istringstream in(manifest);
            std::string line;
            while (std::getline(in, line))
            {
                if (!line.empty() && line.back() == '\r')
                    line.pop_back();
                if (!line.empty())
                    listed.push_back(line);
            }
        }
        for (std::string const& name : listed)
        {
            stamp.Add(name);
            stamp.Add(std::to_string(fs::file_size(LocalPath(files, name), error)));
        }
        if (ours && fs::exists(patch, error) && Text(mark) == stamp.Hex() + "\n")
            return true;

        log("Making the races' patch for the game (" + patch.string() + ")");
        fs::path part = patch;
        part += ".part";
        MpqWriter writer(part);
        size_t tables = 0, packed = 0, missing = 0;
        std::vector<std::string> problems;

        // The races' files: models, textures, voices.
        for (std::string const& name : listed)
        {
            Bytes data = ReadFile(LocalPath(files, name));
            if (data.empty())
            {
                ++missing;
                continue;
            }
            writer.Add(name, data);
            ++packed;
        }
        if (missing)
            problems.push_back(std::to_string(missing) + " of the races' files aren't in " + files.string());

        // Tables.
        for (auto it = fs::directory_iterator(moduleData / "dbc", error); !error && it != fs::directory_iterator(); it.increment(error))
        {
            if (it->path().extension() != ".txt")
                continue;
            std::string name = it->path().stem().string();
            Bytes data;
            Dbc dbc;
            if (!game.Read("DBFilesClient\\" + name + ".dbc", data) || !dbc.Parse(data))
            {
                problems.push_back(name + ".dbc isn't in the game");
                continue;
            }
            std::string problem = Skyborne::ApplyChanges(dbc, Text(it->path()));
            if (!problem.empty())
            {
                problems.push_back(name + ".dbc: " + problem);
                continue;
            }
            writer.Add("DBFilesClient\\" + name + ".dbc", dbc.Write());
            ++tables;
        }

        // The character creation screen: the race icons, the screen's own file list with theirs.
        for (char const* icons : { "Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-Races.blp",
                                   "Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-RacesRound.blp" })
        {
            Bytes data;
            if (game.Read(icons, data) && AlliedIcons(data, moduleData / "icons", problems))
                writer.Add(icons, data);
            else
                problems.push_back(std::string(icons) + ": no race icons to add theirs to");
        }
        Bytes toc;
        if (game.Read("Interface\\GlueXML\\GlueXML.toc", toc))
        {
            std::string text(toc.begin(), toc.end());
            if (text.find("AlliedRacesCreate.xml") == std::string::npos)
            {
                if (!text.empty() && text.back() != '\n')
                    text += "\r\n";
                text += "AlliedRacesCreate.xml\r\nAlliedRacesCreate.lua\r\n";
            }
            writer.Add("Interface\\GlueXML\\GlueXML.toc", Bytes(text.begin(), text.end()));
            for (char const* name : { "AlliedRacesCreate.xml", "AlliedRacesCreate.lua" })
                writer.Add(std::string("Interface\\GlueXML\\") + name, ReadFile(moduleData / "Interface" / "GlueXML" / name));
        }
        else
            problems.push_back("GlueXML.toc isn't in the game: the character screen won't show them");
        // The intros' narration (the user's, data/client/*_intro.mp3), where the game's sound table has it.
        writer.Add("Sound\\CinematicVoices\\VoidElfNarration.mp3", ReadFile(moduleData / "voidelf_intro.mp3"));
        writer.Add("Sound\\CinematicVoices\\NightborneNarration.mp3", ReadFile(moduleData / "nightborne_intro.mp3"));

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
        log("The races' patch is in the game: " + std::to_string(tables) + " tables, " + std::to_string(packed) + " files ("
            + std::to_string(seconds) + " s)");
        return true;
    }
}
