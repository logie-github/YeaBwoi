// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import com.logie.warcrafts.library.BlizzardDownload
import com.logie.warcrafts.library.BlizzardDownload.Product
import com.logie.warcrafts.library.GameLayout
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Needs
import com.logie.warcrafts.library.Wc3Script
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Warcraft III, set up on the phone from Blizzard, silently: the app downloads Blizzard's classic
 * 1.27 installers itself ([download], what Blizzard's legacy downloaders fetch, every piece checked
 * against Blizzard's checksums), then installs the game from them itself ([install]): Blizzard's
 * install script in each package says which files go where ([Wc3Script]), and the app's `wc3unpack`
 * copies them out and puts the rest into the game's archives, Reign of Chaos first, then The Frozen
 * Throne over it. No installer window, licence page or CD key: the game plays offline.
 *
 * The downloads live in `files/wc3-setup/<installer's folder>/`, the game being installed in
 * `files/wc3-setup/game/`, which becomes the app's Warcraft III folder when both are in.
 */
object Wc3Setup {
    fun dir(context: Context) = File(context.filesDir, "wc3-setup")

    private fun downloadedMarker(context: Context, product: Product) = File(dir(context), "${product.name}.done")

    private fun installedMarker(context: Context, product: Product) = File(dir(context), "${product.name}.installed")

    private fun staging(context: Context) = File(dir(context), "game")

    private const val TOME = "Installer Tome.mpq"
    private const val SCRIPT = "InstallCD\\InstallerFileList\\InstallerFileList.xml"

    /** [product]'s package (`Installer Tome.mpq`), once downloaded and checked, until it's installed. */
    private fun tome(context: Context, product: Product): File? {
        val name = downloadedMarker(context, product).takeIf { it.isFile }?.readText()?.trim() ?: return null
        return File(dir(context), "$name/$TOME").takeIf { it.isFile }
    }

    private fun installed(context: Context, product: Product) = installedMarker(context, product).isFile

    /** Both games downloaded (or already installed from their download). */
    fun downloaded(context: Context): Boolean = Product.entries.all { installed(context, it) || tome(context, it) != null }

    /** The next thing to do to have Warcraft III: download, install, or nothing (play). */
    enum class Step { DOWNLOAD, INSTALL, READY }

    fun step(context: Context): Step = when {
        Needs.ready(RtsFiles.needs(context, Install.WC3)) -> Step.READY
        !downloaded(context) -> Step.DOWNLOAD
        else -> Step.INSTALL
    }

    /** Downloads what's missing, then installs: the whole setup, from wherever it was left. */
    fun setUp(context: Context, report: Imports.Report) {
        for (product in Product.entries) {
            if (!installed(context, product) && tome(context, product) == null) download(context, product, report)
        }
        install(context, report)
    }

    /**
     * Installs both games from their packages, as Blizzard's installer would on Windows ([Wc3Script]),
     * into the staging folder; each package is deleted once its game is in (the space goes to the
     * next), and the finished game becomes the app's Warcraft III folder. Doing it again after a stop
     * starts the unfinished game over: every file of it is written again.
     */
    fun install(context: Context, report: Imports.Report) {
        val game = staging(context)
        for (product in Product.entries) {
            if (installed(context, product)) continue
            val tome = tome(context, product) ?: throw Imports.ImportException(context.getString(R.string.rts_setup_not_downloaded))
            report(Imports.Phase.UNPACK, null, context.getString(R.string.rts_setup_reading))
            game.mkdirs()
            val work = File(dir(context), "${product.name}.work").apply { deleteRecursively(); mkdirs() }
            val script = File(work, "InstallerFileList.xml")
            unpack(context, listOf("extract", tome.path, SCRIPT, script.path), work, null)
            val steps = Wc3Script.plan(script.readText(), alreadyThere = listing(game))
            if (steps.isEmpty()) throw Imports.ImportException(context.getString(R.string.rts_setup_bad_script))
            for (step in steps) {
                val target = (step.archive ?: step.to).replace('\\', '/')
                if (!GameLayout.isSafe(target)) throw Imports.ImportException("Unsafe path in Blizzard's script: $target")
            }
            Imports.checkSpace(context, tome.length())
            val plan = File(work, "plan.txt").apply { writeText(Wc3Script.planText(steps)) }
            val name = context.getString(if (product == Product.TFT) R.string.rts_title_tft else R.string.rts_title_roc)
            unpack(context, listOf("run", tome.path, plan.path, game.path), work) { done, total ->
                report(Imports.Phase.UNPACK, if (total > 0) done.toFloat() / total else null, name)
            }
            work.deleteRecursively()
            installedMarker(context, product).writeText("")
            tome.parentFile?.deleteRecursively()
            downloadedMarker(context, product).delete()
        }
        adopt(context)
    }

    /** The staging folder's files and folders, as the script spells them (`Maps\(2)BootyBay.w3m`). */
    private fun listing(game: File): List<String> =
        game.walkTopDown().filter { it != game }.map { it.relativeTo(game).path.replace('/', '\\') }.toList()

    /** Runs `wc3unpack`; [onProgress] gets its "progress <done> <total>" lines. */
    private fun unpack(context: Context, args: List<String>, work: File, onProgress: ((Long, Long) -> Unit)?) {
        var last = 0L
        val result = Tools.run(listOf(Tools.file(context, "libwc3unpack.so").path) + args, work, File(work, "wc3unpack.log")) { line ->
            if (onProgress != null && line.startsWith("progress ")) {
                val now = System.currentTimeMillis()
                if (now - last < 250) return@run
                last = now
                val parts = line.split(' ')
                val done = parts.getOrNull(1)?.toLongOrNull() ?: return@run
                val total = parts.getOrNull(2)?.toLongOrNull() ?: return@run
                onProgress(done, total)
            }
        }
        if (result.code != 0) {
            val error = result.tail.lastOrNull { it.startsWith("error:") }?.removePrefix("error:")?.trim()
            throw Imports.ImportException(context.getString(R.string.rts_setup_install_failed, error ?: "exit ${result.code}"))
        }
    }

    /** The installed game becomes the app's Warcraft III folder (one rename, same storage); the setup's files go. */
    private fun adopt(context: Context) {
        val game = staging(context)
        val target = RtsFiles.folder(context, Install.WC3)
        val old = File(target.parentFile, "wc3.old")
        old.deleteRecursively()
        target.parentFile?.mkdirs()
        if (target.exists() && !target.renameTo(old)) throw Imports.ImportException("Can't replace ${target.path}")
        if (!game.renameTo(target)) {
            old.renameTo(target)
            throw Imports.ImportException("Can't move the game into ${target.path}")
        }
        old.deleteRecursively()
        RtsFiles.setProgram(context, Install.WC3, null)
        dir(context).deleteRecursively()
    }

    /**
     * Downloads [product]'s installer package (`Installer Tome.mpq`, the only file of Blizzard's
     * installer the app needs) from Blizzard, piece by piece, each checked against Blizzard's SHA-1
     * before it's written. A stopped download goes on from the last piece written (`<installer>.pieces`
     * holds the next one's number).
     */
    fun download(context: Context, product: Product, report: Imports.Report) {
        report(Imports.Phase.DOWNLOAD, null, context.getString(R.string.rts_setup_downloader))
        val downloader = fetch(BlizzardDownload.downloaderUrl(product), null)
        val pkg = BlizzardDownload.parse(downloader)
            ?: throw Imports.ImportException(context.getString(R.string.rts_setup_bad_downloader))
        for (file in pkg.files) {
            if (!GameLayout.isSafe(file.path)) throw Imports.ImportException("Unsafe path in Blizzard's list: ${file.path}")
        }
        // Only the package is needed: Blizzard's installer program, its DirectX and the Mac's files aren't.
        val tomeIndex = pkg.files.indexOfFirst { it.path == TOME }
        if (tomeIndex < 0) throw Imports.ImportException(context.getString(R.string.rts_setup_bad_downloader))
        val tomeStart = pkg.files.take(tomeIndex).sumOf { it.length }
        val tomeLength = pkg.files[tomeIndex].length
        val first = (tomeStart / pkg.pieceLength).toInt()
        val last = ((tomeStart + tomeLength - 1) / pkg.pieceLength).toInt()

        val root = File(dir(context), pkg.name)
        val progress = File(dir(context), "${pkg.name}.pieces")
        var next = progress.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.coerceIn(first, last + 1) ?: first
        if (next == first) root.deleteRecursively()
        // The download, and the game it installs (about as big) beside it.
        Imports.checkSpace(context, tomeLength - (next - first).toLong() * pkg.pieceLength + tomeLength)

        val tome = File(root, TOME).apply { parentFile?.mkdirs() }
        val buffer = ByteArray(pkg.pieceLength)
        val count = last + 1 - first
        while (next <= last) {
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
                if (segment.file != tomeIndex) continue
                RandomAccessFile(tome, "rw").use { out ->
                    out.seek(segment.fileOffset)
                    out.write(buffer, segment.pieceOffset, segment.length)
                }
            }
            next++
            progress.writeText(next.toString())
            report(Imports.Phase.DOWNLOAD, (next - first).toFloat() / count, context.getString(if (product == Product.TFT) R.string.rts_title_tft else R.string.rts_title_roc))
        }
        progress.delete()
        downloadedMarker(context, product).writeText(pkg.name)
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
