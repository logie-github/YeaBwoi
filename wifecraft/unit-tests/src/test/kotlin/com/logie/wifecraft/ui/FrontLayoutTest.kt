package com.logie.wifecraft.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontLayoutTest {
    @Test fun titleFitsTheFoldsScreens() {
        val inner = FrontLayout.title(832f, 750f)
        assertFalse(inner.centered); assertFalse(inner.compact)
        val coverStanding = FrontLayout.title(411f, 960f)
        assertTrue(coverStanding.centered)
        val coverLying = FrontLayout.title(960f, 411f)
        assertTrue(coverLying.compact); assertFalse(coverLying.centered)
        for (l in listOf(inner, coverStanding, coverLying, FrontLayout.title(320f, 480f))) {
            assertTrue(l.buttonW <= FrontLayout.BUTTON_MAX)
            assertTrue(l.buttonW >= 0f)
        }
        assertTrue(coverStanding.buttonW + 2 * coverStanding.padH <= 411f)
        assertEquals(0f, FrontLayout.title(10f, 10f).buttonW)
    }

    @Test fun narrowRowsStackAndManyTabsScroll() {
        assertTrue(FrontLayout.sliderStacked(300f)); assertFalse(FrontLayout.sliderStacked(500f))
        assertFalse(FrontLayout.tabsScroll(600f, 3)); assertTrue(FrontLayout.tabsScroll(250f, 3)); assertFalse(FrontLayout.tabsScroll(100f, 0))
    }

    @Test fun backdropDriftIsSmoothBoundedAndStillWhenReduced() {
        assertEquals(Triple(1f, 0f, 0f), FrontMotion.drift(0.37f, reduced = true))
        val start = FrontMotion.drift(0f, false); val end = FrontMotion.drift(1f, false)
        assertEquals(start.first, end.first, 1e-5f)                // loops without a jump
        for (i in 0..100) {
            val (s, x, y) = FrontMotion.drift(i / 100f, false)
            assertTrue(s in 1f..1f + FrontMotion.DRIFT_SCALE + 1e-5f)
            assertTrue(kotlin.math.abs(x) <= FrontMotion.DRIFT_PAN + 1e-5f && kotlin.math.abs(y) <= FrontMotion.DRIFT_PAN)
        }
    }

    @Test fun motesStayOnScreenAndAreStable() {
        for (i in 0 until FrontMotion.MOTES) for (p in 0..20) {
            val (x, y, a) = FrontMotion.mote(i, p / 20f)
            assertTrue(x in 0f..1f, "x $x"); assertTrue(y in -0.1f..1.1f, "y $y"); assertTrue(a in 0f..1f)
        }
        assertEquals(FrontMotion.mote(3, 0.4f), FrontMotion.mote(3, 0.4f))
    }
}
