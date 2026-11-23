#!/usr/bin/env python3
"""Inventory of Sims 4 packages (the user's own animation, object and furniture packs for the Pack import).

    python3 -I tools/pack_inventory.py [--json OUT.json] [--md OUT.md] PATH...

PATH is a .package, a .zip / .rar (read with bsdtar, each into its own new temp folder) or a folder. For every package: its
resource types and counts, what it is (by its resources, never by its file name: `classify`), the stages its WickedWhims
tuning lists (names, category, furniture, how many actors, props, events), the objects with their models, and the audio. A
`.ts4script` is a Python script for the game: it is never run and never read as code, only its file list and the names in it are
printed. Reads only; nothing is written but the two outputs.
"""
import collections
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dbpf  # noqa: E402
import sims_object as so  # noqa: E402

CLIP, CLHD, TUNING_SNIPPET = 0x6B20C4F3, 0xBC4A5044, 0x7DF2169C
SNR, AUDIO_SNS, NMAP = 0x01A527DB, 0xFD04E3BE, 0x0166038C

# Resource types this tool names beyond dbpf.TYPES (the rest print as hex).
EXTRA = {SNR: "SNR", AUDIO_SNS: "SNS", NMAP: "NMAP", 0xB61DE6B4: "OBJ-KEY?", 0x3C2A8647: "THUMB", 0x01357924: "OBJ-REF?",
         0x81CA1A10: "MTBL?"}
KINDS = ("animations", "body-layer", "props", "furniture")


def type_name(t):
    return EXTRA.get(t) or dbpf.TYPES.get(t) or "%08X" % t


def _fields(e):
    d = {}
    for c in e:
        n = c.get("n")
        if n and n not in d:
            d[n] = c
    return d


def _text(e, default=""):
    return (e.text or "").strip() if e is not None else default


def stages_of(xml):
    """The stages of a WickedWhims animation snippet (both generations of its field names): a list of dicts."""
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    if root.get("c") != "WickedWhimsAnimationPackage":
        return None
    lst = next((c for c in root if c.get("n") == "animations_list"), None)
    if lst is None:
        return []
    out = []
    for it in lst:
        f = _fields(it)
        actors = []
        al = f.get("animation_actors_list")
        for a in (list(al) if al is not None else []):
            af = _fields(a)
            actors.append(dict(id=_text(af.get("actor_id")), clip=_text(af.get("animation_clip_name")) or _text(af.get("animation_name")),
                               type=_text(af.get("animation_type")), pref=_text(af.get("animation_pref_gender")),
                               naked=_text(af.get("animation_naked_type")) or _text(af.get("animation_naked_flags"))))
        props = []
        pl = f.get("animation_props_list")
        for p in (list(pl) if pl is not None else []):
            pf = _fields(p)
            guids = [g.strip() for g in re.split(r"[,\s]+", _text(pf.get("prop_guids"))) if g.strip().isdigit()]
            props.append(dict(id=_text(pf.get("prop_id")), type=_text(pf.get("prop_type")), guids=[int(g) for g in guids],
                              clip=_text(pf.get("prop_animation_clip_name"))))
        events = collections.Counter()
        el = f.get("animation_events_list")
        for ev in (list(el) if el is not None else []):
            events[_text(_fields(ev).get("event_type"), "?")] += 1
        out.append(dict(name=_text(f.get("animation_raw_display_name")), author=_text(f.get("animation_author")),
                        stage=_text(f.get("animation_stage_name")), category=_text(f.get("animation_category")).upper(),
                        locations=_text(f.get("animation_locations")), next=_text(f.get("animation_next_stages")),
                        loops=_text(f.get("animation_loops")), actors=actors, props=props, events=dict(events),
                        object_clip=_text(f.get("object_animation_clip_name"))))
    return out


def read_package(path):
    b = open(path, "rb").read()
    es = dbpf.entries(b)
    inv = dict(file=os.path.basename(path), size=len(b), sha256=hashlib.sha256(b).hexdigest())
    counts = collections.Counter(type_name(e["type"]) for e in es)
    inv["types"] = dict(sorted(counts.items(), key=lambda kv: -kv[1]))
    inv["resources"] = len(es)
    stages, clip_names = [], []
    for e in es:
        if e["type"] == TUNING_SNIPPET:
            s = stages_of(dbpf.read(b, e))
            if s is not None:
                stages += s
        if e["type"] == CLIP:
            d = dbpf.read(b, e)
            m = re.match(rb"[\s\S]*?([A-Za-z0-9_:\-\. ]{6,})", d[0x2C:0x2C + 160]) if False else None
            m = re.search(rb"[A-Za-z0-9_\-]+(?::[A-Za-z0-9_\-]+)?:?[A-Za-z0-9_\-]*", d[0x24:0x24 + 200])
            clip_names.append(m.group(0).decode() if m else "")
    inv["stages"] = stages
    inv["clips"] = counts.get("CLIP", 0)
    inv["audio"] = dict(snr=counts.get("SNR", 0), sns=counts.get("SNS", 0))
    # objects
    objs = []
    if any(e["type"] == so.T_OBJD for e in es):
        for guid, o in so.read_package_objects(b).items():
            info = dict(guid=guid, model="%016X" % o["modl"][0] if o["modl"] else None)
            if o["modl"]:
                md = so.find(b, es, so.T_MODL, o["modl"][0])
                if md is not None:
                    _c, ext = so.rcol(md)
                    lod = next((k for k in ext if k[0] == so.T_MLOD), None)
                    if lod:
                        ml = so.find(b, es, so.T_MLOD, lod[2], lod[1])
                        if ml is not None:
                            ms = so.meshes(ml)
                            info["meshes"] = len(ms)
                            info["vertices"] = sum(len(m["pos"]) for m in ms)
                            info["triangles"] = sum(len(m["idx"]) // 3 for m in ms)
                            pts = [p for m in ms for p in m["pos"]]
                            if pts:
                                lo = [min(p[i] for p in pts) for i in range(3)]
                                hi = [max(p[i] for p in pts) for i in range(3)]
                                info["size_m"] = [round(hi[i] - lo[i], 3) for i in range(3)]
            info["variant"] = so.variant_hash(o["raw"])
            if o["rig"]:
                rg = so.find(b, es, so.T_RIG, o["rig"][0])
                if rg is not None:
                    info["rig_bones"] = struct.unpack_from("<I", rg, 8)[0]
            objs.append(info)
    inv["objects"] = objs
    return inv


def prop_refs(inv):
    return {g for s in inv["stages"] for p in s["props"] for g in p["guids"]}


def classify(inv, all_refs):
    """What a package is, from its resources: animations, props, body-layer, furniture or other.

    - CLIP resources and a WickedWhims tuning whose stages name actor clips: animations (also when it names props);
    - objects (OBJD + MODL): props when the package's own stages use them, body-layer when only stages of another package
      do (an animation-linked layer: the cum mesh), furniture when no stage names them (a bed);
    - anything else (a purchase menu's tuning, a script's string tables): other.
    """
    objd = inv["types"].get("OBJD", 0) and inv["types"].get("MODL", 0)
    has_stage_clips = any(a["clip"] for s in inv["stages"] for a in s["actors"])
    own = prop_refs(inv)
    if objd:
        guids = {o["guid"] for o in inv["objects"]}
        if guids & own:
            return "props"
        if guids & all_refs:
            return "body-layer"
        return "furniture"
    if inv["clips"] and has_stage_clips:
        return "animations"
    return "other"


def extract(path, workdir):
    """The packages an input holds: [(display name, path)]. Archives are unpacked into a new folder of [workdir]."""
    low = path.lower()
    if os.path.isdir(path):
        out = []
        for r, _d, fs in os.walk(path):
            for f in sorted(fs):
                out.append(os.path.join(r, f))
        return [(os.path.relpath(p, path), p) for p in out if p.lower().endswith(".package")], \
               [(os.path.relpath(p, path), p) for p in out if p.lower().endswith(".ts4script")]
    if low.endswith(".package"):
        return [(os.path.basename(path), path)], []
    if low.endswith(".ts4script"):
        return [], [(os.path.basename(path), path)]
    if low.endswith((".zip", ".rar", ".7z")):
        dest = tempfile.mkdtemp(prefix="pk-", dir=workdir)
        r = subprocess.run(["bsdtar", "-xf", path, "-C", dest], capture_output=True)
        if r.returncode != 0:
            raise SystemExit("cannot unpack %s: %s" % (path, r.stderr.decode(errors="replace")[:200]))
        return extract(dest, workdir)
    return [], []


def script_info(path):
    """A .ts4script's file list and names only (never run: its .pyc files are not even unmarshalled)."""
    z = zipfile.ZipFile(path)
    names = []
    strings = set()
    for i in z.infolist():
        names.append(i.filename)
        if i.file_size < 200000:
            for s in re.findall(rb"[A-Za-z_][A-Za-z0-9_.]{5,}", z.read(i)):
                strings.add(s.decode())
    keep = sorted(s for s in strings if "phone" in s.lower() or "affordance" in s.lower() or "inject" in s.lower())[:12]
    return dict(file=os.path.basename(path), size=os.path.getsize(path), sha256=hashlib.sha256(open(path, "rb").read()).hexdigest(),
                entries=names, names=keep)


def md_table(invs):
    lines = ["| Package | Kind | Size | Resources | Clips | Stages | Objects | Audio (SNR) |", "|---|---|---:|---:|---:|---:|---:|---:|"]
    for i in invs:
        lines.append("| %s | %s | %.1f MB | %d | %d | %d | %d | %d |" % (
            i["file"], i.get("kind", "?"), i["size"] / 1e6, i["resources"], i["clips"], len(i["stages"]), len(i["objects"]),
            i["audio"]["snr"]))
    return "\n".join(lines)


def main():
    args = sys.argv[1:]
    out_json = out_md = None
    paths = []
    k = 0
    while k < len(args):
        if args[k] == "--json":
            out_json = args[k + 1]; k += 2
        elif args[k] == "--md":
            out_md = args[k + 1]; k += 2
        else:
            paths.append(args[k]); k += 1
    work = tempfile.mkdtemp(prefix="inv-")
    pkgs, scripts = [], []
    for p in paths:
        a, s = extract(p, work)
        pkgs += a
        scripts += s
    invs = []
    for name, path in pkgs:
        inv = read_package(path)
        inv["file"] = name
        invs.append(inv)
    refs = set()
    for inv in invs:
        refs |= prop_refs(inv)
    for inv in invs:
        inv["kind"] = classify(inv, refs)
    report = dict(packages=invs, scripts=[script_info(p) for _n, p in scripts])
    print(md_table(invs))
    for inv in invs:
        print("\n## %s: %s" % (inv["file"], inv["kind"]))
        print("types:", ", ".join("%s x%d" % kv for kv in inv["types"].items()))
        for s in inv["stages"]:
            print("  stage %-44s %-8s actors %d props %d events %s at %s" % (
                s["name"][:44], s["category"], len(s["actors"]), len(s["props"]), s["events"] or "-", s["locations"][:40] or "-"))
        for o in inv["objects"]:
            print("  object %d model %s meshes %s verts %s size_m %s rig bones %s" % (
                o["guid"], o["model"], o.get("meshes"), o.get("vertices"), o.get("size_m"), o.get("rig_bones")))
    for s in report["scripts"]:
        print("\nscript (never run):", s)
    if out_json:
        json.dump(report, open(out_json, "w"), indent=1, default=str)
    if out_md:
        open(out_md, "w").write(md_table(invs) + "\n")


if __name__ == "__main__":
    main()
