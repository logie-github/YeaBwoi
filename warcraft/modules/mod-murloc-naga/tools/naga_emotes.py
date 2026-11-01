#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Emotes for the naga players (and every naga that wears the race's model): the game's naga
character models (Character\\Naga_\\Male and Female) have stand, walk, run, swim, talk, work, roar,
the attacks and casts, wounds and death; no bow, wave, cheer, laugh, cry, salute, kneel, point, sit
or mount. As the world server makes the game's patch, src/MurlocNagaPatch.cpp (MergeEmotes) adds the
animations this makes to the client's own naga models (an HD look's, with one in), so that /bow,
/cheer... play, and a naga on a mount or sitting has a pose.

    python3 tools/naga_emotes.py fetch DIR          fetches the game's files from wago.tools by their ids
                                                     and writes DIR/Character/Naga_/<Sex>/Naga<Sex>Emotes.dat,
                                                     then DIR/nagaemotes.files (the list)
    python3 tools/naga_emotes.py convert SRC DIR    the same from files already fetched (named by id)

Where each animation comes from (today's files on wago.tools; never in this repository):
  - today's naga models (character/naga_/male/naga_male.m2 120294, female 120263: the same skeleton as
    the 3.3.5 naga's, every bone at the same place) have a few more than 3.3.5's: kneel (the emote,
    and its start, loop and end) and point. They're taken as they are, bone for bone. The naga sits
    and rides as it kneels (its tail coiled under it): Sit and Mount are its kneel's loop, sitting
    down and getting up its kneel's start and end.
  - The rest no naga model has (checked: today's naga, the naga lord, the elite naga, the sea witch,
    the siren, Azshara's naga: talk, work, roar, kneel and point at most). The naga were night elves:
    the night elf models (character/nightelf/male/nightelfmale.m2 120791, female 120590, today's
    standalone ones) have every emote, and their upper body (spine, chest, neck, head, jaw, shoulders,
    arms, hands, fingers) is built as the naga's is. Each of those naga bones turns as its night elf
    bone does (its turn from the pose the model is made in, the same in both), the rest (the tail,
    the fins, the hair) held as the naga stands. Sleep lays the whole naga down as the night elf lies.

What's written: each new sequence's every bone's place in the world (where its rest-pose vertices go:
a translation, a rotation and a scale) at its keys, by the bone's place in the rest pose; the patch
finds the client model's bone at each place and works out its own keys (its skeleton's parents may
differ: the 3.3.5 naga's tail bones all hang from one bone, today's from each other).

NagaXxxEmotes.dat: "NEMO", version 1 (u32), bone count, sequence count (u32); the bones' places
(3 floats each); each sequence: its record as 3.3.5 has it (64 bytes: id, variation, length, speed,
flags with 0x20, frequency, replay, blend time; its bounds and the indices after it the patch's), the
id it's an alias of (u16, 0xFFFF for none: then no keys) and two bytes, then for each bone its key
count (u32) and keys: time (u32, ms), translation (3 floats), rotation (4 floats: x, y, z, w), scale
(3 floats). A key is dropped where its neighbours' blend gives it back (within a tenth of a degree
and a millimetre).
"""
import math
import os
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-wrath-remix", "tools"))
import models  # noqa: E402
import murloc_model as M  # noqa: E402

MANIFEST = "nagaemotes.files"
NAGA = {"Male": 120294, "Female": 120263}            # today's naga character models (their skeletons, kneel and point)
NIGHT_ELF = {"Male": 120791, "Female": 120590}       # today's night elf character models (every emote)
FPS = 30
SEQ = 64
IN_MODEL, ALIAS = 0x20, 0x40
NONE = 0xFFFF

# 3.3.5's AnimationData ids
STAND, MOUNT, KNEEL, POINT = 0, 91, 75, 84
KNEEL_START, KNEEL_LOOP, KNEEL_END = 114, 115, 116
SIT_DOWN, SIT, SIT_UP = 96, 97, 98
SLEEP_DOWN, SLEEP, SLEEP_UP = 99, 100, 101

# What each new animation is: ("naga", id) today's naga's own; ("naga_as", id) today's naga's [id]
# under another id; ("elf", id) the night elf's on the upper body; ("elf_body", id) the night elf's on
# the whole body; ("alias", id) the game's alias of another (no data of its own).
EMOTES = [
    (KNEEL, ("naga", KNEEL)), (KNEEL_START, ("naga", KNEEL_START)), (KNEEL_LOOP, ("naga", KNEEL_LOOP)),
    (KNEEL_END, ("naga", KNEEL_END)), (POINT, ("naga", POINT)),
    (140, ("alias", KNEEL_START)), (141, ("alias", KNEEL_LOOP)), (142, ("alias", KNEEL_END)), (209, ("alias", POINT)),
    (SIT_DOWN, ("naga_as", KNEEL_START)), (SIT, ("naga_as", KNEEL_LOOP)), (SIT_UP, ("naga_as", KNEEL_END)),
    (MOUNT, ("naga_as", KNEEL_LOOP)),
    (66, ("elf", 66)),      # bow
    (67, ("elf", 67)),      # wave
    (68, ("elf", 68)),      # cheer
    (64, ("elf", 64)),      # talk (exclamation)
    (65, ("elf", 65)),      # talk (question)
    (80, ("elf", 80)),      # applaud
    (79, ("elf", 79)),      # beg
    (78, ("elf", 78)),      # chicken
    (82, ("elf", 82)),      # flex
    (76, ("elf", 76)),      # kiss
    (70, ("elf", 70)),      # laugh
    (81, ("elf", 81)),      # shout
    (77, ("elf", 77)),      # cry
    (83, ("elf", 83)),      # shy
    (69, ("elf", 69)),      # dance
    (113, ("elf", 113)),    # salute
    (61, ("elf", 61)),      # eat
    (73, ("elf", 73)),      # rude
    (195, ("elf", 195)),    # train
    (185, ("elf", 185)),    # yes
    (186, ("elf", 186)),    # no
    (210, ("alias", 113)), (199, ("alias", 61)),
    (SLEEP_DOWN, ("elf_body", SLEEP_DOWN)), (SLEEP, ("elf_body", SLEEP)), (SLEEP_UP, ("elf_body", SLEEP_UP)),
]

# The key bones (the model's own names for them) the naga and the night elves share, upper body.
SHOULDER_L, SHOULDER_R, ARM_L, ARM_R, SPINE_LOW, WAIST, HEAD, JAW, ROOT = 2, 3, 0, 1, 4, 5, 6, 7, 26
ELBOW_L, ELBOW_R = 62, 61
FINGERS_L = (13, 14, 17)                                # index, middle, thumb (the naga's three)
FINGERS_R = (8, 9, 12)


def quat_matrix(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def matrix_quat(r):
    """A rotation matrix's quaternion (x, y, z, w), w >= 0."""
    t = np.trace(r)
    if t > 0:
        s = math.sqrt(t + 1.0) * 2
        q = ((r[2, 1] - r[1, 2]) / s, (r[0, 2] - r[2, 0]) / s, (r[1, 0] - r[0, 1]) / s, 0.25 * s)
    elif r[0, 0] > r[1, 1] and r[0, 0] > r[2, 2]:
        s = math.sqrt(1.0 + r[0, 0] - r[1, 1] - r[2, 2]) * 2
        q = (0.25 * s, (r[0, 1] + r[1, 0]) / s, (r[0, 2] + r[2, 0]) / s, (r[2, 1] - r[1, 2]) / s)
    elif r[1, 1] > r[2, 2]:
        s = math.sqrt(1.0 + r[1, 1] - r[0, 0] - r[2, 2]) * 2
        q = ((r[0, 1] + r[1, 0]) / s, 0.25 * s, (r[1, 2] + r[2, 1]) / s, (r[0, 2] - r[2, 0]) / s)
    else:
        s = math.sqrt(1.0 + r[2, 2] - r[0, 0] - r[1, 1]) * 2
        q = ((r[0, 2] + r[2, 0]) / s, (r[1, 2] + r[2, 1]) / s, 0.25 * s, (r[1, 0] - r[0, 1]) / s)
    q = np.array(q)
    q /= np.linalg.norm(q)
    return q if q[3] >= 0 else -q


def comp_quat(raw):
    """The game's packed rotation (four int16) as floats."""
    raw = np.asarray(raw, dtype=np.float64)
    return np.where(raw < 0, raw + 32768, raw - 32767) / 32767.0


class Animated:
    """A model's skeleton and animations (today's format, its MD21, or 3.3.5's MD20), as numbers:
    each bone's own turn, move and scale in a sequence at a time, and the world's."""

    def __init__(self, read, file_id):
        data = read(file_id)
        chunks = models.chunks_of(data) if data[:4] != b"MD20" else {"MD21": data}
        self.m = m = chunks["MD21"]
        self.read = read
        n, at = struct.unpack_from("<II", m, 0x2C)
        self.bones = []
        for i in range(n):
            b = at + 88 * i
            key, flags, parent = struct.unpack_from("<iIh", m, b)
            self.bones.append((key, parent, b + 16, b + 36, b + 56, np.array(struct.unpack_from("<3f", m, b + 76))))
        self.pivot = np.array([b[5] for b in self.bones])
        self.parent = [b[1] for b in self.bones]
        self.key = [b[0] for b in self.bones]
        n, at = struct.unpack_from("<II", m, 0x1C)
        self.records = [m[at + SEQ * k:at + SEQ * (k + 1)] for k in range(n)]
        self.ids = [struct.unpack_from("<HH", r, 0) for r in self.records]
        self.anim_ids = {}
        if "AFID" in chunks:
            raw = chunks["AFID"]
            for i in range(0, len(raw), 8):
                anim, sub, fid = struct.unpack_from("<HHI", raw, i)
                if fid:
                    self.anim_ids[(anim, sub)] = fid
        n, at = struct.unpack_from("<II", m, 0x14)
        self.loops = struct.unpack_from("<%dI" % n, m, at)
        self.payloads = {}
        self.cache = {}

    def index(self, anim):
        """The sequence of [anim] (its first variation), following an alias."""
        for k, (a, v) in enumerate(self.ids):
            if a == anim and v == 0:
                for _ in range(8):
                    flags = struct.unpack_from("<I", self.records[k], 12)[0]
                    if not flags & ALIAS:
                        return k
                    k = struct.unpack_from("<H", self.records[k], 62)[0]
                return k
        return None

    def duration(self, k):
        return struct.unpack_from("<I", self.records[k], 4)[0]

    def payload(self, k):
        if struct.unpack_from("<I", self.records[k], 12)[0] & IN_MODEL:
            return self.m
        if k not in self.payloads:
            fid = self.anim_ids.get(self.ids[k])
            self.payloads[k] = models.anim_payload(self.read(fid)) if fid else b""
        return self.payloads[k]

    def keys(self, at, k, size):
        """A track's keys in sequence k: (times, raw values) or None."""
        interp, gseq, tn, to, vn, vo = struct.unpack_from("<HhIIII", self.m, at)
        if gseq >= 0:
            if tn < 1 or vn < 1:
                return None
            n, ko = struct.unpack_from("<II", self.m, to)
            dn, do = struct.unpack_from("<II", self.m, vo)
            data = self.m
        else:
            if k >= tn or k >= vn:
                return None
            n, ko = struct.unpack_from("<II", self.m, to + 8 * k)
            dn, do = struct.unpack_from("<II", self.m, vo + 8 * k)
            data = self.payload(k)
        if n == 0 or dn == 0 or not data or ko + 4 * n > len(data):
            return None
        stride = size * (3 if interp >= 2 else 1)          # (Bezier and Hermite keys carry their tangents)
        times = np.frombuffer(data, dtype="<u4", count=n, offset=ko).astype(np.int64)
        values = np.frombuffer(data, dtype=np.uint8, count=dn * stride, offset=do).reshape(dn, stride)[:, :size]
        if gseq >= 0:
            length = self.loops[gseq] if gseq < len(self.loops) else 0
            return times, values, length
        return times, values, None

    def value(self, at, k, t, kind):
        """A track's value at time t (ms) of sequence k: linear between keys (the rotations' blend
        normalized)."""
        size = 8 if kind == "q" else 12
        default = np.array([0.0, 0.0, 0.0, 1.0]) if kind == "q" else (np.ones(3) if kind == "s" else np.zeros(3))
        if (at, k) not in self.cache:
            got = self.keys(at, k, size)
            if got is not None:
                times, raw, loop = got
                if kind == "q":
                    values = comp_quat(raw.copy().view("<i2").reshape(-1, 4))
                else:
                    values = raw.copy().view("<f4").reshape(-1, 3).astype(np.float64)
                got = times, values, loop
            self.cache[(at, k)] = got
        got = self.cache[(at, k)]
        if got is None:
            return default
        times, values, loop = got
        if loop is not None:
            t = t % loop if loop else 0
        i = int(np.searchsorted(times, t, side="right")) - 1
        if i < 0:
            return values[0]
        if i >= len(values) - 1:
            return values[min(i, len(values) - 1)]
        t0, t1 = times[i], times[i + 1]
        f = 0.0 if t1 <= t0 else (t - t0) / float(t1 - t0)
        a, b = values[i], values[i + 1]
        if kind == "q":
            if np.dot(a, b) < 0:
                b = -b
            v = a * (1 - f) + b * f
            return v / np.linalg.norm(v)
        return a * (1 - f) + b * f

    def local(self, b, k, t):
        """The bone's own transform (4 x 4) in sequence k at t."""
        _, _, ta, ra, sa, pivot = self.bones[b]
        tr, q, sc = self.value(ta, k, t, "v"), self.value(ra, k, t, "q"), self.value(sa, k, t, "s")
        out = np.eye(4)
        out[:3, :3] = quat_matrix(q) * sc[None, :]
        out[:3, 3] = pivot + tr - out[:3, :3] @ pivot
        return out

    def world(self, k, t):
        out = [None] * len(self.bones)
        for b in range(len(self.bones)):
            local = self.local(b, k, t)
            p = self.parent[b]
            out[b] = local if p < 0 else out[p] @ local
        return out


def children(model, b):
    return [c for c in range(len(model.bones)) if model.parent[c] == b]


def first_key(model, key):
    return model.key.index(key) if key in model.key else None


def upper_body_map(naga, elf):
    """{naga bone: night elf bone}: the key bones they share, the bones between them by their place in
    the chain (the chest: the shoulders' parent; the neck: the head's; the wrists: the fingers'), each
    finger's next joint."""
    out = {}

    def pair(key):
        a, b = first_key(naga, key), first_key(elf, key)
        if a is not None and b is not None:
            out[a] = b
        return a, b
    for key in (SPINE_LOW, SHOULDER_L, SHOULDER_R, ARM_L, ARM_R, ELBOW_L, ELBOW_R, HEAD, JAW):
        pair(key)
    for key in (SHOULDER_L, HEAD):
        a, b = first_key(naga, key), first_key(elf, key)
        out[naga.parent[a]] = elf.parent[b]                     # the chest, the neck
    for fingers in (FINGERS_L, FINGERS_R):
        a, b = pair(fingers[0])
        out[naga.parent[a]] = elf.parent[b]                     # the wrist
        for key in fingers:
            a, b = pair(key)
            ca, cb = children(naga, a), children(elf, b)
            if ca and cb:                                       # the finger's next joint
                out[ca[0]] = min(cb, key=lambda c: np.linalg.norm((elf.pivot[c] - elf.pivot[b]) - (naga.pivot[ca[0]] - naga.pivot[a])))
    return out


def under(model, b, top):
    while b >= 0:
        if b == top:
            return True
        b = model.parent[b]
    return False


def sample_times(duration):
    n = max(1, int(round(duration * FPS / 1000.0)))
    return sorted({int(round(i * duration / n)) for i in range(n + 1)})


def retarget(naga, elf, mapping, k_elf, whole_body, stand):
    """The naga's world (each bone's 4 x 4) at each sample of the night elf's sequence k_elf: its
    upper body's bones turned as their night elf bones are (in the world), the rest held as the naga
    stands at its sequence's start; with [whole_body], its root moved and turned as the night elf's."""
    duration = elf.duration(k_elf)
    upper = {b for b in range(len(naga.bones)) if under(naga, b, first_key(naga, SPINE_LOW))}
    still = [naga.local(b, stand, 0) for b in range(len(naga.bones))]
    root_n, root_e = first_key(naga, ROOT), first_key(elf, ROOT)
    height = naga.pivot[first_key(naga, HEAD)][2] / elf.pivot[first_key(elf, HEAD)][2]
    frames = []
    for t in sample_times(duration):
        ew = elf.world(k_elf, t)
        world = [None] * len(naga.bones)
        for b in range(len(naga.bones)):
            p = naga.parent[b]
            above = np.eye(4) if p < 0 else world[p]
            if whole_body and b == root_n:
                r = ew[root_e][:3, :3]
                at = ew[root_e] @ np.append(elf.pivot[root_e], 1.0)
                place = naga.pivot[b] + (at[:3] - elf.pivot[root_e]) * height
                w = np.eye(4)
                w[:3, :3] = r
                w[:3, 3] = place - r @ naga.pivot[b]
                world[b] = w if p < 0 else w            # (the root's parent, if any, is held still: its place is the root's)
            elif b in upper and b in mapping:
                r = ew[mapping[b]][:3, :3]
                place = above @ np.append(naga.pivot[b], 1.0)
                w = np.eye(4)
                w[:3, :3] = r
                w[:3, 3] = place[:3] - r @ naga.pivot[b]
                world[b] = w
            else:
                world[b] = above @ still[b]
        frames.append((t, world))
    return duration, frames


def own(naga, k):
    """Today's naga's own sequence k: its world at each sample."""
    duration = naga.duration(k)
    return duration, [(t, naga.world(k, t)) for t in sample_times(duration)]


def decompose(w):
    """A world transform as (translation, rotation (x, y, z, w), scale)."""
    a = w[:3, :3]
    scale = np.linalg.norm(a, axis=0)
    r = a / np.where(scale > 1e-9, scale, 1.0)[None, :]
    if np.linalg.det(r) < 0:
        scale[0] = -scale[0]
        r[:, 0] = -r[:, 0]
    u, _, vt = np.linalg.svd(r)
    return w[:3, 3].copy(), matrix_quat(u @ vt), scale


def reduced(keys, rotation_tolerance=0.0009, place_tolerance=0.001):
    """[(t, translation, rotation, scale)] without the keys their neighbours' blend gives back."""
    if len(keys) <= 2:
        return keys

    def blend(a, b, t):
        f = (t - a[0]) / float(b[0] - a[0]) if b[0] > a[0] else 0.0
        qb = b[2] if np.dot(a[2], b[2]) >= 0 else -b[2]
        q = a[2] * (1 - f) + qb * f
        return a[1] * (1 - f) + b[1] * f, q / np.linalg.norm(q), a[3] * (1 - f) + b[3] * f

    def close(key, got):
        tr, q, s = got
        return (np.abs(tr - key[1]).max() <= place_tolerance and min(np.abs(q - key[2]).max(), np.abs(q + key[2]).max()) <= rotation_tolerance
                and np.abs(s - key[3]).max() <= place_tolerance)
    out = [keys[0]]
    i = 0
    while i < len(keys) - 1:
        j = i + 1
        while j + 1 < len(keys) and all(close(keys[m], blend(keys[i], keys[j + 1], keys[m][0])) for m in range(i + 1, j + 1)):
            j += 1
        out.append(keys[j])
        i = j
    if len(out) == 2 and close(out[1], (out[0][1], out[0][2], out[0][3])):
        return out[:1]                                            # (one place throughout)
    return out


def record(anim, duration, flags, blend):
    """A 3.3.5 sequence record (its bounds and next indices the patch's)."""
    rec = bytearray(SEQ)
    struct.pack_into("<HHIfIhHIII", rec, 0, anim, 0, duration, 0.0, flags | IN_MODEL, 0, 0, 0, 0, blend)
    struct.pack_into("<hH", rec, 60, -1, 0)
    return bytes(rec)


def build(read, sex):
    """The .dat for [sex]: its bones' places and the new sequences' keys."""
    naga = Animated(read, NAGA[sex])
    elf = Animated(read, NIGHT_ELF[sex])
    mapping = upper_body_map(naga, elf)
    stand = naga.index(STAND)
    sequences = []
    for anim, (kind, source) in EMOTES:
        if kind == "alias":
            sequences.append((record(anim, 0, ALIAS, 150), source, None))
            continue
        model = naga if kind in ("naga", "naga_as") else elf
        k = model.index(source)
        if k is None:
            raise ValueError("%s's model has no animation %d" % (sex, source))
        flags = struct.unpack_from("<I", model.records[k], 12)[0] & ~(ALIAS | IN_MODEL) & 0xFFFF
        blend = struct.unpack_from("<I", model.records[k], 28)[0]
        if kind in ("naga", "naga_as"):
            duration, frames = own(naga, k)
        else:
            duration, frames = retarget(naga, elf, mapping, k, kind == "elf_body", stand)
        bones = []
        for b in range(len(naga.bones)):
            bones.append(reduced([(t,) + decompose(world[b]) for t, world in frames]))
        sequences.append((record(anim, duration, flags, blend), NONE, bones))
    out = bytearray(b"NEMO" + struct.pack("<III", 1, len(naga.bones), len(sequences)))
    for p in naga.pivot:
        out += struct.pack("<3f", *p)
    for rec, alias, bones in sequences:
        out += rec + struct.pack("<HH", alias, 0)
        if bones is None:
            continue
        for keys in bones:
            out += struct.pack("<I", len(keys))
            for t, tr, q, s in keys:
                out += struct.pack("<I3f4f3f", t, *tr, *q, *s)
    return bytes(out), mapping


def convert(read, folder):
    files = {}
    for sex in ("Male", "Female"):
        data, mapping = build(read, sex)
        files["Character/Naga_/%s/Naga%sEmotes.dat" % (sex, sex)] = data
        print("naga emotes: %s, %d bones turned as the night elf's, %d KB" % (sex, len(mapping), len(data) // 1024))
    for name, data in files.items():
        path = os.path.join(folder, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as out:
            out.write(data)
    with open(os.path.join(folder, MANIFEST), "w", newline="\n") as out:
        out.write("".join(name + "\n" for name in sorted(files)))
    print("naga emotes: %d files in %s" % (len(files), folder))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "fetch":
        convert(M.cached(os.environ.get("MURLOC_CACHE"), M.wago), sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "convert":
        convert(M.cached(sys.argv[2], None), sys.argv[3])
    else:
        print(__doc__)
        sys.exit(2)
