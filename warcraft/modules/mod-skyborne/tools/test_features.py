#!/usr/bin/env python3
"""The Bottom Features row (data/client/Interface/GlueXML/SkyborneFeatures.xml and .lua, its data, and the
rows tools/build_data.py makes for it): the Lua run in Lua 5.1 against stand-ins for the game's character
creation screen (its own calls: CharacterRace_OnClick, SetCharacterGender, CharacterClass_OnClick, the
Skin Color arrows' CharacterCustomization_Left/Right, and CycleCharCustomization over a list of colours
that wraps around). The row is shown for a female of a race with a run of colours and hidden for a male,
a race without one and a Death Knight, whose Skin Color arrows are the game's own; from every colour of
every run (S skins 5-20, K options coprime with S) the Skin Color arrows go to the next or last skin with
the same detail and the row's to the next or last detail with the same skin, the short way round, one
colour at a time. The data checked against each other: each race's run is exactly its playable colours,
colour p the skin p mod S (its textures, faces and section 4 rows) with the detail p mod K, the steps right,
colours that fit a byte; the detail's mesh and art there for each race. Needs lupa (pip)."""
import os, re, struct, sys, zlib, math, collections, xml.etree.ElementTree as ET
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
POS, COLOURS = 0, nil       -- the colour shown, and how many the list has (the game wraps it around)
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
function CycleCharCustomization(feature, delta)
  CYCLES[#CYCLES + 1] = { feature, delta }
  if ( feature == 1 and COLOURS ) then POS = (POS + delta) % COLOURS end
end
-- the game's own (CharacterCreate.lua): the rows' arrows (their buttons call these by name, with the
-- row's id), and what the race, class and sex buttons call
function CharacterCustomization_Left(id) PlaySound("gsCharacterCreationLook"); CycleCharCustomization(id, -1) end
function CharacterCustomization_Right(id) PlaySound("gsCharacterCreationLook"); CycleCharCustomization(id, 1) end
function CharacterRace_OnClick(self, id) end
function CharacterClass_OnClick(id) end
function SetCharacterGender(sex) end
function CharacterCreate_UpdateModel() end
function CharacterCreate_Randomize() end

local RACES = { {"Human","Human",1}, {"Dwarf","Dwarf",3}, {"Night Elf","NightElf",4}, {"Gnome","Gnome",7},
  {"Draenei","Draenei",11}, {"SkyborneA","SkyborneA",13}, {"Orc","Orc",2}, {"Undead","Scourge",5},
  {"Tauren","Tauren",6}, {"Troll","Troll",8}, {"Blood Elf","BloodElf",10}, {"SkyborneH","SkyborneH",14},
  {"Void Elf","VoidElf",15}, {"Nightborne","Nightborne",16} }
SELECTED = { race = 1, sex = 3, class = { "Warrior", "WARRIOR", 1 } }
SEX_FEMALE = 3
function GetSelectedRace() return SELECTED.race end
function GetSelectedSex() return SELECTED.sex end
function GetSelectedClass() return unpack(SELECTED.class) end
function CharacterCreateEnumerateRaces() end
function ENUMERATE()
  local out = {}
  -- (as 3.3.5 gives it: name, file and whether the race can be made, 1, never its id)
  for _, e in ipairs(RACES) do out[#out + 1] = e[1]; out[#out + 1] = e[2]; out[#out + 1] = 1 end
  CharacterCreateEnumerateRaces(unpack(out))
end
function PICK(race, sex)
  SELECTED.race = race; SELECTED.sex = sex
  CharacterRace_OnClick({}, race)
end
function CLASS(name, file, index)
  SELECTED.class = { name, file, index }
  CharacterClass_OnClick(index)
end
function SKIN(direction)
  if ( direction > 0 ) then CharacterCustomization_Right(1) else CharacterCustomization_Left(1) end
end
function ROW(direction)
  -- (a row the file left alone stays hidden: nothing to click)
  local click = (direction > 0 and SkyborneFeaturesRightButton or SkyborneFeaturesLeftButton).scripts.OnClick
  if ( click and SkyborneFeatures.shown ) then click() end
end

-- From every colour of the selected race's run of S*K: each arrow of both rows, and where it went.
-- Gives what went wrong first (or nil) and the most single steps a click took.
function SWEEP(S, K)
  local n = S * K
  COLOURS = n
  local most = 0
  for p = 0, n - 1 do
    for _, t in ipairs({ { SKIN, 1, "Skin Color" }, { SKIN, -1, "Skin Color" }, { ROW, 1, "Bottom Features" }, { ROW, -1, "Bottom Features" } }) do
      POS, CYCLES = p, {}
      t[1](t[2])
      local s, k = p % S, p % K
      if ( t[1] == SKIN ) then s = (s + t[2]) % S else k = (k + t[2]) % K end
      if ( POS % S ~= s or POS % K ~= k ) then
        return string.format("from colour %d (skin %d, detail %d), %s %+d went to %d (skin %d, detail %d)",
          p, p % S, p % K, t[3], t[2], POS, POS % S, POS % K), most
      end
      for _, c in ipairs(CYCLES) do
        if ( c[1] ~= 1 or (c[2] ~= 1 and c[2] ~= -1) ) then
          return string.format("%s %+d stepped row %d by %d", t[3], t[2], c[1], c[2]), most
        end
      end
      most = math.max(most, #CYCLES)
    end
  end
  return nil, most
end
"""

# Race id -> position in the stand-in's list.
POSITION = {1: 1, 3: 2, 4: 3, 7: 4, 11: 5, 13: 6, 2: 7, 5: 8, 6: 9, 8: 10, 10: 11, 14: 12, 15: 13, 16: 14}
BODIES = {1: "Human", 2: "Orc", 3: "Dwarf", 4: "NightElf", 5: "Scourge", 7: "Gnome", 8: "Troll", 10: "BloodElf", 11: "Draenei",
          13: "BloodElf", 14: "BloodElf"}

def short(step, n):
    step %= n
    return step - n if step > n / 2 else step

def run(S, K):
    """The run tools/build_data.py makes for S skins and K options: n, the skin step, the style step."""
    n = S * K
    return n, short(K * pow(K, -1, S), n), short(S * pow(S, -1, K), n)

def lua_table(runs, extra=""):
    races = ", ".join("[%d] = { n = %d, skin = %d, style = %d }" % (r, n, a, c) for r, (n, a, c) in sorted(runs.items()))
    return "SKYBORNE_FEATURES = { races = { %s }%s }" % (races, extra)

# Each race's females' skins today, with None and twelve details.
SKINS = {1: 10, 2: 9, 3: 9, 4: 9, 5: 6, 7: 5, 8: 6, 10: 11, 11: 12, 13: 16, 14: 16}
TEST_RUNS = {race: run(s, 13) for race, s in SKINS.items()}
TEST_DATA = lua_table(TEST_RUNS)

def start(data=TEST_DATA, stubs=STUBS):
    lua = lua51.LuaRuntime(unpack_returned_tuples=True)
    lua.execute(stubs)
    if data is not None:
        lua.execute(data)
    lua.execute("ENUMERATE()")
    lua.execute(LUA)
    lua.execute("ENUMERATE()")
    return lua

def shown(lua): return bool(lua.eval("SkyborneFeatures.shown"))
def cycles(lua): return [(int(c[1]), int(c[2])) for c in lua.eval("(function() local t = {} for i, c in ipairs(CYCLES) do t[i] = {c[1], c[2]} end return t end)()").values()]
def clicks(lua, code):
    lua.execute("CYCLES = {}; " + code)
    got = cycles(lua)
    return got[0][0] if got and all(c[0] == got[0][0] for c in got) else None, sum(c[1] for c in got), len(got)

# --- the row, on the screen
check(run(10, 13) == (130, -39, 40), "Human's run: 10 skins of 13 options are 130 colours, Skin Color -39, Bottom Features +40: %s" % (run(10, 13),))
lua = start()
check(lua.eval("SkyborneFeaturesText.text") == "Bottom Features", "the row is called Bottom Features")
check(shown(lua), "a female Human has the row")
check(clicks(lua, "ROW(1)") == (1, 40, 40) and clicks(lua, "ROW(-1)") == (1, -40, 40),
      "its arrows step the skin colour by +40 and -40, one colour at a time")
check(clicks(lua, "SKIN(1)") == (1, -39, 39) and clicks(lua, "SKIN(-1)") == (1, 39, 39),
      "the Skin Color arrows (the game's row 1) step by -39 and +39, one colour at a time")
lua.execute("SOUNDS = 0; ROW(1); SKIN(-1)")
check(int(lua.eval("SOUNDS")) == 2, "each click makes the creation screen's sound once")
lua.execute("CYCLES = {}; CharacterCustomization_Right(2); CharacterCustomization_Left(5)")
check(cycles(lua) == [(2, 1), (5, -1)], "the other rows' arrows are the game's own")

lua.execute("PICK(1, 2)")
check(not shown(lua), "a male has none")
check(clicks(lua, "ROW(1)") == (None, 0, 0), "a hidden row's arrows do nothing")
check(clicks(lua, "SKIN(1)") == (1, 1, 1) and clicks(lua, "SKIN(-1)") == (1, -1, 1), "a male's Skin Color arrows are the game's own")

lua.execute("PICK(9, 3)")
check(not shown(lua), "a female Tauren has none (no run for her)")
check(clicks(lua, "SKIN(1)") == (1, 1, 1), "her Skin Color arrows are the game's own")

for race in (13, 14, 10, 11, 5, 7, 1):
    n, a, c = TEST_RUNS[race]
    lua.execute("PICK(%d, 3)" % POSITION[race])
    check(shown(lua) and clicks(lua, "ROW(1)") == (1, c, abs(c)) and clicks(lua, "SKIN(1)") == (1, a, abs(a)),
          "race %d (%d skins, %d colours): Bottom Features steps %+d, Skin Color %+d" % (race, SKINS[race], n, c, a))

# the void elves and the nightborne have no run: no row for their women (the race is the file's, not the
# list's third value, which is 1 for every race that can be made)
for position, name in ((13, "Void Elf"), (14, "Nightborne")):
    lua.execute("PICK(%d, 3)" % position)
    check(not shown(lua) and clicks(lua, "SKIN(1)") == (1, 1, 1), "%s woman: no Bottom Features row, the game's Skin Color" % name)

# a Death Knight's skins are outside the run: the game's arrows, no row; back for another class
lua.execute("PICK(1, 3); CLASS('Death Knight', 'DEATHKNIGHT', 6)")
check(not shown(lua), "a Death Knight has no row")
check(clicks(lua, "SKIN(1)") == (1, 1, 1) and clicks(lua, "ROW(1)") == (None, 0, 0), "a Death Knight's Skin Color arrows are the game's own")
lua.execute("CLASS('Mage', 'MAGE', 8)")
check(shown(lua) and clicks(lua, "SKIN(1)") == (1, -39, 39), "another class has them back")

# it follows the game's own calls: the race, class and sex buttons' (CharacterRace_OnClick, CharacterClass_OnClick,
# SetCharacterGender), the screen opening, the Randomize button
for change, call, name in (("SELECTED.sex = 2", "SetCharacterGender(2)", "SetCharacterGender"),
                           ("SELECTED.race = 9", "CharacterRace_OnClick({}, 9)", "CharacterRace_OnClick"),
                           ("SELECTED.class = { 'Death Knight', 'DEATHKNIGHT', 6 }", "CharacterClass_OnClick(6)", "CharacterClass_OnClick"),
                           ("SELECTED.sex = 2", "CharacterCreate.hooks.OnShow()", "the screen's OnShow"),
                           ("SELECTED.sex = 2", "CharacterCreate_Randomize()", "CharacterCreate_Randomize")):
    lua.execute("SELECTED = { race = 1, sex = 3, class = { 'Warrior', 'WARRIOR', 1 } }; CharacterRace_OnClick({}, 1)")
    before = shown(lua)
    lua.execute(change)
    lua.execute(call)
    check(before and not shown(lua), "the row follows %s" % name)

# a screen whose getter gives the name, file and id instead of a position
lua2 = start(stubs=STUBS.replace("function GetSelectedRace() return SELECTED.race end", 'function GetSelectedRace() return "Human", "Orc", 2 end'))
check(clicks(lua2, "ROW(1)") == (1, TEST_RUNS[2][2], abs(TEST_RUNS[2][2])), "a getter that gives (name, file, id) is read by its file")

# the races whose models the patch changed, where the data lists them: the row there only, Skin Color everywhere
lua3 = start(data=lua_table(TEST_RUNS, ", patched = { [1] = true }"))
check(shown(lua3), "a listed race (Human) has the row")
lua3.execute("PICK(7, 3)")
check(not shown(lua3) and clicks(lua3, "SKIN(1)") == (1, TEST_RUNS[2][1], abs(TEST_RUNS[2][1])),
      "a race with a run that isn't listed (Orc): no row, its Skin Color steps keep the detail")

# the steps are taken the short way round, whichever way the data gives them
lua4 = start(data=lua_table({1: (130, 91, 40 - 130)}))
check(clicks(lua4, "SKIN(1)") == (1, -39, 39) and clicks(lua4, "ROW(1)") == (1, 40, 40), "steps the long way round (+91, -90) go the short way (-39, +40)")

# without the data, with the old data or with a race's run broken, the arrows are the game's and the row hidden
for data, what in ((None, "without SkyborneFeaturesData.lua"), ("SKYBORNE_FEATURES = { skins = { [1] = 10 } }", "with the old data (skins only)"),
                   ("SKYBORNE_FEATURES = { races = { [1] = { n = 130, skin = -39 } } }", "with a run without its style step"),
                   ("SKYBORNE_FEATURES = { races = { [1] = { skin = -39, style = 40 } } }", "with a run without its length")):
    l = start(data=data)
    check(not shown(l) and clicks(l, "SKIN(1)") == (1, 1, 1) and clicks(l, "ROW(1)") == (None, 0, 0), "%s the row stays hidden and Skin Color is the game's" % what)

# --- every run: from every colour both rows step right and apart, the short way round
lua5 = start()
for S in range(5, 21):
    problems, runs = [], []
    for i, K in enumerate(k for k in range(2, 21) if math.gcd(S, k) == 1):
        n, a, c = run(S, K)
        # the data's steps as they come: the short way, the long way, either way round
        a2, c2 = [(a, c), (a % n, c % n), (a % n - n, c % n - n), (a + 2 * n, c - 2 * n)][i % 4]
        lua5.execute("SKYBORNE_FEATURES.races[1] = { n = %d, skin = %d, style = %d }; PICK(1, 3)" % (n, a2, c2))
        bad, most = lua5.eval("SWEEP(%d, %d)" % (S, K))
        if bad:
            problems.append("K %d: %s" % (K, bad))
        elif most != max(abs(a), abs(c)) or most > n / 2:
            problems.append("K %d: a click took %d steps (the short way is %d)" % (K, most, max(abs(a), abs(c))))
        runs.append(K)
    check(not problems, "%d skins with %s options: from every colour both rows step right and apart, the short way%s"
          % (S, ", ".join(map(str, runs)), (": " + "; ".join(problems[:3])) if problems else ""))

# the XML
root = ET.parse(os.path.join(GLUE, "SkyborneFeatures.xml")).getroot()
frame = root.find("{http://www.blizzard.com/wow/ui/}Frame")
check(frame is not None and frame.get("name") == "SkyborneFeatures" and frame.get("inherits") == "CharacterCustomizationFrameTemplate"
      and frame.get("parent") == "CharacterCreateConfigurationFrame" and frame.get("hidden") == "true", "the frame is the game's row template, under the configuration frame, hidden")
anchor = frame.find(".//{http://www.blizzard.com/wow/ui/}Anchor")
check(anchor.get("relativeTo") == "CharacterCustomizationButtonFrame1" and anchor.get("point") == "BOTTOM" and anchor.get("relativePoint") == "TOP",
      "it sits on top of the skin colour's row")

# --- the data against each other
data = lua51.LuaRuntime(); data.execute(DATA)
races = data.eval("SKYBORNE_FEATURES.races")
check(races is not None, "SkyborneFeaturesData.lua gives each race's run (races = { [race] = { n, skin, style } }, tools/build_data.py)")
runs = {int(r): (int(v["n"]), int(v["skin"]), int(v["style"])) for r, v in (races.items() if races is not None else [])}
names = data.eval("SKYBORNE_FEATURES.names")
names = {int(k): v for k, v in names.items()} if names is not None else None
rows, puts = {}, collections.Counter()
for line in open(os.path.join(MODULE, "data", "client", "dbc", "CharSections.txt")):
    f = line.rstrip("\n").split("\t")
    if f[0] == "put":
        rows[int(f[1])] = [int(x) if re.fullmatch(r"-?\d+", x) else x.replace("\\\\", "\\") for x in f[1:]]
        puts[int(f[1])] += 1
    elif f[0] in ("set", "or") and int(f[1]) in rows:
        row, field, value = rows[int(f[1])], int(f[2]), int(f[3])
        row[field] = value if f[0] == "set" else row[field] | value
# (fields: id, race, sex, section, texture 1-3, flags, face, colour)
check(all(n == 1 for n in puts.values()), "every row has its own id")
check(all(r[0] <= 999999 for r in rows.values()), "the rows' ids stay below 1000000")
check(not any("FemaleFeature" in str(t) for r in rows.values() for t in r[4:7]), "the old Features rows are gone")
check(set(runs) <= set(BODIES), "the races with a run have a body the detail is made for: %s" % sorted(runs))
for race, (n, a, c) in sorted(runs.items()):
    S = math.gcd(c % n, n)
    K = n // S
    check(K >= 2 and S * K == n and math.gcd(S, K) == 1, "race %d: %d colours are %d skins of %d options, coprime" % (race, n, S, K))
    if names is not None:
        check(len(names) == K and names.get(0) == "None", "race %d: the data's %d names are its %d options, None first" % (race, len(names), K))
    check(a % S == 1 % S and a % K == 0 and c % S == 0 and c % K == 1 and abs(a) <= n / 2 and abs(c) <= n / 2,
          "race %d: Skin Color %+d is the next skin with the same detail, Bottom Features %+d the next detail, the short way" % (race, a, c))
    mine = [r for r in rows.values() if r[1] == race and r[2] == 1]
    skins = {r[9]: r for r in mine if r[3] == 0 and r[7] & 1}
    colours = sorted(skins)
    first = colours[0] if colours else 0
    check(colours == list(range(first, first + n)), "race %d: its playable skin colours are exactly the run, %d to %d (%d)" % (race, first, first + n - 1, len(colours)))
    check(first + n - 1 <= 255, "race %d: the last colour (%d) fits the byte the character keeps it in" % (race, first + n - 1))
    if colours != list(range(first, first + n)):
        continue
    def detail(r):
        m = [re.search(r"_D(\d+)\.blp$", t, re.I) for t in r[4:7] if isinstance(t, str)]
        return next((int(x.group(1)) for x in m if x), 0)
    def others(section, colour):
        return sorted((r[8], r[4], r[5], r[6], r[7]) for r in mine if r[3] == section and r[9] == colour)
    check(all(skins[first + p][4] == skins[first + p % S][4] and skins[first + p][6] == skins[first + p % S][6] for p in range(n)),
          "race %d: colour p is skin p mod %d (its textures)" % (race, S))
    check(all(detail(skins[first + p]) == p % K for p in range(n)), "race %d: colour p has the detail p mod %d (_DNN)" % (race, K))
    check(all(others(1, first + p) and others(1, first + p) == others(1, first + p % S) for p in range(n)), "race %d: every colour has its skin's faces" % race)
    check(all(others(4, first + p) == others(4, first + p % S) for p in range(n)), "race %d: every colour has its skin's section 4 rows" % race)

# the detail's mesh and art, for each race with a run
DETAIL = os.path.join(MODULE, "data", "client", "bodydetail")
for body in sorted({BODIES[r] for r in runs}):
    K = max(runs[r][0] // math.gcd(runs[r][2] % runs[r][0], runs[r][0]) for r in runs if BODIES[r] == body)
    path = os.path.join(DETAIL, body + ".mesh")
    lines = [l.split("#")[0].split() for l in open(path)] if os.path.exists(path) else []
    model = [l for l in lines if l and l[0] == "model"]
    vertices = [l for l in lines if l and l[0] == "vertex"]
    tris = [l for l in lines if l and l[0] == "tri"]
    check(len(model) == 1 and len(model[0]) == 5 and int(model[0][3]) == len(vertices) and int(model[0][4]) == 3 * len(tris)
          and all(0.5 <= float(v[3]) <= 1 and 0 <= float(v[2]) <= 1 for v in vertices)
          and all(0 <= int(i) < len(vertices) for t in tris for i in t[2:5]),
          "%s.mesh: %d vertices in the bottom half of the tall texture, %d triangles" % (body, len(vertices), len(tris)))
    for nn in range(1, K):
        path = os.path.join(DETAIL, "%s_%02d.bin" % (body, nn))
        d = open(path, "rb").read() if os.path.exists(path) else b""
        ok = len(d) > 16 and struct.unpack_from("<4sIII", d, 0) == (b"BDET", 1, 512, 512)
        try:
            ok = ok and len(zlib.decompress(d[16:])) == 512 * 512 * 4
        except zlib.error:
            ok = False
        check(ok, "%s_%02d.bin: a 512x512 RGBA detail" % (body, nn))

print("%d failure(s)" % failures)
sys.exit(1 if failures else 0)
