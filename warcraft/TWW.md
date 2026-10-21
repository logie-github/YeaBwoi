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

The supplied [Firestorm page](https://firestorm-servers.com/en/play) is an acquisition/launcher
page. Its launcher link is `https://firestorm-servers.com/download/launcher`, an executable,
not a pinned client archive. [Firestorm's official update instructions](https://firestorm-servers.com/en/news/dev/562)
describe selecting a The War Within 11.2.5 client, but do not establish numeric build 63704.
The launcher may update its offerings, so the imported executable must be checked.

The [original uploader's repack instructions](https://www.emucoach.com/threads/warlords-of-draenor-6-2-3-6-2-4-legion-7-3-5-battle-for-azeroth-8-3-7-shadowlands-9-2-7-dragonflight-10-2-7-the-war-within-11-2-7-midnight-12.18115/)
contain both supplied Drive IDs and identify 11.2.5.63704. The publisher instructs users to open
`UniServerZ/Unicontroller.exe`, start MySQL, then run `Bnetserver.exe` and `Worldserver.exe`, set
the client's portal to `127.0.0.1`, and extract MMAPS at the repack root without a nested
`mmaps/mmaps` directory. These are publisher instructions, not a verified archive manifest.

The Drive sharing/preview/download pages could not be read with the verification web tool.
That does not establish that the links are broken. Current filenames, archive formats, lengths,
hashes, exact startup arguments, and database contents were not independently verified there.
The application resolves the links at download time and reports actual errors; imported files
are inspected before activation. No trusted publisher file manifest or checksum was supplied.

## Implementation

`tww/core/TwwDownload.kt` manages HTTP acquisition and validated resume in a separate download
directory. `TwwArchive` extracts ZIP/7z into staging with CRC/size/path checks. `TwwInstall`
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

Uniform Server's [controller source](https://github.com/iamola/uniserver/blob/master/UniController/us_common_functions.pas)
discovers `mysqld_*.exe`, normally `mysqld_z.exe`. Discovery supports that actual naming convention,
selects the database installation's normal `my.ini` over `my_skip_grant.ini`, and reads an explicit
port from `my.ini` or the imported controller's `us_user.ini`. Managed localhost setup writes the
discovered database and temporary directories into the backed-up normal configuration. This
does not establish which Uniform Server files the inaccessible Draconic archive currently contains.

No client, repack, database or map content is committed or packaged by this integration.
