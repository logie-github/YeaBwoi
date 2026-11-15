package com.logie.wifecraft.creator

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The body options' shapes read from a package laid out as Wrath++'s pack2.py and layer.py make it (the user's own package
 * was not available): a looks model, each shape's patch of it (the header, then the made model's arrays after the looks
 * model's end, the empty design's name first) and skins whose last section is the layer (geoset 1901, on vertices after
 * the model's own). The package pack2.py itself makes from Wrath++'s real looks model is checked by [realPackage] when one
 * is given (engine-test's body check).
 */
class BodyShapesTest {
    private val names = MapNames(emptyMap())
    private fun tmp(): File = Files.createTempDirectory("body").toFile()
    private fun sha(d: ByteArray) = MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it) }

    /** A vertex: position, normal, texture coordinates (the bone bytes are the layer's business, left 0). */
    private data class V(val x: Float, val y: Float, val z: Float, val nx: Float = 1f, val ny: Float = 0f, val nz: Float = 0f, val u: Float = 0.1f, val v: Float = 0.2f)

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun verts(vs: List<V>): ByteArray {
        val b = le(vs.size * 48)
        for ((i, v) in vs.withIndex()) {
            val o = i * 48
            b.putFloat(o, v.x); b.putFloat(o + 4, v.y); b.putFloat(o + 8, v.z)
            b.put(o + 12, 255.toByte())
            b.putFloat(o + 20, v.nx); b.putFloat(o + 24, v.ny); b.putFloat(o + 28, v.nz)
            b.putFloat(o + 32, v.u); b.putFloat(o + 36, v.v)
        }
        return b.array()
    }

    /** The looks model: a 3.3.5 header, its name and vertices, and a length not a multiple of 16 (as a real one can be). */
    private fun looks(vs: List<V>): ByteArray {
        val name = "NightElfFemale\u0000".toByteArray()
        val body = verts(vs)
        val b = le(0x140 + body.size + 32 + 7)
        b.put(0, 'M'.code.toByte()); b.put(1, 'D'.code.toByte()); b.put(2, '2'.code.toByte()); b.put(3, '0'.code.toByte())
        b.putInt(0x08, name.size); b.putInt(0x0C, 0x140 + body.size)
        b.putInt(0x3C, vs.size); b.putInt(0x40, 0x140)
        System.arraycopy(body, 0, b.array(), 0x140, body.size)
        System.arraycopy(name, 0, b.array(), 0x140 + body.size, name.size)
        return b.array()
    }

    /**
     * What layer.add makes of [base] with [model] vertices and [layer] vertices after them, as layer.patch gives it: the
     * header, then (from the base's 16-byte aligned end) the empty design's name, the name, the vertices and the other arrays.
     */
    private fun patch(base: ByteArray, model: List<V>, layer: List<V>): ByteArray {
        val baseLen = (base.size + 15) / 16 * 16
        val blob = ByteArrayOutputStream()
        fun put(bytes: ByteArray): Int {
            while ((baseLen + blob.size()) % 16 != 0) blob.write(0)
            val at = baseLen + blob.size(); blob.write(bytes); return at
        }
        val head = le(0x138); head.put(base, 0, 0x138); val h = head.array()
        val hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val empty = "Character\\NightElf\\Female\\WppLayer_0.blp\u0000".toByteArray()
        val emptyAt = put(empty)
        hb.putInt(0x08, 15); hb.putInt(0x0C, put("NightElfFemale\u0000".toByteArray()))
        hb.putInt(0x3C, model.size + layer.size); hb.putInt(0x40, put(verts(model + layer)))
        val tex = le(32); tex.putInt(0, 6); tex.putInt(16, 11); tex.putInt(24, empty.size); tex.putInt(28, emptyAt)
        hb.putInt(0x50, 2); hb.putInt(0x54, put(tex.array()))
        for (off in intArrayOf(0x68, 0x70, 0x78, 0x80, 0x88, 0x90, 0x98, 0x130)) { hb.putInt(off, 2); hb.putInt(off + 4, put(ByteArray(4))) }
        hb.putInt(0x10, hb.getInt(0x10) or 0x8)
        return h + blob.toByteArray()
    }

    /** A skin: the model's [own] vertices in a body section, the [layer] after them in geoset 1901's (refine.write_skin's layout). */
    private fun skin(own: Int, layer: Int, layerFirst: Int): ByteArray {
        val n = own + layer
        val vertsAt = 48; val secsAt = (vertsAt + n * 2 + 15) / 16 * 16
        val b = le(secsAt + 2 * 48)
        b.put(0, 'S'.code.toByte()); b.put(1, 'K'.code.toByte()); b.put(2, 'I'.code.toByte()); b.put(3, 'N'.code.toByte())
        b.putInt(4, n); b.putInt(8, vertsAt); b.putInt(28, 2); b.putInt(32, secsAt)
        for (i in 0 until own) b.putShort(vertsAt + i * 2, i.toShort())
        for (i in 0 until layer) b.putShort(vertsAt + (own + i) * 2, (layerFirst + i).toShort())
        b.putShort(secsAt, 0); b.putShort(secsAt + 4, 0); b.putShort(secsAt + 6, own.toShort())
        b.putShort(secsAt + 48, 1901); b.putShort(secsAt + 48 + 4, own.toShort()); b.putShort(secsAt + 48 + 6, layer.toShort())
        return b.array()
    }

    private val base = listOf(V(0.1f, 0f, 1.5f), V(0.12f, 0.07f, 1.6f, u = 0.6f), V(0.12f, -0.07f, 1.6f), V(-0.1f, 0f, 1.0f), V(0f, 0f, 2.3f, 0f, 0f, 1f), V(0.05f, 0.02f, 1.1f))
    private val layer = listOf(V(0.1004f, 0f, 1.1f, u = 0.5f), V(0.1004f, 0.01f, 1.12f))

    /** A shape: the looks' vertices moved (the chest out by [chest], the seat back by it too), then [extra] refined ones. */
    private fun shaped(chest: Float, extra: Int): List<V> = base.mapIndexed { i, v ->
        when (i) { 1, 2 -> v.copy(x = v.x + chest, nx = 0.9f, nz = 0.43589f, u = v.u + 0.01f); 3 -> v.copy(x = v.x - chest); else -> v }
    } + (0 until extra).map { V(0.13f, 0f, 1.6f + it * 0.001f) }

    private fun entry(name: String, d: ByteArray) = JSONObject().put("file", name).put("sha256", sha(d)).put("size", d.size)

    /** The package, as pack2.main writes it: the list, each race's patches and skins, the designs. */
    private fun pack(shapes: List<List<V>>, shapeCountBroken: Boolean = false): ByteArray {
        val looksModel = looks(base)
        val files = LinkedHashMap<String, ByteArray>()
        val models = JSONArray()
        val p0 = patch(looksModel, base, layer); val s0 = skin(base.size, layer.size, base.size)
        files["NightElf/0.patch"] = p0; files["NightElf/0.skin"] = s0
        models.put(JSONObject().put("race", "NightElf").put("shape", 0).put("model", "Character/NightElf/Female/NightElfFemale.m2")
            .put("looks_sha256", sha(looksModel)).put("patch", entry("NightElf/0.patch", p0)).put("skin_file", entry("NightElf/0.skin", s0)))
        val refinedCount = if (shapeCountBroken) base.size - 2 else shapes[0].size
        val shared = skin(refinedCount, layer.size, refinedCount)
        files["NightElf/shapes.skin"] = shared
        for ((k, s) in shapes.withIndex()) {
            val p = patch(looksModel, if (shapeCountBroken) s.take(refinedCount) else s, layer)
            files["NightElf/${k + 1}.patch"] = p
            models.put(JSONObject().put("race", "NightElf").put("shape", k + 1).put("anims_of", "Character/NightElf/Female/NightElfFemale")
                .put("patch", entry("NightElf/${k + 1}.patch", p)).put("skin_file", entry("NightElf/shapes.skin", shared)))
        }
        val styles = JSONArray(); listOf("full_natural", "natural", "small_triangle", "heart").forEach { styles.put(it) }
        val textures = JSONArray()
        for (d in 1..4) { val t = byteArrayOf(d.toByte()); files["designs/heart_$d.blp"] = t; textures.put(entry("designs/heart_$d.blp", t).put("design", "heart").put("density", d)) }
        files["designs/none.blp"] = byteArrayOf(0); textures.put(entry("designs/none.blp", byteArrayOf(0)).put("design", "").put("density", 0))
        val list = JSONObject().put("format", 2).put("kind", "wrathpp-body").put("shapes", JSONArray(listOf("As drawn", "Petite", "Curvy", "Voluptuous")))
            .put("densities", JSONArray(listOf(25, 50, 75, 100)))
            .put("styles", JSONObject().put("alliance", styles).put("horde", styles))
            .put("races", JSONArray().put(JSONObject().put("race", "NightElf").put("race_id", 4).put("folder", "Character/NightElf/Female").put("faction", "alliance")))
            .put("models", models).put("textures", textures)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in files) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
            z.putNextEntry(ZipEntry("wrathpp-body.json")); z.write(list.toString(1).toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    /** The made model, as WrathppBody.withPatch puts a patch on the looks model. */
    private fun withPatch(base: ByteArray, patch: ByteArray): ByteArray {
        val at = (base.size + 15) / 16 * 16
        val out = ByteArray(at + patch.size - 0x138)
        System.arraycopy(base, 0, out, 0, base.size); System.arraycopy(patch, 0, out, 0, 0x138); System.arraycopy(patch, 0x138, out, at, patch.size - 0x138)
        return out
    }

    @Test fun aPatchHoldsTheMadeModelsVertices() {
        val looksModel = looks(base)
        val p = patch(looksModel, shaped(0.02f, 3), layer)
        val m = BodyShapes.readPatch(p)
        assertEquals(base.size + 3 + layer.size, m.count)
        // the same as the made model's own vertex array
        val made = withPatch(looksModel, p)
        val b = ByteBuffer.wrap(made).order(ByteOrder.LITTLE_ENDIAN)
        val ov = b.getInt(0x40)
        for (i in 0 until m.count) for (k in 0 until 3) assertEquals(b.getFloat(ov + i * 48 + k * 4), m.pos[i * 3 + k])
        assertEquals(0.12f + 0.02f, m.pos[1 * 3]); assertEquals(0.61f, m.uv[1 * 2], 1e-6f)
        assertEquals(base.size + 3, BodyShapes.bodyCount(skin(base.size + 3, layer.size, base.size + 3), m.count))
        assertEquals(10, BodyShapes.bodyCount(skin(10, 0, 10), 10))
        assertFailsWith<IOException> { BodyShapes.readPatch(looksModel.copyOf(0x100)) }
        assertFailsWith<IOException> { BodyShapes.readPatch(ByteArray(0x200)) }
    }

    @Test fun aShapeMovesTheLooksVerticesAndLeavesTheRefinedOnesOut() {
        val looksModel = looks(base)
        val b = BodyShapes.readPatch(patch(looksModel, base, layer))
        val s = BodyShapes.readPatch(patch(looksModel, shaped(0.03f, 5), layer))
        val m = BodyShapes.morph(b, base.size, s, base.size + 5)
        assertEquals(base.size * BodyShapes.STRIDE, m.size)
        assertEquals(3, BodyShapes.moved(m))
        val o = 1 * BodyShapes.STRIDE
        assertEquals(0.12f, m[o]); assertEquals(0.07f, m[o + 1]); assertEquals(1.6f, m[o + 2])
        assertEquals(0.03f, m[o + 3], 1e-6f); assertEquals(0f, m[o + 4]); assertEquals(0f, m[o + 5])
        assertEquals(1f, m[o + 6]); assertEquals(0.9f, m[o + 9]); assertEquals(0.43589f, m[o + 11])
        assertEquals(0.6f, m[o + 12]); assertEquals(0.61f, m[o + 14], 1e-6f)
        assertEquals(-0.03f, m[3 * BodyShapes.STRIDE + 3], 1e-6f)
        // a shape with fewer vertices than the looks wasn't made from them
        assertFailsWith<IOException> { BodyShapes.morph(b, base.size, s, base.size - 1) }
    }

    @Test fun shapesComeFromTheImportedPackage() {
        val root = tmp()
        val store = ImportStore(root, names)
        val zip = pack(listOf(shaped(-0.01f, 4), shaped(0.02f, 4), shaped(0.045f, 4)))
        store.importZip(ImportKind.BODY, ByteArrayInputStream(zip), "wrathpp-body.zip")
        assertNull(BodyShapes.load(root, 4, 0))
        assertNull(BodyShapes.load(root, 1, 2))           // no Human in this package
        val petite = assertNotNull(BodyShapes.load(root, 4, 1))
        val curvy = assertNotNull(BodyShapes.load(root, 4, 2))
        val voluptuous = assertNotNull(BodyShapes.load(root, 4, 3))
        val dx = { m: FloatArray -> m[1 * BodyShapes.STRIDE + 3] }
        assertEquals(-0.01f, dx(petite), 1e-6f); assertEquals(0.02f, dx(curvy), 1e-6f); assertEquals(0.045f, dx(voluptuous), 1e-6f)
        assertTrue(dx(petite) < dx(curvy) && dx(curvy) < dx(voluptuous))
        assertEquals(base.size * BodyShapes.STRIDE, curvy.size)
        // the layer's designs, as the store gives them, and the race's square
        assertNotNull(store.bodyLayer(4, 4, 2))
        val f = assertNotNull(BodyShapes.frame(4))
        assertEquals(1.1622f * 0.21f, f.span, 1e-6f); assertEquals(1.18762f + 0.16f * 1.1622f, f.top, 1e-6f)
        // a package taken off: no shapes
        store.remove(ImportKind.BODY)
        assertNull(BodyShapes.load(root, 4, 2))
    }

    @Test fun aShapeNotMadeFromTheLooksIsRefused() {
        val root = tmp()
        ImportStore(root, names).importZip(ImportKind.BODY, ByteArrayInputStream(pack(listOf(shaped(0.02f, 0)), shapeCountBroken = true)), "x")
        assertFailsWith<IOException> { BodyShapes.load(root, 4, 1) }
    }

    @Test fun everyRaceOfTheBodyOptionsHasItsSquare() {
        for (r in BodyOptions.RACES) assertNotNull(BodyShapes.frame(r), "race $r")
        assertNull(BodyShapes.frame(9))
    }

    /**
     * A package made by pack2.py's own code ([WIFECRAFT_BODY_ZIP]): imported as the app imports it, each shape's morph
     * written to [WIFECRAFT_BODY_OUT]/shape<N>.bin (raw little-endian floats) and the designs' files listed, for engine-test.
     * Skipped without them.
     */
    @Test fun realPackage() {
        val zip = System.getenv("WIFECRAFT_BODY_ZIP") ?: return
        val out = File(System.getenv("WIFECRAFT_BODY_OUT") ?: return).apply { mkdirs() }
        val race = System.getenv("WIFECRAFT_BODY_RACE")?.toInt() ?: 4
        val root = tmp()
        val store = ImportStore(root, names)
        store.importZip(ImportKind.BODY, File(zip).inputStream(), File(zip).name)
        val report = StringBuilder()
        for (shape in 1..3) {
            val m = assertNotNull(BodyShapes.load(root, race, shape), "shape $shape")
            val b = ByteBuffer.allocate(m.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            m.forEach { b.putFloat(it) }
            File(out, "shape$shape.bin").writeBytes(b.array())
            report.append("shape $shape: ${m.size / BodyShapes.STRIDE} looks vertices, ${BodyShapes.moved(m)} moved\n")
        }
        for (s in listOf(4, 1)) for (d in 1..4) {
            val id = assertNotNull(store.bodyLayer(race, s, d))
            File(root, "game/$id").copyTo(File(out, "design_${s}_$d.blp"), overwrite = true)
        }
        val curvy = BodyShapes.load(root, race, 2)!!
        assertContentEquals(curvy, BodyShapes.load(root, race, 2))
        File(out, "report.txt").writeText(report.toString())
        print(report)
    }
}
