// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import android.content.Intent
import android.util.Log
import com.logie.warcraft.MainActivity
import com.logie.warcraft.R
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.game.GestureView
import com.logie.warcraft.turtle.core.ClientManifest
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.turtle.core.TurtleAddons
import com.logie.warcraft.turtle.core.TurtleClientSetup
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.RootFS
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
 * Turtle WoW's game: the user's 1.18.1 client (`files/turtle/client/WoW.exe`) on the app's Windows
 * runtime, in a Windows of its own: the Winlator container "Turtle WoW" (its own Wine prefix, with
 * Turtle's client as drive D:), apart from the "World of Warcraft" one the 3.3.5a client uses. The
 * runtime's program files are shared and never changed here. [play]: Turtle's realm starts, the
 * runtime is installed if it isn't, the container is made the first time, the client is pointed at
 * the realm, and once the realm is up the game starts at its login screen.
 */
object TurtleGame {
    private const val TAG = "TurtleGame"

    sealed interface State {
        data object Idle : State

        data class Preparing(val step: Step, val progress: Float? = null) : State

        data class Failed(val message: String, val needsAccount: Boolean = false, val chromieCraft: Boolean = false) : State
    }

    enum class Step { RUNTIME, WINDOWS, REALM, STARTING }

    /** The Windows the game runs in (named when the game was Turtle WoW: kept, so it stays the same one). */
    const val CONTAINER_NAME = "Turtle WoW"

    /** The 3.3.5a game's container (game/Game.kt): never Turtle's to touch. */
    private const val OTHER_CONTAINER = "World of Warcraft"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class GameFailed(message: String, val needsAccount: Boolean = false, val chromieCraft: Boolean = false) : Exception(message)

    /** The game screen, waiting for the launcher to be on screen. */
    private var pending: Intent? = null

    fun onLauncherShown(context: Context) {
        val intent = pending ?: return
        pending = null
        context.startActivity(intent)
    }

    fun dismiss() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    /** The game's Windows runtime is installed and current (it's shared by both games). */
    fun runtimeReady(context: Context): Boolean = runCatching {
        val rootFs = RootFS.find(context)
        rootFs.isValid && rootFs.version >= RootFSInstaller.LATEST_VERSION
    }.getOrDefault(false)

    /** Some version of the runtime is installed. */
    fun runtimeThere(context: Context): Boolean = runCatching { RootFS.find(context).isValid }.getOrDefault(false)

    /** The client's folder by the path the game sees it at (drive D:). */
    fun clientPath(context: Context): String = TurtleFiles.serverRoot(context) + "/client"

    fun play(context: Context) {
        if (_state.value is State.Preparing) return
        if (com.logie.warcraft.local.WindowsRealmRegistry.anyBusy()) {
            _state.value = State.Failed(context.getString(R.string.tww_stop_other))
            return
        }
        val app = context.applicationContext
        val resolution = GameSettings.gameSize(context)
        _state.value = State.Preparing(Step.RUNTIME)
        scope.launch {
            try {
                run(app, resolution)
                _state.value = State.Idle
            } catch (e: GameFailed) {
                _state.value = State.Failed(e.message ?: "", e.needsAccount, e.chromieCraft)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(app.getString(R.string.turtle_game_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun run(context: Context, resolution: Pair<Int, Int>) {
        if (!TurtleFiles.hasClient(context)) throw GameFailed(context.getString(R.string.turtle_error_no_client))
        if (!TurtleFiles.hasData(context)) throw GameFailed(context.getString(R.string.turtle_error_no_data))
        TurtleServer.start(context)

        _state.value = State.Preparing(Step.RUNTIME, 0f)
        val installed = withContext(Dispatchers.IO) {
            RootFSInstaller.installIfNeeded(context) { percent -> _state.value = State.Preparing(Step.RUNTIME, percent / 100f) }
        }
        if (!installed) throw GameFailed(context.getString(R.string.turtle_game_error_runtime))

        _state.value = State.Preparing(Step.WINDOWS)
        val (width, height) = resolution
        val container = container(context, width, height)
        withContext(Dispatchers.IO) {
            val layout = TurtleFiles.layout(context)
            // The bots' control addon (/tbm) from the realm package, in the client's AddOns.
            runCatching { TurtleAddons.install(File(layout.server, "addons"), layout.client, TurtleFiles.fs(context)) }
                .onFailure { Log.w(TAG, "Couldn't put the realm's addons in the client", it) }
            TurtleClientSetup.apply(layout.client, width, height, accountName = null)
        }

        _state.value = State.Preparing(Step.REALM)
        val server = TurtleServer.status.first {
            it.phase == TurtleServer.Phase.FAILED || it.phase == TurtleServer.Phase.STOPPED ||
                (it.phase == TurtleServer.Phase.RUNNING && (it.worldUp || it.noData))
        }
        when {
            server.phase == TurtleServer.Phase.FAILED ->
                throw GameFailed(server.error ?: context.getString(R.string.turtle_realm_failed), chromieCraft = server.blockedByChromieCraft)
            server.phase == TurtleServer.Phase.STOPPED -> throw GameFailed(context.getString(R.string.turtle_game_error_stopped))
            server.noData -> throw GameFailed(context.getString(R.string.turtle_error_no_data))
        }
        val accounts = withContext(Dispatchers.IO) { runCatching { TurtleAccounts.list(context) }.getOrNull() }
        if (accounts?.isEmpty() == true) throw GameFailed(context.getString(R.string.turtle_game_error_no_account), needsAccount = true)
        val saved = TurtleLogin.get(context)
        val account = accounts?.firstOrNull { saved != null && it.name.equals(saved.name, ignoreCase = true) } ?: accounts?.singleOrNull()
        if (account != null) withContext(Dispatchers.IO) { TurtleClientSetup.apply(TurtleFiles.layout(context).client, width, height, account.name) }
        val newAccount = account != null && withContext(Dispatchers.IO) { runCatching { TurtleAccounts.characters(context, account.id) == 0 }.getOrDefault(false) }

        _state.value = State.Preparing(Step.STARTING)
        TitleMusic.stopForGame()
        val exe = ClientManifest.find(TurtleFiles.layout(context).client, ClientManifest.EXE)?.name ?: ClientManifest.EXE
        val intent = Intent(context, XServerDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("container_id", container.id)
            .putExtra("exec_path", clientPath(context) + "/" + exe)
            .putExtra("gestures", true)
            // The game screen's touch layer, for Turtle: its own saved login and accounts, and no
            // ChromieCraft realm's files (selections, cutscenes, the spellbook's place).
            .putExtra(GestureView.EXTRA_ENVIRONMENT, GestureView.ENVIRONMENT_TURTLE)
            .putExtra(GestureView.EXTRA_NEW_ACCOUNT, newAccount)
            .putExtra(GestureView.EXTRA_ACCOUNT, account?.name)
        if (MainActivity.visible) context.startActivity(intent) else pending = intent
    }

    /** Turtle's Windows: made the first time, its screen and drive D: set every time. */
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
        val created = suspendCancellableCoroutine { done -> manager.createContainerAsync(data) { container -> done.resume(container) } }
        return created ?: throw GameFailed(context.getString(R.string.turtle_game_error_windows))
    }

    /**
     * Uninstall: Turtle's Windows (its container's folder in the runtime's home), and nothing
     * else: the deletion is limited to that one folder (no link followed: its drive links point at
     * Turtle's client and at `/`), with the other containers protected. Background thread.
     */
    fun removeWindows(context: Context) {
        val home = File(RootFS.find(context).rootDir, "home")
        val all = runCatching { ContainerManager(context).containers }.getOrDefault(arrayListOf())
        val ours = all.filter { it.name == CONTAINER_NAME }
        val others = all.filter { it.name != CONTAINER_NAME }.map { File(home, "${RootFS.USER}-${it.id}") }
        val guard = SafeFs(home, others)
        for (container in ours) {
            val name = "${RootFS.USER}-${container.id}"
            val folder = File(home, name)
            if (all.any { it.name == OTHER_CONTAINER && it.id == container.id }) continue
            // The runtime's "current container" link, if it's Turtle's: the link alone goes (the
            // 3.3.5a game sets it to its own as it starts).
            val current = File(home, RootFS.USER).toPath()
            if (java.nio.file.Files.isSymbolicLink(current) && java.nio.file.Files.readSymbolicLink(current).toString().trimEnd('/').endsWith(name)) {
                guard.delete(current.toFile())
            }
            if (folder.exists()) guard.delete(folder)
        }
    }
}
