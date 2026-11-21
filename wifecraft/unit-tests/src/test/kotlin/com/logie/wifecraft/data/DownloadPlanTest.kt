package com.logie.wifecraft.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadPlanTest {
    private val v = DownloadPlan.version("12.1.7.70892")
    private val GB = 1_000_000_000L

    // ---- the packs ----------------------------------------------------------------------------------------------

    @Test fun packsAreUniqueAndTheBigOnesOptional() {
        assertEquals(PackKey.entries.size, PackKey.entries.map { it.id }.toSet().size)
        assertEquals(setOf(PackKey.TRANSMOG, PackKey.CREATURES, PackKey.PROPS, PackKey.BUILDINGS), PackKey.entries.filter { it.optional }.toSet())
        // the required ones come first: the optional ones are the last of the run
        val firstOptional = PackKey.entries.indexOfFirst { it.optional }
        assertTrue(PackKey.entries.drop(firstOptional).all { it.optional })
        assertEquals(PackKey.BASICS, PackKey.entries.first())
    }

    @Test fun theSelectionAlwaysHoldsTheRequiredPacksInOrder() {
        assertEquals(PackKey.entries.filter { !it.optional }, PackKey.selection(emptySet()))
        assertEquals(PackKey.entries, PackKey.selection(PackKey.entries.toSet()))
        assertEquals(PackKey.entries.filter { !it.optional } + PackKey.PROPS, PackKey.selection(setOf(PackKey.PROPS)))
        assertTrue(PackKey.DEFAULT.containsAll(PackKey.entries.filter { !it.optional }))
    }

    @Test fun estimatesFromAListDropUnknownPacks() {
        val e = DownloadPlan.estimates(mapOf("transmog" to (185_228 to 14_200_937_600L), "nonsense" to (1 to 2L)))
        assertEquals(mapOf(PackKey.TRANSMOG to PackEstimate(185_228, 14_200_937_600L)), e)
    }

    /** assets/files.json carries an estimate for every pack (tools/pack_sizes.py). */
    @Test fun filesJsonEstimatesEveryPack() {
        val assets = System.getProperty("wifecraft.assets") ?: return
        val o = JSONObject(File(assets, "files.json").readText()).getJSONObject("estimates")
        for (p in PackKey.entries) {
            val e = o.getJSONObject(p.id)
            assertTrue(e.getInt("files") > 0 && e.getLong("bytes") > 0, p.id)
        }
        // the places' are exact sums of locations.json's sizes: more than 150 MB, less than 300
        assertTrue(o.getJSONObject("places").getLong("bytes") in 150_000_000L..300_000_000L)
    }

    // ---- a pack's state -----------------------------------------------------------------------------------------

    @Test fun aPacksStateFromItsRecord() {
        assertEquals("Not downloaded", DownloadPlan.view(null, v).label)
        assertEquals("Not downloaded", DownloadPlan.view(PackRecord(PackKey.PROPS, v, 0, 0, 0), v).label)
        assertEquals("Partial 62%", DownloadPlan.view(PackRecord(PackKey.PROPS, v, 1000, 620, 5), v).label)
        assertEquals("Partial 1%", DownloadPlan.view(PackRecord(PackKey.PROPS, v, 100_000, 1, 5), v).label)
        assertEquals("Partial 99%", DownloadPlan.view(PackRecord(PackKey.PROPS, v, 1000, 999, 5), v).label)
        assertEquals("Complete", DownloadPlan.view(PackRecord(PackKey.PROPS, v, 1000, 1000, 5), v).label)
        assertEquals("Needs update", DownloadPlan.view(PackRecord(PackKey.PROPS, "11.0#1", 1000, 1000, 5), v).label)
        assertEquals(PackStatus.NEEDS_UPDATE, DownloadPlan.view(PackRecord(PackKey.PROPS, DownloadPlan.version("12.1.7.70892").dropLast(1) + "0", 9, 9, 9), v).status)
    }

    // ---- sizes --------------------------------------------------------------------------------------------------

    /** A synthetic list: three packs, one complete (its record's exact count wins), one half here, one not begun. */
    private val estimates = mapOf(
        PackKey.BASICS to PackEstimate(16_000, 6 * GB),
        PackKey.PLACES to PackEstimate(2_200, 200_000_000L),
        PackKey.TRANSMOG to PackEstimate(185_000, 14 * GB),
    )
    private val records = mapOf(
        PackKey.BASICS to PackRecord(PackKey.BASICS, v, 16_013, 16_013, 5_900_000_000L),
        PackKey.TRANSMOG to PackRecord(PackKey.TRANSMOG, v, 185_000, 92_500, 7 * GB),
    )

    @Test fun theTotalCountsCompletePacksExactlyAndTheRestByEstimate() {
        val t = DownloadPlan.total(listOf(PackKey.BASICS, PackKey.PLACES, PackKey.TRANSMOG), estimates, records, v)
        assertEquals(16_013 + 2_200 + 185_000, t.files)
        assertEquals(5_900_000_000L + 200_000_000L + 14 * GB, t.bytes)
        assertFalse(t.exact)
        assertTrue(DownloadPlan.total(listOf(PackKey.BASICS), estimates, records, v).exact)
    }

    @Test fun whatIsStillToComeLeavesOutWhatIsHere() {
        assertEquals(0L, DownloadPlan.remaining(estimates[PackKey.BASICS], records[PackKey.BASICS], v))
        assertEquals(7 * GB, DownloadPlan.remaining(estimates[PackKey.TRANSMOG], records[PackKey.TRANSMOG], v))
        assertEquals(200_000_000L, DownloadPlan.remaining(estimates[PackKey.PLACES], null, v))
        // a record of another version counts for nothing
        assertEquals(14 * GB, DownloadPlan.remaining(estimates[PackKey.TRANSMOG], records[PackKey.TRANSMOG]!!.copy(version = "old"), v))
        assertEquals(0L, DownloadPlan.remaining(null, null, v))
    }

    @Test fun sizesAreSaidPlainly() {
        assertEquals("about 18 GB: Wi-Fi recommended", ByteSize.totalLine(18 * GB, exact = false))
        assertEquals("1.4 GB: Wi-Fi recommended", ByteSize.totalLine(1_400_000_000L, exact = true))
        assertEquals("about 9.0 MB", ByteSize.totalLine(9_000_000L, exact = false))
        assertEquals("640 MB", ByteSize.format(640_000_000L))
        assertEquals("12 KB", ByteSize.format(12_345L))
        assertEquals("56 GB", ByteSize.format(55_700_000_000L))
        assertEquals("0 bytes", ByteSize.format(-5))
        assertEquals("185,228", ByteSize.count(185_228))
    }

    // ---- free space -----------------------------------------------------------------------------------------------

    @Test fun itFitsWhenTheFreeSpaceHoldsAllAndTheReserve() {
        val needs = mapOf(PackKey.BASICS to 6 * GB, PackKey.TRANSMOG to 14 * GB)
        assertEquals(SpaceCheck.Fits, SpaceCheck.check(21 * GB, needs))
        assertIs<SpaceCheck.TooBig>(SpaceCheck.check(20 * GB, needs))
    }

    @Test fun tooLittleRoomOffersTheRequiredPacksAndTheSmallOptionalOnes() {
        val needs = mapOf(PackKey.BASICS to 6 * GB, PackKey.PLACES to GB / 5, PackKey.TRANSMOG to 14 * GB,
            PackKey.PROPS to 4 * GB, PackKey.BUILDINGS to 22 * GB)
        val r = assertIs<SpaceCheck.TooBig>(SpaceCheck.check(15 * GB, needs))
        assertEquals(setOf(PackKey.BASICS, PackKey.PLACES, PackKey.PROPS), r.fits)
        assertEquals(46 * GB + GB / 5 + SpaceCheck.RESERVE_BYTES, r.needed)
        assertEquals(listOf(PackKey.TRANSMOG, PackKey.BUILDINGS), r.leftOut(needs.keys))
        val msg = SpaceCheck.message(r, needs.keys)
        assertTrue("Not enough room" in msg && "15 GB is free" in msg && "Without Transmog items (large), Library buildings (huge) it fits" in msg, msg)
    }

    @Test fun whenNotEvenTheRequiredPacksFitNothingIsOffered() {
        val r = assertIs<SpaceCheck.TooBig>(SpaceCheck.check(3 * GB, mapOf(PackKey.BASICS to 6 * GB, PackKey.PROPS to GB)))
        assertTrue(r.fits.isEmpty())
        assertTrue(SpaceCheck.message(r, listOf(PackKey.BASICS, PackKey.PROPS)).endsWith("Free some space first."))
    }

    // ---- the state machine ------------------------------------------------------------------------------------------

    @Test fun aRunGoesThroughDownloadingVerifyingToComplete() {
        var p = Phase.IDLE
        for ((e, want) in listOf(
            DownloadEvent.START to Phase.DOWNLOADING,
            DownloadEvent.NO_WIFI to Phase.WAITING_FOR_WIFI,
            DownloadEvent.WIFI_BACK to Phase.DOWNLOADING,
            DownloadEvent.PAUSE to Phase.PAUSED,
            DownloadEvent.RESUME to Phase.DOWNLOADING,
            DownloadEvent.FINISHED to Phase.VERIFYING,
            DownloadEvent.VERIFIED to Phase.COMPLETE,
        )) { p = DownloadMachine.next(p, e); assertEquals(want, p, "after $e") }
    }

    @Test fun failuresWaitForRetryAndCancelAlwaysStops() {
        var p = DownloadMachine.next(Phase.DOWNLOADING, DownloadEvent.FINISHED_WITH_FAILURES)
        assertEquals(Phase.FAILED, p)
        p = DownloadMachine.next(p, DownloadEvent.RESUME)
        assertEquals(Phase.DOWNLOADING, p)
        assertEquals(Phase.FAILED, DownloadMachine.next(Phase.VERIFYING, DownloadEvent.VERIFIED_WITH_FAILURES))
        assertEquals(Phase.FAILED, DownloadMachine.next(Phase.WAITING_FOR_WIFI, DownloadEvent.ERROR))
        for (ph in Phase.entries) assertEquals(Phase.IDLE, DownloadMachine.next(ph, DownloadEvent.CANCEL))
    }

    @Test fun eventsThatDontApplyChangeNothing() {
        assertEquals(Phase.IDLE, DownloadMachine.next(Phase.IDLE, DownloadEvent.PAUSE))
        assertEquals(Phase.COMPLETE, DownloadMachine.next(Phase.COMPLETE, DownloadEvent.FINISHED))
        assertEquals(Phase.PAUSED, DownloadMachine.next(Phase.PAUSED, DownloadEvent.NO_WIFI))
        assertEquals(Phase.DOWNLOADING, DownloadMachine.next(Phase.DOWNLOADING, DownloadEvent.START))
        assertEquals(Phase.VERIFYING, DownloadMachine.next(Phase.COMPLETE, DownloadEvent.VERIFY))
        assertEquals(Phase.DOWNLOADING, DownloadMachine.next(Phase.DOWNLOADING, DownloadEvent.VERIFY))
    }

    @Test fun onlyARunThatWasGoingIsTakenUpAfterARestart() {
        assertEquals(setOf(Phase.DOWNLOADING, Phase.WAITING_FOR_WIFI, Phase.VERIFYING), Phase.entries.filter { DownloadMachine.resumesAfterRestart(it) }.toSet())
    }

    @Test fun thePagesButtonsFollowThePhase() {
        assertEquals(listOf(PageAction.DOWNLOAD_EVERYTHING), DownloadPage.actions(Phase.IDLE, 0, anyHere = false))
        assertEquals(listOf(PageAction.DOWNLOAD_EVERYTHING, PageAction.VERIFY), DownloadPage.actions(Phase.IDLE, 0, anyHere = true))
        assertEquals(listOf(PageAction.PAUSE, PageAction.CANCEL), DownloadPage.actions(Phase.WAITING_FOR_WIFI, 3, true))
        assertEquals(listOf(PageAction.RESUME, PageAction.CANCEL), DownloadPage.actions(Phase.PAUSED, 0, true))
        assertEquals(listOf(PageAction.RETRY_FAILED, PageAction.VERIFY), DownloadPage.actions(Phase.FAILED, 7, true))
        assertEquals(listOf(PageAction.VERIFY, PageAction.DOWNLOAD_EVERYTHING), DownloadPage.actions(Phase.COMPLETE, 0, true))
        assertEquals("Downloading: Library props (very large) (9 of 10)", DownloadPage.title(Phase.DOWNLOADING, PackKey.PROPS, 9, 10))
        assertEquals("Everything is here", DownloadPage.title(Phase.COMPLETE, null, 0, 0))
    }

    // ---- the failed files -------------------------------------------------------------------------------------------

    @Test fun aFailedFileIsTriedAgainAfterADoublingWait() {
        var now = 0L
        val q = FailedQueue { now }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L), (1..4).map { FailedQueue.backoffMs(it) })
        assertEquals(FailedQueue.MAX_MS, FailedQueue.backoffMs(40))
        q.failed(7, PackKey.PROPS, "wago.tools answered 503")
        assertTrue(q.due().isEmpty())
        assertEquals(30_000L, q.nextDueIn())
        now = 30_000
        assertEquals(listOf(7), q.due())
        q.failed(7, null, "again")
        assertEquals(PackKey.PROPS, q.list().single().pack)   // the pack is kept
        assertEquals(2, q.list().single().attempts)
        now += 60_000
        assertEquals(listOf(7), q.due())
        q.succeeded(7)
        assertTrue(q.isEmpty())
        assertNull(q.nextDueIn())
    }

    @Test fun afterTheAutomaticTriesItWaitsForRetryFailed() {
        var now = 0L
        val q = FailedQueue { now }
        repeat(FailedQueue.MAX_AUTO + 1) { q.failed(9, PackKey.TRANSMOG, "timeout"); now += FailedQueue.MAX_MS }
        q.failed(10, PackKey.TRANSMOG, "wago.tools has no file 10", permanent = true)
        now += FailedQueue.MAX_MS
        assertTrue(q.due().isEmpty())
        assertNull(q.nextDueIn())
        assertEquals(2, q.size)
        q.retryAll()
        assertEquals(setOf(9, 10), q.due().toSet())
        assertTrue(q.list().all { it.attempts == 0 && !it.permanent })
    }

    // ---- what survives a restart ---------------------------------------------------------------------------------------

    @Test fun theStateIsKeptAndReadBack() {
        val s = SavedState(
            phase = Phase.WAITING_FOR_WIFI, chosen = setOf(PackKey.TRANSMOG, PackKey.PROPS), mobileData = true, prebuild = true,
            current = PackKey.TRANSMOG, records = records,
            failed = listOf(FailedQueue.Entry(123, PackKey.TRANSMOG, "wago.tools answered 503\nfor file 123", 2, 99_000L),
                FailedQueue.Entry(456, null, "no such file", 1, 5L, permanent = true)),
            message = "Paused.\nFiles already here are kept.", stoppedBySystem = true, only = setOf(PackKey.PROPS),
        )
        val back = SavedState.decode(s.encode())
        assertEquals(s.copy(message = "Paused. Files already here are kept.",
            failed = s.failed.map { it.copy(reason = it.reason.replace('\n', ' ')) }), back)
        assertEquals(listOf(PackKey.PROPS), back.packs)
        assertEquals(PackKey.selection(back.chosen), back.copy(only = null).packs)
    }

    @Test fun aMissingOrDamagedStateFileGivesWhatCanBeRead() {
        assertEquals(SavedState(), SavedState.decode(null))
        assertEquals(SavedState(), SavedState.decode("something else\nphase DOWNLOADING"))
        val s = SavedState.decode("${SavedState.HEADER}\nphase NOPE\nphase PAUSED\nrecord props v 1\nrecord places $v 10 4 400 0\nfailed x\nchosen props,bogus\n")
        assertEquals(Phase.PAUSED, s.phase)
        assertEquals(setOf(PackKey.PROPS), s.chosen)
        assertEquals(mapOf(PackKey.PLACES to PackRecord(PackKey.PLACES, v, 10, 4, 400)), s.records)
        assertTrue(s.failed.isEmpty())
    }

    // ---- verify ---------------------------------------------------------------------------------------------------------

    @Test fun aPacksFileListFindsTheDamagedFiles() {
        val list = linkedMapOf(1 to 100L, 2 to 0L, 3 to 50L, 4 to 7L)
        val back = PackFiles.decode(PackFiles.encode(list) + "garbage\n5\n")
        assertEquals(list + (5 to 0L), back)
        val onPhone = mapOf(1 to 100L, 2 to 33L, 3 to 49L)        // 3 cut short, 4 missing, 5 missing
        assertEquals(listOf(3, 4, 5), PackFiles.damaged(back) { onPhone[it] ?: 0L })
        assertTrue(PackFiles.whole(10, 0))
        assertFalse(PackFiles.whole(0, 0))
    }
}
