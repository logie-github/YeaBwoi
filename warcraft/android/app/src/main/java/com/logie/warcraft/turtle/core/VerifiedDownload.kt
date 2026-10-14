// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import com.logie.warcraft.client.ZipDownload
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A file downloaded into [target] by HTTP ranges, checked before it's used:
 *
 * - it goes to `<target>.part` first and carries on from there after a dropped connection, a
 *   closed app or a restart of the phone (a few tries in a row, with pauses);
 * - it must be exactly [size] bytes, and its SHA-256 [sha256] (the release's published values,
 *   compiled into the app): a file that isn't is deleted, never used, and the error says so;
 * - only then is it renamed to [target].
 */
class VerifiedDownload(
    private val source: ZipDownload.Source,
    private val target: File,
    private val size: Long,
    private val sha256: String,
    private val fs: SafeFs,
) {
    class Corrupt(message: String) : IOException(message)

    class Cancelled : IOException("cancelled")

    val part = File(target.path + ".part")

    /** Bytes already downloaded (from an earlier try). */
    fun soFar(): Long = if (part.isFile) part.length() else 0L

    /**
     * Downloads what's missing and checks it. [onProgress] gets the bytes there so far;
     * [cancelled] is asked between blocks. Background thread.
     */
    fun run(cancelled: () -> Boolean = { false }, tries: Int = 6, pause: (Int) -> Unit = { Thread.sleep(3_000L * it) }, onProgress: (Long) -> Unit = {}) {
        fs.check(target)
        fs.check(part)
        if (target.isFile && target.length() == size && hashOf(target) == sha256.lowercase()) return
        target.parentFile?.let { fs.mkdirs(it) }
        var attempt = 0
        while (true) {
            try {
                if (part.length() > size) fs.delete(part)
                if (part.length() < size) fetchRest(cancelled, onProgress)
                break
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                if (++attempt >= tries) throw e
                pause(attempt)
            }
        }
        if (part.length() != size) throw IOException("Download ended at ${part.length()} of $size bytes")
        val hash = hashOf(part)
        if (hash != sha256.lowercase()) {
            fs.delete(part)
            throw Corrupt("The download isn't the published file (SHA-256 $hash, expected ${sha256.lowercase()}); it was deleted")
        }
        fs.delete(target)
        fs.move(part, target)
    }

    private fun fetchRest(cancelled: () -> Boolean, onProgress: (Long) -> Unit) {
        val from = part.length()
        source.open(from, null).use { input ->
            FileOutputStream(part, true).use { output ->
                val buffer = ByteArray(1 shl 16)
                var done = from
                var reported = from
                while (done < size) {
                    if (cancelled()) throw Cancelled()
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - done).toInt())
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (done - reported >= 1 shl 20 || done == size) {
                        reported = done
                        onProgress(done)
                    }
                }
                output.fd.sync()
            }
        }
    }

    companion object {
        fun hashOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
