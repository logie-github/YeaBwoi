# mod-forever-quests: WoW: Forever's new quests

WoW: Forever's new quests that take place in the 3.3.5 world as it already is, on ChromieCraft's
realm only. Each quest's givers, enders, the creatures it sends you after and the creatures that
drop its items are already in AzerothCore's world, and none of its objectives needs a script.
That is 38 of Forever's 889 new quests (ids 86000 and up that the core hasn't). Their words are
Forever's own: description, objectives, progress and hand-in texts as Wowhead's Forever
database has them.

| | |
| --- | --- |
| Realm | ChromieCraft (`ctl.sh`, `realm_sql`: `data/sql/chromiecraft/world` into `acore_world` alone); Wrath Remix has none of it |
| Ids | Forever's own: quests 86576 to 99158, items 247834 to 286355 |
| Races | Forever's race masks: an Alliance quest is every Alliance race's, the Draenei's, void elves' and Lightforged's included; a race the realm adds goes with the race whose start it takes (the Skyborne of the High Order with the Humans, the Windshapers and Zandalari with the Tauren, the nightborne and blood elves with the Forsaken, the void elves, Lightforged and Draenei with the Night Elves) |

## The quests

| Quest | Id | Zone | Side | Level | Gaps in the data |
| --- | --- | --- | --- | --- | --- |
| Nibbled-On Book | 91741 | Elwynn Forest | Alliance | 2 |  |
| Fang of Githyiss | 97236 | Teldrassil | Alliance | 5 |  |
| Soft Saber Pelts | 87288 | Teldrassil | Alliance | 7 |  |
| Dawn in the Mountains | 99158 | Dun Morogh | Alliance | 8 | money estimated |
| Stolen Blasting Powder | 95213 | Dun Morogh | Alliance | 10 | money estimated |
| Stolen Blasting Powder | 95214 | Dun Morogh | Alliance | 10 | money estimated |
| The Sisterhood of Elune | 98391 | Darnassus | Alliance | 10 | money estimated |
| Crown of the Earth | 98046 | Teldrassil | Alliance | 11 | money estimated |
| Crown of the Earth | 98065 | Teldrassil | Alliance | 11 | money estimated |
| Business in Auberdine | 97894 | Darkshore | Alliance | 17 | money estimated, experience estimated |
| Expanding Horizons | 97914 | Stormwind City | Alliance | 17 |  |
| Making Do | 97926 | Darkshore | Alliance | 17 |  |
| It's All Fun and Games Until... | 98042 | Darkshore | Alliance | 17 |  |
| Show of Force | 98407 | Redridge Mountains | Alliance | 17 | money estimated |
| Unrequited Love | 98461 | Wetlands | Alliance | 21 |  |
| Ira's Dagger | 96137 | Duskwood | Alliance | 24 | money estimated; no hand-in text |
| Return the Statuette | 98189 | Wetlands | Alliance | 25 | money estimated; no hand-in text |
| Ingredients for the Forge | 86776 | Loch Modan | Alliance | 30 |  |
| The Shamed Lieutenant | 98219 | Stormwind City | Alliance | 30 | money estimated; no hand-in text |
| Pristine Pesterhide Pelts | 98155 | Thousand Needles | Alliance | 31 | no hand-in text |
| Slowing the Orc Advance | 86757 | Ashenvale | Alliance | 32 | no hand-in text |
| Talk of the Town | 98463 | Stranglethorn Vale | Alliance | 33 | money estimated, experience estimated; no hand-in text |
| Carved Centaur Totems | 93224 | Desolace | Alliance | 40 | money estimated, experience estimated; no hand-in text |
| Crocs of the Sky | 98072 | Wetlands | Both | 27 | money estimated; no hand-in text |
| For Further Study | 98313 | Ironforge | Both | 32 | money estimated, experience estimated; no hand-in text |
| Thunderhorn's Report | 98435 | Mulgore | Horde | 10 |  |
| Wild Eyes | 91920 | Silverpine Forest | Horde | 11 |  |
| Return to Quinn (Again) | 91921 | Silverpine Forest | Horde | 11 | money estimated |
| Hides for the Forsaken | 97558 | Tirisfal Glades | Horde | 11 |  |
| Whuut's the Rush | 97275 | Orgrimmar | Horde | 15 |  |
| Prompt Potion Runner | 97891 | Undercity | Horde | 16 |  |
| Arugal's Folly | 98298 | Silverpine Forest | Horde | 16 |  |
| Bruised Pride and Lion Hides | 95494 | The Barrens | Horde | 18 |  |
| Watching the Roads | 95981 | Silverpine Forest | Horde | 20 |  |
| Stop the Spread | 98299 | Silverpine Forest | Horde | 20 |  |
| Bloodfury Trinkets | 86576 | Stonetalon Mountains | Horde | 26 | no hand-in text |
| Stop the Screeching | 98070 | Thousand Needles | Horde | 31 | no hand-in text |
| Carved Centaur Totems | 93225 | Desolace | Horde | 40 | money estimated, experience estimated; no hand-in text |

"Money estimated": Wowhead's data has no rewards block for the quest (no money or experience), so
its money is the median of what the core's quests of the same level and experience pay.
"Experience estimated": wow4ever.quest hasn't it either, so it's the 3.3.5 step for a quest of
its kind (a delivery 2, a quest with objectives 5). "No hand-in text": Wowhead's data has none;
the quest is handed in without words.

## Where everything comes from

- **Which quests, who gives and ends them, objectives, item sources, prerequisites, races,
  levels**: [QuestieDB](https://github.com/Questie/QuestieDB)'s Forever database (`data/Forever`
  and its Forever corrections: the generated delta-base, traces and authored fixes, in its own
  order), read as data by `tools/lualit.py` (no Lua is run).
- **The words, objectives' counts, rewards, money, experience, reputation**: each quest's page on
  [foreverchanges.pro](https://foreverchanges.pro/quests) (Wowhead's Forever database as the site
  shows it; beta client 1.60.1.70532), read by `tools/foreverchanges.py`. Wowhead itself turns
  away scripted reading. Quest text © Blizzard Entertainment.
- **Experience the page hasn't**: wow4ever.quest's pages (`data/forever/experience.json`).
- **The new reward items' stats**: Wowhead's tooltips (`data/forever/reward_items.json`).
  Shipwright's Satchel is Forever's 6-slot reagent bag; 3.3.5 has no reagent bags, so it's a
  plain 6-slot bag here.
- **Looks of the new items**: the 3.3.5 item display with the same icon (ItemDisplayInfo.dbc), the
  one the core's items of the same slot use first.
- **Drop chances**: not in any source. A quest item drops at 60% from one or two kinds of creature
  and 35% from more; an item that starts a quest drops for anyone who hasn't taken it, always
  from a single creature, 8% from several.

## What isn't here, and why

`data/forever/report.json` lists every other new quest with its reasons. In short:

- **New zones and dungeons** (left out on purpose): Zephras Isle, Shen'dralas, Riverglades, the
  Hall of Thanes, the Ruins of Lordaeron, Excavation Site: Wetlands, the City of Dalaran.
- **New NPCs** in the old zones: 3.3.5 has nothing to draw them with. Their models aren't in
  any readable source (the Forever client's Creature table covers only companion pets).
- **Items no creature drops in the data**, quests whose ender or starter the data lacks.
- **Scripts**: places to explore, spells to cast, objects to use, kill credit, duels.
- **Class quests** (3.3.5's class training has its own), **Crafting** (Craftsman's Writs) and
  **Camping** (Forever's profession).

## How it's made

    python3 tools/forever.py import QUESTIEDB_DIR WORLD_SQL_DIR PAGES_DIR
    python3 tools/forever.py build DBC_DIR WORLD_SQL_DIR

`import` picks the quests and writes `data/forever/quests.json` and `report.json` (fetching the
quests' pages into PAGES_DIR, a cache); `build` writes
`data/sql/chromiecraft/world/forever_quests.sql` (quests, their givers and enders, items, drops;
safe to run again). WORLD_SQL_DIR: the core's `data/sql/base/db_world` at the realm's commit
(`warcraft/server-version.env`); DBC_DIR: the server data's `dbc` (`warcraft/server-data.env`).
Both outputs are committed.

## Tests

- `tools/test_forever.py` (CI): the page reader on a stand-in page; the races each Forever race
  mask opens; the committed SQL against its data (each quest's words, a giver, an ender, every
  new item it names made).
- Checked here: the SQL in MariaDB 10.11 on the core's base world tables, applied twice.
- CI's server test: all the quests in ChromieCraft's world, none in Remix's, and not a word from
  the world server about any of them.
- Not checked: the quests played in the game.
