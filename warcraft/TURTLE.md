# OctoWoW (was Turtle WoW — Vanilla+): the brief

The app's second game, played on the phone against its own local realm, next to (and completely
apart from) the 3.3.5a game with its ChromieCraft realm and Wrath Remix. It was Turtle WoW until
October 2026, when it became OctoWoW (below); the code, the folder (`files/turtle`) and the realm
keep Turtle's names, so an install carries on. The user's manual is `README.md` ("OctoWoW"); the
3.3.5a game's brief is `PLAN.md`.

## OctoWoW (October 2026)

### What the user asked

- "See if we can replace turtle wow with octo wow private server setup just like we have it in our
  own server."
- Told OctoWoW's own server isn't published: "So find a source for it that we can play. I know some
  release the old updates."
- The launcher in **OctoWoW's site look** (their pick over keeping the Turtle art).

### What was found

- **OctoWoW** (octowow.st): a Vanilla+ server since April 2026, "built on Turtle WoW's 1.17.2
  patch", with 1.18.1 "restored from data mined from the original Turtle WoW" (July 2026); three
  realms; run anonymously, its data kept by "Knowledge Keepers". **Its server isn't published**: its
  Gitea (`octowow.st/git`, 132 public repositories read through its API) has its launcher
  (OctoLauncher, TypeScript), add-ons and client tools, no core, no database; its roadmap and pages
  say nothing of releasing them.
- **Its client is downloadable**, from its own server, as its launcher gets it: a torrent
  (`dl.octowow.st/download/client.torrent`, 50 files, 9.46 GB, 1 MiB pieces, a BEP 19 web seed
  `https://dl.octowow.st/client/`), each file over HTTPS with ranges (also `client/latest/`), and a
  9.46 GB zip. Its WoW.exe (read from the file) is **1.18.1, build 7272**, Turtle WoW's last,
  rebuilt by OctoWoW ("OCTOWOW CLIENT BUILD gen=2 ... horizon-farclip+questlog-zonelist"); its realm
  list isn't forced ("octowow.st" in it is only on the list of sites it may open); its login screen
  only links to OctoWoW's pages. It imports no DXVK; it loads `d3d9.dll` from its folder when the
  game draws with Direct3D, and the torrent ships one (DXVK gplasync 2.7.1, an OctoLauncher mod), as
  well as other launcher mods loaded only through VanillaFixes' chainloader.
- **Its content against Tortoise-WoW** (DBC tables read from its MPQs on dl.octowow.st with HTTP
  ranges, a small MPQ reader written for it): 135 of the 159 tables Tortoise has checksums of are
  byte for byte Turtle 1.18.1's; 20 are OctoWoW's own (its `patch-5.mpq`: Spell, SkillLine,
  CreatureDisplayInfo, ItemDisplayInfo, Taxi*, ...), all with Turtle's record layouts; three tables
  1.12's patch.MPQ marks deleted and Turtle's hashes.txt aren't Tortoise's versions. Against
  Tortoise's world database (its `sql/base`): every one of its 27,916 spells is in OctoWoW's
  Spell.dbc (which has 109 more), every creature model is there, 2 of 23,078 items have no model;
  4 of 6,247 skill rows point at skill lines OctoWoW dropped (Felguard, Dragonhawk). Its patch
  changes about 165 map files (Gilneas, a Karazhan map, Sunnyglade, the Grove, the Emerald Sanctum,
  two battlegrounds, tiles of both continents). Tortoise's map tools read `patch.MPQ` and
  `patch-2..9`; OctoWoW's `patch-1` and `patch-2` hold only music.
- **"Old updates" released by others**: the Turtle WoW **1.17.1/1.17.2 leak** is passed around as
  Windows repacks (debug builds, MySQL, a 1.17.2 client; one with PartyBots on chroniclecoders.com,
  its source only "on request"); **LunaticPTR** (a 1.17.1 server) publishes a release repack. None
  has source to build for a phone, and they're made for older clients than OctoWoW's 1.18.1 (7272).
  Tortoise-WoW, the open restoration of Turtle WoW's 1.18.1 server, is the one public server made
  for that client.
- octowow.st's look (its site is turtlecraft.gg's, re-skinned, its files under `turtle_assets/`):
  the near-black page, steel-framed panels with corner brackets and rivets, Folkard headings in
  gold to ember, Fontin Sans SC capitals, a steel band with diamonds under the menu, buttons on
  smoky plates with an ember (Download) or green (Sign up) glow and steel diamond ends; its
  launcher: a violet wood and the OctoWoW logo.

### Decided

- **OctoWoW's client on the realm we have**: Tortoise-WoW with TortoiseBots stays the realm (the
  same package, `turtle-server-*`); the game, its texts and its look become OctoWoW's.
- **The app downloads OctoWoW's client** (Install does, after the realm; **Download from OctoWoW**
  under Game files): `ClientTorrent` reads OctoWoW's torrent (fetched at each download or check;
  the last one kept in `files/turtle/client.torrent` for offline checks and the next update's
  removals), `ClientSync` brings a folder to exactly it: every file read in the torrent's order and
  hashed piece by piece, missing or resized files downloaded into `.part` with ranges (carrying on
  after a stop), pieces that don't match fetched again alone, files the previous torrent had and
  this one hasn't deleted, and archives in `Data/` it doesn't list removed (another client's
  `patch-6.mpq` would load over OctoWoW's). It works in `client.download` and puts it in place of
  `client/` only when every piece matches. OctoWoW's `d3d9.dll` and `realmlist.wtf` are kept in
  `client-kept/` for their pieces, out of the game's folder.
- **Your own copy** (folder or zip): copied in, then synced the same way (only what differs
  downloaded); without OctoWoW's server, taken unchecked. Turtle's client manifest is gone from the
  app (`client-manifest.tsv`, and from `turtle-manifests.py`).
- **Updates**: Check for an update (the torrent's info hash against the phone's) and Check and
  update (a sync). The server's data is made again when the client's MPQ archives change (a key
  over their SHA-256s, hashed during the sync), not on any change.
- **The DBC check** keeps Tortoise's checksums and knows OctoWoW's own 24 tables
  (`assets/turtle/octowow-dbc.txt`): only others count as different.
- **The look**: OctoWoW's logo (octowow.st's, its see-through letters made solid over the page's
  colour) over OctoLauncher's violet wood, gold and violet motes, the site's steel band, frames
  (brackets and rivets) and buttons (ember glow, steel diamonds), Almendra (SIL OFL, the nearest
  open font to Folkard) for headings, Alegreya Sans SC for capitals; the spinner has eight plates.
  Turtle's music stays. The user's Turtle art stays in `branding/turtle/`.

### Where it stands

- **Checked off the phone**: the sync's JVM tests (fresh download, resume from a part, a dropped
  connection, a connection dropping every few kB, no connection at all, a damaged piece fetched alone, a resized file, a seed serving wrong bytes, an update's
  removals, another client's archives, an imported client's spellings and its DXVK, BEP 47 padding,
  cancel, the folder guard); OctoWoW's real torrent parsed by the app's code and real pieces
  fetched from its web seed with the app's downloader (six of WoW.exe's, and the last one, across
  `patch-5.mpq` and `realmlist.wtf`) matching; all of the app's JVM tests; the app's Kotlin
  compiled; the launcher and Game files rendered (Robolectric, phone size).
- **Not checked**: a whole 9.5 GB download on a phone; the OctoWoW client running on Winlator; a
  login and play against the realm (its login takes build 7272, as Turtle's client did); the server
  data made on the phone from OctoWoW's client (the same tools, its archives as listed above).

## Turtle WoW (before October 2026)

The app's second game: Turtle WoW, played on the phone against its own local realm, next to (and
completely apart from) the 3.3.5a game with its ChromieCraft realm and Wrath Remix. The user's
manual is `README.md` ("Turtle WoW"); the 3.3.5a game's brief is `PLAN.md`.

### What the user asked for

- Turtle WoW as a **separate, fully working game environment** in the launcher ("Turtle WoW —
  Vanilla+"), alongside ChromieCraft and Wrath Remix, **with no way to disturb them**: their
  clients, servers, databases, configs, accounts, characters, saved data, ports, processes, logs,
  downloads and backups untouched by anything Turtle does, including a failed install, a repair
  and an uninstall.
- Install, download every component, set up the server core, launch, start and stop its realm,
  status, repair, updates, settings and a clean uninstall, **each dependency a one-tap download
  and install**, with progress, resumable checked downloads, retries, rollback and clear errors.
- **Offline single player**: the realm and its database start by themselves, the client connects
  to it, everything stops cleanly.
- PlayerBots and solo features **only where the core really has them**.
- A **Turtle WoW theme** when Turtle WoW is selected, from the user's art (the old map of Kalimdor
  and the Eastern Kingdoms as the header, the "Turtle WoW: Mysteries of Azeroth" logo) and
  turtlecraft.gg's look.

### What was found (October 2026)

- **Turtle WoW is shut down.** Blizzard won its lawsuit: on April 15, 2026 a California court entered
  a consent judgment and permanent injunction that bars the team from running the server, updating
  the code, distributing its modified client or passing the code base on; the servers went offline
  on May 15, 2026. The client (1.18.1, build 7272, with its 2026-04-12 hotfixes) was the last.
- **The core: [Tortoise-WoW](https://github.com/tortoise-wow/tortoise-wow)**, "an unofficial,
  community-driven restoration of the 1.18.1 patch of Turtle-WoW", MaNGOS family (realmd, mangosd),
  GPL-2.0, portions from AzerothCore and VMaNGOS. Its README and `docs/setup/` (read at the pinned
  commit): client **1.18.1 build 7272 with the 2026-04-12 hotfixes only** (its login server
  accepts build 7272 alone: `src/realmd/RealmList.cpp`); dependencies MySQL/MariaDB (MariaDB
  recommended), ACE, OpenSSL, zlib, bzip2 (Recast, G3D, StormLib/libmpq bundled); databases
  `tw_logon`, `tw_char`, `tw_world`, `tw_logs`; setup = `sql/create_databases.sql`, then
  `sql/base/*.sql` into `tw_world`, then mangosd applies `sql/database_updates` as it starts;
  the server's data (dbc, maps, vmaps, mmaps) extracted from the client with its own tools; its
  client manifest and DBC checksums in `tools/dbc_verification`. **No playerbots, no auction house
  bot, no dungeon scaling** in it (a `HeadlessSessionMgr` exists, with no bot AI on top).
- **[tortoise-docker](https://github.com/Nescabir/tortoise-docker)** is Docker packaging of a
  different fork, `Shyalya/tortoise-wow` (branch `playerbots-integration-gh`, with playerbots),
  x86-64 images only. That fork is **no longer on GitHub** (fetching it asks for credentials: gone
  or private). Docker can't run inside an Android app anyway. Its init scripts were useful: the
  `character_inventory_copy` table the core needs and `create_databases.sql` doesn't make, and the
  order of the setup.
- **[TortoiseBots](https://github.com/Sagiroth/TortoiseBots)** (Sagiroth; v1.0.0,
  `c850dc7956c5`): a native playerbots module for Tortoise-WoW's main branch, built into the world
  server (`MODULES=static`), made for one player on their own realm: world bots that level, quest,
  travel and trade; hired companions (an inn's recruiter) and the player's own alts as bots, who
  tank, heal and fill roles in the dungeon finder and battlegrounds. Settings in
  `aiplayerbot.conf` (template: 500 world bots, a PC's), `etc/modules/tortoise_bots.conf` must be
  there for the world server to start, and its database updates are in
  `data/sql/{world,character}`, applied by the core's auto-updater from the module's folder. Its
  control addon, **[TortoiseBotsManager](https://github.com/Sagiroth/TortoiseBotsManager)**
  (`/tbm`, MIT). GPL-2.0 with ported AGPL-3.0 playerbots code (its `LICENCE.md`).
- turtlecraft.gg's look (its stylesheet): page `#100d0c`, the gold-to-coral gradient `#f1c22d` →
  `#ff7757` on buttons, links and borders, orange `#f89d41`, uppercase Fontin Sans headings,
  Friz Quadrata in the menu, thin gold rules fading out both ways.

### Decided

- **A native build, not Docker**: Tortoise-WoW and ACE compiled for arm64 on musl (Alpine) like
  the ChromieCraft realm (`scripts/compile-tortoise.sh`, three small portability patches in
  `turtle/patches/`, `-march=native` replaced by a baseline `armv8-a`), packed with its own
  MariaDB, the system call shim and a seed database with Tortoise's databases made and its world
  imported (`scripts/package-tortoise.sh`). **Not in the APK**: CI publishes it as its own
  prerelease, `turtle-server-<hash>`, once for each version of its inputs; each APK names the one
  it's made for with its size and SHA-256, and the app downloads it on Install (resumable, checked).
- **The client is never downloaded** (until OctoWoW, above). Turtle's own CDN mirrors still appear
  in the manifest Tortoise ships, but the court order bars distributing that client, so the app
  doesn't fetch it.
  The user picks their own copy (folder or zip); the app copies it into Turtle's folder and hashes
  every file as it copies, against the manifest (`assets/turtle/client-manifest.tsv`,
  `scripts/turtle-manifests.py`, checked against the pinned commit in CI). A client that differs
  is accepted with a warning (the realm only checks the build at login).
- **The server's data is made on the phone** from the user's client with Tortoise's own tools
  (`ctl.sh maps|vmaps|mmaps`): dbc and maps in minutes, vmaps minutes more, mmaps (pathfinding)
  optional, hours. The DBC files are checked against Tortoise's checksums. There is no published
  data for this client to download (it would be the client's own files).
- **Isolation by construction**: everything of Turtle's is in `files/turtle` (`TurtleLayout`), every
  delete, move and replace goes through `SafeFs` (refuses anything outside that folder or in the
  other game's folders, never follows a link), its own MariaDB on port 3311 with its own user and
  databases, its world server on 8090, its own Wine prefix (the "Turtle WoW" container), its own
  saved login (`turtle_login`), settings (`turtle_settings`), notification channel and service.
  The two realms' login servers both need the game's port 3724: **one realm runs at a time**, and
  switching games asks before stopping the other's realm.
- **The launcher**: a game switch (a chip in the header and a tile); with Turtle WoW chosen the
  whole launcher is Turtle's (`turtle/ui`) in its own look (the map with warm motes, the logo,
  Moonwhisper Coast), and **works as the 3.3.5a launcher does** (the user's ask: "functionally
  identical"): Press start, then the same nine tiles in the same places: Server (each part of the
  realm with its own button, Wi-Fi, logs, battery, backups, Repair, Uninstall, About), Game files
  (client, server data), Accounts, Graphics, Add-ons (the same manager, for warperia.com's Vanilla
  list), Bots & rates (where Modules is), App icon, Updates, and the switch back. The 3.3.5a
  launcher is unchanged apart from the chip and the tile; its add-on manager is the same code,
  now for either game (`AddonStoreOf`, `AddonGame`).
- **Entering Azeroth**: the loading screen until the game's window is up is each game's own: the
  user's map with Turtle's logo and motes, or the Lich King with snow.
- **Bots: TortoiseBots**, compiled into the realm (the core has none of its own). The package
  carries its database updates (`server/modules/TortoiseBots/data/sql`, where the world server
  looks: Turtle's patch 0004 makes that folder a build setting) and TortoiseBotsManager, which the
  app copies into the client's AddOns at each start. World bots: 40 by default, 0 to 150 under
  Bots & rates (`aiplayerbot.conf`, set at each start); its telemetry port stays off. Also there:
  XP, money, reputation and rest rates, and the new characters' level, all checked against
  Tortoise's `mangosd.conf.dist.in`.
- **Updates**: the realm package is pinned per app version (never an incompatible one); a newer
  realm comes with an app update and is put in with the accounts and characters kept (backed up
  first) and the new database updates applied at the next start.

### Where it stands

**First version: released (`wow-build-65`, with its realm `turtle-server-4aa752a01dff`); tested off
the phone; the user's first install on a phone found a fault (below), fixed in the build after it.**

- On the user's phone, **Download and install** downloaded the realm package and then stopped:
  "Bad path in the package: server/sql/". The app's unpacker refused every folder in it (tar
  writes folders' names ending in `/`, and the check took the empty part after it for a trick),
  and no test had unpacked a real package the app's way: CI's test unpacks it with `tar`. The
  unpacker is now in `turtle/core` (`TurtlePackageFormat`), with a JVM test that makes a package
  the way `package-tortoise.sh` does (GNU tar, then xz) and unpacks it the app's way, and fails
  with the phone's error under the old check. The realm package didn't change (the same release),
  and the downloaded file is kept, so the next try only checks it and unpacks it.

- On the user's phone, every login said "Disconnected from server". Tortoise's login server
  drops a client that asks for the realm list twice within `MinRealmListDelay` seconds (1 in
  its realmd.conf), without a word: a guard for a public server, which the phone, answering at
  once, sets off. Reproduced against the realm's own realmd (two requests in a second: the second
  closes the connection, "sending CMD_REALM_LIST too frequently" in its log) and fixed by the
  app setting it to 0 at each start (`TurtleRealm.REALMD_SETTINGS`): both answered. The client's
  patch list is pointed at the phone too (Turtle's own files name its old servers). Not yet seen
  on the phone.
- **Bots, the launcher rework and the add-on manager** (the next realm package): checked on an
  x86_64 machine first. The realm compiles with TortoiseBots in it; the package is 56 MB (471 MB
  installed) with the module's SQL and the addon; its test passes every functional check: the bots'
  configs are made (`aiplayerbot.conf` with 40 world bots, `etc/modules/tortoise_bots.conf`), the
  login answers three realm lists in a row (the disconnect above), and the world server applies
  all 192 database updates, 41 of them TortoiseBots', before it stops at the DBC files. On CI's
  arm64 (run 70): compiled in 45 minutes; the package is 56 MB (468 MB installed); its test passed
  every check, the system call check against Android's policy included (113 server processes and
  threads), three realm lists answered, 192 of 192 updates; published as the prerelease
  `turtle-server-f239aff1e747`. JVM tests: 176, passing (the bots'
  settings, the addon's install into the client, warperia.com's Vanilla list and downloads). The
  launcher's screens were rendered off the phone, each tile's.
- On the user's phone, after the realm list fix, logging in still ended in "Disconnected from
  server": the client authenticated, got the realm list and connected to the world server, which
  crashed (`Received SIGSEGV` in its log, right after loading the account's tutorials). Tortoise's
  anticheat reads `anticheat.conf` from the world server's folder; the package has none, so its
  defaults turned it on, and for each player it set up Warden, whose RC4 needs OpenSSL's legacy
  module, which the package carries but nothing pointed OpenSSL at (the 3.3.5a realm's ctl.sh
  exports `OPENSSL_MODULES`; Turtle's didn't). Reproduced with the package's own libcrypto
  calling OpenSSL as Tortoise's ARC4 does: without the module path it segfaults, with it RC4
  works. Fixed both ways: the app sets `OPENSSL_MODULES` for the realm and writes
  `run/anticheat.conf` with the anticheat off (the world server opens it: checked with strace),
  so the packages already published work too; ctl.sh does the same for later ones. Off, because
  with the anticheat on and no Warden module, Warden would also hold the character list back
  for good; the world server's own addon handler still answers Turtle's signed addons. Not yet
  seen on the phone. CI's test can't get a player that far (no game data); it now checks the
  file and the module. Released in `wow-build-73` with the realm `turtle-server-b892772a1586`
  (packaged and tested on arm64, the system call check included). That run also found a fault
  in package-tortoise.sh, the first time it packaged from a cached compile: its library check
  didn't look in the package's own lib/ for ACE (fixed; the run before it failed on it).
- **Pathfinding made on a computer** (the user asked for the hours-long job off the phone): CI
  can't make it, as nobody publishes Turtle's server data ready-made (AzerothCore does for 3.3.5a,
  which the app downloads) and it can only be made from the user's own client. So
  `scripts/make-turtle-server-data.sh` makes it on any computer with Docker, with the tools built
  from the realm's own commit, and **Game files → Import zip** takes it (`TurtleDataImport`: only
  the four folders; every map, vmap and mmap file checked by its header, `MAPS`/`z1.4`,
  `VMAP_6.0`, `MMAP` version 6; refused whole otherwise; DBC checksums). Found on the way and
  fixed: Tortoise's MoveMapGen `--silent` ends with **1** when it has built everything, which
  ctl.sh passed on, so the app would have taken hours of finished pathfinding for a failure and
  thrown it away (seen on a real run; ctl.sh now passes 1 on as 0; a unit test runs ctl.sh with
  each code, and the realm's CI test builds a tile and checks it). Making the data again without
  mmaps now keeps the pathfinding already there. The script was run up to the extractor with a
  stand-in client (no Turtle client here); the import was not run on a phone.
- CI on arm64 (GitHub's `ubuntu-24.04-arm`): Tortoise-WoW and ACE compiled in 20 minutes; the
  package is 51 MB (459 MB installed); its test passed every check, including the system call
  check against Android's seccomp policy (113 server processes and threads): Turtle's MariaDB,
  a 1.18.1 (7272) login with the realm list (Tortoise WoW, 127.0.0.1:8090), all 151 database
  updates, backup and restore, nothing made beside `files/turtle`. Published as the prerelease
  `turtle-server-4aa752a01dff`; `wow-build-65`'s APK is pinned to it (size and SHA-256).

- Checked first on an x86_64 machine: Tortoise-WoW and ACE compile on musl with the three patches;
  the package builds (52 MB, 461 MB installed; the seed's world has 283 tables and 12,751 creature
  templates). Its test (`scripts/test-turtle-server.sh`) passes every functional check: Turtle's
  MariaDB from the seed on its own port; an account made the app's way logs in with the 1.18.1
  (7272) protocol and gets the realm list (Tortoise WoW, 127.0.0.1:8090), and a wrong password
  doesn't; the world server applies all 151 of Tortoise's database updates and stops where it needs
  the DBC files (CI has no client data; header-only stand-in maps get it past its map check); a
  backup restores a deleted account; nothing is made beside `files/turtle`. The test found and
  fixed one fault: Tortoise asks staff accounts for a PIN by default, so the app's first account
  (the realm's administrator) couldn't log in (`ForcePinAccountRank`, in Turtle's `ctl.sh`). (The system call check
  only means something on arm64: on x86_64 it flags old calls arm64 doesn't have.)
- JVM tests (41 new, 161 in all, passing): the guard (outside paths, the other game's folders,
  links that lead out, links in a Wine prefix), a scan of the app's sources (every folder the
  3.3.5a side makes is protected; no raw delete, rename or move in Turtle's code; no reference to
  the other game's storage), the server script's folder and ports and its refusal of another
  folder, the client and DBC manifests, the password hash (MaNGOS's ADMINISTRATOR vector), the
  install plan, resumed, corrupt and cancelled downloads, the realm package unpacked the app's way
  (and packages with entries that lead out refused), the client's settings, Tortoise's log lines and
  settings.
- The branch merges into the Wrath Remix line's branch without a conflict.
- The release APK builds (locally and in CI), and the launcher was rendered off the phone
  (Robolectric, phone, unfolded and landscape sizes) before install, while downloading, waiting
  for the client and ready, and the 3.3.5a launcher with its switch.
- **Not verifiable without a phone and the client**: the game itself (the Turtle client on
  Wine/Box64), making the server's data from a real 1.18.1 client, the world server past the DBC
  load, offline play end to end, and the launcher on the phone.

### Sources

- Tortoise-WoW: https://github.com/tortoise-wow/tortoise-wow (GPL-2.0), pinned in
  `turtle/server-version.env`
- tortoise-docker: https://github.com/Nescabir/tortoise-docker (its source fork, Shyalya/tortoise-wow,
  no longer reachable)
- ACE: https://github.com/DOCGroup/ACE_TAO (release pinned with its SHA-256)
- TortoiseBots: https://github.com/Sagiroth/TortoiseBots; TortoiseBotsManager:
  https://github.com/Sagiroth/TortoiseBotsManager (both pinned in `turtle/server-version.env`)
- warperia.com's Vanilla add-ons: https://warperia.com/vanilla-addons/
- The shutdown: https://www.pcgamesn.com/world-of-warcraft/turtle-wow-cease-and-desist,
  https://legalclarity.org/blizzard-lawsuit-against-turtle-wow-settlement-and-shutdown/,
  https://gigazine.net/gsc_news/en/20260421-turtle-wow-down/
- turtlecraft.gg (its stylesheet, for the theme)
- OctoWoW: https://octowow.st/ (its client: https://dl.octowow.st/download/client.torrent; its public
  repositories: https://octowow.st/git/explore/repos; its launcher:
  https://octowow.st/git/OctoWoW/OctoLauncher)
- Turtle WoW's successor servers: https://turtle-wow.fandom.com/wiki/List_of_Turtle_WoW_clones
- The 1.17.2 leak's repacks: https://chroniclecoders.com/games/world-of-warcraft/repacks/turtlewow-1172-repack-with-partybots-and-battlebots,
  https://wowprivateservers.vercel.app/news/turtle-wow-source-leak/
- Almendra (SIL OFL): https://github.com/google/fonts/tree/main/ofl/almendra
