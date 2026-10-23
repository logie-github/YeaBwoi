// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import android.content.Context
import com.logie.warcraft.tww.core.TwwInstall
import java.io.File

data class LocalDownloadSource(val url: String, val size: Long? = null, val sha256: String? = null, val connections: Int = 1)

/** Sources and normalization policy plugged into the application's existing staged installer. */
interface LocalRealmInstallation : ManagedWindowsRealm {
    val id: String
    val server: WindowsRealmServer
    val jobs: LocalInstallJobs
    val sources: Map<LocalInstallJobs.Kind, LocalDownloadSource>
    val receiptHeader: String
    fun findClientRoot(folder: File): File?
    fun findServerRoot(folder: File): File?
    fun inspect(client: File, server: File, maps: File): TwwInstall.Report
    fun configureServer(context: Context) = server.configureInstallation(context)
    fun configureClient(context: Context)
}
