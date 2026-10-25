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
    (race left to us: "Surprise me" → a Nightborne).
- "Design a completely new app called Wifecraft", package `com.logie.wifecraft` (asked: the
  repository's form, not `logie.com.wifecraft`). "Create original menus etc or reuse assets."

## Decided

- **The app**: `com.logie.wifecraft`, "Wifecraft", folder `wifecraft/`, releases
  `Wifecraft 1.0.N`, tag `wifecraft-N`. Kotlin and Compose like the libretro apps (way B in
  `docs/single-game-apps.md`, `parasiteeve/` as the template for the shell: title screen, settings,
  updater, unit tests, CI), with no emulator: its engine is its own.
- **Menus are original**, made for Wifecraft, not the game's interface art: the menus are there
  before anything is downloaded (the first launch), and they give the app a look of its own. The
  game's assets are used for what's inside the scene: the world, the people, their looks, emotes
  and the world's light.
- **The look** (the canvas): Dalaran at night. Ground `#1B1431`, panels `#221A3D`, gold trim
  `#E9C873` (bright `#F6E2A6`), violet lines `#6E5A9E`, cream text `#F2E8D5`, rose for affection
  `#E07A96`. Cinzel for the logo, names and buttons; Alegreya Sans for the story's words (both SIL
  OFL, already licensed in `warcraft/fonts/`). Square-cornered panels with a 1 px gold or violet
  line; gold diamonds at the text box's corners. The text box is night (dark, translucent) by
  default, parchment (light) as a setting.
- **The sigil and icon**: a heart inside a dashed rune ring inside a gold diamond (the canvas's
  title screen), unless the user sends art.
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
  the waist up in the middle (two speakers: left and right); the text box sits low with the name in
  a tab on its top left; the words appear a few at a time; a gold arrow shows when a line is done.
  No buttons on screen: gestures.
- **Expressions are emotes**: each line can name one (talk, exclamation, question, laugh, cry, shy,
  kiss, wave, bow, point, cheer, yes, no, and the standing idle in between). Which ones each model
  has is checked per model (stage 4); a missing one falls back to talk.
- **Affection**: one number per route, 0 to 100, changed by choices; shown as five hearts in the
  pause menu's Bonds (20 a heart) with a line on how that character sees the player. The choices
  themselves don't show numbers.
- **Endings** (five in the demo): for each route a romance ending (60 or more on that route at the
  end) and a friendship ending (30 to 59), and one ending alone (neither). An ending, once seen, is
  listed in the title screen's Endings.
- **Saves**: an autosave at every scene, six slots, each with a still of the scene, the chapter,
  the place and the time (`files/saves/`, JSON plus a PNG, written atomically).
- **Original character**: Nyrelle Moonquill, a Nightborne arcanist, new to the world outside
  Suramar's dome, keeping an enchanting stall in the Magus Commerce Exchange. Female (assumed; to
  confirm); her name is ours and can change.

## The screens (the canvas)

1. **Title**: live Dalaran behind, the sigil and WIFECRAFT, "A Dalaran Romance", `[ tap to begin ]`;
   Continue, New story, Load, Endings, Settings, Updates.
2. **First launch: Gathering Dalaran**: what is being fetched (the city, Alleria, Nyrelle, the
   player's looks, emotes and light), progress, Pause, Wi-Fi only, and "Use files from my own
   client instead". The story can start once the first scene's files are in.
3. **Your character**: the player's model turning in the Legerdemain Lounge (swipe to turn, pinch
   to zoom); name, race, body, skin, face, hair, hair colour, outfit; "Arrive in Dalaran".
4. **Dialogue**: the screenshot's layout over the live scene.
5. **Choice**: the scene dimmed, two to four choices.
6. **Pause and Bonds**: Resume, Save, Load, What was said (the log), Settings, Title screen; each
   route's hearts.
7. **Save and load**: six slots and the autosave.
8. **An ending**: its name, "Ending N of 5", Credits, Endings found, Title screen.

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
- Nyrelle's name and sex; the endings' names (ours, to change freely).
- Art: the user's (as for the other apps) or the canvas's sigil.
- Alleria's exact look (which of her creature displays) and every file id: from wago.tools' tables
  in stage 0, not guessed.
- How big the first download is: unknown until the demo's file list exists.
- Whether wago.tools can serve one pinned game build (so a game patch can't change the demo's
  files under it), or the app keeps whatever it fetched first.

## Where it stands

Designed: this brief and the canvas. No code, no CI workflow, no release.
