#!/usr/bin/env python3
"""The gear button's code (data/client/Interface/GlueXML/SkyborneGear.lua and its data) run in Lua 5.1
against stand-ins for the game's character creation screen: the button cycles no gear, starting gear
and champion gear, its icon is the mode's, the preview is dressed through the model's Undress and
TryOn with the race and class's items, dressed again when a race or class is picked, and a screen
whose model can't be dressed is told so in the tooltip and nothing breaks. Needs lupa (pip)."""
import os, sys
from lupa import lua51

HERE = os.path.dirname(os.path.abspath(__file__))
GLUE = os.path.join(HERE, "..", "data", "client", "Interface", "GlueXML")
DATA = open(os.path.join(GLUE, "SkyborneGearData.lua")).read()
LUA = open(os.path.join(GLUE, "SkyborneGear.lua")).read()
XML = open(os.path.join(GLUE, "SkyborneGear.xml")).read()

STUBS = r"""
strupper = string.upper
LOG = {}
local function Frame(name)
  local f = { name = name, scripts = {}, points = {}, kids = {}, scale = 1, shown = true, kind = "Frame" }
  function f:GetName() return self.name end
  function f:GetObjectType() return self.kind end
  function f:GetChildren() return unpack(self.kids) end
  function f:IsVisible() return self.shown end
  function f:GetEffectiveScale() return self.scale end
  function f:GetWidth() return 38 end
  function f:SetParent(p) self.parent = p end
  function f:GetLeft() return self.rect and self.rect[1] end
  function f:GetRight() return self.rect and self.rect[2] end
  function f:GetBottom() return self.rect and self.rect[3] end
  function f:GetTop() return self.rect and self.rect[4] end
  function f:SetScript(k, fn) self.scripts[k] = fn end
  function f:ClearAllPoints() self.points = {} end
  function f:SetPoint(...) self.points[#self.points + 1] = { ... } end
  function f:SetNormalTexture(t) self.texture = t end
  return f
end
function CreateFrame() LAST_FRAME = Frame("timer"); return LAST_FRAME end
CharacterCreateConfigurationFrame = Frame("CharacterCreateConfigurationFrame")
CharacterCreateGenderButtonFemale = Frame("CharacterCreateGenderButtonFemale")
SkyborneGearButton = Frame("SkyborneGearButton")
GlueTooltip = {}
GlueParent = Frame("GlueParent")
MakeFrame = Frame
function GlueTooltip:SetOwner() self.lines = {} end
function GlueTooltip:SetText(t) self.lines[#self.lines + 1] = t end
function GlueTooltip:AddLine(t) self.lines[#self.lines + 1] = t end
function GlueTooltip:Show() self.shown = true end
function GlueTooltip:Hide() self.shown = false end

-- The screen: ten races and ten classes in lists, the selection by position.
local RACES = { {"Human","Human",1}, {"Dwarf","Dwarf",3}, {"Night Elf","NightElf",4}, {"Gnome","Gnome",7},
  {"Draenei","Draenei",11}, {"SkyborneA","SkyborneA",13}, {"Orc","Orc",2}, {"Undead","Scourge",5},
  {"Tauren","Tauren",6}, {"Troll","Troll",8}, {"Blood Elf","BloodElf",10}, {"SkyborneH","SkyborneH",14} }
local CLASSES = { {"Warrior","WARRIOR",1}, {"Paladin","PALADIN",2}, {"Hunter","HUNTER",3}, {"Rogue","ROGUE",4},
  {"Priest","PRIEST",5}, {"Death Knight","DEATHKNIGHT",6}, {"Shaman","SHAMAN",7}, {"Mage","MAGE",8},
  {"Warlock","WARLOCK",9}, {"Druid","DRUID",11} }
SELECTED = { race = 1, class = 1, sex = 2 }
SEX_FEMALE = 3
function GetSelectedRace() return SELECTED.race end
function GetSelectedClass() return SELECTED.class end
function GetSelectedSex() return SELECTED.sex end
local function flat(list)
  local out = {}
  for _, e in ipairs(list) do out[#out + 1] = e[1]; out[#out + 1] = e[2]; out[#out + 1] = e[3] end
  return unpack(out)
end
function CharacterCreateEnumerateRaces() end
function CharacterCreateEnumerateClasses() end
function CharacterCreateRaceButton_OnClick() end
function CharacterCreateClassButton_OnClick() end
function ENUMERATE() CharacterCreateEnumerateRaces(flat(RACES)); CharacterCreateEnumerateClasses(flat(CLASSES)) end

function MakeModel(dressable)
  local m = { worn = {}, calls = {} }
  if dressable then
    function m:Undress() self.worn = {}; self.calls[#self.calls + 1] = "undress" end
    function m:TryOn(id) self.worn[#self.worn + 1] = id; self.calls[#self.calls + 1] = "tryon" end
  end
  return m
end
"""
failures = 0
def check(ok, what):
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += 0 if ok else 1

def start(dressable=True, with_child=True):
    lua = lua51.LuaRuntime(unpack_returned_tuples=True)
    lua.execute(STUBS)
    lua.execute("MODEL = MakeModel(%s)" % ("true" if dressable else "false"))
    lua.execute("CharacterCreate = { GetChildren = function() return MODEL end }" if with_child else "CharacterCreate = {}")
    lua.execute("ENUMERATE_BEFORE = true")
    lua.execute(DATA)
    lua.execute(LUA)
    lua.execute("ENUMERATE()")
    return lua

def worn(lua):
    return [int(v) for v in lua.eval("(function() local t = {} for i, v in ipairs(MODEL.worn) do t[i] = v end return t end)()").values()]

def click(lua):
    lua.execute("SkyborneGearButton.scripts.OnClick()")

def settle(lua):
    """The screen's timer frame (the last CreateFrame made) runs its OnUpdate for two seconds."""
    lua.execute("for i = 1, 20 do LAST_FRAME.scripts.OnUpdate(LAST_FRAME, 0.1) end")

# ---- the data -------------------------------------------------------------------------------
lua = start()
g = lua.globals()
data = g.SKYBORNE_GEAR
check(data.nude == "INV_Misc_Leaf_01", "the no-gear icon is a leaf")
classes = [1, 2, 3, 4, 5, 6, 7, 8, 9, 11]
for c in classes:
    champ = data.champion[c]
    check(champ is not None and len(champ[3]) == 5 and champ[1], "class %d has a champion set of five pieces and an icon" % c)
check(all(data.fallback[c][1] for c in classes), "every class has a fallback start with an icon")
for race in (1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 13, 14):
    check(data.start[race] is not None, "race %d has starts" % race)
check(data.start[13][1] is not None and data.start[14][11] is not None, "the Skyborne's classes have starts (Alliance warrior, Horde druid)")

# ---- cycling --------------------------------------------------------------------------------
check("Interface\\Icons\\INV_Misc_Leaf_01" == str(g.SkyborneGearButton.texture), "it starts on no gear (a leaf icon)")
check(len(g.SkyborneGearButton.points) == 1, "the button is placed")

click(lua)
human_warrior = [38, 39, 40, 49778]
check(worn(lua) == human_warrior, "starter gear: the Human warrior's outfit is on the model (%s)" % worn(lua))
check(str(g.SkyborneGearButton.texture) == "Interface\\Icons\\" + str(data.start[1][1][1]), "starter gear's icon is the outfit's chest")
click(lua)
champ = [int(v) for v in data.champion[1][3].values()]
check(worn(lua) == champ, "champion gear: the warrior's raid set is on the model (%s)" % worn(lua))
check(str(g.SkyborneGearButton.texture) == "Interface\\Icons\\" + str(data.champion[1][1]), "champion gear's icon is the set's chest piece")
click(lua)
check(worn(lua) == [] and str(g.SkyborneGearButton.texture).endswith("INV_Misc_Leaf_01"), "a third click is back to no gear")

# ---- race and class changes -----------------------------------------------------------------
click(lua)                                         # starter gear
lua.execute("SELECTED.class = 8")                  # a mage
lua.execute("CharacterCreateClassButton_OnClick()")
check(str(g.SkyborneGearButton.texture) == "Interface\\Icons\\" + str(data.start[1][8][1]), "picking a class changes the icon at once")
settle(lua)
mage = [int(v) for v in data.start[1][8][2].values()]
check(worn(lua) == mage, "and dresses the model again a moment later (%s)" % worn(lua))
lua.execute("SELECTED.race = 6; SELECTED.class = 1")   # position 6: the High Order (race 13), a warrior
lua.execute("CharacterCreateRaceButton_OnClick()")
settle(lua)
sky = [int(v) for v in data.start[13][1][2].values()]
check(worn(lua) == sky, "a Skyborne warrior is dressed in the Skyborne's start (%s)" % worn(lua))
click(lua)                                         # champion
lua.execute("SELECTED.class = 10")                 # a different class: Druid is position 10 (id 11)
settle(lua)
lua.execute("CharacterCreateClassButton_OnClick()")
settle(lua)
druid = [int(v) for v in data.champion[11][3].values()]
check(worn(lua) == druid, "champion gear follows the class (the druid's set: %s)" % worn(lua))

# ---- no dressable model ---------------------------------------------------------------------
lua2 = start(dressable=False)
g2 = lua2.globals()
click(lua2)
check(worn(lua2) == [], "a model that can't be dressed is left alone")
lua2.execute("SkyborneGearButton.scripts.OnEnter()")
lines = [str(v) for v in g2.GlueTooltip.lines.values()]
check(any("can't be dressed" in l for l in lines), "its tooltip says the model can't be dressed (%s)" % lines[-1])
check(str(g2.SkyborneGearButton.texture).startswith("Interface\\Icons\\"), "the icon still cycles")

# ---- placement -------------------------------------------------------------------------------
LAYOUT = r"""
local function Box(kind, name, l, r, b, t, parent)
  local f = MakeFrame(name); f.kind = kind; f.rect = { l, r, b, t }
  if parent then parent.kids[#parent.kids + 1] = f end
  return f
end
SCREEN = MakeFrame("CharacterCreate"); SCREEN.rect = { 0, 1920, 0, 1080 }
CharacterCreate = SCREEN
local config = Box("Frame", "ConfigPanel", 20, 300, 100, 900, SCREEN)           -- left: the races, classes, sexes
for i = 1, 12 do Box("Button", "RaceButton"..i, 30, 70, 900 - 45 * i, 940 - 45 * i, config) end
Box("EditBox", "NameBox", 700, 1000, 30, 60, SCREEN)                              -- the name box
local custom = Box("Frame", "CustomizePanel", 1400, 1700, 120, 700, SCREEN)       -- right: the customize panel
LOWEST = nil
for row = 1, 5 do
  local bottom = 640 - 100 * row
  Box("Button", "Left"..row, 1410, 1440, bottom, bottom + 30, custom)
  Box("Button", "Right"..row, 1660, 1690, bottom, bottom + 30, custom)
end
Box("Button", "Randomize", 1450, 1650, 125, 150, SCREEN)                          -- not in the panel
"""

def placed(scale=1.0):
    lua = start()
    lua.execute(LAYOUT)
    lua.execute("SkyborneGearButton.scale = %s" % scale)
    lua.execute("for i = 1, 20 do LAST_FRAME.scripts.OnUpdate(LAST_FRAME, 0.1) end")
    return lua

lua3 = placed()
pt = lua3.eval("SkyborneGearButton.points[#SkyborneGearButton.points]")
vals = [str(pt[i]) if i != 2 else getattr(pt[i], "name", "?") for i in range(1, 6)]
# between the name box (right edge 1000) and the customize panel (left edge 1400), minus half the button's width
check(vals[0] == "BOTTOMLEFT" and vals[1] == "GlueParent" and float(vals[3]) == 1200 - 19,
      "halfway between the name box and the customize panel (x %s)" % vals[3])
# level with the lowest arrow button (row 5: 640 - 500 = 140), not the panel's bottom (120) or the randomize button's (125)
check(float(vals[4]) == 140, "its bottom level with the lowest arrow button (y %s)" % vals[4])
check(lua3.eval("SkyborneGearButton.parent == CharacterCreate"), "it is on the creation screen")
lua3.execute("SkyborneGearButton.scripts.OnEnter()")
tips = [str(v) for v in lua3.globals().GlueTooltip.lines.values()]
check(any("Placed between NameBox and CustomizePanel" in t for t in tips), "its tooltip says what it was placed by (%s)" % [t for t in tips if "Placed" in t])

lua4 = placed(scale=0.5)
pt = lua4.eval("SkyborneGearButton.points[#SkyborneGearButton.points]")
check(float(pt[4]) == (1200 - 19 / 2) / 0.5, "a scaled button's x is in its own units too (x %s)" % pt[4])
check(float(pt[5]) == 140 / 0.5, "a scaled button's offsets are in its own units (y %s)" % pt[5])

# without a name box or a panel the button stays where it was
lua5 = start()
before = len(lua5.eval("SkyborneGearButton.points"))
lua5.execute("for i = 1, 20 do LAST_FRAME.scripts.OnUpdate(LAST_FRAME, 0.1) end")
check(len(lua5.eval("SkyborneGearButton.points")) == before, "with no name box found it keeps its first place")
lua5.execute("SkyborneGearButton.scripts.OnEnter()")
tips = [str(v) for v in lua5.globals().GlueTooltip.lines.values()]
check(any("no name box found" in t for t in tips), "and says so")

# ---- the button's XML -----------------------------------------------------------------------
check('name="SkyborneGearButton"' in XML and 'parent="CharacterCreateConfigurationFrame"' in XML, "the XML makes the button on the creation screen")

print("%d failure(s)" % failures)
sys.exit(1 if failures else 0)
