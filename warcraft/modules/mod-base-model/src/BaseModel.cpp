// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Base Model: every character in the realm drawn without its gear (players and the random bots), while
 * the app's Game options have it on, to see their races, skins, faces and hair. Only the looks: the
 * gear stays on, with its stats.
 *
 * The game draws a character's gear from the character's visible item fields (PLAYER_VISIBLE_ITEM_*: an
 * item and its enchantments a slot), the character's own and everyone else's; with Base Model on they're
 * sent empty. The app makes files/base-model/enabled while the switch is on; the world server looks for
 * it every two seconds, so the switch works while the realm runs: each character is bared or dressed
 * again at its next update. BaseModel.Enable = 0 (mod_base_model.conf) keeps it off whatever the switch
 * says. Not changed: creatures (their gear is baked into their textures), and the character list's
 * pictures (made by the game from the list the realm sends).
 *
 * .basemodel says whether it's on and how many of your slots show gear.
 */

#include "Chat.h"
#include "ChatCommand.h"
#include "Config.h"
#include "Log.h"
#include "Player.h"
#include "ScriptMgr.h"

#include <atomic>
#include <cstdlib>
#include <filesystem>

namespace fs = std::filesystem;
using namespace Acore::ChatCommands;

namespace
{
    constexpr uint32 CHECK_MS = 2000;

    std::atomic<bool> bare{ false };
    bool allowed = true;
    fs::path marker;

    // Whether this character is drawn bare now (it's changed at its own update, in its map's thread).
    struct Shown : DataMap::Base
    {
        bool bare = false;
    };

    void Hide(Player* player, uint8 slot)
    {
        player->SetUInt32Value(PLAYER_VISIBLE_ITEM_1_ENTRYID + (slot * 2), 0);
        player->SetUInt32Value(PLAYER_VISIBLE_ITEM_1_ENCHANTMENT + (slot * 2), 0);
    }

    void Apply(Player* player)
    {
        bool want = bare.load();
        Shown* shown = player->CustomData.GetDefault<Shown>("BaseModel");
        if (shown->bare == want)
            return;
        shown->bare = want;
        for (uint8 slot = EQUIPMENT_SLOT_START; slot < EQUIPMENT_SLOT_END; ++slot)
        {
            if (want)
                Hide(player, slot);
            else
                player->SetVisibleItemSlot(slot, player->GetItemByPos(INVENTORY_SLOT_BAG_0, slot));
        }
    }

    bool MarkerThere()
    {
        std::error_code error;
        return allowed && !marker.empty() && fs::exists(marker, error);
    }
}

class BaseModelWorld : public WorldScript
{
public:
    BaseModelWorld() : WorldScript("BaseModelWorld", { WORLDHOOK_ON_AFTER_CONFIG_LOAD, WORLDHOOK_ON_UPDATE }) { }

    void OnAfterConfigLoad(bool /*reload*/) override
    {
        allowed = sConfigMgr->GetOption<bool>("BaseModel.Enable", true);
        fs::path folder = sConfigMgr->GetOption<std::string>("BaseModel.Folder", "");
        if (folder.empty())
        {
            // (the app's folder for it: beside the game's)
            char const* client = std::getenv("WOW_CLIENT_DIR");
            if (client && *client)
            {
                fs::path clientDir = fs::path(client).lexically_normal();
                if (clientDir.filename().empty())
                    clientDir = clientDir.parent_path();
                folder = clientDir.parent_path() / "base-model";
            }
        }
        marker = folder.empty() ? fs::path() : folder / "enabled";
        bare = MarkerThere();
        LOG_INFO("module", "Base Model: {} ({})", bare ? "on" : "off", marker.string());
    }

    void OnUpdate(uint32 diff) override
    {
        _since += diff;
        if (_since < CHECK_MS)
            return;
        _since = 0;
        bool now = MarkerThere();
        if (now != bare.load())
        {
            bare = now;
            LOG_INFO("module", "Base Model: {}", now ? "on" : "off");
        }
    }

private:
    uint32 _since = 0;
};

class BaseModelPlayer : public PlayerScript
{
public:
    BaseModelPlayer() : PlayerScript("BaseModelPlayer", { PLAYERHOOK_ON_AFTER_SET_VISIBLE_ITEM_SLOT, PLAYERHOOK_ON_UPDATE }) { }

    // Gear put on (or the character loaded) while it's on: not shown.
    void OnPlayerAfterSetVisibleItemSlot(Player* player, uint8 slot, Item* /*item*/) override
    {
        if (bare.load() && slot < EQUIPMENT_SLOT_END)
            Hide(player, slot);
    }

    void OnPlayerUpdate(Player* player, uint32 /*diff*/) override
    {
        Apply(player);
    }
};

class BaseModelCommands : public CommandScript
{
public:
    BaseModelCommands() : CommandScript("BaseModelCommands") { }

    ChatCommandTable GetCommands() const override
    {
        static ChatCommandTable commandTable =
        {
            { "basemodel", HandleState, SEC_PLAYER, Console::No },
        };
        return commandTable;
    }

    static bool HandleState(ChatHandler* handler)
    {
        Player* player = handler->GetSession()->GetPlayer();
        uint32 shown = 0, worn = 0;
        for (uint8 slot = EQUIPMENT_SLOT_START; slot < EQUIPMENT_SLOT_END; ++slot)
        {
            if (player->GetUInt32Value(PLAYER_VISIBLE_ITEM_1_ENTRYID + (slot * 2)))
                ++shown;
            if (player->GetItemByPos(INVENTORY_SLOT_BAG_0, slot))
                ++worn;
        }
        handler->PSendSysMessage("Base Model: {}; gear worn in {} slots, shown in {}", bare.load() ? "on" : "off", worn, shown);
        return true;
    }
};

void AddBaseModelScripts()
{
    new BaseModelWorld();
    new BaseModelPlayer();
    new BaseModelCommands();
}
