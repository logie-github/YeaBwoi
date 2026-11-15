#!/usr/bin/env python3
"""Checks the creature and NPC looks the placement editor puts on stage (android/.../creator/CreatureLooks.kt) against the
game's own files, on the desktop. CreatureLooks resolves a CreatureDisplayInfo id to a look at runtime on the phone; here
the same Kotlin runs on the JVM (unit-tests, CreatureLooksRealTest), writes one look file per display (engine-test's
format) and the counts, and this script fetches what each look needs and draws it with engine-test's harness.

    python3 tools/creature_probe.py tables DIR                  the tables CreatureLooks reads into DIR (wago.tools' CSVs)
    WIFECRAFT_TABLES=DIR WIFECRAFT_LOOKS=OUT gradle -p unit-tests test --tests '*CreatureLooksRealTest*'
                                                                OUT/<display>.look for the displays named in OUT/displays.txt
                                                                (one id a line; the test writes the defaults when it is
                                                                missing) and OUT/counts.txt: every named creature's first
                                                                display resolved, the ones that don't by why
    python3 tools/creature_probe.py files OUT FILES             each look's model, skin, skeleton, stand animation, textures
                                                                and added models into FILES (each file named by its id)
    python3 tools/creature_probe.py render OUT FILES HARNESS    OUT/<display>.png for each look (engine-test/build/harness)

Everything is fetched at the app's pinned build (creator/GameTables.kt BUILD), at most 8 requests at once. Nothing
fetched goes into the repository.
"""
import concurrent.futures
import glob
import os
import struct
import subprocess
import sys
import time
import urllib.request

BUILD = "12.1.7.70892"
TABLES = (
    # creator/GameTables.kt's for the characters' looks
    "ChrRaces", "ChrRaceXChrModel", "ChrModel", "ChrCustomizationCategory", "ChrCustomizationOption",
    "ChrCustomizationChoice", "ChrCustomizationElement", "ChrCustomizationGeoset", "ChrCustomizationMaterial",
    "ChrCustomizationSkinnedModel", "ChrCustomizationReq", "ChrCustomizationBoneSet", "ChrCustomizationCondModel",
    "ChrModelTextureLayer", "ChrModelMaterial", "CharComponentTextureSections", "CreatureDisplayInfo",
    "CreatureModelData", "TextureFileData",
    # and the creatures' and NPCs' (CreatureLooks)
    "Creature", "CreatureDisplayInfoExtra", "CreatureDisplayInfoOption", "CreatureDisplayInfoGeosetData",
    "NPCModelItemSlotDisplayInfo", "ItemDisplayInfo", "ItemDisplayInfoMaterialRes", "ModelFileData",
    "ComponentModelFileData", "ComponentTextureFileData",
)
USER_AGENT = "Mozilla/5.0 (compatible; table-fetch)"
STAND = 0


def get(url, path, tries=4):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return True
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=300) as r:
                data = r.read()
            if not data:
                raise IOError("empty")
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
            return True
        except Exception as e:  # noqa: BLE001 (a 404 is a file wago.tools doesn't have: said once, not retried)
            if getattr(e, "code", 0) == 404:
                return False
            time.sleep(1 << attempt)
    return False


def tables(out):
    os.makedirs(out, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        jobs = {t: pool.submit(get, "https://wago.tools/db2/%s/csv?build=%s" % (t, BUILD), os.path.join(out, t + ".csv"))
                for t in TABLES}
        for t, j in jobs.items():
            print("%s: %s" % (t, "ok" if j.result() else "FAILED"))


def chunks(data):
    """A chunked model's or skeleton's chunks: (tag, bytes)."""
    if data[:4] == b"MD20":
        return
    o = 0
    while o + 8 <= len(data):
        tag = data[o:o + 4].decode("latin-1")
        size = struct.unpack_from("<I", data, o + 4)[0]
        if o + 8 + size > len(data):
            return
        yield tag, data[o + 8:o + 8 + size]
        o += 8 + size


def deps(data):
    """What a model or skeleton names: its first skin, its textures, its skeleton (and parent), the stand animation."""
    out, skeletons = [], []
    for tag, b in chunks(data):
        if tag == "SFID" and len(b) >= 4:
            out.append(struct.unpack_from("<I", b, 0)[0])
        elif tag == "TXID":
            out += [x for x in struct.unpack_from("<%dI" % (len(b) // 4), b) if x]
        elif tag == "SKID" and len(b) >= 4:
            skeletons.append(struct.unpack_from("<I", b, 0)[0])
        elif tag == "SKPD" and len(b) >= 12:
            skeletons.append(struct.unpack_from("<I", b, 8)[0])
        elif tag == "AFID":
            for i in range(len(b) // 8):
                anim, variation, fid = struct.unpack_from("<HHI", b, 8 * i)
                if fid and variation == 0 and anim == STAND:
                    out.append(fid)
    return [x for x in out if x], [x for x in skeletons if x]


def look_files(path):
    """The files a look file names: (models, textures)."""
    models, textures = [], []
    for line in open(path, encoding="utf-8"):
        p = line.split()
        if not p or p[0].startswith("#"):
            continue
        if p[0] == "model":
            models.append(int(p[1]))
        elif p[0] == "tex":
            textures += [int(kv.split("=")[1]) for kv in p[1].split(",") if "=" in kv]
        elif p[0] == "layer":
            textures.append(int(p[10]))
        elif p[0] == "extra":
            models.append(int(p[2]))
            if int(p[3]):
                textures.append(int(p[3]))
    return models, textures


def files(out, fdir):
    os.makedirs(fdir, exist_ok=True)
    url = "https://wago.tools/api/casc/%d?download&version=" + BUILD

    def one(fid):
        return fid, get(url % fid, os.path.join(fdir, str(fid)))

    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        for path in sorted(glob.glob(os.path.join(out, "*.look"))):
            models, textures = look_files(path)
            missing = [f for f, ok in pool.map(one, models + textures) if not ok]
            todo, seen = list(models), set()
            while todo:
                f = todo.pop()
                if f in seen or not os.path.exists(os.path.join(fdir, str(f))):
                    continue
                seen.add(f)
                d, sk = deps(open(os.path.join(fdir, str(f)), "rb").read())
                missing += [x for x, ok in pool.map(one, d + sk) if not ok]
                todo += sk
            print("%s: %s" % (os.path.basename(path), "complete" if not missing else "missing %s" % sorted(set(missing))))


def render(out, fdir, harness):
    for path in sorted(glob.glob(os.path.join(out, "*.look"))):
        png = path[:-5] + ".png"
        r = subprocess.run([harness, "--look", path, "--files", fdir, "--out", png, "--size", "384x512", "--facing", "0.6"],
                           capture_output=True, text=True, timeout=300)
        print("%s: %s %s" % (os.path.basename(png), "ok" if r.returncode == 0 else "FAILED", r.stderr.strip()[-300:]))


def main(argv):
    if len(argv) >= 3 and argv[1] == "tables":
        tables(argv[2])
    elif len(argv) >= 4 and argv[1] == "files":
        files(argv[2], argv[3])
    elif len(argv) >= 5 and argv[1] == "render":
        render(argv[2], argv[3], argv[4])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
