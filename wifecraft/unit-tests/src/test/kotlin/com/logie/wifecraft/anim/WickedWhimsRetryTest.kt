package com.logie.wifecraft.anim

import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WickedWhimsRetryTest {
    @Test
    fun readsRetryAfterSecondsAndHttpDates() {
        val now = Instant.parse("2015-10-21T07:27:00Z").toEpochMilli()
        assertEquals(12_000L, WickedWhimsRetry.retryAfter(" 12 ", now))
        assertEquals(60_000L, WickedWhimsRetry.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now))
        assertEquals(0L, WickedWhimsRetry.retryAfter("Wed, 21 Oct 2015 07:26:00 GMT", now))
        assertEquals(null, WickedWhimsRetry.retryAfter("later", now))
    }

    @Test
    fun waitsBeforeRetryingAndKeepsTheCooldownAcrossInstallAttempts() {
        var time = 1000L
        var requests = 0
        val waits = mutableListOf<Long>()
        val retry = WickedWhimsRetry { time }
        val wait: suspend (Long) -> Unit = { waits += it; time += it }
        assertFailsWith<WickedWhimsRateLimited> {
            synchronously {
                retry.run(wait) {
                    requests++
                    throw WickedWhimsRateLimited(if (requests == 2) 40_000L else null)
                }
            }
        }
        assertEquals(3, requests)
        assertEquals(listOf(15_000L, 40_000L), waits)
        assertEquals("archive", synchronously { retry.run(wait) { "archive" } })
        assertEquals(listOf(15_000L, 40_000L, 60_000L), waits)
    }

    private fun <T> synchronously(block: suspend () -> T): T {
        var completion: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completion = result }
        })
        return checkNotNull(completion).getOrThrow()
    }
}
