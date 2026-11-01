#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The naga's story's people the 3.3.5 game has no model for, made 3.3.5 creature models from today's
game's (tools/naga/looks.py names their looks):

  - Serena Scarscale's sea witch: Battle for Azeroth's (creature/seawitch/seawitch.m2, 1530843);
  - Maiev's Watchers: Legion's warden (creature/warden/warden.m2, 1133752), in its four skins;
  - Queen Azshara as a night elf: Cataclysm's (creature/azshara/azshara.m2, 535719), for the
    Highborne court's memory.

    python3 tools/naga_models.py fetch DIR          fetches them from wago.tools by their file ids and
                                                     writes DIR/Creature/<Name>/..., then DIR/nagamodels.files
    python3 tools/naga_models.py convert SRC DIR    the same from files already fetched (named by id)

The files are the game's, so they are never in this repository: the server package makes them
(scripts/package-azerothcore.sh) into the module's data/files; the world server puts them in the
game's patch-<locale>-T beside their CreatureModelData and CreatureDisplayInfo rows
(src/MurlocNagaPatch.cpp; tools/naga_assets.py makes the rows). The list is written last; without it
the patch leaves the models out and logs it, and their looks draw nothing (the story's people who wear
them keep a look of the game's: tools/naga/looks.py says which).

What changes from today's format to 3.3.5's, as Wrath Remix's converter does it
(mod-wrath-remix/tools/models.py: the model is the MD20 inside the chunked file, version 264, its
textures named, its cameras in 3.3.5's layout, its particles, ribbons and lights left out, its mesh
sections split to at most 64 bones, its external animations as 3.3.5 .anim files), and:
  - every sequence with an id 3.3.5 hasn't (today's ids past 505: their tracks' entries too, and an
    alias of one) is left out: the game would never ask for them;
  - an alias sequence is kept in the model (it has no data of its own: 3.3.5 never looks for its file);
  - each batch keeps its own first texture (the creature's skins, or its own: today's second texture
    is a newer shader's, which 3.3.5 hasn't);
  - textures over 1024 pixels a side are made 1024 (DXT, as they were).
Every emote each model has is kept (tools/naga/looks.py lists them).
"""
import os
import struct
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-wrath-remix", "tools"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "mod-allied-races", "tools"))
import blp  # noqa: E402
import models  # noqa: E402
import murloc_anims  # noqa: E402
import murloc_model as M  # noqa: E402
from naga import looks as LK  # noqa: E402

MANIFEST = "nagamodels.files"
MAX_BONES = 64
MAX_TEXTURE = 1024
LAST_ANIMATION = 505
SEQ = 64
IN_MODEL, ALIAS = 0x20, 0x40
TEXTURE_SKINS = (11, 12, 13)       # a creature's three replaceable textures (its look's)
H = models                          # (the 3.3.5 header's places)


def drop_sequences(blob):
    """Leaves out the sequences 3.3.5 has no id for (and aliases of them): every track's entries
    with them. Returns the kept sequences' (id, variation) and their old indices."""
    m = blob.data
    n, at = struct.unpack_from("<II", m, H.H_SEQS)
    records = [bytearray(m[at + SEQ * k:at + SEQ * (k + 1)]) for k in range(n)]
    ids = [struct.unpack_from("<HH", r, 0) for r in records]
    keep = [a <= LAST_ANIMATION for a, _ in ids]
    for _ in range(8):                                   # (an alias of one left out goes too)
        for k, r in enumerate(records):
            flags, alias = struct.unpack_from("<I", r, 12)[0], struct.unpack_from("<H", r, 62)[0]
            if keep[k] and flags & ALIAS and (alias >= n or not keep[alias]):
                keep[k] = False
    kept = [k for k in range(n) if keep[k]]
    new_index = {k: i for i, k in enumerate(kept)}
    if len(kept) == n:
        return ids, kept
    for track_at, size, _, _ in murloc_anims.tracks(bytes(m)):
        interp, gseq, tn, to, vn, vo = struct.unpack_from("<HhIIII", m, track_at)
        if gseq >= 0 or tn == 0:
            continue
        if tn != n or (size and vn != n):
            raise ValueError("a track with %d entries for %d sequences" % (tn, n))
        times = b"".join(bytes(m[to + 8 * k:to + 8 * k + 8]) for k in kept)
        struct.pack_into("<II", m, track_at + 4, len(kept), blob.append(times))
        if size:
            values = b"".join(bytes(m[vo + 8 * k:vo + 8 * k + 8]) for k in kept)
            struct.pack_into("<II", m, track_at + 12, len(kept), blob.append(values))
    out = bytearray()
    for k in kept:
        r = records[k]
        variation_next, alias = struct.unpack_from("<hH", r, 60)
        struct.pack_into("<hH", r, 60, new_index.get(variation_next, -1) if variation_next >= 0 else -1, new_index.get(alias, new_index[k]))
        out += r
    blob.set_array(H.H_SEQS, len(kept), blob.append(bytes(out)))
    return [ids[k] for k in kept], kept


def sequence_lookup(blob):
    n, at = blob.array(H.H_SEQS)
    ids = [struct.unpack_from("<H", blob.data, at + SEQ * k)[0] for k in range(n)]
    table = [-1] * (max(ids) + 1)
    for k, a in enumerate(ids):
        if table[a] < 0:
            table[a] = k
    blob.set_array(H.H_SEQ_LOOKUP, len(table), blob.append(struct.pack("<%dh" % len(table), *table)))


def convert_skin(skin, blob):
    """3.3.5's skin: every section, split so each has at most MAX_BONES bones, each batch drawing its
    own first texture (one texture, shader 0)."""
    sv_n, sv_o = struct.unpack_from("<II", skin, 4)
    ix_n, ix_o = struct.unpack_from("<II", skin, 12)
    pr_n, pr_o = struct.unpack_from("<II", skin, 20)
    se_n, se_o = struct.unpack_from("<II", skin, 28)
    ba_n, ba_o = struct.unpack_from("<II", skin, 36)
    skin_vertices = struct.unpack_from("<%dH" % sv_n, skin, sv_o)
    indices = struct.unpack_from("<%dH" % ix_n, skin, ix_o)
    properties = [skin[pr_o + i * 4:pr_o + i * 4 + 4] for i in range(pr_n)]
    vcount, voff = blob.array(H.H_VERTS)
    lookup_count, lookup_offset = blob.array(H.H_BONE_LOOKUP)
    lookup = struct.unpack_from("<%dH" % lookup_count, blob.data, lookup_offset)
    batches = {}
    for i in range(ba_n):
        b = struct.unpack_from("<BbHHHhHHHHHHH", skin, ba_o + i * 24)
        batches.setdefault(b[3], []).append(b)
    out_vertices, out_props, out_indices, out_sections, out_batches, out_lookup = [], [], [], [], [], []
    for s in range(se_n):
        sec = struct.unpack_from("<10H7f", skin, se_o + s * 48)
        if s not in batches:
            continue
        level, i_start, i_count, combo = sec[1], sec[4], sec[5], sec[7]
        first_index = i_start + level * 65536
        bones_of = {}
        for k in set(indices[first_index:first_index + i_count]):
            g = skin_vertices[k]
            weights = blob.data[voff + g * 48 + 12:voff + g * 48 + 16]
            bones_of[k] = [(lookup[combo + properties[k][j]], weights[j], j) for j in range(4) if weights[j]]
        groups = []
        for t in range(first_index, first_index + i_count, 3):
            tri = indices[t:t + 3]
            used = {b for k in tri for b, _, _ in bones_of[k]}
            if not groups or len(set(groups[-1][0]) | used) > MAX_BONES:
                groups.append([[], []])
            for b in sorted(used):
                if b not in groups[-1][0]:
                    groups[-1][0].append(b)
            groups[-1][1].append(tri)
        for palette, triangles in groups:
            place = {}
            vertex_start, index_start = len(out_vertices), len(out_indices)
            influences = 0
            for tri in triangles:
                for k in tri:
                    if k not in place:
                        place[k] = len(out_vertices)
                        out_vertices.append(skin_vertices[k])
                        slot = [0, 0, 0, 0]
                        for b, _, j in bones_of[k]:
                            slot[j] = palette.index(b)
                        influences = max(influences, len(bones_of[k]))
                        out_props.append(bytes(slot))
                    out_indices.append(place[k])
            combo_index = len(out_lookup)
            out_lookup.extend(palette)
            out_sections.append(struct.pack("<10H7f", sec[0], index_start >> 16, vertex_start & 0xFFFF, len(place),
                                            index_start & 0xFFFF, len(triangles) * 3, len(palette), combo_index,
                                            max(1, influences), palette[0] if palette else 0, *sec[10:17]))
            for b in batches[s]:
                # its own first texture alone (shader 0), its material, transparency and texture animation
                out_batches.append(struct.pack("<BbHHHhHHHHHHH", 16, b[1], 0, len(out_sections) - 1, len(out_sections) - 1,
                                               -1, b[6], 0, 1, b[9], 0, b[11], b[12]))
    if len(out_vertices) > 65535 or len(out_indices) > 65535 * 3:
        raise ValueError("too big for one 3.3.5 skin")
    if len(out_indices) > 65535:
        raise ValueError("more indices than 3.3.5 reads a section's start in")
    header, body = 48, bytearray()

    def place_it(payload):
        while (header + len(body)) % 16:
            body.append(0)
        at = header + len(body)
        body.extend(payload)
        return at
    arrays = [(len(out_vertices), place_it(struct.pack("<%dH" % len(out_vertices), *out_vertices))),
              (len(out_indices), place_it(struct.pack("<%dH" % len(out_indices), *out_indices))),
              (len(out_props), place_it(b"".join(out_props))),
              (len(out_sections), place_it(b"".join(out_sections))),
              (len(out_batches), place_it(b"".join(out_batches)))]
    head = bytearray(b"SKIN")
    for count, offset in arrays:
        head += struct.pack("<II", count, offset)
    head += struct.pack("<I", MAX_BONES)
    blob.set_array(H.H_BONE_LOOKUP, len(out_lookup), blob.append(struct.pack("<%dH" % len(out_lookup), *out_lookup)))
    return bytes(head) + bytes(body)


def texture(data):
    """A texture of today's as 3.3.5 can have it: as it is, or, past 1024 a side, made 1024 (DXT)."""
    if data[:4] != b"BLP2":
        raise ValueError("not a BLP2 texture")
    width, height = struct.unpack_from("<II", data, 12)
    if max(width, height) <= MAX_TEXTURE:
        return data
    image = np.asarray(blp.read(data))
    f = MAX_TEXTURE / float(max(width, height))
    small = np.asarray(Image.fromarray(image).resize((max(1, int(width * f)), max(1, int(height * f))), Image.Resampling.LANCZOS))
    return blp.write_dxt(np.ascontiguousarray(small), alpha=data[9] > 0)


def convert_creature(read, creature):
    """[creature] (looks.CREATURES' entry) as 3.3.5's: {path: bytes}."""
    name, folder, file_id = creature["name"], creature["folder"], creature["model"]
    m2 = read(file_id)
    chunks = models.chunks_of(m2)
    md20 = chunks["MD21"]
    if md20[:4] != b"MD20":
        raise ValueError("%s isn't a model" % name)
    blob = models.Blob(md20)
    struct.pack_into("<I", blob.data, 4, 264)
    struct.pack_into("<I", blob.data, H.H_FLAGS, struct.unpack_from("<I", blob.data, H.H_FLAGS)[0] & 0x3)
    title = name.encode() + b"\0"
    blob.set_array(H.H_NAME, len(title), blob.append(title))
    # nothing 3.3.5 reads differently: particles, ribbons, lights; cameras in its layout
    for field in (H.H_PARTICLES, H.H_RIBBONS, H.H_LIGHTS):
        blob.set_array(field, 0, 0)
    count, offset = blob.array(H.H_CAMERAS)
    cameras = bytearray()
    for i in range(count):
        at = offset + i * 116
        kind, far, near = struct.unpack_from("<Iff", blob.data, at)
        fov = 0.95
        n_seq, seq_at = struct.unpack_from("<II", blob.data, at + 96 + 12)
        if n_seq:
            n_keys, keys_at = struct.unpack_from("<II", blob.data, seq_at)
            if n_keys and keys_at + 4 <= len(md20):
                value = struct.unpack_from("<f", blob.data, keys_at)[0]
                fov = value if 0.1 < value < 3.0 else fov
        cameras += struct.pack("<Ifff", kind, fov, far, near) + blob.data[at + 12:at + 96]
    if count:
        blob.set_array(H.H_CAMERAS, count, blob.append(bytes(cameras)))
    # a track with one set of keys for every sequence (today's way) given one set a sequence; the
    # sequences 3.3.5 knows, an alias kept in the model, their lookup by id
    M.one_set_a_sequence(blob.data)
    ids, kept = drop_sequences(blob)
    n, at = blob.array(H.H_SEQS)
    for k in range(n):
        flags = struct.unpack_from("<I", blob.data, at + SEQ * k + 12)[0]
        if flags & ALIAS:
            struct.pack_into("<I", blob.data, at + SEQ * k + 12, flags | IN_MODEL)
    sequence_lookup(blob)
    # a texture animation's lookup naming one the model hasn't: none
    transforms = blob.array(H.H_TEX_TRANSFORMS)[0]
    count, offset = blob.array(H.H_TRANSFORM_LOOKUP)
    for i in range(count):
        if struct.unpack_from("<h", blob.data, offset + i * 2)[0] >= transforms:
            struct.pack_into("<h", blob.data, offset + i * 2, -1)
    # textures: the hard-coded ones named (their file ids are in TXID); the creature's skins kept
    file_ids = struct.unpack("<%dI" % (len(chunks["TXID"]) // 4), chunks["TXID"]) if "TXID" in chunks else ()
    count, offset = blob.array(H.H_TEXTURES)
    files = {}
    path_of = lambda i: "%s\\%s%d.blp" % (folder.replace("/", "\\"), name, i)
    for i in range(count):
        kind = struct.unpack_from("<I", blob.data, offset + i * 16)[0]
        if kind == 0 and i < len(file_ids) and file_ids[i]:
            path = path_of(i).encode() + b"\0"
            struct.pack_into("<II", blob.data, offset + i * 16 + 8, len(path), blob.append(path))
            files[path_of(i).replace("\\", "/")] = texture(read(file_ids[i]))
        elif kind not in (0,) + TEXTURE_SKINS:
            raise ValueError("%s: a texture of type %d" % (name, kind))
    blob.set_array(H.H_TEX_UNIT_LOOKUP, 1, blob.append(struct.pack("<hh", 0, 0)))
    sfid = struct.unpack("<%dI" % (len(chunks["SFID"]) // 4), chunks["SFID"])
    skin = convert_skin(read(sfid[0]), blob)
    struct.pack_into("<I", blob.data, H.H_VIEWS, 1)
    files["%s/%s.m2" % (folder, name)] = bytes(blob.data)
    files["%s/%s00.skin" % (folder, name)] = skin
    # the external animations (3.3.5 names them by id and variation)
    anims = {}
    if "AFID" in chunks:
        raw = chunks["AFID"]
        for i in range(0, len(raw), 8):
            anim, sub, fid = struct.unpack_from("<HHI", raw, i)
            anims[(anim, sub)] = fid
    n, at = blob.array(H.H_SEQS)
    for k in range(n):
        anim, sub, _, _, flags = struct.unpack_from("<HHIfI", blob.data, at + SEQ * k)
        if flags & IN_MODEL:
            continue
        fid = anims.get((anim, sub))
        if not fid:
            raise ValueError("%s: animation %d-%d has no file" % (name, anim, sub))
        files["%s/%s%04d-%02d.anim" % (folder, name, anim, sub)] = models.anim_payload(read(fid))
    # its looks' skins
    for skin_name, fid in creature["skins"].items():
        files["%s/%s.blp" % (folder, skin_name)] = texture(read(fid))
    return files


def convert(read, folder):
    files = {}
    for creature in LK.CREATURES:
        made = convert_creature(read, creature)
        print("naga models: %s, %d files" % (creature["name"], len(made)))
        files.update(made)
    for name, data in files.items():
        path = os.path.join(folder, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as out:
            out.write(data)
    with open(os.path.join(folder, MANIFEST), "w", newline="\n") as out:
        out.write("".join(name + "\n" for name in sorted(files)))
    print("naga models: %d files in %s" % (len(files), folder))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "fetch":
        convert(M.cached(os.environ.get("MURLOC_CACHE"), M.wago), sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "convert":
        convert(M.cached(sys.argv[2], None), sys.argv[3])
    else:
        print(__doc__)
        sys.exit(2)
