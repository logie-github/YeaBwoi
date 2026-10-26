package com.logie.warcraft.turtle.core

import com.logie.warcraft.client.ZipDownload
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientManifestTest {
    private val assets = File("../android/app/src/main/assets/turtle")

    @Test
    fun readsTheDbcChecksumsAndOctoWowsOwnTables() {
        val dbc = DbcManifest.parse(File(assets, "dbc-sha256.tsv").readText(), File(assets, "octowow-dbc.txt").readText())
        assertTrue(dbc.hashes.size > 150)
        assertEquals("990d23c46d40cecc1aa68bfdad3810cacbb8f3c6fa1abfc19ad1c0f63f8ca88f", dbc.hashes["AreaTable.dbc"])
        assertEquals(24, dbc.own.size)
        assertTrue("Spell.dbc" in dbc.own && "SkillLine.dbc" in dbc.own && "AreaTable.dbc" !in dbc.own)
        // Every one of OctoWoW's tables is one Tortoise has a checksum of.
        assertTrue(dbc.hashes.keys.containsAll(dbc.own))
    }

    @Test
    fun refusesUnsafePaths() {
        for (bad in listOf("../x", "/etc/passwd", "a/../../b", "C:\\x", "a//b", "")) assertFalse(ClientManifest.isSafePath(bad), bad)
        assertTrue(ClientManifest.isSafePath("Data/patch-5.mpq"))
    }

    @Test
    fun findsTheClientInAPickedFolderOrZip() {
        assertEquals("", ClientManifest.findRoot(listOf("WoW.exe", "Data/dbc.MPQ")))
        assertEquals("OctoWoW", ClientManifest.findRoot(listOf("OctoWoW/WoW.exe", "OctoWoW/Data/patch.MPQ", "readme.txt")))
        assertEquals("a/b", ClientManifest.findRoot(listOf("a/b/wow.exe", "a/b/data/x")))
        assertNull(ClientManifest.findRoot(listOf("WoW.exe")))
        assertNull(ClientManifest.findRoot(listOf("Data/dbc.MPQ")))
    }

    @Test
    fun knowsWhatAClientCantDoWithout() {
        val dir = createTempDirectory("client").toFile()
        try {
            File(dir, "WoW.exe").writeText("exe")
            File(dir, "data").mkdirs()
            File(dir, "data/DBC.mpq").writeText("dbc") // another case: Windows doesn't care
            assertEquals(listOf("Data/terrain.MPQ", "Data/patch.MPQ"), ClientManifest.missingRequired(dir))
            File(dir, "data/terrain.MPQ").writeText("t")
            File(dir, "data/patch.MPQ").writeText("p")
            assertEquals(emptyList(), ClientManifest.missingRequired(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifiesDbcFilesWithOctoWowsOwn() {
        val dir = createTempDirectory("dbc").toFile()
        try {
            File(dir, "Spell.dbc").writeText("octowow's spells")
            File(dir, "Map.dbc").writeText("maps")
            val manifest = DbcManifest(mapOf("Spell.dbc" to sha("spells"), "Map.dbc" to sha("maps"), "Talent.dbc" to sha("t")), own = setOf("Spell.dbc"))
            val report = manifest.verify(dir)
            assertEquals(listOf("Talent.dbc"), report.missing)
            assertEquals(listOf("Spell.dbc"), report.different)
            assertEquals(listOf("Talent.dbc"), report.unexpected)
            assertFalse(report.exact)
            File(dir, "Talent.dbc").writeText("t")
            val again = manifest.verify(dir)
            // Only OctoWoW's own table differs: as expected.
            assertTrue(again.exact)
            assertEquals(1, again.ownDifferent)
        } finally {
            dir.deleteRecursively()
        }
    }
}

class TurtleAccountRulesTest {
    @Test
    fun hashesLikeMangos() {
        // MaNGOS's classic default administrator account: ADMINISTRATOR / ADMINISTRATOR.
        assertEquals("A34B29541B87B7E4823683CE6C7BF6AE68BEAAAC", TurtleAccountRules.shaPassHash("administrator", "administrator"))
        assertTrue(TurtleAccountRules.matches("Administrator", "ADMINISTRATOR", "a34b29541b87b7e4823683ce6c7bf6ae68beaaac"))
        assertFalse(TurtleAccountRules.matches("Administrator", "wrong", "a34b29541b87b7e4823683ce6c7bf6ae68beaaac"))
    }

    @Test
    fun acceptsOnlySafeNamesAndPasswords() {
        assertEquals("TURTLE", TurtleAccountRules.normalizeName(" turtle "))
        for (bad in listOf("", "a b", "x'y", "x\"y", "x;y", "a".repeat(17), "ç")) assertNull(TurtleAccountRules.normalizeName(bad), bad)
        assertTrue(TurtleAccountRules.isPassword("Shell!1"))
        for (bad in listOf("", "with space", "quote'", "dq\"", "back\\slash", "x".repeat(17))) assertFalse(TurtleAccountRules.isPassword(bad), bad)
    }

    @Test
    fun makesSqlOnlyFromCheckedValues() {
        val sql = TurtleAccountRules.createSql("TURTLE", "Shell!1", 9)
        assertTrue("'TURTLE'" in sql && TurtleAccountRules.shaPassHash("TURTLE", "Shell!1") in sql)
        assertTrue(", 4, NOW()" in sql, sql) // the rank capped at administrator
        assertFalse("Shell!1" in sql)
        assertFailsWith<IllegalArgumentException> { TurtleAccountRules.createSql("x'; DROP", "p", 0) }
        assertFailsWith<IllegalArgumentException> { TurtleAccountRules.passwordSql("TURTLE", "a b") }
        assertTrue("v = '0'" in TurtleAccountRules.passwordSql("TURTLE", "new"))
    }
}

class InstallStateTest {
    @Test
    fun roundTrips() {
        val state = InstallState(
            serverSha256 = "a".repeat(64), serverTag = "turtle-server-abc", databaseFrom = "a".repeat(64), databaseUpdated = true,
            clientExeSha256 = "b".repeat(64), clientCheck = InstallState.ClientCheck.DIFFERENT, clientDifferences = 3,
            clientKey = "mpq:" + "c".repeat(64), clientTorrent = "d".repeat(40), clientCheckedAt = 7,
            dataFrom = "b".repeat(64), dataParts = setOf("dbc", "maps", "vmaps"), dbcCheck = InstallState.ClientCheck.EXACT, dbcOwn = 24,
            interruptedStep = "MAPS", updatedAt = 42,
        )
        assertEquals(state, InstallState.parse(state.toJson()))
        assertEquals(InstallState(), InstallState.parse("not json"))
        assertEquals(InstallState(), InstallState.parse(null))
        // The data follows the archives' key; a client never checked has only its WoW.exe's hash.
        assertEquals("mpq:" + "c".repeat(64), state.clientIdentity)
        assertEquals("b".repeat(64), state.copy(clientKey = null).clientIdentity)
        // Exactly OctoWoW's only when checked against its torrent: Turtle WoW's old check doesn't count.
        assertTrue(state.copy(clientCheck = InstallState.ClientCheck.EXACT).clientIsOctoWow)
        assertFalse(state.copy(clientCheck = InstallState.ClientCheck.EXACT, clientTorrent = null).clientIsOctoWow)
        assertFalse(state.clientIsOctoWow)
    }

    private fun facts(
        blocker: String? = null, server: Boolean = true, current: Boolean = true, db: Boolean = true,
        client: Boolean = true, data: Boolean = true, matches: Boolean = true, account: Boolean? = true,
    ) = InstallPlan.Facts(blocker, server, current, db, client, data, matches, account)

    private fun status(lines: List<InstallPlan.Line>, step: InstallPlan.Step) = lines.single { it.step == step }.status

    @Test
    fun plansFromNothing() {
        val lines = InstallPlan.of(facts(server = false, current = false, db = false, client = false, data = false, account = null))
        assertEquals(InstallPlan.Status.TODO, status(lines, InstallPlan.Step.SERVER))
        assertEquals(InstallPlan.Status.WAITING, status(lines, InstallPlan.Step.DATABASE))
        // The app downloads OctoWoW's client itself.
        assertEquals(InstallPlan.Status.TODO, status(lines, InstallPlan.Step.CLIENT))
        assertEquals(InstallPlan.Status.WAITING, status(lines, InstallPlan.Step.DATA))
        assertFalse(InstallPlan.ready(lines))
        assertEquals(InstallPlan.Step.SERVER, InstallPlan.next(lines)?.step)
    }

    @Test
    fun asksForTheClientThenMakesData() {
        val noClient = InstallPlan.of(facts(client = false, data = false))
        assertEquals(InstallPlan.Step.CLIENT, InstallPlan.next(noClient)?.step)
        val noData = InstallPlan.of(facts(data = false))
        assertEquals(InstallPlan.Step.DATA, InstallPlan.next(noData)?.step)
        assertEquals(InstallPlan.Status.TODO, status(noData, InstallPlan.Step.DATA))
        // Data from another client is made again.
        assertEquals(InstallPlan.Status.TODO, status(InstallPlan.of(facts(matches = false)), InstallPlan.Step.DATA))
    }

    @Test
    fun readyOnlyWithEverything() {
        assertTrue(InstallPlan.ready(InstallPlan.of(facts())))
        assertFalse(InstallPlan.ready(InstallPlan.of(facts(account = false))))
        assertFalse(InstallPlan.ready(InstallPlan.of(facts(current = false))))
        val blocked = InstallPlan.of(facts(blocker = "arm64"))
        assertEquals(InstallPlan.Status.BLOCKED, status(blocked, InstallPlan.Step.CHECK))
        assertFalse(InstallPlan.ready(blocked))
    }
}

class VerifiedDownloadTest {
    private class Bytes(private val data: ByteArray, var failAfter: Int = Int.MAX_VALUE) : ZipDownload.Source {
        var opens = 0
        override fun size() = data.size.toLong()
        override fun open(from: Long, to: Long?): InputStream {
            opens++
            val limit = failAfter
            failAfter = Int.MAX_VALUE
            val bytes = data.copyOfRange(from.toInt(), data.size)
            return object : InputStream() {
                var read = 0
                override fun read(): Int {
                    if (read >= limit) throw IOException("connection dropped")
                    return if (read < bytes.size) bytes[read++].toInt() and 0xFF else -1
                }
            }
        }
    }

    private fun setUp(): Triple<File, SafeFs, File> {
        val root = createTempDirectory("dl").toFile()
        val fs = SafeFs(root)
        return Triple(root, fs, File(root, "downloads/turtle-server.tar.xz"))
    }

    @Test
    fun carriesOnAfterADroppedConnection() {
        val (root, fs, target) = setUp()
        try {
            val data = ByteArray(300_000) { (it % 251).toByte() }
            val source = Bytes(data, failAfter = 100_000)
            VerifiedDownload(source, target, data.size.toLong(), sha(data), fs).run(pause = {})
            assertTrue(target.readBytes().contentEquals(data))
            assertEquals(2, source.opens)
            assertFalse(File(target.path + ".part").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deletesADownloadThatIsntThePublishedFile() {
        val (root, fs, target) = setUp()
        try {
            val data = ByteArray(1000) { 7 }
            val download = VerifiedDownload(Bytes(data), target, data.size.toLong(), "0".repeat(64), fs)
            assertFailsWith<VerifiedDownload.Corrupt> { download.run(pause = {}) }
            assertFalse(target.exists())
            assertFalse(download.part.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsWhatItHasWhenCancelled() {
        val (root, fs, target) = setUp()
        try {
            val data = ByteArray(5 shl 20) { 1 }
            var asked = 0
            val download = VerifiedDownload(Bytes(data), target, data.size.toLong(), sha(data), fs)
            assertFailsWith<VerifiedDownload.Cancelled> { download.run(cancelled = { ++asked > 20 }, pause = {}) }
            assertTrue(download.soFar() > 0)
            assertFalse(target.exists())
            download.run(pause = {})
            assertTrue(target.readBytes().contentEquals(data))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesATargetOutsideItsFolder() {
        val (root, fs, _) = setUp()
        try {
            val outside = File(root.parentFile, "elsewhere.bin")
            assertFailsWith<SafeFs.Refused> { VerifiedDownload(Bytes(ByteArray(1)), outside, 1, sha(ByteArray(1)), fs).run(pause = {}) }
        } finally {
            root.deleteRecursively()
        }
    }
}

class TurtleClientSetupTest {
    @Test
    fun pointsTheClientAtThePhonesRealm() {
        val client = createTempDirectory("turtle-client").toFile()
        try {
            File(client, "WTF").mkdirs()
            // As Turtle's own client has them: its old servers for the realm and the patches.
            File(client, "WTF/Config.wtf").writeText("SET gxResolution \"800x600\"\r\nSET SoundVolume \"0.5\"\r\nSET realmList \"logon.turtle-wow.org\"\r\nSET patchlist \"logon.turtle-wow.org\"\r\n")
            File(client, "realmlist.wtf").writeText("set realmlist logon.turtle-wow.org\r\nset patchlist logon.turtle-wow.org\r\n")
            TurtleClientSetup.apply(client, 2400, 1080, "TURTLE")
            assertEquals("set realmlist 127.0.0.1\r\nset patchlist 127.0.0.1\r\n", File(client, "realmlist.wtf").readText())
            val config = File(client, "WTF/Config.wtf").readText()
            assertTrue("SET realmList \"127.0.0.1\"" in config)
            assertTrue("SET patchlist \"127.0.0.1\"" in config, config)
            assertTrue("SET gxResolution \"2400x1080\"" in config)
            assertTrue("SET SoundVolume \"0.5\"" in config, "the player's own settings stay")
            assertTrue("SET accountName \"TURTLE\"" in config)
            assertTrue("SET realmName \"${TurtleClientSetup.REALM_NAME}\"" in config)
            assertFalse("turtle-wow.org" in config)
        } finally {
            client.deleteRecursively()
        }
    }
}

class TurtleRealmTest {
    /** Tortoise's login server drops a client asking for the realm list twice in a second: not on the phone. */
    @Test
    fun theLoginServerAnswersTheRealmListAsOftenAsAsked() {
        assertEquals("0", TurtleRealm.REALMD_SETTINGS["MinRealmListDelay"])
    }

    @Test
    fun recognisesTortoisesLogLines() {
        // As src/game/World.cpp, DBCStores.cpp and AutoUpdater.cpp write them.
        assertTrue(TurtleRealm.READY.containsMatchIn("World server is up and running! Loading time: 3 minutes 12 seconds"))
        assertEquals(TurtleRealm.Fatal.DATA, TurtleRealm.Fatal.of("Correct *.map files not found in path '/x/maps' or *.vmtree/*.vmtile files in '/x/vmaps'."))
        assertEquals(TurtleRealm.Fatal.UPDATE, TurtleRealm.Fatal.of("[DB Auto-Updater] Migration 20260504194945_world with hash ABC failed to apply."))
        assertEquals(TurtleRealm.Fatal.UPDATE, TurtleRealm.Fatal.of("DB AutoUpdater FAILED, cancelling server."))
        assertEquals(TurtleRealm.Fatal.DBC, TurtleRealm.Fatal.of("Some required *.dbc files (3 from 160) not found or not compatible:"))
        assertNull(TurtleRealm.Fatal.of("Loading spells..."))
    }

    @Test
    fun offersOnlyTortoisesOwnSettings() {
        val conf = File("../../build/tortoise/src/mangosd/mangosd.conf.dist.in").takeIf { it.isFile }?.readText() ?: return
        for (setting in TurtleRealm.SETTINGS) {
            assertTrue(Regex("^${Regex.escape(setting.key)}\\s*=\\s*${Regex.escape(setting.default)}\\s*$", RegexOption.MULTILINE).containsMatchIn(conf), setting.key)
            assertTrue(setting.default in setting.choices)
        }
    }
}

private fun sha(text: String) = sha(text.toByteArray())

private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

