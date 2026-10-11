# World of Warcraft (3.3.5a) — the brief

The next app in this repository. Nothing of it is built yet; this is what the user asked for, what
was decided, what's still open, and the plan. Read `docs/single-game-apps.md` first: this app
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
- **Add-ons:** a generic manager, no built-in list (see "Launcher sections").
- **Double tap = jump.** The map moves to another gesture (proposed below).
- **Phone:** Galaxy Z Fold 8.

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

**Controls.** Decided by the user: tap = left click, swipe = movement (W/A/S/D), hold = bags
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

**Decision to make early:** ChromieCraft's pinned core and the playerbots fork are two different
AzerothCore branches. Either merge them (playerbots' core changes onto ChromieCraft's revision)
or use the playerbots fork with ChromieCraft's modules on it. Try the build in CI before
promising either.

## Launcher sections (to design with the user's art)

- **Play** (title screen: header, logo, press start).
- **Game files**: import the client (folder or zip), check the build, show size; the data
  extraction with progress.
- **Server**: running or not, start/stop, logs; ChromieCraft progression brackets; rates and
  realm options; reset databases. Every option changeable at any time.
- **Accounts**: create, change password, GM level, delete; which one the game logs in with.
- **Bots**: on/off, how many, level ranges, per-account bots, the playerbot settings, at any
  time.
- **Add-ons**: install any add-on at will, nothing preinstalled or special-cased. Import a zip
  or a folder; find every add-on in it by its `.toc` file, however it's nested (a zip may hold
  several, or wrap them in an extra folder), and install each as its own folder in
  `Interface/AddOns` (replacing an older copy). List them by their `.toc` title and version, switch
  them on or off (`WTF/Account/<ACCOUNT>/AddOns.txt`; the game's own AddOns button at character
  select keeps working), delete them.
- **Controls**, **Graphics** (resolution, renderer, frame cap), **App icon**, **Updates**.

## Stages

1. **Server in CI.** Build the chosen AzerothCore branch with its modules and MariaDB for Linux
   arm64; in CI start the database, create the databases and start the world server to its
   "ready" line. Then the app shell: launcher UI and the server running on the phone. The user
   can check it by connecting a PC client to the phone.
2. **The client.** Winlator's runtime in the app, the client started directly at its login
   screen, gestures.
3. **Everything else.** All server options, accounts, bots, add-ons, the icon switcher, polish.

## Still open

1. The proposed gestures for right click, camera, map and keyboard (under "Controls"): go with
   them unless the user says otherwise.
2. Whether a PC is available for the one-time map extraction (otherwise it runs on the phone).
3. About 40 GB free on the phone.
4. ChromieCraft and playerbots together (see "Decision to make early"): try it in CI; if they
   can't be combined, ask the user which matters more.

## Sources

- Winlator: https://github.com/brunodev85/winlator (LGPL-2.1)
- Winlator forks and GPU notes: https://emulation.gametechwiki.com/index.php/Winlator,
  https://github.com/StevenMXZ/Winlator-Ludashi
- ChromieCraft core: https://github.com/chromiecraft/azerothcore-wotlk
- Progression system: https://github.com/azerothcore/mod-progression-system
- ChromieCraft modules: https://github.com/orgs/chromiecraft/repositories
- Playerbots: https://github.com/liyunfan1223/mod-playerbots (its core:
  https://github.com/liyunfan1223/azerothcore-wotlk); version-mismatch reports:
  https://www.linux.org.ru/articles/games/18260586
