// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.reports

/**
 * A failure the app met (a realm that wouldn't start, an installation, an import or a game launch
 * that failed, the app itself crashing), as the report the app sends to GitHub: [markdown] makes it
 * a report the app sends as it sends the time anomaly reports ([com.logie.warcraft.AnomalyReports]),
 * with what the app knew about it (its message, the end of its logs).
 *
 * The same failure is sent once a day at most ([key], [due]): a realm that fails at every start
 * makes one issue, not one a minute.
 */
object AppFailure {
    /** The reports' files in the reports' folder start with this; their issues are labelled [LABEL]. */
    const val PREFIX = "failure-"
    const val LABEL = "app failure"

    /** The same failure again within this long isn't sent again. */
    const val REPEAT_MS = 24L * 60 * 60 * 1000

    /** The longest log kept in a report: its end. */
    const val LOG_LIMIT = 20_000

    /** What the player chose (stopping a job) and what was only refused, not failed. */
    fun ignored(message: String): Boolean =
        message.isBlank() || message.contains("cancel", ignoreCase = true)

    /** The message's first line, short: the issue's title. */
    fun summary(message: String): String =
        message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().ifEmpty { "failed" }.take(120)

    /**
     * How the app knows a failure it has sent: where it happened and what it said, its numbers left
     * out (times, ports, sizes and codes change between two of the same failure).
     */
    fun key(source: String, message: String): String =
        "$source ${message.replace(Regex("\\d+"), "#").trim().hashCode().toUInt().toString(16).padStart(8, '0')}"

    /** The failure with [key] isn't sent yet, or was sent [REPEAT_MS] or longer before [now]. */
    fun due(sent: Map<String, Long>, key: String, now: Long): Boolean =
        sent[key]?.let { now - it >= REPEAT_MS || it > now } ?: true

    /** The failures sent ([due]), as the app keeps them: a line each, the key, a tab, the time. */
    fun parseSent(text: String): Map<String, Long> = text.lineSequence().mapNotNull { line ->
        val key = line.substringBeforeLast('\t', "")
        val time = line.substringAfterLast('\t').trim().toLongOrNull()
        if (key.isEmpty() || time == null) null else key to time
    }.toMap()

    /** [sent] as [parseSent] reads it, without the ones older than [REPEAT_MS] at [now]. */
    fun formatSent(sent: Map<String, Long>, now: Long): String =
        sent.filter { now - it.value < REPEAT_MS }.entries.joinToString("") { "${it.key}\t${it.value}\n" }

    /**
     * The report for a failure in [source] (what failed: "SPP Legion 7.3.5 server", "World of
     * Warcraft 3.3.5a import"), saying [message], with [log] (its end, when there is one), at
     * [whenUtc], on [device].
     */
    fun markdown(source: String, message: String, log: String?, whenUtc: String, device: String): String = buildString {
        append("# ").append(source).append(" failed: ").append(fence(summary(message))).append('\n')
        append('\n')
        append("**").append(source).append("** failed at ").append(whenUtc).append(" (UTC) on ").append(device).append(".\n")
        append('\n')
        append("### What the app said\n")
        append("```text\n").append(fence(message).trimEnd()).append("\n```\n")
        val tail = log?.trimEnd()?.takeIf { it.isNotEmpty() }
        if (tail != null) {
            append('\n')
            append("### The log\n")
            val cut = if (tail.length > LOG_LIMIT) "…\n" + tail.takeLast(LOG_LIMIT) else tail
            append("```text\n").append(fence(cut)).append("\n```\n")
        }
    }

    private fun fence(text: String) = text.replace("```", "'''")
}
