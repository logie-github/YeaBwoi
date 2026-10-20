# mod-allied-races: the void elves and the nightborne

Blizzard's allied races of today, playable in the app's 3.3.5 realms (ChromieCraft and Wrath Remix;
Turtle WoW is another game and untouched), in their own models, looks and voices:

| Race | Id | Side | Starts | Classes |
| --- | --- | --- | --- | --- |
| Void Elf | 15 | Alliance | Ammen Vale (the Draenei's start, quests and standing: the Exodar) | Warrior, Hunter, Rogue, Priest, Death Knight, Mage, Warlock |
| Nightborne | 16 | Horde | Deathknell (the Forsaken's start, quests and standing: Undercity) | Warrior, Hunter, Rogue, Priest, Death Knight, Mage, Warlock |

(The classes are today's that 3.3.5 has: no monks, demon hunters or evokers.) Races 15 and 16 were
the game's Skeleton and Vrykul, which only creatures use; in the game's tables they move to 24 and
25, as the Skyborne moved 13 and 14 to 22 and 23.

## What they have

- **Models**: Blizzard's own player models of today (`character/voidelf/...`,
  `character/nightborne/...`: the models, their skeletons with the parent skeletons, their
  animations), made models the 3.3.5 game draws by `tools/m2.py` (below). Not the blood elf's or
  the night elf's body.
- **Size**: both sexes use a 0.9 display scale (10% smaller) in character creation and in the
  world. The client patch and server tables use the same value; existing worlds receive the update.
- **Looks**, from today's customization tables, on 3.3.5's five sliders:
  - *Skin colour*: every skin colour a player picks today (void elves 30, among them the high elf
    skins; nightborne 10, each with each of the seven body tattoos: 70).
  - *Face*: every face with every eye colour, cycling face 1 eye 1, face 1 eye 2... (void elves
    28 faces x 9 eye colours = 252; nightborne 12 x 6 = 72). The void elves' nine eye colours are
    the ones a player picks without a demon hunter's blindfold (3.3.5 holds at most 255 faces). A
    face today has textures for the skins of its kind (the eight void faces for the void skins,
    the twenty high elf faces for the high elf skins): on a skin of the other kind, a face number
    shows that kind's face of the same rank, so every face draws on every skin.
  - *Hair style*: every style.
  - *Hair colour*: every colour a player picks today (16 each).
  - *Feature*: void elf men their facial hair x void tentacles (8 x 7: today's Tentacles switch
    gives each hair style its own tentacle shape; here the shapes are variants named for the
    style they were made for, and any goes with any style), void elf women their earrings x
    tentacles (5 x 7); nightborne men every chin decoration x mustache x jaw jewellery (160),
    nightborne women every jaw jewellery x earrings x necklace (160).
  - Left at their first choice (3.3.5 has no slider for them): ears, eyebrows, eye style and
    eyesight, face tattoos, face jewellery, headdresses, hair decorations, luminous hands. Faces
    differ by texture alone (today's faces also reshape the head with bone files, which 3.3.5
    hasn't).
- **Voices**: today's (VocalUISounds: the words at the UI's moments; EmotesTextSound: the
  emotes 3.3.5 has), as MP3s.
- **Names**: today's name generator's.
- **Racials** (learned at creation from `playercreateinfo_spell_custom`), as today's where 3.3.5
  allows:
  - Void elves: **Spatial Rift** (tear a rift up to 15 yards ahead; use again within 6 sec to
    teleport through it; 3 min cooldown from when the rift is used or closes:
    `src/AlliedRacesSpells.cpp`), **Entropic Embrace** (abilities have a 10% chance, at most every
    30 sec, to empower you: +5% damage and healing for 12 sec; today's "as Shadowfrost" has no
    3.3.5 equivalent), **Chill of Night** (Shadow damage taken -1%), **Preternatural Calm** (no
    spell pushback), **Cantrips** (a mailbox for 10 min, 10 min cooldown: MOLL-E's). Ethereal
    Connection (transmogrification cost) has nothing to act on in 3.3.5 and isn't here.
  - Nightborne: **Arcane Pulse** (Arcane damage to enemies within 8 yards, scaling with level, and
    a 50% slow for 12 sec; 3 min cooldown), **Arcane Resistance** (Arcane damage taken -1%),
    **Magical Affinity** (magical damage +1%), **Ancient History** (Inscription +15).
- **Stats**: their kin's (the blood elves' for the void elves, the night elves' for the
  nightborne).
- **Starts**: the Draenei's and the Forsaken's places for every class (death knights at Ebon
  Hold); the start race's starting gear and action bars (the Humans' for void elf rogues and
  warlocks, the Blood Elves' for nightborne hunters, which the start race hasn't).
- **Quests**: the start race's own quests in its first zones are **cloned** for the new race
  (`tools/quests.py`, `data/sql/db-world/allied_races_quests.sql`): the same quests from the same
  people, with the quest-givers taking in who the player is (a void elf among the Exodar's
  survivors, a nightborne sent to learn the Forsaken's ways) before sending them down the same
  road; the originals stay the start race's alone. Void elves: the seventeen Draenei-only quests
  of Ammen Vale, Azuremyst and Bloodmyst (You Survived!, Replenishing the Healing Crystals, Rescue
  the Survivors!, Medicinal Purpose, An Alternative Alternative, The Prophecy of Velen, A Hearty
  Thanks!, the hippogryph and the prophecy chains); nightborne: Rude Awakening and the ride to the
  Undercity. Every other quest, item and condition of the start race (and of the
  classes' template races) is open to the new race as it is. Scripted events bound to an
  original quest's id don't fire for its clone.
- **The intro**: the start race's flight over its home with each race's own narration (the user's
  MP3s, `data/client/ve.mp3` and `nb.mp3`).
- **Character creation**: their portraits (Wowhead's race icons, `data/client/icons`) in the last
  column of the race icons; each side's races in two columns, two to a row from the top
  (`AlliedRacesCreate.lua`, which the Skyborne's screen code lays out again after, the same way);
  the Draenei's background for the void elves and the Night Elves' for the nightborne (each
  scene's camera is framed for its race's height: the Forsaken's, which they had first, cut off the
  nightborne's heads); their texts.

## How it's made

- **`tools/races.py CSV_DIR`** reads Blizzard's tables of today (wago.tools' CSVs: ChrRaces,
  ChrModel, ChrCustomization*, TextureFileData, SoundKitEntry, VocalUISounds, EmotesTextSound,
  NameGen, and the four displays' CreatureDisplayInfo rows) and writes
  **`data/retail/races.json`** (committed): for each race and sex the model's files, the geoset
  policy, skins, faces, eyes, hair, features, voices and names, by file id. The other tools work
  from it alone.
- **`tools/build_data.py DBC_DIR WORLD_SQL_DIR`** reads the game's tables (the server's dbc
  folder) and the core's world SQL and writes `data/sql/db-world/allied_races_world.sql` (the
  `*_dbc` tables the core reads over its DBC files, starts, skills, racials, standing, the quests
  opened) and `data/client/dbc/*.txt` (the rows to change in the game's tables, applied the
  Skyborne's way). Committed.
- **`tools/quests.py WORLD_SQL_DIR`** writes the cloned quests' SQL and
  `data/retail/cloned_quests.json`. Committed.
- **`tools/package.py fetch CACHE DIR`** (run by `scripts/package-azerothcore.sh` as the server is
  packaged; CI caches what it fetched by the list of files) fetches the game's files by id from
  wago.tools and writes DIR (`data/files` in the package: never in the repository), laid out as
  the game's patch, with `alliedraces.files` listing it:
  - **`tools/m2.py`**: today's model (format 274) made 3.3.5's (264): the child's bones with the
    parent skeleton's sequences (the child's own over them), every track with an entry a
    sequence, the sequence lookup a hash table as the game reads it, external animations kept
    in `.anim` files (the newer files' AFM2 event, AFSA attachment and AFSB bone streams combined
    with each track's offsets relocated; global-loop tracks stay in the model); the body's texture coordinates stretched across (today's body texture is
    twice as wide as tall, the head in its right half; 3.3.5 composes a square), the head's into
    the face region, the eyes' (a texture of their own today) into the FaceUpper strip; the
    geosets 3.3.5's tables choose from kept whole (hair, features, armour); the body parts today's
    models keep in groups 3.3.5 doesn't know (the head, mouth, eyes and eyebrows, the toes, torso
    and forearms: groups 20 and up, which the game doesn't draw) folded into geoset 0, which it
    always draws, as the HD look's models keep their body; of other groups only the first
    variant; **the mesh cut down to 65535 indices** (`tools/decimate.py`: 3.3.5 reads a section's
    triangle start as 16 bits, so every section has to begin within the first 65535 indices, and
    today's models have 85,000 to 140,000; quadric-error half-edge collapses, which keep every
    vertex's texture coordinates, normal and bones and leave boundaries and seams alone, take
    from the gear first, then the features and the hair, the body last: the log says how much of
    each); every batch one texture (body, cape, hair; the tentacle and jewellery textures fixed); mesh
    sections of at most 64 bones, each section's bone combo naming the very bones its vertices
    carry (the game draws with the combo); cameras in 3.3.5's layout. `<Name>.layout.json` tells the
    texture tool where the head went. **`tools/check_m2.py`** checks a model the way the game
    would trip over it.
  - **`tools/textures.py`**: the skins (512 x 512 palettized, as the HD looks': 3.3.5's compositing
    needs palettized textures; the body half of today's), FaceLower (the head's used part, turned
    if that fits better, 256 x 128; nightborne with the eye colour's glow painted in), FaceUpper
    (the eyes, 256 x 64), underwear (256 x 128), hair (as it is, DXT). `tools/blp.py` reads and
    writes BLP2 (palette, DXT1/3/5). `tools/names.py` names them for both the textures and the
    tables.
  - the voices as MP3s (ffmpeg). Every converted model is checked, including all external
    animation offsets, before the complete package manifest is written. A failed rebuild clears
    the old manifest so partial files cannot be mistaken for a complete package.
- As the world server starts, as soon as its configuration is read (`src/AlliedRaces.cpp`),
  `src/AlliedRacesPatch.cpp` makes the game's `Data/patch-V.MPQ` (read after the HD looks'
  patches, whose character tables it builds on, and before Wrath Remix's `patch-Y` and the
  Skyborne's `patch-Z`, which are made later in the start from the client with it in and so carry
  its rows: Remix's patch takes the looks' and models' tables from the client for that; made at
  the startup like theirs, it came after them on a first start and the game didn't know the races
  until the next) from the module's `data/client` and the package's `data/files`: the tables with the rows
  changed, the models, textures and voices, the character screen's files, the race icons with
  their portraits, the narrations. Made again only when the client, the module or the files
  change (the stamp in `patch-V.MPQ.alliedraces`); a `patch-V.MPQ` that isn't the module's is
  left alone. `AlliedRaces.ClientPatch = 0` (`mod_allied_races.conf`) takes it out. The letter V
  is free of the HD looks' (the alternative HD look has a `patch-W`).
- The archive reading and writing, the tables and the textures in C++ are the Skyborne's
  (`mod-skyborne/src/SkyborneFiles`), which this module includes.

## Tests

- `tools/test_tools.py`: the texture codec round-trips, the converter's pieces (the sequence
  lookup, the geoset policy, the coordinate remapping), races.json's shape, the tables naming
  the textures the way they're made. CI runs it.
- `tools/test_screen.py`: `AlliedRacesCreate.lua` in Lua 5.1 against stand-ins for the game's
  screen, alone and with the Skyborne's screen code after it. CI runs it.
- `tools/test_safety.py`: split and unsplit animation streams, relocated bone tracks, local
  global-loop tracks, truncated external animation files for both races and sexes, and narration
  rows selecting only their own MP3. CI runs it. The old global-track reader and external-file
  validator fail these regressions.
- `tools/test_intro.cpp`: builds a small client fixture with the real generated intro tables and
  MP3s, then follows each race's cinematic links through the resulting MPQ and compares its audio
  bytes with the source. Also checks that a second start reuses the patch. CI runs it.
- `tools/test_patch.cpp`: the patch made for a stand-in game from the package's files and read
  back (races, looks and their textures, models, voices, spells, the screen, a second start keeping
  it). Built and run here against the server's tables and a local package.
- **`tools/render_m2.py MODEL.m2 FILES DBC_DIR OUT.png`** draws a converted model the way the game
  reads it (the sections through their bone combos, posed on Stand, the composed character
  texture, the model's own cameras) to a PNG: how the model will look, short of the game itself.
  Run here on all four models before a release. It reads the sections' triangle starts as the
  game does (16 bits), so a model over the limit shows its damage here first.
- `tools/check_m2.py` on every converted model (every vertex's bone slots lead through its
  section's combo to the bones the vertex names: the first models' didn't, and the game drew them
  in pieces; run here: all four pass; the HD look's blood elf,
  read the same way, passes but for what the HD look itself leaves out).
- CI's server test: the world server makes the patch (19 tables) before the Skyborne's and Wrath
  Remix's, the races are playable in the world database with their looks, a void elf warrior starts in Ammen Vale and a nightborne
  warrior in Deathknell, each with their racials.
- Not tested: the game itself (the models drawn, the looks, audible playback of voices and intros,
  the screen on a phone): nothing here runs the 3.3.5 client. In particular, the reported freeze
  and return to the launcher when a new Nightborne speaks to Undertaker Mordo has not been
  reproduced here. These fixes remove verified unsafe animation data paths; a client run is
  still needed to confirm that specific crash is resolved.
