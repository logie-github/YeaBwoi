package com.logie.wifecraft

import android.os.Bundle
import android.util.AtomicFile
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.anim.Profile
import com.logie.wifecraft.anim.Retargeter
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.story.Vec3
import com.logie.wifecraft.ui.AnimImportScreen
import com.logie.wifecraft.ui.RigStore
import com.logie.wifecraft.ui.SceneAnimationScreen
import com.logie.wifecraft.audio.Audio
import com.logie.wifecraft.audio.SceneAudio
import com.logie.wifecraft.audio.SoundVolumes
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.ImportKind
import com.logie.wifecraft.creator.ImportStore
import com.logie.wifecraft.creator.ListfileNames
import com.logie.wifecraft.creator.Prefetch
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.GameTables
import com.logie.wifecraft.creator.RaceBackdrop
import com.logie.wifecraft.creator.TransmogCatalog
import com.logie.wifecraft.data.AssetDownloads
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.data.Lights
import com.logie.wifecraft.data.Settings
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.ProjectStore
import com.logie.wifecraft.engine.Dresser
import com.logie.wifecraft.engine.LocationLoader
import com.logie.wifecraft.engine.PropLoader
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.ui.GameIcons
import com.logie.wifecraft.simulation.Activity
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.ui.CreatorScreen
import com.logie.wifecraft.ui.DownloadOptions
import com.logie.wifecraft.ui.ImportsSection
import com.logie.wifecraft.ui.LocalWar
import com.logie.wifecraft.ui.WarArt
import com.logie.wifecraft.ui.WowWindow
import com.logie.wifecraft.ui.RedButton
import com.logie.wifecraft.ui.frizStyle
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.ui.GatherScreen
import com.logie.wifecraft.ui.LiveSceneScreen
import com.logie.wifecraft.ui.LocalArt
import com.logie.wifecraft.ui.LocalSounds
import com.logie.wifecraft.ui.Overlay
import com.logie.wifecraft.ui.SceneSetupScreen
import com.logie.wifecraft.ui.SetupStep
import com.logie.wifecraft.ui.TitleScreen
import com.logie.wifecraft.ui.UpdatePanel
import com.logie.wifecraft.ui.WowArt
import com.logie.wifecraft.ui.defaultAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var files: GameFiles
    private lateinit var settings: Settings
    private lateinit var tables: CsvTables
    private lateinit var imports: ImportStore
    private lateinit var listfile: ListfileNames
    private lateinit var dresser: Dresser
    private var scene: SceneView? = null
    private var audio: Audio? = null
    private lateinit var locationLoader: LocationLoader
    private lateinit var propLoader: PropLoader
    private var sceneAudio: SceneAudio? = null
    private val rosterFile by lazy { AtomicFile(File(filesDir, "npc-roster.json")) }

    private val animationDirectory by lazy { File(filesDir, "anims") }
    private val animationLibrary by lazy { AnimLibrary(animationDirectory) }
    private val simsRigs by lazy { RigStore(animationDirectory) }
    private val animationBindings by lazy { getSharedPreferences("scene_animations", MODE_PRIVATE) }
    private val simsProfile by lazy { Profile.load(assets.open("sims_profile.json").bufferedReader().use { it.readText() }) }

    private enum class Screen { TITLE, GATHER, SETUP, CREATOR, ANIMATIONS, IMPORT, LIVE }
    private enum class Panel { NONE, OPTIONS, UPDATE }

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
        imports = ImportStore(filesDir, listfile, supportedKinds = setOf(ImportKind.RALIA, ImportKind.BODY, ImportKind.UNDEAD))
        dresser = Dresser(files, tables, imports, lifecycleScope)
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

    private fun readRoster(): List<Actor> {
        val local = if (rosterFile.baseFile.exists()) {
            val rows = JSONArray(rosterFile.readFully().decodeToString())
            List(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                Actor(row.getString("id"), row.getString("name"), Appearance.fromJson(row.getJSONObject("appearance")))
            }
        } else emptyList()
        val legacy = ProjectStore(File(filesDir, "projects")).list().flatMap { project ->
            project.cast.map { it.copy(id = "${project.id}:${it.id}") }
        }
        return local + legacy
    }

    private fun saveNpc(actor: Actor) {
        val rows = if (rosterFile.baseFile.exists()) JSONArray(rosterFile.readFully().decodeToString()) else JSONArray()
        rows.put(JSONObject().put("id", actor.id).put("name", actor.name).put("appearance", actor.appearance.toJson()))
        val output = rosterFile.startWrite()
        try {
            output.write(rows.toString().encodeToByteArray())
            rosterFile.finishWrite(output)
        } catch (failure: Exception) {
            rosterFile.failWrite(output)
            throw failure
        }
    }

    private val wardrobeLock = Mutex()
    private var cachedWardrobe: TransmogCatalog? = null

    private suspend fun loadWardrobe(): TransmogCatalog = wardrobeLock.withLock {
        cachedWardrobe ?: withContext(Dispatchers.IO) {
            GameTables.fetch(files.tablesDir, names = TransmogCatalog.TABLES)
            TransmogCatalog.load(tables).also {
                check(it.starter.keys.any { slot -> slot == 4 || slot == 5 }) { "The starter clothing could not be found in the downloaded tables." }
            }
        }.also { cachedWardrobe = it }
    }

    @Composable
    private fun App() {
        val scope = rememberCoroutineScope()
        val icons = remember { GameIcons(files) }
        val menuArt = remember { WarArt.open(applicationContext) }
        var wardrobe by remember { mutableStateOf<TransmogCatalog?>(null) }
        var wardrobeStatus by remember { mutableStateOf<String?>(null) }
        var wardrobeJob by remember { mutableStateOf<Job?>(null) }
        var downloadProgress by remember { mutableStateOf<AssetDownloads.Progress?>(null) }
        var speed by remember { mutableIntStateOf(settings.downloadSpeed) }
        var effects by remember { mutableIntStateOf(settings.soundVolume) }
        var downloadStatus by remember { mutableStateOf<String?>(null) }
        var downloadJob by remember { mutableStateOf<Job?>(null) }

        fun loadTransmog() {
            if (wardrobe != null || wardrobeJob?.isActive == true) return
            wardrobeStatus = null
            wardrobeJob = scope.launch {
                try {
                    wardrobe = loadWardrobe()
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { wardrobeStatus = failure.message ?: "The clothing catalog could not be loaded." }
            }
        }

        var screen by remember { mutableStateOf(Screen.TITLE) }
        LaunchedEffect(screen) {
            if (screen == Screen.SETUP || screen == Screen.CREATOR) loadTransmog()
        }
        var panel by remember { mutableStateOf(Panel.NONE) }
        var art by remember { mutableStateOf<WowArt?>(null) }
        var catalog by remember { mutableStateOf<Catalog?>(null) }
        var savedRoster by remember { mutableStateOf(emptyList<Actor>()) }
        var problem by remember { mutableStateOf<String?>(null) }
        var update by remember { mutableStateOf<Updater.Update?>(null) }
        var updateStatus by remember { mutableStateOf("") }
        var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
        var step by remember { mutableStateOf(SetupStep.LOCATION) }
        var location by remember { mutableStateOf<String?>(null) }
        var cast by remember { mutableStateOf(emptySet<String>()) }
        var availableClips by remember { mutableStateOf(emptyList<Anim>()) }
        var bindings by remember {
            mutableStateOf(Activity.entries.mapNotNull { a -> animationBindings.getString(a.name, null)?.let { a to it } }.toMap())
        }
        LaunchedEffect(screen) {
            if (screen == Screen.ANIMATIONS || screen == Screen.SETUP) {
                availableClips = withContext(Dispatchers.IO) { animationLibrary.list() }
            }
        }
        var objects by remember { mutableStateOf(SceneObject.entries.toSet()) }
        val progress by files.progress.collectAsState()
        val stage by locationLoader.progress.collectAsState()
        val props by propLoader.loading.collectAsState()
        val fetching by dresser.fetching.collectAsState()
        val ready = files.tablesComplete && art != null && catalog != null
        val stages = remember { locationLoader.locations.filter { it.views["inside"]?.camera != null } }
        val starters = remember(catalog) {
            val race = catalog?.races?.let { races -> races.firstOrNull { it.id == 1 } ?: races.firstOrNull() }
            if (race == null) emptyList() else race.sexes.take(2).map { sex ->
                Actor("starter:${sex.name}", if (sex == Sex.FEMALE) "Mira" else "Rowan", Appearance(race.id, sex))
            }
        }
        val roster = savedRoster + starters
        val chosen = roster.filter { it.id in cast }

        LaunchedEffect(progress.complete) {
            if (art == null && files.packComplete("interface")) {
                art = withContext(Dispatchers.IO) { runCatching { WowArt.load(files) }.getOrNull() }
            }
            if (catalog == null && files.tablesComplete) {
                catalog = withContext(Dispatchers.Default) { runCatching { Catalog.build(tables) }.getOrNull() }
            }
            if (files.packComplete("sound")) audio?.filesArrived()
        }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { readRoster() } }
                .onSuccess { savedRoster = it }
                .onFailure { problem = "Saved NPCs could not be loaded." }
            if (Updater.isConfigured) update = withContext(Dispatchers.IO) { runCatching { Updater.findUpdate() }.getOrNull() }
        }

        BackHandler(panel != Panel.NONE || screen == Screen.GATHER) {
            if (panel != Panel.NONE) panel = Panel.NONE else screen = Screen.TITLE
        }

        LaunchedEffect(art != null) { if (art != null) menuArt.start() }
        CompositionLocalProvider(LocalArt provides art, LocalWar provides menuArt, LocalSounds provides { name: String -> audio?.ui(name) }) {
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.TITLE -> TitleScreen(
                        ready,
                        files.totalBytes.let { b -> if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6) },
                        update?.title,
                        onChooseScene = {
                            step = SetupStep.LOCATION
                            location = stages.firstOrNull()?.id
                            cast = emptySet()
                            objects = SceneObject.entries.toSet()
                            screen = Screen.SETUP
                        },
                        onGather = { screen = Screen.GATHER },
                        onOptions = { panel = Panel.OPTIONS },
                        onUpdates = { updateStatus = ""; panel = Panel.UPDATE },
                    )
                    Screen.GATHER -> GatherScreen(
                        progress, listOf(
                            "interface" to "Interface", GameFiles.TABLES to "Character tables",
                            GameFiles.LOOKS to "Character models", "sound" to "Music and sounds",
                        ),
                        wifiOnly,
                        onWifiOnly = { wifiOnly = it; settings.wifiOnly = it },
                        onStart = { files.start(wifiOnly) },
                        onPause = { files.pause() },
                        onBack = { screen = Screen.TITLE },
                    )
                    Screen.SETUP -> {
                        val previewView = if (step == SetupStep.OBJECTS) null else sceneView()
                        val portraits = if (step == SetupStep.CAST) {
                            remember { Thumbs(applicationContext, files, previewView, tables) }.also { source ->
                                DisposableEffect(source) { onDispose { source.close() } }
                            }
                        } else null
                        SceneSetupScreen(
                            step, stages, location, { location = it }, roster, cast,
                            onToggleActor = { id ->
                                if (id in cast) cast = cast - id
                                else if (cast.size < 6) cast = cast + id
                            },
                            previewView = previewView,
                            locationLoader = locationLoader,
                            portraits = portraits,
                            portraitActor = { actor ->
                                val raw = actor.appearance
                                val clothes = wardrobe
                                val appearance = if (raw.starterClothing && raw.gear) clothes?.equipStarter(raw) else raw
                                appearance?.let { actor.copy(appearance = it) }
                            },
                            objects = objects,
                            onToggleObject = { item -> objects = if (item in objects) objects - item else objects + item },
                            onCreate = { screen = Screen.CREATOR },
                            onAnimations = { screen = Screen.ANIMATIONS },
                            onBack = {
                                when (step) {
                                    SetupStep.LOCATION -> screen = Screen.TITLE
                                    SetupStep.CAST -> step = SetupStep.LOCATION
                                    SetupStep.OBJECTS -> step = SetupStep.CAST
                                }
                            },
                            onNext = {
                                when (step) {
                                    SetupStep.LOCATION -> step = SetupStep.CAST
                                    SetupStep.CAST -> step = SetupStep.OBJECTS
                                    SetupStep.OBJECTS -> if (location != null && chosen.isNotEmpty()) {
                                        if (wardrobe == null && chosen.any { it.appearance.starterClothing && it.appearance.gear }) {
                                            val lastError = wardrobeStatus
                                            loadTransmog()
                                            problem = lastError ?: "Starter clothing is loading. Try Begin scene again once the clothes are ready."
                                        } else {
                                            screen = Screen.LIVE
                                        }
                                    }
                                }
                            },
                        )
                    }
                    Screen.CREATOR -> {
                        val c = checkNotNull(catalog)
                        val view = sceneView()
                        val model = remember { CreatorModel(c, checkNotNull(defaultAppearance(c))) }
                        val key = "npc-preview"
                        val portraits = remember(view) { Thumbs(applicationContext, files, view, tables) }
                        var backdropRace by remember { mutableStateOf<Int?>(null) }
                        var backdropStatus by remember { mutableStateOf<String?>(null) }
                        var backdropRetry by remember { mutableIntStateOf(0) }
                        LaunchedEffect(backdropRace, backdropRetry) {
                            val race = backdropRace ?: return@LaunchedEffect
                            view.creatorBackdrop(0)
                            val fileId = RaceBackdrop.file(tables, race)
                            if (fileId == null) {
                                backdropStatus = "No Warcraft backdrop is listed for this race."
                                return@LaunchedEffect
                            }
                            backdropStatus = "Loading Warcraft backdrop…"
                            try {
                                val failure = withContext(Dispatchers.IO) {
                                    val job = kotlin.coroutines.coroutineContext[Job]
                                    PropLoader.closure(files, fileId) { ids ->
                                        for (id in ids) {
                                            if (job?.isActive == false) throw CancellationException()
                                            files.fetchNow(id)
                                        }
                                    }
                                }
                                if (failure != null) {
                                    backdropStatus = "Warcraft backdrop unavailable. Tap to retry."
                                } else {
                                    val retry = backdropRetry
                                    view.creatorBackdrop(fileId) { drawn ->
                                        if (backdropRace == race && backdropRetry == retry) {
                                            backdropStatus = if (drawn) null else "Warcraft backdrop could not be drawn. Tap to retry."
                                        }
                                    }
                                }
                            } catch (failure: CancellationException) { throw failure }
                            catch (_: Exception) { backdropStatus = "Warcraft backdrop download failed. Tap to retry." }
                        }
                        DisposableEffect(view) {
                            view.removeAll()
                            view.clearLocation()
                            view.showCity(false)
                            view.light(Lights.of("day"))
                            onDispose {
                                dresser.cancel(key)
                                portraits.close()
                                backdropRace = null
                                view.creatorBackdrop(0)
                                view.removeActor(key)
                                view.clearLocation()
                                view.frame = null
                            }
                        }
                        BackHandler { screen = Screen.SETUP }
                        AndroidView({ (view.parent as? ViewGroup)?.removeView(view); view }, Modifier.fillMaxSize())
                        CreatorScreen(
                            model, checkNotNull(art), view, tables,
                            actorKey = key,
                            onPreviewRace = { backdropRace = it },
                            transmog = wardrobe,
                            transmogStatus = wardrobeStatus,
                            onLoadTransmog = ::loadTransmog,
                            iconSource = icons,
                            portraits = portraits,
                            onLook = {
                                val raw = model.appearance
                                if (wardrobe != null || !raw.starterClothing || !raw.gear) {
                                    dresser.dress(view, c, key, wardrobe?.equipStarter(raw) ?: raw)
                                }
                            },
                            onFinish = { name, appearance ->
                                val dressed = wardrobe?.equipStarter(appearance) ?: appearance
                                val npc = Actor(UUID.randomUUID().toString(), name.ifBlank { "New NPC" }, dressed)
                                runCatching { saveNpc(npc) }.onSuccess {
                                    savedRoster = savedRoster + npc
                                    if (cast.size < 6) cast = cast + npc.id
                                    screen = Screen.SETUP
                                }.onFailure { problem = "The NPC could not be saved." }
                            },
                            onBack = { screen = Screen.SETUP },
                        )
                        if (wardrobe == null) {
                            val message = wardrobeStatus ?: "Loading starter clothing…"
                            if (wardrobeStatus == null) {
                                Text(message, Modifier.align(Alignment.TopCenter).padding(top = 78.dp), color = Color.White)
                            } else {
                                TextButton(::loadTransmog, Modifier.align(Alignment.TopCenter).padding(top = 74.dp)) { Text("Retry starter clothing") }
                            }
                        }
                        backdropStatus?.let { message ->
                            if (message.endsWith("Tap to retry.")) {
                                TextButton({ backdropRetry++ }, Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) { Text(message) }
                            } else {
                                Text(message, Modifier.align(Alignment.TopCenter).padding(top = 42.dp), color = Color.White)
                            }
                        }
                    }
                    Screen.ANIMATIONS -> SceneAnimationScreen(
                        availableClips, bindings,
                        onChange = { activity, id ->
                            bindings = if (id == null) bindings - activity else bindings + (activity to id)
                            animationBindings.edit().putString(activity.name, id).apply()
                        },
                        onImport = { screen = Screen.IMPORT },
                        onBack = { screen = Screen.SETUP },
                    )
                    Screen.IMPORT -> AnimImportScreen(
                        animationLibrary, simsRigs,
                        retargeter = { rig -> Retargeter(simsProfile, rig) },
                        onBack = { screen = Screen.ANIMATIONS },
                    )
                    Screen.LIVE -> {
                        val preset = stages.first { it.id == location }
                        val indoor = preset.views.getValue("inside")
                        val camera = checkNotNull(indoor.camera)
                        val origin = indoor.spawn ?: Vec3(0f, 0f, 0f)
                        val embassy = preset.id == "embassy"
                        val rotation = if (embassy) (-Math.PI / 2).toFloat() else 0f
                        val toStage: (Vec3) -> Vec3 = { point ->
                            origin + if (embassy) Vec3(point.y, -point.x, point.z) else point
                        }
                        val view = sceneView()
                        val c = checkNotNull(catalog)
                        val session = remember { UUID.randomUUID().toString() }
                        val actorKey: (String) -> String = { "$session:npc:$it" }
                        val objectKey: (SceneObject) -> String = { "$session:object:${it.name}" }
                        val bookKey = "$session:book"
                        var locationResult by remember(session) { mutableStateOf<Boolean?>(null) }
                        DisposableEffect(session) {
                            view.removeAll()
                            view.clearProps()
                            propLoader.clearError()
                            view.showCity(false)
                            view.light(Lights.of("day"))
                            locationLoader.load(view, preset.id, "inside") { locationResult = it }
                            for (actor in chosen) {
                                val dressed = wardrobe?.equipStarter(actor.appearance) ?: actor.appearance
                                dresser.dress(view, c, actorKey(actor.id), dressed)
                            }
                            for (item in objects) {
                                val pos = toStage(item.position)
                                propLoader.place(view, objectKey(item), item.model, pos.x, pos.y, pos.z, rotation)
                            }
                            if (SceneObject.TABLE in objects) {
                                val pos = toStage(SceneObject.TABLE.position.copy(z = 0.82f))
                                propLoader.place(view, bookKey, SceneObject.BOOK_MODEL, pos.x, pos.y, pos.z, rotation)
                            }
                            onDispose {
                                locationLoader.cancel()
                                for (actor in chosen) dresser.cancel(actorKey(actor.id))
                                for (item in objects) propLoader.cancel(objectKey(item))
                                propLoader.cancel(bookKey)
                                view.select(null)
                                view.frame = null
                                view.removeAll()
                                view.clearProps()
                                view.clearLocation()
                            }
                        }
                        val clips = remember(availableClips, bindings) {
                            val byId = availableClips.associateBy { it.id }
                            bindings.mapNotNull { (activity, id) ->
                                val source = byId[id] ?: return@mapNotNull null
                                val clip = if (activity == Activity.WALK) {
                                    source.copy(tracks = source.tracks.map { track ->
                                        if (track.bone == "Root" && track.channel == Channel.POSITION) {
                                            com.logie.wifecraft.anim.BoneTrack(track.bone, track.channel, track.times,
                                                track.values.copyOf().also { values ->
                                                    for (i in values.indices step 3) { values[i] = 0f; values[i + 1] = 0f }
                                                })
                                        } else track
                                    })
                                } else source
                                activity to clip
                            }.toMap()
                        }
                        val animations = remember(session) { mutableMapOf<String, Pair<Activity, String?>>() }
                        LiveSceneScreen(
                            view, camera, preset.name, chosen, objects, actorKey, objectKey, bookKey, toStage, rotation,
                            onAnimate = { id, activity ->
                                val clip = clips[activity]
                                val playback = activity to clip?.id
                                if (animations.put(id, playback) != playback) {
                                    val key = actorKey(id)
                                    if (clip != null) {
                                        view.playAnim(key, clip)
                                    } else {
                                        view.stopAnim(key)
                                        dresser.fetchAnim(key, activity.animation)
                                        view.animateActor(key, activity.animation)
                                    }
                                }
                            },
                            durations = clips.filterValues { !it.loop }.mapValues { it.value.duration },
                            onEnd = { screen = Screen.TITLE },
                            ready = locationResult == true,
                            status = when {
                                locationResult != null && stage.id == preset.id && stage.error != null -> stage.error
                                locationResult != true -> "Opening ${preset.name}…"
                                fetching.total > 0 -> "Loading NPCs… ${fetching.done}/${fetching.total}"
                                props.total > 0 -> "Loading objects… ${props.done}/${props.total}"
                                fetching.error != null -> fetching.error
                                else -> props.error
                            },
                        )
                    }
                }
                if (screen == Screen.CREATOR && fetching.total > 0) {
                    Text("Loading NPC preview…", Modifier.align(Alignment.TopCenter).padding(12.dp), color = Color.White)
                }
                when (panel) {
                    Panel.NONE -> Unit
                    Panel.OPTIONS -> DownloadOptions(
                        progress = downloadProgress,
                        status = downloadStatus,
                        speed = speed,
                        onSpeed = {
                            speed = it
                            settings.downloadSpeed = it
                            files.setDownloadSpeed(it)
                        },
                        volume = effects,
                        onVolume = {
                            effects = it
                            settings.soundVolume = it
                            audio?.soundVolume = it / 100f
                            applySceneVolumes()
                        },
                        downloading = downloadJob?.isActive == true,
                        canDownload = catalog != null,
                        onDownload = { packs ->
                            if (downloadJob?.isActive != true) {
                                downloadProgress = AssetDownloads.Progress(packs.first(), 1, packs.size)
                                downloadStatus = null
                                downloadJob = scope.launch {
                                    try {
                                        downloadStatus = AssetDownloads(files, tables).download(packs, stages, catalog, ::loadWardrobe) { value -> downloadProgress = value }
                                    } catch (failure: CancellationException) {
                                        downloadStatus = "Download paused. Cached files are ready for the next download."
                                        throw failure
                                    } catch (failure: Exception) {
                                        downloadStatus = failure.message ?: "The download failed."
                                    } finally {
                                        downloadJob = null
                                    }
                                }
                            }
                        },
                        onPause = { downloadJob?.cancel() },
                        onClose = { panel = Panel.NONE },
                        mods = { ImportsSection(imports, listfile) },
                    )
                    Panel.UPDATE -> Overlay({ panel = Panel.NONE }) {
                        LaunchedEffect(Unit) {
                            if (!Updater.isConfigured) {
                                updateStatus = "This build doesn't check for updates."
                            } else {
                                updateStatus = "Checking…"
                                update = withContext(Dispatchers.IO) { runCatching { Updater.findUpdate() }.getOrNull() }
                                updateStatus = update?.let { "${it.title} is available." } ?: "You have the newest version (${BuildConfig.VERSION_NAME})."
                            }
                        }
                        UpdatePanel(updateStatus, update != null, onInstall = { update?.let { installUpdate(it) { status -> updateStatus = status } } },
                            onBack = { panel = Panel.NONE })
                    }
                }
                problem?.let { message ->
                    Overlay({ problem = null }) {
                        WowWindow("Wifecraft", { problem = null }, Modifier.widthIn(max = 500.dp)) {
                            Text(message, style = frizStyle(14.sp, Color.White))
                            RedButton("Close", { problem = null }, Modifier.padding(top = 10.dp))
                        }
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
