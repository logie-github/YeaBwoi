package com.logie.wifecraft.dialogue

import java.util.Locale

/**
 * The speed the player asked for ("JOI and chat took 90 seconds to respond. That's absurd. It needs to be in less than 5"): the first
 * words in about [FIRST_WORD_SECONDS] and a whole JOI line or chat reply in [LINE_SECONDS], once the NPC's persona has been read.
 * The Speed test turns the two speeds it measures (reading a prompt, writing) into the line time this phone will give, with the
 * sizes of the compact prompts ([NEW_TOKENS], [LINE_TOKENS], [PREFIX_TOKENS]; PromptBudgetTest keeps the prompts to them). Pure.
 */
object SpeedTarget {
    const val LINE_SECONDS = 5.0
    const val FIRST_WORD_SECONDS = 3.0
    /** What a warm request reads: the stage and steer text and the task, and the short lines she said last. */
    const val NEW_TOKENS = 80
    /** A JOI line or a short chat reply: 34 tokens is about 24 words. */
    const val LINE_TOKENS = 34
    /** The stable part of a compact prompt (persona, register, examples), read once ahead of the first line ("Getting ready..."). */
    const val PREFIX_TOKENS = 350
    /** The fixed cost of a request outside the model: the queue, the first token's sampling, the screen. */
    const val OVERHEAD_SECONDS = 0.25

    data class Estimate(
        /** From asking for a line to its first word, with the persona already read. */
        val firstWordSeconds: Double,
        /** From asking to the whole line, with the persona already read. */
        val lineSeconds: Double,
        /** Reading the persona before the first line (cold: the "Getting ready..." time, once per NPC and level, then kept on disk). */
        val getReadySeconds: Double,
    ) {
        val passes: Boolean get() = lineSeconds <= LINE_SECONDS
        fun text(): String = String.format(Locale.ROOT, "Estimated JOI line time: %.1f s (%s the %.0f s target); first word after about %.1f s. Getting an NPC ready the first time: about %.0f s.",
            lineSeconds, if (passes) "within" else "over", LINE_SECONDS, firstWordSeconds, getReadySeconds)
    }

    /** Null when either speed is unknown (0): nothing can be said. */
    fun estimate(prefillTokensPerSecond: Double, decodeTokensPerSecond: Double): Estimate? {
        if (prefillTokensPerSecond <= 0 || decodeTokensPerSecond <= 0) return null
        val read = NEW_TOKENS / prefillTokensPerSecond
        val first = OVERHEAD_SECONDS + read + 1.0 / decodeTokensPerSecond
        val line = OVERHEAD_SECONDS + read + LINE_TOKENS / decodeTokensPerSecond
        return Estimate(first, line, PREFIX_TOKENS / prefillTokensPerSecond)
    }
}
