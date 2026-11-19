package com.logie.wifecraft.sims

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Synthetic Sims 4 packages for the tests, written in the layout s4pi documents and the sample pose pack showed:
 * whole CLIP headers (actor, initial offset, explicit namespaces, slot assignments, events), the S3Clip codec, DBPF
 * packages and snippet tuning. These test our reading of our understanding of the format, not real two-actor mods
 * (none were available).
 */
internal object SimsFixtures {
    class Buf {
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
    class Chan(val target: Int, val offset: Float, val scale: Float, val type: Int, val sub: Int, val frames: List<Triple<Int, Int, ByteArray>>)

    fun codec(chans: List<Chan>, ticks: Int, name: String = "clipname", source: String = "pair.blend"): ByteArray {
        val chOff = 48
        val nameOff = chOff + chans.size * 20
        val srcOff = nameOff + name.length + 1
        val palOff = srcOff + source.length + 1
        var dataOff = palOff
        val b = Buf().bytes("_pilC3S_".toByteArray()).u32(2).u32(0).f32(1f / 30f).u16(ticks).u16(0)
            .u32(chans.size).u32(0).u32(chOff).u32(palOff).u32(nameOff).u32(srcOff)
        for (c in chans) {
            b.u32(dataOff).u32(c.target).f32(c.offset).f32(c.scale).u16(c.frames.size).u8(c.type).u8(c.sub)
            dataOff += c.frames.sumOf { 4 + it.third.size }
        }
        b.bytes(name.toByteArray()).u8(0).bytes(source.toByteArray()).u8(0)
        for (c in chans) for ((t, f, d) in c.frames) b.u16(t).u16(f).bytes(d)
        return b.get()
    }

    /** An event record: type, size, then u32 1, u32 0, the time, [payload]. */
    class Ev(val type: Int, val time: Float, val payload: ByteArray = ByteArray(0))

    fun fixed(s: String) = s.toByteArray(Charsets.US_ASCII).copyOf(128)
    fun sound(time: Float, name: String) = Ev(3, time, fixed(name))
    fun censor(time: Float, value: Float) = Ev(19, time, Buf().f32(value).get())

    fun clip(
        codec: ByteArray, name: String, actor: String = "x", duration: Float = 1f,
        q: FloatArray = floatArrayOf(0f, 0f, 0f, 1f), t: FloatArray = floatArrayOf(0f, 0f, 0f),
        namespaces: List<String> = emptyList(), slots: List<SlotAssignment> = emptyList(), events: List<Ev> = emptyList(),
    ): ByteArray {
        val b = Buf().u32(14).u32(0).f32(duration)
        q.forEach { b.f32(it) }; t.forEach { b.f32(it) }
        b.u32(0).u32(0x811C9DC5.toInt()).u32(0x811C9DC5.toInt()).u32(0x811C9DC5.toInt())
        b.str32(name).str32(actor)
        b.u32(namespaces.size); namespaces.forEach { b.str32(it) }
        b.u32(slots.size); slots.forEach { b.u16(it.chain).u16(it.slot).str32(it.actor).str32(it.joint) }
        b.u32(events.size)
        for (e in events) b.u32(e.type).u32(12 + e.payload.size).u32(1).u32(0).f32(e.time).bytes(e.payload)
        b.u32(codec.size).bytes(codec)
        return b.get()
    }

    class Res(val type: Int, val instance: Long, val data: ByteArray)

    fun dbpf(vararg rs: Res): ByteArray {
        val body = ByteArrayOutputStream()
        val stored = rs.map { r ->
            val raw = Deflater().run { setInput(r.data); finish(); val out = ByteArray(r.data.size + 64); val n = deflate(out); end(); out.copyOf(n) }
            val off = 96 + body.size()
            body.write(raw)
            Triple(r, off, raw.size)
        }
        val idx = Buf().u32(0)
        for ((r, off, size) in stored) {
            idx.u32(r.type).u32(0).u32((r.instance ushr 32).toInt()).u32(r.instance.toInt()).u32(off).u32(size or Int.MIN_VALUE).u32(r.data.size)
                .u16(0x5A42).u16(1)
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

    /** A type-18 frame (three 10-bit magnitudes and their signs) for (x, y, z) at [scale]. */
    fun pos(tick: Int, v: FloatArray, scale: Float): Triple<Int, Int, ByteArray> {
        var flags = 0
        var packed = 0
        for (k in 0 until 3) {
            if (v[k] < 0) flags = flags or (1 shl k)
            packed = packed or ((abs(v[k]) / scale * 1023f).roundToInt().coerceIn(0, 1023) shl (10 * k))
        }
        return Triple(tick, flags, Buf().u32(packed).get())
    }

    /** A type-20 frame (four 12-bit magnitudes and their signs) for a quaternion. */
    fun rot(tick: Int, q: FloatArray): Triple<Int, Int, ByteArray> {
        var flags = 0
        val b = Buf()
        for (k in 0 until 4) {
            if (q[k] < 0) flags = flags or (1 shl k)
            b.u16((abs(q[k]) * 4095f).roundToInt().coerceIn(0, 4095))
        }
        return Triple(tick, flags, b.get())
    }

    val ROOT_BIND = SimsHash.fnv32("b__ROOT_bind__")
    val SPINE = SimsHash.fnv32("b__Spine0__")

    /**
     * One actor's clip of [ticks] frames: b__ROOT_bind__ from [from] to [to] (metres, Sims axes), turned [q] the
     * whole time, and the spine at rest.
     */
    fun actorClip(name: String, actor: String, from: FloatArray, to: FloatArray, q: FloatArray, ticks: Int = 31, duration: Float = 1f,
                  events: List<Ev> = emptyList(), slots: List<SlotAssignment> = emptyList()): ByteArray {
        val last = ticks - 1
        val chans = listOf(
            Chan(ROOT_BIND, 0f, 2f, 18, 1, listOf(pos(0, from, 2f), pos(last, to, 2f))),
            Chan(ROOT_BIND, 0f, 1f, 20, 2, listOf(rot(0, q), rot(last, q))),
            Chan(SPINE, 0f, 1f, 20, 2, listOf(rot(0, floatArrayOf(0f, 0f, 0f, 1f)))),
        )
        return clip(codec(chans, ticks), name, actor, duration, events = events, slots = slots)
    }

    /** A snippet tuning entry naming two actors' clips, in the element names a public WickedWhims guide gives (made up here). */
    fun tuning(clipA: String, clipB: String, name: String = "Test Embrace", xB: Float = 0f, loops: String = "3", locations: List<String> = listOf("SOFA")) =
        """<?xml version="1.0" encoding="utf-8"?>
<I c="TestAnimations" i="snippet" m="test.anims" n="test:pair_pack" s="1">
  <L n="animations_list">
    <U>
      <T n="animation_display_name">$name</T>
      <T n="animation_stage_name">embrace_start</T>
      <L n="animation_next_stages"><T>embrace_loop</T></L>
      <T n="animation_loops">$loops</T>
      <L n="animation_locations">${locations.joinToString("") { "<E>$it</E>" }}</L>
      <L n="animation_actors_list">
        <U><T n="actor_id">0</T><T n="animation_clip_name">$clipA</T></U>
        <U><T n="actor_id">1</T><T n="animation_clip_name">$clipB</T><T n="animation_x_offset">$xB</T><T n="animation_angle_offset">0</T></U>
      </L>
    </U>
  </L>
</I>""".toByteArray()

    /** Two actors facing each other 0.8 m apart around one origin, the second turned half round (about Sims up). */
    val FACING = floatArrayOf(0f, 1f, 0f, 0f)
    val IDENTITY = floatArrayOf(0f, 0f, 0f, 1f)

    fun pairPackage(withTuning: Boolean = true, nameA: String = "test:pair_x", nameB: String = "test:pair_y", actorB: String = "y"): ByteArray {
        val a = actorClip(nameA, "x", floatArrayOf(0.4f, 0.9f, 0f), floatArrayOf(0.4f, 0.9f, 0.1f), IDENTITY,
            events = listOf(sound(0.5f, "test_sound"), sound(1000f, "never_plays"), censor(0f, 1000f), Ev(99, 0.2f, ByteArray(20))),
            slots = listOf(SlotAssignment(0, 0, "y", "b__R_Hand__")))
        val b = actorClip(nameB, actorB, floatArrayOf(-0.4f, 0.9f, 0f), floatArrayOf(-0.4f, 0.9f, -0.1f), FACING,
            events = listOf(sound(0.25f, "test_sound_b")))
        val res = mutableListOf(Res(ClipDecoder.TYPE, 1, a), Res(ClipDecoder.TYPE, 2, b))
        if (withTuning) res += Res(AnimTuning.TYPE, 3, tuning(nameA, nameB, xB = 0.1f))
        return dbpf(*res.toTypedArray())
    }
}
