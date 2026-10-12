// Runs the World of Warcraft app's platform-independent code (account passwords, config files,
// recognising the client and its data and setting it up, update picking, reading the servers' logs,
// the game's touch gestures, reading warperia.com's add-on pages) on the JVM, without Android. The sources
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
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/controls")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/addons")
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    compileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
