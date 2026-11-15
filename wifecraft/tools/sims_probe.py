#!/usr/bin/env python3
"""Probe a Sims 4 package's animation clips (CLIP 0x6B20C4F3): the codec header and its channels.

    python3 -I wifecraft/tools/sims_probe.py some.package [rig-names.txt]

Prints, per clip, the codec header fields (offsets from the codec's start), and for the package
the channel (type, sub-target) histogram and the bone hashes. With a text file of bone names
(one per line), says how many hashes are FNV-32 of a lower-cased name in it. Reads only; the
package is the user's and nothing it holds is written anywhere. Stdlib only.
"""
import collections
import struct
import sys
import zlib

CLIP = 0x6B20C4F3


def fnv32(s):
    h = 0x811C9DC5
    for c in s.lower().encode():
        h = ((h * 0x01000193) & 0xFFFFFFFF) ^ c
    return h


def entries(b):
    assert b[:4] == b"DBPF", "not a DBPF package"
    count, = struct.unpack_from("<I", b, 36)
    pos, = struct.unpack_from("<I", b, 64)
    if pos == 0:
        pos, = struct.unpack_from("<I", b, 40)
    flags, = struct.unpack_from("<I", b, pos)
    p = pos + 4
    const = {}
    for bit in range(4):
        if flags & (1 << bit):
            const[bit], = struct.unpack_from("<I", b, p)
            p += 4
    for _ in range(count):
        f = []
        for bit in range(4):
            if bit in const:
                f.append(const[bit])
            else:
                f.append(struct.unpack_from("<I", b, p)[0])
                p += 4
        off, size, mem = struct.unpack_from("<III", b, p)
        p += 12
        comp = 0
        if size & 0x80000000:
            comp, = struct.unpack_from("<H", b, p)
            p += 4
        yield f[0], (f[2] << 32) | f[3], off, size & 0x7FFFFFFF, comp


def main():
    b = open(sys.argv[1], "rb").read()
    names = {}
    if len(sys.argv) > 2:
        for line in open(sys.argv[2], encoding="utf-8"):
            n = line.strip()
            if n:
                names[fnv32(n)] = n
    hist = collections.Counter()
    hashes = collections.Counter()
    for t, inst, off, size, comp in entries(b):
        if t != CLIP:
            continue
        raw = b[off:off + size]
        d = zlib.decompress(raw) if comp == 0x5A42 else raw
        c = d.index(b"_pilC3S_")
        clen, = struct.unpack_from("<I", d, c - 4)
        k = d[c:c + clen]
        ver, fl, tick, ticks, pad, n, pal, choff, paloff, nameoff, srcoff = struct.unpack_from("<IIfHHIIIIII", k, 8)
        name = k[nameoff:k.index(b"\0", nameoff)].decode(errors="replace")
        src = k[srcoff:k.index(b"\0", srcoff)].decode(errors="replace")
        print(f"{inst:016x} codec v{ver} flags {fl:#x} tick {tick:.5f} ticks {ticks} channels {n} palette {pal} "
              f"chOff {choff} palOff {paloff} nameOff {nameoff} srcOff {srcoff} name {name!r} source {src!r}")
        frames = collections.Counter()
        for i in range(n):
            doff, target, o, s, nf, typ, sub = struct.unpack_from("<IIffHBB", k, choff + i * 20)
            hist[(typ, sub)] += 1
            hashes[target] += 1
            frames[nf] += 1
        print(f"   frames per channel: {dict(frames)}")
    print("channel (type, sub-target) counts:", dict(sorted(hist.items())))
    print(f"{len(hashes)} bone hashes")
    if names:
        hit = sorted(names[h] for h in hashes if h in names)
        print(f"{len(hit)} of {len(hashes)} name a bone in the list: {' '.join(hit)}")
        print("unnamed:", " ".join(f"{h:08x}" for h in sorted(hashes) if h not in names))


if __name__ == "__main__":
    main()
