#!/usr/bin/env python3
"""The scene editor's library of props: every model of the game's world usable as a scene prop, with a display name
made from its path, a category, a style (whose it is: human, night elf, Forsaken...) and a size class, and writes
assets/catalog.tsv. Ids and short names only: the models are the game's and the app fetches them on the phone by id.

    python3 -I tools/catalog.py TABLES_DIR LISTFILE [OUT]

TABLES_DIR  wago.tools' CSVs at creator/GameTables.BUILD (12.1.7.70892): ModelFileData (each model's bounding box,
            GeoBox: the size class), CreatureModelData, CreatureDisplayInfo, Creature, CreatureType (the creature
            models), GameObjectDisplayInfo and GameObjects (the game's own names for the models its
            objects use: "Mailbox", "Wooden Chair").
LISTFILE    the community listfile (https://github.com/wowdev/wow-listfile/releases/latest/download/
            community-listfile.csv: "id;path"): the models' paths. The tables name no doodad, so a prop's name and
            category come from its path.

What is a prop: an .m2 under world/ (doodads), the weapons and shields of item/objectcomponents (things to hang on a
wall or lay on a table), and root .wmo files under world/wmo (buildings), less what isn't a thing to place: particles and
effects, collision and test models, skyboxes and sky, detail grass, the listfile's unnamed files, and models whose box is
smaller than MIN_SIZE yards. And every creature model (CreatureModelData's models under creature/, shown through their
first CreatureDisplayInfo, named by path, in the category of the CreatureType that uses them most): the game's named
creatures ("Archmage Khadgar") are not here, they are read from the game's tables on the phone (catalog/Catalog.kt
Catalog.creatures: Creature, CreatureDisplayInfo, CreatureModelData, CreatureType).

Names: the path's file name, less an expansion's or a zone's short prefix (11go_, 7vr_, pa_), split into words; where
one of the game's objects (GameObjects) shows the model under a plain name ("Mailbox"), that name is the prop's alias
(searched too). Category: the first CATS rule a word of the name has (whole words, and inside glued words for words of
four letters or more: "duskwoodtree" is a tree), else of its folders; a weapon or shield file is Weapons and armor, a WMO
a Building. Style: the first STYLES word of the name or a folder.

The file (UTF-8 text, tab separated, one prop a line, ordered by category and name):
    #wifecraft-catalog <TAB> build
    #cats <TAB> name <TAB> ...        #styles <TAB> ...        #sizes <TAB> ...
    file id <TAB> name <TAB> cat <TAB> style <TAB> size <TAB> kind <TAB> display [<TAB> alias]
kind 0 a model (M2), 1 a building (WMO), 2 a creature model (display: its CreatureDisplayInfo id, 0 for the others);
size -1 unknown (no box in ModelFileData / CreatureModelData).
"""
import csv
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "catalog.tsv")
BUILD = "12.1.7.70892"
MIN_SIZE = 0.15          # yards, the box's longest side

# Not a prop: a path with any of these words (folder or file name, after splitting on / _ - and digits).
JUNK = {"fx", "fxs", "particle", "particles", "effect", "effects", "collision", "coll", "test", "debug", "placeholder",
        "skybox", "sky", "skies", "stars", "glow", "glows", "beam", "beams", "lightshaft", "lightshafts", "lightray",
        "godray", "godrays", "fog", "haze", "mist", "smoke", "nodraw", "invisible", "proxy", "unknown", "autogen",
        "boundingbox", "shadow", "shadows", "decal", "decals", "anim", "animation", "template", "precast", "impact",
        "impactlow", "missile", "aura", "spell", "spells", "cloud", "clouds", "ripple", "ripples", "splash", "sparkle",
        "sparkles", "embers", "ember", "dust", "swirl", "lod", "temp", "tmp", "doodadset", "visual", "visuals",
        "float", "floating", "zzold", "old", "deprecated"}
# ... and inside glued words: "lavasmoke", "ograid_steamjets".
JUNK_INSIDE = ("smoke", "steamjet", "particle", "sparkle", "glow", "lightshaft", "lightray", "godray", "ripple", "splash",
               "swirl", "emitter", "collision", "placeholder", "skybox")
# A leading short word that is a real word, kept (another short first word is a zone's or expansion's prefix: pa_, hz_).
SHORT_WORDS = {"big", "old", "red", "tea", "oak", "box", "bed", "pot", "jar", "log", "cup", "mug", "rug", "hay", "axe",
               "bow", "urn", "map", "keg", "sun", "ice", "sea", "gem", "egg", "fan", "net", "bag", "can", "cap", "hat",
               "web", "orb", "elf", "orc", "imp", "rat", "bat", "cat", "dog", "pig", "cow", "owl", "fox", "fish", "tub",
               "wall", "tent", "rock", "tree", "fel", "arc", "war", "sky", "air", "low", "mid", "top", "new", "one", "two"}
JUNK_FOLDERS = ("world/nodxt/", "world/environment/", "world/scale/", "environments/", "world/critter/")

# Category: the first rule with a word of the path's name (then of its folders).
CATS = [
    ("Lighting", "lamp lamps lantern lanterns candle candles candelabra brazier braziers torch torches chandelier sconce "
                 "lightpost streetlamp streetlight lamppost firepit campfire fire bonfire light lights"),
    ("Furniture", "chair chairs table tables bench benches bed beds bookshelf bookcase shelf shelves cabinet stool stools "
                  "desk rug rugs carpet throne couch sofa dresser wardrobe pillow pillows cushion cushions bar counter "
                  "nightstand hammock cot bunk mat mats curtain curtains drawer drawers armoire footlocker trunk bedroll"),
    ("Food and drink", "food bread cheese meat ham fish fruit apple apples pumpkin pumpkins melon bottle bottles mug mugs "
                       "wine ale beer plate plates bowl bowls cup cups goblet pie cake cakes sausage feast fruitbowl "
                       "vegetable vegetables carrot carrots potato potatoes corn grain wheat egg eggs teapot kettle "
                       "noodles rice soup jug pitcher flask"),
    ("Containers", "barrel barrels crate crates box boxes chest chests sack sacks basket baskets keg kegs bag bags jar "
                   "jars pot pots urn urns vase vases bucket buckets bin container containers coffer strongbox pottery"),
    ("Books and papers", "book books scroll scrolls paper papers letter letters map maps tome tomes parchment note "
                         "notes ledger journal"),
    ("Tools and crafts", "anvil forge hammer tool tools saw saws cauldron workbench loom spinningwheel grindstone "
                         "bellows tongs pickaxe shovel rake pitchfork axe wheelbarrow ladder rope ropes chain chains "
                         "bucket scale scales alchemy alchemytable"),
    ("Carts and wagons", "cart carts wagon wagons wheel wheels carriage"),
    ("Weapons and armor", "weapon weapons weaponrack armorrack rack racks sword swords shield shields armor spear "
                          "spears mace bow bows polearm dagger staff staves helm helmet target targetdummy dummy"),
    ("Banners and flags", "banner banners flag flags pennant pennants tabard standard"),
    ("Tents", "tent tents pavilion canopy awning yurt"),
    ("Statues", "statue statues idol idols totem totems monument bust gargoyle"),
    ("Ships and docks", "ship ships boat boats dock docks pier piers anchor canoe raft rowboat gangplank buoy"),
    ("Water", "water waterfall waterfalls fountain fountains well wells pond lake river stream"),
    ("Nature", "tree trees bush bushes shrub shrubs rock rocks boulder boulders stone stones flower flowers plant "
               "plants grass mushroom mushrooms fern ferns vine vines log logs stump stumps cactus coral kelp seaweed "
               "leaf leaves branch branches root roots moss hay haystack reed reeds lilypad cliff cliffs crystal "
               "crystals pebble pebbles hedge sapling willow pine oak palm bamboo herb herbs ore vein fungus fungi"),
    ("Bones and remains", "bone bones skull skulls skeleton skeletons corpse corpses carcass ribcage remains grave "
                          "graves tombstone tombstones coffin coffins gravestone"),
    ("Architecture", "wall walls fence fences gate gates door doors bridge bridges column columns pillar pillars arch "
                     "arches stairs stair steps roof window windows tower towers ruin ruins rubble building buildings "
                     "house houses hut huts post posts beam floor floors platform scaffold scaffolding railing "
                     "railings archway brick bricks plank planks wood woodpile woodstack sign signs signpost "
                     "mailbox well curb curbs path road"),
    ("Magic", "rune runes portal portals orb orbs altar altars obelisk shrine relic artifact candle"),
]
CAT_NAMES = [c for c, _ in CATS] + ["Buildings", "Other"]
CAT_WORDS = [(i, set(w.split())) for i, (_, w) in enumerate(CATS)]

# Style: the first folder or name word that names a people or a place's look.
STYLES = [
    ("Human", "human humans stormwind elwynn westfall duskwood redridge goldshire lordaeron kultiras kultiran boralus "
              "drustvar tiragarde stormsong gilneas gilnean alterac stromgarde hu"),
    ("Forsaken", "forsaken undead scourge tirisfal tirisfalglade brill undercity plagueland plaguelands "
                 "silverpine lordaeron"),
    ("Night elf", "nightelf nightelves ashenvale darnassus teldrassil darkshore feralas valsharah ne"),
    ("Blood elf", "bloodelf bloodelves silvermoon eversong quelthalas be sindorei"),
    ("Dwarf", "dwarf dwarven dwarves ironforge dunmorogh lochmodan wetlands khazmodan darkiron"),
    ("Gnome", "gnome gnomes gnomish gnomeregan mechagon mechagnome"),
    ("Draenei", "draenei exodar azuremyst bloodmyst lightforged"),
    ("Orc", "orc orcs orcish orgrimmar durotar barrens warsong maghar"),
    ("Troll", "troll trolls zuldazar zandalari zandalar zuldrak zulaman zulgurub"),
    ("Tauren", "tauren mulgore thunderbluff highmountain"),
    ("Goblin", "goblin goblins kezan undermine"),
    ("Pandaren", "pandaren pandaria jadeforest valeofeternalblossoms townlong kunlai mz"),
    ("Worgen", "worgen"),
    ("Nightborne", "nightborne nightborn suramar"),
    ("Vrykul", "vrykul stormheim"),
    ("Titan", "titan titans ulduar uldum ulduman"),
    ("Demon", "demon demons legion fel felfire burninglegion eredar argus"),
    ("Naga", "naga nazjatar"),
    ("Dragon", "dragon dragons dragonkin dragonisles"),
    ("Earthen", "earthen khazalgar dornogal"),
    ("Nerubian", "nerubian nerubians azjkahet"),
    ("Arathi", "arathi hallowfall"),
    ("Shadowlands", "maw maldraxxus bastion revendreth ardenweald oribos"),
    ("Void", "void"),
    ("Centaur", "centaur centaurs"),
    ("Tuskarr", "tuskarr"),
    ("Vulpera", "vulpera"),
]
STYLE_NAMES = ["Any"] + [s for s, _ in STYLES]
STYLE_WORDS = [(i + 1, set(w.split())) for i, (_, w) in enumerate(STYLES)]

SIZES = ["Tiny", "Small", "Medium", "Large", "Huge"]
SIZE_LIMITS = [0.5, 1.5, 4.0, 12.0]          # yards: the box's longest side below each


def table(d, name):
    with open(os.path.join(d, name + ".csv"), newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def words(s):
    return [w for w in re.split(r"[^a-z]+", s.lower()) if w]


def name_of(path):
    """'world/expansion10/doodads/goblin/11go_goblin_barrel03.m2' -> 'Goblin barrel 03'."""
    base = os.path.splitext(os.path.basename(path))[0].lower()
    base = re.sub(r"^\d+[a-z]{1,5}_", "", base)            # an expansion's prefix: 11go_, 7vr_, 8xp_
    base = re.sub(r"^\d+(?=[a-z]{4})", "", base)            # 9malsinew -> malsinew
    parts = [p for p in re.split(r"[_\s]+", base) if p]
    if len(parts) > 1 and len(parts[0]) <= 3 and parts[0].isalpha() and parts[0] not in SHORT_WORDS:
        parts = parts[1:]
    out = []
    for p in parts:
        out += [x for x in re.split(r"(?<=[a-z])(?=\d)", p) if x]   # barrel03 -> barrel 03
    text = " ".join(out).strip()
    return text[:1].upper() + text[1:] if text else ""


def match(ws, rules):
    """The first rule with one of the words, whole, else inside a glued word (rule words of four letters or more)."""
    for i, rule in rules:
        if any(w in rule for w in ws):
            return i
    for i, rule in rules:
        if any(len(r) >= 4 and r in w for w in ws for r in rule):
            return i
    return None


def classify(path, kind):
    folders = path.lower().split("/")[:-1]
    own = words(os.path.splitext(os.path.basename(path))[0])
    if kind == 1:
        cat = CAT_NAMES.index("Buildings")
    elif path.lower().startswith("item/objectcomponents/"):
        cat = CAT_NAMES.index("Weapons and armor")
    else:
        cat = match(own, CAT_WORDS)
        if cat is None:
            fw = [w for f in reversed(folders) for w in words(f)]
            cat = match(fw, CAT_WORDS)
        if cat is None:
            cat = CAT_NAMES.index("Other")
    allw = own + [w for f in folders for w in words(f)]
    style = next((i for i, ws in STYLE_WORDS for w in allw if w in ws), 0)
    return cat, style


def is_prop(path):
    p = path.lower()
    if p.endswith(".wmo"):
        if not p.startswith("world/wmo/") or re.search(r"_\d{3}\.wmo$|_lod\d\.wmo$", p):
            return 1, False
        kind = 1
    elif p.endswith(".m2"):
        if p.startswith("item/objectcomponents/"):
            if not (p.startswith("item/objectcomponents/weapon/") or p.startswith("item/objectcomponents/shield/")):
                return 0, False
        elif not p.startswith("world/") or p.startswith(JUNK_FOLDERS):
            return 0, False
        kind = 0
    else:
        return 0, False
    ws = set(words(p))
    base = os.path.basename(p)
    return kind, not (ws & JUNK) and not any(j in base for j in JUNK_INSIDE)


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    d, listfile = sys.argv[1:3]
    out_path = sys.argv[3] if len(sys.argv) > 3 else OUT

    box = {}
    for r in table(d, "ModelFileData"):
        g = [float(r["GeoBox_%d" % k] or 0) for k in range(6)]
        if any(g):
            box[int(r["FileDataID"])] = max(g[3] - g[0], g[4] - g[1], g[5] - g[2])
    # the game's own names for the models its objects show (the most used name per model)
    display_file = {int(r["ID"]): int(r["FileDataID"]) for r in table(d, "GameObjectDisplayInfo")}
    named = {}
    for r in table(d, "GameObjects"):
        f = display_file.get(int(r["DisplayID"] or 0))
        n = (r.get("Name_lang") or "").strip()
        # a plain name only ("Mailbox", "Wooden Chair"): not a designer's ("11.2.0 - Dun Morogh - Brewfest Props")
        if f and re.fullmatch(r"[A-Za-z][A-Za-z' ]{2,30}", n) and len(n.split()) <= 3 \
                and not re.search(r"\b(DND|DNT|test|bunny|credit|trigger|dummy|stalker)\b", n, re.I):
            named.setdefault(f, {}).setdefault(n, 0)
            named[f][n] += 1

    # creature models (kind 2): each CreatureModelData model under creature/, shown through its first display (its
    # textures), its category the CreatureType of the creatures that show it (the most common), else "Creatures"
    types = {int(r["ID"]): r["Name_lang"] for r in table(d, "CreatureType")}
    cats = CAT_NAMES + [types[k] for k in sorted(types)] + ["Creatures"]
    cmd_file, cmd_box = {}, {}
    for r in table(d, "CreatureModelData"):
        fid = int(r["FileDataID"] or 0)
        if fid:
            cmd_file[int(r["ID"])] = fid
            g = [float(r["GeoBox_%d" % k] or 0) for k in range(6)]
            if any(g):
                cmd_box[fid] = max(g[3] - g[0], g[4] - g[1], g[5] - g[2])
    first_display, display_model = {}, {}
    for r in table(d, "CreatureDisplayInfo"):
        f = cmd_file.get(int(r["ModelID"] or 0))
        if f:
            display_model[int(r["ID"])] = f
            first_display[f] = min(first_display.get(f, 1 << 31), int(r["ID"]))
    model_types = {}
    for r in table(d, "Creature"):
        for k in range(4):
            f = display_model.get(int(r["DisplayID_%d" % k] or 0))
            if f and int(r["CreatureType"] or 0) in types:
                c = model_types.setdefault(f, {})
                c[int(r["CreatureType"])] = c.get(int(r["CreatureType"]), 0) + 1

    objects = []
    seen = set()
    with open(listfile, encoding="utf-8", errors="replace") as f:
        for line in f:
            i, _, path = line.rstrip("\r\n").partition(";")
            if not i.isdigit():
                continue
            if int(i) in first_display and path.lower().startswith("creature/") and path.lower().endswith(".m2"):
                fid = int(i)
                if any(j in path.lower() for j in ("invisible", "stalker", "bunny", "collision", "test")):
                    continue
                t = model_types.get(fid)
                cat = cats.index(types[max(t, key=lambda k: (t[k], -k))]) if t else len(cats) - 1
                size = cmd_box.get(fid)
                sc = -1 if size is None else next((k for k, lim in enumerate(SIZE_LIMITS) if size < lim), len(SIZE_LIMITS))
                objects.append([fid, name_of(path), cat, classify(path, 2)[1], sc, 2, first_display[fid], ""])
                continue
            kind, ok = is_prop(path)
            if not ok:
                continue
            fid = int(i)
            size = box.get(fid)
            if kind == 0 and size is not None and size < MIN_SIZE:
                continue
            nm = name_of(path)
            alias = max(named[fid].items(), key=lambda kv: (kv[1], kv[0]))[0] if fid in named else ""
            if alias.lower() == nm.lower():
                alias = ""
            if not nm or fid in seen:
                continue
            seen.add(fid)
            cat, style = classify(path, kind)
            sc = -1 if size is None else next((k for k, lim in enumerate(SIZE_LIMITS) if size < lim), len(SIZE_LIMITS))
            if kind == 1:
                sc = len(SIZES) - 1 if sc < 0 else sc
            objects.append([fid, nm, cat, style, sc, kind, 0, alias])
    objects.sort(key=lambda o: (o[2], o[1].lower(), o[0]))
    with open(out_path, "w", encoding="utf-8", newline="\n") as fo:
        fo.write("#wifecraft-catalog\t%s\n" % BUILD)
        fo.write("#cats\t%s\n#styles\t%s\n#sizes\t%s\n" % ("\t".join(cats), "\t".join(STYLE_NAMES), "\t".join(SIZES)))
        for o in objects:
            row = [str(x) for x in o[:7]] + ([o[7]] if o[7] else [])
            fo.write("\t".join(row) + "\n")
    by = {}
    for o in objects:
        by[cats[o[2]]] = by.get(cats[o[2]], 0) + 1
    print("%d entries: %d props, %d buildings, %d creature models; %d bytes; %s" % (
        len(objects), sum(1 for o in objects if o[5] == 0), sum(1 for o in objects if o[5] == 1),
        sum(1 for o in objects if o[5] == 2), os.path.getsize(out_path), by))


if __name__ == "__main__":
    main()
