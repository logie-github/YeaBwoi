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
 */

#include "WorgenGoblinPatch.h"
#include "Config.h"
#include "DatabaseEnv.h"
#include "Log.h"
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
            return;
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
        WorgenGoblin::UpdatePatch(module / "client", module / "files", data, log);
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
    }
};

void AddWorgenGoblinScripts()
{
    new WorgenGoblinWorld();
}
