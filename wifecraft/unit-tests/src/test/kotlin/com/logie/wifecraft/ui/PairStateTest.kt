package com.logie.wifecraft.ui

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.ImportEvent
import com.logie.wifecraft.anim.PairAddResult
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.anim.PairOffset
import com.logie.wifecraft.anim.Pairing
import com.logie.wifecraft.sims.SimsClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The review's partner animations: rows from the events, picking two clips by hand, the save with the duplicate policy. */
class PairStateTest {
    private fun anim(n: String) = Anim("", n, "", 1f, false, emptyList(), "h:$n", 1L)
    private fun pair(name: String, source: String = "s:$name") =
        PairAnim("", name, PairAnim.CATEGORY, anim("$name a"), anim("$name b"), PairOffset.ZERO, PairOffset.ZERO, false, emptySet(), emptyList(), true, source, 1L)
    private fun half(n: String) = ImportEvent.Unpaired("p.package :: $n", SimsClip(n, 1f, emptyList(), "k:$n"), "p.package")

    private class Store : PairStore {
        val kept = mutableListOf<PairAnim>()
        override fun addPair(p: PairAnim): PairAddResult {
            kept.firstOrNull { it.source == p.source }?.let { return PairAddResult(it, true, "already") }
            kept += p; return PairAddResult(p, false, "saved")
        }
        override fun pairCategories() = kept.map { it.category }.distinct()
    }

    @Test fun pairedEventsBecomeRowsAndHalvesAreRemembered() {
        val run = ImportRun()
        run.apply(ImportEvent.Found("p.package"))
        run.apply(ImportEvent.Found("p.package :: a_x + a_y"))
        run.apply(ImportEvent.Paired("Hug", pair("Hug"), Pairing.Mode.NAME))
        run.apply(ImportEvent.Found("p.package :: lone"))
        run.apply(ImportEvent.Done(anim("lone")))
        run.apply(half("lone"))
        run.finish()
        assertEquals(listOf("Partner: Hug", "lone"), run.rows.map { it.name })
        assertTrue(run.rows[0].note.contains("clip names"))
        assertEquals(listOf("Hug"), run.pairs.map { it.name })
        assertEquals(1, run.unpaired.size)
        assertEquals("1 converted, 1 partner", run.summary())
    }

    @Test fun twoClipsArePairedByHand() {
        val d = PairDraft(listOf(pair("Hug")), listOf(half("one"), half("two"), half("three")))
        assertFalse(d.canPair)
        d.pick(2); d.pick(0)
        assertEquals("A: three · B: one", d.pickLabel())
        d.swap()
        assertEquals("A: one · B: three", d.pickLabel())
        d.pick(0)   // un-picks A: B becomes A
        assertEquals(2, d.pickA); assertNull(d.pickB)
        d.pick(0)
        var got: Pair<String, String>? = null
        assertNull(d.confirm { a, b -> got = a.name to b.name; pair("Made", "s:made") })
        assertEquals("three" to "one", got)
        assertEquals(listOf("two"), d.unpaired.map { it.clip.name })
        assertTrue(d.items.last().byHand)
        assertFalse(d.canPair)
        // a failed conversion keeps the picks and says why
        d.pick(0)
        assertEquals("Pick actor B.", d.confirm { _, _ -> error("x") })
        val d2 = PairDraft(emptyList(), listOf(half("a"), half("b")))
        d2.pick(0); d2.pick(1)
        assertEquals("lengths differ", d2.confirm { _, _ -> throw IllegalArgumentException("lengths differ") })
        assertTrue(d2.canPair)
    }

    @Test fun theSaveKeepsNamesCategoriesAndTheSwitch() {
        val store = Store()
        store.addPair(pair("Old", "s:dup"))
        val d = PairDraft(listOf(pair("Hug"), pair("Again", "s:dup"), pair("Skip me")), emptyList())
        d.setName(0, "  Warm   hug "); d.setCategory(0, ""); d.setEnabled(0, false); d.toggle(2)
        val r = d.save(store)
        assertEquals("1 added, 1 already in the library (skipped)", ReviewDraft.summary(r))
        val saved = store.kept.last()
        assertEquals("Warm hug", saved.name)
        assertEquals("Partner", saved.category)
        assertFalse(saved.enabled)
        assertEquals(listOf("Partner"), d.categoryChoices(emptyList()))
        // the singles of clips paired by hand are left out of the save
        val singles = ReviewDraft(listOf(anim("one"), anim("two")))
        singles.exclude(listOf("h:one"))
        assertEquals(1, singles.chosen)
    }

    @Test fun theBrowserFiltersAndDescribesPairs() {
        val all = listOf(pair("Hug"), pair("Kiss").copy(category = "Close", loops = true, enabled = false, locations = setOf("BED")))
        assertEquals(listOf("Kiss"), PairBrowse.filter(all, "kis", null).map { it.name })
        assertEquals(listOf("Hug"), PairBrowse.filter(all, "", "partner").map { it.name })
        assertEquals(listOf("Close" to 1, "Partner" to 1), PairBrowse.categories(all))
        assertEquals("1.0 s, loops, for BED, off", PairBrowse.describe(all[1]))
    }
}
