package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.data.Emotes
import com.logie.wifecraft.data.Lights
import com.logie.wifecraft.editor.Beat
import com.logie.wifecraft.editor.Compile
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.editor.Scene
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.SceneView
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The scene builder: the stage (the studio, a place of Dalaran or a location's view: the Stormwind Embassy inside or
 * outside) in a light, with what the placement editor put there and the cast standing on it as the selected beat leaves
 * them, and the beats in order, to add to, edit, reorder and delete. Place things opens the placement editor
 * ([EditorPlacement]) on the same stage. Play compiles the project to a script that starts at this scene and hands it to
 * [onPlay].
 */
@Composable
fun EditorSceneBuilder(
    project: Project,
    state: EditorState,
    sceneId: String,
    places: List<PlaceInfo>,
    view: SceneView,
    dress: (key: String, appearance: Appearance) -> Unit,
    onPlay: (script: String) -> Unit,
    onBack: () -> Unit,
    /** The imported animations (Import Animations): what a Pose beat and a Say's emote picker list. */
    animLibrary: AnimLibrary? = null,
    /** The placement editor's engine (MainActivity's): without it the scene has no locations and no placing. */
    engine: PlaceEngine? = null,
) {
    val sc = project.scenes.firstOrNull { it.id == sceneId }
    if (sc == null) {
        EditorFrame("Scene", onBack) { Text("This scene is gone.", style = frizStyle(16.sp, Color.White)) }
        return
    }
    var selected by remember(sceneId) { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var cityAsked by remember { mutableStateOf(false) }

    val anims = remember(sceneId) { runCatching { animLibrary?.list() }.getOrNull().orEmpty() }
    val locations = engine?.locations.orEmpty()
    val stageId = Compile.stageOf(sc)
    val place = stagePlace(stageId, places, locations)
    val stageName = StageChoices.name(stageId, places, locations)
    val sel = selected?.takeIf { it in sc.beats.indices }
    val pre = Staging.preview(sc, sel?.plus(1))
    val status by (engine?.status ?: noStatus).collectAsState()

    // the placement editor's state, kept while this scene is open
    var placing by remember(sceneId) { mutableStateOf(false) }
    val home = remember(stageId) { OrbitCamera.from(place.views.values.firstOrNull() ?: Staging.pose(place, Preview(emptyList(), listOf("wide")))) }
    var orbit by remember(sceneId, stageId) { mutableStateOf(home) }
    var placeUi by remember(sceneId) { mutableStateOf(PlaceUi()) }
    var live by remember(sceneId) { mutableStateOf<com.logie.wifecraft.editor.Placement?>(null) }
    val placements = sc.placements.map { p -> live?.takeIf { it.id == p.id } ?: p }
    LaunchedEffect(sc.placements.map { it.id }) { placeUi = placeUi.keepOnly(sc.placements.map { it.id }) }

    // The cast is put on the renderer's stage (again when a look changes); the placed things go with them and come back.
    LaunchedEffect(project.cast) {
        view.removeAll()
        engine?.reset()
        for (a in project.cast) dress(a.id, a.appearance)
    }
    // The stage: its light, the city (a place of Dalaran) or a location (loaded with its files: the overlay shows them).
    LaunchedEffect(stageId, sc.stage.light) {
        view.light(Lights.of(sc.stage.light))
        view.showCity(!place.void)
        if (!place.void && !cityAsked && !view.cityLoaded.get()) {
            cityAsked = true
            val m = Staging.allMarks(places)
            view.loadCity(m.flatMap { listOf(it.x, it.y, it.z) }.toFloatArray()) {}
        }
        engine?.enterStage(stageId, sc.stage.light)
    }
    // The placed things on the renderer, as the scene has them (the one being moved as it is now).
    val notDrawn = placeUi.notDrawn(placements)
    LaunchedEffect(placements, project.cast, notDrawn) { engine?.sync(placements, project.cast, notDrawn) }
    // The camera, who stands where as the selected beat leaves them, and the placed people and creatures.
    val away = Placement(0f, 0f, -1000f, 0f, false)
    SideEffect {
        val cam = if (placing) orbit.pose() else Staging.pose(place, pre)
        val people = project.cast.associate { a ->
            a.id to (pre.shown.firstOrNull { it.actor == a.id }?.let {
                val s = Staging.standing(place, it.spot)
                Placement(s.pos.x, s.pos.y, s.pos.z, s.facing)
            } ?: away)
        } + placements.filter { it.kind != PlaceKind.PROP }.associate { p ->
            PlaceKeys.of(p.id) to Placement(p.x, p.y, p.z, p.yaw, placeUi.drawn(p))
        }
        view.frame = cam to people
    }
    // the selected placed thing is outlined
    LaunchedEffect(placing, placeUi.selected) { view.select(if (placing) placeUi.selected?.let(PlaceKeys::of) else null) }

    val stages = StageChoices.list(places, locations)
    fun pickStage(id: String) = state.setStageId(sceneId, id, stages.firstOrNull { it.first == id }?.second ?: id)

    fun play() {
        try {
            onPlay(Compile.toScript(project.copy(start = sceneId)))
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        }
    }

    if (placing && engine != null) {
        EditorPlacement(
            project, state, sceneId, view, engine, stageName,
            camera = orbit, onCamera = { orbit = it }, onResetCamera = { orbit = home },
            ui = placeUi, onUi = { placeUi = it }, live = live, onLive = { live = it },
            onDone = { live = null; placing = false },
            stages = stages, stageId = stageId, onStage = { pickStage(it) },
            // Play from the placement editor: what would stop it is shown on the scene builder's page
            onPlay = { val p = state.problems(); if (p.isEmpty()) play() else { live = null; placing = false; report = p } },
        )
        failure?.let { msg -> ConfirmDialog("It can't be played: " + msg, "Close", { failure = null }) { failure = null } }
        return
    }

    // Who holds which imported pose as the selected beat leaves them (a pose is drawn when the actor is on stage).
    val held = Staging.poses(sc, sel?.plus(1))
    LaunchedEffect(held, project.cast, anims) {
        for (a in project.cast) {
            val anim = held[a.id]?.let { id -> anims.firstOrNull { it.id == id } }
            if (anim != null) view.playAnim(a.id, anim) else view.stopAnim(a.id)
        }
    }

    var stagePicker by remember { mutableStateOf(false) }

    EditorFrame(
        project.title + ": " + sc.name, onBack,
        actions = {
            RoundIconButton(Face(icon = "menu.scene"), { renaming = true }, description = "Rename")
            RoundIconButton(Face(glyph = WarIcons.CHECK), { report = state.problems() }, description = "Check")
            RoundIconButton(Face(glyph = WarIcons.PLAY), { val p = state.problems(); if (p.isEmpty()) play() else report = p }, primary = true, size = 64.dp, description = "Play")
        },
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(WarSize.R_CARD.dp)).background(Color.Black)
                    .border(1.5.dp, War.goldSoft, androidx.compose.foundation.shape.RoundedCornerShape(WarSize.R_CARD.dp))) {
                    AndroidView({ (view.parent as? ViewGroup)?.removeView(view); view }, Modifier.fillMaxSize())
                    if (engine != null) PlaceLoading(status, engine::clearErrors, Modifier.align(Alignment.TopCenter))
                }
                // the stage: a row that opens the stage picker (the studio, Dalaran's places, every location's views)
                EditorCard(onClick = { stagePicker = true }) {
                    RoundIconButton(Face(icon = if (StageChoices.locationOf(stageId) != null) "menu.location" else "tool.stage"), { stagePicker = true }, size = 48.dp, description = "Stage")
                    Column(Modifier.weight(1f)) {
                        Text("Stage", style = frizStyle(12.sp, WowColors.grey, false))
                        Text(stageName, style = frizStyle(16.sp, War.cream, false), maxLines = 1)
                    }
                }
                PickRow("Light", EditorChoices.lights.map { it to it.replaceFirstChar(Char::uppercase) }, sc.stage.light, {
                    state.setStage(sceneId, sc.stage.place, it, sc.stage.name)
                    // setStage puts the place in the stage id too: a location keeps its own
                    if (stageId != sc.stage.place) state.setStageId(sceneId, stageId, stageName)
                })
                PillButton(
                    if (sc.placements.isEmpty()) "Place things" else "Place things (${sc.placements.size})", { placing = true },
                    Modifier.align(Alignment.CenterHorizontally).widthIn(min = 260.dp), enabled = engine != null, glyph = WarIcons.PLUS,
                )
            }
            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = rememberLazyListState()
                LaunchedEffect(sel) { sel?.let { list.animateScrollToItem(it) } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sc.beats.isEmpty()) item {
                        Text("Nothing happens yet. Add a beat.", style = frizStyle(15.sp, WowColors.grey), modifier = Modifier.padding(8.dp))
                    }
                    itemsIndexed(sc.beats) { i, b ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            EditorCard(onClick = { selected = if (sel == i) null else i }, selected = sel == i) {
                                Text("${i + 1}", style = frizStyle(13.sp, WowColors.grey), modifier = Modifier.width(24.dp))
                                Text(
                                    describe(b, state::nameOf, { id -> anims.firstOrNull { it.id == id }?.name ?: id }, { id -> sc.placements.firstOrNull { it.id == id }?.name ?: id }),
                                    style = frizStyle(14.sp, Color.White, false),
                                    maxLines = if (sel == i) 4 else 2, modifier = Modifier.weight(1f),
                                )
                            }
                            if (sel == i) BeatEditor(b, project, sc, place, state, i, anims, view, engine) { selected = it }
                        }
                    }
                }
                PillButton("Add beat", { adding = true }, Modifier.align(Alignment.CenterHorizontally).widthIn(min = 220.dp), glyph = WarIcons.PLUS)
            }
        }
    }

    if (adding) PickDialog("Add a beat", BeatKind.values().map { it.name to it.label }, null, { adding = false }) { name ->
        adding = false
        val at = state.addBeat(sceneId, state.newBeat(BeatKind.valueOf(name), sceneId), sel?.plus(1))
        selected = at.takeIf { it >= 0 }
    }
    if (stagePicker) StagePicker(stages, stageId, { stagePicker = false }) { id -> stagePicker = false; pickStage(id) }
    if (renaming) NameDialog("Rename scene", sc.name, "Rename", { renaming = false }) { renaming = false; state.renameScene(sceneId, it) }
    report?.let { problems ->
        WarDialog({ report = null }) {
            Text(if (problems.isEmpty()) "Nothing is wrong." else plural(problems.size, "thing") + " to look at", style = warTitle(20.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                itemsIndexed(problems) { _, p -> Text(p, style = warText(14.sp), modifier = Modifier.padding(vertical = 2.dp)) }
            }
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Close", { report = null }, Modifier.widthIn(min = 140.dp))
                PillButton("Play anyway", { report = null; play() }, Modifier.widthIn(min = 170.dp), glyph = WarIcons.PLAY)
            }
        }
    }
    failure?.let { msg ->
        WarDialog({ failure = null }) {
            Text("It can't be played", style = warTitle(20.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(msg, style = warText(14.sp))
            PillButton("Close", { failure = null }, Modifier.align(Alignment.CenterHorizontally).widthIn(min = 140.dp))
        }
    }
}

/** The selected beat's fields, and the buttons that move, copy and delete it. */
@Composable
private fun BeatEditor(
    beat: Beat,
    project: Project,
    scene: Scene,
    place: PlaceInfo,
    state: EditorState,
    index: Int,
    anims: List<com.logie.wifecraft.anim.Anim>,
    view: SceneView,
    engine: PlaceEngine?,
    onSelect: (Int?) -> Unit,
) {
    val sceneId = scene.id
    val placed = scene.placements.filter { it.kind != PlaceKind.PROP }
    val cast = project.cast.map { it.id to it.name }
    val scenes = project.scenes.map { it.id to it.name }
    fun put(b: Beat) = state.replaceBeat(sceneId, index, b)
    fun shots(shown: String? = null) = EditorChoices.shots(cast, place.views.keys, shown)

    Column(
        Modifier.fillMaxWidth().background(Color(0x44000000)).border(1.dp, Color(0x44FFD100)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (cast.isEmpty() && (beat is Beat.Show || beat is Beat.Hide || beat is Beat.Pose)) {
            Text("Make an NPC in the cast first.", style = frizStyle(13.sp, WowColors.grey, false))
        }
        when (beat) {
            is Beat.Say -> {
                PickRow("Who", listOf("" to "Narration") + cast, beat.who ?: "", { put(beat.copy(who = it.ifEmpty { null })) })
                PickRow("Emote", Emotes.byName.keys.map { it to it } + PoseChoices.emotes(anims), beat.emote, { put(beat.copy(emote = it)) })
                EditorField(beat.text, { put(beat.copy(text = it)) }, "What is said", singleLine = false)
            }
            is Beat.Show -> {
                PickRow("Who", cast, beat.who, { put(beat.copy(who = it)) })
                PickRow("Where", EditorChoices.spots.map { it to it }, beat.spot, { put(beat.copy(spot = it)) })
                PickRow("Shot", shots(beat.who), beat.shot, { put(beat.copy(shot = it)) })
            }
            is Beat.Hide -> PickRow("Who", cast, beat.who, { put(beat.copy(who = it)) })
            is Beat.Camera -> PickRow("Shot", shots(), beat.shot, { put(beat.copy(shot = it)) })
            is Beat.Pose -> {
                PickRow("Who", cast, beat.who.ifEmpty { null }, { put(beat.copy(who = it)) })
                if (anims.isEmpty()) {
                    Text("No animations yet: Import Animations on the title screen.", style = frizStyle(13.sp, WowColors.grey, false))
                } else {
                    PickRow("Animation", PoseChoices.items(anims), beat.animId.ifEmpty { null }, { put(beat.copy(animId = it)) })
                }
                if (beat.animId.isNotBlank() && anims.none { it.id == beat.animId }) {
                    Text("This animation is not in the library any more.", style = frizStyle(13.sp, Color(0xFFFF6A5A), false))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val a = anims.firstOrNull { it.id == beat.animId }
                    SmallButton("Preview", { if (a != null) view.playAnim(beat.who, a) }, enabled = a != null && beat.who.isNotEmpty(), width = 104.dp)
                    SmallButton("Stop", { view.stopAnim(beat.who) }, enabled = beat.who.isNotEmpty(), width = 84.dp)
                }
            }
            is Beat.Choice -> {
                EditorField(beat.prompt, { put(beat.copy(prompt = it)) }, "The question")
                beat.options.forEachIndexed { n, o ->
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EditorField(o.text, { t -> put(beat.copy(options = beat.options.mapIndexed { k, x -> if (k == n) x.copy(text = t) else x })) }, "Option ${n + 1}")
                        PickRow("Goes to", scenes, o.goto.ifEmpty { null }, { g -> put(beat.copy(options = beat.options.mapIndexed { k, x -> if (k == n) x.copy(goto = g) else x })) })
                        if (beat.options.size > 1) SmallButton("Remove option", { put(beat.copy(options = beat.options.filterIndexed { k, _ -> k != n })) }, width = 170.dp)
                    }
                }
                SmallButton("Add option", { put(beat.copy(options = beat.options + Beat.Choice.Option("", scenes.firstOrNull()?.first ?: ""))) }, width = 170.dp)
            }
            is Beat.Emote, is Beat.Anim -> {
                val pid = if (beat is Beat.Emote) beat.placementId else (beat as Beat.Anim).placementId
                if (placed.isEmpty()) Text("Place a person or a creature in this scene first (Place things).", style = frizStyle(13.sp, WowColors.grey, false))
                PickRow("Who", placed.map { it.id to it.name }, pid.ifEmpty { null }, { id ->
                    put(if (beat is Beat.Emote) beat.copy(placementId = id) else (beat as Beat.Anim).copy(placementId = id))
                })
                val p = placed.firstOrNull { it.id == pid }
                val emotes = remember(p?.kind, p?.asset, p?.actorId, project.cast) { if (p == null || engine == null) emptyList() else engine.emotesFor(p, project.cast) }
                if (beat is Beat.Emote) {
                    PickRow("Emote", emotes.map { it.id.toString() to "${it.label} (${it.category})" }, beat.emoteId.toString(), { put(beat.copy(emoteId = it.toInt())) })
                    val e = emotes.firstOrNull { it.id == beat.emoteId }
                    SmallButton("Preview", { if (p != null && e != null) engine?.previewEmote(p, e, project.cast) }, enabled = p != null && e != null, width = 120.dp)
                } else if (beat is Beat.Anim) {
                    val byAnim = emotes.filter { it.anim >= 0 }.distinctBy { it.anim }
                    PickRow("Animation", byAnim.map { it.anim.toString() to "${it.label} (${it.anim})" }, beat.anim.toString(), { put(beat.copy(anim = it.toInt())) })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PToggle("Loops", beat.loop, { put(beat.copy(loop = it)) }, width = 110.dp)
                        val e = byAnim.firstOrNull { it.anim == beat.anim }
                        SmallButton("Preview", { if (p != null && e != null) engine?.previewEmote(p, e.copy(loop = beat.loop), project.cast) }, enabled = p != null && e != null, width = 120.dp)
                    }
                }
            }
            is Beat.Goto -> PickRow("Scene", scenes, beat.scene.ifEmpty { null }, { put(beat.copy(scene = it)) })
            is Beat.End -> EditorField(beat.title, { put(beat.copy(title = it)) }, "The ending's title")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val last = (state.sceneOf(sceneId)?.beats?.size ?: 1) - 1
            SmallButton("Up", { onSelect(state.moveBeat(sceneId, index, -1)) }, enabled = index > 0, width = 64.dp)
            SmallButton("Down", { onSelect(state.moveBeat(sceneId, index, 1)) }, enabled = index < last, width = 74.dp)
            SmallButton("Copy", { onSelect(state.duplicateBeat(sceneId, index)) }, width = 74.dp)
            SmallButton("Delete", { state.removeBeat(sceneId, index); onSelect(null) }, width = 86.dp)
        }
    }
}

private val noStatus = MutableStateFlow(PlaceStatus())

/**
 * The preview's place for a stage: a place of places.json, the studio, or for a location's view the plain stage's marks
 * (where the story stands the cast, as the player does) with the view's own camera, nothing of Dalaran drawn.
 */
fun stagePlace(stageId: String, places: List<PlaceInfo>, locations: List<com.logie.wifecraft.location.LocationInfo>): PlaceInfo {
    val loc = StageChoices.locationOf(stageId)
    val studio = places.firstOrNull { it.id == EditorChoices.STUDIO } ?: PlaceInfo.STUDIO
    if (loc != null) {
        val l = locations.firstOrNull { it.id == loc.first } ?: return studio
        val cam = StageChoices.camera(l, loc.second) ?: return studio
        return studio.copy(id = stageId, name = l.name, views = mapOf(loc.second to cam), void = true)
    }
    return places.firstOrNull { it.id == stageId } ?: studio
}
