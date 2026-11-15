#!/usr/bin/env python3
"""Every emote and animation each of the character creator's models can play, with the emote's voice by race and sex,
and writes assets/emotes.json. Ids and short names only: the files are the game's and the app fetches them on the phone.

    python3 -I tools/emotes.py TABLES_DIR FILES_DIR NAMES_JS [OUT]

TABLES_DIR  wago.tools' CSVs at creator/GameTables.BUILD (https://wago.tools/db2/<Table>/csv?build=12.1.7.70892):
            ChrRaces, ChrRaceXChrModel, ChrModel, CreatureDisplayInfo, CreatureModelData, EmotesText, Emotes,
            EmotesTextSound, SoundKitEntry, AnimationData.
FILES_DIR   the models' files by file id (wago.tools' casc download at the same build): each creator model's M2 and,
            for a model that keeps its skeleton apart (SKID), its .skel and the skeleton's parent (SKPD).
NAMES_JS    wow.export's src/js/3D/AnimMapper.js (MIT; its ANIM_NAMES, index = AnimationData.ID, from wow.tools):
            AnimationData itself has no names at this build.

What is in it:
  models   "race:sex" (sex 0 man, 1 woman) for the creator's 52 models (creator/Catalog's races, the same rule):
           "m" ChrModel id, "f" the M2's file id, "a" [anim id, how many variations, ...] (what the model really has:
           its own sequences, else its skeleton's, merged with the skeleton's parent's as cpp/engine/m2.cpp does),
           "v" {EmotesText id: [voice file ids]} (EmotesTextSound for the race and sex with no class, the newest kit
           where there are several, as tools/sounds.py does; SoundKitEntry's files).
  text     the game's text emotes (EmotesText): [id, name, anim, kind] (anim -1 for none; kind 0 one shot,
           1 a stand state (sit, sleep, kneel: the anim is the state's loop), 2 a looping state).
  npc      the game's NPC emotes that no text emote uses (Emotes, AnimID >= 0): [10000 + Emotes id, name, anim, kind],
           one per animation and kind.
  anims    {anim id: name} for every animation any model has (wow.export's names; "Anim<id>" past its list).
"""
import csv
import json
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "emotes.json")
BUILD = "12.1.7.70892"

# Emotes.EmoteSpecProcParam of a stand state (the game's UNIT_STAND_STATE) -> the animation the state holds, by name.
STAND_STATE = {0: "Stand", 1: "SitGround", 3: "Sleep", 8: "KneelLoop"}

# The NPC emotes' names come from Emotes.EmoteSlashCommand ("STATE_WORK_MINING"): these words go.
DROP_WORDS = {"ONESHOT", "STATE", "STAND", "DNR", "(DNR)", "ZZOLD", "DOESNT", "WORK)", "(DOESNT"}


def table(d, name):
    with open(os.path.join(d, name + ".csv"), newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def num(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return int(float(v)) if v else 0


def creator_models(d):
    """creator/Catalog.build's races and their models: [(race id, the race whose models they are, sex, ChrModel row)].
    Dracthyr (52) is offered as its visage, race 75's models: its voice is the visage's too."""
    cm = {num(r["ID"]): r for r in table(d, "ChrModel")}
    races = {num(r["ID"]): r for r in table(d, "ChrRaces")}
    of = {}
    for x in table(d, "ChrRaceXChrModel"):
        m = cm.get(num(x["ChrModelID"]))
        if m is None or num(m["Sex"]) not in (0, 1):
            continue
        of.setdefault(num(x["ChrRacesID"]), {}).setdefault(num(m["Sex"]), num(m["ID"]))
    picks = []
    for rid, r in races.items():
        if num(r["PlayableRaceBit"]) < 0 or num(r["Flags"]) & 1:
            continue
        own = of.get(rid)
        source = rid if own else num(r["UnalteredVisualRaceID"])
        models = own or of.get(source)
        if models:
            picks.append((r, models, source))
    by = {}
    for r, m, src in picks:
        by.setdefault(tuple(sorted(m.values())), []).append((r, m, src))
    sel = [min(v, key=lambda p: (0 if num(p[0]["Alliance"]) == 0 else 1, num(p[0]["ID"]))) for v in by.values()]
    sel.sort(key=lambda p: (1 if num(p[0]["Flags"]) & 0x80000 else 0, 0 if num(p[0]["Alliance"]) == 0 else 1,
                            num(p[0]["UiDisplayOrder"]), num(p[0]["ID"])))
    return [(num(r["ID"]), src, sex, cm[mid]) for r, models, src in sel for sex, mid in sorted(models.items())]


def chunks(b):
    out, o = {}, 0
    while o + 8 <= len(b):
        tag = b[o:o + 4].decode("latin1")
        n = struct.unpack_from("<I", b, o + 4)[0]
        out.setdefault(tag, (o + 8, n))
        o += 8 + n
    return out


def sequences(b, base, count, offset):
    """(anim id, variation) of each M2Sequence (64 bytes) of an M2Array whose offsets count from base."""
    out = []
    for k in range(count):
        o = base + offset + 64 * k
        if o + 64 > len(b):
            break
        out.append(struct.unpack_from("<HH", b, o))
    return out


def model_sequences(files, fid):
    """The (anim, variation) keys a model plays: its own, else its skeleton's merged with the skeleton's parent's."""
    def read(i):
        p = os.path.join(files, str(i))
        if not os.path.isfile(p):
            raise SystemExit("missing file %d in %s" % (i, files))
        with open(p, "rb") as f:
            return f.read()
    b = read(fid)
    ch = chunks(b)
    if "MD21" in ch:
        base = ch["MD21"][0]
    else:
        base, ch = 0, {}
    n, off = struct.unpack_from("<II", b, base + 0x1C)
    keys = set(sequences(b, base, n, off))
    skel = struct.unpack_from("<I", b, ch["SKID"][0])[0] if "SKID" in ch else 0
    while skel:
        s = read(skel)
        sc = chunks(s)
        if "SKS1" in sc:        # the skeleton's, then its parent's: what the child lacks it plays from the parent
            o = sc["SKS1"][0]
            c, so = struct.unpack_from("<II", s, o + 8)
            keys |= set(sequences(s, o, c, so))
        skel = struct.unpack_from("<I", s, sc["SKPD"][0] + 8)[0] if "SKPD" in sc and sc["SKPD"][1] >= 12 else 0
    return keys


def anim_names(js):
    with open(js, encoding="utf-8") as f:
        t = f.read()
    a = t[t.index("ANIM_NAMES"):]
    a = a[a.index("[") + 1:a.index("];")]
    return re.findall(r'"([^"]*)"', a)


def pretty(cmd):
    words = [w for w in re.split(r"[_\s]+", cmd.upper()) if w and w not in DROP_WORDS and not w.startswith("(")]
    return " ".join(words).capitalize() if words else cmd.capitalize()


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    d, files, js = sys.argv[1:4]
    out_path = sys.argv[4] if len(sys.argv) > 4 else OUT
    names = anim_names(js)
    name_id = {n: i for i, n in enumerate(names)}
    anim_ids = {num(r["ID"]) for r in table(d, "AnimationData")}

    kits = {}
    for r in table(d, "SoundKitEntry"):
        kits.setdefault(num(r["SoundKitID"]), []).append(num(r["FileDataID"]))

    emotes = {num(r["ID"]): r for r in table(d, "Emotes")}

    def anim_kind(e):
        if e is None:
            return -1, 0
        proc = num(e["EmoteSpecProc"])
        if proc == 1:
            state = STAND_STATE.get(num(e["EmoteSpecProcParam"]))
            return (name_id[state] if state in name_id else -1), 1
        return num(e["AnimID"]), 2 if proc == 2 else 0

    text = []
    used = set()
    for r in sorted(table(d, "EmotesText"), key=lambda r: num(r["ID"])):
        e = emotes.get(num(r["EmoteID"]))
        anim, kind = anim_kind(e) if num(r["EmoteID"]) else (-1, 0)
        text.append([num(r["ID"]), r["Name"].capitalize(), anim, kind])
        used.add(num(r["EmoteID"]))
    npc, seen = [], set()
    for eid in sorted(emotes):
        e = emotes[eid]
        if eid in used or num(e["AnimID"]) < 0 and num(e["EmoteSpecProc"]) != 1:
            continue
        anim, kind = anim_kind(e)
        if anim <= 0 and kind != 1 or (anim, kind) in seen:
            continue
        seen.add((anim, kind))
        npc.append([10000 + eid, pretty(e["EmoteSlashCommand"]), anim, kind])

    sounds = {}
    for r in table(d, "EmotesTextSound"):
        if num(r["ClassID"]) != 0:          # 12: the Demon Hunters' own; Wifecraft's people have no class
            continue
        k = (num(r["RaceID"]), num(r["SexID"]), num(r["EmotesTextID"]))
        sounds[k] = max(sounds.get(k, 0), num(r["SoundID"]))

    cdi = {num(r["ID"]): r for r in table(d, "CreatureDisplayInfo")}
    cmd = {num(r["ID"]): r for r in table(d, "CreatureModelData")}
    models, have_any = {}, set()
    for race, voice_race, sex, m in creator_models(d):
        fid = num(cmd[num(cdi[num(m["DisplayID"])]["ModelID"])]["FileDataID"])
        keys = model_sequences(files, fid)
        var = {}
        for a, v in keys:
            var[a] = max(var.get(a, 0), v + 1)
        have_any |= set(var)
        voices = {}
        for (rr, ss, tid), kit in sounds.items():
            if rr == voice_race and ss == sex and kits.get(kit):
                voices[str(tid)] = sorted(set(kits[kit]))
        models["%d:%d" % (race, sex)] = {
            "m": num(m["ID"]), "f": fid,
            "a": [x for a in sorted(var) for x in (a, var[a])],
            "v": dict(sorted(voices.items(), key=lambda kv: int(kv[0]))),
        }

    anims = {str(a): (names[a] if a < len(names) else "Anim%d" % a) for a in sorted(have_any | {x[2] for x in text + npc if x[2] >= 0})}
    unknown = sorted(a for a in have_any if a not in anim_ids)
    out = {"build": BUILD, "text": text, "npc": npc, "anims": anims, "models": models}
    with open(out_path, "w") as f:
        json.dump(out, f, separators=(",", ":"), sort_keys=True)
        f.write("\n")
    print("%d models, %d text emotes, %d NPC emotes, %d animations named; %d bytes%s" % (
        len(models), len(text), len(npc), len(anims), os.path.getsize(out_path),
        ("; ids not in AnimationData: %s" % unknown) if unknown else ""))


if __name__ == "__main__":
    main()
