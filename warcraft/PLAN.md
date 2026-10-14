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
  skin, face, hair or underwear texture the client hasn't got the race's nearest that it has, and
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
  to take the Helm after Arthas and hold the Scourge); Eternus's motive: he believes a kinder
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
    half transparency, in a dim cave) and makes his offer; Alurmi and the class instructor (a
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
  Order of work: the prologue and Chapter I (done, build 64), the report button, Chapter II and the
  turn, Chapters III and IV rewritten, V to VII, then the ending and the move to ChromieCraft.

- Open offers (not started): (1) when a realm with modules fails its test, test again without each
  picked module in turn, to name the one that crashed and still build with the rest; (2) the
  user's own modules and quests in this repository, offered in the Modules screen.

**Next:** the user's test on the phone (the first Wine start, graphics, sound, the gestures' feel,
a realm with modules), then stage 3.

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
