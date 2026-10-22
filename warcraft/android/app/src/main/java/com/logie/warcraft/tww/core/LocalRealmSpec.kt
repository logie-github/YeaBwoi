// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.core

/** Version and format policy for a downloaded local realm, separate from its discovered files. */
data class LocalRealmSpec(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
    val streamingExecutableHashes: Set<String> = emptySet(),
    val executableNames: Set<String> = setOf("wow.exe", "wow-64.exe"),
    val db2Prefixes: List<String> = listOf("WDC"),
    val proofFile: String = ".local-build-proof.properties",
    val serverLayout: LocalServerLayout? = null,
) {
    val version: String get() = "$major.$minor.$patch"
    val pendingBuildCheck: String get() = "Client build $build will be verified from the authentication database when the local server starts"

    companion object {
        val WAR_WITHIN = LocalRealmSpec(11, 2, 5, TwwRealm.CLIENT_BUILD,
            streamingExecutableHashes = setOf(TwwRealm.STREAMING_EXE_SHA256), proofFile = ".tww-build-proof.properties")
    }
}

/** A publisher layout verified against an archive and its launcher, never arbitrary UI input. */
data class LocalServerLayout(
    val authenticationExecutable: String,
    val worldExecutable: String,
    val authenticationConfig: String,
    val worldConfig: String,
    val workingDirectory: String,
    val databaseExecutable: String,
    val databaseConfig: String,
    val databaseData: String,
    val launcher: String,
)
