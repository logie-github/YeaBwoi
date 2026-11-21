package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

/**
 * Two-person animations ordered from the animation browser (Play, Queue, Sequence: PerformanceOrder with its pairId) whose
 * bodies need furniture: played on the furniture as its Play window plays them (FurnitureFit), a sequence's stages on the same
 * piece from the same origin; floor ones as before. Synthetic bodies (anim/PoseFixtures); nothing here has run on a phone.
 */
class PlacedOrderTest {
    private val F = PoseFixtures
    private val step = 1f / 120f

    private fun pair(id: String, a: Anim, b: Anim, dx: Double = 0.0, dy: Double = 0.0) =
        PartnerPair(id, "Pair $id", a, b, PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human((dx * BodyGeometry.REF_UNIT).toFloat(), (dy * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true, emptySet())

    /** As the screen's request(): the choice's own roles (keys of its clips, offsets on the floor) and its pair's id. */
    private fun order(p: PartnerPair, partner: String = "b") = PerformanceOrder(partner,
        listOf(PlaybackRole("${p.id}:0", Vec3(0f, 0f, 0f), 0f), PlaybackRole("${p.id}:1", Vec3(p.slotB.x, p.slotB.y, 0f), 0f)),
        4f, name = p.name, pairId = p.id)

    private fun until(sim: SceneSimulation, limit: Int = 20_000, done: () -> Boolean) {
        var n = 0
        while (!done()) { sim.tick(step); n++; check(n < limit) { "never happened" } }
    }

    private fun onBed(sim: SceneSimulation, id: String): Boolean {
        val bed = sim.furniture(SceneObject.BED)!!
        val at = sim.position(id)!!
        val (lx, ly) = bed.toLocal(at.x, at.y)
        return abs(lx) <= bed.spec.halfX && abs(ly) <= bed.spec.halfY
    }

    private fun clipOf(sim: SceneSimulation, id: String): String? {
        val pose = Pose()
        sim.pose((0 until sim.count).first { sim.id(it) == id }, pose)
        return pose.playback?.clip
    }

    @Test fun aBedStageOrderedFromTheBrowserPlaysOnTheBed() {
        val p = pair("lie2", F.lyingBack(0.55), F.lyingBack(0.55), dy = 0.6)
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(1), pairs = listOf(p))
        assertEquals(Surface.BED, sim.requirementOf(p)!!.surface)
        assertTrue(sim.perform("a", order(p)))
        val v = sim.partnerOf("a")!!
        assertEquals(SceneObject.BED, v.furniture, "the order went to the bed")
        until(sim) { sim.partnerOf("a")?.stage == "PLAY" }
        for (id in listOf("a", "b")) {
            assertTrue(onBed(sim, id), "$id is on the bed")
            // the pair's own roles, fitted (the pair's keys, as the furniture's Play window plays it)
            assertEquals(true, clipOf(sim, id)?.startsWith("pair:lie2:"), "$id plays ${clipOf(sim, id)}")
        }
        assertEquals("pair:lie2:0", clipOf(sim, "a"), "the one who ordered it plays its first role")
        // the origin where the fit put it on the bed (the clip carries the bed's height itself)
        val fit = FurnitureFit.fit(sim.requirementOf(p)!!, sim.furniture(SceneObject.BED)!!, SceneSimulation.HUMAN_BODY_UNIT)!!
        assertEquals(fit.x, v.ox, 1e-4f); assertEquals(fit.y, v.oy, 1e-4f)
        val pose = Pose()
        sim.pose((0 until sim.count).first { sim.id(it) == "a" }, pose)
        assertEquals(SceneObject.BED, pose.furniture)
        assertEquals(sim.furniture(SceneObject.BED)!!.z, pose.floor, 1e-4f, "on the bed's own floor")
    }

    @Test fun aFloorStageOrderedFromTheBrowserStaysOnTheFloor() {
        val hug = pair("hug", F.standing, F.standing, dx = 0.6)
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(1), pairs = listOf(hug))
        assertTrue(sim.perform("a", order(hug)))
        until(sim) { sim.partnerOf("a")?.stage == "PLAY" }
        assertNull(sim.partnerOf("a")!!.furniture)
        assertEquals("hug:0", clipOf(sim, "a"), "its own roles, as before")
        assertEquals(0f, sim.position("a")!!.z, 1e-4f)
    }

    @Test fun aSequenceStaysOnOneBedFromOneOrigin() {
        val first = pair("lie2", F.lyingBack(0.55), F.lyingBack(0.55), dy = 0.6)
        val second = pair("lie2b", F.lyingBack(0.55), F.lyingBack(0.55), dy = 0.5)
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(2), pairs = listOf(first, second))
        assertTrue(sim.enqueue("a", listOf(order(first), order(second))))
        until(sim) { sim.partnerOf("a")?.let { it.stage == "PLAY" && it.name == first.name } == true }
        val one = sim.partnerOf("a")!!
        assertEquals(SceneObject.BED, one.furniture)
        // the next stage starts where the first ends: no walk (PLAY at once), the same bed, the same origin and heading
        until(sim) { sim.partnerOf("a")?.name == second.name }
        val two = sim.partnerOf("a")!!
        assertEquals("PLAY", two.stage, "not re-seated")
        assertEquals(SceneObject.BED, two.furniture)
        assertEquals(one.ox, two.ox, 1e-5f); assertEquals(one.oy, two.oy, 1e-5f); assertEquals(one.yaw, two.yaw, 1e-5f)
        for (id in listOf("a", "b")) assertTrue(onBed(sim, id), "$id still on the bed")
        assertEquals("pair:lie2b:0", clipOf(sim, "a"))
    }

    @Test fun aBedStageWithNoBedPlaysOnTheFloorWithANotice() {
        // (it was refused: "Nothing free here fits ...: it needs a bed")
        val p = pair("lie2", F.lyingBack(0.55), F.lyingBack(0.55), dy = 0.6)
        val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(1), pairs = listOf(p))
        assertTrue(sim.perform("a", order(p)))
        assertNull(sim.takeRefusal())
        assertEquals("No bed here: playing on the floor.", sim.takeQueueNotice())
        assertNull(sim.partnerOf("a")!!.furniture)
        until(sim) { sim.partnerOf("a")?.stage == "PLAY" }
        // lowered onto the floor: its lowest flesh on it (the bed's 0.55 m taken off), not hanging at the bed's height
        val r = sim.requirementOf(p)!!
        val drop = FurnitureAnimation.floorDrop(sim.spaced(p)) * SceneSimulation.HUMAN_BODY_UNIT
        assertTrue(drop > 0.4f * FurnitureSpec.YARDS_PER_METRE, "lowered by $drop yards")
        for ((i, id) in listOf("a", "b").withIndex()) {
            val pose = Pose().also { sim.pose(i, it) }
            assertEquals(-drop, pose.z, 0.02f, "$id at the floor")
            val support = pose.z + r.actors[i].restUnits * SceneSimulation.HUMAN_BODY_UNIT
            assertTrue(abs(support) < 0.15f * SceneSimulation.HUMAN_BODY_UNIT, "$id rests on the floor: $support")
        }
        // an order with no pair named (an older caller) plays as before
        sim.stopAll("a")
        val other = SceneSimulation(listOf("a", "b"), emptySet(), Random(1), pairs = listOf(p))
        assertTrue(other.perform("a", order(p).copy(pairId = null)))
        assertNull(other.partnerOf("a")!!.furniture)
    }

    @Test fun treatedAsFloorItPlaysOnTheFloorBesideTheBed() {
        val p = pair("lie2", F.lyingBack(0.55), F.lyingBack(0.55), dy = 0.6)
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(1), pairs = listOf(p))
        sim.treatAs = mapOf(p.id to TreatAs.FLOOR)
        assertTrue(sim.perform("a", order(p)))
        assertNull(sim.partnerOf("a")!!.furniture, "the player's class wins over the bed")
        // as the bodies were read again: the bed
        val again = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(1), pairs = listOf(p))
        again.treatAs = emptyMap()
        assertTrue(again.perform("a", order(p)))
        assertEquals(SceneObject.BED, again.partnerOf("a")!!.furniture)
    }
}
