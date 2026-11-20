package com.logie.wifecraft.bodyparts

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * The Eve body source mesh: one lower-body variant of the user's Eve body (its upper and lower body CAS meshes merged, the
 * finest level), in Warcraft axes (x forward, y left, z up) and Sims metres, the feet at z 0, skinned by Warcraft bone NAME
 * ([bones]: the names engine/evebody.cpp resolves on each model). Written by [EveConvert], read by the engine at dress time
 * (engine/evebody.h parseEve: keep the two in step).
 *
 * [joints] are the Sims adult woman's joints the fit maps onto each Warcraft skeleton ([EveProfile.JOINTS]: estimates made
 * from Eve's own skin weights, not a rig), [rings] the open edges where the Warcraft body takes over (the neck's top and the
 * two ankles; every copy of a vertex at a texture seam is listed). [uvs] point into the converted textures (the crop
 * [cropV0]..[cropV1] of the Sims 2048 x 4096 body atlas, [textureWidth] x [textureHeight]); [uvs2] is the GEOM's second
 * texture set (unused by the renderer; zero when the mesh had none).
 */
class EveMesh(
    val variant: String,
    val bones: List<String>,
    val joints: Map<String, FloatArray>,
    val rings: Map<String, IntArray>,
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val uvs2: FloatArray,
    val boneIndices: ByteArray,
    val boneWeights: ByteArray,
    val indices: IntArray,
    val textureWidth: Int,
    val textureHeight: Int,
    val cropV0: Float,
    val cropV1: Float,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

/**
 * The .weve file ([EveMesh]), little-endian:
 *
 *     0  "WEVE" (u32 0x45564557)       4  u32 version (1)        8  u32 CRC-32 (zlib's) of every byte from 12 to the end
 *     12 u32 flags (1 normals, 2 second UVs)   16 u32 vertices   20 u32 indices   24 u32 bones   28 u32 joints   32 u32 rings
 *     36 u32 texture width   40 u32 texture height   44 f32 cropV0   48 f32 cropV1   52 string variant
 *     then: bones (string each); joints (string, 3 f32); rings (string, u32 n, n u32 vertex numbers);
 *     vertices, 48 bytes each: 3 f32 position, 3 f32 normal, 2 f32 uv, 2 f32 uv2, 4 u8 bone (into the bone names),
 *     4 u8 weight (/255, summing to 255); then the triangles' u32 indices.
 *
 * A string is u16 byte length then UTF-8. A file whose CRC, version or counts don't hold is refused (the engine says why).
 */
object EveFile {
    const val MAGIC = 0x45564557
    const val VERSION = 1
    const val VERTEX_BYTES = 48

    fun write(m: EveMesh): ByteArray {
        val body = ByteArrayOutputStream()
        val b4 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(v: Int) { b4.clear(); b4.putInt(v); body.write(b4.array(), 0, 4) }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            require(b.size <= 0xFFFF) { "name too long" }
            body.write(b.size and 0xFF); body.write(b.size ushr 8); body.write(b)
        }
        val n = m.vertexCount
        require(m.normals.size == n * 3 && m.uvs.size == n * 2 && m.uvs2.size == n * 2 && m.boneIndices.size == n * 4 && m.boneWeights.size == n * 4) { "per-vertex arrays don't match" }
        require(m.bones.size <= 255) { "more than 255 bones" }
        u32(3)
        u32(n); u32(m.indices.size); u32(m.bones.size); u32(m.joints.size); u32(m.rings.size)
        u32(m.textureWidth); u32(m.textureHeight); f32(m.cropV0); f32(m.cropV1)
        str(m.variant)
        m.bones.forEach { str(it) }
        for ((k, p) in m.joints) { str(k); f32(p[0]); f32(p[1]); f32(p[2]) }
        for ((k, r) in m.rings) { str(k); u32(r.size); r.forEach { u32(it) } }
        val vb = ByteBuffer.allocate(n * VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in 0 until n) {
            for (a in 0..2) vb.putFloat(m.positions[v * 3 + a])
            for (a in 0..2) vb.putFloat(m.normals[v * 3 + a])
            vb.putFloat(m.uvs[v * 2]); vb.putFloat(m.uvs[v * 2 + 1])
            vb.putFloat(m.uvs2[v * 2]); vb.putFloat(m.uvs2[v * 2 + 1])
            for (k in 0..3) vb.put(m.boneIndices[v * 4 + k])
            for (k in 0..3) vb.put(m.boneWeights[v * 4 + k])
        }
        body.write(vb.array())
        m.indices.forEach { u32(it) }
        val rest = body.toByteArray()
        val crc = CRC32().apply { update(rest) }.value.toInt()
        val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).putInt(VERSION).putInt(crc).array()
        return head + rest
    }

    fun read(b: ByteArray): EveMesh {
        fun bad(why: String): Nothing = throw BodyPartFormatException("The Eve mesh $why.")
        if (b.size < 56) bad("is too short")
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != MAGIC) bad("is not a Wifecraft Eve mesh")
        val ver = bb.getInt(4)
        if (ver != VERSION) bad("is version $ver; this build reads $VERSION")
        val crc = CRC32().apply { update(b, 12, b.size - 12) }.value.toInt()
        if (crc != bb.getInt(8)) bad("is damaged (its checksum doesn't match): convert the Eve body again")
        val n = bb.getInt(16); val ic = bb.getInt(20); val bc = bb.getInt(24); val jc = bb.getInt(28); val rc = bb.getInt(32)
        if (n < 0 || ic < 0 || ic % 3 != 0 || bc !in 0..255 || jc !in 0..256 || rc !in 0..64 || n.toLong() * VERTEX_BYTES > b.size || ic.toLong() * 4 > b.size) bad("has bad counts")
        val tw = bb.getInt(36); val th = bb.getInt(40); val c0 = bb.getFloat(44); val c1 = bb.getFloat(48)
        var p = 52
        fun need(k: Int) { if (p + k > b.size) bad("ends early") }
        fun str(): String { need(2); val len = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8); p += 2; need(len); return String(b, p, len, Charsets.UTF_8).also { p += len } }
        fun u32(): Int { need(4); return bb.getInt(p).also { p += 4 } }
        fun f32(): Float { need(4); return bb.getFloat(p).also { p += 4 } }
        val variant = str()
        val bones = List(bc) { str() }
        val joints = LinkedHashMap<String, FloatArray>()
        repeat(jc) { val k = str(); joints[k] = floatArrayOf(f32(), f32(), f32()) }
        val rings = LinkedHashMap<String, IntArray>()
        repeat(rc) {
            val k = str(); val cnt = u32()
            if (cnt < 0 || cnt > n) bad("has a ring of $cnt vertices")
            rings[k] = IntArray(cnt) { u32().also { v -> if (v !in 0 until n) bad("rings vertex $v of $n") } }
        }
        need(n * VERTEX_BYTES + ic * 4)
        val pos = FloatArray(n * 3); val nrm = FloatArray(n * 3); val uv = FloatArray(n * 2); val uv2 = FloatArray(n * 2)
        val bi = ByteArray(n * 4); val bw = ByteArray(n * 4)
        for (v in 0 until n) {
            for (a in 0..2) pos[v * 3 + a] = f32()
            for (a in 0..2) nrm[v * 3 + a] = f32()
            uv[v * 2] = f32(); uv[v * 2 + 1] = f32(); uv2[v * 2] = f32(); uv2[v * 2 + 1] = f32()
            for (k in 0..3) { bi[v * 4 + k] = b[p++]; if ((bi[v * 4 + k].toInt() and 0xFF) >= maxOf(bc, 1)) bad("names bone ${bi[v * 4 + k].toInt() and 0xFF} of $bc") }
            for (k in 0..3) bw[v * 4 + k] = b[p++]
        }
        val idx = IntArray(ic) { u32().also { i -> if (i !in 0 until n) bad("has index $i of $n vertices") } }
        return EveMesh(variant, bones, joints, rings, pos, nrm, uv, uv2, bi, bw, idx, tw, th, c0, c1)
    }
}

/**
 * files/bodyparts/eve/set.json: what [EveConvert] made of the user's Eve packages and what the engine draws with
 * (engine/evebody.h parseEveSet reads the same keys). Colours are "RRGGBB" (sRGB). [skin] is the neutral skin (auto-tinted
 * to each NPC's own), [tones] the skin-tone skins found ([Texture.mean]: their mean colour over the body, the table the
 * menus show), [overlays] the overlay sets by slot, each shade with the swatch it was made for, its own mean colour and its
 * rectangle in the body texture (x, y, w, h pixels).
 */
class EveSetIndex(
    val meshes: Map<String, String>,
    val width: Int,
    val height: Int,
    val cropV0: Float,
    val cropV1: Float,
    val skin: Texture?,
    val tones: List<Texture>,
    val overlays: List<OverlaySlot>,
    val sources: List<String>,
    val notes: List<String>,
) {
    /** A converted skin: its file, ids, the CAS swatch, its mean colour over the body, near the neck ring and near the ankles. */
    class Texture(val id: String, val name: String, val file: String, val swatch: Int, val mean: Int, val neck: Int, val ankle: Int, val correction: Float)
    class Shade(val id: String, val file: String, val swatch: Int, val mean: Int, val rect: IntArray)
    class OverlaySlot(val slot: String, val shades: List<Shade>)

    fun toJson(): String {
        fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
        fun tex(t: Texture) = JSONObject().put("id", t.id).put("name", t.name).put("file", t.file).put("swatch", hex(t.swatch))
            .put("mean", hex(t.mean)).put("neck", hex(t.neck)).put("ankle", hex(t.ankle)).put("correction", t.correction.toDouble())
        val o = JSONObject().put("format", FORMAT)
        val ms = JSONObject(); meshes.forEach { (k, v) -> ms.put(k, v) }
        o.put("meshes", ms)
        o.put("texture", JSONObject().put("width", width).put("height", height).put("cropV0", cropV0.toDouble()).put("cropV1", cropV1.toDouble()))
        skin?.let { o.put("skin", tex(it)) }
        o.put("tones", JSONArray().also { a -> tones.forEach { a.put(tex(it)) } })
        o.put("overlays", JSONArray().also { a ->
            overlays.forEach { s ->
                a.put(JSONObject().put("slot", s.slot).put("shades", JSONArray().also { sa ->
                    s.shades.forEach { sh ->
                        sa.put(JSONObject().put("id", sh.id).put("file", sh.file).put("swatch", hex(sh.swatch)).put("mean", hex(sh.mean))
                            .put("rect", JSONArray().also { r -> sh.rect.forEach { r.put(it) } }))
                    }
                }))
            }
        })
        o.put("sources", JSONArray(sources))
        o.put("notes", JSONArray(notes))
        return o.toString(1)
    }

    companion object {
        const val FORMAT = 1

        fun parse(json: String): EveSetIndex {
            val o = JSONObject(json)
            if (o.optInt("format") != FORMAT) throw BodyPartFormatException("The Eve set is format ${o.optInt("format")}; this build reads $FORMAT: convert it again.")
            fun col(s: String?) = s?.toIntOrNull(16) ?: 0
            fun tex(t: JSONObject) = Texture(t.optString("id"), t.optString("name"), t.getString("file"), col(t.optString("swatch")), col(t.optString("mean")),
                col(t.optString("neck")), col(t.optString("ankle")), t.optDouble("correction", 0.0).toFloat())
            val meshes = LinkedHashMap<String, String>()
            o.optJSONObject("meshes")?.let { m -> val it = m.keys(); while (it.hasNext()) { val k = it.next(); meshes[k] = m.getString(k) } }
            val t = o.optJSONObject("texture") ?: JSONObject()
            val tones = ArrayList<Texture>()
            o.optJSONArray("tones")?.let { a -> for (i in 0 until a.length()) tones += tex(a.getJSONObject(i)) }
            val overlays = ArrayList<OverlaySlot>()
            o.optJSONArray("overlays")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    val shades = ArrayList<Shade>()
                    s.optJSONArray("shades")?.let { sa ->
                        for (j in 0 until sa.length()) {
                            val sh = sa.getJSONObject(j)
                            val r = sh.optJSONArray("rect")
                            shades += Shade(sh.optString("id"), sh.getString("file"), col(sh.optString("swatch")), col(sh.optString("mean")),
                                IntArray(4) { k -> r?.optInt(k) ?: 0 })
                        }
                    }
                    overlays += OverlaySlot(s.getString("slot"), shades)
                }
            }
            fun strings(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            return EveSetIndex(meshes, t.optInt("width"), t.optInt("height"), t.optDouble("cropV0", 0.0).toFloat(), t.optDouble("cropV1", 1.0).toFloat(),
                o.optJSONObject("skin")?.let { tex(it) }, tones, overlays, strings("sources"), strings("notes"))
        }
    }
}
