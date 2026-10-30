// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef MOD_BRILL_HOME_PATCH_H
#define MOD_BRILL_HOME_PATCH_H

#include <filesystem>
#include <functional>
#include <string>

namespace BrillHome
{
    namespace fs = std::filesystem;

    // Makes Data/patch-W.MPQ in the client's Data folder [clientData] from the module's table changes
    // ([moduleData]/dbc: data/client/dbc) and the mastiff's files ([files]: made by tools/mastiff.py as
    // the server was packaged, listed in mastiff.files there), when the game, those changes or those
    // files changed since. True when the patch is in place.
    bool UpdatePatch(fs::path const& moduleData, fs::path const& files, fs::path const& clientData,
                     std::function<void(std::string const&)> const& log);
    // Takes the patch out, if it's this module's.
    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log);
    // Takes the patch out of the game but keeps it beside it (Wrath Remix's realm hasn't the Brill home);
    // UpdatePatch puts it back first.
    void SetPatchAside(fs::path const& clientData, std::function<void(std::string const&)> const& log);
}

#endif
