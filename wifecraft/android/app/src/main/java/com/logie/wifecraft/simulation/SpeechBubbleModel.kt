package com.logie.wifecraft.simulation

import kotlin.math.ceil

/**
 * The layout model of the world's chat bubble (the one look every NPC line has: Chat mode's replies, NPC-to-NPC talk, stage banter),
 * pure Kotlin and JVM-tested (SpeechBubbleModelTest): word wrap, the bubble's width and lines, the scale with distance, where it sits
 * on the screen with its tail aimed at the speaker, how long it stays, and the kind of line (say, yell, emote) as a colour hook.
 * The drawing is ui/SpeechBubbles.kt.
 */
object SpeechBubbleModel {
    enum class Kind { SAY, YELL, EMOTE }

    /** Approximate width of one character of the bubble's 13 sp text, in dp (the width is estimated, the text itself is wrapped by the UI). */
    const val CHAR_DP = 6.6f
    const val PAD_DP = 12f
    const val MIN_WIDTH_DP = 64f
    const val MAX_WIDTH_DP = 220f
    const val MAX_WIDTH_LONG_DP = 270f
    const val MAX_LINES = 3
    const val MAX_LINES_LONG = 7
    /** A reply longer than this many characters is a long one: a taller, wider bubble. */
    const val LONG_CHARS = 110
    /** The depth (yards) at which the bubble is drawn at its natural size, and its size limits. */
    const val REF_DEPTH = 5f
    const val MIN_SCALE = 0.7f
    const val MAX_SCALE = 1.2f

    /** A line's kind: an emote starts with "*", "<" or "/me"; a yell is a shout in capitals (six letters or more) or ends in "!!". */
    fun kind(text: String): Kind {
        val t = text.trim()
        if (t.startsWith("*") || t.startsWith("<") || t.startsWith("/me ")) return Kind.EMOTE
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 6 && letters.all { it.isUpperCase() }) return Kind.YELL
        if (t.endsWith("!!")) return Kind.YELL
        return Kind.SAY
    }

    /** [text] wrapped to lines of at most [maxChars] characters at spaces (a word longer than a line is cut). */
    fun wrap(text: String, maxChars: Int): List<String> {
        val limit = maxChars.coerceAtLeast(4)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (raw in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var w = raw
            while (w.length > limit) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur = StringBuilder() }
                out += w.take(limit); w = w.drop(limit)
            }
            if (cur.isEmpty()) cur.append(w)
            else if (cur.length + 1 + w.length <= limit) cur.append(' ').append(w)
            else { out += cur.toString(); cur = StringBuilder(w) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    class Shape(val widthDp: Float, val maxLines: Int, val lines: List<String>, val long: Boolean) {
        /** The text does not all fit [maxLines]: the UI ends the last line with an ellipsis. */
        val truncated: Boolean get() = lines.size > maxLines
    }

    /** The bubble's shape for [text]: as narrow as the words need (up to the limit), 1 to 3 lines (a long reply: a wider, taller one). */
    fun shape(text: String): Shape {
        val len = text.trim().length
        val long = len > LONG_CHARS
        val maxW = if (long) MAX_WIDTH_LONG_DP else MAX_WIDTH_DP
        val maxChars = ((maxW - 2 * PAD_DP) / CHAR_DP).toInt()
        val lineCount = (ceil(len / maxChars.toFloat()).toInt()).coerceAtLeast(1)
        // balance: spread the text over its lines so the last one is not a stray word
        val perLine = if (lineCount <= 1) len.coerceAtLeast(1) else (len / lineCount + 8).coerceAtMost(maxChars)
        val width = (perLine * CHAR_DP + 2 * PAD_DP).coerceIn(MIN_WIDTH_DP, maxW)
        val lines = wrap(text, ((width - 2 * PAD_DP) / CHAR_DP + 0.5f).toInt())
        return Shape(width, if (long) MAX_LINES_LONG else MAX_LINES, lines, long)
    }

    /** The bubble's scale at [depth] yards from the camera: a gentle change, never under [MIN_SCALE] nor over [MAX_SCALE]. */
    fun scale(depth: Float): Float = if (depth > 0f && depth.isFinite()) (REF_DEPTH / depth).coerceIn(MIN_SCALE, MAX_SCALE) else MIN_SCALE

    /** Shown at all: the head is on screen (depth > 0 as the layout gives it). */
    fun visible(depth: Float, shown: Boolean): Boolean = shown && depth > 0f

    class Place(val left: Float, val tailX: Float)

    /**
     * Where a bubble [boxW] wide goes on a [screenW] wide screen for a head at [anchorX]: centred over it, kept [margin] inside the
     * screen edge, and the tail's x within the bubble (from its left edge), still aimed at the head and kept [tailInset] from the corners.
     */
    fun place(anchorX: Float, boxW: Float, screenW: Float, margin: Float = 6f, tailInset: Float = 14f): Place {
        val maxLeft = (screenW - boxW - margin).coerceAtLeast(margin)
        val left = (anchorX - boxW / 2f).coerceIn(margin, maxLeft)
        val tail = (anchorX - left).coerceIn(minOf(tailInset, boxW / 2f), maxOf(boxW - tailInset, boxW / 2f))
        return Place(left, tail)
    }

    /** How long a line stays up, seconds: its reading time, proportional to its length, never under 3 nor over 16. */
    fun holdSeconds(text: String): Float = (1.8f + 0.06f * text.trim().length).coerceIn(3f, 16f)

    /** The text colour of a [kind] (ARGB): black for a say (the game's), dark red for a yell, orange for an emote. */
    fun colour(kind: Kind): Long = when (kind) { Kind.SAY -> 0xFF000000; Kind.YELL -> 0xFFB01010; Kind.EMOTE -> 0xFFD2691E }
}
