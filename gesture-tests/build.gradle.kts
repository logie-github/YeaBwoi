// Runs the touch gesture recognizer's unit tests on the JVM, without building Dolphin.
// The recognizer source lives in the overlay that gets copied into Dolphin's Android app.

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
        kotlin.srcDir("../overlay/Source/Android/app/src/main/java/com/logie/dolphinxd/gesture")
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
