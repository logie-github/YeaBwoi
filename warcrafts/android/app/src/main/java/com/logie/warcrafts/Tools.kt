// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the programs the app ships for its imports, which Android lets it run from its native
 * library folder: innoextract (libinnoextract.so, GOG's installers), war1tool and wartool
 * (libwar1tool.so, libwartool.so, [Conversion]). Stopped when the import is ([Imports.cancelled]).
 */
object Tools {
    class Result(val code: Int, val tail: List<String>)

    fun file(context: Context, name: String): File = File(context.applicationInfo.nativeLibraryDir, name)

    /** Runs [command] in [directory], its output line by line to [onLine] (and into [log]). */
    fun run(command: List<String>, directory: File, log: File?, environment: Map<String, String> = emptyMap(), onLine: (String) -> Unit): Result {
        val process = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        val watcher = thread(isDaemon = true, name = "tool-watcher") {
            try {
                while (process.isAlive) {
                    if (Imports.cancelled) process.destroy()
                    Thread.sleep(300)
                }
            } catch (_: InterruptedException) {
            }
        }
        val tail = ArrayDeque<String>()
        val out = log?.bufferedWriter()
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                out?.appendLine(line)
                tail.addLast(line)
                if (tail.size > 12) tail.removeFirst()
                onLine(line)
            }
        } finally {
            out?.close()
        }
        val code = process.waitFor()
        watcher.interrupt()
        if (Imports.cancelled) throw Imports.ImportException("Stopped")
        return Result(code, tail.toList())
    }
}
