#!/usr/bin/env python3
"""Lists the game files Wifecraft's demo needs, by file id, with their sizes, in the order the app
fetches them (assets/files.json). Only ids and sizes are written: the files are the game's and are
fetched by the app, on the phone, from wago.tools.

    python3 tools/manifest.py CACHE_DIR

CACHE_DIR keeps files fetched while working the list out (each named by its id). It reads
assets/looks.json (tools/looks.py), assets/places.json and assets/sounds.json (tools/sounds.py).
"""
import json
import math
import os
import struct
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "android", "app", "src", "main", "assets")
AGENT = "YeaBwoi-build/1.0 (+https://github.com/logie-github/YeaBwoi)"

# The interface: Legion's talking head, the red buttons, the gossip parchment, the achievement
# toast, reputation bars, metal frames and stone, the loading bar and Dalaran's loading art, the
# gossip icon, the endings' heart icon, and the game's fonts (Friz Quadrata, Morpheus).
UI = [1111217, 1536801, 3813080, 4035524, 2139076, 3056750, 3056755, 374155, 131811, 131812,
      7963775, 132053, 236709, 615960, 615962]

# Dalaran (Legion's, over the Broken Isles) and its outdoor groups.
CITY_ROOT = 1120838
CITY_GROUPS = [2, 4, 5, 7, 8, 9, 10, 11, 15, 21, 44, 45, 46, 47, 48, 51, 53, 55, 57, 58, 80, 90, 91,
               92, 93, 94, 95, 96, 97, 115, 122, 124]
PROP_RADIUS = 20.0       # props this close to a place's marks
PROP_MIN_SIZE = 1.5      # and at least this big (yards across, scaled)

# The animations the story uses (AnimationData ids): stand, walk, run, and the emotes.
ANIMS = [0, 4, 5, 60, 64, 65, 66, 67, 68, 69, 70, 73, 74, 75, 76, 77, 79, 80, 81, 82, 83, 84, 113, 185, 186]


def fetch(cache, fid):
    path = os.path.join(cache, str(fid))
    if not os.path.exists(path):
        for attempt in range(4):
            try:
                req = urllib.request.Request("https://wago.tools/api/casc/%d?download" % fid, headers={"User-Agent": AGENT})
                data = urllib.request.urlopen(req, timeout=120).read()
                with open(path + ".part", "wb") as f:
                    f.write(data)
                os.replace(path + ".part", path)
                break
            except Exception:
                time.sleep(2 ** attempt)
        else:
            raise SystemExit("can't fetch %d" % fid)
    with open(path, "rb") as f:
        return f.read()


def chunks(d, reverse, start=0, end=None):
    end = len(d) if end is None else end
    o = start
    while o + 8 <= end:
        t = d[o:o + 4]
        n = struct.unpack_from("<I", d, o + 4)[0]
        yield (t[::-1] if reverse else t).decode("latin1"), o + 8, n
        o += 8 + n


def m2_deps(cache, fid, anims):
    """A model's first skin, its own textures, and the .anim files of the asked animations."""
    d = fetch(cache, fid)
    skins, tex, afid = [], [], []
    for t, o, n in chunks(d, False):
        if t == "SFID":
            skins = list(struct.unpack_from("<%dI" % (n // 4), d, o))
        elif t == "TXID":
            tex = [x for x in struct.unpack_from("<%dI" % (n // 4), d, o) if x]
        elif t == "AFID":
            afid = [struct.unpack_from("<HHI", d, o + i) for i in range(0, n, 8)]
    deps = skins[:1] + tex + [f for a, v, f in afid if f and a in anims and v == 0]
    return deps


def m2_size(cache, fid):
    d = fetch(cache, fid)
    for t, o, n in chunks(d, False):
        if t == "MD21":
            mn = struct.unpack_from("<3f", d, o + 160)
            mx = struct.unpack_from("<3f", d, o + 172)
            return max(mx[i] - mn[i] for i in range(3))
    return 0


def main():
    cache = sys.argv[1]
    os.makedirs(cache, exist_ok=True)
    looks = json.load(open(os.path.join(ASSETS, "looks.json")))
    places = json.load(open(os.path.join(ASSETS, "places.json")))["places"]
    packs = []

    packs.append(("interface", UI))

    people = []
    for name, look in looks.items():
        people.append(look["model"])
        people += m2_deps(cache, look["model"], ANIMS)
        people += [t for t in look["textures"].values()]
        for a in look.get("attachments", []):
            people.append(a["model"])
            people += m2_deps(cache, a["model"], [])
            if a.get("texture"):
                people.append(a["texture"])
    packs.append(("people", people))

    root = fetch(cache, CITY_ROOT)
    ch = {t: (o, n) for t, o, n in chunks(root, True)}
    o, n = ch["GFID"]
    gfid = struct.unpack_from("<%dI" % (n // 4), root, o)
    o, n = ch["MOMT"]
    mats = [struct.unpack_from("<16I", root, o + i) for i in range(0, n, 64)]
    city = [CITY_ROOT]
    for g in CITY_GROUPS:
        city.append(gfid[g])
        gd = fetch(cache, gfid[g])
        for t, go, gn in chunks(gd, True):
            if t != "MOGP":
                continue
            for st, so, sn in chunks(gd, True, go + 0x44, go + gn):
                if st != "MOBA":
                    continue
                for b in range(sn // 24):
                    flags = gd[so + b * 24 + 22]
                    mid = struct.unpack_from("<H", gd, so + b * 24 + 10)[0] if flags & 2 else gd[so + b * 24 + 23]
                    city.append(mats[mid][3])
    packs.append(("city", city))

    # Props near the places the story uses.
    o, n = ch["MODI"]
    modi = struct.unpack_from("<%dI" % (n // 4), root, o)
    o, n = ch["MODS"]
    first, count = struct.unpack_from("<II", root, o + 20)
    o, n = ch["MODD"]
    marks = [m for p in places.values() for m in p["marks"].values()]
    props = []
    chosen = set()
    for i in range(first, first + count):
        ref, x, y, z, qx, qy, qz, qw, scale = struct.unpack_from("<I3f4ff", root, o + i * 40)
        fid = modi[ref & 0xFFFFFF]
        if not fid or fid in chosen:
            continue
        if not any(math.dist((x, y, z), m) < PROP_RADIUS for m in marks):
            continue
        if m2_size(cache, fid) * scale < PROP_MIN_SIZE:
            continue
        chosen.add(fid)
        props.append(fid)
        props += m2_deps(cache, fid, [])
    packs.append(("props", props))

    # Music and sounds (tools/sounds.py): last, as the story plays without them.
    snd = json.load(open(os.path.join(ASSETS, "sounds.json")))
    sound = list(snd["music"].values()) + list(snd["ambience"].values())
    sound += [f for ids in snd["ui"].values() for f in ids]
    sound += [f for v in snd["voices"].values() for ids in v.values() for f in ids]
    sound += [f for ids in snd["effects"].values() for f in ids]
    packs.append(("sound", sound))

    seen = set()
    out = {"source": "https://wago.tools/api/casc/<id>?download", "packs": []}
    total = 0
    for name, ids in packs:
        files = []
        for fid in ids:
            if not fid or fid in seen:
                continue
            seen.add(fid)
            size = len(fetch(cache, fid))
            total += size
            files.append([fid, size])
        out["packs"].append({"name": name, "files": files})
        print("%-10s %5d files %8.1f MB" % (name, len(files), sum(s for _, s in files) / 1e6), file=sys.stderr)
    print("total %.1f MB" % (total / 1e6), file=sys.stderr)
    with open(os.path.join(ASSETS, "files.json"), "w") as f:
        json.dump(out, f, separators=(",", ":"))
        f.write("\n")


if __name__ == "__main__":
    main()
