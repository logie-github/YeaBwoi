package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.location.FurnitureExtent
import com.logie.wifecraft.location.FurniturePiece
import com.logie.wifecraft.location.PiecePart
import com.logie.wifecraft.location.PieceRect
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

/**
 * A rectangle bodies may rest on at [height] with one of [postures]; [solid]: nothing stands under it (a bed, not a table).
 * One with no [postures] is a solid part nobody rests on (a headboard, a bedpost, a chair's arm): feet and the ends of a
 * lying body keep out of it.
 */
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

        /** A bed this wide (yards) or wider takes two lying side by side; a narrower one, one (or two head to foot). */
        const val DOUBLE_WIDTH_YARDS = 1.3f
        /** A side with less free room than this (yards) has no standing, sitting or leaning places on it. */
        const val FREE_SIDE_YARDS = 0.45f

        /**
         * [kind] as [e] measured it (FurnitureExtent: a bed's real mattress, a chair's seat, a table's top, the solid parts
         * standing over them and the room beside each side) in a scene of [unitsPerMetre] world units to a metre. [e] is in
         * yards in the piece's frame with its footprint centred on 0, 0 and a bed's head toward -x ([Furniture.of] makes it
         * so). The places on it are the usual ones moved to its real edges: a bed's sits on the mattress's real edges (the
         * hips just in from the edge, the legs over the side), its lying places from the head end, side by side only on a
         * bed [DOUBLE_WIDTH_YARDS] wide or more; a chair's seat from the seat's real front; the places beside it only on
         * sides with room ([FREE_SIDE_YARDS]); its solid parts become regions nobody rests on.
         */
        fun measured(kind: FurnitureKind, e: FurnitureExtent, unitsPerMetre: Float = YARDS_PER_METRE): FurnitureSpec {
            if (kind == FurnitureKind.FLOOR) return standard(kind, unitsPerMetre)
            val k = unitsPerMetre
            val u = k / YARDS_PER_METRE                                   // yards to world units
            fun m(v: Double) = (v * k).toFloat()
            fun r(v: Float) = v * u
            val box = e.box; val sf = e.surface
            val h = r(e.surfaceTop)
            val hx = r(box.lengthX) / 2; val hy = r(box.widthY) / 2
            val sx0 = r(sf.minX); val sx1 = r(sf.maxX); val sy0 = r(sf.minY); val sy1 = r(sf.maxY)
            val smx = (sx0 + sx1) / 2; val smy = (sy0 + sy1) / 2
            fun free(side: String) = e.free(side) >= FREE_SIDE_YARDS
            fun s(name: String, x: Float, y: Float, yaw: Float, height: Float, p: Set<Posture>) = FurnitureSlot(name, x, y, yaw, height, p)
            val frames = e.parts.mapIndexed { i, part ->
                FurnitureRegion("frame$i", r(part.rect.minX), r(part.rect.maxX), r(part.rect.minY), r(part.rect.maxY), r(part.top), emptySet())
            }
            val beside = ArrayList<FurnitureSlot>()
            return when (kind) {
                FurnitureKind.BED_DOUBLE, FurnitureKind.BED_SINGLE -> {
                    val inset = m(0.2)                                     // a sitter's hips in from the mattress's edge
                    val wide = sf.widthY >= DOUBLE_WIDTH_YARDS
                    // the pelvis: the head past the pillows (1.4 m from the head end: nearer, the audit found heads in them)
                    val lieX = minOf(sx0 + m(1.4), smx)
                    val apart = minOf(m(0.4), (sy1 - sy0) / 2 - m(0.35))
                    val lies = ArrayList<FurnitureSlot>()
                    lies += s("lie_middle", lieX, smy, TURN, h, LIE)
                    if (wide && apart > m(0.15)) {
                        lies.add(0, s("lie_right", lieX, smy - apart, TURN, h, LIE))
                        lies.add(0, s("lie_left", lieX, smy + apart, TURN, h, LIE))
                    }
                    val kneel = setOf(Posture.KNEEL, Posture.ALL_FOURS, Posture.CROUCH)
                    val slots = ArrayList<FurnitureSlot>(lies)
                    slots += s("kneel_middle", minOf(lieX + m(0.2), smx), smy, TURN, h, kneel)
                    slots += s("kneel_foot", maxOf(sx1 - m(0.55), sx0 + m(0.3)), smy, 0f, h, kneel)
                    // the foot's edge is sat on only without a footboard or posts along it
                    val footBlocked = e.parts.any { p -> r(p.rect.maxX) > sx1 - m(0.1) && r(p.rect.minX) < hx + 0.01f &&
                        minOf(r(p.rect.maxY), sy1) - maxOf(r(p.rect.minY), sy0) > 0.3f * (sy1 - sy0) }
                    if (free("left")) slots += s("sit_left", smx, sy1 - inset, HALF_PI, h, setOf(Posture.SIT))
                    if (free("right")) slots += s("sit_right", smx, sy0 + inset, -HALF_PI, h, setOf(Posture.SIT))
                    if (free("front") && !footBlocked) slots += s("sit_foot", sx1 - inset, smy, 0f, h, setOf(Posture.SIT))
                    slots += s("lean_foot", sx1 - m(0.15), smy, TURN, h, setOf(Posture.LEAN))
                    slots += s("lean_left", smx, sy1 - m(0.15), -HALF_PI, h, setOf(Posture.LEAN))
                    slots += s("lean_right", smx, sy0 + m(0.15), HALF_PI, h, setOf(Posture.LEAN))
                    if (free("left")) beside += s("floor_left", smx, hy + m(0.4), -HALF_PI, 0f, BESIDE)
                    if (free("right")) beside += s("floor_right", smx, -(hy + m(0.4)), HALF_PI, 0f, BESIDE)
                    if (free("front")) beside += s("floor_foot", hx + m(0.4), smy, TURN, 0f, BESIDE)
                    FurnitureSpec(kind, hx, hy, r(e.height),
                        listOf(FurnitureRegion("bed", sx0, sx1, sy0, sy1, h, ON_BED)) + frames, slots + beside, k)
                }
                FurnitureKind.COUCH -> {
                    val seatX = maxOf(sx1 - m(0.27), sx0 + m(0.15))
                    val side = maxOf(0f, (sy1 - sy0) / 2 - m(0.35))
                    val slots = ArrayList<FurnitureSlot>()
                    slots += s("seat_middle", seatX, smy, 0f, h, setOf(Posture.SIT))
                    if (side > m(0.3)) { slots += s("seat_left", seatX, smy + side, 0f, h, setOf(Posture.SIT)); slots += s("seat_right", seatX, smy - side, 0f, h, setOf(Posture.SIT)) }
                    slots += s("lie", (sx0 + sx1) / 2, smy, HALF_PI, h, LIE)
                    slots += s("kneel_seat", (sx0 + sx1) / 2, smy, TURN, h, setOf(Posture.KNEEL, Posture.ALL_FOURS))
                    val back = e.parts.filter { r(it.rect.maxX) <= sx0 + m(0.1) }.maxByOrNull { it.rect.widthY }
                    val regions = ArrayList<FurnitureRegion>()
                    regions += FurnitureRegion("seat", sx0, sx1, sy0, sy1, h, LIE + setOf(Posture.SIT, Posture.KNEEL, Posture.ALL_FOURS, Posture.CROUCH))
                    if (back != null) {
                        regions += FurnitureRegion("back", r(back.rect.minX), r(back.rect.maxX), r(back.rect.minY), r(back.rect.maxY), h + m(0.4), setOf(Posture.LEAN))
                        slots += s("lean_back", r(back.rect.midX), smy, 0f, h + m(0.4), setOf(Posture.LEAN))
                    }
                    if (free("front")) {
                        beside += s("floor_front", hx + m(0.5), smy, TURN, 0f, BESIDE)
                        if (side > m(0.3)) { beside += s("floor_front_left", hx + m(0.5), smy + side, TURN, 0f, BESIDE); beside += s("floor_front_right", hx + m(0.5), smy - side, TURN, 0f, BESIDE) }
                    }
                    FurnitureSpec(kind, hx, hy, r(e.height), regions + frames.filter { f -> back == null || f.minX != r(back.rect.minX) || f.minY != r(back.rect.minY) }, slots + beside, k)
                }
                FurnitureKind.CHAIR -> {
                    // the pelvis a little in from the seat's front (the knees past its edge, the shins clear of it: the audit's
                    // sits put their feet in a deeper seat), never off its back
                    val seatX = maxOf(sx1 - m(0.12), sx0 + m(0.15))
                    val slots = ArrayList<FurnitureSlot>()
                    slots += s("seat", seatX, smy, 0f, h, setOf(Posture.SIT))
                    val back = e.parts.filter { r(it.rect.maxX) <= seatX }.maxByOrNull { it.rect.widthY * (it.top - e.surfaceTop) }
                    val regions = ArrayList<FurnitureRegion>()
                    regions += FurnitureRegion("seat", sx0, sx1, sy0, sy1, h, setOf(Posture.SIT))
                    if (back != null) {
                        regions += FurnitureRegion("back", r(back.rect.minX), r(back.rect.maxX), r(back.rect.minY), r(back.rect.maxY), h + m(0.45), setOf(Posture.LEAN))
                        slots += s("lean_back", r(back.rect.midX), smy, 0f, h + m(0.45), setOf(Posture.LEAN))
                    }
                    if (free("front")) beside += s("floor_front", hx + m(0.5), smy, TURN, 0f, BESIDE)
                    if (free("left")) beside += s("floor_left", seatX, hy + m(0.4), -HALF_PI, 0f, BESIDE)
                    if (free("right")) beside += s("floor_right", seatX, -(hy + m(0.4)), HALF_PI, 0f, BESIDE)
                    FurnitureSpec(kind, hx, hy, r(e.height), regions + frames.filter { f -> back == null || f.minX != r(back.rect.minX) || f.minY != r(back.rect.minY) }, slots + beside, k)
                }
                FurnitureKind.TABLE, FurnitureKind.COUNTER -> {
                    val table = kind == FurnitureKind.TABLE
                    val slots = listOf(
                        s("lean_front", sx1 - m(0.15), smy, TURN, h, setOf(Posture.LEAN)),
                        s("sit_front", sx1 - m(0.15), smy, 0f, h, setOf(Posture.SIT_ON_SURFACE, Posture.SIT)),
                        s("lie_top", smx, smy, HALF_PI, h, LIE),
                        s("kneel_top", smx, smy, TURN, h, setOf(Posture.KNEEL, Posture.ALL_FOURS)),
                    )
                    if (free("front")) beside += s("floor_front", hx + m(0.4), smy, TURN, 0f, BESIDE)
                    if (free("left")) beside += s("floor_left", smx, hy + m(0.35), -HALF_PI, 0f, BESIDE)
                    if (free("right")) beside += s("floor_right", smx, -(hy + m(0.35)), HALF_PI, 0f, BESIDE)
                    FurnitureSpec(kind, hx, hy, maxOf(h, r(e.height)),
                        listOf(FurnitureRegion("top", sx0, sx1, sy0, sy1, h, ON_TOP, solid = !table)) + frames, slots + beside, k)
                }
                FurnitureKind.FLOOR -> standard(kind, unitsPerMetre)
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

    /** Its main surface's box in the world (a bed's mattress, a seat, a top), floor to surface; its whole box without one. */
    fun surfaceBounds(): Pair<Vec3, Vec3> {
        val r = spec.regions.firstOrNull() ?: return bounds()
        val pts = listOf(r.minX to r.minY, r.minX to r.maxY, r.maxX to r.minY, r.maxX to r.maxY).map { (a, b) -> toWorld(a, b) }
        return Vec3(pts.minOf { it.x }, pts.minOf { it.y }, z) to Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, z + r.height)
    }

    companion object {
        /**
         * The live scene's own objects ([SceneObject]) as furniture, at their measured [spot] when there is one. The object's
         * front: a chair's or couch's sitter's facing; a table's, the side its reader stands at (facing + PI); a bed's foot,
         * opposite the rest pose's facing (tools/locations.py faces a lying body toward the bed's higher end, its headboard).
         * It stands on the spot's floor (its entry's height); its surface (a bed's mattress, a chair's or couch's seat) is how
         * far its pose is over that floor when that is measured (over 0.1), else the usual height.
         */
        fun of(item: SceneObject, spot: InteractionSpot?, unitsPerMetre: Float = FurnitureSpec.YARDS_PER_METRE): Furniture {
            val facing = spot?.facing ?: when (item) { SceneObject.TABLE -> (PI / 2).toFloat(); else -> -(PI / 2).toFloat() }
            val floor = spot?.entry?.z ?: item.position.z
            // the measured spot's height over its floor: the bed's mattress, the seat's top (the model's own, not the usual)
            val measured = spot?.let { it.pose.z - floor }?.takeIf { it > 0.1f }
            return when (item) {
                SceneObject.BED -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.BED_DOUBLE, unitsPerMetre,
                    measured), spot?.pose?.x ?: item.position.x, spot?.pose?.y ?: item.position.y,
                    floor, facing + PI.toFloat())
                SceneObject.CHAIR -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.CHAIR, unitsPerMetre, measured),
                    spot?.pose?.x ?: item.position.x, spot?.pose?.y ?: item.position.y, floor, facing)
                SceneObject.COUCH -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.COUCH, unitsPerMetre, measured),
                    spot?.pose?.x ?: item.position.x, spot?.pose?.y ?: item.position.y, floor, facing)
                SceneObject.TABLE -> Furniture(item.name, FurnitureSpec.standard(FurnitureKind.TABLE, unitsPerMetre),
                    item.position.x, item.position.y, floor, facing + PI.toFloat())
            }
        }

        /**
         * A location's furniture row (locations.json "furniture": [piece]) as furniture, brought into the scene's frame by
         * [toLocal] and turned by [rotation] (the location's frame to the scene's); null for a kind that is not furniture.
         */
        fun of(piece: FurniturePiece, toLocal: (Vec3) -> Vec3 = { it }, rotation: Float = 0f,
               unitsPerMetre: Float = FurnitureSpec.YARDS_PER_METRE): Furniture? {
            val kind = FurnitureKind.of(piece.kind) ?: return null
            val e = piece.extent?.takeIf { kind != FurnitureKind.FLOOR }
            if (e == null) {
                val at = toLocal(piece.position)
                return Furniture(piece.item, FurnitureSpec.standard(kind, unitsPerMetre, piece.surface.takeIf { it > 0f }),
                    at.x, at.y, at.z, piece.yaw + rotation)
            }
            // measured: a bed whose head the measure found at the row's front is turned round (its head toward -x, as
            // every bed's slots have it), and the piece stands at its footprint's middle
            val turned = kind.bed && e.headEnd > 0
            val n = if (turned) turnedRound(e) else e
            val cx = n.box.midX; val cy = n.box.midY
            val yaw = piece.yaw + if (turned) PI.toFloat() else 0f
            val c = cos(yaw); val s = sin(yaw)
            val p = piece.position
            val at = toLocal(Vec3(p.x + cx * c - cy * s, p.y + cx * s + cy * c, p.z))
            return Furniture(piece.item, FurnitureSpec.measured(kind, centred(n, -cx, -cy), unitsPerMetre), at.x, at.y, at.z, wrap(yaw + rotation))
        }

        /** [e] seen from the other end (its frame turned half round: x and y both reversed, front and back, left and right). */
        internal fun turnedRound(e: FurnitureExtent): FurnitureExtent {
            fun t(rc: PieceRect) = PieceRect(-rc.maxX, -rc.minX, -rc.maxY, -rc.minY)
            fun tp(p: PiecePart) = PiecePart(t(p.rect), p.top)
            val swap = mapOf("front" to "back", "back" to "front", "left" to "right", "right" to "left")
            return e.copy(box = t(e.box), surface = t(e.surface), headEnd = -e.headEnd, parts = e.parts.map(::tp), rises = e.rises.map(::tp),
                sides = e.sides.mapKeys { (key, _) -> swap[key] ?: key })
        }

        /** [e] moved [dx], [dy] in its own frame. */
        internal fun centred(e: FurnitureExtent, dx: Float, dy: Float): FurnitureExtent =
            e.copy(box = e.box.shifted(dx, dy), surface = e.surface.shifted(dx, dy),
                parts = e.parts.map { PiecePart(it.rect.shifted(dx, dy), it.top) }, rises = e.rises.map { PiecePart(it.rect.shifted(dx, dy), it.top) })

        private fun wrap(a: Float): Float {
            var x = a % (2 * PI).toFloat()
            if (x > PI) x -= (2 * PI).toFloat()
            if (x < -PI) x += (2 * PI).toFloat()
            return x
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
         * [OVERHANG_M]); a sitting body's feet must be off the solid it sits on (its shins hang); feet that stay on the floor
         * forbid raising the clip and must not land inside a solid piece.
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
                    val lift = slot.height - a.restUnits * bodyUnit
                    val landed = ArrayList<String>(r.actors.size)
                    val at = ArrayList<Vec3>(r.actors.size)
                    var score = 0f
                    var ok = true
                    for ((j, b) in r.actors.withIndex()) {
                        fun place(px: Float, py: Float) = (tx + (px * bodyUnit) * c - (py * bodyUnit) * s) to (ty + (px * bodyUnit) * s + (py * bodyUnit) * c)
                        val (px, py) = place(b.x, b.y)
                        val pz = b.restUnits * bodyUnit + lift
                        val land = land(f, b.posture, px, py, pz, tol, k)
                        if (land == null) { ok = false; break }
                        if (b.posture.lying) {
                            val (hx, hy) = place(b.headX, b.headY)
                            val (fx, fy) = place(b.feetX, b.feetY)
                            if (!over(f, land, hx, hy, k) || !over(f, land, fx, fy, k)) { ok = false; break }
                        }
                        // a seated body's shins hang below its seat: its feet must be off the solid it sits on (a sit on a
                        // bed takes its edge, not its middle, where the shins went into the mattress), and out of its
                        // solid parts (a footboard, a chair's arm)
                        if (b.posture == Posture.SIT && land.region?.solid == true) {
                            val (fx, fy) = place(b.feetX, b.feetY)
                            if (land.region.contains(fx, fy, -0.1f * k)) { ok = false; break }
                            if (f.spec.regions.any { it.postures.isEmpty() && it.height > land.height && it.contains(fx, fy, -0.03f * k) }) { ok = false; break }
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

        /**
         * Whether a lying body's end at [x], [y] is over the surface it lies on (the floor: not inside a solid piece) and not
         * in a solid part standing over it (a headboard, a footboard, a post).
         */
        private fun over(f: Furniture, land: Land, x: Float, y: Float, k: Float): Boolean {
            val r = land.region ?: return f.spec.regions.none { it.solid && it.height > 0.2f * k && it.contains(x, y, -0.15f * k) }
            if (f.spec.regions.any { it.postures.isEmpty() && it.height > r.height + 0.1f * k && it.contains(x, y, -0.03f * k) }) return false
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
