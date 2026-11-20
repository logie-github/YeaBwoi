"""The placement audit's report (tools/audit/README.md): reads engine-test/audit's CSVs and the planner's cases.tsv, takes
each case's worst moment, flags it and ranks the worst first.

  python3 -I report.py AUDIT_DIR [--json OUT.json] [--top N]

Writes AUDIT_DIR/report.txt (the ranked list, the per-placement statistics and the per-animation summary) and prints it.
Flags (yards): two bodies inside each other (body capsules, hands apart) over 0.04 (bad over 0.10) or on one axis
(pelvises under 0.25 apart or trunks overlapping by 0.12); a body inside furniture over 0.04 (bad over 0.10); a resting
body floating over its surface more than 0.06 (bad over 0.12); under the floor more than 0.03 (bad over 0.08)."""
import csv
import glob
import json
import os
import sys
from collections import defaultdict

THRESH = {  # (minor, bad)
    "overlap": (0.04, 0.10),
    "furniture": (0.04, 0.10),
    "float": (0.06, 0.12),
    "sink": (0.03, 0.08),
}


def num(v):
    try:
        x = float(v)
        return x if x == x else None
    except (TypeError, ValueError):
        return None


def load(d):
    meta = {}
    with open(os.path.join(d, "cases.tsv")) as f:
        for row in csv.DictReader(f, delimiter="\t"):
            meta[row["case"]] = row
    rows = defaultdict(list)
    for path in sorted(glob.glob(os.path.join(d, "audit_*.csv"))):
        with open(path) as f:
            for row in csv.DictReader(f):
                rows[row["case"]].append(row)
    return meta, rows


def worst(case, rs):
    """The case's worst values over its moments, and its flags."""
    keys = dict(kv.split("=", 1) for kv in rs[0]["keys"].split(";") if "=" in kv)
    w = dict(overlap=0.0, frac=0.0, hand=0.0, pelvis=None, trunk=None, same=0, furniture=0.0, ffrac=0.0, float=None, sink=0.0,
             body_sink=0.0, t_overlap=None, t_furniture=None, region="")
    for r in rs:
        t = int(r["t"])
        o = num(r["aa_body_depth"]) or 0.0
        if o > w["overlap"]:
            w["overlap"], w["t_overlap"] = o, t
        w["frac"] = max(w["frac"], num(r["aa_body_frac"]) or 0.0)
        w["hand"] = max(w["hand"], num(r["aa_hand_depth"]) or 0.0)
        p = num(r["pelvis_dist"])
        if p is not None:
            w["pelvis"] = p if w["pelvis"] is None else min(w["pelvis"], p)
        tg = num(r["trunk_gap"])
        if tg is not None:
            w["trunk"] = tg if w["trunk"] is None else min(w["trunk"], tg)
        w["same"] = max(w["same"], int(r["same_axis"]))
        for s in (0, 1):
            fd = num(r[f"f{s}_depth"]) or 0.0
            if fd > w["furniture"]:
                w["furniture"], w["t_furniture"], w["region"] = fd, t, r[f"worst_region{s}"]
            w["ffrac"] = max(w["ffrac"], num(r[f"f{s}_frac"]) or 0.0)
            rest = keys.get(f"rest{s}", "stand")
            fl = num(r[f"float{s}"])
            if rest != "stand" and fl is not None:
                w["float"] = fl if w["float"] is None else max(w["float"], fl)
            w["sink"] = max(w["sink"], num(r[f"sink{s}"]) or 0.0)
            # the same leaving out what hangs off the body (a tail, a cape): informational, not a flag (older CSVs lack it)
            w["body_sink"] = max(w["body_sink"], num(r.get(f"body_sink{s}")) or 0.0)
    flags = []
    sev = 0.0

    def check(name, value, label):
        nonlocal sev
        if value is None:
            return
        lo, hi = THRESH[name]
        if value > lo:
            flags.append(f"{label} {value:.2f}{' BAD' if value > hi else ''}")
            sev = max(sev, value / hi)
    check("overlap", w["overlap"], "bodies overlap")
    if w["same"]:
        flags.append("SAME AXIS")
        sev = max(sev, 1.5)
    check("furniture", w["furniture"], f"in furniture ({w['region']})")
    check("float", w["float"], "floats")
    check("sink", w["sink"], "under floor")
    w["flags"] = flags
    w["severity"] = sev
    w["verdict"] = "bad" if sev >= 1.0 else ("minor" if flags else "ok")
    w["keys"] = keys
    return w


def main():
    d = sys.argv[1]
    top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 40
    meta, rows = load(d)
    res = {c: worst(c, rs) for c, rs in rows.items()}
    out = []
    ranked = sorted(res.items(), key=lambda kv: -kv[1]["severity"])
    out.append(f"# Placement audit: {len(res)} cases, {sum(len(v) for v in rows.values())} measured moments")
    out.append("")
    out.append(f"## Worst {top} (severity: the worst measure over its 'bad' threshold)")
    for c, w in ranked[:top]:
        t = w["t_overlap"] if w["overlap"] > THRESH["overlap"][0] else w["t_furniture"]
        out.append(f"{w['severity']:5.2f}  {c}  t={t}  {'; '.join(w['flags']) or 'ok'}"
                   f"  (pelvis {w['pelvis'] if w['pelvis'] is None else round(w['pelvis'], 2)}, trunk gap "
                   f"{w['trunk'] if w['trunk'] is None else round(w['trunk'], 2)}, hands {w['hand']:.2f})")
    out.append("")
    # per placement class: (kind, place)
    out.append("## By placement (yards: max / mean of each case's worst)")
    out.append(f"{'class':28s} {'cases':>5s} {'bad':>4s} {'minor':>5s} {'overlap max/mean':>17s} {'furniture max/mean':>19s} "
               f"{'float max/mean':>15s} {'sink max':>8s} {'same axis':>9s}")
    groups = defaultdict(list)
    for c, w in res.items():
        m = meta.get(c, {})
        groups[f"{m.get('kind', '?')}:{m.get('place', '?')}"].append(w)
    for g in sorted(groups):
        ws = groups[g]

        def mm(k):
            vals = [w[k] for w in ws if w[k] is not None]
            return (max(vals), sum(vals) / len(vals)) if vals else (0.0, 0.0)
        o, fu, fl = mm("overlap"), mm("furniture"), mm("float")
        out.append(f"{g:28s} {len(ws):5d} {sum(w['verdict'] == 'bad' for w in ws):4d} {sum(w['verdict'] == 'minor' for w in ws):5d} "
                   f"{o[0]:8.2f}/{o[1]:<8.2f} {fu[0]:9.2f}/{fu[1]:<9.2f} {fl[0]:7.2f}/{fl[1]:<7.2f} {mm('sink')[0]:8.2f} "
                   f"{sum(w['same'] for w in ws):9d}")
    out.append("")
    out.append("## By animation and placement (cases: bad / minor / ok, the worst case's flags)")
    by = defaultdict(list)
    for c, w in res.items():
        m = meta.get(c, {})
        by[(m.get("anim", "?"), m.get("kind", "?"), m.get("place", "?"))].append((c, w))
    for k in sorted(by):
        ws = by[k]
        bad = sum(w["verdict"] == "bad" for _, w in ws)
        minor = sum(w["verdict"] == "minor" for _, w in ws)
        c, w = max(ws, key=lambda cw: cw[1]["severity"])
        out.append(f"{k[0]:12s} {k[1]:6s} {k[2]:7s} {bad:3d} / {minor:3d} / {len(ws) - bad - minor:3d}   worst {c}: {'; '.join(w['flags']) or 'ok'}")
    text = "\n".join(out) + "\n"
    open(os.path.join(d, "report.txt"), "w").write(text)
    if "--json" in sys.argv:
        json.dump({c: {k: v for k, v in w.items()} for c, w in res.items()}, open(sys.argv[sys.argv.index("--json") + 1], "w"), indent=0)
    print(text)


if __name__ == "__main__":
    main()
