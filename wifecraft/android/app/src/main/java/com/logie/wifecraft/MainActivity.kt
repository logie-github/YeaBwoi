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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.logie.wifecraft.audio.SceneAudio
import com.logie.wifecraft.audio.SoundVolumes
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.catalog.EmoteCatalog
import com.logie.wifecraft.catalog.EmoteKind
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.engine.Clock
import com.logie.wifecraft.engine.LocationLoader
import com.logie.wifecraft.engine.Native
import com.logie.wifecraft.engine.PropLoader
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.play.PlaceHost
import com.logie.wifecraft.ui.PlaceEngine
import com.logie.wifecraft.ui.ThumbSource
import com.logie.wifecraft.ui.LibraryModel
import com.logie.wifecraft.editor.LibraryPrefs
import com.logie.wifecraft.engine.Thumbs
import android.graphics.Bitmap
import com.logie.wifecraft.ui.PlaceKeys
import com.logie.wifecraft.ui.PlaceStatus
import com.logie.wifecraft.ui.StageChoices
import com.logie.wifecraft.ui.WowColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject
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
import com.logie.wifecraft.ui.LocalWar
import com.logie.wifecraft.ui.WarArt
import com.logie.wifecraft.ui.WarArtOverlay
import com.logie.wifecraft.ui.WarArtRow
import com.logie.wifecraft.ui.LogPanel
import com.logie.wifecraft.ui.OptionsWindow
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
    /** The placement editor's: a location's files and loading, a placed prop's files, the scene's sounds (W31's SceneAudio). */
    private lateinit var locationLoader: LocationLoader
    private lateinit var propLoader: PropLoader
    private var sceneAudio: SceneAudio? = null
    /** Every emote of each race and sex, with its voices (assets/emotes.json). */
    private val emoteCatalog: EmoteCatalog? by lazy {
        runCatching { EmoteCatalog.parse(assets.open("emotes.json").bufferedReader().use { it.readText() }) }.getOrNull()
    }
    private val placer: ScenePlacer by lazy { ScenePlacer() }
    /** The creator's catalogue (races and models), for dressing placed NPCs; set once the tables are read. */
    @Volatile private var creatorCatalog: Catalog? = null

    sealed interface Screen {
        data object Title : Screen
        data object Gather : Screen
        data class Editor(val start: EditorStart) : Screen
        data class Story(val projectId: String) : Screen
        data object ImportAnims : Screen
        /** The animation library; [back]: where its Back goes (the title, or the editor's scene it was opened from). */
        data class Animations(val back: Screen = Title) : Screen
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
        locationLoader = LocationLoader(applicationContext, files, lifecycleScope)
        propLoader = PropLoader(files, lifecycleScope)
        sceneAudio = runCatching { SceneAudio(applicationContext, files, tables) }.getOrNull()
        applySceneVolumes()
        setContent { App() }
    }

    override fun onPause() {
        super.onPause()
        scene?.onPause()
        audio?.pause()
        sceneAudio?.pause()
    }

    override fun onResume() {
        super.onResume()
        scene?.onResume()
        audio?.resume()
        sceneAudio?.resume()
    }

    override fun onDestroy() {
        scene?.dispose()
        audio?.release()
        sceneAudio?.release()
        super.onDestroy()
    }

    /** Options' volumes on the scene's sounds: music, ambience, effects (Sounds) and voices, each its own. */
    private fun applySceneVolumes() {
        val s = settings.soundVolume / 100f
        sceneAudio?.volumes = SoundVolumes(music = settings.musicVolume / 100f, ambience = settings.ambienceVolume / 100f, effects = s, voices = settings.voiceVolume / 100f)
    }

    private fun sceneView(): SceneView = scene ?: SceneView(this, files.dir.path).also { scene = it; it.softBody(Settings(this).softBody) }

    @Composable
    private fun App() {
        var screen by remember { mutableStateOf<Screen>(Screen.Title) }
        var panel by remember { mutableStateOf(Panel.None) }
        var director by remember { mutableStateOf<Director?>(null) }
        var art by remember { mutableStateOf<WowArt?>(null) }
        // the editor's build-mode art (WarArt: the game's current interface pieces and icons, fetched once, cut and warmed)
        val war = remember { WarArt.open(applicationContext) }
        var catalog by remember { mutableStateOf<Catalog?>(null) }
        var frame by remember { mutableIntStateOf(0) }
        var last by remember { mutableStateOf(projects.list().firstOrNull()) }
        var update by remember { mutableStateOf<Updater.Update?>(null) }
        var updateStatus by remember { mutableStateOf("") }
        var parchment by remember { mutableStateOf(settings.boxStyle == "parchment") }
        var textSpeed by remember { mutableIntStateOf(settings.textSpeed) }
        var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
        var downloadSpeed by remember { mutableStateOf(settings.downloadSpeed) }
        var musicVolume by remember { mutableIntStateOf(settings.musicVolume) }
        var soundVolume by remember { mutableIntStateOf(settings.soundVolume) }
        var softBody by remember { mutableIntStateOf(settings.softBody) }
        var voiceVolume by remember { mutableIntStateOf(settings.voiceVolume) }
        var ambienceVolume by remember { mutableIntStateOf(settings.ambienceVolume) }
        val progress by files.progress.collectAsState()
        val importsSlot: @Composable () -> Unit = { ImportsSection(imports, listfile) }
        val fetching by dresser.fetching.collectAsState()

        // WoW's interface art, once its files are here (and again when downloads finish).
        LaunchedEffect(progress.complete.contains("interface")) {
            if (files.packComplete("interface")) art = withContext(Dispatchers.IO) { WowArt.load(files) }
        }
        // The build-mode art: fetched (four at a time) and cut once the game's interface files are here or the editor opens;
        // what is already cached is cut at once. Its own loader retries a failed file by itself (WarArt.start is idempotent).
        LaunchedEffect(progress.complete.contains("interface"), screen is Screen.Editor) {
            if (files.packComplete("interface") || screen is Screen.Editor || !war.firstOpen) war.start()
        }
        // The creator's races, options and choices, from the game's tables once they are all here.
        LaunchedEffect(progress.complete.contains(GameFiles.TABLES)) {
            if (catalog == null && files.tablesComplete) catalog = withContext(Dispatchers.Default) { runCatching { Catalog.build(tables) }.getOrNull() }
            creatorCatalog = catalog
        }
        // The placement editor's library (catalog.tsv, and the game's named creatures once the tables are here).
        LaunchedEffect(progress.complete.contains(GameFiles.TABLES)) { placer.loadLibrary(files.tablesComplete) }
        // The music and sounds, once they're here; Dalaran's dawn under the title and the editor.
        LaunchedEffect(progress.complete.contains("sound")) {
            if (files.packComplete("sound")) audio?.filesArrived()
        }
        LaunchedEffect(screen) {
            if (screen !is Screen.Story && screen !is Screen.Editor) placer.quiet()
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
                // the placement editor's things: spawned, animated and voiced by the same engine as the editor's preview
                placeHost = placer.playHost(cast),
                locations = locationLoader.locations,
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
                else -> screen = (screen as? Screen.Animations)?.back ?: Screen.Title
            }
        }

        val needed = files.packs.filter { it.name != "sound" }
        val ready = needed.all { p -> p.files.all { files.has(it.first) } } && progress.complete.contains(GameFiles.TABLES)
        val soundMissing = files.packs.filter { it.name == "sound" }
            .sumOf { p -> p.files.filter { !files.has(it.first) }.sumOf { it.second } }
        CompositionLocalProvider(LocalArt provides art, LocalWar provides war, LocalSounds provides { name: String -> audio?.ui(name) }) {
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
                        onAnimations = { screen = Screen.Animations() },
                    )
                    Screen.ImportAnims -> AnimImportScreen(
                        library = anims, rigs = rigs,
                        retargeter = { rig -> Retargeter(Profile.load(profileJson), rig) },
                        onBack = { screen = Screen.Title },
                        onLibrary = { screen = Screen.Animations() },
                    )
                    is Screen.Animations -> AnimLibraryScreen(anims, onImport = { screen = Screen.ImportAnims }, onBack = { screen = s.back })
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
                            placeEngine = placer,
                            // the Game Menu's Animations (back to the scene it was opened from) and Options (over the editor)
                            onAnimations = { p, sceneId -> screen = Screen.Animations(Screen.Editor(EditorStart.Open(p, sceneId))) },
                            onOptions = { panel = Panel.Options },
                        )
                    }
                    is Screen.Story -> director?.let { d ->
                        StoryScreen(sceneView(), d, parchment, frame, onMenu = { audio?.ui("open"); panel = Panel.Pause }, onLog = { panel = Panel.Log })
                    }
                }

                // the editor's first open: "Preparing the interface: n of m" until its art is here (or Continue)
                if (screen is Screen.Editor && ready && art != null && catalog != null) WarArtOverlay(war)

                // a look's files on their way (the creator, the scene builder, Play)
                if (fetching.total > 0 || fetching.error != null || fetching.emotes > 0) {
                    Column(
                        Modifier.align(Alignment.TopCenter).padding(top = 6.dp).background(Color(0xB0000000))
                            .clickable { dresser.clearError() }.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (fetching.total > 0) {
                            Text("Fetching the look's files… ${fetching.done} of ${fetching.total}", style = frizStyle(13.sp))
                            LoadingBar(fetching.done.toFloat() / fetching.total, Modifier.width(320.dp).height(30.dp))
                        }
                        if (fetching.emotes > 0) Text("Loading emote…", style = frizStyle(13.sp))
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
                        OptionsWindow(
                            textSpeed, { textSpeed = it; settings.textSpeed = if (it >= 120) 0 else it },
                            parchment, { parchment = it; settings.boxStyle = if (it) "parchment" else "dark" },
                            wifiOnly, { wifiOnly = it; settings.wifiOnly = it },
                            downloadSpeed, { downloadSpeed = it; settings.downloadSpeed = it; files.setDownloadSpeed(it) },
                            musicVolume, { musicVolume = it; settings.musicVolume = it; audio?.musicVolume = it / 100f; applySceneVolumes() },
                            ambienceVolume, { ambienceVolume = it; settings.ambienceVolume = it; applySceneVolumes() },
                            soundVolume, { soundVolume = it; settings.soundVolume = it; audio?.soundVolume = it / 100f; applySceneVolumes() },
                            voiceVolume, { voiceVolume = it; settings.voiceVolume = it; applySceneVolumes() },
                            softBody, { softBody = it; settings.softBody = it; scene?.softBody(it) },
                            onBack = { panel = if (d != null) Panel.Pause else Panel.None },
                            // from the title and the editor: the creator's imports (Ralia's mod, Alternate Female Options, the body
                            // options, the undead skins) and the Sims 4 skeleton; never while a story plays
                            imports = if (d == null) importsSlot else null,
                            simsSkeleton = if (rigs.has()) "Loaded (kept on the phone)" else "Not loaded: choose a game package in Import Animations",
                            onImportAnims = if (d == null) ({ panel = Panel.None; screen = Screen.ImportAnims }) else null,
                            // the editor's build-mode art: how much is here, what failed, Retry
                            interfaceArt = { WarArtRow(war) },
                            gameFiles = when {
                                !ready -> "Not all here yet"
                                soundMissing > 0 -> "Music and sounds not here yet (%.0f MB)".format(soundMissing / 1e6)
                                else -> null
                            },
                            onGather = if (d == null && (!ready || soundMissing > 0)) ({ panel = Panel.None; screen = Screen.Gather }) else null,
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

    /**
     * The placement editor's engine and Play's [PlaceHost]: the library, the locations (LocationLoader, with SceneAudio's
     * music and ambience from locations.json's ambience block), and the placed things on the renderer under
     * [PlaceKeys] keys: a prop through PropLoader, a creature through CreatureLooks and Dresser.dressLook, an NPC in its cast
     * actor's look through Dresser.dress; their animations (Native.anim once the actor is on the stage), emotes with their
     * voices and creature sounds (SceneAudio, muted per placement). Main thread.
     */
    private inner class ScenePlacer : PlaceEngine {
        private val _library = MutableStateFlow<com.logie.wifecraft.catalog.Catalog?>(null)
        override val library: StateFlow<com.logie.wifecraft.catalog.Catalog?> = _library

        // ------------------------------------------------------------------ the library's pictures, lists and the editor's switches

        /** engine/Thumbs (W35): a library entry drawn small on the renderer, cached; made with the 3D view. */
        private val thumbMaker: Thumbs? by lazy { runCatching { Thumbs(applicationContext, files, sceneView(), tables) }.getOrNull() }
        private val noThumbErrors = MutableStateFlow<String?>("The library's pictures couldn't be set up: names only.")

        override val thumbs: ThumbSource? by lazy {
            object : ThumbSource {
                /** A prop or building by its model file, a creature by its display ([LibraryModel.thumbOf]); none for an own NPC. */
                override fun request(a: com.logie.wifecraft.catalog.Asset, size: Int, onReady: (Bitmap?) -> Unit) {
                    val t = thumbMaker
                    val (creature, id) = LibraryModel.thumbOf(a) ?: run { onReady(null); return }
                    if (t == null) { onReady(null); return }
                    t.request(if (creature) PlaceKind.CREATURE else PlaceKind.PROP, id, size, onReady)
                }

                override fun cached(a: com.logie.wifecraft.catalog.Asset, size: Int): Bitmap? {
                    val (creature, id) = LibraryModel.thumbOf(a) ?: return null
                    return thumbMaker?.cached(if (creature) PlaceKind.CREATURE else PlaceKind.PROP, id, size)
                }

                override fun cancelAll() { thumbMaker?.cancelAll() }
                override val errors: StateFlow<String?> get() = thumbMaker?.errors ?: noThumbErrors
            }
        }

        /** The projects' folder (files/projects), for each project's prefs.json beside its project.json. */
        private val prefsStore by lazy { ProjectStore(File(filesDir, "projects")) }

        override fun libraryPrefs(projectId: String): LibraryPrefs = runCatching { prefsStore.prefs(projectId) }.getOrDefault(LibraryPrefs())

        override fun saveLibraryPrefs(projectId: String, prefs: LibraryPrefs) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { prefsStore.savePrefs(projectId, prefs) }.onFailure { lookError.value = "The library's favourites couldn't be saved: ${it.message ?: it}" }
            }
        }

        override fun flag(name: String, default: Boolean): Boolean = settings.placeFlag(name, default)
        override fun setFlag(name: String, on: Boolean) = settings.setPlaceFlag(name, on)
        override val locations: List<LocationInfo> get() = locationLoader.locations

        /** The location on the stage now (its id), null for none. */
        private val currentLoc = MutableStateFlow<String?>(null)
        private var currentView: String? = null
        private val lookError = MutableStateFlow<String?>(null)
        private val soundState: Flow<Pair<String?, Boolean>> =
            combine(sceneAudio?.error ?: MutableStateFlow<String?>(null), sceneAudio?.preparing ?: MutableStateFlow(false)) { e, p -> e to p }
        override val status: StateFlow<PlaceStatus> = combine(
            locationLoader.progress, propLoader.loading, soundState, currentLoc, lookError,
        ) { lp, pl, (sound, preparing), loc, look ->
            val here = loc != null && lp.id == loc
            PlaceStatus(
                stageName = locationLoader.location(loc ?: "")?.name ?: loc ?: "",
                stageDone = if (here) lp.done else 0, stageTotal = if (here) lp.total else 0,
                stageBytesDone = if (here) lp.bytesDone else 0, stageBytesTotal = if (here) lp.bytesTotal else 0,
                stageReady = loc == null || (here && lp.ready), stageError = if (here) lp.error else null,
                fetchDone = pl.done, fetchTotal = pl.total, fetchError = pl.error ?: look, soundError = sound, soundPreparing = preparing,
            )
        }.stateIn(lifecycleScope, SharingStarted.Eagerly, PlaceStatus())

        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        /** What the editor's preview has on the renderer: by placement id, and the look each NPC was dressed in. */
        private val shown = HashMap<String, Placement>()
        private val looks = HashMap<String, Appearance>()
        /** Bumped when the renderer's placed things are all taken off: retries for the old ones stop. */
        private var generation = 0
        private var libraryAsked = 0

        // ------------------------------------------------------------------ the library

        fun loadLibrary(tablesHere: Boolean) {
            val want = if (tablesHere) 2 else 1
            if (libraryAsked >= want) return
            libraryAsked = want
            lifecycleScope.launch {
                val lib = withContext(Dispatchers.Default) {
                    val tsv = runCatching { com.logie.wifecraft.catalog.Catalog.parseTsv(assets.open("catalog.tsv").bufferedReader().use { it.readText() }) }
                        .onFailure { lookError.value = "The library (catalog.tsv) couldn't be read: ${it.message ?: it}" }.getOrDefault(emptyList())
                    val creatures = if (tablesHere) runCatching { com.logie.wifecraft.catalog.Catalog.creatures(tables) }
                        .onFailure { lookError.value = "The game's creatures couldn't be read from its tables: ${it.message ?: it}" }.getOrDefault(emptyList()) else emptyList()
                    com.logie.wifecraft.catalog.Catalog(tsv + creatures)
                }
                _library.value = lib
            }
        }

        // ------------------------------------------------------------------ the stage

        /** locations.json's ambience block of [id]: (ZoneMusic, SoundAmbience of [view]). */
        private fun ambienceOf(id: String, view: String): Pair<Int, Int> = runCatching {
            val list = JSONObject(locationLoader.json).getJSONArray("locations")
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                if (e.getString("id") != id) continue
                val a = e.optJSONObject("ambience") ?: return@runCatching 0 to 0
                return@runCatching a.optInt("zoneMusic", 0) to (a.optJSONObject("soundAmbience")?.optInt(view, 0) ?: 0)
            }
            0 to 0
        }.getOrDefault(0 to 0)

        override fun enterStage(stageId: String, light: String) {
            val loc = StageChoices.locationOf(stageId)
            if (loc == null) { leaveLocation(); return }
            enterLocation(loc.first, loc.second, light)
        }

        private fun leaveLocation() {
            if (currentLoc.value == null) return
            sceneView().clearLocation()
            currentLoc.value = null; currentView = null
            sceneAudio?.stop()
        }

        /** Loads location [id] in [view] (its files fetched with progress), or only turns to [view] when it is already on the stage. */
        fun enterLocation(id: String, view: String, light: String) {
            val v = sceneView()
            val info = locationLoader.location(id)
            if (info == null) { lookError.value = "There is no location \"$id\" in locations.json."; return }
            if (currentLoc.value == id && locationLoader.progress.value.let { it.id == id && it.ready }) {
                if (currentView != view) v.locationView(view)
            } else {
                locationLoader.load(v, id, view)
            }
            currentLoc.value = id; currentView = view
            // the location's own music and ambience in place of the menus' music
            audio?.music("", 1000)
            if (info.areaId != 0) {
                val (zoneMusic, ambience) = ambienceOf(id, view)
                sceneAudio?.enterLocation(info.areaId, zoneMusic, ambience, night = light == "night")
            }
        }

        // ------------------------------------------------------------------ the placed things on the renderer

        /** Runs [f] on the GL thread once the actor [key] is on the stage (a look's files can take a while), for up to two minutes. */
        private fun whenOnStage(key: String, gen: Int = generation, tries: Int = 480, f: (Long, Int) -> Unit) {
            sceneView().post { h, ids ->
                val id = ids[key]
                if (id != null) f(h, id)
                else if (tries > 0) main.postDelayed({ if (gen == generation) whenOnStage(key, gen, tries - 1, f) }, 250)
            }
        }

        /** Puts [p] on the renderer; [appearance] is an NPC's look (null: not in the cast). */
        private fun put(p: Placement, appearance: Appearance?, play: Boolean) {
            val v = sceneView()
            val key = PlaceKeys.of(p.id)
            sceneAudio?.setEnabled(key, p.sound)
            when (p.kind) {
                PlaceKind.PROP -> propLoader.place(v, key, p.asset, p.x, p.y, p.z, p.yaw, p.scale)
                PlaceKind.CREATURE -> lifecycleScope.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { CreatureLooks.check(tables, p.asset) }.getOrElse { CreatureLooks.Result(null, it.message ?: it.toString()) } }
                    val look = r.look
                    if (look == null) lookError.value = "${p.name} (display ${p.asset}) can't be drawn: ${r.why ?: "no look"}."
                    else dresser.dressLook(v, key, look)
                }
                PlaceKind.NPC -> {
                    val c = creatorCatalog
                    if (appearance == null) lookError.value = "${p.name}: the NPC \"${p.actorId}\" isn't in the cast."
                    else if (c == null) lookError.value = "The character tables aren't read yet: ${p.name} will show once they are."
                    else dresser.dress(v, c, key, appearance)
                }
            }
            if (p.kind != PlaceKind.PROP) {
                // its size is the look's own times the placement's (SceneView.setActorScale); its place is the frame's in the editor, set here once in Play
                v.setActorScale(key, p.scale)
                // its animation's files on demand (an emote's are fetched when it is played, not with the look)
                p.anim?.let { dresser.fetchAnim(key, it) }
                whenOnStage(key) { h, id ->
                    if (play) { Native.place(h, id, p.x, p.y, p.z, p.yaw); Native.show(h, id, true) }
                    Native.anim(h, id, p.anim ?: 0, if (p.anim == null) true else p.loop, Clock.now())
                }
                if (p.kind == PlaceKind.CREATURE && p.sound) sceneAudio?.creature(p.asset, "loop", key)
            }
        }

        private fun take(p: Placement) {
            val key = PlaceKeys.of(p.id)
            if (p.kind == PlaceKind.PROP) sceneView().removeProp(key) else sceneView().removeActor(key)
            sceneAudio?.stopPlacement(key)
        }

        override fun sync(placements: List<Placement>, cast: List<Actor>, hidden: Set<String>) {
            val want = placements.filter { it.id !in hidden }.associateBy { it.id }
            for ((id, old) in shown.toMap()) if (id !in want) { take(old); shown.remove(id); looks.remove(id) }
            for (p in want.values) {
                val old = shown[p.id]
                val look = if (p.kind == PlaceKind.NPC) cast.firstOrNull { it.id == p.actorId }?.appearance else null
                val remake = old == null || old.kind != p.kind || old.asset != p.asset || old.actorId != p.actorId ||
                    (p.kind == PlaceKind.NPC && looks[p.id] != look)
                if (remake) {
                    if (old != null) take(old)
                    put(p, look, play = false)
                    if (look != null) looks[p.id] = look else looks.remove(p.id)
                } else if (old != null) {
                    if (p.kind == PlaceKind.PROP && (old.x != p.x || old.y != p.y || old.z != p.z || old.yaw != p.yaw || old.scale != p.scale))
                        sceneView().moveProp(PlaceKeys.of(p.id), p.x, p.y, p.z, p.yaw, p.scale)
                    if (p.kind != PlaceKind.PROP && old.scale != p.scale) sceneView().setActorScale(PlaceKeys.of(p.id), p.scale)
                    if (p.kind != PlaceKind.PROP && (old.anim != p.anim || old.loop != p.loop)) {
                        val anim = p.anim ?: 0
                        val loop = p.anim == null || p.loop
                        dresser.fetchAnim(PlaceKeys.of(p.id), anim)
                        whenOnStage(PlaceKeys.of(p.id)) { h, id -> Native.anim(h, id, anim, loop, Clock.now()) }
                    }
                    if (old.sound != p.sound) sceneAudio?.setEnabled(PlaceKeys.of(p.id), p.sound)
                }
                shown[p.id] = p
            }
        }

        override fun reset() {
            generation++
            for (id in shown.keys) sceneAudio?.stopPlacement(PlaceKeys.of(id))
            shown.clear(); looks.clear()
            sceneView().clearProps()
        }

        /** Off the editor (the title): the location's music and ambience stop (they start again with the stage). */
        fun quiet() = sceneAudio?.stop()

        // ------------------------------------------------------------------ emotes and sounds

        /** A display's race and sex (CreatureDisplayInfoExtra), for a humanoid creature; null for any other. */
        private val raceSexCache = HashMap<Int, Pair<Int, Sex>?>()
        private fun raceSexOf(display: Int): Pair<Int, Sex>? = raceSexCache.getOrPut(display) {
            runCatching {
                fun Map<String, String>.int(k: String) = this[k]?.toDoubleOrNull()?.toInt() ?: 0
                val extra = tables.rows("CreatureDisplayInfo").firstOrNull { it.int("ID") == display }?.int("ExtendedDisplayInfoID") ?: 0
                if (extra == 0) null else tables.rows("CreatureDisplayInfoExtra").firstOrNull { it.int("ID") == extra }?.let {
                    it.int("DisplayRaceID") to (if (it.int("DisplaySexID") == 1) Sex.FEMALE else Sex.MALE)
                }
            }.getOrNull()
        }

        /** Every animation the game names (emotes.json's names): what a creature of no player race is offered; it plays those its model has. */
        private val gameAnims: List<Emote> by lazy {
            val c = emoteCatalog ?: return@lazy emptyList()
            (0..2000).mapNotNull { a -> c.animName(a)?.let { n -> Emote(EmoteCatalog.ANIM_BASE + a, n, a, 0, emptyList(), EmoteKind.ANIM, EmoteCatalog.categoryOf(n), false, 1) } }
        }

        private fun raceSex(p: Placement, lookOf: (String) -> Appearance?): Pair<Int, Sex>? = when (p.kind) {
            PlaceKind.NPC -> p.actorId?.let(lookOf)?.let { it.raceId to it.sex }
            PlaceKind.CREATURE -> raceSexOf(p.asset)
            PlaceKind.PROP -> null
        }

        fun emotes(p: Placement, lookOf: (String) -> Appearance?): List<Emote> {
            val c = emoteCatalog ?: return emptyList()
            val rs = raceSex(p, lookOf)
            return if (rs != null) c.forModel(rs.first, rs.second).ifEmpty { gameAnims } else if (p.kind == PlaceKind.CREATURE) gameAnims else emptyList()
        }

        override fun emotesFor(p: Placement, cast: List<Actor>): List<Emote> = emotes(p) { a -> cast.firstOrNull { it.id == a }?.appearance }

        fun emote(p: Placement, e: Emote, lookOf: (String) -> Appearance?) {
            val key = PlaceKeys.of(p.id)
            if (e.anim >= 0) {
                // its animation files come now if they aren't here (Loading emote…); the renderer starts it once they are
                dresser.fetchAnim(key, e.anim)
                whenOnStage(key) { h, id -> Native.anim(h, id, e.anim, e.loop, Clock.now()) }
            }
            if (p.sound) raceSex(p, lookOf)?.let { (race, sex) -> sceneAudio?.emote(race, if (sex == Sex.MALE) 0 else 1, e, key) }
        }

        override fun previewEmote(p: Placement, e: Emote, cast: List<Actor>) = emote(p, e) { a -> cast.firstOrNull { it.id == a }?.appearance }

        override fun creatureSound(p: Placement, kind: String) {
            if (p.kind == PlaceKind.CREATURE && p.sound) sceneAudio?.creature(p.asset, kind, PlaceKeys.of(p.id))
        }

        override fun setSound(p: Placement, on: Boolean) { sceneAudio?.setEnabled(PlaceKeys.of(p.id), on) }

        override fun clearErrors() {
            propLoader.clearError(); locationLoader.clearError(); sceneAudio?.clearError(); lookError.value = null; dresser.clearError()
        }

        // ------------------------------------------------------------------ Play

        /**
         * Play's host: the script's `place` lines spawned as the editor's preview spawns them ([cast]: the script's names of
         * the project's people, Compile.cast), placed once on the stage (the Director's frame moves only its own people),
         * `anim` and `emote` on them, and a `loc:` scene's location. A scene cut takes them all off; a location stays only
         * while the next scene is on it.
         */
        fun playHost(cast: Map<String, Actor>): PlaceHost = object : PlaceHost {
            private val spawned = HashMap<String, Placement>()
            private var locationAsked = false
            private val lookOf: (String) -> Appearance? = { a -> cast[a]?.appearance }

            override fun spawn(p: Placement) {
                spawned[p.id] = p
                put(p, if (p.kind == PlaceKind.NPC) p.actorId?.let(lookOf) else null, play = true)
            }

            override fun clear() {
                generation++
                for (p in spawned.values) take(p)
                spawned.clear()
                shown.clear(); looks.clear()
                sceneView().clearProps()
                // the Director names the next scene's location right after this, in the same step: it stays if it does
                locationAsked = false
                main.post { if (!locationAsked) leaveLocation() }
            }

            override fun playAnim(id: String, anim: Int, loop: Boolean) {
                dresser.fetchAnim(PlaceKeys.of(id), anim)
                whenOnStage(PlaceKeys.of(id)) { h, a -> Native.anim(h, a, anim, loop, Clock.now()) }
            }

            override fun playEmote(id: String, emoteId: Int) {
                val p = spawned[id] ?: return
                val e = emotes(p, lookOf).firstOrNull { it.id == emoteId } ?: return
                emote(p, e, lookOf)
            }

            override fun enterLocation(locationId: String, view: String, light: String) {
                locationAsked = true
                this@ScenePlacer.enterLocation(locationId, view, light)
            }
        }
    }
}
