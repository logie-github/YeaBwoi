// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.content.Context
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Title
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/**
 * What a game said last when it stopped by itself, for the launcher to show (and the user to copy).
 *
 * Warcraft I and II: while Stratagus plays, [StratagusActivity] keeps a file named `running` in the
 * game's folder holding its process's id, deleted when the game is left from the app's menu. The
 * engine writes its output to `stdout.txt` and `stderr.txt` there, a crash's signal and stack too,
 * and "Stratagus: exit 0" when it's quit from its own menu (patch 0003). A `running` file whose
 * process is gone, without that line, is a game that stopped by itself: its last output, and
 * Android's log for its process (a crash's "Fatal signal", a Java exception), make the report.
 *
 * The app's own Java exceptions, in any of its processes, are written to `files/app-stopped.txt`
 * ([catchJavaCrashes]) and shown the same way.
 */
object GameStops {
    class Report(val title: Title?, val text: String)

    private const val RUNNING = "running"
    private const val NORMAL_EXIT = "Stratagus: exit 0"

    /** A Stratagus game starts in this process: the last run's output goes, this one's is watched. */
    fun started(dataDir: File) {
        File(dataDir, "stdout.txt").delete()
        File(dataDir, "stderr.txt").delete()
        File(dataDir, RUNNING).writeText(android.os.Process.myPid().toString())
    }

    /** The game was left from the app's menu. */
    fun left(dataDir: File) {
        File(dataDir, RUNNING).delete()
    }

    /** Java exceptions of this process are written down for the launcher, then go on as before. */
    fun catchJavaCrashes(context: Context, where: String) {
        val file = File(context.filesDir, "app-stopped.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file.writeText("$where, thread ${thread.name}:\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of a game or the app having stopped by itself since last looked, once. */
    fun take(context: Context): Report? {
        val app = File(context.filesDir, "app-stopped.txt")
        if (app.isFile) {
            val text = runCatching { app.readText() }.getOrDefault("")
            app.delete()
            if (text.isNotBlank()) return Report(null, text.lines().take(80).joinToString("\n"))
        }
        for (title in listOf(Title.WC1, Title.WC2)) {
            val dir = RtsFiles.stratagusDir(context, title.install)
            val running = File(dir, RUNNING)
            if (!running.isFile) continue
            val pid = runCatching { running.readText().trim().toInt() }.getOrNull()
            if (pid != null && isGameProcess(pid)) continue
            running.delete()
            val errors = tail(File(dir, "stderr.txt"), 60)
            if (errors.lastOrNull { it.isNotBlank() }?.trim() == NORMAL_EXIT) continue
            val output = tail(File(dir, "stdout.txt"), 25)
            val log = if (pid != null) logcat(pid) else emptyList()
            // Nothing said at all and nothing in Android's log: ended from outside (swiped away).
            if (errors.isEmpty() && log.none { it.contains("Fatal signal") || it.contains("FATAL EXCEPTION") }) continue
            val text = buildString {
                appendLine("${install(title.install)} — stderr.txt:")
                errors.ifEmpty { listOf("(empty)") }.forEach { appendLine(it) }
                appendLine()
                appendLine("stdout.txt (last lines):")
                output.ifEmpty { listOf("(empty)") }.forEach { appendLine(it) }
                if (log.isNotEmpty()) {
                    appendLine()
                    appendLine("Android's log for the game's process:")
                    log.forEach { appendLine(it) }
                }
            }.trimEnd()
            runCatching { File(dir, "last-stop.txt").writeText(text) }
            return Report(title, text)
        }
        return null
    }

    private fun install(install: Install) = if (install == Install.WC1) "Warcraft I" else "Warcraft II"

    /** The game's process (`:stratagus`) still running: the launcher came back while it plays. */
    private fun isGameProcess(pid: Int): Boolean =
        runCatching { File("/proc/$pid/cmdline").readText().contains(":stratagus") }.getOrDefault(false)

    private fun tail(file: File, lines: Int): List<String> =
        if (file.isFile) runCatching { file.readLines().takeLast(lines) }.getOrDefault(emptyList()) else emptyList()

    /** Android's log lines of process [pid] (an app may read its own processes' log). */
    private fun logcat(pid: Int): List<String> = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid", "-t", "400")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)
        lines.filter { it.isNotBlank() && !it.startsWith("-----") }.takeLast(80)
    }.getOrDefault(emptyList())
}
