#!/usr/bin/env python3
"""Makes stand-in DBC files for CI, which can't have the client's: every table the world server
loads, empty, in the layout AzerothCore expects, except for the one row of each that its version
check looks for. They let the world server start up all the way in CI (its "ready" line) with no
real game data, which tests the server's programs, modules and database, not the game.

    fake_dbc.py SOURCE OUT_DIR

SOURCE is the AzerothCore source (for DBCfmt.h and DBCStores.cpp); the files go in OUT_DIR/dbc.
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
        if name in VERSION_ROWS:
            index = fmt.find("n")
            if index < 0:
                index = 0
            offset = sum(field_size(c) for c in fmt[:index])
            row = bytearray(record)
            row[offset:offset + 4] = struct.pack("<I", VERSION_ROWS[name])
            rows.append(bytes(row))
        strings = b"\0"
        with open(os.path.join(out, name), "wb") as f:
            f.write(b"WDBC" + struct.pack("<4I", len(rows), len(fmt), record, len(strings)))
            for row in rows:
                f.write(row)
            f.write(strings)
    print(f"{len(files)} stand-in DBC files in {out}")


if __name__ == "__main__":
    main()
