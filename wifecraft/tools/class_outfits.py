#!/usr/bin/env python3
"""The Gear switch's class outfits: each class's default clothing (the game's starting outfit) per race and sex, as item
display ids by equipment slot, and the classes each race may be. Writes assets/class_outfits.json. Ids and numbers
only: the items' models and textures are the game's and the app fetches them on the phone by id.

    python3 -I tools/class_outfits.py TABLES_DIR [OUT]

TABLES_DIR  wago.tools' CSVs (fetched into it when missing):
            CharStartOutfit at OUTFIT_BUILD (Mists of Pandaria Classic: the newest client that still has the table;
            retail dropped it): RaceID, ClassID, SexID (0 male, 1 female), ItemID_0..23;
            CharBaseInfo, Item, ItemModifiedAppearance and ItemAppearance at the app's BUILD (creator/GameTables.BUILD):
            which classes each race may be, each item's InventoryType and its look (ItemDisplayInfo id, as the
            Transmog catalog read it: the item's appearance of the lowest modifier).

Only what is worn is kept (shirt, chest or robe, waist, legs, feet, wrists, hands, head, shoulders, back, tabard:
ClothingSlot ids, InventoryType 20 a robe on the chest, 16 a cloak on the back); weapons, bags and food are not clothing.
Classes kept: the eleven with a starting outfit in the table (warrior 1 to druid 11, death knight and monk among them);
demon hunter (12) and evoker (13) have none and are not offered. A race the table has no row for (the allied races, the
newer ones) takes its PROXY race's outfit for the class (the race it was made from); the app falls back further to any
race's outfit for the class and sex, then to the civilian clothes (CIVILIAN_ITEMS: the recruit's shirt, pants and boots).
"""
import csv
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "class_outfits.json")
BUILD = "12.1.7.70892"
OUTFIT_BUILD = "5.5.4.71060"
USER_AGENT = "wifecraft-tools"
CLASSES = {1: "Warrior", 2: "Paladin", 3: "Hunter", 4: "Rogue", 5: "Priest", 6: "Death Knight", 7: "Shaman", 8: "Mage",
           9: "Warlock", 10: "Monk", 11: "Druid"}
# allied and newer races to the race they were made from (their outfit is that race's)
PROXY = {25: 24, 26: 24, 27: 4, 28: 6, 29: 10, 30: 11, 31: 8, 32: 1, 33: 5, 34: 3, 35: 9, 36: 2, 37: 7,
         52: 1, 70: 1, 84: 3, 85: 3, 86: 6, 91: 6}
CIVILIAN_ITEMS = [38, 39, 40]   # Recruit's Shirt, Recruit's Pants, Recruit's Boots
# InventoryType to the equipment slot (creator/Transmog.kt ClothingSlot.fromInventory)
SLOT = {1: 1, 3: 3, 4: 4, 5: 5, 6: 6, 7: 7, 8: 8, 9: 9, 10: 10, 19: 19, 20: 5, 16: 15}


def table(d, name, build):
    path = os.path.join(d, name + ".csv")
    if not os.path.isfile(path):
        url = "https://wago.tools/db2/%s/csv?build=%s" % (name, build)
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=300) as r:
            data = r.read()
        with open(path, "wb") as f:
            f.write(data)
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def build(d):
    inv = {int(r["ID"]): int(r["InventoryType"]) for r in table(d, "Item", BUILD)}
    looks = {int(r["ID"]): int(r["ItemDisplayInfoID"]) for r in table(d, "ItemAppearance", BUILD)}
    display = {}
    best = {}
    for r in table(d, "ItemModifiedAppearance", BUILD):
        item, mod = int(r["ItemID"]), int(r["ItemAppearanceModifierID"])
        look = looks.get(int(r["ItemAppearanceID"]), 0)
        if look > 0 and mod < best.get(item, 1 << 30):
            best[item] = mod
            display[item] = look

    def outfit(items):
        out = {}
        for item in items:
            slot = SLOT.get(inv.get(item, 0))
            if slot is None or item not in display or slot in out:
                continue
            out[slot] = display[item]
        return {str(k): out[k] for k in sorted(out)}

    outfits = {}
    for r in table(d, "CharStartOutfit", OUTFIT_BUILD):
        race, cls, sex = int(r["RaceID"]), int(r["ClassID"]), int(r["SexID"])
        if cls not in CLASSES:
            continue
        o = outfit([int(r["ItemID_%d" % i]) for i in range(24) if int(r["ItemID_%d" % i] or 0) > 0])
        if o:
            outfits["%d:%d:%s" % (race, cls, "F" if sex == 1 else "M")] = o
    classes = {}
    for r in table(d, "CharBaseInfo", BUILD):
        race, cls = int(r["RaceID"]), int(r["ClassID"])
        if cls in CLASSES:
            classes.setdefault(race, set()).add(cls)
    return {
        "source": "CharStartOutfit %s; CharBaseInfo, Item, ItemModifiedAppearance, ItemAppearance %s" % (OUTFIT_BUILD, BUILD),
        "classes": [{"id": k, "name": v} for k, v in sorted(CLASSES.items())],
        "races": {str(k): sorted(v) for k, v in sorted(classes.items())},
        "proxy": {str(k): v for k, v in sorted(PROXY.items())},
        "civilian": outfit(CIVILIAN_ITEMS),
        "outfits": dict(sorted(outfits.items(), key=lambda kv: [int(x) if x.isdigit() else x for x in kv[0].split(":")])),
    }


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    d = sys.argv[1]
    os.makedirs(d, exist_ok=True)
    data = build(d)
    out = sys.argv[2] if len(sys.argv) > 2 else OUT
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
        f.write("\n")
    missing = [(race, c) for race, cs in data["races"].items() for c in cs
               if not any(("%s:%d:%s" % (r, c, s)) in data["outfits"] for r in (race, str(PROXY.get(int(race), 0))) for s in "MF")]
    print("outfits %d, races %d, civilian %s; race/class with no row of its own or its proxy's: %s"
          % (len(data["outfits"]), len(data["races"]), data["civilian"], missing))


if __name__ == "__main__":
    main()
