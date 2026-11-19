package com.logie.wifecraft.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThrottleTest {
    private var now = 0L
    private fun sampler(urgent: (Int?, Int) -> Boolean = { _, _ -> false }) = Sampler(250, { now }, urgent)

    @Test fun firstGoesAtOnceAndTheRestAreHeld() {
        val s = sampler()
        assertEquals(1, s.offer(1))
        now = 10; assertNull(s.offer(2))
        now = 20; assertNull(s.offer(3))
        assertEquals(230L, s.dueIn())
        now = 100; assertNull(s.take())   // not due yet
        now = 250; assertEquals(3, s.take())   // only the latest held
        assertNull(s.dueIn())
        assertNull(s.take())
    }

    @Test fun afterTheIntervalAValueGoesAtOnce() {
        val s = sampler()
        s.offer(1)
        now = 260; assertEquals(2, s.offer(2))
        now = 300; assertNull(s.offer(3))
        now = 600; assertEquals(4, s.offer(4))   // the held 3 is superseded, never delivered after 4
        assertNull(s.flush())
    }

    @Test fun urgentAlwaysGoesAndTheLastIsNeverLost() {
        val s = sampler { _, next -> next < 0 }   // negative: finished / failed
        s.offer(1)
        now = 5; assertNull(s.offer(2))
        now = 6; assertEquals(-1, s.offer(-1))
        assertNull(s.dueIn())                   // nothing held: the urgent one carried the latest
        now = 7; assertNull(s.offer(9))
        assertEquals(9, s.flush())              // the stream ended: the held one now, whatever the interval
    }

    @Test fun urgentComparesWithWhatWasDelivered() {
        val s = Sampler<String>(250, { now }) { prev, next -> prev?.first() != next.first() }
        assertEquals("a1", s.offer("a1"))
        now = 1; assertNull(s.offer("a2"))
        now = 2; assertEquals("b1", s.offer("b1"))   // a new pack: at once
    }

    @Test fun valuelessThrottle() {
        val t = Throttle(250) { now }
        assertTrue(t.offer())
        now = 1; assertTrue(!t.offer())
        assertTrue(t.offer(urgent = true))
        now = 2; assertTrue(!t.offer())
        now = 300; assertTrue(t.take())
        assertTrue(!t.flush())
    }

    /** 96 downloads at once, each file a tick, for 10 s of fake time: at most 4 a second reach the screen, the last one included. */
    @Test fun aGatheringsTicksAreCutToFourASecond() {
        val s = sampler()
        val shown = mutableListOf<Int>()
        var tick = 0
        // a file every ~1 ms on average (96 in flight, each about a second)
        for (ms in 0L until 10_000L) {
            now = ms
            s.take()?.let { shown += it }   // the timer firing
            if (ms % 1 == 0L) s.offer(++tick)?.let { shown += it }
        }
        s.flush()?.let { shown += it }
        assertTrue(shown.size in 39..41, "shown ${shown.size}")
        assertEquals(tick, shown.last())
        assertEquals(shown.sorted(), shown)
    }

    /** A desktop measurement: what one offer costs (the download's threads call it for every file). */
    @Test fun measureOfferCost() {
        val s = Sampler<Int>(250, { System.nanoTime() / 1_000_000 })
        repeat(200_000) { s.offer(it) }   // warm-up
        val n = 2_000_000
        val t0 = System.nanoTime()
        for (i in 0 until n) s.offer(i)
        val ns = (System.nanoTime() - t0).toDouble() / n
        println("Sampler.offer: %.1f ns per call".format(ns))
        assertTrue(ns < 5_000, "offer took $ns ns")
    }
}
