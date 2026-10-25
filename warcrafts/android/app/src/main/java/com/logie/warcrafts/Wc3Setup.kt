// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import com.logie.warcrafts.library.BlizzardDownload
import com.logie.warcrafts.library.BlizzardDownload.Product
import com.logie.warcrafts.library.GameLayout
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Needs
import java.io.File
import java.io.RandomAccessFile
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

    /** Both installers downloaded and checked. */
    fun downloaded(context: Context): Boolean = Product.entries.all { installer(context, it) != null }

    /** The next thing to do to have Warcraft III: download, install each game, or nothing (play). */
    enum class Step { DOWNLOAD, INSTALL_ROC, INSTALL_TFT, READY }

    /** [winePrefix]: the games' Windows (`.wine`), where Blizzard's installers install, if it's made. */
    fun step(context: Context, winePrefix: File?): Step {
        if (Needs.ready(RtsFiles.needs(context, Install.WC3))) return Step.READY
        if (!downloaded(context)) return Step.DOWNLOAD
        val installed = winePrefix?.let { installedIn(it) }
        val names = installed?.list()?.map { it.lowercase() }?.toSet().orEmpty()
        return if ("war3.mpq" in names) Step.INSTALL_TFT else Step.INSTALL_ROC
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

    /**
     * Downloads [product]'s installer from Blizzard, piece by piece, each checked against Blizzard's
     * SHA-1 before it's written; nothing is installed yet. A stopped download goes on from the last
     * piece written (`<installer>.pieces` holds how many).
     */
    fun download(context: Context, product: Product, report: Imports.Report) {
        report(Imports.Phase.DOWNLOAD, null, context.getString(R.string.rts_setup_downloader))
        val downloader = fetch(BlizzardDownload.downloaderUrl(product), null)
        val pkg = BlizzardDownload.parse(downloader)
            ?: throw Imports.ImportException(context.getString(R.string.rts_setup_bad_downloader))
        for (file in pkg.files) {
            if (!GameLayout.isSafe(file.path)) throw Imports.ImportException("Unsafe path in Blizzard's list: ${file.path}")
        }
        val root = File(dir(context), pkg.name)
        val progress = File(dir(context), "${pkg.name}.pieces")
        var next = progress.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (next == 0) root.deleteRecursively()
        // The download, and the game it installs (about as big) beside it.
        val left = pkg.totalLength - next.toLong() * pkg.pieceLength
        Imports.checkSpace(context, left + pkg.totalLength)

        val files = pkg.files.map { File(root, it.path).apply { parentFile?.mkdirs() } }
        val buffer = ByteArray(pkg.pieceLength)
        val count = pkg.pieces.size
        while (next < count) {
            if (Imports.cancelled) throw Imports.ImportException("Stopped")
            val size = BlizzardDownload.pieceSize(pkg, next)
            var good = false
            var lastError: IOException? = null
            for (attempt in 1..3) {
                try {
                    val got = fetchInto(BlizzardDownload.pieceUrl(pkg, next), pkg.token, buffer, context)
                    if (got == size && BlizzardDownload.sha1(buffer, size).contentEquals(pkg.pieces[next])) {
                        good = true
                        break
                    }
                } catch (e: Imports.ImportException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (!good) {
                throw lastError?.let { Imports.ImportException(it.message ?: it.javaClass.simpleName) }
                    ?: Imports.ImportException(context.getString(R.string.rts_setup_damaged))
            }
            for (segment in BlizzardDownload.segments(pkg, next)) {
                RandomAccessFile(files[segment.file], "rw").use { out ->
                    out.seek(segment.fileOffset)
                    out.write(buffer, segment.pieceOffset, segment.length)
                }
            }
            next++
            progress.writeText(next.toString())
            report(Imports.Phase.DOWNLOAD, next.toFloat() / count, pkg.files[BlizzardDownload.segments(pkg, next - 1).last().file].path.substringAfterLast('/'))
        }
        progress.delete()
        marker(context, product).writeText(pkg.name)
    }

    private fun fetch(url: String, cookie: String?): ByteArray {
        val connection = open(url, cookie)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** One piece into [buffer]: how many bytes came. */
    private fun fetchInto(url: String, cookie: String?, buffer: ByteArray, context: Context): Int {
        val connection = open(url, cookie)
        try {
            val code = connection.responseCode
            if (code == 403 || code == 404) throw Imports.ImportException(context.getString(R.string.rts_setup_refused, code))
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            var total = 0
            connection.inputStream.use { input ->
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
            }
            // The stream read to its end and closed: the connection stays open for the next piece.
            return total
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private fun open(url: String, cookie: String?): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
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
