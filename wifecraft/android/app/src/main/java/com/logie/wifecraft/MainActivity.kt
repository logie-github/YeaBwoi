package com.logie.wifecraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.wifecraft.audio.Audio
import com.logie.wifecraft.data.Cast
import com.logie.wifecraft.data.Content
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.data.Saves
import com.logie.wifecraft.data.Settings
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.play.Director
import com.logie.wifecraft.play.Sounds
import com.logie.wifecraft.ui.LocalSounds
import com.logie.wifecraft.ui.CreatorScreen
import com.logie.wifecraft.ui.EndingsPanel
import com.logie.wifecraft.ui.GatherScreen
import com.logie.wifecraft.ui.LocalArt
import com.logie.wifecraft.ui.LogPanel
import com.logie.wifecraft.ui.OptionsPanel
import com.logie.wifecraft.ui.Overlay
import com.logie.wifecraft.ui.PauseMenu
import com.logie.wifecraft.ui.SavesPanel
import com.logie.wifecraft.ui.StoryScreen
import com.logie.wifecraft.ui.TitleScreen
import com.logie.wifecraft.ui.UpdatePanel
import com.logie.wifecraft.ui.WowArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wifecraft: the title, the first launch's downloads, who the player is, and the story with its
 * menus, in one activity. The 3D view is made once and kept.
 */
class MainActivity : ComponentActivity() {
    private lateinit var files: GameFiles
    private lateinit var content: Content
    private lateinit var settings: Settings
    private lateinit var saves: Saves
    private var scene: SceneView? = null
    private var audio: Audio? = null

    enum class Screen { Title, Gather, Creator, Story }
    enum class Panel { None, Pause, Save, Load, Log, Options, Endings, Update }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        files = GameFiles(applicationContext)
        content = Content(applicationContext)
        settings = Settings(applicationContext)
        saves = Saves(applicationContext)
        audio = runCatching { Audio(applicationContext, files) }.getOrNull()?.apply {
            musicVolume = settings.musicVolume / 100f
            soundVolume = settings.soundVolume / 100f
        }
        setContent { App() }
    }

    override fun onPause() {
        super.onPause()
        scene?.onPause()
        audio?.pause()
    }

    override fun onResume() {
        super.onResume()
        scene?.onResume()
        audio?.resume()
    }

    override fun onDestroy() {
        scene?.dispose()
        audio?.release()
        super.onDestroy()
    }

    private fun sceneView(): SceneView = scene ?: SceneView(this, files.dir.path).also { scene = it }

    @Composable
    private fun App() {
        var screen by remember { mutableStateOf(if (files.allComplete) Screen.Title else Screen.Title) }
        var panel by remember { mutableStateOf(Panel.None) }
        var director by remember { mutableStateOf<Director?>(null) }
        var art by remember { mutableStateOf<WowArt?>(null) }
        var frame by remember { mutableIntStateOf(0) }
        var saveList by remember { mutableStateOf(saves.all()) }
        var update by remember { mutableStateOf<Updater.Update?>(null) }
        var updateStatus by remember { mutableStateOf("") }
        var parchment by remember { mutableStateOf(settings.boxStyle == "parchment") }
        var textSpeed by remember { mutableIntStateOf(settings.textSpeed) }
        var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
        var musicVolume by remember { mutableIntStateOf(settings.musicVolume) }
        var soundVolume by remember { mutableIntStateOf(settings.soundVolume) }
        val progress by files.progress.collectAsState()

        // WoW's interface art, once its files are here (and again when downloads finish).
        LaunchedEffect(progress.complete.contains("interface")) {
            if (files.packComplete("interface")) art = withContext(Dispatchers.IO) { WowArt.load(files) }
        }
        // The music and sounds, once they're here; Dalaran's dawn under the title and menus.
        LaunchedEffect(progress.complete.contains("sound")) {
            if (files.packComplete("sound")) audio?.filesArrived()
        }
        LaunchedEffect(screen) {
            if (screen != Screen.Story) {
                audio?.music("dawn", 2000)
                audio?.ambience("")
            }
        }
        LaunchedEffect(Unit) {
            if (Updater.isConfigured) update = withContext(Dispatchers.IO) { runCatching { Updater.findUpdate() }.getOrNull() }
        }
        // The story runs every frame while it's on screen and no menu is open.
        LaunchedEffect(director, screen, panel) {
            val d = director ?: return@LaunchedEffect
            if (screen != Screen.Story || panel != Panel.None) return@LaunchedEffect
            while (true) withFrameMillis {
                d.tick()
                frame++
            }
        }

        fun startStory(build: (Director) -> Unit) {
            val d = Director(content, settings, saves, sceneView(), audio ?: Sounds.None) {
                director = null
                screen = Screen.Title
                saveList = saves.all()
            }
            build(d)
            director = d
            panel = Panel.None
            screen = Screen.Story
        }

        BackHandler(enabled = screen != Screen.Title || panel != Panel.None) {
            when {
                panel != Panel.None && panel != Panel.Pause && screen == Screen.Story -> panel = Panel.Pause
                panel != Panel.None -> panel = Panel.None
                screen == Screen.Story -> panel = Panel.Pause
                else -> screen = Screen.Title
            }
        }

        val story = files.packs.filter { it.name != "sound" }
        val storyReady = story.all { p -> p.files.all { files.has(it.first) } }
        val soundMissing = files.packs.filter { it.name == "sound" }
            .sumOf { p -> p.files.filter { !files.has(it.first) }.sumOf { it.second } }
        CompositionLocalProvider(LocalArt provides art, LocalSounds provides { name: String -> audio?.ui(name) }) {
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.Title -> TitleScreen(
                        filesReady = storyReady,
                        downloadSize = "%.0f MB".format(files.totalBytes / 1e6),
                        soundMissing = if (storyReady && soundMissing > 0) "%.0f MB".format(soundMissing / 1e6) else null,
                        hasSave = saveList.any { it != null },
                        updateTitle = update?.title,
                        onContinue = { saves.latest()?.let { s -> startStory { it.resume(s) } } },
                        onNew = { screen = Screen.Creator },
                        onGather = { screen = Screen.Gather },
                        onLoad = { saveList = saves.all(); panel = Panel.Load },
                        onEndings = { panel = Panel.Endings },
                        onOptions = { panel = Panel.Options },
                        onUpdates = { updateStatus = ""; panel = Panel.Update },
                    )
                    Screen.Gather -> GatherScreen(
                        progress = progress,
                        packLabels = listOf("interface" to "WoW's interface", "people" to "Alleria, Nyrelle and you", "city" to "Dalaran", "props" to "The city's lamps, banners and stalls", "sound" to "Music and sounds"),
                        wifiOnly = wifiOnly,
                        onWifiOnly = { wifiOnly = it; settings.wifiOnly = it },
                        onStart = { files.start(wifiOnly) },
                        onPause = { files.pause() },
                        onBack = { screen = Screen.Title },
                    )
                    Screen.Creator -> CreatorScreen(
                        settings.playerName, settings.playerLook,
                        onDone = { name, look ->
                            settings.playerName = name
                            settings.playerLook = look
                            startStory { it.begin() }
                        },
                        onBack = { screen = Screen.Title },
                    )
                    Screen.Story -> director?.let { d ->
                        StoryScreen(sceneView(), d, parchment, frame, onMenu = { audio?.ui("open"); panel = Panel.Pause }, onLog = { panel = Panel.Log })
                    }
                }

                val d = director
                when (panel) {
                    Panel.None -> Unit
                    Panel.Pause -> if (d != null) PauseMenu(
                        chapter = d.runner.state.chapter,
                        bonds = Cast.bonds.map { (key, name, who) -> Triple(name, who, d.runner.affection(key)) },
                        onResume = { audio?.ui("close"); panel = Panel.None },
                        onSave = { saveList = saves.all(); panel = Panel.Save },
                        onLoad = { saveList = saves.all(); panel = Panel.Load },
                        onLog = { panel = Panel.Log },
                        onOptions = { panel = Panel.Options },
                        onTitle = { d.save(0); director = null; screen = Screen.Title; panel = Panel.None; saveList = saves.all() },
                    )
                    Panel.Save -> Overlay({ panel = Panel.Pause }) {
                        SavesPanel(true, saveList, onPick = { slot -> d?.save(slot); saveList = saves.all(); panel = Panel.Pause }, onDelete = {}, onBack = { panel = Panel.Pause })
                    }
                    Panel.Load -> Overlay({ panel = if (d != null) Panel.Pause else Panel.None }) {
                        SavesPanel(
                            false, saveList,
                            onPick = { slot -> saves.load(slot)?.let { s -> startStory { it.resume(s) } } },
                            onDelete = { slot -> saves.delete(slot); saveList = saves.all() },
                            onBack = { panel = if (d != null) Panel.Pause else Panel.None },
                        )
                    }
                    Panel.Log -> Overlay({ panel = Panel.None }) {
                        LogPanel(d?.runner?.state?.log ?: emptyList(), { who ->
                            when (who) {
                                "narrator" -> ""
                                else -> Cast.person(who, settings.playerLook, settings.playerName.ifBlank { "Traveller" })?.name ?: who
                            }
                        }, onBack = { panel = Panel.None })
                    }
                    Panel.Options -> Overlay({ panel = if (d != null) Panel.Pause else Panel.None }) {
                        OptionsPanel(
                            textSpeed, { textSpeed = it; settings.textSpeed = if (it >= 120) 0 else it },
                            parchment, { parchment = it; settings.boxStyle = if (it) "parchment" else "dark" },
                            wifiOnly, { wifiOnly = it; settings.wifiOnly = it },
                            musicVolume, { musicVolume = it; settings.musicVolume = it; audio?.musicVolume = it / 100f },
                            soundVolume, { soundVolume = it; settings.soundVolume = it; audio?.soundVolume = it / 100f },
                            onBack = { panel = if (d != null) Panel.Pause else Panel.None },
                        )
                    }
                    Panel.Endings -> Overlay({ panel = Panel.None }) { EndingsPanel(settings.endings, onBack = { panel = Panel.None }) }
                    Panel.Update -> Overlay({ panel = Panel.None }) {
                        val u = update
                        LaunchedEffect(Unit) {
                            if (!Updater.isConfigured) { updateStatus = "This build doesn't check for updates."; return@LaunchedEffect }
                            if (u == null) {
                                updateStatus = "Checking…"
                                update = withContext(Dispatchers.IO) { runCatching { Updater.findUpdate() }.getOrNull() }
                                updateStatus = update?.let { "${it.title} is out." } ?: "You have the newest version (${BuildConfig.VERSION_NAME})."
                            } else updateStatus = "${u.title} is out."
                        }
                        UpdatePanel(updateStatus, canInstall = update != null, onInstall = { installUpdate(update!!) { updateStatus = it } }, onBack = { panel = Panel.None })
                    }
                }
            }
        }
    }

    private fun installUpdate(u: Updater.Update, status: (String) -> Unit) {
        if (!Updater.canInstall(this)) {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(android.net.Uri.parse("package:$packageName")),
            )
            status("Allow Wifecraft to install apps, then tap Install again.")
            return
        }
        status("Downloading ${u.title}…")
        Thread {
            try {
                val apk = Updater.download(this, u, { done, total ->
                    if (total > 0) runOnUiThread { status("Downloading ${u.title}… ${done * 100 / total}%") }
                }, { false })
                runOnUiThread {
                    status("Opening the installer…")
                    startActivity(Updater.installIntent(this, apk))
                }
            } catch (e: Exception) {
                runOnUiThread { status("The download failed: ${e.message}") }
            }
        }.start()
    }
}
