package com.logie.dolphinxd.savestate

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CardGuardTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("cardguard").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(path: String, content: String, modified: Long? = null): File =
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content)
            modified?.let { setLastModified(it) }
        }

    // The original game's layout (Dolphin's GC folder) and a mod's layout (XD/Saves/<id>).
    private val gci = "USA/Card A/01-GC6E-pokemon_colosseum.gci"
    private val modGci = "Card A/USA/01-GC6E-pokemon_colosseum.gci"

    @Test
    fun findsGciFoldersAndRawCardsOnly() {
        write(gci, "save")
        write(modGci, "mod save")
        write("MemoryCardA.USA.raw", "raw")
        write("USA/IPL.bin", "bios")
        write("StateSaves/GC6E01.s10", "state")
        write("MemoryCardB.USA.raw", "slot b")
        assertEquals(
            listOf(modGci, "MemoryCardA.USA.raw", gci).sorted(),
            CardGuard.cardFiles(root).map { it.first }
        )
    }

    @Test
    fun fingerprintFollowsCardContentOnly() {
        write(gci, "save 1")
        val first = CardGuard.fingerprint(root)
        write("USA/IPL.bin", "bios")
        write("StateSaves/GC6E01.s10", "state")
        assertEquals(first, CardGuard.fingerprint(root), "Other files don't count")

        write(gci, "save 1", modified = 123_000L)
        assertEquals(first, CardGuard.fingerprint(root), "Rewriting the same save doesn't count")

        write(gci, "save 2")
        assertNotEquals(first, CardGuard.fingerprint(root), "An in-game save changes it")
    }

    @Test
    fun aNewSaveFileChangesTheFingerprint() {
        val empty = CardGuard.fingerprint(root)
        write(gci, "first save")
        assertNotEquals(empty, CardGuard.fingerprint(root))
    }

    @Test
    fun loadsWhileTheCardIsUnchanged() {
        write(gci, "save 1")
        val recorded = CardGuard.record(CardGuard.fingerprint(root), requestedAt = 10_000L)
        assertEquals(true, canLoad(stateTime = 10_200L, recorded))
    }

    @Test
    fun refusesAfterAnInGameSave() {
        write(gci, "save 1")
        val recorded = CardGuard.record(CardGuard.fingerprint(root), requestedAt = 10_000L)
        write(gci, "save 2")
        assertEquals(false, canLoad(stateTime = 10_200L, recorded))
    }

    @Test
    fun refusesAfterAnInGameSaveThatIsNotOnDiskYet() {
        write(gci, "save 1")
        val recorded = CardGuard.record(CardGuard.fingerprint(root), requestedAt = 10_000L)
        // The game saved at 30 s, but Dolphin hasn't flushed it to the card file yet.
        assertEquals(false, canLoad(stateTime = 10_200L, recorded, lastGameWrite = 30_000L))
        assertEquals(true, canLoad(stateTime = 10_200L, recorded, lastGameWrite = 9_000L))
    }

    @Test
    fun noStateMeansNothingToLoad() {
        assertNull(canLoad(stateTime = 0L, recorded = null))
    }

    @Test
    fun ignoresTheRecordWhenTheSaveNeverHappened() {
        // An old state from before an in-game save, and a newer record whose save state failed.
        write(gci, "save 2", modified = 50_000L)
        val recorded = CardGuard.record(CardGuard.fingerprint(root), requestedAt = 60_000L)
        assertEquals(false, canLoad(stateTime = 20_000L, recorded), "Older than the in-game save")
    }

    @Test
    fun statesWithoutARecordMustBeNewerThanTheLastCardWrite() {
        write(gci, "save", modified = 50_000L)
        assertEquals(true, canLoad(stateTime = 60_000L, recorded = null))
        assertEquals(false, canLoad(stateTime = 40_000L, recorded = null))
        assertEquals(false, canLoad(stateTime = 60_000L, recorded = null, lastGameWrite = 70_000L))
    }

    private fun canLoad(stateTime: Long, recorded: String?, lastGameWrite: Long = 0L): Boolean? =
        CardGuard.canLoad(
            stateTime = stateTime,
            recorded = recorded,
            lastGameWrite = lastGameWrite,
            currentCard = { CardGuard.fingerprint(root) },
            newestCardWrite = { CardGuard.newestWrite(root) },
        )
}
