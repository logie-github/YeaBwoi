package com.logie.wifecraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.anim.Profile
import com.logie.wifecraft.anim.Retargeter
import com.logie.wifecraft.audio.Audio
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.ImportStore
import com.logie.wifecraft.creator.ListfileNames
import com.logie.wifecraft.creator.Prefetch
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.data.Settings
import com.logie.wifecraft.editor.Compile
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.editor.ProjectStore
import com.logie.wifecraft.engine.Dresser
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.play.Director
import com.logie.wifecraft.play.PosePlayer
import com.logie.wifecraft.play.Sounds
import com.logie.wifecraft.story.Script
import com.logie.wifecraft.ui.AnimImportScreen
import com.logie.wifecraft.ui.AnimLibraryScreen
import com.logie.wifecraft.ui.ArtAtlas
import com.logie.wifecraft.ui.RigStore
import com.logie.wifecraft.ui.EditorScreen
import com.logie.wifecraft.ui.EditorStart
import com.logie.wifecraft.ui.GatherScreen
import com.logie.wifecraft.ui.ImportsSection
import com.logie.wifecraft.ui.LoadingBar
import com.logie.wifecraft.ui.LocalArt
import com.logie.wifecraft.ui.LocalSounds
import com.logie.wifecraft.ui.LogPanel
import com.logie.wifecraft.ui.OptionsPanel
import com.logie.wifecraft.ui.Overlay
import com.logie.wifecraft.ui.PauseMenu
import com.logie.wifecraft.ui.PlaceInfo
import com.logie.wifecraft.ui.StoreApi
import com.logie.wifecraft.ui.StoryScreen
import com.logie.wifecraft.ui.TitleScreen
import com.logie.wifecraft.ui.UpdatePanel
import com.logie.wifecraft.ui.WowArt
import com.logie.wifecraft.ui.frizStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wifecraft: the title, the first launch's downloads, the story editor (projects, their cast made in the character
 * creator, their scenes) and the player its Play runs, in one activity. The 3D view is made once and kept.
 */
class MainActivity : ComponentActivity() {
    private lateinit var files: GameFiles
    private lateinit var settings: Settings
    private lateinit var tables: CsvTables
    private lateinit var imports: ImportStore
    private lateinit var listfile: ListfileNames
    private lateinit var projects: StoreApi
    private lateinit var places: List<PlaceInfo>
    private lateinit var dresser: Dresser
    /** Imported animations (files/anims) and the Sims 4 skeleton they are converted with (files/anims/rig). */
    private lateinit var anims: AnimLibrary
    private lateinit var rigs: RigStore
    private val profileJson: String by lazy { assets.open("sims_profile.json").bufferedReader().use { it.readText() } }
    private var scene: SceneView? = null
    private var audio: Audio? = null

    sealed interface Screen {
        data object Title : Screen
        data object Gather : Screen
        data class Editor(val start: EditorStart) : Screen
        data class Story(val projectId: String) : Screen
        data object ImportAnims : Screen
        data object Animations : Screen
    }
    enum class Panel { None, Pause, Log, Options, Update }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        files = GameFiles(applicationContext)
        settings = Settings(applicationContext)
        tables = CsvTables(files.tablesDir)
        // the first launch fetches every file the creator can draw: the plan from the tables, once they are here
        files.planner = { Prefetch.plan(Catalog.build(tables), tables) }
        listfile = ListfileNames(File(filesDir, "cache/listfile-character.csv"))
        imports = ImportStore(filesDir, listfile)
        projects = StoreApi(ProjectStore(File(filesDir, "projects")))
        places = PlaceInfo.parseAll(assets.open("places.json").bufferedReader().use { it.readText() })
        dresser = Dresser(files, tables, imports, lifecycleScope)
        anims = AnimLibrary(File(filesDir, "anims"))
        rigs = RigStore(File(filesDir, "anims"))
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
        var screen by remember { mutableStateOf<Screen>(Screen.Title) }
        var panel by remember { mutableStateOf(Panel.None) }
        var director by remember { mutableStateOf<Director?>(null) }
        var art by remember { mutableStateOf<WowArt?>(null) }
        var catalog by remember { mutableStateOf<Catalog?>(null) }
        var frame by remember { mutableIntStateOf(0) }
        var last by remember { mutableStateOf(projects.list().firstOrNull()) }
        var update by remember { mutableStateOf<Updater.Update?>(null) }
        var updateStatus by remember { mutableStateOf("") }
        var parchment by remember { mutableStateOf(settings.boxStyle == "parchment") }
        var textSpeed by remember { mutableIntStateOf(settings.textSpeed) }
        var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
        var musicVolume by remember { mutableIntStateOf(settings.musicVolume) }
        var soundVolume by remember { mutableIntStateOf(settings.soundVolume) }
        val progress by files.progress.collectAsState()
        val importsSlot: @Composable () -> Unit = { ImportsSection(imports, listfile) }
        val fetching by dresser.fetching.collectAsState()

        // WoW's interface art, once its files are here (and again when downloads finish).
        LaunchedEffect(progress.complete.contains("interface")) {
            if (files.packComplete("interface")) art = withContext(Dispatchers.IO) { WowArt.load(files) }
        }
        // The creator's races, options and choices, from the game's tables once they are all here.
        LaunchedEffect(progress.complete.contains(GameFiles.TABLES)) {
            if (catalog == null && files.tablesComplete) catalog = withContext(Dispatchers.Default) { runCatching { Catalog.build(tables) }.getOrNull() }
        }
        // The music and sounds, once they're here; Dalaran's dawn under the title and the editor.
        LaunchedEffect(progress.complete.contains("sound")) {
            if (files.packComplete("sound")) audio?.filesArrived()
        }
        LaunchedEffect(screen) {
            if (screen !is Screen.Story) {
                audio?.music("dawn", 2000)
                audio?.ambience("")
            }
            if (screen == Screen.Title) last = withContext(Dispatchers.IO) { projects.list().firstOrNull() }
        }
        LaunchedEffect(Unit) {
            if (Updater.isConfigured) update = withContext(Dispatchers.IO) { runCatching { Updater.findUpdate() }.getOrNull() }
        }
        // The story runs every frame while it's on screen and no menu is open.
        LaunchedEffect(director, screen, panel) {
            val d = director ?: return@LaunchedEffect
            if (screen !is Screen.Story || panel != Panel.None) return@LaunchedEffect
            while (true) withFrameMillis {
                d.tick()
                frame++
            }
        }

        fun backToEditor(projectId: String) {
            director = null
            panel = Panel.None
            val p = projects.list().firstOrNull { it.id == projectId }
            screen = Screen.Editor(if (p != null) EditorStart.Open(p) else EditorStart.Projects)
        }

        /** The editor's Play: the compiled script, its people dressed in their saved looks. Throws when it can't be read. */
        fun play(project: Project, text: String) {
            val script = Script.parse(text)
            val cast = Compile.cast(project)
            val view = sceneView()
            val d = Director(
                script, places.associateBy { it.id }, cast.mapValues { it.value.name }, settings, view, audio ?: Sounds.None,
                dress = { key -> val a = cast[key]; val c = catalog; if (a != null && c != null) dresser.dress(view, c, key, a.appearance) },
                onFinished = { backToEditor(project.id) },
                poses = object : PosePlayer {
                    override fun play(who: String, animId: String): Boolean {
                        val a = runCatching { anims.get(animId) }.getOrNull() ?: return false
                        view.playAnim(who, a)
                        return true
                    }
                    override fun stop(who: String) = view.stopAnim(who)
                },
            )
            d.begin()
            director = d
            panel = Panel.None
            screen = Screen.Story(project.id)
        }

        BackHandler(enabled = screen != Screen.Title || panel != Panel.None) {
            when {
                panel != Panel.None && panel != Panel.Pause && screen is Screen.Story -> panel = Panel.Pause
                panel != Panel.None -> panel = Panel.None
                screen is Screen.Story -> panel = Panel.Pause
                else -> screen = Screen.Title
            }
        }

        val needed = files.packs.filter { it.name != "sound" }
        val ready = needed.all { p -> p.files.all { files.has(it.first) } } && progress.complete.contains(GameFiles.TABLES)
        val soundMissing = files.packs.filter { it.name == "sound" }
            .sumOf { p -> p.files.filter { !files.has(it.first) }.sumOf { it.second } }
        CompositionLocalProvider(LocalArt provides art, LocalSounds provides { name: String -> audio?.ui(name) }) {
            Box(Modifier.fillMaxSize()) {
                when (val s = screen) {
                    Screen.Title -> TitleScreen(
                        filesReady = ready,
                        downloadSize = files.totalBytes.let { b -> if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6) },
                        soundMissing = if (ready && soundMissing > 0) "%.0f MB".format(soundMissing / 1e6) else null,
                        lastProject = if (ready) last?.title else null,
                        updateTitle = update?.title,
                        onContinue = { last?.let { screen = Screen.Editor(EditorStart.Open(it)) } },
                        onNewProject = { screen = Screen.Editor(EditorStart.New) },
                        onProjects = { screen = Screen.Editor(EditorStart.Projects) },
                        onGather = { screen = Screen.Gather },
                        onOptions = { panel = Panel.Options },
                        onUpdates = { updateStatus = ""; panel = Panel.Update },
                        onImportAnims = { screen = Screen.ImportAnims },
                        onAnimations = { screen = Screen.Animations },
                    )
                    Screen.ImportAnims -> AnimImportScreen(
                        library = anims, rigs = rigs,
                        retargeter = { rig -> Retargeter(Profile.load(profileJson), rig) },
                        onBack = { screen = Screen.Title },
                    )
                    Screen.Animations -> AnimLibraryScreen(anims, onImport = { screen = Screen.ImportAnims }, onBack = { screen = Screen.Title })
                    Screen.Gather -> GatherScreen(
                        progress = progress,
                        packLabels = listOf(
                            "interface" to "WoW's interface", GameFiles.TABLES to "The character creator's tables",
                            "city" to "Dalaran, the stages' backdrop", "props" to "Its lamps, banners and stalls",
                            GameFiles.LOOKS to "Every race's looks", "sound" to "Music and sounds",
                        ),
                        wifiOnly = wifiOnly,
                        onWifiOnly = { wifiOnly = it; settings.wifiOnly = it },
                        onStart = {
                            files.start(wifiOnly)
                            // the creator's own art (Shadowlands' sheets and check boxes), cut when the creator opens
                            lifecycleScope.launch { runCatching { ArtAtlas.open(applicationContext).load() } }
                        },
                        onPause = { files.pause() },
                        onBack = { screen = Screen.Title },
                    )
                    is Screen.Editor -> {
                        val a = art
                        val c = catalog
                        if (!ready || a == null || c == null) {
                            NotReady(if (!ready) "The game's files aren't all here yet: Gather the Files on the title." else "Reading the game's tables…") { screen = Screen.Title }
                        } else EditorScreen(
                            api = projects, catalog = c, tables = tables, art = a, scene = sceneView(), places = places,
                            dress = { key, appearance -> dresser.dress(sceneView(), c, key, appearance) },
                            onCreatorLook = {},
                            onPlay = { },
                            onBack = { screen = Screen.Title },
                            start = s.start,
                            onPlayProject = { project, text -> play(project, text) },
                            animLibrary = anims,
                        )
                    }
                    is Screen.Story -> director?.let { d ->
                        StoryScreen(sceneView(), d, parchment, frame, onMenu = { audio?.ui("open"); panel = Panel.Pause }, onLog = { panel = Panel.Log })
                    }
                }

                // a look's files on their way (the creator, the scene builder, Play)
                if (fetching.total > 0 || fetching.error != null) {
                    Column(
                        Modifier.align(Alignment.TopCenter).padding(top = 6.dp).background(Color(0xB0000000))
                            .clickable { dresser.clearError() }.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (fetching.total > 0) {
                            Text("Fetching the look's files… ${fetching.done} of ${fetching.total}", style = frizStyle(13.sp))
                            LoadingBar(fetching.done.toFloat() / fetching.total, Modifier.width(320.dp).height(30.dp))
                        }
                        fetching.error?.let { Text(it, style = frizStyle(12.sp, Color(0xFFFF6A5A))) }
                    }
                }

                val d = director
                when (panel) {
                    Panel.None -> Unit
                    Panel.Pause -> if (d != null) PauseMenu(
                        chapter = d.runner.state.chapter,
                        onResume = { audio?.ui("close"); panel = Panel.None },
                        onLog = { panel = Panel.Log },
                        onOptions = { panel = Panel.Options },
                        onLeave = { (screen as? Screen.Story)?.let { backToEditor(it.projectId) } },
                    )
                    Panel.Log -> Overlay({ panel = Panel.None }) {
                        LogPanel(d?.runner?.state?.log ?: emptyList(), { who -> d?.nameOf(who) ?: who }, onBack = { panel = Panel.None })
                    }
                    Panel.Options -> Overlay({ panel = if (d != null) Panel.Pause else Panel.None }) {
                        OptionsPanel(
                            textSpeed, { textSpeed = it; settings.textSpeed = if (it >= 120) 0 else it },
                            parchment, { parchment = it; settings.boxStyle = if (it) "parchment" else "dark" },
                            wifiOnly, { wifiOnly = it; settings.wifiOnly = it },
                            musicVolume, { musicVolume = it; settings.musicVolume = it; audio?.musicVolume = it / 100f },
                            soundVolume, { soundVolume = it; settings.soundVolume = it; audio?.soundVolume = it / 100f },
                            onBack = { panel = if (d != null) Panel.Pause else Panel.None },
                            // the creator's imports (Ralia's mod, Alternate Female Options, the body options), from the title
                            more = if (d == null) importsSlot else null,
                        )
                    }
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

    /** What the editor waits for, plainly drawn, with the way back. */
    @Composable
    private fun NotReady(why: String, onBack: () -> Unit) {
        BackHandler(onBack = onBack)
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Text(why, style = frizStyle(16.sp, Color.White))
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
