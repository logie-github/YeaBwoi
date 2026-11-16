package com.logie.wifecraft.creator

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An emote's animation files are fetched when it is played (M2Files.animFiles): every variation of its animation, the
 * sequences its are aliases of, through the skeleton and its parent. Release 1.0.33 fetched only the story's emotes at
 * variation 0, and 357 of 3,207 emotes of 7 models stood in the bind pose (arms out) for want of their files.
 */
class EmoteAnimFilesTest {
    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun chunk(tag: String, body: ByteArray): ByteArray =
        le(8 + body.size).put(tag.toByteArray(Charsets.ISO_8859_1)).putInt(body.size).put(body).array()

    private fun afid(vararg e: Triple<Int, Int, Int>): ByteArray {
        val b = le(8 * e.size)
        for ((a, v, f) in e) { b.putShort(a.toShort()); b.putShort(v.toShort()); b.putInt(f) }
        return chunk("AFID", b.array())
    }

    /** SKS1: no global loops, then the sequences (anim, variation, flags, aliasNext), 64 bytes each, right after the header. */
    private fun sks1(vararg s: IntArray): ByteArray {
        val b = le(16 + 64 * s.size)
        b.putInt(0); b.putInt(0); b.putInt(s.size); b.putInt(16)
        for (q in s) {
            val o = 16 + 64 * s.indexOf(q)
            b.putShort(o, q[0].toShort()); b.putShort(o + 2, q[1].toShort()); b.putInt(o + 12, q[2]); b.putShort(o + 62, q[3].toShort())
        }
        return chunk("SKS1", b.array())
    }

    private fun u32s(tag: String, vararg v: Int): ByteArray = le(4 * v.size).also { b -> v.forEach { b.putInt(it) } }.array().let { chunk(tag, it) }

    private fun file(vararg c: ByteArray) = ByteArrayOutputStream().also { o -> c.forEach { o.write(it) } }.toByteArray()

    // model 1 -> skeleton 20 (child of 30). Sit (97) has two variations; Work (136) is an alias of sequence 3 (anim 62, var 0);
    // the parent alone has Sleep (100).
    private val model = file(chunk("MD21", ByteArray(40)), u32s("SKID", 20), u32s("SFID", 11))
    private val skel = file(
        sks1(intArrayOf(0, 0, 0x20, 0), intArrayOf(97, 0, 0, 0), intArrayOf(97, 1, 0, 0), intArrayOf(62, 0, 0, 0), intArrayOf(136, 0, 0x40, 3)),
        u32s("SKPD", 0, 0, 30),
        afid(Triple(97, 0, 201), Triple(97, 1, 202), Triple(62, 0, 203), Triple(136, 0, 0), Triple(4, 0, 204)),
    )
    private val parent = file(sks1(intArrayOf(100, 0, 0, 0)), afid(Triple(100, 0, 301), Triple(97, 0, 302)))
    private val disk = mapOf(1 to model, 20 to skel, 30 to parent)

    @Test fun everyVariationOfTheEmote() {
        assertEquals(setOf(201, 202, 302), M2Files.animFiles(1, 97) { disk[it] })   // the child's two, the parent's own
    }

    @Test fun anAliasFetchesTheSequenceItPlays() {
        assertEquals(setOf(203), M2Files.animFiles(1, 136) { disk[it] })
    }

    @Test fun anAnimationOnlyTheParentHas() {
        assertEquals(setOf(301), M2Files.animFiles(1, 100) { disk[it] })
    }

    @Test fun keysInTheSkeletonNeedNoFileAndAMissingSkeletonNamesNothing() {
        assertEquals(emptySet(), M2Files.animFiles(1, 0) { disk[it] })
        assertEquals(emptySet(), M2Files.animFiles(1, 97) { if (it == 1) model else null })
    }

    /** With the game's files at hand (WIFECRAFT_GAME_FILES: a folder of files by id), prints what the six test models need. */
    @Test fun realModels() {
        val dir = System.getenv("WIFECRAFT_GAME_FILES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val read = { id: Int -> File(dir, "$id").takeIf { it.exists() }?.readBytes() }
        val out = StringBuilder()
        for (m in listOf(1000764, 1011653, 921844, 968705, 940356, 307454, 4220448))
            for (a in listOf(97, 100, 61, 78, 136, 63, 211, 67)) out.append("$m $a ${M2Files.animFiles(m, a, read).sorted()}\n")
        System.getenv("WIFECRAFT_ANIMFILES_OUT")?.let { File(it).writeText(out.toString()) }
        assertTrue(out.isNotEmpty())
    }
}
