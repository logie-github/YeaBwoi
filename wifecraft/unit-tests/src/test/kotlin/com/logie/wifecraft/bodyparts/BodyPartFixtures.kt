package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.sims.SimsFixtures.Buf
import com.logie.wifecraft.sims.SimsHash

/**
 * Tiny synthetic GEOM, CASP and RLE2 resources in the layouts Geom, Casp and SimsTexture document (no bytes of any
 * real mod: these test our reading of our understanding of the formats).
 */
internal object BodyPartFixtures {
    class Vtx(val x: Float, val y: Float, val z: Float, val u: Float = 0f, val v: Float = 0f, val bones: IntArray = intArrayOf(0, 0, 0, 0), val w: IntArray = intArrayOf(255, 0, 0, 0))

    /** An RCOL-wrapped GEOM v14 with position, normal, UV, bone indices, weights and vertex ids. */
    fun geom(verts: List<Vtx>, tris: List<Int>, bones: List<String>, version: Int = 14, rcol: Boolean = true): ByteArray {
        val g = Buf().bytes("GEOM".toByteArray()).u32(version)
        val body = Buf()
        body.u32(0) // shader: none
        body.u32(0).u32(0) // merge group, sort order
        body.u32(verts.size).u32(6)
        for ((u, s) in listOf(1 to 12, 2 to 12, 3 to 8, 4 to 4, 5 to 4, 10 to 4)) body.u32(u).u32(1).u8(s)
        for ((i, v) in verts.withIndex()) {
            body.f32(v.x).f32(v.y).f32(v.z).f32(0f).f32(1f).f32(0f).f32(v.u).f32(v.v)
            v.bones.forEach { body.u8(it) }; v.w.forEach { body.u8(it) }
            body.u32(i)
        }
        body.u32(1).u8(2).u32(tris.size)
        tris.forEach { body.u16(it) }
        body.u32(0) // UV stitches
        if (version >= 13) body.u32(0) // seam stitches
        body.u32(0) // slot rays
        body.u32(bones.size); bones.forEach { body.u32(SimsHash.fnv32(it)) }
        if (version >= 15) body.u32(0)
        val bb = body.get()
        // key-table offset is counted from after its own field: the size field and the body follow
        g.u32(4 + bb.size).u32(4).bytes(bb).u32(0)
        val chunk = g.get()
        if (!rcol) return chunk
        val head = Buf().u32(3).u32(1).u32(0).u32(0).u32(1)
        head.u32(0x12345678).u32(0).u32(Geom.TYPE).u32(0) // internal key: instance, type, group
        val at = head.size + 8
        head.u32(at).u32(chunk.size)
        return head.bytes(chunk).get()
    }

    /** A CASP of [version] (46 or 49) with one LOD per mesh key, a diffuse and the given name and body type. */
    fun casp(name: String, meshes: List<ResourceKey>, diffuse: ResourceKey?, version: Int = 49, bodyType: Int = 7, ageGender: Int = 0x1078): ByteArray {
        val keys = ArrayList<ResourceKey>().apply { add(ResourceKey.NONE); addAll(meshes); diffuse?.let { add(it) } }
        val b = Buf().u32(version)
        b.u32(0).u32(0) // key-table offset (patched below), presets
        val nameBytes = name.toByteArray(Charsets.UTF_16BE)
        b.u8(nameBytes.size).bytes(nameBytes) // < 128 bytes in the tests
        b.f32(0f).u16(0).u32(0).u32(0).u8(0)
        if (version >= 39) b.u8(0)
        b.u32(0).u32(0); b.u32(0).u32(0) // exclude flags (u64, u64)
        b.u32(0).u32(0) // modifier region flags
        b.u32(1).u16(70).u32(81) // one tag
        b.u32(0).u32(0).u32(0).u32(0) // price, title, desc, create desc
        b.u8(0).u32(bodyType).u32(0).u32(ageGender).u32(1)
        b.u16(0).u8(0); repeat(9) { b.u8(0) }
        b.u8(1).u32(0xFFD3D3D3.toInt()) // one swatch
        b.u8(0).u8(0)
        b.u32(0).u32(0) // voice
        b.u8(0) // materials
        b.u32(0) // occult
        b.u32(0).u32(0) // v46 unknown
        b.u32(0).u32(0); b.u32(0).u32(0) // opposite, fallback
        repeat(11) { b.f32(0f) }
        b.u8(0) // v46 unknown count
        b.u8(0).u8(0).u32(2217)
        b.u8(meshes.size)
        for ((i, _) in meshes.withIndex()) { b.u8(i).u32(0).u8(0).u8(1).u8(1 + i) }
        b.u8(0) // slots
        b.u8(if (diffuse != null) keys.size - 1 else 0).u8(0).u8(3).u8(0)
        b.u8(0) // overrides
        b.u8(0).u8(0).u32(7).u8(0)
        b.u8(0) // v42
        if (version >= 49) b.u8(0)
        val tableAt = b.size
        b.u8(keys.size)
        for (k in keys) { b.u32(k.instance.toInt()).u32((k.instance ushr 32).toInt()).u32(k.group).u32(k.type) }
        val out = b.get()
        val off = tableAt - 8
        out[4] = off.toByte(); out[5] = (off ushr 8).toByte(); out[6] = 0; out[7] = 0
        return out
    }

    /** An RLE2 image of [blocks] DXT5 blocks given as (op, 16 bytes) per block, written as one command per block. */
    fun rle2(w: Int, h: Int, blocks: List<Pair<Int, ByteArray>>): ByteArray {
        val cmds = Buf(); val s0 = Buf(); val s1 = Buf(); val s2 = Buf(); val s3 = Buf()
        for ((op, blk) in blocks) {
            cmds.u16((1 shl 2) or op)
            if (op == 1) { s0.bytes(blk.copyOfRange(0, 2)); s1.bytes(blk.copyOfRange(2, 8)) }
            if (op != 0) { s2.bytes(blk.copyOfRange(8, 12)); s3.bytes(blk.copyOfRange(12, 16)) }
        }
        val head = 16 + 20
        val c = head; val o2 = c + cmds.size; val o3 = o2 + s2.size; val o0 = o3 + s3.size; val o1 = o0 + s0.size
        return Buf().bytes("DXT5RLE2".toByteArray()).u16(w).u16(h).u16(1).u16(0)
            .u32(c).u32(o2).u32(o3).u32(o0).u32(o1)
            .bytes(cmds.get()).bytes(s2.get()).bytes(s3.get()).bytes(s0.get()).bytes(s1.get()).get()
    }

    /** A DXT5 block: alpha a0, a1 and 16 3-bit indices, colour c0, c1 (RGB565) and 16 2-bit indices. */
    fun dxt5(a0: Int, a1: Int, aIdx: IntArray, c0: Int, c1: Int, cIdx: IntArray): ByteArray {
        val b = ByteArray(16)
        b[0] = a0.toByte(); b[1] = a1.toByte()
        var bits = 0L
        for (i in 0 until 16) bits = bits or (aIdx[i].toLong() shl (3 * i))
        for (i in 0 until 6) b[2 + i] = (bits ushr (8 * i)).toByte()
        b[8] = c0.toByte(); b[9] = (c0 ushr 8).toByte(); b[10] = c1.toByte(); b[11] = (c1 ushr 8).toByte()
        for (r in 0..3) { var v = 0; for (k in 0..3) v = v or (cIdx[r * 4 + k] shl (2 * k)); b[12 + r] = v.toByte() }
        return b
    }
}
