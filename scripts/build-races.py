#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Builds the World of Warcraft app's custom races package from mod-worgoblin-high-elf's modpaks.

The modpaks change the game's DBC tables with SQL: each table is loaded into MySQL from the stock
DBC files, the modpaks' SQL runs over them in the manifest's order (with the shared variables
config.sql and each modpak's variables.sql work out, computed once, as the module's own
tools/scripts/build-dbc.sh does), and the changed tables are written back as DBC files. The tables'
columns are Thorium's DBC schemas (internal/dbc/meta), which the modpaks' SQL is written for. The
world database's SQL (each modpak's sql/db-world/base) is written out with the same variables in
front of each file, for the phone's server to run.

Output (--out):
  dbc/<Table>.dbc        the changed tables, for the server's data and the client's patch
  world.sql              the world database's changes
  races.txt              the playable races: "<id> <alliance|horde> <name>" a line

  build-races.py --modpaks DIR --manifest FILE --meta DIR --dbc DIR --out DIR [--selftest]

Needs a MariaDB/MySQL server (--host, --port, --user, --password) and the mariadb (or mysql)
command line client, and Python's pymysql.
"""

import argparse
import glob
import json
import os
import re
import shutil
import struct
import subprocess
import sys
from decimal import Decimal

import pymysql

LOC_LANGS = ["enus", "kokr", "frfr", "dede", "zhcn", "zhtw", "eses", "esmx", "ruru", "jajp", "ptpt", "itit",
             "unused_1", "unused_2", "unused_3", "unused_4"]
DBC_DB = "dbc"
WORLD_DB = "acore_world"


# The tables' shapes --------------------------------------------------------------------------------

def load_metas(meta_dir):
    """Thorium's schemas by table name (achievement_criteria, chrraces...)."""
    metas = {}
    for path in sorted(glob.glob(os.path.join(meta_dir, "*.meta.json"))):
        table = os.path.basename(path)[: -len(".meta.json")]
        with open(path, encoding="utf-8") as f:
            metas[table] = json.load(f)
    return metas


def normalize(name):
    """AchievementCriteria.dbc, achievement_criteria -> achievementcriteria (as build-dbc.sh compares them)."""
    name = os.path.basename(name)
    if name.lower().endswith(".dbc"):
        name = name[:-4]
    return name.lower().replace("_", "")


def columns(meta):
    """Each field's columns: (column, type), Loc fields as 16 strings and their flags."""
    out = []
    for field in meta["fields"]:
        count = field.get("count", 0) or 0
        for i in range(max(count, 1)):
            name = f"{field['name']}_{i + 1}" if count > 1 else field["name"]
            if field["type"] == "Loc":
                out += [(f"{name}_{lang}", "string") for lang in LOC_LANGS]
                out.append((f"{name}_flags", "uint32"))
            else:
                out.append((name, field["type"]))
    return out


SQL_TYPES = {"int32": "INT", "uint32": "INT UNSIGNED", "uint8": "TINYINT UNSIGNED", "int8": "TINYINT",
             "float": "DECIMAL(38,16)", "string": "TEXT"}
SIZES = {"int32": 4, "uint32": 4, "float": 4, "string": 4, "uint8": 1, "int8": 1}
FORMATS = {"int32": "<i", "uint32": "<I", "float": "<f", "string": "<I", "uint8": "<B", "int8": "<b"}


def create_table(cursor, table, meta):
    cols = columns(meta)
    names = {name for name, _ in cols}
    defs = [f"`{name}` {SQL_TYPES[kind]}" for name, kind in cols]
    keys = [k for k in meta.get("primaryKeys", []) if k in names]
    if not keys:
        defs.insert(0, "`auto_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT")
        keys = ["auto_id"]
    sql = f"CREATE TABLE `{table}` ({', '.join(defs)}, PRIMARY KEY({', '.join(f'`{k}`' for k in keys)})"
    for i, unique in enumerate(meta.get("uniqueKeys", [])):
        sql += f", UNIQUE KEY `uk_{i}` ({', '.join(f'`{c}`' for c in unique)})"
    cursor.execute(sql + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")


# DBC files -----------------------------------------------------------------------------------------

def read_dbc(path, meta):
    """The records of a DBC file as lists of column values (strings as text)."""
    with open(path, "rb") as f:
        data = f.read()
    magic, count, fields, size, string_size = struct.unpack("<4s4I", data[:20])
    if magic != b"WDBC":
        raise ValueError(f"{path}: not a DBC")
    cols = columns(meta)
    expected = sum(SIZES[kind] for _, kind in cols)
    if expected != size:
        raise ValueError(f"{path}: records are {size} bytes, the schema says {expected}")
    strings = data[20 + count * size: 20 + count * size + string_size]

    def text(offset):
        end = strings.index(b"\0", offset)
        return strings[offset:end].decode("utf-8", errors="replace")

    rows = []
    for r in range(count):
        p = 20 + r * size
        row = []
        for name, kind in cols:
            (value,) = struct.unpack_from(FORMATS[kind], data, p)
            p += SIZES[kind]
            row.append(text(value) if kind == "string" else value)
        rows.append(row)
    return rows


def write_dbc(path, meta, rows):
    cols = columns(meta)
    size = sum(SIZES[kind] for _, kind in cols)
    block = bytearray(b"\0")
    offsets = {"": 0}
    records = bytearray()
    for row in rows:
        for (name, kind), value in zip(cols, row):
            if kind == "string":
                value = value or ""
                if value not in offsets:
                    offsets[value] = len(block)
                    block += value.encode("utf-8") + b"\0"
                records += struct.pack("<I", offsets[value])
            elif kind == "float":
                records += struct.pack("<f", float(value or 0))
            else:
                records += struct.pack(FORMATS[kind], int(value or 0))
    header = struct.pack("<4s4I", b"WDBC", len(rows), len(cols), size, len(block))
    with open(path, "wb") as f:
        f.write(header + records + bytes(block))


def import_table(conn, table, meta, path):
    rows = read_dbc(path, meta)
    with conn.cursor() as cursor:
        create_table(cursor, table, meta)
        cols = columns(meta)
        sql = f"INSERT INTO `{table}` ({', '.join(f'`{n}`' for n, _ in cols)}) VALUES ({', '.join(['%s'] * len(cols))})"
        for i in range(0, len(rows), 2000):
            cursor.executemany(sql, rows[i:i + 2000])
    conn.commit()
    return len(rows)


def export_table(conn, table, meta, path):
    cols = columns(meta)
    order = ", ".join(f"`{o['name']}` {o.get('direction', 'ASC')}" for o in meta.get("sortOrder", []))
    sql = f"SELECT {', '.join(f'`{n}`' for n, _ in cols)} FROM `{table}`" + (f" ORDER BY {order}" if order else "")
    with conn.cursor() as cursor:
        cursor.execute(sql)
        rows = [[float(v) if isinstance(v, Decimal) else v for v in row] for row in cursor.fetchall()]
    write_dbc(path, meta, rows)
    return len(rows)


# SQL -------------------------------------------------------------------------------------------------

class Server:
    def __init__(self, args):
        self.args = args
        self.client = shutil.which("mariadb") or shutil.which("mysql")
        if not self.client:
            sys.exit("needs the mariadb or mysql command line client")

    def connect(self, database=None):
        a = self.args
        return pymysql.connect(host=a.host, port=a.port, user=a.user, password=a.password, database=database,
                               charset="utf8mb4", autocommit=False)

    def script(self, sql, database=None, batch=False):
        """Runs [sql] the way build-dbc.sh does: through the command line client, in one connection."""
        a = self.args
        cmd = [self.client, f"-h{a.host}", f"-P{a.port}", f"-u{a.user}", "--default-character-set=utf8mb4"]
        if batch:
            cmd += ["-N", "-B"]
        if database:
            cmd.append(database)
        env = dict(os.environ, MYSQL_PWD=a.password or "")
        result = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True, env=env)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        return result.stdout.decode("utf-8", errors="replace")


def placeholders(text):
    return text.replace("dbc_db", DBC_DB).replace("world_db", WORLD_DB)


# Fixes to the modpaks' SQL, by the end of the file's path: (old, new), each found exactly once, or
# (old, new, ALL), found at least once and replaced everywhere. The checks at the end (check_races)
# find what these put right.
ALL = "all"
FIXES = {
    # The Worgen mounts: the DELETE names the swift mount's spell, not its item, so the INSERT of
    # both items fails on the item already there (and the first item stays deleted).
    "F-024_mod-playable-worgen/sql/db-world/base/[BASE,F-024]_item_template.sql": [
        ("WHERE `entry` IN (@MountainHorseItem, @SwiftMountainHorse);",
         "WHERE `entry` IN (@MountainHorseItem, @SwiftMountainHorseItem);"),
    ],
    # The Goblin's model and skins are the module's client files' Character\Goblin (which take the
    # place of the game's goblin); there's no Character\GoblinPC, so the Goblin was a missing model
    # (the game's error cube).
    "F-023_mod-playable-goblin/variables.sql": [("GoblinPC", "Goblin", ALL)],
    "F-023_mod-playable-goblin/dbc/[BASE,F-023]_charsections.sql": [("GoblinPC", "Goblin", ALL)],
    # The Ogre's and the Mag'har's looks are their displays (CreatureDisplayInfo), not their models
    # (CreatureModelData): the Ogre's model numbers are other creatures' displays (a dwarf woman and
    # a human man).
    "F-014_mod-playable-ogres/dbc/[BASE,F-014]_chrraces.sql": [
        ("@OgreExplorationSound, @OgreMaleModel, @OgreFemaleModel,",
         "@OgreExplorationSound, @OgreMaleDisplay, @OgreFemaleDisplay,"),
    ],
    "F-007_mod-maghar/dbc/[BASE,F-007]_chrraces.sql": [
        ("@MagharOrcExplorationSound, @MagharOrcMaleModel, @MagharOrcFemaleModel,",
         "@MagharOrcExplorationSound, @MagharOrcMaleDisplay, @MagharOrcFemaleDisplay,"),
    ],
    # The Worgen's facial hair is in the module's client files' Character\Worgen\Male (style 0 only;
    # fill_textures gives the others its textures).
    "F-024_mod-playable-worgen/dbc/[BASE,F-024]_charsections.sql": [
        ("'Character\\\\Worgen\\\\FacialLowerHair", "'Character\\\\Worgen\\\\Male\\\\FacialLowerHair", ALL),
        ("'Character\\\\Worgen\\\\FacialUpperHair", "'Character\\\\Worgen\\\\Male\\\\FacialUpperHair", ALL),
    ],
    # The Pandaren's skins' paths are written with their backslashes escaped twice, which leaves
    # two in each path.
    "F-020_mod-pandaren-alliance/dbc/[BASE,F-020]_charsections.sql": [("\\\\\\\\", "\\\\", ALL)],
    "F-021_mod-pandaren-horde/dbc/[BASE,F-021]_charsections.sql": [("\\\\\\\\", "\\\\", ALL)],
    # The Alliance Pandaren's side went where its file string goes (so it was "0", which the
    # character screen knows nothing of) and its side was always Alliance.
    "F-020_mod-pandaren-alliance/dbc/[BASE,F-020]_chrraces.sql": [
        ("1096, @AlliancePandarenAlliance, @HumanCinematicSequence, 0,",
         "1096, @AlliancePandarenClientString, @HumanCinematicSequence, @AlliancePandarenAlliance,"),
    ],
}


# The app's own changes to the tables, after the modpaks' (with their variables).
OWN_SQL = """
-- Broken women: the game's Broken woman (CreatureModelData 2368) was never finished: she has no
-- head, and none of the game's creatures use her; the pack gives her the Draenei woman's hair and
-- horns, which her model hasn't got. They're the Draenei woman: her model (on the Broken woman's
-- display, which no creature uses), skins, faces, hair and horns.
UPDATE `creaturedisplayinfo` SET `model_id` = (
    SELECT `model_id` FROM (SELECT `d`.`model_id` FROM `creaturedisplayinfo` `d` JOIN `chrraces` `r`
        ON `d`.`id` = `r`.`female_display_id` WHERE `r`.`id` = @Draenei) AS `draenei`)
WHERE `id` = (SELECT `female_display_id` FROM `chrraces` WHERE `id` = @Broken);
DELETE FROM `charsections` WHERE `race` = @Broken AND `gender` = @Female;
SET @Next = (SELECT MAX(`id`) FROM `charsections`);
INSERT INTO `charsections` (`id`, `race`, `gender`, `base_section`, `texture_1`, `texture_2`, `texture_3`, `flags`, `type`, `color_index`)
    SELECT @Next := @Next + 1, @Broken, `gender`, `base_section`, `texture_1`, `texture_2`, `texture_3`, `flags`, `type`, `color_index`
    FROM `charsections` WHERE `race` = @Draenei AND `gender` = @Female ORDER BY `id`;
DELETE FROM `charhairgeosets` WHERE `race` = @Broken AND `gender` = @Female;
SET @Next = (SELECT MAX(`id`) FROM `charhairgeosets`);
INSERT INTO `charhairgeosets` (`id`, `race`, `gender`, `variation`, `geoset`, `show_scalp`)
    SELECT @Next := @Next + 1, @Broken, `gender`, `variation`, `geoset`, `show_scalp`
    FROM `charhairgeosets` WHERE `race` = @Draenei AND `gender` = @Female ORDER BY `id`;
DELETE FROM `characterfacialhairstyles` WHERE `race` = @Broken AND `gender` = @Female;
INSERT INTO `characterfacialhairstyles` (`race`, `gender`, `variation_id`, `geoset_1`, `geoset_2`, `geoset_3`, `geoset_4`, `geoset_5`)
    SELECT @Broken, `gender`, `variation_id`, `geoset_1`, `geoset_2`, `geoset_3`, `geoset_4`, `geoset_5`
    FROM `characterfacialhairstyles` WHERE `race` = @Draenei AND `gender` = @Female ORDER BY `variation_id`;
"""


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    where = path.replace(os.sep, "/")
    for key, fixes in FIXES.items():
        if not where.endswith("/" + key):
            continue
        for fix in fixes:
            old, new = fix[0], fix[1]
            count = text.count(old)
            if (count < 1) if len(fix) > 2 else (count != 1):
                sys.exit(f"{path}: the fix for {old!r} no longer fits")
            text = text.replace(old, new)
    return text


def load_manifest(path, modpaks):
    dirs = []
    for number, line in enumerate(read(path).splitlines(), 1):
        name = line.split("#", 1)[0].strip()
        if not name:
            continue
        directory = os.path.join(modpaks, name)
        if not os.path.isdir(directory):
            sys.exit(f"{path} line {number}: no modpak {name}")
        dirs.append(directory)
    return dirs


def modpak_files(dirs, pattern):
    """Files matching [pattern] (one path segment per glob part) in each modpak, modpak then name order."""
    out = []
    for directory in dirs:
        out += sorted(glob.glob(os.path.join(directory, pattern)), key=lambda p: os.path.basename(p).encode())
    return out


VARIABLE = re.compile(r"SET\s+@([A-Za-z_][A-Za-z0-9_]*)\s*=|@([A-Za-z_][A-Za-z0-9_]*)\s*:=", re.IGNORECASE)
BATCH_ESCAPES = {"\\\\": "\\", "\\t": "\t", "\\n": "\n", "\\0": "\0"}


def unbatch(value):
    """A value as the command line client's batch output (-B) escapes it, as it was."""
    return re.sub(r"\\[\\tn0]", lambda m: BATCH_ESCAPES[m.group(0)], value)


def sql_string(value):
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\0", "\\0") + "'"


def snapshot(server, modpaks, dirs):
    """Every shared variable's final value, as literal SET statements (build-dbc.sh's snapshot)."""
    files = [p for p in [os.path.join(modpaks, "config.sql")] if os.path.isfile(p)]
    files += [p for p in (os.path.join(d, "variables.sql") for d in dirs) if os.path.isfile(p)]
    names = sorted({a or b for f in files for a, b in VARIABLE.findall(read(f))})
    sql = "".join(f"-- ==== {f} ====\n{placeholders(read(f))}\n" for f in files)
    sql += "SELECT " + ", ".join(f"@{n}" for n in names) + ";\n"
    # Batch output escapes backslashes (a model's path), tabs and new lines; NULL is bare.
    values = [None if v == "NULL" else unbatch(v)
              for v in server.script(sql, batch=True).rstrip("\n").split("\n")[-1].split("\t")]
    if len(values) != len(names):
        sys.exit(f"the variables: {len(names)} asked for, {len(values)} came back")
    out = []
    for name, value in zip(names, values):
        out.append(f"SET @{name} = NULL;" if value is None else f"SET @{name} = {sql_string(value)};")
    return "\n".join(out) + "\n", dict(zip(names, values))


# Checks ---------------------------------------------------------------------------------------------

def client_path(path):
    """A game file's path as the game looks it up: backslashes, any case, a model as its .m2."""
    path = path.replace("/", "\\").lower()
    return re.sub(r"\.(mdx|mdl)$", ".m2", path)


class ClientFiles:
    """The files the client has: the module's client files the package ships (--client, less the
    --client-exclude ones) and the game's own, as far as the stock tables name them (their models
    and their skins)."""

    def __init__(self, args, metas, stock):
        excluded = [word.lower() for word in args.client_exclude.split(",") if word]
        self.files = set()
        for folder, _, names in os.walk(args.client):
            for file in names:
                rel = os.path.relpath(os.path.join(folder, file), args.client)
                if not any(word in rel.lower() for word in excluded):
                    self.files.add(client_path(rel))

        def stock_table(name):
            meta = metas[name]
            cols = [c for c, _ in columns(meta)]
            return [dict(zip(cols, row)) for row in read_dbc(stock[normalize(meta.get("file", name))], meta)]

        self.files |= {client_path(r["model_path"]) for r in stock_table("creaturemodeldata")}
        self.files |= {client_path(r[f"texture_{i}"]) for r in stock_table("charsections") for i in (1, 2, 3)}

    def __contains__(self, path):
        return client_path(path) in self.files


SKIN, UNDERWEAR = 0, 4  # CharSections' base sections (1 face, 2 facial hair, 3 hair)


def fill_textures(conn, known):
    """The playable races' skins, faces, hair and underwear (CharSections) whose textures the client
    hasn't got take the race's nearest that it has: the same part, style and colour where there is
    one, then the same style, then the same colour, then any; a second or third texture with none
    to take is left out (they're extras the client draws only when there)."""
    with conn.cursor() as cursor:
        cursor.execute("SELECT id, name_neutral_enus FROM chrraces WHERE (flags & 1) = 0")
        names = dict(cursor.fetchall())
        cursor.execute("SELECT id, race, gender, base_section, type, color_index, texture_1, texture_2, texture_3 "
                       f"FROM charsections WHERE race IN ({', '.join(str(r) for r in names)})")
        rows = [dict(zip(("id", "race", "gender", "section", "type", "color", 1, 2, 3), r)) for r in cursor.fetchall()]
    groups = {}
    for row in rows:
        groups.setdefault((row["race"], row["gender"], row["section"]), []).append(row)
    changed, counts = {}, {}
    for row in rows:
        for i in (1, 2, 3):
            if not row[i] or row[i] in known:
                continue
            donors = [d for d in groups[(row["race"], row["gender"], row["section"])] if d[i] and d[i] in known]
            if not donors and row["section"] == UNDERWEAR and i == 1:
                # No underwear at all: the skin of its colour, as the game's own Broken woman has.
                donors = [d for d in groups.get((row["race"], row["gender"], SKIN), []) if d[1] and d[1] in known]
            rank = lambda d: (d["type"] != row["type"], d["color"] != row["color"], d["type"], d["color"])
            donor = min(donors, key=rank, default=None)
            if not donor and i == 1:
                continue  # check_races reports it
            row[i] = donor[i] if donor else ""
            changed[row["id"]] = row
            counts[row["race"]] = counts.get(row["race"], 0) + 1
    with conn.cursor() as cursor:
        for row in changed.values():
            cursor.execute("UPDATE charsections SET texture_1 = %s, texture_2 = %s, texture_3 = %s WHERE id = %s",
                           (row[1], row[2], row[3], row["id"]))
    conn.commit()
    for race, count in sorted(counts.items()):
        print(f"race {race} ({names[race]}): {count} textures the client hasn't got, the race's nearest in their place",
              flush=True)


def check_races(args, metas, written, stock, known):
    """What the client needs of each playable race, from the built tables (stock where unchanged):
    a file string the character screen knows, displays whose models are files the client has (the
    module's client files or the game's own) and are the race's, skins whose textures it has,
    classes, and the doubled backslashes a wrong escape leaves. Returns the problems."""

    def table(name):
        meta = metas[name]
        path = written.get(name) or stock.get(normalize(meta.get("file", name)))
        cols = [c for c, _ in columns(meta)]
        return [dict(zip(cols, row)) for row in read_dbc(path, meta)]

    problems = []
    for name, path in sorted(written.items()):
        game = {v for row in read_dbc(stock[normalize(metas[name].get("file", name))], metas[name])
                for v in row if isinstance(v, str)}
        for row in read_dbc(path, metas[name]):
            for value in row:
                if isinstance(value, str) and "\\\\" in value and value not in game:
                    problems.append(f"{os.path.basename(path)}: doubled backslashes in {value!r}")
                    break

    races = [r for r in table("chrraces") if not r["flags"] & 1]
    displays = {r["id"]: r for r in table("creaturedisplayinfo")}
    models = {r["id"]: r for r in table("creaturemodeldata")}
    sections = table("charsections")
    extras = table("creaturedisplayinfoextra")
    bases = {r["race"] for r in table("charbaseinfo")}
    seen = {}
    missing_textures = {}
    for race in races:
        rid, fs = race["id"], race["client_filestring"]
        who = f"race {rid} ({race['name_neutral_enus']})"
        if not re.fullmatch(r"[A-Za-z]+", fs or ""):
            problems.append(f"{who}: file string {fs!r}")
        elif fs.upper() in seen:
            problems.append(f"{who}: file string {fs!r} is race {seen[fs.upper()]}'s too")
        else:
            seen[fs.upper()] = rid
        if not re.fullmatch(r"[A-Za-z]+", race["client_prefix"] or ""):
            problems.append(f"{who}: prefix {race['client_prefix']!r}")
        if rid not in bases:
            problems.append(f"{who}: no classes (CharBaseInfo)")
        for gender, column in ((0, "male_display_id"), (1, "female_display_id")):
            display = displays.get(race[column])
            model = models.get(display["model_id"]) if display else None
            if not display:
                problems.append(f"{who}: {column} {race[column]} isn't a display")
            elif not model:
                problems.append(f"{who}: display {display['id']}'s model {display['model_id']} isn't a model")
            elif model["model_path"] not in known:
                problems.append(f"{who}: display {display['id']}'s model {model['model_path']!r} isn't a file")
            elif not client_path(model["model_path"]).startswith("character\\") or \
                    ("female" in client_path(model["model_path"])) != (gender == 1):
                problems.append(f"{who}: {column} {display['id']} is {model['model_path']!r}")
            extra = display and display["extended_display_info_id"]
            if extra and not any(e["id"] == extra and e["race"] == rid and e["gender"] == gender for e in extras):
                problems.append(f"{who}: display {display['id']}'s gear look {extra} isn't this race's")
            mine = [s for s in sections if s["race"] == rid and s["gender"] == gender]
            if not any(s["base_section"] == 0 for s in mine):
                problems.append(f"{who}: no {'male' if gender == 0 else 'female'} skins (CharSections)")
            for s in mine:
                for i in (1, 2, 3):
                    texture = s[f"texture_{i}"]
                    if texture and texture not in known:
                        missing_textures[texture] = who
    problems += [f"{who}: texture {texture!r} isn't a file" for texture, who in sorted(missing_textures.items())]

    # The character screen (warcraft/races/client/RaceScreen.lua over the module's GlueXML).
    if args.glue:
        with open(args.glue, encoding="utf-8") as f:
            glue = f.read()
        with open(os.path.join(args.client, "Interface", "GlueXML", "GlueStrings.lua"), encoding="utf-8") as f:
            strings = f.read()
        listed = {}
        for name, side, column in re.findall(r'\{\s*"([A-Z]+)",\s*"([AH])",\s*(\d+)\s*\}', glue):
            if (side, column) in listed.values():
                problems.append(f"{args.glue}: {name} has another race's icon")
            listed[name] = (side, column)
        sides = [side for side, _ in listed.values()]
        if sides.count("A") > 20 or sides.count("H") > 19:
            problems.append(f"{args.glue}: more races than buttons")
        for race in races:
            fs = (race["client_filestring"] or "").upper()
            want = "A" if race["alliance"] == 0 else "H"
            if fs not in listed:
                problems.append(f"{args.glue}: no {fs} (race {race['id']})")
            elif listed[fs][0] != want:
                problems.append(f"{args.glue}: {fs} on the wrong side")
            text = re.compile(rf"^RACE_INFO_{fs}\s*=", re.MULTILINE)
            if not text.search(strings) and not text.search(glue):
                problems.append(f"no RACE_INFO_{fs}: choosing the race stops the character screen")
    return problems


USED = re.compile(r"@([A-Za-z_][A-Za-z0-9_]*)")
TABLES = re.compile(r"(?:DELETE\s+FROM|INSERT\s+INTO|UPDATE)\s+(?:`[^`]+`\.)?`([^`]+)`", re.IGNORECASE)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--modpaks", required=True)
    p.add_argument("--manifest", required=True)
    p.add_argument("--meta", required=True, action="append", help="Thorium's schemas, then the app's for the tables Thorium lacks")
    p.add_argument("--dbc", required=True, help="the stock DBC files")
    p.add_argument("--out", required=True)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--user", default="root")
    p.add_argument("--password", default="")
    p.add_argument("--selftest", action="store_true", help="check that unchanged tables come back as they were")
    p.add_argument("--client", help="the client files the package ships (the module's patch-A.MPQ): check the races against them")
    p.add_argument("--client-exclude", default="", help="words of the paths of client files left out, comma-separated")
    p.add_argument("--glue", help="the character screen's races (RaceScreen.lua): check them with --client")
    args = p.parse_args()

    metas = {}
    for directory in args.meta:
        metas.update(load_metas(directory))
    stock = {normalize(f): f for f in glob.glob(os.path.join(args.dbc, "*.dbc"))}
    dirs = load_manifest(args.manifest, args.modpaks)
    server = Server(args)
    os.makedirs(os.path.join(args.out, "dbc"), exist_ok=True)

    # The DBC database: every table there's a schema and a DBC file for.
    server.script(f"DROP DATABASE IF EXISTS `{DBC_DB}`; CREATE DATABASE `{DBC_DB}` CHARACTER SET utf8mb4;")
    conn = server.connect(DBC_DB)
    loaded = {}
    for table, meta in metas.items():
        path = stock.get(normalize(meta.get("file", table)))
        if path:
            loaded[table] = path
            count = import_table(conn, table, meta, path)
            print(f"loaded {table}: {count} records", flush=True)

    if args.selftest:
        for table, path in loaded.items():
            out = os.path.join(args.out, "selftest.dbc")
            export_table(conn, table, metas[table], out)
            before = read_dbc(path, metas[table])
            after = read_dbc(out, metas[table])
            key = lambda row: [str(v) for v in row]
            if sorted(map(key, before)) != sorted(map(key, after)):
                sys.exit(f"selftest: {table} doesn't come back as it was")
            os.remove(out)
        print(f"selftest: {len(loaded)} tables come back as they were", flush=True)

    variables, values = snapshot(server, args.modpaks, dirs)
    # A race left out of the manifest leaves its mask undefined (NULL), which turns a whole race
    # mask NULL; left out, its mask is no races.
    sql_files = modpak_files(dirs, "dbc/*.sql") + modpak_files(dirs, "sql/db-world/base/*.sql")
    used = set()
    assigned = set(values)
    for path in sql_files:
        text = read(path)
        used.update(USED.findall(text))
        assigned.update(a or b for a, b in VARIABLE.findall(text))
    undefined = sorted(used - assigned)
    masks = [name for name in undefined if name.endswith("Mask")]
    variables += "".join(f"SET @{name} = 0;\n" for name in masks)
    if masks:
        print(f"masks of races not in the manifest, 0: {', '.join(masks)}", flush=True)
    others = [name for name in undefined if not name.endswith("Mask")]
    if others:
        print(f"variables used but never set (NULL): {', '.join(others)}", flush=True)
    with open(os.path.join(args.out, "variables.sql"), "w", encoding="utf-8") as f:
        f.write(variables)

    # The modpaks' DBC changes, each file in its own connection after the variables.
    dbc_files = modpak_files(dirs, "dbc/*.sql")
    affected = set()
    for path in dbc_files:
        text = read(path)
        affected.update(t.lower() for t in TABLES.findall(text))
        try:
            server.script(placeholders(variables) + f"-- ==== {path} ====\n" + placeholders(text), database=DBC_DB)
        except RuntimeError as e:
            sys.exit(f"{path}:\n{e}")
        print(f"applied {os.path.relpath(path, args.modpaks)}", flush=True)
    try:
        server.script(placeholders(variables) + "-- ==== the app's own ====\n" + OWN_SQL, database=DBC_DB)
    except RuntimeError as e:
        sys.exit(f"the app's own SQL:\n{e}")
    affected.update(t.lower() for t in TABLES.findall(OWN_SQL))
    print("applied the app's own changes", flush=True)
    missing = sorted(t for t in affected if t not in loaded)
    if missing:
        print(f"tables changed with no DBC file to write (helpers): {', '.join(missing)}", flush=True)
    conn.close()
    conn = server.connect(DBC_DB)
    known = ClientFiles(args, metas, stock) if args.client else None
    if known:
        fill_textures(conn, known)
    written = {}
    for table in sorted(t for t in affected if t in loaded):
        name = os.path.basename(loaded[table])
        written[table] = os.path.join(args.out, "dbc", name)
        count = export_table(conn, table, metas[table], written[table])
        print(f"wrote {name}: {count} records", flush=True)

    if known:
        problems = check_races(args, metas, written, stock, known)
        for problem in problems:
            print(f"PROBLEM: {problem}", flush=True)
        if problems:
            sys.exit(f"{len(problems)} problems with the races")
        print("checked: every playable race's file string, models, skins, classes and character screen", flush=True)

    # The races, for the app: the playable ones the races changed.
    with conn.cursor() as cursor:
        cursor.execute("SELECT id, name_neutral_enus, alliance FROM chrraces WHERE (flags & 1) = 0 ORDER BY id")
        races = cursor.fetchall()
    with open(os.path.join(args.out, "races.txt"), "w", encoding="utf-8") as f:
        for race_id, name, alliance in races:
            f.write(f"{race_id} {'alliance' if alliance == 0 else 'horde'} {name}\n")

    # The world SQL reads one DBC table (the races CharBaseInfo has, for totems); the phone's server
    # has no DBC database, so the table's races are written into it.
    with conn.cursor() as cursor:
        cursor.execute("SELECT DISTINCT race FROM charbaseinfo WHERE race IS NOT NULL ORDER BY race")
        bases = [row[0] for row in cursor.fetchall()]
    charbaseinfo = "(" + " UNION ALL ".join(f"SELECT {race} AS `race`" for race in bases) + ") AS `charbaseinfo`"

    # The world database's changes: each file after the variables, as build-dbc.sh applies them.
    with open(os.path.join(args.out, "world.sql"), "w", encoding="utf-8") as f:
        for path in modpak_files(dirs, "sql/db-world/base/*.sql"):
            text = placeholders(read(path)).replace(f"`{DBC_DB}`.`charbaseinfo`", charbaseinfo)
            if f"`{DBC_DB}`." in text:
                sys.exit(f"{path} reads the DBC database, which the phone's server doesn't have")
            f.write(f"-- ==== {os.path.relpath(path, args.modpaks)} ====\n")
            f.write(placeholders(variables))
            f.write(text.rstrip() + "\n\n")
    print("done", flush=True)


if __name__ == "__main__":
    main()
