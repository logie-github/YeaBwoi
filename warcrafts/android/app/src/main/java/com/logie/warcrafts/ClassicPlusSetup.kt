// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import android.net.Uri
import com.logie.warcrafts.library.ClassicPlus
import com.logie.warcrafts.library.Install
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Classic+ ([ClassicPlus]) on the phone: the app downloads its archive from where the mod's page
 * links it (Google Drive), or takes the one the player picked, and installs it into Warcraft III's
 * folder, converted for 1.27. The archive is deleted once installed.
 */
object ClassicPlusSetup {
    private fun dir(context: Context) = File(context.filesDir, "classicplus")

    private fun archive(context: Context) = File(dir(context), "classicplus.7z")

    /** About how much room an install takes: the archive (394 MB) and what it becomes (740 MB). */
    private const val SPACE = 1_200L * 1024 * 1024

    /** Downloads Classic+ (unless [picked] is given) and installs it. */
    fun run(context: Context, picked: Uri?, report: Imports.Report) {
        val game = RtsFiles.folder(context, Install.WC3)
        if (!game.isDirectory) throw Imports.ImportException(context.getString(R.string.rts_classicplus_no_game))
        dir(context).mkdirs()
        val archive = archive(context)
        try {
            if (picked != null) copy(context, picked, archive, report) else download(context, archive, report)
            install(context, archive, game, report)
        } finally {
            archive.delete()
            File(archive.path + ".part").delete()
        }
    }

    private fun install(context: Context, archive: File, game: File, report: Imports.Report) {
        report(Imports.Phase.MOD_INSTALL, null, context.getString(R.string.rts_classicplus_opening))
        val summary = try {
            ClassicPlus.install(
                archive, game,
                progress = { done, total, name -> report(Imports.Phase.MOD_INSTALL, done.toFloat() / total, name) },
                cancelled = { Imports.cancelled },
            )
        } catch (e: InterruptedException) {
            throw Imports.ImportException("Stopped")
        } catch (e: IOException) {
            throw Imports.ImportException(context.getString(R.string.rts_classicplus_bad_archive, e.message ?: e.javaClass.simpleName))
        }
        if (summary.models == 0 && summary.textures == 0) {
            ClassicPlus.remove(game)
            throw Imports.ImportException(context.getString(R.string.rts_classicplus_not_it))
        }
        File(dir(context), "last-install.txt").writeText(
            "models ${summary.models} (${summary.modelsConverted} converted), textures ${summary.textures} " +
                "(${summary.texturesConverted} converted), left out ${summary.left}\n" + summary.failed.joinToString("\n"),
        )
    }

    private fun copy(context: Context, uri: Uri, to: File, report: Imports.Report) {
        Imports.checkSpace(context, SPACE)
        val total = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        val input = context.contentResolver.openInputStream(uri) ?: throw Imports.ImportException(context.getString(R.string.rts_classicplus_cant_read))
        input.use { source ->
            to.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20)
                var done = 0L
                while (true) {
                    if (Imports.cancelled) throw Imports.ImportException("Stopped")
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    report(Imports.Phase.MOD_DOWNLOAD, if (total > 0) done.toFloat() / total else null, null)
                }
            }
        }
    }

    /** The archive from Google Drive, where the mod's page links it (or the link known when this was made). */
    private fun download(context: Context, to: File, report: Imports.Report) {
        Imports.checkSpace(context, SPACE)
        report(Imports.Phase.MOD_DOWNLOAD, null, context.getString(R.string.rts_classicplus_asking))
        val id = runCatching { ClassicPlus.driveId(String(fetch(ClassicPlus.PAGE), Charsets.UTF_8)) }.getOrNull() ?: ClassicPlus.DRIVE_ID
        var url = ClassicPlus.driveUrl(id)
        var connection = open(url)
        // A file this big: Drive first answers with a page saying it can't scan it, whose form downloads it.
        if (connection.contentType?.startsWith("text/html") == true) {
            val page = connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            connection.disconnect()
            url = ClassicPlus.confirmUrl(page) ?: throw Imports.ImportException(context.getString(R.string.rts_classicplus_drive_refused))
            connection = open(url)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299 || connection.contentType?.startsWith("text/html") == true) {
                throw Imports.ImportException(context.getString(R.string.rts_classicplus_drive_refused))
            }
            val total = connection.contentLengthLong
            val part = File(to.path + ".part")
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        if (Imports.cancelled) throw Imports.ImportException("Stopped")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        report(Imports.Phase.MOD_DOWNLOAD, if (total > 0) done.toFloat() / total else null, null)
                    }
                    if (total > 0 && done != total) throw Imports.ImportException(context.getString(R.string.rts_classicplus_cut_short))
                }
            }
            part.renameTo(to)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(url: String): ByteArray {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
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
