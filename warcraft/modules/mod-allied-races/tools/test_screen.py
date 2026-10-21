#!/usr/bin/env python3
"""The races' character screen code (data/client/Interface/GlueXML/AlliedRacesCreate.lua) run in
Lua 5.1 against stand-ins for the game's screen: the extra buttons get the screen's names, each
side's races in two columns, two to a row, the backgrounds and icons are theirs; and with the
Skyborne's screen code loaded after it (as in a client with both patches), both sides' races still
line up.
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
GlueAmbienceTracks = { DRAENEI = "draenei", NIGHTELF = "nightelf", HUMAN = "human", TAUREN = "tauren", ORC = "orc", CHARACTERSELECT = "select" }
function PlayGlueAmbience(sound, fade)
  assert(type(sound) == "string" and sound ~= "", "missing ambience")
  AMBIENCE = sound
end
function ShowCharacter(race)
  SetBackgroundModel("m", race)
  -- Stock CharacterSelect_OnShow retains the original race token.
  PlayGlueAmbience(GlueAmbienceTracks[strupper(race)], 4.0)
end
BACKGROUND = nil
function SetBackgroundModel(model, name) BACKGROUND = name end
"""
failures = 0


def check(ok, what):
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


for native, races, with_skyborne in [
    (10, ["A Human", "A Dwarf", "A NightElf", "A Gnome", "A Draenei", "A VoidElf",
          "H Orc", "H Scourge", "H Tauren", "H Troll", "H BloodElf", "H Nightborne"], False),
    (10, ["A Human", "A Dwarf", "A NightElf", "A Gnome", "A Draenei", "A VoidElf", "A SkyborneA",
          "H Orc", "H Scourge", "H Tauren", "H Troll", "H BloodElf", "H Nightborne", "H SkyborneH"], True),
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
    args = ", ".join(f'"{r.split()[1]}", "{r.split()[1]}", 1' for r in races)
    lua.execute(f"CharacterCreateEnumerateRaces({args})")
    placed = {"Alliance": [], "Horde": []}
    for i, r in enumerate(races, 1):
        side = "Alliance" if r.startswith("A") else "Horde"
        pts = list(g["CharacterCreateRaceButton%d" % i].points.values())
        p = list(pts[-1].values())
        placed[side].append((p[0], p[1]["name"] == "CharacterCreateConfigurationFrame", p[2], p[3], p[4]))
    for side, xs in (("Alliance", (-90, -46)), ("Horde", (46, 90))):
        want = [("TOP", True, "TOP", xs[n % 2], -50 - 50 * (n // 2)) for n in range(len(placed[side]))]
        check(placed[side] == want, f"{side}'s races in two columns, two to a row ({placed[side]})")
    tc = g.RACE_ICON_TCOORDS
    check(tc["VOIDELF_MALE"][1] == 0.875 and tc["VOIDELF_MALE"][4] == 0.25 and tc["NIGHTBORNE_FEMALE"][3] == 0.75, "the icons in the last column, by side and sex")
    lua.execute('SetBackgroundModel("m", "VoidElf")')
    check(g.BACKGROUND == "Draenei", "the void elves' background is the Draenei's")
    lua.execute('SetBackgroundModel("m", "Nightborne")')
    check(g.BACKGROUND == "NightElf", "the nightborne's background is the Night Elves' (framed for their height)")
    lua.execute('SetBackgroundModel("m", "Orc")')
    check(g.BACKGROUND == "Orc", "the game's own backgrounds stay")
    for _ in range(20):
        for race, track in [("Nightborne", "nightelf"), ("Orc", "orc"), ("VoidElf", "draenei")]:
            g.ShowCharacter(race)
            assert g.AMBIENCE == track, f"{race}: missing selection ambience"
    check(True, "60 custom/native race transitions retain valid selection ambience")
    if with_skyborne:
        for race, track in [("SkyborneA", "human"), ("SkyborneH", "tauren")]:
            g.ShowCharacter(race)
            check(g.AMBIENCE == track, f"{race}: selection ambience")
    check(g.RACE_INFO_VOIDELF and g.ABILITY_INFO_NIGHTBORNE4 and g.VOIDELF_DISABLED, "the races' texts")

print("PASSED" if not failures else f"FAILED ({failures})")
sys.exit(1 if failures else 0)
