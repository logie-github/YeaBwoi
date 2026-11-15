package com.logie.wifecraft.data

/**
 * How many requests to wago.tools (a free community service) may be in flight at once. Workers [acquire] a permit before each
 * request and [release] it after; the answer is reported with [onSuccess] or [onThrottled]. A throttle (HTTP 429, 502, 503, 504,
 * a reset connection or a timeout: [isThrottleStatus], [isThrottleError]) halves the limit (never below [MIN_LIMIT]) and, where
 * the server gave a Retry-After, holds new requests back until it is over. Throttles arriving within [SETTLE_MS] of the last
 * halving are the same burst and don't halve again. After [RECOVER_AFTER] successes in a row the limit rises by [RECOVER_STEP],
 * up to the ceiling the player chose ([setCeiling], applied at once). Plain Kotlin, thread-safe; the clock (milliseconds) is
 * injected for tests.
 */
class FetchLimiter(ceiling: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Object()
    private var ceilingV = ceiling.coerceAtLeast(1)
    private var limitV = ceilingV
    private var inUse = 0
    private var streak = 0
    private var pausedUntil = 0L
    private var lastHalved = Long.MIN_VALUE / 2

    /** Called (outside the lock) whenever the limit or the ceiling changed. */
    @Volatile var onChange: (() -> Unit)? = null

    /** The parallelism in force now. */
    val limit: Int get() = synchronized(lock) { limitV }
    /** The most the player allows. */
    val ceiling: Int get() = synchronized(lock) { ceilingV }
    /** Requests holding a permit now. */
    val active: Int get() = synchronized(lock) { inUse }
    /** Milliseconds new requests are still held back for (0: none). */
    fun pausedFor(): Long = synchronized(lock) { (pausedUntil - clock()).coerceAtLeast(0) }

    /** Takes a permit if one is free and no pause is on; never waits. */
    fun tryAcquire(): Boolean = synchronized(lock) {
        if (inUse < limitV && clock() >= pausedUntil) { inUse++; true } else false
    }

    /** Waits for a permit; false when [stop] says to give up (checked about every 200 ms). */
    fun acquire(stop: () -> Boolean = { false }): Boolean {
        synchronized(lock) {
            while (true) {
                if (stop()) return false
                val now = clock()
                if (inUse < limitV && now >= pausedUntil) { inUse++; return true }
                val wait = if (now < pausedUntil) (pausedUntil - now).coerceIn(1, 200) else 200
                lock.wait(wait)
            }
        }
        @Suppress("UNREACHABLE_CODE") return false
    }

    fun release() = synchronized(lock) { if (inUse > 0) inUse--; lock.notifyAll() }

    fun onSuccess() {
        val changed = synchronized(lock) {
            if (++streak < RECOVER_AFTER) return
            streak = 0
            val next = minOf(ceilingV, limitV + RECOVER_STEP)
            (next != limitV).also { limitV = next; lock.notifyAll() }
        }
        if (changed) onChange?.invoke()
    }

    /** The server asked for less; [retryAfterMs] is its Retry-After (0: none given). */
    fun onThrottled(retryAfterMs: Long = 0) {
        val changed = synchronized(lock) {
            val now = clock()
            streak = 0
            if (retryAfterMs > 0) pausedUntil = maxOf(pausedUntil, now + retryAfterMs.coerceAtMost(MAX_PAUSE_MS))
            var c = false
            if (now - lastHalved >= SETTLE_MS) {
                lastHalved = now
                val next = maxOf(minOf(MIN_LIMIT, ceilingV), limitV / 2)
                c = next != limitV
                limitV = next
            }
            lock.notifyAll()
            c
        }
        if (changed) onChange?.invoke()
    }

    /** The player's choice, in force at once: a lower one cuts the limit down, a raised one lifts it if it wasn't slowed. */
    fun setCeiling(n: Int) {
        val changed = synchronized(lock) {
            val old = ceilingV
            ceilingV = n.coerceAtLeast(1)
            val before = limitV
            if (limitV > ceilingV || limitV == old) limitV = ceilingV
            lock.notifyAll()
            old != ceilingV || before != limitV
        }
        if (changed) onChange?.invoke()
    }

    companion object {
        const val MIN_LIMIT = 8
        const val RECOVER_AFTER = 50
        const val RECOVER_STEP = 4
        const val SETTLE_MS = 2000L
        const val MAX_PAUSE_MS = 60_000L

        fun isThrottleStatus(code: Int) = code == 429 || code == 502 || code == 503 || code == 504

        /** Connection reset or timed out: the server (or a proxy) is struggling. */
        fun isThrottleError(e: Throwable): Boolean =
            e is java.net.SocketTimeoutException || (e is java.net.SocketException && e.message?.contains("reset", ignoreCase = true) == true)

        /** Retry-After as milliseconds: whole seconds only (a date is ignored); null when absent or unreadable. */
        fun retryAfterMs(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    }
}
