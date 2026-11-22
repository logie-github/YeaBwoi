package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Consent screen's data: the catalogue's categories, the buttons' edits, and what is remembered. */
class ConsentBookTest {
    private fun act(name: String, kind: String = "", people: Int = 2) = ConsentAct(name.lowercase().replace(' ', '-'), name, kind, people = people)
    private val acts = listOf(act("Standing Blowjob"), act("Cowgirl"), act("Slow Kiss"), act("Scene 9", kind = "CherryPie"), act("Mystery"), act("Solo pose", people = 1),
        act("Rough Doggy"), act("Standing Blowjob"))

    @Test fun theCatalogueIsGroupedByCategoryWithUnknownPacksAndOtherLast() {
        val groups = ActCatalogue.group(acts)
        assertEquals(listOf(ActCategories.KISSING, ActCategories.ORAL, ActCategories.VAGINAL, ActCategories.ROUGH, "Cherry pie", ActCategories.OTHER), groups.map { it.category })
        assertEquals(listOf("Standing Blowjob"), groups.first { it.category == ActCategories.ORAL }.acts.map { it.name }, "each act once, a solo one left out")
        assertTrue("Rough Doggy" in groups.first { it.category == ActCategories.VAGINAL }.acts.map { it.name }, "listed under each of its categories")
        assertTrue("Rough Doggy" in groups.first { it.category == ActCategories.ROUGH }.acts.map { it.name })
        assertEquals(emptyList(), ActCatalogue.group(emptyList()))
    }

    @Test fun searchFindsActsByNameKindOrCategory() {
        val groups = ActCatalogue.group(acts)
        assertEquals(listOf("Cowgirl"), ActCatalogue.search(groups, "cow").flatMap { g -> g.acts.map { it.name } }.distinct())
        assertEquals(listOf("Scene 9"), ActCatalogue.search(groups, "cherry").flatMap { g -> g.acts.map { it.name } })
        assertEquals(2, ActCatalogue.search(groups, "Rough").size, "the category and the act's name")
        assertEquals(groups, ActCatalogue.search(groups, "  "))
        assertEquals(emptyList(), ActCatalogue.search(groups, "zzzz"))
    }

    @Test fun setCategoryClearsItsActsOwnChoicesAndSetActGoesActByAct() {
        val oral = acts[0]
        var l = NpcLimits().withAct(oral.id, Stance.WONT).withRole(oral.id, 0, Stance.WONT).withAct("elsewhere", Stance.WONT)
        l = ConsentEdit.setCategory(l, ActCategories.ORAL, Stance.WILL, listOf(oral))
        assertEquals(Stance.WILL, l.categories[ActCategories.ORAL]); assertEquals(mapOf("elsewhere" to Stance.WONT), l.acts); assertTrue(l.roles.isEmpty())
        val p = ConsentPolicy(mapOf("n" to ConsentEdit.setAct(l, oral, Stance.WONT)))
        assertEquals(Stance.WONT, p.stance("n", oral)); assertEquals(Stance.WILL, p.stance("n", act("Other Blowjob")), "its category's stance for the others")
        assertEquals(Stance.WILL, ConsentPolicy(mapOf("n" to ConsentEdit.setAct(l.withAct(oral.id, Stance.WONT), oral, null))).stance("n", oral), "cleared: back to its category's")
    }

    @Test fun copyToAllCopyFromAndReset() {
        val mine = NpcLimits().withCategory(ActCategories.ANAL, Stance.WONT)
        val all = mapOf("a" to mine, "b" to NpcLimits().withAct("x", Stance.WILL))
        assertEquals(mapOf("b" to mine, "c" to mine), ConsentEdit.copyToAll(all, "a", listOf("a", "b", "c")))
        assertEquals(mine, ConsentEdit.copyFrom(all, "a", "b")); assertEquals(all["b"], ConsentEdit.copyFrom(all, "b", "b"))
        assertEquals(NpcLimits.EMPTY, ConsentEdit.copyFrom(all, "zz", "b"), "copying from someone with no settings: the defaults")
        assertEquals(NpcLimits.EMPTY, ConsentEdit.reset(mine))
        val p = ConsentPolicy(all); p.setAll(ConsentEdit.copyToAll(all, "a", listOf("a", "b", "c")))
        assertEquals(Stance.WONT, p.stance("c", act("Anal Doggy")))
    }

    @Test fun theLimitsLineCountsWhatIsSet() {
        assertEquals("Limits: defaults", ConsentEdit.summary(listOf(NpcLimits.EMPTY, NpcLimits.EMPTY)))
        assertEquals("Limits: 1 person with their own settings", ConsentEdit.summary(listOf(NpcLimits().withAct("x", Stance.WILL), NpcLimits.EMPTY)))
        assertEquals("Limits: 3 set to Won't", ConsentEdit.summary(listOf(NpcLimits().withAct("x", Stance.WONT).withCategory("Oral", Stance.WONT), NpcLimits().withAct("y", Stance.WONT))))
    }

    @Test fun theBookRoundTripsPerCharacterAndPerScene() {
        val a = NpcLimits().withCategory(ActCategories.ORAL, Stance.WONT).withAct("pair-9", Stance.WILL).withRole("pair-9", 1, Stance.ASK)
        val b = NpcLimits().withCategory("Cherry pie", Stance.ASK)
        val key = ConsentBook.sceneKey("embassy", listOf("n2", "n1"))
        assertEquals(ConsentBook.sceneKey("embassy", listOf("n1", "n2")), key)
        val book = ConsentBook.EMPTY.with("n1", a).with("n2", b).with("n1", b, key)
        val back = ConsentBook.parse(book.toJson().toString())
        assertEquals(a, back.limitsFor("n1")); assertEquals(b, back.limitsFor("n2"))
        assertEquals(b, back.limitsFor("n1", key), "the scene's own wins in that scene"); assertEquals(a, back.limitsFor("n1", "elsewhere|n1"))
        assertTrue(back.hasScene("n1", key)); assertEquals(mapOf("n1" to b, "n2" to b), back.forCast(listOf("n1", "n2"), key))
        assertEquals(book.toJson().toString(), back.toJson().toString())
        // empty limits are forgotten, in the character's and in the scene's
        assertEquals(setOf("n2"), book.with("n1", NpcLimits.EMPTY).characters.keys)
        assertTrue(book.with("n1", NpcLimits.EMPTY, key).scenes.isEmpty())
    }

    @Test fun anUnreadableBookIsNothingRemembered() {
        for (bad in listOf(null, "", "   ", "{not json", "[1,2]", "{\"characters\":{\"a\":{\"acts\":{\"x\":\"MAYBE\"}}}}"))
            assertEquals(emptyMap(), ConsentBook.parse(bad).characters, "$bad")
        assertEquals(Stance.WONT, ConsentBook.parse("{\"characters\":{\"a\":{\"acts\":{\"x\":\"WONT\",\"y\":\"???\"}}}}").limitsFor("a").acts["x"])
    }
}
