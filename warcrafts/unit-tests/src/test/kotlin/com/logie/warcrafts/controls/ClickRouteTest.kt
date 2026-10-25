package com.logie.warcrafts.controls

import com.logie.warcrafts.controls.ClickRoute.Kind
import com.logie.warcrafts.controls.ClickRoute.Win
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClickRouteTest {
    private val game = Win(0x00a00005, "war3.exe", enabled = true, inputOnly = false)
    private val desktop = Win(0x00800001, "explorer.exe", enabled = false, inputOnly = false)
    private val clip = Win(0x00800003, "", enabled = false, inputOnly = true)

    @Test
    fun `nothing holding the pointer, the press goes to the game's window`() {
        assertEquals(Kind.WINDOW, ClickRoute.kind(null, programGrab = false, target = game))
        assertEquals("to 0x00a00005 \"war3.exe\"", ClickRoute.describe(null, false, game))
    }

    @Test
    fun `Wine's clipping window holding the pointer is a program's grab, whatever Winlator's mark on it`() {
        assertEquals(Kind.HELD, ClickRoute.kind(clip, programGrab = true, target = game))
        val line = ClickRoute.describe(clip, true, game)
        assertTrue("held by a program" in line, line)
        assertTrue("0x00800003 (input only)" in line, line)
    }

    @Test
    fun `a window Winlator disables drops the press`() {
        assertEquals(Kind.DROPPED, ClickRoute.kind(null, programGrab = false, target = desktop))
        assertTrue("disables" in ClickRoute.describe(null, false, desktop))
        assertEquals(Kind.DROPPED, ClickRoute.kind(desktop, programGrab = false, target = null))
    }

    @Test
    fun `no window taking presses under the pointer drops it`() {
        assertEquals(Kind.DROPPED, ClickRoute.kind(null, programGrab = false, target = null))
        assertEquals("dropped: no window there takes presses", ClickRoute.describe(null, false, null))
    }

    @Test
    fun `a second button while one is down goes where the first went`() {
        assertEquals(Kind.WINDOW, ClickRoute.kind(game, programGrab = false, target = null))
        assertEquals("to 0x00a00005 \"war3.exe\" (a button already down there)", ClickRoute.describe(game, false, null))
    }

    @Test
    fun `the run's presses counted by where they went`() {
        assertEquals("7 presses: 4 to a window, 2 to a program holding the pointer, 1 dropped", ClickRoute.summary(intArrayOf(4, 2, 1)))
        assertEquals("0 presses: 0 to a window, 0 to a program holding the pointer, 0 dropped", ClickRoute.summary(IntArray(3)))
    }
}
