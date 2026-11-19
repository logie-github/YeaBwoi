# Wifecraft

An Android scene simulation. Choose a location, pick or create NPCs, and select the objects available in the room.

1. Open **Choose Scene** and pick the Stormwind Embassy.
2. Select up to six NPCs. **Create NPC** saves a character to your roster. NPCs from older projects also appear in the roster.
3. Enable interactions for objects already in the scene. Stormwind Embassy uses its existing study table and books.
4. Begin the scene. NPCs wander until you give them an order. Select an NPC in the room or in the cast bar, then tap an object or choose an interaction. **NPC camera** follows the selected NPC. Drag to orbit and pinch or use the zoom buttons. Tap the floor in **NPC camera** to send the selected NPC there. **Room camera** restores the room view. **End scene** returns to the title.

The Stormwind Embassy uses the existing wago.tools location pack. The title and scene setup use the original Wifecraft menu backdrop. The title emblem and first-launch download art also come from the earlier Wifecraft builds. Warcraft controls and selector icons load from wago.tools. The character, furniture, and book models load through the app's existing wago.tools pipeline.

## Transmog and offline downloads

In the character creator, open **Transmog** to use a named outfit or mix individual pieces. New NPCs start with Warcraft recruit clothing. Select **None** in any slot to remove that piece. Clothing and removed slots are saved with the NPC and appear when the NPC joins a scene. The preview uses Warcraft's race-selection backdrop models from wago.tools, framed by the camera stored in each model.

In **Options**, choose **Download all** or download scenes, clothes, and Warcraft character animations individually. The download bar shows pack progress, bytes downloaded, and failures. Pause keeps completed files for the next attempt. Sims 4 rigs and clips are imported from your own files.

## Sims 4 animations

For WickedWhims, open **Choose objects → Animation library → Import WickedWhims**. Tap **Install** to download the original archive from the author’s itch.io page and add its compatible clips to the **WickedWhims** category.

From **Choose objects**, open **Animation library** and select **Import rig and clips**. Choose a Sims 4 animation package or its ZIP or 7z archive. The importer can use a humanoid skeleton included in the package. For packs without one, choose an adult Sims 4 skeleton from your game installation. The importer reports which clips it can retarget and lets you review them before saving.

Use **Animation library → Browse animations** to preview imported clips before a scene. Once the scene starts, NPCs wander. Select an NPC, choose **Play animation**, and pick a solo clip or a partner animation. For a partner animation, pick another NPC and the app starts the two roles together after they meet. Reinstall WickedWhims to read its authored role groups and refresh earlier conversions.

The imported Sims rig supplies the source bone hierarchy, bind transforms, and animation curves. The retargeter converts mapped tracks to Warcraft's coordinates and bind pose so Warcraft mesh skinning stays intact. Source clip timing is retained. Ordinary wandering and furniture commands use Warcraft animations.

## Building

Run `cd android && bash gradlew :app:assembleDebug` with Android SDK 35, NDK 29.0.14206865, and CMake installed. Run the platform-independent tests with `bash android/gradlew -p unit-tests test` from this directory.

## Smoothness

Menus, windows and buttons share one motion kit (`ui/MenuMotion.kt`, `ui/MenuMotionUi.kt`): windows fade and grow in over 160 ms and out over 120 ms, dropdowns take 90 ms, a press shrinks to 0.96 over 100 ms, and all of it is off when the phone's "remove animations" setting is on. Every tap target ignores a second tap within 300 ms. Controls take their size from the layout, never from a picture or font that has not arrived yet. Download progress reaches the screen at most four times a second and only to the parts that show it. Pictures decode on two background threads into a size-limited cache, newest request first, and a request is cancelled when its cell leaves the screen. The live scene runs on a fixed 1/120 s step (same speed at 60 and 120 Hz, no jump after a pause), draws people interpolated between steps, and writes the camera and positions straight to the renderer; the menus see a snapshot at most ten times a second. The camera uses critically damped springs with inertia and soft limits, eases between the room and NPC cameras over 350 ms, and an 8 dp touch slop separates a tap from a drag. A loading cover hides the scene until its first frames are drawn. Skins are composed at most one per frame. Desktop measurements only (Mesa llvmpipe): the first frame after dressing six NPCs went from one 427 ms frame to a worst frame of 94 ms; steady walking runs at p95 20.6 ms. Not run on a phone.
