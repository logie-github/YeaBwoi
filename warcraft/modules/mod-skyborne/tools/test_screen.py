#!/usr/bin/env python3
"""The Skyborne's character screen code (data/client/Interface/GlueXML/SkyborneCreate.lua) run in
Lua 5.1 against stand-ins for the game's screen: the extra buttons get the screen's names, each
side's races two to a row down its column, the backgrounds and icons are theirs. Needs lupa (pip)."""
import os, sys
from lupa import lua51

HERE = os.path.dirname(os.path.abspath(__file__))
LUA = open(os.path.join(HERE, "..", "data", "client", "Interface", "GlueXML", "SkyborneCreate.lua")).read()
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

for native, races in [
    (10, ["A Human", "A Dwarf", "A NightElf", "A Gnome", "A Draenei", "A SkyborneA",
          "H Orc", "H Scourge", "H Tauren", "H Troll", "H BloodElf", "H SkyborneH"]),
    (12, ["A Human", "A Dwarf", "A NightElf", "A Gnome", "A Draenei", "A Worgen", "A SkyborneA",
          "H Orc", "H Scourge", "H Tauren", "H Troll", "H BloodElf", "H Goblin", "H SkyborneH"]),
]:
    lua = lua51.LuaRuntime(unpack_returned_tuples=True)
    lua.execute(f"NATIVE = {native}")
    lua.execute(STUBS)
    for i, r in enumerate(races, 1):
        side, name = r.split()
        lua.execute(f'RACES[{i}] = {{ "{name}", "{"Alliance" if side == "A" else "Horde"}" }}')
    lua.execute(LUA)
    g = lua.globals()
    check(g.MAX_RACES >= len(races), f"{native} buttons in the game's screen: MAX_RACES {g.MAX_RACES} for {len(races)} races")
    check(all(g["CharacterCreateRaceButton%d" % i] is not None for i in range(1, len(races) + 1)), "a button for every race")
    check(g.CharacterCreateRaceButton13.GetParent(g.CharacterCreateRaceButton13).name == "CharacterCreateConfigurationFrame", "the extra buttons sit with the game's")
    args = ", ".join(['"x", "y", 1'] * len(races))
    lua.execute(f"CharacterCreateEnumerateRaces({args})")
    placed = {"A": [], "H": []}
    for i, r in enumerate(races, 1):
        pts = list(g["CharacterCreateRaceButton%d" % i].points.values())
        p = list(pts[0].values())
        if p[0] == "TOP":
            placed[r.split()[0]].append((p[3], p[4]))        # x and y from the screen's top
    for side, centre in (("A", -50), ("H", 50)):
        mine = placed[side]
        check(len(mine) == sum(1 for r in races if r.startswith(side)), f"every {side} race is placed from the top")
        # more than five races a side: two to a row, centred on the side's old column, rows going down
        rows = [mine[i:i + 2] for i in range(0, len(mine), 2)]
        check(all(abs((a[0] + b[0]) / 2 - centre) < 1e-6 and a[1] == b[1] and a[0] < b[0] for a, b in (r for r in rows if len(r) == 2)),
              f"{side}: two races a row around {centre}: {mine}")
        check(all(rows[i][0][1] > rows[i + 1][0][1] for i in range(len(rows) - 1)), f"{side}: the rows go down")
    lua.execute('SetBackgroundModel(nil, "SkyborneA")')
    check(g.BACKGROUND == "Human", "the High Order's background is the Humans'")
    lua.execute('SetBackgroundModel(nil, "SKYBORNEH")')
    check(g.BACKGROUND == "Tauren", "the Windshapers' is the Tauren's")
    lua.execute('SetBackgroundModel(nil, "Orc")')
    check(g.BACKGROUND == "Orc", "the others' stay")
    check(g.RACE_ICON_TCOORDS["SKYBORNEH_FEMALE"] is not None and g.RACE_INFO_SKYBORNEA and g.ABILITY_INFO_SKYBORNEH2, "icons and text")
print("all passed" if not failures else f"{failures} failed")
sys.exit(1 if failures else 0)
