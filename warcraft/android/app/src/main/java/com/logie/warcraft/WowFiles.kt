// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.os.StatFs
import com.logie.warcraft.client.DataLayout
import java.io.File

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
    fun logs(context: Context) = File(root(context), "logs")
    fun run(context: Context) = File(root(context), "run")
    fun databases(context: Context) = File(root(context), "mysql")

    /**
     * The app's storage is at the path the server's programs were built for. It isn't for a second
     * user of the phone, or a work profile, where Android puts apps' storage elsewhere.
     */
    fun isStandardPath(context: Context): Boolean =
        runCatching { root(context).canonicalPath == File(STANDARD_PATH).canonicalPath }.getOrDefault(false)

    /** Which of the server's data folders are there, with files in them. */
    fun dataFolders(context: Context): Set<String> =
        DataLayout.FOLDERS.filter { File(data(context), it).list()?.isNotEmpty() == true }.toSet()

    /** The world server has what it needs to start (dbc and maps). */
    fun hasServerData(context: Context): Boolean = dataFolders(context).containsAll(DataLayout.REQUIRED)

    fun hasClient(context: Context): Boolean =
        File(client(context), "Wow.exe").isFile && File(client(context), "Data/common.MPQ").isFile

    fun freeSpace(context: Context): Long = StatFs(root(context).path).availableBytes

    /** The size of a folder and everything in it. */
    fun sizeOf(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { sizeOf(it) } ?: 0L else file.length()

    /** The last [lines] lines of a log file, or "" if there isn't one. */
    fun tail(file: File, lines: Int = 40): String {
        if (!file.isFile) return ""
        val length = file.length()
        val start = (length - 64 * 1024).coerceAtLeast(0)
        val text = file.inputStream().use { input ->
            input.skip(start)
            input.readBytes().toString(Charsets.UTF_8)
        }
        return text.lines().dropLastWhile { it.isBlank() }.takeLast(lines).joinToString("\n")
    }
}
