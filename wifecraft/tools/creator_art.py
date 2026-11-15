#!/usr/bin/env python3
"""Which pieces of Shadowlands' character creator's art Wifecraft's creator screen cuts out of the game's sheets on the phone.

    python3 tools/creator_art.py fetch CSV_DIR        the atlas tables (wago.tools' CSVs at build 9.2.7.45745)
    python3 tools/creator_art.py make CSV_DIR [OUT]   writes OUT (default android/app/src/main/assets/creator_art.json)

The list is Wrath++'s own (realm/warcraft/wrathpp/tools/sl_ui_art.py: WANTED and LEFT_OUT, the pieces its
WrathppCreate.lua draws), read from the same UiTextureAtlas* tables. The json holds ids and boxes only (the sheet's file id,
the piece's rectangle left/top/right/bottom in it, the size Shadowlands draws it at); the pictures are fetched from
wago.tools on the phone by id, as WowArt's are, and nothing of the game is committed.

    {"build": "...", "mask": <file id of the round mask>, "masked": {kind: [button w, h, inset]},
     "sheets": {"<file id>": [w, h]}, "pieces": {name: {"f": file id, "b": [l, t, r, b], "s": [w, h]}},
     "files": {name: {"f": file id, "s": [w, h]}}}

"files" are the pictures Shadowlands' creator draws from plain files, not atlas pieces: the option switch's check
box. Wrath++ draws it from Interface\\Buttons\\UI-CheckBox-* (WrathppCreate.lua 1147-1150, 32 x 32); the 9.2.7 atlas
tables have no check box piece (UiTextureAtlasElement checked: only "common-checkbox-partial"), so these files are it.
"""
import csv
import json
import os
import sys

BUILD = "9.2.7.45745"
USER_AGENT = "build-tools/1.0"
TABLES = ("UiTextureAtlas", "UiTextureAtlasMember", "UiTextureAtlasElement", "ManifestInterfaceData")

# sl_ui_art.py's WANTED and LEFT_OUT, as they are.
WANTED = ("charactercreate-", "raceicon128-", "classicon-", "common-icon-", "common-button-square-gray-",
          "common-gray-button-entrybox-", "charactercreatedropdown-nineslice-", "_charactercreatedropdown-nineslice-",
          "!charactercreatedropdown-nineslice-", "common-radiobutton-", "128-redbutton-", "_128-redbutton-",
          "_charactercreate-")
LEFT_OUT = ("charactercreate-startingzone-", "charactercreate-druidform-")
# sl_ui_art.py MASKED: the button's size and how far its round mask is set in from its edge.
MASKED = {"category": [104, 105, 2], "race": [79, 79, 2], "class": [67, 67, 2], "sex": [46, 46, -5]}
MASK = ("interface\\characterframe\\", "tempportraitalphamask.blp")
# The switch's check box (WrathppCreate.lua 1147-1150): name here -> file in Interface\\Buttons, and the size it is drawn at.
FILES_FOLDER = "interface\\buttons\\"
FILES = {"checkbox-up": "ui-checkbox-up.blp", "checkbox-down": "ui-checkbox-down.blp",
         "checkbox-highlight": "ui-checkbox-highlight.blp", "checkbox-check": "ui-checkbox-check.blp",
         "checkbox-check-disabled": "ui-checkbox-check-disabled.blp"}
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


def mask_id(csv_dir):
    for r in rows(csv_dir, "ManifestInterfaceData"):
        if r["FilePath"].lower() == MASK[0] and r["FileName"].lower() == MASK[1]:
            return int(r["ID"])
    raise RuntimeError("the round mask is not in ManifestInterfaceData")


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


def pieces(csv_dir):
    sheets = {r["ID"]: r for r in rows(csv_dir, "UiTextureAtlas")}
    elements = {r["ID"]: r["Name"] for r in rows(csv_dir, "UiTextureAtlasElement")}
    out, used = {}, {}
    for m in rows(csv_dir, "UiTextureAtlasMember"):
        name = (elements.get(m["UiTextureAtlasElementID"]) or m["CommittedName"]).lower()
        if not name.startswith(WANTED) or name.startswith(LEFT_OUT):
            continue
        sheet = sheets[m["UiTextureAtlasID"]]
        box = [int(m[k]) for k in ("CommittedLeft", "CommittedTop", "CommittedRight", "CommittedBottom")]
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        size = [int(m["OverrideWidth"]) or box[2] - box[0], int(m["OverrideHeight"]) or box[3] - box[1]]
        fid = int(sheet["FileDataID"])
        out[name] = {"f": fid, "b": box, "s": size}
        used[str(fid)] = [int(sheet["AtlasWidth"]), int(sheet["AtlasHeight"])]
    return dict(sorted(out.items())), dict(sorted(used.items(), key=lambda kv: int(kv[0])))


def make(csv_dir, out):
    p, sheets = pieces(csv_dir)
    doc = {"build": BUILD, "mask": mask_id(csv_dir), "masked": MASKED, "sheets": sheets, "pieces": p, "files": files(csv_dir)}
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, separators=(",", ":"), sort_keys=False)
        f.write("\n")
    print("%d pieces on %d sheets, mask file %d -> %s (%d bytes)" % (len(p), len(sheets), doc["mask"], out, os.path.getsize(out)))


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    default = os.path.join(here, "..", "android", "app", "src", "main", "assets", "creator_art.json")
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
