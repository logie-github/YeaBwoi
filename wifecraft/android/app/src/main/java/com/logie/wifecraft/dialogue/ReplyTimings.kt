package com.logie.wifecraft.dialogue

import java.util.Locale

/**
 * What one request to the model cost, as the scheduler saw it: how long it waited, how much of its prompt had to be read (and how
 * much was already in the model's place for that NPC), how fast the model read and wrote, and when the first word came. Kept in a
 * ring ([TimingLog], the last 100) and shown in Options -> Dialogue so the developer can read real phone numbers off the
 * player's phone ("Copy timings").
 */
data class ReplyTiming(
    /** When it ended, the scheduler's clock (milliseconds). */
    val atMs: Long,
    val npcId: String,
    /** "chat", "joi", "speech", "warm", "speedtest" or "" ([DialogueRequest.tag]). */
    val tag: String,
    val priority: DialoguePriority,
    /** From being asked to starting to run: the time spent behind other requests (and, for an unprompted line, behind the token budget). */
    val queueWaitMs: Long,
    /** From being asked to the first piece of the answer: the number the player feels ("first word after X s"). 0 when no word came. */
    val firstWordMs: Long,
    /** From being asked to the end. */
    val totalMs: Long,
    val finish: DialogueFinish,
    /** The answer was ended early: [Softstop] the sentence limit was reached, or [guarded] the stream check found something wrong. */
    val softStopped: Boolean = false,
    val guarded: Boolean = false,
    /** 0 the first run; 1 after having been preempted by an urgent request once. */
    val attempt: Int = 0,
    val maxTokens: Int = 0,
    val promptTokens: Int = 0,
    /** Tokens of the prompt that were read this time (the prompt minus the three kinds below). */
    val readTokens: Int = 0,
    /** Already in this NPC's place from the last turn (a hit), borrowed from another NPC's place (the shared preamble), or read back from the file. */
    val reusedTokens: Int = 0,
    val sharedTokens: Int = 0,
    val restoredTokens: Int = 0,
    val prefillMs: Double = 0.0,
    val generated: Int = 0,
    val decodeMs: Double = 0.0,
    /** From the model's own start of the request to its first token (its prefill included): the model's share of [firstWordMs]. */
    val firstTokenMs: Double = 0.0,
    /** Tokens held in this NPC's place when it ended: the size of the cache in use. */
    val kvTokens: Int = 0,
    val threads: Int = 0,
    val threadsBatch: Int = 0,
    val prefillOnly: Boolean = false,
) {
    /** How fast the prompt was read, tokens a second (0 when it was all reused). */
    val prefillTokensPerSecond: Double get() = if (readTokens > 0 && prefillMs > 0) readTokens * 1000.0 / prefillMs else 0.0

    /** How fast the answer was written, tokens a second (0 when it was too short to say). */
    val decodeTokensPerSecond: Double get() = if (generated > 2 && decodeMs > 0) generated * 1000.0 / decodeMs else 0.0

    /** How the NPC's place served this request. */
    val slot: String get() = when {
        restoredTokens > 0 -> "restored"
        sharedTokens > 0 -> "borrowed"
        reusedTokens > 0 -> "hit"
        else -> "miss"
    }

    /**
     * Where this request's time went, the four phases the player feels: waiting in the queue, reading the prompt, the model's step from the
     * end of the prompt to its first token, and writing the rest; [Phases.otherMs] is what is left (the scheduler's own hand-over).
     */
    val phases: Phases get() {
        val queue = queueWaitMs.toDouble()
        val prefill = prefillMs.coerceAtMost(if (firstTokenMs > 0) firstTokenMs else prefillMs)
        val firstExtra = (firstTokenMs - prefill).coerceAtLeast(0.0)
        val decode = decodeMs
        val other = (totalMs - queue - prefill - firstExtra - decode).coerceAtLeast(0.0)
        return Phases(queue, prefill, firstExtra, decode, other)
    }

    /** One sentence for a slow request: the phase that took the most and what it was made of. */
    fun diagnosis(): String {
        val p = phases
        val top = listOf("waiting in the queue" to p.queueMs, "reading the prompt" to p.prefillMs, "starting to write" to p.firstTokenExtraMs, "writing" to p.decodeMs, "other work" to p.otherMs).maxByOrNull { it.second }!!
        val why = when (top.first) {
            "reading the prompt" -> String.format(Locale.ROOT, " (%d tokens read at %.0f tok/s, %d already in the model)", readTokens, prefillTokensPerSecond, reusedTokens + sharedTokens + restoredTokens)
            "writing" -> String.format(Locale.ROOT, " (%d tokens at %.1f tok/s)", generated, decodeTokensPerSecond)
            else -> ""
        }
        return String.format(Locale.ROOT, "%.1f s of %.1f s went to %s%s", top.second / 1000.0, totalMs / 1000.0, top.first, why)
    }

    /** True for a real answer someone waited for (not a warm-up, not cancelled, not a request dropped for an error). */
    val isReply: Boolean get() = !prefillOnly && !guarded && generated > 0 && finish != DialogueFinish.ERROR && (finish != DialogueFinish.CANCELLED || softStopped)

    fun line(): String = String.format(Locale.ROOT,
        "%s\t%s\t%s\t%s\twait %d ms\tfirst word %d ms\ttotal %d ms\tprompt %d (read %d, hit %d, borrowed %d, restored %d: %s)\tprefill %.0f ms (%.1f tok/s)\tdecode %d tok %.0f ms (%.1f tok/s)\tfirst token %.0f ms\tkv %d\tthreads %d/%d\t%s%s%s%s",
        atMs, tag.ifEmpty { "-" }, npcId, priority.name, queueWaitMs, firstWordMs, totalMs, promptTokens, readTokens, reusedTokens, sharedTokens, restoredTokens, slot,
        prefillMs, prefillTokensPerSecond, generated, decodeMs, decodeTokensPerSecond, firstTokenMs, kvTokens, threads, threadsBatch, finish.name,
        if (softStopped) " sentence-stop" else "", if (guarded) " guard-stop" else "", if (attempt > 0) " rerun" else "")
}

/** The last [capacity] requests' timings, newest last. Thread-safe: the scheduler's worker writes, the Options page and the report read. */
class TimingLog(val capacity: Int = 100) {
    private val ring = ArrayDeque<ReplyTiming>()

    /** How many timings were ever added (the ring forgets the oldest; this does not). */
    @Volatile var added: Long = 0L
        private set

    fun add(t: ReplyTiming) = synchronized(ring) { ring.addLast(t); added++; while (ring.size > capacity) ring.removeFirst() }
    fun all(): List<ReplyTiming> = synchronized(ring) { ring.toList() }
    fun last(n: Int): List<ReplyTiming> = all().takeLast(n)
    fun clear() = synchronized(ring) { ring.clear() }
    val size: Int get() = synchronized(ring) { ring.size }

    /** The median of the newest [n] answered requests' first-word times and writing speeds: what "Last replies" shows. */
    fun summary(n: Int = 10): TimingSummary {
        val replies = all().filter { it.isReply }.takeLast(n)
        if (replies.isEmpty()) return TimingSummary()
        fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        return TimingSummary(
            replies = replies.size,
            firstWordSeconds = median(replies.filter { it.firstWordMs > 0 }.map { it.firstWordMs / 1000.0 }),
            tokensPerSecond = median(replies.map { it.decodeTokensPerSecond }.filter { it > 0 }),
            prefillTokensPerSecond = median(replies.map { it.prefillTokensPerSecond }.filter { it > 0 }),
            queueSeconds = median(replies.map { it.queueWaitMs / 1000.0 }),
            readTokens = median(replies.map { it.readTokens.toDouble() }).toInt(),
        )
    }

    /** The whole ring as text for the clipboard (and the developer): [header] first (the model, the CPU, the threads), then a line per request. */
    fun toText(header: String = ""): String = buildString {
        if (header.isNotBlank()) append(header.trimEnd()).append('\n')
        val rows = all()
        append("requests: ").append(rows.size).append('\n')
        for (r in rows) append(r.line()).append("\tphases: ").append(r.phases.text()).append('\n')
    }

    /**
     * The text of "Report slow replies": [header] (the model, its load, the CPU's features and cores, the threads), how many replies were
     * slower than [limitMs] and what dominated each, then those requests in full (with their phases and prompt token counts). Nothing
     * of what was said is in it: only numbers and the NPC ids.
     */
    fun slowReport(header: String = "", limitMs: Long = SLOW_MS): String = buildString {
        if (header.isNotBlank()) append(header.trimEnd()).append('\n')
        val replies = all().filter { it.isReply }
        val slow = replies.filter { it.totalMs > limitMs }
        append("replies: ").append(replies.size).append(", slower than ").append(limitMs / 1000).append(" s: ").append(slow.size).append('\n')
        if (replies.isNotEmpty()) {
            val s = summary(replies.size)
            append(s.text()).append(' ').append(s.detail()).append('\n')
        }
        for (r in slow.takeLast(20)) append(r.line()).append("\n\tphases: ").append(r.phases.text()).append("\n\t").append(r.diagnosis()).append('\n')
        val warm = all().filter { it.prefillOnly }
        if (warm.isNotEmpty()) append("warm-ups: ").append(warm.size).append(", median ").append(String.format(Locale.ROOT, "%.1f", warm.map { it.totalMs / 1000.0 }.sorted().let { it[it.size / 2] })).append(" s\n")
    }

    companion object {
        /** A reply slower than this misses the target ([SpeedTarget.LINE_SECONDS]). */
        const val SLOW_MS = 5000L
    }
}

/** What a request's time was spent on, in milliseconds ([ReplyTiming.phases]). */
data class Phases(val queueMs: Double, val prefillMs: Double, val firstTokenExtraMs: Double, val decodeMs: Double, val otherMs: Double) {
    fun text(): String = String.format(Locale.ROOT, "queue %.0f ms + prefill %.0f ms + first token %.0f ms + decode %.0f ms + other %.0f ms", queueMs, prefillMs, firstTokenExtraMs, decodeMs, otherMs)
}

data class TimingSummary(
    val replies: Int = 0,
    val firstWordSeconds: Double = 0.0,
    val tokensPerSecond: Double = 0.0,
    val prefillTokensPerSecond: Double = 0.0,
    val queueSeconds: Double = 0.0,
    val readTokens: Int = 0,
) {
    /** "Last replies: first word after 7.2 s, 5.1 tok/s" (or that there are none yet). */
    fun text(): String =
        if (replies == 0) "Last replies: none yet."
        else String.format(Locale.ROOT, "Last replies: first word after %.1f s, %.1f tok/s", firstWordSeconds, tokensPerSecond)

    /** More words under it: what the time was spent on. */
    fun detail(): String =
        if (replies == 0) ""
        else String.format(Locale.ROOT, "Median of the last %d answers: %.1f s waiting in the queue, %d prompt tokens read at %.0f tok/s, then %.1f tok/s writing.",
            replies, queueSeconds, readTokens, prefillTokensPerSecond, tokensPerSecond)

    /** The hint when the phone writes slowly (below [SLOW] tokens a second), else null. */
    fun hint(): String? = SpeedHint.of(tokensPerSecond)
}

/** What the Speed test and the timings tell a player whose phone is slow for this model. */
object SpeedHint {
    const val SLOW = 3.0

    fun of(decodeTokensPerSecond: Double): String? =
        if (decodeTokensPerSecond > 0 && decodeTokensPerSecond < SLOW)
            "This phone is slow for this model (${String.format(Locale.ROOT, "%.1f", decodeTokensPerSecond)} tokens a second); try Speed: Auto or close other apps."
        else null
}
