// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd.patch

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Applies UPS patches (the format Pokémon XG: Next Gen is distributed in) as a stream, so a
 * 1.4 GB disc image never has to fit in memory.
 *
 * Format: "UPS1", source size, target size (both variable-length integers), then hunks of
 * (bytes to copy unchanged, XOR bytes ending with a 0x00 byte), then the CRC32s of the source,
 * the target and the patch itself.
 */
object UpsPatcher {
    private val MAGIC = "UPS1".toByteArray(Charsets.US_ASCII)
    private const val FOOTER_SIZE = 12
    private const val BUFFER_SIZE = 1 shl 20
    private const val PROGRESS_INTERVAL = 16L * BUFFER_SIZE

    data class Header(val sourceSize: Long, val targetSize: Long)

    open class UpsException(message: String) : IOException(message)

    class NotUpsException : UpsException("Not a UPS patch")

    class CorruptPatchException(message: String) : UpsException(message)

    /** The file being patched isn't the one the patch was made for. */
    class SourceMismatchException(val expectedSize: Long, val actualSize: Long?) :
        UpsException("The patch doesn't match this file")

    class CancelledException : UpsException("Cancelled")

    fun readHeader(patch: InputStream, patchSize: Long): Header {
        val reader = PatchReader(patch, patchSize, CRC32())
        reader.expectMagic()
        return Header(reader.readNumber(), reader.readNumber())
    }

    /**
     * Writes the patched file to [target]. Both streams are buffered internally.
     *
     * @param patchSize total size of the patch in bytes
     * @param sourceSize size of [source] if known, to fail before doing any work
     * @param onProgress called now and then with (bytes written, total bytes)
     * @throws SourceMismatchException if [source] isn't the file the patch was made for
     */
    fun apply(
        source: InputStream,
        patch: InputStream,
        patchSize: Long,
        target: OutputStream,
        sourceSize: Long? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        if (patchSize < MAGIC.size + 2 + FOOTER_SIZE) {
            throw CorruptPatchException("The patch file is too small")
        }

        val patchCrc = CRC32()
        val reader = PatchReader(patch, patchSize, patchCrc)
        reader.expectMagic()
        val expectedSourceSize = reader.readNumber()
        val targetSize = reader.readNumber()
        if (sourceSize != null && sourceSize != expectedSourceSize) {
            throw SourceMismatchException(expectedSourceSize, sourceSize)
        }

        val bufferedTarget = target.buffered(BUFFER_SIZE)
        val copier = Copier(source.buffered(BUFFER_SIZE), expectedSourceSize, bufferedTarget, targetSize)
        var nextReport = 0L
        val report = {
            if (copier.written >= nextReport) {
                nextReport = copier.written + PROGRESS_INTERVAL
                onProgress(minOf(copier.written, targetSize), targetSize)
                if (isCancelled()) throw CancelledException()
            }
        }

        val hunksEnd = patchSize - FOOTER_SIZE
        val xorRun = ByteArray(64 * 1024)
        while (reader.position < hunksEnd) {
            copier.copy(reader.readNumber(), report)
            do {
                val length = reader.readXorRun(xorRun)
                copier.writeXor(xorRun, length)
            } while (!reader.lastRunTerminated)
            if (reader.position > hunksEnd) {
                throw CorruptPatchException("The patch data runs into the checksums")
            }
            report()
        }

        // Whatever the hunks didn't reach is copied unchanged (and padded with zeros).
        copier.copy(maxOf(0L, targetSize - copier.written), report)
        copier.drainSource()
        bufferedTarget.flush()
        onProgress(targetSize, targetSize)

        val sourceCrc = reader.readUInt32()
        val targetCrc = reader.readUInt32()
        val storedPatchCrc = reader.readUInt32()

        if (patchCrc.value != storedPatchCrc) {
            throw CorruptPatchException("The patch file is damaged (checksum mismatch)")
        }
        if (copier.sourceRead != expectedSourceSize || copier.sourceCrc.value != sourceCrc) {
            throw SourceMismatchException(expectedSourceSize, sourceSize ?: copier.sourceRead)
        }
        if (copier.targetCrc.value != targetCrc) {
            throw CorruptPatchException("The patched file came out wrong (checksum mismatch)")
        }
    }

    /**
     * Reads the patch through its own buffer. The patch CRC covers everything except the last
     * four bytes (the patch CRC itself) and is updated a buffer at a time.
     */
    private class PatchReader(
        private val input: InputStream,
        private val size: Long,
        private val crc: CRC32,
    ) {
        private val crcEnd = size - 4
        private val buffer = ByteArray(64 * 1024)
        private var bufferStart = 0L
        private var bufferPos = 0
        private var bufferLength = 0

        var lastRunTerminated = false
            private set

        val position: Long
            get() = bufferStart + bufferPos

        private fun refill() {
            bufferStart += bufferLength
            bufferPos = 0
            bufferLength = 0
            val wanted = minOf(buffer.size.toLong(), size - bufferStart).toInt()
            while (bufferLength < wanted) {
                val n = input.read(buffer, bufferLength, wanted - bufferLength)
                if (n < 0) break
                bufferLength += n
            }
            if (bufferLength == 0) {
                throw CorruptPatchException("The patch file is cut off")
            }
            val crcBytes = (minOf(bufferStart + bufferLength, crcEnd) - bufferStart)
                .coerceIn(0L, bufferLength.toLong()).toInt()
            crc.update(buffer, 0, crcBytes)
        }

        fun readByte(): Int {
            if (bufferPos == bufferLength) refill()
            return buffer[bufferPos++].toInt() and 0xFF
        }

        fun expectMagic() {
            for (expected in MAGIC) {
                if (readByte() != expected.toInt()) throw NotUpsException()
            }
        }

        /** UPS/BPS variable-length integer. */
        fun readNumber(): Long {
            var result = 0L
            var shift = 1L
            while (true) {
                val b = readByte()
                result += (b and 0x7F) * shift
                if (b and 0x80 != 0) break
                shift = shift shl 7
                result += shift
                if (shift > (1L shl 56)) {
                    throw CorruptPatchException("The patch contains an invalid number")
                }
            }
            return result
        }

        /** Reads XOR bytes up to and including the terminating zero, or until [dest] is full. */
        fun readXorRun(dest: ByteArray): Int {
            var n = 0
            while (n < dest.size) {
                val b = readByte()
                dest[n++] = b.toByte()
                if (b == 0) {
                    lastRunTerminated = true
                    return n
                }
            }
            lastRunTerminated = false
            return n
        }

        fun readUInt32(): Long =
            readByte().toLong() or (readByte().toLong() shl 8) or
                    (readByte().toLong() shl 16) or (readByte().toLong() shl 24)
    }

    /** Streams source to target, keeping both CRCs. Source bytes past its end read as zero. */
    private class Copier(
        private val source: InputStream,
        private val sourceSize: Long,
        private val target: OutputStream,
        private val targetSize: Long,
    ) {
        val sourceCrc = CRC32()
        val targetCrc = CRC32()
        var sourceRead = 0L
            private set
        var written = 0L
            private set
        private val buffer = ByteArray(BUFFER_SIZE)
        private var sourceEnded = false

        /** Fills buffer[0, count) with the next source bytes, zero-filling past the end. */
        private fun readSource(count: Int) {
            var filled = 0
            val fromFile = minOf(count.toLong(), maxOf(0L, sourceSize - sourceRead)).toInt()
            while (filled < fromFile && !sourceEnded) {
                val n = source.read(buffer, filled, fromFile - filled)
                if (n < 0) {
                    sourceEnded = true
                    break
                }
                filled += n
            }
            sourceCrc.update(buffer, 0, filled)
            sourceRead += filled
            buffer.fill(0, filled, count)
        }

        private fun writeTarget(count: Int) {
            val toWrite = minOf(count.toLong(), maxOf(0L, targetSize - written)).toInt()
            if (toWrite > 0) {
                target.write(buffer, 0, toWrite)
                targetCrc.update(buffer, 0, toWrite)
            }
            written += count
        }

        fun copy(count: Long, onChunk: () -> Unit) {
            var remaining = count
            while (remaining > 0) {
                val chunk = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                readSource(chunk)
                writeTarget(chunk)
                remaining -= chunk
                onChunk()
            }
        }

        fun writeXor(xor: ByteArray, length: Int) {
            readSource(length)
            for (i in 0 until length) {
                buffer[i] = (buffer[i].toInt() xor xor[i].toInt()).toByte()
            }
            writeTarget(length)
        }

        /** Reads the rest of the source so its CRC covers the whole file. */
        fun drainSource() {
            while (sourceRead < sourceSize && !sourceEnded) {
                readSource(minOf(sourceSize - sourceRead, BUFFER_SIZE.toLong()).toInt())
            }
        }
    }
}
