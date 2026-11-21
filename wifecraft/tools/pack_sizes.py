#!/usr/bin/env python3
"""Counts what Options' "Download everything" fetches, pack by pack, and writes the estimates into
assets/files.json ("estimates": {pack: {"files": n, "bytes": b}}): the app shows them until a pack is on
the phone (wago.tools sends no sizes ahead, so a pack's exact size is known only once it is here).

    python3 tools/pack_sizes.py TABLES_DIR

TABLES_DIR holds the game's tables as CSV at the app's build (12.1.7.70892, wago.tools' db2/<name>/csv):
ItemSparse, ItemModifiedAppearance, ItemAppearance, ItemDisplayInfo, ItemDisplayInfoMaterialRes,
ModelFileData, TextureFileData, Creature, CreatureDisplayInfo, CreatureModelData, ChrModel. It also reads
the app's assets (files.json, locations.json, catalog.tsv, characters.json, emotes.json, sounds.json and
the three art files). Only ids, counts and sizes are written: nothing of the game is in the repository.

The counts are the tables' (exact: what each pack asks for at first); what a model names inside itself
(its skins, skeleton, animations, a building's rooms and doodads) is read from the model on the phone, so
those files are counted at the averages below, measured on files fetched while making the app (November
2026, build 12.1.7.70892):
  - 167 item models 131.5 KB, 432 item textures 69.8 KB (the Transmog cards' renders, 1,153 files);
  - 581 placement-library props of the eleven places: model and skin 15.8 KB, about one texture each, 64.3 KB;
  - 87 library buildings: 8.4 rooms each, a room 135 KB (the rest of each building's files guessed);
  - 216 character and creature models 1.45 MB with their first skin 65.5 KB (the body models weigh most);
  - the creator's files: creator/Prefetch's measurement (16,013 files, about 5.9 GB).
Where no file was measured (icons, sounds, animations, the creatures' textures) the figure is a guess and
says so. Every estimate here is "about": the app replaces it with the real count once the pack has run.
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "android", "app", "src", "main", "assets")

KB = 1000
MB = 1000 * KB
ITEM_MODEL = 131_500
ITEM_SKIN = 40_000            # guess: an item model's first skin
ITEM_TEXTURE = 69_800
ICON = 12_000                 # guess: a 64 x 64 BLP icon
PROP = 15_800                 # model and skin
PROP_TEXTURE = 64_300
PROP_TEXTURES_EACH = 0.9      # unique textures a prop brings (568 for 581 in the places; shared more in all)
ROOM = 135_000
ROOMS_EACH = 8.4
BUILDING_OTHER = 6 * 100_000  # guess: a building's own textures beyond its doodads (counted with the props)
CREATURE_MODEL = 1_000_000    # guess below the 1.45 MB measured (that average is pulled up by the body models)
CREATURE_FILES_EACH = 2       # model, skin (a skeleton for the newer ones)
CREATURE_TEXTURE = 200_000    # guess
BAKED_TEXTURE = 250_000       # guess: an NPC drawn as a player race brings its baked face and skin
ANIM_FILE = 250_000           # guess: an external .anim
ANIMS_PER_MODEL = 58          # measured: 11 character skeletons name 50 to 76 animation files each
SOUND = 35_000                # guess: an emote's voice line (.ogg)
NAMED_MODEL = 15_250_000      # creator/Prefetch's model average (the named characters' are character-sized)
ART_SHEET = 260_000           # guess: an atlas sheet (BLP), 9 MB for the Transmog window's 17 sheets measured
LOOKS_FILES, LOOKS_BYTES = 16_013, 5_900_000_000
TABLES_FILES, TABLES_BYTES = 43, 19_500_000


def rows(tables, name):
    with open(os.path.join(tables, name + ".csv"), newline="", encoding="utf-8") as f:
        yield from csv.DictReader(f)


def num(r, k):
    try:
        return int(float(r.get(k) or 0))
    except ValueError:
        return 0


def asset(name):
    with open(os.path.join(ASSETS, name), encoding="utf-8") as f:
        return f.read()


def transmog(tables):
    looks = {num(r, "ID"): (num(r, "ItemDisplayInfoID"), num(r, "DefaultIconFileDataID")) for r in rows(tables, "ItemAppearance")}
    items = {}
    for r in rows(tables, "ItemModifiedAppearance"):
        v = looks.get(num(r, "ItemAppearanceID"))
        if not v or v[0] <= 0:
            continue
        item, mod = num(r, "ItemID"), num(r, "ItemAppearanceModifierID")
        if item not in items or mod < items[item][0]:
            items[item] = (mod, v)
    slots = {1, 3, 4, 5, 20, 6, 7, 8, 9, 10, 16, 19}      # creator/Transmog's ClothingSlot.fromInventory
    displays, icons = set(), set()
    for r in rows(tables, "ItemSparse"):
        if num(r, "InventoryType") not in slots or not (r.get("Display_lang") or "").strip():
            continue
        v = items.get(num(r, "ID"))
        if not v:
            continue
        displays.add(v[1][0])
        if v[1][1] > 0:
            icons.add(v[1][1])
    by_model, by_material, materials = {}, {}, {}
    for r in rows(tables, "ModelFileData"):
        by_model.setdefault(num(r, "ModelResourcesID"), []).append(num(r, "FileDataID"))
    for r in rows(tables, "TextureFileData"):
        by_material.setdefault(num(r, "MaterialResourcesID"), []).append(num(r, "FileDataID"))
    for r in rows(tables, "ItemDisplayInfoMaterialRes"):
        materials.setdefault(num(r, "ItemDisplayInfoID"), []).append(num(r, "MaterialResourcesID"))
    models, textures = set(), set()
    for r in rows(tables, "ItemDisplayInfo"):
        d = num(r, "ID")
        if d not in displays:
            continue
        for k in (0, 1):
            models.update(by_model.get(num(r, "ModelResourcesID_%d" % k), []))
            textures.update(by_material.get(num(r, "ModelMaterialResourcesID_%d" % k), []))
        for m in materials.get(d, []):
            textures.update(by_material.get(m, []))
    models.discard(0)
    textures.discard(0)
    files = len(models) * 2 + len(textures) + len(icons)
    size = len(models) * (ITEM_MODEL + ITEM_SKIN) + len(textures) * ITEM_TEXTURE + len(icons) * ICON
    return files, size, "%d displays: %d models, %d textures, %d icons" % (len(displays), len(models), len(textures), len(icons))


def catalog_rows():
    out = []
    for line in asset("catalog.tsv").splitlines():
        if not line or line.startswith("#"):
            continue
        f = line.split("\t")
        if len(f) >= 7:
            out.append((int(f[0]), f[5], int(f[6])))
    return out


def creatures(tables, cat):
    displays = {d for _, kind, d in cat if kind == "2" and d > 0}
    for r in rows(tables, "Creature"):
        for k in range(4):
            if num(r, "DisplayID_%d" % k) > 0:
                displays.add(num(r, "DisplayID_%d" % k))
                break
    files = {num(r, "ID"): num(r, "FileDataID") for r in rows(tables, "CreatureModelData")}
    models, textures, baked = set(), set(), 0
    for r in rows(tables, "CreatureDisplayInfo"):
        if num(r, "ID") not in displays:
            continue
        models.add(files.get(num(r, "ModelID"), 0))
        for k in range(4):
            if num(r, "TextureVariationFileDataID_%d" % k) > 0:
                textures.add(num(r, "TextureVariationFileDataID_%d" % k))
        if num(r, "ExtendedDisplayInfoID") > 0:
            baked += 1
    models.discard(0)
    n = len(models) * CREATURE_FILES_EACH + len(textures) + baked * 2
    b = len(models) * CREATURE_MODEL + len(textures) * CREATURE_TEXTURE + baked * BAKED_TEXTURE
    return n, b, "%d displays: %d models, %d textures, %d drawn as a player race" % (len(displays), len(models), len(textures), baked)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    tables = sys.argv[1]
    files_json = json.loads(asset("files.json"))
    est = {}

    core = [f for p in files_json["packs"] for f in p["files"]]
    est["basics"] = (len(core) + TABLES_FILES + LOOKS_FILES, sum(s for _, s in core) + TABLES_BYTES + LOOKS_BYTES,
                     "files.json's packs, the tables and the creator's files (creator/Prefetch)")

    places = {}
    for loc in json.loads(asset("locations.json"))["locations"]:
        for p in loc["files"]["packs"]:
            for fid, size in p["files"]:
                places[fid] = size
    backdrops = 29
    est["places"] = (len(places) + backdrops * 3, sum(places.values()) + backdrops * 3 * 300 * KB,
                     "%d locations' files (exact) and %d race backdrops" % (len(places), backdrops))

    art = set()
    for name, keys in (("transmog_art.json", ("files",)), ("ui_art.json", ("icons",)), ("creator_art.json", ("files",))):
        doc = json.loads(asset(name))
        art |= {int(k) for k in doc["sheets"]}
        for k in keys:
            art |= {v["f"] for v in doc.get(k, {}).values()}
        if "mask" in doc:
            art.add(doc["mask"])
    est["art"] = (len(art), len(art) * ART_SHEET, "%d sheets and pieces of the three art files" % len(art))

    sounds = set()

    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if k not in ("area", "zoneMusic", "ambience", "silenceMs", "kit"):
                    walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
        elif isinstance(o, int):
            sounds.add(o)
    walk(json.loads(asset("sounds.json")))
    sounds |= {565519, 538983} | set(range(4612169, 4612182, 2)) | set(range(2843065, 2843075)) | {566564}
    emotes = json.loads(asset("emotes.json"))
    for m in emotes["models"].values():
        for ids in m.get("v", {}).values():
            sounds.update(ids)
    est["sounds"] = (len(sounds), len(sounds) * SOUND, "%d music, ambience, effect and voice files" % len(sounds))

    chr_models = sum(1 for _ in rows(tables, "ChrModel"))
    est["animations"] = (chr_models * ANIMS_PER_MODEL, chr_models * ANIMS_PER_MODEL * ANIM_FILE,
                         "%d character models' animation files" % chr_models)

    named = set()
    for c in json.loads(asset("characters.json"))["characters"]:
        for v in c.get("variants", []):
            for k in ("display", "sourceDisplay"):
                if v.get(k):
                    named.add(v[k])
    model_of = {num(r, "ID"): num(r, "ModelID") for r in rows(tables, "CreatureDisplayInfo") if num(r, "ID") in named}
    named_models = {m for m in model_of.values() if m}
    est["named"] = (len(named) * 4 + len(named_models) * 2, len(named_models) * NAMED_MODEL + len(named) * 3 * 300 * KB,
                    "%d displays, %d models" % (len(named), len(named_models)))

    t = transmog(tables)
    est["transmog"] = t

    cat = catalog_rows()
    est["creatures"] = creatures(tables, cat)
    props = sum(1 for _, kind, _ in cat if kind == "0")
    est["props"] = (int(props * (2 + PROP_TEXTURES_EACH)), int(props * (PROP + PROP_TEXTURES_EACH * PROP_TEXTURE)),
                    "%d props" % props)
    buildings = sum(1 for _, kind, _ in cat if kind == "1")
    est["buildings"] = (int(buildings * (1 + ROOMS_EACH + 6)), int(buildings * (ROOMS_EACH * ROOM + BUILDING_OTHER)),
                        "%d buildings" % buildings)

    files_json["estimates"] = {k: {"files": int(n), "bytes": int(b)} for k, (n, b, _) in est.items()}
    total = 0
    for k, (n, b, why) in est.items():
        total += b
        print("%-10s %8d files %8.2f GB  %s" % (k, n, b / 1e9, why), file=sys.stderr)
    print("everything %.1f GB" % (total / 1e9), file=sys.stderr)
    with open(os.path.join(ASSETS, "files.json"), "w") as f:
        json.dump(files_json, f, separators=(",", ":"))
        f.write("\n")


if __name__ == "__main__":
    main()
