# frame.py [RACE...]: each race's pelvis frame, from its model (r3d/model_RACE.json): zj, the height where the
# front surface meets the thigh gap (the lowest midline vertex at least 2 cm forward), and the front midline above it
# as (z, s) pairs, s the distance along that curve from the junction. Written to decal/frames.json.
import sys, json, os
import numpy as np

OUT = 'decal/frames.json'

def model(race):
    m = json.load(open(f'r3d/model_{race}.json'))
    return m, np.array(m['pos']).reshape(-1, 3), np.array(m['uv']).reshape(-1, 2) * 512

def midline(race):
    m, P, U = model(race)
    used = sorted({i for b in m['batches'] if b['texType'] == 1 for i in b['ind']})
    p, u = P[used], U[used]
    H = float(P[:, 2].max() - P[:, 2].min()); zmin = float(P[:, 2].min())
    sel = (np.abs(p[:, 1]) < 0.0055 * H / 2.05) & (u[:, 0] > 250) & (u[:, 0] < 300) & (u[:, 1] > 190) & (u[:, 1] < 330) \
        & (p[:, 2] < zmin + 0.65 * H) & (p[:, 0] > -0.03 * H / 2.05)
    return H, p[sel], u[sel]

def visible_junction(race, res=0.0005):
    """The height where the body first covers the centre line seen from the front (looking along x), going up from the feet:
    the top of the gap between the thighs, i.e. the V you see."""
    from PIL import Image, ImageDraw
    m, P, U = model(race)
    z0, z1 = float(P[:, 2].min()), float(P[:, 2].max())
    W, Hh = int(0.4 / res), int((z1 - z0) / res)
    img = Image.new('L', (W, Hh), 0); dr = ImageDraw.Draw(img)
    for b in m['batches']:
        if b['texType'] != 1:
            continue
        for tri in np.array(b['ind']).reshape(-1, 3):
            p = P[tri]
            if p[:, 0].mean() < -0.02:
                continue
            dr.polygon([((q[1] + 0.2) / res, (z1 - q[2]) / res) for q in p], fill=255)
    a = np.asarray(img) > 0
    col = a[:, W // 2 - 1:W // 2 + 2].any(1)            # the centre line, 1 mm wide
    for r in range(Hh - 1 - int(0.15 / res), 0, -1):      # from 15 cm above the feet upwards
        if col[r]:
            return float(z1 - r * res)
    return None

def build(race, xmin=0.02, zj=None):
    H, q, w = midline(race)
    if zj is None:
        zj = json.load(open('decal/race_cfg.json')).get(race, {}).get('zj')      # a junction confirmed by eye, per race
    if zj is None:
        zj = float(q[q[:, 0] >= xmin][:, 2].min())
    top = q[q[:, 2] >= zj - 1e-4]
    top = top[np.argsort(top[:, 2])]
    zs, xs = [], []
    for z, x in zip(top[:, 2], top[:, 0]):               # one point per 2 mm of height, the most forward
        if zs and z - zs[-1] < 0.002:
            xs[-1] = max(xs[-1], x); continue
        zs.append(float(z)); xs.append(float(x))
    arcs = [0.0]
    for i in range(1, len(zs)):
        arcs.append(arcs[-1] + float(np.hypot(zs[i] - zs[i - 1], xs[i] - xs[i - 1])))
    return dict(H=H, zj=zj, z=zs, x=xs, s=arcs)

def s_of_z(fr, z):
    z = np.asarray(z, float)
    above = np.interp(z, fr['z'], fr['s'])
    return np.where(z >= fr['zj'], above, z - fr['zj'])

def z_of_s(fr, s):
    s = np.asarray(s, float)
    above = np.interp(s, fr['s'], fr['z'])
    return np.where(s >= 0, above, s + fr['zj'])

if __name__ == '__main__':
    fr = json.load(open(OUT)) if os.path.exists(OUT) else {}
    for race in sys.argv[1:]:
        fr[race] = build(race)
        g = fr[race]
        print(race, 'H %.3f junction z %.3f' % (g['H'], g['zj']), 'curve', [(round(z, 3), round(x, 3), round(s, 3)) for z, x, s in zip(g['z'], g['x'], g['s'])])
    json.dump(fr, open(OUT, 'w'))
