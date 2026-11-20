package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.SimsReader
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The requirement classifier on synthetic bodies (PoseFixtures: the reference WoW skeleton posed by a few world rotations),
 * its hints (tuning locations, names, posture words), the stored "requires" field and its cache. The user's sitting pose
 * pack is read only when WIFECRAFT_SIMS_SAMPLE names it.
 */
class RequirementsTest {
    private val F = PoseFixtures

    private fun one(a: Anim, hints: Hints = Hints()) = RequirementClassifier.classify(a, hints).also { assertEquals(1, it.actors.size) }

    @Test fun eachPostureIsReadFromTheBodyAlone() {
        data class Case(val anim: Anim, val posture: Posture, val surface: Surface, val metres: ClosedFloatingPointRange<Float>)
        val cases = listOf(
            Case(F.standing, Posture.STAND, Surface.NONE, 0f..0.05f),
            Case(F.sitting(0.47), Posture.SIT, Surface.ANY_SEAT, 0.4f..0.55f),
            Case(F.sitting(0.95), Posture.SIT_ON_SURFACE, Surface.TABLE_OR_COUNTER, 0.85f..1.0f),
            Case(F.sitting(0.05), Posture.SIT, Surface.FLOOR, 0f..0.15f),
            Case(F.lyingBack(0.55), Posture.LIE_BACK, Surface.BED, 0.5f..0.62f),
            Case(F.lyingBack(0.0), Posture.LIE_BACK, Surface.FLOOR, 0f..0.08f),
            Case(F.lyingFront(0.0), Posture.LIE_FRONT, Surface.FLOOR, 0f..0.08f),
            Case(F.kneeling(), Posture.KNEEL, Surface.FLOOR, 0f..0.05f),
            Case(F.kneeling(0.55), Posture.KNEEL, Surface.BED, 0.5f..0.6f),
            Case(F.allFours(), Posture.ALL_FOURS, Surface.FLOOR, 0f..0.05f),
            Case(F.leaning(), Posture.LEAN, Surface.TABLE_OR_COUNTER, 0.68f..0.82f),
        )
        for (c in cases) {
            val r = one(c.anim)
            val a = r.actors[0]
            assertEquals(c.posture, a.posture, "${c.anim.name}: ${a.reason}")
            assertEquals(c.surface, a.surface, "${c.anim.name}: ${a.reason}")
            assertEquals(c.surface, r.surface)
            assertTrue(a.height in c.metres, "${c.posture}: ${a.height} m not in ${c.metres}")
            assertTrue(a.confidence >= 0.7f, "${c.posture}: confidence ${a.confidence}")
            assertTrue(a.reason.isNotBlank() && r.basis == "geometry", r.basis)
        }
        assertTrue(one(F.sitting(0.47)).actors[0].feetOnFloor)
        assertFalse(one(F.sitting(0.95)).actors[0].feetOnFloor)
        assertTrue(one(F.leaning()).actors[0].feetOnFloor)
        // lying: the yaw is pelvis to head (the back lies head toward -x, the front toward +x)
        assertEquals(180f, kotlin.math.abs(one(F.lyingBack(0.0)).actors[0].yaw), 1f)
        assertEquals(0f, one(F.lyingFront(0.0)).actors[0].yaw, 1f)
    }

    @Test fun eachActorOfAPairHasItsOwnNeed() {
        // one lies on the bed, the other kneels on the floor at its side, facing it
        val bodies = listOf(BodyGeometry.frames(F.lyingBack(0.55)), BodyGeometry.frames(F.kneeling(0.0, dx = 0.0, facingDeg = 90.0).let {
            it.copy(tracks = it.tracks.map { t -> if (t.bone == "Root" && t.channel == Channel.POSITION) BoneTrack(t.bone, t.channel, t.times,
                floatArrayOf(0f, (-0.8 * BodyGeometry.REF_UNIT).toFloat(), t.values[2], 0f, (-0.8 * BodyGeometry.REF_UNIT).toFloat(), t.values[5])) else t })
        }))
        val r = RequirementClassifier.classify(bodies, Hints(listOf("bedside"), setOf("DOUBLE_BED")))
        assertEquals(Surface.BED, r.surface)
        assertEquals(Posture.LIE_BACK, r.actors[0].posture)
        assertEquals(Surface.BED, r.actors[0].surface)
        assertEquals(Posture.KNEEL, r.actors[1].posture)
        assertEquals(Surface.FLOOR, r.actors[1].surface, r.actors[1].reason)
        assertTrue("beside" in r.actors[1].reason, r.actors[1].reason)
        assertEquals(0, r.primary)
        assertEquals(-0.8f, r.actors[1].y, 0.05f)
        assertTrue("tuning" in r.basis, r.basis)
    }

    @Test fun theTuningAndTheNamesPickTheFurniture() {
        // a floor-level lie made for a bed: played on the bed
        val bed = one(F.lyingBack(0.0), Hints(listOf("loop"), setOf("DOUBLE_BED"))).actors[0]
        assertEquals(Surface.BED, bed.surface)
        assertTrue("lifted" in bed.reason, bed.reason)
        // a seat height named a sofa by the tuning, a chair by the name, nothing: any seat
        assertEquals(Surface.COUCH, one(F.sitting(0.45), Hints(locations = setOf("SOFA"))).actors[0].surface)
        assertEquals(Surface.CHAIR, one(F.sitting(0.45), Hints(listOf("creator:DiningChair_Talk_x"))).actors[0].surface)
        assertEquals(Surface.ANY_SEAT, one(F.sitting(0.45)).actors[0].surface)
        // the Sims clip's slot target names the object
        assertEquals(Surface.COUCH, one(F.sitting(0.45), Hints(slotNames = listOf("sofa_seat_slot"))).actors[0].surface)
        // a name that disagrees with the body: the body wins, with less confidence
        val plain = one(F.lyingBack(0.55)).actors[0]
        val wrong = one(F.lyingBack(0.55), Hints(listOf("standing_hug"))).actors[0]
        assertEquals(Posture.LIE_BACK, wrong.posture)
        assertTrue(wrong.confidence < plain.confidence)
        assertTrue("body wins" in wrong.reason, wrong.reason)
        // agreeing raises it
        assertTrue(one(F.lyingBack(0.55), Hints(listOf("Bed_Lying_Loop"))).actors[0].confidence >= plain.confidence)
        // a seat-height body the tuning calls a bed: on the bed's edge
        assertEquals(Surface.BED, one(F.sitting(0.47), Hints(locations = setOf("DOUBLE_BED"))).actors[0].surface)
        // a body the tuning can't hold (lying at bed height named a chair): the body wins
        val chair = one(F.lyingBack(0.55), Hints(locations = setOf("CHAIR"))).actors[0]
        assertEquals(Surface.BED, chair.surface)
    }

    @Test fun tokensAreWholeWordsAndCountsAreNotPoses() {
        assertEquals(listOf("lie", "on", "bed"), NameHints.tokens("lieOnBed").take(3))
        assertNull(NameHints.read(Hints(listOf("creator:PosePack_123_set_69"))).posture)
        assertEquals(Posture.LIE_BACK, NameHints.read(Hints(listOf("creator:Floor_69_loop"))).posture)
        assertEquals(Posture.ALL_FOURS, NameHints.read(Hints(listOf("all_fours_x"))).posture)
        assertEquals(Surface.BED, NameHints.read(Hints(listOf("x"), setOf("double_bed"))).surface)
        assertEquals(Surface.TABLE_OR_COUNTER, NameHints.read(Hints(listOf("x"), setOf("COUNTER"))).surface)
        assertEquals(Surface.FLOOR, NameHints.read(Hints(listOf("x"), setOf("FLOOR"))).surface)
        // "bedroom" is not "bed"
        assertNull(NameHints.read(Hints(listOf("Bedroom_wave"))).surface)
    }

    @Test fun aClipWithNoBodyFallsBackToItsNamesElseIsUnknown() {
        val hands = Anim("h", "h", "", 1f, true, listOf(BoneTrack("L_Hand", Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 1f))), "s", 0L)
        val unknown = one(hands)
        assertEquals(Posture.UNKNOWN, unknown.actors[0].posture)
        assertEquals(Surface.UNKNOWN, unknown.surface)
        assertTrue(unknown.confidence < 0.1f)
        val named = one(hands, Hints(listOf("Bed_Lying_x"))).actors[0]
        assertEquals(Posture.LIE_BACK, named.posture)
        assertEquals(Surface.BED, named.surface)
        assertTrue(named.confidence in 0.3f..0.5f)
        // a body whose legs aren't moved: the posture is read with half the trust when it needs the legs
        val noLegs = F.pose("sit", mapOf(), 0.6, legs = false)
        assertTrue(one(noLegs).actors[0].confidence < one(F.standing).actors[0].confidence)
    }

    @Test fun requirementsRoundTripAndOldFilesStillLoad() {
        val r = RequirementClassifier.classify(listOf(BodyGeometry.frames(F.lyingBack(0.55)), BodyGeometry.frames(F.kneeling())), Hints())
        val back = RequirementJson.fromJson(JSONObject(RequirementJson.toJson(r).toString()))
        assertEquals(r, back)
        // an Anim file with the new field reads as before; one without it is classified when loaded
        val anim = F.sitting(0.47).copy(id = "a1")
        val withField = RequirementJson.attach(AnimJson.toJson(anim), one(anim))
        assertEquals(anim.id, AnimJson.parse(withField.toString()).id)
        var classified = 0
        val stored = RequirementJson.resolve(withField) { classified++; one(anim) }
        assertEquals(0, classified)
        assertEquals(Posture.SIT, stored.actors[0].posture)
        val old = AnimJson.toJson(anim)
        assertFalse(old.has(RequirementJson.FIELD))
        RequirementJson.resolve(old) { classified++; one(anim) }
        assertEquals(1, classified)
        // a pair file too
        val pair = PairAnim("p1", "p", PairAnim.CATEGORY, F.lyingBack(0.55), F.kneeling(), PairOffset.ZERO, PairOffset(0f, -1f, 0f, 90f),
            true, setOf("BED"), emptyList(), source = "s", importedAt = 0L)
        val pairJson = RequirementJson.attach(PairJson.toJson(pair), RequirementClassifier.classify(pair))
        assertEquals("p1", PairJson.parse(pairJson.toString()).id)
        // a newer classifier's or a damaged field: classified again, never thrown
        assertNull(RequirementJson.fromJson(JSONObject().put("v", 99)))
        assertNull(RequirementJson.fromJson(JSONObject().put("v", 1).put("actors", "x")))
        assertNull(RequirementJson.fromJson(JSONObject().put("v", 1).put("surface", "SPACESHIP").put("actors", org.json.JSONArray())))
    }

    @Test fun theCacheKeepsResultsBesideTheLibraryWithoutDamagingIt() {
        val dir = Files.createTempDirectory("req").toFile()
        try {
            AnimLibrary.forgetCaches()
            val lib = AnimLibrary(dir)
            val a = lib.add(F.sitting(0.47))
            val cache = RequirementCache(dir)
            var runs = 0
            val first = cache.get(a.id, "k1") { runs++; one(a) }
            assertEquals(first, RequirementCache(dir).get(a.id, "k1") { runs++; one(a) })
            assertEquals(1, runs)
            RequirementCache(dir).get(a.id, "k2") { runs++; one(a) }
            assertEquals(2, runs)
            assertTrue(File(dir, RequirementCache.FILE).isFile)
            AnimLibrary.forgetCaches()
            val again = AnimLibrary(dir)
            assertEquals(1, again.list().size)
            assertTrue(again.damaged.isEmpty(), "${again.damaged}")
            // a damaged cache is made again
            File(dir, RequirementCache.FILE).writeText("{not json")
            assertEquals(Posture.SIT, RequirementCache(dir).requirement(a).actors[0].posture)
        } finally {
            dir.deleteRecursively()
            AnimLibrary.forgetCaches()
        }
    }

    /** The user's sitting pose pack: WIFECRAFT_SIMS_SAMPLE names it; skipped otherwise (the file is never committed). */
    @Test fun sampleSittingPoses() {
        val path = System.getenv("WIFECRAFT_SIMS_SAMPLE") ?: return
        val clips = SimsReader.clips(File(path).readBytes())
        assertEquals(15, clips.size)
        var seated = 0
        for (c in clips) {
            val r = RequirementClassifier.classify(c)
            val a = r.actors[0]
            println("sample ${c.name.substringAfterLast(':')}: ${a.posture} on ${a.surface} at %.2f m (confidence %.2f): ${a.reason}"
                .format(java.util.Locale.ROOT, a.height, a.confidence))
            assertTrue(BodyGeometry.frames(c).isNotEmpty(), "${c.name}: no body read")
            if (a.posture.seated) seated++
        }
        println("sample: $seated of ${clips.size} read as sitting")
        assertTrue(seated >= 13, "only $seated of 15 sitting poses read as sitting")
    }
}
