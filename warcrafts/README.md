# Warcraft

Warcraft: Orcs & Humans and Warcraft II: Battle.net Edition (the GOG releases), played natively on
an Android phone by the Stratagus engine, and Warcraft III (Reign of Chaos and The Frozen Throne,
1.30.4) run by Wine and Box64 inside the app, all from your own files. It is a launcher like the
World of Warcraft app's: each game has its own look (its picture, logo, colours and weather),
Press start goes straight into the game, and you play with touch gestures. Nothing connects to
Blizzard.

| | |
| --- | --- |
| Package | `com.logie.warcrafts`, named Warcraft on the phone (installs next to the other apps) |
| Releases | `Warcraft 1.0.N`, tag `warcrafts-build-N` |
| Warcraft I and II | [Stratagus](https://github.com/Wargus/stratagus) with [War1gus](https://github.com/Wargus/war1gus) and [Wargus](https://github.com/Wargus/wargus), built for arm64 into the app (`stratagus-version.env`) |
| Warcraft III | [Winlator](https://github.com/brunodev85/winlator) 11.2 (Wine, Box64, the graphics drivers), built into the app |

## What to import

The launcher says exactly what each game needs, item by item, under **What's needed**, and ticks
each item once it's in the app (choose the game first with **Games**, or the chip in the picture's
corner). Put the folder or zip on the phone itself first (Downloads is fine), then **Game files** →
**Pick folder** or **Pick zip**. A folder or zip with the game in a folder of its own is taken from
inside it. Importing again replaces what's there; saved games stay.

| Game | Bring | It must contain |
| --- | --- | --- |
| Warcraft: Orcs & Humans (GOG) | The installed game folder (`C:\GOG Games\Warcraft Orcs & Humans` or wherever GOG put it), or a zip of it | `DATA\DATA.WAR` and the other `.WAR` files in `DATA` |
| Warcraft II: Battle.net Edition (GOG) | The installed game folder (`C:\GOG Games\Warcraft II BNE` or wherever GOG put it), or a zip of it | `Support\TOMES\TOME.1` to `TOME.4`, and `Install.mpq` |
| Warcraft III: Reign of Chaos and The Frozen Throne (one import serves both) | The Warcraft III 1.30 folder (the repack's folder as it unzips), or the zip itself (about 1.8 GB) | `Warcraft III.exe`; `Data\data`, `Data\indices`, `Data\config`; `.build.info`. Optional: `Maps` (skirmish and custom maps; the campaigns don't need it) and the CD keys (`_SERIAL_KEYS.txt`, or your own) |

**Warcraft I and II are converted for the phone** right after the import, by the app itself, in a
few minutes: War1gus's and Wargus's own tools (`war1tool`, `wartool`, built for Android) turn the GOG
files into the data Stratagus plays, beside the games' scripts and maps. **Converted for the phone**
is the last item of their checklist; **Game files → Convert again** redoes it. Left out, because
the tools make them with ffmpeg, which a phone doesn't have: the intro videos, and Warcraft II's
music (its sound effects and speech are in).

`.build.info` (Warcraft III) is a hidden file: on Windows, View → Hidden items shows it; copying
the whole folder takes it along. The GOG folders' contents are GOG's own lists (GOG Database);
the app hasn't been run on them here.

After a Warcraft III import, **Game files** shows the program that starts it (you can pick
another) and what it's started with.

## First launch

1. Install the APK from the release page, open the app.
2. **Games** → the game, **Game files** → **Pick folder** or **Pick zip**. The import runs in the
   background with a notification; it can take several minutes for Warcraft III.
3. **Press start**. The first time, the app installs the games' Windows runtime (about a minute)
   and makes their Windows; after that it goes straight into the game.

**Warcraft I and II** open on Stratagus's own menus (War1gus, Wargus), with the campaigns and
skirmish of the original games; saves and preferences stay in the app.

**Warcraft III's first start**: the 1.30 repack opens a black window for up to a minute, then asks
how to install: choose the classic installer that asks for CD keys. Four fingers open the app's
menu: the CD keys from the repack's `_SERIAL_KEYS.txt` are listed there, and a tap types one into
the game's key box (tap the box first). **Reign of Chaos or The Frozen Throne**: the game's own main
menu switches between them (since patch 1.28.3) and remembers; the launcher's two looks are the
same game files.

## Controls

Nothing is drawn over the game. The gestures are the ones you chose.

| Gesture | In the game |
| --- | --- |
| Swipe one finger | Pans the map the way you swipe, for as long as the finger stays away from where it landed; lift to stop |
| Tap | Right click: move, attack, gather, set a rally point |
| Touch and hold | Left click when you lift (after a short buzz): select a unit or building, press a button of the game's panel or menus |
| Hold twice on the same spot | Double click: every unit of that kind on the screen |
| Rest one finger, tap or hold with another | Shift-click: queue orders, add units to the selection |
| Two-finger tap | The game's own menu (F10: save, load, options, quit), in all three games |
| Pinch | Zoom (the mouse wheel), where the game has it |
| Three-finger tap | The phone's keyboard, for chat and cheat codes (sent with Enter) |
| Four-finger tap | The app's menu: keyboard, CD keys, leave the game |
| Back | Escape: cancel what's being aimed, close a window |

There's no selection box and no control groups (your choice). A mouse or keyboard connected to the
phone works as on a PC.

## The menus

- **Launcher**: Press start, **What's needed** (the checklist above), and tiles for **Game files**, **Games** (the four games, each by its
  logo, with whether its files are in), **Graphics**, **Controls** (the gestures) and **Updates**.
- **Game files**: what to import, the import, the program that starts the game and what it's
  started with, **Private** (on: Blizzard's servers are out of reach for every game), and deleting
  the game's files.
- **Graphics**: the games' resolution (the screen's own pixels, 1080 or 720 lines), how
  Warcraft III draws (Direct3D, or OpenGL with `-graphicsapi OpenGL2` if the picture is wrong) and
  the hold's vibration.
- **In the game** (four fingers): back to the game, keyboard, chat or cheat code, the CD keys that
  came with the game, leave the game. Save in the game first (two-finger tap, the game's menu):
  leaving ends it where it is.

## Galaxy Z Fold 8

- Folded (the outside screen) the launcher is one column; unfolded (the inside screen, about
  830 dp either way up) the picture is shorter and the game's panel with its checklist sits beside
  the menu.
- The games run at 1080 lines unless Graphics says otherwise: the outside screen's own, and on the
  inside a quarter of the pixels to draw. The inside screen is nearly square (about 10:9), so there
  the games are 4:3 with bars at the sides rather than squeezed; on the outside they're as wide as
  the screen.
- The game's size is set when it starts, for the screen the launcher is on then: unfolding during a
  game keeps it running at that size, scaled to the new screen.
- Touch distances are measured in dp, so the gestures feel the same on both screens.

## Offline

Everything runs on the phone; no server is involved. Warcraft I and II, native, never go online.
For Warcraft III, with **Private** on (the default), the runtime looks names up in a hosts file the
app writes into its folder, which sends Battle.net's, the patch servers' and Blizzard's other names
to nowhere: it can't sign in, update itself or download anything. Campaigns, custom games against
the computer and local network games don't need any of that.

## Saves

Warcraft I and II keep their saves and preferences beside their converted data in the app; they
stay through updates (converting again replaces the data, and the saves with it). Warcraft III's
saves, replays and settings are in the games' Windows, inside the app (`Documents\Warcraft III` of
its user): they stay through updates and through importing its files again. All of them go only
when the app is uninstalled.

## Updates

The app checks this repository's releases when it opens and offers newer builds; **Updates** checks
on demand. A private repository needs a read-only token as the `DOLPHINXD_UPDATE_TOKEN` secret, as
for the other apps.

## How it's built

| Path | Contents |
| --- | --- |
| `app.env` | The app's identity and release tag prefix |
| `stratagus-version.env` | Stratagus, War1gus and Wargus: the pinned commits, and the NDK |
| `patches/stratagus/`, `patches/stratagus-deps/` | Changes to Stratagus (its Android library, OpenGL ES, no shaders, no edge scrolling, text without the phone's keyboard) and to its bundled libraries (configured for Android) |
| `stratagus/tools/` | How `war1tool` and `wartool` are built for Android |
| `../scripts/build-stratagus.sh` | Builds the engine (`libmain.so`, SDL in it), the tools (`libwar1tool.so`, `libwartool.so`), SDL's Java activity and the games' scripts into `android/stratagus` (git-ignored) |
| `winlator-version.env` | Winlator's commit (the World of Warcraft app's), the package and where the runtime goes (`/data/data/com.logie.warcrafts/winfs`, the same length as Winlator's own path) |
| `patches/winlator/` | Changes to Winlator: the runtime in the app's folder, its screens replaced by the app's, the app's controls over the game |
| `../scripts/prepare-winlator-app.sh` | Brings Winlator into `android/winlator` (git-ignored) with the patches, and rewrites its runtime's install path |
| `android/app/` | The app (Kotlin, Jetpack Compose) |
| `branding/` | The user's art and `make-art.py`, which makes the pictures, logos and launcher icon |
| `fonts/` | Licenses of the bundled fonts (Cinzel, Jost: SIL OFL) |
| `unit-tests/` | JVM tests of the gestures, finding a game in the files picked, the checklist, CD keys, the hosts file, resolutions and update picking |

```
scripts/build-stratagus.sh
scripts/prepare-winlator-app.sh warcrafts
gradle -p warcrafts/unit-tests test
cd warcrafts/android && ./gradlew --init-script ../../scripts/maven-mirror.init.gradle :app:assembleRelease \
    -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

CI (`.github/workflows/build-warcrafts.yml`) runs the tests, builds the APK and publishes it.

## License

The app's code is GPL-2.0-or-later; it runs Stratagus, War1gus and Wargus (GPL-2.0) and Winlator
(LGPL-2.1), whose sources are at the commits in `stratagus-version.env` and `winlator-version.env`.
No game files are in the repository, the APK or CI: you bring your own. This project isn't
affiliated with Blizzard Entertainment, GOG, the Stratagus team or Winlator's developers; Warcraft
is a trademark of Blizzard Entertainment.
