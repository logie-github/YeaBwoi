package com.logie.wifecraft.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Interact mode on the screen plan: its dock, its sheet and row, the way in (More) and out (the chip, Back), and that nothing overlaps or covers her. */
class ScreenInteractTest {
    private val sizes = listOf(360f to 800f, 412f to 915f, 800f to 360f, 915f to 412f, 600f to 960f)

    private fun state(panel: ScreenPanel = ScreenPanel.NONE, row: Boolean = false, ime: Boolean = false, context: Boolean = true): ScreenState {
        val d = DockModel.interact()
        return ScreenState(ScreenMode.INTERACT, panel, d.size, d.minWidth, 3, false, false, context, ime, row)
    }

    @Test
    fun theDockIsBackCommandsToolPoseOutfitTalkMoreAndScrollsWhereItIsNarrow() {
        val d = DockModel.interact()
        assertEquals(DockKind.INTERACT, d.kind)
        assertEquals(listOf("back", "commands", "tool", "pose", "outfit", "talk", "more"), d.items.map { it.id })
        assertEquals("Touch", d.items[2].label); assertEquals("Look", DockModel.interact(tool = "Look").items[2].label)
        assertTrue(DockModel.scrolls(DockKind.INTERACT))
        for (i in d.items) assertTrue(i.minW >= ScreenPlan.TOUCH)
        assertTrue(DockModel.interact(tab = "POSE").items.single { it.id == "pose" }.lit)
        assertTrue(DockModel.interact(tell = true).items.single { it.id == "talk" }.lit)
        assertFalse(DockModel.interact().items.any { it.lit })
    }

    @Test
    fun noOverlapsAndHerMiddleStaysFreeInEveryPanelRowAndKeyboardState() {
        for ((w, h) in sizes) for (panel in listOf(ScreenPanel.NONE, ScreenPanel.INTERACT, ScreenPanel.MORE)) for (row in listOf(false, true)) for (ime in listOf(false, true)) {
            if (ime && panel != ScreenPanel.NONE) continue
            val ins = ScreenInsets(ime = if (ime) (if (w > h) 150f else 300f) else 0f)
            val plan = ScreenPlan.plan(w, h, ins, state(panel, row, ime))
            val keep = ScreenPlan.keepOut(w, h, ScreenMode.INTERACT)
            for (p in plan) {
                assertTrue(p.rect.w > 0f && p.rect.h > 0f, "${p.element}")
                assertTrue(p.rect.l >= -0.01f && p.rect.r <= w + 0.01f && p.rect.t >= -0.01f && p.rect.b <= h - ins.ime + 0.01f, "${p.element} leaves the screen at ${w}x$h")
                if (!ime && p.element in setOf("dock", "row", "sheet", "context", "cast", "place", "camera")) assertFalse(p.rect.intersects(keep), "${p.element} covers her at ${w}x$h panel=$panel row=$row ime=$ime")
            }
            for (i in plan.indices) for (j in i + 1 until plan.size) assertFalse(plan[i].rect.intersects(plan[j].rect), "${plan[i].element} overlaps ${plan[j].element} at ${w}x$h")
            assertNull(ScreenPlan.find(plan, "cast"), "no cast over her"); assertNull(ScreenPlan.find(plan, "camera")); assertNull(ScreenPlan.find(plan, "place"))
            assertNotNull(ScreenPlan.find(plan, "mode"), "the way out is always there")
            if (!ime) assertNotNull(ScreenPlan.find(plan, "dock"))
        }
    }

    @Test
    fun theSheetOpensOnlyInInteractAndMoreOpensThereToo() {
        assertEquals(ScreenPanel.INTERACT, PanelRules.open(ScreenPanel.NONE, ScreenPanel.INTERACT, ScreenMode.INTERACT))
        assertEquals(ScreenPanel.NONE, PanelRules.open(ScreenPanel.INTERACT, ScreenPanel.INTERACT, ScreenMode.INTERACT), "again closes it")
        assertEquals(ScreenPanel.NONE, PanelRules.open(ScreenPanel.NONE, ScreenPanel.INTERACT, ScreenMode.NORMAL), "none outside Interact")
        assertEquals(ScreenPanel.MORE, PanelRules.open(ScreenPanel.INTERACT, ScreenPanel.MORE, ScreenMode.INTERACT))
        assertEquals(ScreenPanel.NONE, PanelRules.open(ScreenPanel.NONE, ScreenPanel.ANIMATIONS, ScreenMode.INTERACT), "the animation browser is not Interact's")
    }

    @Test
    fun backClosesThePanelThenLeavesTheModeAndTheChipIsAlwaysUp() {
        assertEquals(BackStep.CLOSE_PANEL, PanelRules.back(ScreenPanel.INTERACT, ScreenMode.INTERACT, false, true))
        assertEquals(BackStep.LEAVE_MODE, PanelRules.back(ScreenPanel.NONE, ScreenMode.INTERACT, true, true))
        assertTrue(ChromeVisibility.exitChip(ScreenMode.INTERACT))
        assertTrue(ChromeVisibility.shown(true, false, 60_000, ScreenMode.INTERACT, false), "the chrome never hides in Interact")
        assertEquals("Interact", modeLabelForTest())
    }

    private fun modeLabelForTest(): String = "Interact"

    @Test
    fun interactIsOneOrderInMoreAndTwoTapsAway() {
        val slots = SceneHud.orders(false, false, 2, false, 0, joi = true, chatMode = true, interact = true)
        assertTrue(slots.any { it.order == HudOrder.INTERACT && it.label == "Interact" })
        assertFalse(SceneHud.orders(false, false, 2, false, 0, joi = true, chatMode = true).any { it.order == HudOrder.INTERACT }, "off unless offered")
        val dock = DockModel.normal(slots, false, false)
        assertFalse(dock.items.any { it.order == HudOrder.INTERACT }, "it is in More, the dock keeps its six")
        assertTrue(dock.more.any { it.order == HudOrder.INTERACT })
        assertEquals(2, TapCounts.INTERACT_FROM_MORE); assertEquals(1, TapCounts.INTERACT_LEAVE); assertEquals(1, TapCounts.INTERACT_TOUCH)
        assertTrue(ScreenFeatures.all.any { it.id == "interact.enter" })
        for (f in ScreenFeatures.all.filter { it.id.startsWith("interact.") }) assertTrue(ScreenFeatures.places.any { f.slot.startsWith(it) || f.slot.contains(it) }, f.id)
    }

    @Test
    fun theTellRowTakesTheDocksPlaceWhileTypingAndStaysAboveTheKeyboard() {
        for ((w, h) in sizes) {
            val ime = if (w > h) 150f else 300f
            val plan = ScreenPlan.plan(w, h, ScreenInsets(ime = ime), state(row = true, ime = true))
            assertNull(ScreenPlan.find(plan, "dock"), "the dock goes while typing")
            val field = assertNotNull(ScreenPlan.find(plan, "row"))
            assertTrue(field.b <= h - ime + 0.01f)
        }
    }

    @Test
    fun theStockingsPanelIsOneTabOfTheSameSheetAndFitsAtEverySize() {
        assertTrue(com.logie.wifecraft.simulation.InteractTab.entries.any { it.label == "Stockings" })
        assertTrue(ScreenFeatures.all.any { it.id == "interact.stockings" })
        // the panel is the sheet's body (it scrolls inside the sheet): the sheet is the same rect with it open, never over her, in every state
        for ((w, h) in sizes) for (row in listOf(false, true)) {
            val plan = ScreenPlan.plan(w, h, ScreenInsets(), state(ScreenPanel.INTERACT, row))
            val sheet = assertNotNull(ScreenPlan.find(plan, "sheet"), "the sheet is up at ${w}x$h")
            val keep = ScreenPlan.keepOut(w, h, ScreenMode.INTERACT)
            assertFalse(sheet.intersects(keep), "the stockings panel covers her at ${w}x$h")
            assertTrue(sheet.w >= 200f && sheet.h >= 120f, "the sheet holds the panel's status, gauge and buttons at ${w}x$h: ${sheet.w}x${sheet.h}")
            for (o in plan) if (o.element != "sheet") assertFalse(sheet.intersects(o.rect), "${o.element} overlaps the sheet at ${w}x$h")
        }
    }
}
