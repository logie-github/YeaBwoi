// Runs Wifecraft's platform-independent code (the story's script and runner, the camera's
// framings, update picking) on the JVM, without Android, and checks the demo story itself.
// The sources live in the app.

plugins {
    kotlin("jvm") version "2.2.20"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/story")
        kotlin.srcDir("../android/app/src/main/java/com/logie/wifecraft/update")
    }
}

dependencies {
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
