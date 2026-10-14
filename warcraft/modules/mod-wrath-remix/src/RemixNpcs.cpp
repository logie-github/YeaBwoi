// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Wrath Remix's people: Moratari (the prologue, the Bazaar, her transitions, the way back to the
 * last chapter that held), Eternus (the choice of coast), the Keepers, Alurmi's Bronze shop,
 * Zephyr's roads, Sa'at's dungeons, the Chronologist's chapters, Momentus, and the prologue's
 * time-echoes.
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
#include "ScriptedGossip.h"
#include "StringFormat.h"
#include "TemporarySummon.h"
#include <cmath>

using namespace WrathRemix;

namespace
{
    enum Senders : uint32
    {
        SENDER_MAIN = GOSSIP_SENDER_MAIN,
        SENDER_CATEGORY,
        SENDER_BUY,
        SENDER_TRAVEL,
        SENDER_CHAPTER,
        SENDER_NONE,
    };

    enum MoratariActions : uint32
    {
        ACTION_ANOMALIES = 1,
        ACTION_BAZAAR,
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

    void TeleportTo(Player* player, Destination const& d)
    {
        CloseGossipMenuFor(player);
        player->TeleportTo(d.map, d.x, d.y, d.z, d.o);
    }

    // A "talk to" objective of Remix's quests (the Bazaar's Alurmi and Chronologist, Momentus).
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
        bool prologue = creature->GetMapId() == 1;
        if (player->GetQuestStatus(QUEST_DEMONSTRATION) == QUEST_STATUS_INCOMPLETE)
            AddGossipItemFor(player, GOSSIP_ICON_BATTLE, "I'm ready. Let the anomalies through.", SENDER_MAIN, ACTION_ANOMALIES);
        if (prologue && (player->GetQuestStatus(QUEST_BAZAAR) == QUEST_STATUS_INCOMPLETE ||
                         player->GetQuestStatus(QUEST_BAZAAR) == QUEST_STATUS_COMPLETE || player->GetQuestRewardStatus(QUEST_BAZAAR)))
            AddGossipItemFor(player, GOSSIP_ICON_TAXI, "Open the way to the Infinite Bazaar.", SENDER_MAIN, ACTION_BAZAAR);
        AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Where are we in history?", SENDER_MAIN, ACTION_HISTORY);
        SendGossipMenuFor(player, prologue ? TEXT_MORATARI_PROLOGUE : TEXT_MORATARI, creature);
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
                creature->Say("There, through the seams. Mind the teeth.", LANG_UNIVERSAL, player);
                for (int i = 0; i < 3; ++i)
                {
                    float angle = player->GetOrientation() + float(i) * 2.0f * float(M_PI) / 3.0f;
                    float x = player->GetPositionX() + 7.0f * std::cos(angle);
                    float y = player->GetPositionY() + 7.0f * std::sin(angle);
                    if (Creature* anomaly = creature->SummonCreature(NPC_ANOMALY, x, y, player->GetPositionZ(), angle + float(M_PI),
                                                                     TEMPSUMMON_TIMED_DESPAWN_OUT_OF_COMBAT, 60000))
                        anomaly->AI()->AttackStart(player);
                }
                return true;
            }
            case ACTION_BAZAAR:
                if (Destination const* bazaar = DestinationFor(TRAVEL_BAZAAR))
                    TeleportTo(player, *bazaar);
                return true;
            case ACTION_HISTORY:
            {
                Timerunner const t = Get(player);
                uint8 chapter = StableChapter(player);
                Chapter const* c = ChapterFor(chapter);
                AddGossipItemFor(player, GOSSIP_ICON_DOT, Acore::StringFormat("You carry {} Bronze. {}", Number(t.bronze),
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
        QuestStatus war = player->GetQuestStatus(QUEST_WAR_WE_REMEMBER);
        bool routes = war == QUEST_STATUS_INCOMPLETE || war == QUEST_STATUS_COMPLETE || player->GetQuestRewardStatus(QUEST_WAR_WE_REMEMBER);
        if (routes)
        {
            bool alliance = IsAlliance(player);
            uint16 borean = alliance ? TRAVEL_VALIANCE_KEEP : TRAVEL_WARSONG_HOLD;
            uint16 fjord = alliance ? TRAVEL_VALGARDE : TRAVEL_VENGEANCE_LANDING;
            for (uint16 id : { borean, fjord })
                if (Destination const* d = DestinationFor(id))
                    AddGossipItemFor(player, GOSSIP_ICON_TAXI, "Send me to " + d->name + ".", SENDER_TRAVEL, d->id);
        }
        SendGossipMenuFor(player, routes ? TEXT_ETERNUS_ROUTES : TEXT_ETERNUS, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* /*creature*/, uint32 sender, uint32 action) override
    {
        if (!Active())
            return false;
        Destination const* d = sender == SENDER_TRAVEL ? DestinationFor(uint16(action)) : nullptr;
        if (d && OnSide(player, d->side))
            TeleportTo(player, *d);
        else
            CloseGossipMenuFor(player);
        return true;
    }
};

// Andormu and Nozari: their quests and their words. When Andormu has read the terms (WRM-002),
// Eternus answers and Moratari has the last word.
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

    bool OnQuestReward(Player* player, Creature* creature, Quest const* quest, uint32 /*opt*/) override
    {
        if (!Active() || quest->GetQuestId() != QUEST_AGREEMENT)
            return false;
        if (Creature* eternus = creature->FindNearestCreature(NPC_ETERNUS, 40.0f))
            eternus->Say("Then let us determine how much of that road truly matters. A champion is more than an obedient witness.", LANG_UNIVERSAL, player);
        if (Creature* moratari = creature->FindNearestCreature(NPC_MORATARI, 40.0f))
        {
            ObjectGuid playerGuid = player->GetGUID();
            moratari->m_Events.AddEventAtOffset([moratari, playerGuid]()
            {
                moratari->Say("Wonderful. A controlled disagreement. These generally produce the best notes.", LANG_UNIVERSAL,
                    ObjectAccessor::GetPlayer(*moratari, playerGuid));
            }, 4s);
        }
        return false;
    }
};

class npc_remix_alurmi : public CreatureScript
{
public:
    npc_remix_alurmi() : CreatureScript("npc_remix_alurmi") { }

    static char const* CategoryName(uint8 category)
    {
        switch (category)
        {
            case 1: return "Heirloom armor";
            case 2: return "Mounts";
            case 3: return "Companions";
            case 4: return "Supplies";
            case 5: return "Heirloom weapons";
            default: return "Wares";
        }
    }

    static void Main(Player* player, Creature* creature)
    {
        ClearGossipMenuFor(player);
        AddGossipItemFor(player, GOSSIP_ICON_MONEY_BAG, "You carry " + Number(Get(player).bronze) + " Bronze.", SENDER_MAIN, 0);
        for (uint8 category : { 1, 5, 2, 3, 4 })
        {
            bool any = false;
            for (Ware const& w : Wares())
                any = any || (w.category == category && OnSide(player, w.side));
            if (any)
                AddGossipItemFor(player, GOSSIP_ICON_VENDOR, CategoryName(category), SENDER_CATEGORY, category);
        }
        SendGossipMenuFor(player, TEXT_ALURMI, creature);
    }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        if (player->GetQuestStatus(QUEST_BAZAAR) == QUEST_STATUS_INCOMPLETE)
            SpokenTo(player, creature);
        Main(player, creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action) override
    {
        if (!Active())
            return false;
        if (sender == SENDER_CATEGORY)
        {
            ClearGossipMenuFor(player);
            AddGossipItemFor(player, GOSSIP_ICON_MONEY_BAG, "You carry " + Number(Get(player).bronze) + " Bronze.", SENDER_CATEGORY, action);
            for (Ware const& w : Wares())
            {
                if (w.category != action || !OnSide(player, w.side))
                    continue;
                ItemTemplate const* item = sObjectMgr->GetItemTemplate(w.item);
                if (!item)
                    continue;
                std::string name = item->Name1 + (w.count > 1 ? " x" + std::to_string(w.count) : "");
                AddGossipItemFor(player, GOSSIP_ICON_VENDOR, name + ": " + Number(w.price) + " Bronze", SENDER_BUY, w.id,
                    "Buy " + name + " for " + Number(w.price) + " Bronze?", 0, false);
            }
            AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Back.", SENDER_MAIN, 0);
            SendGossipMenuFor(player, TEXT_ALURMI, creature);
            return true;
        }
        if (sender == SENDER_BUY)
        {
            Ware const* w = WareFor(uint16(action));
            ChatHandler chat(player->GetSession());
            if (w && OnSide(player, w->side))
            {
                if (Get(player).bronze < w->price)
                    chat.PSendSysMessage("|cffd4a017Alurmi:|r That costs {} Bronze, and you carry {}.", Number(w->price), Number(Get(player).bronze));
                else if (!player->AddItem(w->item, w->count))
                    chat.SendSysMessage("|cffd4a017Alurmi:|r Make some room in your bags first.");
                else
                {
                    SpendBronze(player, w->price);
                    Record(player, "buy", w->item, -int32(w->price));
                }
            }
            OnGossipSelect(player, creature, SENDER_CATEGORY, w ? w->category : 0);
            return true;
        }
        Main(player, creature);
        return true;
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
        if (player->GetQuestStatus(QUEST_BAZAAR) == QUEST_STATUS_INCOMPLETE)
            SpokenTo(player, creature);
        ClearGossipMenuFor(player);
        uint8 current = StableChapter(player);
        AddGossipItemFor(player, GOSSIP_ICON_DOT, Acore::StringFormat("You: level {}, {} Bronze, {}.", uint32(player->GetLevel()),
            Number(Get(player).bronze), current ? Acore::StringFormat("chapter {}", Roman(current)) : std::string("the prologue")), SENDER_NONE, 0);
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
        if (player->GetQuestStatus(QUEST_SHADOW_OF_CITY) == QUEST_STATUS_INCOMPLETE)
            SpokenTo(player, creature);
        ClearGossipMenuFor(player);
        SendGossipMenuFor(player, TEXT_MOMENTUS, creature);
        return true;
    }
};

// The prologue's frozen moments: looking at one is WRM-001's objective.
class npc_remix_echo : public CreatureScript
{
public:
    npc_remix_echo() : CreatureScript("npc_remix_echo") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        if (player->GetQuestStatus(QUEST_WEIGHT_OF_TIME) == QUEST_STATUS_INCOMPLETE)
            SpokenTo(player, creature);
        ClearGossipMenuFor(player);
        SendGossipMenuFor(player, TEXT_ECHO_LANDING + (creature->GetEntry() - NPC_ECHO_LANDING), creature);
        return true;
    }
};

void AddRemixNpcScripts()
{
    new npc_remix_moratari();
    new npc_remix_eternus();
    new npc_remix_keeper();
    new npc_remix_alurmi();
    new npc_remix_zephyr();
    new npc_remix_saat();
    new npc_remix_chronologist();
    new npc_remix_momentus();
    new npc_remix_echo();
}
