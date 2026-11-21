package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Move mode's item mapper (the live scene's character mode): the player taps a piece of furniture (or open floor), says what
 * it is ([ItemClass]), the controlled NPC is put there and held, the player nudges them into place and files a report
 * (ItemReports). The held place is kept in the item's own frame ([LocalOffset]) so it means the same wherever the piece
 * stands. Reports merged into `assets/item_overrides.json` (tools/item_reports.py) and the player's own mappings kept on the
 * phone ([MappedItems]) override the furniture's measured class and places ([ItemOverrides]). Pure.
 */
enum class ItemClass(val id: String, val label: String) {
    SEAT("seat", "Chair/seat"), TABLETOP("tabletop", "Table top"), FLOOR("floor", "Floor"), BED("bed", "Bed/couch");

    /** The furniture kind an item of this class is taken as when its own kind isn't one of this class's ([accepts]). */
    val kind: FurnitureKind get() = when (this) {
        SEAT -> FurnitureKind.CHAIR
        TABLETOP -> FurnitureKind.TABLE
        FLOOR -> FurnitureKind.FLOOR
        BED -> FurnitureKind.BED_DOUBLE
    }

    /** Whether a piece measured as [k] is of this class already (its measured shape is kept). */
    fun accepts(k: FurnitureKind): Boolean = when (this) {
        SEAT -> k == FurnitureKind.CHAIR || k == FurnitureKind.COUCH
        TABLETOP -> k == FurnitureKind.TABLE || k == FurnitureKind.COUNTER
        FLOOR -> k == FurnitureKind.FLOOR
        BED -> k.bed || k == FurnitureKind.COUCH
    }

    /** The poses an NPC can be held in on an item of this class, the usual first. */
    val holds: List<HeldPose> get() = when (this) {
        SEAT -> listOf(HeldPose.SIT, HeldPose.STAND)
        TABLETOP -> listOf(HeldPose.STAND, HeldPose.SIT)
        FLOOR -> listOf(HeldPose.STAND, HeldPose.SIT)
        BED -> listOf(HeldPose.LIE, HeldPose.SIT)
    }

    companion object {
        fun of(id: String?): ItemClass? = entries.firstOrNull { it.id == id }

        /** The class the furniture's own measurement gives [k] (to compare with the player's). */
        fun detected(k: FurnitureKind?): ItemClass? = when {
            k == null -> null
            k == FurnitureKind.CHAIR -> SEAT
            k == FurnitureKind.TABLE || k == FurnitureKind.COUNTER -> TABLETOP
            k == FurnitureKind.FLOOR -> FLOOR
            else -> BED
        }
    }
}

/** How a held NPC is posed: the scene's activity it is drawn with (its game animation) and the body's posture. */
enum class HeldPose(val id: String, val label: String, val activity: Activity, val posture: Posture) {
    STAND("stand", "Stand", Activity.IDLE, Posture.STAND),
    SIT("sit", "Sit", Activity.SIT, Posture.SIT),
    LIE("lie", "Lie", Activity.REST, Posture.LIE_BACK);

    /** The game animation it shows (0: the model's idle stand). */
    val animation: Int get() = activity.animation

    companion object {
        fun of(id: String?): HeldPose? = entries.firstOrNull { it.id == id }
    }
}

/** A place in an item's own frame: [x] its front, [y] its left, [z] up from its floor (yards at scale 1), [yawDeg] the facing from its front. */
data class LocalOffset(val x: Float, val y: Float, val z: Float, val yawDeg: Float) {
    fun moved(dx: Float = 0f, dy: Float = 0f, dz: Float = 0f, dYawDeg: Float = 0f) =
        LocalOffset(x + dx, y + dy, z + dz, ItemFrame.wrapDeg(yawDeg + dYawDeg))
}

/** An item stood at [x], [y], [z] (world, yards), its front turned [yaw] radians from +x, drawn [scale] times its model's size. */
data class ItemFrame(val x: Float, val y: Float, val z: Float, val yaw: Float, val scale: Float = 1f) {
    /** A world place and facing (radians) in this frame. */
    fun toLocal(world: Vec3, facing: Float): LocalOffset {
        val c = cos(yaw); val s = sin(yaw)
        val dx = world.x - x; val dy = world.y - y
        val k = if (scale > 1e-4f) scale else 1f
        return LocalOffset((dx * c + dy * s) / k, (-dx * s + dy * c) / k, (world.z - z) / k, wrapDeg(Math.toDegrees((facing - yaw).toDouble()).toFloat()))
    }

    /** [o] in the world: the place and the facing (radians, wrapped). */
    fun toWorld(o: LocalOffset): Pair<Vec3, Float> {
        val c = cos(yaw); val s = sin(yaw)
        val lx = o.x * scale; val ly = o.y * scale
        return Vec3(x + lx * c - ly * s, y + lx * s + ly * c, z + o.z * scale) to wrapRad(yaw + Math.toRadians(o.yawDeg.toDouble()).toFloat())
    }

    companion object {
        fun of(f: Furniture) = ItemFrame(f.x, f.y, f.z, f.yaw)

        fun wrapDeg(a: Float): Float {
            var d = a % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        fun wrapRad(a: Float): Float {
            val t = (2 * PI).toFloat()
            var r = a % t
            if (r > PI) r -= t
            if (r <= -PI) r += t
            return r
        }
    }
}

/**
 * What the player tapped: [objectName] the scene's object (CHAIR, BED...; "FLOOR" for open floor, [model] 0), its model's file
 * id, its name in the menus, where it stands in the scene ([frame]) and in its place's own frame ([placeFrame], as
 * locations.json has it: what a report says), and its measured shape ([spec]; null for open floor).
 */
data class ItemTarget(
    val placeId: String, val placeName: String, val objectName: String, val model: Int, val displayName: String,
    val frame: ItemFrame, val placeFrame: ItemFrame, val spec: FurnitureSpec?,
) {
    val openFloor: Boolean get() = spec == null
    val key: String get() = ItemMapping.key(placeId, objectName, model)
    /** The class its own measurement gives it (null: open floor, nothing measured). */
    val detected: ItemClass? get() = ItemClass.detected(spec?.kind)
}

/** Move mode's steps: on with nothing tapped, a class being picked, the NPC placed and held, the report going, and how it went. */
enum class MovePhase { IDLE, PICKING, PLACED, SENDING, REPORTED, QUEUED, FAILED }

/** The checkboxes of a report. */
enum class ReportFlag(val id: String, val label: String) {
    FITS_TWO("fits_two", "Fits two people"), TOO_SMALL("too_small", "Too small"), WRONG_FACING("wrong_facing", "Wrong facing"),
    FLOATING("floating", "Floating"), CLIPS("clips", "Clips"), OTHER("other", "Other");

    companion object {
        fun of(id: String?): ReportFlag? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Move mode's state: [phase], the tapped [target], the player's [cls], the held place ([offset], [pose]), the report's
 * [notes] and [flags], the issue it became ([issue]) or what went wrong ([message]). Every change is a new state.
 */
data class MoveState(
    val phase: MovePhase = MovePhase.IDLE,
    val target: ItemTarget? = null,
    val cls: ItemClass? = null,
    val offset: LocalOffset? = null,
    val pose: HeldPose? = null,
    val notes: String = "",
    val flags: Set<ReportFlag> = emptySet(),
    val issue: Int = 0,
    val message: String? = null,
) {
    /** Whether an NPC is held on the target (placed, or its report under way or done). */
    val holding: Boolean get() = phase != MovePhase.IDLE && phase != MovePhase.PICKING && offset != null
    val canReport: Boolean get() = holding && phase != MovePhase.SENDING

    /** An item tapped: its class is asked (open floor is Floor at once: [place] with the tapped spot). */
    fun tap(t: ItemTarget): MoveState = MoveState(MovePhase.PICKING, t)

    /** The class picked (or open floor's): the NPC goes to [at] in [p] (the class's usual place unless given). */
    fun place(c: ItemClass, at: LocalOffset? = null, p: HeldPose? = null): MoveState {
        val t = target ?: return this
        if (phase != MovePhase.PICKING && !holding) return this
        val (usual, pose0) = ItemMapping.defaultSpot(c, t)
        return copy(phase = MovePhase.PLACED, cls = c, offset = at ?: usual, pose = p ?: pose0, issue = 0, message = null)
    }

    /** The picker closed without a class: back to waiting for a tap. */
    fun cancelPick(): MoveState = if (phase == MovePhase.PICKING) MoveState() else this

    /** Moved [dx], [dy], [dz] yards in the item's frame and turned [dYawDeg]; a report already sent can be sent again. */
    fun nudge(dx: Float = 0f, dy: Float = 0f, dz: Float = 0f, dYawDeg: Float = 0f): MoveState {
        val o = offset ?: return this
        if (!holding || phase == MovePhase.SENDING) return this
        return copy(phase = MovePhase.PLACED, offset = o.moved(dx, dy, dz, dYawDeg), message = null)
    }

    fun withPose(p: HeldPose): MoveState = if (!holding || phase == MovePhase.SENDING) this else copy(phase = MovePhase.PLACED, pose = p, message = null)
    fun nextPose(): MoveState {
        val c = cls ?: return this
        val list = c.holds
        return withPose(list[(list.indexOf(pose) + 1).mod(list.size)])
    }

    fun withNotes(text: String): MoveState = copy(notes = text.take(ItemMapping.MAX_NOTES))
    fun toggle(f: ReportFlag): MoveState = copy(flags = if (f in flags) flags - f else flags + f)

    fun sending(): MoveState = if (canReport) copy(phase = MovePhase.SENDING, message = null) else this
    fun sent(number: Int): MoveState = if (phase == MovePhase.SENDING) copy(phase = MovePhase.REPORTED, issue = number, message = null) else this
    fun queued(why: String): MoveState = if (phase == MovePhase.SENDING) copy(phase = MovePhase.QUEUED, message = why) else this
    fun failed(why: String): MoveState = if (phase == MovePhase.SENDING) copy(phase = MovePhase.FAILED, message = why) else this

    /** The world place and facing the NPC is held at (null: nothing held). */
    fun world(): Pair<Vec3, Float>? {
        val t = target ?: return null
        val o = offset ?: return null
        return t.frame.toWorld(o)
    }
}

object ItemMapping {
    const val MAX_NOTES = 500
    /** The fine and coarse nudges (yards) and turns (degrees). */
    const val STEP_FINE = 0.02f
    const val STEP_COARSE = 0.1f
    const val TURN_FINE = 5f
    const val TURN_COARSE = 15f

    /** An item's key in the reports and the overrides: its place, its object and its model. */
    fun key(placeId: String, objectName: String, model: Int): String = "$placeId/$objectName/$model"

    /**
     * Where an NPC goes on [t] as [c] before any nudge, and how they're held: a seat's sit place, a bed's lying place, in front
     * of a top standing (its front's floor place), a floor place in front; on open floor, the tapped spot itself.
     */
    fun defaultSpot(c: ItemClass, t: ItemTarget): Pair<LocalOffset, HeldPose> {
        val spec = t.spec ?: return LocalOffset(0f, 0f, 0f, 0f) to c.holds.first()
        val pose = c.holds.first()
        fun deg(r: Float) = ItemFrame.wrapDeg(Math.toDegrees(r.toDouble()).toFloat())
        fun of(s: FurnitureSlot) = LocalOffset(s.x, s.y, s.height, deg(s.yaw))
        val k = spec.unitsPerMetre
        return when (c) {
            ItemClass.SEAT -> (spec.slots.firstOrNull { Posture.SIT in it.postures && it.height > 0.05f * k }?.let(::of)
                ?: LocalOffset(0f, 0f, spec.surface.takeIf { it > 0f } ?: 0.47f * k, 0f)) to pose
            ItemClass.BED -> (spec.slots.firstOrNull { Posture.LIE_BACK in it.postures && it.height > 0.05f * k }?.let(::of)
                ?: LocalOffset(0f, 0f, spec.surface, 180f)) to pose
            ItemClass.TABLETOP, ItemClass.FLOOR -> (spec.slots.firstOrNull { it.name.startsWith("floor_front") || it.name == "floor_foot" }?.let(::of)
                ?: spec.slots.firstOrNull { Posture.STAND in it.postures && it.height < 0.05f * k }?.let(::of)
                ?: LocalOffset(spec.halfX + 0.4f * k, 0f, 0f, 180f)) to pose
        }
    }

    /**
     * A place's id and its furniture's models by object (locations.json: the location named [name], its "furniture" rows'
     * "object" and "model"); null when it isn't there.
     */
    fun placeModels(locationsJson: String, name: String): Pair<String, Map<String, Int>>? {
        val root = try { JSONObject(locationsJson) } catch (e: Exception) { return null }
        val list = root.optJSONArray("locations") ?: return null
        for (i in 0 until list.length()) {
            val l = list.optJSONObject(i) ?: continue
            if (l.optString("name") != name && l.optString("id") != name) continue
            val models = LinkedHashMap<String, Int>()
            val rows = l.optJSONArray("furniture") ?: JSONArray()
            for (j in 0 until rows.length()) {
                val r = rows.optJSONObject(j) ?: continue
                val obj = r.optString("object")
                if (obj.isNotEmpty() && obj !in models) models[obj] = r.optInt("model", 0)
            }
            // an object only the interactions name: its first model
            val inter = l.optJSONArray("interactions") ?: JSONArray()
            for (j in 0 until inter.length()) {
                val r = inter.optJSONObject(j) ?: continue
                val obj = r.optString("object")
                if (obj.isEmpty() || obj in models) continue
                models[obj] = r.optJSONArray("models")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            }
            return l.optString("id") to models
        }
        return null
    }

    /** Facing radians toward [to] from [from] (open floor's NPC faces where they came from). */
    fun facingToward(from: Vec3, to: Vec3): Float =
        if (hypot(to.x - from.x, to.y - from.y) < 1e-3f) 0f else atan2(to.y - from.y, to.x - from.x)
}

/** A held place of an override: [offset] in the item's frame, [pose], how many [reports] it was made from and the [outliers] left out (issue numbers). */
data class HeldSpot(val offset: LocalOffset, val pose: HeldPose, val reports: Int = 1, val outliers: List<Int> = emptyList())

/**
 * What the player said of an item ([key]): its class ([cls]), its surface's height when known (yards over its floor), the held
 * place for each class reported ([spots]) and the notes that came with them.
 */
data class ItemOverride(val key: String, val cls: ItemClass, val surface: Float?, val spots: Map<ItemClass, HeldSpot>,
                        val notes: List<String> = emptyList(), val issues: List<Int> = emptyList())

/** Where an item's class came from: the player's own mapping on the phone, the shipped file, or the furniture's own measure. */
enum class OverrideSource { LOCAL, SHIPPED, HEURISTIC }

/** One report as the merge reads it (an issue's JSON block, or a local mapping). */
data class ReportSample(val issue: Int, val cls: ItemClass, val offset: LocalOffset, val pose: HeldPose, val surface: Float?, val notes: String = "")

object ItemOverrides {
    const val SCHEMA = 1
    /** A report this far (yards) from the median, or turned this far (degrees), is an outlier (with three reports or more). */
    const val OUTLIER_YARDS = 0.35f
    const val OUTLIER_DEG = 45f

    /** `assets/item_overrides.json` (or the phone's own mappings in the same shape): its items by key. Bad entries are skipped. */
    fun parse(json: String): Map<String, ItemOverride> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyMap() }
        val items = root.optJSONObject("items") ?: return emptyMap()
        val out = LinkedHashMap<String, ItemOverride>()
        for (key in items.keys()) {
            val o = items.optJSONObject(key) ?: continue
            val cls = ItemClass.of(o.optString("class")) ?: continue
            val spots = LinkedHashMap<ItemClass, HeldSpot>()
            o.optJSONObject("spots")?.let { s ->
                for (ck in s.keys()) {
                    val c = ItemClass.of(ck) ?: continue
                    val p = s.optJSONObject(ck) ?: continue
                    val off = LocalOffset(p.optDouble("x", Double.NaN).toFloat(), p.optDouble("y", Double.NaN).toFloat(),
                        p.optDouble("z", Double.NaN).toFloat(), p.optDouble("yaw", 0.0).toFloat())
                    if (!off.x.isFinite() || !off.y.isFinite() || !off.z.isFinite() || !off.yawDeg.isFinite()) continue
                    val outliers = p.optJSONArray("outliers")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                    spots[c] = HeldSpot(off, HeldPose.of(p.optString("pose")) ?: c.holds.first(), p.optInt("reports", 1), outliers)
                }
            }
            val surface = if (o.has("surface") && !o.isNull("surface")) o.optDouble("surface", Double.NaN).toFloat().takeIf { it.isFinite() } else null
            val notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val issues = o.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            out[key] = ItemOverride(key, cls, surface, spots, notes, issues)
        }
        return out
    }

    fun toJson(items: Collection<ItemOverride>): String {
        val all = JSONObject()
        for (o in items) {
            val spots = JSONObject()
            for ((c, s) in o.spots) spots.put(c.id, JSONObject().put("x", r(s.offset.x)).put("y", r(s.offset.y)).put("z", r(s.offset.z))
                .put("yaw", r(s.offset.yawDeg)).put("pose", s.pose.id).put("reports", s.reports).put("outliers", JSONArray(s.outliers)))
            all.put(o.key, JSONObject().put("class", o.cls.id).put("surface", o.surface?.let { r(it) } ?: JSONObject.NULL)
                .put("spots", spots).put("notes", JSONArray(o.notes)).put("issues", JSONArray(o.issues)))
        }
        return JSONObject().put("schema", SCHEMA).put("items", all).toString(1)
    }

    private fun r(v: Float): Double = Math.round(v * 1000.0) / 1000.0

    /** The override for [key]: the phone's own mapping first, then the shipped file's; none: the furniture's own measure. */
    fun lookup(key: String, local: Map<String, ItemOverride>, shipped: Map<String, ItemOverride>): Pair<ItemOverride?, OverrideSource> =
        local[key]?.let { it to OverrideSource.LOCAL } ?: shipped[key]?.let { it to OverrideSource.SHIPPED } ?: (null to OverrideSource.HEURISTIC)

    /**
     * [f] as [o] says: taken as its class (its measured shape kept when it is of that class already, else the class's usual
     * shape at the reported surface's height), the held place of its class put first among its places, at its height (the
     * surface the place rests on moved to that height for a sit or a lie: the animations' fit lands there).
     */
    fun apply(f: Furniture, o: ItemOverride): Furniture {
        val c = o.cls
        val spot = o.spots[c]
        val k = f.spec.unitsPerMetre
        val restsOn = spot != null && spot.pose != HeldPose.STAND && spot.offset.z > 0.05f * k
        val surface = (if (restsOn) spot!!.offset.z else null) ?: o.surface?.takeIf { it > 0f }
        val base = if (c.accepts(f.kind)) f.spec else FurnitureSpec.standard(c.kind, k, surface)
        if (spot == null && surface == null) return if (base === f.spec) f else Furniture(f.id, base, f.x, f.y, f.z, f.yaw)
        val regions = if (restsOn && base.regions.isNotEmpty()) {
            val main = base.regions[0]
            val grown = main.copy(height = surface!!,
                minX = minOf(main.minX, spot!!.offset.x - 0.15f * k), maxX = maxOf(main.maxX, spot.offset.x + 0.15f * k),
                minY = minOf(main.minY, spot.offset.y - 0.15f * k), maxY = maxOf(main.maxY, spot.offset.y + 0.15f * k),
                postures = main.postures + spot.pose.posture)
            listOf(grown) + base.regions.drop(1)
        } else base.regions
        val mainHeight = base.regions.firstOrNull()?.height
        val slots = base.slots.map { s ->
            // the places that rested on the old surface rest on the new one
            if (restsOn && mainHeight != null && abs(s.height - mainHeight) < 0.02f * k) s.copy(height = surface!!) else s
        }
        val mine = spot?.let { s ->
            val postures = when (s.pose) {
                HeldPose.SIT -> if (c == ItemClass.TABLETOP) setOf(Posture.SIT_ON_SURFACE, Posture.SIT) else setOf(Posture.SIT)
                HeldPose.LIE -> FurnitureSpec.LIE
                HeldPose.STAND -> if (s.offset.z > 0.05f * k) setOf(Posture.STAND, Posture.KNEEL) else setOf(Posture.STAND, Posture.KNEEL, Posture.CROUCH)
            }
            FurnitureSlot("user_${c.id}", s.offset.x, s.offset.y, Math.toRadians(s.offset.yawDeg.toDouble()).toFloat(), s.offset.z, postures)
        }
        val spec = FurnitureSpec(base.kind, base.halfX, base.halfY, maxOf(base.top, surface ?: 0f), regions, listOfNotNull(mine) + slots, k)
        return Furniture(f.id, spec, f.x, f.y, f.z, f.yaw)
    }

    /**
     * Whether an animation needing [r] belongs on an item of class [c] (the picker offers only these on an overridden item):
     * a seat the seated ones (a chair, a couch, any seat), a top the ones at a table or counter (leaning, sitting up on it),
     * the floor the floor's and open ground's, a bed the ones on a bed or couch and the lying ones.
     */
    fun compatible(c: ItemClass, r: Requirement): Boolean {
        val p = r.actors.getOrNull(r.primary)?.posture ?: Posture.UNKNOWN
        return when (c) {
            ItemClass.SEAT -> r.surface == Surface.CHAIR || r.surface == Surface.COUCH || r.surface == Surface.ANY_SEAT
            ItemClass.TABLETOP -> r.surface == Surface.TABLE_OR_COUNTER || (p == Posture.LEAN && r.surface != Surface.BED)
            ItemClass.FLOOR -> r.surface in Requirement.FLOOR_LEVEL
            ItemClass.BED -> r.surface == Surface.BED || r.surface == Surface.COUCH || (p.lying && r.surface !in Requirement.FLOOR_LEVEL)
        }
    }

    /**
     * Several reports of one item ([key]) as one override: the class most of them gave (a tie: the newest's), and for each
     * class its median place and facing and the commonest pose. With three reports or more of a class, the ones far from its
     * median ([OUTLIER_YARDS], [OUTLIER_DEG]) are left out (listed in [HeldSpot.outliers]) and the median taken again.
     */
    fun merge(key: String, samples: List<ReportSample>): ItemOverride? {
        if (samples.isEmpty()) return null
        val byClass = samples.groupBy { it.cls }
        val top = byClass.values.maxOf { it.size }
        val cls = samples.sortedByDescending { it.issue }.first { byClass.getValue(it.cls).size == top }.cls
        val spots = LinkedHashMap<ItemClass, HeldSpot>()
        for ((c, list) in byClass) {
            var kept = list
            var med = median(kept)
            val outliers = ArrayList<Int>()
            if (list.size >= 3) {
                kept = list.filter { s ->
                    val far = hypot(s.offset.x - med.x, s.offset.y - med.y) > OUTLIER_YARDS || abs(s.offset.z - med.z) > OUTLIER_YARDS ||
                        abs(ItemFrame.wrapDeg(s.offset.yawDeg - med.yawDeg)) > OUTLIER_DEG
                    if (far) outliers += s.issue
                    !far
                }
                if (kept.isEmpty()) { kept = list; outliers.clear() }
                med = median(kept)
            }
            val pose = kept.groupBy { it.pose }.entries.maxWith(compareBy<Map.Entry<HeldPose, List<ReportSample>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.issue } }).key
            spots[c] = HeldSpot(med, pose, kept.size, outliers.sorted())
        }
        val surfaces = samples.filter { it.cls == cls }.mapNotNull { it.surface }
        val surface = if (surfaces.isEmpty()) null else medianOf(surfaces)
        return ItemOverride(key, cls, surface, spots, samples.map { it.notes.trim() }.filter { it.isNotEmpty() }.distinct(),
            samples.map { it.issue }.filter { it > 0 }.distinct().sorted())
    }

    private fun medianOf(v: List<Float>): Float {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /** The median place, the facing's median taken round the first's (so -179 and 179 are 2 degrees apart, not 358). */
    private fun median(list: List<ReportSample>): LocalOffset {
        val ref = list.first().offset.yawDeg
        val yaw = ref + medianOf(list.map { ItemFrame.wrapDeg(it.offset.yawDeg - ref) })
        return LocalOffset(medianOf(list.map { it.offset.x }), medianOf(list.map { it.offset.y }), medianOf(list.map { it.offset.z }), ItemFrame.wrapDeg(yaw))
    }
}
