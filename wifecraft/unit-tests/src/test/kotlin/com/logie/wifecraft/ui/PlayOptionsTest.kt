package com.logie.wifecraft.ui

import kotlin.test.*

class PlayOptionsTest {
    @Test
    fun keptPerSceneAndReadBackFieldByField() {
        val o = PlayOptions(PlayRepeat.TIMES, 5, repeatQueue = true, shuffle = false)
        assertEquals(o, PlayOptions.decode(o.encode()))
        assertEquals(PlayOptions(), PlayOptions.decode(null))
        assertEquals(PlayOptions(), PlayOptions.decode("garbage"))
        assertEquals(PlayOptions(PlayRepeat.UNTIL_STOPPED), PlayOptions.decode("UNTIL_STOPPED"), "an older, shorter form")
        assertEquals(PlayOptions.MAX_TIMES, PlayOptions.decode("TIMES;999;0;0").times)
        // the same place and people in any order is the same scene; another cast is another
        assertEquals(PlayOptions.sceneKey("Dalaran", listOf("b", "a")), PlayOptions.sceneKey("Dalaran", listOf("a", "b")))
        assertNotEquals(PlayOptions.sceneKey("Dalaran", listOf("a")), PlayOptions.sceneKey("Dalaran", listOf("a", "b")))
    }

    @Test
    fun loopsAreTheAuthorsOnceAndTheCountForRepeatN() {
        assertEquals(2, PlayOptions().loops(2), "once: the author's own loops")
        assertEquals(1, PlayOptions().loops(0))
        assertEquals(4, PlayOptions(PlayRepeat.TIMES, 4).loops(2))
        assertEquals(PlayOptions.MIN_TIMES, PlayOptions(PlayRepeat.TIMES).withTimes(0).times)
        assertTrue(PlayOptions(PlayRepeat.UNTIL_STOPPED).untilStopped)
        assertEquals(listOf("Once", "Repeat ×3", "Until stopped"), PlayRepeat.entries.map { PlayOptions().label(it) })
    }

    @Test
    fun theBarOffersStopAndSkipWhilePlaying() {
        val idle = SceneHud.orders(false, false, 1, repeating = false, queued = 0).map { it.order }
        assertFalse(HudOrder.STOP in idle || HudOrder.SKIP in idle)
        val playing = SceneHud.orders(false, false, 1, repeating = true, queued = 2, performing = true).map { it.order }
        val stop = playing.indexOf(HudOrder.STOP)
        assertTrue(stop >= 0 && stop < playing.indexOf(HudOrder.STOP_REPEAT) && playing.indexOf(HudOrder.STOP_REPEAT) <
            playing.indexOf(HudOrder.SKIP) && playing.indexOf(HudOrder.SKIP) < playing.indexOf(HudOrder.CLEAR_QUEUE), "$playing")
    }
}
