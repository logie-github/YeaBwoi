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
  labels   {anim id: label}: each animation's name for the player ("Ready (Unarmed)", "Sit Chair (Medium)").
A text and an NPC emote's row ends with its label too: [id, name, anim, kind, label] ("Talk (Excited)", "For the
Alliance!"). The game has no display names for them at this build: EmotesText.Name is the chat token ("TALKEX"),
GlobalStrings has only the slash commands (EMOTE95_CMD1 "/talkex", CMD2 "/excited") and EmotesTextData the chat lines
("You talk excitedly."): text_label() writes the compound tokens out (TEXT_LABELS) and capitalises the one-word ones;
anim_label() splits an animation's camel-case name and puts its qualifiers in brackets.

    python3 -I tools/emotes.py labels [JSON]    (re)writes the labels of an emotes.json already made, from its names
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


# The text emotes whose chat token is several words run together, or an abbreviation (EmotesText.Name), written out.
TEXT_LABELS = {
    "ATTACKMYTARGET": "Attack My Target", "BADFEELING": "Bad Feeling", "BRB": "Be Right Back",
    "COVEREARS": "Cover Ears", "CROSSARMS": "Cross Arms", "FORTHEALLIANCE": "For the Alliance!",
    "FORTHEHORDE": "For the Horde!", "GOLFCLAP": "Golf Clap", "GOODLUCK": "Good Luck", "HEALME": "Heal Me",
    "HELPME": "Help Me", "HIGHFIVE": "High Five", "HOLDHAND": "Hold Hands", "JK": "Just Kidding", "LAYDOWN": "Lie Down",
    "MOUNTSPECIAL": "Mount Special", "NOSEPICK": "Pick Nose", "OOM": "Out of Mana", "OPENFIRE": "Open Fire",
    "ROFL": "ROFL", "ROLLEYES": "Roll Eyes", "SHAKEFIST": "Shake Fist", "STOPATTACK": "Stop Attack",
    "TALKEX": "Talk (Excited)", "TALKQ": "Talk (Question)", "YW": "You're Welcome", "FACEPALM": "Facepalm",
    "HUZZAH": "Huzzah!", "WHOA": "Whoa!", "OOPS": "Oops!", "BOOP": "Boop",
}

# An animation name's words (anim_label): prefixes written out, the abbreviations expanded, and which are qualifiers
# (put in brackets after the rest: "Attack (One-Handed, Off-Hand)").
ANIM_PREFIX = {"DH": "Demon Hunter", "Pal": "Paladin", "Dk": "Death Knight", "Pri": "Priest", "Sha": "Shaman",
               "Mag": "Mage", "Loc": "Warlock", "Dru": "Druid", "Drac": "Dracthyr", "Art": "Artifact"}
ANIM_WORDS = {"1H": "One-Handed", "2H": "Two-Handed", "2HL": "Two-Handed Loose", "Off": "Off-Hand", "Glv": "Glaive",
              "Med": "Medium", "Q": "Question", "Ex": "Excited", "Bartend": "Bartender"}
ANIM_PAIRS = {("No", "Sheathe"): "Weapon Out", ("No", "Sheath"): "Weapon Out", ("Lift", "Off"): "Lift Off",
              ("Take", "Off"): "Take Off", ("Blow", "Dart"): "Blow Dart", ("Fist", "1H"): "Fist Weapon",
              ("2H", "Loose"): "2HL", ("Bar", "Tend"): "Bartender"}
QUALIFIERS = {"Unarmed", "One-Handed", "Two-Handed", "Two-Handed Loose", "Off-Hand", "Bow", "Rifle", "Thrown",
              "Crossbow", "Dart", "Blow Dart", "Joust", "Glaive", "Fist Weapon", "FL", "Special", "Excited",
              "Exclamation", "Question", "Subdued", "Frustrated", "Left", "Right", "Up", "Down", "Forward", "Backward",
              "Backwards", "Front", "Both", "Start", "Loop", "End", "Finish", "Low", "Medium", "High", "Pierce", "Big",
              "Channel", "Omni", "Directed", "Weapon Out", "Flying", "Var1"}


def anim_words(name):
    """An animation's camel-case name in words: "Attack2HL" -> Attack, 2HL; "WAGuardStand01" -> WA, Guard, Stand, 01."""
    return re.findall(r"[A-Z]+(?=[A-Z][a-z])|\d+[A-Z]*(?![a-z])|\d+|[A-Z]?[a-z]+|[A-Z]+", name)


def anim_label(name):
    """An animation's name for the player: "ReadyUnarmed" -> "Ready (Unarmed)", "EmoteDanceSpecial" ->
    "Dance (Special)", "FlyAttack1H" -> "Attack (One-Handed, Flying)", "WAGuardStand01" -> "Guard Stand 1",
    "Anim1776" as it is."""
    if re.fullmatch(r"Anim\d+", name):
        return name
    words = [x for w in anim_words(name) for x in (["Walk", "Backwards"] if w == "Walkbackwards" else [w])]
    if not words:
        return name
    # words that go together (NoSheathe: played with the weapon out; LiftOff isn't the off hand)
    joined = []
    i = 0
    while i < len(words):
        pair = ANIM_PAIRS.get(tuple(words[i:i + 2]))
        if pair:
            joined.append(pair)
            i += 2
            continue
        joined.append(words[i])
        i += 1
    words = joined
    main, quals = [], []
    flying = False
    for k, w in enumerate(words):
        if k == 0 and w == "Fly" and len(words) > 1:
            flying = True
            continue
        if w in ("Emote", "WA") and len(words) > 1:
            continue
        if k == 0 and w in ANIM_PREFIX:
            main.append(ANIM_PREFIX[w])
            continue
        w = ANIM_WORDS.get(w, w)
        if w.isdigit():
            main.append(str(int(w)))
        elif w in QUALIFIERS and main and (w != "Special" or main == ["Dance"]):
            quals.append("Variation 1" if w == "Var1" else w)
        else:
            main.append(w if not w.islower() else w.capitalize())
    if flying:
        quals.append("Flying")
    if not main:
        main, quals = quals[:1], quals[1:]
    out = " ".join(main)
    return out + (" (%s)" % ", ".join(dict.fromkeys(quals)) if quals else "")


def text_label(token):
    """A text emote's name for the player from its chat token (EmotesText.Name): "TALKEX" -> "Talk (Excited)"."""
    t = token.upper()
    return TEXT_LABELS.get(t, t.capitalize())


# An NPC emote's command words run together where its animation has no name to split them by.
NPC_WORDS = {"Waarmscrossed": "Arms Crossed"}


def npc_label(cmd_name, anim_name, kind):
    """An NPC emote's name: its animation's (anim_label; the command's words when the animation has no name), with
    "Looping" when it is held (kind 2) and the animation's name doesn't say so."""
    if anim_name and not re.fullmatch(r"Anim\d+", anim_name):
        label = anim_label(anim_name)
    else:
        words = [w.capitalize() for w in cmd_name.split()]
        out = [NPC_WORDS.get(w, w) for w in words if w not in ("Nosheathe", "Nosheath")]
        label = " ".join(out) + (" (Weapon Out)" if len(out) < len(words) else "")
    if kind == 2 and "Loop" not in label:
        label = label[:-1] + ", Looping)" if label.endswith(")") else label + " (Looping)"
    return label


def add_labels(out):
    """The labels of an emotes.json's rows and animations, from its own names (text: the token; NPC: the animation)."""
    anims = out["anims"]
    for row in out["text"]:
        del row[4:]
        row.append(text_label(row[1]))
    for row in out["npc"]:
        del row[4:]
        row.append(npc_label(row[1], anims.get(str(row[2])), row[3]))
    out["labels"] = {k: anim_label(v) for k, v in anims.items()}
    return out


def relabel(path):
    with open(path) as f:
        out = json.load(f)
    add_labels(out)
    with open(path, "w") as f:
        json.dump(out, f, separators=(",", ":"), sort_keys=True)
        f.write("\n")
    print("%d text emotes, %d NPC emotes, %d animations labelled; %d bytes" % (
        len(out["text"]), len(out["npc"]), len(out["labels"]), os.path.getsize(path)))


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "labels":
        return relabel(sys.argv[2] if len(sys.argv) > 2 else OUT)
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
    out = add_labels({"build": BUILD, "text": text, "npc": npc, "anims": anims, "models": models})
    with open(out_path, "w") as f:
        json.dump(out, f, separators=(",", ":"), sort_keys=True)
        f.write("\n")
    print("%d models, %d text emotes, %d NPC emotes, %d animations named; %d bytes%s" % (
        len(models), len(text), len(npc), len(anims), os.path.getsize(out_path),
        ("; ids not in AnimationData: %s" % unknown) if unknown else ""))


if __name__ == "__main__":
    main()
