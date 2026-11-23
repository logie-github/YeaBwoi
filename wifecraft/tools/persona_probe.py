#!/usr/bin/env python3
"""Prints the persona pieces the dialogue prompt gets for a character in an era, for review (mirrors dialogue/PersonaKit.kt).

  python3 -I tools/persona_probe.py <character-id> <era-id> [--present id,id] [--say "text the talk mentions"]
  python3 -I tools/persona_probe.py --list
"""
import argparse, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import persona_kit as K


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("character", nargs="?")
    ap.add_argument("era", nargs="?")
    ap.add_argument("--present", default="")
    ap.add_argument("--say", default="")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    idx, eras, profiles = K.load()
    if a.list or not a.character or not a.era:
        print("characters:", ", ".join(profiles)); print("eras:", ", ".join(e["id"] for e in eras)); return 0
    p = profiles.get(a.character)
    e = next((x for x in eras if x["id"] == a.era), None)
    if not p or not e:
        print("unknown character or era (--list)"); return 1
    core, sl = K.core(p, e, eras), K.era_slice(p, e)
    print(core); print(); print(sl); print()
    print("[tokens: core %d/%d, era %d/%d]" % (K.tokens(core), K.BUDGET["CORE"], K.tokens(sl), K.BUDGET["ERA"]))
    low = a.say.lower()
    notes = []
    for pid in [x for x in a.present.split(",") if x]:
        r = p["relationships"].get(pid)
        if r and order_ok(eras, r, e):
            notes.append("About them: " + K.relation_text(r, e, eras))
    for r in p["relationships"].values():
        if order_ok(eras, r, e) and (r["name"].lower() in low or r["name"].split()[0].lower() in low) and not any(r["name"] in n for n in notes) and len(notes) < K.BUDGET["REL_MAX"]:
            notes.append("About them: " + K.relation_text(r, e, eras))
    tops = []
    for t in p.get("backstory", []) + p.get("topics", []):
        if "from" in t and K.order(eras, t["from"]) > e["order"]:
            continue
        hits = sum(1 for k in t["keys"] if k.lower() in low)
        if hits:
            tops.append((hits, "You recall: " + K.fit(t["text"], K.BUDGET["TOPIC"])))
    notes += [x for _, x in sorted(tops, key=lambda x: -x[0])[:K.BUDGET["TOPIC_MAX"]]]
    if notes:
        print("\n".join(notes))
    bad = K.find_blocked(core + " " + sl + " " + " ".join(notes), eras, e)
    if bad:
        print("WARNING: later-era term in the prompt:", bad)
    return 0


def order_ok(eras, r, e):
    return (K.order(eras, r["from"]) if r.get("from") else -1) <= e["order"]


if __name__ == "__main__":
    sys.exit(main())
