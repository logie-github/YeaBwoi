package com.logie.wifecraft.simulation

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
