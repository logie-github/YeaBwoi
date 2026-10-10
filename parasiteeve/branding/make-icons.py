# Builds the Parasite Eve app's launcher icon layers: Aya's face, cropped from the header art
# (header.jpg) and centered, and for Android's themed icons the swirling "PE" from the logo.
#   python3 make-icons.py header.jpg logo.png ../android/app/src/main/res
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

header_path, logo_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
header = Image.open(header_path).convert('RGB')
logo = Image.open(logo_path).convert('RGBA')

# Aya's face in the header, and how far around it the icon's full 108dp square reaches (the
# launcher shows the middle 72dp of it).
FACE_X, FACE_Y, HALF = 1325, 235, 235


def background(px):
    """Aya's face filling the icon, darkening a little toward its edges."""
    crop = header.crop((FACE_X - HALF, FACE_Y - HALF, FACE_X + HALF, FACE_Y + HALF)).resize((px, px), Image.LANCZOS)
    shade = Image.new('L', (px, px), 0)
    d = ImageDraw.Draw(shade)
    steps = 40
    for i in range(steps):
        t = i / steps
        inset = px * 0.5 * t
        d.ellipse([inset - px * 0.25, inset - px * 0.25, px - inset + px * 0.25, px - inset + px * 0.25], fill=int(110 * (1 - t) ** 2))
    dark = Image.new('RGB', (px, px), (2, 6, 13))
    return Image.composite(dark, crop, shade).convert('RGBA')


def foreground(px):
    """Nothing in front: the picture is the icon."""
    return Image.new('RGBA', (px, px), (0, 0, 0, 0))


def swirl():
    """The logo's teal "PE" strokes alone, without the lettering and its white edge."""
    pixels = logo.load()
    mask = Image.new('L', logo.size, 0)
    m = mask.load()
    for y in range(logo.height):
        for x in range(logo.width):
            r, g, b, a = pixels[x, y]
            if a > 128 and g > r + 25 and b > r + 10 and g > 70:
                m[x, y] = 255
    # The print's grain leaves holes: close them, then smooth the edges.
    mask = mask.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(4))
    mask = mask.point(lambda v: 255 if v > 110 else 0).filter(ImageFilter.GaussianBlur(1.5))
    return mask.crop(mask.getbbox())


def monochrome(px, shape):
    """The "PE" swirl in white, inside the themed icon's safe zone."""
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    target = int(px * 0.6)
    k = target / max(shape.size)
    s = shape.resize((max(1, int(shape.width * k)), max(1, int(shape.height * k))), Image.LANCZOS)
    white = Image.new('RGBA', s.size, (255, 255, 255, 255))
    img.paste(white, ((px - s.width) // 2, (px - s.height) // 2), s)
    return img


shape = swirl()
densities = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for name, k in densities.items():
    d = os.path.join(out, 'mipmap-' + name)
    os.makedirs(d, exist_ok=True)
    px = int(108 * k)
    background(px).save(os.path.join(d, 'ic_launcher_background.png'), optimize=True)
    foreground(px).save(os.path.join(d, 'ic_launcher_foreground.png'), optimize=True)
    monochrome(px, shape).save(os.path.join(d, 'ic_launcher_monochrome.png'), optimize=True)

# Preview of the icon as a circle, a rounded square, and themed.
size = 432
icon = background(size)
preview = Image.new('RGBA', (size * 3 + 80, size + 40), (230, 230, 230, 255))
circle = Image.new('L', (size, size), 0)
ImageDraw.Draw(circle).ellipse([72, 72, 360, 360], fill=255)
square = Image.new('L', (size, size), 0)
ImageDraw.Draw(square).rounded_rectangle([72, 72, 360, 360], radius=70, fill=255)
preview.paste(icon, (20, 20), circle)
preview.paste(icon, (size + 40, 20), square)
themed = Image.new('RGBA', (size, size), (36, 52, 64, 255))
themed.alpha_composite(monochrome(size, shape))
preview.paste(themed, (2 * size + 60, 20), circle)
preview.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'preview.png'))
