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

- **How the apps are made, tested and released:** `docs/single-game-apps.md`. Read it before
  building or changing an app.
- **The app being built:** `warcraft/PLAN.md` (the user's requirements, decisions, open questions,
  stages and where each stands). The user's art for it is in `warcraft/branding/`.
- **Wrath Remix** (a Legion Remix-style realm): the app's second realm, built from the user's guide
  (`warcraft/REMIX-GUIDE.md`) as `warcraft/modules/mod-wrath-remix` (its README). It must never
  change the ChromieCraft realm: its SQL goes into the Remix databases only, its module is off on
  realm 1. Where it stands and what's next: `warcraft/PLAN.md`.
- Each app's `README.md` is its user manual; keep it current with every change.

## Rules

- Every change is committed and pushed; CI (`.github/workflows/build-<app>.yml`) builds it and
  publishes a release. Confirm the run and the release before reporting to the user.
- Build locally before pushing; run the app's unit tests. Local Gradle builds use
  `--init-script scripts/maven-mirror.init.gradle` (Maven Central rate-limits these machines).
- Never commit build outputs (`build/`, `.cxx/`, `<app>/android/core/`) or any game files.
- Tell the user what was tested and what wasn't (nothing here runs on a real phone).
