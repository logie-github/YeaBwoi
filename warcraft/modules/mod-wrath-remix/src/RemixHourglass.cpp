// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Moratari's Hourglass (wrath_remix_world_0021_hourglass.sql): Moratari puts it in a Timerunner's
 * bags as they wake at the Bazaar (a death knight's as they come to it from Acherus), and it stays
 * there until they go home. Turned anywhere, it takes them to the Bazaar; turned at the Bazaar, it
 * takes them back to where they came to it from, however they came (the hourglass, a hearthstone,
 * one of the story's quests). Not in a fight, nor dead, nor on a flight, nor in a battleground.
 */

#include "WrathRemix.h"
#include "Chat.h"
#include "ChatCommand.h"
#include "Item.h"
#include "ObjectMgr.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "StringFormat.h"
#include <cmath>

using namespace WrathRemix;

namespace
{
    constexpr uint32 SPELL_TELEPORT_VISUAL = 51347;
    constexpr float BAZAAR_REACH = 60.0f;       // how far the Bazaar is the Bazaar

    void Tell(Player* player, std::string const& text)
    {
        ChatHandler(player->GetSession()).SendSysMessage("|cff8fd3ffMoratari's Hourglass:|r " + text);
    }

    // Turned: to the Bazaar, or from it back where they were.
    void Turn(Player* player)
    {
        if (player->IsBeingTeleported() || !player->IsInWorld())
            return;
        if (!player->IsAlive())
            return Tell(player, "The sand will not run for the dead. Find your body first.");
        if (player->IsInCombat())
            return Tell(player, "Not in the middle of a fight.");
        if (player->IsInFlight())
            return Tell(player, "Not on a flight. Wait until you land.");
        if (player->InBattleground() || player->InArena())
            return Tell(player, "Not here. This fight keeps its own time.");
        if (SceneRunning(player))
            return Tell(player, "Not now. Let the moment finish.");

        if (AtTheBazaar(player))
        {
            Timerunner const t = Get(player);
            if (t.backMap < 0)
                return Tell(player, "You are at the Bazaar, and have come here from nowhere yet that it could take you back to.");
            player->CastSpell(player, SPELL_TELEPORT_VISUAL, true);
            if (!player->TeleportTo(uint32(t.backMap), t.backX, t.backY, t.backZ, t.backO))
                Tell(player, "The way back is closed for now. Try again in a moment.");
            return;
        }

        // (where they were is kept as they go: the teleport's own hook, below)
        Destination const* bazaar = DestinationFor(TRAVEL_BAZAAR);
        if (!bazaar)
            return;
        player->CastSpell(player, SPELL_TELEPORT_VISUAL, true);
        player->TeleportTo(bazaar->map, bazaar->x, bazaar->y, bazaar->z, bazaar->o);
    }
}

namespace WrathRemix
{
    bool AtTheBazaar(Player* player)
    {
        return player->GetMapId() == MAP_NORTHREND && player->GetExactDist2d(BAZAAR_X, BAZAAR_Y) < BAZAAR_REACH;
    }

    void GiveHourglass(Player* player)
    {
        if (!Active() || IsBot(player) || player->HasItemCount(ITEM_HOURGLASS, 1, true) || !sObjectMgr->GetItemTemplate(ITEM_HOURGLASS))
            return;
        // (a death knight has it as they come to the Bazaar, done with Acherus; one gone home, never again)
        if ((player->getClass() == CLASS_DEATH_KNIGHT && !Get(player).chapter) || player->GetQuestRewardStatus(QUEST_BACK_TO_YOUR_OWN_TIME))
            return;
        ItemPosCountVec dest;
        if (player->CanStoreNewItem(NULL_BAG, NULL_SLOT, dest, ITEM_HOURGLASS, 1) != EQUIP_ERR_OK)
        {
            // (never by mail: the bags are where it's looked for, and a second would be waiting)
            Tell(player, "Your bags are full. Make room, and I will find my way back into them.");
            return;
        }
        if (Item* item = player->StoreNewItem(dest, ITEM_HOURGLASS, true))
            player->SendNewItem(item, 1, true, false);
    }

    // .remix hourglass (game masters, for CI's tests): where it is in the bags, and where it would
    // take them back to.
    std::string HourglassReport(Player* player)
    {
        Item* item = player->GetItemByEntry(ITEM_HOURGLASS);
        Timerunner const t = Get(player);
        std::string back = t.backMap < 0 ? std::string("nowhere") :
            Acore::StringFormat("map {} at {:.1f} {:.1f} {:.1f}", t.backMap, t.backX, t.backY, t.backZ);
        if (!item)
            return "Wrath Remix hourglass: none, way back " + back + ".";
        ItemTemplate const* proto = item->GetTemplate();
        return Acore::StringFormat("Wrath Remix hourglass: bag {} slot {} item {} spell {}, at the Bazaar: {}, way back {}.",
            uint32(item->GetBagSlot()), uint32(item->GetSlot()), item->GetGUID().GetRawValue(), proto->Spells[0].SpellId,
            AtTheBazaar(player) ? "yes" : "no", back);
    }
}

class item_remix_hourglass : public ItemScript
{
public:
    item_remix_hourglass() : ItemScript("item_remix_hourglass") { }

    bool OnUse(Player* player, Item* item, SpellCastTargets const& /*targets*/) override
    {
        // (the item's own spell is never cast: the client is told the use is over)
        player->SendEquipError(EQUIP_ERR_NONE, item, nullptr);
        if (Active())
            Turn(player);
        return true;
    }
};

// Where a Timerunner came to the Bazaar from, kept as they go (any teleport there from somewhere
// else but Acherus).
class RemixHourglassPlayer : public PlayerScript
{
public:
    RemixHourglassPlayer() : PlayerScript("RemixHourglassPlayer", { PLAYERHOOK_ON_BEFORE_TELEPORT }) { }

    bool OnPlayerBeforeTeleport(Player* player, uint32 mapid, float x, float y, float /*z*/, float /*orientation*/, uint32 /*options*/, Unit* /*target*/) override
    {
        if (!Active() || !player->IsInWorld() || IsBot(player))
            return true;
        bool toBazaar = mapid == MAP_NORTHREND && std::hypot(x - BAZAAR_X, y - BAZAAR_Y) < BAZAAR_REACH;
        if (toBazaar && !AtTheBazaar(player) && player->GetMapId() != MAP_EBON_HOLD)
            SetWayBack(player, int32(player->GetMapId()), player->GetPositionX(), player->GetPositionY(), player->GetPositionZ(), player->GetOrientation());
        return true;
    }
};

void AddRemixHourglassScripts()
{
    new item_remix_hourglass();
    new RemixHourglassPlayer();
}
