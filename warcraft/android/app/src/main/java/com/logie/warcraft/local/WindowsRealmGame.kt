// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import android.content.Context
import android.content.Intent
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.game.GameSettings
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
import kotlin.coroutines.resume

/** One isolated container per realm, using the application's existing Wine/Box64 runtime. */
object WindowsRealmGame {
    /** Called by TwwServer; callers must already have validated a local startup profile. */
    suspend fun openSession(context: Context, name: String, sessionExtra: String, layout: LocalInstallation, gesturesEnvironment: String? = null) {
        val app = context.applicationContext
        val installed = withContext(Dispatchers.IO) { RootFSInstaller.installIfNeeded(app) { } }
        if (!installed) throw IOException("The Windows runtime could not be installed.")
        val container = container(app, name, layout)
        val cmd = File(container.rootDir, ".wine/drive_c/windows/system32/cmd.exe")
        if (!cmd.isFile) throw IOException("The realm's Windows container has no cmd.exe; repair the Windows runtime.")
        withContext(Dispatchers.Main) {
            app.startActivity(Intent(app, XServerDisplayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("container_id", container.id)
                .putExtra("exec_path", cmd.path)
                .putExtra("exec_args", "/d /k title " + name)
                .putExtra(sessionExtra, true)
                .putExtra("local_server_session", true)
                .putExtra("gestures", gesturesEnvironment != null)
                .putExtra(com.logie.warcraft.game.GestureView.EXTRA_ENVIRONMENT, gesturesEnvironment))
        }
    }

    fun showSession(context: Context, sessionExtra: String) {
        TitleMusic.stopForGame()
        context.startActivity(Intent(context, XServerDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(sessionExtra, true))
    }

    private suspend fun container(context: Context, name: String, layout: LocalInstallation): Container {
        val manager = ContainerManager(context)
        val drives = "D:${layout.client.path}S:${layout.server.path}R:${layout.root.path}M:${layout.maps.path}"
        val size = GameSettings.gameSize(context).let { "${it.first}x${it.second}" }
        val existing = manager.containers.firstOrNull { it.name == name }
        if (existing != null) {
            existing.setScreenSize(size)
            existing.setDrives(drives)
            withContext(Dispatchers.IO) { existing.saveData() }
            return existing
        }
        val defaults = Container(0)
        val data = JSONObject()
            .put("name", name).put("screenSize", size).put("drives", drives)
            .put("envVars", defaults.getEnvVars()).put("graphicsDriver", defaults.getGraphicsDriver())
            .put("dxwrapper", defaults.getDXWrapper()).put("audioDriver", defaults.getAudioDriver())
            .put("wincomponents", defaults.getWinComponents()).put("hudMode", defaults.getHUDMode().toInt())
            .put("startupSelection", defaults.getStartupSelection().toInt()).put("box64Preset", defaults.getBox64Preset())
            .put("desktopTheme", defaults.getDesktopTheme())
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Container?> { continuation ->
                manager.createContainerAsync(data) { created ->
                    if (continuation.isActive) continuation.resume(created)
                }
            }
        } ?: throw IOException("The realm's Windows container could not be created.")
    }

}
