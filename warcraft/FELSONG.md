# Felsong — Legion 7.3.5: the brief

The app's third game: Felsong (World of Warcraft: Legion 7.3.5) against its own local realm, kept
completely apart from the 3.3.5a game (ChromieCraft) and Turtle WoW, as Turtle is (`TURTLE.md`).
**Status: research done; Felsong's launcher is in the app with its look and music, and says it
isn't playable (no Play); its realm (LegionCore) is being ported to the phone and hasn't been built
through; the client hasn't been tried on a phone.** Nothing here is released (the user's word: no
release until it's playable).

## What the user asked for

- Felsong as a **separate, fully working, locally hosted realm**, handled exactly like Turtle WoW:
  its own server programs, client folder, configs, databases, login and world processes and
  ports; installed, updated, started and stopped on its own; chosen from the launcher's game
  switch, with the same install management and Play. **Nothing of the other realms changes.**
- The **Felsong experience**: its rates, class changes, artifacts / Artifact Power / Artifact
  Knowledge, order halls, Mythic+, legendaries and its own legendary systems, world quests, its
  vendors and currencies, cross-faction play, account-wide systems and quality of life; **solo**,
  with configurable scaling or NPC companions; characters kept; playable **without Felsong's
  servers**.
- The core: Felsong's own if public; if not, the closest Legion 7.3.5 emulator, with
  [LegionCore 7.3.5](https://github.com/The-Legion-Preservation-Project/LegionCore-7.3.5) named as
  the fallback, with Felsong's changes made on it. World server, login server, databases, the
  server's data, a 7.3.5 client and a way for it to connect to localhost. **Felsong's own launcher
  is not assumed to connect to a local realm.**
- **Honesty**: nothing incomplete called working; no placeholder servers, simulated connections
  or screens that can't really start the game; what can't be reproduced exactly, said so.
- **Downloads in order** (October 16, 2026), as the other realms' launchers offer theirs: the
  launcher lists every file Felsong needs as numbered steps, 1 to N, in the order they're needed,
  each with its download where one is published, its size and whether it's done.
- **Theme** from [felsong.gg](https://felsong.gg/en/presentation), the user's logo ("Felsong —
  Forge your own destiny — Legion 7.3.5") and Illidan header; Entering Azeroth with the user's big
  picture (2560×1080) and green motes, filling the screen. The art is in `branding/felsong/`.

## What was found (October 15, 2026)

**Felsong itself.** Felsong is run by WoW Freakz on their own closed Legion core (their hiring post
asks for TrinityCore experience); **no source, database or tools are public.** Three realms:
Felsong (2023, ×1.5 XP, start at level 98 / item level 660), **Felsong+** (March 17, 2025: ×10 XP,
×2 reputation) and Felsong Remix (seasonal, December 2025). Its client is "7.3.5" (build not
stated; 26972 is 7.3.5's last), 64-bit only, with its own launcher (`Felsong-64.exe`,
`launcher_felsong-64.dll`); its download (download1.felsong.gg) answers 404. Bots are against its
rules; it has no solo modes. What Felsong+ documents (news posts 530, 533, 542, 543 and its
changelog): account-wide Artifact Power (the highest applies to all artifacts), instant AP
tokens, a third relic slot at 110, flying at once, every allied race free, Order Hall teleports
for every class, a legendary vendor (Wakening Essence: a chosen legendary 2000, a random one
1000; the 7.1/7.2 legendaries), 7.1.5/7.2 legendaries in the drop pool, 35% Warforge/Titanforge
per proc, better tertiary stats, LFR with 1 tank / 2 healers / 6 damage, Mythic Flex 10–20,
Enduring Elixirs (Renown, Soaring Winds, Prosperity, Reawakening), a recipe vendor (Bfaal,
Elunaria), Mythic+ vendors Echoes of Time (Missives, Infusions, Augmentors 865→895) and Echoes of
Reality (cosmetics), keystone edits (Shuffling Sands), the Legion Pocket Portal (+20 timed),
Overflowing removed, Bolstering changed, optional extra affixes; class changes of 10.03.2025
(Shadow Priest, Enhancement, Survival, Subtlety, Mistweaver, Protection Warrior, Destruction);
cross-faction guilds, groups, dungeons, raids and PvP; account-wide titles, mounts, honor and
prestige; on the first realm, pre-Legion flight paths at once, flying in old zones, the Suramar
intro skipped, Artifact Knowledge 55.

**The core: LegionCore 7.3.5** (The Legion Preservation Project, `fed58f7`, April 19, 2025; quiet
since). For client **7.3.5.26972**, GPL-2.0, from the 2020 leak of UWoW's core (itself from an old
TrinityCore). Programs: `bnetserver` (Battle.net login: TLS on 1119 with its own certificate,
valid to 2036, and a REST login on 8081), `worldserver` (8085, instances 8086), MySQL/MariaDB
(`auth`, `characters`, `world`, `hotfixes`). Its world and hotfixes databases are a release
(`LegionCore_full_735.26972_2024_10_23.7z`, 33 MB; world 237 MB of SQL: 408,427 creatures,
30,317 quests, 71,889 smart scripts). About 680,000 lines of scripts: every Legion dungeon and
raid, the Broken Isles, Argus; Mythic+ with affixes, order halls, artifacts, world quests,
scenarios. Open issues show gaps (the Legion intro scenario, some Azsuna world quests, creatures
that don't move, start-up database errors).
- **Doesn't build for arm64 as it is**: SSE2 forced on (its GCC settings and rapidjson), x86
  assembly in its G3D (cpuid, rdtsc, atomics). TrinityCore's current tree has the fixes for each.
- **musl**: four places, two of them patches this repository already carries (AzerothCore's
  `__cpu_mask`, Tortoise's `stat64`), plus its old gSOAP (`gethostbyname_r`, `strerror_r`) and
  CascLib (tools only).
- Boost 1.74–1.86 (1.87 removed `io_service`); its MariaDB client library must be the very version
  it was compiled with; OpenSSL 3's legacy provider for the world socket's RC4 (`OPENSSL_MODULES`,
  as the other realms).
- **Its databases import into MariaDB 10.11** with one fix (`utf8mb4_0900_ai_ci` → 
  `utf8mb4_unicode_ci`, six places in `auth.build_info`), checked: about 1.4 GB on disk.
- **Solo**: Solocraft is built in (dungeon scaling by group size, per class; off by default), and
  the dungeon finder can queue one player. **No playerbots** in it; the Legion bot projects found
  (LegionPlayerBot 2022, SylvaniaCore's PlayerBots and Mercenaries, LegionBotAI 2026) are
  unproven or from other forks.
- Other Legion cores: LegionCore-Reforged (active in 2026, GPL-3.0, removed Solocraft),
  SylvaniaCore (bots and hired companions, French realm), TrinityCore's 7.3.5 tag (2018, far less
  content).

**The server's data** (`dbc`, `maps`, `vmaps`, `mmaps`, `cameras`, `gt`) is made from the client
with LegionCore's own extractors (formats changed in October 2024: older UWoW data loads but is
wrong). None is published. Estimate 10–20 GB; mmaps take hours.

**The client and the connection.** A stock 7.3.5.26972 `Wow-64.exe` is patched by the core's
`connection_patcher` (the portal, the Battle.net RSA key and certificate fingerprint, the
versions URL) and pointed at `127.0.0.1` in `WTF/Config.wtf`; the patcher writes a certificate
bundle into Battle.net's cache in the Wine prefix. 7.3.5 has 32-bit `Wow.exe` too, but the 32-bit
7.3 client never ran under Wine; 64-bit it is. 40–67 GB depending on the copy (Felsong's: 48.9 GB).

**The client on a phone — the blocker.** **No report anywhere of Legion (or any later x86 WoW)
running on Android** through Winlator, Box64 or FEX. **Box64 fails on every WoW client from 7.x
on**, private servers' included: [box64 #3061](https://github.com/ptitSeb/box64/issues/3061),
open, no fix ("Game initialization failed"; since Box64 0.4.0 a black window, exceptions every
second). Clients 1.x to 6.x run. The cause is Blizzard's anti-tamper layer added in 7.3 (exception
semantics an emulator must match exactly; Wine fixed its part in 2018), not missing instructions.
The app's runtime already has the newest Box64 (0.4.4, August 2026); nobody has published a test
of 7.x on it. FEX fails the same way.

**Memory and storage on the phone (estimates, not measured):** client 2–3 GB, Wine/Box64/DXVK
0.5–1 GB, world server 2–4 GB, MariaDB 0.3–1 GB: about 5–8 GB. Storage: the client 40–67 GB, the
server's data 10–20 GB, the databases 1.5 GB.

## What can't be reproduced exactly

- **Felsong's core, scripts, database fixes and exact numbers**: closed. Each documented Felsong+
  change above can be made on LegionCore (rates and cross-faction are settings; vendors,
  currencies and drop pools are SQL; account-wide AP, Order Hall teleports, keystone changes,
  elixirs and class changes are code or spell data), each one an approximation from its
  description.
- **Felsong's launcher, website, store, Remix seasons and its other players.**
- **New spells and items the client doesn't have** only as far as Legion's hotfix data allows.
- **NPC companions**: no proven Legion bots. Solo scaling (Solocraft) is real.

## The plan

1. **The client first (the gate).** In the app: import the user's own 7.3.5.26972 client, patch
   `Wow-64.exe` for a local realm, and start it with the app's Wine and Box64. **If it reaches the
   login screen** on the phone, the rest is worth building. **If it doesn't, Felsong stops there**,
   said plainly, until Box64 fixes #3061 (or the realm is played from a PC on the same Wi-Fi, if the
   user wants that).
2. **The realm**: LegionCore patched for arm64 and musl, packed like Turtle's (`bnetserver`,
   `worldserver`, MariaDB, the databases made in the package, `ctl.sh`), in `files/felsong`, on
   ports of its own (the other realms use 3724, 8085, 8086 and their MariaDB ports).
3. **Its data**: imported from a PC (as Turtle's import), or made on the phone (hours).
4. **The launcher**: `com.logie.warcraft.felsong`, the same as Turtle's (install, server, game
   files, accounts — Battle.net style, an e-mail as the name —, rates and solo scaling, Play), in
   Felsong's look. Its setup as numbered steps (the user's ask), each with its source:
   1. **The client**, 7.3.5.26972 (64-bit): from a folder, a zip, or a link the user pastes,
      checked against 26972's own files (`Wow-64.exe`'s size and hash) before it's kept. No link
      is built in: Blizzard serves no 7.x client (its CDN drops a build within days or weeks of
      the next, and 26972 was replaced in 2018), and every 26972 download found is a third
      party's copy, not Blizzard's.
   2. **The realm**: this repository's own release of the LegionCore package (as Turtle's
      `turtle-server-<hash>`), one tap, size and SHA-256 checked.
   3. **The world and hotfixes databases**: LegionCore's own release
      (`LegionCore_full_735.26972_2024_10_23.7z`, 33 MB, `server-version.env`), downloaded and
      checked, unless the realm's package already carries them.
   4. **The server's data** (dbc, maps, vmaps, mmaps, cameras, gt): made from the client
      (nobody publishes it), on the phone (hours) or imported from a PC's zip.
   5. **The client patched for the local realm** (`connection_patcher`): done by the app, no
      download.
   6. **An account**, then Play.
5. **Felsong+'s systems**, one by one, each listed as done and tested or not.
6. Built and released by CI when the user says (Actions minutes are used up until about November
   1, 2026; the arm64 builds meanwhile run on this machine).

## Done

- Felsong's look (`com.logie.warcraft.felsong.ui.FelsongTheme`): felsong.gg's palette (olive
  `#1e2117`, fel `#9ccb2d`, lime `#bae449`, headings white to `#c1e46f`), Marcellus (SIL OFL) in
  place of the site's Friz Quadrata, the user's logo, header and loading art, a spinner on the
  logo's diamond, rising fel motes. The Entering Azeroth screen (`FelsongLoading`) fills the
  screen with the big picture, its logo bobbing as on felsong.gg's loading screen. Not reachable
  until Felsong's game is in the app.
- Felsong in the launcher: the game switch is a picker (Wrath of the Lich King, Turtle WoW,
  Felsong), each game in its own look. Felsong's launcher (`FelsongHome`) has the look above,
  Kingdoms Will Burn as its music (`res/raw/felsong_title_music.ogg`, −6.5 dB), and says plainly
  that there's no Play yet, why (the realm, the client and box64 #3061), and that nothing of the
  other games changes. Released in no build yet.

## In progress: the realm (step 2)

- LegionCore pinned (`felsong/server-version.env`: `fed58f7`, its world and hotfixes release by
  SHA-256, client build 26972), with the app's patches for arm64, musl and clang 20
  (`felsong/patches/0001`–`0008`: G3D's x86 assembly, CascLib's and G3D's `stat64`, gSOAP on musl,
  fmt's compile-time checks, rapidjson's NEON, the CPU mask and `LockedMap::swap`, clang's
  `DEBUG=1`, `NULL` where a number is meant in three scripts).
- `scripts/fetch-legioncore.sh`, `scripts/compile-legioncore.sh` (clang in Alpine, natively on arm64
  or cross-compiled into an arm64 sysroot; hours), `scripts/package-legioncore.sh` (with Alpine's
  MariaDB and the databases made from LegionCore's release, `felsong/server/seed.sql`), and
  `felsong/server/ctl.sh` on ports of its own (MariaDB 3312, Battle.net 1119 and 8081, world 8095
  and 8096).
- **Not yet:** a complete compile (patch 0008 fixes errors in the game's scripts, so a compile got
  at least that far; none is recorded as finished), the package, a test of the realm, its CI job, and anything in the app that installs or starts it.
