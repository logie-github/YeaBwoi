import struct, numpy as np
def decode_palette_blp(d):
    """BLP2, palettised (encoding 1): the indices, then a separate alpha plane (Pillow reads alpha from the palette instead)."""
    assert d[:4] == b'BLP2'
    typ, enc, ad, ae, mips = struct.unpack_from('<IBBBB', d, 4)
    w, h = struct.unpack_from('<II', d, 12)
    offs = struct.unpack_from('<16I', d, 20); sizes = struct.unpack_from('<16I', d, 84)
    pal = np.frombuffer(d[148:148 + 1024], np.uint8).reshape(256, 4)
    assert enc == 1
    base = offs[0]
    idx = np.frombuffer(d[base:base + w * h], np.uint8)
    rgb = pal[idx][:, [2, 1, 0]]
    a = base + w * h
    if ad == 8:
        alpha = np.frombuffer(d[a:a + w * h], np.uint8)
    elif ad == 4:
        raw = np.frombuffer(d[a:a + (w * h + 1) // 2], np.uint8)
        alpha = np.empty(w * h, np.uint8); alpha[0::2] = (raw & 15) * 17; alpha[1::2] = (raw >> 4) * 17; alpha = alpha[:w * h]
    elif ad == 1:
        raw = np.frombuffer(d[a:a + (w * h + 7) // 8], np.uint8)
        alpha = np.unpackbits(raw, bitorder='little')[:w * h] * 255
    else:
        alpha = np.full(w * h, 255, np.uint8)
    out = np.dstack([rgb.reshape(h, w, 3), alpha.reshape(h, w)])
    return out
