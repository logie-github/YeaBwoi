package com.logie.warcraft.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerLogTest {
    private val ready = Regex("\\(worldserver-daemon\\) ready")
    private val esc = "\u001B"

    private val readyLine = "$esc[0m$esc[36mAzerothCore rev. b5a7de6c15f6 2026-10-11 23:07:45 +0000 (HEAD branch) " +
        "(Unix, Release, Static) (worldserver-daemon) ready...\n"

    @Test
    fun colourCodesAreRemoved() {
        assertEquals("WORLD: World Initialized In 0 Minutes 24 Seconds", ConsoleText.plain("$esc[0m$esc[36mWORLD: World Initialized In 0 Minutes 24 Seconds"))
        assertEquals("AC>", ConsoleText.plain("$esc[0mAC>"))
        assertEquals("no codes here", ConsoleText.plain("no codes here"))
    }

    @Test
    fun readyLineFollowedByOthersInOnePieceIsFound() {
        // As on the phone: the ready line and twenty more in the same second.
        val after = (1..20).joinToString("") { "$esc[0m$esc[36m>>TEMPLATE NPC: Loaded line $it\n" } + "$esc[0mAC>"
        val watch = LineWatch(ready)
        assertFalse(watch.feed("$esc[0m$esc[36mWORLD: World Initialized In 0 Minutes 24 Seconds\n"))
        assertTrue(watch.feed(readyLine + after))
        assertTrue(watch.found)
    }

    @Test
    fun readyLineSplitAcrossPiecesIsFound() {
        val watch = LineWatch(ready)
        val cut = readyLine.indexOf("daemon")
        assertFalse(watch.feed("Loading Template Talents...\n" + readyLine.substring(0, cut)))
        assertTrue(watch.feed(readyLine.substring(cut) + "Calendar deletion of old events.\n"))
    }

    @Test
    fun otherLinesAreNotReady() {
        val watch = LineWatch(ready)
        assertFalse(watch.feed("$esc[0m$esc[36mLoading Creature Data...\n>> Loaded 30000 creatures\n"))
        assertFalse(watch.feed(""))
        assertFalse(watch.found)
    }
}
