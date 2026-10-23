// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The racials that need more than their rows in Spell.dbc (tools/build_data.py makes those):
 *
 *  Spatial Rift (void elves): the first use tears a rift up to 15 yards ahead (a Spatial Rift
 *  creature stands there for 6 seconds) and marks the player; used again while the rift stands,
 *  it takes the player to the rift. The 3 minute cooldown starts when the rift is used or closes
 *  (the spell itself has none, so the second use isn't blocked by the first).
 *
 *  Entropic Embrace (void elves), Arcane Pulse (nightborne), Embrace of Pa'ku (Zandalari) and
 *  Regeneratin' are plain spells: a proc with its rate in spell_proc, an area spell with its slow,
 *  a periodic heal; Pterrordax Swoop is Slow Fall with another name and cooldown.
 *
 *  Light's Judgment (Lightforged Draenei): an unseen creature stands on the target (or the
 *  player) and, three seconds later, strikes the enemies around it with Holy damage.
 *
 *  Light's Reckoning (Lightforged Draenei): when the player dies, an unseen creature at the body
 *  bursts: Holy damage to enemies, healing to allies. Demonbane (+20% experience from demons)
 *  and City of Gold (+2% money looted) are the player script's.
 */

#include "Creature.h"
#include "LootMgr.h"
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

    constexpr uint32 SPELL_LIGHTS_JUDGMENT_STRIKE = 98724;
    constexpr uint32 SPELL_LIGHTS_RECKONING = 98726;
    constexpr uint32 SPELL_LIGHTS_RECKONING_BURST = 98727;
    constexpr uint32 SPELL_DEMONBANE = 98729;
    constexpr uint32 SPELL_CITY_OF_GOLD = 98734;
    constexpr uint32 NPC_LIGHTS_JUDGMENT = 900102;
    constexpr uint32 NPC_LIGHTS_RECKONING = 900103;
    constexpr float JUDGMENT_REACH = 40.0f;
    constexpr uint32 JUDGMENT_DELAY = 3000;

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

// Light's Judgment: the strike falls where the target (or, with none in reach, the player) stands.
class spell_allied_lights_judgment : public SpellScript
{
    PrepareSpellScript(spell_allied_lights_judgment);

    void HandleDummy(SpellEffIndex /*index*/)
    {
        Player* player = GetCaster() ? GetCaster()->ToPlayer() : nullptr;
        if (!player)
            return;
        WorldObject* at = player;
        if (Unit* target = ObjectAccessor::GetUnit(*player, player->GetTarget()))
            if (target->IsAlive() && player->IsWithinDist(target, JUDGMENT_REACH) && player->IsHostileTo(target))
                at = target;
        TempSummon* stalker = player->SummonCreature(NPC_LIGHTS_JUDGMENT, at->GetPositionX(), at->GetPositionY(), at->GetPositionZ(), at->GetOrientation(),
            TEMPSUMMON_TIMED_DESPAWN, JUDGMENT_DELAY + 2000);
        if (!stalker)
            return;
        stalker->SetFaction(player->GetFaction());
        stalker->SetLevel(player->GetLevel());
        ObjectGuid guid = stalker->GetGUID();
        player->m_Events.AddEventAtOffset([player, guid]()
        {
            if (Creature* strike = ObjectAccessor::GetCreature(*player, guid))
                strike->CastSpell(strike, SPELL_LIGHTS_JUDGMENT_STRIKE, true);
        }, Milliseconds(JUDGMENT_DELAY));
    }

    void Register() override
    {
        OnEffectHitTarget += SpellEffectFn(spell_allied_lights_judgment::HandleDummy, EFFECT_0, SPELL_EFFECT_DUMMY);
    }
};

class AlliedRacesPlayer : public PlayerScript
{
public:
    AlliedRacesPlayer() : PlayerScript("AlliedRacesPlayer", { PLAYERHOOK_ON_PLAYER_JUST_DIED, PLAYERHOOK_ON_GIVE_EXP, PLAYERHOOK_ON_BEFORE_LOOT_MONEY }) { }

    // Light's Reckoning: the burst where a Lightforged Draenei fell.
    void OnPlayerJustDied(Player* player) override
    {
        if (!player->HasSpell(SPELL_LIGHTS_RECKONING))
            return;
        if (TempSummon* stalker = player->SummonCreature(NPC_LIGHTS_RECKONING, player->GetPositionX(), player->GetPositionY(), player->GetPositionZ(),
            player->GetOrientation(), TEMPSUMMON_TIMED_DESPAWN, 3000))
        {
            stalker->SetFaction(player->GetFaction());
            stalker->SetLevel(player->GetLevel());
            stalker->CastSpell(stalker, SPELL_LIGHTS_RECKONING_BURST, true);
        }
    }

    // Demonbane: 20% more experience from demons.
    void OnPlayerGiveXP(Player* player, uint32& amount, Unit* victim, uint8 /*xpSource*/) override
    {
        if (victim && victim->GetTypeId() == TYPEID_UNIT && victim->GetCreatureType() == CREATURE_TYPE_DEMON && player->HasSpell(SPELL_DEMONBANE))
            amount += amount / 5;
    }

    // City of Gold: 2% more money from what's looted.
    void OnPlayerBeforeLootMoney(Player* player, Loot* loot) override
    {
        if (loot && loot->gold && player->HasSpell(SPELL_CITY_OF_GOLD))
            loot->gold += loot->gold / 50;
    }
};

void AddAlliedRacesSpellScripts()
{
    new AlliedRacesPlayer();
    RegisterSpellScript(spell_allied_lights_judgment);
    RegisterSpellScript(spell_allied_spatial_rift);
    RegisterSpellScript(spell_allied_spatial_rift_open);
}
