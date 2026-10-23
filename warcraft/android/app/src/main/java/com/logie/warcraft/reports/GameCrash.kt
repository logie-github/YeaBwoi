// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.reports

/**
 * A crash of the game (the 3.3.5a client), as the report the app sends to GitHub: the game writes
 * its own report when it stops on an error (`Errors\*.txt` in its folder: the error, the exception,
 * where it was, what it had loaded), and [markdown] makes it a report the app sends as it sends the
 * time anomaly reports ([com.logie.warcraft.AnomalyReports]), with the game's patches beside it
 * (a crash on the character screen is most often one of their tables or models).
 */
object GameCrash {
    /** The reports' files in the reports' folder start with this; their issues are labelled [LABEL]. */
    const val PREFIX = "crash-"
    const val LABEL = "game crash"

    /** Reports older than this when the app first looks are left out (crashes from before it looked). */
    const val OLDEST_MS = 3L * 24 * 60 * 60 * 1000

    /** What the game's report says went wrong, in a line: its error, or its exception. */
    fun summary(log: String): String {
        val lines = log.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val error = lines.firstOrNull { it.startsWith("ERROR #") }
        val exception = lines.firstOrNull { it.startsWith("Exception:") }
        return listOfNotNull(error, exception?.takeIf { it != error }).joinToString(", ").ifEmpty { "the game stopped" }.take(160)
    }

    /** How the app knows a report it has seen: its name, size and time. */
    fun key(name: String, size: Long, modified: Long) = "$name $size $modified"

    /**
     * The report for the game's [log] (`Errors\`[name], written at [whenUtc]), on [realm]'s realm, with
     * the game's archives ([patches]: name and size in bytes, as the game's Data folder has them).
     */
    fun markdown(name: String, log: String, whenUtc: String, realm: String, patches: List<Pair<String, Long>>): String = buildString {
        append("# Game crash: ").append(summary(log)).append('\n')
        append('\n')
        append("**The game stopped** at ").append(whenUtc).append(" (UTC), on the ").append(realm).append(" realm.\n")
        append('\n')
        append("### The game's archives\n")
        if (patches.isEmpty()) append("- (none found)\n")
        for ((file, size) in patches) append("- `").append(file).append("` ").append(megabytes(size)).append('\n')
        append('\n')
        append("### The game's report (`Errors\\").append(name).append("`)\n")
        append("```text\n").append(log.replace("```", "'''").trimEnd()).append("\n```\n")
    }

    private fun megabytes(size: Long) = if (size >= 1_000_000) "${size / 1_000_000} MB" else "$size bytes"
}
