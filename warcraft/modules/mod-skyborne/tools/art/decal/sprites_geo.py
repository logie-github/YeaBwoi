import sys, os, io, json, struct, subprocess, numpy as np
sys.path.insert(0, 'tools'); import mpq
from PIL import Image
from scipy.ndimage import distance_transform_edt

os.makedirs('decal/sprites', exist_ok=True)
m = mpq.Mpq('Patch-H.MPQ'); low = {n.lower(): n for n in m.listfile()}
d = m.read(low['character\\scourge\\female\\scourgefemale.m2']); s = m.read(low['character\\scourge\\female\\scourgefemale00.skin'])
nv, ov = struct.unpack_from('<II', d, 0x3C)
V = [struct.unpack_from('<3f4B4B3f2f2f', d, ov + 48 * i) for i in range(nv)]
h = struct.unpack_from('<4s10I', s, 0)
idx = struct.unpack_from('<%dH' % h[1], s, h[2]); tri = struct.unpack_from('<%dH' % h[3], s, h[4])
subs = [struct.unpack_from('<6H', s, h[8] + 48 * i) for i in range(h[7])]
base = dict(pos=[c for v in V for c in v[0:3]], nor=[c for v in V for c in v[11:14]], uv=[c for v in V for c in v[14:16]])
P = np.array(base['pos']).reshape(-1, 3)
Image.open(io.BytesIO(m.read(low['character\\scourge\\female\\featurestexture.blp']))).convert('RGB').save('r3d/features.png')
Image.new('RGB', (8, 8), (255, 255, 255)).save('r3d/white.png')
def model(geoset, tex):
    out = dict(base, batches=[])
    for i in range(h[9]):
        b = struct.unpack_from('<BBHHHHHHHHHHH', s, h[10] + 24 * i)
        sid, lvl, vs, vc, ts, tc = subs[b[3]]
        if sid != geoset:
            continue
        st = ts + (lvl << 16)
        ind = np.array([idx[tri[st + k]] for k in range(tc)]).reshape(-1, 3)
        keep = (P[ind][:, :, 0] > 0.07).all(1)
        out['batches'].append(dict(geoset=sid, texType=0, blend=0, tex=tex, ind=ind[keep].ravel().tolist()))
    return out
for geoset, out in ((302, 'straps'), (304, 'shards')):
    json.dump(model(geoset, 'features.png'), open(f'r3d/model_spr_{geoset}.json', 'w'))
    json.dump(model(geoset, 'white.png'), open(f'r3d/model_spr_{geoset}_w.json', 'w'))
    for suffix in ('', '_w'):
        url = f'http://127.0.0.1:8765/index.html?model=model_spr_{geoset}{suffix}.json&skin=skin_Un00.png&extra=extra_Scourge.png&cam=0,1.80,-0.6,0,1.80,0.1&ortho=0.22&w=1000&h=1000&unlit=1'
        subprocess.run(['node', 'shot.js', url, f'spr_{geoset}{suffix}.png', '1000', '1000'], cwd='r3d', check=True, capture_output=True)
    col = np.asarray(Image.open(f'r3d/spr_{geoset}.png').convert('RGB')).astype(float)
    wh = np.asarray(Image.open(f'r3d/spr_{geoset}_w.png').convert('RGB')).astype(float).mean(2)
    cover = np.clip((wh - 70) / 180, 0, 1)
    inner = cover > 0.97
    _, (iy, ix) = distance_transform_edt(~inner, return_indices=True)
    ys, xs = np.where(cover > 0.05)
    rgba = np.dstack([col[iy, ix], cover * 255]).astype(np.uint8)[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    Image.fromarray(rgba, 'RGBA').save(f'decal/sprites/{out}.png'); print(out, rgba.shape)
