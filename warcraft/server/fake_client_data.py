#!/usr/bin/env python3
"""Makes stand-in client data for CI, which can't have the client's:

- dbc/: every table the world server loads, empty, in the layout AzerothCore expects, except for
  the one row of each that its version check looks for, and one placeholder row in the tables
  without an id column (which count their records);
- maps/: a header-only map file for every grid of the continents (the world server checks that the
  starting zones' map files exist; with vmaps switched off it doesn't open them further).

They let the world server start up all the way in CI (its "ready" line) with no real game data,
which tests the server's programs, scripts, modules and databases, not the game.

    fake_client_data.py SOURCE OUT_DIR

SOURCE is the AzerothCore source (for DBCfmt.h, DBCStores.cpp and the map file version).
"""
import os
import re
import struct
import sys

# The rows the world server checks for (DBCStores.cpp: "last ... added in 3.3.5a").
VERSION_ROWS = {
    "AreaTable.dbc": 4987,
    "CharTitles.dbc": 177,
    "GemProperties.dbc": 1629,
    "Item.dbc": 56806,
    "ItemExtendedCost.dbc": 2997,
    "Map.dbc": 724,
    "Spell.dbc": 80864,
}


def field_size(c):
    return 1 if c in "bX" else 4


def main():
    source, out = sys.argv[1], os.path.join(sys.argv[2], "dbc")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(source, "src/server/shared/DataStores/DBCfmt.h")) as f:
        formats = dict(re.findall(r'char constexpr (\w+)\[\]\s*=\s*"([^"]*)"', f.read()))
    with open(os.path.join(source, "src/server/game/DataStores/DBCStores.cpp")) as f:
        stores_src = f.read()
    stores = dict(re.findall(r"DBCStorage\s*<\s*\w+\s*>\s*(\w+)\((\w+)\)", stores_src))
    files = re.findall(r'LOAD_DBC\((\w+),\s*"([^"]+\.dbc)"', stores_src)
    for store, name in files:
        fmt = formats[stores[store]]
        record = sum(field_size(c) for c in fmt)
        rows = []
        strings = b"\0"
        if "n" not in fmt and "d" not in fmt:
            # A table without an id column counts its records, and none reads as a missing file:
            # one record, its texts a name nobody types (NamesReserved and NamesProfanity are
            # patterns names are checked against).
            strings = b"\0^stand-in$\0"
            row = bytearray(record)
            offset = 0
            for c in fmt:
                if c == "s":
                    row[offset:offset + 4] = struct.pack("<I", 1)
                offset += field_size(c)
            rows.append(bytes(row))
        elif name in VERSION_ROWS:
            index = fmt.find("n")
            if index < 0:
                index = 0
            offset = sum(field_size(c) for c in fmt[:index])
            row = bytearray(record)
            row[offset:offset + 4] = struct.pack("<I", VERSION_ROWS[name])
            rows.append(bytes(row))
        with open(os.path.join(out, name), "wb") as f:
            f.write(b"WDBC" + struct.pack("<4I", len(rows), len(fmt), record, len(strings)))
            for row in rows:
                f.write(row)
            f.write(strings)
    print(f"{len(files)} stand-in DBC files in {out}")

    # Map files: just the header (magic, version, build; every part empty).
    with open(os.path.join(source, "src/server/game/Grids/GridTerrainData.h")) as f:
        version = int(re.search(r"MapVersionMagic\s*=\s*(\d+)", f.read()).group(1))
    maps = os.path.join(sys.argv[2], "maps")
    os.makedirs(maps, exist_ok=True)
    header = b"MAPS" + struct.pack("<10I", version, 12340, 0, 0, 0, 0, 0, 0, 0, 0)
    count = 0
    for map_id in (0, 1, 530, 571):
        for x in range(64):
            for y in range(64):
                with open(os.path.join(maps, f"{map_id:03}{x:02}{y:02}.map"), "wb") as f:
                    f.write(header)
                count += 1
    print(f"{count} stand-in map files in {maps}")


if __name__ == "__main__":
    main()
