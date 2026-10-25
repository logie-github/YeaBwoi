// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.content.Context
import android.os.Build
import com.winlator.core.Callback
import com.winlator.core.ProcessHelper
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened in Warcraft III's last run, for the user to copy and send: the settings it ran with,
 * what Wine and the programs under it printed (Wine's errors and the libraries loaded: the
 * container's `WINEDEBUG`, [WINE_DEBUG]), what the app read from the game's picture
 * ([Wc3Watcher]) and the last taps, with what each was sent as. Kept in `files/wc3-last-run.txt`,
 * started again for each run; Game files → Copy report.
 */
object Wc3Report {
    /** Wine's output: its errors and fixmes (on by default) and every library it loads (native or Wine's own). */
    const val WINE_DEBUG = "+loaddll"

    private const val MAX_BYTES = 1_500_000
    private const val TAIL_LINES = 400

    private var file: File? = null
    private var writer: Writer? = null
    private var bytes = 0
    private var unflushed = 0
    private val tail = ArrayDeque<String>()
    private var dropped = 0
    private val taps = ArrayDeque<String>()

    private val output = Callback<String> { line -> add(line) }

    fun file(context: Context) = File(context.filesDir, "wc3-last-run.txt")

    /** A run starts: the report again from nothing, the settings at its top, Wine's output from here on. */
    @Synchronized
    fun start(context: Context, settings: List<Pair<String, String>>) {
        runCatching { writer?.close() }
        tail.clear()
        taps.clear()
        dropped = 0
        bytes = 0
        val target = file(context)
        file = target
        writer = runCatching { target.bufferedWriter() }.getOrNull()
        add("Warcraft III, " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        add("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        for ((name, value) in settings) add("$name: $value")
        add("---- output ----")
        listen()
    }

    /**
     * Wine's output to this report from here on. Winlator's game screen takes every listener off as
     * it opens (before it starts Wine), so the game screen calls this again once it's up
     * ([GestureView]).
     */
    fun listen() {
        ProcessHelper.removeDebugCallback(output)
        ProcessHelper.addDebugCallback(output)
    }

    /** A line of the app's own (a reading of the picture, a tap). */
    fun note(line: String) = add("[app] $line")

    /** A tap or a hold and what it was sent as, the last 40 kept for the report's end. */
    @Synchronized
    fun tap(line: String) {
        taps.addLast(line)
        if (taps.size > 40) taps.removeFirst()
    }

    @Synchronized
    private fun add(line: String) {
        val out = writer ?: return
        if (bytes < MAX_BYTES) {
            runCatching {
                out.write(line)
                out.write("\n")
                // Written out every few lines: what was printed before the game was swiped away stays.
                if (++unflushed >= 20 || line.startsWith("[app]")) {
                    out.flush()
                    unflushed = 0
                }
            }
            bytes += line.length + 1
        } else {
            tail.addLast(line)
            if (tail.size > TAIL_LINES) {
                tail.removeFirst()
                dropped++
            }
        }
    }

    /** The game was left: the report's end (what didn't fit, the last taps, the picture's readings). */
    @Synchronized
    fun finish(summary: List<String>) {
        val out = writer ?: return
        runCatching {
            if (tail.isNotEmpty()) {
                out.write("---- ($dropped lines left out) ----\n")
                for (line in tail) out.write(line + "\n")
            }
            out.write("---- the game's picture ----\n")
            for (line in summary) out.write(line + "\n")
            out.write("---- the last taps ----\n")
            for (line in taps) out.write(line + "\n")
            out.close()
        }
        writer = null
        tail.clear()
        ProcessHelper.removeDebugCallback(output)
    }

    /** The last run's report, at most [limit] characters (its start and its end), or null if there's none. */
    fun read(context: Context, limit: Int = 60_000): String? {
        val target = file(context)
        if (!target.isFile) return null
        val text = runCatching { target.readText() }.getOrNull() ?: return null
        if (text.length <= limit) return text
        val head = text.take(limit / 3)
        val end = text.takeLast(limit - head.length)
        return "$head\n…\n$end"
    }
}
