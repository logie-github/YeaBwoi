# Wifecraft

An Android scene simulation. Choose a location, pick or create NPCs, and select the objects available in the room.

1. Open **Choose Scene** and pick an inn or the Stormwind Embassy.
2. Select up to six NPCs. **Create NPC** saves a character to your roster. NPCs from older projects also appear in the roster.
3. Choose a chair, bed, table with a book, or couch. The scene places the objects automatically.
4. Begin the scene. NPCs will walk, talk, read, sit, and rest based on what is available. Select an NPC in the room or in the cast bar, then tap an object or choose an interaction. **End scene** returns to the title.

The three inn presets use original stage layouts. The Stormwind Embassy uses the existing wago.tools location pack. The character, furniture, and book models load through the app's existing wago.tools pipeline.

## Transmog and offline downloads

In the character creator, open **Transmog** to use a named outfit or mix individual pieces. Clothing is saved with the NPC and appears when the NPC joins a scene. The preview uses original race-themed stages and Warcraft scenery from wago.tools.

In **Options**, choose **Download all** or download scenes, clothes, and Warcraft character animations individually. Downloads reuse files already on the device. Sims 4 rigs and clips are imported from your own files.

## Sims 4 animations

From **Choose objects**, open **Sims 4 animations** and select **Import rig and clips**. Supply your adult Sims 4 skeleton package and animation clips. The importer reports which clips it can retarget and lets you review them before saving. Assign saved clips to NPC actions, including walking, sitting, resting, reading, and talking. Assignments apply to autonomous actions and player commands and are saved for the next scene.

The imported Sims rig supplies the source bone hierarchy, bind transforms, and animation curves. The retargeter converts mapped tracks to Warcraft's coordinates and bind pose so Warcraft mesh skinning stays intact. Source clip timing is retained. Scene movement supplies horizontal travel for the walk action, so the bound walk clip contributes the vertical root motion and mapped pose. Unassigned actions use Warcraft animations.

## Building

Run `cd android && bash gradlew :app:assembleDebug` with Android SDK 35, NDK 29.0.14206865, and CMake installed. Run the platform-independent tests with `bash android/gradlew -p unit-tests test` from this directory.
