// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * A home in Brill (mod-brill-home), ChromieCraft's realm alone (ctl.sh: BrillHome.Enable, and the SQL in
 * ChromieCraft's databases only).
 *
 * The house by Brill's north road (data/sql/chromiecraft/world, made by tools/build_sql.py) is everyone's
 * to see; what's in it is the player's own, by phases: near the house, a player is in phase 1 and the
 * bits that are theirs (Cedric Harrow until his quest is turned in, the bed from Jamie Nore's bed quest
 * on, each piece of furniture once it's bought). Away from it, phase 1 alone again. A player under a
 * phasing spell of the game's own is left as the spell has them.
 *
 * Jamie Nore (Brill's town hall) gives the quests, and once the deed is signed, the house's upgrades:
 * furniture for gold, carried over at once. The bed: lying down to rest, or going to sleep (a yawn, the
 * screen fading to black through the app's add-on, then asleep in it): a quarter of a level of rested
 * experience at most once every six hours, and the bed quest's credit.
 */

#include "Chat.h"
#include "ChatCommand.h"
#include "Config.h"
#include "Creature.h"
#include "DatabaseEnv.h"
#include "GameObject.h"
#include "GameTime.h"
#include "BrillHomePatch.h"
#include "Log.h"
#include "MotionMaster.h"
#include "ObjectAccessor.h"
#include "Pet.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "ScriptedGossip.h"
#include "Trainer.h"
#include "StringFormat.h"
#include "World.h"
#include "WorldPacket.h"
#include "WorldSession.h"
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <unordered_map>

namespace fs = std::filesystem;

namespace
{
    // (tools/build_sql.py)
    constexpr float HOUSE_X = 2280.0f, HOUSE_Y = 485.0f;
    constexpr uint32 HOUSE_MAP = 0;
    constexpr float NEAR_HOUSE = 150.0f;

    constexpr uint32 GO_BED = 970101, NPC_BED_CREDIT = 970111;
    constexpr uint32 NPC_DOG = 970112, NPC_FETCH_CREDIT = 970113, NPC_EARS_CREDIT = 970114, NPC_DOG_PET = 970115;
    constexpr uint32 QUEST_HOUSE = 970120, QUEST_WELCOME = 970121, QUEST_BED = 970122, QUEST_DOG = 970123;
    constexpr uint32 TEXT_JAMIE = 970140, TEXT_UPGRADES = 970141, TEXT_BED = 970142, TEXT_DOG = 970144, TEXT_DOG_WAITING = 970145;
    // Out the door, where the stick lands (the house's own (-1.9, -12): tools/build_sql.py).
    constexpr float STICK_X = 2268.2f, STICK_Y = 482.0f, STICK_Z = 33.8f;

    constexpr uint32 PHASE_CEDRIC = 0x100, PHASE_BED = 0x200, PHASE_DOG = 0x400;
    // A character's state: they've slept in the bed (the mastiff came); the mastiff is theirs (its quest
    // done); it's their pet (there was room for it).
    constexpr uint32 FLAG_SLEPT = 0x1, FLAG_DOG_ADOPTED = 0x2, FLAG_DOG_PET = 0x4;
    // The hunter's pet spells a dog's keeper learns with it (Call, Dismiss, Revive, Feed Pet), and Mend
    // Pet's ranks by the level they're learnt at.
    constexpr uint32 PET_SPELLS[] = { 883, 2641, 982, 6991 };
    struct Rank { uint8 level; uint32 spell; };
    constexpr Rank MEND_PET[] = { { 12, 136 }, { 20, 3111 }, { 28, 3661 }, { 36, 3662 }, { 44, 13542 }, { 52, 13543 },
                                  { 60, 13544 }, { 68, 27046 }, { 74, 48989 }, { 80, 48990 } };
    constexpr uint32 PHASES_OURS = 0xFF00;

    // The furniture Jamie has carried over: its phase bit, what she offers, its price in copper.
    struct Upgrade { uint32 phase; char const* offer; char const* done; uint32 price; };
    constexpr Upgrade UPGRADES[] = {
        { 0x0800, "A kitchen table and two chairs.", "A table and chairs, then. Somewhere to eat that isn't your lap.", 20000 },
        { 0x1000, "A wardrobe.", "A wardrobe. Your spare boots will thank you.", 15000 },
        { 0x2000, "A bookshelf.", "A bookshelf. I'll see if the Apothecaries have anything they're not using.", 10000 },
        { 0x4000, "A bearskin rug.", "A bearskin rug. The floors in those old farmhouses are cold as a crypt.", 5000 },
        { 0x8000, "A pot-bellied stove.", "A stove! Now it'll feel like someone lives there.", 30000 },
    };

    constexpr uint32 SENDER_UPGRADES = 1, SENDER_BUY = 2, SENDER_REST = 3, SENDER_SLEEP = 4;
    constexpr uint32 SENDER_FETCH = 5, SENDER_EARS = 6, SENDER_BRING = 7;
    // (a sender none of the game's own menus use: the option this module adds to the hunter trainers')
    constexpr uint32 SENDER_TAME = 970190;
    constexpr uint32 SPELL_TAME_BEAST = 1515, SPELL_BEAST_LORE = 1462;
    constexpr uint8 TAME_LEVEL = 10;   // a hunter's, for Tame Beast

    // The bed (the game's duskwoodbed: 4.8 yards along its +x, its headboard there, the mattress 0.86 up).
    constexpr float MATTRESS_HEIGHT = 0.86f;
    constexpr float MATTRESS_ALONG = -0.2f;   // its middle, a little toward the foot from the bed's middle
    constexpr uint32 SLEEP_FADE_MS = 5000;
    constexpr uint32 SLEEP_DARK_MS = 2200;   // when the screen is black: into the bed then
    constexpr uint32 RESTED_EVERY = 6 * 3600;  // seconds between sleeps that give rested experience
    constexpr uint32 TEXT_EMOTE_YAWN_ID = 106;

    bool enabled = true;

    struct Home { uint32 upgrades = 0; uint32 lastRested = 0; uint32 flags = 0; };
    std::unordered_map<uint32, Home> homes;  // by the character's guid (low)
    std::unordered_map<uint32, uint32> sinceCheck;

    Home& HomeOf(Player* player)
    {
        uint32 guid = player->GetGUID().GetCounter();
        auto it = homes.find(guid);
        if (it != homes.end())
            return it->second;
        Home home;
        if (QueryResult result = CharacterDatabase.Query("SELECT `upgrades`, `last_rested`, `flags` FROM `brill_home` WHERE `guid` = {}", guid))
        {
            Field* fields = result->Fetch();
            home.upgrades = fields[0].Get<uint32>();
            home.lastRested = fields[1].Get<uint32>();
            home.flags = fields[2].Get<uint32>();
        }
        return homes[guid] = home;
    }

    void Save(Player* player)
    {
        Home const& home = HomeOf(player);
        CharacterDatabase.Execute("REPLACE INTO `brill_home` (`guid`, `upgrades`, `last_rested`, `flags`) VALUES ({}, {}, {}, {})",
            player->GetGUID().GetCounter(), home.upgrades, home.lastRested, home.flags);
    }

    bool OwnsHouse(Player* player) { return player->GetQuestRewardStatus(QUEST_WELCOME); }

    bool NearHouse(Player* player)
    {
        return player->GetMapId() == HOUSE_MAP && std::hypot(player->GetPositionX() - HOUSE_X, player->GetPositionY() - HOUSE_Y) < NEAR_HOUSE;
    }

    // The phases that are the player's near the house.
    uint32 HousePhases(Player* player)
    {
        uint32 mask = PHASEMASK_NORMAL;
        if (!player->GetQuestRewardStatus(QUEST_HOUSE))
            mask |= PHASE_CEDRIC;
        if (player->GetQuestStatus(QUEST_BED) != QUEST_STATUS_NONE)
            mask |= PHASE_BED;
        Home& home = HomeOf(player);
        if (OwnsHouse(player))
            mask |= home.upgrades;
        // the mastiff: from the first night in the bed until it's their pet
        if ((home.flags & FLAG_SLEPT) && !(home.flags & FLAG_DOG_PET))
            mask |= PHASE_DOG;
        return mask;
    }

    void UpdatePhases(Player* player)
    {
        if (!enabled || player->HasAuraType(SPELL_AURA_PHASE))
            return;
        uint32 now = player->GetPhaseMask();
        // (only a phase of the house's own, or the world's: never one something else put them in)
        if ((now & ~(PHASEMASK_NORMAL | PHASES_OURS)) != 0)
            return;
        uint32 wanted = NearHouse(player) ? HousePhases(player) : PHASEMASK_NORMAL;
        if (wanted != now)
            player->SetPhaseMask(wanted, true);
    }

    void Yawn(Player* player)
    {
        WorldPacket data(SMSG_TEXT_EMOTE, 8 + 4 + 4 + 4 + 1);
        data << player->GetGUID();
        data << uint32(TEXT_EMOTE_YAWN_ID);
        data << uint32(0xFFFFFFFF);
        data << uint32(0);
        data << uint8(0);
        player->SendMessageToSet(&data, true);
    }

    // The app's add-on darkens the screen (its "fade", through Wrath Remix's add-on prefix, which any
    // realm's client reads: client/TouchAddon.kt).
    void FadeToBlack(Player* player, uint32 milliseconds)
    {
        WorldPacket data;
        ChatHandler::BuildChatPacket(data, CHAT_MSG_WHISPER, LANG_ADDON, player->GetGUID(), player->GetGUID(),
            Acore::StringFormat("WowRemix\tfade {} ", milliseconds), 0);
        player->SendDirectMessage(&data);
    }

    // Onto the bed, lying along it, the head at its headboard (facing its foot), then asleep.
    void LieDown(Player* player, GameObject* bed)
    {
        float o = bed->GetOrientation();
        float x = bed->GetPositionX() + MATTRESS_ALONG * std::cos(o);
        float y = bed->GetPositionY() + MATTRESS_ALONG * std::sin(o);
        float z = bed->GetPositionZ() + MATTRESS_HEIGHT;
        float facing = Position::NormalizeOrientation(o + float(M_PI));
        ObjectGuid guid = player->GetGUID();
        player->NearTeleportTo(x, y, z, facing);
        player->m_Events.AddEventAtOffset([guid]()
        {
            if (Player* sleeper = ObjectAccessor::FindPlayer(guid))
                sleeper->SetStandState(UNIT_STAND_STATE_SLEEP);
        }, 400ms);
    }

    void Sleep(Player* player, GameObject* bed)
    {
        Yawn(player);
        FadeToBlack(player, SLEEP_FADE_MS);
        ObjectGuid guid = player->GetGUID();
        ObjectGuid bedGuid = bed->GetGUID();
        player->m_Events.AddEventAtOffset([guid, bedGuid]()
        {
            Player* sleeper = ObjectAccessor::FindPlayer(guid);
            if (!sleeper || !sleeper->IsAlive() || !sleeper->IsInWorld())
                return;
            GameObject* bed = sleeper->GetMap()->GetGameObject(bedGuid);
            if (!bed || !sleeper->IsWithinDistInMap(bed, 10.0f))
                return;
            LieDown(sleeper, bed);
            sleeper->KilledMonsterCredit(NPC_BED_CREDIT);
            Home& home = HomeOf(sleeper);
            if (!(home.flags & FLAG_SLEPT))
            {
                // (the first night: they wake to find the mastiff there)
                home.flags |= FLAG_SLEPT;
                Save(sleeper);
            }
            uint32 now = uint32(GameTime::GetGameTime().count());
            if (sleeper->GetLevel() < sWorld->getIntConfig(CONFIG_MAX_PLAYER_LEVEL) && now - home.lastRested >= RESTED_EVERY)
            {
                home.lastRested = now;
                Save(sleeper);
                sleeper->SetRestBonus(sleeper->GetRestBonus() + sleeper->GetUInt32Value(PLAYER_NEXT_LEVEL_XP) / 4.0f);
                ChatHandler(sleeper->GetSession()).SendSysMessage("You wake feeling well rested.");
            }
        }, std::chrono::milliseconds(SLEEP_DARK_MS));
    }
}

namespace
{
    // The pet spells a keeper of the dog has, for their level (Mend Pet's ranks as a hunter learns them).
    void LearnPetSpells(Player* player)
    {
        for (uint32 spell : PET_SPELLS)
            if (!player->HasSpell(spell))
                player->learnSpell(spell);
        for (Rank const& rank : MEND_PET)
            if (player->GetLevel() >= rank.level && !player->HasSpell(rank.spell))
                player->learnSpell(rank.spell);
    }

    // The mastiff made the player's pet, as a hunter's tamed beast is: at their level, out beside them,
    // kept as their current pet. Not while they have a pet out or kept as current (a hunter's own, a
    // warlock's demon...): the dog waits in the house until there's room.
    bool GiveDog(Player* player)
    {
        Home& home = HomeOf(player);
        if (!(home.flags & FLAG_DOG_ADOPTED) || (home.flags & FLAG_DOG_PET))
            return false;
        PetStable& stable = player->GetOrInitPetStable();
        if (player->GetPetGUID() || stable.CurrentPet || stable.GetUnslottedHunterPet())
            return false;
        // (it's theirs from here: a hunter's pet's every rule asks whether the keeper is one)
        home.flags |= FLAG_DOG_PET;
        Pet* pet = player->CreateTamedPetFrom(NPC_DOG_PET, 0);
        if (!pet)
        {
            home.flags &= ~FLAG_DOG_PET;
            return false;
        }
        Save(player);
        pet->SetUInt32Value(UNIT_FIELD_LEVEL, player->GetLevel() - 1);
        pet->GetMap()->AddToMap(pet->ToCreature(), true);
        pet->SetUInt32Value(UNIT_FIELD_LEVEL, player->GetLevel());
        player->SetMinion(pet, true);
        pet->InitTalentForLevel();
        pet->SavePetToDB(PET_SAVE_AS_CURRENT);
        player->PetSpellInitialize();
        LearnPetSpells(player);
        UpdatePhases(player);
        return true;
    }

    // The mastiff's own doings, seen by the player: shown, never said (it doesn't talk).
    void DogDoes(Creature* dog, Player* player, char const* what)
    {
        dog->TextEmote(what, player);
    }
}

class go_brill_home_bed : public GameObjectScript
{
public:
    go_brill_home_bed() : GameObjectScript("go_brill_home_bed") { }

    bool OnGossipHello(Player* player, GameObject* go) override
    {
        if (!enabled)
            return false;
        ClearGossipMenuFor(player);
        AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Lie down to rest.", SENDER_REST, 0);
        AddGossipItemFor(player, GOSSIP_ICON_CHAT, "Go to sleep.", SENDER_SLEEP, 0);
        SendGossipMenuFor(player, TEXT_BED, go->GetGUID());
        return true;
    }

    bool OnGossipSelect(Player* player, GameObject* go, uint32 sender, uint32 /*action*/) override
    {
        if (!enabled)
            return false;
        CloseGossipMenuFor(player);
        if (sender == SENDER_REST)
            LieDown(player, go);
        else if (sender == SENDER_SLEEP)
            Sleep(player, go);
        return true;
    }
};

class npc_brill_home_jamie : public CreatureScript
{
public:
    npc_brill_home_jamie() : CreatureScript("npc_brill_home_jamie") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!enabled)
            return false;
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        if (OwnsHouse(player))
            AddGossipItemFor(player, GOSSIP_ICON_CHAT, "I'd like to make my house more of a home.", SENDER_UPGRADES, 0);
        SendGossipMenuFor(player, TEXT_JAMIE, creature->GetGUID());
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 action) override
    {
        if (!enabled)
            return false;
        ClearGossipMenuFor(player);
        Home& home = HomeOf(player);
        if (sender == SENDER_UPGRADES)
        {
            for (uint32 i = 0; i < std::size(UPGRADES); ++i)
                if (!(home.upgrades & UPGRADES[i].phase))
                    AddGossipItemFor(player, GOSSIP_ICON_VENDOR, UPGRADES[i].offer, SENDER_BUY, i,
                        "Have it carried over to your house?", UPGRADES[i].price, false);
            SendGossipMenuFor(player, TEXT_UPGRADES, creature->GetGUID());
            return true;
        }
        CloseGossipMenuFor(player);
        if (sender != SENDER_BUY || action >= std::size(UPGRADES) || !OwnsHouse(player) || (home.upgrades & UPGRADES[action].phase))
            return true;
        Upgrade const& upgrade = UPGRADES[action];
        if (!player->HasEnoughMoney(upgrade.price))
        {
            creature->Whisper("You'll need a little more coin than that, I'm afraid.", LANG_UNIVERSAL, player);
            return true;
        }
        player->ModifyMoney(-int32(upgrade.price));
        home.upgrades |= upgrade.phase;
        Save(player);
        creature->Whisper(upgrade.done, LANG_UNIVERSAL, player);
        UpdatePhases(player);
        return true;
    }
};

class npc_brill_home_mastiff : public CreatureScript
{
public:
    npc_brill_home_mastiff() : CreatureScript("npc_brill_home_mastiff") { }

    bool OnGossipHello(Player* player, Creature* creature) override
    {
        if (!enabled)
            return false;
        ClearGossipMenuFor(player);
        if (creature->IsQuestGiver())
            player->PrepareQuestMenu(creature->GetGUID());
        if (player->GetQuestStatus(QUEST_DOG) == QUEST_STATUS_INCOMPLETE)
        {
            if (player->GetReqKillOrCastCurrentCount(QUEST_DOG, NPC_FETCH_CREDIT) < 3)
                AddGossipItemFor(player, GOSSIP_ICON_CHAT, "(Throw the stick.)", SENDER_FETCH, 0);
            AddGossipItemFor(player, GOSSIP_ICON_CHAT, "(Scratch behind its ears.)", SENDER_EARS, 0);
        }
        bool waiting = (HomeOf(player).flags & FLAG_DOG_ADOPTED) && !(HomeOf(player).flags & FLAG_DOG_PET);
        if (waiting)
            AddGossipItemFor(player, GOSSIP_ICON_CHAT, "(Whistle for it to follow you.)", SENDER_BRING, 0);
        SendGossipMenuFor(player, waiting ? TEXT_DOG_WAITING : TEXT_DOG, creature->GetGUID());
        return true;
    }

    bool OnGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 /*action*/) override
    {
        if (!enabled)
            return false;
        CloseGossipMenuFor(player);
        ObjectGuid playerGuid = player->GetGUID();
        if (sender == SENDER_FETCH)
        {
            if (creature->HasUnitState(UNIT_STATE_ROAMING_MOVE) || creature->m_Events.HasEvents())
                return true;
            DogDoes(creature, player, "(The mastiff's ears go up. It watches the stick sail out the door, then bolts after it.)");
            Position home = creature->GetHomePosition();
            creature->GetMotionMaster()->MovePoint(1, STICK_X, STICK_Y, STICK_Z);
            creature->m_Events.AddEventAtOffset([creature, home]()
            {
                creature->GetMotionMaster()->MovePoint(2, home.GetPositionX(), home.GetPositionY(), home.GetPositionZ());
            }, 3500ms);
            creature->m_Events.AddEventAtOffset([creature, home, playerGuid]()
            {
                creature->SetFacingTo(home.GetOrientation());
                Player* thrower = ObjectAccessor::FindPlayer(playerGuid);
                if (!thrower)
                    return;
                uint32 done = thrower->GetReqKillOrCastCurrentCount(QUEST_DOG, NPC_FETCH_CREDIT);
                DogDoes(creature, thrower, done == 0 ? "(The mastiff trots back and drops the stick at your feet, very pleased with itself.)"
                    : done == 1 ? "(Back again, tail going like a flag. The stick is a good deal wetter than before.)"
                    : "(The mastiff brings the stick back, then lies down with it and starts to chew. It seems to think it has won.)");
                thrower->KilledMonsterCredit(NPC_FETCH_CREDIT);
            }, 7000ms);
            return true;
        }
        if (sender == SENDER_EARS)
        {
            DogDoes(creature, player, "(The mastiff leans its whole weight against your leg, eyes half closed, and groans happily.)");
            player->KilledMonsterCredit(NPC_EARS_CREDIT);
            return true;
        }
        if (sender == SENDER_BRING && !GiveDog(player))
            DogDoes(creature, player, "(The mastiff looks at whatever is already following you, and sits back down by the door.)");
        return true;
    }

    // Its quest done: the dog is theirs, their pet now if there's room.
    bool OnQuestReward(Player* player, Creature* creature, Quest const* quest, uint32 /*opt*/) override
    {
        if (!enabled || quest->GetQuestId() != QUEST_DOG)
            return false;
        HomeOf(player).flags |= FLAG_DOG_ADOPTED;
        Save(player);
        if (!GiveDog(player))
            DogDoes(creature, player, "(The mastiff looks at whatever is already following you, and sits back down by the door to wait.)");
        return false;
    }
};

// The hunter trainers teach a dog's keeper who isn't a hunter to tame beasts, as they do hunters: Tame
// Beast and Beast Lore (under General in their spellbook), from a hunter's level for it.
class BrillHomeHunterTrainers : public AllCreatureScript
{
public:
    BrillHomeHunterTrainers() : AllCreatureScript("BrillHomeHunterTrainers") { }

    static bool HunterTrainer(Creature* creature)
    {
        Trainer::Trainer const* trainer = sObjectMgr->GetTrainer(creature->GetEntry());
        return trainer && trainer->GetTrainerType() == Trainer::Type::Class && trainer->GetTrainerRequirement() == CLASS_HUNTER;
    }

    static bool Learner(Player* player)
    {
        return enabled && player->getClass() != CLASS_HUNTER && (HomeOf(player).flags & FLAG_DOG_PET)
            && !(player->HasSpell(SPELL_TAME_BEAST) && player->HasSpell(SPELL_BEAST_LORE));
    }

    // Their own menu, with the module's option at its end.
    bool CanCreatureGossipHello(Player* player, Creature* creature) override
    {
        if (!Learner(player) || !HunterTrainer(creature))
            return false;
        player->PrepareGossipMenu(creature, creature->GetCreatureTemplate()->GossipMenuId, true);
        AddGossipItemFor(player, GOSSIP_ICON_TRAINER, "Teach me to tame beasts, as you would a hunter.", SENDER_TAME, 0);
        player->SendPreparedGossip(creature);
        return true;
    }

    bool CanCreatureGossipSelect(Player* player, Creature* creature, uint32 sender, uint32 /*action*/) override
    {
        if (sender != SENDER_TAME)
            return false;
        CloseGossipMenuFor(player);
        if (!Learner(player) || !HunterTrainer(creature))
            return true;
        if (player->GetLevel() < TAME_LEVEL)
        {
            creature->Whisper("Your dog trusts you, I'll grant that. Come back when you've a few more seasons behind you.", LANG_UNIVERSAL, player);
            return true;
        }
        if (!player->HasSpell(SPELL_TAME_BEAST))
            player->learnSpell(SPELL_TAME_BEAST);
        if (!player->HasSpell(SPELL_BEAST_LORE))
            player->learnSpell(SPELL_BEAST_LORE);
        creature->Whisper("A beast already follows you of its own will: that's the hard part. The rest is patience, a steady hand, and something good to eat in your pack.",
            LANG_UNIVERSAL, player);
        return true;
    }
};

class BrillHomePlayer : public PlayerScript
{
public:
    BrillHomePlayer() : PlayerScript("BrillHomePlayer", { PLAYERHOOK_ON_UPDATE, PLAYERHOOK_ON_LOGOUT, PLAYERHOOK_ON_LOGIN,
        PLAYERHOOK_ON_LEVEL_CHANGED, PLAYERHOOK_ON_PLAYER_IS_CLASS }) { }

    // (their house read before anything asks: the class question below reads it from memory)
    void OnPlayerLogin(Player* player) override
    {
        if (enabled && (HomeOf(player).flags & FLAG_DOG_PET))
            LearnPetSpells(player);
    }

    void OnPlayerLevelChanged(Player* player, uint8 /*oldLevel*/) override
    {
        if (enabled && (HomeOf(player).flags & FLAG_DOG_PET))
            LearnPetSpells(player);
    }

    // A keeper of the dog is a hunter wherever the game asks about pets (their pet's kind, the stable
    // master, Tame Beast, the pet's training and happiness): its every rule for a hunter's pet.
    Optional<bool> OnPlayerIsClass(Player const* player, Classes playerClass, ClassContext context) override
    {
        if (!enabled || playerClass != CLASS_HUNTER || context != CLASS_CONTEXT_PET)
            return std::nullopt;
        auto it = homes.find(player->GetGUID().GetCounter());
        if (it != homes.end() && (it->second.flags & FLAG_DOG_PET))
            return true;
        return std::nullopt;
    }

    // Once a second: in the house's phases near it, out of them away from it.
    void OnPlayerUpdate(Player* player, uint32 diff) override
    {
        if (!enabled)
            return;
        uint32& since = sinceCheck[player->GetGUID().GetCounter()];
        since += diff;
        if (since < 1000)
            return;
        since = 0;
        UpdatePhases(player);
    }

    void OnPlayerLogout(Player* player) override
    {
        homes.erase(player->GetGUID().GetCounter());
        sinceCheck.erase(player->GetGUID().GetCounter());
    }
};

using namespace Acore::ChatCommands;

// Game masters' (CI's realm test): .brillhome state says what the house shows the character (their
// phases, the furniture bought, the rested experience, how they're lying or standing, where they are,
// which of the quests are done); .brillhome sleep goes to sleep in the nearest bed, as its menu does.
class BrillHomeCommands : public CommandScript
{
public:
    BrillHomeCommands() : CommandScript("BrillHomeCommands") { }

    ChatCommandTable GetCommands() const override
    {
        static ChatCommandTable brillHomeTable =
        {
            { "state", HandleState, SEC_GAMEMASTER, Console::No },
            { "sleep", HandleSleep, SEC_GAMEMASTER, Console::No },
        };
        static ChatCommandTable commandTable =
        {
            { "brillhome", brillHomeTable },
        };
        return commandTable;
    }

    static bool HandleState(ChatHandler* handler)
    {
        Player* player = handler->GetSession()->GetPlayer();
        UpdatePhases(player);
        handler->PSendSysMessage("Brill home: phases 0x{:X} upgrades 0x{:X} rested {} stand {} at {:.1f} {:.1f} {:.1f} facing {:.2f} quests {}{}{}",
            player->GetPhaseMask(), HomeOf(player).upgrades, uint32(player->GetRestBonus()), uint32(player->getStandState()),
            player->GetPositionX(), player->GetPositionY(), player->GetPositionZ(), player->GetOrientation(),
            player->GetQuestRewardStatus(QUEST_HOUSE) ? 1 : 0, player->GetQuestRewardStatus(QUEST_WELCOME) ? 1 : 0,
            player->GetQuestRewardStatus(QUEST_BED) ? 1 : 0);
        // and the dog: the house's flags, the pet out (its entry, its kind: 1 a hunter's), whether the game
        // counts them a hunter for pets, and whether they have Call Pet and Mend Pet
        Pet* pet = player->GetPet();
        handler->PSendSysMessage("Brill home: flags 0x{:X} pet {} kind {} hunterpets {} callpet {} mendpet {} tame {}",
            HomeOf(player).flags, pet ? pet->GetEntry() : 0, pet ? uint32(pet->getPetType()) : 9,
            player->IsClass(CLASS_HUNTER, CLASS_CONTEXT_PET) ? 1 : 0, player->HasSpell(883) ? 1 : 0, player->HasSpell(136) ? 1 : 0,
            player->HasSpell(SPELL_TAME_BEAST) ? 1 : 0);
        return true;
    }

    static bool HandleSleep(ChatHandler* handler)
    {
        Player* player = handler->GetSession()->GetPlayer();
        GameObject* bed = player->FindNearestGameObject(GO_BED, 30.0f);
        if (!bed)
        {
            handler->SendSysMessage("Brill home: no bed within 30 yards.");
            return true;
        }
        Sleep(player, bed);
        handler->SendSysMessage("Brill home: sleeping.");
        return true;
    }
};

class BrillHomeWorld : public WorldScript
{
public:
    BrillHomeWorld() : WorldScript("BrillHomeWorld", { WORLDHOOK_ON_AFTER_CONFIG_LOAD, WORLDHOOK_ON_LOAD_CUSTOM_DATABASE_TABLE }) { }

    // (on Wrath Remix's realm the game's patch-W is set aside as the configuration is read, before
    // Remix's own patch is made from the game)
    void OnAfterConfigLoad(bool reload) override
    {
        enabled = sConfigMgr->GetOption<bool>("BrillHome.Enable", true);
        fs::path data = ClientData();
        if (!reload && !enabled && !data.empty())
            BrillHome::SetPatchAside(data, Log);
    }

    // The mastiff in the game: patch-W, after the Worgen's and Goblins' patch-U and the allied races'
    // patch-V are made (as the configuration is read), before the Skyborne's patch-Z.
    void OnLoadCustomDatabaseTable() override
    {
        fs::path data = ClientData();
        if (!enabled || data.empty())
            return;
        fs::path module = fs::path(sConfigMgr->GetOption<std::string>("SourceDirectory", "")) / "modules" / "mod-brill-home" / "data";
        BrillHome::UpdatePatch(module / "client", module / "files", data, Log);
    }

private:
    static void Log(std::string const& line) { LOG_INFO("module", "Brill home: {}", line); }

    static fs::path ClientData()
    {
        char const* client = std::getenv("WOW_CLIENT_DIR");
        if (!client || !*client)
            return {};
        fs::path data = fs::path(client) / "Data";
        std::error_code error;
        return fs::is_directory(data, error) ? data : fs::path();
    }
};

void AddBrillHomeScripts()
{
    new go_brill_home_bed();
    new npc_brill_home_jamie();
    new BrillHomePlayer();
    new BrillHomeWorld();
    new BrillHomeCommands();
    new npc_brill_home_mastiff();
    new BrillHomeHunterTrainers();
}
