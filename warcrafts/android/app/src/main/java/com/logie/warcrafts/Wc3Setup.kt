// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import com.logie.warcrafts.library.BlizzardDownload
import com.logie.warcrafts.library.BlizzardDownload.Product
import com.logie.warcrafts.library.GameLayout
import com.logie.warcrafts.library.Install
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Warcraft III, set up on the phone from Blizzard: the app downloads Blizzard's classic 1.27
 * installers itself ([download], what Blizzard's legacy downloaders fetch, every piece checked
 * against Blizzard's checksums), then Blizzard's own installer runs in the games' Windows, where the
 * player types their CD key ([com.logie.warcrafts.game.Game.install]), Reign of Chaos first, then The
 * Frozen Throne. When the game is installed there, [adopt] moves it into the app's Warcraft III
 * folder and the installers are deleted.
 *
 * The installers live in `files/wc3-setup/<installer's folder>/`, drive G: in the games' Windows.
 */
object Wc3Setup {
    fun dir(context: Context) = File(context.filesDir, "wc3-setup")

    private fun marker(context: Context, product: Product) = File(dir(context), "${product.name}.done")

    /** The installer program of [product], once downloaded and checked. */
    fun installer(context: Context, product: Product): File? {
        val name = marker(context, product).takeIf { it.isFile }?.readText()?.trim() ?: return null
        return File(dir(context), "$name/Installer.exe").takeIf { it.isFile }
    }

    /** Where Blizzard's installer puts the game in the games' Windows (searched for its `War3.mpq`). */
    fun installedIn(winePrefix: File): File? {
        val driveC = File(winePrefix, "drive_c")
        return listOf("Program Files (x86)/Warcraft III", "Program Files/Warcraft III", "Warcraft III")
            .map { File(driveC, it) }
            .firstOrNull { dir -> dir.listFiles()?.any { it.name.equals("War3.mpq", ignoreCase = true) } == true }
    }

    /**
     * After Blizzard's installers: the game moves from the games' Windows into the app's Warcraft III
     * folder (one rename, same storage), once both games are in it, and the installers go.
     */
    fun adopt(context: Context, winePrefix: File): Boolean {
        val installed = installedIn(winePrefix) ?: return false
        val names = installed.list()?.map { it.lowercase() }?.toSet() ?: return false
        if ("war3x.mpq" !in names) return false
        val target = RtsFiles.folder(context, Install.WC3)
        val old = File(target.parentFile, "wc3.old")
        old.deleteRecursively()
        target.parentFile?.mkdirs()
        if (target.exists() && !target.renameTo(old)) return false
        if (!installed.renameTo(target)) {
            old.renameTo(target)
            return false
        }
        old.deleteRecursively()
        RtsFiles.setProgram(context, Install.WC3, null)
        dir(context).deleteRecursively()
        return true
    }

    /** Downloads [product]'s installer from Blizzard and checks it; nothing is installed yet. */
    fun download(context: Context, product: Product, report: Imports.Report) {
        report(Imports.Phase.DOWNLOAD, null, context.getString(R.string.rts_setup_downloader))
        val downloader = fetch(BlizzardDownload.downloaderUrl(product))
        val pkg = BlizzardDownload.parse(downloader)
            ?: throw Imports.ImportException(context.getString(R.string.rts_setup_bad_downloader))
        val wanted = BlizzardDownload.wanted(pkg)
        val root = File(dir(context), pkg.name)
        val total = wanted.sumOf { it.length }
        val have = wanted.sumOf { file -> File(root, file.path).let { if (it.isFile) minOf(it.length(), file.length) else 0L } }
        // The download, and the game it installs (about as big) beside it.
        Imports.checkSpace(context, (total - have) + total)

        var done = have
        for (file in wanted) {
            if (!GameLayout.isSafe(file.path)) throw Imports.ImportException("Unsafe path in Blizzard's list: ${file.path}")
            val target = File(root, file.path)
            target.parentFile?.mkdirs()
            if (target.isFile && target.length() == file.length) continue
            if (target.isFile && target.length() > file.length) target.delete()
            val start = if (target.isFile) target.length() else 0L
            download(context, BlizzardDownload.urls(pkg, file), target, start, file.length) { read ->
                done += read
                report(Imports.Phase.DOWNLOAD, done.toFloat() / total, file.path.substringAfterLast('/'))
            }
        }

        report(Imports.Phase.DOWNLOAD, null, context.getString(R.string.rts_setup_checking))
        val pieces = BlizzardDownload.Pieces(pkg)
        val buffer = ByteArray(1 shl 16)
        for (file in pkg.files) {
            if (file !in wanted) {
                pieces.skip(file.length)
                continue
            }
            File(root, file.path).inputStream().use { input ->
                while (true) {
                    if (Imports.cancelled) throw Imports.ImportException("Stopped")
                    val count = input.read(buffer)
                    if (count < 0) break
                    pieces.feed(buffer, 0, count)
                }
            }
        }
        if (!pieces.finish()) {
            root.deleteRecursively()
            throw Imports.ImportException(context.getString(R.string.rts_setup_damaged))
        }
        marker(context, product).writeText(pkg.name)
    }

    private fun fetch(url: String): ByteArray {
        val connection = open(url, 0)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** [target] from the first of [urls] Blizzard serves, from byte [start] on (a stopped download goes on). */
    private fun download(context: Context, urls: List<String>, target: File, start: Long, length: Long, onRead: (Int) -> Unit) {
        var lastCode = 0
        for (url in urls) {
            val connection = open(url, start)
            try {
                lastCode = connection.responseCode
                if (lastCode != 200 && lastCode != 206) continue
                // A server that ignores the range sends the whole file: start it again.
                val append = lastCode == 206 && start > 0
                FileOutputStream(target, append).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            if (Imports.cancelled) throw Imports.ImportException("Stopped")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            onRead(count)
                        }
                    }
                }
                if (target.length() != length) throw IOException("${target.name}: ${target.length()} of $length bytes")
                return
            } finally {
                connection.disconnect()
            }
        }
        throw Imports.ImportException(context.getString(R.string.rts_setup_refused, lastCode))
    }

    private fun open(url: String, start: Long): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: return connection
                connection.disconnect()
                current = URL(current, location)
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects for $url")
    }
}
