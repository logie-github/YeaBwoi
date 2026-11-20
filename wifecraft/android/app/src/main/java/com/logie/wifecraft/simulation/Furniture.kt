package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Furniture a scene's people can use for animations: what kind it is, where its surfaces are, and the slots bodies take on
 * it or beside it. Pure: the simulation asks [FurnitureFit.fit] which animations an object allows and where to play them.
 *
 * Frames. An object's own frame: x its front (a bed: toward its foot; a seat: the way a sitter faces; a table or counter:
 * toward the side people stand at), y its left, z up from the floor it stands on; in world units (yards in the live scene).
 * A [FurnitureSlot]'s yaw is radians in that frame: the facing for a sitting, kneeling, standing or leaning body, the
 * pelvis-to-head direction for a lying one.
 */
enum class FurnitureKind(val label: String) {
    BED_DOUBLE("Double bed"), BED_SINGLE("Bed"), COUCH("Couch"), CHAIR("Chair"), TABLE("Table"), COUNTER("Counter"), FLOOR("Floor");

    val bed: Boolean get() = this == BED_DOUBLE || this == BED_SINGLE

    companion object {
        /** A location file's or tuning's object name (TABLE, BED, SOFA...) as a kind; null for one that is not furniture (a book). */
        fun of(name: String): FurnitureKind? = when (name.trim().uppercase().replace(' ', '_')) {
            "BED", "DOUBLE_BED", "BED_DOUBLE", "KING_BED" -> BED_DOUBLE
            "SINGLE_BED", "BED_SINGLE", "KIDS_BED", "COT" -> BED_SINGLE
            "COUCH", "SOFA", "LOVESEAT", "SETTEE", "BENCH" -> COUCH
            "CHAIR", "ARMCHAIR", "DINING_CHAIR", "LIVING_CHAIR", "DESK_CHAIR", "STOOL" -> CHAIR
            "TABLE", "DESK", "DINING_TABLE" -> TABLE
            "COUNTER", "BAR", "COUNTERTOP" -> COUNTER
            "FLOOR", "RUG", "GROUND", "CARPET" -> FLOOR
            else -> null
        }
    }
}

/** A place a body takes ([postures]) at [x], [y] (object frame), facing [yaw], resting at [height] over the floor. */
data class FurnitureSlot(val name: String, val x: Float, val y: Float, val yaw: Float, val height: Float, val postures: Set<Posture>)

/** A rectangle bodies may rest on at [height] with one of [postures]; [solid]: nothing stands under it (a bed, not a table). */
data class FurnitureRegion(
    val name: String, val minX: Float, val maxX: Float, val minY: Float, val maxY: Float, val height: Float,
    val postures: Set<Posture>, val solid: Boolean = true,
) {
    fun contains(x: Float, y: Float, margin: Float = 0f) = x >= minX - margin && x <= maxX + margin && y >= minY - margin && y <= maxY + margin
}

/** A kind's shape: half its depth ([halfX]) and width ([halfY]), its tallest part ([top]), its surfaces and slots; world units. */
class FurnitureSpec(
    val kind: FurnitureKind,
    val halfX: Float, val halfY: Float, val top: Float,
    val regions: List<FurnitureRegion>,
    val slots: List<FurnitureSlot>,
    val unitsPerMetre: Float,
) {
    /** The main surface's height (the bed, the seat, the top; 0 for the floor). */
    val surface: Float get() = regions.firstOrNull()?.height ?: 0f

    companion object {
        const val YARDS_PER_METRE = 1.0936133f
        private const val HALF_PI = (PI / 2).toFloat()
        private const val TURN = PI.toFloat()

        val LIE = setOf(Posture.LIE_BACK, Posture.LIE_FRONT, Posture.LIE_SIDE)
        val ON_FLOOR = setOf(Posture.STAND, Posture.KNEEL, Posture.CROUCH, Posture.ALL_FOURS, Posture.SIT) + LIE
        private val ON_BED = LIE + setOf(Posture.KNEEL, Posture.ALL_FOURS, Posture.CROUCH, Posture.SIT, Posture.LEAN)
        private val ON_TOP = LIE + setOf(Posture.SIT_ON_SURFACE, Posture.SIT, Posture.KNEEL, Posture.ALL_FOURS, Posture.LEAN)
        private val BESIDE = setOf(Posture.STAND, Posture.KNEEL, Posture.CROUCH)

        /**
         * The usual shape of [kind] in a scene that measures [unitsPerMetre] world units to a metre (yards: [YARDS_PER_METRE]);
         * [surface] the measured height of its bed, seat or top in world units when known (its regions and slots follow it).
         */
        fun standard(kind: FurnitureKind, unitsPerMetre: Float = YARDS_PER_METRE, surface: Float? = null): FurnitureSpec {
            val k = unitsPerMetre
            fun m(v: Double) = (v * k).toFloat()
            fun s(name: String, x: Double, y: Double, yaw: Float, h: Float, p: Set<Posture>) = FurnitureSlot(name, m(x), m(y), yaw, h, p)
            return when (kind) {
                FurnitureKind.BED_DOUBLE, FurnitureKind.BED_SINGLE -> {
                    val double = kind == FurnitureKind.BED_DOUBLE
                    val hx = 1.05; val hy = if (double) 0.8 else 0.5
                    val h = surface ?: m(0.55)
                    val edge = hy - 0.18
                    val lies = if (double) listOf(s("lie_left", 0.1, 0.4, TURN, h, LIE), s("lie_right", 0.1, -0.4, TURN, h, LIE),
                        s("lie_middle", 0.1, 0.0, TURN, h, LIE)) else listOf(s("lie_middle", 0.1, 0.0, TURN, h, LIE))
                    FurnitureSpec(kind, m(hx), m(hy), m(1.1),
                        listOf(FurnitureRegion("bed", m(-hx), m(hx), m(-hy), m(hy), h, ON_BED)),
                        lies + listOf(
                            s("kneel_middle", 0.3, 0.0, TURN, h, setOf(Posture.KNEEL, Posture.ALL_FOURS, Posture.CROUCH)),
                            s("kneel_foot", 0.5, 0.0, 0f, h, setOf(Posture.KNEEL, Posture.ALL_FOURS, Posture.CROUCH)),
                            s("sit_left", 0.1, edge, HALF_PI, h, setOf(Posture.SIT)),
                            s("sit_right", 0.1, -edge, -HALF_PI, h, setOf(Posture.SIT)),
                            s("sit_foot", hx - 0.2, 0.0, 0f, h, setOf(Posture.SIT)),
                            s("lean_foot", hx - 0.15, 0.0, TURN, h, setOf(Posture.LEAN)),
                            s("lean_left", 0.0, hy - 0.15, -HALF_PI, h, setOf(Posture.LEAN)),
                            s("lean_right", 0.0, -(hy - 0.15), HALF_PI, h, setOf(Posture.LEAN)),
                            s("floor_left", 0.1, hy + 0.4, -HALF_PI, 0f, BESIDE),
                            s("floor_right", 0.1, -(hy + 0.4), HALF_PI, 0f, BESIDE),
                            s("floor_foot", hx + 0.4, 0.0, TURN, 0f, BESIDE),
                        ), k)
                }
                FurnitureKind.COUCH -> {
                    val h = surface ?: m(0.45)
                    FurnitureSpec(kind, m(0.45), m(1.0), m(0.9),
                        listOf(
                            FurnitureRegion("seat", m(-0.25), m(0.45), m(-0.95), m(0.95), h,
                                LIE + setOf(Posture.SIT, Posture.KNEEL, Posture.ALL_FOURS, Posture.CROUCH)),
                            FurnitureRegion("back", m(-0.45), m(-0.25), m(-1.0), m(1.0), h + m(0.4), setOf(Posture.LEAN)),
                        ),
                        listOf(
                            s("seat_left", 0.05, 0.6, 0f, h, setOf(Posture.SIT)),
                            s("seat_middle", 0.05, 0.0, 0f, h, setOf(Posture.SIT)),
                            s("seat_right", 0.05, -0.6, 0f, h, setOf(Posture.SIT)),
                            s("lie", 0.1, -0.2, HALF_PI, h, LIE),
                            s("kneel_seat", 0.1, 0.0, TURN, h, setOf(Posture.KNEEL, Posture.ALL_FOURS)),
                            s("lean_back", -0.35, 0.0, 0f, h + m(0.4), setOf(Posture.LEAN)),
                            s("floor_front", 0.95, 0.0, TURN, 0f, BESIDE),
                            s("floor_front_left", 0.95, 0.6, TURN, 0f, BESIDE),
                            s("floor_front_right", 0.95, -0.6, TURN, 0f, BESIDE),
                        ), k)
                }
                FurnitureKind.CHAIR -> {
                    val h = surface ?: m(0.47)
                    FurnitureSpec(kind, m(0.27), m(0.27), m(0.95),
                        listOf(
                            FurnitureRegion("seat", m(-0.2), m(0.27), m(-0.25), m(0.25), h, setOf(Posture.SIT)),
                            FurnitureRegion("back", m(-0.27), m(-0.2), m(-0.27), m(0.27), h + m(0.45), setOf(Posture.LEAN)),
                        ),
                        listOf(
                            s("seat", 0.0, 0.0, 0f, h, setOf(Posture.SIT)),
                            s("lean_back", -0.24, 0.0, 0f, h + m(0.45), setOf(Posture.LEAN)),
                            s("floor_front", 0.75, 0.0, TURN, 0f, BESIDE),
                            s("floor_left", 0.1, 0.65, -HALF_PI, 0f, BESIDE),
                            s("floor_right", 0.1, -0.65, HALF_PI, 0f, BESIDE),
                        ), k)
                }
                FurnitureKind.TABLE, FurnitureKind.COUNTER -> {
                    val table = kind == FurnitureKind.TABLE
                    val hx = if (table) 0.45 else 0.3; val hy = if (table) 0.8 else 1.0
                    val h = surface ?: m(if (table) 0.75 else 0.92)
                    FurnitureSpec(kind, m(hx), m(hy), h,
                        listOf(FurnitureRegion("top", m(-hx), m(hx), m(-hy), m(hy), h, ON_TOP, solid = !table)),
                        listOf(
                            s("lean_front", hx - 0.15, 0.0, TURN, h, setOf(Posture.LEAN)),
                            s("sit_front", hx - 0.15, 0.0, 0f, h, setOf(Posture.SIT_ON_SURFACE, Posture.SIT)),
                            s("lie_top", 0.0, 0.0, HALF_PI, h, LIE),
                            s("kneel_top", 0.0, 0.0, TURN, h, setOf(Posture.KNEEL, Posture.ALL_FOURS)),
                            s("floor_front", hx + 0.4, 0.0, TURN, 0f, BESIDE),
                            s("floor_left", 0.0, hy + 0.35, -HALF_PI, 0f, BESIDE),
                            s("floor_right", 0.0, -(hy + 0.35), HALF_PI, 0f, BESIDE),
                        ), k)
                }
                FurnitureKind.FLOOR -> FurnitureSpec(kind, m(1.5), m(1.5), 0f,
                    listOf(FurnitureRegion("floor", m(-1.5), m(1.5), m(-1.5), m(1.5), 0f, ON_FLOOR, solid = false)),
                    listOf(
                        s("center", 0.0, 0.0, 0f, 0f, ON_FLOOR),
                        s("front", 0.7, 0.0, TURN, 0f, ON_FLOOR),
                        s("back", -0.7, 0.0, 0f, 0f, ON_FLOOR),
                    ), k)
            }
        }
    }
}

/** One piece of furniture in the scene: [spec] stood at [x], [y] on the floor at [z], its front turned [yaw] radians from +x. */
class Furniture(val id: String, val spec: FurnitureSpec, val x: Float, val y: Float, val z: Float, val yaw: Float) {
    val kind: FurnitureKind get() = spec.kind

    /** A point of its frame in the world (z: over its floor). */
    fun toWorld(lx: Float, ly: Float, lz: Float = 0f): Vec3 {
        val c = cos(yaw); val s = sin(yaw)
        return Vec3(x + lx * c - ly * s, y + lx * s + ly * c, z + lz)
    }

    /** A world point in its frame (x, y). */
    fun toLocal(wx: Float, wy: Float): Pair<Float, Float> {
        val c = cos(yaw); val s = sin(yaw)
        val dx = wx - x; val dy = wy - y
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    /** Its eight corners' box in the world: min and max (for the cameras). */
    fun bounds(): Pair<Vec3, Vec3> {
        val pts = listOf(-1f to -1f, -1f to 1f, 1f to -1f, 1f to 1f).map { (a, b) -> toWorld(a * spec.halfX, b * spec.halfY) }
        return Vec3(pts.minOf { it.x }, pts.minOf { it.y }, z) to Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, z + spec.top)
    }

    companion object {
        /**
         * The live scene's own objects ([SceneObject]) as furniture, at their measured [spot] when there is one. The object's
         * front: a chair's or couch's sitter's facing; a table's, the side its reader stands at (facing + PI); a bed's foot is
         * taken along the spot's facing (the rest pose's direction: not checked against the model). Bed height from the spot.
         */
        fun of(item: SceneObject, spot: InteractionSpot?, unitsPerMetre: Float = FurnitureSpec.YARDS_PER_METRE): Furniture {
            val facing = spot?.facing ?: when (item) { SceneObject.TABLE -> (PI / 2).toFloat(); else -> -(PI / 2).toFloat() }
            return when (item) {
                SceneObject.BED -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.BED_DOUBLE, unitsPerMetre,
                    spot?.pose?.z?.takeIf { it > 0.1f }), spot?.pose?.x ?: item.position.x, spot?.pose?.y ?: item.position.y, item.position.z, facing)
                SceneObject.CHAIR -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.CHAIR, unitsPerMetre),
                    spot?.pose?.x ?: item.position.x, spot?.pose?.y ?: item.position.y, item.position.z, facing)
                SceneObject.COUCH -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.COUCH, unitsPerMetre),
                    spot?.pose?.x ?: item.position.x, spot?.pose?.y ?: item.position.y, item.position.z, facing)
                SceneObject.TABLE -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.TABLE, unitsPerMetre),
                    item.position.x, item.position.y, item.position.z, facing + PI.toFloat())
            }
        }

        /**
         * A location's interaction (locations.json "interactions": an object name, the entry where its user stands and their
         * facing, the models) as furniture: the first model is the object (the Embassy's study table), the others its props
         * (books) and stay props. Its front faces the entry (yaw = facing + PI). Null for a name that is not furniture.
         */
        fun of(id: String, objectName: String, entry: Vec3, facing: Float, models: List<Vec3>,
               unitsPerMetre: Float = FurnitureSpec.YARDS_PER_METRE): Furniture? {
            val kind = FurnitureKind.of(objectName) ?: return null
            val at = models.firstOrNull() ?: entry
            return Furniture(id, FurnitureSpec.standard(kind, unitsPerMetre), at.x, at.y, entry.z, facing + PI.toFloat())
        }
    }
}

/**
 * Where an animation plays on a piece of furniture: the clip's shared origin at [x], [y], [z] (world) turned [yaw] radians,
 * so each actor's support lands on a surface that takes its posture at its height; [slots] the slot (or surface: "bed",
 * "floor"...) each actor takes, in the clip's role order; [positions] where each actor's support lands (world); [lift]
 * how far the clip is raised (world units: a floor-level clip played on a bed); [score] smaller is better.
 */
data class FurnitureFit(
    val furniture: String, val x: Float, val y: Float, val z: Float, val yaw: Float,
    val slots: List<String>, val positions: List<Vec3>, val lift: Float, val score: Float,
) {
    companion object {
        /** A standing pelvis in metres; [DEFAULT_BODY_UNIT] the same in yards. */
        const val DEFAULT_BODY_UNIT = 0.95f * FurnitureSpec.YARDS_PER_METRE
        /** Heights (metres) within this of a surface rest on it. */
        const val HEIGHT_TOLERANCE_M = 0.16f
        /** How far a lying body's head or feet may hang past its surface (metres). */
        const val OVERHANG_M = 0.3f
        /** How far from the furniture a floor-level actor may be (metres). */
        const val REACH_M = 2.0f

        /** Which kinds a requirement's surface may be played on at all (the geometry then decides: [fit]). */
        fun allows(surface: Surface, kind: FurnitureKind): Boolean = when (surface) {
            Surface.BED -> kind.bed
            Surface.COUCH -> kind == FurnitureKind.COUCH || kind == FurnitureKind.CHAIR
            Surface.CHAIR -> kind == FurnitureKind.CHAIR || kind == FurnitureKind.COUCH
            Surface.ANY_SEAT -> kind == FurnitureKind.CHAIR || kind == FurnitureKind.COUCH || kind.bed
            Surface.TABLE_OR_COUNTER -> kind == FurnitureKind.TABLE || kind == FurnitureKind.COUNTER
            Surface.FLOOR -> kind == FurnitureKind.FLOOR || kind.bed
            Surface.NONE -> kind == FurnitureKind.FLOOR
            Surface.UNKNOWN -> false
        }

        /** Whether [r] can play on [f] (with [bodyUnit]: the actors' standing pelvis height in world units). */
        fun compatible(r: Requirement, f: Furniture, bodyUnit: Float = DEFAULT_BODY_UNIT): Boolean = fit(r, f, bodyUnit) != null

        /**
         * The best place for [r] on [f], or null when it can't play there. Each actor that is on the furniture (else each
         * actor) is tried on each slot that takes its posture: its support on the slot, its facing (or head) along the slot,
         * the clip raised by the slot's height less its own; then every actor must land on a surface (or the floor around)
         * that takes its posture within [HEIGHT_TOLERANCE_M]; a lying body's head and feet must be over it (give
         * [OVERHANG_M]); feet that stay on the floor forbid raising the clip and must not land inside a solid piece.
         * The fit whose actors land nearest slots, at the closest heights, wins. [bodyUnit]: world units of one body unit
         * (the actor's standing pelvis height as drawn).
         */
        fun fit(r: Requirement, f: Furniture, bodyUnit: Float = DEFAULT_BODY_UNIT): FurnitureFit? {
            if (r.actors.isEmpty() || !allows(r.surface, f.kind)) return null
            val k = f.spec.unitsPerMetre
            val tol = HEIGHT_TOLERANCE_M * k
            val raised = r.actors.indices.filter { r.actors[it].surface !in Requirement.FLOOR_LEVEL }
            val primaries = raised.ifEmpty { r.actors.indices.toList() }
            var best: FurnitureFit? = null
            for (i in primaries) {
                val a = r.actors[i]
                for (slot in f.spec.slots) {
                    if (a.posture !in slot.postures) continue
                    val theta = slot.yaw - Math.toRadians(a.yaw.toDouble()).toFloat()
                    val c = cos(theta); val s = sin(theta)
                    val ax = a.x * bodyUnit; val ay = a.y * bodyUnit
                    val tx = slot.x - (ax * c - ay * s)
                    val ty = slot.y - (ax * s + ay * c)
                    val lift = slot.height - a.heightUnits * bodyUnit
                    val landed = ArrayList<String>(r.actors.size)
                    val at = ArrayList<Vec3>(r.actors.size)
                    var score = 0f
                    var ok = true
                    for ((j, b) in r.actors.withIndex()) {
                        fun place(px: Float, py: Float) = (tx + (px * bodyUnit) * c - (py * bodyUnit) * s) to (ty + (px * bodyUnit) * s + (py * bodyUnit) * c)
                        val (px, py) = place(b.x, b.y)
                        val pz = b.heightUnits * bodyUnit + lift
                        val land = land(f, b.posture, px, py, pz, tol, k)
                        if (land == null) { ok = false; break }
                        if (b.posture.lying) {
                            val (hx, hy) = place(b.headX, b.headY)
                            val (fx, fy) = place(b.feetX, b.feetY)
                            if (!over(f, land, hx, hy, k) || !over(f, land, fx, fy, k)) { ok = false; break }
                        }
                        if (b.feetOnFloor) {
                            if (abs(lift) > tol) { ok = false; break }
                            val (fx, fy) = place(b.feetX, b.feetY)
                            if (f.spec.regions.any { it.solid && it.height > tol && it.contains(fx, fy, -0.1f * k) && land.region !== it }) { ok = false; break }
                        }
                        // the slot it lands on (or the surface)
                        val near = f.spec.slots.filter { b.posture in it.postures && abs(it.height - land.height) <= tol }
                            .minByOrNull { hypot(it.x - px, it.y - py) }
                        val d = near?.let { hypot(it.x - px, it.y - py) } ?: Float.MAX_VALUE
                        landed += if (near != null && d < 0.5f * k) near.name else land.name
                        score += minOf(d, 1.5f * k) / k + 2f * land.error / k
                        at += f.toWorld(px, py, pz)
                    }
                    if (!ok) continue
                    score += abs(lift) / k * 0.5f
                    if (best == null || score < best.score - 1e-4f) {
                        val o = f.toWorld(tx, ty, lift)
                        best = FurnitureFit(f.id, o.x, o.y, o.z, wrap(f.yaw + theta), landed, at, lift, score)
                    }
                }
            }
            return best
        }

        private class Land(val name: String, val height: Float, val error: Float, val region: FurnitureRegion?)

        /** The surface under ([x], [y], object frame) that takes [posture] at [z], else the floor around, else null. */
        private fun land(f: Furniture, posture: Posture, x: Float, y: Float, z: Float, tol: Float, k: Float): Land? {
            for (r in f.spec.regions) {
                if (posture !in r.postures || !r.contains(x, y)) continue
                val e = abs(r.height - z)
                if (e <= tol) return Land(r.name, r.height, e, r)
            }
            if (posture == Posture.LEAN || posture == Posture.SIT_ON_SURFACE || posture == Posture.UNKNOWN) return null
            if (abs(z) > tol) return null
            if (f.spec.regions.any { it.solid && it.height > tol && it.contains(x, y, -0.05f * k) }) return null
            val reach = REACH_M * k
            if (abs(x) > f.spec.halfX + reach || abs(y) > f.spec.halfY + reach) return null
            return Land("floor", 0f, abs(z), null)
        }

        /** Whether a lying body's end at [x], [y] is over the surface it lies on (the floor: not inside a solid piece). */
        private fun over(f: Furniture, land: Land, x: Float, y: Float, k: Float): Boolean {
            val r = land.region ?: return f.spec.regions.none { it.solid && it.height > 0.2f * k && it.contains(x, y, -0.15f * k) }
            return r.contains(x, y, OVERHANG_M * k)
        }

        private fun wrap(a: Float): Float {
            var x = a % (2 * PI).toFloat()
            if (x > PI) x -= (2 * PI).toFloat()
            if (x < -PI) x += (2 * PI).toFloat()
            return x
        }
    }
}
