package com.logie.wifecraft.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageCacheTest {
    /** A fake picture: its key and its size in bytes. */
    private data class Pic(val key: Int, val bytes: Long)

    private val steps = ArrayDeque<Runnable>()
    private val decoded = mutableListOf<Int>()

    private fun cache(maxBytes: Long = 1000, maxQueued: Int = 64, fail: Set<Int> = emptySet()) =
        ImageCache<Int, Pic>(maxBytes, { it.bytes }, { steps.addLast(it) }, { k ->
            decoded += k
            if (k in fail) null else Pic(k, 100)
        }, maxQueued)

    private fun runAll() { while (steps.isNotEmpty()) steps.removeFirst().run() }

    @Test fun peekNeverDecodes() {
        val c = cache()
        assertNull(c.peek(1))
        assertTrue(steps.isEmpty())
        assertTrue(decoded.isEmpty())
    }

    @Test fun aCachedPictureIsGivenAtOnce() {
        val c = cache()
        var got: Pic? = null
        c.request(1) { got = it }
        assertNull(got)
        runAll()
        assertEquals(Pic(1, 100), got)
        var again: Pic? = null
        c.request(1) { again = it }
        assertEquals(Pic(1, 100), again)   // synchronously: no step queued
        assertTrue(steps.isEmpty())
        assertEquals(listOf(1), decoded)
    }

    @Test fun theSameKeyIsDecodedOnceForEveryone() {
        val c = cache()
        val told = mutableListOf<String>()
        c.request(7) { told += "a" }
        c.request(7) { told += "b" }
        runAll()
        assertEquals(listOf(7), decoded)
        assertEquals(listOf("a", "b"), told)
    }

    @Test fun theNewestIsDecodedFirst() {
        val c = cache()
        for (k in 1..5) c.request(k) { }
        runAll()
        assertEquals(listOf(5, 4, 3, 2, 1), decoded)
    }

    @Test fun aCancelledRequestIsNeverDecoded() {
        val c = cache()
        val t1 = c.request(1) { }
        var told2 = false
        c.request(2) { told2 = true }
        t1.cancel()
        runAll()
        assertEquals(listOf(2), decoded)
        assertTrue(told2)
        assertNull(c.peek(1))
    }

    @Test fun cancellingOneOfTwoWaitersStillDecodes() {
        val c = cache()
        val t = c.request(3) { error("cancelled: never told") }
        var other: Pic? = null
        c.request(3) { other = it }
        t.cancel()
        runAll()
        assertEquals(listOf(3), decoded)
        assertEquals(3, other?.key)
    }

    /** A fast scroll past 100 cells that are cancelled as they leave: only those still on screen are decoded. */
    @Test fun aFastScrollDecodesOnlyWhatIsInSight() {
        val c = cache(maxBytes = 1_000_000)
        val tickets = ArrayDeque<ImageCache.Ticket>()
        for (k in 1..100) {
            tickets.addLast(c.request(k) { })
            if (tickets.size > 12) tickets.removeFirst().cancel()   // 12 cells in sight
        }
        runAll()
        assertEquals((100 downTo 89).toList(), decoded)
    }

    @Test fun theQueueIsBounded() {
        val c = cache(maxBytes = 1_000_000, maxQueued = 4)
        for (k in 1..10) c.request(k) { }
        assertEquals(4, c.queued())
        runAll()
        assertEquals(listOf(10, 9, 8, 7), decoded)
    }

    @Test fun theLeastRecentlyUsedGoFirstPastTheByteLimit() {
        val c = cache(maxBytes = 300)
        for (k in 1..3) { c.request(k) { }; runAll() }
        c.peek(1)                       // 1 used again: 2 is now the oldest
        c.request(4) { }; runAll()
        assertEquals(300, c.sizeBytes())
        assertNull(c.peek(2))
        assertTrue(c.peek(1) != null && c.peek(3) != null && c.peek(4) != null)
    }

    @Test fun aFailedDecodeTellsNullAndIsTriedAgainNextTime() {
        val c = cache(fail = setOf(9))
        var told = false
        var got: Pic? = Pic(0, 0)
        c.request(9) { told = true; got = it }
        runAll()
        assertTrue(told)
        assertNull(got)
        c.request(9) { }
        runAll()
        assertEquals(listOf(9, 9), decoded)
    }

    /** Two real threads, many cells asking for few pictures at once: each decoded once, everyone told. */
    @Test fun underTwoThreads() {
        val pool = Executors.newFixedThreadPool(2)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val c = ImageCache<Int, Pic>(1_000_000, { it.bytes }, { pool.execute(it) }, { k ->
            count.incrementAndGet(); Thread.sleep(2); Pic(k, 100)
        })
        val asks = 400
        val done = CountDownLatch(asks)
        repeat(asks) { i -> c.request(i % 20) { done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(20, count.get())
    }

    /** A desktop measurement: a peek (what every visible cell does each frame it composes) and a cached request. */
    @Test fun measurePeek() {
        val c = cache(maxBytes = 1_000_000)
        for (k in 0 until 500) { c.request(k) { }; runAll() }
        repeat(200_000) { c.peek(it % 500) }
        val n = 2_000_000
        val t0 = System.nanoTime()
        var hits = 0
        for (i in 0 until n) if (c.peek(i % 500) != null) hits++
        val ns = (System.nanoTime() - t0).toDouble() / n
        println("ImageCache.peek: %.1f ns per call ($hits hits)".format(ns))
        assertEquals(n, hits)
        assertTrue(ns < 5_000)
    }
}
