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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private sealed interface Page {
    data object Cast : Page
    data object Scenes : Page
    data class Builder(val sceneId: String) : Page
    data class Creator(val actorId: String?) : Page
}

/**
 * The editor. [dress] puts an NPC's look on the scene's actor named by that key (the creator's
 * resolved look, however the renderer is asked to draw it); [onPlay] runs a compiled script
 * (`.wfs` text) through the play path; [onCreatorLook] draws the creator's look on its actor; [onBack] leaves the editor for the title.
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
) {
    var rev by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<EditorState?>(null) }
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
            api, onBack,
            onOpen = { p ->
                state = EditorState(p, onChange = { api.save(it); rev++ })
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
            project, st, p.sceneId, places, scene, dress, onPlay,
            onBack = { page = Page.Scenes },
        )
        is Page.Creator -> {
            val actor = p.actorId?.let { st.actorOf(it) }
            val start = actor?.appearance ?: defaultAppearance(catalog)
            if (start == null) {
                EditorFrame("New NPC", { page = Page.Cast }) { Text("The game's character tables have no races.", style = frizStyle(16.sp, Color.White)) }
            } else {
                val model = remember(p.actorId) { CreatorModel(catalog, start) }
                CreatorScreen(
                    model, art, scene, tables,
                    onFinish = { name, a ->
                        if (actor == null) st.addActor(name, a) else st.updateActor(actor.id, name, a)
                        page = Page.Cast
                    },
                    onBack = { page = Page.Cast },
                    initialName = actor?.name ?: "",
                    onLook = onCreatorLook,
                )
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

/** The projects: open one, make one, rename or delete one. */
@Composable
fun EditorHome(api: ProjectsApi, onBack: () -> Unit, onOpen: (Project) -> Unit) {
    var projects by remember { mutableStateOf(api.list()) }
    var asking by remember { mutableStateOf<Ask?>(null) }
    BackHandler(onBack = onBack)
    EditorFrame("Projects", onBack) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (projects.isEmpty()) {
                Text("No stories yet.", style = frizStyle(16.sp, WowColors.grey), modifier = Modifier.padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    EditorCard(onClick = { onOpen(p) }) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = frizStyle(18.sp), maxLines = 1)
                            Text(
                                plural(p.cast.size, "NPC") + " · " + plural(p.scenes.size, "scene"),
                                style = frizStyle(12.sp, WowColors.grey),
                            )
                        }
                        SmallButton("Rename", { asking = Ask.Rename(p) })
                        SmallButton("Delete", { asking = Ask.Delete(p) })
                    }
                }
            }
            WowButton("New story", { asking = Ask.New }, Modifier.align(Alignment.CenterHorizontally), width = 240.dp)
        }
    }
    when (val a = asking) {
        Ask.New -> NameDialog("New story", "", "Create", { asking = null }) { name ->
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

/** A full-screen window: the title in gold, Back at the right, the content in the metal frame. */
@Composable
fun EditorFrame(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0710))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = morpheusStyle(26.sp), modifier = Modifier.weight(1f), maxLines = 1)
                actions()
                WowButton("Back", onBack, width = 130.dp, height = 40.dp)
            }
            MetalPanel(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** A row on a list: dark, gold-edged, lit when [selected]. */
@Composable
fun EditorCard(onClick: () -> Unit, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0x55FFD100) else Color(0x66000000))
            .border(1.dp, if (selected) WowColors.gold else Color(0x66FFD100))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, width: androidx.compose.ui.unit.Dp = 104.dp) =
    WowButton(text, onClick, modifier, enabled, width, 36.dp)

/** A line of text the author types, in the game's black edit box. */
@Composable
fun EditorField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    Box(modifier.fillMaxWidth().background(Color.Black).border(1.dp, Color(0xFF6B6B6B)).padding(10.dp)) {
        if (value.isEmpty()) Text(hint, style = frizStyle(16.sp, WowColors.grey, false))
        BasicTextField(
            value, onChange, singleLine = singleLine,
            textStyle = frizStyle(16.sp, Color.White, false),
            cursorBrush = SolidColor(WowColors.gold),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A name to type, in a window over the page. */
@Composable
fun NameDialog(title: String, initial: String, ok: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Overlay(onCancel) {
        MetalPanel(Modifier.width(480.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = frizStyle(18.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                EditorField(text, { text = it.take(60) }, "Name")
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowButton("Cancel", onCancel, width = 150.dp)
                    WowButton(ok, { onOk(text) }, width = 150.dp)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(question: String, ok: String, onCancel: () -> Unit, onOk: () -> Unit) {
    Overlay(onCancel) {
        MetalPanel(Modifier.width(480.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(question, style = frizStyle(16.sp, Color.White), modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowButton("Cancel", onCancel, width = 150.dp)
                    WowButton(ok, onOk, width = 150.dp)
                }
            }
        }
    }
}

/** A list of (id, label) to pick one from, in a window over the page. */
@Composable
fun PickDialog(title: String, items: List<Pair<String, String>>, current: String?, onCancel: () -> Unit, onPick: (String) -> Unit) {
    Overlay(onCancel) {
        MetalPanel(Modifier.width(420.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = frizStyle(18.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { it.first }) { (id, label) ->
                        EditorCard(onClick = { onPick(id) }, selected = id == current) {
                            Text(label, style = frizStyle(16.sp, if (id == current) WowColors.gold else Color.White), maxLines = 1)
                        }
                    }
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
        Box(
            Modifier.weight(1f).background(Color.Black).border(1.dp, Color(0xFF6B6B6B)).clickable { open = true }.padding(10.dp),
        ) {
            Text((items.firstOrNull { it.first == current }?.second ?: current ?: "").ifEmpty { "Choose" }, style = frizStyle(15.sp, Color.White, false), maxLines = 1)
        }
    }
    if (open) PickDialog(label, items, current, { open = false }) { open = false; onPick(it) }
}
