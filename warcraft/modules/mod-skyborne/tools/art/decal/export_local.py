import sys, struct, json
m2, sk, out, piece = sys.argv[1:5]
d = open(m2, 'rb').read(); s = open(sk, 'rb').read()
nv, ov = struct.unpack_from('<II', d, 0x3C)
V = [struct.unpack_from('<3f4B4B3f2f2f', d, ov + 48 * i) for i in range(nv)]
nt, ot = struct.unpack_from('<II', d, 0x50); texs = [struct.unpack_from('<IIII', d, ot + 16 * i) for i in range(nt)]
nl, ol = struct.unpack_from('<II', d, 0x80); lookup = struct.unpack_from('<%dH' % nl, d, ol)
nm, om = struct.unpack_from('<II', d, 0x70); mats = [struct.unpack_from('<HH', d, om + 4 * i) for i in range(nm)]
h = struct.unpack_from('<4s11I', s, 0)
idx = struct.unpack_from('<%dH' % h[1], s, h[2]); tri = struct.unpack_from('<%dH' % h[3], s, h[4])
subs = [struct.unpack_from('<6H', s, h[8] + 48 * i) for i in range(h[7])]
o = dict(pos=[c for v in V for c in v[0:3]], nor=[c for v in V for c in v[11:14]], uv=[c for v in V for c in v[14:16]], batches=[])
for i in range(h[9]):
    b = struct.unpack_from('<BbHHHhHHHHHHH', s, h[10] + 24 * i)
    sid, lvl, vs, vc, ts, tc = subs[b[3]]
    t = texs[lookup[b[9]]]
    keep = sid == 0 or sid in (401, 501, 702, 901, 1301, 1801, 2001)
    if not keep or t[0] not in (0, 1, 6, 8):
        continue
    name = d[t[3]:t[3] + t[2]].rstrip(b'\0').decode() if t[0] == 0 else ''
    if t[0] == 0 and not name.lower().endswith('piece.blp'):
        continue
    if t[0] == 6 and sid != 901:
        continue
    st = ts + (lvl << 16)
    o['batches'].append(dict(geoset=sid, texType=t[0], blend=mats[b[6]][1], tex=piece if t[0] in (0, 6) else None,
                             ind=[idx[tri[st + k]] for k in range(tc)]))
json.dump(o, open(out, 'w'))
print('batches', [(b['geoset'], b['texType'], b['blend'], len(b['ind']) // 3) for b in o['batches']])
