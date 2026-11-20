"""Before and after pictures of the same audit cases (tools/audit/README.md): each case taken from two audit runs (the same
case id in both plans), drawn with one camera set (front, three-quarter, side; the first run's centre) at up to four of its
measured moments, one picture per case: the first run's rows over the second's, with the measures of both in a caption.

  python3 -I compare.py BEFORE_DIR AFTER_DIR OUT_DIR ID [ID...] [--audit BIN] [--title "words"]"""
import json
import os
import subprocess
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import renders  # noqa: E402

W, H = 240, 280


def caption(w):
    if not w:
        return "not played here"
    def r(v):
        return "-" if v is None else f"{v:.2f}"
    return (f"{w['verdict']}: bodies in each other {r(w['overlap'])} yd, in furniture {r(w['furniture'])} yd, "
            f"floats {r(w['float'])} yd, under floor {r(w['sink'])} yd, pelvises {r(w['pelvis'])} yd apart"
            + (", SAME AXIS" if w.get("same") else ""))


def main():
    args = sys.argv[1:]
    binary = args[args.index("--audit") + 1] if "--audit" in args else os.path.join(os.path.dirname(__file__), "../../engine-test/build/audit")
    title = args[args.index("--title") + 1] if "--title" in args else ""
    pos = [a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] not in ("--audit", "--title"))]
    before, after, out = pos[:3]
    ids = pos[3:]
    os.makedirs(out, exist_ok=True)
    runs = []
    for d in (before, after):
        rooms, cases = renders.blocks(d)
        rep = json.load(open(os.path.join(d, "report.json"))) if os.path.exists(os.path.join(d, "report.json")) else {}
        runs.append((d, rooms, cases, rep))
    for c in ids:
        ref = next((r[2][c] for r in runs if c in r[2]), None)
        if ref is None:
            print(f"{c}: in neither run")
            continue
        k = renders.keys_of(ref[1])
        times = [int(l.split()[1]) for l in ref[1] if l.startswith("measure ")]
        times = times[:: max(1, len(times) // 4)][:4] if len(times) > 1 else times
        cams = renders.cameras(k)
        grids = []
        for n, (d, rooms, cases, rep) in enumerate(runs):
            pics = []
            if c in cases:
                room, block = cases[c]
                plan = list(rooms[room]) + [l for l in block if not l.startswith("measure ")]
                for t in times:
                    for name, cam in cams:
                        png = os.path.join(out, f"{c}_{n}_{name}_{t}.png")
                        plan += [cam + "\n", f"render {png} {W} {H} {t}\n"]
                        pics.append((f"{'before' if n == 0 else 'after'} {name} {t} ms", png))
                path = os.path.join(out, f"{c}_{n}.plan")
                open(path, "w").writelines(plan)
                subprocess.run([binary, path, os.devnull], check=True, stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
            grids.append((pics, caption(rep.get(c))))
        cols = 3 * len(times)
        sheet = Image.new("RGB", (cols * W, 2 * H + 3 * 22 + 8), (22, 22, 26))
        d = ImageDraw.Draw(sheet)
        d.text((6, 4), f"{c}  {title}", fill=(255, 230, 120))
        for n, (pics, cap) in enumerate(grids):
            y = 24 + n * (H + 22)
            for i, (label, png) in enumerate(pics):
                if os.path.exists(png):
                    sheet.paste(Image.open(png).convert("RGB"), (i * W, y))
                d.text((i * W + 4, y + 4), label, fill=(255, 255, 255))
            d.text((6, y + H + 4), ("BEFORE  " if n == 0 else "AFTER   ") + cap, fill=(200, 255, 200) if n else (255, 190, 190))
        path = os.path.join(out, f"{c}.png")
        sheet.save(path)
        for pics, _ in grids:
            for _, png in pics:
                if os.path.exists(png):
                    os.remove(png)
        print(path)


if __name__ == "__main__":
    main()
