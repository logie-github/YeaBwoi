# Builds the World of Warcraft app's launcher icon layers from the user's icon pack: the WotLK "W"
# medallion (the default icon) and the Cataclysm one (for the in-app icon switcher), each on a
# background of its own colour, and for Android's themed icons the "W" alone, in white.
#   python3 make-icons.py icons ../android/app/src/main/res
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

icons_dir, out = sys.argv[1], sys.argv[2]

# name -> (source, background top colour, background bottom colour)
ICONS = {
    'wotlk': ('WoW_WoTLK_Icon_PC.png', (18, 52, 78), (3, 12, 22)),
    'cata': ('WoW_Cata_Icon_PC.png', (70, 26, 10), (12, 6, 4)),
}
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def background(px, top, bottom):
    """A soft glow of the medallion's colour, darkening toward the edges."""
    img = Image.new('RGB', (px, px), bottom)
    glow = Image.new('L', (px, px), 0)
    d = ImageDraw.Draw(glow)
    steps = 48
    for i in range(steps):
        t = i / steps
        r = px * 0.75 * (1 - t)
        d.ellipse([px / 2 - r, px / 2 - r, px / 2 + r, px / 2 + r], fill=int(255 * t ** 0.8))
    img.paste(Image.new('RGB', (px, px), top), (0, 0), glow)
    return img.convert('RGBA')


def foreground(px, medallion):
    """The medallion inside the launcher's 72dp safe zone (a 66dp circle shows whole)."""
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    size = int(px * 70 / 108)
    m = medallion.resize((size, size), Image.LANCZOS)
    img.alpha_composite(m, ((px - size) // 2, (px - size) // 2))
    return img


def w_shape(medallion):
    """The gold "W": the largest patch of warm, bright pixels in the middle of the medallion."""
    src = medallion.load()
    w, h = medallion.size
    on = set()
    for y in range(int(h * 0.15), int(h * 0.85)):
        for x in range(int(w * 0.15), int(w * 0.85)):
            r, g, b, a = src[x, y]
            if a > 128 and r > 140 and g > 80 and r > b + 60 and g > b + 20:
                on.add((x, y))
    best = set()
    seen = set()
    for start in on:
        if start in seen:
            continue
        part, todo = set(), [start]
        seen.add(start)
        while todo:
            x, y = todo.pop()
            part.add((x, y))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    n = (x + dx, y + dy)
                    if n in on and n not in seen:
                        seen.add(n)
                        todo.append(n)
        if len(part) > len(best):
            best = part
    mask = Image.new('L', medallion.size, 0)
    m = mask.load()
    for x, y in best:
        m[x, y] = 255
    # Fill the pinholes the shading leaves, then soften the edge.
    mask = mask.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(0.8))
    return mask.crop(mask.getbbox())


def monochrome(px, shape):
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    target = int(px * 0.5)
    k = target / max(shape.size)
    s = shape.resize((max(1, int(shape.width * k)), max(1, int(shape.height * k))), Image.LANCZOS)
    img.paste(Image.new('RGBA', s.size, (255, 255, 255, 255)), ((px - s.width) // 2, (px - s.height) // 2), s)
    return img


previews = []
for name, (source, top, bottom) in ICONS.items():
    medallion = Image.open(os.path.join(icons_dir, source)).convert('RGBA')
    shape = w_shape(medallion)
    for density, k in DENSITIES.items():
        d = os.path.join(out, 'mipmap-' + density)
        os.makedirs(d, exist_ok=True)
        px = int(108 * k)
        background(px, top, bottom).save(os.path.join(d, f'ic_{name}_background.png'), optimize=True)
        foreground(px, medallion).save(os.path.join(d, f'ic_{name}_foreground.png'), optimize=True)
        monochrome(px, shape).save(os.path.join(d, f'ic_{name}_monochrome.png'), optimize=True)
    size = 432
    icon = background(size, top, bottom)
    icon.alpha_composite(foreground(size, medallion))
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).ellipse([72, 72, 360, 360], fill=255)
    tile = Image.new('RGBA', (size, size), (230, 230, 230, 255))
    tile.paste(icon, (0, 0), mask)
    mono = Image.new('RGBA', (size, size), (40, 60, 70, 255))
    mono.alpha_composite(monochrome(size, shape))
    previews += [tile, mono]

sheet = Image.new('RGBA', (432 * len(previews), 432), (230, 230, 230, 255))
for i, p in enumerate(previews):
    sheet.paste(p, (432 * i, 0))
sheet.convert('RGB').resize((216 * len(previews), 216)).save(os.path.join(os.path.dirname(__file__) or '.', 'preview.png'))
