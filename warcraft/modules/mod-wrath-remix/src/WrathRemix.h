// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Wrath Remix: a Legion Remix-style replay of Wrath of the Lich King on a realm of its own.
 * Part of the World of Warcraft Android app (github.com/logie-github/YeaBwoi). GPL-3.0-or-later.
 */

#ifndef MOD_WRATH_REMIX_H
#define MOD_WRATH_REMIX_H

#include "Define.h"
#include "Duration.h"
#include "ObjectGuid.h"
#include <string>
#include <unordered_map>
#include <vector>

class Creature;
class CreatureAI;
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
        NPC_SYLVANAS      = 770011,     // from the future, with the bronze (Chapter II on)
        NPC_ALLERIA       = 770012,
        NPC_MORATARI_SCENE = 770013,    // Moratari as WRM-207's scene has her (no quests, seen by one)
        NPC_SYLVANAS_ABROAD = 770014,   // the same two anywhere but the Bazaar: hooded, passing for
        NPC_ALLERIA_ABROAD  = 770015,   // adventurers (no one may learn who they are)
        NPC_ECHO_FOOTMAN  = 770021,     // WRM-011's echoes of the war's dead
        NPC_ECHO_SAURFANG = 770022,
        NPC_ECHO_BOLVAR   = 770023,
        NPC_ANOMALY       = 770031,
        NPC_ANOMALY_CREDIT = 770032,    // WRM-013's kill credit, given as each anomaly dies
        NPC_INSTRUCTOR_CREDIT = 770033, // WRM-012's, given by any instructor as they're spoken to
        NPC_TURN_CREDIT   = 770034,     // WRM-207's, given as its scene ends
        NPC_INTERLOPER    = 770041,     // Eternus's loyalists on the Wrathgate's cliffs (WRM-208)
        NPC_TIME_RIFT     = 28409,      // the Culling of Stratholme's time rift, for Moratari's return
        NPC_INSTRUCTOR_FIRST = 770101,  // the Bazaar's Timeworn Instructors, one per class trainer list
        NPC_INSTRUCTOR_LAST  = 770111,
        NPC_HOROS         = 770201,     // the Bazaar's Bronze vendors (Alurmi is one too)
        NPC_DOMELIUS      = 770202,
        NPC_ARTUROS       = 770203,
        NPC_AEONICUS      = 770204,
        NPC_DURUS         = 770205,

        AREA_CAVERNS_OF_TIME = 2300,    // the Caverns of Time in Tanaris, where the prologue was until build 64
        ZONE_CRYSTALSONG  = 2817,       // the Bazaar's zone
        AREA_WYRMREST_TEMPLE = 4161,

        ITEM_BRONZE       = 77001,      // Bronze: a currency token, like the emblems
        ITEM_HOURGLASS    = 77002,      // Moratari's Hourglass: to the Bazaar and back (RemixHourglass.cpp)

        QUEST_OUT_OF_TIME      = 77010,     // the prologue at the Bazaar (STORY.md)
        QUEST_WAR_WILL_COST    = 77011,
        QUEST_TIMES_TOLL       = 77012,
        QUEST_DEMONSTRATION    = 77013,
        QUEST_TO_THE_FRONT_A   = 77014,
        QUEST_TO_THE_FRONT_H   = 77015,
        QUEST_MYSTERY_OF_THE_INFINITE = 12470,  // Chromie's, at Wyrmrest Temple (the game's own)
        QUEST_STRANGERS        = 77204,     // Chapter II and the turn (STORY.md)
        QUEST_WEIGHT_OF_TIME   = 77207,
        QUEST_LAST_HOUR        = 77721,     // Chapter VII: Eternus at the doors of Icecrown Citadel
        QUEST_LAST_NECESSARY_BATTLE = 77800,  // the Lich King
        QUEST_SEE_YOU_LATER    = 77801,     // the end (STORY.md)
        QUEST_BACK_TO_YOUR_OWN_TIME = 77802,

        NPC_SCENE_CREDIT  = 770035,     // a scene's credit, for quests that are a scene
        NPC_LAST_HOUR_CREDIT = 770036,  // WRM-721's, as Eternus retreats
        NPC_LAST_ETERNUS  = 770061,     // Eternus at the last hour, his loyalists, and the three beside you
        NPC_LAST_CHRONOMANCER = 770062,
        NPC_LAST_EXECUTIONER = 770063,
        NPC_ALLY_MORATARI = 770064,
        NPC_ALLY_SYLVANAS = 770065,
        NPC_ALLY_ALLERIA  = 770066,

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
        TEXT_SYLVANAS_WYRMREST = 7702090,   // Sylvanas and Alleria at Wyrmrest Temple, at the Bazaar after
        TEXT_SYLVANAS          = 7702091,   // the turn, and Alleria on the Wrathgate's cliffs
        TEXT_ALLERIA_WYRMREST  = 7702092,
        TEXT_ALLERIA           = 7702093,
        TEXT_ALLERIA_GATE      = 7702094,
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

    // A quest that takes the Timerunner somewhere as it's taken (remix_departure): to the Bazaar
    // to answer Eternus, to the Wrathgate's cliffs.
    struct Departure
    {
        uint32 quest;
        uint32 map;
        float x, y, z, o;
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
        // Where they came to the Bazaar from, the last time they came (-1: nowhere yet), for
        // Moratari's Hourglass to take them back to.
        int32 backMap = -1;
        float backX = 0.0f, backY = 0.0f, backZ = 0.0f, backO = 0.0f;
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
    Departure const* DepartureFor(uint32 quest);
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
    void SetWayBack(Player* player, int32 map, float x, float y, float z, float o);
    // A line in a Timerunner's journal (remix_log), where they are: what ("accept", "talk",
    // "bridge"...), its quest, creature or other entry, and an amount.
    void Record(Player* player, char const* what, uint32 entry, int32 amount);

    // The realm's playerbots: sessions without a client.
    bool IsBot(Player* player);

    // The game's patch (Data/patch-Y.MPQ in the app's client): written as Wrath Remix starts,
    // taken out as ChromieCraft starts (ClientPatch.cpp).
    void UpdateClientPatch();

    // Moves a Timerunner a moment from now (after a quest's reward, a gossip's close).
    void TeleportSoon(Player* player, uint32 map, float x, float y, float z, float o);
    bool IsAlliance(Player* player);

    // The story's scenes (RemixScenes.cpp): played as a quest is taken or turned in.
    enum SceneTrigger : uint8
    {
        SCENE_ON_ACCEPT = 1,
        SCENE_ON_REWARD = 2,
    };
    void LoadScenes();
    bool PlayScene(Player* player, uint32 scene);
    bool SceneRunning(Player* player);
    void ScenesForQuest(Player* player, uint32 quest, uint8 trigger);
    bool ReplaySceneAt(Player* player, Creature* creature);

    // The end (RemixEnding.cpp).
    void LoadEnding();
    bool AddEndingGossip(Player* player, Creature* creature);
    bool EndingGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action, uint32 menuText);
    void SendHome(Player* player);
    void ReturnToBazaarSoon(Player* player, Milliseconds after);

    // Moratari's Hourglass (RemixHourglass.cpp): in every Timerunner's bags from the moment they
    // wake at the Bazaar; anywhere, it takes them to the Bazaar; there, back where they came from.
    bool AtTheBazaar(Player* player);
    void GiveHourglass(Player* player);

    // The Bazaar's people talking while a Timerunner is about (RemixBazaar.cpp): the AI the camp's
    // own scripts give them.
    CreatureAI* BazaarLifeAI(Creature* creature);
    std::string HourglassReport(Player* player);
    void TakeInTimerunners();
    void ForgetGoneHome();

    // Eternus's last hour (RemixLastHour.cpp): begun as WRM-721 brings the Timerunner to the doors.
    bool BeginLastHour(Player* player);
    void LastHourAgain(Player* player);

    // Dungeons and raids for however many are there (WrathRemix.Solo.*, RemixSolo.cpp).
    bool SoloActive();
    float SoloDungeonDamage();
    float SoloRaidDamage();
    float SoloMaxBoost();
    // What the scaling makes of the instance the player is in (.remix solo).
    std::string SoloReport(Player* player);

    // Bronze amounts (WrathRemix.Bronze.*): enough over the story to buy everything the Bazaar sells
    // a class, once or twice over.
    struct BronzeRates
    {
        uint32 quest = 4000, kill = 75, elite = 300, rare = 3000, dungeonBoss = 6000, raidBoss = 30000,
            treasure = 1500, achievementPoint = 100;
    };
    BronzeRates const& Rates();
    bool Announce();
}

#endif
