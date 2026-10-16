// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Wrath Remix's end (STORY.md, "The end"):
 *
 * - The Lich King's fall counts for every Timerunner in his citadel who is on The Last Necessary
 *   Battle; once the ending cinematic is over (the game says so, or long enough has passed), they
 *   are brought back to the Infinite Bazaar, where everyone is waiting (scene 800), and Eternus's
 *   letter arrives.
 * - What Sylvanas, Alleria, Moratari and the Keepers can tell once the war is over
 *   (remix_conversation), and the goodbyes See You Later asks for.
 * - Back to Your Own Time: once its scene is over, the Timerunner goes home, as Legion Remix sends
 *   its characters home. Their character, without its Bronze, is written to WOW_TRANSFERS_DIR
 *   (ctl.sh: files/run/transfers) and taken off Wrath Remix; the game is let go, and the app starts
 *   the server again on ChromieCraft (Homecomings.kt), whose world server takes the character in, in
 *   their side's capital, as it starts.
 */

#include "WrathRemix.h"
#include "Chat.h"
#include "Creature.h"
#include "DatabaseEnv.h"
#include "GossipDef.h"
#include "Log.h"
#include "Mail.h"
#include "Map.h"
#include "ObjectAccessor.h"
#include "ObjectMgr.h"
#include "Opcodes.h"
#include "Player.h"
#include "PlayerDump.h"
#include "QuestDef.h"
#include "ScriptMgr.h"
#include "ScriptedGossip.h"
#include "World.h"
#include "WorldPacket.h"
#include "WorldSession.h"
#include <chrono>
#include <cstdlib>
#include <functional>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <set>

namespace WrathRemix
{
    namespace
    {
        constexpr uint32 NPC_THE_LICH_KING = 36597;
        constexpr uint32 MAP_REMIX_ICECROWN_CITADEL = 631;
        constexpr uint32 SCENE_RETURN = 800;
        // How long after the Lich King's death a Timerunner who never says the cinematic is over is
        // brought back anyway (it starts 11.5 seconds after his death and runs a few minutes).
        constexpr Seconds RETURN_AT_THE_LATEST = 420s;

        enum EndingSenders : uint32
        {
            SENDER_TOPIC = 50,
            SENDER_GOODBYE = 51,
        };

        struct Topic
        {
            uint8 topic;
            std::string label;
            uint32 text;
        };

        std::unordered_map<uint32, std::vector<Topic>> conversations;
        std::string letterSubject, letterBody;

        // The goodbyes See You Later asks for: whose, its credit, and what they say to it.
        struct Goodbye
        {
            uint32 npc, credit, text;
        };
        constexpr Goodbye GOODBYES[] = {
            { NPC_SYLVANAS, 770037, 7703031 },
            { NPC_ALLERIA,  770038, 7703032 },
            { NPC_ANDORMU,  770039, 7703033 },
            { NPC_NOZARI,   770040, 7703034 },
        };

        // Timerunners who saw the Lich King fall and are waiting to be brought back.
        std::mutex returningLock;
        std::set<ObjectGuid> returning;

        // Timerunners gone home, to take off Wrath Remix once their session has let them go: their
        // account, and when they went.
        struct GoneHome
        {
            ObjectGuid guid;
            uint32 account;
            std::chrono::steady_clock::time_point at;
        };
        std::mutex goneLock;
        std::vector<GoneHome> gone;

        Goodbye const* GoodbyeOf(uint32 npc)
        {
            for (Goodbye const& g : GOODBYES)
                if (g.npc == npc)
                    return &g;
            return nullptr;
        }

        bool NeedsCredit(Player* player, uint32 questId, uint32 credit)
        {
            if (player->GetQuestStatus(questId) != QUEST_STATUS_INCOMPLETE)
                return false;
            Quest const* quest = sObjectMgr->GetQuestTemplate(questId);
            if (!quest)
                return false;
            uint16 slot = player->FindQuestSlot(questId);
            for (uint8 i = 0; i < QUEST_OBJECTIVES_COUNT; ++i)
                if (quest->RequiredNpcOrGo[i] == int32(credit))
                    return player->GetQuestSlotCounter(slot, i) < quest->RequiredNpcOrGoCount[i];
            return false;
        }

        std::string TransfersDir()
        {
            char const* dir = std::getenv("WOW_TRANSFERS_DIR");
            return dir ? dir : "";
        }

        void BringBack(ObjectGuid guid)
        {
            {
                std::lock_guard<std::mutex> lock(returningLock);
                if (!returning.erase(guid))
                    return;
            }
            Player* player = ObjectAccessor::FindPlayer(guid);
            if (!player)
                return;
            Destination const* bazaar = DestinationFor(TRAVEL_BAZAAR);
            if (!bazaar)
                return;
            Record(player, "return", NPC_THE_LICH_KING, 0);
            player->TeleportTo(bazaar->map, BAZAAR_X, BAZAAR_Y, BAZAAR_Z, BAZAAR_O);

            // Eternus's letter, from the river's edge.
            if (!letterBody.empty())
            {
                CharacterDatabaseTransaction trans = CharacterDatabase.BeginTransaction();
                MailDraft(letterSubject, letterBody).SendMailTo(trans, MailReceiver(player), MailSender(MAIL_CREATURE, NPC_ETERNUS), MAIL_CHECK_MASK_COPIED);
                CharacterDatabase.CommitTransaction(trans);
            }

            // Everyone at the Bazaar, once they're there (the journey takes a loading screen).
            std::shared_ptr<uint32> tries = std::make_shared<uint32>(30);
            std::shared_ptr<std::function<void()>> wait = std::make_shared<std::function<void()>>();
            *wait = [guid, tries, wait]()
            {
                Player* p = ObjectAccessor::FindPlayer(guid);
                if (!p || !(*tries)--)
                    return;
                if (p->IsInWorld() && !p->IsBeingTeleported() && p->GetMapId() == MAP_NORTHREND && p->GetExactDist2d(BAZAAR_X, BAZAAR_Y) < 40.0f)
                    PlayScene(p, SCENE_RETURN);
                else
                    p->m_Events.AddEventAtOffset([wait]() { (*wait)(); }, 1s);
            };
            player->m_Events.AddEventAtOffset([wait]() { (*wait)(); }, 2500ms);
        }

        void BringBackSoon(Player* player, Milliseconds after)
        {
            ObjectGuid guid = player->GetGUID();
            player->m_Events.AddEventAtOffset([guid]() { BringBack(guid); }, after);
        }

        // The capital of the Timerunner's side, from the world database's teleports.
        bool Capital(bool alliance, uint32& map, float& x, float& y, float& z, float& o, uint32& zone)
        {
            QueryResult result = WorldDatabase.Query("SELECT `map`, `position_x`, `position_y`, `position_z`, `orientation` FROM `game_tele` WHERE `name` = '{}'",
                alliance ? "Stormwind" : "Orgrimmar");
            if (!result)
                return false;
            Field* f = result->Fetch();
            map = f[0].Get<uint32>();
            x = f[1].Get<float>();
            y = f[2].Get<float>();
            z = f[3].Get<float>();
            o = f[4].Get<float>();
            zone = alliance ? 1519 : 1637;
            return true;
        }
    }

    // Back to the Bazaar [after] from now, or as soon as the game says the cinematic is over.
    void ReturnToBazaarSoon(Player* player, Milliseconds after)
    {
        {
            std::lock_guard<std::mutex> lock(returningLock);
            returning.insert(player->GetGUID());
        }
        BringBackSoon(player, after);
    }

    void LoadEnding()
    {
        conversations.clear();
        letterSubject.clear();
        letterBody.clear();
        if (!Active())
            return;
        if (QueryResult result = WorldDatabase.Query("SELECT `npc`, `topic`, `label`, `text` FROM `remix_conversation` ORDER BY `npc`, `topic`"))
        {
            do
            {
                Field* f = result->Fetch();
                conversations[f[0].Get<uint32>()].push_back({ f[1].Get<uint8>(), f[2].Get<std::string>(), f[3].Get<uint32>() });
            } while (result->NextRow());
        }
        if (QueryResult result = WorldDatabase.Query("SELECT `subject`, `body` FROM `remix_letter` WHERE `id` = 1"))
        {
            letterSubject = result->Fetch()[0].Get<std::string>();
            letterBody = result->Fetch()[1].Get<std::string>();
        }
    }

    // Once the Lich King has fallen: their stories, and the goodbyes See You Later asks for.
    bool AddEndingGossip(Player* player, Creature* creature)
    {
        if (!Active() || !(player->GetQuestRewardStatus(QUEST_LAST_NECESSARY_BATTLE) ||
                           player->GetQuestStatus(QUEST_LAST_NECESSARY_BATTLE) == QUEST_STATUS_COMPLETE))
            return false;
        bool added = false;
        auto topics = conversations.find(creature->GetEntry());
        if (topics != conversations.end())
            for (Topic const& t : topics->second)
            {
                AddGossipItemFor(player, GOSSIP_ICON_CHAT, t.label, SENDER_TOPIC, t.topic);
                added = true;
            }
        if (Goodbye const* g = GoodbyeOf(creature->GetEntry()); g && NeedsCredit(player, QUEST_SEE_YOU_LATER, g->credit))
        {
            AddGossipItemFor(player, GOSSIP_ICON_CHAT, "I'm going home. Goodbye.", SENDER_GOODBYE, 0);
            added = true;
        }
        return added;
    }

    // A topic chosen (its words, with the topics again), or a goodbye said.
    bool EndingGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action, uint32 menuText)
    {
        if (!Active() || (sender != SENDER_TOPIC && sender != SENDER_GOODBYE))
            return false;
        uint32 text = menuText;
        if (sender == SENDER_GOODBYE)
        {
            if (Goodbye const* g = GoodbyeOf(creature->GetEntry()))
            {
                if (NeedsCredit(player, QUEST_SEE_YOU_LATER, g->credit))
                {
                    player->KilledMonsterCredit(g->credit);
                    Record(player, "goodbye", creature->GetEntry(), 0);
                }
                text = g->text;
            }
        }
        else if (auto topics = conversations.find(creature->GetEntry()); topics != conversations.end())
        {
            for (Topic const& t : topics->second)
                if (t.topic == action)
                    text = t.text;
        }
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        AddEndingGossip(player, creature);
        SendGossipMenuFor(player, text, creature);
        return true;
    }

    // Home: the end of Back to Your Own Time's scene (RemixScenes.cpp).
    void SendHome(Player* player)
    {
        if (!Active() || IsBot(player))
            return;
        // the quest done (it goes with the character, and means nothing on ChromieCraft)
        if (player->GetQuestStatus(QUEST_BACK_TO_YOUR_OWN_TIME) == QUEST_STATUS_INCOMPLETE)
            player->KilledMonsterCredit(NPC_SCENE_CREDIT);
        if (Quest const* quest = sObjectMgr->GetQuestTemplate(QUEST_BACK_TO_YOUR_OWN_TIME);
            quest && player->GetQuestStatus(QUEST_BACK_TO_YOUR_OWN_TIME) == QUEST_STATUS_COMPLETE)
            player->RewardQuest(quest, 0, player, false);

        std::string dir = TransfersDir();
        if (dir.empty())
        {
            ChatHandler(player->GetSession()).SendSysMessage("|cff8fd3ffMoratari:|r The way home is closed on this realm (WOW_TRANSFERS_DIR). Report a time anomaly, and I will look into it.");
            return;
        }
        // The Bronze stays at the Bazaar.
        if (uint32 bronze = player->GetItemCount(ITEM_BRONZE, true))
            player->DestroyItemCount(ITEM_BRONZE, bronze, true, false);
        // (saved now, not queued: the dump reads the character from the database next)
        CharacterDatabaseTransaction save = CharacterDatabase.BeginTransaction();
        player->SaveToDB(save, false, false);
        CharacterDatabase.DirectCommitTransaction(save);

        std::error_code error;
        std::filesystem::create_directories(dir, error);
        ObjectGuid::LowType low = player->GetGUID().GetCounter();
        std::string base = dir + "/" + std::to_string(low);
        PlayerDumpWriter writer;
        if (writer.WriteDumpToFile(base + ".dump.part", low) != DUMP_SUCCESS)
        {
            LOG_ERROR("module", "Wrath Remix: {} couldn't be written to {} to go home", player->GetName(), dir);
            ChatHandler(player->GetSession()).SendSysMessage("|cff8fd3ffMoratari:|r Something is wrong with the way home. You are still here; report a time anomaly, and I will look into it.");
            return;
        }
        {
            std::ofstream who(base + ".who");
            who << player->GetSession()->GetAccountId() << "\n" << player->GetName() << "\n"
                << (player->GetTeamId() == TEAM_ALLIANCE ? "alliance" : "horde") << "\n";
        }
        std::filesystem::rename(base + ".dump.part", base + ".dump", error);
        LOG_INFO("module", "Wrath Remix: {} goes home to ChromieCraft ({}.dump)", player->GetName(), base);
        Record(player, "home", 0, 0);

        // Off Wrath Remix: let go of the game, and take the Timerunner off this realm.
        ChatHandler(player->GetSession()).SendSysMessage("|cff8fd3ffMoratari:|r Go home, Timerunner. The game will close, and the server will start again on ChromieCraft: you will wake in your side's capital.");
        {
            std::lock_guard<std::mutex> lock(goneLock);
            gone.push_back({ player->GetGUID(), player->GetSession()->GetAccountId(), std::chrono::steady_clock::now() });
        }
        player->GetSession()->KickPlayer("Wrath Remix: gone home to ChromieCraft");
    }

    // (the world's update: a Timerunner gone home is taken off the realm once they're logged out
    // and saved, a few seconds on)
    void ForgetGoneHome()
    {
        std::vector<GoneHome> ready;
        {
            std::lock_guard<std::mutex> lock(goneLock);
            if (gone.empty())
                return;
            auto now = std::chrono::steady_clock::now();
            for (auto it = gone.begin(); it != gone.end();)
            {
                if (now - it->at > std::chrono::seconds(5) && !ObjectAccessor::FindConnectedPlayer(it->guid))
                {
                    ready.push_back(*it);
                    it = gone.erase(it);
                }
                else
                    ++it;
            }
        }
        for (GoneHome const& g : ready)
        {
            Player::DeleteFromDB(g.guid.GetCounter(), g.account, true, true);
            LOG_INFO("module", "Wrath Remix: Timerunner {} taken off Wrath Remix (gone home)", g.guid.GetCounter());
        }
    }

    // On ChromieCraft, as its world server starts: the Timerunners who came home from Wrath Remix,
    // in their side's capital.
    void TakeInTimerunners()
    {
        std::string dir = TransfersDir();
        std::error_code error;
        if (dir.empty() || !std::filesystem::is_directory(dir, error))
            return;
        std::filesystem::create_directories(dir + "/home", error);
        for (auto const& entry : std::filesystem::directory_iterator(dir, error))
        {
            if (!entry.is_regular_file() || entry.path().extension() != ".dump")
                continue;
            std::string base = entry.path().parent_path().string() + "/" + entry.path().stem().string();
            std::ifstream who(base + ".who");
            uint32 account = 0;
            std::string name, side;
            if (!(who >> account >> name >> side))
            {
                LOG_ERROR("module", "Wrath Remix: {} has no word of who it is ({}.who)", entry.path().string(), base);
                continue;
            }
            who.close();
            // (the guid the dump takes: the next free one)
            ObjectGuid::LowType guid = sObjectMgr->GetGenerator<HighGuid::Player>().GetNextAfterMaxUsed();
            PlayerDumpReader reader;
            DumpReturn loaded = reader.LoadDumpFromFile(entry.path().string(), account, name, 0);
            if (loaded != DUMP_SUCCESS)
            {
                LOG_ERROR("module", "Wrath Remix: {} couldn't come home to ChromieCraft ({}, error {})", name, entry.path().string(), uint32(loaded));
                continue;
            }
            // In its side's capital, at home there (queued after the dump's own writes, which are
            // queued too).
            uint32 map, zone;
            float x, y, z, o;
            if (Capital(side == "alliance", map, x, y, z, o, zone))
            {
                CharacterDatabase.Execute("UPDATE `characters` SET `map` = {}, `position_x` = {}, `position_y` = {}, `position_z` = {}, `orientation` = {}, `zone` = {}, "
                    "`instance_id` = 0, `taxi_path` = '', `is_logout_resting` = 1 WHERE `guid` = {}", map, x, y, z, o, zone, guid);
                CharacterDatabase.Execute("DELETE FROM `character_homebind` WHERE `guid` = {}", guid);
                CharacterDatabase.Execute("INSERT INTO `character_homebind` (`guid`, `mapId`, `zoneId`, `posX`, `posY`, `posZ`) VALUES ({}, {}, {}, {}, {}, {})",
                    guid, map, zone, x, y, z);
            }
            for (char const* ext : { ".dump", ".who" })
                std::filesystem::rename(base + ext, dir + "/home/" + std::filesystem::path(base).filename().string() + ext, error);
            LOG_INFO("module", "Wrath Remix: {} came home to ChromieCraft", name);
        }
    }
}

using namespace WrathRemix;

// The Lich King falls: it counts for every Timerunner in his citadel on The Last Necessary Battle,
// and brings them back to the Bazaar once the cinematic is over.
class WrathRemixEnding : public UnitScript
{
public:
    WrathRemixEnding() : UnitScript("WrathRemixEnding", true, { UNITHOOK_ON_UNIT_DEATH }) { }

    void OnUnitDeath(Unit* unit, Unit* /*killer*/) override
    {
        if (!Active() || !unit || unit->GetEntry() != NPC_THE_LICH_KING || unit->GetMapId() != MAP_REMIX_ICECROWN_CITADEL || !unit->ToCreature())
            return;
        unit->GetMap()->DoForAllPlayers([](Player* player)
        {
            if (IsBot(player))
                return;
            if (player->GetQuestStatus(QUEST_LAST_NECESSARY_BATTLE) == QUEST_STATUS_INCOMPLETE)
                player->KilledMonsterCredit(NPC_THE_LICH_KING);
            if (player->GetQuestStatus(QUEST_LAST_NECESSARY_BATTLE) != QUEST_STATUS_COMPLETE)
                return;
            Record(player, "lich king", NPC_THE_LICH_KING, 0);
            ReturnToBazaarSoon(player, RETURN_AT_THE_LATEST);
        });
    }
};

// The game says a cinematic is over (CMSG_COMPLETE_MOVIE, handled in the player's own map update).
class WrathRemixEndingPackets : public ServerScript
{
public:
    WrathRemixEndingPackets() : ServerScript("WrathRemixEndingPackets", { SERVERHOOK_CAN_PACKET_RECEIVE }) { }

    bool CanPacketReceive(WorldSession* session, WorldPacket const& packet) override
    {
        if (!Active() || packet.GetOpcode() != CMSG_COMPLETE_MOVIE || !session)
            return true;
        Player* player = session->GetPlayer();
        if (!player || !player->IsInWorld())
            return true;
        bool waiting;
        {
            std::lock_guard<std::mutex> lock(returningLock);
            waiting = returning.count(player->GetGUID()) != 0;
        }
        if (waiting)
            BringBackSoon(player, 2s);
        return true;
    }
};

// ChromieCraft takes in the Timerunners who came home, as its world server starts; Wrath Remix lets
// them go once they've left.
class WrathRemixHomecoming : public WorldScript
{
public:
    WrathRemixHomecoming() : WorldScript("WrathRemixHomecoming", { WORLDHOOK_ON_STARTUP, WORLDHOOK_ON_UPDATE }) { }

    void OnStartup() override
    {
        if (!Active())
            TakeInTimerunners();
    }

    void OnUpdate(uint32 /*diff*/) override
    {
        if (Active())
            ForgetGoneHome();
    }
};

void AddRemixEndingScripts()
{
    new WrathRemixEnding();
    new WrathRemixEndingPackets();
    new WrathRemixHomecoming();
}
