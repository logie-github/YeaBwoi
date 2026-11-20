#!/usr/bin/env python3
"""Makes the film's pictures into something to watch (tools/film/README.md): an animated GIF at reduced size (kept under
--max-mb by dropping frames and colours), an MP4 when ffmpeg is there, and contact sheets of the morning (07-11), noon
(11-14) and night (21-03) with each picture's time.

    python3 -I tools/film/assemble.py OVERLAID_DIR OUT_PREFIX [--fps 8] [--width 480] [--max-mb 8] [--title TEXT]

OVERLAID_DIR is overlay.py's output (o_NNNNN.png and index.jsonl). Writes OUT_PREFIX.gif, OUT_PREFIX.mp4,
OUT_PREFIX_morning.png, OUT_PREFIX_noon.png, OUT_PREFIX_night.png.
"""
import json
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont


def opt(argv, name, default):
    return type(default)(argv[argv.index(name) + 1]) if name in argv else default


def gif(frames, path, fps, width, max_mb):
    step, colours = 1, 128
    while True:
        pics = []
        for f in frames[::step]:
            im = Image.open(f).convert("RGB")
            im = im.resize((width, int(im.height * width / im.width)), Image.LANCZOS)
            pics.append(im.quantize(colors=colours, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE))
        pics[0].save(path, save_all=True, append_images=pics[1:], duration=int(1000 * step / fps), loop=0, optimize=True)
        mb = os.path.getsize(path) / 1e6
        if mb <= max_mb or step >= 8:
            return mb, step, colours
        if colours > 64:
            colours = 64
        else:
            step *= 2


def sheet(index, folder, path, ranges, title, cols=4, n=12):
    chosen = [e for e in index if any(a <= e["hour"] % 24 < b for a, b in ranges)]
    if not chosen:
        return 0
    pick = [chosen[int(i * (len(chosen) - 1) / max(1, n - 1))] for i in range(min(n, len(chosen)))]
    first = Image.open(os.path.join(folder, pick[0]["png"]))
    tw, th = first.width // 2, first.height // 2
    rows = (len(pick) + cols - 1) // cols
    out = Image.new("RGB", (cols * tw, rows * th + 28), (20, 20, 24))
    d = ImageDraw.Draw(out)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
    except OSError:
        f = ImageFont.load_default()
    d.text((8, 5), title, font=f, fill=(255, 220, 120))
    for k, e in enumerate(pick):
        im = Image.open(os.path.join(folder, e["png"])).convert("RGB").resize((tw, th), Image.LANCZOS)
        out.paste(im, ((k % cols) * tw, 28 + (k // cols) * th))
    out.save(path)
    return len(pick)


def main(argv):
    folder, prefix = argv[0], argv[1]
    fps, width, max_mb, title = opt(argv, "--fps", 8), opt(argv, "--width", 480), opt(argv, "--max-mb", 8.0), opt(argv, "--title", "")
    with open(os.path.join(folder, "index.jsonl")) as f:
        index = [json.loads(l) for l in f]
    frames = [os.path.join(folder, e["png"]) for e in index]
    mb, step, colours = gif(frames, prefix + ".gif", fps, width, max_mb)
    print("%s.gif: %d frames, %.1f MB (every %d, %d colours)" % (prefix, len(frames[::step]), mb, step, colours))
    if shutil.which("ffmpeg"):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(fps), "-i", os.path.join(folder, "o_%05d.png"),
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", prefix + ".mp4"], check=False)
        if os.path.exists(prefix + ".mp4"):
            print("%s.mp4: %.1f MB" % (prefix, os.path.getsize(prefix + ".mp4") / 1e6))
    for name, ranges in (("morning", [(7, 11)]), ("noon", [(11, 14)]), ("night", [(21, 24), (0, 3)])):
        n = sheet(index, folder, "%s_%s.png" % (prefix, name), ranges, "%s - %s" % (title, name))
        print("%s_%s.png: %d pictures" % (prefix, name, n))


if __name__ == "__main__":
    main(sys.argv[1:])
