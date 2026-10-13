// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A zip on a web server, read a piece at a time: its directory from the end of the file, then just
 * the files wanted, so a few patches come out of a 29 GB client without downloading it. Zip64
 * (files and archives over 4 GB) included.
 */
object RemoteZip {
    /** Reads [length] bytes of the file from [from]. */
    fun interface Ranges {
        fun read(from: Long, length: Int): ByteArray
    }

    data class Entry(
        val name: String,
        /** 0 stored, 8 deflated. */
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val crc: Long,
        val localHeader: Long,
    )

    private const val EOCD = 0x06054b50
    private const val ZIP64_LOCATOR = 0x07064b50
    private const val ZIP64_EOCD = 0x06064b50
    private const val CENTRAL = 0x02014b50
    private const val LOCAL = 0x04034b50

    /** The zip's files, for a zip of [size] bytes. */
    fun directory(size: Long, ranges: Ranges): List<Entry> {
        val tailLength = minOf(size, 70_000L).toInt()
        val tailStart = size - tailLength
        val tail = buffer(ranges.read(tailStart, tailLength))
        var eocd = -1
        for (i in tailLength - 22 downTo 0) {
            if (tail.getInt(i) == EOCD) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw IOException("Not a zip (no directory at its end)")
        var count = tail.getShort(eocd + 10).toLong() and 0xFFFF
        var directorySize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        var directoryOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        val locator = eocd - 20
        if (locator >= 0 && tail.getInt(locator) == ZIP64_LOCATOR) {
            val zip64At = tail.getLong(locator + 8)
            val record = buffer(ranges.read(zip64At, 56))
            if (record.getInt(0) != ZIP64_EOCD) throw IOException("Damaged zip64 directory")
            count = record.getLong(32)
            directorySize = record.getLong(40)
            directoryOffset = record.getLong(48)
        }
        if (directoryOffset + directorySize > size || directorySize > Int.MAX_VALUE) throw IOException("Damaged zip directory")
        val directory = if (directoryOffset >= tailStart) {
            val from = (directoryOffset - tailStart).toInt()
            buffer(tail.array().copyOfRange(from, from + directorySize.toInt()))
        } else {
            buffer(ranges.read(directoryOffset, directorySize.toInt()))
        }
        return entries(directory, count)
    }

    private fun entries(directory: ByteBuffer, count: Long): List<Entry> {
        val out = ArrayList<Entry>()
        var p = 0
        for (i in 0 until count) {
            if (p + 46 > directory.limit() || directory.getInt(p) != CENTRAL) throw IOException("Damaged zip directory")
            val method = directory.getShort(p + 10).toInt() and 0xFFFF
            val crc = directory.getInt(p + 16).toLong() and 0xFFFFFFFFL
            var compressed = directory.getInt(p + 20).toLong() and 0xFFFFFFFFL
            var size = directory.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLength = directory.getShort(p + 28).toInt() and 0xFFFF
            val extraLength = directory.getShort(p + 30).toInt() and 0xFFFF
            val commentLength = directory.getShort(p + 32).toInt() and 0xFFFF
            var local = directory.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val name = String(directory.array(), p + 46, nameLength, Charsets.UTF_8)
            // Zip64: the sizes and the offset that didn't fit come in an extra field, in this order.
            var q = p + 46 + nameLength
            val extraEnd = q + extraLength
            while (q + 4 <= extraEnd) {
                val id = directory.getShort(q).toInt() and 0xFFFF
                val length = directory.getShort(q + 2).toInt() and 0xFFFF
                if (id == 1) {
                    var r = q + 4
                    if (size == 0xFFFFFFFFL) size = directory.getLong(r).also { r += 8 }
                    if (compressed == 0xFFFFFFFFL) compressed = directory.getLong(r).also { r += 8 }
                    if (local == 0xFFFFFFFFL) local = directory.getLong(r).also { r += 8 }
                }
                q += 4 + length
            }
            out += Entry(name, method, compressed, size, crc, local)
            p = extraEnd + commentLength
        }
        return out
    }

    /** Where [entry]'s data starts, from the first 30 bytes of its local header. */
    fun dataStart(entry: Entry, localHeader: ByteArray): Long {
        val header = buffer(localHeader)
        if (localHeader.size < 30 || header.getInt(0) != LOCAL) throw IOException("Damaged zip entry: ${entry.name}")
        val nameLength = header.getShort(26).toInt() and 0xFFFF
        val extraLength = header.getShort(28).toInt() and 0xFFFF
        return entry.localHeader + 30 + nameLength + extraLength
    }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
