package com.logie.wifecraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.Shots
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.flow.StateFlow
import com.logie.wifecraft.catalog.Catalog as Library

/**
 * What the placement editor needs of the app (MainActivity's ScenePlacer): the library, the locations and their loading,
 * the renderer's placed things kept in step with the scene ([sync]), the emotes of a placed person or creature and their
 * preview, the creature's sounds. Main thread.
 */
interface PlaceEngine {
    /** The library (catalog.tsv's props, buildings and creature models, and the game's named creatures), once it is read. */
    val library: StateFlow<Library?>
    /** assets/locations.json's locations (the stage picker lists each with its views). */
    val locations: List<LocationInfo>
    /** A location's and a placed thing's files on their way, and what went wrong. */
    val status: StateFlow<PlaceStatus>

    /** Puts the stage on the renderer: a location ("loc:<id>:<view>", its files fetched with progress), or none (the city or the studio). */
    fun enterStage(stageId: String, light: String)
    /** Makes the renderer's placed things what [placements] says (added, moved, changed, removed; [hidden] ones taken off). */
    fun sync(placements: List<Placement>, cast: List<Actor>, hidden: Set<String>)
    /** Forgets what is on the renderer (it was cleared: SceneView.removeAll); the next [sync] puts everything back. */
    fun reset()
    /** Every emote the placed person or creature can play (EmoteCatalog.forModel by its race and sex). */
    fun emotesFor(p: Placement, cast: List<Actor>): List<Emote>
    /** Plays [e] on the placed thing now, with its voice when the placement's sound is on. */
    fun previewEmote(p: Placement, e: Emote, cast: List<Actor>)
    /** A creature's own sound ("fidget", "attack", "wound", "death", "loop"...), when its sound is on. */
    fun creatureSound(p: Placement, kind: String)
    /** The placement's sounds on or off (SceneAudio.setEnabled). */
    fun setSound(p: Placement, on: Boolean)
    fun clearErrors()
}

/** What the editor shows of loading: the stage's files, a placed thing's files, the sound. */
data class PlaceStatus(
    val stageName: String = "",
    val stageDone: Int = 0,
    val stageTotal: Int = 0,
    val stageBytesDone: Long = 0,
    val stageBytesTotal: Long = 0,
    val stageReady: Boolean = true,
    val stageError: String? = null,
    val fetchDone: Int = 0,
    val fetchTotal: Int = 0,
    val fetchError: String? = null,
    val soundError: String? = null,
) {
    val loadingStage: Boolean get() = !stageReady && stageError == null
    val errors: List<String> get() = listOfNotNull(stageError, fetchError, soundError)
}

/** A button at the touch size the placement editor keeps to (48 dp high). */
@Composable
internal fun PButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, width: Dp = 104.dp) =
    WowButton(text, onClick, modifier, enabled, width, 48.dp)

/** A switch drawn as a button: lit gold while on. */
@Composable
internal fun PToggle(text: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, width: Dp = 104.dp) {
    Box(
        modifier.width(width).height(48.dp)
            .background(if (on) Color(0x66FFD100) else Color(0x66000000))
            .border(1.dp, if (on) WowColors.gold else Color(0x66FFD100))
            .clickable { onChange(!on) },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = frizStyle(14.sp, if (on) Color.White else WowColors.gold), maxLines = 1) }
}

private enum class PanelTab(val label: String) { THINGS("Things"), SELECTED("Selected"), ANIMATION("Animation") }

/**
 * The placement editor of one scene (the Creation Kit's way): the stage full size with what is placed on it, the
 * library to add from, tap to select, drag on the floor to move (it follows the ground), the turn and lift handles, two
 * fingers to turn and resize, nudges, snapping, copy and delete, undo and redo, the list of placed things (rename, hide,
 * lock) and the animation or emote of a placed person or creature. The camera orbits with one finger and pans and zooms
 * with two where no placed thing is being moved. [camera] and [ui] are the scene builder's (kept while it is open);
 * [live] is the thing being moved before it is let go (drawn, not yet a change).
 */
@Composable
fun EditorPlacement(
    project: Project,
    state: EditorState,
    sceneId: String,
    view: SceneView,
    engine: PlaceEngine,
    stageName: String,
    camera: OrbitCamera,
    onCamera: (OrbitCamera) -> Unit,
    onResetCamera: () -> Unit,
    ui: PlaceUi,
    onUi: (PlaceUi) -> Unit,
    live: Placement?,
    onLive: (Placement?) -> Unit,
    onDone: () -> Unit,
) {
    val scene = state.sceneOf(sceneId) ?: return
    val library by engine.library.collectAsState()
    val status by engine.status.collectAsState()
    var snap by remember { mutableStateOf(Snap()) }
    var adding by remember { mutableStateOf(false) }
    var query by remember(sceneId) { mutableStateOf(LibraryQuery(style = LibraryPager.styleOf(scene.stageId))) }
    var tab by remember { mutableStateOf(PanelTab.THINGS) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(0 to 0) }

    BackHandler(onBack = onDone)

    val selected = ui.selected?.let { id -> scene.placements.firstOrNull { it.id == id } }
    val shownSel = if (live != null && live.id == selected?.id) live else selected
    val own = project.cast.map { Library.ownNpc(it.id, it.name) }

    fun commit(p: Placement) {
        state.updatePlacement(sceneId, p.id) { p }
        state.endGesture()
        onLive(null)
    }

    /** Adds [a] where the middle of the screen meets the floor (the renderer's pick, else the camera's floor), facing the camera. */
    fun add(a: Asset) {
        adding = false
        val (w, h) = size
        val pose = camera.pose()
        fun put(at: Vec3) {
            val p = placementOf(a, at, Shots.facing(at, pose.eye), scene.placements.map { it.id })
            val id = state.addPlacement(sceneId, p)
            if (id != null) { onUi(ui.select(id)); tab = PanelTab.SELECTED }
        }
        val fallback = FloorMath.onPlane(pose, w / 2f, h / 2f, w.toFloat(), h.toFloat(), camera.target.z) ?: camera.target
        if (w <= 0 || h <= 0) { put(fallback); return }
        view.pick(w / 2f, h / 2f, w, h) { hit ->
            val at = hit?.let { Vec3(it.x, it.y, it.z) } ?: fallback
            view.groundAt(at.x, at.y, at.z + 2f) { g -> put(Vec3(snap.pos(at.x), snap.pos(at.y), g ?: at.z)) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0A0710))) {
        val wide = maxWidth >= 600.dp && maxWidth >= maxHeight * 0.9f
        val panelW = (maxWidth * 0.34f).coerceIn(280.dp, 400.dp)
        val panelH = (maxHeight * 0.42f).coerceAtLeast(200.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // the tools: every one at least 48 dp; the row scrolls on a narrow screen
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stageName, style = morpheusStyle(20.sp), maxLines = 1, modifier = Modifier.widthIn(max = 260.dp).padding(end = 4.dp))
                PButton("Add", { adding = true }, width = 90.dp)
                PButton("Undo", { state.undo(); onLive(null) }, enabled = state.canUndo, width = 90.dp)
                PButton("Redo", { state.redo(); onLive(null) }, enabled = state.canRedo, width = 90.dp)
                PToggle("Snap", snap.on, { snap = snap.copy(on = it) }, width = 80.dp)
                if (snap.on) {
                    PButton("Grid ${showNumber(snap.grid)}", { snap = snap.copy(grid = Snap.GRIDS[(Snap.GRIDS.indexOf(snap.grid) + 1) % Snap.GRIDS.size]) }, width = 110.dp)
                    PButton("Turn ${snap.angleDeg.toInt()}°", { snap = snap.copy(angleDeg = Snap.ANGLES[(Snap.ANGLES.indexOf(snap.angleDeg) + 1) % Snap.ANGLES.size]) }, width = 110.dp)
                }
                PButton("View", onResetCamera, width = 80.dp)
                PButton("Done", onDone, width = 90.dp)
            }
            val panel: @Composable (Modifier) -> Unit = { m ->
                MetalPanel(m) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (t in PanelTab.values()) {
                                val can = t != PanelTab.ANIMATION || (shownSel != null && shownSel.kind != PlaceKind.PROP)
                                if (can || t == tab) PToggle(t.label, tab == t, { tab = t }, Modifier.weight(1f), width = 120.dp)
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                PanelTab.THINGS -> PlaceOutliner(
                                    scene.placements, ui, onSelect = { onUi(ui.select(it)) },
                                    onHide = { onUi(ui.toggleHidden(it)) }, onLock = { onUi(ui.toggleLocked(it)) },
                                    onRename = { renaming = it },
                                    onLookAt = { p -> onCamera(camera.lookAt(Vec3(p.x, p.y, p.z + 1f))) },
                                    nameOf = { a -> project.cast.firstOrNull { it.id == a }?.name },
                                )
                                PanelTab.SELECTED -> if (shownSel == null) {
                                    Text("Tap something on the stage, or in Things, to select it. Add puts a new one in the middle of the view.",
                                        style = frizStyle(14.sp, WowColors.grey, false), modifier = Modifier.padding(6.dp))
                                } else PlaceInspector(
                                    shownSel, snap, camera.pose(), locked = shownSel.id in ui.locked,
                                    onChange = { p -> state.updatePlacement(sceneId, p.id) { p } },
                                    onSlide = { p -> state.updatePlacement(sceneId, p.id, "slide:$sceneId:${p.id}") { p } },
                                    onSlideEnd = { state.endGesture() },
                                    onNudge = { d -> state.nudgePlacement(sceneId, shownSel.id, d.x, d.y, d.z) },
                                    onDuplicate = { state.duplicatePlacement(sceneId, shownSel.id)?.let { onUi(ui.select(it)) } },
                                    onDelete = { confirmDelete = shownSel.id },
                                    onAnimation = { tab = PanelTab.ANIMATION },
                                )
                                PanelTab.ANIMATION -> if (shownSel == null || shownSel.kind == PlaceKind.PROP) {
                                    Text("Select a person or a creature to give them an animation or an emote.", style = frizStyle(14.sp, WowColors.grey, false), modifier = Modifier.padding(6.dp))
                                } else {
                                    val emotes = remember(shownSel.kind, shownSel.asset, shownSel.actorId, project.cast) { engine.emotesFor(shownSel, project.cast) }
                                    EmotePicker(
                                        emotes, shownSel,
                                        onPreview = { e -> engine.previewEmote(shownSel, e, project.cast) },
                                        onUse = { e -> state.updatePlacement(sceneId, shownSel.id) { EmoteGroups.apply(it, e) } },
                                        onLoop = { state.setPlacementLoop(sceneId, shownSel.id, it) },
                                        onSound = { on -> state.setPlacementSound(sceneId, shownSel.id, on); engine.setSound(shownSel, on) },
                                        onCreatureSound = if (shownSel.kind == PlaceKind.CREATURE) { k -> engine.creatureSound(shownSel, k) } else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val viewport: @Composable (Modifier) -> Unit = { m ->
                Box(m.background(Color.Black).border(1.dp, Color(0x66FFD100))) {
                    PlaceViewport(
                        view, camera, onCamera, shownSel, ui.canMove(shownSel?.id), snap,
                        onTap = { sx, sy, w, h -> view.pick(sx, sy, w, h) { hit -> onUi(ui.tapped(hit?.key)) } },
                        onLive = { onLive(it) },
                        onCommit = { commit(it) },
                        onSize = { w, h -> size = w to h },
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlaceLoading(status, engine::clearErrors, Modifier.align(Alignment.TopCenter))
                }
            }
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewport(Modifier.weight(1f).fillMaxHeight())
                    panel(Modifier.width(panelW).fillMaxHeight())
                }
            } else {
                viewport(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth().height(panelH))
            }
        }

        if (adding) {
            val lib = library
            PlaceLibrarySheet(
                lib, own, query, { query = it }, onPick = { add(it) }, onClose = { adding = false },
                modifier = Modifier.align(Alignment.Center).padding(8.dp).widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(),
            )
        }
    }

    renaming?.let { id ->
        val p = scene.placements.firstOrNull { it.id == id }
        if (p == null) renaming = null else NameDialog("Rename", p.name, "Rename", { renaming = null }) { renaming = null; state.renamePlacement(sceneId, id, it) }
    }
    confirmDelete?.let { id ->
        val p = scene.placements.firstOrNull { it.id == id }
        val uses = scene.beats.count { (it is com.logie.wifecraft.editor.Beat.Emote && it.placementId == id) || (it is com.logie.wifecraft.editor.Beat.Anim && it.placementId == id) }
        if (p == null) confirmDelete = null else ConfirmDialog(
            "Delete \"${p.name}\"?" + if (uses > 0) " Its ${plural(uses, "beat")} go too." else "", "Delete", { confirmDelete = null },
        ) {
            confirmDelete = null
            state.removePlacement(sceneId, id)
            onUi(ui.select(null))
        }
    }
}

/** The stage's files and a placed thing's on their way, and what went wrong (a tap clears the errors). */
@Composable
fun PlaceLoading(s: PlaceStatus, onClear: () -> Unit, modifier: Modifier = Modifier) {
    if (!s.loadingStage && s.fetchTotal == 0 && s.errors.isEmpty()) return
    Column(
        modifier.padding(top = 6.dp).widthIn(max = 520.dp).background(Color(0xC0000000)).clickable(onClick = onClear)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (s.loadingStage) {
            val mb = if (s.stageBytesTotal > 0) " (%.1f of %.1f MB)".format(s.stageBytesDone / 1e6, s.stageBytesTotal / 1e6) else ""
            Text(
                if (s.stageTotal > 0) "Fetching ${s.stageName}: ${s.stageDone} of ${s.stageTotal} files$mb" else "Loading ${s.stageName}…",
                style = frizStyle(13.sp),
            )
            LoadingBar(if (s.stageTotal > 0) s.stageDone.toFloat() / s.stageTotal else 0f, Modifier.width(320.dp).height(30.dp))
        }
        if (s.fetchTotal > 0) Text("Fetching a placed thing's files… ${s.fetchDone} of ${s.fetchTotal}", style = frizStyle(12.sp, Color.White))
        for (e in s.errors) Text(e, style = frizStyle(12.sp, Color(0xFFFF6A5A), false))
    }
}
