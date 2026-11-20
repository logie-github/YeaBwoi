package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.bodyparts.BodyPartFixtures.Vtx
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.sims.SimsFixtures
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Eve converter (bodyparts/EveConvert) on SYNTHETIC tiny packages made here (no bytes of the user's mod), the .weve file,
 * the Sims-to-Warcraft weight names (assets/sims_profile.json), the rule and the colour maths. The real packages run only
 * when EVE_PACKAGES (a folder of them, or a list separated by ':') is set, writing to EVE_OUT.
 */
class EveConvertTest {
    private val profile = File("../android/app/src/main/assets/sims_profile.json").readText()

    private fun tmp(): File = Files.createTempDirectory("eve").toFile()

    // a ring of `n` vertices at height y (Sims axes), radius r about (cx, cz), with UVs along u, v
    private fun ring(y: Float, r: Float, cx: Float, n: Int, v: Float, bones: IntArray, w: IntArray) =
        List(n) { i -> val a = 2 * Math.PI * i / n; Vtx(cx + r * Math.cos(a).toFloat(), y, r * Math.sin(a).toFloat(), 0.1f + 0.8f * i / n, v, bones, w) }

    // an open tube between two rings (quads as triangles): its ends are the open rings
    private fun tube(lo: Float, hi: Float, r: Float, cx: Float, vLo: Float, vHi: Float, bLo: IntArray, bHi: IntArray, n: Int = 8): Pair<List<Vtx>, List<Int>> {
        val one = intArrayOf(255, 0, 0, 0)
        val verts = ring(lo, r, cx, n, vLo, bLo, one) + ring(hi, r, cx, n, vHi, bHi, one)
        val tris = ArrayList<Int>()
        for (i in 0 until n) { val j = (i + 1) % n; tris += listOf(i, j, n + i, j, n + j, n + i) }
        return verts to tris
    }

    private fun opaque(c: Int) = BodyPartFixtures.dxt5(255, 255, IntArray(16), c, c, IntArray(16))

    internal fun packages(dir: File): List<File> {   // (EveStoreTest uses them too)
        // the upper body: a tube from the waist (1.0) to the neck (1.6), weighted to Spine2 below, the neck at the top,
        // and a twist bone and a bone the rig doesn't know on its lower ring
        val (tv, tt) = tube(1.0f, 1.6f, 0.12f, 0f, 0.55f, 0.32f, intArrayOf(0, 0, 0, 0), intArrayOf(1, 0, 0, 0))
        val top = BodyPartFixtures.geom(tv.mapIndexed { i, v -> if (i == 2) Vtx(v.x, v.y, v.z, v.u, v.v, intArrayOf(2, 3, 0, 0), intArrayOf(128, 127, 0, 0)) else v }, tt,
            listOf("b__Spine2__", "b__Neck__", "b__L_ShoulderTwist__", "b__WickedWhims_Extra__"))
        // the lower body: two legs, each open at the ankle (0.2 m) and the thigh's top
        val (lv, lt) = tube(0.2f, 0.95f, 0.06f, 0.1f, 0.78f, 0.6f, intArrayOf(1, 0, 0, 0), intArrayOf(0, 0, 0, 0))
        val (rv, rt) = tube(0.2f, 0.95f, 0.06f, -0.1f, 0.78f, 0.6f, intArrayOf(2, 0, 0, 0), intArrayOf(0, 0, 0, 0))
        val bottom = BodyPartFixtures.geom(lv + rv, lt + rt.map { it + lv.size }, listOf("b__Pelvis__", "b__L_Calf__", "b__R_Calf__"))
        val geomTop = ResourceKey(Geom.TYPE, 0, 0x11); val geomBot = ResourceKey(Geom.TYPE, 0, 0x12)
        val body = File(dir, "SMSims_eve10.2TOP_WW.package")
        body.writeBytes(SimsFixtures.dbpf(
            SimsFixtures.Res(Casp.TYPE, 1, BodyPartFixtures.casp("eveV10TOP_WW", listOf(geomTop), null, bodyType = 6, ageGender = 0x2078)),
            SimsFixtures.Res(Geom.TYPE, 0x11, top)))
        val bot = File(dir, "SMSims_eve10.2BOTTOM_WWanimated.package")
        bot.writeBytes(SimsFixtures.dbpf(
            SimsFixtures.Res(Casp.TYPE, 2, BodyPartFixtures.casp("eveV10BOTTOM_WW_animated", listOf(geomBot), null, bodyType = 7, ageGender = 0x2078)),
            SimsFixtures.Res(Geom.TYPE, 0x12, bottom)))
        // the skins: an 8 x 16 grey (neutral) and a warm tone, both opaque; 2 x 4 blocks
        val grey = (22 shl 11) or (45 shl 5) or 22
        val warm = (28 shl 11) or (40 shl 5) or 16
        val skinKey = ResourceKey(SimsTexture.TYPE_RLE, 0, 0x21); val toneKey = ResourceKey(SimsTexture.TYPE_RLE, 0, 0x22)
        val skins = File(dir, "skins.package")
        skins.writeBytes(SimsFixtures.dbpf(
            SimsFixtures.Res(Casp.TYPE, 3, BodyPartFixtures.casp("skinDetail_grey", emptyList(), skinKey, bodyType = 38)),
            SimsFixtures.Res(SimsTexture.TYPE_RLE, 0x21, BodyPartFixtures.rle2(8, 16, List(8) { 2 to opaque(grey) })),
            SimsFixtures.Res(Casp.TYPE, 4, BodyPartFixtures.casp("skinDetail_warm", emptyList(), toneKey, bodyType = 40)),
            SimsFixtures.Res(SimsTexture.TYPE_RLE, 0x22, BodyPartFixtures.rle2(8, 16, List(8) { 2 to opaque(warm) }))))
        // an overlay in a zip: one block painted (the upper left quarter of the body's rows), the rest transparent
        val ovKey = ResourceKey(SimsTexture.TYPE_RLE, 0, 0x31)
        val ovPkg = SimsFixtures.dbpf(
            SimsFixtures.Res(Casp.TYPE, 5, BodyPartFixtures.casp("overlay_shade1", emptyList(), ovKey, bodyType = 72)),
            SimsFixtures.Res(SimsTexture.TYPE_RLE, 0x31, BodyPartFixtures.rle2(8, 16, List(8) { i -> if (i == 2) 1 to opaque(warm) else 0 to ByteArray(16) })))
        val zip = File(dir, "o.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("set/SMSims-juicyboobsoverlay-acne.package")); z.write(ovPkg); z.closeEntry() }
        return listOf(body, bot, skins, zip)
    }

    @Test
    fun convertsSyntheticPackages() {
        val dir = tmp(); val out = File(dir, "eve")
        val progress = ArrayList<String>()
        val r = EveConvert.convert(packages(dir), out, profile) { _, _, what -> progress += what }
        val ix = EveSetIndex.parse(File(out, "set.json").readText())
        assertEquals(listOf("animated"), ix.meshes.keys.toList())
        val skin = assertNotNull(ix.skin)
        assertEquals("skin_neutral.png", skin.file)
        assertEquals(1, ix.tones.size, "the warm skin is a tone")
        assertEquals(listOf("acne"), ix.overlays.map { it.slot })
        val shade = ix.overlays[0].shades.single()
        assertTrue(shade.rect[2] in 1..EveConvert.WIDTH && shade.rect[3] > 0, "overlay cropped to what it paints: ${shade.rect.toList()}")
        assertTrue(File(out, shade.file).isFile && File(out, ix.tones[0].file).isFile)
        assertEquals(EveConvert.WIDTH, ix.width)
        // the crop: rows 0.32..0.78 padded to the 1/512 grid
        assertTrue(ix.cropV0 <= 0.32f - 4f / 2048f && ix.cropV0 > 0.30f && ix.cropV1 >= 0.78f && ix.cropV1 < 0.80f, "crop ${ix.cropV0}..${ix.cropV1}")
        assertEquals(Math.round((ix.cropV1 - ix.cropV0) * 2048f), ix.height)
        val m = EveFile.read(File(out, ix.meshes.getValue("animated")).readBytes())
        assertEquals(48, m.vertexCount)
        // Warcraft axes: Sims y (up) is z, the left leg (Sims +x) is +y
        assertTrue((0 until m.vertexCount).all { m.positions[it * 3 + 2] in 0.19f..1.61f })
        // the weights by Warcraft name: Spine2 is the chest, the twist the helper the engine resolves, an unknown bone the pelvis
        assertTrue(m.bones.containsAll(listOf("Chest", "Neck1", "L_UpperArmTwist", "Pelvis", "L_Calf", "R_Calf")), "${m.bones}")
        for (v in 0 until m.vertexCount) assertEquals(255, (0..3).sumOf { m.boneWeights[v * 4 + it].toInt() and 0xFF })
        // the rings: the neck's top (8 vertices) and each ankle (8); the waist and the thighs' tops are not rings
        assertEquals(8, m.rings.getValue("neck").size)
        assertEquals(8, m.rings.getValue("L_ankle").size)
        assertEquals(8, m.rings.getValue("R_ankle").size)
        assertTrue(m.rings.getValue("L_ankle").all { m.positions[it * 3 + 1] > 0 })
        // the texture coordinates point into the crop
        assertTrue((0 until m.vertexCount).all { m.uvs[it * 2 + 1] in 0f..1f })
        assertTrue(progress.isNotEmpty() && r.problems.any { "folded into the pelvis" in it }, "${r.problems}")
        assertTrue(!File(out, ".tmp").exists(), "the zip's unpacked packages are deleted")
        dir.deleteRecursively()
    }

    @Test
    fun refusesWithoutABody() {
        val dir = tmp()
        val only = packages(dir).filter { it.name == "skins.package" }
        val x = assertFailsWith<BodyPartFormatException> { EveConvert.convert(only, File(dir, "eve"), profile) }
        assertTrue("upper body" in (x.message ?: ""))
        dir.deleteRecursively()
    }

    @Test
    fun eveFileRoundTripsAndChecksItsSum() {
        val dir = tmp()
        EveConvert.convert(packages(dir), File(dir, "eve"), profile)
        val f = File(dir, "eve/eve_animated.weve")
        val b = f.readBytes()
        val m = EveFile.read(b)
        val again = EveFile.write(m)
        assertTrue(b.contentEquals(again), "written again byte for byte")
        b[b.size - 5] = (b[b.size - 5] + 1).toByte()
        assertFailsWith<BodyPartFormatException> { EveFile.read(b) }
        dir.deleteRecursively()
    }

    @Test
    fun profileMapsTheBodyBones() {
        val p = EveProfile.profileBones(profile)
        assertEquals("Spine1", EveProfile.wowBone("Spine0", p))
        assertEquals("Chest", EveProfile.wowBone("Spine2", p))
        assertEquals("Neck1", EveProfile.wowBone("Neck", p))
        assertEquals("L_Middle1", EveProfile.wowBone("L_Mid0", p))
        assertEquals("L_Middle2", EveProfile.wowBone("L_Mid2", p))
        assertEquals("R_ForearmTwist", EveProfile.wowBone("R_ForearmTwist", p))
        assertEquals("L_ElbowHelper", EveProfile.wowBone("L_Elbow", p))
        assertEquals("L_Thigh", EveProfile.wowBone("L_ThighTwist", p))
        assertEquals("L_Breast", EveProfile.wowBone("CAS_L_Breast", p))
        assertEquals("Pelvis", EveProfile.wowBone("L_Butt", p))
        assertEquals("Pelvis", EveProfile.wowBone("ROOT_bind", p))
        assertEquals("Pelvis", EveProfile.wowBone("SomethingElse", p))
        // the joints are the left side, in Warcraft axes: the shoulder left (+y) and above the hip
        val j = EveProfile.JOINTS
        assertTrue(j.getValue("shoulder")[1] > 0 && j.getValue("shoulder")[2] > j.getValue("hip")[2] && j.getValue("ankle")[2] < j.getValue("knee")[2])
    }

    @Test
    fun namesVariantsAndSlots() {
        assertEquals("plain", EveConvert.variantOf("SMSims_SMSims_eveV10BOTTOM_WW_nonanimated", "SMSims_eve10.2BOTTOM_WW.package"))
        assertEquals("animated", EveConvert.variantOf("SMSims_eveV10BOTTOM_WW_animated", "SMSims_eve10.2BOTTOM_WWanimated.package"))
        assertEquals("fairy", EveConvert.variantOf("SMSims_SMSims_eveV10BOTTOM_WW_202509051716132525", "SMSims_eve10.2BOTTOM_WWfairy.package"))
        assertEquals("acne", EveConvert.slotOf("SMSims-juicyboobsoverlay-acne.package"))
        assertEquals("lipmole", EveConvert.slotOf("x/SMSims-juicyboobsoverlay-lipmole.package"))
    }

    @Test
    fun ruleAndColours() {
        val bare = Appearance(1, Sex.FEMALE, gear = false)
        assertTrue(EveRule.applies(bare, EveOptions(on = true)))
        assertTrue(!EveRule.applies(bare, EveOptions(on = false)))
        assertTrue(!EveRule.applies(Appearance(1, Sex.MALE, gear = false), EveOptions(on = true)))
        assertTrue(!EveRule.applies(Appearance(1, Sex.FEMALE, clothing = mapOf(5 to 100)), EveOptions(on = true)), "a chest covers her")
        assertTrue(!EveRule.applies(Appearance(1, Sex.FEMALE, clothing = mapOf(10 to 100)), EveOptions(on = true)), "gloves: Eve's hands would cover them")
        assertTrue(EveRule.applies(Appearance(1, Sex.FEMALE, clothing = mapOf(8 to 100, 1 to 5)), EveOptions(on = true)), "boots and a helm leave her bare")
        val o = EveOptions(true, "fairy", 3, 1, -1, 0.4f)
        assertEquals(o, EveOptions.fromJson(o.toJson()))
        assertEquals(EveOptions.DEFAULT_OPACITY, EveOptions.fromJson(null).opacity)
        // the nearest swatch by Lab: a pale skin picks the pale one, a dark the dark one
        val sw = listOf(0x2A201D, 0xEDCFC0, 0xB4765E)
        assertEquals(1, EveColour.nearest(0xE8C9B8, sw))
        assertEquals(0, EveColour.nearest(0x30241F, sw))
        assertTrue(EveColour.distance(0xFFFFFF, 0xFFFFFF) < 1e-9)
    }

    /** The user's own packages, when given (never committed): EVE_PACKAGES, written to EVE_OUT. */
    @Test
    fun convertsRealPackages() {
        val spec = System.getenv("EVE_PACKAGES") ?: return
        val sources = spec.split(':').map(::File).filter { it.exists() }
        if (sources.isEmpty()) return
        val out = File(System.getenv("EVE_OUT") ?: File(System.getProperty("java.io.tmpdir"), "wifecraft-eve").path)
        val t0 = System.nanoTime()
        val r = EveConvert.convert(sources, out, profile)
        val s = (System.nanoTime() - t0) / 1e9
        val ix = r.index
        val rep = buildString {
            append("converted in %.1f s, %d textures decoded, peak heap %.0f MB\n".format(s, r.decoded, r.peakHeapBytes / 1048576.0))
            append("meshes ${ix.meshes}\nskin ${ix.skin?.id} mean %06X neck %06X\n".format(ix.skin?.mean ?: 0, ix.skin?.neck ?: 0))
            append("tones ${ix.tones.size}: " + ix.tones.joinToString { "%06X".format(it.mean) } + "\n")
            for (sl in ix.overlays) append("overlay ${sl.slot}: ${sl.shades.size} shades, rect ${sl.shades.firstOrNull()?.rect?.toList()}\n")
            ix.notes.forEach { append("note: $it\n") }
            r.problems.forEach { append("problem: $it\n") }
        }
        File(out, "report.txt").writeText(rep)
        println(rep)
        assertTrue(ix.meshes.isNotEmpty() && ix.skin != null)
    }
}
