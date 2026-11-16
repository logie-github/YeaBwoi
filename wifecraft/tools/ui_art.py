#!/usr/bin/env python3
"""Which pieces of the game's current interface the editor's "build mode" look (ui/WarArt.kt, ui/WarWidgets.kt) cuts on the phone.

    python3 tools/ui_art.py fetch CSV_DIR           the atlas tables and the interface manifest (wago.tools' CSVs at the app's build)
    python3 tools/ui_art.py make CSV_DIR [OUT]      writes OUT (default android/app/src/main/assets/ui_art.json)
    python3 tools/ui_art.py check CSV_DIR LISTFILE  every icon's file id against the community listfile (names as the game has them)

The look is "build mode, themed like Warcraft": the game's own housing editor (12.x: its round tool buttons, the decor
chest's picture cards, the wooden corbels and signs), the spellbook's page and side tabs, the talent tree's gold rings, the
action bar's slot frames, parchment, ribbons, the minimal scroll bar and slider, the round radio button, the gold glyphs
(undo, back, delete...) and ~60 of the game's item and spell icons for the library's tabs and categories, the tools and the
emotes. All of them are read from the real UiTextureAtlas* tables and ManifestInterfaceData at the app's pinned build
(GameTables.BUILD, 12.1.7.70892): a piece or an icon that isn't there stops the script.

The json holds ids and boxes only (nothing of the game is committed):

    {"build": "...",
     "sheets": {"<file id>": [w, h]},                                  the atlas sheets (fetched by id at that build)
     "pieces": {name: {"f": file id, "b": [l, t, r, b], "s": [w, h]}}, a piece's box in its sheet and the size the game draws it at
     "icons":  {key: {"f": file id, "n": "inv_misc_gift_01"}}}         whole icon files (Interface\\Icons), by what the editor uses them for

A name in the atlas tables can have several members (the 1x and 2x art); the largest is taken. Pieces are chosen so
that few sheets are fetched: 29 sheets and 57 icons, about 58 MB at this build (a sheet is fetched whole; one that would
be fetched for one or two small pieces, as the 2048 x 2048 ones of the eye glyphs or the leafy filigree, was left out).
"""
import csv
import json
import os
import sys

BUILD = "12.1.7.70892"
USER_AGENT = "build-tools/1.0"
TABLES = ("UiTextureAtlas", "UiTextureAtlasMember", "UiTextureAtlasElement", "ManifestInterfaceData")

# The atlas pieces, by what they are in the kit.
PIECES = (
    # the housing editor's round-cornered tool buttons (Pointer = its Move hand, Rotate, Scale, Snap), each up, lit, pressed
    "decor-ability-move-default", "decor-ability-move-active", "decor-ability-move-pressed",
    "decor-ability-expert-rotate-default", "decor-ability-expert-rotate-active", "decor-ability-expert-rotate-pressed",
    "decor-ability-expert-scale-default", "decor-ability-expert-scale-active", "decor-ability-expert-scale-pressed",
    "decor-ability-snap-default", "decor-ability-snap-active", "decor-ability-snap-pressed",
    # the decor chest's picture cards (also the empty slot of the other tools, an icon inside), with their mask
    "house-chest-list-item-default", "house-chest-list-item-active", "house-chest-list-item-pressed", "house-chest-list-item-mask",
    # the catalogue bar's wood: the corbels with leaves, the sign plank, the drawer handle
    "decor-abilitybar-bookend-left", "decor-abilitybar-bookend-right", "decor-abilitybar-divider", "decor-abilitybar-drawer-arrow",
    "house-chest-corbel-topright", "housing-woodsign",
    "housing-basic-container-woodheader", "housing-basic-panel-background", "housing-basic-panel--stone-background",
    "house-chest-open-drawer-button", "house-chest-close-drawer-button",
    # round buttons: the radio button's disc and dot, the talent tree's rings, the housing map's glowing ring
    "common-radiobutton-circle", "common-radiobutton-dot",
    "talents-node-circle-yellow", "talents-node-circle-gray", "talents-node-square-yellow", "talents-node-square-gray",
    "housing-layout-room-orb-ring-default", "housing-layout-room-orb-ring-active",
    # the action bar's slot frame (up, pressed, lit) and its mask
    "ui-hud-actionbar-iconframe", "ui-hud-actionbar-iconframe-down", "ui-hud-actionbar-iconframe-flash", "ui-hud-actionbar-iconframe-mask",
    # the spellbook: its page, its ribbon, its side tab
    "spellbook-background-evergreen-left", "spellbook-background-evergreen-ribbon", "spellbook-skilllinetab",
    # parchment and ribbons
    "questbg-parchment", "parchmentpopup-background", "parchmentpopup-filigree", "ui-frame-neutral-ribbon", "ui-frame-neutral-cardparchment",
    # pill buttons (the tertiary button: up, chosen, pressed)
    "common-button-tertiary-normal", "common-button-tertiary-selected", "common-button-tertiary-pressed",
    # tooltip (nine slice), scroll bar, slider, search box
    "tooltip-nineslice-center", "tooltip-nineslice-cornertopleft", "tooltip-nineslice-cornertopright",
    "tooltip-nineslice-cornerbottomleft", "tooltip-nineslice-cornerbottomright",
    "minimal-scrollbar-thumb-top", "minimal-scrollbar-thumb-middle", "minimal-scrollbar-thumb-bottom",
    "minimal-scrollbar-track-top", "minimal-scrollbar-track-bottom", "!minimal-scrollbar-track-middle",
    "minimal_sliderbar_button", "minimal_sliderbar_left", "minimal_sliderbar_right", "_minimal_sliderbar_middle",
    "common-search-border-left", "common-search-border-middle", "common-search-border-right", "common-search-magnifyingglass",
    # the gold glyphs on round buttons
    "common-icon-undo", "common-icon-backarrow", "common-icon-forwardarrow", "common-icon-delete", "common-icon-copy",
    "common-icon-move", "common-icon-rotateleft", "common-icon-rotateright", "common-icon-zoomin", "common-icon-zoomout",
    "common-icon-sound", "common-icon-checkmark-yellow", "common-icon-plus", "common-icon-minus", "common-icon-exit",
    "common-icon-speak", "common-icon-redx", "icon-camera-target", "ui-charactercreate-padlock",
    "auctionhouse-icon-favorite", "auctionhouse-icon-favorite-off",
    # the WoW-style menus (W40): a window's red close X, the frame's bottom tabs (up and chosen), the settings' checkbox and
    # its tick, the dropdown's arrow button (shut and open)
    "redbutton-exit", "redbutton-exit-pressed",
    "uiframe-tab-left", "uiframe-tab-right", "_uiframe-tab-center",
    "uiframe-activetab-left", "uiframe-activetab-right", "_uiframe-activetab-center",
    "checkbox-minimal", "checkmark-minimal",
    "common-dropdown-a-button", "common-dropdown-a-button-open",
)

# The icons (Interface\Icons\<name>.blp), by what they stand for. Keys are read by WarKit (WarIcons).
ICONS = {
    # the library's tabs
    "tab.starter": "inv_misc_gift_01", "tab.props": "inv_crate_01", "tab.buildings": "spell_housing",
    "tab.creatures": "ability_hunter_pet_wolf", "tab.npcs": "achievement_character_human_female",
    "tab.favourites": "inv_jewelcrafting_starofelune_01", "tab.recent": "inv_misc_pocketwatch_01",
    # the props' and buildings' categories (catalog.tsv's #cats)
    "cat.lighting": "inv_misc_lantern_01", "cat.furniture": "inv_10_tailoring2_pillow_red", "cat.food and drink": "inv_misc_food_102_flatbread",
    "cat.containers": "inv_crate_04", "cat.books and papers": "inv_misc_book_07", "cat.tools and crafts": "inv_hammer_20",
    "cat.carts and wagons": "inv_misc_noodle_cart_base_level", "cat.weapons and armor": "inv_sword_04", "cat.banners and flags": "inv_banner_01",
    "cat.tents": "inv_10_tailoring2_tent_color1", "cat.statues": "inv_misc_statue_02", "cat.ships and docks": "inv_misc_anchor",
    "cat.water": "inv_misc_fish_02", "cat.nature": "inv_misc_flower_02", "cat.bones and remains": "inv_misc_bone_humanskull_01",
    "cat.architecture": "inv_12ph_rugged_ior01_partition_w_l_arch_icon", "cat.magic": "spell_holy_magicalsentry",
    "cat.buildings": "spell_housing", "cat.other": "inv_misc_questionmark",
    # the creatures' types
    "cat.beast": "ability_hunter_pet_wolf", "cat.dragonkin": "inv_misc_head_dragon_01", "cat.demon": "spell_shadow_summonimp",
    "cat.elemental": "inv_elemental_mote_fire01", "cat.giant": "achievement_dungeon_ulduarraid_icegiant_01",
    "cat.undead": "inv_misc_head_undead_01", "cat.humanoid": "achievement_character_human_male", "cat.critter": "inv_misc_rabbit",
    "cat.mechanical": "inv_gizmo_02", "cat.totem": "spell_nature_earthelemental_totem", "cat.non-combat pet": "inv_box_petcarrier_01",
    "cat.gas cloud": "spell_shadow_plaguecloud", "cat.wild pet": "inv_pet_battlepettraining", "cat.aberration": "ability_racial_aberration",
    "cat.creatures": "ability_hunter_pet_boar", "cat.not specified": "inv_misc_questionmark", "cat.my people": "achievement_character_bloodelf_female",
    # the tools that have no glyph of their own
    "tool.eyedropper": "inv_inscription_inkblack01", "tool.duplicate": "spell_nature_mirrorimage", "tool.lock": "inv_misc_key_03",
    "tool.emote": "achievement_halloween_smiley_01", "tool.focus": "inv_misc_spyglass_02", "tool.stage": "inv_misc_map_01",
    "tool.camera": "inv_misc_ selfiecamera_01", "tool.hide": "ability_eyeoftheowl", "tool.tips": "inv_misc_note_01", "tool.things": "inv_misc_book_07",
    # the emote groups (EmoteCatalog's categories)
    "emote.emotes": "achievement_halloween_smiley_01", "emote.dance": "inv_misc_drum_01", "emote.sit": "spell_nature_sleep",
    "emote.work": "inv_pick_02", "emote.combat": "inv_sword_04", "emote.spells": "spell_fire_fireball", "emote.other": "inv_misc_toy_02",
    # the projects, the cast, the scenes
    "menu.project": "inv_misc_1h_scribesquill_b_01_gold", "menu.scene": "inv_misc_note_01", "menu.cast": "achievement_character_bloodelf_female",
    "menu.studio": "inv_misc_candle_01", "menu.location": "inv_garrison_hearthstone",
}
ICON_FOLDER = "interface\\icons\\"


def fetch(csv_dir):
    import urllib.request
    os.makedirs(csv_dir, exist_ok=True)
    for t in TABLES:
        url = "https://wago.tools/db2/%s/csv?build=%s" % (t, BUILD)
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=300) as r:
            data = r.read()
        with open(os.path.join(csv_dir, t + ".csv"), "wb") as f:
            f.write(data)
        print("%s: %d bytes" % (t, len(data)))


def rows(csv_dir, table):
    with open(os.path.join(csv_dir, table + ".csv"), newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def interface_files(csv_dir):
    """ManifestInterfaceData: (folder, file name) -> file id, lower case; and every file id in it."""
    by_name, ids = {}, set()
    for r in rows(csv_dir, "ManifestInterfaceData"):
        by_name[(r["FilePath"].lower(), r["FileName"].lower())] = int(r["ID"])
        ids.add(int(r["ID"]))
    return by_name, ids


def pieces(csv_dir, interface_ids):
    sheets = {r["ID"]: r for r in rows(csv_dir, "UiTextureAtlas")}
    elements = {r["ID"]: r["Name"] for r in rows(csv_dir, "UiTextureAtlasElement")}
    best = {}
    for m in rows(csv_dir, "UiTextureAtlasMember"):
        name = (elements.get(m["UiTextureAtlasElementID"]) or m["CommittedName"]).lower()
        if name not in PIECES:
            continue
        box = [int(m[k]) for k in ("CommittedLeft", "CommittedTop", "CommittedRight", "CommittedBottom")]
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        area = (box[2] - box[0]) * (box[3] - box[1])
        if name in best and best[name][0] >= area:
            continue
        best[name] = (area, m, box)
    missing = [n for n in PIECES if n not in best]
    if missing:
        raise RuntimeError("not in the atlas tables at %s: %s" % (BUILD, ", ".join(missing)))
    out, used = {}, {}
    for name in PIECES:
        _, m, box = best[name]
        sheet = sheets[m["UiTextureAtlasID"]]
        fid = int(sheet["FileDataID"])
        if fid not in interface_ids:
            raise RuntimeError("%s: its sheet %d is not in ManifestInterfaceData at %s" % (name, fid, BUILD))
        size = [int(m["OverrideWidth"]) or box[2] - box[0], int(m["OverrideHeight"]) or box[3] - box[1]]
        out[name] = {"f": fid, "b": box, "s": size}
        used[str(fid)] = [int(sheet["AtlasWidth"]), int(sheet["AtlasHeight"])]
    return dict(sorted(out.items())), dict(sorted(used.items(), key=lambda kv: int(kv[0])))


def icons(by_name):
    out, missing = {}, []
    for key, name in ICONS.items():
        fid = by_name.get((ICON_FOLDER, name + ".blp"))
        if fid is None:
            missing.append(name)
        else:
            out[key] = {"f": fid, "n": name}
    if missing:
        raise RuntimeError("not in ManifestInterfaceData's Interface\\Icons at %s: %s" % (BUILD, ", ".join(missing)))
    return out


def make(csv_dir, out):
    by_name, ids = interface_files(csv_dir)
    p, sheets = pieces(csv_dir, ids)
    doc = {"build": BUILD, "sheets": sheets, "pieces": p, "icons": icons(by_name)}
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, separators=(",", ":"))
        f.write("\n")
    files = len(sheets) + len({v["f"] for v in doc["icons"].values()})
    print("%d pieces on %d sheets, %d icons (%d files in all) -> %s (%d bytes)"
          % (len(p), len(sheets), len(doc["icons"]), files, out, os.path.getsize(out)))


def check(csv_dir, listfile):
    """Every icon's id against the community listfile (id;path): the same file, by the game's own name."""
    by_name, _ = interface_files(csv_dir)
    want = {fid: name for name, fid in ((n, by_name.get((ICON_FOLDER, n + ".blp"))) for n in ICONS.values()) if fid}
    found = {}
    with open(listfile, encoding="utf-8", errors="replace") as f:
        for line in f:
            fid, _, path = line.strip().partition(";")
            if fid.isdigit() and int(fid) in want:
                found[int(fid)] = path.lower()
    bad = 0
    for fid, name in sorted(want.items()):
        path = found.get(fid)
        ok = path == "interface/icons/%s.blp" % name
        bad += not ok
        if not ok:
            print("MISMATCH %d %s: listfile says %s" % (fid, name, path))
    print("%d icons, %d agree with the listfile" % (len(want), len(want) - bad))
    return 1 if bad else 0


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    default = os.path.join(here, "..", "android", "app", "src", "main", "assets", "ui_art.json")
    if len(argv) == 3 and argv[1] == "fetch":
        fetch(argv[2])
        return 0
    if len(argv) in (3, 4) and argv[1] == "make":
        make(argv[2], argv[3] if len(argv) == 4 else os.path.normpath(default))
        return 0
    if len(argv) == 4 and argv[1] == "check":
        return check(argv[2], argv[3])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
