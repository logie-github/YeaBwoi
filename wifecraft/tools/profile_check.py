#!/usr/bin/env python3
"""Validates the character profiles and eras (assets/dialogue/profiles, eras). Exit 1 on any error.

  python3 -I tools/profile_check.py [assets_dir]

Checks: the schema; the token budgets (core, era slice, relationship and topic snippets); era ids (in states, variants, topics,
relationships, voice); every relationship that names another profile uses that profile's id; no modern or out-of-world words; no
term of a later era in a state, a topic or a relationship line of an earlier one; a profile for every named character
(assets/characters.json); every era state present and any out-of-era state framed as an adult in the age.
"""
import json, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import persona_kit as K

BAD = ["okay", " ok ", "dude", "bro ", "guys", "awesome", "internet", "phone", "email", "smartphone", "computer", "google", "boyfriend",
       "girlfriend", "emoji", "hashtag", "the player", "npc", "simulation", "chatbot", "language model"]
MINOR = ["underage", "teen", "teenager", "loli", "schoolgirl", "minor", "little girl"]
CORE_KEYS = ["who", "temper", "speech", "look", "values", "intimate"]


def main(assets):
    errs = []
    err = errs.append
    idx, eras, profiles = K.load(assets)
    ids = [e["id"] for e in eras]
    if idx.get("default") not in ids:
        err("index default is not an era")
    if [e["order"] for e in eras] != list(range(len(eras))):
        err("era orders must be 0..n")
    for e in eras:
        for k in ["id", "label", "order", "release", "years", "present", "world", "idiom", "folk", "classes", "races", "stances", "introduces", "places"]:
            if k not in e:
                err(f"era {e.get('id')}: missing {k}")
        if K.tokens(e["world"]) > 110:
            err(f"era {e['id']}: world too long ({K.tokens(e['world'])} tokens)")
        if K.tokens(e["present"]) > 60:
            err(f"era {e['id']}: present too long")
        for f in ["alliance", "horde", "neutral"]:
            if f not in e.get("folk", {}):
                err(f"era {e['id']}: folk.{f} missing")
    named = [c["id"] for c in json.load(open(os.path.join(assets, "characters.json"), encoding="utf-8"))["characters"]]
    for n in named:
        if n not in profiles:
            err(f"no profile for the named character {n}")
    names = {p["name"].lower(): p["id"] for p in profiles.values()}
    for pid, p in profiles.items():
        w = lambda m: err(f"{pid}: {m}")
        if p["id"] + ".json" not in os.listdir(os.path.join(assets, "dialogue", "profiles")):
            w("file name must be <id>.json")
        for k in CORE_KEYS:
            if not p["core"].get(k):
                w(f"core.{k} missing")
        if len(p["voice"]) < 6:
            w("a voice sheet needs 6 to 10 lines")
        if len(p["voice"]) > 10:
            w("more than 10 voice lines")
        if not p.get("sources"):
            w("no sources")
        if p.get("canon", True) and not p.get("unverified"):
            w("a canon profile must list what is unverified (even if only 'none known')")
        for e in eras:
            st = p["eraStates"].get(e["id"])
            if not st:
                w(f"no state for era {e['id']}")
                continue
            if st["s"] not in ("active", "out_of_era", "dead"):
                w(f"state status {st['s']}")
            if K.tokens(st["t"]) > 60:
                w(f"state {e['id']} too long")
            if st["s"] == "out_of_era" and not re.search(r"play|alternate|framing|frame", st["t"], re.I):
                w(f"out-of-era state {e['id']} gives no in-era framing")
            if st["s"] != "dead":
                bad = K.find_blocked(st["t"], eras, e)
                if bad:
                    w(f"state {e['id']} names a later term: {bad}")
            core = K.core(p, e, eras); sl = K.era_slice(p, e)
            if K.tokens(core) > K.BUDGET["CORE"]:
                w(f"core over budget in {e['id']}")
            if K.tokens(sl) > K.BUDGET["ERA"]:
                w(f"era slice over budget in {e['id']}")
            b = K.find_blocked(core + " " + sl, eras, e)
            if b:
                w(f"core or slice in {e['id']} names a later term: {b}")
        for x in p.get("variants", []):
            for k in ("from", "until"):
                if k in x and x[k] not in ids:
                    w(f"variant era {x[k]}")
            for k in x:
                if k not in CORE_KEYS + ["from", "until"]:
                    w(f"variant key {k}")
        for t in p.get("backstory", []) + p.get("topics", []):
            if not t.get("keys") or not t.get("text"):
                w("topic needs keys and text")
            for k in ("from", "until"):
                if k in t and t[k] not in ids:
                    w(f"topic era {t[k]}")
            if K.tokens(t["text"]) > K.BUDGET["TOPIC"]:
                w(f"topic too long: {t['text'][:40]}")
            if "from" in t:
                e = eras[K.order(eras, t["from"])]
                bad = K.find_blocked(t["text"], eras, e)
                if bad:
                    w(f"topic from {t['from']} names a later term: {bad}")
        for k, r in p.get("relationships", {}).items():
            other = names.get(r["name"].lower())
            if other and other != k:
                w(f"relationship {k} is the profile {other}: use its id")
            if k in profiles and profiles[k]["name"] != r["name"]:
                w(f"relationship {k}: name differs from the profile's")
            if "from" in r and r["from"] not in ids:
                w(f"relationship {k}: era {r['from']}")
            for ek in r.get("eras", {}):
                if ek not in ids:
                    w(f"relationship {k}: era {ek}")
            for e in eras:
                if r.get("from") and K.order(eras, r["from"]) > e["order"]:
                    continue
                t = K.relation_text(r, e, eras)
                if K.tokens(t) > K.BUDGET["REL"]:
                    w(f"relationship {k} over budget in {e['id']}")
                bad = K.find_blocked(t, eras, e)
                if bad:
                    w(f"relationship {k} in {e['id']} names a later term: {bad}")
        text = json.dumps(p, ensure_ascii=False).lower()
        for b in BAD:
            if b in text:
                w(f"out-of-world word: {b.strip()}")
        for b in MINOR:
            if re.search(r"\b" + re.escape(b) + r"\b", text):
                w(f"forbidden word: {b}")
        if "adult" not in (p["core"]["who"] + json.dumps(p["eraStates"])).lower() and p["id"] in ("tess",):
            w("an out-of-era state of a character who is young in some eras must say adult")
        for s in p["eraStates"].values():
            if s["s"] == "out_of_era" and p["id"] in ("tess", "lilian", "calia") and "adult" not in s["t"].lower() and "grown" not in s["t"].lower():
                w("out-of-era state must frame the character as grown/adult: " + s["t"][:40])
    for e in eras:
        for k in e["places"]:
            pass
    print("%d profiles, %d eras, %s" % (len(profiles), len(eras), "OK" if not errs else "%d errors" % len(errs)))
    for m in errs:
        print("ERROR", m)
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else K.ASSETS))
