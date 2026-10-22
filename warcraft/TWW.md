# The War Within — 11.2.5

This is an expansion in the existing `com.logie.warcraft` Android application, selected through
`Environments` and the existing game choosers. It does not use the 3.3.5a `Realms` enum: those two
realms share AzerothCore authentication, accounts and client configuration and must stay isolated
from a different client protocol and database.

| Setting | Value |
| --- | --- |
| Expansion | The War Within |
| Patch | 11.2.5 |
| Expected client build | 63704 |
| Distribution | Draconic WoW 11.2.5 |
| Server address | 127.0.0.1 |
| Installation type | Downloadable/importable local server |

## Sources checked

The supplied [Firestorm page](https://firestorm-servers.com/en/play) remains a launcher page.
The Draconic publisher additionally links a [streaming mini-client](https://drive.google.com/file/d/1GXgO7jDJrdLsN7LDjkdBCW8i1fKSUPc1/view).
On 2026-10-21 the source inspection workflow downloaded it without executing any game files:
7z, 63,298,635 bytes, SHA-256 `36f6f3e7ab5e2edc0aaa7b4400b8c9918a5a7263956cbf89c1a9a7bc2fa6d878`.
`WoW.exe` has PE version **11.2.5.63704**, SHA-256
`01ee0d23abb6f1da8a5670b858fd1f5ce899bef97e8434452c7e48e2491da3d5`.
It includes `cdns`, `versions` and `WTF/Config.wtf` (already using portal 127.0.0.1), but no initial
CASC data. Only that known executable plus bootstrap files qualify for streaming installation;
ordinary client imports still require CASC indexes/data. Streamed assets are not reported as
fully downloaded/offline. A pinned 7-Zip 25.01 executable is built for Android arm64 because the
archive uses BCJ2 and a 192 MiB dictionary; Commons Compress cannot decode BCJ2. The helper is
packaged as an executable native library, validates archive paths before extraction, verifies
7-Zip’s exit/checksum results and file sizes, and supports cancellation and staged cleanup.

Launcher artwork uses Blizzard’s published Clash wallpaper and official TWW press-kit logo. It
follows the existing realm hero, music/game chips, action panel and menu tile layout.

The [original uploader's repack instructions](https://www.emucoach.com/threads/warlords-of-draenor-6-2-3-6-2-4-legion-7-3-5-battle-for-azeroth-8-3-7-shadowlands-9-2-7-dragonflight-10-2-7-the-war-within-11-2-7-midnight-12.18115/)
contain both supplied Drive IDs and identify 11.2.5.63704. The publisher instructs users to open
`UniServerZ/Unicontroller.exe`, start MySQL, then run `Bnetserver.exe` and `Worldserver.exe`, set
the client's portal to `127.0.0.1`, and extract MMAPS at the repack root without a nested
`mmaps/mmaps` directory. These are publisher instructions, not a verified archive manifest.

On 2026-10-22 the repack inspection workflow downloaded the supplied server archive:
3,338,656,068 bytes, SHA-256 `3e3c4a9fa60a7e65b9d5f50e13d574a923d03ef03ce7a7c78436df038471887e`.
It contains `DraconicServer/bnetserver.exe`, `worldserver.exe`, their default-named configurations,
and `UniServerZ/UniController.exe`. The active `core/mysql/bin` contains both `mysqld.exe` and
`mysqld_z.exe`; `core/old` contains a second daemon, configuration, tools and initialized data.
The controller's active daemon is `core/mysql/bin/mysqld_z.exe`, with `core/mysql/my.ini` and
`core/mysql/data`. Its `us_user.ini` sets port 3306; the core configurations refer to `twwauth`,
`twwworld`, `twwcharacters` and `twwhotfixes`, all present in the active data directory.
The world uses `RealmID=1`, `DataDir="."` and port 8085; Battle.net uses 1119 and REST 8081.
Inspection extracts configuration and lists database files without running any downloaded code.
The initialized database's build_info is still checked at runtime before starting the core.
The separate MMAPS archive's full contents have not been independently inspected.
The application resolves the links at download time and reports actual errors; imported files
are inspected before activation. No trusted publisher file manifest or checksum was supplied.

## Implementation

`tww/core/TwwDownload.kt` manages HTTP acquisition and validated resume in a separate download
directory. `TwwArchive` extracts ZIP/7z/RAR into staging with CRC/size/path checks. `TwwInstall`
discovers client/core/database/maps and startup requirements from the actual installation.
`TwwJobs` and `TwwService` follow the app's existing foreground import pattern, with real
progress, cancellation, receipts and transactional placement. `tww/ui/TwwHome.kt` uses the
existing Compose windows, buttons, dialogs and typography.

The existing native server controls run Linux ARM64 musl builds. Downloaded Windows x64 Draconic
programs cannot be launched through those native `ctl.sh` scripts. The TWW runtime adapter uses
the already embedded Wine/Box64 runtime and a dedicated container; the Winlator patch is scoped
to TWW sessions. Unknown/ambiguous programs, database startup, client builds or unavailable ports
must produce actionable errors, never simulated running status. Modern client authentication and
the repack's game-content implementation still need actual end-to-end testing on a phone.

Missing build documentation permits a database-only bootstrap when every other structural
requirement is satisfied. Before any `realmlist` change or authentication/world startup, the
runtime queries the imported authentication database's `build_info` table for build 63704.
Successful evidence is recorded against SHA-256 hashes of both core executables and is invalidated
by an executable change. Imported proof files are discarded. The realm row follows the positive
`RealmID` in the actual world configuration; missing or ambiguous values block configuration.

Uniform Server's [controller source](https://github.com/iamola/uniserver/blob/1704fa1d6bbc15ad835cb8bcb749f5d7d4897e69/UniController/us_common_functions.pas)
discovers `mysqld_*.exe` only in `core/mysql/bin`. Discovery follows that layout, respects its
`[MYSQL]` configuration overrides, and reads the port from `my.ini` or `us_user.ini`.
The client/shutdown tools and schema validation use the same active installation; backup tables
cannot conceal a missing active schema. Multiple controllers or multiple active renamed daemons
remain errors. Managed localhost setup backs up text configuration and preserves every executable.
Existing installations can use Download & Install again to finish configuration without replacing
their database or redownloading installed components.

No client, repack, database or map content is committed or packaged by this integration.

## Background lifecycle

Winlator’s foreground session protection is always enabled in this app, including installs with
its old hidden preferences disabled. Leaving the game pauses rendering and keeps Wine/Box64,
the database and realm processes running. The existing foreground service holds a CPU wake lock
while the session is behind another app or the lock screen, releases it on session exit, and does
not kill the entire app when the launcher task is removed. Removing a game activity still tears
down its Windows runtime; Force Stop and Android process termination cannot preserve live Wine
memory. The app does not claim that a foreground service makes it immune to those events.

TWW installation runs independently of the launcher with a foreground notification, CPU/Wi-Fi
locks and redelivery of the original job intent after a system service restart. Supported picked
folders retain their read permission. Completed components and partial downloads are checked on
resume. The Compose report effect rethrows cancellation instead of displaying it as a file error;
only an active screen’s actual read failures are shown.
