# How the single-game apps are made

This repository builds Android apps that each play **one** game on an emulator and behave like a
native game app: Dolphin XD and Dolphin Colosseum (GameCube, Dolphin), V3 Gold (PS Vita, Vita3K),
Azure Dreams and Parasite Eve (PlayStation, libretro cores through LibretroDroid). This is the
playbook they were built with, for whoever builds the next one. The apps' own READMEs hold the
details of each; `parasiteeve/` is the most complete and most recent template.

## What every app is

- **One game, one app.** Its own package (`com.logie.<name>`), name, icon and storage. It installs
  next to the others and never touches them.
- **No on-screen buttons.** You play with taps and swipes anywhere on the screen, mapped to the
  console's buttons the way the user asks for that game. A game controller works too.
- **A title screen in the game's style**, built from the art the user sends (a header image, a
  logo, an icon), instead of the emulator's own screens. Tiles under it open the settings, file
  setup, save management and updates.
- **Straight into the game.** Once set up, opening the app checks for an update, then starts the
  game. Leaving the game (or Android's back gesture, or a two-finger gesture) opens an in-game menu
  over the paused game: save states, settings, quit to title.
- **The user brings the game files.** Nothing from the game ever goes into the repository, the
  APK or CI. The app imports the user's files (disc images, BIOS, packs) into its own storage
  through Android's file picker.
- **Saves are safe.** Memory cards are written as soon as the game saves and when it pauses, with
  a backup of the card each session replaces; save states are kept per slot; updates keep both.
- **It updates itself** from this repository's GitHub releases.
- **Every change ships.** A push to the branch builds the app in GitHub Actions and publishes it
  as a release, `<App name> 1.0.N`, tag `<prefix>-N` (N is the workflow's run number). The user
  installs from the release page on the phone; after that, the app's updater takes over.

## Two ways to build one

### A. An upstream Android emulator app, patched (Dolphin apps, V3 Gold)

When the emulator already is a full Android app, the repository holds only the changes:

| Path | Contents |
| --- | --- |
| `<app>/<emulator>-version.env` | The upstream repository and the pinned commit or tag |
| `<app>/patches/*.patch` | Changes to upstream's own files, as `git format-patch` output |
| `<app>/overlay/` | New files (the app's own code, layouts, art), copied over the tree |
| `scripts/prepare-<emulator>.sh` | Fetches the pinned commit, `git am`s the patches, copies the overlay |

The tree is built in `build/<emulator>` (git-ignored). To change upstream code: edit it there,
commit, and regenerate the patches with
`git format-patch --zero-commit --no-signature -o <app>/patches <pinned>..HEAD`. Patch 0001 is
always the app identity: package, name, version and signing taken from Gradle properties, and the
update source. Later patches: gestures in place of the on-screen controller, the app's own menus,
the game's palette over the emulator's screens, and fixes.

### B. The app's own code around an emulator library (Azure Dreams, Parasite Eve)

When the emulator is a library (a libretro core), the app is written here, in Kotlin and Jetpack
Compose, under `<app>/android/`:

| Path | Contents |
| --- | --- |
| `<app>/app.env` | `APP_ID`, `APP_NAME`, `GAME_NAME`, `APK_NAME`, `RELEASE_TAG_PREFIX` |
| `<app>/core-version.env` | The core's repository and pinned commit |
| `<app>/patches/<core>/` | Changes to the core, applied by its build script |
| `scripts/build-<core>.sh` | Fetches the core, applies the patches, builds it for arm64 with the NDK into `<app>/android/core/arm64-v8a/` (git-ignored), packaged with `jniLibs.srcDirs += ['../core']` |
| `azuredreams/android/libretrodroid/` | LibretroDroid built from source (`scripts/prepare-libretrodroid.sh`, `azuredreams/libretrodroid-version.env`, `azuredreams/patches/libretrodroid/`), shared by both libretro apps through `settings.gradle` |
| `<app>/android/app/` | The app |
| `<app>/branding/` | The user's art and `make-icons.py`, which makes the launcher icon layers |
| `<app>/fonts/` | Licenses of bundled fonts (SIL OFL) |
| `<app>/unit-tests/` | A JVM Gradle project that compiles the app's platform-independent packages (gestures, update picking, file sorting) and tests them |
| `<app>/README.md` | The user-facing manual (see "README" below) |

A new app of this kind starts as a copy of `parasiteeve/`, renamed.

## The app, piece by piece (Parasite Eve's names)

- `MainActivity` → `ui/PeHome.kt` `PeHomeRoute`: the title screen. Header art with a slow drift,
  the logo, a breathing `[ press start ]`, notices for anything missing (disc, BIOS) with the
  button that adds it, and a grid of tiles that open dialogs. On a fresh open it checks for an
  update, then fades to black into the game (a loading overlay hides the menu meanwhile).
- `GameActivity`: the emulator view (`GLRetroView`), the gesture layer over it, a fade from black.
  Settings changed in the menu are applied in `onResume`; save, load and reset run on the
  emulation thread once the next frame is drawn. The memory card is read every 3 s and on pause
  and written atomically.
- `MenuActivity` + `ui/PeMenu.kt`: the in-game menu, a translucent activity over the paused game;
  it returns an `Action` (save slot, load slot, change disc, reset, quit) that the game runs.
- `PeFiles`: everything in the app's storage (`files/`): `game/`, `system/` (BIOS), `saves/`,
  `states/`. Imports copy picked files through the Storage Access Framework on a background
  thread, with progress, into a staging folder first, so a failed import changes nothing.
- `PeConfig`: settings in SharedPreferences. `PeCore`: the core's library name and its options
  (libretro `Variable`s), built from the settings.
- `PeUpdater` + `PeUpdateActivity`: GitHub's releases API for this repository (a token is built in
  for a private repository), the newest release with this app's tag prefix and a higher version
  code, download, and Android's installer through a `FileProvider`.
- `gesture/GestureRecognizer.kt`: plain Kotlin, no Android types, fed touch events by
  `GestureView` and unit-tested on the JVM.
- `ui/PeTheme.kt`, `PeParts.kt`, `PeBackdrop.kt`: the theme (colors from the art, the font), the
  panel ("window"), button, switch, choice row, dialog and spinner every screen is made of, and the
  animated backdrop.

### Gestures

The user describes the controls per game in plain words ("double tap is Triangle", "two fingers
swipe down is Start"); implement exactly that, and write the README's controls table from it. The
recognizer's vocabulary, shared by all the apps:

- **Tap**, sent after a short wait (about a quarter second) so it can't be the start of a double
  tap. **Double tap.** **Hold** (pressed while the finger stays down). **Tap, then tap and hold**,
  often split into left and right halves of the screen.
- **Swipe** anywhere = the D-pad or stick in 8 directions, pressed as soon as the swipe starts and
  following the finger: the start point is dragged along about 6 mm behind it, so turning around
  is instant and moving back to it lets go.
- A second finger while one walks: tap or hold for buttons. Hold, then drag = a held button plus
  direction (running).
- **Two-finger** swipes (up, down, left, right), two-finger hold, and two-finger tap then
  two-finger tap and hold = the app's menu. Android's back gesture also opens the menu.
- A short vibration when a hold is recognized (switchable).

### The look

Each app gets its own look from the user's art and mood images: palette, typeface, motifs. The
user sends a header image, a logo and an icon (or says to make the icon from the header, centered
on the character's face). Check the title screen in portrait, landscape and tablet widths: the
user's phone is a **Galaxy Z Fold 8**, folded and unfolded. Keep the character in frame
(`ContentScale.Crop` with a bias alignment) and cap the header's height in landscape so the start
button stays visible. `branding/make-icons.py` builds the adaptive icon's background and
foreground layers and a monochrome layer for themed icons.

### Gradle

`compileSdk`/`targetSdk` 35, `minSdk` 26, NDK `29.0.14206865`, `abiFilters "arm64-v8a"`, R8
(`minifyEnabled`, `shrinkResources`) with keep rules for anything native code calls by name,
`useLegacyPackaging` when a library is loaded by file name, Compose BOM 2025.04.00. CI passes the
app's identity as properties with an app prefix (`-Ppe…`, `-Pad…`): `VersionCode`, `VersionName`,
`ApplicationId`, `UpdateRepo`, `ReleaseTagPrefix`, `UpdateToken`, plus `keystore`, `storepass`,
`keyalias`, `keypass`.

## CI and releases

One workflow per app, `.github/workflows/build-<app>.yml`, modeled on `build-parasiteeve.yml`:

- Triggers on pushes that touch the app's folder and whatever it shares (scripts, patches), not on
  its README or branding alone. The Dolphin workflow (`build-apk.yml`) has a `paths-ignore` list:
  add a new app's paths there too.
- Job 1 runs `<app>/unit-tests`. Job 2 installs the NDK and CMake with `sdkmanager`, builds the
  emulator parts, picks the signing key (the `DOLPHINXD_KEYSTORE_*` secrets, else the shared
  `ci/dolphinxd-ci.p12`, password `dolphinxd`), builds `:app:assembleRelease` with
  `versionCode = run_number`, `versionName = 1.0.<run_number>`, lists the APK's native libraries,
  and publishes the APK with `gh release create "<prefix>-N" --title "<App> 1.0.N"` and notes
  naming the pinned emulator commits.
- All apps share one signing key, so updates install over the last build.
- After pushing, follow the run (GitHub MCP `actions_list`/`actions_get`, `get_job_logs` when it
  fails) and confirm the release and its APK with `get_release_by_tag` before telling the user.

## Proving it works before the user tries it

The user can't debug: report only what was checked, and say what wasn't.

1. **Unit tests**: `gradle -p <app>/unit-tests test`.
2. **Local release build**: `./gradlew --init-script scripts/maven-mirror.init.gradle
   assembleRelease -P<prefix>VersionCode=… -Pkeystore="$PWD/../../ci/dolphinxd-ci.p12" …`
   (the init script puts Google's Maven Central mirror first: Maven Central rate-limits the cloud
   machines). Check the APK's `lib/arm64-v8a/` contents and the R8 mapping for JNI-called names.
3. **Screenshots with Robolectric**: a throwaway Gradle project (in the scratchpad) whose `app`
   points its source sets at the real app's `src/main` (manifest, java, kotlin, res), with
   `testImplementation` Robolectric 4.16, `ui-test-junit4` and `androidx.test:core`,
   `includeAndroidResources = true`, `robolectric.graphicsMode=NATIVE`, and
   `robolectric.dependency.repo.url` set to the mirror. Tests use
   `createAndroidComposeRule<ComponentActivity>()`, `@Config(sdk = [34], qualifiers =
   "w411dp-h891dp-xxhdpi")` (phone), `"w891dp-h411dp-xxhdpi"` (landscape), `"w840dp-h900dp-xhdpi"`
   (unfolded), set the content, advance `mainClock`, draw `window.decorView` into a Bitmap and save
   a PNG. A Compose `Dialog` is its own window: draw the screen, then the last view in
   `WindowManagerGlobal`'s `mViews` over it. Prepare states by writing the app's files into
   `filesDir` (or setting private state flows by reflection). Put the shots side by side and send
   them to the user.
4. **Emulator cores on the desktop**: build the core for Linux and drive it with a minimal
   libretro frontend (C++, EGL surfaceless, an OpenGL ES 3 context only, an FBO from
   `get_current_framebuffer`, options reported as changed on the first frame as LibretroDroid
   does, LibretroDroid's state resets between frames). Run frames, dump the picture, save and
   load states, change options live. A small PS-EXE test scene stands in for the game.
5. **CI** green and the release published.

## Working with this user

- Messages are short. Art and requirements arrive over several messages: "don't generate anything
  yet" means wait for the rest; "give me ideas first" means ideas only, no code.
- Ask only what can't be decided from the request or a sensible default; otherwise decide, say
  so, and build.
- Every change is committed, pushed and released; the user installs it and reports back with
  screenshots or screen recordings.
- The final report is short: what changed, how to use it, what wasn't tested on a real phone.
- Commits: `<App>: <what changed>`, a body saying what and why, and the attribution lines the
  session's instructions give. Work on the session's designated branch, no pull requests unless
  asked, no model names in anything committed, no build outputs committed (`core/`, `.cxx/`,
  `build/`).
- READMEs are manuals for the user, in plain sentences: what it is (a table with package,
  releases, emulator), First launch, Controls (a table), the menus, Saves, Updates, How it's built
  (a path table and the local build commands), License (and that the project isn't affiliated
  with the game's owner or the emulator's team).

## Lessons

- Maven Central rate-limits these machines: `scripts/maven-mirror.init.gradle`.
- LibretroDroid treats options as changed on the first frame; a core that reads options only on
  changes still gets them.
- Adreno (Snapdragon) GPUs mishandle Dolphin's ubershaders and some Vulkan paths; OpenGL ES with
  synchronous shaders was the stable choice there.
- SwanStation's OpenGL ES renderer left Parasite Eve's pre-drawn backgrounds black on the user's
  phone; Beetle PSX HW drew them. Upstream Beetle's OpenGL renderer decoded HD textures but never
  drew them (`parasiteeve/patches/beetle/0001`).
- Files picked from cloud folders aren't seekable; ask for them to be moved to the phone first.
- Long imports (gigabytes) run in a foreground service (`dataSync`, with `POST_NOTIFICATIONS`
  and a wake lock) and report progress, so leaving the app doesn't stop them.
- Android only executes files from the app's native library folder (W^X): ship executables as
  `lib<name>.so` in `jniLibs`, or target an older SDK the way Termux and Winlator do.
