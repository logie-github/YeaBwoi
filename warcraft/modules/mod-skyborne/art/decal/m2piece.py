import sys, struct, json, os
sys.path.insert(0, 'tools'); sys.path.insert(0, 'decal')
import numpy as np
import mpq
from frame import s_of_z

race = sys.argv[1]
fr = json.load(open('decal/frames.json'))[race]
cfg = json.load(open('decal/race_cfg.json'))[race]
CW, CH = cfg['canvas']; rx, ry, rw, rh = cfg['region']
ZLO = cfg.get('below', 0.05)
YMAX = cfg.get('ymax', 0.105)
GEOSETS = cfg.get('geosets', [1301])
XMIN = cfg.get('xmin', 0.0)
ZTOP = cfg.get('ztop', 9.0)
SHELLS = [(0.0020, 0.0, 0.0), (0.0045, 0.006, 0.004), (0.0072, -0.005, 0.009)]

m = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in m.listfile()}
d = bytearray(m.read(low[f'character\\{race}\\female\\{race}female.m2'.lower()]))
def arr(off): return struct.unpack_from('<II', d, off)
def append(blob):
    while len(d) % 16: d.append(0)
    at = len(d); d.extend(blob); return at

nv, ov = arr(0x3C)
verts = [bytes(d[ov + 48 * i: ov + 48 * i + 48]) for i in range(nv)]
unp = [struct.unpack('<3f4B4B3f2f2f', v) for v in verts]
nt, ot = arr(0x50); texs = [struct.unpack_from('<IIII', d, ot + 16 * i) for i in range(nt)]
nl, ol = arr(0x80); lookup = list(struct.unpack_from('<%dH' % nl, d, ol))
nmat, omat = arr(0x70); mats = [struct.unpack_from('<HH', d, omat + 4 * i) for i in range(nmat)]
blend_mat = next((i for i, (f, b) in enumerate(mats) if b == 2), None)
if blend_mat is None:
    mats.append((0x10, 2)); blend_mat = len(mats) - 1

skins = [bytearray(m.read(low[f'character\\{race}\\female\\{race}female0{li}.skin'.lower()])) for li in range(4)]
plans = []
for li, s in enumerate(skins):
    h = list(struct.unpack_from('<4s11I', s, 0))
    idx = list(struct.unpack_from('<%dH' % h[1], s, h[2])); tri = list(struct.unpack_from('<%dH' % h[3], s, h[4]))
    props = [bytes(s[h[6] + 4 * i: h[6] + 4 * i + 4]) for i in range(h[5])]
    subs = [bytes(s[h[8] + 48 * i: h[8] + 48 * i + 48]) for i in range(h[7])]
    bats = [bytes(s[h[10] + 24 * i: h[10] + 24 * i + 24]) for i in range(h[9])]
    picked = []
    for si, sub in enumerate(subs):
        sid, lvl, vs, vc, ts, tc = struct.unpack_from('<6H', sub, 0)
        if sid not in GEOSETS:
            continue
        body = [b for b in bats if struct.unpack_from('<H', b, 4)[0] == si and texs[lookup[struct.unpack_from('<H', b, 16)[0]]][0] == 1]
        if not body:
            continue
        st = ts + (lvl << 16)
        for k in range(st, st + tc, 3):
            ents = [tri[k + j] for j in range(3)]
            pp = [unp[idx[e]][0:3] for e in ents]
            if all(p[2] >= fr['zj'] - ZLO and p[2] <= fr['zj'] + ZTOP for p in pp) and all(p[0] >= XMIN for p in pp) and all(abs(p[1]) <= YMAX for p in pp):
                picked.append((si, ents, body[0]))
    plans.append((h, idx, tri, props, subs, bats, picked))

allv = sorted({idx[e] for (_, idx, _, _, _, _, picked) in plans for _, ents, _ in picked for e in ents})
Y = np.array([unp[v][1] for v in allv]); S = s_of_z(fr, [unp[v][2] for v in allv])
y0, y1, s0, s1 = float(Y.min()) - 0.002, float(Y.max()) + 0.002, float(S.min()) - 0.002, float(S.max()) + 0.002
RX, RY, RW, RH = rx / CW, ry / CH, rw / CW, rh / CH
print(race, 'picked', [len(p[6]) for p in plans], 'vertices', len(allv), 'y %.1f..%.1f mm  s %.1f..%.1f mm' % (y0 * 1e3, y1 * 1e3, s0 * 1e3, s1 * 1e3),
      'px %.2f x %.2f mm' % ((y1 - y0) * 1e3 / rw, (s1 - s0) * 1e3 / rh))
pieces = json.load(open('decal/pieces.json')) if os.path.exists('decal/pieces.json') else {}
pieces[race] = dict(y0=y0, y1=y1, s0=s0, s1=s1)
json.dump(pieces, open('decal/pieces.json', 'w'))

new_index, new_verts = {}, []
for (h, idx, tri, props, subs, bats, picked) in plans:
    for si, ents, _ in picked:
        for e in ents:
            for sh, (lift, su, sv) in enumerate(SHELLS):
                key = (idx[e], sh)
                if key in new_index:
                    continue
                new_index[key] = nv + len(new_verts)
                v = list(unp[idx[e]])
                pos, nor = v[0:3], v[11:14]
                u = (v[1] - y0) / (y1 - y0); w = (s1 - float(s_of_z(fr, v[2]))) / (s1 - s0)
                v[0:3] = [pos[j] + nor[j] * lift for j in range(3)]
                v[14:16] = [RX + (u + su) * RW, RY + (w + sv) * RH]
                new_verts.append(struct.pack('<3f4B4B3f2f2f', *v))
if nv + len(new_verts) > 65535:
    sys.exit('too many vertices')

struct.pack_into('<II', d, 0x3C, nv + len(new_verts), append(b''.join(verts + new_verts)))
hair_lookup = next(i for i, t in enumerate(lookup) if texs[t][0] == 6)
struct.pack_into('<II', d, 0x80, len(lookup), append(struct.pack('<%dH' % len(lookup), *lookup)))
struct.pack_into('<II', d, 0x70, len(mats), append(b''.join(struct.pack('<HH', *mm) for mm in mats)))
open(f'decal/{race}FemaleShells.m2', 'wb').write(d)

for li, (h, idx, tri, props, subs, bats, picked) in enumerate(plans):
    s = skins[li]
    def sappend(blob):
        while len(s) % 16: s.append(0)
        at = len(s); s.extend(blob); return at
    by_section = {}
    for si, ents, batch in picked:
        by_section.setdefault(si, (batch, []))[1].append(ents)
    idx2, tri2, props2, subs2, bats2 = list(idx), list(tri), list(props), list(subs), list(bats)
    for si, (batch, faces) in by_section.items():
        entry_of = {}
        vstart, tstart = len(idx2), len(tri2)
        for sh in range(len(SHELLS)):
            for ents in faces:
                for e in ents:
                    key = (idx[e], sh)
                    if key not in entry_of:
                        entry_of[key] = len(idx2); idx2.append(new_index[key]); props2.append(props[e])
                tri2.extend(entry_of[(idx[e], sh)] for e in ents)
        sub = bytearray(subs[si])
        struct.pack_into('<6H', sub, 0, 901, tstart >> 16, vstart, len(idx2) - vstart, tstart & 0xFFFF, len(tri2) - tstart)
        subs2.append(bytes(sub))
        b = bytearray(batch)
        new_section = len(subs2) - 1
        struct.pack_into('<HH', b, 4, new_section, new_section)
        struct.pack_into('<H', b, 10, blend_mat)
        struct.pack_into('<H', b, 14, 1)
        struct.pack_into('<H', b, 16, hair_lookup)
        bats2.append(bytes(b))
    h[1], h[2] = len(idx2), sappend(struct.pack('<%dH' % len(idx2), *idx2))
    h[3], h[4] = len(tri2), sappend(struct.pack('<%dH' % len(tri2), *tri2))
    h[5], h[6] = len(props2), sappend(b''.join(props2))
    h[7], h[8] = len(subs2), sappend(b''.join(subs2))
    h[9], h[10] = len(bats2), sappend(b''.join(bats2))
    struct.pack_into('<4s11I', s, 0, *h)
    open(f'decal/{race}FemaleShells0{li}.skin', 'wb').write(s)
print('written')
