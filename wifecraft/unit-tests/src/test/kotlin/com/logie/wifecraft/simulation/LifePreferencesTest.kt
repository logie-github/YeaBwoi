package com.logie.wifecraft.simulation

import com.logie.wifecraft.creator.CasPersonality
import com.logie.wifecraft.creator.Initiation
import com.logie.wifecraft.creator.Pace
import com.logie.wifecraft.creator.Preferences
import com.logie.wifecraft.creator.StageKind
import com.logie.wifecraft.story.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Personality tab in the life simulation: chosen traits, and the boundaries NPCs keep when they start things themselves. */
class LifePreferencesTest {
    private class Bare : LifeWorld {
        override fun x(index: Int) = index.toFloat()
        override fun y(index: Int) = 0f
        override fun freeSpot(item: SceneObject, index: Int): Vec3? = null
        override fun approachable(index: Int) = true
        override fun finish(index: Int) {}
        override val chatterOn = true
        override val pairsOn = true
    }

    private fun life(a: CasPersonality?, b: CasPersonality?, seed: Long = 5): LifeSim =
        LifeSim(listOf("a", "b"), mapOf("a" to LifeProfile("Ann", 1, true, a), "b" to LifeProfile("Bea", 1, true, b)), seed)

    @Test fun chosenTraitsReplaceTheAssignedOnes() {
        val l = life(CasPersonality(listOf("BOOKISH", "CALM", "NOPE")), null)
        assertEquals(listOf(Trait.BOOKISH, Trait.CALM), l.traits("a"))
        assertEquals(Traits.assign("b", 1, true), l.traits("b"))           // none chosen: as before
        // opposites after the first are dropped
        assertEquals(listOf(Trait.LONER), LifePreferences.traits(CasPersonality(listOf("LONER", "GREGARIOUS"))))
    }

    @Test fun capsFollowTheAllowedStages() {
        assertEquals(Intimacy.INTIMATE, LifePreferences.cap(Preferences()))
        assertEquals(Intimacy.ROMANTIC, LifePreferences.cap(Preferences(allowed = StageKind.entries.toSet() - StageKind.INTIMATE)))
        assertEquals(Intimacy.FRIENDLY, LifePreferences.cap(Preferences(allowed = setOf(StageKind.DANCE))))
        assertEquals(Intimacy.CASUAL, LifePreferences.cap(Preferences(allowed = setOf(StageKind.KISS))))   // a kiss alone needs friendship's stages
        assertEquals(Intimacy.CASUAL, LifePreferences.cap(Preferences(allowed = emptySet())))
    }

    @Test fun allowedIsTheLeastOfStandingAndBoundaries() {
        val l = life(CasPersonality(preferences = Preferences(allowed = setOf(StageKind.EMBRACE, StageKind.DANCE))), null)
        val r = l.relationship("a", "b")!!
        r.friendship = 90f; r.romance = 90f
        assertEquals(Intimacy.FRIENDLY, l.allowed(0, 1))
        assertEquals(Intimacy.FRIENDLY, l.allowed(1, 0))
        val free = life(null, null)
        free.relationship("a", "b")!!.apply { friendship = 90f; romance = 90f }
        assertEquals(Intimacy.INTIMATE, free.allowed(0, 1))                 // nothing chosen: as before
    }

    @Test fun acceptanceHonoursInitiation() {
        for ((initiation, expectZero) in listOf(Initiation.NO to true, Initiation.ASK to false, Initiation.YES to false)) {
            val l = life(null, CasPersonality(preferences = Preferences(initiation = initiation)))
            l.relationship("a", "b")!!.apply { friendship = 60f; romance = 50f }
            val npc = l.acceptance(1, 0, InteractionId.HUG, byNpc = true)
            val player = l.acceptance(1, 0, InteractionId.HUG)
            if (expectZero) assertEquals(0f, npc) else assertTrue(npc > 0f)
            if (initiation == Initiation.ASK) assertEquals(player * LifePreferences.ASK_FACTOR, npc, 1e-5f)
            if (initiation == Initiation.YES) assertEquals(player, npc, 1e-5f)
            assertTrue(player > 0.5f)                                        // the player's own order is the player's
            // words are never a stage: chatting is as before
            assertEquals(l.acceptance(1, 0, InteractionId.CHAT), l.acceptance(1, 0, InteractionId.CHAT, byNpc = true), 1e-6f)
        }
        // a stage she doesn't allow is refused when started by someone else
        val l = life(null, CasPersonality(preferences = Preferences(allowed = setOf(StageKind.EMBRACE))))
        l.relationship("a", "b")!!.apply { friendship = 60f; romance = 60f }
        assertEquals(0f, l.acceptance(1, 0, InteractionId.KISS, byNpc = true))
        assertTrue(l.acceptance(1, 0, InteractionId.HUG, byNpc = true) > 0f)
    }

    @Test fun npcsNeverStartWhatTheOtherForbids() {
        // sweethearts, both romantic: left alone they kiss; with b's No, a never starts a kiss or a hug with b by herself
        fun kissesAndHugs(b: CasPersonality?): Int {
            var n = 0
            for (seed in 1L..30L) {
                val l = life(CasPersonality(listOf("ROMANTIC")), b ?: CasPersonality(listOf("ROMANTIC")), seed)
                l.relationship("a", "b")!!.apply { friendship = 70f; romance = 70f }
                l.setNeed("a", Need.SOCIAL, 0.1f); l.setNeed("a", Need.FUN, 0.1f)
                repeat(6) { val p = l.decide(0, Bare()); if (p?.other == 1 && (p.id == InteractionId.KISS || p.id == InteractionId.HUG)) n++ }
            }
            return n
        }
        assertTrue(kissesAndHugs(null) > 0, "free to: some kisses or hugs")
        assertEquals(0, kissesAndHugs(CasPersonality(listOf("ROMANTIC"), Preferences(initiation = Initiation.NO))))
        assertEquals(0, kissesAndHugs(CasPersonality(listOf("ROMANTIC"), Preferences(allowed = setOf(StageKind.DANCE)))))
    }

    @Test fun paceWeighsTheGentlerStages() {
        assertTrue(LifePreferences.paceFactor(Preferences(pace = Pace.SLOW), InteractionId.KISS) < 1f)
        assertTrue(LifePreferences.paceFactor(Preferences(pace = Pace.INTENSE), InteractionId.KISS) > 1f)
        assertEquals(1f, LifePreferences.paceFactor(Preferences(pace = Pace.SLOW), InteractionId.CHAT))
    }
}
