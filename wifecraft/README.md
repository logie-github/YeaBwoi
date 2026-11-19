# Wifecraft

An Android scene simulation. Choose a location, pick or create NPCs, and select the objects available in the room.

1. Open **Choose Scene** and pick the Stormwind Embassy.
2. Select up to six NPCs. **Create NPC** saves a character to your roster. NPCs from older projects also appear in the roster.
3. Choose a chair, bed, table with a book, or couch. The scene places the objects automatically.
4. Begin the scene. NPCs will walk, talk, read, sit, and rest based on what is available. Select an NPC in the room or in the cast bar, then tap an object or choose an interaction. **NPC camera** follows the selected NPC. Drag to orbit and pinch or use the zoom buttons. **Room camera** restores the room view. **End scene** returns to the title.

The Stormwind Embassy uses the existing wago.tools location pack. The title and scene setup use the original Wifecraft menu backdrop. The title emblem and first-launch download art also come from the earlier Wifecraft builds. Warcraft controls and selector icons load from wago.tools. The character, furniture, and book models load through the app's existing wago.tools pipeline.

## Transmog and offline downloads

In the character creator, open **Transmog** to use a named outfit or mix individual pieces. New NPCs start with Warcraft recruit clothing. Select **None** in any slot to remove that piece. Clothing and removed slots are saved with the NPC and appear when the NPC joins a scene. The preview uses Warcraft's race-selection backdrop models from wago.tools, framed by the camera stored in each model.

In **Options**, choose **Download all** or download scenes, clothes, and Warcraft character animations individually. The download bar shows pack progress, bytes downloaded, and failures. Pause keeps completed files for the next attempt. Sims 4 rigs and clips are imported from your own files.

## Sims 4 animations

From **Choose objects**, open **Sims 4 animations** and select **Import rig and clips**. Supply your adult Sims 4 skeleton package and animation clips. The importer reports which clips it can retarget and lets you review them before saving. Assign saved clips to NPC actions, including walking, sitting, resting, reading, and talking. Assignments apply to autonomous actions and player commands and are saved for the next scene.

The imported Sims rig supplies the source bone hierarchy, bind transforms, and animation curves. The retargeter converts mapped tracks to Warcraft's coordinates and bind pose so Warcraft mesh skinning stays intact. Source clip timing is retained. Scene movement supplies horizontal travel for the walk action, so the bound walk clip contributes the vertical root motion and mapped pose. Unassigned actions use Warcraft animations.

## Partner sounds

Options > Game > Partner sounds plays sounds with partner animations from the user's own files. A clip's sound event carries a sound name; put audio files (ogg, wav, mp3, m4a, aac, flac), single or in a zip, through Add sounds, named after those names (the exact name, the name without a `vo_` or `sfx_` prefix, numbered variants `name_01`, `name_02` of which one is picked at random, a file named by the name's 32- or 64-bit FNV-1a hash in hex, or a file called `default` for the rest). A sound with no file is simply silent. The files are kept in `files/partner-sounds`; there is an On box, a volume and Remove all. The game's own EA audio (SNR/SNS) is not decoded yet. The folder (`audio/PartnerSounds`, `SoundNames`) is unit-tested; the playback (`SceneAudio.partnerSound`) and the Options rows are compiled by CI only and have not run on a phone.

## Building

Run `cd android && bash gradlew :app:assembleDebug` with Android SDK 35, NDK 29.0.14206865, and CMake installed. Run the platform-independent tests with `bash android/gradlew -p unit-tests test` from this directory.

## Smoothness

Menus, windows and buttons share one motion kit (`ui/MenuMotion.kt`, `ui/MenuMotionUi.kt`): windows fade and grow in over 160 ms and out over 120 ms, dropdowns take 90 ms, a press shrinks to 0.96 over 100 ms, and all of it is off when the phone's "remove animations" setting is on. Every tap target ignores a second tap within 300 ms. Controls take their size from the layout, never from a picture or font that has not arrived yet. Download progress reaches the screen at most four times a second and only to the parts that show it. Pictures decode on two background threads into a size-limited cache, newest request first, and a request is cancelled when its cell leaves the screen. The live scene runs on a fixed 1/120 s step (same speed at 60 and 120 Hz, no jump after a pause), draws people interpolated between steps, and writes the camera and positions straight to the renderer; the menus see a snapshot at most ten times a second. The camera uses critically damped springs with inertia and soft limits, eases between the room and NPC cameras over 350 ms, and an 8 dp touch slop separates a tap from a drag. A loading cover hides the scene until its first frames are drawn. Skins are composed at most one per frame. Desktop measurements only (Mesa llvmpipe): the first frame after dressing six NPCs went from one 427 ms frame to a worst frame of 94 ms; steady walking runs at p95 20.6 ms. Not run on a phone.
