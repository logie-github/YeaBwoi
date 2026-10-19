#!/usr/bin/env python3
"""Builds the character creation screen's gear button data from the game's own tables:
data/client/Interface/GlueXML/SkyborneGearData.lua (the button, SkyborneGear.lua, reads it). Run it
again when something here changes; its output is committed.

  build_gear.py DBC_DIR WORLD_SQL_DIR

DBC_DIR: the server's dbc folder (the data.zip in warcraft/server-data.env); WORLD_SQL_DIR: the
core's data/sql/base/db_world at the realm's commit (warcraft/server-version.env).

What the button cycles through, for the race and class selected:
  nude      no gear (a leaf icon: INV_Misc_Leaf_01)
  starter   the race and class's starting outfit (CharStartOutfit), its icon the starting chest's
            (the shirt where the class starts with no chest piece)
  champion  the class's top raid set, its icon the set's chest piece. The top raid set is the
            class's own set (every piece for that class alone) with the highest item level, which
            is Wrath's last tier (Tier 10, the heroic 25-player pieces, item level 277); where a
            class has a set for each role at that level, the damage dealers' (Battlegear, then
            Regalia) is taken. CHAMPION_SETS below overrides a class's choice.
"""

import collections
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_data  # noqa: E402  (the DBC reader, the Skyborne races' templates)

MODULE = os.path.dirname(HERE)
OUT = os.path.join(MODULE, "data", "client", "Interface", "GlueXML", "SkyborneGearData.lua")

NUDE_ICON = "INV_Misc_Leaf_01"

# ChrClasses ID -> the class's name here (only for the comments in the file).
CLASSES = {1: "Warrior", 2: "Paladin", 3: "Hunter", 4: "Rogue", 5: "Priest", 6: "Death Knight",
           7: "Shaman", 8: "Mage", 9: "Warlock", 11: "Druid"}
# Race IDs that have a start of their own (CharStartOutfit), in the order a missing one is looked
# for (a race no module gave a start, mod-arac's every race in every class).
RACE_ORDER = (1, 2, 3, 4, 5, 6, 7, 8, 10, 11)
# A class's top raid set where this should differ from the rule (ItemSet ID).
CHAMPION_SETS = {}

# Inventory types the preview model shows (the others, rings, trinkets, bags, ammunition, are
# not on the body).
VISIBLE = {1, 3, 4, 5, 6, 7, 8, 9, 10, 13, 14, 15, 16, 17, 19, 20, 21, 22, 23, 26}
CHEST = (5, 20)
SHIRT = 4
# The slots of a set, the chest piece's two inventory types as one.
SLOT = {5: 5, 20: 5}


def item_template(path):
    text = open(path, encoding="utf8", errors="replace").read()
    ddl = text.split("CREATE TABLE", 1)[1].split(") ENGINE", 1)[0]
    cols = re.findall(r"^\s+`(\w+)`", ddl, re.M)[1:]      # the first is the table's name
    idx = {c: i for i, c in enumerate(cols)}
    want = ("name", "displayid", "InventoryType", "AllowableClass", "ItemLevel", "itemset")

    def split(body):
        out, cur, quoted, i = [], [], False, 0
        while i < len(body):
            c = body[i]
            if quoted:
                if c == "\\":
                    cur.append(body[i:i + 2])
                    i += 2
                    continue
                if c == "'":
                    if body[i + 1:i + 2] == "'":
                        cur.append("''")
                        i += 2
                        continue
                    quoted = False
                cur.append(c)
            elif c == "'":
                quoted = True
                cur.append(c)
            elif c == ",":
                out.append("".join(cur))
                cur = []
            else:
                cur.append(c)
            i += 1
        out.append("".join(cur))
        return out

    items = {}
    for line in text.splitlines():
        if not line.startswith("("):
            continue
        v = split(line.rstrip(",;")[1:-1])
        assert len(v) == len(cols), (len(v), len(cols))
        r = {c: v[idx[c]] for c in want}
        items[int(v[0])] = {"name": r["name"].strip("'"), "inv": int(r["InventoryType"]),
                            "classes": int(r["AllowableClass"]), "level": int(r["ItemLevel"]),
                            "set": int(r["itemset"])}
    return items


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    dbc_dir, world = sys.argv[1], sys.argv[2]
    D = lambda name: build_data.Dbc(os.path.join(dbc_dir, name + ".dbc"))

    # Icons: an item's ItemDisplayInfo row's inventory icon.
    item_dbc, display = D("Item"), D("ItemDisplayInfo")
    icon_of_display = {r[0]: r[5] for r in display.rows}

    def icon(item):
        return display.s(icon_of_display[item_dbc_display[item]])

    item_dbc_display = {r[0]: r[5] for r in item_dbc.rows}

    # Starting outfits: (race, class, sex) -> the items the model shows, in the table's order.
    outfit = D("CharStartOutfit")
    import struct
    starts = {}
    for raw in outfit.raw:
        _, race, klass, sex, _outfit = struct.unpack("<I4B", raw[:8])
        values = struct.unpack("<72i", raw[8:])
        items, types = values[:24], values[48:72]
        if _outfit != 0:
            continue
        shown = [i for i, t in zip(items, types) if i > 0 and t in VISIBLE]
        starts[(race, klass, sex)] = shown

    def chest_icon(items, types_of):
        for kinds in (CHEST, (SHIRT,)):
            for i in items:
                if types_of(i) in kinds:
                    return icon(i)
        return icon(items[0]) if items else NUDE_ICON

    type_of_dbc = {r[0]: r[6] for r in item_dbc.rows}
    types_of = lambda i: type_of_dbc[i]

    # The Skyborne's two races take their template race's start for each class they can be.
    for skyborne, classes in build_data.CLASSES.items():
        for klass in classes:
            for sex in (0, 1):
                starts[(skyborne, klass, sex)] = starts[(build_data.TEMPLATE[skyborne][klass], klass, sex)]

    start = collections.OrderedDict()
    for (race, klass, sex), items in sorted(starts.items()):
        start.setdefault(race, {}).setdefault(klass, {})[sex] = items
    fallback = {}
    for klass in CLASSES:
        for race in RACE_ORDER:
            if klass in start.get(race, {}):
                fallback[klass] = start[race][klass]
                break
        else:
            raise SystemExit("no start for class %d" % klass)

    # The champion sets.
    items = item_template(os.path.join(world, "item_template.sql"))
    set_names = D("ItemSet")
    names = {r[0]: set_names.s(r[1]) for r in set_names.rows}
    by_set = collections.defaultdict(list)
    for entry, it in items.items():
        if it["set"]:
            by_set[it["set"]].append(entry)
    champion = {}
    for klass in CLASSES:
        bit = 1 << (klass - 1)
        candidates = []
        for set_id, members in by_set.items():
            if all(items[m]["classes"] == bit for m in members):
                top = max(items[m]["level"] for m in members)
                name = names.get(set_id, "")
                role = 0 if name.endswith("Battlegear") else 1 if name.endswith("Regalia") else 2
                candidates.append((-top, role, set_id))
        candidates.sort()
        chosen = CHAMPION_SETS.get(klass, candidates[0][2])
        members = by_set[chosen]
        best = {}
        for m in sorted(members):
            it = items[m]
            if it["inv"] not in VISIBLE:
                continue
            slot = SLOT.get(it["inv"], it["inv"])
            if slot not in best or it["level"] > items[best[slot]]["level"]:
                best[slot] = m
        pieces = [best[s] for s in sorted(best)]
        champion[klass] = {"set": chosen, "name": names[chosen], "items": pieces,
                           "icon": chest_icon(pieces, types_of),
                           "level": max(items[m]["level"] for m in pieces)}

    # The file.
    lua = ["-- SPDX-License-Identifier: GPL-3.0-or-later",
           "-- The gear button's data (SkyborneGear.lua): generated by tools/build_gear.py from the game's",
           "-- own tables; edit that, not this.",
           "SKYBORNE_GEAR = {};",
           'SKYBORNE_GEAR.nude = "%s";' % NUDE_ICON,
           "-- Starting outfits: [race][class] = { icon, male items, female items (when they differ) }.",
           "SKYBORNE_GEAR.start = {"]

    def entry(per_sex):
        male, female = per_sex.get(0, []), per_sex.get(1, per_sex.get(0, []))
        text = '{ "%s", {%s}' % (chest_icon(male, types_of), ", ".join(map(str, male)))
        if female != male:
            text += ", {%s}" % ", ".join(map(str, female))
        return text + " }"

    for race in start:
        lua.append("\t[%d] = {" % race)
        for klass in sorted(start[race]):
            lua.append("\t\t[%d] = %s, -- %s" % (klass, entry(start[race][klass]), CLASSES[klass]))
        lua.append("\t},")
    lua.append("};")
    lua.append("-- A race with no start of its own for a class (a module's race, a class it can't be) takes this.")
    lua.append("SKYBORNE_GEAR.fallback = {")
    for klass in sorted(fallback):
        lua.append("\t[%d] = %s, -- %s" % (klass, entry(fallback[klass]), CLASSES[klass]))
    lua.append("};")
    lua.append("-- The top raid sets: [class] = { icon, set name, items }.")
    lua.append("SKYBORNE_GEAR.champion = {")
    for klass in sorted(champion):
        c = champion[klass]
        lua.append('\t[%d] = { "%s", "%s", {%s} }, -- %s, item level %d' % (
            klass, c["icon"], c["name"].replace('"', '\\"'), ", ".join(map(str, c["items"])), CLASSES[klass], c["level"]))
    lua.append("};")
    with open(OUT, "w", newline="\n") as f:
        f.write("\n".join(lua) + "\n")
    for klass in sorted(champion):
        c = champion[klass]
        print("%-13s %-28s %s %s" % (CLASSES[klass], c["name"], c["icon"], c["items"]))


if __name__ == "__main__":
    main()
