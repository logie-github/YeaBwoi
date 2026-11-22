package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Build mode's New tab: spots the player makes ([UserSpot]) where a body can sit, lie or stand. A spot is a type (Surface,
 * Chair, Table, Bed, or a Custom label that maps to one of those classes), a footprint (a box, [UserSpot.halfX] by
 * [UserSpot.halfY] yards, turned [UserSpot.yawDeg]), the place and a surface height. From then on it is interactive: it stands
 * in for the scene's object of its class (the same machinery as an item given an interaction: [furniture] is what the furniture
 * system takes, so what plays on it, where bodies go and the "every animation plays anywhere" rule are the furniture's own),
 * it can be tapped (Play animation / Sit here), and it sparkles outside Build mode ([Sparkles]). Kept per place in the build
 * file (files/build/<place>.json, [BuildDoc.spots]) and sent in the Build report as items[] with "user": true. Pure.
 */
enum class SpotKind(val id: String, val label: String, val cls: ItemClass) {
    SURFACE("surface", "Surface", ItemClass.FLOOR),
    CHAIR("chair", "Chair", ItemClass.SEAT),
    TABLE("table", "Table", ItemClass.TABLETOP),
    BED("bed", "Bed", ItemClass.BED),
    CUSTOM("custom", "Custom label", ItemClass.FLOOR);

    companion object {
        fun of(id: String?): SpotKind? = entries.firstOrNull { it.id == id }
        /** The classes a Custom spot can map to (the same list as the types). */
        val CLASSES = listOf(ItemClass.FLOOR, ItemClass.SEAT, ItemClass.TABLETOP, ItemClass.BED)
        fun classLabel(c: ItemClass): String = when (c) {
            ItemClass.FLOOR -> "Surface"; ItemClass.SEAT -> "Chair"; ItemClass.TABLETOP -> "Table"; ItemClass.BED -> "Bed"; else -> c.label
        }
    }
}

data class UserSpot(
    val id: String, val kind: SpotKind, val label: String, val cls: ItemClass,
    /** The place's frame, yards: [x], [y] and [z] the floor under it; [surface] its height over that floor; [yawDeg] its facing. */
    val x: Float, val y: Float, val z: Float, val yawDeg: Float,
    val halfX: Float, val halfY: Float, val surface: Float,
    val sparkle: Boolean = true, val shipped: Boolean = false,
) {
    /** What the player sees it called: the custom label, else its type. */
    val name: String get() = label.trim().ifEmpty { if (kind == SpotKind.CUSTOM) SpotKind.classLabel(cls) else kind.label }
    val key: String get() = "spot:$id"
    /** The scene object it stands in for (the furniture system's four). */
    val slot: SceneObject get() = Spots.slotFor(cls)
    val top: Float get() = z + surface
    val yaw: Float get() = Math.toRadians(yawDeg.toDouble()).toFloat()

    /** Whether the place point ([px], [py]) is over its footprint and, with [pz], near its surface (within [reach] yards). */
    fun contains(px: Float, py: Float, pz: Float? = null, reach: Float = 0.9f): Boolean {
        val c = cos(yaw); val s = sin(yaw)
        val dx = px - x; val dy = py - y
        val lx = dx * c + dy * s; val ly = -dx * s + dy * c
        if (abs(lx) > halfX || abs(ly) > halfY) return false
        return pz == null || abs(pz - top) <= reach
    }

    /** The furniture it is, in the simulation's frame: [toLocal] maps the place's frame, [rotation] the stage's turn. */
    fun furniture(toLocal: (Vec3) -> Vec3, rotation: Float): Furniture {
        val at = toLocal(Vec3(x, y, z))
        val spec = Spots.spec(cls, halfX, halfY, surface)
        return Furniture(slot.name, spec, at.x, at.y, at.z, ItemFrame.wrapRad(yaw - rotation))
    }

    fun toJson(): JSONObject = JSONObject().put("user", true).put("key", key).put("kind", kind.id).put("label", name).put("name", name)
        .put("class", cls.id).put("id", id)
        .put("position", org.json.JSONArray(listOf(rd(x), rd(y), rd(z)))).put("yawDeg", rd(yawDeg))
        .put("box", JSONObject().put("x", rd(2 * halfX)).put("y", rd(2 * halfY)).put("z", rd(surface)))
        .put("surface", rd(surface)).put("sparkle", sparkle)
        .put("supports", org.json.JSONArray(cls.supports.sortedBy { it.ordinal }.map { it.id }))
        .put("anchor", JSONObject().put("x", 0.0).put("y", 0.0).put("z", rd(surface)).put("yaw", 0.0))

    companion object {
        private fun rd(v: Float): Double = Math.round(v * 1000.0) / 1000.0

        /** One from an items[] entry flagged "user" (the build file's or a report's); null when it is not one. */
        fun of(o: JSONObject, shipped: Boolean = false): UserSpot? {
            if (!o.optBoolean("user", false)) return null
            val kind = SpotKind.of(o.optString("kind")) ?: return null
            val cls = ItemClass.of(o.optString("class")) ?: kind.cls
            val p = o.optJSONArray("position") ?: return null
            val x = p.optDouble(0, Double.NaN).toFloat(); val y = p.optDouble(1, Double.NaN).toFloat(); val z = p.optDouble(2, Double.NaN).toFloat()
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
            val box = o.optJSONObject("box") ?: JSONObject()
            val hx = (box.optDouble("x", 1.0).toFloat() / 2f).coerceIn(Spots.MIN_HALF, Spots.MAX_HALF)
            val hy = (box.optDouble("y", 1.0).toFloat() / 2f).coerceIn(Spots.MIN_HALF, Spots.MAX_HALF)
            val label = if (kind == SpotKind.CUSTOM) o.optString("label").take(BuildDocs.MAX_NAME) else ""
            val id = o.optString("id").ifEmpty { o.optString("key").removePrefix("spot:") }.ifEmpty { return null }
            return UserSpot(id, kind, label, cls, x, y, z, o.optDouble("yawDeg", 0.0).toFloat(), hx, hy,
                o.optDouble("surface", box.optDouble("z", 0.0)).toFloat().coerceAtLeast(0f), o.optBoolean("sparkle", true), shipped)
        }
    }
}

object Spots {
    const val MIN_HALF = 0.15f
    const val MAX_HALF = 3f
    /** One + or - press changes a footprint side by this many yards (its half by half of it). */
    const val STEP = 0.1f

    /** The scene object a class stands in for: a seat the chair, a top or wall the table, a bed the bed, the floor the couch. */
    fun slotFor(cls: ItemClass): SceneObject = when (cls) {
        ItemClass.SEAT -> SceneObject.CHAIR
        ItemClass.TABLETOP, ItemClass.WALL -> SceneObject.TABLE
        ItemClass.BED -> SceneObject.BED
        ItemClass.FLOOR, ItemClass.OTHER -> SceneObject.COUCH
    }

    /** The class's usual shape resized to [halfX] x [halfY] (regions and slots scaled with it) at [surface] yards (0: the usual). */
    fun spec(cls: ItemClass, halfX: Float, halfY: Float, surface: Float): FurnitureSpec {
        val base = FurnitureSpec.standard(cls.kind, surface = surface.takeIf { it > 0f && cls != ItemClass.FLOOR })
        val fx = if (base.halfX > 1e-4f) halfX / base.halfX else 1f
        val fy = if (base.halfY > 1e-4f) halfY / base.halfY else 1f
        return FurnitureSpec(base.kind, halfX, halfY, base.top,
            base.regions.map { it.copy(minX = it.minX * fx, maxX = it.maxX * fx, minY = it.minY * fy, maxY = it.maxY * fy) },
            base.slots.map { it.copy(x = it.x * fx, y = it.y * fy) }, base.unitsPerMetre)
    }

    /** A new spot of [kind] (a Custom one with [label] and [cls]) at the place point, the class's usual size and height. */
    fun make(id: String, kind: SpotKind, label: String, cls: ItemClass?, x: Float, y: Float, floorZ: Float, hitZ: Float, yawDeg: Float): UserSpot {
        val c = if (kind == SpotKind.CUSTOM) (cls ?: ItemClass.FLOOR) else kind.cls
        val base = FurnitureSpec.standard(c.kind)
        // the surface's height defaults from the hit point (a prop's top), else the class's own
        val fromHit = (hitZ - floorZ).takeIf { it > 0.05f && c != ItemClass.FLOOR }
        val surface = fromHit ?: if (c == ItemClass.FLOOR) 0f else base.surface
        return UserSpot(id, kind, if (kind == SpotKind.CUSTOM) label.trim().take(BuildDocs.MAX_NAME) else "", c, x, y, if (c == ItemClass.FLOOR) hitZ else floorZ, ItemFrame.wrapDeg(yawDeg),
            base.halfX.coerceIn(MIN_HALF, MAX_HALF), base.halfY.coerceIn(MIN_HALF, MAX_HALF), surface)
    }

    /** [s] with its footprint's width (y) or depth (x) changed by [steps] presses (each [STEP] yards across), kept within limits. */
    fun resize(s: UserSpot, widthSteps: Int, depthSteps: Int): UserSpot = s.copy(
        halfX = (s.halfX + depthSteps * STEP / 2f).coerceIn(MIN_HALF, MAX_HALF), halfY = (s.halfY + widthSteps * STEP / 2f).coerceIn(MIN_HALF, MAX_HALF))

    fun setSurface(s: UserSpot, surface: Float): UserSpot = s.copy(surface = surface.coerceIn(0f, 3f))

    /** The topmost spot under the place point (surface highest first), or null. */
    fun hit(spots: Collection<UserSpot>, x: Float, y: Float, z: Float? = null): UserSpot? =
        spots.filter { it.contains(x, y, z) }.maxByOrNull { it.top }

    /** The shipped spots (assets/user_spots.json, made by tools/item_reports.py from build reports): each place's, by its id. */
    fun shipped(json: String): Map<String, List<UserSpot>> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyMap() }
        val places = root.optJSONObject("places") ?: return emptyMap()
        val out = LinkedHashMap<String, List<UserSpot>>()
        for (id in places.keys()) {
            val a = places.optJSONArray(id) ?: continue
            out[id] = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { UserSpot.of(it.put("user", true), shipped = true) } }
        }
        return out
    }

    /** Where the scene's own spot for the slot [f] stands in for is, as the simulation's interaction spot (its entry on the floor beside it). */
    fun interactionSpot(slot: SceneObject, f: Furniture): InteractionSpot {
        val facing = if (slot == SceneObject.BED) ItemFrame.wrapRad(f.yaw - Math.PI.toFloat()) else f.yaw
        val i = slot.interaction(Vec3(f.x, f.y, f.z + f.spec.surface), facing, f.spec.halfX)
        return i.copy(entry = i.entry.copy(z = f.z))
    }

    /** The player's phrase for the slot's menu entry of a spot. */
    fun useLabel(s: UserSpot): String = when (s.cls) { ItemClass.SEAT -> "Sit here"; ItemClass.BED -> "Lie here"; else -> "Play animation" }
}

object SpotEdits {
    fun add(d: BuildDoc, s: UserSpot): BuildDoc = d.copy(spots = d.spots + (s.id to s))
    fun update(d: BuildDoc, s: UserSpot): BuildDoc = if (s.id in d.spots) d.copy(spots = d.spots + (s.id to s)) else d
    fun remove(d: BuildDoc, id: String): BuildDoc = d.copy(spots = d.spots - id)
    fun move(d: BuildDoc, id: String, p: BuildPose): BuildDoc =
        d.spots[id]?.let { update(d, it.copy(x = p.x, y = p.y, z = p.z - it.surface, yawDeg = p.yawDeg)) } ?: d
}

/**
 * The sparkles' rules (engine/sparkle.h draws): which spots show them and how many. Off in Build mode (its own overlay), in
 * cinematic mode (the film look) and with the switch off; hidden while a spot is occupied; a user spot's own switch.
 */
object Sparkles {
    const val PREFS = "sparkles"
    const val KEY_ON = "on"
    /** One source: the surface's middle (place frame, stage frame for the engine), footprint and turn, [occupied], [enabled]. */
    data class Source(val key: String, val at: Vec3, val halfX: Float, val halfY: Float, val yawRad: Float, val occupied: Boolean, val enabled: Boolean = true)

    fun shown(globalOn: Boolean, buildOn: Boolean, cinematic: Boolean): Boolean = globalOn && !buildOn && !cinematic

    /** The Graphics governor's lowered steps as a density: full, then half, then a few, then none (the engine adds nothing it can't afford). */
    fun density(loweredSteps: Int): Float = when { loweredSteps <= 0 -> 1f; loweredSteps == 1 -> 0.6f; loweredSteps == 2 -> 0.35f; else -> 0f }

    /** The engine's floats (8 a spot, [SparkleLimits]): x, y, z, halfX, halfY, yaw, strength, seed. Empty when nothing shows. */
    fun floats(sources: List<Source>, show: Boolean, max: Int = 48): FloatArray {
        if (!show) return FloatArray(0)
        val use = sources.filter { it.enabled && !it.occupied }.take(max)
        val out = FloatArray(use.size * 8)
        use.forEachIndexed { i, s ->
            val k = i * 8
            out[k] = s.at.x; out[k + 1] = s.at.y; out[k + 2] = s.at.z; out[k + 3] = s.halfX; out[k + 4] = s.halfY; out[k + 5] = s.yawRad
            out[k + 6] = 1f; out[k + 7] = (s.key.hashCode() and 0xffff).toFloat()
        }
        return out
    }

    /** Whether a furniture piece is worth a sparkle: it has a surface a body can rest on. */
    fun usable(f: Furniture): Boolean = f.spec.regions.any { it.postures.isNotEmpty() }
}
