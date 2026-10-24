// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local.core

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadlessWineTest {
    private val folder = Files.createTempDirectory("headless-wine").toFile()
    private val kill: (Int) -> Unit = { pid -> ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() } }

    @AfterTest fun cleanUp() { folder.deleteRecursively() }

    /** A [HeadlessWine] whose "wine" is the shell: its programs are shell commands. */
    private fun shell(marker: String = "test-${System.nanoTime()}", environment: Map<String, String> = emptyMap()) =
        HeadlessWine(emptyList(), "/bin/sh", "/bin/true", environment, folder, File(folder, "host.log"), "WINLATOR_REALM" to marker, kill)

    @Test fun findsProcessesByTheMarkerInTheirEnvironment() {
        val proc = File(folder, "proc")
        fun process(pid: String, vararg environ: String) =
            File(proc, pid).apply { mkdirs() }.resolve("environ").writeBytes(environ.joinToString("\u0000", postfix = "\u0000").toByteArray())
        process("100", "HOME=/x", "WINLATOR_REALM=spp_session", "PATH=/bin")
        process("101", "WINLATOR_REALM=spp_session_other")
        process("102", "XWINLATOR_REALM=spp_session")
        process("103", "WINLATOR_SESSION=1", "WINLATOR_REALM=spp_session")
        process("self")
        File(proc, "104").mkdirs() // (no environ readable)
        assertEquals(listOf(100, 103), HeadlessWine.processesWith(proc, "WINLATOR_REALM", "spp_session"))
    }

    @Test fun programsGetTheMarkerAndNoneOfASessionsVariables() {
        val wine = shell(marker = "env-check", environment = mapOf("WINEPREFIX" to "/p/.wine", "DISPLAY" to ":0", "WINLATOR_SESSION" to "7"))
        val process = wine.start("env", listOf("-c", "env > '${File(folder, "env.txt")}'"))
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val env = File(folder, "env.txt").readLines()
        assertTrue("WINLATOR_REALM=env-check" in env)
        assertTrue("WINEPREFIX=/p/.wine" in env)
        assertTrue(env.none { it.startsWith("DISPLAY=") || it.startsWith("WINLATOR_SESSION=") || it.startsWith("ANDROID_SYSVSHM_SERVER=") })
    }

    @Test fun reportsHowAProgramEnded() {
        val wine = shell()
        val ok = wine.start("ok", listOf("-c", "echo said; exit 0"))
        val failed = wine.start("failed", listOf("-c", "exit 3"))
        val killed = wine.start("killed", listOf("-c", "kill -9 $$"))
        listOf(ok, failed, killed).forEach { assertTrue(it.waitFor(10, TimeUnit.SECONDS)) }
        assertEquals(0, wine.exitStatus("ok"))
        assertEquals(3, wine.exitStatus("failed"))
        assertEquals(137, wine.exitStatus("killed"))
        assertNull(wine.exitStatus("never"))
        assertTrue("said" in File(folder, "host.log").readText())
    }

    @Test fun aRunningProgramIsntStartedTwice() {
        val wine = shell()
        wine.start("long", listOf("-c", "sleep 30"))
        assertTrue(wine.running("long"))
        assertTrue(runCatching { wine.start("long", listOf("-c", "true")) }.isFailure)
        wine.killAll()
    }

    @Test fun killAllEndsEveryProcessWithTheMarkerAndNoOther() {
        val marker = "kill-${System.nanoTime()}"
        val wine = shell(marker)
        // (a child it starts, as Wine starts the servers: found by the marker it inherits)
        wine.start("parent", listOf("-c", "sleep 60 & sleep 60"))
        val other = ProcessBuilder("sleep", "60").start()
        try {
            var found = emptyList<Int>()
            repeat(50) { if (found.size < 2) { Thread.sleep(100); found = wine.processes() } }
            assertTrue(found.size >= 2, "found $found")
            wine.killAll()
            repeat(50) { if (wine.processes().isNotEmpty()) Thread.sleep(100) }
            assertEquals(emptyList(), wine.processes())
            assertTrue(other.isAlive)
            assertFalse(wine.running("parent"))
        } finally { other.destroyForcibly() }
    }

    /**
     * The realm's database and its tools under real Wine with no display, as the app runs them: set
     * HEADLESS_WINE_TESTBED to a test bed (see warcraft/SPP.md: R/runtime's scripts, the prefix, the
     * runtime's Wine) to run it; skipped otherwise.
     */
    @Test fun realmDatabaseUnderWineWithNoDisplay() {
        val bed = System.getenv("HEADLESS_WINE_TESTBED")?.let(::File) ?: return
        val rootfs = File(System.getenv("HEADLESS_WINE_ROOTFS") ?: return)
        val bin = File(rootfs, "opt/wine/bin")
        val marker = "testbed-${System.nanoTime()}"
        val environment = mapOf(
            "WINEPREFIX" to File(bed, "prefix").path, "WINEDEBUG" to "-all", "WINEESYNC" to "0",
            "LD_LIBRARY_PATH" to File(rootfs, "lib/x86_64-linux-gnu").path, "TMPDIR" to File(rootfs, "tmp").path,
            "PATH" to "$bin:" + System.getenv("PATH"))
        val wine = HeadlessWine(emptyList(), "$bin/wine", "$bin/wineserver", environment, bed, File(folder, "host.log"), "WINLATOR_REALM" to marker, kill)
        val runtime = File(bed, "R/runtime")
        fun exit(name: String) = File(runtime, "$name.exit")
        fun open(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess
        fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (!condition()) { if (System.currentTimeMillis() > deadline) return false; Thread.sleep(250) }
            return true
        }
        File(runtime, "probe.cmd").writeText("@echo off\r\nver >\"R:\\runtime\\probe.log\" 2>&1\r\necho %ERRORLEVEL% >\"R:\\runtime\\probe.exit\"\r\n")
        listOf("probe", "database", "shutdown-db").forEach { exit(it).delete() }
        try {
            assertEquals(0, wine.startServer())
            wine.startScript("probe", "R:\\runtime\\probe.cmd")
            assertTrue(waitFor(120) { exit("probe").isFile }, File(folder, "host.log").readText())
            assertEquals("0", exit("probe").readText().trim())
            assertTrue("Windows" in File(runtime, "probe.log").readText())

            wine.startScript("database", "R:\\runtime\\database.cmd")
            assertTrue(waitFor(180) { open(3312) }, File(runtime, "database.log").readText())
            assertTrue(wine.processes().size >= 3, "wineserver, the database's cmd and mysqld: ${wine.processes()}")

            wine.startScript("shutdown-db", "R:\\runtime\\shutdown-db.cmd")
            assertTrue(waitFor(120) { exit("database").isFile && !open(3312) })
            assertEquals("0", exit("database").readText().trim())
            assertEquals(0, wine.exitStatus("database"))
        } finally {
            wine.stopServer()
            waitFor(30) { wine.processes().isEmpty() }
            wine.killAll()
            File(runtime, "probe.cmd").delete()
        }
        assertEquals(emptyList(), wine.processes())
    }
}
