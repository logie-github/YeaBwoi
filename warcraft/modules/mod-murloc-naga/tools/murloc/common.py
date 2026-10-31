# SPDX-License-Identifier: GPL-3.0-or-later
"""The murlocs' questline: ids, and the names a chapter may use for looks, models, icons, emotes,
sounds, spell visuals and languages (tools/murloc/SCHEMA.md). Data only."""

MURLOC_RACE, MURLOC_BIT = 19, 1 << 18
MAP_KALIMDOR, MAP_EASTERN = 1, 0

# Ids: creatures 976000-977999, objects 978000-978499, quests 978500-978999, items 979000-979799;
# gossip menus and texts 976000 on (their own tables); spawns 9760000 on, objects' 9790000 on;
# object looks (GameObjectDisplayInfo) 9800-9999; story phases' spells 979800-979899; trainers 976000 on.
NPC_BASE, NPC_END = 976000, 977999
GO_BASE, GO_END = 978000, 978499
QUEST_BASE, QUEST_END = 978500, 978999
ITEM_BASE, ITEM_END = 979000, 979799
TEXT_BASE = 976000
SPAWN_BASE, SPAWN_END = 9760000, 9789999
GO_SPAWN_BASE, GO_SPAWN_END = 9790000, 9799999
GO_DISPLAY_BASE, GO_DISPLAY_END = 9800, 9999
PHASE_SPELL_BASE = 979800
TRAINER_BASE = 976000

# The race's own phase (the world's and theirs): spell_area puts it on a murloc in every zone the
# story goes to. Story phases (an NPC there from a quest on, until one, or while one's in the log)
# take bits of their own, zone by zone.
RACE_PHASE = 1024
STORY_PHASE_BITS = [1 << b for b in range(12, 31) if (1 << b) not in (32768, 65536, 131072)]

# Who people are to a murloc: FactionTemplate ids.
SIDES = {
    "tribe": 35,        # friendly to everyone (the tribe's phase is murlocs' alone)
    "naga": 74,         # the naga (hostile)
    "hostile": 14,      # monsters
    "neutral": 7,       # creatures, yellow
}

# Looks (CreatureDisplayInfo ids) by name.
LOOKS = {
    # murlocs
    "oracle": 4920, "tidehunter": 478, "seer": 3940, "coastrunner": 3619, "netter": 2597,
    "warrior": 540, "hunter": 652, "raider": 346, "flagglemurk": 1305, "murkdeep": 1079,
    "fry_green": 15394, "fry_blue": 15393, "fry_pink": 15396, "fry_purple": 15397,
    # naga
    "naga_male": 4036, "siren": 4980, "sorceress": 4973, "naga_warrior": 11269, "naga_lord": 4762,
    "sea_witch": 4981, "myrmidon": 6747,
    # others
    "troll_scout": 31737, "trigger": 11686,
}
# The tribe's people: murloc2 (the race's own murloc) with emotes, in each skin (tools/build_data.py's
# ID_TRIBE_DISPLAY on): talk, point, cheer, bow, cry, wave, laugh, beg, applaud... all play on them.
TRIBE_SKINS = ["grey", "blue", "green", "pale", "orange", "gold", "fel", "red", "purple", "slate", "abyssal", "bronze", "ice"]
LOOKS.update({f"murloc_{name}": 33103 + n for n, name in enumerate(TRIBE_SKINS)})

# Object models: a GameObjectDisplayInfo id, or a game model path (a look of its own is made).
MODELS = {
    "murloc_hut": "World\\AZEROTH\\SWAMPOSORROW\\PASSIVEDOODADS\\WATERHUTS\\WaterHut01.mdx",
    "murloc_hut2": "World\\AZEROTH\\SWAMPOSORROW\\PASSIVEDOODADS\\WATERHUTS\\WaterHut02.mdx",
    "great_pearl": "World\\GENERIC\\Murloc\\Passive Doodads\\Pearl\\GreatPearl_01.mdx",
    "murloc_altar": "World\\GENERIC\\Murloc\\Passive Doodads\\Altars\\MurlocAltar_01.mdx",
    "naga_pagoda": "World\\EXPANSION01\\DOODADS\\GENERIC\\NAGA\\Pagoda\\NagaPagodaHut01.mdx",
    "naga_banner": "World\\EXPANSION01\\DOODADS\\GENERIC\\NAGA\\Banner\\NA_Banner01.mdx",
    "naga_torch": "World\\EXPANSION01\\DOODADS\\GENERIC\\NAGA\\TORCH\\NA_Torch01.mdx",
    "naga_crate": "World\\EXPANSION01\\DOODADS\\GENERIC\\NAGA\\Crates\\NA_Crate01.mdx",
    "naga_weapons": "World\\EXPANSION01\\DOODADS\\GENERIC\\NAGA\\WeaponStacks\\NA_WeaponStack01.mdx",
    "naga_jar": "World\\EXPANSION01\\DOODADS\\GENERIC\\NAGA\\Jars\\NA_Jar01.mdx",
    # the game's object looks
    "crate": 2, "chest": 10, "barrel": 259, "basket": 10, "kelp": 267, "clam": 261, "coral": 887,
    "sack": 32, "cage": 3854, "bonfire": 192,
}

# Item icons (ItemDisplayInfo ids, or "item:<entry>" for an item of the game's look).
ICONS = {
    "kelp": 18089, "coral": 6652, "pearl": 12310, "mussel": 22193, "trinket": 12410, "bread": 21203,
    "letter": 3093, "scroll": 3093, "trident": 8747, "lures": 7914, "fish": 24702, "shell": 22193,
    "key": 7737, "bones": 7140, "eye": 7140, "orders": 3093, "spear": 8747,
}

# Emotes (Emotes.dbc): one-off by name.
EMOTES = {
    "talk": 1, "bow": 2, "wave": 3, "cheer": 4, "exclamation": 5, "question": 6, "eat": 7, "laugh": 11,
    "rude": 14, "roar": 15, "kneel": 16, "kiss": 17, "cry": 18, "chicken": 19, "beg": 20, "applaud": 21,
    "shout": 22, "flex": 23, "shy": 24, "point": 25, "wound": 33, "attack": 36, "attack2h": 37,
    "parry": 39, "spell": 51, "battle_roar": 53, "kick": 60, "throw": 61, "salute": 66, "yes": 273,
    "no": 274, "train": 275, "laugh_nosheathe": 153, "eat_nosheathe": 92, "point_nosheathe": 29,
}
# States (Emotes.dbc's EMOTE_STATE_*): kept until changed.
STATES = {
    "none": 0, "dance": 10, "sleep": 12, "sit": 13, "stun": 64, "dead": 65, "kneel": 68,
    "use": 69, "work": 173, "ready": 27, "mine": 233, "chop": 234, "fish": 379, "cower": 431,
    "read": 483, "eat": 422, "talk": 469,
}

# Sounds (SoundEntries ids) by name (more, with every id: the research's effects.md; any id works).
SOUNDS = {
    # murlocs
    "murloc_aggro": 416, "murloc_alert": 1109, "murloc_fidget": 414, "murloc_fidget2": 415, "murloc_attack": 417,
    "murloc_wound": 418, "murloc_wound_crit": 419, "murloc_death": 420, "murloc_aggro_old": 11802, "murloc_preaggro": 15995,
    "baby_murloc": 8475, "baby_murloc_dance": 8485, "marine_murloc_click": 15933,
    # naga
    "naga_attack": 3433, "naga_wound": 3434, "naga_death": 3436, "naga_aggro": 3438, "naga_hiss": 3437,
    "siren_attack": 4020, "siren_death": 4023, "siren_aggro": 4025, "siren_hiss": 4024,
    "naga_lord_aggro": 11695, "naga_lord_hiss": 11694, "naga_lord_death": 11693,
    # water
    "splash_small": 1090, "splash": 1096, "splash_big": 1097, "thud_water": 1267, "drowning": 5736,
    "pickup_water": 1197, "water_elemental": 10012,
    # places
    "ocean": 1114, "beach_day": 4164, "beach_night": 4165, "ship": 4215, "ship_docked": 5154, "sail": 5454,
    "dock_bell": 5495, "underwater": 4123, "waterfall": 3791, "stormwind": 4198, "swamp": 4200, "westfall": 4207,
    "tavern": 4394, "tavern_crowded": 7359, "campfire": 3240,
    # guards' voices (hello, goodbye, annoyed)
    "human_guard_pissed": 5973, "human_guard_hello": 5971, "dwarf_guard_pissed": 5912, "sentinel_pissed": 6007,
    "goblin_guard_pissed": 5963, "orc_guard_pissed": 6020, "undead_guard_pissed": 6041, "tauren_guard_pissed": 6010,
}

# Spells for looks alone (cosmetic), by name.
VISUALS = {}

# Languages (Languages.dbc).
LANGUAGES = {
    "universal": 0, "orcish": 1, "darnassian": 2, "taurahe": 3, "dwarvish": 6, "common": 7, "demonic": 8,
    "titan": 9, "thalassian": 10, "draconic": 11, "kalimag": 12, "gnomish": 13, "troll": 14,
    "gutterspeak": 33, "draenei": 35, "naga": 7,
}
# The murlocs' own: the game's Titan, "Nerglish" in the patch (no player race speaks it).
NERGLISH_LANGUAGE = 9

CLASSES = {"warrior": 1, "paladin": 2, "hunter": 3, "rogue": 4, "priest": 5, "shaman": 7, "mage": 8, "warlock": 9, "druid": 11}
SKILLS = {
    "first_aid": 129, "cooking": 185, "fishing": 356, "skinning": 393, "mining": 186, "herbalism": 182,
    "leatherworking": 165, "tailoring": 197, "blacksmithing": 164, "alchemy": 171, "engineering": 202,
    "enchanting": 333, "jewelcrafting": 755, "inscription": 773,
}

# What a vendor sells by default (the game's items): the murlocs' food and drink.
VENDOR_FOOD = [787, 4592, 4593, 4594, 8957, 21552, 159, 1179, 1205, 1708, 1645, 8766]
