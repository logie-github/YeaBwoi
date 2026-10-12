// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.logs

/** The servers' console output, readable: AzerothCore colours its lines with ANSI escape codes. */
object ConsoleText {
    private val ESCAPES = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    fun plain(text: String): String = ESCAPES.replace(text, "")
}

/**
 * Looks for a line in a log that's still being written, fed each piece of it as it's read. Every
 * line is looked at, however many come at once (a check of the log's last lines misses one that
 * others have already followed), including a line that arrives in two pieces.
 */
class LineWatch(private val pattern: Regex) {
    /** The end of the log after its last line break, not looked at yet. */
    private var partial = ""

    var found = false
        private set

    /** Takes the next piece of the log; true once a line matching the pattern has come. */
    fun feed(text: String): Boolean {
        if (found || text.isEmpty()) return found
        val all = partial + text
        val end = all.lastIndexOf('\n')
        val lines = if (end >= 0) all.substring(0, end) else ""
        partial = all.substring(end + 1).takeLast(MAX_PARTIAL)
        found = ConsoleText.plain(lines).lineSequence().any { pattern.containsMatchIn(it) } ||
            pattern.containsMatchIn(ConsoleText.plain(partial))
        return found
    }

    private companion object {
        /** A line longer than this without a break isn't a line the pattern is looking for. */
        const val MAX_PARTIAL = 4096
    }
}
