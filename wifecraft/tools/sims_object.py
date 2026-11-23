"""Sims 4 object models (OBJD -> MODL -> MLOD) read as plain meshes: the reference reader for the app's
`props/SimsObject.kt` (the two are kept in step; this one also draws the previews). Stdlib only.

An object package holds, per object, an OBJD (its catalog definition; its instance is the object's id, the number a
WickedWhims tuning calls `prop_guids`), a MODL (the model: its LODs), an MLOD per LOD (the meshes), a RIG (the object's own
skeleton), a SLOT (where Sims attach to it) and the textures (DDS/DST). Reads only; nothing in a package is executed.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # our own dbpf.py, also under python -I
from dbpf import entries, read  # noqa: E402

T_OBJD, T_MODL, T_MLOD, T_RIG, T_SLOT = 0xC0DB5AE7, 0x01661233, 0x01D10F34, 0x8EAF13DE, 0xD3044521
T_DDS, T_RLE2, T_COBJ, T_STBL, T_FTPT = 0x00B2D882, 0x3453CF95, 0x319E4F1D, 0x220557DA, 0xD382BF57
C_MATD, C_MTST, C_VRTF, C_VBUF, C_IBUF, C_SKIN = 0x01D0E75D, 0x02019972, 0x01D0E723, 0x01D0E6FB, 0x01D0E70F, 0x01D0E76B
P_DIFFUSE = 0x6CC0FD85  # the diffuse map's parameter in a MATD


def rcol(d):
    """A resource collection: its internal chunks as (type, group, instance, bytes), and its external keys."""
    ver, pub, _unused, ext, intc = struct.unpack_from("<5I", d, 0)
    p = 20
    keys = []
    for _ in range(intc + ext):
        inst, typ, grp = struct.unpack_from("<QII", d, p)
        p += 16
        keys.append((typ, grp, inst))
    chunks = []
    for i in range(intc):
        pos, size = struct.unpack_from("<II", d, p)
        p += 8
        t, g, inst = keys[i]
        chunks.append((t, g, inst, d[pos:pos + size]))
    return chunks, keys[intc:]


def key_in(blob, type_):
    """The first (inst, group) of a resource key of [type_] written as instance(8, halves swapped), type(4), group(4) in [blob]."""
    t = struct.pack("<I", type_)
    i = blob.find(t)
    while i >= 0:
        if i >= 8 and i + 8 <= len(blob):
            hi, lo = struct.unpack_from("<II", blob, i - 8)  # an OBJD writes an instance as its high half, then its low
            return (hi << 32) | lo, struct.unpack_from("<I", blob, i + 4)[0]
        i = blob.find(t, i + 1)
    return None


def ref(chunks, v):
    """A chunk reference of an MLOD (0x1000000N: the Nth internal chunk), or None."""
    if v >> 28 == 1 and (v & 0x0FFFFFFF) < len(chunks):
        return chunks[v & 0x0FFFFFFF]
    return None


def vrtf(blob):
    """(stride, [(usage, usage_index, format, offset)]) of a VRTF chunk."""
    _tag, _ver, stride, n, _flags = struct.unpack_from("<4sIIII", blob, 0)
    return stride, [tuple(blob[20 + i * 4:24 + i * 4]) for i in range(n)]


def _ub4n(b):
    return [x / 127.5 - 1 for x in b[:3]]


def decode_vertex(buf, base, elements):
    out = {}
    for usage, idx, fmt, off in elements:
        o = base + off
        if fmt == 2:
            v = struct.unpack_from("<3f", buf, o)
        elif fmt == 3:
            v = struct.unpack_from("<4f", buf, o)
        elif fmt == 1:
            v = struct.unpack_from("<2f", buf, o)
        elif fmt == 0:
            v = struct.unpack_from("<f", buf, o)
        elif fmt == 7:  # Short4, normalised
            v = tuple(x / 32767.0 for x in struct.unpack_from("<4h", buf, o))
        elif fmt == 10:  # Short4N
            v = tuple(x / 32767.0 for x in struct.unpack_from("<4h", buf, o))
        elif fmt == 6:  # two signed 16-bit numbers over 32767 (texture coordinates: 32767 is 1.0)
            v = tuple(x / 32767.0 for x in struct.unpack_from("<2h", buf, o))
        elif fmt == 9:
            v = tuple(x / 32767.0 for x in struct.unpack_from("<2h", buf, o))
        elif fmt in (4, 8):
            v = tuple(buf[o:o + 4])
        elif fmt == 5:
            v = tuple(buf[o:o + 4])
        else:
            continue
        out[(usage, idx)] = (fmt, v)
    return out


def meshes(mlod_bytes):
    """The meshes of an MLOD: dicts with positions, normals, uvs, indices, material chunk and the skin's bone hashes."""
    chunks, _ext = rcol(mlod_bytes)
    c = chunks[0][3]
    ver, count = struct.unpack_from("<II", c, 4)
    p = 12
    out = []
    for _ in range(count):
        size, = struct.unpack_from("<I", c, p)
        q = p + 4
        f = struct.unpack_from("<6I", c, q)
        name, mat, vf, vb, ib, flags = f
        stream_off, start_v, start_i, min_v, nverts, nprims = struct.unpack_from("<6I", c, q + 24)
        bounds = struct.unpack_from("<6f", c, q + 48)
        skin = struct.unpack_from("<I", c, q + 72)[0]
        nb, = struct.unpack_from("<I", c, q + 76)
        hashes = list(struct.unpack_from("<%dI" % nb, c, q + 80))
        p = q + size
        mat_c, vf_c, vb_c, ib_c = ref(chunks, mat), ref(chunks, vf), ref(chunks, vb), ref(chunks, ib)
        if not (vf_c and vb_c and ib_c) or nverts < 4 or nprims < 1:
            continue  # the shadow stub (3 vertices) and meshes without buffers
        stride, els = vrtf(vf_c[3])
        vbuf = vb_c[3][16:]
        ibuf = ib_c[3]
        iflags = struct.unpack_from("<I", ibuf, 8)[0]
        data = ibuf[16:]
        n = nprims * 3
        o = start_i * 2
        width = 4 if iflags & 2 else 2
        if iflags & 1:  # differenced: the sum runs on through the whole buffer
            deltas = struct.unpack_from("<%d%s" % (start_i + n, "i" if width == 4 else "h"), data, 0)
            acc, run = 0, []
            for dlt in deltas:
                acc += dlt
                run.append(acc)
            idx = run[start_i:]
        else:
            idx = list(struct.unpack_from("<%d%s" % (n, "I" if width == 4 else "H"), data, start_i * width))
        pos, nor, uv, bi, bw = [], [], [], [], []
        for v in range(nverts):
            d = decode_vertex(vbuf, stream_off + v * stride, els)
            pos.append(d[(0, 0)][1][:3])
            if (1, 0) in d:
                fmt, val = d[(1, 0)]
                nor.append(tuple(val[:3]) if fmt in (2, 3, 7, 10) else tuple(x / 127.5 - 1 for x in val[:3]))
            if (2, 0) in d:
                fmt, val = d[(2, 0)]
                uv.append(val[:2])
            if (3, 0) in d:
                bi.append(d[(3, 0)][1])
            if (4, 0) in d:
                bw.append(d[(4, 0)][1])
        out.append(dict(name=name, material=mat_c, bounds=bounds, pos=pos, nor=nor, uv=uv, idx=idx, bone_hashes=hashes,
                        bi=bi, bw=bw, skin=ref(chunks, skin)))
    return out


def material_textures(chunk_bytes):
    """The resource keys (instance, type, group) a MATD or MTST names, by parameter: {param hash: (inst, type, group)}."""
    out = {}
    if chunk_bytes[:4] != b"MATD":
        return out
    o = chunk_bytes.find(b"MTRL")
    if o < 0:
        o = chunk_bytes.find(b"MTNF")
    if o < 0:
        return out
    cnt = struct.unpack_from("<I", chunk_bytes, o + 12)[0]
    for i in range(min(cnt, 64)):
        h, t, c, off = struct.unpack_from("<IIII", chunk_bytes, o + 16 + i * 16)
        if t == 4 and o + off + 16 <= len(chunk_bytes):
            inst, typ, grp = struct.unpack_from("<QII", chunk_bytes, o + off)
            out[h] = (inst, typ, grp)
    return out


def material_color(chunk_bytes):
    """The first float4 parameters of a MATD as {param hash: (r, g, b, a)} (flat colours of untextured materials)."""
    out = {}
    o = chunk_bytes.find(b"MTRL")
    if o < 0:
        return out
    cnt = struct.unpack_from("<I", chunk_bytes, o + 12)[0]
    for i in range(min(cnt, 64)):
        h, t, c, off = struct.unpack_from("<IIII", chunk_bytes, o + 16 + i * 16)
        if t == 1 and c == 4 and o + off + 16 <= len(chunk_bytes):
            out[h] = struct.unpack_from("<4f", chunk_bytes, o + off)
    return out


def mtst_matds(chunks, mtst_bytes):
    """The MATD chunks an MTST (material set) lists, in order."""
    n = struct.unpack_from("<I", mtst_bytes, 16)[0]
    out = []
    for i in range(min(n, 64)):
        o = 20 + i * 12
        if o + 8 > len(mtst_bytes):
            break
        r = ref(chunks, struct.unpack_from("<I", mtst_bytes, o)[0])
        if r is not None and r[3][:4] == b"MATD":
            out.append(r)
    return out


def read_package_objects(b):
    """Every object of a package: {guid: dict(model=key, rig=key, slot=key, name)}; OBJD keys are found by their types."""
    es = entries(b)
    objs = {}
    for e in es:
        if e["type"] != T_OBJD:
            continue
        d = read(b, e)
        objs[e["inst"]] = dict(guid=e["inst"], modl=key_in(d, T_MODL), rig=key_in(d, T_RIG), slot=key_in(d, T_SLOT), raw=d)
    return objs


def find(b, es, type_, inst, group=None):
    for e in es:
        if e["type"] == type_ and e["inst"] == inst and (group is None or e["group"] == group):
            return read(b, e)
    return None


def variant_hash(objd):
    """The FNV-32 of the object's material variant name ("Set2-MaterialVariant") as an MTST lists it; None when it has none."""
    import re
    m = re.search(rb"[A-Za-z0-9_]+-[Mm]aterial[Vv]ariant", objd)
    if not m:
        return None
    h = 0x811C9DC5
    for c in m.group(0).decode().lower().encode():
        h = ((h * 0x01000193) & 0xFFFFFFFF) ^ c
    return h


def pick_material(chunks, mat_chunk, vhash):
    """The MATD a mesh's material chunk stands for: itself, or the entry of an MTST its object's variant names (else the first)."""
    if mat_chunk is None:
        return None
    t, _g, _i, bb = mat_chunk
    if t == C_MATD:
        return mat_chunk
    if t != C_MTST:
        return None
    n = struct.unpack_from("<I", bb, 16)[0]
    best = None
    for i in range(min(n, 64)):
        o = 20 + i * 12
        if o + 12 > len(bb):
            break
        r = ref(chunks, struct.unpack_from("<I", bb, o)[0])
        ha, hb = struct.unpack_from("<II", bb, o + 4)
        if r is None or r[3][:4] != b"MATD":
            continue
        if best is None:
            best = r
        if vhash is not None and vhash in (ha, hb) and (ha == 0 or hb == 0):
            return r
    return best
