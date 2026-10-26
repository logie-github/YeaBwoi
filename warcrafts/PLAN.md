# Warcraft — the brief

The user's requirements, what was decided, what's open, and where it stands. The manual is
`README.md`.

## What the user asked for

- "Look how we made World of Warcraft and all these other games. Can you do the same with this?"
  with Warcraft III 1.30 (a Google Drive zip, "Warcraft III 1.30 (16by9 ratio) (Multi-Language) by
  Mashiro", 1.8 GB, and its r/warcraft3 post: a pre-Reforged 1.30.4.11274 repack, RoC and TFT).
- "A private server not connecting to Blizzard at all", "play the campaign or whatever as long as I
  upload files", "tell me what I need to import", "a launcher like we did for Warcraft but its own
  separate app".
- Warcraft I Remastered and Warcraft II Remastered too; the user provides all the files.
- "Like the WoW launcher with themes based on the games"; the art came in `branding/`: each game's
  hero, splash and logo (Warcraft III: RoC and TFT logos and heroes, one splash) and the app icon
  (the orc portrait in its frame).
- Asked mid-build: "Are there any key binds that may need touch gesture reworks?" → the quick keys
  (build 1).
- After build 1: "Swiping in any direction pans the map in that direction. Don't just assume
  keybinds. Ask what I want to use." "Make it com.logie.warcrafts and the name simply Warcraft."
  "Have the launcher tell me exactly what is needed." "Optimize for Z Fold 8." Asked, the user chose:
  no selection box; tap = right click; touch and hold = left click; a resting finger = Shift; no
  control groups; pinch = zoom; three-finger tap = keyboard; two-finger tap = the game's menu;
  gestures only, no keys on screen.

- After build 2: "I don't like the current setup. Can you use the GOG versions of 1 and 2?", then,
  to a DOSBox start: "No. Stop. Don't use DOSBox, this must be Android native." Asked, the user
  chose Stratagus (War1gus, Wargus) for I and II, and kept Warcraft III (its 1.30 archive link).

- "The screen I'm playing on is a perfect 4:3 ratio." "Reinvent it a bit so make it play how I
  want if you have to."

- After build 3: "I have the install setup exe. The one downloaded from GOG."

- After build 4 (both GOG installers unpacked on the phone, both conversions stopped at "Cannot
  open … for writing"): "Warcraft 3 needs a CD key", pointing at a key generator site. "They need
  to be borderless, not windowed", then: "I mean fullscreen."

- After build 5: "1 and 2 crash on launch and 3 still needs keys. Auto generate them from the
  website so I don't have to."

## Decided

- **One app, four looks**: `com.logie.warcrafts`, "Warcraft" (build 1 was `com.logie.warcraftrts`,
  "Warcraft Collection": a different app on the phone, to uninstall). Warcraft I, Warcraft II,
  Reign of Chaos, The Frozen Throne; RoC and TFT share one install (1.30 is one program; the game's
  main menu switches, since 1.28.3).
- **Winlator**, as in the World of Warcraft app, at the same commit, compiled into the app
  (`scripts/prepare-winlator-app.sh`, a copy of `prepare-winlator.sh` that takes the app's folder,
  so the WoW app's build is untouched). The runtime goes to `/data/data/com.logie.warcrafts/winfs`:
  the package is 3 characters longer than `com.logie.warcraft`, so the folder is 3 shorter to keep
  the rewritten path as long as Winlator's.
- **No server**: the campaigns, custom games against the computer and LAN need none. "Private" is
  done in the runtime: its glibc reads `<rootfs>/etc/hosts` and `etc/nsswitch.conf` (compiled-in
  paths, checked in `libc.so.6`; neither file ships), so the app writes `hosts: files dns` and a
  hosts file sending Blizzard's names to 0.0.0.0 before each start (`library/Offline`).
  A PvPGN Battle.net server was considered: its version checks go up to Warcraft III 1.28.2, not
  1.30, so it isn't in.
- **Gestures** (the user's choices, `controls/RtsGestures`): one-finger swipe pans (arrow keys toward
  the swipe, held while the finger is away from where it landed), tap right click, hold left click
  on lift (a second hold on the same spot within 0.9 s: double click), a finger resting 0.2 s before
  another lands holds Shift for the other's taps and holds, two-finger tap F10, pinch wheel, three
  fingers the keyboard prompt, four the app's menu, Back Escape. The left click comes on lift so a
  resting finger never clicks.
- **What's needed** (`library/Needs`): per game, the program, `Data\{data,indices,config}`,
  `.build.info`, and for WC3 the optional `Maps` and CD keys, each checked case-insensitively in the
  game's folder and ticked on the launcher; Press start appears once the required ones are in.
- **Z Fold 8**: two-pane launcher at 700 dp and wider; games at 1080 lines by default, never narrower
  than 4:3 (letterboxed on the ~10:9 inside screen, not stretched).
- **Warcraft III setup**: registry `Video\reswidth/resheight/windowmode=2` (the phone's size, fullscreen) and the
  repack's `Classic Launcher\w3\Locale` (enUS unless set). The repack's first start asks for CD keys:
  the game menu lists keys found in a text file next to the program (`_SERIAL_KEYS.txt`) and types
  them.
- **Remastered games** (builds 1–2, replaced by the GOG versions in build 3): found by known names, started with `-launch`.

- **Warcraft I and II, native** (build 3): Stratagus master `3d87c93f7` (the engine has no Android
  build of its own; drodin/Stratagus's Android fork is 3.3.2 from 2022), with War1gus `8ec7576` and
  Wargus `cde1a07` (the pairing xr.wargus, a Quest port, uses), built with NDK 24 for arm64:
  - the engine as SDL's `libmain.so` with SDL 2.0.22 statically in it (whole archive, for its JNI);
    its bundled libraries (ExternalProject) configured with the NDK's ABI, platform and STL;
    libpng static only; `c++_static` (Winlator ships its own `libc++_shared.so`);
  - runtime: OpenGL ES 2 renderer, no desktop-GL shaders, text events on without the phone's
    keyboard (the app's prompt types), no edge scrolling (a lifted finger leaves the pointer at the
    edge); the user dir is the game dir (`ENABLE_USEGAMEDIR`);
  - `war1tool` and `wartool` (StormLib for the BNE MPQs) as PIE programs shipped as
    `lib*.so`, run by the app with the GOG folder and a staging folder; the games' scripts zipped in
    the APK, unpacked beside the data; the tools' `extracted` file marks it done. They call ffmpeg
    for videos and WAV music: not on a phone, so intro videos and Warcraft II's music are left out;
  - `StratagusActivity` (SDL's activity, process `:stratagus`, killed on leaving so each game starts
    clean) with `StratagusGestureView` over it: the same `RtsGestures`, sent through SDL's JNI
    (`SdlBridge`: mouse with Android's action/button codes, Android key codes, committed text).
- **Taps where a right click does nothing** (the user's "reinvent it a bit"): with tap = right
  click, menus and the panel's buttons couldn't be tapped. In Stratagus (patch 0001,
  `TouchButtonPress` in `sdl.cpp`) a right click becomes the left click unless the game is running
  with no menu open (`GameCallbacks`) and the cursor is on the map or minimap, and on the map also
  when none of the player's units is selected (the tap selects); the release follows the press.
  Warcraft III can't be changed inside: `GestureView` turns taps on its bottom panel (from 76% down)
  and top bar (top 5%) into left clicks; its full-screen menus still need a hold.
- **4:3** (the user's inside screen): Wargus 640x480 and War1gus 480x300 with its original pixel
  ratio (x1.2, 480x360) are 4:3 already; Warcraft III at 1440x1080 by default. The launcher's two
  columns from 760 dp (the inside screen sideways), its shorter picture from 600 dp.
- **GOG's offline installers** (build 4): the app takes the setup `.exe` and its `.bin` parts
  (picked together, or a folder holding them) and unpacks them on the phone with innoextract,
  built for arm64 like the tools (`libinnoextract.so`, with Boost 1.84 and liblzma from xz 5.6.3,
  static). crazy-max's fork at v1.13.0: upstream's master stops at Inno Setup 6.4.0, and GOG's newer
  installers are made with 6.6 (dscharrer/innoextract#214 names one, 6.6.1); the fork has #210
  (6.4.2 to 7.0). Tested here, on a PC build of the same source, with Inno Setup's own installers
  6.2.2 to 6.7.3: all languages listed, all files out into `app/`. Run with `--exclude-temp`
  and English as `--default-language` (from `--list-languages`); the game's folder in the output
  is found by its files as for a folder. The installer is copied into the app first (innoextract
  needs files beside each other, named as GOG named them), deleted once unpacked; then the usual
  conversion. Old GOG installers with RAR `.bin` parts (before 2015) would need unrar: not bundled.
- **The tools on Android** (build 5): build 4's first try on the phone showed both installers
  unpacked (652 MB, 737 MB) and both tools stopping on copying the game's archives into their output
  (`CAVE1.WAR`, `Support/TOMES/TOME.2`). Stratagus's `stratagus-gameutils.h` cuts a path to its
  folder with `dirname(x)` alone, counting on glibc's changing `x`; Android's (like macOS's) returns
  the folder and leaves `x` as it was, so the tools made a folder of the file's own name and couldn't
  open it. Patch `0002`: the macOS form, `strcpy(x, dirname(x))`, on Android too. It is the tools'
  only use of it; their other folders are made by `CheckPath`, which doesn't need it.
- **Warcraft III's CD keys** (build 5): Game files has a CD keys box; what's pasted (one key per
  line, a label before it optional) is kept in `files/cdkeys.txt`, apart from the game's folder, and
  the four-finger menu offers it with the keys of a key file that came with the game. The app takes
  the keys the user pastes; it doesn't fetch or carry any.
- **Fullscreen** (build 5, the user's "I mean fullscreen" after seeing Warcraft III windowed):
  Stratagus starts with `-F` (Wargus's own preference is windowed, which on Android leaves the
  phone's bars). Warcraft III starts with `-windowmode fullscreen -nativefullscr` (`library/
  Wc3Arguments`): patch 1.31's notes replaced 1.30's `-nativefullscr`, `-fullscreen` (windowed
  fullscreen) and `-window` with `-windowmode fullscreen|windowedfullscreen|windowed`, and the game
  passes over flags it doesn't know, so both are given; registry `windowmode` 2 (one guide's
  mapping: 0 windowed, 1 windowed fullscreen, 2 fullscreen; not confirmed for 1.30). A window
  mode typed in Game files takes its place. Wine's desktop is the screen's size (Winlator's
  `explorer /desktop`), as is the game's resolution, so fullscreen changes no display mode. The
  same notes replaced `-opengl` with `-graphicsapi OpenGL`: the OpenGL choice gives both (it gave
  `-graphicsapi OpenGL2`, which 1.30 may not know).
- **Stratagus's folders on Android** (build 6): built with `ENABLE_USEGAMEDIR`, the engine takes its
  user folder (preferences, saves, logs) from the data path when its settings are made, before
  `-d` is read: ".", the working directory, which on Android is "/". Both games' scripts call
  `SetGameName` at once, which creates `./war1gus` (`./wargus`): refused in "/", the likeliest cause
  of both games stopping at launch (not seen: no log came back). The app passes `-u <game folder>`;
  patch `0003` also makes the game folder the working directory on Android.
- **What a game says when it stops** (build 6): patch `0003` (Android only) sends the engine's
  output to `stdout.txt` and `stderr.txt` in the game's folder, writes a crash's signal, pc/lr and
  stack (dladdr names: the engine's functions are exported from `libmain.so`) and an uncaught
  exception's message there, and "Stratagus: exit N" on its own exits. `StratagusActivity` keeps a
  `running` file with its process id; `GameStops` in the launcher finds one whose process is gone
  without "exit 0", and shows the game's last output with Android's log for that process (`logcat
  --pid`, which an app may read for its own processes), with Copy. The app's own Java exceptions
  (launcher and game processes) are shown the same way.
- **Lua and FORTIFY** (build 7): build 6's stop report from the phone (Warcraft I): "FORTIFY: strchr:
  prevented read past end of buffer", signal 6, in Lua's start. The release's `libmain.so` at the
  reported address is Lua 5.1's `traversetable` (`lgc.c`): `strchr(svalue(mode), 'k')` on a weak
  table's mode, called as `__strchr_chk` with a size of 0. Lua keeps a string's characters after
  its header (`getstr(ts)` is `ts + 1`), and with the NDK's `_FORTIFY_SOURCE=2` the compiler sees
  no room past the header; the first weak table the collector meets ends the game. The bundled Lua
  is built with `-U_FORTIFY_SOURCE` on Android (deps patch). (The report's function names come
  from `dladdr`, the nearest exported symbol, so static functions show their neighbour's name; the
  addresses are exact.)
- **Warcraft III 1.27 on Wine** (build 9; after build 7 the user said "Wine may be the best ... you
  tell me", then "Use the 1.27", "assume I'm not tech savvy ... no preferences except 4:3"). The
  research (OpenRealm, OpenWar3, Warsmash, CASC conversion) found no open-source engine that plays
  the full campaigns today; the real game under Wine does. 1.27 from Blizzard's own classic
  installers (`us.battle.net/download/getLegacy?product=WAR3|W3XP`, small downloaders, run on a PC)
  is MPQ-based, has no start-up key screen (keys go in the installer), and is the version Lutris's
  maintained Wine setups use, with `-opengl`. The checklist wants `Warcraft III.exe`, `War3.mpq`,
  `War3x.mpq`, `War3xLocal.mpq`, `War3Patch.mpq`; The Frozen Throne starts `Frozen Throne.exe`;
  OpenGL is the default; Wine's Light visual style is on; windowed fullscreen at the screen's 4:3
  size; no name server while Private. Untested: whether `Frozen Throne.exe` in 1.27 starts the
  expansion directly under Wine, and how 1.27 runs on the Fold.
- **Warcraft III set up on the phone** (after build 13 the user: "This all needs to be done in the
  app. No pc. Pull the mpq from the installation"). Blizzard's legacy downloaders carry a torrent
  description ("disable p2p"; direct download `http://rogue.blizzard.com.edgesuite.net/applications/
  Warcraft3/1.27a2/{ROC,TFT}/enUS`; files `Installer.exe`, `Installer Tome.mpq` 752 MB / 581 MB,
  DirectX; SHA-1 per 256 KB piece). `library/BlizzardDownload` reads it (tested on the real
  downloaders); `Wc3Setup` downloads with resume and checks every piece, then Blizzard's
  `Installer.exe` runs in the games' Windows (drive G:, taps as plain clicks) where the player types
  their key; the installed folder (`C:\Program Files (x86)\Warcraft III`) is moved into the app's
  Warcraft III folder. Build 15's file-by-file download got 403 on the phone too. Each downloader
  carries, after its torrent description, an Akamai token made for that download
  (`bcac=expires=…~access=/applications/Warcraft3/1.27a2/<ROC|TFT>/enUS/*~md5=…`); sent as a
  cookie, the CDN serves the installer piece by piece at `<direct download>/<piece number>`
  (checked from this build machine: pieces 0, 1 and the last of Reign of Chaos match their SHA-1s).
  Build 16 downloads that way, each piece checked before it's written, resumable. Unverified: the
  installer under Wine/Box64.
- **Warcraft III, clean rebuild** (after build 16 the user: "Completely scrap what you have for
  warcraft 3 except for gesture controls and start over clean", then agreed to: Blizzard's real 1.27
  on Wine, set up by the app). Removed: the 1.30 handling, CD key box/menu/files (`CdKeys`),
  start-argument logic (`Wc3Arguments`), Warcraft III's folder/zip import, program picker and file
  checklist, the Classic Launcher locale. Kept: the gesture layer, the Windows-layer start, the
  offline lock, the verified Blizzard download (`BlizzardDownload`, `Wc3Setup`). Warcraft III's
  Game files is its own dialog with three steps (download, install Reign of Chaos, install The
  Frozen Throne), only the next one's button offered; the checklist is Downloaded, Installed;
  Reign of Chaos starts `Warcraft III.exe`, The Frozen Throne `Frozen Throne.exe`, with `-opengl`
  unless Direct3D is chosen; the registry gets the 4:3 resolution and Wine's Light style.
- **Silent install** (after build 21 the user sent Blizzard's installer showing a blank licence
  page with Agree greyed out under Wine, and: "Needs to install silently"). `Installer.exe` has no
  silent switch. Each package (`Installer Tome.mpq`) carries the install script
  (`InstallCD\InstallerFileList\InstallerFileList.xml`): on Win32, stage 1 copies `Common\*` and
  `PC\*` into the game's folder (`War3.mpq`, `War3Patch.mpq`, `War3x.mpq`, `War3xLocal.mpq`, the
  programs, `Maps`, `Movies`, Miles' files), stage 2 adds `PC-100\*` (`PC-100x\*`) into `war3.mpq`
  (`War3x.mpq`) uncompressed, then the CD key is written into the archive (`font\font.gid`,
  `font\font.exp`), and the registry gets `InstallPath`, `GamePath`/`Program`. Build 22 does it in
  the app: `library/Wc3Script` reads the script (Win32, English; case-insensitive paths keep the
  first spelling), `wc3unpack` (StormLib, `stratagus/tools/wc3unpack.cpp`, shipped as
  libwc3unpack.so) runs the plan, `Wc3Setup` downloads only the packages (the installer program,
  DirectX and the Mac's files are skipped) and installs, Reign of Chaos then The Frozen Throne, into
  `files/wc3-setup/game`, which becomes the Warcraft III folder; the registry values go into the
  games' Windows at each start (`F:\`). Left out: the CD key step, the uninstaller, DirectX's
  setup, shortcuts. Tested here on a PC build of the tool with both real packages: every file
  copied and every archive addition byte-identical to the package's. Not known yet: whether 1.27
  starts offline without the key data in its archives (single player needs no key in 1.27 as far
  as known; the installer only writes it for Battle.net), and the arm64 build on the phone.
- **Build 22 on the phone**: "Installing stopped: can't put PC-100\(Mac)BNetMenu.html into War3.mpq
  (9)", the first file small enough to be written in one piece. Reproduced here with the arm64 build
  under qemu: Wargus's StormLib bundles LibTomCrypt, whose `ulong32` is `unsigned long` (8 bytes)
  on every 64-bit CPU but x86-64 and SPARC, so on arm64 its MD5 state is 112 bytes in StormLib's
  96-byte `hctx`, overwriting the file handle's CRC, error code and write flag (error 9: not a write
  handle). `patches/wargus/0001` makes `ulong32` 4 bytes on arm64 too (also used by wartool's
  StormLib). After it, the arm64 build under qemu installs both games and every archive addition
  is byte-identical to the package's (build 23).
- **CD keys after the silent install** (build 24 on the phone: installed, then the game said
  "Warcraft III was unable to find a CD Key. Please reinstall the game from the Warcraft III CD").
  1.27's Game.dll (1.27.0.52240) reads `font\font.gid` (the RoC key, required), `font\font.exp`
  (TFT's), `font\font.clh` (owner, required) and `font\font.ccd` (compared with "sgubon") from its
  archives; no public description of their format was found, so it was read from Game.dll
  (0x6f313340 and below): each file is one 64-byte block and an 8-byte trailer; the block is the
  text plus a keystream (the state of Blizzard's SHA-1, the Battle.net variant: little-endian words,
  no rotation in the expansion, arithmetic shifts, after hashing bytes from MSVC `rand()` seeded
  0x4fa7 mixed with the SHA-1 of a key from `rand()` seeded 0x150b), then IDEA (little-endian words,
  key from the same bytes); the trailer is the first SHA-1 word after hashing the block, 0, the
  length. `wc3unpack key` writes them; checked by running Game.dll's own decoder in an x86
  emulator (unicorn) on the encoder's output (key, owner and mark decode exactly), and the arm64
  build's output is byte-identical. The key text is 26 characters from Game.dll's table
  `246789BCDEFGHJKMNPRTVWXYZ`. Build 25 asks for the two keys in Game files (a third step,
  `Wc3Keys`, need `keys.done`). The keys are the user's own; nothing is generated. Not known: whether
  the user's keys pass the game's own check (it validates them itself at start).
- **Warcraft III's menus didn't answer taps** (build 25 on the phone: the main menu up, the user's
  recording showing nothing reacting). A tap was a right click everywhere but the in-match panel
  and top bar, and the game's menus ignore right clicks. Build 26 reads the game's picture
  (`game/Wc3Watcher`: four times a second the game's window is drawn at half size into a texture of
  the app's own and read back) and recognises the in-match console (`library/Wc3Screen`,
  `Wc3ScreenData` generated from `UI\FrameDef\UI\ConsoleUI.fdf` and each race's console tiles,
  RoC and TFT: 11x11-pixel blocks of the stone frame, light and dark, compared with tolerance 40;
  a match needs 80% of each). No console: every tap is a left click. In a match: the console (where
  its panel starts in each 80th of the width, and the top bar) is a left click, the battlefield a
  right click, and a battlefield that doesn't change for 0.4 s (single player pauses under the F10
  menu and the quest log) or the 1.5 s after the game's menu was asked for are left clicks.
  Tested here on the console renders at 1024x768 to 2448x1836, blurred, 10% darker and brighter, at
  half texture quality (all found, with their race) and on the menu frames of the user's recording,
  greys and noise (none found; best score 0.22 of the 0.8 needed). Not known: the picture as read on
  the phone (orientation is found from the first console seen), dialogs in a game that doesn't pause
  (LAN, maybe the victory dialog): touch and hold there.
- **Build 26 on the phone, and the controls Warcraft III needs** (the user: "the main screen will
  not let me do any touching. It will move the mouse but won't click", "look at the flag", "movies
  don't show it's just black screen", "add what touch controls you think are needed"). Build 27:
  - Clicks hold the button 110 ms (was 40): Warcraft III reads the mouse once a frame, and at the
    phone's frame rate a 40 ms click can fall between two readings. No console seen: left clicks
    (the picture not read yet counts as the menus).
  - The banner in the main menu (recording) had black holes flickering through it: OpenGL's
    drawing on the phone. Direct3D is the default now (DXVK 2.4.1's d3d8 on Vulkan), OpenGL a choice.
  - Films: Warcraft III plays them through DirectShow (`Game.dll`: FilterGraph, `RenderFile`,
    `IVideoWindow.put_Owner`; `War3.exe` registers `blizzard.ax`); the container now has Winlator's
    own DirectShow (`wincomponents` directshow=1, as Winlator sets for its film-playing games).
  - Controls: aim (after a tap on the command grid, x 0.765-0.99, y from 0.74, or the inventory,
    x 0.64-0.74, y from 0.80, measured from the console's art, the next battlefield tap within 8 s
    is a left click, unless the game's pointer shape is the one it has at plain right clicks, once
    the game has shown two shapes); hero buttons and idle worker (x below 0.055, y 0.053-0.8) left
    clicks; hold then drag: a selection box on the battlefield, cancel on a button; a swipe from the
    minimap (x 0.013-0.185, y 0.803-0.986) drags the camera; two two-finger taps within 0.32 s:
    Space. `RtsGestures` gets `HeldDrag` and `dragZone` (Warcraft I and II keep their gestures).
  Not known until the phone: whether 110 ms is the cause of the missed clicks, whether Direct3D
  draws the banner right and the films show, whether the game's pointer changes shape when aiming.
- **Build 27 on the phone: "nothing works and it's a black screen entirely"**, and "start anticipating
  things like 10 at a time rather than being reactive". Build 27 changed two untested things at once
  (Direct3D through DXVK as the default, Windows' DirectShow), after build 25/26 had shown the menu
  with OpenGL. Build 28, all at once:
  1. OpenGL (`-opengl`) is the default again; DirectShow is Wine's again (Winlator restores it).
  2. Three ways to draw, each applied to the container at start: OpenGL, Direct3D through Wine
     (wined3d), Direct3D through DXVK.
  3. The opening film is marked seen (`Misc\seenintromovie`): it can't hold the first start black.
  4. Clicks: the pointer goes there, 70 ms pass, a one-pixel nudge and back, then the press (110 ms):
     the game may read a press before it reads the pointer's new place. Drags hold their moves until
     their press is down.
  5. A picture all black for 5 s after the game had shown something: Escape once (a film shown black).
  6. `Wc3Report`: each run's report (`files/wc3-last-run.txt`): settings, Wine's output (the
     container's `WINEDEBUG=+loaddll` with Wine's default errors and fixmes, through Winlator's
     `ProcessHelper` callbacks), what `Wc3Watcher` read (readings, races, black, still, upside down)
     and the last 40 taps with their reason. Game files → Copy report.
  7. A run whose picture was black every time: the launcher says so on return, with the report.
  8. The picture is read every 350 ms (was 250).
  9. No console seen (or nothing read): taps are left clicks (the menus).
  10. README and this plan.
  Not known until the phone: whether OpenGL shows the menu again (as in 25), whether clicks now land,
  what the report says.
- **Build 28 on the phone: "Now it doesn't start at all"**, with its report: settings only, no
  output at all, no "game's window is up". Two findings: Winlator's game screen takes every output
  listener off as it opens (`ProcessHelper.removeAllDebugCallbacks()`, `XServerDisplayActivity`
  line 163), so the report's listener, added before, heard nothing; and no game window ever showed.
  The likeliest hold-up: Wine's and Box64's processes left running from build 27's black-screen run
  (an app swiped away doesn't take them with it), holding Wine's server for the prefix, so the new
  start waits on them. Build 29: before each start, processes of the app's user running the runtime
  (`winfs`), wineserver, Box64 or a `.exe` are stopped and listed in the report; the game screen
  listens to Wine's output again once it's up (before Wine starts); every window shown is noted
  (class, title, size, drawn), and "no game window yet" at 20, 45, 90 and 180 s; the screen closing.
- **Build 30** (a new session, after the user: "the game won't even start"; build 29 not yet run on
  the phone). Build 28's report showed nothing after "---- output ----": the listener was taken off
  (fixed in 29), so it can't say why no window came. What builds 27 and 28 changed that stays on the
  phone is the games' Windows itself (its DirectShow switched to Windows' and back, settings, and a
  game possibly killed mid-run with the registry being written), and build 26's fresh one showed the
  menu. Build 30:
  1. The games' Windows is made again once (`rtsGeneration` 30 in the container), and again after any
     start that never showed the game's window (`files/wc3-starting`, written as Warcraft III starts,
     deleted when its window shows), but not twice in a row. It holds no game files, saves or keys
     (all in the game's folder on F:, never followed: Winlator's delete doesn't follow links); the
     registry values are written at each start. The report says whether it was made fresh and why.
  2. Wine's output is its errors only (`-all,err+all`), not build 28's `+loaddll` with fixmes.
  3. The report's lock: Winlator's output threads call it holding their listener list, and the
     report took that list holding its own lock (start, finish): a deadlock on leaving the game. The
     listener is now added and removed outside it.
  4. A start while the game's screen is still open (Winlator's screen is single-instance, so a new
     start only brought it back with nothing started, and reset the report) returns to it.
  Tested here: the app compiles, 70 unit tests pass. Not on the phone.
- **Build 31: clicks** (build 30 on the phone: the game starts and draws with Direct3D through Wine or
  DXVK; on the main menu "It moves the cursor but doesn't select", as in builds 25/26 with OpenGL).
  Found in the sources (Winlator at the app's commit, Wine 10.10, the runtime's Wine):
  - Wine keeps the cursor in a window by grabbing the X pointer on a clipping window of its own
    (`winex11.drv/mouse.c:366-438`, `XGrabPointer(clip_window, False, PointerMotion|ButtonPress|ButtonRelease)`):
    for a game's `ClipCursor` smaller than the screen, and for DirectInput's exclusive mouse, which
    clips one pixel inside the screen "even if the window covers the whole screen"
    (`dinput/mouse.c:316-324`). Warcraft III confines the cursor when fullscreen (Wine bug 27616:
    its window "simply grabs the mouse").
  - Winlator's X server sent a press during such a grab only to listeners chosen on the grabbed
    window itself (`InputDeviceManager.onPointerButtonPress`), not to the program through its grab.
    Wine's clipping window chooses only structure and focus events (`window.c:2690`, `1188`), so no
    press arrived. Worse, the clipping window is put in Wine's desktop window before that maps
    (`window.c:2591`, `explorer/desktop.c:1283`), and Winlator disables the desktop window and
    everything in it as it maps (`XServerDisplayActivity.onMapWindow`, `disableAllDescendants`).
    So during the grab no motion, press or release reached Wine. The game's pointer still followed
    the finger: Wine asks the X server where the pointer is when it has heard nothing for 100 ms
    (`win32u/input.c:755`, `XQueryPointer`), and Winlator answers that whatever the grab.
  - Checked by running Winlator's own routing classes (`InputDeviceManager`, `GrabManager`, `Window`)
    on the JVM with those windows as Wine makes them: before, a click during the grab gave Wine
    nothing (with the clipping window not disabled, motion and release but no press); after, the
    motion, press and release, at the clipping window's coordinates, which Wine maps back. Clicks
    with no grab and drags are the same before and after.
  - Ruled out: Wine 10.10 doesn't ignore the ordinary motion events while it reads XInput2's raw
    motion (`mouse.c:1592-1615`; older Wines did), so the app's absolute pointer moves are fine. And
    Wine 10.10 doesn't read XInput2's raw button events at all: presses only ever come from the
    ordinary ButtonPress.
  Build 31, both fixes at once:
  1. Wine's `HKCU\Software\Wine\X11 Driver\GrabPointer` = `N`, written at each start
     (`win32u/sysparams.c:5566`): Wine keeps the clip (its server still holds the cursor inside, and
     `ClipCursor` still succeeds, `input.c:2760-2769`, so DirectInput doesn't warp the cursor) but
     never grabs the X pointer for it (`input.c:2716`). Presses go to the game's window like any click.
  2. `patches/winlator/0005`: while a program holds the pointer, its presses, releases and motion go
     to it as an X server sends them, whatever window it grabbed and whether Winlator disabled that
     window (other grabs remain: Wine's `SetCursorPos` grabs the pointer for a moment).
  3. The report: every mouse button press (gestures, Winlator's touchpad, a mouse) and where the X
     server sent it (`controls/ClickRoute`): the game's window, a program holding the pointer, or
     dropped. The last 40 and the counts at the end, the first three and each change of where they
     go in the report as it's written.
  4. Graphics → **Winlator's touchpad (if taps don't click)**, off unless chosen: Warcraft III with
     Winlator's own touchpad (drag to move the pointer, tap to click, two-finger tap to right click,
     four fingers for Winlator's menu with Exit). The app's view stays on the game screen with every
     touch going on to the touchpad. Without the view (no `gestures` extra), the start's bookkeeping
     would be lost: the `wc3-starting` mark would never be cleared, so every other start would make
     the games' Windows again, and a start while the game ran would stop it. Back stays Escape.
  Not changed: the graphics, the tap logic in matches (`Wc3Watcher`, `Wc3Screen`), the click timing.
  Tested here: the app compiles with the five Winlator patches, 76 unit tests pass (6 new), the
  routing check above. Not on the phone: whether Warcraft III's clicks land now, and whether it does
  clip the cursor (with the grab off the report can't show it; it shows where each press went).
- **Build 32: the controls the user asked for after build 31** (playing a match: "Tapping once then
  moving once is too much. Make it keep the last selected move until I click another one. Also
  selecting multiple units needs to be easier. Like tap left then tap and drag for select box";
  "Double tap on items should let me pick them up"):
  1. **The kept order**: the command card is read with the console (`Wc3Screen.ORDER_SLOTS`, 4x3
     from `onCommandCard`'s grid, three points per button, `ORDER_POINTS`). A command button tapped
     is remembered with the card as it was; at its target tap, a card read since then that is empty
     but for its bottom-right button (Cancel: `targeting`) keeps it (and makes that tap the left
     click; a card that isn't waiting makes it the right click, as after Stop). Each later
     battlefield tap clicks the kept button, waits 90 ms, then left-clicks the tap's spot, while
     the card shows the same buttons (`sameCard`: 10 of 12 within 60); another card (another unit,
     the Build submenu after placing a building) lets it go, and so do another command button, a
     hold, a box, a double hold, a tap on the portraits or hero buttons, Escape. Taps during an
     order given again wait for it. Thresholds (a button's point brighter than 120, summed) are from
     the game's art as known, not from the phone's picture: if the card is misread nothing is kept
     and taps stay as before. The report notes each order kept and let go.
  2. **Tap, then touch and drag**: `RtsGestures` holds a tap where `tapWaits` (Warcraft III: the
     battlefield and the inventory in a match) for `TAP_WAIT_MS` 250 ms; a touch in that time that
     drags where `heldDrag` says SELECT draws the box from its own start, the tap's click left out.
     Hold, then drag still draws one too.
  3. **Double tap**: a second tap within 250 ms on the same spot (40 dp): `doubleTap`, a right
     click there. On the inventory it picks the item up, and the next battlefield tap within 8 s
     is the left click that puts it down; on the battlefield it's the plain right click (pick up an
     item on the ground), a kept order left as it is.
  Warcraft I and II keep their gestures (`tapWaits` false). Tested here: compiles, 87 unit tests
  (the gestures and the card logic). Not on the phone.
- **Build 33: Classic+** (the user: "https://kenshin.pl/classicplus/ implement this"). Classic+ v1.01
  (`kenshin_lab_v1.01.7z`, 394 MB, Google Drive from the page; for 1.32.6+, loose files read with
  `Allow Local Files`) holds 609 MDX (487 at 800, 122 at 1100), 936 DDS (DXT5 888, DXT1 48, up to
  1024), 297 BLP1, 166 FLAC (Reforged's copies of 1.27's own WAVs: same length and rate, left out)
  and 10 PNG. Checked first on this machine with the real 1.27 under Wine 10.10 (the stopped test
  agent's install, Xvfb): `Allow Local Files`=1 makes 1.27 read loose files in its folder; converted
  with Python (Pillow, war3-model), a skirmish showed the mod's art. Two findings:
  - **BLP1 JPEG** as Blizzard makes it (read from War3.mpq's `Textures\Footman.blp`): 4 components
    1-4, 1x1 sampling, no Adobe marker, holding blue, green, red and alpha as they are (javax.imageio's
    raw raster: Footman's alpha averages 239). Pillow reads and writes every 4-component JPEG as
    Adobe's inverted CMYK, which hid this: written through Pillow without inverting, every
    converted texture drew white or team-coloured in the game.
  - **MDX 1100** adds, per layer, a shader type and a texture list (the first the layer's own),
    as war3-model 4.0.1 (MIT) reads it; mdx-m3-viewer 5.12 doesn't read 1100. Classic+'s 1100
    models have no skin weights, tangents or HD layers, so 800 draws them.
  In the app: `library/Mdx` (byte-level 900-1100 → 800: VERS, MTLS, GEOS' LOD fields, CORN/FAFX/BPOS
  dropped; its 121 outputs read back by war3-model equal war3-model's own conversion, and
  mdx-m3-viewer reads them as 800), `library/Blp` (DDS DXT1/3/5 → BLP1 with its own baseline JPEG
  encoder; all 936 of the mod's within 3 on average of the DDS, 18 s here), `library/ClassicPlus` (the archive with commons-compress, the `Classic+` folder's files
  into the game's folder, folders matched without case, the list written, on/off, removal; the
  Drive link from the page and Drive's confirm form), `ClassicPlusSetup` (download or picked archive,
  in `ImportService`), Game files' Classic+ section, `Game` writing `Allow Local Files` 1 when on.
  The app's Kotlin installer run here on the archive: 609 models (122 converted), 1233 textures
  (936 converted), nothing failed, 65 s on one core of this machine; installed into 1.27 under Wine
  here, a skirmish showed the mod's art as the Python version had. Not run on the phone: the time
  there, and how 1.27 runs with the bigger textures (up to 1024, a 32-bit game).
- **Build 34: the films** (the user: "Movies still don't show up"). Worked out here with the real
  1.27 under Wine 10.10 (the same black screen, with sound):
  - The films (`Movies\*.mpq`) are AVI: 800x368 MPEG-4 under Blizzard's tag `BLZ0` (only I and P
    frames: Simple Profile), constant-rate MP3 (834 or 1044 bytes a chunk). The game plays them with
    DirectShow and its `blizzard.ax`, DivX 4 ("DivX for Blizzard Decoder Filter", for `BLZ0`/`Blz0`),
    which the install doesn't register.
  - Wine's AVI splitter (winegstreamer) has no compressed type for MPEG-4 part 2 (`unixlib.h`,
    `caps_is_compressed`), so it decodes with GStreamer and offers only I420
    (`avi_splitter_source_get_media_type`: the current format alone); wined3d has no I420 surface, so
    the renderer fails (0x80070057) and only the sound plays.
  - Windows' DirectShow (Winlator's `directshow` part, as build 27 had) with `blizzard.ax`
    registered: the chain builds, nothing is drawn. Windows' AVI splitter and file reader in Wine's
    DirectShow (as `nquartz.dll`): the chain builds (splitter, DivX for Blizzard, Wine's renderer,
    YUY2) and the game crashes in the decoder's copy (an access violation in msvcrt's memcpy under
    `blizzard.ax`), with Windows' renderer too.
  So the app plays them (`game/Wc3MoviePlayer`): every 400 ms it reads which files the game's
  processes (`.exe` or `wine` in their command line, as Winlator tells them) have open (`/proc/<pid>/fd`); a film in the game's
  `Movies` folder pauses the game (its guest processes stopped, as Winlator does in the background)
  and plays over it with Media3 (ExoPlayer, Android's AVI reader and MPEG-4 decoder), through a file
  source that reads it with `library/Wc3Movie`'s changes: `BLZ0` as `XVID` (which Media3's AVI
  reader takes for MPEG-4), and the sound's header in chunks (Media3 times chunks as duration × i /
  length, and these count bytes: all the sound fell in the first 0.3 s). At its end, a tap or Back,
  the game resumes and gets Escape, which skips its own black playing. Checked here: Media3's AVI
  reader on the JVM over all nine films read the app's way finds the 800x368 MPEG-4 and the MP3, every
  video frame (keyframes and frames as ffprobe counts them), the sound ending with the picture. The
  opening film is no longer marked seen. Not known until the phone: the phone's decoder on these
  files, the pause and resume around a film, the game after Escape.
- **Build 35: the touch controls as a mobile RTS** (the user: "Doesn't work to pick up items by double
  tap. How about you look at the controls and decide touch controls. Do some really good research.
  Convert this into a good feeling touch game as though it were a mobile RTS.")
  - **Why double tap and the kept order did nothing on the phone**: everything in a match hung on
    recognising the console from its art (`Wc3ScreenData`, Blizzard's tiles), and Classic+ replaces
    those tiles (`ui/console/<race>/<race>uitile01-06`); not recognised, every tap was a menu click.
  - **Research** (mobile RTS and PC ports to touch): tap selects, tap the destination orders, the order
    fitting what's there (Feral's EMPIRE: Total War, Construct's RTS devlog, Iron Marines); a box from
    a drag that panning doesn't claim (a mode, two fingers, or hold first: C&C Red Alert iPhone,
    Rusted Warfare, Construct); double tap for every unit of a kind (Iron Marines, CoH); hold for
    the rest (CoH's command wheel, Rome's positioning); right click is what touch does worst (a
    two-finger tap, layers: Steam Remote Play). Warcraft III gives items by right-clicking them in
    the inventory, then left-clicking the unit.
  - **Found in the game** (1.27 under Wine here): the game draws its own cursor, tinted **green**
    over the player's units and buildings, **red** over enemies, **yellow** over neutral ones (a gold
    mine), silver over ground and trees; Classic+ doesn't change cursors (`UI\Cursor\*Cursor.blp`).
    And while a match is on the game keeps `TempReplay.w3g` open (with the map), in the menus not.
  - **Built**: `game/Wc3Files` (the game's open files every 400 ms: a match while the replay is open;
    the films for `Wc3MoviePlayer`); `library/Wc3Cursor` (the picture around the pointer before the
    cursor comes and after: changed, strongly coloured pixels by hue; on ten real captures, day and
    night, dirt, grass, trees, own/enemy/neutral, all right); `Wc3Watcher.probe` (a full-size square
    around a point, on Winlator's drawing thread); `RtsGestures`' `fingerDown`. In a match a finger
    landing on the battlefield is read (the pointer moved away first if it's close by, so "before"
    doesn't hold the cursor; read again once if the cursor isn't drawn there yet), and the tap waits
    for it (at most 380 ms; its 280 ms double-tap wait mostly covers it): **own → left click
    (select)**, anything else → the right click (or the kept order, the aimed target). Double tap: own
    unit → double click (all of its kind), hero button → double click (camera), inventory → right
    click (pick up), elsewhere → attack-move (A, left click). Hold: left click (select anything,
    shops). The console without its race: the highest panel edge of the four races. Not known until
    the phone: the cursor's timing there (Winlator's frames), the probe's orientation when the console
    isn't recognised (taken as found by the watcher, else the picture's bottom first).
- **Build 36: heroes pick up items by walking over them** (the user, after build 35: "It still won't
  let me pick up items. Can we make it just so walking over them with a hero does it")
  - **Why a tap on an item may not have picked it up**: over an item the game's cursor is yellow
    (measured: neutral 1283 changed pixels, own 11), so the tap is the right click, the game's pick-up
    order. But with a kept order (build 32) a tap on an item is that order again: Move walks onto the
    item without taking it. Not seen on the phone, so not certain.
  - **How**: 1.27 has no such setting, and every map's script calls `InitBlizzard` from the game's
    `Scripts\Blizzard.j`. With `Allow Local Files` on, 1.27 reads a `Blizzard.j` at the top of the
    game's folder (Wine's file trace: it asks for `F:\blizzard.j` and `F:\common.j`, not under
    `Scripts\`; one at `F:\Scripts\Blizzard.j` was ignored; at the top, a changed starting gold showed
    in a skirmish). `library/Wc3Pickup` adds globals, functions and a call at the end of
    `InitBlizzard`; `Wc3PickupSetup` takes the player's own from `War3Patch.mpq` with `wc3unpack
    extract` at each start (again only when the archive or the addition changes), writes it, and
    `Game` turns `Allow Local Files` on for it. Graphics → Controls turns it off (the file removed).
  - **The addition**: ten times a second, each hero of each player at the controls (alive, shown, not
    an illusion, not paused, with an inventory; no cinematic) is given (`UnitAddItem`) an item on the
    ground within 50 of its edge, when there's room or it's a powerup. A used powerup lies there
    dead a while (life 0, still visible): left alone, or a tome would count twice (found by the test).
    An item a hero drops (the game's drop event) stays until that hero is 150 from it.
  - **Tested in 1.27 under Wine** with a test-only harness in the same file (a Paladin, items, orders,
    results written with `PreloadGenEnd` to `Logs\`), on Frozen Throne (Booty Bay) and Reign of Chaos
    (Duskwood): 300 away not taken; stood on, taken; a tome used once (Strength 22 → 23, and not again);
    dropped beside the hero, left; walked away and back by a Move order, taken; full inventory, left;
    in a cinematic, left, then taken after it; the game's pick-up event fired each time. The app's
    Kotlin makes the same file byte for byte as the tested one; with it the first Human campaign
    mission plays its opening. Not tried: on the phone, saved games and replays from before, other
    patch versions' `War3Patch.mpq`.
- **Build 37: a hold is the right click** (the user: "Do we have a simple hold tap? If not can I make
  that right click?", then chose "right click everywhere" of three ways put to them, and "Single tap
  should work as it does now"). Warcraft III in a match: a hold is the right click wherever it is
  (`GestureView.holdRightClicks`): what taps can't right-click (the player's own units: follow, load,
  repair; ability buttons: autocast; inventory items: picked up, the next tap putting it down; the
  minimap), and on the battlefield the same order as a tap. It ends the game's waiting for a target
  (aim forgotten); a kept order stays. A second hold there: another right click. In the menus and
  the game's dialogs (a still picture, or just after the top bar's menu buttons) a hold is still the
  left click; Warcraft I and II unchanged. What the hold did (selecting an enemy, a shop, a neutral
  one) is now a double tap on it (by the cursor's tint: red or yellow → the left click); a double tap
  on the ground stays attack-move. Taps unchanged. Not tried on the phone.
- **CD key generation** (asked after build 5): not built in. The app is published as a release, and
  generating or fetching keys from a key generator site for a paid game isn't something it will
  carry. Since build 25 the app asks for the user's own two keys and writes them as the installer does.
- The GOG contents come from GOG Database's manifests: Warcraft I (Windows build, DOSBox
  dependency) has `WAR.EXE`, `DATA\*.WAR`, `WAR1.CUE/BIN`; Warcraft II BNE has `Warcraft II BNE.exe`,
  `Install.mpq`, `War2Dat.mpq`, `Support\TOMES\TOME.1-4`. wartool detects BNE by `Support/TOMES/TOME.1`.

## Open (not knowable without the phone and the files)

- Whether 1.30's CEF-based first-run screen (the classic installer choice) works under Wine/Box64.
- Whether Warcraft III's Direct3D 9 runs well on DXVK + the Fold's Adreno (OpenGL is the fallback
  in Graphics), and its sound.
- Whether the converted GOG data plays fully: Wargus has open issues on BNE extraction (videos
  #522, sound effects #348). Whether Stratagus's GLES renderer and SDL's audio behave on the Fold.
- Warcraft I's music: war1tool makes MIDI from the game's XMI; SDL_mixer on Android has no MIDI
  instruments without Timidity patches (not bundled yet), so it may be silent.
- Whether the registry values above are the ones 1.30 reads (the game also has `preferences.json`),
  and whether fullscreen (`-nativefullscr`) behaves under Wine and DXVK on the phone (windowed
  fullscreen is the fallback, typed in Game files).
- Which Inno Setup version the user's GOG installers are, and whether innoextract unpacks them on
  the phone (not run on a GOG installer, nor on arm64, here).

## Stages

1. **Launcher, imports, the games on Winlator, gestures, quick keys, offline** — build 1
   (`com.logie.warcraftrts`).
2. **The user's gestures, `com.logie.warcrafts` "Warcraft", the What's needed checklist, Fold 8
   layout and resolutions** — built.
3. **Warcraft I and II native on Stratagus from the GOG files** — built (build 3).
4. **GOG's offline installers taken as they are** — built (build 4); unpacking worked on the phone.
5. **The tools' fix for Android, Warcraft III's CD keys box, fullscreen** — built (build 5); the
   conversions ran on the phone, both games stopped at launch.
6. **Stratagus's folders on Android, what a game says when it stops** — built (build 6); the stop
   report came back from the phone.
7. **Lua without FORTIFY** — built (build 7).
9. **Warcraft III installed silently** (download and install in one step, no installer window or
   key) — built (build 22); on the phone it stopped on an arm64 StormLib bug, fixed in build 24,
   which installed; the game then asked for the CD key data: build 25 writes the user's keys.
8. After the user's next try: whatever the phone shows (the stop report, if a game stops again).

## Where it stands

Stage 7: built locally and in CI; unit tests pass. On the user's phone: both GOG installers
unpacked (build 4), both conversions ran (build 5), the games stopped at launch (build 5); build 6
got past the folders and stopped in Lua's start on FORTIFY (its report); build 7 builds Lua
without it, not yet run. Warcraft III: build 21's download worked on the phone, Blizzard's installer
showed a blank licence page; build 22 installs without it but stopped on the phone (StormLib on
arm64); build 24 installed on the phone and the game started, asking for its CD key; build 25
writes the keys the user types, not yet run on the phone.
