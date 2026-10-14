#!/usr/bin/env python3
"""Checks where Wrath Remix's NPCs stand, from a game master's character on the Remix realm.

    remix_check.py floors HOST USER PASSWORD CHARACTER SPAWNS

SPAWNS is a tab-separated list of the Remix spawns (guid, map, x, y, z, comment), as
`SELECT guid, map, position_x, position_y, position_z, Comment FROM creature WHERE ...` prints
it. The character goes to each one and asks the realm what floor is there (.gps): an NPC more
than 2.5 yards above or below the floor under it is floating or buried. Prints every spawn's
floor and fails if any is off. WORLD_PORT names the realm's port, as for auth_check.py.
"""
import re
import sys

import auth_check

TOLERANCE = 2.5
GPS = re.compile(r"Zone: (\d+).*?GroundZ: (-?[\d.]+) FloorZ: (-?[\d.]+)", re.S)


def floors(host, user, password, character, spawns_file):
    spawns = []
    with open(spawns_file) as f:
        for line in f:
            fields = line.rstrip("\n").split("\t")
            if len(fields) >= 5:
                spawns.append((fields[0], int(fields[1]), float(fields[2]), float(fields[3]), float(fields[4]),
                               fields[5] if len(fields) > 5 else ""))
    steps = []
    for _, map_id, x, y, z, _ in spawns:
        steps += [f"go {map_id} {x:.2f} {y:.2f} {z + 0.5:.2f}", "cmd .gps"]
    transcript = auth_check.world(host, user, password, character, steps=steps)
    gps = [heard for line, heard in transcript if line == "cmd .gps"]
    bad = 0
    for (guid, map_id, x, y, z, comment), heard in zip(spawns, gps):
        match = GPS.search("\n".join(heard))
        if not match:
            print(f"floor: {guid} ({comment}): no answer to .gps")
            bad += 1
            continue
        zone, ground, floor = int(match.group(1)), float(match.group(2)), float(match.group(3))
        off = z - floor
        ok = abs(off) <= TOLERANCE
        bad += 0 if ok else 1
        print(f"floor: {guid} map {map_id} zone {zone} z {z:.1f} floor {floor:.1f} ground {ground:.1f}"
              f" {'ok' if ok else ('FLOATING' if off > 0 else 'BURIED')} ({comment})")
    if len(gps) < len(spawns):
        print(f"floor: only {len(gps)} of {len(spawns)} spawns checked")
        bad += 1
    return bad


def main():
    if len(sys.argv) == 7 and sys.argv[1] == "floors":
        bad = floors(*sys.argv[2:7])
        print(f"floor: {bad} off" if bad else "floor: every spawn stands on the floor")
        sys.exit(1 if bad else 0)
    print(__doc__, file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
