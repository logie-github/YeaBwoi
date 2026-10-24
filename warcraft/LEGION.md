# Legion — 7.3.5: LegionCore and the stock client

**Where it stands (October 24, 2026).** The app's Legion is **LegionCore 7.3.5** running natively on
the phone, with the **stock 7.3.5.26972 client**, built from nothing at the user's request:
"Remake legion if you have to but make it work. Just a clean install, clean client, LegionCore, no
SPP, just completely restart and wipe the current legion." The Single Player Project version
(`SPP.md`, a Windows server repack run under Wine) is gone from the code, the docs and, on first run,
from the phone. What was tested here and what wasn't is under "Evidence". **No part of this has run on
a phone**, and nothing here says the Legion client plays under Box64: it is the open question
([box64 #3061](https://github.com/ptitSeb/box64/issues/3061), below).

The research behind the choice of core (Felsong's own server is closed; LegionCore is the closest
public one) is in `FELSONG.md`. The code keeps the package name `com.logie.warcraft.felsong`, the
folder `files/felsong` and the preference key `felsong` it was made with; the user sees "Legion".

## The pieces

| What | Where |
| --- | --- |
| The realm: LegionCore at the pinned commit with the app's patches (arm64, musl, clang) | `felsong/server-version.env`, `felsong/patches/`, `scripts/fetch-legioncore.sh`, `scripts/compile-legioncore.sh` |
| The package: programs, libraries, MariaDB 11.4, SQL, a seed database with LegionCore's world and hotfixes imported | `scripts/package-legioncore.sh`, `felsong/server/seed.sql`, `felsong/server/ctl.sh` |
| The test of the package, the way the phone runs it | `scripts/test-legion-server.sh`, `felsong/server/login_check.py` |
| CI: compile, package, test, publish as `legion-server-<hash>`; the APK is told its size and SHA-256 | `.github/workflows/build-warcraft.yml` (job `legion-server`) |
| The app: install, server, accounts, game, launcher | `android/app/src/main/java/com/logie/warcraft/felsong/` (`FelsongJobs`, `FelsongServer`, `FelsongGame`, `ui/LegionHome`, `ui/LegionDialogs`) |
| The client: where it comes from, what is taken from it | `felsong/core/FelsongClient.kt`, `felsong/core/FelsongCasc.kt` |
| Removing the old SPP Legion from a phone | `felsong/LegacySpp.kt`, `felsong/core/FelsongLegacy.kt` |

Everything of Legion is in `files/felsong`, through `SafeFs` (the folder guard): its realm, MariaDB
(port 3312), configs, logs, the client, the server data, backups. The Battle.net server uses the
client's own ports 1119 and 8081; the world server 8095 and 8096. None of the other games' ports or
folders is used (`FelsongIsolationTest`).

## The client

The 7.3.5.26972 client is a Battle.net installation: the launcher's record (`.build.info`), the
build's configuration, and the CASC storage (`Data/data/*.idx`, `data.000`…`data.039`, about 42.7 GB).
Blizzard serves no 7.x client any more. The one complete copy the app knows is a 42,288,827,614-byte
zip (`FelsongClient.SOURCE_URL`, HTTP only: `res/xml/network_security_config.xml` allows that host and
no other). Its directory was read by HTTP ranges on October 24, 2026: 1,371 files, `.build.info`
saying 7.3.5.26972, the 40 `data.NNN` files deflated (they barely shrink).

**The app downloads what the game needs from it** (`FelsongJobs.downloadClient`): `.build.info` and
everything under `Data/` (`FelsongClient.wanted`), one pass over the zip with `ZipDownload.extractAll`
(each file checked against the zip's CRC and size, resumed at the first file not complete, kept
between tries and across a closed app). The rest of the zip isn't taken: `Utils/` (the launcher's
browser), `Launcher.db`, DLLs, `Wow.exe`.

**`Wow-64.exe` is not in the zip as a file** (the zip has the 32-bit `Wow.exe` loose), **and the loose
`Wow.exe` isn't the stock one**: the install manifest of the build lists `Wow.exe` with content hash
`40917da496bfb63f861f5527869db6e6`; the zip's file hashes to `ce5ad36289c8b33b753000d205267367`
(changed by whoever made the zip). So the app takes the program from where the build keeps it,
`FelsongCasc`:

1. `.build.info` → the build key `3b0517b51edbe0b96f6ac5ea7eaaed38` → `Data/config/3b/05/…`, the
   build configuration (the install manifest's and encoding file's keys);
2. the install manifest (`IN`, in the storage; BLTE) lists `Wow-64.exe` for `x86_64` with content key
   `6ff2a79bf3fa89b146dcce93fd0d8ac8`, 27,600,872 bytes;
3. the encoding file (79 MB, read through its page index, one page and the blocks holding it) maps that
   content key to the storage key `725ce99f556931fc348c9aa76751a49d`;
4. the local index (`.idx`, version 7, the newest of each of the 16 buckets) says it is in `data.000`
   at 204,075,630;
5. its BLTE blocks are checked (each block's MD5), decoded and written, and the **MD5 of the result is
   compared with the build's content key**. A mismatch deletes the file and fails.

Tested on the real client's storage (below): `Wow-64.exe` came out with MD5
`6ff2a79bf3fa89b146dcce93fd0d8ac8` and `<Version> 7.3.5.26972`, the program LegionCore's connection
patcher then found all six of its patterns in.

A client the user brings (a folder or a zip) is treated the same way: its `Wow-64.exe`, if it has
one, is compared with the build's hash; a different one is renamed `Wow-64.exe.not-stock` and the stock
one takes its place. Where the storage can't be read, the program there is used as it is.

## The realm

LegionCore 7.3.5 (The Legion Preservation Project, `fed58f7`, April 2025), GPL-2.0, for client
26972, with its world and hotfixes databases (the project's release of October 23, 2024, pinned by
SHA-256). `scripts/compile-legioncore.sh` cross-compiles it for Linux arm64 on musl in Alpine 3.22 with
clang (1,791 build steps, about an hour on 4 cores; natively on CI's arm64 runner) with the patches in
`felsong/patches/` (G3D's x86 assembly, CascLib, gSOAP, fmt, rapidjson NEON, musl's `cpu_set_t`,
clang's `DEBUG`, `NULL` for numbers in three scripts). Built: `bnetserver`, `worldserver`,
`mapextractor`, `vmap4extractor`, `vmap4assembler`, `mmaps_generator`, `connection_patcher`.

`scripts/package-legioncore.sh` packs them with their libraries, Alpine's MariaDB 11.4, the SQL, the
configs, the Battle.net server's certificate and a seed database folder (the four databases made, the
world and hotfixes imported, `seed.sql`'s realm "Legion" on 127.0.0.1:8095 for build 26972, the
public `GM@GM` account removed): 112 MB compressed, 1.55 GB unpacked. The app makes the databases from
the seed (a copy), and the world server applies LegionCore's SQL updates when it starts.

**Accounts** are LegionCore's `auth.account` rows with an e-mail as the name and the Battle.net
password hash the REST login checks (`FelsongAccountRules`, checked against the core's
`LoginRESTService::CalculateShaPassHash`).

**The server's data** (`dbc`, `maps`, `vmaps`, optional `mmaps`) is made on the phone from the client
by LegionCore's own extractors (`ctl.sh maps|vmaps|mmaps`); the job runs in the foreground service.
**The client for the realm**: `connection_patcher` writes `Wow-64_Patched.exe` and the certificate
bundle it trusts (`ctl.sh patch-client`); the game's start copies the bundle into the game's Windows.

**The game** runs `Wow-64_Patched.exe` in its own Winlator container, with the Box64 the app builds
with the signal-context fix (`0.4.4-legion`, Winlator patch 0009, `scripts/build-box64.sh`), a log of
what Wine and Box64 print (patch 0008; `logs/game-session.log`), Direct3D 9 first and a start whose
graphics fail moving on to Direct3D 11, then OpenGL (`FelsongGraphics`, read from the client's
`Logs/gx.log`), and Vortek or Turnip as the Vulkan driver. Its first start uses Legion's lowest
graphics preset.

## Evidence (October 24, 2026)

On the PC this was built on (x86-64, qemu-user for the arm64 programs):

- **Compile**: LegionCore compiled to the end for arm64/musl (it had never been recorded as finished).
- **Client**: `FelsongCascTest` (Kotlin, `LEGION_CASC_TESTBED`) takes `Wow-64.exe` out of the real client's storage — the
  `.build.info`, build configuration, the 16 index files and the first 225 MB of `data.000`, read from
  the zip by HTTP ranges — with the build's hash. The same test builds a storage of its own (index
  versions, BLTE with plain and deflated blocks, multi-page encoding file, install manifest) and checks
  damaged data, short files and a mismatching hash are refused.
- **Patcher**: the arm64 `connection_patcher` patched that `Wow-64.exe` through `ctl.sh patch-client`:
  all six patterns found, `Wow-64_Patched.exe` the same size, the certificate bundle written where the
  app looks for it.
- **Package** (`scripts/test-legion-server.sh`, under qemu): MariaDB starts from the seed; the four
  databases are there (34, 146, 281 and 813 tables; 101,838 creature templates); the realm list says
  Legion, 127.0.0.1:8095, build 26972; **an account made as the app makes it logs in to the Battle.net
  server's REST service over TLS with the server's own certificate and gets a login ticket**, and a
  wrong password gets none; **the world server connects to all four databases, applies LegionCore's
  database updates** and stops where it needs the DB2 files (the game's data, which can't be in CI);
  the backup restores a deleted account; the tools start. The test found one bug in the earlier
  Felsong work: the database account couldn't shut MariaDB down (`mariadb-admin shutdown` was denied);
  `seed.sql` now grants it.
- **Mapextractor** (arm64) opens the real storage (`Opened casc storage`) and then, with only a part
  of `data.000` there, fails to find files, as it must.
- **Launcher**: `LegionHomeTest` (Robolectric, portrait and landscape) renders the screens, and the download button starts Legion's own installer service with the right job.
- **Old SPP data**: `FelsongLegacyTest` removes `spp-legion` from the app's files and the external
  storage without following a link out of it or touching `felsong`, `turtle` or `server`.

**Not tested** (and so not claimed):

- The check that no program makes a system call Android forbids apps: it runs on CI's arm64 machine (under qemu it would trace the emulator).
- Removing the old SPP Windows container from a phone (its folder removal is tested; the container's isn't).
- Anything on a phone: the installer's job flow, the launcher screens (compiled, not seen), the
  foreground service, Winlator, the game's start.
- The data tools on a complete real client (needs the whole 42 GB on a machine with the room), so the
  maps, vmaps, DB2 extraction and the time they take on a phone.
- The world server past its database updates (it needs that data): loading the world, listening on 8095,
  a character in the world.
- Whether the Legion client gets past its start-up protection and renders under Box64 on a phone
  (the fix reached a first window on a PC through qemu, with the earlier repack's program).
- Clean world-server shutdown (LegionCore's, not the old repack's), and the cost in memory of the
  realm and the game together on a phone.
- The download of the 42.3 GB client end to end (the directory, the ranges and the CRC check use the
  app's existing, tested `ZipDownload`; the full transfer wasn't made here, only pieces).

## Known limits

- The client's host is a third party's, over HTTP. The app can't prove the files are Blizzard's: it
  checks the zip's CRCs and, for the program, the build's own hash. If the host changes the file, the
  size check stops the download (`felsong_error_source_changed`) and **Add my client** is the way.
- Felsong's own changes (its rates, class changes, Artifact Power rules, vendors, Mythic+, legendary
  systems) aren't in LegionCore; `FELSONG.md` lists what each would take. Settings offered: rates,
  starting level and money, Solocraft.
- The core has gaps (the Legion intro scenario, some world quests): its own open issues.
