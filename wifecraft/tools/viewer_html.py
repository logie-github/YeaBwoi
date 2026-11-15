#!/usr/bin/env python3
"""A one-file model viewer for the animations Wifecraft imports from the Sims (anim/, sims/): a night elf
woman (the demo's Nyrelle look: model 921844, her geosets and her baked Sentinel skin) skinned in three.js,
with her own Stand, a few test poses made in the page, and "Load animation JSON..." for the importer's Anim
files (AnimJson, version 1), which play on her by their WoW-side bone names.

    python3 -I wifecraft/tools/viewer_html.py [OUT_HTML] [CACHE_DIR]

OUT_HTML defaults to /home/user/viewer-out/nightelf-viewer.html, CACHE_DIR (the game files, fetched from
wago.tools by file id) to ~/.cache/wifecraft-viewer. The page holds the game's model and textures: it is for
the user's own eyes, never committed or published. This script holds no game data.

How an Anim track plays (the page's rule, the same as the engine's SceneView.playAnim is meant to follow):
a ROTATION key (x, y, z, w) replaces the bone's local rotation as an M2 bone's own track does (about its
pivot, in its parent's frame; at rest every M2 bone's rotation is the identity, so the frame is the model's:
z up, the face along +x, her left +y); a POSITION key (x, y, z) replaces the bone's local translation, model
units. Bones an animation leaves out keep the base the page's "Base" menu picks (the rest pose or Stand's
first frame).

The bone names are tools/wow_bones.py's (Root, Pelvis, Head, L_UpperArm ...), resolved the same way (key bone
id, then bone name CRC, then "first child of"); the generator prints which resolved.
"""
import base64
import io
import json
import math
import os
import struct
import sys
import urllib.request
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wow_bones  # noqa: E402  (the names and how they resolve: one table for the tools and the engine)

USER_AGENT = "Mozilla/5.0 (compatible; model-viewer)"
MODEL = 921844
# Nyrelle's look (assets/looks.json at 663fd7a): geoset groups and the texture slots the model's replaceable
# textures take (1 skin: her baked Sentinel skin, 6 hair, the rest the model's other slots).
GEOSET_GROUPS = {0: 6, 4: 2, 5: 1, 7: 1, 8: 1, 9: 1, 10: 1, 11: 1, 13: 1, 15: 2, 16: 0, 18: 1, 20: 2, 22: 1,
                 23: 1, 26: 1, 28: 1, 34: 1, 35: 0, 36: 0, 37: 0, 39: 0, 51: 0}
TEXTURES = {1: 2445823, 2: 2373822, 6: 1014041, 10: 3636573, 19: 3509223}
MAX_TEX = 1024


def fetch(cache, fid):
    path = os.path.join(cache, str(fid))
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        req = urllib.request.Request("https://wago.tools/api/casc/%d?download" % fid, headers={"User-Agent": USER_AGENT})
        data = urllib.request.urlopen(req, timeout=300).read()
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
    with open(path, "rb") as f:
        return f.read()


# ---------------------------------------------------------------- M2

def arr(b, o):
    return struct.unpack_from("<II", b, o)


def chunks(b):
    out, o = {}, 0
    while o + 8 <= len(b):
        tag, size = b[o:o + 4], struct.unpack_from("<I", b, o + 4)[0]
        out[tag.decode("latin-1")] = (o + 8, size)
        o += 8 + size
    return out


def unpack_short(s):
    return (s + 32768 if s < 0 else s - 32767) / 32767.0


def read_m2(cache):
    whole = fetch(cache, MODEL)
    ch = chunks(whole)
    mo, ms = ch["MD21"]
    m = whole[mo:mo + ms]
    assert m[:4] == b"MD20", "not an MD20 model"
    so, ss = ch["SFID"]
    skin_ids = struct.unpack_from("<%dI" % (ss // 4), whole, so)
    to, ts = ch.get("TXID", (0, 0))
    tex_ids = struct.unpack_from("<%dI" % (ts // 4), whole, to) if ts else ()

    seqs = []
    n, off = arr(m, 28)
    for i in range(n):
        sid, var, dur, _speed, flags = struct.unpack_from("<HHIfI", m, off + i * 64)
        seqs.append((sid, var, dur, flags))
    stand = next(i for i, s in enumerate(seqs) if s[0] == 0 and s[1] == 0)
    assert seqs[stand][3] & 0x20, "Stand's keys are not in the model (an .anim file): not handled"

    def track(o):
        interp, gseq = struct.unpack_from("<HH", m, o)
        tsn, tso = arr(m, o + 4)
        vsn, vso = arr(m, o + 12)
        if gseq != 0xFFFF or stand >= tsn or stand >= vsn:
            return None
        cnt, toff = arr(m, tso + 8 * stand)
        vcnt, voff = arr(m, vso + 8 * stand)
        cnt = min(cnt, vcnt)
        if not cnt:
            return None
        times = list(struct.unpack_from("<%dI" % cnt, m, toff))
        return interp, times, voff

    bones = []
    n, off = arr(m, 44)
    for i in range(n):
        o = off + i * 88
        key, flags, parent, _sub, crc = struct.unpack_from("<iIhHI", m, o)
        pivot = struct.unpack_from("<3f", m, o + 76)
        anim = {}
        t = track(o + 16)
        if t:
            interp, times, voff = t
            stride = 36 if interp >= 2 else 12
            anim["t"] = (times, [v for k in range(len(times)) for v in struct.unpack_from("<3f", m, voff + k * stride)])
        t = track(o + 36)
        if t:
            interp, times, voff = t
            stride = 24 if interp >= 2 else 8
            vals = []
            for k in range(len(times)):
                q = [unpack_short(s) for s in struct.unpack_from("<4h", m, voff + k * stride)]
                ln = math.sqrt(sum(c * c for c in q)) or 1.0
                vals += [c / ln for c in q]
            anim["r"] = (times, vals)
        t = track(o + 56)
        if t:
            interp, times, voff = t
            stride = 36 if interp >= 2 else 12
            anim["s"] = (times, [v for k in range(len(times)) for v in struct.unpack_from("<3f", m, voff + k * stride)])
        bones.append({"key": key, "parent": parent, "crc": crc, "pivot": pivot, "anim": anim})

    n, off = arr(m, 60)
    verts = m[off:off + n * 48]
    nverts = n

    textures = []
    n, off = arr(m, 80)
    for i in range(n):
        ttype, tflags = struct.unpack_from("<II", m, off + i * 16)
        textures.append((ttype, tflags, tex_ids[i] if i < len(tex_ids) else 0))
    n, off = arr(m, 112)
    materials = [struct.unpack_from("<HH", m, off + 4 * i) for i in range(n)]
    n, off = arr(m, 128)
    combos = list(struct.unpack_from("<%dH" % n, m, off)) if n else []

    skin = fetch(cache, skin_ids[0])
    assert skin[:4] == b"SKIN"
    ln, lo = arr(skin, 4)
    tn, tof = arr(skin, 12)
    sn, sof = arr(skin, 28)
    bn, bof = arr(skin, 36)
    lookup = struct.unpack_from("<%dH" % ln, skin, lo)
    tris = [lookup[i] if i < ln else 0 for i in struct.unpack_from("<%dH" % tn, skin, tof)]
    subs = []
    for i in range(sn):
        o = sof + i * 48
        sid, level, vstart, vcount, istart, icount = struct.unpack_from("<HHHHHH", skin, o)
        subs.append((sid, istart + (level << 16), icount))
    batches = []
    for i in range(bn):
        o = bof + i * 24
        flags, prio, shader, sub = struct.unpack_from("<BbHH", skin, o)
        color, mat, layer, tcount, tcombo = struct.unpack_from("<HHHHH", skin, o + 8)
        batches.append({"sub": sub, "mat": mat, "layer": layer, "prio": prio, "combo": tcombo, "tcount": tcount})
    return {"seqs": seqs, "stand": stand, "bones": bones, "verts": verts, "nverts": nverts, "textures": textures,
            "materials": materials, "combos": combos, "tris": tris, "subs": subs, "batches": batches,
            "raw": m}


def visible_geosets(subs):
    """scene.cpp characterGeosets: defaults, then the look's group values."""
    out = set()
    for sid, _s, _c in subs:
        group, value = (0, sid) if sid < 100 else (sid // 100, sid % 100)
        show = sid == 0 or (sid >= 100 and value == 1) or group == 32
        if group in (17, 35):
            show = False
        if sid != 0 and group in GEOSET_GROUPS:
            show = value == GEOSET_GROUPS[group]
        if show:
            out.add(sid)
    return out


# ---------------------------------------------------------------- BLP

def rgb565(c):
    return (((c >> 11) & 31) * 255 // 31, ((c >> 5) & 63) * 255 // 63, (c & 31) * 255 // 31)


def decode_blp(b):
    """BLP2 (blp.cpp's cases: palettised, DXT1/3/5, BGRA) at the largest mip level not over MAX_TEX: (w, h, rgba)."""
    assert b[:4] == b"BLP2", "not a BLP2 texture"
    enc, adepth, aenc = b[8], b[9], b[10]
    w, h = struct.unpack_from("<II", b, 12)
    level = 0
    while level < 15 and (w > MAX_TEX or h > MAX_TEX):
        if not struct.unpack_from("<I", b, 20 + 4 * (level + 1))[0]:
            break
        level += 1
        w, h = max(1, w // 2), max(1, h // 2)
    off, ln = struct.unpack_from("<I", b, 20 + 4 * level)[0], struct.unpack_from("<I", b, 84 + 4 * level)[0]
    src = b[off:off + ln]
    out = bytearray(w * h * 4)
    if enc == 1:
        pal = struct.unpack_from("<256I", b, 148)
        n = w * h
        for i in range(min(n, len(src))):
            c = pal[src[i]]
            a = 255
            if adepth == 8 and n + i < len(src):
                a = src[n + i]
            elif adepth == 4 and n + i // 2 < len(src):
                a = ((src[n + i // 2] >> ((i & 1) * 4)) & 15) * 17
            elif adepth == 1 and n + i // 8 < len(src):
                a = 255 if (src[n + i // 8] >> (i & 7)) & 1 else 0
            out[i * 4:i * 4 + 4] = bytes(((c >> 16) & 255, (c >> 8) & 255, c & 255, a))
    elif enc == 2:
        kind = 5 if aenc == 7 else 3 if aenc == 1 else 1
        bsz = 8 if kind == 1 else 16
        bw, bh = (w + 3) // 4, (h + 3) // 4
        for by in range(bh):
            for bx in range(bw):
                o = (by * bw + bx) * bsz
                if o + bsz > len(src):
                    break
                blk = src[o:o + bsz]
                cb = blk if kind == 1 else blk[8:]
                c0, c1 = struct.unpack_from("<HH", cb, 0)
                p0, p1 = rgb565(c0), rgb565(c1)
                if kind != 1 or c0 > c1:
                    pal = [p0 + (255,), p1 + (255,),
                           tuple((2 * p0[i] + p1[i]) // 3 for i in range(3)) + (255,),
                           tuple((p0[i] + 2 * p1[i]) // 3 for i in range(3)) + (255,)]
                else:
                    pal = [p0 + (255,), p1 + (255,), tuple((p0[i] + p1[i]) // 2 for i in range(3)) + (255,), (0, 0, 0, 0)]
                bits = struct.unpack_from("<I", cb, 4)[0]
                alphas = None
                if kind == 3:
                    alphas = [((blk[i // 2] >> ((i & 1) * 4)) & 15) * 17 for i in range(16)]
                elif kind == 5:
                    a0, a1 = blk[0], blk[1]
                    ap = [a0, a1]
                    if a0 > a1:
                        ap += [((7 - i) * a0 + i * a1) // 7 for i in range(1, 7)]
                    else:
                        ap += [((5 - i) * a0 + i * a1) // 5 for i in range(1, 5)] + [0, 255]
                    abits = int.from_bytes(blk[2:8], "little")
                    alphas = [ap[(abits >> (3 * i)) & 7] for i in range(16)]
                for i in range(16):
                    x, y = bx * 4 + (i & 3), by * 4 + (i >> 2)
                    if x >= w or y >= h:
                        continue
                    p = pal[(bits >> (2 * i)) & 3]
                    a = alphas[i] if alphas else (p[3] if adepth else 255)
                    j = (y * w + x) * 4
                    out[j:j + 4] = bytes((p[0], p[1], p[2], a))
    elif enc == 3:
        for i in range(min(w * h, len(src) // 4)):
            out[i * 4:i * 4 + 4] = bytes((src[i * 4 + 2], src[i * 4 + 1], src[i * 4], src[i * 4 + 3]))
    else:
        raise ValueError("BLP encoding %d not handled" % enc)
    return w, h, bytes(out)


def png(w, h, rgba):
    raw = b"".join(b"\x00" + rgba[y * w * 4:(y + 1) * w * 4] for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


# ---------------------------------------------------------------- the page's data

def b64(fmt, values):
    return base64.b64encode(struct.pack("<%d%s" % (len(values), fmt), *values)).decode()


def build(cache):
    m = read_m2(cache)
    show = visible_geosets(m["subs"])
    # the triangles of the drawn submeshes, per batch, with their vertices renumbered
    groups, remap, keep = [], {}, []
    tex_slots = {}
    for b in sorted(m["batches"], key=lambda b: (b["prio"], b["layer"])):
        if b["sub"] >= len(m["subs"]) or b["layer"] != 0:
            continue
        sid, start, count = m["subs"][b["sub"]]
        if sid not in show or not count or b["combo"] >= len(m["combos"]):
            continue
        ti = m["combos"][b["combo"]]
        if ti >= len(m["textures"]):
            continue
        ttype, tflags, fid = m["textures"][ti]
        if ttype:
            fid = TEXTURES.get(ttype, 0)
        if not fid:
            continue
        blend = m["materials"][b["mat"]][1] if b["mat"] < len(m["materials"]) else 0
        mflags = m["materials"][b["mat"]][0] if b["mat"] < len(m["materials"]) else 0
        idx = []
        for v in m["tris"][start:start + count]:
            if v not in remap:
                remap[v] = len(keep)
                keep.append(v)
            idx.append(remap[v])
        tex_slots.setdefault(fid, tflags)
        groups.append({"geoset": sid, "tex": str(fid), "blend": blend, "twoSided": bool(mflags & 4),
                       "unlit": bool(mflags & 1), "start": 0, "count": len(idx), "idx": idx})
    index, pos, nrm, uv, bidx, bw = [], [], [], [], [], []
    for g in groups:
        g["start"] = len(index)
        index += g.pop("idx")
    for v in keep:
        o = v * 48
        p = struct.unpack_from("<3f", m["verts"], o)
        w = m["verts"][o + 12:o + 16]
        bi = m["verts"][o + 16:o + 20]
        n = struct.unpack_from("<3f", m["verts"], o + 20)
        t = struct.unpack_from("<2f", m["verts"], o + 32)
        pos += p
        nrm += n
        uv += t
        bidx += list(bi)
        bw += [x / 255.0 for x in w]

    textures = {}
    for fid, flags in tex_slots.items():
        w, h, rgba = decode_blp(fetch(cache, fid))
        textures[str(fid)] = {"png": base64.b64encode(png(w, h, rgba)).decode(), "w": w, "h": h,
                              "wrapU": bool(flags & 1), "wrapV": bool(flags & 2)}

    bones = [{"parent": b["parent"], "pivot": [round(c, 6) for c in b["pivot"]],
              "stand": {k: {"t": v[0], "v": [round(x, 6) for x in v[1]]} for k, v in b["anim"].items()}}
             for b in m["bones"]]
    found, nb = wow_bones.resolve(m["raw"])
    names = [t[0] for t in wow_bones.table()]
    boneNames = {n: found[n][0] for n in names if n in found}
    how = {n: found[n][1] for n in names if n in found}
    missing = [n for n in names if n not in found]
    data = {
        "model": MODEL, "standMs": m["seqs"][m["stand"]][2],
        "geosets": sorted(show), "groups": groups,
        "index": b64("I", index), "pos": b64("f", pos), "nrm": b64("f", nrm), "uv": b64("f", uv),
        "bidx": base64.b64encode(bytes(bidx)).decode(), "bw": b64("f", bw),
        "bones": bones, "boneNames": boneNames, "boneHow": how, "missing": missing, "textures": textures,
    }
    report = {"vertices": len(keep), "of": m["nverts"], "triangles": len(index) // 3, "batches": len(groups),
              "geosets": sorted(show), "textures": sorted(textures), "bones": nb,
              "resolved": how, "missing": missing}
    return data, report


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/home/user/viewer-out/nightelf-viewer.html"
    cache = sys.argv[2] if len(sys.argv) > 2 else os.path.expanduser("~/.cache/wifecraft-viewer")
    os.makedirs(cache, exist_ok=True)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    data, report = build(cache)
    page = PAGE.replace("/*DATA*/null", json.dumps(data, separators=(",", ":")))
    with open(out + ".part", "w") as f:
        f.write(page)
    os.replace(out + ".part", out)
    print("wrote %s (%.1f MB)" % (out, os.path.getsize(out) / 1e6))
    print("%(vertices)d of %(of)d vertices, %(triangles)d triangles, %(batches)d batches" % report)
    print("geosets:", report["geosets"])
    print("textures:", report["textures"])
    print("%d bones; names resolved %d, missing %d" % (report["bones"], len(report["resolved"]), len(report["missing"])))
    for n, h in report["resolved"].items():
        print("   %-12s %s" % (n, h))
    if report["missing"]:
        print("   missing:", " ".join(report["missing"]))
    return 0


PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Night Elf Viewer</title>
<style>
:root { --bg:#14161b; --panel:#1d2027; --line:#2e323c; --text:#e6e3da; --dim:#9a968c; --accent:#c9a74e; --warn:#d9825b; }
* { box-sizing:border-box; }
html, body { margin:0; height:100%; background:var(--bg); color:var(--text); font:14px/1.4 system-ui, sans-serif; overflow:hidden; }
#view { position:fixed; inset:0; touch-action:none; }
#panel { position:fixed; top:12px; left:12px; width:300px; max-width:calc(100vw - 24px); max-height:calc(100vh - 24px); overflow:auto;
  background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:12px; }
h1 { font-size:15px; margin:0 0 2px; color:var(--accent); }
.sub { color:var(--dim); font-size:12px; margin-bottom:10px; }
.label { font-size:11px; text-transform:uppercase; letter-spacing:.06em; color:var(--dim); margin:10px 0 4px; }
#list { list-style:none; margin:0; padding:0; border:1px solid var(--line); border-radius:6px; }
#list li { padding:6px 8px; cursor:pointer; border-top:1px solid var(--line); }
#list li:first-child { border-top:0; }
#list li.on { background:#2b2a22; color:var(--accent); }
#list li .k { display:block; font-size:11px; color:var(--dim); }
#list li.test .k { color:var(--warn); }
.row { display:flex; gap:6px; align-items:center; margin-top:8px; flex-wrap:wrap; }
button, select { background:#272a32; color:var(--text); border:1px solid var(--line); border-radius:5px; padding:5px 10px; font:inherit; cursor:pointer; }
button:hover { border-color:var(--accent); }
input[type=range] { width:100%; accent-color:var(--accent); }
#info { margin-top:8px; font-size:12px; color:var(--dim); white-space:pre-wrap; }
#warn { margin-top:6px; font-size:12px; color:var(--warn); white-space:pre-wrap; }
#drop { position:fixed; inset:0; display:none; align-items:center; justify-content:center; background:rgba(20,22,27,.8);
  border:3px dashed var(--accent); font-size:20px; color:var(--accent); pointer-events:none; }
body.dragging #drop { display:flex; }
@media (max-width:600px) { #panel { top:auto; bottom:12px; max-height:45vh; } }
</style>
</head>
<body>
<canvas id="view"></canvas>
<div id="panel">
  <h1>Night elf animation viewer</h1>
  <div class="sub">Model 921844 (night elf woman), Nyrelle's look. Drag to turn, wheel or pinch to zoom.</div>
  <div class="label">Animations</div>
  <ul id="list"></ul>
  <div class="row">
    <button id="load">Load animation JSON...</button>
    <input id="file" type="file" accept=".json,application/json" multiple hidden>
  </div>
  <div class="row">
    <button id="play">Pause</button>
    <label><input id="loop" type="checkbox" checked> Loop</label>
    <label>Base <select id="base"><option value="rest">Rest pose</option><option value="stand">Stand, frame 0</option></select></label>
  </div>
  <div class="row"><input id="time" type="range" min="0" max="1000" value="0"></div>
  <div id="info"></div>
  <div id="warn"></div>
</div>
<div id="drop">Drop Anim JSON files</div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
"use strict";
const D = /*DATA*/null;

function bytes(b64) { const s = atob(b64), u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; }
const f32 = b64 => new Float32Array(bytes(b64).buffer);
const u32 = b64 => new Uint32Array(bytes(b64).buffer);

// ---------------------------------------------------------------- scene
const canvas = document.getElementById("view");
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(window.devicePixelRatio || 1);
renderer.outputEncoding = THREE.sRGBEncoding;
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x14161b);
const camera = new THREE.PerspectiveCamera(35, 1, 0.05, 100);
scene.add(new THREE.HemisphereLight(0xdfe6ff, 0x3a3328, 0.75));
const sun = new THREE.DirectionalLight(0xfff2dd, 0.85); sun.position.set(3, 5, 4); scene.add(sun);
const rim = new THREE.DirectionalLight(0x99aaff, 0.3); rim.position.set(-4, 3, -3); scene.add(rim);
const floor = new THREE.Mesh(new THREE.CircleGeometry(3, 64), new THREE.MeshBasicMaterial({ color: new THREE.Color(0x1c1f26).convertSRGBToLinear() }));
floor.rotation.x = -Math.PI / 2; scene.add(floor);
const grid = new THREE.GridHelper(6, 24, 0x3a3f4a, 0x262a32); grid.position.y = 0.002; scene.add(grid);

// M2 space is z up, the face along +x: turned so three's y is up.
const holder = new THREE.Group(); holder.rotation.x = -Math.PI / 2; scene.add(holder);

const geom = new THREE.BufferGeometry();
const pos = f32(D.pos), nV = pos.length / 3;
geom.setAttribute("position", new THREE.BufferAttribute(pos, 3));
geom.setAttribute("normal", new THREE.BufferAttribute(f32(D.nrm), 3));
geom.setAttribute("uv", new THREE.BufferAttribute(f32(D.uv), 2));
geom.setAttribute("skinIndex", new THREE.Uint8BufferAttribute(bytes(D.bidx), 4));
geom.setAttribute("skinWeight", new THREE.BufferAttribute(f32(D.bw), 4));
geom.setIndex(new THREE.BufferAttribute(u32(D.index), 1));

const texCache = {};
function tex(id) {
  if (texCache[id]) return texCache[id];
  const t = D.textures[id], img = new Image();
  const tx = new THREE.Texture(img);
  img.onload = () => { tx.needsUpdate = true; texReady++; };
  img.src = "data:image/png;base64," + t.png;
  tx.flipY = false; tx.encoding = THREE.sRGBEncoding;
  tx.wrapS = t.wrapU ? THREE.RepeatWrapping : THREE.ClampToEdgeWrapping;
  tx.wrapT = t.wrapV ? THREE.RepeatWrapping : THREE.ClampToEdgeWrapping;
  tx.anisotropy = 4;
  return texCache[id] = tx;
}
let texReady = 0;
const materials = D.groups.map((g, i) => {
  const o = { map: tex(g.tex), skinning: true, side: g.twoSided ? THREE.DoubleSide : THREE.FrontSide };
  if (g.blend === 1) o.alphaTest = 0.5;
  if (g.blend >= 2) { o.transparent = true; o.depthWrite = false; o.alphaTest = 0.004; }
  if (g.blend >= 3 && g.blend !== 5 && g.blend !== 6) o.blending = THREE.AdditiveBlending;
  geom.addGroup(g.start, g.count, i);
  return g.unlit ? new THREE.MeshBasicMaterial(o) : new THREE.MeshLambertMaterial(o);
});

const nB = D.bones.length;
const bones = D.bones.map(() => { const b = new THREE.Bone(); b.matrixAutoUpdate = false; return b; });
const skeleton = new THREE.Skeleton(bones, bones.map(() => new THREE.Matrix4()));
const mesh = new THREE.SkinnedMesh(geom, materials);
mesh.bindMode = "detached";
mesh.bind(skeleton, new THREE.Matrix4());
mesh.frustumCulled = false;
holder.add(mesh);

// The order parents come before children in.
const order = []; { const done = new Uint8Array(nB); let left = nB, guard = 0;
  while (left && guard++ < 64) for (let i = 0; i < nB; i++) { if (done[i]) continue; const p = D.bones[i].parent;
    if (p >= 0 && p < nB && !done[p]) continue; done[i] = 1; order.push(i); left--; } }

// ---------------------------------------------------------------- poses
// A pose: per bone a local translation, rotation (x, y, z, w) and scale, as an M2 bone's tracks give them.
function restPose() { const p = []; for (let i = 0; i < nB; i++) p.push({ t: [0, 0, 0], r: [0, 0, 0, 1], s: [1, 1, 1] }); return p; }

function sampleVec(times, vals, n, t, out) {
  const k = times.length; if (!k) return;
  let a = 0, b = 0, f = 0;
  if (k === 1 || t <= times[0]) { a = b = 0; }
  else if (t >= times[k - 1]) { a = b = k - 1; }
  else { let lo = 0, hi = k - 1; while (hi - lo > 1) { const m = (lo + hi) >> 1; if (times[m] <= t) lo = m; else hi = m; }
    a = lo; b = hi; f = (t - times[a]) / ((times[b] - times[a]) || 1); }
  if (n === 4) { // nlerp, the shorter way
    let d = 0; for (let i = 0; i < 4; i++) d += vals[a * 4 + i] * vals[b * 4 + i];
    const s = d < 0 ? -1 : 1; let l = 0;
    for (let i = 0; i < 4; i++) { out[i] = vals[a * 4 + i] * (1 - f) + s * vals[b * 4 + i] * f; l += out[i] * out[i]; }
    l = Math.sqrt(l) || 1; for (let i = 0; i < 4; i++) out[i] /= l;
  } else for (let i = 0; i < n; i++) out[i] = vals[a * n + i] * (1 - f) + vals[b * n + i] * f;
}

function standPose(ms) {
  const p = restPose();
  for (let i = 0; i < nB; i++) { const s = D.bones[i].stand;
    if (s.t) sampleVec(s.t.t, s.t.v, 3, ms, p[i].t);
    if (s.r) sampleVec(s.r.t, s.r.v, 4, ms, p[i].r);
    if (s.s) sampleVec(s.s.t, s.s.v, 3, ms, p[i].s); }
  return p;
}

const mL = new THREE.Matrix4(), mP = new THREE.Matrix4(), vT = new THREE.Vector3(), vS = new THREE.Vector3(), qR = new THREE.Quaternion();
function apply(pose) {
  for (const i of order) {
    const b = D.bones[i], q = pose[i], pv = b.pivot;
    vT.set(pv[0] + q.t[0], pv[1] + q.t[1], pv[2] + q.t[2]);
    qR.set(q.r[0], q.r[1], q.r[2], q.r[3]); vS.set(q.s[0], q.s[1], q.s[2]);
    mL.compose(vT, qR, vS); mP.makeTranslation(-pv[0], -pv[1], -pv[2]); mL.multiply(mP);
    const w = bones[i].matrixWorld;
    if (b.parent >= 0 && b.parent < nB) w.multiplyMatrices(bones[b.parent].matrixWorld, mL); else w.copy(mL);
  }
}

// ---------------------------------------------------------------- animations
// Every entry plays through one of two paths: the model's own Stand, or an Anim (the importer's JSON,
// version 1: tracks by WoW-side bone name), which the test poses are too.
const anims = [];
const list = document.getElementById("list"), info = document.getElementById("info"), warn = document.getElementById("warn");
const playBtn = document.getElementById("play"), loopBox = document.getElementById("loop"), scrub = document.getElementById("time");
const baseSel = document.getElementById("base");
let cur = 0, playing = true, t = 0, loadErr = "";
function addAnim(a) { anims.push(a); renderList(); return anims.length - 1; }

function animFromJson(o, file) {
  const v = o.v | 0;
  if (v < 1 || v > 1) throw new Error("animation file version " + v + " is not supported (this page reads version 1)");
  if (!Array.isArray(o.tracks)) throw new Error("no tracks");
  const tracks = [], unknown = new Set();
  for (const t of o.tracks) {
    const per = t.ch === "ROTATION" ? 4 : t.ch === "POSITION" ? 3 : 0;
    if (!per) throw new Error("track " + t.bone + ": channel " + t.ch + " is not ROTATION or POSITION");
    if (!Array.isArray(t.times) || !Array.isArray(t.values) || t.values.length !== t.times.length * per)
      throw new Error("track " + t.bone + " has " + (t.values || []).length + " values for " + (t.times || []).length + " keys");
    const bone = D.boneNames[t.bone];
    if (bone === undefined) { unknown.add(t.bone); continue; }
    tracks.push({ bone, name: t.bone, ch: t.ch, times: t.times.map(x => x * 1000), values: t.values });
  }
  return { kind: "anim", name: o.name || file || "Animation", note: (o.category ? o.category + " · " : "") + (file || "loaded file"),
    duration: Math.max(0, +o.duration || 0) * 1000, loop: !!o.loop, tracks, total: o.tracks.length, unknown: [...unknown] };
}

function animPose(a, ms, base) {
  const p = base;
  for (const t of a.tracks) {
    if (t.ch === "ROTATION") sampleVec(t.times, t.values, 4, ms, p[t.bone].r);
    else sampleVec(t.times, t.values, 3, ms, p[t.bone].t);
  }
  return p;
}

// Test poses, made here and NOT from the Sims: an Anim in the importer's own format (so they take the
// same path a loaded file does), from axis-angle turns in model space (z up, face +x, her left +y).
function quat(ax, deg) { const l = Math.hypot(ax[0], ax[1], ax[2]), h = deg * Math.PI / 360, s = Math.sin(h) / l;
  return [ax[0] * s, ax[1] * s, ax[2] * s, Math.cos(h)]; }
function testPose(name, turns, moves) {
  const tracks = [];
  for (const [bone, ax, deg] of turns) tracks.push({ bone, ch: "ROTATION", times: [0, 1], values: [0, 0, 0, 1, ...quat(ax, deg)] });
  for (const [bone, v] of moves || []) tracks.push({ bone, ch: "POSITION", times: [0, 1], values: [0, 0, 0, ...v] });
  const a = animFromJson({ v: 1, name, duration: 1, loop: false, tracks }, null);
  a.test = true; a.note = "Test pose (not from the Sims)";
  return a;
}
const TEST = [
  testPose("Arms raised", [["L_UpperArm", [1, 0, 0], 120], ["R_UpperArm", [1, 0, 0], -120],
    ["L_Forearm", [0, 0, 1], 10], ["R_Forearm", [0, 0, 1], -10]]),
  testPose("Seated-like leg bend", [["L_Thigh", [0, 1, 0], -85], ["R_Thigh", [0, 1, 0], -85],
    ["L_Calf", [0, 1, 0], 85], ["R_Calf", [0, 1, 0], 85], ["L_Foot", [0, 1, 0], -10], ["R_Foot", [0, 1, 0], -10]],
    [["Root", [0, 0, -0.5]]]),
  testPose("Head turned", [["Head", [0, 0, 1], 50], ["Neck1", [0, 0, 1], 15]]),
];

addAnim({ kind: "stand", name: "Stand", note: "The model's own animation (id 0)", duration: D.standMs, loop: true });
for (const t of TEST) addAnim(t);

// ---------------------------------------------------------------- panel

function renderList() {
  list.innerHTML = "";
  anims.forEach((a, i) => {
    const li = document.createElement("li");
    li.className = (i === cur ? "on " : "") + (a.test ? "test" : "");
    li.textContent = a.name;
    const k = document.createElement("span"); k.className = "k"; k.textContent = a.note; li.appendChild(k);
    li.onclick = () => select(i);
    list.appendChild(li);
  });
}
function select(i) {
  cur = i; t = 0; const a = anims[i];
  loopBox.checked = a.loop; playing = true; playBtn.textContent = "Pause";
  warn.textContent = (a.kind === "anim" && a.unknown.length ? "Bones this model has no match for (skipped): " + a.unknown.join(", ") : "") +
    (loadErr ? "\nNot loaded:\n" + loadErr : "");
  renderList();
}
playBtn.onclick = () => { playing = !playing; playBtn.textContent = playing ? "Pause" : "Play";
  const a = anims[cur]; if (playing && !loopBox.checked && t >= a.duration) t = 0; };
scrub.oninput = () => { const a = anims[cur]; t = scrub.value / 1000 * a.duration; playing = false; playBtn.textContent = "Play"; };

const fileIn = document.getElementById("file");
document.getElementById("load").onclick = () => fileIn.click();
fileIn.onchange = () => { loadFiles([...fileIn.files]); fileIn.value = ""; };
async function loadFiles(files) {
  const errs = []; let last = -1; loadErr = "";
  for (const f of files) {
    try { last = addAnim(animFromJson(JSON.parse(await f.text()), f.name)); }
    catch (e) { errs.push(f.name + ": " + e.message); }
  }
  loadErr = errs.join("\n");
  select(last >= 0 ? last : cur);
}
let dragDepth = 0;
addEventListener("dragenter", e => { e.preventDefault(); dragDepth++; document.body.classList.add("dragging"); });
addEventListener("dragleave", e => { if (--dragDepth <= 0) { dragDepth = 0; document.body.classList.remove("dragging"); } });
addEventListener("dragover", e => e.preventDefault());
addEventListener("drop", e => { e.preventDefault(); dragDepth = 0; document.body.classList.remove("dragging"); loadFiles([...e.dataTransfer.files]); });

// ---------------------------------------------------------------- orbit, zoom, pinch
const target = new THREE.Vector3(0, 1.3, 0);
let yaw = 0.35, pitch = 0.08, dist = 5.4;
function placeCamera() {
  camera.position.set(target.x + dist * Math.cos(pitch) * Math.cos(yaw), target.y + dist * Math.sin(pitch), target.z - dist * Math.cos(pitch) * Math.sin(yaw));
  camera.lookAt(target);
}
const ptrs = new Map(); let pinch0 = 0, dist0 = 0;
canvas.addEventListener("pointerdown", e => { canvas.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, [e.clientX, e.clientY]);
  if (ptrs.size === 2) { const [a, b] = [...ptrs.values()]; pinch0 = Math.hypot(a[0] - b[0], a[1] - b[1]); dist0 = dist; } });
canvas.addEventListener("pointermove", e => {
  if (!ptrs.has(e.pointerId)) return;
  const prev = ptrs.get(e.pointerId); ptrs.set(e.pointerId, [e.clientX, e.clientY]);
  if (ptrs.size === 1) { yaw -= (e.clientX - prev[0]) * 0.008; pitch = Math.max(-1.2, Math.min(1.4, pitch + (e.clientY - prev[1]) * 0.006)); }
  else if (ptrs.size === 2 && pinch0) { const [a, b] = [...ptrs.values()]; dist = clampDist(dist0 * pinch0 / Math.max(1, Math.hypot(a[0] - b[0], a[1] - b[1]))); }
});
const up = e => { ptrs.delete(e.pointerId); if (ptrs.size < 2) pinch0 = 0; };
canvas.addEventListener("pointerup", up); canvas.addEventListener("pointercancel", up);
canvas.addEventListener("wheel", e => { e.preventDefault(); dist = clampDist(dist * Math.exp(e.deltaY * 0.001)); }, { passive: false });
function clampDist(d) { return Math.max(0.6, Math.min(12, d)); }

function resize() { const w = innerWidth, h = innerHeight; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix(); }
addEventListener("resize", resize); resize();

// ---------------------------------------------------------------- loop
let last = performance.now();
function frame(now) {
  const dt = now - last; last = now;
  const a = anims[cur];
  if (playing) {
    t += dt;
    if (t > a.duration) { if (loopBox.checked && a.duration > 0) t %= a.duration; else { t = a.duration; } }
  }
  let pose;
  if (a.kind === "stand") pose = standPose(t);
  else { const base = baseSel.value === "stand" ? standPose(0) : restPose(); pose = animPose(a, t, base); }
  apply(pose);
  scrub.value = a.duration ? Math.round(t / a.duration * 1000) : 0;
  const applied = a.kind === "anim" ? " · " + a.tracks.length + " of " + a.total + " tracks on her bones" : "";
  info.textContent = a.name + " · " + (t / 1000).toFixed(2) + " / " + (a.duration / 1000).toFixed(2) + " s" + applied +
    "\n" + Object.keys(D.boneNames).length + " named bones resolved" + (D.missing.length ? ", unresolved: " + D.missing.join(", ") : ", none unresolved");
  placeCamera();
  renderer.render(scene, camera);
  requestAnimationFrame(frame);
}
placeCamera();
requestAnimationFrame(frame);

// For screenshots: viewer.show(index, ms, base) poses and draws one frame.
window.viewer = { anims, count: () => anims.length, texturesLoaded: () => texReady >= Object.keys(D.textures).length,
  show(i, ms, base, view) { select(i); playing = false; t = ms; if (base) baseSel.value = base; if (view) { yaw = view[0]; pitch = view[1]; dist = view[2]; } },
  boneNames: D.boneNames };
</script>
</body>
</html>
"""

if __name__ == "__main__":
    sys.exit(main())
