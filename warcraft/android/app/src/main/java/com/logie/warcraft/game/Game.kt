// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import com.logie.warcraft.Accounts
import com.logie.warcraft.MainActivity
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.WowFiles
import com.logie.warcraft.client.ClientConfig
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.RootFSInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * The game: the user's 3.3.5a client (`files/client/Wow.exe`) on Winlator, which runs Windows
 * programs on the phone (Wine, Box64 and its own X server; scripts/prepare-winlator.sh builds it
 * into the app). [play] gets everything ready and starts it, logging in to the realm on the phone:
 *
 * 1. the server starts, if it isn't running ([Server]);
 * 2. meanwhile Winlator's runtime is installed, the first time and after an update that changes it
 *    (`/data/data/com.logie.warcraft/rootfs`, from the APK);
 * 3. the game's Windows (a Winlator container) is made, the first time, with the client as drive
 *    D:, and its screen set to this phone's shape;
 * 4. the client is pointed at the realm (`realmlist.wtf`, `Config.wtf`);
 * 5. once the realm is up, the music stops and Winlator's game screen starts Wow.exe, with the
 *    app's touch controls ([GestureView]).
 */
object Game {
    sealed interface State {
        data object Idle : State

        /** Getting ready: installing the runtime ([progress] 0-1), then waiting for the realm. */
        data class Preparing(val step: Step, val progress: Float? = null) : State

        /** [needsAccount]: there's no game account to log in with yet (Accounts makes one). */
        data class Failed(val message: String, val needsAccount: Boolean = false) : State
    }

    enum class Step { RUNTIME, WINDOWS, REALM, STARTING }

    private const val CONTAINER_NAME = "World of Warcraft"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The client's folder by the path the game sees it at (drive D:). */
    fun clientPath(context: Context): String = WowFiles.serverRoot(context) + "/client"

    /** Gets the game ready and starts it. */
    fun play(context: Context) {
        if (_state.value is State.Preparing) return
        val app = context.applicationContext
        val resolution = gameResolution(context)
        _state.value = State.Preparing(Step.RUNTIME)
        scope.launch {
            try {
                run(app, resolution)
                _state.value = State.Idle
            } catch (e: GameFailed) {
                _state.value = State.Failed(e.message ?: "", e.needsAccount)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(app.getString(R.string.wow_game_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Clears a failure, back to the launcher's start. */
    fun dismiss() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    private class GameFailed(message: String, val needsAccount: Boolean = false) : Exception(message)

    /** The game screen, waiting for the launcher to be on screen (Android starts no screens from the background). */
    private var pending: Intent? = null

    /** The launcher came on screen: starts the game if it was ready while the app was in the background. */
    fun onLauncherShown(context: Context) {
        val intent = pending ?: return
        pending = null
        context.startActivity(intent)
    }

    private suspend fun run(context: Context, resolution: Pair<Int, Int>) {
        if (!WowFiles.hasClient(context)) throw GameFailed(context.getString(R.string.wow_game_error_no_client))
        // The realm takes minutes to load: it starts first, and the rest is done while it loads.
        Server.start(context)

        _state.value = State.Preparing(Step.RUNTIME, 0f)
        val installed = withContext(Dispatchers.IO) {
            RootFSInstaller.installIfNeeded(context) { percent ->
                _state.value = State.Preparing(Step.RUNTIME, percent / 100f)
            }
        }
        if (!installed) throw GameFailed(context.getString(R.string.wow_game_error_runtime))

        _state.value = State.Preparing(Step.WINDOWS)
        val (width, height) = resolution
        val container = container(context, width, height)
        withContext(Dispatchers.IO) { configureClient(context, width, height, accountName = null) }

        _state.value = State.Preparing(Step.REALM)
        val server = Server.status.first {
            it.phase == Server.Phase.FAILED || it.phase == Server.Phase.STOPPED ||
                (it.phase == Server.Phase.RUNNING && it.world != Server.World.STARTING && it.world != Server.World.OFF)
        }
        when {
            server.phase == Server.Phase.FAILED -> throw GameFailed(server.error ?: context.getString(R.string.wow_server_failed))
            server.phase == Server.Phase.STOPPED -> throw GameFailed(context.getString(R.string.wow_game_error_server_stopped))
            server.world == Server.World.NO_DATA -> throw GameFailed(context.getString(R.string.wow_game_error_no_data))
        }
        // The login box filled in with the account, when there's only one that's the user's.
        val accounts = withContext(Dispatchers.IO) { runCatching { Accounts.list(context) }.getOrNull() }
        if (accounts?.isEmpty() == true) throw GameFailed(context.getString(R.string.wow_game_error_no_account), needsAccount = true)
        accounts?.singleOrNull()?.let { account ->
            withContext(Dispatchers.IO) { configureClient(context, width, height, account.name) }
        }

        _state.value = State.Preparing(Step.STARTING)
        TitleMusic.stopForGame()
        val intent = Intent(context, XServerDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("container_id", container.id)
            .putExtra("exec_path", clientPath(context) + "/Wow.exe")
            // The app's touch controls (GestureView) in place of Winlator's touchpad.
            .putExtra("gestures", true)
        if (MainActivity.visible) context.startActivity(intent) else pending = intent
    }

    /** The game's resolution for the screen the launcher is on now (a Fold's inside or outside). */
    private fun gameResolution(context: Context): Pair<Int, Int> {
        return if (context is Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.windowManager.maximumWindowMetrics.bounds
            ClientConfig.resolution(bounds.width(), bounds.height())
        } else {
            val metrics = context.resources.displayMetrics
            ClientConfig.resolution(metrics.widthPixels, metrics.heightPixels)
        }
    }

    /** The game's Windows: made the first time, its screen and drive D: set every time. */
    private suspend fun container(context: Context, width: Int, height: Int): Container {
        val manager = ContainerManager(context)
        val drives = "D:" + clientPath(context)
        val screenSize = "${width}x$height"
        val existing = manager.containers.firstOrNull { it.name == CONTAINER_NAME }
        if (existing != null) {
            existing.setScreenSize(screenSize)
            existing.setDrives(drives)
            withContext(Dispatchers.IO) { existing.saveData() }
            return existing
        }
        val defaults = Container(0)
        val data = JSONObject()
            .put("name", CONTAINER_NAME)
            .put("screenSize", screenSize)
            .put("envVars", defaults.getEnvVars())
            .put("graphicsDriver", defaults.getGraphicsDriver())
            .put("dxwrapper", defaults.getDXWrapper())
            .put("audioDriver", defaults.getAudioDriver())
            .put("wincomponents", defaults.getWinComponents())
            .put("drives", drives)
            .put("hudMode", defaults.getHUDMode().toInt())
            .put("startupSelection", defaults.getStartupSelection().toInt())
            .put("box64Preset", defaults.getBox64Preset())
            .put("desktopTheme", defaults.getDesktopTheme())
        // Unpacks Windows' files into it on a thread of its own, then answers here.
        val created = suspendCancellableCoroutine { done ->
            manager.createContainerAsync(data) { container -> done.resume(container) }
        }
        return created ?: throw GameFailed(context.getString(R.string.wow_game_error_windows))
    }

    /** Points the client at the realm on this phone, at the game's resolution, intro movie off. */
    private fun configureClient(context: Context, width: Int, height: Int, accountName: String?) {
        val client = WowFiles.client(context)
        val realmlist = ClientConfig.realmlist("127.0.0.1")
        File(client, "Data").listFiles { file -> file.isDirectory && ClientConfig.LOCALE.matches(file.name) }
            ?.forEach { File(it, "realmlist.wtf").writeText(realmlist) }
        File(client, "realmlist.wtf").takeIf { it.isFile }?.writeText(realmlist)

        val config = File(client, "WTF/Config.wtf")
        config.parentFile?.mkdirs()
        val values = buildMap {
            put("realmList", "127.0.0.1")
            put("realmName", Server.REALM_NAME)
            put("accounttype", "LK")
            put("readTOS", "1")
            put("readEULA", "1")
            put("movie", "0")
            put("gxResolution", "${width}x$height")
            put("gxWindow", "1")
            put("gxMaximize", "1")
            if (accountName != null) put("accountName", accountName)
        }
        val text = if (config.isFile) config.readText() else ""
        config.writeText(ClientConfig.updateConfig(text, values))
    }
}
