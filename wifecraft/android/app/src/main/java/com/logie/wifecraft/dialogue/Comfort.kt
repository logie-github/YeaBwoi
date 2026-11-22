package com.logie.wifecraft.dialogue

/** How at ease two people are with each other in bed and out of it. The talk gets bolder, kinkier and stranger up the bands. */
enum class ComfortBand(val key: String, val label: String, val min: Float) {
    STRANGERS("stranger", "strangers", 0f),
    FAMILIAR("familiar", "familiar", 0.8f),
    COMFORTABLE("comfortable", "comfortable", 2.5f),
    VERY_COMFORTABLE("very comfortable", "very comfortable", 5f),
    UNINHIBITED("uninhibited", "uninhibited", 8f);

    /**
     * The same ease on the kink pack's scale ([KinkPack]'s [Band], which caps how intense its notes may be): strangers, familiar to
     * acquaintances, comfortable to friendly, very comfortable to intimate, uninhibited to uninhibited. This enum is the one the
     * scene keeps; the pack's is only what its notes are asked with.
     */
    val kink: Band get() = when (this) {
        STRANGERS -> Band.STRANGERS; FAMILIAR -> Band.ACQUAINTANCES; COMFORTABLE -> Band.FRIENDLY
        VERY_COMFORTABLE -> Band.INTIMATE; UNINHIBITED -> Band.UNINHIBITED
    }

    /** Whether the body of a partner's race may be drawn on in talk (from comfortable up). */
    val bodyTalk: Boolean get() = this >= COMFORTABLE

    companion object {
        fun of(score: Float): ComfortBand = entries.last { score >= it.min }
    }
}

/**
 * Who has been at ease with whom, this scene only: a ledger kept in memory and thrown away with the scene (nothing is saved,
 * nothing carries over between sessions). It rises with each sexual stage two have had (more for a long one), with trust (the
 * friendship and romance the life simulation keeps) and kindness, and falls with hesitation and refusals. Each NPC reads it
 * through their own nature: a shy one loosens slowly, a bold one fast ([Disposition.speed]).
 */
class ComfortLedger {
    private class Entry { var stages = 0; var seconds = 0f; var kind = 0f; var chat = 0f; var hesitations = 0; var refusals = 0 }
    private val entries = HashMap<String, Entry>()

    private fun key(a: String, b: String) = if (a <= b) "$a|$b" else "$b|$a"
    private fun entry(a: String, b: String) = entries.getOrPut(key(a, b)) { Entry() }

    /** A sexual stage of [a] and [b] that ran [seconds]. */
    fun stage(a: String, b: String, seconds: Float) { entry(a, b).apply { stages++; this.seconds += seconds.coerceAtLeast(0f) } }
    fun kindness(a: String, b: String) { entry(a, b).apply { kind = (kind + 0.3f).coerceAtMost(1.5f) } }
    /**
     * An exchange of talk between [a] and [b] (Chat mode: the player's character and an NPC): [amount] (0.2 an ordinary exchange, 0.3 a
     * kind one) added to the ease of the pair, up to [CHAT_CAP] in all, so a long friendly talk loosens them to comfortable and no further.
     */
    fun chatted(a: String, b: String, amount: Float = 0.2f) { entry(a, b).apply { chat = (chat + amount.coerceAtLeast(0f)).coerceAtMost(CHAT_CAP) } }
    fun hesitated(a: String, b: String) { entry(a, b).apply { hesitations++ } }
    fun refused(a: String, b: String) { entry(a, b).apply { refusals++ } }

    fun stages(a: String, b: String): Int = entries[key(a, b)]?.stages ?: 0

    /** The raw score of the pair, before either one's nature: stages (1 each, up to 3 for a long one) + trust + kindness - doubts. */
    fun score(a: String, b: String, friendship: Float = 0f, romance: Float = 0f): Float {
        val e = entries[key(a, b)]
        val encounters = if (e == null) 0f else (e.stages * 1f + (e.seconds / 120f).coerceAtMost(e.stages * 2f))
        val trust = (friendship.coerceIn(-100f, 100f) / 100f).coerceAtLeast(-0.5f) + (romance.coerceIn(-100f, 100f) / 100f).coerceAtLeast(-0.5f)
        val doubt = (e?.hesitations ?: 0) * 0.4f + (e?.refusals ?: 0) * 1.5f
        return (encounters + trust + (e?.kind ?: 0f) + (e?.chat ?: 0f) - doubt).coerceAtLeast(0f)
    }

    /** [who]'s band toward [other]: the score scaled by [who]'s own speed. */
    fun band(who: Disposition, a: String, b: String, friendship: Float = 0f, romance: Float = 0f): ComfortBand =
        ComfortBand.of(score(a, b, friendship, romance) * who.speed)

    fun clear() = entries.clear()

    companion object {
        /** The most that talk alone adds to a pair's ease (a long, friendly chat reaches comfortable for a nature of ordinary pace). */
        const val CHAT_CAP = 3.0f
    }
}
