package com.logie.wifecraft

import android.os.Bundle
import android.util.AtomicFile
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.anim.CheckedAnimation
import com.logie.wifecraft.anim.Profile
import com.logie.wifecraft.anim.Retargeter
import com.logie.wifecraft.story.Vec3
import com.logie.wifecraft.ui.AnimImportScreen
import com.logie.wifecraft.ui.AnimLibraryScreen
import com.logie.wifecraft.ui.AnimationViewport
import com.logie.wifecraft.ui.RigStore
import com.logie.wifecraft.ui.SceneAnimationScreen
import com.logie.wifecraft.audio.Audio
import com.logie.wifecraft.audio.SceneAudio
import com.logie.wifecraft.audio.MixLevels
import com.logie.wifecraft.audio.MixSettings
import com.logie.wifecraft.audio.Mixer
import com.logie.wifecraft.simulation.PartnerFrequency
import com.logie.wifecraft.simulation.ChatterFrequency
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.anim.animationChoices
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.PartnerSoundSink
import com.logie.wifecraft.ui.PartnerSoundsSection
import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.bodyparts.PartChoice
import com.logie.wifecraft.bodyparts.PartStates
import com.logie.wifecraft.characters.NamedCast
import com.logie.wifecraft.characters.NpcLooks
import com.logie.wifecraft.characters.NpcRosterFile
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.PartOptions
import com.logie.wifecraft.ui.LocalNpcActions
import com.logie.wifecraft.ui.LocalPartControl
import com.logie.wifecraft.ui.NpcActions
import com.logie.wifecraft.ui.NpcDeleteWindow
import com.logie.wifecraft.ui.NpcMenuWindow
import com.logie.wifecraft.ui.PartControl
import com.logie.wifecraft.ui.PartImportSection
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
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.LifeProfile
import com.logie.wifecraft.simulation.LifeSim
import com.logie.wifecraft.simulation.WorldClock
import org.json.JSONObject
import com.logie.wifecraft.ui.CreatorScreen
import com.logie.wifecraft.ui.DownloadOptions
import com.logie.wifecraft.ui.ImportsSection
import com.logie.wifecraft.ui.LocalWar
import com.logie.wifecraft.ui.WarArt
import com.logie.wifecraft.ui.WifeMessage
import com.logie.wifecraft.ui.frizStyle
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.ui.GatherScreen
import com.logie.wifecraft.ui.LiveSceneScreen
import com.logie.wifecraft.ui.LocalArt
import com.logie.wifecraft.ui.LocalSounds
import com.logie.wifecraft.ui.Overlay
import com.logie.wifecraft.ui.OverlayFor
import com.logie.wifecraft.ui.SceneSetupScreen
import com.logie.wifecraft.ui.SetupStep
import com.logie.wifecraft.ui.TitleScreen
import com.logie.wifecraft.ui.UpdatePanel
import com.logie.wifecraft.ui.WowArt
import com.logie.wifecraft.ui.defaultAppearance
import com.logie.wifecraft.ui.collectThrottled
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private var sceneAudio: SceneAudio? = null
    private lateinit var mixSettings: MixSettings
    /** The Sound page's levels (Options), kept by [mixSettings]. */
    private var mix = MixLevels()
    private val rosterFile by lazy { AtomicFile(File(filesDir, "npc-roster.json")) }
    /** What the user chose for the built-in NPCs and the named characters (their look, a man's body-part settings): NpcLooks. */
    private val looksFile by lazy { AtomicFile(File(filesDir, "npc-looks.json")) }
    /** files/bodyparts: the named characters' model sets cut on the phone and the imported body-part sets. */
    private val partsRoot by lazy { File(filesDir, "bodyparts") }
    /** The named characters (assets/characters.json) with their partial looks (assets/model_parts.json). */
    private val namedCast by lazy { NamedCast.parse(assetText("characters.json"), assetText(ModelParts.ASSET)) }

    private fun assetText(name: String): String? = runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    private val animationDirectory by lazy { File(filesDir, "anims") }
    private val animationLibrary by lazy { AnimLibrary(animationDirectory) }
    private val simsRigs by lazy { RigStore(animationDirectory) }
    private val simsProfile by lazy { Profile.load(assets.open("sims_profile.json").bufferedReader().use { it.readText() }) }

    private enum class Screen { TITLE, GATHER, SETUP, CREATOR, ANIMATIONS, IMPORT, IMPORT_WICKED_WHIMS, LIBRARY, LIVE }
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
        dresser = Dresser(files, tables, imports, lifecycleScope).apply {
            named = namedCast
            manifestJson = assetText(ModelParts.ASSET)
            partsRoot = this@MainActivity.partsRoot
            partSet = settings.partSet
        }
        mixSettings = MixSettings(applicationContext)
        mix = mixSettings.load()
        audio = runCatching { Audio(applicationContext, files) }.getOrNull()
        locationLoader = LocationLoader(applicationContext, files, lifecycleScope)
        sceneAudio = runCatching { SceneAudio(applicationContext, files, tables) }.getOrNull()
        applyMix(mix)
        sceneAudio?.apply {
            partnerOn = settings.partnerSoundsOn; partnerVolume = settings.partnerSoundVolume / 100f
            // the music and the ambience dip while an emote's voice or a partner sound plays
            onVoices = { n -> audio?.voices(n) }
        }
        setContent { App() }
    }

    override fun onPause() {
        super.onPause()
        scene?.onPause()
        audio?.pause()
        sceneAudio?.pause()
        sceneAudio?.stopAll()
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

    /** The Sound page's levels on everything that plays: the soundscape (Audio) and the scene's sounds (SceneAudio). */
    private fun applyMix(m: MixLevels) {
        mix = m
        audio?.levels = m
        sceneAudio?.volumes = Mixer.sceneVolumes(m)
    }

    private fun sceneView(): SceneView = scene ?: SceneView(this, files.dir.path).also { scene = it; it.softBody(Settings(this).softBody) }

    private fun readText(file: AtomicFile): String? = if (file.baseFile.exists()) file.readFully().decodeToString() else null

    /** A cast's saved life (LifeSim.fileName) and its world clock beside it ("clock_" and the same name), in files/life. */
    private fun lifeFiles(ids: List<String>): Pair<AtomicFile, AtomicFile> {
        val name = LifeSim.fileName(ids)
        val dir = File(filesDir, "life")
        return AtomicFile(File(dir, name)) to AtomicFile(File(dir, "clock_" + name.removePrefix("life_")))
    }

    private val lifeLock = Any()

    /** A cast's life and clock written whole or not at all (any thread; one write at a time). A failed write keeps the last. */
    private fun saveLife(files: Pair<AtomicFile, AtomicFile>, life: String?, clock: String?) = synchronized(lifeLock) {
        runCatching {
            files.first.baseFile.parentFile?.mkdirs()
            if (life != null) writeText(files.first, life)
            if (clock != null) writeText(files.second, clock)
        }
    }

    /** [text] written whole or not at all (AtomicFile). */
    private fun writeText(file: AtomicFile, text: String) {
        val output = file.startWrite()
        try {
            output.write(text.encodeToByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    /** The NPCs the user made (npc-roster.json: they can be deleted) and those of older projects (read from the projects). */
    private fun readRoster(): Pair<List<Actor>, List<Actor>> {
        val local = NpcRosterFile.parse(readText(rosterFile))
        val legacy = ProjectStore(File(filesDir, "projects")).list().flatMap { project ->
            project.cast.map { it.copy(id = "${project.id}:${it.id}") }
        }
        return local to legacy
    }

    private fun saveNpc(actor: Actor) = writeText(rosterFile, NpcRosterFile.add(readText(rosterFile), actor))

    private fun updateNpc(actor: Actor) = writeText(rosterFile, NpcRosterFile.replace(readText(rosterFile), actor))

    /** The NPC [id] out of npc-roster.json, and its portraits out of files/thumbs. */
    private fun deleteNpc(id: String) {
        writeText(rosterFile, NpcRosterFile.delete(readText(rosterFile), id))
        val thumbs = File(filesDir, "thumbs")
        for (name in NpcRosterFile.thumbnails(thumbs.list()?.toList().orEmpty(), id)) File(thumbs, name).delete()
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
        // set from the download's threads once per file; read only by the Options panel's host, at most 4 times a second
        val downloadProgress = remember { MutableStateFlow<AssetDownloads.Progress?>(null) }
        var speed by remember { mutableIntStateOf(settings.downloadSpeed) }
        var mixShown by remember { mutableStateOf(mix) }
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
        // the ids of npc-roster.json's NPCs (the user's own: they have Delete); the older projects' NPCs are in savedRoster too
        var savedIds by remember { mutableStateOf(emptySet<String>()) }
        var npcLooks by remember { mutableStateOf(NpcLooks()) }
        var npcMenu by remember { mutableStateOf<String?>(null) }
        var npcDelete by remember { mutableStateOf<Actor?>(null) }
        var partSet by remember { mutableStateOf(settings.partSet) }
        var problem by remember { mutableStateOf<String?>(null) }
        var update by remember { mutableStateOf<Updater.Update?>(null) }
        var updateStatus by remember { mutableStateOf("") }
        var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
        var step by remember { mutableStateOf(SetupStep.LOCATION) }
        var location by remember { mutableStateOf<String?>(null) }
        var cast by remember { mutableStateOf(emptySet<String>()) }
        var availableClips by remember { mutableStateOf(emptyList<CheckedAnimation>()) }
        // the library's partner animations, read again whenever the setup, the animation or the live screens open (as the clips are)
        var pairs by remember { mutableStateOf(emptyList<PairAnim>()) }
        var partnerFrequency by remember { mutableStateOf(PartnerFrequency.of(settings.partnerFrequency)) }
        var chatterFrequency by remember { mutableStateOf(ChatterFrequency.of(settings.chatterFrequency)) }
        // Options' Time page: the life simulation's switch and the world clock's settings
        var lifeOn by remember { mutableStateOf(settings.lifeSimulation) }
        var clockSettings by remember { mutableStateOf(settings.clock) }
        var partnerOn by remember { mutableStateOf(settings.partnerSoundsOn) }
        var partnerVolume by remember { mutableIntStateOf(settings.partnerSoundVolume) }
        val partnerSink = remember {
            object : PartnerSoundSink {
                override fun play(name: String, actorKey: String?, loop: Boolean) { sceneAudio?.partnerSound(name, actorKey, loop, 1f) }
                override fun stopAll() { sceneAudio?.stopAll() }
            }
        }
        LaunchedEffect(screen) {
            if (screen == Screen.ANIMATIONS || screen == Screen.LIVE || screen == Screen.SETUP) {
                availableClips = withContext(Dispatchers.IO) { animationLibrary.list().map { CheckedAnimation(it) } }
                pairs = withContext(Dispatchers.IO) { runCatching { animationLibrary.pairs() }.getOrDefault(emptyList()) }
            }
        }
        var objects by remember { mutableStateOf(emptySet<SceneObject>()) }
        // The root reads only which packs are complete (a few changes in all), never the download's ticks: those are read
        // by the small composables that show them (GatherHost, the live scene's status, the Options panel), so a tick
        // redraws them and not the whole app. Nothing here touches the disk while composing.
        val complete by remember { files.progress.map { it.complete }.distinctUntilChanged() }
            .collectAsStateWithLifecycle(files.progress.value.complete)
        val fetchingState = dresser.fetching.collectThrottled { old, new -> (old?.total == 0) != (new.total == 0) || new.error != old?.error }
        val npcLoading by remember { derivedStateOf { fetchingState.value.total > 0 } }
        val ready = GameFiles.TABLES in complete && art != null && catalog != null
        val stages = remember { locationLoader.locations.filter { it.views["inside"]?.camera != null } }
        fun sceneObjects(id: String?) = stages.firstOrNull { it.id == id }?.interactions.orEmpty()
            .mapNotNull { interaction -> SceneObject.entries.firstOrNull { it.name == interaction.item } }.toSet()
        val starters = remember(catalog, npcLooks.variants) {
            val race = catalog?.races?.let { races -> races.firstOrNull { it.id == 1 } ?: races.firstOrNull() }
            val built = if (race == null) emptyList() else race.sexes.take(2).map { sex ->
                Actor("${NpcRosterFile.STARTER_PREFIX}${sex.name}", if (sex == Sex.FEMALE) "Mira" else "Rowan", Appearance(race.id, sex))
            }
            // the named characters, each in the look chosen on its menu (npc-looks.json)
            built + namedCast.actors(npcLooks.variants)
        }
        // a man's body-part settings: in npc-roster.json for the user's own NPCs, in npc-looks.json for the others
        val roster = remember(savedRoster, starters, npcLooks) { (savedRoster + starters).map { npcLooks.apply(it) } }
        val chosen = remember(roster, cast) { roster.filter { it.id in cast } }

        LaunchedEffect(complete) {
            if (art == null && "interface" in complete) {
                art = withContext(Dispatchers.IO) { runCatching { WowArt.load(files) }.getOrNull() }
            }
            if (catalog == null && GameFiles.TABLES in complete) {
                catalog = withContext(Dispatchers.Default) { runCatching { Catalog.build(tables) }.getOrNull() }
            }
            // the player has gathered the game's files: the music and sounds not here yet may be fetched as they are wanted
            if ("interface" in complete || GameFiles.TABLES in complete) audio?.fetchAllowed = true
            if ("sound" in complete) audio?.filesArrived()
        }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { readRoster() } }
                .onSuccess { (local, legacy) -> savedRoster = local + legacy; savedIds = local.mapTo(HashSet()) { it.id } }
                .onFailure { problem = "Saved NPCs could not be loaded." }
            runCatching { withContext(Dispatchers.IO) { NpcLooks.parse(readText(looksFile)) } }.onSuccess { npcLooks = it }
            if (Updater.isConfigured) update = withContext(Dispatchers.IO) { runCatching { Updater.findUpdate() }.getOrNull() }
        }

        LaunchedEffect(art != null) { if (art != null) menuArt.start() }
        // the NPC picker's card menus: a named character's look, a man's body-part settings, Delete for the user's own NPCs
        val npcActions = remember(savedIds) {
            NpcActions(
                hasMenu = { actor -> NpcRosterFile.canDelete(actor.id, savedIds) || NamedCast.isNamed(actor.id) || PartChoice.applies(actor.appearance) },
                open = { actor -> npcMenu = actor.id },
                portraitDisplay = { actor -> namedCast.choiceOf(actor.appearance)?.display ?: 0 },
            )
        }
        fun saveLooks(looks: NpcLooks) {
            npcLooks = looks
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { writeText(looksFile, looks.toJson()) } }
                    .onFailure { problem = "The NPC's look could not be saved." }
            }
        }
        // the soundscape follows the screen: the title's music on the menus, a place's music and ambience in its live scene
        LaunchedEffect(screen, location) {
            val a = audio ?: return@LaunchedEffect
            if (screen == Screen.LIVE) a.place(location ?: "") else a.title()
        }
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
                            objects = sceneObjects(location)
                            screen = Screen.SETUP
                        },
                        onGather = { screen = Screen.GATHER },
                        onOptions = { panel = Panel.OPTIONS },
                        onUpdates = { updateStatus = ""; panel = Panel.UPDATE },
                    )
                    Screen.GATHER -> GatherHost(
                        listOf(
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
                        CompositionLocalProvider(LocalNpcActions provides npcActions) { SceneSetupScreen(
                            step, stages, location, { id ->
                                if (location != id) {
                                    location = id
                                    objects = sceneObjects(id)
                                }
                            }, roster, cast,
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
                            partner = partnerFrequency,
                            onPartner = { partnerFrequency = it; settings.partnerFrequency = it.name },
                            chatter = chatterFrequency,
                            onChatter = { chatterFrequency = it; settings.chatterFrequency = it.name },
                            partnerPairs = remember(pairs, availableClips) {
                                pairs.count { it.enabled } + animationChoices(availableClips).count { PartnerPair.of(it) != null }
                            },
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
                        ) }
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
                                    savedIds = savedIds + npc.id
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
                        availableClips,
                        preview = { clip ->
                            val actor = chosen.firstOrNull() ?: starters.firstOrNull()
                            val animationCatalog = catalog
                            if (actor != null && animationCatalog != null) {
                                if (wardrobe == null && actor.appearance.starterClothing && actor.appearance.gear) {
                                    Text(wardrobeStatus ?: "Loading starter clothing…", style = frizStyle(14.sp))
                                } else {
                                    val appearance = wardrobe?.equipStarter(actor.appearance) ?: actor.appearance
                                    AnimationViewport(sceneView(), dresser, animationCatalog, appearance, actor.name, clip)
                                }
                            } else {
                                Text("Choose an NPC to preview animations.", style = frizStyle(14.sp))
                            }
                        },
                        onImport = { screen = Screen.IMPORT },
                        onImportWickedWhims = { screen = Screen.IMPORT_WICKED_WHIMS },
                        onBack = { screen = Screen.SETUP },
                        pairs = pairs,
                        onManage = { screen = Screen.LIBRARY },
                    )
                    Screen.LIBRARY -> AnimLibraryScreen(animationLibrary, onImport = { screen = Screen.IMPORT }, onBack = { screen = Screen.ANIMATIONS })
                    Screen.IMPORT, Screen.IMPORT_WICKED_WHIMS -> AnimImportScreen(
                        animationLibrary, simsRigs,
                        retargeter = { rig -> Retargeter(simsProfile, rig) },
                        onBack = { screen = Screen.ANIMATIONS },
                        onLibrary = { screen = Screen.LIBRARY },
                        wickedWhims = screen == Screen.IMPORT_WICKED_WHIMS,
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
                        val toLocal: (Vec3) -> Vec3 = { point ->
                            val delta = point - origin
                            if (embassy) Vec3(-delta.y, delta.x, delta.z) else delta
                        }
                        val view = sceneView()
                        val c = checkNotNull(catalog)
                        val session = remember { UUID.randomUUID().toString() }
                        val actorKey: (String) -> String = { "$session:npc:$it" }
                        var objectHits by remember(session) { mutableStateOf(emptyMap<String, SceneObject>()) }
                        var locationResult by remember(session) { mutableStateOf<Boolean?>(null) }
                        var loadedActors by remember(session) { mutableIntStateOf(0) }
                        var contactError by remember(session) { mutableStateOf<String?>(null) }
                        var spots by remember(session) { mutableStateOf(emptyMap<SceneObject, InteractionSpot>()) }
                        DisposableEffect(session) {
                            var active = true
                            view.removeAll()
                            view.clearProps()
                            view.showCity(false)
                            view.light(Lights.of("day"))
                            locationLoader.load(view, preset.id, "inside") { loaded ->
                                if (!active) return@load
                                locationResult = loaded
                                if (loaded) for (interaction in preset.interactions) {
                                    val item = objects.firstOrNull { it.name == interaction.item } ?: continue
                                    view.findLocationObjects(interaction.models) onFound@{ keys ->
                                        if (!active) return@onFound
                                        if (keys.isEmpty()) {
                                            contactError = "${item.label} couldn't be found in the scene."
                                            return@onFound
                                        }
                                        val entry = toLocal(interaction.entry)
                                        spots = spots + (item to InteractionSpot(entry, toLocal(interaction.pose), interaction.facing - rotation))
                                        objectHits = objectHits + keys.associateWith { item }
                                    }
                                }
                            }
                            val actionAnimations = objects.mapTo(mutableSetOf()) { it.action.animation }
                            for (actor in chosen) {
                                val dressed = wardrobe?.equipStarter(actor.appearance) ?: actor.appearance
                                dresser.dress(view, c, actorKey(actor.id), dressed, actionAnimations) {
                                    if (active) loadedActors++
                                }
                            }
                            onDispose {
                                active = false
                                locationLoader.cancel()
                                for (actor in chosen) dresser.cancel(actorKey(actor.id))
                                view.select(null)
                                view.frame = null
                                view.removeAll()
                                view.clearProps()
                                view.clearLocation()
                            }
                        }
                        // the body parts' switch per NPC (bodyparts/PartStates): an NPC wearing them (Dresser.withParts) starts at
                        // DEFAULT; the controlled NPC's On / Off (LocalPartControl) shows the ON part
                        val partKeys by dresser.withParts.collectAsStateWithLifecycle()
                        var partStates by remember(session) { mutableStateOf(PartStates()) }
                        LaunchedEffect(partKeys, chosen) {
                            partStates = chosen.fold(partStates) { states, actor ->
                                if (actorKey(actor.id) in partKeys) states.register(actor.id) else states.remove(actor.id)
                            }
                        }
                        val partControl = remember(session) {
                            object : PartControl {
                                override fun has(npcId: String): Boolean = partStates.has(npcId)
                                override fun isOn(npcId: String): Boolean = partStates.isOn(npcId)
                                override fun toggle(npcId: String) {
                                    if (!partStates.has(npcId)) return
                                    partStates = partStates.toggle(npcId)
                                    dresser.showParts(view, actorKey(npcId), partStates.state(npcId))
                                }
                            }
                        }
                        // The loaders' ticks (each file) are read only here, through the derived status: it changes a few
                        // times a second at most, and only the live screen's host is redrawn for it.
                        val stage = locationLoader.progress.collectThrottled { old, new -> old?.id != new.id || new.error != old?.error }
                        val status = remember(session, preset.id, chosen.size) {
                            derivedStateOf {
                                val st = stage.value; val fe = fetchingState.value
                                when {
                                    locationResult != null && st.id == preset.id && st.error != null -> st.error
                                    locationResult != true -> "Opening ${preset.name}…"
                                    fe.total > 0 -> "Loading NPCs… ${fe.done}/${fe.total}"
                                    fe.modelSets > 0 -> "Preparing a named character's partial look…"
                                    loadedActors < chosen.size -> "Preparing NPCs… $loadedActors/${chosen.size}"
                                    fe.error != null -> fe.error
                                    else -> contactError
                                }
                            }
                        }
                        // one life simulation and one world clock for the scene, built for its cast with the cast's saved life and
                        // time put back (a new cast's day starts at Options' start hour); the screen drives them from here on
                        val lifeIds = remember(chosen) { chosen.map { it.id } }
                        val lifeStore = remember(lifeIds) { lifeFiles(lifeIds) }
                        val lifeSim = remember(session, lifeIds) {
                            LifeSim(lifeIds, chosen.associate { it.id to LifeProfile(it.name, it.appearance.raceId, it.appearance.sex == Sex.FEMALE) },
                                seed = System.nanoTime()).also { sim ->
                                sim.enabled = lifeOn
                                runCatching { readText(lifeStore.first)?.let { sim.load(it) } }
                            }
                        }
                        val worldClock = remember(session, lifeIds) {
                            WorldClock().also { c ->
                                val saved = runCatching { readText(lifeStore.second)?.let { JSONObject(it) } }.getOrNull()
                                if (saved != null) c.restore(saved) else c.start(clockSettings.startHour)
                                c.apply(clockSettings)
                            }
                        }
                        Collected(status) { liveStatus -> CompositionLocalProvider(LocalPartControl provides partControl) { LiveSceneScreen(
                            view, camera, preset.name, chosen, objects, spots, actorKey, objectHits, toStage, toLocal, rotation,
                            onAnimate = { id, activity -> dresser.fetchAnim(actorKey(id), activity.animation) },
                            clips = availableClips,
                            pairs = pairs,
                            partner = partnerFrequency,
                            chatter = chatterFrequency,
                            fetchAnim = { key, anim -> dresser.fetchAnim(key, anim) },
                            partnerSounds = partnerSink,
                            onEnd = { screen = Screen.TITLE },
                            onSettings = { panel = Panel.OPTIONS },
                            ready = locationResult == true && loadedActors == chosen.size,
                            status = liveStatus,
                            lifeSim = lifeSim,
                            lifeEnabled = lifeOn,
                            worldClock = worldClock,
                            clockSettings = clockSettings,
                            onTimeOfDay = { hours, perHour -> audio?.timeOfDay(hours, perHour) },
                            onLifeSave = { life, clock -> lifecycleScope.launch(Dispatchers.IO) { saveLife(lifeStore, life, clock) } },
                            icons = icons,
                        ) } }
                    }
                }
                if (screen == Screen.CREATOR && npcLoading) {
                    Text("Loading NPC preview…", Modifier.align(Alignment.TopCenter).padding(12.dp), color = Color.White)
                }
                // composed after the screens' own (the creator's): the last composed wins, so Back closes a panel first
                BackHandler(panel != Panel.NONE || screen == Screen.GATHER) {
                    if (panel != Panel.NONE) panel = Panel.NONE else screen = Screen.TITLE
                }
                when (panel) {
                    Panel.NONE, Panel.UPDATE -> Unit
                    Panel.OPTIONS -> Collected(downloadProgress.collectThrottled { old, new -> old?.pack != new?.pack }) { shownProgress -> DownloadOptions(
                        progress = shownProgress,
                        status = downloadStatus,
                        speed = speed,
                        onSpeed = {
                            speed = it
                            settings.downloadSpeed = it
                            files.setDownloadSpeed(it)
                        },
                        mix = mixShown,
                        onMix = {
                            mixShown = it
                            applyMix(it)
                            mixSettings.save(it)
                        },
                        downloading = downloadJob?.isActive == true,
                        canDownload = catalog != null,
                        onDownload = { packs ->
                            if (downloadJob?.isActive != true) {
                                downloadProgress.value = AssetDownloads.Progress(packs.first(), 1, packs.size)
                                downloadStatus = null
                                downloadJob = scope.launch {
                                    try {
                                        downloadStatus = AssetDownloads(files, tables, namedCast).download(packs, stages, catalog, ::loadWardrobe) { value -> downloadProgress.value = value }
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
                        clock = clockSettings,
                        onClock = { clockSettings = it; settings.clock = it },
                        lifeSimulation = lifeOn,
                        onLifeSimulation = { lifeOn = it; settings.lifeSimulation = it },
                        mods = {
                            Column {
                                ImportsSection(imports, listfile)
                                PartImportSection(partsRoot, partSet, profileJson = { assetText("sims_profile.json").orEmpty() }, onChanged = { set ->
                                    partSet = set
                                    settings.partSet = set
                                    dresser.partSet = set
                                })
                            }
                        },
                        sound = {
                            sceneAudio?.let { sa ->
                                PartnerSoundsSection(sa.partner, partnerOn,
                                    onOn = { partnerOn = it; settings.partnerSoundsOn = it; sa.partnerOn = it; if (!it) sa.stopAll() },
                                    volume = partnerVolume,
                                    onVolume = { partnerVolume = it; settings.partnerSoundVolume = it; sa.partnerVolume = it / 100f })
                            }
                        },
                    ) }
                }
                // always composed: it fades out when closed (MenuMotionUi)
                Overlay({ panel = Panel.NONE }, visible = panel == Panel.UPDATE) {
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
                // Back closes the Delete question, then the NPC's menu (composed after the panels' own: it is asked first)
                BackHandler(npcMenu != null || npcDelete != null) { if (npcDelete != null) npcDelete = null else npcMenu = null }
                val menuActor = roster.firstOrNull { it.id == npcMenu }
                OverlayFor(menuActor, { npcMenu = null }) { actor ->
                    val character = namedCast.character(actor.id.removePrefix(NamedCast.PREFIX)).takeIf { NamedCast.isNamed(actor.id) }
                    val canDelete = NpcRosterFile.canDelete(actor.id, savedIds)
                    NpcMenuWindow(
                        actor,
                        variants = character?.let { c -> namedCast.choices(c).map { it.key to it.label } }.orEmpty(),
                        variant = actor.appearance.named?.variant,
                        onVariant = { key -> saveLooks(npcLooks.withVariant(actor.id, key)) },
                        parts = if (PartChoice.applies(actor.appearance)) actor.appearance.parts ?: PartOptions() else null,
                        partsImported = partSet != null,
                        onParts = { options ->
                            if (actor.id in savedIds) {
                                val changed = actor.copy(appearance = actor.appearance.copy(parts = options))
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { updateNpc(changed) } }
                                        .onSuccess { savedRoster = savedRoster.map { if (it.id == changed.id) changed else it } }
                                        .onFailure { problem = "The NPC could not be saved." }
                                }
                            } else {
                                saveLooks(npcLooks.withParts(actor.id, options))
                            }
                        },
                        canDelete = canDelete,
                        onDelete = { npcDelete = actor },
                        onClose = { npcMenu = null },
                    )
                }
                OverlayFor(npcDelete, { npcDelete = null }) { actor ->
                    NpcDeleteWindow(actor.name, onCancel = { npcDelete = null }, onDelete = {
                        if (npcDelete != null) {
                            npcDelete = null
                            npcMenu = null
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { deleteNpc(actor.id) } }
                                    .onSuccess {
                                        savedRoster = savedRoster.filter { it.id != actor.id }
                                        savedIds = savedIds - actor.id
                                        cast = NpcRosterFile.castWithout(cast, actor.id)
                                    }
                                    .onFailure { problem = "The NPC could not be deleted." }
                            }
                        }
                    })
                }
                OverlayFor(problem, { problem = null }) { message ->
                    WifeMessage("Wifecraft", message) { problem = null }
                }
            }
        }
    }

    /** The first launch's gathering screen: the only reader of the download's ticks (4 a second at most: GameFiles). */
    @Composable
    private fun GatherHost(
        packLabels: List<Pair<String, String>>, wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit,
        onStart: () -> Unit, onPause: () -> Unit, onBack: () -> Unit,
    ) {
        val progress by files.progress.collectAsStateWithLifecycle()
        GatherScreen(progress, packLabels, wifiOnly, onWifiOnly = onWifiOnly, onStart = onStart, onPause = onPause, onBack = onBack)
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
                // one post per percent, not per chunk read (thousands, each redrawing the panel)
                var shown = -1L
                val apk = Updater.download(this, u, { done, total ->
                    val percent = if (total > 0) done * 100 / total else -1L
                    if (percent >= 0 && percent != shown) {
                        shown = percent
                        runOnUiThread { status("Downloading ${u.title}… $percent%") }
                    }
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

/** Shows [state]'s value in [content], its own restart scope: a change redraws [content], not the composable that holds it. */
@Composable
private fun <T> Collected(state: State<T>, content: @Composable (T) -> Unit) {
    content(state.value)
}
