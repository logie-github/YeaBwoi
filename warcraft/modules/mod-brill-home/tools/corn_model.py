#!/usr/bin/env python3
"""Corn's model for the 3.3.5a game, from the user's Forsaken pug (forsaken_pug.zip: model/pug.m2 and
model/pug00.skin, the game's current pug, creature/pug/pug.m2 on wago.tools, in its current format).

The game's 3.3.5a pug is an older, lower model with another layout of its coat, so the user's coat only
fits the user's model. This writes that model in 3.3.5a's format (M2 version 264) to data/client/corn:

- Pug.m2: the MD21 chunk's model (the rest of the file, the TXID/SFID/LDV1/TXAC chunks, are the current
  game's file IDs, which 3.3.5a hasn't); version 264; the global flags 3.3.5a hasn't cleared; each
  animation's flags kept to 3.3.5a's (all of them in the file, 0x20) and its blend times (in and out
  in the current format) made one; the cameras (the current format's field of view a track) in 3.3.5a's
  layout; the textures named, as 3.3.5a's models name them: the coat (the creature skin kind the current
  game gives it a display's texture for) is Creature\\Pug\\ForsakenPug_Corn.blp, the metal's reflection
  the game's own Item\\ObjectComponents\\Shield\\ArmorReflect4.blp (the same texture the current game
  has at creature/pug/armorreflect4.blp).
- Pug00.skin: the user's, as it is (3.3.5a's skin layout, with the current format's shadow batches
  after its header, which 3.3.5a doesn't read).

The patch (src/BrillHomePatch.cpp) puts them at the game's own pug's paths (Creature\\Pug\\Pug.m2 and
Pug00.skin, display 31174), so no table of the game's is changed.

Usage: corn_model.py <folder with pug.m2 and pug00.skin>
"""
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "data", "client", "corn")
COAT = b"Creature\\Pug\\ForsakenPug_Corn.blp"
METAL = b"Item\\ObjectComponents\\Shield\\ArmorReflect4.blp"
SEQ_FLAGS = 0x7F          # 3.3.5a's animation flags (0x20: in the file, 0x40: an alias)
GLOBAL_FLAGS = 0x1F       # 3.3.5a's model flags


def chunks(b):
    out, i = {}, 0
    while i + 8 <= len(b):
        tag, n = b[i:i + 4], struct.unpack_from("<I", b, i + 4)[0]
        out[tag] = b[i + 8:i + 8 + n]
        i += 8 + n
    return out


def convert(m2):
    if m2[:4] == b"MD21":
        m2 = chunks(m2)[b"MD21"]
    d = bytearray(m2)
    if d[:4] != b"MD20":
        raise SystemExit("not an M2 model")
    version = struct.unpack_from("<I", d, 4)[0]
    if version < 264:
        raise SystemExit(f"version {version}: older than 3.3.5a's")
    u32 = lambda at: struct.unpack_from("<I", d, at)[0]
    arr = lambda at: struct.unpack_from("<II", d, at)
    put = lambda at, v: struct.pack_into("<I", d, at, v)

    def append(data, align=16):
        while len(d) % align:
            d.append(0)
        at = len(d)
        d.extend(data)
        return at

    put(4, 264)
    put(0x10, u32(0x10) & GLOBAL_FLAGS)
    if u32(0x10) & 0x8:
        raise SystemExit("texture combiner combos: the header would be longer")

    # animations: 64 bytes each; flags at 12, blend time at 28 (in, out: two uint16 since Legion)
    count, at = arr(0x1C)
    for i in range(count):
        s = at + 64 * i
        put(s + 12, u32(s + 12) & SEQ_FLAGS)
        if not u32(s + 12) & 0x20 and not u32(s + 12) & 0x40:
            raise SystemExit(f"animation {i} is in a file of its own")
        blend_in = struct.unpack_from("<H", d, s + 28)[0]
        put(s + 28, blend_in)

    # cameras: 116 bytes since Cataclysm (fov a track at the end) -> 100 (fov a float after the type)
    count, at = arr(0x110)
    if count:
        cams = bytearray()
        for i in range(count):
            c = at + 116 * i
            kind, far, near = struct.unpack_from("<Iff", d, c)
            fov = 0.785398
            n, o = struct.unpack_from("<II", d, c + 96 + 12)        # fov track (at 96): its values per animation
            for k in range(n):
                vn, vo = struct.unpack_from("<II", d, o + 8 * k)
                if vn:
                    fov = struct.unpack_from("<f", d, vo)[0]        # SplineKey<float>: value, in, out
                    break
            cams += struct.pack("<Ifff", kind, fov, far, near) + d[c + 12:c + 96]
        put(0x114, append(cams))

    # textures: 16 bytes (kind, flags, name); names where the current format has file IDs
    count, at = arr(0x50)
    names = {0: COAT, 1: METAL}
    for i in range(count):
        t = at + 16 * i
        name = names.get(i)
        if name is None:
            raise SystemExit(f"texture {i}: not expected")
        put(t, 0)                     # hard-coded (the coat was the creature skin kind, 11)
        o = append(name + b"\0", 4)
        struct.pack_into("<II", d, t + 8, len(name) + 1, o)
    return bytes(d)


def check_skin(skin):
    if skin[:4] != b"SKIN":
        raise SystemExit("not a skin")
    nv, ov, ni, oi, nb, ob, ns, os_, nt, ot = struct.unpack_from("<10I", skin, 4)
    for n, o, size in ((nv, ov, 2), (ni, oi, 2), (nb, ob, 4), (ns, os_, 48), (nt, ot, 24)):
        if o + n * size > len(skin):
            raise SystemExit("skin: an array past its end")
    return skin


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src = sys.argv[1]
    model = convert(open(os.path.join(src, "pug.m2"), "rb").read())
    skin = check_skin(open(os.path.join(src, "pug00.skin"), "rb").read())
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "Pug.m2"), "wb") as f:
        f.write(model)
    with open(os.path.join(OUT, "Pug00.skin"), "wb") as f:
        f.write(skin)
    print(f"Pug.m2 {len(model)} bytes, Pug00.skin {len(skin)} bytes")


if __name__ == "__main__":
    main()
