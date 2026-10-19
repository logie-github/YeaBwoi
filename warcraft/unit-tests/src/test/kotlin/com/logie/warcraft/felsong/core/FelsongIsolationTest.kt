package com.logie.warcraft.felsong.core

import com.logie.warcraft.turtle.core.TurtleLayout
import com.logie.warcraft.turtle.core.TurtleRealm
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Felsong apart from the 3.3.5a game and Turtle WoW: its folders, its server script, its ports, and
 * the rule that its code only deletes or moves files through the folder guard (SafeFs). Reads the
 * app's own sources, so a folder another game adds later, or a raw delete in Felsong's code, fails
 * here.
 */
class FelsongIsolationTest {
    private val app = File("../android/app/src/main/java/com/logie/warcraft")
    private val felsongCode = File(app, "felsong")
    private val ctl = File("../felsong/server/ctl.sh")

    @Test
    fun everyFelsongPathIsInsideFelsongsFolder() {
        val files = File("/data/user/0/com.logie.warcraft/files")
        val layout = FelsongLayout(files, files.parentFile)
        val paths = listOf(
            layout.server, layout.serverStaging, layout.serverOld, layout.ctl, layout.etc, layout.databases,
            layout.data, layout.dataWork, layout.dataImport, layout.client, layout.clientStaging, layout.logs, layout.run,
            layout.downloads, layout.backups, layout.stateFile, layout.patcherHome, layout.certBundle,
        )
        for (path in paths) {
            assertTrue(path.toPath().normalize().startsWith(layout.root.toPath()), "${path.path} is outside ${layout.root.path}")
            for (other in layout.protectedFolders) {
                assertFalse(path.toPath().startsWith(other.toPath()), "${path.path} is in ${other.path}")
                assertFalse(other.toPath().startsWith(path.toPath()), "${other.path} is in ${path.path}")
            }
        }
        assertEquals(listOf(layout.root), layout.all)
        assertTrue(FelsongLayout.STANDARD_ROOT.endsWith("/files/" + FelsongLayout.ROOT_NAME))
        // Turtle's folder is protected from Felsong, and Felsong's name isn't one Turtle uses.
        assertTrue(TurtleLayout.ROOT_NAME in FelsongLayout.PROTECTED_NAMES)
        assertTrue(TurtleLayout.PROTECTED_NAMES.all { it in FelsongLayout.PROTECTED_NAMES })
        assertFalse(FelsongLayout.ROOT_NAME in TurtleLayout.PROTECTED_NAMES || FelsongLayout.ROOT_NAME == TurtleLayout.ROOT_NAME)
    }

    @Test
    fun everyFolderTheOtherGamesUseIsProtected() {
        val patterns = listOf(
            Regex("""File\((?:WowFiles\.)?root\(context\), "([^"/]+)"\)"""),
            Regex("""File\(context\.filesDir, "([^"/]+)[/"]"""),
            Regex("""staging\(context, "([^"/]+)"\)"""),
        )
        val names = app.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/turtle/") && !it.path.contains("/felsong/") }
            .flatMap { file -> val text = file.readText(); patterns.flatMap { p -> p.findAll(text).map { it.groupValues[1] } } }
            .toSet()
        assertTrue("server" in names && "client" in names && "mysql" in names, "the scan found $names")
        for (name in names) assertTrue(name in FelsongLayout.PROTECTED_NAMES, "$name (a folder of the 3.3.5a game) isn't protected from Felsong's code")
        for (script in listOf("../server/ctl.sh", "../turtle/server/ctl.sh")) {
            val text = File(script).readText()
            val ctlNames = Regex("\\\$FILES/([a-z][a-z-]*(?:\\.[a-z]+)?)").findAll(text).map { it.groupValues[1] }.toSet()
            assertTrue("server" in ctlNames && "mysql" in ctlNames, "the scan of $script found $ctlNames")
            for (name in ctlNames) {
                if (script.contains("turtle")) assertTrue(name != FelsongLayout.ROOT_NAME) // Turtle's are under files/turtle, protected whole
                else assertTrue(name in FelsongLayout.PROTECTED_NAMES, "$name (ChromieCraft's ctl.sh) isn't protected")
            }
        }
    }

    @Test
    fun felsongCodeDeletesOnlyThroughTheGuard() {
        val forbidden = listOf(".deleteRecursively(", ".delete()", ".renameTo(", "Files.delete(", "Files.move(")
        val offenders = felsongCode.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "core" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (forbidden.any { it in line } && !line.trim().startsWith("//")) "${file.name}:${i + 1}: ${line.trim()}" else null } }
            .filterNot { it.contains("tmp.renameTo(file)") } // FelsongServer.editConf: a config file in Felsong's etc, written beside then renamed
            .toList()
        assertEquals(emptyList(), offenders, "raw file operations in Felsong's code")
    }

    @Test
    fun felsongCodeNeverNamesTheOtherGamesStorage() {
        val folders = listOf(
            "WowFiles.server(", "WowFiles.etc(", "WowFiles.databases(", "WowFiles.data(", "WowFiles.client(",
            "WowFiles.logs(", "WowFiles.run(", "WowFiles.ctl(", "Database.query(", "Database.exec(", "SavedLogin.save(", "\"acore_",
            "TurtleFiles.", "TurtleServer.", "TurtleDb.", "TurtleJobs.", "TurtleLogin.", "TurtleSettings.", "\"tw_",
        )
        val offenders = felsongCode.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (folders.any { it in line }) "${file.name}:${i + 1}: ${line.trim()}" else null } }
            // The game switch asks Turtle's realm whether it runs, and stops it when the user says so (GameSwitch).
            .filterNot { it.startsWith("GameSwitch.kt") || it.startsWith("FelsongHome.kt") && "TurtleServer.isBusy" in it }
            .toList()
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun felsongsServerScriptKeepsToItsFolderAndPorts() {
        val text = ctl.readText()
        assertTrue("FILES=\"\${FILES:-/data/data/com.logie.warcraft/files/felsong}\"" in text)
        val db = Regex("""DB_PORT="\$\{DB_PORT:-(\d+)}"""").find(text)!!.groupValues[1].toInt()
        val world = Regex("""WORLD_PORT=(\d+)""").find(text)!!.groupValues[1].toInt()
        val instance = Regex("""INSTANCE_PORT=(\d+)""").find(text)!!.groupValues[1].toInt()
        val bnet = Regex("""BNET_PORT=(\d+)""").find(text)!!.groupValues[1].toInt()
        val rest = Regex("""REST_PORT=(\d+)""").find(text)!!.groupValues[1].toInt()
        assertEquals(FelsongRealm.DB_PORT, db)
        assertEquals(FelsongRealm.WORLD_PORT, world)
        assertEquals(FelsongRealm.INSTANCE_PORT, instance)
        assertEquals(FelsongRealm.BNET_PORT, bnet)
        assertEquals(FelsongRealm.REST_PORT, rest)
        for (line in text.lines().filter { !it.trim().startsWith("#") }) {
            assertFalse("/files/server" in line || "/files/mysql" in line || "/files/turtle" in line || "acore_" in line || "tw_" in line, "ctl.sh: $line")
        }
    }

    @Test
    fun felsongsServerScriptRefusesAnotherFolder() {
        val dir = createTempDirectory("ctl").toFile()
        try {
            for (files in listOf(dir.path, "/data/data/com.logie.warcraft/files", "/data/data/com.logie.warcraft/files/turtle")) {
                val process = ProcessBuilder("sh", ctl.absolutePath, "configure").apply { environment()["FILES"] = files }
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(2, process.waitFor(), output)
                assertTrue("files/felsong" in output, output)
            }
            assertEquals(0, dir.list()?.size ?: 0, "the script wrote something before refusing")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noPortIsShared() {
        assertEquals(emptyList(), FelsongRealm.SHARED_PORTS)
        for (port in FelsongRealm.PORTS) {
            assertFalse(port in TurtleRealm.PORTS, "$port is Turtle's too")
            assertFalse(port in TurtleRealm.OTHER_PORTS, "$port is ChromieCraft's too")
        }
        // The other realms' ports, as this side knows them, include Turtle's.
        for (port in TurtleRealm.PORTS) assertTrue(port in FelsongRealm.OTHER_PORTS)
    }

    @Test
    fun theRealmScriptAndTheAppAgreeOnTheRealm() {
        val seed = File("../felsong/server/seed.sql").readText()
        assertTrue("`name` = '${FelsongRealm.REALM_NAME}'" in seed)
        assertTrue("`port` = ${FelsongRealm.WORLD_PORT}, `gamePort` = ${FelsongRealm.INSTANCE_PORT}, `gamebuild` = ${FelsongRealm.CLIENT_BUILD}" in seed)
        val text = ctl.readText()
        assertTrue("setkey \"\$conf\" Game.Build.Version ${FelsongRealm.CLIENT_BUILD}" in text)
        assertTrue("export OPENSSL_MODULES=\"\$SERVER/${FelsongRealm.OPENSSL_MODULES}\"" in text)
    }
}
