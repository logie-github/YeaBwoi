package com.logie.wifecraft.ui

import com.logie.wifecraft.simulation.CameraMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneHudTest {
    @Test
    fun ordersStartWithTheEverydayOnesAndPartnerOnlyWithAPartner() {
        val alone = SceneHud.orders(walking = false, partner = false, others = 0, repeating = false, queued = 0)
        assertEquals(listOf(HudOrder.WALK, HudOrder.PLAY, HudOrder.SAY, HudOrder.RELEASE, HudOrder.FREE_WILL), alone.map { it.order })
        val paired = SceneHud.orders(walking = true, partner = true, others = 2, repeating = false, queued = 0)
        assertEquals(listOf(HudOrder.WALK, HudOrder.PLAY, HudOrder.PARTNER, HudOrder.SAY, HudOrder.CHAT, HudOrder.RELEASE),
            SceneHud.page(paired, 0).map { it.order })
        assertTrue(paired.first().lit)
    }

    @Test
    fun noPageHoldsMoreThanSixAndPagesWrapBack() {
        val all = SceneHud.orders(false, true, 3, repeating = true, queued = 2,
            uses = listOf("CHAIR" to "Sit in chair", "COUCH" to "Sit on couch", "BED" to "Rest on bed", "TABLE" to "Read a book"))
        assertEquals(13, all.size)
        assertEquals(3, SceneHud.pages(all.size))
        for (p in 0 until 3) assertTrue(SceneHud.page(all, p).size <= SceneHud.SLOTS)
        assertEquals(all, (0 until 3).flatMap { SceneHud.page(all, it) })
        assertEquals("Clear queue (2)", all.first { it.order == HudOrder.CLEAR_QUEUE }.label)
        // a shorter bar under a later page: the last page there is
        assertEquals(0, SceneHud.clampPage(2, 5))
        assertEquals(1, SceneHud.pages(0))
        // every slot's key differs (the object slots by their target)
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test
    fun theFoldsScreensGetSixInARowOrTwoRowsOfThreeAndNothingWiderThanTheScreen() {
        for ((w, h) in listOf(411f to 960f, 960f to 411f, 832f to 750f, 750f to 832f, 360f to 640f)) {
            val l = SceneHud.layout(w, h)
            assertTrue(l.barW <= w - 2 * SceneHud.MARGIN + 0.01f, "bar fits $w")
            assertEquals(SceneHud.SLOTS, l.cols * l.rows)
            assertTrue(l.slotW >= 88f, "slot wide enough to read at $w: ${l.slotW}")
            assertEquals(l.rows * SceneHud.SLOT_H + (l.rows - 1) * SceneHud.GAP, l.barH)
        }
        assertEquals(3, SceneHud.layout(411f, 960f).cols)
        assertFalse(SceneHud.layout(411f, 960f).castSide)
        assertEquals(6, SceneHud.layout(960f, 411f).cols)
        assertTrue(SceneHud.layout(832f, 750f).castSide)
    }

    @Test
    fun theHintSaysWhatATapDoesNow() {
        assertEquals("Tap an NPC to direct them.", SceneHud.hint(false, false, 3, false))
        assertEquals("Tap another NPC to make them the partner.", SceneHud.hint(true, false, 2, false))
        assertEquals("Tap the floor to walk there.", SceneHud.hint(true, true, 2, true))
        assertEquals("", SceneHud.hint(true, false, 1, false))
    }

    @Test
    fun theClockReadsAsTheGames() {
        assertEquals("12:05 AM", SceneHud.clock(0, 5, false))
        assertEquals("9:30 PM", SceneHud.clock(21, 30, false))
        assertEquals("12:00 PM", SceneHud.clock(12, 0, false))
        assertEquals("07:04", SceneHud.clock(7, 4, true))
    }

    @Test
    fun partnerTabComesFirstWithAPartnerAndOnlyWhenThereArePairs() {
        val groups = listOf("Dances", "Partner", "Sitting", "Dances")
        assertEquals(listOf("Partner", "All", "Dances", "Sitting"), SceneHud.animationTabs(groups, hasPairs = true, partnerFirst = true))
        assertEquals(listOf("All", "Dances", "Sitting", "Partner"), SceneHud.animationTabs(groups, hasPairs = true, partnerFirst = false))
        assertEquals(listOf("All", "Dances", "Sitting"), SceneHud.animationTabs(groups, hasPairs = false, partnerFirst = true))
        assertTrue(SceneHud.inTab("Partner", "Partner dances", 2))
        assertFalse(SceneHud.inTab("Partner", "Dances", 1))
        assertTrue(SceneHud.inTab("Dances", "Dances", 1))
        assertTrue(SceneHud.inTab("All", "x", 1))
    }

    @Test
    fun theCameraControlsNameTheModeAndKeepTheirPlace() {
        assertEquals("Room view", SceneHud.cameraLabel(CameraMode.ROOM))
        assertEquals("Camera: Orbit. Tap for Follow.", SceneHud.cameraDescription(CameraMode.ORBIT, canFollow = true))
        assertEquals("Camera: Orbit. Tap for Room view.", SceneHud.cameraDescription(CameraMode.ORBIT, canFollow = false))
        // a drag on the room's view starts the orbit; an orbit or Follow is kept
        assertEquals(CameraMode.ORBIT, SceneHud.cameraForTouch(CameraMode.ROOM))
        assertEquals(CameraMode.FOLLOW, SceneHud.cameraForTouch(CameraMode.FOLLOW))
        // letting the NPC go turns Follow into the orbit; selecting changes nothing
        assertEquals(CameraMode.ORBIT, SceneHud.cameraAfterRelease(CameraMode.FOLLOW, selected = false))
        assertEquals(CameraMode.FOLLOW, SceneHud.cameraAfterRelease(CameraMode.FOLLOW, selected = true))
        assertEquals(CameraMode.ROOM, SceneHud.cameraAfterRelease(CameraMode.ROOM, selected = false))
        // 48 dp targets, under the top bar (and the cast strip on a narrow screen), clear of the action bar
        assertEquals(2 * 48f + SceneHud.CAMERA_GAP, SceneHud.CAMERA_W)
        for ((w, h) in listOf(360f to 780f, 780f to 360f, 900f to 700f)) {
            val layout = SceneHud.layout(w, h)
            val top = SceneHud.cameraTop(layout)
            assertTrue(top >= SceneHud.TOP_H + (if (layout.castSide) 0f else layout.castH), "$w x $h: $top")
            val bottom = top + 3 * SceneHud.SLOT_H + 2 * SceneHud.CAMERA_GAP
            val barTop = h - SceneHud.MARGIN - layout.barH - 6f - 48f
            assertTrue(bottom <= barTop, "$w x $h: the controls end at $bottom, the bar's line starts at $barTop")
        }
    }
}
