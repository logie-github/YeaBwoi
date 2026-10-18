package com.logie.warcraft.reports

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnomalyReportTest {
    /** A report as Wrath Remix writes it (RemixReport.cpp), with [steps] steps on its way here. */
    private fun file(steps: Int) = buildString {
        append("# Time anomaly: Time's Toll (Timerunner, level 10)\n\n")
        append("**What happened:** The instructor wouldn't speak\n\n")
        append("### The Timerunner\n- **Timerunner**, level 10 Human Warrior, Alliance\n\n")
        append("### The quest they're on\n**Time's Toll** (77012), in progress, taken 10-14 18:20 in The Infinite Bazaar from Eternus (770002)\n")
        append("- Spoken to since it was taken: Alurmi (770005) at 18:21\n\n")
        append("${AnomalyReport.WAY_HEADING}\nEvery step the journal has, oldest first (times in UTC):\n")
        for (n in 1..steps) append("- `10-14 18:${"%02d".format(n % 60)}` The Infinite Bazaar: Spoke to Alurmi (770005), step $n\n")
    }

    @Test
    fun aFileIsItsTitleAndBody() {
        val report = AnomalyReport.parse(file(3))!!
        assertEquals("Time anomaly: Time's Toll (Timerunner, level 10)", report.title)
        assertTrue(report.body.startsWith("**What happened:** The instructor wouldn't speak\n"))
        assertTrue(report.body.endsWith("step 3\n"))
        assertNull(AnomalyReport.parse(""))
        assertNull(AnomalyReport.parse("Not a report\n"))
        assertNull(AnomalyReport.parse("# \nNo title"))
    }

    @Test
    fun aShortReportGoesWhole() {
        val report = AnomalyReport.parse(file(5))!!.signed("Sent by the app")
        assertEquals(report, report.fitted(AnomalyReport.API_LIMIT))
        val json = JSONObject(report.issueJson())
        assertEquals(report.title, json.getString("title"))
        assertEquals(report.body, json.getString("body"))
        assertEquals(AnomalyReport.LABEL, json.getJSONArray("labels").getString(0))
        assertTrue(report.body.endsWith("step 5\n\n---\nSent by the app\n"))
    }

    @Test
    fun aLongReportLosesTheOldestStepsFirst() {
        val report = AnomalyReport.parse(file(300))!!.signed("Sent by the app")
        val fitted = report.fitted(4_000)
        assertTrue(fitted.body.length <= 4_000)
        // Everything before the way here, the latest steps and the signature stay; a line says how
        // many of the oldest went.
        assertTrue(fitted.body.startsWith(report.body.substringBefore(AnomalyReport.WAY_HEADING)))
        assertTrue(fitted.body.contains("step 300\n") && fitted.body.endsWith("Sent by the app\n"))
        assertTrue(!fitted.body.contains("step 1\n"))
        val left = Regex("- … (\\d+) earlier steps left out\n").find(fitted.body)!!.groupValues[1].toInt()
        assertTrue(fitted.body.contains("step ${left + 1}\n") && !fitted.body.contains("step $left\n"))
        // The fewest left out: one more step wouldn't fit.
        val lines = report.body.lines()
        val oneMore = (lines.subList(0, lines.indexOfFirst { it.endsWith("step 1") }) + "- … ${left - 1} earlier steps left out" +
            lines.subList(lines.indexOfFirst { it.endsWith("step $left") }, lines.size)).joinToString("\n")
        assertTrue(oneMore.length > 4_000)
    }

    @Test
    fun aReportTooLongEvenWithoutItsStepsIsCutShort() {
        val report = AnomalyReport("Time anomaly", "**What happened:** " + "x".repeat(10_000) + "\n\n${AnomalyReport.WAY_HEADING}\n- step\n")
        val fitted = report.fitted(2_000)
        assertTrue(fitted.body.length <= 2_000)
        assertTrue(fitted.body.endsWith("(cut short)"))
        val noSteps = AnomalyReport("Time anomaly", "y".repeat(5_000))
        assertTrue(noSteps.fitted(1_000).body.length <= 1_000)
    }

    @Test
    fun theBrowsersLinkIsFilledInAndShortEnough() {
        val report = AnomalyReport.parse(file(300))!!
        val link = report.newIssueLink("logie-github/YeaBwoi")
        assertTrue(link.startsWith("https://github.com/logie-github/YeaBwoi/issues/new?labels=time%20anomaly&title=Time%20anomaly%3A%20Time"))
        assertTrue(link.length <= AnomalyReport.LINK_LIMIT, "${link.length}")
        assertTrue(!link.contains('+') && !link.contains(' '))
        val body = URLDecoder.decode(link.substringAfter("&body="), "UTF-8")
        assertTrue(body.startsWith("**What happened:** The instructor wouldn't speak"))
        assertTrue(body.contains("step 300\n") && body.contains("earlier steps left out"))
        assertEquals(link.substringAfter("&body="), URLEncoder.encode(body, "UTF-8").replace("+", "%20"))
    }

    @Test
    fun theIssueGitHubOpened() {
        assertEquals(12 to "https://github.com/o/r/issues/12",
            AnomalyReport.openedIssue("""{"number": 12, "html_url": "https://github.com/o/r/issues/12", "title": "x"}"""))
        assertNull(AnomalyReport.openedIssue("not json"))
    }

    @Test
    fun aReportSentBeforeIsFoundByItsMark() {
        val name = "anomaly-20261014-182000-5.md"
        val text = file(300)
        // The same file: the same mark at every try; another report: another.
        val marker = AnomalyReport.marker(name, text)
        assertEquals(marker, AnomalyReport.marker(name, text))
        assertNotEquals(marker, AnomalyReport.marker(name, file(299)))
        assertNotEquals(marker, AnomalyReport.marker("anomaly-20261014-182001-5.md", text))
        // At the start of the issue, unseen there, however short the report is made.
        val report = AnomalyReport.parse(text)!!.signed("Sent by the app").marked(marker)
        assertTrue(report.fitted(4_000).body.startsWith("<!-- $marker -->\n**What happened:**"))
        val body = JSONObject(report.issueJson()).getString("body")
        assertTrue(body.startsWith("<!-- $marker -->\n"))
        // GitHub's list of issues, with it among them.
        val issues = JSONArray()
            .put(JSONObject().put("number", 14).put("html_url", "https://github.com/o/r/issues/14").put("body", "Another"))
            .put(JSONObject().put("number", 13).put("html_url", "https://github.com/o/r/issues/13").put("body", JSONObject.NULL))
            .put(JSONObject().put("number", 12).put("html_url", "https://github.com/o/r/issues/12").put("body", body))
            .toString()
        assertEquals(12 to "https://github.com/o/r/issues/12", AnomalyReport.markedIssue(issues, marker))
        assertNull(AnomalyReport.markedIssue(issues, AnomalyReport.marker(name, file(299))))
        assertNull(AnomalyReport.markedIssue("[]", marker))
        assertNull(AnomalyReport.markedIssue("not json", marker))
    }

    @Test
    fun aBusyGitHubIsTriedAgainARefusalIsNot() {
        assertTrue(AnomalyReport.busy(502, null))
        assertTrue(AnomalyReport.busy(503, "Service Unavailable"))
        assertTrue(AnomalyReport.busy(429, null))
        assertTrue(AnomalyReport.busy(403, "You have exceeded a secondary rate limit. Please wait a few minutes before you try again."))
        assertTrue(AnomalyReport.busy(403, "API rate limit exceeded for user ID 1."))
        assertFalse(AnomalyReport.busy(403, "Resource not accessible by personal access token"))
        assertFalse(AnomalyReport.busy(401, "Bad credentials"))
        assertFalse(AnomalyReport.busy(404, "Not Found"))
        assertFalse(AnomalyReport.busy(410, "Issues are disabled for this repo"))
        assertFalse(AnomalyReport.busy(422, null))
    }
}
