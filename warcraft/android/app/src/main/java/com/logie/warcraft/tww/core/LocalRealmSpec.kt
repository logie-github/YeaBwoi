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
    val buildTable: String = "build_info",
    val authenticationSha256: String? = null,
    val worldSha256: String? = null,
    val minimumCascBytes: Long = 0,
    val clientMachines: Set<Int> = setOf(0x8664),
) {
    val version: String get() = "$major.$minor.$patch"
    val hasPinnedCore: Boolean get() = authenticationSha256 != null && worldSha256 != null
    val pendingBuildCheck: String get() = if (hasPinnedCore) "Server executables must match the inspected build $build release"
        else "Client build $build will be verified from the authentication database when the local server starts"
    val proofSource: String get() = if (hasPinnedCore) "inspected-server-release" else "authentication-database-$buildTable"
    fun buildQuery(): String = when (buildTable) {
        "build_info" -> "SELECT COUNT(*) FROM build_info WHERE build=$build;\n"
        "battlenet_components" -> "SELECT COUNT(*) FROM battlenet_components WHERE Program='WoW' AND Build=$build;\n"
        else -> error("Unsupported server build evidence table")
    }

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
