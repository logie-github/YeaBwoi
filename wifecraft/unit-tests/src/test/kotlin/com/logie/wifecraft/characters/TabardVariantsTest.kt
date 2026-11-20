package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Sex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tabard variants (CharacterLooks.tabard, VariantKind.TABARD): the slot rule, and assets/characters.json made by it. */
class TabardVariantsTest {
    private val outfit = CharacterVariant("o", "Outfit", VariantKind.OUTFIT, raceId = 1, sex = Sex.FEMALE, picks = mapOf(10 to 101),
        equipment = mapOf(1 to 11, 3 to 13, 4 to 14, 5 to 15, 6 to 16, 7 to 17, 8 to 18, 9 to 19, 10 to 20, 15 to 25, 19 to 29))

    @Test fun shirtChestAndLegsComeOffTheTabardAndTheRestStay() {
        val n = assertNotNull(CharacterLooks.tabard(outfit))
        assertEquals("o_tabard", n.key)
        assertEquals("Outfit, tabard only", n.label)
        assertEquals(VariantKind.TABARD, n.kind)
        // shirt 4, chest or robe 5 and legs 7 go; tabard 19, head, shoulders, belt, feet, wrists, gloves and cloak stay
        assertEquals(mapOf(1 to 11, 3 to 13, 6 to 16, 8 to 18, 9 to 19, 10 to 20, 15 to 25, 19 to 29), n.equipment)
        assertEquals(setOf(4, 5, 7), CharacterLooks.TABARD_REMOVED)
        assertEquals(outfit.picks, n.picks)
        val a = assertNotNull(CharacterLooks.appearance(n))
        assertTrue(a.gear)
        assertEquals(n.equipment, a.clothing)
        assertTrue(n.kind.bare && n.kind.worn)
        // round trip, equipment kept
        val c = Character("x", "X", listOf(outfit, n))
        assertEquals(listOf(c), CharacterRoster.parse(CharacterRoster.toJson(listOf(c)).toString()))
        // the cast offers it once, drawn bare
        val choices = NamedCast(listOf(Character("x", "X", listOf(outfit)))).choices(Character("x", "X", listOf(outfit)))
        val t = assertNotNull(choices.firstOrNull { it.key == "o_tabard" })
        assertEquals(NamedCast.Kind.TABARD, t.kind)
        assertTrue(t.bare)
        assertEquals(n, t.variant)
    }

    @Test fun noneForADisplayAGnomeWomanOrAnOutfitWithoutATabard() {
        assertNull(CharacterLooks.tabard(CharacterVariant("d", "D", VariantKind.OUTFIT, display = 801)))
        assertNull(CharacterLooks.tabard(outfit.copy(raceId = 7)))
        assertNotNull(CharacterLooks.tabard(outfit.copy(raceId = 7, sex = Sex.MALE)))
        assertNull(CharacterLooks.tabard(outfit.copy(equipment = outfit.equipment - 19)))
        assertNull(CharacterLooks.tabard(outfit.copy(kind = VariantKind.BARE)))
        assertNull(CharacterLooks.tabard(outfit.copy(kind = VariantKind.NUDE)))
    }

    @Test fun theRosterHasEveryTabardVariantTheRuleMakesAndNoOther() {
        val roster = CharacterRoster.parse(File(System.getProperty("wifecraft.assets"), "characters.json").readText())
        val made = ArrayList<String>()
        for (ch in roster) {
            for (v in ch.variants.filter { it.kind == VariantKind.OUTFIT }) {
                val want = CharacterLooks.tabard(v)
                val have = ch.variant("${v.key}_tabard")
                if (want == null) { assertNull(have, "${ch.id}/${v.key}: no tabard variant"); continue }
                assertNotNull(have, "${ch.id}/${v.key}_tabard")
                assertEquals(VariantKind.TABARD, have.kind)
                assertEquals(want.equipment, have.equipment, "${ch.id}/${v.key}_tabard: kept slots")
                assertEquals(want.picks, have.picks)
                assertEquals(v.raceId, have.raceId)
                made += "${ch.id}/${have.key}"
            }
            assertTrue(ch.variants.filter { it.kind == VariantKind.TABARD }.all { it.key.endsWith("_tabard") || it.key.endsWith("_tabard_crusade") })
            assertTrue(ch.variants.none { it.kind == VariantKind.TABARD && (it.isDisplay || it.raceId == 7 && it.sex == Sex.FEMALE) }, "${ch.id}: no gnome woman or display tabard")
            assertTrue(ch.presets.none { it.kind == VariantKind.TABARD })
        }
        // of the roster's outfits only Whitemane's Scarlet display 2043 wears a tabard (15983)
        assertEquals(listOf("whitemane/scarlet_tabard"), made)
        val s = assertNotNull(roster.first { it.id == "whitemane" }.variant("scarlet_tabard"))
        assertEquals(mapOf(1 to 15991, 3 to 9574, 8 to 15988, 10 to 15989, 19 to 15983), s.equipment)
        assertTrue(roster.first { it.id == "chromie" }.variants.none { it.kind == VariantKind.TABARD })
        // and her Scarlet look in the game's Tabard of the Scarlet Crusade (item 23192, ItemDisplayInfo 692029) in place of
        // her display's own tabard, the same slots kept
        val crusade = assertNotNull(roster.first { it.id == "whitemane" }.variant("scarlet_tabard_crusade"))
        assertEquals(VariantKind.TABARD, crusade.kind)
        assertEquals(s.equipment + (19 to 692029), crusade.equipment)
        assertEquals(s.picks, crusade.picks)
    }
}
