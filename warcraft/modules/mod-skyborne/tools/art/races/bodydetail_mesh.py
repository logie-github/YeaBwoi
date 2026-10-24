#!/usr/bin/env python3
"""Bottom Features (mod-skyborne): the detail mesh of a race's female model, data/client/bodydetail/<Race>.mesh,
and the model's line in data/client/bodydetail/models.txt, from the HD look's own files.

The mesh is a copy of the model's bare-body triangles (its sections of geosets 0, 1301 and 1801 drawn with
the body texture) that reach into where the art of every style can be (y -70 to 70 mm, s -8 to 115 mm of the
body frame), face the front, follow the body more than the legs (at each corner a leg weight of about 0.8 at
most, so the mesh ends where the legs take over) and lie within the art's square. The patch (src/SkyborneModel.cpp)
adds it to the model as geoset 1101, LIFT off the body, drawn with the lower half of the tall extra texture;
the file's format is in src/SkyborneModel.h.

The body frame is the art's: y = mm across (+: the model's left), s = mm up the front of the body from the
frame's base height (decal/frames.json's zj), along the profile of the body's middle line (below the base,
the height under it). A vertex at (y, s) takes, in the tall texture,
    u = (y + 80) / 160        v = 0.5 + g + (130 - s) / 160 * (0.5 - g)
(g: the gutter, 16 rows of its 1024), so a style's 512 by 512 art is the frame from y -80 to 80 and s 130
down to -30, upright and as seen from the front.

    python3 races/bodydetail_mesh.py SOURCE [RACE ...] [--same OTHER]... [--prints] [--out DIR]

SOURCE: the HD look's character archive (ChromieCraft's Patch-H.MPQ) or a folder of
Character/<Race>/Female/<Race>Female.m2 and 00.skin (the Alternative HD look's, say). Each race named gets
its mesh and its line in models.txt (with --prints only the line, and every race when none is named);
--same checks another source has the same models; --out writes elsewhere. Needs numpy.
"""
import argparse
import json
import os
import struct
import sys
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
OUT = os.path.join(ART, '..', '..', 'data', 'client', 'bodydetail')
sys.path.insert(0, os.path.join(ART, 'tools'))
import mpq  # noqa: E402

RACES = ['Human', 'Orc', 'Dwarf', 'NightElf', 'Scourge', 'Gnome', 'Troll', 'BloodElf', 'Draenei']
GEOSETS = (0, 1301, 1801)
BODY_TEXTURE = 1
Y_MAX, S_MIN, S_MAX = 70.0, -8.0, 115.0       # mm: where the art of every style can be
LEG_MAX = 0.85                                # the legs' share of a corner's bone weights: where the legs
                                              # take over 0.8, on the legs 0.9 and more
FRONT = 0.15                                  # the least forward (x) part of a triangle's normal
LIFT = 0.0015                                 # metres the copy stands off the body
GUTTER = 16 / 1024.0                          # src/SkyborneModel.h's DETAIL_GUTTER
INDEX_LIMIT = 65535                           # the game reads a section's first index in 16 bits

# The frame (as the art is made in): the art's square, mm.
Y0, Y1 = -80.0, 80.0
S0, S1 = -30.0, 130.0


def uv(y, s):
    return (np.asarray(y) - Y0) / (Y1 - Y0), (S1 - np.asarray(s)) / (S1 - S0)


def tall_uv(y, s):
    """Where (y, s) is in the tall extra texture: the frame in its lower half, under the gutter."""
    u, v = uv(y, s)
    return u, 0.5 + GUTTER + v * (0.5 - GUTTER)


# The model ----------------------------------------------------------------------------------------------

def arr(d, off):
    return struct.unpack_from('<II', d, off)


class Model:
    """What the mesh needs of a model of the 3.3.5 format (264) and its first skin."""

    def __init__(self, m2, skin):
        if m2[:4] != b'MD20' or struct.unpack_from('<I', m2, 4)[0] != 264:
            raise ValueError('not a model of the 3.3.5 format')
        if skin[:4] != b'SKIN':
            raise ValueError('not a skin file')
        self.m2, self.skin = m2, skin
        n, o = arr(m2, 60)
        self.vertex_block = m2[o:o + n * 48]
        v = np.frombuffer(self.vertex_block, dtype=np.uint8).reshape(n, 48)
        self.pos = np.frombuffer(v[:, 0:12].tobytes(), dtype='<f4').reshape(n, 3).astype(np.float64)
        self.normals = np.frombuffer(v[:, 20:32].tobytes(), dtype='<f4').reshape(n, 3).astype(np.float64)
        self.uv = np.frombuffer(v[:, 32:40].tobytes(), dtype='<f4').reshape(n, 2).astype(np.float64)
        self.weights = v[:, 12:16].astype(np.float64) / 255.0
        self.bones = v[:, 16:20].astype(np.int64)
        n, o = arr(m2, 80)
        self.textures = [struct.unpack_from('<I', m2, o + i * 16)[0] for i in range(n)]
        n, o = arr(m2, 128)
        self.tex_lookup = list(struct.unpack_from('<%dh' % n, m2, o))
        n, o = arr(m2, 44)
        self.bone_keys = [struct.unpack_from('<i', m2, o + i * 88)[0] for i in range(n)]
        self.bone_parents = [struct.unpack_from('<h', m2, o + i * 88 + 8)[0] for i in range(n)]
        self.bone_pivots = np.array([struct.unpack_from('<3f', m2, o + i * 88 + 76) for i in range(n)])
        vn, vo, ixn, ixo, pn, po, sn, so, bn, bo = struct.unpack_from('<10I', skin, 4)
        self.lookup = np.frombuffer(skin, dtype='<u2', count=vn, offset=vo).astype(np.int64)
        self.indices = np.frombuffer(skin, dtype='<u2', count=ixn, offset=ixo).astype(np.int64)
        self.sections = [struct.unpack_from('<10H', skin, so + i * 48) for i in range(sn)]
        self.batches = [struct.unpack_from('<BbHHHhHHHHHHH', skin, bo + i * 24) for i in range(bn)]

    def fingerprint(self):
        """models.txt's and the mesh's: the CRC32 of the model's vertices and of its 00.skin."""
        return zlib.crc32(self.vertex_block) & 0xffffffff, zlib.crc32(self.skin) & 0xffffffff

    def drawn_with(self, section, kind):
        for b in self.batches:
            if b[3] == section and b[9] < len(self.tex_lookup) and self.textures[self.tex_lookup[b[9]]] == kind:
                return True
        return False

    def triangles(self, section):
        """A section's triangles, as the skin's vertices (its lookup entries)."""
        s = self.sections[section]
        return self.indices[s[4]:s[4] + s[5]].reshape(-1, 3)

    def bare(self):
        """The sections the game draws with nothing worn and the body texture: geoset 0 and of each group it
        knows (up to 18) the first geoset (X01; 702 of group 7), as render_m2.visible_geosets decides."""
        present = {s[0] for s in self.sections}
        shown = {0}
        for g in {p // 100 for p in present}:
            if g in (0, 1, 2, 3, 16, 17) or g > 18:
                continue
            first = 702 if g == 7 else g * 100 + 1
            if first in present:
                shown.add(first)
        return [i for i, s in enumerate(self.sections) if s[0] in shown and self.drawn_with(i, BODY_TEXTURE)]

    def leg_weights(self):
        """How much each of the model's vertices follows the legs: its weights on the upper-leg bones (key bones
        55 and 56) and every bone under them. In models without those keys an upper leg is a bone off the middle
        (|y| over 3 cm) with a child straight below it, a quarter metre or more lower."""
        legs = set(i for i, k in enumerate(self.bone_keys) if k in (55, 56))
        piv = self.bone_pivots
        for i in range(len(piv)):
            if abs(piv[i][1]) < 0.03:
                continue
            for j, p in enumerate(self.bone_parents):
                if p == i and abs(piv[j][1] - piv[i][1]) < 0.03 and piv[i][2] - piv[j][2] > 0.25:
                    legs.add(i)
        grown = True
        while grown:
            grown = False
            for i, p in enumerate(self.bone_parents):
                if p in legs and i not in legs:
                    legs.add(i)
                    grown = True
        return (self.weights * np.isin(self.bones, list(legs))).sum(1)


# The frame -----------------------------------------------------------------------------------------------

def texel_map(model, ss=2):
    """Where each texel of the body texture's part at x 256-512, y 192-320 (of 512) is on the model, at ss
    samples a texel: rest position and normal of the front-most surface there (NaN where none)."""
    tris = np.concatenate([model.lookup[model.triangles(i)] for i in model.bare()])
    P, N, UV = model.pos, model.normals, model.uv
    W, H = 256 * ss, 128 * ss
    pos = np.full((H, W, 3), np.nan)
    nor = np.full((H, W, 3), np.nan)
    for t in tris:
        px = (UV[t, 0] * 512 - 256) * ss
        py = (UV[t, 1] * 512 - 192) * ss
        if px.max() < 0 or px.min() > W or py.max() < 0 or py.min() > H:
            continue
        x0, x1 = int(max(0, np.floor(px.min()))), int(min(W - 1, np.ceil(px.max())))
        y0, y1 = int(max(0, np.floor(py.min()))), int(min(H - 1, np.ceil(py.max())))
        if x1 < x0 or y1 < y0:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        den = (py[1] - py[2]) * (px[0] - px[2]) + (px[2] - px[1]) * (py[0] - py[2])
        if abs(den) < 1e-12:
            continue
        l0 = ((py[1] - py[2]) * (gx - px[2]) + (px[2] - px[1]) * (gy - py[2])) / den
        l1 = ((py[2] - py[0]) * (gx - px[2]) + (px[0] - px[2]) * (gy - py[2])) / den
        l2 = 1 - l0 - l1
        ok = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
        if not ok.any():
            continue
        p = l0[..., None] * P[t[0]] + l1[..., None] * P[t[1]] + l2[..., None] * P[t[2]]
        n = l0[..., None] * N[t[0]] + l1[..., None] * N[t[1]] + l2[..., None] * N[t[2]]
        sub, subn = pos[y0:y1 + 1, x0:x1 + 1], nor[y0:y1 + 1, x0:x1 + 1]
        better = ok & (np.isnan(sub[..., 0]) | (p[..., 0] > sub[..., 0]))     # the front-most where shared
        sub[better] = p[better]
        subn[better] = n[better]
    return pos, nor


class Frame:
    """The body frame of a model: (y, s) of rest positions, s along the profile of the front of the body's middle
    line (texels within 8 mm of it, facing forward) from the base height zj up, in 4 mm steps."""

    def __init__(self, model, zj, ss=2):
        pos, nor = texel_map(model, ss)
        self.zj = zj
        ok = ~np.isnan(pos[..., 0]) & (nor[..., 0] > 0.15)
        mid = ok & (np.abs(pos[..., 1]) < 0.008)
        z, x = pos[mid][:, 2], pos[mid][:, 0]
        zx, xx = [], []
        for b in np.arange(zj, z.max() + 0.004, 0.004):
            sel = (z >= b) & (z < b + 0.004)
            if sel.any():
                zx.append(b + 0.002)
                xx.append(x[sel].max())
        self.zx = np.array(zx)
        self.arc = np.concatenate([[0], np.cumsum(np.hypot(np.diff(self.zx), np.diff(np.array(xx))))])

    def ys(self, P):
        P = np.asarray(P, float)
        z = P[:, 2]
        s = np.where(z >= self.zj, np.interp(z, self.zx, self.arc) + (self.zx[0] - self.zj), z - self.zj)
        return P[:, 1] * 1000.0, s * 1000.0

    def top(self):
        """The highest point of the profile (higher, s would stand still): its height and s (mm)."""
        return self.zx[-1], (self.arc[-1] + self.zx[0] - self.zj) * 1000.0


# The mesh ------------------------------------------------------------------------------------------------

def zj_of(race):
    return json.load(open(os.path.join(ART, 'decal', 'frames.json')))[race]['zj']


def reaches(y, s):
    """Which triangles ((y, s) of their corners, n by 3) reach into the region: none of the region's two axes or
    the triangle's three edge normals parts them."""
    lo, hi = np.array([-Y_MAX, S_MIN]), np.array([Y_MAX, S_MAX])
    corners = np.array([[lo[0], lo[1]], [hi[0], lo[1]], [hi[0], hi[1]], [lo[0], hi[1]]])
    P = np.stack([y, s], -1)
    ok = (P.max(1) >= lo).all(1) & (P.min(1) <= hi).all(1)
    for j in range(3):
        e = P[:, (j + 1) % 3] - P[:, j]
        n = np.stack([-e[:, 1], e[:, 0]], -1)
        tri = np.einsum('nk,nck->nc', n, P)
        box = corners @ n.T
        ok &= (box.max(0) >= tri.min(1)) & (box.min(0) <= tri.max(1))
    return ok


def detail_mesh(model, frame):
    """The triangles copied, {section: [(a, b, c) as the skin's vertices]}, and each copied vertex's (u, v)."""
    legw = model.leg_weights()
    ztop, _ = frame.top()
    tris, place = {}, {}
    for i in sorted(set(model.bare()) & {k for k, s in enumerate(model.sections) if s[0] in GEOSETS}):
        t = model.triangles(i)
        g = model.lookup[t]
        P = model.pos[g]
        y, s = frame.ys(P.reshape(-1, 3))
        y, s = y.reshape(-1, 3), s.reshape(-1, 3)
        normal = np.cross(P[:, 1] - P[:, 0], P[:, 2] - P[:, 0])
        front = normal[:, 0] > FRONT * np.linalg.norm(normal, axis=1)
        body = (legw[g] <= LEG_MAX).all(1)
        square = ((np.abs(y) <= Y1) & (s >= S0) & (s <= S1) & (P[..., 2] <= ztop)).all(1)
        keep = reaches(y, s) & front & body & square
        if keep.any():
            tris[i] = [tuple(int(k) for k in row) for row in t[keep]]
            for row, yy, ss in zip(t[keep], y[keep], s[keep]):
                for k, a, b in zip(row, yy, ss):
                    place[int(k)] = tall_uv(a, b)
    return tris, place


def mesh_text(race, model, tris, place):
    crc_v, crc_s = model.fingerprint()
    lines = {k: n for n, k in enumerate(sorted(place))}
    count = sum(len(t) for t in tris.values())
    out = ['# Bottom Features: the body detail mesh of the %s female model, made by tools/art/races/bodydetail_mesh.py' % race,
           '# (its format: src/SkyborneModel.h)',
           'model %08x %08x %d %d' % (crc_v, crc_s, len(lines), count * 3),
           'lift %g' % LIFT]
    for k in sorted(place):
        u, v = place[k]
        out.append('vertex %d %.6f %.6f' % (k, u, v))
    for section in sorted(tris):
        for a, b, c in tris[section]:
            out.append('tri %d %d %d %d' % (section, lines[a], lines[b], lines[c]))
    return '\n'.join(out) + '\n'


def headroom(model):
    return INDEX_LIMIT - max(s[4] for s in model.sections)


# models.txt ----------------------------------------------------------------------------------------------

MODELS_HEAD = ['# Bottom Features: the female models the body detail is made for, a race a line: the CRC32 (hex) of the',
               "# model's vertices and of its 00.skin, the same in ChromieCraft's HD look and the Alternative HD look",
               '# (tools/art/races/bodydetail_mesh.py). A race whose model has another, or that has no <Race>.mesh, is left as it is.']


def write_models(path, prints):
    old = {}
    if os.path.exists(path):
        for line in open(path):
            f = line.split('#')[0].split()
            if len(f) == 3:
                old[f[0]] = (int(f[1], 16), int(f[2], 16))
    old.update(prints)
    with open(path, 'w') as out:
        out.write('\n'.join(MODELS_HEAD) + '\n')
        for race in [r for r in RACES if r in old] + sorted(r for r in old if r not in RACES):
            out.write('%s %08x %08x\n' % (race, old[race][0], old[race][1]))


# Sources -------------------------------------------------------------------------------------------------

class Source:
    def __init__(self, path):
        self.path = path
        self.archive = None if os.path.isdir(path) else mpq.Mpq(path)

    def read(self, name):
        if self.archive:
            return self.archive.read(name)
        full = os.path.join(self.path, *name.split('\\'))
        return open(full, 'rb').read() if os.path.isfile(full) else None

    def model(self, race):
        base = 'Character\\%s\\Female\\%sFemale' % (race, race)
        m2, skin = self.read(base + '.m2'), self.read(base + '00.skin')
        return Model(m2, skin) if m2 and skin else None


def main():
    ap = argparse.ArgumentParser(description='Bottom Features: the detail mesh of a female model, and models.txt')
    ap.add_argument('source', help="the HD look's Patch-H.MPQ or a folder of Character/<Race>/Female/")
    ap.add_argument('races', nargs='*', help='the races (all nine with --prints)')
    ap.add_argument('--same', action='append', default=[], help='another source that must have the same models')
    ap.add_argument('--prints', action='store_true', help="only models.txt's lines")
    ap.add_argument('--out', default=OUT, help='where the meshes and models.txt go (data/client/bodydetail)')
    a = ap.parse_args()
    races = a.races or (RACES if a.prints else [])
    if not races:
        ap.error('name a race (or --prints for all)')
    source, others = Source(a.source), [Source(p) for p in a.same]
    os.makedirs(a.out, exist_ok=True)
    prints, failed = {}, False
    for race in races:
        model = source.model(race)
        if model is None:
            print('%s: no model in %s' % (race, a.source))
            continue
        fp = model.fingerprint()
        differ = []
        for other in others:
            m = other.model(race)
            if m is None or m.fingerprint() != fp:
                differ.append(other.path)
        if differ:
            print('%s: model %08x %08x, other in %s' % (race, fp[0], fp[1], ', '.join(differ)))
            failed = True
            continue
        prints[race] = fp
        line = '%s: model %08x %08x' % (race, fp[0], fp[1]) + (' (the same in %d other)' % len(others) if others else '')
        if a.prints:
            print(line)
            continue
        frame = Frame(model, zj_of(race))
        tris, place = detail_mesh(model, frame)
        count = 3 * sum(len(t) for t in tris.values())
        room = headroom(model)
        print('%s, %d triangles in %d sections, %d vertices, %d indices of %d free' % (
            line, count // 3, len(tris), len(place), count, room))
        if not count or count > room:
            print('%s: the mesh is %s' % (race, 'empty' if not count else 'too big for the skin'))
            failed = True
            continue
        with open(os.path.join(a.out, race + '.mesh'), 'w') as out:
            out.write(mesh_text(race, model, tris, place))
    if prints:
        write_models(os.path.join(a.out, 'models.txt'), prints)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
