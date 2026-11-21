#!/usr/bin/env python3
"""Move mode's item reports and build mode's build reports into the app's shipped data.

Lists the open GitHub issues labelled `item-report` on this repository (the app files one per placement the player sends
from Move mode), reads the fenced ```json block of each, checks it, and merges every item's reports into
`android/app/src/main/assets/item_overrides.json` (keyed by place/object/model: the class most reports gave, and for
each class reported the median held place and facing, the commonest pose, the surface's height and the notes). With
three reports or more of one class, those far from the median (0.35 yards, 45 degrees) are left out and listed as
outliers. The same rules as the app's ItemOverrides.merge (simulation/ItemMapping.kt).

Build mode's reports (issues labelled `build-report`, schema 2: one per Save and send) are read too: their items (a
class, the animation classes ticked, the surface's height, the anchor) join the item reports in item_overrides.json (the
class most gave, the median anchor, the commonest set of ticks); their markers are merged into
`assets/place_markers.json` by place (markers of the same type and name: the median place and facing, far ones flagged
as outliers); their remembered race placements into `assets/race_offsets.json` (one per animation, role, race or size
class, scope, furniture class and item: the median delta, far ones flagged). The app reads all three.

Issues are never closed or changed here: close them yourself once their item is merged.

Usage:
  tools/item_reports.py [--repo OWNER/NAME] [--out FILE] [--markers-out FILE] [--race-out FILE] [--dry-run]
                        [--from-json FILE] [--state open|all]

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
BUILD_SCHEMA = 2
LABEL = "item-report"
BUILD_LABEL = "build-report"
CLASSES = ("seat", "tabletop", "floor", "bed", "wall", "other")
SUPPORTS = ("lying", "sitting", "kneeling", "standing", "leaning")
MARKER_TYPES = ("stand", "sit", "lie", "kneel", "pair_origin", "camera")
SCOPES = ("pair", "role", "size")
POSES = ("stand", "sit", "lie")
OUTLIER_YARDS = 0.35
OUTLIER_DEG = 45.0
HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "android", "app", "src", "main", "assets")
DEFAULT_OUT = os.path.join(ASSETS, "item_overrides.json")
DEFAULT_MARKERS = os.path.join(ASSETS, "place_markers.json")
DEFAULT_RACE = os.path.join(ASSETS, "race_offsets.json")
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
    ticks = {}
    for s in samples:
        if s["class"] == cls and s.get("supports"):
            t = tuple(s["supports"])
            n, newest = ticks.get(t, (0, 0))
            ticks[t] = (n + 1, max(newest, s["issue"]))
    supports = list(max(ticks.items(), key=lambda kv: kv[1])[0]) if ticks else []
    return {
        "class": cls,
        "surface": round(median(surfaces), 3) if surfaces else None,
        "spots": spots,
        "notes": notes,
        "issues": sorted({s["issue"] for s in samples if s["issue"] > 0}),
        "detected": detected,
        "supports": supports,
    }


def num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def validate_build(o):
    """What is wrong with a build report's JSON (schema 2); empty: it is good."""
    if not isinstance(o, dict):
        return ["not an object"]
    bad = []
    if o.get("schema") != BUILD_SCHEMA:
        bad.append("schema is not %d" % BUILD_SCHEMA)
    if not (o.get("place") or {}).get("id"):
        bad.append("no place id")
    if not any(o.get(k) for k in ("markers", "items", "pairs", "raceOffsets")):
        bad.append("nothing in it")
    return bad


def item_pose(cls, supports):
    """The held pose a build item's anchor stands for (BuildModeData.ItemInteraction.override's rule)."""
    if "lying" in supports and cls != "seat":
        return "lie"
    if "sitting" in supports and cls not in ("floor", "wall"):
        return "sit"
    return {"seat": "sit", "bed": "lie"}.get(cls, "stand")


def build_samples(o, issue):
    """A build report's items as item samples (key, sample), its markers as (place, marker), its race offsets."""
    place = o["place"]["id"]
    items, markers, races, bad = [], [], [], []
    for i in o.get("items") or []:
        a = i.get("anchor") or {}
        if not i.get("key") or i.get("class") not in CLASSES or not all(num(a.get(k)) for k in ("x", "y", "z", "yaw")):
            bad.append("an item without a key, class or anchor")
            continue
        supports = [t for t in SUPPORTS if t in (i.get("supports") or [])]
        items.append((i["key"], {
            "issue": issue, "class": i["class"], "x": float(a["x"]), "y": float(a["y"]), "z": float(a["z"]), "yaw": float(a["yaw"]),
            "pose": item_pose(i["class"], supports), "surface": float(i["surface"]) if num(i.get("surface")) and i["surface"] > 0 else None,
            "notes": "", "detected": None, "supports": supports}))
    for m in o.get("markers") or []:
        if m.get("type") not in MARKER_TYPES or not all(num(m.get(k)) for k in ("x", "y", "z")):
            bad.append("a marker without a type or place")
            continue
        markers.append((place, {"issue": issue, "type": m["type"], "name": (m.get("name") or m["type"])[:40], "x": float(m["x"]),
                                "y": float(m["y"]), "z": float(m["z"]), "yaw": float(m.get("yawDeg") or 0), "item": m.get("item")}))
    for r in o.get("raceOffsets") or []:
        if not r.get("animationId") or r.get("role") not in ("A", "B") or r.get("scope") not in SCOPES or \
                not all(num(r.get(k)) for k in ("dx", "dy", "dz", "dyawDeg")) or max(abs(r["dx"]), abs(r["dy"]), abs(r["dz"])) > 3:
            bad.append("a race offset without an animation, role, scope or delta")
            continue
        races.append(dict(r, issue=issue))
    return items, markers, races, bad


def read_build(issues):
    """Build reports: (item samples by key, markers by place, race offsets, problems)."""
    by_key, markers, races, problems = {}, {}, [], []
    for issue in issues:
        if not is_build(issue):
            continue
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
        bad = validate_build(o)
        if bad:
            problems.append((number, bad))
            continue
        items, marks, rs, bad = build_samples(o, number)
        for key, s in items:
            by_key.setdefault(key, []).append(s)
        for place, mk in marks:
            markers.setdefault(place, []).append(mk)
        races += rs
        if bad:
            problems.append((number, sorted(set(bad))))
    return by_key, markers, races, problems


def far(a, b, keys=("x", "y", "z"), yaw="yaw"):
    return (math.hypot(a[keys[0]] - b[keys[0]], a[keys[1]] - b[keys[1]]) > OUTLIER_YARDS or abs(a[keys[2]] - b[keys[2]]) > OUTLIER_YARDS
            or abs(wrap_deg(a[yaw] - b[yaw])) > OUTLIER_DEG)


def merge_markers(markers):
    """Each place's markers: those of one type and name merged (median; with three or more, far ones flagged as outliers)."""
    out = {}
    for place in sorted(markers):
        groups = {}
        for m in markers[place]:
            groups.setdefault((m["type"], m["name"]), []).append(m)
        merged = []
        for (t, name), group in sorted(groups.items()):
            kept, outliers = group, []
            med = median_place(group)
            if len(group) >= 3:
                kept = [m for m in group if not far(m, med)]
                outliers = sorted(m["issue"] for m in group if far(m, med))
                if not kept:
                    kept, outliers = group, []
                med = median_place(kept)
            items = sorted({m["item"] for m in kept if m.get("item")})
            mid = "%s_%s" % (t, re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_"))
            merged.append({"id": mid, "name": name, "type": t, "x": round(med["x"], 3), "y": round(med["y"], 3), "z": round(med["z"], 3),
                           "yawDeg": round(med["yaw"], 1), "item": items[0] if items else None, "reports": len(kept),
                           "outliers": outliers, "issues": sorted({m["issue"] for m in group})})
        out[place] = merged
    return {"schema": 1, "comment": "Made by wifecraft/tools/item_reports.py from the build-report issues (build mode's markers). "
            "Do not edit by hand: run the tool again.", "places": out}


def race_key(r):
    who = r.get("sizeClass") if r["scope"] == "size" else "%s:%s" % (r.get("race"), r.get("sex"))
    other = r.get("otherRace", -1) if r["scope"] == "pair" else "*"
    return (r["animationId"], r["role"], r["scope"], str(who), str(other), r.get("furnitureClass") or "", r.get("item") or "")


def merge_races(races):
    """The remembered placements: one per key, the median delta (with three or more, far ones flagged as outliers)."""
    groups = {}
    for r in races:
        groups.setdefault(race_key(r), []).append(r)
    out = []
    for key in sorted(groups):
        group = groups[key]
        conv = [{"x": g["dx"], "y": g["dy"], "z": g["dz"], "yaw": g["dyawDeg"], "issue": g["issue"]} for g in group]
        kept, outliers = conv, []
        med = median_place(conv)
        if len(conv) >= 3:
            kept = [c for c in conv if not far(c, med)]
            outliers = sorted(c["issue"] for c in conv if far(c, med))
            if not kept:
                kept, outliers = conv, []
            med = median_place(kept)
        newest = max(group, key=lambda g: g["issue"])
        o = {k: newest.get(k) for k in ("animationId", "animationName", "role", "race", "sex", "otherRace", "furnitureClass", "item",
                                        "scope", "sizeClass")}
        o.update({"dx": round(med["x"], 3), "dy": round(med["y"], 3), "dz": round(med["z"], 3), "dyawDeg": round(med["yaw"], 1),
                  "reports": len(kept), "outliers": outliers, "issues": sorted({g["issue"] for g in group}), "at": newest["issue"]})
        out.append(o)
    return {"schema": 1, "comment": "Made by wifecraft/tools/item_reports.py from the build-report issues (remembered race placements). "
            "Do not edit by hand: run the tool again.", "offsets": out}


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


def fetch_issues(repo, tok, state="open", label=LABEL):
    issues, page = [], 1
    while True:
        url = "https://api.github.com/repos/%s/issues?labels=%s&state=%s&per_page=100&page=%d" % (repo, label, state, page)
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


def is_build(issue):
    """Whether an issue is a build report (its label, or its JSON says so)."""
    names = [(l.get("name") if isinstance(l, dict) else l) for l in issue.get("labels") or []]
    return BUILD_LABEL in names or (issue.get("title") or "").startswith("Build report:")


def read_reports(issues):
    """(samples by key, problems): every item report's JSON block checked (build reports are left to read_build)."""
    by_key, problems = {}, []
    for issue in issues:
        if is_build(issue):
            continue
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
    ap.add_argument("--markers-out", default=DEFAULT_MARKERS)
    ap.add_argument("--race-out", default=DEFAULT_RACE)
    a = ap.parse_args(argv)
    if a.from_json:
        with open(a.from_json) as f:
            issues = json.load(f)
    else:
        repo = a.repo or repo_name()
        if not repo:
            sys.exit("No repository: pass --repo OWNER/NAME.")
        tok = token()
        issues = fetch_issues(repo, tok, a.state)
        seen = {i.get("number") for i in issues}
        issues += [i for i in fetch_issues(repo, tok, a.state, BUILD_LABEL) if i.get("number") not in seen]
    by_key, problems = read_reports(issues)
    built, markers, races, build_problems = read_build(issues)
    for key, samples in built.items():
        by_key.setdefault(key, []).extend(samples)
    problems += build_problems
    data = build(by_key)
    marker_data = merge_markers(markers)
    race_data = merge_races(races)
    print(summary(data, problems, issues))
    for place, ms in marker_data["places"].items():
        for m in ms:
            print("  marker %s: %s (%s) at x %.2f y %.2f z %.2f yaw %.0f from %d report(s)%s" % (
                place, m["name"], m["type"], m["x"], m["y"], m["z"], m["yawDeg"], m["reports"],
                (", outliers %s" % m["outliers"]) if m["outliers"] else ""))
    for r in race_data["offsets"]:
        print("  race offset %s role %s %s %s: %+.2f %+.2f %+.2f %+.0f from %d report(s)%s" % (
            r["animationId"], r["role"], r["scope"], r.get("sizeClass") if r["scope"] == "size" else "%s %s" % (r.get("race"), r.get("sex")),
            r["dx"], r["dy"], r["dz"], r["dyawDeg"], r["reports"], (", outliers %s" % r["outliers"]) if r["outliers"] else ""))
    if not a.dry_run:
        for path, d in ((a.out, data), (a.markers_out, marker_data), (a.race_out, race_data)):
            with open(path, "w") as f:
                json.dump(d, f, indent=1)
                f.write("\n")
            print("Wrote %s" % os.path.relpath(path))


if __name__ == "__main__":
    main()
