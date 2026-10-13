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


# Fixes to the modpaks' SQL, by file name: (old, new), each found exactly once.
FIXES = {
    # The Worgen mounts: the DELETE names the swift mount's spell, not its item, so the INSERT of
    # both items fails on the item already there (and the first item stays deleted).
    "[BASE,F-024]_item_template.sql": [
        ("WHERE `entry` IN (@MountainHorseItem, @SwiftMountainHorse);",
         "WHERE `entry` IN (@MountainHorseItem, @SwiftMountainHorseItem);"),
    ],
}


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    for old, new in FIXES.get(os.path.basename(path), []):
        if text.count(old) != 1:
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


def snapshot(server, modpaks, dirs):
    """Every shared variable's final value, as literal SET statements (build-dbc.sh's snapshot)."""
    files = [p for p in [os.path.join(modpaks, "config.sql")] if os.path.isfile(p)]
    files += [p for p in (os.path.join(d, "variables.sql") for d in dirs) if os.path.isfile(p)]
    names = sorted({a or b for f in files for a, b in VARIABLE.findall(read(f))})
    sql = "".join(f"-- ==== {f} ====\n{placeholders(read(f))}\n" for f in files)
    sql += "SELECT " + ", ".join(f"@{n}" for n in names) + ";\n"
    values = server.script(sql, batch=True).rstrip("\n").split("\n")[-1].split("\t")
    out = []
    for name, value in zip(names, values):
        if value == "NULL":
            out.append(f"SET @{name} = NULL;")
        else:
            out.append("SET @{} = '{}';".format(name, value.replace("\\", "\\\\").replace("'", "\\'")))
    return "\n".join(out) + "\n", dict(zip(names, values))


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
    missing = sorted(t for t in affected if t not in loaded)
    if missing:
        print(f"tables changed with no DBC file to write (helpers): {', '.join(missing)}", flush=True)
    conn.close()
    conn = server.connect(DBC_DB)
    for table in sorted(t for t in affected if t in loaded):
        name = os.path.basename(loaded[table])
        count = export_table(conn, table, metas[table], os.path.join(args.out, "dbc", name))
        print(f"wrote {name}: {count} records", flush=True)

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
