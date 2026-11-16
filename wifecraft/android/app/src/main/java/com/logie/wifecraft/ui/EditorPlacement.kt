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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
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

private const val PANEL_FLAG = "place.panel"
private const val BAR_FLAG = "place.bar"
private fun tipFlag(t: PlaceTip) = "place.tip." + t.id

/**
 * The placement editor of one scene, laid out as the Sims' build mode in the game's own interface (WarArt, softened):
 *
 * - **The scene fills the screen.** A slim top bar over it: Back, the scene's name on its ribbon, Undo, Redo, Camera (home
 *   view, look at the selected thing, show hidden things, tips again), Snap (with its Grid and Turn steps under the bar
 *   while it is on), Stage (the stage picker) and Play.
 * - **The catalogue bar** along the bottom (PlaceCatalogueBar): round category tabs (Starter pack, Props, Buildings,
 *   Creatures, My NPCs, Favourites, Recent), the category chips (More… opens the whole tree as a spellbook-page flyout), a
 *   sideways row of picture cards. Tapping a card arms it: its see-through ghost follows a finger dragged over the stage
 *   and lifting drops one (one undo step each); a tap aims the crosshair and Drop puts one there; it stays armed until Done.
 * - **The tool cluster** in the bottom-left corner: Select (move), Rotate, Scale, Delete, Eyedropper (tap a placed thing to
 *   arm another of it), Duplicate, Lock, Hide, Emote, Focus. A long press names a tool in the tooltip strip.
 * - **The spellbook pages** on the right: Things (the outliner), Selected (the inspector), Animation (the emotes); they slide
 *   in, fold to their side tabs (remembered), and fold by themselves while placing or while the flyout is open.
 * - Selecting as before: tap a thing; tap the same spot again for the one behind it; double tap to look at it; long press for
 *   its menu; the gizmo on the selected thing (Move arrows and floor square, Rotate ring, Scale boxes) follows the cluster.
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
    var bar by remember { mutableStateOf(CatalogueBar(open = engine.flag(BAR_FLAG, true))) }
    var query by remember(sceneId) { mutableStateOf(LibraryQuery(style = LibraryPager.styleOf(scene.stageId))) }
    var placing by remember { mutableStateOf<Placing?>(null) }
    var book by remember { mutableStateOf(BookPages(open = engine.flag(PANEL_FLAG, true))) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(0 to 0) }
    var cycle by remember { mutableStateOf(TapCycle()) }
    var lastTap by remember { mutableStateOf<Pair<Long, Pair<Float, Float>>?>(null) }
    var menu by remember { mutableStateOf<Pair<String, Pair<Float, Float>>?>(null) }
    var matrices by remember { mutableStateOf<ViewMatrices?>(null) }
    var prefs by remember(project.id) { mutableStateOf(engine.libraryPrefs(project.id)) }
    var tipsSeen by remember { mutableStateOf(PlaceTip.values().filter { engine.flag(tipFlag(it), false) }.map { it.id }.toSet()) }
    var cameraMenu by remember { mutableStateOf(false) }
    var stagePicker by remember { mutableStateOf(false) }
    var barPx by remember { mutableIntStateOf(0) }
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
    // the tooltip's hint goes after a moment
    LaunchedEffect(cluster.hint) { if (cluster.hint != null) { delay(3500); cluster = cluster.clearHint() } }
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
            val now = cycle.selected?.let { i -> sceneNow.placements.firstOrNull { it.id == i } }
            book = book.selected(now)
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

    /** The Eyedropper: what is under the finger is armed to be placed again, and the cluster goes back to Select. */
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

    fun setBook(b: BookPages) { book = b; engine.setFlag(PANEL_FLAG, b.open) }
    fun setBar(b: CatalogueBar) { if (b.open != bar.open) engine.setFlag(BAR_FLAG, b.open); bar = b }

    /** A tool of the cluster tapped: its mode, or its action on the selected thing. */
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
            ToolAction.OPEN_EMOTES -> setBook(BookPages(true, BookTab.ANIMATION))
            ToolAction.FOCUS -> focus(p)
        }
    }

    BackHandler(onBack = onDone)
    BackHandler(enabled = menu != null || placing != null || bar.flyout || cameraMenu || cluster.eyedropping) {
        when {
            menu != null -> menu = null
            cameraMenu -> cameraMenu = false
            placing != null -> stopPlacing()
            bar.flyout -> setBar(bar.closeFlyout())
            else -> cluster = cluster.picked()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF140D08))) {
        val layout = BuildLayout.of(maxWidth.value, maxHeight.value)
        val boxH = maxHeight // read here: inside Row/Column scopes the layout DSL marker hides BoxWithConstraints' receiver
        val barH = with(density) { barPx.toDp() }
        val pageShown = book.visible(bar.flyout, placing != null)

        // ---- the scene, the whole screen
        PlaceViewport(
            view, camera, onCamera, shownSel, canMoveSel, snap, tool,
            placing = placing != null, crosshair = placing?.cross,
            rays = { w, h -> rays(w, h) },
            actions = ViewportActions(
                onTap = { sx, sy, w, h, ms -> menu = null; cameraMenu = false; if (clusterNow.eyedropping) eyedrop(sx, sy, w, h) else tapped(sx, sy, w, h, ms) },
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

        // ---- the top bar
        TopBar(scene.name.ifBlank { stageName }, onDone, Modifier.align(Alignment.TopStart)) {
            RoundIconButton(Face(glyph = WarIcons.UNDO), { state.undo(); onLive(null) }, enabled = state.canUndo, description = "Undo")
            RoundIconButton(Face(glyph = WarIcons.REDO), { state.redo(); onLive(null) }, enabled = state.canRedo, description = "Redo")
            RoundIconButton(Face(icon = "tool.camera"), { cameraMenu = !cameraMenu }, lit = cameraMenu, description = "Camera")
            RoundIconButton(Face(decor = if (snap.on) "decor-ability-snap-active" else "decor-ability-snap-default"), { snap = snap.copy(on = !snap.on) },
                lit = snap.on, description = "Snap")
            if (stages.isNotEmpty()) RoundIconButton(Face(icon = "tool.stage"), { stagePicker = true }, description = "Stage")
            if (onPlay != null) RoundIconButton(Face(glyph = WarIcons.PLAY), onPlay, primary = true, size = if (layout.compact) 56.dp else 64.dp, description = "Play")
        }

        // ---- under the top bar: placing, the snap steps, the camera menu, tips, loading
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = (WarSize.TOP_BAR + 6).dp, start = 72.dp, end = 72.dp).widthIn(max = 760.dp),
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
            if (snap.on) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Grid ${showNumber(snap.grid)} yd", null, false, { snap = snap.copy(grid = Snap.GRIDS[(Snap.GRIDS.indexOf(snap.grid) + 1) % Snap.GRIDS.size]) })
                Chip("Turn ${snap.angleDeg.toInt()}°", null, false, { snap = snap.copy(angleDeg = Snap.ANGLES[(Snap.ANGLES.indexOf(snap.angleDeg) + 1) % Snap.ANGLES.size]) })
            }
            if (cameraMenu) GlassStrip {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Home view", { onResetCamera(); cameraMenu = false })
                    PillButton("Look at it", { shownSel?.let { focus(it) }; cameraMenu = false }, enabled = shownSel != null)
                    WarToggle("Show hidden", ui.showHidden, { onUi(ui.toggleShowHidden()) }, dark = true)
                    PillButton("Tips again", {
                        for (t in PlaceTip.values()) engine.setFlag(tipFlag(t), false)
                        tipsSeen = emptySet(); cameraMenu = false
                    })
                }
            }
            when {
                shownSel?.locked == true && placing == null -> Tooltip("${shownSel.name} is locked", onDismiss = { state.setPlacementLocked(sceneId, shownSel.id, false) }, dismissLabel = "Unlock")
                activeAxis != GizmoAxis.NONE -> Tooltip(GizmoAxis.name(activeAxis))
                else -> {
                    val tip = PlaceTip.of(placing != null, shownSel != null, shownSel?.locked == true, tool)
                    if (tip.id !in tipsSeen && cluster.hint == null) Tooltip(tip.text, onDismiss = { tipsSeen = tipsSeen + tip.id; engine.setFlag(tipFlag(tip), true) })
                }
            }
            PlaceLoading(status, engine::clearErrors)
        }

        // ---- the spellbook pages' side tabs, and the open page
        val tabs: @Composable () -> Unit = {
            for (t in book.tabs(shownSel)) SideTab(t.icon, t.label, pageShown && book.shown(shownSel) == t, { setBook(book.tap(t)) })
        }
        if (layout.sideTabsRow) Row(
            Modifier.align(Alignment.TopEnd).padding(top = (WarSize.TOP_BAR + 4).dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { tabs() }
        else Column(
            Modifier.align(Alignment.TopEnd).padding(top = (WarSize.TOP_BAR + 18).dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { tabs() }
        AnimatedVisibility(
            pageShown,
            Modifier.align(Alignment.TopEnd).padding(top = (WarSize.TOP_BAR + if (layout.sideTabsRow) 68 else 6).dp, end = if (layout.sideTabsRow) 12.dp else 56.dp),
            enter = slideInHorizontally(tween(WarSize.SLIDE_MS)) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(WarSize.SLIDE_MS)) { it } + fadeOut(),
        ) {
            val pageH = if (layout.compact) maxHeight - (WarSize.TOP_BAR + 80).dp else maxHeight - (WarSize.TOP_BAR + 18).dp - barH
            BookPage(Modifier.width(layout.pageW.dp).height(pageH.coerceAtLeast(160.dp))) {
                when (book.shown(shownSel)) {
                    BookTab.THINGS -> Column(Modifier.fillMaxSize()) {
                        BookHeader(stageName, "Things", "${plural(scene.placements.size, "placed thing")}") {
                            WarToggle("Hidden", ui.showHidden, { onUi(ui.toggleShowHidden()) })
                        }
                        PlaceOutliner(
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
                    }
                    BookTab.SELECTED -> if (shownSel == null) Column {
                        BookHeader(null, "Selected")
                        Text("Tap something in the scene, or in Things, to select it. Pick a card in the catalogue below, then drag it into the scene.",
                            style = warText(14.sp, War.brownSoft))
                    } else PlaceInspector(
                        shownSel, snap, camera.pose(), locked = shownSel.locked,
                        onChange = { p -> state.updatePlacement(sceneId, p.id) { p } },
                        onSlide = { p -> state.updatePlacement(sceneId, p.id, "slide:$sceneId:${p.id}") { p } },
                        onSlideEnd = { state.endGesture() },
                        onNudge = { d -> state.nudgePlacement(sceneId, shownSel.id, d.x, d.y, d.z) },
                        onDuplicate = { state.duplicatePlacement(sceneId, shownSel.id)?.let { onUi(ui.select(it)) } },
                        onDelete = { confirmDelete = shownSel.id },
                        onAnimation = { setBook(BookPages(true, BookTab.ANIMATION)) },
                        onSound = { on -> state.setPlacementSound(sceneId, shownSel.id, on); engine.setSound(shownSel, on) },
                    )
                    BookTab.ANIMATION -> if (shownSel == null || shownSel.kind == PlaceKind.PROP) {
                        Text("Select a person or a creature to give them an animation or an emote.", style = warText(14.sp, War.brownSoft))
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

        // ---- the tool cluster, bottom left over the scene, above the bar; its tooltip strip beside it
        if (placing == null) Row(
            Modifier.align(Alignment.BottomStart).padding(start = WarSize.GUTTER.dp, bottom = barH + 16.dp, end = 64.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val roomH = (boxH - (WarSize.TOP_BAR + 24).dp - barH - 16.dp).coerceAtLeast(64.dp)
            Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (layout.clusterHorizontal && cluster.hint != null) Tooltip(cluster.tip(shownSel))
                ToolClusterView(layout, cluster, shownSel, ::useTool, { cluster = cluster.longPress(it) }, Modifier.heightIn(max = roomH))
            }
            if (!layout.clusterHorizontal && cluster.hint != null) Tooltip(cluster.tip(shownSel), Modifier.weight(1f, fill = false))
        }

        // ---- the catalogue bar, and its flyout over it
        PlaceCatalogueBar(
            library, own, query, { query = it }, prefs,
            onFavourite = { a -> savePrefs(prefs.toggleFavourite(a.key)) },
            thumbs = engine.thumbs, thumbError = thumbError,
            layout = layout, bar = bar, onBar = { setBar(it) },
            armed = placing?.asset?.key,
            onPick = { a -> if (placing?.asset?.key == a.key) stopPlacing() else arm(a) },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { barPx = it.height },
        )
        AnimatedVisibility(
            bar.flyout && library != null,
            Modifier.align(Alignment.BottomStart).padding(start = WarSize.GUTTER.dp, bottom = barH + 4.dp),
            enter = slideInVertically(tween(WarSize.SLIDE_MS)) { it / 3 } + fadeIn(),
            exit = slideOutVertically(tween(WarSize.SLIDE_MS)) { it / 3 } + fadeOut(),
        ) {
            library?.let { lib ->
                PlaceCategoryFlyout(
                    lib, own.size, prefs, query,
                    onPick = { q -> query = q; setBar(bar.closeFlyout()) },
                    onClose = { setBar(bar.closeFlyout()) },
                    modifier = Modifier.width(layout.flyoutW.dp).heightIn(max = (maxHeight - (WarSize.TOP_BAR + 12).dp - barH).coerceAtLeast(160.dp)),
                )
            }
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
                    PlaceAction.EMOTE -> setBook(BookPages(true, BookTab.ANIMATION))
                    PlaceAction.FOCUS -> focus(p)
                }
            }, onDismiss = { menu = null })
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

/** The tool cluster's face for each tool: the housing editor's own buttons for Select, Rotate and Scale, glyphs and icons for the rest. */
fun toolFace(t: BuildTool): Face = when (t) {
    BuildTool.POINTER -> Face(decor = "decor-ability-move-default")
    BuildTool.ROTATE -> Face(decor = "decor-ability-expert-rotate-default")
    BuildTool.SCALE -> Face(decor = "decor-ability-expert-scale-default")
    BuildTool.DELETE -> Face(glyph = WarIcons.DELETE)
    BuildTool.EYEDROPPER -> Face(icon = "tool.eyedropper")
    BuildTool.DUPLICATE -> Face(icon = "tool.duplicate")
    BuildTool.LOCK -> Face(glyph = WarIcons.LOCK)
    BuildTool.HIDE -> Face(icon = "tool.hide")
    BuildTool.EMOTE -> Face(icon = "tool.emote")
    BuildTool.FOCUS -> Face(icon = "tool.focus")
}

/**
 * The round tool cluster on its dark glass panel: [BuildLayout]'s columns and rows (a row along the bottom of a short
 * scene; a column that scrolls when even that won't fit), the mode lit gold, what can't be used now dimmed.
 */
@Composable
fun ToolClusterView(layout: BuildLayout, cluster: ToolCluster, sel: Placement?, onTap: (BuildTool) -> Unit, onLong: (BuildTool) -> Unit, modifier: Modifier = Modifier) {
    val tools = BuildTool.values().toList()
    val button: @Composable (BuildTool) -> Unit = { t ->
        RoundIconButton(toolFace(t), { onTap(t) }, lit = cluster.lit(t, sel), enabled = cluster.enabled(t, sel), description = t.label, onLongPress = { onLong(t) })
    }
    GlassPanel(modifier) {
        when {
            layout.clusterHorizontal -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tools.forEach { button(it) } }
            else -> {
                val perCol = if (layout.clusterScrolls) tools.size else (tools.size + layout.clusterCols - 1) / layout.clusterCols
                Row(Modifier.verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in tools.chunked(perCol)) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { c.forEach { button(it) } }
                }
            }
        }
    }
}

/** The stage picker: the studio, Dalaran's places and every location's views, as rows on a page; the current one lit. */
@Composable
fun StagePicker(stages: List<Pair<String, String>>, current: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
    WarDialog(onCancel) {
        BookHeader("The stage", "Where it happens")
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stages, key = { it.first }) { (id, name) ->
                WarRow({ onPick(id) }, selected = id == current, icon = if (StageChoices.locationOf(id) != null) "menu.location" else if (id == EditorChoices.STUDIO) "menu.studio" else "tool.stage") {
                    Text(name, style = warText(16.sp), maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }
        PillButton("Close", onCancel, Modifier.align(Alignment.CenterHorizontally).width(160.dp))
    }
}

/**
 * The placing mode's strip under the top bar: "Placing: <name>", the ghost's turn (a step each way), Drop (one at the
 * crosshair), Done; under it the mode's line (loading, why the model can't be drawn, how many were placed).
 */
@Composable
private fun PlacingBar(p: Placing, onTurn: (Float) -> Unit, turnStep: Float, onDrop: () -> Unit, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GlassStrip {
            Text(p.title, style = warText(15.sp, War.gold, true), maxLines = 1, modifier = Modifier.widthIn(max = 220.dp))
            RoundIconButton(Face(glyph = WarIcons.UNDO), { onTurn(turnStep) }, size = 48.dp, enabled = p.error == null, description = "Turn left")
            RoundIconButton(Face(glyph = WarIcons.REDO), { onTurn(-turnStep) }, size = 48.dp, enabled = p.error == null, description = "Turn right")
            RoundIconButton(Face(glyph = WarIcons.PLUS), onDrop, size = 48.dp, enabled = p.canDrop, description = "Drop")
            RoundIconButton(Face(glyph = WarIcons.CHECK), onDone, size = 48.dp, description = "Done")
        }
        Text(p.line, style = warText(12.sp, if (p.error != null) Color(0xFFFF8A6A) else War.ink, true), maxLines = 2)
    }
}

/** The stage's files and a placed thing's on their way, and what went wrong (a tap clears the errors). */
@Composable
fun PlaceLoading(s: PlaceStatus, onClear: () -> Unit, modifier: Modifier = Modifier) {
    if (!s.loadingStage && s.fetchTotal == 0 && s.errors.isEmpty() && !s.soundPreparing) return
    Column(
        modifier.widthIn(max = 520.dp).warPress(onClick = onClear).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlassStrip {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                if (s.loadingStage) {
                    val mb = if (s.stageBytesTotal > 0) " (%.1f of %.1f MB)".format(s.stageBytesDone / 1e6, s.stageBytesTotal / 1e6) else ""
                    Text(
                        if (s.stageTotal > 0) "Fetching ${s.stageName}: ${s.stageDone} of ${s.stageTotal} files$mb" else "Loading ${s.stageName}…",
                        style = warText(13.sp, War.gold, true),
                    )
                    LoadingBar(if (s.stageTotal > 0) s.stageDone.toFloat() / s.stageTotal else 0f, Modifier.width(320.dp).height(30.dp))
                }
                if (s.fetchTotal > 0) Text("Fetching a placed thing's files… ${s.fetchDone} of ${s.fetchTotal}", style = warText(12.sp, War.ink, true))
                if (s.soundPreparing) Text("Preparing sounds…", style = warText(12.sp, War.ink, true))
                for (e in s.errors) Text(e, style = warText(12.sp, Color(0xFFFF8A6A), true))
                if (s.errors.isNotEmpty()) Text("Dismiss", style = warText(13.sp, War.gold, true), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}
