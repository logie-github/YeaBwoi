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
#include <functional>
#include <memory>
#include <mutex>
#include <set>

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

    // ---- WRM-207, The Weight of Time: the turn (STORY.md) ---------------------------------------
    //
    // Played at the Bazaar as the Timerunner takes the quest from Eternus. Sylvanas and Alleria
    // walk in; Sylvanas tells Moratari what Eternus left out; Moratari goes to see for herself
    // and steps back out of a rift ten seconds later; Eternus refuses, and leaves. The visitors
    // and this Moratari are summoned for the Timerunner alone to see, and the Bazaar's own
    // Moratari is hidden from them while it plays (visibility conditions,
    // wrath_remix_world_0011_chapter2.sql). As it ends, the quest's credit hides Eternus from them
    // for good and shows the Bazaar's own Sylvanas and Alleria, where these two stood.

    enum SceneSpells : uint32
    {
        SPELL_TELEPORT_VISUAL = 51347,  // Teleport Visual Only
        SPELL_TIME_RIFT_CHANNEL = 31387,
    };

    // The spots, as wrath_remix_world_0004_spawns.sql has the Bazaar's own people.
    constexpr float HX = 5700.0f, HY = 1015.0f, HZ = 174.6f;
    Position const MORATARI_SPOT(HX - 5.0f, HY + 6.0f, HZ, 5.30f);
    Position const SYLVANAS_SPOT(HX + 4.0f, HY + 6.0f, HZ, 3.60f);
    Position const ALLERIA_SPOT(HX + 6.5f, HY + 3.5f, HZ, 3.40f);
    Position const VISITORS_FROM(HX + 15.0f, HY - 17.0f, HZ, 2.30f);
    Position const RIFT_SPOT(HX - 7.5f, HY + 8.5f, HZ, 5.30f);

    struct TurnScene
    {
        ObjectGuid player, eternus, sylvanas, alleria, moratari, rift;
    };

    std::mutex scenesLock;
    std::set<ObjectGuid> scenes;  // the Timerunners whose scene is playing

    Creature* Actor(Player* player, ObjectGuid guid)
    {
        return guid ? ObjectAccessor::GetCreature(*player, guid) : nullptr;
    }

    void Speak(Player* player, ObjectGuid who, char const* text)
    {
        if (Creature* actor = Actor(player, who))
            actor->Say(text, LANG_UNIVERSAL, player);
    }

    ObjectGuid Summon(Player* player, uint32 entry, Position const& at)
    {
        TempSummon* actor = player->SummonCreature(entry, at.GetPositionX(), at.GetPositionY(), at.GetPositionZ(), at.GetOrientation(),
            TEMPSUMMON_TIMED_DESPAWN, 150000, nullptr, true);
        return actor ? actor->GetGUID() : ObjectGuid::Empty;
    }

    void Unsummon(Player* player, ObjectGuid& guid)
    {
        if (Creature* actor = Actor(player, guid))
            actor->DespawnOrUnsummon();
        guid.Clear();
    }

    void EndTurnScene(Player* player, TurnScene& scene)
    {
        Unsummon(player, scene.sylvanas);
        Unsummon(player, scene.alleria);
        Unsummon(player, scene.moratari);
        Unsummon(player, scene.rift);
    }

    void PlayTurnScene(Player* player, Creature* eternus)
    {
        {
            std::lock_guard<std::mutex> lock(scenesLock);
            if (!scenes.insert(player->GetGUID()).second)
                return;
        }
        auto scene = std::make_shared<TurnScene>();
        scene->player = player->GetGUID();
        scene->eternus = eternus->GetGUID();
        scene->moratari = Summon(player, NPC_MORATARI_SCENE, MORATARI_SPOT);
        scene->sylvanas = Summon(player, NPC_SYLVANAS, VISITORS_FROM);
        scene->alleria = Summon(player, NPC_ALLERIA, VISITORS_FROM);
        if (Creature* sylvanas = Actor(player, scene->sylvanas))
            sylvanas->GetMotionMaster()->MovePoint(1, SYLVANAS_SPOT);
        if (Creature* alleria = Actor(player, scene->alleria))
            alleria->GetMotionMaster()->MovePoint(1, ALLERIA_SPOT);
        player->UpdateObjectVisibility();

        using Step = std::function<void(Player*, TurnScene&)>;
        std::vector<std::pair<uint32, Step>> steps = {
            { 1500, [](Player* p, TurnScene& s) { Speak(p, s.eternus, "Visitors. And not the bronze's usual kind."); } },
            { 8000, [](Player* p, TurnScene& s) { Speak(p, s.sylvanas, "Your champion was warned, Eternus. Now I will speak to the one who keeps your records."); } },
            { 14000, [](Player* p, TurnScene& s) { Speak(p, s.eternus, "The Banshee Queen, sent to lecture us on history. Tell them what became of Teldrassil, Windrunner, before you tell anyone what must not change."); } },
            { 21000, [](Player* p, TurnScene& s) { Speak(p, s.sylvanas, "I know what I am. That is why the bronze sent me. I know what the Lich King's Helm is for, and who made it."); } },
            { 28000, [](Player* p, TurnScene& s) {
                if (Creature* sylvanas = Actor(p, s.sylvanas))
                    if (Creature* moratari = Actor(p, s.moratari))
                        sylvanas->SetFacingToObject(moratari);
                Speak(p, s.sylvanas, "Listen to me, Moratari. If Bolvar Fordragon does not burn at the Wrathgate, no one takes up the Helm when Arthas falls. The Scourge goes masterless, and every soul the Helm has bound is lost with it. I have spent years freeing souls like those. I will not watch them thrown away for his kindness."); } },
            { 38000, [](Player* p, TurnScene& s) { Speak(p, s.alleria, "She speaks the truth. I can feel the shape of the road your master wants. It ends in the dark."); } },
            { 44000, [](Player* p, TurnScene& s) { Speak(p, s.moratari, "He told me it was mercy. Give me a moment. I will see it for myself."); } },
            { 47000, [](Player* p, TurnScene& s) {
                if (Creature* moratari = Actor(p, s.moratari))
                    moratari->CastSpell(moratari, SPELL_TIME_RIFT_CHANNEL, true); } },
            { 50000, [](Player* p, TurnScene& s) {
                if (Creature* moratari = Actor(p, s.moratari))
                    moratari->CastSpell(moratari, SPELL_TELEPORT_VISUAL, true); } },
            { 51000, [](Player* p, TurnScene& s) { Unsummon(p, s.moratari); } },
            { 54000, [](Player* p, TurnScene& s) { Speak(p, s.eternus, "Whatever she finds, champion, remember who brought you here, and why."); } },
            // (ten seconds gone: a rift opens and she steps out of it)
            { 60000, [](Player* p, TurnScene& s) { s.rift = Summon(p, NPC_TIME_RIFT, RIFT_SPOT); } },
            { 61500, [](Player* p, TurnScene& s) {
                s.moratari = Summon(p, NPC_MORATARI_SCENE, RIFT_SPOT);
                if (Creature* moratari = Actor(p, s.moratari))
                {
                    moratari->CastSpell(moratari, SPELL_TELEPORT_VISUAL, true);
                    moratari->GetMotionMaster()->MovePoint(1, MORATARI_SPOT);
                } } },
            { 64000, [](Player* p, TurnScene& s) { Unsummon(p, s.rift); } },
            { 65000, [](Player* p, TurnScene& s) { Speak(p, s.moratari, "I walked the road where Bolvar lives. At the end of it there is no one on the Frozen Throne, and the dead have no one to hold them. She is right. It has to happen as it did. All of it."); } },
            { 74000, [](Player* p, TurnScene& s) { Speak(p, s.eternus, "I know."); } },
            { 77000, [](Player* p, TurnScene& s) { Speak(p, s.eternus, "I have always known. I will not watch it again. Keep your history, Moratari. I will save whom I can without you."); } },
            { 84000, [](Player* p, TurnScene& s) {
                if (Creature* eternus = Actor(p, s.eternus))
                    eternus->CastSpell(eternus, SPELL_TELEPORT_VISUAL, true); } },
            { 86000, [](Player* p, TurnScene& s) {
                // (only if the Timerunner stayed for it: one who left can ask Eternus to begin again)
                bool watched = Actor(p, s.eternus) && p->GetDistance(MORATARI_SPOT) < 60.0f;
                EndTurnScene(p, s);
                if (watched)
                    p->KilledMonsterCredit(NPC_TURN_CREDIT);
                p->UpdateObjectVisibility(); } },
        };
        for (auto& [at, step] : steps)
        {
            player->m_Events.AddEventAtOffset([scene, step]()
            {
                Player* p = ObjectAccessor::FindPlayer(scene->player);
                if (p && p->IsInWorld() && p->GetQuestStatus(QUEST_WEIGHT_OF_TIME) == QUEST_STATUS_INCOMPLETE)
                    step(p, *scene);
            }, Milliseconds(at));
        }
        // (the scene is over, played to its end or not)
        player->m_Events.AddEventAtOffset([scene]()
        {
            if (Player* p = ObjectAccessor::FindPlayer(scene->player); p && p->IsInWorld())
                EndTurnScene(p, *scene);
            std::lock_guard<std::mutex> lock(scenesLock);
            scenes.erase(scene->player);
        }, 88s);
    }
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
        // (one who left while the turn played sees it again)
        if (player->GetQuestStatus(QUEST_WEIGHT_OF_TIME) == QUEST_STATUS_INCOMPLETE)
        {
            CloseGossipMenuFor(player);
            PlayTurnScene(player, creature);
            return true;
        }
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        SendGossipMenuFor(player, player->GetQuestRewardStatus(QUEST_WAR_WILL_COST) ? TEXT_ETERNUS_LATER : TEXT_ETERNUS, creature);
        return true;
    }

    bool OnQuestAccept(Player* player, Creature* creature, Quest const* quest) override
    {
        if (Active() && quest->GetQuestId() == QUEST_WEIGHT_OF_TIME)
        {
            CloseGossipMenuFor(player);
            PlayTurnScene(player, creature);
        }
        return false;
    }
};

// Sylvanas and Alleria Windrunner, from the future: at Wyrmrest Temple with Chromie, on the
// Wrathgate's cliffs (Alleria), and at the Bazaar after the turn. They're seen only where the
// story has them (visibility conditions, wrath_remix_world_0011_chapter2.sql).
class npc_remix_windrunner : public CreatureScript
{
public:
    npc_remix_windrunner() : CreatureScript("npc_remix_windrunner") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        bool sylvanas = creature->GetEntry() == NPC_SYLVANAS;
        uint32 text;
        if (creature->GetZoneId() == ZONE_CRYSTALSONG)
            text = sylvanas ? TEXT_SYLVANAS : TEXT_ALLERIA;
        else if (creature->GetAreaId() == AREA_WYRMREST_TEMPLE)
            text = sylvanas ? TEXT_SYLVANAS_WYRMREST : TEXT_ALLERIA_WYRMREST;
        else
            text = sylvanas ? TEXT_SYLVANAS : TEXT_ALLERIA_GATE;
        SendGossipMenuFor(player, text, creature);
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
    new npc_remix_windrunner();
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
