-- SPDX-License-Identifier: GPL-3.0-or-later
-- The World of Warcraft app's custom races on the character creation screen. Loaded after
-- mod-worgoblin-high-elf's own CharacterCreate.lua and GlueParent.lua (scripts/build-races-package.sh
-- adds it to GlueXML.toc), it puts right what those get wrong for the app's races:
--
--  * The race icons. UI-CharacterCreate-Races (and -RacesRound, the big one) is 1024 by 256: rows
--    of 64-pixel icons, 16 to a row (Alliance men, Horde men, Alliance women, Horde women), so an
--    icon is 1/16 of the width. The module's coordinates take it for 32 to a row, which shows half
--    of the wrong face.
--  * Where the buttons go. The module keeps a place for every race it may one day have, so the
--    app's races sit apart with gaps between them; here each side's races fill its two columns
--    from the top.
--  * The backgrounds of races the game has none for (the module leaves Goblin and Worgen out, and
--    the game's UI_Goblin and UI_Worgen don't exist).
--  * The text of the races the module has none for, without which choosing one stops the screen
--    halfway (no background, the last race's colours).
--
-- RACES is the one list: each race's file string (ChrRaces.dbc), side and icon column, in the order
-- its side shows them. scripts/build-races.py checks that every playable race is in it.

local RACES = {
	{ "HUMAN",            "A", 0 },
	{ "DWARF",            "A", 1 },
	{ "NIGHTELF",         "A", 3 },
	{ "GNOME",            "A", 2 },
	{ "DRAENEI",          "A", 4 },
	{ "WORGEN",           "A", 5 },
	{ "HIGHELF",          "A", 6 },
	{ "DARKIRONDWARF",    "A", 7 },
	{ "ALLIANCEPANDAREN", "A", 10 },
	{ "BROKEN",           "A", 8 },
	{ "ORC",              "H", 3 },
	{ "SCOURGE",          "H", 1 },
	{ "TAUREN",           "H", 0 },
	{ "TROLL",            "H", 2 },
	{ "BLOODELF",         "H", 4 },
	{ "GOBLIN",           "H", 5 },
	{ "MAGHAR",           "H", 6 },
	{ "OGRE",             "H", 7 },
	{ "ZANDALARITROLL",   "H", 8 },
	{ "VULPERA",          "H", 9 },
	{ "HORDEPANDAREN",    "H", 10 },
};

-- The first button of each side (CharacterCreate.xml: Alliance 1 to 20, Horde 21 to 39, two to a
-- row, left then right).
local FIRST_SLOT = { A = 1, H = 21 };

RACE_ICON_TCOORDS = {};
RACE_VISUAL_SLOTS = {};
local nextSlot = { A = FIRST_SLOT.A, H = FIRST_SLOT.H };
for _, race in ipairs(RACES) do
	local name, side, column = race[1], race[2], race[3];
	local left, right = column / 16, (column + 1) / 16;
	local men = (side == "A") and 0 or 0.25;
	RACE_ICON_TCOORDS[name.."_MALE"] = { left, right, men, men + 0.25 };
	RACE_ICON_TCOORDS[name.."_FEMALE"] = { left, right, men + 0.5, men + 0.75 };
	RACE_VISUAL_SLOTS[name] = nextSlot[side];
	nextSlot[side] = nextSlot[side] + 1;
end

-- The game's backgrounds for the races it has none for; the module's SetBackgroundModel maps the
-- others itself.
local BACKGROUNDS = {
	GOBLIN = "Orc",       -- they start in Durotar
	WORGEN = "NightElf",  -- they start in Teldrassil
};
local moduleSetBackgroundModel = SetBackgroundModel;
function SetBackgroundModel(model, name)
	return moduleSetBackgroundModel(model, BACKGROUNDS[strupper(name or "")] or name);
end

-- The races the module has no text for.
RACE_INFO_ZANDALARITROLL = RACE_INFO_ZANDALARITROLL or "The Zandalari are the oldest of the trolls, the keepers of their people's history and the founders of the first troll empire. From the golden city of Dazar'alor on the islands of Zandalar their kings and prophets have led their kin for thousands of years, and no other trolls know the loa and the old magic as they do.";
RACE_INFO_VULPERA = RACE_INFO_VULPERA or "The vulpera are a nomadic people of the deserts of Vol'dun. Travelling in caravans, they live by their wits, by making use of whatever the sands leave behind, and by their loyalty to one another. Small and quick, they are far tougher than they look.";
RACE_INFO_ALLIANCEPANDAREN = RACE_INFO_ALLIANCEPANDAREN or "The pandaren come from the mist-shrouded land of Pandaria and from the great wandering turtle Shen-zin Su. They seek balance in all things: food and drink, rest and adventure, the mind and the body. Those who follow the Tushui, the way of discipline and right conduct, have joined the Alliance.";
RACE_INFO_HORDEPANDAREN = RACE_INFO_HORDEPANDAREN or "The pandaren come from the mist-shrouded land of Pandaria and from the great wandering turtle Shen-zin Su. They seek balance in all things: food and drink, rest and adventure, the mind and the body. Those who follow the Huojin, the way of bold and decisive action, have joined the Horde.";
