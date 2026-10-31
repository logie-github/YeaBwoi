#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The murlocs' character model and textures (tools/murloc_looks.py says what they are): Blizzard's
newer murloc made a model the 3.3.5 game draws as a character's, with its kin's parts as geosets the
character creation screen picks, its skins, the eyes' face textures and the hair's colours.

    python3 tools/murloc_model.py fetch DIR          fetches the game's files from wago.tools by their
                                                      ids and writes the murlocs' files into DIR
    python3 tools/murloc_model.py convert SRC DIR    the same from files already fetched (named by id)

The files are the game's, so they are never in this repository: the server package makes them
(scripts/package-azerothcore.sh) into the module's data/files, where the world server finds them
(src/MurlocNagaPatch.cpp puts them in the game's patch-<locale>-T with the module's tables). The list
of them, murloc.files, is written last.

What's made:
  - Character/Murloc/<Sex>/Murloc<Sex>.m2, its 00.skin and .anim files: murloc2 as Wrath Remix's
    converter makes the newer format 3.3.5's (mod-wrath-remix/tools/models.py), then
      - its body drawn with the character's skin "extra" texture (type 8: CharSections' skin row's
        second texture, the skin colour's whole murloc skin), its eyes with the character's composed
        texture (type 1), their texture coordinates moved into its face region (FaceLower), which
        the face textures fill with the skins' eyes;
      - the parts its kin lend (their vertices, bones taken to murloc2's at the same places: a bone
        it hasn't, to its nearest parent it has), as geosets: hair styles (group 0) and features
        (group 1); Maria's hair drawn with the character's hair texture (type 6: its colour);
  - Character/Murloc/MurlocSkin<NN>.blp: the skins (the game's, as they are);
  - Character/Murloc/MurlocBase.blp: the composed texture's base (512 x 512, palettized, as the
    game composes only those): one colour, under the eyes' region;
  - Character/Murloc/MurlocEyes<NN>.blp: FaceLower (256 x 128, palettized): a skin's eyes;
  - Character/Murloc/MurlocHair<NN>.blp: Maria's texture in each hair colour;
  - Character/Murloc/Murloc<Part>.blp: the parts' textures (the game's).
"""
import os
import struct
import sys
import time
import urllib.request

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-wrath-remix", "tools"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-allied-races", "tools"))
import murloc_looks as L  # noqa: E402
import models  # noqa: E402
import blp  # noqa: E402
import importlib.util  # noqa: E402

# The starting areas' list of a 3.3.5 model's animation tracks (mod-worgen-goblin/tools/zones/package.py).
_ZONES = os.path.join(HERE, "..", "..", "mod-worgen-goblin", "tools", "zones")
sys.path.insert(0, _ZONES)
_spec = importlib.util.spec_from_file_location("zones_package", os.path.join(_ZONES, "package.py"))
zones_package = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(zones_package)


def one_set_a_sequence(data):
    """A track with one set of keys for every sequence (the newer format's way) given one set a
    sequence, as 3.3.5 reads it (the set of the sequence playing): the one set for the first sequence
    and every other kept in the model, none for a sequence kept in its own .anim file (its flag 0x20,
    at its record's byte 12, says it's in the model). Returns how many tracks changed."""
    sequences, seq_at = struct.unpack_from("<II", data, 28)
    in_model = [bool(struct.unpack_from("<I", data, seq_at + 64 * k + 12)[0] & 0x20) for k in range(sequences)]
    changed = 0

    def append(block):
        while len(data) % 16:
            data.append(0)
        at = len(data)
        data.extend(block)
        return at
    for at, has_values in zones_package.m2_tracks(bytes(data)):
        gseq, count, offset = struct.unpack_from("<hII", data, at + 2)
        if gseq >= 0 or count != 1 or sequences < 2:
            continue
        stamps = struct.unpack_from("<II", data, offset)
        vcount, voffset = struct.unpack_from("<II", data, at + 12)
        values = struct.unpack_from("<II", data, voffset) if vcount == 1 else (0, 0)
        keep = [in_model[k] or k == 0 for k in range(sequences)]
        struct.pack_into("<II", data, at + 4, sequences, append(b"".join(struct.pack("<II", *(stamps if keep[k] else (0, 0))) for k in range(sequences))))
        if has_values and vcount == 1:
            struct.pack_into("<II", data, at + 12, sequences, append(b"".join(struct.pack("<II", *(values if keep[k] else (0, 0))) for k in range(sequences))))
        changed += 1
    return changed

USER_AGENT = "YeaBwoi-build/1.0 (+https://github.com/logie-github/YeaBwoi)"
MANIFEST = "murloc.files"
FOLDER = "Character/Murloc"
MAX_BONES = 64
COMPOSED = 512                      # the composed texture's size (the HD looks' skins)
# the 3.3.5 header's arrays
H_VERTS, H_TEXTURES, H_TEX_REPLACE, H_RENDER_FLAGS, H_BONE_LOOKUP, H_TEX_LOOKUP = 0x3C, 0x50, 0x68, 0x70, 0x78, 0x80
H_TEX_UNIT_LOOKUP, H_TRANSP_LOOKUP, H_UV_LOOKUP, H_BOUNDS, H_BONES = 0x88, 0x90, 0x98, 0xA0, 0x2C
TYPE_HARDCODED, TYPE_SKIN, TYPE_HAIR, TYPE_SKIN_EXTRA = 0, 1, 6, 8
# FaceLower's place in the composed texture (u0, v0, u1, v1), a texel in from its edges
FACE_LOWER = (0.5 / COMPOSED, 0.75 + 0.5 / COMPOSED, 0.5 - 0.5 / COMPOSED, 1.0 - 0.5 / COMPOSED)


def wago(file_id):
    for attempt in range(4):
        try:
            request = urllib.request.Request("https://wago.tools/api/casc/%d?download" % file_id, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except Exception as error:  # a network hiccup: once more, a little later
            print("murloc: file %d: %s" % (file_id, error), file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    raise RuntimeError("file %d couldn't be fetched" % file_id)


def cached(folder, fetch):
    def read(file_id):
        path = os.path.join(folder, str(file_id)) if folder else None
        if path and os.path.exists(path) and os.path.getsize(path):
            with open(path, "rb") as f:
                return f.read()
        if fetch is None:
            raise FileNotFoundError(file_id)
        data = fetch(file_id)
        if path:
            os.makedirs(folder, exist_ok=True)
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
        return data
    return read


# The newer format, read --------------------------------------------------------------------------

class Source:
    """A model of the newer format: its vertices, bones and first skin, as numbers."""

    def __init__(self, read, file_id):
        data = read(file_id)
        self.chunks = models.chunks_of(data)
        self.m = self.chunks["MD21"]
        m = self.m
        nv, ov = struct.unpack_from("<II", m, H_VERTS)
        self.raw = np.frombuffer(m[ov:ov + 48 * nv], dtype=np.uint8).reshape(nv, 48).copy()
        self.pos = self.raw[:, 0:12].copy().view("<f4").reshape(nv, 3)
        nb, ob = struct.unpack_from("<II", m, H_BONES)
        self.parent = [struct.unpack_from("<h", m, ob + 88 * i + 8)[0] for i in range(nb)]
        self.key = [struct.unpack_from("<i", m, ob + 88 * i)[0] for i in range(nb)]
        self.pivot = np.array([struct.unpack_from("<3f", m, ob + 88 * i + 76) for i in range(nb)])
        sfid = struct.unpack("<%dI" % (len(self.chunks["SFID"]) // 4), self.chunks["SFID"])
        self.skin = read(sfid[0])
        s = self.skin
        sv_n, sv_o = struct.unpack_from("<II", s, 4)
        ix_n, ix_o = struct.unpack_from("<II", s, 12)
        pr_n, pr_o = struct.unpack_from("<II", s, 20)
        se_n, se_o = struct.unpack_from("<II", s, 28)
        ba_n, ba_o = struct.unpack_from("<II", s, 36)
        self.skin_vertices = np.array(struct.unpack_from("<%dH" % sv_n, s, sv_o))
        self.indices = np.array(struct.unpack_from("<%dH" % ix_n, s, ix_o))
        self.props = np.frombuffer(s[pr_o:pr_o + 4 * pr_n], dtype=np.uint8).reshape(pr_n, 4)
        self.sections = [struct.unpack_from("<10H7f", s, se_o + 48 * i) for i in range(se_n)]
        self.batches = [struct.unpack_from("<BbHHHhHHHHHHH", s, ba_o + 24 * i) for i in range(ba_n)]
        lc, lo = struct.unpack_from("<II", m, H_BONE_LOOKUP)
        self.lookup = struct.unpack_from("<%dH" % lc, m, lo)
        rc, ro = struct.unpack_from("<II", m, H_RENDER_FLAGS)
        self.materials = [m[ro + 4 * i:ro + 4 * i + 4] for i in range(rc)]
        # each model vertex's bones (global) through the skin's sections, with its weights
        self.bones = np.zeros((nv, 4), dtype=np.int32)
        for sec in self.sections:
            vs, vc, combo = sec[2] + 65536 * sec[1], sec[3], sec[7]
            for k in range(vs, vs + vc):
                g = self.skin_vertices[k]
                for j in range(4):
                    self.bones[g, j] = self.lookup[combo + self.props[k, j]] if self.raw[g, 12 + j] else 0

    def batch_triangles(self, index):
        b = self.batches[index]
        sec = self.sections[b[3]]
        first = sec[4] + 65536 * sec[1]
        return self.skin_vertices[self.indices[first:first + sec[5]]].reshape(-1, 3), b

    def under(self, bone, root):
        while bone >= 0:
            if bone == root:
                return True
            bone = self.parent[bone]
        return False


def pieces(src, triangles):
    """The batch's connected pieces (vertices at one place joined), each a list of triangle rows."""
    weld = {}
    ids = np.zeros(len(src.pos), dtype=np.int64)
    for v in np.unique(triangles):
        ids[v] = weld.setdefault(tuple(np.round(src.pos[v], 4)), len(weld))
    parent = list(range(len(weld)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    for t in triangles:
        a = find(ids[t[0]])
        for v in t[1:]:
            b = find(ids[v])
            if b != a:
                parent[b] = a
    groups = {}
    for i, t in enumerate(triangles):
        groups.setdefault(find(ids[t[0]]), []).append(i)
    return [np.array(g) for g in groups.values()]


def part_triangles(src, base, spec):
    """The triangles (source vertex indices) of a part (murloc_looks.PARTS)."""
    triangles, batch = src.batch_triangles(spec["batch"])
    take = spec["take"]
    kind = take[0]
    if kind in ("top", "below", "centre"):
        keep = []
        for group in pieces(src, triangles):
            vs = np.unique(triangles[group])
            if kind == "top" and src.pos[vs, 2].max() > take[1]:
                keep.extend(group)
            elif kind == "below" and src.pos[vs, 2].max() <= take[1]:
                keep.extend(group)
            elif kind == "centre":
                c = src.pos[vs].mean(axis=0)
                x0, x1, y0, y1, z0, z1 = take[1:]
                if x0 <= c[0] <= x1 and y0 <= c[1] <= y1 and z0 <= c[2] <= z1 and len(group) < 1000:
                    keep.extend(group)
        return triangles[sorted(keep)], batch
    dominant = src.bones[np.arange(len(src.pos)), src.raw[:, 12:16].argmax(axis=1)]
    if kind in ("head", "body"):
        head = src.key.index(6)
        on_head = np.array([all(src.under(dominant[v], head) for v in t) for t in triangles])
        return triangles[on_head if kind == "head" else ~on_head], batch
    if kind == "added":
        extra = set(int(b) for b in np.where(np.linalg.norm(src.pivot[:, None] - base.pivot[None], axis=2).min(axis=1) > 0.01)[0])
        x0, x1, y0, y1, z0, z1 = take[1:]
        keep = []
        for i, t in enumerate(triangles):
            c = src.pos[t].mean(axis=0)
            if all(int(dominant[v]) in extra for v in t) and x0 <= c[0] <= x1 and y0 <= c[1] <= y1 and z0 <= c[2] <= z1:
                keep.append(i)
        return triangles[keep], batch
    raise ValueError("a part taken by %r" % (kind,))


def bone_map(src, base):
    """Each of [src]'s bones as murloc2's: the one at its place, else its nearest parent's."""
    out = []
    for b in range(len(src.pivot)):
        a = b
        while True:
            d = np.linalg.norm(base.pivot - src.pivot[a], axis=1)
            if d.min() < 0.01:
                out.append(int(d.argmin()))
                break
            a = src.parent[a]
            if a < 0:
                out.append(base.key.index(6) if 6 in base.key else 0)
                break
    return out


# The model, made ---------------------------------------------------------------------------------

def build_skin(blob, raw, vbones, parts):
    """3.3.5's skin for [parts] (geoset, triangles of model vertices, texture lookup index, material
    index, priority): sections of at most MAX_BONES bones, their batches; the model's bone lookup."""
    out_vertices, out_props, out_indices, out_sections, out_batches, out_lookup = [], [], [], [], [], []
    for geoset, triangles, texture, material, priority in parts:
        groups = []
        for tri in triangles:
            used = {int(vbones[v, j]) for v in tri for j in range(4) if raw[v, 12 + j]}
            if not groups or len(set(groups[-1][0]) | used) > MAX_BONES:
                groups.append([[], []])
            palette = groups[-1][0]
            for b in sorted(used):
                if b not in palette:
                    palette.append(b)
            groups[-1][1].append(tri)
        for palette, tris in groups:
            place = {}
            vertex_start, index_start = len(out_vertices), len(out_indices)
            influences = 0
            centre = raw[np.unique(np.array(tris)), 0:12].copy().view("<f4").reshape(-1, 3).mean(axis=0)
            for tri in tris:
                for v in tri:
                    if v not in place:
                        place[v] = len(out_vertices)
                        out_vertices.append(int(v))
                        slot = [0, 0, 0, 0]
                        n = 0
                        for j in range(4):
                            if raw[v, 12 + j]:
                                slot[j] = palette.index(int(vbones[v, j]))
                                n += 1
                        influences = max(influences, n)
                        out_props.append(bytes(slot))
                    out_indices.append(place[v])
            combo = len(out_lookup)
            out_lookup.extend(palette)
            radius = float(np.linalg.norm(raw[np.unique(np.array(tris)), 0:12].copy().view("<f4").reshape(-1, 3) - centre, axis=1).max())
            out_sections.append(struct.pack("<10H7f", geoset, index_start >> 16, vertex_start & 0xFFFF, len(place),
                                            index_start & 0xFFFF, len(tris) * 3, len(palette), combo, max(1, influences),
                                            palette[0] if palette else 0, *centre, *centre, radius))
            # flags 16 (as 3.3.5's own), shader 0, one texture, no texture transform, first transparency
            out_batches.append(struct.pack("<BbHHHhHHHHHHH", 16, priority, 0, len(out_sections) - 1, len(out_sections) - 1,
                                           -1, material, 0, 1, texture, 0, 0, 0))
    if len(out_vertices) > 65535:
        raise ValueError("too many vertices for one skin")
    header = 48
    body = bytearray()

    def place(payload):
        while (header + len(body)) % 16:
            body.append(0)
        at = header + len(body)
        body.extend(payload)
        return at
    arrays = [
        (len(out_vertices), place(struct.pack("<%dH" % len(out_vertices), *out_vertices))),
        (len(out_indices), place(struct.pack("<%dH" % len(out_indices), *out_indices))),
        (len(out_props), place(b"".join(out_props))),
        (len(out_sections), place(b"".join(out_sections))),
        (len(out_batches), place(b"".join(out_batches))),
    ]
    head = bytearray(b"SKIN")
    for count, offset in arrays:
        head += struct.pack("<II", count, offset)
    head += struct.pack("<I", MAX_BONES)
    blob.set_array(H_BONE_LOOKUP, len(out_lookup), blob.append(struct.pack("<%dH" % len(out_lookup), *out_lookup)))
    return bytes(head) + bytes(body)


def make_model(read, sex):
    """The murloc's model for [sex] (its skin, .anim files and the parts' texture ids by name)."""
    name = "Murloc" + sex
    folder = "%s/%s" % (FOLDER, sex)
    models.NAME, models.FOLDER = name, folder
    base = Source(read, L.BASE_MODEL)
    se = base.sections
    models.SHOWN_GEOSETS = {s[0] for s in se}
    m2 = read(L.BASE_MODEL)
    model, _, needed, anims = models.convert_model(m2, base.skin)
    blob = models.Blob(model)

    # The base's own triangles: the eyes (the smaller batch) and the body.
    own = []
    for i in range(len(base.batches)):
        tris, batch = base.batch_triangles(i)
        own.append((tris, batch))
    own.sort(key=lambda tb: len(tb[0]))
    eyes, body = own[0], own[-1]

    raw = base.raw.copy()
    vbones = base.bones.copy()
    # the eyes read FaceLower: their coordinates from the skin's eye region to it
    eye_vertices = np.unique(eyes[0])
    uv = raw[:, 32:40].copy().view("<f4").reshape(-1, 2)
    u0, v0, u1, v1 = L.EYES
    f0, g0, f1, g1 = FACE_LOWER
    duplicate = {}
    for v in eye_vertices:                  # (a vertex the body shares gets a copy for the eyes)
        duplicate[int(v)] = len(raw) + len(duplicate)
    extra_raw = raw[eye_vertices].copy()
    extra_uv = uv[eye_vertices].copy()
    extra_uv[:, 0] = f0 + np.clip((extra_uv[:, 0] - u0) / (u1 - u0), 0, 1) * (f1 - f0)
    extra_uv[:, 1] = g0 + np.clip((extra_uv[:, 1] - v0) / (v1 - v0), 0, 1) * (g1 - g0)
    extra_raw[:, 32:40] = extra_uv.astype("<f4").view(np.uint8).reshape(-1, 8)
    raw = np.concatenate([raw, extra_raw])
    vbones = np.concatenate([vbones, base.bones[eye_vertices]])
    eye_triangles = np.vectorize(lambda v: duplicate[int(v)])(eyes[0])

    # textures: the body's skin extra, the composed texture, the hair, then each part's own
    textures = [(TYPE_SKIN_EXTRA, ""), (TYPE_SKIN, ""), (TYPE_HAIR, "")]
    part_textures = {}
    parts = [(0, body[0], 0, body[1][6], 0), (0, eye_triangles, 1, eyes[1][6], 0)]
    materials = list(base.materials)
    sources = {}
    styles = [(0, s, p) for s, p in enumerate(L.HAIR_STYLES[sex])] + [(1, s, p) for s, p in enumerate(L.FEATURES[sex])]
    for group, style, names in styles:
        geoset = (L.hair_geoset(style) if group == 0 else 100 + L.feature_geoset(style))
        for part in names:
            spec = L.PARTS[part]
            src = sources.get(spec["model"])
            if src is None:
                src = sources[spec["model"]] = Source(read, spec["model"])
            tris, batch = part_triangles(src, base, spec)
            if not len(tris):
                raise ValueError("%s: no triangles taken" % part)
            mapping = bone_map(src, base)
            vs = np.unique(tris)
            at = {int(v): len(raw) + i for i, v in enumerate(vs)}
            add = src.raw[vs].copy()
            add_bones = np.array([[mapping[int(src.bones[v, j])] for j in range(4)] for v in vs], dtype=np.int32)
            for i in range(len(vs)):                       # (the vertex's own bone indices, the model's)
                add[i, 16:20] = np.array([b if add[i, 12 + j] else 0 for j, b in enumerate(add_bones[i])], dtype=np.uint8) \
                    if add_bones.max() < 256 else add[i, 16:20]
            raw = np.concatenate([raw, add])
            vbones = np.concatenate([vbones, add_bones])
            if spec.get("hair"):
                tex_index = 2
            else:
                key = spec["texture"]
                if key not in part_textures:
                    part_textures[key] = len(textures)
                    textures.append((TYPE_HARDCODED, "%s\\Murloc%s.blp" % (FOLDER.replace("/", "\\"), L.TEXTURE_NAMES[key])))
                tex_index = part_textures[key]
            mat = src.materials[batch[6]] if batch[6] < len(src.materials) else b"\0\0\0\0"
            if mat not in materials:
                materials.append(mat)
            parts.append((geoset, np.vectorize(lambda v: at[int(v)])(tris), tex_index, materials.index(mat), 0))

    # the arrays the header names, made again
    blob.set_array(H_VERTS, len(raw), blob.append(raw.tobytes()))
    tex_bytes, names = bytearray(), {"": blob.append(b"\0")}
    for kind, path in textures:
        if path not in names:
            names[path] = blob.append(path.encode() + b"\0")
    for kind, path in textures:
        tex_bytes += struct.pack("<IIII", kind, 0, len(path) + 1, names[path])
    blob.set_array(H_TEXTURES, len(textures), blob.append(bytes(tex_bytes)))
    blob.set_array(H_TEX_LOOKUP, len(textures), blob.append(struct.pack("<%dh" % len(textures), *range(len(textures)))))
    replace = [-1] * (max(k for k, _ in textures) + 1)
    for i, (kind, _) in enumerate(textures):
        if kind and replace[kind] < 0:
            replace[kind] = i
    blob.set_array(H_TEX_REPLACE, len(replace), blob.append(struct.pack("<%dh" % len(replace), *replace)))
    blob.set_array(H_RENDER_FLAGS, len(materials), blob.append(b"".join(materials)))
    count, _ = blob.array(H_TRANSP_LOOKUP)
    if count == 0:
        blob.set_array(H_TRANSP_LOOKUP, 1, blob.append(struct.pack("<h", 0)))
    blob.set_array(H_UV_LOOKUP, 1, blob.append(struct.pack("<h", -1)))
    skin = build_skin(blob, raw, vbones, parts)
    pos = raw[:, 0:12].copy().view("<f4").reshape(-1, 3)
    lo, hi = pos.min(axis=0), pos.max(axis=0)
    struct.pack_into("<6ff", blob.data, H_BOUNDS, *lo, *hi, float(np.linalg.norm(hi - lo) / 2))
    one_set_a_sequence(blob.data)
    textures_needed = {fid: path for fid, path in needed.items()}
    return bytes(blob.data), skin, anims, textures_needed, part_textures


# The textures ------------------------------------------------------------------------------------

def rgba(read, file_id):
    return np.asarray(blp.read(read(file_id)), dtype=np.uint8)


def hls_recolour(image, hue, saturation, lightness):
    import colorsys
    out = image.copy()
    flat = out.reshape(-1, 4)
    cache = {}
    for i in range(len(flat)):
        key = tuple(flat[i, :3])
        if key not in cache:
            h, l, s = colorsys.rgb_to_hls(*(c / 255.0 for c in key))
            h = h if hue < 0 else hue
            l = min(1.0, l * lightness)
            s = min(1.0, s * saturation)
            cache[key] = tuple(int(round(c * 255)) for c in colorsys.hls_to_rgb(h, l, s))
        flat[i, :3] = cache[key]
    return out


def eyes_texture(skin):
    """FaceLower (256 x 128) from a skin's eye region."""
    h, w = skin.shape[:2]
    u0, v0, u1, v1 = L.EYES
    crop = skin[int(v0 * h):int(round(v1 * h)), int(u0 * w):int(round(u1 * w))]
    image = np.asarray(Image.fromarray(crop).resize((COMPOSED // 2, COMPOSED // 4), Image.Resampling.BICUBIC)).copy()
    image[:, :, 3] = 255
    return blp.write_palette(image, alpha=False)


def convert(read, folder):
    files = {}
    for sex in ("Male", "Female"):
        model, skin, anims, needed, part_textures = make_model(read, sex)
        files["%s/%s/Murloc%s.m2" % (FOLDER, sex, sex)] = model
        files["%s/%s/Murloc%s00.skin" % (FOLDER, sex, sex)] = skin
        for file_id, name in anims.items():
            files[name] = models.anim_payload(read(file_id))
    for file_id, name in L.TEXTURE_NAMES.items():
        files["%s/Murloc%s.blp" % (FOLDER, name)] = read(file_id)
    for i, (file_id, _) in enumerate(L.SKINS):
        data = read(file_id)
        files["%s/MurlocSkin%02d.blp" % (FOLDER, i)] = data
        files["%s/MurlocEyes%02d.blp" % (FOLDER, i)] = eyes_texture(np.asarray(blp.read(data), dtype=np.uint8))
    base = np.zeros((COMPOSED, COMPOSED, 4), dtype=np.uint8)
    base[:, :] = (96, 96, 96, 255)
    files["%s/MurlocBase.blp" % FOLDER] = blp.write_palette(base, alpha=False)
    hair = rgba(read, L.PARTS["Hair"]["texture"])
    for i, (_, hue, saturation, lightness) in enumerate(L.HAIR_COLOURS):
        image = hair if hue < 0 and saturation == 1.0 and lightness == 1.0 else hls_recolour(hair, hue, saturation, lightness)
        files["%s/MurlocHair%02d.blp" % (FOLDER, i)] = blp.write_dxt(image, alpha=True)
    for name, data in files.items():
        if name.endswith(".blp") and data[:4] != b"BLP2":
            raise ValueError("%s isn't a BLP2 texture" % name)
    for name, data in files.items():
        path = os.path.join(folder, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as out:
            out.write(data)
    with open(os.path.join(folder, MANIFEST), "w") as out:
        out.write("".join(name + "\n" for name in sorted(files)))
    print("murloc: %d files in %s" % (len(files), folder))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "fetch":
        convert(cached(os.environ.get("MURLOC_CACHE"), wago), sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "convert":
        convert(cached(sys.argv[2], None), sys.argv[3])
    else:
        print(__doc__)
        sys.exit(2)
