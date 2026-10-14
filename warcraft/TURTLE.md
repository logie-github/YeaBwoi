# Turtle WoW — Vanilla+: the brief

The app's second game: Turtle WoW, played on the phone against its own local realm, next to (and
completely apart from) the 3.3.5a game with its ChromieCraft realm and Wrath Remix. The user's
manual is `README.md` ("Turtle WoW"); the 3.3.5a game's brief is `PLAN.md`.

## What the user asked for

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

## What was found (October 2026)

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
- turtlecraft.gg's look (its stylesheet): page `#100d0c`, the gold-to-coral gradient `#f1c22d` →
  `#ff7757` on buttons, links and borders, orange `#f89d41`, uppercase Fontin Sans headings,
  Friz Quadrata in the menu, thin gold rules fading out both ways.

## Decided

- **A native build, not Docker**: Tortoise-WoW and ACE compiled for arm64 on musl (Alpine) like
  the ChromieCraft realm (`scripts/compile-tortoise.sh`, three small portability patches in
  `turtle/patches/`, `-march=native` replaced by a baseline `armv8-a`), packed with its own
  MariaDB, the system call shim and a seed database with Tortoise's databases made and its world
  imported (`scripts/package-tortoise.sh`). **Not in the APK**: CI publishes it as its own
  prerelease, `turtle-server-<hash>`, once for each version of its inputs; each APK names the one
  it's made for with its size and SHA-256, and the app downloads it on Install (resumable, checked).
- **The client is never downloaded.** Turtle's own CDN mirrors still appear in the manifest
  Tortoise ships, but the court order bars distributing that client, so the app doesn't fetch it.
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
  whole launcher is Turtle's (`turtle/ui`): the map with warm motes, the logo, Press start, each
  component on its own line with its own button, and Realm, Accounts, Settings, Updates, Repair &
  backups, About, Uninstall. The 3.3.5a launcher is unchanged apart from the chip and the tile.
- **Bots**: none, because the core has none (Settings and About say so). Offered instead: XP,
  money, reputation and rest rates, and the new characters' level, all checked against Tortoise's
  `mangosd.conf.dist.in`.
- **Updates**: the realm package is pinned per app version (never an incompatible one); a newer
  realm comes with an app update and is put in with the accounts and characters kept (backed up
  first) and the new database updates applied at the next start.

## Where it stands

**First version: built and tested off the phone; nothing run on a phone.**

- Checked here (an x86_64 machine): Tortoise-WoW and ACE compile on musl with the three patches;
  the package builds (52 MB, 461 MB installed; the seed's world has 283 tables and 12,751 creature
  templates). Its test (`scripts/test-turtle-server.sh`) passes every functional check: Turtle's
  MariaDB from the seed on its own port; an account made the app's way logs in with the 1.18.1
  (7272) protocol and gets the realm list (Tortoise WoW, 127.0.0.1:8090), and a wrong password
  doesn't; the world server applies all 151 of Tortoise's database updates and stops where it needs
  the DBC files (CI has no client data; header-only stand-in maps get it past its map check); a
  backup restores a deleted account; nothing is made beside `files/turtle`. The test found and
  fixed one fault: Tortoise asks staff accounts for a PIN by default, so the app's first account
  (the realm's administrator) couldn't log in (`ForcePinAccountRank`, in Turtle's `ctl.sh`). The
  system call check only means something on arm64 (on x86_64 it flags the old calls arm64 doesn't
  have): that's CI's.
- JVM tests (37 new, 157 in all, passing): the guard (outside paths, the other game's folders,
  links that lead out, links in a Wine prefix), a scan of the app's sources (every folder the
  3.3.5a side makes is protected; no raw delete, rename or move in Turtle's code; no reference to
  the other game's storage), the server script's folder and ports and its refusal of another
  folder, the client and DBC manifests, the password hash (MaNGOS's ADMINISTRATOR vector), the
  install plan, resumed, corrupt and cancelled downloads, the client's settings, Tortoise's log
  lines and settings.
- The branch merges into the Wrath Remix line's branch without a conflict.
- **Not verifiable without a phone and the client**: the game itself (the Turtle client on
  Wine/Box64), making the server's data from a real 1.18.1 client, the world server past the DBC
  load, offline play end to end, and the launcher on the phone.

## Sources

- Tortoise-WoW: https://github.com/tortoise-wow/tortoise-wow (GPL-2.0), pinned in
  `turtle/server-version.env`
- tortoise-docker: https://github.com/Nescabir/tortoise-docker (its source fork, Shyalya/tortoise-wow,
  no longer reachable)
- ACE: https://github.com/DOCGroup/ACE_TAO (release pinned with its SHA-256)
- The shutdown: https://www.pcgamesn.com/world-of-warcraft/turtle-wow-cease-and-desist,
  https://legalclarity.org/blizzard-lawsuit-against-turtle-wow-settlement-and-shutdown/,
  https://gigazine.net/gsc_news/en/20260421-turtle-wow-down/
- turtlecraft.gg (its stylesheet, for the theme)
