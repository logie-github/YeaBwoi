import sys, os, json, numpy as np
from PIL import Image
race, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
frames = json.load(open('decal/frames.json')); sH = frames[race]['H'] / frames['BloodElf']['H']
_c = json.load(open('decal/race_cfg.json')).get(race, {})
if os.environ.get('CFG_OVER'):
    _c.update(json.loads(os.environ['CFG_OVER']))
sH = _c.get('lines_scale', _c.get('hair_scale', sH))
mp = np.load(f'decal/uvmap_{race}.npz'); us, vs, Y, S = mp['us'], mp['vs'], mp['Y'], mp['S']
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
ok = np.isfinite(ay) & np.isfinite(s)
if _c.get('lines_fill', 0):
    from scipy.ndimage import distance_transform_edt
    dist, (ij, ii2) = distance_transform_edt(~ok, return_indices=True)
    near = (~ok) & inside & (dist <= _c['lines_fill'] * k) & (u >= _c.get('lines_fill_u0', 0))
    ay = np.where(near, ay[ij, ii2], ay); s = np.where(near, s[ij, ii2], s)
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
    wp = (1 - sm(cwv * 0.55, cwv, ay0)) * _c.get('lines_core_k', 0.9) * np.clip(along * 1.6, 0, 1)
    cc = np.array(_c['lines_core']) * (0.88 + 0.12 * min(1.0, lum_ref * 2.0))
    out = out * (1 - wp[..., None]) + cc[None, None, :] * wp[..., None]
    slot = np.exp(-(ay0 / _c.get('lines_slot_w', 0.9)) ** 2) * along
    out = out * (1 - _c.get('lines_slot_k', 0.8) * slot)[..., None]
Image.fromarray((np.clip(out, 0, 1) * 255).round().astype(np.uint8)).save(dst)
print(race, 'texels shaded', int(ok.sum()), 'line texels', int((line > 0.3).sum()))
