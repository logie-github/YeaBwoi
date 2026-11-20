package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.bodyparts.BodyPartFixtures.Vtx
import com.logie.wifecraft.sims.SimsFixtures
import com.logie.wifecraft.sims.SimsFixtures.Buf
import com.logie.wifecraft.sims.SimsHash
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextureTest {
    private fun px(out: ByteArray, i: Int) = IntArray(4) { out[i * 4 + it].toInt() and 0xFF }.toList()

    @Test
    fun dxt1BlockFourAndThreeColourModes() {
        val out = ByteArray(64)
        // c0 = pure red (0xF800) > c1 = pure blue (0x001F): four colours, index 2 = 2/3 red + 1/3 blue
        val blk = byteArrayOf(0x00, 0xF8.toByte(), 0x1F, 0x00, 0b11100100.toByte(), 0, 0, 0)
        SimsTexture.decodeDxt1Block(blk, 0, out)
        assertEquals(listOf(255, 0, 0, 255), px(out, 0))
        assertEquals(listOf(0, 0, 255, 255), px(out, 1))
        assertEquals(listOf(170, 0, 85, 255), px(out, 2))
        assertEquals(listOf(85, 0, 170, 255), px(out, 3))
        // c0 <= c1: three colours and transparent black at index 3
        val three = byteArrayOf(0x1F, 0x00, 0x00, 0xF8.toByte(), 0b11100100.toByte(), 0, 0, 0)
        SimsTexture.decodeDxt1Block(three, 0, out)
        assertEquals(listOf(127, 0, 127, 255), px(out, 2))
        assertEquals(listOf(0, 0, 0, 0), px(out, 3))
        // DXT5's colour half always uses four colours
        SimsTexture.decodeDxt1Block(three, 0, out, fourColour = true)
        assertEquals(255, px(out, 3)[3])
    }

    @Test
    fun dxt5AlphaBothModes() {
        val out = ByteArray(64)
        val idx = IntArray(16) { it % 8 }
        val blk = BodyPartFixtures.dxt5(200, 60, idx, 0, 0, IntArray(16))
        SimsTexture.decodeDxt5AlphaBlock(blk, 0, out)
        assertEquals(200, out[3].toInt() and 0xFF)
        assertEquals(60, out[7].toInt() and 0xFF)
        assertEquals((6 * 200 + 60 + 3) / 7, out[11].toInt() and 0xFF)
        val six = BodyPartFixtures.dxt5(60, 200, idx, 0, 0, IntArray(16))
        SimsTexture.decodeDxt5AlphaBlock(six, 0, out)
        assertEquals(0, out[6 * 4 + 3].toInt() and 0xFF)
        assertEquals(255, out[7 * 4 + 3].toInt() and 0xFF)
        assertEquals((4 * 60 + 200 + 2) / 5, out[2 * 4 + 3].toInt() and 0xFF)
    }

    @Test
    fun rle2ContainerTransparentCopiedAndOpaqueBlocks() {
        val green = BodyPartFixtures.dxt5(128, 128, IntArray(16), 0x07E0, 0, IntArray(16)) // alpha 128, green
        val blue = BodyPartFixtures.dxt5(0, 0, IntArray(16), 0x001F, 0, IntArray(16))
        val bytes = BodyPartFixtures.rle2(12, 4, listOf(0 to ByteArray(16), 1 to green, 2 to blue))
        assertEquals("RLE2", SimsTexture.kind(bytes))
        val img = SimsTexture.decode(bytes)
        assertEquals(12, img.width); assertEquals(4, img.height)
        assertEquals(0x00FFFFFF, img.pixel(1, 1))            // op 0: transparent white
        assertEquals(0x8000FF00.toInt(), img.pixel(5, 2))   // op 1: copied block, alpha 128
        assertEquals(0xFF0000FF.toInt(), img.pixel(10, 3))  // op 2: colour copied, alpha 255
    }

    @Test
    fun rle2RejectsTooFewBlocks() {
        val bytes = BodyPartFixtures.rle2(8, 8, listOf(0 to ByteArray(16)))
        assertFailsWith<BodyPartFormatException> { SimsTexture.decode(bytes) }
    }

    @Test
    fun lrleV2ColourTableAndRuns() {
        // 4x4: one tile; table [red, green]; repeat red x10 (1-byte index), then 6 literal greens via index list
        val b = Buf().bytes("LRLE".toByteArray()).bytes("V002".toByteArray()).u16(4).u16(4).u32(1).u32(0)
            .u32(2).u8(0).u8(0).u8(255).u8(255).u8(0).u8(255).u8(0).u8(255) // BGRA red, BGRA green
        b.u8((10 shl 3) or 2).u8(0)                       // repeat run, one-byte index
        b.u8((6 shl 2) or 1); repeat(6) { b.u8(1) }       // index list
        val img = SimsTexture.decode(b.get())
        assertEquals(0xFFFF0000.toInt(), img.pixel(0, 0))
        assertEquals(0xFFFF0000.toInt(), img.pixel(1, 2))  // pixel 9
        assertEquals(0xFF00FF00.toInt(), img.pixel(2, 2))  // pixel 10
        assertEquals(0xFF00FF00.toInt(), img.pixel(3, 3))
    }

    @Test
    fun dst1MatchesDxt1() {
        fun dds(fourcc: String, data: ByteArray): ByteArray {
            val h = Buf().bytes("DDS ".toByteArray()).u32(124).u32(0).u32(4).u32(8)
            while (h.size < 76) h.u32(0)
            h.u32(32).u32(4).bytes(fourcc.toByteArray())
            while (h.size < 128) h.u32(0)
            return h.bytes(data).get()
        }
        val b1 = byteArrayOf(0x00, 0xF8.toByte(), 0x1F, 0x00, 0x1B, 0x2C, 0x3D, 0x4E)
        val b2 = byteArrayOf(0xE0.toByte(), 0x07, 0x00, 0x00, 0x55, 0x66, 0x77, 0x00)
        val dxt = dds("DXT1", b1 + b2)
        val dst = dds("DST1", b1.copyOfRange(0, 4) + b2.copyOfRange(0, 4) + b1.copyOfRange(4, 8) + b2.copyOfRange(4, 8))
        assertContentEquals(SimsTexture.decode(dxt).rgba, SimsTexture.decode(dst).rgba)
    }

    @Test
    fun pngHasSignatureAndChunks() {
        val png = Png.encode(RgbaImage(2, 1, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertEquals(0x89.toByte(), png[0]); assertEquals("IHDR", String(png, 12, 4)); assertEquals("IEND", String(png, png.size - 8, 4))
    }
}

class GeomCaspTest {
    private val square = listOf(Vtx(0f, 1f, 0f, 0f, 0f), Vtx(1f, 1f, 0f, 1f, 0f), Vtx(1f, 2f, 0f, 1f, 1f), Vtx(0f, 2f, 0.5f, 0f, 1f, intArrayOf(1, 0, 0, 0), intArrayOf(128, 127, 0, 0)))

    @Test
    fun geomReadsVerticesFacesAndBones() {
        for (v in listOf(12, 13, 14, 15)) for (rcol in listOf(true, false)) {
            val m = Geom.parse(BodyPartFixtures.geom(square, listOf(0, 1, 2, 0, 2, 3), listOf("b__Pelvis__", "b__Penis_Base"), v, rcol))
            assertEquals(4, m.vertexCount); assertEquals(2, m.triangleCount)
            assertEquals(0.5f, m.positions[11]); assertEquals(1f, m.uvs!![5])
            assertEquals(SimsHash.fnv32("b__Penis_Base"), m.boneHashes[1])
            assertEquals(1, m.dominantBone(3))
            assertEquals(3, m.vertexIds!![3])
            assertTrue(m.notes.isEmpty(), "v$v: ${m.notes}")
            assertContentEquals(floatArrayOf(0f, 1f, 0f, 1f, 2f, 0.5f), m.bounds())
        }
    }

    @Test
    fun geomRejectsOutOfRangeTriangleAndBadVersion() {
        assertFailsWith<BodyPartFormatException> { Geom.parse(BodyPartFixtures.geom(square, listOf(0, 1, 7), listOf("b__Pelvis__"))) }
        assertFailsWith<BodyPartFormatException> { Geom.parse(BodyPartFixtures.geom(square, listOf(0, 1, 2), listOf("b__Pelvis__"), version = 9)) }
        assertFailsWith<BodyPartFormatException> { Geom.parse(ByteArray(10)) }
    }

    @Test
    fun caspReadsNameTypeLodsAndTextures() {
        val g0 = ResourceKey(Geom.TYPE, 0x8006DFA8.toInt(), 0x1111L)
        val g1 = ResourceKey(Geom.TYPE, 0x8006DFA9.toInt(), 0x1111L)
        val tex = ResourceKey(SimsTexture.TYPE_RLE, 0, 0x2222L)
        for (ver in listOf(46, 49)) {
            val d = Casp.parse(BodyPartFixtures.casp("demo_Small_Soft_Uncut_High", listOf(g0, g1), tex, ver))
            assertEquals(ver, d.version)
            assertEquals("demo_Small_Soft_Uncut_High", d.name)
            assertEquals(7, d.bodyType); assertTrue(d.isMale); assertTrue(d.isAdult)
            assertEquals(listOf(70 to 81), d.tags)
            assertEquals(1, d.swatchColors.size)
            assertEquals(listOf(g0), d.finestMeshes()); assertEquals(0, d.finestLevel())
            assertEquals(tex, d.diffuse)
            assertEquals(2217, d.sortLayer)
        }
    }

    @Test
    fun caspRejectsLayoutMismatch() {
        val b = BodyPartFixtures.casp("x", emptyList(), null)
        b[0] = 45 // a version whose layout differs: its fields no longer end at the key table
        assertFailsWith<BodyPartFormatException> { Casp.parse(b) }
    }
}

class RecolorTest {
    private fun grey(vararg v: Int) = RgbaImage(v.size, 1, ByteArray(v.size * 4) { i -> if (i % 4 == 3) 255.toByte() else v[i / 4].toByte() })

    @Test
    fun averageBecomesTargetAndShadingIsKept() {
        val img = grey(100, 150, 200)
        val ref = Recolor.averageColor(img)!!
        val target = 0xC08060
        val out = Recolor.recolor(img, target, reference = ref)
        val mid = out.pixel(0, 0) and 0xFFFFFF
        val bright = out.pixel(2, 0) and 0xFFFFFF
        // darker stays darker, brighter brighter, in every channel; hue follows the target (r > g > b)
        for (s in listOf(16, 8, 0)) assertTrue(((mid shr s) and 255) < ((bright shr s) and 255))
        assertTrue((bright shr 16 and 255) > (bright shr 8 and 255) && (bright shr 8 and 255) > (bright and 255))
        // a pixel equal to the reference becomes exactly the target
        val flat = RgbaImage(1, 1, byteArrayOf((ref shr 16).toByte(), (ref shr 8).toByte(), ref.toByte(), 77))
        val f = Recolor.recolor(flat, target, reference = ref)
        assertEquals(target, f.pixel(0, 0) and 0xFFFFFF)
        assertEquals(77, f.pixel(0, 0) ushr 24)
    }

    @Test
    fun strengthZeroKeepsTheImageAndMaskLimitsTheReference() {
        val img = grey(50, 250)
        assertContentEquals(img.rgba, Recolor.recolor(img, 0xFF0000, strength = 0f).rgba)
        assertEquals(0x323232, Recolor.averageColor(img, booleanArrayOf(true, false)))
        assertNull(Recolor.averageColor(RgbaImage(1, 1, ByteArray(4))))
    }

    @Test
    fun uvMaskCoversTheTriangle() {
        val m = Recolor.uvMask(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2), 8, 8)
        assertTrue(m[0]); assertTrue(m[1 * 8 + 1]); assertTrue(!m[7 * 8 + 7])
        assertTrue(m.count { it } in 28..40)
    }
}

class PartMeshTest {
    private val pelvis = "b__Pelvis__"

    /** Two pieces: a body strip weighted to the thigh, and a part piece weighted to its own bone and the pelvis. */
    private fun twoPieces(): SimsMesh {
        val body = listOf(Vtx(-0.2f, 0.3f, 0f), Vtx(-0.1f, 0.3f, 0f), Vtx(-0.1f, 0.8f, 0f)).map { Vtx(it.x, it.y, it.z, bones = intArrayOf(2, 0, 0, 0)) }
        val part = listOf(
            Vtx(0f, 0.9f, 0.10f, 0.5f, 0.5f, intArrayOf(0, 0, 0, 0), intArrayOf(255, 0, 0, 0)),
            Vtx(0.02f, 0.9f, 0.12f, 0.6f, 0.5f, intArrayOf(1, 0, 0, 0)),
            Vtx(0f, 0.88f, 0.15f, 0.6f, 0.6f, intArrayOf(1, 0, 0, 0)),
        )
        return Geom.parse(BodyPartFixtures.geom(body + part, listOf(0, 1, 2, 3, 4, 5), listOf(pelvis, "b__Penis_Base", "b__L_Thigh__")))
    }

    @Test
    fun extractKeepsOnlyThePiecesOnOwnBones() {
        val m = PartMesh.extract(twoPieces())!!
        assertEquals(3, m.vertexCount); assertEquals(1, m.triangleCount)
        assertTrue(m.indices.all { it in 0..2 })
        assertEquals(0.9f, m.positions[1])
        val bodyOnly = Geom.parse(BodyPartFixtures.geom(listOf(Vtx(0f, 0f, 0f), Vtx(1f, 0f, 0f), Vtx(0f, 1f, 0f)), listOf(0, 1, 2), listOf(pelvis)))
        assertNull(PartMesh.extract(bodyOnly))
    }

    @Test
    fun convertMovesToWarcraftAxesAroundTheAnchorAndFoldsOwnBones() {
        val m = PartMesh.extract(twoPieces())!!
        val c = PartMesh.convert(m, AttachSpec.default(), "id", "DEFAULT", "Small", null)
        // anchor: the vertex weighted to the pelvis (0, 0.9, 0.10)
        assertContentEquals(floatArrayOf(0f, 0.9f, 0.1f), c.anchorSims)
        // vertex 2 (0, 0.88, 0.15) -> Warcraft x = sims z, y = sims x, z = sims y, from the anchor
        assertTrue(abs(c.positions[6] - 0.05f) < 1e-5f && abs(c.positions[7]) < 1e-6f && abs(c.positions[8] + 0.02f) < 1e-5f)
        assertEquals(listOf("Pelvis"), c.bones) // b__Penis_Base folded into the attach bone
        for (v in 0 until c.vertexCount) assertEquals(255, (0..3).sumOf { c.boneWeights!![v * 4 + it].toInt() and 0xFF })
        assertTrue(abs(c.defaultScale - 1.4407f / 0.9f) < 1e-4f)
        assertContentEquals(m.indices, c.indices) // a rotation keeps the winding
    }

    @Test
    fun partFileRoundTrips() {
        val c = PartMesh.convert(PartMesh.extract(twoPieces())!!, AttachSpec.default(), "ABCD", "ON", "Large Cut Low", RgbaImage(4, 8, ByteArray(128)))
        val back = PartFile.read(PartFile.write(c))
        assertEquals("ABCD", back.id); assertEquals("ON", back.state); assertEquals("Large Cut Low", back.variant); assertEquals("Pelvis", back.attachBone)
        assertContentEquals(c.positions, back.positions); assertContentEquals(c.uvs, back.uvs); assertContentEquals(c.indices, back.indices)
        assertContentEquals(c.boneWeights, back.boneWeights); assertEquals(4, back.textureWidth); assertEquals(8, back.textureHeight)
        val bad = PartFile.write(c).also { it[16] = 99 } // index count beyond the data
        assertFailsWith<BodyPartFormatException> { PartFile.read(bad) }
    }

    @Test
    fun profileAxesAndBonesAreRead() {
        val assets = System.getProperty("wifecraft.assets") ?: return
        val spec = AttachSpec.fromProfile(File(assets, "sims_profile.json").readText())
        val d = AttachSpec.default()
        for (w in 0..2) assertEquals(d.axes[w], spec.axes[w])
        assertEquals("Pelvis", spec.boneMap[SimsHash.fnv32(pelvis)])
        assertEquals("L_Thigh", spec.boneMap[SimsHash.fnv32("b__L_ThighTwist__")])
        assertEquals(1f, spec.handedness)
    }
}

class PartPackageTest {
    private val tuning = """<?xml version="1.0" encoding="utf-8"?>
<I c="WickedWhimsCASPartsPackage" i="snippet" n="demo">
  <L n="cas_parts_list">
    <!-- SOFT -->
    <T>
      <T n="cas_part_raw_display_name">Demo Small Uncut - Scrotum High</T>
      <T n="cas_part_type">PENIS_SOFT_MALE</T>
      <T n="cas_part_id">17</T>
    </T>
    <T>
      <T n="cas_part_raw_display_name">Demo Small Uncut - Scrotum High</T>
      <T n="cas_part_type">PENIS_HARD_MALE</T>
      <T n="cas_part_id">18446744073709551615</T>
    </T>
  </L>
</I>"""

    @Test
    fun tuningAndNamesGiveStatesAndVariants() {
        val t = PartPackage.parseTuning(tuning)
        assertEquals("PENIS_SOFT_MALE", t[17L]!!.second)
        assertEquals(PartState.ON, PartPackage.stateFromType(t[-1L]!!.second))
        assertEquals(PartState.DEFAULT, PartPackage.stateFromName("x_ymBottom_Nude_SimplePenisDefault_Medium_High_Uncut_2023"))
        assertEquals(PartState.ON, PartPackage.stateFromName("x_Large_Hard_Cut_Low_2023_CAS"))
        assertEquals(PartState.OTHER, PartPackage.stateFromName("x_Body"))
        val v = PartVariant.parse("SimplePenis Medium Retracted - Scrotum Low")
        assertEquals("Medium", v.size); assertEquals("Retracted", v.cut); assertEquals("Low", v.placement)
        assertEquals("Cut", PartVariant.parse("a_Large_Hard_Cut_High").cut)
    }

    private fun demoPackage(): ByteArray {
        val verts = listOf(
            Vtx(-0.2f, 0.3f, 0f, 0.1f, 0.1f, intArrayOf(2, 0, 0, 0)), Vtx(-0.1f, 0.3f, 0f, 0.2f, 0.1f, intArrayOf(2, 0, 0, 0)), Vtx(-0.1f, 0.8f, 0f, 0.2f, 0.2f, intArrayOf(2, 0, 0, 0)),
            Vtx(0f, 0.9f, 0.10f, 0.5f, 0.5f), Vtx(0.02f, 0.9f, 0.12f, 0.75f, 0.5f, intArrayOf(1, 0, 0, 0)), Vtx(0f, 0.88f, 0.15f, 0.75f, 0.75f, intArrayOf(1, 0, 0, 0)),
        )
        val geom = BodyPartFixtures.geom(verts, listOf(0, 1, 2, 3, 4, 5), listOf("b__Pelvis__", "b__Penis_Base", "b__L_Thigh__"))
        val gk = ResourceKey(Geom.TYPE, 0x8006DFA8.toInt(), 0x5000L)
        val tk = ResourceKey(SimsTexture.TYPE_RLE, 0, 0x6000L)
        val grey = BodyPartFixtures.dxt5(255, 255, IntArray(16), 0x8410, 0x8410, IntArray(16))
        val tex = BodyPartFixtures.rle2(8, 8, List(4) { 1 to grey })
        val tuned = BodyPartFixtures.casp("demo_ymBottom_Small_Soft_Uncut_High_202310242209262309", listOf(gk), tk)
        val copy = BodyPartFixtures.casp("demo_ymBottom_Small_Soft_Uncut_High_202311181538556590_CAS", listOf(gk), tk)
        val overlay = BodyPartFixtures.casp("demo_Overlay", emptyList(), tk, bodyType = 99)
        return SimsFixtures.dbpf(
            SimsFixtures.Res(Casp.TYPE, 17L, tuned), SimsFixtures.Res(Casp.TYPE, 99L, copy), SimsFixtures.Res(Casp.TYPE, 100L, overlay),
            SimsFixtures.Res(Geom.TYPE, gk.instance, geom), SimsFixtures.Res(SimsTexture.TYPE_RLE, tk.instance, tex),
            SimsFixtures.Res(PartPackage.TUNING, 1L, tuning.toByteArray()),
        )
    }

    @Test
    fun packageGivesOnePartWithAliasStateTextureAndExports() {
        val imp = PartPackage.fromPackages("Demo Set!", listOf("demo.package" to demoPackage()))
        assertEquals(1, imp.parts.size, imp.problems.joinToString())
        val p = imp.parts[0]
        assertEquals(PartState.DEFAULT, p.state)
        assertEquals("Demo Small Uncut - Scrotum High", p.name)
        assertEquals("Small Uncut High", p.variant.label)
        assertEquals(1, p.aliases.size)
        assertTrue(p.extracted); assertEquals(3, p.mesh!!.vertexCount); assertEquals(6, p.fullMesh!!.vertexCount)
        assertEquals(1, imp.overlays.size)
        val d = assertNotNull(p.diffuse()); assertEquals(8, d.width)

        val root = Files.createTempDirectory("bodyparts").toFile()
        try {
            val ex = PartPackage.export(imp, AttachSpec.default(), root)
            assertEquals(File(root.canonicalFile, "Demo_Set"), ex.dir)
            val lines = File(ex.dir, "parts.jsonl").readLines()
            assertEquals(1, lines.size); assertTrue(lines[0].contains("\"state\":\"DEFAULT\""), lines[0])
            val back = PartFile.read(File(ex.dir, "0000000000000011.wcbp").readBytes())
            assertEquals(3, back.vertexCount)
            // the texture was cropped to the UVs (0.5..0.75 of 8 px, padded, multiple of 4) and the UVs moved with it
            assertTrue(back.textureWidth in 4..8 && back.textureWidth % 4 == 0)
            assertTrue(back.uvs!!.all { it in 0f..1f })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun zipReadsPackagesAndSkipsUnsafeNames() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for (n in listOf("mod/1 - Required/a.package", "../evil.package", "readme.txt")) {
                z.putNextEntry(ZipEntry(n)); z.write(demoPackage()); z.closeEntry()
            }
        }
        val f = Files.createTempFile("parts", ".zip").toFile()
        try {
            f.writeBytes(bos.toByteArray())
            val imp = PartPackage.open(f)
            assertEquals(listOf("mod/1 - Required/a.package"), imp.packages)
            assertTrue(imp.problems.any { it.contains("unsafe") })
            assertEquals(1, imp.parts.size)
            assertFailsWith<BodyPartFormatException> { PartPackage.open(f, limits = ImportLimits(maxPackageBytes = 100)) }
        } finally { f.delete() }
    }

    @Test
    fun safeNamesStayInsideTheFolder() {
        assertEquals("a_b", PartPackage.safeName("../a/b"))
        assertEquals("set", PartPackage.safeName("..."))
    }
}
