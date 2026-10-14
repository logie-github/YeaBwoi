// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Wrath Remix: a Legion Remix-style replay of Wrath of the Lich King on a realm of its own.
 * Part of the World of Warcraft Android app (github.com/logie-github/YeaBwoi). GPL-3.0-or-later.
 */

#ifndef MOD_WRATH_REMIX_H
#define MOD_WRATH_REMIX_H

#include "Define.h"
#include "ObjectGuid.h"
#include <string>
#include <unordered_map>
#include <vector>

class Creature;
class Player;
class Quest;
class Unit;
struct CreatureTemplate;

namespace WrathRemix
{
    // Remix's own ids (the world SQL in data/sql/remix/world).
    enum Ids : uint32
    {
        NPC_MORATARI      = 770001,
        NPC_ETERNUS       = 770002,
        NPC_ANDORMU       = 770003,
        NPC_NOZARI        = 770004,
        NPC_ALURMI        = 770005,
        NPC_ZEPHYR        = 770006,
        NPC_SAAT          = 770007,
        NPC_CHRONOLOGIST  = 770008,
        NPC_MOMENTUS      = 770009,
        NPC_ECHO_LANDING  = 770021,
        NPC_ECHO_WRATHGATE = 770022,
        NPC_ECHO_ICECROWN = 770023,
        NPC_ANOMALY       = 770031,
        NPC_ANOMALY_CREDIT = 770032,    // WRM-003's kill credit, given as each anomaly dies
        NPC_INSTRUCTOR_FIRST = 770101,  // the Bazaar's Timeworn Instructors, one per class trainer list
        NPC_INSTRUCTOR_LAST  = 770111,

        QUEST_WEIGHT_OF_TIME   = 77001,
        QUEST_AGREEMENT        = 77002,
        QUEST_DEMONSTRATION    = 77003,
        QUEST_BAZAAR           = 77004,
        QUEST_WAR_WE_REMEMBER  = 77005,
        QUEST_SHADOW_OF_CITY   = 77007,

        TEXT_MORATARI_PROLOGUE = 7702001,
        TEXT_MORATARI          = 7702002,
        TEXT_MORATARI_HISTORY  = 7702003,
        TEXT_ETERNUS           = 7702010,
        TEXT_ETERNUS_ROUTES    = 7702011,
        TEXT_ANDORMU           = 7702020,
        TEXT_NOZARI            = 7702021,
        TEXT_ALURMI            = 7702030,
        TEXT_ZEPHYR            = 7702040,
        TEXT_SAAT              = 7702050,
        TEXT_CHRONOLOGIST      = 7702060,
        TEXT_MOMENTUS          = 7702070,
        TEXT_ECHO_LANDING      = 7702080,
    };

    // The travel entries the routes of WRM-005 use (remix_travel).
    enum Travel : uint16
    {
        TRAVEL_BAZAAR = 1,
        TRAVEL_VALIANCE_KEEP = 10,
        TRAVEL_WARSONG_HOLD = 11,
        TRAVEL_VALGARDE = 12,
        TRAVEL_VENGEANCE_LANDING = 13,
    };

    struct LevelBand
    {
        int32 originalMin, originalMax, bandMin, bandMax;
        uint8 chapter;
        std::string name;
    };

    struct Bridge
    {
        uint32 quest, nextQuest;
        uint8 chapter;
        uint32 map;
        float x, y, z, o;
        std::string skipped;
    };

    struct Chapter
    {
        uint8 chapter;
        std::string name, summary;
        uint8 levelMin, levelMax;
        uint32 map[2];
        float x[2], y[2], z[2];
    };

    struct Destination
    {
        uint16 id;
        std::string name;
        uint8 side, minLevel;
        uint32 map;
        float x, y, z, o;
    };

    struct Ware
    {
        uint16 id;
        uint8 category;
        uint32 item, count, price;
        uint8 side;
    };

    struct Timerunner
    {
        uint32 bronze = 0;
        uint32 earned = 0;
        uint8 chapter = 0;
        uint8 kit = 0;      // the starter kit's version this Timerunner has had (0: none yet)
        bool dirty = false;
    };

    // A piece of a class's starter kit (remix_starter_kit), and an ability its class quests teach
    // (remix_class_spell).
    struct KitPiece
    {
        uint32 item;
        uint8 count;
    };

    struct ClassSpell
    {
        uint32 spell;
        uint8 level;
    };

    // Whether Wrath Remix runs here: on, and this is its realm.
    bool Active();
    bool BandsActive();

    void LoadConfig();
    void LoadData();

    // The tables, as loaded.
    LevelBand const* BandFor(uint32 map, uint32 zone);
    Bridge const* BridgeFor(uint32 quest);
    std::vector<Chapter> const& Chapters();
    Chapter const* ChapterFor(uint8 chapter);
    std::vector<Destination> const& Destinations();
    Destination const* DestinationFor(uint16 id);
    std::vector<Ware> const& Wares();
    Ware const* WareFor(uint16 id);
    uint32 QuestBronze(uint32 quest);

    // A level in a band: unchanged far below the zone's own levels, else on the straight line
    // from the original range to the band's, at most 3 above its top.
    int32 BandedLevel(LevelBand const& band, int32 level);

    // The chapter a level belongs to (1 to 7), or 0 below the first.
    uint8 ChapterForLevel(uint8 level);

    // Timerunners: loaded at login, saved at logout, on the core's saves, and after purchases.
    // Maps update on several threads: every read is a copy, every change made under a lock.
    Timerunner Get(Player* player);
    void Load(Player* player);
    void Save(Player* player);
    void Forget(Player* player);
    void AddBronze(Player* player, uint32 amount, bool announce);
    bool SpendBronze(Player* player, uint32 amount);
    void SetChapter(Player* player, uint8 chapter);
    void SetKit(Player* player, uint8 version);
    void Record(Player* player, char const* what, uint32 entry, int32 amount);

    // Moves a Timerunner a moment from now (after a quest's reward, a gossip's close).
    void TeleportSoon(Player* player, uint32 map, float x, float y, float z, float o);
    bool IsAlliance(Player* player);

    // Bronze amounts (WrathRemix.Bronze.*).
    struct BronzeRates
    {
        uint32 quest = 25, kill = 1, elite = 4, rare = 20, dungeonBoss = 40, raidBoss = 150;
    };
    BronzeRates const& Rates();
    bool Announce();
}

#endif
