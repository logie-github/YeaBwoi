<p align="center"><img src="branding/icon-source.png" width="128" alt="Dolphin XD icon"></p>

# Dolphin XD

An Android build of the [Dolphin](https://dolphin-emu.org) GameCube emulator that plays one game, **Pokémon XD: Gale of Darkness**, and behaves like a native game app:

- **Package:** `com.logie.dolphinxd`
- **App name:** Dolphin XD
- **No game list and no on-screen buttons.** You play with taps and swipes anywhere on the screen.

## Getting the APK

GitHub Actions builds the APK on every push (**Actions → Build Dolphin XD APK**) and publishes it on the repository's **Releases** page. On your phone, open the latest release and tap `DolphinXD-<version>.apk`. Pull request builds are checked but not published.

The first build takes about an hour because it compiles all of Dolphin. Later builds reuse a compiler cache and run faster.

## First launch

1. **Setup files (optional):** choose a GameCube BIOS (`IPL.bin`) dumped from your own console, or tap **Skip**. Pokémon XD doesn't need a BIOS. If you add one, the GameCube startup animation plays before the game.
2. **Game file:** choose your own Pokémon XD disc image (`.iso`, `.gcm`, `.rvz`, `.ciso`, `.gcz` or `.wbfs`). The app checks that the file is Pokémon XD (game ID `GXXE01`, `GXXP01` or `GXXJ01`) and rejects any other game.
3. Tap **Play Pokémon XD**.

After that, opening the app goes straight into the game. Exiting the game closes the app. If you move or delete the game file, the setup screen asks for it again.

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

The Dolphin menu has Pause, save states, Dolphin's full **Settings**, **Gesture controls** (this table), **Change game file** and **Exit**. The Back button opens the same menu. Bluetooth and USB game controllers also work.

## How this fork is built

Dolphin's full source is too large to vendor, so this repository holds only the changes, and CI applies them to a pinned upstream release:

| Path | Contents |
| --- | --- |
| `dolphin-version.env` | The upstream Dolphin release (tag and commit) to build from |
| `patches/` | Changes to Dolphin's own files: package name, launcher, gesture input in the emulation screen, menu entries |
| `overlay/` | New files copied into Dolphin's tree: the setup screen, gesture controls, theme and icons |
| `scripts/prepare-dolphin.sh` | Clones Dolphin, applies `patches/`, copies `overlay/` |
| `gesture-tests/` | JVM unit tests for the gesture recognizer. CI runs these before the APK build. |
| `scripts/generate-icons.sh` | Rebuilds the launcher icons from `branding/icon-source.png` |

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
