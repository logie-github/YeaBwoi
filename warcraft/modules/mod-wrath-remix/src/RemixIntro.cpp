// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The opening (WrathRemix.Intro, STORY.md): a new Timerunner's first moments are not at the Bazaar
 * but in the war, at its worst hour. On the way to the start of it, Eternus drags them through the
 * end: the Broken Front in Icecrown, the Citadel ahead, Highlord Tirion Fordring and the Argent
 * Crusade holding the line with them, the Scourge pouring out of the snow. They arrive as the hero
 * they are in their own time, the Light's wings on them, and the dead fall to them in a blow or
 * three; nothing there can hurt them.
 *
 * Ghouls claw up out of the ice at the Lich King's word, abominations and crypt fiends follow,
 * frost bombs and plague burst across the field, a frost wyrm strafes the line, the val'kyr come at
 * his call; then the Lich King himself, and the Scourge kneel. Tirion charges him; the winter takes
 * the paladin to his knee; Arthas turns to the Timerunner, and raises Frostmourne. And time stops.
 * Moratari steps out of a rift, Eternus says why they were shown this, and she pulls them out: to
 * the Infinite Bazaar, years earlier, where the story begins.
 *
 * It is the Timerunner's alone: a phase of its own (no one else there, none of the Front's own
 * creatures), and everyone in it summoned for them. Their own voices are the game's (the Lich King's
 * and Tirion's lines from Icecrown Citadel, with their sound). Once seen it is not seen again
 * (remix_character.intro); one cut short (a logout) plays again at the next login. Death knights,
 * who have Acherus, and the realm's bots don't have it. `.remix intro` plays it for a game master,
 * `.remix intro skip` ends it.
 */

#include "WrathRemix.h"
#include "Chat.h"
#include "Config.h"
#include "Creature.h"
#include "CreatureAI.h"
#include "GameObject.h"
#include "Map.h"
#include "ObjectAccessor.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "ScriptedCreature.h"
#include "SpellAuras.h"
#include "Random.h"
#include "TemporarySummon.h"
#include <atomic>
#include <cmath>
#include <functional>
#include <iterator>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

using namespace WrathRemix;

namespace
{
    // The Broken Front, facing the Citadel.
    constexpr float FRONT_X = 6650.0f, FRONT_Y = 1145.0f, FRONT_Z = 274.0f, FRONT_O = 2.30f;
    constexpr float FRONT_REACH = 150.0f;
    constexpr uint32 PHASE_INTRO = 16384;

    enum IntroPeople : uint32
    {
        GHOUL = 770120, SPIKED_GHOUL, ABOMINATION, CRYPT_FIEND, DEATHBRINGER, FROST_WYRM, VALKYR, LICH_KING,
        TIRION, CRUSADER, CRUSADER_2, MARINE, REAVER, LIGHT, PLAGUE, ETERNUS_VOICE, LICH_KING_VOICE,
        INTRO_FIRST = GHOUL, INTRO_LAST = LICH_KING_VOICE,
    };

    enum IntroSpells : uint32
    {
        SPELL_AVENGING_WRATH   = 31884,  // the Light's wings, on the Timerunner
        SPELL_TELEPORT_VISUAL  = 51347,
        SPELL_TIME_RIFT        = 31387,
        SPELL_TIME_STOP        = 60074,  // the Culling of Stratholme's, its bubble
        SPELL_TIME_STOP_VISUAL = 25171,
        SPELL_GHOUL_RISE       = 47448,  // Risen Ghoul Spawn-In: out of the ground
        SPELL_EMERGE           = 50142,
        SPELL_FROST_BOMB_MARK  = 70022,  // Sindragosa's, on the ground where it will land
        SPELL_EXPLOSION        = 60081,
        SPELL_HUGE_EXPLOSION   = 46225,
        SPELL_GREEN_EXPLOSION  = 43401,
        SPELL_DEATH_AND_DECAY  = 43265,
        SPELL_PILLAR_OF_FIRE   = 43541,
        SPELL_CAMERA_SHAKE     = 47533,
        SPELL_CAMERA_SHAKE_MED = 44762,
        SPELL_SHADOW_BOLT      = 9613,
        SPELL_DIVINE_STORM     = 53385,
        SPELL_CONSECRATION     = 48818,
        SPELL_HOLY_SHOCK       = 25914,
        SPELL_REMORSELESS_WINTER = 68981,
        SPELL_FURY_OF_FROSTMOURNE = 72350,
    };

    enum IntroSounds : uint32
    {
        MUSIC_BATTLE         = 6077,   // Moment - Battle01
        MUSIC_LICH_KING      = 12765,  // the Lich King's theme
        SOUND_FROST_IMPACT   = 17454,
        SOUND_SHADOW_IMPACT  = 17447,
        SOUND_FROST_WYRM     = 14822,
    };

    // The game's own lines, with their sound (Icecrown Citadel's, and Ebon Hold's).
    struct Voice
    {
        char const* text;
        uint32 sound;
    };
    Voice const TIRION_MUST_FALL{ "The Lich King must fall!!", 17389 };
    Voice const TIRION_NO_MORE{ "No more, Arthas! No more lives will be consumed by your hatred!", 17393 };
    Voice const TIRION_ARTHAS{ "ARTHAS! I swore that I would see you dead and the Scourge dismantled! I'm going to finish what I started at Light's Hope!", 16656 };
    Voice const LK_ARISE{ "Arise minions. Do not let them pass.", 17216 };
    Voice const LK_RAVAGE{ "Ravage the living, minions!", 14788 };
    Voice const LK_VALKYR{ "Val'kyr, your master calls!", 17373 };
    Voice const LK_HOPE{ "Hope wanes...", 17363 };
    Voice const LK_HUNGERS{ "Frostmourne hungers...", 17366 };

    struct Battle
    {
        ObjectGuid player;
        std::vector<ObjectGuid> people;   // everyone summoned, the unseen ones too
        std::vector<ObjectGuid> scourge;
        ObjectGuid tirion, lichKing, voice, moratari;
        bool frozen = false, over = false;
    };

    std::mutex battlesLock;
    std::unordered_map<ObjectGuid, std::shared_ptr<Battle>> battles;
    std::atomic<uint32> running{ 0 };

    std::shared_ptr<Battle> BattleOf(Player const* player)
    {
        if (!running.load())
            return nullptr;
        std::lock_guard<std::mutex> lock(battlesLock);
        auto it = battles.find(player->GetGUID());
        return it == battles.end() ? nullptr : it->second;
    }

    bool Enabled()
    {
        return sConfigMgr->GetOption<bool>("WrathRemix.Intro", true, false);
    }

    bool AtTheFront(Player const* player)
    {
        return player->GetMapId() == MAP_NORTHREND && std::hypot(player->GetPositionX() - FRONT_X, player->GetPositionY() - FRONT_Y) < FRONT_REACH;
    }

    // A place on the Front: yards forward (towards the Citadel) and to the left of its middle.
    Position Spot(Player* player, float forward, float left, float facing = 0.0f)
    {
        float x = FRONT_X + forward * std::cos(FRONT_O) - left * std::sin(FRONT_O);
        float y = FRONT_Y + forward * std::sin(FRONT_O) + left * std::cos(FRONT_O);
        float z = player->GetMap()->GetHeight(player->GetPhaseMask(), x, y, FRONT_Z + 8.0f, true, 30.0f);
        if (z < FRONT_Z - 20.0f)
            z = FRONT_Z;
        return Position(x, y, z + 0.1f, Position::NormalizeOrientation(FRONT_O + facing));
    }

    Creature* Who(Player* player, ObjectGuid guid)
    {
        return guid ? ObjectAccessor::GetCreature(*player, guid) : nullptr;
    }

    // Someone for this Timerunner's battle, on their side or the Scourge's.
    Creature* Bring(Player* player, Battle& b, uint32 entry, Position const& at, uint32 health = 0)
    {
        TempSummon* c = player->SummonCreature(entry, at, TEMPSUMMON_TIMED_DESPAWN, 240000, 0, nullptr, true);
        if (!c)
            return nullptr;
        bool ours = entry >= INTRO_FIRST && entry <= INTRO_LAST;
        bool scourge = ours && (entry <= LICH_KING || entry == PLAGUE);
        c->SetLevel(entry == LICH_KING || entry == TIRION ? 13 : (scourge ? 10 : 12));
        if (health)
        {
            c->SetMaxHealth(health);
            c->SetHealth(health);
        }
        c->SetReactState(!ours || (entry >= FROST_WYRM && entry <= LICH_KING) ? REACT_PASSIVE : REACT_AGGRESSIVE);
        b.people.push_back(c->GetGUID());
        if (scourge && entry <= DEATHBRINGER)
            b.scourge.push_back(c->GetGUID());
        return c;
    }

    // An unseen one, for an effect at a place: the Light's (hurting only the Scourge) or the
    // Scourge's.
    Creature* Effect(Player* player, Battle& b, bool scourge, Position const& at, uint32 lasts = 12000)
    {
        TempSummon* c = player->SummonCreature(scourge ? PLAGUE : LIGHT, at, TEMPSUMMON_TIMED_DESPAWN, lasts, 0, nullptr, true);
        if (c)
            b.people.push_back(c->GetGUID());
        return c;
    }

    void Say(Player* player, Creature* who, Voice const& voice, bool yell = true)
    {
        if (!who)
            return;
        if (yell)
            who->Yell(voice.text, LANG_UNIVERSAL, player);
        else
            who->Say(voice.text, LANG_UNIVERSAL, player);
        if (voice.sound)
            who->PlayDirectSound(voice.sound, player);
    }

    void Shake(Player* player, bool hard)
    {
        player->CastSpell(player, hard ? SPELL_CAMERA_SHAKE_MED : SPELL_CAMERA_SHAKE, true);
    }

    uint32 ScourgeStanding(Player* player, Battle& b)
    {
        uint32 n = 0;
        for (ObjectGuid guid : b.scourge)
            if (Creature* c = Who(player, guid); c && c->IsAlive())
                ++n;
        return n;
    }

    // Somewhere out on the field, ahead, not on top of the Timerunner.
    Position Somewhere(Player* player, float nearest = 12.0f, float farthest = 40.0f)
    {
        for (int tries = 0; tries < 8; ++tries)
        {
            Position at = Spot(player, frand(nearest, farthest), frand(-24.0f, 24.0f));
            if (player->GetExactDist2d(at.GetPositionX(), at.GetPositionY()) > 9.0f)
                return at;
        }
        return Spot(player, farthest, 0.0f);
    }

    void RaiseGhoul(Player* player, Battle& b, Position const& at)
    {
        uint32 entry = urand(0, 3) ? GHOUL : SPIKED_GHOUL;
        if (Creature* ghoul = Bring(player, b, entry, at, entry == GHOUL ? 700 : 900))
            ghoul->CastSpell(ghoul, SPELL_GHOUL_RISE, true);
    }

    // One of the field's blows, somewhere: a frost bomb, plague bursting, a pillar of fire, a blast.
    void Blow(Player* player, Battle& b)
    {
        Position at = Somewhere(player, 10.0f, 38.0f);
        bool near = player->GetExactDist2d(at.GetPositionX(), at.GetPositionY()) < 20.0f;
        switch (urand(0, 4))
        {
            case 0:
            case 1:  // a frost bomb: its mark on the ice, then the blast
                if (Creature* mark = Effect(player, b, true, at, 8000))
                {
                    mark->AddAura(SPELL_FROST_BOMB_MARK, mark);
                    ObjectGuid guid = player->GetGUID(), markGuid = mark->GetGUID();
                    player->m_Events.AddEventAtOffset([guid, markGuid, near]()
                    {
                        Player* p = ObjectAccessor::FindPlayer(guid);
                        std::shared_ptr<Battle> battle = p ? BattleOf(p) : nullptr;
                        if (!battle || battle->frozen)
                            return;
                        if (Creature* m = ObjectAccessor::GetCreature(*p, markGuid))
                        {
                            m->RemoveAurasDueToSpell(SPELL_FROST_BOMB_MARK);
                            m->CastSpell(m, SPELL_EXPLOSION, true);
                            m->PlayDirectSound(SOUND_FROST_IMPACT, p);
                        }
                        if (near)
                            Shake(p, false);
                    }, 2500ms);
                }
                break;
            case 2:  // plague, bursting up through the snow
                if (Creature* plague = Effect(player, b, true, at))
                {
                    plague->CastSpell(plague, SPELL_GREEN_EXPLOSION, true);
                    plague->CastSpell(plague, SPELL_DEATH_AND_DECAY, true);
                }
                break;
            case 3:  // the Light's answer: a pillar of fire among the dead
                if (Creature* light = Effect(player, b, false, at, 6000))
                    light->CastSpell(light, SPELL_PILLAR_OF_FIRE, true);
                break;
            default:  // a blast
                if (Creature* blast = Effect(player, b, true, at, 6000))
                {
                    blast->CastSpell(blast, near ? SPELL_EXPLOSION : SPELL_HUGE_EXPLOSION, true);
                    blast->PlayDirectSound(SOUND_SHADOW_IMPACT, player);
                }
                if (near)
                    Shake(player, false);
                break;
        }
    }

    void At(Player* player, std::shared_ptr<Battle> b, uint32 ms, std::function<void(Player*, Battle&)> step)
    {
        ObjectGuid guid = player->GetGUID();
        player->m_Events.AddEventAtOffset([guid, b, step]()
        {
            Player* p = ObjectAccessor::FindPlayer(guid);
            if (!p || !p->IsInWorld() || b->over)
                return;
            step(p, *b);
        }, Milliseconds(ms));
    }

    void Despawn(Player* player, Battle& b)
    {
        for (ObjectGuid guid : b.people)
            if (Creature* c = Who(player, guid))
                c->DespawnOrUnsummon();
        b.people.clear();
        b.scourge.clear();
    }

    void EndBattle(Player* player)
    {
        std::lock_guard<std::mutex> lock(battlesLock);
        if (battles.erase(player->GetGUID()))
            --running;
    }

    // The end of it: out of the phase, home to the Bazaar, the opening seen.
    void Finish(Player* player, Battle& b)
    {
        if (b.over)
            return;
        b.over = true;
        Despawn(player, b);
        player->RemoveAurasDueToSpell(SPELL_AVENGING_WRATH);
        player->RemoveAurasDueToSpell(SPELL_TIME_STOP_VISUAL);
        player->CombatStop(true);
        player->SetPhaseMask(PHASEMASK_NORMAL, true);
        SetIntroSeen(player);
        // (still in the battle as it goes: the hourglass doesn't count the Front as where they came from)
        player->CastSpell(player, SPELL_TELEPORT_VISUAL, true);
        player->TeleportTo(MAP_NORTHREND, BAZAAR_X, BAZAAR_Y, BAZAAR_Z, BAZAAR_O);
        EndBattle(player);
        // (the hourglass has nowhere to take them back to: the Front was never somewhere they were)
        SetWayBack(player, -1, 0.0f, 0.0f, 0.0f, 0.0f);
        ObjectGuid guid = player->GetGUID();
        player->m_Events.AddEventAtOffset([guid]()
        {
            Player* p = ObjectAccessor::FindPlayer(guid);
            if (!p || !p->IsInWorld())
                return;
            p->SetStandState(UNIT_STAND_STATE_KNEEL);
            if (Creature* moratari = p->FindNearestCreature(NPC_MORATARI, 30.0f))
            {
                moratari->SetFacingToObject(p);
                moratari->Say("Steady. You're safe now. Breathe.", LANG_UNIVERSAL, p);
            }
        }, 2500ms);
    }

    // Time stops: everyone where they stand, the Lich King with Frostmourne raised.
    void Freeze(Player* player, Battle& b)
    {
        b.frozen = true;
        for (ObjectGuid guid : b.people)
            if (Creature* c = Who(player, guid); c && c->IsAlive())
            {
                c->AttackStop();
                c->StopMoving();
                c->GetMotionMaster()->Clear();
                c->SetReactState(REACT_PASSIVE);
                c->SetControlled(true, UNIT_STATE_STUNNED);
                c->AddAura(SPELL_TIME_STOP_VISUAL, c);
            }
        player->CombatStop(true);
        player->RemoveAurasDueToSpell(SPELL_AVENGING_WRATH);
        if (Creature* stop = Effect(player, b, false, player->GetPosition(), 20000))
            stop->CastSpell(stop, SPELL_TIME_STOP, true);
        Shake(player, true);
    }

    // The Lich King comes, and the Scourge kneel to him.
    void TheKingComes(Player* player, Battle& b)
    {
        Position at = Spot(player, 24.0f, 0.0f, float(M_PI));
        if (Creature* blast = Effect(player, b, false, at, 6000))
        {
            blast->CastSpell(blast, SPELL_HUGE_EXPLOSION, true);
            blast->PlayDirectSound(SOUND_SHADOW_IMPACT, player);
        }
        Shake(player, true);
        player->SendPlayMusic(MUSIC_LICH_KING, true);
        if (Creature* king = Bring(player, b, LICH_KING, at, 2000000))
        {
            b.lichKing = king->GetGUID();
            king->CastSpell(king, SPELL_TELEPORT_VISUAL, true);
            king->SetFacingToObject(player);
        }
        for (ObjectGuid guid : b.people)
            if (Creature* c = Who(player, guid); c && c->IsAlive() && c->GetEntry() != LICH_KING &&
                c->GetEntry() != FROST_WYRM && c->GetEntry() != VALKYR && c->GetEntry() != LIGHT && c->GetEntry() != PLAGUE)
            {
                c->AttackStop();
                c->SetReactState(REACT_PASSIVE);
                c->GetMotionMaster()->Clear();
                c->StopMoving();
                if (Creature* king = Who(player, b.lichKing))
                    c->SetFacingToObject(king);
                if (c->GetEntry() <= DEATHBRINGER)
                    c->SetStandState(UNIT_STAND_STATE_KNEEL);
            }
        player->CombatStop(true);
    }

    void Begin(Player* player);

    // Arrived: the battle, already raging.
    void Arrive(Player* player, std::shared_ptr<Battle> battle)
    {
        Battle& b = *battle;
        player->CastSpell(player, SPELL_TELEPORT_VISUAL, true);
        if (Aura* wings = player->AddAura(SPELL_AVENGING_WRATH, player))
        {
            wings->SetMaxDuration(120000);
            wings->SetDuration(120000);
        }
        Shake(player, true);
        player->SendPlayMusic(MUSIC_BATTLE, true);

        // Tirion and the line: the Argent Crusade, and the Timerunner's own side.
        if (Creature* tirion = Bring(player, b, TIRION, Spot(player, 5.0f, 2.5f), 400000))
            b.tirion = tirion->GetGUID();
        float const crusaders[][2] = { { 8.0f, -5.0f }, { 9.0f, 5.5f }, { 7.0f, -10.0f }, { 8.0f, 10.0f }, { 4.0f, -13.0f }, { 4.0f, 13.0f } };
        for (uint32 i = 0; i < std::size(crusaders); ++i)
            Bring(player, b, i % 2 ? CRUSADER : CRUSADER_2, Spot(player, crusaders[i][0], crusaders[i][1]), 60000);
        uint32 soldier = player->GetTeamId() == TEAM_ALLIANCE ? MARINE : REAVER;
        float const soldiers[][2] = { { 2.0f, -7.0f }, { 2.0f, 7.0f }, { -2.0f, -10.0f }, { -2.0f, 10.0f } };
        for (auto const& at : soldiers)
            Bring(player, b, soldier, Spot(player, at[0], at[1]), 60000);

        // The Scourge, already at them.
        float const ghouls[][2] = { { 12.0f, -6.0f }, { 13.0f, -1.0f }, { 12.0f, 4.0f }, { 14.0f, 8.0f }, { 15.0f, -10.0f },
                                    { 16.0f, 2.0f }, { 18.0f, -4.0f }, { 17.0f, 11.0f } };
        for (uint32 i = 0; i < std::size(ghouls); ++i)
            Bring(player, b, i % 3 ? GHOUL : SPIKED_GHOUL, Spot(player, ghouls[i][0], ghouls[i][1], float(M_PI)), i % 3 ? 700 : 900);
        if (Creature* fiend = Bring(player, b, CRYPT_FIEND, Spot(player, 20.0f, 7.0f, float(M_PI)), 1600))
            fiend->CastSpell(fiend, SPELL_EMERGE, true);

        At(player, battle, 1500, [](Player* p, Battle& b)
        {
            Creature* tirion = Who(p, b.tirion);
            Say(p, tirion, TIRION_MUST_FALL);
            if (tirion)
            {
                tirion->CastSpell(tirion, SPELL_CONSECRATION, true);
                tirion->CastSpell(p, SPELL_HOLY_SHOCK, true);
            }
        });
        // His voice, from the Citadel: the dead rise.
        At(player, battle, 4000, [](Player* p, Battle& b)
        {
            if (Creature* voice = Bring(p, b, LICH_KING_VOICE, Spot(p, 60.0f, 0.0f)))
            {
                b.voice = voice->GetGUID();
                Say(p, voice, LK_ARISE);
            }
            for (int i = 0; i < 6; ++i)
                RaiseGhoul(p, b, Somewhere(p, 14.0f, 30.0f));
            Shake(p, false);
        });
        // The field's blows, until the king comes; and the Scourge kept coming.
        for (uint32 t = 3000; t < 43000; t += 2200)
            At(player, battle, t, [](Player* p, Battle& b) { Blow(p, b); });
        for (uint32 t = 7000; t < 42000; t += 3000)
            At(player, battle, t, [](Player* p, Battle& b)
            {
                for (uint32 n = ScourgeStanding(p, b); n < 11; ++n)
                    RaiseGhoul(p, b, Somewhere(p, 16.0f, 34.0f));
            });
        // Abominations and a deathbringer.
        At(player, battle, 13000, [](Player* p, Battle& b)
        {
            Say(p, Who(p, b.voice), LK_RAVAGE);
            for (float left : { -8.0f, 9.0f })
                if (Creature* abom = Bring(p, b, ABOMINATION, Spot(p, 36.0f, left, float(M_PI)), 4500))
                    abom->GetMotionMaster()->MovePoint(1, Spot(p, 12.0f, left * 0.5f));
            for (float left : { -14.0f, 15.0f })
                Bring(p, b, DEATHBRINGER, Spot(p, 30.0f, left, float(M_PI)), 2500);
        });
        At(player, battle, 22000, [](Player* p, Battle& b)
        {
            Creature* tirion = Who(p, b.tirion);
            Say(p, tirion, TIRION_NO_MORE);
            if (tirion)
                tirion->CastSpell(tirion, SPELL_DIVINE_STORM, true);
        });
        // A frost wyrm, low over the line.
        At(player, battle, 27000, [](Player* p, Battle& b)
        {
            Position from = Spot(p, 80.0f, 25.0f, float(M_PI));
            from.m_positionZ += 30.0f;
            Creature* wyrm = Bring(p, b, FROST_WYRM, from, 1000000);
            if (!wyrm)
                return;
            wyrm->SetCanFly(true);
            wyrm->SetDisableGravity(true);
            wyrm->SetSpeed(MOVE_FLIGHT, 3.0f);
            wyrm->SetSpeed(MOVE_RUN, 3.0f);
            Position to = Spot(p, -70.0f, -20.0f);
            wyrm->GetMotionMaster()->MovePoint(1, to.GetPositionX(), to.GetPositionY(), to.GetPositionZ() + 22.0f,
                FORCED_MOVEMENT_NONE, 0.0f, 0.0f, false);
            wyrm->PlayDirectSound(SOUND_FROST_WYRM, p);
            wyrm->DespawnOrUnsummon(10s);
            ObjectGuid guid = p->GetGUID();
            for (uint32 i = 0; i < 4; ++i)
                p->m_Events.AddEventAtOffset([guid, i]()
                {
                    Player* pl = ObjectAccessor::FindPlayer(guid);
                    std::shared_ptr<Battle> battle = pl ? BattleOf(pl) : nullptr;
                    if (!battle || battle->frozen)
                        return;
                    // the breath, along the line
                    Position at = Spot(pl, 16.0f - 7.0f * float(i), i % 2 ? 9.0f : -9.0f);
                    if (Creature* frost = Effect(pl, *battle, true, at, 5000))
                    {
                        frost->CastSpell(frost, SPELL_EXPLOSION, true);
                        frost->PlayDirectSound(SOUND_FROST_IMPACT, pl);
                    }
                    Shake(pl, i == 2);
                }, Milliseconds(1400 + 600 * i));
        });
        // The val'kyr, at his call.
        At(player, battle, 34000, [](Player* p, Battle& b)
        {
            Say(p, Who(p, b.voice), LK_VALKYR);
            for (float left : { -12.0f, 12.0f })
            {
                Position at = Spot(p, 40.0f, left, float(M_PI));
                at.m_positionZ += 14.0f;
                if (Creature* valkyr = Bring(p, b, VALKYR, at, 100000))
                {
                    valkyr->SetCanFly(true);
                    valkyr->SetDisableGravity(true);
                    Position over = Spot(p, 18.0f, left * 0.6f);
                    valkyr->GetMotionMaster()->MovePoint(1, over.GetPositionX(), over.GetPositionY(), over.GetPositionZ() + 9.0f,
                        FORCED_MOVEMENT_NONE, 0.0f, 0.0f, false);
                }
            }
        });
        // The king.
        At(player, battle, 43000, [](Player* p, Battle& b) { TheKingComes(p, b); });
        At(player, battle, 45500, [](Player* p, Battle& b) { Say(p, Who(p, b.lichKing), LK_HOPE); });
        At(player, battle, 48000, [](Player* p, Battle& b)
        {
            Creature* tirion = Who(p, b.tirion);
            Say(p, tirion, TIRION_ARTHAS);
            if (tirion)
            {
                tirion->SetStandState(UNIT_STAND_STATE_STAND);
                tirion->GetMotionMaster()->MovePoint(2, Spot(p, 13.0f, 1.0f));
            }
        });
        // The winter takes the paladin to his knee.
        At(player, battle, 57500, [](Player* p, Battle& b)
        {
            if (Creature* king = Who(p, b.lichKing))
                king->CastSpell(king, SPELL_REMORSELESS_WINTER, true);
            Shake(p, true);
            if (Creature* tirion = Who(p, b.tirion))
            {
                tirion->StopMoving();
                tirion->SetStandState(UNIT_STAND_STATE_KNEEL);
            }
        });
        // And he turns to the Timerunner.
        At(player, battle, 60500, [](Player* p, Battle& b)
        {
            if (Creature* king = Who(p, b.lichKing))
            {
                king->SetFacingToObject(p);
                king->Say("You... You are not of this hour.", LANG_UNIVERSAL, p);
            }
        });
        At(player, battle, 65000, [](Player* p, Battle& b)
        {
            if (Creature* king = Who(p, b.lichKing))
            {
                Say(p, king, LK_HUNGERS);
                king->CastSpell(king, SPELL_FURY_OF_FROSTMOURNE, false);
            }
        });
        // Time stops.
        At(player, battle, 66600, [](Player* p, Battle& b) { Freeze(p, b); });
        At(player, battle, 68500, [](Player* p, Battle& b)
        {
            float o = p->GetOrientation() + 1.0f;
            Position at(p->GetPositionX() + 4.0f * std::cos(o), p->GetPositionY() + 4.0f * std::sin(o), p->GetPositionZ(), 0.0f);
            TempSummon* rift = p->SummonCreature(NPC_TIME_RIFT, at, TEMPSUMMON_TIMED_DESPAWN, 6000, 0, nullptr, true);
            if (rift)
                b.people.push_back(rift->GetGUID());
            if (Creature* moratari = Bring(p, b, NPC_MORATARI_SCENE, at))
            {
                b.moratari = moratari->GetGUID();
                moratari->SetFaction(35);
                moratari->CastSpell(moratari, SPELL_TELEPORT_VISUAL, true);
                Position close(p->GetPositionX() + 2.0f * std::cos(o), p->GetPositionY() + 2.0f * std::sin(o), p->GetPositionZ(), 0.0f);
                moratari->GetMotionMaster()->MovePoint(1, close);
            }
        });
        At(player, battle, 71000, [](Player* p, Battle& b)
        {
            if (Creature* moratari = Who(p, b.moratari))
            {
                moratari->SetFacingToObject(p);
                moratari->Say("Found you. Stay still and stay close: time has stopped around us, and while it holds, nothing here can touch you.", LANG_UNIVERSAL, p);
            }
        });
        At(player, battle, 77500, [](Player* p, Battle& b)
        {
            if (Creature* eternus = Bring(p, b, ETERNUS_VOICE, p->GetPosition()))
                eternus->Say("Look upon it, champion. This is how the war ends: the snow, the Citadel, his blade. Every time. Remember this hour when I ask for your help.", LANG_UNIVERSAL, p);
        });
        At(player, battle, 88000, [](Player* p, Battle& b)
        {
            if (Creature* moratari = Who(p, b.moratari))
            {
                moratari->Say("Enough, Eternus. They have seen it. Take my hand, Timerunner: this hour is about to start again, and we will not be in it.", LANG_UNIVERSAL, p);
                moratari->CastSpell(p, SPELL_TIME_RIFT, true);
            }
        });
        At(player, battle, 95500, [](Player* p, Battle& b) { Finish(p, b); });
    }

    bool Wanted(Player* player)
    {
        if (!Active() || !Enabled() || IsBot(player) || player->getClass() == CLASS_DEATH_KNIGHT)
            return false;
        if (Get(player).intro || player->GetQuestStatus(QUEST_OUT_OF_TIME) != QUEST_STATUS_NONE ||
            player->GetQuestRewardStatus(QUEST_OUT_OF_TIME))
            return false;
        return AtTheBazaar(player) || AtTheFront(player);
    }

    void Begin(Player* player)
    {
        auto battle = std::make_shared<Battle>();
        battle->player = player->GetGUID();
        {
            std::lock_guard<std::mutex> lock(battlesLock);
            if (!battles.emplace(player->GetGUID(), battle).second)
                return;
            ++running;
        }
        player->SetPhaseMask(PHASE_INTRO, true);
        player->SetStandState(UNIT_STAND_STATE_STAND);
        float x = FRONT_X - 2.0f * std::cos(FRONT_O), y = FRONT_Y - 2.0f * std::sin(FRONT_O);
        player->TeleportTo(MAP_NORTHREND, x, y, FRONT_Z + 1.0f, FRONT_O);
        // (a moment for the Front to be there around them)
        At(player, battle, 1500, [battle](Player* p, Battle&) { Arrive(p, battle); });
    }
}

bool WrathRemix::InIntro(Player* player)
{
    return player && BattleOf(player) != nullptr;
}

void WrathRemix::PlayIntro(Player* player)
{
    if (InIntro(player))
        return;
    Begin(player);
}

void WrathRemix::SkipIntro(Player* player)
{
    if (std::shared_ptr<Battle> battle = BattleOf(player))
        Finish(player, *battle);
    else if (Active())
        SetIntroSeen(player);
}

// Everyone in it: they fight the other side, and the Timerunner's blows land as a hero's.
class npc_remix_intro : public CreatureScript
{
public:
    npc_remix_intro() : CreatureScript("npc_remix_intro") { }

    struct npc_remix_introAI : public ScriptedAI
    {
        npc_remix_introAI(Creature* creature) : ScriptedAI(creature) { }

        uint32 lookIn = 300;
        uint32 specialIn = urand(3000, 6000);

        // (the wyrm, the val'kyr, the king and the unseen ones are the battle's to move)
        bool Scripted() const
        {
            uint32 entry = me->GetEntry();
            return entry == FROST_WYRM || entry == VALKYR || entry == LICH_KING || entry >= LIGHT;
        }

        void Reset() override { }

        void MoveInLineOfSight(Unit* who) override
        {
            if (!Scripted() && me->HasReactState(REACT_AGGRESSIVE))
                ScriptedAI::MoveInLineOfSight(who);
        }

        void AttackStart(Unit* who) override
        {
            if (!Scripted() && me->HasReactState(REACT_AGGRESSIVE))
                ScriptedAI::AttackStart(who);
        }

        void JustDied(Unit* /*killer*/) override
        {
            if (me->GetEntry() == ABOMINATION)
                me->CastSpell(me, SPELL_GREEN_EXPLOSION, true);
            me->DespawnOrUnsummon(5s);
        }

        void UpdateAI(uint32 diff) override
        {
            if (Scripted() || me->HasUnitState(UNIT_STATE_STUNNED) || !me->HasReactState(REACT_AGGRESSIVE))
                return;
            if (!UpdateVictim())
            {
                if (lookIn <= diff)
                {
                    lookIn = 700;
                    if (Unit* target = me->SelectNearestTarget(40.0f))
                        AttackStart(target);
                }
                else
                    lookIn -= diff;
                return;
            }
            if (specialIn <= diff)
            {
                specialIn = urand(4000, 7000);
                switch (me->GetEntry())
                {
                    case DEATHBRINGER:
                        DoCastVictim(SPELL_SHADOW_BOLT);
                        break;
                    case TIRION:
                        DoCast(me, urand(0, 1) ? SPELL_DIVINE_STORM : SPELL_CONSECRATION, true);
                        break;
                    default:
                        break;
                }
            }
            DoMeleeAttackIfReady();
        }
    };

    CreatureAI* GetAI(Creature* creature) const override
    {
        return new npc_remix_introAI(creature);
    }
};

// Nothing there hurts the Timerunner; their blows fell the dead in one to three; the two sides wear
// each other down slowly, so the line holds and the field stays full.
class RemixIntroDamage : public UnitScript
{
public:
    RemixIntroDamage() : UnitScript("RemixIntroDamage", true, { UNITHOOK_ON_DAMAGE }) { }

    static bool OfTheBattle(Unit const* unit)
    {
        return unit && unit->IsCreature() && unit->GetEntry() >= INTRO_FIRST && unit->GetEntry() <= INTRO_LAST;
    }

    void OnDamage(Unit* attacker, Unit* victim, uint32& damage) override
    {
        if (!running.load() || !damage || !attacker || !victim)
            return;
        if (Player* hero = victim->ToPlayer(); hero && InIntro(hero))
        {
            damage = 0;
            return;
        }
        if (!OfTheBattle(victim))
            return;
        if (Player* hero = attacker->GetCharmerOrOwnerPlayerOrPlayerItself(); hero && InIntro(hero))
        {
            float share = victim->GetEntry() == ABOMINATION ? 0.2f : 0.36f;
            damage = std::max<uint32>(damage * 12, uint32(victim->GetMaxHealth() * share) + 1);
            return;
        }
        if (OfTheBattle(attacker))
        {
            bool allyHurt = victim->GetEntry() >= TIRION && victim->GetEntry() <= REAVER;
            float cap = allyHurt ? 0.01f : 0.05f;
            damage = std::min<uint32>(damage, std::max<uint32>(1, uint32(victim->GetMaxHealth() * cap)));
        }
    }
};

class RemixIntroPlayer : public PlayerScript
{
public:
    RemixIntroPlayer() : PlayerScript("RemixIntroPlayer", { PLAYERHOOK_ON_LOGIN, PLAYERHOOK_ON_LOGOUT }) { }

    void OnPlayerLogin(Player* player) override
    {
        if (!Active() || IsBot(player))
            return;
        ObjectGuid guid = player->GetGUID();
        // (after the realm's own first moments: the kit on, the abilities learned)
        player->m_Events.AddEventAtOffset([guid]()
        {
            if (Player* p = ObjectAccessor::FindPlayer(guid); p && p->IsInWorld() && Wanted(p))
                Begin(p);
        }, 3s);
    }

    // Cut short: the battle goes with them, and they are kept at the Bazaar, to see it again.
    void OnPlayerLogout(Player* player) override
    {
        std::shared_ptr<Battle> battle = BattleOf(player);
        if (!battle)
            return;
        battle->over = true;
        Despawn(player, *battle);
        player->RemoveAurasDueToSpell(SPELL_AVENGING_WRATH);
        player->SetPhaseMask(PHASEMASK_NORMAL, false);
        player->Relocate(BAZAAR_X, BAZAAR_Y, BAZAAR_Z, BAZAAR_O);
        EndBattle(player);
    }
};

void AddRemixIntroScripts()
{
    new npc_remix_intro();
    new RemixIntroDamage();
    new RemixIntroPlayer();
}
