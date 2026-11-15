#!/usr/bin/env python3
"""Which of Wifecraft's WoW-side bone names (the ones the Sims profile and an Anim's tracks use) each
character skeleton has, and which of its bones they are.

    python3 -I wifecraft/tools/wow_bones.py DIR [FILE_ID ...]
    python3 -I wifecraft/tools/wow_bones.py --profile PROFILE_JSON DIR FILE_ID

DIR holds models downloaded by file id (wago.tools' casc download, as the app keeps them); with no
ids, every MD21 file in DIR. Prints, per model, each name and the bone it resolves to (index, how:
key bone id, name CRC or the child rule) and the names it lacks.

How a name resolves (the same table and order as cpp/engine/m2.cpp kWowBones; keep them in step):
  1. a key bone id (the M2 key bone lookup, wowdev's key bone names);
  2. a bone name CRC: today's M2 bones carry CRC-32 (zlib's) of the bone's name. Every key bone's
     name reproduces its CRC (night elf female 921844: Root, SpineLow, Chest, ArmL, ElbowL, HandL,
     FootL, EXP_C1_Pelvis1, EXP_L1_Leg1Twist1 ...), which is how the names below were found;
  3. "child of": the first child of a named bone (a toe has no key id nor a known name).

--profile rewrites the Sims profile's (assets/sims_profile.json) "wowRest" from FILE_ID's skeleton (the
reference: night elf female 921844): for each mapped bone with an "aim", the direction from its pivot to the
aim's pivot in model space; and "rootHeight", the Root pivot's height. The file is rewritten with one bone
per line; nothing else in it changes. No game data is kept: only directions, rounded.
"""
import json
import os
import struct
import sys
import zlib

# name -> (key bone ids, CRC names, child-of rule). The left side is +y in WoW's model space, the
# face looks along +x; the right side's names are the left's with L and R swapped.
SIDED = {
    "Clavicle": ([2, 3], ["ShoulderL", "ShoulderR"], None),
    "UpperArm": ([0, 1], ["ArmL", "ArmR"], None),
    "Forearm": ([62, 61], ["ElbowL", "ElbowR", "ForearmL", "ForearmR"], None),
    "Hand": ([65, 64], ["HandL", "HandR"], None),
    "Thigh": ([56, 55], ["EXP_L1_Leg1Twist1", "EXP_R1_Leg1Twist1", "LegL", "LegR"], None),
    "Calf": ([58, 57], ["EXP_L1_Leg2Twist1", "EXP_R1_Leg2Twist1", "KneeL", "KneeR", "CalfL", "CalfR"], None),
    "Ankle": ([], ["EXP_L1_Leg2Twist3", "EXP_R1_Leg2Twist3"], None),
    "Foot": ([59, 60], ["FootL", "FootR"], None),
    "Toe": ([], ["ToeL", "ToeR"], "Foot"),
    "Breast": ([], ["EXP_L1_Breast1", "EXP_R1_Breast1"], None),
    "Thumb1": ([17, 12], ["ThumbL", "ThumbR"], None),
    "Thumb2": ([], ["ThumbTipL", "ThumbTipR"], None),
    "Index1": ([13, 8], ["IndexFingerL", "IndexFingerR"], None),
    "Index2": ([], ["IndexFingerTipL", "IndexFingerTipR"], None),
    "Middle1": ([14, 9], ["MiddleFingerL", "MiddleFingerR"], None),
    "Middle2": ([], ["MiddleFingerTipL", "MiddleFingerTipR"], None),
    "Ring1": ([16, 11], ["RingFingerL", "RingFingerR"], None),
    "Ring2": ([], ["RingFingerTipL", "RingFingerTipR"], None),
    "Pinky1": ([15, 10], ["PinkyFingerL", "PinkyFingerR"], None),
    "Pinky2": ([], ["PinkyFingerTipL", "PinkyFingerTipR"], None),
    "Eye": ([285, 286], ["EXP_L1_Eye1", "EXP_R1_Eye1"], None),
}
CENTRE = {
    "Root": ([26], ["Root"], None),
    "Pelvis": ([51], ["EXP_C1_Pelvis1"], None),
    "Waist": ([5], ["Waist"], None),
    "Spine1": ([4], ["SpineLow"], None),
    "Spine2": ([48], ["EXP_C1_Spine2"], None),
    "Chest": ([53], ["Chest", "SpineUp"], None),
    "Neck1": ([49], ["EXP_C1_Neck1", "Neck"], None),
    "Neck2": ([50], ["EXP_C1_Neck2"], None),
    "Head": ([6], ["Head"], None),
    "Jaw": ([7], ["Jaw"], None),
}


def table():
    out = [(n, k, c, ch) for n, (k, c, ch) in CENTRE.items()]
    for base, (keys, crcs, child) in SIDED.items():
        for side in (0, 1):
            s = "LR"[side]
            k = [keys[side]] if keys else []
            c = crcs[side::2]
            out.append((f"{s}_{base}", k, c, f"{s}_{child}" if child else None))
    return out


def md20(path):
    b = open(path, "rb").read()
    if b[:4] == b"MD20":
        return b
    o = 0
    while o + 8 <= len(b):
        tag, size = b[o:o + 4], struct.unpack_from("<I", b, o + 4)[0]
        if tag == b"MD21":
            return b[o + 8:o + 8 + size]
        o += 8 + size
    return None


def bones(m):
    n, off = struct.unpack_from("<II", m, 44)
    kn, ko = struct.unpack_from("<II", m, 52)
    out = []
    for i in range(n):
        key, _flags, parent, _sub, crc = struct.unpack_from("<iIhHI", m, off + i * 88)
        out.append((key, parent, crc))
    lookup = [struct.unpack_from("<h", m, ko + 2 * i)[0] for i in range(kn)]
    return out, lookup


def resolve(m):
    bs, lookup = bones(m)
    by_crc = {}
    for i, (_k, _p, crc) in enumerate(bs):
        by_crc.setdefault(crc, i)
    found = {}
    for name, keys, crcs, child in table():
        hit = None
        for k in keys:
            if 0 <= k < len(lookup) and 0 <= lookup[k] < len(bs):
                hit = (lookup[k], f"key {k}")
                break
            idx = next((i for i, b in enumerate(bs) if b[0] == k), None)
            if idx is not None:
                hit = (idx, f"key {k}")
                break
        if hit is None:
            for c in crcs:
                i = by_crc.get(zlib.crc32(c.encode()))
                if i is not None:
                    hit = (i, f"crc {c}")
                    break
        if hit is None and child and child in found:
            p = found[child][0]
            kids = [i for i, b in enumerate(bs) if b[1] == p]
            if kids:
                hit = (kids[0], f"first child of {child}")
        if hit:
            found[name] = hit
    return found, len(bs)


def pivots(m):
    n, off = struct.unpack_from("<II", m, 44)
    return [struct.unpack_from("<3f", m, off + i * 88 + 76) for i in range(n)]


def dump_profile(p):
    """The profile as JSON with one bone, one drop pattern and one rest direction per line."""
    lines = ["{"]
    keys = list(p.keys())
    for ki, k in enumerate(keys):
        end = "," if ki < len(keys) - 1 else ""
        v = p[k]
        if k in ("bones", "drop", "_about") and isinstance(v, list):
            lines.append(f"  {json.dumps(k)}: [")
            for i, item in enumerate(v):
                lines.append("    " + json.dumps(item) + ("," if i < len(v) - 1 else ""))
            lines.append("  ]" + end)
        elif k == "wowRest" and isinstance(v, dict):
            lines.append(f"  {json.dumps(k)}: {{")
            items = list(v.items())
            for i, (name, d) in enumerate(items):
                lines.append(f"    {json.dumps(name)}: {json.dumps(d)}" + ("," if i < len(items) - 1 else ""))
            lines.append("  }" + end)
        else:
            lines.append(f"  {json.dumps(k)}: {json.dumps(v)}{end}")
    lines.append("}")
    return "\n".join(lines) + "\n"


def fill_profile(profile_path, d, fid):
    m = md20(os.path.join(d, fid))
    if m is None:
        print(f"{fid}: not an M2", file=sys.stderr)
        return 1
    found, _ = resolve(m)
    piv = pivots(m)
    with open(profile_path) as f:
        prof = json.load(f)
    rest = {}
    for b in prof["bones"]:
        aim = b.get("aim")
        if not aim:
            continue
        if b["wow"] not in found or aim not in found:
            print(f"  {b['wow']} -> {aim}: not both in {fid}, left out", file=sys.stderr)
            continue
        a, c = piv[found[b["wow"]][0]], piv[found[aim][0]]
        v = [c[i] - a[i] for i in range(3)]
        length = sum(x * x for x in v) ** 0.5
        if length < 1e-4:
            print(f"  {b['wow']} -> {aim}: the same place in {fid}, left out", file=sys.stderr)
            continue
        rest[b["wow"]] = [round(x / length, 4) for x in v]
    prof["wowRest"] = rest
    if "Root" in found:
        prof["rootHeight"] = round(piv[found["Root"][0]][2], 4)
    with open(profile_path, "w") as f:
        f.write(dump_profile(prof))
    print(f"{profile_path}: {len(rest)} rest directions, rootHeight {prof.get('rootHeight')}")
    return 0


def main():
    if len(sys.argv) >= 5 and sys.argv[1] == "--profile":
        return fill_profile(sys.argv[2], sys.argv[3], sys.argv[4])
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    d = sys.argv[1]
    ids = sys.argv[2:] or sorted(os.listdir(d))
    names = [t[0] for t in table()]
    for fid in ids:
        p = os.path.join(d, fid)
        if not os.path.isfile(p):
            continue
        m = md20(p)
        if m is None:
            continue
        found, n = resolve(m)
        missing = [x for x in names if x not in found]
        print(f"{fid}: {n} bones, {len(found)}/{len(names)} names" + (f"; lacks {' '.join(missing)}" if missing else ""))
        if os.environ.get("VERBOSE"):
            for x in names:
                if x in found:
                    print(f"    {x:12s} bone {found[x][0]:4d}  ({found[x][1]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
