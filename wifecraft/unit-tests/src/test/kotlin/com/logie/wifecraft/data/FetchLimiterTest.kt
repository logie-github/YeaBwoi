package com.logie.wifecraft.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FetchLimiterTest {
    private var now = 1_000_000L
    private fun limiter(ceiling: Int = 96) = FetchLimiter(ceiling) { now }

    @Test fun startsAtTheCeiling() = assertEquals(96, limiter().limit)

    @Test fun throttleHalvesDownToTheMinimum() {
        val l = limiter()
        val seen = mutableListOf<Int>()
        repeat(6) { l.onThrottled(); seen += l.limit; now += 3000 }
        assertEquals(listOf(48, 24, 12, 8, 8, 8), seen)
    }

    @Test fun aBurstHalvesOnce() {
        val l = limiter()
        repeat(40) { l.onThrottled(); now += 10 }
        assertEquals(48, l.limit)
    }

    @Test fun lowCeilingNeverGoesBelowItself() {
        val l = limiter(6)
        l.onThrottled()
        assertEquals(6, l.limit)
    }

    @Test fun retryAfterPausesNewRequests() {
        val l = limiter()
        l.onThrottled(5000)
        assertFalse(l.tryAcquire())
        assertEquals(5000, l.pausedFor())
        now += 4999
        assertFalse(l.tryAcquire())
        now += 1
        assertTrue(l.tryAcquire())
        assertEquals(0, l.pausedFor())
    }

    @Test fun permitsAreCappedByTheLimit() {
        val l = limiter(12)
        repeat(12) { assertTrue(l.tryAcquire()) }
        assertFalse(l.tryAcquire())
        l.release()
        assertTrue(l.tryAcquire())
    }

    @Test fun acquireGivesUpWhenStopped() {
        val l = limiter(1)
        assertTrue(l.acquire())
        assertFalse(l.acquire { true })
    }

    @Test fun recoversByFourAfterFiftySuccesses() {
        val l = limiter()
        l.onThrottled()                       // 48
        repeat(49) { l.onSuccess() }
        assertEquals(48, l.limit)
        l.onSuccess()
        assertEquals(52, l.limit)
        repeat(50 * 20) { l.onSuccess() }
        assertEquals(96, l.limit)             // never past the ceiling
    }

    @Test fun aThrottleResetsTheStreak() {
        val l = limiter()
        l.onThrottled()
        repeat(49) { l.onSuccess() }
        now += 3000
        l.onThrottled()                       // 24
        l.onSuccess()
        assertEquals(24, l.limit)
    }

    @Test fun loweringTheCeilingCutsAtOnce() {
        val l = limiter()
        l.setCeiling(24)
        assertEquals(24, l.limit)
        assertEquals(24, l.ceiling)
    }

    @Test fun raisingTheCeilingLiftsAnUnslowedLimitButNotASlowedOne() {
        val l = limiter(24)
        l.setCeiling(96)
        assertEquals(96, l.limit)
        l.onThrottled()                       // 48
        l.setCeiling(96)
        assertEquals(48, l.limit)
    }

    @Test fun reportsChanges() {
        val l = limiter()
        var n = 0
        l.onChange = { n++ }
        l.onThrottled()
        l.setCeiling(24)
        l.setCeiling(24)
        assertEquals(2, n)
    }

    @Test fun classifiesFailures() {
        for (c in listOf(429, 502, 503, 504)) assertTrue(FetchLimiter.isThrottleStatus(c))
        for (c in listOf(200, 404, 500, 403)) assertFalse(FetchLimiter.isThrottleStatus(c))
        assertTrue(FetchLimiter.isThrottleError(java.net.SocketTimeoutException()))
        assertTrue(FetchLimiter.isThrottleError(java.net.SocketException("Connection reset")))
        assertFalse(FetchLimiter.isThrottleError(java.io.IOException("File 5 came empty")))
        assertEquals(7000L, FetchLimiter.retryAfterMs(" 7 "))
        assertNull(FetchLimiter.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(FetchLimiter.retryAfterMs(null))
    }
}
