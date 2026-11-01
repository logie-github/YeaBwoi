// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Brill home's patch for the game (mod-brill-home): Corn, the house's dog, and the house's cellar.
 *
 * Corn is the game's own pug (Perky Pug's model and display, 31174) in the user's Forsaken texture (the user:
 * "Use this texture on the pug dog you get from the quest in brill. Name the dog Corn"; "my pug is supposed
 * to be zombified"). The patch carries the user's coat (data/client/corn/ForsakenPug_Corn.blp, made from
 * warcraft/branding/corn-forsaken-pug.png by tools/build_sql.py) at the path of the pug's own coat, which
 * the client's pug model names: no table and no model is changed for him. (A display of his own, 39700,
 * showed as the game's cube, as the Cataclysm mastiff's had: the game never had that row. Every pug in
 * the game wears Corn's coat now; Perky Pug is the only other.)
 *
 * The cellar is the game's own models as objects (GameObjectDisplayInfo 9700 and 9701, made by
 * tools/build_town.py).
 *
 * It's Data/<locale>/patch-<locale>-V.MPQ: read after the Worgen's and Goblins' patch-U and the allied
 * races' patch-V (the game reads a letter's language patch after its own), and made from the game with
 * them in (so the tables it writes carry their rows), as the world database's own tables are loaded
 * (after those two are made, as the configuration is read); before the Alternative HD Patch's W and X
 * (water and sky) and the Skyborne's Y and Z, which write none of these tables. Patches U and V are
 * made from the game without this one, as they are without Y and Z.
 *
 * An old Data/patch-W.MPQ of this module's (the mastiff's, before the language folder's V) is taken out;
 * the Alternative HD Patch's water, which has the same name, is left as it is.
 *
 * The archive reading and writing and the tables are the Skyborne's (SkyborneFiles).
 */

#include "BrillHomePatch.h"
#include "../../mod-skyborne/src/SkyborneFiles.h"
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstring>
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
        constexpr char const* MARK_SUFFIX = ".brillhome";
        constexpr char const* ASIDE_SUFFIX = ".aside";
        constexpr char const* VERSION = "brill-home-4";
        // Corn: the game's pug, its coat a display's (tools/build_sql.py: DOG_*)
        constexpr char const* PUG = "Creature\\Pug\\Pug.m2";
        // (data/client/corn: the user's coat, tools/build_sql.py)
        constexpr char const* CORN_COAT = "ForsakenPug_Corn.blp";
        // (where it was: Data/patch-W.MPQ, and a file of its that tells it's this module's)
        constexpr char const* OLD_PATCH = "patch-W.MPQ";
        constexpr char const* MASTIFF = "Creature\\Mastiff\\Mastiff.m2";

        std::string Lower(std::string text)
        {
            std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return std::tolower(c); });
            return text;
        }

        // The client's language: the folder in Data with its locale-<name>.MPQ.
        std::string Locale(fs::path const& clientData)
        {
            std::error_code error;
            for (auto it = fs::directory_iterator(clientData, error); !error && it != fs::directory_iterator(); it.increment(error))
            {
                std::string name = it->path().filename().string();
                if (name.size() != 4 || !it->is_directory(error))
                    continue;
                for (auto in = fs::directory_iterator(it->path(), error); !error && in != fs::directory_iterator(); in.increment(error))
                    if (Lower(in->path().filename().string()) == Lower("locale-" + name + ".mpq"))
                        return name;
            }
            return "";
        }

        fs::path PatchPath(fs::path const& clientData, std::string const& locale)
        {
            return clientData / locale / ("patch-" + locale + "-V.MPQ");
        }

        fs::path With(fs::path path, char const* suffix)
        {
            path += suffix;
            return path;
        }

        // The archives read after this one, and this one: left out of what it's made from.
        std::vector<std::string> Skip(std::string const& locale)
        {
            std::string loc = Lower(locale);
            std::vector<std::string> skip = { "patch-" + loc + "-v.mpq" };
            for (char c = 'w'; c <= 'z'; ++c)
            {
                skip.push_back(std::string("patch-") + c + ".mpq");
                skip.push_back("patch-" + loc + "-" + c + ".mpq");
            }
            return skip;
        }

        // The patch where it was (Data/patch-W.MPQ), out of the game if it's this module's (the mastiff in
        // it); the Alternative HD Patch's water, which has the same name, left alone.
        void RemoveOldPatch(fs::path const& clientData, std::function<void(std::string const&)> const& log)
        {
            std::error_code error;
            fs::path old = clientData / OLD_PATCH;
            if (!fs::exists(With(old, MARK_SUFFIX), error))
                return;
            for (fs::path const& file : { old, With(old, ASIDE_SUFFIX) })
                if (fs::exists(file, error) && Skyborne::OpenArchive(file)->Has(MASTIFF))
                {
                    fs::remove(file, error);
                    log("Took the Brill home's old patch out of the game (" + file.string() + ")");
                }
            fs::remove(With(old, MARK_SUFFIX), error);
        }

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
    }

    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        RemoveOldPatch(clientData, log);
        std::string locale = Locale(clientData);
        if (locale.empty())
            return;
        fs::path patch = PatchPath(clientData, locale);
        if (!fs::exists(With(patch, MARK_SUFFIX), error))
            return;
        fs::remove(patch, error);
        fs::remove(With(patch, ASIDE_SUFFIX), error);
        fs::remove(With(patch, MARK_SUFFIX), error);
        log("The Brill home's patch is out of the game");
    }

    void SetPatchAside(fs::path const& clientData, std::function<void(std::string const&)> const& log)
    {
        std::error_code error;
        RemoveOldPatch(clientData, log);
        std::string locale = Locale(clientData);
        if (locale.empty())
            return;
        fs::path patch = PatchPath(clientData, locale);
        if (!fs::exists(With(patch, MARK_SUFFIX), error) || !fs::exists(patch, error))
            return;
        fs::remove(With(patch, ASIDE_SUFFIX), error);
        fs::rename(patch, With(patch, ASIDE_SUFFIX), error);
        if (error)
            log("The Brill home's patch couldn't be set aside: " + error.message());
        else
            log("The Brill home's patch is out of the game, kept beside it for ChromieCraft");
    }

    // The game's pug's coat: the first texture the model names (hard-coded) that is a pug's, or "".
    static std::string PugCoat(Bytes const& m2)
    {
        auto u32 = [&](size_t at) { uint32_t v; std::memcpy(&v, &m2[at], 4); return v; };
        if (m2.size() < 0x130 || std::memcmp(m2.data(), "MD20", 4) != 0)
            return "";
        uint32_t count = u32(0x50), offset = u32(0x54);
        if (offset + uint64_t(count) * 16 > m2.size())
            return "";
        for (uint32_t i = 0; i < count; ++i)
        {
            size_t at = offset + i * 16;
            uint32_t kind = u32(at), length = u32(at + 8), name = u32(at + 12);
            if (kind != 0 || !length || name + uint64_t(length) > m2.size())
                continue;
            std::string path(reinterpret_cast<char const*>(&m2[name]), length);
            path = path.c_str();
            std::string low = Lower(path);
            if (low.size() > 4 && low.substr(low.size() - 4) == ".blp" && low.find("pug") != std::string::npos)
                return path;
        }
        return "";
    }

    bool UpdatePatch(fs::path const& moduleData, fs::path const& clientData, std::function<void(std::string const&)> const& log, bool& cornReady)
    {
        cornReady = false;
        std::error_code error;
        RemoveOldPatch(clientData, log);
        std::string locale = Locale(clientData);
        if (locale.empty())
        {
            log("No language folder in " + clientData.string() + ": the patch isn't made");
            return false;
        }
        fs::path patch = PatchPath(clientData, locale), mark = With(patch, MARK_SUFFIX);
        bool ours = fs::exists(mark, error);
        if (!ours && fs::exists(patch, error))
        {
            log(patch.string() + " isn't this module's: left alone, so the game won't have Corn or the cellar");
            return false;
        }
        if (ours && !fs::exists(patch, error) && fs::exists(With(patch, ASIDE_SUFFIX), error))
            fs::rename(With(patch, ASIDE_SUFFIX), patch, error);
        auto start = std::chrono::steady_clock::now();
        // (the game as patches U and V leave it: this one, and those read after it, left out)
        Game game(clientData, Skip(locale));
        if (game.archives.empty())
        {
            log("No game in " + clientData.string());
            return false;
        }

        // The module's own files (data/client/files: Corn's coat), by their game paths.
        std::vector<std::pair<std::string, fs::path>> own;
        fs::path filesDir = moduleData / "files";
        if (fs::is_directory(filesDir, error))
            for (auto it = fs::recursive_directory_iterator(filesDir, error); !error && it != fs::recursive_directory_iterator(); it.increment(error))
                if (it->is_regular_file(error))
                {
                    std::string rel = fs::relative(it->path(), filesDir, error).generic_string();
                    std::replace(rel.begin(), rel.end(), '/', '\\');
                    own.emplace_back(rel, it->path());
                }
        std::sort(own.begin(), own.end());

        // Corn: the user's coat put where the game's pug's own coat is (the pug's model names it). No table
        // and no model of the game's is changed for him, so nothing in the game can stand in front of it:
        // the mastiff and then the pug with a display of its own (39700) both showed as the game's cube
        // for a look it hasn't.
        Bytes pug, cornCoat = ReadFile(moduleData / "corn" / CORN_COAT);
        std::string coatPath;
        if (game.Read(PUG, pug))
            coatPath = PugCoat(pug);
        if (coatPath.empty() || cornCoat.empty())
            log("The game's pug (" + std::string(PUG) + ") or Corn's coat isn't as expected: Corn is the plain pug");

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
        for (auto const& [name, path] : own)
        {
            stamp.Add(name);
            stamp.Add(std::to_string(fs::file_size(path, error)));
        }
        stamp.Add(coatPath);
        stamp.Add(std::to_string(cornCoat.size()));
        bool corn = !coatPath.empty() && !cornCoat.empty();
        if (ours && fs::exists(patch, error) && Text(mark) == stamp.Hex() + "\n")
        {
            cornReady = corn;
            return true;
        }

        log("Making the Brill home's patch for the game (" + patch.string() + ")");
        fs::path part = patch;
        part += ".part";
        MpqWriter writer(part);
        size_t packed = 0, written = 0;
        std::vector<std::string> problems;
        for (auto const& [name, path] : own)
        {
            Bytes data = ReadFile(path);
            if (data.empty())
            {
                problems.push_back(name + " is empty");
                continue;
            }
            writer.Add(name, data);
            ++packed;
        }
        if (corn)
        {
            writer.Add(coatPath, cornCoat);
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
        cornReady = corn;
        auto seconds = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - start).count();
        log("The Brill home's patch is in the game: " + std::to_string(written) + " tables, " + std::to_string(packed) + " files ("
            + std::to_string(seconds) + " s)" + (cornReady ? ", Corn the Forsaken pug" : ", Corn the plain pug"));
        return true;
    }
}
