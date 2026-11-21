#!/usr/bin/env python3
"""Move mode's item reports into the app's item overrides.

Lists the open GitHub issues labelled `item-report` on this repository (the app files one per placement the player sends
from Move mode), reads the fenced ```json block of each, checks it, and merges every item's reports into
`android/app/src/main/assets/item_overrides.json` (keyed by place/object/model: the class most reports gave, and for
each class reported the median held place and facing, the commonest pose, the surface's height and the notes). With
three reports or more of one class, those far from the median (0.35 yards, 45 degrees) are left out and listed as
outliers. The same rules as the app's ItemOverrides.merge (simulation/ItemMapping.kt).

Issues are never closed or changed here: close them yourself once their item is merged.

Usage:
  tools/item_reports.py [--repo OWNER/NAME] [--out FILE] [--dry-run] [--from-json FILE] [--state open|all]

The token comes from $GITHUB_TOKEN or $GH_TOKEN, else from `gh auth token`; --from-json reads a saved list of issues
(the API's own JSON) instead. Standard library only.
"""

import argparse
import json
import math
import os
import re
import subprocess
import sys
import urllib.request

SCHEMA = 1
LABEL = "item-report"
CLASSES = ("seat", "tabletop", "floor", "bed")
POSES = ("stand", "sit", "lie")
OUTLIER_YARDS = 0.35
OUTLIER_DEG = 45.0
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "item_overrides.json")
BLOCK = re.compile(r"```json\s*\n(.*?)\n```", re.S)


def wrap_deg(a):
    d = math.fmod(a, 360.0)
    if d > 180.0:
        d -= 360.0
    if d <= -180.0:
        d += 360.0
    return d


def median(values):
    s = sorted(values)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def validate(o):
    """What is wrong with a report's JSON; empty: it is good."""
    bad = []
    if not isinstance(o, dict):
        return ["not an object"]
    if o.get("schema") != SCHEMA:
        bad.append("schema is not %d" % SCHEMA)
    if not (o.get("place") or {}).get("id"):
        bad.append("no place id")
    item = o.get("item") or {}
    if not item.get("key") or not item.get("object"):
        bad.append("no item key")
    if o.get("class") not in CLASSES:
        bad.append("unknown class %r" % o.get("class"))
    off = o.get("offset") or {}
    for k in ("x", "y", "z", "yaw"):
        v = off.get(k)
        if not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(v):
            bad.append("offset missing or not numbers")
            break
    if (o.get("pose") or {}).get("id") not in POSES:
        bad.append("unknown pose")
    return bad


def sample(o, issue):
    """A good report as (key, sample dict)."""
    item = o["item"]
    surface = None
    surfaces = item.get("surfaces") or []
    if surfaces and isinstance(surfaces[0].get("height"), (int, float)):
        surface = float(surfaces[0]["height"])
    elif isinstance((item.get("fit") or {}).get("surface"), (int, float)) and item["fit"]["surface"] > 0:
        surface = float(item["fit"]["surface"])
    notes = "; ".join(x for x in (o.get("notes") or "", ", ".join(o.get("flags") or [])) if x.strip())
    off = o["offset"]
    return item["key"], {
        "issue": issue, "class": o["class"], "x": float(off["x"]), "y": float(off["y"]), "z": float(off["z"]),
        "yaw": float(off["yaw"]), "pose": o["pose"]["id"], "surface": surface, "notes": notes,
        "detected": item.get("detected_class"),
    }


def median_place(samples):
    ref = samples[0]["yaw"]
    yaw = wrap_deg(ref + median([wrap_deg(s["yaw"] - ref) for s in samples]))
    return {"x": median([s["x"] for s in samples]), "y": median([s["y"] for s in samples]),
            "z": median([s["z"] for s in samples]), "yaw": yaw}


def merge(key, samples):
    """Several reports of one item as one override (ItemOverrides.merge's rules)."""
    if not samples:
        return None
    by_class = {}
    for s in samples:
        by_class.setdefault(s["class"], []).append(s)
    top = max(len(v) for v in by_class.values())
    cls = next(s["class"] for s in sorted(samples, key=lambda s: -s["issue"]) if len(by_class[s["class"]]) == top)
    spots = {}
    for c, group in by_class.items():
        kept = group
        med = median_place(kept)
        outliers = []
        if len(group) >= 3:
            kept = []
            for s in group:
                far = (math.hypot(s["x"] - med["x"], s["y"] - med["y"]) > OUTLIER_YARDS or abs(s["z"] - med["z"]) > OUTLIER_YARDS
                       or abs(wrap_deg(s["yaw"] - med["yaw"])) > OUTLIER_DEG)
                if far:
                    outliers.append(s["issue"])
                else:
                    kept.append(s)
            if not kept:
                kept, outliers = group, []
            med = median_place(kept)
        counts = {}
        for s in kept:
            n, newest = counts.get(s["pose"], (0, 0))
            counts[s["pose"]] = (n + 1, max(newest, s["issue"]))
        pose = max(counts.items(), key=lambda kv: kv[1])[0]
        spots[c] = {k: round(v, 3) for k, v in med.items()}
        spots[c].update({"pose": pose, "reports": len(kept), "outliers": sorted(outliers)})
    surfaces = [s["surface"] for s in samples if s["class"] == cls and s["surface"] is not None]
    notes = []
    for s in samples:
        n = s["notes"].strip()
        if n and n not in notes:
            notes.append(n)
    detected = sorted({s["detected"] for s in samples if s.get("detected")})
    return {
        "class": cls,
        "surface": round(median(surfaces), 3) if surfaces else None,
        "spots": spots,
        "notes": notes,
        "issues": sorted({s["issue"] for s in samples if s["issue"] > 0}),
        "detected": detected,
    }


def token():
    t = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if t:
        return t
    try:
        return subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, check=True).stdout.strip() or None
    except (OSError, subprocess.CalledProcessError):
        return None


def repo_name():
    try:
        url = subprocess.run(["git", "-C", HERE, "remote", "get-url", "origin"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None
    m = re.search(r"github\.com[:/]([^/]+/[^/.]+?)(?:\.git)?$", url) or re.search(r"/git/([^/]+/[^/]+?)(?:\.git)?$", url)
    return m.group(1) if m else None


def fetch_issues(repo, tok, state="open"):
    issues, page = [], 1
    while True:
        url = "https://api.github.com/repos/%s/issues?labels=%s&state=%s&per_page=100&page=%d" % (repo, LABEL, state, page)
        req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "item-reports-tool",
                                                   "X-GitHub-Api-Version": "2022-11-28"})
        if tok:
            req.add_header("Authorization", "Bearer " + tok)
        with urllib.request.urlopen(req, timeout=30) as r:
            batch = json.load(r)
        issues += [i for i in batch if "pull_request" not in i]
        if len(batch) < 100:
            return issues
        page += 1


def read_reports(issues):
    """(samples by key, problems): every issue's JSON block checked."""
    by_key, problems = {}, []
    for issue in issues:
        number = issue.get("number", 0)
        m = BLOCK.search(issue.get("body") or "")
        if not m:
            problems.append((number, ["no json block"]))
            continue
        try:
            o = json.loads(m.group(1))
        except ValueError as e:
            problems.append((number, ["json: %s" % e]))
            continue
        bad = validate(o)
        if bad:
            problems.append((number, bad))
            continue
        key, s = sample(o, number)
        by_key.setdefault(key, []).append(s)
    return by_key, problems


def build(by_key):
    items = {}
    for key in sorted(by_key):
        merged = merge(key, by_key[key])
        if merged:
            items[key] = merged
    return {"schema": SCHEMA, "comment": "Made by wifecraft/tools/item_reports.py from the open item-report issues (Move mode's reports). "
            "Keys: place/object/model. Do not edit by hand: run the tool again.", "items": items}


def summary(data, problems, issues):
    lines = ["%d issue(s) read, %d item(s) merged, %d skipped." % (len(issues), len(data["items"]), len(problems))]
    for key, o in data["items"].items():
        spot = o["spots"].get(o["class"], {})
        d = (" (measured as %s)" % ", ".join(o["detected"])) if o.get("detected") and o["class"] not in o["detected"] else ""
        lines.append("  %s: %s%s, %s at x %.2f y %.2f z %.2f yaw %.0f from %d report(s)%s; issues %s" % (
            key, o["class"], d, spot.get("pose"), spot.get("x", 0), spot.get("y", 0), spot.get("z", 0), spot.get("yaw", 0),
            spot.get("reports", 0), (", outliers %s" % spot["outliers"]) if spot.get("outliers") else "",
            " ".join("#%d" % n for n in o["issues"])))
    for number, bad in problems:
        lines.append("  skipped #%d: %s" % (number, "; ".join(bad)))
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", help="owner/name (default: this checkout's origin)")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--dry-run", action="store_true", help="print the summary, write nothing")
    ap.add_argument("--from-json", help="a saved list of issues (the API's JSON) instead of asking GitHub")
    ap.add_argument("--state", default="open", choices=("open", "all"))
    a = ap.parse_args(argv)
    if a.from_json:
        with open(a.from_json) as f:
            issues = json.load(f)
    else:
        repo = a.repo or repo_name()
        if not repo:
            sys.exit("No repository: pass --repo OWNER/NAME.")
        issues = fetch_issues(repo, token(), a.state)
    by_key, problems = read_reports(issues)
    data = build(by_key)
    print(summary(data, problems, issues))
    if not a.dry_run:
        with open(a.out, "w") as f:
            json.dump(data, f, indent=1)
            f.write("\n")
        print("Wrote %s" % os.path.relpath(a.out))


if __name__ == "__main__":
    main()
