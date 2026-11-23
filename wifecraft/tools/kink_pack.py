#!/usr/bin/env python3
"""Validates the kink and JOI knowledge pack (assets/dialogue/kink/*.json). Exit 1 on any error.

  python3 -I tools/kink_pack.py [pack_dir]
"""
import json, os, sys

BANDS = ["strangers", "acquaintances", "friendly", "intimate", "uninhibited"]
CAP = dict(zip(BANDS, range(1, 6)))
ROLES = {"dom", "sub", "any"}
RACES = {"human", "orc", "undead", "tauren", "troll", "nightelf", "dwarf", "gnome", "draenei", "bloodelf", "worgen", "goblin",
         "pandaren", "voidelf", "nightborne", "zandalari", "lightforged"}
CLASSES = {"warrior", "paladin", "hunter", "rogue", "priest", "shaman", "mage", "warlock", "druid", "monk", "deathknight", "demonhunter"}
# modern slang and out-of-world words an Azerothian NPC would not say
BAD_WORDS = ["okay", " ok ", "dude", "bro ", "guys", "awesome", "cool ", "internet", "phone", "email", "sexy", "babe", "bae"]
MAX_SNIPPET = 260  # characters, about 60 tokens


def load(d, name):
    with open(os.path.join(d, name), encoding="utf-8") as f:
        return json.load(f)


def main(d):
    errs = []
    def err(m): errs.append(m)

    concepts = load(d, "concepts.json")["entries"]
    ids = set()
    for e in concepts:
        i = e.get("id", "?")
        if i in ids: err(f"duplicate id {i}")
        ids.add(i)
        for k in ("id", "cat", "i", "role", "band", "tags", "concept", "voice"):
            if k not in e: err(f"{i}: missing {k}")
        if e.get("band") not in BANDS: err(f"{i}: bad band {e.get('band')}")
        if e.get("role") not in ROLES: err(f"{i}: bad role")
        if not 1 <= e.get("i", 0) <= 5: err(f"{i}: intensity outside 1..5")
        if e.get("i", 0) > CAP.get(e.get("band"), 0) + 1: err(f"{i}: intensity {e['i']} too high for band {e['band']} (max band+1)")
        for k in e.get("tw", {}):
            if k not in RACES | CLASSES: err(f"{i}: unknown race/class key {k}")
        # a rendered snippet: concept + one voice + one twist must fit
        v = next(iter(e.get("voice", {}).values()), "")
        t = next(iter(e.get("tw", {}).values()), "")
        if len(e.get("concept", "")) > 230: err(f"{i}: concept longer than 230 chars")
        text = " ".join([e.get("concept", ""), v, t] + list(e.get("voice", {}).values()) + list(e.get("tw", {}).values())).lower()
        for w in BAD_WORDS:
            if w in " " + text + " ": err(f"{i}: out-of-world word '{w.strip()}'")

    care = load(d, "care.json")["entries"]
    kinds = {c["kind"] for c in care}
    for need in ("checkin", "safeword", "aftercare", "negotiate", "limit"):
        if need not in kinds: err(f"care: no '{need}' entry")
    for c in care:
        if c["band"] not in BANDS: err(f"care {c['id']}: bad band")
        if len(c["text"]) > MAX_SNIPPET: err(f"care {c['id']}: text over {MAX_SNIPPET} chars")

    ex = load(d, "examples.json")["examples"]
    if len(ex) < 30: err(f"only {len(ex)} examples (need 30+)")
    for x in ex:
        i = x["id"]
        if x["band"] not in BANDS: err(f"{i}: bad band")
        for r in x.get("races", []):
            if r not in RACES | CLASSES: err(f"{i}: unknown race/class {r}")
        if not 1 <= len(x["lines"]) <= 6: err(f"{i}: 1..6 lines")
        txt = " ".join(l["text"] for l in x["lines"]).lower()
        for w in BAD_WORDS:
            if w in " " + txt + " ": err(f"{i}: out-of-world word '{w.strip()}'")
    stages = {x["stage"] for x in ex}
    for s in ("flirt", "negotiate", "scene", "joi", "care", "aftercare"):
        if s not in stages: err(f"examples: no '{s}' stage")
    if not any("safeword" in x["tags"] for x in ex): err("examples: none shows a safeword")
    # intense examples must not be shown to bands below their own: examples inherit their band; JOI only from intimate
    for x in ex:
        if x["stage"] == "joi" and BANDS.index(x["band"]) < BANDS.index("intimate"): err(f"{x['id']}: JOI below intimate")

    joi = load(d, "joi.json")
    sid = [s["id"] for s in joi["stages"]]
    for need in ("setup", "warmup", "build", "edge", "hold", "countdown", "permit", "afterglow"):
        if need not in sid: err(f"joi: no stage {need}")
    for s in joi["stages"]:
        if s.get("min_band", "strangers") not in BANDS: err(f"joi {s['id']}: bad min_band")
        if len(s["hint"]) > 230: err(f"joi {s['id']}: hint over 230 chars")
    tw = load(d, "twists.json")["twists"]
    for k in tw:
        if k not in RACES | CLASSES: err(f"twists: unknown key {k}")

    # the Dirty talk levels' few-shot lines (dirty.json): seven levels in the user's order, every JOI stage family, in-world, adults only
    LEVELS = ["cute", "mild", "spicy", "hot", "nasty", "depraved", "wtf"]
    FAMILIES = {"opening": 8, "build": 8, "edge": 8, "countdown": 8, "permit": 8, "afterglow": 8, "live": 5, "chat": 5}
    WELLNESS = ("breath", "inhal", "exhal", "stillness", "mindful", "relax", "doing beautifully", "doing so well", "let your body", "settle into",
                "sink into", "melt into", "lean into", "check in", "sensation")
    MINORS = ("child", "kid ", " boy", " girl", "teen", "minor", "schoolgirl", "daughter")
    dirty = load(d, "dirty.json")
    fam = dirty.get("families", {})
    for st in ("setup", "warmup", "build", "edge", "hold", "countdown", "permit", "afterglow", "live", "chat"):
        if fam.get(st) not in FAMILIES: err(f"dirty: stage {st} has no family")
    if list(dirty.get("levels", {})) != LEVELS: err(f"dirty: levels must be {LEVELS}")
    for lv in LEVELS:
        exs = dirty.get("levels", {}).get(lv, {}).get("examples", {})
        for f, need in FAMILIES.items():
            lines = exs.get(f, [])
            plain = [l for l in lines if not l.startswith(("f|", "m|"))]
            if len(plain) < need: err(f"dirty {lv}/{f}: {len(plain)} lines for anyone (need {need})")
            for l in lines:
                t = " " + l.lower().removeprefix("f|").removeprefix("m|") + " "
                for w in BAD_WORDS:
                    if w in t: err(f"dirty {lv}/{f}: out-of-world word '{w.strip()}' in: {l}")
                for w in MINORS:
                    if w in t: err(f"dirty {lv}/{f}: adults only, '{w.strip()}' in: {l}")
                if lv not in ("cute", "mild"):
                    for w in WELLNESS:
                        if w in t: err(f"dirty {lv}/{f}: wellness language '{w}' in: {l}")
                if len(l) > 330: err(f"dirty {lv}/{f}: line over 330 chars: {l[:40]}")
    for c in concepts:
        if not c.get("hot"): err(f"{c['id']}: no 'hot' (the filthy idea shown from Spicy up)")
        elif len(c["hot"]) > 200: err(f"{c['id']}: hot over 200 chars")

    print(f"concepts {len(concepts)}, care {len(care)}, examples {len(ex)}, joi stages {len(sid)}, twists {len(tw)}")
    for m in errs: print("ERROR", m)
    return 1 if errs else 0


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "android/app/src/main/assets/dialogue/kink")))
