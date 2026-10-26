// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.os.Handler
import android.os.Looper
import android.system.Os
import com.logie.warcrafts.library.Wc3Movie
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Which of the game's files Warcraft III has open, a few times a second, from its processes'
 * `/proc/<pid>/fd` (the app's own processes: readable, cheap). It tells what the game is doing
 * whatever its art looks like (Classic+ replaces the console the picture was matched against):
 *
 * - **A match is on** while the game writes its replay (`TempReplay.w3g`, open from the loading
 *   screen to the end of the match, every match: seen in 1.27 under Wine), not in the menus.
 * - **A film plays** while one of `Movies\*` is open ([Wc3MoviePlayer]).
 */
class Wc3Files(game: File) {
    private val gamePath = runCatching { game.canonicalPath }.getOrDefault(game.path)
    private val own = android.os.Process.myPid()
    private val main = Handler(Looper.getMainLooper())
    private val scanner = Executors.newSingleThreadScheduledExecutor()
    private var running = false
    private val listeners = ArrayList<(Wc3Files) -> Unit>()

    /** Whether a match is on (the game's replay open). */
    @Volatile var inMatch = false
        private set
    /** The film the game has open, by its path, or null. */
    @Volatile var film: String? = null
        private set
    /** Whether the files have been read at least once. */
    @Volatile var known = false
        private set

    /** Hears every reading, on the main thread. */
    fun listen(listener: (Wc3Files) -> Unit) {
        listeners += listener
    }

    fun start() {
        if (running) return
        running = true
        scanner.scheduleWithFixedDelay({
            val open = runCatching { openFiles() }.getOrNull() ?: return@scheduleWithFixedDelay
            main.post {
                if (!running) return@post
                inMatch = open.any { it.substringAfterLast('/').equals(REPLAY, ignoreCase = true) }
                film = open.firstOrNull { Wc3Movie.isFilm(it, gamePath) }
                known = true
                for (listener in listeners) listener(this)
            }
        }, 0, POLL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        scanner.shutdownNow()
    }

    /** The game's files its processes have open: those of Wine and the programs under it ("wine" or ".exe" in their command line, as Winlator tells them). */
    private fun openFiles(): List<String> {
        val found = LinkedHashSet<String>()
        for (pid in File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }) {
            if (pid == own) continue
            val command = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: continue
            if (!command.contains(".exe", ignoreCase = true) && !command.contains("wine", ignoreCase = true)) continue
            for (fd in File("/proc/$pid/fd").list().orEmpty()) {
                val target = runCatching { Os.readlink("/proc/$pid/fd/$fd") }.getOrNull() ?: continue
                if (target.startsWith("$gamePath/")) found += target
            }
        }
        return found.toList()
    }

    companion object {
        /** The replay the game writes during every match. */
        const val REPLAY = "TempReplay.w3g"
        private const val POLL_MS = 400L
    }
}
