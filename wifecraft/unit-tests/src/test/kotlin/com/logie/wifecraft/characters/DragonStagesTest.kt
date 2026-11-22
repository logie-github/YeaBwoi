package com.logie.wifecraft.characters

import com.logie.wifecraft.anim.AnimArrays
import com.logie.wifecraft.anim.ClipMath
import com.logie.wifecraft.anim.ClipType
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.sims.Channel
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sindragosa's own stage: "Petting Sindragosa's head" as data, its baked clips, its place and its registration as a pair. */
class DragonStagesTest {
    private val s = DragonStages.PET_HEAD

    @Test fun theTableParsesAndIsConsistent() {
        assertEquals("sindragosa_pet_head", s.id); assertEquals("Petting Sindragosa's head", s.name)
        assertEquals(CreatureNpcs.SINDRAGOSA.character, s.creature)
        assertTrue(s.duration in 4f..30f && s.speed > 0f && s.fps in 5..60)
        for (t in s.creatureTracks) assertTrue(t.bone in s.creatureBones, t.bone)
        assertEquals(s.creatureFreeze.size, s.holdBones.size)
        // her neck chain is her model's own bones (frostwurmnorthrend.m2: 21, 24, 31 under the torso's 15, the head 42, the jaw 64)
        assertEquals(listOf(21, 24, 31, 42, 64), listOf("neck1", "neck2", "neck3", "head", "jaw").map { s.creatureBones.getValue(it) })
        assertEquals(42, s.reach.bone)
        assertTrue(s.reach.weight.all { it.value in 0f..1f })
        assertTrue(s.creatureHold.all { it.value in 0f..1f })
    }

    @Test fun herNeckLowersToTheHandAndComesBackSoTheLoopHasNoSeam() {
        val neck = listOf("neck1", "neck2", "neck3").map { n -> s.creatureTracks.first { it.bone == n } }
        // the chain's pitch at the middle of the loop: the head comes down to a person's reach, about 20 to 60 degrees in all
        val mid = neck.sumOf { ClipMath.sample(it, s.duration / 2, s.duration, true)[0].toDouble() }
        assertTrue(mid in 20.0..60.0, "pitch $mid")
        // and it is her own pose at both ends of the loop
        for (t in s.creatureTracks) {
            assertEquals(0f, ClipMath.sample(t, 0f, s.duration, true).sumOf { abs(it).toDouble() }.toFloat(), 1e-4f)
            assertEquals(0f, ClipMath.sample(t, s.duration, s.duration, true).sumOf { abs(it).toDouble() }.toFloat(), 1e-4f)
        }
        // the head tilts into the hand: it rolls toward it while the hand is on it
        val head = s.creatureTracks.first { it.bone == "head" }
        assertTrue(ClipMath.sample(head, s.duration / 2, s.duration, true)[2] > 2f)
        // the hold and the hand come and go together: nothing held at the ends, all of it while the hand is on
        assertEquals(0f, ClipMath.weight(s.creatureHold, 0f, s.duration, true), 1e-5f)
        assertEquals(1f, ClipMath.weight(s.creatureHold, s.duration / 2, s.duration, true), 1e-5f)
        assertEquals(0f, ClipMath.weight(s.reach.weight, 0f, s.duration, true), 1e-5f)
        assertEquals(1f, ClipMath.weight(s.reach.weight, s.duration / 2, s.duration, true), 1e-5f)
    }

    @Test fun theCreatureLayerIsRotationKeysByBoneIndexWithHeldBonesAndTheHoldWeight() {
        val a = s.creatureLayer()
        assertTrue(a.loop); assertEquals(s.seconds, a.duration, 1e-5f)
        val names = a.tracks.map { it.bone }
        for (n in listOf("#21", "#24", "#31", "#42", "#64")) assertTrue(n in names, n)
        for (b in s.holdBones) assertTrue("!#$b" in names, "!#$b")
        val hold = a.tracks.first { it.bone == "layer.hold" }
        assertEquals(Channel.POSITION, hold.channel)
        for (t in a.tracks.filter { it.channel == Channel.ROTATION }) {
            for (i in t.times.indices) assertEquals(1f, sqrt((0..3).sumOf { (t.values[i * 4 + it] * t.values[i * 4 + it]).toDouble() }).toFloat(), 1e-4f)
            assertTrue((1 until t.times.size).all { t.times[it] > t.times[it - 1] })
            assertTrue(t.times.isEmpty() || t.times.last() < a.duration)                // the loop's wrap comes after the last key
        }
        // the engine's arrays take it whole
        val arrays = AnimArrays.of(a)
        assertEquals(a.tracks.size, arrays.bones.size); assertTrue(arrays.loop)
        assertTrue(arrays.times.all { it.isFinite() } && arrays.values.all { it.isFinite() })
    }

    @Test fun amountIsThePerStageHeadHeightAndSpeedThePace() {
        fun peak(amount: Float): Float {
            val a = s.creatureLayer(amount); val t = a.tracks.first { it.bone == "#24" }
            return (0 until t.times.size).maxOf { 2f * kotlin.math.asin(abs(t.values[it * 4 + 1]).coerceIn(0f, 1f)) }
        }
        assertEquals(peak(1f) * 0.5f, peak(0.5f), 0.01f)
        assertEquals(0f, peak(0f), 1e-5f)
        val fast = CreatureStage.parse(DragonStages.PET_HEAD_JSON.replace("\"speed\": 1.0", "\"speed\": 2.0"))
        assertEquals(s.seconds / 2, fast.seconds, 1e-5f)
        assertEquals(s.seconds / 2, fast.creatureLayer().duration, 1e-5f)
        assertEquals(s.seconds / 2, fast.reachKeys().duration, 1e-5f)
        assertEquals(s.seconds / 2, fast.personClip().duration, 1e-5f)
    }

    @Test fun thePersonPlaysALeanTheHeadUpToHerAndFlatFingersAndTheirArmIsLeftToTheReach() {
        val p = s.personClip()
        val names = p.tracks.map { it.bone }.toSet()
        assertTrue(names.containsAll(setOf("Spine1", "Spine2", "Neck1", "Head")))
        assertTrue(names.none { it == "R_UpperArm" || it == "R_Forearm" || it == "R_Hand" }, "the right arm is the reach's, not the clip's")
        assertEquals(s.seconds, p.duration, 1e-5f)
        // a clip that moves and closes on itself: a loop to the importer's own inspection
        assertEquals(ClipType.LOOP, com.logie.wifecraft.anim.ClipInspection.inspect(p).type)
    }

    @Test fun theReachIsOneHandOnHerHeadWithAStrokeAlongItThatStartsAndEndsGently() {
        assertTrue(!s.reach.left)
        val r = s.reach
        val base = r.offsetAt(0f, s.duration)
        assertEquals(r.offset, base.toList())
        val stroke = r.stroke
        assertNotNull(stroke)
        // before and after the stroke the hand rests on the base point; inside it swings by at most the amplitude
        assertEquals(r.offset, r.offsetAt(stroke.from - 0.1f, s.duration).toList())
        assertEquals(r.offset, r.offsetAt(stroke.to + 0.1f, s.duration).toList())
        var farthest = 0f
        var t = stroke.from
        while (t <= stroke.to) {
            val o = r.offsetAt(t, s.duration)
            val d = sqrt((0..2).sumOf { ((o[it] - r.offset[it]) * (o[it] - r.offset[it])).toDouble() }).toFloat()
            val axisLen = sqrt(stroke.axis.sumOf { (it * it).toDouble() }).toFloat()
            assertTrue(d <= stroke.amplitude * axisLen + 1e-4f)
            farthest = maxOf(farthest, d); t += 0.05f
        }
        assertTrue(farthest > stroke.amplitude * 0.5f, "the hand strokes")
        // the keys: weight, offset (per key), along and pole (one each)
        val keys = s.reachKeys()
        assertEquals(setOf("reach.w", "reach.off", "reach.along", "reach.pole"), keys.tracks.map { it.bone }.toSet())
        assertTrue(keys.loop)
        val off = keys.tracks.first { it.bone == "reach.off" }
        assertEquals(off.times.size * 3, off.values.size)
        assertTrue(off.values.all { it.isFinite() })
        assertEquals(1, keys.tracks.first { it.bone == "reach.along" }.times.size)
    }

    @Test fun itIsRegisteredAsAFloorPairOnlyHerStageWithThePersonActive() {
        val pair = s.pair()
        assertEquals("sindragosa", pair.exclusiveTo)
        assertTrue(pair.locations.isEmpty())              // no furniture: the open floor
        assertTrue(pair.loops); assertTrue(pair.enabled)
        assertEquals("creature", pair.kind)
        // her slot is where the table puts her, in the library's units (yards over HumanScale.PAIR)
        assertEquals(s.creatureAt[0], pair.offsetB.x * HumanScale.PAIR, 1e-4f)
        assertEquals(s.creatureAt[1], pair.offsetB.y * HumanScale.PAIR, 1e-4f)
        assertEquals(180f, pair.offsetB.yawDeg)
        assertEquals(0f, pair.offsetA.x); assertEquals(0f, pair.offsetA.yawDeg)
        // the browser offers it as an ordinary two-person animation that can be played
        val choice = pair.choice()
        assertEquals(2, choice.participantCount); assertTrue(choice.playable)
        assertEquals("sindragosa", choice.exclusiveTo)
        assertNotNull(choice.clips[0].forPlayback()); assertNotNull(choice.clips[1].forPlayback())
        // and the simulation takes it as a partner pair that carries the exclusivity
        val pp = PartnerPair.of(pair)
        assertEquals("sindragosa", pp.exclusiveTo)
        assertEquals(pair.id, pp.id)
        assertNull(PartnerPair.of(pair.copy(exclusiveTo = null)).exclusiveTo)
    }

    @Test fun readFromItsBodiesItNeedsNoFurniture() {
        // the simulation reads a pair's bodies for what it needs (a bed, a seat, the floor): this one stands on the floor
        val r = com.logie.wifecraft.simulation.FurnitureAnimation.of(PartnerPair.of(s.pair())).requirement
        assertTrue(r.surface in com.logie.wifecraft.anim.Requirement.FLOOR_LEVEL || r.surface == com.logie.wifecraft.anim.Surface.UNKNOWN,
            "surface ${r.surface}")
    }

    @Test fun playbackKeysNameTheStageAndItsRole() {
        assertEquals(s.id to 0, DragonStages.ofKey("pair:${s.id}:0")?.let { it.first.id to it.second })
        assertEquals(1, DragonStages.ofKey("pair:${s.id}:1")?.second)
        assertNull(DragonStages.ofKey("pair:other:0")); assertNull(DragonStages.ofKey("emote:3")); assertNull(DragonStages.ofKey("pair:${s.id}:x"))
        assertEquals(s, DragonStages.of(s.id)); assertNull(DragonStages.of(null)); assertNull(DragonStages.of("nope"))
    }
}
