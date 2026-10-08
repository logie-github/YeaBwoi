// Runs Dolphin XD's platform-independent code (touch gestures, UPS patching) on the JVM,
// without building Dolphin. The sources live in the overlay copied into Dolphin's Android app.

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
        kotlin.srcDir("../overlay/Source/Android/app/src/main/java/com/logie/dolphinxd/patch")
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
