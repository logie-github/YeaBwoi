# Legion — 7.3.5 (Single Player Project)

This replaces the Felsong launcher section at the user's request. The requested project is
[SPP AshamaneCore](https://www.ownedcore.com/forums/world-of-warcraft/world-of-warcraft-emulator-servers/wow-emu-general-releases/652544-single-player-project-ashamanecore-legion.html),
the original Legion distribution for build **26972**. Existing Felsong data is preserved;
SPP's downloaded files use a separate installation directory. Other expansions keep their files.

Implementation is in progress. No completed phone play test is claimed.

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

The [streaming client](https://drive.google.com/file/d/1tCP3mJUqsDK1H4zug4bQZOUrAzAX30ud/view)
was inspected separately: 1,124,676,989 bytes, SHA-256
`c6f13b5696c90c0e5055acaa35db286cb339f7fc4733e74b41bea30ab7d0859f`;
1,462,712,292 bytes expanded. It contains a partial CASC store, `cdns` and `versions` for 26972.
Its `Wow-64.exe` reports 7.3.5.26972, SHA-256
`7f86d310c9db965110c09d322322b6b4593c7dc250afa07278af7fdc0e1eb59a`.
The repack's `Addons/Patched_Exe_26972/SPP_Wow-64.exe` also reports 7.3.5.26972, SHA-256
`be484c9a3bd8e5de173e9a8e185a9b6636406d69a9122987f6f2c1d512b2bec5`.
Older 26654 executables at the outer archive root are unsuitable and must never be selected.
Streaming requires internet access; partial CASC presence does not establish an offline client.

No game, database or server binary is committed or included in the APK. Inspection and native
runtime checks use disposable CI storage. Android Wine/Box64 client compatibility still requires
testing on the phone, independently from Windows server validation.
