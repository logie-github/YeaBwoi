// Runs Wifecraft's platform-independent code on the JVM, without Android: the story editor's
// projects, store and compiler (editor/), the script and its runner (story/), the character
// creator's catalog, looks, imports, body shapes, the glue to the renderer and the tables' fetch
// (creator/), the creator's layout and the editor's state and staging (two pure files of ui/),
// and update picking. The sources live in the app.

plugins {
    kotlin("jvm") version "2.2.20"
}

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

// The two pure files of ui/ (the rest of it is Compose): copied alone so the JVM can compile them.
val pureUi = tasks.register<Copy>("pureUi") {
    from("../android/app/src/main/java/com/logie/wifecraft/ui/CreatorLayout.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/ui/EditorState.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/ui/AnimState.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/ui/PlaceState.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/ui/PlaceModes.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/ui/PlaceLibraryModel.kt")
    // the editor's build-mode look (WarKit: palette, sizes, tint, layout) and its buttons' behaviour (BuildModeState)
    from("../android/app/src/main/java/com/logie/wifecraft/ui/WarKit.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/ui/BuildModeState.kt")
    // the WoW-like menus: the action bar, the dropdowns, the windows, the Game Menu, the title, Options (MenuModel)
    from("../android/app/src/main/java/com/logie/wifecraft/ui/MenuModel.kt")
    // the art's loading (its state machine, the answer's check) and the glyphs drawn when a picture is missing (WarArtPlan)
    from("../android/app/src/main/java/com/logie/wifecraft/ui/WarArtPlan.kt")
    // the progress throttle (Sampler, Throttle) and the off-main-thread image cache (ImageCache): the menus' smoothness
    from("../android/app/src/main/java/com/logie/wifecraft/ui/Throttle.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/ui/ImageCache.kt")
    // the menus' motion: durations, the reduce-motion switch, the double-tap gate, touch boxes, scroll thumb, list keys (MenuMotion)
    from("../android/app/src/main/java/com/logie/wifecraft/ui/MenuMotion.kt")
    // the live scene's HUD: the action bar's orders and pages, the help line, the clock, the layout, the animation tabs (SceneHud)
    from("../android/app/src/main/java/com/logie/wifecraft/ui/SceneHud.kt")
    into(layout.buildDirectory.dir("gen/pureUi"))
}

// data/ also holds Android classes: only the limiter is copied.
val pureData = tasks.register<Copy>("pureData") {
    from("../android/app/src/main/java/com/logie/wifecraft/data/FetchLimiter.kt")
    into(layout.buildDirectory.dir("gen/pureData"))
}

// play/ is Android (Compose) but for the placed things' host, which is pure.
val purePlay = tasks.register<Copy>("purePlay") {
    from("../android/app/src/main/java/com/logie/wifecraft/play/PlaceHost.kt")
    into(layout.buildDirectory.dir("gen/purePlay"))
}

// audio/ also holds Android classes: only the scene audio's decisions and the sound index are copied.
val pureAudio = tasks.register<Copy>("pureAudio") {
    from("../android/app/src/main/java/com/logie/wifecraft/audio/SceneAudioCore.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/audio/SoundIndex.kt")
    // the soundscape's decisions: the mixer, the ducking, the crossfade, which music where, sounds.json (Soundscape)
    from("../android/app/src/main/java/com/logie/wifecraft/audio/Soundscape.kt")
    // the partner animations' sounds folder (resolution, names, hashes, zip import)
    from("../android/app/src/main/java/com/logie/wifecraft/audio/SoundNames.kt")
    from("../android/app/src/main/java/com/logie/wifecraft/audio/PartnerSounds.kt")
    into(layout.buildDirectory.dir("gen/pureAudio"))
}

sourceSets {
    main {
        kotlin.srcDir(pureUi.map { it.destinationDir })
        kotlin.srcDir(pureData.map { it.destinationDir })
        kotlin.srcDir(pureAudio.map { it.destinationDir })
        kotlin.srcDir(purePlay.map { it.destinationDir })
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/editor")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/story")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/update")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/creator")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/sims")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/anim")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/location")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/catalog")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/simulation")
    }
}

dependencies {
    // org.json for the creator's Appearance (on Android it is the platform's)
    implementation("org.json:json:20240303")
    // Sims animation import: archives
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
    // The app's assets (places.json), for the tests that check them.
    systemProperty("wifecraft.assets", file("../android/app/src/main/assets").absolutePath)
    // the app's ui sources, for the art key audit (WarArtPlanTest: every picture the kit names is in ui_art.json)
    systemProperty("wifecraft.ui", file("../android/app/src/main/java/com/logie/wifecraft/ui").absolutePath)
    // downloaded game files (each named by its id), for the tests that check the real ones (-Dwifecraft.gamefiles=DIR)
    System.getProperty("wifecraft.gamefiles")?.let { systemProperty("wifecraft.gamefiles", it) }
    // the build mode's layout for the mock images (WarKitTest.dumpForMocks: -Dwifecraft.layoutOut=FILE)
    System.getProperty("wifecraft.layoutOut")?.let { systemProperty("wifecraft.layoutOut", it) }
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
