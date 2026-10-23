#!/usr/bin/env python3
"""The Features of the character creation screen (mod-skyborne): the body-hair designs of every race
that has them, as data for the patch (src/SkybornePatch.cpp makes each one's texture from it).

For each race with hair-art settings (decal/race_cfg.json) and each design, decal/paint.py paints the
design on its canvas (LAYERS=... saves the canvas), and this samples the canvas at every texel of the
pelvis part of the female body texture, through the fields the shading already has for the race
(data/client/shade/<Race>.bin: each texel's distance from the body's middle and its height). The result
is the texture the game puts on the pelvis (the underwear's first texture, a quarter of the body
texture's height and half its width), with alpha. One file per race:

    FEAT, version 1, pelvis width, pelvis height, box x, y, w, h (in the pelvis texture's texels),
    design count; per design: its number, the compressed size, the box's alpha bytes then its
    colours (RGB bytes), zlib-compressed together.

and features.txt names the designs and the races. Run from tools/art with Patch-H.MPQ (the HD
character archive) in it, after `races/model_json.py BloodElf` and `decal/uvmap.py BloodElf 250 330 215 280`.

    python3 races/features_export.py ../../data/client/features [Race ...]

(SHADE_DIR names the shading folder, JOBS the paints run at once: 4, WORK a folder to keep the painted
canvases in, so a second run paints only what is missing.)
"""
import sys, os, io, json, struct, zlib, subprocess, tempfile
import numpy as np
from concurrent.futures import ThreadPoolExecutor
from PIL import Image
from scipy.ndimage import map_coordinates, distance_transform_edt
sys.path.insert(0, 'tools'); import mpq

# The designs, by decal/paint.py's number (1-4: four densities; 5 a strip, 6 a heart, 7 a moon, 8 a
# moon turned, 9 a star, 10 crossed swords, 11 a skull; 18 patches). The order is the screen's.
DESIGNS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 18]
SHARE = 0.33

out_dir = sys.argv[1]
cfgs = json.load(open('decal/race_cfg.json'))
# (the goblin isn't a player's race, and the tauren have no hair art: neither has designs)
races = sys.argv[2:] or [r for r in cfgs if r != 'Goblin' and all(k in cfgs[r] for k in ('canvas', 'region', 'hair_cols'))]
game = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in game.listfile()}
os.makedirs(out_dir, exist_ok=True)
work = os.environ.get('WORK') or tempfile.mkdtemp(prefix='features_')
os.makedirs(work, exist_ok=True)


def paint(job):
    race, design = job
    npz = os.path.join(work, f'{race}_{design}.npz')
    if os.path.exists(npz):                      # (painted already: WORK names the folder of an earlier run)
        return npz
    env = dict(os.environ, LAYERS=npz)
    r = subprocess.run([sys.executable, '-I', 'decal/paint.py', race, f'Character\\{race}\\Hair00_00.blp', str(design),
                        os.path.join(work, f'{race}_{design}.png'), str(SHARE)], env=env, capture_output=True, text=True)
    if r.returncode != 0 or not os.path.exists(npz):
        raise SystemExit(f'{race} design {design}: the painter failed\n{r.stdout[-400:]}\n{r.stderr[-800:]}')
    return npz


def fields(race):
    """Every texel of the 512 x 512 body texture: |distance from the middle| and height (mm), nan where unmapped."""
    d = open(os.path.join(os.environ.get('SHADE_DIR', '../../data/client/shade'), race + '.bin'), 'rb').read()
    magic, ver, x0, y0, w, h, noise = struct.unpack_from('<4sIIIIII', d, 0)
    assert magic == b'SHDE' and ver == 1, race
    n, o = w * h, 28
    ay = np.frombuffer(d, '<f4', n, o).reshape(h, w); o += 4 * n
    s = np.frombuffer(d, '<f4', n, o).reshape(h, w); o += 4 * n
    o += 4 * n
    ok = np.frombuffer(d, 'u1', n, o).reshape(h, w) > 0
    AY = np.zeros((512, 512)); S = np.zeros((512, 512)); OK = np.zeros((512, 512))
    AY[y0:y0 + h, x0:x0 + w] = ay; S[y0:y0 + h, x0:x0 + w] = s; OK[y0:y0 + h, x0:x0 + w] = ok
    return AY, S, OK


def sample(race, npz, AY, S, OK, pw, ph):
    c = cfgs[race]
    L = np.load(npz)
    A, P, R, XW, HTOP, sH, SV = L['A'], L['P'], float(L['R']), float(L['XW']), float(L['HTOP']), float(L['sH']), float(L['SV'])
    drop = float(L['hair_drop'])
    # the fields, filled outward from the mapped texels so the interpolation has no holes
    _, (ij, ii) = distance_transform_edt(OK == 0, return_indices=True)
    AYf, Sf = AY[ij, ii], S[ij, ii]
    # each pelvis texel's place in the body texture (the pelvis is x 256-512, y 192-320 of 512)
    px, py = np.meshgrid(np.arange(pw) + 0.5, np.arange(ph) + 0.5)
    u = 256 + px * (256.0 / pw) - 0.5
    v = 192 + py * (128.0 / ph) - 0.5
    look = lambda img: map_coordinates(img, [v, u], order=1, mode='nearest')
    ay, s, ok = look(AYf), look(Sf), look(OK.astype(float))
    ok = np.clip((ok - 0.5) / 0.5, 0, 1)
    # the canvas, as decal/paint.py maps it into the hair texture: lateral Xr = |y| / hair scale, height
    # hr = (s + drop) / vertical scale; one half of the canvas (the body texture holds one half, mirrored)
    Xr, hr = ay / sH, (s + drop) / SV
    cx, cy = (Xr + XW) / R, (HTOP - hr) / R
    samp = lambda img: map_coordinates(img, [cy, cx], order=1, mode='constant', cval=0.0)
    a = np.clip(samp(A), 0, 1)
    rgb = np.stack([samp(P[k]) for k in range(3)], -1) / np.maximum(a, 1e-6)[..., None]
    if 'hair_clear' in c:
        hw_, st_ = c['hair_clear']
        sm = lambda e0, e1, x: (lambda t: t * t * (3 - 2 * t))(np.clip((x - e0) / (e1 - e0), 0, 1))
        a = a * (1 - c.get('hair_clear_k', 0.9) * np.exp(-(ay / hw_) ** 2) * sm(st_ + 4.0, st_ - 4.0, s)) * sm(0.0, 6.0, s)
    a = a * ok
    return a, np.clip(rgb, 0, 1)


jobs = [(r, d) for r in races for d in DESIGNS]
print(f'{len(jobs)} designs to paint ({len(races)} races)', flush=True)
with ThreadPoolExecutor(max_workers=int(os.environ.get('JOBS', '4'))) as pool:
    npzs = dict(zip(jobs, pool.map(paint, jobs)))

text = ['# the Features: per race, the pelvis texture it was made for and its designs (races/features_export.py)',
        'designs ' + ' '.join(str(d) for d in DESIGNS)]
for race in races:
    skin = Image.open(io.BytesIO(game.read(low[f'character\\{race}\\female\\{race}femaleskin00_00.blp'.lower()]))).size
    pw, ph = skin[0] // 2, skin[1] // 4
    AY, S, OK = fields(race)
    maps = {d: sample(race, npzs[(race, d)], AY, S, OK, pw, ph) for d in DESIGNS}
    covered = np.zeros((ph, pw), bool)
    for a, _ in maps.values():
        covered |= a > 0.004
    ys, xs = np.where(covered)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()) + 1, int(ys.min()), int(ys.max()) + 1
    w, h = x1 - x0, y1 - y0
    body = bytearray(b'FEAT' + struct.pack('<IIIIIIII', 1, pw, ph, x0, y0, w, h, len(DESIGNS)))
    sizes = []
    for d in DESIGNS:
        a, rgb = maps[d]
        a8 = (a[y0:y1, x0:x1] * 255).round().astype(np.uint8)
        c8 = (rgb[y0:y1, x0:x1] * 255).round().astype(np.uint8)
        c8[a8 == 0] = 0
        blob = zlib.compress(a8.tobytes() + c8.tobytes(), 9)
        body += struct.pack('<II', d, len(blob)) + blob
        sizes.append((d, int((a8 > 0).sum()), round(float(a.max()), 2), len(blob)))
    open(os.path.join(out_dir, race + '.bin'), 'wb').write(bytes(body))
    text.append(f'[{race}]')
    text.append(f'pelvis {pw} {ph}')
    print(race, f'skin {skin[0]}x{skin[1]} pelvis {pw}x{ph} box {x0},{y0} {w}x{h} bytes {len(body)}',
          ' '.join(f'{d}:{n}px/max{m}' for d, n, m, _ in sizes), flush=True)
open(os.path.join(out_dir, 'features.txt'), 'w').write('\n'.join(text) + '\n')
