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
  package. Its optional class trainers (templates only) aren't used.
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
- Not tested: the game itself (the models drawn, the looks, the screen on a phone): nothing here
  runs the 3.3.5 client.
