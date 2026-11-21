#!/usr/bin/env python3
"""The alignment audit's bed mode, read (README, "Alignment audit"): engine-test/audit's CSV and joints (AUDIT_JOINTS=1) of
PairAlignmentAudit's bed plan (ALIGN_BED), per case: B's hips from A's as drawn against the author's (3D and up, cm), the
bodies on the bed (each resting body's lowest flesh over the mattress under it, cm; negative: in it), the deepest point of a
body inside the bed and inside the other body.

    python3 -I tools/align/bed.py BED_DIR        (BED_DIR: audit.csv, joints.txt, cases.tsv)
"""
import csv
import statistics
import sys
from pathlib import Path

CM = 91.44  # cm per yard


def main(d: Path) -> int:
    names = {}
    with open(d / "cases.tsv") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            names[row["case"]] = (row["name"], row["class"], row["posture0"], row["posture1"])
    joints = {}
    for line in open(d / "joints.txt"):
        p = line.split()
        if len(p) != 11 or p[0] != "joints":
            continue
        joints[(p[1], int(p[2]), int(p[3]))] = [float(x) for x in p[4:]]
    cases = {}
    for r in csv.reader(open(d / "audit.csv")):
        if not r or r[0] == "case":
            continue
        cid, t, keys = r[0], int(r[1]), dict(kv.split("=", 1) for kv in r[2].split(";") if "=" in kv)
        c = cases.setdefault(cid, {"keys": keys, "rows": []})
        c["rows"].append((t, [float(x) if x not in ("", "nan") else float("nan") for x in r[3:17]]))
    out = []
    for cid, c in cases.items():
        k = c["keys"]
        ams = [int(x) for x in k["ams"].split("/")]
        ahd = [float(x) for x in k["ahd"].split("/")]
        ahz = [float(x) for x in k["ahz"].split("/")]
        role = {int(k["role0"]): 0, int(k["role1"]): 1}   # pair role -> slot
        top = float(k["bedtop"])
        derr, zerr, gaps, low, furn, pen = [], [], [], [], 0.0, 0.0
        for t, v in c["rows"]:
            aa, frac, hand, pel, trunk, same, fd0, ff0, fd1, ff1, fl0, fl1, sk0, sk1 = v
            furn = max(furn, fd0, fd1)
            pen = max(pen, aa)
            for s, fl in ((0, fl0), (1, fl1)):
                if k.get(f"rest{s}") == "lie" and fl == fl:
                    gaps.append(fl)
            ja, jb = joints.get((cid, t, role[0])), joints.get((cid, t, role[1]))
            if ja is None or jb is None or t not in ams:
                continue
            i = ams.index(t)
            dd = sum((jb[n] - ja[n]) ** 2 for n in range(3)) ** 0.5
            derr.append(dd - ahd[i])
            zerr.append((jb[2] - ja[2]) - ahz[i])
            low.append(min(ja[6], jb[6]) - top)
        name, cls, p0, p1 = names.get(cid, (cid, "?", "?", "?"))
        f = lambda xs, fn: fn(xs) * CM if xs else float("nan")
        out.append((cid, name, cls, p0, p1, f(derr, lambda x: max(x, key=abs)), f(derr, statistics.median), f(zerr, lambda x: max(x, key=abs)),
                    f(gaps, min), f(gaps, max), f(low, min), furn * CM, pen * CM, k.get("lift0"), k.get("lift1")))
    print("case\tname\tclass\tposture0\tposture1\thipsDist_worst_cm\thipsDist_med_cm\thipsUp_worst_cm\tlieGap_min_cm\tlieGap_max_cm"
          "\tlowestOverMattress_cm\tinBed_cm\tinOther_cm\tlift0\tlift1")
    for o in sorted(out, key=lambda o: (o[2], o[1])):
        print("\t".join(x if isinstance(x, str) else ("%.1f" % x if x is not None else "") for x in o))
    return 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1])))
