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
