# Wifecraft

A Warcraft dating sim for Android, laid out like a visual novel: a speaker from the waist up, a
text box with their name, choices. The people are the game's own models playing the game's emotes,
and the scenes are Legion's Dalaran over the Broken Isles, drawn live in 3D behind them. The menus
are WoW's own interface: the talking-head box, the gossip window, the red buttons, reputation bars
for affection, the achievement toast for an ending.

| | |
| --- | --- |
| App | Wifecraft (`com.logie.wifecraft`), Android 8 or later, 64-bit ARM, OpenGL ES 3 |
| The demo | *A Day in Dalaran*: a prologue flyover, three chapters (morning, afternoon, night), two routes, five endings |
| The routes | Alleria Windrunner, and Nyrelle Moonquill (a night elf huntress, a Sentinel far from Darnassus) with her nightsaber Shadowpaw |
| You | A Kirin Tor Invoker, man or woman, with your own name |
| The game's files | Fetched once from [wago.tools](https://wago.tools) on the first launch (about 311 MB), kept on the phone; none are in the app |
| Music and sounds | The game's own: Legion's Dalaran by day, dusk and night, Alleria's theme, Darnassus and the Temple of the Moon for Nyrelle, the city's ambience, the characters' emote voices, Shadowpaw's roars, the interface's clicks and the achievement fanfare |

## First launch

1. Open the app. The title says the demo needs Dalaran's files: tap **Gather Dalaran**.
2. **Start** fetches them: the interface first (so the screens turn into WoW's), then the
   people, the city and its props, then the music and sounds (the story can start before those
   arrive; it plays silent until they do). **Pause** stops; **Resume** carries on where it stopped (files
   already fetched are kept). **Wi-Fi only** (on by default) waits for Wi-Fi.
3. When it says "Dalaran is ready", **Back** to the title and **New Story**.
4. **Who Are You?**: your name and look, then **Arrive in Dalaran**.

## Playing

| Gesture | Does |
| --- | --- |
| Tap | Show the whole line; tap again for the next |
| Hold | Fast-forward through the lines, while held |
| Swipe up | What Was Said (the log) |
| Swipe down | Hide the text box to look at the scene; tap to bring it back |
| Two-finger tap, or Android's back | The Game Menu |
| On a choice | Tap the answer |

The cutscenes (the flyover, the saber's charge, walks and camera moves) play on their own; a tap
or a hold skips ahead through them as through lines.

**Affection** is each route's reputation, Neutral to Exalted, shown in the Game Menu. The night's ending depends on whom you spend the evening with and
how they feel about you: Revered or better is the romance ending, less is the friendship one.

**Endings**: Windrunner's Promise and Arrows at Dusk (Alleria), Under Elune's Light and A Saber's
Trust (Nyrelle), A City of Strangers (on your own). Each found ending is kept in **Endings** on
the title screen.

## Menus

- **Game Menu**: Resume, Save, Load, What Was Said, Options, Title Screen, and the Reputation pane.
- **Save / Load**: six slots and the autosave (made at the start of each scene). **Continue** on
  the title loads the latest.
- **Options**: music and sound volume (0 is off); text speed (60 letters a second to start; at the far right, whole lines at once); the parchment talking head instead of Legion's dark one; Wi-Fi only.

## Updates

Coming from 1.0.3 or earlier, the title shows **Get Music**: it fetches the music and sounds
(16 MB) on the Gathering screen; the rest stays as it was.

The app checks this repository's releases (`wifecraft-N`) when it starts; **Updates** on the title
shows the newest and installs it (Android asks once to allow installs from the app). The update
keeps the game's files, saves and endings.

## How it's built

- **The renderer** (`android/app/src/main/cpp/engine`, C++17, OpenGL ES 3): the game's textures
  (BLP), models (M2 with their skins and animation files, skinned on the GPU, with character
  customization geosets, textures and shoulder attachments), and Dalaran's buildings (WMO groups
  with their placed props), lit by time of day with fog. It draws only what the camera sees.
- **The story** (`assets/story/demo.wfs`): a small script language (scenes, places and camera
  views from `assets/places.json`, lines, emotes, walks, choices, affection, endings), run by
  `story/StoryRunner.kt` and staged by `play/Director.kt`.
- **The looks, the sounds and the file list** come from the game's tables on wago.tools:
  `tools/looks.py` (writes `assets/looks.json`), `tools/sounds.py` (`assets/sounds.json`: which
  tracks, voices and effects) and `tools/manifest.py` (`assets/files.json`).
- **Sound** (`audio/Audio.kt`): music crossfades and loops, the city's ambience loops under it,
  voices and effects play from a sound pool; the story picks the music (`music NAME`) and effects
  (`sound NAME`), voices follow the emotes.
- `engine-test/` is a desktop build of the renderer (headless EGL) used to check scenes off the
  phone. `unit-tests/` checks the script, the runner, every route of the demo and updates.
- CI: `.github/workflows/build-wifecraft.yml` runs the unit tests, builds the APK and publishes
  the release.

Wifecraft is a fan project, not affiliated with or endorsed by Blizzard Entertainment. World of
Warcraft and its characters, models and art are Blizzard's; the app downloads them for you and
doesn't include them.
