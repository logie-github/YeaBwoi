#!/usr/bin/env python3
"""Writes the Infinite Bazaar's camp: data/sql/remix/world/wrath_remix_world_0023_bazaar.sql.

    python3 tools/bazaar.py

The Bazaar is a camp, not a ring of people. Around the story's own people at its heart (Eternus,
Moratari, the echoes; wrath_remix_world_0004_spawns.sql and the others place them, and the turn's
scene walks between them), it has districts a Timerunner can read at a glance:

  - the heart, under a great hourglass, where every Timerunner wakes;
  - the market lane along the west edge: a stall for each Bronze trader, Alurmi's first;
  - the training yard to the south-east: the instructors at work, dummies and targets to try;
  - the waystation to the north-east: Zephyr, her drake and a door in time; Sa'at's map table;
  - the chronicle corner to the north-west: the Chronologist's shelves, Momentus's orb;
  - the campfire to the south: other Timerunners, veterans of this war from their own hours after it, and a cook;
  - and between them porters on their rounds, wardens walking the edge, whelps underfoot.

Everyone with something to say says it now and then while a Timerunner is about, to the air or to
someone near them (src/RemixBazaar.cpp). Positions are the world's (x north, y west); heights are
the floor's, from data/bazaar_ground.tsv (`.remix ground` on the realm, a reading every two
yards), so nothing floats or sinks.
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "data", "sql", "remix", "world", "wrath_remix_world_0023_bazaar.sql")
GROUND = os.path.join(HERE, "..", "data", "bazaar_ground.tsv")

MAP, ZONE = 571, 2817

# Emotes (SharedDefines.h): one-shot gestures for lines, looping states for what people do.
TALK, BOW, WAVE, CHEER, EXCLAIM, QUESTION, EAT, LAUGH, POINT, SALUTE, YES, NO = 1, 2, 3, 4, 5, 6, 7, 11, 25, 66, 273, 274
STATE_WORK, STATE_TALK, STATE_EAT, STATE_READY1H, STATE_BOW, STATE_SPELL = 173, 378, 418, 333, 376, 474
SIT, SLEEP, KNEEL = 1, 3, 8                                          # stand states (creature_addon.bytes1)

EAST, NORTH, WEST, SOUTH = 3 * math.pi / 2, 0.0, math.pi / 2, math.pi


def load_ground():
    ground = {}
    if os.path.exists(GROUND):
        for line in open(GROUND):
            if line.startswith("#") or not line.strip():
                continue
            x, y, z = line.split()
            ground[(int(x), int(y))] = float(z)
    return ground


GROUND_Z = load_ground()


def z_at(x, y):
    """The floor under (x, y): the nearest reading within two yards (the plaza is level)."""
    best, best_d = None, 99.0
    for gx in range(int(x) - 3, int(x) + 4):
        for gy in range(int(y) - 3, int(y) + 4):
            if (gx, gy) in GROUND_Z:
                d = math.hypot(gx - x, gy - y)
                if d < best_d:
                    best, best_d = GROUND_Z[(gx, gy)], d
    if best is None:
        raise SystemExit(f"no floor reading near {x} {y}: survey there first (data/bazaar_ground.tsv)")
    return best


def facing(x, y, tx, ty):
    """The orientation from (x, y) towards (tx, ty)."""
    return math.atan2(ty - y, tx - x) % (2 * math.pi)


def sql_text(text):
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


# ---- What stands in the camp (gameobject templates of their own, 770001 and up) ----------------
# (key: display, size, name). Displays are the game's own (GameObjectDisplayInfo.dbc).
PROPS = {
    "hourglass":   (7133, 0.065, "The Great Hourglass"),        # the Caverns of Time's, small
    "rug_green":   (6767, 1.4, "Rug"),
    "rug_red":     (6360, 1.6, "Rug"),
    "brazier":     (474, 1.0, "Brazier"),                        # purple flame, the Infinite's colour
    "brazier_sm":  (6765, 1.0, "Brazier"),
    "lamp":        (8091, 0.55, "Lamppost"),                     # Dalaran's, above us
    "stall":       (7498, 0.75, "Stall"),                        # an open canopy
    "pavilion":    (7683, 0.6, "Pavilion"),
    "wagon":       (5392, 0.9, "Wagon"),
    "crate":       (31, 1.0, "Crate"),
    "crate_be":    (6792, 1.2, "Crate"),
    "crate_open":  (6791, 1.2, "Crate"),
    "barrel":      (6670, 1.0, "Barrel"),
    "table":       (6916, 1.1, "Table"),
    "table_big":   (6608, 1.0, "Table"),
    "bench":       (6979, 1.0, "Bench"),
    "weapon_rack": (130, 1.0, "Weapon Rack"),
    "weapon_crate": (6708, 1.0, "Weapon Crate"),
    "target":      (232, 0.8, "Archery Target"),
    "banner":      (6794, 0.8, "Banner"),
    "campfire":    (6744, 1.6, "Campfire"),
    "cookpot":     (6888, 1.0, "Cooking Pot"),
    "bookshelf":   (137, 1.0, "Bookshelf"),
    "bookshelf_low": (187, 1.0, "Bookshelf"),
    "book":        (6475, 1.0, "Book"),
    "scroll_map":  (222, 1.0, "Map"),
    "map_table":   (6605, 0.5, "Map Table"),
    "orb":         (7150, 1.0, "Scrying Orb"),
    "portal":      (4713, 0.12, "Door in Time"),
}
PROP_ENTRY = {key: 770001 + i for i, key in enumerate(PROPS)}
# Props that do something when clicked: (game object type, script). The campfire calls Moratari over
# (RemixCampfire.cpp); a goober (10) is clickable.
PROP_USE = {"campfire": (10, "go_remix_campfire")}

# Where each thing stands: (prop, x, y, orientation, what it's for)
PLACED = [
    # The heart: the great hourglass behind the echoes, a rug under Eternus and Moratari, braziers.
    ("hourglass", 5700.0, 1044.0, 0.0, "the heart"),
    ("rug_green", 5698.0, 1022.0, 0.0, "the heart, under Eternus and Moratari"),
    ("brazier", 5693.5, 1027.5, 0.0, "the heart"),
    ("brazier", 5706.5, 1027.5, 0.0, "the heart"),
    ("lamp", 5692.5, 1003.0, 0.0, "the heart, south"),
    ("lamp", 5711.0, 1034.0, 0.0, "the heart, north"),
    # The market lane, down the west edge: a stall for each trader, facing the lane.
    *[(kind, x, 1048.5, EAST, f"the market, {who}'s stall") for kind, x, who in (
        ("stall", 5686.0, "Alurmi"), ("stall", 5677.0, "Horos"), ("stall", 5668.0, "Domelius"),
        ("stall", 5659.0, "Arturos"), ("pavilion", 5650.0, "Aeonicus"), ("pavilion", 5641.5, "Durus"))],
    ("table", 5686.0, 1044.0, EAST, "Alurmi's counter"),
    ("weapon_crate", 5688.5, 1046.0, EAST, "Alurmi's heirlooms"),
    ("weapon_rack", 5683.0, 1047.5, EAST, "Alurmi's heirlooms"),
    ("crate_be", 5675.0, 1046.0, 0.4, "Horos's stall"),
    ("table", 5668.0, 1044.0, EAST, "Domelius's counter"),
    ("crate_open", 5665.5, 1046.0, 0.0, "Domelius's stall"),
    ("weapon_rack", 5661.5, 1047.5, EAST, "Arturos's stall"),
    ("weapon_rack", 5656.5, 1047.5, EAST, "Arturos's stall"),
    ("table_big", 5650.0, 1043.8, EAST, "Aeonicus's counter"),
    ("table_big", 5641.5, 1043.8, EAST, "Durus's counter"),
    ("banner", 5690.5, 1043.0, EAST, "the market's mouth"),
    ("lamp", 5663.5, 1042.0, 0.0, "the market lane"),
    # Across the lane: a trader's wagon, what the porters carry.
    ("wagon", 5668.0, 1029.0, NORTH, "the market lane"),
    ("crate", 5678.0, 1033.0, 0.3, "the market lane"),
    ("crate", 5678.5, 1031.6, 1.1, "the market lane"),
    ("barrel", 5677.0, 1032.2, 0.0, "the market lane"),
    ("barrel", 5655.0, 1033.0, 0.0, "the market lane"),
    ("crate_be", 5656.0, 1031.5, 0.7, "the market lane"),
    # The training yard, south-east: targets, racks, banners at its corners.
    ("target", 5649.0, 972.0, NORTH, "the training yard"),
    ("target", 5649.0, 978.0, NORTH, "the training yard"),
    ("weapon_rack", 5669.0, 964.0, WEST, "the training yard"),
    ("weapon_rack", 5673.0, 964.0, WEST, "the training yard"),
    ("banner", 5686.0, 964.0, WEST, "the training yard"),
    ("banner", 5686.0, 999.0, EAST, "the training yard"),
    ("banner", 5646.0, 999.0, EAST, "the training yard"),
    ("brazier", 5654.0, 996.5, 0.0, "the training yard, the shaman's fire"),
    ("brazier_sm", 5656.0, 986.5, 0.0, "the training yard, the warlock's"),
    ("rug_red", 5682.0, 996.5, 0.0, "the training yard, the priestess's"),
    # The waystation, north-east: a door in time, Sa'at's map table.
    ("portal", 5713.5, 981.0, facing(5713.5, 981.0, 5700.0, 1012.0), "the waystation"),
    ("map_table", 5692.0, 968.0, 0.0, "the waystation, Sa'at's"),
    ("lamp", 5700.0, 966.0, 0.0, "the waystation"),
    # The chronicle corner, north-west (on the level ground; the rise beyond is the hill's): shelves,
    # books, an orb.
    ("bookshelf", 5723.0, 1039.0, SOUTH, "the chronicle corner"),
    ("bookshelf", 5723.0, 1042.5, SOUTH, "the chronicle corner"),
    ("bookshelf_low", 5723.0, 1045.5, SOUTH, "the chronicle corner"),
    ("table", 5718.5, 1041.0, 0.0, "the chronicle corner"),
    ("book", 5718.5, 1041.0, 0.5, "the chronicle corner"),
    ("scroll_map", 5718.8, 1040.6, 1.2, "the chronicle corner"),
    ("orb", 5719.0, 1049.5, 0.0, "the chronicle corner, Momentus's"),
    ("brazier_sm", 5714.0, 1037.0, 0.0, "the chronicle corner"),
    # The campfire, south: benches round a fire, the cook's pot, lanterns.
    ("campfire", 5655.0, 1012.0, 0.0, "the campfire"),
    ("bench", 5650.5, 1012.0, NORTH, "the campfire"),
    ("bench", 5659.5, 1012.0, NORTH, "the campfire"),
    ("cookpot", 5645.5, 1006.0, 0.0, "the campfire, the cook's"),
    ("barrel", 5643.5, 1004.0, 0.0, "the campfire"),
    ("crate", 5642.5, 1009.0, 0.6, "the campfire"),
    ("brazier_sm", 5663.0, 1019.0, 0.0, "the campfire"),
    ("brazier_sm", 5648.0, 1020.5, 0.0, "the campfire"),
]

# ---- Who is in the camp -----------------------------------------------------------------------
# New people (770070 and up): entry: (name, subname, displays, type, flags, script, gossip text)
NOT_SELECTABLE = 0x02000000
PASSIVE = 770                                                         # not attackable, nor attacking
PEOPLE = {
    770070: ("Brannigan Holt", "Timerunner", [3343], 7, 0, "npc_remix_bazaar_life", 7702401),
    770071: ("Vaela Sunbrook", "Timerunner", [21271], 7, 0, "npc_remix_bazaar_life", 7702402),
    770072: ("Grukk Ironjaw", "Timerunner", [4560], 7, 0, "npc_remix_bazaar_life", 7702403),
    770073: ("Thaddeus Quill", "Timerunner", [21272], 7, 0, "npc_remix_bazaar_life", 7702404),
    770074: ("Tessaly Dawnwhisper", "Timerunner", [16685], 7, 0, "npc_remix_bazaar_life", 7702405),
    770075: ("Morrigan Ashe", "Timerunner", [1592], 7, 0, "npc_remix_bazaar_life", 7702406),
    770076: ("Zhar'ka", "Timerunner", [4068], 7, 0, "npc_remix_bazaar_life", 7702407),
    770077: ("Korvan Steelhide", "Timerunner", [21273], 7, 0, "npc_remix_bazaar_life", 7702408),
    770078: ("Marta Sandsworth", "Camp Cook", [27952], 7, 0, "npc_remix_bazaar_life", 7702409),
    770079: ("Sand Gnome Porter", None, [21027, 21028, 21029, 21030], 7, 0, "npc_remix_bazaar_life", 0),
    770080: ("Bazaar Warden", "Keepers of Time", [14887], 2, 0, "npc_remix_bazaar_life", 7702410),
    770081: ("Bronze Whelpling", None, [6292], 2, 0, "", 0),
    770082: ("Zephyr's Drake", None, [25852], 2, NOT_SELECTABLE, "", 0),
    770083: ("Time-Lost Proto-Drake", "Horos's Collection", [28045], 2, NOT_SELECTABLE, "", 0),
    770084: ("Hyacinth Macaw", "Domelius's Companions", [6192], 1, 0, "", 0),
    770085: ("Mana Wyrmling", "Domelius's Companions", [19600], 10, 0, "", 0),
    770086: ("Orange Tabby Cat", "Domelius's Companions", [5554], 1, 0, "", 0),
}
TRAINING_DUMMY = 770087                                               # the game's dummy, renamed

# Where the story's own Bazaar people stand now (written into 0004/0009, kept here for the lines
# and the checks): guid: (who, x, y, orientation)
STORY_SPOTS = {
    7704011: ("Eternus", 5700.0, 1023.0, 4.71),
    7704012: ("Moratari", 5691.0, 1017.0, 5.78),
    7704013: ("Sylvanas", 5704.0, 1021.0, 3.60),
    7704014: ("Alleria", 5706.5, 1018.5, 3.40),
    7704015: ("Alurmi", 5686.0, 1046.5, EAST),
    7704016: ("Zephyr", 5708.5, 984.0, facing(5708.5, 984.0, 5700.0, 1012.0)),
    7704017: ("Sa'at", 5695.5, 968.5, facing(5695.5, 968.5, 5692.0, 968.0)),
    7704018: ("the Chronologist", 5716.0, 1041.0, facing(5716.0, 1041.0, 5718.5, 1041.0)),
    7704019: ("Momentus", 5716.0, 1049.5, facing(5716.0, 1049.5, 5719.0, 1049.5)),
    7704201: ("Horos", 5677.0, 1046.5, EAST),
    7704202: ("Domelius", 5668.0, 1046.5, EAST),
    7704203: ("Arturos", 5659.0, 1046.0, EAST),
    7704204: ("Aeonicus", 5650.0, 1046.5, EAST),
    7704205: ("Durus", 5641.5, 1046.5, EAST),
    7704300: ("Andormu", 5701.5, 1024.0, 4.71),
    7704301: ("Nozari", 5698.5, 1024.0, 4.71),
}
# The instructors (770101 to 770111, guid 7704020 + n): (x, y, orientation, stand state, emote state)
DUMMIES = [(5674.0, 982.0), (5666.0, 982.0), (5658.0, 982.0)]
INSTRUCTORS = {
    770101: (5677.5, 984.5, facing(5677.5, 984.5, 5674.0, 982.0), 0, STATE_READY1H),      # warrior, at a dummy
    770102: (5684.0, 976.0, facing(5684.0, 976.0, 5684.0, 971.0), 0, 0),                  # the paladins, talking
    770103: (5684.0, 971.0, facing(5684.0, 971.0, 5684.0, 976.0), 0, 0),
    770104: (5661.0, 975.0, facing(5661.0, 975.0, 5649.0, 975.0), 0, STATE_BOW),          # hunter, at the targets
    770105: (5680.5, 989.5, facing(5680.5, 989.5, 5677.5, 984.5), 0, 0),                  # rogue, watching the warrior
    770106: (5682.0, 996.5, NORTH, KNEEL, 0),                                              # priestess, kneeling
    770107: (5671.0, 965.8, WEST + math.pi, 0, STATE_WORK),                                # death knight, at the racks
    770108: (5654.0, 994.0, facing(5654.0, 994.0, 5654.0, 996.5), 0, 0),                  # shaman, at her fire
    770109: (5666.0, 986.5, facing(5666.0, 986.5, 5666.0, 982.0), 0, STATE_SPELL),        # mage, at a dummy
    770110: (5658.0, 986.5, facing(5658.0, 986.5, 5658.0, 982.0), 0, 0),                  # warlock, at a dummy
    770111: (5649.5, 992.0, facing(5649.5, 992.0, 5654.0, 994.0), SIT, 0),                # druid, sitting
}

# New spawns, 7704500 and up: (guid, entry, x, y, orientation, stand state, emote state, wander)
SPAWNS = [
    # The campfire.
    (7704501, 770070, 5655.0, 1008.3, facing(5655.0, 1008.3, 5655.0, 1012.0), SIT, 0, 0),
    (7704502, 770071, 5654.0, 1015.7, facing(5654.0, 1015.7, 5655.0, 1012.0), SIT, 0, 0),
    (7704503, 770072, 5660.5, 1007.8, facing(5660.5, 1007.8, 5655.0, 1012.0), 0, 0, 0),
    (7704504, 770073, 5657.2, 1015.3, facing(5657.2, 1015.3, 5655.0, 1012.0), SIT, 0, 0),
    (7704505, 770074, 5662.5, 1021.5, facing(5662.5, 1021.5, 5700.0, 1044.0), 0, 0, 0),
    (7704506, 770075, 5647.0, 1018.0, facing(5647.0, 1018.0, 5645.5, 1021.5), 0, STATE_TALK, 0),
    (7704507, 770076, 5645.5, 1021.5, facing(5645.5, 1021.5, 5647.0, 1018.0), 0, 0, 0),
    (7704508, 770077, 5648.5, 1005.0, facing(5648.5, 1005.0, 5645.5, 1006.0), 0, STATE_EAT, 0),
    (7704509, 770078, 5644.0, 1007.0, facing(5644.0, 1007.0, 5645.5, 1006.0), 0, STATE_WORK, 0),
    # The market lane's critters, Domelius's companions.
    (7704510, 770084, 5667.0, 1042.0, 0.0, 0, 0, 3.0),
    (7704511, 770085, 5669.5, 1041.0, 0.0, 0, 0, 3.0),
    (7704512, 770086, 5666.0, 1040.0, 0.0, 0, 0, 3.0),
    # Horos's proto-drake, beside his stall; Zephyr's drake, resting.
    (7704513, 770083, 5677.0, 1058.0, EAST, 0, 0, 0),
    (7704514, 770082, 5709.5, 969.0, facing(5709.5, 969.0, 5704.0, 977.0), 0, 0, 0),
    # Whelplings underfoot.
    (7704515, 770081, 5693.0, 1040.0, 0.0, 0, 0, 5.0),
    (7704516, 770081, 5681.0, 1036.0, 0.0, 0, 0, 5.0),
    (7704517, 770081, 5664.0, 1004.0, 0.0, 0, 0, 5.0),
    # The training yard's dummies.
    *[(7704520 + i, TRAINING_DUMMY, x, y, NORTH, 0, 0, 0) for i, (x, y) in enumerate(DUMMIES)],
]

# Walkers: (guid, entry, [(x, y, pause ms), ...]) on a loop (waypoint_data, path id = guid).
WALKERS = [
    (7704530, 770079, [(5688.0, 1040.0, 4000), (5672.0, 1039.0, 0), (5646.0, 1039.0, 5000), (5646.0, 1034.0, 0),
                       (5672.0, 1035.0, 3000), (5688.0, 1036.0, 0)]),
    (7704531, 770079, [(5665.0, 1001.0, 3000), (5672.0, 996.0, 0), (5684.0, 1003.0, 0), (5690.0, 1008.0, 4000),
                       (5676.0, 1012.0, 0), (5667.0, 1016.0, 0)]),
    (7704532, 770079, [(5698.0, 982.0, 5000), (5689.5, 996.0, 0), (5686.0, 1030.0, 4000), (5689.5, 996.0, 0)]),
    # (the wardens are dragonspawn, two yards round: clear of the training yard's archery targets, and
    # of its racks, banner and lamp along y 964)
    (7704533, 770080, [(5645.0, 964.0, 2000), (5644.0, 996.0, 0), (5638.0, 1003.0, 0), (5638.0, 1024.0, 0), (5646.0, 1032.0, 0),
                       (5646.0, 1040.0, 3000), (5646.0, 1032.0, 0), (5638.0, 1024.0, 0), (5638.0, 1003.0, 0), (5644.0, 996.0, 0)]),
    (7704534, 770080, [(5650.0, 960.5, 2000), (5676.0, 960.5, 0), (5708.0, 960.5, 2000), (5676.0, 960.5, 0)]),
]

# ---- What they say ------------------------------------------------------------------------------
# guid: [(text, emote, reply guid, reply, reply emote)]
LINES = {
    # The story's people.
    7704011: [("Every stall in this camp holds something history threw away. I only kept it.", TALK, 0, "", 0),
              ("Do not let the sands hurry you, champion. Here, at least, there is time.", TALK, 0, "", 0)],
    7704012: [("Mind the hourglass. It runs in every direction at once, and it hates being bumped.", POINT, 0, "", 0),
              ("Every Timerunner who wakes here asks me the same thing first. The answer is no: you cannot go back yet.", TALK, 0, "", 0)],
    7704015: [("Heirlooms! Gear that grows with you, for Timerunners with no time to shop twice.", EXCLAIM, 0, "", 0),
              ("Bronze only. Gold has no sense of history.", TALK, 0, "", 0),
              ("Moratari! Someone tried to pay me in Stratholme coin again.", TALK, 7704012, "Did you take it?", QUESTION)],
    7704016: [("Roads fold best when nobody watches them do it.", TALK, 0, "", 0),
              ("Easy, girl. They are only Timerunners. They are always in a hurry.", TALK, 0, "", 0)],
    7704017: [("Every dungeon in Northrend, waiting at the hour you are ready for it. Ask, and I will tell you which.", TALK, 0, "", 0)],
    7704018: [("Valiance Keep, the first day... no, this page is smudged. Someone keeps changing things.", QUESTION, 0, "", 0),
              ("Every record says the same war. That is the comforting part. It is also the frightening part.", TALK, 0, "", 0)],
    7704019: [("Power kept is power owed. Time always collects.", TALK, 0, "", 0)],
    7704013: [("Stop watching the hourglass, sister. It will not run out.", TALK, 7704014,
               "It already has, in a hundred places. You just cannot hear it.", TALK),
              ("This year smells of plague and ice. I had forgotten. I had made myself forget.", 0, 0, "", 0)],
    7704014: [("When the bronze came for me, the Void was at the gates of Quel'Thalas. I hope they are still holding.", TALK,
               7704013, "They will hold. Our people always hold, until the moment they do not.", 0)],
    7704300: [("The record is quieter today.", TALK, 7704301, "Quieter is not the same as whole, Andormu.", TALK)],
    # The traders.
    7704201: [("Proto-drakes, mammoths, a horse made entirely of bones. Every mount Northrend ever saw, and a few it only dreamed of.", EXCLAIM, 0, "", 0)],
    7704202: [("Careful, that whelp bites. No, the other one.", POINT, 0, "", 0),
              ("Companions, keepsakes, toys. History remembers the battles. I remember what people carried into them.", TALK, 0, "", 0)],
    7704203: [("Every blade on this rack came out of a dungeon in a braver hand than mine. It can go back in with yours.", TALK, 0, "", 0)],
    7704204: [("Ten champions against the Scourge, again and again. Their spoils look better on you than in a tomb.", TALK, 0, "", 0)],
    7704205: [("Twenty-five against the Lich King's halls. This is what they carried home. The ones who came home.", TALK, 0, "", 0)],
    # The instructors.
    7704021: [("Again. Shield up. No, the other shield.", TALK, 0, "", 0)],
    7704025: [("You telegraph every swing, orc.", TALK, 7704021, "And you hide behind every tent. We all have our gifts.", LAUGH)],
    7704022: [("The Light sounds the same in every year.", TALK, 7704023, "Says a man who has never heard it sung in Silvermoon.", TALK)],
    7704024: [("Patience. The beast comes to the one who waits.", 0, 0, "", 0)],
    7704026: [("Breathe. The Light will wait for you. It always has.", 0, 0, "", 0)],
    7704027: [("I remember dying. It gets easier to talk about. Not much easier.", TALK, 0, "", 0)],
    7704028: [("The winds here blow from tomorrow. Listen, and you will hear rain that has not fallen yet.", TALK, 0, "", 0)],
    7704029: [("If you can see the fireball coming, you cast it too slowly.", TALK, 0, "", 0)],
    7704030: [("Do not feed the imp. Whatever it tells you.", NO, 0, "", 0)],
    7704031: [("Even on stone, things grow. Slowly. Sideways, sometimes.", 0, 0, "", 0)],
    # The other Timerunners, veterans of this war, each from their own hour after it.
    7704501: [("Last thing I remember, I was buying a round in Ironforge to toast the Lich King's fall.",
               TALK, 7704502, "Then we had better be home before it goes flat.", 0)],
    7704502: [("Third time through the Borean Tundra this week. The penguins are starting to recognize me.", LAUGH,
               7704501, "Those are not penguins taking notes, Vaela. Those are bronze dragons.", TALK)],
    7704503: [("Gnome. Why is my bag lighter than when I came?", QUESTION, 7704504, "Time dilation! Or I borrowed your rations. One of the two.", TALK),
              ("I fought my way into Ulduar. Next to what was under it, the Scourge is almost polite.", TALK, 7704504, "Almost. They still bite.", 0)],
    7704505: [("The Sunwell burns again in this year. In mine too, and still half of Silvermoon lies in ruins. Some wounds take longer than a war.", 0, 0, "", 0)],
    7704506: [("They keep asking if I am from this year. I am barely from this century.", TALK, 7704507,
               "Ya worry too much, mon. Time be a river. We just be fishin'.", LAUGH)],
    7704507: [("Vol'jin be chieftain of de Darkspear in dis year, and in mine. Some tings time don't touch.", TALK, 7704506,
               "Do not say that too loud. The dragons get twitchy.", 0)],
    7704508: [("Best stew in any century. Do not tell the cook I said so.", EAT, 7704509, "I heard that, Steelhide. Second bowl is still Bronze.", LAUGH)],
    7704509: [("Stew's on! Crystalsong mushrooms, and something the gnome swears is a vegetable.", EXCLAIM, 0, "", 0),
              ("Eat while you can. The Scourge does not stop for supper.", TALK, 0, "", 0)],
    # Those on their rounds.
    7704530: [("Mind your feet! Crates of yesterday coming through!", EXCLAIM, 0, "", 0)],
    7704531: [("Who orders forty hourglasses? Again?", QUESTION, 0, "", 0)],
    7704532: [("If this crate is from tomorrow, why is it already late?", TALK, 0, "", 0)],
    7704533: [("The Bazaar keeps its own peace, in every year.", 0, 0, "", 0)],
    7704534: [("No fighting, no thieving, and no meeting your own past self. Those are the rules.", 0, 0, "", 0)],
}

# What the Timerunners and the camp's people say when spoken to (npc_text, gossip_menu).
GOSSIP = {
    7702401: "Brannigan Holt, Stormwind. Or I was, a few years from now.$B$BYou look like you woke up the same way I did. Get your strength back, then get out there. The war is not going to wait for us twice.",
    7702402: "Shh. Listen to that fire. In my time I cannot sit by one without thinking of the Wrathgate.$B$BI know. I know what Eternus wants. I have not decided what I think about it yet. Have you?",
    7702403: "Grukk Ironjaw, of the Frostwolves. I have killed centaur and quilboar and once, by accident, a very large goat.$B$BThe Scourge I know: I followed it all the way to the Frozen Throne. It is good to fight something I already understand.",
    7702404: "Thaddeus Quill, engineer, Timerunner, and the only gnome in this camp who read the warning label on the hourglass.$B$BIt says do not shake. I shook it. That is how I got here, I think.",
    7702405: "The Sunwell is burning again. I can feel it from here, even in this year. It was dark for so long.$B$BWhatever Eternus offers you, $c, ask her what it costs. It always costs something.",
    7702406: "Morrigan Ashe. Forsaken, priestess, and very tired of being asked if I am from around here.$B$BI died in the Third War. I would rather not watch this one do it to anyone else. That is why I listened to Eternus. I am still listening.",
    7702407: "Zhar'ka of the Darkspear. De spirits be very confused in dis place, mon. Dey keep askin' me what year it be.$B$BI tell dem: all of dem.",
    7702408: "Korvan Steelhide, Ironforge. If you are looking for the stew, it is behind me, and it is mine.$B$B...Fine. There is enough for two.",
    7702409: "Sit, eat, Timerunner. Whatever year you came from, you came hungry. They all do.",
    7702410: "The Keepers set wardens on the Bazaar while Timerunners come and go. Keep the peace, and the peace will keep you.",
}


def main():
    out = []
    w = out.append
    w("""-- The Infinite Bazaar's camp (tools/bazaar.py writes this file: edit that and run it again). Around
-- the story's people at its heart, districts a Timerunner can read at a glance: the market lane along
-- the west edge (a stall for each Bronze trader), the training yard to the south-east (the
-- instructors at work, dummies and targets), the waystation to the north-east (Zephyr, her drake, a
-- door in time, Sa'at's map table), the chronicle corner to the north-west, the campfire to the
-- south (other Timerunners, veterans of this war from their own hours after it, and a cook), and porters, wardens
-- and whelps on their rounds. Who says what while a Timerunner is about is remix_bazaar_line
-- (src/RemixBazaar.cpp). Heights are the floor's (data/bazaar_ground.tsv). The story's own people
-- stand where wrath_remix_world_0004_spawns.sql, 0009 and 0018 place them.
""")
    # Props.
    lo, hi = 770001, 770001 + len(PROPS) - 1
    w(f"DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 770001 AND 770099;")
    w("INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `IconName`, `castBarCaption`, `unk1`, `size`, `AIName`, `ScriptName`, `VerifiedBuild`) VALUES")
    w(",\n".join(f"({PROP_ENTRY[k]}, {PROP_USE.get(k, (5, ''))[0]}, {d}, {sql_text(n)}, '', '', '', {s}, '', "
                 f"{sql_text(PROP_USE.get(k, (5, ''))[1])}, 0)" for k, (d, s, n) in PROPS.items()) + ";")
    w("")
    w("DELETE FROM `gameobject` WHERE `guid` BETWEEN 7705001 AND 7705299;")
    w("INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`,")
    w("  `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `ScriptName`, `VerifiedBuild`, `Comment`) VALUES")
    rows = []
    for i, (key, x, y, o, what) in enumerate(PLACED):
        z = z_at(x, y)
        rows.append(f"({7705001 + i}, {PROP_ENTRY[key]}, {MAP}, {ZONE}, 0, 1, 1, {x:.2f}, {y:.2f}, {z:.2f}, {o:.4f}, "
                    f"0, 0, {math.sin(o / 2):.6f}, {math.cos(o / 2):.6f}, 300, 100, 1, '', 0, {sql_text('Wrath Remix: ' + PROPS[key][2] + ', ' + what)})")
    w(",\n".join(rows) + ";")
    w("")

    # People.
    w("DELETE FROM `creature_template` WHERE `entry` BETWEEN 770070 AND 770099;")
    w("DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 770070 AND 770099;")
    w("INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `exp`, `faction`, `npcflag`,")
    w("  `speed_walk`, `speed_run`, `rank`, `unit_class`, `unit_flags`, `unit_flags2`, `type`, `flags_extra`,")
    w("  `HealthModifier`, `DamageModifier`, `BaseAttackTime`, `RegenHealth`, `ScriptName`) VALUES")
    rows = []
    for entry, (name, sub, displays, ctype, flags, script, text) in PEOPLE.items():
        menu = text - 7702400 + 77040 if text else 0
        rows.append(f"({entry}, {sql_text(name)}, {sql_text(sub) if sub else 'NULL'}, {menu}, 80, 80, 2, 35, {1 if text else 0}, "
                    f"1, 1.14286, 0, 1, {PASSIVE | flags}, 2048, {ctype}, 2, 10, 1, 2000, 1, {sql_text(script)})")
    w(",\n".join(rows) + ";")
    w("INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) VALUES")
    rows = []
    for entry, (name, sub, displays, *_rest) in PEOPLE.items():
        for i, d in enumerate(displays):
            rows.append(f"({entry}, {i}, {d}, 1, 1, 0)")
    w(",\n".join(rows) + ";")
    w("""
-- The training yard's dummies: the game's own, under a plain name, at a Timerunner's first level,
-- and the class lessons' (npc_remix_dummy, src/RemixLessons.cpp).
DROP TEMPORARY TABLE IF EXISTS `remix_dummy`;
CREATE TEMPORARY TABLE `remix_dummy` SELECT * FROM `creature_template` WHERE `entry` = 31146;
UPDATE `remix_dummy` SET `entry` = 770087, `name` = 'Training Dummy', `subname` = NULL, `minlevel` = 10, `maxlevel` = 10, `rank` = 0, `ScriptName` = 'npc_remix_dummy';
INSERT INTO `creature_template` SELECT * FROM `remix_dummy`;
DROP TEMPORARY TABLE `remix_dummy`;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`)
SELECT 770087, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, 0 FROM `creature_template_model` WHERE `CreatureID` = 31146;
""")

    # What they say when spoken to.
    w("DELETE FROM `npc_text` WHERE `ID` BETWEEN 7702401 AND 7702499;")
    w("INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES")
    w(",\n".join(f"({i}, {sql_text(t)}, {sql_text(t)}, 1)" for i, t in GOSSIP.items()) + ";")
    w("DELETE FROM `gossip_menu` WHERE `MenuID` BETWEEN 77040 AND 77099;")
    w("INSERT INTO `gossip_menu` (`MenuID`, `TextID`) VALUES")
    w(",\n".join(f"({i - 7702400 + 77040}, {i})" for i in GOSSIP) + ";")
    w("")

    # Where they stand, and what they do.
    w("DELETE FROM `creature` WHERE `guid` BETWEEN 7704500 AND 7704599;")
    w("INSERT INTO `creature` (`guid`, `id`, `map`, `spawnMask`, `phaseMask`, `equipment_id`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES")
    rows = []
    for guid, entry, x, y, o, stand, emote, wander in SPAWNS:
        name = PEOPLE[entry][0] if entry in PEOPLE else "Training Dummy"
        rows.append(f"({guid}, {entry}, {MAP}, 1, 1, 0, {x:.2f}, {y:.2f}, {z_at(x, y) + 0.1:.2f}, {o:.4f}, 60, {wander:.1f}, {1 if wander else 0}, "
                    f"{sql_text('Wrath Remix: ' + name + ', Bazaar')})")
    for guid, entry, points in WALKERS:
        x, y, _ = points[0]
        rows.append(f"({guid}, {entry}, {MAP}, 1, 1, 0, {x:.2f}, {y:.2f}, {z_at(x, y) + 0.1:.2f}, 0, 60, 0, 2, "
                    f"{sql_text('Wrath Remix: ' + PEOPLE[entry][0] + ', Bazaar, on rounds')})")
    w(",\n".join(rows) + ";")
    w("DELETE FROM `creature_addon` WHERE `guid` BETWEEN 7704500 AND 7704599 OR `guid` BETWEEN 7704021 AND 7704031;")
    w("INSERT INTO `creature_addon` (`guid`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES")
    rows = []
    for guid, entry, x, y, o, stand, emote, wander in SPAWNS:
        if stand or emote:
            rows.append(f"({guid}, 0, 0, {stand}, 1, {emote}, 0, '')")
    for guid, entry, points in WALKERS:
        rows.append(f"({guid}, {guid}, 0, 0, 1, 0, 0, '')")
    for entry, (x, y, o, stand, emote) in INSTRUCTORS.items():
        if stand or emote:
            rows.append(f"({7704020 + entry - 770100}, 0, 0, {stand}, 1, {emote}, 0, '')")
    w(",\n".join(rows) + ";")
    w("DELETE FROM `waypoint_data` WHERE `id` BETWEEN 7704500 AND 7704599;")
    w("INSERT INTO `waypoint_data` (`id`, `point`, `position_x`, `position_y`, `position_z`, `orientation`, `delay`, `move_type`) VALUES")
    rows = []
    for guid, entry, points in WALKERS:
        for n, (x, y, pause) in enumerate(points, start=1):
            rows.append(f"({guid}, {n}, {x:.2f}, {y:.2f}, {z_at(x, y) + 0.1:.2f}, NULL, {pause}, 0)")
    w(",\n".join(rows) + ";")
    w("")

    # The lines.
    w("""CREATE TABLE IF NOT EXISTS `remix_bazaar_line` (
  `guid` INT UNSIGNED NOT NULL,
  `line` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `text` VARCHAR(512) NOT NULL,
  `emote` INT UNSIGNED NOT NULL DEFAULT 0,
  `reply_guid` INT UNSIGNED NOT NULL DEFAULT 0,
  `reply` VARCHAR(512) NOT NULL DEFAULT '',
  `reply_emote` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`guid`, `line`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DELETE FROM `remix_bazaar_line`;""")
    w("INSERT INTO `remix_bazaar_line` (`guid`, `line`, `text`, `emote`, `reply_guid`, `reply`, `reply_emote`) VALUES")
    rows = []
    for guid, lines in LINES.items():
        for n, (text, emote, reply_guid, reply, reply_emote) in enumerate(lines):
            rows.append(f"({guid}, {n}, {sql_text(text)}, {emote}, {reply_guid}, {sql_text(reply)}, {reply_emote})")
    w(",\n".join(rows) + ";")

    with open(OUT, "w") as f:
        f.write("\n".join(out) + "\n")
    print(f"{len(PLACED)} things placed, {len(SPAWNS) + len(WALKERS)} people, {sum(len(v) for v in LINES.values())} lines")


if __name__ == "__main__":
    main()
