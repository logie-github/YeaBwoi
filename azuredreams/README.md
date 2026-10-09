<p align="center">
  <img src="branding/logo.png" height="96" alt="Azure Dreams logo">
</p>

# Azure Dreams

An Android app that plays one game: **Azure Dreams** for the PlayStation. It runs on [PCSX ReARMed](https://github.com/libretro/pcsx_rearmed), the PlayStation emulator core Lemuroid and RetroArch use on Android, through [LibretroDroid](https://github.com/Swordfish90/LibretroDroid). You play with taps and swipes anywhere on the screen, from a title screen styled after PlayStation-era menus.

| | |
| --- | --- |
| Package | `com.logie.azuredreams` |
| Releases | **Azure Dreams 1.0.N**, tags `ad-build-N` (**Actions → Build Azure Dreams**) |
| Emulator | PCSX ReARMed, built for arm64 from the commit in `core-version.env` |

Why not DuckStation: its license (CC BY-NC-ND, now PolyForm Strict) doesn't allow modified versions to be shared, and its Android app's source isn't public. PCSX ReARMed is GPL, small and fast, and plays Azure Dreams at full speed on any phone.

## First launch

1. Open the app and tap **Choose files** (or the **Disc** tile).
2. Pick your Azure Dreams disc image: the `.cue` file and its `.bin` files together, or a single `.chd`, `.pbp` or `.iso`. The app copies it into its own storage, so you can delete the original afterwards.
3. Tap **PRESS START**.

No BIOS is needed: the core has its own replacement. To use a BIOS dumped from your PlayStation (`scph1001.bin` and the like, 512 KB), add it under **BIOS**.

After that, opening the app goes straight back into the game, where you left it, once it has checked for updates.

## Controls

The gestures are V3 Gold's, with the D-pad in place of the sticks: Azure Dreams only uses the digital pad.

| Gesture | Button | In Azure Dreams |
| --- | --- | --- |
| Swipe anywhere | D-pad, any of 8 directions, held while the finger stays down | Walk, pick in menus |
| Tap | Cross | Attack, OK |
| Hold | Circle, held | Cancel |
| Hold, then drag (or hold and swipe with another finger) | Circle + D-pad | Run |
| Tap, then tap and hold | R1 on the right half of the screen, L1 on the left, held | Turn the view |
| Two fingers swipe down | Square | The game's menu |
| Two fingers swipe up | Triangle | |
| Two fingers swipe right / left | L1 / R1 | |
| Double tap | Start | Pause |
| Two-finger hold | Select, held | Map |
| Two-finger tap, then two-finger tap and hold | This app's menu | |
| Android's back gesture | This app's menu | |

A swipe presses the D-pad as soon as it starts and follows the finger: the start point is dragged along about 6 mm behind it, so the D-pad points where the finger last moved and turning around is instant. Moving back to the start point lets go. A tap waits a quarter of a second before it presses Cross, to tell it apart from a double tap. While one finger walks, a second can tap for Cross or hold for Circle.

A game controller works too; plug it in or pair it and play.

## In-game menu

Back gesture or the two-finger menu gesture. The game pauses while it's open.

- **Save state / Load state**: three slots. A state keeps the exact moment, anywhere, even inside the tower. The game's own saves are on the memory card and aren't touched by states.
- **Speed**: 1×, 2× or 3× (fast forward).
- **Picture**: sharp pixels, smooth, CRT TV scanlines or upscaled, and **Sharper 3D** (twice the PlayStation's resolution).
- **Controls**: the gesture guide and the vibration switch.
- **Reset game**, **Check for updates**, **Quit to title**.

Leaving the game in any way (quitting, switching apps, the phone sleeping) saves where it was. **PRESS START** picks up from there; **From the title screen** boots the game instead.

## Saves

The game saves to memory card 1, which the app writes to `saves/pcsx-card1.mcd` in its storage as soon as the game saves. **Memory card** on the title screen imports a card from another emulator or a PlayStation (`.mcd`, `.mcr`, `.srm`, DexDrive `.gme`, PSP `.vmp`) and exports a copy (`.mcd`) for other emulators. Importing keeps the replaced card as a backup.

Save states and the memory card stay when the app updates and when you replace or delete the disc.

## Updates

Like the other apps: the app checks this repository's releases (`ad-build-N`) when it opens and from **Check for updates**, and installs over itself. A private repository needs the `DOLPHINXD_UPDATE_TOKEN` secret (see the main README). It's signed with the same key as the other apps.

## How it's built

| Path | Contents |
| --- | --- |
| `app.env` | Package, app name and release tag prefix |
| `core-version.env` | The PCSX ReARMed commit to build |
| `android/` | The app: title screen, game screen, in-game menu, gestures, update checker |
| `branding/` | Source images. `make-icons.py` rebuilds the launcher icon layers from `icon.png`. |
| `fonts/` | Licenses of the pixel fonts (Press Start 2P, Pixelify Sans), both SIL Open Font License |
| `unit-tests/` | JVM unit tests for the gesture recognizer and the update picker |
| `../scripts/build-pcsx-rearmed.sh` | Fetches PCSX ReARMed and builds it with the NDK into `android/core/` |

To build locally, you need JDK 17+ and the Android SDK with NDK 29.0.14206865:

```sh
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865" scripts/build-pcsx-rearmed.sh
cd azuredreams/android
./gradlew assembleRelease -PadVersionCode=1 -PadVersionName=1.0.1 \
  -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

To update the core, change the commit in `core-version.env`.

## License

The app is GPLv3 (LibretroDroid's license). PCSX ReARMed is GPLv2. The fonts are under the SIL Open Font License (`fonts/`). Azure Dreams is a trademark of Konami. This project isn't affiliated with Konami, the PCSX ReARMed team or LibretroDroid.
