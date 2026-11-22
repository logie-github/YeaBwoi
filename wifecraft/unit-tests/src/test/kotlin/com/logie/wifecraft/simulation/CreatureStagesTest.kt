package com.logie.wifecraft.simulation

import com.logie.wifecraft.characters.CreatureNpcs
import com.logie.wifecraft.characters.DragonStages
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A creature's own stage in the simulation: only with her, the person active and she receiving, never on furniture. */
class CreatureStagesTest {
    private val stage = DragonStages.PET_HEAD
    private val dragon = "character:sindragosa"
    private val pair = PartnerPair.of(stage.pair())
    private val other = PartnerPair("plain", "Plain", pair.a, pair.b, PartnerSlot(0f, 0f, 0f, 0f), PartnerSlot(0.6f, 0f, 0f, 180f), true)

    private fun sim(vararg ids: String, pairs: List<PartnerPair> = listOf(pair, other)) =
        SceneSimulation(ids.toList(), emptySet<SceneObject>(), Random(3), wanderOnly = true).also {
            it.creatures = mapOf(dragon to CreatureNpcs.SINDRAGOSA)
            it.pairs = pairs
        }

    private fun order(partner: String?) = PerformanceOrder(partner,
        listOf(PlaybackRole(PlaybackKeys.pair(stage.id, 0), com.logie.wifecraft.story.Vec3(0f, 0f, 0f), 0f),
            PlaybackRole(PlaybackKeys.pair(stage.id, 1), com.logie.wifecraft.story.Vec3(2.1f, -0.2f, 0f), 3.1415927f)),
        stage.seconds, pairId = stage.id)

    @Test fun theKeyNamesItsPair() {
        assertEquals(stage.id, CreatureStages.pairIdOf(PlaybackKeys.pair(stage.id, 1)))
        assertNull(CreatureStages.pairIdOf("emote:3")); assertNull(CreatureStages.pairIdOf("clip"))
    }

    @Test fun onlyHerAndAPersonMayPlayIt() {
        val e = "sindragosa"
        assertTrue(CreatureStages.eligible(null, null, null, false, false))             // an ordinary stage: any two
        assertTrue(CreatureStages.eligible(e, null, e, false, true))
        assertTrue(CreatureStages.eligible(e, e, null, true, false))
        assertFalse(CreatureStages.eligible(e, null, null, false, false))               // two people
        assertFalse(CreatureStages.eligible(e, e, e, true, true))                       // two creatures
        assertFalse(CreatureStages.eligible(e, "other", null, true, false))             // a different creature
    }

    @Test fun twoPeopleAreRefusedItAndTheReasonIsSaid() {
        val s = sim("a", "b")
        assertFalse(s.perform("a", order("b")))
        assertTrue(assertNotNull(s.takeRefusal()).contains("Sindragosa"))
        assertFalse(s.perform("a", order(null)))                                       // alone: no creature either
    }

    @Test fun thePersonLeadsRoleZeroAndSheReceivesRoleOne() {
        val s = sim("a", dragon)
        assertTrue(s.perform("a", order(dragon)))
        repeat(80) { s.tick(0.1f) }
        val a = Pose(); val b = Pose()
        s.pose(0, a); s.pose(1, b)
        assertEquals(PlaybackKeys.pair(stage.id, 0), a.playback?.clip)
        assertEquals(PlaybackKeys.pair(stage.id, 1), b.playback?.clip)
        assertEquals(a.playback?.command, b.playback?.command)                          // one start, one clock
    }

    @Test fun whoeverWasChosenFirstThePersonStillPlaysRoleZero() {
        val s = sim("a", dragon)
        assertTrue(s.perform(dragon, order("a")))                                       // she leads: the roles turn over
        repeat(80) { s.tick(0.1f) }
        val a = Pose(); val b = Pose()
        s.pose(0, a); s.pose(1, b)
        assertEquals(PlaybackKeys.pair(stage.id, 0), a.playback?.clip)
        assertEquals(PlaybackKeys.pair(stage.id, 1), b.playback?.clip)
    }

    @Test fun sheStandsWhereTheTableSaysNotWhereTheSideOnRuleWouldPutHer() {
        val s = sim("a", dragon)
        assertTrue(s.perform("a", order(dragon)))
        repeat(80) { s.tick(0.1f) }
        val a = Pose(); val b = Pose()
        s.pose(0, a); s.pose(1, b)
        // she is where the stage's slot is from the person (2.1 yards across, turned round to face them), at no height
        assertEquals(2.1f, kotlin.math.hypot(b.x - a.x, b.y - a.y), 0.4f)
        assertEquals(0f, b.lift, 1e-4f)
        // her facing is the slot's own (turned about to face them), not a quarter turn side-on
        val toPerson = kotlin.math.atan2(a.y - b.y, a.x - b.x)
        var d = b.facing - toPerson
        while (d > Math.PI) d -= (2 * Math.PI).toFloat(); while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        assertTrue(kotlin.math.abs(d) < 0.6f, "she faces them: $d")
    }

    @Test fun itHasNoOtherWayRoundSoSwitchingPositionsIsRefused() {
        val s = sim("a", dragon)
        assertTrue(s.perform("a", order(dragon)))
        repeat(80) { s.tick(0.1f) }
        assertFalse(s.switchRoles("a"))
    }

    @Test fun theirOwnPickNeverChoosesItForTwoPeopleButMayForHerAndAPerson() {
        val people = sim("a", "b", pairs = listOf(pair))
        // two people with only her stage installed: nothing to pick, so they never start it
        repeat(400) { people.tick(0.25f) }
        val a = Pose(); val b = Pose(); people.pose(0, a); people.pose(1, b)
        assertNull(a.playback?.clip?.takeIf { stage.id in it }); assertNull(b.playback?.clip?.takeIf { stage.id in it })
    }

    @Test fun anAutoPlayedPairGoesThroughTheSamePlacementAndRoles() {
        val s = sim("a", dragon)
        assertTrue(s.autoplay(dragon, "a", stage.id, null, teleport = false))
        repeat(120) { s.tick(0.1f) }
        val a = Pose(); val b = Pose(); s.pose(0, a); s.pose(1, b)
        assertEquals(PlaybackKeys.pair(stage.id, 0), a.playback?.clip)
        assertEquals(PlaybackKeys.pair(stage.id, 1), b.playback?.clip)
    }
}
