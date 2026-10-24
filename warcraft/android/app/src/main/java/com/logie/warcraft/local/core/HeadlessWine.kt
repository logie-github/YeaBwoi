// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local.core

import java.io.File
import java.io.IOException

/**
 * A local realm's Windows programs run by Wine with no screen: each a process of its own, started
 * from the app (no Activity, no X server), [launcher] then Wine's `wine` ([wine]) and the program's
 * arguments, in [environment] (no DISPLAY: nothing of theirs depends on a game's screen). Every
 * process of the realm, and every one Wine starts for them, carries [marker] in its environment, so
 * the realm's processes are found ([processes]) and ended ([killAll]) as a whole, and a game's session
 * joining the same Wine server ends only its own. What they print goes to [log].
 *
 * Platform-independent: [kill] ends a process by its id ([android.os.Process.killProcess] on a phone).
 */
class HeadlessWine(
    private val launcher: List<String>,
    private val wine: String,
    private val wineserver: String,
    environment: Map<String, String>,
    private val workingDirectory: File,
    private val log: File,
    val marker: Pair<String, String>,
    private val kill: (Int) -> Unit,
    private val proc: File = File("/proc"),
) {
    private val environment = environment - UNSHARED + marker
    private val launched = linkedMapOf<String, Process>()

    /** Starts [arguments] (after `wine`) as [name]; a program of that name still running is left. */
    @Synchronized
    fun start(name: String, arguments: List<String>): Process {
        launched[name]?.takeIf { it.isAlive }?.let { throw IOException("$name is already running.") }
        val process = spawn(listOf(wine) + arguments)
        launched[name] = process
        return process
    }

    /** `cmd.exe /d /c [script]` (a Windows path) as [name]. */
    fun startScript(name: String, script: String): Process = start(name, listOf("cmd.exe", "/d", "/c", script))

    /** The Unix exit status of [name]'s process once it has ended (128 + n: ended by signal n), else null. */
    @Synchronized
    fun exitStatus(name: String): Int? = launched[name]?.takeUnless { it.isAlive }?.exitValue()

    @Synchronized
    fun running(name: String): Boolean = launched[name]?.isAlive == true

    /** The Wine server, staying until [stopServer] (`wineserver -p`); the processes find it themselves. */
    fun startServer(): Int = run(listOf(wineserver, "-p"), 60)

    /** Ends the Wine server and every program of its prefix (`wineserver -k`). */
    fun stopServer(): Int = run(listOf(wineserver, "-k"), 60)

    /** Waits up to [seconds] for [arguments] to end; its exit status (-1: still running, then killed). */
    fun run(arguments: List<String>, seconds: Long): Int {
        val process = spawn(arguments)
        if (!process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1
        }
        return process.exitValue()
    }

    /** The ids of the realm's processes still running (every one with [marker]). */
    fun processes(): List<Int> = processesWith(proc, marker.first, marker.second)

    /** Ends every process of the realm at once (a last resort: no program saves anything). */
    fun killAll() {
        processes().forEach { pid -> runCatching { kill(pid) } }
        synchronized(this) { launched.values.forEach { it.destroyForcibly() } }
    }

    /** Forgets the ended programs, keeping the running ones. */
    @Synchronized
    fun forgetEnded() { launched.entries.removeAll { !it.value.isAlive } }

    private fun spawn(arguments: List<String>): Process {
        log.parentFile?.mkdirs()
        val builder = ProcessBuilder(launcher + arguments)
            .directory(workingDirectory)
            .redirectInput(File("/dev/null"))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        val env = builder.environment()
        UNSHARED.forEach { env.remove(it) }
        env.putAll(environment)
        return builder.start()
    }

    companion object {
        /** A game's session's variables (its X server, shared memory, sound), never a realm program's. */
        val UNSHARED = setOf("DISPLAY", "WAYLAND_DISPLAY", "ANDROID_SYSVSHM_SERVER", "ANDROID_ALSA_SERVER",
            "ANDROID_ASERVER_USE_SHM", "PULSE_SERVER", "PULSE_LATENCY_MSEC", "WINLATOR_SESSION")

        /** The ids of the processes under [proc] whose environment has [name]=[value] (those readable). */
        fun processesWith(proc: File, name: String, value: String): List<Int> {
            val wanted = "$name=$value".toByteArray()
            return (proc.listFiles() ?: emptyArray()).mapNotNull { entry ->
                val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
                val environ = runCatching { File(entry, "environ").readBytes() }.getOrNull() ?: return@mapNotNull null
                pid.takeIf { split(environ).any { it.contentEquals(wanted) } }
            }.sorted()
        }

        private fun split(bytes: ByteArray): List<ByteArray> {
            val parts = mutableListOf<ByteArray>()
            var start = 0
            for (i in 0..bytes.size) {
                if (i == bytes.size || bytes[i] == 0.toByte()) {
                    if (i > start) parts += bytes.copyOfRange(start, i)
                    start = i + 1
                }
            }
            return parts
        }
    }
}
