package com.logie.wifecraft.bodyparts

/**
 * One Sims 4 GEOM mesh, decoded. Arrays are per vertex: [positions] x,y,z (Sims model space: y up, the sim faces +z,
 * its left is +x, metres, feet at y = 0), [normals] x,y,z, [uvs] u,v of the first UV set (v as stored: 0 at the top of
 * the texture), [boneIndices] four indices into [boneHashes] (unsigned bytes), [weights] four weights (0..1, summing to
 * about 1), [vertexIds] the morph vertex ids (BGEO/DMap address vertices by them). [indices] are triangles.
 * [boneHashes] are FNV-32 hashes of the rig bone names (SimsHash.fnv32: "b__Pelvis__").
 */
class SimsMesh(
    val version: Int,
    val shaderHash: Int,
    val vertexCount: Int,
    val positions: FloatArray,
    val normals: FloatArray?,
    val uvs: FloatArray?,
    val uvSets: Int,
    val tangents: FloatArray?,
    val boneIndices: ByteArray?,
    val weights: FloatArray?,
    val vertexIds: IntArray?,
    val indices: IntArray,
    val boneHashes: IntArray,
    val states: List<GeometryState>,
    val references: List<ResourceKey>,
    /** Vertex elements this reader skipped (usage code and size), and anything else worth knowing; empty when all read. */
    val notes: List<String>,
    /** The second UV set's u,v per vertex (null: the mesh has one set). Read for any mesh that has one; no body is drawn from it now. */
    val uvs2: FloatArray? = null,
) {
    val triangleCount: Int get() = indices.size / 3

    /** min x,y,z then max x,y,z of [positions]. */
    fun bounds(): FloatArray {
        val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) for (a in 0..2) {
            val v = positions[i * 3 + a]
            if (v < out[a]) out[a] = v
            if (v > out[3 + a]) out[3 + a] = v
        }
        return out
    }

    /** The bone (index into [boneHashes]) carrying most of vertex [v]'s weight, -1 without bone data. */
    fun dominantBone(v: Int): Int {
        val bi = boneIndices ?: return -1
        val w = weights ?: return -1
        var best = -1
        var bw = 0f
        for (k in 0..3) if (w[v * 4 + k] > bw) { bw = w[v * 4 + k]; best = bi[v * 4 + k].toInt() and 0xFF }
        return best
    }
}

/** A GEOM v15+ geometry state: a named range of the index list (state hash, start index, min vertex, vertex count, triangles). */
class GeometryState(val state: Int, val startIndex: Int, val minVertex: Int, val vertexCount: Int, val primitiveCount: Int)

/**
 * Reads GEOM (0x015A1849): an RCOL wrapper (u32 version, u32 public chunks, u32 unused, u32 external count, u32 internal
 * count, the internal then external keys as instance u64, type u32, group u32, then offset u32 and size u32 per internal
 * chunk) around one GEOM chunk:
 *
 *  "GEOM", u32 version (5, 12, 13, 14, 15), u32 key-table offset (from after this field), u32 key-table size,
 *  u32 shader hash (when not 0: u32 size and that many bytes of MTNF material), u32 merge group, u32 sort order,
 *  u32 vertex count, u32 element count, per element u32 usage, u32 data type, u8 size; then the vertices, each element
 *  in that order. Usages: 1 position (3 f32), 2 normal (3 f32), 3 UV (2 f32; repeated per set), 4 bone indices (4 u8),
 *  5 weights (4 u8 / 255, or 4 f32 in version 5), 6 tangent (3 f32), 7 tag colour (u32), 10 vertex id (u32).
 *  Faces: u32 sub-mesh count (1), u8 bytes per index (2), u32 index count, the indices.
 *  Version 5: u32 skin-controller index. Version 12+: UV stitches (u32 count; each i32 vertex, i32 n, n x 2 f32),
 *  version 13+: seam stitches (u32 count; each u32 + u16), slot-ray intersections (u32 count; each 62 bytes plus a u32
 *  pivot bone hash from version 14, a u8 before). Then u32 bone count and the bone hashes; version 15+: u32 count of
 *  geometry states, 20 bytes each. The key table (u32 count, type u32, group u32, instance u64 each) closes the chunk.
 *
 * Sources: s4pi's MeshChunks/GEOM.cs and CmarNYC's TS4SimRipper GEOM.cs (open source), checked on a CAS mod's meshes.
 */
object Geom {
    const val TYPE = 0x015A1849

    /** The largest vertex or index count accepted (a body part has thousands; this stops a bad count from eating memory). */
    const val MAX_ELEMENTS = 2_000_000

    fun parse(resource: ByteArray, key: String = "GEOM"): SimsMesh {
        val what = "mesh $key"
        val start = chunkStart(resource, what)
        return parseChunk(Bin(resource, what, start), start)
    }

    private fun chunkStart(b: ByteArray, what: String): Int {
        if (isMagic(b, 0)) return 0
        val r = Bin(b, what)
        r.i32() // RCOL version
        r.i32() // public chunks
        r.i32() // unused
        val ext = r.i32()
        val internal = r.i32()
        if (internal !in 1..64 || ext !in 0..4096) r.fail("is not a GEOM (no GEOM tag and an RCOL header claiming $internal chunks)")
        r.skip(16 * (internal + ext))
        val off = r.i32()
        r.i32() // size
        if (off < 0 || off + 4 > b.size || !isMagic(b, off)) r.fail("has no GEOM chunk where its RCOL header points (byte $off)")
        return off
    }

    private fun isMagic(b: ByteArray, at: Int) =
        at >= 0 && at + 4 <= b.size && b[at] == 'G'.code.toByte() && b[at + 1] == 'E'.code.toByte() && b[at + 2] == 'O'.code.toByte() && b[at + 3] == 'M'.code.toByte()

    private fun parseChunk(r: Bin, start: Int): SimsMesh {
        r.skip(4)
        val version = r.i32()
        if (version != 5 && version !in 12..15) r.fail("is GEOM version $version; versions 5 and 12 to 15 are read")
        val tgiOffset = r.i32()
        val tgiPos = r.p + tgiOffset.toLong()
        r.i32() // key-table size
        val shader = r.i32()
        if (shader != 0) {
            val mtnf = r.i32()
            if (mtnf < 0) r.fail("has a material block of $mtnf bytes")
            r.skip(mtnf)
        }
        r.i32() // merge group
        r.i32() // sort order
        val n = r.i32()
        if (n < 0 || n > MAX_ELEMENTS) r.fail("claims $n vertices")
        val fc = r.i32()
        if (fc !in 1..32) r.fail("claims $fc vertex elements")
        val usage = IntArray(fc)
        val dataType = IntArray(fc)
        val size = IntArray(fc)
        for (i in 0 until fc) { usage[i] = r.i32(); dataType[i] = r.i32(); size[i] = r.u8() }
        val stride = size.sum()
        if (n.toLong() * stride > r.left) r.fail("claims $n vertices of $stride bytes, more than it holds")

        val notes = ArrayList<String>()
        val uvSets = (0 until fc).count { usage[it] == 3 && size[it] == 8 }
        val pos = FloatArray(n * 3)
        var hasPos = false
        val nrm = if ((0 until fc).any { usage[it] == 2 && size[it] == 12 }) FloatArray(n * 3) else null
        val uv = if (uvSets > 0) FloatArray(n * 2) else null
        val uv1 = uvSets > 1
        val uvb = if (uv1) FloatArray(n * 2) else null
        val tan = if ((0 until fc).any { usage[it] == 6 && size[it] == 12 }) FloatArray(n * 3) else null
        val bones = if ((0 until fc).any { usage[it] == 4 && size[it] == 4 }) ByteArray(n * 4) else null
        val wts = if ((0 until fc).any { usage[it] == 5 && (size[it] == 4 || size[it] == 16) }) FloatArray(n * 4) else null
        val ids = if ((0 until fc).any { usage[it] == 10 && size[it] == 4 }) IntArray(n) else null
        for (i in 0 until fc) {
            val known = when (usage[i]) {
                1 -> size[i] == 12
                2, 6 -> size[i] == 12
                3 -> size[i] == 8
                4, 7, 10 -> size[i] == 4
                5 -> size[i] == 4 || size[i] == 16
                else -> false
            }
            if (!known) notes += "vertex element usage ${usage[i]} type ${dataType[i]} of ${size[i]} bytes skipped"
        }
        for (v in 0 until n) {
            var uvSeen = 0
            for (e in 0 until fc) {
                val s = size[e]
                when {
                    usage[e] == 1 && s == 12 -> { for (a in 0..2) pos[v * 3 + a] = r.f32(); hasPos = true }
                    usage[e] == 2 && s == 12 && nrm != null -> for (a in 0..2) nrm[v * 3 + a] = r.f32()
                    usage[e] == 3 && s == 8 -> {
                        if (uvSeen == 0 && uv != null) { uv[v * 2] = r.f32(); uv[v * 2 + 1] = r.f32() }
                        else if (uvSeen == 1 && uvb != null) { uvb[v * 2] = r.f32(); uvb[v * 2 + 1] = r.f32() } else r.skip(8)
                        uvSeen++
                    }
                    usage[e] == 4 && s == 4 && bones != null -> for (a in 0..3) bones[v * 4 + a] = r.u8().toByte()
                    usage[e] == 5 && s == 4 && wts != null -> for (a in 0..3) wts[v * 4 + a] = r.u8() / 255f
                    usage[e] == 5 && s == 16 && wts != null -> for (a in 0..3) wts[v * 4 + a] = r.f32()
                    usage[e] == 6 && s == 12 && tan != null -> for (a in 0..2) tan[v * 3 + a] = r.f32()
                    usage[e] == 10 && s == 4 && ids != null -> ids[v] = r.i32()
                    else -> r.skip(s)
                }
            }
        }
        if (!hasPos && n > 0) r.fail("has no positions")
        if (uv1) notes += "$uvSets UV sets; the first is kept"

        val subMeshes = r.i32()
        if (subMeshes != 1) r.fail("has $subMeshes index lists; one is read")
        val bpi = r.u8()
        if (bpi != 1 && bpi != 2 && bpi != 4) r.fail("has $bpi-byte indices")
        val ic = r.i32()
        if (ic < 0 || ic > MAX_ELEMENTS * 3 || ic % 3 != 0) r.fail("claims $ic indices")
        r.need(ic * bpi)
        val idx = IntArray(ic) { when (bpi) { 1 -> r.u8(); 2 -> r.u16(); else -> r.i32() } }
        for (i in idx) if (i < 0 || i >= n) r.fail("has a triangle using vertex $i of $n")

        if (version == 5) {
            r.i32()
        } else {
            val uvStitch = r.count(8, "UV stitches")
            for (i in 0 until uvStitch) { r.i32(); val c = r.count(8, "UV stitch points"); r.skip(8 * c) }
            if (version >= 13) r.skip(6 * r.count(6, "seam stitches"))
            val slots = r.count(if (version >= 14) 66 else 63, "slot-ray intersections")
            r.skip(slots * if (version >= 14) 66 else 63)
        }
        val bc = r.count(4, "bones")
        val hashes = IntArray(bc) { r.i32() }
        val states = ArrayList<GeometryState>()
        if (version >= 15) {
            val sc = r.count(20, "geometry states")
            for (i in 0 until sc) states += GeometryState(r.i32(), r.i32(), r.i32(), r.i32(), r.i32())
        }
        if (r.p.toLong() != tgiPos) notes += "data ends at byte ${r.p - start}, the key table is at ${tgiPos - start}"

        val refs = ArrayList<ResourceKey>()
        if (tgiPos in r.p.toLong()..(r.size - 4).toLong()) {
            val t = r.absolute(tgiPos.toInt())
            val c = t.i32()
            if (c in 0..(t.left / 16)) repeat(c) { val ty = t.i32(); val g = t.i32(); refs += ResourceKey(ty, g, t.u64()) }
        }

        if (bones != null && bc > 0) for (v in 0 until n) for (k in 0..3) {
            val bi = bones[v * 4 + k].toInt() and 0xFF
            if (bi >= bc && (wts == null || wts[v * 4 + k] > 0f)) r.fail("gives vertex $v bone $bi of $bc")
        }
        for (f in pos) if (!f.isFinite()) r.fail("has a non-number position")
        return SimsMesh(version, shader, n, pos, nrm, uv, uvSets, tan, bones, wts, ids, idx, hashes, states, refs, notes, uvb)
    }
}
