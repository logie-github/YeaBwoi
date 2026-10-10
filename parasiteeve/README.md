<p align="center">
  <img src="branding/logo.png" height="110" alt="Parasite Eve logo">
</p>

# Parasite Eve

An Android app that plays one game: **Parasite Eve** for the PlayStation. It runs on [Beetle PSX HW](https://github.com/libretro/beetle-psx-libretro), libretro's port of Mednafen's PlayStation emulation, through [LibretroDroid](https://github.com/Swordfish90/LibretroDroid). Beetle draws with the phone's GPU (OpenGL ES), at 4× the PlayStation's resolution by default, and can draw an **HD texture pack** (like VieRock Retro HD's [Parasite Eve HD](https://sites.google.com/view/vierockretrohd/ps1/parasite-eve-hd)) in place of the game's own backgrounds, menus and text. You play with taps and swipes anywhere on the screen. The title screen is styled after the game's New York at night: Aya on the shore, the logo, and a river skyline under film grain.

| | |
| --- | --- |
| Package | `com.logie.pe` |
| Releases | **Parasite Eve 1.0.N**, tags `pe-build-N` (**Actions → Build Parasite Eve**) |
| Emulator | Beetle PSX HW, built for arm64 from the commit in `core-version.env`, and LibretroDroid as the [Azure Dreams app](../azuredreams/README.md) builds it |

Versions up to 1.0.1 ran on SwanStation, the Azure Dreams app's core. On some phones its OpenGL ES renderer left Parasite Eve's pre-drawn backgrounds, menu panels and text black or missing while the 3D drew, and it has no HD texture replacement; Beetle PSX HW has both a renderer that copes with the game's 2D and the texture packs made for it.

## First launch

1. Open the app and tap **Choose files** (or the **Discs** tile).
2. Pick your Parasite Eve discs: the `.cue` and `.bin` files of both discs together, or a `.chd`, `.pbp` or `.iso` file for each. The app tells the discs apart by their names ("Disc 1", "CD2", ...) and copies them into its own storage, so you can delete the originals afterwards. Disc 2 can wait: **Add disc 2** on the title screen, or the **Discs** tile, adds it later.
3. Tap **[ press start ]**.

A `.pbp` with both discs in it (made for the PSP) works too: it takes both places.

Add a BIOS dumped from your own PlayStation (`scph1001.bin`, `scph5501.bin` and the like, 512 KB, any region): **Add BIOS** on the title screen, or the **BIOS** tile. The game boots without one, on OpenBIOS (a free BIOS Beetle carries), but the game's own memory card saves are only known to work with a real BIOS. Save states work either way.

After that, opening the app goes straight into the game once it has checked for updates. The game always starts fresh, at its own title screen: load your save from the memory card there.

## Disc 2

At the end of disc 1 the game saves and asks for disc 2. Open this app's menu (back gesture, or the two-finger menu gesture) and pick **disc 2** next to **Disc**: the lid opens, disc 2 goes in and the lid closes, as on a PlayStation. The same row puts disc 1 back.

The app starts the game from a playlist of the installed discs (`game/Parasite Eve.m3u`), so both are in the emulator's disc tray from the start. A save state remembers the disc that was in, and loading it puts that disc back.

## Controls

The gestures are Azure Dreams', with Triangle, Parasite Eve's menu, on the double tap.

| Gesture | Button | In Parasite Eve |
| --- | --- | --- |
| Swipe anywhere | D-pad, any of 8 directions, held while the finger stays down | Walk, pick in menus |
| Tap | Cross | Examine, talk, OK; in battle, carry out the command |
| Hold | Circle, held | Cancel |
| Hold, then drag (or hold and swipe with another finger) | Circle + D-pad | Run |
| Hold, and tap with another finger (again and again while you hold) | Triangle, with Circle still held | |
| Tap, then tap and hold | R1 on the right half of the screen, L1 on the left, held | Scroll menus |
| Double tap | Triangle | The menu |
| Two fingers swipe up | Square | Skip the battle command |
| Two fingers swipe right / left | L1 / R1 | Scroll menus up / down |
| Two fingers swipe down | Start | Pause |
| Two-finger hold | Select, held | |
| Two-finger tap, then two-finger tap and hold | This app's menu | |
| Android's back gesture | This app's menu | |

A swipe presses the D-pad as soon as it starts and follows the finger: the start point is dragged along about 6 mm behind it, so the D-pad points where the finger last moved and turning around is instant. Moving back to the start point lets go. A tap waits a quarter of a second before it presses Cross, to tell it apart from a double tap. While one finger walks, a second can tap for Cross or hold for Circle. While a finger holds Circle (running too), taps with another finger are Triangle instead of Cross.

A game controller works too; plug it in or pair it and play.

## In-game menu

Back gesture or the two-finger menu gesture. The game pauses while it's open.

- **Save state / Load state**: three slots. A state keeps the exact moment, anywhere, even mid-battle. The game's own saves are on the memory card and aren't touched by states.
- **Speed**: 1×, 2× or 3× (fast forward). The sound keeps its pitch: it's time-stretched to the game's speed rather than played back faster.
- **Disc**: which disc is in (once both are added).
- **Picture**: **Resolution** (1×, 2×, 4× or 8× the PlayStation's, 4× to start with: 1280 × 960), **Steady 3D** (PGXP: no wobbling polygons or warping textures), **HD textures** (once a pack is installed), and sharp pixels, smooth, CRT TV scanlines or upscaled. The resolution sharpens Aya and the creatures; the backgrounds are pre-drawn pictures and keep their own, unless an HD texture pack redraws them.
- **Controls**: the gesture guide and the vibration switch.
- **Reset game**, **Check for updates**, **Quit to title**.

Switching apps or the phone sleeping pauses the game where it is. Closing the app or **Quit to title** ends it: the next start boots the game fresh to its title screen. Save in the game (or in a save state slot) before closing.

## HD textures

An HD texture pack redraws the game's textures in high definition: VieRock Retro HD's [Parasite Eve HD](https://sites.google.com/view/vierockretrohd/ps1/parasite-eve-hd) redraws the backgrounds, menus and, in its `Text` folder, the text. Packs made for Beetle PSX work: a folder of images named after the textures they replace (`<texture hash>-<palette hash>.png`).

1. Download all the parts of the pack. Parasite Eve HD comes as a 7z archive split in four (`.7z.001` to `.7z.004`, about 27.5 GB together).
2. In the app, open the **HD textures** tile, tap **Add pack** and pick all four parts together.
3. The app unpacks the pack's images into its own storage: about 28 GB for Parasite Eve HD, so that much has to be free besides the parts. It reads the parts where they are (no copy first), checks each image against the archive's checksum, and puts every image, its `Text` folder's too, in one folder, as Beetle wants them. It takes several minutes; it carries on with the app in the background, with a notification showing how far it is and a button to stop it.
4. Once it's done, the app offers to delete the downloaded parts.

The game draws the pack from the next time it starts. **HD textures** in **Picture** (on the title screen and in the in-game menu) switches it off and on; switched during the game, a change shows from the next room on, when the game loads its textures again. The **HD textures** tile also replaces the pack with another or deletes it.

Split 7z archives (`.7z.001`, `.002`, ...), whole `.7z` files (LZMA or LZMA2, solid or not) and `.zip` files work. A missing part, a part that didn't finish downloading or a damaged one is reported, and nothing is installed from it.

Decoded HD textures are kept in memory so they aren't read again: up to 256 MB to 1 GB of RAM and 512 MB to 1.5 GB of GPU memory, depending on how much the phone has.

## Saves

The game saves to memory card 1, shared by both discs, which the app keeps in `saves/card1.mcd` in its storage: it's written within a few seconds of the game saving, and whenever the game pauses (leaving the app, the in-game menu), so a save is kept even if the app is closed right after. The first change in a session keeps the card it replaces as a backup (`.backup`). The **Memory card** tile imports a card from another emulator or a PlayStation and exports a copy.

Save states and the memory card stay when the app updates and when you replace or delete a disc. Save states made on SwanStation (versions up to 1.0.1) don't load on Beetle; they're kept, renamed `.swanstation`, in the app's `states/` folder. The memory card works on both.

## Updates

Like the other apps: the app checks this repository's releases (`pe-build-N`) when it opens and from **Check for updates**, and installs over itself. A private repository needs the `DOLPHINXD_UPDATE_TOKEN` secret (see the main README). It's signed with the same key as the other apps.

## How it's built

`scripts/build-beetle.sh` builds Beetle PSX HW for arm64 with the NDK, at the commit in `core-version.env`, with `patches/beetle/`, into `android/core/`: its hardware renderer with OpenGL ES only (LibretroDroid has no Vulkan), and its OpenGL renderer attaching the HD textures it has decoded (upstream, only the Vulkan renderer did; the OpenGL one decoded them and never drew them).

LibretroDroid is the Azure Dreams app's build, shared rather than copied: `scripts/prepare-libretrodroid.sh` prepares it in `azuredreams/android/libretrodroid/` with `azuredreams/patches/libretrodroid/` (among them: OpenGL ES contexts only, a framebuffer that follows the frames' size, and fast forward's time-stretched sound). This app's Gradle build uses it from there.

The 7z extraction is the app's own native code (`android/app/src/main/cpp/`): the LZMA SDK's archive reader (public domain, `lzma/`) and a decoder that streams each solid block through a dictionary-sized window, so a solid block of any size needs no more memory than its dictionary, reading the parts straight from the files the player picked.

| Path | Contents |
| --- | --- |
| `app.env` | Package, app name and release tag prefix |
| `core-version.env` | The Beetle PSX HW commit to build |
| `patches/beetle/` | Changes to Beetle PSX HW: HD textures drawn by the OpenGL renderer, and building with OpenGL ES only |
| `android/` | The app (`app/`): title screen, game screen, in-game menu, gestures, discs, HD texture packs (`hd/`, and the 7z extraction in `cpp/`), update checker |
| `branding/` | Source art: the header (`header.jpg`) and the logo. `make-icons.py` makes the launcher icon from the header, centered on Aya's face, and the themed icon from the logo's "PE". |
| `fonts/` | License of Jost, the typeface (SIL Open Font License) |
| `unit-tests/` | JVM unit tests for the gesture recognizer, the update picker and sorting picked files into discs |

To build locally, you need JDK 17+ and the Android SDK with NDK 29.0.14206865 and CMake 3.31.6:

```sh
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865" scripts/build-beetle.sh
scripts/prepare-libretrodroid.sh
cd parasiteeve/android
./gradlew assembleRelease -PpeVersionCode=1 -PpeVersionName=1.0.1 \
  -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" -Pstorepass=dolphinxd -Pkeyalias=dolphinxd -Pkeypass=dolphinxd
```

## License

The app is GPLv3 (LibretroDroid's license; Beetle PSX HW is GPLv2 or later). OpenBIOS, inside Beetle, is MIT. The LZMA SDK is public domain. Jost is under the SIL Open Font License (`fonts/`). Parasite Eve is a trademark of Square Enix; its art belongs to Square Enix. HD texture packs belong to their makers and aren't part of the app. This project isn't affiliated with Square Enix, the Beetle PSX or Mednafen teams, VieRock Retro HD, or LibretroDroid.
