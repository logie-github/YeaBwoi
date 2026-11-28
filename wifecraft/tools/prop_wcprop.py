#!/usr/bin/env python3
"""Writes the objects of Sims 4 object packages as ".wcprop" files for the desktop harness (the same layout as
props/PropFile.kt writes on the phone): python3 -I tools/prop_wcprop.py OUT_DIR PACKAGE... Prints one line per file:
name, guid, triangles, size (Warcraft axes). Reads the packages only, executes nothing in them."""
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prop_preview as pp  # noqa: E402


def write_prop(path, guid, ms):
    # Sims axes (x right, y up, z forward) to Warcraft's (x forward = z, y left = x, z up = y)
    meshes, textures = [], []
    lo = [1e9] * 3
    hi = [-1e9] * 3
    for m in ms:
        pos = [(p[2], p[0], p[1]) for p in m["pos"]]
        nor = [(n[2], n[0], n[1]) for n in m["nor"]] if m["nor"] else None
        uv = m["uv"] if m["uv"] else None
        tex = -1
        if m["rgba"] is not None and uv:
            t = m["rgba"]
            h, w = t.shape[:2]
            if t.shape[2] == 3:
                import numpy as np
                t = np.concatenate([t, np.full((h, w, 1), 255, t.dtype)], axis=2)
            textures.append((w, h, t.tobytes()))
            tex = len(textures) - 1
        col = list(m["color"][:4]) if m["color"] else [0.78, 0.78, 0.8, 1.0]
        while len(col) < 4:
            col.append(1.0)
        for p in pos:
            for a in range(3):
                lo[a] = min(lo[a], p[a])
                hi[a] = max(hi[a], p[a])
        meshes.append((pos, nor, uv, list(m["idx"]), col, tex))
    o = bytearray()
    o += struct.pack("<II", 0x52504357, 1) + struct.pack("<Q", guid) + struct.pack("<6f", *lo, *hi)
    o += struct.pack("<I", 0)
    o += struct.pack("<I", len(meshes))
    for pos, nor, uv, idx, col, tex in meshes:
        o += struct.pack("<III", len(pos), len(idx), (1 if nor else 0) | (2 if uv else 0))
        o += struct.pack("<4f", *col) + struct.pack("<i", tex)
        o += b"".join(struct.pack("<3f", *p) for p in pos)
        if nor:
            o += b"".join(struct.pack("<3f", *n) for n in nor)
        if uv:
            o += b"".join(struct.pack("<2f", *u) for u in uv)
        o += struct.pack("<%dI" % len(idx), *idx)
    o += struct.pack("<I", len(textures))
    for w, h, raw in textures:
        z = zlib.compress(raw, 6)
        o += struct.pack("<III", w, h, len(z)) + z
    o += struct.pack("<I", zlib.crc32(bytes(o)) & 0xFFFFFFFF)
    open(path, "wb").write(bytes(o))


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for pkg in sys.argv[2:]:
        for guid, vh, ms in pp.load_objects(pkg):
            if not ms:
                continue
            name = "%s_%x.wcprop" % (os.path.splitext(os.path.basename(pkg))[0], guid)
            path = os.path.join(out, name)
            write_prop(path, guid, ms)
            tris = sum(len(m["idx"]) // 3 for m in ms)
            allp = [(p[2], p[0], p[1]) for m in ms for p in m["pos"]]
            ext = [max(p[a] for p in allp) - min(p[a] for p in allp) for a in range(3)]
            print(name, guid, tris, "size %.3f %.3f %.3f" % tuple(ext))


if __name__ == "__main__":
    main()
