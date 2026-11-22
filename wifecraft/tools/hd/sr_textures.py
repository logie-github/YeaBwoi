"""Desktop-only comparison pass: one texture (PNG) enlarged 2x by the Swin2SR lightweight ONNX model (Xenova/swin2SR-lightweight-x2-64 on Hugging Face, 8 MB), tiled, CPU. Usage: python3 -I sr_textures.py MODEL.onnx IN.png OUT.png [PASSES]. Never part of the app: the phone uses creator/HdTextures.kt (minutes per 512 px texture here on 4 cores)."""
import sys, time, numpy as np, onnxruntime as ort
from PIL import Image
model, src, dst, passes = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 1
s = ort.InferenceSession(model, providers=['CPUExecutionProvider'])
T, O = 96, 8   # tile and overlap (in source pixels)
def up2(a):
    h, w, _ = a.shape
    out = np.zeros((h * 2, w * 2, 3), np.float32); wt = np.zeros((h * 2, w * 2, 1), np.float32)
    step = T - 2 * O
    ys = list(range(0, max(h - T, 0) + 1, step)); xs = list(range(0, max(w - T, 0) + 1, step))
    if ys[-1] + T < h: ys.append(h - T)
    if xs[-1] + T < w: xs.append(w - T)
    for y in ys:
        for x in xs:
            t = a[y:y+T, x:x+T]
            r = s.run(None, {'pixel_values': t.transpose(2,0,1)[None]})[0][0].transpose(1,2,0)
            m = np.ones((T*2, T*2, 1), np.float32)
            ramp = np.linspace(0, 1, 2*O, endpoint=False)[:, None, None].astype(np.float32) + 0.5/(2*O)
            if y > 0: m[:2*O] *= ramp
            if x > 0: m[:, :2*O] *= ramp.transpose(1, 0, 2)
            if y + T < h: m[-2*O:] *= ramp[::-1]
            if x + T < w: m[:, -2*O:] *= ramp[::-1].transpose(1, 0, 2)
            out[y*2:(y+T)*2, x*2:(x+T)*2] += r * m; wt[y*2:(y+T)*2, x*2:(x+T)*2] += m
    return out / np.maximum(wt, 1e-6)
im = Image.open(src); alpha = im.getchannel('A') if im.mode == 'RGBA' else None
a = np.asarray(im.convert('RGB'), np.float32) / 255
t0 = time.time()
for _ in range(passes): a = np.clip(up2(a), 0, 1)
out = Image.fromarray((a * 255 + .5).astype(np.uint8))
if alpha is not None: out = Image.merge('RGBA', (*out.split(), alpha.resize(out.size, Image.BICUBIC)))
out.save(dst); print(src, '->', out.size, '%.1fs' % (time.time() - t0))
