// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The void elves and the nightborne (mod-allied-races): Blizzard's allied races of today as two
 * races of the 3.3.5 realms, void elves (Alliance, race 15, starting in Ammen Vale with the
 * draenei) and nightborne (Horde, race 16, starting in Deathknell with the Forsaken), in their
 * own models, looks and voices. The world database has them from the module's SQL (data/sql/
 * db-world, tools/build_data.py); the game, from the patch this makes as the world server starts
 * (AlliedRacesPatch.cpp) in the client WOW_CLIENT_DIR names (ctl.sh), from the module's files
 * and the races' models, textures and voices the server package fetched (tools/package.py).
 *
 * The patch is made as soon as the configuration is read, before any module's tables are loaded:
 * Wrath Remix's patch (patch-Y, made at the custom tables' load) and the Skyborne's (patch-Z, at
 * the startup) are read by the game after this one and are made from the game's tables with this
 * patch in, so they carry its rows on. Made at the startup, as those are, it came after them on a
 * first start, and the game didn't know the races until the next.
 */

#include "AlliedRacesPatch.h"
#include "Config.h"
#include "Log.h"
#include "ScriptMgr.h"
#include <cstdlib>
#include <filesystem>

namespace fs = std::filesystem;

class AlliedRacesWorld : public WorldScript
{
public:
    AlliedRacesWorld() : WorldScript("AlliedRacesWorld", { WORLDHOOK_ON_AFTER_CONFIG_LOAD }) { }

    void OnAfterConfigLoad(bool reload) override
    {
        if (reload)
            return;
        char const* client = std::getenv("WOW_CLIENT_DIR");
        if (!client || !*client)
            return;
        fs::path data = fs::path(client) / "Data";
        std::error_code error;
        if (!fs::is_directory(data, error))
            return;
        auto log = [](std::string const& line) { LOG_INFO("module", "Allied races: {}", line); };
        if (!sConfigMgr->GetOption<bool>("AlliedRaces.ClientPatch", true))
        {
            AlliedRaces::RemovePatch(data, log);
            return;
        }
        fs::path module = fs::path(sConfigMgr->GetOption<std::string>("SourceDirectory", "")) / "modules" / "mod-allied-races" / "data";
        if (!fs::is_directory(module / "client", error))
        {
            LOG_ERROR("module", "Allied races: the module's files for the game aren't in {}", (module / "client").string());
            return;
        }
        AlliedRaces::UpdatePatch(module / "client", module / "files", data, log);
    }
};

void AddAlliedRacesScripts()
{
    new AlliedRacesWorld();
}
