// Runs V3 Gold's platform-independent code (touch gestures, update picking) on the JVM, without
// building Vita3K. The sources live in the overlay copied into Vita3K's Android app.

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
        kotlin.srcDir("../overlay/android/app/src/main/java/com/logie/v3gold/gesture")
        kotlin.srcDir("../overlay/android/app/src/main/java/com/logie/v3gold/update")
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
