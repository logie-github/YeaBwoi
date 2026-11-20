"""Contact sheets for the placement audit (tools/audit/README.md): takes cases out of the planner's plans, adds cameras (front,
three-quarter and side of the furniture or the pair, and the same from above for lying cases) and pictures at their measured
moments, runs engine-test/audit on that, and lays the pictures out one row a case with its flags.

  python3 -I renders.py AUDIT_DIR OUT_DIR (--cases ID,ID... | --worst N | --sample) [--report report.json] [--audit BIN]

--sample: one case per animation and placement (the first pairing measured); --worst N: the N worst of report.json."""
import json
import math
import os
import subprocess
import sys

from PIL import Image, ImageDraw

W, H = 260, 300


def blocks(audit_dir):
    """Each room's header lines and its cases' blocks, by case id."""
    rooms, cases = {}, {}
    for name in sorted(os.listdir(audit_dir)):
        if not (name.startswith("plan_") and name.endswith(".txt")):
            continue
        room = name[5:-4]
        head, cur, lines = [], None, []
        for line in open(os.path.join(audit_dir, name)):
            if line.startswith("case "):
                if cur:
                    cases[cur] = (room, lines)
                cur, lines = line.split()[1], [line]
            elif cur is None:
                head.append(line)
            else:
                lines.append(line)
        if cur:
            cases[cur] = (room, lines)
        rooms[room] = head
    return rooms, cases


def keys_of(block):
    return dict(kv.split("=", 1) for kv in block[0].split()[2:] if "=" in kv)


def cameras(k):
    cx, cy, cz, yaw = (float(k[n]) for n in ("cx", "cy", "cz", "fyaw"))
    lying = "lie" in (k.get("rest0"), k.get("rest1"))
    out = []
    for name, turn, dist, up in (("front", 0.0, 3.4, 0.9), ("3q", math.pi / 4, 3.4, 1.0), ("side", math.pi / 2, 3.2, 0.7)):
        a = yaw + turn
        ex, ey = cx + math.cos(a) * dist, cy + math.sin(a) * dist
        if lying and name == "front":
            up, dist = 2.6, 1.2
            ex, ey = cx + math.cos(a) * dist, cy + math.sin(a) * dist
        out.append((name, f"camera {ex:.3f} {ey:.3f} {cz + up:.3f} {cx:.3f} {cy:.3f} {cz + 0.55:.3f} 48"))
    return out


def main():
    audit_dir, out_dir = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    binary = args[args.index("--audit") + 1] if "--audit" in args else os.path.join(os.path.dirname(__file__), "../../engine-test/build/audit")
    report = json.load(open(args[args.index("--report") + 1])) if "--report" in args else \
        (json.load(open(os.path.join(audit_dir, "report.json"))) if os.path.exists(os.path.join(audit_dir, "report.json")) else {})
    rooms, cases = blocks(audit_dir)
    if "--cases" in args:
        chosen = args[args.index("--cases") + 1].split(",")
    elif "--worst" in args:
        n = int(args[args.index("--worst") + 1])
        chosen = [c for c, _ in sorted(report.items(), key=lambda kv: -kv[1]["severity"])[:n]]
    else:
        seen, chosen = set(), []
        for c, (room, b) in cases.items():
            k = keys_of(b)
            key = (k.get("kind"), k.get("anim"), k.get("place"))
            if key not in seen:
                seen.add(key)
                chosen.append(c)
    os.makedirs(out_dir, exist_ok=True)
    pics = {}
    by_room = {}
    for c in chosen:
        if c in cases:
            by_room.setdefault(cases[c][0], []).append(c)
    for room, ids in by_room.items():
        plan = list(rooms[room])
        for c in ids:
            b = cases[c][1]
            k = keys_of(b)
            times = [int(l.split()[1]) for l in b if l.startswith("measure ")]
            t = times[len(times) // 2]
            plan += [l for l in b if not l.startswith("measure ")]
            for name, cam in cameras(k):
                png = os.path.join(out_dir, f"{c}_{name}_{t}.png")
                plan += [cam + "\n", f"render {png} {W} {H} {t}\n"]
                pics.setdefault(c, []).append((f"{name} {t} ms", png))
        path = os.path.join(out_dir, f"renders_{room}.plan")
        open(path, "w").writelines(plan)
        subprocess.run([binary, path, os.path.join(out_dir, f"renders_{room}.csv")], check=True, stderr=subprocess.DEVNULL)
    # the sheets: 12 cases a sheet, a row a case: its three views and its flags
    order = [c for c in chosen if c in pics]
    sheets = []
    for s in range(0, len(order), 12):
        part = order[s:s + 12]
        sheet = Image.new("RGB", (3 * W + 420, len(part) * (H + 4)), (24, 24, 28))
        d = ImageDraw.Draw(sheet)
        for r, c in enumerate(part):
            y = r * (H + 4)
            for i, (label, png) in enumerate(pics[c]):
                if os.path.exists(png):
                    sheet.paste(Image.open(png).convert("RGB"), (i * W, y))
                d.text((i * W + 4, y + 4), label, fill=(255, 230, 120))
            w = report.get(c, {})
            text = [c, f"verdict: {w.get('verdict', '?')}"] + w.get("flags", [])
            if w:
                text.append(f"pelvis apart {w.get('pelvis')}, trunk gap {w.get('trunk')}")
            for j, line in enumerate(text):
                d.text((3 * W + 8, y + 8 + 16 * j), line[:62], fill=(235, 235, 235))
        path = os.path.join(out_dir, f"sheet_{s // 12 + 1:02d}.png")
        sheet.save(path)
        sheets.append(path)
    json.dump({c: pics[c] for c in order}, open(os.path.join(out_dir, "pictures.json"), "w"), indent=0)
    print("\n".join(sheets))


if __name__ == "__main__":
    main()
