// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.content.Context
import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.local.LocalConfig
import com.logie.warcraft.local.LocalDownloadSource
import com.logie.warcraft.local.LocalInstallJobs
import com.logie.warcraft.local.LocalRealmInstallation
import com.logie.warcraft.spp.core.SppInstall
import com.logie.warcraft.spp.core.SppRealm
import java.io.File
import java.io.IOException

object SppIntegration : LocalRealmInstallation {
    override val id = SppRealm.ID
    override val displayName = SppRealm.DISPLAY_NAME
    override val sessionExtra = SppGame.SESSION_EXTRA
    override val spec = SppRealm.SPEC
    override val receiptHeader = "SPP Legion 7.3.5 import receipt v1"
    override val installationBusy get() = SppJobs.busy
    override val server get() = SppServer
    override val jobs get() = SppJobs
    override val sources = mapOf(
        LocalInstallJobs.Kind.DOWNLOAD_CLIENT to LocalDownloadSource(SppRealm.CLIENT_URL, SppRealm.CLIENT_SIZE, SppRealm.CLIENT_SHA256),
        LocalInstallJobs.Kind.DOWNLOAD_SERVER to LocalDownloadSource(SppRealm.SERVER_URL, SppRealm.SERVER_SIZE, SppRealm.SERVER_SHA256),
    )
    override fun root(context: Context) = SppFiles.layout(context).root
    override fun report(context: Context) = SppFiles.report(context)
    override fun inspect(client: File, server: File, maps: File) = SppInstall.inspect(client, server, maps)
    override fun findClientRoot(folder: File) = SppInstall.clientRoot(folder)
    override fun findServerRoot(folder: File) = SppInstall.serverRoot(folder)
    override fun localClientConfigured(context: Context) = SppGame.localConfigured(context)
    override suspend fun openSession(context: Context) = SppGame.openSession(context)
    override fun showSession(context: Context) = SppGame.showSession(context)
    override fun prepareClientLaunch(context: Context) = SppGame.prepareLaunch(context)
    override fun configureClient(context: Context) = SppGame.configureInstallation(context)

    override fun configureServer(context: Context) {
        if (SppServer.isBusy) throw IOException("Stop SPP before changing its configuration.")
        val detected = SppFiles.report(context).detected ?: throw IOException("Install the SPP server first.")
        val database = detected.databaseConfig ?: throw IOException("The SPP database configuration is missing.")
        LocalConfig.backup(database)
        val port = Regex("(?im)^\\s*port\\s*=.*$")
        if (!port.containsMatchIn(database.readText())) throw IOException("The SPP database port setting is missing.")
        LocalConfig.atomicWrite(database, port.replace(database.readText(), "port=${SppRealm.DATABASE_PORT}"))
        val world = detected.worldConfig ?: throw IOException("The SPP world configuration is missing.")
        LocalConfig.backup(world)
        val conf = ConfFile(world.readText())
        conf["WorldServerPort"] = SppRealm.WORLD_PORT.toString()
        if (conf["Solocraft.Enable"] == null) conf.setSwitch("Solocraft.Enable", true)
        LocalConfig.atomicWrite(world, conf.toString())
        SppServer.configureInstallation(context)
    }
}
