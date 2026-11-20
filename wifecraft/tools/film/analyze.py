#!/usr/bin/env python3
"""What the recorded live scene did (tools/film/README.md): reads a track the JVM recorder wrote (and, when given, the
frames.jsonl engine-test/film wrote beside its pictures) and prints an activity log and the problems it can see: who ate,
slept, chatted, danced, sat and for how long (real seconds and game hours at the track's speed), activities cut short and
started again (flapping), people standing still while walking, turning back and forth, standing inside furniture, talking
without facing each other, standing in each other, animations changed again within a moment, feet off the floor.

    python3 -I tools/film/analyze.py TRACK.jsonl [FRAMES.jsonl] [--json OUT.json]
"""
import json
import math
import sys
from collections import defaultdict


def load(path):
    header, frames, events, cues, end = None, [], [], [], None
    with open(path) as f:
        for line in f:
            d = json.loads(line)
            kind = d.get("type")
            if kind == "header":
                header = d
            elif kind == "frame":
                frames.append(d)
            elif kind == "event":
                events.append(d)
            elif kind == "cue":
                cues.append(d)
            elif kind == "end":
                end = d
    return header, frames, events, cues, end


def angle(a):
    return (a + math.pi) % (2 * math.pi) - math.pi


def clock(hours):
    m = int(hours * 60) % 1440
    return "%02d:%02d" % (m // 60, m % 60)


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    out_json = argv[argv.index("--json") + 1] if "--json" in argv else None
    if out_json in args:
        args.remove(out_json)
    header, frames, events, cues, end = load(args[0])
    side = []
    if len(args) > 1:
        with open(args[1]) as f:
            side = [json.loads(l) for l in f]
    cast = [c["id"] for c in header["cast"]]
    names = {c["id"]: c["name"] for c in header["cast"]}
    per_hour = 60.0 / 1.0 / 6.0  # real seconds a game hour at x6
    dt = header["sampleMs"] / 1000.0
    report = {"plan": header["plan"], "people": {}, "problems": defaultdict(list)}
    print("# %s (%s), %d people, %.1f game hours (%d samples every %d ms, x6: %.0f real s a game hour)" % (
        header["name"], header["plan"], len(cast), len(frames) * dt / per_hour, len(frames), header["sampleMs"], per_hour))
    print("cast: " + ", ".join("%s (%s; %s)" % (c["name"], c["look"], ", ".join(c.get("traits", []))) for c in header["cast"]))
    print()
    print("## Cues (forced moments) and the scene's answers")
    for c in cues:
        if "answer" in c:
            print("  %6.1f s  %s -> %s" % (c["t"] / 1000, c["label"], c["answer"][:160]))
    first_answer = {}
    for c in cues:
        if "answer" not in c:
            first_answer.setdefault(c["label"], c["t"])
    print()

    # ---- episodes per person from the samples: (activity, doing, what the engine plays)
    for i, who in enumerate(cast):
        acts = defaultdict(float)
        doing = defaultdict(float)
        episodes = []
        cur = None
        for fr in frames:
            p = fr["people"][i]
            key = (p["act"], p["doing"], p["clip"] or ("anim %d" % p["anim"]))
            if cur is None or cur[0] != key:
                if cur:
                    episodes.append(cur)
                cur = [key, fr["t"], fr["t"], fr["hour"]]
            cur[2] = fr["t"]
            acts[p["act"]] += dt
            if p["doing"]:
                doing[p["doing"]] += dt
        if cur:
            episodes.append(cur)
        # life episodes (doing runs), with counts and the shortest
        runs = defaultdict(list)
        prev, start = None, 0
        for fr in frames + [None]:
            d = fr["people"][i]["doing"] if fr else None
            if d != prev:
                if prev:
                    runs[prev].append((fr["t"] if fr else frames[-1]["t"]) - start)
                prev, start = d, fr["t"] if fr else 0
        print("## %s" % names[who])
        print("  activity (real s / game h): " + ", ".join("%s %.0f/%.1f" % (k, v, v / per_hour) for k, v in sorted(acts.items(), key=lambda x: -x[1])))
        print("  life (count, total game h, shortest real s): " + ", ".join(
            "%s x%d %.1fh min %.1fs" % (k, len(v), sum(v) / 1000 / per_hour, min(v) / 1000) for k, v in sorted(runs.items(), key=lambda x: -sum(x[1]))))
        short = [r for k, v in runs.items() for r in v if r < 1500 and k not in ("CHAT",)]
        if short:
            report["problems"]["flapping"].append("%s: %d life activities over within 1.5 s (%s)" % (
                names[who], len(short), ", ".join(sorted({k for k, v in runs.items() for r in v if r < 1500}))))
        report["people"][who] = {"activity": acts, "life": {k: {"count": len(v), "gameHours": sum(v) / 1000 / per_hour} for k, v in runs.items()}}

    # ---- the event stream: animation pops (a person's engine animation changed twice within 0.4 s)
    print()
    last_change = {}
    pops = defaultdict(int)
    for e in events:
        if e["kind"] in ("activity", "playback", "gesture"):
            k = e["who"]
            if k in last_change and 0 < e["t"] - last_change[k] < 400:
                pops[k] += 1
            last_change[k] = e["t"]
    for k, n in pops.items():
        report["problems"]["pops"].append("%s: %d animation changes within 0.4 s of the one before" % (names[k], n))
    missing = [e for e in events if e["kind"] == "missing-clip"]
    if missing:
        report["problems"]["missing clips"].append("%d playbacks of clips the screen has no file for: %s" % (len(missing), sorted({e["value"] for e in missing})))

    # ---- per-sample checks
    stuck = defaultdict(float)
    still_walk = defaultdict(int)
    jitter = defaultdict(int)
    inside = defaultdict(lambda: defaultdict(int))
    chats_bad = defaultdict(int)
    chats_all = defaultdict(int)
    overlap = defaultdict(int)
    idle_long = defaultdict(float)
    prev = None
    for fr in frames:
        ps = fr["people"]
        for i, p in enumerate(ps):
            who = cast[i]
            if prev:
                q = prev["people"][i]
                moved = math.hypot(p["x"] - q["x"], p["y"] - q["y"])
                if p["act"] == "WALK" and q["act"] == "WALK" and moved < 0.02:
                    still_walk[who] += 1
                if p["act"] == q["act"] and p["act"] not in ("WALK",) and abs(angle(p["f"] - q["f"])) > 1.4 and moved < 0.05 and not p["clip"]:
                    jitter[who] += 1
            if p["inFurniture"]:
                inside[who][p["inFurniture"] + (" walking" if p["act"] == "WALK" else " standing")] += 1
            if p["act"] in ("IDLE",) and not p["doing"]:
                idle_long[who] += dt
            if p["with"] and p["act"] in ("CHAT", "LISTEN"):
                j = cast.index(p["with"])
                o = ps[j]
                if o["act"] in ("CHAT", "LISTEN"):
                    want = math.atan2(o["y"] - p["y"], o["x"] - p["x"])
                    chats_all[who] += 1
                    if abs(angle(p["f"] - want)) > math.radians(35):
                        chats_bad[who] += 1
            for j in range(i + 1, len(ps)):
                o = ps[j]
                if p["pose"] == 0 and o["pose"] == 0 and not p["clip"] and not o["clip"] and p["partner"] != cast[j]:
                    if math.hypot(p["x"] - o["x"], p["y"] - o["y"]) < 0.35:
                        overlap[(who, cast[j])] += 1
        prev = fr
    for k, n in still_walk.items():
        if n >= 4:
            report["problems"]["walking in place"].append("%s: %d samples walking without moving (%.1f s)" % (names[k], n, n * dt))
    for k, n in jitter.items():
        if n:
            report["problems"]["turning"].append("%s: %d samples turned more than 80 degrees in a quarter second while standing" % (names[k], n))
    for k, d in inside.items():
        report["problems"]["inside furniture"].append("%s: %s (samples)" % (names[k], dict(d)))
    for k, n in chats_bad.items():
        if n:
            report["problems"]["not facing in conversation"].append("%s: %d of %d talking samples turned more than 35 degrees from the other" % (names[k], n, chats_all[k]))
    for (a, b), n in overlap.items():
        report["problems"]["standing in each other"].append("%s and %s: %d samples closer than 0.35 yd" % (names[a], names[b], n))

    # ---- conversations: their length in game time
    talks = []
    open_ = {}
    for fr in frames:
        for i, p in enumerate(fr["people"]):
            w = p["with"] if p["act"] in ("CHAT",) else None
            k = cast[i]
            if w and k not in open_:
                open_[k] = (fr["t"], w)
            if not w and k in open_:
                t0, w0 = open_.pop(k)
                talks.append((k, w0, t0, fr["t"]))
    if talks:
        lens = [(b - a) / 1000 for _, _, a, b in talks]
        print("## Conversations: %d, %.0f to %.0f real s each (%.1f to %.1f game hours at x6)" % (len(talks), min(lens), max(lens), min(lens) / per_hour, max(lens) / per_hour))
        if max(lens) / per_hour > 1.5:
            report["problems"]["conversation length"].append("conversations take real reading time: the longest %.0f s is %.1f game hours at x6 (needs fall meanwhile)" % (max(lens), max(lens) / per_hour))

    # ---- the renderer's sidecar: feet over the floor
    if side:
        offs = defaultdict(list)
        for s in side:
            for i, v in enumerate(s["overFloor"]):
                if v is not None:
                    offs[cast[i]].append(v)
        for k, v in offs.items():
            bad = [x for x in v if abs(x) > 0.08]
            print("feet over floor %s: %d drawn frames, worst %+.3f yd, %d off by more than 0.08" % (names[k], len(v), max(v, key=abs), len(bad)))
            if bad:
                report["problems"]["feet off the floor"].append("%s: %d frames, worst %+.3f yd" % (names[k], len(bad), max(v, key=abs)))

    # ---- the end: feed and relationships
    if end:
        print()
        print("## Life feed (newest last)")
        for e in reversed(end["feed"]):
            print("  day %d %s  %s" % (int(e["hours"] // 24) + 1, clock(e["hours"]), e["text"]))
        print("## Relationships at the end")
        for r in end["relationships"]:
            print("  %s & %s: friendship %.1f (%s), romance %.1f (%s), allows %s" % (names[r["a"]], names[r["b"]], r["friendship"], r["friendTier"], r["romance"], r["romanceTier"], r["allows"]))
        print("  walk tests asked of the engine: %d; life decisions: %d" % (end["walkTests"], end["decisions"]))
    print()
    print("## Problems seen")
    if not report["problems"]:
        print("  none")
    for k, v in report["problems"].items():
        for line in v:
            print("  [%s] %s" % (k, line))
    if out_json:
        with open(out_json, "w") as f:
            json.dump(report, f, indent=1, default=dict)


if __name__ == "__main__":
    main(sys.argv[1:])
