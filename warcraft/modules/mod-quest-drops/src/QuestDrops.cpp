// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Quest drops (mod-quest-drops): an item a quest asks the player to collect from creatures (the bear
 * asses of "bring me 10") drops more often while the quest needs it, where its chance was low (the
 * user: "ones that had ridiculously low drop rates, such as less than 25% chance push the drop rate to
 * 75. For 10% or less set it to 50"): a quest drop (the loot tables' QuestRequired) that rolls on its
 * own chance rolls 75 when that was under 25, 50 when it was 10 or less, and its own chance otherwise.
 * One in a group of items (one of them drops) is left as it is, as raising it would take from the
 * group's other items (24 of the game's 5725 quest drops). Each player who needs the item sees it in
 * the loot, as before; those who don't, don't. ChromieCraft's realm alone (ctl.sh sets
 * QuestDrops.Enable for each realm).
 */

#include "Chat.h"
#include "ChatCommand.h"
#include "Config.h"
#include "Creature.h"
#include "LootMgr.h"
#include "Player.h"
#include "ScriptMgr.h"

namespace
{
    bool enabled = true;

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

    bool OnItemRoll(Player const* /*player*/, LootStoreItem const* item, float& chance, Loot& /*loot*/, LootStore const& /*store*/) override
    {
        if (enabled && item->needs_quest && !item->groupid && !item->reference)
            chance = Raised(chance);
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
        handler->PSendSysMessage("Quest drops: {:g} rolls {:g}{}", chance, enabled ? Raised(chance) : chance, enabled ? "" : " (off)");
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
