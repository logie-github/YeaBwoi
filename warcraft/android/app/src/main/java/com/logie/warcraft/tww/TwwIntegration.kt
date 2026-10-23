// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww

import android.content.Context
import com.logie.warcraft.local.LocalDownloadSource
import com.logie.warcraft.local.LocalInstallJobs
import com.logie.warcraft.local.LocalRealmInstallation
import com.logie.warcraft.tww.core.LocalRealmSpec
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwRealm
import java.io.File

object TwwIntegration : LocalRealmInstallation {
    override val id = "tww"
    override val displayName = TwwRealm.DISPLAY_NAME
    override val sessionExtra = TwwGame.SESSION_EXTRA
    override val spec = LocalRealmSpec.WAR_WITHIN
    override val receiptHeader = "The War Within 11.2.5 import receipt v1"
    override val installationBusy get() = TwwJobs.busy
    override val server get() = TwwServer
    override val jobs get() = TwwJobs
    override val sources = mapOf(
        LocalInstallJobs.Kind.DOWNLOAD_CLIENT to LocalDownloadSource(TwwRealm.CLIENT_ARCHIVE_URL, TwwRealm.CLIENT_ARCHIVE_SIZE, TwwRealm.CLIENT_ARCHIVE_SHA256),
        LocalInstallJobs.Kind.DOWNLOAD_SERVER to LocalDownloadSource(TwwRealm.SERVER_URL),
        LocalInstallJobs.Kind.DOWNLOAD_MAPS to LocalDownloadSource(TwwRealm.MMAPS_URL),
    )
    override fun root(context: Context) = TwwFiles.layout(context).root
    override fun report(context: Context) = TwwFiles.report(context)
    override fun localClientConfigured(context: Context) = TwwGame.localConfigured(context)
    override suspend fun openSession(context: Context) = TwwGame.openSession(context)
    override fun showSession(context: Context) = TwwGame.showSession(context)
    override fun findClientRoot(folder: File) = TwwInstall.findClientRoot(folder)
    override fun findServerRoot(folder: File) = TwwInstall.findServerRoot(folder)
    override fun inspect(client: File, server: File, maps: File) = TwwInstall.inspect(client, server, maps)
    override fun configureClient(context: Context) = TwwGame.configureInstallation(context)
}
