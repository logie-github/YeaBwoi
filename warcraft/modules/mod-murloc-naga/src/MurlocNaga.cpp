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

#include "CellImpl.h"
#include "Config.h"
#include "DatabaseEnv.h"
#include "DBCStores.h"
#include "GridNotifiers.h"
#include "GridNotifiersImpl.h"
#include "Log.h"
#include "MurlocNagaPatch.h"
#include "ObjectMgr.h"
#include "Player.h"
#include "ReputationMgr.h"
#include "ScriptMgr.h"
#include "SpellInfo.h"
#include "SpellMgr.h"
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <map>
#include <unordered_map>
#include <vector>

namespace fs = std::filesystem;

namespace
{
    constexpr uint8 RACE_MURLOC = 19, RACE_NAGA = 20, START_LEVEL = 10;
    constexpr uint32 MURLOC_RACE_BIT = 1u << (RACE_MURLOC - 1);

    // The murlocs' questline (tools/murloc/): its last quest (from the world database's table the
    // module's SQL makes), and the towns' factions a murloc starts with (tools/build_data.py's
    // MURLOC_HOSTILE_FACTIONS): the Alliance's and the Horde's Hostile (at war: their guards attack it,
    // and it them), the goblins' Unfriendly (mercenary: they don't trade with it, nor attack it).
    constexpr int32 MURLOC_START_STANDING = -3500, MURLOC_GOBLIN_STANDING = -500;
    uint32 const MURLOC_HOSTILE_FACTIONS[] = { 469, 72, 47, 69, 54, 930, 67, 76, 81, 68, 530, 911, 169, 21, 369, 470, 577 };
    uint32 const GOBLIN_FACTIONS[] = { 169, 21, 369, 470, 577 };
    uint32 const ALLIANCE_FACTIONS[] = { 469, 72, 47, 69, 54, 930 };
    uint32 finalQuest = 0;

    int32 StartStanding(uint32 faction)
    {
        for (uint32 id : GOBLIN_FACTIONS)
            if (id == faction)
                return MURLOC_GOBLIN_STANDING;
        return MURLOC_START_STANDING;
    }

    // The tribe's graveyards (the story's: tools/murloc/, the table its SQL makes): where a murloc whose
    // story isn't done wakes as a ghost, the nearest on its map whose quest rule holds, never a town's.
    struct TribeGraveyard
    {
        uint32 id, map;
        float x, y, z;
        uint32 fromQuest, untilQuest;
    };
    std::vector<TribeGraveyard> tribeGraveyards;

    void LoadTribeGraveyards()
    {
        tribeGraveyards.clear();
        if (!WorldDatabase.Query("SHOW TABLES LIKE 'murloc_naga_graveyards'"))
            return;
        if (QueryResult r = WorldDatabase.Query("SELECT `id`, `map`, `x`, `y`, `z`, `from_quest`, `until_quest` FROM `murloc_naga_graveyards`"))
            do
            {
                Field* f = r->Fetch();
                tribeGraveyards.push_back({ f[0].Get<uint32>(), f[1].Get<uint32>(), f[2].Get<float>(), f[3].Get<float>(), f[4].Get<float>(),
                    f[5].Get<uint32>(), f[6].Get<uint32>() });
            } while (r->NextRow());
    }

    uint32 StoryValue(char const* name)
    {
        if (QueryResult r = WorldDatabase.Query("SELECT `value` FROM `murloc_naga_story` WHERE `name` = '{}'", name))
            return r->Fetch()[0].Get<uint32>();
        return 0;
    }

    bool Murloc(Player const* player)
    {
        return player && player->getRace() == RACE_MURLOC;
    }

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
    MurlocNagaWorld() : WorldScript("MurlocNagaWorld", { WORLDHOOK_ON_BEFORE_CONFIG_LOAD, WORLDHOOK_ON_AFTER_CONFIG_LOAD,
                                                         WORLDHOOK_ON_LOAD_CUSTOM_DATABASE_TABLE }) { }

    // Before the quests and their conditions are read: the murlocs' gate, made again from every quest
    // the world database has now (other modules' too). A murloc takes no Alliance or Horde quest, nor
    // any open to all, until its own story's last quest is done; then the Alliance's (those open to all
    // of the Alliance's races) and those open to all are its too.
    void OnLoadCustomDatabaseTable() override
    {
        finalQuest = 0;
        tribeGraveyards.clear();
        if (!Enabled() || !WorldDatabase.Query("SHOW TABLES LIKE 'murloc_naga_story'"))
            return;
        LoadTribeGraveyards();
        finalQuest = StoryValue("final_quest");
        uint32 first = StoryValue("first_quest"), last = StoryValue("last_quest");
        if (!finalQuest || !first || !last)
            return;
        std::string const gate = "'mod-murloc-naga: gate'";
        std::string const ours = fmt::format("q.`ID` NOT BETWEEN {} AND {} AND q.`ID` NOT BETWEEN 975000 AND 975999", first, last);
        std::string const gated = fmt::format("(q.`AllowableRaces` = 0 OR q.`AllowableRaces` & {}) AND {}", MURLOC_RACE_BIT, ours);
        std::string const cols = "(`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`, `ConditionTypeOrReference`, "
            "`ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`, `NegativeCondition`, `ErrorType`, `ErrorTextId`, "
            "`ScriptName`, `Comment`)";
        std::string const existing = "SELECT DISTINCT `SourceEntry`, `ElseGroup` FROM `conditions` WHERE `SourceTypeOrReferenceId` = 19 "
            "AND `SourceGroup` = 0 AND `Comment` <> " + gate;
        WorldDatabase.DirectExecute("DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 19 AND `Comment` = " + gate);
        // (the Alliance's quests, open to all of its races: the murlocs' too, once the gate lets them)
        WorldDatabase.DirectExecute(fmt::format("UPDATE `quest_template` q SET q.`AllowableRaces` = q.`AllowableRaces` | {} WHERE "
            "(q.`AllowableRaces` & 1101) = 1101 AND (q.`AllowableRaces` & 0x7FFFFFFF) <> 0x7FFFFFFF AND {}", MURLOC_RACE_BIT, ours));
        // not a murloc: each group the quest has, or one of its own
        WorldDatabase.DirectExecute(fmt::format("INSERT INTO `conditions` {} SELECT 19, 0, q.`ID`, 0, g.`ElseGroup`, 16, 0, {}, 0, 0, 1, 0, 0, '', {} "
            "FROM `quest_template` q JOIN ({}) g ON g.`SourceEntry` = q.`ID` WHERE {}", cols, MURLOC_RACE_BIT, gate, existing, gated));
        WorldDatabase.DirectExecute(fmt::format("INSERT INTO `conditions` {} SELECT 19, 0, q.`ID`, 0, 0, 16, 0, {}, 0, 0, 1, 0, 0, '', {} "
            "FROM `quest_template` q WHERE {} AND NOT EXISTS (SELECT 1 FROM `conditions` c WHERE c.`SourceTypeOrReferenceId` = 19 "
            "AND c.`SourceGroup` = 0 AND c.`SourceEntry` = q.`ID` AND c.`Comment` <> {})", cols, MURLOC_RACE_BIT, gate, gated, gate));
        // or a murloc whose story is done: each group again, with that
        WorldDatabase.DirectExecute(fmt::format("INSERT INTO `conditions` {} SELECT c.`SourceTypeOrReferenceId`, c.`SourceGroup`, c.`SourceEntry`, "
            "c.`SourceId`, c.`ElseGroup` + 1000, c.`ConditionTypeOrReference`, c.`ConditionTarget`, c.`ConditionValue1`, c.`ConditionValue2`, "
            "c.`ConditionValue3`, c.`NegativeCondition`, c.`ErrorType`, c.`ErrorTextId`, c.`ScriptName`, {} FROM `conditions` c "
            "JOIN `quest_template` q ON q.`ID` = c.`SourceEntry` WHERE c.`SourceTypeOrReferenceId` = 19 AND c.`SourceGroup` = 0 "
            "AND c.`Comment` <> {} AND {}", cols, gate, gate, gated));
        WorldDatabase.DirectExecute(fmt::format("INSERT INTO `conditions` {} SELECT 19, 0, q.`ID`, 0, g.`ElseGroup` + 1000, 8, 0, {}, 0, 0, 0, 0, 0, '', {} "
            "FROM `quest_template` q JOIN ({}) g ON g.`SourceEntry` = q.`ID` WHERE {}", cols, finalQuest, gate, existing, gated));
        WorldDatabase.DirectExecute(fmt::format("INSERT INTO `conditions` {} SELECT 19, 0, q.`ID`, 0, 1000, 8, 0, {}, 0, 0, 0, 0, 0, '', {} "
            "FROM `quest_template` q WHERE {} AND NOT EXISTS (SELECT 1 FROM `conditions` c WHERE c.`SourceTypeOrReferenceId` = 19 "
            "AND c.`SourceGroup` = 0 AND c.`SourceEntry` = q.`ID` AND c.`Comment` <> {})", cols, finalQuest, gate, gated, gate));
        if (QueryResult r = WorldDatabase.Query("SELECT COUNT(DISTINCT `SourceEntry`) FROM `conditions` WHERE `Comment` = " + gate))
            LOG_INFO("module", "Murlocs and Naga: the murlocs' gate is on {} quests (open once quest {} is done)", r->Fetch()[0].Get<uint64>(), finalQuest);
    }

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

// What a town guard says to a murloc it doesn't like the look of, in its own people's language (which
// a murloc doesn't speak: the game garbles it), as it comes near; the guard attacks only at a few yards
// (Low Profile, the module's aura in towns).
namespace
{
    char const* const WARNINGS[] = {
        "Halt! Not one step closer, murloc!",
        "You there! Back to the water, fish-face!",
        "Stop right there. Turn around. Now.",
        "No murlocs in town. Go on, get!",
        "One more step and I'll have your fins for a hat!",
        "I see you, little gurgler. Don't you dare.",
    };
    char const* const NODS[] = {
        "Mind yourself, little one.",
        "Oh, it's you. Behave.",
        "Keep out of trouble, eh?",
    };

    // The guard's own annoyed voice (the game's NPC voices: its people's guards').
    uint32 GuardVoice(Creature const* guard)
    {
        FactionTemplateEntry const* ft = guard->GetFactionTemplateEntry();
        switch (ft ? ft->faction : 0)
        {
            case 47: case 54: return 5912;                                   // dwarf guard
            case 69: return 6007;                                            // Sentinel
            case 930: return 5973;
            case 21: case 369: case 470: case 577: case 169: return 5963;  // goblin bruiser
            case 68: return 6041;                                            // Deathguard
            case 81: return 6010;                                            // tauren
            case 67: case 76: case 530: case 911: return 6020;               // grunt
            default: break;
        }
        return (ft && (ft->ourMask & FACTION_MASK_HORDE)) ? 6020 : 5973;
    }

    Language GuardLanguage(Creature const* guard)
    {
        FactionTemplateEntry const* ft = guard->GetFactionTemplateEntry();
        switch (ft ? ft->faction : 0)
        {
            case 69: return LANG_DARNASSIAN;
            case 47: return LANG_DWARVISH;
            case 54: return LANG_GNOMISH;
            case 930: return LANG_DRAENEI;
            case 67: case 76: return LANG_ORCISH;
            case 81: return LANG_TAURAHE;
            case 68: return LANG_GUTTERSPEAK;
            case 530: return LANG_TROLL;
            case 911: return LANG_THALASSIAN;
            default: break;
        }
        if (ft && (ft->ourMask & FACTION_MASK_HORDE))
            return LANG_ORCISH;
        return LANG_COMMON;
    }

    struct GuardsNear
    {
        WorldObject const* me;
        float range;
        bool operator()(Creature* c) const
        {
            return c->IsAlive() && c->IsGuard() && !c->IsInCombat() && me->IsWithinDistInMap(c, range);
        }
    };

    std::unordered_map<ObjectGuid::LowType, uint32> sinceCheck;            // player -> ms
    std::unordered_map<uint64, time_t> lastSaid;                             // guard x player -> when
}

class MurlocNagaPlayer : public PlayerScript
{
public:
    MurlocNagaPlayer() : PlayerScript("MurlocNagaPlayer", { PLAYERHOOK_ON_FIRST_LOGIN, PLAYERHOOK_ON_LEVEL_CHANGED, PLAYERHOOK_ON_LOGIN,
                                                            PLAYERHOOK_ON_UPDATE, PLAYERHOOK_ON_PLAYER_COMPLETE_QUEST, PLAYERHOOK_ON_LOGOUT,
                                                            PLAYERHOOK_ON_BEFORE_CHOOSE_GRAVEYARD }) { }

    // A murloc whose story isn't done wakes at the tribe's nearest graveyard on its map (one whose
    // quest rule holds), not a town's; elsewhere, the game's.
    void OnPlayerBeforeChooseGraveyard(Player* player, TeamId /*teamId*/, bool nearCorpse, uint32& graveyardOverride) override
    {
        if (!Murloc(player) || !Enabled() || !finalQuest || tribeGraveyards.empty() || player->GetQuestRewardStatus(finalQuest))
            return;
        WorldLocation loc = nearCorpse ? player->GetCorpseLocation() : player->GetWorldLocation();
        float best = 0.0f;
        for (TribeGraveyard const& g : tribeGraveyards)
        {
            if (g.map != loc.GetMapId() || (g.fromQuest && !player->GetQuestRewardStatus(g.fromQuest))
                || (g.untilQuest && player->GetQuestRewardStatus(g.untilQuest)))
                continue;
            float dx = g.x - loc.GetPositionX(), dy = g.y - loc.GetPositionY();
            float d = dx * dx + dy * dy;
            if (!graveyardOverride || d < best)
            {
                graveyardOverride = g.id;
                best = d;
            }
        }
    }

    // They start at level 10, with their class's spells to that level, whole. A murloc starts Hostile
    // with the towns of the Alliance, the Horde and the goblins (at war: their guards attack it, and it
    // them), until it earns better.
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
        if (Murloc(player) && finalQuest)
            for (uint32 id : MURLOC_HOSTILE_FACTIONS)
                if (FactionEntry const* faction = sFactionStore.LookupEntry(id))
                    player->GetReputationMgr().SetReputation(faction, float(StartStanding(id)));
    }

    // The naga learn their class's spells as they come of age for them; the murlocs, from the tribe's
    // trainers (their story's own).
    void OnPlayerLevelChanged(Player* player, uint8 /*oldLevel*/) override
    {
        if (Ours(player) && Enabled() && !(Murloc(player) && finalQuest))
            TeachClassSpells(player);
    }

    // (what a talent learned since opens, and spells a newer release teaches)
    void OnPlayerLogin(Player* player) override
    {
        if (Ours(player) && Enabled() && !player->HasAtLoginFlag(AT_LOGIN_FIRST) && !(Murloc(player) && finalQuest))
            TeachClassSpells(player);
        if (Murloc(player) && Enabled() && finalQuest && !player->HasAtLoginFlag(AT_LOGIN_FIRST) && !player->HasSkill(SKILL_LANG_NERGLISH))
            Migrate(player);
    }

    // A murloc made before the questline (the island village off Auberdine, since retired): once, it
    // speaks Nerglish and not Common, is Hostile with the towns like any murloc, and goes to the start
    // of the story if it's still in Darkshore.
    static constexpr uint16 SKILL_LANG_NERGLISH = 140, SKILL_LANG_COMMON_ID = 98;
    static constexpr uint32 SPELL_LANG_NERGLISH = 816, SPELL_LANG_COMMON = 668;

    void Migrate(Player* player)
    {
        player->SetSkill(SKILL_LANG_NERGLISH, 0, 300, 300);
        if (!player->HasSpell(SPELL_LANG_NERGLISH))
            player->learnSpell(SPELL_LANG_NERGLISH);
        if (player->HasSkill(SKILL_LANG_COMMON_ID))
            player->SetSkill(SKILL_LANG_COMMON_ID, 0, 0, 0);
        if (player->HasSpell(SPELL_LANG_COMMON))
            player->removeSpell(SPELL_LANG_COMMON, SPEC_MASK_ALL, false);
        for (uint32 id : MURLOC_HOSTILE_FACTIONS)
            if (FactionEntry const* faction = sFactionStore.LookupEntry(id))
                if (player->GetReputationMgr().GetReputation(faction) > StartStanding(id))
                    player->GetReputationMgr().SetReputation(faction, float(StartStanding(id)));
        LOG_INFO("module", "Murlocs and Naga: {} (a murloc of an earlier release) speaks Nerglish now, and is Hostile with the towns",
            player->GetName());
        if (player->GetZoneId() == 148)                                     // (Darkshore: the retired village)
            if (PlayerInfo const* info = sObjectMgr->GetPlayerInfo(player->getRace(), player->getClass()))
                player->TeleportTo(info->mapId, info->positionX, info->positionY, info->positionZ, info->orientation);
    }

    void OnPlayerLogout(Player* player) override
    {
        sinceCheck.erase(player->GetGUID().GetCounter());
    }

    // The murlocs' story done: the Alliance takes them in (its towns' standing Neutral at least).
    void OnPlayerCompleteQuest(Player* player, Quest const* quest) override
    {
        if (!Murloc(player) || !finalQuest || quest->GetQuestId() != finalQuest)
            return;
        for (uint32 id : ALLIANCE_FACTIONS)
            if (FactionEntry const* faction = sFactionStore.LookupEntry(id))
                if (player->GetReputationMgr().GetReputation(faction) < 0)
                    player->GetReputationMgr().SetReputation(faction, 0.0f);
    }

    // Town guards near a murloc: one that doesn't like it warns it off, in its own language; one that
    // does, nods.
    void OnPlayerUpdate(Player* player, uint32 diff) override
    {
        if (!Murloc(player) || !Enabled() || !finalQuest || !player->IsAlive() || player->IsInCombat() || player->IsGameMaster())
            return;
        uint32& since = sinceCheck[player->GetGUID().GetCounter()];
        since += diff;
        if (since < 1000)
            return;
        since = 0;
        std::list<Creature*> guards;
        GuardsNear check{ player, 20.0f };
        Acore::CreatureListSearcher<GuardsNear> searcher(player, guards, check);
        Cell::VisitObjects(player, searcher, 20.0f);
        time_t now = time(nullptr);
        for (Creature* guard : guards)
        {
            if (!guard->IsWithinLOSInMap(player))
                continue;
            uint64 key = (uint64(guard->GetSpawnId()) << 32) ^ player->GetGUID().GetCounter();
            time_t& when = lastSaid[key];
            bool hostile = guard->IsHostileTo(player);
            if (now - when < (hostile ? 25 : 120))
                continue;
            when = now;
            guard->SetFacingToObject(player);
            if (hostile)
            {
                guard->HandleEmoteCommand(urand(0, 1) ? EMOTE_ONESHOT_POINT : EMOTE_ONESHOT_SHOUT);
                guard->PlayDirectSound(GuardVoice(guard), player);
                guard->Say(WARNINGS[urand(0, std::size(WARNINGS) - 1)], GuardLanguage(guard), player);
            }
            else
            {
                guard->HandleEmoteCommand(EMOTE_ONESHOT_SALUTE);
                guard->Say(NODS[urand(0, std::size(NODS) - 1)], GuardLanguage(guard), player);
            }
            break;                                                           // (one guard at a time)
        }
    }
};

void AddMurlocNagaScripts()
{
    new MurlocNagaWorld();
    new MurlocNagaPlayer();
}
