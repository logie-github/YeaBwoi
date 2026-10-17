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
#include <chrono>
#include <cstdio>
#include <sstream>

namespace Skyborne
{
    namespace
    {
        constexpr char const* VERSION = "skyborne-1";

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
            stamp.Add(fs::relative(f, moduleData, error).generic_string());
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
            writer.Add("DBFilesClient\\" + name + ".dbc", dbc.Write());
            ++tables;
        }

        // Textures.
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
                if (!Recolor(data, recipe))
                {
                    ++bad;
                    continue;
                }
                writer.Add(to, data);
                ++textures;
            }
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
            writer.Add("Interface\\GlueXML\\GlueXML.toc", Bytes(text.begin(), text.end()));
            for (char const* name : { "SkyborneCreate.xml", "SkyborneCreate.lua" })
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
