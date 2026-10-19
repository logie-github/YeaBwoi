// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The racials that need more than their rows in Spell.dbc (tools/build_data.py makes those):
 *
 *  Spatial Rift (void elves): the first use tears a rift up to 15 yards ahead (a Spatial Rift
 *  creature stands there for 6 seconds) and marks the player; used again while the rift stands,
 *  it takes the player to the rift. The 3 minute cooldown starts when the rift is used or closes
 *  (the spell itself has none, so the second use isn't blocked by the first).
 *
 *  Entropic Embrace (void elves) and Arcane Pulse (nightborne) are plain spells: a proc with its
 *  rate in spell_proc, an area spell with its slow.
 */

#include "Creature.h"
#include "ObjectAccessor.h"
#include "ObjectGuid.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "Spell.h"
#include "SpellAuraEffects.h"
#include "SpellAuras.h"
#include "SpellScript.h"
#include "TemporarySummon.h"
#include <unordered_map>

namespace
{
    constexpr uint32 SPELL_SPATIAL_RIFT = 98711;
    constexpr uint32 SPELL_SPATIAL_RIFT_OPEN = 98712;          // the mark on the player while the rift stands
    constexpr uint32 NPC_SPATIAL_RIFT = 900101;
    constexpr uint32 RIFT_SECONDS = 6;
    constexpr float RIFT_REACH = 15.0f;
    constexpr uint32 RIFT_COOLDOWN = 3 * MINUTE * IN_MILLISECONDS;

    struct Rift
    {
        ObjectGuid creature;
        Position where;
    };
    std::unordered_map<ObjectGuid, Rift> rifts;        // by the player
}

class spell_allied_spatial_rift : public SpellScript
{
    PrepareSpellScript(spell_allied_spatial_rift);

    void HandleDummy(SpellEffIndex /*index*/)
    {
        Player* player = GetCaster() ? GetCaster()->ToPlayer() : nullptr;
        if (!player)
            return;
        auto it = rifts.find(player->GetGUID());
        if (it != rifts.end() && player->HasAura(SPELL_SPATIAL_RIFT_OPEN))
        {
            // Through the rift.
            Position where = it->second.where;
            player->NearTeleportTo(where.GetPositionX(), where.GetPositionY(), where.GetPositionZ(), player->GetOrientation());
            if (Creature* rift = ObjectAccessor::GetCreature(*player, it->second.creature))
                rift->DespawnOrUnsummon();
            rifts.erase(it);
            player->RemoveAurasDueToSpell(SPELL_SPATIAL_RIFT_OPEN);
            return;
        }
        // A rift ahead, as far as the ground allows.
        Position where = player->GetNearPosition(RIFT_REACH, 0.0f);
        player->UpdateAllowedPositionZ(where.GetPositionX(), where.GetPositionY(), where.m_positionZ);
        Rift rift;
        rift.where = where;
        if (Creature* summoned = player->SummonCreature(NPC_SPATIAL_RIFT, where, TEMPSUMMON_TIMED_DESPAWN, RIFT_SECONDS * IN_MILLISECONDS))
            rift.creature = summoned->GetGUID();
        rifts[player->GetGUID()] = rift;
        player->CastSpell(player, SPELL_SPATIAL_RIFT_OPEN, true);
    }

    void Register() override
    {
        OnEffectHitTarget += SpellEffectFn(spell_allied_spatial_rift::HandleDummy, EFFECT_0, SPELL_EFFECT_DUMMY);
    }
};

// The mark: when it goes (the rift used, or closed unused), the cooldown starts.
class spell_allied_spatial_rift_open : public AuraScript
{
    PrepareAuraScript(spell_allied_spatial_rift_open);

    void OnRemove(AuraEffect const* /*effect*/, AuraEffectHandleModes /*mode*/)
    {
        Player* player = GetTarget() ? GetTarget()->ToPlayer() : nullptr;
        if (!player)
            return;
        auto it = rifts.find(player->GetGUID());
        if (it != rifts.end())
        {
            if (Creature* rift = ObjectAccessor::GetCreature(*player, it->second.creature))
                rift->DespawnOrUnsummon();
            rifts.erase(it);
        }
        player->AddSpellCooldown(SPELL_SPATIAL_RIFT, 0, RIFT_COOLDOWN, true);
    }

    void Register() override
    {
        AfterEffectRemove += AuraEffectRemoveFn(spell_allied_spatial_rift_open::OnRemove, EFFECT_0, SPELL_AURA_DUMMY, AURA_EFFECT_HANDLE_REAL);
    }
};

void AddAlliedRacesSpellScripts()
{
    RegisterSpellScript(spell_allied_spatial_rift);
    RegisterSpellScript(spell_allied_spatial_rift_open);
}
