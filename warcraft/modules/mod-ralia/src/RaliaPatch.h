// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Ralia's mod's patch for the game: the character textures of Ralia's patches (the user's own copy,
 * picked in the app's Game options) made the game's, as Data/<locale>/patch-<locale>-Z.MPQ.
 */

#ifndef MOD_RALIA_PATCH_H
#define MOD_RALIA_PATCH_H

#include <filesystem>
#include <functional>
#include <string>

namespace Ralia
{
    namespace fs = std::filesystem;
    using Log = std::function<void(std::string const&)>;

    // Makes the patch (again, when what it's made from changed) from the textures in [files] (a
    // folder of today's game's paths: Character\<race>\<sex>\...), with [sources] (the module's
    // data/client/sources.txt: which file each of the allied races' colours takes). False when it
    // isn't in the game.
    bool UpdatePatch(fs::path const& files, fs::path const& sources, fs::path const& clientData, Log const& log);

    // Takes this module's patch out of the game.
    void RemovePatch(fs::path const& clientData, Log const& log);
}

#endif
