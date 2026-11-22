package com.logie.wifecraft.dialogue

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.CasPersonality
import com.logie.wifecraft.creator.Initiation
import com.logie.wifecraft.creator.Pace
import com.logie.wifecraft.creator.Preferences
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilesTest {
    @Test fun theCreatorsChoicesBecomeTheProfile() {
        val pers = CasPersonality(listOf("LONER", "ASCETIC"), Preferences(Initiation.ASK, Pace.SLOW), "A shy, devout seamstress.")
        val p = Profiles.of(Actor("x", "Mara Voss", Appearance(1, Sex.FEMALE, classId = 5, personality = pers)))
        assertEquals(listOf("LONER", "ASCETIC"), p.traits); assertEquals(1, p.raceId); assertEquals(5, p.classId); assertEquals(true, p.female)
        assertEquals("shy", p.disposition.style); assertTrue(p.disposition.devout > 0.3f)
    }

    @Test fun withoutAPersonalityTheLifeSimulationsTraitsAreUsed() {
        val a = Actor("rowan", "Rowan", Appearance(5, Sex.MALE))
        val p = Profiles.of(a)
        assertEquals(3, p.traits.size); assertEquals(p.traits, Profiles.of(a).traits, "the same every time"); assertEquals(false, p.female)
    }
}
