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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.data.Emotes
import com.logie.wifecraft.data.Lights
import com.logie.wifecraft.editor.Beat
import com.logie.wifecraft.editor.Compile
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.SceneView

/**
 * The scene builder: the stage (a place and a light) with the cast standing on it as the selected
 * beat leaves them, and the beats in order, to add to, edit, reorder and delete. Play compiles the
 * project to a script that starts at this scene and hands it to [onPlay].
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

    val place = places.firstOrNull { it.id == sc.stage.place } ?: PlaceInfo.STUDIO
    val sel = selected?.takeIf { it in sc.beats.indices }
    val pre = Staging.preview(sc, sel?.plus(1))

    // The cast is put on the renderer's stage (again when a look changes).
    LaunchedEffect(project.cast) {
        view.removeAll()
        for (a in project.cast) dress(a.id, a.appearance)
    }
    // The stage's light, place and camera, and who stands where, as the selected beat leaves them.
    LaunchedEffect(sc.stage, pre, project.cast) {
        view.light(Lights.of(sc.stage.light))
        view.showCity(!place.void)
        if (!place.void && !cityAsked && !view.cityLoaded.get()) {
            cityAsked = true
            val m = Staging.allMarks(places)
            view.loadCity(m.flatMap { listOf(it.x, it.y, it.z) }.toFloatArray()) {}
        }
        val away = Placement(0f, 0f, -1000f, 0f, false)
        view.frame = Staging.pose(place, pre) to project.cast.associate { a ->
            a.id to (pre.shown.firstOrNull { it.actor == a.id }?.let {
                val s = Staging.standing(place, it.spot)
                Placement(s.pos.x, s.pos.y, s.pos.z, s.facing)
            } ?: away)
        }
    }

    fun play() {
        try {
            onPlay(Compile.toScript(project.copy(start = sceneId)))
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        }
    }

    EditorFrame(
        project.title + ": " + sc.name, onBack,
        actions = {
            SmallButton("Rename", { renaming = true }, width = 104.dp)
            SmallButton("Check", { report = state.problems() }, width = 90.dp)
            WowButton("Play", { val p = state.problems(); if (p.isEmpty()) play() else report = p }, width = 110.dp, height = 40.dp)
        },
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).border(1.dp, Color(0x66FFD100))) {
                    AndroidView({ (view.parent as? ViewGroup)?.removeView(view); view }, Modifier.fillMaxSize())
                }
                PickRow("Place", places.map { it.id to it.name }, sc.stage.place, {
                    state.setStage(sceneId, it, sc.stage.light, places.firstOrNull { p -> p.id == it }?.name ?: it)
                })
                PickRow("Light", EditorChoices.lights.map { it to it.replaceFirstChar(Char::uppercase) }, sc.stage.light, {
                    state.setStage(sceneId, sc.stage.place, it, sc.stage.name)
                })
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
                                    describe(b, state::nameOf), style = frizStyle(14.sp, Color.White, false),
                                    maxLines = if (sel == i) 4 else 2, modifier = Modifier.weight(1f),
                                )
                            }
                            if (sel == i) BeatEditor(b, project, sc.id, place, state, i) { selected = it }
                        }
                    }
                }
                WowButton("Add beat", { adding = true }, Modifier.align(Alignment.CenterHorizontally), width = 220.dp, height = 40.dp)
            }
        }
    }

    if (adding) PickDialog("Add a beat", BeatKind.values().map { it.name to it.label }, null, { adding = false }) { name ->
        adding = false
        val at = state.addBeat(sceneId, state.newBeat(BeatKind.valueOf(name), sceneId), sel?.plus(1))
        selected = at.takeIf { it >= 0 }
    }
    if (renaming) NameDialog("Rename scene", sc.name, "Rename", { renaming = false }) { renaming = false; state.renameScene(sceneId, it) }
    report?.let { problems ->
        Overlay({ report = null }) {
            MetalPanel(Modifier.width(560.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (problems.isEmpty()) "Nothing is wrong." else plural(problems.size, "thing") + " to look at", style = frizStyle(18.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        itemsIndexed(problems) { _, p -> Text(p, style = frizStyle(14.sp, Color.White, false), modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WowButton("Close", { report = null }, width = 150.dp)
                        WowButton("Play anyway", { report = null; play() }, width = 190.dp)
                    }
                }
            }
        }
    }
    failure?.let { msg ->
        Overlay({ failure = null }) {
            MetalPanel(Modifier.width(520.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("It can't be played", style = frizStyle(18.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(msg, style = frizStyle(14.sp, Color.White, false))
                    WowButton("Close", { failure = null }, Modifier.align(Alignment.CenterHorizontally), width = 150.dp)
                }
            }
        }
    }
}

/** The selected beat's fields, and the buttons that move, copy and delete it. */
@Composable
private fun BeatEditor(
    beat: Beat,
    project: Project,
    sceneId: String,
    place: PlaceInfo,
    state: EditorState,
    index: Int,
    onSelect: (Int?) -> Unit,
) {
    val cast = project.cast.map { it.id to it.name }
    val scenes = project.scenes.map { it.id to it.name }
    fun put(b: Beat) = state.replaceBeat(sceneId, index, b)
    fun shots(shown: String? = null) = EditorChoices.shots(cast, place.views.keys, shown)

    Column(
        Modifier.fillMaxWidth().background(Color(0x44000000)).border(1.dp, Color(0x44FFD100)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (cast.isEmpty() && (beat is Beat.Show || beat is Beat.Hide)) {
            Text("Make an NPC in the cast first.", style = frizStyle(13.sp, WowColors.grey, false))
        }
        when (beat) {
            is Beat.Say -> {
                PickRow("Who", listOf("" to "Narration") + cast, beat.who ?: "", { put(beat.copy(who = it.ifEmpty { null })) })
                PickRow("Emote", Emotes.byName.keys.map { it to it }, beat.emote, { put(beat.copy(emote = it)) })
                EditorField(beat.text, { put(beat.copy(text = it)) }, "What is said", singleLine = false)
            }
            is Beat.Show -> {
                PickRow("Who", cast, beat.who, { put(beat.copy(who = it)) })
                PickRow("Where", EditorChoices.spots.map { it to it }, beat.spot, { put(beat.copy(spot = it)) })
                PickRow("Shot", shots(beat.who), beat.shot, { put(beat.copy(shot = it)) })
            }
            is Beat.Hide -> PickRow("Who", cast, beat.who, { put(beat.copy(who = it)) })
            is Beat.Camera -> PickRow("Shot", shots(), beat.shot, { put(beat.copy(shot = it)) })
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
