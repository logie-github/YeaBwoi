package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.Hints
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.location.FurnitureExtent
import com.logie.wifecraft.location.FurniturePiece
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.location.PiecePart
import com.logie.wifecraft.location.PieceRect
import com.logie.wifecraft.story.Vec3
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/**
 * Furniture built from its measured extent (locations.json "extent", engine-test/furniture_measure): the places on a bed, a
 * chair and a table from their real size (synthetic boxes), the rows read with and without it, and the real rooms' pieces.
 */
class FurnitureExtentTest {
    private val F = PoseFixtures
    private val yard = FurnitureSpec.YARDS_PER_METRE

    private fun req(vararg actors: Anim): Requirement = RequirementClassifier.classify(actors.map { BodyGeometry.frames(it) }, Hints())

    /** A bed in its row's frame (head toward -x), yards: a mattress [length] by [width] at [top], a headboard behind it. */
    private fun bed(
        width: Float, length: Float = 2.6f, top: Float = 0.75f, headboard: Boolean = true, footboard: Boolean = false,
        sides: Map<String, Float> = emptyMap(), headEnd: Int = -1,
    ): FurnitureExtent {
        val hl = length / 2; val hw = width / 2
        val parts = ArrayList<PiecePart>()
        // the headboard at the head end (-x, or +x when the row has the bed the wrong way round), the footboard at the other
        val head = if (headEnd > 0) 1f else -1f
        if (headboard) parts += PiecePart(if (head < 0) PieceRect(-hl - 0.3f, -hl, -hw - 0.1f, hw + 0.1f) else PieceRect(hl, hl + 0.3f, -hw - 0.1f, hw + 0.1f), top + 0.8f)
        if (footboard) parts += PiecePart(if (head < 0) PieceRect(hl, hl + 0.2f, -hw - 0.1f, hw + 0.1f) else PieceRect(-hl - 0.2f, -hl, -hw - 0.1f, hw + 0.1f), top + 0.3f)
        return FurnitureExtent(PieceRect(-hl - 0.3f, hl + 0.3f, -hw - 0.1f, hw + 0.1f), top + 0.8f, PieceRect(-hl, hl, -hw, hw), top,
            headEnd, parts, emptyList(), mapOf("front" to 1.2f, "back" to 0.1f, "left" to 1.2f, "right" to 1.2f) + sides)
    }

    private fun piece(kind: String, e: FurnitureExtent?, yaw: Float = 0f, surface: Float = 0f) =
        FurniturePiece(if (kind.startsWith("BED")) "BED" else kind, kind, Vec3(0f, 0f, 0f), yaw, surface, e)

    private fun slotNames(f: Furniture) = f.spec.slots.map { it.name }.toSet()

    @Test fun aWideBedTakesTwoSideBySideANarrowOneOne() {
        val wide = Furniture.of(piece("BED_DOUBLE", bed(2.0f)))!!
        val narrow = Furniture.of(piece("BED_DOUBLE", bed(1.0f)))!!
        assertTrue(setOf("lie_left", "lie_right", "lie_middle").all { it in slotNames(wide) }, "${slotNames(wide)}")
        assertTrue("lie_middle" in slotNames(narrow))
        assertFalse("lie_left" in slotNames(narrow) || "lie_right" in slotNames(narrow), "${slotNames(narrow)}")
        // two lying side by side, 0.6 body units apart: on the wide bed both on the mattress; the narrow one can't hold them
        val sideBySide = req(F.lyingBack(0.0), F.lyingBack(0.0, dy = 0.6))
        val fit = assertNotNull(FurnitureFit.fit(sideBySide, wide))
        for (p in fit.positions) {
            val (lx, ly) = wide.toLocal(p.x, p.y)
            assertTrue(abs(ly) <= 1.0f && abs(lx) <= 1.3f, "on the mattress: $lx, $ly")
            assertEquals(0.75f, p.z - wide.z, 0.2f)
        }
        assertNull(FurnitureFit.fit(sideBySide, narrow))
        // one lying fits both
        assertNotNull(FurnitureFit.fit(req(F.lyingBack(0.55)), narrow))
    }

    @Test fun anEdgeSitTakesTheRealEdgeOfAWideBed() {
        // the Lion's Pride's canopy bed: a mattress 4.3 yards across; the usual double bed's edge is 0.68 yards out
        val e = bed(4.3f, length = 3.8f, top = 0.744f)
        val real = Furniture.of(piece("BED_DOUBLE", e))!!
        val usual = Furniture("usual", FurnitureSpec.standard(FurnitureKind.BED_DOUBLE, surface = 0.744f), 0f, 0f, 0f, 0f)
        val sit = req(F.sitting(0.744 / yard))                     // a sit made for a seat of the bed's height
        fun edgeDistance(f: Furniture): Float {
            val fit = assertNotNull(FurnitureFit.fit(sit, f))
            val (lx, ly) = f.toLocal(fit.positions[0].x, fit.positions[0].y)
            return minOf(2.15f - abs(ly), 1.9f - lx)
        }
        val inset = 0.2f * yard
        assertEquals(inset, edgeDistance(real), 0.05f)
        // the old fault: the usual bed's sit lands far inside the real mattress (its shins went into it)
        assertTrue(edgeDistance(usual) > 1.0f)
        // the sit's height is the real mattress's
        val fit = FurnitureFit.fit(sit, real)!!
        assertEquals(0.744f, fit.positions[0].z, 0.1f)
    }

    @Test fun aBlockedSideOrAFootboardHasNoPlacesOnIt() {
        val f = Furniture.of(piece("BED_DOUBLE", bed(2.0f, footboard = true, sides = mapOf("left" to 0.1f))))!!
        val names = slotNames(f)
        assertFalse("sit_left" in names || "floor_left" in names, "$names")
        assertFalse("sit_foot" in names, "$names")
        assertTrue("sit_right" in names && "floor_right" in names && "floor_foot" in names, "$names")
        // the headboard and the footboard are solid parts nobody rests on
        assertEquals(2, f.spec.regions.count { it.postures.isEmpty() && it.solid })
        // without a footboard the foot is sat on
        assertTrue("sit_foot" in slotNames(Furniture.of(piece("BED_DOUBLE", bed(2.0f)))!!))
    }

    @Test fun aBedMeasuredTheOtherWayRoundIsTurnedSoTheHeadIsAtItsHeadboard() {
        val f = Furniture.of(piece("BED_DOUBLE", bed(2.0f, headEnd = 1)))!!
        assertEquals(1f, cos(f.yaw - PI.toFloat()), 1e-4f)          // the row's yaw (0) turned half round
        val lie = f.spec.slots.first { it.name == "lie_middle" }
        val pelvis = f.toWorld(lie.x, lie.y)
        val toward = f.toWorld(lie.x + cos(lie.yaw), lie.y + sin(lie.yaw))
        assertTrue(toward.x > pelvis.x, "the head toward the row's +x, where the headboard is: $pelvis -> $toward")
        // the headboard is behind the lying place's head end in the turned frame
        val headboard = f.spec.regions.first { it.postures.isEmpty() }
        assertTrue(headboard.maxX <= f.spec.regions.first().minX + 1e-3f)
        // and its pelvis past the pillows from the head end, not at the middle of a long bed
        assertEquals(minOf(f.spec.regions.first().minX + 1.4f * yard, 0f), lie.x, 0.01f)
    }

    @Test fun aLyingBodysHeadKeepsOutOfTheHeadboard() {
        // a mattress shorter than the body: lying on it the head would reach over its end, into the headboard; without
        // one it may hang over the end (as a bed's usual overhang allows)
        val short = Furniture.of(piece("BED_SINGLE", bed(1.0f, length = 1.6f)))!!
        val open = Furniture.of(piece("BED_SINGLE", bed(1.0f, length = 1.6f, headboard = false)))!!
        val lie = req(F.lyingBack(0.55))
        assertNotNull(FurnitureFit.fit(lie, open))
        val fit = FurnitureFit.fit(lie, short)
        if (fit != null) {
            // where it did fit, its head end is clear of the headboard: the pelvis well in from the head end
            val (lx, _) = short.toLocal(fit.positions[0].x, fit.positions[0].y)
            val headboard = short.spec.regions.first { it.postures.isEmpty() }
            assertTrue(lx - headboard.maxX > 0.6f, "pelvis $lx, headboard to ${headboard.maxX}")
        }
    }

    @Test fun aChairsSitterTakesItsRealSeat() {
        // the Lion's Pride chair as measured: its origin at its seat's front, its back 0.96 yards behind
        val e = FurnitureExtent(PieceRect(-0.964f, 0.136f, -0.524f, 0.526f), 1.325f, PieceRect(-0.764f, 0.086f, -0.474f, 0.476f), 0.48f,
            0, listOf(PiecePart(PieceRect(-0.964f, -0.914f, -0.474f, 0.451f), 1.317f)), emptyList(),
            mapOf("front" to 0.18f, "back" to 1.2f, "left" to 1.2f, "right" to 1.2f))
        val chair = Furniture.of(piece("CHAIR", e, surface = 0.479f))!!
        val fit = assertNotNull(FurnitureFit.fit(req(F.sitting(0.47)), chair))
        assertEquals(listOf("seat"), fit.slots)
        val p = fit.positions[0]
        assertEquals(0.086f - 0.12f * yard, p.x, 0.05f)          // a little in from the seat's real front
        assertEquals(0f, p.y, 0.05f)
        assertEquals(0.48f, p.z, 0.2f)
        // the desk in front: nobody is put on the floor in front of it; the back is leaned on, not sat in
        assertFalse("floor_front" in slotNames(chair))
        assertTrue(chair.spec.regions.any { it.name == "back" })
    }

    @Test fun aTablesTopIsItsRealTop() {
        val e = FurnitureExtent(PieceRect(-1.04f, 1.09f, -1.09f, 1.06f), 1.125f, PieceRect(-1.01f, 1.06f, -1.04f, 1.04f), 1.1f)
        val table = Furniture.of(piece("TABLE", e))!!
        assertEquals(1.1f, table.spec.surface, 1e-4f)
        assertEquals(1.065f, table.spec.halfX, 0.01f)
        val lean = table.spec.slots.first { it.name == "lean_front" }
        assertEquals(table.spec.regions.first().maxX - 0.15f * yard, lean.x, 1e-3f)
    }

    @Test fun theCamerasFrameThePeopleNotTheWholeOfABigBed() {
        val big = Furniture.of(piece("BED_DOUBLE", bed(4.3f, length = 3.8f)))!!
        val alone = FurnitureCameras.presets(big, emptyList()).first { it.view == FurnitureView.FOOT }
        val people = listOf(Vec3(-0.6f, 0.2f, 1.0f), Vec3(-0.6f, -0.4f, 1.0f))
        val withPeople = FurnitureCameras.presets(big, people).first { it.view == FurnitureView.FOOT }
        assertTrue(withPeople.distance < alone.distance, "${withPeople.distance} vs ${alone.distance}")
    }

    @Test fun rowsParseWithAndWithoutTheirExtent() {
        val json = """{"id": "x", "furniture": [
            {"object": "BED", "kind": "BED_DOUBLE", "position": [1, 2, 3], "yaw": 0.5, "surface": 0.7},
            {"object": "CHAIR", "kind": "CHAIR", "position": [0, 0, 3], "yaw": 0, "surface": 0.48, "model": 198115,
             "extent": {"box": [-0.96, 0.14, -0.52, 0.53], "height": 1.33, "surfaceRect": [-0.76, 0.09, -0.47, 0.48], "surfaceTop": 0.48,
                        "headEnd": 0, "parts": [[-0.96, -0.91, -0.47, 0.45, 1.32]], "rises": [], "sides": {"front": 0.18, "left": 1.2}}},
            {"object": "TABLE", "kind": "TABLE", "position": [0, 0, 3], "yaw": 0, "surface": 1.1, "extent": {"box": [0, 1]}}
        ]}"""
        val info = LocationInfo.parse(JSONObject(json))
        assertEquals(3, info.furniture.size)
        assertNull(info.furniture[0].extent)
        assertEquals(0.7f, info.furniture[0].surface, 1e-4f)
        val e = assertNotNull(info.furniture[1].extent)
        assertEquals(0.48f, e.surfaceTop, 1e-4f)
        assertEquals(1, e.parts.size)
        assertEquals(1.32f, e.parts[0].top, 1e-4f)
        assertEquals(0.18f, e.free("front"), 1e-4f)
        assertEquals(Float.MAX_VALUE, e.free("back"))
        assertNull(info.furniture[2].extent)                         // a broken extent: the kind's usual shape
        // without an extent the usual shape, as before
        val usual = Furniture.of(info.furniture[0])!!
        assertEquals(FurnitureSpec.standard(FurnitureKind.BED_DOUBLE, surface = 0.7f).halfY, usual.spec.halfY, 1e-4f)
        assertEquals(1f, usual.x, 1e-4f); assertEquals(0.5f, usual.yaw, 1e-4f)
    }

    /** The real rooms' rows (assets/locations.json), when the extents are there: each piece's size is a real one. */
    @Test fun theRealRoomsPiecesArePlausible() {
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val file = File(assets, "locations.json")
        assumeTrue(file.exists(), "no locations.json")
        val all = LocationInfo.parseAll(file.readText())
        val goldshire = all.first { it.id == "goldshire_inn" }
        assumeTrue(goldshire.furniture.all { it.extent != null }, "the Lion's Pride's furniture isn't measured")
        val bed = goldshire.furniture.first { it.item == "BED" }.extent!!
        val chair = goldshire.furniture.first { it.item == "CHAIR" }.extent!!
        val table = goldshire.furniture.first { it.item == "TABLE" }.extent!!
        // the canopy bed: a mattress over a yard across (the game's inn beds are wider than a real one: this one 4.3 yards)
        // and long enough for a body (about 2 yards at human scale), its top at a bed's height
        assertTrue(bed.surface.widthY in 1.0f..5.0f, "bed width ${bed.surface.widthY}")
        assertTrue(bed.surface.lengthX >= 2.0f, "bed length ${bed.surface.lengthX}")
        assertTrue(bed.surfaceTop in 0.35f..0.8f, "mattress top ${bed.surfaceTop}")
        assertTrue(bed.parts.isNotEmpty(), "its posts and ends")
        assertTrue(chair.surfaceTop in 0.4f..0.6f, "seat ${chair.surfaceTop}")
        assertTrue(chair.surface.widthY in 0.4f..1.2f && chair.surface.lengthX in 0.4f..1.2f, "seat ${chair.surface}")
        assertTrue(table.surfaceTop in 0.7f..1.2f, "table top ${table.surfaceTop}")
        // every room's every piece is measured, and its bed takes a sitter and one lying
        for (loc in all) for (p in loc.furniture) {
            val e = assertNotNull(p.extent, "${loc.id} ${p.item}")
            assertTrue(e.surface.minX >= e.box.minX - 0.05f && e.surface.maxX <= e.box.maxX + 0.05f, "${loc.id} ${p.item}: surface in its box")
            if (p.item == "BED") {
                val f = Furniture.of(p)!!
                assertNotNull(FurnitureFit.fit(req(F.lyingBack(0.55)), f), "${loc.id}: lying")
                // a sit made for a seat of the bed's height, where a bed is no higher than a seat sat on with the feet down
                // (RequirementClassifier.SEAT_MAX: Brill's, Razor Hill's and Fairbreeze's beds stand higher: a sit there
                // is a sit on a high top)
                if (e.surfaceTop / yard < RequirementClassifier.SEAT_MAX - 0.01f)
                    assertNotNull(FurnitureFit.fit(req(F.sitting((e.surfaceTop / yard).toDouble())), f), "${loc.id}: sitting")
            }
        }
    }
}
