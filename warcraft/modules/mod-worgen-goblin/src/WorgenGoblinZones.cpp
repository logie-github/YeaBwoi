// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The starting areas as they're played (mod-worgen-goblin, data/zones, tools/zones): what
 * Cataclysm's game and server do there that 3.3.5a's don't.
 *
 *  - Gilneas's terrain swaps (CPP's terrain_swap_defaults and their conditions): Cataclysm shows one
 *    map of Gilneas (654) with another's tiles over it as the story goes. Here each is a map of its
 *    own (tools/zones/package.py), so a player of Gilneas is on the one their quests say: 638
 *    (Gilneas City before the curse) until Last Stand (14222) is done, then 654, then 655 (the
 *    land broken by the Cataclysm) once Leader of the Pack (14386) is. Any teleport onto Gilneas
 *    lands on that one; finishing either quest takes the player to it where they stand.
 *  - Cataclysm's spell effects 3.3.5a hasn't (tools/zones/spells.py, EFFECT_HOOKS): dummies whose
 *    second misc value says which (ZoneHook), each answered by its script in spell_script_names: a
 *    movie (its narration: Cataclysm Classic's game has the Worgen's and Goblins' movies' sound, not
 *    their pictures), the phases updated (the area's phase auras, spell_area), a teleport to the
 *    spell's place (spell_target_position).
 */

#include "WorgenGoblinZonesIds.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "SpellInfo.h"
#include "SpellScript.h"
#include "SpellScriptLoader.h"

namespace
{
    constexpr uint32 QUEST_LAST_STAND = WorgenGoblinZones::Id(14222);
    constexpr uint32 QUEST_LEADER_OF_THE_PACK = WorgenGoblinZones::Id(14386);
    constexpr uint32 MAP_GILNEAS = 654, MAP_GILNEAS_CITY = 638, MAP_GILNEAS_BROKEN = 655;

    enum class ZoneHook : int32
    {
        Movie = 1,
        Phases = 2,
        Teleport = 3,
    };

    bool IsGilneas(uint32 map)
    {
        return map == MAP_GILNEAS || map == MAP_GILNEAS_CITY || map == MAP_GILNEAS_BROKEN;
    }

    // The map of Gilneas a player's story has them on.
    uint32 GilneasMap(Player const* player)
    {
        if (!player->GetQuestRewardStatus(QUEST_LAST_STAND))
            return MAP_GILNEAS_CITY;
        return player->GetQuestRewardStatus(QUEST_LEADER_OF_THE_PACK) ? MAP_GILNEAS_BROKEN : MAP_GILNEAS;
    }

    // A player on another map of Gilneas than theirs, taken to theirs where they stand.
    void ToGilneasMap(Player* player)
    {
        if (!IsGilneas(player->GetMapId()) || player->IsBeingTeleported())
            return;
        uint32 map = GilneasMap(player);
        if (map != player->GetMapId())
            player->TeleportTo(map, player->GetPositionX(), player->GetPositionY(), player->GetPositionZ(), player->GetOrientation());
    }

    bool IsHook(SpellInfo const* spell, SpellEffIndex effect, ZoneHook hook)
    {
        return spell->Effects[effect].Effect == SPELL_EFFECT_DUMMY && spell->Effects[effect].MiscValueB == int32(hook);
    }
}

class WorgenGoblinZonesPlayer : public PlayerScript
{
public:
    WorgenGoblinZonesPlayer() : PlayerScript("WorgenGoblinZonesPlayer", { PLAYERHOOK_ON_BEFORE_TELEPORT, PLAYERHOOK_ON_LOGIN }) { }

    // Onto Gilneas: onto the player's own map of it.
    bool OnPlayerBeforeTeleport(Player* player, uint32 mapid, float x, float y, float z, float orientation, uint32 options, Unit* target) override
    {
        if (!IsGilneas(mapid))
            return true;
        uint32 map = GilneasMap(player);
        if (map == mapid)
            return true;
        player->TeleportTo(map, x, y, z, orientation, options, target);
        return false;
    }

    void OnPlayerLogin(Player* player) override
    {
        ToGilneasMap(player);
    }
};

// A quest of Gilneas's handed in that changes its map.
class WorgenGoblinZonesCreature : public AllCreatureScript
{
public:
    WorgenGoblinZonesCreature() : AllCreatureScript("WorgenGoblinZonesCreature") { }

    bool CanCreatureQuestReward(Player* player, Creature* /*creature*/, Quest const* quest, uint32 /*opt*/) override
    {
        if (quest->GetQuestId() == QUEST_LAST_STAND || quest->GetQuestId() == QUEST_LEADER_OF_THE_PACK)
            ToGilneasMap(player);
        return false;
    }
};

class WorgenGoblinZonesObject : public AllGameObjectScript
{
public:
    WorgenGoblinZonesObject() : AllGameObjectScript("WorgenGoblinZonesObject") { }

    bool CanGameObjectQuestReward(Player* player, GameObject* /*go*/, Quest const* quest, uint32 /*opt*/) override
    {
        if (quest->GetQuestId() == QUEST_LAST_STAND || quest->GetQuestId() == QUEST_LEADER_OF_THE_PACK)
            ToGilneasMap(player);
        return false;
    }
};

// Cataclysm's 45 (a movie): its narration, to the player it's for.
class spell_worgen_goblin_zones_movie : public SpellScript
{
    PrepareSpellScript(spell_worgen_goblin_zones_movie);

    void Handle(SpellEffIndex effect)
    {
        if (!IsHook(GetSpellInfo(), effect, ZoneHook::Movie))
            return;
        Player* player = GetHitPlayer();
        if (uint32 sound = GetSpellInfo()->Effects[effect].MiscValue; player && sound)
            player->PlayDirectSound(sound, player);
    }

    void Register() override
    {
        OnEffectHitTarget += SpellEffectFn(spell_worgen_goblin_zones_movie::Handle, EFFECT_ALL, SPELL_EFFECT_DUMMY);
    }
};

// Cataclysm's 167 and 170 (the phases and the area's auras updated): the area's phase auras
// (spell_area) as the player's quests now say, and their map of Gilneas.
class spell_worgen_goblin_zones_phases : public SpellScript
{
    PrepareSpellScript(spell_worgen_goblin_zones_phases);

    void Handle(SpellEffIndex effect)
    {
        if (!IsHook(GetSpellInfo(), effect, ZoneHook::Phases))
            return;
        if (Player* player = GetHitPlayer())
        {
            player->UpdateZoneDependentAuras(player->GetZoneId());
            player->UpdateAreaDependentAuras(player->GetAreaId());
            ToGilneasMap(player);
        }
    }

    void Register() override
    {
        OnEffectHitTarget += SpellEffectFn(spell_worgen_goblin_zones_phases::Handle, EFFECT_ALL, SPELL_EFFECT_DUMMY);
    }
};

// Cataclysm's 252 (a teleport with a loading screen): to the spell's place (on Gilneas, the
// player's own map of it: WorgenGoblinZonesPlayer).
class spell_worgen_goblin_zones_teleport : public SpellScript
{
    PrepareSpellScript(spell_worgen_goblin_zones_teleport);

    void Handle(SpellEffIndex effect)
    {
        if (!IsHook(GetSpellInfo(), effect, ZoneHook::Teleport))
            return;
        Player* player = GetHitPlayer();
        WorldLocation const* dest = GetHitDest();
        if (player && dest && dest->GetMapId() != MAPID_INVALID)
            player->TeleportTo(*dest);
    }

    void Register() override
    {
        OnEffectHitTarget += SpellEffectFn(spell_worgen_goblin_zones_teleport::Handle, EFFECT_ALL, SPELL_EFFECT_DUMMY);
    }
};

void AddWorgenGoblinZonesScripts()
{
    new WorgenGoblinZonesPlayer();
    new WorgenGoblinZonesCreature();
    new WorgenGoblinZonesObject();
    RegisterSpellScript(spell_worgen_goblin_zones_movie);
    RegisterSpellScript(spell_worgen_goblin_zones_phases);
    RegisterSpellScript(spell_worgen_goblin_zones_teleport);
}
