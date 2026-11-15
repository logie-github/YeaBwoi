package com.logie.wifecraft.creator

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Today's character models keep their bones in a skeleton file (SKID) that can have a parent (SKPD): what the dresser
 * fetches for a model follows both (W16: every race but the old human models drew nothing), and files from before the
 * build was pinned are cleared once.
 */
class SkeletonFilesTest {
    private fun chunk(tag: String, vararg u32: Int): ByteArray {
        val b = ByteBuffer.allocate(8 + 4 * u32.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(tag.toByteArray(Charsets.ISO_8859_1)); b.putInt(4 * u32.size)
        for (v in u32) b.putInt(v)
        return b.array()
    }

    /** AFID entries: (anim, variation, file). */
    private fun afid(vararg e: Triple<Int, Int, Int>): ByteArray {
        val b = ByteBuffer.allocate(8 + 8 * e.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("AFID".toByteArray(Charsets.ISO_8859_1)); b.putInt(8 * e.size)
        for ((a, v, f) in e) { b.putShort(a.toShort()); b.putShort(v.toShort()); b.putInt(f) }
        return b.array()
    }

    private fun file(vararg chunks: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> chunks.forEach { o.write(it) } }.toByteArray()

    // a model (MD21, no bones of its own), its skeleton 20 (child of 30), the parent 30
    private val model = file(chunk("MD21", 0x3032444D, 274), chunk("SKID", 20), chunk("SFID", 11, 12), chunk("TXID", 13, 0))
    private val skel = file(chunk("SKL1", 256, 0, 0, 0), chunk("SKPD", 0, 0, 30), afid(Triple(0, 0, 21), Triple(4, 0, 22), Triple(0, 1, 23)))
    private val parent = file(chunk("SKL1", 256, 0, 0, 0), afid(Triple(0, 0, 31), Triple(5, 0, 32)))

    @Test fun aModelNamesItsSkeletonAndASkeletonItsParent() {
        assertEquals(listOf(20, 11, 13), M2Files.deps(model, setOf(0)))
        assertEquals(listOf(20), M2Files.skeletons(model))
        assertEquals(listOf(30, 21), M2Files.deps(skel, setOf(0)))
        assertEquals(listOf(30), M2Files.skeletons(skel))
        assertEquals(emptyList(), M2Files.skeletons(parent))
    }

    @Test fun theClosureFetchesTheSkeletonsAndTheirAnimations() {
        val disk = HashMap<Int, ByteArray>()
        disk[1] = model
        val remote = mapOf(20 to skel, 30 to parent, 11 to ByteArray(1), 13 to ByteArray(1), 21 to ByteArray(1), 22 to ByteArray(1), 31 to ByteArray(1), 32 to ByteArray(1))
        val asked = ArrayList<Int>()
        val all = M2Files.closure(1, setOf(0, 4), { disk[it] }) { ids -> for (i in ids) if (i !in disk) { asked += i; remote[i]?.let { disk[i] = it } } }
        assertEquals(setOf(20, 11, 13, 30, 21, 22, 31), all)
        assertEquals(listOf(20, 11, 13, 30, 21, 22, 31), asked)        // the skeleton, then its parent and animations, then the parent's
        assertFalse(32 in asked)                                         // an animation not asked for
    }

    @Test fun aSkeletonThatWontComeEndsTheWalk() {
        val all = M2Files.closure(1, setOf(0), { if (it == 1) model else null }) { }
        assertEquals(setOf(20, 11, 13), all)
    }

    @Test fun filesFromBeforeTheBuildWasPinnedGoOnce() {
        val root = Files.createTempDirectory("files").toFile()
        val game = File(root, "game").apply { mkdirs() }
        File(game, "100").writeBytes(model)                               // a model: goes
        File(game, "101").writeBytes(file(chunk("SKIN", 1)))               // its skin: goes
        File(game, "102").writeBytes(skel)                                 // a skeleton: goes
        File(game, "103").writeBytes("BLP2xxxx".toByteArray())             // a texture: stays
        File(game, "104").writeBytes(file(chunk("AFM2", 1)))               // an animation file: goes
        val tables = File(root, "cache/tables").apply { mkdirs() }
        File(tables, "ChrModel.csv").writeText("ID\n1\n")
        File(root, "cache/looks-complete").writeText("1")
        assertEquals(5, GameTables.migrate(root))
        assertEquals(listOf("103"), game.listFiles()!!.map { it.name }.filter { it != ".build" })
        assertFalse(File(tables, "ChrModel.csv").exists())
        assertFalse(File(root, "cache/looks-complete").exists())
        assertEquals(GameTables.BUILD, File(game, ".build").readText())
        // once: what is fetched at the pinned build stays
        File(game, "100").writeBytes(model)
        assertEquals(0, GameTables.migrate(root))
        assertTrue(File(game, "100").exists())
        assertTrue(GameTables.fileUrl(7).endsWith("/api/casc/7?download&version=${GameTables.BUILD}"))
    }
}
