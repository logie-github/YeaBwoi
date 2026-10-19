import sys, os, json, zlib, struct, numpy as np
from scipy.ndimage import distance_transform_edt
sys.path.insert(0, 'tools'); import mpq

out_dir = sys.argv[1]
races = sys.argv[2:] or ['BloodElf', 'Human', 'Dwarf', 'NightElf', 'Gnome', 'Draenei', 'Orc', 'Scourge', 'Tauren', 'Troll', 'Goblin']
frames = json.load(open('decal/frames.json')); cfgs = json.load(open('decal/race_cfg.json'))
game = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in game.listfile()}
os.makedirs(out_dir, exist_ok=True)

ROW_S = [[226, 139.4], [232, 106.2], [240, 53.7], [246, 4.2], [258, -8.8], [266, -41.4]]


def crc(path):
    return zlib.crc32(game.read(low[path.lower()])) & 0xffffffff


def flat(v):
    return ' '.join(repr(float(x)) for x in np.array(v, float).ravel())


def export(race, text):
    c = cfgs[race]
    sH = frames[race]['H'] / frames['BloodElf']['H']
    sH = c.get('lines_scale', c.get('hair_scale', sH))
    mp = np.load(f'decal/uvmap_{race}.npz'); us, vs, Y, S, Zm = mp['us'], mp['vs'], mp['Y'], mp['S'], mp['Z']
    yy, xx = np.mgrid[0:512, 0:512]
    u = xx + 0.5; v = yy + 0.5
    iu = np.clip(np.round((u - us[0]) / 0.25).astype(int), 0, len(us) - 1); iv = np.clip(np.round((v - vs[0]) / 0.25).astype(int), 0, len(vs) - 1)
    inside = (u >= us[0]) & (u <= us[-1]) & (v >= vs[0]) & (v <= vs[-1])
    ay = np.where(inside, Y[iv, iu], np.nan) * 1000
    s = np.where(inside, S[iv, iu], np.nan) * 1000
    zz = np.where(inside, Zm[iv, iu], np.nan) * 1000
    ok = np.isfinite(ay) & np.isfinite(s)
    if c.get('lines_fill', 0):
        dist, (ij, ii2) = distance_transform_edt(~ok, return_indices=True)
        near = (~ok) & inside & (dist <= c['lines_fill']) & (u >= c.get('lines_fill_u0', 0))
        ay = np.where(near, ay[ij, ii2], ay); s = np.where(near, s[ij, ii2], s); zz = np.where(near, zz[ij, ii2], zz)
        ok = np.isfinite(ay) & np.isfinite(s)
    keep = ok & (ay < 95.0)
    ys, xs = np.where(keep)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()) + 1, int(ys.min()), int(ys.max()) + 1
    box = (slice(y0, y1), slice(x0, x1))
    D = c.get('lines_detail')
    noise = None
    if D:
        ay0 = np.where(ok, ay, 0); yr = ay0
        q = np.where(ok, np.nan_to_num(zz) - D['z0'], -1e3)
        rq = np.random.default_rng(D.get('seed', 5))
        def nz(fy, fq, n=6):
            acc = 0.0
            for _ in range(n):
                a_ = rq.uniform(0, 2 * np.pi); f1 = fy * rq.uniform(0.6, 1.6); f2 = fq * rq.uniform(0.6, 1.6)
                acc = acc + np.sin(f1 * yr * np.cos(a_) + f2 * q * np.sin(a_) + rq.uniform(0, 6.28))
            return acc / np.sqrt(n / 2)
        noise = [nz(0.35, 0.12, 4), nz(0.25, 0.2, 4), nz(0.2, 0.3, 3), nz(0.15, 0.35, 5), nz(2.2, 2.0, 7)]
    h, w = y1 - y0, x1 - x0
    body = bytearray()
    body += b'SHDE' + struct.pack('<IIIIII', 1, x0, y0, w, h, 1 if noise else 0)
    for arr in (ay, s, zz):
        body += np.where(ok, arr, 0.0)[box].astype('<f4').tobytes()
    body += ok[box].astype('u1').tobytes()
    if noise:
        for a in noise:
            body += a[box].astype('<f4').tobytes()
    open(os.path.join(out_dir, race + '.bin'), 'wb').write(bytes(body))
    fem = f'character\\{race}\\female\\{race}female'
    text.append(f'[{race}]')
    text.append(f'fingerprint {crc(fem + ".m2")} {crc(fem + "00.skin")}')
    P = {'scale': [sH], 'shift': [c.get('lines_shift', 0.0)], 'cut': [c.get('lines_cut', 0.0)], 'width': [c.get('lines_width', 1.0)],
         'depth': [c.get('lines_depth', 0.95)], 'fold': [c.get('lines_fold', 0.10)], 'rim_at': [c.get('lines_rim_at', 3.0)],
         'rim_w': [c.get('lines_rim_w', 1.3)], 'rim_ramp': [c.get('lines_rim_ramp', 0.0)], 'rim': [c.get('lines_rim', 0.0)],
         'rim_col': c.get('lines_rim_col', [1.0, 1.0, 1.0]), 'lift': [c.get('lines_lift', 0.0)], 'lift_w': [c.get('lines_lift_w', 8.0)],
         'lift_top': [c.get('lines_lift_top', 100.0)], 'tone': c.get('lines_tone', [0.28, 0.40, 0.17])}
    if 'lines_ref' in c:
        P['ref'] = c['lines_ref']
        P['tone_k'] = np.array(c.get('lines_tone_k', [[0, 1.0], [85, 1.0]])).ravel()
        if 'lines_rim_k' in c:
            P['rim_k'] = [c['lines_rim_k']]
    if 'lines_core' in c:
        P['core'] = c['lines_core']; P['core_w'] = [c.get('lines_core_w', 3.3)]; P['core_k'] = [c.get('lines_core_k', 0.9)]
        P['slot_w'] = [c.get('lines_slot_w', 0.9)]; P['slot_k'] = [c.get('lines_slot_k', 0.8)]
    if D:
        defaults = {'ref_lo': [90.0], 'ref_hi': [170.0], 'skew': [0.8], 'wmin': [2.0], 'soft': [5.0], 'tint': [1.08, 0.80, 0.76], 'tint_k': [0.30],
                    'ridge_k': [0.13], 'edge_k': [0.16], 'gap_edge_k': [0.22], 'low_k': [0.08], 'gap_win': [0.06, 0.24, 0.74, 0.40], 'gap': [0.6],
                    'gap_open': [1.3], 'band_win': [0.07, 0.28, 0.78, 0.42], 'band': [2.8], 'ripple': [0.07], 'band_k': [0.88], 'band_hi': [0.45],
                    'gap_k': [0.9], 'cap_rx': [3.2], 'cap_ry': [4.0], 'cap_k': [0.5]}
        for k_, v_ in defaults.items():
            P['d_' + k_] = D.get(k_, v_) if isinstance(D.get(k_, v_), list) else [D.get(k_, v_)]
        for k_ in ('z0', 'height', 'width'):
            P['d_' + k_] = [D[k_]]
        for k_ in ('pink', 'hi', 'deep'):
            P['d_' + k_] = D[k_]
        P['d_cap_c'] = [D.get('cap_c', 0.93 * D['height'])]
    for name, vals in P.items():
        text.append(f'{name} {flat(vals)}')
    print(race, 'box', x0, y0, w, h, 'bytes', len(body))


text = ['# the shading of the body textures: per race, the texture box of fields (races/shade_export.py) and its settings']
for r in races:
    export(r, text)
open(os.path.join(out_dir, 'shade.txt'), 'w').write('\n'.join(text) + '\n')
