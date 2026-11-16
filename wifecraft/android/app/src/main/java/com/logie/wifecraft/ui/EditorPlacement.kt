package com.logie.wifecraft.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
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

/** A button at the touch size the placement editor keeps to (48 dp high): the build mode's wood pill. */
@Composable
internal fun PButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, width: Dp = 104.dp) =
    PillButton(text, onClick, modifier.widthIn(min = width), enabled)

/** A switch drawn as a chip: deep parchment with a gold edge while on. */
@Composable
internal fun PToggle(text: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, width: Dp = 104.dp) =
    Chip(text, null, on, { onChange(!on) }, modifier.widthIn(min = width))


private fun tipFlag(t: PlaceTip) = "place.tip." + t.id

/**
 * The placement editor of one scene, as simple as the game's own screen (MenuModel: [ActionSlot], [EditorChrome]):
 *
 * - **On screen**: the scene, the **Menu** button (top left, the Game Menu), the zone name, **Undo**, **Redo** and **Play**
 *   (top right), and the **action bar** along the bottom: Select (move), Rotate, Scale, Delete, **Add** (the Library window)
 *   and **More** (a dropdown: Duplicate, Lock, Hide, Emote, Look at it; Eyedropper, Snap to grid, Show hidden things; Home view,
 *   Objects in this scene, Stage, Tips again). A long press on a slot says what it does.
 * - **The selected thing** shows as the game's tooltip (bottom right): its name, Emote, Details (its fields and switches
 *   folded open). A long press on a thing opens its dropdown (Duplicate, Lock, Hide, Emote, Look at it, Details, Delete).
 * - **Windows** (one at a time, over the dimmed scene): the Library ([LibraryWindowView]: tapping a card arms it and the
 *   window closes), Objects in this scene, Emotes, and the **Game Menu** (Resume, Scenes, Cast, Animations, Options, Play,
 *   Save and Exit: [onMenu]). Back closes what is open, and with nothing open opens the Game Menu.
 * - Placing, selecting, the gizmo and the camera are as they were: tap a thing (again for the one behind), double tap to look
 *   at it; an armed card's ghost follows a finger dragged over the stage and lifting drops one (one undo step each).
 *
 * [camera] and [ui] are the scene builder's (kept while it is open); [live] is the thing being moved before it is let go.
 * [stages] (id, name) and [onStage] are the stage picker's; [onPlay] plays the project from this scene.
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
    stages: List<Pair<String, String>> = emptyList(),
    stageId: String = "",
    onStage: (String) -> Unit = {},
    onPlay: (() -> Unit)? = null,
    /** The Game Menu's Scenes, Cast, Animations, Options and Save and Exit (Resume and Play are the editor's own). */
    onMenu: (GameMenuItem) -> Unit = { if (it == GameMenuItem.SCENES || it == GameMenuItem.SAVE_EXIT) onDone() },
) {
    val scene = state.sceneOf(sceneId) ?: return
    val library by engine.library.collectAsState()
    val status by engine.status.collectAsState()
    val noThumbErrors = remember { MutableStateFlow<String?>(null) }
    val thumbError by (engine.thumbs?.errors ?: noThumbErrors).collectAsState()
    val density = LocalDensity.current

    var snap by remember { mutableStateOf(Snap()) }
    var cluster by remember { mutableStateOf(ToolCluster()) }
    val tool = cluster.gizmo
    var activeAxis by remember { mutableIntStateOf(GizmoAxis.NONE) }
    var chrome by remember { mutableStateOf(EditorChrome()) }
    var slotHint by remember { mutableStateOf<ActionSlot?>(null) }
    var query by remember(sceneId) { mutableStateOf(LibraryQuery(tab = LibraryTab.PROPS, style = LibraryPager.styleOf(scene.stageId))) }
    var placing by remember { mutableStateOf<Placing?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(0 to 0) }
    var cycle by remember { mutableStateOf(TapCycle()) }
    var lastTap by remember { mutableStateOf<Pair<Long, Pair<Float, Float>>?>(null) }
    var menuAt by remember { mutableStateOf<Pair<String, Pair<Float, Float>>?>(null) }
    var matrices by remember { mutableStateOf<ViewMatrices?>(null) }
    var prefs by remember(project.id) { mutableStateOf(engine.libraryPrefs(project.id)) }
    var tipsSeen by remember { mutableStateOf(PlaceTip.values().filter { engine.flag(tipFlag(it), false) }.map { it.id }.toSet()) }
    var stagePicker by remember { mutableStateOf(false) }
    // the ghost's pick: one asked of the renderer at a time, the newest finger point asked next
    val ghostPick = remember { object { var busy = false; var next: FloatArray? = null } }

    val sceneNow by rememberUpdatedState(scene)
    val uiNow by rememberUpdatedState(ui)
    val camNow by rememberUpdatedState(camera)
    val clusterNow by rememberUpdatedState(cluster)

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
    // the hints go after a moment
    LaunchedEffect(cluster.hint) { if (cluster.hint != null) { delay(3500); cluster = cluster.clearHint() } }
    LaunchedEffect(slotHint) { if (slotHint != null) { delay(3500); slotHint = null } }
    // the selected thing's details fold away when another is selected
    LaunchedEffect(ui.selected) { if (ui.selected == null && chrome.details) chrome = chrome.copy(details = false) }
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
        }
    }

    fun longPressed(sx: Float, sy: Float, w: Int, h: Int) {
        view.pick(sx, sy, w, h) { hit ->
            val id = under(hit, sx, sy, w, h).firstOrNull() ?: return@pick
            onUi(uiNow.select(id))
            cycle = TapCycle(sx to sy, listOf(id), 0)
            menuAt = id to (sx to sy)
            chrome = chrome.open(EditorWindow.CONTEXT)
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
        chrome = chrome.close()
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

    /** The Eyedropper: what is under the finger is armed to be placed again, and the bar goes back to Select. */
    fun eyedrop(sx: Float, sy: Float, w: Int, h: Int) {
        view.pick(sx, sy, w, h) { hit ->
            val id = under(hit, sx, sy, w, h).firstOrNull() ?: return@pick
            val p = sceneNow.placements.firstOrNull { it.id == id } ?: return@pick
            val lib = library
            val a = Eyedropper.assetFor(
                p, find = { k -> own.firstOrNull { it.key == k } ?: lib?.get(k) },
                byDisplay = { d ->
                    lib?.assets?.firstOrNull { it.kind == AssetKind.CREATURE && it.display == d }
                        ?: lib?.assets?.firstOrNull { it.kind == AssetKind.CREATURE_MODEL && it.display == d }
                },
            )
            cluster = clusterNow.picked()
            arm(a)
        }
    }

    /** A tool of the old cluster (Select, Rotate, Scale, Delete; the More dropdown's Duplicate, Lock, Hide, Emote, Look at it). */
    fun useTool(t: BuildTool) {
        val (next, action) = cluster.tap(t, shownSel)
        cluster = next
        val p = shownSel ?: return
        when (action) {
            ToolAction.NONE -> Unit
            ToolAction.DELETE -> confirmDelete = p.id
            ToolAction.DUPLICATE -> state.duplicatePlacement(sceneId, p.id)?.let { onUi(ui.select(it)) }
            ToolAction.TOGGLE_LOCK -> state.setPlacementLocked(sceneId, p.id, !p.locked)
            ToolAction.TOGGLE_HIDE -> { state.setPlacementHidden(sceneId, p.id, !p.hidden); onUi(ui.afterHide(p.copy(hidden = !p.hidden))) }
            ToolAction.OPEN_EMOTES -> chrome = chrome.open(EditorWindow.EMOTES)
            ToolAction.FOCUS -> focus(p)
        }
    }

    /** A row of More's dropdown or of the long-press menu, on [target] (the selected thing, or the long-pressed one). */
    fun act(a: MenuAction, target: Placement?) {
        // a switch's row leaves the dropdown open (as the game's check box rows do); everything else closes it
        if (a != MenuAction.SNAP && a != MenuAction.SHOW_HIDDEN) chrome = chrome.close()
        when (a) {
            MenuAction.DUPLICATE -> target?.let { p -> state.duplicatePlacement(sceneId, p.id)?.let { onUi(ui.select(it)) } }
            MenuAction.LOCK -> target?.let { p -> state.setPlacementLocked(sceneId, p.id, !p.locked) }
            MenuAction.HIDE -> target?.let { p -> state.setPlacementHidden(sceneId, p.id, !p.hidden); onUi(ui.afterHide(p.copy(hidden = !p.hidden))) }
            MenuAction.EMOTE -> if (target != null && target.kind != PlaceKind.PROP) chrome = chrome.open(EditorWindow.EMOTES)
            MenuAction.FOCUS -> target?.let { focus(it) }
            MenuAction.DETAILS -> if (target != null) chrome = chrome.copy(window = EditorWindow.NONE, details = true)
            MenuAction.DELETE -> target?.let { confirmDelete = it.id }
            MenuAction.EYEDROPPER -> cluster = if (cluster.eyedropping) cluster.picked() else cluster.tap(BuildTool.EYEDROPPER, shownSel).first
            MenuAction.SNAP -> snap = snap.copy(on = !snap.on)
            MenuAction.SHOW_HIDDEN -> onUi(ui.toggleShowHidden())
            MenuAction.HOME_VIEW -> onResetCamera()
            MenuAction.OBJECTS -> chrome = chrome.open(EditorWindow.OBJECTS)
            MenuAction.STAGE -> stagePicker = true
            MenuAction.TIPS -> { for (t in PlaceTip.values()) engine.setFlag(tipFlag(t), false); tipsSeen = emptySet() }
        }
    }

    fun slot(s: ActionSlot) {
        when (s) {
            ActionSlot.ADD -> chrome = chrome.toggle(EditorWindow.LIBRARY)
            ActionSlot.MORE -> chrome = chrome.toggle(EditorWindow.MORE)
            else -> { chrome = chrome.close(); s.tool?.let { useTool(it) } }
        }
    }

    // Back: leaves placing or the Eyedropper first, then closes what is open; with nothing open it opens the Game Menu
    BackHandler {
        when {
            placing != null && chrome.window == EditorWindow.NONE -> stopPlacing()
            cluster.eyedropping && chrome.window == EditorWindow.NONE -> cluster = cluster.picked()
            else -> chrome = chrome.back()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF140D08))) {
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        val boxH = maxHeight
        val barTopPx = with(density) { layout.barTop.dp.toPx() }

        // ---- the scene, the whole screen
        PlaceViewport(
            view, camera, onCamera, shownSel, canMoveSel, snap, tool,
            placing = placing != null, crosshair = placing?.cross,
            rays = { w, h -> rays(w, h) },
            actions = ViewportActions(
                onTap = { sx, sy, w, h, ms ->
                    if (chrome.window != EditorWindow.NONE) chrome = chrome.close()
                    if (clusterNow.eyedropping) eyedrop(sx, sy, w, h) else tapped(sx, sy, w, h, ms)
                },
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

        if (chrome.barShown) {
            // a soft fade at the top so the few buttons read over a bright scene
            Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color(0x96140E09), Color.Transparent))))

            // ---- top left: the Menu button and the zone name
            Row(
                Modifier.align(Alignment.TopStart).padding(ChromeLayout.G.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MenuCogButton({ chrome = chrome.toggle(EditorWindow.GAME_MENU) })
                if (layout.nameW > 0f) Text(scene.name.ifBlank { stageName }, style = frizStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = layout.nameW.dp))
            }
            // ---- top right: Undo, Redo, Play
            Row(
                Modifier.align(Alignment.TopEnd).padding(ChromeLayout.G.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundIconButton(Face(glyph = WarIcons.UNDO), { state.undo(); onLive(null) }, enabled = state.canUndo, description = "Undo")
                RoundIconButton(Face(glyph = WarIcons.REDO), { state.redo(); onLive(null) }, enabled = state.canRedo, description = "Redo")
                if (onPlay != null) RoundIconButton(Face(glyph = WarIcons.PLAY), onPlay, primary = true, size = 60.dp, description = "Play")
            }

            // ---- under the top: placing, the snap steps, the one tip line, loading
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 76.dp, start = 12.dp, end = 12.dp).widthIn(max = 640.dp),
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
                if (snap.on) TipLine(null) {
                    TextAction("Grid ${showNumber(snap.grid)} yd", { snap = snap.copy(grid = Snap.GRIDS[(Snap.GRIDS.indexOf(snap.grid) + 1) % Snap.GRIDS.size]) })
                    TextAction("Turn ${snap.angleDeg.toInt()}°", { snap = snap.copy(angleDeg = Snap.ANGLES[(Snap.ANGLES.indexOf(snap.angleDeg) + 1) % Snap.ANGLES.size]) })
                    TextAction("Snap off", { snap = snap.copy(on = false) })
                }
                when {
                    slotHint != null -> TipLine("${slotHint!!.label}: ${slotHint!!.tip}")
                    cluster.hint != null -> TipLine(cluster.tip(shownSel))
                    cluster.eyedropping -> TipLine("Eyedropper: tap a placed thing to place another of it") { TextAction("Cancel", { cluster = cluster.picked() }) }
                    shownSel?.locked == true && placing == null -> TipLine("${shownSel.name} is locked") {
                        TextAction("Unlock", { state.setPlacementLocked(sceneId, shownSel.id, false) })
                    }
                    activeAxis != GizmoAxis.NONE -> TipLine(GizmoAxis.name(activeAxis))
                    else -> {
                        val tip = PlaceTip.of(placing != null, shownSel != null, shownSel?.locked == true, tool)
                        if (tip.id !in tipsSeen) TipLine(tip.text) { TextAction("Got it", { tipsSeen = tipsSeen + tip.id; engine.setFlag(tipFlag(tip), true) }) }
                    }
                }
                PlaceLoading(status, engine::clearErrors)
            }

            // ---- the selected thing's tooltip, bottom right over the bar
            if (shownSel != null && chrome.inspectorShown(true, placing != null)) {
                val emotes = remember(shownSel.kind, shownSel.asset, shownSel.actorId, project.cast) {
                    if (shownSel.kind == PlaceKind.PROP) emptyList() else engine.emotesFor(shownSel, project.cast)
                }
                InspectorTooltip(
                    shownSel, snap, camera.pose(), chrome.details,
                    onDetails = { chrome = chrome.toggleDetails() },
                    onEmote = { chrome = chrome.open(EditorWindow.EMOTES) },
                    onChange = { p -> state.updatePlacement(sceneId, p.id) { p } },
                    onNudge = { d -> state.nudgePlacement(sceneId, shownSel.id, d.x, d.y, d.z) },
                    onSound = { on -> state.setPlacementSound(sceneId, shownSel.id, on); engine.setSound(shownSel, on) },
                    onLoop = { state.setPlacementLoop(sceneId, shownSel.id, it) },
                    emoteName = emotes.firstOrNull { it.id == shownSel.emoteId }?.label,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = ChromeLayout.G.dp, bottom = (layout.barTop + 12).dp).width(layout.inspectorW.dp),
                    maxHeight = (boxH - (layout.barTop + 12 + 76).dp).coerceAtLeast(120.dp),
                )
            }

            // ---- the action bar
            ActionBarView(
                layout.slot.dp,
                {
                    for (s in ActionSlot.values()) ActionSlotButton(
                        slotFace(s), s.label, { slot(s) }, layout.slot.dp,
                        lit = ActionBarModel.lit(s, cluster, chrome, placing != null),
                        enabled = ActionBarModel.enabled(s, cluster, shownSel),
                        onLongPress = { slotHint = s },
                    )
                },
                Modifier.align(Alignment.BottomCenter).padding(bottom = ChromeLayout.BAR_MARGIN.dp),
            )
        }

        // ---- More's dropdown, over the bar's right end
        if (chrome.window == EditorWindow.MORE) {
            Box(Modifier.fillMaxSize().clickable { chrome = chrome.close() })
            val rows = EditorMenus.more(shownSel, cluster, snap.on, ui.showHidden, stages.isNotEmpty())
            val wants = EditorMenus.height(rows, true)
            val room = maxHeight.value - layout.barTop - 8f - ChromeLayout.G
            val h = ChromeLayout.dropdownH(wants, room)
            val end = ((maxWidth.value - layout.barW) / 2f + 18f).coerceAtLeast(ChromeLayout.G)
            WowDropdownList(
                "More", rows, { r -> act(r.action, shownSel) },
                Modifier.align(Alignment.BottomEnd).padding(end = end.dp, bottom = (layout.barTop + 8).dp).width(300.dp),
                maxHeight = (h - EditorMenus.TITLE - 8f).dp,
            )
        }
        // ---- the long-pressed thing's dropdown
        if (chrome.window == EditorWindow.CONTEXT) menuAt?.let { (id, at) ->
            val p = scene.placements.firstOrNull { it.id == id }
            if (p == null) chrome = chrome.close()
            else PlaceContextMenu(p, at, size.first, size.second, barTopPx, onAction = { a -> act(a, p) }, onDismiss = { chrome = chrome.close() })
        }

        // ---- the windows
        when (chrome.window) {
            EditorWindow.LIBRARY -> LibraryWindowView(
                library, own, query, { query = it }, prefs,
                onFavourite = { a -> savePrefs(prefs.toggleFavourite(a.key)) },
                thumbs = engine.thumbs, thumbError = thumbError,
                armed = placing?.asset?.key,
                onPick = { a -> if (placing?.asset?.key == a.key) { stopPlacing(); chrome = chrome.close() } else arm(a) },
                onClose = { chrome = chrome.close() },
                width = layout.libraryW.dp, height = layout.libraryH.dp, card = layout.card.dp,
            )
            EditorWindow.OBJECTS -> ObjectsWindow(
                scene.placements, ui,
                onSelect = { id ->
                    onUi(ui.select(id))
                    id?.let { i -> scene.placements.firstOrNull { it.id == i } }?.let { focus(it) }
                },
                onHide = { id -> scene.placements.firstOrNull { it.id == id }?.let { p -> state.setPlacementHidden(sceneId, id, !p.hidden); onUi(ui.afterHide(p.copy(hidden = !p.hidden))) } },
                onLock = { id -> scene.placements.firstOrNull { it.id == id }?.let { p -> state.setPlacementLocked(sceneId, id, !p.locked) } },
                onRename = { renaming = it },
                onLookAt = { p -> focus(p); chrome = chrome.close() },
                onShowHidden = { onUi(ui.toggleShowHidden()) },
                nameOf = { a -> project.cast.firstOrNull { it.id == a }?.name },
                onClose = { chrome = chrome.close() },
                width = layout.listW.dp, height = layout.listH.dp,
            )
            EditorWindow.EMOTES -> if (shownSel == null || shownSel.kind == PlaceKind.PROP) chrome = chrome.close() else {
                val emotes = remember(shownSel.kind, shownSel.asset, shownSel.actorId, project.cast) { engine.emotesFor(shownSel, project.cast) }
                EmoteWindow(
                    emotes, shownSel,
                    onPreview = { e -> engine.previewEmote(shownSel, e, project.cast) },
                    onUse = { e -> state.updatePlacement(sceneId, shownSel.id) { EmoteGroups.apply(it, e) } },
                    onLoop = { state.setPlacementLoop(sceneId, shownSel.id, it) },
                    onSound = { on -> state.setPlacementSound(sceneId, shownSel.id, on); engine.setSound(shownSel, on) },
                    onCreatureSound = if (shownSel.kind == PlaceKind.CREATURE) { k -> engine.creatureSound(shownSel, k) } else null,
                    onClose = { chrome = chrome.close() },
                    width = layout.listW.dp, height = layout.listH.dp,
                )
            }
            EditorWindow.GAME_MENU -> GameMenuPanel(
                Menus.game(onPlay != null), layout.gameMenuCols,
                onPick = { item ->
                    chrome = chrome.close()
                    when (item) {
                        GameMenuItem.RESUME -> Unit
                        GameMenuItem.PLAY -> onPlay?.invoke()
                        else -> { stopPlacing(); onMenu(item) }
                    }
                },
                onDismiss = { chrome = chrome.close() },
                subtitle = scene.name.ifBlank { stageName },
            )
            else -> Unit
        }
    }

    if (stagePicker) StagePicker(stages, stageId, { stagePicker = false }) { id -> stagePicker = false; onStage(id) }
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

/** One line over the scene in the game's tooltip box: [text] (none: only the actions), then [actions] (text buttons). */
@Composable
fun TipLine(text: String?, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    WowTooltipBox(modifier.heightIn(min = 44.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (text != null) Text(text, style = frizStyle(13.sp, Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp, horizontal = 0.dp))
            actions?.invoke(this)
        }
    }
}

/** The stage picker: the studio, Dalaran's places and every location's views, as a WoW list window; the current one lit. */
@Composable
fun StagePicker(stages: List<Pair<String, String>>, current: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
    Overlay(onCancel) {
        WowWindow("The stage", onCancel, Modifier.widthIn(max = 560.dp).heightIn(max = 560.dp).padding(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(stages, key = { it.first }) { (id, name) ->
                    WowListRow(
                        name, null, selected = id == current, onClick = { onPick(id) },
                        icon = if (StageChoices.locationOf(id) != null) "menu.location" else if (id == EditorChoices.STUDIO) "menu.studio" else "tool.stage",
                    )
                }
            }
        }
    }
}

/**
 * The placing mode's strip under the top: "Placing: <name>", the ghost's turn (a step each way), Drop (one at the crosshair),
 * Done; under it the mode's line (loading, why the model can't be drawn, how many were placed).
 */
@Composable
private fun PlacingBar(p: Placing, onTurn: (Float) -> Unit, turnStep: Float, onDrop: () -> Unit, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WowTooltipBox {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = frizStyle(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                TextAction("Turn ‹", { onTurn(turnStep) }, enabled = p.error == null)
                TextAction("Turn ›", { onTurn(-turnStep) }, enabled = p.error == null)
                TextAction("Drop", onDrop, enabled = p.canDrop)
                TextAction("Done", onDone)
            }
        }
        Text(p.line, style = frizStyle(12.sp, if (p.error != null) Color(0xFFFF8A6A) else Color.White), maxLines = 2)
    }
}

/** The stage's files and a placed thing's on their way, and what went wrong (a tap clears the errors). */
@Composable
fun PlaceLoading(s: PlaceStatus, onClear: () -> Unit, modifier: Modifier = Modifier) {
    if (!s.loadingStage && s.fetchTotal == 0 && s.errors.isEmpty() && !s.soundPreparing) return
    WowTooltipBox(modifier.widthIn(max = 520.dp).warPress(onClick = onClear)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            for (e in s.errors) Text(e, style = frizStyle(12.sp, Color(0xFFFF8A6A)))
            if (s.errors.isNotEmpty()) Text("Dismiss", style = frizStyle(13.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}
