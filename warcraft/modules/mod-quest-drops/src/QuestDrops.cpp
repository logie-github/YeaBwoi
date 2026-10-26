// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Quest drops (mod-quest-drops), ChromieCraft's realm (ctl.sh sets QuestDrops.Enable for each realm):
 *
 *  An item a quest asks the player to collect (a quest drop: the loot tables' QuestRequired) drops more
 *  often where its chance was low (the user: "ones that had ridiculously low drop rates, such as less
 *  than 25% chance push the drop rate to 75. For 10% or less set it to 50"): 75 where it was under 25,
 *  50 where it was 10 or less, its own from 25 up. Each player who needs it sees it in the loot, as
 *  before; those who don't, don't.
 *
 *  Every other drop from creatures rolls ten times its chance, 10 at least and 100 at most (the user:
 *  "Increase other drop rates by 10 total so 1% drop rate in rare etc item becomes 10 but 100 stays at
 *  100 but not less than 10"); a reference (the roll into a table of world drops: the greens, blues and
 *  epics) too. Chests, skinning, mining, herbs, fishing and pickpocketing keep their chances.
 *
 * Items in a loot group (one of the group drops, by their chances) are left as they are: a higher chance
 * for one would take from the group's others (24 of the game's 5725 quest drops are in one).
 */

#include "Chat.h"
#include "ChatCommand.h"
#include "Config.h"
#include "Creature.h"
#include "LootMgr.h"
#include "Player.h"
#include "ScriptMgr.h"
#include <algorithm>

namespace
{
    bool enabled = true;

    // Any other drop's chance, as the module rolls it: ten times its own, 10 at least, 100 at most (the
    // user: "Increase other drop rates by 10 total so 1% drop rate in rare etc item becomes 10 but 100
    // stays at 100 but not less than 10").
    float RaisedOther(float chance)
    {
        if (chance <= 0.0f)
            return chance;
        return std::min(100.0f, std::max(10.0f, chance * 10.0f));
    }

    // A quest drop's chance, as the module rolls it.
    float Raised(float chance)
    {
        if (chance <= 0.0f)
            return chance;
        if (chance <= 10.0f)
            return 50.0f;
        if (chance < 25.0f)
            return 75.0f;
        return chance;
    }
}

class QuestDropsWorld : public WorldScript
{
public:
    QuestDropsWorld() : WorldScript("QuestDropsWorld", { WORLDHOOK_ON_AFTER_CONFIG_LOAD }) { }

    void OnAfterConfigLoad(bool /*reload*/) override
    {
        enabled = sConfigMgr->GetOption<bool>("QuestDrops.Enable", true);
    }
};

class QuestDropsLoot : public GlobalScript
{
public:
    QuestDropsLoot() : GlobalScript("QuestDropsLoot", { GLOBALHOOK_ON_ITEM_ROLL }) { }

    bool OnItemRoll(Player const* /*player*/, LootStoreItem const* item, float& chance, Loot& loot, LootStore const& store) override
    {
        if (!enabled || item->groupid)
            return true;
        if (item->needs_quest)
        {
            if (!item->reference)
                chance = Raised(chance);
        }
        // (a creature's: its own table, or a reference from it, the roll into a table of world drops; a
        // chest's references keep theirs)
        else if (loot.sourceWorldObjectGUID.IsCreatureOrVehicle() && (&store == &LootTemplates_Creature || &store == &LootTemplates_Reference))
            chance = RaisedOther(chance);
        return true;
    }
};

using namespace Acore::ChatCommands;

// Game masters' (CI's realm test): .questdrops chance <percent> says what a quest drop of that chance
// rolls here; .questdrops near <entry> takes the game master beside the nearest living creature of an
// entry (300 yards at most), within looting reach of it once it falls.
class QuestDropsCommands : public CommandScript
{
public:
    QuestDropsCommands() : CommandScript("QuestDropsCommands") { }

    ChatCommandTable GetCommands() const override
    {
        static ChatCommandTable questDropsTable =
        {
            { "chance", HandleChance, SEC_GAMEMASTER, Console::Yes },
            { "near",   HandleNear,   SEC_GAMEMASTER, Console::No },
        };
        static ChatCommandTable commandTable =
        {
            { "questdrops", questDropsTable },
        };
        return commandTable;
    }

    static bool HandleChance(ChatHandler* handler, float chance)
    {
        handler->PSendSysMessage("Quest drops: {:g} rolls {:g} for a quest item, {:g} for any other{}", chance,
            enabled ? Raised(chance) : chance, enabled ? RaisedOther(chance) : chance, enabled ? "" : " (off)");
        return true;
    }

    static bool HandleNear(ChatHandler* handler, uint32 entry)
    {
        Player* player = handler->GetSession()->GetPlayer();
        Creature* creature = player->FindNearestCreature(entry, 300.0f);
        if (!creature)
        {
            handler->PSendSysMessage("Quest drops near: no creature {} within 300 yards.", entry);
            return true;
        }
        Position at = creature->GetNearPosition(1.5f, 0.0f);
        player->NearTeleportTo(at.GetPositionX(), at.GetPositionY(), creature->GetPositionZ() + 0.5f, player->GetAngle(creature));
        handler->PSendSysMessage("Quest drops near: {} at {:.1f} yards.", entry, player->GetDistance(creature));
        return true;
    }
};

void AddQuestDropsScripts()
{
    new QuestDropsWorld();
    new QuestDropsLoot();
    new QuestDropsCommands();
}
