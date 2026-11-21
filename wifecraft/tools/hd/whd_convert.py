"""whd_convert.py: converts the user's own HD character .blend (and its textures) into Wifecraft HD model sets (.whd), one per
variant of a recipe (tools/hd/recipes/*.json), and zips them for the phone's Options -> Mods -> HD Models import.

Desktop only, run inside Blender's Python (the bpy module):
    python -I tools/hd/whd_convert.py RECIPE.json SOURCE.blend TEXTURE_DIR OUT_DIR [--zip NAME.zip] [--only FILE.whd]

The .blend and its textures are the user's own downloads (third-party art): they and the .whd/.zip made from them are never
committed or shipped. Nothing in the .blend is executed (its drivers and scripts stay off: Blender's Python runs only this file;
autoexec is off in the bpy module unless asked for).

What it does, per variant (README "HD Models", the .whd layout: README "HD Models"):
  - every object the recipe names (the body, eyes, brows, hair styles, outfits, jewellery), evaluated at the rig's REST pose
    with only shape keys and hooks (the armature, subdivision, masks and smoothing modifiers off), decimated to its triangle
    budget, in Warcraft axes (x forward, y left, z up; metres, feet at z = 0);
  - its weights: the rig's authored vertex-group weights, each bone mapped to the game's skeleton by the recipe's `weights`
    table (a bone not in it takes its nearest mapped ancestor's name), four per vertex, normalised; an object marked `rigid`
    is all on that bone; an object without weights takes the body's nearest vertex's;
  - the rig's joints (the recipe's `joints`: each game bone's place on the HD figure), for the engine's fit;
  - its textures (each material's Base Color image, PNG, at most `textureMax` px, or its own size), outfits' hidden body
    triangles (the body's skin under each outfit, so it can't poke through), overlays (face and body marks), tint presets;
  - the human variant: ears reshaped (`EarsSmall` and a displacement toward a rounded human ear) and the skin recoloured
    toward a human tone (a Lab hue/chroma shift of the base colour maps).
"""
import bpy, bmesh, json, math, os, struct, subprocess, sys, zipfile, zlib
import numpy as np
from mathutils import Vector
from mathutils.bvhtree import BVHTree
from mathutils.kdtree import KDTree

WHD_VERSION = 1


def log(*a):
    print("[whd]", *a, flush=True)


def args():
    a = sys.argv[1:]
    if '--' in a: a = a[a.index('--') + 1:]
    opt = {'zip': None, 'only': None}
    pos = []
    i = 0
    while i < len(a):
        if a[i] == '--zip': opt['zip'] = a[i + 1]; i += 2
        elif a[i] == '--only': opt['only'] = a[i + 1]; i += 2
        else: pos.append(a[i]); i += 1
    if len(pos) < 4: raise SystemExit(__doc__)
    return pos[0], pos[1], pos[2], pos[3], opt


# ---------------------------------------------------------------- the scene
def to_wow(v, forward):
    # Blender world -> Warcraft axes: the figure's forward becomes +x, its left +y, z stays up
    x, y, z = v[0], v[1], v[2]
    if forward == '-y': return (-y, x, z)
    if forward == '+y': return (y, -x, z)
    if forward == '-x': return (-x, -y, z)
    return (x, y, z)


def open_blend(path, texdir):
    bpy.ops.wm.open_mainfile(filepath=path, load_ui=False, use_scripts=False)
    for sub in sorted(os.listdir(texdir)) + ['']:
        d = os.path.join(texdir, sub)
        if os.path.isdir(d):
            try: bpy.ops.file.find_missing_files(directory=d + os.sep, find_all=True)
            except Exception as e: log("find_missing_files", d, e)


def norm(name):
    """A rig bone's name as the recipes match it: case and underscores ignored (M_Neck_01 is M_Neck01, M_spine_01 M_Spine01)."""
    return name.lower().replace('_', '')


def bone_chain_map(arms, table):
    """Every bone of the rigs -> the game bone of itself or its nearest mapped ancestor (None: none). Names are matched
    without case or underscores (norm)."""
    t = {norm(k): v for k, v in table.items()}
    out = {}
    for arm in arms:
        for b in arm.data.bones:
            c = b
            while c is not None and norm(c.name) not in t: c = c.parent
            out[b.name] = t[norm(c.name)] if c is not None else None
    return out


def image_of(mat, ignore):
    """The material's Base Color image (followed upstream from its Principled BSDF), skipping the recipe's ignored ones."""
    if not mat or not mat.node_tree: return None
    def up(sock, depth):
        for l in sock.links:
            n = l.from_node
            if n.type == 'TEX_IMAGE' and n.image and not any(s in n.image.name or s in n.image.filepath for s in ignore):
                p = bpy.path.abspath(n.image.filepath)
                if os.path.exists(p): return p
            if depth < 12:
                for i in n.inputs:
                    r = up(i, depth + 1)
                    if r: return r
        return None
    for n in mat.node_tree.nodes:
        if n.type == 'BSDF_PRINCIPLED':
            r = up(n.inputs['Base Color'], 0)
            if r: return r
    for n in mat.node_tree.nodes:   # any image at all (a diffuse-only material)
        if n.type == 'TEX_IMAGE' and n.image and not any(s in n.image.name for s in ignore):
            p = bpy.path.abspath(n.image.filepath)
            if os.path.exists(p): return p
    return None


def evaluate(obj, recipe, keys, max_tris):
    """(positions (n,3) Blender world, per-vertex groups [(name, w)], triangles [(loop vertex ids, uvs, normals, material)], material images)."""
    skip = set(recipe.get('skipModifiers', []))
    saved = []
    for md in obj.modifiers:
        saved.append((md, md.show_viewport))
        if md.type in skip: md.show_viewport = False
    sk = obj.data.shape_keys
    saved_keys = []
    if sk:
        for kb in sk.key_blocks:
            if kb.name in keys:
                saved_keys.append((kb, kb.value, kb.mute))
                kb.value = keys[kb.name]; kb.mute = False
    # a hidden object (or one in a hidden collection) isn't evaluated by the depsgraph: shown, and linked to the scene, for now
    vis = (obj.hide_viewport, obj.hide_get())
    linked = False
    if obj.name not in bpy.context.view_layer.objects or not obj.visible_get():
        try: bpy.context.scene.collection.objects.link(obj); linked = True
        except RuntimeError: pass
    obj.hide_viewport = False
    try: obj.hide_set(False)
    except RuntimeError: pass
    tris0 = sum(len(p.vertices) - 2 for p in obj.data.polygons)
    dec = None
    if max_tris and tris0 > max_tris:
        dec = obj.modifiers.new('whd_decimate', 'DECIMATE')
        dec.decimate_type = 'COLLAPSE'
        dec.ratio = max(0.05, max_tris / tris0)
        dec.use_collapse_triangulate = True
    dg = bpy.context.evaluated_depsgraph_get()
    dg.update()
    ev = obj.evaluated_get(dg)
    me = ev.to_mesh(preserve_all_data_layers=True, depsgraph=dg)
    mw = obj.matrix_world.copy()
    me.calc_loop_triangles()
    pos = np.array([tuple(mw @ v.co) for v in me.vertices], dtype=np.float64)
    names = [g.name for g in obj.vertex_groups]
    groups = [[(names[g.group], g.weight) for g in v.groups if g.group < len(names) and g.weight > 1e-4] for v in me.vertices]
    # the recipe's UV map by name where the mesh has it (an FBX's active map can be a leftover one), else the active one
    uvl = me.uv_layers.get(recipe.get('uvLayer', '')) or me.uv_layers.active
    nrm3 = mw.to_3x3().inverted_safe().transposed()
    cn = me.corner_normals
    tris = []
    for t in me.loop_triangles:
        uvs = [tuple(uvl.uv[li].vector) if uvl else (0.0, 0.0) for li in t.loops]
        ns = [tuple((nrm3 @ cn[li].vector).normalized()) for li in t.loops]
        tris.append((tuple(t.vertices), uvs, ns, t.material_index))
    mats = [s.material for s in obj.material_slots]
    ev.to_mesh_clear()
    if dec: obj.modifiers.remove(dec)
    for md, v in saved: md.show_viewport = v
    for kb, v, m in saved_keys: kb.value = v; kb.mute = m
    obj.hide_viewport = vis[0]
    try: obj.hide_set(vis[1])
    except RuntimeError: pass
    if linked: bpy.context.scene.collection.objects.unlink(obj)
    return pos, groups, tris, mats


# ---------------------------------------------------------------- textures (ImageMagick for the pixels)
def magick(*a):
    r = subprocess.run(['convert', *a], capture_output=True, text=True)
    if r.returncode != 0: raise RuntimeError('convert failed: ' + r.stderr[:400])


def read_rgba(path, size):
    r = subprocess.run(['convert', path, '-resize', '%dx%d!' % (size, size) if isinstance(size, int) else size, '-depth', '8', 'RGBA:-'],
                       capture_output=True)
    if r.returncode != 0: raise RuntimeError('convert failed: ' + r.stderr.decode()[:400])
    return np.frombuffer(r.stdout, dtype=np.uint8)


def image_size(path):
    r = subprocess.run(['identify', '-format', '%w %h', path], capture_output=True, text=True)
    w, h = r.stdout.split()[:2]
    return int(w), int(h)


def write_png(path, rgba, w, h):
    rgba = np.ascontiguousarray(rgba.reshape(h, w, 4), dtype=np.uint8)
    alpha = bool((rgba[:, :, 3] < 255).any())
    px = rgba if alpha else np.ascontiguousarray(rgba[:, :, :3])
    ch = 4 if alpha else 3
    raw = b''.join(b'\x00' + px[y].tobytes() for y in range(h))
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6 if alpha else 2, 0, 0, 0)) + \
        chunk(b'IDAT', zlib.compress(raw, 7)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f: f.write(data)
    return alpha


def srgb_to_lab(rgb):
    c = rgb / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = lin @ M.T / np.array([0.9505, 1.0, 1.089])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def lab_to_srgb(lab):
    fy = (lab[..., 0] + 16) / 116; fx = fy + lab[..., 1] / 500; fz = fy - lab[..., 2] / 200
    def finv(t): return np.where(t ** 3 > 0.008856, t ** 3, (t - 16 / 116) / 7.787)
    xyz = np.stack([finv(fx) * 0.9505, finv(fy), finv(fz) * 1.089], -1)
    Mi = np.linalg.inv(np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]]))
    lin = np.clip(xyz @ Mi.T, 0, 1)
    c = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * lin ** (1 / 2.4) - 0.055)
    return np.clip(c * 255 + 0.5, 0, 255)


def recolor_skin(rgba, target, mask=None):
    """The skin's colours moved to a human tone: each texel's a*b* rotated and scaled so the map's median chroma lands on
    `target`'s, its lightness shifted by the medians' difference (detail kept). Texels far from the skin's own tone (lips,
    eyes, painted marks: over 40 a*b* units from the median) move less."""
    px = rgba.reshape(-1, 4)
    lab = srgb_to_lab(px[:, :3].astype(np.float64))
    skin = lab if mask is None else lab[mask]
    med = np.median(skin, axis=0)
    t = np.array(target, dtype=np.float64)
    ang_s, ang_t = math.atan2(med[2], med[1]), math.atan2(t[2], t[1])
    ch_s, ch_t = math.hypot(med[1], med[2]), math.hypot(t[1], t[2])
    k = ch_t / max(ch_s, 1e-3)
    da = ang_t - ang_s
    ca, sa = math.cos(da), math.sin(da)
    d = np.hypot(lab[:, 1] - med[1], lab[:, 2] - med[2])
    w = np.clip(1.0 - (d - 25) / 40.0, 0.25, 1.0)[:, None]
    a, b = lab[:, 1] * k, lab[:, 2] * k
    out = lab.copy()
    out[:, 1] = a * ca - b * sa
    out[:, 2] = a * sa + b * ca
    out[:, 0] = lab[:, 0] + (t[0] - med[0])
    out = lab * (1 - w) + out * w
    px2 = px.copy()
    px2[:, :3] = lab_to_srgb(out).astype(np.uint8)
    return px2.reshape(rgba.shape), med


# ---------------------------------------------------------------- the human ear
def human_ears(pos, groups, joints_blender, reach=0.034):
    """The night elf's long ear made a human one. Each ear vertex (its weight on the rig's Ear bones, Ear and Ear2, as the
    blend) is measured along the ear's own axis (the Ear bone's head to Ear2's tail: out and back from the head) from its
    root (the Ear bone's head, where the ear meets the head) and that distance a is mapped to reach * tanh(a / reach):
    unchanged near the head, the long blade folded into a rounded back edge about 3.4 cm from the root (a human ear's depth
    from the canal to the helix), so the tip no longer sweeps out and up; its sideways reach from the head is cut by a third.
    Applied after the blend's own EarsSmall key. Returns the moved positions and how many vertices moved."""
    out = pos.copy()
    moved = 0
    for side in ('L', 'R'):
        root, tip = joints_blender.get('Ear.' + side), joints_blender.get('Ear2.' + side + '#tail')
        if root is None or tip is None: continue
        root = np.array(root); axis = np.array(tip) - root
        axis /= np.linalg.norm(axis) + 1e-9
        ids, ws = [], []
        for i, g in enumerate(groups):
            w = sum(wt for n, wt in g if n in ('Ear.' + side, 'Ear2.' + side))
            if w > 0.01: ids.append(i); ws.append(min(1.0, w))
        if not ids: continue
        ids = np.array(ids); ws = np.array(ws)
        p = pos[ids] - root
        a = p @ axis
        perp = p - np.outer(a, axis)
        a2 = np.where(a > 0, reach * np.tanh(a / reach), a)
        q = root + np.outer(a2, axis) + perp
        side_dir = np.array([1.0 if side == 'L' else -1.0, 0, 0])
        lat = (q - root) @ side_dir
        q = q - np.outer(np.where(lat > 0, lat * 0.33, 0), side_dir)
        # the folded blade's top rounded: the outer part's height drawn toward its own middle
        t = np.where(a > 0, np.tanh(a / reach), 0)
        far = t > 0.55
        if far.any():
            zc = q[far, 2].mean()
            k = np.clip((t - 0.55) / 0.45, 0, 1) * 0.45
            q[:, 2] = q[:, 2] - (q[:, 2] - zc) * np.where(q[:, 2] > zc, k, k * 0.3)
        out[ids] = pos[ids] + (q - pos[ids]) * ws[:, None] ** 0.7
        moved += len(ids)
    return out, moved


def short_brows(pos, groups, joints_blender, inner=0.042):
    """Brows of a human's length: past `inner` metres from the face's middle line each brow's run sideways is cut to 30% and
    its rise toward the temple to 25%, so the night elf's long sweeping brows end above the outer eye."""
    out = pos.copy()
    x = pos[:, 0]
    ax = np.abs(x)
    outer = ax > inner
    if not outer.any(): return out, 0
    ref = pos[(ax > inner - 0.006) & (ax <= inner)]
    z0 = ref[:, 2].mean() if len(ref) else pos[:, 2].mean()
    d = ax - inner
    out[:, 0] = np.where(outer, np.sign(x) * (inner + d * 0.3), x)
    out[:, 2] = np.where(outer, z0 + (pos[:, 2] - z0) * 0.25, pos[:, 2])
    return out, int(outer.sum())


# ---------------------------------------------------------------- one mesh
class MeshOut:
    def __init__(self, mid, kind):
        self.id, self.kind = mid, kind
        self.pos, self.nrm, self.uv, self.bi, self.bw, self.idx, self.parts = [], [], [], [], [], [], []
        self.src = []   # per output vertex: the source vertex (the body's hidden-triangle test, the shape keys)
        self.genital = []   # output vertices of the body's own genitals (genitalMask)


def build_mesh(mid, kind, objs, recipe, keys, bone_names, chain, textures, tex_of_image, forward, max_tris=None, rigid=None,
               body_kd=None, body_weights=None, ear_fix=None, image_for=None, genital=None):
    m = MeshOut(mid, kind)
    alpha_mats = recipe.get('alphaMaterials', [])
    ignore = recipe.get('ignoreImages', [])
    budget = max_tris or recipe.get('maxTris', {}).get(mid)
    if image_for is None: image_for = lambda mt: image_of(mt, ignore)
    for obj in objs:
        n_obj_tris = sum(len(p.vertices) - 2 for p in obj.data.polygons)
        share = int(budget * n_obj_tris / max(1, sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in objs))) if budget else None
        pos, groups, tris, mats = evaluate(obj, recipe, keys, share)
        if ear_fix is not None: pos, n = ear_fix(pos, groups); log(mid, 'ears: %d vertices moved' % n)
        fix = FIXES.get((recipe.get('_variant') or {}).get('objectFix', {}).get(obj.name))
        if fix: pos, n = fix(pos, groups, None); log(mid, obj.name, fix.__name__, '%d vertices moved' % n)
        # weights per source vertex
        W = []
        for vi, g in enumerate(groups):
            acc = {}
            if rigid:
                acc[rigid] = 1.0
            else:
                for name, w in g:
                    tb = chain.get(name)
                    if tb: acc[tb] = acc.get(tb, 0) + w
                if sum(acc.values()) < 0.2 and body_kd is not None:
                    _, j, _ = body_kd.find(Vector(pos[vi]))
                    acc = dict(body_weights[j])
            top = sorted(acc.items(), key=lambda kv: -kv[1])[:4]
            tot = sum(w for _, w in top)
            if tot <= 0: top, tot = [('Root', 1.0)], 1.0
            W.append([(bone_names.setdefault(n, len(bone_names)), w / tot) for n, w in top])
        img = [image_for(mt) for mt in mats]
        # the body's own genitals (`genital`: a vertex-group name pattern and the least weight on them): their vertices kept
        gflag = None
        if genital:
            import re as _re
            pat = _re.compile(genital[0], _re.I)
            gflag = [sum(w for n, w in g if pat.search(n)) >= genital[1] for g in groups]
        alpha = [bool(mt and any(s in mt.name for s in alpha_mats)) for mt in mats]
        verts = {}
        by_part = {}
        for (vs, uvs, ns, mi) in tris:
            mi = min(mi, len(mats) - 1) if mats else 0
            path = img[mi] if mats else None
            key_t = (path, alpha[mi] if mats else False)
            lst = by_part.setdefault(key_t, [])
            tri = []
            for k in range(3):
                v = vs[k]; uv = uvs[k]; n = ns[k]
                kk = (obj.name, v, round(uv[0], 5), round(uv[1], 5), round(n[0], 2), round(n[1], 2), round(n[2], 2))
                o = verts.get(kk)
                if o is None:
                    o = len(m.pos); verts[kk] = o
                    m.pos.append(to_wow(pos[v], forward)); m.nrm.append(to_wow(n, forward))
                    m.uv.append((uv[0], 1.0 - uv[1]))
                    ww = W[v] + [(0, 0.0)] * (4 - len(W[v]))
                    q = [int(round(w * 255)) for _, w in ww]
                    q[0] += 255 - sum(q)
                    m.bi.append([b for b, _ in ww]); m.bw.append(q)
                    m.src.append((obj.name, v))
                    if gflag is not None and gflag[v]: m.genital.append(o)
                tri.append(o)
            lst.append(tri)
        for (path, a), lst in by_part.items():
            start = len(m.idx)
            for t in lst: m.idx.extend(t)
            tex = tex_of_image(path) if path else -1
            m.parts.append({'start': start, 'count': len(lst) * 3, 'texture': tex, 'alpha': 'test' if a else 'opaque'})
    log('mesh', mid, '%d vertices, %d triangles, %d parts' % (len(m.pos), len(m.idx) // 3, len(m.parts)))
    return m


def mesh_blob(m):
    n = len(m.pos)
    arr = np.zeros(n, dtype=[('p', '<f4', 3), ('n', '<f4', 3), ('t', '<f4', 2), ('b', 'u1', 4), ('w', 'u1', 4)])
    arr['p'] = np.array(m.pos, dtype=np.float32); arr['n'] = np.array(m.nrm, dtype=np.float32)
    arr['t'] = np.array(m.uv, dtype=np.float32); arr['b'] = np.array(m.bi, dtype=np.uint8); arr['w'] = np.array(m.bw, dtype=np.uint8)
    return arr.tobytes(), np.array(m.idx, dtype='<u4').tobytes()


def hidden_under(body, body_pos_src, outfit_meshes):
    """Body triangles under an outfit: every corner within 2.5 cm of the outfit's surface and not deeper than 0.6 cm outside it."""
    verts, polys = [], []
    for om in outfit_meshes:
        base = len(verts)
        verts += [Vector(p) for p in om.pos]
        polys += [(om.idx[i] + base, om.idx[i + 1] + base, om.idx[i + 2] + base) for i in range(0, len(om.idx), 3)]
    if not polys: return []
    bvh = BVHTree.FromPolygons(verts, polys)
    cov = []
    for p, n in zip(body.pos, body.nrm):
        hit = bvh.find_nearest(Vector(p), 0.025)
        ok = False
        if hit and hit[0] is not None:
            d = hit[0] - Vector(p)
            ok = d.dot(Vector(n)) > -0.006
        cov.append(ok)
    out = []
    for t in range(len(body.idx) // 3):
        a, b, c = body.idx[3 * t:3 * t + 3]
        if cov[a] and cov[b] and cov[c]: out.append(t)
    return out


# ---------------------------------------------------------------- the file
def write_whd(path, meta, blobs):
    js = json.dumps(meta, separators=(',', ':')).encode()
    head = 16
    table_at = head + len(js)
    table_at = (table_at + 15) & ~15
    off = table_at + 16 * len(blobs)
    off = (off + 15) & ~15
    table = []
    for b in blobs:
        table.append((off, len(b)))
        off = (off + len(b) + 15) & ~15
    with open(path, 'wb') as f:
        f.write(b'WHD1' + struct.pack('<III', WHD_VERSION, len(js), len(blobs)))
        f.write(js)
        f.write(b'\0' * (table_at - f.tell()))
        for o, s in table: f.write(struct.pack('<QQ', o, s))
        for (o, s), b in zip(table, blobs):
            f.write(b'\0' * (o - f.tell()))
            f.write(b)
    return os.path.getsize(path)


def load_recipe(path):
    """A recipe, on top of the one it `extends` (its tables merged: the model's own entries win), with every "{S}" in the
    `weights` and `joints` tables expanded for both sides (L and R)."""
    r = json.load(open(path))
    base = r.get('extends')
    if base:
        b = load_recipe(os.path.join(os.path.dirname(path), base))
        for k, v in r.items():
            if isinstance(v, dict) and isinstance(b.get(k), dict): b[k] = {**b[k], **v}
            else: b[k] = v
        r = b
    for table in ('weights', 'joints'):
        t = {}
        for k, v in r.get(table, {}).items():
            if '{S}' in k or '{S}' in v:
                for sd in ('L', 'R'): t[k.replace('{S}', sd)] = v.replace('{S}', sd)
            else: t[k] = v
        r[table] = t
    return r


def open_source(path, texdir):
    """The user's model: a .blend opened, or an .fbx imported into an empty scene (Blender's own FBX importer)."""
    if path.lower().endswith('.fbx'):
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.fbx(filepath=path)
    else:
        open_blend(path, texdir)


def convert(recipe_path, blend, texdir, out, opt):
    recipe = load_recipe(recipe_path)
    open_source(blend, texdir)
    arms = [o for o in bpy.data.objects if o.type == 'ARMATURE']
    arm = bpy.data.objects.get(recipe.get('armature', '')) or (arms[0] if arms else None)
    if arm is None: raise SystemExit('no armature in ' + blend)
    arm.data.pose_position = 'REST'
    for o in bpy.data.objects:
        if o.type == 'ARMATURE': o.data.pose_position = 'REST'
    bpy.context.view_layer.update()
    fwd = recipe.get('forward', '-y')
    chain = bone_chain_map([arm] + [a for a in arms if a is not arm], recipe['weights'])
    joints_b = {b.name: tuple(arm.matrix_world @ b.head_local) for b in arm.data.bones}
    joints_b.update({b.name + '#tail': tuple(arm.matrix_world @ b.tail_local) for b in arm.data.bones})
    joints_n = {norm(b.name): tuple(arm.matrix_world @ b.head_local) for b in arm.data.bones}
    os.makedirs(out, exist_ok=True)
    written = []
    for var in recipe['variants']:
        if opt['only'] and var['file'] != opt['only']: continue
        log('variant', var['file'])
        recipe['_variant'] = var
        bone_names = {}
        textures, tex_index, blobs = [], {}, []
        tex_max = recipe.get('textureMax', 1024)
        sizes = recipe.get('textureSize', {})
        human = var.get('skin', {}).get('recolor') == 'human'
        skin_images = set(sizes.keys()) if human else set()
        gpu = [0]

        def tex_of_image(path, base=None):
            if path in tex_index: return tex_index[path]
            name = os.path.basename(path)
            w0, h0 = image_size(path)
            lim = sizes.get(name, tex_max)
            s = min(1.0, lim / max(w0, h0))
            w, h = max(4, int(w0 * s)), max(4, int(h0 * s))
            rgba = read_rgba(path, '%dx%d!' % (w, h)).copy().reshape(h, w, 4)
            info = {'id': name, 'w': w, 'h': h}
            if name in skin_images and 'Mouth' not in name:
                rgba, med = recolor_skin(rgba, var['skin']['targetLab'])
                info['recolored'] = [round(x, 1) for x in med.tolist()]
            fn = os.path.join(out, '_tex.png')
            info['alpha'] = write_png(fn, rgba, w, h)
            blobs.append(open(fn, 'rb').read()); os.remove(fn)
            info['blob'] = len(blobs) - 1
            gpu[0] += w * h * 4 * 4 // 3
            textures.append(info)
            tex_index[path] = len(textures) - 1
            return tex_index[path]

        def find_image(name):
            # (TEXTURE_DIR may list several folders, ':'-separated: a model's own first, then shared ones)
            for d in texdir.split(os.pathsep):
                for root, _, files in os.walk(d):
                    if name in files: return os.path.join(root, name)
            return None

        def image_for(mt):
            # the recipe's own image for a material (an FBX's materials name none), else its Base Color's
            name = (recipe.get('materialImages') or {}).get(mt.name if mt else '')
            if name: return find_image(name)
            return image_of(mt, recipe.get('ignoreImages', []))

        def images(spec):
            """A list of {label, image} or a {glob, label} pattern ("{n}" numbered) as [(label, path)]."""
            import fnmatch
            out_ = []
            if isinstance(spec, dict):
                found = sorted(os.path.join(r_, f) for d in texdir.split(os.pathsep) for r_, _, fs in os.walk(d) for f in fs if fnmatch.fnmatch(f, spec['glob']))
                for i, f in enumerate(found): out_.append((spec.get('label', '{n}').replace('{n}', str(i + 1)), f))
            else:
                for e in spec or []:
                    pth = find_image(e['image']) if e.get('image') else None
                    out_.append((e['label'], pth, e))
            return [x if len(x) == 3 else (x[0], x[1], {}) for x in out_]

        keys = dict(recipe.get('shapeKeys', {}))
        ear_fix = None
        if var.get('ears') == 'human':
            keys['EarsSmall'] = 1.0
            ear_fix = lambda p, g: human_ears(p, g, joints_b)
        bdef = var.get('body', recipe['body'])
        body_obj = bpy.data.objects[bdef['object']]
        gm = recipe.get('genitalMask')
        genital = (gm['groups'], gm.get('min', 0.5)) if gm else None
        body = build_mesh('body', 'body', [body_obj], recipe, keys, bone_names, chain, textures, tex_of_image, fwd,
                          max_tris=bdef.get('maxTris'), ear_fix=ear_fix, image_for=image_for, genital=genital)
        # the other bodies the figure can have (a tail's length, bones showing, male parts: one choice), and the shape keys
        # as morphs (each key's change per vertex, sparse: the creator's sliders)
        alt_bodies = [body]
        for i, b in enumerate(var.get('bodies', recipe.get('bodies', []))):
            o = bpy.data.objects.get(b['object'])
            if o is None: log('missing body', b['object']); continue
            alt_bodies.append(build_mesh('body%d' % (i + 2), 'bodyalt', [o], recipe, keys, bone_names, chain, textures, tex_of_image, fwd,
                                         ear_fix=ear_fix, image_for=image_for, genital=genital))
        body_labels = [bdef.get('label', 'Body')] + [b.get('label', b['object']) for b in var.get('bodies', recipe.get('bodies', []))
                                                     if bpy.data.objects.get(b['object'])]

        def shape_blob(ms, key):
            obj = bpy.data.objects[ms.src[0][0]]
            if not obj.data.shape_keys or key not in obj.data.shape_keys.key_blocks: return None
            base, _, _, _ = evaluate(obj, recipe, dict(keys, **{key: 0.0}), None)
            moved, _, _, _ = evaluate(obj, recipe, dict(keys, **{key: 1.0}), None)
            out_ = bytearray(struct.pack('<I', 0))
            n = 0
            for i, (_, v) in enumerate(ms.src):
                d = moved[v] - base[v]
                if abs(d[0]) + abs(d[1]) + abs(d[2]) < 1e-6: continue
                out_ += struct.pack('<Ifff', i, *to_wow(d, fwd))
                n += 1
            out_[0:4] = struct.pack('<I', n)
            return bytes(out_), n

        shapes = [sh for sh in recipe.get('shapes', [])]
        bodies_meta = []
        # the body's weights by position, for objects that have none
        kd = KDTree(len(body.pos))
        bw = []
        inv = {v: k for k, v in bone_names.items()}
        for i, p in enumerate(body.pos):
            kd.insert(Vector(from_wow(p, fwd)), i)
            bw.append([(inv[b], w / 255.0) for b, w in zip(body.bi[i], body.bw[i]) if w > 0])
        kd.balance()
        meshes = list(alt_bodies)
        for ms, label in zip(alt_bodies, body_labels):
            entry = {'label': label, 'mesh': ms.id, 'shapes': []}
            if ms.genital:
                blobs.append(np.array(sorted(ms.genital), dtype='<u4').tobytes())
                entry['genitalBlob'] = len(blobs) - 1
                log(ms.id, 'genitals: %d vertices' % len(ms.genital))
            for sh in shapes:
                r_ = shape_blob(ms, sh['key'])
                if not r_: entry['shapes'].append(-1); continue
                blobs.append(r_[0]); entry['shapes'].append(len(blobs) - 1)
                log(ms.id, 'shape', sh['key'], '%d vertices' % r_[1])
            bodies_meta.append(entry)

        def objs_of(names):
            got = []
            for n in names:
                o = bpy.data.objects.get(n)
                if o is None or o.type != 'MESH': log('missing object', n); continue
                got.append(o)
                for c in o.children:   # (blades and charms parented to a piece)
                    if c.type == 'MESH': got.append(c)
            return got

        def add_group(gid, kind, names, rigid=None, max_tris=None):
            ms = build_mesh(gid, kind, objs_of(names), recipe, keys, bone_names, chain, textures, tex_of_image, fwd,
                            max_tris=max_tris, rigid=rigid, body_kd=kd, body_weights=bw, image_for=image_for)
            meshes.append(ms)
            return ms

        always = [add_group(g['id'], g['kind'], g['objects'], g.get('rigid')).id for g in recipe.get('always', [])]
        eye_tex = []
        for i, (label, p, e) in enumerate(images(recipe.get('eyeTextures', []))):
            if p: eye_tex.append({'id': e.get('id', 'eyes%d' % (i + 1)), 'label': label, 'texture': tex_of_image(p)})
        # tint presets that are textures of their own (a skin's other tones, the hair's colours): {label, image} or a glob
        def presets(spec):
            got = []
            for i, (label, p, e) in enumerate(images(spec)):
                t = {'id': e.get('id', 'p%d' % (i + 1)), 'label': label, 'rgb': e.get('rgb', 'FFFFFF')}
                if e.get('lift'): t['lift'] = e['lift']
                if p: t['texture'] = tex_of_image(p)
                elif e.get('image'): log('preset image missing', e['image']); continue
                got.append(t)
            return got
        tints = presets(var.get('tints', recipe.get('tints', [])))
        hair_tints = presets(var.get('hairTints', recipe.get('hairTints', [])))
        def base_tex(key):
            pth = find_image(recipe[key]) if recipe.get(key) else None
            return tex_of_image(pth) if pth else -1
        skin_tex, hair_tex = base_tex('skinImage'), base_tex('hairImage')
        hair = []
        for h in recipe.get('hair', []):
            ms = add_group('hair_' + h['id'], 'hair', h['objects'], h.get('rigid'), h.get('maxTris'))
            hair.append({'id': h['id'], 'label': h['label'], 'meshes': [ms.id]})
        outfits = []
        for o in recipe.get('outfits', []):
            parts = []
            for name in o['objects']:
                ms = add_group('outfit_%s_%s' % (o['id'], len(parts)), 'outfit', [name], o.get('rigid'), o.get('maxTris'))
                parts.append(ms)
            hide = hidden_under(body, None, parts)
            blobs.append(np.array(hide, dtype='<u4').tobytes())
            outfits.append({'id': o['id'], 'label': o['label'], 'classes': o['classes'], 'meshes': [p.id for p in parts],
                            'hideBlob': len(blobs) - 1, 'hidden': len(hide)})
            log('outfit', o['id'], 'hides %d of %d body triangles' % (len(hide), len(body.idx) // 3))
        jewelry = []
        for j in recipe.get('jewelry', []):
            ms = add_group('jewel_' + j['id'], 'jewel', j['objects'], j.get('rigid'), j.get('maxTris'))
            jewelry.append({'id': j['id'], 'label': j['label'], 'meshes': [ms.id]})
        overlays = []
        for ov in (var['overlays'] if 'overlays' in var else recipe.get('overlays', [])):
            p, onto = find_image(ov['image']), find_image(ov['onto'])
            if not p or not onto or onto not in tex_index: log('overlay skipped', ov['id']); continue
            t = textures[tex_index[onto]]
            rgba = read_rgba(p, '%dx%d!' % (t['w'], t['h']))
            fn = os.path.join(out, '_ov.png')
            write_png(fn, rgba, t['w'], t['h'])
            blobs.append(open(fn, 'rb').read()); os.remove(fn)
            overlays.append({'id': ov['id'], 'label': ov['label'], 'slot': ov['slot'], 'onto': tex_index[onto], 'blob': len(blobs) - 1})
        # the meshes' blobs
        mesh_meta = []
        for ms in meshes:
            vb, ib = mesh_blob(ms)
            blobs.append(vb); blobs.append(ib)
            mesh_meta.append({'id': ms.id, 'kind': ms.kind, 'vertices': len(ms.pos), 'triangles': len(ms.idx) // 3,
                              'vertexBlob': len(blobs) - 2, 'indexBlob': len(blobs) - 1, 'parts': ms.parts})
        # the joints, in the same axes
        joints = {}
        for wow, b in recipe['joints'].items():
            j = joints_b.get(b) or joints_n.get(norm(b))
            if j: joints[wow] = [round(x, 5) for x in to_wow(j, fwd)]
        if var.get('ears') == 'human':
            for n in ('Ear.L', 'Ear.R'): pass
        bones = [None] * len(bone_names)
        for n, i in bone_names.items(): bones[i] = n
        meta = {
            'format': 'whd', 'version': WHD_VERSION, 'id': recipe['id'] + ':' + var['file'], 'label': var['label'],
            'race': var['race'], 'sex': var['sex'], 'units': 'metres', 'axes': 'x forward, y left, z up; feet at z 0',
            'bones': bones, 'joints': joints, 'textures': textures, 'meshes': mesh_meta, 'always': always,
            'bodies': bodies_meta, 'shapes': [{'key': sh['key'], 'label': sh['label']} for sh in shapes],
            'eyeTextures': eye_tex, 'hair': hair, 'outfits': outfits, 'jewelry': jewelry, 'overlays': overlays,
            'tints': tints, 'hairTints': hair_tints, 'skinTexture': skin_tex, 'hairTexture': hair_tex, 'credit': recipe.get('credit', ''), 'gpuBytes': gpu[0],
            'triangles': {'body': len(body.idx) // 3, 'max': len(body.idx) // 3 + max((sum(next(x['triangles'] for x in mesh_meta if x['id'] == mid) for mid in h['meshes']) for h in hair), default=0)
                          + max((sum(next(x['triangles'] for x in mesh_meta if x['id'] == mid) for mid in o['meshes']) for o in outfits), default=0)},
        }
        fn = os.path.join(out, var['file'])
        size = write_whd(fn, meta, blobs)
        log('wrote', fn, '%.1f MB, %d textures, GPU about %.0f MB with mipmaps' % (size / 1e6, len(textures), gpu[0] / 1e6))
        written.append(fn)
    if opt['zip']:
        zp = os.path.join(out, opt['zip'])
        with zipfile.ZipFile(zp, 'w', zipfile.ZIP_STORED) as z:
            for f in written: z.write(f, os.path.basename(f))
        log('zip', zp, '%.1f MB' % (os.path.getsize(zp) / 1e6))


FIXES = {'short_brows': short_brows}


def from_wow(p, forward):
    x, y, z = p
    if forward == '-y': return (y, -x, z)
    if forward == '+y': return (-y, x, z)
    if forward == '-x': return (-x, -y, z)
    return (x, y, z)


if __name__ == '__main__':
    r, b, t, o, opt = args()
    convert(r, b, t, o, opt)
