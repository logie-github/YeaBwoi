// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Wrath Remix: the realm gate, its tables, Timerunners and their Bronze, the chapters' creature
 * levels, and the hooks that tie them to the game. The NPCs are in RemixNpcs.cpp.
 *
 * Everything here does nothing unless WrathRemix.Enable is on and this world server is the realm
 * WrathRemix.RealmID names: the same program runs the app's ChromieCraft realm, where Remix's
 * tables don't even exist.
 */

#include "WrathRemix.h"
#include "CellImpl.h"
#include "Chat.h"
#include "ChatCommand.h"
#include "Config.h"
#include "Creature.h"
#include "CreatureData.h"
#include "DatabaseEnv.h"
#include "Formulas.h"
#include "GridNotifiers.h"
#include "GridNotifiersImpl.h"
#include "Log.h"
#include "LootMgr.h"
#include "Map.h"
#include "ObjectAccessor.h"
#include "ObjectMgr.h"
#include "Player.h"
#include "QuestDef.h"
#include "ScriptMgr.h"
#include "SpellInfo.h"
#include "StringFormat.h"
#include "World.h"
#include <algorithm>
#include <cmath>
#include <map>
#include <mutex>

namespace WrathRemix
{
    namespace
    {
        bool enabled = false;
        bool bandsEnabled = true;
        bool announce = true;
        bool freeRiding = true;
        BronzeRates rates;

        std::map<std::pair<uint32, uint32>, LevelBand> bands;
        std::unordered_map<uint32, Bridge> bridges;
        std::vector<Chapter> chapters;
        std::vector<Destination> destinations;
        std::vector<Ware> wares;
        std::unordered_map<uint32, uint32> questBronze;

        std::mutex timerunnersLock;
        std::unordered_map<ObjectGuid::LowType, Timerunner> timerunners;

        // Riding, taught for free as a Timerunner reaches each level (WrathRemix.FreeRiding).
        struct Riding { uint8 level; uint32 spell; };
        constexpr Riding RIDING[] = {
            { 20, 33388 },  // Apprentice Riding
            { 40, 33391 },  // Journeyman Riding
            { 60, 34090 },  // Expert Riding
            { 70, 34091 },  // Artisan Riding
            { 77, 54197 },  // Cold Weather Flying
        };

        // The death knights' last quests of Acherus' story: after it, Moratari brings them to the Bazaar.
        constexpr uint32 QUEST_WHERE_KINGS_WALK = 13188;
        constexpr uint32 QUEST_WARCHIEFS_BLESSING = 13189;
    }

    bool Active() { return enabled; }
    bool BandsActive() { return enabled && bandsEnabled; }
    BronzeRates const& Rates() { return rates; }
    bool Announce() { return announce; }

    void LoadConfig()
    {
        uint32 realmId = sConfigMgr->GetOption<uint32>("RealmID", 0, false);
        uint32 remixRealm = sConfigMgr->GetOption<uint32>("WrathRemix.RealmID", 2, false);
        enabled = sConfigMgr->GetOption<bool>("WrathRemix.Enable", false, false) && realmId == remixRealm;
        bandsEnabled = sConfigMgr->GetOption<bool>("WrathRemix.LevelBands.Enable", true, false);
        announce = sConfigMgr->GetOption<bool>("WrathRemix.Announce", true, false);
        freeRiding = sConfigMgr->GetOption<bool>("WrathRemix.FreeRiding", true, false);
        rates.quest = sConfigMgr->GetOption<uint32>("WrathRemix.Bronze.Quest", 25, false);
        rates.kill = sConfigMgr->GetOption<uint32>("WrathRemix.Bronze.Kill", 1, false);
        rates.elite = sConfigMgr->GetOption<uint32>("WrathRemix.Bronze.Elite", 4, false);
        rates.rare = sConfigMgr->GetOption<uint32>("WrathRemix.Bronze.Rare", 20, false);
        rates.dungeonBoss = sConfigMgr->GetOption<uint32>("WrathRemix.Bronze.DungeonBoss", 40, false);
        rates.raidBoss = sConfigMgr->GetOption<uint32>("WrathRemix.Bronze.RaidBoss", 150, false);
        if (enabled)
            LOG_INFO("module", "Wrath Remix: on (realm {})", realmId);
        else
            LOG_INFO("module", "Wrath Remix: off (realm {}; Wrath Remix runs on realm {} when turned on)", realmId, remixRealm);
    }

    void LoadData()
    {
        bands.clear();
        bridges.clear();
        chapters.clear();
        destinations.clear();
        wares.clear();
        questBronze.clear();
        if (!enabled)
            return;

        if (QueryResult result = WorldDatabase.Query("SELECT `map`, `zone`, `original_min`, `original_max`, `band_min`, `band_max`, `chapter`, `name` FROM `remix_level_band`"))
        {
            do
            {
                Field* f = result->Fetch();
                bands[{ f[0].Get<uint32>(), f[1].Get<uint32>() }] = { f[2].Get<int32>(), f[3].Get<int32>(), f[4].Get<int32>(), f[5].Get<int32>(), f[6].Get<uint8>(), f[7].Get<std::string>() };
            } while (result->NextRow());
        }
        if (QueryResult result = WorldDatabase.Query("SELECT `quest`, `next_quest`, `chapter`, `map`, `x`, `y`, `z`, `o`, `skipped` FROM `remix_bridge`"))
        {
            do
            {
                Field* f = result->Fetch();
                Bridge b{ f[0].Get<uint32>(), f[1].Get<uint32>(), f[2].Get<uint8>(), f[3].Get<uint32>(), f[4].Get<float>(), f[5].Get<float>(), f[6].Get<float>(), f[7].Get<float>(), f[8].Get<std::string>() };
                bridges[b.quest] = b;
            } while (result->NextRow());
        }
        if (QueryResult result = WorldDatabase.Query("SELECT `chapter`, `name`, `summary`, `level_min`, `level_max`, `alliance_map`, `alliance_x`, `alliance_y`, `alliance_z`, `horde_map`, `horde_x`, `horde_y`, `horde_z` FROM `remix_chapter` ORDER BY `chapter`"))
        {
            do
            {
                Field* f = result->Fetch();
                Chapter c;
                c.chapter = f[0].Get<uint8>();
                c.name = f[1].Get<std::string>();
                c.summary = f[2].Get<std::string>();
                c.levelMin = f[3].Get<uint8>();
                c.levelMax = f[4].Get<uint8>();
                for (int side = 0; side < 2; ++side)
                {
                    c.map[side] = f[5 + side * 4].Get<uint32>();
                    c.x[side] = f[6 + side * 4].Get<float>();
                    c.y[side] = f[7 + side * 4].Get<float>();
                    c.z[side] = f[8 + side * 4].Get<float>();
                }
                chapters.push_back(c);
            } while (result->NextRow());
        }
        if (QueryResult result = WorldDatabase.Query("SELECT `id`, `name`, `side`, `min_level`, `map`, `x`, `y`, `z`, `o` FROM `remix_travel` ORDER BY `id`"))
        {
            do
            {
                Field* f = result->Fetch();
                destinations.push_back({ f[0].Get<uint16>(), f[1].Get<std::string>(), f[2].Get<uint8>(), f[3].Get<uint8>(), f[4].Get<uint32>(), f[5].Get<float>(), f[6].Get<float>(), f[7].Get<float>(), f[8].Get<float>() });
            } while (result->NextRow());
        }
        if (QueryResult result = WorldDatabase.Query("SELECT `id`, `category`, `item`, `count`, `price`, `side` FROM `remix_shop` ORDER BY `category`, `id`"))
        {
            do
            {
                Field* f = result->Fetch();
                wares.push_back({ f[0].Get<uint16>(), f[1].Get<uint8>(), f[2].Get<uint32>(), f[3].Get<uint32>(), f[4].Get<uint32>(), f[5].Get<uint8>() });
            } while (result->NextRow());
        }
        if (QueryResult result = WorldDatabase.Query("SELECT `quest`, `bronze` FROM `remix_quest_reward`"))
        {
            do
            {
                Field* f = result->Fetch();
                questBronze[f[0].Get<uint32>()] = f[1].Get<uint32>();
            } while (result->NextRow());
        }
        LOG_INFO("module", "Wrath Remix: {} level bands, {} transitions, {} chapters, {} destinations, {} wares",
            bands.size(), bridges.size(), chapters.size(), destinations.size(), wares.size());
    }

    LevelBand const* BandFor(uint32 map, uint32 zone)
    {
        auto it = bands.find({ map, zone });
        if (it == bands.end())
            it = bands.find({ map, 0 });
        return it == bands.end() ? nullptr : &it->second;
    }

    Bridge const* BridgeFor(uint32 quest)
    {
        auto it = bridges.find(quest);
        return it == bridges.end() ? nullptr : &it->second;
    }

    std::vector<Chapter> const& Chapters() { return chapters; }

    Chapter const* ChapterFor(uint8 chapter)
    {
        for (Chapter const& c : chapters)
            if (c.chapter == chapter)
                return &c;
        return nullptr;
    }

    std::vector<Destination> const& Destinations() { return destinations; }

    Destination const* DestinationFor(uint16 id)
    {
        for (Destination const& d : destinations)
            if (d.id == id)
                return &d;
        return nullptr;
    }

    std::vector<Ware> const& Wares() { return wares; }

    Ware const* WareFor(uint16 id)
    {
        for (Ware const& w : wares)
            if (w.id == id)
                return &w;
        return nullptr;
    }

    uint32 QuestBronze(uint32 quest)
    {
        auto it = questBronze.find(quest);
        return it == questBronze.end() ? rates.quest : it->second;
    }

    int32 BandedLevel(LevelBand const& band, int32 level)
    {
        if (level <= 0 || level < band.originalMin - 5)
            return level;
        double span = std::max(1, band.originalMax - band.originalMin);
        int32 banded = int32(std::lround(band.bandMin + (level - band.originalMin) * (band.bandMax - band.bandMin) / span));
        return std::clamp(banded, 1, band.bandMax + 3);
    }

    uint8 ChapterForLevel(uint8 level)
    {
        uint8 found = 0;
        for (Chapter const& c : chapters)
            if (level >= c.levelMin)
                found = c.chapter;
        return found;
    }

    bool IsAlliance(Player* player) { return player->GetTeamId() == TEAM_ALLIANCE; }

    Timerunner Get(Player* player)
    {
        std::lock_guard<std::mutex> lock(timerunnersLock);
        auto it = timerunners.find(player->GetGUID().GetCounter());
        return it == timerunners.end() ? Timerunner() : it->second;
    }

    void Load(Player* player)
    {
        Timerunner t;
        if (QueryResult result = CharacterDatabase.Query("SELECT `bronze`, `bronze_earned`, `chapter` FROM `remix_character` WHERE `guid` = {}", player->GetGUID().GetCounter()))
        {
            Field* f = result->Fetch();
            t.bronze = f[0].Get<uint32>();
            t.earned = f[1].Get<uint32>();
            t.chapter = f[2].Get<uint8>();
        }
        else
            t.dirty = true;
        std::lock_guard<std::mutex> lock(timerunnersLock);
        timerunners[player->GetGUID().GetCounter()] = t;
    }

    void Save(Player* player)
    {
        Timerunner t;
        {
            std::lock_guard<std::mutex> lock(timerunnersLock);
            auto it = timerunners.find(player->GetGUID().GetCounter());
            if (it == timerunners.end() || !it->second.dirty)
                return;
            it->second.dirty = false;
            t = it->second;
        }
        CharacterDatabase.Execute("REPLACE INTO `remix_character` (`guid`, `bronze`, `bronze_earned`, `chapter`) VALUES ({}, {}, {}, {})",
            player->GetGUID().GetCounter(), t.bronze, t.earned, t.chapter);
    }

    void Forget(Player* player)
    {
        std::lock_guard<std::mutex> lock(timerunnersLock);
        timerunners.erase(player->GetGUID().GetCounter());
    }

    void AddBronze(Player* player, uint32 amount, bool show)
    {
        if (!amount)
            return;
        uint32 now;
        {
            std::lock_guard<std::mutex> lock(timerunnersLock);
            Timerunner& t = timerunners[player->GetGUID().GetCounter()];
            t.bronze += amount;
            t.earned += amount;
            t.dirty = true;
            now = t.bronze;
        }
        if (show)
            ChatHandler(player->GetSession()).PSendSysMessage("|cffd4a017You receive {} Bronze.|r (You have {}.)", amount, now);
    }

    bool SpendBronze(Player* player, uint32 amount)
    {
        {
            std::lock_guard<std::mutex> lock(timerunnersLock);
            Timerunner& t = timerunners[player->GetGUID().GetCounter()];
            if (t.bronze < amount)
                return false;
            t.bronze -= amount;
            t.dirty = true;
        }
        Save(player);
        return true;
    }

    void SetChapter(Player* player, uint8 chapter)
    {
        {
            std::lock_guard<std::mutex> lock(timerunnersLock);
            Timerunner& t = timerunners[player->GetGUID().GetCounter()];
            if (chapter <= t.chapter)
                return;
            t.chapter = chapter;
            t.dirty = true;
        }
        Save(player);
        if (Chapter const* c = ChapterFor(chapter))
            ChatHandler(player->GetSession()).PSendSysMessage("|cff8fd3ffChapter {}: {}.|r {}", uint32(c->chapter), c->name, c->summary);
    }

    void Record(Player* player, char const* what, uint32 entry, int32 amount)
    {
        CharacterDatabase.Execute("INSERT INTO `remix_log` (`guid`, `what`, `entry`, `amount`) VALUES ({}, '{}', {}, {})",
            player->GetGUID().GetCounter(), what, entry, amount);
    }

    void TeleportSoon(Player* player, uint32 map, float x, float y, float z, float o)
    {
        player->m_Events.AddEventAtOffset([player, map, x, y, z, o]()
        {
            if (player->IsInWorld() && player->IsAlive())
                player->TeleportTo(map, x, y, z, o);
        }, 1500ms);
    }

    // The creature behind a blow or a heal when its level came from a band: a creature, or the
    // totem or guardian of one (never a player's pet, guardian or totem).
    static Creature* BandedSource(Unit* unit)
    {
        if (!unit)
            return nullptr;
        if (Unit* owner = unit->GetOwner())
        {
            if (owner->IsPlayer())
                return nullptr;
            unit = owner;
        }
        Creature* creature = unit->ToCreature();
        if (!creature || creature->IsPet())
            return nullptr;
        CreatureTemplate const* info = creature->GetCreatureTemplate();
        if (!info || creature->GetLevel() >= std::min(info->minlevel, info->maxlevel))
            return nullptr;
        return creature;
    }

    // How much of its spells' power a banded creature keeps: its damage at its level now over its
    // damage at its own level (creature_classlevelstats), as its melee already does.
    static float BandFactor(Creature* creature)
    {
        CreatureTemplate const* info = creature->GetCreatureTemplate();
        uint8 original = uint8((info->minlevel + info->maxlevel) / 2);
        CreatureBaseStats const* now = sObjectMgr->GetCreatureBaseStats(creature->GetLevel(), info->unit_class);
        CreatureBaseStats const* was = sObjectMgr->GetCreatureBaseStats(original, info->unit_class);
        uint32 expansion = std::min<uint32>(info->expansion, MAX_EXPANSIONS - 1);
        if (!now || !was || was->BaseDamage[expansion] <= 0.0f)
            return 1.0f;
        return std::clamp(now->BaseDamage[expansion] / was->BaseDamage[expansion], 0.01f, 1.0f);
    }

    template<typename T>
    static void Scale(T& amount, Unit* source)
    {
        if (!BandsActive() || !amount)
            return;
        if (Creature* creature = BandedSource(source))
            amount = T(std::max<double>(1.0, std::round(amount * BandFactor(creature))));
    }

    static void TeachRiding(Player* player)
    {
        if (!freeRiding)
            return;
        for (Riding const& r : RIDING)
            if (player->GetLevel() >= r.level && !player->HasSpell(r.spell))
                player->learnSpell(r.spell);
    }
}

using namespace WrathRemix;

class WrathRemixWorld : public WorldScript
{
public:
    WrathRemixWorld() : WorldScript("WrathRemixWorld", { WORLDHOOK_ON_AFTER_CONFIG_LOAD, WORLDHOOK_ON_LOAD_CUSTOM_DATABASE_TABLE }) { }

    void OnAfterConfigLoad(bool /*reload*/) override
    {
        LoadConfig();
    }

    void OnLoadCustomDatabaseTable() override
    {
        LoadData();
    }
};

class WrathRemixPlayer : public PlayerScript
{
public:
    WrathRemixPlayer() : PlayerScript("WrathRemixPlayer", {
        PLAYERHOOK_ON_FIRST_LOGIN,
        PLAYERHOOK_ON_LOGIN,
        PLAYERHOOK_ON_LOGOUT,
        PLAYERHOOK_ON_SAVE,
        PLAYERHOOK_ON_LEVEL_CHANGED,
        PLAYERHOOK_ON_QUEST_COMPUTE_EXP,
        PLAYERHOOK_ON_CREATURE_KILL,
        PLAYERHOOK_ON_BEFORE_LOOT_MONEY,
    }) { }

    void OnPlayerFirstLogin(Player* player) override
    {
        if (!Active())
            return;
        ChatHandler handler(player->GetSession());
        if (player->getClass() == CLASS_DEATH_KNIGHT)
            handler.SendSysMessage("|cff8fd3ffWrath Remix.|r Your own story comes first, death knight. When the Ebon Blade lets you go, Moratari will find you.");
        else
            handler.SendSysMessage("|cff8fd3ffWrath Remix.|r Welcome, Timerunner. Moratari is waiting for you.");
    }

    void OnPlayerLogin(Player* player) override
    {
        if (!Active())
            return;
        Load(player);
        TeachRiding(player);
        Timerunner const t = Get(player);
        if (Announce())
        {
            uint8 chapter = std::max(t.chapter, ChapterForLevel(player->GetLevel()));
            Chapter const* c = ChapterFor(chapter);
            ChatHandler(player->GetSession()).PSendSysMessage("|cff8fd3ffWrath Remix|r: Timerunner, {} Bronze. {}", t.bronze,
                c ? Acore::StringFormat("Chapter {}: {}.", uint32(c->chapter), c->name) : std::string("The prologue."));
        }
        Save(player);
    }

    void OnPlayerLogout(Player* player) override
    {
        if (!Active())
            return;
        Save(player);
        Forget(player);
    }

    void OnPlayerSave(Player* player) override
    {
        if (Active())
            Save(player);
    }

    void OnPlayerLevelChanged(Player* player, uint8 /*oldLevel*/) override
    {
        if (!Active())
            return;
        TeachRiding(player);
    }

    // Called once as a quest is turned in (it works out the experience).
    void OnPlayerQuestComputeXP(Player* player, Quest const* quest, uint32& /*xp*/) override
    {
        if (!Active())
            return;
        uint32 id = quest->GetQuestId();
        AddBronze(player, QuestBronze(id), true);

        if (Bridge const* bridge = BridgeFor(id))
        {
            Record(player, "bridge", id, 0);
            if (bridge->chapter)
                SetChapter(player, bridge->chapter);
            if (bridge->map != 0xFFFF)
                TeleportSoon(player, bridge->map, bridge->x, bridge->y, bridge->z, bridge->o);
        }
        else if (id == QUEST_WAR_WE_REMEMBER)
            SetChapter(player, 1);
        else if (id == QUEST_BAZAAR)
        {
            // The hearthstone remembers the Bazaar.
            if (Destination const* bazaar = DestinationFor(TRAVEL_BAZAAR))
                player->SetHomebind(WorldLocation(bazaar->map, bazaar->x, bazaar->y, bazaar->z, bazaar->o), player->GetAreaId());
        }

        // Death knights, done with Acherus: to the Bazaar, at their level's chapter.
        if (id == QUEST_WHERE_KINGS_WALK || id == QUEST_WARCHIEFS_BLESSING)
        {
            if (Destination const* bazaar = DestinationFor(TRAVEL_BAZAAR))
            {
                ChatHandler(player->GetSession()).SendSysMessage("|cff8fd3ffMoratari whispers:|r There you are. Your king will keep. Come, the Bazaar is this way.");
                SetChapter(player, ChapterForLevel(player->GetLevel()));
                TeleportSoon(player, bazaar->map, bazaar->x, bazaar->y, bazaar->z, bazaar->o);
            }
        }
    }

    // A banded creature's gold is its template's, for its own level: it drops its new level's share.
    void OnPlayerBeforeLootMoney(Player* player, Loot* loot) override
    {
        if (!BandsActive() || !loot || !loot->gold || !loot->sourceWorldObjectGUID.IsCreature())
            return;
        if (Creature* creature = BandedSource(ObjectAccessor::GetCreature(*player, loot->sourceWorldObjectGUID)))
            loot->gold = std::max<uint32>(1, uint32(std::lround(loot->gold * BandFactor(creature))));
    }

    void OnPlayerCreatureKill(Player* player, Creature* killed) override
    {
        if (!Active() || !killed || Acore::XP::Gain(player, killed) == 0)
            return;
        BronzeRates const& r = Rates();
        CreatureTemplate const* info = killed->GetCreatureTemplate();
        uint32 amount = r.kill;
        bool show = false;
        if (killed->IsDungeonBoss() || (info && info->rank == CREATURE_ELITE_WORLDBOSS))
        {
            amount = killed->GetMap()->IsRaid() ? r.raidBoss : r.dungeonBoss;
            show = true;
        }
        else if (info && (info->rank == CREATURE_ELITE_RARE || info->rank == CREATURE_ELITE_RAREELITE))
        {
            amount = r.rare;
            show = true;
        }
        else if (info && info->rank == CREATURE_ELITE_ELITE)
            amount = r.elite;
        AddBronze(player, amount, show);
    }
};

// Northrend's creatures at their chapter's level, and their spells to match.
class WrathRemixLevels : public AllCreatureScript
{
public:
    WrathRemixLevels() : AllCreatureScript("WrathRemixLevels") { }

    void OnBeforeCreatureSelectLevel(CreatureTemplate const* /*info*/, Creature* creature, uint8& level) override
    {
        if (!BandsActive() || !creature || creature->IsPet())
            return;
        Map* map = creature->GetMap();
        if (!map || (map->IsDungeon() && map->IsHeroic()) || map->IsRaid())
            return;
        uint32 zone = map->GetZoneId(creature->GetPhaseMask(), creature->GetPositionX(), creature->GetPositionY(), creature->GetPositionZ());
        LevelBand const* band = BandFor(map->GetId(), zone);
        if (!band)
            return;
        level = uint8(BandedLevel(*band, level));
    }
};

class WrathRemixDamage : public UnitScript
{
public:
    WrathRemixDamage() : UnitScript("WrathRemixDamage", true, {
        UNITHOOK_MODIFY_SPELL_DAMAGE_TAKEN,
        UNITHOOK_MODIFY_PERIODIC_DAMAGE_AURAS_TICK,
        UNITHOOK_MODIFY_HEAL_RECEIVED,
    }) { }

    void ModifySpellDamageTaken(Unit* /*target*/, Unit* attacker, int32& damage, SpellInfo const* /*spellInfo*/) override
    {
        if (damage > 0)
            Scale(damage, attacker);
    }

    void ModifyPeriodicDamageAurasTick(Unit* /*target*/, Unit* attacker, uint32& damage, SpellInfo const* /*spellInfo*/) override
    {
        Scale(damage, attacker);
    }

    void ModifyHealReceived(Unit* /*target*/, Unit* healer, uint32& heal, SpellInfo const* /*spellInfo*/) override
    {
        Scale(heal, healer);
    }
};

using namespace Acore::ChatCommands;

class WrathRemixCommands : public CommandScript
{
public:
    WrathRemixCommands() : CommandScript("WrathRemixCommands") { }

    ChatCommandTable GetCommands() const override
    {
        static ChatCommandTable bronzeTable =
        {
            { "add", HandleBronzeAdd, SEC_GAMEMASTER, Console::No },
        };
        static ChatCommandTable remixTable =
        {
            { "",       HandleStatus, SEC_PLAYER,     Console::No },
            { "bronze", bronzeTable },
            { "band",   HandleBand,   SEC_GAMEMASTER, Console::No },
        };
        static ChatCommandTable commandTable =
        {
            { "remix", remixTable },
        };
        return commandTable;
    }

    // .remix: whether Remix runs here, and the Timerunner's Bronze and chapter.
    static bool HandleStatus(ChatHandler* handler)
    {
        Player* player = handler->GetSession()->GetPlayer();
        if (!Active())
        {
            handler->SendSysMessage("Wrath Remix: off on this realm.");
            return true;
        }
        Timerunner const t = Get(player);
        uint8 chapter = std::max(t.chapter, ChapterForLevel(player->GetLevel()));
        Chapter const* c = ChapterFor(chapter);
        handler->PSendSysMessage("Wrath Remix: Timerunner {}, level {}, {} Bronze ({} earned), chapter {} ({}).",
            player->GetName(), uint32(player->GetLevel()), t.bronze, t.earned, uint32(chapter), c ? c->name : "the prologue");
        return true;
    }

    // .remix bronze add N (game masters).
    static bool HandleBronzeAdd(ChatHandler* handler, uint32 amount)
    {
        if (!Active())
            return false;
        Player* player = handler->getSelectedPlayerOrSelf();
        AddBronze(player, amount, true);
        Save(player);
        return true;
    }

    // .remix band (game masters): the creatures within 60 yards, their level and their band.
    static bool HandleBand(ChatHandler* handler)
    {
        Player* player = handler->GetSession()->GetPlayer();
        uint32 zone = player->GetZoneId();
        LevelBand const* band = BandFor(player->GetMapId(), zone);
        if (band)
            handler->PSendSysMessage("Wrath Remix band: {} (zone {}), levels {}-{} become {}-{}.", band->name, zone,
                band->originalMin, band->originalMax, band->bandMin, band->bandMax);
        else
            handler->PSendSysMessage("Wrath Remix band: none here (map {}, zone {}).", player->GetMapId(), zone);
        std::list<Creature*> creatures;
        AnyCreatureInRange check(player, 60.0f);
        Acore::CreatureListSearcher<AnyCreatureInRange> searcher(player, creatures, check);
        Cell::VisitObjects(player, searcher, 60.0f);
        uint32 shown = 0, lowest = 255, highest = 0;
        for (Creature* creature : creatures)
        {
            CreatureTemplate const* info = creature->GetCreatureTemplate();
            if (!info || info->Entry >= 7700000)
                continue;
            lowest = std::min<uint32>(lowest, creature->GetLevel());
            highest = std::max<uint32>(highest, creature->GetLevel());
            if (shown++ < 12)
                handler->PSendSysMessage("creature {} {}: level {} (template {}-{})", info->Entry, info->Name,
                    uint32(creature->GetLevel()), uint32(info->minlevel), uint32(info->maxlevel));
        }
        if (shown)
            handler->PSendSysMessage("Wrath Remix band check: {} creatures, levels {} to {}.", shown, lowest, highest);
        return true;
    }

    struct AnyCreatureInRange
    {
        AnyCreatureInRange(WorldObject const* source, float range) : _source(source), _range(range) { }
        bool operator()(Creature* creature) const { return _source->IsWithinDistInMap(creature, _range); }
        WorldObject const* _source;
        float _range;
    };
};

void AddWrathRemixScripts()
{
    new WrathRemixWorld();
    new WrathRemixPlayer();
    new WrathRemixLevels();
    new WrathRemixDamage();
    new WrathRemixCommands();
}
