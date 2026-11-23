package com.logie.wifecraft.ui

import com.logie.wifecraft.dialogue.Approach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dock modes the plan holds (Chat, JOI, persuasion), the row over the dock, the keyboard, and the taps as asserted constants. */
class ScreenModesTest {
    private val sizes = listOf(360f to 800f, 412f to 915f, 800f to 360f, 915f to 412f, 600f to 960f)

    private fun chatState(row: Boolean, ime: Boolean, context: Boolean = true, panel: ScreenPanel = ScreenPanel.NONE): ScreenState {
        val d = DockModel.chat()
        return ScreenState(ScreenMode.CHAT, panel, d.size, d.minWidth, 0, false, false, context, ime, row)
    }

    @Test
    fun chatsDockIsTheFieldSendAndLeaveAndFitsA360DpPhone() {
        val d = DockModel.chat()
        assertEquals(DockKind.CHAT, d.kind)
        assertEquals(listOf("field", "send", "leave"), d.items.map { it.id })
        assertTrue(d.minWidth <= 328f, "needs ${d.minWidth}")
        assertTrue(d.items.first().minW >= 160f)
    }

    @Test
    fun chatPutsTheFieldTheRowAndTheLastLineInTheSlotsAndNothingElseOnTheScreen() {
        for ((w, h) in sizes) {
            val plan = ScreenPlan.plan(w, h, ScreenInsets(), chatState(row = true, ime = false))
            val dock = assertNotNull(ScreenPlan.find(plan, "dock"), "dock ${w}x$h")
            val row = assertNotNull(ScreenPlan.find(plan, "row"), "row ${w}x$h")
            val line = assertNotNull(ScreenPlan.find(plan, "context"), "last line ${w}x$h")
            assertTrue(row.b <= dock.t && line.b <= row.t, "stack order at ${w}x$h")
            assertTrue(dock.w >= DockModel.chat().minWidth)
            assertNull(ScreenPlan.find(plan, "cast")); assertNull(ScreenPlan.find(plan, "camera")); assertNull(ScreenPlan.find(plan, "place"))
            assertNotNull(ScreenPlan.find(plan, "mode"), "the way out")
            // the camera frames her above the stack: it covers a minority of the height, below the share the camera accepts (0.6)
            val share = ScreenPlan.bottomShare(plan, h)
            assertTrue(share in 0.1f..0.6f, "the stack covers $share of ${w}x$h")
        }
    }

    @Test
    fun withTheKeyboardUpTheFieldStaysAboveItAndLandscapeDropsTheChipsNotTheField() {
        for ((w, h) in sizes) {
            val ime = if (w > h) 150f else 300f
            val ins = ScreenInsets(ime = ime)
            val plan = ScreenPlan.plan(w, h, ins, chatState(row = true, ime = true))
            val dock = assertNotNull(ScreenPlan.find(plan, "dock"), "the field is gone at ${w}x$h")
            assertTrue(dock.b <= h - ime, "the field ${dock.b} is under the keyboard at ${h - ime}")
            assertNull(ScreenPlan.find(plan, "context"), "no line while typing")
            if (w > h) assertNull(ScreenPlan.find(plan, "row"), "landscape has no room for the chips with the keyboard up")
            else assertNotNull(ScreenPlan.find(plan, "row"))
        }
    }

    @Test
    fun theLogIsASheetThatNeverCoversTheStackAndTheSceneKeepsItsFaceZone() {
        for ((w, h) in sizes) {
            val plan = ScreenPlan.plan(w, h, ScreenInsets(), chatState(row = true, ime = false, panel = ScreenPanel.CHAT_LOG))
            val sheet = assertNotNull(ScreenPlan.find(plan, "sheet"), "log sheet at ${w}x$h")
            val line = ScreenPlan.find(plan, "context")!!
            assertFalse(sheet.intersects(ScreenPlan.keepOut(w, h, ScreenMode.CHAT)), "the log covers her at ${w}x$h")
            if (w > h) assertTrue(sheet.l >= 0.55f * w) else assertTrue(sheet.b <= line.t)
        }
        assertEquals(ScreenPanel.CHAT_LOG, PanelRules.open(ScreenPanel.NONE, ScreenPanel.CHAT_LOG, ScreenMode.CHAT))
        assertEquals(ScreenPanel.NONE, PanelRules.open(ScreenPanel.CHAT_LOG, ScreenPanel.CHAT_LOG, ScreenMode.CHAT), "Log again closes it")
        assertEquals(ScreenPanel.NONE, PanelRules.open(ScreenPanel.NONE, ScreenPanel.CHAT_LOG, ScreenMode.NORMAL), "no log outside Chat")
    }

    @Test
    fun joisDockHoldsSixControlsAndFiveForACreature() {
        val d = DockModel.joi()
        assertEquals(listOf("stop", "pause", "skip", "level", "frame", "steer"), d.items.map { it.id })
        assertTrue(d.size <= DockModel.MAX && d.minWidth <= 328f, "needs ${d.minWidth}")
        assertEquals("Resume", DockModel.joi(paused = true).items[1].label)
        assertEquals("Torso", DockModel.joi(framing = "Torso").items[4].label)
        assertEquals(listOf("stop", "pause", "skip", "level", "steer"), DockModel.joi(creature = true).items.map { it.id }, "a creature has no framing")
        assertTrue(DockModel.joi(row = DockRowKind.JOI_LEVEL).items.single { it.id == "level" }.lit)
        assertTrue(DockModel.joi(row = DockRowKind.JOI_STEER).items.single { it.id == "steer" }.lit)
        assertFalse(DockModel.joi(row = DockRowKind.JOI_STEER).items.single { it.id == "level" }.lit)
    }

    @Test
    fun joisRowSitsAboveTheDockAndTakesItsPlaceWhileTyping() {
        val d = DockModel.joi()
        val s = ScreenState(ScreenMode.JOI, ScreenPanel.NONE, d.size, d.minWidth, 0, false, false, false, false, true)
        for ((w, h) in sizes) {
            val plan = ScreenPlan.plan(w, h, ScreenInsets(), s)
            val dock = assertNotNull(ScreenPlan.find(plan, "dock")); val row = assertNotNull(ScreenPlan.find(plan, "row"))
            assertTrue(row.b <= dock.t, "row over the dock at ${w}x$h")
            assertFalse(row.intersects(ScreenPlan.keepOut(w, h, ScreenMode.JOI)), "the row covers her at ${w}x$h")
            val ime = if (w > h) 150f else 300f
            val typing = ScreenPlan.plan(w, h, ScreenInsets(ime = ime), s.copy(ime = true))
            assertNull(ScreenPlan.find(typing, "dock"), "the dock goes while the steer is typed")
            val field = assertNotNull(ScreenPlan.find(typing, "row"), "the steer field is gone at ${w}x$h")
            assertTrue(field.b <= h - ime, "the steer field is under the keyboard")
        }
    }

    @Test
    fun theFilmFrameKeepsRoomForTheDockUnderTheSubtitles() {
        // the dock's top, from the bottom of the screen: the subtitle must end above it (JoiLayer lifts it by this much)
        for ((w, h) in sizes) {
            val d = DockModel.joi()
            val plan = ScreenPlan.plan(w, h, ScreenInsets(), ScreenState(ScreenMode.JOI, dockItems = d.size, dockWidth = d.minWidth))
            val dock = ScreenPlan.find(plan, "dock")!!
            assertTrue(h - dock.t <= 16f + ScreenPlan.TOUCH + 0.01f)
        }
    }

    @Test
    fun persuasionIsADockThatScrollsWithOneChipPerApproachAndLater() {
        val d = DockModel.persuade(Approach.entries.map { it.label })
        assertEquals(DockKind.PERSUADE, d.kind)
        assertEquals(Approach.entries.size + 1, d.size)
        assertEquals("Later", d.items.last().label)
        assertTrue(DockModel.scrolls(DockKind.PERSUADE) && DockModel.scrolls(DockKind.BUILD))
        assertFalse(DockModel.scrolls(DockKind.NORMAL) || DockModel.scrolls(DockKind.JOI) || DockModel.scrolls(DockKind.CHAT))
        for (i in d.items) assertTrue(i.minW >= ScreenPlan.TOUCH)
        for ((w, h) in sizes) {
            val plan = ScreenPlan.plan(w, h, ScreenInsets(), ScreenState(ScreenMode.NORMAL, dockItems = d.size, dockWidth = d.minWidth, castCount = 2, context = true))
            assertNotNull(ScreenPlan.find(plan, "dock")); assertNotNull(ScreenPlan.find(plan, "context"))
        }
    }

    @Test
    fun everyControlOfEachModeIsInTheRegistryAndTheDock() {
        val ids = ScreenFeatures.all.map { it.id }.toSet()
        for (id in listOf("joi.stop", "joi.pause", "joi.skip", "joi.framing", "joi.level", "joi.steer.chips", "joi.steer.text",
            "chat.leave", "chat.field", "chat.send", "chat.zoom", "chat.relationship", "chat.length", "chat.topics", "chat.log", "chat.setup",
            "persuade.chips", "persuade.later", "tip.firstrun", "options.scene.pair")) assertTrue(id in ids, "$id is not in the registry")
        val joi = DockModel.joi().items.map { it.id }.toSet()
        for (c in listOf("stop", "pause", "skip", "level", "frame", "steer")) assertTrue(c in joi, "JOI's $c is not in its dock")
        // each JOI and Chat feature names the dock or the row as its place
        for (f in ScreenFeatures.all.filter { it.id.startsWith("joi.") || it.id.startsWith("chat.") }) assertTrue("dock" in f.slot || "row" in f.slot || "SHEET" in f.slot || "mode" in f.slot || "window" in f.slot, "${f.id}: ${f.slot}")
    }

    @Test
    fun tapCountsAreConstantsTheRegistryAgreesWith() {
        fun taps(id: String) = ScreenFeatures.all.single { it.id == id }.taps
        assertEquals(TapCounts.CHAT_FIRST_NEW, taps("chat.setup"))
        assertEquals(TapCounts.CHAT_SEND, taps("chat.send"))
        assertEquals(TapCounts.CHAT_LEAVE, taps("chat.leave"))
        assertEquals(TapCounts.CHAT_TOPIC, taps("chat.topics"))
        assertEquals(TapCounts.CHAT_ZOOM, taps("chat.zoom"))
        assertEquals(TapCounts.JOI_STOP, taps("joi.stop"))
        assertEquals(TapCounts.JOI_PAUSE, taps("joi.pause"))
        assertEquals(TapCounts.JOI_SKIP, taps("joi.skip"))
        assertEquals(TapCounts.JOI_FRAMING, taps("joi.framing"))
        assertEquals(TapCounts.JOI_LEVEL, taps("joi.level"))
        assertEquals(TapCounts.JOI_STEER_TEXT, taps("joi.steer.text"))
        assertEquals(TapCounts.PERSUADE, taps("persuade.chips"))
        assertEquals(TapCounts.BUILD_FROM_MENU, taps("menu.build"))
        assertEquals(TapCounts.PAIR_CONFIRM_SWITCH_PLACES, taps("options.scene.pair"))
        assertTrue(TapCounts.CHAT_FIRST_REMEMBERED <= 2 && TapCounts.BUILD_FROM_MORE <= 2, "the user's rule: three taps is too many")
        for (f in ScreenFeatures.all.filter { it.frequent }) assertTrue(f.taps <= TapCounts.MAX_FREQUENT + 1, "${f.id}: ${f.taps}")
    }
}
