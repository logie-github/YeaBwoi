package com.logie.warcraft.felsong.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FelsongAccountRulesTest {
    @Test
    fun hashesLikeLegionCore() {
        // LegionCore's own auth database comes with GM@GM, password admin (sql/base/auth_database.sql).
        assertEquals("586EF64D6BCF71292B55C8805E465172D876E0C79968F925A0DCE5D9B4BAA492", FelsongAccountRules.shaPassHash("GM@GM", "admin"))
        assertEquals(FelsongAccountRules.shaPassHash("gm@gm", "ADMIN"), FelsongAccountRules.shaPassHash("GM@GM", "admin"))
        assertTrue(FelsongAccountRules.matches("GM@GM", "admin", "586ef64d6bcf71292b55c8805e465172d876e0c79968f925a0dce5d9b4baa492"))
        assertFalse(FelsongAccountRules.matches("GM@GM", "wrong", "586EF64D6BCF71292B55C8805E465172D876E0C79968F925A0DCE5D9B4BAA492"))
    }

    @Test
    fun acceptsOnlyAnEmailAndASafePassword() {
        assertEquals("ME@PHONE", FelsongAccountRules.normalizeName(" me@phone "))
        assertEquals("LOGAN.HILL@GMAIL.COM", FelsongAccountRules.normalizeName("logan.hill@gmail.com"))
        for (bad in listOf("", "noatsign", "two@@at", "quote'@x", "a b@x", "@x", "x@", "a".repeat(60) + "@x")) {
            assertNull(FelsongAccountRules.normalizeName(bad), bad)
        }
        assertTrue(FelsongAccountRules.isPassword("Fel-song_1!"))
        for (bad in listOf("", "with space", "quote'", "back\\slash", "a".repeat(17), "ünïcode")) {
            assertFalse(FelsongAccountRules.isPassword(bad), bad)
        }
    }

    @Test
    fun makesSqlOnlyFromCheckedValues() {
        val sql = FelsongAccountRules.createSql("ME@PHONE", "admin", FelsongAccountRules.LEVEL_ADMINISTRATOR)
        assertTrue("INSERT INTO account (username, sha_pass_hash, email, joindate) VALUES ('ME@PHONE', '" in sql)
        assertTrue("INSERT INTO account_access (id, gmlevel, RealmID) SELECT id, 3, -1 FROM account WHERE username = 'ME@PHONE'" in sql)
        assertTrue("REPLACE INTO realmcharacters" in sql)
        assertFalse("account_access" in FelsongAccountRules.createSql("ME@PHONE", "admin", FelsongAccountRules.LEVEL_PLAYER))
        assertTrue("sha_pass_hash = '${FelsongAccountRules.shaPassHash("ME@PHONE", "new")}'" in FelsongAccountRules.passwordSql("ME@PHONE", "new"))
        assertTrue("gmlevel, RealmID) SELECT id, 2, -1" in FelsongAccountRules.levelSql("ME@PHONE", FelsongAccountRules.LEVEL_GAMEMASTER))
        assertFalse("INSERT" in FelsongAccountRules.levelSql("ME@PHONE", FelsongAccountRules.LEVEL_PLAYER))
        runCatching { FelsongAccountRules.createSql("x'; DROP TABLE account; --@x", "admin", 0) }.exceptionOrNull().let { assertNotNull(it) }
        runCatching { FelsongAccountRules.passwordSql("ME@PHONE", "it's") }.exceptionOrNull().let { assertNotNull(it) }
    }
}

class FelsongInstallStateTest {
    @Test
    fun roundTrips() {
        val state = FelsongInstallState(
            serverSha256 = "a".repeat(64), serverTag = "felsong-server-abc", databaseFrom = "a".repeat(64), databaseUpdated = true,
            clientExeSha256 = "b".repeat(64), clientBuild = 26972, patchedFrom = "b".repeat(64), dataFrom = "b".repeat(64),
            dataParts = setOf("dbc", "maps", "vmaps"), interruptedStep = "MAPS", updatedAt = 5L,
        )
        assertEquals(state, FelsongInstallState.parse(state.toJson()))
        assertEquals(FelsongInstallState(), FelsongInstallState.parse(null))
        assertEquals(FelsongInstallState(), FelsongInstallState.parse("not json"))
    }

    @Test
    fun theStepsGoInOrder() {
        val nothing = FelsongInstallPlan.Facts(
            serverInstalled = false, serverCurrent = false, databasesThere = false, clientThere = false,
            dataThere = false, dataMatchesClient = false, clientPatched = false, hasAccount = null,
        )
        val lines = FelsongInstallPlan.of(nothing)
        assertEquals(FelsongInstallPlan.Step.entries, lines.map { it.step })
        assertEquals(FelsongInstallPlan.Step.SERVER, FelsongInstallPlan.next(lines)?.step)
        assertEquals(FelsongInstallPlan.Status.NEEDS_USER, lines.first { it.step == FelsongInstallPlan.Step.CLIENT }.status)
        assertEquals(FelsongInstallPlan.Status.WAITING, lines.first { it.step == FelsongInstallPlan.Step.DATA }.status)
        assertEquals(FelsongInstallPlan.Status.WAITING, lines.first { it.step == FelsongInstallPlan.Step.PATCH }.status)
        assertFalse(FelsongInstallPlan.ready(lines))

        val all = nothing.copy(serverInstalled = true, serverCurrent = true, databasesThere = true, clientThere = true, dataThere = true, dataMatchesClient = true, clientPatched = true, hasAccount = true)
        assertTrue(FelsongInstallPlan.ready(FelsongInstallPlan.of(all)))
        assertNull(FelsongInstallPlan.next(FelsongInstallPlan.of(all)))

        val noPatch = all.copy(clientPatched = false)
        assertEquals(FelsongInstallPlan.Step.PATCH, FelsongInstallPlan.next(FelsongInstallPlan.of(noPatch))?.step)
        assertEquals(FelsongInstallPlan.Status.TODO, FelsongInstallPlan.of(noPatch).first { it.step == FelsongInstallPlan.Step.PATCH }.status)

        val blocked = FelsongInstallPlan.of(nothing.copy(blocker = "arm64"))
        assertEquals(FelsongInstallPlan.Status.BLOCKED, blocked.first().status)
        assertTrue(blocked.drop(1).all { it.status == FelsongInstallPlan.Status.WAITING })
    }
}

class FelsongClientTest {
    @Test
    fun readsTheVersionFromBuildInfo() {
        val info = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0\n" +
            "us|1|abcdef|abcdef|abcdef|0|tpr/wow|x y|Windows x86_64 US? enUS speech?:Windows x86_64 US? enUS text?||x|7.3.5.26972\n"
        val version = FelsongClient.versionFromBuildInfo(info)
        assertEquals(26972, version?.build)
        assertEquals("7.3.5.26972", version?.text)
        assertTrue(version!!.isExpected)
        assertFalse(FelsongClient.versionFromBuildInfo(info.replace("26972", "26124"))!!.isExpected)
        assertNull(FelsongClient.versionFromBuildInfo("Branch!STRING:0\nus\n"))
        assertNull(FelsongClient.versionFromBuildInfo(""))
    }

    @Test
    fun readsTheVersionFromTheProgram() {
        // As the connection patcher does: the text after <Version>, anywhere in the program, across block boundaries.
        val padding = ByteArray((1 shl 16) - 5)
        val bytes = padding + "<Version>7.3.5.26972</Version>".toByteArray() + ByteArray(100)
        assertEquals(26972, FelsongClient.versionFromExe(bytes.inputStream())?.build)
        assertEquals(26124, FelsongClient.versionFromExe("xx<Version>7.3.5.26124".toByteArray().inputStream())?.build)
        assertNull(FelsongClient.versionFromExe(ByteArray(200_000).inputStream()))
    }

    @Test
    fun findsTheClientAndWhatItMustHave() {
        val paths = listOf("World of Warcraft/Wow-64.exe", "World of Warcraft/.build.info", "World of Warcraft/Data/data/0000000010.idx", "World of Warcraft/Data/data/data.000", "World of Warcraft/Wow.exe")
        assertEquals("World of Warcraft", FelsongClient.findRoot(paths))
        assertEquals("", FelsongClient.findRoot(listOf("wow-64.exe", "data/data/data.000")))
        assertNull(FelsongClient.findRoot(listOf("Wow-64.exe")))
        assertEquals(emptyList(), FelsongClient.missing(paths, "World of Warcraft"))
        assertEquals(listOf("Data/data/*.idx", "Data/data/data.000"), FelsongClient.missing(listOf("Wow-64.exe", "Data/x"), ""))
        assertTrue(FelsongClient.isSafePath("Data/data/data.000"))
        assertFalse(FelsongClient.isSafePath("../Wow-64.exe"))
    }

    @Test
    fun aClientWithoutWow64CanStillBeUsedWhenItsStorageHoldsTheProgram() {
        // The publisher's zip has the 32-bit program loose and the 64-bit one only inside the storage.
        val zip = listOf(".build.info", "Data/config/3b/05/3b05", "Data/data/0000000018.idx", "Data/data/data.000", "Data/indices/x.index", "Wow.exe", "Utils/libcef.dll")
        assertEquals("", FelsongClient.findRoot(zip))
        assertEquals(emptyList(), FelsongClient.missing(zip, ""))
        assertEquals("Retail", FelsongClient.findRoot(zip.map { "Retail/$it" }))
        // Without the build's record the program can't be taken out, so it must be there.
        assertEquals(listOf("Wow-64.exe"), FelsongClient.missing(zip.filter { it != ".build.info" }, ""))
        assertNull(FelsongClient.findRoot(listOf(".build.info")))
        // What the app fetches from that zip: the record and the storage, not the loose programs.
        assertTrue(FelsongClient.wanted(".build.info"))
        assertTrue(FelsongClient.wanted("Data/data/data.039"))
        assertTrue(FelsongClient.wanted("Data/config/3b/05/3b0517b51edbe0b96f6ac5ea7eaaed38"))
        assertFalse(FelsongClient.wanted("Wow.exe"))
        assertFalse(FelsongClient.wanted("Utils/libcef.dll"))
        assertFalse(FelsongClient.wanted("Data/"))
    }

    @Test
    fun readsTheVersionOfAClientFolder() {
        val dir = createTempDirectory("client").toFile()
        try {
            File(dir, "wow-64.exe").writeBytes("junk<Version>7.3.5.26972</Version>".toByteArray())
            assertEquals(26972, FelsongClient.version(dir)?.build)
            File(dir, ".build.info").writeText("Version!STRING:0|Active!DEC:1\n7.3.5.26124|1\n")
            assertEquals(26124, FelsongClient.version(dir)?.build)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pointsTheClientAtThePhone() {
        val values = FelsongClientSetup.values(1600, 720, "me@phone")
        assertEquals("127.0.0.1", values["portal"])
        assertEquals("1600x720", values["gxWindowedResolution"])
        assertEquals("me@phone", values["accountName"])
        assertNull(FelsongClientSetup.values(1600, 720, null)["accountName"])
        val dir = createTempDirectory("client").toFile()
        try {
            File(dir, "WTF").mkdirs()
            File(dir, "WTF/Config.wtf").writeText("SET portal \"us.actual.battle.net\"\r\nSET textLocale \"enUS\"\r\n")
            FelsongClientSetup.apply(dir, 1600, 720, null)
            val text = File(dir, "WTF/Config.wtf").readText()
            assertTrue("SET portal \"127.0.0.1\"" in text)
            assertTrue("SET textLocale \"enUS\"" in text)
            assertFalse("actual.battle.net" in text)
        } finally {
            dir.deleteRecursively()
        }
    }
}

class FelsongDataImportTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** The first 16 bytes of a tile LegionCore's mmaps_generator makes: 'MMAP', navmesh 7, generator 9. */
    private val realTile = bytes(0x50, 0x41, 0x4d, 0x4d, 0x07, 0, 0, 0, 0x09, 0, 0, 0, 0x68, 0xbe, 0x01, 0)

    @Test
    fun takesTheServersFoldersAtTheTopOrInsideAnother() {
        assertEquals("mmaps/00003132.mmtile", FelsongDataImport.target("mmaps/00003132.mmtile"))
        assertEquals("maps/0000_31_32.map", FelsongDataImport.target("felsong-data/maps/0000_31_32.map"))
        assertEquals("dbc/enUS/Spell.db2", FelsongDataImport.target("data/dbc/enUS/Spell.db2"))
        assertEquals("vmaps/0000.vmtree", FelsongDataImport.target("data\\vmaps\\0000.vmtree"))
        assertEquals("cameras/FlyByHuman.m2", FelsongDataImport.target("Cameras/FlyByHuman.m2"))
        assertEquals("gt/SpellScaling.txt", FelsongDataImport.target("gt/SpellScaling.txt"))
    }

    @Test
    fun leavesOutEverythingElse() {
        for (name in listOf(
            "readme.txt", "maps/", "Buildings/dir_bin", "mmaps/sub/00003132.mmtile", "maps/.DS_Store", "dbc/Spell.db2", "dbc/enUS/sub/Spell.db2",
            "../maps/0000_31_32.map", "maps/../../etc/passwd", "/maps/0000_31_32.map", "maps//0000_31_32.map", "C:/maps/0000_31_32.map",
        )) {
            assertNull(FelsongDataImport.target(name), name)
        }
    }

    @Test
    fun takesLegionCoresOwnFormats() {
        assertNull(FelsongDataImport.problem("mmaps/00003132.mmtile", realTile))
        assertNull(FelsongDataImport.problem("mmaps/0000.mmap", ByteArray(16)))
        assertNull(FelsongDataImport.problem("maps/0000_31_32.map", "MAPSv1.9".toByteArray() + ByteArray(8)))
        assertNull(FelsongDataImport.problem("vmaps/0000.vmtree", "VMAP_4.8".toByteArray() + ByteArray(8)))
        assertNull(FelsongDataImport.problem("vmaps/0000_31_32.vmtile", "VMAP_4.8".toByteArray()))
        assertNull(FelsongDataImport.problem("vmaps/Stormwind.wmo.vmo", "VMAP_4.8".toByteArray()))
        assertNull(FelsongDataImport.problem("vmaps/temp_gameobject_models", ByteArray(16)))
        assertNull(FelsongDataImport.problem("dbc/enUS/Spell.db2", "WDC1".toByteArray()))
        assertNull(FelsongDataImport.problem("cameras/FlyByHuman.m2", "MD21".toByteArray()))
        assertNull(FelsongDataImport.problem("gt/SpellScaling.txt", "1\t2".toByteArray()))
    }

    @Test
    fun refusesDataInAnotherFormat() {
        val otherGenerator = realTile.copyOf().also { it[8] = 6 }
        assertEquals(FelsongDataImport.Problem.FOREIGN, FelsongDataImport.problem("mmaps/00003132.mmtile", otherGenerator))
        assertEquals(FelsongDataImport.Problem.FOREIGN, FelsongDataImport.problem("mmaps/00003132.mmtile", ByteArray(16)))
        assertEquals(FelsongDataImport.Problem.FOREIGN, FelsongDataImport.problem("maps/0000_31_32.map", "MAPSz1.4".toByteArray() + ByteArray(8)))
        assertEquals(FelsongDataImport.Problem.FOREIGN, FelsongDataImport.problem("vmaps/0000.vmtree", "VMAP_6.0".toByteArray()))
        assertEquals(FelsongDataImport.Problem.FOREIGN, FelsongDataImport.problem("dbc/enUS/Spell.db2", "WDBC".toByteArray()))
    }

    @Test
    fun leavesOutNotesBesideTheData() {
        assertEquals(FelsongDataImport.Problem.NOT_DATA, FelsongDataImport.problem("mmaps/offmesh.txt", ByteArray(16)))
        assertEquals(FelsongDataImport.Problem.NOT_DATA, FelsongDataImport.problem("maps/readme.txt", ByteArray(16)))
        assertEquals(FelsongDataImport.Problem.NOT_DATA, FelsongDataImport.problem("dbc/enUS/component.wow-enUS.txt", ByteArray(16)))
    }

    @Test
    fun pathfindingAloneNeedsTheDataThePhoneMade() {
        assertTrue(FelsongDataImport.complete(setOf("mmaps"), setOf("dbc", "maps", "vmaps")))
        assertFalse(FelsongDataImport.complete(setOf("mmaps"), emptySet()))
        assertTrue(FelsongDataImport.complete(setOf("dbc", "maps"), emptySet()))
    }
}
