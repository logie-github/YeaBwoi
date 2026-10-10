// Runs the Parasite Eve app's platform-independent code (touch gestures, update picking, sorting
// picked files into discs) on the JVM, without Android. The sources live in the app.

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
        kotlin.srcDir("../android/app/src/main/java/com/logie/pe/gesture")
        kotlin.srcDir("../android/app/src/main/java/com/logie/pe/update")
        kotlin.srcDir("../android/app/src/main/java/com/logie/pe/discs")
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
