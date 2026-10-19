import sys, os, json, numpy as np
from PIL import Image
race, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
frames = json.load(open('decal/frames.json')); sH = frames[race]['H'] / frames['BloodElf']['H']
_c = json.load(open('decal/race_cfg.json')).get(race, {})
if os.environ.get('CFG_OVER'):
    _c.update(json.loads(os.environ['CFG_OVER']))
sH = _c.get('lines_scale', _c.get('hair_scale', sH))
mp = np.load(f'decal/uvmap_{race}.npz'); us, vs, Y, S = mp['us'], mp['vs'], mp['Y'], mp['S']; Zm = mp['Z']
im = np.asarray(Image.open(src).convert('RGB')).astype(float) / 255
H, W = im.shape[:2]; k = W / 512
ROW_S = np.array([[226, 139.4], [232, 106.2], [240, 53.7], [246, 4.2], [258, -8.8], [266, -41.4]])
def t_old(s):
    row = np.interp(-s, -ROW_S[:, 1], ROW_S[:, 0])
    return np.clip((row - 240.0) / (266.3 - 240.0), 0, 1)
yy, xx = np.mgrid[0:H, 0:W]
u = (xx + 0.5) / k; v = (yy + 0.5) / k
iu = np.clip(np.round((u - us[0]) / 0.25).astype(int), 0, len(us) - 1); iv = np.clip(np.round((v - vs[0]) / 0.25).astype(int), 0, len(vs) - 1)
inside = (u >= us[0]) & (u <= us[-1]) & (v >= vs[0]) & (v <= vs[-1])
ay = np.where(inside, Y[iv, iu], np.nan) * 1000 / sH
s = np.where(inside, S[iv, iu], np.nan) * 1000 / sH - _c.get('lines_shift', 0.0)
zz = np.where(inside, Zm[iv, iu], np.nan) * 1000
ok = np.isfinite(ay) & np.isfinite(s)
if _c.get('lines_fill', 0):
    from scipy.ndimage import distance_transform_edt
    dist, (ij, ii2) = distance_transform_edt(~ok, return_indices=True)
    near = (~ok) & inside & (dist <= _c['lines_fill'] * k) & (u >= _c.get('lines_fill_u0', 0))
    ay = np.where(near, ay[ij, ii2], ay); s = np.where(near, s[ij, ii2], s); zz = np.where(near, zz[ij, ii2], zz)
    ok = np.isfinite(ay) & np.isfinite(s)
ay0 = np.where(ok, ay, 0); s0 = np.where(ok, s, 0)
t = t_old(s0)
along = np.sin(np.pi * t) ** 0.6 * (t > 0) * (t < 1) * np.clip((s0 + _c.get('lines_shift', 0.0) - _c.get('lines_cut', 0.0)) / 6.0, 0, 1)
W = _c.get('lines_width', 1.0)
line = np.exp(-(ay0 / (1.63 * W)) ** 2) * along
fold = np.exp(-((ay0 - 4.2 * W) / (3.1 * W)) ** 2) * along
foldshade = np.exp(-((ay0 - 8.45 * W) / (2.9 * W)) ** 2) * along * 0.5
rim = np.exp(-((ay0 - _c.get('lines_rim_at', 3.0) * W) / (_c.get('lines_rim_w', 1.3) * W)) ** 2) * along \
    * (1 - _c.get('lines_rim_ramp', 0.0) * (1 - np.clip(t * 1.25, 0, 1)))
dome = np.exp(-(ay0 / 11.4) ** 2) * np.exp(-((s0 - 80.0) / 26.0) ** 2) * 0.05
f = (1 - _c.get('lines_depth', 0.95) * line + _c.get('lines_fold', 0.10) * fold - 0.06 * foldshade + dome)
f = np.where(ok, f, 1.0)
lw = _c.get('lines_lift_w', 8.0)
sprof = np.clip((s0 + 3.0) / 10.0, 0, 1) * np.clip((_c.get('lines_lift_top', 100.0) - s0) / 30.0, 0, 1)
wl = np.where(ok, np.clip(_c.get('lines_lift', 0.0) * np.exp(-(ay0 / lw) ** 4) * sprof, 0, 1), 0.0)
tn = _c.get('lines_tone', [0.28, 0.40, 0.17])
if 'lines_ref' in _c:
    u0, v0, u1, v1 = _c['lines_ref']
    ref = np.median(im[int(v0 * k):int(v1 * k), int(u0 * k):int(u1 * k)].reshape(-1, 3), 0)
    fk = np.array(_c.get('lines_tone_k', [[0, 1.0], [85, 1.0]]))
    tone = ref[None, None, :] * np.interp(s0, fk[:, 0], fk[:, 1])[..., None]
elif isinstance(tn[0], list):
    tn = np.array(tn); tone = np.stack([np.interp(s0, tn[:, 0], tn[:, c]) for c in (1, 2, 3)], -1)
else:
    tone = np.array(tn)
base = im + np.maximum(tone - im, 0) * wl[..., None]
out = base * f[..., None]
rc = np.array(_c.get('lines_rim_col', [1.0, 1.0, 1.0]))
if 'lines_ref' in _c and 'lines_rim_k' in _c:
    rc = np.clip(ref * _c['lines_rim_k'], 0, 1)
out = out + (rc - out) * (np.where(ok, rim, 0.0) * _c.get('lines_rim', 0.0))[..., None]
if 'lines_core' in _c:
    sm = lambda e0, e1, x: (lambda t: t * t * (3 - 2 * t))(np.clip((x - e0) / (e1 - e0), 0, 1))
    sel = ok & (s0 > 30) & (s0 < 110) & (ay0 < 25)
    lum_ref = float(np.median(im[sel] @ np.array([0.299, 0.587, 0.114])))
    cw = _c.get('lines_core_w', 3.3)
    cwv = cw * np.clip(along, 0.05, 1) ** 0.6
    wp = (1 - sm(cwv * 0.55, cwv, ay0)) * _c.get('lines_core_k', 0.9) * np.clip(along * 1.6, 0, 1) * ok
    cc = np.array(_c['lines_core']) * (0.88 + 0.12 * min(1.0, lum_ref * 2.0))
    out = out * (1 - wp[..., None]) + cc[None, None, :] * wp[..., None]
    slot = np.exp(-(ay0 / _c.get('lines_slot_w', 0.9)) ** 2) * along * ok
    out = out * (1 - _c.get('lines_slot_k', 0.8) * slot)[..., None]
if 'lines_detail' in _c:
    D = _c['lines_detail']
    sm = lambda e0, e1, x: (lambda t: t * t * (3 - 2 * t))(np.clip((x - e0) / (e1 - e0), 0, 1))
    lum = lambda a: a @ np.array([0.299, 0.587, 0.114])
    rq = np.random.default_rng(D.get('seed', 5))
    def nz(fy, fq, n=6):
        acc = 0.0
        for _ in range(n):
            a_ = rq.uniform(0, 2 * np.pi); f1 = fy * rq.uniform(0.6, 1.6); f2 = fq * rq.uniform(0.6, 1.6)
            acc = acc + np.sin(f1 * yr * np.cos(a_) + f2 * q * np.sin(a_) + rq.uniform(0, 6.28))
        return acc / np.sqrt(n / 2)
    yr = ay0 * sH
    q = np.where(ok, np.nan_to_num(zz) - D['z0'], -1e3)
    sel = ok & (q > D.get('ref_lo', 90.0)) & (q < D.get('ref_hi', 170.0)) & (yr < 25)
    base_l = float(np.median(lum(im[sel])))
    tl = 0.88 + 0.12 * min(1.0, base_l * 2.0)
    Hh, Wm = D['height'], D['width']
    U = q / Hh; Uc = np.clip(U, 0, 1)
    env = sm(0.0, 0.06, U) * sm(1.0, 0.92, U) * ok
    wob = 0.35 * nz(0.35, 0.12, 4)
    Wf = Wm * np.sin(np.pi * Uc ** D.get('skew', 0.8)) ** 0.75 + D.get('wmin', 2.0)
    pink = np.array(D['pink']) * tl; hi = np.array(D['hi']) * tl; deep = np.array(D['deep']) * tl
    soft = D.get('soft', 5.0)
    mm = sm(Wf + soft, Wf - soft, yr) * env
    tint = np.array(D.get('tint', [1.08, 0.80, 0.76])) * lum(out)[..., None]
    wb = np.exp(-(yr / (0.75 * Wf + 2.0)) ** 2) * env
    out = out + (tint - out) * (wb * D.get('tint_k', 0.30))[..., None]
    ridge = np.exp(-((yr - 0.5 * Wf) / (0.34 * Wf + 1.0)) ** 2) * mm
    edge = np.exp(-((yr - (Wf + 1.5)) / (soft * 0.55)) ** 2) * env * sm(0.0, 0.2, U)
    gap_in = np.exp(-((yr - 1.5) / 2.0) ** 2) * env
    shade = 1 + D.get('ridge_k', 0.13) * ridge - D.get('edge_k', 0.16) * edge - D.get('gap_edge_k', 0.22) * gap_in
    shade = shade * (1 - D.get('low_k', 0.08) * (1 - Uc) * mm)
    out = out * shade[..., None]
    ow = D.get('gap_win', [0.06, 0.24, 0.74, 0.40])
    cw = D.get('gap', 0.6) + D.get('gap_open', 1.3) * sm(ow[0], ow[1], U) * sm(ow[2], ow[3], U) + 0.15 * wob
    mw = D.get('band_win', [0.07, 0.28, 0.78, 0.42])
    mb = D.get('band', 2.8) * sm(mw[0], mw[1], U) * sm(mw[2], mw[3], U) * (1 + 0.12 * nz(0.25, 0.2, 4))
    yb = cw + 0.5 * mb
    bmask = np.exp(-(np.abs(yr - yb) / (0.55 * mb + 0.35)) ** 2.4) * env * (mb > 0.2)
    wr = 1 + D.get('ripple', 0.07) * np.sin(q * 0.8 + 3.0 * nz(0.2, 0.3, 3))
    grad = np.clip((yr - cw) / np.maximum(mb, 1e-3), 0, 1)
    tone = pink[None, None, :] * (0.78 + 0.38 * np.sin(np.pi * grad))[..., None]
    tone = tone + (deep[None, None, :] * 1.2 - tone) * (1 - grad)[..., None] * 0.35
    out = out * (1 - (bmask * D.get('band_k', 0.88))[..., None]) + tone * wr[..., None] * (bmask * D.get('band_k', 0.88))[..., None]
    gl = np.exp(-((yr - (cw + 0.38 * mb)) / 0.55) ** 2) * bmask * np.clip(0.55 + 0.45 * nz(0.15, 0.35, 5), 0, 1) * sm(0.2, 0.4, U) * sm(0.7, 0.5, U)
    out = out + (hi[None, None, :] - out) * (gl * D.get('band_hi', 0.45))[..., None]
    gmask = np.exp(-(yr / np.maximum(cw, 0.2)) ** 2.2) * env * sm(0.07, 0.13, U) * sm(0.9, 0.8, U)
    gcol = deep[None, None, :] * (0.6 + 0.4 * np.clip(yr / np.maximum(cw, 1e-3), 0, 1))[..., None]
    out = out * (1 - (gmask * D.get('gap_k', 0.9))[..., None]) + gcol * (gmask * D.get('gap_k', 0.9))[..., None]
    hc = D.get('cap_c', 0.93 * Hh); hrx, hry = D.get('cap_rx', 3.2), D.get('cap_ry', 4.0)
    cap = np.hypot(yr / hrx, (q - hc) / hry)
    m_cap = sm(1.6, 0.4, cap) * ok
    hl = np.clip(0.5 - 0.5 * (q - hc) / hry, 0, 1)
    out = out + (np.clip(out * 1.16 + 0.015, 0, 1) - out) * (m_cap * D.get('cap_k', 0.5) * (0.35 + 0.65 * hl))[..., None]
    reg = np.exp(-(yr / (Wf + 8.0)) ** 2) * ok
    out = out * (1 + 0.022 * nz(2.2, 2.0, 7) * reg)[..., None]
Image.fromarray((np.clip(out, 0, 1) * 255).round().astype(np.uint8)).save(dst)
print(race, 'texels shaded', int(ok.sum()), 'line texels', int((line > 0.3).sum()))
