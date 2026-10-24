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
- **Racials** (learned at creation from `playercreateinfo_spell_custom`, which needs
  `PlayerStart.CustomSpells = 1` in worldserver.conf: the realm's ctl.sh sets it), as WoW: Forever has
  them since its beta build of 15 October 2026:
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
  each side's races in two columns (the Alliance's left, the Horde's right), two to a row from the
  top in the screen's order, the side by the game's `GetFactionForRace`, or by the race where the
  game gives none; their background is their start race's.
- **Blood elves** get a Dark Ranger skin (colour 19: the blood elf's fairest skin with its
  saturation turned down to a tenth, so grey, and red eyes).
- **Zoom** on the character creation screen (`SkyborneZoom.lua`): the mouse wheel (the app's pinch)
  zooms the preview in, up to two and a half times, and back out to the whole screen. 3.3.5's
  preview is the screen's ModelFFX, whose camera Lua can't move; a model frame draws its scene to
  fit its rectangle, so zooming makes that frame larger around where the character stands (the
  middle of the screen, a little above its middle), the panels kept over the whole screen
  (`CharacterCreateFrame` anchored to `GlueParent`). Showing the screen again starts zoomed out.
- **Gear button** on the character creation screen (`SkyborneGear.xml/.lua`, data in
  `SkyborneGearData.lua`): one button that cycles the preview between no gear (a leaf icon,
  INV_Misc_Leaf_01), the starting gear of the race and class selected (CharStartOutfit; its icon is
  the starting chest's, the shirt where a class starts without a chest piece) and champion gear (the
  class's top raid set: the class's own set with the highest item level, Tier 10's heroic 25-player
  pieces at 277, the damage dealers' set where a class has one for each role; its icon is the set's
  chest piece). The preview is dressed through the screen's model (`Undress` and `TryOn`), found by
  what it can do. 3.3.5's has none (its preview is a ModelFFX, dressed by the game from
  CharStartOutfit), so on this game the button shows and cycles, its icon following the mode, but the
  preview keeps the gear the game gives it, and the tooltip says so ("This game's preview can't be
  dressed yet"). Dressing 3.3.5's preview needs a change to the game's executable (PLAN.md, on hold).
  (The user's play of build 132 found it doing nothing, drawn in a square frame, its tooltip failing:
  `SetOwner`, a method 3.3.5's tooltips here don't have. Its tooltip is now the creation screen's
  own, `GlueTooltip_SetOwner` and `GlueTooltip_SetText`, and its icon has no frame; it was then
  hidden in 3.3.5, until the user asked for it shown between the sex buttons.) The Skyborne take
  their start race's outfit; a race with no start of its own for a class takes the first race's that
  has one. The race and class are their file names' (`GetSelectedClass` gives the name, file and
  position; the lists' third value is whether one can be picked, not an id). The button stands between the sex buttons: where the middle of the two was (3.3.5's: the
  configuration panel's top, 392 down), the
  male button moved to its left and the female to its
  right, 12 apart (their centres 50 either side of the middle). It takes the sex buttons' parent and
  is drawn over them, and has the leaf for its icon until its data gives one (a button with no
  texture isn't seen). Not yet seen in the game.
- **HD looks**: the HD blood elf takes its ears, jaw and eyeballs from a second skin texture
  (`...Skin00_NN_Extra.blp`, the skin row's second texture in CharSections). The patch makes each
  new skin's own and names it in its row, and gives the Skyborne's blood elf skins the blood elf's.
  The eye glow is the model's own (pale green, added over the eyes) for every skin.
- **NPC skins**: a skin the game gives only its NPCs (its face painted into it) has no face or overlay
  rows in CharSections, as the game always draws such an NPC from a texture baked beforehand. A look
  the game has to put together itself with one (Wrath Remix's Moratari in her dress and in her tent)
  asked for rows that weren't there and could crash the game; each such skin gets a face row for
  every face of its race and sex, and an overlay row, with no textures (its own face shows). Skins a
  player can choose keep the faces they have.

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
- As the world server starts, before it says it's ready (`src/Skyborne.cpp`: the app starts the
  game on that line, and the patch is made again when the realm changes), `src/SkybornePatch.cpp` makes the game's
  `Data/patch-Z.MPQ` from the client's own files (WOW_CLIENT_DIR): the tables with the rows changed,
  the textures recoloured (a palette's colours, compressed blocks' end colours: the same format and
  size; with HD looks, each skin's `_Extra` too, named in its CharSections row), the race icons, `GlueXML.toc` with `SkyborneCreate.xml` and `.lua`, the gear button's three files, the zoom's, and the narration. It's
  made again only when the client's archives or the module's files change (the stamp in
  `patch-Z.MPQ.skyborne`); a `patch-Z.MPQ` that isn't the Skyborne's is left alone.
  `Skyborne.ClientPatch = 0` (`mod_skyborne.conf`) takes it out.
- The game starts with the app's patched executable while the patch is in (the character screen's
  files load only there).

## Tests

- `tools/test_client.cpp`: the patch made for a game folder and read back (races, looks, racials,
  textures, icons, the character screen, a second start keeping it). Built and run here against the
  server's tables and ChromieCraft's HD character archive.
- `tools/test_zoom.py`: `SkyborneZoom.lua` in Lua 5.1 against stand-ins for the screen (the wheel
  growing the preview's frame around the character and back, its limits, the panels staying put,
  a fresh start when the screen is shown).
- `tools/test_gear.py`: `SkyborneGear.lua` and its data in Lua 5.1 against stand-ins for the screen
  (cycling, the icons, the items on the model, dressing again after a race or class is picked, the
  screen's own tooltip functions with tooltips that have no methods, as 3.3.5's, its place between
  the sex buttons and theirs, and, where the model can't be dressed, the button still shown and
  cycling with a tooltip that says so).
- `tools/test_screen.py`: `SkyborneCreate.lua` in Lua 5.1 against stand-ins for the game's screen,
  with the game's ten race buttons and with mod-worgoblin's twelve, and with no sides from the game
  (the races' own then): each side two to a row.
- CI's server test: the world server makes the patch from the server's tables (no textures there),
  and a High Order warrior starts in Northshire and a Windshaper shaman at Camp Narache, each with
  their racials and their side's language (Common, Orcish). Releases up to 1.0.236 gave the
  Skyborne no language and no Swords or Two-Handed Swords: the allied races' SQL, which the realm
  applies first, changes the same skill rows, and this module's updates looked for them exactly as
  they come (they're found by their stock races now); and the Skyborne were in the other side's
  language row of SkillRaceClassInfo too (a tiered row for learning it), which the server could
  read first and then teach nothing (each side's language is on its own row now). A character made
  then learns what it lacks at its next login (the core teaches missing start skills at every
  login).
