#!/usr/bin/env python3
"""The female body model of each race, read from the HD archive (Patch-H.MPQ in the working folder), as the
JSON the other tools in tools/art read (r3d/model_<Race>.json): positions, UVs, and the index lists of the
body batches (the skin texture's and the geoset-0 / group-1 pieces).

    python3 races/model_json.py BloodElf Human ...
"""
import sys, struct, json, os
sys.path.insert(0, 'tools'); import mpq

m = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in m.listfile()}
os.makedirs('r3d', exist_ok=True)
for race in sys.argv[1:]:
    d = m.read(low[f'character\\{race}\\female\\{race}female.m2'.lower()])
    s = m.read(low[f'character\\{race}\\female\\{race}female00.skin'.lower()])
    nv, ov = struct.unpack_from('<II', d, 0x3C)
    V = [struct.unpack_from('<3f4B4B3f2f2f', d, ov + 48 * i) for i in range(nv)]
    h = struct.unpack_from('<4s10I', s, 0)
    idx = struct.unpack_from('<%dH' % h[1], s, h[2]); tri = struct.unpack_from('<%dH' % h[3], s, h[4])
    nt, ot = struct.unpack_from('<II', d, 0x50); texs = [struct.unpack_from('<IIII', d, ot + 16 * i) for i in range(nt)]
    nl, ol = struct.unpack_from('<II', d, 0x80); lookup = struct.unpack_from('<%dH' % nl, d, ol)
    subs = [struct.unpack_from('<6H', s, h[8] + 48 * i) for i in range(h[7])]
    out = dict(pos=[c for v in V for c in v[0:3]], nor=[c for v in V for c in v[11:14]], uv=[c for v in V for c in v[14:16]], batches=[])
    for i in range(h[9]):
        b = struct.unpack_from('<BBHHHHHHHHHHH', s, h[10] + 24 * i)
        sid, lvl, vs, vc, ts, tc = subs[b[3]]; tt = texs[lookup[b[9]]][0]
        keep = sid == 0 or (sid >= 100 and sid % 100 == 1 and sid // 100 not in (15, 17))
        if keep and tt in (1, 8):
            st = ts + (lvl << 16)
            out['batches'].append(dict(geoset=sid, texType=tt, blend=0, ind=[idx[tri[st + k]] for k in range(tc)]))
    json.dump(out, open(f'r3d/model_{race}.json', 'w'))
    print(race, 'batches', len(out['batches']), 'vertices', nv)
