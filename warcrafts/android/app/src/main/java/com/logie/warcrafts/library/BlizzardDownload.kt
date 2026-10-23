// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Blizzard's classic Warcraft III installers, as its legacy downloaders fetch them
 * (`us.battle.net/download/getLegacy?product=WAR3|W3XP`). Each downloader is a small Windows program
 * carrying a torrent description: the installer's files (`Installer.exe`, `Installer Tome.mpq`,
 * DirectX's redistributable), a SHA-1 for every 256 KB piece of them end to end, and a "direct
 * download" address on Blizzard's CDN ("disable p2p": they come over plain HTTP). [parse] reads that
 * description; the app downloads the files itself and checks every piece ([Pieces]). Plain Kotlin,
 * unit-tested.
 */
object BlizzardDownload {
    /** The legacy downloaders: Reign of Chaos, then The Frozen Throne (its installer needs the first). */
    enum class Product(val code: String) { ROC("WAR3"), TFT("W3XP") }

    fun downloaderUrl(product: Product, locale: String = "enUS"): String =
        "https://us.battle.net/download/getLegacy?product=${product.code}&locale=$locale&os=WIN"

    data class FileEntry(val path: String, val length: Long)

    /** An installer: its folder [name], [files] in torrent order, its CDN address, and its pieces. */
    class Package(
        val name: String,
        val directDownload: String,
        val launchTarget: String,
        val pieceLength: Int,
        val pieces: List<ByteArray>,
        val files: List<FileEntry>,
    ) {
        val totalLength: Long get() = files.sumOf { it.length }
    }

    /** The torrent description inside a downloader program, or null if there's none. */
    fun parse(downloader: ByteArray): Package? {
        val start = indexOf(downloader, "d8:announce".toByteArray()).takeIf { it >= 0 } ?: return null
        val root = runCatching { Bencode(downloader).decode(start).first }.getOrNull() as? Map<*, *> ?: return null
        val info = root["info"] as? Map<*, *> ?: return null
        val name = (info["name"] as? ByteArray)?.decodeToString() ?: return null
        val pieceLength = (info["piece length"] as? Long)?.toInt() ?: return null
        val piecesBytes = info["pieces"] as? ByteArray ?: return null
        val files = (info["files"] as? List<*>)?.mapNotNull { file ->
            val entry = file as? Map<*, *> ?: return@mapNotNull null
            val length = entry["length"] as? Long ?: return@mapNotNull null
            val path = (entry["path"] as? List<*>)?.joinToString("/") { (it as ByteArray).decodeToString() } ?: return@mapNotNull null
            FileEntry(path, length)
        } ?: return null
        val direct = (root["direct download"] as? ByteArray)?.decodeToString() ?: return null
        val launch = (root["launch target"] as? ByteArray)?.decodeToString() ?: "$name/Installer.exe"
        return Package(name, direct.trimEnd('/'), launch, pieceLength, piecesBytes.toList().chunked(20).map { it.toByteArray() }, files)
    }

    /** The files the phone needs: the Windows installer and its data (not the Mac app). */
    fun wanted(pkg: Package): List<FileEntry> = pkg.files.filter { !it.path.startsWith("Installer.app/") }

    /**
     * Where [file] may be on the CDN, the likeliest first: under the installer's folder, then
     * straight under the address (the downloader's own form isn't documented).
     */
    fun urls(pkg: Package, file: FileEntry): List<String> {
        val path = file.path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val folder = URLEncoder.encode(pkg.name, "UTF-8").replace("+", "%20")
        return listOf("${pkg.directDownload}/$folder/$path", "${pkg.directDownload}/$path")
    }

    /**
     * Checks the installer's files against its pieces: fed every file's bytes in torrent order
     * ([skip] for the files the phone doesn't download), it answers whether every piece matched.
     * Pieces that reach into a skipped file can't be checked and are left out.
     */
    class Pieces(private val pkg: Package) {
        private val digest = MessageDigest.getInstance("SHA-1")
        private var index = 0
        private var filled = 0
        private var skipping = false
        var checked = 0
            private set
        var failed = 0
            private set

        fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            var at = offset
            var left = length
            while (left > 0) {
                val take = minOf(left, pkg.pieceLength - filled)
                if (!skipping) digest.update(bytes, at, take)
                filled += take
                at += take
                left -= take
                if (filled == pkg.pieceLength) endPiece()
            }
        }

        /** [length] bytes not on the phone: the piece they fall in can't be checked. */
        fun skip(length: Long) {
            var left = length
            while (left > 0) {
                skipping = true
                val take = minOf(left, (pkg.pieceLength - filled).toLong()).toInt()
                filled += take
                left -= take
                if (filled == pkg.pieceLength) endPiece()
            }
        }

        /** After the last file: the last, shorter piece. */
        fun finish(): Boolean {
            if (filled > 0) endPiece()
            return failed == 0 && index == pkg.pieces.size
        }

        private fun endPiece() {
            val hash = digest.digest()
            if (!skipping) {
                if (index < pkg.pieces.size && hash.contentEquals(pkg.pieces[index])) checked++ else failed++
            }
            index++
            filled = 0
            skipping = false
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** BitTorrent's encoding: integers (Long), strings (ByteArray), lists, dictionaries (String keys). */
    private class Bencode(private val b: ByteArray) {
        fun decode(at: Int): Pair<Any, Int> = when (b[at].toInt().toChar()) {
            'i' -> {
                val end = find('e'.code.toByte(), at)
                String(b, at + 1, end - at - 1).toLong() to end + 1
            }
            'l' -> {
                var i = at + 1
                val list = mutableListOf<Any>()
                while (b[i] != 'e'.code.toByte()) {
                    val (value, next) = decode(i)
                    list += value
                    i = next
                }
                list to i + 1
            }
            'd' -> {
                var i = at + 1
                val map = linkedMapOf<String, Any>()
                while (b[i] != 'e'.code.toByte()) {
                    val (key, afterKey) = decode(i)
                    val (value, next) = decode(afterKey)
                    map[(key as ByteArray).decodeToString()] = value
                    i = next
                }
                map to i + 1
            }
            else -> {
                val colon = find(':'.code.toByte(), at)
                val length = String(b, at, colon - at).toInt()
                b.copyOfRange(colon + 1, colon + 1 + length) to colon + 1 + length
            }
        }

        private fun find(byte: Byte, from: Int): Int {
            var i = from
            while (b[i] != byte) i++
            return i
        }
    }
}
