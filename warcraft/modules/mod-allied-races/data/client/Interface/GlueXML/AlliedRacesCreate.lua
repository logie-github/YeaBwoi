-- SPDX-License-Identifier: GPL-3.0-or-later
-- The void elves and the nightborne (mod-allied-races) on the character creation and selection
-- screens. Loaded after the game's own screens (the patch adds it to GlueXML.toc), before
-- mod-skyborne's SkyborneCreate.lua when that patch is in (it reads this one's archive):
--
--  * More race buttons: the game's screen has ten (CharacterCreateRaceButton1-10);
--    AlliedRacesCreate.xml makes six more, here given the names the screen looks for, where it
--    hasn't a button by that number already (another patch's screen may have more).
--  * Where they go: after the screen lays out its races, each side's go down its own column (the
--    Alliance's left, the Horde's right), closer together when a side has more than five (the
--    Skyborne's screen lays them out the same way, after this).
--  * Their icons: their portraits in the last column of the race icons (the patch paints them
--    there: data/client/icons).
--  * Their backgrounds: the void elves' is the Draenei's (they start in Ammen Vale), the
--    nightborne's the Forsaken's (Deathknell).
--  * Their text.

local ALLIED = { VOIDELF = "Draenei", NIGHTBORNE = "Scourge" };

RACE_ICON_TCOORDS["VOIDELF_MALE"]      = { 0.875, 1.0, 0, 0.25 };
RACE_ICON_TCOORDS["NIGHTBORNE_MALE"]   = { 0.875, 1.0, 0.25, 0.5 };
RACE_ICON_TCOORDS["VOIDELF_FEMALE"]    = { 0.875, 1.0, 0.5, 0.75 };
RACE_ICON_TCOORDS["NIGHTBORNE_FEMALE"] = { 0.875, 1.0, 0.75, 1.0 };

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

VOIDELF_DISABLED = "Void elves can't be made on this realm.";
NIGHTBORNE_DISABLED = "Nightborne can't be made on this realm.";
