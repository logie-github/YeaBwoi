package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Sex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The nude variants (CharacterLooks.nude, VariantKind.NUDE): the slot rule, and assets/characters.json made by it. */
class NudeVariantsTest {
    private val outfit = CharacterVariant("o", "Outfit", VariantKind.OUTFIT, raceId = 1, sex = Sex.FEMALE, picks = mapOf(10 to 101),
        equipment = mapOf(1 to 11, 3 to 13, 4 to 14, 5 to 15, 6 to 16, 7 to 17, 8 to 18, 9 to 19, 10 to 20, 15 to 25, 19 to 29))

    @Test fun torsoLegsAndFeetComeOffTheRestStays() {
        val n = assertNotNull(CharacterLooks.nude(outfit))
        assertEquals("o_nude", n.key)
        assertEquals(VariantKind.NUDE, n.kind)
        // head, shoulders, wrists, gloves and cloak stay; shirt, chest/robe, belt, legs, feet and tabard go
        assertEquals(mapOf(1 to 11, 3 to 13, 9 to 19, 10 to 20, 15 to 25), n.equipment)
        assertEquals(outfit.picks, n.picks)
        val a = assertNotNull(CharacterLooks.appearance(n))
        assertTrue(a.gear)
        assertEquals(n.equipment, a.clothing)
        // round trip, equipment kept
        val c = Character("x", "X", listOf(outfit, n))
        assertEquals(listOf(c), CharacterRoster.parse(CharacterRoster.toJson(listOf(c)).toString()))
    }

    @Test fun noneForADisplayAGnomeWomanOrNothingToTakeOff() {
        assertNull(CharacterLooks.nude(CharacterVariant("d", "D", VariantKind.OUTFIT, display = 801)))
        assertNull(CharacterLooks.nude(outfit.copy(raceId = 7)))
        assertNotNull(CharacterLooks.nude(outfit.copy(raceId = 7, sex = Sex.MALE)))
        assertNull(CharacterLooks.nude(outfit.copy(equipment = mapOf(3 to 13, 10 to 20))))
        assertNull(CharacterLooks.nude(outfit.copy(kind = VariantKind.BARE)))
    }

    @Test fun theRosterHasEveryNudeVariantTheRuleMakesAndNoOther() {
        val roster = CharacterRoster.parse(File(System.getProperty("wifecraft.assets"), "characters.json").readText())
        val made = ArrayList<String>()
        for (ch in roster) {
            for (v in ch.variants.filter { it.kind == VariantKind.OUTFIT }) {
                val want = CharacterLooks.nude(v)
                val have = ch.variant("${v.key}_nude")
                if (want == null) { assertNull(have, "${ch.id}/${v.key}: no nude variant"); continue }
                assertNotNull(have, "${ch.id}/${v.key}_nude")
                assertEquals(VariantKind.NUDE, have.kind)
                assertEquals(want.equipment, have.equipment, "${ch.id}/${v.key}_nude: kept slots")
                assertEquals(want.picks, have.picks)
                assertEquals(v.raceId, have.raceId)
                made += "${ch.id}/${have.key}"
            }
            assertTrue(ch.variants.filter { it.kind == VariantKind.NUDE }.all { it.key.endsWith("_nude") })
            assertTrue(ch.variants.none { it.kind == VariantKind.NUDE && it.raceId == 7 && it.sex == Sex.FEMALE }, "${ch.id}: no gnome woman nude")
            assertTrue(ch.presets.none { it.kind == VariantKind.NUDE })
        }
        assertEquals(listOf("lilian/bfa_nude", "whitemane/scarlet_nude", "whitemane/deathknight_nude", "xalatath/bfa_nude"), made)
        assertTrue(roster.first { it.id == "chromie" }.variants.none { it.kind == VariantKind.NUDE })
    }
}
