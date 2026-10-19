import sys, io, json, numpy as np
sys.path.insert(0, 'tools'); import mpq
from PIL import Image
from scipy.ndimage import gaussian_filter, map_coordinates

race, path, level, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
share = float(sys.argv[5]) if len(sys.argv) > 5 else 0.33
STYLE = {5: 'strip', 6: 'heart', 7: 'moon', 8: 'moon', 9: 'star', 10: 'swords', 11: 'skull'}.get(level)
PATCHY = level == 18
if PATCHY:
    level = 3
MOON_TURN = {7: -5.0, 8: 90.0}.get(level, 30.0)
if STYLE:
    level = 2
cfg = json.load(open('decal/race_cfg.json'))[race]
if __import__('os').environ.get('CFG_OVER'):
    cfg.update(json.loads(__import__('os').environ['CFG_OVER']))
opts = json.loads(sys.argv[6]) if len(sys.argv) > 6 else {}
if level == 2:
    opts = {**{"even": True, "half": cfg.get('lw_half', 30), "arch": 5.0, "len": [5.8, 6.0], "turn": [0.06, 0.10], "count": 1700,
               "width": [0.5, 0.55], "dir": "down", "ang_sd": 7, "alpha": [0.38, 0.42]}, **opts}
frames = json.load(open('decal/frames.json'))
pc = json.load(open('decal/pieces.json'))[race]
CW, CH = cfg['canvas']; RX, RY, RW, RH = cfg['region']
sH = frames[race]['H'] / frames['BloodElf']['H']
if 'hair_scale' in cfg:
    sH = cfg['hair_scale']
sH = cfg.get(f'hair_scale_{level}', sH)
if 'HAIR_SCALE' in __import__('os').environ:
    sH = float(__import__('os').environ['HAIR_SCALE'])
bm = np.load('decal/uvmap_BloodElf.npz')
from scipy.ndimage import distance_transform_edt
def filled(a):
    bad = np.isnan(a); idx = distance_transform_edt(bad, return_distances=False, return_indices=True)
    return a[tuple(idx)]
BY, BS = filled(bm['Y']), filled(bm['S'])
def be_phys(ub, vb):
    fu, fv = (ub - bm['us'][0]) / 0.25, (vb - bm['vs'][0]) / 0.25
    ay, s = [float(map_coordinates(g, [[fv], [fu]], order=1)[0]) * 1000 for g in (BY, BS)]
    if ub < 258.0:
        ay += (ub - 258.0) * 1.6
    return [ay, s]

m = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in m.listfile()}
src = Image.open(io.BytesIO(m.read(low[path.lower()]))).convert('RGBA')
tex = np.asarray(src.resize((CW, CH), Image.BICUBIC)).astype(float) / 255
hair = np.asarray(src).astype(float)[:, cfg.get('hair_c0', 0):cfg['hair_cols'], :3].reshape(-1, 3) / 255
lum_all = hair @ [0.299, 0.587, 0.114]
if np.median(lum_all) < 0.10:
    gold = (hair[:, 0] > 0.45) & (hair[:, 0] > 2.0 * hair[:, 2]) & (hair[:, 1] > 0.25)
    hair = hair[~gold]
order = np.argsort(hair @ [0.299, 0.587, 0.114])
dark, mid, light = (hair[order[int(len(order) * q)]] for q in (0.15, 0.5, 0.85))
LUM = np.array([0.299, 0.587, 0.114])
pal = hair[order][int(len(order) * 0.06):int(len(order) * 0.997)]
lum_pal = pal @ LUM
crng = np.random.default_rng(7 + level)
rng = np.random.default_rng(41 + level)

X0, YT, YS, YB = 257.0, 229.3, 240.0, 266.8
LEVELS = {1: (40.0, 1.0, 0.5, 1.2, 0.0, 0.0), 2: (40.0, 1.0, 0.4, 1.2, 0.0, 0.0),
        3: (43.0, 3.0, 0.85, 0.4, 0.0, 0.0), 4: (46.0, 6.0, 1.0, 0.4, 10.0, 0.06)}
halfmax, reach, down_f, clear, spread, stray = LEVELS[level]
if level == 4:
    stray = cfg.get('l4_stray', stray)
if level == 2 and 'half' in opts:
    halfmax = opts['half']
top = YT + 2 - reach
low_end = YS + down_f * (YB - YS)

NOISE = np.random.default_rng(900 + level).uniform(0, 6.28, 4)
def depth_parts(ub, vb, sg=1.0):
    ub, vb = np.asarray(ub, float), np.asarray(vb, float)
    upper_part = vb <= YS
    f = np.clip((vb - top) / (YS - top), 0, 1)
    half = np.where(upper_part, halfmax * (1 - 0.7 * f ** 0.9) + 3.0,
                    (halfmax * 0.2 + 2.0) * (1 - 0.6 * np.clip((vb - YS) / max(1e-3, low_end - YS), 0, 1)) + 1.0)
    if cfg.get('triangle') and level in (1, 2, 3):
        VJ = 246.2
        half = halfmax * np.clip((VJ - vb) / (VJ - top), 0, 1) ** cfg.get('fan', 1.0) + 2.0
    inner = np.where(upper_part, 10.0, (ub - X0 - clear) / 0.8)
    side = (1 - (ub - X0) / half) * 2.0
    rel_u = (ub - X0) / (halfmax + 3)
    if level in (1, 2, 3):
        u = sg * (ub - X0)
        wave = 1.5 * np.sin(0.31 * u + NOISE[0]) + 1.0 * np.sin(0.83 * u + NOISE[1]) + 0.6 * np.sin(1.9 * u + NOISE[2])
        arch = opts.get('arch', 6.0) if level == 2 else (7.0 if level == 3 else 6.0)
        if level == 2 and opts.get('even'):
            wave = 0.0
        upper = (vb - top - arch * rel_u ** 2 - wave) / 2.5
    else:
        u4 = sg * (ub - X0)
        wave4 = 1.5 * np.sin(0.31 * u4 + NOISE[0]) + 1.0 * np.sin(0.83 * u4 + NOISE[1]) + 0.6 * np.sin(1.9 * u4 + NOISE[2])
        upper = (vb - top - cfg.get('l4_arch', 0.3) * rel_u ** 2 - cfg.get('l4_wave', 0.0) * wave4) / 2.5
    lower = (low_end - vb) / 2.0
    return side, upper, lower, inner * np.ones_like(side), vb

def depth(ub, vb, sg=1.0):
    ub, vb = np.asarray(ub, float), np.asarray(vb, float)
    side, upper, lower, inner, vb = depth_parts(ub, vb, sg)
    d = np.minimum.reduce([side, upper, lower, inner])
    if spread > 0:
        f2 = np.clip((vb - (YS - 6)) / (YB - YS + 6), 0, 1)
        flank = np.minimum.reduce([(spread * (0.4 + 0.6 * f2) - (ub - X0 - 4)) / 6.0, (vb - (YS - 6)) / 4.0, (YB - 2 - vb) / 3.0])
        d = np.where(vb > YS - 6, np.maximum(d, flank * 0.5), d)
    return d

TOPH = be_phys(X0 + 3.0, top)[1]
R = 0.25
XW, HTOP, HBOT = 130.0, 190.0, -70.0
CWW, CHH = int(2 * XW / R), int((HTOP - HBOT) / R)
def to_canvas(X, h): return (np.asarray(X) + XW) / R, (HTOP - np.asarray(h)) / R

SV = cfg.get(f'hair_scale_y_{level}', cfg.get('hair_scale_y', sH))
CRE = None
if cfg.get('lines'):
    cj = json.load(open('decal/lines.json'))[race]
    CRE = (np.array(cj['s']) * 1000, np.array(cj['half']) * 1000)
CM = {1: 2.0, 2: 2.0, 3: 2.0, 4: -12.0}[level]
ROWMM = 6.7
def half_lim(h_be):
    return np.interp(np.asarray(h_be, float) * SV, CRE[0], CRE[1]) / sH - CM / sH

stamps = []
def stroke(X, Y, width_mm, color, alpha):
    cx, cy = to_canvas(X, Y)
    rank = np.searchsorted(lum_pal, float(color @ LUM)) / len(pal)
    alpha = min(0.85, alpha * (0.85 + 0.6 * rank))
    if len(cx) > 3:
        tt = np.linspace(0, 1, len(cx))[:, None]
        cols = color * (1 - tt) + tip_of(color) * tt
    else:
        cols = np.tile(color, (len(cx), 1))
    stamps.append((cx, cy, np.full(len(cx), width_mm / 2 / R), cols, np.full(len(cx), alpha)))

def depth_w(X, h, sg=1.0):
    X, h = np.asarray(X, float), np.asarray(h, float)
    half = np.maximum(half_lim(h), 2.0)
    side = (1 - np.abs(X) / half) * 2.0 * cfg.get('edge_sharp', 1.0)
    half_top = max(float(half_lim(TOPH)), 2.0)
    rel = np.clip(np.abs(X) / half_top, 0, 1.2)
    if level in (1, 2, 3):
        u = sg * np.abs(X) / 1.63
        wave = 1.5 * np.sin(0.31 * u + NOISE[0]) + 1.0 * np.sin(0.83 * u + NOISE[1]) + 0.6 * np.sin(1.9 * u + NOISE[2])
        arch = (opts.get('arch', 6.0) if level == 2 else (7.0 if level == 3 else 6.0)) * cfg.get('arch_scale', 1.0)
        if level == 2 and opts.get('even'):
            wave = 0.0
        top_edge = TOPH - (arch * rel ** 2 + wave) * ROWMM
    else:
        top_edge = TOPH - 0.3 * rel ** 2 * ROWMM
    return side, (top_edge - h) / (2.5 * ROWMM), (h - 2.0) / (2.0 * ROWMM)

def place_world(count, keep_fn):
    placed = 0
    half_top = max(float(half_lim(TOPH)), 2.0)
    for _ in range(count * 8):
        if placed >= count:
            break
        side = rng.choice([-1, 1])
        h = rng.uniform(2.0, TOPH + 6 * ROWMM)
        half = max(float(half_lim(h)), 2.0)
        if level == 2 and opts.get('even'):
            Xa = rng.uniform(-2.0, half * 1.02)
        else:
            Xa = abs(rng.normal(0, cfg.get('xsigma', 0.6) * half_top)) - 2.0
        sd, up, lo = depth_w(Xa, h, side)
        dpt = float(min(sd, up, lo))
        if rng.random() > keep_fn(dpt):
            continue
        placed += 1
        yield (Xa, h, min(1.0, max(0.0, Xa) / half_top)), None, side, dpt

def start(ub, vb, side):
    if vb is None:
        return side * ub[0], ub[1]
    ay, h = be_phys(ub, vb)
    return side * ay, h

def place(count, keep_fn):
    if CRE is not None:
        yield from place_world(count, keep_fn)
        return
    placed = 0
    for _ in range(count * 8):
        if placed >= count:
            break
        side = rng.choice([-1, 1])
        ub = X0 - 1.5 + (rng.uniform(0, (halfmax + 3.0) * 1.05) if (level == 2 and opts.get('even')) else abs(rng.normal(0, (halfmax + spread * 0.5) * 0.6)))
        vb = rng.uniform(top - (6 if level in (1, 2, 3) else 3), min(low_end + (6 if spread else 0), YB - 1))
        dpt = float(depth(ub, vb, side))
        if rng.random() > keep_fn(dpt):
            continue
        placed += 1
        yield ub, vb, side, dpt

STEP = 0.465
def coil(ub, vb, side, L, turn_lo, turn_hi, mode='fan'):
    X, h = start(ub, vb, side)
    rel = ub[2] if vb is None else max(0.0, ub - X0) / (halfmax + 3)
    ang_sd = opts.get('ang_sd', 25 if mode == 'fan' else 14)
    base = {'fan': 60 + 70 * rel, 'down': 90 + 25 * rel, 'flat': 50 + 45 * rel}[mode]
    ang = np.radians(base + rng.normal(0, ang_sd))
    if level >= 3:
        L = L * (0.75 + 0.55 * min(1.0, rel))
        turn_scale = 1.25 - 0.55 * min(1.0, rel)
    else:
        turn_scale = 1.0
    turn = rng.choice([-1, 1]) * rng.uniform(turn_lo, turn_hi) * turn_scale
    kink = rng.uniform(0.0, 0.25)
    n = int(L * 2)
    i = np.arange(n)
    a = ang + i * 0.5 * turn * (1 + kink * np.sin(i * 1.3))
    xs = X + np.concatenate([[0], np.cumsum(side * STEP * np.cos(a))])
    ys = h - np.concatenate([[0], np.cumsum(STEP * np.sin(a))])
    return xs, ys

def shade(lo=0.08, hi=0.56, sprinkle=0.36):
    q = crng.uniform(0.68, 0.998) if crng.random() < sprinkle else crng.uniform(lo, hi)
    return np.clip(pal[int(q * (len(pal) - 1))] * crng.uniform(0.93, 1.08), 0, 1)
def tip_of(color):
    q = np.searchsorted(lum_pal, float(color @ LUM)) / len(pal)
    q = min(0.998, q + crng.uniform(0.08, 0.40))
    return np.clip(pal[int(q * (len(pal) - 1))] * crng.uniform(0.95, 1.08), 0, 1)

from scipy.spatial import cKDTree
ok = np.isfinite(bm['Y']) & np.isfinite(bm['S'])
jj, ii = np.where(ok)
tree = cKDTree(np.stack([bm['Y'][jj, ii] * 1000, bm['S'][jj, ii] * 1000], 1))
tex_u, tex_v = bm['us'][ii], bm['vs'][jj]

if STYLE:
    from scipy.ndimage import distance_transform_edt as edt
    ax, ah = np.meshgrid(np.arange(0.0, 100.0, 1.0), np.arange(0.0, 170.0, 1.0))
    _, kk = tree.query(np.stack([ax.ravel(), ah.ravel()], 1))
    sd_, up_, lo_, in_, _ = depth_parts(tex_u[kk], tex_v[kk], 1.0)
    A0 = 2.0 * float(((np.minimum.reduce([sd_, lo_, in_]) > 0) & (up_ > 0)).sum())
    GS = 0.5
    GXm, GHm = np.meshgrid(np.arange(-110.0, 110.0, GS), np.arange(0.0, 170.0, GS))
    GXa = np.abs(GXm)
    hb = cfg.get('style_bottom', 22.0)
    Hs = cfg.get('style_h', 0.8) * (TOPH - hb)
    ht_s = hb + cfg.get('strip_h', 0.92) * (TOPH - hb)
    Hst = ht_s - hb
    if STYLE == 'strip':
        ws = cfg.get('strip_half', 15.5)
        ins = (GXa <= ws) & (GHm <= ht_s) & (GHm >= hb - 12)
    elif STYLE == 'heart':
        g = np.linspace(-1.6, 1.6, 1601); gx, gy = np.meshgrid(g, g)
        ih = (gx ** 2 + gy ** 2 - 1) ** 3 - gx ** 2 * gy ** 3 <= 0
        ymax, xmax = gy[ih].max(), gx[ih].max()
        Hh = cfg.get('heart_h', 40.0); Wh = cfg.get('heart_half', 19.0); hbot = cfg.get('heart_bottom', 62.0)
        hx = GXa / Wh * xmax; hy = -1.0 + (GHm - hbot) / Hh * (ymax + 1.0)
        ins = ((hx ** 2 + hy ** 2 - 1) ** 3 - hx ** 2 * hy ** 3 <= 0) & (GHm >= hbot)
    elif STYLE == 'swords':
        th = np.radians(cfg.get('swords_angle', 40.0)); ks = cfg.get('swords_scale', 1.0)
        bw, gw, gt = cfg.get('swords_blade', 4.8), cfg.get('swords_guard', 8.5), cfg.get('swords_guard_t', 4.5)
        gr, pr = cfg.get('swords_grip', 2.6), cfg.get('swords_pommel', 4.0)
        xc, bl = cfg.get('swords_cross', 16.0), cfg.get('swords_len', 38.0)
        def swords(hc_s):
            m_ = np.zeros(GXm.shape, bool)
            for sgn in (1.0, -1.0):
                e0, e1 = np.sin(th) * sgn, np.cos(th); q0, q1 = np.cos(th), -np.sin(th) * sgn
                dx, dh = GXm / ks, (GHm - hc_s) / ks
                a_ = dx * e0 + dh * e1 + xc; p_ = dx * q0 + dh * q1
                blade = (a_ >= 0) & (a_ <= bl) & (np.abs(p_) <= bw * np.clip(1 - a_ / bl, 0, 1) ** 0.6)
                guard = (a_ >= -3) & (a_ <= -3 + gt) & (np.abs(p_) <= gw)
                grip = (a_ >= -12) & (a_ <= -3) & (np.abs(p_) <= gr)
                pommel = np.hypot(a_ + 12.0 + pr * 0.45, p_) <= pr
                m_ |= blade | guard | grip | pommel
            return m_
        ins = swords(100.0)
        ins = swords(100.0 + cfg.get('swords_bottom', 62.0) - float(GHm[ins].min()))
    elif STYLE == 'skull':
        from PIL import Image as _I, ImageDraw as _D
        ks = cfg.get('skull_scale', 1.0); hs = cfg.get('skull_bottom', 62.0) + 24.5 * ks
        im_ = _I.new('L', (GXm.shape[1], GXm.shape[0]), 0); dr_ = _D.Draw(im_)
        to_g = lambda x, h: ((x * ks + 110.0) / GS, (hs + h * ks) / GS)
        def ell(cx_, ch_, ax_, ay_, fill):
            x0_, y0_ = to_g(cx_ - ax_, ch_ - ay_); x1_, y1_ = to_g(cx_ + ax_, ch_ + ay_)
            dr_.ellipse([x0_, y0_, x1_, y1_], fill=fill)
        def poly(pts, fill):
            dr_.polygon([to_g(x, h) for x, h in pts], fill=fill)
        th = np.radians(cfg.get('skull_bone_angle', 33.0)); BL, BW, KN, KO = 23.0, 2.3, 3.0, 2.4
        for sg in (1, -1):
            ux, uy = np.cos(th), sg * np.sin(th); nx, ny = -uy, ux
            a_ = (-BL * ux, -9.0 - BL * uy); b_ = (BL * ux, -9.0 + BL * uy)
            poly([(a_[0] + BW * nx, a_[1] + BW * ny), (b_[0] + BW * nx, b_[1] + BW * ny), (b_[0] - BW * nx, b_[1] - BW * ny), (a_[0] - BW * nx, a_[1] - BW * ny)], 255)
            for e_ in (a_, b_):
                for q in (1, -1):
                    ell(e_[0] + q * KO * nx, e_[1] + q * KO * ny, KN, KN, 255)
        ell(0, 3, 13.5, 13.5, 255); ell(0, -5, 11, 8, 255)
        poly([(-8.5, -8), (8.5, -8), (7.0, -17), (-7.0, -17)], 255)
        ell(-5.2, 0.5, 3.9, 3.7, 0); ell(5.2, 0.5, 3.9, 3.7, 0)
        poly([(0, -3.2), (-2.1, -8.2), (2.1, -8.2)], 0)
        ins = np.asarray(im_) > 0
    elif STYLE == 'star':
        from PIL import Image as _I, ImageDraw as _D
        Rs = cfg.get('star_r', 22.0); rin = cfg.get('star_inner', 0.55) * Rs; hcs = cfg.get('star_bottom', 62.0) + 0.809 * Rs
        pts = []
        for k in range(10):
            a = np.radians(90 + 36 * k); rr_ = Rs if k % 2 == 0 else rin
            pts.append(((rr_ * np.cos(a) + 110.0) / GS, (hcs + rr_ * np.sin(a)) / GS))
        im_ = _I.new('L', (GXm.shape[1], GXm.shape[0]), 0); _D.Draw(im_).polygon(pts, fill=255)
        ins = np.asarray(im_) > 0
    else:
        th = np.radians(cfg.get('moon_turn', MOON_TURN)); hc = cfg.get('moon_centre', 86.0)
        R_ = cfg.get('moon_h', 54.0) / 2.0; ax_ = cfg.get('moon_narrow', 1.0); r_ = cfg.get('moon_r', 0.78) * R_; d_ = cfg.get('moon_d', 0.5) * R_
        def moon(cx0):
            px, ph = GXm - cx0, GHm - hc
            u = (px * np.cos(th) - ph * np.sin(th)) / ax_; v = px * np.sin(th) + ph * np.cos(th)
            return (u ** 2 + v ** 2 <= R_ ** 2) & ((u - d_) ** 2 + v ** 2 > r_ ** 2)
        m0 = moon(0.0); cx_ = float(GXm[m0].mean())
        ins = moon(-cx_ + cfg.get('moon_dx', 1.5))
    ins &= ~((GXa < 2.0) & (GHm < 54.0))
    SDF = (edt(ins) - edt(~ins)) * GS
    A_style = float(ins.sum()) * GS * GS
    MARG = 2.5
    A_start = float((SDF > -MARG).sum()) * GS * GS
    SXM = float(GXa[ins].max()); SH0, SH1 = float(GHm[ins].min()), float(GHm[ins].max())
    sdf = lambda X, h: map_coordinates(SDF, [np.atleast_1d(np.asarray(h, float)) / GS, (np.atleast_1d(np.asarray(X, float)) + 110.0) / GS], order=1, mode='nearest')
    print('style', STYLE, 'area %.0f mm2 (level 2 %.0f), height %.0f, half width %.0f' % (A_style, A0, SH1 - SH0, SXM))
    def place(count, keep_fn):
        cnt = int(count * A_start / A0 * (cfg.get('swords_density', 5.0) if STYLE == 'swords' else cfg.get('star_density', 4.0) if STYLE == 'star' else cfg.get('skull_density', 5.0) if STYLE == 'skull' else 1.5 if STYLE != 'strip' else 1.0)); placed = 0
        for _ in range(cnt * 30):
            if placed >= cnt:
                break
            Xs = rng.uniform(-SXM - MARG, SXM + MARG); h = rng.uniform(SH0 - MARG, SH1 + MARG)
            d = float(sdf(Xs, h)[0])
            if d < -MARG or rng.random() > keep_fn(max(d, 0.0)):
                continue
            placed += 1
            yield (abs(Xs), h, min(1.0, abs(Xs) / SXM)), None, (1 if Xs >= 0 else -1), d

PXW = 0.93
n = lambda k: int(k * share * cfg.get('density', {}).get(str(level), 1.0))
if level == 1:
    for ub, vb, side, dpt in place(n(1700), lambda d: 0.8 * (lambda q: q * q * (3 - 2 * q))(min(1.0, max(0.0, (d + 0.35) / 1.1))) if d > -0.35 else 0):
        X, h = start(ub, vb, side)
        stroke([X, X + 0.56], [h, h - 0.37], rng.uniform(0.6, 0.9) * PXW, shade(), rng.uniform(0.18, 0.32))
elif level == 2:
    Llo, Lhi = opts.get('len', [9.0, 17.0]); tlo, thi = opts.get('turn', [0.02, 0.07]); wlo, whi = opts.get('width', [0.5, 0.7])
    if opts.get('even'):
        keep2 = lambda d: 0.85 if d > -0.05 else 0
    else:
        keep2 = lambda d: 0.80 * (lambda q: q * q * (3 - 2 * q))(min(1.0, max(0.0, (d + 0.35) / 1.1))) if d > -0.35 else 0
    for ub, vb, side, dpt in place(n(opts.get('count', 2000)), keep2):
        xs, ys = coil(ub, vb, side, rng.uniform(Llo, Lhi), tlo, thi, opts.get('dir', 'fan'))
        stroke(xs, ys, rng.uniform(wlo, whi) * PXW, shade(), rng.uniform(*opts.get('alpha', [0.30, 0.45])))
elif level == 3:
    for ub, vb, side, dpt in place(n(2300 * (cfg.get('patch_density', 2.2) if PATCHY else 1)), lambda d: 0.75 * (lambda q: q * q * (3 - 2 * q))(min(1.0, max(0.0, (d + 0.35) / 1.3))) if d > -0.35 else 0):
        xs, ys = coil(ub, vb, side, rng.uniform(8, 15), 0.18, 0.45)
        stroke(xs, ys, rng.uniform(0.55, 0.85) * PXW, shade(), rng.uniform(0.3, 0.5))
else:
    for ub, vb, side, dpt in place(n(2500), lambda d: np.clip(0.42 + 0.25 * d, 0, 0.8) if d > 0 else (stray if d > -1.5 else 0)):
        xs, ys = coil(ub, vb, side, rng.uniform(16, 28) * cfg.get('l4_len', 1.0) * (1.2 if dpt < 0 else 1.0), 0.07 * cfg.get('l4_turn', 1.0), 0.2 * cfg.get('l4_turn', 1.0))
        stroke(xs, ys, rng.uniform(0.55, 0.9) * PXW, shade(), rng.uniform(0.3, 0.52))

FLAT_TOP = be_phys(X0 + 3.0, top + 0.5)[1]
def shape_mask_w(cx, cy):
    Xs = cx * R - XW; h = HTOP - cy * R; sg = np.where(Xs >= 0, 1.0, -1.0)
    X = np.abs(Xs); half = np.maximum(half_lim(h), 2.0)
    _, up, lo = depth_w(X, h, sg)
    m_side = np.clip((half - X + 1.0) / 2.0, 0, 1) if level != 4 else np.clip((half - X + 8.0) / 8.0, 0, 1)
    if level == 4:
        return m_side * np.clip((up + 1.2) / 1.2, 0, 1)
    if level == 2 and opts.get('even'):
        return m_side * np.clip((up + 0.1) / 0.2, 0, 1)
    return m_side * np.clip((up + 1.4) / 1.8, 0, 1)

def shape_mask_style(cx, cy):
    return np.clip((sdf(cx * R - XW, HTOP - cy * R) + 0.15) / 0.3, 0, 1)

def shape_mask(cx, cy):
    if STYLE:
        return shape_mask_style(cx, cy)
    if CRE is not None:
        return shape_mask_w(cx, cy)
    X = np.abs(cx * R - XW); h = HTOP - cy * R; sg = np.where(cx * R - XW >= 0, 1.0, -1.0)
    _, k = tree.query(np.stack([X, h], 1))
    if level == 4:
        return np.clip((depth(tex_u[k], tex_v[k], sg) + 1.2) / 1.2, 0, 1)
    side, upper, lower, inner, _ = depth_parts(tex_u[k], tex_v[k], sg)
    edge = np.minimum.reduce([side, lower, inner])
    if level == 2 and opts.get('even'):
        return np.clip((edge + 0.1) / 0.2, 0, 1) * np.clip((upper + 0.1) / 0.2, 0, 1)
    return np.clip((edge + 0.3) / 0.6, 0, 1) * np.clip((upper + 1.4) / 1.8, 0, 1)

lnT = np.zeros(CHH * CWW); Cs = np.zeros((3, CHH * CWW)); As = np.zeros(CHH * CWW)
if stamps:
    cx = np.concatenate([s[0] for s in stamps]); cy = np.concatenate([s[1] for s in stamps])
    rad = np.concatenate([s[2] for s in stamps]); col = np.concatenate([s[3] for s in stamps]); al = np.concatenate([s[4] for s in stamps])
    al = al * shape_mask(cx, cy)
    if PATCHY:
        Xp = cx * R - XW; hp_ = HTOP - cy * R
        pr = np.random.default_rng(2100); nz = np.zeros_like(Xp)
        for _ in range(6):
            lam = pr.uniform(22, 45); thp = pr.uniform(0, 6.28); php = pr.uniform(0, 6.28)
            nz += np.sin(2 * np.pi * (Xp * np.cos(thp) + hp_ * np.sin(thp)) / lam + php)
        nz = nz / 6.0 * 0.5 + 0.5
        al = al * np.clip((nz - cfg.get('patch_cut', 0.47)) / 0.1, 0, 1)
    K = int(np.ceil(rad.max() + 1)); offs = np.arange(-K, K + 1)
    CHUNK = max(50, min(20000, int(2.0e6 // (2 * K + 1) ** 2)))
    for s in range(0, len(cx), CHUNK):
        sl = slice(s, s + CHUNK)
        ix = np.floor(cx[sl])[:, None, None] + offs[None, :, None]; iy = np.floor(cy[sl])[:, None, None] + offs[None, None, :]
        d = np.hypot(ix + 0.5 - cx[sl][:, None, None], iy + 0.5 - cy[sl][:, None, None])
        a = np.clip(rad[sl][:, None, None] + 0.5 - d, 0, 1) * al[sl][:, None, None]
        ok = (a > 0) & (ix >= 0) & (ix < CWW) & (iy >= 0) & (iy < CHH)
        flat = (iy * CWW + ix)[ok].astype(int); aa = a[ok]
        lnT += np.bincount(flat, weights=np.log1p(-np.minimum(aa, 0.98)), minlength=CHH * CWW)
        As += np.bincount(flat, weights=aa, minlength=CHH * CWW)
        for c in range(3):
            Cs[c] += np.bincount(flat, weights=(aa * np.broadcast_to(col[sl][:, c][:, None, None], a.shape)[ok]), minlength=CHH * CWW)
A = (1 - np.exp(lnT)).reshape(CHH, CWW)
A = A * np.clip((HTOP - (np.arange(CHH) + 0.5) * R - 12.0) / 10.0, 0, 1)[:, None]
P = [gaussian_filter((Cs[c] / np.maximum(As, 1e-9)).reshape(CHH, CWW) * A, 1.2) for c in range(3)]
A = gaussian_filter(A, 1.2)
if __import__('os').environ.get('LAYERS'):
    # (the canvas as painted, for the Features export (races/features_export.py): its alpha and its
    # colour, the canvas's frame and the scales the hair texture's mapping below uses)
    np.savez_compressed(__import__('os').environ['LAYERS'], A=A.astype(np.float32), P=np.stack(P).astype(np.float32),
                        R=R, XW=XW, HTOP=HTOP, sH=sH, SV=SV, hair_drop=cfg.get('hair_drop', 0.0), strokes=len(stamps))
yy, xx = np.mgrid[0:RH, 0:RW].astype(float)
Xr = (pc['y0'] + (xx + 0.5) / RW * (pc['y1'] - pc['y0'])) * 1000 / sH
hr = ((pc['s1'] - (yy + 0.5) / RH * (pc['s1'] - pc['s0'])) * 1000 + cfg.get('hair_drop', 0.0)) / SV
px, py = to_canvas(Xr, hr)
samp = lambda img: map_coordinates(img, [py, px], order=1, mode='constant', cval=0.0)
a = np.clip(samp(A), 0, 1)
rgb = np.stack([samp(P[c]) for c in range(3)], -1) / np.maximum(a, 1e-6)[..., None]
if 'hair_clear' in cfg:
    hw_, st_ = cfg['hair_clear']
    s_real = (pc['s1'] - (yy + 0.5) / RH * (pc['s1'] - pc['s0'])) * 1000
    y_real = np.abs(Xr) * sH
    sm_ = lambda e0, e1, x: (lambda t: t * t * (3 - 2 * t))(np.clip((x - e0) / (e1 - e0), 0, 1))
    a = a * (1 - cfg.get('hair_clear_k', 0.9) * np.exp(-(y_real / hw_) ** 2) * sm_(st_ + 4.0, st_ - 4.0, s_real)) * sm_(0.0, 6.0, s_real)
tex[RY:RY + RH, RX:RX + RW, :3] = np.clip(rgb, 0, 1)
tex[RY:RY + RH, RX:RX + RW, 3] = a
Image.fromarray((tex * 255).round().astype(np.uint8), 'RGBA').save(out)
print('strokes', len(stamps), 'stamps', sum(len(s[0]) for s in stamps), 'sampled alpha mean %.3f max %.2f' % (a.mean(), a.max()))
