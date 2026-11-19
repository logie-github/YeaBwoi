package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneSimulationTest {
    @Test fun aCommandWalksToAndReservesItsObject() {
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.CHAIR), Random(1))
        assertFalse(sim.use("a", SceneObject.TABLE))
        assertTrue(sim.use("a", SceneObject.CHAIR))
        assertFalse(sim.use("b", SceneObject.CHAIR))
        assertEquals(Activity.WALK, sim.people().first().activity)
        repeat(40) { sim.tick(0.1f) }
        val actor = sim.people().first()
        assertEquals(SceneObject.CHAIR.approach, actor.position)
        assertEquals(Activity.SIT, actor.activity)
        assertTrue(actor.directed)
        sim.freeWill("a")
        assertTrue(sim.available(SceneObject.CHAIR, "b"))
    }

    @Test fun bedStartsOnTheFloorBesideItAndRestsOnTheMeasuredSurface() {
        val sim = SceneSimulation(listOf("a"), setOf(SceneObject.BED), Random(1), initiallyReady = false)
        assertFalse(sim.use("a", SceneObject.BED))
        val surface = Vec3(2.6f, 2.2f, 0.75f)
        val spot = SceneObject.BED.interaction(surface, 0f, 0.65f)
        sim.setSpot(SceneObject.BED, spot)
        assertEquals(0f, spot.entry.z)
        assertEquals(surface.y - 1f, spot.entry.y, 0.001f)
        assertTrue(sim.use("a", SceneObject.BED))
        sim.tick(0.1f)
        assertEquals(Activity.WALK, sim.people().single().activity)
        assertEquals(0f, sim.people().single().position.z)
        repeat(80) { sim.tick(0.1f) }
        val resting = sim.people().single()
        assertEquals(Activity.REST, resting.activity)
        assertEquals(SceneObject.BED, resting.furniture)
        assertEquals(surface, resting.position)
        assertEquals(spot.entry, resting.entry)
        sim.freeWill("a")
        assertEquals(spot.entry, sim.people().single().position)
        assertEquals(null, sim.people().single().furniture)
    }

    @Test fun seatsUseTheModelFacingAndTableReadingStaysOnTheFloor() {
        val surface = Vec3(2f, 3f, 0.5f)
        val seat = SceneObject.COUCH.interaction(surface, 1.5707964f, 0.55f)
        assertEquals(surface.x, seat.entry.x, 0.001f)
        assertEquals(surface.y + 0.9f, seat.entry.y, 0.001f)
        assertEquals(surface, seat.pose)
        val read = SceneObject.TABLE.interaction(surface, 0f, 1f)
        assertEquals(read.entry, read.pose)
        assertEquals(0f, read.pose.z)
    }

    @Test fun aChatPutsTheOtherNpcInTheConversationAndReleasesBoth() {
        val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(1))
        assertTrue(sim.chat("a", "b"))
        repeat(20) { sim.tick(0.1f) }
        assertEquals(listOf(Activity.CHAT, Activity.LISTEN), sim.people().map { it.activity })
        sim.freeWill("a")
        assertEquals(listOf(Activity.IDLE, Activity.IDLE), sim.people().map { it.activity })
    }

    @Test fun freeWillMovesAndUsesAnObjectWithoutACommand() {
        val sim = SceneSimulation(listOf("a"), setOf(SceneObject.TABLE), Random(1))
        val activities = buildSet {
            repeat(400) {
                sim.tick(0.1f)
                add(sim.people().single().activity)
            }
        }
        assertTrue(Activity.WALK in activities)
        assertTrue(Activity.READ in activities)
        assertFalse(sim.people().single().directed)
    }

    @Test fun aOneShotClipCanSetTheInteractionLength() {
        val sim = SceneSimulation(listOf("a"), setOf(SceneObject.CHAIR), Random(1),
            durations = mapOf(Activity.SIT to 0.25f))
        sim.use("a", SceneObject.CHAIR)
        while (sim.people().single().activity == Activity.WALK) sim.tick(0.1f)
        repeat(3) { sim.tick(0.1f) }
        assertEquals(Activity.IDLE, sim.people().single().activity)
    }
}
