#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Makes mod-brill-home's world SQL (data/sql/chromiecraft/world/brill_home_world.sql): the house in
Brill, its furniture, Cedric Harrow, Jamie Nore's part and the quests, the mastiff, and Carolai Anise's
quests (her keepsakes: her locket at Solliden Farmstead, a lily on Brightwater Lake's shore).

The house is the farmhouse Brill's own houses are (Duskwood_Human_Farm.wmo, the game's display 7432),
as a gameobject of the kind the game's own buildings out in the world are (a destructible building,
never damaged: Light's Point Tower's). Where it stands (the user, after four places: "The best place you
think that doesn't look stupid and looks like a natural place to put it"), found from the server's data
(data.zip: the terrain's heights, the world's models; the guards' paths for the roads; every spawn),
searching Brill for the ground that holds the whole model level, beside a road, its door onto it:
- (2275.0, 480.5), map 56.5, 51.9: beside the road north out of Brill, 15 yards off it, nothing within the
  model's 19 by 18 yards (the builder's yard of the Community Projects moved for it);
- turned 290 degrees: its doorway (the model's own side at 257.5 degrees) faces the road, south;
- its base 29.96 high: the floor (4.1 above the base) 0.2 above the highest ground under it; the ground
  under it is level to 0.4 yards and the doorstep 0.25 above the ground outside, as Brill's own farmhouse
  stands.

Everything inside is placed in the house's own coordinates (yards from its middle, the doorway's side
at -y) and turned with it. Run it after changing the layout; the output is committed.
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "data", "sql", "chromiecraft", "world", "brill_home_world.sql")

MAP, ZONE, AREA = 0, 85, 159
HOUSE_X, HOUSE_Y, HOUSE_BASE = 2275.0, 480.5, 29.96
HOUSE_O = 5.0615  # radians (290 degrees): the doorway toward the road, south of it
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
# Laid out against the farmhouse's own walls (its collision model in the server's vmaps,
# Duskwood_Human_Farm.wmo.vmo: the room inside is x -5.25 to 5.5, y -5.75 to 5.5; the hearth stands out
# from the north wall between x -1.0 and 3.5 to y 4.6, its mantel 2.1 up; the door is x -3.0 to -0.25
# in the south wall; braces fill the north corners (x below -3.25 and above 4.5, y above 4.25) and posts
# the south ones; the loft is over y below -3, 4.1 up) and each model's own size (the game's files), so
# nothing stands in a wall, in the hearth, in the doorway or in another piece. The bed under the loft in
# the south-east corner, its headboard to the east wall; the table and chairs on the west side; the
# wardrobe on the west wall; the bookshelf on the east wall; the rug before the hearth; the stove
# between the hearth and the north-east brace.
FURNITURE = [
    (GO_BED, 2, 3471, 1.0, "Starter Bed", PHASE_BED, [(2.98, -3.42, 0, 0)]),
    (GO_TABLE, 5, 234, 1.0, "Kitchen Table", PHASE_TABLE, [(-2.1, 2.3, 0, 90)]),
    (GO_CHAIR, 7, 39, 1.0, "Chair", PHASE_TABLE, [(-2.1, 0.85, 0, 90), (0.15, 2.3, 0, 180)]),
    (GO_WARDROBE, 5, 4, 1.0, "Wardrobe", PHASE_WARDROBE, [(-4.93, -3.6, 0, 0)]),
    (GO_BOOKSHELF, 5, 188, 1.0, "Bookshelf", PHASE_BOOKSHELF, [(5.13, 2.8, 0, 180)]),
    (GO_RUG, 5, 9405, 0.65, "Bearskin Rug", PHASE_RUG, [(1.9, 2.5, 0, 90)]),
    (GO_STOVE, 5, 348, 1.0, "Pot-Bellied Stove", PHASE_STOVE, [(3.98, 4.42, 0, -90)]),
]
CEDRIC_AT = (-1.0, -3.3, 257.5)  # inside the door, facing it

# The mastiff (there when the player first wakes in the bed; its quest; then the player's pet).
NPC_DOG, NPC_FETCH_CREDIT, NPC_EARS_CREDIT, NPC_DOG_PET = 970112, 970113, 970114, 970115
# A hunter's dog beside their own pet, and a beast master's stabled pet beside them both (its looks
# put on it as it's summoned: src/BrillHome.cpp).
NPC_DOG_COMPANION, NPC_BEAST_COMPANION = 970116, 970117
QUEST_DOG = 970123
TEXT_DOG, TEXT_DOG_WAITING = 970144, 970145
PHASE_DOG = 0x400
DOG_AT = (-0.1, -1.9, -35)  # by the foot of the bed, looking toward it
# Its model (tools/mastiff.py, in the game's patch-<locale>-V) and its four coats: ids clear of the game's and
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

# Carolai Anise (Brill's alchemy trainer): her five quests once the mastiff is the player's, a romance or
# a friendship (the user's choice in the last; the rewards the same), and, as lovers, her moving in.
CAROLAI = 2132
NPC_CAROLAI_VISIT, NPC_CAROLAI_HOME = 970118, 970119  # in the house: the evening she lets herself in; living there
NPC_TEA_CREDIT, NPC_LOCKET_CREDIT, NPC_LILY_CREDIT, NPC_ANSWER_CREDIT = 970150, 970151, 970152, 970153
GO_LOCKET, GO_LILY = 970108, 970109
QUEST_TONIC, QUEST_LOCKET, QUEST_GIFT, QUEST_TEA, QUEST_LILY = range(970124, 970129)
TEXT_CAROLAI_VISIT, TEXT_CAROLAI_HOME, TEXT_CAROLAI_ANSWER, TEXT_CAROLAI_LOVER = 970146, 970147, 970148, 970149
TEXT_CAROLAI_FRIEND, TEXT_LOCKET, TEXT_LILY, TEXT_CAROLAI_CALLING = 970150, 970151, 970152, 970153
# Her alchemy table in the house while she lives there (src/BrillHome.cpp: where she works, reads, makes
# tea and sits with the dog, by turns).
GO_ALCHEMY = 970110
ALCHEMY_AT = (-4.47, -1.0, 0)  # against the west wall
PHASE_CAROLAI_VISIT, PHASE_CAROLAI_HOME = 0x10000, 0x20000
CAROLAI_DISPLAY = 1601
CURSED_DARKHOUND = 1548
CAROLAI_VISIT_AT = (-1.0, -3.3, 257.5)  # where Cedric stood, facing the door as the player comes in
CAROLAI_HOME_AT = (-3.3, -1.0, 180)  # at her alchemy table
# Her mother's locket in Solliden Farmstead's farmhouse (beside old Farmer Solliden, who walks it);
# the lily on Brightwater Lake's shore (among the Vile Fins): heights from the game's terrain.
LOCKET_AT = (2266.6, 1316.4, 34.1, 1.2)
LILY_AT = (2905.0, 200.0, 7.86, 0.0)


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
    "I built this house before the plague, for Elinor and our two boys. When I rose again my feet carried "
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


# Carolai's words. She's a yandere (the user's): sweet, attentive, and a little too much of both.
TONIC_OFFER = (
    "You're the one who took Cedric's house. With the mastiff.$B$B"
    "Don't look so surprised. Brill is small, and I have a window. I see you leave at first light and I see "
    "you come home, and I see that dog of yours sitting in your doorway, scratching at its ears.$B$B"
    "It's blightmange. The darkhounds carry it out of the woods and leave it in the grass for every living "
    "dog in Tirisfal to roll in. First the fur comes out in clumps, then the skin goes, and then... well. "
    "You've seen the darkhounds.$B$B"
    "I can brew something for it. But it's no use treating him while the hills are crawling with the things "
    "that gave it to him. Thin out the Cursed Darkhounds north of Brill, and the tonic will be ready when you "
    "come back.$B$BI'll be right here. I'm always right here.")
TONIC_LOG = "Kill 8 Cursed Darkhounds in the hills north of Brill, then return to Carolai Anise."
TONIC_PROGRESS = "Has he stopped scratching? He hadn't this morning. Five times before you'd even opened your shutters."
TONIC_DONE = (
    "Eight. I counted the howls from here. I thought it might be nine.$B$B"
    "Here. Rub it into his ears, morning and night. He'll fuss; they always fuss.$B$B"
    "Don't thank me, $n. It was nothing. I like having a reason to see you.")

LOCKET_OFFER = (
    "The tonic took. He barely scratched at all today. I watched.$B$B"
    "May I ask you something? Not as an alchemist.$B$B"
    "Before the plague I lived at Solliden Farmstead, north of here. My mother gave me a locket the day I was "
    "sent to Lordaeron to apprentice. When I rose, it was gone. I've never gone back for it. Old Solliden "
    "still walks his fields, and he doesn't remember me. He doesn't remember anything.$B$B"
    "I could ask anyone in Brill. I'm asking you. It was in the farmhouse, in the footlocker under the "
    "window. Will you bring it back to me?")
LOCKET_LOG = "Recover Carolai's locket from the footlocker in the farmhouse at Solliden Farmstead, then return to Carolai Anise in Brill."
LOCKET_PROGRESS = "The farmhouse is north, past the mills. Mind Solliden. He was kind, once."
LOCKET_DONE = (
    "You found it.$B$B"
    "(She turns the locket over in her fingers for a long time.)$B$B"
    "I'd forgotten her face. I'd started to think I had made her up.$B$B"
    "Thank you, $n. I'll keep it close. I keep everything that matters close.")
LOCKET_FOUND = (
    "(Under a moth-eaten quilt in the footlocker lies a small tin locket, its chain knotted. Inside is a faded "
    "painting of a young woman with Carolai's eyes.)$B$B(You take it.)")

GIFT_OFFER = (
    "I've been thinking about Jamie Nore.$B$B"
    "She found you that house. She found you the bed. She's always so helpful, isn't she? Always there, with "
    "her ledgers and her deeds and her warm, living hands.$B$B"
    "I should thank her properly, for looking after you before I could. Take her this: a perfume, a very old "
    "recipe. Tell her it's from me. Tell her I said she's been more than kind, and that she needn't trouble "
    "herself over you any longer.$B$B"
    "And $n? Don't open it. It isn't for you.")
GIFT_LOG = "Take Carolai's perfume to Jamie Nore in the Brill town hall."
GIFT_DONE = (
    "From Carolai? How... thoughtful.$B$B"
    "(Jamie unstoppers the vial, sniffs once, and goes very still.)$B$B"
    "That's bitterroot, $n. The Apothecaries put it in rat poison. A drop of this on the skin and I'd only "
    "be sick for a week. Probably.$B$B"
    "I'm sure it's a mistake. She does work with a great many bottles.$B$B"
    "(She corks it and drops it into the waste bin all the same.)$B$B"
    "She's been asking about you, you know. Since the day you signed the deed. Which road you take, when you "
    "come home, whether you have... company. Last week she asked me which key opens your door.$B$B"
    "I didn't give it to her. Just lock up tonight, would you?")

TEA_OFFER = (
    "Oh! You're home. You're early. You're never home this early.$B$B"
    "Your door was open. It was. I thought something might have happened to you, so I came in to wait, and "
    "then I couldn't just sit, could I? So I swept. And I washed the cups. And I folded your things. You "
    "fold your shirts in thirds; I've put them in halves. It's better for the linen.$B$B"
    "Jamie didn't like her present, did she? I could tell by the way she looked at me in the square. It "
    "doesn't matter.$B$B"
    "Sit with me. Please. I made tea. I can't taste it anymore, but I remember how it ought to be made, and I "
    "made it for you.")
TEA_LOG = "Sit and drink tea with Carolai in your house, then speak with her at her shop in Brill."
TEA_PROGRESS = "You came. I wasn't sure you would."
TEA_DONE = (
    "You came. I wasn't sure you would.$B$B"
    "I've stood at this bench for six years mixing tinctures for travelers who never look up. Then you looked "
    "up.$B$B"
    "I know what I am, $n. I know what the living say about us, and I know what Jamie says about me. I know I "
    "don't go about this the way other people do. But I have never wanted anything the way I want to be near "
    "you.$B$B"
    "There. It's said. Now I need to know what you'll say back, and I want to hear it properly.")

LILY_OFFER = (
    "There's a flower that grows on the shore of Brightwater Lake, east of here: the moonpetal lily. Before "
    "the plague, a girl in Tirisfal gave one to the one she meant to marry. If they kept it, that was yes.$B$B"
    "I won't ask you a question you aren't ready for. So I'll ask you this instead. Go to the lake and bring "
    "me one. Then, when you give it to me, tell me what it means.$B$B"
    "Whatever you say, I'll... I'll understand.$B$B"
    "I'll be here. Waiting. As I always am.")
LILY_LOG = "Pick a moonpetal lily on the shore of Brightwater Lake, then give it to Carolai Anise in Brill and tell her what it means."
LILY_PROGRESS = "(Carolai looks at your hands first, to see if there's a lily in them.)"
LILY_FOUND = (
    "(A single pale lily grows where the water laps at the reeds. Its petals are cold, and faintly bright, "
    "like the moon on Lordamere.)$B$B(You pick it.)")
LILY_DONE = (
    "(Carolai threads the lily's stem through the locket's chain and lets it rest against her heart, where it "
    "will dry, and keep.)$B$B"
    "Whatever we are to each other, $n, you've given me a reason to keep my shutters open in the morning.$B$B"
    "Take these. I brewed them for you before you ever walked into my shop. I brew a great many things for "
    "you.")

CAROLAI_ANSWER = "(Carolai looks at the lily in your hand, then at you, and waits.)"
CAROLAI_VISIT = "(Carolai has swept your floor, folded your things and made tea. She's waiting for you to sit.)"
CAROLAI_LOVER = "There you are. You always come back to me, $n.$B$BI'd know your footsteps anywhere. I listen for them."
CAROLAI_FRIEND = "Hello, friend.$B$BYou didn't sleep well last night. No, don't tell me. I know you didn't."
# A friend's call now and then (one day in five she's at the house when they come home).
CAROLAI_CALLING = [
    "I let myself in. You don't mind. Friends don't mind. I brought you a tonic for the cough you had on Tuesday.",
    "I was passing. I'm always passing. Your dog was lonely, so I sat with him. We had a long talk about you.",
    "Jamie says I shouldn't come in when you're out. Jamie says a great many things.",
]
CAROLAI_HOME = [
    "Welcome home, my love. I've warmed the kettle. I warm it every time I hear you on the road.",
    "The dog likes me now. He sleeps across the doorway, so nothing gets in. Or out. Good boy.",
    "I moved your things around a little. You'll get used to it. I got used to everything about you.",
    "Jamie waved at you in the square today. I saw. It's all right. I'm not angry. I'm never angry with you.",
    "I've set up my bench by the window. The light's better here. And I can see the road, so I know when you're coming.",
    "I read to the dog while you were out. He likes the sad parts. So do I.",
    "Tea? I made it the way you like it. I've been watching how you like it.",
]


def main():
    out = []
    w = out.append
    w("-- mod-brill-home (ChromieCraft's world database alone: ctl.sh realm_sql). Made by tools/build_sql.py;")
    w("-- don't edit by hand. Written to be applied again: its own rows go first.")
    w("")
    w("DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 970100 AND 970119;")
    w("DELETE FROM `gameobject` WHERE `guid` BETWEEN 970100 AND 970149;")
    w("DELETE FROM `creature_template` WHERE `entry` BETWEEN 970110 AND 970119;")
    w("DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 970110 AND 970119;")
    w("DELETE FROM `creature` WHERE `guid` BETWEEN 970100 AND 970149;")
    for t in ("quest_template", "quest_template_addon", "quest_offer_reward", "quest_request_items"):
        w(f"DELETE FROM `{t}` WHERE `ID` BETWEEN 970120 AND 970129;")
    w("DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 970120 AND 970129;")
    w("DELETE FROM `creature_questender` WHERE `quest` BETWEEN 970120 AND 970129;")
    w("DELETE FROM `creature_template` WHERE `entry` BETWEEN 970150 AND 970159;")
    w("DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 970150 AND 970159;")
    w("DELETE FROM `npc_text` WHERE `ID` BETWEEN 970140 AND 970159;")
    w("")

    w("-- The house (the farmhouse Brill's own are), its furniture (each in its phase).")
    w("INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `Data3`, `ScriptName`) VALUES")
    # (a building as the game's own buildings out in the world are: a destructible building that's never
    # damaged, as Light's Point Tower is; the game draws a building's model for that kind)
    rows = [f"({GO_HOUSE}, 33, 7432, 'Harrow Farmhouse', 1, 0, 0, 0, '')"]
    for entry, kind, display, scale, name, _, _ in FURNITURE:
        data0, data1 = (1, 1) if kind == 7 else (0, 0)  # a chair: one seat, normal height
        script = "go_brill_home_bed" if entry == GO_BED else ""
        data3 = 0  # (the bed's menu is its script's: src/BrillHome.cpp)
        rows.append(f"({entry}, {kind}, {display}, {q(name)}, {scale}, {data0}, {data1}, {data3}, {q(script)})")
    w(",\n".join(rows) + ";")
    w(f"DELETE FROM `gameobject_template_addon` WHERE `entry` = {GO_HOUSE};")
    w(f"INSERT INTO `gameobject_template_addon` (`entry`, `faction`, `flags`) VALUES ({GO_HOUSE}, 0, 32);")
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
    w("-- template its pet is made from; the quest's two credits. Its coats are its model's (patch-<locale>-V).")
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
    w("-- The server's copy of the game's rows for it (the client's are in patch-<locale>-V: data/client/dbc).")
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

    w("")
    w("-- Carolai Anise: her quests (once the mastiff is the player's), her visit to the house, her living")
    w("-- there; the keepsakes she sends the player for, and the credits. Her menu is src/BrillHome.cpp's.")
    w(f"UPDATE `creature_template` SET `npcflag` = `npcflag` | 3, `ScriptName` = 'npc_brill_home_carolai' WHERE `entry` = {CAROLAI};")
    w("INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, "
      "`speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `flags_extra`, `ScriptName`) VALUES")
    w(",\n".join([
        f"({NPC_CAROLAI_VISIT}, 'Carolai Anise', NULL, 0, 20, 20, 68, 3, 1, 1.14286, 1, 0, 6, 2, 'npc_brill_home_carolai_visit')",
        f"({NPC_CAROLAI_HOME}, 'Carolai Anise', NULL, 0, 20, 20, 68, 1, 1, 1.14286, 1, 0, 6, 2, 'npc_brill_home_carolai_home')",
    ] + [f"({e}, {q(n)}, NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 130, '')" for e, n in (
        (NPC_TEA_CREDIT, "Shared tea with Carolai"), (NPC_LOCKET_CREDIT, "Carolai's locket"),
        (NPC_LILY_CREDIT, "Moonpetal lily"), (NPC_ANSWER_CREDIT, "Gave Carolai your answer"))]) + ";")
    w("INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES")
    w(",\n".join([f"({NPC_CAROLAI_VISIT}, 0, {CAROLAI_DISPLAY}, 1, 1)", f"({NPC_CAROLAI_HOME}, 0, {CAROLAI_DISPLAY}, 1, 1)"]
                  + [f"({e}, 0, 11686, 1, 1)" for e in (NPC_TEA_CREDIT, NPC_LOCKET_CREDIT, NPC_LILY_CREDIT, NPC_ANSWER_CREDIT)]) + ";")
    w("INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, "
      "`orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES")
    rows = []
    for guid, entry, phase, (lx, ly, deg) in ((970102, NPC_CAROLAI_VISIT, PHASE_CAROLAI_VISIT, CAROLAI_VISIT_AT),
                                               (970103, NPC_CAROLAI_HOME, PHASE_CAROLAI_HOME, CAROLAI_HOME_AT)):
        x, y = world(lx, ly)
        rows.append(f"({guid}, {entry}, {MAP}, {ZONE}, {AREA}, 1, {phase}, {x:.3f}, {y:.3f}, {FLOOR:.3f}, {turn(deg):.4f}, 60, 0, 0, 'mod-brill-home')")
    w(",\n".join(rows) + ";")
    w("INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `Data3`, `ScriptName`) VALUES")
    w(f"({GO_LOCKET}, 2, 8, 'Old Footlocker', 1, 0, 0, 0, 'go_brill_home_keepsake'),")
    w(f"({GO_LILY}, 2, 253, 'Moonpetal Lily', 1, 0, 0, 0, 'go_brill_home_keepsake'),")
    w(f"({GO_ALCHEMY}, 5, 4391, 'Carolai\\'s Alchemy Table', 0.8, 0, 0, 0, '');")
    w("INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, "
      "`position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES")
    w(",\n".join([go_spawn(970120, GO_LOCKET, *LOCKET_AT, 1).replace(f", {ZONE}, {AREA},", ", 85, 0,"),
                  go_spawn(970121, GO_LILY, *LILY_AT, 1).replace(f", {ZONE}, {AREA},", ", 85, 0,"),
                  go_spawn(970122, GO_ALCHEMY, *world(ALCHEMY_AT[0], ALCHEMY_AT[1]), FLOOR, turn(ALCHEMY_AT[2]), PHASE_CAROLAI_HOME)]) + ";")
    w("INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES")
    w(",\n".join(f"({i}, {q(t)}, {q(t)}, 1)" for i, t in (
        (TEXT_CAROLAI_VISIT, CAROLAI_VISIT), (TEXT_CAROLAI_ANSWER, CAROLAI_ANSWER), (TEXT_CAROLAI_LOVER, CAROLAI_LOVER),
        (TEXT_CAROLAI_FRIEND, CAROLAI_FRIEND), (TEXT_LOCKET, LOCKET_FOUND), (TEXT_LILY, LILY_FOUND))) + ";")
    # (living together: one of her lines at random each time, the game's way)
    cols = ", ".join(f"`text{i}_0`, `text{i}_1`, `Probability{i}`" for i in range(len(CAROLAI_HOME)))
    vals = ", ".join(f"{q(t)}, {q(t)}, {1 / len(CAROLAI_HOME):.2f}" for t in CAROLAI_HOME)
    w(f"INSERT INTO `npc_text` (`ID`, {cols}) VALUES ({TEXT_CAROLAI_HOME}, {vals});")
    cols = ", ".join(f"`text{i}_0`, `text{i}_1`, `Probability{i}`" for i in range(len(CAROLAI_CALLING)))
    vals = ", ".join(f"{q(t)}, {q(t)}, {1 / len(CAROLAI_CALLING):.2f}" for t in CAROLAI_CALLING)
    w(f"INSERT INTO `npc_text` (`ID`, {cols}) VALUES ({TEXT_CAROLAI_CALLING}, {vals});")
    w("INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, "
      "`RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, "
      "`RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, "
      "`RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`) VALUES")
    back = q("Return to Carolai Anise in Brill.")
    w(",\n".join([
        f"({QUEST_TONIC}, 2, -1, 5, {AREA}, 0, 4, 2500, 0, 'A Tonic for the Mastiff', {q(TONIC_LOG)}, {q(TONIC_OFFER)}, '', {back}, "
        f"{CURSED_DARKHOUND}, 8, '', 0, 0, '', 0, 0, 0, 0)",
        f"({QUEST_LOCKET}, 2, -1, 5, {AREA}, 0, 4, 2500, 0, 'What Solliden Kept', {q(LOCKET_LOG)}, {q(LOCKET_OFFER)}, '', {back}, "
        f"{NPC_LOCKET_CREDIT}, 1, 'Carolai\\'s locket', 0, 0, '', 0, 0, 0, 0)",
        f"({QUEST_GIFT}, 2, -1, 5, {AREA}, 0, 2, 2500, 0, 'A Gift for Jamie Nore', {q(GIFT_LOG)}, {q(GIFT_OFFER)}, '', "
        f"{q('Speak with Jamie Nore in Brill.')}, 0, 0, '', 0, 0, '', 0, 0, 0, 0)",
        f"({QUEST_TEA}, 2, -1, 5, {AREA}, 0, 3, 2500, 0, 'An Unlocked Door', {q(TEA_LOG)}, {q(TEA_OFFER)}, '', {back}, "
        f"{NPC_TEA_CREDIT}, 1, 'Shared tea with Carolai', 0, 0, '', 0, 0, 0, 0)",
        f"({QUEST_LILY}, 2, -1, 5, {AREA}, 0, 5, 10000, 0, 'What the Heart Keeps', {q(LILY_LOG)}, {q(LILY_OFFER)}, '', {back}, "
        f"{NPC_LILY_CREDIT}, 1, 'Moonpetal lily', {NPC_ANSWER_CREDIT}, 1, 'Gave Carolai your answer', 929, 5, 3385, 3)",
    ]) + ";")
    w("INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES")
    w(f"({QUEST_TONIC}, {QUEST_DOG}), ({QUEST_LOCKET}, {QUEST_TONIC}), ({QUEST_GIFT}, {QUEST_LOCKET}), "
      f"({QUEST_TEA}, {QUEST_GIFT}), ({QUEST_LILY}, {QUEST_TEA});")
    w("INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES")
    w(f"({QUEST_TONIC}, 1, {q(TONIC_DONE)}), ({QUEST_LOCKET}, 0, {q(LOCKET_DONE)}), ({QUEST_GIFT}, 0, {q(GIFT_DONE)}), "
      f"({QUEST_TEA}, 1, {q(TEA_DONE)}), ({QUEST_LILY}, 0, {q(LILY_DONE)});")
    w("INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES")
    w(f"({QUEST_TONIC}, 1, 1, {q(TONIC_PROGRESS)}), ({QUEST_LOCKET}, 1, 1, {q(LOCKET_PROGRESS)}), "
      f"({QUEST_TEA}, 0, 0, {q(TEA_PROGRESS)}), ({QUEST_LILY}, 0, 0, {q(LILY_PROGRESS)});")
    w("INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES")
    w(f"({CAROLAI}, {QUEST_TONIC}), ({CAROLAI}, {QUEST_LOCKET}), ({CAROLAI}, {QUEST_GIFT}), ({NPC_CAROLAI_VISIT}, {QUEST_TEA}), "
      f"({CAROLAI}, {QUEST_LILY});")
    w("INSERT INTO `creature_questender` (`id`, `quest`) VALUES")
    w(f"({CAROLAI}, {QUEST_TONIC}), ({CAROLAI}, {QUEST_LOCKET}), ({JAMIE_NORE}, {QUEST_GIFT}), ({CAROLAI}, {QUEST_TEA}), "
      f"({CAROLAI}, {QUEST_LILY});")

    # The game's own tables for it (patch-<locale>-V, src/BrillHomePatch.cpp).
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
