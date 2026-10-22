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

SPP uses `Server/bin64/bnetserver.exe` and `worldserver.exe`, with the actual realm settings
`Realms/1/Settings/login.conf` and `world.conf`, working from `Realms/1`.
Its initialized MariaDB 10.1.30 is `Server/Database/bin/mysqld.exe`; `start.bat` selects
`Server/Database/my-large.ini` with `--console --standalone`. The data directory is
`Server/Database/data`; schemas include `realmd`, `characters`, `world`, `hotfixes`, `mysql`.
The publisher's port 3310 will be isolated from ChromieCraft by using SPP port 3312.
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

No game, database or server binary is committed or included in the APK. Inspection and native
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
