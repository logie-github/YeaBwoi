# mod-worgen-goblin: the Worgen and Goblins in ChromieCraft's realm

The Worgen and Goblins of [mod-worgoblin](https://github.com/heyitsbench/mod-worgoblin) (the
AzerothCore module most realms use for them, AGPL-3.0, pinned in `warcraft/server-version.env`),
playable in the app's 3.3.5 ChromieCraft realm beside the allied races and the Skyborne (not on
Wrath Remix's realm: "Only chromie needs new races"):

| Race | Id | Side | Starts | Classes |
| --- | --- | --- | --- | --- |
| Goblin | 9 | Horde | The Valley of Trials (with the Orcs and Trolls) | Warrior, Hunter, Rogue, Priest, Death Knight, Shaman, Mage, Warlock |
| Worgen | 12 | Alliance | Shadowglen (with the Night Elves) | Warrior, Hunter, Rogue, Priest, Death Knight, Mage, Warlock, Druid |

What they have is mod-worgoblin's: their models (Cataclysm's), looks, voices, names, starting gear
and action bars, racials (the Goblins' Best Deals Anywhere, Better Living Through Chemistry, Pack
Hobgoblin, Rocket Barrage, Rocket Jump, Time is Money; the Worgen's Darkflight, Viciousness,
Aberration, Flayer and Two Forms), their side's language, the quests of their start
race's zone opened to them, their death knights' quests at Ebon Hold and the Goblins' trike vendor
and riding trainer, and their character creation scenes (Gilneas's and Kezan's).

mod-worgoblin is built as it is (its scripts: the Goblins' discount and Rocket Barrage); this module
puts the rest in the way the app's realm needs.

## How mod-worgoblin goes in

mod-worgoblin's own way is to copy 30 whole tables of the game over the server's and into the game,
replace four of the game's interface files and the race icon sheet, and apply its SQL to every
realm. In the app that would undo the HD looks', the allied races' and the Skyborne's changes to the
same tables and screen, put text an editor damaged into the game (quotes doubled, line breaks lost:
2000 spell tooltips), and give Wrath Remix the races. So:

- **The game's tables** (`data/client/dbc`, made by `tools/build_data.py` from the game's own tables
  and mod-worgoblin's): only what mod-worgoblin really changes, put on top of the tables the game
  already has (the Skyborne's format: mod-skyborne's `SkyborneFiles.cpp`, `ApplyChanges`):
  - its rows of the two races (looks, hair, features, start outfits, names, voices, barber styles,
    the race rows 9 and 12), renumbered where an HD look or the other modules may have rows of their
    own (looks 880000 on, outfits, hair and voices 8800 on, names 80000 on, emotes 88000 on);
  - its other new rows (its models' and items' displays, sounds, spells, items, icons) at its ids;
  - the race bits it adds to the game's rows (the reputations, skills, abilities, talent trees), OR'd
    in, and the few numbers it changes in the game's spells (Skinning's for the Worgen's Flayer) and
    helmets (which hide the Worgen's ears); never its copy of the game's text;
  - of its character tables, only the Worgen's and Goblins' rows (its tables also carry another
    project's rows for other races);
  - first, the game's own creatures of races 9 and 12 (the goblins of Azeroth's ports and the fel
    orcs of Outland: their looks, hair, features, voices and race rows) moved to races 28 and 29, as
    the allied races and the Skyborne move theirs, so they look as they always have;
  - the numbers mod-worgoblin's Worgen leave out filled in the way the game's own tables do (features
    0 and 16 none, the women's hair style 0 none): the screen counts a slider's rows and asks for each
    number from 0 up, and a missing one is the look-up that crashed it (crash report #34).
- **The game's patch** (`src/WorgenGoblinPatch.cpp`): `Data/patch-U.MPQ`, made as ChromieCraft's
  world server starts (as its configuration is about to be read, before the allied races' patch-V,
  the Skyborne's patch-Z and Wrath Remix's patch-Y, which are made from the game with it in and so
  carry its rows), from the tables above, mod-worgoblin's models, textures, sounds and icons (laid out
  by `tools/package.py` as the server is packaged: they're the game's, never in this repository), and
  the character creation screen (below). Made again only when the client, this module or those files
  change (the stamp in `patch-U.MPQ.worgengoblin`). On Wrath Remix (`WorgenGoblin.Enable = 0`, set by
  ctl.sh) it's set aside as `patch-U.MPQ.aside` and put back for ChromieCraft.
  `WorgenGoblin.ClientPatch = 0` takes it out.
- **The character creation screen**: the game's own screen files stay (mod-worgoblin's are the game's
  with a few changes). `data/client/Interface/GlueXML/WorgenGoblinCreate.lua` and `.xml`, read after
  the game's and before the allied races' and the Skyborne's code: race buttons up to 18 (the others'
  code makes 16: with these two races there are 18), the Worgen's and Goblins' icons from a sheet of
  their own (`UI-CharacterCreate-Worgoblin` and `-WorgoblinRound`, cut out of mod-worgoblin's sheet:
  the game's sheet has no column left, the allied races and the Skyborne have its free ones), their
  scenes' music and lights; their texts (`WorgenGoblinStrings.lua`) from mod-worgoblin's
  GlueStrings.lua. The layout is the allied races' (each side's races in two columns).
- **The world server's tables** (`data/server/worgen_goblin_dbc.sql`, made by `tools/build_data.py`):
  the same changes for the tables the world server reads (their `*_dbc` tables), run by
  `src/WorgenGoblin.cpp` at every start of ChromieCraft's world server, after every module's SQL
  is in and before the tables are read: new rows put in, race bits OR'd into the rows there. The
  allied races' and the Skyborne's SQL write whole rows of the same reputation tables, so whichever
  went in last would otherwise take the others' races out; their reputation rows merge the same way
  now (`tools/build_data.py` in both; until 1.0.328 the Skyborne's took the allied races' standing
  out of 98 reputations, or the other way round, whichever was applied last). mod-worgoblin's own
  `*_dbc` SQL isn't used (it replaces whole rows, and leaves the start outfits out).
- **The rest of its SQL** (the races' starts, stats, gear, action bars, skills, racials' scripts, the
  quests opened to them, its death knights' quests, the trike vendor: `tools/package.py`) goes into
  this module's `data/sql/chromiecraft/world` as the server is packaged, which ctl.sh puts in
  ChromieCraft's world database's update list alone; mod-worgoblin's own `data/sql` isn't in the
  package. Its death knights' quests were written for an older core: the columns this core's tables
  haven't are taken out of them (quest_template's RewardBonusMoney; creature_template's models and
  scale, which go into creature_template_model, and its trainer and immunity columns), and
  creature's id1 is this core's id (build 1.0.328's server test stopped on them). Its optional
  class trainers (templates only) aren't used.
- Its message at every login ("This server is running the Worgoblin module") is off
  (`Announce.enable`, set by ctl.sh).

## How it's made

- **`tools/build_data.py STOCK_DBC_DIR WORGOBLIN_DIR CORE_DIR`**: the server's dbc folder (the game's
  own tables: the data.zip in `warcraft/server-data.env`), mod-worgoblin at the pinned commit and the
  core's source (the `*_dbc` tables' columns) → `data/client/dbc/*.txt` and
  `data/server/worgen_goblin_dbc.sql`. Committed; run again when the pin moves.
  `tools/dbc_types.json`: the 30 tables' fields for build 12340 (from WoWDBDefs).
- **`tools/package.py WORGOBLIN_DIR OUT`** (run by `scripts/package-azerothcore.sh`; the compile keeps
  mod-worgoblin's `data/patch` for it): its files for the game, its race texts and its SQL, into the
  package.

## The starting areas (Gilneas, Kezan and the Lost Isles)

The user: Gilneas's and Kezan's starting areas from Cataclysm, "a very faithful port that doesn't
overwrite the old 3.3.5 content", the two races started there and joined to Wrath's world after them;
"for any other character than worgen or goblin the world and quests and everything stay as is". It's
being built in stages (`warcraft/PLAN.md`); `WorgenGoblin.StartingAreas` is off until their creatures
and quests are in. So far:

- **Their maps**, from Cataclysm Classic's files (`tools/zones/common.py` names the build, from
  wago.tools), made for 3.3.5a by [wotlkconv](https://github.com/Bar3b0n3s/Converter) (the commit
  `warcraft/zones-version.env` pins): Gilneas (map 654), the Lost Isles with Kezan (648), and the maps
  whose terrain Cataclysm swaps into them as the story goes. 3.3.5a can't swap a map's terrain, so each
  swap is made a whole map of its own (its base's tiles where it has none: 638, Gilneas as it is until
  quest 14222, where the Worgen start; 655 and 656; 659 and 661 for the Lost Isles), the player to be
  moved between them as Cataclysm swaps them. Two things the converter leaves that 3.3.5a can't take
  are fixed after it: the empty tiles' flag in a map's tile list, and Cataclysm's liquid types 3.3.5a
  hasn't (made its own of the same kind). Their ids are free in 3.3.5a's tables, and kept.
- **Everything they place** (`tools/zones/files.py`): the models, buildings and textures, with
  everything those use, read from the files themselves; the world's textures at most 512 across (a
  32-bit game on a phone), the interface's as they are; their minimap pictures, world maps and their
  discovered parts, loading screens, music and ambience (Ogg Vorbis, as mod-worgoblin's own voices).
  `data/zones/files.tsv` lists them by Cataclysm Classic's file ids (never the files: those are the
  game's).
- **Their rows in the game's tables and the server's** (`tools/zones/build_tables.py`,
  `data/zones/client/dbc`, `data/zones/server`): the maps, their 92 areas, lights (3.3.5a's own
  layout: its light positions, colour and number bands, checked on the lights both games have), the
  areas' music, intro music, ambience and sounds, the buildings' areas, the world maps and their
  discovered parts, the loading screens. Every row is a new one: a Cataclysm id that 3.3.5a or another
  of the realm's modules has for something else is given a new one (and what names it is renumbered);
  one of 3.3.5a's that's the same (the same sound files) is used as it is. Exploration bits past
  3.3.5a's 4096 get free ones.
- **The package** (`tools/zones/package.py`, `scripts/package-zones.sh`): the converted files, and the
  world server's data for the seven maps (AzerothCore's own map, vmap and mmap tools over a stand-in
  game of them), published by CI as the release `cata-zones-<hash>`. Files with the same bytes (a terrain
  swap's tiles that are its base's) are kept once and named under each path.
- **In the realm** (`src/WorgenGoblin.cpp`, `src/WorgenGoblinPatch.cpp`, `ctl.sh zones_data`), with
  the package installed (`files/zones`) and `WorgenGoblin.StartingAreas` on: the patch-U the realm
  makes has their files (only those the game hasn't: none of the game's own is replaced), their rows
  (after mod-worgoblin's) and their maps' lines in the game's minimap index; the world server's data
  has their maps (never in place of a file that's there); the world's tables have their rows; the two
  races' new characters start in Gilneas City and Kezan (death knights at Ebon Hold, as ever). No other
  race's character is let onto their maps (a game master's is). Off, or without the package: their
  rows go, and the two races start with the Night Elves and the Orcs as before.

Next: their creatures and objects, quests, items and spells (the Cataclysm Preservation Project's
world, `The-Cataclysm-Preservation-Project/TrinityCore`), the scripts and phases, and the way into
Wrath's world after them (Worgen to Darnassus, Goblins to Orgrimmar), each with new ids only.

## Tests

- `tools/test_screen.py`: `WorgenGoblinCreate.lua` in Lua 5.1 against stand-ins for the game's
  screen, with the allied races' and the Skyborne's code after it, as the game loads them: 18 buttons,
  the Worgen's and Goblins' icons from their sheet and the others' from the game's as the sex and race
  change, their music and lights, each side's nine races in its two columns. CI runs it.
- `tools/test_patch.cpp`: the patch made for a stand-in game (the server's tables as the game's) and
  read back: the two races, the creatures moved to 28 and 29 (648 goblin and 130 fel orc looks), the
  race bits, the racials' spells, the game's own spell texts kept, the screen's files in its list, the
  icons, models and scenes, a second start keeping it; then the allied races' and the Skyborne's
  changes applied over its tables: every race there, none in another's place, no two looks with one
  id, Stormwind's reputation with the Worgen's, void elves' and High Order's bits. Built and run here.
- mod-allied-races' `tools/check_looks.py` on the tables as the game reads them (the game's, this
  patch's, the allied races' and the Skyborne's changes): the same problems as without this patch (36,
  on the server's plain tables: none of the Worgen's or Goblins'); before the gaps were filled, five
  of the Worgen's (features 0 and 16, the women's hair style 0). Run here.
- The world server's SQL against MariaDB 11.4 with the core's `*_dbc` tables and the allied races'
  and the Skyborne's SQL, in every order and each applied again: the same rows every time, every
  module's race bits in them. Run here.
- CI's server test: the patch made before the allied races' (30 tables), the rows in the world's
  tables, races 9 and 12 playable with their looks, the capitals' reputations with every race's bits,
  a Worgen and a Goblin warrior at their starts with their racials and their side's language; on
  Wrath Remix, the patch set aside and none of it in its world; back on ChromieCraft, the patch back.
  With the starting areas' package (the zones job's): their maps in the server's data with paths for
  each, their files in the patch, their seven maps and 92 areas in the world's tables; a Worgen warrior
  starts on map 638 in Gilneas City and a Goblin warrior in Kezan (648), a Human and an Orc where they
  always have; the game's own creatures, objects, quests, items, scripts and other races' starts as
  they were; a Human put on Gilneas's map is taken home as it logs in; off again, their rows gone and
  the starts back.
- `tools/test_patch.cpp` with `WG_ZONE_TABLES`: a stand-in package's files in the patch, one the game
  has kept as the game's, one that's another's stored once under both names, the minimap index's
  lines, the seven maps and 92 areas added to the game's tables with the races' changes still in;
  without the package again, its files out. Built and run here.
- The starting areas' converter run here on all seven maps: AzerothCore's map_extractor takes all 241
  tiles, vmap4_extractor and vmap4_assembler the models and buildings, mmaps_generator the paths.
- Its SQL, as `tools/package.py` writes it, applied twice to a world database made from the pinned
  core's base and updates (MariaDB 11.4), with the allied races' and the Skyborne's: no error. Run
  here.
- Not tested: the game itself (the models drawn, the looks, the screen on a phone): nothing here
  runs the 3.3.5 client.
