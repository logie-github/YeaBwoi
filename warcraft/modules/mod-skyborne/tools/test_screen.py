#!/usr/bin/env python3
"""The Skyborne's character screen code (data/client/Interface/GlueXML/SkyborneCreate.lua) run in
Lua 5.1 against stand-ins for the game's screen: the extra buttons get the screen's names, each
side's races go down its column, the backgrounds and icons are theirs. Needs lupa (pip)."""
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
    tops = []
    for i, r in enumerate(races, 1):
        pts = list(g["CharacterCreateRaceButton%d" % i].points.values())
        p = list(pts[0].values())
        if p[0] == "TOP":
            tops.append((r.split()[0], p[3]))
    check(sorted(tops) == [("A", -50), ("H", 50)], f"one column a side, the Alliance's left: {tops}")
    lua.execute('SetBackgroundModel(nil, "SkyborneA")')
    check(g.BACKGROUND == "Human", "the High Order's background is the Humans'")
    lua.execute('SetBackgroundModel(nil, "SKYBORNEH")')
    check(g.BACKGROUND == "Tauren", "the Windshapers' is the Tauren's")
    lua.execute('SetBackgroundModel(nil, "Orc")')
    check(g.BACKGROUND == "Orc", "the others' stay")
    check(g.RACE_ICON_TCOORDS["SKYBORNEH_FEMALE"] is not None and g.RACE_INFO_SKYBORNEA and g.ABILITY_INFO_SKYBORNEH2, "icons and text")
print("all passed" if not failures else f"{failures} failed")
sys.exit(1 if failures else 0)
