-- SPDX-License-Identifier: GPL-3.0-or-later
-- The gear button on the character creation screen (mod-skyborne). It cycles the preview between
--
--   no gear   a leaf icon
--   starter   the starting outfit of the race and class selected, its chest's icon
--   champion  the class's top raid set (Tier 10), its chest piece's icon
--
-- It sits between the sex buttons, which move apart to make room for it (each under its race
-- column). SkyborneGearData.lua (tools/build_gear.py) has the items and icons. The preview is
-- dressed through the model's own calls (Undress and TryOn); 3.3.5's preview has none (it is a
-- ModelFFX, dressed by the game itself from CharStartOutfit), so there the button shows and cycles
-- but the preview stays as the game dresses it, and its tooltip says so. Its tooltip is the creation
-- screen's own (GlueTooltip_SetOwner and GlueTooltip_SetText: functions in 3.3.5's GlueXML, not
-- methods). It is put after the screen's race and class lists and buttons are made (the patch adds
-- it to GlueXML.toc after SkyborneCreate.lua), and dresses the preview again whenever a race, class
-- or sex is picked.

local MODES = { "nude", "starter", "champion" };
local NAMES = { nude = "No gear", starter = "Starting gear", champion = "Champion gear" };
local mode = 1;

-- Race and class by the file names the screen gives (its lists' second values), where it gives no id.
local RACE_IDS = { HUMAN = 1, ORC = 2, DWARF = 3, NIGHTELF = 4, SCOURGE = 5, TAUREN = 6, GNOME = 7, TROLL = 8,
	BLOODELF = 10, DRAENEI = 11, SKYBORNEA = 13, SKYBORNEH = 14 };
local CLASS_IDS = { WARRIOR = 1, PALADIN = 2, HUNTER = 3, ROGUE = 4, PRIEST = 5, DEATHKNIGHT = 6, SHAMAN = 7,
	MAGE = 8, WARLOCK = 9, DRUID = 11 };

-- The screen's lists, by position: the races and classes it enumerates (name, file, id) three at a time.
local raceList, classList = {}, {};

local function Capture(list, ids, ...)
	for i = 1, select("#", ...), 3 do
		local name, file, id = select(i, ...);
		local n = (i - 1) / 3 + 1;
		if ( type(id) ~= "number" and type(file) == "string" ) then
			id = ids[strupper(file)];
		end
		list[n] = id;
	end
end

local function Wrap(name, list, ids)
	local original = _G[name];
	if ( type(original) == "function" ) then
		_G[name] = function(...)
			Capture(list, ids, ...);
			return original(...);
		end
	end
end
Wrap("CharacterCreateEnumerateRaces", raceList, RACE_IDS);
Wrap("CharacterCreateEnumerateClasses", classList, CLASS_IDS);

-- The selected race or class id: the screen's getter may give the position in its list, or the
-- name, file and id.
local function Selected(getter, list, ids)
	if ( type(getter) ~= "function" ) then
		return nil;
	end
	local a, b, c = getter();
	if ( type(a) == "number" and list[a] ) then
		return list[a];
	end
	if ( type(c) == "number" ) then
		return c;
	end
	if ( type(b) == "string" and ids[strupper(b)] ) then
		return ids[strupper(b)];
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

-- What the preview wears for a mode: its items and icon (and the champion set's name), or nil.
local function Gear(which)
	local data = SKYBORNE_GEAR;
	if ( not data ) then
		return nil;
	end
	if ( which == "nude" ) then
		return {}, data.nude;
	end
	local class = Selected(GetSelectedClass, classList, CLASS_IDS);
	if ( not class ) then
		return nil;
	end
	if ( which == "champion" ) then
		local set = data.champion[class];
		if ( set ) then
			return set[3], set[1], set[2];
		end
		return nil;
	end
	local race = Selected(GetSelectedRace, raceList, RACE_IDS);
	local start = (race and data.start[race] and data.start[race][class]) or data.fallback[class];
	if ( start ) then
		return (IsFemale() and start[3]) or start[2], start[1];
	end
	return nil;
end

-- The model the preview is drawn in, found by what it can do.
local function Dressable(frame)
	return type(frame) == "table" and type(frame.TryOn) == "function" and type(frame.Undress) == "function";
end

local function FindModel()
	local tried = {};
	local function try(name, frame)
		if ( type(frame) == "table" and not tried[frame] ) then
			tried[frame] = true;
			if ( Dressable(frame) ) then
				return frame;
			end
		end
	end
	for _, name in ipairs({ "CharacterCreateModel", "CharacterCreatePreview", "CharCustomizeFrame", "CharacterCreate" }) do
		local found = try(name, _G[name]);
		if ( found ) then
			return found;
		end
	end
	local parent = CharacterCreate;
	if ( type(parent) == "table" and type(parent.GetChildren) == "function" ) then
		for _, child in ipairs({ parent:GetChildren() }) do
			local found = try(type(child.GetName) == "function" and child:GetName() or "?", child);
			if ( found ) then
				return found;
			end
		end
	end
	return nil;
end

local function Dress(items)
	local model = FindModel();
	if ( not model ) then
		return false;
	end
	pcall(model.Undress, model);
	for _, id in ipairs(items) do
		pcall(model.TryOn, model, id);
	end
	return true;
end

local button = SkyborneGearButton;

-- The creation screen's tooltip, as its race, class and sex buttons show theirs.
local function Tooltip()
	return CharacterCreateTooltip or GlueTooltip;
end

local function ShowTooltip(text)
	local tip = Tooltip();
	if ( not tip or type(GlueTooltip_SetOwner) ~= "function" or type(GlueTooltip_SetText) ~= "function" ) then
		return;
	end
	pcall(GlueTooltip_SetOwner, button, tip, -3, -5);
	pcall(GlueTooltip_SetText, text, tip);
end

local function HideTooltip()
	local tip = Tooltip();
	if ( tip and type(tip.Hide) == "function" ) then
		tip:Hide();
	end
end

-- Placement ----------------------------------------------------------------------------------
-- The middle of where the sex buttons stand (the screen's: 20 either side of the middle), and they
-- move out to 50 either side, under the race columns (SkyborneCreate.lua's), the button between.
local GAP = 12;

local function Place()
	local male, female = CharacterCreateGenderButtonMale, CharacterCreateGenderButtonFemale;
	button:ClearAllPoints();
	if ( not (male and female and type(male.GetPoint) == "function" and type(female.GetPoint) == "function") ) then
		if ( CharacterCreateConfigurationFrame ) then
			button:SetPoint("TOP", CharacterCreateConfigurationFrame, "TOP", 0, -392);
		end
		return false;
	end
	local ok, point, relativeTo, relativePoint, x, y = pcall(male.GetPoint, male, 1);
	local ok2, _, _, _, x2 = pcall(female.GetPoint, female, 1);
	-- (once only: after it, the sex buttons are anchored to the button)
	if ( not (ok and ok2 and point and type(x) == "number" and type(x2) == "number") or relativeTo == button ) then
		point, relativeTo, relativePoint, x, y, x2 = "TOP", CharacterCreateConfigurationFrame, "TOP", -20, -392, 20;
	end
	button:SetPoint(point, relativeTo, relativePoint, (x + x2) / 2, y);
	male:ClearAllPoints();
	male:SetPoint("RIGHT", button, "LEFT", -GAP, 0);
	female:ClearAllPoints();
	female:SetPoint("LEFT", button, "RIGHT", GAP, 0);
	return true;
end

local function Refresh()
	local items, icon = Gear(MODES[mode]);
	if ( icon ) then
		button:SetNormalTexture("Interface\\Icons\\"..icon);
	end
	return items;
end

local function Apply()
	local items = Refresh();
	if ( items ) then
		Dress(items);
	end
end

-- The screen puts the model together after a pick, over a moment: dress it again as it settles.
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
			Apply();
		end
	end
end);

local function ApplyLater()
	Refresh();
	waits = { 0.05, 0.4, 1.2 };
end

for _, name in ipairs({ "CharacterCreateRaceButton_OnClick", "CharacterCreateClassButton_OnClick",
		"CharacterCreateGenderButton_OnClick", "CharacterCreate_UpdateModel", "CharacterCreateEnumerateRaces",
		"CharacterCreateEnumerateClasses" }) do
	local original = _G[name];
	if ( type(original) == "function" ) then
		_G[name] = function(...)
			local a, b, c, d = original(...);
			ApplyLater();
			return a, b, c, d;
		end
	end
end

if ( button ) then
	Place();
	button:SetScript("OnClick", function()
		mode = mode % #MODES + 1;
		Apply();
		if ( button.tooltip ) then
			button.tooltip();
		end
	end);
	button.tooltip = function()
		local _, _, extra = Gear(MODES[mode]);
		local what = (FindModel() and "Click to change what the preview wears.")
			or "Click to change it. This game's preview can't be dressed yet: it keeps the gear the game gives it.";
		ShowTooltip(NAMES[MODES[mode]]..(extra and (": "..extra) or "").."\n"..what);
	end;
	button:SetScript("OnEnter", function()
		button.tooltip();
	end);
	button:SetScript("OnShow", function()
		waits = { 0.05, 0.4, 1.2 };
	end);
	button:SetScript("OnLeave", HideTooltip);
	Refresh();
	button:Show();
	waits = { 0.05, 0.4, 1.2 };
end
