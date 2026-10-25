# This repository

Android apps that each play one game on an emulator, like a native game app: gesture controls
instead of on-screen buttons, a title screen styled from the user's art, straight into the game,
self-updating from this repository's GitHub releases.

| Folder | App | Package | Built on |
| --- | --- | --- | --- |
| `games/xd`, `games/colosseum` (+ `patches/`, `overlay/`) | Dolphin XD, Dolphin Colosseum | `com.logie.dolphinxd`, `com.logie.dolphincollosseum` | Dolphin, patched |
| `v3gold/` | V3 Gold (Persona 4 Golden) | `com.logie.v3gold` | Vita3K, patched |
| `azuredreams/` | Azure Dreams | `com.logie.azure` | SwanStation core + LibretroDroid |
| `parasiteeve/` | Parasite Eve | `com.logie.pe` | Beetle PSX HW core + LibretroDroid |
| `warcraft/` | World of Warcraft 3.3.5a: ChromieCraft and Wrath Remix realms, the game on Winlator | `com.logie.warcraft` | AzerothCore/ChromieCraft + playerbots (musl arm64), `mod-wrath-remix`, Winlator |
| `warcrafts/` | Warcraft (on the phone): Warcraft I and II (GOG) native, Warcraft III RoC/TFT 1.30, offline | `com.logie.warcrafts` | Stratagus + War1gus/Wargus (arm64), innoextract for GOG's installers; Winlator (the WoW app's commit) for WC3 |

- **How the apps are made, tested and released:** `docs/single-game-apps.md`. Read it before
  building or changing an app.
- **The app being built:** `warcraft/PLAN.md` (the user's requirements, decisions, open questions,
  stages and where each stands). The user's art for it is in `warcraft/branding/`.
- **Wrath Remix** (a Legion Remix-style realm): the app's second realm, built from the user's guide
  (`warcraft/REMIX-GUIDE.md`) as `warcraft/modules/mod-wrath-remix` (its README). It must never
  change the ChromieCraft realm: its SQL goes into the Remix databases only, its module is off on
  realm 1. Where it stands and what's next: `warcraft/PLAN.md`.
- **The Skyborne** (WoW: Forever's race, both realms): `warcraft/modules/mod-skyborne` (its README);
  `tools/build_data.py` makes its data, committed.
- **The allied races** (void elves, nightborne, Lightforged Draenei, Zandalari Trolls: Blizzard's
  own models, ChromieCraft's realm only: the user, "Only chromie needs new races"): `warcraft/modules/mod-allied-races` (its README); `tools/races.py`, `tools/build_data.py` and
  `tools/quests.py` make its data, committed; `tools/package.py` fetches and converts the game's
  files as the server is packaged (never committed).
- **The Worgen and Goblins** (mod-worgoblin, built in, ChromieCraft's realm only: the user, "Use the
  popular worgen goblin module"): `warcraft/modules/mod-worgen-goblin` (its README) puts it in on top
  of the others' changes; `tools/build_data.py` makes its data, committed; `tools/package.py` makes
  its files for the game as the server is packaged (never committed). Next: Gilneas's and Kezan's
  starting areas from Cataclysm, joined to Wrath's world after them (`warcraft/PLAN.md`).
- Each app's `README.md` is its user manual; keep it current with every change.

## Rules

- Every change is committed and pushed; CI (`.github/workflows/build-<app>.yml`) builds it and
  publishes a release. Confirm the run and the release before reporting to the user.
- Build locally before pushing; run the app's unit tests. Local Gradle builds use
  `--init-script scripts/maven-mirror.init.gradle` (Maven Central rate-limits these machines).
- Never commit build outputs (`build/`, `.cxx/`, `<app>/android/core/`) or any game files.
- Tell the user what was tested and what wasn't (nothing here runs on a real phone).

## Turtle WoW

The World of Warcraft app's second game, Turtle WoW (1.18.1) with its own Tortoise-WoW realm, kept
completely apart from the 3.3.5a game: `warcraft/TURTLE.md` (the brief), `warcraft/turtle/` (its
realm), `com.logie.warcraft.turtle` (its code, every file operation through `turtle/core/SafeFs`).

## Legion 7.3.5 (LegionCore)

The World of Warcraft app's Legion game is LegionCore 7.3.5 (The Legion Preservation Project,
`fed58f7`) on the phone, set up like ChromieCraft (native arm64 realm, the client on Winlator), at
the user's request ("use legioncore ... I don't need the spp ... copy how ChromieCraft works").
The code keeps the Felsong names (`com.logie.warcraft.felsong`, `files/felsong`,
`warcraft/felsong/`, `felsong-server-<hash>` releases from the `felsong-server` CI job);
`warcraft/FELSONG.md` is the brief. It takes the client and server data the Single Player
Project download left in `files/spp-legion` (`FelsongSpp`, Kind.SPP). The SPP launcher is out of
the picker; its code (`spp/`, `local/`: the headless Windows realm host, issue #53, `SPP.md`)
remains for The War Within's Draconic realm, which shares it. The game launches with Box64
0.4.4-legion (`scripts/build-box64.sh`, `warcraft/patches/box64/`). Legion phone gameplay is
unverified: do not claim it works without a phone test.

## Warcraft

A separate app (`warcrafts/`, `com.logie.warcrafts`): Warcraft I and II from the user's GOG files (the offline installer itself, unpacked by innoextract),
native on Android (Stratagus with War1gus/Wargus, `scripts/build-stratagus.sh`; the user ruled out
DOSBox and wants them Android-native), and Warcraft III (Reign of Chaos and The Frozen Throne, 1.30)
on Winlator, offline (Blizzard's names blocked in the runtime's hosts file), with the touch gestures
the user chose. The brief: `warcrafts/PLAN.md`; the manual: `warcrafts/README.md`. Its Winlator
comes from `scripts/prepare-winlator-app.sh warcrafts` (the WoW app keeps its own script).
