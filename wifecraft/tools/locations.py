#!/usr/bin/env python3
"""Works out the editor's locations (assets/locations.json): which of the game's buildings, props and
ground each one is made of, by file id, with every transform already in the location's own frame, its
light, fog, music, spawns and cameras, and the files the app fetches for it (ids and sizes only: the
files are the game's, fetched on the phone from wago.tools, never put in this repository).

    python3 tools/locations.py CACHE_DIR [LISTFILE]

CACHE_DIR keeps everything fetched while working it out (files named by id, tables as <Name>.csv);
LISTFILE is the community listfile (wowdev/wow-listfile, `id;path` lines), used only to name files in
the log. stdlib only. Be gentle with wago.tools: at most 8 requests at once.

A location's frame is its main building's own (WMO) frame: x, y across, z up, yards; the building's
root sits at the origin unrotated. What the map places around it (the ground, the map's props) is
moved into that frame with the inverse of the building's placement on the map (ADT MODF), found by
its file id and unique id. Placements: world = P + Rz(rot.y + 180 deg) * local (checked against the
MODF extents of Stormwind's WMOs); props use the full ADT matrix (Rx90 Ry90 T Ry(b-270) Rz(-a) Rx(c-90)).
"""
import csv
import io
import json
import math
import os
import struct
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "android", "app", "src", "main", "assets")
BUILD = "12.1.7.70892"
CASC = "https://wago.tools/api/casc/%d?download&version=" + BUILD
DB2 = "https://wago.tools/db2/%s/csv?build=" + BUILD
AGENT = "YeaBwoi-build/1.0"
TILE = 533.33333
ORIGIN = 32 * TILE

# The locations. Each names its building by file id and the map placement that stands it in the world
# (map WDT, tile, MODF unique id): nothing is guessed from names. Later locations (Goldshire's inn,
# Brill's, Auberdine's, the Azuremyst and Teldrassil glades, Sylvanas' chamber) are added here.
LOCATIONS = [
    {
        "id": "embassy",
        "name": "Stormwind Embassy",
        "kind": "interior+exterior",
        # 7hu_garrison_barracks_v2_embassy.wmo: WMOAreaTable 93968 (WMOID 11989, name set 1) names it
        # "Stormwind Embassy" (AreaTable 9171, in Stormwind City 1519); placed on azeroth_30_47.
        "wmo": 1725413,
        "wdt": 775971,
        "tile": (30, 47),
        "uid": 19042137,
        "doodadSets": [0, 1],            # Set_$DefaultGlobal (always) and Set_Embassy (the MODF's own set)
        "area": 9171,
        "wmoArea": 93968,
        "outsideRadius": 40.0,           # the map's props and ground this close to the building's centre
        "outsideFog": (28.0, 55.0),      # hides the edge of that yard
    },
]


def log(*a):
    print(*a, file=sys.stderr)


class Store:
    def __init__(self, cache, listfile=None):
        self.cache = cache
        os.makedirs(cache, exist_ok=True)
        self.names = {}
        if listfile and os.path.exists(listfile):
            for line in open(listfile, encoding="utf-8", errors="replace"):
                i = line.find(";")
                if i > 0 and line[:i].isdigit():
                    self.names[int(line[:i])] = line[i + 1:].strip()
        self.status = {}

    def name(self, fid):
        return self.names.get(fid, "?")

    def _get(self, url, path):
        for attempt in range(4):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": AGENT})
                data = urllib.request.urlopen(req, timeout=180).read()
                with open(path + ".part", "wb") as f:
                    f.write(data)
                os.replace(path + ".part", path)
                return data, 200
            except urllib.error.HTTPError as e:
                if e.code in (400, 403, 404):
                    return None, e.code
                time.sleep(2 ** attempt)
            except Exception:
                time.sleep(2 ** attempt)
        raise SystemExit("can't fetch " + url)

    def file(self, fid):
        path = os.path.join(self.cache, str(fid))
        if os.path.exists(path):
            self.status[fid] = 200
            return open(path, "rb").read()
        if os.path.exists(path + ".missing"):
            self.status[fid] = int(open(path + ".missing").read() or 0)
            return None
        data, code = self._get(CASC % fid, path)
        self.status[fid] = code
        if data is None:
            with open(path + ".missing", "w") as f:
                f.write(str(code))
        return data

    def files(self, fids):
        with ThreadPoolExecutor(8) as ex:
            list(ex.map(self.file, sorted(set(f for f in fids if f))))

    def table(self, name):
        path = os.path.join(self.cache, name + ".csv")
        if not os.path.exists(path):
            data, code = self._get(DB2 % name, path)
            if data is None:
                raise SystemExit("no table %s (%d)" % (name, code))
        return list(csv.DictReader(io.open(path, encoding="utf-8")))


def chunks(d, start=0, end=None):
    end = len(d) if end is None else end
    o = start
    while o + 8 <= end:
        n = struct.unpack_from("<I", d, o + 4)[0]
        yield d[o:o + 4][::-1].decode("latin1"), o + 8, n
        o += 8 + n


def chunk_map(d):
    out = {}
    for t, o, n in chunks(d):
        out.setdefault(t, (o, n))
    return out


# ---- maths (4x4 row-major) ----

def mm(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def ident():
    return [[float(i == j) for j in range(4)] for i in range(4)]


def rot(axis, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    m = ident()
    i, j = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}[axis]
    m[i][i], m[i][j], m[j][i], m[j][j] = c, -s, s, c
    return m


def trans(x, y, z):
    m = ident()
    m[0][3], m[1][3], m[2][3] = x, y, z
    return m


def adt_matrix(pos, r, scale=1.0):
    """An ADT placement (MODF/MDDF position and rotation) as a matrix to world coordinates."""
    m = ident()
    for x in (rot("x", 90), rot("y", 90), trans(ORIGIN - pos[0], pos[1], ORIGIN - pos[2]),
              rot("y", r[1] - 270), rot("z", -r[0]), rot("x", r[2] - 90)):
        m = mm(m, x)
    s = ident()
    s[0][0] = s[1][1] = s[2][2] = scale
    return mm(m, s)


def frame_matrix(pos, yaw):
    """The location's frame to world: the building's placement, yaw about z only."""
    return mm(trans(*pos), rot("z", yaw))


def inverse_rigid(m):
    r = [[m[j][i] for j in range(3)] for i in range(3)]
    t = [-sum(r[i][k] * m[k][3] for k in range(3)) for i in range(3)]
    out = ident()
    for i in range(3):
        for j in range(3):
            out[i][j] = r[i][j]
        out[i][3] = t[i]
    return out


def apply(m, p):
    return tuple(m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3] for i in range(3))


def quat(m):
    """Rotation of a matrix with scale divided out, as (x, y, z, w)."""
    s = math.sqrt(sum(m[i][0] ** 2 for i in range(3)))
    r = [[m[i][j] / s for j in range(3)] for i in range(3)]
    tr = r[0][0] + r[1][1] + r[2][2]
    if tr > 0:
        k = math.sqrt(tr + 1) * 2
        q = ((r[2][1] - r[1][2]) / k, (r[0][2] - r[2][0]) / k, (r[1][0] - r[0][1]) / k, 0.25 * k)
    elif r[0][0] > r[1][1] and r[0][0] > r[2][2]:
        k = math.sqrt(1 + r[0][0] - r[1][1] - r[2][2]) * 2
        q = (0.25 * k, (r[0][1] + r[1][0]) / k, (r[0][2] + r[2][0]) / k, (r[2][1] - r[1][2]) / k)
    elif r[1][1] > r[2][2]:
        k = math.sqrt(1 + r[1][1] - r[0][0] - r[2][2]) * 2
        q = ((r[0][1] + r[1][0]) / k, 0.25 * k, (r[1][2] + r[2][1]) / k, (r[0][2] - r[2][0]) / k)
    else:
        k = math.sqrt(1 + r[2][2] - r[0][0] - r[1][1]) * 2
        q = ((r[0][2] + r[2][0]) / k, (r[1][2] + r[2][1]) / k, 0.25 * k, (r[1][0] - r[0][1]) / k)
    if q[3] < 0:
        q = tuple(-v for v in q)
    return q, s


def rnd(v, n=3):
    return [round(x, n) + 0.0 for x in v]


def colour_bgra(u):
    return [round(((u >> 16) & 255) / 255, 3), round(((u >> 8) & 255) / 255, 3), round((u & 255) / 255, 3)]


def colour_rgb(v):
    v = int(v)
    return [round(((v >> 16) & 255) / 255, 3), round(((v >> 8) & 255) / 255, 3), round((v & 255) / 255, 3)]


# ---- the game's formats ----

class Wmo:
    def __init__(self, st, fid):
        d = st.file(fid)
        c = chunk_map(d)
        o, n = c["MOHD"]
        (self.nMat, self.nGroups, self.nPortals, self.nLights, self.nNames, self.nDefs,
         self.nSets) = struct.unpack_from("<7I", d, o)
        self.ambient = struct.unpack_from("<I", d, o + 28)[0]
        self.wmoId = struct.unpack_from("<I", d, o + 32)[0]
        self.bounds = struct.unpack_from("<6f", d, o + 36)
        o, n = c["MOMT"]
        self.materials = [struct.unpack_from("<16I", d, o + i) for i in range(0, n, 64)]
        o, n = c["MOGI"]
        self.groups = [dict(flags=struct.unpack_from("<I", d, o + i)[0], bounds=struct.unpack_from("<6f", d, o + i + 4))
                       for i in range(0, n, 32)]
        o, n = c["GFID"]
        gfid = struct.unpack_from("<%dI" % (n // 4), d, o)
        for i, g in enumerate(self.groups):
            g["file"] = gfid[i]          # the first nGroups ids are the full-detail groups; LODs follow
        o, n = c["MODS"]
        self.sets = [(d[o + i:o + i + 20].split(b"\0")[0].decode("latin1"),) + struct.unpack_from("<2I", d, o + i + 20)
                     for i in range(0, n, 32)]
        modi = []
        if "MODI" in c:
            o, n = c["MODI"]
            modi = struct.unpack_from("<%dI" % (n // 4), d, o)
        self.doodads = []
        if "MODD" in c:
            o, n = c["MODD"]
            for i in range(0, n, 40):
                ref, x, y, z, qx, qy, qz, qw, sc, col = struct.unpack_from("<I3f4ffI", d, o + i)
                idx = ref & 0xFFFFFF
                self.doodads.append(dict(model=modi[idx] if idx < len(modi) else 0, pos=(x, y, z),
                                         rot=(qx, qy, qz, qw), scale=sc, color=col))
        self.fogs = []
        if "MFOG" in c:
            o, n = c["MFOG"]
            for i in range(0, n, 48):
                flags, px, py, pz, r1, r2, end, mult, col = struct.unpack_from("<I3f4fI", d, o + i)
                self.fogs.append(dict(pos=(px, py, pz), r1=r1, r2=r2, end=end, mult=mult, color=col))
        self.portals = []
        if "MOPT" in c and "MOPV" in c:
            vo, vn = c["MOPV"]
            verts = [struct.unpack_from("<3f", d, vo + i) for i in range(0, vn, 12)]
            o, n = c["MOPT"]
            for i in range(0, n, 20):
                start, count = struct.unpack_from("<HH", d, o + i)
                plane = struct.unpack_from("<4f", d, o + i + 4)
                self.portals.append(dict(verts=verts[start:start + count], normal=plane[:3], dist=plane[3]))
        self.has = {t for t in c}


class Group:
    def __init__(self, st, fid):
        d = st.file(fid)
        self.flags = 0
        self.tris = []
        self.materials = set()
        for t, o, n in chunks(d):
            if t != "MOGP":
                continue
            self.flags = struct.unpack_from("<I", d, o + 8)[0]
            pos, idx, self.sub = [], [], []
            for st_, so, sn in chunks(d, o + 0x44, o + n):
                self.sub.append(st_)
                if st_ == "MOVT":
                    pos = [struct.unpack_from("<3f", d, so + i) for i in range(0, sn, 12)]
                elif st_ == "MOVI":
                    idx = struct.unpack_from("<%dH" % (sn // 2), d, so)
                elif st_ == "MOBA":
                    for b in range(0, sn, 24):
                        flags = d[so + b + 22]
                        self.materials.add(struct.unpack_from("<H", d, so + b + 10)[0] if flags & 2 else d[so + b + 23])
            self.tris = [(pos[idx[i]], pos[idx[i + 1]], pos[idx[i + 2]]) for i in range(0, len(idx) - 2, 3)]


def floor_at(tris, x, y, z):
    """As the engine's Scene::floorAt: the highest upward-facing floor under (x, y) at most 1.2 above z."""
    best = None
    for a, b, c in tris:
        ux, uy, uz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
        vx, vy, vz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
        nz = ux * vy - uy * vx
        ln = math.sqrt((uy * vz - uz * vy) ** 2 + (uz * vx - ux * vz) ** 2 + nz ** 2)
        if ln < 1e-6 or abs(nz) / ln < 0.5:
            continue
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-9:
            continue
        u = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d
        v = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d
        if u < -1e-4 or v < -1e-4 or u + v > 1.0001:
            continue
        h = u * a[2] + v * b[2] + (1 - u - v) * c[2]
        if h <= z + 1.2 and (best is None or h > best):
            best = h
    return best


class Terrain:
    """One map tile's ground: heights of its 16x16 chunks (root ADT), texture layers (tex0)."""

    def __init__(self, st, root, tex0):
        d = st.file(root)
        self.chunks = {}
        for t, o, n in chunks(d):
            if t != "MCNK":
                continue
            ix, iy = struct.unpack_from("<2I", d, o + 4)
            px, py, pz = struct.unpack_from("<3f", d, o + 104)
            h = None
            for s, so, sn in chunks(d, o + 128, o + n):
                if s == "MCVT":
                    h = struct.unpack_from("<145f", d, so)
            self.chunks[(ix, iy)] = dict(pos=(px, py, pz), h=h, layers=[])
        t0 = st.file(tex0)
        self.diffuse = []
        k = 0
        for t, o, n in chunks(t0):
            if t == "MDID":
                self.diffuse = list(struct.unpack_from("<%dI" % (n // 4), t0, o))
            elif t == "MCNK":
                key = (k % 16, k // 16)
                k += 1
                for s, so, sn in chunks(t0, o, o + n):
                    if s == "MCLY" and key in self.chunks:
                        self.chunks[key]["layers"] = [struct.unpack_from("<I", t0, so + i)[0] for i in range(0, sn, 16)]

    def height(self, x, y):
        unit = TILE / 16 / 8
        for (ix, iy), c in self.chunks.items():
            px, py, pz = c["pos"]
            if not (px - TILE / 16 <= x <= px and py - TILE / 16 <= y <= py) or not c["h"]:
                continue
            r, q = (px - x) / unit, (py - y) / unit
            r0, q0 = min(int(r), 7), min(int(q), 7)
            # bilinear over the outer grid (rows of 9 outer + 8 inner values): close enough for a spawn
            def outer(rr, cc):
                return c["h"][rr * 17 + cc]
            fr, fq = r - r0, q - q0
            h = (outer(r0, q0) * (1 - fr) * (1 - fq) + outer(r0 + 1, q0) * fr * (1 - fq) +
                 outer(r0, q0 + 1) * (1 - fr) * fq + outer(r0 + 1, q0 + 1) * fr * fq)
            return pz + h
        return None

    def near(self, x, y, radius):
        out = []
        s = TILE / 16
        for key, c in sorted(self.chunks.items()):
            px, py, _ = c["pos"]
            dx = max(px - s - x, 0, x - px)
            dy = max(py - s - y, 0, y - py)
            if math.hypot(dx, dy) < radius:
                out.append(key)
        return out


def placements(st, obj0):
    d = st.file(obj0)
    modf, mddf = [], []
    for t, o, n in chunks(d):
        if t == "MODF":
            for i in range(0, n, 64):
                fid, uid = struct.unpack_from("<2I", d, o + i)
                modf.append(dict(fid=fid, uid=uid, pos=struct.unpack_from("<3f", d, o + i + 8),
                                 rot=struct.unpack_from("<3f", d, o + i + 20),
                                 flags=struct.unpack_from("<H", d, o + i + 56)[0],
                                 set=struct.unpack_from("<H", d, o + i + 58)[0],
                                 nameSet=struct.unpack_from("<H", d, o + i + 60)[0]))
        elif t == "MDDF":
            for i in range(0, n, 36):
                fid, uid = struct.unpack_from("<2I", d, o + i)
                sc, fl = struct.unpack_from("<2H", d, o + i + 32)
                mddf.append(dict(fid=fid, uid=uid, pos=struct.unpack_from("<3f", d, o + i + 8),
                                 rot=struct.unpack_from("<3f", d, o + i + 20), scale=sc / 1024.0, flags=fl))
    return modf, mddf


def wdt_tiles(st, wdt):
    d = st.file(wdt)
    for t, o, n in chunks(d):
        if t == "MAID":
            return {(i % 64, i // 64): struct.unpack_from("<8I", d, o + i * 32) for i in range(4096)}
    return {}


def m2_deps(st, fid):
    """A prop's first skin and its own textures (as tools/manifest.py lists Dalaran's props)."""
    d = st.file(fid)
    if not d:
        return []
    skins, tex = [], []
    for t, o, n in chunks(d):
        t = t[::-1]   # M2 chunk tags are stored forwards
        if t == "SFID":
            skins = list(struct.unpack_from("<%dI" % (n // 4), d, o))
        elif t == "TXID":
            tex = [x for x in struct.unpack_from("<%dI" % (n // 4), d, o) if x]
    return skins[:1] + tex


# ---- a location ----

def build(st, loc):
    w = Wmo(st, loc["wmo"])
    tiles = wdt_tiles(st, loc["wdt"])
    root, obj0, _obj1, tex0 = tiles[loc["tile"]][:4]
    modf, mddf = placements(st, obj0)
    place = next(p for p in modf if p["fid"] == loc["wmo"] and p["uid"] == loc["uid"])
    world = adt_matrix(place["pos"], place["rot"])
    frame = frame_matrix(apply(world, (0, 0, 0)), place["rot"][1] + 180)
    to_local = inverse_rigid(frame)
    origin = apply(world, (0, 0, 0))
    log("%s: %s (wmoID %d) at %s yaw %.2f set %d nameSet %d" % (loc["id"], st.name(loc["wmo"]), w.wmoId,
        rnd(origin, 2), place["rot"][1] + 180, place["set"], place["nameSet"]))

    groups = [Group(st, g["file"]) for g in w.groups]
    tris = [t for g in groups for t in g.tris]
    indoor = [i for i, g in enumerate(w.groups) if g["flags"] & 0x2000]
    outdoor = [i for i, g in enumerate(w.groups) if g["flags"] & 0x8]

    # the building's own props: the always-shown set and the placement's set
    doodads = []
    for s in loc["doodadSets"]:
        name, first, count = w.sets[s]
        for dd in w.doodads[first:first + count]:
            if dd["model"]:
                doodads.append(dict(model=dd["model"], pos=rnd(dd["pos"]), rot=rnd(dd["rot"], 5),
                                    scale=round(dd["scale"], 4), set=s))

    # the yard: the map's props and ground within outsideRadius of the building's centre
    b = w.bounds
    centre_l = ((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, b[2])
    centre_w = apply(frame, centre_l)
    radius = loc["outsideRadius"]
    yard = []
    for p in mddf:
        wp = adt_matrix(p["pos"], p["rot"])
        pw = apply(wp, (0, 0, 0))
        if math.hypot(pw[0] - centre_w[0], pw[1] - centre_w[1]) >= radius:
            continue
        lm = mm(to_local, wp)
        q, _ = quat(lm)
        yard.append(dict(model=p["fid"], pos=rnd(apply(lm, (0, 0, 0))), rot=rnd(q, 5), scale=round(p["scale"], 4), set=-1))
    others = []
    for p in modf:
        if p["uid"] == loc["uid"]:
            continue
        pw = apply(adt_matrix(p["pos"], p["rot"]), (0, 0, 0))
        d = math.hypot(pw[0] - centre_w[0], pw[1] - centre_w[1])
        if d < radius + 30:
            others.append((p["fid"], round(d, 1)))

    ter = Terrain(st, root, tex0)
    near = ter.near(centre_w[0], centre_w[1], radius)
    layers = sorted({ter.diffuse[i] for k in near for i in ter.chunks[k]["layers"] if i < len(ter.diffuse)})

    # the door: the building's portal between outside and in
    portal = w.portals[0] if w.portals else None
    if portal:
        pc = tuple(sum(v[i] for v in portal["verts"]) / len(portal["verts"]) for i in range(3))
        n = portal["normal"]
        inward = (n[0], n[1]) if math.hypot(pc[0] + n[0] - centre_l[0], pc[1] + n[1] - centre_l[1]) < math.hypot(pc[0] - centre_l[0], pc[1] - centre_l[1]) else (-n[0], -n[1])
        ln = math.hypot(*inward)
        inward = (inward[0] / ln, inward[1] / ln)
    else:
        pc, inward = centre_l, (1, 0)
    sill = floor_at(tris, pc[0], pc[1], pc[2]) or pc[2] - 1

    def ground(x, y, z):
        """The building's floor there, else the map's ground (moved into the frame)."""
        f = floor_at(tris, x, y, z + 1)
        wx = apply(frame, (x, y, z))
        g = ter.height(wx[0], wx[1])
        g = None if g is None else g - origin[2]
        return f if f is not None else g

    # inside: stand in the room, 5 yards in from the door, facing into the room, where the camera
    # stands 11 yards in and looks back at them (the door behind them)
    ib = w.groups[indoor[0]]["bounds"] if indoor else w.bounds
    room_c = ((ib[0] + ib[3]) / 2, (ib[1] + ib[4]) / 2)
    sx, sy = pc[0] + inward[0] * 5, pc[1] + inward[1] * 5
    sz = floor_at(groups[indoor[0]].tris if indoor else tris, sx, sy, sill + 0.5)
    sz = sill if sz is None else sz
    facing_in = math.atan2(inward[1], inward[0])
    ex, ey = pc[0] + inward[0] * 16, pc[1] + inward[1] * 16
    ez = floor_at(groups[indoor[0]].tris if indoor else tris, ex, ey, sill + 0.5)
    ez = sill if ez is None else ez
    inside = dict(
        spawn=dict(pos=rnd((sx, sy, sz)), facing=round(facing_in, 4)),
        view=dict(eye=rnd((ex, ey, ez + 1.9)), at=rnd((sx, sy, sz + 1.2)), fov=50),
    )
    # outside: 6 yards out from the door facing out (towards the camera), the camera 16 yards out
    ox, oy = pc[0] - inward[0] * 6, pc[1] - inward[1] * 6
    oz = ground(ox, oy, sill)
    oz = sill if oz is None else oz
    cx, cy = pc[0] - inward[0] * 18, pc[1] - inward[1] * 18
    cz = ground(cx, cy, sill)
    cz = sill if cz is None else cz
    outside = dict(
        spawn=dict(pos=rnd((ox, oy, oz)), facing=round(math.atan2(-inward[1], -inward[0]), 4)),
        view=dict(eye=rnd((cx, cy, cz + 3.0)), at=rnd((pc[0], pc[1], sill + 3.5)), fov=55),
    )

    # light: the map's default light (Light 1 of the continent, its first params) at noon; inside,
    # the building's own ambient (MOHD) over its baked vertex colours, and its fog (MFOG)
    lights = [r for r in st.table("Light") if r["ContinentID"] == str(continent(st, loc))]
    lp = None
    for r in lights:
        c = (float(r["GameCoords_0"]), float(r["GameCoords_1"]))
        if c == (0.0, 0.0) or math.hypot(c[0] - origin[0], c[1] - origin[1]) < float(r["GameFalloffEnd"]):
            if c != (0.0, 0.0) or lp is None:
                lp = (r["ID"], r["LightParamsID_0"], c != (0.0, 0.0))
    noon = None
    for r in st.table("LightData"):
        if r["LightParamID"] == lp[1] and (noon is None or abs(int(r["Time"]) - 1440) < abs(int(noon["Time"]) - 1440)):
            noon = r
    day = dict(ambient=colour_rgb(noon["AmbientColor"]), direct=colour_rgb(noon["DirectColor"]),
               source="Light %s, LightParams %s, LightData %s (time %s)" % (lp[0], lp[1], noon["ID"], noon["Time"]))
    day_fog = dict(colour=colour_rgb(noon["SkyFogColor"]), start=loc["outsideFog"][0], end=loc["outsideFog"][1])
    room_light = dict(ambient=colour_bgra(w.ambient), direct=[0.0, 0.0, 0.0], vertexColours=True,
                      source="MOHD ambient; the room's light is baked into its vertex colours (MOCV)")
    f = w.fogs[0] if w.fogs else None
    room_fog = dict(colour=colour_bgra(f["color"]) if f else day_fog["colour"],
                    start=round(f["end"] * f["mult"], 2) if f else 30.0, end=round(f["end"], 2) if f else 60.0,
                    source="MFOG 0" if f else "none")
    inside["light"], inside["fog"] = room_light, room_fog
    outside["light"], outside["fog"] = day, day_fog
    inside["groups"] = list(range(len(w.groups)))
    outside["groups"] = list(range(len(w.groups)))

    # music and ambience: the building's WMOAreaTable row, else the area's
    area = next(r for r in st.table("AreaTable") if r["ID"] == str(loc["area"]))
    parent = next((r for r in st.table("AreaTable") if r["ID"] == area["ParentAreaID"]), None)
    wa = next((r for r in st.table("WMOAreaTable") if r["ID"] == str(loc.get("wmoArea", -1))), None)
    music = int(wa["ZoneMusic"]) if wa and int(wa["ZoneMusic"]) else int(area["ZoneMusic"]) or int(parent["ZoneMusic"] if parent else 0)
    amb_in = int(wa["AmbienceID"]) if wa and int(wa["AmbienceID"]) else int(area["AmbienceID"])
    amb_out = int(area["AmbienceID"]) or int(parent["AmbienceID"] if parent else 0)
    zm = {r["ID"]: r for r in st.table("ZoneMusic")}
    sa = {r["ID"]: r for r in st.table("SoundAmbience")}
    kits = {}
    for r in st.table("SoundKitEntry"):
        kits.setdefault(r["SoundKitID"], []).append(int(r["FileDataID"]))
    music_kits = sorted({zm[str(music)]["Sounds_0"], zm[str(music)]["Sounds_1"]}) if str(music) in zm else []
    amb_kits = {}
    for key, a in (("inside", amb_in), ("outside", amb_out)):
        if str(a) in sa:
            amb_kits[key] = [sa[str(a)]["AmbienceID_0"], sa[str(a)]["AmbienceID_1"]]   # day, night SoundKits
    ambience = dict(area=loc["area"], areaName=area["AreaName_lang"], parentArea=int(area["ParentAreaID"]),
                    wmoAreaTable=int(wa["ID"]) if wa else None, zoneMusic=music, musicSoundKits=[int(k) for k in music_kits],
                    musicFiles=sorted({f for k in music_kits for f in kits.get(k, [])}),
                    soundAmbience=dict(inside=amb_in, outside=amb_out),
                    ambienceSoundKits={k: [int(x) for x in v] for k, v in amb_kits.items()},
                    ambienceFiles={k: sorted({f for x in v for f in kits.get(x, [])}) for k, v in amb_kits.items()})

    # every file it needs, by pack: the building, its props, the yard's props, the ground, the sound
    group_files = [g["file"] for g in w.groups]
    mats = sorted({m for g in groups for m in g.materials if m < len(w.materials)})
    wmo_tex = []
    for m in mats:
        for t in (w.materials[m][3], w.materials[m][6]):     # texture1 (diffuse), texture2 (second layer)
            if t and t not in wmo_tex:
                wmo_tex.append(t)
    st.files(wmo_tex)
    prop_models = sorted({d["model"] for d in doodads})
    yard_models = sorted({d["model"] for d in yard} - set(prop_models))
    st.files(prop_models + yard_models)
    prop_deps = [x for m in prop_models for x in m2_deps(st, m)]
    yard_deps = [x for m in yard_models for x in m2_deps(st, m)]
    st.files(prop_deps + yard_deps + layers + ambience["musicFiles"] + [f for v in ambience["ambienceFiles"].values() for f in v])
    packs = [
        ("building", [loc["wmo"]] + group_files + wmo_tex),
        ("props", [x for m in prop_models for x in [m] + m2_deps(st, m)]),
        ("yard", [x for m in yard_models for x in [m] + m2_deps(st, m)]),
        ("ground", [root, tex0] + layers),
        ("sound", ambience["musicFiles"] + [f for v in ambience["ambienceFiles"].values() for f in v]),
    ]
    seen, out_packs, missing, total = set(), [], [], 0
    for name, ids in packs:
        files = []
        for fid in ids:
            if not fid or fid in seen:
                continue
            seen.add(fid)
            data = st.file(fid)
            if data is None:
                missing.append([fid, st.status.get(fid)])
                continue
            files.append([fid, len(data)])
            total += len(data)
        out_packs.append(dict(name=name, files=files, bytes=sum(s for _, s in files)))
    # drop props whose model can't be fetched
    bad = {m for m, _ in missing}
    doodads = [d for d in doodads if d["model"] not in bad]
    yard = [d for d in yard if d["model"] not in bad]

    entry = dict(
        id=loc["id"], name=loc["name"], kind=loc["kind"],
        frame=dict(comment="the building's own frame; world (map %d) = origin + Rz(yawDeg) * local" % continent(st, loc),
                   map=continent(st, loc), origin=rnd(origin), yawDeg=round(place["rot"][1] + 180, 4)),
        wmo=dict(root=loc["wmo"], groups=group_files, groupIndices=list(range(len(w.groups))),
                 indoorGroups=indoor, outdoorGroups=outdoor,
                 doodadSets=loc["doodadSets"], doodadSetNames=[w.sets[s][0] for s in loc["doodadSets"]],
                 position=[0.0, 0.0, 0.0], rotation=[0.0, 0.0, 0.0, 1.0], bounds=rnd(w.bounds)),
        doodads=doodads + yard,
        terrain=dict(comment="the map's ground under the yard: root ADT (MCNK MCVT heights) and tex0 (MCLY layers, MCAL alpha), "
                             "chunks [ix, iy] of the tile, in world coordinates (move with frame)",
                     wdt=loc["wdt"], tile=list(loc["tile"]), root=root, tex0=tex0, chunks=[list(k) for k in near], textures=layers),
        light=day, fog=day_fog,
        spawns=dict(inside=inside["spawn"], outside=outside["spawn"]),
        views=dict(inside=dict(inside["view"], light=inside["light"], fog=inside["fog"], groups=inside["groups"]),
                   outside=dict(outside["view"], light=outside["light"], fog=outside["fog"], groups=outside["groups"])),
        ambience=ambience,
        files=dict(source=CASC.replace("%d", "<id>"), packs=out_packs, total=total, totalMB=round(total / 1e6, 2), missing=missing),
    )
    report = dict(groups=[(i, hex(g["flags"]), g["file"], st.name(g["file"]), rnd(g["bounds"], 1), groups[i].sub)
                          for i, g in enumerate(w.groups)],
                  sets=w.sets, has=sorted(w.has), portals=len(w.portals), lights=w.nLights,
                  ambientRaw="%08x" % w.ambient, fogs=w.fogs, wmoId=w.wmoId,
                  propCounts=count_models(st, doodads), yardCounts=count_models(st, yard),
                  otherWmos=[(f, st.name(f), dist) for f, dist in others],
                  ground=[(t, st.name(t)) for t in layers], packs=[(p["name"], len(p["files"]), p["bytes"]) for p in out_packs],
                  door=rnd(pc), inward=rnd(inward), sill=sill, statuses=dict(st.status))
    return entry, report


def continent(st, loc):
    return int(next(r for r in st.table("AreaTable") if r["ID"] == str(loc["area"]))["ContinentID"])


def count_models(st, items):
    c = {}
    for d in items:
        c[d["model"]] = c.get(d["model"], 0) + 1
    return sorted(((n, m, st.name(m)) for m, n in c.items()), reverse=True)


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    st = Store(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
    path = os.path.join(ASSETS, "locations.json")
    out = {"comment": "The editor's locations (tools/locations.py). Each is in its main building's own frame (x, y across, "
                      "z up, yards); doodads: model file id, pos, rot quaternion [x, y, z, w], scale. Files are fetched from "
                      "wago.tools by id at build " + BUILD + "; nothing of the game is in this file but ids and transforms.",
           "build": BUILD, "locations": []}
    if os.path.exists(path):
        old = json.load(open(path))
        out["locations"] = [l for l in old.get("locations", []) if l["id"] not in {x["id"] for x in LOCATIONS}]
    reports = {}
    for loc in LOCATIONS:
        entry, rep = build(st, loc)
        out["locations"].append(entry)
        reports[loc["id"]] = rep
        log("%s: %d groups, %d props, %d yard props, %d ground chunks, %.2f MB, %d missing" % (
            loc["id"], len(entry["wmo"]["groups"]), sum(1 for d in entry["doodads"] if d["set"] >= 0),
            sum(1 for d in entry["doodads"] if d["set"] < 0), len(entry["terrain"]["chunks"]),
            entry["files"]["totalMB"], len(entry["files"]["missing"])))
    out["locations"].sort(key=lambda l: l["id"])
    with open(path, "w") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    if os.environ.get("LOCATIONS_REPORT"):
        with open(os.environ["LOCATIONS_REPORT"], "w") as f:
            json.dump(reports, f, indent=1, default=str)


if __name__ == "__main__":
    main()
