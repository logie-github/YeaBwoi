# Warcraft

Warcraft: Orcs & Humans and Warcraft II: Battle.net Edition (the GOG releases), played natively on
an Android phone by the Stratagus engine, and Warcraft III (Reign of Chaos and The Frozen Throne,
1.27) run by Wine and Box64 inside the app, all from your own files. It is a launcher like the
World of Warcraft app's: each game has its own look (its picture, logo, colours and weather),
Press start goes straight into the game, and you play with touch gestures. Nothing connects to
Blizzard.

| | |
| --- | --- |
| Package | `com.logie.warcrafts`, named Warcraft on the phone (installs next to the other apps) |
| Releases | `Warcraft 1.0.N`, tag `warcrafts-build-N` |
| Warcraft I and II | [Stratagus](https://github.com/Wargus/stratagus) with [War1gus](https://github.com/Wargus/war1gus) and [Wargus](https://github.com/Wargus/wargus), built for arm64 into the app (`stratagus-version.env`); GOG's installers unpacked by [innoextract](https://github.com/crazy-max/innoextract) |
| Warcraft III | [Winlator](https://github.com/brunodev85/winlator) 11.2 (Wine, Box64, the graphics drivers), built into the app |

## What to import

The launcher says exactly what each game needs, item by item, under **What's needed**, and ticks
each item once it's in the app (choose the game first with **Games**, or the chip in the picture's
corner). Put the files on the phone itself first (Downloads is fine), then **Game files** →
**Pick GOG installer** (Warcraft I and II), **Pick folder** or **Pick zip**. A folder or zip with
the game in a folder of its own is taken from inside it. Importing again replaces what's there;
saved games stay.

| Game | Bring | It must contain |
| --- | --- | --- |
| Warcraft: Orcs & Humans (GOG) | GOG's offline installer, as downloaded from the game's page in your GOG library (offline backup game installers): `setup_warcraft_….exe` and every `setup_warcraft_…-1.bin` (`-2.bin`…) that came with it. Or the installed game folder (`C:\GOG Games\…`), or a zip of it | `DATA\DATA.WAR` and the other `.WAR` files in `DATA` (the installer has them) |
| Warcraft II: Battle.net Edition (GOG) | GOG's offline installer, as above: `setup_warcraft_ii_….exe` and its `.bin` files. Or the installed game folder (`C:\GOG Games\…`), or a zip of it | `Support\TOMES\TOME.1` to `TOME.4`, and `Install.mpq` (the installer has them) |
| Warcraft III: Reign of Chaos and The Frozen Throne | Nothing: set up in the app from Blizzard (below), with your two CD keys | — |

**GOG's installer** (Inno Setup) is unpacked on the phone by innoextract, built for Android into the
app: **Pick GOG installer**, then in the picker touch and hold the setup `.exe` and tap each `.bin`
beside it, then confirm (or put them in a folder of their own inside Downloads and **Pick folder**).
If a `.bin` is missing, the app says so. The installer is copied into the app first and deleted
once unpacked, so it needs room for the installer and about as much again. GOG Galaxy's small
downloader can't be used; the offline installer can.

**Warcraft I and II are converted for the phone** right after the import, by the app itself, in a
few minutes: War1gus's and Wargus's own tools (`war1tool`, `wartool`, built for Android) turn the GOG
files into the data Stratagus plays, beside the games' scripts and maps. **Converted for the phone**
is the last item of their checklist; **Game files → Convert again** redoes it. Left out, because
the tools make them with ffmpeg, which a phone doesn't have: the intro videos, and Warcraft II's
music (its sound effects and speech are in).

`.build.info` (Warcraft III) is a hidden file: on Windows, View → Hidden items shows it; copying
the whole folder takes it along. The GOG folders' contents are GOG's own lists (GOG Database);
the app hasn't been run on them, nor on GOG's installers, here (innoextract was tested on Inno Setup
6.2 to 6.7 installers).

**Fullscreen**: every game fills the screen. Warcraft I and II run Stratagus fullscreen (`-F`);
Warcraft III fills the screen at its 4:3 resolution (registry `reswidth`/`resheight`, Wine's desktop
the same size), drawing with OpenGL (`-opengl`, as the maintained Wine setups for 1.27 do; Direct3D
in Graphics). Wine's Light visual style is on for any Windows dialog. The runtime has no network at all while
Private is on (no name server; only `localhost` resolves): to the game, a PC without internet.

## First launch

1. Install the APK from the release page, open the app.
2. **Games** → the game, **Game files** → **Pick GOG installer** (Warcraft I and II), **Pick
   folder** or **Pick zip**. The import runs in the background with a notification; it can take
   several minutes.
3. **Press start**. The first time, the app installs the games' Windows runtime (about a minute)
   and makes their Windows; after that it goes straight into the game.

**Warcraft I and II** open on Stratagus's own menus (War1gus, Wargus), with the campaigns and
skirmish of the original games; saves and preferences stay in the app.

**If a game stops by itself**, the launcher shows what it said last when you come back: the
engine's output (`stderr.txt`, `stdout.txt` in its folder, with a crash's signal and stack) and
Android's log for the game's process. **Copy** puts it on the clipboard to send.

**Setting up Warcraft III (1.27), on the phone**: **Games → Warcraft III → Game files**:
1. **Download from Blizzard**: the app fetches Blizzard's own classic installers (what Blizzard's
   legacy downloaders at `us.battle.net/download/getLegacy?product=WAR3|W3XP` fetch: the address
   and access token inside them, piece by piece from Blizzard's CDN), about 1.4 GB, each piece
   checked against Blizzard's checksum before it's kept. A stopped download goes on where it
   stopped.
2. **Install Reign of Chaos**: Blizzard's installer opens inside the app. Taps are plain clicks
   there; three fingers open the keyboard for your CD key.
3. **Install The Frozen Throne** the same way, with its key.
4. Back in the launcher, the installed game moves into the app and the installers are deleted.
Game files shows the three steps, ticks the done ones and offers only the next one's button.

**Reign of Chaos or The Frozen Throne**: the launcher's two looks start the same files, Reign of
Chaos with `Warcraft III.exe`, The Frozen Throne with `Frozen Throne.exe`.

## Controls

Nothing is drawn over the game. The gestures are the ones you chose.

| Gesture | In the game |
| --- | --- |
| Swipe one finger | Pans the map the way you swipe, for as long as the finger stays away from where it landed; lift to stop |
| Tap | Right click on the battlefield: move, attack, gather, set a rally point. Where a right click would do nothing, the left click: in Warcraft I and II on menus and buttons, and on the map when none of your units is selected (the tap selects); in Warcraft III on its bottom panel and top bar |
| Touch and hold | Left click when you lift (after a short buzz): select a unit or building, press a button of the game's panel or menus |
| Hold twice on the same spot | Double click: every unit of that kind on the screen |
| Rest one finger, tap or hold with another | Shift-click: queue orders, add units to the selection |
| Two-finger tap | The game's own menu (F10: save, load, options, quit), in all three games |
| Pinch | Zoom (the mouse wheel), where the game has it |
| Three-finger tap | The phone's keyboard, for chat and cheat codes (sent with Enter) |
| Four-finger tap | The app's menu: keyboard, chat, leave the game |
| Back | Escape: cancel what's being aimed, close a window |

There's no selection box and no control groups (your choice). A mouse or keyboard connected to the
phone works as on a PC.

## The menus

- **Launcher**: Press start, **What's needed** (the checklist above), and tiles for **Game files**, **Games** (the four games, each by its
  logo, with whether its files are in), **Graphics**, **Controls** (the gestures) and **Updates**.
- **Game files**: Warcraft I and II: what to import, the import, converting again, deleting.
  Warcraft III: its setup (download, install each game), **Private** (on: the games' Windows has no
  network at all), deleting.
- **Graphics**: the games' resolution (the screen's own pixels, 1080 or 720 lines), how
  Warcraft III draws (Direct3D, or OpenGL with `-opengl` if the picture is wrong) and
  the hold's vibration.
- **In the game** (four fingers): back to the game, keyboard, chat or cheat code, leave the game. Save in the game first (two-finger tap, the game's menu):
  leaving ends it where it is.

## Galaxy Z Fold 8

- The inside screen is 4:3, the games' own shape, so all three fill it exactly: Warcraft II at its
  640×480, Warcraft I at 480×300 drawn 4:3 as the original was (War1gus's original pixel ratio),
  Warcraft III at 1080 lines (1440×1080) unless Graphics says otherwise.
- Folded (the outside screen) the launcher is one column; unfolded the picture is shorter, and held
  sideways the game's panel with its checklist sits beside the menu.
- Warcraft III's size is set when it starts, for the screen the launcher is on then: unfolding during
  a game keeps it running at that size, scaled to the new screen.
- Touch distances are measured in dp, so the gestures feel the same on both screens.

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
| `unit-tests/` | JVM tests of the gestures, finding a game in the files picked, the checklist, Blizzard's installer downloads, the hosts file, resolutions and update picking |

```
scripts/build-stratagus.sh
scripts/prepare-winlator-app.sh warcrafts
gradle -p warcrafts/unit-tests test
cd warcrafts/android && ./gradlew --init-script ../../scripts/maven-mirror.init.gradle :app:assembleRelease \
    -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

CI (`.github/workflows/build-warcrafts.yml`) runs the tests, builds the APK and publishes it.

## License

The app's code is GPL-2.0-or-later; it runs Stratagus, War1gus and Wargus (GPL-2.0), innoextract
(zlib license, with Boost and liblzma) and Winlator (LGPL-2.1), whose sources are at the commits in
`stratagus-version.env` and `winlator-version.env`.
No game files are in the repository, the APK or CI: you bring your own. This project isn't
affiliated with Blizzard Entertainment, GOG, the Stratagus team or Winlator's developers; Warcraft
is a trademark of Blizzard Entertainment.
