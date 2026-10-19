# Builds the Warcraft app's pictures from the user's art in this folder:
#   python3 make-art.py ../android/app/src/main/res
#
# - drawable-nodpi/: each game's header (hero) and loading picture (splash), as JPEG, and its logo,
#   as WebP with its transparency, at sizes a phone's screen needs;
# - mipmap-*/: the launcher icon (the orc portrait in its frame, icon.png) as adaptive layers: the
#   whole framed portrait over the frame's dark wood (Samsung's rounded square shows it all; a
#   round mask trims the frame's corners), and a white silhouette of the face for themed icons.
import os
import sys

from PIL import Image, ImageFilter, ImageOps

here = os.path.dirname(os.path.abspath(__file__))
res = sys.argv[1]
nodpi = os.path.join(res, 'drawable-nodpi')
os.makedirs(nodpi, exist_ok=True)

# name -> (source, longest side)
PICTURES = {
    'wc1_header': ('wc1/hero.jpg', 2400),
    'wc1_loading': ('wc1/splash.jpg', 1920),
    'wc2_header': ('wc2/hero.jpg', 1920),
    'wc2_loading': ('wc2/splash.jpg', 2400),
    'roc_header': ('wc3/hero-roc.jpg', 2400),
    'tft_header': ('wc3/hero-tft.jpg', 2400),
    'wc3_loading': ('wc3/splash.jpg', 1920),
}
LOGOS = {
    'wc1_logo': ('wc1/logo.png', 1400),
    'wc2_logo': ('wc2/logo.png', 1200),
    'roc_logo': ('wc3/logo-roc.png', 1000),
    'tft_logo': ('wc3/logo-tft.webp', 1400),
}


def fit(img, longest):
    scale = longest / max(img.size)
    if scale >= 1:
        return img
    return img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)


for name, (src, longest) in PICTURES.items():
    img = fit(Image.open(os.path.join(here, src)).convert('RGB'), longest)
    img.save(os.path.join(nodpi, name + '.jpg'), quality=88, optimize=True, progressive=True)

for name, (src, longest) in LOGOS.items():
    img = Image.open(os.path.join(here, src)).convert('RGBA')
    img = img.crop(img.getbbox())
    fit(img, longest).save(os.path.join(nodpi, name + '.webp'), quality=90, method=6)

DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
icon = Image.open(os.path.join(here, 'icon.png')).convert('RGBA')
w, h = icon.size
for density, factor in DENSITIES.items():
    px = int(108 * factor)
    folder = os.path.join(res, 'mipmap-' + density)
    os.makedirs(folder, exist_ok=True)
    # The framed portrait across the visible 72dp of the 108dp layer, on the frame's dark wood.
    Image.new('RGBA', (px, px), (38, 24, 14, 255)).save(os.path.join(folder, 'ic_rts_background.png'))
    size = int(px * 74 / 108)
    fg = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    fg.alpha_composite(icon.resize((size, size), Image.LANCZOS), ((px - size) // 2, (px - size) // 2))
    fg.save(os.path.join(folder, 'ic_rts_foreground.png'))
    inner = icon.crop((int(w * 0.06), int(h * 0.06), int(w * 0.94), int(h * 0.94)))
    # Themed icons: the green of the face, as a white shape.
    face = inner.resize((px, px), Image.LANCZOS)
    mask = Image.new('L', (px, px), 0)
    src = face.load()
    out = mask.load()
    for y in range(px):
        for x in range(px):
            r, g, b, a = src[x, y]
            if a > 128 and g > 70 and g > r + 12 and g > b + 25:
                out[x, y] = 255
    mask = mask.filter(ImageFilter.MedianFilter(5)).filter(ImageFilter.GaussianBlur(factor * 0.6))
    shrink = Image.new('L', (px, px), 0)
    small = mask.resize((int(px * 0.62), int(px * 0.62)), Image.LANCZOS)
    shrink.paste(small, ((px - small.width) // 2, (px - small.height) // 2))
    mono = Image.new('RGBA', (px, px), (255, 255, 255, 0))
    mono.putalpha(shrink)
    mono.save(os.path.join(folder, 'ic_rts_monochrome.png'))
print('done')
