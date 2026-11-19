package com.logie.wifecraft.ui

// Pure Kotlin (no Android, no coroutines): copied alone into the JVM tests (unit-tests: pureUi).

/**
 * Holds high-frequency updates (a download's per-file ticks, up to 96 at once) to at most one per [intervalMs], the way a
 * progress bar wants them: the first goes through at once, those within the interval are held and only the latest is
 * kept, an [urgent] one (finished, failed, paused) always goes through at once, and nothing held is ever lost: [dueIn]
 * says when the held one is due and [take] hands it over then (the caller schedules that; [flush] hands it over now).
 * Thread-safe. [clock] is in milliseconds (injected for tests). Values are never null (null is "held"): wrap a nullable one.
 */
class Sampler<T : Any>(
    val intervalMs: Long,
    private val clock: () -> Long,
    private val urgent: (previous: T?, next: T) -> Boolean = { _, _ -> false },
) {
    private var lastAt = Long.MIN_VALUE
    private var delivered: T? = null
    private var held: T? = null
    private var holding = false

    /** A new value: returned when it is to be delivered now, else null (held; [dueIn] says when). */
    @Synchronized
    fun offer(value: T): T? {
        val now = clock()
        if (lastAt == Long.MIN_VALUE || now - lastAt >= intervalMs || urgent(delivered, value)) {
            return deliver(value, now)
        }
        held = value
        holding = true
        return null
    }

    /** Milliseconds until the held value is due (0: now), or null when nothing is held. */
    @Synchronized
    fun dueIn(): Long? = if (!holding) null else (lastAt + intervalMs - clock()).coerceAtLeast(0L)

    /** The held value once it is due (else null). */
    @Synchronized
    fun take(): T? {
        if (!holding) return null
        val now = clock()
        if (now - lastAt < intervalMs) return null
        @Suppress("UNCHECKED_CAST")
        return deliver(held as T, now)
    }

    /** The held value now, whatever the interval (the stream ended: the last value is never dropped). */
    @Synchronized
    fun flush(): T? {
        if (!holding) return null
        @Suppress("UNCHECKED_CAST")
        return deliver(held as T, clock())
    }

    private fun deliver(value: T, now: Long): T {
        lastAt = now
        delivered = value
        held = null
        holding = false
        return value
    }
}

/**
 * [Sampler] without a value, for a source that recomputes its state when it posts (GameFiles' counters): [offer] true is
 * "post now", false "held"; [dueIn] / [take] / [flush] as the sampler's.
 */
class Throttle(intervalMs: Long, clock: () -> Long) {
    private val sampler = Sampler<Boolean>(intervalMs, clock) { _, urgent -> urgent }

    fun offer(urgent: Boolean = false): Boolean = sampler.offer(urgent) != null
    fun dueIn(): Long? = sampler.dueIn()
    fun take(): Boolean = sampler.take() != null
    fun flush(): Boolean = sampler.flush() != null
}

/** The progress interval the UI is fed at (4 a second: a bar moves smoothly, the screen isn't redrawn 96 times a second). */
const val PROGRESS_INTERVAL_MS = 250L
