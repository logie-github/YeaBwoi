package com.logie.wifecraft.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.LibraryPrefs
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.engine.Hit
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.Shots
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import com.logie.wifecraft.catalog.Catalog as Library

/**
 * The library's pictures (engine/Thumbs, MainActivity): a small picture of an entry, asked for when its card is on screen,
 * from the cache when it was drawn before. [cancelAll] drops what is still waiting (the sheet closed). [errors]: why
 * pictures can't be drawn (a line under the grid).
 */
interface ThumbSource {
    fun request(a: Asset, size: Int, onReady: (Bitmap?) -> Unit)
    fun cached(a: Asset, size: Int): Bitmap?
    fun cancelAll()
    val errors: StateFlow<String?>
}

/**
 * What the placement editor needs of the app (MainActivity's ScenePlacer): the library, the locations and their loading,
 * the renderer's placed things kept in step with the scene ([sync]), the emotes of a placed person or creature and their
 * preview, the creature's sounds, the library's pictures, each project's favourites and recent picks, and the editor's
 * own switches kept for the user (panels open or closed, tips seen). Main thread.
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

    /** The library's pictures; null: names only. */
    val thumbs: ThumbSource? get() = null
    /** Project [projectId]'s favourites and recent picks (files/projects/<id>/prefs.json). */
    fun libraryPrefs(projectId: String): LibraryPrefs = LibraryPrefs()
    fun saveLibraryPrefs(projectId: String, prefs: LibraryPrefs) {}
    /** One of the editor's switches kept for the user (data/Settings): a panel open, a tip seen. */
    fun flag(name: String, default: Boolean): Boolean = default
    fun setFlag(name: String, on: Boolean) {}
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
    /** SceneAudio is building or downloading its sound index (the first sound of a session waits for it). */
    val soundPreparing: Boolean = false,
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

private const val PANEL_FLAG = "place.panel"
private fun tipFlag(t: PlaceTip) = "place.tip." + t.id

/**
 * The placement editor of one scene (the Creation Kit's way): the stage full size with what is placed on it.
 *
 * - **Adding**: Add opens the library (a side panel on a wide screen, a bottom sheet otherwise). Tapping an entry arms it:
 *   its see-through ghost follows a finger dragged over the stage and lifting drops one (one undo step each); a tap aims
 *   the crosshair and Drop puts one there; it stays armed for the next until Done or Back.
 * - **Selecting**: tap a thing; tap the same spot again for the one behind it; double tap to look at it; long press for its
 *   menu (Duplicate, Delete, Lock, Hide, Emote, Focus); tap the floor to deselect; the outliner selects and looks.
 * - **Moving**: the gizmo on the selected thing (Move arrows and floor square, Rotate ring, Scale boxes: the mode bar), or
 *   drag its body on the floor; two fingers on it turn and resize it; nudges and numbers in Selected.
 * - The panels fold to slim tabs (remembered); a one-line tip says what the mode does until it is dismissed.
 *
 * [camera] and [ui] are the scene builder's (kept while it is open); [live] is the thing being moved before it is let go.
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
    val noThumbErrors = remember { MutableStateFlow<String?>(null) }
    val thumbError by (engine.thumbs?.errors ?: noThumbErrors).collectAsState()
    val density = LocalDensity.current

    var snap by remember { mutableStateOf(Snap()) }
    var tool by remember { mutableStateOf(GizmoTool.MOVE) }
    var activeAxis by remember { mutableIntStateOf(GizmoAxis.NONE) }
    var libraryOpen by remember { mutableStateOf(false) }
    var query by remember(sceneId) { mutableStateOf(LibraryQuery(style = LibraryPager.styleOf(scene.stageId))) }
    var placing by remember { mutableStateOf<Placing?>(null) }
    var panelOpen by remember { mutableStateOf(engine.flag(PANEL_FLAG, true)) }
    var tab by remember { mutableStateOf(PanelTab.THINGS) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(0 to 0) }
    var cycle by remember { mutableStateOf(TapCycle()) }
    var lastTap by remember { mutableStateOf<Pair<Long, Pair<Float, Float>>?>(null) }
    var menu by remember { mutableStateOf<Pair<String, Pair<Float, Float>>?>(null) }
    var matrices by remember { mutableStateOf<ViewMatrices?>(null) }
    var prefs by remember(project.id) { mutableStateOf(engine.libraryPrefs(project.id)) }
    var tipsSeen by remember { mutableStateOf(PlaceTip.values().filter { engine.flag(tipFlag(it), false) }.map { it.id }.toSet()) }
    // the ghost's pick: one asked of the renderer at a time, the newest finger point asked next
    val ghostPick = remember { object { var busy = false; var next: FloatArray? = null } }

    val sceneNow by rememberUpdatedState(scene)
    val uiNow by rememberUpdatedState(ui)
    val camNow by rememberUpdatedState(camera)

    val selected = ui.selected?.let { id -> scene.placements.firstOrNull { it.id == id } }
    val shownSel = if (live != null && live.id == selected?.id) live else selected
    val own = project.cast.map { Library.ownNpc(it.id, it.name) }
    val canMoveSel = ui.canMove(shownSel)

    // the renderer's camera matrices for the touch rays, once it draws the camera the editor has now
    LaunchedEffect(camera, size) {
        delay(80)
        view.cameraMatrices { matrices = it }
    }
    fun rays(w: Float, h: Float): ViewRay = ViewRays.choose(matrices, camNow.pose(), w, h)

    // the gizmo on the selected, unlocked thing (none while placing), its dragged handle lit
    LaunchedEffect(shownSel?.id, canMoveSel, tool, placing == null, activeAxis) {
        val key = shownSel?.id?.takeIf { canMoveSel && placing == null }?.let(PlaceKeys::of)
        view.showGizmo(if (key == null) null else tool, key, activeAxis)
    }
    DisposableEffect(Unit) {
        onDispose {
            view.showGizmo(null, null)
            view.ghostOff()
            engine.thumbs?.cancelAll()
        }
    }

    fun savePrefs(p: LibraryPrefs) { prefs = p; engine.saveLibraryPrefs(project.id, p) }

    fun commit(p: Placement) {
        state.updatePlacement(sceneId, p.id) { p }
        state.endGesture()
        onLive(null)
    }

    /** The camera looks at [p] from near enough to fill the view (a prop by its box; a person or creature by its height). */
    fun focus(p: Placement) {
        val tall = 1.8f * p.scale
        val fallback = Vec3(p.x, p.y, p.z + tall / 2f)
        if (p.kind == PlaceKind.PROP) view.propBounds(PlaceKeys.of(p.id)) { b ->
            if (b == null) onCamera(camNow.focus(fallback, tall))
            else onCamera(camNow.focus(Vec3((b.minX + b.maxX) / 2f, (b.minY + b.maxY) / 2f, (b.minZ + b.maxZ) / 2f), max(b.maxX - b.minX, max(b.maxY - b.minY, b.maxZ - b.minZ))))
        } else onCamera(camNow.focus(fallback, tall))
    }

    /** What is under a screen point, the renderer's own hit first, then the others there (the editor's "pick all"). */
    fun under(hit: Hit?, sx: Float, sy: Float, w: Int, h: Int): List<String> {
        val drawn = sceneNow.placements.filter { uiNow.drawn(it) }
        val engineHit = PlaceKeys.idOf(hit?.key)?.takeIf { id -> drawn.any { it.id == id } }
        val reach = with(density) { (if (engineHit != null) 56.dp else 28.dp).toPx() }
        return SelectCycle.order(engineHit, SelectCycle.under(rays(w.toFloat(), h.toFloat()), drawn, sx, sy, w.toFloat(), h.toFloat(), reach))
    }

    fun tapped(sx: Float, sy: Float, w: Int, h: Int, ms: Long) {
        val last = lastTap
        val double = TapTiming.isDouble(last?.first, last?.second, ms, sx to sy, with(density) { 32.dp.toPx() })
        lastTap = if (double) null else ms to (sx to sy)
        if (double) {
            uiNow.selected?.let { id -> sceneNow.placements.firstOrNull { it.id == id } }?.let { focus(it) }
            return
        }
        view.pick(sx, sy, w, h) { hit ->
            cycle = SelectCycle.next(cycle, sx, sy, under(hit, sx, sy, w, h), with(density) { 24.dp.toPx() })
            onUi(uiNow.select(cycle.selected))
            if (cycle.selected != null && tab == PanelTab.THINGS) tab = PanelTab.SELECTED
        }
    }

    fun longPressed(sx: Float, sy: Float, w: Int, h: Int) {
        view.pick(sx, sy, w, h) { hit ->
            val id = under(hit, sx, sy, w, h).firstOrNull() ?: return@pick
            onUi(uiNow.select(id))
            cycle = TapCycle(sx to sy, listOf(id), 0)
            menu = id to (sx to sy)
        }
    }

    // ---- placing

    /**
     * The ghost to [at] (snapped to the grid while Snap is on), turned as the mode has it; [settle]: put it on the ground
     * there (SceneView.groundAt), for a point that was only the camera's level floor or was moved by the grid.
     */
    fun moveGhost(at: Vec3, settle: Boolean) {
        val pl = placing ?: return
        val p = Vec3(snap.pos(at.x), snap.pos(at.y), at.z)
        placing = pl.follow(p)
        view.ghostTo(p, pl.yaw)
        if (settle) view.groundAt(p.x, p.y, at.z + 2f) { g ->
            val now = placing
            val cur = now?.at
            if (g != null && now != null && cur != null && cur.x == p.x && cur.y == p.y) {
                val q = Vec3(p.x, p.y, g)
                placing = now.follow(q)
                view.ghostTo(q, now.yaw)
            }
        }
    }

    /** The ghost under the screen point: the renderer's pick (the floor, a table top), else the camera's level floor there. */
    fun ghostAt(sx: Float, sy: Float, w: Int, h: Int) {
        val pl = placing ?: return
        if (ghostPick.busy) { ghostPick.next = floatArrayOf(sx, sy, w.toFloat(), h.toFloat()); return }
        ghostPick.busy = true
        view.pick(sx, sy, w, h) { hit ->
            ghostPick.busy = false
            if (placing?.asset?.key != pl.asset.key) return@pick
            val floor = hit != null && (hit.kind == Hit.Kind.GROUND || hit.kind == Hit.Kind.LOCATION)
            val onThing = hit != null && PlaceKeys.idOf(hit.key) != null
            if (hit != null && (floor || onThing)) moveGhost(Vec3(hit.x, hit.y, hit.z), settle = floor && snap.on)
            else {
                // nothing the renderer knows under the finger (or only the ghost or the cast): the level floor, then the ground there
                val (o, d) = rays(w.toFloat(), h.toFloat()).ray(sx, sy, w.toFloat(), h.toFloat())
                GizmoMath.planeHit(o, d, placing?.at?.z ?: camNow.target.z)?.let { moveGhost(it, settle = true) }
            }
            ghostPick.next?.let { n -> ghostPick.next = null; ghostAt(n[0], n[1], n[2].toInt(), n[3].toInt()) }
        }
    }

    fun drop(a: Asset, at: Vec3, yaw: Float) {
        val p = placementOf(a, at, yaw, sceneNow.placements.map { it.id })
        val id = state.addPlacement(sceneId, p)
        if (id != null) onUi(uiNow.select(id))
        savePrefs(prefs.used(a.key))
    }

    fun stopPlacing() {
        placing = null
        ghostPick.next = null
        view.ghostOff()
    }

    fun arm(a: Asset) {
        view.ghostOff()
        val pose = camera.pose()
        placing = Placing(a, yaw = Shots.facing(camera.target, pose.eye))
        menu = null
        val probe = placementOf(a, Vec3(0f, 0f, 0f), 0f, emptyList())
        val look = probe.actorId?.let { id -> project.cast.firstOrNull { it.id == id }?.appearance }
        if (probe.kind == PlaceKind.NPC && look == null) {
            placing = placing?.loaded(false, "${a.name} isn't in the cast any more.")
            return
        }
        view.ghostOf(probe.kind, probe.asset, 1f, look) { ok, why ->
            val now = placing
            if (now == null || now.asset.key != a.key) return@ghostOf
            placing = now.loaded(ok, why)
            val (w, h) = size
            if (ok && w > 0 && h > 0) {
                val c = now.cross ?: (w / 2f to h / 2f)
                ghostAt(c.first, c.second, w, h)
            }
        }
    }

    BackHandler(onBack = onDone)
    BackHandler(enabled = menu != null || placing != null || libraryOpen) {
        when {
            menu != null -> menu = null
            placing != null -> stopPlacing()
            else -> libraryOpen = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0A0710))) {
        val sheet = LibraryModel.sheet(maxWidth.value, maxHeight.value)
        val wide = maxWidth >= 600.dp && maxWidth >= maxHeight * 0.9f
        val panelW = (maxWidth * 0.34f).coerceIn(280.dp, 400.dp)
        val panelH = (maxHeight * 0.42f).coerceIn(160.dp, 420.dp)
        // the panel folds while the library is beside the stage or over it, and while placing
        val panelShown = panelOpen && !libraryOpen && placing == null

        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // the tools: every one at least 48 dp; the row scrolls on a narrow screen
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stageName, style = morpheusStyle(20.sp), maxLines = 1, modifier = Modifier.widthIn(max = 260.dp).padding(end = 4.dp))
                PToggle("Add", libraryOpen, { libraryOpen = it }, width = 90.dp)
                PButton("Undo", { state.undo(); onLive(null) }, enabled = state.canUndo, width = 90.dp)
                PButton("Redo", { state.redo(); onLive(null) }, enabled = state.canRedo, width = 90.dp)
                PToggle("Snap", snap.on, { snap = snap.copy(on = it) }, width = 80.dp)
                if (snap.on) {
                    PButton("Grid ${showNumber(snap.grid)}", { snap = snap.copy(grid = Snap.GRIDS[(Snap.GRIDS.indexOf(snap.grid) + 1) % Snap.GRIDS.size]) }, width = 110.dp)
                    PButton("Turn ${snap.angleDeg.toInt()}°", { snap = snap.copy(angleDeg = Snap.ANGLES[(Snap.ANGLES.indexOf(snap.angleDeg) + 1) % Snap.ANGLES.size]) }, width = 110.dp)
                }
                PToggle("Show hidden", ui.showHidden, { onUi(ui.toggleShowHidden()) }, width = 130.dp)
                PButton("View", onResetCamera, width = 80.dp)
                PButton("Tips", {
                    for (t in PlaceTip.values()) engine.setFlag(tipFlag(t), false)
                    tipsSeen = emptySet()
                }, width = 80.dp)
                PButton("Done", onDone, width = 90.dp)
            }

            val panel: @Composable (Modifier) -> Unit = { m ->
                MetalPanel(m) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (t in PanelTab.values()) {
                                val can = t != PanelTab.ANIMATION || (shownSel != null && shownSel.kind != PlaceKind.PROP)
                                if (can || t == tab) PToggle(t.label, tab == t, { tab = t }, Modifier.weight(1f), width = 120.dp)
                            }
                            PButton("Hide", { panelOpen = false; engine.setFlag(PANEL_FLAG, false) }, width = 72.dp)
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                PanelTab.THINGS -> PlaceOutliner(
                                    scene.placements, ui,
                                    onSelect = { id ->
                                        onUi(ui.select(id))
                                        id?.let { i -> scene.placements.firstOrNull { it.id == i } }?.let { focus(it) }
                                    },
                                    onHide = { id -> scene.placements.firstOrNull { it.id == id }?.let { p -> state.setPlacementHidden(sceneId, id, !p.hidden); onUi(ui.afterHide(p.copy(hidden = !p.hidden))) } },
                                    onLock = { id -> scene.placements.firstOrNull { it.id == id }?.let { p -> state.setPlacementLocked(sceneId, id, !p.locked) } },
                                    onRename = { renaming = it },
                                    onLookAt = { p -> focus(p) },
                                    nameOf = { a -> project.cast.firstOrNull { it.id == a }?.name },
                                )
                                PanelTab.SELECTED -> if (shownSel == null) {
                                    Text("Tap something on the stage, or in Things, to select it. Add opens the library: pick a thing, then drag it onto the stage.",
                                        style = frizStyle(14.sp, WowColors.grey, false), modifier = Modifier.padding(6.dp))
                                } else PlaceInspector(
                                    shownSel, snap, camera.pose(), locked = shownSel.locked,
                                    onChange = { p -> state.updatePlacement(sceneId, p.id) { p } },
                                    onSlide = { p -> state.updatePlacement(sceneId, p.id, "slide:$sceneId:${p.id}") { p } },
                                    onSlideEnd = { state.endGesture() },
                                    onNudge = { d -> state.nudgePlacement(sceneId, shownSel.id, d.x, d.y, d.z) },
                                    onDuplicate = { state.duplicatePlacement(sceneId, shownSel.id)?.let { onUi(ui.select(it)) } },
                                    onDelete = { confirmDelete = shownSel.id },
                                    onAnimation = { tab = PanelTab.ANIMATION },
                                    onSound = { on -> state.setPlacementSound(sceneId, shownSel.id, on); engine.setSound(shownSel, on) },
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
            // the panel folded: its tabs only, a slim strip (a tap opens it on that tab)
            val tabs: @Composable (Modifier, Boolean) -> Unit = { m, vertical ->
                val items: @Composable () -> Unit = {
                    for (t in PanelTab.values()) {
                        val can = t != PanelTab.ANIMATION || (shownSel != null && shownSel.kind != PlaceKind.PROP)
                        if (!can) continue
                        Box(
                            Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).background(Color(0xC0000000)).border(1.dp, Color(0x66FFD100))
                                .clickable { tab = t; panelOpen = true; libraryOpen = false; engine.setFlag(PANEL_FLAG, true) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (vertical) t.label.take(5) else t.label, style = frizStyle(12.sp), maxLines = 1) }
                    }
                }
                if (vertical) Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) { items() }
                else Row(m.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { items() }
            }

            val viewport: @Composable (Modifier) -> Unit = { m ->
                Box(m.background(Color.Black).border(1.dp, Color(0x66FFD100))) {
                    PlaceViewport(
                        view, camera, onCamera, shownSel, canMoveSel, snap, tool,
                        placing = placing != null, crosshair = placing?.cross,
                        rays = { w, h -> rays(w, h) },
                        actions = ViewportActions(
                            onTap = { sx, sy, w, h, ms -> menu = null; tapped(sx, sy, w, h, ms) },
                            onLongPress = { sx, sy, w, h -> longPressed(sx, sy, w, h) },
                            onLive = { onLive(it) },
                            onCommit = { commit(it) },
                            onActiveAxis = { activeAxis = it },
                            onGhostPress = { placing = placing?.press() },
                            onGhostDrag = { sx, sy, w, h -> ghostAt(sx, sy, w, h) },
                            onGhostRelease = {
                                val pl = placing
                                if (pl != null) {
                                    val (next, at) = pl.release()
                                    placing = next
                                    if (at != null) drop(next.asset, at, next.yaw)
                                }
                            },
                            onGhostCancel = { placing = placing?.cancelDrag() },
                            onAim = { sx, sy, w, h -> placing = placing?.aim(sx, sy); ghostAt(sx, sy, w, h) },
                        ),
                        onSize = { w, h -> size = w to h },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        placing?.let { pl ->
                            PlacingBar(
                                pl,
                                onTurn = { d -> val n = pl.turned(d); placing = n; n.at?.let { view.ghostTo(it, n.yaw) } },
                                turnStep = Math.toRadians((if (snap.on) snap.angleDeg else 15f).toDouble()).toFloat(),
                                onDrop = {
                                    val (next, at) = pl.dropHere()
                                    placing = next
                                    if (at != null) drop(next.asset, at, next.yaw)
                                },
                                onDone = { stopPlacing() },
                            )
                        }
                        val tip = PlaceTip.of(placing != null, shownSel != null, shownSel?.locked == true, tool)
                        if (tip.id !in tipsSeen) PlaceTipStrip(tip, { t -> tipsSeen = tipsSeen + t.id; engine.setFlag(tipFlag(t), true) })
                        PlaceLoading(status, engine::clearErrors)
                    }
                    if (shownSel != null && placing == null) {
                        GizmoBar(
                            tool, { tool = it }, locked = shownSel.locked,
                            onUnlock = { state.setPlacementLocked(sceneId, shownSel.id, false) },
                            active = activeAxis,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        )
                    }
                    menu?.let { (id, at) ->
                        val p = scene.placements.firstOrNull { it.id == id }
                        if (p == null) menu = null else PlaceContextMenu(p, at, size.first, size.second, onAction = { a ->
                            menu = null
                            when (a) {
                                PlaceAction.DUPLICATE -> state.duplicatePlacement(sceneId, id)?.let { onUi(ui.select(it)) }
                                PlaceAction.DELETE -> confirmDelete = id
                                PlaceAction.LOCK -> state.setPlacementLocked(sceneId, id, !p.locked)
                                PlaceAction.HIDE -> { state.setPlacementHidden(sceneId, id, !p.hidden); onUi(ui.afterHide(p.copy(hidden = !p.hidden))) }
                                PlaceAction.EMOTE -> { tab = PanelTab.ANIMATION; panelOpen = true; libraryOpen = false; engine.setFlag(PANEL_FLAG, true) }
                                PlaceAction.FOCUS -> focus(p)
                            }
                        }, onDismiss = { menu = null })
                    }
                }
            }

            val librarySheet: @Composable (Modifier) -> Unit = { m ->
                PlaceLibrarySheet(
                    library, own, query, { query = it }, prefs,
                    onFavourite = { a -> savePrefs(prefs.toggleFavourite(a.key)) },
                    thumbs = engine.thumbs, thumbError = thumbError,
                    spec = sheet, armed = placing?.asset?.key,
                    onPick = { a -> arm(a); if (!sheet.side) libraryOpen = false },
                    onClose = { libraryOpen = false; engine.thumbs?.cancelAll() },
                    modifier = m,
                )
            }

            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (libraryOpen && sheet.side) librarySheet(Modifier.width(sheet.widthDp.dp).fillMaxHeight())
                    viewport(Modifier.weight(1f).fillMaxHeight())
                    if (panelShown) panel(Modifier.width(panelW).fillMaxHeight())
                    else tabs(Modifier.width(64.dp).fillMaxHeight(), true)
                }
            } else {
                viewport(Modifier.weight(1f).fillMaxWidth())
                if (panelShown) panel(Modifier.fillMaxWidth().height(panelH))
                else tabs(Modifier.fillMaxWidth(), false)
            }
        }

        // the narrow screen's library: a bottom sheet over the lower part of the stage (the stage above stays usable)
        if (libraryOpen && !sheet.side) {
            PlaceLibrarySheet(
                library, own, query, { query = it }, prefs,
                onFavourite = { a -> savePrefs(prefs.toggleFavourite(a.key)) },
                thumbs = engine.thumbs, thumbError = thumbError,
                spec = sheet, armed = placing?.asset?.key,
                onPick = { a -> arm(a); libraryOpen = false },
                onClose = { libraryOpen = false; engine.thumbs?.cancelAll() },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheet.heightDp.dp),
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

/**
 * The placing mode's bar over the stage: "Placing: <name>", the ghost's turn (a step each way), Drop (one at the
 * crosshair), Done; under it the mode's line (loading, why the model can't be drawn, how many were placed).
 */
@Composable
private fun PlacingBar(p: Placing, onTurn: (Float) -> Unit, turnStep: Float, onDrop: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.widthIn(max = 720.dp).background(Color(0xD0100C08)).border(1.dp, WowColors.gold).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(p.title, style = frizStyle(15.sp), maxLines = 1, modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 6.dp))
            PButton("Turn ‹", { onTurn(turnStep) }, enabled = p.error == null, width = 88.dp)
            PButton("Turn ›", { onTurn(-turnStep) }, enabled = p.error == null, width = 88.dp)
            PButton("Drop", onDrop, enabled = p.canDrop, width = 84.dp)
            PButton("Done", onDone, width = 84.dp)
        }
        Text(p.line, style = frizStyle(12.sp, if (p.error != null) Color(0xFFFF6A5A) else Color.White, false), maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** The stage's files and a placed thing's on their way, and what went wrong (a tap clears the errors). */
@Composable
fun PlaceLoading(s: PlaceStatus, onClear: () -> Unit, modifier: Modifier = Modifier) {
    if (!s.loadingStage && s.fetchTotal == 0 && s.errors.isEmpty() && !s.soundPreparing) return
    Column(
        modifier.widthIn(max = 520.dp).background(Color(0xC0000000)).clickable(onClick = onClear)
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
        if (s.soundPreparing) Text("Preparing sounds…", style = frizStyle(12.sp, Color.White))
        for (e in s.errors) Text(e, style = frizStyle(12.sp, Color(0xFFFF6A5A), false))
        if (s.errors.isNotEmpty()) Text("Dismiss", style = frizStyle(13.sp, WowColors.gold), modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
