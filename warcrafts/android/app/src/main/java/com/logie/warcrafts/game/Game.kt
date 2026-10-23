// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.content.Context
import android.content.Intent
import com.logie.warcrafts.MainActivity
import com.logie.warcrafts.R
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.library.Engine
import com.logie.warcrafts.library.GameLayout
import com.logie.warcrafts.library.Install
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
 * files. Warcraft III is the user's own files (`files/games/wc3/`) on Winlator, which runs Windows programs on
 * the phone (Wine, Box64 and its own X server; scripts/prepare-winlator-app.sh builds it into the
 * app). [play] gets everything ready and starts it:
 *
 * 1. Winlator's runtime is installed, the first time and after an update that changes it
 *    (`/data/data/com.logie.warcrafts/winfs`, from the APK);
 * 2. Blizzard's servers are put out of the games' reach, unless Private is off ([Offline]);
 * 3. the games' Windows (one Winlator container) is made, the first time, with Warcraft I, II and
 *    III as drives D:, E: and F:, and its screen set to this phone's shape;
 * 4. Warcraft III is told the screen's size and language;
 * 5. Winlator's game screen starts the game's program, with the app's touch controls ([GestureView]).
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
        // The Frozen Throne starts from its own program (1.27: Frozen Throne.exe), Reign of Chaos from
        // Warcraft III.exe.
        val program = withContext(Dispatchers.IO) {
            val main = RtsFiles.program(context, install)
            if (title == Title.TFT) GameLayout.expansionProgram(RtsFiles.programs(context, install)) ?: main else main
        }
            ?: throw GameFailed(context.getString(R.string.rts_game_error_no_game, context.getString(com.logie.warcrafts.ImportService.installName(install))))

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
        if (install == Install.WC3) withContext(Dispatchers.IO) { configureWarcraft3(container, width, height) }

        _state.value = State.Preparing(Step.STARTING)
        val folder = RtsFiles.folder(context, install)
        val arguments = GameSettings.arguments(context, install)
        val execPath = File(folder, program).path + if (arguments.isNotEmpty()) " $arguments" else ""
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
     * Warcraft III's settings in the games' Windows registry: the screen's size, the whole screen
     * (`windowmode` 1, windowed fullscreen; 0 is windowed, 2 native fullscreen; the start's
     * arguments say it too, [com.logie.warcrafts.library.Wc3Arguments]), and English unless a
     * language was chosen (the repack's "Classic Launcher" key).
     */
    private fun configureWarcraft3(container: Container, width: Int, height: Int) {
        val userReg = File(container.rootDir, ".wine/user.reg")
        if (!userReg.isFile) return
        WineRegistryEditor(userReg).use { registry ->
            val video = "Software\\Blizzard Entertainment\\Warcraft III\\Video"
            registry.setDwordValue(video, "reswidth", width)
            registry.setDwordValue(video, "resheight", height)
            registry.setDwordValue(video, "windowmode", 1)
            // Windows' own dialogs (the game's messages) in Wine's Light style, which comes with the
            // runtime but is off: without it they're drawn like Windows 95.
            val theme = "Software\\Microsoft\\Windows\\CurrentVersion\\ThemeManager"
            registry.setStringValue(theme, "ThemeActive", "1")
            registry.setStringValue(theme, "DllName", "C:\\windows\\resources\\themes\\light\\light.msstyles")
            registry.setStringValue(theme, "ColorName", "NormalColor")
            registry.setStringValue(theme, "SizeName", "NormalSize")
            val launcher = "Software\\Blizzard Entertainment\\Classic Launcher\\w3"
            if (registry.getStringValue(launcher, "Locale", "").isEmpty()) registry.setStringValue(launcher, "Locale", "enUS")
        }
    }
}
