// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Wrath Remix's people (STORY.md): Moratari (the prologue, her transitions, the way back to the
 * last chapter that held), Eternus, the Keepers, the Bronze vendors (Alurmi, Horos, Domelius,
 * Arturos, Aeonicus, Durus: lists of wares as vendor windows), Zephyr's roads, Sa'at's dungeons,
 * the Chronologist's chapters, Momentus, the class instructors, and the prologue's echoes of the
 * war's dead and its anomalies.
 */

#include "WrathRemix.h"
#include "Chat.h"
#include "Creature.h"
#include "CreatureAI.h"
#include "ObjectAccessor.h"
#include "ObjectMgr.h"
#include "Player.h"
#include "QuestDef.h"
#include "ScriptMgr.h"
#include "ScriptedCreature.h"
#include "ScriptedGossip.h"
#include "StringFormat.h"
#include "TemporarySummon.h"
#include "WorldSession.h"
#include <cmath>

using namespace WrathRemix;

namespace
{
    enum Senders : uint32
    {
        SENDER_MAIN = GOSSIP_SENDER_MAIN,
        SENDER_LIST,
        SENDER_TRAVEL,
        SENDER_CHAPTER,
        SENDER_NONE,
    };

    enum MoratariActions : uint32
    {
        ACTION_ANOMALIES = 1,
        ACTION_HISTORY,
        ACTION_RECOVER,
        ACTION_BACK,
    };

    std::string Number(uint32 n)
    {
        std::string s = std::to_string(n);
        for (int i = int(s.size()) - 3; i > 0; i -= 3)
            s.insert(size_t(i), ",");
        return s;
    }

    bool OnSide(Player* player, uint8 side)
    {
        return side == 0 || (side == 1) == IsAlliance(player);
    }

    // Bronze in hand: the currency's, and any the realm still owes.
    uint32 Carried(Player* player)
    {
        return Bronze(player) + Get(player).bronze;
    }

    void TeleportTo(Player* player, Destination const& d)
    {
        CloseGossipMenuFor(player);
        player->TeleportTo(d.map, d.x, d.y, d.z, d.o);
    }

    // WRM-013's anomalies come through one at a time: each one Moratari lets through brings the
    // next when it dies, three in all, while the quest still wants them.
    constexpr uint32 ANOMALY_WAVES = 3;
    enum AnomalyData : uint32 { DATA_WAVE = 1 };

    void LetAnomalyThrough(Creature* moratari, Player* player, uint32 wave)
    {
        float angle = player->GetOrientation() + float(wave) * 2.0f * float(M_PI) / 3.0f;
        float x = player->GetPositionX() + 7.0f * std::cos(angle);
        float y = player->GetPositionY() + 7.0f * std::sin(angle);
        if (Creature* anomaly = moratari->SummonCreature(NPC_ANOMALY, x, y, player->GetPositionZ(), angle + float(M_PI),
                                                         TEMPSUMMON_TIMED_DESPAWN_OUT_OF_COMBAT, 60000))
        {
            anomaly->AI()->SetGUID(player->GetGUID());
            anomaly->AI()->SetData(DATA_WAVE, wave);
            anomaly->AI()->AttackStart(player);
        }
    }

    // A "talk to" objective of Remix's quests (WRM-011's echoes, WRM-012's Alurmi).
    void SpokenTo(Player* player, Creature* creature)
    {
        player->KilledMonsterCredit(creature->GetEntry());
    }

    // The chapter to go back to when the record is tangled: the furthest reached, or the one
    // the Timerunner's level belongs to.
    uint8 StableChapter(Player* player)
    {
        return std::max(Get(player).chapter, ChapterForLevel(player->GetLevel()));
    }

    void ToChapter(Player* player, Chapter const& c)
    {
        int side = IsAlliance(player) ? 0 : 1;
        CloseGossipMenuFor(player);
        player->TeleportTo(c.map[side], c.x[side], c.y[side], c.z[side], player->GetOrientation());
    }

    char const* ROMAN[] = { "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };
    char const* Roman(uint8 n) { return n < 11 ? ROMAN[n] : "?"; }
}

class npc_remix_moratari : public CreatureScript
{
public:
    npc_remix_moratari() : CreatureScript("npc_remix_moratari") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        // (as a new Timerunner wakes: where and when they are)
        bool waking = !player->GetQuestRewardStatus(QUEST_OUT_OF_TIME) && player->getClass() != CLASS_DEATH_KNIGHT;
        if (player->GetQuestStatus(QUEST_DEMONSTRATION) == QUEST_STATUS_INCOMPLETE)
            AddGossipItemFor(player, GOSSIP_ICON_BATTLE, "I'm ready. Let the anomalies through.", SENDER_MAIN, ACTION_ANOMALIES);
        AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Where are we in history?", SENDER_MAIN, ACTION_HISTORY);
        SendGossipMenuFor(player, waking ? TEXT_MORATARI_PROLOGUE : TEXT_MORATARI, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        if (sender != SENDER_MAIN)
        {
            CloseGossipMenuFor(player);
            return true;
        }
        switch (action)
        {
            case ACTION_ANOMALIES:
            {
                CloseGossipMenuFor(player);
                creature->Say("There, through the seams. One at a time. Mind the teeth.", LANG_UNIVERSAL, player);
                LetAnomalyThrough(creature, player, 1);
                return true;
            }
            case ACTION_HISTORY:
            {
                uint8 chapter = StableChapter(player);
                Chapter const* c = ChapterFor(chapter);
                AddGossipItemFor(player, GOSSIP_ICON_DOT, Acore::StringFormat("You carry {} Bronze. {}", Number(Carried(player)),
                    c ? Acore::StringFormat("Chapter {}: {}.", Roman(c->chapter), c->name) : std::string("The prologue.")), SENDER_NONE, 0);
                AddGossipItemFor(player, GOSSIP_ICON_CHAT, "The record became tangled. Return me to my last stable chapter.", SENDER_MAIN, ACTION_RECOVER,
                    "Moratari will take you to the start of your chapter.", 0, false);
                AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Back.", SENDER_MAIN, ACTION_BACK);
                SendGossipMenuFor(player, TEXT_MORATARI_HISTORY, creature);
                return true;
            }
            case ACTION_RECOVER:
            {
                Chapter const* c = ChapterFor(StableChapter(player));
                if (c)
                {
                    Record(player, "recover", c->chapter, 0);
                    ToChapter(player, *c);
                }
                else if (Destination const* bazaar = DestinationFor(TRAVEL_BAZAAR))
                    TeleportTo(player, *bazaar);
                return true;
            }
            case ACTION_BACK:
                return OnGossipHello(player, creature);
        }
        CloseGossipMenuFor(player);
        return true;
    }
};

class npc_remix_eternus : public CreatureScript
{
public:
    npc_remix_eternus() : CreatureScript("npc_remix_eternus") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        SendGossipMenuFor(player, player->GetQuestRewardStatus(QUEST_WAR_WILL_COST) ? TEXT_ETERNUS_LATER : TEXT_ETERNUS, creature);
        return true;
    }
};

// Andormu and Nozari, the Keepers of Time: their words.
class npc_remix_keeper : public CreatureScript
{
public:
    npc_remix_keeper() : CreatureScript("npc_remix_keeper") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        SendGossipMenuFor(player, creature->GetEntry() == NPC_NOZARI ? TEXT_NOZARI : TEXT_ANDORMU, creature);
        return true;
    }
};

// The Bazaar's Bronze vendors (Alurmi, Horos, Domelius, Arturos, Aeonicus, Durus): each opens
// its lists (remix_vendor_list) as vendor windows, priced in Bronze.
static void BronzeVendorMenu(Player* player, Creature* creature)
{
    ClearGossipMenuFor(player);
    AddGossipItemFor(player, GOSSIP_ICON_MONEY_BAG, "You carry " + Number(Carried(player)) + " Bronze.", SENDER_NONE, 0);
    for (VendorList const& list : VendorLists())
        if (list.vendor == creature->GetEntry() && OnSide(player, list.side))
            AddGossipItemFor(player, GOSSIP_ICON_VENDOR, list.label, SENDER_LIST, list.list);
    uint32 text = creature->GetEntry() == NPC_ALURMI ? uint32(TEXT_ALURMI) : uint32(TEXT_HOROS) + (creature->GetEntry() - NPC_HOROS);
    SendGossipMenuFor(player, text, creature);
}

static bool BronzeVendorSelect(Player* player, Creature* creature, uint32 sender, uint32 action)
{
    if (sender == SENDER_LIST)
    {
        for (VendorList const& list : VendorLists())
        {
            if (list.list == action && list.vendor == creature->GetEntry() && OnSide(player, list.side))
            {
                CloseGossipMenuFor(player);
                player->GetSession()->SendListInventory(creature->GetGUID(), list.list);
                return true;
            }
        }
    }
    BronzeVendorMenu(player, creature);
    return true;
}

class npc_remix_alurmi : public CreatureScript
{
public:
    npc_remix_alurmi() : CreatureScript("npc_remix_alurmi") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        if (player->GetQuestStatus(QUEST_TIMES_TOLL) == QUEST_STATUS_INCOMPLETE)
            SpokenTo(player, creature);
        BronzeVendorMenu(player, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action) override
    {
        return Active() && BronzeVendorSelect(player, creature, sender, action);
    }
};

class npc_remix_bronze_vendor : public CreatureScript
{
public:
    npc_remix_bronze_vendor() : CreatureScript("npc_remix_bronze_vendor") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        BronzeVendorMenu(player, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action) override
    {
        return Active() && BronzeVendorSelect(player, creature, sender, action);
    }
};

class npc_remix_zephyr : public CreatureScript
{
public:
    npc_remix_zephyr() : CreatureScript("npc_remix_zephyr") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        uint32 shown = 0;
        for (Destination const& d : Destinations())
        {
            if (!OnSide(player, d.side) || player->GetLevel() < d.minLevel || shown >= 30)
                continue;
            AddGossipItemFor(player, GOSSIP_ICON_TAXI, d.name, SENDER_TRAVEL, d.id);
            ++shown;
        }
        SendGossipMenuFor(player, TEXT_ZEPHYR, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* /*creature*/, uint32 sender, uint32 action) override
    {
        if (!Active())
            return false;
        Destination const* d = sender == SENDER_TRAVEL ? DestinationFor(uint16(action)) : nullptr;
        if (d && OnSide(player, d->side) && player->GetLevel() >= d->minLevel)
            TeleportTo(player, *d);
        else
            CloseGossipMenuFor(player);
        return true;
    }
};

class npc_remix_saat : public CreatureScript
{
public:
    npc_remix_saat() : CreatureScript("npc_remix_saat") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        for (Chapter const& c : Chapters())
            AddGossipItemFor(player, GOSSIP_ICON_DOT, Acore::StringFormat("Chapter {}: {}, levels {} to {}", Roman(c.chapter), c.name,
                uint32(c.levelMin), uint32(c.levelMax)), SENDER_NONE, 0);
        AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Every dungeon waits at its chapter's level: Utgarde Keep and the Nexus 15 to 20, Halls of Reflection 78 to 80. Heroic is still for eighty.", SENDER_NONE, 0);
        SendGossipMenuFor(player, TEXT_SAAT, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* /*creature*/, uint32 /*sender*/, uint32 /*action*/) override
    {
        CloseGossipMenuFor(player);
        return Active();
    }
};

class npc_remix_chronologist : public CreatureScript
{
public:
    npc_remix_chronologist() : CreatureScript("npc_remix_chronologist") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        uint8 current = StableChapter(player);
        AddGossipItemFor(player, GOSSIP_ICON_DOT, Acore::StringFormat("You: level {}, {} Bronze, {}.", uint32(player->GetLevel()),
            Number(Carried(player)), current ? Acore::StringFormat("chapter {}", Roman(current)) : std::string("the prologue")), SENDER_NONE, 0);
        for (Chapter const& c : Chapters())
        {
            bool open = player->GetLevel() + 2 >= c.levelMin;
            std::string text = Acore::StringFormat("{}Chapter {}: {} ({} to {}){}", c.chapter == current ? "> " : "", Roman(c.chapter), c.name,
                uint32(c.levelMin), uint32(c.levelMax), open ? "" : ", ahead of you");
            if (open)
                AddGossipItemFor(player, GOSSIP_ICON_TAXI, text, SENDER_CHAPTER, c.chapter, "Go to the start of Chapter " + std::string(Roman(c.chapter)) + "?", 0, false);
            else
                AddGossipItemFor(player, GOSSIP_ICON_DOT, text, SENDER_NONE, 0);
        }
        SendGossipMenuFor(player, TEXT_CHRONOLOGIST, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* /*creature*/, uint32 sender, uint32 action) override
    {
        if (!Active())
            return false;
        Chapter const* c = sender == SENDER_CHAPTER ? ChapterFor(uint8(action)) : nullptr;
        if (c && player->GetLevel() + 2 >= c->levelMin)
            ToChapter(player, *c);
        else
            CloseGossipMenuFor(player);
        return true;
    }
};

class npc_remix_momentus : public CreatureScript
{
public:
    npc_remix_momentus() : CreatureScript("npc_remix_momentus") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        SendGossipMenuFor(player, TEXT_MOMENTUS, creature);
        return true;
    }
};

// The war's dead as Eternus shows them (WRM-011): hearing one is the quest's objective.
class npc_remix_echo : public CreatureScript
{
public:
    npc_remix_echo() : CreatureScript("npc_remix_echo") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        if (player->GetQuestStatus(QUEST_WAR_WILL_COST) == QUEST_STATUS_INCOMPLETE)
            SpokenTo(player, creature);
        ClearGossipMenuFor(player);
        SendGossipMenuFor(player, TEXT_ECHO_FOOTMAN + (creature->GetEntry() - NPC_ECHO_FOOTMAN), creature);
        return true;
    }
};

// A Temporal Anomaly of WRM-013: when it dies, Moratari lets the next one through.
class npc_remix_anomaly : public CreatureScript
{
public:
    npc_remix_anomaly() : CreatureScript("npc_remix_anomaly") { }

    struct npc_remix_anomalyAI : public ScriptedAI
    {
        npc_remix_anomalyAI(Creature* creature) : ScriptedAI(creature) { }

        ObjectGuid timerunner;
        uint32 wave = ANOMALY_WAVES;

        void SetGUID(ObjectGuid const& guid, int32 /*id*/) override { timerunner = guid; }
        void SetData(uint32 type, uint32 value) override
        {
            if (type == DATA_WAVE)
                wave = value;
        }

        void JustDied(Unit* /*killer*/) override
        {
            Player* player = ObjectAccessor::GetPlayer(*me, timerunner);
            if (!player || player->GetQuestStatus(QUEST_DEMONSTRATION) != QUEST_STATUS_INCOMPLETE)
                return;
            // The credit is the Timerunner's whoever struck the blow: the realm's bots start here
            // too, and a kill only counts for a player who did half its damage.
            player->KilledMonsterCredit(NPC_ANOMALY_CREDIT);
            if (wave >= ANOMALY_WAVES || !player->IsAlive() || player->GetQuestStatus(QUEST_DEMONSTRATION) != QUEST_STATUS_INCOMPLETE)
                return;
            TempSummon* summon = me->ToTempSummon();
            Creature* moratari = summon ? summon->GetSummonerCreatureBase() : nullptr;
            LetAnomalyThrough(moratari ? moratari : me, player, wave + 1);
        }
    };

    CreatureAI* GetAI(Creature* creature) const override
    {
        return new npc_remix_anomalyAI(creature);
    }
};

// The Bazaar's instructors: speaking with one is WRM-012's objective; then their lessons, as any
// trainer's.
class npc_remix_instructor : public CreatureScript
{
public:
    npc_remix_instructor() : CreatureScript("npc_remix_instructor") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        if (player->GetQuestStatus(QUEST_TIMES_TOLL) == QUEST_STATUS_INCOMPLETE)
            player->KilledMonsterCredit(NPC_INSTRUCTOR_CREDIT);
        player->GetSession()->SendTrainerList(creature);
        return true;
    }
};

void AddRemixNpcScripts()
{
    new npc_remix_instructor();
    new npc_remix_moratari();
    new npc_remix_eternus();
    new npc_remix_keeper();
    new npc_remix_alurmi();
    new npc_remix_zephyr();
    new npc_remix_saat();
    new npc_remix_chronologist();
    new npc_remix_momentus();
    new npc_remix_echo();
    new npc_remix_anomaly();
    new npc_remix_bronze_vendor();
}
