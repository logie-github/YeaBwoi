#!/usr/bin/env python3
"""Draws the objects of Sims 4 object packages (toys, dolls, a bed, a cum mesh) from the app's reader's reference
implementation, for a look: python3 -I tools/prop_preview.py OUT.png PACKAGE... [--label TEXT]. A software rasteriser
(numpy, PIL): flat light, the diffuse texture of the object's material variant where it has one. Reads only."""
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dbpf  # noqa: E402
import sims_object as so  # noqa: E402
import sims_texture as st  # noqa: E402


def load_objects(path):
    """[(guid, name, [mesh dict + 'rgba' texture or None + 'color'])] of the first LOD of each distinct model/variant."""
    b = open(path, "rb").read()
    es = dbpf.entries(b)
    out = []
    for guid, o in so.read_package_objects(b).items():
        if o["modl"] is None:
            continue
        md = so.find(b, es, so.T_MODL, o["modl"][0])
        if md is None:
            continue
        _c, ext = so.rcol(md)
        lods = [k for k in ext if k[0] == so.T_MLOD]
        if not lods:
            continue
        ml = so.find(b, es, so.T_MLOD, lods[0][2], lods[0][1])
        chunks, _ = so.rcol(ml)
        vh = so.variant_hash(o["raw"])
        ms = so.meshes(ml)
        for m in ms:
            matd = so.pick_material(chunks, m["material"], vh)
            tex, color = None, None
            if matd is not None:
                texs = so.material_textures(matd[3])
                key = texs.get(so.P_DIFFUSE)
                if key:
                    for e in es:
                        if e["type"] == key[1] and e["inst"] == key[0]:
                            tex = st.decode(dbpf.read(b, e))
                            break
                cols = so.material_color(matd[3])
                color = next(iter(cols.values()), None) if cols else None
            m["rgba"], m["color"] = tex, color
        out.append((guid, vh, ms))
    return out


def render(ms, size=420, yaw=0.6, pitch=0.25):
    allp = np.array([p for m in ms for p in m["pos"]], np.float32)
    lo, hi = allp.min(0), allp.max(0)
    c = (lo + hi) / 2
    ext = max(hi - lo) or 1.0
    cy, sy, cp, sp = np.cos(yaw), np.sin(yaw), np.cos(pitch), np.sin(pitch)
    R = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], np.float32)
    P = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]], np.float32)
    M = P @ R
    img = np.full((size, size, 3), 28, np.uint8)
    zb = np.full((size, size), 1e9, np.float32)
    s = size * 0.8 / ext
    for m in ms:
        p = (np.array(m["pos"], np.float32) - c) @ M.T
        n = (np.array(m["nor"], np.float32) @ M.T) if m["nor"] else None
        uv = np.array(m["uv"], np.float32) if m["uv"] else None
        tex = m["rgba"]
        base = np.array(m["color"][:3] if m["color"] else (0.75, 0.75, 0.78), np.float32)
        base = np.clip(base, 0, 1) * 255
        idx = np.array(m["idx"], np.int64).reshape(-1, 3)
        sx = p[:, 0] * s + size / 2
        sy2 = size / 2 - p[:, 1] * s
        for a, b_, c_ in idx:
            xs, ys = sx[[a, b_, c_]], sy2[[a, b_, c_]]
            x0, x1 = int(max(xs.min(), 0)), int(min(xs.max() + 1, size - 1))
            y0, y1 = int(max(ys.min(), 0)), int(min(ys.max() + 1, size - 1))
            if x1 < x0 or y1 < y0:
                continue
            d = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if abs(d) < 1e-9:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            w0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / d
            w1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / d
            w2 = 1 - w0 - w1
            ok = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not ok.any():
                continue
            z = w0 * p[a, 2] + w1 * p[b_, 2] + w2 * p[c_, 2]
            sub = zb[y0:y1 + 1, x0:x1 + 1]
            ok &= z < sub
            if not ok.any():
                continue
            sub[ok] = z[ok]
            if n is not None:
                nn = w0[..., None] * n[a] + w1[..., None] * n[b_] + w2[..., None] * n[c_]
                nn = nn / (np.linalg.norm(nn, axis=-1, keepdims=True) + 1e-9)
                lam = 0.35 + 0.65 * np.clip(-nn[..., 2] * 0.6 + nn[..., 1] * 0.5 + nn[..., 0] * 0.3, 0, 1)
                lam = np.clip(lam * 1.0, 0, 1)
            else:
                lam = np.full(gx.shape, 0.8, np.float32)
            if tex is not None and uv is not None:
                u = w0 * uv[a, 0] + w1 * uv[b_, 0] + w2 * uv[c_, 0]
                v = w0 * uv[a, 1] + w1 * uv[b_, 1] + w2 * uv[c_, 1]
                h, w = tex.shape[:2]
                col = tex[np.clip((v % 1.0) * h, 0, h - 1).astype(int), np.clip((u % 1.0) * w, 0, w - 1).astype(int), :3].astype(np.float32)
            else:
                col = np.broadcast_to(base, gx.shape + (3,))
            region = img[y0:y1 + 1, x0:x1 + 1]
            region[ok] = np.clip(col[ok] * lam[ok][:, None] * 1.15, 0, 255).astype(np.uint8)
    return Image.fromarray(img)


def main():
    out, pkgs = sys.argv[1], [a for a in sys.argv[2:] if not a.startswith("--")]
    label = ""
    if "--label" in sys.argv:
        label = sys.argv[sys.argv.index("--label") + 1]
        pkgs = [a for a in pkgs if a != label]
    tiles = []
    for pkg in pkgs:
        seen = set()
        for guid, vh, ms in load_objects(pkg):
            sig = (len(ms), sum(len(m["pos"]) for m in ms), vh)
            if sig in seen:
                continue
            seen.add(sig)
            for yaw in (0.5, 2.2):
                im = render(ms, 300, yaw)
                d = ImageDraw.Draw(im)
                d.rectangle([0, 0, 299, 34], fill=(255, 235, 60))
                d.text((6, 4), "%s" % os.path.basename(pkg)[:44], fill=(0, 0, 0))
                d.text((6, 18), "%d meshes %d verts guid %d" % (len(ms), sum(len(m["pos"]) for m in ms), guid % 100000), fill=(0, 0, 0))
                tiles.append(im)
    cols = min(4, len(tiles))
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * 300, rows * 300), (28, 28, 28))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % cols) * 300, (i // cols) * 300))
    if label:
        d = ImageDraw.Draw(sheet)
        d.rectangle([0, 0, sheet.width, 0], fill=(0, 0, 0))
    sheet.save(out)
    print(out, len(tiles), "views")


if __name__ == "__main__":
    main()
