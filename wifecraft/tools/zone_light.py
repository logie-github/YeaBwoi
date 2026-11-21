#!/usr/bin/env python3
"""The zone's own light for each location (cinematic mode's zone mood, cpp engine/daylight.h ZoneLight): the light the game
itself draws at the place, by the hour, from its light tables at the app's build. Writes assets/zone_light.json (what was
used and why, every place's keys) and puts the same keys into each locations.json entry's "cinematic" block as "zone" (the
renderer reads its location's entry only; every other line of locations.json stays as it stands).

    python3 tools/zone_light.py CACHE_DIR

How the game picks a place's light (and so how this does):
  1. ZoneLight: a polygon on the map (ZoneLightPoint, in order) with a height range; the place's (x, y) inside it and its z
     in [Zmin, Zmax] picks its LightID. (Retail's zones are lit this way: Tirisfal, Elwynn, Teldrassil...)
  2. Else a point light (Light with GameCoords not 0) whose GameFalloffEnd reaches the place (the nearest).
  3. Else the map's default (Light with GameCoords 0, 0, 0 on the map).
The Light row's LightParamsID_0 (clear weather) gives LightParams (its Glow) and LightData's keyframes (Time / 120 is the
hour: the game counts half-minutes, 2880 a day), each a key:

  [hour, ambient rgb, direct rgb, sky top rgb, sky middle rgb, fog rgb, end fog rgb, fog near, fog far,
   sun rgb, cloud rgb, sky band 1 rgb, sky band 2 rgb, horizon ambient rgb, ground ambient rgb]

  ambient AmbientColor, direct DirectColor (the sun's or moon's light), sky top SkyTopColor, sky middle SkyMiddleColor, fog
  SkyFogColor, end fog EndFogColor (SkyFogColor where it is black: older params have none); sun SunColor (the sun's disc
  and glow), cloud CloudSunColor, the bands SkyBand1Color and SkyBand2Color, horizon and ground ambient HorizonAmbientColor
  and GroundAmbientColor (each its neighbour's where it is black: the sky's middle, the ambient); colours 0..1.
  fog far (yards): FogEnd / 36 where FogEnd is set (the older params' unit); else from FogDensity (retail's exponential
  fog: 2400 / density, so density 4.5, Elwynn's, is about 530 yards and Tirisfal's 14 about 170); clamped 80..1600.
  fog near: fog far times FogScaler (clamped 0..0.8). Both are an estimate of how far the zone sees, used for the cinematic
  fog's distance only (the engine compresses and caps them); the colours are the game's own numbers.

CACHE_DIR keeps the tables (<Name>.csv) fetched from wago.tools (db2 CSV export at the build tools/locations.py names).
stdlib only.
"""
import csv
import io
import json
import math
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import locations as L  # noqa: E402  (entry_texts, compact, BUILD, ASSETS)

TABLES = ["Light", "LightParams", "LightData", "ZoneLight", "ZoneLightPoint"]


def table(cache, name):
    path = os.path.join(cache, name + ".csv")
    if not os.path.exists(path):
        req = urllib.request.Request(L.DB2 % name, headers={"User-Agent": L.AGENT})
        data = urllib.request.urlopen(req, timeout=180).read()
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
    rows = list(csv.DictReader(io.open(path, encoding="utf-8")))
    return rows, os.path.getsize(path)


def rgb(v):
    v = int(v)
    return [round(((v >> 16) & 255) / 255, 3), round(((v >> 8) & 255) / 255, 3), round((v & 255) / 255, 3)]


def inside(poly, x, y):
    c = False
    for i in range(len(poly)):
        x1, y1 = poly[i]
        x2, y2 = poly[i - 1]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def pick_light(t, mapid, pos):
    """(Light row, how) the game lights `pos` on `mapid` with."""
    lights = {r["ID"]: r for r in t["Light"]}
    pts = {}
    for r in t["ZoneLightPoint"]:
        pts.setdefault(r["ZoneLightID"], []).append((int(r["PointOrder"]), float(r["Pos_0"]), float(r["Pos_1"])))
    for z in t["ZoneLight"]:
        if z["MapID"] != str(mapid) or z["ID"] not in pts or z["PlayerConditionID"] not in ("0", ""):
            continue
        poly = [(a, b) for _, a, b in sorted(pts[z["ID"]])]
        if inside(poly, pos[0], pos[1]) and float(z["Zmin"]) <= pos[2] <= float(z["Zmax"]) and z["LightID"] in lights:
            return lights[z["LightID"]], "ZoneLight %s %r (a polygon of %d points) -> Light %s" % (
                z["ID"], z["Name"].strip(), len(poly), z["LightID"])
    best = None
    for r in t["Light"]:
        if r["ContinentID"] != str(mapid):
            continue
        c = [float(r["GameCoords_%d" % i]) for i in range(3)]
        if c == [0.0, 0.0, 0.0]:
            continue
        d = math.dist(c, pos)
        if d < float(r["GameFalloffEnd"]) and (best is None or d < best[0]):
            best = (d, r)
    if best:
        return best[1], "point Light %s (%.0f yards away, falloff end %.0f)" % (best[1]["ID"], best[0], float(best[1]["GameFalloffEnd"]))
    for r in t["Light"]:
        if r["ContinentID"] == str(mapid) and all(float(r["GameCoords_%d" % i]) == 0 for i in range(3)):
            return r, "no zone or point light reaches it: the map's default, Light %s" % r["ID"]
    raise SystemExit("map %s has no default light" % mapid)


def keys_of(t, params):
    rows = sorted((r for r in t["LightData"] if r["LightParamID"] == str(params)), key=lambda r: int(r["Time"]))
    keys = []
    for r in rows:
        hour = round(int(r["Time"]) / 120.0, 4)
        if keys and keys[-1][0] == hour:
            continue
        fog = rgb(r["SkyFogColor"])
        end = rgb(r["EndFogColor"])
        if sum(end) == 0:
            end = fog
        fe = float(r["FogEnd"])
        far = fe / 36.0 if fe > 0 else 2400.0 / max(float(r["FogDensity"]), 0.5)
        far = min(1600.0, max(80.0, far))
        near = far * min(0.8, max(0.0, float(r["FogScaler"])))
        mid = rgb(r["SkyMiddleColor"])
        amb = rgb(r["AmbientColor"])

        def band(name, fallback):
            c = rgb(r[name] or 0)
            return c if sum(c) > 0 else fallback
        keys.append([hour] + amb + rgb(r["DirectColor"]) + rgb(r["SkyTopColor"])
                    + mid + fog + end + [round(near, 1), round(far, 1)]
                    + band("SunColor", rgb(r["DirectColor"])) + band("CloudSunColor", mid) + band("SkyBand1Color", mid)
                    + band("SkyBand2Color", fog) + band("HorizonAmbientColor", amb) + band("GroundAmbientColor", amb))
    return keys


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cache = sys.argv[1]
    os.makedirs(cache, exist_ok=True)
    t, sizes = {}, {}
    for name in TABLES:
        t[name], sizes[name] = table(cache, name)
    params = {r["ID"]: r for r in t["LightParams"]}
    path = os.path.join(L.ASSETS, "locations.json")
    text = open(path).read()
    old = json.loads(text)
    places, parts = {}, []
    dec = json.JSONDecoder()
    for loc, et in zip(old["locations"], L.entry_texts(text)):
        fr = loc["frame"]
        light, how = pick_light(t, fr["map"], fr["origin"])
        lp = light["LightParamsID_0"]
        keys = keys_of(t, lp)
        glow = round(float(params[lp]["Glow"]), 3) if lp in params else 0.0
        source = "%s, LightParams %s (clear weather): LightData's %d rows" % (how, lp, len(keys))
        places[loc["id"]] = dict(map=fr["map"], at=fr["origin"], source=source, light=int(light["ID"]), params=int(lp),
                                 glow=glow, keys=keys)
        zone = dict(source="tools/zone_light.py: " + source + "; [hour, ambient, direct, sky top, sky middle, fog, end fog, "
                                                             "fog near, fog far, sun, cloud, sky band 1, sky band 2, horizon ambient, ground ambient]", glow=glow, keys=keys)
        print("%s: %s" % (loc["id"], source), file=sys.stderr)
        # the entry's "cinematic" block with its "zone" put in (or replaced); the rest of the entry as its text stands
        m = re.search(r'\n   "cinematic": ', et)
        if not m:
            raise SystemExit("%s has no cinematic block (tools/locations.py --cinematic first)" % loc["id"])
        block, end = dec.raw_decode(et, m.end())
        block["zone"] = zone
        body = "\n   ".join(L.compact(json.dumps(block, indent=1)).split("\n"))
        # each key on one line
        body = re.sub(r'("keys": )\[\n\s+(\[[^\[\]]*\](?:,\n\s+\[[^\[\]]*\])*)\n\s+\]',
                      lambda g: g.group(1) + "[\n      " + ",\n      ".join(x.strip() for x in g.group(2).split(",\n")) + "\n     ]",
                      body)
        parts.append(et[:m.end()] + body + et[end:])
    with open(path, "w") as f:
        f.write("{\n \"comment\": %s,\n \"build\": %s,\n \"locations\": [\n  %s\n ]\n}\n" % (
            json.dumps(old["comment"]), json.dumps(old["build"]), ",\n  ".join(parts)))
    out = dict(
        comment="Each location's zone light (tools/zone_light.py): the light the game draws at the place by the hour, picked "
                "as the game picks it (ZoneLight polygon, else a point Light's falloff, else the map's default) at build "
                + L.BUILD + "; keys [hour, ambient rgb, direct rgb, sky top rgb, sky middle rgb, fog rgb, end fog rgb, fog "
                "near, fog far (yards)]. The renderer reads the copy in each locations.json entry's cinematic.zone.",
        build=L.BUILD,
        tables={n: dict(rows=len(t[n]), bytes=sizes[n], url=L.DB2 % n) for n in TABLES},
        places=places)
    body = L.compact(json.dumps(out, indent=1))
    body = re.sub(r'("keys": )\[\n\s+(\[[^\[\]]*\](?:,\n\s+\[[^\[\]]*\])*)\n\s+\]',
                  lambda g: g.group(1) + "[\n    " + ",\n    ".join(x.strip() for x in g.group(2).split(",\n")) + "\n   ]", body)
    with open(os.path.join(L.ASSETS, "zone_light.json"), "w") as f:
        f.write(body + "\n")


if __name__ == "__main__":
    main()
