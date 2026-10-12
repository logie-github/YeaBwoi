// Runs the World of Warcraft app's platform-independent code (account passwords, config files,
// recognising the client and its data and setting it up, update picking, reading the servers' logs) on the JVM, without Android. The sources
// live in the app.

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
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/auth")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/config")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/client")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/update")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/logs")
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
