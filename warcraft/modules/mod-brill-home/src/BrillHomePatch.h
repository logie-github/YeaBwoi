// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef MOD_BRILL_HOME_PATCH_H
#define MOD_BRILL_HOME_PATCH_H

#include <filesystem>
#include <functional>
#include <string>

namespace BrillHome
{
    namespace fs = std::filesystem;

    // Takes the patch out, if it's this module's.
    void RemovePatch(fs::path const& clientData, std::function<void(std::string const&)> const& log);

}

#endif
