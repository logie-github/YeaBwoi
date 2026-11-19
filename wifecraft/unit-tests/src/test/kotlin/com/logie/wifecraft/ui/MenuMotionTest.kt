package com.logie.wifecraft.ui

import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The menus' motion and touch (W41): one set of durations, off under reduced motion; one tap per press; 48 dp to touch. */
class MenuMotionTest {
    @Test fun theDurationsAreShortAndOrdered() {
        assertTrue(MenuMotion.WINDOW_IN_MS in 120..180 && MenuMotion.WINDOW_OUT_MS in 90..180)
        assertTrue(MenuMotion.WINDOW_OUT_MS <= MenuMotion.WINDOW_IN_MS, "going is never slower than coming")
        assertTrue(MenuMotion.POP_MS < MenuMotion.WINDOW_IN_MS, "a dropdown is quicker than a window")
        assertEquals(100, MenuMotion.PRESS_MS)
        assertEquals(0.96f, MenuMotion.PRESS_SCALE)
        assertTrue(MenuMotion.WINDOW_SCALE_FROM in 0.9f..0.99f)
        assertTrue(MenuMotion.REOPEN_MS <= MenuMotion.DEBOUNCE_MS)
    }

    @Test fun removeAnimationsStopsEveryMotion() {
        assertTrue(MenuMotion.reduced(0f))
        assertTrue(MenuMotion.reduced(1f, removeAnimations = true))
        assertFalse(MenuMotion.reduced(1f)); assertFalse(MenuMotion.reduced(0.5f))
        for (ms in listOf(MenuMotion.WINDOW_IN_MS, MenuMotion.WINDOW_OUT_MS, MenuMotion.POP_MS, MenuMotion.PAGE_MS, MenuMotion.PRESS_MS)) {
            assertEquals(0, MenuMotion.duration(ms, reduced = true))
            assertEquals(ms, MenuMotion.duration(ms, reduced = false))
        }
        assertEquals(1f, MenuMotion.pressScale(down = true, reduced = true), "no sink under reduced motion")
        assertEquals(MenuMotion.PRESS_SCALE, MenuMotion.pressScale(down = true, reduced = false))
        assertEquals(1f, MenuMotion.pressScale(down = false, reduced = false))
    }

    @Test fun aSecondTapWithinTheQuietTimeDoesNothing() {
        var now = 1_000L
        val g = ClickGate(300) { now }
        assertTrue(g.pass(), "the first tap acts")
        now += 120; assertFalse(g.pass(), "a double tap's second is dropped")
        now += 150; assertFalse(g.pass(), "still within 300 ms of the first")
        now += 31; assertTrue(g.pass(), "301 ms after the first: acts")
        now += 10; assertFalse(g.pass())
        g.reset(); assertTrue(g.pass(), "a reset gate lets the next tap through")
        // a repeating button (zoom, undo): no quiet time
        val r = ClickGate(0) { now }
        assertTrue(r.pass()); assertTrue(r.pass()); assertTrue(r.pass())
        // the clock going back (a test's clock, or a restored state) never locks the button
        val b = ClickGate(300) { now }
        assertTrue(b.pass()); now -= 5_000; assertTrue(b.pass())
    }

    @Test fun smallThingsGetAFingersTouchBoxWithoutGrowing() {
        assertEquals(11f, HitTarget.pad(26f))
        assertEquals(48f, HitTarget.box(26f))
        assertEquals(0f, HitTarget.pad(48f)); assertEquals(60f, HitTarget.box(60f))
        assertEquals(6f, HitTarget.pad(36f), "the close X: 36 dp drawn in a 48 dp box")
        // the kit's own tap targets are a finger at least
        assertTrue(EditorMenus.ROW >= HitTarget.MIN_DP)
        assertTrue(ChromeLayout.of(400f, 800f).slot >= HitTarget.MIN_DP)
    }

    @Test fun theScrollThumbGlidesAndFits() {
        assertNull(ScrollThumb.of(0, 0, 48, 10, 10), "all shown: no thumb")
        assertNull(ScrollThumb.of(0, 0, 48, 0, 0))
        val (top0, len) = ScrollThumb.of(0, 0, 48, 5, 20)!!
        assertEquals(0f, top0); assertEquals(0.25f, len)
        // half a row scrolled: half a row's step, not nothing and not a whole row
        val (half, _) = ScrollThumb.of(0, 24, 48, 5, 20)!!
        assertEquals(0.5f / 20, half, 1e-6f)
        // it moves without steps as the offset grows
        var last = -1f
        for (off in 0..47 step 4) { val t = ScrollThumb.of(3, off, 48, 5, 20)!!.first; assertTrue(t > last); last = t }
        // never past the track's end
        val (end, l2) = ScrollThumb.of(19, 47, 48, 5, 20)!!
        assertTrue(end + l2 <= 1f + 1e-6f)
    }

    @Test fun listKeysAreUniqueAndStable() {
        assertEquals(listOf("a", "b", "a#1", "a#2"), ListKeys.unique(listOf("a", "b", "a", "a")))
        assertEquals(listOf("x", "y"), ListKeys.unique(listOf("x", "y")))
        // the menus' own rows: every dropdown's keys (their actions) are unique, with and without a selection
        val her = Placement("her", PlaceKind.NPC, 0, "alleria", "Alleria", 2f, 0f, 0f, 0f, 1f)
        for (sel in listOf(null, her)) for (stages in listOf(true, false)) {
            val rows = EditorMenus.more(sel, ToolCluster(), snap = true, showHidden = false, hasStages = stages)
            assertEquals(rows.size, rows.map { it.action.name }.toSet().size, "More's rows ${rows.map { it.action }}")
        }
        val ctx = EditorMenus.context(her)
        assertEquals(ctx.size, ctx.map { it.action.name }.toSet().size)
        // the Options window's pages and rows, the title's and the Game Menu's buttons
        for (p in OptionsPage.values()) { val r = OptionsModel.rows(p); assertEquals(r.size, r.toSet().size, "$p") }
        for (f in listOf(true, false)) for (h in listOf(true, false)) { val t = Menus.title(f, h).map { it.item }; assertEquals(t.size, t.toSet().size) }
        val g = Menus.game(true).map { it.item }; assertEquals(g.size, g.toSet().size)
        assertEquals(OptionsModel.DOWNLOAD.size, OptionsModel.DOWNLOAD.map { it.first }.toSet().size)
    }
}
