package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.sims.SimsHash
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where a part goes on a Warcraft body and how Sims space becomes Warcraft's.
 *
 * - [axes]: for each Warcraft axis (x forward, y left, z up), the Sims axis it takes (0 x, 1 y, 2 z) and its sign. The
 *   profile's "axes" (assets/sims_profile.json: Sims x -> Warcraft +y, y -> +z, z -> +x) gives it.
 * - [bone]: the Warcraft bone the part hangs from ("Pelvis"); [simsBone] the Sims bone whose region marks the part's
 *   anchor (b__Pelvis__).
 * - [referenceHeight]: Warcraft model units from the ground to the reference skeleton's Root pivot (the profile's
 *   rootHeight, 1.4407 for the night elf woman); the default scale assumes the part's anchor sits at that height.
 * - [boneMap]: Sims bone hash to Warcraft bone name for skinning; any other bone of the part (its own bones: a mod's
 *   penis or scrotum bones) is folded into [bone].
 */
class AttachSpec(
    val bone: String,
    val simsBone: String,
    val axes: Array<Pair<Int, Float>>,
    val referenceHeight: Float,
    val boneMap: Map<Int, String>,
) {
    /** +1 when the axis change keeps handedness, -1 when it mirrors (then triangles are rewound). */
    val handedness: Float
        get() {
            val m = Array(3) { FloatArray(3) }
            for (w in 0..2) m[w][axes[w].first] = axes[w].second
            val d = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
            return if (d < 0) -1f else 1f
        }

    companion object {
        /** Sims bones that are not in the profile but are the body's (they fold to the bone named): the thigh twists. */
        val EXTRA_BODY = mapOf("b__L_ThighTwist__" to "L_Thigh", "b__R_ThighTwist__" to "R_Thigh", "b__ROOT__" to "Root", "b__ROOT_bind__" to "Root")

        /** Read from the retarget profile's JSON (assets/sims_profile.json): axes, rootHeight and the bone names. */
        fun fromProfile(json: String, bone: String = "Pelvis", simsBone: String = "b__Pelvis__"): AttachSpec {
            val o = JSONObject(json)
            val ax = o.getJSONObject("axes")
            val axes = arrayOfNulls<Pair<Int, Float>>(3)
            for ((i, s) in listOf("x", "y", "z").withIndex()) {
                val t = ax.getString(s).trim()
                val sign = if (t.startsWith("-")) -1f else 1f
                val w = "xyz".indexOf(t.trimStart('+', '-').lowercase())
                require(w >= 0) { "profile axis $s -> $t" }
                axes[w] = i to sign
            }
            require(axes.all { it != null }) { "profile axes are not a permutation" }
            val map = HashMap<Int, String>()
            val bones = o.getJSONArray("bones")
            for (i in 0 until bones.length()) {
                val b = bones.getJSONObject(i)
                map[SimsHash.fnv32(b.getString("sims"))] = b.getString("wow")
            }
            for ((s, w) in EXTRA_BODY) map.putIfAbsent(SimsHash.fnv32(s), w)
            val h = o.optDouble("rootHeight", 0.0).toFloat().takeIf { it > 0f } ?: 1.4407f
            return AttachSpec(bone, simsBone, axes.requireNoNulls(), h, map)
        }

        /** The profile's values written out, for when the profile isn't at hand (tests): Sims x -> +y, y -> +z, z -> +x. */
        fun default(): AttachSpec {
            val map = HashMap<Int, String>()
            for (n in listOf("Pelvis", "Spine0:Spine1", "Spine1:Spine2", "Spine2:Chest", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf", "L_Foot", "R_Foot", "L_Toe", "R_Toe")) {
                val (s, w) = if (':' in n) n.split(':').let { it[0] to it[1] } else n to n
                map[SimsHash.fnv32("b__${s}__")] = w
            }
            for ((s, w) in EXTRA_BODY) map[SimsHash.fnv32(s)] = w
            return AttachSpec("Pelvis", "b__Pelvis__", arrayOf(2 to 1f, 0 to 1f, 1 to 1f), 1.4407f, map)
        }
    }
}

/** A part ready for the engine: Warcraft axes, relative to its anchor, skinned to named Warcraft bones. See [PartFile]. */
class ConvertedPart(
    val id: String,
    val state: String,
    val variant: String,
    val attachBone: String,
    /** The anchor in Sims model space (metres, y up): where the part meets the body. */
    val anchorSims: FloatArray,
    /** Warcraft units per Sims metre by default (referenceHeight / the anchor's height); the engine should use the NPC's own Pelvis height. */
    val defaultScale: Float,
    /** Added at the attach bone's pivot, Warcraft model units; zero until a Sims rig places b__Pelvis__ (assumed). */
    val attachOffset: FloatArray,
    val positions: FloatArray,
    val normals: FloatArray?,
    val uvs: FloatArray?,
    val bones: List<String>,
    val boneIndices: ByteArray?,
    val boneWeights: ByteArray?,
    val indices: IntArray,
    val textureWidth: Int,
    val textureHeight: Int,
) {
    val vertexCount: Int get() = positions.size / 3
}

/**
 * Turns a Sims mesh into a part: [extract] keeps the pieces that belong to the part (a CAS bottom carries the whole
 * lower body: legs, hips and the add-on), [convert] moves them to Warcraft's axes around their anchor.
 */
object PartMesh {
    /** Lower-body bones of the Sims 4 adult rig: weight on these alone marks body, not the part's own pieces. */
    val SIMS_BODY_BONES: Set<Int> = listOf(
        "b__ROOT__", "b__ROOT_bind__", "b__Pelvis__", "b__Spine0__", "b__Spine1__", "b__Spine2__",
        "b__L_Thigh__", "b__R_Thigh__", "b__L_ThighTwist__", "b__R_ThighTwist__", "b__L_Calf__", "b__R_Calf__",
        "b__L_Foot__", "b__R_Foot__", "b__L_Toe__", "b__R_Toe__",
    ).map(SimsHash::fnv32).toSet()

    /**
     * The connected pieces of [mesh] (triangles sharing vertices) in which at least [minShare] of the vertices carry
     * weight on a bone outside [body]: the part's own bones. Returns null when none qualifies (a mesh without such
     * bones: then the whole mesh is the part, or it is not an add-on at all).
     */
    fun extract(mesh: SimsMesh, body: Set<Int> = SIMS_BODY_BONES, minShare: Float = 0.5f): SimsMesh? {
        val bi = mesh.boneIndices ?: return null
        val wt = mesh.weights ?: return null
        val n = mesh.vertexCount
        val own = BooleanArray(n) { v -> (0..3).any { k -> wt[v * 4 + k] > 0f && mesh.boneHashes.getOrNull(bi[v * 4 + k].toInt() and 0xFF)?.let { it !in body } == true } }
        val parent = IntArray(n) { it }
        fun root(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (t in 0 until mesh.triangleCount) for (k in 1..2) {
            val a = root(mesh.indices[t * 3]); val b = root(mesh.indices[t * 3 + k]); if (a != b) parent[a] = b
        }
        val total = HashMap<Int, Int>(); val owned = HashMap<Int, Int>()
        for (v in 0 until n) { val r = root(v); total[r] = (total[r] ?: 0) + 1; if (own[v]) owned[r] = (owned[r] ?: 0) + 1 }
        val keep = BooleanArray(n) { v -> val r = root(v); (owned[r] ?: 0) >= minShare * (total[r] ?: 1) && (owned[r] ?: 0) > 0 }
        if (keep.none { it }) return null
        return subset(mesh, keep)
    }

    /** The vertices flagged in [keep] and the triangles wholly inside them, renumbered. */
    fun subset(mesh: SimsMesh, keep: BooleanArray): SimsMesh {
        val map = IntArray(mesh.vertexCount) { -1 }
        var c = 0
        for (v in 0 until mesh.vertexCount) if (keep[v]) map[v] = c++
        val idx = ArrayList<Int>()
        for (t in 0 until mesh.triangleCount) {
            val a = map[mesh.indices[t * 3]]; val b = map[mesh.indices[t * 3 + 1]]; val d = map[mesh.indices[t * 3 + 2]]
            if (a >= 0 && b >= 0 && d >= 0) { idx += a; idx += b; idx += d }
        }
        fun f(src: FloatArray?, k: Int) = src?.let { s -> FloatArray(c * k).also { o -> for (v in 0 until mesh.vertexCount) if (map[v] >= 0) System.arraycopy(s, v * k, o, map[v] * k, k) } }
        val bones = mesh.boneIndices?.let { s -> ByteArray(c * 4).also { o -> for (v in 0 until mesh.vertexCount) if (map[v] >= 0) System.arraycopy(s, v * 4, o, map[v] * 4, 4) } }
        val ids = mesh.vertexIds?.let { s -> IntArray(c).also { o -> for (v in 0 until mesh.vertexCount) if (map[v] >= 0) o[map[v]] = s[v] } }
        return SimsMesh(mesh.version, mesh.shaderHash, c, f(mesh.positions, 3)!!, f(mesh.normals, 3), f(mesh.uvs, 2), mesh.uvSets,
            f(mesh.tangents, 3), bones, f(mesh.weights, 4), ids, idx.toIntArray(), mesh.boneHashes, emptyList(), mesh.references, mesh.notes)
    }

    /**
     * The anchor, in Sims model space: the mean of the vertices weighted to [simsBone] (where the part joins the body),
     * or, when none is, the centre of the mesh's top-back edge (highest y, lowest z: where a hanging part meets the body).
     */
    fun anchor(mesh: SimsMesh, simsBone: String): FloatArray {
        val h = SimsHash.fnv32(simsBone)
        val slot = mesh.boneHashes.indexOf(h)
        var x = 0.0; var y = 0.0; var z = 0.0; var n = 0
        if (slot >= 0 && mesh.boneIndices != null && mesh.weights != null) for (v in 0 until mesh.vertexCount) {
            if ((0..3).any { k -> (mesh.boneIndices[v * 4 + k].toInt() and 0xFF) == slot && mesh.weights[v * 4 + k] >= 0.5f }) {
                x += mesh.positions[v * 3]; y += mesh.positions[v * 3 + 1]; z += mesh.positions[v * 3 + 2]; n++
            }
        }
        if (n > 0) return floatArrayOf((x / n).toFloat(), (y / n).toFloat(), (z / n).toFloat())
        val b = mesh.bounds()
        return floatArrayOf((b[0] + b[3]) / 2, b[4], b[2])
    }

    /** [mesh] in Warcraft's axes around its anchor (Sims metres; scale with [ConvertedPart.defaultScale] or the NPC's own). */
    fun convert(mesh: SimsMesh, spec: AttachSpec, id: String, state: String, variant: String, texture: RgbaImage?): ConvertedPart {
        val a = anchor(mesh, spec.simsBone)
        val n = mesh.vertexCount
        fun axes(src: FloatArray, v: Int, out: FloatArray, sub: FloatArray?) {
            for (w in 0..2) {
                val (s, sign) = spec.axes[w]
                out[v * 3 + w] = sign * (src[v * 3 + s] - (sub?.get(s) ?: 0f))
            }
        }
        val pos = FloatArray(n * 3).also { o -> for (v in 0 until n) axes(mesh.positions, v, o, a) }
        val nrm = mesh.normals?.let { s -> FloatArray(n * 3).also { o -> for (v in 0 until n) axes(s, v, o, null) } }
        val idx = mesh.indices.copyOf()
        if (spec.handedness < 0) for (t in 0 until idx.size / 3) { val k = idx[t * 3 + 1]; idx[t * 3 + 1] = idx[t * 3 + 2]; idx[t * 3 + 2] = k }

        // skinning: Sims bones to Warcraft names, the part's own bones folded into the attach bone, four slots summing to 255
        val names = ArrayList<String>().apply { add(spec.bone) }
        var boneIdx: ByteArray? = null
        var boneW: ByteArray? = null
        if (mesh.boneIndices != null && mesh.weights != null) {
            boneIdx = ByteArray(n * 4); boneW = ByteArray(n * 4)
            for (v in 0 until n) {
                val acc = LinkedHashMap<Int, Float>()
                for (k in 0..3) {
                    val w = mesh.weights[v * 4 + k]
                    if (w <= 0f) continue
                    val hash = mesh.boneHashes.getOrNull(mesh.boneIndices[v * 4 + k].toInt() and 0xFF) ?: continue
                    val name = spec.boneMap[hash] ?: spec.bone
                    var i = names.indexOf(name)
                    if (i < 0) { names += name; i = names.size - 1 }
                    acc[i] = (acc[i] ?: 0f) + w
                }
                if (acc.isEmpty()) acc[0] = 1f
                val top = acc.entries.sortedByDescending { it.value }.take(4)
                val sum = top.sumOf { it.value.toDouble() }.toFloat()
                var left = 255
                for ((k, e) in top.withIndex()) {
                    val q = if (k == top.size - 1) left else (e.value / sum * 255f + 0.5f).toInt().coerceIn(0, left)
                    boneIdx[v * 4 + k] = e.key.toByte(); boneW[v * 4 + k] = q.toByte(); left -= q
                }
            }
            if (names.size > 255) throw BodyPartFormatException("The part $id uses ${names.size} bones; a part file holds 255.")
        }
        val height = a[1]
        val scale = if (height > 0.05f) spec.referenceHeight / height else 1f
        return ConvertedPart(id, state, variant, spec.bone, a, scale, FloatArray(3), pos, nrm, mesh.uvs?.copyOf(), names, boneIdx, boneW, idx,
            texture?.width ?: 0, texture?.height ?: 0)
    }
}

/**
 * The part file, Wifecraft's own format for one converted part (".wcbp", little-endian), written at import time to
 * files/bodyparts/<set>/ beside its texture (<id>.png) and the set's parts.jsonl; never committed.
 *
 *   0  "WCBP"                 magic
 *   4  u32 version            1
 *   8  u32 flags              1 normals, 2 UVs, 4 skin (bone indices and weights)
 *  12  u32 vertex count V
 *  16  u32 index count I      (triangles x 3)
 *  20  u32 bone count B
 *  24  f32 x3 anchor          Sims model space, metres (y up)
 *  36  f32 default scale      Warcraft units per Sims metre (the engine should use NPC Pelvis height / anchor height)
 *  40  f32 x3 attach offset   Warcraft model units, added at the attach bone's pivot (0: not yet known)
 *  52  u32 texture width, u32 texture height (0 when the part has none)
 *  60  strings, each u16 byte length then UTF-8: attach bone, id, state, variant, then B bone names (Warcraft names;
 *      bone 0 is the attach bone)
 *      f32 x3 x V positions    Warcraft axes (x forward, y left, z up), metres from the anchor
 *      f32 x3 x V normals      (flag 1)
 *      f32 x2 x V UVs          (flag 2; v = 0 at the texture's top row)
 *      u8 x4 x V bone indices, u8 x4 x V weights summing to 255 (flag 4)
 *      u32 x I indices
 */
object PartFile {
    const val MAGIC = 0x50424357 // "WCBP"
    const val VERSION = 1

    fun write(p: ConvertedPart): ByteArray {
        val v = p.vertexCount
        val strings = listOf(p.attachBone, p.id, p.state, p.variant) + p.bones
        val enc = strings.map { it.toByteArray(Charsets.UTF_8).also { b -> require(b.size <= 0xFFFF) } }
        val flags = (if (p.normals != null) 1 else 0) or (if (p.uvs != null) 2 else 0) or (if (p.boneIndices != null) 4 else 0)
        val size = 60 + enc.sumOf { 2 + it.size } + v * 12 + (if (p.normals != null) v * 12 else 0) + (if (p.uvs != null) v * 8 else 0) +
            (if (p.boneIndices != null) v * 8 else 0) + p.indices.size * 4
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC).putInt(VERSION).putInt(flags).putInt(v).putInt(p.indices.size).putInt(p.bones.size)
        p.anchorSims.forEach { bb.putFloat(it) }
        bb.putFloat(p.defaultScale)
        p.attachOffset.forEach { bb.putFloat(it) }
        bb.putInt(p.textureWidth).putInt(p.textureHeight)
        for (e in enc) { bb.putShort(e.size.toShort()); bb.put(e) }
        p.positions.forEach { bb.putFloat(it) }
        p.normals?.forEach { bb.putFloat(it) }
        p.uvs?.forEach { bb.putFloat(it) }
        if (p.boneIndices != null) { bb.put(p.boneIndices); bb.put(p.boneWeights!!) }
        p.indices.forEach { bb.putInt(it) }
        return bb.array()
    }

    fun read(b: ByteArray): ConvertedPart {
        val r = Bin(b, "part file")
        if (r.i32() != MAGIC) r.fail("is not a Wifecraft part file")
        val ver = r.i32()
        if (ver != VERSION) r.fail("is version $ver; this build reads $VERSION")
        val flags = r.i32(); val v = r.i32(); val ic = r.i32(); val bc = r.i32()
        if (v < 0 || ic < 0 || bc < 0 || bc > 255 || v.toLong() * 12 > b.size || ic.toLong() * 4 > b.size) r.fail("has bad counts")
        val anchor = FloatArray(3) { r.f32() }
        val scale = r.f32()
        val off = FloatArray(3) { r.f32() }
        val tw = r.i32(); val th = r.i32()
        fun str(): String { val n = r.u16(); return String(r.bytes(n), Charsets.UTF_8) }
        val bone = str(); val id = str(); val state = str(); val variant = str()
        val bones = List(bc) { str() }
        val pos = FloatArray(v * 3) { r.f32() }
        val nrm = if (flags and 1 != 0) FloatArray(v * 3) { r.f32() } else null
        val uv = if (flags and 2 != 0) FloatArray(v * 2) { r.f32() } else null
        val bi = if (flags and 4 != 0) r.bytes(v * 4) else null
        val bw = if (flags and 4 != 0) r.bytes(v * 4) else null
        val idx = IntArray(ic) { r.i32() }
        for (i in idx) if (i < 0 || i >= v) r.fail("has index $i of $v vertices")
        if (bi != null) for (x in bi) if ((x.toInt() and 0xFF) >= bc) r.fail("names bone ${x.toInt() and 0xFF} of $bc")
        return ConvertedPart(id, state, variant, bone, anchor, scale, off, pos, nrm, uv, bones, bi, bw, idx, tw, th)
    }

    /** One line of the set's parts.jsonl. */
    fun manifestLine(p: ConvertedPart, name: String, file: String, texture: String?, problems: List<String>): String {
        val o = JSONObject()
        o.put("id", p.id); o.put("name", name); o.put("state", p.state); o.put("variant", p.variant)
        o.put("file", file); o.put("texture", texture ?: JSONObject.NULL)
        o.put("vertices", p.vertexCount); o.put("triangles", p.indices.size / 3); o.put("bone", p.attachBone)
        o.put("problems", org.json.JSONArray(problems))
        return o.toString()
    }
}
