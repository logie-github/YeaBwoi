# mod-ralia: Ralia's mod

Ralia's character textures in the game, from your own copy of Ralia's patches (made for the game of
today), while the launcher's **Game options → Ralia's mod** is on. Both realms (it's the client's
look). Nothing of Ralia's is in this repository or the app: the app reads the zip you pick on the phone.

## Turning it on and off

**Game options → Ralia's mod**. The first time, it asks for the zip of Ralia's patches; the app keeps
the zip's `Character` folder (`files/ralia/files`) and nothing else from it. **Choose the zip again**
replaces them. It takes effect at the realm's next start: the world server makes the game's patch as it
starts (a few seconds to a minute the first time; again only when the zip, the client or the realm
changes), and takes it out while the option is off. Turning it off also takes the patch out of your
client at once.

`Ralia.Enable = 0` in the realm's `modules/mod_ralia.conf` keeps it out whatever the option says. To take
the module out of the realm altogether, delete this folder: nothing else needs it (the other race
modules only leave its patch out of what they're made from).

## What it changes

The skin and the pelvis and torso textures of the game's character table (`CharSections`, sections 0
and 4) for the races in the game: the eight of 1.x but the gnomes, the blood elves, the Draenei, the
Worgen and Goblins (`mod-worgen-goblin`), and the allied races (`mod-allied-races`: void elves,
nightborne, Lightforged Draenei, Zandalari Trolls). The Skyborne (`mod-skyborne`) take the blood elves':
their skins are the blood elves' recoloured, made after this patch from the game with it in. The
gnomes, the murlocs and the naga are left as they are, as are the races Ralia's patches have that the
game doesn't (Pandaren, Kul Tirans, Dark Iron dwarves, Highmountain Tauren, the orcs' clan skins).

- **Each texture takes Ralia's file of the same name**: the game's names without their underscores or
  their case are today's, with `_hd` after the newer models' (`HumanFemaleSkin00_03.blp` takes
  `humanfemaleskin00_03_hd.blp`). The allied races' colours are numbered as this game's tables have them,
  not as today's files are: `data/client/sources.txt` (made by `tools/build_data.py` from
  `mod-allied-races/data/retail/races.json` and today's file names, `data/retail_paths.json`) names the
  file each takes. The Worgen's pelvis and torso textures (named `_regular` and `_dk`) take Ralia's of
  their kind.
- **The game's size and format**: a skin of today is twice as wide as it is high, the body (classic's
  layout) in its left half, which is what's used; each texture is made the size the game's is, and
  palettized (256 colours), as 3.3.5 composes characters from. A skin keeps the game's own alpha.
- **A colour Ralia's patches don't have** (the allied races' newer skin colours, the creatures' skins of
  the playable races) takes the change Ralia's file makes to the nearest colour that has one: where that
  colour's file differs from the game's, this colour's picture is changed as much, so its own colour
  stays. The allied races' tattooed skins take their plain colour's change the same way, so their tattoos
  stay. A colour whose nearest file is another picture altogether (more than a twentieth of it changed)
  is left as it is.

## The patch

`Data/<locale>/patch-<locale>-Z.MPQ` (with `patch-<locale>-Z.MPQ.ralia`, what it was made from),
the last archive the game reads, so its textures are drawn whatever an HD look, `patch-U` (Worgen and
Goblins) or `patch-V` (allied races) has at the same paths. It's made from the game as those leave it,
as the world database's own tables are loaded (after `patch-U` and `patch-V`, made as the configuration
is read), and before the Skyborne's `patch-Z` and Wrath Remix's `patch-Y`, which it isn't made from. A
`patch-<locale>-Z.MPQ` that isn't this module's is left alone, and then the mod isn't in the game.

## Files

| File | What it is |
| --- | --- |
| `src/Ralia.cpp` | The world server's part: makes or takes out the patch as the world starts. |
| `src/RaliaPatch.cpp` | Which file each texture takes, and the patch. |
| `src/RaliaImage.cpp` | Resizing, the nearest colour's change, palettized BLP2 textures. |
| `data/client/sources.txt` | The allied races' colours' files (`tools/build_data.py`). |
| `data/retail_paths.json` | Today's file names of the allied races' textures (`tools/build_data.py --fetch`, from wago.tools). |
| `tools/test_patch.cpp` | The patch made from a stand-in game and stand-in files (CI's unit tests). |

Tested: the patch's pieces against stand-ins (`tools/test_patch.cpp`), the change carried to another
colour against Ralia's own file for it (a void elf colour: within 6 levels a channel where Ralia's
changes are), and a dry run of the real table and files (all the playable races' textures but the
gnomes'; about 10 seconds on a PC). Not seen in the game: nothing here runs the 3.3.5 client.
