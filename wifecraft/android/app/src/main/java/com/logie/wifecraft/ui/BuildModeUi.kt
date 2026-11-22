package com.logie.wifecraft.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.BuildConfig
import com.logie.wifecraft.engine.Hit
import com.logie.wifecraft.engine.Native
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.BuildActors
import com.logie.wifecraft.simulation.BuildBarTool
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.MoveTarget
import com.logie.wifecraft.simulation.SpotEdits
import com.logie.wifecraft.simulation.SpotKind
import com.logie.wifecraft.simulation.Spots
import com.logie.wifecraft.simulation.Sparkles
import com.logie.wifecraft.simulation.UserSpot
import com.logie.wifecraft.simulation.BuildDoc
import com.logie.wifecraft.simulation.BuildDocs
import com.logie.wifecraft.simulation.BuildEdits
import com.logie.wifecraft.simulation.BuildGizmo
import com.logie.wifecraft.simulation.BuildGizmoDrag
import com.logie.wifecraft.simulation.BuildMarker
import com.logie.wifecraft.simulation.BuildPhase
import com.logie.wifecraft.simulation.BuildPose
import com.logie.wifecraft.simulation.BuildReport
import com.logie.wifecraft.simulation.BuildReports
import com.logie.wifecraft.simulation.BuildSelection
import com.logie.wifecraft.simulation.BuildState
import com.logie.wifecraft.simulation.BuildStore
import com.logie.wifecraft.simulation.BuildView
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.GizmoHandle
import com.logie.wifecraft.simulation.ItemClass
import com.logie.wifecraft.simulation.ItemFrame
import com.logie.wifecraft.simulation.ItemInteraction
import com.logie.wifecraft.simulation.ItemMapping
import com.logie.wifecraft.simulation.ItemOverrides
import com.logie.wifecraft.simulation.ItemReports
import com.logie.wifecraft.simulation.ItemTarget
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.LocalOffset
import com.logie.wifecraft.simulation.MarkerType
import com.logie.wifecraft.simulation.PairMove
import com.logie.wifecraft.simulation.PlayingPair
import com.logie.wifecraft.simulation.RaceOffset
import com.logie.wifecraft.simulation.RaceOffsetStore
import com.logie.wifecraft.simulation.RaceOffsets
import com.logie.wifecraft.simulation.RaceScope
import com.logie.wifecraft.simulation.ReportQueue
import com.logie.wifecraft.simulation.RoleActor
import com.logie.wifecraft.simulation.RoleDelta
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SendOutcome
import com.logie.wifecraft.simulation.SendResult
import com.logie.wifecraft.simulation.SizeClass
import com.logie.wifecraft.simulation.SupportClass
import com.logie.wifecraft.simulation.UrlTransport
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The live scene's build mode (simulation/BuildMode*.kt for its state, data, maths and report). The HUD's Build chip turns it
 * on: the camera orbits round the selected thing (one finger orbits, two pan and pinch; a tap selects and re-centres), any
 * item of the place can be selected (the game's own props too) and given an interaction (Interact: its class, the animation
 * classes it takes, its surface's height: it takes effect at once), location markers are placed (Marker) and moved with the
 * axis gizmo (X red, Y green, Z blue, the ring turns; snapped), a playing pair is moved live with the same gizmo (it keeps
 * playing) and its correction remembered for the races in it, and Send files it all as one GitHub issue. Move mode (the item
 * mapper, ui/ItemMapperUi.kt) is part of it: a selected scene object can hold the controlled NPC for an item report. The
 * scene never pauses for it. Main thread, but for the files and the network.
 */
class BuildController internal constructor(
    private val context: Context, private val live: LiveScene, private val view: SceneView, private val scope: CoroutineScope,
) {
    var on by mutableStateOf(false)
        private set
    var state by mutableStateOf(BuildState())
        private set
    /** The markers shown in normal play too (small), and the shipped ones of this place. */
    var showMarkers by mutableStateOf(false)
    var shipped by mutableStateOf<List<BuildMarker>>(emptyList())
        private set
    /** The phone's item interactions (every place's, by key) and remembered placements; the shipped placements. */
    var items by mutableStateOf<Map<String, ItemInteraction>>(emptyMap())
        private set
    var raceLocal by mutableStateOf<List<RaceOffset>>(emptyList())
        private set
    @Volatile private var raceShipped: List<RaceOffset> = emptyList()
    @Volatile private var raceMine: List<RaceOffset> = emptyList()
    /** What the selected NPC plays (refreshed after every move), the consent question, the report's notes. */
    var playing by mutableStateOf<PlayingPair?>(null)
        private set
    var askConsent by mutableStateOf(false)
        private set
    var notes by mutableStateOf("")
    /** The new marker's type (Marker tool). */
    var markerType by mutableStateOf(MarkerType.STAND)
    /** Who a gizmo drag moves in a pair: both (the shared origin), the selected NPC alone or its partner alone. */
    var target by mutableStateOf(MoveTarget.BOTH)
    /** Where the selected NPC stands when it plays nothing (the gizmo moves it alone). */
    var soloPose by mutableStateOf<BuildPose?>(null)
        private set
    /** The New tab: the type picked, a custom label and its class, and whether the next tap in the scene places it. */
    var newKind by mutableStateOf(SpotKind.CHAIR)
    var newLabel by mutableStateOf("")
    var newClass by mutableStateOf(ItemClass.FLOOR)
    var armed by mutableStateOf(false)
    /** Spots shipped with the app (assets/user_spots.json) for this place; the spot in use for each scene object (the one last tapped). */
    var shippedSpots by mutableStateOf<List<UserSpot>>(emptyList())
        private set
    var activeSpots by mutableStateOf<Map<SceneObject, String>>(emptyMap())
        private set
    /** The global switch for sparkles (Options' Graphics and the New tab); default on. */
    val sparklesOn: Boolean get() = SparkleSwitch.on
    /** The scene's own spots as measured (the simulation's frame): what a scene object goes back to when no spot is in use. */
    internal var baseSpots: Map<SceneObject, InteractionSpot> = emptyMap()
    /** Every spot of this place: the shipped, then the player's. */
    val allSpots: List<UserSpot> get() = shippedSpots + state.doc.spots.values
    /** Changes whenever the spots or the one in use change (the screen's furniture is made again). */
    val spotSig: Int get() = allSpots.hashCode() * 31 + activeSpots.hashCode()

    // the screen's world: the place's id and name, the simulation's frame, the cast
    var placeId = ""
        internal set
    var placeName = ""
        internal set
    internal var toStage: (Vec3) -> Vec3 = { it }
    internal var toLocal: (Vec3) -> Vec3 = { it }
    internal var stageRotation = 0f
    /** Who each NPC is for a remembered placement (race, sex) and their names. */
    @Volatile internal var cast: Map<String, RoleActor> = emptyMap()
    internal var names: Map<String, String> = emptyMap()
    /** A scene object's item key (the item mapper's) and its furniture now (the simulation's frame). */
    internal var keyOf: (SceneObject) -> String = { it.name }
    internal var furnitureOf: (SceneObject) -> Furniture? = { null }

    private val store get() = BuildStore(File(context.filesDir, "build"))
    private val queue get() = ReportQueue(File(File(context.filesDir, "build"), "queue"))
    private val prefs get() = context.getSharedPreferences("item_reports", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    /** Items saved by earlier sessions (this session's are the doc's). */
    private var earlierItems: Map<String, ItemInteraction> = emptyMap()
    private var camera: Triple<Vec3, Vec3, Float>? = null
    private var viewW = 1f
    private var viewH = 1f
    private var drag: BuildGizmoDrag? = null
    private var reportId = newId()

    /** The camera as last drawn, for the gizmo's maths ([w] x [h] pixels). */
    private fun buildView(): BuildView? = camera?.let { (eye, target, fov) -> BuildView(eye, target, fov, viewW, viewH) }

    // ---- on and off

    fun enter() {
        if (on) return
        on = true
        state = state.copy(phase = if (state.selection != null) BuildPhase.SELECTED else BuildPhase.IDLE, message = null)
        live.camera.clearPivot()
        push()
    }

    fun leave() {
        if (!on) return
        endDragIfAny()
        armed = false
        on = false
        state = state.select(null)
        live.camera.clearPivot()
        playing = null
        askConsent = false
        push()
    }

    fun toggle() { if (on) leave() else enter() }

    /** The camera as the renderer last drew it, kept fresh while build mode is on (GL thread round trip). */
    fun refreshCamera() {
        view.cameraState { c -> camera = Triple(c.eye, c.target, c.fovY) }
        val sel = state.selection
        if (sel is BuildSelection.Npc) refreshPlaying(sel.key)
    }

    fun viewSize(w: Float, h: Float) { viewW = w.coerceAtLeast(1f); viewH = h.coerceAtLeast(1f) }

    // ---- the files

    fun load(locationName: String, id: String) {
        placeName = locationName
        placeId = id
        scope.launch {
            val (doc, all, mine, ship, marks) = withContext(Dispatchers.IO) {
                val s = store
                val d = runCatching { s.loadDoc(id) }.getOrDefault(BuildDoc())
                val i = runCatching { s.loadItems() }.getOrDefault(emptyMap())
                val r = runCatching { s.raceOffsets.load() }.getOrDefault(emptyList())
                val sh = runCatching { context.assets.open("race_offsets.json").bufferedReader().use { RaceOffsets.parse(it.readText()) } }.getOrDefault(emptyList())
                val m = runCatching { context.assets.open("place_markers.json").bufferedReader().use { BuildDocs.shippedMarkers(it.readText())[id] } }.getOrNull().orEmpty()
                Five(d, i, r, sh, m)
            }
            earlierItems = all - doc.items.keys
            state = state.copy(doc = doc)
            items = earlierItems + doc.items
            raceLocal = mine; raceMine = mine
            raceShipped = ship
            shipped = marks
            shippedSpots = runCatching { context.assets.open("user_spots.json").bufferedReader().use { Spots.shipped(it.readText())[id] } }.getOrNull().orEmpty()
            SparkleSwitch.load(context)
            showMarkers = runCatching { prefs.getBoolean(KEY_SHOW, false) }.getOrDefault(false)
            installPlacement()
            push()
        }
    }

    private data class Five<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    /** The simulation asks this for remembered placements (GL thread: only the volatile lists are read). */
    private fun installPlacement() {
        live.command { sim ->
            sim.placement = { animation, role, actorId, otherId, furniture ->
                val who = cast[actorId]
                if (who == null) null else {
                    val other = otherId?.let { cast[it] }?.race ?: -1
                    val item = furniture?.let { runCatching { keyOf(it) }.getOrNull() }
                    RaceOffsets.lookup(raceMine, raceShipped, animation, role, who.copy(otherRace = other),
                        RaceOffsets.furnitureClass(furniture), item).first?.delta
                }
            }
        }
    }

    /** The phone's items as overrides of the furniture system (their keys; a doodad's stands in for its slot: [overlay]). */
    fun overlay(raw: Map<SceneObject, Furniture>): Map<SceneObject, Furniture> {
        if (items.isEmpty()) return raw
        val out = HashMap(raw)
        for ((item, f) in raw) items[keyOf(item)]?.let { out[item] = ItemOverrides.apply(f, it.override()) }
        // a doodad given an interaction takes the place of its slot's object (any prop can be made a bed)
        for (i in items.values) {
            val slot = i.slot ?: continue
            if (!i.key.startsWith("$placeId/") || i.key == keyOf(slot)) continue
            val at = toLocal(Vec3(i.frame.x, i.frame.y, i.frame.z))
            val base = Furniture(slot.name, com.logie.wifecraft.simulation.FurnitureSpec.standard(i.cls.kind, surface = i.surface.takeIf { it > 0f }),
                at.x, at.y, at.z, ItemFrame.wrapRad(i.frame.yaw - stageRotation))
            out[slot] = ItemOverrides.apply(base, i.override())
        }
        // a spot made in the New tab stands in for its scene object while it is the one in use (or when the scene has none of its own)
        for (sp in allSpots) {
            val slot = sp.slot
            val inUse = activeSpots[slot] == sp.id
            val orphan = raw[slot] == null && out[slot] == null && activeSpots[slot] == null && allSpots.first { it.slot == slot } === sp
            if (inUse || orphan) out[slot] = sp.furniture(toLocal, stageRotation)
        }
        return out
    }

    /** The spot under a stage point ([x], [y], [z]) that a body could use, or null. */
    fun spotUnder(x: Float, y: Float, z: Float): UserSpot? = Spots.hit(allSpots, x, y, z)

    /** [sp] is the one in use for its scene object: its furniture and floor spot take the place of the scene's own. */
    fun activate(sp: UserSpot) {
        if (activeSpots[sp.slot] == sp.id) return
        activeSpots = activeSpots + (sp.slot to sp.id)
        applySpots()
    }

    /** The scene's own object [slot] was used: a spot no longer stands in for it. */
    fun deactivate(slot: SceneObject): Boolean {
        if (slot !in activeSpots) return false
        activeSpots = activeSpots - slot
        applySpots()
        return true
    }

    /** The simulation's interaction spots set for the scene objects a spot stands in for (back to the scene's own when none is). */
    fun applySpots() {
        val active = activeSpots.mapNotNull { (slot, id) -> allSpots.firstOrNull { it.id == id }?.let { slot to Spots.interactionSpot(slot, it.furniture(toLocal, stageRotation)) } }.toMap()
        val base = baseSpots
        live.command { sim ->
            for (slot in SceneObject.entries) (active[slot] ?: base[slot])?.let { sim.setSpot(slot, it) }
        }
    }

    /** The furniture [sp] is, in the simulation's frame. */
    fun spotFurniture(sp: UserSpot): Furniture = sp.furniture(toLocal, stageRotation)

    /** The sparkles' sources: the scene's usable furniture (not while held) and the spots, in the stage's frame. */
    fun sparkleSources(furniture: Map<SceneObject, Furniture>, usable: Set<SceneObject>, holders: Set<SceneObject>): List<Sparkles.Source> {
        val out = ArrayList<Sparkles.Source>()
        for (sp in allSpots) {
            out += Sparkles.Source(sp.key, Vec3(sp.x, sp.y, sp.top), sp.halfX, sp.halfY, sp.yaw, occupied = sp.slot in holders && activeSpots[sp.slot] == sp.id, enabled = sp.sparkle)
        }
        for ((item, f) in furniture) {
            if (item !in usable || !Sparkles.usable(f)) continue
            // (a spot standing in for it is drawn as the spot)
            if (allSpots.any { it.slot == item && kotlin.math.abs(spotFurniture(it).x - f.x) < 1e-3f && kotlin.math.abs(spotFurniture(it).y - f.y) < 1e-3f }) continue
            val at = toStage(Vec3(f.x, f.y, f.z + f.spec.surface))
            out += Sparkles.Source("scene:${item.name}", at, f.spec.halfX, f.spec.halfY, ItemFrame.wrapRad(f.yaw + stageRotation), occupied = item in holders)
        }
        return out
    }

    /** The override build mode gave [item] (null: none), for the animation picker's filter. */
    fun overrideOf(item: SceneObject) = items[keyOf(item)]?.override()

    private fun persist() {
        val doc = state.doc
        items = earlierItems + doc.items
        val all = items
        val id = placeId; val name = placeName
        scope.launch(Dispatchers.IO) {
            runCatching {
                val s = store
                s.saveDoc(id, name, doc)
                File(File(context.filesDir, "build"), "items.json").also { f ->
                    f.parentFile?.mkdirs()
                    f.writeText(BuildDocs.itemsJson(all.values))
                }
            }
        }
        push()
    }

    // ---- the overlay drawn by the engine (buildview.cpp)

    fun push() {
        val visible = on || showMarkers
        val sel = state.selection
        val markers = if (!visible) null else {
            val list = shipped + state.doc.markers
            val spots = if (on) allSpots else emptyList()
            FloatArray((list.size + spots.size) * 8).also { a ->
                list.forEachIndexed { i, m ->
                    val k = i * 8
                    a[k] = m.pose.x; a[k + 1] = m.pose.y; a[k + 2] = m.pose.z; a[k + 3] = m.pose.yaw
                    val dim = if (m.shipped) 0.7f else 1f
                    a[k + 4] = m.type.r * dim; a[k + 5] = m.type.g * dim; a[k + 6] = m.type.b * dim
                    a[k + 7] = if (on && sel is BuildSelection.Marker && sel.key == m.id) 1f else 0f
                }
                // the New tab's spots: gold pins on their surfaces
                spots.forEachIndexed { i, sp ->
                    val k = (list.size + i) * 8
                    a[k] = sp.x; a[k + 1] = sp.y; a[k + 2] = sp.top; a[k + 3] = sp.yaw
                    a[k + 4] = 1f; a[k + 5] = 0.82f; a[k + 6] = 0.25f
                    a[k + 7] = if (sel is BuildSelection.Spot && sel.key == sp.id) 1f else 0f
                }
            }
        }
        val box = (sel as? BuildSelection.Item)?.takeIf { on }?.let { i ->
            val c = i.frame.toWorld(LocalOffset(0f, 0f, i.top / 2f, 0f)).first
            floatArrayOf(c.x, c.y, c.z, i.halfX, i.halfY, i.top / 2f, i.frame.yaw)
        } ?: (sel as? BuildSelection.Spot)?.takeIf { on }?.let { sp -> allSpots.firstOrNull { it.id == sp.key } }?.let { sp ->
            val h = maxOf(sp.surface, 0.04f) / 2f
            floatArrayOf(sp.x, sp.y, sp.z + h, sp.halfX, sp.halfY, h, sp.yaw)
        }
        val pose = if (on) gizmoPose() else null
        val flags = if (pose == null) 0 else Native.BUILD_ARROWS or Native.BUILD_RING
        view.setBuild(flags, pose?.x ?: 0f, pose?.y ?: 0f, pose?.z ?: 0f, pose?.yaw ?: 0f, state.handle?.axis ?: -1, markers, box)
    }

    /** Where the gizmo is (the place's frame): the marker, the item's interaction anchor, the playing pair's origin. Null: none. */
    fun gizmoPose(): BuildPose? = when (val s = state.selection) {
        is BuildSelection.Marker -> (state.doc.markers.firstOrNull { it.id == s.key } ?: shipped.firstOrNull { it.id == s.key })?.pose
        is BuildSelection.Item -> state.doc.items[s.key]?.let { i ->
            val (at, yaw) = s.frame.toWorld(i.anchor)
            BuildPose.of(at, yaw)
        }
        is BuildSelection.Npc -> playing?.takeIf { s.playing }?.let { p ->
            val role = target.role(p.roleOf(s.key)).takeIf { it in p.roles.indices }
            if (role == null) BuildPose.of(toStage(p.anchor), p.heading + stageRotation) else {
                val w = BuildActors.poseOf(p.anchor, p.heading, p.roles[role], p.fixed.getOrElse(role) { RoleDelta.ZERO }, p.extra.getOrElse(role) { RoleDelta.ZERO })
                BuildPose.of(toStage(w.at), w.yaw + stageRotation)
            }
        } ?: soloPose
        is BuildSelection.Spot -> allSpots.firstOrNull { it.id == s.key }?.let { BuildPose(it.x, it.y, it.top, it.yawDeg) }
        null -> null
    }

    // ---- touches (SceneSessionScreen's gesture loop)

    /** A finger down at ([x], [y]): a gizmo handle under it is taken (true: the drag is the gizmo's, not the camera's). */
    fun grab(x: Float, y: Float, tolerancePx: Float): Boolean {
        if (!on) return false
        val pose = gizmoPose() ?: return false
        val v = buildView() ?: return false
        val h = BuildGizmo.hit(v, pose.at, pose.yaw, x, y, tolerancePx) ?: return false
        drag = BuildGizmoDrag(h, pose, x, y, state.snap)
        state = state.beginDrag(h)
        push()
        return true
    }

    fun dragTo(x: Float, y: Float) {
        val d = drag ?: return
        val v = buildView() ?: return
        apply(d.poseAt(v, x, y), dragging = true)
    }

    fun endDrag() {
        if (drag == null) return
        drag = null
        state = state.endDrag()
        persist()
    }

    private fun endDragIfAny() { if (drag != null) endDrag() }

    /** A tap: a marker under it is selected first (markers aren't picked by the engine); true when it took the tap. */
    fun tapMarker(x: Float, y: Float, tolerancePx: Float): Boolean {
        if (!on) return false
        val v = buildView() ?: return false
        var best: BuildMarker? = null
        var bestD = tolerancePx
        for (m in shipped + state.doc.markers) {
            val p = v.project(m.pose.at) ?: continue
            val d = hypot(p.x - x, p.y - y)
            if (d < bestD) { bestD = d; best = m }
        }
        val m = best ?: return false
        selectMarker(m)
        return true
    }

    fun selectMarker(m: BuildMarker) {
        state = state.select(BuildSelection.Marker(m.id, m.name))
        live.camera.setPivot(m.pose.x, m.pose.y, m.pose.z)
        push()
    }

    /**
     * The engine's pick under a tap: an NPC, an item (a scene object, a location doodad, a placed prop), the floor. [objectHits]:
     * the scene objects by their pick keys.
     */
    fun onTap(hit: Hit?, objectHits: Map<String, SceneObject>, actorOf: (String) -> String?) {
        if (!on || state.phase == BuildPhase.DRAGGING) return
        // the New tab armed: this tap places the spot (on the floor or on whatever was hit); otherwise a spot under the tap is selected
        if (hit != null && hit.kind != Hit.Kind.ACTOR) {
            if (armed && state.tool == BuildBarTool.NEW) { placeSpot(hit); return }
            val sp = spotUnder(hit.x, hit.y, hit.z)
            if (sp != null && hit.kind == Hit.Kind.GROUND) { selectSpot(sp); return }
        }
        when (hit?.kind) {
            Hit.Kind.ACTOR -> {
                val id = hit.key?.let(actorOf) ?: return
                target = MoveTarget.BOTH
                armed = false
                state = state.select(BuildSelection.Npc(id, names[id] ?: id, false))
                live.camera.setPivot(hit.x, hit.y, hit.z)
                refreshPlaying(id)
            }
            Hit.Kind.LOCATION, Hit.Kind.PROP -> {
                val key = hit.key ?: return
                val obj = objectHits[key]
                if (obj != null) selectObject(obj, hit) else {
                    val index = key.removePrefix("location:").toIntOrNull()
                    if (hit.kind == Hit.Kind.LOCATION && index != null && index >= 0) {
                        view.locationObjectInfo(index) { v -> if (v != null) selectDoodad(index, v, hit) else selectNothing(hit) }
                    } else selectNothing(hit)
                }
            }
            Hit.Kind.GROUND -> {
                if (state.tool == BuildBarTool.MARKER) addMarkerAt(BuildPose(hit.x, hit.y, hit.z, cameraFacingDeg(Vec3(hit.x, hit.y, hit.z))))
                else selectNothing(hit)
            }
            null -> selectNothing(null)
        }
    }

    private fun selectNothing(hit: Hit?) {
        state = state.select(null)
        playing = null
        if (hit != null) live.camera.setPivot(hit.x, hit.y, hit.z) else live.camera.clearPivot()
        push()
    }

    private fun selectObject(obj: SceneObject, hit: Hit) {
        val f = furnitureOf(obj)
        val frame = if (f != null) {
            val at = toStage(Vec3(f.x, f.y, f.z))
            ItemFrame(at.x, at.y, at.z, ItemFrame.wrapRad(f.yaw + stageRotation))
        } else ItemFrame(hit.x, hit.y, hit.z, 0f)
        val spec = f?.spec
        state = state.select(BuildSelection.Item(keyOf(obj), obj.label, obj.model, hit.key ?: obj.name, obj, frame,
            spec?.halfX ?: 0.5f, spec?.halfY ?: 0.5f, spec?.top?.takeIf { it > 0f } ?: 0.6f))
        live.camera.setPivot(frame.x, frame.y, frame.z + (spec?.surface ?: 0.4f))
        playing = null
        push()
    }

    /** A location doodad ([v]: model, origin, yaw, scale, its model's box): selectable like any item. */
    private fun selectDoodad(index: Int, v: FloatArray, hit: Hit) {
        val model = v[0].toInt()
        val scale = v[5].takeIf { it > 1e-3f } ?: 1f
        val yaw = v[4]
        // the box's middle on the floor, in the place's frame
        val cx = (v[6] + v[9]) / 2f * scale; val cy = (v[7] + v[10]) / 2f * scale
        val c = cos(yaw); val s = sin(yaw)
        val frame = ItemFrame(v[1] + cx * c - cy * s, v[2] + cx * s + cy * c, v[3] + v[8] * scale, yaw)
        val key = ItemMapping.key(placeId, "doodad:$index", model)
        state = state.select(BuildSelection.Item(key, items[key]?.name ?: "Prop $model", model, hit.key ?: "location:$index", null, frame,
            (v[9] - v[6]) / 2f * scale, (v[10] - v[7]) / 2f * scale, (v[11] - v[8]) * scale))
        live.camera.setPivot(frame.x, frame.y, frame.z + (v[11] - v[8]) * scale / 2f)
        playing = null
        push()
    }

    private fun refreshPlaying(id: String) {
        live.command { sim ->
            val p = sim.playingOf(id)
            val at = if (p == null) sim.placeOf(id) else null
            main.post {
                val sel = state.selection
                if (sel is BuildSelection.Npc && sel.key == id) {
                    playing = p
                    if (state.phase != BuildPhase.DRAGGING) soloPose = at?.let { (v, f) -> BuildPose.of(toStage(v), f + stageRotation) }
                    if (p == null && target != MoveTarget.BOTH) target = MoveTarget.BOTH
                    if (sel.playing != (p != null)) state = state.copy(selection = BuildSelection.Npc(id, sel.name, p != null))
                    push()
                }
            }
        }
    }

    /** Facing toward the camera from [at] (degrees): a new marker looks at the player. */
    private fun cameraFacingDeg(at: Vec3): Float {
        val eye = camera?.first ?: return 0f
        return ItemFrame.wrapDeg(Math.toDegrees(atan2((eye.y - at.y).toDouble(), (eye.x - at.x).toDouble())).toFloat())
    }

    // ---- edits

    /** The selected thing put at [p] (a drag's step, or the numeric fields' Set: one undo step). */
    fun apply(p: BuildPose, dragging: Boolean = false) {
        val change: (BuildDoc) -> BuildDoc = changeFor(p) ?: return
        state = if (dragging) state.dragged(change) else state.edit(change)
        if (!dragging) persist() else push()
    }

    /** What moving the selection to [p] does to the edits (a playing pair is moved in the scene at once); null: nothing to move. */
    private fun changeFor(p: BuildPose): ((BuildDoc) -> BuildDoc)? {
        when (val s = state.selection) {
            is BuildSelection.Marker -> return { d -> BuildEdits.moveMarker(d, s.key, p) }
            is BuildSelection.Item -> return { d ->
                val i = d.items[s.key]
                if (i == null) d else {
                    val local = s.frame.toLocal(p.at, p.yaw)
                    // a seat's or a bed's surface follows its anchor's height (the Z arrow sets it)
                    val surface = if (i.cls == ItemClass.FLOOR || i.cls == ItemClass.WALL) i.surface else local.z
                    BuildEdits.setItem(d, i.copy(anchor = local, surface = surface))
                }
            }
            is BuildSelection.Spot -> return { d -> SpotEdits.move(d, s.key, p) }
            is BuildSelection.Npc -> {
                val pair = playing
                if (pair == null || !s.playing) {
                    // not playing: put where it stands (it stays there; nothing sends it on while Build mode holds the scene)
                    val local = toLocal(p.at)
                    val facing = ItemFrame.wrapRad(p.yaw - stageRotation)
                    live.command { sim -> sim.placeActor(s.key, local, facing) }
                    soloPose = p
                    return { d -> BuildEdits.moveNpc(d, s.key, p) }
                }
                val role = target.role(pair.roleOf(s.key)).takeIf { it in pair.roles.indices }
                val roles = listOfNotNull(pair.lead, pair.other).map { cast[it] ?: RoleActor(0, false) }
                val from = BuildPose.of(toStage(pair.baseAnchor), pair.baseHeading + stageRotation)
                val itemKey = pair.furniture?.let { runCatching { keyOf(it) }.getOrNull() }
                if (role == null) {
                    // both: the shared origin (each one's own nudge stays as it was)
                    val local = toLocal(p.at)
                    val heading = ItemFrame.wrapRad(p.yaw - stageRotation)
                    live.command { sim -> sim.movePlaying(s.key, local, heading) }
                    val now = pair.copy(anchor = local, heading = heading)
                    playing = now
                    val move = PairMove(s.key, pair.name, roles, from, p, itemKey, now.extra, BuildActors.moveKind(now))
                    return { d -> BuildEdits.movePair(d, move) }
                }
                // one alone: its own nudge from the pair's placement, both clips on the same clock, the origin where it was
                val sim = BuildPose(toLocal(p.at).x, toLocal(p.at).y, toLocal(p.at).z, ItemFrame.wrapDeg(p.yawDeg - Math.toDegrees(stageRotation.toDouble()).toFloat()))
                val extra = BuildActors.extraFor(pair.anchor, pair.heading, pair.roles[role], pair.fixed.getOrElse(role) { RoleDelta.ZERO }, sim)
                val extras = BuildActors.withExtra(pair.extra, role, extra)
                live.command { m -> m.movePlaying(s.key, pair.anchor, pair.heading, extras) }
                val now = pair.copy(extra = extras)
                playing = now
                val move = PairMove(s.key, pair.name, roles, from, BuildPose.of(toStage(pair.anchor), pair.heading + stageRotation), itemKey, extras, BuildActors.moveKind(now))
                return { d -> BuildEdits.movePair(d, move) }
            }
            null -> return null
        }
    }

    fun setSnap(s: com.logie.wifecraft.simulation.BuildSnap) { state = state.withSnap(s) }
    fun setTool(t: BuildBarTool) { state = state.withTool(t) }

    fun undo() { state = state.undo(); afterHistory() }
    fun redo() { state = state.redo(); afterHistory() }

    /** After undo or redo: the live pairs put where the edits now say (a pair no longer moved goes back to its computed place). */
    private fun afterHistory() {
        val pairs = state.doc.pairs
        val sel = state.selection
        if (sel is BuildSelection.Npc && sel.playing) {
            val m = pairs[sel.key]
            if (m != null) {
                val local = toLocal(m.to.at)
                val heading = ItemFrame.wrapRad(m.to.yaw - stageRotation)
                val extras = BuildActors.withExtra(BuildActors.withExtra(m.roleDeltas, 0, m.roleDeltas.getOrElse(0) { RoleDelta.ZERO }), 1, m.roleDeltas.getOrElse(1) { RoleDelta.ZERO })
                live.command { sim -> sim.movePlaying(sel.key, local, heading, extras) }
            } else live.command { sim -> sim.resetPlaying(sel.key) }
            refreshPlaying(sel.key)
        } else if (sel is BuildSelection.Npc) {
            state.doc.npcMoves[sel.key]?.let { p ->
                val local = toLocal(p.at); val facing = ItemFrame.wrapRad(p.yaw - stageRotation)
                live.command { sim -> sim.placeActor(sel.key, local, facing) }
                refreshPlaying(sel.key)
            }
        }
        if (sel is BuildSelection.Spot && state.doc.spots[sel.key] == null) state = state.select(null)
        applySpots()
        persist()
    }

    /** A marker of [markerType] at [p] (on the floor where the finger tapped, or at the selection / the camera's centre). */
    fun addMarkerAt(p: BuildPose) {
        val t = markerType
        val id = "m" + newId().take(8)
        val itemKey = (state.selection as? BuildSelection.Item)?.key
        val m = BuildMarker(id, BuildEdits.markerName(state.doc, t), t, p, itemKey)
        state = state.edit { BuildEdits.addMarker(it, m) }.select(BuildSelection.Marker(m.id, m.name))
        live.camera.setPivot(p.x, p.y, p.z)
        persist()
    }

    /** Add marker: at the selection's gizmo, else the item's top, else where the camera looks (its centre). */
    fun addMarkerHere() {
        val pose = gizmoPose()
            ?: (state.selection as? BuildSelection.Item)?.let { BuildPose.of(it.frame.toWorld(LocalOffset(0f, 0f, it.top, 0f)).first, it.frame.yaw) }
            ?: live.camera.pivot?.let { BuildPose(it[0], it[1], it[2], 0f) }
            ?: camera?.let { (_, target, _) -> BuildPose(target.x, target.y, target.z, cameraFacingDeg(target)) }
            ?: return
        addMarkerAt(pose.copy(yawDeg = if (pose.yawDeg == 0f) cameraFacingDeg(pose.at) else pose.yawDeg))
    }

    // ---- the New tab: spots

    /** The type picked: the next tap in the scene places it (a Custom one needs its label first). */
    fun arm(kind: SpotKind) {
        newKind = kind
        armed = true
        state = state.select(null).withTool(BuildBarTool.NEW)
        push()
    }

    /** A spot of the picked type at the engine's hit (the floor, or any prop: its top gives the surface's height). */
    fun placeSpot(hit: Hit) {
        val floorZ = toStage(Vec3(0f, 0f, 0f)).z
        val onFloor = hit.kind == Hit.Kind.GROUND
        val sp = Spots.make("s" + newId().take(8), newKind, newLabel, newClass, hit.x, hit.y, if (onFloor) hit.z else floorZ,
            hit.z, cameraFacingDeg(Vec3(hit.x, hit.y, hit.z)))
        // (the floor under a prop's hit: the stage's floor; a spot on the ground has none above it)
        armed = false
        state = state.edit { SpotEdits.add(it, sp) }.select(BuildSelection.Spot(sp.id, sp.name))
        live.camera.setPivot(sp.x, sp.y, sp.top)
        persist()
        applySpots()
    }

    fun selectSpot(sp: UserSpot) {
        armed = false
        state = state.select(BuildSelection.Spot(sp.id, sp.name))
        live.camera.setPivot(sp.x, sp.y, sp.top)
        push()
    }

    private fun editSpot(id: String, change: (UserSpot) -> UserSpot) {
        val cur = state.doc.spots[id] ?: return
        val next = change(cur)
        if (next == cur) return
        state = state.edit { SpotEdits.update(it, next) }
        val sel = state.selection
        if (sel is BuildSelection.Spot && sel.key == id && sel.name != next.name) state = state.copy(selection = BuildSelection.Spot(id, next.name))
        persist()
        applySpots()
    }

    /** Width (y) and depth (x) changed by presses of + (1) or - (-1). */
    fun resizeSpot(id: String, widthSteps: Int, depthSteps: Int) = editSpot(id) { Spots.resize(it, widthSteps, depthSteps) }
    fun setSpotSurface(id: String, surface: Float) = editSpot(id) { Spots.setSurface(it, surface) }
    fun setSpotSparkle(id: String, on: Boolean) = editSpot(id) { it.copy(sparkle = on) }
    fun renameSpot(id: String, label: String) = editSpot(id) { it.copy(label = label.trim().take(BuildDocs.MAX_NAME)) }
    fun setSpotKind(id: String, kind: SpotKind, cls: ItemClass?) = editSpot(id) {
        val c = if (kind == SpotKind.CUSTOM) (cls ?: it.cls) else kind.cls
        it.copy(kind = kind, cls = c, label = if (kind == SpotKind.CUSTOM) it.label else "")
    }
    fun deleteSpot(id: String) {
        state = state.edit { SpotEdits.remove(it, id) }
        if ((state.selection as? BuildSelection.Spot)?.key == id) state = state.select(null)
        activeSpots = activeSpots.filterValues { it != id }
        persist()
        applySpots()
    }

    /** The global Show sparkles switch (kept on the phone; Options' Graphics page has it too). */
    fun changeSparkles(v: Boolean) {
        SparkleSwitch.set(context, v)
    }

    fun renameMarker(id: String, name: String) { state = state.edit { BuildEdits.renameMarker(it, id, name) }; persist() }
    fun retypeMarker(id: String, t: MarkerType) { state = state.edit { BuildEdits.retypeMarker(it, id, t) }; persist() }
    fun deleteMarker(id: String) {
        state = state.edit { BuildEdits.deleteMarker(it, id) }
        if ((state.selection as? BuildSelection.Marker)?.key == id) state = state.select(null)
        persist()
    }

    /**
     * Interact: the selected item given [cls], the classes ticked ([supports]) and its surface's height ([surface] yards over its
     * floor): kept at once (it changes the furniture in this scene: any prop can be a bed), its anchor where a body goes.
     */
    fun interact(cls: ItemClass, supports: Set<SupportClass>, surface: Float) {
        val s = state.selection as? BuildSelection.Item ?: return
        val was = state.doc.items[s.key] ?: items[s.key]
        val obj = s.sceneObject
        val anchor = was?.anchor ?: run {
            val f = obj?.let(furnitureOf)
            if (f != null && obj != null) {
                val t = ItemTarget(placeId, placeName, obj.name, s.model, s.name, ItemFrame.of(f), s.frame, f.spec)
                ItemMapping.defaultSpot(cls, t).first
            } else LocalOffset(0f, 0f, surface, 0f)
        }
        val i = ItemInteraction(s.key, s.name, s.model, s.frame, s.halfX, s.halfY, s.top, cls, supports, surface,
            if (cls == ItemClass.FLOOR || cls == ItemClass.WALL) anchor else anchor.copy(z = surface),
            if (obj != null) null else ItemInteraction.slotFor(cls, supports))
        state = state.edit { BuildEdits.setItem(it, i) }
        persist()
    }

    fun removeInteraction() {
        val s = state.selection as? BuildSelection.Item ?: return
        state = state.edit { BuildEdits.removeItem(it, s.key) }
        earlierItems = earlierItems - s.key
        persist()
    }

    /** Re-place: the selected pair back on its computed place (remembered placements still laid on). */
    fun replacePair() {
        val s = state.selection as? BuildSelection.Npc ?: return
        live.command { sim -> sim.resetPlaying(s.key) }
        state = state.edit { BuildEdits.resetPair(it, s.key) }
        refreshPlaying(s.key)
        persist()
    }

    /** Reset (the Both / This NPC only / Partner only choice): the shared placement back on its computed place, or just that one's own nudge gone. */
    fun resetTarget() {
        val s = state.selection as? BuildSelection.Npc ?: return
        val pair = playing ?: return replacePair()
        val role = target.role(pair.roleOf(s.key)).takeIf { it in pair.roles.indices } ?: return replacePair()
        val extras = BuildActors.withExtra(pair.extra, role, RoleDelta.ZERO)
        live.command { m -> m.movePlaying(s.key, pair.anchor, pair.heading, extras) }
        val now = pair.copy(extra = extras)
        playing = now
        val roles = listOfNotNull(pair.lead, pair.other).map { cast[it] ?: RoleActor(0, false) }
        val move = PairMove(s.key, pair.name, roles, BuildPose.of(toStage(pair.baseAnchor), pair.baseHeading + stageRotation),
            BuildPose.of(toStage(pair.anchor), pair.heading + stageRotation), pair.furniture?.let { runCatching { keyOf(it) }.getOrNull() }, extras, BuildActors.moveKind(now))
        state = state.edit { d -> if (now.moved) BuildEdits.movePair(d, move) else BuildEdits.resetPair(d, s.key) }
        refreshPlaying(s.key)
        persist()
    }

    /** The targets offered for the selected NPC, and the label of each (A / B with names). */
    fun targets(): List<MoveTarget> {
        val s = state.selection as? BuildSelection.Npc ?: return emptyList()
        return BuildActors.targets(playing.takeIf { s.playing }, s.key)
    }

    fun targetLabel(t: MoveTarget): String {
        val s = state.selection as? BuildSelection.Npc ?: return t.label
        val p = playing ?: return t.label
        fun who(id: String?) = id?.let { names[it] ?: it } ?: ""
        val mine = p.roleOf(s.key)
        return when (t) {
            MoveTarget.BOTH -> "Both"
            MoveTarget.THIS -> "${if (mine == 1) "B" else "A"}: ${who(s.key)} only"
            MoveTarget.PARTNER -> "${if (mine == 1) "A" else "B"}: ${who(if (mine == 1) p.lead else p.other)} only"
        }
    }

    /** The own offset of the target role from the pair's placement (the numeric fields show it), null when Both or alone. */
    fun targetOffset(): RoleDelta? {
        val s = state.selection as? BuildSelection.Npc ?: return null
        val p = playing ?: return null
        val role = target.role(p.roleOf(s.key)).takeIf { it in p.roles.indices } ?: return null
        return p.extra.getOrElse(role) { RoleDelta.ZERO }
    }

    fun changeTarget(t: MoveTarget) { if (t in targets()) { target = t; push() } }

    /** Reset to default placement: the remembered placements of this animation for these two forgotten, and Re-place. */
    fun resetPlacement() {
        val p = playing ?: return
        val keep = raceMine.filterNot { o -> o.animation == p.name && listOfNotNull(p.lead, p.other).any { id -> cast[id]?.race == o.race } }
        saveRace(keep)
        state = state.edit { d -> d.copy(raceOffsets = d.raceOffsets.filterNot { it.animation == p.name }) }
        replacePair()
    }

    /**
     * Remember for these races: the selected pair's correction now (its moved origin and nudges, per role) kept for this
     * animation, [roles] (0 A, 1 B) and [scope]; the pair goes back onto its computed place, where the remembered correction
     * now puts it (nothing moves on the screen).
     */
    fun remember(scope: RaceScope, roles: Set<Int>) {
        val s = state.selection as? BuildSelection.Npc ?: return
        val p = playing ?: return
        val ids = listOfNotNull(p.lead, p.other)
        val now = System.currentTimeMillis()
        var list = raceMine
        val made = ArrayList<RaceOffset>()
        for (i in roles.sorted()) {
            val id = ids.getOrNull(i) ?: continue
            val role = p.roles.getOrNull(i) ?: continue
            val who = cast[id] ?: continue
            val other = ids.getOrNull(1 - i)?.let { cast[it] }
            // the correction on top of what was already remembered for it (that one stays laid on by the simulation)
            val already = RaceOffsets.lookup(raceMine, raceShipped, p.name, i, who.copy(otherRace = other?.race ?: -1),
                RaceOffsets.furnitureClass(p.furniture), p.furniture?.let(keyOf)).first?.delta ?: RoleDelta.ZERO
            val change = RaceOffsets.deltaFor(already.applyTo(role), p.baseAnchor, p.baseHeading, p.anchor, p.heading, p.extra.getOrElse(i) { RoleDelta.ZERO })
            // a role that wasn't moved (the other one was moved alone) keeps what it had remembered
            if (change.none) continue
            val d = already + change
            val kind = BuildActors.moveKind(p)
            val o = RaceOffset(p.name, p.name, i, who.race, who.female, if (scope == RaceScope.PAIR) other?.race ?: -1 else -1,
                RaceOffsets.furnitureClass(p.furniture), if (p.other == null) p.furniture?.let(keyOf) else null, scope, d, at = now,
                move = if (kind == "A" || kind == "B") "single" else kind)
            list = RaceOffsets.upsert(list, o)
            made += o
        }
        if (made.isEmpty()) return
        saveRace(list)
        state = state.edit { d -> made.fold(BuildEdits.resetPair(d, s.key)) { acc, o -> BuildEdits.remember(acc, o) } }
        live.command { sim -> sim.resetPlaying(s.key) }
        refreshPlaying(s.key)
        persist()
    }

    private fun saveRace(list: List<RaceOffset>) {
        raceMine = list; raceLocal = list
        scope.launch(Dispatchers.IO) { runCatching { store.raceOffsets.save(list) } }
    }

    fun deleteRace(o: RaceOffset) = saveRace(raceMine.filter { it.key != o.key })
    fun resetRaces() = saveRace(emptyList())

    fun changeShowMarkers(v: Boolean) {
        showMarkers = v
        runCatching { prefs.edit().putBoolean(KEY_SHOW, v).apply() }
        push()
    }

    /** The race names of the selected pair's roles ("gnome female", "human male"), for the Remember button. */
    fun roleNames(): List<String> {
        val p = playing ?: return emptyList()
        return listOfNotNull(p.lead, p.other).map { id -> cast[id]?.let { raceName(it.race, it.female) } ?: (names[id] ?: id) }
    }

    // ---- the report

    fun send() {
        if (!state.canSend) return
        if (!runCatching { prefs.getBoolean(KEY_CONSENT, false) }.getOrDefault(false)) { askConsent = true; return }
        val r = BuildReport(reportId, placeId, placeName, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, state.doc, notes.trim())
        state = state.sending()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.saveDoc(placeId, placeName, r.doc)
                    BuildReports.enqueue(queue, r)
                    flushNow()
                }.getOrElse { SendResult(SendOutcome.RETRY, 0, "couldn't keep the report: ${it.javaClass.simpleName}") }
            }
            state = when (result?.outcome) {
                SendOutcome.SENT -> state.sent(result.issue)
                SendOutcome.REFUSED -> state.failed("GitHub refused the report: ${result.detail}")
                SendOutcome.NOT_CONFIGURED -> state.queued("Kept on the phone: this build can't send reports (${result.detail}).")
                else -> state.queued("Waiting to send: it goes when the phone is online.")
            }
            reportId = newId()
        }
    }

    fun consent(yes: Boolean) {
        askConsent = false
        if (yes) { runCatching { prefs.edit().putBoolean(KEY_CONSENT, true).apply() }; send() }
    }

    /** Whatever build reports wait go now if they can (when build mode opens and the phone comes online). */
    fun flush() { scope.launch(Dispatchers.IO) { runCatching { flushNow() } } }

    private fun flushNow(): SendResult? {
        val http = UrlTransport()
        return queue.flush({ title, body ->
            ItemReports.send(BuildConfig.WC_UPDATE_REPO, BuildConfig.WC_REPORT_TOKEN, title, body, BuildConfig.VERSION_NAME, http, label = BuildReports.LABEL)
        })
    }

    companion object {
        private const val KEY_CONSENT = "consent"
        private const val KEY_SHOW = "build_show_markers"
        private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

        /** A race's name for the panels (the playable races; others by number). */
        fun raceName(race: Int, female: Boolean): String {
            val name = when (race) {
                1 -> "human"; 2 -> "orc"; 3 -> "dwarf"; 4 -> "night elf"; 5 -> "undead"; 6 -> "tauren"; 7 -> "gnome"; 8 -> "troll"
                9 -> "goblin"; 10 -> "blood elf"; 11 -> "draenei"; 22 -> "worgen"; 24, 25, 26 -> "pandaren"; 27 -> "nightborne"
                28 -> "Highmountain tauren"; 29 -> "void elf"; 30 -> "Lightforged draenei"; 31 -> "Zandalari troll"; 32 -> "Kul Tiran"
                34 -> "Dark Iron dwarf"; 35 -> "vulpera"; 36 -> "Mag'har orc"; 37 -> "mechagnome"; 52, 70 -> "dracthyr"
                else -> "race $race"
            }
            return "$name ${if (female) "female" else "male"}"
        }
    }
}

/** Build mode's controller for this scene: its files loaded, the camera kept fresh while on, the waiting reports sent. */
@Composable
fun rememberBuildController(live: LiveScene, view: SceneView): BuildController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val build = remember(live) { BuildController(context, live, view, scope) }
    LaunchedEffect(build, build.on) {
        if (build.on) build.flush()
        while (build.on) { build.refreshCamera(); delay(66) }
    }
    DisposableEffect(build) { onDispose { build.leave(); view.clearBuild(); view.setSparkles(FloatArray(0), 0f); live.command { it.placement = null } } }
    return build
}

/** The Build bar at the bottom: Select, Marker, Move, Interact, Send, then undo, redo and Done. */
@Composable
fun BuildBar(build: BuildController, modifier: Modifier = Modifier) {
    if (!build.on) return
    val s = build.state
    WifePanel(modifier, padding = WifeTheme.gapS) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs),
            verticalAlignment = Alignment.CenterVertically) {
            for (t in BuildBarTool.entries) WifeButton(t.label, { build.setTool(t) }, Modifier.widthIn(min = 84.dp),
                kind = if (s.tool == t) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY)
            WifeButton("Undo", { build.undo() }, enabled = s.canUndo, kind = WifeButtonKind.QUIET)
            WifeButton("Redo", { build.redo() }, enabled = s.canRedo, kind = WifeButtonKind.QUIET)
            WifeButton("Done", { build.leave() })
        }
    }
}

/**
 * Build mode's context panel (at the side, never over the middle where the gizmo is): what is selected and what the tool
 * does with it. [onHold]: Move mode's hold of the controlled NPC on the selected scene object (an item report).
 */
@Composable
fun BuildPanel(build: BuildController, onHold: ((SceneObject) -> Unit)?, modifier: Modifier = Modifier) {
    if (!build.on) return
    val s = build.state
    WifePanel(modifier.width(288.dp).heightIn(max = 460.dp), padding = WifeTheme.gapM) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            val sel = s.selection
            Text(sel?.name ?: s.tool.label, style = WifeTheme.headingStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (s.tool) {
                BuildBarTool.SEND -> SendPage(build)
                BuildBarTool.MARKER -> MarkerPage(build)
                BuildBarTool.NEW -> NewPage(build)
                else -> when (sel) {
                    null -> WifeNote("Tap anything in the room: a prop, a piece of furniture, an NPC, a marker. One finger orbits round it, two pan and pinch.")
                    is BuildSelection.Item -> ItemPage(build, sel, onHold)
                    is BuildSelection.Marker -> MarkerDetail(build, sel)
                    is BuildSelection.Spot -> SpotDetail(build, sel)
                    is BuildSelection.Npc -> NpcPage(build, sel)
                }
            }
            if (s.tool != BuildBarTool.SEND && build.gizmoPose() != null) GizmoFields(build)
            val status = when (s.phase) {
                BuildPhase.SENDING -> "Sending…"
                BuildPhase.SENT -> "Report sent: #${s.issue}"
                else -> s.message
            }
            if (status != null) WifeNote(status, color = if (s.phase == BuildPhase.FAILED) WifeTheme.danger else if (s.phase == BuildPhase.SENT) WifeTheme.good else WifeTheme.textDim)
        }
    }
}

/** The gizmo's snap and the exact values (x, y, z yards, facing degrees), each Set one step. */
@Composable
private fun GizmoFields(build: BuildController) {
    val pose = build.gizmoPose() ?: return
    WifeSectionHeader("Position")
    WifeNote(String.format(Locale.ROOT, "x %.2f  y %.2f  z %.2f yd  ·  %.0f°", pose.x, pose.y, pose.z, pose.yawDeg))
    build.targetOffset()?.let { d ->
        WifeNote(String.format(Locale.ROOT, "Offset from the pair's placement: %+.2f %+.2f %+.2f yd  ·  %+.0f°", d.dx, d.dy, d.dz, d.dyawDeg))
    }
    WifeChip("Snap: ${build.state.snap.label}", { build.setSnap(build.state.snap.next()) }, trailing = null)
    WifeNote("Drag an arrow to move along it (X red, Y green, Z blue), the gold ring to turn (15° steps; Fine: 1°).")
    var x by remember(pose) { mutableStateOf(buildFmt(pose.x)) }
    var y by remember(pose) { mutableStateOf(buildFmt(pose.y)) }
    var z by remember(pose) { mutableStateOf(buildFmt(pose.z)) }
    var yaw by remember(pose) { mutableStateOf(String.format(Locale.ROOT, "%.0f", pose.yawDeg)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WarField(x, { x = it }, "x", Modifier.weight(1f), label = "X")
        WarField(y, { y = it }, "y", Modifier.weight(1f), label = "Y")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WarField(z, { z = it }, "z", Modifier.weight(1f), label = "Z")
        WarField(yaw, { yaw = it }, "°", Modifier.weight(1f), label = "Facing")
    }
    WifeButton("Set", {
        val p = BuildPose(x.toFloatOrNull() ?: pose.x, y.toFloatOrNull() ?: pose.y, z.toFloatOrNull() ?: pose.z,
            ItemFrame.wrapDeg(yaw.toFloatOrNull() ?: pose.yawDeg))
        build.apply(p)
    }, Modifier.fillMaxWidth())
}

private fun buildFmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)

/** Interact: the item's class, the animation classes it takes, its surface's height; and Move mode's hold. */
@Composable
private fun ItemPage(build: BuildController, sel: BuildSelection.Item, onHold: ((SceneObject) -> Unit)?) {
    val have = build.state.doc.items[sel.key] ?: build.items[sel.key]
    WifeNote(String.format(Locale.ROOT, "Model %d · %.2f × %.2f × %.2f yd", sel.model, 2 * sel.halfX, 2 * sel.halfY, sel.top) +
        (if (have != null) " · ${have.cls.label}" else " · no interaction"))
    var cls by remember(sel.key, have) { mutableStateOf(have?.cls ?: ItemClass.OTHER) }
    var supports by remember(sel.key, have) { mutableStateOf(have?.supports ?: emptySet()) }
    var surface by remember(sel.key, have) { mutableStateOf(have?.surface ?: sel.top) }
    WifeSectionHeader("Class")
    for (row in ItemClass.entries.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (c in row) WifeChip((if (c == cls) "● " else "") + c.label, {
            cls = c
            if (supports.isEmpty() || have == null) supports = c.supports
        }, Modifier.weight(1f), trailing = null)
    }
    WifeSectionHeader("Animations on it")
    for (row in SupportClass.entries.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (c in row) WifeChip((if (c in supports) "✓ " else "") + c.label, { supports = if (c in supports) supports - c else supports + c },
            Modifier.weight(1f), trailing = null)
    }
    WifeSectionHeader("Surface")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs), verticalAlignment = Alignment.CenterVertically) {
        WifeButton("−", { surface = (surface - 0.02f).coerceAtLeast(0f) })
        Text(String.format(Locale.ROOT, "%.2f yd", surface), Modifier.weight(1f), style = WifeTheme.labelStyle())
        WifeButton("+", { surface += 0.02f })
        WifeButton("Box", { surface = sel.top })
    }
    WifeButton(if (have == null) "Make interactive" else "Update", { build.interact(cls, supports, surface) }, Modifier.fillMaxWidth(),
        kind = WifeButtonKind.PRIMARY)
    if (have != null) {
        WifeNote("It takes effect at once: what plays on it is offered on it" + (if (have.slot != null) ", standing in for the scene's ${have.slot.label.lowercase()}" else "") +
            ". Drag the gizmo to move where a body goes on it.")
        WifeButton("Remove interaction", { build.removeInteraction() }, Modifier.fillMaxWidth(), kind = WifeButtonKind.QUIET)
    }
    val obj = sel.sceneObject
    if (obj != null && onHold != null) WifeButton("Hold NPC here (item report)", { onHold(obj) }, Modifier.fillMaxWidth())
}

@Composable
private fun MarkerDetail(build: BuildController, sel: BuildSelection.Marker) {
    val m = build.state.doc.markers.firstOrNull { it.id == sel.key }
    if (m == null) { WifeNote("A shipped marker (assets/place_markers.json): shown, not edited."); return }
    var name by remember(m.id) { mutableStateOf(m.name) }
    WarField(name, { name = it }, "Name", Modifier.fillMaxWidth(), label = "Name")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeButton("Rename", { build.renameMarker(m.id, name) }, Modifier.weight(1f))
        WifeButton("Delete", { build.deleteMarker(m.id) }, Modifier.weight(1f), kind = WifeButtonKind.QUIET)
    }
    for (row in MarkerType.entries.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (t in row) WifeChip((if (t == m.type) "● " else "") + t.label, { build.retypeMarker(m.id, t) }, Modifier.weight(1f), trailing = null)
    }
    m.itemKey?.let { WifeNote("For $it") }
}

/** Marker: its type, Add marker, and the list (rename and delete in each one's detail). */
@Composable
private fun MarkerPage(build: BuildController) {
    WifeNote("Tap the floor to put a marker there, or Add marker at what is selected (or the middle of the view).")
    for (row in MarkerType.entries.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (t in row) WifeChip((if (t == build.markerType) "● " else "") + t.label, { build.markerType = t }, Modifier.weight(1f), trailing = null)
    }
    WifeButton("Add marker", { build.addMarkerHere() }, Modifier.fillMaxWidth(), kind = WifeButtonKind.PRIMARY)
    (build.state.selection as? BuildSelection.Marker)?.let { MarkerDetail(build, it) }
    WifeSectionHeader("Markers")
    val all = build.state.doc.markers + build.shipped
    if (all.isEmpty()) WifeNote("None yet.")
    for (m in all) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        Text("${m.name} · ${m.type.label}${if (m.shipped) " · shipped" else ""}", Modifier.weight(1f), style = WifeTheme.labelStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        WifeButton("Go", { build.selectMarker(m) }, kind = WifeButtonKind.QUIET)
        if (!m.shipped) WifeButton("×", { build.deleteMarker(m.id) }, kind = WifeButtonKind.QUIET)
    }
    WifeSwitchRow("Show markers", build.showMarkers, { build.changeShowMarkers(it) }, detail = "Small, outside build mode too.")
}

/**
 * A selected NPC: playing, the gizmo moves the pair (Both), this NPC alone or its partner alone (the clips keep playing on the
 * one clock); not playing, it moves the NPC where it stands. Re-place, Reset, and Remember for the races in it.
 */
@Composable
private fun NpcPage(build: BuildController, sel: BuildSelection.Npc) {
    val p = build.playing
    if (!sel.playing || p == null) {
        WifeNote("Not playing an animation: the gizmo puts " + sel.name + " where you want them (they stay there while Build mode is on). Give them an animation and it moves while it plays.")
        if (build.state.doc.npcMoves.containsKey(sel.key)) WifeNote("Moved.")
        return
    }
    WifeNote("Playing ${p.name.ifEmpty { "an animation" }}${p.furniture?.let { " on the ${it.label.lowercase()}" } ?: ""}. The gizmo moves who you pick below; it keeps playing." +
        if (p.moved) " Moved." else "")
    val targets = build.targets()
    if (targets.size > 1) {
        WifeSectionHeader("Move")
        for (t in targets) WifeChip((if (t == build.target) "● " else "") + build.targetLabel(t), { build.changeTarget(t) }, Modifier.fillMaxWidth(), trailing = null)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeButton(if (build.target == MoveTarget.BOTH) "Re-place" else "Reset this one", { build.resetTarget() }, Modifier.weight(1f))
        WifeButton("Reset to default placement", { build.resetPlacement() }, Modifier.weight(1f), kind = WifeButtonKind.QUIET)
    }
    WifeSectionHeader("Remember")
    val names = build.roleNames()
    var scope by remember(sel.key) { mutableStateOf(RaceScope.ROLE) }
    // the roles that were moved come ticked (one moved alone: only that one; the pair moved: both)
    val changed = BuildActors.changedRoles(p)
    var roles by remember(sel.key, names.size, changed) { mutableStateOf(changed.ifEmpty { names.indices.toSet() }) }
    for (sc in RaceScope.entries) WifeChip((if (sc == scope) "● " else "") + sc.label, { scope = sc }, Modifier.fillMaxWidth(), trailing = null)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        names.forEachIndexed { i, n ->
            WifeChip((if (i in roles) "✓ " else "") + (if (i == 0) "A: " else "B: ") + n, { roles = if (i in roles) roles - i else roles + i },
                Modifier.weight(1f), trailing = null)
        }
    }
    val who = roles.sorted().mapNotNull { names.getOrNull(it) }.let { n ->
        if (scope == RaceScope.SIZE) roles.sorted().mapNotNull { i -> listOfNotNull(p.lead, p.other).getOrNull(i)?.let { id -> build.cast[id]?.let { SizeClass.of(it.race).label } } }.distinct() else n
    }
    WifeButton("Remember for ${who.joinToString(" and ").ifEmpty { "…" }}", { build.remember(scope, roles) }, Modifier.fillMaxWidth(),
        enabled = roles.isNotEmpty(), kind = WifeButtonKind.PRIMARY)
    WifeNote("Next time these play it, they start where they are now (the animation itself is not changed). One moved alone is saved as its own role's offset; the pair moved together as a shared one.")
}

/** The New tab: pick a type, tap the scene to place it; the sparkles switch. */
@Composable
private fun NewPage(build: BuildController) {
    WifeNote("Pick what the spot is, then tap the floor or any prop in the scene to put it there. From then on that space is clickable: NPCs can use it" +
        " and it sparkles outside Build mode.")
    for (row in SpotKind.entries.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (k in row) WifeChip((if (k == build.newKind) "● " else "") + k.label, { build.newKind = k }, Modifier.weight(1f), trailing = null)
    }
    if (build.newKind == SpotKind.CUSTOM) {
        WarField(build.newLabel, { build.newLabel = it.take(BuildDocs.MAX_NAME) }, "Label", Modifier.fillMaxWidth(), label = "Label")
        WifeNote("Acts as:")
        for (row in SpotKind.CLASSES.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            for (c in row) WifeChip((if (c == build.newClass) "● " else "") + SpotKind.classLabel(c), { build.newClass = c }, Modifier.weight(1f), trailing = null)
        }
    }
    WifeButton(if (build.armed) "Tap the scene to place…" else "Place ${build.newKind.label.lowercase()}", { build.arm(build.newKind) }, Modifier.fillMaxWidth(),
        kind = if (build.armed) WifeButtonKind.SECONDARY else WifeButtonKind.PRIMARY)
    (build.state.selection as? BuildSelection.Spot)?.let { SpotDetail(build, it) }
    WifeSectionHeader("Spots")
    val all = build.allSpots
    if (all.isEmpty()) WifeNote("None yet.")
    for (sp in all) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        Text("${sp.name} · ${SpotKind.classLabel(sp.cls)}${if (sp.shipped) " · shipped" else ""}", Modifier.weight(1f), style = WifeTheme.labelStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        WifeButton("Go", { build.selectSpot(sp) }, kind = WifeButtonKind.QUIET)
        if (!sp.shipped) WifeButton("×", { build.deleteSpot(sp.id) }, kind = WifeButtonKind.QUIET)
    }
    WifeSwitchRow("Show sparkles", build.sparklesOn, { build.changeSparkles(it) }, detail = "On every spot a body can use, outside Build mode. Off in cinematic mode.")
}

/** A selected spot: its name, size (+ and -), surface height, sparkles (per spot) and Delete; the gizmo moves and turns it. */
@Composable
private fun SpotDetail(build: BuildController, sel: BuildSelection.Spot) {
    val sp = build.state.doc.spots[sel.key]
    if (sp == null) { WifeNote("A shipped spot (assets/user_spots.json): shown, not edited."); return }
    WifeNote(String.format(Locale.ROOT, "%s · %.2f × %.2f yd · surface %.2f yd", SpotKind.classLabel(sp.cls), 2 * sp.halfX, 2 * sp.halfY, sp.surface))
    if (sp.kind == SpotKind.CUSTOM) {
        var label by remember(sp.id) { mutableStateOf(sp.label) }
        WarField(label, { label = it }, "Label", Modifier.fillMaxWidth(), label = "Label")
        WifeButton("Rename", { build.renameSpot(sp.id, label) }, Modifier.fillMaxWidth())
    }
    WifeSectionHeader("Size")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs), verticalAlignment = Alignment.CenterVertically) {
        WifeButton("−", { build.resizeSpot(sp.id, -1, 0) }); Text("Width", Modifier.weight(1f), style = WifeTheme.labelStyle()); WifeButton("+", { build.resizeSpot(sp.id, 1, 0) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs), verticalAlignment = Alignment.CenterVertically) {
        WifeButton("−", { build.resizeSpot(sp.id, 0, -1) }); Text("Depth", Modifier.weight(1f), style = WifeTheme.labelStyle()); WifeButton("+", { build.resizeSpot(sp.id, 0, 1) })
    }
    WifeSectionHeader("Surface height")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs), verticalAlignment = Alignment.CenterVertically) {
        WifeButton("−", { build.setSpotSurface(sp.id, sp.surface - 0.02f) })
        Text(String.format(Locale.ROOT, "%.2f yd", sp.surface), Modifier.weight(1f), style = WifeTheme.labelStyle())
        WifeButton("+", { build.setSpotSurface(sp.id, sp.surface + 0.02f) })
    }
    WifeSwitchRow("Sparkles on this spot", sp.sparkle, { build.setSpotSparkle(sp.id, it) }, detail = "Shown outside Build mode while the global switch is on.")
    WifeButton("Delete spot", { build.deleteSpot(sp.id) }, Modifier.fillMaxWidth(), kind = WifeButtonKind.QUIET)
}

/** Send: what goes, the notes, Save and send; the remembered placements; Show markers. */
@Composable
private fun SendPage(build: BuildController) {
    val d = build.state.doc
    WifeNote("${d.markers.size} marker(s), ${d.items.size} item(s), ${d.pairs.size} moved pair(s), ${d.raceOffsets.size} race placement(s) this session.")
    WarField(build.notes, { build.notes = it.take(ItemMapping.MAX_NOTES) }, "What should change (optional)", Modifier.fillMaxWidth(), label = "Notes", singleLine = false)
    WifeButton("Save and send", { build.send() }, Modifier.fillMaxWidth(), enabled = build.state.canSend, kind = WifeButtonKind.PRIMARY)
    WifeNote("Saved on the phone (files/build) and sent as one issue: positions in the place's frame, the items' classes, the moved pairs and races. Nothing else from the phone.")
    RaceOffsetsList(build.raceLocal, { build.deleteRace(it) }, { build.resetRaces() })
    WifeSwitchRow("Show markers", build.showMarkers, { build.changeShowMarkers(it) }, detail = "Small, outside build mode too.")
}

@Composable
private fun RaceOffsetsList(list: List<RaceOffset>, onDelete: (RaceOffset) -> Unit, onReset: () -> Unit) {
    WifeSectionHeader("Race offsets")
    if (list.isEmpty()) WifeNote("None remembered. Move a playing pair with the gizmo, then Remember.")
    for (o in list) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        Text(o.describe(), Modifier.weight(1f), style = WifeTheme.captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        WifeButton("Delete", { onDelete(o) }, kind = WifeButtonKind.QUIET)
    }
    if (list.isNotEmpty()) WifeButton("Reset all", onReset, Modifier.fillMaxWidth(), kind = WifeButtonKind.QUIET)
}

/** Build mode's window: the one-time question before a report goes (shared with Move mode's item reports). */
@Composable
fun BuildWindows(build: BuildController) {
    Overlay({ build.consent(false) }, build.on && build.askConsent) {
        WifeWindow("Send build reports?", { build.consent(false) }, Modifier.widthIn(min = 280.dp, max = 460.dp).padding(WifeTheme.gapL)) {
            Text("This sends build data to the developer's repository: the place, your markers, the items you gave interactions, " +
                "the pairs you moved and the races in them, your notes and the app's version, as an issue on its GitHub page. " +
                "Nothing else from the phone (no account, device or files).", style = WifeTheme.bodyStyle())
            Row(Modifier.fillMaxWidth().padding(top = WifeTheme.gapM), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                WifeButton("Not now", { build.consent(false) }, Modifier.weight(1f))
                WifeButton("Send reports", { build.consent(true) }, Modifier.weight(1f), kind = WifeButtonKind.PRIMARY)
            }
        }
    }
}

/** Options → Mods → Race offsets: the remembered placements on this phone, each deletable, and Reset all. */
@Composable
fun RaceOffsetsSection() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val file = remember(context) { File(File(context.filesDir, "build"), "race_offsets.json") }
    var list by remember { mutableStateOf<List<RaceOffset>>(emptyList()) }
    fun reload() { scope.launch { list = withContext(Dispatchers.IO) { RaceOffsetStore(file).load() } } }
    LaunchedEffect(Unit) { reload() }
    RaceOffsetsList(list, { o -> scope.launch { withContext(Dispatchers.IO) { runCatching { RaceOffsetStore(file).delete(o.key) } }; reload() } },
        { scope.launch { withContext(Dispatchers.IO) { runCatching { RaceOffsetStore(file).reset() } }; reload() } })
}

/** The global Show sparkles switch (Options' Graphics page and the New tab): on by default, kept on the phone. */
object SparkleSwitch {
    var on by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        on = try { context.applicationContext.getSharedPreferences(Sparkles.PREFS, Context.MODE_PRIVATE).getBoolean(Sparkles.KEY_ON, true) } catch (e: Exception) { true }
    }

    fun set(context: Context, v: Boolean) {
        loaded = true
        on = v
        try { context.applicationContext.getSharedPreferences(Sparkles.PREFS, Context.MODE_PRIVATE).edit().putBoolean(Sparkles.KEY_ON, v).apply() } catch (e: Exception) { }
    }
}
