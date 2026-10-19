// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.reports

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * A time anomaly report, as Wrath Remix wrote it when the player tapped Report Time Anomaly in the
 * game's menu (mod-wrath-remix, RemixReport.cpp): Markdown, its first line the title ("# ...").
 * The app sends it to GitHub as an issue ([com.logie.warcraft.AnomalyReports]).
 */
data class AnomalyReport(val title: String, val body: String) {
    /** The report with a last line about the app that sent it. */
    fun signed(line: String) = copy(body = body.trimEnd() + "\n\n---\n" + line + "\n")

    /** The report with [marker] ([Companion.marker]) at its start, unseen on GitHub: how its issue is known again. */
    fun marked(marker: String) = copy(body = "<!-- $marker -->\n" + body)

    /**
     * The report cut to [limit] as [measure] counts it: the oldest steps of its way here go first
     * (a line says how many), then, if that's not enough, the end of the body.
     */
    fun fitted(limit: Int, measure: (String) -> Int = { it.length }): AnomalyReport {
        if (measure(body) <= limit) return this
        var text = body
        val lines = body.lines()
        val heading = lines.indexOfFirst { it.trim() == WAY_HEADING }
        if (heading >= 0) {
            // The steps: the list under the heading (after its line about them).
            val first = (heading + 1 until lines.size).firstOrNull { lines[it].startsWith("- ") } ?: lines.size
            var end = first
            while (end < lines.size && lines[end].startsWith("- ")) end++
            val steps = end - first
            // The fewest left out that fit (a search: fewer left out is longer).
            var low = 1
            var high = steps
            var best: String? = null
            while (low <= high) {
                val cut = (low + high) / 2
                val shorter = (lines.subList(0, first) + "- … $cut earlier ${if (cut == 1) "step" else "steps"} left out" +
                    lines.subList(first + cut, lines.size)).joinToString("\n")
                if (measure(shorter) <= limit) {
                    best = shorter
                    high = cut - 1
                } else {
                    low = cut + 1
                }
            }
            if (best != null) return copy(body = best)
            if (steps > 0) {
                text = (lines.subList(0, first) + "- … all $steps steps left out" + lines.subList(end, lines.size)).joinToString("\n")
            }
        }
        // Still too long: as much of the start as fits.
        var low = 0
        var high = text.length
        while (low < high) {
            val length = (low + high + 1) / 2
            if (measure(text.take(length) + CUT) <= limit) low = length else high = length - 1
        }
        return copy(body = text.take(low) + CUT)
    }

    /** What GitHub's API takes to open the issue ([LABEL] on it). */
    fun issueJson(): String = JSONObject()
        .put("title", title)
        .put("body", fitted(API_LIMIT).body)
        .put("labels", JSONArray().put(LABEL))
        .toString()

    /**
     * github.com's page for a new issue in [repo], filled in with the report: for the player to
     * send themselves when the app can't. Links that long are cut short ([LINK_LIMIT]).
     */
    fun newIssueLink(repo: String): String {
        val start = "https://github.com/$repo/issues/new?labels=${encode(LABEL)}&title=${encode(title)}&body="
        return start + encode(fitted(LINK_LIMIT - start.length) { encode(it).length }.body)
    }

    companion object {
        const val LABEL = "time anomaly"
        /** The heading of the report's way here: the steps the app may leave out. */
        const val WAY_HEADING = "### The way here"
        /** GitHub takes 65536 characters in an issue's body. */
        const val API_LIMIT = 60_000
        /** Longer links than this aren't sure to open. */
        const val LINK_LIMIT = 7_500
        private const val CUT = "\n\n… (cut short)"

        /** The report a file holds, or null for one that isn't (empty, or no title). */
        fun parse(text: String): AnomalyReport? {
            val firstLine = text.lineSequence().firstOrNull()?.trim() ?: return null
            if (!firstLine.startsWith("# ")) return null
            val title = firstLine.removePrefix("# ").trim()
            if (title.isEmpty()) return null
            return AnomalyReport(title, text.substringAfter('\n', "").trim('\n') + "\n")
        }

        /** The issue GitHub's API says it opened: its number and page. */
        fun openedIssue(answer: String): Pair<Int, String>? = runCatching {
            val json = JSONObject(answer)
            json.getInt("number") to json.getString("html_url")
        }.getOrNull()

        /**
         * What marks the issue of the report a file holds ([marked]): the file's name and a hash of
         * what's in it, the same at every try.
         */
        fun marker(name: String, text: String) =
            "time anomaly report $name ${text.hashCode().toUInt().toString(16).padStart(8, '0')}"

        /** The issue in GitHub's list of issues ([answer]) that has [marker] in it: its number and page. */
        fun markedIssue(answer: String, marker: String): Pair<Int, String>? = runCatching {
            val issues = JSONArray(answer)
            (0 until issues.length()).map { issues.getJSONObject(it) }
                .firstOrNull { it.optString("body").contains("<!-- $marker -->") }
                ?.let { it.getInt("number") to it.getString("html_url") }
        }.getOrNull()

        /**
         * GitHub didn't take a report for now, not for good: it's busy (5xx), or the token sent too
         * many requests (a rate limit). It goes again later, with the same token.
         */
        fun busy(code: Int, reason: String?): Boolean =
            code >= 500 || code == 408 || code == 429 || reason?.contains("rate limit", ignoreCase = true) == true

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}
