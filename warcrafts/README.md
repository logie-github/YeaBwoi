# Warcraft

Warcraft: Orcs & Humans Remastered, Warcraft II: Tides of Darkness Remastered and Warcraft III
(Reign of Chaos and The Frozen Throne, 1.30.4) on an Android phone, from your own game files, run
by Wine and Box64 inside the app. It is a launcher like the World of Warcraft app's: each game has
its own look (its picture, logo, colours and weather), Press start goes straight into the game, and
you play with touch gestures. Nothing connects to Blizzard: Battle.net and Blizzard's servers are
blocked for every game.

| | |
| --- | --- |
| Package | `com.logie.warcrafts`, named Warcraft on the phone (installs next to the other apps) |
| Releases | `Warcraft 1.0.N`, tag `warcrafts-build-N` |
| Runtime | [Winlator](https://github.com/brunodev85/winlator) 11.2 (Wine, Box64, the graphics drivers), built into the app |

## What to import

The launcher says exactly what each game needs, item by item, under **What's needed**, and ticks
each item once it's in the app (choose the game first with **Games**, or the chip in the picture's
corner). Put the folder or zip on the phone itself first (Downloads is fine), then **Game files** →
**Pick folder** or **Pick zip**. A folder or zip with the game in a folder of its own is taken from
inside it. Importing again replaces what's there; saved games stay.

| Game | Bring | It must contain |
| --- | --- | --- |
| Warcraft III: Reign of Chaos and The Frozen Throne (one import serves both) | The Warcraft III 1.30 folder (the repack's folder as it unzips), or the zip itself (about 1.8 GB) | `Warcraft III.exe`; `Data\data`, `Data\indices`, `Data\config`; `.build.info`. Optional: `Maps` (skirmish and custom maps; the campaigns don't need it) and the CD keys (`_SERIAL_KEYS.txt`, or your own) |
| Warcraft: Orcs & Humans Remastered | The whole game folder from a PC with Battle.net (`C:\Program Files (x86)\Warcraft I Remastered` or wherever it's installed), or a zip of it | `x86\Warcraft.exe`; `Data\data`, `Data\indices`, `Data\config`; `.build.info` |
| Warcraft II: Tides of Darkness Remastered | The same, for `Warcraft II Remastered` | `x86\Warcraft II.exe`; `Data\data`, `Data\indices`, `Data\config`; `.build.info` |

`.build.info` is a hidden file: on Windows, View → Hidden items shows it; copying the whole folder
takes it along. The Remastered games' items are those of a Battle.net install as players report
them; no install of them was looked at for this app.

After an import, **Game files** shows the program that starts the game (you can pick another) and
what it's started with: nothing for Warcraft III, `-launch` for the Remastered games.

## First launch

1. Install the APK from the release page, open the app.
2. **Games** → the game, **Game files** → **Pick folder** or **Pick zip**. The import runs in the
   background with a notification; it can take several minutes for Warcraft III.
3. **Press start**. The first time, the app installs the games' Windows runtime (about a minute)
   and makes their Windows; after that it goes straight into the game.

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
| Two-finger tap | The game's own menu (F10: save, load, options, quit) |
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

The games run on the phone; no server is involved. Campaigns, custom games against the computer,
skirmishes and the games' own local network modes need nothing else. With **Private** on (the
default), the games' runtime looks names up in a hosts file the app writes into the runtime's
folder, which sends Battle.net's, the patch servers' and Blizzard's other names to nowhere: no game
can sign in, update itself or download anything. The Remastered games may insist on Battle.net to
start: that hasn't been tried, and with Private on they can't reach it.

## Saves

Saved games, replays and settings are in the games' Windows, inside the app (Warcraft III's in
`Documents\Warcraft III` of its user): they stay through updates and through importing the game
files again, and go only when the app is uninstalled.

## Updates

The app checks this repository's releases when it opens and offers newer builds; **Updates** checks
on demand. A private repository needs a read-only token as the `DOLPHINXD_UPDATE_TOKEN` secret, as
for the other apps.

## How it's built

| Path | Contents |
| --- | --- |
| `app.env` | The app's identity and release tag prefix |
| `winlator-version.env` | Winlator's commit (the World of Warcraft app's), the package and where the runtime goes (`/data/data/com.logie.warcrafts/winfs`, the same length as Winlator's own path) |
| `patches/winlator/` | Changes to Winlator: the runtime in the app's folder, its screens replaced by the app's, the app's controls over the game |
| `../scripts/prepare-winlator-app.sh` | Brings Winlator into `android/winlator` (git-ignored) with the patches, and rewrites its runtime's install path |
| `android/app/` | The app (Kotlin, Jetpack Compose) |
| `branding/` | The user's art and `make-art.py`, which makes the pictures, logos and launcher icon |
| `fonts/` | Licenses of the bundled fonts (Cinzel, Jost: SIL OFL) |
| `unit-tests/` | JVM tests of the gestures, finding a game in the files picked, the checklist, CD keys, the hosts file, resolutions and update picking |

```
scripts/prepare-winlator-app.sh warcrafts
gradle -p warcrafts/unit-tests test
cd warcrafts/android && ./gradlew --init-script ../../scripts/maven-mirror.init.gradle :app:assembleRelease \
    -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

CI (`.github/workflows/build-warcrafts.yml`) runs the tests, builds the APK and publishes it.

## License

The app's code is GPL-3.0-or-later; Winlator is LGPL-2.1 (its sources: the commit in
`winlator-version.env`). No game files are in the repository, the APK or CI: you bring your own.
This project isn't affiliated with Blizzard Entertainment or Winlator's developers; Warcraft is a
trademark of Blizzard Entertainment.
