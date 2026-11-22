package com.logie.wifecraft.pack

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synthetic Sims 4 packages and packs for the tests: DBPF with groups, snippet tuning, an object model (OBJD, MODL, MLOD with
 * a vertex format, buffers and a material), and pack zips with a manifest. Written from the layout the reader documents and the
 * real packages showed; never the user's files.
 */
internal object PackFixtures {
    class W {
        val o = ByteArrayOutputStream()
        val size get() = o.size()
        fun u8(v: Int) = apply { o.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
        fun u64(v: Long) = apply { u32(v.toInt()); u32((v ushr 32).toInt()) }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        fun bytes(b: ByteArray) = apply { o.write(b) }
        fun get(): ByteArray = o.toByteArray()
    }

    class Res(val type: Int, val instance: Long, val data: ByteArray, val group: Int = 0)

    const val T_CLIP = 0x6B20C4F3
    const val T_TUNING = 0x7DF2169C
    const val T_OBJD = 0xC0DB5AE7.toInt()
    const val T_MODL = 0x01661233
    const val T_MLOD = 0x01D10F34
    const val T_DDS = 0x00B2D882

    /** A DBPF 2.1 package, each resource zlib-packed (0x5A42), keys with their groups. */
    fun dbpf(vararg rs: Res): ByteArray {
        val body = ByteArrayOutputStream()
        val stored = rs.map { r ->
            val raw = Deflater().run { setInput(r.data); finish(); val out = ByteArray(r.data.size + 64); val n = deflate(out); end(); out.copyOf(n) }
            val off = 96 + body.size()
            body.write(raw)
            Triple(r, off, raw.size)
        }
        val idx = W().u32(0)
        for ((r, off, size) in stored) {
            idx.u32(r.type).u32(r.group).u32((r.instance ushr 32).toInt()).u32(r.instance.toInt()).u32(off).u32(size or Int.MIN_VALUE).u32(r.data.size)
                .u16(0x5A42).u16(1)
        }
        val index = idx.get()
        val h = W().bytes("DBPF".toByteArray()).u32(2).u32(1)
        while (h.size < 36) h.u32(0)
        h.u32(rs.size).u32(0).u32(index.size)
        while (h.size < 64) h.u32(0)
        h.u32(96 + body.size())
        while (h.size < 96) h.u32(0)
        return h.bytes(body.toByteArray()).bytes(index).get()
    }

    // ------------------------------------------------------------------------------------------ tuning

    class StageSpec(
        val name: String, val category: String = "VAGINAL", val locations: String = "DOUBLE_BED", val actorClips: List<String>,
        val props: List<Triple<Int, List<Long>, String>> = emptyList(), val stage: String = "", val author: String = "Tester",
    )

    /** A WickedWhims animation snippet: `<L><U>` lists, `animation_clip_name`, props with `prop_guids`. */
    fun tuning(vararg stages: StageSpec): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<I c=\"WickedWhimsAnimationPackage\" i=\"snippet\" m=\"wickedwhims.sex.animations.animations_tuning\" n=\"t\" s=\"1\">\n")
        append("  <T n=\"wickedwhims_animations\">1</T>\n  <L n=\"animations_list\">\n")
        for (s in stages) {
            append("    <U>\n      <T n=\"animation_raw_display_name\">${s.name}</T>\n      <T n=\"animation_author\">${s.author}</T>\n")
            append("      <T n=\"animation_stage_name\">${s.stage}</T>\n      <T n=\"animation_locations\">${s.locations}</T>\n")
            append("      <T n=\"animation_category\">${s.category}</T>\n      <T n=\"animation_loops\">3</T>\n      <L n=\"animation_actors_list\">\n")
            s.actorClips.forEachIndexed { i, c ->
                append("        <U><T n=\"actor_id\">$i</T><T n=\"animation_clip_name\">$c</T><T n=\"animation_pref_gender\">${if (i == 0) "FEMALE" else "MALE"}</T></U>\n")
            }
            append("      </L>\n")
            if (s.props.isNotEmpty()) {
                append("      <L n=\"animation_props_list\">\n")
                for ((id, guids, clip) in s.props) {
                    append("        <U><T n=\"prop_id\">$id</T><T n=\"prop_type\">BASIC</T><T n=\"prop_guids\">${guids.joinToString(", ") { it.toULong().toString() }}</T><T n=\"prop_animation_clip_name\">$clip</T></U>\n")
                }
                append("      </L>\n")
            }
            append("    </U>\n")
        }
        append("  </L>\n</I>")
    }.toByteArray()

    // ------------------------------------------------------------------------------------------ an object model

    /** A resource collection: [chunks] (key type, group, instance, bytes) all internal, [external] keys after them. */
    fun rcol(chunks: List<Res>, external: List<Res> = emptyList()): ByteArray {
        val keys = W()
        for (c in chunks + external) keys.u64(c.instance).u32(c.type).u32(c.group)
        val headerSize = 20 + keys.size + 8 * chunks.size
        val table = W()
        var pos = headerSize
        for (c in chunks) { table.u32(pos).u32(c.data.size); pos += c.data.size }
        val out = W().u32(3).u32(1).u32(0).u32(external.size).u32(chunks.size).bytes(keys.get()).bytes(table.get())
        for (c in chunks) out.bytes(c.data)
        return out.get()
    }

    /** One MLOD mesh record (what follows the size): references are `0x1000000N`, a negative [skin] none. */
    private fun meshRecord(mat: Int, vf: Int, vb: Int, ib: Int, streamOff: Int, startIndex: Int, nverts: Int, nprims: Int, hashes: List<Int>): ByteArray {
        val m = W().u32(0x1234).u32(ref(mat)).u32(ref(vf)).u32(ref(vb)).u32(ref(ib)).u32(0x185003)
            .u32(streamOff).u32(0).u32(startIndex).u32(0).u32(nverts).u32(nprims)
        repeat(6) { m.f32(0f) }
        m.u32(0).u32(hashes.size)
        for (h in hashes) m.u32(h)
        m.u32(0).u32(0).u32(0).u32(0).u32(0x3F800000).u32(0).u32(0).u32(0)   // the tail: a reference, counts, the mirror plane
        return m.get()
    }

    private fun ref(index: Int): Int = if (index < 0) 0 else 0x10000000 or index

    /** A material chunk with one resource-key parameter: the diffuse texture ([P_DIFFUSE]) at [tex] (instance, type, group). */
    private fun matd(tex: Triple<Long, Int, Int>?): ByteArray {
        val params = W()
        val data = W()
        val n = if (tex != null) 1 else 0
        val dataStart = 16 + 16 * n
        if (tex != null) {
            params.u32(0x6CC0FD85).u32(4).u32(4).u32(dataStart)
            data.u64(tex.first).u32(tex.second).u32(tex.third)
        }
        val mtrl = W().bytes("MTRL".toByteArray()).u32(0).u32(params.size + data.size).u32(n).bytes(params.get()).bytes(data.get())
        return W().bytes("MATD".toByteArray()).u32(0x103).u32(0).u32(0).u32(mtrl.size).bytes(mtrl.get()).get()
    }

    /**
     * An MLOD (one 3-vertex shadow stub, then a quad of two triangles: 4 vertices of 32 bytes: Short4 position, unsigned-byte
     * normal, two u16 UVs, bone indices, weights, tangent, a second UV; indices stored as differences) with a material using
     * the diffuse texture [tex].
     */
    fun mlod(instance: Long, group: Int, tex: Triple<Long, Int, Int>?, quad: List<FloatArray> = listOf(
        floatArrayOf(-0.5f, 0f, 0f), floatArrayOf(0.5f, 0f, 0f), floatArrayOf(0.5f, 1f, 0f), floatArrayOf(-0.5f, 1f, 0f),
    )): ByteArray {
        val vrtf = W().bytes("VRTF".toByteArray()).u32(0x200).u32(32).u32(7).u32(0)
        for (e in listOf(intArrayOf(0, 0, 7, 0), intArrayOf(1, 0, 8, 8), intArrayOf(2, 0, 6, 12), intArrayOf(3, 0, 4, 16), intArrayOf(4, 0, 8, 20), intArrayOf(5, 0, 8, 24), intArrayOf(2, 1, 6, 28))) {
            vrtf.u8(e[0]).u8(e[1]).u8(e[2]).u8(e[3])
        }
        val verts = W()
        // the shadow stub's three vertices first (stream offset 0), then the quad at 96
        repeat(3) { verts.bytes(ByteArray(32)) }
        val uvs = listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        quad.forEachIndexed { i, p ->
            for (k in 0 until 3) verts.u16((p[k] * 32767f).toInt())
            verts.u16(32767)                                   // w
            verts.u8(128).u8(255).u8(128).u8(255)              // the normal: (0, 1, 0)
            verts.u16((uvs[i][0] * 65535f).toInt().coerceAtMost(65535)).u16((uvs[i][1] * 65535f).toInt().coerceAtMost(65535))
            verts.u32(0).u32(0xFF).u32(0).u32(0)               // bone indices, weights, tangent, second uv
        }
        val vbuf = W().bytes("VBUF".toByteArray()).u32(0x101).u32(0).u32(0x10000000).bytes(verts.get())
        // triangles 0 1 2 and 0 2 3 as differences: 0, +1, +1, -2, +2, +1
        val ibuf = W().bytes("IBUF".toByteArray()).u32(2).u32(1).u32(0)
        for (d in listOf(0, 1, 1, -2, 2, 1)) ibuf.u16(d)
        // the shadow stub's own triangle is at index 0 of its buffers: give it a separate tiny index buffer instead
        val stubIb = W().bytes("IBUF".toByteArray()).u32(2).u32(0).u32(0).u16(0).u16(1).u16(2)
        val stubVb = W().bytes("VBUF".toByteArray()).u32(0x101).u32(0).u32(0).bytes(ByteArray(96))
        val chunks = listOf(
            Res(T_MLOD, instance, ByteArray(0), group),                                        // 0 patched below
            Res(0x01D0E75D, 0, matd(tex), 0),                                                   // 1 material
            Res(0x02019972 /* unused */, 0, ByteArray(0), 0),                                   // 2 (placeholder)
            Res(0x01D0E723, 0, vrtf.get(), 0),                                                  // 3 vertex format
            Res(0x01D0E6FB, 0, vbuf.get(), 0),                                                  // 4 vertex buffer
            Res(0x01D0E70F, 0, ibuf.get(), 0),                                                  // 5 index buffer
            Res(0x0229684B, 0, stubVb.get(), 0),                                                // 6 stub vertices
            Res(0x0229684F, 0, stubIb.get(), 0),                                                // 7 stub indices
        )
        val stub = meshRecord(1, 3, 6, 7, 0, 0, 3, 1, emptyList())
        val real = meshRecord(1, 3, 4, 5, 96, 0, 4, 2, listOf(0x1111))
        val mesh = W().bytes("MLOD".toByteArray()).u32(0x205).u32(2).u32(stub.size).bytes(stub).u32(real.size).bytes(real).get()
        return rcol(chunks.mapIndexed { i, c -> if (i == 0) Res(T_MLOD, instance, mesh, group) else c })
    }

    /** An OBJD blob holding the keys of its model (halves of the instance swapped, as the real ones write them) and a variant name. */
    fun objd(modelInstance: Long, variant: String? = null): ByteArray {
        val w = W().u16(2).u32(251).u32(5).bytes("proto".toByteArray())
        w.u32(4).u32((modelInstance ushr 32).toInt()).u32(modelInstance.toInt()).u32(T_MODL).u32(0)
        if (variant != null) { w.u32(variant.length).bytes(variant.toByteArray()) }
        return w.get()
    }

    /** A package of one object: OBJD [guid], its MODL pointing at one MLOD, the MLOD, a texture. */
    fun objectPackage(guid: Long, modelInstance: Long, withTuning: StageSpec? = null, clips: List<Res> = emptyList(), variant: String? = null): ByteArray {
        val tex = Triple(0x7777L, T_DDS, 0x80000000.toInt())
        val modl = rcol(listOf(Res(T_MODL, modelInstance, W().bytes("MODL".toByteArray()).u32(0x300).get())), listOf(Res(T_MLOD, modelInstance, ByteArray(0), 0)))
        val res = mutableListOf(
            Res(T_OBJD, guid, objd(modelInstance, variant), 0x80000000.toInt()),
            Res(T_MODL, modelInstance, modl),
            Res(T_MLOD, modelInstance, mlod(modelInstance, 0, tex), 0),
            Res(T_DDS, 0x7777L, dds(8, 8, 200, 60, 30), 0x80000000.toInt()),
        )
        withTuning?.let { res += Res(T_TUNING, 0xABCDL, tuning(it)) }
        res += clips
        return dbpf(*res.toTypedArray())
    }

    /** An uncompressed 32-bit BGRA DDS of one colour (the reader takes plain DDS as well as DST). */
    fun dds(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray {
        val o = W().bytes("DDS ".toByteArray()).u32(124).u32(0x1007).u32(h).u32(w).u32(w * 4).u32(0).u32(0)
        repeat(11) { o.u32(0) }
        o.u32(32).u32(0x41).u32(0).u32(32).u32(0xFF0000).u32(0xFF00).u32(0xFF).u32(0xFF000000.toInt())
        o.u32(0x1000).u32(0).u32(0).u32(0).u32(0)
        while (o.size < 128) o.u8(0)
        repeat(w * h) { o.u8(b).u8(g).u8(r).u8(255) }
        return o.get()
    }

    /** A package of animation clips with a tuning (the clip bytes are not read by the classifier, only counted). */
    fun animationPackage(vararg stages: StageSpec): ByteArray {
        val res = ArrayList<Res>()
        var n = 1L
        for (s in stages) for (c in s.actorClips) res += Res(T_CLIP, n++, byteArrayOf(1, 2, 3))
        res += Res(T_TUNING, 0xF00DL, tuning(*stages))
        return dbpf(*res.toTypedArray())
    }

    // ------------------------------------------------------------------------------------------ packs

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    class PackFile(val kind: String, val file: String, val data: ByteArray, val source: String = file, val extra: String = "")

    /** A pack zip: `pack/manifest.json` and the files; [extraZip] entries written as they are (a bad name, a script). */
    fun pack(files: List<PackFile>, extraZip: Map<String, ByteArray> = emptyMap(), name: String = "Test pack", tamper: (String) -> String = { it }): ByteArray {
        val dirs = mapOf("animations" to "animations", "body-layer" to "bodylayers", "props" to "props", "furniture" to "furniture", "script-info" to "info", "unused" to "other")
        val entries = files.joinToString(",") { f ->
            val path = "pack/${dirs.getValue(f.kind)}/${f.file}"
            """{"id":"${f.kind}/${f.file}","type":"${f.kind}","path":"$path","source":"${f.source}","sha256":"${sha256(f.data)}","size":${f.data.size},"summary":{"category":"Cat ${f.kind}"${f.extra}}}"""
        }
        val manifest = tamper("""{"format":"wifecraft-pack","schema":1,"name":"$name","entries":[$entries],"notes":[]}""")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("pack/manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
            for (f in files) {
                z.putNextEntry(ZipEntry("pack/${dirs.getValue(f.kind)}/${f.file}")); z.write(f.data); z.closeEntry()
            }
            for ((n, d) in extraZip) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
        }
        return out.toByteArray()
    }

    /** A zip of just [names] to bytes (for the zip safety tests). */
    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return out.toByteArray()
    }
}
