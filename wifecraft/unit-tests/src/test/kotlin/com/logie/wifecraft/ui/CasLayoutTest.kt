package com.logie.wifecraft.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Create-a-Character shell's layout (CasLayout) on both Z Fold screens and a phone, and its camera moved sideways. */
class CasLayoutTest {
    private fun inside(b: CasBox, w: Float, h: Float) = b.x >= 0f && b.y >= 0f && b.x + b.w <= w + 0.01f && b.y + b.h <= h + 0.01f
    private fun overlap(a: CasBox, b: CasBox) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    // dp sizes: a phone held sideways (2400 x 1080 at 2.6), it held up, a Z Fold's cover and inner screens
    private val screens = listOf(923f to 415f, 415f to 923f, 412f to 916f, 916f to 412f, 841f to 734f, 734f to 841f, 1280f to 800f)

    @Test fun everythingFitsAndNothingOverlaps() {
        for ((w, h) in screens) for (outfit in listOf(false, true)) {
            val l = CasLayout.of(w, h, outfit)
            val all = listOf(l.top, l.rail, l.stage, l.panel, l.cameras)
            for (b in all) assertTrue(inside(b, w, h), "$w x $h $outfit: $b")
            assertTrue(!overlap(l.top, l.rail) && !overlap(l.top, l.stage) && !overlap(l.rail, l.stage), "$w x $h")
            if (!outfit) {
                assertTrue(!overlap(l.panel, l.stage) && !overlap(l.panel, l.rail) && !overlap(l.panel, l.top), "$w x $h panel")
                assertTrue(l.panel.w >= 300f || w < h, "$w x $h: the panel is wide enough for a row")
            }
            // 48 dp to touch: the top bar's height, the rail's eight tabs, the camera buttons
            assertTrue(l.top.h >= 48f)
            val tabSpan = if (w >= h) l.rail.h / 8f else l.rail.w / 8f
            assertTrue(tabSpan >= 44f, "$w x $h: a tab ${tabSpan}dp")
            assertTrue(l.stage.w > 150f && l.stage.h > 150f, "$w x $h: room for her")
        }
    }

    @Test fun theLayoutDependsOnTheScreenAlone() {
        // no jumps: the same screen always gives the same boxes, the stage only widening on the Outfit tab
        val a = CasLayout.of(923f, 415f, false); val b = CasLayout.of(923f, 415f, false)
        assertEquals(a, b)
        val o = CasLayout.of(923f, 415f, true)
        assertEquals(a.rail, o.rail); assertEquals(a.top, o.top)
        assertTrue(o.stage.w > a.stage.w)
    }

    @Test fun theCameraStandsHerWhereAsked() {
        for (centre in listOf(0.3f, 0.5f, 0.62f)) for (aspect in listOf(2.22f, 0.45f)) {
            val p = CasCameraLayout.pose(1.4f, 0.6f, centre, aspect)
            assertEquals(centre, CasCameraLayout.screenX(p, aspect), 1e-4f)
            val plain = CreatorCamera.pose(1.4f, 0.6f)
            assertEquals(plain.fov, p.fov); assertEquals(plain.eye.z, p.eye.z); assertEquals(plain.at.x, p.at.x)
        }
        assertEquals(0f, CasCameraLayout.pose(1f, 0.6f, 0.5f, 2f).eye.y, 1e-6f)
    }
}
