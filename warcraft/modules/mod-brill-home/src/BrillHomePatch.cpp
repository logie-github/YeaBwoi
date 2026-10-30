// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Brill home's patch for the game (mod-brill-home): the mastiff (Cataclysm's Gilneas dog, made
 * 3.3.5's by tools/mastiff.py as the server was packaged: never in this repository) and its rows in
 * the game's tables (data/client/dbc, made by tools/build_sql.py: its CreatureModelData and its four
 * coats' CreatureDisplayInfo).
 *
 * It's Data/patch-W.MPQ: read after the Worgen's and Goblins' patch-U and the allied races' patch-V,
 * and made from the game with them in (so the tables it writes carry their rows), as the world
 * database's own tables are loaded (after those two are made, as the configuration is read); before
 * the Skyborne's patch-Z, which writes neither of these tables. Patches U and V are made from the game
 * without this one, as they are without Y and Z.
 *
 * The archive reading and writing and the tables are the Skyborne's (SkyborneFiles).
 */

#include "BrillHomePatch.h"
#include "../../mod-skyborne/src/SkyborneFiles.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>

namespace BrillHome
{
    using Skyborne::Bytes;
    using Skyborne::Dbc;
    using Skyborne::Game;
    using Skyborne::MpqWriter;

    namespace
    {
        constexpr char const* PATCH = "patch-W.MPQ";
        constexpr char const* MARK = "patch-W.MPQ.brillhome";
        constexpr char const* ASIDE = "patch-W.MPQ.aside";
        constexpr char const* MANIFEST = "mastiff.files";
        constexpr char const* VERSION = "brill-home-1";

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

    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        if (!fs::exists(clientData / MARK, error))
            return;
        fs::remove(clientData / PATCH, error);
        fs::remove(clientData / ASIDE, error);
        fs::remove(clientData / MARK, error);
        log("The Brill home's patch is out of the game");
    }

    void SetPatchAside(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        if (!fs::exists(clientData / MARK, error) || !fs::exists(clientData / PATCH, error))
            return;
        fs::remove(clientData / ASIDE, error);
        fs::rename(clientData / PATCH, clientData / ASIDE, error);
        if (error)
            log("The Brill home's patch couldn't be set aside: " + error.message());
        else
            log("The Brill home's patch is out of the game, kept beside it for ChromieCraft");
    }

    bool UpdatePatch(fs::path const& moduleData, fs::path const& files, fs::path const& clientData,
                     std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        fs::path patch = clientData / PATCH, mark = clientData / MARK;
        bool ours = fs::exists(mark, error);
        if (!ours && fs::exists(patch, error))
        {
            log(patch.string() + " isn't this module's: left alone, so the game won't have the mastiff");
            return false;
        }
        if (ours && !fs::exists(patch, error) && fs::exists(clientData / ASIDE, error))
            fs::rename(clientData / ASIDE, patch, error);
        auto start = std::chrono::steady_clock::now();
        // (the game as patches U and V leave it: this one, and those read after it, left out)
        Game game(clientData, { "patch-w.mpq", "patch-y.mpq", "patch-z.mpq", "patch-enus-y.mpq", "patch-engb-y.mpq", "patch-dede-y.mpq",
            "patch-frfr-y.mpq", "patch-eses-y.mpq", "patch-esmx-y.mpq", "patch-ruru-y.mpq", "patch-kokr-y.mpq", "patch-zhcn-y.mpq", "patch-zhtw-y.mpq" });
        if (game.archives.empty())
        {
            log("No game in " + clientData.string());
            return false;
        }
        std::string manifest = Text(files / MANIFEST);
        if (manifest.empty())
        {
            log("The mastiff's model and textures aren't in " + files.string() + " (no " + MANIFEST + "): the patch isn't made");
            RemovePatch(clientData, log);
            return false;
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

        Stamp stamp;
        stamp.Add(VERSION);
        stamp.Add(game.Describe());
        std::vector<fs::path> tables;
        for (auto it = fs::directory_iterator(moduleData / "dbc", error); !error && it != fs::directory_iterator(); it.increment(error))
            if (it->path().extension() == ".txt")
                tables.push_back(it->path());
        std::sort(tables.begin(), tables.end());
        for (auto const& t : tables)
        {
            stamp.Add(t.filename().string());
            stamp.Add(Text(t));
        }
        for (std::string const& name : listed)
        {
            stamp.Add(name);
            stamp.Add(std::to_string(fs::file_size(LocalPath(files, name), error)));
        }
        if (ours && fs::exists(patch, error) && Text(mark) == stamp.Hex() + "\n")
            return true;

        log("Making the Brill home's patch for the game (" + patch.string() + ")");
        fs::path part = patch;
        part += ".part";
        MpqWriter writer(part);
        size_t packed = 0, written = 0;
        std::vector<std::string> problems;
        for (std::string const& name : listed)
        {
            Bytes data = ReadFile(LocalPath(files, name));
            if (data.empty())
            {
                problems.push_back(name + " isn't in " + files.string());
                continue;
            }
            std::string gamePath = name;
            std::replace(gamePath.begin(), gamePath.end(), '/', '\\');
            writer.Add(gamePath, data);
            ++packed;
        }
        for (auto const& t : tables)
        {
            std::string name = t.stem().string();
            Bytes data;
            Dbc dbc;
            if (!game.Read("DBFilesClient\\" + name + ".dbc", data) || !dbc.Parse(data))
            {
                problems.push_back(name + ".dbc isn't in the game");
                continue;
            }
            std::string problem = Skyborne::ApplyChanges(dbc, Text(t));
            if (!problem.empty())
            {
                problems.push_back(name + ".dbc: " + problem);
                continue;
            }
            writer.Add("DBFilesClient\\" + name + ".dbc", dbc.Write());
            ++written;
        }
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
        log("The Brill home's patch is in the game: " + std::to_string(written) + " tables, " + std::to_string(packed) + " files ("
            + std::to_string(seconds) + " s)");
        return true;
    }
}
