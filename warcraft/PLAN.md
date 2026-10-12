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
  already running behind it.
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
- `game/Game.kt`: **Press start** (and opening the app) starts the server, installs the runtime
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

**Next:** the user's test on the phone (the first Wine start, graphics, sound, the gestures' feel),
then stage 3.

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
