package com.logie.wifecraft.props

import com.logie.wifecraft.bodyparts.RgbaImage
import com.logie.wifecraft.bodyparts.SimsTexture
import com.logie.wifecraft.sims.Dbpf
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.SimsFormatException
import com.logie.wifecraft.sims.SimsHash
import com.logie.wifecraft.sims.SimsRig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A material as far as the engine draws it: a diffuse texture's key, else a flat colour (r, g, b, a). */
class SimsMaterial(val diffuse: Key?, val color: FloatArray?)

/** One mesh of an object model, in Sims axes (x left, y up, z forward; metres). Rigid: its skin is not read. */
class SimsMesh(
    val positions: FloatArray, val normals: FloatArray?, val uvs: FloatArray?, val indices: IntArray,
    val material: SimsMaterial, val boneHashes: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
}

/**
 * One object of a package: its id ([guid], the OBJD's instance: what a tuning's `prop_guids` name), its first-LOD [meshes],
 * the bone names of its own rig ([rigBones], root first; the clips that animate the object name them) and the material
 * variant it asks for ([variantHash]: the FNV-32 of `Set2-MaterialVariant`...).
 */
class SimsObject(
    val guid: Long, val modelInstance: Long, val meshes: List<SimsMesh>, val rigBones: List<String>, val variantHash: Int?,
) {
    val vertexCount: Int get() = meshes.sumOf { it.vertexCount }

    /** The box of all positions: minX, minY, minZ, maxX, maxY, maxZ. */
    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (m in meshes) {
            var i = 0
            while (i < m.positions.size) {
                for (k in 0 until 3) {
                    val v = m.positions[i + k]
                    if (v < b[k]) b[k] = v
                    if (v > b[k + 3]) b[k + 3] = v
                }
                i += 3
            }
        }
        if (b[0] > b[3]) return FloatArray(6)
        return b
    }
}

/**
 * Sims 4 object models read as plain meshes (`tools/sims_object.py` is the reference reader; the two are kept in step). The path:
 * OBJD (the object's definition: its model, rig and slot keys are found by their types in it, an instance written as its high
 * half first), MODL (its LODs as external keys), MLOD (the meshes), their VRTF (the vertex layout), VBUF and IBUF (the buffers:
 * indices may be stored as differences), MATD or MTST (the material; an MTST lists a MATD per variant, chosen by the object's
 * variant name). Positions are Short4 over 32767, UVs two signed shorts over 32767 (32767 is 1.0), normals unsigned bytes. Only what a rigid draw needs is
 * kept; nothing is run.
 */
object SimsObjects {
    const val T_OBJD = 0xC0DB5AE7.toInt()
    const val T_MODL = 0x01661233
    const val T_MLOD = 0x01D10F34
    const val T_RIG = 0x8EAF13DE.toInt()
    const val T_SLOT = 0xD3044521.toInt()
    private const val C_MATD = 0x01D0E75D
    private const val C_MTST = 0x02019972
    private const val C_VRTF = 0x01D0E723
    private const val C_VBUF = 0x01D0E6FB
    private const val C_IBUF = 0x01D0E70F
    const val P_DIFFUSE = 0x6CC0FD85
    private const val MAX_VERTICES = 400_000
    private const val MAX_TRIANGLES = 800_000

    /** The (instance, group) of the first resource key of [type] in [blob], written as instance (halves swapped), type, group; null when none. */
    fun keyIn(blob: ByteArray, type: Int): Pair<Long, Int>? {
        val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        var i = 8
        while (i + 8 <= blob.size) {
            if (b.getInt(i) == type) {
                val hi = b.getInt(i - 8).toLong() and 0xFFFFFFFFL
                val lo = b.getInt(i - 4).toLong() and 0xFFFFFFFFL
                return ((hi shl 32) or lo) to b.getInt(i + 4)
            }
            i++
        }
        return null
    }

    /** The FNV-32 of the object's material variant name ("Set2-MaterialVariant", lower-cased) when its OBJD has one. */
    fun variantHash(objd: ByteArray): Int? {
        val text = String(objd, Charsets.ISO_8859_1)
        val m = Regex("[A-Za-z0-9_]+-[Mm]aterial[Vv]ariant").find(text) ?: return null
        return SimsHash.fnv32(m.value)
    }

    /** Every object of [pkg] that has a model (one whose model cannot be read is left out, never thrown). */
    fun read(pkg: DbpfFile, problems: MutableList<String>? = null): List<SimsObject> {
        val byKey = HashMap<String, Dbpf.Entry>()
        for (e in pkg.entries) byKey["%08X:%08X:%016X".format(e.type, e.group, e.instance)] = e
        fun entry(type: Int, group: Int, instance: Long) = byKey["%08X:%08X:%016X".format(type, group, instance)]
        val models = HashMap<Long, List<RawMesh>?>()
        val failed = HashSet<Long>()
        val out = ArrayList<SimsObject>()
        for (e in pkg.entries) {
            if (e.type != T_OBJD) continue
            val objd = runCatching { pkg.read(e) }.getOrNull() ?: continue
            val (modl, modlGroup) = keyIn(objd, T_MODL) ?: continue
            val raw = models.getOrPut(modl) {
                runCatching {
                    val me = entry(T_MODL, modlGroup, modl) ?: throw SimsFormatException("the model resource is not in the package")
                    val rc = Rcol.parse(pkg.read(me), "model")
                    val lod = rc.external.firstOrNull { it.type == T_MLOD } ?: throw SimsFormatException("the model lists no LOD")
                    val le = entry(T_MLOD, lod.group, lod.instance) ?: throw SimsFormatException("the model's first LOD is not in the package")
                    meshes(Rcol.parse(pkg.read(le), "model LOD"))
                }.getOrElse { if (failed.add(modl)) problems?.add("model %016X: %s".format(modl, it.message?.take(120))); null }
            } ?: continue
            val vh = variantHash(objd)
            val meshes = raw.map { SimsMesh(it.positions, it.normals, it.uvs, it.indices, material(it, vh), it.boneHashes) }
            if (meshes.isEmpty()) continue
            val rigBones = keyIn(objd, T_RIG)?.let { (inst, grp) -> entry(T_RIG, grp, inst) }
                ?.let { runCatching { SimsRig.parse(pkg.read(it)).bones.map { b -> b.name } }.getOrNull() }.orEmpty()
            out += SimsObject(e.instance, modl, meshes, rigBones, vh)
        }
        return out
    }

    /** The diffuse texture of [m]'s material as an image (at most [maxSide] a side), or null. */
    fun texture(pkg: DbpfFile, key: Key, maxSide: Int = 512): RgbaImage? {
        val e = pkg.entries.firstOrNull { it.instance == key.instance && SimsTexture.isTexture(it.type) && (key.type == 0 || it.type == key.type) } ?: return null
        val img = runCatching { SimsTexture.decode(pkg.read(e), e.key) }.getOrNull() ?: return null
        return shrink(img, maxSide)
    }

    /** [img] halved (2 x 2 mean) until its larger side is at most [maxSide]. */
    fun shrink(img: RgbaImage, maxSide: Int): RgbaImage {
        var cur = img
        while (maxOf(cur.width, cur.height) > maxSide && cur.width >= 2 && cur.height >= 2) {
            val w = cur.width / 2
            val h = cur.height / 2
            val o = ByteArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) {
                var s = 0
                for (dy in 0..1) for (dx in 0..1) s += cur.rgba[((2 * y + dy) * cur.width + 2 * x + dx) * 4 + c].toInt() and 0xFF
                o[(y * w + x) * 4 + c] = (s / 4).toByte()
            }
            cur = RgbaImage(w, h, o)
        }
        return cur
    }

    // ---------------------------------------------------------------------------------------------- MLOD

    internal class RawMesh(
        val positions: FloatArray, val normals: FloatArray?, val uvs: FloatArray?, val indices: IntArray,
        val boneHashes: IntArray, val rcol: Rcol, val materialRef: Int,
    )

    private fun material(m: RawMesh, vhash: Int?): SimsMaterial {
        val c = m.rcol.chunk(m.materialRef) ?: return SimsMaterial(null, null)
        val matd = when (c.type) {
            C_MATD -> c
            C_MTST -> pickVariant(m.rcol, c, vhash)
            else -> null
        } ?: return SimsMaterial(null, null)
        return SimsMaterial(textureKey(matd.data, P_DIFFUSE), firstColor(matd.data))
    }

    /** The MATD of an MTST (material set) that the variant hash names, else the first listed. Entries are (reference, hash, hash). */
    private fun pickVariant(rc: Rcol, mtst: Chunk, vhash: Int?): Chunk? {
        val b = ByteBuffer.wrap(mtst.data).order(ByteOrder.LITTLE_ENDIAN)
        if (mtst.data.size < 20) return null
        val n = b.getInt(16)
        var first: Chunk? = null
        for (i in 0 until minOf(n, 64)) {
            val o = 20 + i * 12
            if (o + 12 > mtst.data.size) break
            val c = rc.chunk(b.getInt(o)) ?: continue
            if (c.type != C_MATD) continue
            if (first == null) first = c
            val ha = b.getInt(o + 4)
            val hb = b.getInt(o + 8)
            if (vhash != null && (ha == vhash || hb == vhash) && (ha == 0 || hb == 0)) return c
        }
        return first
    }

    private fun params(d: ByteArray): List<IntArray> {
        val o = indexOf(d, "MTRL").takeIf { it >= 0 } ?: indexOf(d, "MTNF").takeIf { it >= 0 } ?: return emptyList()
        if (o + 16 > d.size) return emptyList()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val cnt = b.getInt(o + 12)
        val out = ArrayList<IntArray>()
        for (i in 0 until minOf(cnt, 64).coerceAtLeast(0)) {
            val p = o + 16 + i * 16
            if (p + 16 > d.size) break
            val off = o + b.getInt(p + 12)
            out += intArrayOf(b.getInt(p), b.getInt(p + 4), b.getInt(p + 8), off)
        }
        return out
    }

    private fun indexOf(d: ByteArray, tag: String): Int {
        val t = tag.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..d.size - t.size) { for (k in t.indices) if (d[i + k] != t[k]) continue@outer; return i }
        return -1
    }

    private fun textureKey(d: ByteArray, param: Int): Key? {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        for ((h, type, _, off) in params(d).map { listOf(it[0], it[1], it[2], it[3]) }) {
            if (h == param && type == 4 && off >= 0 && off + 16 <= d.size) return Key(b.getInt(off + 8), b.getInt(off + 12), b.getLong(off))
        }
        return null
    }

    private fun firstColor(d: ByteArray): FloatArray? {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        for (p in params(d)) {
            if (p[1] == 1 && p[2] == 4 && p[3] >= 0 && p[3] + 16 <= d.size) {
                val c = FloatArray(4) { b.getFloat(p[3] + it * 4) }
                if (c.all { it.isFinite() && it in 0f..1f } && c.any { it > 0.01f }) return c
            }
        }
        return null
    }

    internal fun meshes(rc: Rcol): List<RawMesh> {
        val c = rc.chunks.firstOrNull() ?: return emptyList()
        if (c.tag != "MLOD" || c.data.size < 12) throw SimsFormatException("The model LOD has no MLOD chunk.")
        val b = ByteBuffer.wrap(c.data).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.getInt(8)
        if (count < 0 || count > 256) throw SimsFormatException("The model LOD claims $count meshes; it is damaged.")
        var p = 12
        val out = ArrayList<RawMesh>()
        for (i in 0 until count) {
            if (p + 4 > c.data.size) break
            val size = b.getInt(p)
            val q = p + 4
            if (size < 84 || q + size > c.data.size) throw SimsFormatException("A mesh of the model LOD is cut short.")
            val matRef = b.getInt(q + 4)
            val vfRef = b.getInt(q + 8)
            val vbRef = b.getInt(q + 12)
            val ibRef = b.getInt(q + 16)
            val streamOff = b.getInt(q + 24)
            val startIndex = b.getInt(q + 32)
            val nverts = b.getInt(q + 40)
            val nprims = b.getInt(q + 44)
            val nbones = b.getInt(q + 76)
            val hashes = IntArray(if (nbones in 0..255 && q + 80 + nbones * 4 <= c.data.size) nbones else 0) { b.getInt(q + 80 + it * 4) }
            p = q + size
            val vf = rc.chunk(vfRef)?.takeIf { it.type == C_VRTF }
            val vb = rc.chunk(vbRef)?.takeIf { it.type == C_VBUF }
            val ib = rc.chunk(ibRef)?.takeIf { it.type == C_IBUF }
            // the shadow stub (3 vertices) and meshes without buffers carry nothing to draw
            if (vf == null || vb == null || ib == null || nverts < 4 || nprims < 1) continue
            if (nverts > MAX_VERTICES || nprims > MAX_TRIANGLES) throw SimsFormatException("A mesh has $nverts vertices and $nprims triangles; that is too large for a prop.")
            out += readMesh(rc, vf, vb, ib, streamOff, startIndex, nverts, nprims, hashes, matRef)
        }
        return out
    }

    private fun readMesh(rc: Rcol, vf: Chunk, vb: Chunk, ib: Chunk, streamOff: Int, startIndex: Int, nverts: Int, nprims: Int, hashes: IntArray, matRef: Int): RawMesh {
        val f = ByteBuffer.wrap(vf.data).order(ByteOrder.LITTLE_ENDIAN)
        val stride = f.getInt(8)
        val ne = f.getInt(12)
        if (stride !in 8..128 || ne !in 1..16 || 20 + ne * 4 > vf.data.size) throw SimsFormatException("A vertex format is damaged.")
        // (usage, index, format, offset)
        val els = Array(ne) { intArrayOf(vf.data[20 + it * 4].toInt() and 0xFF, vf.data[21 + it * 4].toInt() and 0xFF, vf.data[22 + it * 4].toInt() and 0xFF, vf.data[23 + it * 4].toInt() and 0xFF) }
        val v = ByteBuffer.wrap(vb.data, 16, vb.data.size - 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        if (streamOff < 0 || streamOff.toLong() + nverts.toLong() * stride > v.capacity()) throw SimsFormatException("A mesh's vertices run past its vertex buffer.")
        val pos = FloatArray(nverts * 3)
        val nor = els.firstOrNull { it[0] == 1 && it[1] == 0 }?.let { FloatArray(nverts * 3) }
        val uv = els.firstOrNull { it[0] == 2 && it[1] == 0 }?.let { FloatArray(nverts * 2) }
        val posEl = els.firstOrNull { it[0] == 0 && it[1] == 0 } ?: throw SimsFormatException("A mesh has no positions.")
        val norEl = els.firstOrNull { it[0] == 1 && it[1] == 0 }
        val uvEl = els.firstOrNull { it[0] == 2 && it[1] == 0 }
        for (i in 0 until nverts) {
            val base = streamOff + i * stride
            readPosition(v, base + posEl[3], posEl[2], pos, i * 3)
            if (nor != null && norEl != null) {
                val o = base + norEl[3]
                when (norEl[2]) {
                    2, 3 -> for (k in 0 until 3) nor[i * 3 + k] = v.getFloat(o + k * 4)
                    7, 10 -> for (k in 0 until 3) nor[i * 3 + k] = v.getShort(o + k * 2) / 32767f
                    else -> for (k in 0 until 3) nor[i * 3 + k] = (v.get(o + k).toInt() and 0xFF) / 127.5f - 1f
                }
            }
            if (uv != null && uvEl != null) {
                val o = base + uvEl[3]
                when (uvEl[2]) {
                    1 -> { uv[i * 2] = v.getFloat(o); uv[i * 2 + 1] = v.getFloat(o + 4) }
                    else -> { uv[i * 2] = v.getShort(o) / 32767f; uv[i * 2 + 1] = v.getShort(o + 2) / 32767f }
                }
            }
        }
        val ibd = ib.data
        if (ibd.size < 16) throw SimsFormatException("An index buffer is cut short.")
        val iflags = ByteBuffer.wrap(ibd).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
        val ibb = ByteBuffer.wrap(ibd, 16, ibd.size - 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        val n = nprims * 3
        val width = if (iflags and 2 != 0) 4 else 2
        if (startIndex < 0 || (startIndex.toLong() + n) * width > ibb.capacity()) throw SimsFormatException("A mesh's indices run past its index buffer.")
        val idx = IntArray(n)
        var acc = 0
        // differenced indices run on through the whole buffer: a mesh that starts part way continues the sum of those before it
        val from = if (iflags and 1 != 0) 0 else startIndex
        for (k in from until startIndex + n) {
            val raw = if (width == 4) ibb.getInt(k * 4) else if (iflags and 1 != 0) ibb.getShort(k * 2).toInt() else ibb.getShort(k * 2).toInt() and 0xFFFF
            val v = if (iflags and 1 != 0) { acc += raw; acc } else raw
            if (k >= startIndex) {
                idx[k - startIndex] = v
                if (v < 0 || v >= nverts) throw SimsFormatException("A mesh's index $v is outside its $nverts vertices.")
            }
        }
        return RawMesh(pos, nor, uv, idx, hashes, rc, matRef)
    }

    private fun readPosition(v: ByteBuffer, o: Int, fmt: Int, out: FloatArray, at: Int) {
        when (fmt) {
            2, 3 -> for (k in 0 until 3) out[at + k] = v.getFloat(o + k * 4)
            else -> for (k in 0 until 3) out[at + k] = v.getShort(o + k * 2) / 32767f
        }
    }
}
