package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrath++'s body options drawn on today's models (the user's `wrathpp-body.zip`, as [ImportStore] keeps it).
 *
 * What the package holds (Blizzard's `realm/warcraft/wrathpp/tools/body_shapes/pack2.py`, `layer.py`, `refine.py`): per race
 * of 3.3.5, not models but **patches** of Wrath++'s converted looks model (`HumanFemale.m2` of the `wrathpp-looks` release):
 * a patch is the made model's 0x138-byte header and everything the made model has after the looks model's end; the header's
 * arrays (name, vertices, textures, materials, combos) all point there. So a patch alone holds the made model's whole vertex
 * array (48 bytes a vertex: position, 4 bone weights, 4 bone indices, normal, two texture coordinates):
 *  - shape 0 ("As drawn"): the looks model's own vertices, then the body layer's (geoset 1901, its skin's last section);
 *  - shapes 1 to 3 (Petite, Curvy, Voluptuous): the looks model refined (`refine.py`: its own vertices first, in order, then
 *    the split triangles' new ones), reshaped to the preset (`presets.py`), then the layer's.
 * The looks model's vertices are today's model's own, copied as they are (sl_looks.py -> m2.py `convert`: positions and
 * normals byte for byte, the body's texture coordinates u x 2): checked on the night elf woman, every one of its 19241
 * vertices is at a vertex of today's NightElfFemale (file 921844), and 96.7% of today's body (geoset 0) is at one of them.
 *
 * [morph] turns a shape into what Wifecraft's renderer applies to today's model ([com.logie.wifecraft.engine.Native.body]):
 * per looks vertex, its place as drawn, how far the shape moves it, its normal before and after, its texture coordinates
 * before and after ([STRIDE] floats). The renderer finds each of today's vertices at its looks vertex (or, between them,
 * the nearest ones within 2 cm) and moves it so. The refined vertices the shape adds have no place in today's model and are
 * left out: today's model is drawn at its own (finer) resolution.
 *
 * The body layer (Style x Density) is a design texture per style and density ([ImportStore.bodyLayer]) laid over the body's
 * front below the navel in model space: a square [Frame.span] wide, its top [Frame.top] (layer.py's SPAN 0.21 and TOP 0.16 x
 * the race's scale k, above its fold height), as `races.json` measures each race. The renderer draws it as Wrath++'s native mod
 * does, on the body's own triangles lifted off the skin.
 *
 * Pure JVM; NOT run on a phone, and not run against the user's own package (only against packages made the way pack2.py
 * makes them, in the tests).
 */
object BodyShapes {
    /** Floats per vertex of a [morph]: x y z, dx dy dz, nx ny nz (before), nx ny nz (after), u v (before), u v (after). */
    const val STRIDE = 16
    const val LAYER_GEOSET = 1901
    private const val HEADER = 0x138
    private const val VERTEX = 48
    // the made model's header arrays layer.add writes after the looks model (layer.py: NAME ... COMBINER_COMBOS)
    private val ARRAYS = intArrayOf(0x08, 0x3C, 0x50, 0x68, 0x70, 0x78, 0x80, 0x88, 0x90, 0x98, 0x130)
    private const val VERTICES = 0x3C

    /** The layer's square for a race (layer.py frame(): SPAN 0.21, TOP 0.16 times [k], above [fold]), in model units. */
    data class Frame(val k: Float, val fold: Float) {
        val span get() = 0.21f * k
        val top get() = fold + 0.16f * k
    }

    /** races.json (Wrath++'s body_shapes): each race's scale against the Human and its fold's height, by race id. */
    val FRAMES: Map<Int, Frame> = mapOf(
        1 to Frame(1.00012f, 0.99602f),   // Human
        2 to Frame(1.16461f, 1.04146f),   // Orc
        3 to Frame(0.99056f, 0.65924f),   // Dwarf
        4 to Frame(1.1622f, 1.18762f),    // NightElf
        5 to Frame(0.97399f, 1.01101f),   // Scourge
        6 to Frame(1.76219f, 1.34511f),   // Tauren
        7 to Frame(0.65404f, 0.34333f),   // Gnome
        8 to Frame(1.23869f, 1.23991f),   // Troll
        10 to Frame(0.98621f, 1.01905f),  // BloodElf
        11 to Frame(1.15249f, 1.22633f),  // Draenei
    )

    fun frame(raceId: Int): Frame? = FRAMES[raceId]

    /** A model's vertices: [count] positions, normals and first texture coordinates. */
    class Mesh(val count: Int, val pos: FloatArray, val normal: FloatArray, val uv: FloatArray)

    /**
     * The vertices of the model a [patch] makes (WrathppBody.withPatch): the header's vertex array, found in the patch's tail.
     * The tail starts at the looks model's end (16-byte aligned), with the empty design's name layer.add puts there first and
     * the made model's design texture names; the header's own arrays follow it. So the looks model itself isn't needed.
     */
    fun readPatch(patch: ByteArray): Mesh {
        if (patch.size < HEADER || String(patch, 0, 4, Charsets.ISO_8859_1) != "MD20") throw IOException("Not a body options model (no 3.3.5 header).")
        val b = ByteBuffer.wrap(patch).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(at: Long) = b.getInt(at.toInt()).toLong() and 0xFFFFFFFFL
        var first = Long.MAX_VALUE
        for (off in ARRAYS) {
            val n = u32(off.toLong()); val o = u32(off + 4L)
            if (n > 0 && o >= HEADER) first = minOf(first, o)
        }
        if (first == Long.MAX_VALUE) throw IOException("The body options model's header points nowhere.")
        // The tail starts with the empty design's name (a texture's, not one of the header's arrays), then the header's first
        // array: the start is the one a texture of the made model names its file at.
        var nul = HEADER
        while (nul < minOf(patch.size, HEADER + 512) && patch[nul] != 0.toByte()) nul++
        val candidates = listOf(first - ((nul - HEADER + 1 + 15) / 16 * 16), first)
        val texN = u32(0x50); val texO = u32(0x54)
        val at = candidates.firstOrNull { c ->
            if (c < HEADER || c % 16 != 0L) return@firstOrNull false
            val start = HEADER + (texO - c)
            texN in 1..4096 && start >= HEADER && start + texN * 16 <= patch.size &&
                (0 until texN).any { u32(start + it * 16 + 12) == c }
        } ?: throw IOException("The body options model isn't laid out as pack2.py makes it.")
        val n = b.getInt(VERTICES).toLong() and 0xFFFFFFFFL
        val start = HEADER + ((b.getInt(VERTICES + 4).toLong() and 0xFFFFFFFFL) - at)
        if (n <= 0 || start < HEADER || start + n * VERTEX > patch.size) throw IOException("The body options model's vertices aren't in it.")
        val count = n.toInt()
        val pos = FloatArray(count * 3); val normal = FloatArray(count * 3); val uv = FloatArray(count * 2)
        for (i in 0 until count) {
            val v = (start + i.toLong() * VERTEX).toInt()
            for (k in 0 until 3) { pos[i * 3 + k] = b.getFloat(v + k * 4); normal[i * 3 + k] = b.getFloat(v + 20 + k * 4) }
            uv[i * 2] = b.getFloat(v + 32); uv[i * 2 + 1] = b.getFloat(v + 36)
        }
        return Mesh(count, pos, normal, uv)
    }

    /**
     * How many of a made model's [total] vertices are the model's own (before the body layer's): the layer's section
     * (geoset [LAYER_GEOSET]) uses the vertices layer.add put after them, so its lowest model vertex; [total] without one.
     */
    fun bodyCount(skin: ByteArray, total: Int): Int {
        if (skin.size < 48 || String(skin, 0, 4, Charsets.ISO_8859_1) != "SKIN") throw IOException("Not a body options skin.")
        val b = ByteBuffer.wrap(skin).order(ByteOrder.LITTLE_ENDIAN)
        val vn = b.getInt(4); val vo = b.getInt(8); val sn = b.getInt(28); val so = b.getInt(32)
        if (vn < 0 || sn < 0 || vo + vn * 2L > skin.size || so + sn * 48L > skin.size) throw IOException("The body options skin is damaged.")
        var lowest = total
        for (s in 0 until sn) {
            val at = so + s * 48
            if (b.getShort(at).toInt() and 0xFFFF != LAYER_GEOSET) continue
            val start = (b.getShort(at + 4).toInt() and 0xFFFF)
            val count = (b.getShort(at + 6).toInt() and 0xFFFF)
            for (k in start until minOf(vn, start + count)) lowest = minOf(lowest, b.getShort(vo + k * 2).toInt() and 0xFFFF)
        }
        return lowest
    }

    /**
     * The shape's [morph]: for each of the looks model's [baseCount] vertices of [base] (shape 0), where [shape]'s vertex of the
     * same number is ([shapeCount]: the shape's own vertices, the refined ones after the looks' among them).
     */
    fun morph(base: Mesh, baseCount: Int, shape: Mesh, shapeCount: Int): FloatArray {
        if (baseCount <= 0 || baseCount > base.count) throw IOException("The body options' own model is damaged.")
        if (shapeCount < baseCount || shapeCount > shape.count) throw IOException("The shape wasn't made from this race's looks.")
        val out = FloatArray(baseCount * STRIDE)
        for (i in 0 until baseCount) {
            val o = i * STRIDE
            for (k in 0 until 3) {
                out[o + k] = base.pos[i * 3 + k]
                out[o + 3 + k] = shape.pos[i * 3 + k] - base.pos[i * 3 + k]
                out[o + 6 + k] = base.normal[i * 3 + k]
                out[o + 9 + k] = shape.normal[i * 3 + k]
            }
            out[o + 12] = base.uv[i * 2]; out[o + 13] = base.uv[i * 2 + 1]
            out[o + 14] = shape.uv[i * 2]; out[o + 15] = shape.uv[i * 2 + 1]
        }
        return out
    }

    /** How many vertices a [morph] moves (by more than [eps]). */
    fun moved(morph: FloatArray, eps: Float = 1e-6f): Int {
        var n = 0
        for (o in morph.indices step STRIDE) {
            val dx = morph[o + 3]; val dy = morph[o + 4]; val dz = morph[o + 5]
            if (dx * dx + dy * dy + dz * dz > eps * eps) n++
        }
        return n
    }

    private class Entry(val model: Int, val skin: Int)

    private fun entries(root: File, raceId: Int): Map<Int, Entry> {
        val meta = File(root, "imports/${ImportKind.BODY.key}/meta.json").takeIf { it.isFile } ?: return emptyMap()
        val models = runCatching { JSONObject(meta.readText()) }.getOrNull()?.optJSONArray("models") ?: return emptyMap()
        val out = HashMap<Int, Entry>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            if (m.optInt("race_id") == raceId) out[m.optInt("shape")] = Entry(m.optInt("model"), m.optInt("skin"))
        }
        return out
    }

    private val cache = LinkedHashMap<String, FloatArray>()

    /**
     * The [morph] of race [raceId]'s shape [shape] (1..3) from what [ImportStore] keeps under [root] (the app's files folder:
     * `imports/wrathpp-body/meta.json`, the files under `game/<id>`), or null when the package has no such shape (or none is
     * imported). Throws when the package's files are there but aren't what pack2.py makes.
     */
    fun load(root: File, raceId: Int, shape: Int): FloatArray? {
        if (shape <= 0) return null
        val e = entries(root, raceId)
        val base = e[0] ?: return null
        val made = e[shape] ?: return null
        if (base.skin == 0 || made.skin == 0) return null
        val key = "${root.path}:${base.model}:${base.skin}:${made.model}:${made.skin}"
        synchronized(cache) { cache[key]?.let { return it } }
        fun bytes(id: Int) = File(root, "game/$id").takeIf { it.isFile }?.readBytes() ?: throw IOException("The body options' file $id is missing; import the package again.")
        val b = readPatch(bytes(base.model))
        val s = readPatch(bytes(made.model))
        val m = morph(b, bodyCount(bytes(base.skin), b.count), s, bodyCount(bytes(made.skin), s.count))
        synchronized(cache) {
            cache[key] = m
            while (cache.size > 4) cache.remove(cache.keys.first())
        }
        return m
    }
}
