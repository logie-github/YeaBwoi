// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import com.logie.warcraft.client.ZipDownload
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Brings the client in [folder] to exactly what [torrent] describes (OctoWoW's client), the way
 * OctoWoW's own launcher does, and checks every byte on the way:
 *
 * - every file is read in the torrent's order and hashed piece by piece (SHA-1) against the
 *   torrent: one pass over the whole client;
 * - a file that isn't there, or has another size (another version), is downloaded from the web
 *   seed with HTTP ranges into `<file>.part`, hashed as it comes, and carries on from there after
 *   a dropped connection, a cancel or a restart (the part is read and hashed again, then the rest
 *   fetched);
 * - a piece that doesn't match is fetched again, just its bytes, and written in place, then hashed
 *   again from the disk, a few rounds at most ([Corrupt] if it still doesn't match);
 * - files the [previous] torrent had and this one doesn't are deleted, and so are archives in
 *   `Data/` (and its locale folders) the torrent doesn't list, which the game would load over the
 *   client's own (another client's patch-6.mpq, say); nothing else is: the player's settings,
 *   add-ons and screenshots stay;
 * - the torrent's files in [aside] (lowercase paths) are kept in [kept], never in the game's
 *   folder (OctoWoW's own d3d9.dll, which would replace the graphics the app sets up); those in
 *   [appOwned] are kept there too, while the game's folder has the app's own (the realm list).
 *
 * Every file it writes, moves or deletes goes through [fs] (Turtle's folder only).
 */
class ClientSync(
    private val torrent: ClientTorrent,
    private val folder: File,
    private val kept: File,
    private val fs: SafeFs,
    private val source: (ClientTorrent.Entry) -> ZipDownload.Source,
    private val aside: Set<String> = ASIDE,
    private val appOwned: Set<String> = APP_OWNED,
) {
    class Corrupt(message: String) : IOException(message)

    enum class Step { CHECKING, DOWNLOADING, REPAIRING }

    /** [done] of [total] bytes of the client gone through; [downloaded] of them from the network. */
    data class Progress(val step: Step, val done: Long, val total: Long, val downloaded: Long, val file: String)

    data class Result(
        /** Bytes fetched from the network. */
        val downloaded: Long,
        /** Files downloaded whole (missing, or another version). */
        val fetched: List<String>,
        /** Pieces fetched again because they didn't match. */
        val repairedPieces: Int,
        /** Files of the previous client no longer in this one, deleted. */
        val removed: List<String>,
        /** Every file's SHA-256 (the torrent's path to lowercase hex), as it's on the phone now. */
        val sha256: Map<String, String> = emptyMap(),
    ) {
        /**
         * What the server's data is made from: the client's MPQ archives (the map tools read
         * nothing else), by their content. The same key means the data made before still fits.
         */
        val dataKey: String
            get() {
                val digest = MessageDigest.getInstance("SHA-256")
                for ((path, hash) in sha256.entries.filter { isArchive(it.key) }.sortedBy { it.key.lowercase() }) {
                    digest.update(path.lowercase().toByteArray())
                    digest.update(0)
                    digest.update(hash.toByteArray())
                }
                return "mpq:" + digest.digest().joinToString("") { "%02x".format(it) }
            }
    }

    /** Where [entry] is on the phone: in [kept] or in the game's folder (found without regard to case). */
    fun localFile(entry: ClientTorrent.Entry): File {
        val key = entry.path.lowercase()
        if (key in aside || key in appOwned) return File(kept, entry.path)
        return ClientManifest.find(folder, entry.path)?.takeIf { it.exists() } ?: newFile(entry)
    }

    /**
     * Where [entry] goes when it's downloaded new: in the folders already there whatever their
     * spelling (Windows sees `Data` and `data` as one; the phone wouldn't), under the torrent's name.
     */
    private fun newFile(entry: ClientTorrent.Entry): File {
        val key = entry.path.lowercase()
        if (key in aside || key in appOwned) return File(kept, entry.path)
        var current = folder
        val parts = entry.path.split('/')
        for (part in parts.dropLast(1)) {
            current = current.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(part, ignoreCase = true) } ?: File(current, part)
        }
        return File(current, parts.last())
    }

    /** About how many bytes [run] will download: the files not there with their size, less their parts. */
    fun bytesToFetch(): Long = torrent.realFiles.sumOf { entry ->
        val local = localFile(entry)
        when {
            local.isFile && local.length() == entry.size -> 0L
            else -> {
                val part = File(local.path + ".part")
                entry.size - (if (part.isFile && part.length() <= entry.size) part.length() else 0L)
            }
        }
    }

    /**
     * Checks and completes the client. [cancelled] is asked between blocks (then
     * [VerifiedDownload.Cancelled], with what's done kept); [pause] between tries after a network
     * error. Background thread.
     */
    fun run(
        previous: ClientTorrent? = null,
        cancelled: () -> Boolean = { false },
        tries: Int = 6,
        pause: (Int) -> Unit = { Thread.sleep(3_000L * it) },
        onProgress: (Progress) -> Unit = {},
    ): Result {
        fs.mkdirs(folder)
        fs.mkdirs(kept)
        val removed = removeStale(previous) + removeForeignArchives()
        setAside()
        val hasher = PieceHasher(torrent)
        val hashes = LinkedHashMap<String, String>()
        val fetched = mutableListOf<String>()
        var downloaded = 0L
        var done = 0L
        val total = torrent.streamSize
        val buffer = ByteArray(BLOCK)

        fun progress(step: Step, file: String) = onProgress(Progress(step, done, total, downloaded, file))

        for (entry in torrent.files) {
            if (cancelled()) throw VerifiedDownload.Cancelled()
            if (entry.padding) {
                hasher.zeros(entry.size)
                done += entry.size
                continue
            }
            var local = fs.check(localFile(entry))
            val fileDigest = MessageDigest.getInstance("SHA-256")
            if (local.isFile && local.length() == entry.size) {
                done = hashFile(local, hasher, fileDigest, done, cancelled) { done = it; progress(Step.CHECKING, entry.path) }
                hashes[entry.path] = hex(fileDigest.digest())
                continue
            }
            if (local.isDirectory) throw IOException("${entry.path} is a folder on the phone, a file in the client")
            // Another version: it goes, the torrent's comes (under the torrent's spelling).
            if (local.exists()) {
                fs.delete(local)
                local = fs.check(newFile(entry))
            }
            local.parentFile?.let { fs.mkdirs(it) }
            val part = fs.check(File(local.path + ".part"))
            if (part.exists() && (!part.isFile || part.length() > entry.size)) fs.delete(part)
            // What an earlier try downloaded, hashed again (it's on the disk, not in memory).
            if (part.isFile) done = hashFile(part, hasher, fileDigest, done, cancelled) { done = it; progress(Step.CHECKING, entry.path) }
            var attempt = 0
            while (part.length() < entry.size) {
                val from = part.length()
                try {
                    source(entry).open(from, entry.size - 1).use { input ->
                        FileOutputStream(part, true).use { output ->
                            var left = entry.size - from
                            var since = 0L
                            while (left > 0) {
                                if (cancelled()) throw VerifiedDownload.Cancelled()
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                hasher.update(buffer, 0, n)
                                fileDigest.update(buffer, 0, n)
                                left -= n
                                done += n
                                downloaded += n
                                since += n
                                if (since >= REPORT) {
                                    since = 0
                                    progress(Step.DOWNLOADING, entry.path)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (part.length() < entry.size) throw IOException("The download of ${entry.path} ended early")
                } catch (e: VerifiedDownload.Cancelled) {
                    throw e
                } catch (e: IOException) {
                    // Only tries that got nothing count: a long download on a poor connection drops often.
                    if (part.length() > from) attempt = 0
                    if (++attempt >= tries) throw e
                    pause(attempt)
                }
            }
            fs.move(part, local)
            hashes[entry.path] = hex(fileDigest.digest())
            fetched += entry.path
            progress(Step.DOWNLOADING, entry.path)
        }
        hasher.finish()

        // Pieces that don't match: fetched again, just their bytes, a few rounds at most.
        var bad = hasher.bad.toList()
        val repaired = bad.size
        var round = 0
        while (bad.isNotEmpty()) {
            if (++round > tries) {
                throw Corrupt("${bad.size} pieces of the client still don't match OctoWoW's after $tries tries (${torrent.filesOf(bad.first()).joinToString { it.path }})")
            }
            for (index in bad) {
                if (cancelled()) throw VerifiedDownload.Cancelled()
                downloaded += fetchPiece(index, cancelled, tries, pause)
                onProgress(Progress(Step.REPAIRING, done, total, downloaded, torrent.filesOf(index).firstOrNull()?.path ?: ""))
            }
            bad = bad.filter { !pieceMatches(it) }
        }
        // The files a fetched piece went into, hashed again as they are now.
        for (entry in hasher.bad.flatMap { torrent.filesOf(it) }.filter { !it.padding }.distinct()) {
            val digest = MessageDigest.getInstance("SHA-256")
            localFile(entry).inputStream().use { input ->
                val buffer = ByteArray(BLOCK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            hashes[entry.path] = hex(digest.digest())
        }
        // Parts left by a try that the file came whole after all (an import, say).
        for (entry in torrent.realFiles) {
            val part = File(localFile(entry).path + ".part")
            if (part.exists()) fs.delete(part)
        }
        return Result(downloaded, fetched, repaired, removed, hashes)
    }

    /** Files of the [previous] client that this one hasn't, deleted (by the torrent's own list only). */
    private fun removeStale(previous: ClientTorrent?): List<String> {
        if (previous == null) return emptyList()
        val now = torrent.realFiles.map { it.path.lowercase() }.toSet()
        val removed = mutableListOf<String>()
        for (old in previous.realFiles) {
            if (old.path.lowercase() in now) continue
            val key = old.path.lowercase()
            val local = if (key in aside || key in appOwned) File(kept, old.path) else ClientManifest.find(folder, old.path)
            if (local != null && local.isFile) {
                fs.delete(local)
                removed += old.path
            }
            local?.let { fs.delete(File(it.path + ".part")) }
        }
        return removed
    }

    /** Archives in Data/ and its locale folders that aren't the client's: the game would load them over its own. */
    private fun removeForeignArchives(): List<String> {
        val data = ClientManifest.find(folder, "Data")?.takeIf { it.isDirectory } ?: return emptyList()
        val listed = torrent.realFiles.map { it.path.lowercase() }.toSet()
        val removed = mutableListOf<String>()
        fun scan(dir: File, prefix: String, top: Boolean) {
            for (file in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val path = "$prefix/${file.name}"
                if (top && file.isDirectory) {
                    scan(file, path, top = false)
                } else if (file.isFile && file.name.endsWith(".mpq", ignoreCase = true) && path.lowercase() !in listed) {
                    fs.delete(file)
                    removed += path
                }
            }
        }
        scan(data, "Data", top = true)
        return removed
    }

    /** The files the game mustn't see, moved out of its folder (or deleted there if kept already). */
    private fun setAside() {
        for (entry in torrent.realFiles) {
            val key = entry.path.lowercase()
            if (key !in aside) continue
            val inGame = ClientManifest.find(folder, entry.path)?.takeIf { it.isFile } ?: continue
            val target = File(kept, entry.path)
            if (target.exists()) {
                fs.delete(inGame)
            } else {
                target.parentFile?.let { fs.mkdirs(it) }
                fs.move(inGame, target)
            }
        }
    }

    /** Feeds [file] to [hasher] and [fileDigest]; [onBlock] gets the stream's position every few MB and at the end. */
    private fun hashFile(file: File, hasher: PieceHasher, fileDigest: MessageDigest, start: Long, cancelled: () -> Boolean, onBlock: (Long) -> Unit): Long {
        var done = start
        val buffer = ByteArray(BLOCK)
        var since = 0L
        file.inputStream().use { input ->
            while (true) {
                if (cancelled()) throw VerifiedDownload.Cancelled()
                val n = input.read(buffer)
                if (n < 0) break
                hasher.update(buffer, 0, n)
                fileDigest.update(buffer, 0, n)
                done += n
                since += n
                if (since >= REPORT) {
                    since = 0
                    onBlock(done)
                }
            }
        }
        onBlock(done)
        return done
    }

    /** Piece [index]'s bytes fetched again and written in place in each file it covers. */
    private fun fetchPiece(index: Int, cancelled: () -> Boolean, tries: Int, pause: (Int) -> Unit): Long {
        val range = torrent.pieceRange(index)
        var fetched = 0L
        for (entry in torrent.filesOf(index)) {
            if (entry.padding) continue
            val from = maxOf(range.first, entry.offset) - entry.offset
            val to = minOf(range.last + 1, entry.end) - entry.offset
            val file = fs.check(localFile(entry))
            file.parentFile?.let { fs.mkdirs(it) }
            var attempt = 0
            var at = from
            while (at < to) {
                val before = at
                try {
                    source(entry).open(at, to - 1).use { input ->
                        RandomAccessFile(file, "rw").use { output ->
                            output.seek(at)
                            val buffer = ByteArray(BLOCK)
                            while (at < to) {
                                if (cancelled()) throw VerifiedDownload.Cancelled()
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), to - at).toInt())
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                at += n
                                fetched += n
                            }
                            output.fd.sync()
                        }
                    }
                    if (at < to) throw IOException("The download of ${entry.path} ended early")
                } catch (e: VerifiedDownload.Cancelled) {
                    throw e
                } catch (e: IOException) {
                    if (at > before) attempt = 0
                    if (++attempt >= tries) throw e
                    pause(attempt)
                }
            }
        }
        return fetched
    }

    /** Piece [index] as it's on the disk now hashes to the torrent's value. */
    private fun pieceMatches(index: Int): Boolean {
        val range = torrent.pieceRange(index)
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(BLOCK)
        for (entry in torrent.filesOf(index)) {
            val from = maxOf(range.first, entry.offset) - entry.offset
            val to = minOf(range.last + 1, entry.end) - entry.offset
            if (entry.padding) {
                var left = to - from
                java.util.Arrays.fill(buffer, 0)
                while (left > 0) {
                    val n = minOf(buffer.size.toLong(), left).toInt()
                    digest.update(buffer, 0, n)
                    left -= n
                }
                continue
            }
            val file = localFile(entry)
            if (!file.isFile || file.length() != entry.size) return false
            RandomAccessFile(file, "r").use { input ->
                input.seek(from)
                var left = to - from
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) return false
                    digest.update(buffer, 0, n)
                    left -= n
                }
            }
        }
        return digest.digest().contentEquals(torrent.pieces[index])
    }

    /** SHA-1 of each piece of the stream as it's fed in order; [bad]: the pieces that don't match. */
    private class PieceHasher(private val torrent: ClientTorrent) {
        private val digest = MessageDigest.getInstance("SHA-1")
        private var index = 0
        private var filled = 0L
        val bad = mutableListOf<Int>()

        private fun size(index: Int): Long = minOf(torrent.pieceLength, torrent.streamSize - index * torrent.pieceLength)

        fun update(bytes: ByteArray, offset: Int, length: Int) {
            var at = offset
            var left = length
            while (left > 0) {
                if (index >= torrent.pieces.size) throw IOException("More bytes than the torrent has")
                val n = minOf(size(index) - filled, left.toLong()).toInt()
                digest.update(bytes, at, n)
                filled += n
                at += n
                left -= n
                if (filled == size(index)) close()
            }
        }

        fun zeros(count: Long) {
            val block = ByteArray(BLOCK)
            var left = count
            while (left > 0) {
                val n = minOf(block.size.toLong(), left).toInt()
                update(block, 0, n)
                left -= n
            }
        }

        private fun close() {
            if (!digest.digest().contentEquals(torrent.pieces[index])) bad += index
            index++
            filled = 0
        }

        fun finish() {
            if (filled != 0L || index != torrent.pieces.size) throw IOException("The client's stream ended at piece $index of ${torrent.pieces.size}")
        }
    }

    companion object {
        private const val BLOCK = 1 shl 16
        private const val REPORT = 8L shl 20

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** One of the client's MPQ archives (in Data/, where the map tools look). */
        fun isArchive(path: String) = path.startsWith("Data/", ignoreCase = true) && path.endsWith(".mpq", ignoreCase = true)

        /**
         * OctoWoW's own DXVK (d3d9.dll), a mod of its launcher's: the game would load it in place of
         * the graphics the app sets up for it, so it's kept out of the game's folder.
         */
        val ASIDE = setOf("d3d9.dll")

        /** The realm list, which the app writes for the realm on the phone before each start. */
        val APP_OWNED = setOf("realmlist.wtf")
    }
}
