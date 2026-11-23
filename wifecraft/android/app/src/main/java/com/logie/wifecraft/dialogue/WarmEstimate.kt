package com.logie.wifecraft.dialogue

import java.util.Locale

/**
 * How long reading an NPC's persona ahead of its first request takes, for the "Getting ready..." bar. The native core does not report
 * how far a read has got, so the bar is an estimate from the tokens to read and the speed this phone read prompts at lately (the
 * timings), held under 95 % until the read really ends. Pure (JVM-tested: WarmUpTest).
 */
object WarmEstimate {
    /** A prompt-reading speed to assume before the phone has shown its own (tokens a second): a small model on the big cores. */
    const val DEFAULT_RATE = 60.0

    /** The median speed of the newest prompt reads of 48 tokens or more, tokens a second ([DEFAULT_RATE] when there are none). */
    fun rateFrom(timings: List<ReplyTiming>): Double {
        val r = timings.filter { it.readTokens >= 48 && it.prefillMs > 0 }.takeLast(8).map { it.prefillTokensPerSecond }.filter { it > 0 }.sorted()
        return if (r.isEmpty()) DEFAULT_RATE else r[r.size / 2]
    }

    /** Seconds [tokens] take to read at [rate] tokens a second. */
    fun seconds(tokens: Int, rate: Double): Double = if (tokens <= 0) 0.0 else tokens / rate.coerceAtLeast(1.0)

    /** 0 to 0.95: how much of the read has probably been done [elapsedMs] after it was asked for. */
    fun progress(elapsedMs: Long, tokens: Int, rate: Double): Float {
        val total = seconds(tokens, rate) * 1000.0
        if (total <= 0.0) return 0.95f
        return (elapsedMs / total).coerceIn(0.0, 0.95).toFloat()
    }

    /** "Getting ready..." with the estimate in seconds left once there is one. */
    fun label(progress: Float, tokens: Int, rate: Double): String {
        val left = (seconds(tokens, rate) * (1.0 - progress)).coerceAtLeast(0.0)
        return if (left < 1.0) "Getting ready..." else String.format(Locale.ROOT, "Getting ready... about %d s", Math.ceil(left).toInt())
    }
}
