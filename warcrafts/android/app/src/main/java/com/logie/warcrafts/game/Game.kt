// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.content.Context
import android.content.Intent
import com.logie.warcrafts.MainActivity
import com.logie.warcrafts.R
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.Wc3Setup
import com.logie.warcrafts.library.ClassicPlus
import com.logie.warcrafts.library.Engine
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Needs
import com.logie.warcrafts.library.Offline
import com.logie.warcrafts.library.Title
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.DXWrappers
import com.winlator.container.ContainerManager
import com.winlator.core.EnvVars
import com.winlator.core.KeyValueSet
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
    /** The games' Windows as this version makes it; an older one is made again once ([remakeReason]). */
    private const val GENERATION_EXTRA = "rtsGeneration"
    private const val GENERATION = "30"
    /** A start in progress: written as Warcraft III starts, deleted when its window shows. */
    const val STARTING = "wc3-starting"
    /** [STARTING]'s text when that start had the games' Windows made fresh (not made again for it). */
    private const val FRESH = "fresh"

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
        // The game's screen is still open (Winlator's screen is single-instance: a new start would
        // only bring it back, with nothing started): back to it, its game and report untouched.
        if (GestureView.screenUp) {
            val back = Intent(context, XServerDisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (MainActivity.visible) context.startActivity(back) else pending = back
            return
        }
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
        val (container, madeFresh) = container(context, width, height)
        if (install == Install.WC3) withContext(Dispatchers.IO) { configureWarcraft3(context, container, title, width, height) }

        _state.value = State.Preparing(Step.STARTING)
        val leftovers = withContext(Dispatchers.IO) { stopLeftovers(context) }
        // Cleared when the game's window shows ([GestureView.onGameWindowShown]): still here at the
        // next start, this one never showed a window.
        withContext(Dispatchers.IO) { File(context.filesDir, STARTING).writeText(if (madeFresh != null) FRESH else "") }
        Wc3Report.start(context, listOf(
            "Games' Windows" to (madeFresh ?: "as before"),
            "Left over from before, stopped" to leftovers.ifEmpty { listOf("nothing") }.joinToString(), 
            "App" to (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"),
            "Game" to "${title.key}: $execPath",
            "Screen" to "${width}x$height (${GameSettings.resolution(context).name})",
            "Draws with" to "${GameSettings.renderer(context).name}, Direct3D by ${container.dxWrapper}",
            "Controls" to if (GameSettings.touchpad(context)) "Winlator's touchpad" else "the app's gestures",
            "Wine's pointer grab" to "off (X11 Driver GrabPointer N): the cursor is kept in the game's window without it",
            "Graphics driver" to container.graphicsDriver,
            "Windows parts" to container.winComponents,
            "Private" to RtsFiles.isPrivate(context).toString(),
        ))
        val intent = Intent(context, XServerDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("container_id", container.id)
            .putExtra("exec_path", execPath)
            // The app's touch controls (GestureView) in place of Winlator's touchpad; with Winlator's
            // touchpad chosen (Graphics, under Controls), the GestureView stands aside, every touch
            // going on to the touchpad, and keeps only the report and the start's bookkeeping.
            .putExtra("gestures", true)
            .putExtra(GestureView.EXTRA_TOUCHPAD, GameSettings.touchpad(context))
            .putExtra(GestureView.EXTRA_TITLE, title.key)
        if (MainActivity.visible) context.startActivity(intent) else pending = intent
    }

    /**
     * Wine's and Box64's processes still running from an earlier game (the game swiped away, the
     * app stopped while it ran): stopped before a new start, which they could hold up (Wine's
     * server, a game waiting on a film). The app's own processes aren't touched. What was stopped.
     */
    private fun stopLeftovers(context: Context): List<String> {
        if (GestureView.screenUp) return emptyList()
        val own = android.os.Process.myPid()
        val stopped = mutableListOf<String>()
        val runtime = RootFS.find(context).rootDir.path
        for (pid in File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }) {
            if (pid == own) continue
            val command = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ').trim() }.getOrNull() ?: continue
            if (command.isEmpty() || command.startsWith(context.packageName)) continue
            if (command.contains(runtime) || command.contains("wineserver") || command.contains("box64") ||
                command.endsWith(".exe") || command.contains(".exe ")
            ) {
                runCatching { android.os.Process.killProcess(pid) }
                stopped += command.take(80)
            }
        }
        return stopped
    }

    /** The runtime's name lookups: none but this phone's while Private is on, offline ([Offline]). */
    private fun keepOffline(context: Context) {
        val etc = File(RootFS.find(context).rootDir, "etc")
        etc.mkdirs()
        File(etc, "hosts").writeText(Offline.hosts())
        File(etc, "nsswitch.conf").writeText(Offline.nsswitch(RtsFiles.isPrivate(context)))
    }

    /**
     * Windows' parts in the games' Windows: Winlator's defaults, with Wine's own DirectShow (build 27
     * put Windows' in for the films, and that build showed only a black screen; Winlator puts Wine's
     * back when it's turned off).
     */
    private fun winComponents(current: String): String = KeyValueSet(current).put("directshow", "0").toString()

    /** What draws Warcraft III's Direct3D: Wine's own (wined3d, with OpenGL) or DXVK (Vulkan); unused with `-opengl`. */
    private fun dxWrapper(context: Context): String =
        if (GameSettings.renderer(context) == GameSettings.Renderer.WINED3D) DXWrappers.WINED3D else DXWrappers.DXVK

    /**
     * Why the games' Windows has to be made again before this start, or null to keep it: once for
     * [GENERATION] (builds 27 and 28 changed its parts and settings, and build 28 never started),
     * and after a start that never showed the game's window, unless that start already had a fresh one.
     */
    private fun remakeReason(context: Context, existing: Container): String? {
        if (existing.getExtra(GENERATION_EXTRA) != GENERATION) return "made fresh (once, for this version)"
        val starting = File(context.filesDir, STARTING)
        if (starting.isFile && runCatching { starting.readText() }.getOrDefault("") != FRESH) {
            return "made fresh (the last start never showed the game)"
        }
        return null
    }

    /**
     * The games' Windows: made the first time (and again when [remakeReason] says so: it holds no
     * game files, saves or keys, which are all in the game's folder on F:, and the game's registry
     * values are written at every start), its screen and drives set every time. With why it was
     * made fresh for this start, or null if it wasn't.
     */
    private suspend fun container(context: Context, width: Int, height: Int): Pair<Container, String?> {
        val manager = ContainerManager(context)
        // Warcraft III on F:.
        val drives = Install.entries.filter { it.engine == Engine.WINE }.joinToString("") { "${drive(it)}:" + RtsFiles.folder(context, it).path }
        val screenSize = "${width}x$height"
        var existing = manager.containers.firstOrNull { it.name == CONTAINER_NAME }
        var fresh: String? = null
        if (existing != null) {
            val reason = remakeReason(context, existing)
            if (reason != null) {
                val old = existing
                suspendCancellableCoroutine { done -> manager.removeContainerAsync(old) { done.resume(Unit) } }
                // Not all of it could be removed: kept as it is (its drive links are never followed).
                existing = manager.containers.firstOrNull { it.name == CONTAINER_NAME }
                fresh = if (existing == null) reason else null
            }
        }
        if (existing != null) {
            existing.setScreenSize(screenSize)
            existing.setDrives(drives)
            existing.winComponents = winComponents(existing.winComponents)
            existing.dxWrapper = dxWrapper(context)
            existing.envVars = EnvVars(existing.envVars).put("WINEDEBUG", Wc3Report.WINE_DEBUG).toString()
            withContext(Dispatchers.IO) { existing.saveData() }
            return existing to null
        }
        val defaults = Container(0)
        val data = JSONObject()
            .put("name", CONTAINER_NAME)
            .put("screenSize", screenSize)
            .put("envVars", EnvVars(defaults.getEnvVars()).put("WINEDEBUG", Wc3Report.WINE_DEBUG).toString())
            .put("graphicsDriver", defaults.getGraphicsDriver())
            .put("dxwrapper", dxWrapper(context))
            .put("audioDriver", defaults.getAudioDriver())
            .put("wincomponents", winComponents(defaults.getWinComponents()))
            .put("drives", drives)
            .put("hudMode", defaults.getHUDMode().toInt())
            .put("startupSelection", defaults.getStartupSelection().toInt())
            .put("box64Preset", defaults.getBox64Preset())
            .put("desktopTheme", defaults.getDesktopTheme())
        val created = suspendCancellableCoroutine { done ->
            manager.createContainerAsync(data) { container -> done.resume(container) }
        }
        created ?: throw GameFailed(context.getString(R.string.rts_game_error_windows))
        created.putExtra(GENERATION_EXTRA, GENERATION)
        withContext(Dispatchers.IO) { created.saveData() }
        return created to (fresh ?: "made fresh (the first time)")
    }

    /**
     * Warcraft III's settings in the games' Windows registry: its resolution, the screen's 4:3 size
     * (1.27 fills the screen at it), and Windows' own dialogs in Wine's Light style (it comes with
     * the runtime but is off: without it they're drawn like Windows 95).
     */
    private fun configureWarcraft3(context: Context, container: Container, title: Title, width: Int, height: Int) {
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
            // Wine keeps the cursor in a window (Warcraft III's ClipCursor, DirectInput's exclusive
            // mouse, which clips one pixel inside the screen even for a fullscreen window) by
            // grabbing the X pointer on a window of its own. Winlator's X server gave a program
            // holding the pointer no button presses (patches/winlator/0005 fixes that), the likely
            // reason the game's pointer moved and nothing was pressed. Without the grab (Wine's own
            // setting) presses go to the game's window as any click does; the clip itself stays,
            // kept by Wine's server, and on the phone there's nowhere else for the pointer to go.
            registry.setStringValue("Software\\Wine\\X11 Driver", "GrabPointer", "N")
            // Classic+ ([ClassicPlus]): its files loose in the game's folder, read in place of the game's own.
            registry.setDwordValue(blizzard, "Allow Local Files", if (ClassicPlus.enabled(RtsFiles.folder(context, Install.WC3))) 1 else 0)
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
