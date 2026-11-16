package com.logie.wifecraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.data.Lights
import com.logie.wifecraft.editor.Compile
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.engine.SceneView

// The story editor's screens, in WoW's own interface: metal frames, red buttons, gold words.
// EditorScreen is the one entry point; it holds which page is open (projects, cast, scenes, the
// scene builder, the character creator). Every change goes through EditorState and is saved at once.

/** Where the editor opens: the list of projects, a project (the title's Continue), or the list asking a new one's name. */
sealed interface EditorStart {
    data object Projects : EditorStart
    data object New : EditorStart
    data class Open(val project: Project) : EditorStart
}

/** The renderer's key of the character creator's actor (CreatorScreen's actorKey). */
const val CREATOR_KEY = "creator"

private sealed interface Page {
    data object Cast : Page
    data object Scenes : Page
    data class Builder(val sceneId: String) : Page
    data class Creator(val actorId: String?) : Page
}

/**
 * The editor. [dress] puts an NPC's look on the scene's actor named by that key (the host resolves
 * the appearance, fetches its files and dresses the renderer's actor; the creator's actor is
 * [CREATOR_KEY]); [onPlay] runs a compiled script (`.wfs` text) through the play path, or
 * [onPlayProject] when given (the project too: who each script name is); [onCreatorLook] is told
 * each look the creator resolves (without the imports; [dress] draws it with them); [start] is
 * where it opens; [onBack] leaves the editor for the title.
 */
@Composable
fun EditorScreen(
    api: ProjectsApi,
    catalog: Catalog,
    tables: Tables,
    art: WowArt,
    scene: SceneView,
    places: List<PlaceInfo>,
    dress: (key: String, appearance: Appearance) -> Unit,
    onCreatorLook: (ResolvedLook) -> Unit,
    onPlay: (script: String) -> Unit,
    onBack: () -> Unit,
    start: EditorStart = EditorStart.Projects,
    onPlayProject: ((project: Project, script: String) -> Unit)? = null,
    /** The imported animations: a Pose beat picks from them, and the checks name a missing one. */
    animLibrary: AnimLibrary? = null,
    /** The placement editor's engine (MainActivity's): the locations, the library, the placed things on the stage. */
    placeEngine: PlaceEngine? = null,
) {
    /**
     * The checks, with the animation library's ids when there is one (read when they run, so a deleted animation shows) and
     * every stage there is (the studio, the places, each location's views).
     */
    val stageIds = remember(places, placeEngine) { StageChoices.list(places, placeEngine?.locations.orEmpty()).map { it.first }.toSet() }
    val validator: (Project) -> List<String> = { p ->
        Compile.validate(p, animLibrary?.let { lib -> runCatching { lib.list().map { it.id }.toSet() }.getOrNull() }, stageIds)
    }
    var rev by remember { mutableIntStateOf(0) }
    var state by remember {
        mutableStateOf((start as? EditorStart.Open)?.let { s -> EditorState(s.project, validator, onChange = { api.save(it); rev++ }) })
    }
    var page by remember { mutableStateOf<Page>(Page.Cast) }
    @Suppress("UNUSED_EXPRESSION") rev   // changes redraw the open project

    val st = state
    BackHandler(enabled = st != null) {
        when (val p = page) {
            is Page.Builder -> page = Page.Scenes
            is Page.Creator -> page = Page.Cast
            Page.Scenes -> page = Page.Cast
            Page.Cast -> state = null
        }
    }
    if (st == null) {
        EditorHome(
            api, onBack, askNew = start == EditorStart.New,
            onOpen = { p ->
                state = EditorState(p, validator, onChange = { api.save(it); rev++ })
                page = Page.Cast
            },
        )
        return
    }
    val project = st.project
    when (val p = page) {
        Page.Cast -> EditorCast(
            project, catalog, st,
            onNew = { page = Page.Creator(null) },
            onEdit = { page = Page.Creator(it) },
            onScenes = { page = Page.Scenes },
            onBack = { state = null },
        )
        Page.Scenes -> EditorScenes(
            project, st, places,
            onOpen = { page = Page.Builder(it) },
            onCast = { page = Page.Cast },
            onBack = { state = null },
        )
        is Page.Builder -> EditorSceneBuilder(
            project, st, p.sceneId, places, scene, dress,
            onPlay = { script -> onPlayProject?.invoke(st.project, script) ?: onPlay(script) },
            onBack = { page = Page.Scenes },
            animLibrary = animLibrary,
            engine = placeEngine,
        )
        is Page.Creator -> {
            val actor = p.actorId?.let { st.actorOf(it) }
            val start = actor?.appearance ?: defaultAppearance(catalog)
            if (start == null) {
                EditorFrame("New NPC", { page = Page.Cast }) { Text("The game's character tables have no races.", style = frizStyle(16.sp, Color.White)) }
            } else {
                val model = remember(p.actorId) { CreatorModel(catalog, start) }
                // the creator's stage: the character alone in the void, in daylight
                LaunchedEffect(p.actorId) {
                    scene.removeAll()
                    scene.showCity(false)
                    scene.light(Lights.of("day"))
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView({ (scene.parent as? ViewGroup)?.removeView(scene); scene }, Modifier.fillMaxSize())
                    CreatorScreen(
                        model, art, scene, tables,
                        onFinish = { name, a ->
                            if (actor == null) st.addActor(name, a) else st.updateActor(actor.id, name, a)
                            scene.removeActor(CREATOR_KEY)
                            page = Page.Cast
                        },
                        onBack = { scene.removeActor(CREATOR_KEY); page = Page.Cast },
                        initialName = actor?.name ?: "",
                        actorKey = CREATOR_KEY,
                        onLook = { look -> onCreatorLook(look); dress(CREATOR_KEY, model.appearance) },
                    )
                }
            }
        }
    }
}

/** A starting look for a new NPC: the first race the creator lists, as a woman where it has one. */
fun defaultAppearance(c: Catalog): Appearance? {
    val race = c.races.firstOrNull() ?: return null
    val sex = race.sexes.firstOrNull { it.name == "FEMALE" } ?: race.sexes.firstOrNull() ?: return null
    return Appearance(race.id, sex)
}

/**
 * The projects (the build-mode look: a spellbook page over the warm dark ground): each project a parchment row with the
 * quill, its cast and scenes counted, Rename and Delete; New Project at the foot. Open one with a tap.
 */
@Composable
fun EditorHome(api: ProjectsApi, onBack: () -> Unit, onOpen: (Project) -> Unit, askNew: Boolean = false) {
    var projects by remember { mutableStateOf(api.list()) }
    var asking by remember { mutableStateOf<Ask?>(if (askNew) Ask.New else null) }
    BackHandler(onBack = onBack)
    WarScreen("Projects", onBack, actions = {
        RoundIconButton(Face(glyph = WarIcons.PLUS), { asking = Ask.New }, description = "New Project")
    }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BookHeader("Your stories", if (projects.isEmpty()) "No stories yet" else plural(projects.size, "project"))
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.weight(1f).warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects, key = { it.id }) { p ->
                    WarRow({ onOpen(p) }, Modifier.padding(end = 10.dp), icon = "menu.project") {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = warTitle(19.sp), maxLines = 1)
                            Text(plural(p.cast.size, "NPC") + " · " + plural(p.scenes.size, "scene"), style = warText(12.sp, War.brownSoft))
                        }
                        RoundIconButton(Face(icon = "menu.scene"), { asking = Ask.Rename(p) }, size = 48.dp, label = "Rename", onLight = true)
                        RoundIconButton(Face(glyph = WarIcons.DELETE), { asking = Ask.Delete(p) }, size = 48.dp, label = "Delete", onLight = true)
                    }
                }
            }
            PillButton("New Project", { asking = Ask.New }, Modifier.align(Alignment.CenterHorizontally).widthIn(min = 240.dp), glyph = WarIcons.PLUS)
        }
    }
    when (val a = asking) {
        Ask.New -> NameDialog("New Project", "", "Create", { asking = null }) { name ->
            asking = null
            onOpen(api.create(name.ifBlank { "My story" }))
            projects = api.list()
        }
        is Ask.Rename -> NameDialog("Rename", a.p.title, "Rename", { asking = null }) { name ->
            asking = null
            if (name.isNotBlank()) api.save(a.p.copy(title = name.trim()))
            projects = api.list()
        }
        is Ask.Delete -> ConfirmDialog("Delete \"${a.p.title}\" for good?", "Delete", { asking = null }) {
            asking = null
            api.delete(a.p.id)
            projects = api.list()
        }
        null -> Unit
    }
}

private sealed interface Ask {
    data object New : Ask
    data class Rename(val p: Project) : Ask
    data class Delete(val p: Project) : Ask
}

fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"

// ------------------------------------------------------------------------ the editor's pieces
// In the build-mode look (WarWidgets): the warm dark ground, rounded glass panels, wood pills with gold words, cream fields.

/** A full-screen window: Back and the title on its ribbon in the top bar, [actions] beside them, the content on warm glass. */
@Composable
fun EditorFrame(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2A1C12), Color(0xFF140D08))))) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, onBack, actions = actions)
            GlassPanel(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), corbel = false) {
                Box(Modifier.fillMaxSize().padding(8.dp)) { content() }
            }
        }
    }
}

/** A row on a list over the dark glass: warm, rounded, gold-edged, lit when [selected]. */
@Composable
fun EditorCard(onClick: () -> Unit, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(WarSize.R_CARD.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (selected) Color(0x66E3A33B) else Color(0x803A2414), shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) War.gold else Color(0x88B08850), shape)
            .warPress(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, width: androidx.compose.ui.unit.Dp = 104.dp) =
    PillButton(text, onClick, modifier.widthIn(min = width), enabled)

/** A line of text the author types, in a cream field. */
@Composable
fun EditorField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true) =
    WarField(value, onChange, hint, modifier.fillMaxWidth(), singleLine = singleLine)

/** A name to type, on a parchment page over the screen. */
@Composable
fun NameDialog(title: String, initial: String, ok: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    WarDialog(onCancel) {
        Text(title, style = warTitle(22.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
        WarField(text, { text = it.take(60) }, "Name")
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Cancel", onCancel, Modifier.widthIn(min = 140.dp))
            PillButton(ok, { onOk(text) }, Modifier.widthIn(min = 140.dp), glyph = WarIcons.CHECK)
        }
    }
}

@Composable
fun ConfirmDialog(question: String, ok: String, onCancel: () -> Unit, onOk: () -> Unit) {
    WarDialog(onCancel) {
        Text(question, style = warText(16.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Cancel", onCancel, Modifier.widthIn(min = 140.dp))
            PillButton(ok, onOk, Modifier.widthIn(min = 140.dp), danger = ok == "Delete")
        }
    }
}

/** A list of (id, label) to pick one from, on a parchment page over the screen. */
@Composable
fun PickDialog(title: String, items: List<Pair<String, String>>, current: String?, onCancel: () -> Unit, onPick: (String) -> Unit) {
    WarDialog(onCancel) {
        Text(title, style = warTitle(22.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.first }) { (id, label) ->
                WarRow({ onPick(id) }, selected = id == current) {
                    Text(label, style = warText(16.sp), maxLines = 1)
                }
            }
        }
    }
}

/** A labelled setting that shows its value and opens a [PickDialog]. */
@Composable
fun PickRow(label: String, items: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = frizStyle(14.sp), modifier = Modifier.width(86.dp))
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(WarSize.R_SMALL.dp)
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp).background(War.cream, shape).border(1.dp, War.edge, shape).warPress { open = true }.padding(12.dp),
        ) {
            Text((items.firstOrNull { it.first == current }?.second ?: current ?: "").ifEmpty { "Choose" }, style = warText(15.sp), maxLines = 1)
        }
    }
    if (open) PickDialog(label, items, current, { open = false }) { open = false; onPick(it) }
}
