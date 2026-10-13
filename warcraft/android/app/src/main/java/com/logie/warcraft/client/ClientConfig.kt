// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * The 3.3.5a client's own settings, set before each start so it goes to the realm on this phone:
 *
 * - `Data/<locale>/realmlist.wtf`: `set realmlist <host>`, where the client logs in.
 * - `WTF/Config.wtf`: lines of `SET name "value"`, which the client also writes (its options);
 *   [updateConfig] changes the lines the app sets and keeps the rest.
 */
object ClientConfig {
    private val SET_LINE = Regex("""^\s*SET\s+(\S+)\s+"(.*)"\s*$""", RegexOption.IGNORE_CASE)

    /** The client's locale folders in `Data/` (enUS, deDE...). */
    val LOCALE = Regex("[a-z]{2}[A-Z]{2}")

    fun realmlist(host: String) = "set realmlist $host\r\n"

    /**
     * [text] (a Config.wtf, or "" for a new one) with each of [values] set: in place where the
     * setting is already (names are case-insensitive, like the client's), at the end where not.
     */
    fun updateConfig(text: String, values: Map<String, String>): String {
        val newline = if ("\r\n" in text || text.isEmpty()) "\r\n" else "\n"
        val wanted = values.mapKeys { it.key.lowercase() }
        val seen = mutableSetOf<String>()
        val lines = text.lines().dropLastWhile { it.isBlank() }.map { line ->
            val match = SET_LINE.matchEntire(line) ?: return@map line
            val name = match.groupValues[1]
            val value = wanted[name.lowercase()] ?: return@map line
            seen += name.lowercase()
            "SET $name \"$value\""
        }
        val added = values.filterKeys { it.lowercase() !in seen }.map { (name, value) -> "SET $name \"$value\"" }
        return (lines + added).joinToString(newline, postfix = newline)
    }

    /**
     * The game's resolution for a screen of [width] x [height] pixels, in landscape: [lines] high
     * (the client draws its interface for that height) and as wide as the screen's shape, even.
     * The client needs at least 800x600.
     */
    fun resolution(width: Int, height: Int, lines: Int = 720): Pair<Int, Int> {
        val long = maxOf(width, height).coerceAtLeast(1)
        val short = minOf(width, height).coerceAtLeast(1)
        val wide = (lines.toLong() * long / short).toInt() and 1.inv()
        return maxOf(wide, 800) to maxOf(lines, 600)
    }

    /**
     * The game's resolution for a choice of [lines] (null: the screen's own pixels, every one of
     * them), never more than the screen has.
     */
    fun resolutionFor(width: Int, height: Int, lines: Int?): Pair<Int, Int> {
        val short = minOf(width, height).coerceAtLeast(1)
        return resolution(width, height, minOf(lines ?: short, short) and 1.inv())
    }
}
