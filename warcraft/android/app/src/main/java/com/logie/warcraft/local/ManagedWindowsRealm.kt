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
    /** The realm's container ready for its Wine with no screen ([WindowsRealmGame.prepareRuntime]). */
    suspend fun prepareRuntime(context: Context): com.winlator.container.Container
    /** The game's session joining the realm's running Wine ([WindowsRealmGame.openClientSession]), from the launcher. */
    fun openClientSession(context: Context, container: com.winlator.container.Container)
    fun showSession(context: Context)
    fun prepareClientLaunch(context: Context) {}
    /** The game ended [seconds] after its launch at [launchedAt] with exit [code]: what to say of it (null: nothing of the realm's own). */
    fun clientEnded(context: Context, launchedAt: Long, seconds: Long, code: String): String? = null
    /** The client's command-line arguments after its program (each a plain word: no quotes or spaces). */
    fun clientArguments(context: Context): List<String> = emptyList()
    /** The game's own logs, for a report of its failure (null: none). */
    fun clientLogs(context: Context): String? = null
    /** The world server's settings the app sets at every start, before it loads (the realm's choices). */
    val worldSettings: Map<String, String> get() = emptyMap()
    /** SQL for the world database, run at every start before the world server loads it; safe to repeat. */
    val worldStartupSql: String? get() = null
    /** SQL for the authentication database, run at every start with the world's; safe to repeat. */
    val loginStartupSql: String? get() = null
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
