// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import android.content.Intent
import android.util.Log
import com.logie.warcraft.MainActivity
import com.logie.warcraft.R
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.felsong.core.FelsongClient
import com.logie.warcraft.felsong.core.FelsongClientSetup
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.game.GestureView
import com.logie.warcraft.turtle.core.SafeFs
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
 * Felsong's game: the user's 7.3.5 client (`files/felsong/client/Wow-64_Patched.exe`, the program
 * the connection patcher made for a local realm) on the app's Windows runtime, in a Windows of its
 * own: the Winlator container "Felsong" (its own Wine prefix, with the client as drive D:), apart
 * from the other games' containers. [play]: Felsong's realm starts, the runtime is installed if it
 * isn't, the container is made the first time, the client is pointed at the realm (its portal and
 * the Battle.net certificate bundle the patched program trusts), and once the realm is up the game
 * starts. Whether the Legion client runs on the phone's Box64 at all is the open question
 * (FELSONG.md): this starts it and says what happened.
 */
object FelsongGame {
    private const val TAG = "FelsongGame"

    sealed interface State {
        data object Idle : State

        data class Preparing(val step: Step, val progress: Float? = null) : State

        data class Failed(val message: String, val needsAccount: Boolean = false) : State
    }

    enum class Step { RUNTIME, WINDOWS, REALM, STARTING }

    const val CONTAINER_NAME = "Felsong"

    /** The other games' containers (game/Game.kt, turtle/TurtleGame.kt): never Felsong's to touch. */
    private val OTHER_CONTAINERS = setOf("World of Warcraft", "Turtle WoW")

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class GameFailed(message: String, val needsAccount: Boolean = false) : Exception(message)

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

    /** The game's Windows runtime is installed and current (it's shared by the games). */
    fun runtimeReady(context: Context): Boolean = runCatching {
        val rootFs = RootFS.find(context)
        rootFs.isValid && rootFs.version >= RootFSInstaller.LATEST_VERSION
    }.getOrDefault(false)

    /** Some version of the runtime is installed. */
    fun runtimeThere(context: Context): Boolean = runCatching { RootFS.find(context).isValid }.getOrDefault(false)

    /** The client's folder by the path the game sees it at (drive D:). */
    fun clientPath(context: Context): String = FelsongFiles.serverRoot(context) + "/client"

    fun play(context: Context) {
        if (_state.value is State.Preparing) return
        val app = context.applicationContext
        val resolution = GameSettings.gameSize(context)
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
                _state.value = State.Failed(app.getString(R.string.felsong_game_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun run(context: Context, resolution: Pair<Int, Int>) {
        if (!FelsongFiles.hasClient(context)) throw GameFailed(context.getString(R.string.felsong_error_no_client))
        if (!FelsongFiles.hasData(context)) throw GameFailed(context.getString(R.string.felsong_error_no_data))
        if (!FelsongFiles.isPatched(context)) throw GameFailed(context.getString(R.string.felsong_error_not_patched))
        FelsongServer.start(context)

        _state.value = State.Preparing(Step.RUNTIME, 0f)
        val installed = withContext(Dispatchers.IO) {
            RootFSInstaller.installIfNeeded(context) { percent -> _state.value = State.Preparing(Step.RUNTIME, percent / 100f) }
        }
        if (!installed) throw GameFailed(context.getString(R.string.felsong_game_error_runtime))

        _state.value = State.Preparing(Step.WINDOWS)
        val (width, height) = resolution
        val container = container(context, width, height)
        withContext(Dispatchers.IO) {
            val layout = FelsongFiles.layout(context)
            FelsongClientSetup.apply(layout.client, width, height, accountName = null)
            installCertificateBundle(context, container)
        }

        _state.value = State.Preparing(Step.REALM)
        val server = FelsongServer.status.first {
            it.phase == FelsongServer.Phase.FAILED || it.phase == FelsongServer.Phase.STOPPED ||
                (it.phase == FelsongServer.Phase.RUNNING && (it.worldUp || it.noData))
        }
        when {
            server.phase == FelsongServer.Phase.FAILED -> throw GameFailed(server.error ?: context.getString(R.string.felsong_realm_failed))
            server.phase == FelsongServer.Phase.STOPPED -> throw GameFailed(context.getString(R.string.felsong_game_error_stopped))
            server.noData -> throw GameFailed(context.getString(R.string.felsong_error_no_data))
        }
        val accounts = withContext(Dispatchers.IO) { runCatching { FelsongAccounts.list(context) }.getOrNull() }
        if (accounts?.isEmpty() == true) throw GameFailed(context.getString(R.string.felsong_game_error_no_account), needsAccount = true)
        val saved = FelsongLogin.get(context)
        val account = accounts?.firstOrNull { saved != null && it.name.equals(saved.name, ignoreCase = true) } ?: accounts?.singleOrNull()
        if (account != null) withContext(Dispatchers.IO) { FelsongClientSetup.apply(FelsongFiles.layout(context).client, width, height, account.name) }
        val newAccount = account != null && withContext(Dispatchers.IO) { runCatching { FelsongAccounts.characters(context, account.id) == 0 }.getOrDefault(false) }

        _state.value = State.Preparing(Step.STARTING)
        TitleMusic.stopForGame()
        val exe = FelsongFiles.patchedExe(context)?.name ?: FelsongClient.PATCHED_EXE
        val intent = Intent(context, XServerDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("container_id", container.id)
            .putExtra("exec_path", clientPath(context) + "/" + exe)
            .putExtra("gestures", true)
            // The game screen's touch layer, for Felsong: its own saved login and accounts.
            .putExtra(GestureView.EXTRA_ENVIRONMENT, GestureView.ENVIRONMENT_FELSONG)
            .putExtra(GestureView.EXTRA_NEW_ACCOUNT, newAccount)
            .putExtra(GestureView.EXTRA_ACCOUNT, account?.name)
        if (MainActivity.visible) context.startActivity(intent) else pending = intent
    }

    /**
     * The Battle.net certificate bundle the patched client checks its login server against, as the
     * connection patcher wrote it (files/felsong/.wine/...), into the game's Windows: both of the
     * places a Windows program may look for ProgramData in a Wine prefix. The prefix is Felsong's
     * own container's; the files go through a guard scoped to that prefix.
     */
    private fun installCertificateBundle(context: Context, container: Container) {
        val bundle = FelsongFiles.layout(context).certBundle
        if (!bundle.isFile) throw GameFailed(context.getString(R.string.felsong_error_not_patched))
        val driveC = File(container.rootDir, ".wine/drive_c")
        val guard = SafeFs(driveC)
        for (path in FelsongClientSetup.CERT_BUNDLE_IN_PREFIX) {
            val target = guard.check(File(driveC, path))
            target.parentFile?.let { guard.mkdirs(it) }
            bundle.copyTo(target, overwrite = true)
        }
    }

    /** Felsong's Windows: made the first time, its screen and drive D: set every time. */
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
        return created ?: throw GameFailed(context.getString(R.string.felsong_game_error_windows))
    }

    /**
     * Uninstall: Felsong's Windows (its container's folder in the runtime's home), and nothing
     * else: the deletion is limited to that one folder (no link followed: its drive links point at
     * Felsong's client and at `/`), with the other containers protected. Background thread.
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
            if (all.any { it.name in OTHER_CONTAINERS && it.id == container.id }) continue
            val current = File(home, RootFS.USER).toPath()
            if (java.nio.file.Files.isSymbolicLink(current) && java.nio.file.Files.readSymbolicLink(current).toString().trimEnd('/').endsWith(name)) {
                guard.delete(current.toFile())
            }
            if (folder.exists()) guard.delete(folder)
        }
    }
}
