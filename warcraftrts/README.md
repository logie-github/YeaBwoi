# Warcraft Collection

Warcraft: Orcs & Humans Remastered, Warcraft II: Tides of Darkness Remastered and Warcraft III
(Reign of Chaos and The Frozen Throne, 1.30.4) on an Android phone, from your own game files, run
by Wine and Box64 inside the app. It is a launcher like the World of Warcraft app's: each game has
its own look (its picture, logo, colours and weather), Press start goes straight into the game, and
you play with touch gestures. Nothing connects to Blizzard: Battle.net and Blizzard's servers are
blocked for every game.

| | |
| --- | --- |
| Package | `com.logie.warcraftrts` (installs next to the other apps) |
| Releases | `Warcraft Collection 1.0.N`, tag `wcrts-build-N` |
| Runtime | [Winlator](https://github.com/brunodev85/winlator) 11.2 (Wine, Box64, the graphics drivers), built into the app |

## What to import

Each game is imported once, from **Game files** (choose the game first with **Games**, or the chip
in the picture's corner). Put the folder or zip on the phone itself first (Downloads is fine), then
**Pick folder** or **Pick zip**. A folder or zip with the game in a folder of its own is taken from
inside it. Importing again replaces what's there; saved games stay.

| Game | What to bring |
| --- | --- |
| Warcraft III: Reign of Chaos and The Frozen Throne | The Warcraft III 1.30 folder: the one with `Warcraft III.exe`, `Data/` and `Maps/` in it (the 1.30 repack's folder as it unzips), or the zip itself (about 1.8 GB). One import serves both games. |
| Warcraft: Orcs & Humans Remastered | The game's install folder from a PC (Battle.net puts it in `Program Files (x86)`), with the game's `.exe` in it, or a zip of it. |
| Warcraft II: Tides of Darkness Remastered | The same, for Warcraft II Remastered. |

After an import, **Game files** shows the program that starts the game (chosen by itself: you can
pick another) and what it's started with: nothing for Warcraft III, `-launch` for the Remastered
games (the way past Battle.net's launcher that Blizzard's other games take).

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

Nothing is drawn over the game. The finger is the mouse pointer.

| Gesture | In the game |
| --- | --- |
| Tap | Left click: select, press a button of the game's panel or menus |
| Two quick taps | Double click: every unit of that kind on the screen |
| Touch and hold | Right click: move, attack, gather, set a rally point (a short buzz) |
| Drag one finger | Selection box (the left button held from where the finger landed), sliders |
| Two-finger tap | Right click where the first finger landed |
| Drag two fingers | Scroll the camera (the arrow keys), the way a map follows the finger; it keeps scrolling while the fingers stay away from where they landed. Tapping the minimap jumps there. |
| Pinch | Zoom (the mouse wheel), where the game has it |
| Three-finger tap | Show or hide the quick keys |
| Four-finger tap | The app's menu |
| Back | Escape: cancel an order, close a menu, the game's menu |

### Quick keys

What these games need a keyboard for, in a strip at the top of the screen (▼ moves it to the
bottom, ✕ hides it). The game goes on under it and the rest of the screen keeps its gestures.

| Key | What it does |
| --- | --- |
| Shift | Held until tapped again: orders queue up, clicks add to the selection |
| Ctrl, Alt | Held until tapped again (Alt shows health bars in Warcraft III) |
| 1 … 0 | Tap: select that control group. Hold: make the selection that group (Ctrl+number) |
| F1 F2 F3 | Warcraft III's heroes (a second tap centres on the hero) |
| Tab | The next kind of unit in the selection (Warcraft III) |
| Space | Jump to the last event (Warcraft III) |
| F10 | The game's menu (Warcraft III) |
| Esc, Chat, Type | Escape; Enter then a line or a cheat code from the phone's keyboard; text into a box |

A mouse or keyboard connected to the phone works as on a PC.

## The menus

- **Launcher**: Press start, and tiles for **Game files**, **Games** (the four games, each by its
  logo, with whether its files are in), **Graphics**, **Controls** (the gestures) and **Updates**.
- **Game files**: what to import, the import, the program that starts the game and what it's
  started with, **Private** (on: Blizzard's servers are out of reach for every game), and deleting
  the game's files.
- **Graphics**: the games' resolution (the screen's own pixels, 1080 or 720 lines), how
  Warcraft III draws (Direct3D, or OpenGL with `-graphicsapi OpenGL2` if the picture is wrong) and
  the hold's vibration.
- **In the game** (four fingers): back to the game, keyboard, chat or cheat code, the CD keys that
  came with the game, leave the game. Save in the game first: leaving ends it where it is.

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
| `winlator-version.env` | Winlator's commit (the World of Warcraft app's), the package and where the runtime goes (`/data/data/com.logie.warcraftrts/rfs`, the same length as Winlator's own path) |
| `patches/winlator/` | Changes to Winlator: the runtime in the app's folder, its screens replaced by the app's, the app's controls over the game |
| `../scripts/prepare-winlator-app.sh` | Brings Winlator into `android/winlator` (git-ignored) with the patches, and rewrites its runtime's install path |
| `android/app/` | The app (Kotlin, Jetpack Compose) |
| `branding/` | The user's art and `make-art.py`, which makes the pictures, logos and launcher icon |
| `fonts/` | Licenses of the bundled fonts (Cinzel, Jost: SIL OFL) |
| `unit-tests/` | JVM tests of the gestures, finding a game in the files picked, CD keys, the hosts file, resolutions and update picking |

```
scripts/prepare-winlator-app.sh warcraftrts
gradle -p warcraftrts/unit-tests test
cd warcraftrts/android && ./gradlew --init-script ../../scripts/maven-mirror.init.gradle :app:assembleRelease \
    -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

CI (`.github/workflows/build-warcraftrts.yml`) runs the tests, builds the APK and publishes it.

## License

The app's code is GPL-3.0-or-later; Winlator is LGPL-2.1 (its sources: the commit in
`winlator-version.env`). No game files are in the repository, the APK or CI: you bring your own.
This project isn't affiliated with Blizzard Entertainment or Winlator's developers; Warcraft is a
trademark of Blizzard Entertainment.
