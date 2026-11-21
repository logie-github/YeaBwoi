package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.location.FurnitureExtent
import com.logie.wifecraft.location.FurniturePiece
import com.logie.wifecraft.location.PiecePart
import com.logie.wifecraft.location.PieceRect
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The director's placement rules: a canopy bed's roof and its solid ends block the eye and the sight line, the near shoulder
 * is never most of the picture, the low hip detail is set by the surface the pair is on, a wide shot fits a small room, and
 * the hip and head points come from the playing clip's reading.
 */
class CinematicShotRulesTest {
    private val dt = 1f / 60f
    private val text: String by lazy {
        File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets", "shot_library.json").readText()
    }
    private val lib: ShotLibrary by lazy { ShotLibrary.parse(text) }

    private fun libId(s: CinematicShot) = if (s.preset.startsWith(CinematicDirector.LIBRARY))
        s.preset.removePrefix(CinematicDirector.LIBRARY).substringBefore(':') else null

    private fun room(x0: Float, x1: Float, y0: Float, y1: Float) =
        CameraClearance { eye, _ -> eye.x > x0 && eye.x < x1 && eye.y > y0 && eye.y < y1 }

    /** The Lion's Pride's canopy bed as measured (locations.json): posts and drapes at both ends, open long sides. */
    private fun canopyBed(): Furniture {
        val e = FurnitureExtent(PieceRect(-2.532f, 2.543f, -2.381f, 2.394f), 3.672f, PieceRect(-1.857f, 1.943f, -2.156f, 2.144f), 0.744f,
            0, listOf(PiecePart(PieceRect(-2.532f, -1.857f, -2.381f, 2.394f), 3.672f), PiecePart(PieceRect(1.943f, 2.393f, -2.381f, 2.394f), 3.672f)),
            emptyList(), mapOf("front" to 1.2f, "back" to 0.07f, "left" to 1.2f, "right" to 1.2f))
        return Furniture.of(FurniturePiece("BED", "BED_DOUBLE", Vec3(0f, 0f, 0f), 0f, 0.765f, e))!!
    }

    private fun focusOf(f: Furniture, st: CinematicStage): FurnitureFocus {
        var x = 0f; var y = 0f; var z = 0f
        for (i in 0 until st.count) { x += st.hipX[i] + st.headX[i]; y += st.hipY[i] + st.headY[i]; z += st.hipZ[i] + st.headZ[i] }
        val n = 2f * st.count
        return FurnitureFocus(f.id, Vec3(x / n, y / n, z / n), emptyList(), hypot(f.spec.halfX, f.spec.halfY), f.bounds(), f.z + f.spec.surface)
    }

    /** One lying along the bed (head toward -x), one kneeling over them, on [f]'s surface; or on the floor when [f] is null. */
    private fun pairOn(f: Furniture?, cls: ShotClass, walls: CameraClearance, ceiling: Float = Float.NaN) = CinematicStage(2).apply {
        val s = f?.let { it.z + it.spec.surface } ?: 0f
        count = 2
        person(0, 0.2f, 0f, s + 0.12f, -0.5f, 0f, s + 0.18f)
        person(1, 0.3f, 0f, s + 0.62f, 0.3f, 0f, s + 1.32f)
        facing[0] = PI.toFloat(); facing[1] = PI.toFloat()
        floorZ = 0f
        ceilingZ = ceiling
        clearance = walls
        lineYaw = 0f
        if (f != null) {
            obstacles = listOf(f.bounds())
            solids = PropSolids.world(f)
            furniture = focusOf(f, this)
        }
        camera(-3.5f, -3f, 2.2f, 0f, 0f, 1.0f, 55f)
        action = ShotAction(cls)
    }

    private fun standing(gap: Float, walls: CameraClearance, ceiling: Float = Float.NaN, cls: ShotClass = ShotClass.PARTNER) = CinematicStage(2).apply {
        count = 2
        person(0, -gap / 2, 0f, 1.1f, -gap / 2, 0f, 1.82f)
        person(1, gap / 2, 0f, 1.1f, gap / 2, 0f, 1.82f)
        facing[0] = 0f; facing[1] = PI.toFloat()
        floorZ = 0f
        ceilingZ = ceiling
        clearance = walls
        camera(-1.5f, -1.2f, 1.8f, 0f, 0f, 1.2f, 55f)
        action = ShotAction(cls)
    }

    private fun eyeOf(focus: Vec3, f: ShotFrame) = CinematicFraming.eye(focus + f.at, f.yaw, f.pitch, f.distance)

    /** Each frame's eye and frame, from when the shot is reached (its move over). */
    private fun reached(d: CinematicDirector, st: CinematicStage, seconds: Float, each: (ShotFrame, CinematicShot) -> Unit) {
        for (k in 0 until (seconds / dt).toInt()) {
            assertTrue(d.update(dt, st))
            val s = d.shot ?: continue
            if (d.spec == null || (!s.cut && d.shotTime < s.travel)) continue
            each(d.frameNow ?: continue, s)
        }
    }

    // ---- 1. a canopy bed

    @Test fun aCanopyBedsRoofAndEndsBlockTheSightLine() {
        val bed = canopyBed()
        assertTrue(PropSolids.canopy(bed))
        val solids = PropSolids.world(bed)
        // the base, the two ends and the canopy
        assertEquals(4, solids.size, "$solids")
        val canopy = solids.maxBy { it.first.z }
        assertTrue(canopy.first.z > 2.5f && canopy.second.z > 3.6f, "canopy $canopy")
        val middle = Vec3(0f, 0f, 1.0f)
        fun seen(eye: Vec3) = solids.none { (lo, hi) -> CinematicFraming.segmentHits(eye, middle, lo, hi, CinematicDirector.SIGHT_END) }
        assertFalse(seen(Vec3(0.5f, 0.8f, 4.3f)), "from over the canopy, through its roof")
        assertFalse(seen(Vec3(4.5f, 0f, 1.6f)), "from past the foot, through the drapes at the end")
        assertTrue(seen(Vec3(0.2f, 4.0f, 1.6f)), "from the open side, under the canopy")
        // an ordinary bed (a headboard, no canopy): no canopy box
        val plain = Furniture.of(FurniturePiece("BED", "BED_DOUBLE", Vec3(0f, 0f, 0f), 0f, 0.6f, null))!!
        assertFalse(PropSolids.canopy(plain))
    }

    @Test fun noShotOnACanopyBedSitsUnderItOrLooksThroughIt() {
        val bed = canopyBed()
        val (lo, hi) = bed.bounds()
        var shots = 0
        for (seed in 1L..4L) {
            for (cls in listOf(ShotClass.FACING_LYING, ShotClass.RIDING)) {
                val st = pairOn(bed, cls, room(-7f, 7f, -7f, 7f), ceiling = 5.5f)
                val d = CinematicDirector(CinematicSettings(randomness = 60), seed).also { it.library = lib }
                val focus = d.rawFocus(st)
                reached(d, st, 90f) { f, _ ->
                    val eye = eyeOf(focus, f)
                    val under = eye.x > lo.x && eye.x < hi.x && eye.y > lo.y && eye.y < hi.y && eye.z < hi.z
                    assertFalse(under, "seed $seed $cls: eye $eye under the canopy")
                    val at = focus + f.at
                    for ((a, b) in st.solids) {
                        if (at.x > a.x && at.x < b.x && at.y > a.y && at.y < b.y && at.z > a.z && at.z < b.z) continue
                        assertFalse(CinematicFraming.segmentHits(eye, at, a, b, CinematicDirector.SIGHT_END),
                            "seed $seed $cls ${d.spec?.id}: $eye to $at through $a..$b")
                    }
                }
                shots += d.history.count { libId(it) != null }
            }
        }
        assertTrue(shots >= 8, "the library still films the canopy bed ($shots shots)")
    }

    // ---- 2. over the shoulder

    @Test fun theNearShoulderIsNeverMostOfThePicture() {
        // an eye just behind a head, looking past it: that body fills the picture
        val hip = Vec3(0f, 0f, 1.1f); val head = Vec3(0f, 0f, 1.82f)
        val big = CinematicFraming.coverage(Vec3(-0.35f, 0.05f, 1.9f), Vec3(0.8f, 0f, 1.7f), 45f, 16f / 9f, hip, head)
        assertTrue(big > 0.3f, "close behind: $big")
        val small = CinematicFraming.coverage(Vec3(-0.9f, 0.6f, 2.0f), Vec3(0.8f, 0f, 1.7f), 45f, 16f / 9f, hip, head)
        assertTrue(small < CinematicDirector.MAX_NEAR_COVER, "off to the side: $small")

        // the old shoulder shot (nearly in line, low, near): turned down where the shoulder would fill the picture
        val old = ShotLibrary.parse(text.replace("\"azimuth\": [124, 144],\"pitch\": [12, 22], \"distance\": [0.95, 1.15]",
            "\"azimuth\": [150, 170],\"pitch\": [4, 8], \"distance\": [0.6, 0.7]"))
        assertEquals(150f, old.shots.getValue("over_shoulder_a").azimuth.lo)
        var turnedDown = 0
        var placed = 0
        for (l in listOf(old, lib)) for (seed in 1L..4L) {
            val st = standing(0.8f, room(-4f, 4f, -3.5f, 3.5f))
            val d = CinematicDirector(CinematicSettings(shotStyle = ShotStyle.CLOSE_UPS), seed).also { it.library = l }
            for (k in 0 until (200f / dt).toInt()) d.update(dt, st)
            if (l === old) turnedDown += d.rejections["OVER_SHOULDER: near person covers the picture"] ?: 0
            val focus = d.rawFocus(st)
            for (s in d.history) {
                val id = libId(s)
                val nearIdx = when {
                    id == "over_shoulder_a" -> 0
                    id == null && s.type == ShotType.OVER_SHOULDER -> s.preset.substringAfter(':').takeWhile { it.isDigit() }.toInt()
                    else -> continue
                }
                if (l === lib && id != null) placed++
                for (f in listOf(s.from, s.to)) {
                    val c = CinematicFraming.coverage(eyeOf(focus, f), focus + f.at, f.fov, st.aspect, st.hip(nearIdx), st.head(nearIdx))
                    assertTrue(c <= CinematicDirector.MAX_NEAR_COVER + 1e-4f, "seed $seed ${s.preset}: the shoulder covers $c")
                }
            }
        }
        assertTrue(turnedDown > 0, "the old geometry was never turned down")
        assertTrue(placed > 0, "the new shoulder shot is never placed")
    }

    // ---- 3. the low hip detail

    @Test fun theLowHipDetailSitsOnTheSurfaceAndNeverOnTheFloor() {
        val walls = room(-4f, 4f, -3.5f, 3.5f)
        for (seed in 1L..4L) {
            val d = CinematicDirector(CinematicSettings(shotStyle = ShotStyle.CLOSE_UPS), seed).also { it.library = lib }
            val st = pairOn(null, ShotClass.RIDING, walls)
            for (k in 0 until (150f / dt).toInt()) d.update(dt, st)
            assertTrue(d.history.none { libId(it) == "pelvis_low" }, "seed $seed: the low detail on the floor")
        }
        val bed = Furniture(SceneObject.BED.name, FurnitureSpec.standard(FurnitureKind.BED_DOUBLE), 0f, 0f, 0f, 0f)
        val surface = bed.spec.surface
        var seen = 0
        for (seed in 1L..6L) {
            val d = CinematicDirector(CinematicSettings(shotStyle = ShotStyle.CLOSE_UPS), seed).also { it.library = lib }
            val st = pairOn(bed, ShotClass.RIDING, walls)
            for (k in 0 until (150f / dt).toInt()) d.update(dt, st)
            val focus = d.rawFocus(st)
            for (s in d.history.filter { libId(it) == "pelvis_low" }) {
                seen++
                val z = eyeOf(focus, s.from).z
                assertTrue(z >= surface + 0.04f && z <= surface + 0.31f, "seed $seed: eye $z over a surface at $surface")
            }
        }
        assertTrue(seen > 0, "the low detail is never placed on the bed")
    }

    // ---- 4. a small room

    @Test fun theHighWideFitsASmallRoomOrTakesItsCorner() {
        for ((name, walls) in listOf("small" to room(-2f, 2f, -2f, 2f), "corridor" to room(-3.5f, 3.5f, -1.1f, 1.1f))) {
            var placed = 0
            val corners = HashSet<String>()
            for (seed in 1L..5L) {
                val st = standing(0.8f, walls, ceiling = 2.8f)
                val d = CinematicDirector(CinematicSettings(shotStyle = ShotStyle.WIDE), seed).also { it.library = lib }
                for (k in 0 until (200f / dt).toInt()) d.update(dt, st)
                val focus = d.rawFocus(st)
                for (s in d.history.filter { libId(it) == "high_wide" }) {
                    placed++
                    if (s.preset.endsWith(":corner")) corners += s.preset
                    for (f in listOf(s.from, s.to)) {
                        val e = eyeOf(focus, f)
                        assertTrue(walls.clear(e, focus), "$name seed $seed: $e out of the room")
                        assertTrue(e.z <= 2.8f - CinematicDirector.CEILING_GAP + 1e-3f, "$name: $e over the ceiling")
                        assertTrue(f.pitch > 0.25f, "$name: a high shot ($f)")
                    }
                }
            }
            assertTrue(placed > 0, "$name: the high wide is never placed")
            if (name == "corridor") println("corridor: corner shots $corners")
        }
    }

    // ---- 5. the hips and head

    private fun need(posture: Posture, x: Float, y: Float, headX: Float, headY: Float, units: Float = 0f) =
        ActorRequirement(posture, Surface.BED, units, units, x, y, 0f, headX, headY, x, y, false, 1f, "test")

    @Test fun theHipsAndHeadComeFromTheClipsReading() {
        val out = FloatArray(6)
        val unit = SceneSimulation.HUMAN_BODY_UNIT
        // lying with the head toward the frame's -x, the frame turned a quarter: the head lies toward -y of the pelvis
        assertTrue(CinematicAnchors.performer(need(Posture.LIE_BACK, 0.3f, 0f, -0.35f, 0f, 0.5f), 1f, 2f, (PI / 2).toFloat(), unit, 1f,
            0.1f, 0f, out))
        assertEquals(1f, out[0], 1e-3f); assertEquals(2f + 0.3f * unit, out[1], 1e-3f)
        assertEquals(1f, out[3], 1e-3f); assertEquals(2f - 0.35f * unit, out[4], 1e-3f)
        assertEquals(0.1f + 0.5f * unit + 0.12f, out[2], 1e-3f, "the pelvis just over the bed under it")
        assertTrue(abs(out[5] - out[2]) < 0.1f, "a lying head about level with the hips")
        // standing: the head over the pelvis, at a standing height
        assertTrue(CinematicAnchors.performer(need(Posture.STAND, 0f, 0f, 0.02f, 0f), 0f, 0f, 0f, unit, 1f, 0f, 0f, out))
        assertTrue(out[2] in 1.0f..1.25f && out[5] - out[2] > 0.6f && hypot(out[3] - out[0], out[4] - out[1]) < 0.05f)
        // on all fours: the head forward of the hips, about as high
        assertTrue(CinematicAnchors.performer(need(Posture.ALL_FOURS, 0f, 0f, 0.6f, 0f), 0f, 0f, 0f, unit, 1f, 0f, 0f, out))
        assertTrue(out[3] > 0.6f && abs(out[5] - out[2]) < 0.15f)
        // a reading with the head two bodies from the pelvis is not believed
        assertFalse(CinematicAnchors.performer(need(Posture.LIE_BACK, 0f, 0f, 2.5f, 0f), 0f, 0f, 0f, unit, 1f, 0f, 0f, out))
        // without a reading: a body resting laid out toward its facing (a bed's lying places face the headboard)
        CinematicAnchors.estimate(Posture.LIE_BACK, 0f, 0f, 0.6f, (PI / 2).toFloat(), 1f, out)
        assertTrue(out[4] > 0.6f && abs(out[3]) < 1e-3f && abs(out[5] - out[2]) < 0.1f, "${out.toList()}")
        assertEquals(PI / 2, atan2((out[4] - out[1]).toDouble(), (out[3] - out[0]).toDouble()), 1e-3)
    }
}
