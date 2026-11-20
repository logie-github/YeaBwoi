#!/usr/bin/env python3
"""Makes android/app/src/main/assets/characters.json: the named characters' variants (characters/CharacterRoster.kt), from
the game's tables (wago.tools' CSVs at the app's pinned build, as tools/creature_probe.py fetches them).

    python3 -I tools/characters.py TABLES_DIR [OUT_JSON]      writes the roster (default: the app's assets)
    python3 -I tools/characters.py TABLES_DIR --candidates    lists every Creature row of each character and its display

Only ids, names and choice ids go into the JSON: nothing of the game's files.

Each character has:
- outfit variants: a display of the game. A humanoid display (CreatureDisplayInfoExtra: a playable race's model) is
  written as its race, sex, kept choices (CreatureDisplayInfoOption) and worn items (NPCModelItemSlotDisplayInfo, by
  equipment slot), drawn through the creator's resolver and transmog; a display of a model of its own (creature/sylvanas,
  creature/jaina3...) is written as the display, drawn as the game's NPC is.
- creator presets ("presets"): a body variant of a playable race offered by the creator's Transmog panel as a named
  outfit that also sets the character's own choices for that race and sex (characters/CreatorPresets.kt).
- undressed variants (underwear: the creator's No Gear; bare: also without the skin colour's underwear layers): her own
  choices on the playable race's body. For a character the game draws with a model of its own, a stand-in: the closest
  playable race and the choices named below by hand (approximate; said in the variant's note).
"""
import csv
import json
import os
import sys

# Only models of Battle for Azeroth (8.x) or later are used (the user's rule). A display's era is its MODEL's: the model
# file's id (FileDataIDs grow with the game: about 1.6-1.7M at 7.3.5/8.0, 3.0M at 8.3, 3.2-4.2M in Shadowlands, 4.2M+ from
# Dragonflight, 5M+ The War Within, 6.7M+ Midnight) checked against Shadowlands' last build (9.2.7.45745): a display
# whose model data pointed to another, older file then was remodelled later (Chromie's 10008, Alexstrasza's 28227). A
# playable race's model (the Forsaken's and the Void Elf's) is the creator's own body, the same every woman of that race
# gets; its era is the display's. Dropped as older: Sylvanas 28213 and 86014 (creature/sylvanas, 1245874: Legion), Calia
# 67797 (a Legion display), Lilian Voss 67721 (Legion), Jaina 24952 (Wrath), Chromie 78231 (Legion), Alleria 76272,
# 86013 (creature/alleria, 1624825: 7.3) and 114447 (alleriavoid, 1668960: 7.3). A character with no model of BfA or later
# (Whitemane) keeps her newest display: the one the latest creature of her uses in the current game.
CHARACTERS = [
    {
        "id": "sylvanas", "name": "Sylvanas Windrunner",
        "outfits": [
            (95032, "shadowlands", "Shadowlands (creature/sylvanasshadowlands)", "Shadowlands 9.0: model file 3221727"),
            (102133, "shadowlands2", "Shadowlands, Sanctum (sylvanasshadowlands2)", "Shadowlands 9.1: model file 4201178"),
            (102134, "shadowlands3", "Shadowlands, Sepulcher (sylvanasshadowlands3)", "Shadowlands 9.1 to 9.2: model file 4214530"),
        ],
        "midnight": "does not exist: no Sylvanas creature or display after Shadowlands in the tables",
        # stand-in: a Void Elf woman (an elf's body with the blue-grey skins her undead look needs; no Blood Elf skin has it),
        # pale blue-grey skin, ash-blond hair, long ears
        "standin": {"race": 29, "picks": {"Skin Color": 2870, "Hair Style": 2886, "Hair Color": 19171, "Ears": 19180}},
    },
    {
        "id": "calia", "name": "Calia Menethil",
        "outfits": [
            (92900, "shadowlands", "The Pallid Lady (creature/caliamenethil)", "Battle for Azeroth 8.3 model (file 2997555), worn through Shadowlands"),
        ],
        "midnight": "does not exist as a new display: the later creatures (249460) reuse 92900",
        # stand-in: a Human woman (the Pallid Lady's own model has no choices): the palest grey skin, white hair
        "standin": {"race": 1, "picks": {"Skin Color": 97, "Hair Style": 136, "Hair Color": 165}},
    },
    {
        "id": "lilian", "name": "Lilian Voss",
        "outfits": [
            (85799, "bfa", "Battle for Azeroth (Forsaken), worn through Shadowlands (Desolate Council)", "Battle for Azeroth display 85799 on the Forsaken model"),
        ],
        "midnight": "does not exist: no Lilian Voss creature of Midnight's ids",
        "identity": 85799,
    },
    {
        "id": "jaina", "name": "Jaina Proudmoore",
        "outfits": [
            (88316, "bfa", "Lord Admiral (creature/jaina3)", "Battle for Azeroth model (file 1717164, first used by 8.0's displays); reused by later creatures"),
            (104817, "shadowlands", "Shadowlands (creature/jaina3)", "Shadowlands 9.1 to 9.2 display of the same model"),
        ],
        "midnight": "does not exist as a new display: Midnight-era creatures reuse 87892 and 88316",
        # stand-in: a Human woman (jaina3 has no choices): fair skin, white hair in a high tail
        "standin": {"race": 1, "picks": {"Skin Color": 85, "Hair Style": 140, "Hair Color": 165}},
    },
    {
        "id": "alexstrasza", "name": "Alexstrasza",
        "outfits": [
            (28227, "visage", "Visage (creature/ladyalexstrasza)", "Dragonflight model: display 28227 drew file 234501 in 9.2.7, the remodel 4218362 now"),
            (108614, "dragonflight", "Dragonflight cinematic visage (creature/igc_alexstrasza)", "Dragonflight: model file 4569783"),
        ],
        "shadowlands": "does not exist: no Alexstrasza display of Shadowlands' ids",
        "midnight": "not humanoid: creature 268929 is display 148181, a proto-dragon (CompanionProtoDragon) form",
        # stand-in: a Blood Elf woman, fair skin, red hair, golden eyes, curved horns
        "standin": {"race": 10, "picks": {"Skin Color": 1734, "Hair Style": 1782, "Hair Color": 1803, "Eye Color": 6591, "Horns": 1854}},
    },
    {
        "id": "chromie", "name": "Chromie",
        "outfits": [
            (10008, "timewalker", "Timewalker (creature/chromie)", "Dragonflight model: display 10008 drew the gnome player model in 9.2.7, creature/chromie (4218359) now"),
        ],
        "shadowlands": "does not exist",
        "midnight": "does not exist as a new display: creature 248051 reuses 10008",
        # no undressed variant: her only humanoid form is a gnome's (a child-sized body)
        "undressed": False,
        # the creator's preset (the user: "adding Chromie's hair and face and outfit to the character creator"): her Legion
        # gnome display (creature 124154) on the gnome woman's own model, its choices (all the player's own: face, skin,
        # hair, eyes) and worn items, offered for a gnome woman as the Transmog outfit "Chromie". The era rule is about the
        # model drawn: here it is the creator's own current gnome body. Her Timewalker display 10008 and the later ones
        # (24877, 96559, 109398) are creature/chromie with no choices or items; 107733 and 107134 are a Highland Drake's.
        "presets": [(78231, "gnome", "Chromie", "her choices and worn items from display 78231 (creature 124154)")],
        # her own hair colour in place of the display's pale ash (1279): the creator's own choice "Chromie", sampled from her
        # model's hair (creator/CustomChoices.kt: CHROMIE_HAIR, an id of Wifecraft's, never the game's)
        "preset_custom": {"Hair Color": -70001},
    },
    {
        "id": "valeera", "name": "Valeera Sanguinar",
        "outfits": [
            (26365, "current", "Current (creature/valeera)", "Battle for Azeroth 8.3 model (file 3049179); the display her newest creatures (268807, 274915: Midnight's ids) use"),
        ],
        "shadowlands": "does not exist as a new display",
        "midnight": "her Midnight-era creatures (268807, 274915) use 26365, the variant above",
        "standin": {"race": 10, "picks": {"Skin Color": 1733, "Hair Style": 1781, "Hair Color": 1801, "Eye Color": 6587}},
    },
    {
        "id": "alleria", "name": "Alleria Windrunner",
        "outfits": [
            (118072, "warwithin", "The War Within (creature/alleria3)", "The War Within: model file 5395590"),
            (141483, "midnight", "Midnight, void-touched (creature/alleria3void)", "Midnight: model file 6718343, creature 259399"),
        ],
        "shadowlands": "does not exist as a new display: creature 175138 reuses 78345 (Legion)",
        "standin": {"race": 10, "picks": {"Skin Color": 1733, "Hair Style": 1782, "Hair Color": 1801, "Eye Color": 6602}},
    },
    {
        "id": "whitemane", "name": "Sally Whitemane",
        "outfits": [
            (2043, "scarlet", "Scarlet Crusade (High Inquisitor, Scarlet Monastery)", "classic display 2043 (npc 3977), on the human woman's current model"),
            (71257, "deathknight", "Death knight (human)", "Legion display, her newest: Shadowlands-era creature 161708 uses it; no newer model exists"),
        ],
        # Her Scarlet Crusade look: display 2043, classic High Inquisitor Whitemane (npc 3977, Scarlet Monastery). Creature has
        # no 3977 row and wowhead's page gave no display id to a plain fetch (its model viewer is filled by script); 2043 is
        # the classic display as remembered, and the tables bear it out: a human woman (extra 655) at the boss's scale 1.6,
        # the hair style of her death knight display (134), white hair, eight items. The era rule doesn't apply to it (the
        # user asked for this outfit). Kept as a fallback should 2043 not resolve: "assembled" below, unused.
        "assembled_fallback": [
            ("scarlet", "Scarlet Crusade (assembled)", 71257, {"Skin Color": 85}, {
                3: (8814, "Mantle of the Scarlet Crusade, item 22405"),
                5: (194791, "Scarlet Zealot's Cassock, item 190444"),
                6: (8827, "Scarlet Belt, item 10329"),
                8: (6143, "Scarlet Boots, item 10332"),
                9: (5322, "Scarlet Wristguards, item 10333"),
                10: (7732, "Scarlet Gauntlets, item 10331"),
                15: (194790, "Scarlet Zealot's Cloak, item 190427"),
                19: (692029, "Tabard of the Scarlet Crusade, item 23192"),
            }),
        ],
        "identity": 2043,
        # a tabard variant wearing another tabard than the outfit's (base outfit, key, label, tabard ItemDisplayInfo, note): her
        # display's own tabard 15983 is an NPC-only piece painting her whole bodice (red gown, studded corset, gold panel) on
        # the torso; the game's Tabard of the Scarlet Crusade (item 23192: ItemModifiedAppearance 9122 -> ItemAppearance
        # 31731 -> ItemDisplayInfo 692029) is the plain cloth tabard players wear
        "tabards": [("scarlet", "scarlet_tabard_crusade", "Scarlet Crusade, Tabard of the Scarlet Crusade only", 692029,
                     "item 23192 (ItemDisplayInfo 692029)")],
        # her display's skin (95, an NPC-only skin) has no underwear layers: the undressed variants take the death knight
        # pallor (97, the pale grey of the death knight skins), which has them; face, hair, eyes and markings stay hers
        "override": {"Skin Color": 97},
    },
    {
        "id": "xalatath", "name": "Xal'atath",
        "outfits": [
            (88826, "bfa", "Battle for Azeroth (void elf)", "Battle for Azeroth display 88826 on the Void Elf model"),
            (117121, "warwithin", "The Harbinger (creature/xalatath)", "The War Within: model file 5159886"),
        ],
        "shadowlands": "does not exist",
        "midnight": "not humanoid: creature 262994 is display 143465, the dagger",
        "identity": 88826,
    },
]


def read(tables, name):
    with open(os.path.join(tables, name + ".csv"), newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


RACE_NAMES = {1: "Human", 10: "Blood Elf", 29: "Void Elf"}

# A nude variant ("<outfit>_nude", kind "nude"; characters/CharacterLooks.kt NUDE_REMOVED): the outfit's own items without
# its torso, leg and feet pieces (equipment slots: shirt 4, chest or robe 5, belt 6, legs 7, feet 8, tabard 19) over the
# bare body (the skin colour's underwear layers left out, as "bare"); the head piece, shoulders, wrists, gloves and cloak
# stay. Only an outfit of a playable race's body (a humanoid display: its items are NPCModelItemSlotDisplayInfo's) has one.
# A display of a model of its own (creature/sylvanasshadowlands, jaina3...) has its armour in the model and no item list
# (no ExtendedDisplayInfoID, nor any of its creatures' other displays): nothing comes off, so it has none. Never a gnome
# woman's (the user: Chromie keeps her outfit).
NUDE_REMOVED = {4, 5, 6, 7, 8, 19}


def nude(v):
    if v["kind"] != "outfit" or "race" not in v or (v["race"] == 7 and v["sex"] == "FEMALE"):
        return None
    eq = {k: x for k, x in v["equipment"].items()}
    if not any(int(k) in NUDE_REMOVED for k in eq):
        return None
    n = dict(v)
    n.update(key=v["key"] + "_nude", label=v["label"] + ", nude", kind="nude",
             equipment={k: x for k, x in eq.items() if int(k) not in NUDE_REMOVED},
             note="display %d's items without the shirt, chest, tabard, belt, legs and feet, over the bare body" % v["sourceDisplay"])
    return n


# A tabard variant ("<outfit>_tabard", kind "tabard"; characters/CharacterLooks.kt TABARD_REMOVED): the outfit's own items
# without the shirt 4, chest or robe 5 and legs 7, the tabard 19 kept with everything else (head piece, shoulders, belt,
# boots, wrists, gloves, cloak), over the bare body as "nude". Only an outfit of a playable race's body that wears a tabard
# has one (of the roster's: Whitemane's Scarlet display 2043; Lilian's 85799, Whitemane's death knight 71257 and
# Xal'atath's 88826 list none), and a character's "tabards" another tabard on an outfit's (Whitemane's Scarlet look in the
# game's Tabard of the Scarlet Crusade); a display of a model of its own has its armour in the model, nothing to take off. Never a
# gnome woman's.
TABARD_REMOVED = {4, 5, 7}


def tabard(v):
    if v["kind"] != "outfit" or "race" not in v or (v["race"] == 7 and v["sex"] == "FEMALE"):
        return None
    eq = v["equipment"]
    if "19" not in eq:
        return None
    n = dict(v)
    n.update(key=v["key"] + "_tabard", label=v["label"] + ", tabard only", kind="tabard",
             equipment={k: x for k, x in eq.items() if int(k) not in TABARD_REMOVED},
             note="display %d's items without the shirt, chest and legs, the tabard kept, over the bare body" % v["sourceDisplay"])
    return n


NPC_SLOT = {0: 1, 1: 3, 2: 4, 3: 5, 4: 6, 5: 7, 6: 8, 7: 9, 8: 10, 9: 19, 10: 15}


class Tables:
    def __init__(self, d):
        self.disp = {int(r["ID"]): r for r in read(d, "CreatureDisplayInfo")}
        self.extra = {int(r["ID"]): r for r in read(d, "CreatureDisplayInfoExtra")}
        self.opts, self.slots = {}, {}
        for r in read(d, "CreatureDisplayInfoOption"):
            self.opts.setdefault(int(r["CreatureDisplayInfoExtraID"]), {})[int(r["ChrCustomizationOptionID"])] = int(r["ChrCustomizationChoiceID"])
        for r in read(d, "NPCModelItemSlotDisplayInfo"):
            s = NPC_SLOT.get(int(r["ItemSlot"]))
            if s and int(r["ItemDisplayInfoID"]):
                self.slots.setdefault(int(r["NpcModelID"]), {})[s] = int(r["ItemDisplayInfoID"])
        chr_models = {int(r["ID"]): r for r in read(d, "ChrModel")}
        self.model_of = {}
        for r in read(d, "ChrRaceXChrModel"):
            m = chr_models.get(int(r["ChrModelID"]))
            if m and m["Sex"] in ("0", "1"):
                self.model_of[(int(r["ChrRacesID"]), int(m["Sex"]))] = int(m["ID"])
        self.options = {}
        for r in read(d, "ChrCustomizationOption"):
            self.options.setdefault(int(r["ChrModelID"]), {})[r["Name_lang"]] = int(r["ID"])
        self.choice_option = {int(r["ID"]): int(r["ChrCustomizationOptionID"]) for r in read(d, "ChrCustomizationChoice")}
        self.creatures = read(d, "Creature")

    def parts(self, display):
        e = int(self.disp[display]["ExtendedDisplayInfoID"] or 0)
        if not e or e not in self.extra:
            return None
        x = self.extra[e]
        return int(x["DisplayRaceID"]), int(x["DisplaySexID"]), self.opts.get(e, {}), self.slots.get(e, {})

    def by_name(self, race, sex, named):
        """{option name: choice id} to {option id: choice id}, checked against the race's own options."""
        own = self.options.get(self.model_of[(race, sex)], {})
        out = {}
        for name, choice in named.items():
            if name not in own:
                raise SystemExit(f"race {race}: no option {name!r}")
            if self.choice_option.get(choice) != own[name]:
                raise SystemExit(f"race {race}: choice {choice} isn't {name!r}'s")
            out[own[name]] = choice
        return out

    def creatures_of(self, display):
        return sorted(int(c["ID"]) for c in self.creatures if display in [int(c[f"DisplayID_{i}"] or 0) for i in range(4)])


def build(t):
    out = []
    for c in CHARACTERS:
        variants = []
        for display, key, label, era in c["outfits"]:
            p = t.parts(display)
            v = {"key": key, "label": label, "kind": "outfit", "sourceDisplay": display,
                 "sourceCreatures": t.creatures_of(display)[:12], "era": era}
            if p:
                race, sex, picks, slots = p
                v.update(race=race, sex="FEMALE" if sex == 1 else "MALE", picks={str(k): x for k, x in sorted(picks.items())},
                         body={"shape": 0, "style": 0, "density": 0}, equipment={str(k): x for k, x in sorted(slots.items())})
            else:
                v["display"] = display
            variants.append(v)
        for key, label, base, named, items in c.get("assembled", []):
            race, sex, picks, _ = t.parts(base)
            picks = dict(picks)
            picks.update(t.by_name(race, sex, named))
            variants.insert(0, {"key": key, "label": label, "kind": "outfit", "race": race, "sex": "FEMALE" if sex == 1 else "MALE",
                                "picks": {str(k): x for k, x in sorted(picks.items())}, "body": {"shape": 0, "style": 0, "density": 0},
                                "equipment": {str(k): v[0] for k, v in sorted(items.items())}, "sourceDisplay": base,
                                "era": "assembled: not a display of the game",
                                "note": "assembled from her choices (display %d) and the items: " % base + "; ".join(v[1] for _, v in sorted(items.items()))})
        outfits = list(variants)
        variants += [n for n in map(nude, outfits) if n]
        variants += [n for n in map(tabard, outfits) if n]
        for base, key, label, item, what in c.get("tabards", []):
            n = tabard(next(v for v in outfits if v["key"] == base))
            n.update(key=key, label=label, equipment=dict(sorted({**n["equipment"], "19": item}.items(), key=lambda kv: int(kv[0]))),
                     note="display %d's items without the shirt, chest and legs, wearing the tabard %s in place of its own, over "
                          "the bare body" % (n["sourceDisplay"], what))
            variants.append(n)
        if c.get("undressed", True):
            if "identity" in c:
                race, sex, picks, _ = t.parts(c["identity"])
                picks = dict(picks)
                picks.update(t.by_name(race, sex, c.get("override", {})))
                src, note = c["identity"], "her own choices from display %d" % c["identity"]
                if c.get("override"):
                    note += "; " + ", ".join(c["override"]) + " set by hand"
            else:
                s = c["standin"]
                race, sex = s["race"], 1
                picks = t.by_name(race, sex, s["picks"])
                src = c["outfits"][-1][0]
                note = "stand-in: the game draws her with a model of her own; a %s woman with choices picked by hand to resemble her" % RACE_NAMES[race]
            for kind, label in (("underwear", "Underwear (No Gear)"), ("bare", "Bare (no underwear layers)")):
                variants.append({"key": kind, "label": label, "kind": kind, "race": race, "sex": "FEMALE" if sex == 1 else "MALE",
                                 "picks": {str(k): x for k, x in sorted(picks.items())}, "body": {"shape": 0, "style": 0, "density": 0},
                                 "equipment": "nude", "sourceDisplay": src, "note": note})
        entry = {"id": c["id"], "name": c["name"], "variants": variants}
        presets = []
        for display, key, label, note in c.get("presets", []):
            race, sex, picks, slots = t.parts(display)
            picks = dict(picks)
            own = t.options.get(t.model_of[(race, sex)], {})
            for name, choice in c.get("preset_custom", {}).items():
                picks[own[name]] = choice
            presets.append({"key": key, "label": label, "kind": "outfit", "race": race, "sex": "FEMALE" if sex == 1 else "MALE",
                            "picks": {str(k): x for k, x in sorted(picks.items())}, "body": {"shape": 0, "style": 0, "density": 0},
                            "equipment": {str(k): x for k, x in sorted(slots.items())}, "sourceDisplay": display,
                            "sourceCreatures": t.creatures_of(display)[:12], "note": note})
        if presets:
            entry["presets"] = presets
        out.append(entry)
    return {"characters": out}


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    t = Tables(argv[1])
    if len(argv) > 2 and argv[2] == "--candidates":
        for c in CHARACTERS:
            for display, key, *_ in c["outfits"]:
                print(c["id"], key, display, t.parts(display) and t.parts(display)[:2], t.creatures_of(display)[:8])
        return 0
    here = os.path.dirname(os.path.abspath(__file__))
    path = argv[2] if len(argv) > 2 else os.path.join(here, "..", "android", "app", "src", "main", "assets", "characters.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(build(t), f, indent=1)
        f.write("\n")
    print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
