// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.os.StatFs
import com.logie.warcraft.client.DataLayout
import com.logie.warcraft.logs.ConsoleText
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Everything in the app's storage (`files/`):
 *
 * - `server/`: the server's programs, libraries, SQL and default configs, unpacked from the APK
 *   (replaced by each update).
 * - `etc/`: the server's live configs (`worldserver.conf`, `modules/<module>.conf`): the settings.
 * - `mysql/`: MariaDB's databases: accounts, characters, the world.
 * - `data/`: the server's data made from the client: dbc, Cameras, maps, vmaps, mmaps.
 * - `client/`: the user's 3.3.5a client.
 * - `logs/`, `run/`: the servers' logs, and their sockets and temporary files.
 *
 * The server's programs are built for this folder's path (scripts/build-azerothcore.sh), like
 * Termux's: [isStandardPath] says if the app is where they expect it.
 */
object WowFiles {
    /** Where the server's programs look for themselves and their libraries. */
    const val STANDARD_PATH = "/data/data/com.logie.warcraft/files"

    fun root(context: Context): File = context.filesDir
    fun server(context: Context) = File(root(context), "server")
    fun ctl(context: Context) = File(server(context), "ctl.sh")
    fun etc(context: Context) = File(root(context), "etc")
    fun data(context: Context) = File(root(context), "data")
    fun client(context: Context) = File(root(context), "client")
    /** The custom races' files for the server, from versions that had them (LegacyRaces, ctl.sh races). */
    fun races(context: Context) = File(root(context), "races")
    fun logs(context: Context) = File(root(context), "logs")
    fun run(context: Context) = File(root(context), "run")
    fun databases(context: Context) = File(root(context), "mysql")

    @Volatile private var standardPath: Boolean? = null

    /**
     * The app's storage is the folder the server's programs were built for. Android gives it to the
     * app as /data/user/0/<package>/files, which for the phone's main user is the same folder as
     * /data/data/<package>/files: through a link on older Android, and on Android 11 and later
     * mounted at both places in the app's own view of /data, where comparing the paths can't tell.
     * So a file written through one path is looked for through the other. For a second user of the
     * phone, or a work profile, Android puts the app's storage elsewhere.
     */
    fun isStandardPath(context: Context): Boolean =
        standardPath ?: sameFolder(root(context), File(STANDARD_PATH)).also { standardPath = it }

    private fun sameFolder(own: File, standard: File): Boolean {
        if (runCatching { own.canonicalPath == standard.canonicalPath }.getOrDefault(false)) return true
        val marker = File(own, ".path-check")
        return runCatching {
            val token = UUID.randomUUID().toString()
            marker.writeText(token)
            File(standard, marker.name).readText() == token
        }.getOrDefault(false).also { marker.delete() }
    }

    /** The app's storage by the path the server's programs were built for, when it's there. */
    fun serverRoot(context: Context): String = if (isStandardPath(context)) STANDARD_PATH else root(context).path

    /** Which of the server's data folders are there, with files in them. */
    fun dataFolders(context: Context): Set<String> =
        DataLayout.FOLDERS.filter { File(data(context), it).list()?.isNotEmpty() == true }.toSet()

    private fun downloadedMark(context: Context) = File(data(context), "downloaded")

    /** The version of AzerothCore's server data that's in `data/`, if it was downloaded. */
    fun downloadedData(context: Context): String? =
        downloadedMark(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun setDownloadedData(context: Context, version: String?) {
        if (version == null) downloadedMark(context).delete() else downloadedMark(context).writeText("$version\n")
    }

    /** The world server has what it needs to start (dbc and maps). */
    fun hasServerData(context: Context): Boolean = dataFolders(context).containsAll(DataLayout.REQUIRED)

    fun hasClient(context: Context): Boolean =
        File(client(context), "Wow.exe").isFile && File(client(context), "Data/common.MPQ").isFile

    fun freeSpace(context: Context): Long = StatFs(root(context).path).availableBytes

    /** The size of a folder and everything in it. */
    fun sizeOf(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { sizeOf(it) } ?: 0L else file.length()

    /** The last [lines] lines of a log file without the servers' colour codes, or "" if there isn't one. */
    fun tail(file: File, lines: Int = 40): String {
        if (!file.isFile) return ""
        val length = file.length()
        val start = (length - 64 * 1024).coerceAtLeast(0)
        val text = file.inputStream().use { input ->
            input.skip(start)
            input.readBytes().toString(Charsets.UTF_8)
        }
        return ConsoleText.plain(text).lines().dropLastWhile { it.isBlank() }.takeLast(lines).joinToString("\n")
    }

    /**
     * What a log that's being written has gained since byte [from], and the byte it ends at now
     * (at most [max] bytes at a time). Starts again from the beginning if the log was started anew.
     */
    fun readFrom(file: File, from: Long, max: Int = 1 shl 20): Pair<String, Long> {
        if (!file.isFile) return "" to 0L
        val length = file.length()
        val start = if (length < from) 0L else from
        if (length == start) return "" to start
        val bytes = ByteArray(minOf(max.toLong(), length - start).toInt())
        val read = RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            input.read(bytes)
        }
        if (read <= 0) return "" to start
        return String(bytes, 0, read, Charsets.UTF_8) to start + read
    }
}
