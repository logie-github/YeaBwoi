// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.hd

/**
 * 7z extraction for HD texture packs (native, src/main/cpp/pe7z.c): an archive split in parts
 * (name.7z.001, .002, ...) or whole, read straight from the files the player picked. Every image in
 * it, whatever folder it's in, lands directly in the output folder.
 */
object SevenZip {
    init {
        System.loadLibrary("pe7z")
    }

    /** What an archive holds (images and their bytes, other files), or why it couldn't be read. */
    class Summary(val result: Int, val files: Long, val bytes: Long, val skipped: Long) {
        val ok: Boolean get() = result == OK
    }

    fun interface Progress {
        /** Bytes decoded so far, of [total]; returns true to stop. */
        fun onProgress(done: Long, total: Long): Boolean
    }

    const val OK = 0
    const val NOT_7Z = 1
    const val MISSING_PARTS = 2
    const val DAMAGED = 3
    const val UNSUPPORTED = 4
    const val READ_ERROR = 5
    const val WRITE_ERROR = 6
    const val CRC_ERROR = 7
    const val OUT_OF_MEMORY = 8
    const val CANCELLED = 9

    /** [fds] are the parts in order, [sizes] their sizes. */
    fun inspect(fds: IntArray, sizes: LongArray): Summary = summary(nativeInspect(fds, sizes))

    fun extract(fds: IntArray, sizes: LongArray, outDir: String, progress: Progress): Summary =
        summary(nativeExtract(fds, sizes, outDir, progress))

    private fun summary(values: LongArray) = Summary(values[0].toInt(), values[1], values[2], values[3])

    @JvmStatic
    private external fun nativeInspect(fds: IntArray, sizes: LongArray): LongArray

    @JvmStatic
    private external fun nativeExtract(fds: IntArray, sizes: LongArray, outDir: String, progress: Progress): LongArray
}
