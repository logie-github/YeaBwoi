// Runs the Warcraft Collection app's platform-independent code (the touch gestures, finding a game
// in the files picked and the program that starts it, CD keys, the offline hosts file, the games'
// resolution, update picking) on the JVM, without Android. The sources live in the app.

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
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraftrts/controls")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraftrts/library")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraftrts/update")
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
