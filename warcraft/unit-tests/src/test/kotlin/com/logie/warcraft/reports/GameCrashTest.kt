package com.logie.warcraft.reports

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameCrashTest {
    /** A report as the 3.3.5a client writes it in its Errors folder (shortened). */
    private val log = """
        ==============================================================================
        World of WarCraft: Retail Build (build 12340)

        Exe:      D:\Wow.exe
        Time:     Oct 23, 2026  9:41:07.112 PM
        User:     user
        Computer: WINLATOR
        ------------------------------------------------------------------------------

        This application has encountered a critical error:

        ERROR #132 (0x85100084) Fatal Exception
        Program:	D:\Wow.exe
        Exception:	0xC0000005 (ACCESS_VIOLATION) at 0023:004D8A9C

        The instruction at "0x004D8A9C" referenced memory at "0x0000001C".
        The memory could not be "read".
    """.trimIndent()

    @Test
    fun theSummaryIsTheErrorAndTheException() {
        assertEquals("ERROR #132 (0x85100084) Fatal Exception, Exception:\t0xC0000005 (ACCESS_VIOLATION) at 0023:004D8A9C", GameCrash.summary(log))
        assertEquals("the game stopped", GameCrash.summary("\n\n"))
    }

    @Test
    fun theReportIsOneTheAppSends() {
        val text = GameCrash.markdown("2026-10-23 214107.txt", log, "2026-10-23 21:41:07", "Wrath Remix",
            listOf("patch-V.MPQ" to 233_000_000L, "enUS/patch-enUS-Y.MPQ" to 4_096L))
        val report = AnomalyReport.parse(text)!!
        assertTrue(report.title.startsWith("Game crash: ERROR #132"))
        assertTrue("on the Wrath Remix realm" in report.body)
        assertTrue("- `patch-V.MPQ` 233 MB" in report.body)
        assertTrue("- `enUS/patch-enUS-Y.MPQ` 4096 bytes" in report.body)
        assertTrue("referenced memory at \"0x0000001C\"" in report.body)
        assertEquals(GameCrash.LABEL, JSONObject(report.issueJson(GameCrash.LABEL)).getJSONArray("labels").getString(0))
        assertEquals(AnomalyReport.LABEL, JSONObject(report.issueJson()).getJSONArray("labels").getString(0))
        assertTrue("labels=game%20crash" in report.newIssueLink("owner/repo", GameCrash.LABEL) ||
            "labels=game+crash" in report.newIssueLink("owner/repo", GameCrash.LABEL))
    }

    @Test
    fun aFenceInTheLogDoesNotEndTheBlock() {
        val text = GameCrash.markdown("x.txt", "ERROR #1\n```\nafter", "t", "ChromieCraft", emptyList())
        assertEquals(2, Regex("^```", RegexOption.MULTILINE).findAll(text).count())
    }
}
