# World of Warcraft (3.3.5a) — the brief

The app being built in this repository: what the user asked for, what was decided, what's still
open, the plan, and where each stage stands ("Where it stands", below). The user's manual is
`README.md`. Read `docs/single-game-apps.md` first: this app
follows the same playbook, but its emulator is a whole Windows (Wine) and server stack.

## Identity

| | |
| --- | --- |
| App name | **World of Warcraft** |
| Package | `com.logie.warcraft` |
| Folder | `warcraft/` |
| Release tag prefix (proposed) | `wow-build`, releases **World of Warcraft 1.0.N** |
| The user's phone | **Galaxy Z Fold 8**: Snapdragon, so an Adreno GPU (the open-source Turnip Vulkan driver works) |

## What the user asked for

- Play the **World of Warcraft: Wrath of the Lich King 3.3.5a** client on the phone, with very
  simple touch controls:
  - **Tap** = click.
  - **Swipe** up, down, left, right = movement with W/A/S/D, "a full 360" of directions.
  - **Hold** = open or close the bags.
  - **Double tap** = jump (Space). (First asked as "open the map"; changed by the user.)
- **A launcher.** The user drops in their own 3.3.5a client (never shipped by the app), and adds
  **add-ons** to it from the launcher's menu: **any add-on, installed at will**. None come
  preinstalled and none are special-cased in code.
- **A private server on the phone, set up silently**: AzerothCore, specifically **ChromieCraft**
  (see below). Tapping the app goes straight to the game's **login screen**, with the server
  already running behind it. (Later changed by the user: nothing starts on opening the app;
  **Press start** goes straight to the login screen.)
- **ChromieCraft fully integrated**, "with all available ChromieCraft options", in the launcher.
- Launcher sections of their own for **account creation**, **bot management** and **server
  options** (and any other settings a private server allows).
- **App icon switcher**: the user sent icons so the app's icon can be changed from the launcher.
- Look: the header image and logo the user sent (below).

## Decided (the user's answers)

- **Client:** a clean 3.3.5a (build 12340), **enUS**, the one ChromieCraft uses. No HD patches.
  (Whether it comes as a folder or a zip: support both.)
- **Server behaves the way ChromieCraft does:** its core revision and modules, its progression
  (starting at the first bracket, advanced from the launcher), its blizzlike rates, as defaults.
- **Nothing is fixed at setup.** Bots and every other server setting (progression, rates,
  realm options) are chosen in the launcher **at any time**, not in a first-run wizard. The
  launcher writes the config and applies it (`.reload config` where a setting supports it,
  otherwise a world server restart, saying first if the game will disconnect).
- **Add-ons:** a full manager with warperia.com as its catalogue ("use this as a repo for an
  addon browser ... click into the addon or just install it in one click ... a fully fledged
  addon manager"), plus importing zips.
- **Double tap = jump.** The map moves to another gesture (proposed below).
- **Phone:** Galaxy Z Fold 8.
- **The server's data** (dbc, maps, vmaps, mmaps) can be made **either way**: on a Windows PC
  (CI builds AzerothCore's tools for Windows; `windows/extract-server-data.bat`, then import the
  zip) or straight from the phone (the app imports the client and runs the tools).
- **"Make GitHub Actions do the server data":** the app downloads AzerothCore's ready-made data
  (github.com/wowgaming/client-data, the release the pinned core's own installer uses, pinned with
  its size and SHA-256 in `server-data.env`), and CI downloads the same file and tests the server
  on it all the way into the world. Making it in CI from the user's client would need the 17 GB
  client uploaded somewhere CI can fetch it, and mmaps take hours. The two ways from the client
  stay.
- **Launcher music:** the main title theme the user sent (`res/raw/wow_title_music.ogg`, Opus 160
  kbps from their MP3) loops on the launcher, and **stops when the game launches**: stage 2's game
  start calls `TitleMusic.stopForGame()`. A switch in the header turns it off (remembered).

## The user's art (in `branding/`)

- `header.jpg`: the Lich King with Icecrown Citadel and a frost wyrm, 3840 × 1240 (converted
  from the PNG the user sent).
- `logo.png`: the *World of Warcraft: Wrath of the Lich King* logo, 3518 × 1534, transparent.
- `icons/`: the user's icon pack: **WotLK** (frost, blue) and **Cataclysm** (fire, dark) "W"
  icons, each as Windows `.ico` and Mac `.icns` (largest size 256 × 256), plus the 256 px PNGs
  taken from the `.ico` files. These are the choices for the icon switcher (build adaptive-icon
  layers from them; the default is WotLK). Android switches launcher icons with an
  `activity-alias` per icon, enabling one and disabling the others through
  `PackageManager.setComponentEnabledSetting`; launchers may take a moment to refresh and remove
  home-screen shortcuts of the disabled alias.

## The plan as told to the user (feasibility)

Feasible, but the hardest app so far, and the first where the phone matters more than the code.

**The client.** 3.3.5a is a 32-bit Windows program. It runs through **Wine** (Windows), **Box64**
(x86 to ARM translation; Winlator also ships Box86 for 32-bit programs, and newer Wine and
Box64 can run them in Wine's WoW64 mode) and the GPU
driver (**Turnip** on Adreno, through DXVK for Direct3D 9, or the client's OpenGL mode). **Winlator**
packages exactly this as an Android app and is open source (LGPL-2.1,
https://github.com/brunodev85/winlator; its `app` is a submodule pinned to a commit, check that it
matches a release). Build the app on it the way V3 Gold is built on Vita3K: a pinned commit,
patches, an overlay (pattern A in the playbook), with Winlator's container screens replaced by
this app's launcher, one container made automatically, `Wow.exe` started directly, and the
gesture layer in place of Winlator's input controls. Things to check in Winlator's source: its
target SDK (executing binaries from app storage), its own X server and input injection (where
the gesture layer sends clicks and keys), and how its rootfs (`imagefs`) is shipped.

**The server.** AzerothCore and MariaDB built natively for arm64 (no translation). The most
direct route: build them for Linux arm64 (glibc) in CI on GitHub's `ubuntu-24.04-arm` runners
and run them inside the same arm64 rootfs Winlator ships, from a foreground service (Android
shows a notification for it; that's as silent as Android allows). The launcher starts it,
waits for the auth server (port 3724) and world server (8085) to answer, writes
`realmlist.wtf` (`set realmlist 127.0.0.1`), then starts the game.

**First-time setup on the phone.** Import the client (about 17 GB; check it's build 12340 from
`Wow.exe`'s version). Extract the server's data from it (`dbc`, `maps`, `vmaps`, `mmaps`) with
AzerothCore's tools, on the phone, because CI can't have Blizzard's files. `mmaps` (pathfinding,
which bots need) take an hour or more on a phone; offer a PC script as a faster way, then import.
Create the databases, an account for the user (GM), and the realm.

**Bots.** `mod-playerbots` needs its own AzerothCore branch (it began as
https://github.com/liyunfan1223/mod-playerbots with the core at
https://github.com/liyunfan1223/azerothcore-wotlk, branch `Playerbot`; check where both live
now), and the core and module commits have to be pinned together: mismatches crash or fail to
build.

**Controls** (first version; the split-screen scheme under "Where it stands" replaced the swipe
and the two-finger drag). Decided by the user: tap = left click, swipe = movement (W/A/S/D), hold = bags
(`B`; Shift+B opens all bags), double tap = jump (Space). Proposed, unless the user objects:
the client's built-in **Click-to-Move** on, two-finger tap = right click (talk, loot, attack,
walk there), two-finger drag = turn the camera, tap then tap-and-hold = map (`M`), three-finger
tap = show the keyboard (chat, passwords; Wine can't tell Android a text box is active). Swipe
in 8 directions first (like Parasite Eve's D-pad); true 360° by steering with mouse-turn while W
is held, later. A double tap with a second finger while one swipes = jump while running.

**Limits told to the user.** A translated Windows game, a server and bots on one phone run hot;
start with 50–200 bots. About 40 GB free storage. Android needs a notification while the server
runs. Only the server can be tested in CI; the game half only on the user's phone. Fallback: the
server on a PC, the app only plays. The user supplies the client; it never goes in the repo or
APK. Playing on a private server is against Blizzard's terms; for a local server on their own
phone, it's the user's call.

## ChromieCraft

ChromieCraft is an AzerothCore-based progressive server (it opens content bracket by bracket, as
the original game did). Its code:

- **Core:** https://github.com/chromiecraft/azerothcore-wotlk, a read-only copy of the exact
  AzerothCore revision ChromieCraft runs.
- **Progression:** https://github.com/azerothcore/mod-progression-system (AGPL-3.0), "the
  ChromieCraft Progression System": level brackets and content tiers (named like `40_49`,
  `61_64`, `80_1` in its README), each switched on or off in `conf/progression_system.conf.dist`
  (read that file for the exact option names). Each bracket
  replaces scripts and applies SQL to the world database. **SQL can't be unloaded**: going back a
  bracket leaves the later bracket's changes; the clean way back is a fresh world database. The
  launcher has to say so before changing brackets, and offer "reset world database".
  Support module: `mod-zone-difficulty` (per-zone nerfs and buffs).
- Related modules: `mod-chromie-xp` (azerothcore org), and in the chromiecraft org:
  `mod-chromiecraft-smartstone`, `mod-npc-talent-template`, `mod-gm-commands`,
  `mod-hoard-collector`, `mod-enhanced-support`, `mod-contributors`, `mod-ptr-changes` (some are
  for the live server only; decide per module).
- "All available ChromieCraft options" = every option those modules' `.conf.dist` files offer,
  plus `worldserver.conf`'s (rates, realm, instances, characters) and `authserver.conf`'s that make
  sense on a phone. Read the files at the pinned commits and build the launcher's server screens
  from them (grouped, with plain explanations and defaults).

**ChromieCraft plus playerbots: settled by building it.** ChromieCraft's core repository is a
mirror of upstream AzerothCore `master`, synced daily (its head is an ancestor of upstream's). The
playerbots fork (now https://github.com/mod-playerbots/azerothcore-wotlk, branch `Playerbot`;
the module moved to https://github.com/mod-playerbots/mod-playerbots, the old liyunfan1223 URLs
redirect) is upstream plus about 150 commits, merged from upstream every few days. Merging the
fork's commit onto ChromieCraft's has no conflicts: it changes 33 files (the module database pool,
pathfinding, the mmaps generator, CI). `scripts/fetch-azerothcore.sh` does that merge from the two
commits pinned in `server-version.env`, with mod-playerbots at the commit made for that fork
commit, and the ChromieCraft modules on top. Building it in CI is the test (see "Where it
stands"). Updating: pin a new ChromieCraft commit and the fork's and module's latest together.

Modules chosen: mod-progression-system, mod-zone-difficulty, mod-chromie-xp,
mod-chromiecraft-smartstone, mod-hoard-collector, mod-npc-talent-template, mod-playerbots. Left
out as live-server staff tools: mod-gm-commands, mod-enhanced-support, mod-contributors,
mod-ptr-changes.

## Launcher sections (to design with the user's art)

- **Play** (title screen: header, logo, press start).
- **Game files**: import the client (folder or zip), check the build, show size; the data
  extraction with progress.
- **Server**: running or not, start/stop, logs; ChromieCraft progression brackets; rates and
  realm options; reset databases. Every option changeable at any time.
- **Accounts**: create, change password, GM level, delete; which one the game logs in with.
- **Bots**: on/off, how many, level ranges, per-account bots, the playerbot settings, at any
  time.
- **Add-ons**: install any add-on at will, nothing preinstalled or special-cased. Browse
  warperia.com's 3.3.5a list in the app and install in one tap, or import a zip; find every
  add-on in it by its `.toc` file, however it's nested (a zip may hold several, or wrap them in
  an extra folder), and install each as its own folder in `Interface/AddOns` (replacing an older
  copy). List them, update them, switch them on or off (moved out to `Interface/AddOns-off`; the
  game's own AddOns button at character select keeps working too), delete them.
- **Controls**, **Graphics** (resolution, renderer, frame cap), **App icon**, **Updates**.

## Stages

1. **Server in CI.** Build the chosen AzerothCore branch with its modules and MariaDB for Linux
   arm64; in CI start the database, create the databases and start the world server to its
   "ready" line. Then the app shell: launcher UI and the server running on the phone. The user
   can check it by connecting a PC client to the phone.
2. **The client.** Winlator's runtime in the app, the client started directly at its login
   screen, gestures.
3. **Everything else.** All server options, accounts, bots, add-ons, the icon switcher, polish.

## Where it stands

**Stage 1 (released; CI's arm64 server test reaches the world with a character; nothing tested
on a phone):**

- The server is built for Linux arm64 on **musl** (Alpine), not glibc and not inside Winlator's
  rootfs: Android kills an app's process that makes a system call outside its seccomp policy, and
  glibc makes such calls (`rseq`, `set_robust_list`), which musl doesn't need; a self-contained
  package also doesn't tie the server to Winlator's. Each program's interpreter is the package's own musl loader at
  `/data/data/com.logie.warcraft/files/server/lib/`, like Termux, so the server only runs for the
  phone's main user. MariaDB is Alpine's, run with `--innodb-use-native-aio=0` (no io_uring).
- AzerothCore needed patches to build on musl with MariaDB's client library
  (`patches/azerothcore/`: missing includes, glibc-only names, MySQL-8-only calls, and a version
  check that read MariaDB 11 as older than MySQL 8). Its bundled jemalloc is configured for glibc,
  so the world server preloads Alpine's.
- CI (`build-warcraft.yml`, `ubuntu-24.04-arm`) builds it, then tests the package unpacked where the
  phone puts it: MariaDB from the seed folder, dbimport fills the auth, characters and world
  databases, an account made the way the app makes them logs in to the auth server with the 3.3.5a
  protocol and gets the realm list, and the world server (on jemalloc) starts on the server data
  the app downloads, up to "ready". Then a 3.3.5a world client (`server/auth_check.py world`)
  creates a character, enters the world with it, runs `.server info`, and logs out, and the world
  server shuts down the way the app stops it. Every system call of the server's programs is checked
  against bionic's app policy. (Tried and dropped before the download: stand-in dbc files.) The
  full start found two faults the earlier test couldn't reach: mod-npc-talent-template keeps its
  SQL where the servers don't look (`sql/<database>`; the package puts it in
  `data/sql/db-<database>`, without its outdated `development.sql`), and the world server stopped
  the moment a client entered: RC4, which encrypts the game's packet headers, is in OpenSSL 3's
  legacy provider, a module the package didn't have (now `lib/ossl-modules/legacy.so`, found
  through `OPENSSL_MODULES` in `ctl.sh`). The arm64 run then caught `setfsuid`/`setfsgid`, outside
  Android's app policy, from ncurses opening terminfo for the world server's console once the
  world is up: `libandroidcompat.so` makes them no-ops, as it turns `accept` into `accept4`.
- The app: launcher (header, logo, press start, tiles, the title music), the server in a
  foreground service, client import (folder or zip, build 12340 check), server data downloaded
  (resumable, SHA-256 checked), made on the phone or imported from the Windows tools' zip,
  accounts, the icon switcher (done early: the art was there), updates. Targets
  SDK 28 (like Termux and Winlator) to run the server's programs from its storage, and Wine's.
- On the user's phone: the realm online and the world server up (with the downloaded data).
- Defaults set on the first start: progression brackets `0` and `1_19` (ChromieCraft's start),
  50 random bots (the module's default is 500), bots idle while nobody's logged in.

**Stage 2 (in progress; built and packaged in CI, nothing run on a phone yet):** the user asked
for the game on the phone itself ("that's the whole point").

- Winlator 11.2 is built into the app's own module (`scripts/prepare-winlator.sh`), not run as a
  separate app: its Java code (using the app's `R`), resources, native code (NDK 24, CMake 3.22),
  prebuilt libraries and assets (rootfs, container pattern, Box64, DXVK, Vortek/Gladio/Turnip
  drivers). Only `app_name` and its FileProvider paths clashed with the app's; its FileProvider is
  left out (its authority would clash with Winlator's own app).
- Its runtime has its install path compiled in (`/data/data/com.winlator/files/rootfs`, 900-odd
  places: Box64's ELF interpreter, Wine, scripts, links). `scripts/winlator-rewrite.py` rewrites
  every `.tzst` to `/data/data/com.logie.warcraft/rootfs`, the same length, so nothing moves;
  `RootFS.find` is patched to `getDataDir()/rootfs` to match.
- AGP 9 makes resource ids non-constant in the app module too (`android.nonFinalResIds=false` has
  no effect), so Winlator's `switch` statements on them became `if` chains (patch 0002); its
  theme attributes from Material and AppCompat come through `android.nonTransitiveRClass=false`.
- `game/Game.kt`: **Press start** (opening the app did too until build 39) starts the server, installs the runtime
  meanwhile (patch 0003, without Winlator's screens, progress in the launcher), makes one container
  (client as D:, the screen's shape at 720 lines), writes `realmlist.wtf` and `Config.wtf`
  (127.0.0.1, realm name, intro off, the account's name), waits for the world, stops the music and
  starts Winlator's `XServerDisplayActivity` on `Wow.exe`. Its **Exit** finishes back to the
  launcher (patch 0001). With no game account yet it says so and opens Accounts.
- Not on a phone yet: whether DXVK + Vortek run 3.3.5a's Direct3D 9 well on the Fold's GPU (the
  alternative is the client's `-opengl` with Gladio), how the first Wine start goes, and sound.

- The gestures (under "Controls", as proposed there): `controls/TouchGestures.kt` (plain Kotlin,
  unit-tested: taps, double tap, hold, tap-then-hold, swipe steering with a stick that follows the
  finger, jump while walking, two-finger tap and drag, three and four fingers), and
  `game/GestureView.kt`, which Winlator's game screen puts over its touchpad (patch 0004) and which
  turns them into the X server's pointer and keys. Back is Escape rather than leaving: the game's
  menu has Exit Game, and a stray Back doesn't end the session; four fingers open Winlator's menu.
  The true-360° steering (mouse-turn while W is held) is left for later: two-finger drag turns the
  character, which covers it.

- The user then asked not to use Winlator "if possible". Asked to choose between keeping its code
  hidden, a stack of our own (Wine's Android driver + Box64 + DXVK, no confirmed working setup
  found), and the open-source client rewrite Whoa (login and character screens only, can't enter
  the world): **keep Winlator's code, hide every trace of it.** Nothing of Winlator shows: no
  separate app, its game screen's drawer menu replaced by the app's own (`game/GameScreens.kt`:
  back to the game, keyboard, leave), its loading box by the app's loading screen (logo, snow,
  spinner), no Winlator name in the app's text or the release notes; its background-protection
  notification stays off (a Winlator setting nobody can reach). Its name stays in the build
  scripts, the README's build and license sections (LGPL-2.1 credit) and code comments.

- **On the phone (build 28): "it works".** The user then asked for a more intuitive swipe
  experience and the keyboard as an Android pop-up when entering text, and chose from the
  suggestions: **split screen** (left half a stick drawn under the thumb, right half one-finger
  drag turns camera and character), **strafe, not turn** (stick left/right = Q/E), **pinch to
  zoom** (the wheel); not auto-run or vibration. Keyboard: **pops up by itself at the login screen
  and the chat, and also character creation**. Built: `TouchGestures` rewritten per finger (each
  thumb on its own; two fingers that land within 200 ms are a pinch, a two-finger tap or a
  two-finger swipe up (chat) until they move apart, which tells walk-and-turn from a pinch);
  `GestureView` is a text editor for the keyboard (an InputConnection typing plain characters,
  no suggestions; Enter is the game's; the game shrinks above the keyboard). Character creation:
  the launcher tells the game screen when the account has no characters, so the keyboard stays up
  after the login for the name (the game goes there by itself); a new character later needs a
  three-finger tap (nothing tells the app that screen opened).

- Then: "immersive touch controls without on-screen overlays", "an authentic WoW experience",
  "clicking on NPCs also talks to them" (build 29: a tap selected an NPC but couldn't talk).
  The stick isn't drawn any more. A tap now does what WoW's cursor says (`controls/CursorKinds`,
  `GestureView.tapAt`): the pointer goes there, after a frame or two the game's cursor there is
  read from the X server (the window's cursor, fingerprinted by its picture; `gxCursor 1` keeps
  it a system cursor), a left click, and when the cursor was special (not the login screen's
  arrow, nor the one most taps see) and is still the same after the click, a right click: talk,
  attack, loot, gather, as on a PC. The two-finger right click now waits for the game to see the
  pointer first. Without a readable cursor, a second tap on the same spot within 1.5 s right
  clicks. Not verified on the phone: that Wine and DXVK hand WoW's cursor to the X server.

- On the phone (build 30): "this is great". Then: a single tap should select an enemy, a double
  tap on it attack; single taps keep acting on friendlies and objects. The cursor can't tell an
  enemy (the sword is a picture to the app), the realm can: patch 0007 makes the world server
  write, for each selection by the client on 127.0.0.1, a count and whether it's a living,
  attackable unit (`files/run/selection`, named by `WOW_SELECTION_FILE` in `ctl.sh`). A tap on a
  special cursor left clicks, waits up to 400 ms for that word, and right clicks unless it's an
  enemy; the cursor's fingerprint is remembered as an enemy's or not, so later taps don't wait. A
  double tap: the second tap attacks when the first selected an enemy, else jumps (or nothing
  after an interaction). The Windows tools' cache key leaves 0007 out (game server only).
- Resolution (the user's Z Fold 8): **Graphics** tile, the phone's screen by default (every pixel
  of the screen it's on), or 1080 / 720 lines at its shape (`GameSettings`, next game start).
- Add-ons: CurseForge browsing in the app was set aside by the user ("too much"); for now **import
  zips** (`Addons`, `client/AddonLayout`: any packing, folder named after its `.toc`), list and
  delete; `checkAddonVersion 0` so 3.3.5a add-ons marked otherwise load. (Then warperia.com, below.)

- On the phone (build 31): "black screen and crash during login if you use your keyboard"; asked
  for "the keyboard like a prompt rather than pushing the screen up". Most likely the shrink
  above the keyboard: resizing the game's surface while it draws. Now the game's window never
  resizes (adjustNothing) and text goes through `GamePrompt`, a box at the top with the phone's
  keyboard; on send the app types the text into the game, 15 ms a key (the password then Enter;
  a chat line then Enter, Cancel closes the chat; a name or other text as it is). The name
  prompt for a new account comes 7 s after the login.

- Then: the mouse pointer invisible "in a touch screen way", still moving and clicking for the
  game; and pinch to zoom (in since build 29). The renderer no longer draws the cursor once the
  game's window is up (the X server's pointer and the game's cursor are unchanged, so taps and
  the cursor reading work as before); a mouse connected to the phone brings it back. The pinch
  takes fingers landing within 300 ms (was 200).

- Then: "make it extend to full screen size not default resolution". Three causes covered (not
  told apart on the phone): the game's first-start hardware detection replacing the resolution
  in Config.wtf (`hwDetect 0` now); the game screen kept clear of the camera's edge
  (`layoutInDisplayCutoutMode` always, so the view is the whole screen, the size the game is
  made); and Winlator's force-fullscreen on, which scales a game window smaller than the screen
  up to fill it, the pointer mapped with it.

- Then: "can I give you the login info and it automatically logs me in?" Not in the code (it
  would be in the repository and every APK): `SavedLogin` keeps the account and password in the
  app's own storage, from **Accounts → Log in automatically** (checked against the account's
  SRP6 verifier first; the first account made in the app gets it), or from the game's password
  prompt (**Log in automatically next time**, on by default, kept once the realm confirms it).
  The game starts with that account in the box, waits for the login screen (the game's cursor
  shown a few times running, at most 15 s), and types the password and Enter.

- Then: a two-finger tap targets the nearest enemy (Tab, in place of the right click: one tap
  already talks, loots and uses), and rapid taps on the action bar must never jump. The game's
  pointer doesn't tell a button from the ground, so it's the place (`controls/ScreenZones`): the
  bottom 22 % and the right 8 % of the screen, where the default interface puts its bars; a
  quick second tap there is another press. Bars moved elsewhere by an add-on aren't covered.

- Then: "when in game hide the notification". The server's foreground-service notification steps
  aside while the game screen is started (`ServerService.setGameShown`, from the game screen's
  lifecycle: stopForeground, the service still running; the visible game keeps the process at
  the front) and comes back on its onStop (another app, home, screen off). Targets SDK 28, so
  going back to the foreground from there isn't restricted.

- Then: "use warperia.com/wotlk-addons as a repo for an addon browser ... a fully fledged addon
  manager". The site's REST API wants a key, so the app reads its public pages as a browser does
  (`addons/Warperia`, jsoup): the list (`?search=`, `category=`, `sort=recent|updated`,
  `paged=`, 20 a page, 838 add-ons in 42 pages in October 2026), an add-on's page (version, days,
  downloads, categories, developers, description, screenshots, changelog) and the free download:
  its waiting page (`data-countdown-seconds`, 5) and, after the wait, the form its script sends
  (`admin-ajax.php`, `increment_addon_download`, the page's `download_nonce`), whose answer is a
  link to the zip valid for that IP and user agent only (`WarperiaDownload`, one retry on 403).
  The app waits the site's seconds like any visitor (skipping them is the site's Premium).
  The site serves its card markup under two sets of class names (seen 1 in 7 loads), so the
  parser leans on attributes (`data-addon-id`, `data-tooltip`, the tab ids, the facts' labels)
  with fallbacks; it writes days two ways ("Aug 7" in the list, "Aug 07" on a page), compared as
  `yyyy-MM-dd`. Installs go one at a time through the zip installer; `files/addons/warperia.tsv`
  (`AddonRecords`) keeps each one's slug, version, day and folders, for updates (the page's
  version or day moved; checked when the screen opens, at most every 30 minutes, or by hand),
  on/off and delete (libraries another install also brought stay). The screen
  (`ui/WowAddons.kt`) is full-screen over the launcher: Browse (search, order, categories,
  cards with Install / Update / progress, endless scrolling, two columns on the open Fold),
  an add-on's page, Installed (Update all, Check for updates, Import zip, switches, delete).

- Then: "make it so the server doesn't auto start when the app opens". Opening the app only
  checks for an update now; the server and the game start from **Press start**.

- Then: "the hold tap that uses B also functions as the ESC key ONLY during cutscenes like the
  intro ones when creating new chars". The realm knows: patch 0008 writes `files/run/cutscene`
  (`WOW_CUTSCENE_FILE`) for the 127.0.0.1 client: "c <time>" when it sends a cinematic
  (`Player::SendCinematicStart`, the first login's intro among them), "m <time>" for a movie,
  "- <time>" on the client's complete-cinematic or complete-movie (the latter had no handler; it
  has one now that does only this) and on logout. The hold sends Escape while one plays
  (`controls/Cutscene`), at most 4 minutes for a cinematic and 8 for a movie if the end is never
  said.

- Then: "make the HD patch and alternate HD patch as options in the launcher that can be turned
  off or on" (chromiecraft.com/en/downloads). The HD patch is `additional_patches_for_335a.zip`
  (3.4 GB, deflated entries: Patch-F, G, H, S, T, X and a readme saying X spoils T); its server
  sends it only with a browser's user agent and chromiecraft.com as referer. The alternative is a
  whole HD client on Google Drive (`wotlkhdclient_stable_enUS_all-updates.zip`, 29 GB, stored
  entries) with a patch menu (`patchmenu/.config/config_en.json`: each patch a letter, in `Data/`
  and `Data/enUS/`, and its defaults). `client/RemoteZip` reads a zip's directory from its end
  (zip64 too) and `client/ZipDownload` fetches single entries by range (resuming stored ones,
  inflating deflated ones, checking the CRC); `client/HdPatches` says which; `ClientMods` (a job in
  `ImportService`, kind MODS) puts them in or takes them out, lists them in `files/mods/hd.txt`,
  and keeps a client file of the same name as `.user`. Reading the patches' MPQs by range showed
  which client tables they replace: the alternative's HD characters (enUS a) CharSections and the
  other character tables; its HD models (enUS f) and CC's G CreatureDisplayInfo and
  CreatureModelData; its s SoundEntries (enUS s: Spell). CC's H (characters, by its readme) and F
  (mounts and creatures, not read: 0.8 GB deflated) are taken to do the same. Those conflict with
  custom races, so with races on they're set aside (`.off`). The alternative's dungeon maps carry
  an add-on, left out.
- `client/WowExePatch`: the interface signature check off (six edits; the alternative HD client's
  wow.exe has exactly these, and undoing them gives back bytes that patch to it again) and large
  address aware. `WowPatched.exe`, used by `Game` while any client mod is in; each edit checks the
  bytes it replaces.

- Then: "build it with the switch. Bots can only use the originals. Also include worgen and
  goblins. Also if needed can you just retrieve whatever from the other COA and convert it". The
  races come from Medviten's mod-worgoblin-high-elf (AGPL-3.0, pinned in `races/races.env`), which
  has Goblin and Worgen as well as the rest, for the stock 12340 client: CoA's files weren't needed
  (CoA is made for the Ascension client). Its race packs ("modpaks") change the game's tables with
  SQL; `scripts/build-races.py` loads the stock tables into MariaDB with Thorium's layouts (our own
  in `races/meta` for the seven it lacks), runs the packs listed in `races/manifest` (the NPC races
  moved to 169-181; then Goblin 9, Worgen 12, High Elf 13, Mag'har 14, Ogre 15, Dark Iron Dwarf 16,
  Zandalari Troll 17, Vulpera 18, Pandaren 19 and 20, Broken 21; Kul Tiran and the half-made ones
  left out), writes the changed tables (31) and the world SQL with the variables worked out, and
  fixes one typo in the Worgen pack (its mounts' DELETE). The world SQL's one read of the DBC
  database (totems) is written in. Checked here: the world SQL applies to a clean world database
  with no errors; every custom race has 10 classes.
- The switch: on downloads the package (`wow-races-<hash>`, a prerelease CI publishes when its
  inputs change; client 450 MB zip into `Data/patch-Z.MPQ`, a folder the game loads as a patch,
  streamed out of the zip with resume; server 9 MB into `files/races`); the server's start runs
  `ctl.sh races`, which adds the world SQL once (table `app_races` says which version, or that
  adding it didn't end) and points DataDir at `files/data-races` (links, the races' tables in
  place). Off sets the client folder aside, and the next start makes `acore_world` again with
  dbimport. Characters stay (the core leaves a character whose race has no playercreateinfo out of
  the list). Ids are fixed in the manifest's order.
- Core patch 0009: the race hunks of the module's `whe-acore.patch` (ids, names, flight paths, the
  transforms and costumes by race, .pinfo, the pony's banner, druid forms by fur or hair colour)
  plus Broken, and its scripts (Goblin racials, Worgen Running Wild) without the login
  announcement; the rest of that patch (fatigue off, faction-free cities, a deleted Scarlet
  Enclave script, GM speed limits) left out. `patches/mod-playerbots/0001`: random bots of the ten
  original races only. CI's server test turns the races on (bots made again: none custom; a
  Worgen and a Goblin into the world) and off (world rebuilt; the two hidden, kept).

- Then: "make it so by default in controls when in the spell book I can click on an icon to drag
  it to an action bar". The touch layer can't see the game's windows, so the app installs its own
  add-on (`client/TouchAddon`, `Interface/AddOns/WowTouch`, hidden from the manager): while the
  spellbook is open it whispers its place (inside the frame's hit insets, in ten-thousandths of
  the screen) to the player itself as an add-on message; patch 0010 keeps that in
  `files/run/interface` (the phone's own session only) instead of routing it, and writes "-" at
  logout. A lone finger that comes down inside it is the mouse with its left button held
  (`TouchGestures` role DRAG), so WoW's own drag picks the spell up and the action button takes it.
  Checked here: the add-on run in Lua 5.1 and LuaJ against a stand-in API, the gestures, the note
  parsing; CI's server test sends the add-on message and checks the file.

- Then (screenshots): "the custom races is really fucked up": race icons wrong, doubled and
  misplaced with gaps, the Goblin a checkered cube. Causes, in the module (a work in progress) and
  in our build: its icon coordinates take the 1024-wide race icon texture for 32 icons a row (it
  has 16, 64 pixels each), so every icon showed half of another face; its button layout keeps a
  place for 39 races; the Goblin pack names `Character\GoblinPC` models and skins its client files
  don't have (they're `Character\Goblin`); the Ogre and Mag'har packs give the race model ids
  where display ids go (the Ogre was a dwarf woman and a human man); the Alliance Pandaren pack
  puts its side where its file string goes ("0", unknown to the screen); four races have no text,
  which stops the screen's race change halfway (no background); and our build read the shared
  variables back through `mariadb -B`, which escapes backslashes, so every model path set by a
  variable had doubled backslashes (the Pandaren's skins have them in the pack itself).
  Fixed: `build-races.py` undoes the batch escapes, fixes the packs' mistakes (`FIXES`), gives each
  skin, face, hair or other CharSections texture the client hasn't got the race's nearest that it has, and
  checks every playable race against the client's files (the module's client files plus the
  models and skins the stock tables name): file string, prefix, displays and models (a player
  model of the right sex), gear looks, skins, classes, doubled backslashes, and its place, icon
  and text in `races/client/RaceScreen.lua`, our file after the module's screen in `GlueXML.toc`
  (icons at 1/16, each side's races in two columns from the top, Durotar's and Teldrassil's
  backgrounds for Goblin and Worgen, texts for Zandalari, Vulpera and the Pandaren). A newer races
  package is put in at the app's next opening while the realm is stopped. Checked here: the build
  passes its checks; the screen's code (the module's CharacterCreate.lua and our file) run in
  Lua 5.1 lays the buttons out as listed with the right icons for both sexes (drawn from the real
  texture); not checked: the screen in the game.

- Then: "https://github.com/topics/azerothcore-module add modules as a menu option in launcher. It
  will use this repo like the add-ons browser and let me install modules that I selected." A
  module is C++ compiled into the world server (its loader is named after its folder), which a
  phone can't build, so the realm is built on GitHub: the Modules tile browses the topic (GitHub's
  search, 590 repositories; README as text), the user adds modules, and **Build** dispatches
  `build-warcraft-modules.yml` on the app's branch (`BuildConfig.WOW_BUILD_BRANCH`) with the app's
  release tag, a request name and `owner/repo@commit` lines (each module at its branch's newest
  commit). The workflow checks the inputs, checks out the release's tag (without credentials),
  adds the modules to the realm's own (`WOW_EXTRA_MODULES` in the fetch, compile and package
  scripts; submodules too), compiles with the main build's ccache, packages, runs the server test,
  and a second job (the only one with write access, running none of the modules' code) attaches
  `server-<request>.tar.xz` to the release, keeping the last three. The app follows the run (its
  title names the request), downloads the package with resume and unpacks it in place of the
  realm (`ServerPackage.installFile`, stamped `<version>:modules:<request>`); a new app version
  drops back to its own realm. Dispatching needs the user's fine-grained token (Actions read and
  write on this repository), checked with GitHub and kept in the app's private preferences.
  Checked here: unit tests of the topic search, READMEs, requests, run tracking and the kept
  state; both APKs build. The workflow can only run from a release that has it (46 on).
  Checked on GitHub: dispatched like the app does (wow-build-46, mod-transmog at 327753a): found
  by its title, compiled (the module's sources with the cached rest), packaged (its SQL for the
  three databases, its config, VERSION naming it), the world server ready in 68 s and a
  character in the world, `server-citestmodules46.tar.xz` (94 MB) attached to the release and
  downloaded through the API as the app does; about 20 minutes.

- Then (screenshots): the races "look much better now", but the Broken woman is headless (warrior
  and death knight), the Worgen man has "textures missing"; random names should be one name;
  Backspace on the keyboard. The Broken woman model (2368) is used by no creature of the game, has
  no head, and the pack gives her the Draenei woman's hair and horn geosets, which it lacks:
  `OWN_SQL` in `build-races.py` gives her display the Draenei woman's model and copies the Draenei
  woman's skins, hair and horns. The module's two-name randomizer (`TwoNames_CharacterCreate.lua`)
  is left out of `GlueXML.toc`: it made "First Last" names the realm rejects, named by list
  position rather than race, and took over the appearance's Randomize; the game's own Randomize
  gives one NameGen name of the race. The prompt: ⌫ and the keyboard's Backspace (with the
  prompt's box empty) send Backspace to the game; the name prompt empties the game's name box
  first. The Worgen man's textures all exist (skin and fur for each colour; the model's fur slot
  covers its mane, hair and facial hair, its body is the skin) and decode; what looks missing is
  asked of the user.

- Then: "I don't like the custom races options. Remove it. But keep the modules setup." Taken out:
  the Races tile and dialog, `CustomRaces`/`CustomRaceList`, the races package (`warcraft/races`,
  `scripts/build-races*.py/.sh`, its CI job and `wow-races-*` downloads), core patch 0009 (the
  races; the spellbook patch is 0009 now) and the playerbots patch (bots of the original races
  only), the HD looks' race rules. What phones that had them keep is taken out once
  (`LegacyRaces`): the client's `Data/patch-Z.MPQ` (or `.off`, `.part`) as the app opens or the
  game starts, the HD files they set aside back; the realm's part at its next start (`ctl.sh
  races`: the world database made again when `app_races` is there, `files/races` and
  `files/data-races` deleted). Characters of the races stay in the database, out of the
  character list (the stock core skips a race/class it has no playercreateinfo for). CI's server
  test plants such leftovers (an `app_races` table, the folders, a race-12 copy of the test
  character) and checks they go, the character stays hidden, and the human still plays. The
  `wow-races-*` prereleases stay on GitHub (unused). Modules unchanged.

- Then: "I need to be able to interchange modules. Is that possible". The user's first build
  (DungeonRespawn, mod-worgoblin, mod-no-hearthstone-cooldown, mod-weekendbonus, on wow-build-48)
  had failed: DungeonRespawn is written for AzerothCore's older script hooks ("marked 'override',
  but does not override"), and one module stopped the whole realm. Done:
  - The modules workflow compiles with `-k 0`; extra modules whose own files fail are left out
    (their folder deleted, the rest compiled again from ccache, at most four rounds); anything else
    failing fails the build. `build.json` (the modules in, those left out with the compiler's
    first error) goes to the release as `server-<request>.json` next to the package; the app reads
    it when the build is done and says what was left out (the list marks them "doesn't compile
    with this realm"). The parsing was run on the failed run's log: DungeonRespawn, its first error.
  - Kept realms: each realm put in stays in `files/modules/realms/` (`realms.json`, at most 12);
    **Put this one in** swaps it in without a build; **Use these modules** makes its modules the
    list; **Delete**. A realm for another version can't go in (its ctl.sh and the app change
    together): its package goes, its entry stays, **Build it again for this version**. A failed
    build has **Build again**.
  - The world database follows the realm's modules (`ctl.sh modules`, each start, after `races`):
    `files/mysql/.world-modules` lists the modules with `db-world` SQL; one on it that isn't in the
    realm now has the world database made again (the realm's modules' SQL goes in again as the
    world server starts). The core never cleans a module's applied-file entries
    (`UpdateFetcher`: `state != MODULE`), so the characters database keeps a module's tables and
    its SQL isn't applied twice when it's put back; items of a module no longer in are deleted
    from bags by the core (`_LoadInventory`). CI's server test records the modules at the first
    start, plants a gone module (`mod-gone` on the record, a `mod_gone` table), checks the world
    database is made again without it, a second run does nothing, and the realm's module updates
    are back after the world server starts.

- Then (screenshot of character creation): "Make it at this menu I can rotate the char by
  swiping. Only this screen should be affected." The client turns the model while the left
  button is held on `CharacterCreateFrame` and the mouse moves (GlueXML `CharacterCreate.lua`,
  `CharacterCreateFrame_OnMouseDown/OnUpdate`, read from a copy in mod-worgoblin's client patch);
  the app didn't know which glue screen is up. Done: `CharacterCreation` looks at a picture of the
  game's window (Winlator's `takeWindowScreenshot`, at most 256 high, every second while the game
  is in sight) for the race list's banners: blue in 0.06–0.17 heights across, red in 0.18–0.30,
  on 14 of 20 rows down 0.06–0.44 (red and blue swapped counts too, for a window read as RGBA).
  On the user's screenshot it says yes; on the 13 other screenshots of this project (the custom
  races' screens among them, their icons covering the banners) no. While it's up, a finger on its
  own that moves turns the character (`Role.TURN`: the left button on the screen's middle, the
  pointer going sideways, back to the middle every fifth of the screen), on either half, and a
  hold presses nothing (the stick's E had typed "e" into the name box). Untested on a phone: the
  picture of a window the game draws with the GPU (a different texture path in Winlator).

- The user's first realm with modules, built again end to end on GitHub (wow-build-50) with the
  same four modules at the same commits (`citestswap50`): DungeonRespawn was left out (its first
  error recorded in `server-<request>.json`) and the other three compiled. The world server then
  segfaulted as playerbots created its first random bot: mod-worgoblin (unmaintained) adds
  Worgen and Goblin through `chrraces_dbc` in its world SQL, without the server's DBC files or the
  client patch it needs, and the bots most likely pick those races. The same build without it
  (mod-no-hearthstone-cooldown, mod-weekendbonus: `citesttwo50`) built, started, took a
  character into the world and was attached to the release. Told the user: take mod-worgoblin
  and DungeonRespawn off the list and build again on 1.0.51. A module that compiles but crashes
  the server still fails the whole build ("failed at: Test the server").
  The user's own builds from the phone meanwhile: mod-no-hearthstone-cooldown alone on wow-build-49
  built (that realm is for 1.0.49: built again for a later version); mod-no-hearthstone-cooldown,
  mod-arac and mod-worgoblin on wow-build-50 failed at the server test (the world server didn't
  start; mod-worgoblin was in it). mod-arac (all races with all classes) also wants a client patch
  for the character creation screen to offer the new pairs.

- Answered without changes:
  - Portrait play: possible when the game starts (the X screen and `gxResolution` made tall, an
    add-on rearranging the interface), live rotation unproven (Winlator's screen is sized once).
    The user dropped it.
  - Where to find up-to-date modules: the catalogue at azerothcore.org lists the same topic the
    app does; AzerothCore's own (github.com/azerothcore) and ChromieCraft's (github.com/chromiecraft)
    modules keep up with the core; this core's player hooks are `OnPlayer…`
    (`OnPlayerBeforeTeleport`), so modules still on `OnLogin`/`OnBeforeTeleport` don't compile.
  - The user's own modules and custom quests: possible server-side (quest text, items and NPCs
    come from the server; models and icons must be the game's own; the client's WDB cache keeps
    old quest text). Proposed: kept in this repository, offered in the Modules screen.
  - A "Wrath Remix" game mode (Legion Remix within Wrath, its own realm): assessed in
    `REMIX-ASSESSMENT.md`. The user first wanted it in a separate fork (later: built as a second
    realm in this app, see below); Remix must not change the ChromieCraft realm.

- Then the user sent a Wrath Remix design guide (`REMIX-GUIDE.md`, written with another
  assistant: Moratari, Eternus and Andormu, a prologue, a Crystalsong Forest hub, seven chapters
  of original quests with Moratari's transitions between them, levels 10 to 80, Bronze, Infinite
  Power, Keeper assignments, the original Lich King as the ending) and asked: "Create this as a
  distinct module or realm separate from the main ChromieCraft." Built as a second realm in this
  app, not a separate fork, with the module in this repository (`modules/mod-wrath-remix`):
  - The realm (the guide's phase 1): realm 2, "Wrath Remix", port 8086, sharing the auth server
    and accounts; its own configs (`files/etc-remix`, made by `ctl.sh configure`: StartPlayerLevel
    10, quest XP ×3, kill XP ×2, no race intros, no progression brackets, Chromie's XP lock at
    80, mod-zone-difficulty off, the module on; the module off in ChromieCraft's), its own
    databases (`acore_world_remix`, `acore_characters_remix`, `acore_playerbots_remix`, made by
    `ctl.sh dbimport remix`), `ctl.sh realmlist` writing both realms. One world server at a time:
    the launcher's **Realm** tile picks it (`Realms`, `Server`), `Config.wtf`'s realmName follows,
    and the client's quest cache goes when the realm changes (`client/RealmCache`). Module configs
    per realm need the core to read them from the realm's folder: patch 0010,
    `WOW_MODULES_CONF_DIR`. The module's SQL is in `data/sql/remix/`, listed in the Remix
    databases' `updates_include` by `ctl.sh`, so ChromieCraft's databases never get it.
  - The module: the realm gate (enabled and RealmID 2, else every hook returns), Timerunners
    (Bronze and chapter in `remix_character`), Bronze from quests and kills, Alurmi's Bronze
    shop (heirlooms, mounts, companions, bags) through gossip (the vendor window can't show a
    currency the client doesn't have), free riding, death knights brought to the Bazaar after
    Acherus, `.remix` commands.
  - The guide's level 10 start, over the assessment's 68: Northrend's zones and dungeons are
    banded to the chapters' levels (`remix_level_band`): creatures by the zone they appear in
    (`OnBeforeCreatureSelectLevel`; heroic dungeons and raids untouched), their spells' damage
    and healing scaled by the ratio of their base damage at the new and old level; quests'
    levels, minimum levels and gold, and their armor and weapon rewards' item level, stats,
    armor, damage and price (most of Northrend's rewards ask for no level), worked out in SQL
    from the originals kept once; dungeons open at the chapter's levels.
  - The prologue (WRM-001 to 005, 007) in the Caverns of Time (the intro flight's landing) and the
    Infinite Bazaar by the Violet Stand in Crystalsong Forest (three Azure Dragonflight raiders
    there removed from Remix's world), with Eternus, Moratari, Andormu, Nozari, Alurmi, Zephyr,
    Sa'at, the Chronologist, Momentus and an instructor per class (copies of the capitals'
    trainer lists). Models from the 3.3.5a client's tables (`CreatureDisplayInfo`): Moratari is
    Lady Alistra's look (blood elf woman, dark plate, glowing runes), Eternus Chrono-Lord Epoch.
  - The campaign's quests: every title the guide names matched to the world database
    (`tools/quest_audit.py` → `data/campaign_manifest.csv`, `quest_audit.md`): 271 of 272 found
    ("Bearly Hanging On" isn't a 3.3.5a quest), 38 titles that are an Alliance and a Horde quest.
    Chapter I's transitions built from it: WRM-006 (Alliance Borean, Thassarian's trail),
    WRM-016 (Horde Borean, Mobu's parts), WRM-026 (Alliance Howling Fjord, Westguard's missions),
    WRM-121 to 124 (each coast to the Dragonblight). The next original quest opens with either
    the transition or the skipped block's end (`conditions` with an else group), so nothing is
    marked done that wasn't.
  - Checked: the SQL applied twice to a copy of the pinned world database (idempotent, Borean
    rewards a quarter, Enlistment Day level 18); the C++ syntax-checked with the build's flags;
    CI's server test (see `scripts/test-warcraft-server.sh`, 6) plays the prologue on the Remix
    realm and checks the floor under every NPC placed. Not on a phone: all of it in the game.
  - Next, in the guide's order: Chapters II to VII's transitions (one chapter at a time, both
    sides, with the Wrathgate and the Battle for the Undercity checked first; II done in build 61,
    III in 62, IV in 63),
    Infinite Power,
    Keeper assignments, then challenge content.

- Then (the user played the demo): "really cool"; the intro needs much more explanation of who
  everyone is (later); "the first NPC I attacked I got absolutely trashed... I need starter gear
  appropriate to my class for every class". Causes: a character made at 10 has level 1's gear,
  only level 1's spells (trainers and class quests never happened) and weapon skills at 1 of 50
  (the core starts them at 1 unless `AlwaysMaxWeaponSkill`), and WRM-003 let three anomalies at
  it at once. Done (build 56):
  - A starter kit per class (`remix_starter_kit`, `0008_starter.sql`): heirloom shoulders, chest,
    weapons, trinket and the Dread Pirate Ring (they scale to 80; heirloom plate and mail are
    wearable before 40), and the best fixed-stat greens up to level 10 for legs, hands, feet,
    waist and back, plus The 1 Ring; the shaman's four totems. Put on in place of white or grey
    gear of item level 5 or less, else into the bags, else by mail; missing weapon proficiencies
    taught. Once per kit version (`remix_character.kit`), so existing Timerunners get it too.
    Death knights and the realm's bots (sessions without a client) are left out.
  - Training at login and every level: every spell the class trainers (the instructors' lists)
    teach at the level, free, and the abilities class quests teach (`remix_class_spell`:
    Defensive Stance, Taunt, Sunder Armor, Berserker Stance, the hunter's pet spells, the
    warlock's demons, Bear Form, Growl, Maul, Redemption, Poisons...); weapon and defense skills
    at the level's maximum (`UpdateSkillsToMaxSkillsForLevel`). Options `WrathRemix.StarterKit`,
    `AutoLearn`, `MaxWeaponSkill`.
  - WRM-003's anomalies come one at a time (each brings the next as it dies), at level 10 and
    half damage. Found while testing: the realm's random bots are made where every new character
    starts, the Caverns of Time, and a level 10 bot paladin judged the anomaly; a kill only counts
    for a player who did half its damage, so the Timerunner lost the credit. Each anomaly's death
    now credits the Timerunner it came for (a credit entry the quest asks for, 770032), and
    summoned creatures give no Bronze. CI's test kills them with `.damage` (`.die` gives no
    credit) and checks each one brings the next.
  - Ids made small: the core keeps creature templates, quests and items in tables as long as the
    highest id (`_creatureTemplateStoreFast`, `_questTemplatesFast`, `_itemTemplateStoreFast`), so
    Remix's 7.7 million ids cost about 120 MB on the phone. Creatures are 770001+ and quests 77nnn
    now; the SQL removes the old rows and moves Timerunners' quest records.
  - Released in build 57 (build 56's server test expected the axe skill of a level 10; the
    prologue's quests make the warrior 11).

- Then (before the demo): "the same vendors in legion remix need to be present at the hub... their
  own wrath specific ones... all the rare and specific wrath raid and dungeon sets and everything
  introduced there such as pets, toys, mounts, weapons, etc ... buyable with bronze like in legion
  remix. If bronze is too hard to incorporate then just use a currency like some sort of
  preexisting token." Done with Bronze itself (build 58):
  - Bronze is a currency token, item 77001 (Emblem of Frost's template, the bronze dragonflight's
    head for an icon), with a CurrencyTypes row (342, Wrath of the Lich King, bit 30) and Item.dbc
    row; prices are ItemExtendedCost rows of n Bronze, a ladder of 27 (25 to 25,000). The server
    has them from the Remix world database's `*_dbc` tables; the client from a patch the module
    writes as Wrath Remix starts (`ClientPatch.cpp`: the server's three DBC files with the rows
    added, in an MPQ of the first format with zlib sectors, as Blizzard's own patches are, at
    `Data/patch-Y.MPQ`; ChromieCraft's world server takes it out). Y is above every letter the HD
    patches use; a `patch-Y.MPQ` of the user's own is left alone. Bronze kept in
    `remix_character.bronze` before is paid into the currency at the next login.
  - The guide's roster: Alurmi (heirlooms, bags), Horos (rare collections: 109 Wrath mounts),
    Domelius (65 companions, 24 toys), Arturos (dungeons), Aeonicus (10-player raids), Durus
    (25-player and heroic raids, Val'anyr and Shadowmourne), Infinite Dragonflight models, in a row
    on the Bazaar's west side. Each opens its lists as vendor windows (gossip, then
    `SendListInventory(guid, list)`: a hidden vendor template per list, which the core remembers
    for the purchase). 48 lists, 4,650 wares, from `tools/vendor_catalog.py`: instance_encounters
    and the bosses' loot (references followed) and chests by mode, tier sets T7 to T10 by item set
    and item level, curated mounts, companions, toys and heirlooms. Gear: 200 Bronze at item
    level 200, 3% more a level, weapons and trinkets more. The core shows each player only their
    side's wares and their class's bind-on-pickup ones, so a list is split only past 150 for one
    class (a window's limit; a list holds 255).
  - Checked here: the server built and run on the real data (x86_64): the patch written, read
    back by the test's own MPQ reader and by mpyq (every row of the game's kept, Bronze's added),
    taken out by ChromieCraft's world server; the prologue with Bronze as an item (a heirloom
    bought in Alurmi's window for 300), Horos's mounts and Durus's Tier 10 as a warrior sees them
    (the warrior's 10 pieces, no mage's), Val'anyr bought for 20,000; an older character's 225
    Bronze moved into the currency tab; every vendor on the floor. Not on a phone: whether the
    game loads the patch, and the windows' look.

- Then: "maybe a stat squish for enemies not just a level equalizer." Done (build 59): a creature
  whose level comes from a band (Northrend's, by zone) has a share of its new level's health and
  deals that share of its new level's damage: `WrathRemix.Squish.Health` and `.Damage`, 75 each
  (melee, spells, damage over time; its heals scale with the health). The band was already making a
  creature a classic one of its new level; the squish makes the fights easier than the level alone.
  Creatures that keep their own level (the Bazaar, above their band) are as they were. `.remix band`
  reports how many creatures around it are squished and to what. Checked here: the server built and
  run, the prologue's character's band check (Borean Tundra's creatures at 75% of their level's
  health). Not on a phone: the feel of the fights.

- Then (continuing): the realm's random bots out of the prologue. Every character is made at
  `playercreateinfo`, the Caverns of Time in Remix, and a bot made at 10 isn't moved by the
  playerbots until its first teleport (an hour or more): the test's own run found 45 of 50 there,
  one of them the paladin that took the anomalies' credit. Done (build 60): a bot (a session
  without a client) entering the Caverns of Time area (2300) is sent, a second later, to the
  start of its level's chapter for its side, with its home there; not one in a group with a
  player. Checked here: the server built and run with the prologue played, 45 bots sent to their
  chapter and none of those in the world saved in the Caverns (the 451 never logged in are
  still where they were made: each goes at its first login); CI checks the same.

- Then (continuing, the guide's order): Chapter II's transitions. Done (build 61), from the Remix
  world database's chains (`0011_chapter2.sql`):
  - II-A, *The Road to Angrathar*: Alliance after Find Durkon! (by Cavalier Durkon), standing for
    The Noble's Crypt to A Righteous Sermon and The Bleeding Ore to Understanding the Scourge War
    Machine, to Into Hostile Territory (which the world opens only after both ends: their
    `NextQuestID`s, now conditions with an else group); Horde after The Forsaken Blight (by
    Doctor Sintar Malefious), standing for the taunka's oath, the Attack by Air! chain and the
    Icemist rescue, to The Kor'kron Vanguard! (its four required quests kept as the else group).
    Each takes the Timerunner back to the High Commander or the Overlord.
  - II-B, *What We Cannot Change*: no skip, after Return To Angrathar, by Alexstrasza at the
    Wrathgate (Moratari in her phase, 2, the one the Wrathgate is in after it); the guide's text.
  - II-C, *After Angrathar* (the guide's WRM-221): after the Battle for the Undercity (scripted in
    this core for both sides, `zone_undercity.cpp`), by King Varian Wrynn in Stormwind Keep or
    Thrall in Lordaeron's throne room (his spawn is in the battle's phase, 64, which ends as it's
    turned in: Moratari in 64 and 1), to Amberpine Lodge or Conquest Hold, Chapter III.
  - `0006` now clears only its own `remix_bridge` rows (it emptied the table, which would have
    taken later chapters' rows whenever it was applied again).
  - CI plays them on each side (`remix_chapter2_alliance.steps`, `_horde.steps`, now
    `remix_campaign_*.steps`, a level 30 human
    and orc): the quest before each transition given with `.quest add/complete/reward`, the next
    quest not offered before and offered after, the Wrathgate's phase, Chapter III and the Grizzly
    Hills at the end. The test client learned what that needed: Orcish for the Horde (the server
    drops a line, GM commands included, in a language the character doesn't know), a quest giver's
    hello and quest list for NPCs without gossip, a quest offered at once, calling a walking NPC
    over (`.npc follow`, past ~10 yards), and the second quest of a menu (it misread every quest
    after the first).
  - Checked here: the server run on the real data, the prologue and both sides' Chapter II played
    through, the five new Moratari spawns on the floor. Not checked: the Wrathgate and the Battle
    for the Undercity played for real (the test gives those quests by command), and anything on a
    phone.

- Then Chapter III's transitions (build 62, `0012_chapter3.sql`):
  - III-A, *A Shorter Path Through the Woods*: after Ursoc, the Bear God (Alliance by Hierophant
    Thayreen, Horde by Windseer Grayhorn), the hills' other wars left as they are, to Samir; it
    opens Filling the Cages in place of Drakuru's breadcrumbs (Troll Season!, Good Troll Hunting:
    their `NextQuestID`s now conditions with else groups).
  - III-B, *A Friend of Questionable Judgment* (both sides): no skip, after Voices From the Dust,
    by the Image of Drakuru at Drak'Tharon Keep's door.
  - WRM-321, *The Truth About Drakuru*: after Cleansing Drak'Tharon, to Crusader Lord Lantinga at
    Light's Breach (In Search Of Answers, Chapter IV). That quest is turned in to an Image of
    Drakuru the dungeon summons at its top, so it's the Moratari at the door.
  - Found on the way: the Horde's Grizzly Hills begins with The Conqueror's Task, which asks for a
    Dragonblight breadcrumb (To Conquest Hold, But Be Careful!) that WRM-222 had taken the
    Timerunner past; WRM-222 now opens it too.
  - CI's steps are one file a side now (`remix_campaign_alliance.steps`, `_horde.steps`), through
    Chapter III to Light's Breach. Checked here the same way as Chapter II: both sides played, the
    eight Moratari spawns on the floor. Not checked: Drak'Tharon Keep and the Grizzly Hills'
    quests played for real.
  - Next: Chapter IV (Zul'Drak); then V to VII.

- Then Chapter IV's transitions (build 63, `0013_chapter4.sql`), all both sides':
  - IV-A in two, by Stefan Vadu at the Ebon Watch: *The Things the Scourge Remembers* after The
    Ebon Watch, standing for the chokers' quests (a Withered Troll's drop: item-started, which a
    test can't give, and drop collecting, which IV-A skips) and the disguise errands, to
    Infiltrating Voltarus (Reunited, Darkness Calling's way's Voltarus quest, opens after it);
    *The Paperwork of Betrayal* (the guide's line) after Infiltrating Voltarus, standing for So
    Far, So Bad, It Rolls Downhill, Hazardous Materials and Zero Tolerance, to Sabotage, so
    Sabotage, Fuel for the Fire, Disclosure and Betrayal are played.
  - *A Pilgrim's Shortcut*: the guide's loa list goes from Precious Elemental Fluids to
    Preparations for the Underworld; between them, eleven quests of Har'koa's rescue. By Hexxer
    Ubungo, to Har'koa at Zim'Torga.
  - IV-B, *The Gods Are Still Silent*: after You Reap What You Sow and Betrayal, by Witch Doctor
    Khufu, standing for The Key of Warlord Zol'Maz and Rampage, to The Gods Have Spoken
    (Convocation at Zol'Heb and Unfinished Business, into Gundrak, are played).
  - WRM-421, *A Kingdom Gone*: after Gundrak's Gal'darah Must Pay (turned in outside, to Tol'mar)
    and Betrayal, to Hemet Nesingwary at his camp in Sholazar Basin, Chapter V. Not to Monte
    Muzzleshot, whose Welcome to Sholazar Basin starts the zone: Aotona, a hostile rare, flies 11
    yards from him (the test's character landed in a fight there and couldn't log out), and
    nothing asks for that quest.
  - Gundrak's quests weren't on Chapter IV's levels: its band named the area outside (4375), its
    quests are filed under the instance's (4416). Fixed in `0005_levels.sql`; Gal'darah Must Pay
    is level 52 now, not 78.
  - CI plays Chapter IV on each side after III. Checked here: both sides through to Nesingwary
    Base Camp, the four new Moratari spawns on the floor. Not checked: Voltarus, the loa's quests
    and Gundrak played for real.

- Then (after playing the demo): "The intro isn't very clear... there's no introduction to timerunning,
  why you're there... there needs to be a quest chain in the beginning explaining all that. I also
  don't like the caverns of time being the base of operations, choose somewhere else. Also it seems
  as though you get to choose where to start. I don't like that. It also mentions 3 echoes but only
  shows 2 ghosts... No guessing where to go, each quest leads you to the next one. Also it needs to
  have some sort of antagonist... The infinite dragonflight already is an antagonist at this point
  in time... perhaps they pull you back in time as a hero to stop it. But along the way after
  chapter 1 sylvanas windrunner comes from the future along with Alleria to stop you saying it is
  crucial to the integrity of Azeroth's world soul that the Lich King's story happens entirely
  as-is. And Sylvanas stands to gain more than anyone who would benefit from the lich kings story
  differing. You realize after carrying out some of the quests that youve been working for the
  wrong side. So rewrite all custom dialogue and quests up to this point... Moratari listens to
  Sylvanas, uses a spell, dissapears for about 10 seconds then a portal opens and she steps out.
  She says she learned that Sylvanas is telling the truth and we need to continue the timeline.
  Eternaus disagrees even though he knows this is what must happen and continues to interfere.
  From this point on Eternaus is not at the infinite bazaar but Sylvanas and Alleria are. So make
  the transition quests ones where you have to fight or do quests like this to stop the rest of
  the infinite dragonflight from changing things. Small changes can happen in quests or between
  chains as well... Keep everything consistent with lore."
  Lore notes given back (accepted): post-Shadowlands Sylvanas is serving her sentence, so the
  bronze dragonflight brings her and Alleria (her stake: a changed Lich King story loses the souls
  she's atoning for); the 3.3.5 Sylvanas is alive in the Undercity (two Sylvanases: the future one
  keeps out of her own sight); the argument lands hardest at the Wrathgate (Bolvar must burn there
  to take the Helm after Arthas and hold the Scourge); Eternus's motive: she believes a kinder
  timeline is possible and won't let it go. Her motive as read back and confirmed: she'd gain the
  most if Arthas's story changed (he killed her and made her a banshee), which is why her
  insisting it stay is what convinces.
  Decided (the user's answers): the home base and start is the Infinite Bazaar in Crystalsong
  Forest, not the Caverns of Time; one route through Chapter I, my pick ("as long as it is
  completely guided... If there's usually a 'find this on your own' without a quest, make it a
  quest"): Borean Tundra for both sides; the turn right before the Wrathgate ("just keep it lore
  friendly... fit it in where it's best... I'd rather it insert between quests if needed").
  Chapter V's transitions (built in the old voice, tested here, not pushed) wait for the rewrite.

- The rewrite, first part (build 64): the prologue at the Infinite Bazaar and a guided Chapter I.
  - Every new character but a death knight wakes at the Bazaar (`playercreateinfo`), before
    Moratari and Eternus. Five quests, each given where the last one ended (WRM-010 to 015): where
    and when you are and why you're weaker; Eternus shows three of the war's dead (echoes with the
    real people's looks: a Valiance Keep footman, Saurfang the Younger, Bolvar, all at the Bazaar
    in plain sight; the old prologue's third echo was most likely its big dark Icecrown model at
    half transparency, in a dim cave) and makes her offer; Alurmi and the class instructor (a
    credit any instructor gives as they're spoken to; they have the gossip flag now, so the game
    asks them for it before their lessons) and the hearthstone; the anomalies; and the front,
    Valiance Keep or Warsong Hold, before the officer who enlists (no choice of coast). The first
    prologue's quests (77001 to 77009) and the Caverns of Time's spawns are gone; a Timerunner
    made before and still in the prologue wakes at the Bazaar at their next login.
  - Chapter I: the Borean Tundra's chains need only one guide (Alliance: Mark Hanes's Word on the
    Street, a little way from Sergeant Hammerhill); Eternus's first kindness on each side
    (WRM-017 crypt crawlers on Valiance Keep's beach, WRM-018 the Nerub'ar at Mightstone Quarry),
    which the next chain quest now waits for; the transitions in the new voice.
  - The bots: the Bazaar is where characters are made now, so it's where bots are sent from (and
    the Caverns, for the ones made there before).
  - Checked here: the server built and run on the real data, the prologue played (the three
    echoes, the instructor, the anomalies, the purchases, Valiance Keep with Enlistment Day
    offered), Chapters II to IV on each side, every spawn on the floor, bots made at the Bazaar
    sent to their chapters. Not on a phone: the look of the echoes and the Bazaar.

- Then, while the rewrite was being built: "Make a button in the menu like under video interface
  exit etc that says report time anomaly that sends an issue to GitHub with exactly the process my
  character has made, which quest I'm on, which NPCs I've talked to during the current quest. This
  should happen invisibly going to GitHub if possible but if not just open it and let the user
  submit it." Plan: the app's own add-on (WowTouch) adds the button to the game menu on the Wrath
  Remix realm; it asks the realm (an add-on message, as the spellbook's place goes); the module
  writes the report (the character, where they are, the quest log with its objectives, and a
  journal it keeps of quests taken, finished and turned in, NPCs spoken to, transitions,
  teleports, deaths and levels) into a file for the app; the app files it as an issue with the
  GitHub token the user gave it for modules, or else opens GitHub's new-issue page filled in.

- Then the ending (the user's, not to be spoiled for them: the user doesn't read STORY.md):
  "it should end when you reach icecrown before you enter the raid you face Eternaus and the
  infinite dragonflight in a last ditch effort to stop you. You win obviously, but don't kill
  Eternaus who retreats at 10% health. then you move on to the icecrown raid. After that you are
  teleported back to the bazaar after the original cutscene. It also begins a quest called 'See
  you later' in which you say I'm ready to go back to the future and she sends you back after you
  say goodbye to key NPCs. She tells the player that because of what happened here and time can be
  wonky, some people may never know what you did here, and also that in order to preserve future
  events, she's wiping the memories of the whole thing from Sylvanas and Alleria's mind. At this
  point you can talk to both of them about theyre experiences past WOTLK expansion, find out their
  feelings on things in each expansion... actually make it sound natural and in universe... If
  they weren't present, make up something that was lore accurate potentially or doesn't conflict
  with the lore. Alleria should have a big of a part in this story as Sylvanas does. Then after
  talking with them or skipping it entirely if you want to you can tell moratari you're ready to
  return to your timeline. Similar to how legion remix does it, you can transfer your timerunner
  character to the ChromieCraft realm, and any items that weren't in the original (there really
  shouldn't be any) are converted to an equivalent weapon in wotlk stats. You will remain whatever
  level you were at the end of wrath remix, it will force you to close the game and restart the
  server, then when you log in it will have you in your factions capitol city."
  Order of work: the prologue and Chapter I (done, build 64), the report button (done, build 65),
  Chapter II and the turn, Chapters III and IV rewritten, V to VII, then the ending and the move to
  ChromieCraft.

- Report Time Anomaly (build 65), as planned above:
  - The game's menu, on Wrath Remix only (the realm's name as the game has it): **Report Time
    Anomaly** in a group of its own between Macros and Log Out (the menu taller to fit). A popup
    asks what went wrong (it may stay empty; the box takes the three-finger keyboard) and the
    add-on whispers "WowRemix<tab>report <text>" to the player itself; the realm keeps it from
    them (a PlayerScript chat hook, no core patch) and answers in the chat.
  - The journal (`remix_log`, with the area of each line now): logins, quests taken, objectives
    finished, quests turned in and dropped, creatures and quest objects spoken to (the same one
    again within a minute: one line), zones entered, levels, deaths (and to whom), Moratari's
    transitions. Not the realm's bots.
  - The report (`RemixReport.cpp`): the player's words; the Timerunner (race, class, side,
    chapter, where to the yard, alive or not, Bronze, quests turned in, time played); the quest
    they're on (the one taken last) with its objectives' counts, when, where and from whom it
    was taken, and who they've spoken to since; the rest of the log the same way; then the
    journal's last 300 steps. Markdown, the title on its first line, in `files/run/reports`
    (`WOW_REPORTS_DIR`, ctl.sh).
  - The app (`AnomalyReports`, run by the server's service every 3 seconds while it's up): an
    issue labeled *time anomaly* in this repository through GitHub's API with the modules token,
    then the token the app was built with; if neither may, github.com's new-issue page opens
    filled in (a long report shortened, oldest steps first); with no network, it waits and tries
    again a minute later. A toast says how it went; sent and opened reports are kept apart.
  - Checked here: the add-on against a stand-in for the game's menu and popups (only on Wrath
    Remix; the button's place and the menu's height; what's whispered), the report's fitting, its
    issue and its link; the realm built and run on the real data, the prologue played with a
    report asked for during Time's Toll, and its file read (the quest, who gave it, who was spoken
    to since, the way there). Not on a phone: the button's look in the menu, the popup and its
    keyboard, the issue on GitHub (neither the API nor the browser was used from here).

- Then: "Where are we percentage wise being done here" (about 30% of the whole plan, said), "How
  much until playable", and: "Focus on a build of the intro and chapters 1-2 first. Also you are
  supposed to have a shit ton of bronze by the end so you can use them at the vendors. Just like
  in legion remix."

- Build 65: the intro and Chapters I and II in the new story, Report Time Anomaly, and Bronze.
  - Bronze twenty times what it was: quests 500, kills 15, elites 60, rares 500, dungeon bosses
    1,000, raid bosses 5,000, Remix's own quests twenty times theirs (the prologue 8,500). The
    vendors' collectibles alone are about 539,000 (mounts 489,000, companions 37,400, toys 12,250)
    and each class's gear several hundred thousand more; the old rates earned about 40,000 from 10
    to 80. ctl.sh moves a realm config still at the old defaults to the new ones.
  - Chapter II as STORY.md has it: WRM-203/213 (to Chromie), Mystery of the Infinite, WRM-204
    (Sylvanas and Alleria appear beside Chromie), WRM-205/215 (to Eternus and back), WRM-201/211,
    then right before the Wrathgate's chain WRM-206/216 (Eternus's plan), WRM-207 (the turn: a
    scene of about 85 seconds at the Bazaar, its people summoned for the one Timerunner, the
    Bazaar's own Moratari hidden while it plays; one cut short counts as seen at the next login,
    or plays again when Eternus is spoken to), WRM-208/218 (six of Eternus's loyalists on the
    cliffs above the gate, where the Forsaken's catapults stand on the day; Audience With The
    Dragon Queen waits for it), and the Wrathgate, II-B and II-C rewritten. Who is seen where is
    visibility conditions (Eternus gone from the Bazaar after the turn, for those past it).
    Quests that take you somewhere as they're taken: `remix_departure`.
  - Checked here: see the build's entry below once it's tested.

- Then: "Make it so when you hold tap on an item or talent or ability it counts as a right click.
  Make it so if the item is on your action bar it removes it from the bar." Done: the add-on
  (WowTouch, version 3) also says where the bags, the bank, the character pane, the talents and
  the action bars are ("hold" areas in the same note as the spellbook's "drag"); a hold there, or
  on the spellbook, is a right click instead of B. On the spellbook the left button now waits for
  the finger to move (a tap is a click, a hold a right click, a move a drag). The action bars'
  buttons get no right-click action of their own ("type2"), and a right click picks their action up
  and lets it go: off the bar. Checked here: unit tests (the gestures, the note, the add-on
  against stand-ins for the bags, bars and buttons). Not on a phone: the feel of it, and the
  secure buttons in the real client (a taint report there would show as an "AddOn blocked" line).

- Then: "The bronze is not an issue. Ideally there should be more than enough from quest rewards,
  world events, random treasures, looting etc to get everything, every bit of armor and weapons
  and mounts etc. I should have enough leftover even 2x is fine ... Umm the timed dungeons I don't
  really care about. I should also be able to solo the dungeons without it being too crazy. Finish
  the story, look at it good and be sure it makes sense lore wise. And also put some real effort
  into the dialogue. And into the cutscenes and stuff the story of wow is where the real treasure
  is, not the gameplay. Let me know when it's ready to play."
  - The story to its end (STORY.md): Chapters III and IV rewritten after the turn (WRM-303/313 The
    Hollow Crown with Alleria at Ursoc's Den, Sylvanas at Drak'Tharon's door, WRM-405 One Life Too
    Many on Voltarus), Chapter V (WRM-501, 502 Stolen Hours: Eternus comes for Moratari, 521),
    Chapter VI (WRM-601, 602 A Gift of Grief: Eternus offers Thorim Sif, 621), Chapter VII
    (WRM-702 What He Was Offered, 701/711 before the Frozen Halls, 721 The Last Hour at Icecrown
    Citadel's doors, 800 the Lich King as the game has him), the end (the return to the Bazaar,
    Eternus's letter, WRM-801 See You Later with the goodbyes and fifteen conversations about what
    comes after this war, WRM-802 Back to Your Own Time). Read through against the game's lore
    (the ending's conversations corrected where they weren't: Vol'jin's death, Locus-Walker on
    Argus, Xal'atath's title, Sylvanas at the top of the citadel).
  - Scenes (`RemixScenes.cpp`, `remix_scene`, `tools/scenes.py`): 13 of them, 170 steps, their
    people summoned for the one Timerunner, walking, casting and speaking (reading time per line).
    The Last Hour is a fight of its own (`RemixLastHour.cpp`): Eternus, her loyalists in waves,
    Moratari, Sylvanas and Alleria beside the player; she retreats at a tenth.
  - Home (`RemixEnding.cpp`, `Homecomings.kt`): WRM-802 writes the character as a player dump and
    takes it out of Remix; the app sees the dump, closes the game and starts ChromieCraft, whose
    world server loads it into its capital. Bronze stays behind; every other item is Wrath's own.
  - Bronze for everything: quests 4,000 (the story's at least that), kills 75, elites 300, rares
    3,000, dungeon bosses 6,000, raid bosses 30,000, treasure chests 1,500, achievements 100 a
    point (world events' included). Every vendor ware a class can use costs 1.3 to 2.2 million
    (worked out from `npc_vendor` and `itemextendedcost_dbc`); the story from 10 to 80 at these
    rates comes to roughly 2.5 to 4 million (an estimate, not a measured playthrough). ctl.sh moves
    a config still at build 65's defaults to these.
  - Solo (`RemixSolo.cpp`, `WrathRemix.Solo.*`): an instance's share of a full group (bots count)
    scales the damage both ways; Icecrown Citadel's group-only mechanics are done for a player
    nearly alone. Timed dungeons: left out, as asked.
  - Checked here: see the build's entry once it's tested.
  - Disguises (the user's ask): away from the Bazaar, the Sylvanas and Alleria from the future are
    the Hooded Ranger and the Cloaked Ranger, *Adventurer* (entries 770014/770015, the scenes' and
    the last hour's too); the Sylvanas of this year never learns of the other, but for the Forge of
    Souls' moment. Moratari has no title over her head.
  - Moratari's Hourglass (item 77002, `RemixHourglass.cpp`): in the bags from the first login at the
    Bazaar; anywhere to the Bazaar, at the Bazaar back to where you came from; not in a fight.
    Pushed for a release at the user's request before a local full run of these two; CI's server
    test is their first run.
  - After the user's first play (build 83): the Bazaar made a camp (`tools/bazaar.py`,
    `0023_bazaar.sql`: the market lane, training yard, waystation, chronicle corner and campfire,
    59 props, 25 new people, 48 lines spoken while a player is near, `RemixBazaar.cpp`), everyone in
    a mortal shape (no dragon models in the Bazaar; the broken Time's Toll was Alurmi hidden among
    overlapping drakes); the guide (`RemixGuide.cpp`: map flag and *Next:* line for every quest);
    all riding from the first login and every Bronze ware without requirements (`0022`); saves every
    30 seconds and the server kept running in the background (build 84, the hotfix). The story's
    timeline as the user set it: the Timerunner from the eve of the Broken Shore, Sylvanas and
    Alleria from the days of Midnight; the prologue, Wyrmrest, the last hour and the end rewritten
    for it (Moratari takes the Timerunner's knowledge of their own future at the end).
    Checked here: compiled; the Remix realm starts and applies every changed file; the prologue
    plays through Time's Toll and A Small Demonstration at the new positions (the vendors' part
    after it, and the campaign, are CI's server test, alongside the release). Found while testing
    (build 86): a Temporal Anomaly whose Timerunner left mid-demonstration stayed at the Bazaar and
    attacked everyone who arrived, never leaving combat; now each anomaly fights only its own
    Timerunner and goes when they do (checked: one left mid-fight, a newcomer wasn't attacked).

- Then (screenshots: modules build saewd4jpdxxu for wow-build-87 failed; mod-no-hearthstone-cooldown,
  mod-arac, mod-worgoblin, mod-transmog): "look into why it failed, make it compatible". All four
  compiled; the server test's world server crashed (segfault) while the random bots were made.
  mod-worgoblin's SQL makes races 9 and 12 playable (playercreateinfo, chrraces_dbc) but the
  server's CharSections.dbc has no faces or hair for them, and playerbots indexes those empty lists.
  Both modules' READMEs have their DBC files copied over the server's and a patch put in the game,
  which the build didn't do. Done:
  - `patches/mod-playerbots/0001`: random bots only of races with faces and hair for both sexes
    (and an empty list never indexed).
  - `scripts/module-files.py` (package step, picked modules only): every .dbc a module has goes to
    `share/dbc`; the same file from two modules is put together by record id (mod-arac's and
    mod-worgoblin's CharBaseInfo, CharStartOutfit and SkillRaceClassInfo: no field conflicts but
    SkillRaceClassInfo's race masks, OR'd); a file with text from two modules takes the later one's
    and says so. Patch folders (`patch`, `patch-*.MPQ` with the game's folders) go to `share/client`
    with those DBC files in its DBFilesClient; mod-arac's Patch-A.MPQ holds only its three DBC
    files (read from its tables), so MPQs aren't opened. `share/client.stamp` names the content.
  - `ctl.sh modules` (each start, per realm): `files/data-modules` (links to the data, the modules'
    DBC files in place of the game's of the same name, any case) and DataDir pointed there, or back
    at `files/data`; the patch into the client's `Data/patch-M.MPQ` (hard links where possible,
    copied again only when the stamp changes; a patch-M of the user's own left alone), taken out by
    a realm without one. The game starts with `WowPatched.exe` while it's there (`ClientMods`).
  - Known limits: mod-worgoblin's own `skillraceclassinfo_dbc` SQL rows override three of
    mod-arac's wider masks on the server (guns and maces for some ARAC combinations: 133, 624,
    625); HD looks (letters after M) win over the modules' files they share; Turtle's SafeFs
    protects `data-modules`.
  - Checked here: the merge on the four modules' real files (30 DBC files, 6484 game files, 312 MB),
    ctl.sh modules with dash on a stand-in files folder (links, case matching, the patch in, kept,
    out), unit tests (205). Not built here: the APK and the server (CI); not checked: the game on
    a phone with the modules' patch.

- Then: the modules realm check (fixcheck90, wow-build-90, the user's four modules): the world
  server started, made its bots and took a character in; the run then stopped at Wrath Remix's
  prologue (the hourglass's way back), red on every build since 86 from the Remix opening's work on
  ccr-d466cf9d-qtiixw, which that session is still changing. Until it's green, no module realm passes
  its test, so none is published.

- Then: "research the Skyborne from WoW: Forever, make a custom module that adds them": blood elf
  models and textures with blue skins and new hair colours; the Alliance's start in Northshire with the
  human questlines, the Horde's at Camp Narache; the user's MP3 as the intro's narration; the blood
  elf's portraits with blue skin and white hair; a Dark Ranger skin (grey, red eyes) for blood elves
  only; eyes yellow or blue (not green or red); then "look up the actual stats and racials and use
  those". `warcraft/modules/mod-skyborne` (its README):
  - Races 13 (High Order, Alliance) and 14 (Windshapers, Horde) on the blood elf's model; the game's
    Naga and Broken moved to 22 and 23 (their creature looks too). Ids up to 21 because the old custom
    races used up to 21 (and the client is thought to need playable races there); a phone's old High
    Elf (13) and Mag'har (14) characters show again as Skyborne of the same side.
  - `tools/build_data.py` (run here on the server data's DBC files and the core's world SQL, output
    committed): the world database's `*_dbc` rows (ChrRaces, CharSections, CharStartOutfit,
    SkillRaceClassInfo and SkillLineAbility masks, Faction standing like the start race, the intro's
    camera and sound, the racials' Spell rows), starts, action bars, skills, racials, stats (WoW:
    Forever's class starting stats are the class's with Str -1 Agi +1 Sta -1 Int +1), forms
    (night elf), totems (tauren), quests/items/conditions of the start race (and the Night Elves'/
    Blood Elves' for classes the start race hasn't); and `data/client`: the rows to change in the
    game's tables and `textures.txt` (1407 textures: six light blue skins, nine hair colours from the
    blood elf's silver (three pinks), every face with blue and with yellow eyes, the Dark Ranger skin and faces).
  - Racials as of WoW: Forever's 15 October beta (Wowhead was unreadable here; Warcraft Tavern,
    lfcarry, BoostRoom, Blizzard's article): Walk on Air 10 s glide, 90 s; Skysight 0.5 s cast while
    moving, Elemental Blessing +10% run and mounted speed (not stacking), 30 s, 2 min; Read Ley Line
    2 s cast while moving, Energized +100% Health and Mana regen, 30 s, 2 min; Wind Blessed +3% haste;
    Elemental Insight +5% vs Elementals. The 15-minute versions (beside a ley line or convergence)
    aren't made: Northrend hasn't those.
  - The game's patch (`SkybornePatch.cpp`, at each world server start, only when the client or the
    module changed): Data/patch-Z.MPQ from the client's own archives (its MPQ files and folder
    patches, in the game's order, HD looks included): tables with the rows, textures recoloured in
    their own format (palettes, DXT end colours), the race icons (column 7: the blood elf's, blue,
    white-haired, blue-eyed, by hand-drawn face masks), GlueXML.toc with SkyborneCreate.xml/.lua
    (six more race buttons, each side's column, backgrounds Human/Tauren, texts), the narration.
    LegacyRaces leaves it alone; the app starts WowPatched.exe while it's in.
  - Checked here: the recolouring tuned by eye on ChromieCraft's HD character textures (downloaded
    only for that); `tools/test_client.cpp` (patch made for a stand-in game: stock tables, the HD
    archive, a race icon sheet; read back; second start keeps it); `tools/test_screen.py` (the Lua in
    Lua 5.1, ten and twelve native buttons); the SQL in MariaDB 10.11, twice; unit tests. CI: the
    world server makes the patch from the server's tables, a High Order warrior starts in Northshire
    and a Windshaper shaman at Camp Narache with their racials. Not checked: the game itself (the
    screen, the looks, the intro on a phone).

- Then the user's answers: Skyborne HD only when the HD look is on (as built: the patch is made from
  the client's files, an HD look's included); "Eluna into the realm"; the two buffs always 15 minutes;
  the MP3 kept, named `intro.mp3`. Done: mod-ale (azerothcore/mod-ale, pinned; azerothcore/eluna is
  its documentation, mod-eluna redirects to it) in `server-version.env`; its extensions folder from
  the install to `files/server/lua_scripts` (compile and package), ctl.sh points `ALE.ScriptPath` there
  for both realms; a picked module's `.lua` files go to `lua_scripts/<module>` (not those of the game's
  add-ons it brings). ALE's README: not compatible with the original Eluna's scripts. Elemental
  Blessing and Energized last 15 minutes (SpellDuration 347). Not checked: ALE compiled with the
  playerbots core (CI), a Lua module run on it.

- Then (builds 106 to 111): "finish Wrath Remix", "remove all bugs", every branch's work in one
  build (the tavern opening included: 106, 109 and 110 have it, for characters made from then on;
  the user: no replay for older characters), and Moratari's own voice. Done:
  - The bug hunt in five parts (scenes and the ending; the last hour, the Frozen Halls and the
    hourglass; the companion, outings and the romance; the intro, guide, lessons and Bazaar; the
    app's anomaly reports and homecomings), each fix in the module's README.
  - Creatures at the Timerunner's level, as in Legion Remix (`WrathRemix.LevelBands.FollowPlayer`):
    the nearest real player's out in the banded zones, the highest's in Wrath's dungeons, bosses up
    to 2 or 3 above, never above their own; re-levelled out of combat every 1.5 s within 100 yards.
    Kill Bronze to whoever the game rewards (a companion's kills too); dailies a fifth, repeatables
    a tenth; the heal hooks' units put right (the core passes a cast heal's swapped).
  - Moratari's voice (the user's answers: greetings yes, whispered when she isn't near, the lines
    that could fit used once each, the farewell as judged best, size no object): 41 of her Legion
    Remix lines (`tools/voices.py`, `remix_voice`), fetched by file id from wago.tools and made WAVs
    as the server is packaged (never in the repository), put in patch-Y with SoundEntries 77001+.
    Eternus's answer to "How is this to be done?" now says the experiment can't last (her line 32
    refers to it).
  - Checked here: compiled; the realm starts, 41 of 41 lines voiced; the patch holds the WAVs and
    the 41 SoundEntries rows; the unit tests (209). The prologue and the Alliance campaign were
    under way locally against this build, passing so far, when it was pushed (the user's time);
    CI's server test runs them all. Not checked: her voice in the game itself, the new levels in a
    real fight on a phone.
- Then (build 116): "do them, prioritizing the character creation".
  - Eternus and Zephyr are women (the user's decision): every he/him for them to she/her in
    STORY.md, the SQL texts, scenes, code, READMEs and the tests' comments (285 words in 29 files;
    WRM-205/215 is now *Ask Her Yourself*; "master" became "mistress" where it named Eternus).
    Zephyr's look, 19282, was a blood elf woman already; Moratari's voiced lines already said "her".
  - Eternus in her own shape (the user: "must remain in dragon form", "no troll anywhere"):
    `tools/models.py` makes the newer games' model (display 106055) one 3.3.5 draws, the package
    fetches it (`data/models`, never in the repository), `ClientPatch.cpp` and `RemixModels.cpp` put
    it in the game's patch and on her (0.4 at the Bazaar and in her scenes, 0.5 at the last hour,
    no staff), `0029_eternus_shape` the realm's rows. Found while converting and fixed: eleven
    animations with ids newer than 3.3.5's AnimationData (aliases: given ids no creature plays),
    the animation lookup a hash in the newer format (made a table by id, which reads the same
    either way), a texture animation lookup naming one that isn't there. Checked here: a structural
    check of the converted model (every array in bounds, 105 sequences and 14,220 animation blocks
    against the model and its .anim files, every skin section within 64 bones and every vertex's
    bones matching its palette, batches, materials (blend modes 0 to 6), lookups), the textures
    (DXT1/DXT5 with all mip levels), the patch with her rows read back, the realm starting with her
    look. Not checked: the game drawing her (no client here); her size and place at the Bazaar are
    judged from the game's own dragon of her size.
  - The server test: a creature called over that's still out of reach (as the Cloaked Ranger at
    the Wrathgate's cliffs in build 109's run, back from chasing an interloper) is called again and
    waited for.
- Then (build 116, the user's play of 111): "the tavern scene needs to have us at a table, and so
  that I can't move"; "it spawns me in Crystalsong then immediately loads another area", "it lags
  when loading: preload everything, just have me spawn in the tavern"; "Moratari still uses the
  default lines for blood elf". Done:
  - A new Timerunner is made in their side's tavern, in their chair (playercreateinfo set as the
    realm starts, as for the Bazaar before; at the Bazaar still when the opening is off): one
    loading screen, the tavern's. Seated at the table with their three friends, their movement
    taken from them (the game's control of them) until Moratari takes them from it; everyone up
    for the two toasts and sitting again; no gestures from those sitting (one would bring them to
    their feet). The Gilded Rose's own table, its four chairs set out again in the opening's phase
    (the game's are in everyone's, not seen in it); the Broken Tusk has no table in the open: an
    orc table and four log chairs before the innkeeper. A skip before it begins (CI's) goes to the
    Bazaar, the hearthstone with it.
  - Everywhere the opening goes and the Bazaar loaded as the realm starts (the core keeps a grid it
    has loaded): about half a second here.
  - Moratari's look has no stock voice (0026: NPCSounds 0), so clicking her is her own greeting.
  - Found in build 111's CI: wago.tools turns away Python's own user agent (403 for all 41 lines:
    the APK had none of her voice); both fetches now name the build. The prologue's squish check
    counted a bot's pet (79% for 75%): pets, guardians and player-controlled ones left out.
  - Checked here: compiled; the realm starting with all of it (Eternus's look in the game's patch,
    her rows and twelve files read back from it the same, her parts in it; 62 starts in the
    taverns; the places loaded in 0.2 to 0.5 s; 41 of 41 of Moratari's lines fetched with the new
    user agent); the prologue for an Alliance Timerunner made in the Gilded Rose (seated there, then
    everything to Valiance Keep); a Horde one's opening (seated at the Broken Tusk's set-out table,
    then the Front, then a skip to the Bazaar). Not checked: the game drawing any of it (the seats,
    the table, her dragon), and the Horde campaign's goodbyes: in a local run of 111, Sylvanas
    wasn't at the Bazaar for WRM-801 (not traced yet; CI's run will say whether it's this build's).
- Then (build 117): "the infinite bazaar doesn't feel magical... have the same NPCs from the legion
  remix" (the user's answers: working roles, Wrath versions; keep all of Wrath's); then "move it into
  an icecrown area... Let's use the unfinished area malykriss. So rewrite the quests or whatever and
  the hourglass. ... alive and magical but also scourge themed and include death knights"; "a custom
  set of death knight armor found in a location. It's never mentioned but displayed in a chest. ...
  a short and easy questline ... like an heirloom that scales ... even if they aren't a death knight";
  "living Nerubians ... an independent faction who has aligned with the cause ... a very personal
  reason to hate Arthas". Decided here (no questions asked):
  - The site: Malykriss's crater floor (area 4520), level at 508.6 under the unfinished necropolis's
    bare ribs (Icecrown_Necropolis_Deathstar01). Seen by rendering the server's own collision data
    (terrain, WMOs, M2s) and the navmesh, as no client here draws it; the old camp fitted onto it
    whole by a search over shifts and turns (x + 694, y + 854, no turn: every one of its 144 spots on
    level floor with 6 yards clear), then the realm's `.remix ground` survey: all 508.6.
  - Malykriss's Scourge garrison taken out of Remix's world, and the Shadow Vault's twelve quests
    that need it off (`disables`); the Nerubians as a faction in name (their subname), friendly to
    all, not a reputation (that would need the client's Faction tables).
  - Done (`tools/malykriss.py`, `0030_malykriss`, `src/RemixMalykriss.cpp`): the Ebon Blade's gate,
    quarter, Runeworks and walkways; the nerubians' hall by the Blood Forge, their wardens and
    skyguards; the time-magic at the hourglass (sands, beams, rift, frost spheres); the camp's props
    rethemed (Ebon Blade banners, Scourge braziers, a rune circle and light at the hourglass, a
    working runeforge). The start, homebind, hourglass, travel hub, scenes' spots, the romance's
    tent, the prologue's text and the tests all moved with the camp.
  - The rest of Legion Remix's cast, Wrath versions (looks from the game's infinite dragonflight,
    each with their wares, a stall and their lines from the wiki): a north row of apparel sellers
    (Pythagorus, Sacerdormu, Agos, Unicus, Larah, Freddie), Nedward and his barber's chair, Hemet
    XVII's mounts at the waystation, Jakkus and Momentus (Weaponmaster now) at the training yard,
    Nostwin and Erus by the campfire, Metros at the heart, Lidamorrutu and Lindormi in the
    chronicle corner. The instructors' spawns had stayed in Crystalsong: moved.
  - Checked here: compiled; the realm started with all of it; every Malykriss resident where it
    should be (`.remix guid`, the skyguards and frost spheres moving); the prologue played through
    for an Alliance Timerunner (65 checks: woken in Malykriss, the hourglass there and back, Hemet's
    mounts, Pythagorus's tier set). Not checked: the game drawing any of it (no client here: the
    props' sizes, the beams, the barber's window away from a barbershop).
  - CI's run 120 (before the move) played both campaigns through for the first time since build 108
    and failed only at ChromieCraft taking the Timerunners in: the test asked before its queued
    writes landed (now it waits).
  - The hidden death knight set (`0031_vile_hold_set`): nine heirloom pieces any class wears, in a Scourge
    coffer on the south terrace, WRM-061 to 063. Checked here on the realm: the coffer offers WRM-061, the
    three runes each give its credit, the three quests follow at the coffer, and a mage ends with Plate of
    the Unrisen (all nine pieces in it); the floor there reads 525.2 as placed. Not checked: the stats on
    a character (the agent's table is from the DBCs, as the core computes them), the tooltips, the look.
    Kept out of the guide (never pointed to: it's to be found). Found while adding it: 0002 and 0010,
    re-applied, cleared other files' NPC texts and creature templates; each now clears only its own.
  - Then: "Just have moratari mention that the hero may remember this area but when they started
    cleaning up the timeline they took care of the scourge infestation here. What this means is that
    the quests never even become available and it's entirely ours." Done: in Out of Time's text as
    the Timerunner wakes, and a line of hers now and then at the Bazaar; the hidden plate's tally and
    STORY.md say the same.
  - Then: "one of the moratari romance quests crashed the game. The one where she teleports you to
    the inn after she reveals her new dress" (Dinner at Krasus' Landing). Found: her dress look (and
    her tent's) has no baked texture, so the game puts it together from CharSections, and her skin
    (blood elf woman's 15, an NPC's: face painted in) has no face or overlay rows there; the game
    always bakes such NPCs. Fixed in the Skyborne's patch (patch-Z, the one with CharSections): every
    NPC skin gets empty face and overlay rows (skyborne-7). The likeliest cause, not proven: no game
    here to crash. Checked here: the patch made from the server's tables (CI's stand-in game) has
    them (her skin's face 2 and overlay rows; 74 NPC skins, none missing a face), and the players'
    skins and the death knights' faces as they were.
  - Then: "Make alleria when you talk to her sort of tsundre. Like keep her lore friendly but also
    she hints that she cares deeply for the player but never outright says it. She tries to keep
    her distance but she knows that you are very verit intimate with her platonically like a brother
    or sister. She eventually reveals this and says she'll see you in the future." Done: where she
    comes from the Timerunner is family (Argus, the Void's roads since); here she keeps them at arm's
    length, crossly, and gives herself away (they haven't been sleeping, should eat, went in ahead of
    her again; every kindness with an excuse; slips into the future tense and takes it back). Her
    words at the Bazaar warm as the war goes on (after the turn, from Chapter V, from Chapter VII,
    after the war: npc_remix_windrunner), and she has her own in the Grizzly Hills (she showed the
    Wrathgate's there before). Before the Citadel: "Ask me after." After the war, a ninth topic
    ("Why do you keep looking out for me?"), and her goodbye says it: family, her brother or sister
    in every way that counts; Turalyon says she treats them as she treated Lirath; "I will see you
    in the future, $n. Try not to be late. You always are." Moratari's scene echoes it before the
    memories go. Also in her quest texts, the Hollow Crown and Ursoc scenes, Thorim's, the Frozen
    Halls', the last hour and the return; Sylvanas's "someone I trust" stays hers. STORY.md tells it.
- Then: "whatever is left in this wrath remix, finish it. And then polish ... First and foremost
  change how the initial new player happens. Because you spawn in and the world loads around you
  but it's very choppy and laggy. Don't stop until it's done make a list of things you aren't sure
  about and we'll do them later."
  - The opening opens from the dark. Found: the choppiness is the game loading Stormwind or
    Orgrimmar (and each act's place after) the moment its loading screen ends, while the tavern
    scene already ran; nothing server-side makes a phone draw a city faster. Done: the app's add-on
    holds a curtain (black at once, with where and when the act is on it: "The Gilded Rose,
    Stormwind / Some months after the fall of the Lich King") from the realm's word at login
    (before the first loading screen ends) or before each act's teleport, watches its frames and
    tells the realm "settled" once they have run smoothly for a second and a half (two seconds in
    at least); the realm lifts the curtain over 2.5 s and the act begins (`WrathRemix.Intro.
    SettleSeconds`, 25, the longest wait; never under 1.5 s so the act's people are in place). The
    tavern's talk starts once the room is up. The Bazaar at the end the same, Moratari's welcome
    after the lift. CI's test client answers a curtain at once (it has no frames).
  - Tested locally (the realm built, a new Timerunner logged in by the test client): at first every
    act waited the whole 25 s. Found: the core's chat packet builder marks a game master's own words
    as GM chat (`SMSG_GM_MESSAGECHAT`), the realm's whispers to the player themself among them, and
    the test account is a game master, so its client never saw a curtain and never answered. Fixed
    both ways: the realm builds the add-on's whispers by guid (plain chat, whoever the player is;
    the app can make the phone's account a game master too), and the test client reads both
    packets. Verified: three curtains at a new login (the login's, the way to the tavern's, the
    arrival's), each answered; the curtain lifts 4.3 s after the login and the talk starts 2.5 s
    later; `.remix intro status` now counts curtains and settles ("curtain 3 (1.6 s ago), settled 3
    (1.6 s ago)") so a phone that seems to wait the whole SettleSeconds can be read. The prologue
    script's seated check moved to 30 s (the toast has them standing from +9 to +16 s).
  - See "Not sure" below for what the phone will show.
- Open (the user's):
  0. **The next fix** (the user: "Put this on the next fix"): the future Sylvanas, Alleria and
     Chromie in their retail looks and voices. The user's links: Lady Sylvanas Windrunner (10181),
     Alleria Windrunner (223944), Chromie (167032); "Chromie brings them back in time"; "use their
     voice lines at least for greeting and idle stuff and attacks". Found (wago.tools; nothing
     converted or wired in yet):
     - Sylvanas: 10181's display 28213 is now `creature/sylvanas/sylvanas.m2` (1245874); its bones,
       sequences and attachments are in a separate skeleton (.skel 3476709, with its own AFID);
       skin 1246126, four textures (1246113 to 1246116), geoset 0 only.
     - Alleria: 223944's display 118072 (scale 1.3) is model 5395590: nine textures, 54 animations
       in their own files (AFID), BFID and PFDC chunks (left out), geoset 0 only.
     - Chromie: `creature/chromie/chromie.m2` (4218359), the look of every Chromie display now
       (24877, 10008, 96559...): two textures, 54 animation files, geosets 0, 401, 2201, 3301.
     - Voices (sound kits): Sylvanas NPCSounds 1349 (greeting 95141, farewell 95140, annoyed
       95142; 28213's own, 175, is the old greetings), sounds 2898 (exertions 16319 and 101101,
       injuries 16317 and 16318, death 16320, alert 123438, battle shout 123437) and 8759 (aggro
       248024, injury 248025, idle 248026 to 248029); Alleria NPCSounds 3666 (327366, 327367,
       290382), sounds 8858 (injury 258823, battle shout 258825, idle 258822, 258824, 258826,
       258827); Chromie NPCSounds 1897 (139316 to 139318), sounds 4735 (exertions 43853 and
       43854, injuries 43856 and 43857, death 43855).
     - To do: `tools/models.py` for several models (each batch with its own texture: Eternus's
       draws everything with her one skin; Sylvanas's skeleton merged into her model); the sound
       kits' files fetched as WAVs (as `tools/voices.py` does) with SoundEntries, NPCSounds and
       CreatureSoundData rows in patch-Y; new CreatureModelData and CreatureDisplayInfo rows
       (`ClientPatch.cpp`) and the looks on the future pair (`RemixModels.cpp`), the package
       fetching it all; a future Chromie in her retail look who brings them at Wyrmrest (Strangers
       Out of Time) and takes them home in Back to Your Own Time, the game's own Chromie keeping
       hers. To ask: whether the hooded "Cloaked Ranger" versions away from the Bazaar keep their
       hoods (the story's disguise) or take the new looks too.
  1. Character creation. The gear button (no gear, starting gear and champion gear on the
     creation screen's preview, PR #17 and after) is taken out, at the user's word: 3.3.5's preview
     is a ModelFFX that no interface code can dress (it has no `Undress` or `TryOn`), so the button
     only ever cycled its icon there, and dressing it would have taken a change to the game's
     executable, which isn't followed. Kept from that work: each side's races in two columns, two
     to a row (`SkyborneCreate.lua` and `AlliedRacesCreate.lua`, the side from the race where the
     game gives none); the nightborne in the Night Elves' scene (each race's scene has a camera
     framed for that race's height: the Forsaken's, for the stooped undead, cut off the
     night-elf-sized nightborne; their meshes measure as the night elves', 2.55 and 2.36).
     Then: "Also on this menu make it
     so I can pinch to zoom in or out." Done: the pinch is already the mouse wheel; the Skyborne's
     patch now makes the wheel zoom the creation screen's preview (`SkyborneZoom.lua`: its model
     frame grown around the character, up to 2.5 times, back to the whole screen; the panels kept
     where they are). Checked here: test_zoom.py (10 checks). Not checked: the game (that the
     ModelFFX draws its scene to its larger rectangle, as model frames do).
- Open offers (not started): (1) when a realm with modules fails its test, test again without each
  picked module in turn, to name the one that crashed and still build with the rest; (2) the
  user's own modules and quests in this repository, offered in the Modules screen.

- **Turtle WoW — Vanilla+**, a second game with its own Tortoise-WoW realm, kept apart from this
  one (released in `wow-build-65`): its brief is `TURTLE.md`.

- Then: "Look up how you took Sylvanas and Alleria and put them into the game. Do the same thing by
  adding void elves ... Also for nightborne ... Combine face and eye colors ... Use the voice lines
  used by both races ... Make the void elves start in the draenei area with draenei quests. Clone
  the quests specific to dreini but have them acknowledge you are a void elf but then help you
  integrate to the storyline. But don't mess up regular draenei quests. Make nightbourne start in
  the undead area and do the same ... Do it as a new module ... I'm using the HD patches. It should
  only add to chromie craft and wrath remix not turtle ... No compromises don't use the classic sd
  models. Don't use blood elves as a base I want the races ported exactly as is." The user sent the
  two intro narrations (MP3s) and asked for the character creation portraits from Wowhead.
  `warcraft/modules/mod-allied-races` (its README):
  - (Sylvanas and Alleria aren't playable: they're Wrath Remix's people, and their retail looks were
    only found, not wired in; the precedents were the Skyborne's playable races and Eternus's model
    conversion.) Races 15 (Void Elf, Alliance) and 16 (Nightborne, Horde); the game's Skeleton and
    Vrykul moved to 24 and 25.
  - Found first (wago.tools, and ChromieCraft's HD character archive read as the reference of what
    the 3.3.5 client takes): today's player models are chunked format 274 with a child skeleton over
    a parent's (the blood elf's, the night elf's), the body texture 1024 x 512 with the head in its
    right half and the eyes a texture of their own; the HD look's models are format 264 with 512
    palettized skins, faces 256 x 128 + 256 x 64, sequences past 3.3.5's ids kept, the sequence
    lookup a hash table, geoset groups 3.3.5 doesn't know shown as they are. `tools/m2.py` converts
    on that (merged skeleton, every track an entry a sequence, coordinates remapped: body stretched,
    head into the face region, eyes into the FaceUpper strip; geosets pruned to the chosen variant
    of the groups 3.3.5 can't switch; 64-bone sections); `tools/textures.py` bakes the textures
    (palettized skins and faces, as the HD look's). All four models pass `tools/check_m2.py`; the
    HD blood elf read the same way passes but for what the HD look itself leaves out.
  - The looks: every skin, face x eye colour (void elves' nine eye colours: 3.3.5's face byte holds
    255, and 28 faces x 10 wouldn't fit; the tenth, a late addition, is left), hair style (with the
    tentacles as styles of their own) and colour, the feature slider as the README says; options
    with no slider at their first choice. Faces differ by texture alone (today's bone files aren't
    3.3.5's).
  - The racials as today's where 3.3.5 allows (Spatial Rift as a two-step spell script; Entropic
    Embrace a 10% proc with a 30 s rest; Ethereal Connection has no transmogrification to act on).
  - The quests: the Draenei's seventeen own quests of Ammen Vale, Azuremyst and Bloodmyst and the
    Forsaken's five of Deathknell and the ride to the Undercity cloned (ids +950000) for the new race
    with the givers' acknowledgments (`tools/quests.py`); the rest opened by race bit. Scripted
    events bound to an original's id don't fire for its clone.
  - The patch is `patch-V.MPQ` (W is the alternative HD look's water); Wrath Remix's `ClientPatch`
    now takes CreatureDisplayInfo, CreatureDisplayInfoExtra and CreatureModelData from the client
    (with this patch in) rather than the server's dbc folder, so its patch-Y carries the races' looks
    and models on that realm (and an HD look's creature tables, which it used to put the stock ones
    over). The user's first try (1.0.148): the races didn't show at all. Found: the patch was made
    at the startup hook, after Remix's (the custom tables' load) and the Skyborne's (the startup,
    registered first), so on a first start patch-Y and patch-Z, read after it, carried ChrRaces,
    GlueXML.toc and the display tables without the races; the next start would have had them.
    Now made at the configuration's load, before both (the realm test checks the order).
    Second try (the user's screenshots): the races on the screen, the models drawn in pieces
    (legs whole, the rest spikes). Found: `m2.py` built each section's bone combo from the retail
    skin's bone *slots* (relative to the retail section's combo) instead of the vertices' bone
    indices, so the game, skinning through the combo, put every vertex on another bone. The HD
    look's blood elf and the retail file agree on the rule (combo[slot] is the vertex's own bone);
    `check_m2.py` now checks it (the old models fail, 85 sections). Patch version 2, so phones
    remake patch-V (the files' sizes, which the stamp holds, don't change).
  - `tools/render_m2.py` (new): a converted model drawn as the game reads it (sections through
    their combos, posed on Stand, the composed texture, the model's cameras). It reproduces the
    pieces on the old models and a whole figure on the new, and showed the next fault: the upper
    arms missing. Today's models keep the shoulders and upper arms in geoset 0 but the head,
    mouth, eyes, eyebrows, toes, torso and forearms in groups 20-34, which 3.3.5 doesn't know
    (its defaults go up to group 18; the HD look's blood elf keeps its whole body in geoset 0).
    Those are now folded into geoset 0; the renderer shows only what 3.3.5 shows.
  - Third try (1.0.174): bodies whole, but the hair, wrists, thighs and eye glow in pieces. Found:
    3.3.5 reads a mesh section's triangle start as 16 bits (the modern format's `level` high word
    is lost on it), so every section past the first 65535 indices drew from the wrong triangles:
    exactly the sections the screenshots broke (the nightborne male's body, hair, hands and boots
    came before the limit and were right; sleeves, eye glow, thighs after it and weren't). The HD
    look's blood elf has 64,521 indices with 20 hair styles; ours had 95,000 to 140,000, and
    Blizzard's own LOD3 only takes a quarter off. `tools/decimate.py` cuts the meshes to the
    budget (quadric-error half-edge collapses; gear first, then features and hair, body last);
    the void tentacles moved from duplicated hair styles to the feature slider (a third of the
    void elves' triangles). The renderer now reads starts as 16 bits too, and `check_m2.py`
    refuses a section past the limit.
  - The Lightforged Draenei (race 17, Alliance, Ammen Vale) and the Zandalari Trolls (race 18,
    Horde, Camp Narache in Mulgore), the same way: ChrModels 59/60 and 61/62; the Lightforged on
    the HD draenei's parent skeleton (1005887, 1022598), the Zandalari with their own; each
    within 65535 indices (Lightforged gear at about 46%, Zandalari hair 91% and 100%), whole in
    the renderer. Tuskarr and Forest Troll (17, 18) moved to 26 and 27. Racials: Light's
    Judgment (an unseen creature of the player's faction and level strikes after 3 s), Forge of
    Light, Light's Reckoning (the same on death: `OnPlayerJustDied`), Holy Resistance, Demonbane
    (`OnPlayerGiveXP`); Regeneratin' (a periodic heal ended by damage), Embrace of Pa'ku (a proc
    like Entropic Embrace), Pterrordax Swoop (Slow Fall renamed), City of Gold
    (`OnPlayerBeforeLootMoney`). Not here: Bwonsamdi's Reach, the Zandalari's flight, Ethereal
    Connection. The Lightforged's skins have no underwear of their own: the first jewelry
    colour's pelvis and torso stand in. Quests: the Lightforged take the void elves' seventeen,
    the Zandalari The Hunt Begins, the Cleansing chain, Journey to the Crossroads and the ride to
    Thunder Bluff, each with a greeting of its own. **A bug of the first two races found on the
    way**: quests that need a cloned quest done first (the Draenei's class training, the
    Tauren's Hunt Continues...) stayed locked for the clone's race, which can't complete the
    original; `quests.py` now clones them too, chained to the clones. Character creation: two
    races to a row per side (both modules' layout code and tests), the new icons in column 5.
    Checked here: the converter, the checker and the renderer on all eight models,
    `test_patch.cpp` on a stand-in game (19 tables, 5,215 files, 0 failures), the tools' and
    screens' tests; the server compile and realm test are CI's (the new spell scripts and player
    hooks compile there first).
  - A review of all four (the user: the intro MP3 never played under the earlier attempt; look for
    bugs in the questing, the models' size and presentation; make sure the racials work):
    - The narrations: the tables match the game's own (CinematicCamera's SoundID, a SoundEntries
      row of type 31 under `Sound\CinematicVoices`); the files didn't: the user's MP3s were
      variable bit rate with ID3v2.4 tags and Xing headers, the game's are constant bit rate
      (128-160 kbit/s, 44.1 kHz) with neither. All five (the Skyborne's too) re-encoded to constant
      160 kbit/s 44.1 kHz with no tags or VBR header; the voices `package.py` makes likewise
      (mono, 64 kbit/s, 44.1 kHz; they were 22 kHz MPEG-2). Whether that was what stopped them is
      the phone's to show.
    - Quests: scripts and conditions bound to the originals' ids did nothing for the clones (the
      Ending Their World escort and its credit, Legoso's run, gossip conditions): `quests.py`'s
      bindings copy smart_scripts rows on accepting or handing in a quest and the credit they
      give, and add each condition's clone (an OR group for "is on it" or "has done it", the same
      group for "hasn't"). Rescue the Survivors! needs Gift of the Naaru, which void elves and
      Lightforged don't have: lent while the clone is in the log (`AlliedRacesSpells.cpp`).
    - Racials: Preternatural Calm had the health regeneration aura (88): now REDUCE_PUSHBACK (149).
      Light's Judgment and Light's Reckoning were cast by unseen creatures, so their kills gave no
      loot, experience or credit: now the player's own casts (the strike a destination spell, the
      burst a triggered cast the core allows the dead).
    - Models: display scales 1.0, as retail's. The nightborne were cut off at the head on the
      creation screen, framed by the Forsaken's scene's camera: they take the night elves' scene.
    - New in the realm test: each race in the world (`warcraft/server/allied_*.steps`): Calm's
      aura, Spatial Rift through the rift, Arcane Pulse's, Reckoning's and Judgment's damage as the
      character's, Regeneratin' and the swoop's aura, the race's own first quest offered and the
      start race's not, Rescue the Survivors! credited with the lent gift, handed in, and the gift taken back.
      Not tested: Entropic Embrace's and Pa'ku's procs, Demonbane's and City of Gold's amounts.
    - Found by that test (build 231): a void elf's chat commands went unanswered (no language).
      Both modules add their races' bits to the same playercreateinfo_skills rows and found them by
      the stock masks exactly, so whichever ran second changed nothing; both find them by the stock
      bits now. A character made earlier learns its start skills at its next login (the core does that at
      every login). And build 229's world SQL
      didn't import (an unquoted apostrophe in Light's Judgment's note): fixed in 1e755c4. The SQL
      is now checked here on MariaDB over the core's stock tables (both modules' in the realm's
      order, then again).
    - Still no Common in build 233: both modules' generators also put their races in the other
      side's language row of SkillRaceClassInfo (the Horde's Common row, the Alliance's Orcish:
      tiered rows for learning the other side's language, rank 0 at creation). The core keeps a
      skill's rows in an unordered multimap, where the later row comes first, so these races got
      that row and learned nothing; the Skyborne had the same. Each side's language now goes on its
      own row only, the bits earlier releases left on the other row are cleared, and the realm
      test checks the language for all six.
    - Build 234: the Skyborne still had none. The test's report (the realm's rows, the character's
      skills beside a human's) showed why: the realm applies this module's SQL before the
      Skyborne's (by the folders' names), and the Skyborne's exact matches then found nothing: no
      Common or Orcish, Swords or Two-Handed Swords for the Skyborne since the allied races came.
      The Skyborne's updates find the rows by the stock bits now too. Checked here in that order,
      fresh and applied twice: all six races in their side's rows.
    - Build 237: all six know their language; Preternatural Calm's aura is in the world (type
      149, 100). Spatial Rift did nothing, for the test's way of casting it: a GM's `.cast X
      triggered` casts with every trigger flag, TRIGGERED_IGNORE_EFFECTS among them, so no effect
      of the spell runs. The steps cast the racials with a plain `.cast` now.
  - The package: `scripts/package-azerothcore.sh` fetches the game's files (about 600 MB) from
    wago.tools into `build/allied-races-cache` (CI caches it by races.json) and converts them (two
    minutes here); the server package grows by about 300 MB (voices as MP3).
  - Checked here: the converter and textures on the fetched files; `tools/test_patch.cpp` (the patch
    made for a stand-in game from the package and read back, 233 MB, 14 s; a second start keeps it);
    `tools/test_tools.py`, `tools/test_screen.py` (alone and with the Skyborne's screen code).
    Not checked: the game itself (the models drawn, the composite textures, the voices, the screen,
    the intros), the server build and SQL (CI), the quests played.
  - To ask: whether the options with no 3.3.5 slider (eyebrows, ears, face tattoos, jewellery) should
    take some other slider's place; whether the tenth void elf eye colour should replace one of the
    nine; the Wowhead portraits are 56 px (the game's cells are larger: they're scaled up).

**Next:** the user's test on the phone (the first Wine start, graphics, sound, the gestures' feel,
a realm with modules), then stage 3.

- The character creation screen crashed (the user's crash report, issue #34: ERROR #132, an access
  violation at 0x004EA77F reading 0x00000010, from the screen's Lua; on ChromieCraft it wouldn't
  open, on Wrath Remix it died as the race or sex changed). The blood elf's Dark Ranger skin had the
  player faces of its skin but not the death knights' (flag 4), half the faces of every other skin
  of its race and sex; a look with it and one of the missing faces had no row, and the game reads
  through a missing row. The Dark Ranger skin now has all its skin's faces.
  Found by rebuilding the user's tables here: the server's, the Alternative HD Patch's
  character and creature tables (its `patch-enUS-A` and `-F`, fetched from its archive), then the
  allied races' and the Skyborne's patches; `tools/test_client.cpp` now checks that every player
  skin has the same faces as the others of its race and sex (it fails on the old rows).
- The user: "Only chromie needs new races, wrath just needs bug fixes." The allied races are
  ChromieCraft's alone: their SQL moved to `data/sql/chromiecraft/world`, which `ctl.sh` (`realm_sql`)
  puts in ChromieCraft's world database only, with `data/sql/remix/world` taking earlier releases'
  rows out of Remix's (their starts, stats, action bars, racials, race and look rows); their patch is
  set aside on Remix (`AlliedRaces.Enable 0`, `patch-V.MPQ.aside`) and back on ChromieCraft without
  being made again; `world_modules_now` counts the realms' own folders, so the move doesn't make
  either world database again. Wrath Remix's language folder's patch (`patch-<locale>-Y.MPQ`) stayed
  for ChromieCraft and hid newer tables there (its `SoundEntries.dbc` was the server's own: the
  allied races' voices and narrations, the HD looks' spell sounds); ChromieCraft's world server takes
  it out now, it's made onto the game's own `SoundEntries.dbc`, and the allied races' patch isn't made
  from it. The Skyborne stay on both realms until the user says otherwise.
- The user: "These races are not scaled correctly. Load the old race models and new ones in 3d then
  scale them according to the old race size. Trolls to trolls. Draenei to draenei. Elves to elves."
  Measured here as the game poses them (where the head's bone is in Stand's first moment, the feet
  at 0; the mesh's top was tried first, but the nightborne's ears are in their body's mesh and the
  night elves' aren't, and the troll's top is his stoop), the old races in both HD looks (the
  Alternative HD Patch's `patch-A`, fetched from its archive, and ChromieCraft's `Patch-H`: the same
  heights) against the converted models: void elves 1.868 / 1.717 (men / women) as the blood elves
  exactly, Lightforged 2.251 / 2.108 as the Draenei exactly, nightborne 2.245 / 2.000 against the
  night elves' 2.212 / 2.017, Zandalari 2.537 / 2.443 against the trolls' 2.072 / 2.136 (today's
  stand upright). `tools/scale.py` makes each its kin's height all through as the package is made
  (nightborne 0.985 / 1.009, Zandalari 0.817 / 0.874; the rest stay); every pose checked here to be
  the old one times the factor (to 3e-5 yards over Stand, Walk, Run and a dozen animations of
  each), and the models drawn side by side with their kin's. On the creation screen the void elves
  were in the Draenei's scene, framed for a race half a yard taller, and the Zandalari in the
  Tauren's: each is in its kin's now (Blood Elves', Trolls').
  Found on the way: the converter wrote each animation kept in a file of its own as the file's
  model chunk alone (AFM2, 16 to 192 bytes), while the bones' data is in its AFSB chunk: every such
  animation (emotes, sits, most besides Stand, Walk and Run) had the game reading its bones from
  past the end of a 64-byte file. The files are whole now (the bones', the attachments' and the
  model's chunks in one block, the tracks pointing into theirs). Some of today's skeletons list
  files laid out for another skeleton (their times don't rise): the nightborne's parent skeletons
  list their older night elf models' files, whose own bones fit them exactly (12,486 tracks), so
  those sequences take their bones from there, matched by the bones' names' hashes; where nothing
  fits (32 of the void elf women's, 22 of their skeleton's 54 files fit it; the Lightforged women's
  Cry; the Zandalari women's Shy), the animation is drawn as its first variation or Stand. After
  that, no vertex of any of the 1,012 animations 3.3.5 knows (first variations) goes beyond 4.6
  yards. Not checked: the game itself.
- The user: "Be sure the char creator never crashes." `tools/check_looks.py` checks the creation
  screen's tables out of the whole patch chain for every look it can step to (the rule crash #34
  broke: each player skin with its race and sex's faces, a death knight's with the death knights';
  hair colours and geoset rows for every style; features 0 to n-1; the looks' display and model
  rows). On the Alternative HD Patch's tables (the user's only setup: "I'll never use" the others)
  with both patches in: 53 races and sexes, no problem; on the rows before 1.0.249's fix: 926.
- Found by the realm test of 1.0.252 ("Wrath Remix lets go of its Timerunners who went home"
  failed: the two were still there as it looked): with the allied races' patch set aside on Remix,
  the Skyborne's patch is made again at each change of realm (rightly: it carries the patches'
  rows before it), and it was made in the world server's startup scripts, after its "ready" line,
  which is when the app starts the game. So on a change of realm the game could start on the other
  realm's `patch-Z` (on Remix, one with the allied races' rows and none of their files). The
  Skyborne's patch is made before the world says it's ready now (`OnBeforeWorldInitialized`: after
  Wrath Remix's `patch-Y`, made as the tables load, as before); the test waits for Remix's queued
  deletions as it does for ChromieCraft's homecomings.
- Crash report #47, the same address as #34 (0x004EA77F, reading a missing CharSections row's
  texture), as the race changed on the creation screen. The screen reads a skin colour's row by its
  number, and some colours below a race and sex's highest had none (the Skyborne's between the blood
  elf's skins and their blues; colours the server's tables have and the Alternative HD Patch's
  haven't). The Skyborne's patch now gives each such colour an NPC's skin row (flag 8, as the game's
  own colours between), its faces and overlay by CompleteSkins. `check_looks.py` and
  `test_client.cpp` fail on a colour without a row (none now on ChromieCraft's 53 races and sexes
  or Wrath Remix's 45). Not certain this is the crash (nothing here runs the game).
- The user: "Lol fix the models" (a void elf's staff through her body on her back), "the lightforged
  draenei forehead light Is missing", "let undead be paladins". The allied races' back sheath points
  get bones of their own turned to their kin's orientation in Stand (`tools/sheaths.py`, measured on
  the Alternative HD Patch's models); the Lightforged's Rune (today's first choice is None) is always
  Righteous; the Forsaken can be paladins on ChromieCraft's realm (Deathknell, the Blood Elves' gear
  and bar, the paladin's skills and spells opened to them: `tools/build_data.py`'s EXTRA_CLASSES).
  Not seen in the game: the sheaths and the rune checked by the model renderer and checker only.
- The user: "the intros video needs to be around the same length as the sound clip". Each race's
  intro camera is the game's flyby retimed to its narration's length + 1.5 s, made by the world
  server with the patch (`RetimeCamera`). Tested on stand-in 3.3.5 cameras made to the format (no
  3.3.5 client's base archives here): the real flybys' layout is assumed (type, field of view, clip
  planes, three tracks); one that doesn't fit is left stock, not changed.
- The user's screenshot: Questie's "playerRaceId (a nil value)" on a void elf. The races' patch adds
  an in-game script that gives Questie's race table (`QuestieCompat.ChrRaces`, Questie-335) each race
  as its start race (`AlliedRacesAddons.lua`). Checked in Lua 5.1 against stand-ins (`test_screen.py`),
  not with Questie in the game. The Skyborne have the same gap; not done.

## Not sure (the user: "make a list of things you aren't sure about and we'll do them later")

Nothing here runs on a phone; these are the things only the phone can settle, and the calls made
without it. Each is small on its own.

- **The curtain on the phone.** The add-on calls the frames settled after 1.5 s without a frame
  over 0.2 s (2 s in at least, 60 s at most); the realm waits 25 s at most (`WrathRemix.Intro.
  SettleSeconds`). On the Fold the right numbers may differ: a city that never runs smoothly
  means every act waits the whole 25 s; too lax and the room is still loading as it's revealed.
  `.remix intro status` says how many curtains were drawn and answered and when.
- **The caption's look.** White text on black, two lines ("The Gilded Rose, Stormwind / Some months
  after the fall of the Lich King"), the add-on's fade frame's font and size: not seen on a screen.
- **Game masters' add-on messages.** Until this build the realm's whispers to a game master's own
  character went out as GM chat; the app can make the phone's account a game master, and whether
  the game's add-on still heard them wasn't verified (the test client didn't). They're plain chat
  now, whoever the player is.
- **The bots at a first login.** Fifty bots load with the realm; their CPU time on the phone in the
  opening's first minute, and whether it keeps the frames from settling, is unmeasured.
- **The pinch zoom on the creation screen.** The model frame is grown around the character (the
  screen's middle, a little above its centre); whether 3.3.5's ModelFFX draws its scene to the
  frame's rectangle as assumed, and where the character actually stands on the Fold, isn't seen.
- **Eternus's dragon model, Malykriss's look, the barber chair, the heirloom set's stats, the
  gendered words ($G) in lines:** all built from the data and the formats, none watched in the
  game yet (the earlier entries above say what each is).
- **The Bazaar's name.** Malykriss keeps its own area name in the game (patch-Y is ChromieCraft's
  too, so the area isn't renamed); the Bazaar is named in its captions and its people's words.
- **The hooded Cloaked Ranger and the new looks.** The Bazaar's people got new looks from the data;
  how the hooded ranger reads beside them is a matter of taste for the screen.
- **Felsong's texts.** The 103 strings the Felsong code needed were written here in Turtle WoW's
  words said of Felsong, so the app builds; the Felsong session may word them its own way.
- **CI's triggers.** The push of the Felsong texts started no run on either branch (every push
  before did); the run was started by hand (157). Whether GitHub skipped it or was late is unknown.

## Still open

1. The proposed gestures for right click, camera, map and keyboard (under "Controls"): built as
   proposed; the user can change them after trying them.
2. About 40 GB free on the phone.

## Sources

- Winlator: https://github.com/brunodev85/winlator (LGPL-2.1)
- Winlator forks and GPU notes: https://emulation.gametechwiki.com/index.php/Winlator,
  https://github.com/StevenMXZ/Winlator-Ludashi
- ChromieCraft core: https://github.com/chromiecraft/azerothcore-wotlk
- Progression system: https://github.com/azerothcore/mod-progression-system
- ChromieCraft modules: https://github.com/orgs/chromiecraft/repositories
- Playerbots: https://github.com/mod-playerbots/mod-playerbots (its core:
  https://github.com/mod-playerbots/azerothcore-wotlk, branch `Playerbot`); version-mismatch reports:
  https://www.linux.org.ru/articles/games/18260586
