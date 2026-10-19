// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef MOD_ALLIED_RACES_PATCH_H
#define MOD_ALLIED_RACES_PATCH_H

#include <filesystem>
#include <functional>
#include <string>

namespace AlliedRaces
{
    namespace fs = std::filesystem;

    // Makes Data/patch-V.MPQ in the client's Data folder [clientData] from the module's files for
    // the game ([moduleData]: data/client: the tables' changes, the character screen, the icons,
    // the intros) and the races' models, textures and voices ([files]: made by tools/package.py
    // as the server was packaged, listed in alliedraces.files there), when the client, the
    // module or those files changed since. True when the patch is in place.
    bool UpdatePatch(fs::path const& moduleData, fs::path const& files, fs::path const& clientData,
                     std::function<void(std::string const&)> const& log);
    // Takes the patch out, if it's this module's.
    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log);
}

#endif
