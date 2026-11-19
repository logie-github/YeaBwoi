package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.ActorSlot
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.ClipEvent
import com.logie.wifecraft.sims.PairDefinition
import com.logie.wifecraft.sims.RigBone
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsFixtures
import com.logie.wifecraft.sims.SimsHash
import com.logie.wifecraft.sims.SimsReader
import com.logie.wifecraft.sims.SimsRig
import com.logie.wifecraft.sims.SimsTrack
import com.logie.wifecraft.sims.SlotAssignment
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

/**
 * Partner animations: pairing (tuning, names, geometry, by hand), the pair conversion (shared origin, one horizontal
 * scale), the library's pairs and the pipeline, on synthetic clips and packages (no real two-actor mod was available).
 */
class PairTest {
    private val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
    private val profile = Profile.load(File(assets, "sims_profile.json").readText())
    private fun tmp() = Files.createTempDirectory("pair").toFile()

    private val rootBind = SimsHash.fnv32("b__ROOT_bind__")
    private val id = floatArrayOf(0f, 0f, 0f, 1f)
    private val half = floatArrayOf(0f, 1f, 0f, 0f)   // half a turn about Sims up

    /** A clip with the root at [from] then [to] (Sims metres), turned [q]; [events] as given. */
    private fun clip(name: String, actor: String, from: FloatArray, to: FloatArray = from, q: FloatArray = id, duration: Float = 1f,
                     events: List<ClipEvent> = emptyList(), slots: List<SlotAssignment> = emptyList()) = SimsClip(
        name, duration,
        listOf(
            SimsTrack(rootBind, Channel.POSITION, floatArrayOf(0f, duration), from + to),
            SimsTrack(rootBind, Channel.ROTATION, floatArrayOf(0f, duration), q + q),
        ),
        "6B20C4F3:00000000:${name.hashCode()}", actorName = actor, events = events, slots = slots,
    )

    // ---------------------------------------------------------------- pairing

    @Test fun theTuningPairsFirst() {
        val a = clip("test:kiss_one", "x", floatArrayOf(0.4f, 0.9f, 0f))
        val b = clip("test:kiss_two", "y", floatArrayOf(-0.4f, 0.9f, 0f))
        val solo = clip("test:wave", "x", floatArrayOf(0f, 0.9f, 0f))
        val def = PairDefinition("Kiss", listOf(ActorSlot(0, "test:kiss_one"), ActorSlot(1, "TEST:KISS_TWO")))
        val r = Pairing.find(listOf(solo, b, a), listOf(def))
        val f = r.pairs.single()
        assertEquals(Pairing.Mode.TUNING, f.mode)
        assertSame(a, f.a); assertSame(b, f.b); assertSame(def, f.def)
        assertEquals(listOf(solo), r.singles.ifEmpty { r.candidates })
        // a tuning entry for three actors, or with a missing clip: noted, its clips left to pair by hand
        val three = PairDefinition("Three", listOf(ActorSlot(0, "test:kiss_one"), ActorSlot(1, "test:kiss_two"), ActorSlot(2, "test:wave")))
        val r3 = Pairing.find(listOf(a, b, solo), listOf(three))
        assertTrue(r3.notes.single().contains("3 actors"))
        assertTrue(r3.pairs.isEmpty())
        assertEquals(3, r3.candidates.size)
        val missing = Pairing.find(listOf(a, solo), listOf(PairDefinition("M", listOf(ActorSlot(0, "test:kiss_one"), ActorSlot(1, "test:gone")))))
        assertTrue(missing.notes.single().contains("test:gone"))
        assertTrue(a in missing.candidates)
    }

    @Test fun theNamesPairNext() {
        val pairs = listOf("hug_x" to "hug_y", "Dance_A" to "Dance_B", "kiss_male" to "kiss_female", "lift_actor0" to "lift_actor1", "spinActor0" to "spinActor1", "sit_xx" to "sit_yy")
        for ((n0, n1) in pairs) {
            val a = clip("c:$n0", "x", floatArrayOf(0f, 0.9f, 0f))
            val b = clip("c:$n1", "y", floatArrayOf(0.5f, 0.9f, 0f), duration = 1f + 1f / 30f)   // a frame longer still pairs
            val r = Pairing.find(listOf(b, a), emptyList())
            val f = r.pairs.singleOrNull() ?: fail("$n0 / $n1 not paired")
            assertEquals(Pairing.Mode.NAME, f.mode, n0)
            assertSame(a, f.a, "actor 0 first: $n0")
        }
        assertEquals("c:kiss", Pairing.split("c:kiss_female")!!.first)
        // a pose pack's numbered poses by one actor are not a pair; neither are clips whose lengths differ
        val p0 = clip("pack:set_0", "x", floatArrayOf(0f, 0.5f, 0f)); val p1 = clip("pack:set_1", "x", floatArrayOf(0.2f, 0.5f, 0f))
        val poses = Pairing.find(listOf(p0, p1), emptyList())
        assertTrue(poses.pairs.isEmpty())
        assertEquals(2, poses.singles.size)
        val long = Pairing.find(listOf(clip("c:hug_x", "x", floatArrayOf(0f, 1f, 0f)), clip("c:hug_y", "y", floatArrayOf(0.5f, 1f, 0f), duration = 2f)), emptyList())
        assertTrue(long.pairs.isEmpty())
        assertTrue(long.notes.single().contains("lengths differ"))
        assertEquals(2, long.candidates.size)
    }

    @Test fun theGeometryPairsTheLastTwo() {
        val a = clip("c:one", "x", floatArrayOf(0.3f, 0.9f, 0f))
        val b = clip("c:two", "y", floatArrayOf(-0.3f, 0.9f, 0.1f))
        val f = Pairing.find(listOf(b, a), emptyList()).pairs.single()
        assertEquals(Pairing.Mode.GEOMETRY, f.mode)
        assertSame(a, f.a)
        // the same actor name, roots on top of each other, or too far apart: not a pair, and with one actor not even a candidate
        val same = Pairing.find(listOf(a, clip("c:two", "x", floatArrayOf(-0.3f, 0.9f, 0f))), emptyList())
        assertTrue(same.pairs.isEmpty()); assertEquals(2, same.singles.size)
        assertTrue(Pairing.find(listOf(a, clip("c:two", "y", floatArrayOf(0.3f, 0.9f, 0f))), emptyList()).pairs.isEmpty())
        val far = Pairing.find(listOf(a, clip("c:two", "y", floatArrayOf(3f, 0.9f, 0f))), emptyList())
        assertTrue(far.pairs.isEmpty())
        assertEquals(2, far.candidates.size)   // two actor names in one package: offered to pair by hand
        // a single clip whose slots name another actor is a candidate too
        val lone = Pairing.find(listOf(clip("c:reach", "x", floatArrayOf(0f, 1f, 0f), slots = listOf(SlotAssignment(0, 0, "y", "b__R_Hand__")))), emptyList())
        assertEquals(1, lone.candidates.size)
    }

    // ---------------------------------------------------------------- the conversion

    /** A small rig: ROOT, ROOT_bind at [height] (Sims y), two spine bones above. */
    private fun rig(height: Float): SimsRig {
        fun b(n: String, parent: Int, y: Float) = RigBone(SimsHash.fnv32(n), n, parent, floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, y, 0f))
        return SimsRig(listOf(b("b__ROOT__", -1, 0f), b("b__ROOT_bind__", 0, height), b("b__Spine0__", 1, 0.1f), b("b__Spine1__", 2, 0.15f)))
    }

    private fun track(a: Anim, bone: String, ch: Channel) = a.tracks.single { it.bone == bone && it.channel == ch }

    @Test fun aPairKeepsItsOffsetsWithOneSharedScale() {
        val rigA = rig(1.0f)
        val rigB = rig(0.8f)   // a shorter Sims skeleton for actor B
        val a = clip("c:hold_x", "x", floatArrayOf(0.4f, 1.0f, 0f), floatArrayOf(0.4f, 1.0f, 0.1f),
            events = listOf(ClipEvent(ClipEvent.SOUND, 0.5f, "test_sound"), ClipEvent(ClipEvent.SOUND, 1000f, "never"), ClipEvent(19, 0f)))
        val b = clip("c:hold_y", "y", floatArrayOf(-0.4f, 0.8f, 0f), floatArrayOf(-0.4f, 0.8f, -0.1f), q = half, duration = 1f + 1f / 30f,
            events = listOf(ClipEvent(ClipEvent.SOUND, 0.25f, "test_sound_b")))
        val p = Retargeter(profile, rigA).convertPair(a, b, null, rigB)
        val sA = profile.rootHeight / 1.0
        val sB = profile.rootHeight / 0.8
        val shared = (sA + sB) / 2
        // Sims x (her left) is WoW y: A stands 0.4 m to the origin's left, B 0.4 m to its right, by the one shared scale
        assertEquals(0.4 * shared, p.offsetA.y.toDouble(), 1e-4)
        assertEquals(-0.4 * shared, p.offsetB.y.toDouble(), 1e-4)
        assertEquals(0.0, (p.offsetA.y + p.offsetB.y).toDouble(), 1e-4)
        assertEquals(0.0, p.offsetA.x.toDouble(), 1e-4); assertEquals(0.0, p.offsetB.x.toDouble(), 1e-4)
        assertEquals(0.0, p.offsetA.yawDeg.toDouble(), 1e-3)
        assertEquals(180.0, abs(p.offsetB.yawDeg.toDouble()), 1e-3, "B faces A")
        // each walks 0.1 m forward in its own facing: the same distance in WoW for both skeletons
        val ra = track(p.a, "Root", Channel.POSITION).values
        val rb = track(p.b, "Root", Channel.POSITION).values
        assertEquals(0f, ra[0], 1e-5f); assertEquals(0f, ra[1], 1e-5f)   // the start is in the offset, not the track
        assertEquals(0.1 * shared, ra[3].toDouble(), 1e-4)
        assertEquals(0.1 * shared, rb[3].toDouble(), 1e-4)
        assertEquals(0.0, rb[4].toDouble(), 1e-4)
        // the facing is in the offset: B's root track is unturned
        val qb = track(p.b, "Root", Channel.ROTATION).values
        assertEquals(1.0, abs(qb[3].toDouble()), 1e-4)
        // where B ends up in the world (the origin at 0, facing +x): its offset, then its move turned by its yaw
        val yaw = Math.toRadians(p.offsetB.yawDeg.toDouble())
        val endX = p.offsetB.x + rb[3] * kotlin.math.cos(yaw)
        assertEquals(-0.1 * shared, endX, 1e-4)
        // one length (B's extra frame trimmed), the sounds inside it in time order, Partner by default
        assertEquals(1f, p.a.duration); assertEquals(1f, p.b.duration)
        assertEquals(listOf(SoundEvent(0.25f, "test_sound_b", 1), SoundEvent(0.5f, "test_sound", 0)), p.sounds)
        assertEquals("Partner", p.category)
        assertEquals("hold", p.name)
        assertEquals(PairAnim.sourceOf(b.sourceKey, a.sourceKey), p.source)
        assertTrue(p.enabled)
    }

    @Test fun theTuningsOffsetIsAddedAndLengthsMustAgree() {
        val r = Retargeter(profile, rig(1f))
        val a = clip("c:a_x", "x", floatArrayOf(0.4f, 1f, 0f))
        val b = clip("c:a_y", "y", floatArrayOf(-0.4f, 1f, 0f), q = half)
        val def = PairDefinition("Tuned", listOf(ActorSlot(0, "c:a_x"), ActorSlot(1, "c:a_y", x = 0.1f, angle = 90f)), loops = true, locations = setOf("SOFA", "FLOOR"))
        val p = r.convertPair(a, b, def)
        val s = profile.rootHeight.toDouble()
        assertEquals(0.4 * s, p.offsetA.y.toDouble(), 1e-4)
        // the tuning's nudge outermost: 0.1 m to the left, a quarter turn; then the clip's own start inside it
        assertEquals(-90.0, p.offsetB.yawDeg.toDouble(), 1e-3)   // 90 + 180, wrapped
        assertEquals(0.4 * s, p.offsetB.x.toDouble(), 1e-4)      // the clip's -0.4 m to the right, turned a quarter: +x
        assertEquals(0.1 * s, p.offsetB.y.toDouble(), 1e-4)
        assertTrue(p.loops); assertTrue(p.a.loop && p.b.loop)
        assertEquals(setOf("COUCH"), p.locations)
        assertEquals("Tuned", p.name)
        val e = assertFailsWith<IllegalArgumentException> { r.convertPair(a, clip("c:a_y", "y", floatArrayOf(0f, 1f, 0f), duration = 1.5f), null) }
        assertTrue("lengths differ" in e.message!!, e.message)
        assertFailsWith<IllegalStateException> { Retargeter(profile, null).convertPair(a, b, null) }
    }

    // ---------------------------------------------------------------- the library

    private fun pair(name: String, source: String, category: String = "Partner"): PairAnim {
        val t = listOf(BoneTrack("Root", Channel.POSITION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 0.1f, 0f, 0f)))
        return PairAnim("", name, category, Anim("", "$name a", "", 1f, true, t, "s:a", 1L), Anim("", "$name b", "", 1f, true, t, "s:b", 1L),
            PairOffset(0f, 0.5f, 0f, 0f), PairOffset(0f, -0.5f, 0f, 180f), true, setOf("BED"), listOf(SoundEvent(0.5f, "s", 1)), true, source, 9L)
    }

    @Test fun pairsPersistWithTheDuplicatePolicy() {
        val d = tmp()
        val lib = AnimLibrary(d)
        lib.add(Anim("", "Sit", "Poses", 0.1f, false, emptyList(), "h:1", 1L))
        val p = lib.addPair(pair("Hug", "a+b"))
        assertTrue(File(d, "pair_${p.id}.json").isFile)
        val dup = lib.addPairResult(pair("Other", "a+b"))
        assertTrue(dup.duplicate); assertEquals(p.id, dup.pair.id)
        assertEquals("Hug (2)", lib.addPair(pair("hug", "c+d")).name.replace("hug", "Hug"))
        lib.renamePair(p.id, "Embrace"); lib.setPairCategory(p.id, "Close"); lib.setPairEnabled(p.id, false)
        AnimLibrary.forgetCaches()
        val again = AnimLibrary(d)
        val back = again.getPair(p.id)!!
        assertEquals(p.copy(name = "Embrace", category = "Close", enabled = false), back)
        assertEquals(2, again.pairs().size)
        assertEquals(listOf("Close", "Partner"), again.pairCategories())
        // the pairs are not singles: the singles' list and index are untouched by them
        assertEquals(listOf("Sit"), again.list().map { it.name })
        assertEquals(listOf("Sit"), again.index().map { it.name })
        assertTrue(again.damaged.isEmpty())
        again.deletePair(p.id)
        assertNull(again.getPair(p.id)); assertFalse(File(d, "pair_${p.id}.json").exists())
        assertFailsWith<NoSuchElementException> { again.renamePair("nope", "x") }
        assertFailsWith<IllegalArgumentException> { again.renamePair(again.pairs().first().id, " ") }
        // a damaged pair file is skipped, a newer version refused
        File(d, "pair_bad.json").writeText("{")
        File(d, "pair_new.json").writeText(PairJson.toJson(pair("N", "n").copy(id = "new")).put("v", 99).toString())
        AnimLibrary.forgetCaches()
        val third = AnimLibrary(d)
        assertEquals(1, third.pairs().size)
        assertEquals(setOf("pair_bad.json", "pair_new.json"), third.damagedPairs.toSet())
        assertTrue(third.damaged.isEmpty())
        // a single can never take a pair's file name
        assertFalse(third.add(Anim("pair_x", "Odd", "", 0.1f, false, emptyList(), "h:9", 1L)).id.startsWith("pair_"))
    }

    @Test fun aLibraryFromBeforePairsLoads() {
        val d = tmp()
        // as the app wrote it before partner animations: <id>.json and index.json, nothing else
        val old = Anim("abc", "Sit", "Poses", 0.1f, false, listOf(BoneTrack("Head", Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 1f))), "h:1", 1L)
        File(d, "abc.json").writeText(AnimJson.toJson(old).toString())
        File(d, "index.json").writeText("""{"v":1,"anims":[{"id":"abc","name":"Sit","category":"Poses"}]}""")
        AnimLibrary.forgetCaches()
        val lib = AnimLibrary(d)
        assertEquals(listOf("Sit"), lib.index().map { it.name })
        assertEquals(old, lib.get("abc"))
        assertTrue(lib.pairs().isEmpty())
        assertTrue(lib.pairCategories().isEmpty())
    }

    // ---------------------------------------------------------------- the pipeline

    /** Converts by the real Retargeter on a small rig: every synthetic clip has the root and the spine. */
    private val retarget = Retargeter(profile, rig(0.9f))

    private fun runPkg(bytes: ByteArray): Pair<ImportResult, List<ImportEvent>> {
        val ev = ArrayList<ImportEvent>()
        val t = tmp()
        val r = ImportPipeline(retarget, t).runAll(listOf(ImportInput("pair.package") { bytes.inputStream() })) { ev += it }
        assertEquals(0, t.listFiles()!!.size, "temp folder cleaned")
        return r to ev
    }

    @Test fun aSyntheticTwoActorPackageImportsAsAPair() {
        val (r, ev) = runPkg(SimsFixtures.pairPackage())
        val p = r.pairs.single()
        assertTrue(r.anims.isEmpty(), "the pair's clips are not also singles")
        val paired = ev.filterIsInstance<ImportEvent.Paired>().single()
        assertEquals(Pairing.Mode.TUNING, paired.mode)
        assertEquals("Test Embrace", p.name)
        assertEquals(setOf("COUCH"), p.locations)
        assertTrue(p.loops)
        assertEquals(listOf("test_sound_b" to 1, "test_sound" to 0), p.sounds.map { it.name to it.actor })
        val s = profile.rootHeight / 0.9
        // A 0.4 m to the left; B 0.4 m to the right, nudged 0.1 m left by the tuning, facing A (10-bit curves: 3e-3 m)
        assertEquals(0.4 * s, p.offsetA.y.toDouble(), 3e-3 * s)
        assertEquals(-0.3 * s, p.offsetB.y.toDouble(), 3e-3 * s)
        assertEquals(180.0, abs(p.offsetB.yawDeg.toDouble()), 0.5)
        // without the tuning, the names pair them; renamed, the actor names and start positions do
        val (byName, _) = runPkg(SimsFixtures.pairPackage(withTuning = false))
        assertEquals("pair", byName.pairs.single().name)
        val (byShape, ev3) = runPkg(SimsFixtures.pairPackage(withTuning = false, nameA = "test:lead", nameB = "test:follow"))
        assertEquals(Pairing.Mode.GEOMETRY, ev3.filterIsInstance<ImportEvent.Paired>().single().mode)
        assertEquals(1, byShape.pairs.size)
        // the same actor name for both and no matching names: two singles offered to pair by hand
        val (loose, ev4) = runPkg(SimsFixtures.pairPackage(withTuning = false, nameA = "test:lead", nameB = "test:follow_b", actorB = "x"))
        assertTrue(loose.pairs.isEmpty())
        assertEquals(2, loose.anims.size)
        assertEquals(2, loose.unpaired.size, ev4.toString())
        // paired by hand: the same conversion
        val hand = ImportPipeline(retarget, tmp()).pairByHand(loose.unpaired[0].clip, loose.unpaired[1].clip)
        assertEquals(0.4 * s, hand.offsetA.y.toDouble(), 3e-3 * s)
        val lib = AnimLibrary(tmp())
        assertFalse(lib.addPairResult(p).duplicate)
        assertTrue(lib.addPairResult(byName.pairs.single()).duplicate, "the same two clips again")
    }

    @Test fun aPackageOfOneClipStaysASingle() {
        val one = SimsFixtures.dbpf(SimsFixtures.Res(com.logie.wifecraft.sims.ClipDecoder.TYPE, 1,
            SimsFixtures.actorClip("test:solo_x", "x", floatArrayOf(0f, 0.9f, 0f), floatArrayOf(0f, 0.9f, 0f), SimsFixtures.IDENTITY)))
        val (r, ev) = runPkg(one)
        assertEquals(listOf("solo_x"), r.anims.map { it.name })
        assertTrue(r.pairs.isEmpty()); assertTrue(r.unpaired.isEmpty())
        assertTrue(ev.none { it is ImportEvent.Paired || it is ImportEvent.Unpaired })
        assertEquals(1, SimsReader.clips(one).size)
    }
}
