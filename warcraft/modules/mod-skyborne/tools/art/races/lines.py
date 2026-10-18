import sys, json, numpy as np
from PIL import Image
race, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
frames = json.load(open('decal/frames.json')); sH = frames[race]['H'] / frames['BloodElf']['H']
_c = json.load(open('decal/race_cfg.json')).get(race, {})
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
ay0 = np.where(ok, ay, 0); s0 = np.where(ok, s, 0)
t = t_old(s0)
along = np.sin(np.pi * t) ** 0.6 * (t > 0) * (t < 1) * np.clip((s0 + _c.get('lines_shift', 0.0) - _c.get('lines_cut', 0.0)) / 6.0, 0, 1)
line = np.exp(-(ay0 / 1.63) ** 2) * along
fold = np.exp(-((ay0 - 4.2) / 3.1) ** 2) * along
foldshade = np.exp(-((ay0 - 8.45) / 2.9) ** 2) * along * 0.5
dome = np.exp(-(ay0 / 11.4) ** 2) * np.exp(-((s0 - 80.0) / 26.0) ** 2) * 0.05
f = (1 - 0.95 * line + 0.10 * fold - 0.06 * foldshade + dome)
f = np.where(ok, f, 1.0)
out = im * f[..., None]
Image.fromarray((np.clip(out, 0, 1) * 255).round().astype(np.uint8)).save(dst)
print(race, 'texels shaded', int(ok.sum()), 'line texels', int((line > 0.3).sum()))
