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
    // downloaded game files (each named by its id), for the tests that check the real ones (-Dwifecraft.gamefiles=DIR)
    System.getProperty("wifecraft.gamefiles")?.let { systemProperty("wifecraft.gamefiles", it) }
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
