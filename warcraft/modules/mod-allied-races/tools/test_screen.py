#!/usr/bin/env python3
"""The races' character screen code (data/client/Interface/GlueXML/AlliedRacesCreate.lua) run in
Lua 5.1 against stand-ins for the game's screen: the extra buttons get the screen's names, each
side's races go down its column, the backgrounds and icons are theirs; and with the Skyborne's
screen code loaded after it (as in a client with both patches), both sides' races still line up.
Needs lupa (pip)."""
import os
import sys

from lupa import lua51

HERE = os.path.dirname(os.path.abspath(__file__))
LUA = open(os.path.join(HERE, "..", "data", "client", "Interface", "GlueXML", "AlliedRacesCreate.lua")).read()
SKYBORNE = os.path.join(HERE, "..", "..", "mod-skyborne", "data", "client", "Interface", "GlueXML", "SkyborneCreate.lua")
STUBS = r"""
strupper = string.upper
local function Button(name)
  local b = { name = name, points = {} }
  function b:SetParent(p) self.parent = p end
  function b:GetParent() return self.parent end
  function b:ClearAllPoints() self.points = {} end
  function b:SetPoint(...) self.points[#self.points + 1] = { ... } end
  _G[name] = b; _G[name.."NormalTexture"] = {}; _G[name.."PushedTexture"] = {}; _G[name.."Text"] = {}
  return b
end
Frame = { name = "CharacterCreateConfigurationFrame" }
for i = 1, NATIVE do Button("CharacterCreateRaceButton"..i).parent = Frame end
for i = 11, 16 do Button("AlliedRaceButton"..i).parent = { name = "xml" } end
for i = 11, 16 do Button("SkyborneRaceButton"..i).parent = { name = "xml" } end
MAX_RACES = NATIVE
RACE_ICON_TCOORDS = {}
CharacterCreate = {}
RACES = {}
function GetFactionForRace(i) return RACES[i][1], RACES[i][2] end
function CharacterCreateEnumerateRaces(...) CharacterCreate.numRaces = select("#", ...) / 3 end
BACKGROUND = nil
function SetBackgroundModel(model, name) BACKGROUND = name end
"""
failures = 0


def check(ok, what):
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


for native, races, with_skyborne in [
    (10, ["A Human", "A Dwarf", "A NightElf", "A Gnome", "A Draenei", "A VoidElf", "A LightforgedDraenei",
          "H Orc", "H Scourge", "H Tauren", "H Troll", "H BloodElf", "H Nightborne", "H ZandalariTroll"], False),
    (10, ["A Human", "A Dwarf", "A NightElf", "A Gnome", "A Draenei", "A VoidElf", "A LightforgedDraenei", "A SkyborneA",
          "H Orc", "H Scourge", "H Tauren", "H Troll", "H BloodElf", "H Nightborne", "H ZandalariTroll", "H SkyborneH"], True),
]:
    lua = lua51.LuaRuntime(unpack_returned_tuples=True)
    lua.execute(f"NATIVE = {native}")
    lua.execute(STUBS)
    for i, r in enumerate(races, 1):
        side, name = r.split()
        lua.execute(f'RACES[{i}] = {{ "{name}", "{"Alliance" if side == "A" else "Horde"}" }}')
    lua.execute(LUA)
    if with_skyborne and os.path.exists(SKYBORNE):
        lua.execute(open(SKYBORNE).read())
    g = lua.globals()
    label = "with the Skyborne's screen too" if with_skyborne else "on the game's screen"
    check(g.MAX_RACES >= len(races), f"{label}: MAX_RACES {g.MAX_RACES} for {len(races)} races")
    check(all(g["CharacterCreateRaceButton%d" % i] is not None for i in range(1, len(races) + 1)), "a button for every race")
    check(g.CharacterCreateRaceButton12.GetParent(g.CharacterCreateRaceButton12).name == "CharacterCreateConfigurationFrame", "the extra buttons sit with the game's")
    args = ", ".join(['"x", "y", 1'] * len(races))
    lua.execute(f"CharacterCreateEnumerateRaces({args})")
    columns = {"Alliance": [], "Horde": []}
    for i, r in enumerate(races, 1):
        side = "Alliance" if r.startswith("A") else "Horde"
        pts = list(g["CharacterCreateRaceButton%d" % i].points.values())
        columns[side].append(pts)
    for side, pts in columns.items():
        points = [p[0] for p in pts]
        check(all(pt[1] == "TOP" for pt in points), f"{side}'s races are placed from the top of the screen")
        check(all((pt[4] < 0) == (side == "Alliance") for pt in points), f"{side}'s races stay on its side")
        two = len(points) > 5
        rows = [points[i:i + (2 if two else 1)] for i in range(0, len(points), 2 if two else 1)]
        check(all(len({pt[5] for pt in row}) == 1 for row in rows), f"{side}'s {'two races to a row' if two else 'one race to a row'} share the row's height")
        check(all(rows[i][0][5] > rows[i + 1][0][5] for i in range(len(rows) - 1)), f"{side}'s rows go down the screen")
        check(not two or all(len(row) < 2 or row[0][4] < row[1][4] for row in rows), f"{side}'s rows read left to right")
    tc = g.RACE_ICON_TCOORDS
    check(tc["VOIDELF_MALE"][1] == 0.875 and tc["VOIDELF_MALE"][4] == 0.25 and tc["NIGHTBORNE_FEMALE"][3] == 0.75, "the icons in the last column, by side and sex")
    check(tc["LIGHTFORGEDDRAENEI_MALE"][1] == 0.625 and tc["LIGHTFORGEDDRAENEI_MALE"][2] == 0.75 and tc["ZANDALARITROLL_FEMALE"][3] == 0.75, "the Lightforged's and Zandalari's icons in the next column")
    lua.execute('SetBackgroundModel("m", "VoidElf")')
    check(g.BACKGROUND == "Draenei", "the void elves' background is the Draenei's")
    lua.execute('SetBackgroundModel("m", "Nightborne")')
    check(g.BACKGROUND == "NightElf", "the nightborne's background is the night elves' (its camera frames their height)")
    lua.execute('SetBackgroundModel("m", "LightforgedDraenei")')
    check(g.BACKGROUND == "Draenei", "the Lightforged Draenei's background is the Draenei's")
    lua.execute('SetBackgroundModel("m", "ZandalariTroll")')
    check(g.BACKGROUND == "Tauren", "the Zandalari's background is the Tauren's")
    lua.execute('SetBackgroundModel("m", "Orc")')
    check(g.BACKGROUND == "Orc", "the game's own backgrounds stay")
    check(g.RACE_INFO_VOIDELF and g.ABILITY_INFO_NIGHTBORNE4 and g.VOIDELF_DISABLED, "the races' texts")
    check(g.RACE_INFO_LIGHTFORGEDDRAENEI and g.ABILITY_INFO_ZANDALARITROLL4 and g.ZANDALARITROLL_DISABLED, "the Lightforged's and Zandalari's texts")

print("PASSED" if not failures else f"FAILED ({failures})")
sys.exit(1 if failures else 0)
