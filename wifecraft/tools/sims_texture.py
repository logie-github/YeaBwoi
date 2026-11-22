"""Sims 4 DDS / DST images to RGBA numpy arrays (the previews' and the pack inventory's reader; the app's own is
bodyparts/Rle2.kt). DXT1, DXT5 and DST1, DST5 (DXT with each block field regrouped into one stream). Top mip only."""
import struct

import numpy as np


def _rgb565(c):
    r = ((c >> 11) & 31) * 255 // 31
    g = ((c >> 5) & 63) * 255 // 63
    b = (c & 31) * 255 // 31
    return np.stack([r, g, b], -1).astype(np.uint8)


def _color_blocks(ep, ix, four):
    """ep: (N,2) uint16 colour endpoints, ix: (N,4) uint8 index bytes -> (N,16,3) uint8."""
    c0, c1 = ep[:, 0].astype(np.int32), ep[:, 1].astype(np.int32)
    p0, p1 = _rgb565(c0).astype(np.int32), _rgb565(c1).astype(np.int32)
    pal = np.zeros((len(ep), 4, 3), np.int32)
    pal[:, 0], pal[:, 1] = p0, p1
    opaque = (c0 > c1) | four
    pal[:, 2] = np.where(opaque[:, None], (2 * p0 + p1) // 3, (p0 + p1) // 2)
    pal[:, 3] = np.where(opaque[:, None], (p0 + 2 * p1) // 3, 0)
    bits = ix.astype(np.uint32)
    word = bits[:, 0] | (bits[:, 1] << 8) | (bits[:, 2] << 16) | (bits[:, 3] << 24)
    sel = np.stack([(word >> (2 * i)) & 3 for i in range(16)], 1)
    return np.take_along_axis(pal, sel[:, :, None].astype(np.int64), 1).astype(np.uint8)


def _alpha_blocks(a0, a1, ix6):
    a0, a1 = a0.astype(np.int32), a1.astype(np.int32)
    pal = np.zeros((len(a0), 8), np.int32)
    pal[:, 0], pal[:, 1] = a0, a1
    for k in range(1, 7):
        pal[:, k + 1] = np.where(a0 > a1, ((7 - k) * a0 + k * a1) // 7, 0)
    for k in range(1, 5):
        pal[:, k + 1] = np.where(a0 > a1, pal[:, k + 1], ((5 - k) * a0 + k * a1) // 5)
    pal[:, 6] = np.where(a0 > a1, pal[:, 6], 0)
    pal[:, 7] = np.where(a0 > a1, pal[:, 7], 255)
    b = ix6.astype(np.uint64)
    word = np.zeros(len(a0), np.uint64)
    for i in range(6):
        word |= b[:, i] << np.uint64(8 * i)
    sel = np.stack([((word >> np.uint64(3 * i)) & np.uint64(7)) for i in range(16)], 1).astype(np.int64)
    return np.take_along_axis(pal, sel, 1).astype(np.uint8)


def decode(d):
    """RGBA uint8 (h, w, 4) of a DDS/DST resource; None when the format is not one of the supported."""
    if d[:4] != b"DDS ":
        return None
    h, w = struct.unpack_from("<II", d, 12)
    cc = d[84:88]
    n = (w // 4) * (h // 4)
    body = d[128:]
    if cc in (b"DXT1", b"DST1"):
        if cc == b"DXT1":
            blk = np.frombuffer(body[:n * 8], np.uint8).reshape(n, 8)
            ep, ix = blk[:, :4].copy().view("<u2").reshape(n, 2), blk[:, 4:8]
        else:
            ep = np.frombuffer(body[:n * 4], np.uint8).copy().view("<u2").reshape(n, 2)
            ix = np.frombuffer(body[n * 4:n * 8], np.uint8).reshape(n, 4)
        rgb = _color_blocks(ep, ix, False)
        a = np.full((n, 16), 255, np.uint8)
    elif cc in (b"DXT5", b"DST5"):
        if cc == b"DXT5":
            blk = np.frombuffer(body[:n * 16], np.uint8).reshape(n, 16)
            a0, a1, aix = blk[:, 0], blk[:, 1], blk[:, 2:8]
            ep, cix = blk[:, 8:12].copy().view("<u2").reshape(n, 2), blk[:, 12:16]
        else:
            o = 0
            ae = np.frombuffer(body[o:o + 2 * n], np.uint8).reshape(n, 2); o += 2 * n
            ep = np.frombuffer(body[o:o + 4 * n], np.uint8).copy().view("<u2").reshape(n, 2); o += 4 * n
            aix = np.frombuffer(body[o:o + 6 * n], np.uint8).reshape(n, 6); o += 6 * n
            cix = np.frombuffer(body[o:o + 4 * n], np.uint8).reshape(n, 4)
            a0, a1 = ae[:, 0], ae[:, 1]
        rgb = _color_blocks(ep, cix, True)
        a = _alpha_blocks(a0, a1, aix)
    else:
        return None
    px = np.concatenate([rgb, a[:, :, None]], 2).reshape(h // 4, w // 4, 4, 4, 4)
    return px.transpose(0, 2, 1, 3, 4).reshape(h, w, 4)
