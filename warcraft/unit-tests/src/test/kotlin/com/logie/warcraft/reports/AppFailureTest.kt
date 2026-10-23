package com.logie.warcraft.reports

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppFailureTest {
    private val message = "The Windows session ended while starting database.\ndatabase.log:\nInnoDB: Error: unable to create temporary file; errno: 2"

    @Test
    fun reportIsAnIssue() {
        val text = AppFailure.markdown("SPP Legion 7.3.5 server", message, "line 1\nline 2", "2026-10-23 21:41:07", "Pixel 9 (Android 16)")
        val report = assertNotNull(AnomalyReport.parse(text))
        assertEquals("SPP Legion 7.3.5 server failed: The Windows session ended while starting database.", report.title)
        assertTrue("**SPP Legion 7.3.5 server** failed at 2026-10-23 21:41:07 (UTC) on Pixel 9 (Android 16)." in report.body)
        assertTrue("InnoDB: Error: unable to create temporary file" in report.body)
        assertTrue("### The log\n```text\nline 1\nline 2\n```" in report.body)
        val json = JSONObject(report.issueJson(AppFailure.LABEL))
        assertEquals(AppFailure.LABEL, json.getJSONArray("labels").getString(0))
    }

    @Test
    fun noLogNoLogSection() {
        val text = AppFailure.markdown("Game", "It stopped", null, "t", "d")
        assertFalse("### The log" in text)
        assertFalse("### The log" in AppFailure.markdown("Game", "It stopped", "  \n", "t", "d"))
    }

    @Test
    fun longLogsKeepTheirEnd() {
        val log = (1..10_000).joinToString("\n") { "line $it" }
        val text = AppFailure.markdown("Game", "It stopped", log, "t", "d")
        assertTrue("line 10000" in text)
        assertFalse("\nline 1\n" in text)
        assertTrue(text.length < AppFailure.LOG_LIMIT + 1_000)
    }

    @Test
    fun fencesInTextDontEndTheBlock() {
        val text = AppFailure.markdown("Game", "a ``` b", "c ``` d", "t", "d")
        assertEquals(4, Regex("```").findAll(text).count())
    }

    @Test
    fun sameFailureSameKeyWhateverItsNumbers() {
        val a = AppFailure.key("SPP server", "database failed with code 137 on port 3312")
        val b = AppFailure.key("SPP server", "database failed with code 1 on port 3313")
        assertEquals(a, b)
        assertNotEquals(a, AppFailure.key("SPP server", "world failed with code 1"))
        assertNotEquals(a, AppFailure.key("Turtle WoW server", "database failed with code 137 on port 3312"))
    }

    @Test
    fun sentOnceADay() {
        val now = 10L * AppFailure.REPEAT_MS
        val sent = mapOf("a" to now - 1_000, "b" to now - AppFailure.REPEAT_MS, "c" to now + 60_000)
        assertFalse(AppFailure.due(sent, "a", now))
        assertTrue(AppFailure.due(sent, "b", now))
        // (a clock set back: sent again rather than never)
        assertTrue(AppFailure.due(sent, "c", now))
        assertTrue(AppFailure.due(sent, "d", now))
    }

    @Test
    fun sentListRoundTripsAndForgetsOldOnes() {
        val now = 10L * AppFailure.REPEAT_MS
        val sent = mapOf("SPP server 0a1b2c3d" to now - 5, "old 00000000" to now - AppFailure.REPEAT_MS - 1)
        val text = AppFailure.formatSent(sent, now)
        assertEquals(mapOf("SPP server 0a1b2c3d" to now - 5), AppFailure.parseSent(text))
        assertEquals(emptyMap(), AppFailure.parseSent("broken\n\tx\nno-time\t\n"))
    }

    @Test
    fun cancellingIsNotAFailure() {
        assertTrue(AppFailure.ignored("Installation cancelled. Partial downloads are kept for Resume."))
        assertTrue(AppFailure.ignored("Another installation is running. Finish or cancel it before starting this installation."))
        assertTrue(AppFailure.ignored(" "))
        assertFalse(AppFailure.ignored("The Windows session ended while starting database."))
    }

    @Test
    fun summaryIsTheFirstLine() {
        assertEquals("first", AppFailure.summary("\n  first  \nsecond"))
        assertEquals("failed", AppFailure.summary(""))
        assertEquals(120, AppFailure.summary("x".repeat(500)).length)
    }
}
