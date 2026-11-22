package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.Disposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Consent and limits rules: resolution (role, act, category, default), categories read from the catalogue, defaults from nature. */
class ConsentPolicyTest {
    private fun act(name: String, kind: String = "", pack: String = "", id: String = name.lowercase().replace(' ', '-')) = ConsentAct(id, name, kind, pack)
    private val oral = act("Standing Blowjob")
    private val kiss = act("Slow Kiss")

    @Test fun stancesOrderWontOverAskOverWill() {
        assertEquals(Stance.WONT, Stance.WILL.strictest(Stance.WONT)); assertEquals(Stance.ASK, Stance.WILL.strictest(Stance.ASK))
        assertEquals(Stance.WONT, Stance.WONT.strictest(Stance.ASK)); assertEquals(Stance.WILL, Stance.WILL.strictest(Stance.WILL))
        assertEquals(Stance.WONT, Stance.of("WONT")); assertNull(Stance.of("nope"))
    }

    @Test fun resolutionGoesRoleThenActThenCategoryThenDefault() {
        val p = ConsentPolicy()
        assertEquals(Resolved.Source.DEFAULT, p.resolve("n", oral).source)
        p.set("n", NpcLimits().withCategory(ActCategories.ORAL, Stance.WONT))
        assertEquals(Resolved(Stance.WONT, Resolved.Source.CATEGORY, ActCategories.ORAL), p.resolve("n", oral))
        // an act overrides its category, in both directions
        p.set("n", p.limitsOf("n").withAct(oral.id, Stance.WILL))
        assertEquals(Resolved(Stance.WILL, Resolved.Source.ACT), p.resolve("n", oral))
        p.set("n", NpcLimits().withCategory(ActCategories.ORAL, Stance.WILL).withAct(oral.id, Stance.WONT))
        assertFalse(p.allows("n", oral))
        // a role overrides the act
        p.set("n", p.limitsOf("n").withRole(oral.id, 1, Stance.ASK))
        assertEquals(Resolved(Stance.ASK, Resolved.Source.ROLE), p.resolve("n", oral, 1))
        assertEquals(Stance.WONT, p.stance("n", oral, 0)); assertEquals(Stance.WONT, p.stance("n", oral), "any role: the act's")
        // someone else is untouched
        assertTrue(p.allows("other", oral))
    }

    @Test fun anActInSeveralCategoriesTakesTheStrictestSetOne() {
        val rough = act("Rough Doggy")
        assertEquals(listOf(ActCategories.ROUGH, ActCategories.VAGINAL), rough.categories)
        val p = ConsentPolicy()
        p.set("n", NpcLimits().withCategory(ActCategories.VAGINAL, Stance.WILL).withCategory(ActCategories.ROUGH, Stance.WONT))
        assertEquals(Stance.WONT, p.stance("n", rough)); assertEquals(ActCategories.ROUGH, p.resolve("n", rough).via)
        p.set("n", NpcLimits().withCategory(ActCategories.VAGINAL, Stance.WONT))
        assertFalse(p.allows("n", rough), "a Won't on either keeps it from being played")
        // what the player set beats a stricter default on the sister category
        p.set("n", NpcLimits().withCategory(ActCategories.VAGINAL, Stance.WILL))
        assertEquals(Stance.WILL, p.stance("n", rough))
    }

    @Test fun categoriesAreReadFromNamesAndPackKinds() {
        assertEquals(ActCategories.KISSING, kiss.category)
        assertEquals(ActCategories.ORAL, oral.category); assertEquals(ActCategories.ORAL, act("x", kind = "ORALJOB").category)
        assertEquals(ActCategories.ORAL, act("x", kind = "ORAJOB").category, "a pack's typo")
        assertEquals(ActCategories.HANDS, act("Hand Job Sofa").category)
        assertEquals(ActCategories.HANDS, act("x", kind = "HANDJOB").category)
        assertEquals(ActCategories.ANAL, act("Anal Doggy").category)
        assertEquals(listOf(ActCategories.ANAL, ActCategories.VAGINAL), act("Anal Doggy").categories)
        assertEquals(ActCategories.TOYS, act("Vibrator Tease").category)
        assertEquals(ActCategories.BONDAGE, act("Rope Bound Kneel").category)
        assertEquals(ActCategories.FEMDOM, act("Facesitting").category); assertEquals(ActCategories.FEMDOM, act("x", kind = "FEMDOMANIA").category)
        assertEquals(ActCategories.CUCKOLD, act("Cuckold Watch").category)
        assertEquals(ActCategories.FOOT, act("Footjob On Couch").category)
        assertEquals(ActCategories.PUBLIC, act("Public Flashing").category)
        assertEquals(ActCategories.GROUP, act("Threesome Bed").category)
        assertEquals(ActCategories.DANCING, act("Lap Dance").category); assertEquals(ActCategories.DANCING, act("x", kind = "TEASING").category)
        assertEquals(ActCategories.CLIMAX, act("x", kind = "CLIMAX").category)
        assertEquals(ActCategories.FRIENDLY, act("High Five").category)
        // the gentle ones apply only where no explicit one does
        assertEquals(listOf(ActCategories.VAGINAL), act("Kissing Missionary").categories)
        // whole words: a word inside another is not a match
        assertEquals(ActCategories.OTHER, act("Moral Support").category); assertEquals(ActCategories.OTHER, act("Passage").category)
    }

    @Test fun aNewPacksOwnCategoriesShowUpByThemselvesAndTheRestIsOther() {
        assertEquals("Cherry pie", act("Scene 4", kind = "CherryPie").category)
        assertEquals("Lpgs story", act("Scene 4", kind = "LPGS_STORY").category)
        assertEquals("Lpgs", act("Scene 4", pack = "LPGS").category, "the pack's own category when the kind says nothing")
        for (generic in listOf("", "Partner", "CALM")) assertEquals(ActCategories.OTHER, act("Scene 4", kind = generic, pack = generic).category)
        assertEquals(ActCategories.OTHER, act("").category)
        assertEquals(listOf(ActCategories.ORAL, "Cherry pie", "Lpgs", ActCategories.OTHER), ActCategories.sorted(listOf(ActCategories.OTHER, "Lpgs", ActCategories.ORAL, "Cherry pie")))
    }

    @Test fun defaultsFollowTheNatureAndNeverRefuse() {
        val shy = Disposition(caution = 0.7f, devout = 0.3f); val bold = Disposition(boldness = 0.8f, curiosity = 0.4f)
        val all = ActCategories.ORDER + ActCategories.OTHER + "A New Pack"
        val shyAsks = all.count { ConsentDefaults.stance(shy, it) == Stance.ASK }
        val boldAsks = all.count { ConsentDefaults.stance(bold, it) == Stance.ASK }
        assertTrue(shyAsks > boldAsks, "shy $shyAsks bold $boldAsks")
        for (c in all) for (d in listOf(shy, bold, Disposition())) assertNotEquals(Stance.WONT, ConsentDefaults.stance(d, c), "never silently a Won't")
        for (c in listOf(ActCategories.KISSING, ActCategories.DANCING, ActCategories.FRIENDLY)) assertEquals(Stance.WILL, ConsentDefaults.stance(Disposition(caution = 0.5f), c))
        assertEquals(Stance.ASK, ConsentDefaults.stance(Disposition(), ActCategories.CUCKOLD), "even an ordinary person is asked about the heaviest")
        assertEquals(Stance.WILL, ConsentDefaults.stance(bold, ActCategories.BONDAGE))
        assertEquals(Stance.ASK, ConsentDefaults.stance(shy, ActCategories.ORAL))
        // through the policy: the default is each NPC's own
        val p = ConsentPolicy(disposition = { id -> if (id == "shy") shy else bold })
        assertEquals(Stance.ASK, p.stance("shy", oral)); assertEquals(Stance.WILL, p.stance("bold", oral))
    }

    @Test fun aStageNeedsBothToAllowItInTheirRolesAndMayBeTurnedOver() {
        val p = ConsentPolicy()
        assertEquals(0, p.orientation("a", "b", oral)); assertTrue(p.allowsPair("a", "b", oral))
        p.set("a", NpcLimits().withRole(oral.id, 0, Stance.WONT))           // a won't do it in role 0 ...
        assertEquals(1, p.orientation("a", "b", oral), "... so a takes role 1")
        p.set("b", NpcLimits().withRole(oral.id, 0, Stance.WONT))           // ... and b won't in role 0 either: nobody can play role 0
        assertEquals(-1, p.orientation("a", "b", oral)); assertFalse(p.allowsPair("a", "b", oral))
        p.set("b", NpcLimits().withAct(oral.id, Stance.WONT))
        assertEquals("b", p.decliner("a", "b", oral)); assertTrue(p.allowsRoles("a", "b", act("Other act"), 0))
        assertEquals("a", p.decliner("a", "b", oral, 0)); assertNull(p.decliner("a", "b", kiss))
        // an act both play alike (an emote) has no roles
        assertTrue(p.allowsPair("a", "b", kiss, symmetric = true)); p.set("a", NpcLimits().withAct(kiss.id, Stance.WONT))
        assertFalse(p.allowsPair("a", "b", kiss, symmetric = true))
    }

    @Test fun willSkipsTheQuestionAndAskKeepsItAndTheOrderersLimitCounts() {
        val p = ConsentPolicy()
        p.set("b", NpcLimits().withAct(oral.id, Stance.WILL))
        assertTrue(p.skipsAsking("a", "b", oral, directed = false))
        p.set("a", NpcLimits().withAct(oral.id, Stance.ASK))
        assertTrue(p.skipsAsking("a", "b", oral, directed = false), "the invitee decides when nobody ordered it")
        assertFalse(p.skipsAsking("a", "b", oral, directed = true), "an ordered one asks the orderer too")
        p.set("b", NpcLimits().withAct(oral.id, Stance.ASK))
        assertFalse(p.skipsAsking("a", "b", oral, directed = false))
    }

    @Test fun editsBumpTheVersionAndEmptyLimitsAreForgotten() {
        val p = ConsentPolicy(mapOf("a" to NpcLimits(), "b" to NpcLimits().withAct("x", Stance.WONT)))
        assertEquals(setOf("b"), p.all.keys); val v = p.version
        p.set("a", NpcLimits().withAct("y", Stance.WILL)); assertTrue(p.version > v)
        p.set("a", NpcLimits.EMPTY); assertEquals(setOf("b"), p.all.keys)
        p.setAll(mapOf("a" to NpcLimits().withAct("z", Stance.WONT), "b" to NpcLimits.EMPTY)); assertEquals(setOf("a"), p.all.keys)
    }
}
