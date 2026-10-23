// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The goggles (mod-skyborne): an item whose spell is a 15 minute aura (data/sql/db-world/skyborne_goggles.sql, the
 * spell's rows in skyborne_world.sql and the client's Spell table). While any character has the aura, the visible
 * equipment of every female character in the world is cleared; it comes back when the last of the auras ends.
 * Every character who has none is given the goggles at login.
 */

#include "AccountMgr.h"
#include "Item.h"
#include "ObjectAccessor.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "SpellAuraEffects.h"
#include "SpellScript.h"
#include "WorldSession.h"
#include <cctype>
#include <string>

namespace
{
    constexpr uint32 GOGGLES = 98708;       // the item, and its spell
    uint32 running = 0;                     // the goggles' auras in the world

    bool Female(Player* player)
    {
        return player && player->getGender() == GENDER_FEMALE;
    }

    void Clear(Player* player)
    {
        for (uint8 slot = EQUIPMENT_SLOT_START; slot < EQUIPMENT_SLOT_END; ++slot)
            player->SetVisibleItemSlot(slot, nullptr);
    }

    void Restore(Player* player)
    {
        for (uint8 slot = EQUIPMENT_SLOT_START; slot < EQUIPMENT_SLOT_END; ++slot)
            player->SetVisibleItemSlot(slot, player->GetItemByPos(INVENTORY_SLOT_BAG_0, slot));
    }

    // The characters of the playerbots' random accounts are left without the item.
    bool RandomBot(Player* player)
    {
        std::string name;
        if (!player->GetSession() || !AccountMgr::GetName(player->GetSession()->GetAccountId(), name))
            return false;
        for (char& c : name)
            c = char(std::tolower(static_cast<unsigned char>(c)));
        return name.rfind("rndbot", 0) == 0;
    }

    void ForEveryFemale(void (*apply)(Player*))
    {
        for (auto const& [guid, player] : ObjectAccessor::GetPlayers())
            if (Female(player))
                apply(player);
    }
}

class spell_skyborne_goggles : public AuraScript
{
    PrepareAuraScript(spell_skyborne_goggles);

    void OnApply(AuraEffect const* /*aurEff*/, AuraEffectHandleModes /*mode*/)
    {
        if (++running == 1)
            ForEveryFemale(Clear);
    }

    void OnRemove(AuraEffect const* /*aurEff*/, AuraEffectHandleModes /*mode*/)
    {
        if (running && --running == 0)
            ForEveryFemale(Restore);
    }

    void Register() override
    {
        AfterEffectApply += AuraEffectApplyFn(spell_skyborne_goggles::OnApply, EFFECT_0, SPELL_AURA_DUMMY, AURA_EFFECT_HANDLE_REAL);
        AfterEffectRemove += AuraEffectRemoveFn(spell_skyborne_goggles::OnRemove, EFFECT_0, SPELL_AURA_DUMMY, AURA_EFFECT_HANDLE_REAL);
    }
};

class SkyborneGogglesPlayer : public PlayerScript
{
public:
    SkyborneGogglesPlayer() : PlayerScript("SkyborneGogglesPlayer", { PLAYERHOOK_ON_LOGIN, PLAYERHOOK_ON_EQUIP }) { }

    void OnPlayerLogin(Player* player) override
    {
        if (running && Female(player))
            Clear(player);
        if (!RandomBot(player) && !player->HasItemCount(GOGGLES, 1, true))
            player->AddItem(GOGGLES, 1);
    }

    void OnPlayerEquip(Player* player, Item* /*item*/, uint8 /*bag*/, uint8 /*slot*/, bool /*update*/) override
    {
        if (running && Female(player))
            Clear(player);
    }
};

void AddSkyborneGogglesScripts()
{
    RegisterSpellScript(spell_skyborne_goggles);
    new SkyborneGogglesPlayer();
}
