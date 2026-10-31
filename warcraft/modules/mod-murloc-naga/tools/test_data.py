#!/usr/bin/env python3
"""The committed data of mod-murloc-naga checked against itself: every quest's giver, ender, required
creature and item is there (the module's own or the game's named in tools/content.py), every spawn's
creature and object, every look's texture the patch makes; every id in the module's ranges; the
intros' cameras read as the world server reads them (one camera, keys in time order, the flight's
length); the narrations' recipe names the game's files."""
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-allied-races", "tools"))
import cameras  # noqa: E402
import content as C  # noqa: E402
import sqlrows  # noqa: E402

DATA = os.path.join(HERE, "..", "data")
SQL = open(os.path.join(DATA, "sql", "chromiecraft", "world", "murloc_naga_world.sql"), encoding="utf-8").read()
failures = 0


def check(ok, what):
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def rows(table):
    """The rows of [table]'s INSERTs, as dicts."""
    out = []
    for m in re.finditer(r"INSERT INTO `%s` \(([^)]*)\) VALUES\n" % table, SQL):
        cols = [c.strip(" `") for c in m.group(1).split(",")]
        end = SQL.index(";\n", m.end())
        for r in sqlrows.values("INSERT INTO x VALUES " + SQL[m.end():end + 1]):
            out.append(dict(zip(cols, r)))
    return out


creatures = {int(r["entry"]): r for r in rows("creature_template")}
gos = {int(r["entry"]): r for r in rows("gameobject_template")}
items = {int(r["entry"]): r for r in rows("item_template")}
quests = {int(r["ID"]): r for r in rows("quest_template")}
game_creatures = {C.PYGMY_TIDE_CRAWLER, C.DARKSHORE_THRESHER, 2187, C.WRATHTAIL_MYRMIDON, C.WRATHTAIL_RAZORTAIL,
                  C.THUNDRIS_WINDWEAVER, C.GLYNDA_NALSHEA, C.ONU, C.MARUKAI}
check(len(quests) == len(C.MURLOC_QUESTS) + len(C.NAGA_QUESTS), f"every quest is there ({len(quests)})")
check(all(C.QUEST_BASE <= q < C.QUEST_BASE + 100 for q in quests), "the quests' ids are the module's")
check(all(C.NPC_BASE <= e < C.NPC_BASE + 100 for e in creatures), "the creatures' ids are the module's")
check(all(C.GO_BASE <= e < C.GO_BASE + 100 for e in gos), "the objects' ids are the module's")
check(all(C.ITEM_BASE <= e < C.ITEM_BASE + 100 for e in items), "the items' ids are the module's")
check(sum(1 for q in quests.values() if int(q["AllowableRaces"]) == 1 << 18) == len(C.MURLOC_QUESTS), "the murlocs' quests are theirs alone")
check(sum(1 for q in quests.values() if int(q["AllowableRaces"]) == 1 << 19) == len(C.NAGA_QUESTS), "the naga's quests are theirs alone")

starters = {(int(r["id"]), int(r["quest"])) for r in rows("creature_queststarter")}
enders = {(int(r["id"]), int(r["quest"])) for r in rows("creature_questender")}
go_enders = {(int(r["id"]), int(r["quest"])) for r in rows("gameobject_questender")}
known = set(creatures) | game_creatures
for q in quests:
    givers = [c for c, quest in starters if quest == q]
    ends = [c for c, quest in enders if quest == q] + [g for g, quest in go_enders if quest == q]
    check(givers and all(c in known for c in givers), f"quest {q} ({quests[q]['LogTitle']}): offered by {givers}")
    check(len(ends) == 1 and (ends[0] in known or ends[0] in gos), f"...handed in to {ends}")
    for i in range(1, 5):
        n = int(quests[q][f"RequiredNpcOrGo{i}"])
        if n:
            check(n in known, f"...its creature {n} is there")
    for i in range(1, 7):
        it = int(quests[q][f"RequiredItemId{i}"])
        if it:
            check(it in items or it == C.THRESHER_EYE, f"...its item {it} is there")
addons = {int(r["ID"]): r for r in rows("quest_template_addon")}
for q, a in addons.items():
    prev = int(a["PrevQuestID"])
    check(prev == 0 or prev in quests, f"quest {q} comes after {prev or 'nothing'}")
tide = C.QUEST_BASE + 32
check((C.ONU, tide) in starters and quests[tide]["MinLevel"] == "17", "The Tide Turns: Onu's, from level 17")
check((C.MARUKAI, C.QUEST_BASE + 34) in enders, "An Unlikely Allegiance: handed in to Marukai at Zoram'gar")

spawned = [int(r["id"]) for r in rows("creature")]
check(all(e in known for e in spawned), f"every creature spawned is there ({len(spawned)} spawns)")
phases = {int(r["phaseMask"]) for r in rows("creature")} | {int(r["phaseMask"]) for r in rows("gameobject")}
check(phases == {C.PHASE[C.MURLOC], C.PHASE[C.NAGA]}, f"everything in the races' own phases ({phases})")
check(all(int(r["id"]) in gos for r in rows("gameobject")), "every object placed is there")
loot = {int(r["Entry"]) for r in rows("gameobject_loot_template")}
check(all(int(g["Data1"]) in loot for g in gos.values() if g["type"] == "3"), "every chest has its loot")

# The looks' textures: the murlocs' are tools/murloc_model.py's (named by tools/murloc_looks.py), the
# naga's new colours the patch's (looks.txt).
import murloc_looks as ML  # noqa: E402
looks = [l.rstrip("\n").split("\t") for l in open(os.path.join(DATA, "client", "looks.txt")) if not l.startswith("#")]
check(len([l for l in looks if l[0] == "naga_colour"]) == 8, "the naga's four new colours a sex")
sections = open(os.path.join(DATA, "client", "dbc", "CharSections.txt")).read()
made = {"MurlocBase"} | {"MurlocSkin%02d" % i for i in range(len(ML.SKINS))} | {"MurlocEyes%02d" % i for i in range(len(ML.SKINS))} \
    | {"MurlocHair%02d" % i for i in range(len(ML.HAIR_COLOURS))}
named = set(re.findall(r"Character\\\\Murloc\\\\([A-Za-z0-9]+)\.blp", sections))
check(named and named <= made, f"every murloc texture the looks name is one tools/murloc_model.py makes ({sorted(named - made)})")
check(all("MurlocSkin%02d" % i in named and "MurlocEyes%02d" % i in named for i in range(len(ML.SKINS))),
      f"the murlocs' {len(ML.SKINS)} skins and eye colours")
geosets = open(os.path.join(DATA, "client", "dbc", "CharHairGeosets.txt")).read()
check(sum(len(v) for v in ML.HAIR_STYLES.values()) == len(re.findall(r"^put\t\d+\t19\t", geosets, re.M)), "a hair style row each (murlocs)")
facial = open(os.path.join(DATA, "client", "dbc", "CharacterFacialHairStyles.txt")).read()
check(facial.count("appendraw") >= sum(len(v) for v in ML.FEATURES.values()), "a feature row each (murlocs)")
check(sections.count("put\t") > 100, "the races' looks")

# The cameras, as the world server reads them.
for race, length in (("Murloc", 42000), ("Naga", 61000)):
    m2 = open(os.path.join(DATA, "client", "Cameras", f"Flyby{race}.m2"), "rb").read()
    skin = open(os.path.join(DATA, "client", "Cameras", f"Flyby{race}00.skin"), "rb").read()
    check(m2[:4] == b"MD20" and struct.unpack_from("<I", m2, 4)[0] == 264 and skin[:4] == b"SKIN", f"{race}'s camera is a 3.3.5 model")
    pos, tgt = cameras.read_positions(m2)
    check(len(pos) >= 8 and len(tgt) == len(pos) and pos[-1][0] == length, f"...{len(pos)} keys, {length} ms")
    check(all(a[0] < b[0] for a, b in zip(pos, pos[1:])), "...in time order")
    seq = struct.unpack_from("<I", m2, struct.unpack_from("<I", m2, 0x20)[0] + 4)[0]
    check(seq == length, "...its sequence as long")

narration = [l.rstrip("\n").split("\t") for l in open(os.path.join(DATA, "client", "narration.txt")) if not l.startswith("#")]
check({l[0] for l in narration} == {"Murloc", "Naga"} and all(l[2].startswith("Sound\\") for l in narration), "the narrations: the game's sounds")
check(sum(1 for l in narration if "LadyVashj" in l[2]) == 8, "the naga's: eight of Lady Vashj's lines")

print("PASSED" if not failures else f"FAILED ({failures})")
sys.exit(1 if failures else 0)
