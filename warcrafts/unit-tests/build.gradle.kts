// Runs the Warcraft app's platform-independent code (the touch gestures, finding a game
// in the files picked and the program that starts it, Warcraft III's download and install list,
// the offline hosts file, the games' resolution, update picking) on the JVM, without Android. The
// sources live in the app.

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
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcrafts/controls")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcrafts/library")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcrafts/update")
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
