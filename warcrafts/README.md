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
| Warcraft III: Reign of Chaos and The Frozen Throne | Your two CD keys: the game is downloaded from Blizzard and installed in the app (below) | — |

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
the same size), drawing with OpenGL (`-opengl`, the way seen working on the phone). Graphics → Warcraft III draws with offers Direct3D
through Wine and Direct3D through DXVK too. The opening film is skipped (marked seen); a later film the game shows black
is skipped after 5 seconds (Escape). Wine's Light visual style is on for any Windows dialog. The game keeps the
pointer inside its window without Windows grabbing the pointer (Wine's `GrabPointer` off): while Wine held it, Winlator's
screen passed the game no presses, the likely reason taps moved the game's pointer but pressed nothing. The runtime has no network at all while
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
**Download and install** does everything, silently: no installer window or licence page. Then
you type your two CD keys once.
1. The app fetches the game packages of Blizzard's own classic installers (`Installer Tome.mpq`,
   what Blizzard's legacy downloaders at `us.battle.net/download/getLegacy?product=WAR3|W3XP`
   fetch: the address and access token inside them, piece by piece from Blizzard's CDN), about
   1.3 GB, each piece checked against Blizzard's checksum before it's kept. A stopped download goes
   on where it stopped.
2. It installs them itself, as Blizzard's installer would on Windows: the install list in each
   package says which files go where; the app's `wc3unpack` copies them out and puts the rest into
   the game's archives, Reign of Chaos first, then The Frozen Throne over it. Each package is
   deleted once installed.
3. **Your CD keys**: type the Reign of Chaos and The Frozen Throne keys (on the box or CD case, or
   in your Blizzard account; 26 characters, dashes are fine) and tap **Save keys**. The app writes
   them into the game's archives as Blizzard's installer does (`font\font.gid` in `war3.mpq`,
   `font\font.exp` in `War3x.mpq`, encoded the way the game reads them); the game won't start
   without them ("unable to find a CD Key"). They stay on the phone. **Change CD keys** writes
   others.
Game files shows the three steps and ticks the done ones; if the setup stopped, the same button goes
on from there. It needs about 2 GB free while it runs, 1.3 GB after.

**Reign of Chaos or The Frozen Throne**: the launcher's two looks start the same files, Reign of
Chaos with `Warcraft III.exe`, The Frozen Throne with `Frozen Throne.exe`.

**The films** (the openings, endings and tutorial, in the game's `Movies` folder): the app plays them
itself. When the game starts one, the game pauses and the film plays over it with the phone's own
video player, then the game goes on. Tap or Back skips a film. (Under Windows-on-Android the game
can only show them black: its 2001 DivX decoder and Wine's video parts don't fit together.)

**If something goes wrong in Warcraft III**: Games → Warcraft III → Game files → **Copy report of the last run**
copies what the last game did: the settings it ran with, Windows' messages (Wine's errors), what the app read from the game's screen (whether it saw a match, all black, and so on), your last
40 taps with what each was sent as, and the last 40 mouse button presses with where they went (the game's window,
Windows holding the pointer, or nowhere), all of them counted. If a run shows only a black screen, the launcher says so when you come back.
If a start never shows the game, the next start makes the games' Windows again from new (about a minute, once);
it holds none of the game's files, saves or keys, so nothing is lost.

## Classic+ graphics (Warcraft III)

[Classic+](https://kenshin.pl/classicplus/) by Kenshin (Piotr Bednarczuk) gives the units,
buildings, trees and effects more detailed models and textures in the game's own style. It was
made for the game's newer versions (1.32.6 and later); the app converts it for 1.27. Campaign-only
units and heroes keep their own look (the mod covers what melee games use).

Games → Warcraft III → Game files, once the game is installed:

1. **Download and install Classic+** (about 400 MB; the app gets it from the Google Drive link on the
   mod's page), or **Pick its archive** if you downloaded `kenshin_lab_v1.01.7z` yourself.
2. The app converts it: its newer-format models become 1.27's, its DDS textures become 1.27's BLP
   (each mipmap, as Blizzard's own are made), and the files go into the game's folder (about 750 MB).
   The mod's sounds are left out: they are the game's own sounds in a newer format. A few minutes.
3. **Use Classic+** switches it on and off (the game reads the files or doesn't; from the next
   start). **Remove Classic+** takes its files out again; the game's own and your saves stay.

If Google Drive refuses the download (it limits downloads of popular files), download the archive
in the browser from the mod's page and use **Pick its archive**.

## Controls

Nothing is drawn over the game. The gestures are the ones you chose, with what Warcraft III needs added.

| Gesture | In the game |
| --- | --- |
| Swipe one finger | Pans the map the way you swipe, for as long as the finger stays away from where it landed; lift to stop. Warcraft III: a swipe that starts on the minimap moves the camera along it instead |
| Tap | **Warcraft III, in a match:** on one of your own units or buildings it selects it; anywhere else on the battlefield your selected units act there: move to the ground, attack an enemy, gather from a mine or a tree, pick up an item. The app knows which by the game's own cursor, which the game tints green over your units, red over enemies and yellow over neutral ones (read from the game's picture when your finger lands, before the tap is sent). On the panel, the top bar, the hero buttons, the menus and dialogs a tap is the left click that presses what's there. **Warcraft I and II:** right click on the battlefield (move, attack, gather), the left click on menus and buttons and on the map when none of your units is selected |
| Tap after a command button (Warcraft III) | After you tap a spell, Attack, Patrol, an item or a building to build, the next tap on the battlefield is its target (the left click the game waits for). When the game isn't waiting for a target (after Stop or training a unit, say), that tap is a normal tap |
| Keep tapping (Warcraft III) | An order that waited for its target (Move, Attack, Patrol, a spell) is kept: every later tap on the battlefield gives it again there (the app taps its button, then your spot), until you tap another command button, select other units (a tap on your unit, a hold, a box, the portraits) or press Back. The app reads the command card from the game's picture: an order is kept only when the card showed the game waiting for a target, and given again only while the card shows the same buttons |
| Tap, then touch and drag (Warcraft III) | A selection box from where the second touch lands (within about a quarter of a second of the tap); the first tap does nothing. Taps on the battlefield wait that moment to see whether a box or a second tap follows |
| Double tap (Warcraft III) | On one of your units: every unit of that kind on the screen. On a hero button: the camera to the hero. On the ground or an enemy: attack-move (your units fight everything on the way). On an item in the inventory: picks it up; the next tap puts it in a slot, on the ground or on a unit (gives it) |
| Touch and hold | Left click when you lift (after a short buzz): selects anything, an enemy or a shop too (to buy from it), presses a button of the game's panel or menus. While you hold a Warcraft III button its tooltip shows |
| Hold, then drag (Warcraft III) | On the battlefield: a selection box from where you held. On a button: slide off before lifting and nothing is pressed |
| Hold twice on the same spot | Double click: every unit of that kind on the screen |
| Rest one finger, tap or hold with another | Shift-click: queue orders, add units to the selection |
| Two-finger tap | The game's own menu (F10: save, load, options, quit), in all three games |
| Two-finger tap twice quickly (Warcraft III) | Space: the camera to the last alert ("Our base is under attack") |
| Pinch | Zoom (the mouse wheel), where the game has it |
| Three-finger tap | The phone's keyboard, for chat and cheat codes (sent with Enter) |
| Four-finger tap | The app's menu: keyboard, chat, leave the game |
| Back | Escape: cancel what's being aimed, close a window |

There are no control groups (your choice). A mouse or keyboard connected to the phone works as on a
PC.

**If taps don't click in Warcraft III**: Graphics → **Winlator's touchpad (if taps don't click)** starts
it with Winlator's own touchpad in place of the gestures above: drag a finger to move the pointer (from
wherever it is, like a laptop's touchpad), tap to left click where it is, two-finger tap to right click,
rest one finger and move another to drag with the left button held (a selection box), two fingers close
together moved up or down to scroll, and four fingers for Winlator's menu (keyboard, exit). Back is still
Escape. It's off unless you turn it on, and it takes effect at the next start. If the touchpad clicks
and the gestures don't, the gestures are the problem; if neither clicks, it's further down (the report
says where each press went).

## The menus

- **Launcher**: Press start, **What's needed** (the checklist above), and tiles for **Game files**, **Games** (the four games, each by its
  logo, with whether its files are in), **Graphics**, **Controls** (the gestures) and **Updates**.
- **Game files**: Warcraft I and II: what to import, the import, converting again, deleting.
  Warcraft III: its setup (download, install each game), **Classic+** (install, on or off, remove),
  **Private** (on: the games' Windows has no network at all), deleting.
- **Graphics**: the games' resolution (the screen's own pixels, 1080 or 720 lines), how
  Warcraft III draws (Direct3D, or OpenGL with `-opengl` if the picture is wrong),
  the hold's vibration and, for Warcraft III, Winlator's touchpad in place of the gestures.
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
| `unit-tests/` | JVM tests of the gestures, finding a game in the files picked, the checklist, Blizzard's installer downloads and install lists, the hosts file, resolutions, update picking, Classic+'s conversion (models, textures, the archive's paths, the download) and the films' headers as the phone reads them |

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
(zlib license, with Boost and liblzma), Winlator (LGPL-2.1) and AndroidX Media3 (Apache-2.0, the films), whose sources are at the commits in
`stratagus-version.env` and `winlator-version.env`.
No game files are in the repository, the APK or CI: you bring your own. Classic+ is its author's
work, downloaded from where he publishes it; the app only converts it on the phone. Its model
conversion follows the 1100 format as war3-model (MIT) reads it. This project isn't
affiliated with Blizzard Entertainment, GOG, the Stratagus team or Winlator's developers; Warcraft
is a trademark of Blizzard Entertainment.
