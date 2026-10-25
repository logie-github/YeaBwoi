#!/usr/bin/env python3
"""mod-worgoblin's files for ChromieCraft's realm, made as the server is packaged
(scripts/package-azerothcore.sh), from the module's checkout the compile used: never in this
repository (they're the game's: models, textures, sounds).

    python3 tools/package.py WORGOBLIN_DIR OUT

OUT is the module's data folder in the package (src/modules/mod-worgen-goblin/data). It gets:

  - files/: mod-worgoblin's patch for the game (its data/patch: the Worgen's and Goblins' models,
    textures, sounds, icons, their creation screen's backgrounds), laid out as the game's patch, with
    worgengoblin.files listing it (written last: a package that stops leaves none, and the world
    server then makes no patch). Left out: its 30 whole tables (DBFilesClient: the realm puts what they
    change into the game's own, data/client/dbc), its character screen's and FrameXML's files (the
    game's own, with its own few changes: data/client/Interface/GlueXML/WorgenGoblinCreate.lua does
    them on top of the others'), and its race icons (the game's whole icon sheet with its two races
    in the column the allied races' Lightforged and Zandalari have: theirs are cut out into a sheet of
    their own, UI-CharacterCreate-Worgoblin*.blp). Its race texts (from its GlueStrings.lua, the
    game's whole English text file) become Interface/GlueXML/WorgenGoblinStrings.lua.
  - sql/chromiecraft/world/: its SQL for the world database (the races' starts, stats, gear, action
    bars, skills, racials and quests; its death knights' quests and the goblins' trike vendor), named
    after the module (the servers know an applied file by its name alone, across every module):
    ChromieCraft's alone (ctl.sh's realm_sql), never Wrath Remix's. Its *_dbc SQL isn't used: it
    takes whole rows the allied races' and the Skyborne's SQL also change
    (data/server/worgen_goblin_dbc.sql merges instead).
"""
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(HERE)), "mod-allied-races", "tools"))

MANIFEST = "worgengoblin.files"
LEFT_OUT_FOLDERS = ("dbfilesclient/", "interface/gluexml/", "interface/framexml/")
ICONS = {
    "interface/glues/charactercreate/ui-charactercreate-races.blp": "Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-Worgoblin.blp",
    "interface/glues/charactercreate/ui-charactercreate-racesround.blp": "Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-WorgoblinRound.blp",
}
ICON_COLUMN = 5                      # of 8: Worgen men, Goblin men, Worgen women, Goblin women, top to bottom
SQL = {
    "data/sql/db-world/worgoblin.sql": "worgen_goblin_races.sql",
    "data/sql/db-world/worgoblin_x_druid_quests.sql": "worgen_goblin_druid_quests.sql",
    "data/sql/supplementary/dk-quests.sql": "worgen_goblin_dk_quests.sql",
    "data/sql/supplementary/optional-mount-vendor.sql": "worgen_goblin_trike_vendor.sql",
}
STRINGS = re.compile(r"^(RACE_INFO|ABILITY_INFO)_(WORGEN|GOBLIN)\w*\s*=")


def icon_column(data):
    """The two races' column cut out of a race icon sheet (8 icons a row, 4 rows): a sheet of its own."""
    import blp
    rgba = blp.read(data)
    height, width = rgba.shape[:2]
    cell = width // 8
    column = rgba[:, ICON_COLUMN * cell:(ICON_COLUMN + 1) * cell].copy()
    return blp.write_dxt(column, alpha=True)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    worgoblin, out = sys.argv[1:]
    patch = os.path.join(worgoblin, "data", "patch")
    files = os.path.join(out, "files")
    manifest = os.path.join(files, MANIFEST)
    if os.path.exists(manifest):
        os.remove(manifest)
    listed = []
    for root, dirs, names in os.walk(patch):
        dirs.sort()
        for name in sorted(names):
            src = os.path.join(root, name)
            rel = os.path.relpath(src, patch).replace(os.sep, "/")
            low = rel.lower()
            if low.startswith(LEFT_OUT_FOLDERS):
                continue
            if low in ICONS:
                game = ICONS[low]
                data = icon_column(open(src, "rb").read())
            else:
                game = rel.replace("/", "\\")
                data = None
            dest = os.path.join(files, game.replace("\\", "/"))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if data is None:
                shutil.copyfile(src, dest)
            else:
                with open(dest, "wb") as f:
                    f.write(data)
            listed.append(game)

    # The two races' texts, from its English GlueStrings.lua.
    glue = os.path.join(patch, "Interface", "GlueXML", "GlueStrings.lua")
    lines = [l.rstrip("\r\n") for l in open(glue, encoding="utf-8", errors="replace") if STRINGS.match(l)]
    if not lines:
        sys.exit("mod-worgoblin's GlueStrings.lua has no texts for its races")
    game = "Interface\\GlueXML\\WorgenGoblinStrings.lua"
    dest = os.path.join(files, game.replace("\\", "/"))
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8", newline="\r\n") as f:
        f.write("-- The Worgen's and Goblins' texts on the character creation screen (mod-worgoblin's GlueStrings.lua)\n")
        f.write("\n".join(lines) + "\n")
    listed.append(game)

    # Its SQL, ChromieCraft's world database's alone.
    sql = os.path.join(out, "sql", "chromiecraft", "world")
    os.makedirs(sql, exist_ok=True)
    for src, name in SQL.items():
        shutil.copyfile(os.path.join(worgoblin, src), os.path.join(sql, name))

    with open(manifest + ".part", "w", newline="\n") as f:
        f.write("\n".join(listed) + "\n")
    os.replace(manifest + ".part", manifest)
    print(f"Worgen and Goblins: {len(listed)} files for the game, {len(SQL)} SQL files for ChromieCraft's world")


if __name__ == "__main__":
    main()
