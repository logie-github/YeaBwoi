import sys, os, io, numpy as np
sys.path.insert(0, 'tools'); import mpq, blp_pal
from PIL import Image
from scipy.ndimage import gaussian_filter

os.makedirs('decal/sprites', exist_ok=True)
m = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in m.listfile()}
def feature(nm):
    return blp_pal.decode_palette_blp(m.read(low[f'character\\scourge\\facialfemalelowerhair{nm}.blp'])).astype(float)
def cut(a, box, soft=(24, 190)):
    x0, y0, x1, y1 = box
    c = a[y0:y1, x0:x1].copy()
    al = np.clip((c[..., 3] - soft[0]) / (soft[1] - soft[0]), 0, 1)
    c[..., 3] = al * 255
    ys, xs = np.where(al > 0.04)
    c = c[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return Image.fromarray(np.clip(c, 0, 255).astype(np.uint8), 'RGBA')

a = feature('00_00')
x0, y0, x1, y1 = 108, 42, 192, 114
rgb = a[y0:y1, x0:x1, :3].copy(); al = a[y0:y1, x0:x1, 3]
yy, xx = np.mgrid[0:rgb.shape[0], 0:rgb.shape[1]]
def ell(cx, cy, ax, ay): return ((xx - (cx - x0)) / ax) ** 2 + ((yy - (cy - y0)) / ay) ** 2 <= 1
mask = ell(149.5, 79, 30, 13) | ell(149, 59, 15, 8) | ell(149, 95, 17, 9)
mask = gaussian_filter(mask.astype(float), 1.2) > 0.2
inside = al > 120
fill = rgb.copy()
mean = rgb[inside & ~mask].mean(0); fill[mask] = mean
for _ in range(600):
    nb = (np.roll(fill, 1, 0) + np.roll(fill, -1, 0) + np.roll(fill, 1, 1) + np.roll(fill, -1, 1)) / 4
    fill[mask] = nb[mask]
clean = inside & ~mask
hp = rgb - gaussian_filter(rgb, (1.6, 1.6, 0))
rng = np.random.default_rng(5)
src = np.argwhere(clean[8:-8, 8:-8]) + 8
for (py, px) in np.argwhere(mask):
    sy, sx = src[rng.integers(len(src))]
    fill[py, px] += hp[sy, sx] * 0.35
out = a.copy(); out[y0:y1, x0:x1, :3] = np.where(mask[..., None], fill, rgb)
Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), 'RGBA').save('decal/sprites/leather_full.png')
cut(out, (x0 - 4, y0 - 4, x1 + 4, y1 + 4)).save('decal/sprites/leather.png')

b = feature('03_00')
col = (b[..., 3] > 96).sum(0); xs = np.where(col > 0)[0]
gaps = [(xs[i], xs[i + 1]) for i in range(len(xs) - 1) if xs[i + 1] - xs[i] > 4]
print('black jaw columns', xs.min(), xs.max(), 'gaps', gaps)
cut(b, (60, 40, gaps[-1][0] + 2 if gaps else 256, 128)).save('decal/sprites/blackjaw.png')
cut(feature('04_00'), (60, 44, 244, 122), soft=(16, 150)).save('decal/sprites/greyjaw.png')
import shutil
shutil.copy('r3d/../decal/sprites/feat_302.png', 'decal/sprites/straps.png') if False else None
for n in ('leather', 'blackjaw', 'greyjaw'):
    print(n, Image.open(f'decal/sprites/{n}.png').size)
