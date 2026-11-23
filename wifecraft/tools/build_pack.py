#!/usr/bin/env python3
"""Builds the one zip the app's Options -> Mods -> Import pack takes: your own Sims 4 animation, toy, furniture and layer packages.

    python3 -I tools/build_pack.py [--out pack.zip] [--name NAME] PATH...

PATH is a .package, a .zip / .rar / .7z of them (read with bsdtar) or a folder. Each package is told by what it holds, never by its
file name (tools/pack_inventory.py `classify`, the same rule as the app's PackClassifier):

    animations   Sims 4 clips with a WickedWhims tuning naming actor clips (also when it names props)
    props        object models (toys, dolls, handcuffs) that the package's own stages name, with their clips
    body-layer   object models only another package's stages name (the cum mesh: an animation-linked layer)
    furniture    object models no stage names (a bed)
    script-info  a note about a .ts4script (its file list and names; the script itself is never put in the pack, never run)
    unused       any other package (a purchase menu's tuning): kept, listed, never imported

The zip holds pack/manifest.json (format, schema 1, per file: id, type, path, source name, sha-256, size and a summary: category,
stages, clips, objects, audio) and the files under pack/animations, pack/bodylayers, pack/props, pack/furniture, pack/info,
pack/other. Names inside are made plain (letters, digits, . _ - + ( )). The zip is deterministic (fixed times, sorted entries).
Your packages are your own files, used on your phone only: this tool never uploads anything.
"""
import hashlib
import json
import os
import re
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pack_inventory as inv  # noqa: E402

SCHEMA = 1
DIRS = {"animations": "animations", "body-layer": "bodylayers", "props": "props", "furniture": "furniture",
        "script-info": "info", "unused": "other"}
KIND_OF = {"animations": "animations", "bodylayers": "body-layer", "props": "props", "furniture": "furniture", "other": "unused"}
ZIP_TIME = (2026, 1, 1, 0, 0, 0)


def safe_name(name, taken, ext):
    """A plain file name for [name] (an upload's `8hex-` prefix dropped), unique among [taken]."""
    stem = re.sub(r"^[0-9a-f]{8}-", "", os.path.splitext(os.path.basename(name))[0])
    stem = re.sub(r"[^A-Za-z0-9._()+-]+", "_", stem).strip("._-")[:100] or "package"
    out, n = stem + ext, 2
    while out.lower() in taken:
        out, n = "%s_%d%s" % (stem, n, ext), n + 1
    taken.add(out.lower())
    return out


def category_of(i):
    """The animation library category for an animation package: its author, else its stages' common prefix."""
    names = {s["author"] for s in i["stages"] if s["author"]}
    if len(names) == 1:
        a = next(iter(names))
    else:
        clips = [x["clip"] for s in i["stages"] for x in s["actors"] if x["clip"]]
        a = clips[0].split(":")[0] if clips and ":" in clips[0] else ""
    return a.strip() or "Imported"


def pack_category(i, source):
    """A readable library category: LPGS, CherryPie, FEMDOMANIA · Foot Fetish (the package's own name after the author)."""
    base = category_of(i)
    stem = re.sub(r"^[0-9a-f]{8}-", "", os.path.splitext(os.path.basename(source))[0])
    stem = re.sub(r"^(WW_|\[?%s\]?[_ ]?)" % re.escape(base), "", stem, flags=re.I)
    stem = re.sub(r"(?i)(_?animations?|_?package)$", "", stem).replace("_", " ").strip(" -")
    if base.lower() == "femdomania" and stem:
        return "FEMDOMANIA · " + stem.title()
    return base


def summary_of(i, kind, source):
    s = {"types": i["types"], "resources": i["resources"]}
    if kind == "animations":
        stages = i["stages"]
        s.update(category=pack_category(i, source), clips=i["clips"], stages=len(stages),
                 stages_two_people=sum(1 for t in stages if len(t["actors"]) <= 2),
                 stages_three_or_more=sum(1 for t in stages if len(t["actors"]) > 2),
                 stages_with_props=sum(1 for t in stages if t["props"]),
                 categories=sorted({t["category"] for t in stages if t["category"]}),
                 locations=sorted({l.strip() for t in stages for l in t["locations"].split(",") if l.strip()}))
        if i["audio"]["snr"]:
            s["audio_snr"] = i["audio"]["snr"]
    elif kind in ("props", "body-layer", "furniture"):
        s.update(category={"props": "Props", "body-layer": "Body layers", "furniture": "Furniture"}[kind], objects=len(i["objects"]),
                 clips=i["clips"], stages=len(i["stages"]),
                 models=sorted({o["model"] for o in i["objects"] if o.get("model")}),
                 triangles=max([o.get("triangles", 0) for o in i["objects"]] or [0]))
    else:
        s["why"] = "a Sims purchase-menu tuning: it adds a phone menu to The Sims, nothing the app draws or plays"
    return s


def script_note(info, pack_name):
    lines = ["Script note (never run, never included): %s" % info["file"],
             "",
             "A .ts4script is a Python script for The Sims 4 (a zip of compiled .pyc files). Wifecraft does not run scripts: this note is",
             "only the file list and the names the script mentions, read as text, so you know what the mod's script does.",
             "",
             "size: %d bytes   sha-256: %s" % (info["size"], info["sha256"]),
             "files in it:"] + ["  " + e for e in info["entries"]] + ["", "names it mentions:"] + ["  " + n for n in info["names"]]
    lines += ["", "Reading: the script injects the toys' phone pie menu (buy a toy from the phone) into The Sims' objects. That menu is the game's",
              "own interface; in Wifecraft the toys come from the pack's props and the stages that name them."]
    return "\n".join(lines) + "\n"


def build(paths, out, name="Wifecraft pack"):
    work = tempfile.mkdtemp(prefix="bp-")
    pkgs, scripts = [], []
    for p in paths:
        a, s = inv.extract(p, work)
        pkgs += a
        scripts += s
    if not pkgs and not scripts:
        raise SystemExit("no .package or .ts4script found in the inputs")
    seen, invs = {}, []
    for disp, path in pkgs:
        i = inv.read_package(path)
        if i["sha256"] in seen:
            print("skipped duplicate:", disp)
            continue
        i["file"], i["_path"] = disp, path
        seen[i["sha256"]] = i
        invs.append(i)
    refs = set()
    for i in invs:
        refs |= inv.prop_refs(i)
    for i in invs:
        i["kind"] = inv.classify(i, refs)
        if i["kind"] == "other":
            i["kind"] = "unused"
    order = {"animations": 0, "props": 1, "body-layer": 2, "furniture": 3, "unused": 4}
    invs.sort(key=lambda i: (order[i["kind"]], re.sub(r"^[0-9a-f]{8}-", "", i["file"]).lower()))
    taken = {d: set() for d in DIRS.values()}
    entries, files = [], []
    for i in invs:
        d = DIRS[i["kind"]]
        fname = safe_name(i["file"], taken[d], ".package")
        path = "pack/%s/%s" % (d, fname)
        entries.append(dict(id="%s/%s" % (i["kind"], fname), type=i["kind"], path=path, source=os.path.basename(i["file"]),
                            sha256=i["sha256"], size=i["size"], summary=summary_of(i, i["kind"], i["file"])))
        files.append((path, open(i["_path"], "rb").read()))
    for disp, path in scripts:
        info = inv.script_info(path)
        text = script_note(info, name).encode()
        fname = safe_name(disp, taken["info"], ".txt")
        p = "pack/info/" + fname
        entries.append(dict(id="script-info/" + fname, type="script-info", path=p, source=os.path.basename(disp),
                            sha256=hashlib.sha256(text).hexdigest(), size=len(text),
                            summary=dict(category="Script notes", script_sha256=info["sha256"], script_size=info["size"],
                                         script_files=info["entries"], included=False)))
        files.append((p, text))
    notes = [
        "Built by tools/build_pack.py from your own packages; nothing in it is executed. Scripts (.ts4script) are not included: a note describes each.",
        "Stages for three or more people are imported only as far as the importer plays them: a scene plays one or two people.",
        "Props the game itself provides (a plate, a cup: small object ids no package here defines) have no model: their stage plays without them.",
        "Animations need the Sims 4 skeleton (import WickedWhims first, or load one under Import rig and clips).",
    ]
    manifest = dict(format="wifecraft-pack", schema=SCHEMA, name=name, entries=entries, notes=notes)
    mbytes = (json.dumps(manifest, indent=1, sort_keys=False) + "\n").encode()
    readme = ("Wifecraft pack: %s\n\nImport it in the app: Options -> Mods -> Import pack, then pick this zip.\n"
              "pack/manifest.json lists every file (type, source name, sha-256, size). Scripts are never run.\n" % name).encode()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for arc, data in [("pack/manifest.json", mbytes), ("pack/README.txt", readme)] + sorted(files):
            zi = zipfile.ZipInfo(arc, ZIP_TIME)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, data)
    return manifest


def main():
    args = sys.argv[1:]
    out, name, paths, k = "pack.zip", "Wifecraft pack", [], 0
    while k < len(args):
        if args[k] == "--out":
            out = args[k + 1]; k += 2
        elif args[k] == "--name":
            name = args[k + 1]; k += 2
        else:
            paths.append(args[k]); k += 1
    if not paths:
        raise SystemExit(__doc__)
    m = build(paths, out, name)
    sha = hashlib.sha256(open(out, "rb").read()).hexdigest()
    print("wrote %s: %d bytes, sha-256 %s, %d entries" % (out, os.path.getsize(out), sha, len(m["entries"])))
    for e in m["entries"]:
        print("  %-12s %-52s %10d  %s" % (e["type"], e["path"], e["size"], e["summary"].get("category", "")))


if __name__ == "__main__":
    main()
