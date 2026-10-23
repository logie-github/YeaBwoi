#!/usr/bin/env python3
"""The Features row (data/client/Interface/GlueXML/SkyborneFeatures.xml and .lua, its data, and the rows
tools/build_data.py makes for it): the Lua run in Lua 5.1 against stand-ins for the game's character
creation screen (shown for a female of a race that has designs and hidden for a male or a race without;
its arrows step the skin colour by the race's skin count, the Skyborne's too; it follows the race and sex
picked), and the data checked against each other (every design has the race's skins again as a block of
colours after the others, with the same faces, a pelvis texture for the design, and colours that fit a
byte; the designs' files are there for each race). Needs lupa (pip)."""
import os, re, struct, sys, collections, xml.etree.ElementTree as ET
from lupa import lua51

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.join(HERE, "..")
GLUE = os.path.join(MODULE, "data", "client", "Interface", "GlueXML")
DATA = open(os.path.join(GLUE, "SkyborneFeaturesData.lua")).read()
LUA = open(os.path.join(GLUE, "SkyborneFeatures.lua")).read()

failures = 0
def check(ok, what):
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += 0 if ok else 1

STUBS = r"""
strupper = string.upper
CYCLES, SOUNDS = {}, 0
local function Frame(name)
  local f = { name = name, scripts = {}, shown = false, hooks = {} }
  function f:GetName() return self.name end
  function f:SetScript(k, fn) self.scripts[k] = fn end
  function f:HookScript(k, fn) self.hooks[k] = fn end
  function f:Show() self.shown = true end
  function f:Hide() self.shown = false end
  function f:SetText(t) self.text = t end
  return f
end
function CreateFrame() TIMER = Frame("timer"); return TIMER end
CharacterCreate = Frame("CharacterCreate")
SkyborneFeatures = Frame("SkyborneFeatures")
SkyborneFeaturesText = Frame("SkyborneFeaturesText")
SkyborneFeaturesRightButton = Frame("SkyborneFeaturesRightButton")
SkyborneFeaturesLeftButton = Frame("SkyborneFeaturesLeftButton")
function PlaySound() SOUNDS = SOUNDS + 1 end
function CycleCharCustomization(feature, delta) CYCLES[#CYCLES + 1] = { feature, delta } end

local RACES = { {"Human","Human",1}, {"Dwarf","Dwarf",3}, {"Night Elf","NightElf",4}, {"Gnome","Gnome",7},
  {"Draenei","Draenei",11}, {"SkyborneA","SkyborneA",13}, {"Orc","Orc",2}, {"Undead","Scourge",5},
  {"Tauren","Tauren",6}, {"Troll","Troll",8}, {"Blood Elf","BloodElf",10}, {"SkyborneH","SkyborneH",14},
  {"Void Elf","VoidElf",15}, {"Nightborne","Nightborne",16} }
SELECTED = { race = 1, sex = 3 }
SEX_FEMALE = 3
function GetSelectedRace() return SELECTED.race end
function GetSelectedSex() return SELECTED.sex end
function CharacterCreateEnumerateRaces() end
function CharacterCreateRaceButton_OnClick() end
function CharacterCreateGenderButton_OnClick() end
function ENUMERATE()
  local out = {}
  -- (as 3.3.5 gives it: name, file and whether the race can be made, 1, never its id)
  for _, e in ipairs(RACES) do out[#out + 1] = e[1]; out[#out + 1] = e[2]; out[#out + 1] = 1 end
  CharacterCreateEnumerateRaces(unpack(out))
end
function PICK(race, sex)
  SELECTED.race = race; SELECTED.sex = sex
  CharacterCreateRaceButton_OnClick()
end
"""

def start():
    lua = lua51.LuaRuntime(unpack_returned_tuples=True)
    lua.execute(STUBS)
    lua.execute(DATA)
    lua.execute("ENUMERATE()")
    lua.execute(LUA)
    lua.execute("ENUMERATE()")
    return lua

def shown(lua): return bool(lua.eval("SkyborneFeatures.shown"))
def cycles(lua): return [(int(c[1]), int(c[2])) for c in lua.eval("(function() local t = {} for i, c in ipairs(CYCLES) do t[i] = {c[1], c[2]} end return t end)()").values()]
def click(lua, which): lua.execute("SkyborneFeatures%sButton.scripts.OnClick()" % which)

# --- the row, on the screen
lua = start()
check(lua.eval("SkyborneFeaturesText.text") == "Features", "the row is called Features")
check(shown(lua), "a female Human has the row")
click(lua, "Right"); click(lua, "Left")
check(cycles(lua) == [(1, 10), (1, -10)], "its arrows step the skin colour by the Human's ten skins: %s" % cycles(lua))
check(int(lua.eval("SOUNDS")) == 2, "each click makes the creation screen's sound")

lua.execute("PICK(1, 2)")
check(not shown(lua), "a male has none")
lua.execute("CYCLES = {}"); click(lua, "Right")
check(cycles(lua) == [], "a hidden row's arrows do nothing")

lua.execute("PICK(9, 3)")
check(not shown(lua), "a female Tauren has none (no designs for her)")

for position, name, skins in ((6, "SkyborneA", 16), (12, "SkyborneH", 16), (11, "Blood Elf", 11), (1, "Human", 10)):
    lua.execute("PICK(%d, 3); CYCLES = {}" % position)
    click(lua, "Right")
    check(shown(lua) and cycles(lua) == [(1, skins)], "%s: the row steps %d" % (name, skins))

# the void elves and the nightborne have no designs: no row for their women (the race is the file's,
# not the list's third value, which is 1 for every race that can be made)
for position, name in ((13, "Void Elf"), (14, "Nightborne")):
    lua.execute("PICK(%d, 3); CYCLES = {}" % position)
    check(not shown(lua), "%s woman: no Features row" % name)

# a screen whose getter gives the name, file and id instead of a position
lua2 = lua51.LuaRuntime(unpack_returned_tuples=True)
lua2.execute(STUBS.replace("function GetSelectedRace() return SELECTED.race end", 'function GetSelectedRace() return "Human", "Orc", 2 end'))
lua2.execute(DATA); lua2.execute(LUA)
click(lua2, "Right")
check(cycles(lua2) == [(1, 9)], "a getter that gives (name, file, id) is read by its file: %s" % cycles(lua2))

# without the data the file does nothing, and breaks nothing
lua3 = lua51.LuaRuntime(unpack_returned_tuples=True)
lua3.execute(STUBS); lua3.execute(LUA)
check(not shown(lua3), "without SkyborneFeaturesData.lua the row stays hidden")

# the XML
root = ET.parse(os.path.join(GLUE, "SkyborneFeatures.xml")).getroot()
frame = root.find("{http://www.blizzard.com/wow/ui/}Frame")
check(frame is not None and frame.get("name") == "SkyborneFeatures" and frame.get("inherits") == "CharacterCustomizationFrameTemplate"
      and frame.get("parent") == "CharacterCreateConfigurationFrame" and frame.get("hidden") == "true", "the frame is the game's row template, under the configuration frame, hidden")
anchor = frame.find(".//{http://www.blizzard.com/wow/ui/}Anchor")
check(anchor.get("relativeTo") == "CharacterCustomizationButtonFrame1" and anchor.get("point") == "BOTTOM" and anchor.get("relativePoint") == "TOP",
      "it sits on top of the skin colour's row")

# --- the data against each other
lua4 = lua51.LuaRuntime(); lua4.execute(DATA)
skins = {int(k): int(v) for k, v in lua4.eval("SKYBORNE_FEATURES.skins").items()}
names = {int(k): v for k, v in lua4.eval("SKYBORNE_FEATURES.names").items()}
designs = len(names) - 1
rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(MODULE, "data", "client", "dbc", "CharSections.txt")) if l.startswith("put")]
rows = [[int(x) if re.fullmatch(r"-?\d+", x) else x for x in r[1:]] for r in rows]
feature = [r for r in rows if r[0] >= 970000]
check(designs == 12 and names[0] == "None", "twelve designs after none: %d" % designs)
check(feature and max(r[0] for r in feature) <= 999999, "the rows' ids stay in 970000-999999 (%d rows)" % len(feature))
check(len({r[0] for r in feature}) == len(feature), "every row has its own id")
files = {}
for line in open(os.path.join(MODULE, "data", "client", "features", "features.txt")):
    m = re.match(r"\[(\w+)\]", line)
    if m:
        files[m.group(1)] = True
bodies = {1: "Human", 2: "Orc", 3: "Dwarf", 4: "NightElf", 5: "Scourge", 7: "Gnome", 8: "Troll", 10: "BloodElf", 11: "Draenei", 13: "BloodElf", 14: "BloodElf"}
check(set(skins) == set(bodies), "the races with designs are the races with a body file: %s" % sorted(skins))
check(all(bodies[r] in files for r in skins), "every race with designs has its designs' file (features.txt)")
for race, s in sorted(skins.items()):
    mine = [r for r in feature if r[1] == race]
    check(all(r[2] == 1 for r in mine), "race %d: females only" % race)
    own = sorted(r[9] for r in mine if r[3] == 0)
    top = None
    colors = []
    # the race's own colours: every colour of its non-feature skin rows (the game's own are not in this repo,
    # so the base block is taken from the pattern: the first block's colours are top+1+i)
    check(len(own) == designs * s, "race %d: %d designs of %d skins = %d skin rows (%d)" % (race, designs, s, designs * s, len(own)))
    start = own[0]
    check(own == [start + i for i in range(designs * s)], "race %d: the designs' colours follow each other from %d" % (race, start))
    check(start + designs * s - 1 <= 255, "race %d: the last colour (%d) fits the byte the character keeps it in" % (race, start + designs * s - 1))
    pelvis = sorted(r for r in mine if r[3] == 4)
    path = lambda r: r[4].replace("\\\\", "\\")          # (the table file doubles each backslash)
    check(len(pelvis) == designs * s and all(path(r).endswith("FemaleFeature%02d.blp" % (((r[9] - start) // s) + 1)) and path(r).startswith("Character\\%s\\Female\\" % bodies[race])
                                              for r in pelvis), "race %d: each design's colours have its pelvis texture" % race)
    faces = collections.defaultdict(list)
    for r in mine:
        if r[3] == 1:
            faces[r[9]].append(r[8])
    per_block = [sorted(faces[start + i]) for i in range(s)]
    check(all(per_block[i] == sorted(faces[start + d * s + i]) for d in range(designs) for i in range(s)), "race %d: every design's skin has the first block's faces" % race)
    check(all(per_block), "race %d: every skin has faces" % race)

# the files themselves
for race in sorted(set(bodies.values())):
    d = open(os.path.join(MODULE, "data", "client", "features", race + ".bin"), "rb").read()
    magic, ver, pw, ph, x0, y0, w, h, n = struct.unpack_from("<4sIIIIIIII", d, 0)
    check(magic == b"FEAT" and ver == 1 and n == designs and x0 + w <= pw and y0 + h <= ph and (pw, ph) == (256, 128), "%s.bin: %d designs in a %dx%d box of a %dx%d pelvis" % (race, n, w, h, pw, ph))

print("%d failure(s)" % failures)
sys.exit(1 if failures else 0)
