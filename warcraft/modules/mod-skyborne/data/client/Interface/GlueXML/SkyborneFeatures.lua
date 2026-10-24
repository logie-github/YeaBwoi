-- SPDX-License-Identifier: GPL-3.0-or-later
-- The Bottom Features row on the character creation screen (mod-skyborne): arrows, like the game's
-- other rows', that cycle a female's body detail (none is the first). The game has no slider for it and
-- no getter for the look shown, so the skin colours carry it: a race with S skins and K options (none
-- and K-1 details, K coprime with S) has N = S*K colours, colour p the skin p mod S with the detail
-- p mod K (tools/build_data.py, CharSections). Stepping the colour by the race's skin step (a multiple
-- of K, one more than a multiple of S) is the next skin with the same detail, by its style step (a
-- multiple of S, one more than a multiple of K) the same skin with the next detail, from any colour, as
-- the list wraps around: the Skin Color arrows step by the first, this row's by the second, the short
-- way round, one colour at a time (as the game's arrows do: a longer step in one call is untried).
-- SkyborneFeaturesData.lua gives each race's { n = N, skin = step, style = step } (its females; males
-- have none), and may list the races whose models the patch changed (patched), the only ones then
-- shown the row. For a male, a Death Knight (her own skins are outside the colours) or a race without
-- them the Skin Color arrows are the game's and the row is hidden. Like the other rows it is reached by
-- the game's own random look and the Randomize button, which pick any colour.

local frame = SkyborneFeatures;
if ( not frame or type(SKYBORNE_FEATURES) ~= "table" ) then
	return;
end

-- Race by the file names the screen gives (its list's second values).
local RACE_IDS = { HUMAN = 1, ORC = 2, DWARF = 3, NIGHTELF = 4, SCOURGE = 5, TAUREN = 6, GNOME = 7, TROLL = 8,
	BLOODELF = 10, DRAENEI = 11, SKYBORNEA = 13, SKYBORNEH = 14, VOIDELF = 15, NIGHTBORNE = 16 };

-- The screen's race list, by position: (name, file, enabled) three at a time in 3.3.5, whose third
-- value is whether the race can be made (1 or 0), not its id: the race is its file's.
local raceList = {};
local function Capture(...)
	for i = 1, select("#", ...), 3 do
		local _, file, third = select(i, ...);
		local n = (i - 1) / 3 + 1;
		local id = type(file) == "string" and RACE_IDS[strupper(file)] or nil;
		if ( not id and type(third) == "number" and third > 1 ) then
			id = third;
		end
		raceList[n] = id;
	end
end

-- The selected race's id: by the file the screen's getter gives, if it gives one, else by its position
-- in the list (3.3.5's GetSelectedRace gives the position alone).
local function SelectedRace()
	if ( type(GetSelectedRace) ~= "function" ) then
		return nil;
	end
	local a, b, c = GetSelectedRace();
	if ( type(b) == "string" and RACE_IDS[strupper(b)] ) then
		return RACE_IDS[strupper(b)];
	end
	if ( type(a) == "number" and raceList[a] ) then
		return raceList[a];
	end
	if ( type(c) == "number" and raceList[c] ) then
		return raceList[c];
	end
	return nil;
end

local function IsFemale()
	if ( type(GetSelectedSex) ~= "function" ) then
		return false;
	end
	local sex = GetSelectedSex();
	if ( SEX_FEMALE ~= nil ) then
		return sex == SEX_FEMALE;
	end
	return sex == 3;
end

local function IsDeathKnight()
	if ( type(GetSelectedClass) ~= "function" ) then
		return false;
	end
	local _, file = GetSelectedClass();
	return type(file) == "string" and strupper(file) == "DEATHKNIGHT";
end

-- A step the short way round n colours.
local function Short(step, n)
	step = step % n;
	if ( step > n / 2 ) then
		step = step - n;
	end
	return step;
end

-- The selected race's colours: how many, and the skin and style steps; nil where the arrows are the
-- game's own.
local function Run()
	local race, runs = SelectedRace(), SKYBORNE_FEATURES.races;
	local run = race and type(runs) == "table" and runs[race];
	if ( type(run) ~= "table" or type(run.n) ~= "number" or run.n < 2 or type(run.skin) ~= "number"
			or type(run.style) ~= "number" or not IsFemale() or IsDeathKnight() ) then
		return nil;
	end
	return run.n, Short(run.skin, run.n), Short(run.style, run.n);
end

local function Shown()
	local patched = SKYBORNE_FEATURES.patched;
	return Run() ~= nil and (type(patched) ~= "table" or patched[SelectedRace()] == true);
end

local function Update()
	if ( Shown() ) then
		frame:Show();
	else
		frame:Hide();
	end
end

local function Step(delta)
	if ( type(PlaySound) == "function" ) then
		PlaySound("gsCharacterCreationLook");
	end
	local one = delta < 0 and -1 or 1;
	for i = 1, math.abs(delta) do
		CycleCharCustomization(1, one);
	end
end

local function Cycle(direction)
	if ( not Shown() ) then
		return;
	end
	local _, _, style = Run();
	Step(direction * style);
end

-- The Skin Color arrows (the game's row 1, whose buttons call these by name).
for name, direction in pairs({ CharacterCustomization_Left = -1, CharacterCustomization_Right = 1 }) do
	local original = _G[name];
	if ( type(original) == "function" ) then
		_G[name] = function(id, ...)
			local n, skin = Run();
			if ( id == 1 and n ) then
				Step(direction * skin);
				return;
			end
			return original(id, ...);
		end
	end
end

local label = _G["SkyborneFeaturesText"];
if ( label ) then
	label:SetText("Bottom Features");
end
local right, left = _G["SkyborneFeaturesRightButton"], _G["SkyborneFeaturesLeftButton"];
if ( right ) then
	right:SetScript("OnClick", function() Cycle(1); end);
end
if ( left ) then
	left:SetScript("OnClick", function() Cycle(-1); end);
end

-- The screen picks a race, sex or look over a moment: look again as it settles.
local waits = {};
local timer = CreateFrame("Frame");
timer:SetScript("OnUpdate", function(self, elapsed)
	if ( #waits == 0 ) then
		return;
	end
	elapsed = elapsed or arg1 or 0;
	for i = #waits, 1, -1 do
		waits[i] = waits[i] - elapsed;
		if ( waits[i] <= 0 ) then
			table.remove(waits, i);
			Update();
		end
	end
end);

local function UpdateLater()
	Update();
	waits = { 0.05, 0.4, 1.2 };
end

local enumerate = CharacterCreateEnumerateRaces;
if ( type(enumerate) == "function" ) then
	CharacterCreateEnumerateRaces = function(...)
		Capture(...);
		local a, b, c, d = enumerate(...);
		UpdateLater();
		return a, b, c, d;
	end
end
for _, name in ipairs({ "CharacterRace_OnClick", "SetCharacterGender", "CharacterClass_OnClick", "CharacterCreate_UpdateModel",
		"CharacterCreate_Randomize" }) do
	local original = _G[name];
	if ( type(original) == "function" ) then
		_G[name] = function(...)
			local a, b, c, d = original(...);
			UpdateLater();
			return a, b, c, d;
		end
	end
end
if ( type(CharacterCreate) == "table" and type(CharacterCreate.HookScript) == "function" ) then
	CharacterCreate:HookScript("OnShow", UpdateLater);
end
UpdateLater();
