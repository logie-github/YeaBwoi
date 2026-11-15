package com.logie.wifecraft.creator

import java.util.WeakHashMap

/**
 * One call of the renderer's `Native.addActor` (the model and its scale) and `Native.dress` (everything else), as plain
 * arrays in the order engine/Native.kt documents them. [skipped]: each layer the tables give no rectangle for, and why
 * (empty when every layer is placed).
 */
class DressCall(
    val model: Int,
    val scale: Float,
    val geosetKeys: IntArray,
    val geosetValues: IntArray,
    val layerFiles: IntArray,
    val layerMeta: IntArray,
    val tintColours: IntArray,
    val extraModels: IntArray,
    val skipped: List<String> = emptyList(),
)

/**
 * Wrath++'s body options for engine/Native.body, called after the dress: the Shape's [morph] (null: as drawn) and the race's
 * body layer square ([k] 0: no layer; the design itself is the dress's layer at LookResolver.BODY_LAYER).
 */
class BodyCall(val morph: FloatArray?, val k: Float, val fold: Float) {
    companion object { val NONE = BodyCall(null, 0f, 0f) }
}

/**
 * A [ResolvedLook] made the renderer's arrays (the glue between creator/LookResolver and engine/Native.dress), pure JVM:
 *
 * - each layer's rectangle: the material's size from ChrModelMaterial (the ChrModel's CharComponentTextureLayoutID and
 *   the layer's texture type); a section mask of -1 is the whole material, any other is the first row of that layout's
 *   CharComponentTextureSections whose `1 << SectionType` is in the mask (as wow.export's character-appearance.js does);
 *   a layer with no material or no such section is left out, as there;
 * - layerMeta per layer: [type, layer, blend, x, y, w, h, matW, matH]; tints as 0xAARRGGBB, alpha 0 for none;
 * - extra models: [attachment, model, texture]; a skinned model's own geoset (group × 100 + id) joins the geoset groups
 *   (a group the look already sets keeps its value).
 *
 * The face's bone files ([ResolvedLook.boneFiles]) are fetched with the look but the renderer has no call for them yet.
 */
object Dressing {
    const val META = 9
    const val TINTS = 2
    const val EXTRA = 3

    /** As engine/Native.tint: 0xRRGGBB to 0xFFRRGGBB, null to 0 (no tint). */
    fun tint(rgb: Int?): Int = if (rgb == null) 0 else (0xFF000000.toInt() or (rgb and 0xFFFFFF))

    /** The ChrModel's CharComponentTextureLayoutID for [a]'s race and sex (0 when the tables don't have it). */
    fun layout(c: Catalog, t: Tables, a: Appearance): Int {
        val entry = c.model(a.raceId, a.sex) ?: return 0
        return index(t).layoutOf[entry.chrModelId] ?: 0
    }

    fun call(t: Tables, layout: Int, look: ResolvedLook): DressCall {
        val ix = index(t)
        val groups = java.util.TreeMap(look.geosetGroups)
        for (e in look.extraModels) if (e.attachment == -1 && e.geoset > 0) groups.putIfAbsent(e.geoset / 100, e.geoset % 100)

        val files = ArrayList<Int>()
        val meta = ArrayList<Int>()
        val tints = ArrayList<Int>()
        val skipped = ArrayList<String>()
        for (l in look.layers) {
            val mat = ix.materials[layout to l.textureType]
            if (mat == null) { skipped += "file ${l.fileId}: layout $layout has no material for texture type ${l.textureType}"; continue }
            val (mw, mh) = mat
            val rect = if (l.region == -1) intArrayOf(0, 0, mw, mh) else ix.section(layout, l.region)
            if (rect == null) { skipped += "file ${l.fileId}: layout $layout has no section in mask ${l.region}"; continue }
            files += l.fileId
            meta += listOf(l.textureType, l.layer, l.blend, rect[0], rect[1], rect[2], rect[3], mw, mh)
            tints += tint(l.tint1); tints += tint(l.tint2)
        }
        val extra = ArrayList<Int>()
        for (e in look.extraModels) { extra += e.attachment; extra += e.model; extra += e.texture }
        return DressCall(
            look.model, look.scale,
            groups.keys.toIntArray(), groups.values.toIntArray(),
            files.toIntArray(), meta.toIntArray(), tints.toIntArray(), extra.toIntArray(), skipped,
        )
    }

    /**
     * The body options of [a] as the renderer takes them: the shape's morph from the user's package under [root] (the app's
     * files folder, where ImportStore keeps it) when a shape is picked and the package is [imported], else none; the race's
     * frame (BodyShapes.frame) with k 0 unless a Style and Density are picked. Not eligible (a man, a race Wrath++ has no
     * body options for) or nothing imported: [BodyCall.NONE]. Reads the package's files: off the main thread.
     */
    fun body(root: java.io.File, a: Appearance, imported: Boolean): BodyCall {
        if (!imported || !BodyOptions.eligible(a.raceId, a.sex)) return BodyCall.NONE
        val f = BodyShapes.frame(a.raceId)
        val morph = if (a.body.shape > 0) BodyShapes.load(root, a.raceId, a.body.shape) else null
        val k = if (f != null && a.body.style != 0 && a.body.density != 0) f.k else 0f
        return BodyCall(morph, k, f?.fold ?: 0f)
    }

    /** Every model file the look draws (the body and its extra models): their skins and animations are fetched with them. */
    fun models(look: ResolvedLook): List<Int> = (listOf(look.model) + look.extraModels.map { it.model }).filter { it != 0 }.distinct()

    // --- the tables, indexed once each -----------------------------------------------------------------------------

    private val indexes = WeakHashMap<Tables, Index>()

    private fun index(t: Tables): Index = synchronized(indexes) { indexes.getOrPut(t) { Index(t) } }

    private class Index(t: Tables) {
        val layoutOf: Map<Int, Int> = t.rows("ChrModel").associate { it.int("ID") to it.int("CharComponentTextureLayoutID") }
        /** (layout, texture type) -> (width, height); the first row of each wins. */
        val materials: Map<Pair<Int, Int>, Pair<Int, Int>> = LinkedHashMap<Pair<Int, Int>, Pair<Int, Int>>().also { m ->
            for (r in t.rows("ChrModelMaterial")) m.putIfAbsent(r.int("CharComponentTextureLayoutsID") to r.int("TextureType"), r.int("Width") to r.int("Height"))
        }
        /** layout -> its sections in the table's order: (section type, x, y, w, h). */
        private val sections: Map<Int, List<IntArray>> = t.rows("CharComponentTextureSections").groupBy({ it.int("CharComponentTextureLayoutID") }) {
            intArrayOf(it.int("SectionType"), it.int("X"), it.int("Y"), it.int("Width"), it.int("Height"))
        }

        fun section(layout: Int, mask: Int): IntArray? {
            val s = sections[layout].orEmpty().firstOrNull { it[0] in 0..31 && (mask and (1 shl it[0])) != 0 } ?: return null
            return intArrayOf(s[1], s[2], s[3], s[4])
        }
    }
}

/**
 * What a model file (M2, chunked) needs beside itself, as the renderer reads it (engine/m2.cpp): its first skin (SFID), its
 * own textures (TXID) and the animation files (AFID) of [anims] at variation 0. Pure; reads only the chunk headers.
 */
object M2Files {
    fun deps(bytes: ByteArray, anims: Set<Int>): List<Int> {
        val out = LinkedHashSet<Int>()
        var o = 0
        fun u32(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        fun u16(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "MD20") return emptyList()   // the old, unchunked form
        while (o + 8 <= bytes.size) {
            val tag = String(bytes, o, 4, Charsets.ISO_8859_1)
            val size = u32(o + 4)
            val at = o + 8
            if (size < 0 || at + size > bytes.size) break
            when (tag) {
                "SFID" -> if (size >= 4) u32(at).takeIf { it != 0 }?.let { out += it }
                "TXID" -> for (i in 0 until size / 4) u32(at + 4 * i).takeIf { it != 0 }?.let { out += it }
                "AFID" -> for (i in 0 until size / 8) {
                    val anim = u16(at + 8 * i); val variation = u16(at + 8 * i + 2); val file = u32(at + 8 * i + 4)
                    if (file != 0 && variation == 0 && anim in anims) out += file
                }
            }
            o = at + size
        }
        return out.toList()
    }
}
