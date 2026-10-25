// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Worgen's and Goblins' patch for the game (mod-worgen-goblin): mod-worgoblin's models,
 * textures, sounds and icons (its files, laid out by tools/package.py as the server was packaged:
 * never in this repository), the game's own tables with what mod-worgoblin changes in them put in
 * (data/client/dbc, tools/build_data.py: its rows added, its races' bits OR'd in, the game's own
 * goblin and fel orc creatures moved out of the two races' places first), and the character
 * creation screen's script (data/client/Interface/GlueXML/WorgenGoblinCreate.lua) with its texts.
 *
 * It's Data/patch-U.MPQ: read after the HD looks' patches (whose tables it builds on) and before the
 * allied races' patch-V.MPQ, Wrath Remix's patch-Y.MPQ and the Skyborne's patch-Z.MPQ, which are made
 * from the client with this patch in it and so carry its rows on (made first: WorgenGoblin.cpp, as
 * the configuration is about to be read; the allied races' is made once it's read).
 *
 * The archive reading and writing and the tables are the Skyborne's (SkyborneFiles).
 */

#include "WorgenGoblinPatch.h"
#include "../../mod-skyborne/src/SkyborneFiles.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <sstream>

namespace WorgenGoblin
{
    using Skyborne::Bytes;
    using Skyborne::Dbc;
    using Skyborne::Game;
    using Skyborne::MpqWriter;

    namespace
    {
        constexpr char const* PATCH = "patch-U.MPQ";
        constexpr char const* MARK = "patch-U.MPQ.worgengoblin";
        constexpr char const* ASIDE = "patch-U.MPQ.aside";
        constexpr char const* MANIFEST = "worgengoblin.files";
        constexpr char const* VERSION = "worgen-goblin-1";
        // The character screen's files in the game's GlueXML.toc, in this order: the races' texts
        // (mod-worgoblin's, from its files), the extra race buttons, then the script.
        constexpr char const* STRINGS = "WorgenGoblinStrings.lua";
        constexpr char const* BUTTONS = "WorgenGoblinCreate.xml";
        constexpr char const* SCRIPT = "WorgenGoblinCreate.lua";

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
    }

    std::vector<std::string> Statements(std::string const& sql)
    {
        std::vector<std::string> out;
        std::istringstream in(sql);
        std::string line, current;
        while (std::getline(in, line))
        {
            if (!line.empty() && line.back() == '\r')
                line.pop_back();
            if (current.empty() && (line.empty() || line.rfind("--", 0) == 0))
                continue;
            current += line;
            current += '\n';
            if (!line.empty() && line.back() == ';')
            {
                out.push_back(current);
                current.clear();
            }
        }
        if (current.find_first_not_of(" \t\r\n") != std::string::npos)
            out.push_back(current);
        return out;
    }

    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        if (!fs::exists(clientData / MARK, error))
            return;
        fs::remove(clientData / PATCH, error);
        fs::remove(clientData / ASIDE, error);
        fs::remove(clientData / MARK, error);
        log("The Worgen's and Goblins' patch is out of the game");
    }

    void SetPatchAside(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        if (!fs::exists(clientData / MARK, error) || !fs::exists(clientData / PATCH, error))
            return;
        fs::remove(clientData / ASIDE, error);
        fs::rename(clientData / PATCH, clientData / ASIDE, error);
        if (error)
            log("The Worgen's and Goblins' patch couldn't be set aside: " + error.message());
        else
            log("The Worgen's and Goblins' patch is out of the game, kept beside it for a realm with them");
    }

    bool UpdatePatch(fs::path const& moduleData, fs::path const& files, fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        fs::path patch = clientData / PATCH, mark = clientData / MARK;
        bool ours = fs::exists(mark, error);
        if (!ours && fs::exists(patch, error))
        {
            log(patch.string() + " isn't this module's: left alone, so the game won't know the Worgen and Goblins");
            return false;
        }
        if (ours && !fs::exists(patch, error) && fs::exists(clientData / ASIDE, error))
            fs::rename(clientData / ASIDE, patch, error);
        auto start = std::chrono::steady_clock::now();
        // (the game's tables as the game reads them before this patch: without it and the patches
        // made from the game with it in)
        Game game(clientData, { "patch-u.mpq", "patch-v.mpq", "patch-y.mpq", "patch-z.mpq", "patch-enus-y.mpq", "patch-engb-y.mpq",
            "patch-dede-y.mpq", "patch-frfr-y.mpq", "patch-eses-y.mpq", "patch-esmx-y.mpq", "patch-ruru-y.mpq", "patch-kokr-y.mpq",
            "patch-zhcn-y.mpq", "patch-zhtw-y.mpq" });
        if (game.archives.empty())
        {
            log("No game in " + clientData.string());
            return false;
        }
        std::string manifest = Text(files / MANIFEST);
        if (manifest.empty())
        {
            log("mod-worgoblin's files for the game aren't in " + files.string() + " (no " + MANIFEST + "): the patch isn't made");
            RemovePatch(clientData, log);
            return false;
        }

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

        log("Making the Worgen's and Goblins' patch for the game (" + patch.string() + ")");
        fs::path part = patch;
        part += ".part";
        MpqWriter writer(part);
        size_t tables = 0, packed = 0, missing = 0;
        std::vector<std::string> problems;
        bool strings = false;

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
            std::string low = name;
            std::transform(low.begin(), low.end(), low.begin(), [](unsigned char c) { return char(std::tolower(c)); });
            if (low == "interface\\gluexml\\worgengoblinstrings.lua")
                strings = true;
        }
        if (missing)
            problems.push_back(std::to_string(missing) + " of mod-worgoblin's files aren't in " + files.string());

        std::vector<fs::path> changes;
        for (auto it = fs::directory_iterator(moduleData / "dbc", error); !error && it != fs::directory_iterator(); it.increment(error))
            if (it->path().extension() == ".txt")
                changes.push_back(it->path());
        std::sort(changes.begin(), changes.end());
        for (auto const& path : changes)
        {
            std::string name = path.stem().string();
            Bytes data;
            Dbc dbc;
            if (!game.Read("DBFilesClient\\" + name + ".dbc", data) || !dbc.Parse(data))
            {
                problems.push_back(name + ".dbc isn't in the game");
                continue;
            }
            std::string problem = Skyborne::ApplyChanges(dbc, Text(path));
            if (!problem.empty())
            {
                problems.push_back(name + ".dbc: " + problem);
                continue;
            }
            Skyborne::SortCharacterTable(dbc, name);
            writer.Add("DBFilesClient\\" + name + ".dbc", dbc.Write());
            ++tables;
        }

        // The character creation screen: the races' texts and the script, in the game's list.
        Bytes toc;
        if (game.Read("Interface\\GlueXML\\GlueXML.toc", toc))
        {
            std::string text(toc.begin(), toc.end());
            if (!text.empty() && text.back() != '\n')
                text += "\r\n";
            if (strings && text.find(STRINGS) == std::string::npos)
                text += std::string(STRINGS) + "\r\n";
            if (text.find(SCRIPT) == std::string::npos)
                text += std::string(BUTTONS) + "\r\n" + SCRIPT + "\r\n";
            writer.Add("Interface\\GlueXML\\GlueXML.toc", Bytes(text.begin(), text.end()));
            for (char const* name : { BUTTONS, SCRIPT })
                writer.Add(std::string("Interface\\GlueXML\\") + name, ReadFile(moduleData / "Interface" / "GlueXML" / name));
        }
        else
            problems.push_back("GlueXML.toc isn't in the game: the character screen won't show them");

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
        log("The Worgen's and Goblins' patch is in the game: " + std::to_string(tables) + " tables, " + std::to_string(packed)
            + " files (" + std::to_string(seconds) + " s)");
        return true;
    }
}
