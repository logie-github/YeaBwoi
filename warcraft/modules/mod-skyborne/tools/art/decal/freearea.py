# freearea.py RACE [W H]: which texels of the race's hair texture no geoset that uses it draws (every triangle of every
# batch with the character-hair texture type, from the female and male models), and the largest free rectangles.
import sys, struct, numpy as np
sys.path.insert(0, 'tools'); import mpq
from PIL import Image, ImageDraw
race = sys.argv[1]; W = int(sys.argv[2]) if len(sys.argv) > 2 else 512; H = int(sys.argv[3]) if len(sys.argv) > 3 else 256
m = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in m.listfile()}
mask = Image.new('L', (W, H), 0); dr = ImageDraw.Draw(mask)
tris = 0
for sex in ('female',):
    k = f'character\\{race}\\{sex}\\{race}{sex}.m2'.lower()
    if k not in low: continue
    d = m.read(low[k]); nv, ov = struct.unpack_from('<II', d, 0x3C)
    V = [struct.unpack_from('<3f4B4B3f2f2f', d, ov + 48 * i) for i in range(nv)]
    nt, ot = struct.unpack_from('<II', d, 0x50); texs = [struct.unpack_from('<IIII', d, ot + 16 * i) for i in range(nt)]
    nl, ol = struct.unpack_from('<II', d, 0x80); lookup = struct.unpack_from('<%dH' % nl, d, ol)
    for li in range(4):
        sk = f'character\\{race}\\{sex}\\{race}{sex}0{li}.skin'.lower()
        if sk not in low: continue
        s = m.read(low[sk]); h = struct.unpack_from('<4s11I', s, 0)
        idx = struct.unpack_from('<%dH' % h[1], s, h[2]); tri = struct.unpack_from('<%dH' % h[3], s, h[4])
        subs = [struct.unpack_from('<6H', s, h[8] + 48 * i) for i in range(h[7])]
        for i in range(h[9]):
            b = struct.unpack_from('<BbHHHhHHHHHHH', s, h[10] + 24 * i)
            if texs[lookup[b[9]]][0] != 6: continue
            sid, lvl, vs, vc, ts, tc = subs[b[3]]; st = ts + (lvl << 16)
            for t in range(st, st + tc, 3):
                pts = [(V[idx[tri[t + j]]][14] * W, V[idx[tri[t + j]]][15] * H) for j in range(3)]
                dr.polygon(pts, fill=255); tris += 1
cov = np.asarray(mask) > 0
from scipy.ndimage import binary_dilation
cov = binary_dilation(cov, iterations=2)
print(race, 'hair triangles', tris, 'texels used %.1f%%' % (100 * cov.mean()))
free = ~cov
cs = np.cumsum(np.cumsum(free, 0), 1)
def s(y0, x0, y1, x1): return int(cs[y1-1, x1-1] - (cs[y0-1, x1-1] if y0 else 0) - (cs[y1-1, x0-1] if x0 else 0) + (cs[y0-1, x0-1] if y0 and x0 else 0))
best = []
for h in range(4, H + 1, 2):
    for w in range(4, W + 1, 2):
        if h * w < 600: continue
        found = None
        for y0 in range(0, H - h + 1, 2):
            for x0 in range(0, W - w + 1, 2):
                if s(y0, x0, y0 + h, x0 + w) == h * w: found = (x0, y0, w, h); break
            if found: break
        if found: best.append((h * w, found))
best.sort(reverse=True)
seen = []
for a, r in best:
    if all(not (r[0] < q[0] + q[2] and q[0] < r[0] + r[2] and r[1] < q[1] + q[3] and q[1] < r[1] + r[3]) for q in seen):
        seen.append(r); print('  free rectangle x,y,w,h', r, 'area', a)
    if len(seen) >= 4: break
Image.fromarray(np.where(cov, 40, 255).astype(np.uint8)).save(f'r3d/free_{race}.png')
