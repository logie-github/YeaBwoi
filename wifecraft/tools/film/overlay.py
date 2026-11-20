#!/usr/bin/env python3
"""Puts the scene's words over the film's frames (tools/film/README.md): each person's speech bubble at their head (where
engine-test/film projected it), their thought in a smaller grey cloud when they say nothing, their name and what life says
they are doing, the clock and the place in the corner, and the forced moments (cues) as a caption.

    python3 -I tools/film/overlay.py TRACK.jsonl FRAMES_DIR OUT_DIR

FRAMES_DIR holds film's f_NNNNN.png and frames.jsonl; OUT_DIR gets o_NNNNN.png (same size) and index.jsonl (each picture's
hour, for the contact sheets).
"""
import json
import os
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont


def font(size, bold=False, italic=False):
    names = ["DejaVuSans-Bold.ttf" if bold else "DejaVuSans-Oblique.ttf" if italic else "DejaVuSans.ttf"]
    for n in names:
        for d in ("/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/dejavu"):
            p = os.path.join(d, n)
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def clock(hours):
    h = hours % 24
    m = int((h - int(h)) * 60)
    return "%02d:%02d" % (int(h), m)


LIFE_WORDS = {"EAT": "eating", "DRINK": "drinking", "SLEEP": "asleep", "NAP": "napping", "READ": "reading", "DANCE": "dancing",
              "CHAT": "talking", "RELAX": "relaxing", "REST": "resting", "STRETCH": "stretching", "TIDY": "freshening up",
              "INVITE_SIT": "sitting together", "PLAY_TOGETHER": "together", "DANCE_TOGETHER": "dancing together",
              "HUG": "hugging", "KISS": "kissing", "JOKE": "joking", "WANDER": "wandering"}


def main(track, frames_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    header, records, cues = None, [], []
    with open(track) as f:
        for line in f:
            d = json.loads(line)
            if d["type"] == "header":
                header = d
            elif d["type"] == "frame":
                records.append(d)
            elif d["type"] == "cue" and "answer" not in d and all(c["label"] != d["label"] for c in cues):
                cues.append(d)   # a retried cue (tried again 5 s later) is captioned once
    names = [c["name"].split(" ")[0] for c in header["cast"]]
    with open(os.path.join(frames_dir, "frames.jsonl")) as f:
        side = [json.loads(l) for l in f]
    index = open(os.path.join(out_dir, "index.jsonl"), "w")
    for s in side:
        im = Image.open(os.path.join(frames_dir, s["png"])).convert("RGB")
        w, h = im.size
        k = max(0.6, w / 640.0)
        f_say, f_name, f_think, f_clock = font(int(12 * k)), font(int(11 * k), bold=True), font(int(11 * k), italic=True), font(int(14 * k), bold=True)
        fr = records[s["record"]]
        d = ImageDraw.Draw(im, "RGBA")
        # far ones first: nearer bubbles drawn over them
        order = sorted(range(len(s["heads"])), key=lambda i: s["heads"][i][1])
        for i in order:
            hx, hy, vis = s["heads"][i]
            if not vis or hx < -50 or hx > w + 50 or hy < -50 or hy > h + 50:
                continue
            p = fr["people"][i]
            doing = LIFE_WORDS.get(p["doing"] or "", "")
            label = names[i] + (" - " + doing if doing else "")
            tw = d.textlength(label, font=f_name)
            y = hy - 4 * k
            d.rectangle([hx - tw / 2 - 3, y - 14 * k, hx + tw / 2 + 3, y], fill=(0, 0, 0, 140))
            d.text((hx - tw / 2, y - 13 * k), label, font=f_name, fill=(255, 210, 0, 255))
            y -= 16 * k
            text, think = p.get("say"), None
            if not text and p.get("thought"):
                think = p["thought"]
            if text or think:
                lines = textwrap.wrap(text or think, 30)[:4]
                fnt = f_say if text else f_think
                lw = max(d.textlength(l, font=fnt) for l in lines)
                lh = 14 * k
                bx0, by1 = hx - lw / 2 - 6, y - 4
                by0 = by1 - lh * len(lines) - 8
                bx0 = min(max(bx0, 2), w - lw - 14)
                fill = (255, 255, 255, 235) if text else (220, 225, 235, 200)
                d.rounded_rectangle([bx0, by0, bx0 + lw + 12, by1], radius=int(6 * k), fill=fill, outline=(40, 40, 40, 255))
                if text:
                    d.polygon([(hx - 5, by1), (hx + 5, by1), (hx, by1 + 7)], fill=fill)
                else:
                    d.ellipse([hx - 4, by1 + 2, hx + 2, by1 + 8], fill=fill)
                for n, l in enumerate(lines):
                    d.text((bx0 + 6, by0 + 4 + n * lh), l, font=fnt, fill=(20, 20, 20, 255) if text else (60, 60, 80, 255))
        tag = "Day %d  %s   %s" % (fr["day"], clock(fr["hour"]), header["name"])
        d.rectangle([0, 0, d.textlength(tag, font=f_clock) + 14, 22 * k], fill=(0, 0, 0, 160))
        d.text((7, 3 * k), tag, font=f_clock, fill=(255, 255, 255, 255))
        for c in cues:
            if 0 <= fr["t"] - c["t"] < 6000:
                cap = c["label"]
                cw = d.textlength(cap, font=f_say)
                d.rectangle([(w - cw) / 2 - 8, h - 24 * k, (w + cw) / 2 + 8, h - 4 * k], fill=(60, 0, 0, 170))
                d.text(((w - cw) / 2, h - 22 * k), cap, font=f_say, fill=(255, 255, 255, 255))
        name = s["png"].replace("f_", "o_")
        im.save(os.path.join(out_dir, name))
        index.write(json.dumps({"png": name, "t": fr["t"], "hour": fr["hour"], "day": fr["day"]}) + "\n")
    index.close()


if __name__ == "__main__":
    main(*sys.argv[1:4])
