package com.logie.wifecraft.sims

import com.logie.wifecraft.sims.SimsFixtures.Buf
import com.logie.wifecraft.sims.SimsFixtures.Ev
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CLIP header read whole (actor, initial offset, namespaces, slots, events) and the partner tuning, on synthetic data. */
class PartnerClipTest {
    @Test fun theHeaderIsReadWhole() {
        val clips = SimsReader.clips(SimsFixtures.pairPackage())
        assertEquals(2, clips.size)
        val a = clips.single { it.name == "test:pair_x" }
        val b = clips.single { it.name == "test:pair_y" }
        assertEquals("x", a.actorName)
        assertEquals("y", b.actorName)
        assertEquals(1f, a.duration)
        assertEquals(1f / 30f, a.tickLength, 1e-7f)
        assertTrue(a.initialOffset.isIdentity)
        assertEquals(listOf(SlotAssignment(0, 0, "y", "b__R_Hand__")), a.slots)
        // the sound at 0.5 s, the censor at 0, and the unknown type 99 kept by number; the sound at 1000 s left out
        assertEquals(listOf(19, 99, 3), a.events.sortedBy { it.timeSec }.map { it.type })
        val sound = a.events.single { it.isSound }
        assertEquals("test_sound", sound.name)
        assertEquals(0.5f, sound.timeSec)
        assertEquals(1, a.eventsPastEnd)
        assertTrue(a.notes.isEmpty(), a.notes.toString())
        // the curves still decode after the events (the codec is found through the header, not by searching)
        val root = a.tracks.single { it.boneHash == SimsFixtures.ROOT_BIND && it.channel == Channel.POSITION }
        assertEquals(0.4f, root.values[0], 3e-3f)
        assertEquals(0.9f, root.values[1], 3e-3f)
        assertEquals(1f, root.times.last(), 1e-6f)
    }

    @Test fun anInitialOffsetAndNamespacesAreRead() {
        val codec = SimsFixtures.codec(listOf(SimsFixtures.Chan(SimsFixtures.SPINE, 0f, 1f, 20, 2, listOf(SimsFixtures.rot(0, SimsFixtures.IDENTITY)))), 2)
        val bytes = SimsFixtures.clip(codec, "c:offset", "y", 0.0667f, q = floatArrayOf(0f, 0.7071068f, 0f, 0.7071068f), t = floatArrayOf(0.5f, 0f, -0.25f),
            namespaces = listOf("x", "sofa"), events = listOf(Ev(14, 0.01f, SimsFixtures.fixed("modifier_sound") + Buf().u32(0x1234).u32(5).get())))
        val c = ClipDecoder.decode(bytes, "k")
        assertEquals("y", c.actorName)
        assertContentEquals(floatArrayOf(0.5f, 0f, -0.25f), c.initialOffset.translation)
        assertEquals(0.7071068f, c.initialOffset.rotation[1])
        assertEquals(listOf("x", "sofa"), c.explicitNamespaces)
        val e = c.events.single()
        assertEquals("modifier_sound", e.name)
        assertEquals(0x1234, e.actorHash)
        assertTrue(e.isSound)
    }

    @Test fun aDamagedEventListFallsBackToTheTagWithANote() {
        val good = SimsFixtures.pairPackage()
        val d = Dbpf(good)
        val raw = d.read(d.entries.first { it.type == ClipDecoder.TYPE })
        // the event count (after the one slot assignment) made huge: the header after the actor is not read, the curves still are
        val countAt = indexOf(raw, "b__R_Hand__".toByteArray()) + "b__R_Hand__".length
        val bad = raw.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(countAt, 999_999) }
        val c = ClipDecoder.decode(bad, "k")
        assertTrue(c.events.isEmpty())
        assertTrue(c.notes.any { "without events" in it }, c.notes.toString())
        assertTrue(c.tracks.isNotEmpty())
    }

    @Test fun theTuningNamesThePairsActors() {
        val defs = AnimTuning.pairs(SimsFixtures.pairPackage())
        val d = defs.single()
        assertEquals("Test Embrace", d.name)
        assertEquals(listOf("test:pair_x", "test:pair_y"), d.actors.map { it.clipName })
        assertEquals(listOf(0, 1), d.actors.map { it.actorId })
        assertEquals(0.1f, d.actors[1].x)
        assertEquals(true, d.loops)
        assertEquals(setOf("SOFA"), d.locations)
        assertEquals(listOf("embrace_loop"), d.stageLinks)
        assertEquals("embrace_start", d.stageName)
        // a pose list (no actors), a DOCTYPE, broken XML, no tuning: nothing, never an error
        assertTrue(AnimTuning.parse("<I n=\"p\"><L n=\"pose_list\"><T><T n=\"pose_name\">a</T></T></L></I>").isEmpty())
        assertTrue(AnimTuning.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><I><L n=\"animation_actors_list\"><U><T n=\"animation_clip_name\">&e;</T></U></L></I>").isEmpty())
        assertTrue(AnimTuning.parse("<I><L n=\"animation_actors_list\"><U>").isEmpty())
        assertTrue(AnimTuning.pairs(SimsFixtures.pairPackage(withTuning = false)).isEmpty())
        assertTrue(AnimTuning.pairs("not a package".toByteArray()).isEmpty())
        // an entry whose actors name no clip is no entry; loops as words; a single value list
        val one = AnimTuning.parse("""<I><U><T n="animation_loops">False</T><T n="animation_locations">DOUBLE_BED, floor</T>
            <L n="animation_actors_list"><U><T n="animation_clip_name">a</T></U><U><T n="animation_clip_name">b</T></U></L></U>
            <U><L n="animation_actors_list"><U><T n="actor_id">0</T></U></L></U></I>""").single()
        assertEquals(false, one.loops)
        assertEquals(setOf("DOUBLE_BED", "FLOOR"), one.locations)
        assertEquals("a", one.name)
    }

    @Test fun garbageHeadersNeverCrash() {
        val rnd = Random(4242)
        val d = Dbpf(SimsFixtures.pairPackage())
        val raw = d.read(d.entries.first { it.type == ClipDecoder.TYPE })
        repeat(3000) {
            val c = raw.copyOf()
            repeat(1 + rnd.nextInt(4)) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() }
            try { ClipDecoder.decode(if (rnd.nextBoolean()) c.copyOf(rnd.nextInt(c.size)) else c) } catch (_: SimsFormatException) {}
        }
        val xml = SimsFixtures.tuning("a", "b")
        repeat(500) {
            val x = xml.copyOf()
            repeat(1 + rnd.nextInt(4)) { x[rnd.nextInt(x.size)] = rnd.nextInt(256).toByte() }
            AnimTuning.parse(if (rnd.nextBoolean()) x.copyOf(rnd.nextInt(x.size)) else x)
        }
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }
        error("not found")
    }
}
