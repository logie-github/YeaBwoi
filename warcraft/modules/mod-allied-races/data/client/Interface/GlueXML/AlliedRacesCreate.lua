-- SPDX-License-Identifier: GPL-3.0-or-later
-- The void elves, nightborne, Lightforged Draenei and Zandalari Trolls (mod-allied-races) on the character creation and selection
-- screens. Loaded after the game's own screens (the patch adds it to GlueXML.toc), before
-- mod-skyborne's SkyborneCreate.lua when that patch is in (it reads this one's archive):
--
--  * More race buttons: the game's screen has ten (CharacterCreateRaceButton1-10);
--    AlliedRacesCreate.xml makes six more, here given the names the screen looks for, where it
--    hasn't a button by that number already (another patch's screen may have more).
--  * Where they go: after the screen lays out its races, each side's go down its own banner (the
--    Alliance's left, the Horde's right), two to a row when a side has more than five (the
--    Skyborne's screen lays them out the same way, after this).
--  * Their icons: their portraits in the last column of the race icons (the patch paints them
--    there: data/client/icons).
--  * Their backgrounds: the void elves' and the Lightforged's are the Draenei's (they start in Ammen
--    Vale), the nightborne's the Forsaken's (Deathknell), the Zandalari's the Tauren's (Camp Narache).
--  * Their text.

local ALLIED = { VOIDELF = "Draenei", NIGHTBORNE = "Scourge", LIGHTFORGEDDRAENEI = "Draenei", ZANDALARITROLL = "Tauren" };

RACE_ICON_TCOORDS["VOIDELF_MALE"]      = { 0.875, 1.0, 0, 0.25 };
RACE_ICON_TCOORDS["NIGHTBORNE_MALE"]   = { 0.875, 1.0, 0.25, 0.5 };
RACE_ICON_TCOORDS["VOIDELF_FEMALE"]    = { 0.875, 1.0, 0.5, 0.75 };
RACE_ICON_TCOORDS["NIGHTBORNE_FEMALE"] = { 0.875, 1.0, 0.75, 1.0 };
RACE_ICON_TCOORDS["LIGHTFORGEDDRAENEI_MALE"]   = { 0.625, 0.75, 0, 0.25 };
RACE_ICON_TCOORDS["ZANDALARITROLL_MALE"]       = { 0.625, 0.75, 0.25, 0.5 };
RACE_ICON_TCOORDS["LIGHTFORGEDDRAENEI_FEMALE"] = { 0.625, 0.75, 0.5, 0.75 };
RACE_ICON_TCOORDS["ZANDALARITROLL_FEMALE"]     = { 0.625, 0.75, 0.75, 1.0 };

local highest = 0;
for i = 1, 16 do
	local name = "CharacterCreateRaceButton"..i;
	if ( not _G[name] and _G["AlliedRaceButton"..i] ) then
		local extra = "AlliedRaceButton"..i;
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
	-- Two races to a row once a side has more than five (a side's banner is two icons wide);
	-- the game's five fill 230 points with gaps of 10, more rows fill what's free above the sex buttons.
	local perRow = ( most > 5 ) and 2 or 1;
	local rows = math.ceil(most / perRow);
	local gap = 10;
	if ( rows > 5 ) then
		gap = math.max(2, math.floor((326 - 38 * rows) / (rows - 1)));
	end
	for side, x in pairs({ Alliance = -50, Horde = 50 }) do
		for index, button in ipairs(columns[side]) do
			local row = math.floor((index - 1) / perRow);
			local across = 0;
			if ( perRow == 2 ) then
				across = ( (index - 1) % 2 == 0 ) and -21 or 21;
			end
			button:ClearAllPoints();
			button:SetPoint("TOP", parent, "TOP", x + across, -50 - row * (38 + gap));
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
	return setBackgroundModel(model, ALLIED[strupper(name or "")] or name);
end

RACE_INFO_VOIDELF = "Years ago, a group of blood elves led by Magister Umbric sought to harness the power of the Void. Cast out of Silvermoon, they were transformed by the very force they studied, and now, as the ren'dorei, they fight for the Alliance. Those who wash up on the shores of Azuremyst Isle find a welcome among the draenei, who know what it is to be exiles.";
RACE_INFO_NIGHTBORNE = "Secluded in Suramar for ten thousand years behind a barrier of their own making, the nightborne developed an unrivaled mastery of the arcane. Freed from the Legion and from their own tyrant, the shal'dorei have joined the Horde, and their emissaries now walk the roads of Tirisfal beside the Forsaken.";

ABILITY_INFO_VOIDELF1 = "- Spatial Rift: Tear a rift in space. Use it again to teleport through the rift.";
ABILITY_INFO_VOIDELF2 = "- Entropic Embrace: Your abilities have a chance to empower you with the essence of the Void.";
ABILITY_INFO_VOIDELF3 = "- Chill of Night: Reduces Shadow damage taken by 1%.";
ABILITY_INFO_VOIDELF4 = "- Preternatural Calm: Your spell casts are not delayed by taking damage.";
ABILITY_INFO_NIGHTBORNE1 = "- Arcane Pulse: Deals Arcane damage to nearby enemies and reduces their movement speed by 50%.";
ABILITY_INFO_NIGHTBORNE2 = "- Arcane Resistance: Reduces Arcane damage taken by 1%.";
ABILITY_INFO_NIGHTBORNE3 = "- Magical Affinity: Increases magical damage dealt by 1%.";
ABILITY_INFO_NIGHTBORNE4 = "- Ancient History: Inscription skill increased by 15.";

RACE_INFO_LIGHTFORGEDDRAENEI = "When the Army of the Light fell upon the Legion's ships above Argus, the draenei who were forged anew in its radiance became the Lightforged: warriors bound to the Holy Light, who fight the demons that once corrupted their people. The Lightforged who arrive on Azuremyst Isle are welcomed among the exiles of the Exodar as kin.";
RACE_INFO_ZANDALARITROLL = "The Zandalari are the oldest and proudest of the troll tribes, keepers of the loa and of Zuldazar's golden city. Their kings have stood beside the Horde in its wars, and their emissaries now cross the sea to Mulgore, where the tauren teach them the earth's ways before they find their own place among the Horde.";

ABILITY_INFO_LIGHTFORGEDDRAENEI1 = "- Light's Judgment: Calls down a strike of Holy energy on your target, damaging nearby enemies.";
ABILITY_INFO_LIGHTFORGEDDRAENEI2 = "- Forge of Light: Blacksmithing skill increased by 10.";
ABILITY_INFO_LIGHTFORGEDDRAENEI3 = "- Light's Reckoning: When you die, Holy energy bursts from you, harming enemies and healing allies.";
ABILITY_INFO_LIGHTFORGEDDRAENEI4 = "- Holy Resistance: Reduces Holy damage taken by 1%. Demonbane: more experience from demons.";
ABILITY_INFO_ZANDALARITROLL1 = "- Regeneratin': Regenerates your health over 6 sec, until you take damage.";
ABILITY_INFO_ZANDALARITROLL2 = "- Embrace of Pa'ku: Your attacks have a chance to increase your critical strike chance.";
ABILITY_INFO_ZANDALARITROLL3 = "- Pterrordax Swoop: Slows your fall for 2 min.";
ABILITY_INFO_ZANDALARITROLL4 = "- City of Gold: 2% more money looted from creatures.";

LIGHTFORGEDDRAENEI_DISABLED = "Lightforged Draenei can't be made on this realm.";
ZANDALARITROLL_DISABLED = "Zandalari Trolls can't be made on this realm.";
VOIDELF_DISABLED = "Void elves can't be made on this realm.";
NIGHTBORNE_DISABLED = "Nightborne can't be made on this realm.";
