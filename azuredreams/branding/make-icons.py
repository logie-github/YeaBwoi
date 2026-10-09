# Builds the Azure Dreams app's launcher icon layers from the blue "AD" monogram (icon.png).
#   python3 make-icons.py icon.png ../android/app/src/main/res
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter

src, out = sys.argv[1], sys.argv[2]
mono = Image.open(src).convert('RGBA')
mono = mono.crop(mono.getbbox())


def background(px):
    """A twilight sky: deep blue fading to night, a soft azure glow behind the letters and a few
    pixel stars, under faint CRT scanlines."""
    img = Image.new('RGBA', (px, px))
    top, bottom = (16, 58, 140), (3, 10, 31)
    d = ImageDraw.Draw(img)
    for y in range(px):
        t = y / (px - 1)
        d.line([(0, y), (px, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    glow = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([px * 0.18, px * 0.2, px * 0.82, px * 0.8], fill=(61, 169, 240, 120))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(px * 0.12)))
    stars = ImageDraw.Draw(img)
    rnd = random.Random(7)
    dot = max(1, px // 108)
    for _ in range(26):
        x, y = rnd.randrange(px), rnd.randrange(px)
        size = dot * rnd.choice([1, 1, 2])
        color = rnd.choice([(124, 196, 247), (124, 196, 247), (246, 197, 49)])
        stars.rectangle([x, y, x + size - 1, y + size - 1], fill=color + (rnd.randrange(90, 200),))
    lines = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lines)
    step = max(3, px // 54)
    for y in range(0, px, step):
        ld.line([(0, y), (px, y)], fill=(0, 0, 0, 40), width=max(1, step // 3))
    img.alpha_composite(lines)
    return img


def foreground(px, scale):
    """The monogram centered in the adaptive icon's 72/108 safe zone, with a dark drop shadow."""
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    target = int(px * scale)
    w, h = mono.size
    k = target / max(w, h)
    s = mono.resize((max(1, int(w * k)), max(1, int(h * k))), Image.LANCZOS)
    x, y = (px - s.width) // 2, (px - s.height) // 2
    shadow = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    black = Image.new('RGBA', s.size, (2, 6, 20, 170))
    shadow.paste(black, (x + px // 60, y + px // 60), s)
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(px / 120)))
    img.alpha_composite(s, (x, y))
    return img


def monochrome(px, scale):
    """The monogram's shape in white, for Android's themed icons."""
    fg = foreground(px, scale)
    alpha = fg.getchannel('A').point(lambda a: 255 if a > 140 else 0)
    white = Image.new('RGBA', fg.size, (255, 255, 255, 255))
    white.putalpha(alpha)
    return white


densities = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for name, k in densities.items():
    d = os.path.join(out, 'mipmap-' + name)
    os.makedirs(d, exist_ok=True)
    px = int(108 * k)
    background(px).save(os.path.join(d, 'ic_launcher_background.png'), optimize=True)
    foreground(px, 0.64).save(os.path.join(d, 'ic_launcher_foreground.png'), optimize=True)
    monochrome(px, 0.64).save(os.path.join(d, 'ic_launcher_monochrome.png'), optimize=True)

# Preview of the masked icon.
prev = background(432)
prev.alpha_composite(foreground(432, 0.64))
mask = Image.new('L', (432, 432), 0)
ImageDraw.Draw(mask).ellipse([18, 18, 414, 414], fill=255)
preview = Image.new('RGBA', (432, 432), (230, 230, 230, 255))
preview.paste(prev, (0, 0), mask)
preview.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'preview.png'))
