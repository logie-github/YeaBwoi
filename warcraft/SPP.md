# Legion — 7.3.5 (Single Player Project)

This replaces the Felsong launcher section at the user's request. The requested project is
[SPP AshamaneCore](https://www.ownedcore.com/forums/world-of-warcraft/world-of-warcraft-emulator-servers/wow-emu-general-releases/652544-single-player-project-ashamanecore-legion.html),
the original Legion distribution for build **26972**. Existing Felsong data is preserved;
SPP's downloaded files use a separate installation directory. Other expansions keep their files.

The installer, realm controls and linked-account management are integrated into the existing
application. No completed phone play test is claimed; Box64's
[Legion startup issue](https://github.com/ptitSeb/box64/issues/3061) remains open.

## Inspected external sources

The [repack mirror](https://drive.google.com/file/d/1AUYiD-2NpamLFzxJDBNVRcO7FBg9YwIi/view)
was downloaded on 2026-10-22 without running its binaries: 5,444,369,208 bytes, SHA-256
`c161a7289b907ba86471374253f7b7844a6f49377722ca706da3b40f90d077e0`.
It expands to 23,506,837,301 bytes across 127,483 files. It includes SPP Legion and a separate
SPP BestCore, so installation selects `SingleCore_Legion/spp-legion` explicitly.
One of its folders, `SingleCore_Legion/spp-bestcore/.git`, is marked hidden: 7-Zip lists its
attributes as `HD`, not `D`. Builds up to 2026-10-23 read only a leading `D` as a folder and
stopped with "Missing archive entry: …/spp-bestcore/.git"; the listing reader now finds `D`
anywhere among the Windows letters (checked against the archive's full 7-Zip listing: all 4,151
folders and 123,332 files classified correctly).

SPP uses `Server/bin64/bnetserver.exe` and `worldserver.exe`, with the actual realm settings
`Realms/1/Settings/login.conf` and `world.conf`, working from `Realms/1`.
Its initialized MariaDB 10.1.30 is `Server/Database/bin/mysqld.exe`; `start.bat` selects
`Server/Database/my-large.ini` with `--console --standalone`. The data directory is
`Server/Database/data`; schemas include `realmd`, `characters`, `world`, `hotfixes`, `mysql`.
The publisher's port 3310 is changed to SPP port 3312, keeping it separate from ChromieCraft.
The server's maps and DB2 files are included in `Server/Data`, relative to the realm's working
directory. The archive also includes solo-scaling settings and its patched client executable.

The [publisher's full client](http://ashamanecore.com/dicpics/LegionClient.zip) was inspected
using validated HTTP byte ranges on 2026-10-22: **42,288,827,614 bytes**, 1,371 ZIP entries,
42,925,693,337 bytes expanded, including 42,659,087,855 bytes of CASC files. The ZIP contains
`Wow.exe` (7.3.5.26972), SHA-256
`edb91d59e530644b76c7ad1a665203506d0b4b58415baa6a9fe67c38a3ebf33d`.
Its `.build.info` declares 7.3.5.26972, Build Complete 1 and both Windows architectures;
SHA-256 `978c542c90467db98335a8263a04c209e3b5881d37df6c2c5906a2fec2c68d5a`.
The central directory, build metadata and executable were read, and those members' ZIP CRCs
were validated. No full archive digest was supplied by the publisher; the downloader enforces
its inspected length, calculates a transport SHA-256, and extraction checks all ZIP CRCs.
These checks detect incomplete/damaged transfers, not publisher authenticity. The publisher
serves HTTP only; Android permits cleartext for this specific download host.

The previously listed 1.1 GB mini-client was also downloaded and inspected. Its
`data-eu.hijosdeltiempo.com` and `data-us.hijosdeltiempo.com` CDN hosts no longer resolve;
Blizzard no longer supplies that old build configuration at the tested endpoint either.
It is therefore not offered by setup. SPP requires at least 40,000,000,000 bytes of CASC chunks,
in addition to version/index checks, to reject the retired partial client. This size threshold
is not a claim of complete asset coverage for arbitrary imports.

The repack's `Addons/Patched_Exe_26972/SPP_Wow-64.exe` reports 7.3.5.26972, SHA-256
`be484c9a3bd8e5de173e9a8e185a9b6636406d69a9122987f6f2c1d512b2bec5`.
Setup installs that 64-bit local-play executable beside the full archive's 32-bit `Wow.exe`;
the original remains untouched. Older 26654 executables at the outer repack root are unsuitable.

No SPP game client, database or server binary is committed or included in the APK. Inspection and native
runtime checks use disposable CI storage. Android Wine/Box64 client compatibility still requires
testing on the phone, independently from Windows server validation.

## Installation and isolation

The legacy `felsong` environment preference selects the new SPP launcher. All new files are
under `files/spp-legion` (or the selected external app directory); existing `files/felsong`,
Wrath, Turtle and TWW files are preserved. The normal Download & Install action uses inspected
archive sizes and the repack digest, validated resume, automatic extraction, staging and transactional
placement from `local/LocalInstallJobs`, shared with Draconic. SPP's server data is included;
there is no separate map download. Optional folder/archive import follows the same verifier.

`SppIntegration` selects the actual 64-bit SPP binaries and realm 1 settings rather than its
32-bit alternative, other realm templates or BestCore. The native executables report
`SingleCore_Legion rev. 404d478c1cc2 2018-12-30 (AshamaneCore-dmca branch)`.
The old authentication database has no `build_info` or `battlenet_components` table. Its known
fixed-build release is therefore bound to the inspected core executable SHA-256 values:
`bnetserver.exe`: `8bb9eea52f8f7421b75f9fd92311bea74ef3f6d001a1986dd0cfee96449675c3`;
`worldserver.exe`: `d54437a169b9bc00d56e809ace78629e78cbf2fdb7008c5feca9374df25b7cf5`.
A different core does not inherit that compatibility claim. Draconic retains its separate live
`build_info` check, and changing a realmlist routing row never establishes build compatibility.

`local/WindowsRealmServer` owns actual database/authentication/world processes and status,
safe shutdown attempts, retained-session failures and an explicit Force Stop. Only one Windows
realm can own Winlator's active Wine home. Each realm has separate installation/service state;
downloads and active sessions retain the app's background protections. Startup returns the
launcher to the foreground while Windows processes load, so server progress stays visible.

The SPP client preparation copies the inspected `SPP_Wow-64.exe` beside the original client executable, preserving
the latter and backing up an existing modified local-play copy. This is explained before setup.
The managed-session window listener ignores server consoles before activating game login and
touch controls. Client configuration keeps the portal on 127.0.0.1 and applies the app's existing
graphics/touch conventions. CASC chunks are mutable storage; length and ZIP CRC checks validate their initial transfer,
while receipts do not report ordinary CASC changes as damage.

Accounts use the real `realmd.battlenet_accounts` and `account` tables. Battle.net password hashing
matches the publisher source's `BattlenetAccountMgr::CalculateShaPassHash`; a game account named
`<bnet id>#1` is linked with `battlenet_account`/`battlenet_index` and the SHA-1 game-account hash.
SQL credentials are encoded as hex literals, requests are serialized and temporary query files
are removed. Solocraft and rate settings edit the imported world configuration while stopped.

## Native runtime verification (2026-10-22)

The original pinned repack was extracted in disposable Windows CI. Its MariaDB and bnetserver
started, a newly created linked account authenticated over the actual HTTPS login service and
received a login ticket, and the world server opened port 8095 with the supplied data.
The database shut down with exit code 0. **The old publisher worldserver exited with access
violation 0xC0000005 after `server shutdown 1`. Clean world shutdown is not verified.**
The app checks component exit markers and reports abnormal shutdowns; closed ports alone
never establish a clean save. This integration does not silently replace the publisher core.
Android/Wine shutdown behavior and gameplay still need a real phone test.

The [native startup/account run](https://github.com/logie-github/YeaBwoi/actions/runs/39783461057)
provides that runtime evidence. A repeat on 2026-10-22 was blocked before extraction by
Google Drive's download quota, not a new core failure. No alternate original-release mirror
was verified. The app detects quota pages, reports the error and retains resumable progress;
Retry may need to wait for the external host's quota reset. Do not claim that downloads are
currently unrestricted or that the later blocked run completed native validation.

## Download throughput

The client source opts into four HTTP range connections; other sources keep their existing
single-connection/Google Drive handling. The source must supply a strong ETag and a known
length. Every chunk must return the exact requested range, total length and same ETag, without
content encoding. Unsupported ranges or connection-limit responses fall back to serial mode.

The old `client.part` stays a contiguous prefix. Up to four 32 MiB chunk files retain partial
network transfers, and a validator-bound sidecar prevents reusing chunks from a changed source.
Completed chunks are appended in order and removed, so assembly needs at most 128 MiB extra,
not another complete client copy. Pause, process death and upgrades preserve resumable data.
Final length/SHA-256 validation and ZIP CRC extraction remain in place.

A bounded 16 MiB comparison on 2026-10-22 measured 23.7 and 27.5 MB/s with one connection,
and 35.7 MB/s with four, from the build runner. All three downloads had identical SHA-256.
This is evidence the host supports concurrent requests, not a phone-speed guarantee.

## The database under the app's own Wine (2026-10-23)

On a phone, Start stopped with "The Windows session ended while starting database." The pinned
repack's `spp-legion` server was then run here the way the app runs it, with the app's own Wine
10.10 (from its runtime, natively on x86_64, so without Box64): a prefix from the runtime, `S:` the
server, `R:` the realm's folder, `explorer /desktop=nogui` holding `winhandler.exe` → `cmd /d /k`,
then `R:\runtime\database.cmd` exactly as `TwwSession.launch` writes it, with `my-large.ini` as
`configureLocal` leaves it (port 3312, bind 127.0.0.1, `S:/` paths). MariaDB 10.1.30 was ready
for connections on 3312 in 2 seconds, and the session stayed up. So the database start and the
session's command flow are sound under Wine; what ended the phone's session is Android- or
Box64-specific, and unknown.

From this change, a realm's Windows session writes `runtime/session.log` (Winlator patch 0008):
what Wine and Box64 print, and the runtime's exit status (137 is a SIGKILL, usually Android
reclaiming memory). When a session ends while a component starts, the error shows the tail of that
component's log and of `session.log`.

### Under Box64: Winlator's TMPDIR stopped the database

The same run with the app's own Box64 0.4.4 (aarch64, under qemu-user here) as Winlator starts it
(`TMPDIR=/data/data/com.logie.warcraft/rootfs/tmp` among its variables) failed where the native run
had not: `InnoDB: Error: unable to create temporary file; errno: 2`, then `Plugin 'InnoDB' init
function returned error` and exit 1. MariaDB takes its temporary folder from `TMPDIR` before
Windows' `TEMP`, and Windows programs inherit Winlator's Unix `TMPDIR` as is: Wine looked for
`S:\data\data\com.logie.warcraft\rootfs\tmp` (a `+file` trace showed it). Every realm script now
clears `TMPDIR` first (`TwwSession.script`); with that, the database was ready on 3312 under Box64
in 16 seconds (qemu's emulation included).


### The world server stopped itself after loading: its console

With the database and bnetserver up (3312, 1119 and 8081), worldserver loaded the world ("World
initialized in 9 minutes 34 seconds" under Box64 and qemu, 2 minutes 30 seconds under x86-64 Wine)
and opened its REST port (8082), but never its game ports (8085, 8086), and never exited: the same
under Wine on x86-64 and under Box64. `Server.log` ended "Now you ready to play. Halting process...":
with `Console.Enable = 1`, TrinityCore's console thread reads the process's input, which in the
Windows session is no console (Wine's Unix input, at its end), so it stops the world as soon as it
starts. On the way out a static destructor in `common.dll` dereferences null, and the crash
reporter (dbghelp) hangs writing its report (`bin64\Crashes\*.txt`, left empty), so the process
stays without its ports (a `+seh` trace and winedbg's backtraces showed it). With
`Console.Enable = 0` the world server opened 8085 after 156 seconds under x86-64 Wine and kept it
open. The app sets it at every start (`WindowsRealmServer.headlessWorld`) and when it configures
the repack, for SPP and The War Within alike.

### On the phone: the launcher ended the session (status 137)

The app's first failure reports from a phone (1.0.260, issues #40 and #41) gave the session's log:
`Windows session started.`, `esync: up and running.`, then `The Windows runtime (wine explorer)
exited with status 137`, and nothing in `database.log`. The app had killed it: once the session's
screen was up, `WindowsRealmServer.start` put the launcher back in front, but `MainActivity` is
`singleTask` and the session's screen was above it in the same task, and Android ends the screens
above a `singleTask` screen started again (`FLAG_ACTIVITY_REORDER_TO_FRONT` doesn't apply to it).
The screen's `onDestroy` stops Winlator's environment, which kills Wine (SIGKILL: 137). Local
realms' sessions now open in `RealmSessionActivity` (Winlator's screen with a task of its own,
`taskAffinity`), so the launcher coming back only puts the session behind. The games' own sessions
are unchanged.

### The client's start-up protection under Box64 (#3061): the flags in a single step's context

The repack's `SPP_Wow-64.exe` can be started without the game's data: it gets through its
start-up protection, then asks for a language (a list-view dialog), the same with or without
data. Under x86-64 Wine that takes about a minute; it's a quick test of the CPU emulation.
The protection installs a vectored handler, raises privileged-instruction faults (`hlt`) and
then single-steps itself with the trap flag: about 30,000 `EXCEPTION_SINGLE_STEP`s under x86-64
Wine, and 25 privileged-instruction and 2 illegal-instruction exceptions at fixed addresses.

Under the app's Box64 0.4.4 (aarch64, through qemu-user here) the sequence parts from Wine's at
the 36th step: at `0x14000C843` (`je`, right after `add rdx, r11`) Box64 jumps where Wine goes on.
Box64's own register dump at that step shows its flags still deferred (`flags=???Z?P?`), and the
context Wine's handler got had `eflags=00010246`: ZF set, left from the `cmp cx, ax` before
the add. The handler resumes with those flags, the `je` goes the wrong way, and the protection
ends on its deliberate trap at `0x14037B6B0`. The interpreter (`BOX64_DYNAREC=0`) does the same:
the emulated signal's context is built from `eflags` without computing the deferred ones first.

Box64 fixed exactly this upstream after 0.4.4: a01ded93, "[SIGNAL] Synchronized deferred flags
across signal contexts (#4348)" (`CHECK_FLAGS` before the context is built, `RESET_FLAGS` when it's
read back). 0.4.4 with that commit (`warcraft/patches/box64/`) follows Wine's sequence through
the protection (one more wait-loop step at `0x14001193C`, and the `rep stosb` at `0x140010479`
is one step, not 1,750 as on a real CPU, which the protection doesn't mind) to the same two
"World of Warcraft" windows, with Box64's defaults and with the PERFORMANCE preset the app's
sessions use. (The windows stay unmapped here, as Notepad's do under this Box64 with a PC's X
server: Winlator's X server on the phone is a different one.)

`scripts/build-box64.sh` builds it (0.4.4 at its tag's commit, the patches, an arm64 cross
compiler, stripped, its loader at Winlator's path) as `box64-0.4.4-legion.tzst` beside Winlator's
own, in `scripts/prepare-winlator.sh`, which rewrites its path for the app with the rest.
Winlator patch 0009 makes it a built-in version and lets a session ask for it (`box64_version`);
`WindowsRealmGame` asks for it for local realms' sessions (SPP's and The War Within's), so the
games keep Winlator's 0.4.4.

### Wine's cmd and `<"file"`

Every SQL the app runs through `mysql.exe` (the realm's row at each start, the build check,
accounts) was written as `mysql.exe … realmd <"R:\runtime\realm.sql" >"R:\runtime\realm.log"`.
Wine 10.10's cmd says "Invalid name." to `<"…"` and doesn't run the command (`<R:\…`, unquoted,
gives "Path not found."); `< "…"`, with a space, works. On the phone the start would have stopped
right after the database, the realm row unverified. `TwwSession.input` writes it with the space.

### All races and classes

SPP's core (its `game.dll`, checked against AshamaneCore's `legion` branch) shows the allied races
in character creation only to an account with Battle for Azeroth's expansion (7), whatever
`race_unlock_requirement` says and whatever world.conf's `Expansion` is (6, which caps the world,
not the account). The repack's own accounts have 7 (the column's default); the app made its
accounts with 6. It now makes them with 7 and raises older ones at every start
(`SppRealm.LOGIN_STARTUP_SQL`), keeps `race_unlock_requirement`'s rows for races 27-30 without an
achievement (`WORLD_STARTUP_SQL`, as the repack has them), and sets world.conf for Demon Hunters
without a level-70 character (`CharacterCreating.MinLevelForDemonHunter = 0`,
`DemonHuntersPerRealm = 16`) and with nothing that stops or moves a new character. Allied races
start at level 20 (hard-coded) in their home zones; their intro quests and portals to the
capitals are in the database, and the repack's own Lightforged and Highmountain characters
played through them. The world server loads the changed tables without an error. Character
creation itself hasn't been tried in a client. The repack's second realm row ("Haunted Lands",
which this single core doesn't run) is hidden from the client's list (`flag = 3`).

### Reviewed for the phone: stopping, leaving, coming back, crashes

Found by reading the code against the repack and Wine 10.10, and checked here where it could be:

- **Stop.** Wine's `taskkill /IM worldserver.exe` (without `/F`) answers "Could not find process":
  it closes windows, and the servers are console programs without one (tried under Box64). Stop
  now ends the world and authentication servers with `/F` (under Box64 the world's port closed in
  5 s; `world.exit` and `authentication.exit` 1), then shuts MariaDB down with mysqladmin
  ("Shutdown complete", `database.exit` 0). Only the database's exit counts in the result.
- **Leave** (the game's menu) ended the whole session, the realm with it: in a local realm's
  session it now closes the game and brings the launcher back (Winlator patch 0010).
- **Coming back.** The session was out of Recents and the notification opened Winlator's other
  screen: Launch Game started a second client. It returns to the running one now (Return to
  Game), the notification opens the session's own screen, and a game started again in the
  session gets its login again.
- **Theme or font changes** (dark mode at sunset, power saving) made the session's screen anew,
  which ended it: those changes are now the screen's own (`configChanges`).
- **Crashes.** A server that crashes under Wine hangs writing its report, keeping its port: the
  app watches `Crashes\` beside the servers while they start and run.
- **Ports.** The world's second port (`InstanceServerPort`) was the repack's 8086, Wrath Remix's;
  it's 8096 now, set before the profile is read, and checked with the others.
- **Force Stop** threw out of its coroutine (the app ended) when the session had already gone;
  the session's end now releases the installation once its ports close (during a stop too), and
  a Wine process left of an ended session is ended with it.
- **The client.** Its Config.wtf is written before it's checked; Legion's intro is skipped
  (`playIntroMovie`); a phone's graphics to start with, where the game has none of its own; the
  login waits for the game's own cursor (up to 3 minutes); TrinityCore's certificate bundle
  (2,709 bytes, the one trinity6.github.io serves, pinning the repack's `bnetserver.cert.pem` key:
  SHA-256 of its PKCS#1 form `B1241D83…`) is put in the session's ProgramData before it starts.
- **Space.** The 42 GB client archive and the 5.4 GB repack stayed in `downloads/` after a
  successful installation; they're removed once everything is installed and checked.

### On the phone, 1.0.268: the session's task trimmed from Recents

The next report from the phone (issue #48) got much further: the database ready, the
authentication server up, and the world server loading fast (its DB2 stores in 16 s). Then every
Windows program lost the X server at once ("X connection to :0 broken" six times) and Wine was
killed (137): the session's screen, where Winlator's X server lives, was gone. Its task was kept
out of Recents (`excludeFromRecents`), and Android keeps such a task only while it's the most
recent (`RecentTasks.isInVisibleRange`); once another is (the launcher brought back, Recents
opened), it's trimmed, and `ActivityTaskSupervisor.onRecentTaskRemoved(wasTrimmed = true)` removes
the task outright ("recent-task-trimmed"), its screen ended with it. The session's task is now
in Recents like any other (its own card, a way back to the game too); swiping that card away
ends the session, as for any game.

### On the phone, 1.0.275: the session's screen never opened; the realm without a screen

The next report (issue #53) said "The Windows session did not start" after the 120 s wait for the
session's screen, and every log in it (database, authentication, world, session) was #48's, byte
for byte, from an hour before: the screen never got as far as writing its log, so it never opened.
The app asked for it from the background of a long start (the repack and client checked twice,
the runtime and container prepared); Android opens no screen for an app that isn't on screen
(`BackgroundActivityStartController`, every target SDK since Android 10), and drops the request
without a word. Four failures in a row (#40, #41, #48, #53) had one cause: the realm's servers lived
in a screen, which Android can refuse, close, trim or recreate.

So the realm has no screen now. `WindowsRealmHost` runs its Wine itself (`local/core/HeadlessWine`):
the container's prefix made ready as a session's screen makes it (`WinePrefixSetup`, Winlator
patch 0011: system files, DLLs, registry, drives, Box64 0.4.4-legion, Wine's shared memory folder
and network files), `wineserver -p`, then each script (`cmd.exe /d /c R:\runtime\<name>.cmd`) as a
process of the app's, with the session's environment minus its display, shared memory and sound,
and `WINLATOR_REALM=<realm>` in it, which Wine passes on to every program it starts, so the realm's
processes are found and ended as a whole (Force Stop; `wineserver -k` at Stop). A probe (`ver`) runs
first; what Wine prints goes to `runtime/host.log`. `LocalRealmService`, a foreground service with
the realm's notification and a partial wake lock, keeps the app alive while a realm runs (Wine's
processes are the app's children). Each start keeps the last run's logs as `*.previous.log`, so a
report has this start's only.

The game joins it: Launch Game opens `RealmSessionActivity` from the launcher on screen (or as soon
as it's shown) with `cmd.exe /d /c R:\runtime\client.cmd`. Patch 0011 makes such a session join the
running Wine: it leaves the runtime's `tmp` (the Wine server's socket and esync shared memory there;
cleared, the next program starts a second Wine server for the same prefix, which can't see the
realm's processes: shown on a PC), the registry (Wine keeps it in its server and writes it back) and
the drives as the realm's start made them, makes the active-container link atomically (and leaves
it when it's already that container), and gives its programs `WINLATOR_SESSION=<token>`, so its end
(Leave, the game exiting, its card swiped, Android closing it) kills those only. The session's task
leaves Recents with it (`autoRemoveFromRecents`, `finishAndRemoveTask`).

Tested on a PC: the realm's database started and stopped by `HeadlessWine` under Wine 10.10 with no
display (`HeadlessWineTest.realmDatabaseUnderWineWithNoDisplay`, with `HEADLESS_WINE_TESTBED` and
`HEADLESS_WINE_ROOTFS`: the test bed is described in the test; x86-64 Wine, without Box64 or esync); a display session (Xvfb, `explorer /desktop`) on the realm's Wine server:
its programs (start.exe, explorer, cmd, conhost, the program) carried the session's marker and the
realm's (services, winedevice, plugplay, svchost, rpcss started later, cmd, mysqld) the realm's,
none both; the session's killed with its X server, the database stayed up and shut down cleanly
(exit 0), and `wineserver -k` left nothing. Under Box64 0.4.4-legion (through qemu-aarch64, with the
environment `headlessEnvironment` gives on a phone, esync on, no display): `wineserver -p`, the probe
at 25 s, the database answering at 43 s, its clean shutdown (exit 0) at 68 s. Untested: all of it on
a phone.

### Before release: an audit of the phone's way into the game

Before the next release, five independent audits went through the way from Start Server into the game
(the client's start, its graphics, its login, the realm on Android 17, the app's flow), each finding
checked by another trying to refute it. What they established, and what changed:

- **"Game Initialization Failed!"** (the user's screenshot, 1.0.278) is the client's start-up
  protection, not its graphics: the dialog's only reference in the decrypted client is its obfuscated
  entry stub, before any of the game's code; graphics failures have their own dialogs and write
  `Logs/gx.log`. Under Winlator's stock Box64 0.4.4 the protection fails with exactly this box (exit
  code 1, through qemu); under 0.4.4-legion it passes. How the stock one could run: Box64's version was
  extracted over the running binary in place, the failure (ETXTBSY) ignored and the new version
  recorded anyway, for good. Patch 0012 stages the version's binary, compares it at every start, puts
  it in place by a rename and records it only once installed; the session's log and the realm's
  host.log say which Box64 ran (its SHA-256), and the game's report says when the protection stopped it.
- **The language dialog**: with no textLocale and no launcher, the client asks for a language, and
  its OK sets the portal to the region's code ("US"), away from the realm. textLocale and audioLocale
  are written (enUS, the client's data's only one).
- **The graphics API**: the client resets gxApi to auto at every start while videoOptionsVersion is
  below 15 (set only after a start that worked); the app writes 15 and passes the API on the command
  line too (-d3d, -d3d11, -opengl, which override it). Direct3D 9 first. The failure lines matched in
  gx.log are the client's own (ReportDeviceCreationFailure…); gx.log is moved aside at each launch.
- **The game's end** ends its session (the realm's code finishes it once client.exit is written:
  Winlator's helper and an empty desktop stayed otherwise); Wine's crash dialog is off in the realm's
  prefix (a crash ends the program with its code instead of waiting behind the loading screen); the
  session's log names the game's windows and Box64's word on a fault (BOX64_SHOWSEGV).
- **Apart from Wrath** (the user: "completely disconnect it from wrath"): a running local realm no
  longer stops the 3.3.5a game, Turtle WoW or Felsong from starting (only a second Windows screen is
  refused), and its start asks nothing of their realms. Every session's screen clears the runtime's tmp
  but for Wine's server folders and esync memory, so another game's screen leaves the realm's Wine whole;
  the realm's programs use their container's real paths, not the active-container link a game moves.
- **Android 17**: the realm and its game share the app's memory budget (its memory limiter, lmkd) and
  its 32 extra processes (the realm about 15, the game about 6); a start warns when the app's battery use is Restricted, and the app's next start reports why Android
  ended it while a realm ran (ApplicationExitInfo). host.log has the realm's process count and memory
  every 5 minutes; the database's key cache is 32 MB. A game session from an older version's Recents
  card is refused; the game's screen size is taken at launch; the Legion login asks for the password
  when its wait ends instead of typing it blind.

Untested: all of it on a phone. The client's data isn't on the PC, so its graphics and login weren't run.
