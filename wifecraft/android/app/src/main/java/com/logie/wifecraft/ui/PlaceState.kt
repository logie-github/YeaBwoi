package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.catalog.Catalog
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.catalog.EmoteCatalog
import com.logie.wifecraft.catalog.Page
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Pure Kotlin, no android.* and no Compose: the placement editor's own state and maths (the camera that orbits, the floor
// under a finger, snapping, the gestures' turns and pinches, the library's pages, the stage list, the emote groups), so
// the JVM tests (unit-tests/.../ui/PlaceStateTest.kt) cover them. The screens (EditorPlacement, PlaceLibrarySheet,
// PlaceOutliner, PlaceGizmo, PlaceInspector, EmotePicker) draw what these return. WoW's coordinates: Z up, yaw radians
// about Z counter-clockwise from +X.

private fun len(v: Vec3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
private fun norm(v: Vec3): Vec3 = len(v).let { l -> if (l < 1e-6f) Vec3(0f, 0f, 0f) else Vec3(v.x / l, v.y / l, v.z / l) }
private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

/** An angle brought into (-PI, PI]. */
fun wrapAngle(a: Float): Float {
    var r = a % (2 * PI).toFloat()
    if (r <= -PI.toFloat()) r += (2 * PI).toFloat()
    if (r > PI.toFloat()) r -= (2 * PI).toFloat()
    return r
}

/**
 * The placement editor's camera: it looks at [target] from [distance] away, from the direction [yaw] (radians about Z, the
 * way from the target to the eye) raised by [pitch] (radians above the floor). One finger orbits, two pan and pinch to zoom.
 */
data class OrbitCamera(val target: Vec3, val yaw: Float, val pitch: Float, val distance: Float, val fov: Float = 50f) {
    val eye: Vec3 get() = target + Vec3(cos(pitch) * cos(yaw), cos(pitch) * sin(yaw), sin(pitch)) * distance
    fun pose(): CameraPose = CameraPose(eye, target, fov)

    /** A finger's move of [dx], [dy] pixels on a view [viewH] pixels high: half the height turns it about a quarter. */
    fun orbit(dx: Float, dy: Float, viewH: Float): OrbitCamera {
        val k = (PI.toFloat() / 2f) / (viewH / 2f).coerceAtLeast(1f)
        return copy(yaw = wrapAngle(yaw - dx * k), pitch = (pitch + dy * k).coerceIn(MIN_PITCH, MAX_PITCH))
    }

    /** Two fingers moved [dx], [dy] pixels: the floor follows them (the target moves the other way). */
    fun pan(dx: Float, dy: Float, viewH: Float): OrbitCamera {
        val k = worldPerPixel(viewH)
        val f = norm(Vec3(-cos(yaw), -sin(yaw), 0f))     // forward along the floor
        val r = Vec3(f.y, -f.x, 0f)                       // the viewer's right
        return copy(target = target + r * (-dx * k) + f * (dy * k))
    }

    /** Pinched by [factor] (more than 1: the fingers spread, it comes nearer). */
    fun zoom(factor: Float): OrbitCamera =
        if (!factor.isFinite() || factor <= 0f) this else copy(distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    /** How many yards one pixel is at the target's distance, on a view [viewH] pixels high. */
    fun worldPerPixel(viewH: Float): Float = 2f * distance * tan(Math.toRadians(fov / 2.0).toFloat()) / viewH.coerceAtLeast(1f)

    /** Looks at [p] from where it is now (the distance kept): the outliner's "Look at". */
    fun lookAt(p: Vec3): OrbitCamera = copy(target = p)

    /**
     * Focuses on a thing [size] yards across whose middle is [p] (a double tap, the outliner): it looks at it from the same
     * side, near enough that the thing fills about half the view's height.
     */
    fun focus(p: Vec3, size: Float): OrbitCamera {
        val s = if (size.isFinite() && size > 0f) size else 1.8f
        val d = s / tan(Math.toRadians(fov / 2.0).toFloat())
        return copy(target = p, distance = d.coerceIn(MIN_DISTANCE * 2f, FOCUS_MAX))
    }

    companion object {
        const val MIN_PITCH = -0.15f
        const val MAX_PITCH = 1.50f
        const val MIN_DISTANCE = 0.6f
        const val MAX_DISTANCE = 600f
        /** A focus never goes further back than this (a building is seen from where its door is). */
        const val FOCUS_MAX = 80f

        /** The orbit camera that sees what [pose] sees (a location view's or a place's camera). */
        fun from(pose: CameraPose): OrbitCamera {
            val d = pose.eye - pose.at
            val dist = len(d).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            val flat = hypot(d.x, d.y)
            return OrbitCamera(pose.at, atan2(d.y, d.x), atan2(d.z, flat).coerceIn(MIN_PITCH, MAX_PITCH), dist, pose.fov)
        }
    }
}

/**
 * Screen points and the floor, as the renderer sees them (cpp engine/location.cpp Scene::pick: vertical [CameraPose.fov],
 * Z up, the screen's y down): the ray under a finger, where it meets a level floor, where a point of the world is on the
 * screen, and a drag's move on the floor.
 */
object FloorMath {
    /** The ray from the eye through the screen point ([sx], [sy] pixels of a [w] x [h] view): origin and direction. */
    fun ray(cam: CameraPose, sx: Float, sy: Float, w: Float, h: Float): Pair<Vec3, Vec3> {
        val f = norm(cam.at - cam.eye)
        val s = norm(cross(f, Vec3(0f, 0f, 1f)))
        val u = cross(s, f)
        val th = tan(Math.toRadians(cam.fov / 2.0).toFloat())
        val aspect = w / h.coerceAtLeast(1f)
        val nx = 2f * sx / w.coerceAtLeast(1f) - 1f
        val ny = 1f - 2f * sy / h.coerceAtLeast(1f)
        return cam.eye to norm(f + s * (nx * th * aspect) + u * (ny * th))
    }

    /** Where the screen point meets the level floor at height [z]; null when it looks away from it (or along it). */
    fun onPlane(cam: CameraPose, sx: Float, sy: Float, w: Float, h: Float, z: Float): Vec3? {
        val (o, d) = ray(cam, sx, sy, w, h)
        if (abs(d.z) < 1e-5f) return null
        val t = (z - o.z) / d.z
        if (t <= 0f || t > MAX_REACH) return null
        return o + d * t
    }

    /** Where [p] is on the screen (pixels), or null when it is behind the eye. */
    fun project(cam: CameraPose, p: Vec3, w: Float, h: Float): Pair<Float, Float>? {
        val f = norm(cam.at - cam.eye)
        val s = norm(cross(f, Vec3(0f, 0f, 1f)))
        val u = cross(s, f)
        val v = p - cam.eye
        val depth = dot(v, f)
        if (depth <= 0.01f) return null
        val th = tan(Math.toRadians(cam.fov / 2.0).toFloat())
        val aspect = w / h.coerceAtLeast(1f)
        val nx = dot(v, s) / (depth * th * aspect)
        val ny = dot(v, u) / (depth * th)
        return (nx + 1f) * w / 2f to (1f - ny) * h / 2f
    }

    /** How far [p] is in front of the eye (along the view), for a handle's pixels to yards. */
    fun depth(cam: CameraPose, p: Vec3): Float = dot(p - cam.eye, norm(cam.at - cam.eye))

    /** Yards per pixel at [p]'s depth on a view [h] pixels high. */
    fun worldPerPixelAt(cam: CameraPose, p: Vec3, h: Float): Float =
        2f * depth(cam, p).coerceAtLeast(0.1f) * tan(Math.toRadians(cam.fov / 2.0).toFloat()) / h.coerceAtLeast(1f)

    /**
     * A drag of a thing standing at height [z] from screen point [from] to [to]: how far it moves on its level floor (z 0:
     * the height is kept), or null when either point doesn't meet that floor.
     */
    fun dragDelta(cam: CameraPose, from: Pair<Float, Float>, to: Pair<Float, Float>, w: Float, h: Float, z: Float): Vec3? {
        val a = onPlane(cam, from.first, from.second, w, h, z) ?: return null
        val b = onPlane(cam, to.first, to.second, w, h, z) ?: return null
        val d = b - a
        // a finger near the horizon moves a long way: one drag step stays within reason
        val l = hypot(d.x, d.y)
        val k = if (l > MAX_STEP) MAX_STEP / l else 1f
        return Vec3(d.x * k, d.y * k, 0f)
    }

    /** One step of a nudge button: [forward] and [right] (-1, 0, 1) as the camera sees the floor, [step] yards. */
    fun nudge(cam: CameraPose, forward: Int, right: Int, step: Float): Vec3 {
        val f = norm(Vec3(cam.at.x - cam.eye.x, cam.at.y - cam.eye.y, 0f)).let { if (len(it) < 0.5f) Vec3(1f, 0f, 0f) else it }
        // the nudge goes along the world axis nearest the camera's forward, so repeated nudges stay on a grid
        val ax = if (abs(f.x) >= abs(f.y)) Vec3(if (f.x >= 0) 1f else -1f, 0f, 0f) else Vec3(0f, if (f.y >= 0) 1f else -1f, 0f)
        val r = Vec3(ax.y, -ax.x, 0f)
        return ax * (forward * step) + r * (right * step)
    }

    const val MAX_REACH = 3000f
    const val MAX_STEP = 60f
}

/** Two fingers' turn and pinch between the last event ([a0], [b0]) and this one ([a1], [b1]), screen pixels. */
object Gestures {
    /** The turn of the line between the fingers, radians on the screen (clockwise positive: y is down). */
    fun twist(a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>): Float {
        val t0 = atan2(b0.second - a0.second, b0.first - a0.first)
        val t1 = atan2(b1.second - a1.second, b1.first - a1.first)
        return wrapAngle(t1 - t0)
    }

    /** How much the fingers spread (1: as before; 0.5: half as far apart). */
    fun pinch(a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>): Float {
        val d0 = hypot(b0.first - a0.first, b0.second - a0.second)
        val d1 = hypot(b1.first - a1.first, b1.second - a1.second)
        return if (d0 < 1f) 1f else d1 / d0
    }

    /** The middle between two fingers. */
    fun mid(a: Pair<Float, Float>, b: Pair<Float, Float>) = (a.first + b.first) / 2f to (a.second + b.second) / 2f

    /** A screen twist made the thing's yaw change: the floor seen from above turns the other way to the screen's y. */
    fun yawOfTwist(twist: Float): Float = -twist

    /** A finger going round a handle's centre ([c]) from [p0] to [p1]: the yaw change (as [yawOfTwist]). */
    fun yawAround(c: Pair<Float, Float>, p0: Pair<Float, Float>, p1: Pair<Float, Float>): Float =
        yawOfTwist(wrapAngle(atan2(p1.second - c.second, p1.first - c.first) - atan2(p0.second - c.second, p0.first - c.first)))
}

/** Snapping: positions to a [grid] of yards and turns to steps of [angleDeg] degrees, while [on]. */
data class Snap(val on: Boolean = false, val grid: Float = 0.5f, val angleDeg: Float = 15f) {
    fun pos(v: Float): Float = if (!on || grid <= 0f) v else (v / grid).roundToInt() * grid
    fun yaw(a: Float): Float {
        if (!on || angleDeg <= 0f) return wrapAngle(a)
        val step = Math.toRadians(angleDeg.toDouble()).toFloat()
        return wrapAngle((a / step).roundToInt() * step)
    }
    fun scale(s: Float): Float = clampScale(if (!on) s else (s / 0.05f).roundToInt() * 0.05f)

    /** The nudge buttons' step: the grid while snapping, a tenth of a yard otherwise. */
    val step: Float get() = if (on && grid > 0f) grid else 0.1f

    companion object {
        val GRIDS = listOf(0.25f, 0.5f, 1f, 2f)
        val ANGLES = listOf(5f, 15f, 45f, 90f)
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
        fun clampScale(s: Float): Float = if (!s.isFinite()) 1f else s.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}

/**
 * Where a dragged thing stands as it goes: on the ground under it at the height it stood above the ground when the drag
 * began ([lift]: 0 for a thing on the ground); where the ground is unknown it keeps its height ([kept]).
 */
fun followGround(ground: Float?, lift: Float, kept: Float): Float = if (ground == null) kept else ground + lift

/** The renderer's keys of placed things (beside the cast's own keys and the creator's). */
object PlaceKeys {
    const val PREFIX = "pl:"
    fun of(placementId: String) = PREFIX + placementId
    /** The placement id of a renderer key (a pick's hit), or null when the key is someone else's. */
    fun idOf(key: String?): String? = key?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

/**
 * The outliner's and the viewport's state that isn't saved with the scene: which placement is selected and whether hidden
 * ones are drawn in the editor ("show hidden"). Hiding and locking are the placements' own ([Placement.hidden], saved with the
 * scene): a locked one is selected but not moved; a hidden one is not drawn (nor tapped) unless [showHidden]. Immutable.
 */
data class PlaceUi(val selected: String? = null, val showHidden: Boolean = false) {
    fun select(id: String?): PlaceUi = copy(selected = id)
    fun toggleShowHidden(): PlaceUi = copy(showHidden = !showHidden)
    /** Whether the editor's preview draws [p]. */
    fun drawn(p: Placement): Boolean = !p.hidden || showHidden
    /** Whether [p] can be dragged, nudged or resized: not locked, and drawn. */
    fun canMove(p: Placement?): Boolean = p != null && !p.locked && drawn(p)
    /** The ids the preview leaves off the renderer. */
    fun notDrawn(placements: List<Placement>): Set<String> = placements.filterTo(ArrayList()) { !drawn(it) }.map { it.id }.toSet()
    /** Forgets what is no longer in the scene ([ids]: the placements' ids now). */
    fun keepOnly(ids: Collection<String>): PlaceUi = copy(selected = selected?.takeIf { it in ids })
    /** The selection once [p] is hidden or shown: a hidden thing that isn't drawn can't stay selected. */
    fun afterHide(p: Placement): PlaceUi = if (selected == p.id && !drawn(p)) copy(selected = null) else this
    /** A tap's hit (a renderer key, or null for nothing or the floor): the placement it selects, or none. */
    fun tapped(key: String?, placements: List<Placement>): PlaceUi =
        select(PlaceKeys.idOf(key)?.takeIf { id -> placements.none { it.id == id && !drawn(it) } })
}

/** Words the placement editor shows that a test can check. */
object PlaceLabels {
    /** The Sound switch's label: props have no GameObjectDisplayInfo id here, so nothing plays for them yet. */
    fun sound(kind: PlaceKind): String = if (kind == PlaceKind.PROP) "Sound (none for props yet)" else "Sound"
}

/** A placement of the library entry [a] at [at] turned [yaw], with an id not in [taken] (its name made an id). */
fun placementOf(a: Asset, at: Vec3, yaw: Float, taken: Collection<String>): Placement {
    val id = freshId(a.name.ifBlank { "thing" }, taken)
    return when (a.kind) {
        AssetKind.PROP, AssetKind.BUILDING -> Placement(id, PlaceKind.PROP, a.fileId, null, a.name, at.x, at.y, at.z, yaw, 1f)
        AssetKind.CREATURE, AssetKind.CREATURE_MODEL -> Placement(id, PlaceKind.CREATURE, a.display, null, a.name, at.x, at.y, at.z, yaw, 1f)
        AssetKind.OWN_NPC -> Placement(id, PlaceKind.NPC, 0, a.key.removePrefix("n:"), a.name, at.x, at.y, at.z, yaw, 1f)
    }
}

/** A copy of [p] beside it (a yard to the side), with a fresh id and "(copy)" in the outliner. */
fun duplicateOf(p: Placement, taken: Collection<String>): Placement =
    p.copy(id = freshId(p.id + "_copy", taken), name = p.name + " (copy)", x = p.x + 1f, y = p.y)

// ------------------------------------------------------------------------------------------------- the library

/** The library sheet's tabs. */
enum class LibraryTab(val label: String) {
    STARTER("Starter pack"), PROPS("Props"), BUILDINGS("Buildings"), CREATURES("Creatures"), MY_NPCS("My NPCs"),
    FAVOURITES("Favourites"), RECENT("Recent");

    /** The catalogue kinds a tab searches. */
    val kinds: Set<AssetKind>
        get() = when (this) {
            STARTER, PROPS -> setOf(AssetKind.PROP)
            BUILDINGS -> setOf(AssetKind.BUILDING)
            CREATURES -> setOf(AssetKind.CREATURE, AssetKind.CREATURE_MODEL)
            MY_NPCS -> setOf(AssetKind.OWN_NPC)
            FAVOURITES, RECENT -> AssetKind.values().toSet()
        }
}

/** What the library sheet shows: a tab, the words typed, a category chip (null: all), a style (Starter pack), a page. */
data class LibraryQuery(
    val tab: LibraryTab = LibraryTab.STARTER,
    val text: String = "",
    val category: String? = null,
    val style: String = Catalog.ANY,
    val page: Int = 0,
    /** The category tree's style under Props, Buildings or Creatures ("Forsaken"); null: every style. */
    val styleFilter: String? = null,
    /** The words search only this tab (a group's "Show all"); otherwise words search everything, grouped. */
    val scoped: Boolean = false,
) {
    val offset: Int get() = page * PAGE

    /**
     * Whether the sheet shows the grouped search (Props, Buildings, Creatures, My NPCs, each with its count): words are
     * typed and nothing narrows them (no category or style picked in the tree, no group's "Show all").
     */
    val grouped: Boolean get() = text.isNotBlank() && category == null && styleFilter == null && !scoped

    /** Another tab: its own chips and first page (the words stay). */
    fun withTab(t: LibraryTab) = if (t == tab) copy(scoped = false) else copy(tab = t, category = null, styleFilter = null, page = 0, scoped = false)
    fun withText(s: String) = copy(text = s, page = 0, scoped = if (s.isBlank()) false else scoped)
    fun withCategory(c: String?) = copy(category = if (c == category) null else c, styleFilter = null, page = 0)
    fun withStyleFilter(s: String?) = copy(styleFilter = if (s == styleFilter) null else s, category = null, page = 0)
    /** A group's "Show all" of the grouped search: that tab, the same words, its pages. */
    fun showAll(t: LibraryTab) = copy(tab = t, category = null, styleFilter = null, page = 0, scoped = true)
    fun withStyle(s: String) = copy(style = s, page = 0)
    fun next(total: Int) = copy(page = (page + 1).coerceAtMost(pages(total) - 1))
    fun prev() = copy(page = (page - 1).coerceAtLeast(0))

    companion object {
        /** Entries on a page (a page fits a phone's list without a long scroll). */
        const val PAGE = 40
        fun pages(total: Int): Int = maxOf(1, (total + PAGE - 1) / PAGE)
    }
}

/**
 * The library's entries for a [LibraryQuery]: the Starter pack is the [library]'s starterPack of the query's style; My NPCs
 * is [own] (the project's cast); the other tabs search the whole [library] (props, buildings, the game's creatures).
 */
object LibraryPager {
    /**
     * [favourites] and [recent] are the project's lists as entries (LibraryPrefs resolved: [LibraryModel.resolve]); they
     * keep their own order (the newest pick first) unless words are typed.
     */
    fun page(library: Catalog, own: List<Asset>, q: LibraryQuery, favourites: List<Asset> = emptyList(), recent: List<Asset> = emptyList()): Page = when (q.tab) {
        LibraryTab.STARTER -> Catalog(library.starterPack(q.style)).search(q.text, q.category, null, null, q.offset, LibraryQuery.PAGE)
        LibraryTab.MY_NPCS -> Catalog(own).search(q.text, null, null, null, q.offset, LibraryQuery.PAGE)
        LibraryTab.FAVOURITES, LibraryTab.RECENT -> {
            val list = if (q.tab == LibraryTab.FAVOURITES) favourites else recent
            val shown = if (q.text.isBlank()) list else Catalog(list).search(q.text, null, null, null, 0, Int.MAX_VALUE).items
            val from = q.offset.coerceIn(0, shown.size)
            Page(shown.subList(from, (from + LibraryQuery.PAGE).coerceAtMost(shown.size)), shown.size, from)
        }
        else -> library.search(q.text, q.category, q.styleFilter, q.tab.kinds, q.offset, LibraryQuery.PAGE)
    }

    /** The category chips of a tab (the most used first, at most [most]); none for My NPCs, Favourites and Recent. */
    fun chips(library: Catalog, q: LibraryQuery, most: Int = 24): List<String> = when (q.tab) {
        LibraryTab.MY_NPCS, LibraryTab.FAVOURITES, LibraryTab.RECENT -> emptyList()
        LibraryTab.STARTER -> library.starterPack(q.style).map { it.category }.distinct()
        else -> library.categories(q.tab.kinds).sortedByDescending { it.second }.take(most).map { it.first }
    }

    /** What a row says under the name: its kind, category, style and size. */
    fun detail(a: Asset): String = listOfNotNull(
        when (a.kind) {
            AssetKind.PROP -> "Prop"; AssetKind.BUILDING -> "Building"; AssetKind.CREATURE_MODEL -> "Creature model"
            AssetKind.CREATURE -> "Creature"; AssetKind.OWN_NPC -> "Your NPC"
        },
        a.category.takeIf { it.isNotBlank() && a.kind != AssetKind.OWN_NPC },
        a.style.takeIf { it != Catalog.ANY },
        Catalog.SIZES.getOrNull(a.size),
        a.alias.takeIf { it.isNotBlank() && a.kind == AssetKind.CREATURE }?.let { "<$it>" },
    ).joinToString(" · ")

    /** The starter pack's style for a stage: the Embassy is Stormwind's (human); anywhere else, the unstyled props. */
    fun styleOf(stageId: String): String = when (StageChoices.locationOf(stageId)?.first) {
        "embassy" -> "Human"
        else -> Catalog.ANY
    }
}

// ------------------------------------------------------------------------------------------------- stages

/** The stage picker's list: the studio, the places of places.json, then every location of locations.json with each view. */
object StageChoices {
    fun loc(locationId: String, view: String) = "loc:$locationId:$view"

    /** "loc:embassy:inside" as ("embassy", "inside"); null for a place id or the studio. */
    fun locationOf(stageId: String): Pair<String, String>? {
        if (!stageId.startsWith("loc:")) return null
        val w = stageId.split(':')
        return if (w.size == 3 && w[1].isNotBlank() && w[2].isNotBlank()) w[1] to w[2] else null
    }

    fun viewLabel(view: String): String = view.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun list(places: List<PlaceInfo>, locations: List<LocationInfo>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val studio = places.firstOrNull { it.id == EditorChoices.STUDIO } ?: PlaceInfo.STUDIO
        out += studio.id to studio.name
        for (p in places) if (p.id != EditorChoices.STUDIO) out += p.id to "Dalaran: " + p.name
        for (l in locations) for (v in l.views.keys) out += loc(l.id, v) to "${l.name}: ${viewLabel(v)}"
        return out
    }

    /** The name a stage id is shown with ([list]'s), or the id itself. */
    fun name(stageId: String, places: List<PlaceInfo>, locations: List<LocationInfo>): String =
        list(places, locations).firstOrNull { it.first == stageId }?.second ?: stageId

    /** What Play shows of a location scene: its name, the view's camera (null when the view has none) and its spawn. */
    data class LocationStage(val name: String, val camera: CameraPose?, val spawn: Vec3?, val facing: Float)

    /**
     * The stage "loc:<id>:<view>" of [locations] as Play opens it ([Director]): the location's name with the view's, the
     * view's camera ([camera]) and where someone stands. Null for a stage that isn't a location of the list.
     */
    fun locationStage(stageId: String, locations: List<LocationInfo>): LocationStage? {
        val (id, view) = locationOf(stageId) ?: return null
        val l = locations.firstOrNull { it.id == id } ?: return null
        val v = l.views[view]
        return LocationStage("${l.name}: ${viewLabel(view)}", camera(l, view), v?.spawn, v?.facing ?: 0f)
    }

    /** The camera a location view opens on (its own; else a look at its spawn from a few yards back; else none). */
    fun camera(l: LocationInfo, view: String): CameraPose? {
        val v = l.views[view] ?: return null
        v.camera?.let { return it }
        val s = v.spawn ?: return null
        val back = Vec3(-cos(v.facing) * 6f, -sin(v.facing) * 6f, 2.5f)
        return CameraPose(s + back, s + Vec3(0f, 0f, 1f), 50f)
    }
}

// ------------------------------------------------------------------------------------------------- emotes

/** The emote picker's list: every emote of a race and sex in the catalogue's categories, in their order, filtered by words. */
object EmoteGroups {
    fun of(emotes: List<Emote>, filter: String = ""): List<Pair<String, List<Emote>>> {
        val f = filter.trim().lowercase()
        // the words match the label the player reads ("Talk (Excited)") or the game's own name ("Talkex")
        val shown = if (f.isEmpty()) emotes else emotes.filter { e ->
            val label = e.label.lowercase(); val name = e.name.lowercase()
            f.split(' ').filter { it.isNotEmpty() }.all { w -> label.contains(w) || name.contains(w) }
        }
        val groups = shown.groupBy { it.category }
        val order = EmoteCatalog.CATEGORIES + groups.keys.filter { it !in EmoteCatalog.CATEGORIES }.sorted()
        return order.mapNotNull { c -> groups[c]?.let { c to it } }
    }

    /** What an emote row says beside its name: held or once, how many voice lines, how many ways the model plays it. */
    fun detail(e: Emote): String = listOfNotNull(
        if (e.anim < 0) "voice only" else if (e.loop) "held" else "once",
        e.soundFiles.size.takeIf { it > 0 }?.let { if (it == 1) "1 voice line" else "$it voice lines" },
        e.variations.takeIf { it > 1 }?.let { "$it ways" },
    ).joinToString(" · ")

    /** A placement given the emote [e]: its animation (none for a voice-only one), the emote's id, and held or not. */
    fun apply(p: Placement, e: Emote?): Placement =
        if (e == null) p.copy(anim = null, emoteId = null)
        else p.copy(anim = e.anim.takeIf { it >= 0 }, emoteId = e.id, loop = e.loop)
}

/** Numbers typed in the inspector: a float read leniently ("1,5" too); null when it isn't one. */
fun readNumber(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

/** A number as the inspector shows it: at most [decimals] places, no trailing zeros. */
fun showNumber(v: Float, decimals: Int = 2): String {
    val s = "%.${decimals}f".format(java.util.Locale.ROOT, v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it } else s
}

/** Degrees for the inspector's yaw, and back. */
fun yawDegrees(yaw: Float): Float = Math.toDegrees(yaw.toDouble()).toFloat()
fun yawRadians(deg: Float): Float = wrapAngle(Math.toRadians(deg.toDouble()).toFloat())
