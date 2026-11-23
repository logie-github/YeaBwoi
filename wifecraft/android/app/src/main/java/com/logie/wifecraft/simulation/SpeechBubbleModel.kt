package com.logie.wifecraft.simulation

import kotlin.math.ceil

/**
 * The layout model of the world's chat bubble (the one look every NPC line has: Chat mode's replies, NPC-to-NPC talk, stage banter),
 * pure Kotlin and JVM-tested (SpeechBubbleModelTest): word wrap, the bubble's width and lines, the scale with distance, where it sits
 * on the screen with its tail aimed at the speaker, how long it stays, and the kind of line (say, yell, emote) as a colour hook.
 * The drawing is ui/SpeechBubbles.kt.
 */
object SpeechBubbleModel {
    enum class Kind { SAY, YELL, EMOTE, WHISPER }

    /** Approximate width of one character of the bubble's 13 sp text, in dp (the width is estimated, the text itself is wrapped by the UI). */
    const val CHAR_DP = 6.2f
    /** The WoW bubble's padding: generous at the sides, less above and below. */
    const val PAD_DP = 14f
    const val PAD_V_DP = 10f
    /** Where the tail sits along an unconstrained bubble (0 = left edge): left of centre, as in the game. */
    const val TAIL_FRAC = 0.42f
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
        val t = display(text).trim()
        if (t.startsWith("/w ") || t.startsWith("/whisper ")) return Kind.WHISPER
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
        val shown = display(text)
        val len = shown.trim().length
        val long = len > LONG_CHARS
        val maxW = if (long) MAX_WIDTH_LONG_DP else MAX_WIDTH_DP
        val maxChars = ((maxW - 2 * PAD_DP) / CHAR_DP).toInt()
        val lineCount = (ceil(len / maxChars.toFloat()).toInt()).coerceAtLeast(1)
        // balance: spread the text over its lines so the last one is not a stray word
        val perLine = if (lineCount <= 1) len.coerceAtLeast(1) else (len / lineCount + 8).coerceAtMost(maxChars)
        val width = (perLine * CHAR_DP + 2 * PAD_DP).coerceIn(MIN_WIDTH_DP, maxW)
        val lines = wrap(shown, ((width - 2 * PAD_DP) / CHAR_DP + 0.5f).toInt())
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
    fun place(anchorX: Float, boxW: Float, screenW: Float, margin: Float = 6f, tailInset: Float = 14f, tailFrac: Float = 0.5f): Place {
        val maxLeft = (screenW - boxW - margin).coerceAtLeast(margin)
        val left = (anchorX - boxW * tailFrac).coerceIn(margin, maxLeft)
        val tail = (anchorX - left).coerceIn(minOf(tailInset, boxW / 2f), maxOf(boxW - tailInset, boxW / 2f))
        return Place(left, tail)
    }

    /** How long a line stays up, seconds: its reading time, proportional to its length, never under 3 nor over 16. */
    fun holdSeconds(text: String): Float = (1.8f + 0.06f * display(text).trim().length).coerceIn(3f, 16f)

    /** The text colour of a [kind] (ARGB) on the dark bubble: white for a say, red for a yell, orange for an emote, pink-purple for a whisper. */
    fun colour(kind: Kind): Long = when (kind) {
        Kind.SAY -> 0xFFFFFFFF; Kind.YELL -> 0xFFFF4040; Kind.EMOTE -> 0xFFFF8040; Kind.WHISPER -> 0xFFFF80FF
    }

    // ---- inline link markup -------------------------------------------------------------------------------------------------
    // "[Name]" is drawn with its brackets in the epic-item purple; "[[q:Name]]" shows "[Name]" in the colour of its code. Nothing
    // else is markup: any other character stays as written, so a model's or a player's text can only ever choose these colours.

    /** Link colours (ARGB), by the code after "[[": i epic purple (the plain "[Name]"), q quest yellow, g uncommon green, b rare blue, e legendary orange. */
    val LINK_COLOURS: Map<Char, Long> = mapOf('i' to 0xFFA335EE, 'q' to 0xFFFFD100, 'g' to 0xFF1EFF00, 'b' to 0xFF0070DD, 'e' to 0xFFFF8000)
    const val LINK_MAX_CHARS = 60

    /** A coloured stretch of [Parsed.text]: [start] until [end], in [colour] (ARGB). */
    class Span(val start: Int, val end: Int, val colour: Long)

    /** [text] as shown (markup resolved, brackets of links kept) and the coloured stretches in it. */
    class Parsed(val text: String, val spans: List<Span>)

    private fun clean(name: String): Boolean =
        name.isNotBlank() && name.length <= LINK_MAX_CHARS && name.none { it == '[' || it == ']' || it == '\n' }

    /** Resolve the markup in [raw]; malformed or unknown markup is left as plain text. */
    fun parse(raw: String): Parsed {
        val out = StringBuilder(); val spans = ArrayList<Span>()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '[') {
                val typed = raw.startsWith("[[", i) && i + 4 < raw.length && raw[i + 3] == ':' && LINK_COLOURS.containsKey(raw[i + 2])
                if (typed) {
                    val close = raw.indexOf("]]", i + 4)
                    val name = if (close > 0) raw.substring(i + 4, close) else ""
                    if (close > 0 && clean(name)) {
                        val s = out.length; out.append('[').append(name).append(']')
                        spans += Span(s, out.length, LINK_COLOURS.getValue(raw[i + 2])); i = close + 2; continue
                    }
                } else if (!raw.startsWith("[[", i)) {
                    val close = raw.indexOf(']', i + 1)
                    val name = if (close > 0) raw.substring(i + 1, close) else ""
                    if (close > 0 && clean(name)) {
                        val s = out.length; out.append('[').append(name).append(']')
                        spans += Span(s, out.length, LINK_COLOURS.getValue('i')); i = close + 1; continue
                    }
                }
            }
            if (c == '[' && raw.startsWith("[[", i)) { out.append("[["); i += 2; continue }
            out.append(c); i++
        }
        return Parsed(out.toString(), spans)
    }

    /** [raw] with the markup resolved and no colours: what the bubble shows, and what wrapping and timing measure. */
    fun display(raw: String): String = if (raw.indexOf('[') < 0) raw else parse(raw).text

    /** Safe text for a link's name: brackets, line breaks and over-long names removed. */
    fun escapeName(name: String): String = name.filter { it != '[' && it != ']' && it != '\n' && it != '\r' }.trim().take(LINK_MAX_CHARS)

    /** A link for the dialogue layer to put in a line: [code] is a key of [LINK_COLOURS] (any other becomes the epic purple). */
    fun link(name: String, code: Char = 'i'): String {
        val n = escapeName(name)
        if (n.isEmpty()) return ""
        return if (code == 'i' || !LINK_COLOURS.containsKey(code)) "[$n]" else "[[$code:$n]]"
    }
}
