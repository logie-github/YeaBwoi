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

The same gestures work everywhere: in town, dungeons, battles, menus and conversations. They follow the Dolphin apps' gestures.

| Gesture | Button |
| --- | --- |
| Swipe anywhere | Left stick, in any direction: a short swipe walks, a longer one runs |
| Hold one finger, swipe with another | Right stick: turns the camera, following the swiping finger |
| Tap | Cross |
| Hold | Circle, held while the finger stays down |
| Tap, then tap and hold | Square |
| Two fingers swipe up | Triangle |
| Double tap | Start |
| Two-finger hold | Select, held while the fingers stay down |
| Two fingers swipe right / left | L1 / R1 |
| Two-finger tap, then two-finger tap and hold | In-game menu (pauses the game) |
| Android's back gesture | In-game menu |

A tap waits a quarter of a second before it presses Cross, to tell it apart from a double tap.

The stick follows the swipe's exact angle, so you can walk in any direction, not just 8. A short swipe (up to about 4.5 mm) pushes the stick partway, so he walks. Past that the push grows, and from about 7.5 mm he runs. The start point is dragged along behind the finger, so coming back towards it slows him to a walk, and returning to it stops him. Swipes don't press the D-pad: in Persona 4 Golden the D-pad moves the character at full speed in only 4 directions.

While one finger walks, a second finger can tap for Cross, hold for Circle or swipe to turn the camera. For the camera on its own, hold one finger anywhere and swipe with another. Holding lets go of Circle as soon as the other finger swipes, and you can keep swiping again while still holding. To hold Circle while walking, hold, then drag the same finger.

The in-game menu's **Controls** tab turns gestures off, which brings back Vita3K's on-screen buttons. It also has the gesture guide, a switch for the vibration on holds, and:

- **Walking**: how far a short swipe pushes the stick (40% at first). If short swipes still run, slide it left. If he doesn't move, slide it right.
- **Running**: how long a swipe has to be to push the stick all the way (about 7.5 mm at first, 4 to 15 mm). Swipes up to 60% of that walk.
- **Swipes press the D-pad too** (off): for any menu that ignores the stick.
 While a game controller is connected, the gestures are off and the controller plays.

## Main menu

- **Play**: cuts to the game with the TV color bars.
- **Controls**: the gesture guide.
- **Game settings**: Vita3K settings for Persona 4 Golden only.
- **Emulator settings**: all of Vita3K's settings. Graphics stay at Vita3K's defaults (native resolution), which keeps the picture free of upscaling glitches. Resolution can be raised here.
- **Install game**: install or update the game, or add a license.
- **Firmware**: Vita3K's setup screen.
- **Updates**: check for a newer V3 Gold. **Check for updates** is also on the in-game menu's Session tab.
- **Library**: Vita3K's own app list, for managing saves and other installed apps.

## Screen shape

The in-game menu's **Session** tab picks how the picture fills the screen. It changes right away.

- **16:9**: the game's own picture, as big as fits.
- **4:3 crop**: the middle of the picture in 4:3, bigger. Its sides are cut off (about an eighth of the width on each side), along with anything the game draws there.
- **4:3 squeeze**: the whole picture squeezed into 4:3, so everything looks narrower.
- **Fill**: the whole picture stretched over the whole screen.

Persona 4 Golden only draws 16:9 (960×544) and no mod is known that makes it draw 4:3, so these crop or squeeze its picture. In 4:3 crop the 3D view looks the way a 4:3 game would, but the game still places its menus and status display for 16:9, so parts near the left and right edges get cut. Moving those into 4:3 would need a patch to the game itself.

## Experiments

The in-game menu's **Session** tab has an **Experiments** card:

- **Remove motion blur** (on by default): Persona 4 Golden draws the previous frame back over each new frame, partly transparent, which is its ghosting blur. The renderer finds that full-screen draw and skips it, or gives it the new frame if it mixes the two. **Show details** lists every full-screen draw that read back an earlier frame, and what the fix did with it. A screenshot of that list shows how the game draws its blur, to tune the fix.
- **60 FPS** (off by default): Vita3K's FPS hack, so the game draws a frame every screen refresh instead of every other one. Persona 4's engine moves the game on by a stored time per frame, 1/30 s. The PS2 game's 60 FPS patch sets it to 1/60. So at 60 FPS the game may run twice as fast, and the **speed fix** finds the 1/30 s values in the game's executable and sets them to 1/60 s:
  - The values in its data change while it runs. **All** changes every one, and the numbers try them one at a time.
  - **Also fix the values in the game's code** writes a Vita3K patch file (`patch/<title ID>_V3GOLD_SPEED.txt`), applied the next time the game starts.

Nobody has published a 60 FPS patch for the Vita version, so this is a search. The menu shows the game's frame rate and how many values it found.

## Audio

V3 Gold uses Vita3K's **Cubeb** audio backend: a low-latency stream for each of the game's audio channels. Vita3K's default, SDL, lets several of the phone's audio buffers queue up, so voices and music lagged behind the game. It's set once, also on installs from before 1.0.4. **Emulator settings → Audio** can switch back to SDL.

## Limitations

- Vita3K is experimental. Persona 4 Golden is playable, but expect occasional glitches or crashes. Save often in the game.
- Vita3K has no save states, so there's no quick save or quick load. Saving works at the game's own save points.
- There's no fast-forward. Vita3K can't speed a game up.
- 60 FPS and the motion blur removal are experiments (see above).

## How it's built

As with the Dolphin apps, this folder holds only V3 Gold's changes. CI applies them to the pinned upstream Vita3K commit:

| Path | Contents |
| --- | --- |
| `vita3k-version.env` | The upstream Vita3K commit to build from |
| `app.env` | Package, app name and release tag prefix |
| `patches/` | Changes to Vita3K's own files: app identity, version and signing from Gradle properties, gestures in the input overlay, the main menu and in-game menu entries, the Persona 4 Golden palette, the experiments' renderer hooks, Cubeb audio by default, the screen shapes |
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
