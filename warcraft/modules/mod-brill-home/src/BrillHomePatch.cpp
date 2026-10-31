// SPDX-License-Identifier: GPL-3.0-or-later
/* Remove archives created by the retired Cataclysm mastiff backport. The Brill dog now uses
 * the stock pug model, so neither a model download nor a client patch is needed. */

#include "BrillHomePatch.h"
#include "../../mod-skyborne/src/SkyborneFiles.h"
#include <algorithm>
#include <cctype>

namespace BrillHome
{

    namespace
    {
        constexpr char const* MARK_SUFFIX = ".brillhome";
        constexpr char const* ASIDE_SUFFIX = ".aside";
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

}
