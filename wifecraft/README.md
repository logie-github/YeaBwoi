# Wifecraft

A story editor for Android: a visual novel and scene builder. You make the people in a character
creator laid out as Wrath++'s (Shadowlands' own creator: every race and allied race, both sexes,
every option the game's tables give), set scenes on a stage, write what they say and the choices
the reader makes, and play it back: the speakers from the waist up over a live 3D stage, the words
in WoW's talking-head box, the choices in its gossip window. The people are the game's own models
playing the game's emotes; the menus are WoW's own interface.

| | |
| --- | --- |
| App | Wifecraft (`com.logie.wifecraft`), Android 8 or later, 64-bit ARM, OpenGL ES 3 |
| What you make | Projects: a cast of people (as many as you like) and scenes, each a stage and its beats |
| The stages | The studio (the people alone, in the void) and four places in Legion's Dalaran: Krasus' Landing, its streets, a plaza below the Violet Citadel, the Citadel's terrace; each by day, dusk or night |
| The game's files | Fetched from [wago.tools](https://wago.tools) on the phone and kept, every table and file at one build (12.1.7.70892, `GameTables.BUILD`); none are in the app or this repository |

## First launch

1. The title says the files aren't here yet: **Gather the Files**, then **Start**. It fetches WoW's
   interface first, then the game's tables the character creator reads (about 20 MB), then
   Dalaran (the places' backdrop) and its props, then **every race's looks**, then the music and
   sounds. The bar counts files ("N of M files") and bytes. **Pause** stops, **Resume** carries on
   (also after the app was closed or killed: what is on the phone is never fetched again);
   **Wi-Fi only** (on by default) waits for Wi-Fi. Options' **Download speed** (Gentle 24, Normal 48, Fast 96 files at once; Fast by default, changes the running download at once) is a ceiling: when wago.tools, a free service, answers 429/502/503/504, resets or times out, the download halves (never below 8), obeys Retry-After and climbs back by 4 every 50 good files; the Gather screen says "slowed to N". A file wago.tools won't give is tried five times
   and set aside, the rest carry on; at the end "N files could not be fetched: **Retry**".
2. **Every race's looks** is every file the character creator can draw (creator/Prefetch.kt), so no
   character waits for a download later: for every race and both sexes (26 races, 52 models in
   November 2026), every option and every choice, each choice resolved on the default look (and with
   the choice its rows hang on: a colour on its pattern, a hair colour on its style), plus a sweep of
   every row of every choice: the models (and Upright's), their skins and the story's animations
   (read from each model once it is here), every texture the layers use, the skinned models
   (horns, blindfolds...) and the face's bone files. The list is made on the phone from the tables
   and kept (files/cache/looks-plan.txt); new tables make a new list, and only what's new is fetched.
   **How big:** measured on 15 November 2026's tables, **16,013 files, about 5.9 GB** (65 models,
   991.5 MB, and the 853 files they name, 143.8 MB, all fetched and weighed; the 15,095 textures and
   bone files estimated from a random 1,500 of them: 4.80 GB ± 0.28 GB). With the rest, about **6.1 GB
   in all**: have that much free. wago.tools gives no sizes before a file is fetched, so the total
   is shown as "about" until everything is here; the count of files is exact once the models are in.
3. Anything still missing (a file that couldn't be fetched, or the gathering stopped half way) is
   fetched when a character first wears it (in the creator, the scene builder or Play), on any
   connection: a bar at the top of the screen counts it. The creator's own art (Shadowlands' sheets,
   build 9.2.7) is fetched with the files and cut when the creator opens.

## The title

**Continue** opens the project you worked on last, **New Project** starts one, **Projects** lists
them all (open, rename, delete). **Import Animations** and **Animations** (the library) are
described under "Sims 4 animations" below. **Options** and **Updates** are below.

## The editor

- **Cast**: the project's people. **New NPC** opens the character creator; **Edit**,
  **Duplicate**, **Delete**.
- **The character creator** is Wrath++'s screen: the races down both sides and the two sexes, then
  Customize with the round category buttons (Face, Hair, Accessories, Body, Markings... as the
  game's tables name them for that race and sex), the dice, each option with its choice, arrows
  and tap-for-list, the name box, the view buttons, Back and Finish. Drag turns the character,
  pinch brings it nearer. Every option and choice comes from the game's tables
  (`ChrCustomization*`), drawn on the model as you pick it. Wrath++'s own body options (Shape,
  Style, Density) are there for the women of 3.3.5's ten races, drawn from the body options'
  package when it is imported (below). **Gear** is last in every category.
- **Scenes**: **Add scene**, **Up**/**Down**, **Start** (where the story starts), **Rename**, **Delete**.
- **The scene builder**: the stage (a place and a light) with the cast standing as the selected
  beat leaves them, and the beats in order: **Say** (who, an emote, the words; nobody is narration),
  **Show** (who, left, center or right, and the camera: bust, two, wide or one of the place's
  views), **Hide**, **Camera**, **Pose** (who holds an imported animation, picked by name from the
  library; **Preview** plays it on the stage, **Stop** ends it), **Choice** (each answer goes to a
  scene), **Goto** a scene, **End** (its title is shown as the ending). A Say's emote list also
  offers the library's animations ("Pose: name"): the line is spoken while the person holds it. **Check** lists what would stop it playing; **Play** plays the
  project from this scene.

## Placement editor

Pick a thing or an NPC, put it in the scene, drag it where you want it, and give it an animation or an emote (the
Creation Kit's way). It works on the scene builder's stage.

- **The stage**: the scene builder's **Place** lists the Studio, Dalaran's places and every location of
  `locations.json` with each of its views (the **Stormwind Embassy: Inside** and **Outside** so far). A location's
  files are fetched when it is picked: the stage shows how many of how many (and the megabytes), and anything that
  couldn't be fetched or drawn, in red (tap it to clear). Its own music and ambience play (the Embassy's ZoneMusic
  and SoundAmbience).
- **Place things** opens the placement editor on that stage. **Add** opens the library: **Starter pack** (a style's
  everyday props: furniture, light, containers, food, books, banners, nature; the Embassy starts on Human, the style
  chips change it), **Props**, **Buildings**, **Creatures** (the game's named creatures and its creature models) and
  **My NPCs** (the project's cast). Search by name, filter by the category chips, Back and Next through pages of 40.
  Entries are names only (no pictures yet). A pick lands where the middle of the view meets the floor, facing you, and
  is selected; its files are fetched then (the stage shows them coming).
- **Select**: tap a thing on the stage, or in **Things** (the list of everything placed).
- **Move**: drag the selected thing with one finger: it slides on the floor and follows the ground under it (keeping
  the height it stood above the ground). Two fingers on it turn it and pinch its size. The **Turn** handle beside it
  turns it about its feet, the **Lift** handle above it raises and lowers it. One finger anywhere else orbits the
  camera; two pan and pinch to zoom. **View** puts the camera back.
- **Selected**: X, Y, Z (yards, Z up), Turn (degrees) and Size typed in, a size slider (0.05 to 20) and a turn
  slider; the nudge buttons (**Left**, **Right**, **Away**, **Near** as the camera sees the floor, **Up**, **Down**: a
  tenth of a yard, or the grid while snapping); **Copy** (beside the original) and **Delete** (its Emote and Animation
  beats go too).
- **Snap**: positions to a grid (0.25, 0.5, 1 or 2 yards: tap **Grid**) and turns to steps (5°, 15°, 45° or 90°: tap
  **Turn**).
- **Undo** and **Redo**: every change (a whole drag is one).
- **Things**: each placed thing, what it is, and for the selected one **Hide** (off the editor's view only), **Lock**
  (it can't be moved), **Rename** and **Look** (the camera turns to it). Hide and Lock last while the scene is open.
- **Animation** (a person or a creature): every emote and animation of its race and sex, grouped (Emotes, Dance, Sit,
  sleep and kneel, Work and life, Combat, Spells...), with a search box. **Play** (or tapping the row) previews it on
  the stage with its voice; **Use** makes it what it plays in the scene; **None** goes back to standing. **Held**
  keeps it looping, **Sound** turns its voice and sounds on or off. A creature also has its own sounds to hear
  (Fidget, Alert, Attack, Wound, Death, Shout, Loop). A creature that isn't one of the player races is offered every
  animation the game names; it plays the ones its model has.
- **Beats**: **Emote** and **Animation** beats play one on a placed person or creature in the middle of a scene.
- **Play** shows everything placed, in its animation, with its sounds.
- Options has a **Voices** volume beside Music and Sounds.

## Sims 4 animations

**Import Animations** (the title) turns Sims 4 animation files into poses and clips your stories can
use. Nothing from the Sims game is in the app or the repository: you bring the files.

1. **The Sims 4 skeleton, once.** Conversion needs the Sims' own skeleton (its RIG resource), which
   lives in the game's packages, not in animation mods. Under "Sims 4 skeleton" tap **Choose
   package** and pick a game package from your own install (a file such as
   `ClientFullBuild0.package`). Only its index is read; the skeleton found is kept in the app's
   storage (`files/anims/rig`), so the big package is read once. The row says whether a skeleton is
   loaded. Without one you can still pick files, but each is reported as **Failed** with that
   reason; nothing is converted or invented.
2. **Choose animation files.** Pick one or many `.package` files, or a `.zip` or `.7z` holding them
   (folders inside are fine; entries with unsafe paths, such as `../` or absolute ones, are
   refused and said so; temporary files are removed afterwards; an invalid archive is reported and
   the rest carries on). The list shows each file or clip as it converts: **Done**, **Skipped**
   (for example a package with no clips) or **Failed**, with the reason. One bad file never stops
   the others.
3. **Review.** Each converted animation has a name (its clip or file name to begin with), a
   category (one of the existing ones, or **New**), a Keep/Skip button; with several, **All to**
   sets one category for the batch. **Save** adds them to the library. The same source (the file's
   hash and the clip's name) is skipped with a message; the same display name gets " (2)".

**Animations** (the title, or Import's neighbour) is the library: search by name, category or
source file, filter by category, **Rename**, **Category**, **Delete**. It is stored as one JSON
file per animation plus an index, under `files/anims/`, written atomically. Playing an animation
from the library screen is not there; use a Pose beat's **Preview** in the scene builder.

In a story, a Pose beat is compiled to the script command `pose <person> <id>` and played by the
renderer: the animation's bones replace the person's skeleton pose by bone name (the conversion's
profile in `assets/sims_profile.json` says which Sims bone drives which WoW bone) and a pose holds
until the person moves, uses a game emote or leaves. An animation that has since been deleted is a
problem **Check** names, and is skipped if you play anyway.

What it can't do (yet): poses (single-frame clips) are what it is made for; longer clips convert
with the same rules but are less checked. Sims content that is not an animation clip (objects,
CAS parts, sliders, scripts), Sims 4's compressed or encrypted packages that the reader doesn't
know, and clips whose bones the profile doesn't map are reported, not converted. The Wifecraft
renderer draws the game's current models without 3.3.5's animation sequences, so a pose is the
bone transforms of the clip, not blended with an idle. The skeleton package is read from disk, so
it needs room for one temporary copy while it is picked.

## Playing

| Gesture | Does |
| --- | --- |
| Tap | Show the whole line; tap again for the next |
| Hold | Fast-forward through lines already read, while held |
| Swipe up | What Was Said (the log) |
| Swipe down | Hide the text box to look at the scene; tap to bring it back |
| Two-finger tap, or Android's back | The Game Menu: Resume, What Was Said, Options, Stop Playing |
| On a choice | Tap the answer |

An End beat shows its title as an achievement toast; **Back**, or the end of the story, returns
to the editor.

## Options

Music and sound volume; text speed; the parchment talking head instead of Legion's dark one; Wi-Fi
only for the first launch's gathering; its download speed (24, 48 or 96 files at once); **Soft body**; and, from the title, the creator's **Imports**:

**Soft body** (Off, Subtle, Natural by default, Strong; applies at once, kept between runs): the chest, belly, rear,
thighs and upper arms are soft. Each region is a damped spring hung on the bone that carries it (the chest, the lower
spine, the pelvis, a thigh, an upper arm), simulated at a fixed 120 Hz from that bone's own motion, so steps, hops, stops,
turns, emotes, imported poses and idle breathing all move it, and under gravity: standing still it hangs lower than
modelled (a fuller Shape hangs lower and swings slower), lying down it falls the way the world's down is. It lags, bounces
and settles; the shown displacement is softly limited (to about a quarter of the region's size at Natural), a gap longer
than a quarter second (a pause, a hitch) holds it still, and a character put somewhere else doesn't swing. The regions
are found on each model from its bones (the breast bones of today's women's models, the spine, pelvis, thighs and arms)
and its skin, and the vertex shader moves only skin carried by those bones (never the head, hands, calves), fading to zero
at each region's edges and against the body behind it; clothing drawn on the body moves with the skin, a cape and the
items hung on the body don't. Men's models have no breast bones: their chest doesn't move. Off draws exactly as before.

| Import | The zip | What it does |
| --- | --- | --- |
| Ralia's mod | `ralia.zip` | Character textures in place of the game's (matched by name with the game's file list, fetched once, about 150 MB) |
| Alternate Female Options | `alternate-female.zip` | The women's textures in place of the game's (one texture set is used at a time) |
| Body options | `wrathpp-body.zip` (or `body.zip`) | Wrath++'s Shape, Style and Density |

Each zip is looked for by its name in Download, Downloads, Documents and the top of the phone's
storage (where Android lets the app look) and taken in by itself; **Choose zip** picks it by hand,
**Remove** takes it off. The zips are yours: nothing of them is in the app or this repository.

## Updates

The app checks this repository's releases (`wifecraft-N`) when it starts; **Updates** on the title
shows the newest and installs it (Android asks once to allow installs from the app). The update
keeps the game's files and your projects.

## How it's built

- **The renderer** (`android/app/src/main/cpp/engine`, C++17, OpenGL ES 3): the game's textures
  (BLP), models (M2 with their skins and animation files, skinned on the GPU; today's character
  models keep their bones, sequences and animation files in a skeleton file, `.skel`, which can
  have a parent: Lightforged on Draenei, Mechagnome on Gnome), a character's
  customization (geosets, the skin composed from its layers on the GPU, extra and skinned models,
  Wrath++'s body shapes and layer: `bodyshape.cpp` splits the skin where a shape moves it and moves it along the shape's
  surface as the package draws it, with the package's finer vertices too, so the curve is smooth and hangs as made), and
  Dalaran's buildings (WMO groups with their placed props),
  lit by the light of the time of day, with fog; the city can be hidden for the studio.
- **The creator** (`creator/`, plain Kotlin): `Catalog` (races, categories, options, choices from
  the game's tables), `CreatorModel` (Wrath++'s behaviour), `LookResolver` (a look as model,
  geosets, texture layers, extra models and the files it needs), `Dressing` (that look as the
  renderer's call: each layer's rectangle from `CharComponentTextureSections` and
  `ChrModelMaterial`), `GameTables` (which tables, fetched once), `ImportStore` and `BodyShapes`
  (the imports). `engine/Dresser.kt` fetches a look's files and dresses the actor.
- **The editor** (`editor/`): the project (`Project`, saved as `files/projects/<id>/project.json`,
  written atomically by `ProjectStore`) and `Compile`, which turns it into the story script
  (`story/Script.kt`) that `story/StoryRunner.kt` runs and `play/Director.kt` stages.
- **The places** (`assets/places.json`): each place's marks (every one has left, center and
  right), its camera views; the studio.
- **The file lists** come from the game's tables on wago.tools: `tools/manifest.py`
  (`assets/files.json`: the interface, Dalaran, its props, the sounds), `tools/sounds.py`
  (`assets/sounds.json`), `tools/creator_art.py` (`assets/creator_art.json`); ids only.
- `engine-test/` is a desktop build of the renderer (headless EGL) used to check looks and scenes
  off the phone (`--soft LEVEL`, `--strip N:STEP_MS` and `--camera` draw a soft body test strip of a move);
  `build/softbody_test` checks the soft body's maths alone. `unit-tests/` (JVM) checks the editor, the script and runner, the creator against
  the game's own tables, the glue to the renderer, the places and updates.
- CI: `.github/workflows/build-wifecraft.yml` runs the unit tests, builds the APK and publishes
  the release.

## What was tested

The JVM unit tests and the renderer's desktop build (`engine-test/`). The animation import's pure
parts (the progress list, the review and save, the library browser, the Pose beat's save, compile,
checks and script command) are unit-tested; its screens, the file pickers, a real Sims 4 package
and the poses on a model have not run (nothing here runs on a phone). Nothing of the editor, the
creator's screen, the downloads on the phone or Play has run on a phone yet. Not drawn yet: the
face shapes (`ChrCustomizationBoneSet`'s bone files are fetched, the renderer has no call for
them), the starting outfit (Gear shows or hides the bare equipment geosets only), the glow of glowing
markings (the Nightborne's Face Tattoo: its colour layer is nearly transparent, the glow is a second
texture, `TextureFileData.UsageType` 2, the renderer doesn't use) and voices for the people's emotes.

## Changes

- **Body shapes, smooth** (November 2026): Shape (Petite, Curvy, Voluptuous) no longer draws a wavy, faceted chest. The
  shapes are read at the package's own resolution (its refined vertices, where each was before worked out again as
  Wrath++'s refine.py made it; vertices of pieces the package cut down left out), the skin is split once where a shape
  moves it, every vertex is moved along the shape's surface (exactly at a looks vertex, a smooth fit between, one move per
  place so nothing opens) and the normals turn with the surface. Checked on the desktop with the user's own package
  (Human, shapes 1 to 3: every added vertex within 4.3 mm of the package's own shaped surface; the move's bumpiness, p99,
  from 6 to 16 mm down to 2 to 6 mm) and engine-test's `bodyshape_test`; skinned to the same bones (it runs with them); not
  run on a phone.

- **Smoother animation** (November 2026): standing plays the game's own idle variations (glances, weight shifts,
  fidgets) by their frequencies and replay counts instead of one short loop; every change of animation (an emote, the
  same emote again, back to standing, an imported pose on or off) blends from the pose last drawn with an eased
  150 to 400 ms fade (the sequence's own blend time), turns by slerp; a held imported pose breathes; the animations keep
  their own clock (a pause or a stall never jumps them ahead; 60 and 120 Hz screens play at the same speed); a model's
  texture-weight tracks no longer read the skeleton's animation file. Measured on the desktop with six 12.1.7 models
  (human, night elf, tauren women, gnome man and woman, tauren man); not run on a phone.

- **Every race shows** (November 2026): the creator drew only the old human models; every other
  race (and, at the current build, every race) keeps its bones in a skeleton file the renderer
  didn't read, and wago.tools' unversioned links mixed a model with another build's skin (the
  Blood Elf man's triangles joined the wrong vertices). The renderer reads skeletons and their
  parents, every table and file is fetched at one build (files fetched before are fetched again,
  once), a skinned model shows its own geoset (the Earthen's hair), and a model that can't be drawn
  says why on the screen. All 52 race and sex models drawn on the desktop with a face, hair and
  marking choice each; not run on a phone.

- **Sims 4 animations** (November 2026): **Import Animations** and **Animations** on the title,
  the Pose beat and the library's animations in a Say's emote list ("Sims 4 animations" above).

- **The story editor** (November 2026): the demo story (*A Day in Dalaran*, Alleria, Nyrelle, the
  endings, the reputations, the Who Are You? screen, saves) is gone; Wifecraft is now a story
  editor with a character creator laid out as Wrath++'s, every race and option of the game's
  tables, the imports (Ralia's mod, Alternate Female Options, the body options), the studio stage
  and Play.

Wifecraft is a fan project, not affiliated with or endorsed by Blizzard Entertainment. World of
Warcraft and its characters, models and art are Blizzard's; the app downloads them for you and
doesn't include them.

## Soft body: what is verified and what is not

- **Verified (desktop):** `softbody_test` (determinism and replay, rest sag equal to sag x g / w^2 and lower for a
  heavier shape, swings shrinking after a stop and settled within 2 s, the drawn limit held, nothing non-finite with 1 ms,
  300 ms and 10 s frames and time going back, no kick when placed elsewhere, lying down falls to the world's down). The
  retail human and night elf women (12.1.7) drawn by `engine-test` through a hop, a stop and a quarter turn at Off,
  Natural and Strong: the chest lags, overshoots and settles, nothing tears; Off is byte-identical to the renderer before it.
  The regions are found on the women of the other eight races of 3.3.5 (all nine each) and a tauren and a dwarf woman
  were drawn hopping without tearing; the human man gets no chest region (no breast bones).
- **Not verified:** anything on a phone (the shader's cost on a phone GPU, how it looks at the phone's frame rate), the
  allied races' and other models (their regions are found from their bones; a model without the named bones gets none).

## Sims animation conversion: what is verified and what is not

- **Verified (desktop, synthetic):** the Sims 4 package and clip reader decodes the 15 clips of one sample pose pack (96 bone rotations and 96 translations each, all bone hashes named); the retargeter maps a synthetic Sims pose onto a night elf model's bones and the engine draws it (`engine-test`, Mesa headless).
- **Not verified:** a real Sims pose converted with a real Sims 4 skeleton (none was available: the skeleton's bone names and parents come from community documentation, unchecked against the game's own RIG), anything on a phone, the compressed channel types (palette and normalized) against real data.
- **Known limits:** while an imported animation plays, its bones and every bone above them stand at the model's rest pose (the A-pose), and only each bone's direction is corrected (it can still be twisted along its length). Sims face and CAS bones are dropped. Poses are single-frame; motion clips use the same path and are untested.
- **Sample viewer:** `tools/viewer_html.py` builds a private single-file HTML viewer of a night elf with the test poses and a loader for the importer's animation JSON (it embeds game model data: keep the output file to yourself; never commit it).

## Undead skins import

A fourth import beside Ralia's mod, Alternate Female Options and the body options: **Undead skins** (`wrathpp-undead-skins.zip` or `undead-skins.zip`, the same package Wrath++ takes: a `manifest.json` and BLP skins and faces under `client/Character/<Race>/<Sex>/`). Options, Imports: choose the zip, or remove it. With it in, the creator shows an **Undead** switch under Accessories (the race's last category where there is none), for the races and sexes the package has (all ten races but the Forsaken, both sexes; no blood elf man). While on, Skin Color lists the undead skins and the Face row is hidden; women's underwear is dropped where the package says so. Without the package the row is greyed "Import the undead skins in Options". Verified on the desktop with the real package (renders of a blood elf and an orc); not run on a phone.
