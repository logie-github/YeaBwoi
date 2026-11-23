package com.logie.wifecraft.dialogue

/**
 * The fourth-wall and anachronism checks on an answer while it is still being written, so a wrong one is stopped after its first
 * words and asked again at once, instead of being read to its end and then asked again (which doubled the wait). It sees what the
 * player would see: actions in asterisks, brackets and parentheses are not speech (the cleaners take them out), and a word still
 * being written ("ok" of "okay") is not judged until it is finished. The same words as the final checks ([Anachronisms],
 * [JoiPrompt.breaksWorld]); the live-vocalisation filter is not here because it rewrites a line and keeps what is left.
 */
object StreamGuard {
    /** The speech in [text] that is settled: no closed or open action, no word still being written. */
    fun settled(text: String): String {
        var s = text.replace(Regex("\\*[^*]*\\*|\\([^)]*\\)|\\[[^\\]]*]"), " ")
        val open = s.indexOfFirst { it == '*' || it == '(' || it == '[' }
        if (open >= 0) s = s.substring(0, open)
        var end = s.length
        while (end > 0 && (s[end - 1].isLetterOrDigit() || s[end - 1] == '\'' || s[end - 1] == '-')) end--
        return s.substring(0, end)
    }

    /** What is wrong with [text] so far: [WALL] (it names something beyond the world), an anachronistic word, or null. */
    fun problem(text: String): String? {
        val s = settled(text)
        if (s.isEmpty()) return null
        if (JoiPrompt.breaksWorld(s)) return WALL
        return Anachronisms.find(s)
    }

    /** The marker [problem] gives for a fourth-wall break. */
    const val WALL = "\u0000wall"

    /** The words that ask the model to speak as Azeroth does after [problem] found [what] (the retry's note). */
    fun note(what: String): String =
        if (what == WALL) "Speak only as a person of Azeroth in the room with them would; mention nothing beyond this world."
        else "Speak as people of Azeroth do; do not use the word \"$what\" or anything like it."
}
