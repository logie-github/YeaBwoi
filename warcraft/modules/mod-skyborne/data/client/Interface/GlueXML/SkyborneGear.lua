-- SPDX-License-Identifier: GPL-3.0-or-later
-- The gear button on the character creation screen (mod-skyborne). It cycles the preview between
--
--   no gear   the fig leaf
--   starter   the starting outfit of the race and class selected, its chest's icon
--   champion  the class's top raid set (Tier 10), its chest piece's icon
--
-- It sits at the bottom of the customization panel's arrow buttons, halfway between the name box and
-- that panel (measured from the screen as it is laid out; by the race and class lists if it can't be).
-- SkyborneGearData.lua (tools/build_gear.py) has the items and icons. The preview is dressed
-- through the model's own calls (Undress and TryOn); where the screen's model has none, the button
-- says so in its tooltip and changes nothing, and lists the frames it looked at. It is put after
-- the screen's race and class lists and buttons are made (the patch adds it to GlueXML.toc after
-- SkyborneCreate.lua), and dresses the preview again whenever a race, class or sex is picked.

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

-- The model the preview is drawn in, found by what it can do: the frames looked at are kept for the tooltip.
local looked = {};
local function Dressable(frame)
	return type(frame) == "table" and type(frame.TryOn) == "function" and type(frame.Undress) == "function";
end

local function FindModel()
	looked = {};
	local tried = {};
	local function try(name, frame)
		if ( type(frame) == "table" and not tried[frame] ) then
			tried[frame] = true;
			looked[#looked + 1] = name;
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

-- Placement ----------------------------------------------------------------------------------
-- The screen's frames in pixels (their scales undone), or nil while they aren't laid out.
local function Pixels(frame)
	if ( type(frame) ~= "table" or type(frame.GetLeft) ~= "function" ) then
		return nil;
	end
	local ok, l, r, b, t = pcall(function() return frame:GetLeft(), frame:GetRight(), frame:GetBottom(), frame:GetTop(); end);
	if ( not ok or not (l and r and b and t) ) then
		return nil;
	end
	local scale = (type(frame.GetEffectiveScale) == "function" and frame:GetEffectiveScale()) or 1;
	return l * scale, r * scale, b * scale, t * scale;
end

local function Visible(frame)
	return type(frame) == "table" and (type(frame.IsVisible) ~= "function" or frame:IsVisible());
end

local function Descendants(root, depth, out)
	out = out or {};
	if ( type(root) ~= "table" or type(root.GetChildren) ~= "function" or depth < 1 ) then
		return out;
	end
	for _, child in ipairs({ root:GetChildren() }) do
		if ( type(child) == "table" and #out < 400 ) then
			out[#out + 1] = child;
			Descendants(child, depth - 1, out);
		end
	end
	return out;
end

local function TypeOf(frame)
	return type(frame.GetObjectType) == "function" and frame:GetObjectType() or "";
end

local function NameOf(frame)
	return (type(frame) == "table" and type(frame.GetName) == "function" and frame:GetName()) or "?";
end

-- Where the button was put (or why not), for the tooltip.
local placed = "by the sex buttons";

local function Place()
	local origin = GlueParent or UIParent;
	local root = CharacterCreate;
	if ( not button or type(origin) ~= "table" or type(root) ~= "table" or type(root.GetChildren) ~= "function" ) then
		return false;
	end
	local all = Descendants(root, 6);
	-- The name box: the screen's, or the first edit box showing.
	local nameBox = CharacterCreateNameEdit;
	if ( not (Visible(nameBox) and Pixels(nameBox)) ) then
		nameBox = nil;
		for _, f in ipairs(all) do
			if ( TypeOf(f) == "EditBox" and Visible(f) and Pixels(f) ) then
				nameBox = f;
				break;
			end
		end
	end
	if ( not nameBox ) then
		placed = "by the sex buttons (no name box found)";
		return false;
	end
	local nl, nr = Pixels(nameBox);
	local nameCenter = (nl + nr) / 2;
	-- The customize panel: the screen's, or the frame right of the name box that holds the most buttons.
	local panel;
	for _, name in ipairs({ "CharacterCustomizationFrame", "CharacterCreateCustomizationFrame" }) do
		local f = _G[name];
		if ( Visible(f) and Pixels(f) ) then
			panel = f;
			break;
		end
	end
	if ( not panel ) then
		local most = 3;
		for _, f in ipairs(all) do
			local l = Pixels(f);
			if ( Visible(f) and l and l > nameCenter and f ~= nameBox ) then
				local buttons = 0;
				for _, d in ipairs(Descendants(f, 4)) do
					if ( TypeOf(d) == "Button" and Visible(d) ) then
						buttons = buttons + 1;
					end
				end
				if ( buttons > most ) then
					most, panel = buttons, f;
				end
			end
		end
	end
	if ( not panel ) then
		placed = "by the sex buttons (no customize panel found)";
		return false;
	end
	local pl, _, pb = Pixels(panel);
	-- The bottom of the arrow buttons (the panel's buttons): the lowest of them, else the panel's.
	local bottom, lowest, lowestName = pb, nil, nil;
	for _, d in ipairs(Descendants(panel, 4)) do
		if ( TypeOf(d) == "Button" and Visible(d) ) then
			local _, _, b = Pixels(d);
			if ( b and (not lowest or b < lowest) ) then
				lowest = b;
				lowestName = NameOf(d);
			end
		end
	end
	bottom = lowest or bottom;
	local scale = (type(button.GetEffectiveScale) == "function" and button:GetEffectiveScale()) or 1;
	local width = ((type(button.GetWidth) == "function" and button:GetWidth()) or 38) * scale;
	local x = (nr + pl) / 2 - width / 2;
	button:SetParent(root);
	button:ClearAllPoints();
	button:SetPoint("BOTTOMLEFT", origin, "BOTTOMLEFT", x / scale, bottom / scale);
	placed = "between "..NameOf(nameBox).." and "..NameOf(panel)..", level with "..(lowest and lowestName or "its bottom");
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
			Place();
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
	local frame = CharacterCreateGenderButtonFemale;
	button:ClearAllPoints();
	if ( frame ) then
		button:SetPoint("LEFT", frame, "RIGHT", 12, 0);
	elseif ( CharacterCreateConfigurationFrame ) then
		button:SetPoint("BOTTOM", CharacterCreateConfigurationFrame, "BOTTOM", 0, 12);
	end
	button:SetScript("OnClick", function()
		mode = mode % #MODES + 1;
		Apply();
		if ( button.tooltip ) then
			button.tooltip();
		end
	end);
	button.tooltip = function()
		if ( not GlueTooltip ) then
			return;
		end
		local _, _, extra = Gear(MODES[mode]);
		GlueTooltip:SetOwner(button, "ANCHOR_RIGHT");
		GlueTooltip:SetText(NAMES[MODES[mode]]..(extra and (": "..extra) or ""), 1, 1, 1);
		GlueTooltip:AddLine("Click to change what the preview wears.", 0.8, 0.8, 0.8, 1);
		GlueTooltip:AddLine("Placed "..placed..".", 0.6, 0.6, 0.6, 1);
		if ( not FindModel() ) then
			GlueTooltip:AddLine("This screen's model can't be dressed here (looked at: "..table.concat(looked, ", ")..").", 1, 0.3, 0.3, 1);
		end
		GlueTooltip:Show();
	end;
	button:SetScript("OnEnter", function()
		button.tooltip();
	end);
	button:SetScript("OnShow", function()
		waits = { 0.05, 0.4, 1.2 };
	end);
	button:SetScript("OnLeave", function()
		if ( GlueTooltip ) then
			GlueTooltip:Hide();
		end
	end);
	Refresh();
	waits = { 0.05, 0.4, 1.2 };
end
