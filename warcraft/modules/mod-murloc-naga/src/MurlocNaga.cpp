// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Murlocs and the Naga (mod-murloc-naga): two races of ChromieCraft's realm, the murlocs
 * (Alliance, race 19) in a village on the island west of Auberdine and the renegade naga (Horde,
 * race 20) on the island north of it, both starting there at level 10. The world database has them
 * from the module's SQL (data/sql/chromiecraft/world, tools/build_data.py; ctl.sh puts it in
 * ChromieCraft's world database alone); the game, from the patch this makes as the configuration
 * is read (MurlocNagaPatch.cpp) in the client WOW_CLIENT_DIR names (ctl.sh).
 *
 * The patch is made first of the race modules' (they're read by the game after it and made from
 * the game with it in, so they carry its rows): before the Worgen and Goblins' (made as the
 * configuration is about to be read too, its script registered after this one's: the modules are
 * loaded in their folders' order), the allied races', Wrath Remix's and the Skyborne's.
 *
 * They start at level 10, away from their kin's trainers: they learn their class's trainer spells
 * as they come of age for them (at creation and every level, as the trainers would teach them;
 * talents' ranks once the talent is theirs).
 */

#include "Config.h"
#include "DatabaseEnv.h"
#include "Log.h"
#include "MurlocNagaPatch.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "SpellInfo.h"
#include "SpellMgr.h"
#include <cstdlib>
#include <filesystem>
#include <map>
#include <vector>

namespace fs = std::filesystem;

namespace
{
    constexpr uint8 RACE_MURLOC = 19, RACE_NAGA = 20, START_LEVEL = 10;

    bool Enabled()
    {
        return sConfigMgr->GetOption<bool>("MurlocNaga.Enable", true);
    }

    bool Ours(Player const* player)
    {
        return player && (player->getRace() == RACE_MURLOC || player->getRace() == RACE_NAGA);
    }

    fs::path ModuleData()
    {
        return fs::path(sConfigMgr->GetOption<std::string>("SourceDirectory", "")) / "modules" / "mod-murloc-naga" / "data";
    }

    // A class's trainer spells (its class trainers', the game's: trainer of type 0, the class its
    // requirement), in the order their levels come.
    struct TrainerSpell { uint32 spell, level, needs[3]; };

    std::vector<TrainerSpell> const& ClassSpells(uint8 cls)
    {
        static std::map<uint8, std::vector<TrainerSpell>> cache;
        auto it = cache.find(cls);
        if (it != cache.end())
            return it->second;
        std::vector<TrainerSpell>& out = cache[cls];
        if (QueryResult result = WorldDatabase.Query(
                "SELECT DISTINCT s.`SpellId`, s.`ReqLevel`, s.`ReqAbility1`, s.`ReqAbility2`, s.`ReqAbility3` FROM `trainer_spell` s "
                "JOIN `trainer` t ON t.`Id` = s.`TrainerId` WHERE t.`Type` = 0 AND t.`Requirement` = {} AND s.`ReqSkillLine` = 0 "
                "ORDER BY s.`ReqLevel`, s.`SpellId`", cls))
        {
            do
            {
                Field* f = result->Fetch();
                out.push_back({ f[0].Get<uint32>(), f[1].Get<uint32>(), { f[2].Get<uint32>(), f[3].Get<uint32>(), f[4].Get<uint32>() } });
            } while (result->NextRow());
        }
        return out;
    }

    // What a trainer spell teaches: the spells it learns, or itself.
    std::vector<uint32> Taught(uint32 spell)
    {
        std::vector<uint32> out;
        if (SpellInfo const* info = sSpellMgr->GetSpellInfo(spell))
        {
            for (SpellEffectInfo const& effect : info->GetEffects())
                if (effect.Effect == SPELL_EFFECT_LEARN_SPELL && effect.TriggerSpell)
                    out.push_back(effect.TriggerSpell);
            if (out.empty())
                out.push_back(spell);
        }
        return out;
    }

    void TeachClassSpells(Player* player)
    {
        uint32 level = player->GetLevel();
        uint32 learned = 0;
        for (TrainerSpell const& s : ClassSpells(player->getClass()))
        {
            if (s.level > level)
                break;
            bool can = true;
            for (uint32 need : s.needs)
                if (need && !player->HasSpell(need))
                    can = false;
            if (!can)
                continue;
            for (uint32 spell : Taught(s.spell))
                if (!player->HasSpell(spell) && sSpellMgr->GetSpellInfo(spell))
                {
                    player->learnSpell(spell);
                    ++learned;
                }
        }
        if (learned)
            LOG_DEBUG("module", "Murlocs and Naga: {} learned {} class spells at level {}", player->GetName(), learned, level);
    }
}

class MurlocNagaWorld : public WorldScript
{
public:
    MurlocNagaWorld() : WorldScript("MurlocNagaWorld", { WORLDHOOK_ON_BEFORE_CONFIG_LOAD, WORLDHOOK_ON_AFTER_CONFIG_LOAD }) { }

    // As the configuration is about to be read (the modules' configs are read already): before the
    // other race modules' patches, which are made from the game with this one in.
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
        auto log = [](std::string const& line) { LOG_INFO("module", "Murlocs and Naga: {}", line); };
        // (Wrath Remix's realm has no murlocs or naga: ctl.sh turns them off there, and the patch is
        // set aside before the other patches are made from the game's tables)
        if (!Enabled())
        {
            MurlocNaga::SetPatchAside(data, log);
            return;
        }
        if (!sConfigMgr->GetOption<bool>("MurlocNaga.ClientPatch", true))
        {
            MurlocNaga::RemovePatch(data, log);
            return;
        }
        if (!fs::is_directory(ModuleData() / "client", error))
        {
            LOG_ERROR("module", "Murlocs and Naga: the module's files for the game aren't in {}", (ModuleData() / "client").string());
            return;
        }
        MurlocNaga::UpdatePatch(ModuleData() / "client", data, log);
    }

    // The intros' cameras where the world server reads them (its data folder's Cameras, read as the
    // world starts), so it follows the camera and sends the game what's along the way.
    void OnAfterConfigLoad(bool reload) override
    {
        if (reload || !Enabled())
            return;
        std::string dataDir = sConfigMgr->GetOption<std::string>("DataDir", "./");
        MurlocNaga::PutCameras(ModuleData() / "client", fs::path(dataDir), [](std::string const& line)
        {
            LOG_ERROR("module", "Murlocs and Naga: {}", line);
        });
    }
};

class MurlocNagaPlayer : public PlayerScript
{
public:
    MurlocNagaPlayer() : PlayerScript("MurlocNagaPlayer", { PLAYERHOOK_ON_FIRST_LOGIN, PLAYERHOOK_ON_LEVEL_CHANGED, PLAYERHOOK_ON_LOGIN }) { }

    // They start at level 10, with their class's spells to that level, whole.
    void OnPlayerFirstLogin(Player* player) override
    {
        if (!Ours(player) || !Enabled())
            return;
        if (player->GetLevel() < START_LEVEL)
            player->GiveLevel(START_LEVEL);
        TeachClassSpells(player);
        player->SetFullHealth();
        if (player->getPowerType() == POWER_MANA)
            player->SetPower(POWER_MANA, player->GetMaxPower(POWER_MANA));
    }

    void OnPlayerLevelChanged(Player* player, uint8 /*oldLevel*/) override
    {
        if (Ours(player) && Enabled())
            TeachClassSpells(player);
    }

    // (what a talent learned since opens, and spells a newer release teaches)
    void OnPlayerLogin(Player* player) override
    {
        if (Ours(player) && Enabled() && !player->HasAtLoginFlag(AT_LOGIN_FIRST))
            TeachClassSpells(player);
    }
};

void AddMurlocNagaScripts()
{
    new MurlocNagaWorld();
    new MurlocNagaPlayer();
}
