package com.logie.warcraft.turtle.core

import com.logie.warcraft.client.ZipDownload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Makes torrents the way OctoWoW's is made (a folder, BEP 19 web seeds, optional BEP 47 padding). */
internal object Torrents {
    class File(val path: String, val bytes: ByteArray, val padding: Boolean = false)

    fun bencode(value: Any, out: ByteArrayOutputStream = ByteArrayOutputStream()): ByteArray {
        when (value) {
            is Long -> out.write("i${value}e".toByteArray())
            is Int -> out.write("i${value}e".toByteArray())
            is String -> bencode(value.toByteArray(), out)
            is ByteArray -> {
                out.write("${value.size}:".toByteArray())
                out.write(value)
            }
            is List<*> -> {
                out.write('l'.code)
                value.forEach { bencode(it!!, out) }
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                for (key in value.keys.map { it as String }.sorted()) {
                    bencode(key, out)
                    bencode(value[key]!!, out)
                }
                out.write('e'.code)
            }
            else -> error("Can't bencode $value")
        }
        return out.toByteArray()
    }

    fun info(files: List<File>, pieceLength: Int, name: String = "client"): Map<String, Any> {
        val stream = ByteArrayOutputStream()
        files.forEach { stream.write(it.bytes) }
        val all = stream.toByteArray()
        val pieces = ByteArrayOutputStream()
        var at = 0
        while (at < all.size) {
            val end = minOf(at + pieceLength, all.size)
            pieces.write(MessageDigest.getInstance("SHA-1").digest(all.copyOfRange(at, end)))
            at = end
        }
        return mapOf(
            "name" to name,
            "piece length" to pieceLength.toLong(),
            "pieces" to pieces.toByteArray(),
            "files" to files.map { file ->
                buildMap<String, Any> {
                    put("length", file.bytes.size.toLong())
                    put("path", file.path.split('/'))
                    if (file.padding) put("attr", "p")
                }
            },
        )
    }

    fun make(files: List<File>, pieceLength: Int = 4096, seeds: Any = "https://dl.example/client/"): ByteArray =
        bencode(mapOf("announce" to "udp://tracker.example:1337/announce", "info" to info(files, pieceLength), "url-list" to seeds))

    fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed * 7) % 251).toByte() }
}

class ClientTorrentTest {
    @Test
    fun readsTheFilesPiecesAndSeeds() {
        val files = listOf(
            Torrents.File("Data/patch-3.mpq", Torrents.bytes(10_000, 1)),
            Torrents.File("WoW.exe", Torrents.bytes(5_000, 2)),
            Torrents.File("Interface/AddOns/Blizzard_RaidUI/Blizzard_RaidUI.pub", Torrents.bytes(257, 3)),
        )
        val bytes = Torrents.make(files, pieceLength = 4096, seeds = listOf("https://dl.example/client/", "http://plain.example/"))
        val torrent = ClientTorrent.parse(bytes)
        assertEquals("client", torrent.name)
        assertEquals(4096L, torrent.pieceLength)
        assertEquals(listOf("Data/patch-3.mpq", "WoW.exe", "Interface/AddOns/Blizzard_RaidUI/Blizzard_RaidUI.pub"), torrent.files.map { it.path })
        assertEquals(listOf(0L, 10_000L, 15_000L), torrent.files.map { it.offset })
        assertEquals(15_257L, torrent.totalSize)
        assertEquals(4, torrent.pieces.size)
        // Plain HTTP seeds are left out.
        assertEquals(listOf("https://dl.example/client/"), torrent.webSeeds)
        // The info dictionary's own SHA-1, as BitTorrent clients name the torrent.
        val info = Torrents.bencode(Torrents.info(files, 4096))
        assertEquals(MessageDigest.getInstance("SHA-1").digest(info).joinToString("") { "%02x".format(it) }, torrent.infoHash)
        assertEquals(0..2, torrent.piecesOf(torrent.files[0]))
        assertEquals(listOf("Data/patch-3.mpq", "WoW.exe"), torrent.filesOf(2).map { it.path })
        assertEquals("https://dl.example/client/client/Data/patch-3.mpq", torrent.url(torrent.webSeeds[0], torrent.files[0]))
    }

    @Test
    fun encodesTheWebSeedsPaths() {
        val torrent = ClientTorrent.parse(Torrents.make(listOf(Torrents.File("Data/Interface/Cinematics/WOW Intro.avi", ByteArray(3)))))
        assertEquals("https://dl.example/client/client/Data/Interface/Cinematics/WOW%20Intro.avi", torrent.url("https://dl.example/client/", torrent.files[0]))
    }

    @Test
    fun refusesPathsThatLeadOutAndBrokenTorrents() {
        for (bad in listOf("../outside.dll", "Data/../../x", "C:/Windows/x.dll")) {
            assertFailsWith<Exception>(bad) { ClientTorrent.parse(Torrents.make(listOf(Torrents.File(bad, ByteArray(10))))) }
        }
        // The same file twice (Windows would see one).
        assertFailsWith<IOException> {
            ClientTorrent.parse(Torrents.make(listOf(Torrents.File("WoW.exe", ByteArray(10)), Torrents.File("wow.EXE", ByteArray(10)))))
        }
        // Pieces that don't cover the files.
        val info = Torrents.info(listOf(Torrents.File("WoW.exe", ByteArray(10_000))), 4096).toMutableMap()
        info["pieces"] = ByteArray(40)
        assertFailsWith<IOException> { ClientTorrent.parse(Torrents.bencode(mapOf("info" to info))) }
        assertFailsWith<IOException> { ClientTorrent.parse("<html>not a torrent</html>".toByteArray()) }
        assertFailsWith<IOException> { ClientTorrent.parse("d4:infoi1e".toByteArray()) }
    }

    @Test
    fun fileIdsChangeWithTheContent() {
        fun torrent(vararg files: Torrents.File) = ClientTorrent.parse(Torrents.make(files.toList()))
        val a = torrent(Torrents.File("Data/a.mpq", Torrents.bytes(8192, 1)), Torrents.File("Data/b.mpq", Torrents.bytes(9000, 2)))
        val b = torrent(Torrents.File("Data/a.mpq", Torrents.bytes(8192, 1)), Torrents.File("Data/b.mpq", Torrents.bytes(9000, 3)))
        assertEquals(a.fileId(a.files[0]), b.fileId(b.files[0]))
        assertFalse(a.fileId(a.files[1]) == b.fileId(b.files[1]))
        // A piece shared with the next file: a change there counts for both (it can't be told apart).
        val c = torrent(Torrents.File("Data/a.mpq", Torrents.bytes(9000, 1)), Torrents.File("Data/b.mpq", Torrents.bytes(9000, 2)))
        val d = torrent(Torrents.File("Data/a.mpq", Torrents.bytes(9000, 1)), Torrents.File("Data/b.mpq", Torrents.bytes(9000, 3)))
        assertFalse(c.fileId(c.files[0]) == d.fileId(d.files[0]))
    }
}

class ClientSyncTest {
    /** A web seed in memory: counts what it serves, can drop a connection or serve wrong bytes. */
    private class Seed(files: List<Torrents.File>) {
        val content = files.associate { it.path to it.bytes }.toMutableMap()
        var served = 0L
        var dropAfter = Long.MAX_VALUE
        /** Every connection drops after this many bytes (a poor connection). */
        var dropEvery = Long.MAX_VALUE
        /** Connections that fail before sending anything, from now on. */
        var dead = false
        val wrong = mutableSetOf<String>()

        fun source(entry: ClientTorrent.Entry) = object : ZipDownload.Source {
            override fun size() = entry.size

            override fun open(from: Long, to: Long?): InputStream {
                val data = content.getValue(entry.path)
                val end = (to ?: (data.size - 1L)) + 1
                if (dead) throw IOException("no route to host")
                val limit = minOf(dropAfter, dropEvery)
                dropAfter = Long.MAX_VALUE
                return object : InputStream() {
                    var at = from
                    var sent = 0L
                    override fun read(): Int {
                        if (sent >= limit) throw IOException("connection dropped")
                        if (at >= end) return -1
                        sent++
                        served++
                        val byte = data[(at++).toInt()].toInt() and 0xFF
                        return if (entry.path in wrong) byte xor 0x55 else byte
                    }
                }
            }
        }
    }

    private class Setup(val files: List<Torrents.File>, pieceLength: Int = 4096) {
        val root: File = createTempDirectory("sync").toFile()
        val fs = SafeFs(root)
        val client = File(root, "client")
        val kept = File(root, "client-kept")
        val torrent = ClientTorrent.parse(Torrents.make(files, pieceLength))
        val seed = Seed(files)
        fun sync(torrent: ClientTorrent = this.torrent) = ClientSync(torrent, client, kept, fs, seed::source)
    }

    private val client = listOf(
        Torrents.File("Data/dbc.MPQ", Torrents.bytes(20_000, 1)),
        Torrents.File("Data/patch-5.mpq", Torrents.bytes(33_333, 2)),
        Torrents.File("Interface/AddOns/Blizzard_RaidUI/Blizzard_RaidUI.pub", Torrents.bytes(257, 3)),
        Torrents.File("WoW.exe", Torrents.bytes(12_000, 4)),
        Torrents.File("d3d9.dll", Torrents.bytes(7_000, 5)),
        Torrents.File("realmlist.wtf", "set realmlist \"octowow.st\"\r\n".toByteArray()),
    )

    private fun withSetup(files: List<Torrents.File> = client, pieceLength: Int = 4096, test: (Setup) -> Unit) {
        val setup = Setup(files, pieceLength)
        try {
            test(setup)
        } finally {
            setup.root.deleteRecursively()
        }
    }

    @Test
    fun downloadsTheClientWithTheGamesFilesInItsFolderOnly() = withSetup { s ->
        val result = s.sync().run(pause = {})
        assertContentEquals(client[0].bytes, File(s.client, "Data/dbc.MPQ").readBytes())
        assertContentEquals(client[3].bytes, File(s.client, "WoW.exe").readBytes())
        assertContentEquals(client[2].bytes, File(s.client, "Interface/AddOns/Blizzard_RaidUI/Blizzard_RaidUI.pub").readBytes())
        // OctoWoW's DXVK and its realm list are kept aside: the game sees neither.
        assertFalse(File(s.client, "d3d9.dll").exists())
        assertFalse(File(s.client, "realmlist.wtf").exists())
        assertContentEquals(client[4].bytes, File(s.kept, "d3d9.dll").readBytes())
        assertContentEquals(client[5].bytes, File(s.kept, "realmlist.wtf").readBytes())
        assertEquals(s.torrent.totalSize, result.downloaded)
        assertEquals(6, result.fetched.size)
        assertEquals(0, result.repairedPieces)
        assertTrue(s.client.walk().none { it.name.endsWith(".part") })
    }

    @Test
    fun knowsEachFilesHashAndWhenTheArchivesChange() = withSetup { s ->
        val first = s.sync().run(pause = {})
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(client.associate { it.path to sha(it.bytes) }, first.sha256)
        // Read again: the same key. WoW.exe changing size moves every later file's pieces, not the key.
        assertEquals(first.dataKey, s.sync().run(pause = {}).dataKey)
        val newerExe = client.map { if (it.path == "WoW.exe") Torrents.File("WoW.exe", Torrents.bytes(13_000, 8)) else it }
        val newer = ClientTorrent.parse(Torrents.make(newerExe))
        val seed = Seed(newerExe)
        val second = ClientSync(newer, s.client, s.kept, s.fs, seed::source).run(previous = s.torrent, pause = {})
        assertEquals(first.dataKey, second.dataKey)
        assertEquals(listOf("WoW.exe"), second.fetched)
        assertEquals(listOf("WoW.exe"), newer.changesFrom(s.torrent).fetch.map { it.path })
        // An archive changing: another key.
        val newerPatch = newerExe.map { if (it.path == "Data/patch-5.mpq") Torrents.File(it.path, Torrents.bytes(33_333, 9)) else it }
        val third = ClientSync(ClientTorrent.parse(Torrents.make(newerPatch)), s.client, s.kept, s.fs, Seed(newerPatch)::source).run(previous = newer, pause = {})
        assertFalse(third.dataKey == second.dataKey)
        // Same size, other bytes: fetched piece by piece, not counted as a new file.
        assertTrue(third.fetched.isEmpty())
        assertTrue(third.repairedPieces > 0)
    }

    @Test
    fun aClientInPlaceIsOnlyRead() = withSetup { s ->
        s.sync().run(pause = {})
        s.seed.served = 0
        // The app's own realm list in the game's folder doesn't count against the client.
        File(s.client, "realmlist.wtf").writeText("set realmlist 127.0.0.1\r\n")
        File(s.client, "WTF").mkdirs()
        File(s.client, "WTF/Config.wtf").writeText("SET realmList \"127.0.0.1\"\n")
        val progress = mutableListOf<ClientSync.Progress>()
        val result = s.sync().run(pause = {}) { progress += it }
        assertEquals(0L, result.downloaded)
        assertEquals(0L, s.seed.served)
        assertEquals(s.torrent.streamSize, progress.last().done)
        assertTrue(progress.all { it.step == ClientSync.Step.CHECKING })
        assertEquals("set realmlist 127.0.0.1\r\n", File(s.client, "realmlist.wtf").readText())
        assertTrue(File(s.client, "WTF/Config.wtf").isFile)
    }

    @Test
    fun carriesOnFromWhatAnEarlierTryDownloaded() = withSetup { s ->
        File(s.client, "Data").mkdirs()
        File(s.client, "Data/dbc.MPQ").writeBytes(client[0].bytes)
        // Half of patch-5 came before the app was closed.
        File(s.client, "Data/patch-5.mpq.part").writeBytes(client[1].bytes.copyOf(15_000))
        val result = s.sync().run(pause = {})
        assertContentEquals(client[1].bytes, File(s.client, "Data/patch-5.mpq").readBytes())
        assertEquals(s.torrent.totalSize - 20_000 - 15_000, result.downloaded)
        assertEquals(result.downloaded, s.seed.served)
        assertFalse(File(s.client, "Data/patch-5.mpq.part").exists())
    }

    @Test
    fun carriesOnAfterADroppedConnection() = withSetup { s ->
        s.seed.dropAfter = 10_000
        var pauses = 0
        s.sync().run(pause = { pauses++ })
        assertEquals(1, pauses)
        assertContentEquals(client[0].bytes, File(s.client, "Data/dbc.MPQ").readBytes())
        assertContentEquals(client[1].bytes, File(s.client, "Data/patch-5.mpq").readBytes())
    }

    @Test
    fun aPoorConnectionDroppingOftenStillGetsThere() = withSetup { s ->
        // Far more drops than tries: each one got something, so none counts against it.
        s.seed.dropEvery = 3_000
        var pauses = 0
        s.sync().run(tries = 3, pause = { pauses++ })
        assertTrue(pauses > 10, "pauses $pauses")
        assertContentEquals(client[1].bytes, File(s.client, "Data/patch-5.mpq").readBytes())
    }

    @Test
    fun noConnectionAtAllGivesUpAfterItsTries() = withSetup { s ->
        s.seed.dead = true
        var pauses = 0
        assertFailsWith<IOException> { s.sync().run(tries = 3, pause = { pauses++ }) }
        assertEquals(2, pauses)
    }

    @Test
    fun aDamagedPieceIsFetchedAgainAlone() = withSetup { s ->
        s.sync().run(pause = {})
        val file = File(s.client, "Data/patch-5.mpq")
        val damaged = file.readBytes().also { it[20_000] = (it[20_000] + 1).toByte() }
        file.writeBytes(damaged)
        s.seed.served = 0
        val result = s.sync().run(pause = {})
        assertContentEquals(client[1].bytes, file.readBytes())
        assertEquals(1, result.repairedPieces)
        // One piece's worth, not the file.
        assertTrue(s.seed.served <= 4096, "served ${s.seed.served}")
        assertTrue(result.fetched.isEmpty())
    }

    @Test
    fun anotherVersionOfAFileIsReplaced() = withSetup { s ->
        s.sync().run(pause = {})
        File(s.client, "Data/patch-5.mpq").writeBytes(Torrents.bytes(40_000, 9))
        val result = s.sync().run(pause = {})
        assertEquals(listOf("Data/patch-5.mpq"), result.fetched)
        assertContentEquals(client[1].bytes, File(s.client, "Data/patch-5.mpq").readBytes())
    }

    @Test
    fun aSeedServingWrongBytesEndsInCorrupt() = withSetup { s ->
        s.seed.wrong += "WoW.exe"
        assertFailsWith<ClientSync.Corrupt> { s.sync().run(tries = 3, pause = {}) }
    }

    @Test
    fun anUpdateRemovesWhatTheNewClientHasntAndNothingElse() {
        val old = client + Torrents.File("Data/patch-6.mpq", Torrents.bytes(5_000, 7))
        withSetup(old) { s ->
            s.sync().run(pause = {})
            File(s.client, "Screenshots").mkdirs()
            File(s.client, "Screenshots/shot.jpg").writeBytes(ByteArray(10))
            File(s.client, "Interface/AddOns/pfUI").mkdirs()
            File(s.client, "Interface/AddOns/pfUI/pfUI.toc").writeText("## Title: pfUI")
            val newer = ClientTorrent.parse(Torrents.make(client))
            val result = ClientSync(newer, s.client, s.kept, s.fs, s.seed::source).run(previous = s.torrent, pause = {})
            assertEquals(listOf("Data/patch-6.mpq"), result.removed)
            assertFalse(File(s.client, "Data/patch-6.mpq").exists())
            assertTrue(File(s.client, "Screenshots/shot.jpg").isFile)
            assertTrue(File(s.client, "Interface/AddOns/pfUI/pfUI.toc").isFile)
        }
    }

    @Test
    fun anImportedClientKeepsItsSpellingAndItsDxvkGoesAside() = withSetup { s ->
        // As copied from a computer: other spellings, OctoWoW's DXVK in the game's folder.
        File(s.client, "data").mkdirs()
        File(s.client, "data/DBC.mpq").writeBytes(client[0].bytes)
        File(s.client, "D3D9.dll").writeBytes(client[4].bytes)
        File(s.client, "wow.exe").writeBytes(client[3].bytes)
        val result = s.sync().run(pause = {})
        assertFalse(File(s.client, "D3D9.dll").exists())
        assertContentEquals(client[4].bytes, File(s.kept, "d3d9.dll").readBytes())
        // Found as they are, not downloaded again; the new file went into the folder that's there.
        assertTrue(File(s.client, "data/DBC.mpq").isFile)
        assertTrue(File(s.client, "data/patch-5.mpq").isFile)
        assertFalse(File(s.client, "Data").exists())
        assertEquals(setOf("Data/patch-5.mpq", "Interface/AddOns/Blizzard_RaidUI/Blizzard_RaidUI.pub", "realmlist.wtf"), result.fetched.toSet())
    }

    @Test
    fun anotherClientsArchivesGoSoTheGameLoadsOnlyOctoWows() = withSetup { s ->
        // Turtle WoW's final client had patch-6 and patch-7, which the game would load over OctoWoW's patch-5.
        File(s.client, "Data/enUS").mkdirs()
        File(s.client, "Data/patch-6.mpq").writeBytes(ByteArray(100))
        File(s.client, "Data/Patch-7.MPQ").writeBytes(ByteArray(100))
        File(s.client, "Data/enUS/patch-enUS-2.MPQ").writeBytes(ByteArray(100))
        File(s.client, "Data/enUS/realmlist.wtf").writeText("set realmlist logon.turtle-wow.org")
        File(s.client, "Data/dbc.MPQ").writeBytes(client[0].bytes)
        val result = s.sync().run(pause = {})
        assertEquals(setOf("Data/patch-6.mpq", "Data/Patch-7.MPQ", "Data/enUS/patch-enUS-2.MPQ"), result.removed.toSet())
        assertFalse(File(s.client, "Data/patch-6.mpq").exists())
        // Only archives go: other files stay.
        assertTrue(File(s.client, "Data/enUS/realmlist.wtf").isFile)
        assertTrue(File(s.client, "Data/dbc.MPQ").isFile)
    }

    @Test
    fun paddingFilesAreHashedAsZerosAndNeverWritten() {
        val padded = listOf(
            Torrents.File("Data/a.mpq", Torrents.bytes(5_000, 1)),
            Torrents.File(".pad/3192", ByteArray(3_192), padding = true),
            Torrents.File("WoW.exe", Torrents.bytes(6_000, 2)),
        )
        withSetup(padded) { s ->
            s.sync().run(pause = {})
            assertContentEquals(padded[2].bytes, File(s.client, "WoW.exe").readBytes())
            assertFalse(File(s.client, ".pad").exists())
            assertEquals(11_000L, s.torrent.totalSize)
        }
    }

    @Test
    fun keepsWhatItHasWhenCancelled() = withSetup { s ->
        var asked = 0
        assertFailsWith<VerifiedDownload.Cancelled> { s.sync().run(cancelled = { ++asked > 3 }, pause = {}) }
        assertTrue(File(s.client, "Data/dbc.MPQ").isFile || File(s.client, "Data/dbc.MPQ.part").isFile)
        s.sync().run(pause = {})
        assertContentEquals(client[0].bytes, File(s.client, "Data/dbc.MPQ").readBytes())
    }

    @Test
    fun staysInItsFolder() = withSetup { s ->
        val outside = File(s.root.parentFile, "elsewhere-${System.nanoTime()}")
        assertFailsWith<SafeFs.Refused> { ClientSync(s.torrent, outside, s.kept, s.fs, s.seed::source).run(pause = {}) }
        assertFalse(outside.exists())
    }

    @Test
    fun aFolderWhereTheClientHasAFileIsNotDeleted() = withSetup { s ->
        File(s.client, "WoW.exe").mkdirs()
        File(s.client, "WoW.exe/keep.txt").writeText("mine")
        assertFailsWith<IOException> { s.sync().run(pause = {}) }
        assertTrue(File(s.client, "WoW.exe/keep.txt").isFile)
    }
}
