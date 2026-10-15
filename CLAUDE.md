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
| `warcraft/` | World of Warcraft 3.3.5a (stage 1: launcher + server) | `com.logie.warcraft` | AzerothCore/ChromieCraft + playerbots (musl arm64); Winlator stack next |

- **How the apps are made, tested and released:** `docs/single-game-apps.md`. Read it before
  building or changing an app.
- **The app being built:** `warcraft/PLAN.md` (the user's requirements, decisions, open questions,
  stages and where each stands). The user's art for it is in `warcraft/branding/`.
- **Wrath Remix** (a Legion Remix-style realm): assessment only, in `warcraft/REMIX-ASSESSMENT.md`.
  The user wants it built in a separate fork; work on this line keeps to normal gameplay.
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

## Felsong

The World of Warcraft app's third game, Felsong (Legion 7.3.5) on LegionCore, kept apart like
Turtle: `warcraft/FELSONG.md` (the brief, what was found, the plan; the client on a phone is the
open question). Not released: the user releases it once it's playable.
