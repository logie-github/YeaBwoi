# Builds Wifecraft's launcher icon layers from the user's emblem (icon.png: the blue-violet "W" in
# its ring, on black). The emblem fills the adaptive icon's safe zone on a dark violet ground;
# Android's themed icons get the "W" and its ring in white.
#   python3 make-icons.py icon.png ../android/app/src/main/res
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

icon_path, out = sys.argv[1], sys.argv[2]
emblem = Image.open(icon_path).convert('RGB')


def emblem_rgba():
    """The emblem with its black surround made transparent."""
    e = emblem.convert('RGBA')
    px = e.load()
    for y in range(e.height):
        for x in range(e.width):
            r, g, b, a = px[x, y]
            v = max(r, g, b)
            if v < 40:
                px[x, y] = (r, g, b, int(255 * v / 40))
    return e.crop(e.getbbox())


EMBLEM = emblem_rgba()


def background(px):
    """A dark violet ground, lighter in the middle, like Dalaran's night."""
    img = Image.new('RGB', (px, px), (14, 9, 34))
    glow = Image.new('L', (px, px), 0)
    ImageDraw.Draw(glow).ellipse([px * 0.15, px * 0.15, px * 0.85, px * 0.85], fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(px * 0.12))
    img = Image.composite(Image.new('RGB', (px, px), (58, 40, 120)), img, glow)
    return img.convert('RGBA')


def foreground(px):
    """The emblem, inside the 72dp the launcher shows of the 108dp square."""
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    target = int(px * 0.62)
    e = EMBLEM.resize((target, target), Image.LANCZOS)
    img.alpha_composite(e, ((px - target) // 2, (px - target) // 2))
    return img


def monochrome(px):
    """The emblem's bright strokes in white, for themed icons."""
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    target = int(px * 0.6)
    e = EMBLEM.resize((target, target), Image.LANCZOS)
    r, g, b, a = e.split()
    lum = Image.eval(Image.merge('RGB', (r, g, b)).convert('HSV').split()[2], lambda v: 255 if v > 175 else 0)
    lum = lum.filter(ImageFilter.MedianFilter(5)).filter(ImageFilter.GaussianBlur(0.8))
    white = Image.new('RGBA', e.size, (255, 255, 255, 255))
    img.paste(white, ((px - target) // 2, (px - target) // 2), lum)
    return img


densities = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for name, k in densities.items():
    d = os.path.join(out, 'mipmap-' + name)
    os.makedirs(d, exist_ok=True)
    px = int(108 * k)
    background(px).save(os.path.join(d, 'ic_launcher_background.png'), optimize=True)
    foreground(px).save(os.path.join(d, 'ic_launcher_foreground.png'), optimize=True)
    monochrome(px).save(os.path.join(d, 'ic_launcher_monochrome.png'), optimize=True)

# Preview: a circle, a rounded square, and themed.
size = 432
icon = background(size)
icon.alpha_composite(foreground(size))
preview = Image.new('RGBA', (size * 3 + 80, size + 40), (230, 230, 230, 255))
circle = Image.new('L', (size, size), 0)
ImageDraw.Draw(circle).ellipse([72, 72, 360, 360], fill=255)
square = Image.new('L', (size, size), 0)
ImageDraw.Draw(square).rounded_rectangle([72, 72, 360, 360], radius=70, fill=255)
preview.paste(icon, (20, 20), circle)
preview.paste(icon, (size + 40, 20), square)
themed = Image.new('RGBA', (size, size), (36, 40, 64, 255))
themed.alpha_composite(monochrome(size))
preview.paste(themed, (2 * size + 60, 20), circle)
preview.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'preview.png'))
