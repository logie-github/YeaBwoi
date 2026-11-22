package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.BLOODELF
import com.logie.wifecraft.dialogue.Kit.GNOME
import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.ORC
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonaTest {
    @Test fun thePromptMakesThemARealPersonOfAzerothWithNoFourthWall() {
        val people = listOf(
            npc("sylvanas", UNDEAD, true, 3, listOf("BRAVE", "MISCHIEVOUS"), "Bitter and proud."),
            npc("thrall", ORC, false, 7, listOf("CALM")), npc("jaina", HUMAN, true, 8, listOf("BOOKISH", "ROMANTIC")),
            npc("lirath", BLOODELF, false, 2), npc("gimble", GNOME, null, 0),
        )
        for (p in people) {
            val s = PersonaPrompt.system(p, lore).lowercase()
            for (w in FOURTH_WALL) assertFalse(s.contains(w), "${p.id}'s prompt has '$w'")
            assertTrue(s.contains("you are ${p.name.lowercase()}"), "first person, by name")
            assertTrue("azeroth" in s)
            assertTrue("adult" in s, "every character is a grown adult")
            assertTrue("no modern speech" in s || "use no modern speech" in s)
        }
    }

    @Test fun thePromptCarriesRaceClassFactionAndNature() {
        val s = PersonaPrompt.system(npc("sylvanas", UNDEAD, true, 3, listOf("BRAVE", "MISCHIEVOUS"), "Bitter and proud."), lore)
        assertTrue("Forsaken" in s); assertTrue("hunter" in s); assertTrue("Horde" in s)
        assertTrue("unshaken" in s && "prank" in s, "the traits in words")
        assertTrue("Bitter and proud." in s)
        assertTrue("dry, bitter" in s, "the Forsaken's register")
        val orc = PersonaPrompt.system(npc("grom", ORC, false, 1), lore)
        assertTrue("blunt" in orc, "orcs are blunt")
    }

    @Test fun thePromptIsStablePerNpcAndNeverDependsOnTheScene() {
        val a = npc("a", HUMAN, true, 2, listOf("CALM"))
        assertEquals(PersonaPrompt.system(a, lore), PersonaPrompt.system(a.copy(), lore))
        val s = PersonaPrompt.system(a, lore)
        assertFalse("Tirisfal" in s || "light rain" in s, "no scene in the prefix")
    }

    @Test fun retrievalPicksOnlyWhatTheSceneNeeds() {
        val sn = lore.forScene(UNDEAD, 0, "A House in Brill, Tirisfal", listOf(BLOODELF to 8), withBodies = false)
        val all = sn.joinToString(" ")
        assertTrue("Brill" in all, "the place")
        assertTrue("blood elf" in all, "the other's race")
        assertTrue(lore.stance(UNDEAD, BLOODELF)!! in all, "the stance of the Forsaken on blood elves")
        assertFalse("gnome" in all.lowercase(), "not a race that is not here")
        assertFalse("Their body" in all, "no bodies unless asked")
        assertTrue(all.length < 1600, "short")
        val withBodies = lore.forScene(UNDEAD, 0, "Brill", listOf(BLOODELF to 8), withBodies = true).joinToString(" ")
        assertTrue("Their body" in withBodies)
    }

    @Test fun stancesAreAsymmetricAndFactionAware() {
        assertTrue("frighten" in lore.stance(HUMAN, UNDEAD)!!.lowercase() || "repuls" in lore.stance(HUMAN, UNDEAD)!!.lowercase())
        assertNotNull(lore.stance(UNDEAD, HUMAN)); assertNotNull(lore.stance(ORC, HUMAN))
        assertNotEquals(lore.stance(HUMAN, UNDEAD), lore.stance(UNDEAD, HUMAN))
        assertNull(lore.stance(HUMAN, 999))
        assertEquals(Faction.HORDE, lore.faction(UNDEAD)); assertEquals(Faction.ALLIANCE, lore.faction(HUMAN))
        assertEquals(Faction.HORDE, lore.faction(26)); assertEquals(Faction.NEUTRAL, lore.faction(24))
    }

    private fun assertNotEquals(a: Any?, b: Any?) = assertTrue(a != b)

    @Test fun dispositionComesFromTheTraitsAndTheBio() {
        val bold = Disposition.of(listOf("BRAVE", "MISCHIEVOUS"))
        val shy = Disposition.of(listOf("LONER", "ASCETIC"), "A shy, devout woman.")
        assertTrue(bold.boldness > 0.5f && bold.caution < 0f)
        assertTrue(shy.caution > 0.6f && shy.devout > 0.5f)
        assertTrue(bold.speed > shy.speed)
        assertEquals("shy", shy.style)
        assertTrue(bold.style == "bold" || bold.style == "dominant")
        assertTrue(Disposition.of(listOf("ROMANTIC", "CALM")).style == "tender")
        assertTrue(bold.desireTalk > shy.desireTalk)
    }
}
