// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Legion Remix's Bazaar cast, the ones with more to do than sell (the vendors are RemixNpcs.cpp's
 * Bronze vendors): Pageturner Metros, the Bazaar's administrative assistant, who knows where
 * everyone is and what they do, and puts a flag on the Timerunner's map for whoever they ask after.
 */

#include "WrathRemix.h"
#include "Creature.h"
#include "ObjectMgr.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "ScriptedGossip.h"
#include "StringFormat.h"
#include "WorldPacket.h"

using namespace WrathRemix;

namespace
{
    constexpr uint32 SENDER_WHERE = 7718;
    constexpr uint32 POI_ICON_FLAG = 7, POI_FLAGS = 99;     // (RemixGuide.cpp's flag)

    // Everyone Metros knows, in the order a newcomer asks: the story's people, then the Bazaar's.
    uint32 const PEOPLE[] = {
        NPC_ETERNUS, NPC_MORATARI, NPC_ALURMI,
        NPC_ARTUROS, NPC_AEONICUS, NPC_DURUS, 770207 /* Sacerdormu */, NPC_PYTHAGORUS,
        770208 /* Agos the Silent */, 770209 /* Larah Treebender */, 770210 /* Freddie Threads */, 770214 /* Unicus */,
        NPC_JAKKUS, 770212 /* Hemet Nesingwary XVII */, NPC_HOROS, NPC_DOMELIUS,
        770213 /* Nostwin */, 770215 /* Lidamorrutu */, 770216 /* Lindormi */, NPC_ERUS,
        NPC_MOMENTUS, NPC_NEDWARD, NPC_ZEPHYR, NPC_SAAT, NPC_CHRONOLOGIST,
    };

    // "Arturos, Dungeon Apparel"
    std::string Who(uint32 entry)
    {
        CreatureTemplate const* info = sObjectMgr->GetCreatureTemplate(entry);
        if (!info)
            return std::string();
        return info->SubName.empty() ? info->Name : info->Name + ", " + info->SubName;
    }

    void Flag(Player* player, Creature const* at, std::string const& name)
    {
        WorldPacket data(SMSG_GOSSIP_POI, 4 + 4 + 4 + 4 + 4 + name.size() + 1);
        data << uint32(POI_FLAGS);
        data << float(at->GetPositionX());
        data << float(at->GetPositionY());
        data << uint32(POI_ICON_FLAG);
        data << uint32(0);
        data << name;
        player->SendDirectMessage(&data);
    }
}

class npc_remix_metros : public CreatureScript
{
public:
    npc_remix_metros() : CreatureScript("npc_remix_metros") { }

    CreatureAI* GetAI(Creature* creature) const override { return BazaarLifeAI(creature); }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!Active())
            return false;
        ClearGossipMenuFor(player);
        for (uint32 entry : PEOPLE)
        {
            std::string who = Who(entry);
            // (only those here for this Timerunner: Eternus is gone from the Bazaar after the turn)
            if (!who.empty() && creature->FindNearestCreature(entry, 200.0f))
                AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Where is " + who + "?", SENDER_WHERE, entry);
        }
        SendGossipMenuFor(player, TEXT_HOROS + (NPC_METROS - NPC_HOROS), creature);
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action) override
    {
        if (!Active())
            return false;
        CloseGossipMenuFor(player);
        if (sender != SENDER_WHERE)
            return true;
        if (Creature const* there = creature->FindNearestCreature(action, 200.0f))
        {
            CreatureTemplate const* info = sObjectMgr->GetCreatureTemplate(action);
            Flag(player, there, info ? info->Name : Who(action));
            creature->Whisper(Words(player, Acore::StringFormat("{}. I've marked it on your map, $n. Mind the whelps.", Who(action))),
                LANG_UNIVERSAL, player);
        }
        return true;
    }
};

void AddRemixCastScripts()
{
    new npc_remix_metros();
}
