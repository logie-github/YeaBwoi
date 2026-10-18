import sys, json
import numpy as np
sys.path.insert(0, 'decal')
from frame import model, s_of_z

def build(race, u0, u1, v0, v1, geosets=(1301,), step=0.25):
    fr = json.load(open('decal/frames.json'))[race]
    m, P, U = model(race)
    us = np.arange(u0, u1 + 1e-9, step); vs = np.arange(v0, v1 + 1e-9, step)
    Y = np.full((len(vs), len(us)), np.nan); S = np.full_like(Y, np.nan); Z = np.full_like(Y, np.nan)
    for b in m['batches']:
        if b['texType'] != 1 or b['geoset'] not in geosets:
            continue
        ind = np.array(b['ind']).reshape(-1, 3)
        for tri in ind:
            uv = U[tri]; p = P[tri]
            if uv[:, 0].max() < u0 or uv[:, 0].min() > u1 or uv[:, 1].max() < v0 or uv[:, 1].min() > v1 or p[:, 0].mean() < 0:
                continue
            ia = np.where((us >= uv[:, 0].min() - 1e-6) & (us <= uv[:, 0].max() + 1e-6))[0]
            ja = np.where((vs >= uv[:, 1].min() - 1e-6) & (vs <= uv[:, 1].max() + 1e-6))[0]
            if len(ia) == 0 or len(ja) == 0:
                continue
            gu, gv = np.meshgrid(us[ia], vs[ja])
            (x0, y0), (x1, y1), (x2, y2) = uv
            den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if abs(den) < 1e-9:
                continue
            l0 = ((y1 - y2) * (gu - x2) + (x2 - x1) * (gv - y2)) / den
            l1 = ((y2 - y0) * (gu - x2) + (x0 - x2) * (gv - y2)) / den
            l2 = 1 - l0 - l1
            ok = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
            if not ok.any():
                continue
            yy = l0 * p[0, 1] + l1 * p[1, 1] + l2 * p[2, 1]
            zz = l0 * p[0, 2] + l1 * p[1, 2] + l2 * p[2, 2]
            sub = (slice(ja[0], ja[-1] + 1), slice(ia[0], ia[-1] + 1))
            Y[sub] = np.where(ok, np.abs(yy), Y[sub]); Z[sub] = np.where(ok, zz, Z[sub])
    S = s_of_z(fr, np.where(np.isnan(Z), 0, Z)); S = np.where(np.isnan(Z), np.nan, S)
    return us, vs, Y, S, Z

if __name__ == '__main__':
    race = sys.argv[1]; u0, u1, v0, v1 = map(float, sys.argv[2:6])
    gs = tuple(int(g) for g in sys.argv[6:]) or (1301,)
    us, vs, Y, S, Z = build(race, u0, u1, v0, v1, gs)
    np.savez(f'decal/uvmap_{race}.npz', us=us, vs=vs, Y=Y, S=S, Z=Z)
    print(race, 'covered %.0f%%' % (100 * np.isfinite(Y).mean()), 'y mm', np.nanmin(Y) * 1000, np.nanmax(Y) * 1000, 's mm', np.nanmin(S) * 1000, np.nanmax(S) * 1000)
    for v in (226, 232, 240, 246, 250, 258, 266):
        j = int((v - v0) / 0.25)
        row = [(round(u), round(float(Y[j, int((u - u0) / 0.25)]) * 1000, 1), round(float(S[j, int((u - u0) / 0.25)]) * 1000, 1)) for u in (258, 262, 266, 272, 280, 290, 300, 316) if u <= u1 and np.isfinite(Y[j, int((u - u0) / 0.25)])]
        print(' v', v, '(u, |y| mm, s mm):', row)
