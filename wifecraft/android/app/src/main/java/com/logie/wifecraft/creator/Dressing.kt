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
 * - extra models: [attachment, model, texture, geoset]; a skinned model's own geoset (group × 100 + id, 0 none) is shown
 *   on that model whatever the body's groups say (the Earthen's hair is the collection's group 0 while the body's group 0
 *   is bald), and joins the geoset groups (a group the look already sets keeps its value).
 *
 * The face's bone files ([ResolvedLook.boneFiles]) are fetched with the look but the renderer has no call for them yet.
 */
object Dressing {
    const val META = 9
    const val TINTS = 2
    const val EXTRA = 4

    /** A layer's own box (TextureLayer.rect: u0, v0, u1, v1 as fractions) in the material's pixels: x, y, w, h. */
    fun boxOf(r: List<Float>, mw: Int, mh: Int): IntArray {
        val x0 = Math.round(r[0] * mw); val y0 = Math.round(r[1] * mh)
        return intArrayOf(x0, y0, Math.round(r[2] * mw) - x0, Math.round(r[3] * mh) - y0)
    }

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
            val rect = l.rect?.let { r -> boxOf(r, mw, mh) } ?: if (l.region == -1) intArrayOf(0, 0, mw, mh) else ix.section(layout, l.region)
            if (rect == null) { skipped += "file ${l.fileId}: layout $layout has no section in mask ${l.region}"; continue }
            files += l.fileId
            meta += listOf(l.textureType, l.layer, l.blend, rect[0], rect[1], rect[2], rect[3], mw, mh)
            tints += tint(l.tint1); tints += tint(l.tint2)
        }
        val extra = ArrayList<Int>()
        for (e in look.extraModels) { extra += e.attachment; extra += e.model; extra += e.texture; extra += e.geoset }
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
 * own textures (TXID), its skeleton (SKID: today's character models keep their bones, sequences and animation files in a
 * .skel) and the animation files (AFID) of [anims] at variation 0. A skeleton file (.skel) read the same way gives its
 * parent skeleton (SKPD: Lightforged on Draenei, Mechagnome on Gnome...) and its own animation files. Pure; reads only
 * the chunk headers.
 */
object M2Files {
    /**
     * Everything [model] needs, through its skeleton and the skeleton's parent: [fetch] is called with each round's files
     * ([deps] of the model, then of each skeleton) and fetches what of them isn't here; [read] then gives a file's bytes
     * (null when it isn't here: what it would need is left out). Null [anims] includes every animation variation.
     */
    fun closure(model: Int, anims: Set<Int>?, read: (Int) -> ByteArray?, fetch: (Collection<Int>) -> Unit): Set<Int> {
        val out = LinkedHashSet<Int>()
        val todo = ArrayDeque(listOf(model))
        val seen = HashSet<Int>()
        while (todo.isNotEmpty()) {
            val f = todo.removeFirst()
            if (!seen.add(f)) continue
            val bytes = read(f) ?: continue
            val d = deps(bytes, anims)
            out += d
            fetch(d)
            // the skeletons hold what more is needed: the parent, their animation files
            todo += skeletons(bytes)
        }
        return out
    }

    /**
     * The animation files [anim] needs on [model] (the files the renderer's M2Model.ensureSequence reads): through the
     * model, its skeleton and the skeleton's parent, each file's AFID entries of [anim] at every variation, and of the
     * sequences an [anim] sequence is an alias of (flag 0x40: its keys are another's, aliasNext in the same file's
     * sequence list). [read] gives a file's bytes (null when it isn't here: what it would name is left out). An emote's
     * files are fetched with this when it is played ([anim] 0, the idle, is in [closure] already). Pure.
     */
    fun animFiles(model: Int, anim: Int, read: (Int) -> ByteArray?): Set<Int> {
        val out = LinkedHashSet<Int>()
        val todo = ArrayDeque(listOf(model))
        val seen = HashSet<Int>()
        while (todo.isNotEmpty()) {
            val f = todo.removeFirst()
            if (!seen.add(f)) continue
            val bytes = read(f) ?: continue
            out += animFilesOf(bytes, anim)
            todo += skeletons(bytes)
        }
        return out
    }

    /** One model's or skeleton's own files for [anim] (see [animFiles]). */
    fun animFilesOf(bytes: ByteArray, anim: Int): List<Int> {
        fun u16(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
        val afid = ArrayList<IntArray>()          // (anim, variation, file)
        val seqs = ArrayList<IntArray>()          // (anim, variation, flags, aliasNext)
        fun sequences(arrayAt: Int, base: Int, end: Int) {
            if (arrayAt + 8 > end) return
            val count = u32(arrayAt); val off = u32(arrayAt + 4)
            for (i in 0 until count) {
                val o = base + off + i * 64
                if (off < 0 || o < base || o + 64 > end) break
                seqs += intArrayOf(u16(o), u16(o + 2), u32(o + 12), u16(o + 62))
            }
        }
        walk(bytes) { tag, at, size, _ ->
            when (tag) {
                "AFID" -> for (i in 0 until size / 8) afid += intArrayOf(u16(at + 8 * i), u16(at + 8 * i + 2), u32(at + 8 * i + 4))
                "SKS1" -> sequences(at + 8, at, at + size)              // a skeleton's sequences: after its global loops
                "MD21" -> if (size >= 36) sequences(at + 28, at, at + size)   // a model's own (MD20's header at 28)
            }
        }
        val keys = HashSet<Long>()
        fun key(a: Int, v: Int) = a.toLong() shl 16 or v.toLong()
        for (e in afid) if (e[0] == anim) keys += key(e[0], e[1])
        for (s in seqs) {
            if (s[0] != anim) continue
            var cur = s
            var guard = 0
            while (cur[2] and 0x40 != 0 && guard++ < 16) {
                val next = seqs.getOrNull(cur[3]) ?: break
                keys += key(next[0], next[1])
                cur = next
            }
        }
        return afid.filter { it[2] != 0 && key(it[0], it[1]) in keys }.map { it[2] }.distinct()
    }

    /** The skeleton files a model or skeleton names (SKID; SKPD's parent). */
    fun skeletons(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        walk(bytes) { tag, at, size, u32 ->
            if (tag == "SKID" && size >= 4) u32(at).takeIf { it != 0 }?.let { out += it }
            if (tag == "SKPD" && size >= 12) u32(at + 8).takeIf { it != 0 }?.let { out += it }
        }
        return out
    }

    private fun walk(bytes: ByteArray, each: (tag: String, at: Int, size: Int, u32: (Int) -> Int) -> Unit) {
        fun u32(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "MD20") return
        var o = 0
        while (o + 8 <= bytes.size) {
            val size = u32(o + 4)
            val at = o + 8
            if (size < 0 || at + size > bytes.size) break
            each(String(bytes, o, 4, Charsets.ISO_8859_1), at, size, ::u32)
            o = at + size
        }
    }

    fun deps(bytes: ByteArray, anims: Set<Int>?): List<Int> {
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
                "SKID" -> if (size >= 4) u32(at).takeIf { it != 0 }?.let { out += it }
                "SKPD" -> if (size >= 12) u32(at + 8).takeIf { it != 0 }?.let { out += it }
                "SFID" -> if (size >= 4) u32(at).takeIf { it != 0 }?.let { out += it }
                "TXID" -> for (i in 0 until size / 4) u32(at + 4 * i).takeIf { it != 0 }?.let { out += it }
                "AFID" -> for (i in 0 until size / 8) {
                    val anim = u16(at + 8 * i); val variation = u16(at + 8 * i + 2); val file = u32(at + 8 * i + 4)
                    if (file != 0 && (anims == null || (variation == 0 && anim in anims))) out += file
                }
            }
            o = at + size
        }
        return out.toList()
    }
}
