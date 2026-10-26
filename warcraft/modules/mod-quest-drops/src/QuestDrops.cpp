// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Quest drops (mod-quest-drops): an item a quest asks the player to collect from creatures (the bear
 * asses of "bring me 10") drops from every one of them while the quest needs it, instead of from
 * some. A quest drop (the loot tables' QuestRequired) that rolls on its own chance rolls 100; one in
 * a group of items (one of them drops) is left as it is, as forcing it would keep the group's
 * other items from ever dropping (24 of the game's 5725 quest drops). Each player who needs the
 * item sees it in the loot, as before; those who don't, don't.
 */

#include "Config.h"
#include "LootMgr.h"
#include "ScriptMgr.h"

namespace
{
    bool enabled = true;
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
        if (enabled && item->needs_quest && !item->groupid && !item->reference && chance > 0.0f)
            chance = 100.0f;
        return true;
    }
};

void AddQuestDropsScripts()
{
    new QuestDropsWorld();
    new QuestDropsLoot();
}
