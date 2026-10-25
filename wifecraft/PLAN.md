# Wifecraft — the brief

The user's requirements, what was decided, what's open, and where it stands. Nothing is built yet:
this is the design. The screens' mockups are on a canvas
(https://claude.ai/artifact/B2i9Zi7R6hmyCJ8pNTjNVn, private to the user).

## What the user asked for

- "I want to use wow engine for a dating sim engine. So pulls models and text boxes etc like a
  visual novel setup." Then a screenshot of a visual novel (a painted town street, a character from
  the waist up in the middle, a translucent text box with a name tab, back/log/forward/skip
  icons): "I'm thinking similar to this but Warcraft. It would run as a separate app."
- Asked one question at a time, the user chose:
  - **models**: the newer ones (Legion-era remodels or later), for their faces;
  - **art style**: the plain WoW look (no cel shading, no paint-over);
  - **expressions**: the game's emote animations;
  - **cast**: lore characters and original ones;
  - **the player**: made by the player (race, body, look), shown in some scenes;
  - **backgrounds**: the live 3D world behind the characters;
  - **story**: its own, nothing from Wrath Remix;
  - **first version**: affection and endings, in "a base playable small story just to demo the
    engine";
  - **game files**: "I already have the client but if the tools wago would be easier we can do
    that" → wago.tools;
  - **the demo's place**: Broken Isles Dalaran (Legion's);
  - **the demo's routes**: one lore character, Alleria Windrunner, and one original character
    (race left to us: "Surprise me" → a Nightborne). Later: "The original character will be
  female".
- "Design a completely new app called Wifecraft", package `com.logie.wifecraft` (asked: the
  repository's form, not `logie.com.wifecraft`). "Create original menus etc or reuse assets."
- On the first mockups (menus of our own): "Umm for the screens I wanted you to reuse the wow ui".
  Asked, the user chose **the current WoW interface** (today's game files on wago.tools, the same
  source as the models) and **affection as WoW reputation** (Neutral to Exalted) instead of hearts.

## Decided

- **The app**: `com.logie.wifecraft`, "Wifecraft", folder `wifecraft/`, releases
  `Wifecraft 1.0.N`, tag `wifecraft-N`. Kotlin and Compose like the libretro apps (way B in
  `docs/single-game-apps.md`, `parasiteeve/` as the template for the shell: title screen, settings,
  updater, unit tests, CI), with no emulator: its engine is its own.
- **The menus are WoW's own interface**, the current game's (the canvas): its textures, drawn
  by Compose from the game's files, which the app fetches like the rest (never bundled). Each screen
  is a WoW window:

  | Screen | WoW's | Files (file id; atlas member from `UiTextureAtlasMember`) |
  | --- | --- | --- |
  | Title, Gathering Dalaran | The login and loading screens | Loading art `loadscreen_camelot_dalaran.blp` (7963775) as the first launch's backdrop; loading bar `loading-barborder.blp` (131811), `loading-barfill.blp` (131812) |
  | Every button | The red panel button | `128redbutton.blp` (1536801): `128-redbutton-left`, `_128-redbutton-center`, `128-redbutton-right` (and their `-disabled`, `-pressed`) |
  | Panels (menu, character, saves) | The metal window frame | `ui-frame-diamondmetal-*` corners and edges (3056750, 3056755) over `ui-background-rock.blp` (374155) |
  | Dialogue | Legion's talking head | `talkingheads.blp` (1111217): `talkingheads-textbackground` (dark, default), `talkingheads-neutral-textbackground` (parchment), `-portraitframe`, `-portraitbg` |
  | Choice | The gossip window | `questbg-parchment` (3813080) inside the metal frame; `gossipgossipicon.blp` (132053) before each choice |
  | Bonds | The reputation pane | `ui-frame-bar-*` border, background and `-fill-green/-yellow/-blue` (2139076) |
  | An ending | The achievement toast | `ui-achievement-alert-background`, `ui-achievement-iconframe` (4035524) |
  | Text | The game's fonts | Friz Quadrata `fonts/frizqt__.ttf` (615960), Morpheus `fonts/morpheus.ttf` (615962) for titles |

  Colours are the game's: gold `#FFD100` for names and buttons, white words with a 1 px black
  shadow, dark brown on parchment. The files are fetched first on the first launch (a few MB) so the
  menus are WoW's from the start; until they're in, the Gathering screen alone draws plainly.
  Atlas pieces are cut by `UiTextureAtlasMember` (CommittedLeft/Right/Top/Bottom) from wago.tools'
  table export; 2x pieces are drawn at half size. The game's newer textures are mostly
  uncompressed BLP2 (encoding 3, BGRA), the rest DXT: the app decodes both.
- **The title**: "Wifecraft" in Morpheus, gold, over Dalaran; "A Dalaran Romance" in Friz.
- **Game files from wago.tools**, on the phone. Nothing from the game goes into the repository,
  the APK or CI (`docs/single-game-apps.md`): on first launch the app fetches the files the demo
  needs by file id (`https://wago.tools/api/casc/<id>?download`, as
  `warcraft/modules/mod-wrath-remix/tools/models.py` does) and the tables it needs (the game's DB2
  tables, from wago.tools' table exports), into `files/cache/`. A list of the demo's file ids is
  made by a tool here from the community listfile and the tables, and committed (ids only). The
  user's own client is the fallback for any file wago.tools doesn't have (a PC-side export, later).
- **The renderer reads the game's own formats** (M2 models with their skins and animations, WMO
  buildings, ADT terrain, BLP textures) on the phone, in native code (OpenGL ES 3), so nothing has
  to be converted to glTF and nothing converted is ever published.
- **The screen** (like the user's screenshot): the live scene fills it; the speaker is framed from
  the waist up in the middle (two speakers: left and right); the talking-head box sits low with the
  name in gold at its top; the words appear a few at a time; a gold arrow shows when a line is done.
  No buttons on screen: gestures.
- **Expressions are emotes**: each line can name one (talk, exclamation, question, laugh, cry, shy,
  kiss, wave, bow, point, cheer, yes, no, and the standing idle in between). Which ones each model
  has is checked per model (stage 4); a missing one falls back to talk.
- **Affection is reputation**: one number per route, 0 to 100, changed by choices, shown as WoW's
  reputation bar and standing in the pause menu's Reputation pane: Neutral 0–19, Friendly 20–39,
  Honored 40–59, Revered 60–79, Exalted 80–100, with a line on how that character sees the player.
  The choices themselves don't show numbers.
- **Endings** (five in the demo): for each route a romance ending (Revered or higher on that route
  at the end) and a friendship ending (Friendly or Honored), and one ending alone (Neutral with
  both). An ending is shown as an achievement toast ("Ending Unlocked") and, once seen, listed in
  the title screen's Endings.
- **Saves**: an autosave at every scene, six slots, each with a still of the scene, the chapter,
  the place and the time (`files/saves/`, JSON plus a PNG, written atomically).
- **Original character**: Nyrelle Moonquill, a Nightborne arcanist, new to the world outside
  Suramar's dome, keeping an enchanting stall in the Magus Commerce Exchange. Female (the user's
  choice); her name is ours and can change.

## The screens (the canvas)

1. **Title** (WoW's login screen): live Dalaran behind, "Wifecraft"; Continue and New Story
   centred low, Load, Endings, Options, Updates at the bottom right, the version bottom left.
2. **First launch: Gathering Dalaran** (a loading screen): the Dalaran loading art, WoW's loading
   bar, what is being fetched (the interface first, then the city, Alleria, Nyrelle, the player's
   looks, emotes and light), Pause, Wi-Fi only, and "Use my own client's files". The story can
   start once the first scene's files are in.
3. **Your character**: the player's model turning in the Legerdemain Lounge (swipe to turn, pinch
   to zoom), a metal-framed panel: name, race, body, skin, face, hair, hair colour, outfit;
   "Arrive in Dalaran".
4. **Dialogue**: the screenshot's layout over the live scene, the words in Legion's talking-head
   box (dark, or parchment as a setting), the name in gold.
5. **Choice**: the scene dimmed, a gossip window: the question, two to four choices with the
   gossip icon.
6. **Pause and Reputation**: the Game Menu (Resume, Save, Load, What Was Said, Options, Title
   Screen) beside the Reputation pane (each route's portrait, bar and standing).
7. **Save and load**: six slots and the autosave in a metal frame; Save, Load, Delete, Back.
8. **An ending**: the achievement toast ("Ending Unlocked", its name, "1/5"); Credits, Endings,
   Title Screen.

Settings (not drawn): text speed, auto speed, text box style and opacity, vibration, graphics
(resolution scale, view distance, shadows).

## Gestures (proposed; the user decides)

| Gesture | Does |
| --- | --- |
| Tap | Show the whole line; tap again for the next |
| Hold | Fast-forward through lines already read, while held |
| Swipe up | What was said (the log) |
| Swipe down | Hide the text box (look at the scene); a tap brings it back |
| Two-finger tap, or Android's back | The pause menu |
| On a choice: tap | Choose it |
| Character screen: swipe / pinch | Turn / zoom the model |

## The demo story (outline)

Set after the Legion's defeat, in Dalaran over the Broken Isles; its own story, not the game's.

- **Prologue**: the character screen; arriving at Krasus' Landing.
- **Chapter I, Morning**: Alleria at Krasus' Landing, watching the Isles; a collision with Nyrelle
  in the Magus Commerce Exchange (her spilled reagents). Small choices move both.
- **Chapter II, Afternoon**: the choice of the canvas: walk Krasus' Landing with Alleria (the Void
  she carries, the family she came back to), help Nyrelle at her stall (an enchantment gone wrong;
  ten thousand years under a dome), or wander alone (a short scene with each).
- **Chapter III, Night**: Alleria at Krasus' Landing, or Nyrelle on a balcony of the Violet
  Citadel; the ending is picked by that route's affection.
- Endings: Windrunner's Promise (Alleria, romance), Arrows at Dusk (Alleria, friendship), Beyond
  the Dome (Nyrelle, romance), A Stall in the Exchange (Nyrelle, friendship), A City of Strangers
  (alone).

## The story script

Plain text, one file per scene (`wifecraft/story/*.wfs`, committed: the story is ours), read by a
small parser in plain Kotlin (unit-tested on the JVM like the gesture code). A sketch:

```
scene krasus_landing dusk
show alleria center bust
alleria [talk] The Isles look calm from up here. I have learned not to trust calm.
player [question] Then why come up here at all?
alleria [laugh] Because it's quiet.
choice
  "It's a good place to think." -> alleria +10
  "I'd rather be somewhere livelier." -> alleria -5, nyrelle +5
end
if alleria >= 60 jump alleria_romance
```

Commands: `scene <place> <light>`, `show/hide <who> <where> <shot>`, `<who> [<emote>] <words>`,
`move <who> <where>`, `camera <shot>`, `choice ... end`, `<route> +/-<n>`, `if ... jump`,
`jump`, `ending <id>`. Places are named spots in the world (position, facing, the camera's
shots) in `wifecraft/story/places.txt`; people are named looks (a creature display, or race,
customization choices and outfit items) in `people.txt`.

## Stages

0. **Prove the renderer**: fetch Alleria's model and Krasus' Landing's buildings and terrain from
   wago.tools; draw Alleria from the waist up, playing talk, against Dalaran, on the desktop
   (headless EGL to a PNG, as the libretro cores are proved) and in an Android build. Decide there:
   an existing open-source WoW renderer (WebWowViewerCpp is the candidate to evaluate: its
   license, its OpenGL ES path, the newest model versions) or our own.
1. **The app shell**: title, Gathering Dalaran, settings, updater, `build-wifecraft.yml`, release.
2. **The story engine**: the script, affection, saves, the log; the dialogue, choice, pause and
   save screens over a still backdrop.
3. **The world**: Dalaran's spots, the camera's shots, the world's light by time of day.
4. **The people**: Alleria (a creature look), the emotes per model; the player and Nyrelle
   (character customization and outfits: skin, face, hair and item textures combined).
5. **The demo story** written in full, its five endings.
6. **On the phone**: speed on a Galaxy Z Fold 8 (folded and unfolded), loading, battery.

Later, not chosen for the first version: music and the game's sounds, voices, a gallery of scenes,
more routes and places.

## Open

- The gestures above.
- Nyrelle's name; the endings' names (ours, to change freely).
- Art: an app icon (the user's, as for the other apps, or one made from the game's art).
- Alleria's exact look (which of her creature displays) and every file id: from wago.tools' tables
  in stage 0, not guessed.
- How big the first download is: unknown until the demo's file list exists.
- Whether wago.tools can serve one pinned game build (so a game patch can't change the demo's
  files under it), or the app keeps whatever it fetched first.

## Where it stands

Designed: this brief and the canvas. No code, no CI workflow, no release.
