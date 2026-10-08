<p align="center">
  <img src="games/xd/branding/logo.png" height="96" alt="Dolphin XD logo">
  &nbsp;&nbsp;
  <img src="games/colosseum/branding/logo.png" height="96" alt="Dolphin Colosseum logo">
</p>

# Dolphin XD and Dolphin Colosseum

Two Android builds of the [Dolphin](https://dolphin-emu.org) GameCube emulator. Each plays one game and behaves like a native game app.

| App | Game | Package | Look |
| --- | --- | --- | --- |
| **Dolphin XD** | Pokémon XD: Gale of Darkness (`GXX…`) | `com.logie.dolphinxd` | Dark theme in Shadow Lugia purple, red and black |
| **Dolphin Colosseum** | Pokémon Colosseum (`GC6…`) | `com.logie.dolphincollosseum` | Light desert theme in sand, terracotta and gold |

Both apps work the same way. There's no game list and no on-screen buttons: you play with taps and swipes anywhere on the screen. A themed main menu has texture pack and mod installers and Dolphin's emulator settings.

## Getting the APKs

GitHub Actions (**Actions → Build APKs**) publishes each app as its own release on the repository's **Releases** page: **Dolphin XD 1.0.N** (tags `xd-build-N`) and **Dolphin Colosseum 1.0.N** (tags `colosseum-build-N`). On your phone, open the latest release of the app you want and tap its `.apk`.

A push only rebuilds and releases the apps whose files changed since their last release (`scripts/apps-to-build.sh` decides). A change to one game's files never publishes the other app. A change to the shared code (`overlay/`, `patches/`, the Dolphin version) releases both. To rebuild an app by hand, for example after changing build settings in the workflow, use **Run workflow** and pick the app. Pull request builds are checked but not published.

The two apps have different package names, so they install side by side as separate apps. Each keeps its own saves, settings, texture packs and mods in its own app storage. Installing or updating one never touches the other.

The first build takes about an hour because it compiles all of Dolphin. Later builds reuse a compiler cache.

## First launch

1. **Setup files (optional):** choose a GameCube BIOS (`IPL.bin`) dumped from your own console, or tap **Skip**. Neither game needs a BIOS. If you add one, the GameCube startup animation plays before the game.
2. **Game file:** choose your own disc image (`.iso`, `.gcm`, `.rvz`, `.ciso`, `.gcz` or `.wbfs`). Each app accepts only its own game in any region: `GXXE01`, `GXXP01` or `GXXJ01` for Dolphin XD, and `GC6E01`, `GC6P01` or `GC6J01` for Dolphin Colosseum.
3. Tap **Play**.

After that, opening the app goes straight into the game. Exiting the game (**Exit to main menu** in the Dolphin menu) opens the main menu. Back on the main menu closes the app.

The apps don't include any game or BIOS files. You have to provide your own.

## Controls

| Gesture | GameCube input |
| --- | --- |
| Tap | A |
| Hold | B (held for as long as your finger stays down) |
| Double-tap | Start |
| Tap, then tap and hold on the **left** half | L |
| Tap, then tap and hold on the **right** half | X |
| Swipe up, down, left, right or any angle in between, keeping your finger down | Control Stick, in that direction |
| Swipe up or down | Also D-pad Up or Down, held for as long as the swipe is |
| Two-finger tap, then two-finger tap and hold | Dolphin menu |

While one finger is on the Control Stick, you can tap with a second finger for A or hold it for B. Holding and then dragging presses B and moves the stick together.

The D-pad turns on once an up or down swipe is about halfway to full tilt and within 45° of straight up or down, so sideways and diagonal walking doesn't press it. That way menus that only follow the D-pad work with the same swipe.

The Dolphin menu has Pause, save states, Dolphin's full **Settings**, **Gesture controls** (this table) and **Exit to main menu**. The Back button opens the same menu. Bluetooth and USB game controllers also work.

## Main menu

| Button | What it does |
| --- | --- |
| **Play** | Starts the selected game version |
| **Texture packs** | Imports, enables, disables and deletes texture packs, and turns Load custom textures and Prefetch on or off |
| **Mods** | Installs mods and picks the version Play starts (the original game or a mod) |
| **Emulator settings** | Dolphin's full settings: graphics, audio, controllers and more |
| **Controls** | The gesture guide |
| **Change game file** | Runs the game-file setup again |
| **Exit** | Closes the app |

### Texture packs

Tap **Import pack (.zip)** and choose the pack's zip as downloaded. The importer handles both layouts Dolphin packs come in:

- **Texture packs** contain a folder named after the game ID: `GXX` or `GXXE01` for XD, `GC6` or `GC6E01` for Colosseum.
- **Dolphin resource packs** contain `textures/<game ID>/…` and a `manifest.json`.

If a zip won't open, extract it with a file manager and use **Import extracted folder**.

[Pokémon XG/XD HD Texture Pack](https://forums.dolphin-emu.org/Thread-pokemon-xg-xd-hd-texture-pack-v1-0) and Pokémon Colosseum HD packs install this way. If a pack comes in PNG and DDS versions, use PNG on phones: Dolphin only shows DDS textures on GPUs that support BC texture compression. Most Snapdragon (Adreno) GPUs do; Mali (Exynos, Tensor, MediaTek) GPUs don't.

Each pack gets its own folder in `Load/Textures` with a marker file named after the game (`GXX.txt` or `GC6.txt`). Dolphin loads every folder that has the marker, which is how a pack is switched on or off without deleting it.

### Mods

Tap **Install mod patch (.ups)** and choose the patch. The app follows the mod's own instructions: it applies the patch to a clean copy of your original disc image and never changes your original file. The result is a separate game version. The patch's checksums confirm that the base image is the exact one it was made for.

When a mod is already installed, the app asks what to apply the patch to:

- **The clean original:** for new mods and for updates. Mod authors say to always patch updates onto a clean ISO.
- **An installed mod:** for add-ons made for that mod. The mod is updated in place.

| Mod | What to do |
| --- | --- |
| [Pokémon XG: Next Gen](https://projectpokemon.org/home/forums/topic/37672-pokemon-xg-next-gen/) (Dolphin XD) | Install the `.ups` patch. It needs the **USA** Pokémon XD (GXXE01). |
| [Pokémon Grand Colosseum](https://projectpokemon.org/home/files/file/4623-pokemon-grand-colosseum/) (Dolphin Colosseum) | Install the `.ups` patch of the version you want (Johto, Kanto or Hoenn). It needs the **USA** Pokémon Colosseum (GC6E01). For the Nuzlocke patch, install it afterwards and choose your Grand Colosseum version when asked. |

Both mods need a full 1.46 GB ISO of the USA game. RVZ, GCZ and WIA images are converted to ISO automatically first. NKit images can't be used, because NKit trims the disc. Patching needs about 1.5 GB of free space.

**Add already-patched game** adds a modded ISO that was patched somewhere else, for mods that ship as xdelta or other formats.

Each game version keeps its own memory card saves and save states. These mods use the same game ID as the original, so without this the original and the mod would overwrite each other's saves.

## How this fork is built

Dolphin's full source is too large to vendor, so this repository holds only the changes, and CI applies them to a pinned upstream release:

| Path | Contents |
| --- | --- |
| `dolphin-version.env` | The upstream Dolphin release (tag and commit) to build from |
| `patches/` | Changes to Dolphin's own files: configurable package name, the app's screens, gesture input in the emulation screen, in-game menu entries, the themed palette |
| `overlay/` | Code and layouts shared by both apps: setup and main menu screens, texture pack and mod installers, UPS patcher, gesture controls |
| `games/<game>/` | One app each (`xd`, `colosseum`). `game.env` sets the package and name. `overlay/` holds that game's wording, game ID, colors and artwork. `branding/` holds the source images. |
| `scripts/prepare-dolphin.sh` | Clones Dolphin, applies `patches/`, then copies `overlay/` and `games/<game>/overlay/` |
| `scripts/generate-art.sh` | Rebuilds a game's launcher icons, splash and menu artwork from its `branding/` |
| `scripts/apps-to-build.sh` | Lists the apps that changed since their last release. CI builds and releases only those. |
| `unit-tests/` | JVM unit tests for the gesture recognizer and the UPS patcher. CI runs these before the APK builds. |

To build locally, you need JDK 21 and the Android SDK, plus the NDK version named in Dolphin's `build.gradle.kts`:

```sh
XD="$PWD"                                   # this repository
GAME=colosseum                              # or xd
scripts/prepare-dolphin.sh "$GAME"          # creates build/dolphin
source "games/$GAME/game.env"
cd build/dolphin/Source/Android
./gradlew assembleRelease -PxdVersionCode=1 -PxdVersionName=1.0.1 \
  -PxdApplicationId="$APP_ID" -PxdAppName="$APP_NAME" \
  -Pkeystore="$XD/ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

To add another game, copy `games/colosseum`, change `game.env`, the strings and colors in its `overlay`, and the images in `branding/`. Give it its own `RELEASE_TAG_PREFIX` in `game.env`. Then run `scripts/generate-art.sh <game>` and add the game to the `app` choices in `.github/workflows/build-apk.yml`. CI picks up new games automatically.

To update Dolphin, change `dolphin-version.env` and run `scripts/prepare-dolphin.sh`. If a patch no longer applies, fix it in `build/dolphin`, commit the fix there, and regenerate the patches with `git format-patch --zero-commit --no-signature -o ../../patches <old tag>..HEAD~1`.

## Signing

Android only installs an update over an existing app if both are signed with the same key. By default CI signs both apps with `ci/dolphinxd-ci.p12` (password `dolphinxd`). That key is committed, so every build installs over the last one. It also means anyone can sign an APK with it, so only install builds from this repository.

To sign with a private key instead, add these repository secrets (**Settings → Secrets and variables → Actions**):

- `DOLPHINXD_KEYSTORE_BASE64`: your keystore, base64-encoded (`base64 -w0 my.keystore`)
- `DOLPHINXD_KEYSTORE_PASSWORD`
- `DOLPHINXD_KEY_ALIAS`
- `DOLPHINXD_KEY_PASSWORD`: optional, defaults to the keystore password

Switching keys changes the apps' signatures. Uninstall the old builds first, which deletes saves, or back up `Android/data/<package>` before switching.

## License

Dolphin is licensed under GPLv2+. This fork, including everything in `patches/`, `overlay/` and `games/`, uses the same license. Pokémon is a trademark of Nintendo, Creatures Inc. and GAME FREAK inc. This project isn't affiliated with any of them or with the Dolphin team.
