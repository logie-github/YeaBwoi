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

In short: the first launch downloads WoW's interface, the game's tables (about 27 MB) and Dalaran from wago.tools, then,
in the background, every file the character creator can draw (about 6 GB; Options' **Download speed** and **Wi-Fi
only**). An editor location (the Stormwind Embassy, 30.5 MB) and an emote's animation file are fetched when first used,
and the 37 MB sound table streams once. Nothing of the game is in the app.

1. The title says the files aren't here yet: **Gather the Files**, then **Start**. It fetches WoW's
   interface first, then the game's tables the character creator reads (about 27 MB), then
   Dalaran (the places' backdrop) and its props, then **every race's looks**, then the music and
   sounds. The bar counts files ("N of M files") and bytes. **Pause** stops, **Resume** carries on
   (also after the app was closed or killed: what is on the phone is never fetched again);
   **Wi-Fi only** (on by default) waits for Wi-Fi. Options' **Download speed** (Gentle 24, Normal 48, Fast 96 files at once; Fast by default, changes the running download at once) is a ceiling: when wago.tools, a free service, answers 429/502/503/504, resets or times out, the download halves (never below 8), obeys Retry-After and climbs back by 4 every 50 good files; the Gather screen says "slowed to N". A file wago.tools won't give is tried five times
   and set aside, the rest carry on; at the end "N files could not be fetched: **Retry**". A file the pinned build answers
   with nothing (Dalaran's loading art, 7963775, at 12.1.7.70892: 200 and no bytes, so a new install's interface files never
   finished and the editor said they weren't all here) is asked for again without the build, which gives it whole.
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

The title stacks the game's own red buttons as WoW's Game Menu does, six at most: **Continue** (the project you worked on
last, named under the stack), **New Project**, **Projects** (a WoW list window: each project's name, when it was last
saved, its cast and scenes; a tap picks one, a tap on the picked one opens it, and its own line holds **Rename** and
**Delete**; **New Project** and **Open** at the foot), **Options**, **Import Animations** (the animation library is reached
from there and from the editor's Game Menu) and **Updates**. Until the game's files are here, **Gather the Files** stands in
Continue's place. On the cover screen on its side the six stand in two columns.

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

## The look

The editor's menus are World of Warcraft's own (W40, the user on 1.0.42: "way too many buttons or options. Simplify the menu
and keep the menus like you'd find it in WoW"): the scene, a few buttons, and everything else one tap further, in the
game's windows, dropdowns and Game Menu. The character creator keeps Wrath++'s look exactly (Shadowlands' own art,
`creator_art.json`) and is not part of this.

- **On screen while placing things**: the scene; the **Menu** button (top left: the action bar's frame with a gold cog;
  it opens the Game Menu) and the scene's name in gold; **Undo**, **Redo** and **Play** (top right, the only round buttons);
  and the **action bar** along the bottom, six framed slots on the housing editor's ability bar: **Select** (move),
  **Rotate**, **Scale**, **Delete**, **Add** (the Library window) and **More**. That is 10 things to tap (11 with a tip's
  Got it); the old screen had about 50 (the 1.0.42 inner-screen layout with nothing selected: Back and six top buttons, ten
  tools, three side tabs, the Things page, the catalogue's search, seven tabs and fold, five chips, seven cards and their
  stars). A long press on a slot says what it does.
- **More** (a WoW dropdown: the tooltip's dark box, plain rows of white words with small icons, check boxes): Duplicate,
  Lock, Hide, Emote…, Look at it (the selected thing's, dimmed without one); Eyedropper, Snap to grid, Show hidden things;
  Home view, Objects in this scene…, Stage…, Tips again.
- **The selected thing** shows as the game's tooltip, bottom right: its name in gold, what it is and what it plays,
  **Emote** (a person or a creature) and **Details** (folds open X, Y, Z, Turn and Size typed in, the nudge buttons, Sound
  and Held). A **long press** on a thing opens its dropdown: Duplicate, Lock, Hide, Emote…, Look at it, Details…, and
  Delete in red.
- **Windows** (one at a time, over the dimmed scene, the bar put away under them; the red close X, a tap outside or Back
  closes them): the **Library** (the spellbook's parchment in the metal frame, four tabs along its bottom edge: Props,
  Buildings, People, Favourites), **Objects in this scene**, **Emotes**, the stage picker.
- **The Game Menu** (the Menu button, or Back with nothing open; Back again is Resume): the metal frame with the game's
  red buttons stacked, seven: **Resume**, **Scenes** (this scene's beats; Back from there lists the scenes), **Cast**,
  **Animations** (the library; Back comes back to this scene), **Options**, **Play**, **Save and Exit** (everything is
  saved as it changes; it goes to the title). On the cover screen on its side, two columns.
- **Options** is the game's settings window: the categories down its left side (Game, Sound, Graphics, Interface,
  Advanced last; a dropdown instead on a narrow screen), gold labels, the game's minimal check boxes and sliders, its
  dropdown button, **Okay**.
- **The cast and the scenes** are lists of WoW rows: a tap picks a row and shows its own line of text buttons (Edit,
  Duplicate, Delete; Open, Up, Down, Start here, Rename, Delete), a tap on the picked row opens it. Names, dialogs and
  questions are small WoW windows with red buttons.
- **Where the pieces come from**: `assets/ui_art.json` (made by `tools/ui_art.py` from the real `UiTextureAtlas*` tables and
  `ManifestInterfaceData` at the app's build, 12.1.7.70892; ids and boxes only) names 102 atlas pieces on 33 sheets and 65
  icon keys (57 icon files): the housing editor's tool pictures and ability bar with its wooden corbels, the action bar's
  slot frame, the decor chest's picture cards, the spellbook's page, the tooltip's corners, the frame's bottom tabs
  (`uiframe-tab-*`, `uiframe-activetab-*`), the red close X (`redbutton-exit`), the minimal check box and tick
  (`checkbox-minimal`, `checkmark-minimal`), the minimal slider, the dropdown's arrow button (`common-dropdown-a-button`),
  the search box's glass, the gold glyphs (undo, redo, delete...), and item and spell icons for the tabs, the categories,
  the More rows and the emote groups. The metal frame, its stone and the 128-RedButton are the interface pack's own
  (`WowArt`). The atlas pieces are fetched from wago.tools on the phone the first time the editor opens (about **60 MB**,
  once, into `files/war-art`), cut, and warmed a little (`WarKit.WarTint`). Nothing of the game is in the app or this
  repository.
- **Loading the art** (`ui/WarArt.kt`, its rules in `ui/WarArtPlan.kt`): four files fetched at a time, each kept only when it
  is a BLP2 texture (wago.tools answers a file it lacks with JSON or nothing); the pieces go into observable state, so every
  button draws again the moment its picture arrives. A file that fails is tried again by itself after 5 s, 15 s, 45 s...
  (five times), one that is here but can't be read is deleted and fetched again. The editor's first open shows **Preparing
  the interface: n of m pieces** over it (with the failures, **Retry** and **Continue**); **Options** (Game) has an
  **Interface art** row: how many pieces are loaded, what failed and why, Retry.
- **A button is never blank**: until its picture is here (or when it can't be had) every slot, window, row, tab and button
  draws plainly in the same colours, with a vector glyph (`ui/WarGlyphDraw.kt`) where a picture would be.
- **Every screen size** (`ChromeLayout` in `ui/MenuModel.kt`, from the real width and height): the slots are 60 dp (48 at
  least, on the cover screen upright); the scene's name gives way to Undo, Redo and Play on a narrow screen; the selected
  thing's tooltip spans the width on a narrow screen; windows are never taller than the screen (their lists and grids
  scroll inside), the Library's cards are 92 dp on the inner screen and 76 dp on the cover; the Game Menu and the title go
  to two columns where one doesn't fit. Nothing to touch is under 48 dp.
- **Not compiled here and not run on a phone**: the Compose code (`ui/WowMenus.kt` and the rebuilt screens) is compiled by
  CI only. The pure parts (`MenuModel`: the action bar, the chrome and Back, More's and the context menu's rows, the
  Library's tabs and dropdown, the Game Menu, the title, Options' pages, the layout at the Fold's four sizes; `WarKit`,
  `BuildModeState`, `WarArtPlan`) are JVM-tested, with a source scan that every picture the kit names is in `ui_art.json`.
  The look was judged on mock images drawn from the real cut pieces over a screenshot of the Embassy and the title's art, at
  2184 x 1968, 1080 x 2520 and 2520 x 1080 (the editor, More, the context menu, the selected thing's tooltip, the Library,
  the Game Menu, the title, Options and Projects). The four new sheets were fetched at the pinned build and cut.

## Placement editor

Pick a thing or an NPC, put it in the scene, drag it where you want it, and give it an animation or an emote (the
Creation Kit's way). It works on the scene builder's stage.

- **The stage**: the scene builder's **Place** lists the Studio, Dalaran's places and every location of
  `locations.json` with each of its views (the **Stormwind Embassy: Inside** and **Outside** so far). A location's
  files are fetched when it is picked: the stage shows how many of how many (and the megabytes), and anything that
  couldn't be fetched or drawn, in red (**Dismiss** under it clears it, the location's own error too). **Preparing
  sounds...** shows while the sound index is first built or downloaded (the first sound of a session waits for it). Its
  own music and ambience play (the Embassy's ZoneMusic and SoundAmbience).
  **Play opens a location scene on the view's camera** (its own, else a look at its spawn) and shows the location's
  name (for example Stormwind Embassy: Inside) instead of the bare id; the cast still stands on the plain stage's marks.
- **Place things** opens the placement editor on that stage. The **library** is the Library window (**Add** on the action
  bar; see "The look"): four tabs (Props, Buildings, People: your NPCs and the game's creatures, Favourites: the recent
  picks on top), one search box, one category dropdown with its counts, a grid of picture cards; Props starts with
  "Suggested for this place" (the place's starter pack). Tapping a card arms it and the window closes.
  - **Picture cards**: each entry is a small picture of its model (drawn on the phone as its card comes on screen and
    kept; its name shows until the picture is ready, an NPC of your cast shows its initial). A line under the grid says
    why pictures can't be drawn, if they can't.
  - **The dropdown**, each row with how many it has: Props and Buildings: all of it, its categories (Furniture, Lighting,
    Food and drink, Containers, Architecture, Nature, Water, Ships and docks, Banners and flags, Tents, Statues...) and its
    styles (Human, Forsaken, Night elf...); People: **Your NPCs** (the project's cast), **All creatures** (the game's
    named creatures and its creature models) and each creature type. Favourites has no dropdown: **Recently placed** (the
    last 30) on top, then your favourites. The suggestions (the starter pack) follow the place's style (the Embassy:
    Human).
  - **Search as you type**: the results come a moment after the last letter, from everything, grouped as **Props**,
    **Buildings**, **Creatures** and **My NPCs** with their counts and the matched words lit; **Show all** on a group
    pages through all of it (40 a page: **Previous** and **Next** at the grid's end). With a category or style picked, the
    search stays inside it.
  - **Favourites**: the star on a card. Favourites and Recent are kept per project (beside it, in `prefs.json`).
- **Placing**: tapping a card arms it ("Placing: name" over the stage). A see-through copy of it, the **ghost**, appears
  at the crosshair once its model is loaded. **Drag one finger over the stage and the ghost follows it** (onto the floor,
  or on top of a table or another thing), snapping to the grid while Snap is on; **lift the finger to drop it there**. It
  stays armed: drag again for another, as many as you like. A **tap** moves the crosshair (and the ghost) without
  dropping; **Drop** puts one at the crosshair. **Turn ‹** and **Turn ›** turn the ghost (15°, or the snap angle).
  Two fingers move the camera (pan, pinch, twist) without dropping anything. **Done** or Back leaves placing; **Add** opens
  the Library again for another card. Each drop
  is one undo step; a model that can't be loaded says why in the bar and drops nothing.
- **Selecting**: tap a thing. **Tap the same spot again for the one behind it** (round through everything under your
  finger). **Double-tap** to look at it (the camera comes near, the thing in the middle). A **long press** opens its
  dropdown: **Duplicate**, **Lock**/**Unlock**, **Hide**/**Show**, **Emote…** (a person or a creature), **Look at it**,
  **Details…** and **Delete**. Tapping the empty floor deselects. In **Objects in this scene** (More), tapping a row selects
  it and looks at it.
- **Moving, turning, sizing**: the selected thing carries a **gizmo** (the Creation Kit's), its mode the action bar's slot:
  **Select** (move) (arrows: drag one to move along X, Y or up and down; the square at its feet slides it on the floor),
  **Rotate** (drag the ring to turn it about its feet), **Scale** (drag a box to make it bigger or smaller: one size for
  the whole thing). The handle you hold is lit and named in the bar; the camera never moves while you drag a handle; each
  drag is one undo step. Dragging the thing's body slides it on the floor too (it follows the ground under it, keeping the
  height it stood above the ground), and two fingers on it turn and resize it. A locked thing shows no handles (the bar
  says Locked, with Unlock). One finger anywhere else orbits the camera; two pan and pinch to zoom. **View** puts the
  camera back.
- **Nothing stays over the stage** but the bar and the selected thing's tooltip: Objects in this scene and Emotes are windows
  opened when wanted (More, the tooltip's Emote, the long-press dropdown).
- **Tips**: a one-line tip over the stage says what the current mode does ("Drag the ghost to place, lift to drop",
  "Drag an arrow to move along an axis"...). **Got it** hides that tip for good; **Tips again** (More) brings them back.
- **Details** (the selected thing's tooltip): X, Y, Z (yards, Z up), Turn (degrees, to the snap's step while snapping) and
  Size (0.05 to 20) typed in (**Size** works on people and creatures too: their own look's size times it); the nudge
  buttons (**Left**, **Right**, **Away**, **Near** as the camera sees the floor, **Up**, **Down**: a tenth of a yard, or the
  grid while snapping); **Sound** and **Held**. **Duplicate** (beside the original) is in More and the long-press dropdown;
  **Delete** is on the bar (its Emote and Animation beats go too).
- **Snap** (More's Snap to grid): positions to a grid (0.25, 0.5, 1 or 2 yards: tap **Grid** on the line under the top)
  and turns to steps (5°, 15°, 45° or 90°: tap **Turn**); **Snap off** there too.
- **Undo** and **Redo**: every change (a whole drag is one).
- **Objects in this scene** (More): each placed thing, what it is, and on the picked one's own line **Hide**, **Lock**,
  **Rename** and **Look at it** (the camera turns to it). **Hide** and **Lock** are saved with the scene. A hidden thing stays in the list (greyed) but is
  left out of Play (the compiled script says so in a comment, and drops the Emote and Animation beats on it); the editor
  draws it only while **Show hidden things** (More, or the Objects window's check box) is on, undimmed. A locked thing can't be moved, turned or resized by
  drag, handles, nudge buttons or the Inspector's fields (they are greyed); unlock it to change it. A copy is unlocked.
- **Emotes** (a person or a creature: the tooltip's Emote, More, the long-press dropdown; a WoW window): every emote and
  animation of its race and sex, with one search box and one dropdown of its groups (Emotes, Dance, Sit, sleep and kneel,
  Work and life, Combat, Spells..., with their counts). Each reads as words: the game's chat tokens
  written out (**Talk (Excited)**, **For the Alliance!**), an animation's name split, its weapon or stance in brackets
  (**Ready (Unarmed)**, **Dance (Special)**, **Sit Chair (Medium)**; tools/emotes.py's labels). Tapping a row picks it and previews it on the
  stage with its voice (**Preview** plays it again); **Use** makes it what it plays in the scene; **None** goes back to
  standing. **Held** keeps it looping, **Sound** turns its voice and sounds on or off. A creature also has its own sounds
  to hear (the Creature sound dropdown: Fidget, Alert, Attack, Wound, Death, Shout, Loop). A creature that isn't one of the player races is offered every
  animation the game names; it plays the ones its model has. An emote's animation file is fetched the first time it is
  played (picked, previewed, spawned with a placement or run by a script's `emote`/`anim`), every variation of it, and
  kept: the screen says **Loading emote…** (about 0.1 MB an emote, at most 1 MB), the person goes on with what they were
  doing (never the arms-out bind pose) and the emote starts the moment its file is in; a file that won't come is said in
  red. A look's own download stays the story's emotes only.
- **Limits**: a prop has no GameObjectDisplayInfo id in the library, so no sound plays for props yet (its **Sound**
  check box in Details says "Sound (none for props yet)"); Hide's dimmed look is only the greyed name in Objects (the renderer
  has no transparency for a placed thing); Play's people still stand on the plain stage's marks in a location.
- **Beats**: **Emote** and **Animation** beats play one on a placed person or creature in the middle of a scene.
- **Play** shows everything placed, in its animation, with its sounds.
- Options' Sound page has **Music**, **Ambience**, **Effects** and **Voices**.

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

**Animations** (top right of Import Animations, or the editor's Game Menu) is the library: search by name, category or
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

The game's settings window (from the title, the editor's Game Menu and the story's Game Menu): the categories down its
left side and the chosen page's rows, short:

| Page | Rows |
| --- | --- |
| Game | **Imports** (a page of its own: Ralia's mod, Alternate Female Options, the body options, the undead skins, each with its state, Choose zip, Use, Remove; and the Sims 4 skeleton's state with the way to Import Animations); **Interface art** (pieces loaded, failures, Retry) |
| Sound | **Music**, **Ambience** (the places' ambience), **Effects** (Sounds), **Voices** |
| Graphics | **Soft body** (Off, Subtle, Natural, Strong); **Download speed** (Gentle 24, Normal 48, Fast 96 files at once) |
| Interface | **Text speed**; **Parchment talking head** (instead of Legion's dark one) |
| Advanced | **Gather on Wi-Fi only**; **The game's files** (what is missing, **Gather**: the first launch's screen, with the music and sounds) |

The imports are set from the title's or the editor's Options, not while a story plays. There is no resolution scale (the
renderer draws at the screen's own).

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

### Placing, made easier (gizmo, ghost, thumbnails)

- **Ghost:** tap a library item and a see-through copy follows one finger on the floor; lifting drops it, and it stays armed for the next one until Done. Two fingers move the camera. The ghost is drawn at half strength, never picked and never saved.
- **Gizmo:** Move, Rotate and Scale buttons on the selected thing. Arrows move along an axis (X red, Y green, Z blue), a small floor square moves it freely, the ring turns it, the boxes resize it. Handles keep one size on screen at any distance, the active one lights up, and the camera does not move while one is dragged. Axes are world-aligned and the pivot is the feet. A placement has only a yaw, so there are no pitch or roll rings. Locked placements show no handles.
- **Thumbnails:** the library shows a picture per item, drawn on the phone the first time the card is visible (it downloads that model once) and kept in `files/thumbs/`. A thumbnail never touches the live scene.
- **Selection:** tapping the same spot again cycles through what is under the finger, a double tap focuses the camera, a long press opens a menu (Duplicate, Delete, Lock, Hide, Emote, Focus).
- Not run on a phone; Compose and Android Kotlin compiled by CI only.
