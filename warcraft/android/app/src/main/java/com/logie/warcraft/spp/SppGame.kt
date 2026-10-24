// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.content.Context
import com.logie.warcraft.client.ClientConfig
import com.logie.warcraft.felsong.core.FelsongClientSetup
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.local.LocalConfig
import com.logie.warcraft.local.WindowsRealmGame
import com.logie.warcraft.spp.core.SppInstall
import com.logie.warcraft.spp.core.SppRealm
import java.io.File
import java.io.IOException

object SppGame {
    const val SESSION_EXTRA = "spp_session"
    private const val CONTAINER = "Single Player Project — Legion 7.3.5"

    /** The installation UI explains this copy before the user starts setup. Wow-64.exe is retained. */
    internal fun configureInstallation(context: Context) {
        if (SppServer.isBusy) throw IOException("Stop SPP before preparing its local client.")
        val layout = SppFiles.layout(context)
        val source = SppInstall.member(layout.server, "Addons/Patched_Exe_26972/${SppRealm.CLIENT_EXE}")
            ?.takeIf { it.isFile } ?: throw IOException("SPP's build 26972 local-play executable is missing from the repack.")
        if (LocalConfig.sha256(source) != SppRealm.CLIENT_EXE_SHA256)
            throw IOException("SPP's supplied local-play executable does not match the inspected release. No client executable was changed.")
        val target = SppFiles.fs(context).check(File(layout.client, SppRealm.CLIENT_EXE))
        if (!target.isFile || LocalConfig.sha256(target) != SppRealm.CLIENT_EXE_SHA256) {
            if (target.isFile) {
                val previousHash = LocalConfig.sha256(target)
                val backup = SppFiles.fs(context).check(File(target.parentFile, target.name + ".before-local-" + previousHash.take(12)))
                if (!backup.exists()) target.copyTo(backup)
                if (LocalConfig.sha256(backup) != previousHash) throw IOException("The previous local-play executable could not be backed up.")
            }
            val temporary = SppFiles.fs(context).check(File(target.parentFile, target.name + ".preparing"))
            source.copyTo(temporary, overwrite = true)
            if (LocalConfig.sha256(temporary) != SppRealm.CLIENT_EXE_SHA256 || !temporary.renameTo(target)) {
                temporary.delete(); throw IOException("The SPP local client could not be prepared. Original client files were kept.")
            }
        }
        prepareLaunch(context)
        LocalConfig.atomicWrite(File(layout.root, "local-client.sha256"), SppRealm.CLIENT_EXE_SHA256 + "\n")
    }

    internal fun prepareLaunch(context: Context) {
        val client = SppFiles.layout(context).client
        val config = SppFiles.fs(context).check(File(client, "WTF/Config.wtf"))
        LocalConfig.backup(config)
        val size = GameSettings.gameSize(context)
        val text = if (config.isFile) config.readText() else ""
        // A phone's start for the graphics, only where the game has no setting of its own yet
        // (what's chosen in the game's options stays).
        val unset = SppRealm.CLIENT_GRAPHICS.filterKeys { key -> !Regex("(?im)^\\s*SET\\s+${Regex.escape(key)}\\s").containsMatchIn(text) }
        LocalConfig.atomicWrite(config, ClientConfig.updateConfig(text,
            unset + FelsongClientSetup.values(size.first, size.second, SppLogin.selectedName(context))))
    }

    /**
     * The certificate bundle the client trusts its Battle.net server by (TrinityCore's, which the
     * repack's patched program fetches from trinity6.github.io at every start and keeps in
     * ProgramData), put there before it starts: it logs in without the internet, or that site.
     */
    internal fun installCertificateBundle(context: Context, driveC: File) {
        val bundle = context.assets.open(CERT_BUNDLE_ASSET).use { it.readBytes() }
        for (path in FelsongClientSetup.CERT_BUNDLE_IN_PREFIX) {
            val target = File(driveC, path)
            target.parentFile?.mkdirs()
            if (!target.isFile || !target.readBytes().contentEquals(bundle)) target.writeBytes(bundle)
        }
    }
    private const val CERT_BUNDLE_ASSET = "spp/web_cert_bundle"

    fun localConfigured(context: Context): Boolean = runCatching {
        val layout = SppFiles.layout(context)
        val executable = File(layout.client, SppRealm.CLIENT_EXE)
        val record = File(layout.root, "local-client.sha256")
        val config = File(layout.client, "WTF/Config.wtf")
        executable.isFile && record.isFile && record.readText().trim() == SppRealm.CLIENT_EXE_SHA256 &&
            LocalConfig.sha256(executable) == SppRealm.CLIENT_EXE_SHA256 && config.isFile &&
            Regex("(?im)^\\s*SET\\s+portal\\s+\"127\\.0\\.0\\.1\"\\s*$").containsMatchIn(config.readText())
    }.getOrDefault(false)

    internal suspend fun openSession(context: Context) = WindowsRealmGame.openSession(
        context, CONTAINER, SESSION_EXTRA, SppFiles.layout(context), "spp", SppSettings.graphicsDriver(context)) { driveC ->
        installCertificateBundle(context, driveC)
    }
    fun showSession(context: Context) = WindowsRealmGame.showSession(context, SESSION_EXTRA)
    fun play(context: Context) = SppServer.launchGame(context)
}
