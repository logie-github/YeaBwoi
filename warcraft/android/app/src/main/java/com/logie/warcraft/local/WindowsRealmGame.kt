// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import android.content.Context
import android.content.Intent
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.game.GameSettings
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
    /**
     * The realm's container ready for its Wine with no screen: the runtime installed, the container
     * made (its drives: D: the client, S: the server, R: the realm's root, M: the maps) with
     * [graphicsDriver] for the game's session, [prepareDriveC] done in its drive C:.
     */
    suspend fun prepareRuntime(context: Context, name: String, layout: LocalInstallation,
                               graphicsDriver: String? = null, prepareDriveC: ((File) -> Unit)? = null): Container {
        val app = context.applicationContext
        val installed = withContext(Dispatchers.IO) { RootFSInstaller.installIfNeeded(app) { } }
        if (!installed) throw IOException("The Windows runtime could not be installed.")
        val container = container(app, name, layout, graphicsDriver)
        val cmd = File(container.rootDir, ".wine/drive_c/windows/system32/cmd.exe")
        if (!cmd.isFile) throw IOException("The realm's Windows container has no cmd.exe; repair the Windows runtime.")
        prepareDriveC?.let { prepare -> withContext(Dispatchers.IO) { prepare(File(container.rootDir, ".wine/drive_c")) } }
        return container
    }

    /** Marks a realm's game session (its program the game, client.cmd). */
    const val REALM_CLIENT = "realm_client"

    /** The game's session, waiting for the launcher to be on screen (Android opens no screen for an app in the background). */
    @Volatile private var pending: Intent? = null

    /** A game's session is waiting for the launcher to be on screen. */
    val launchPending: Boolean get() = pending != null

    /**
     * The game's session for a realm whose Wine runs ([container]): its program R:\runtime\client.cmd
     * (the client, its exit code written when it ends), the session joining the realm's Wine server.
     */
    fun openClientSession(context: Context, container: Container, sessionExtra: String, layout: LocalInstallation, gesturesEnvironment: String? = null) {
        TitleMusic.stopForGame()
        val cmd = File(container.rootDir, ".wine/drive_c/windows/system32/cmd.exe")
        val intent = Intent(context, RealmSessionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("container_id", container.id)
            .putExtra("exec_path", cmd.path)
            // (no quotes: Winlator passes the arguments on as they are)
            .putExtra("exec_args", "/d /c R:\\runtime\\client.cmd")
            .putExtra(sessionExtra, true)
            .putExtra("local_server_session", true)
            // (a game session of this design: an older version's Recents card for a realm's session isn't one)
            .putExtra(REALM_CLIENT, true)
            // What Wine and Box64 print in the session, and how it ended (Winlator patch 0008).
            .putExtra("session_log", File(layout.root, "runtime/session.log").path)
            // Box64 with the flags of a signal's context right (Winlator patch 0009, scripts/build-box64.sh):
            // modern WoW clients' start-up protection single-steps itself and, on Box64 0.4.4, takes the
            // wrong branch and stops on a deliberate trap (seen with SPP's 7.3.5 client). The realm's
            // Wine runs the same one (WindowsRealmHost).
            .putExtra("box64_version", com.winlator.core.DefaultVersion.BOX64_LEGION)
            .putExtra("gestures", gesturesEnvironment != null)
            .putExtra(com.logie.warcraft.game.GestureView.EXTRA_ENVIRONMENT, gesturesEnvironment)
        if (com.logie.warcraft.MainActivity.visible) {
            pending = null
            context.startActivity(intent)
        } else pending = intent
    }

    /**
     * The launcher came on screen: opens the game's session asked for while the app was in the
     * background, if its realm still runs (its Stop, or a failure, drops it: [cancelPending]).
     */
    fun onLauncherShown(context: Context) {
        val intent = pending ?: return
        pending = null
        if (WindowsRealmRegistry.servers().none { it.allowsSession() }) return
        context.startActivity(intent)
    }

    /** A game's session waiting for the launcher isn't wanted any more (its realm stopped or failed). */
    fun cancelPending() { pending = null }

    /**
     * The game's running session in front (from the launcher, on screen): its task brought forward,
     * never a new session's screen (one without its container would be made if the old were closing).
     */
    @Suppress("UNUSED_PARAMETER")
    fun showSession(context: Context, sessionExtra: String) {
        val tasks = context.getSystemService(android.app.ActivityManager::class.java)?.appTasks.orEmpty()
        val task = tasks.firstOrNull { runCatching { it.taskInfo.baseIntent.let { intent ->
            intent.component?.className == RealmSessionActivity::class.java.name && intent.getBooleanExtra(REALM_CLIENT, true) } }.getOrDefault(false) }
            ?: return
        TitleMusic.stopForGame()
        task.moveToFront()
    }

    private suspend fun container(context: Context, name: String, layout: LocalInstallation, graphicsDriver: String?): Container {
        val manager = ContainerManager(context)
        val drives = "D:${layout.client.path}S:${layout.server.path}R:${layout.root.path}M:${layout.maps.path}"
        val size = GameSettings.gameSize(context).let { "${it.first}x${it.second}" }
        val existing = manager.containers.firstOrNull { it.name == name }
        if (existing != null) {
            existing.setScreenSize(size)
            existing.setDrives(drives)
            if (graphicsDriver != null) existing.setGraphicsDriver(graphicsDriver)
            withContext(Dispatchers.IO) { existing.saveData() }
            return existing
        }
        val defaults = Container(0)
        val data = JSONObject()
            .put("name", name).put("screenSize", size).put("drives", drives)
            .put("envVars", defaults.getEnvVars()).put("graphicsDriver", graphicsDriver ?: defaults.getGraphicsDriver())
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
