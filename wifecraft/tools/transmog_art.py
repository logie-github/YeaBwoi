#!/usr/bin/env python3
"""Which pieces of the game's own Transmogrify window (Midnight's Blizzard_Transmog) the creator's Transmog window cuts on the phone.

    python3 tools/transmog_art.py fetch CSV_DIR        the atlas tables and the interface manifest (wago.tools' CSVs at the build)
    python3 tools/transmog_art.py make CSV_DIR [OUT]   writes OUT (default android/app/src/main/assets/transmog_art.json)

The pieces are the atlases Blizzard_Transmog.xml, Blizzard_TransmogTemplates.xml and the templates they inherit name (the
frame's metal border, the outfit column, the gear slots, the item and set cards, the tabs, the search box, the filter
button, the tertiary buttons, the tooltip), read from the real UiTextureAtlas* tables at the app's pinned build
(GameTables.BUILD, 12.1.7.70892). A piece that isn't there stops the script.

    {"build": "...",
     "sheets": {"<file id>": [w, h]},                     the atlas sheets (fetched by id at that build)
     "pieces": {name: {"f": file id, "b": [l, t, r, b],   a piece's box in its sheet: its largest member (the 2x art)
                       "s": [w, h],                        the size the game lays it out at (logical_size)
                       "n": [l, t, r, b]}},                its nine-slice margins in those units
                                                           (UiTextureAtlasElementSliceData), when it has them
     "files": {name: {"f": file id, "s": [w, h]}}}         plain files (Interface\\Buttons): the paging arrows

Nothing of the game is committed: the json holds ids and boxes; the pictures are fetched from wago.tools on the phone.
"""
import csv
import json
import os
import sys

BUILD = "12.1.7.70892"
USER_AGENT = "build-tools/1.0"
TABLES = ("UiTextureAtlas", "UiTextureAtlasMember", "UiTextureAtlasElement", "UiTextureAtlasElementSliceData",
          "ManifestInterfaceData")

SLOTS = ("head", "shoulders", "back", "chest", "tabard", "shirt", "wrist", "hands", "waist", "legs", "feet")

PIECES = (
    # PortraitFrameTemplate (NineSliceLayouts.lua PortraitFrameTemplate), its portrait (transmog-icon-ui) and close button
    "ui-frame-portraitmetal-cornertopleft", "ui-frame-metal-cornertopright",
    "ui-frame-metal-cornerbottomleft", "ui-frame-metal-cornerbottomright",
    "_ui-frame-metal-edgetop", "_ui-frame-metal-edgebottom", "!ui-frame-metal-edgeleft", "!ui-frame-metal-edgeright",
    "transmog-icon-ui", "redbutton-exit", "redbutton-exit-pressed",
    # the outfit column (OutfitCollection)
    "transmog-outfit-darkbg", "transmog-outfit-toptexture", "transmog-outfit-bottomtexture",
    "transmog-outfit-darkbg-cornerline", "transmog-outfit-darkbg-gradient", "transmog-outfit-dividerline",
    "transmog-outfit-spellframe", "transmog-outfit-spellframe-active",
    "transmog-outfit-card", "transmog-outfit-card-selected", "transmog-outfit-card-selected-purple",
    # SharedButtonTemplate (BigRedThreeSliceButtonTemplate): Save Outfit, New Custom Set
    "128-redbutton-left", "_128-redbutton-center", "128-redbutton-right",
    "128-redbutton-left-pressed", "_128-redbutton-center-pressed", "128-redbutton-right-pressed",
    "128-redbutton-left-disabled", "_128-redbutton-center-disabled", "128-redbutton-right-disabled",
    # the character's slots (TransmogAppearanceSlotTemplate)
    "transmog-gearslot-default", "transmog-gearslot-transmogrified", "transmog-gearslot-transmogrified-hidden",
    "transmog-gearslot-pending", "transmog-gearslot-selected", "transmog-gearslot-transmogrified-glw",
    "transmog-gearslot-transmogrified-hl", "transmog-icon-hidden", "transmog-icon-tick",
) + tuple("transmog-gearslot-unassigned-" + s for s in SLOTS) + tuple("transmog-appearance-unassigned-" + s for s in SLOTS) + (
    # ClearAllPendingButton
    "common-button-tertiary-square-normal", "common-button-tertiary-square-pressed", "common-icon-undo",
    # the wardrobe column: its frame, tabs (TabSystemTopButtonTemplate: uiframe tabs turned over), title line
    "transmog-tabs-frame-bg", "transmog-tabs-frame", "transmog-tab-hl", "transmog-tabs-header-line",
    "uiframe-tab-left", "uiframe-tab-right", "_uiframe-tab-center",
    "uiframe-activetab-left", "uiframe-activetab-right", "_uiframe-activetab-center",
    # the display type buttons (DisplayTypeButtonTemplate)
    "common-button-tertiary-normal", "common-button-tertiary-pressed", "common-button-tertiary-depressed-normal",
    "common-button-tertiary-depressed-normal-purple", "transmog-appearance-circframe", "transmog-appearance-circframe-active",
    # the cards (TransmogItemModelTemplate, TransmogSetBaseModelTemplate)
    "transmog-itemcard-default", "transmog-itemcard-transmogrified", "transmog-itemcard-transmogrified-pending",
    "transmog-setcard-default", "transmog-setcard-transmogrified", "transmog-setcard-transmogrified-pending",
    # SearchBoxNineSliceTemplate, WowStyle1FilterDropdownTemplate, MinimalCheckboxArtTemplate
    "common-searchbar-a", "common-searchbar-icon-a", "common-search-clearbutton",
    "common-dropdown-b-button", "common-dropdown-b-button-pressed", "common-dropdown-b-button-open",
    "checkbox-minimal", "checkmark-minimal",
    # GameTooltip (NineSliceLayouts TooltipDefaultLayout)
    "tooltip-nineslice-center", "tooltip-nineslice-cornertopleft", "tooltip-nineslice-cornertopright",
    "tooltip-nineslice-cornerbottomleft", "tooltip-nineslice-cornerbottomright",
    "_tooltip-nineslice-edgetop", "_tooltip-nineslice-edgebottom", "!tooltip-nineslice-edgeleft", "!tooltip-nineslice-edgeright",
)

# PagingControlsPrevPageButtonTemplate / NextPage: plain files in Interface\Buttons, 32 x 32
FILES_FOLDER = "interface\\buttons\\"
FILES = {"page-prev": "ui-spellbookicon-prevpage-up.blp", "page-prev-down": "ui-spellbookicon-prevpage-down.blp",
         "page-prev-disabled": "ui-spellbookicon-prevpage-disabled.blp",
         "page-next": "ui-spellbookicon-nextpage-up.blp", "page-next-down": "ui-spellbookicon-nextpage-down.blp",
         "page-next-disabled": "ui-spellbookicon-nextpage-disabled.blp"}
FILE_SIZE = [32, 32]


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


def logical_size(m):
    """The size the game lays a piece out at: its override; else its member's Width x Height, halved for a member that is
    only the 2x art ("-2x" in its committed name: the metal frame's corners and edges have no 1x member)."""
    w, h = int(m["OverrideWidth"]), int(m["OverrideHeight"])
    if w and h:
        return [w, h]
    w, h = int(m["Width"]), int(m["Height"])
    if m["CommittedName"].lower().endswith("-2x"):
        return [max(1, round(w / 2)), max(1, round(h / 2))]
    return [w, h]


def pieces(csv_dir, interface_ids):
    sheets = {r["ID"]: r for r in rows(csv_dir, "UiTextureAtlas")}
    elements = {r["ID"]: r["Name"] for r in rows(csv_dir, "UiTextureAtlasElement")}
    slices = {r["UiTextureAtlasElementID"]: [int(r[k]) for k in ("Left", "Top", "Right", "Bottom")]
              for r in rows(csv_dir, "UiTextureAtlasElementSliceData")}
    members = {}
    for m in rows(csv_dir, "UiTextureAtlasMember"):
        name = (elements.get(m["UiTextureAtlasElementID"]) or m["CommittedName"]).lower()
        if name not in PIECES:
            continue
        box = [int(m[k]) for k in ("CommittedLeft", "CommittedTop", "CommittedRight", "CommittedBottom")]
        if box[2] <= box[0] or box[3] <= box[1] or m["UiTextureAtlasID"] not in sheets:
            continue
        members.setdefault(name, []).append((m, box))
    missing = [n for n in PIECES if n not in members]
    if missing:
        raise RuntimeError("not in the atlas tables at %s: %s" % (BUILD, ", ".join(missing)))
    out, used = {}, {}
    for name in PIECES:
        ms = members[name]
        big_m, big_box = max(ms, key=lambda mb: (mb[1][2] - mb[1][0]) * (mb[1][3] - mb[1][1]))
        small_m, _ = min(ms, key=lambda mb: int(mb[0]["Width"]) * int(mb[0]["Height"]))
        sheet = sheets[big_m["UiTextureAtlasID"]]
        fid = int(sheet["FileDataID"])
        if fid not in interface_ids:
            raise RuntimeError("%s: its sheet %d is not in ManifestInterfaceData at %s" % (name, fid, BUILD))
        piece = {"f": fid, "b": big_box, "s": logical_size(small_m)}
        nine = slices.get(big_m["UiTextureAtlasElementID"])
        if nine:
            piece["n"] = nine
        out[name] = piece
        used[str(fid)] = [int(sheet["AtlasWidth"]), int(sheet["AtlasHeight"])]
    return dict(sorted(out.items())), dict(sorted(used.items(), key=lambda kv: int(kv[0])))


def files(csv_dir):
    found = {}
    for r in rows(csv_dir, "ManifestInterfaceData"):
        if r["FilePath"].lower() == FILES_FOLDER:
            found[r["FileName"].lower()] = int(r["ID"])
    out = {}
    for name, file in FILES.items():
        if file not in found:
            raise RuntimeError("%s%s is not in ManifestInterfaceData" % (FILES_FOLDER, file))
        out[name] = {"f": found[file], "s": FILE_SIZE}
    return out


def make(csv_dir, out):
    ids = {int(r["ID"]) for r in rows(csv_dir, "ManifestInterfaceData")}
    p, sheets = pieces(csv_dir, ids)
    doc = {"build": BUILD, "sheets": sheets, "pieces": p, "files": files(csv_dir)}
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, separators=(",", ":"))
        f.write("\n")
    print("%d pieces on %d sheets, %d files -> %s (%d bytes)" % (len(p), len(sheets), len(doc["files"]), out, os.path.getsize(out)))


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    default = os.path.join(here, "..", "android", "app", "src", "main", "assets", "transmog_art.json")
    if len(argv) == 3 and argv[1] == "fetch":
        fetch(argv[2])
        return 0
    if len(argv) in (3, 4) and argv[1] == "make":
        make(argv[2], argv[3] if len(argv) == 4 else os.path.normpath(default))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
