-- SPDX-License-Identifier: GPL-3.0-or-later
-- The Features row on the character creation screen (mod-skyborne): arrows, like the face's or the hair's,
-- that cycle a female's body-hair design (data/client/features; none is the first). The game has no
-- slider for it, so a design is a block of skin colours: a race with S skins has them again for each
-- design (tools/build_data.py, CharSections), in the order of the designs, so stepping the skin colour
-- by S is the same skin in the next design, and the list wraps around from the last design to none.
-- SkyborneFeaturesData.lua says how many skins each race has (its females; males have no designs).
-- The row is shown for a female of a race that has designs, and hidden for anyone else. Like the other
-- rows it is reached by the game's own random look and the Randomize button, which pick any skin
-- colour: a new look may start with a design.

local frame = SkyborneFeatures;
if ( not frame or type(SKYBORNE_FEATURES) ~= "table" ) then
	return;
end

-- Race by the file names the screen gives (its list's second values), where it gives no id.
local RACE_IDS = { HUMAN = 1, ORC = 2, DWARF = 3, NIGHTELF = 4, SCOURGE = 5, TAUREN = 6, GNOME = 7, TROLL = 8,
	BLOODELF = 10, DRAENEI = 11, SKYBORNEA = 13, SKYBORNEH = 14 };

-- The screen's race list, by position: (name, file, id) three at a time.
local raceList = {};
local function Capture(...)
	for i = 1, select("#", ...), 3 do
		local _, file, id = select(i, ...);
		local n = (i - 1) / 3 + 1;
		if ( type(id) ~= "number" and type(file) == "string" ) then
			id = RACE_IDS[strupper(file)];
		end
		raceList[n] = id;
	end
end

-- The selected race's id: the screen's getter may give the position in its list, or the name, file and id.
local function SelectedRace()
	if ( type(GetSelectedRace) ~= "function" ) then
		return nil;
	end
	local a, b, c = GetSelectedRace();
	if ( type(a) == "number" and raceList[a] ) then
		return raceList[a];
	end
	if ( type(c) == "number" ) then
		return c;
	end
	if ( type(b) == "string" and RACE_IDS[strupper(b)] ) then
		return RACE_IDS[strupper(b)];
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

-- How many skins the selected race and sex have (the step from a design to the next), or nil where
-- there are no designs.
local function Skins()
	local race = SelectedRace();
	if ( race and IsFemale() ) then
		return SKYBORNE_FEATURES.skins[race];
	end
	return nil;
end

local function Update()
	if ( Skins() ) then
		frame:Show();
	else
		frame:Hide();
	end
end

local function Cycle(direction)
	local skins = Skins();
	if ( not skins ) then
		return;
	end
	if ( type(PlaySound) == "function" ) then
		PlaySound("gsCharacterCreationLook");
	end
	CycleCharCustomization(1, direction * skins);
end

local label = _G["SkyborneFeaturesText"];
if ( label ) then
	label:SetText("Features");
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
for _, name in ipairs({ "CharacterCreateRaceButton_OnClick", "CharacterCreateGenderButton_OnClick", "CharacterCreate_UpdateModel",
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
