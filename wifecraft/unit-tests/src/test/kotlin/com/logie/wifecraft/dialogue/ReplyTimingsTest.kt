package com.logie.wifecraft.dialogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyTimingsTest {
    private fun t(at: Long = 0, firstWord: Long = 4000, generated: Int = 30, decodeMs: Double = 6000.0, read: Int = 120, prefillMs: Double = 4000.0,
        finish: DialogueFinish = DialogueFinish.EOS, tag: String = "chat", prefillOnly: Boolean = false, guarded: Boolean = false, reused: Int = 0, shared: Int = 0, restored: Int = 0) =
        ReplyTiming(atMs = at, npcId = "chat:jaina", tag = tag, priority = DialoguePriority.SELECTED, queueWaitMs = 500, firstWordMs = firstWord, totalMs = firstWord + decodeMs.toLong(),
            finish = finish, promptTokens = read + reused + shared + restored, readTokens = read, reusedTokens = reused, sharedTokens = shared, restoredTokens = restored,
            prefillMs = prefillMs, generated = generated, decodeMs = decodeMs, firstTokenMs = 3500.0, kvTokens = 700, threads = 4, threadsBatch = 6, prefillOnly = prefillOnly, guarded = guarded)

    @Test fun theRingKeepsTheLastHundredAndForgetsTheOldest() {
        val log = TimingLog()
        for (i in 1..130) log.add(t(at = i.toLong()))
        assertEquals(100, log.size)
        assertEquals(31L, log.all().first().atMs, "the oldest 30 are gone")
        assertEquals(130L, log.all().last().atMs)
        assertEquals(130L, log.added, "the count of everything ever added keeps counting")
        assertEquals(listOf(129L, 130L), log.last(2).map { it.atMs })
        log.clear(); assertEquals(0, log.size)
    }

    @Test fun theSpeedsAreWorkedOutFromTheTokensAndTheMilliseconds() {
        val x = t(generated = 30, decodeMs = 6000.0, read = 120, prefillMs = 4000.0)
        assertEquals(5.0, x.decodeTokensPerSecond, 1e-9)
        assertEquals(30.0, x.prefillTokensPerSecond, 1e-9)
        assertEquals(0.0, t(read = 0).prefillTokensPerSecond, "nothing read: no speed to quote")
        assertEquals(0.0, t(generated = 2).decodeTokensPerSecond, "too short to say")
    }

    @Test fun theNpcsPlaceIsClassifiedHitBorrowedRestoredOrMissed() {
        assertEquals("miss", t().slot)
        assertEquals("hit", t(reused = 400).slot)
        assertEquals("borrowed", t(reused = 10, shared = 165).slot)
        assertEquals("restored", t(restored = 300, shared = 100).slot)
    }

    @Test fun lastRepliesIsTheMedianOfTheNewestAnsweredOnes() {
        val log = TimingLog()
        assertEquals("Last replies: none yet.", log.summary().text())
        // a warm-up, a cancelled answer and a guard stop are not replies someone waited for
        log.add(t(tag = "warm", prefillOnly = true, generated = 0))
        log.add(t(finish = DialogueFinish.CANCELLED, generated = 0))
        log.add(t(guarded = true, finish = DialogueFinish.CANCELLED, generated = 4))
        assertEquals(0, log.summary().replies)
        for (fw in listOf(9000L, 3000L, 6000L)) log.add(t(firstWord = fw, generated = 25, decodeMs = 5000.0))   // 5 tok/s
        val s = log.summary()
        assertEquals(3, s.replies)
        assertEquals(6.0, s.firstWordSeconds, 1e-9, "the median, not the mean")
        assertEquals(5.0, s.tokensPerSecond, 1e-9)
        assertEquals("Last replies: first word after 6.0 s, 5.0 tok/s", s.text())
        assertTrue("0.5 s waiting in the queue" in s.detail(), s.detail())
        assertNull(s.hint())
    }

    @Test fun aSentenceStoppedAnswerCountsAsAReplyAndAnErrorDoesNot() {
        assertTrue(t(finish = DialogueFinish.CANCELLED).copy(softStopped = true).isReply)
        assertFalse(t(finish = DialogueFinish.ERROR).isReply)
        assertFalse(t(prefillOnly = true).isReply)
    }

    @Test fun aSlowPhoneGetsTheHint() {
        assertNull(SpeedHint.of(0.0), "no measurement, no hint")
        assertNull(SpeedHint.of(5.2))
        val h = SpeedHint.of(2.4)
        assertNotNull(h)
        assertTrue("slow for this model" in h && "Speed: Auto" in h && "close other apps" in h, h)
        val log = TimingLog()
        repeat(3) { log.add(t(generated = 20, decodeMs = 10_000.0)) }   // 2 tok/s
        assertNotNull(log.summary().hint())
    }

    @Test fun theCopiedTextHasTheHeaderAndALinePerRequest() {
        val log = TimingLog()
        log.add(t(at = 10, reused = 400, shared = 0))
        log.add(t(at = 20, tag = "warm", prefillOnly = true, generated = 0))
        val text = log.toText("Wifecraft dialogue timings\nmodel: mn-12b")
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals("Wifecraft dialogue timings", lines[0])
        assertEquals("requests: 2", lines[2])
        assertEquals(5, lines.size)
        assertTrue("hit" in lines[3] && "first word 4000 ms" in lines[3] && "threads 4/6" in lines[3] && "kv 700" in lines[3], lines[3])
        assertTrue("warm" in lines[4])
    }
}
