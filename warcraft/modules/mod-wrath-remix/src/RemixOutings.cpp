// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Wrath Remix: outings (remix_outing). Somewhere Moratari takes the Timerunner (an evening in
 * Dalaran, her tent at the Bazaar, a flight on her back): they go there, in a phase of its own when
 * it's private (her tent: no one else is in it), with their gear unseen when it says so (it's only
 * looks: nothing comes off), a scene plays (remix_scene); then their gear is seen again, the world's
 * phase is theirs again, and they're back where they were, and she at her post.
 *
 * A flight is a taxi ride on her (her dragon shape is the mount) from one of the game's flight
 * points to another, her words on the way (remix_outing_line), then back.
 *
 * The screen's fade (a scene's ACT_FADE) is the app's add-on's: an add-on message to the player
 * ("WowRemix<tab>fade <milliseconds> <words>"), and the words in chat as well.
 */

#include "WrathRemix.h"
#include "Chat.h"
#include "DatabaseEnv.h"
#include "Log.h"
#include "MotionMaster.h"
#include "ObjectAccessor.h"
#include "ObjectMgr.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "WorldPacket.h"
#include "WorldSession.h"
#include <functional>
#include <memory>
#include <unordered_map>
#include <vector>

using namespace WrathRemix;

namespace
{
    constexpr uint32 PHASE_OUTING = 32768;   // a private outing's (her tent's things are in it too)

    struct OutingLine
    {
        uint32 at;
        std::string text;
    };

    struct Outing
    {
        uint32 id, map, scene, flightFrom, flightTo, mount;
        float x, y, z, o;
        bool privately, undress, back;
        std::vector<OutingLine> lines;
    };

    std::unordered_map<uint32, Outing> outings;

    // The Timerunner's outing while it lasts: where they came from.
    struct OnOutingState : public DataMap::Base
    {
        uint32 outing = 0;
        uint32 map = 0;
        float x = 0.0f, y = 0.0f, z = 0.0f, o = 0.0f;
        uint32 phase = PHASEMASK_NORMAL;
        bool undressed = false;
    };

    OnOutingState* StateOf(Player* player)
    {
        return player->CustomData.GetDefault<OnOutingState>("remix_outing");
    }

    void Undress(Player* player)
    {
        for (uint8 slot = EQUIPMENT_SLOT_START; slot < EQUIPMENT_SLOT_END; ++slot)
            player->SetVisibleItemSlot(slot, nullptr);
    }

    void Dress(Player* player)
    {
        for (uint8 slot = EQUIPMENT_SLOT_START; slot < EQUIPMENT_SLOT_END; ++slot)
            player->SetVisibleItemSlot(slot, player->GetItemByPos(INVENTORY_SLOT_BAG_0, slot));
    }

    // The outing over: their gear seen, the world's phase, and back where they were.
    void EndOuting(Player* player)
    {
        OnOutingState* state = StateOf(player);
        if (!state->outing)
            return;
        auto it = outings.find(state->outing);
        bool back = it == outings.end() || it->second.back;
        if (state->undressed)
            Dress(player);
        if (player->GetPhaseMask() == PHASE_OUTING)
            player->SetPhaseMask(state->phase ? state->phase : PHASEMASK_NORMAL, true);
        uint32 map = state->map;
        float x = state->x, y = state->y, z = state->z, o = state->o;
        *state = OnOutingState();
        if (back)
            TeleportSoon(player, map, x, y, z, o);
    }

    void Fly(Player* player, Outing const& outing);

    // There: its phase, their looks, its scene, and when the scene's over, the flight or the way
    // back.
    void Arrived(Player* player, Outing const& outing)
    {
        OnOutingState* state = StateOf(player);
        if (outing.privately)
            player->SetPhaseMask(PHASE_OUTING, true);
        if (outing.undress)
        {
            Undress(player);
            state->undressed = true;
        }
        uint32 lasts = 2000;
        if (outing.scene && PlayScene(player, outing.scene))
            lasts = SceneDuration(outing.scene) + 2500;
        ObjectGuid guid = player->GetGUID();
        uint32 id = outing.id;
        player->m_Events.AddEventAtOffset([guid, id]()
        {
            Player* p = ObjectAccessor::FindPlayer(guid);
            if (!p || !p->IsInWorld() || StateOf(p)->outing != id)
                return;
            auto it = outings.find(id);
            if (it != outings.end() && it->second.flightFrom)
                Fly(p, it->second);
            else
                EndOuting(p);
        }, Milliseconds(lasts));
    }

    // (a far journey can keep them on a loading screen: a look a second, for half a minute)
    void WhenThere(ObjectGuid guid, uint32 id, uint32 tries)
    {
        Player* player = ObjectAccessor::FindPlayer(guid);
        if (!player || !tries)
            return;
        player->m_Events.AddEventAtOffset([guid, id, tries]()
        {
            Player* p = ObjectAccessor::FindPlayer(guid);
            if (!p || StateOf(p)->outing != id)
                return;
            auto it = outings.find(id);
            if (it == outings.end())
                return;
            Outing const& outing = it->second;
            if (p->IsInWorld() && !p->IsBeingTeleported() && p->GetMapId() == outing.map && p->GetExactDist2d(outing.x, outing.y) < 30.0f)
                Arrived(p, outing);
            else if (tries == 1)
                EndOuting(p);
            else
                WhenThere(guid, id, tries - 1);
        }, 1s);
    }

    // On her back: from the flight point to the other, her words on the way; back when they land.
    void Fly(Player* player, Outing const& outing)
    {
        uint32 path = 0, cost = 0;
        sObjectMgr->GetTaxiPath(outing.flightFrom, outing.flightTo, path, cost);
        if (!path)
        {
            EndOuting(player);
            return;
        }
        player->RemoveAurasByType(SPELL_AURA_MOUNTED);
        player->CombatStop();
        player->m_taxi.ClearTaxiDestinations();
        player->m_taxi.AddTaxiDestination(outing.flightFrom);
        player->m_taxi.AddTaxiDestination(outing.flightTo);
        player->GetSession()->SendDoFlight(outing.mount, path);
        ObjectGuid guid = player->GetGUID();
        for (OutingLine const& line : outing.lines)
        {
            std::string text = line.text;
            player->m_Events.AddEventAtOffset([guid, text]()
            {
                if (Player* p = ObjectAccessor::FindPlayer(guid); p && p->IsInWorld() && p->IsInFlight())
                    MoratariSays(p, NPC_MORATARI, text);
            }, Milliseconds(line.at));
        }
        // (landed: a look every two seconds, for up to ten minutes)
        uint32 id = outing.id;
        auto landed = std::make_shared<std::function<void(uint32)>>();
        *landed = [guid, id, landed](uint32 tries)
        {
            Player* p = ObjectAccessor::FindPlayer(guid);
            if (!p || StateOf(p)->outing != id)
                return;
            if (!p->IsInFlight() || !tries)
            {
                EndOuting(p);
                return;
            }
            p->m_Events.AddEventAtOffset([landed, tries]() { (*landed)(tries - 1); }, 2s);
        };
        player->m_Events.AddEventAtOffset([landed]() { (*landed)(300); }, 5s);
    }
}

namespace WrathRemix
{
    void LoadOutings()
    {
        outings.clear();
        if (!Active())
            return;
        if (QueryResult result = WorldDatabase.Query("SELECT `id`, `map`, `x`, `y`, `z`, `o`, `scene`, `private`, `undress`, `back`, `flight_from`, `flight_to`, `mount` FROM `remix_outing`"))
        {
            do
            {
                Field* f = result->Fetch();
                Outing o;
                o.id = f[0].Get<uint32>();
                o.map = f[1].Get<uint32>();
                o.x = f[2].Get<float>();
                o.y = f[3].Get<float>();
                o.z = f[4].Get<float>();
                o.o = f[5].Get<float>();
                o.scene = f[6].Get<uint32>();
                o.privately = f[7].Get<uint8>() != 0;
                o.undress = f[8].Get<uint8>() != 0;
                o.back = f[9].Get<uint8>() != 0;
                o.flightFrom = f[10].Get<uint32>();
                o.flightTo = f[11].Get<uint32>();
                o.mount = f[12].Get<uint32>();
                outings[o.id] = std::move(o);
            } while (result->NextRow());
        }
        if (QueryResult result = WorldDatabase.Query("SELECT `outing`, `at_ms`, `text` FROM `remix_outing_line` ORDER BY `outing`, `at_ms`"))
        {
            do
            {
                Field* f = result->Fetch();
                auto it = outings.find(f[0].Get<uint32>());
                if (it != outings.end())
                    it->second.lines.push_back({ f[1].Get<uint32>(), f[2].Get<std::string>() });
            } while (result->NextRow());
        }
        LOG_INFO("module", "Wrath Remix: {} outings", outings.size());
    }

    bool OnOuting(Player* player)
    {
        return StateOf(player)->outing != 0;
    }

    bool StartOuting(Player* player, uint32 id)
    {
        auto it = outings.find(id);
        if (!Active() || it == outings.end() || OnOuting(player) || SceneRunning(player) || player->IsInCombat() || player->IsInFlight() || !player->IsAlive())
            return false;
        Outing const& outing = it->second;
        OnOutingState* state = StateOf(player);
        state->outing = id;
        state->map = player->GetMapId();
        state->x = player->GetPositionX();
        state->y = player->GetPositionY();
        state->z = player->GetPositionZ();
        state->o = player->GetOrientation();
        state->phase = player->GetPhaseMask();
        Record(player, "outing", id, 0);
        // (a flight starts at its flight point: there first, its scene, then up)
        TeleportSoon(player, outing.map, outing.x, outing.y, outing.z, outing.o);
        WhenThere(player->GetGUID(), id, 30);
        return true;
    }

    void ScreenFade(Player* player, uint32 milliseconds, std::string const& text)
    {
        std::string words = Words(player, text);
        WorldPacket data;
        ChatHandler::BuildChatPacket(data, CHAT_MSG_WHISPER, LANG_ADDON, player, player,
            Acore::StringFormat("WowRemix\tfade {} {}", milliseconds, words));
        player->SendDirectMessage(&data);
        if (!words.empty())
            ChatHandler(player->GetSession()).SendSysMessage("|cffc8b8a0" + words + "|r");
    }
}

class RemixOutingsPlayer : public PlayerScript
{
public:
    RemixOutingsPlayer() : PlayerScript("RemixOutingsPlayer", { PLAYERHOOK_ON_LOGOUT }) { }

    // (one who leaves the game in the middle of one comes back where it was, dressed, in the world)
    void OnPlayerLogout(Player* player) override
    {
        if (Active() && OnOuting(player))
        {
            OnOutingState* state = StateOf(player);
            if (state->undressed)
                Dress(player);
            *state = OnOutingState();
        }
    }
};

void AddRemixOutingScripts()
{
    new RemixOutingsPlayer();
}
