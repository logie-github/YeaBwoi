package com.logie.warcraft.turtle.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Turtle WoW apart from the 3.3.5a game: its folders, its server script, its ports, and the rule
 * that its code only deletes or moves files through [SafeFs]. Reads the app's own sources, so a
 * folder the ChromieCraft side adds later, or a raw delete in Turtle's code, fails here.
 */
class IsolationTest {
    private val app = File("../android/app/src/main/java/com/logie/warcraft")
    private val turtleCode = File(app, "turtle")
    private val ctl = File("../turtle/server/ctl.sh")

    @Test
    fun everyTurtlePathIsInsideTurtlesFolder() {
        val files = File("/data/user/0/com.logie.warcraft/files")
        val layout = TurtleLayout(files, files.parentFile)
        val paths = listOf(
            layout.server, layout.serverStaging, layout.serverOld, layout.ctl, layout.etc, layout.databases,
            layout.data, layout.dataWork, layout.client, layout.clientStaging, layout.logs, layout.run,
            layout.downloads, layout.backups, layout.stateFile,
        )
        for (path in paths) {
            assertTrue(path.toPath().normalize().startsWith(layout.root.toPath()), "${path.path} is outside ${layout.root.path}")
            for (other in layout.protectedFolders) {
                assertFalse(path.toPath().startsWith(other.toPath()), "${path.path} is in ${other.path}")
                assertFalse(other.toPath().startsWith(path.toPath()), "${other.path} is in ${path.path}")
            }
        }
        assertEquals(listOf(layout.root), layout.all)
        assertTrue(TurtleLayout.STANDARD_ROOT.endsWith("/files/" + TurtleLayout.ROOT_NAME))
    }

    @Test
    fun everyFolderTheOtherGameUsesIsProtected() {
        // Folders the 3.3.5a side makes in the app's files folder: File(WowFiles.root(context), "x")
        // or File(root(context), "x") in its sources.
        val patterns = listOf(
            Regex("""File\((?:WowFiles\.)?root\(context\), "([^"/]+)"\)"""),
            Regex("""File\(context\.filesDir, "([^"/]+)[/"]"""),
            Regex("""staging\(context, "([^"/]+)"\)"""),
        )
        val names = app.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/turtle/") }
            .flatMap { file -> val text = file.readText(); patterns.flatMap { p -> p.findAll(text).map { it.groupValues[1] } } }
            .toSet()
        assertTrue("server" in names && "client" in names && "mysql" in names, "the scan found $names")
        for (name in names) {
            assertTrue(name in TurtleLayout.PROTECTED_NAMES, "$name (a folder of the 3.3.5a game) isn't protected from Turtle WoW's code")
            assertNotEquals(TurtleLayout.ROOT_NAME, name)
        }
        // The server script's folders too (warcraft/server/ctl.sh: FILES/<name>).
        val wrathCtl = File("../server/ctl.sh").readText()
        val ctlNames = Regex("\\\$FILES/([a-z][a-z-]*(?:\\.[a-z]+)?)").findAll(wrathCtl).map { it.groupValues[1] }.toSet()
        assertTrue("server" in ctlNames && "mysql" in ctlNames, "the scan of ctl.sh found $ctlNames")
        for (name in ctlNames) assertTrue(name in TurtleLayout.PROTECTED_NAMES, "$name (ChromieCraft's ctl.sh) isn't protected")
    }

    @Test
    fun turtleCodeDeletesOnlyThroughTheGuard() {
        // No raw deletes, renames or recursive deletes in Turtle's Android code: SafeFs does them.
        val forbidden = listOf(".deleteRecursively(", ".delete()", ".renameTo(", "Files.delete(", "Files.move(")
        val offenders = turtleCode.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "core" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (forbidden.any { it in line } && !line.trim().startsWith("//")) "${file.name}:${i + 1}: ${line.trim()}" else null } }
            .filterNot { it.contains("tmp.renameTo(file)") } // TurtleServer.editConf: a config file in Turtle's etc, written beside then renamed
            .toList()
        assertEquals(emptyList(), offenders, "raw file operations in Turtle WoW's code")
    }

    @Test
    fun turtleCodeNeverNamesTheOtherGamesStorage() {
        // Turtle's code may read WowFiles' helpers (isStandardPath, tail, readFrom, sizeOf) but
        // never the 3.3.5a game's folders.
        val folders = listOf("WowFiles.server(", "WowFiles.etc(", "WowFiles.databases(", "WowFiles.data(", "WowFiles.client(",
            "WowFiles.logs(", "WowFiles.run(", "WowFiles.ctl(", "Database.query(", "Database.exec(", "Accounts.", "SavedLogin.save(", "\"acore_")
        val offenders = turtleCode.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (folders.any { it in line }) "${file.name}:${i + 1}: ${line.trim()}" else null } }
            .filterNot { it.contains("TurtleAccounts.") }
            .toList()
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun turtlesServerScriptKeepsToItsFolderAndPorts() {
        val text = ctl.readText()
        assertTrue("FILES=\"\${FILES:-/data/data/com.logie.warcraft/files/turtle}\"" in text)
        // Its database and world ports aren't ChromieCraft's (3310, 8085) or Wrath Remix's (8086).
        val db = Regex("""DB_PORT="\$\{DB_PORT:-(\d+)}"""").find(text)!!.groupValues[1].toInt()
        val world = Regex("""WORLD_PORT=(\d+)""").find(text)!!.groupValues[1].toInt()
        assertEquals(TurtleRealm.DB_PORT, db)
        assertEquals(TurtleRealm.WORLD_PORT, world)
        assertFalse(db in listOf(3310, 8085, 8086))
        assertFalse(world in listOf(3310, 8085, 8086))
        // Every path it uses is under $FILES (Turtle's folder): none written out.
        for (line in text.lines().filter { !it.trim().startsWith("#") }) {
            assertFalse("/files/server" in line || "/files/mysql" in line || "acore_" in line, "ctl.sh: $line")
        }
    }

    @Test
    fun turtlesServerScriptRefusesAnotherFolder() {
        val dir = createTempDirectory("ctl").toFile()
        try {
            for (files in listOf(dir.path, "/data/data/com.logie.warcraft/files", "/data/data/com.logie.warcraft/files/server")) {
                val process = ProcessBuilder("sh", ctl.absolutePath, "configure").apply { environment()["FILES"] = files }
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(2, process.waitFor(), output)
                assertTrue("files/turtle" in output, output)
            }
            assertEquals(0, dir.list()?.size ?: 0, "the script wrote something before refusing")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun onlyTheLoginPortIsShared() {
        assertEquals(listOf(TurtleRealm.REALMD_PORT), TurtleRealm.SHARED_PORTS)
        assertEquals(3724, TurtleRealm.REALMD_PORT)
    }
}
