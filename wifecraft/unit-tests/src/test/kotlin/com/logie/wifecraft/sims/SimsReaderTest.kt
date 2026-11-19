package com.logie.wifecraft.sims

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic packages built here, in the layout the sample pose pack showed; the sample itself is read only when WIFECRAFT_SIMS_SAMPLE names it. */
class SimsReaderTest {
    // ---- builders -------------------------------------------------------------------------

    private class Buf {
        val o = ByteArrayOutputStream()
        val size get() = o.size()
        fun u8(v: Int) = apply { o.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        fun bytes(b: ByteArray) = apply { o.write(b) }
        fun str32(s: String) = apply { val b = s.toByteArray(); u32(b.size); bytes(b) }
        fun get(): ByteArray = o.toByteArray()
    }

    /** A channel: [frames] are (tick, flags, packed data bytes). */
    private class Chan(val target: Int, val offset: Float, val scale: Float, val type: Int, val sub: Int, val frames: List<Triple<Int, Int, ByteArray>>)

    private fun codec(chans: List<Chan>, palette: FloatArray = FloatArray(0), name: String = "clipname", source: String = "pose 1.blend", ticks: Int = 2): ByteArray {
        val chOff = 48 // the codec header is 48 bytes; every clip in the sample pose pack puts its channel table right after it, at 48
        val nameOff = chOff + chans.size * 20
        val srcOff = nameOff + name.length + 1
        val palOff = srcOff + source.length + 1
        var dataOff = palOff + palette.size * 4
        val b = Buf().bytes("_pilC3S_".toByteArray()).u32(2).u32(0).f32(1f / 30f).u16(ticks).u16(0)
            .u32(chans.size).u32(palette.size).u32(chOff).u32(palOff).u32(nameOff).u32(srcOff)
        for (c in chans) {
            b.u32(dataOff).u32(c.target).f32(c.offset).f32(c.scale).u16(c.frames.size).u8(c.type).u8(c.sub)
            dataOff += c.frames.sumOf { 4 + it.third.size }
        }
        b.bytes(name.toByteArray()).u8(0).bytes(source.toByteArray()).u8(0)
        palette.forEach { b.f32(it) }
        for (c in chans) for ((t, f, d) in c.frames) b.u16(t).u16(f).bytes(d)
        return b.get()
    }

    private fun clip(codec: ByteArray, name: String = "creator:PosePack_set_1", version: Int = 14, duration: Float = 0.0667f): ByteArray {
        val b = Buf().u32(version).u32(0).f32(duration)
            .f32(0f).f32(0f).f32(0f).f32(1f).f32(0f).f32(0f).f32(0f)
        if (version >= 5) b.u32(0)
        if (version >= 10) b.u32(0).u32(0)
        if (version >= 11) b.u32(0)
        if (version >= 7) b.str32(name)
        b.str32("x")
        b.u32(0) // explicit namespaces: none (contents are skipped by the reader either way)
        b.u32(0).u32(0) // IK slot assignments, events: stand-ins, not read
        b.u32(codec.size).bytes(codec)
        return b.get()
    }

    private class Res(val type: Int, val instance: Long, val data: ByteArray, val compression: Int = 0x5A42)

    private fun dbpf(vararg rs: Res, indexFlags: Int = 0): ByteArray {
        val body = ByteArrayOutputStream()
        val stored = rs.map { r ->
            val raw = when (r.compression) {
                0x5A42 -> Deflater().run { setInput(r.data); finish(); val out = ByteArray(r.data.size + 64); val n = deflate(out); end(); out.copyOf(n) }
                else -> r.data
            }
            val off = 96 + body.size()
            body.write(raw)
            Triple(r, off, raw.size)
        }
        val idx = Buf().u32(indexFlags)
        if (indexFlags and 2 != 0) idx.u32(0)
        for ((r, off, size) in stored) {
            idx.u32(r.type)
            if (indexFlags and 2 == 0) idx.u32(0)
            idx.u32((r.instance ushr 32).toInt()).u32(r.instance.toInt()).u32(off).u32(size or Int.MIN_VALUE).u32(r.data.size)
                .u16(r.compression).u16(1)
        }
        val index = idx.get()
        val h = Buf().bytes("DBPF".toByteArray()).u32(2).u32(1)
        while (h.size < 36) h.u32(0)
        h.u32(rs.size).u32(0).u32(index.size)
        while (h.size < 64) h.u32(0)
        h.u32(96 + body.size())
        while (h.size < 96) h.u32(0)
        return h.bytes(body.toByteArray()).bytes(index).get()
    }

    private fun f3(x: Int, y: Int, z: Int) = Buf().u32(x or (y shl 10) or (z shl 20)).get()
    private fun f4(a: Int, b: Int, c: Int, d: Int) = Buf().u16(a).u16(b).u16(c).u16(d).get()

    private val head = SimsHash.fnv32("b__Head__")
    private val spine = SimsHash.fnv32("b__Spine0__")

    private fun poseClip(): ByteArray = clip(codec(listOf(
        // position: offset 0.5, scale 0.5, components (1023, 0 negated, 512) -> (1.0, 0.5, ~0.75)
        Chan(head, 0.5f, 0.5f, 18, 1, listOf(Triple(0, 0b010, f3(1023, 0, 512)))),
        // rotation: 12-bit magnitudes with scale 1: (0, 0, -0.6, 0.8) at tick 0 and (0.6, 0, 0, 0.8) at tick 1
        Chan(head, 0f, 1f, 20, 2, listOf(Triple(0, 0b0100, f4(0, 0, 2457, 3276)), Triple(1, 0, f4(2457, 0, 0, 3276)))),
        // palette rotation (type 4): indices into [0, 1]
        Chan(spine, 0f, 1f, 4, 2, listOf(Triple(0, 0, f4(0, 0, 0, 1)))),
        Chan(spine, 0f, 1f, 18, 3, listOf(Triple(0, 0, f3(1, 1, 1)))), // scale: skipped
        Chan(spine, 0f, 1f, 19, 2, listOf(Triple(0, 0, f3(1, 1, 1)))), // 10-bit quaternion: skipped
    ), palette = floatArrayOf(0f, 1f)))

    // ---- tests ----------------------------------------------------------------------------

    @Test fun fnvMatchesTheGamesBoneHashes() {
        // Seen in the sample pose pack's clips (FNV-1 32 of the lower-cased name).
        assertEquals(0x57884bb9, SimsHash.fnv32("b__ROOT_bind__"))
        assertEquals(0x6fa96266, SimsHash.fnv32("b__Spine0__"))
        assertEquals(0x0f97b21b, SimsHash.fnv32("b__Head__"))
        assertEquals(SimsHash.fnv32("B__HEAD__"), SimsHash.fnv32("b__head__"))
    }

    @Test fun readsAPackageAndDecodesTracks() {
        val pkg = dbpf(Res(0x0BC4A5044.toInt(), 1, ByteArray(10)), Res(ClipDecoder.TYPE, 0x40B7C43E0158AB45, poseClip()))
        val d = Dbpf(pkg)
        assertEquals(2, d.entries.size)
        assertTrue(d.entries.all { it.compressed && it.compression == Dbpf.ZLIB })
        val clips = SimsReader.clips(pkg)
        assertEquals(1, clips.size)
        val c = clips[0]
        assertEquals("creator:PosePack_set_1", c.name)
        assertEquals("pose 1.blend", c.sourceAsset)
        assertEquals("6B20C4F3:00000000:40B7C43E0158AB45", c.sourceKey)
        assertEquals(0.0667f, c.duration, 1e-6f)
        assertEquals(3, c.tracks.size)
        assertEquals(2, c.notes.size, c.notes.toString())

        val pos = c.tracks.single { it.boneHash == head && it.channel == Channel.POSITION }
        assertEquals(1.0f, pos.values[0], 1e-5f)
        assertEquals(0.5f, pos.values[1], 1e-5f)
        assertEquals(0.5f + 0.5f * 512f / 1023f, pos.values[2], 1e-5f)

        val rot = c.tracks.single { it.boneHash == head && it.channel == Channel.ROTATION }
        assertContentEquals(floatArrayOf(0f, 1f / 30f), rot.times)
        assertEquals(-0.6f, rot.values[2], 1e-3f)
        assertEquals(0.8f, rot.values[3], 1e-3f)
        assertEquals(0.6f, rot.values[4], 1e-3f)
        for (f in 0 until 2) {
            val q = rot.values.copyOfRange(f * 4, f * 4 + 4)
            assertEquals(1f, sqrt(q.sumOf { (it * it).toDouble() }).toFloat(), 1e-5f)
        }

        val pal = c.tracks.single { it.boneHash == spine }
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 1f), pal.values)
    }

    @Test fun readsUncompressedRefPackAndConstantIndexFields() {
        // Command 0x1B 0x02: 3 literals ("abc"), then 9 bytes copied from 3 back; 0xFD: stop after 1 literal ("X").
        val refpack = Buf().u8(0x10).u8(0xFB).u8(0).u8(0).u8(13)
            .u8(0x1B).u8(0x02).bytes("abc".toByteArray()).u8(0xFD).bytes("X".toByteArray()).get()
        assertEquals("abcabcabcabcX", String(RefPack.decompress(refpack)))
        // 0xE0: 4 literals; 0xFD: stop after 1.
        val four = Buf().u8(0x10).u8(0xFB).u8(0).u8(0).u8(5).u8(0xE0).bytes("wxyz".toByteArray()).u8(0xFD).bytes("!".toByteArray()).get()
        assertEquals("wxyz!", String(RefPack.decompress(four)))

        // Index flag bit 1: the group is written once for every entry. Entries stored as given (no zlib).
        val pkg = dbpf(Res(0x220557DA, 7, "plain".toByteArray(), compression = 0), Res(0x220557DA, 8, refpack, compression = 0xFFFF), indexFlags = 2)
        val d = Dbpf(pkg)
        assertEquals(2, d.entries.size)
        assertEquals(8L, d.entries[1].instance)
        assertEquals("plain", String(d.read(d.entries[0])))
        assertEquals(Dbpf.REFPACK, d.entries[1].compression)
        assertEquals("abcabcabcabcX", String(d.read(d.entries[1])))
    }

    @Test fun readsARig() {
        val b = Buf().u32(4).u32(2).u32(2)
        fun bone(name: String, parent: Int, px: Float, rw: Float) {
            b.f32(px).f32(0f).f32(0f).f32(0f).f32(0f).f32(0f).f32(rw).f32(1f).f32(1f).f32(1f)
            b.str32(name).u32(-1).u32(parent).u32(SimsHash.fnv32(name)).u32(0)
        }
        bone("b__ROOT__", -1, 0f, 1f)
        bone("b__Head__", 0, 1.5f, 1f)
        b.str32("auRig").u32(0)
        val pkg = dbpf(Res(SimsRig.TYPE, 1, b.get()))
        val rig = SimsReader.rigs(pkg).single()
        assertEquals(2, rig.bones.size)
        val h = assertNotNull(rig.byHash(head))
        assertEquals("b__Head__", h.name)
        assertEquals(0, h.parent)
        assertEquals(1.5f, h.bindPosition[0])
        assertEquals(1f, h.bindRotation[3])
        assertEquals(1, rig.coverage(listOf(head, spine)))

        val f = Files.createTempFile("rig", ".package").toFile()
        try {
            f.writeBytes(pkg)
            assertEquals(2, SimsReader.rigs(f).single().bones.size)
        } finally { f.delete() }
    }

    @Test fun errorsAreActionable() {
        fun msg(block: () -> Unit) = assertFailsWith<SimsFormatException> { block() }.message!!
        assertTrue("Not a Sims 4 package" in msg { SimsReader.clips("hello world, not a package".toByteArray()) })
        assertTrue("ZIP" in msg { SimsReader.clips(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0, 0)) })
        assertTrue("no Sims 4 animation clips" in msg { SimsReader.clips(dbpf(Res(0x220557DA, 1, ByteArray(4)))) })
        assertTrue("no Sims 4 rig" in msg { SimsReader.rigs(dbpf(Res(ClipDecoder.TYPE, 1, poseClip()))) })
        assertTrue("version 99" in msg { ClipDecoder.decode(clip(codec(emptyList()), version = 99)) })
        // A clip whose header is whole but holds no codec block.
        assertTrue("no animation data" in msg { ClipDecoder.decode(clip(ByteArray(0))) })
        // A clip cut inside its header (here, inside the clip name's 22 bytes, which end at 82).
        assertTrue("cut short" in msg { ClipDecoder.decode(clip(ByteArray(0)).copyOf(80)) })
        // A clip cut short: the codec length points past the end.
        val full = poseClip()
        assertTrue("cut short" in msg { ClipDecoder.decode(full.copyOf(full.size - 10)) })
        // A package cut short.
        val pkg = dbpf(Res(ClipDecoder.TYPE, 1, poseClip()))
        msg { Dbpf(pkg.copyOf(pkg.size - 8)) }
        msg { Dbpf(pkg.copyOf(60)) }
        // One bad clip: clips() refuses, scan() keeps the good one.
        val two = dbpf(Res(ClipDecoder.TYPE, 1, poseClip()), Res(ClipDecoder.TYPE, 2, full.copyOf(full.size - 10)))
        assertTrue("cannot be read" in msg { SimsReader.clips(two) })
        val s = SimsReader.scan(two)
        assertEquals(1, s.clips.size)
        assertEquals(1, s.failures.size)
        assertTrue(s.failures[0].first.endsWith("0000000000000002"))
    }

    @Test fun streamedImportsReadVersion18AndContinuePastAnUnsupportedClip() {
        val bytes = poseClip()
        val version18 = bytes.copyOf().apply { this[0] = 18 }
        val incompatible = bytes.copyOf().apply { this[0] = 99 }
        val humanoid = listOf("b__ROOT_bind__", "b__Pelvis__", "b__Head__", "b__L_Thigh__", "b__R_Thigh__")
        fun rig(names: List<String>): ByteArray {
            val b = Buf().u32(4).u32(2).u32(names.size)
            for ((i, name) in names.withIndex()) {
                repeat(6) { b.f32(0f) }
                repeat(4) { b.f32(1f) }
                b.str32(name).u32(-1).u32(if (i == 0) -1 else 0).u32(SimsHash.fnv32(name)).u32(0)
            }
            return b.get()
        }
        val pkg = dbpf(
            Res(ClipDecoder.TYPE, 1, incompatible),
            Res(SimsRig.TYPE, 2, rig(listOf("b__Root__", "b__Head__"))),
            Res(ClipDecoder.TYPE, 3, version18),
            Res(SimsRig.TYPE, 4, rig(humanoid)),
        )
        val file = Files.createTempFile("mod-import", ".package").toFile()
        try {
            file.writeBytes(pkg)
            DbpfFile(file).use { disk ->
                assertEquals(humanoid.size, SimsReader.humanoidRigs(disk).single().bones.size)
                val clips = mutableListOf<SimsClip>()
                val failures = mutableListOf<String>()
                assertEquals(2, SimsReader.visitClips(disk, { clips += it }, { key, _ -> failures += key }))
                assertTrue(failures.single().endsWith("0000000000000001"))
                assertEquals(ClipDecoder.decode(bytes).name, clips.single().name)
                assertEquals(ClipDecoder.decode(bytes).tracks.size, clips.single().tracks.size)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun garbageNeverCrashes() {
        val rnd = Random(1357)
        val good = dbpf(Res(ClipDecoder.TYPE, 1, poseClip()), Res(SimsRig.TYPE, 2, ByteArray(40)))
        val clipBytes = poseClip()
        repeat(3000) {
            val pkg = good.copyOf()
            repeat(1 + rnd.nextInt(6)) { pkg[rnd.nextInt(pkg.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextBoolean()) pkg.copyOf(rnd.nextInt(pkg.size)) else pkg
            try { SimsReader.scan(cut) } catch (_: SimsFormatException) {}
            try { SimsReader.rigs(cut) } catch (_: SimsFormatException) {}
            val c = clipBytes.copyOf()
            repeat(1 + rnd.nextInt(4)) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() }
            try { ClipDecoder.decode(if (rnd.nextBoolean()) c.copyOf(rnd.nextInt(c.size)) else c) } catch (_: SimsFormatException) {}
            val r = ByteArray(rnd.nextInt(200)).also { rnd.nextBytes(it) }
            try { SimsRig.parse(r) } catch (_: SimsFormatException) {}
            try { RefPack.decompress(r) } catch (_: SimsFormatException) {}
        }
    }

    /** The user's pose pack: set WIFECRAFT_SIMS_SAMPLE to its path. Skipped otherwise; the file is never committed. */
    @Test fun samplePosePack() {
        val path = System.getenv("WIFECRAFT_SIMS_SAMPLE") ?: return
        val pkg = File(path).readBytes()
        val clips = SimsReader.clips(pkg)
        val raw = Dbpf(pkg).let { d -> d.entries.filter { it.type == ClipDecoder.TYPE }.map { ClipDecoder.decode(d.read(it), it.key, normalize = false) } }
        println("sample: ${clips.size} clips")
        val hashes = sortedSetOf<Long>()
        var worst = 0f
        for (c in raw) {
            for (t in c.tracks) {
                hashes += t.boneHash.toLong() and 0xFFFFFFFFL
                if (t.channel == Channel.ROTATION) for (f in t.times.indices) {
                    val q = t.values.copyOfRange(f * 4, f * 4 + 4)
                    worst = maxOf(worst, abs(sqrt(q.sumOf { (it * it).toDouble() }).toFloat() - 1f))
                }
            }
            val r = c.tracks.filter { it.channel == Channel.ROTATION }
            println("  ${c.name} (${c.sourceAsset}) ${c.duration}s: ${c.tracks.size} tracks, ${r.size} rotations; " +
                r.take(3).joinToString { "%08x=".format(it.boneHash) + it.values.take(4).joinToString(",", "(", ")") { v -> "%.4f".format(v) } } +
                if (c.notes.isEmpty()) "" else " notes=${c.notes}")
        }
        println("  ${hashes.size} bone hashes: " + hashes.joinToString(" ") { "%08x".format(it) })
        println("  worst |q|-1 before normalising: $worst")
        assertEquals(15, clips.size)
        // Every pose is a whole body: 96 bones, each with a rotation (type 20) and a translation (type 18); nothing skipped.
        for (c in clips) {
            assertEquals(96, c.tracks.count { it.channel == Channel.ROTATION }, c.name)
            assertEquals(96, c.tracks.count { it.channel == Channel.POSITION }, c.name)
            assertTrue(c.notes.isEmpty(), "${c.name}: ${c.notes}")
        }
        assertEquals(96, hashes.size)
        assertTrue(worst < 1e-3f, "a quaternion is not unit length: $worst")
        val known = listOf("b__ROOT_bind__", "b__Pelvis__", "b__Spine0__", "b__Head__", "b__L_Thigh__", "b__R_Hand__")
        for (n in known) assertTrue((SimsHash.fnv32(n).toLong() and 0xFFFFFFFFL) in hashes, "$n not found in the pack")
        // The poses differ: no two clips have the same rotations.
        val sigs = clips.map { c -> c.tracks.filter { it.channel == Channel.ROTATION }.sortedBy { it.boneHash }.flatMap { it.values.toList() } }
        assertEquals(clips.size, sigs.map { s -> s.map { (it * 1000).toInt() } }.toSet().size)
    }
}
