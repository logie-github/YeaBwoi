#!/usr/bin/env python3
"""Expands the kink pack's examples into a JSONL chat dataset: an OPTIONAL LoRA training set.

Nothing in the app is trained from this. The app uses the pack by retrieval (KinkPack). This file exists so that someone
with a GPU can later fine-tune a small model on the same original text. Each line is {"messages": [system, user, assistant]}
and every text in it is the pack's own original wording.

  python3 -I tools/dialogue/finetune_dataset.py [pack_dir] [out.jsonl]
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_PACK = os.path.join(HERE, "..", "..", "android/app/src/main/assets/dialogue/kink")

SYSTEM = ("You voice one adult character in a fantasy world of Azeroth (Warcraft), in an adult 18+ game. "
          "Everyone is a consenting adult. Stay in the world's voice, no modern slang. You enjoy the play and you care for your partner: "
          "check in, honour any safeword at once, and offer aftercare. Reply with your character's next line only.")


def load(d, n):
    with open(os.path.join(d, n), encoding="utf-8") as f:
        return json.load(f)


def sys_prompt(x):
    parts = [SYSTEM, f"Comfort between the two: {x['band']}.", f"Scene: {x['stage']}."]
    if x.get("pers"): parts.append("Personality: " + ", ".join(x["pers"]) + ".")
    if x.get("races"): parts.append("Races or classes: " + " and ".join(x["races"]) + ".")
    if x.get("role", "any") != "any": parts.append(f"Your role: {x['role']}.")
    return " ".join(parts)


def rows(pack):
    ex = load(pack, "examples.json")["examples"]
    for x in ex:
        lines = x["lines"]
        # each exchange becomes one sample per reply: the earlier lines are context, the line is the target
        for k in range(1, len(lines)):
            ctx = "\n".join(f"{l['who']}: {l['text']}" for l in lines[:k])
            who = lines[k]["who"]
            yield {"messages": [
                {"role": "system", "content": sys_prompt(x) + f" You are {who}."},
                {"role": "user", "content": ctx},
                {"role": "assistant", "content": lines[k]["text"]},
            ], "meta": {"id": x["id"], "band": x["band"], "stage": x["stage"]}}
    # the care layer: one sample per care example, so check-ins and aftercare are part of the style
    for c in load(pack, "care.json")["entries"]:
        for t in c.get("examples", []):
            yield {"messages": [
                {"role": "system", "content": SYSTEM + f" Comfort between the two: {c['band']}. Moment: {c['kind']}."},
                {"role": "user", "content": "(The scene reaches a point where a caring partner would say something.)"},
                {"role": "assistant", "content": t},
            ], "meta": {"id": c["id"], "band": c["band"], "stage": "care"}}


def main(pack, out):
    n = 0
    with open(out, "w", encoding="utf-8") as f:
        for r in rows(pack):
            f.write(json.dumps(r, ensure_ascii=False) + "\n"); n += 1
    print(f"{n} samples -> {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PACK, sys.argv[2] if len(sys.argv) > 2 else "kink_finetune.jsonl")
