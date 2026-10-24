// Runs the World of Warcraft app's platform-independent code (account passwords, config files,
// recognising the client and its data and setting it up, update picking, reading the servers' logs,
// the game's touch gestures, reading warperia.com's add-on pages, GitHub's AzerothCore modules and
// the realm builds with them, Turtle WoW's isolation and installation logic, Wrath Remix's time
// anomaly reports) on the JVM, without Android. The sources live in the app.

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
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/modules")
        // Turtle WoW's platform-independent parts: its folder guard, manifests, downloads, accounts.
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/turtle/core")
        // Felsong's platform-independent parts: its folder layout, install plan, Legion accounts, client checks.
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/felsong/core")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/tww/core")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/spp/core")
        // Local realms' Wine with no screen: its processes, environment and marker.
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/local/core")
        kotlin.srcDir("../android/app/src/main/java/com/logie/warcraft/reports")
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    // Android has org.json built in; the module browser's code reads GitHub's answers with it.
    implementation("org.json:json:20240303")
    compileOnly("org.jspecify:jspecify:1.0.0")
    // Turtle WoW's realm package (tar.xz), unpacked as the app does: the same versions as the app.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    testImplementation(kotlin("test"))
    // Runs the app's add-on (client/TouchAddon) against a stand-in for the game's API.
    testImplementation("org.luaj:luaj-jse:3.0.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
