#!/usr/bin/env python3
"""The gear button's code (data/client/Interface/GlueXML/SkyborneGear.lua and its data) run in Lua 5.1
against stand-ins for the game's character creation screen: the button cycles no gear, starting gear
and champion gear, its icon is the mode's, the preview is dressed through the model's Undress and
TryOn with the race and class's items, dressed again when a race or class is picked, its tooltip
through the creation screen's own (GlueTooltip_SetOwner and GlueTooltip_SetText, functions in 3.3.5:
the tooltip frame has no SetOwner method here, as in the game), it sits between the sex buttons (moved
apart for it), and on a screen whose model can't be dressed (3.3.5's) it still shows and cycles, its
tooltip says the preview can't be dressed, and nothing breaks. Needs lupa (pip)."""
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
  function f:GetPoint(i) local p = self.points[i or 1]; if p then return unpack(p) end end
  function f:GetParent() return self.parent end
  function f:SetNormalTexture(t) self.texture = t end
  function f:Show() self.shown = true end
  function f:Hide() self.shown = false end
  return f
end
function CreateFrame() LAST_FRAME = Frame("timer"); return LAST_FRAME end
CharacterCreateConfigurationFrame = Frame("CharacterCreateConfigurationFrame")
-- the sex buttons as 3.3.5's XML puts them: TOP of the panel, 20 either side of its middle
CharacterCreateGenderButtonMale = Frame("CharacterCreateGenderButtonMale")
CharacterCreateGenderButtonMale.points = { { "TOP", CharacterCreateConfigurationFrame, "TOP", -20, -392 } }
CharacterCreateGenderButtonFemale = Frame("CharacterCreateGenderButtonFemale")
CharacterCreateGenderButtonFemale.points = { { "TOP", CharacterCreateConfigurationFrame, "TOP", 20, -392 } }
SkyborneGearButton = Frame("SkyborneGearButton")
GlueParent = Frame("GlueParent")
MakeFrame = Frame
-- 3.3.5's tooltips on this screen: frames with no SetOwner method, set by GlueXML's functions.
GlueTooltip = Frame("GlueTooltip")
CharacterCreateTooltip = Frame("CharacterCreateTooltip")
CharacterCreateTooltip.lines = {}
function GlueTooltip_SetOwner(owner, tip, x, y) tip.owner = owner; tip.lines = {}; tip.shown = true end
function GlueTooltip_SetText(text, tip)
  for line in string.gmatch(text, "[^\n]+") do tip.lines[#tip.lines + 1] = line end
end

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
check("Interface\\Icons\\INV_Misc_Leaf_01" == str(g.SkyborneGearButton.texture), "it starts on no gear (the fig leaf)")

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
check(g2.SkyborneGearButton.shown, "the button shows on a screen whose model can't be dressed (3.3.5's)")
settle(lua2)
check(g2.SkyborneGearButton.shown, "and stays shown as the screen settles")
check(str(g2.SkyborneGearButton.texture) == "Interface\\Icons\\" + str(data.start[1][1][1]), "it still cycles there (starting gear's icon)")
lua2.execute("SkyborneGearButton.scripts.OnEnter()")
tips = [str(v) for v in lua2.globals().CharacterCreateTooltip.lines.values()]
check(tips[0] == "Starting gear" and any("can't be dressed" in t for t in tips), "its tooltip names the mode and says the preview can't be dressed (%s)" % tips)
lua2.execute("SkyborneGearButton.scripts.OnLeave()")
check(not lua2.globals().CharacterCreateTooltip.shown, "and hides again")

# ---- placement -------------------------------------------------------------------------------
def point(lua, frame):
    pt = lua.eval("%s.points[#%s.points]" % (frame, frame))
    return [getattr(pt[i], "name", None) if i in (2,) else pt[i] for i in range(1, 6)]

lua3 = start()
gear = point(lua3, "SkyborneGearButton")
check(gear == ["TOP", "CharacterCreateConfigurationFrame", "TOP", 0, -392],
      "the button stands where the middle of the sex buttons was (%s)" % gear)
check(len(lua3.eval("SkyborneGearButton.points")) == 1, "anchored once")
male = point(lua3, "CharacterCreateGenderButtonMale")
female = point(lua3, "CharacterCreateGenderButtonFemale")
check(male == ["RIGHT", "SkyborneGearButton", "LEFT", -12, 0], "male to its left, 12 apart (centre 50 left of the middle) (%s)" % male)
check(female == ["LEFT", "SkyborneGearButton", "RIGHT", 12, 0], "female to its right, 12 apart (centre 50 right of the middle) (%s)" % female)
half = 19                                          # the buttons are 38 wide (the XML's and 3.3.5's)
check(gear[3] - half + male[3] - half == -50 and gear[3] + half + female[3] + half == 50,
      "so each sex button's centre is under its race column (SkyborneCreate.lua's, 50 either side)")
lua3.execute("SkyborneGearButton.scripts.OnEnter()")
tips = [str(v) for v in lua3.globals().CharacterCreateTooltip.lines.values()]
check(lua3.eval("CharacterCreateTooltip.owner == SkyborneGearButton"), "the creation screen's tooltip, owned by the button")
check(any("Click to change what the preview wears." in t for t in tips) and not any("can't be dressed" in t for t in tips),
      "where the model can be dressed the tooltip says the click dresses it (%s)" % tips)
check(lua3.eval("SkyborneGearButton.shown"), "the button shows where the model can be dressed")
settle(lua3)
check(point(lua3, "SkyborneGearButton") == gear and len(lua3.eval("SkyborneGearButton.points")) == 1, "settling doesn't move it")

# without the sex buttons it stands where they would be
lua4 = lua51.LuaRuntime(unpack_returned_tuples=True)
lua4.execute(STUBS)
lua4.execute("CharacterCreateGenderButtonMale = nil; CharacterCreateGenderButtonFemale = nil")
lua4.execute("MODEL = MakeModel(false); CharacterCreate = {}")
lua4.execute(DATA)
lua4.execute(LUA)
check(point(lua4, "SkyborneGearButton") == ["TOP", "CharacterCreateConfigurationFrame", "TOP", 0, -392],
      "with no sex buttons it takes their place on the panel")

# ---- the button's XML -----------------------------------------------------------------------
check('name="SkyborneGearButton"' in XML and 'parent="CharacterCreateConfigurationFrame"' in XML, "the XML makes the button on the creation screen")
check("UI-Quickslot" not in XML and "<Layers>" not in XML, "no frame drawn round its icon")
check(":SetOwner" not in LUA and "Tooltip:AddLine" not in LUA, "no tooltip method calls (3.3.5's tooltips here have none)")

print("%d failure(s)" % failures)
sys.exit(1 if failures else 0)
