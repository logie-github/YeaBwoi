<p align="center">
  <img src="branding/logo.png" height="120" alt="Persona 4 Golden logo">
</p>

# V3 Gold

An Android build of the [Vita3K](https://vita3k.org) PS Vita emulator that plays one game, Persona 4 Golden, and behaves like a native game app. There are no on-screen buttons: you play with taps and swipes anywhere on the screen. The app has a Persona 4 Golden themed main menu in yellow and black, with an animated tile wall.

| | |
| --- | --- |
| Package | `com.logie.v3gold` |
| Releases | **V3 Gold 1.0.N** (tags `v3gold-build-N`), from **Actions → Build V3 Gold** |
| Built from | Vita3K, pinned in `vita3k-version.env` |

It installs next to Dolphin XD, Dolphin Colosseum and the official Vita3K app without touching them. Its firmware, game and saves live in its own app storage (`Android/data/com.logie.v3gold`).

## First launch

The main menu's **Setup** card walks through the two things the app can't ship:

1. **PS Vita system software.** **Set up** opens Vita3K's setup screen, which links to Sony's download pages. Install the firmware (`PSVUPDAT.PUP`) and the font package (`PSP2UPDAT.PUP`).
2. **Persona 4 Golden.** **Install** takes your own dump of the game: a `.pkg` with its license (`work.bin` or a zRIF key), or a `.vpk` or `.zip`. Supported regions: PCSE00120 (North America), PCSB00245 (Europe), PCSG00004 and PCSG00563 (Japan), PCSH00021 (Asia).

After that, opening the app checks for an update, then goes straight into the game. Leaving the game, from the in-game menu, lands on the main menu.

## Controls

| Gesture | Button |
| --- | --- |
| Tap | Cross |
| Hold | Circle, held while the finger stays down |
| Tap, then tap and hold | Square |
| Two fingers swipe up | Triangle |
| Double tap | Start |
| Two-finger hold | Select, held while the fingers stay down |
| Swipe on the left half | Left stick, and the D-pad in the swipe's main direction |
| Swipe and keep holding on the right half | Right stick |
| Quick flick right / left on the right half | L1 / R1 |
| Two-finger tap, then two-finger tap and hold | In-game menu (pauses the game) |
| Android's back gesture | In-game menu |

A tap waits a quarter of a second before it presses Cross, to tell it apart from a double tap. A right-half swipe waits a quarter of a second before moving the right stick, to tell it apart from an L1/R1 flick.

While one finger walks or holds a button, a second finger can tap for Cross, hold for Circle or swipe the other stick. To hold Circle while walking, hold, then drag the same finger.

The in-game menu's **Controls** tab turns gestures off, which brings back Vita3K's on-screen buttons. It also has the gesture guide and a switch for the vibration on holds. While a game controller is connected, the gestures are off and the controller plays.

## Main menu

- **Play**: cuts to the game with the TV color bars.
- **Controls**: the gesture guide.
- **Game settings**: Vita3K settings for Persona 4 Golden only.
- **Emulator settings**: all of Vita3K's settings. Graphics stay at Vita3K's defaults (native resolution), which keeps the picture free of upscaling glitches. Resolution can be raised here.
- **Install game**: install or update the game, or add a license.
- **Firmware**: Vita3K's setup screen.
- **Updates**: check for a newer V3 Gold. **Check for updates** is also on the in-game menu's Session tab.
- **Library**: Vita3K's own app list, for managing saves and other installed apps.

## Limitations

- Vita3K is experimental. Persona 4 Golden is playable, but expect occasional glitches or crashes. Save often in the game.
- Vita3K has no save states, so there's no quick save or quick load. Saving works at the game's own save points.
- There's no fast-forward. Vita3K can't speed a game up.

## How it's built

As with the Dolphin apps, this folder holds only V3 Gold's changes. CI applies them to the pinned upstream Vita3K commit:

| Path | Contents |
| --- | --- |
| `vita3k-version.env` | The upstream Vita3K commit to build from |
| `app.env` | Package, app name and release tag prefix |
| `patches/` | Changes to Vita3K's own files: app identity, version and signing from Gradle properties, gestures in the input overlay, the main menu and in-game menu entries, the Persona 4 Golden palette |
| `overlay/` | V3 Gold's code and resources: gesture recognizer, main menu, update checker, artwork |
| `branding/` | Source images. `make-icons.py` rebuilds the launcher icon layers from `icon.png`. |
| `unit-tests/` | JVM unit tests for the gesture recognizer and the update picker |
| `../scripts/prepare-vita3k.sh` | Fetches Vita3K, applies `patches/`, copies `overlay/` and Vita3K's assets |

To build locally, you need JDK 17+, the Android SDK with NDK 29.0.14206865, [vcpkg](https://vcpkg.io) (`VCPKG_ROOT`) and ninja:

```sh
scripts/prepare-vita3k.sh                     # creates build/vita3k
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
(cd build/vita3k && "$VCPKG_ROOT/vcpkg" install --triplet arm64-android)
cd build/vita3k/android
./gradlew assembleRelease -PgoldVersionCode=1 -PgoldVersionName=1.0.1 \
  -Pkeystore="$PWD/../../../ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

To update Vita3K, change the commit in `vita3k-version.env` and run `scripts/prepare-vita3k.sh`. If a patch no longer applies, fix it in `build/vita3k`, commit the fix there, and regenerate the patches with `git format-patch --zero-commit --no-signature -o ../../v3gold/patches <old commit>..HEAD~1`. Some of Vita3K's files have Windows line endings, so the script applies the patches with `git am --keep-cr`.

Signing and update tokens use the same repository secrets as the Dolphin apps (see the main README).

## License

Vita3K is licensed under GPLv2+. V3 Gold, including everything in `patches/` and `overlay/`, uses the same license. Persona 4 Golden and its artwork belong to ATLUS/SEGA. This project isn't affiliated with them, Sony or the Vita3K team. You need your own copy of the game.
