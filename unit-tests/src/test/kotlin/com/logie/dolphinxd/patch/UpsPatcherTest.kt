package com.logie.dolphinxd.patch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpsPatcherTest {
    /** Minimal UPS encoder (the format byuu's tools write), used to make test patches. */
    private fun makeUps(source: ByteArray, target: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun number(value: Long) {
            var v = value
            while (true) {
                val x = (v and 0x7F).toInt()
                v = v shr 7
                if (v == 0L) {
                    out.write(0x80 or x)
                    return
                }
                out.write(x)
                v--
            }
        }
        fun at(array: ByteArray, i: Int) = if (i < array.size) array[i].toInt() and 0xFF else 0
        fun crc(bytes: ByteArray, length: Int = bytes.size) = CRC32().apply { update(bytes, 0, length) }.value
        fun u32(value: Long) {
            for (i in 0 until 4) out.write(((value shr (8 * i)) and 0xFF).toInt())
        }

        out.write("UPS1".toByteArray())
        number(source.size.toLong())
        number(target.size.toLong())
        val end = maxOf(source.size, target.size)
        var last = 0
        var i = 0
        while (i < end) {
            if (at(source, i) xor at(target, i) == 0) {
                i++
                continue
            }
            number((i - last).toLong())
            while (i < end && at(source, i) xor at(target, i) != 0) {
                out.write(at(source, i) xor at(target, i))
                i++
            }
            out.write(0)
            i++
            last = i
        }
        u32(crc(source))
        u32(crc(target))
        val soFar = out.toByteArray()
        u32(crc(soFar))
        return out.toByteArray()
    }

    private fun applyPatch(source: ByteArray, patch: ByteArray, sourceSize: Long? = null): ByteArray {
        val out = ByteArrayOutputStream()
        UpsPatcher.apply(
            ByteArrayInputStream(source),
            ByteArrayInputStream(patch),
            patch.size.toLong(),
            out,
            sourceSize = sourceSize
        )
        return out.toByteArray()
    }

    private fun randomBytes(size: Int, seed: Int) = Random(seed).nextBytes(size)

    @Test
    fun patchesScatteredChanges() {
        val source = randomBytes(3_000_000, 1)
        val target = source.copyOf()
        val rnd = Random(2)
        repeat(500) {
            val at = rnd.nextInt(target.size - 100)
            for (j in 0 until rnd.nextInt(1, 90)) target[at + j] = rnd.nextInt().toByte()
        }
        val patch = makeUps(source, target)
        assertContentEquals(target, applyPatch(source, patch, source.size.toLong()))
    }

    @Test
    fun patchesLongChangedRuns() {
        // Runs longer than the patcher's internal XOR buffer.
        val source = randomBytes(2_500_000, 3)
        val target = source.copyOf()
        for (i in 100_000 until 400_000) target[i] = (target[i] + 1).toByte()
        val patch = makeUps(source, target)
        assertContentEquals(target, applyPatch(source, patch))
    }

    @Test
    fun handlesGrowingAndShrinkingFiles() {
        val source = randomBytes(1_200_000, 4)
        val bigger = source.copyOf(1_700_000).also { Random(5).nextBytes(it, 1_200_000, 1_700_000) }
        assertContentEquals(bigger, applyPatch(source, makeUps(source, bigger)))

        val smaller = source.copyOf(700_000).also { it[10] = 42 }
        assertContentEquals(smaller, applyPatch(source, makeUps(source, smaller)))
    }

    @Test
    fun identicalFilesProduceACopy() {
        val source = randomBytes(100_000, 6)
        assertContentEquals(source, applyPatch(source, makeUps(source, source.copyOf())))
    }

    @Test
    fun readsHeader() {
        val source = randomBytes(5_000, 7)
        val target = source.copyOf(6_000)
        val patch = makeUps(source, target)
        val header = UpsPatcher.readHeader(ByteArrayInputStream(patch), patch.size.toLong())
        assertEquals(UpsPatcher.Header(5_000, 6_000), header)
    }

    @Test
    fun rejectsTheWrongSourceFile() {
        val source = randomBytes(1_000_000, 8)
        val target = source.copyOf().also { it[500] = (it[500] + 1).toByte() }
        val patch = makeUps(source, target)

        val otherRegion = source.copyOf().also { it[999_000] = (it[999_000] + 1).toByte() }
        assertFailsWith<UpsPatcher.SourceMismatchException> { applyPatch(otherRegion, patch) }

        val trimmed = source.copyOf(900_000)
        val error = assertFailsWith<UpsPatcher.SourceMismatchException> {
            applyPatch(trimmed, patch, trimmed.size.toLong())
        }
        assertEquals(1_000_000, error.expectedSize)
    }

    @Test
    fun rejectsDamagedPatches() {
        val source = randomBytes(200_000, 9)
        val target = source.copyOf().also { for (i in 1000 until 2000) it[i] = 7 }
        val patch = makeUps(source, target)

        val damaged = patch.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<UpsPatcher.UpsException> { applyPatch(source, damaged) }

        val cutOff = patch.copyOf(patch.size - 20)
        assertFailsWith<UpsPatcher.UpsException> { applyPatch(source, cutOff) }

        val notUps = "BPS1".toByteArray() + patch.copyOfRange(4, patch.size)
        assertFailsWith<UpsPatcher.NotUpsException> { applyPatch(source, notUps) }
    }

    @Test
    fun reportsProgressAndCanBeCancelled() {
        val source = randomBytes(40_000_000, 10)
        val target = source.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val patch = makeUps(source, target)

        val reports = ArrayList<Long>()
        UpsPatcher.apply(
            ByteArrayInputStream(source), ByteArrayInputStream(patch), patch.size.toLong(),
            ByteArrayOutputStream(), onProgress = { done, total ->
                assertEquals(target.size.toLong(), total)
                reports.add(done)
            }
        )
        assertTrue(reports.size >= 2)
        assertEquals(target.size.toLong(), reports.last())

        assertFailsWith<UpsPatcher.CancelledException> {
            UpsPatcher.apply(
                ByteArrayInputStream(source), ByteArrayInputStream(patch), patch.size.toLong(),
                ByteArrayOutputStream(), isCancelled = { true }
            )
        }
    }
}
