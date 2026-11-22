package com.logie.wifecraft.props

import com.logie.wifecraft.sims.SimsFormatException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** One mesh of a prop in Warcraft axes (x forward, y left, z up; metres): flat colour [color] or [texture] (an index, -1 none). */
class PropMesh(
    val positions: FloatArray, val normals: FloatArray?, val uvs: FloatArray?, val indices: IntArray,
    val color: FloatArray, val texture: Int,
)

class PropTexture(val width: Int, val height: Int, val rgba: ByteArray)

/**
 * A prop ready for the engine (engine/propmesh.h reads it): [guid] the Sims object id, [bounds] (min x, y, z, max x, y, z) in
 * Warcraft axes, its meshes, textures and the bone names of the object's own rig (root first; a clip that moves the prop names them).
 *
 * File `.wcprop` (little-endian): u32 "WCPR" 0x52504357, u32 version 1, u64 guid, 6 f32 bounds, u32 bone count and each bone as a
 * u16-length UTF-8 name, u32 mesh count; per mesh: u32 vertices, u32 indices, u32 flags (1 normals, 2 UVs), 4 f32 colour, i32
 * texture, then the positions (3 f32 each), the normals, the UVs (2 f32), the indices (u32); u32 texture count; per texture:
 * u32 width, u32 height, u32 packed length and the RGBA bytes deflated; u32 CRC-32 of all bytes before it.
 */
class PropModel(val guid: Long, val bounds: FloatArray, val bones: List<String>, val meshes: List<PropMesh>, val textures: List<PropTexture>) {
    val vertexCount: Int get() = meshes.sumOf { it.positions.size / 3 }
    val triangleCount: Int get() = meshes.sumOf { it.indices.size / 3 }
    /** Size in metres: depth (x), width (y), height (z). */
    val size: FloatArray get() = floatArrayOf(bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2])
}

object PropFile {
    const val MAGIC = 0x52504357
    const val VERSION = 1
    const val MAX_TEXTURE_SIDE = 512

    /** A Sims object as a prop: axes turned to Warcraft's (x forward = Sims z, y left = Sims x, z up = Sims y), textures read through [texture]. */
    fun fromObject(o: SimsObject, texture: (Key) -> com.logie.wifecraft.bodyparts.RgbaImage?): PropModel {
        val textures = ArrayList<PropTexture>()
        val byKey = HashMap<Key, Int>()
        val meshes = o.meshes.map { m ->
            var tex = -1
            m.material.diffuse?.let { k ->
                tex = byKey.getOrPut(k) {
                    val img = texture(k)
                    if (img == null) -1 else { textures += PropTexture(img.width, img.height, img.rgba); textures.size - 1 }
                }
            }
            val pos = FloatArray(m.positions.size)
            var i = 0
            while (i < pos.size) { pos[i] = m.positions[i + 2]; pos[i + 1] = m.positions[i]; pos[i + 2] = m.positions[i + 1]; i += 3 }
            val nor = m.normals?.let { n -> FloatArray(n.size).also { r -> var j = 0; while (j < r.size) { r[j] = n[j + 2]; r[j + 1] = n[j]; r[j + 2] = n[j + 1]; j += 3 } } }
            PropMesh(pos, nor, m.uvs, m.indices, m.material.color ?: floatArrayOf(0.78f, 0.78f, 0.8f, 1f), tex)
        }
        val b = o.bounds()
        return PropModel(o.guid, floatArrayOf(b[2], b[0], b[1], b[5], b[3], b[4]), o.rigBones, meshes, textures)
    }

    fun write(m: PropModel): ByteArray {
        val o = ByteArrayOutputStream()
        fun u32(v: Int) { o.write(v and 0xFF); o.write(v ushr 8 and 0xFF); o.write(v ushr 16 and 0xFF); o.write(v ushr 24 and 0xFF) }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        u32(MAGIC); u32(VERSION)
        u32(m.guid.toInt()); u32((m.guid ushr 32).toInt())
        for (v in m.bounds) f32(v)
        u32(m.bones.size)
        for (b in m.bones) { val t = b.toByteArray(Charsets.UTF_8); o.write(t.size and 0xFF); o.write(t.size ushr 8 and 0xFF); o.write(t) }
        u32(m.meshes.size)
        for (me in m.meshes) {
            u32(me.positions.size / 3); u32(me.indices.size)
            u32((if (me.normals != null) 1 else 0) or (if (me.uvs != null) 2 else 0))
            for (c in 0 until 4) f32(me.color.getOrElse(c) { 1f })
            u32(me.texture)
            for (v in me.positions) f32(v)
            me.normals?.forEach { f32(it) }
            me.uvs?.forEach { f32(it) }
            for (i in me.indices) u32(i)
        }
        u32(m.textures.size)
        for (t in m.textures) {
            u32(t.width); u32(t.height)
            val d = Deflater(6)
            val z = ByteArrayOutputStream()
            try { d.setInput(t.rgba); d.finish(); val buf = ByteArray(65536); while (!d.finished()) z.write(buf, 0, d.deflate(buf)) } finally { d.end() }
            u32(z.size()); o.write(z.toByteArray())
        }
        val body = o.toByteArray()
        val crc = CRC32().also { it.update(body) }.value.toInt()
        return body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte(), (crc ushr 16).toByte(), (crc ushr 24).toByte())
    }

    fun read(bytes: ByteArray): PropModel {
        fun fail(why: String): Nothing = throw SimsFormatException("The prop file is damaged ($why).")
        if (bytes.size < 56) fail("too short")
        val crc = CRC32().also { it.update(bytes, 0, bytes.size - 4) }.value.toInt()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(bytes.size - 4) != crc) fail("checksum")
        if (b.getInt(0) != MAGIC) fail("not a prop file")
        if (b.getInt(4) != VERSION) fail("version ${b.getInt(4)}")
        var p = 8
        val guid = (b.getInt(p).toLong() and 0xFFFFFFFFL) or (b.getInt(p + 4).toLong() shl 32); p += 8
        val bounds = FloatArray(6) { b.getFloat(p + it * 4) }; p += 24
        fun need(n: Long) { if (n < 0 || p + n > bytes.size - 4) fail("cut short") }
        val nb = b.getInt(p); p += 4
        if (nb < 0 || nb > 4096) fail("bones")
        val bones = ArrayList<String>()
        repeat(nb) { need(2); val l = b.getShort(p).toInt() and 0xFFFF; p += 2; need(l.toLong()); bones += String(bytes, p, l, Charsets.UTF_8); p += l }
        need(4); val nm = b.getInt(p); p += 4
        if (nm < 0 || nm > 256) fail("meshes")
        val meshes = ArrayList<PropMesh>()
        repeat(nm) {
            need(36); val nv = b.getInt(p); val ni = b.getInt(p + 4); val flags = b.getInt(p + 8)
            val color = FloatArray(4) { b.getFloat(p + 12 + it * 4) }; val tex = b.getInt(p + 28); p += 32
            if (nv < 0 || ni < 0 || ni % 3 != 0 || nv > 2_000_000 || ni > 6_000_000) fail("mesh size")
            need(nv * 12L); val pos = FloatArray(nv * 3) { b.getFloat(p + it * 4) }; p += nv * 12
            val nor = if (flags and 1 != 0) { need(nv * 12L); FloatArray(nv * 3) { b.getFloat(p + it * 4) }.also { p += nv * 12 } } else null
            val uv = if (flags and 2 != 0) { need(nv * 8L); FloatArray(nv * 2) { b.getFloat(p + it * 4) }.also { p += nv * 8 } } else null
            need(ni * 4L); val idx = IntArray(ni) { b.getInt(p + it * 4) }; p += ni * 4
            if (idx.any { it < 0 || it >= nv }) fail("index outside the vertices")
            meshes += PropMesh(pos, nor, uv, idx, color, tex)
        }
        need(4); val nt = b.getInt(p); p += 4
        if (nt < 0 || nt > 64) fail("textures")
        val textures = ArrayList<PropTexture>()
        repeat(nt) {
            need(12); val w = b.getInt(p); val h = b.getInt(p + 4); val len = b.getInt(p + 8); p += 12
            if (w < 1 || h < 1 || w > 4096 || h > 4096) fail("texture size")
            need(len.toLong())
            val raw = ByteArray(w * h * 4)
            val inf = Inflater()
            try {
                inf.setInput(bytes, p, len)
                var n = 0
                while (n < raw.size) { val got = inf.inflate(raw, n, raw.size - n); if (got == 0 && (inf.finished() || inf.needsInput())) break; n += got }
                if (n != raw.size) fail("texture data")
            } finally { inf.end() }
            p += len
            textures += PropTexture(w, h, raw)
        }
        if (meshes.any { it.texture >= textures.size }) fail("texture index")
        return PropModel(guid, bounds, bones, meshes, textures)
    }
}
