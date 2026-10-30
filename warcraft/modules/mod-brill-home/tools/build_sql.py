#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Makes mod-brill-home's world SQL (data/sql/chromiecraft/world/brill_home_world.sql): the house by
Brill's north road, its furniture, Cedric Harrow, Jamie Nore's part and the quests.

The house is the farmhouse Brill's own houses are (Duskwood_Human_Farm.wmo, the game's display 7432),
as a gameobject. Its place and turn were worked out from the server's data (data.zip: the terrain's
heights, the collision models' doorway and the world's other models), see the README:
- (2280, 485), 19 yards east of the road out of Brill to the north, on ground within 2 yards of
  level, 18 yards from the nearest spawn (the seasonal camp south of it) and 24 from the nearest tree;
- turned so its doorway (the model's own side at 257.5 degrees, where its walls open) faces the road;
- its base 31.1 high: the floor (4.1 above the base) 0.2 above the highest ground under it.

Everything inside is placed in the house's own coordinates (yards from its middle, the doorway's side
at -y) and turned with it. Run it after changing the layout; the output is committed.
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "data", "sql", "chromiecraft", "world", "brill_home_world.sql")

MAP, ZONE, AREA = 0, 85, 159
HOUSE_X, HOUSE_Y, HOUSE_BASE = 2280.0, 485.0, 31.1
HOUSE_O = 5.1217  # radians: the doorway (model's 257.5 degrees) toward the road
FLOOR = HOUSE_BASE + 4.1

# Entries (970100 to 970199: nothing else in the game or the app's modules uses them)
GO_HOUSE, GO_BED, GO_TABLE, GO_CHAIR, GO_WARDROBE, GO_BOOKSHELF, GO_RUG, GO_STOVE = range(970100, 970108)
NPC_CEDRIC, NPC_BED_CREDIT = 970110, 970111
JAMIE_NORE = 2310
QUEST_HOUSE, QUEST_WELCOME, QUEST_BED = 970120, 970121, 970122
MENU_JAMIE, MENU_UPGRADES, MENU_BED = 970130, 970131, 970132
TEXT_JAMIE, TEXT_UPGRADES, TEXT_BED, TEXT_CEDRIC = 970140, 970141, 970142, 970143

# Phases (each its own bit; the module puts a player in the ones that are theirs while near the
# house, src/BrillHome.cpp): Cedric until his quest is done, the bed from Jamie's bed quest on, and
# each piece of furniture once it's bought.
PHASE_CEDRIC, PHASE_BED, PHASE_TABLE, PHASE_WARDROBE, PHASE_BOOKSHELF, PHASE_RUG, PHASE_STOVE = (
    0x100, 0x200, 0x800, 0x1000, 0x2000, 0x4000, 0x8000)

# The furniture: (entry, type, display, scale, name, phase, [(x, y, z, turn in degrees)]) in the
# house's coordinates; turn 0 faces the house's +x. Chairs are the game's chair kind (sitting works).
FURNITURE = [
    (GO_BED, 2, 3471, 1.0, "Starter Bed", PHASE_BED, [(2.9, 3.2, 0, 0)]),
    (GO_TABLE, 5, 234, 1.0, "Kitchen Table", PHASE_TABLE, [(-1.8, 0.8, 0, 0)]),
    (GO_CHAIR, 7, 39, 1.0, "Chair", PHASE_TABLE, [(-1.8, -0.5, 0, 90), (-1.8, 2.1, 0, -90)]),
    (GO_WARDROBE, 5, 4, 1.0, "Wardrobe", PHASE_WARDROBE, [(-5.0, -2.0, 0, 0)]),
    (GO_BOOKSHELF, 5, 188, 1.0, "Bookshelf", PHASE_BOOKSHELF, [(0.2, 5.1, 0, -90)]),
    (GO_RUG, 5, 9405, 1.0, "Bearskin Rug", PHASE_RUG, [(0.8, -1.6, 0, 0)]),
    (GO_STOVE, 5, 348, 1.0, "Pot-Bellied Stove", PHASE_STOVE, [(-4.5, 4.3, 0, -45)]),
]
CEDRIC_AT = (0.5, -2.5, 257.5)  # facing the doorway

# The mastiff (there when the player first wakes in the bed; its quest; then the player's pet).
NPC_DOG, NPC_FETCH_CREDIT, NPC_EARS_CREDIT, NPC_DOG_PET = 970112, 970113, 970114, 970115
# A hunter's dog beside their own pet, and a beast master's stabled pet beside them both (its looks
# put on it as it's summoned: src/BrillHome.cpp).
NPC_DOG_COMPANION, NPC_BEAST_COMPANION = 970116, 970117
QUEST_DOG = 970123
TEXT_DOG, TEXT_DOG_WAITING = 970144, 970145
PHASE_DOG = 0x400
DOG_AT = (0.6, 0.6, 30)  # beside the bed, looking toward it
# Its model (tools/mastiff.py, in the game's patch-W) and its four coats: ids clear of the game's and
# of every module's (the starting areas' own mastiff rows, 3190 and 30210 to 30213, go when they're off).
MASTIFF_MODEL = 3970
MASTIFF_COATS = [(39700, "Gray"), (39701, "Black"), (39702, "Red"), (39703, "Yellow")]
MASTIFF_SCALE = 0.7
WOLF_SOUNDS = 43   # 3.3.5's wolf's CreatureSoundData (the mastiff's own sounds are the starting areas')
# CreatureModelData's row for it: the starting areas' (Cataclysm's), with the wolf's sounds.
MASTIFF_MODEL_ROW = [4096, "creature\\mastiff\\mastiff.mdx", 0, 1.0, 1, 7, 18.0, 12.0, 1.0, 0, 0, 0, WOLF_SOUNDS,
                     0.6111111044883728, 2.031277894973755, 1.399109959602356, -1.3119957447052002, -0.42800551652908325,
                     -0.029394155368208885, 1.1700317859649658, 0.4534095823764801, 1.9144073724746704, 1.0, 1.0, 0.0, 0.0, 0.0]
CLIENT_DBC = os.path.join(HERE, "..", "data", "client", "dbc")


def world(lx, ly):
    c, s = math.cos(HOUSE_O), math.sin(HOUSE_O)
    return HOUSE_X + lx * c - ly * s, HOUSE_Y + lx * s + ly * c


def turn(degrees):
    return (HOUSE_O + math.radians(degrees)) % (2 * math.pi)


def q(text):
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"


def go_spawn(guid, entry, x, y, z, o, phase):
    return (f"({guid}, {entry}, {MAP}, {ZONE}, {AREA}, 1, {phase}, {x:.3f}, {y:.3f}, {z:.3f}, {o:.4f}, "
            f"0, 0, {math.sin(o / 2):.6f}, {math.cos(o / 2):.6f}, 0, 255, 1, 'mod-brill-home')")


CEDRIC_OFFER = (
    "You've the look of someone who keeps their word, $n. Brill could use more of those.$B$B"
    "I built this house before the plague, for Marta and our two boys. When I rose again my feet carried "
    "me back here before my mind knew where it was going. I put the roof back on board by board. Thought it "
    "would help me remember who I was.$B$B"
    "It didn't. It only reminds me who isn't here.$B$B"
    "There's a ship leaving the Undercity for Vengeance Landing at the next dark of the moon, and I mean to "
    "be on it. Whatever the Lich King took from me, I'll take some of it back in Northrend.$B$B"
    "Brill took me in when the living would have burned me. I'd like to leave it something better than an "
    "empty house. Let it go to someone the town can count on.$B$B"
    "Jamie Nore at the town hall keeps the deeds. Tell her Cedric Harrow sent you, and that the house is yours.")
CEDRIC_DONE = (
    "Cedric's house? He's giving it to you?$B$B"
    "He stood in that doorway every night for three years, watching the road as if someone was coming "
    "home. I suppose someone finally is.$B$B"
    "Well. The deed's in order. Cedric always did keep his papers tidy, even dead.")
WELCOME_OFFER = (
    "Then let's make it official. Sign here... and here. There.$B$B"
    "Brill isn't much, $n, and Tirisfal isn't kind. But a door that shuts and a roof that keeps the rain out "
    "are worth more than gold out here. Look after it, and it'll look after you.")
WELCOME_DONE = (
    "Welcome home, neighbor.$B$B"
    "I walked past it this morning, mind you. Cedric took nearly everything with him; there isn't so much "
    "as a stool left. We'll fix that.")
BED_OFFER = (
    "We can't have someone living in this town sleeping on the floor now, can we?$B$B"
    "The innkeeper owed me a favor, and the inn had a spare bed taking up room in its cellar. I've had it "
    "carried over to your house. Nothing grand: a wooden frame, a straw mattress, and a quilt that's only "
    "been mended twice.$B$B"
    "Go and try it out. Lie down, get a proper night's sleep, and then come back and tell me I was right.")
BED_PROGRESS = "Well? Have you tried it yet?"
DOG_OFFER = (
    "(A big mastiff is sitting beside your bed, as if it has been waiting a while for you to wake up. A muddy "
    "stick lies between its front paws.)$B$B"
    "(It noses the stick toward you. It looks at the door. It looks back at you.)")
DOG_LOG = "Throw the stick for the mastiff three times, then give it a good scratch behind the ears."
DOG_PROGRESS = "(The mastiff drops the stick at your feet again and waits, ears up.)"
DOG_DONE = (
    "(The mastiff turns three circles on the floor beside your bed and flops down with a long, contented sigh.)$B$B"
    "(When you step toward the door, it gets up and follows.)")
BED_DONE = (
    "You look rested. Don't argue; you do.$B$B"
    "Whenever you'd like to make the place more of a home, come and see me. I know a carpenter in the "
    "Undercity who owes me even more than the innkeeper did.")


def main():
    out = []
    w = out.append
    w("-- mod-brill-home (ChromieCraft's world database alone: ctl.sh realm_sql). Made by tools/build_sql.py;")
    w("-- don't edit by hand. Written to be applied again: its own rows go first.")
    w("")
    w("DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 970100 AND 970109;")
    w("DELETE FROM `gameobject` WHERE `guid` BETWEEN 970100 AND 970149;")
    w("DELETE FROM `creature_template` WHERE `entry` BETWEEN 970110 AND 970119;")
    w("DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 970110 AND 970119;")
    w("DELETE FROM `creature` WHERE `guid` BETWEEN 970100 AND 970149;")
    for t in ("quest_template", "quest_template_addon", "quest_offer_reward", "quest_request_items"):
        w(f"DELETE FROM `{t}` WHERE `ID` BETWEEN 970120 AND 970129;")
    w("DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 970120 AND 970129;")
    w("DELETE FROM `creature_questender` WHERE `quest` BETWEEN 970120 AND 970129;")
    w("DELETE FROM `npc_text` WHERE `ID` BETWEEN 970140 AND 970149;")
    w("")

    w("-- The house (the farmhouse Brill's own are), its furniture (each in its phase).")
    w("INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `Data3`, `ScriptName`) VALUES")
    rows = [f"({GO_HOUSE}, 5, 7432, 'Harrow Farmhouse', 1, 0, 0, 0, '')"]
    for entry, kind, display, scale, name, _, _ in FURNITURE:
        data0, data1 = (1, 1) if kind == 7 else (0, 0)  # a chair: one seat, normal height
        script = "go_brill_home_bed" if entry == GO_BED else ""
        data3 = 0  # (the bed's menu is its script's: src/BrillHome.cpp)
        rows.append(f"({entry}, {kind}, {display}, {q(name)}, {scale}, {data0}, {data1}, {data3}, {q(script)})")
    w(",\n".join(rows) + ";")
    w("")
    w("INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, "
      "`position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES")
    rows = [go_spawn(970100, GO_HOUSE, HOUSE_X, HOUSE_Y, HOUSE_BASE, HOUSE_O, 1)]
    guid = 970101
    for entry, _, _, _, _, phase, places in FURNITURE:
        for lx, ly, lz, deg in places:
            x, y = world(lx, ly)
            rows.append(go_spawn(guid, entry, x, y, FLOOR + lz, turn(deg), phase))
            guid += 1
    w(",\n".join(rows) + ";")
    w("")

    w("-- Cedric Harrow (who leaves the house to the player), and the bed's credit (sleeping in it).")
    w("INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, "
      "`speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `flags_extra`, `ScriptName`) VALUES")
    w(f"({NPC_CEDRIC}, 'Cedric Harrow', NULL, 0, 20, 20, 68, 2, 1, 1.14286, 1, 0, 6, 2, ''),")
    w(f"({NPC_BED_CREDIT}, 'Slept in your new bed', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 130, '');")
    w("INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES")
    w(f"({NPC_CEDRIC}, 0, 3533, 1, 1),")
    w(f"({NPC_BED_CREDIT}, 0, 11686, 1, 1);")
    cx, cy = world(CEDRIC_AT[0], CEDRIC_AT[1])
    w("INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, "
      "`orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES")
    w(f"(970100, {NPC_CEDRIC}, {MAP}, {ZONE}, {AREA}, 1, {PHASE_CEDRIC}, {cx:.3f}, {cy:.3f}, {FLOOR:.3f}, {turn(CEDRIC_AT[2]):.4f}, 120, 0, 0, 'mod-brill-home');")
    w("")

    w("-- Jamie Nore (Brill's town hall) gives the quests and the house's upgrades (src/BrillHome.cpp).")
    w(f"UPDATE `creature_template` SET `npcflag` = `npcflag` | 3, `gossip_menu_id` = {MENU_JAMIE}, `ScriptName` = 'npc_brill_home_jamie' WHERE `entry` = {JAMIE_NORE};")
    w("")

    w("-- Their words.")
    w("INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES")
    texts = [
        (TEXT_JAMIE, "Brill's records are in order, the dead stay in their proper plots, and the living pay their taxes. Mostly.$B$BWhat can I do for you, $n?"),
        (TEXT_UPGRADES, "What would you like for the house? I'll have it carried over today."),
        (TEXT_BED, "A wooden bed with a straw mattress and a quilt that's only been mended twice."),
        (TEXT_CEDRIC, "Mind the third board by the door. I never did get it to sit right."),
    ]
    w(",\n".join(f"({i}, {q(t)}, {q(t)}, 1)" for i, t in texts) + ";")
    w("")

    w("-- The quests: Cedric's house to Jamie; her welcome (done as it's taken); her bed.")
    w("INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, "
      "`RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, "
      "`RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`) VALUES")
    horde = 0  # (any race: Brill is the Forsaken's, where only the Horde gets this far)
    w(",\n".join([
        f"({QUEST_HOUSE}, 2, -1, 5, {AREA}, 8, 2, 0, {horde}, 'The House by the Road', "
        f"{q('Speak with Jamie Nore in the Brill town hall.')}, {q(CEDRIC_OFFER)}, '', {q('Return to Jamie Nore in Brill.')}, 0, 0, '')",
        f"({QUEST_WELCOME}, 2, -1, 5, {AREA}, {8 | 0x10000}, 1, 0, {horde}, 'Welcome Home', "
        f"{q('Sign the deed to your house with Jamie Nore.')}, {q(WELCOME_OFFER)}, '', '', 0, 0, '')",
        f"({QUEST_BED}, 2, -1, 5, {AREA}, 8, 2, 0, {horde}, 'Not on the Floor', "
        f"{q('Sleep in the bed in your new house, then return to Jamie Nore in Brill.')}, {q(BED_OFFER)}, '', "
        f"{q('Return to Jamie Nore in Brill.')}, {NPC_BED_CREDIT}, 1, 'Slept in your new bed')",
    ]) + ";")
    w("INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES")
    w(f"({QUEST_HOUSE}, 0), ({QUEST_WELCOME}, {QUEST_HOUSE}), ({QUEST_BED}, {QUEST_WELCOME});")
    w("INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES")
    w(f"({QUEST_HOUSE}, 1, {q(CEDRIC_DONE)}), ({QUEST_WELCOME}, 2, {q(WELCOME_DONE)}), ({QUEST_BED}, 1, {q(BED_DONE)});")
    w("INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES")
    w(f"({QUEST_BED}, 1, 1, {q(BED_PROGRESS)});")
    w("INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES")
    w(f"({NPC_CEDRIC}, {QUEST_HOUSE}), ({JAMIE_NORE}, {QUEST_WELCOME}), ({JAMIE_NORE}, {QUEST_BED});")
    w("INSERT INTO `creature_questender` (`id`, `quest`) VALUES")
    w(f"({JAMIE_NORE}, {QUEST_HOUSE}), ({JAMIE_NORE}, {QUEST_WELCOME}), ({JAMIE_NORE}, {QUEST_BED});")

    w("")
    w("-- The mastiff: beside the bed once the player has slept in it (its phase), its quest, and the")
    w("-- template its pet is made from; the quest's two credits. Its coats are its model's (patch-W).")
    w("INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, "
      "`speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `type_flags`, `family`, `flags_extra`, `ScriptName`) VALUES")
    w(f"({NPC_DOG}, 'Mastiff', NULL, 0, 1, 1, 35, 3, 1, 1.14286, 1, 0, 1, 0, 1, 2, 'npc_brill_home_mastiff'),")
    w(f"({NPC_DOG_PET}, 'Mastiff', NULL, 0, 1, 1, 35, 0, 1, 1.14286, 1, 0, 1, 1, 1, 0, ''),")
    w(f"({NPC_DOG_COMPANION}, 'Mastiff', NULL, 0, 1, 1, 35, 0, 1, 1.14286, 1, 0, 1, 0, 1, 0, 'npc_brill_home_companion'),")
    w(f"({NPC_BEAST_COMPANION}, 'Stabled Companion', NULL, 0, 1, 1, 35, 0, 1, 1.14286, 1, 0, 1, 0, 1, 0, 'npc_brill_home_companion'),")
    w(f"({NPC_FETCH_CREDIT}, 'Played fetch with the mastiff', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 0, 130, ''),")
    w(f"({NPC_EARS_CREDIT}, 'Scratched the mastiff behind the ears', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 0, 130, '');")
    w("INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES")
    rows = []
    for entry in (NPC_DOG, NPC_DOG_PET, NPC_DOG_COMPANION, NPC_BEAST_COMPANION):
        rows += [f"({entry}, {i}, {display}, 1, 1)" for i, (display, _) in enumerate(MASTIFF_COATS)]
    rows += [f"({NPC_FETCH_CREDIT}, 0, 11686, 1, 1)", f"({NPC_EARS_CREDIT}, 0, 11686, 1, 1)"]
    w(",\n".join(rows) + ";")
    dx, dy = world(DOG_AT[0], DOG_AT[1])
    w("INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, "
      "`orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES")
    w(f"(970101, {NPC_DOG}, {MAP}, {ZONE}, {AREA}, 1, {PHASE_DOG}, {dx:.3f}, {dy:.3f}, {FLOOR:.3f}, {turn(DOG_AT[2]):.4f}, 60, 0, 0, 'mod-brill-home');")
    w("DELETE FROM `creature_model_info` WHERE `DisplayID` BETWEEN 39700 AND 39703;")
    w("INSERT INTO `creature_model_info` (`DisplayID`, `BoundingRadius`, `CombatReach`, `Gender`, `DisplayID_Other_Gender`) VALUES")
    w(",\n".join(f"({display}, 0.7, 1.5, 2, 0)" for display, _ in MASTIFF_COATS) + ";")
    w("-- The server's copy of the game's rows for it (the client's are in patch-W: data/client/dbc).")
    w(f"DELETE FROM `creaturemodeldata_dbc` WHERE `ID` = {MASTIFF_MODEL};")
    w("INSERT INTO `creaturemodeldata_dbc` (`ID`, `Flags`, `ModelName`, `SizeClass`, `ModelScale`, `BloodID`, `FootprintTextureID`, "
      "`FootprintTextureLength`, `FootprintTextureWidth`, `FootprintParticleScale`, `FoleyMaterialID`, `FootstepShakeSize`, "
      "`DeathThudShakeSize`, `SoundID`, `CollisionWidth`, `CollisionHeight`, `MountHeight`, `GeoBoxMinX`, `GeoBoxMinY`, `GeoBoxMinZ`, "
      "`GeoBoxMaxX`, `GeoBoxMaxY`, `GeoBoxMaxZ`, `WorldEffectScale`, `AttachedEffectScale`, `MissileCollisionRadius`, "
      "`MissileCollisionPush`, `MissileCollisionRaise`) VALUES")
    w("(" + ", ".join([str(MASTIFF_MODEL)] + [q(v.replace("\\\\", "\\")) if isinstance(v, str) else repr(v) for v in MASTIFF_MODEL_ROW]) + ");")
    w("DELETE FROM `creaturedisplayinfo_dbc` WHERE `ID` BETWEEN 39700 AND 39703;")
    w("INSERT INTO `creaturedisplayinfo_dbc` (`ID`, `ModelID`, `SoundID`, `ExtendedDisplayInfoID`, `CreatureModelScale`, `CreatureModelAlpha`, "
      "`TextureVariation_1`, `TextureVariation_2`, `TextureVariation_3`, `PortraitTextureName`, `BloodLevel`, `BloodID`, `NPCSoundID`, "
      "`ParticleColorID`, `CreatureGeosetData`, `ObjectEffectPackageID`) VALUES")
    w(",\n".join(f"({display}, {MASTIFF_MODEL}, 0, 0, {MASTIFF_SCALE}, 255, 'MastiffDog_{coat}', '', '', '', 0, 0, 0, 0, 0, 0)"
                  for display, coat in MASTIFF_COATS) + ";")
    w("INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES")
    w(f"({TEXT_DOG}, {q('(The dog looks happy to see you.)')}, {q('(The dog looks happy to see you.)')}, 1),")
    w(f"({TEXT_DOG_WAITING}, {q('(The mastiff sits by the door, watching you. It seems to be waiting until you have room for it.)')}, "
      f"{q('(The mastiff sits by the door, watching you. It seems to be waiting until you have room for it.)')}, 1);")
    w("INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, "
      "`RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, "
      "`RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`) VALUES")
    w(f"({QUEST_DOG}, 2, -1, 5, {AREA}, 8, 1, 0, 0, 'A Visitor in the Night', {q(DOG_LOG)}, {q(DOG_OFFER)}, '', "
      f"{q('Return to the mastiff.')}, {NPC_FETCH_CREDIT}, 3, 'Played fetch', {NPC_EARS_CREDIT}, 1, 'Scratched behind the ears');")
    w(f"INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES ({QUEST_DOG}, 0);")
    w(f"INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES ({QUEST_DOG}, 0, {q(DOG_DONE)});")
    w(f"INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES ({QUEST_DOG}, 0, 0, {q(DOG_PROGRESS)});")
    w(f"INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES ({NPC_DOG}, {QUEST_DOG});")
    w(f"INSERT INTO `creature_questender` (`id`, `quest`) VALUES ({NPC_DOG}, {QUEST_DOG});")

    # The game's own tables for it (patch-W, src/BrillHomePatch.cpp).
    os.makedirs(CLIENT_DBC, exist_ok=True)
    with open(os.path.join(CLIENT_DBC, "CreatureModelData.txt"), "w", encoding="utf-8") as f:
        f.write("# CreatureModelData: the mastiff (tools/build_sql.py)\ntypes\tiisifiiffffiiiffffffffffffff\n")
        # (a backslash in a change file is written twice, as the other modules' are)
        f.write("put\t" + "\t".join(str(v).replace("\\", "\\\\") for v in [MASTIFF_MODEL] + MASTIFF_MODEL_ROW) + "\n")
    with open(os.path.join(CLIENT_DBC, "CreatureDisplayInfo.txt"), "w", encoding="utf-8") as f:
        f.write("# CreatureDisplayInfo: the mastiff's four coats (tools/build_sql.py)\ntypes\tiiiifissssiiiiii\n")
        for display, coat in MASTIFF_COATS:
            f.write(f"put\t{display}\t{MASTIFF_MODEL}\t0\t0\t{MASTIFF_SCALE}\t255\tMastiffDog_{coat}\t\t\t\t0\t0\t0\t0\t0\t0\n")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
