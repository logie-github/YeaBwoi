// Runs Wifecraft's platform-independent code (the story's script and runner, the camera's
// framings, update picking) on the JVM, without Android, and checks the demo story itself.
// The sources live in the app.

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
    into(layout.buildDirectory.dir("gen/pureUi"))
}

sourceSets {
    main {
        kotlin.srcDir(pureUi.map { it.destinationDir })
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/editor")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/story")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/update")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/creator")
    }
}

dependencies {
    // org.json for the creator's Appearance (on Android it is the platform's)
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
    // The demo's script and data, for the test that plays it through.
    systemProperty("wifecraft.assets", file("../android/app/src/main/assets").absolutePath)
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
