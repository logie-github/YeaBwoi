#!/usr/bin/env python3
"""Works out the editor's locations (assets/locations.json): which of the game's buildings, props and
ground each one is made of, by file id, with every transform already in the location's own frame, its
light, fog, music, spawns and cameras, and the files the app fetches for it (ids and sizes only: the
files are the game's, fetched on the phone from wago.tools, never put in this repository).

    python3 tools/locations.py CACHE_DIR [LISTFILE] [--only=ID,ID...]

--only rebuilds just those locations and keeps every other entry as it is (the Embassy's entry has hand-made parts:
its interactions, its views' interiorFog; rebuild the bedrooms with --only=<their ids>).

    python3 tools/locations.py CACHE_DIR --day-cycle

adds (or replaces) only each entry's "dayCycle" and leaves the rest of every entry as its text stands: the map light's
day (LightData's keyframes for the LightParams its "light" came from: hour = Time / 120, ambient, direct, sky middle,
sky fog), which the engine samples by the world clock's hour (cpp engine/daylight.h).

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
import re
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
# (map WDT, tile, MODF unique id): nothing is guessed from names. The bedrooms follow the Embassy.
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
] + [
    # The bedrooms. Each is a real building of the game, standing where the game stands it (its MODF on the map, found by
    # file id and unique id, with the placement's own doodad set), its furniture the building's own doodads, picked by
    # their index in the WMO's MODD (beds, chairs, tables and the books on them): "room" says which. Where the game has no
    # furnished bedroom of a people, "extras" adds the game's own props to a real building of theirs (kind "composed").
    dict(id="goldshire_inn", name="Lion's Pride Inn, Goldshire", kind="interior", people="human",
         # goldshireinn.wmo, WMOID 53 name set 2 (WMOAreaTable 20327 "Lion's Pride Inn"); Elwynn, azeroth 31_49
         wmo=106851, wdt=775971, tile=(31, 49), uid=71414, doodadSets=[0, 2], area=87, wmoArea=20327,
         outsideRadius=40.0, outsideFog=(28.0, 60.0),
         room=dict(bed=511, chair=470, table=527, books=[424, 439],                 # the canopy bed upstairs, the writing desk
                   eye=(3.4, -0.9, 9.6), at=(8.8, 2.2, 8.0),
                   props=[dict(kind="BOOKSHELF", model=198053, near=(8.41, -8.61)),     # duskwoodbookshelf02, both upstairs
                          dict(kind="BOOKSHELF", model=198053, near=(-0.32, -1.34))])),
    dict(id="thunderbrew", name="Thunderbrew Distillery, Kharanos", kind="interior", people="dwarf",
         # snow_inn.wmo, WMOID 1970 name set 1 (WMOAreaTable 25370 "Thunderbrew Distillery", area 2102); azeroth 32_42
         wmo=113925, wdt=775971, tile=(32, 42), uid=125539, doodadSets=[0, 2], area=2102, wmoArea=25370,
         outsideRadius=40.0, outsideFog=(28.0, 60.0),
         room=dict(bed=69, eye=(-22.09, 7.83, -15.15),                              # the cellar's guest beds
                   outside=dict(eye=(26.0, 8.35, 3.0), at=(-12.0, 8.35, 6.0), spawn=(2.0, 8.35)))),
    dict(id="dolanaar_inn", name="Dolanaar Inn, Teldrassil", kind="interior", people="night elf",
         # nightelfinn.wmo, WMOID 727 (set_Inn); Dolanaar, kalimdor 30_13
         wmo=112867, wdt=782779, tile=(30, 13), uid=167621, doodadSets=[0, 1], area=186, wmoArea=None,
         outsideRadius=45.0, outsideFog=(30.0, 65.0),
         room=dict(bed=10, eye=(-1.54, 0.83, 20.76),                                # the upper loft's beds
                   outside=dict(eye=(56.0, 12.0, 3.0), at=(20.0, 12.0, 5.0), spawn=(38.0, 12.0)))),
    dict(id="razor_hill_inn", name="Razor Hill Inn, Durotar", kind="interior", people="orc",
         # orcinn.wmo, WMOID 1849; Razor Hill, kalimdor 40_31
         wmo=113021, wdt=782779, tile=(40, 31), uid=359836, doodadSets=[0], area=362, wmoArea=None,
         outsideRadius=40.0, outsideFog=(30.0, 65.0),
         room=dict(bed=2, eye=(8.98, -5.90, 3.49))),
    dict(id="bloodhoof_inn", name="Bloodhoof Village Inn, Mulgore", kind="interior", people="tauren",
         # taurenlonghouse.wmo, WMOID 704, Set_Inn; Bloodhoof Village, kalimdor 32_36
         wmo=113176, wdt=782779, tile=(32, 36), uid=340063, doodadSets=[0, 3], area=222, wmoArea=None,
         outsideRadius=45.0, outsideFog=(30.0, 70.0),
         room=dict(bed=81, eye=(11.52, 2.71, 10.74))),                              # the loft's beds over the hall
    dict(id="fairbreeze_inn", name="Fairbreeze Village Inn, Eversong", kind="interior", people="blood elf",
         # bloodelf_twostory01.wmo, WMOID 4468, Set_TwoStory_02; Fairbreeze Village, expansion01 44_15
         wmo=114286, wdt=828395, tile=(44, 15), uid=742849, doodadSets=[0, 2], area=3462, wmoArea=None,
         outsideRadius=40.0, outsideFog=(30.0, 65.0),
         room=dict(bed=254, chair=211, eye=(2.38, -5.03, 25.67),
                   outside=dict(eye=(-55.0, 12.77, 3.0), at=(-15.0, 12.77, 6.0), spawn=(-37.0, 12.77)))),
    dict(id="brill_house", name="A House in Brill, Tirisfal", kind="interior", people="Forsaken",
         # nd_forsaken_house.wmo, WMOID 4953; Brill, azeroth 31_27 (beside the Brill Inn)
         wmo=114858, wdt=775971, tile=(31, 27), uid=5521907, doodadSets=[0], area=159, wmoArea=None,
         outsideRadius=40.0, outsideFog=(28.0, 60.0),
         room=dict(bed=9, eye=(-2.69, 3.70, 9.61),                                  # the upstairs bed among the coffins
                   props=[dict(kind="BOOKSHELF", model=203837, near=(-0.54, -4.51)),    # scholme_bookshelflarge, upstairs
                          dict(kind="BOOKSHELF", model=189993, near=(-3.42, -4.63))])),  # karazahnbookshelfsmall, upstairs
    dict(id="gadgetzan_inn", name="Gadgetzan Inn, Tanaris", kind="interior", people="goblin",
         # kl_tanaris_goblinbld_b.wmo, WMOID 1770 name set 2 (WMOAreaTable 89788, area 976), set_GoblinInn; kalimdor 39_45
         wmo=112806, wdt=782779, tile=(39, 45), uid=357332, doodadSets=[0, 1], area=976, wmoArea=89788,
         outsideRadius=40.0, outsideFog=(30.0, 70.0),
         room=dict(bed=23, eye=(-3.39, 1.49, 0.42))),
    dict(id="senjin_hut", name="Sen'jin Village Hut, Durotar", kind="composed", people="troll",
         # troll_hotel01.wmo, WMOID 1198, standing in Sen'jin Village (kalimdor 41_33) with its set_TrollGeneral (set 2);
         # composed: furnished instead with the same building's own set_TrollInn (set 1, the game's bunk-hammocks and
         # sleeping mats for this hut), as no troll hut the game stands is furnished to sleep in
         wmo=113195, wdt=782779, tile=(41, 33), uid=576557, doodadSets=[0, 1], area=367, wmoArea=None,
         outsideRadius=40.0, outsideFog=(30.0, 65.0),
         room=dict(bed=25, bedTop=1.25, eye=(1.81, -5.96, 16.88))),                 # an upstairs hammock (its sling)
    dict(id="telaar_hut", name="A Hut in Telaar, Nagrand", kind="composed", people="draenei",
         # ancdraehuta.wmo, WMOID 4566, as it stands in Telaar (expansion01 18_36, area 3626), furnished by the game with
         # books, cushions and stores; composed: the game has no draenei bed in any building it stands, so a bed of the
         # draenei of Draenor (6dr_draenei_bed01, the same ancient draenei style as the hut) is added to it
         wmo=115783, wdt=828395, tile=(18, 36), uid=798146, doodadSets=[0], area=3626, wmoArea=None,
         outsideRadius=40.0, outsideFog=(30.0, 70.0),
         extras=[dict(model=979920, at=(5.0, 6.5, 1.0), yawDeg=90.0, scale=0.8)],     # on the rug, the stores behind it
         room=dict(bed="extra:0", headTurn=math.pi, eye=(1.11, 10.39, 3.9),         # its pillows are at its +y end
                   props=[dict(kind="BOOKSHELF", model=193332, near=(0.3, 10.23)),      # ancient_d_bookshelf, the ground floor's
                          dict(kind="BOOKSHELF", model=193332, near=(-1.76, 9.15)),
                          dict(kind="BOOKSHELF", model=193332, near=(6.68, 12.43))])),
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
        self.tables = {}

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
        if name in self.tables:
            return self.tables[name]
        path = os.path.join(self.cache, name + ".csv")
        if not os.path.exists(path):
            data, code = self._get(DB2 % name, path)
            if data is None:
                raise SystemExit("no table %s (%d)" % (name, code))
        self.tables[name] = list(csv.DictReader(io.open(path, encoding="utf-8")))
        return self.tables[name]


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


def m2_info(st, fid):
    """A prop's box (MD20 bounding box: min xyz, max xyz) and its vertices' positions, in its own frame."""
    d = st.file(fid)
    if not d:
        return None
    o = next((o for t, o, n in chunks(d) if t[::-1] == "MD21"), None)
    if o is None:
        return None
    box = struct.unpack_from("<6f", d, o + 160)
    nv, ov = struct.unpack_from("<2I", d, o + 60)
    verts = [struct.unpack_from("<3f", d, o + ov + i * 48) for i in range(nv)]
    return dict(box=box, verts=verts)


def quat_yaw(q):
    x, y, z, w = q
    return math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))


def m2_tris(st, fid, verts):
    """A prop's triangles (its first skin's), as positions in its own frame."""
    d = st.file(fid)
    skin = next((struct.unpack_from("<I", d, o)[0] for t, o, n in chunks(d) if t[::-1] == "SFID" and n >= 4), 0)
    k = st.file(skin) if skin else None
    if not k or k[:4] != b"SKIN":
        return []
    n_look, o_look, n_tri, o_tri = struct.unpack_from("<4I", k, 4)
    look = struct.unpack_from("<%dH" % n_look, k, o_look)
    idx = struct.unpack_from("<%dH" % n_tri, k, o_tri)
    return [tuple(verts[look[idx[i + j]]] for j in range(3)) for i in range(0, n_tri - 2, 3)]


def lying_top(st, fid, info, scale, cap):
    """Where someone lies on a bed: its surface under its middle, found by dropping a line onto its own triangles from
    `cap` yards up (a canopy, posts and a headboard stand above that or beside the middle); along its length a little
    either way too, the median taken. Its vertices' highest under `cap` when it has no skin to drop onto."""
    tris = m2_tris(st, fid, info["verts"])
    b = info["box"]
    cx, cy = (b[0] + b[3]) / 2, (b[1] + b[4]) / 2
    hits = []
    for fx in (-0.2, 0.0, 0.2):
        for fy in (-0.15, 0.0, 0.15):
            o = (cx + fx * (b[3] - b[0]), cy + fy * (b[4] - b[1]), cap / scale)
            t = ray_hit(tris, o, (0.0, 0.0, -1.0), cap / scale + 1)
            if t < cap / scale + 1:
                hits.append(o[2] - t)
    if hits:
        return sorted(hits)[len(hits) // 2] * scale
    zs = [v[2] for v in info["verts"] if v[2] * scale < cap]
    return (max(zs) if zs else b[5]) * scale


def ray_hit(tris, o, d, maxd):
    """The nearest hit of the ray o + t d (t in 0..maxd) on the triangles (both faces), or maxd."""
    best = maxd
    for a, b, c in tris:
        e1 = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
        e2 = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
        p = (d[1] * e2[2] - d[2] * e2[1], d[2] * e2[0] - d[0] * e2[2], d[0] * e2[1] - d[1] * e2[0])
        det = e1[0] * p[0] + e1[1] * p[1] + e1[2] * p[2]
        if abs(det) < 1e-9:
            continue
        inv = 1.0 / det
        s = (o[0] - a[0], o[1] - a[1], o[2] - a[2])
        u = (s[0] * p[0] + s[1] * p[1] + s[2] * p[2]) * inv
        if u < 0 or u > 1:
            continue
        q = (s[1] * e1[2] - s[2] * e1[1], s[2] * e1[0] - s[0] * e1[2], s[0] * e1[1] - s[1] * e1[0])
        v = (d[0] * q[0] + d[1] * q[1] + d[2] * q[2]) * inv
        if v < 0 or u + v > 1:
            continue
        t = (e2[0] * q[0] + e2[1] * q[1] + e2[2] * q[2]) * inv
        if 0.05 < t < best:
            best = t
    return best


def near_tris(tris, p, r):
    return [t for t in tris if min(abs(v[0] - p[0]) for v in t) < r and min(abs(v[1] - p[1]) for v in t) < r
            and min(abs(v[2] - p[2]) for v in t) < r]



# Chair seats (yards over the floor) measured on the models by engine-test/audit (`solid`: the top of the seat's stretch at
# its middle): the Lion's Pride Inn's chair 198115. Others keep the usual 0.47 m (0.514 yards) until measured.
CHAIR_SEATS = {198115: 0.479}


def furniture_rows(spots):
    """The location's furniture for animations (locations.json "furniture", read by location/Locations.kt FurniturePiece and
    simulation/Furniture.of), from its interaction spots: each piece's kind, its middle on the floor, its front's direction
    and its surface's height over that floor (yards).

    A bed: its middle at the lying pose, on the floor its entry stands on, its front (the foot) opposite the pose's facing
    (bedroom() faces it toward the headboard: the end that stands higher), its surface the mattress (the pose's height over
    that floor); every bed is taken as a double: bedroom() steps 0.55 yards out from half its width and every bed's half
    width came out 0.9 yards or more. A chair: where it stands, its front the model's +x (as bedroom() faces the sitter;
    not checked against each model), its seat measured where CHAIR_SEATS has the model, else the usual 0.47 m. A table: its model's middle, its front toward
    the spot its reader stands at, its top as high as the lowest of its books stands (their origins: a book's base, not
    checked), else the usual 0.75 m."""
    rows = []
    for s in spots:
        obj, entry, facing = s["object"], s["entry"], s["facing"]
        flip = math.atan2(math.sin(facing + math.pi), math.cos(facing + math.pi))
        if obj == "BED":
            pose = s.get("pose", entry)
            rows.append(dict(object=obj, kind="BED_DOUBLE", position=[pose[0], pose[1], entry[2]], yaw=round(flip, 4),
                             surface=round(pose[2] - entry[2], 3)))
        elif obj == "CHAIR":
            # where it stands: the seat (its pose); its entry is the floor beside it the sitter gets up onto; its seat's
            # height as engine-test/audit measures the model's top over its floor (CHAIR_SEATS), else the usual 0.47 m
            model = (s.get("models") or [{}])[0].get("id")
            rows.append(dict(object=obj, kind="CHAIR", position=list(s.get("pose", entry)), yaw=round(facing, 4),
                             surface=CHAIR_SEATS.get(model, 0.514)))
        elif obj == "TABLE" and s.get("models"):
            m = s["models"][0]["position"]
            books = [b["position"][2] for b in s["models"][1:]]
            top = round(min(books) - entry[2], 3) if books else 0.82
            rows.append(dict(object=obj, kind="TABLE", position=[m[0], m[1], entry[2]], yaw=round(flip, 4), surface=top))
    return rows

def bedroom(st, w, tris, room, extras=()):
    """The bedroom's view, spawn and interaction spots from the building's own furniture (room: MODD indices).

    The bed: lying on it at its middle, as high as its mattress, facing its length (the model's +x, the way the game's
    beds lie; room["headTurn"] turns it, radians); getting in from the floor beside its long side, on the side with more room. The camera: from the room's
    widest side of the bed, as far as the walls let it (at most 5.5 yards), 2 yards up, looking at the bed. A chair: sat in
    where it stands, facing its way. A table: read at, standing 1.1 yards in front of it, with its books."""
    def dd(i):
        # an index of the building's MODD, or "extra:N", the location's Nth composed prop
        return extras[int(i[6:])] if isinstance(i, str) else w.doodads[i]
    bed = dd(room["bed"])
    info = m2_info(st, bed["model"])
    yaw = quat_yaw(bed["rot"])
    sc = bed["scale"]
    bx, by, bz = bed["pos"]
    box = info["box"]
    # the box's middle, turned and scaled into the building's frame
    mx, my = (box[0] + box[3]) / 2 * sc, (box[1] + box[4]) / 2 * sc
    cx = bx + mx * math.cos(yaw) - my * math.sin(yaw)
    cy = by + mx * math.sin(yaw) + my * math.cos(yaw)
    floor = floor_at(tris, cx, cy, bz + 0.3)
    floor = bz if floor is None else floor
    top = room.get("bedTop")
    top = bz + (top if top is not None else lying_top(st, bed["model"], info, sc, 1.05))
    # its length: its box's longer side (the model's x when nearly square, as the Lion's Pride's canopy bed, whose pillows
    # are at +x); its head: the end where it stands higher (the headboard); room["headTurn"] for one that lies otherwise
    ex_, ey_ = box[3] - box[0], box[4] - box[1]
    along_y = ey_ > 1.15 * ex_
    axis = 1 if along_y else 0
    mid = (box[axis] + box[axis + 3]) / 2
    hi_plus = max([v[2] for v in info["verts"] if v[axis] > mid] or [0])
    hi_minus = max([v[2] for v in info["verts"] if v[axis] < mid] or [0])
    head = -1 if hi_minus > hi_plus * 1.05 else 1
    width = (ex_ if along_y else ey_) * sc / 2
    yaw += (math.pi / 2 if along_y else 0.0) + (math.pi if head < 0 else 0.0)
    yaw = math.atan2(math.sin(yaw + room.get("headTurn", 0.0)), math.cos(yaw + room.get("headTurn", 0.0)))
    local = near_tris(tris, (cx, cy, floor + 1.5), 12)
    side = (-math.sin(yaw), math.cos(yaw))
    best = None
    for sgn in (1, -1):
        ex, ey = cx + side[0] * sgn * (width + 0.55), cy + side[1] * sgn * (width + 0.55)
        f = floor_at(local, ex, ey, floor + 0.5)
        if f is None or abs(f - floor) > 0.3:
            continue
        room_d = ray_hit(local, (cx, cy, floor + 0.6), (side[0] * sgn, side[1] * sgn, 0), 8)
        if best is None or room_d > best[0]:
            best = (room_d, (ex, ey, f), sgn)
    if best is None:
        ex, ey = cx + side[0] * (width + 0.55), cy + side[1] * (width + 0.55)
        best = (0, (ex, ey, floor), 1)
    entry = best[1]
    spots = [dict(object="BED", entry=rnd(entry), pose=rnd((cx, cy, top)), facing=round(yaw, 4),
                  models=[dict(id=bed["model"], position=rnd(bed["pos"]))])]
    # the camera: every way round the bed, the one with the most room (a side view of the bed preferred)
    eye_z = floor + 2.0
    at = (cx, cy, top + 0.45)
    cand = None
    for k in range(36):
        a = math.radians(k * 10)
        d = (math.cos(a), math.sin(a), 0.0)
        free = ray_hit(local, (cx, cy, eye_z), d, 9)
        dist = min(free - 0.6, 5.5)
        if dist < 1.5:
            continue
        ex_, ey_ = cx + d[0] * dist, cy + d[1] * dist
        f = floor_at(local, ex_, ey_, eye_z)
        if f is None or abs(f - floor) > 0.6:
            continue
        across = abs(math.sin(a - yaw))
        score = dist + 1.5 * across
        if cand is None or score > cand[0]:
            cand = (score, (ex_, ey_, eye_z))
    if cand is None:
        cand = (0, (cx + side[0] * best[2] * 3, cy + side[1] * best[2] * 3, eye_z))
    eye = room.get("eye") or cand[1]
    at = room.get("at") or at
    spawn_facing = math.atan2(eye[1] - entry[1], eye[0] - entry[0])
    table_at = dd(room["table"])["pos"] if "table" in room else None
    for key, obj in (("chair", "CHAIR"),):
        if key in room:
            c = dd(room[key])
            cf = floor_at(local, c["pos"][0], c["pos"][1], c["pos"][2] + 0.5)
            p = (c["pos"][0], c["pos"][1], c["pos"][2] if cf is None else cf)
            cyaw = quat_yaw(c["rot"])
            # the floor its sitter gets up onto: in front of the seat, else beside it (a desk in front), on the same floor,
            # clear of the chair's own spot and out of the room's table (never the seat itself: they'd stand up in the chair)
            stand = None
            for turn, d in ((0.0, 0.6), (math.pi / 2, 0.75), (-math.pi / 2, 0.75), (0.0, 0.9)):
                a = cyaw + turn
                sx, sy = p[0] + math.cos(a) * d, p[1] + math.sin(a) * d
                f = floor_at(local, sx, sy, p[2] + 0.5)
                if f is None or abs(f - p[2]) > 0.3:
                    continue
                if table_at is not None and abs(sx - table_at[0]) < 0.7 and abs(sy - table_at[1]) < 0.7:
                    continue
                if ray_hit(local, (p[0], p[1], p[2] + 0.6), (math.cos(a), math.sin(a), 0), 2) < d:
                    continue
                stand = (sx, sy, f)
                break
            spots.append(dict(object=obj, entry=rnd(stand or p), pose=rnd(p), facing=round(cyaw, 4),
                              models=[dict(id=c["model"], position=rnd(c["pos"]))]))
    if "table" in room:
        t = dd(room["table"])
        tyaw = quat_yaw(t["rot"])
        # stand at the table's side that faces the room (towards the bed's camera side)
        opts = []
        for a in (tyaw, tyaw + math.pi / 2, tyaw + math.pi, tyaw - math.pi / 2):
            px, py = t["pos"][0] + math.cos(a) * 1.1, t["pos"][1] + math.sin(a) * 1.1
            f = floor_at(local, px, py, t["pos"][2] + 0.5)
            if f is not None and abs(f - t["pos"][2]) < 0.4:
                opts.append((ray_hit(local, (px, py, f + 1.0), (math.cos(a), math.sin(a), 0), 4), (px, py, f), a))
        if opts:
            _, p, a = max(opts)
            spots.append(dict(object="TABLE", entry=rnd(p), facing=round(math.atan2(-math.sin(a), -math.cos(a)), 4),
                              models=[dict(id=t["model"], position=rnd(t["pos"]))] +
                                     [dict(id=dd(b)["model"], position=rnd(dd(b)["pos"])) for b in room.get("books", [])]))
    view = dict(eye=rnd(eye), at=rnd(at), fov=60)
    spawn = dict(pos=rnd(entry), facing=round(spawn_facing, 4))
    return view, spawn, spots, room_props(w, local, room)


def room_props(w, tris, room):
    """The life simulation's props of the room (locations.json "props", simulation/LifeProps): only the game's own models
    that stand in it (room["props"]: each a kind, FIREPLACE, BASIN or BOOKSHELF, the model's file id and a point near it,
    picked by looking at the room's doodads' file names: fireplace, hearth, basin, bookshelf, bookcase). The one of that
    model nearest the point; its user stands 0.8 yards out on the side with floor at its height and the most room (its
    model's +x first), facing it. None is made up: a room with none of the game's own has none."""
    out = []
    for want in room.get("props", []):
        near = [d for d in w.doodads if d["model"] == want["model"]]
        if not near:
            continue
        d = min(near, key=lambda d: math.hypot(d["pos"][0] - want["near"][0], d["pos"][1] - want["near"][1]))
        yaw = quat_yaw(d["rot"])
        best = None
        for turn in (0.0, math.pi / 2, -math.pi / 2, math.pi):
            a = yaw + turn
            px, py = d["pos"][0] + math.cos(a) * 0.8, d["pos"][1] + math.sin(a) * 0.8
            f = floor_at(tris, px, py, d["pos"][2] + 0.5)
            if f is None or abs(f - d["pos"][2]) > 0.3:
                continue
            free = ray_hit(tris, (px, py, f + 1.0), (math.cos(a), math.sin(a), 0), 4)
            if best is None or free > best[0] + 0.5:
                best = (free, (px, py, f))
        if best is None:
            continue
        p = best[1]
        out.append(dict(kind=want["kind"], entry=rnd(p), facing=round(math.atan2(d["pos"][1] - p[1], d["pos"][0] - p[0]), 4),
                        model=d["model"], position=rnd(d["pos"])))
    return out


def door_portal(w, tris):
    """The building's front door: of the upright portals with open sky on one side (nothing over the step out), the
    lowest; else portal 0."""
    best = None
    for i, p in enumerate(w.portals):
        c = tuple(sum(v[k] for v in p["verts"]) / len(p["verts"]) for k in range(3))
        n = p["normal"]
        for sgn in (1, -1):
            x, y = c[0] + n[0] * sgn * 2.5, c[1] + n[1] * sgn * 2.5
            roof = ray_hit(near_tris(tris, (x, y, c[2]), 15), (x, y, c[2]), (0, 0, 1), 40)
            if roof >= 40 and abs(n[2]) < 0.3:
                key = (c[2], i)
                if best is None or key < best:
                    best = key
    return best[1] if best else 0


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
    tris_all = tris
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
    # a composed location's own props (the game's models, placed by hand: kind "composed"), standing on the building's
    # floor under (x, y), turned yawDeg about z
    extras = []
    for x in loc.get("extras", []):
        f = floor_at(tris_all, x["at"][0], x["at"][1], x["at"][2] + 0.5)
        pos = (x["at"][0], x["at"][1], x["at"][2] if f is None else f)
        a = math.radians(x["yawDeg"]) / 2
        extras.append(dict(model=x["model"], pos=rnd(pos), rot=rnd((0.0, 0.0, math.sin(a), math.cos(a)), 5),
                           scale=round(x.get("scale", 1.0), 4)))
    yard += [dict(e, set=-1, composed=True) for e in extras]
    others = []
    for p in modf:
        if p["uid"] == loc["uid"]:
            continue
        pw = apply(adt_matrix(p["pos"], p["rot"]), (0, 0, 0))
        d = math.hypot(pw[0] - centre_w[0], pw[1] - centre_w[1])
        if d < radius + 30:
            others.append((p["fid"], round(d, 1)))

    ter = Terrain(st, root, tex0)
    # the ground: as far as the outside view's fog reaches past the yard (the Embassy's hand-picked 95 yards, as a rule)
    near = ter.near(centre_w[0], centre_w[1], radius + 55 if loc.get("room") else radius)
    layers = sorted({ter.diffuse[i] for k in near for i in ter.chunks[k]["layers"] if i < len(ter.diffuse)})

    # the door: the building's portal between outside and in (a bedroom's building: its front door, door_portal)
    portal = (w.portals[door_portal(w, tris)] if loc.get("room") else w.portals[0]) if w.portals else None
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
    cont = str(continent(st, loc))
    lights = [r for r in st.table("Light") if r["ContinentID"] == cont]
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
    cycle = day_cycle(st, lp[1])
    day_fog = dict(colour=colour_rgb(noon["SkyFogColor"]), start=loc["outsideFog"][0], end=loc["outsideFog"][1])
    room_light = dict(ambient=colour_bgra(w.ambient), direct=[0.0, 0.0, 0.0], vertexColours=True,
                      source="MOHD ambient; the room's light is baked into its vertex colours (MOCV)")
    unbaked = [i for i in indoor if "MOCV" not in groups[i].sub]
    if loc.get("room") and unbaked and sum(room_light["ambient"]) < 0.06:
        # rooms with no baked light (no MOCV) and no ambient (the Gadgetzan inn's): the game lights them with the area's
        # light; without it the engine draws them black. The day's ambient and half its sun, as one flat light.
        room_light = dict(ambient=[round(min(1.0, a + 0.5 * d), 3) for a, d in zip(day["ambient"], day["direct"])],
                          direct=[0.0, 0.0, 0.0], vertexColours=True,
                          source="the map's day light (ambient + half the sun): groups %s have no MOCV and MOHD's ambient is black" % unbaked)
    f = w.fogs[0] if w.fogs else None
    room_fog = dict(colour=colour_bgra(f["color"]) if f else day_fog["colour"],
                    start=round(f["end"] * f["mult"], 2) if f else 30.0, end=round(f["end"], 2) if f else 60.0,
                    source="MFOG 0" if f else "none")
    inside["light"], inside["fog"] = room_light, room_fog
    outside["light"], outside["fog"] = day, day_fog
    inside["groups"] = list(range(len(w.groups)))
    outside["groups"] = list(range(len(w.groups)))
    spots = []
    props = []
    if loc.get("room"):
        # a bedroom: the camera on its bed, the person beside it; the room's MFOG kept as interiorFog and the map's fog
        # seen through windows and doors, as the Embassy's
        inside["view"], inside["spawn"], spots, props = bedroom(st, w, tris, loc["room"], extras)
        ov = loc["room"].get("outside")
        if ov:
            # the outside view picked by looking (tools: the yard seen from all round): eye and target as (x, y, height
            # over the ground there), the person standing on the ground at spawn (x, y), facing the camera
            def on_ground(x, y, dz):
                g = ground(x, y, sill + 3)
                return (x, y, (sill if g is None else g) + dz)
            eye, at = on_ground(*ov["eye"]), on_ground(*ov["at"])
            sp = on_ground(ov["spawn"][0], ov["spawn"][1], 0.0)
            outside["view"] = dict(eye=rnd(eye), at=rnd(at), fov=55)
            outside["spawn"] = dict(pos=rnd(sp), facing=round(math.atan2(eye[1] - sp[1], eye[0] - sp[0]), 4))
        inside["interiorFog"] = room_fog
        inside["fog"] = dict(day_fog, source="the map's, as outside: seen through doors and windows; MFOG is interiorFog")

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
        id=loc["id"], name=loc["name"], kind=loc["kind"], **({"people": loc["people"]} if "people" in loc else {}),
        **({"interactions": spots} if spots else {}),
        **({"furniture": furniture_rows(spots)} if spots else {}),
        **({"props": props} if props else {}),
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
        light=day, fog=day_fog, dayCycle=cycle,
        spawns=dict(inside=inside["spawn"], outside=outside["spawn"]),
        views=dict(inside=dict(inside["view"], light=inside["light"], groups=inside["groups"],
                               **({"interiorFog": inside["interiorFog"]} if "interiorFog" in inside else {}), fog=inside["fog"]),
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


def day_cycle(st, params):
    """The map light's whole day: every LightData row of LightParams `params`, by hour (Time / 120: the game counts
    half-minutes), as [hour, ambient rgb, direct rgb, sky middle rgb, sky fog rgb] (cpp engine/daylight.h's keys)."""
    rows = sorted((r for r in st.table("LightData") if r["LightParamID"] == str(params)), key=lambda r: int(r["Time"]))
    keys = []
    for r in rows:
        hour = round(int(r["Time"]) / 120.0, 4)
        if keys and keys[-1][0] == hour:
            continue
        keys.append([hour] + colour_rgb(r["AmbientColor"]) + colour_rgb(r["DirectColor"])
                    + colour_rgb(r["SkyMiddleColor"]) + colour_rgb(r["SkyFogColor"]))
    return dict(source="LightParams %s: LightData's %d rows, hour = Time / 120; [hour, ambient, direct, sky, fog]" % (params, len(keys)),
                keys=keys)


def add_day_cycles(st):
    """--day-cycle: each entry's dayCycle from the LightParams its light names, the rest of its text untouched."""
    path = os.path.join(ASSETS, "locations.json")
    text = open(path).read()
    old = json.loads(text)
    parts = []
    for loc, t in zip(old["locations"], entry_texts(text)):
        m = re.search(r"LightParams (\d+)", loc.get("light", {}).get("source", ""))
        if not m:
            log("%s: its light names no LightParams; no dayCycle" % loc["id"])
            parts.append(t)
            continue
        cycle = day_cycle(st, m.group(1))
        body = "\n  ".join(compact(json.dumps(cycle, indent=1)).split("\n"))
        # its own keys on one line each
        body = re.sub(r"\[\n\s+(\[[^\[\]]*\](?:,\n\s+\[[^\[\]]*\])*)\n\s+\]",
                      lambda g: "[\n    " + ",\n    ".join(x.strip() for x in g.group(1).split(",\n")) + "\n   ]", body)
        if "dayCycle" in loc:
            # replace the old one: the entry rebuilt from its JSON (its other parts keep their values)
            loc["dayCycle"] = cycle
            parts.append("\n  ".join(compact(json.dumps(loc, indent=1)).split("\n")))
            continue
        end = t.rstrip().rindex("}")
        parts.append(t[:end].rstrip() + ",\n   \"dayCycle\": " + body.replace("\n  ", "\n   ") + "\n  }")
        log("%s: dayCycle from LightParams %s, %d keys" % (loc["id"], m.group(1), len(cycle["keys"])))
    with open(path, "w") as f:
        f.write("{\n \"comment\": %s,\n \"build\": %s,\n \"locations\": [\n  %s\n ]\n}\n" % (
            json.dumps(old["comment"]), json.dumps(old["build"]), ",\n  ".join(parts)))


def continent(st, loc):
    return int(next(r for r in st.table("AreaTable") if r["ID"] == str(loc["area"]))["ContinentID"])


def count_models(st, items):
    c = {}
    for d in items:
        c[d["model"]] = c.get(d["model"], 0) + 1
    return sorted(((n, m, st.name(m)) for m, n in c.items()), reverse=True)


def entry_texts(text):
    """The text of each entry of a locations.json's "locations" array, as it stands in the file."""
    dec = json.JSONDecoder()
    i = text.index("[", text.index('"locations"')) + 1
    out = []
    while True:
        while text[i] in " \t\r\n,":
            i += 1
        if text[i] == "]":
            return out
        _, end = dec.raw_decode(text, i)
        out.append(text[i:end])
        i = end


def compact(text):
    """json.dumps(indent=1)'s text with every list of plain numbers on one line ([x, y, z] rather than a line each)."""
    return re.sub(r"\[\s*(-?[0-9][-0-9.eE+]*(?:\s*,\s*-?[0-9][-0-9.eE+]*)*)\s*\]",
                  lambda m: "[" + ", ".join(v.strip() for v in m.group(1).split(",")) + "]", text)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--only=")]
    only = {x for a in sys.argv[1:] if a.startswith("--only=") for x in a[7:].split(",")}
    if not args:
        raise SystemExit(__doc__)
    if "--day-cycle" in args:
        add_day_cycles(Store(args[0]))
        return
    st = Store(args[0], args[1] if len(args) > 1 else None)
    todo = [l for l in LOCATIONS if not only or l["id"] in only]
    path = os.path.join(ASSETS, "locations.json")
    out = {"comment": "The editor's locations (tools/locations.py). Each is in its main building's own frame (x, y across, "
                      "z up, yards); doodads: model file id, pos, rot quaternion [x, y, z, w], scale. Files are fetched from "
                      "wago.tools by id at build " + BUILD + "; nothing of the game is in this file but ids and transforms.",
           "build": BUILD, "locations": []}
    kept = {}
    if os.path.exists(path):
        text = open(path).read()
        old = json.loads(text)
        out["locations"] = [l for l in old.get("locations", []) if l["id"] not in {x["id"] for x in todo}]
        kept = {l["id"]: t for l, t in zip(old.get("locations", []), entry_texts(text))}
    reports = {}
    for loc in todo:
        entry, rep = build(st, loc)
        out["locations"].append(entry)
        reports[loc["id"]] = rep
        log("%s: %d groups, %d props, %d yard props, %d ground chunks, %.2f MB, %d missing" % (
            loc["id"], len(entry["wmo"]["groups"]), sum(1 for d in entry["doodads"] if d["set"] >= 0),
            sum(1 for d in entry["doodads"] if d["set"] < 0), len(entry["terrain"]["chunks"]),
            entry["files"]["totalMB"], len(entry["files"]["missing"])))
    order = [l["id"] for l in LOCATIONS]      # the scene list's order: the Embassy first, then the bedrooms as listed
    out["locations"].sort(key=lambda l: (order.index(l["id"]) if l["id"] in order else len(order), l["id"]))
    # the entries not rebuilt are kept as their text was (the Embassy's hand-made parts, as written); the rebuilt ones
    # with their number lists on one line
    rebuilt = {x["id"] for x in todo}
    parts = [kept[l["id"]] if l["id"] in kept and l["id"] not in rebuilt else
             "\n  ".join(compact(json.dumps(l, indent=1)).split("\n")) for l in out["locations"]]
    with open(path, "w") as f:
        f.write("{\n \"comment\": %s,\n \"build\": %s,\n \"locations\": [\n  %s\n ]\n}\n" % (
            json.dumps(out["comment"]), json.dumps(out["build"]), ",\n  ".join(parts)))
    if os.environ.get("LOCATIONS_REPORT"):
        with open(os.environ["LOCATIONS_REPORT"], "w") as f:
            json.dump(reports, f, indent=1, default=str)


if __name__ == "__main__":
    main()
