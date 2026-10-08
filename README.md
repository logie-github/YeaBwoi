<p align="center"><img src="branding/icon-source.png" width="128" alt="Dolphin XD icon"></p>

# Dolphin XD

An Android build of the [Dolphin](https://dolphin-emu.org) GameCube emulator that plays one game, **Pokémon XD: Gale of Darkness**, and behaves like a native game app:

- **Package:** `com.logie.dolphinxd`
- **App name:** Dolphin XD
- **No game list and no on-screen buttons.** You play with taps and swipes anywhere on the screen.
- **Main menu** in Dolphin XD's own purple, red and black theme, with texture pack and mod installers and Dolphin's emulator settings.

## Getting the APK

GitHub Actions builds the APK on every push (**Actions → Build Dolphin XD APK**) and publishes it on the repository's **Releases** page. On your phone, open the latest release and tap `DolphinXD-<version>.apk`. Pull request builds are checked but not published.

The first build takes about an hour because it compiles all of Dolphin. Later builds reuse a compiler cache and run faster.

## First launch

1. **Setup files (optional):** choose a GameCube BIOS (`IPL.bin`) dumped from your own console, or tap **Skip**. Pokémon XD doesn't need a BIOS. If you add one, the GameCube startup animation plays before the game.
2. **Game file:** choose your own Pokémon XD disc image (`.iso`, `.gcm`, `.rvz`, `.ciso`, `.gcz` or `.wbfs`). The app checks that the file is Pokémon XD (game ID `GXXE01`, `GXXP01` or `GXXJ01`) and rejects any other game.
3. Tap **Play Pokémon XD**.

After that, opening the app goes straight into the game. Exiting the game (**Exit to main menu** in the Dolphin menu) opens the main menu. Back on the main menu closes the app.

Dolphin XD doesn't include any game or BIOS files. You have to provide your own.

## Controls

| Gesture | GameCube input |
| --- | --- |
| Tap | A |
| Hold | B (held for as long as your finger stays down) |
| Double-tap | Start |
| Tap, then tap and hold on the **left** half | L |
| Tap, then tap and hold on the **right** half | X |
| Swipe up, down, left, right or any angle in between, keeping your finger down | Control Stick, in that direction |
| Two-finger tap, then two-finger tap and hold | Dolphin menu |

While one finger is on the Control Stick, you can tap with a second finger for A or hold it for B. Holding and then dragging presses B and moves the stick together.

The Dolphin menu has Pause, save states, Dolphin's full **Settings**, **Gesture controls** (this table) and **Exit to main menu**. The Back button opens the same menu. Bluetooth and USB game controllers also work.

## Main menu

| Button | What it does |
| --- | --- |
| **Play** | Starts the selected game version |
| **Texture packs** | Imports, enables, disables and deletes texture packs, and turns Load custom textures and Prefetch on or off |
| **Mods** | Installs mods and picks the version Play starts (original Pokémon XD or a mod) |
| **Emulator settings** | Dolphin's full settings: graphics, audio, controllers and more |
| **Controls** | The gesture guide |
| **Change game file** | Runs the game-file setup again |
| **Exit** | Closes the app |

### Texture packs

Tap **Import pack (.zip)** and choose the pack's zip as downloaded. The importer handles both layouts Dolphin packs come in: texture packs with a `GXX` folder inside, and Dolphin resource packs with `textures/GXX/…` and a `manifest.json`. If a zip won't open, extract it with a file manager and use **Import extracted folder**.

For example, the [Pokémon XG/XD HD Texture Pack](https://forums.dolphin-emu.org/Thread-pokemon-xg-xd-hd-texture-pack-v1-0) works this way. On phones, use its PNG version: Dolphin only shows DDS textures on GPUs that support BC texture compression. Most Snapdragon (Adreno) GPUs do; Mali (Exynos, Tensor, MediaTek) GPUs don't.

Each pack gets its own folder in `Load/Textures` with a `GXX.txt` marker. Dolphin loads every folder that has the marker, which is how a pack is switched on or off without deleting it.

### Mods

Tap **Install mod patch (.ups)** and choose the patch. Dolphin XD follows the mod's own instructions: it applies the patch to a clean copy of your original Pokémon XD disc image and never to an already-patched one. The result is a separate game version, and your original file is never changed. The patch's checksums confirm that the base image is the exact one it was made for.

[Pokémon XG: Next Gen](https://projectpokemon.org/home/forums/topic/37672-pokemon-xg-next-gen/) is distributed as a UPS patch for the **USA** release (GXXE01) as a full 1.46 GB ISO. Disc images in RVZ, GCZ or WIA format are converted to ISO automatically first. NKit images can't be used, because NKit trims the disc. Patching needs about 1.5 GB of free space.

**Add already-patched game** adds a modded ISO that was patched somewhere else, for mods that ship as xdelta or other formats.

Each game version keeps its own memory card saves and save states. Pokémon XG uses the same game ID as Pokémon XD, so without this the two games would overwrite each other's saves.

## How this fork is built

Dolphin's full source is too large to vendor, so this repository holds only the changes, and CI applies them to a pinned upstream release:

| Path | Contents |
| --- | --- |
| `dolphin-version.env` | The upstream Dolphin release (tag and commit) to build from |
| `patches/` | Changes to Dolphin's own files: package name and screens, gesture input in the emulation screen, in-game menu entries, the purple/red dark theme |
| `overlay/` | New files copied into Dolphin's tree: setup and main menu screens, texture pack and mod installers, UPS patcher, gesture controls, theme and icons |
| `scripts/prepare-dolphin.sh` | Clones Dolphin, applies `patches/`, copies `overlay/` |
| `unit-tests/` | JVM unit tests for the gesture recognizer and the UPS patcher. CI runs these before the APK build. |
| `scripts/generate-icons.sh` | Rebuilds the launcher icons and the menu artwork from `branding/` |

To build locally, you need JDK 21 and the Android SDK, plus the NDK version named in Dolphin's `build.gradle.kts`:

```sh
XD="$PWD"                              # this repository
scripts/prepare-dolphin.sh             # creates build/dolphin
cd build/dolphin/Source/Android
./gradlew assembleRelease -PxdVersionCode=1 -PxdVersionName=1.0.1 \
  -Pkeystore="$XD/ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

To update Dolphin, change `dolphin-version.env` and run `scripts/prepare-dolphin.sh`. If a patch no longer applies, fix it in `build/dolphin`, commit the fix there, and regenerate the patches with `git format-patch --zero-commit --no-signature -o ../../patches <old tag>..HEAD~1`.

## Signing

Android only installs an update over an existing app if both are signed with the same key. By default CI signs with `ci/dolphinxd-ci.p12` (password `dolphinxd`). That key is committed, so every build installs over the last one. It also means anyone can sign an APK with it, so only install Dolphin XD builds from this repository.

To sign with a private key instead, add these repository secrets (**Settings → Secrets and variables → Actions**):

- `DOLPHINXD_KEYSTORE_BASE64`: your keystore, base64-encoded (`base64 -w0 my.keystore`)
- `DOLPHINXD_KEYSTORE_PASSWORD`
- `DOLPHINXD_KEY_ALIAS`
- `DOLPHINXD_KEY_PASSWORD`: optional, defaults to the keystore password

Switching keys changes the app's signature. Uninstall the old build first, which deletes saves, or back up `Android/data/com.logie.dolphinxd` before switching.

## License

Dolphin is licensed under GPLv2+. This fork, including everything in `patches/` and `overlay/`, uses the same license. Pokémon is a trademark of Nintendo, Creatures Inc. and GAME FREAK inc. This project isn't affiliated with any of them or with the Dolphin team.
