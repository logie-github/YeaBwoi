# Builds V3 Gold's launcher icon layers from the P4 sticker artwork.
import sys, random
from PIL import Image, ImageDraw, ImageFilter

src, out = sys.argv[1], sys.argv[2]
sticker = Image.open(src).convert('RGBA')
sticker = sticker.crop(sticker.getbbox())

INK = (27, 21, 18, 255)

def background(px):
    """Ink black with a faint grid of dark gold tiles, like the menu animation at night."""
    img = Image.new('RGBA', (px, px), INK)
    tiles = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(tiles)
    cell = px / 9
    gap = cell * 0.12
    rnd = random.Random(4)
    for r in range(10):
        for c in range(10):
            a = rnd.choice([0, 0, 0, 18, 28, 40])
            if a:
                x, y = c * cell + gap / 2, r * cell + gap / 2
                d.rectangle([x, y, x + cell - gap, y + cell - gap], fill=(242, 195, 24, a))
    img.alpha_composite(tiles)
    return img

def foreground(px, scale):
    """The sticker centered in the adaptive icon's 72/108 safe zone."""
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    target = int(px * scale)
    w, h = sticker.size
    k = target / max(w, h)
    s = sticker.resize((max(1, int(w * k)), max(1, int(h * k))), Image.LANCZOS)
    img.alpha_composite(s, ((px - s.width) // 2, (px - s.height) // 2))
    return img

def monochrome(px, scale):
    """The white "P4" letters only, for Android's themed icons."""
    fg = foreground(px, scale)
    mask = Image.new('L', fg.size, 0)
    pix = fg.load()
    mp = mask.load()
    for y in range(px):
        for x in range(px):
            r, g, b, a = pix[x, y]
            if a > 128 and r > 225 and g > 225 and b > 225:
                mp[x, y] = 255
    mask = mask.filter(ImageFilter.MaxFilter(3))
    img = Image.new('RGBA', fg.size, (0, 0, 0, 0))
    img.putalpha(mask)
    white = Image.new('RGBA', fg.size, (255, 255, 255, 255))
    white.putalpha(mask)
    return white

densities = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
import os
for name, k in densities.items():
    d = os.path.join(out, 'mipmap-' + name)
    os.makedirs(d, exist_ok=True)
    px = int(108 * k)
    background(px).save(os.path.join(d, 'gold_icon_background.png'), optimize=True)
    foreground(px, 0.62).save(os.path.join(d, 'gold_icon_foreground.png'), optimize=True)
    monochrome(px, 0.62).save(os.path.join(d, 'gold_icon_monochrome.png'), optimize=True)

def flat_icon(px):
    """The icon as one rounded-square image: ic_launcher, which Vita3K's screens, its splash
    screen and its notifications draw directly (they can't draw the layered launcher icon)."""
    full = background(px * 2)
    full.alpha_composite(foreground(px * 2, 0.84))
    mask = Image.new('L', full.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, px * 2 - 1, px * 2 - 1], radius=int(px * 2 * 0.22), fill=255)
    full.putalpha(mask)
    return full.resize((px, px), Image.LANCZOS)

# Vita3K shows it at up to 132dp.
for name, k in [('mipmap', 1)] + [('mipmap-' + n, k) for n, k in densities.items()]:
    d = os.path.join(out, name)
    os.makedirs(d, exist_ok=True)
    flat_icon(int(144 * k)).save(os.path.join(d, 'ic_launcher.png'), optimize=True)
# Preview of the masked icon.
prev = background(432); prev.alpha_composite(foreground(432, 0.62))
m = Image.new('L', (432, 432), 0); ImageDraw.Draw(m).ellipse([18, 18, 414, 414], fill=255)
pv = Image.new('RGBA', (432, 432), (230, 230, 230, 255)); pv.paste(prev, (0, 0), m)
pv.save(os.path.join(out, 'preview.png'))
