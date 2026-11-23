"""Minimal Sims 4 DBPF 2.x reader (stdlib only), shared by pack_inventory.py and build_pack.py.

Reads the index and unpacks entries (zlib 0x5A42, RefPack 0xFFFF, none). Nothing is executed
or written; a package is the user's own file.
"""
import struct
import zlib

TYPES = {
    0x6B20C4F3: "CLIP", 0xBC4A5044: "CLHD", 0x8EAF13DE: "RIG", 0xC0DB5AE7: "OBJD",
    0x319E4F1D: "COBJ", 0x01661233: "MODL", 0x01D10F34: "MLOD", 0x015A1849: "GEOM",
    0x034AEECB: "CASP", 0x00B2D882: "DDS", 0xB6C8B6A0: "DST", 0x3453CF95: "RLE2",
    0xBA856C78: "RLES", 0x220557DA: "STBL", 0x02D5DF13: "ASM", 0x01EEF63A: "SNR",
    0xD3044521: "SLOT", 0xD382BF57: "FTPT", 0x0355E0A6: "BOND", 0x03B4C61D: "LITE",
    0x3C1AF1F2: "CASTHUMB", 0x5B282D45: "OBJTHUMB", 0x2D4284F0: "THUMB", 0x0D338A3A: "THUMB",
    0x16CA6BC4: "THUMB", 0xE2760650: "THUMB", 0x00AE6C67: "BONE?", 0x033A1435: "TXTC",
    0x0333406C: "XMLTUNING", 0x03E9D964: "TUNING", 0x62E94D38: "TUNING", 0xE882D22F: "TUNING",
    0x7DF2169C: "TUNING", 0xCB5FDDC7: "SIMDATA", 0x545AC67A: "SIMDATA", 0xAC16FBEC: "RCOL?",
    0x2F7D0004: "PNG", 0x2BC04EDF: "LRLE", 0xF0633989: "PKG?", 0xD99F5E5C: "XDD",
    0x9C925813: "PNGIMG", 0x71BDB8A2: "STBL?", 0x2A8A5E22: "SPLASH", 0xE0D1B8B6: "SNS?",
    0xFD04E3BE: "AUDIO?", 0xB61215E9: "SCRIPTCFG?", 0xEBCBB16C: "FOOTPRINT?",
    0x7FB6AD8A: "BLUEPRINT?", 0xD5F0F921: "COMBINEDTUNING", 0x2B6CAA8C: "SKINTONE?",
}

def name_of(t):
    return TYPES.get(t, "%08X" % t)


def entries(b):
    if b[:4] != b"DBPF":
        raise ValueError("not a DBPF package")
    count, = struct.unpack_from("<I", b, 36)
    pos, = struct.unpack_from("<I", b, 64)
    if pos == 0:
        pos, = struct.unpack_from("<I", b, 40)
    if count == 0:
        return []
    flags, = struct.unpack_from("<I", b, pos)
    p = pos + 4
    const = {}
    for bit in range(4):
        if flags & (1 << bit):
            const[bit], = struct.unpack_from("<I", b, p)
            p += 4
    out = []
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
        out.append({"type": f[0], "group": f[1], "inst": (f[2] << 32) | f[3], "off": off,
                    "size": size & 0x7FFFFFFF, "mem": mem, "comp": comp})
    return out


def refpack(src):
    flags = src[0]
    w = 4 if flags & 0x80 else 3
    p = 2
    if flags & 1:
        p += w
    size = int.from_bytes(src[p:p + w], "big")
    p += w
    out = bytearray()
    n = len(src)
    while p < n:
        c0 = src[p]
        if c0 < 0x80:
            c1 = src[p + 1]; p += 2
            plain, ln, dist = c0 & 3, ((c0 & 0x1C) >> 2) + 3, ((c0 & 0x60) << 3) + c1 + 1
        elif c0 < 0xC0:
            c1, c2 = src[p + 1], src[p + 2]; p += 3
            plain, ln, dist = (c1 >> 6) & 3, (c0 & 0x3F) + 4, ((c1 & 0x3F) << 8) + c2 + 1
        elif c0 < 0xE0:
            c1, c2, c3 = src[p + 1], src[p + 2], src[p + 3]; p += 4
            plain, ln, dist = c0 & 3, ((c0 & 0x0C) << 6) + c3 + 5, ((c0 & 0x10) << 12) + (c1 << 8) + c2 + 1
        elif c0 < 0xFC:
            p += 1
            plain, ln, dist = ((c0 & 0x1F) << 2) + 4, 0, 0
        else:
            p += 1
            out += src[p:p + (c0 & 3)]
            break
        out += src[p:p + plain]; p += plain
        for _ in range(ln):
            out.append(out[-dist])
    if len(out) != size:
        raise ValueError("refpack size mismatch")
    return bytes(out)


def read(b, e):
    raw = b[e["off"]:e["off"] + e["size"]]
    c = e["comp"]
    if c == 0:
        return raw
    if c == 0x5A42:
        return zlib.decompress(raw)
    if c == 0xFFFF:
        return refpack(raw)
    raise ValueError("compression %#x not supported" % c)
