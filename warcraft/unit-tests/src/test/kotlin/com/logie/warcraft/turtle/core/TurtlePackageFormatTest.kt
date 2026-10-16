package com.logie.warcraft.turtle.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turtle WoW's realm package unpacked the way the app unpacks it ([TurtlePackageFormat]), from a
 * package made the way scripts/package-tortoise.sh makes it (GNU tar, then xz), and packages with
 * entries the app must refuse.
 */
class TurtlePackageFormatTest {
    private lateinit var base: File
    private lateinit var layout: TurtleLayout
    private lateinit var staging: SafeFs

    @BeforeTest
    fun setUp() {
        base = createTempDirectory("turtle-package").toFile()
        layout = TurtleLayout(File(base, "files").apply { mkdirs() }, base)
        val fs = SafeFs(layout.root, layout.protectedFolders)
        fs.mkdirs(layout.serverStaging)
        staging = fs.within(layout.serverStaging)
    }

    @AfterTest
    fun tearDown() {
        base.walkBottomUp().forEach { it.delete() }
    }

    private fun unpack(pkg: File) = pkg.inputStream().use {
        TurtlePackageFormat.unpack(it, layout.serverStaging, staging, symlink = { target, link ->
            Files.createSymbolicLink(link.toPath(), Paths.get(target))
        })
    }

    @Test
    fun entryNames() {
        val r = TurtlePackageFormat::relative
        assertEquals("", r("server/"))
        assertEquals("", r("server"))
        assertEquals("", r("./server/"))
        // Folders' names end in "/", as tar writes them (the fault the first release had).
        assertEquals("sql", r("server/sql/"))
        assertEquals("sql/database_updates/auth", r("server/sql/database_updates/auth/"))
        assertEquals("bin/mangosd", r("server/bin/mangosd"))
        assertEquals("bin/mangosd", r("./server/bin/mangosd"))
        for (bad in listOf("server/../files/mysql", "server/bin/../../x", "/server/bin/x", "other/x", "server.new/x",
            "serverx/y", "server//x", "server/./x", "server/a\\..\\b", "", "./", "..")) {
            assertNull(r(bad), bad)
        }
        assertTrue(TurtlePackageFormat.isSafeLink("libACE.so.8.0.8"))
        assertFalse(TurtlePackageFormat.isSafeLink("/system/lib64/libc.so"))
        assertFalse(TurtlePackageFormat.isSafeLink("../../mysql/x"))
        assertFalse(TurtlePackageFormat.isSafeLink(""))
    }

    /** A package made like scripts/package-tortoise.sh's: `tar -C stage -cf - server | xz -T0 -6`. */
    @Test
    fun unpacksAPackageMadeLikeTheRealOne() {
        val stage = File(base, "stage")
        File(stage, "server/bin").mkdirs()
        File(stage, "server/bin/mangosd").apply { writeText("#!/bin/sh\n"); setExecutable(true, false) }
        File(stage, "server/ctl.sh").writeText("#!/bin/sh\n")
        File(stage, "server/sql/database_updates/auth").mkdirs() // empty: kept as a folder
        File(stage, "server/sql/base").mkdirs()
        File(stage, "server/sql/base/world.sql").writeText("CREATE TABLE x (a INT);\n")
        File(stage, "server/lib").mkdirs()
        File(stage, "server/lib/libACE.so.8.0.8").writeText("elf")
        Files.createSymbolicLink(File(stage, "server/lib/libACE.so").toPath(), Paths.get("libACE.so.8.0.8"))
        val pkg = File(base, "turtle-server.tar.xz")
        val made = ProcessBuilder("sh", "-c", "tar -C \"$1\" -cf - server | xz -T0 -6 > \"$2\"", "sh", stage.path, pkg.path)
            .redirectErrorStream(true).start()
        val output = made.inputStream.bufferedReader().readText()
        assertEquals(0, made.waitFor(), "tar | xz failed (both are needed for this test): $output")

        unpack(pkg)

        val server = layout.serverStaging
        assertTrue(File(server, "bin/mangosd").canExecute())
        assertFalse(File(server, "ctl.sh").canExecute())
        assertTrue(File(server, "sql/database_updates/auth").isDirectory)
        assertEquals("CREATE TABLE x (a INT);\n", File(server, "sql/base/world.sql").readText())
        assertEquals("libACE.so.8.0.8", Files.readSymbolicLink(File(server, "lib/libACE.so").toPath()).toString())
        assertEquals("elf", File(server, "lib/libACE.so").readText())
        // Nothing beside the staging folder.
        assertEquals(listOf(layout.serverStaging.name), layout.root.list()!!.toList())
    }

    private fun tarXz(vararg entries: Pair<TarArchiveEntry, String?>): File {
        val pkg = File(base, "bad-${System.nanoTime()}.tar.xz")
        TarArchiveOutputStream(XZCompressorOutputStream(pkg.outputStream())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((entry, text) in entries) {
                val bytes = text?.toByteArray() ?: ByteArray(0)
                if (entry.isFile) entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                if (entry.isFile) tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return pkg
    }

    private fun file(name: String, preserveAbsolute: Boolean = false) = TarArchiveEntry(name, preserveAbsolute) to "x"

    private fun link(name: String, target: String, flag: Byte) =
        TarArchiveEntry(name, flag).apply { linkName = target } to null

    @Test
    fun refusesEntriesThatLeadOut() {
        val outside = File(layout.files, "mysql/evil")
        val bad = listOf(
            tarXz(file("server/ok"), file("server/../../mysql/evil")),
            tarXz(file("/data/data/com.logie.warcraft/files/mysql/evil", preserveAbsolute = true)),
            tarXz(file("mysql/evil")),
            tarXz(link("server/lib/x", "/system/lib64/libc.so", TarConstants.LF_SYMLINK)),
            tarXz(link("server/lib/y", "../../../mysql", TarConstants.LF_SYMLINK)),
            tarXz(file("server/ok"), link("server/hard", "server/ok", TarConstants.LF_LINK)),
        )
        for (pkg in bad) {
            assertFailsWith<TurtlePackageFormat.BadEntry>(pkg.name) { unpack(pkg) }
        }
        assertFalse(outside.exists())
        assertFalse(File(layout.root, "mysql").exists())
    }

    @Test
    fun stopsWhenCancelled() {
        val pkg = tarXz(file("server/a"), file("server/b"))
        assertFailsWith<VerifiedDownload.Cancelled> {
            pkg.inputStream().use {
                TurtlePackageFormat.unpack(it, layout.serverStaging, staging, symlink = { _, _ -> }, cancelled = { true })
            }
        }
    }
}
