// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Worgen (race 12, Alliance) and Goblins (race 9, Horde) in ChromieCraft's realm
 * (mod-worgen-goblin): mod-worgoblin (github.com/heyitsbench/mod-worgoblin, built in: its scripts,
 * the goblins' racials) made to fit the app's realm, beside the allied races and the Skyborne, and
 * never on Wrath Remix's realm (WorgenGoblin.Enable 0, ctl.sh).
 *
 *  - The game's patch (WorgenGoblinPatch.cpp, Data/patch-U.MPQ in the client WOW_CLIENT_DIR names):
 *    made before the configuration is read, ahead of the allied races' patch (made once it's read),
 *    the Skyborne's and Wrath Remix's, which are made from the game with it in.
 *  - The world server's tables (data/server/worgen_goblin_dbc.sql, tools/build_data.py): what
 *    mod-worgoblin changes in the game's tables, merged into the world database's *_dbc tables at
 *    every start, after every module's SQL is in and before the tables are read: the allied races'
 *    and the Skyborne's SQL put whole rows of some of the same tables (the reputations, the skills),
 *    which would otherwise drop the Worgen's and Goblins' bits whenever they're put in again.
 *  - The rest of mod-worgoblin's SQL (the races' starts, gear, skills, quests: tools/package.py) goes
 *    into ChromieCraft's world database alone (ctl.sh, data/sql/chromiecraft/world).
 *  - The starting areas (Gilneas for the Worgen, Kezan and the Lost Isles for the Goblins, from
 *    Cataclysm: data/zones, tools/zones), when the app has their package installed (WOW_ZONES_DIR,
 *    ctl.sh) and WorgenGoblin.StartingAreas is on: their files in the game's patch (only those the
 *    game hasn't), their rows in the world server's tables, the two races' new characters started
 *    there. They're the two races' alone: no other character is let onto their maps, and nothing of
 *    the game's own world, quests or creatures is changed by them. Without the package, the two races
 *    start where mod-worgoblin has them (Shadowglen, the Valley of Trials) and the areas' rows are taken
 *    out of the world's tables.
 */

#include "WorgenGoblinPatch.h"
#include "Config.h"
#include "DatabaseEnv.h"
#include "Chat.h"
#include "Log.h"
#include "Player.h"
#include "ScriptMgr.h"
#include <cstdlib>
#include <fstream>
#include <iterator>

namespace fs = std::filesystem;

namespace
{
    fs::path ModuleData()
    {
        return fs::path(sConfigMgr->GetOption<std::string>("SourceDirectory", "")) / "modules" / "mod-worgen-goblin" / "data";
    }

    bool Enabled()
    {
        return sConfigMgr->GetOption<bool>("WorgenGoblin.Enable", true);
    }

    // The starting areas' package the app installed (its client and server folders), if there's one.
    fs::path ZonesPackage()
    {
        char const* dir = std::getenv("WOW_ZONES_DIR");
        if (!dir || !*dir)
            return {};
        std::error_code error;
        fs::path package(dir);
        if (!fs::exists(package / "client" / "files.txt", error) || !fs::is_directory(package / "server" / "maps", error))
            return {};
        return package;
    }

    // Whether the game's patch was made with the starting areas in it (or there's no game here to
    // make it for: the server alone), set as the configuration is about to be read.
    bool zonesInGame = false;

    bool ZonesOn()
    {
        return Enabled() && sConfigMgr->GetOption<bool>("WorgenGoblin.StartingAreas", false) && !ZonesPackage().empty() && zonesInGame;
    }

    constexpr uint8 GOBLIN = 9, WORGEN = 12;

    // The starting areas' maps: the two races' alone.
    bool IsZoneMap(uint32 map)
    {
        switch (map)
        {
            case 638: case 648: case 654: case 655: case 656: case 659: case 661:
                return true;
            default:
                return false;
        }
    }

    bool RunSql(fs::path const& file)
    {
        std::ifstream in(file, std::ios::binary);
        std::string sql((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        if (sql.empty())
        {
            LOG_ERROR("module", "Worgen and Goblins: {} isn't there", file.string());
            return false;
        }
        auto statements = WorgenGoblin::Statements(sql);
        for (std::string const& statement : statements)
            WorldDatabase.DirectExecute(statement);
        return true;
    }
}

class WorgenGoblinWorld : public WorldScript
{
public:
    WorgenGoblinWorld() : WorldScript("WorgenGoblinWorld", { WORLDHOOK_ON_BEFORE_CONFIG_LOAD, WORLDHOOK_ON_LOAD_CUSTOM_DATABASE_TABLE }) { }

    // As the configuration is about to be read (the modules' configs are read already): before the
    // allied races' patch, made once it's read from the game with this one in.
    void OnBeforeConfigLoad(bool reload) override
    {
        if (reload)
            return;
        char const* client = std::getenv("WOW_CLIENT_DIR");
        if (!client || !*client)
            return;
        fs::path data = fs::path(client) / "Data";
        std::error_code error;
        if (!fs::is_directory(data, error))
        {
            zonesInGame = true;          // (no game here: the server's alone)
            return;
        }
        auto log = [](std::string const& line) { LOG_INFO("module", "Worgen and Goblins: {}", line); };
        if (!Enabled())
        {
            WorgenGoblin::SetPatchAside(data, log);
            return;
        }
        if (!sConfigMgr->GetOption<bool>("WorgenGoblin.ClientPatch", true))
        {
            WorgenGoblin::RemovePatch(data, log);
            return;
        }
        fs::path module = ModuleData();
        if (!fs::is_directory(module / "client", error))
        {
            LOG_ERROR("module", "Worgen and Goblins: the module's files for the game aren't in {}", (module / "client").string());
            return;
        }
        WorgenGoblin::Zones zones;
        fs::path package = ZonesPackage();
        if (!package.empty() && sConfigMgr->GetOption<bool>("WorgenGoblin.StartingAreas", false))
            zones = { module / "zones" / "client", package / "client" };
        zonesInGame = WorgenGoblin::UpdatePatch(module / "client", module / "files", data, log, zones) && !zones.package.empty();
    }

    // The world database's *_dbc tables, read right after this.
    void OnLoadCustomDatabaseTable() override
    {
        if (!Enabled())
            return;
        fs::path file = ModuleData() / "server" / "worgen_goblin_dbc.sql";
        std::ifstream in(file, std::ios::binary);
        std::string sql((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        if (sql.empty())
        {
            LOG_ERROR("module", "Worgen and Goblins: {} isn't there: the world server doesn't know them", file.string());
            return;
        }
        auto statements = WorgenGoblin::Statements(sql);
        for (std::string const& statement : statements)
            WorldDatabase.DirectExecute(statement);
        LOG_INFO("module", "Worgen and Goblins: their rows are in the world's tables ({} statements)", statements.size());
        // The starting areas: their rows and the two races' starts there, or neither.
        fs::path zones = ModuleData() / "zones" / "server";
        if (ZonesOn())
        {
            if (RunSql(zones / "zones_dbc.sql") && RunSql(zones / "starts_on.sql"))
                LOG_INFO("module", "Worgen and Goblins: the starting areas are in the world (Gilneas, Kezan and the Lost Isles)");
        }
        else if (RunSql(zones / "zones_off.sql"))
            LOG_INFO("module", "Worgen and Goblins: no starting areas ({}): they start with the Night Elves and the Orcs",
                ZonesPackage().empty() ? "their package isn't installed" : "off");
    }
};

// The starting areas' maps are the Worgen's and Goblins' alone (a game master's too).
class WorgenGoblinPlayer : public PlayerScript
{
public:
    WorgenGoblinPlayer() : PlayerScript("WorgenGoblinPlayer", { PLAYERHOOK_ON_BEFORE_TELEPORT, PLAYERHOOK_ON_LOGIN }) { }

    static bool Allowed(Player* player)
    {
        return player->getRace() == GOBLIN || player->getRace() == WORGEN || player->GetSession()->GetSecurity() > SEC_PLAYER;
    }

    bool OnPlayerBeforeTeleport(Player* player, uint32 mapid, float, float, float, float, uint32, Unit*) override
    {
        if (!IsZoneMap(mapid) || Allowed(player))
            return true;
        ChatHandler(player->GetSession()).SendSysMessage("Only the Worgen and the Goblins go there.");
        return false;
    }

    // (one that's there anyway, from before: taken to its home)
    void OnPlayerLogin(Player* player) override
    {
        if (IsZoneMap(player->GetMapId()) && !Allowed(player))
            player->TeleportTo(player->m_homebindMapId, player->m_homebindX, player->m_homebindY, player->m_homebindZ, player->GetOrientation());
    }
};

void AddWorgenGoblinScripts()
{
    new WorgenGoblinWorld();
    new WorgenGoblinPlayer();
}
