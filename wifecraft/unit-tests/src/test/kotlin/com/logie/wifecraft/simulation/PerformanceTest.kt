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
