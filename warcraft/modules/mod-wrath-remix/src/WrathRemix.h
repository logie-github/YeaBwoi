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
        NPC_ECHO_FOOTMAN  = 770021,     // WRM-011's echoes of the war's dead
        NPC_ECHO_SAURFANG = 770022,
        NPC_ECHO_BOLVAR   = 770023,
        NPC_ANOMALY       = 770031,
        NPC_ANOMALY_CREDIT = 770032,    // WRM-013's kill credit, given as each anomaly dies
        NPC_INSTRUCTOR_CREDIT = 770033, // WRM-012's, given by any instructor as they're spoken to
        NPC_INSTRUCTOR_FIRST = 770101,  // the Bazaar's Timeworn Instructors, one per class trainer list
        NPC_INSTRUCTOR_LAST  = 770111,
        NPC_HOROS         = 770201,     // the Bazaar's Bronze vendors (Alurmi is one too)
        NPC_DOMELIUS      = 770202,
        NPC_ARTUROS       = 770203,
        NPC_AEONICUS      = 770204,
        NPC_DURUS         = 770205,

        AREA_CAVERNS_OF_TIME = 2300,    // the Caverns of Time in Tanaris, where the prologue was until build 64

        ITEM_BRONZE       = 77001,      // Bronze: a currency token, like the emblems

        QUEST_OUT_OF_TIME      = 77010,     // the prologue at the Bazaar (STORY.md)
        QUEST_WAR_WILL_COST    = 77011,
        QUEST_TIMES_TOLL       = 77012,
        QUEST_DEMONSTRATION    = 77013,
        QUEST_TO_THE_FRONT_A   = 77014,
        QUEST_TO_THE_FRONT_H   = 77015,

        TEXT_MORATARI_PROLOGUE = 7702001,
        TEXT_MORATARI          = 7702002,
        TEXT_MORATARI_HISTORY  = 7702003,
        TEXT_ETERNUS           = 7702010,
        TEXT_ETERNUS_LATER     = 7702011,
        TEXT_ANDORMU           = 7702020,
        TEXT_NOZARI            = 7702021,
        TEXT_ALURMI            = 7702030,
        TEXT_ZEPHYR            = 7702040,
        TEXT_SAAT              = 7702050,
        TEXT_CHRONOLOGIST      = 7702060,
        TEXT_MOMENTUS          = 7702070,
        TEXT_ECHO_FOOTMAN      = 7702080,   // then Saurfang's, Bolvar's
        TEXT_HOROS             = 7702201,   // then Domelius, Arturos, Aeonicus, Durus: 7702201 + n
    };

    // The travel entries the module names (remix_travel).
    enum Travel : uint16
    {
        TRAVEL_BAZAAR = 1,
    };

    // The Infinite Bazaar's middle, where new Timerunners wake (playercreateinfo).
    constexpr float BAZAAR_X = 5700.0f, BAZAAR_Y = 1012.0f, BAZAAR_Z = 174.6f, BAZAAR_O = 1.57f;

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

    // A list of Bronze wares one of the Bazaar's vendors opens (remix_vendor_list): its own
    // hidden vendor template, whose npc_vendor rows are priced in Bronze.
    struct VendorList
    {
        uint32 list, vendor;
        uint8 side;
        std::string label;
    };

    struct Timerunner
    {
        uint32 bronze = 0;  // Bronze owed: what the realm kept before Bronze was an item, or couldn't store
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
    std::vector<VendorList> const& VendorLists();
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
    uint32 Bronze(Player* player);   // what the Timerunner carries
    void SetChapter(Player* player, uint8 chapter);
    void SetKit(Player* player, uint8 version);
    void Record(Player* player, char const* what, uint32 entry, int32 amount);

    // The game's patch (Data/patch-Y.MPQ in the app's client): written as Wrath Remix starts,
    // taken out as ChromieCraft starts (ClientPatch.cpp).
    void UpdateClientPatch();

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
