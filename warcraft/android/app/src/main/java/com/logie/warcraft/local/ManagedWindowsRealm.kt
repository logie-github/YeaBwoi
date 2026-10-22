// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import android.content.Context
import com.logie.warcraft.tww.core.LocalRealmSpec
import com.logie.warcraft.tww.core.TwwInstall
import java.io.File

/** Application bindings for an isolated downloaded server; no realm-specific paths in the manager. */
interface ManagedWindowsRealm {
    val displayName: String
    val sessionExtra: String
    val spec: LocalRealmSpec
    val address: String get() = "127.0.0.1"
    val installationBusy: Boolean
    fun root(context: Context): File
    fun report(context: Context): TwwInstall.Report
    fun localClientConfigured(context: Context): Boolean
    suspend fun openSession(context: Context)
    fun showSession(context: Context)
}

data class LocalInstallation(val root: File) {
    val client = File(root, "client")
    val server = File(root, "server")
    val maps = File(root, "maps")
    val downloads = File(root, "downloads")
    val staging = File(root, "staging")
    val logs = File(root, "logs")
    val receipts = File(root, "receipts")
}
