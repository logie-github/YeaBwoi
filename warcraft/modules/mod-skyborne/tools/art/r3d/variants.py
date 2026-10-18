# variants.py RACE TAG CAM OUT.png STYLE_LEVEL "SKIN:HAIR ..." name=json ...   (a sheet of one outline's trial settings)
import sys, json, os, subprocess
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageDraw
S = '/tmp/claude-0/-home-user-YeaBwoi/c06350a4-89d8-5aa6-9491-88b79be5b5d2/scratchpad'
race, tag, cam, out, lvl, combos = sys.argv[1:7]
variants = [a.split('=', 1) for a in sys.argv[7:]]
combos = combos.split()
jobs = [(sk, hh, name, js) for sk, hh in (c.split(':') for c in combos) for name, js in variants]
def run(j):
    sk, hh, name, js = j
    key = f'{tag}_{hh}_{name}'
    env = dict(os.environ, CFG_OVER=js)
    subprocess.run(['python3', '-I', 'decal/hairpatch5.py', race, f'Character\\{race}\\Hair00_{hh}.blp', lvl, f'r3d/v_{key}.png', '0.33'], cwd=S, env=env, check=True, capture_output=True)
    subprocess.run(['python3', '-I', 'decal/export_local.py', f'decal/{race}FemaleShells.m2', f'decal/{race}FemaleShells00.skin', f'r3d/model_V_{key}.json', f'v_{key}.png'], cwd=S, check=True, capture_output=True)
    url = f'http://127.0.0.1:8765/index.html?model=model_V_{key}.json&skin=skin_{tag}d{sk}.png&extra=extra_{race}.png&cam={cam}&fov=20&w=380&h=380'
    subprocess.run(['node', 'shot.js', url, f's_V_{key}_{sk}.png', '380', '380'], cwd=S + '/r3d', check=True, capture_output=True)
with ThreadPoolExecutor(6) as ex:
    list(ex.map(run, jobs))
W = 380 * len(variants); c = Image.new('RGB', (W, 400 * len(combos)), (43, 43, 51)); d = ImageDraw.Draw(c)
for r, cb in enumerate(combos):
    sk, hh = cb.split(':')
    d.text((8, r * 400 + 4), f'skin {sk}, hair {hh}', fill=(255, 255, 255))
    for i, (name, js) in enumerate(variants):
        c.paste(Image.open(f'{S}/r3d/s_V_{tag}_{hh}_{name}_{sk}.png'), (380 * i, r * 400 + 20)); d.text((380 * i + 8, r * 400 + 24), name, fill=(255, 255, 255))
c.save(f'{S}/{out}'); print(c.size)
