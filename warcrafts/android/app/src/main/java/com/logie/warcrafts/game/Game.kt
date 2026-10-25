// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.content.Context
import android.content.Intent
import com.logie.warcrafts.MainActivity
import com.logie.warcrafts.R
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.Wc3Setup
import com.logie.warcrafts.library.Engine
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Needs
import com.logie.warcrafts.library.Offline
import com.logie.warcrafts.library.Title
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * A game. Warcraft I and II are native: [StratagusActivity] plays the data made from the user's GOG
 * files. Warcraft III is Blizzard's 1.27, installed in the app ([Wc3Setup]) into
 * `files/games/wc3/`, on Winlator, which runs Windows programs on the phone (Wine, Box64 and its own
 * X server; scripts/prepare-winlator-app.sh builds it into the app). [play] gets everything ready
 * and starts it:
 *
 * 1. Winlator's runtime is installed, the first time and after an update that changes it
 *    (`/data/data/com.logie.warcrafts/winfs`, from the APK);
 * 2. the runtime is offline while Private is on ([Offline]);
 * 3. the games' Windows (one Winlator container) is made, the first time, Warcraft III on F: and
 *    Blizzard's installers on G:, its screen set to the game's 4:3 size;
 * 4. Warcraft III is told that size, and Windows' dialogs get Wine's Light style;
 * 5. Winlator's game screen starts Warcraft III.exe or Frozen Throne.exe, with the app's touch
 *    controls ([GestureView]).
 *
 * Nothing goes to a server: the campaigns, custom games against the computer and the games' own
 * local network modes are all on the phone.
 */
object Game {
    sealed interface State {
        data object Idle : State
        data class Preparing(val step: Step, val progress: Float? = null) : State
        data class Failed(val message: String) : State
    }

    enum class Step { RUNTIME, WINDOWS, STARTING }

    private const val CONTAINER_NAME = "Warcraft"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The drive each game is on in the games' Windows. */
    fun drive(install: Install): Char = when (install) {
        Install.WC1 -> 'D'
        Install.WC2 -> 'E'
        Install.WC3 -> 'F'
    }

    fun play(context: Context, title: Title) {
        if (_state.value is State.Preparing) return
        val app = context.applicationContext
        val resolution = GameSettings.gameSize(context)
        _state.value = State.Preparing(Step.RUNTIME)
        scope.launch {
            try {
                run(app, title, resolution)
                _state.value = State.Idle
            } catch (e: GameFailed) {
                _state.value = State.Failed(e.message ?: "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(app.getString(R.string.rts_game_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun dismiss() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    private class GameFailed(message: String) : Exception(message)

    /** The game screen, waiting for the launcher to be on screen (Android starts no screens from the background). */
    private var pending: Intent? = null

    fun onLauncherShown(context: Context) {
        val intent = pending ?: return
        pending = null
        context.startActivity(intent)
    }

    private suspend fun run(context: Context, title: Title, resolution: Pair<Int, Int>) {
        val install = title.install
        // Warcraft I and II: native, Stratagus playing the data made from the GOG files.
        if (install.engine == Engine.STRATAGUS) {
            if (!RtsFiles.isConverted(context, install)) {
                throw GameFailed(context.getString(R.string.rts_game_error_no_game, context.getString(com.logie.warcrafts.ImportService.installName(install))))
            }
            _state.value = State.Preparing(Step.STARTING)
            val intent = StratagusActivity.intent(context, title, RtsFiles.stratagusDir(context, install).path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (MainActivity.visible) context.startActivity(intent) else pending = intent
            return
        }
        // Reign of Chaos starts Warcraft III.exe, The Frozen Throne its own Frozen Throne.exe.
        val folder = RtsFiles.folder(context, install)
        if (!withContext(Dispatchers.IO) { Needs.ready(RtsFiles.needs(context, install)) }) {
            throw GameFailed(context.getString(R.string.rts_game_error_no_game, context.getString(com.logie.warcrafts.ImportService.installName(install))))
        }
        val program = if (title == Title.TFT) "Frozen Throne.exe" else "Warcraft III.exe"
        val arguments = GameSettings.arguments(context, install)
        val execPath = File(folder, program).path + if (arguments.isNotEmpty()) " $arguments" else ""
        startWindows(context, title, resolution, execPath)
    }

    private suspend fun startWindows(context: Context, title: Title, resolution: Pair<Int, Int>, execPath: String) {
        val install = title.install
        _state.value = State.Preparing(Step.RUNTIME, 0f)
        val installed = withContext(Dispatchers.IO) {
            RootFSInstaller.installIfNeeded(context) { percent ->
                _state.value = State.Preparing(Step.RUNTIME, percent / 100f)
            }
        }
        if (!installed) throw GameFailed(context.getString(R.string.rts_game_error_runtime))
        withContext(Dispatchers.IO) { keepOffline(context) }

        _state.value = State.Preparing(Step.WINDOWS)
        val (width, height) = resolution
        val container = container(context, width, height)
        if (install == Install.WC3) withContext(Dispatchers.IO) { configureWarcraft3(container, title, width, height) }

        _state.value = State.Preparing(Step.STARTING)
        val intent = Intent(context, XServerDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("container_id", container.id)
            .putExtra("exec_path", execPath)
            // The app's touch controls (GestureView) in place of Winlator's touchpad.
            .putExtra("gestures", true)
            .putExtra(GestureView.EXTRA_TITLE, title.key)
        if (MainActivity.visible) context.startActivity(intent) else pending = intent
    }

    /** The runtime's name lookups: none but this phone's while Private is on, offline ([Offline]). */
    private fun keepOffline(context: Context) {
        val etc = File(RootFS.find(context).rootDir, "etc")
        etc.mkdirs()
        File(etc, "hosts").writeText(Offline.hosts())
        File(etc, "nsswitch.conf").writeText(Offline.nsswitch(RtsFiles.isPrivate(context)))
    }

    /** The games' Windows: made the first time, its screen and drives set every time. */
    private suspend fun container(context: Context, width: Int, height: Int): Container {
        val manager = ContainerManager(context)
        // Warcraft III on F:.
        val drives = Install.entries.filter { it.engine == Engine.WINE }.joinToString("") { "${drive(it)}:" + RtsFiles.folder(context, it).path }
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
        val created = suspendCancellableCoroutine { done ->
            manager.createContainerAsync(data) { container -> done.resume(container) }
        }
        return created ?: throw GameFailed(context.getString(R.string.rts_game_error_windows))
    }

    /**
     * Warcraft III's settings in the games' Windows registry: its resolution, the screen's 4:3 size
     * (1.27 fills the screen at it), and Windows' own dialogs in Wine's Light style (it comes with
     * the runtime but is off: without it they're drawn like Windows 95).
     */
    private fun configureWarcraft3(container: Container, title: Title, width: Int, height: Int) {
        val userReg = File(container.rootDir, ".wine/user.reg")
        if (!userReg.isFile) return
        // Where the game is, as Blizzard's installer writes it (F:, the app's Warcraft III folder).
        val gameDir = "${drive(Install.WC3)}:\\"
        val program = gameDir + if (title == Title.TFT) "Frozen Throne.exe" else "Warcraft III.exe"
        val systemReg = File(container.rootDir, ".wine/system.reg")
        if (systemReg.isFile) WineRegistryEditor(systemReg).use { registry ->
            for (key in listOf("Software\\Blizzard Entertainment\\Warcraft III", "Software\\Wow6432Node\\Blizzard Entertainment\\Warcraft III")) {
                registry.setStringValue(key, "InstallPath", gameDir)
                registry.setStringValue(key, "GamePath", program)
            }
        }
        WineRegistryEditor(userReg).use { registry ->
            val blizzard = "Software\\Blizzard Entertainment\\Warcraft III"
            registry.setStringValue(blizzard, "InstallPath", gameDir)
            registry.setStringValue(blizzard, "Program", program)
            registry.setStringValue(blizzard, "GamePath", program)
            val video = "Software\\Blizzard Entertainment\\Warcraft III\\Video"
            registry.setDwordValue(video, "reswidth", width)
            registry.setDwordValue(video, "resheight", height)
            val theme = "Software\\Microsoft\\Windows\\CurrentVersion\\ThemeManager"
            registry.setStringValue(theme, "ThemeActive", "1")
            registry.setStringValue(theme, "DllName", "C:\\windows\\resources\\themes\\light\\light.msstyles")
            registry.setStringValue(theme, "ColorName", "NormalColor")
            registry.setStringValue(theme, "SizeName", "NormalSize")
        }
    }
}
