// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.util.Log
import com.logie.warcraft.reports.GameCrash
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The game's crash reports, sent to this app's repository on GitHub: when the game stops on an error
 * it writes its own report (`Errors\*.txt` in its folder, `files/client/Errors`), and each new one is
 * made a report in [AnomalyReports]' folder ([GameCrash.markdown]), which sends it with the time
 * anomaly reports while the server runs ([ServerService]). The ones seen are listed in the folder's
 * `crashes-seen.txt`; those already old when the app first looks ([GameCrash.OLDEST_MS]) aren't sent.
 */
object GameCrashReports {
    private const val TAG = "GameCrashReports"
    private const val SEEN = "crashes-seen.txt"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    fun collect(context: Context) {
        val logs = File(WowFiles.client(context), "Errors")
            .listFiles { file -> file.isFile && file.name.endsWith(".txt", ignoreCase = true) }
            ?.sortedBy { it.lastModified() }.orEmpty()
        if (logs.isEmpty()) return
        val folder = AnomalyReports.folder(context).apply { mkdirs() }
        val seenFile = File(folder, SEEN)
        val firstLook = !seenFile.isFile
        val seen = runCatching { seenFile.readLines() }.getOrDefault(emptyList()).toMutableSet()
        val now = System.currentTimeMillis()
        var changed = false
        for (log in logs) {
            val key = GameCrash.key(log.name, log.length(), log.lastModified())
            if (key in seen) continue
            seen += key
            changed = true
            if (firstLook && now - log.lastModified() > GameCrash.OLDEST_MS) continue
            val text = runCatching { log.readText(Charsets.ISO_8859_1) }.getOrNull() ?: continue
            val report = GameCrash.markdown(log.name, text, stamp.format(Instant.ofEpochMilli(log.lastModified())),
                Realms.current(context).displayName, archives(context))
            val name = GameCrash.PREFIX + log.lastModified() + ".md"
            // (written whole under another name first: the sender takes only .md files)
            val partial = File(folder, "$name.part")
            runCatching {
                partial.writeText(report)
                partial.renameTo(File(folder, name))
            }.onFailure { Log.w(TAG, "${log.name}: not kept ($it)") }
        }
        if (changed) runCatching { seenFile.writeText(seen.joinToString("\n", postfix = "\n")) }
    }

    /** The game's archives: Data's and its language folder's, by name and size. */
    private fun archives(context: Context): List<Pair<String, Long>> {
        val data = File(WowFiles.client(context), "Data")
        val folders = listOf(data) + (data.listFiles { file -> file.isDirectory && file.name.length == 4 }?.toList().orEmpty())
        return folders.flatMap { folder ->
            folder.listFiles { file -> file.isFile && file.name.endsWith(".mpq", ignoreCase = true) }.orEmpty()
                .map { (if (folder == data) it.name else "${folder.name}/${it.name}") to it.length() }
        }.sortedBy { it.first.lowercase() }
    }
}
