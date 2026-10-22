// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww

import android.content.Context
import android.content.Intent
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.client.ClientBuild
import com.logie.warcraft.client.ClientConfig
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.tww.core.TwwRealm
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.RootFSInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume

/** One Wine container for this realm's Windows server and client, never a second guest launcher. */
object TwwGame {
    const val CONTAINER_NAME = TwwRealm.DISPLAY_NAME
    internal const val SESSION_EXTRA = "tww_session"

    /**
     * This changes text settings only. The executable is never patched or replaced. The source
     * publisher documents portal=127.0.0.1; that alone cannot remove Firestorm's authentication
     * modifications. The explicit acknowledgement is bound to the executable's digest.
     */
    fun configureLocal(context: Context, acknowledgeFirestorm: Boolean = false) = configureLocal(context, acknowledgeFirestorm, false)

    internal fun configureInstallation(context: Context) = configureLocal(context, true, true)

    private fun configureLocal(context: Context, acknowledgeFirestorm: Boolean, installing: Boolean) {
        if (TwwJobs.busy && !installing || TwwServer.isBusy) throw IOException("Wait for installation and stop this realm before changing the client configuration.")
        val report = TwwFiles.report(context)
        val executable = report.clientExecutable ?: throw IOException("Import the 11.2.5 client first.")
        val version = executable.inputStream().use(ClientBuild::read)
        if (version == null || version.major != 11 || version.minor != 2 || version.patch != 5 || version.build != TwwRealm.CLIENT_BUILD) {
            throw IOException("The client must be 11.2.5 build ${TwwRealm.CLIENT_BUILD}; found ${version ?: "no readable version"}.")
        }
        if (report.firestormModified && !acknowledgeFirestorm) {
            throw IOException("Firestorm modifications were found. Review and acknowledge the local-connection warning before changing Config.wtf. No executable will be changed.")
        }
        val config = File(executable.parentFile, "WTF/Config.wtf")
        config.parentFile?.mkdirs()
        backup(config)
        val previous = if (config.isFile) config.readText() else ""
        atomicWrite(config, ClientConfig.updateConfig(previous, mapOf("portal" to TwwRealm.ADDRESS)))
        val record = TwwFiles.fs(context).check(File(TwwFiles.layout(context).root, "local-client.sha256"))
        atomicWrite(record, sha256(executable) + "\n")
    }

    fun localConfigured(context: Context): Boolean = runCatching {
        val executable = TwwFiles.report(context).clientExecutable ?: return@runCatching false
        val record = File(TwwFiles.layout(context).root, "local-client.sha256")
        val config = File(executable.parentFile, "WTF/Config.wtf")
        record.isFile && record.readText().trim() == sha256(executable) && config.isFile &&
            Regex("""(?im)^\s*SET\s+portal\s+"127\.0\.0\.1"\s*$""").containsMatchIn(config.readText())
    }.getOrDefault(false)

    /** A launch request is queued in the existing Windows session, after real server readiness. */
    fun play(context: Context) = TwwServer.launchGame(context)

    internal suspend fun openSession(context: Context) = com.logie.warcraft.local.WindowsRealmGame.openSession(
        context, CONTAINER_NAME, SESSION_EXTRA, com.logie.warcraft.local.LocalInstallation(TwwFiles.layout(context).root))

    internal fun showSession(context: Context) = com.logie.warcraft.local.WindowsRealmGame.showSession(context, SESSION_EXTRA)

    internal fun backup(file: File) {
        if (file.isFile) {
            val backup = File(file.parentFile, file.name + ".before-local")
            if (java.nio.file.Files.isSymbolicLink(file.toPath()) || java.nio.file.Files.isSymbolicLink(backup.toPath())) {
                throw IOException("Configuration backup paths must not be symbolic links.")
            }
            if (!backup.exists()) file.copyTo(backup)
        }
    }

    internal fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || java.nio.file.Files.isSymbolicLink(temporary.toPath())) {
            throw IOException("Configuration paths must not be symbolic links.")
        }
        temporary.writeText(content)
        if (!temporary.renameTo(file)) { temporary.delete(); throw IOException("Could not write ${file.name}.") }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
