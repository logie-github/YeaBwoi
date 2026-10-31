// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Ralia's mod: the character textures of the user's copy of Ralia's patches in the game, while the
 * app's Game options have it on (RaliaPatch.cpp makes the game's patch from them).
 *
 * The app keeps the textures (the zip's Character folder, picked on the phone) in files/ralia/files,
 * and files/ralia/enabled while the option is on; the world server makes the patch as the world
 * database's own tables are loaded (after the Worgen's and Goblins' and the allied races' patches are
 * made, as the configuration is read, and before the Skyborne's, as the world is made), and takes it
 * out of the game when the option is off. Ralia.Enable = 0 (mod_ralia.conf) turns it off whatever
 * the option says.
 */

#include "Config.h"
#include "Log.h"
#include "RaliaPatch.h"
#include "ScriptMgr.h"

#include <cstdlib>
#include <filesystem>

namespace fs = std::filesystem;

class RaliaWorld : public WorldScript
{
public:
    RaliaWorld() : WorldScript("RaliaWorld", { WORLDHOOK_ON_LOAD_CUSTOM_DATABASE_TABLE }) { }

    void OnLoadCustomDatabaseTable() override
    {
        char const* client = std::getenv("WOW_CLIENT_DIR");
        if (!client || !*client)
            return;
        fs::path clientDir = fs::path(client).lexically_normal();
        if (clientDir.filename().empty())
            clientDir = clientDir.parent_path();
        fs::path data = clientDir / "Data";
        std::error_code error;
        if (!fs::is_directory(data, error))
            return;
        // (the app's folder for the mod: beside the game's)
        fs::path folder = sConfigMgr->GetOption<std::string>("Ralia.Folder", "");
        if (folder.empty())
            folder = clientDir.parent_path() / "ralia";
        auto log = [](std::string const& line) { LOG_INFO("module", "Ralia's mod: {}", line); };
        if (!sConfigMgr->GetOption<bool>("Ralia.Enable", true) || !fs::exists(folder / "enabled", error))
        {
            Ralia::RemovePatch(data, log);
            return;
        }
        fs::path sources = fs::path(sConfigMgr->GetOption<std::string>("SourceDirectory", "")) / "modules" / "mod-ralia" / "data" / "client" / "sources.txt";
        Ralia::UpdatePatch(folder / "files", sources, data, log);
    }
};

void AddRaliaScripts()
{
    new RaliaWorld();
}
