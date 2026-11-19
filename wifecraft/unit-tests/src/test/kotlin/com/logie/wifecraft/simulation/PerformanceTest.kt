package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.*

class PerformanceTest {
    private val zero = Vec3(0f, 0f, 0f)

    @Test
    fun waitsForThePartnerAndStartsBothRolesTogether() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        val roles = listOf(PlaybackRole("lead-clip", zero, 0f), PlaybackRole("guest-clip", zero, 1f))
        assertFalse(sim.perform("lead", "lead", roles, 2f))
        assertFalse(sim.perform("lead", null, roles, 2f))
        assertTrue(sim.perform("lead", "guest", roles, 2f))
        val a = Pose()
        val b = Pose()
        sim.pose(0, a)
        sim.pose(1, b)
        assertNull(a.playback)
        assertEquals(Activity.WALK, b.activity)
        repeat(12) { sim.tick(0.1f) }
        sim.pose(0, a)
        sim.pose(1, b)
        assertEquals("lead-clip", a.playback?.clip)
        assertEquals("guest-clip", b.playback?.clip)
        assertEquals(a.playback?.command, b.playback?.command)
        assertEquals(a.x, b.x)
        sim.freeWill("guest")
        sim.pose(0, a)
        sim.pose(1, b)
        assertNull(a.playback)
        assertNull(b.playback)
    }

    @Test
    fun repeatsAuthoredCyclesTogetherBeforeAdvancingTheQueue() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        val roles = listOf(PlaybackRole("lead-clip", Vec3(.2f, 0f, 0f), 0f), PlaybackRole("guest-clip", zero, 1f))
        assertTrue(sim.perform("lead", PerformanceOrder("guest", roles, .25f, repeat = true, repetitions = 2)))
        val a = Pose()
        val b = Pose()
        repeat(12) { sim.tick(.1f) }
        sim.pose(0, a)
        val initialCommand = assertNotNull(a.playback).command
        val initialX = a.x
        val nextRoles = roles.map { it.copy(clip = "${it.clip}-next") }
        val lastRoles = listOf(PlaybackRole("solo", zero, 0f))
        assertTrue(sim.enqueue("lead", listOf(
            PerformanceOrder("guest", nextRoles, .25f),
            PerformanceOrder(null, lastRoles, .25f),
        )))
        val transitions = mutableListOf<String>()
        var command = initialCommand
        repeat(12) {
            sim.tick(.1f)
            sim.pose(0, a)
            sim.pose(1, b)
            val clip = a.playback ?: return@repeat
            if (clip.command != command) {
                transitions += clip.clip
                command = clip.command
            }
            if (clip.clip != "solo") {
                assertEquals(clip.command, b.playback?.command)
                assertEquals(initialX, a.x)
            }
        }
        assertEquals(listOf("lead-clip", "lead-clip-next", "solo"), transitions)
        assertEquals(0, sim.people().first().queued)
    }

    @Test
    fun stoppingRepeatLetsTheCurrentCycleFinish() {
        val sim = SceneSimulation(listOf("lead"), emptySet(), Random(1), wanderOnly = true)
        val roles = listOf(PlaybackRole("clip", zero, 0f))
        assertTrue(sim.perform("lead", PerformanceOrder(null, roles, .25f, repeat = true)))
        val a = Pose()
        sim.pose(0, a)
        val before = a.playback?.command
        repeat(3) { sim.tick(.1f) }
        sim.pose(0, a)
        assertNotNull(a.playback)
        assertNotEquals(before, a.playback?.command)
        sim.stopRepeat("lead")
        assertFalse(sim.people().single().repeating)
        sim.pose(0, a)
        assertNotNull(a.playback)
        repeat(3) { sim.tick(.1f) }
        sim.pose(0, a)
        assertNull(a.playback)
    }

    @Test
    fun wanderOnlyDoesNotClaimFurnitureUntilDirected() {
        val sim = SceneSimulation(listOf("lead"), setOf(SceneObject.BED), Random(1), wanderOnly = true)
        repeat(400) {
            sim.tick(0.1f)
            assertNull(sim.holder(SceneObject.BED))
        }
        assertTrue(sim.use("lead", SceneObject.BED))
        assertEquals("lead", sim.holder(SceneObject.BED))
    }
}
