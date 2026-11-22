#!/usr/bin/env python3
"""Measures how far each game animation bends a body, on the engine-test harness, and writes
android/app/src/main/java/com/logie/wifecraft/simulation/GesturePoseData.kt (what PoseSanity judges a conversation's gestures by).

    python3 -I tools/gesture_poses.py HARNESS FILES_DIR LOOKS_DIR OUT_KT

HARNESS   engine-test/build/harness      FILES_DIR   the game's files by id      LOOKS_DIR  harness look files
For each look (human_f, human_m, dwarf_m, ...) and each candidate animation the animation is drawn every 100 ms for 4 s and the
bones' pivots are read (`bonepos`): the worst spine tilt and head-to-chest bend beyond the body's own standing pose (an orc or an undead
woman stands hunched), the lowest head (of its standing height, 1.0: as high as standing), the farthest a hand goes from the chest and the highest it goes above the head (both of the standing torso
height). The worst over all the bodies is the animation's row. Desktop only; nothing here has run on a phone.
"""
import math, os, re, subprocess, sys, tempfile
BONES = ['Pelvis', 'Chest', 'Head', 'L_Hand', 'R_Hand']
CANDIDATES = [0, 60, 64, 65, 66, 67, 68, 69, 70, 73, 74, 77, 78, 79, 80, 81, 82, 83, 84, 113, 185, 186, 208, 1492, 778]
LOOKS = ['human_f', 'human_m', 'dwarf_m', 'nightelf_f', 'draenei_f', 'bloodelf_m', 'orc_m', 'undead_f', 'gnome_f']

def sub(a, b): return [a[i] - b[i] for i in range(3)]
def norm(a): return math.sqrt(sum(x * x for x in a))
def ang(a, b):
    if norm(a) < 1e-6 or norm(b) < 1e-6: return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, sum(x * y for x, y in zip(a, b)) / (norm(a) * norm(b))))))

def frames(harness, files, look, model, anim):
    cmds = ['files ' + files, 'look 0 %s 0 0 0 0' % look, 'anim 0 %d' % anim, 'camera 0.3 -3 1.5 0.3 0 1.05 36']
    out = os.path.join(tempfile.gettempdir(), 'gp.png')
    for t in range(100, 4100, 100):
        cmds += ['render %s 16 16 %d' % (out, t), 'bonepos 0 %s %s' % (model, ' '.join(BONES))]
    with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False) as f:
        f.write('\n'.join(cmds) + '\n')
    r = subprocess.run([harness, f.name], capture_output=True, text=True)
    os.unlink(f.name)
    res = []
    for l in r.stdout.splitlines():
        if l.startswith('bonepos'):
            d = {m.group(1): [float(m.group(i)) for i in (2, 3, 4)] for m in re.finditer(r'(\w+)=\(([-\d.]+),([-\d.]+),([-\d.]+)\)', l)}
            if all(k in d for k in ('Pelvis', 'Chest', 'Head')): res.append(d)
    return res

def measure(frs, stand_h):
    tilt = bend = reach = up = 0.0; drop = 9.0
    for f in frs:
        P, C, H = f['Pelvis'], f['Chest'], f['Head']
        tilt = max(tilt, ang(sub(H, P), [0, 0, 1])); bend = max(bend, ang(sub(C, P), sub(H, C)))
        drop = min(drop, (H[2] - P[2]) / stand_h)
        for k in ('L_Hand', 'R_Hand'):
            if k in f:
                reach = max(reach, norm(sub(f[k], C)) / stand_h); up = max(up, (f[k][2] - H[2]) / stand_h)
    return tilt, bend, drop, reach, up

def main(harness, files, looks_dir, out_kt):
    rows = {a: [0.0, 0.0, 9.0, 0.0, -9.0] for a in CANDIDATES}
    for name in LOOKS:
        path = os.path.join(looks_dir, name + '.look')
        model = re.search(r'^model (\d+)', open(path).read(), re.M).group(1)
        stand = frames(harness, files, path, model, 0)
        if not stand: print('skip', name); continue
        sh = stand[len(stand) // 2]['Head'][2] - stand[len(stand) // 2]['Pelvis'][2]
        base = measure(stand, sh)   # the body's own stand (an orc or an undead woman is hunched): gestures count what they add
        for a in CANDIDATES:
            m = measure(frames(harness, files, path, model, a), sh)
            m = (max(0.0, m[0] - base[0]), max(0.0, m[1] - base[1]), m[2] - base[2] + 1.0, m[3], m[4])
            print('  %s %d: lean +%.1f bend +%.1f head %.3f reach %.2f up %.2f' % ((name, a) + m), flush=True)
            r = rows[a]
            r[0] = max(r[0], m[0]); r[1] = max(r[1], m[1]); r[2] = min(r[2], m[2]); r[3] = max(r[3], m[3]); r[4] = max(r[4], m[4])
        print('measured', name)
    with open(out_kt, 'w') as o:
        o.write('package com.logie.wifecraft.simulation\n\n// Made by tools/gesture_poses.py (the engine-test harness on the game\'s models); do not edit by hand.\n')
        o.write('/** Each measured game animation\'s worst pose over %s: [PoseSanity] judges conversation gestures by these. */\n' % ', '.join(LOOKS))
        o.write('internal object GesturePoseData {\n    val ROWS: Map<Int, PoseMetrics> = mapOf(\n')
        for a in CANDIDATES:
            r = rows[a]
            o.write('        %d to PoseMetrics(%.1ff, %.1ff, %.3ff, %.3ff, %.3ff),\n' % (a, r[0], r[1], r[2], r[3], r[4]))
        o.write('    )\n}\n')
    print('wrote', out_kt)

if __name__ == '__main__':
    main(*sys.argv[1:5])
