package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.Hints
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.*

/** Furniture's slots and which animations each kind allows, where they play, and who plays them (synthetic bodies). */
class FurnitureTest {
    private val F = PoseFixtures

    /** [a] moved [dx], [dy] body units (its Root track shifted). */
    private fun moved(a: Anim, dx: Double, dy: Double): Anim = a.copy(tracks = a.tracks.map { t ->
        if (t.bone == "Root" && t.channel == Channel.POSITION) {
            val v = t.values.copyOf()
            for (k in t.times.indices) { v[k * 3] += (dx * BodyGeometry.REF_UNIT).toFloat(); v[k * 3 + 1] += (dy * BodyGeometry.REF_UNIT).toFloat() }
            BoneTrack(t.bone, t.channel, t.times, v)
        } else t
    })

    private fun req(vararg actors: Anim, hints: Hints = Hints()): Requirement =
        RequirementClassifier.classify(actors.map { BodyGeometry.frames(it) }, hints)

    private fun at(kind: FurnitureKind, x: Float = 0f, y: Float = 0f, yaw: Float = 0f) = Furniture(kind.name, FurnitureSpec.standard(kind), x, y, 0f, yaw)

    private val kinds = FurnitureKind.entries

    private fun allowed(r: Requirement) = kinds.filter { FurnitureFit.compatible(r, at(it)) }.toSet()

    private val chairSit = req(F.sitting(0.47))
    private val bedLie = req(F.lyingBack(0.55))
    private val floorPair = req(F.lyingBack(0.0), F.lyingFront(0.0).let { moved(it, -0.5, 0.0) })   // head to feet, on the floor
    private val standingPair = req(F.standing, moved(F.standing, 0.6, 0.0))
    private val counterSit = req(F.sitting(0.95))
    private val lean = req(F.leaning())

    @Test fun eachKindAllowsOnlyWhatItsBodiesCanDo() {
        assertEquals(setOf(FurnitureKind.CHAIR, FurnitureKind.COUCH, FurnitureKind.BED_DOUBLE, FurnitureKind.BED_SINGLE), allowed(chairSit))
        assertEquals(setOf(FurnitureKind.BED_DOUBLE, FurnitureKind.BED_SINGLE), allowed(bedLie))
        // lying on the floor head to feet: the floor, or a bed (a single one too: they lie in one line) with the pair lifted onto it
        assertEquals(setOf(FurnitureKind.FLOOR, FurnitureKind.BED_DOUBLE, FurnitureKind.BED_SINGLE), allowed(floorPair))
        assertEquals(setOf(FurnitureKind.FLOOR), allowed(standingPair))
        // sitting up on a top, feet off the floor: a counter, or a table (lowered onto it; the feet still hang)
        assertEquals(setOf(FurnitureKind.COUNTER, FurnitureKind.TABLE), allowed(counterSit))
        assertEquals(setOf(FurnitureKind.TABLE), allowed(lean))
        assertEquals(emptySet(), allowed(req(Anim("h", "h", "", 1f, true, listOf(BoneTrack("L_Hand", Channel.ROTATION, floatArrayOf(0f),
            floatArrayOf(0f, 0f, 0f, 1f))), "s", 0L))))
        // the surface gate on its own
        assertTrue(FurnitureFit.allows(Surface.ANY_SEAT, FurnitureKind.BED_SINGLE))
        assertFalse(FurnitureFit.allows(Surface.BED, FurnitureKind.CHAIR))
        assertFalse(FurnitureFit.allows(Surface.NONE, FurnitureKind.BED_DOUBLE))
        assertFalse(FurnitureFit.allows(Surface.UNKNOWN, FurnitureKind.FLOOR))
    }

    @Test fun aSitterLandsOnTheSeatFacingTheChairsFront() {
        val chair = at(FurnitureKind.CHAIR, 3f, -2f, (PI / 2).toFloat())
        val fit = FurnitureFit.fit(chairSit, chair)!!
        assertEquals(listOf("seat"), fit.slots)
        val p = fit.positions[0]
        assertEquals(3f, p.x, 0.05f); assertEquals(-2f, p.y, 0.05f)
        assertEquals(chair.spec.surface, p.z, 0.2f)
        assertEquals((PI / 2).toFloat(), fit.yaw, 0.05f)
        assertEquals(0f, fit.lift, 0.2f)
    }

    @Test fun aBedTakesOneLyingAndOneKneelingBesideIt() {
        val kneelBeside = req(F.lyingBack(0.55), moved(F.kneeling(0.0, facingDeg = 90.0), 0.0, -0.95), hints = Hints(locations = setOf("DOUBLE_BED")))
        val fit = FurnitureFit.fit(kneelBeside, at(FurnitureKind.BED_DOUBLE))!!
        assertTrue(fit.slots[0].startsWith("lie_"), fit.slots.toString())
        assertTrue(fit.slots[1].startsWith("floor"), fit.slots.toString())
        assertNull(FurnitureFit.fit(kneelBeside, at(FurnitureKind.COUCH)))
        // two lying side by side: a double bed, not a single one
        val sideBySide = req(F.lyingBack(0.55), moved(F.lyingBack(0.55), 0.0, 0.6))
        val double = FurnitureFit.fit(sideBySide, at(FurnitureKind.BED_DOUBLE))
        assertNotNull(double)
        assertTrue(double.slots.all { it.startsWith("lie_") || it == "bed" }, double.slots.toString())
        assertNull(FurnitureFit.fit(sideBySide, at(FurnitureKind.BED_SINGLE)))
        // the floor pair lifted onto the bed by its height
        val lifted = FurnitureFit.fit(floorPair, at(FurnitureKind.BED_DOUBLE))!!
        assertEquals(FurnitureSpec.standard(FurnitureKind.BED_DOUBLE).surface, lifted.lift, 0.1f)
    }

    @Test fun aCouchSeatsTwoSideBySideAndAChairOne() {
        val two = req(F.sitting(0.45), moved(F.sitting(0.45), 0.0, 0.6))
        val fit = FurnitureFit.fit(two, at(FurnitureKind.COUCH))!!
        assertTrue(fit.slots.all { it.startsWith("seat") }, fit.slots.toString())
        assertNotEquals(fit.slots[0], fit.slots[1])
        assertNull(FurnitureFit.fit(two, at(FurnitureKind.CHAIR)))
        // one sits, the other kneels in front of the chair
        val kneelFront = req(F.sitting(0.47), moved(F.kneeling(0.0, facingDeg = 180.0), 0.75, 0.0))
        val chair = FurnitureFit.fit(kneelFront, at(FurnitureKind.CHAIR))!!
        assertEquals("seat", chair.slots[0])
        assertTrue(chair.slots[1].startsWith("floor"), chair.slots.toString())
    }

    @Test fun theScenesObjectsAndTheEmbassyTableAreFurniture() {
        assertEquals(FurnitureKind.BED_DOUBLE, Furniture.of(SceneObject.BED, null).kind)
        assertEquals(FurnitureKind.COUCH, Furniture.of(SceneObject.COUCH, null).kind)
        assertEquals(FurnitureKind.CHAIR, Furniture.of(SceneObject.CHAIR, null).kind)
        val table = Furniture.of(SceneObject.TABLE, null)
        assertEquals(FurnitureKind.TABLE, table.kind)
        // the table's front faces its reader's spot
        val (lx, ly) = table.toLocal(SceneObject.TABLE.approach.x, SceneObject.TABLE.approach.y)
        assertTrue(lx > 0f && abs(ly) < 0.2f, "$lx, $ly")
        // the bed's measured height is its surface
        val spot = InteractionSpot(Vec3(2.6f, 1.2f, 0f), Vec3(2.6f, 2.2f, 0.6f), 0f)
        assertEquals(0.6f, Furniture.of(SceneObject.BED, spot).spec.surface, 1e-4f)
        // locations.json: the Embassy's study table (the books are its props)
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val embassy = LocationInfo.parseAll(File(assets, "locations.json").readText()).first { it.interactions.isNotEmpty() }
        val row = embassy.interactions.first()
        val desk = Furniture.of("embassy-table", row.item, row.entry, row.facing, row.models.map { it.position })!!
        assertEquals(FurnitureKind.TABLE, desk.kind)
        val (ex, ey) = desk.toLocal(row.entry.x, row.entry.y)
        assertTrue(ex > 0f, "the entry is in front of the table: $ex, $ey")
        assertEquals(row.entry.z, desk.z)
        assertNull(Furniture.of("x", "BOOK", row.entry, row.facing, emptyList()))
    }

    @Test fun thePlayWindowListsOnlyWhatFitsGroupedByPosture() {
        val library = listOf(
            FurnitureAnimation("lie", "Lie down", bedLie), FurnitureAnimation("sit", "Sit", chairSit),
            FurnitureAnimation("pair69", "Floor pair", floorPair), FurnitureAnimation("hug", "Hug", standingPair),
            FurnitureAnimation("lean", "Lean", lean),
        )
        val bed = FurnitureSelection.listFor(at(FurnitureKind.BED_DOUBLE), library)
        assertEquals(listOf("Lying", "Sitting"), bed.keys.toList())
        assertEquals(listOf("Floor pair", "Lie down"), bed.getValue("Lying").map { it.animation.name })
        val floor = FurnitureSelection.listFor(at(FurnitureKind.FLOOR), library)
        assertEquals(setOf("Lying", "Standing"), floor.keys)
        assertEquals(listOf("Lean"), FurnitureSelection.listFor(at(FurnitureKind.TABLE), library).values.flatten().map { it.animation.name })
    }

    @Test fun pickForChoosesAFittingAnimationAndTheNearestCast() {
        val library = listOf(FurnitureAnimation("lie", "Lie", bedLie), FurnitureAnimation("hug", "Hug", standingPair),
            FurnitureAnimation("pair69", "Floor pair", floorPair))
        val bed = at(FurnitureKind.BED_DOUBLE, 5f, 5f)
        val npcs = listOf(NearbyNpc("far", 12f, 12f), NearbyNpc("near", 5.5f, 6.5f), NearbyNpc("mid", 6f, 3.5f))
        repeat(20) { seed ->
            val pick = FurnitureSelection.pickFor(bed, npcs, library, Random(seed))!!
            assertNotEquals("hug", pick.animation.id)
            assertEquals(pick.animation.participants, pick.cast.size)
            assertEquals(pick.cast.size, pick.cast.toSet().size)
            assertFalse("far" in pick.cast, pick.cast.toString())
        }
        // only one free person: only the one-person animation
        assertEquals("lie", FurnitureSelection.pickFor(bed, npcs, library, Random(3), busy = setOf("near", "mid"))!!.animation.id)
        assertNull(FurnitureSelection.pickFor(at(FurnitureKind.CHAIR), npcs, library, Random(1)))
        // the cast: each role to the nearer person
        val fit = FurnitureFit("f", 0f, 0f, 0f, 0f, listOf("a", "b"), listOf(Vec3(0f, 0f, 0f), Vec3(5f, 0f, 0f)), 0f, 0f)
        assertEquals(listOf("A", "B"), FurnitureSelection.cast(fit, listOf(NearbyNpc("B", 4.5f, 0f), NearbyNpc("A", 0.5f, 0f))))
        assertNull(FurnitureSelection.cast(fit, listOf(NearbyNpc("A", 0f, 0f))))
    }

    @Test fun aPartnerPairIsClassifiedAtItsSlots() {
        val pair = PartnerPair("p", "Bed_Lying", F.lyingBack(0.55), F.lyingBack(0.55), PartnerSlot(0f, 0f, 0f, 0f),
            PartnerSlot(0f, (0.6 * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true, setOf("DOUBLE_BED"))
        val a = FurnitureAnimation.of(pair)
        assertEquals(Surface.BED, a.requirement.surface)
        assertEquals(Posture.LIE_BACK, a.posture)
        assertEquals(0.6f, a.requirement.actors[1].y, 0.05f)
        assertNotNull(FurnitureFit.fit(a.requirement, at(FurnitureKind.BED_DOUBLE)))
        assertTrue(hypot(a.requirement.actors[1].x, 0f) < 0.1f)
    }
}
