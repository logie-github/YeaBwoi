# mod-skyborne: the Skyborne

WoW: Forever's elves of the wind, playable in the app's realms (ChromieCraft and Wrath Remix), as two
races on the blood elf's body:

| Race | Id | Side | Starts | Classes |
| --- | --- | --- | --- | --- |
| Skyborne of the High Order | 13 | Alliance | Northshire (the Humans' start, quests and standing) | Warrior, Hunter, Rogue, Mage, Druid |
| Skyborne Windshaper | 14 | Horde | Camp Narache (the Tauren's start, quests and standing) | Warrior, Hunter, Rogue, Shaman, Druid |

Quests open to their start race are theirs too, and so are those of the race whose start a class of
theirs takes where the start race hasn't the class (the Night Elves' for the High Order's hunters and
druids: the pet and form quests; the Blood Elves' for the Windshapers' rogues). Class trainers for
those classes aren't in their start zones: Stormwind's and the Horde's cities have them.

Races 13 and 14 were the game's Naga and Broken, which only creatures use; in the game's tables they
move to 22 and 23.

## What they have

- **Looks**: the blood elf's model, faces, hair styles, voices and names. Skins: the blood elf's ten
  and six light, sky blues (Sky, Azure, Cerulean, Ocean, Glacier, Frost: colours 19 to 24). Eyes
  are blue or yellow, never the blood elf's green: each face comes twice (faces 1-10 blue-eyed,
  11-20 yellow-eyed). Hair: the blood elf's thirteen colours and nine more (Snow, Sky, Azure, Storm,
  Teal, Twilight, and the pinks Pink, Rose, Blossom: colours 13 to 21), beards with them.
- **Racials**, as WoW: Forever has them since its beta build of 15 October 2026:
  - Walk on Air (both): glide downward for 10 sec; instant, 90 sec cooldown.
  - Skysight (Windshapers): Elemental Blessing, run and mounted speed +10% for 15 min, not stacking
    with other speed increases; 0.5 sec cast while moving, 2 min cooldown.
  - Read Ley Line (High Order): Energized, Health and Mana regeneration +100% for 15 min; 2 sec cast
    while moving, 2 min cooldown.
  - Wind Blessed (both, passive): melee, ranged and spell haste +3%.
  - Elemental Insight (both, passive): damage to Elementals +5%.

  In WoW: Forever, Skysight and Read Ley Line last 15 minutes beside an elemental convergence or a
  ley line and 30 seconds elsewhere; Northrend's world has neither, so here they last 15 minutes
  everywhere.
- **Stats**: WoW: Forever's starting stats for every class are the class's own with Strength -1,
  Agility +1, Stamina -1, Intellect +1 (player_race_stats).
- **Druid forms**: the night elf's. **Totems**: the tauren's.
- **The intro**: the start race's flight over its home (Northshire, Mulgore) with the Skyborne's own
  narration (`data/client/intro.mp3`).
- **Character creation**: their icons are the blood elf's with blue skin, white hair and blue eyes;
  each side's races go down its own column; their background is their start race's.
- **Blood elves** get a Dark Ranger skin (colour 19: the blood elf's fairest skin with its
  saturation turned down to a tenth, so grey, and red eyes).
- **Gear button** on the character creation screen (`SkyborneGear.xml/.lua`, data in
  `SkyborneGearData.lua`): one button that cycles the preview between no gear (the fig leaf,
  INV_Misc_Leaf_01), the starting gear of the race and class selected (CharStartOutfit; its icon is
  the starting chest's, the shirt where a class starts without a chest piece) and champion gear (the
  class's top raid set: the class's own set with the highest item level, Tier 10's heroic 25-player
  pieces at 277, the damage dealers' set where a class has one for each role; its icon is the set's
  chest piece). The preview is dressed through the screen's model (`Undress` and `TryOn`), found by
  what it can do; if the screen has no such model the tooltip says so (and lists the frames it
  looked at) and the preview is left as it is. The Skyborne take their start race's outfit; a race
  with no start of its own for a class takes the first race's that has one. Not yet seen in the game.
- **No underwear on anyone**: the patch empties every race's and sex's underwear row (CharSections
  section 4, the NakedPelvisSkin/NakedTorsoSkin textures the game paints over a bare body), as the
  game's own rows for race 20 are; the body's skin shows. Armour is as it was.
- **HD looks**: the HD blood elf takes its ears, jaw and eyeballs from a second skin texture
  (`...Skin00_NN_Extra.blp`, the skin row's second texture in CharSections). The patch makes each
  new skin's own and names it in its row, and gives the Skyborne's blood elf skins the blood elf's.
  The eye glow is the model's own (pale green, added over the eyes) for every skin.

## How it's made

- `tools/build_data.py` reads the game's tables (the server's dbc folder) and the core's world SQL,
  and writes `data/sql/db-world/skyborne_world.sql` (the world database: the `*_dbc` tables the core
  reads over its DBC files, starts, skills, racials, quests, items and conditions open to the start
  race) and `data/client` (`dbc/*.txt`: the rows to change in the game's tables; `textures.txt`:
  which textures to make from which, and how). Its output is committed; run it again after changing
  it.
- `tools/build_gear.py` reads the same tables and writes the gear button's data
  (`data/client/Interface/GlueXML/SkyborneGearData.lua`: starting outfits, champion sets, icons).
  Its output is committed; run it again after changing it (`CHAMPION_SETS` in it picks a different
  set for a class).
- As the world server starts (`src/Skyborne.cpp`), `src/SkybornePatch.cpp` makes the game's
  `Data/patch-Z.MPQ` from the client's own files (WOW_CLIENT_DIR): the tables with the rows changed,
  the textures recoloured (a palette's colours, compressed blocks' end colours: the same format and
  size; with HD looks, each skin's `_Extra` too, named in its CharSections row), the race icons, `GlueXML.toc` with `SkyborneCreate.xml` and `.lua` and the gear button's three files, and the narration. It's
  made again only when the client's archives or the module's files change (the stamp in
  `patch-Z.MPQ.skyborne`); a `patch-Z.MPQ` that isn't the Skyborne's is left alone.
  `Skyborne.ClientPatch = 0` (`mod_skyborne.conf`) takes it out.
- The game starts with the app's patched executable while the patch is in (the character screen's
  files load only there).

## Tests

- `tools/test_client.cpp`: the patch made for a game folder and read back (races, looks, racials,
  textures, icons, the character screen, a second start keeping it). Built and run here against the
  server's tables and ChromieCraft's HD character archive.
- `tools/test_gear.py`: `SkyborneGear.lua` and its data in Lua 5.1 against stand-ins for the screen
  (cycling, the icons, the items on the model, dressing again after a race or class is picked, a
  model that can't be dressed).
- `tools/test_screen.py`: `SkyborneCreate.lua` in Lua 5.1 against stand-ins for the game's screen,
  with the game's ten race buttons and with mod-worgoblin's twelve.
- CI's server test: the world server makes the patch from the server's tables (no textures there),
  and a High Order warrior starts in Northshire and a Windshaper shaman at Camp Narache, each with
  their racials.
