-- SPDX-License-Identifier: GPL-3.0-or-later
-- The Skyborne (mod-skyborne) on the character creation and selection screens. Loaded after the
-- game's own screens (the patch adds it to GlueXML.toc):
--
--  * More race buttons: the game's screen has ten (CharacterCreateRaceButton1-10); SkyborneCreate.xml
--    makes six more, here given the names the screen looks for, where it hasn't a button by that
--    number already (another patch's screen may have more).
--  * Where they go: after the screen lays out its races, each side's go down its own column (the
--    Alliance's left, the Horde's right), closer together when a side has more than five.
--  * Their icons: the blood elf's, blue and white-haired, in column 7 of the race icons (the patch
--    paints them there).
--  * Their backgrounds: the High Order's is the Humans' (they start in Northshire), the Windshapers'
--    the Tauren's (Camp Narache).
--  * Their text.

local SKYBORNE = { SKYBORNEA = "Human", SKYBORNEH = "Tauren" };

RACE_ICON_TCOORDS["SKYBORNEA_MALE"]   = { 0.75, 0.875, 0, 0.25 };
RACE_ICON_TCOORDS["SKYBORNEH_MALE"]   = { 0.75, 0.875, 0.25, 0.5 };
RACE_ICON_TCOORDS["SKYBORNEA_FEMALE"] = { 0.75, 0.875, 0.5, 0.75 };
RACE_ICON_TCOORDS["SKYBORNEH_FEMALE"] = { 0.75, 0.875, 0.75, 1.0 };

local highest = 0;
for i = 1, 16 do
	local name = "CharacterCreateRaceButton"..i;
	if ( not _G[name] and _G["SkyborneRaceButton"..i] ) then
		local extra = "SkyborneRaceButton"..i;
		_G[name] = _G[extra];
		_G[name.."NormalTexture"] = _G[extra.."NormalTexture"];
		_G[name.."PushedTexture"] = _G[extra.."PushedTexture"];
		_G[name.."Text"] = _G[extra.."Text"];
		_G[name.."Shadow"] = _G[extra.."Shadow"];
		if ( CharacterCreateRaceButton1 ) then
			_G[name]:SetParent(CharacterCreateRaceButton1:GetParent());
		end
	end
	if ( _G[name] ) then
		highest = i;
	end
end
if ( highest > MAX_RACES ) then
	MAX_RACES = highest;
end

local function LayOut()
	local first = CharacterCreateRaceButton1;
	if ( not first or not CharacterCreate.numRaces ) then
		return;
	end
	local parent = first:GetParent();
	local columns = { Alliance = {}, Horde = {} };
	for i = 1, CharacterCreate.numRaces do
		local _, faction = GetFactionForRace(i);
		local column = columns[faction] or columns.Alliance;
		column[#column + 1] = _G["CharacterCreateRaceButton"..i];
	end
	local most = math.max(#columns.Alliance, #columns.Horde);
	local gap = 10;
	if ( most > 5 ) then
		-- The game's five fill 230 points with gaps of 10; more fill what's free above the sex buttons.
		gap = math.max(2, math.floor((326 - 38 * most) / (most - 1)));
	end
	for side, x in pairs({ Alliance = -50, Horde = 50 }) do
		local previous;
		for _, button in ipairs(columns[side]) do
			button:ClearAllPoints();
			if ( previous ) then
				button:SetPoint("TOPLEFT", previous, "BOTTOMLEFT", 0, -gap);
			else
				button:SetPoint("TOP", parent, "TOP", x, -50);
			end
			previous = button;
		end
	end
end

local enumerateRaces = CharacterCreateEnumerateRaces;
function CharacterCreateEnumerateRaces(...)
	enumerateRaces(...);
	LayOut();
end

local setBackgroundModel = SetBackgroundModel;
function SetBackgroundModel(model, name)
	return setBackgroundModel(model, SKYBORNE[strupper(name or "")] or name);
end

local description = "Thousands of years ago, Highborne exiles made a pact with the elemental spirits of the wind and raised a civilization in Skywall, among the clouds. The shen'dorei lived above the world for millennia, until the air spirits vanished and their sky cities began to fall. Now the Skyborne descend to Azeroth to find their place in it.";
RACE_INFO_SKYBORNEA = description.."|n|nThe High Order descends from the Highborne scholars of Eldre'Thalas. They seek the arcane knowledge their people need to live without the spirits, and have joined the Alliance.";
RACE_INFO_SKYBORNEH = description.."|n|nThe Windshapers descend from the first shen'dorei to receive the wind spirits' gift of skysight. They seek to restore their lost mentors, and have joined the Horde.";

ABILITY_INFO_SKYBORNEA1 = "- Walk on Air: Glide downward through the air for 10 sec.";
ABILITY_INFO_SKYBORNEA2 = "- Read Ley Line: Increases Health and Mana regeneration by 100% for 15 min.";
ABILITY_INFO_SKYBORNEA3 = "- Wind Blessed: Increases haste by 3%.";
ABILITY_INFO_SKYBORNEA4 = "- Elemental Insight: Damage dealt to Elementals increased by 5%.";
ABILITY_INFO_SKYBORNEH1 = ABILITY_INFO_SKYBORNEA1;
ABILITY_INFO_SKYBORNEH2 = "- Skysight: Increases run and mounted speed by 10% for 15 min.";
ABILITY_INFO_SKYBORNEH3 = ABILITY_INFO_SKYBORNEA3;
ABILITY_INFO_SKYBORNEH4 = ABILITY_INFO_SKYBORNEA4;

SKYBORNEA_DISABLED = "Skyborne of the High Order can't be made on this realm.";
SKYBORNEH_DISABLED = "Skyborne Windshapers can't be made on this realm.";
