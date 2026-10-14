package com.logie.warcraft.turtle.core

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard every Turtle WoW delete, move and replace goes through: nothing outside Turtle's
 * folder, nothing of the other games', no link followed. A stand-in for the app's files folder
 * with the ChromieCraft realm's folders in it, which must come out of every test untouched.
 */
class SafeFsTest {
    private lateinit var base: File
    private lateinit var files: File
    private lateinit var layout: TurtleLayout
    private lateinit var fs: SafeFs

    /** The other game's files, with what's in them, to check afterwards. */
    private val wrath = mutableMapOf<File, String>()

    @BeforeTest
    fun setUp() {
        base = createTempDirectory("safefs").toFile()
        files = File(base, "files").apply { mkdirs() }
        layout = TurtleLayout(files, base)
        for (name in TurtleLayout.PROTECTED_NAMES) {
            val file = File(files, "$name/keep.txt")
            file.parentFile.mkdirs()
            file.writeText("wrath $name")
            wrath[file] = "wrath $name"
        }
        File(base, "rootfs/home/xuser-1").mkdirs()
        File(base, "rootfs/home/xuser-1/prefix.txt").writeText("wow prefix").also { wrath[File(base, "rootfs/home/xuser-1/prefix.txt")] = "wow prefix" }
        layout.root.mkdirs()
        fs = SafeFs(layout.root, layout.protectedFolders)
    }

    @AfterTest
    fun tearDown() {
        // Every other game's file still there, unchanged.
        for ((file, text) in wrath) {
            assertTrue(file.isFile, "${file.path} was removed")
            assertEquals(text, file.readText(), "${file.path} was changed")
        }
        base.walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun deletesInsideTurtle() {
        val folder = File(layout.root, "server/bin").apply { mkdirs() }
        File(folder, "mangosd").writeText("x")
        fs.delete(File(layout.root, "server"))
        assertFalse(File(layout.root, "server").exists())
        assertTrue(layout.root.isDirectory)
    }

    @Test
    fun refusesOutsideTurtle() {
        for (target in listOf(
            files,
            File(files, "server"),
            File(files, "mysql/keep.txt"),
            File(layout.root, "../server"),
            File(layout.root, "../../files/client"),
            File(base, "rootfs"),
            base,
            File("/"),
        )) {
            assertFailsWith<SafeFs.Refused>(target.path) { fs.delete(target) }
            assertFailsWith<SafeFs.Refused>(target.path) { fs.check(target) }
        }
    }

    @Test
    fun refusesTheTurtleFolderItself() {
        assertFailsWith<SafeFs.Refused> { fs.delete(layout.root) }
        assertTrue(layout.root.isDirectory)
    }

    @Test
    fun widerScopeStillProtectsTheOtherGame() {
        // As Uninstall uses it: the app's files folder, every other game's folder protected.
        val wide = SafeFs(files, layout.protectedFolders)
        for (name in TurtleLayout.PROTECTED_NAMES) {
            assertFailsWith<SafeFs.Refused>(name) { wide.delete(File(files, name)) }
            assertFailsWith<SafeFs.Refused>(name) { wide.delete(File(files, "$name/keep.txt")) }
        }
        assertFailsWith<SafeFs.Refused> { wide.deleteContents(files) }
        File(layout.root, "client/WoW.exe").apply { parentFile.mkdirs(); writeText("exe") }
        wide.delete(layout.root)
        assertFalse(layout.root.exists())
    }

    @Test
    fun neverFollowsLinks() {
        // A link in Turtle's folder to the other game's client, and to one of its files: deleting
        // the folder removes the links, not what they point to.
        val folder = File(layout.root, "client").apply { mkdirs() }
        Files.createSymbolicLink(File(folder, "d_drive").toPath(), File(files, "client").toPath())
        Files.createSymbolicLink(File(folder, "file_link").toPath(), File(files, "mysql/keep.txt").toPath())
        Files.createSymbolicLink(File(folder, "z_drive").toPath(), File("/").toPath())
        fs.delete(folder)
        assertFalse(folder.exists())
    }

    @Test
    fun refusesALinkThatLeadsOut() {
        val link = File(layout.root, "escape")
        Files.createSymbolicLink(link.toPath(), File(files, "mysql").toPath())
        // Through the link, the path is in ChromieCraft's databases: refused.
        assertFailsWith<SafeFs.Refused> { fs.delete(File(link, "keep.txt")) }
        assertFailsWith<SafeFs.Refused> { fs.check(File(link, "anything")) }
        // The link itself is Turtle's, and goes as a link.
        fs.delete(link)
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun replaceSwapsAndCleansUp() {
        val target = File(layout.root, "server").apply { mkdirs() }
        File(target, "old").writeText("old")
        val staging = File(layout.root, "server.new").apply { mkdirs() }
        File(staging, "new").writeText("new")
        fs.replace(staging, target)
        assertTrue(File(target, "new").isFile)
        assertFalse(File(target, "old").exists())
        assertFalse(staging.exists())
        assertFalse(File(layout.root, "server.old").exists())
    }

    @Test
    fun replaceRefusesOtherGamesFolders() {
        val staging = File(layout.root, "client.import").apply { mkdirs() }
        assertFailsWith<SafeFs.Refused> { fs.replace(staging, File(files, "client")) }
        assertTrue(staging.isDirectory)
    }

    @Test
    fun writesAtomically() {
        fs.writeAtomically(layout.stateFile, "{}")
        assertEquals("{}", layout.stateFile.readText())
        assertFalse(File(layout.stateFile.path + ".tmp").exists())
        assertFailsWith<SafeFs.Refused> { fs.writeAtomically(File(files, "etc/worldserver.conf"), "x") }
    }

    @Test
    fun withinNarrowsTheScope() {
        val server = File(layout.root, "server.new").apply { mkdirs() }
        val narrow = fs.within(server)
        assertFailsWith<SafeFs.Refused> { narrow.check(File(layout.root, "mysql")) }
        narrow.check(File(server, "bin/mangosd"))
    }
}
