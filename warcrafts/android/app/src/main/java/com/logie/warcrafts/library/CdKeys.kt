// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * CD keys in a text file that came with the game (Warcraft III asks for its keys on its first
 * start): what the game menu offers to type. Plain Kotlin, unit-tested.
 */
object CdKeys {
    /** A key with a label for it: the text before it on its line (like "Reign of Chaos:"), if any. */
    data class Found(val label: String, val key: String)

    /** Groups of letters and digits joined by dashes (XXXX-XXXX-...), or 26 in a row (Warcraft III's). */
    private val KEY = Regex("(?<![A-Za-z0-9-])([A-Za-z0-9]{4,8}(?:-[A-Za-z0-9]{4,8}){2,7}|[A-Za-z0-9]{26})(?![A-Za-z0-9-])")

    /** File names that hold keys. */
    private val FILE = Regex("(?i).*(serial|cd[ _-]?key|keys?)[^/]*\\.txt")

    fun isKeyFile(name: String): Boolean = FILE.matches(name)

    fun find(text: String): List<Found> {
        val found = mutableListOf<Found>()
        for (line in text.lines()) {
            for (match in KEY.findAll(line)) {
                val key = match.groupValues[1]
                // A 26-character run must mix letters and digits, like a key does (not a word).
                if ('-' !in key && (key.none { it.isDigit() } || key.none { it.isLetter() })) continue
                val label = line.substring(0, match.range.first).trim().trimEnd(':', '-', '=', '.').trim()
                found += Found(label.take(60), key.uppercase())
            }
        }
        return found.distinctBy { it.key }
    }
}
