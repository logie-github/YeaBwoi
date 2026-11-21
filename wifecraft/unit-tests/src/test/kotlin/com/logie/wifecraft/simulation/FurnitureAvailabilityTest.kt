package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.Hints
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.location.LocationInfo
import java.io.File
import kotlin.test.*

/**
 * How each animation plays in a scene (FurnitureAvailability): Ready where they are, on furniture it fits (or nearly fits), or
 * on the floor in place of furniture the scene hasn't got, with its notice; the player's Treat as, kept; the animation window's
 * counter and filter; auto-play's floor options. Synthetic bodies (anim/PoseFixtures) on synthetic and measured furniture
 * (assets/locations.json); nothing here has run on a phone.
 */
class FurnitureAvailabilityTest {
    private val F = PoseFixtures
    private val unit = SceneSimulation.HUMAN_BODY_UNIT

    private fun moved(a: Anim, dx: Double, dy: Double): Anim = a.copy(tracks = a.tracks.map { t ->
        if (t.bone == "Root" && t.channel == Channel.POSITION) {
            val v = t.values.copyOf()
            for (k in t.times.indices) { v[k * 3] += (dx * BodyGeometry.REF_UNIT).toFloat(); v[k * 3 + 1] += (dy * BodyGeometry.REF_UNIT).toFloat() }
            BoneTrack(t.bone, t.channel, t.times, v)
        } else t
    })

    private fun bodies(vararg actors: Anim) = actors.map { BodyGeometry.frames(it) }
    private fun req(vararg actors: Anim): Requirement = RequirementClassifier.classify(bodies(*actors), Hints())
    private fun piece(kind: FurnitureKind, surface: Float? = null) = Furniture(kind.name, FurnitureSpec.standard(kind, surface = surface), 0f, 0f, 0f, 0f)

    private val lying = arrayOf(F.lyingBack(0.55), moved(F.lyingBack(0.55), 0.0, 0.6))
    private val bedPair get() = req(*lying)
    private val standingPair get() = req(F.standing, moved(F.standing, 0.6, 0.0))
    private val floorPair get() = req(F.lyingBack(0.0), moved(F.lyingFront(0.0), -0.5, 0.0))

    @Test fun eachClassPlaysReadyOnFurnitureOrOnTheFloor() {
        val bed = piece(FurnitureKind.BED_DOUBLE)
        val chair = piece(FurnitureKind.CHAIR)
        // standing and floor ones: ready anywhere, nothing needed
        for (r in listOf(standingPair, floorPair)) for (scene in listOf(emptyList(), listOf(bed))) {
            assertEquals(PlayState.READY, FurnitureAvailability.of(r, scene, unit).state)
        }
        // a bed one: on the bed where there is one, else on the floor with its notice
        val on = FurnitureAvailability.of(bedPair, listOf(chair, bed), unit)
        assertEquals(PlayState.ON_FURNITURE, on.state); assertSame(bed, on.furniture); assertFalse(on.relaxed)
        assertEquals("On the double bed", on.chip)
        val none = FurnitureAvailability.of(bedPair, emptyList(), unit)
        assertEquals(PlayState.FLOOR, none.state)
        assertEquals("No bed here: playing on the floor.", none.notice)
        assertEquals("Needs a bed: floor", none.chip)
        // a chair only (nothing that takes two lying): the floor, and the notice says the chair doesn't take it
        val chairOnly = FurnitureAvailability.of(bedPair, listOf(chair), unit)
        assertEquals(PlayState.FLOOR, chairOnly.state)
        assertEquals("Nothing here fits it as a bed: playing on the floor.", chairOnly.notice)
        // a seat one with no seat at all; a table one with no table
        val sitting = req(F.sitting(0.45))
        assertEquals("No seat here: playing on the floor.", FurnitureAvailability.of(sitting, emptyList(), unit).notice)
        assertEquals("No table here: playing on the floor.", FurnitureAvailability.of(req(F.leaning()), emptyList(), unit).notice)
        // read from no body at all (UNKNOWN): ready, never blocked
        val blank = RequirementClassifier.classify(listOf(emptyList()), Hints())
        assertEquals(Surface.UNKNOWN, blank.surface)
        assertEquals(PlayState.READY, FurnitureAvailability.of(blank, emptyList(), unit).state)
    }

    @Test fun aRiderAstrideRestsOnThePartnerNotOnTheBed() {
        // one lying on her back on the bed, one kneeling astride over her hips (his support 0.2 m over hers): he rests on her,
        // not on the mattress, so every bed takes it at its own height (each place's measured bed: the tall ones refused it)
        val pair = req(F.lyingBack(0.55), F.kneeling(0.75))
        assertTrue(FurnitureFit.onPartner(pair, 1)); assertFalse(FurnitureFit.onPartner(pair, 0))
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        var beds = 0
        for (loc in LocationInfo.parseAll(File(assets, "locations.json").readText())) for (p in loc.furniture) {
            val f = Furniture.of(p) ?: continue
            if (!f.kind.bed) continue
            beds++
            val a = FurnitureAvailability.of(pair, listOf(f), unit)
            assertEquals(PlayState.ON_FURNITURE, a.state, "${loc.id}: ${a.notice}")
            assertFalse(a.relaxed, loc.id)
        }
        assertEquals(10, beds)
    }

    @Test fun aBedClipGoesToACouchItNearlyFitsAndNeverOntoATable() {
        // one lying on a bed (0.55 m) where there is only a couch (0.45 m): a near fit on its seat
        val one = req(F.lyingBack(0.55))
        val couch = piece(FurnitureKind.COUCH)
        assertNull(FurnitureFit.fit(one, couch, unit), "not a bed")
        val near = FurnitureAvailability.of(one, listOf(couch), unit)
        assertEquals(PlayState.ON_FURNITURE, near.state)
        assertTrue(near.relaxed)
        assertEquals("Plays on the couch, the nearest fit (its height differs from the animation's).", FurnitureAvailability.describe(near))
        // the Embassy has only its study table: a bed's pair is never laid on it, it plays on the floor, lowered
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val table = Furniture.of(LocationInfo.parseAll(File(assets, "locations.json").readText()).first { it.id == "embassy" }.furniture.single())!!
        val onFloor = FurnitureAvailability.of(bedPair, listOf(table), unit, drop = FurnitureAvailability.floorDrop(bodies(*lying)))
        assertEquals(PlayState.FLOOR, onFloor.state); assertTrue(onFloor.floorRests)
        assertEquals("Nothing here fits it as a bed: playing on the floor.", onFloor.notice)
        // one with a partner standing beside it: lowered, it wouldn't rest on the floor; played where they stand, as made
        val edge = arrayOf(F.standing, moved(F.lyingBack(0.55), -0.7, 0.0))
        val a = FurnitureAvailability.of(req(*edge), emptyList(), unit, drop = FurnitureAvailability.floorDrop(bodies(*edge)))
        assertEquals(PlayState.FLOOR, a.state)
        assertFalse(a.floorRests)
        assertEquals("No bed here: playing it where they stand, as made.", a.notice)
    }

    @Test fun theFloorPlayLowersTheBodiesOntoTheFloor() {
        val r = bedPair
        val drop = FurnitureAvailability.floorDrop(bodies(*lying))
        // lowered by about the bed's height (0.55 m, less the back's flesh), so both rest on the floor
        assertTrue(drop * unit > 0.45f * FurnitureSpec.YARDS_PER_METRE && drop * unit < 0.62f * FurnitureSpec.YARDS_PER_METRE, "drop ${drop * unit}")
        assertTrue(FurnitureAvailability.floorRests(r, drop))
        for (a in r.actors) assertTrue(kotlin.math.abs(a.restUnits - drop) < FurnitureAvailability.FLOOR_REST, "${a.restUnits} - $drop")
        // a partner standing beside it holds it up: nothing is lowered, so it doesn't rest on the floor
        val mixed = arrayOf(F.standing, moved(F.lyingBack(0.55), -0.7, 0.0))
        val d2 = FurnitureAvailability.floorDrop(bodies(*mixed))
        assertTrue(d2 < 0.05f, "$d2")
        assertFalse(FurnitureAvailability.floorRests(req(*mixed), d2))
        // a floor one: nothing to lower
        assertTrue(FurnitureAvailability.floorDrop(bodies(F.lyingBack(0.0))) < 0.05f)
    }

    @Test fun treatAsTakesThePlaceOfTheReadingAndIsKept() {
        val bed = piece(FurnitureKind.BED_DOUBLE)
        val table = piece(FurnitureKind.TABLE)
        assertEquals(PlayState.FLOOR, FurnitureAvailability.of(bedPair, listOf(bed), unit, TreatAs.FLOOR).state)
        assertEquals(PlayState.READY, FurnitureAvailability.of(bedPair, listOf(bed), unit, TreatAs.STANDING).state)
        // a floor pair treated as Bed: looked for on the bed (a floor one lifted onto it)
        val asBed = FurnitureAvailability.of(floorPair, listOf(table, bed), unit, TreatAs.BED)
        assertEquals(PlayState.ON_FURNITURE, asBed.state); assertSame(bed, asBed.furniture); assertEquals(TreatAs.BED, asBed.treatedAs)
        assertEquals(Surface.BED, FurnitureAvailability.applied(floorPair, TreatAs.BED).surface)
        val read = bedPair
        assertSame(read.actors, FurnitureAvailability.applied(read, TreatAs.TABLE).actors, "the bodies' reading is kept")
        // kept per animation id, as one string; damaged or unknown: nothing
        val m = TreatAsMemory()
        assertTrue(m.set("pairA", TreatAs.BED)); assertFalse(m.set("pairA", TreatAs.BED))
        assertTrue(m.set("clip:b\"2", TreatAs.FLOOR))
        val back = TreatAsMemory.decode(m.encode())
        assertEquals(mapOf("pairA" to TreatAs.BED, "clip:b\"2" to TreatAs.FLOOR), back.all)
        assertTrue(back.set("pairA", null)); assertNull(back["pairA"]); assertFalse(back.set("pairA", null))
        assertTrue(TreatAsMemory.decode("{not json").all.isEmpty())
        assertTrue(TreatAsMemory.decode(null).all.isEmpty())
        assertEquals(mapOf("x" to TreatAs.SEAT), TreatAsMemory.decode("""{"x":"SEAT","y":"SOFA"}""").all)
    }

    @Test fun theWindowsCounterAndFilter() {
        val states = listOf(PlayState.READY, PlayState.ON_FURNITURE, PlayState.FLOOR, PlayState.FLOOR)
        assertEquals("2 of 4 playable here, 2 more on the floor", FurnitureAvailability.counter(states))
        assertEquals("1 of 1 playable here", FurnitureAvailability.counter(listOf(PlayState.READY)))
        val floor = Availability(PlayState.FLOOR, Surface.BED)
        assertTrue(FurnitureAvailability.shown(floor, onlyPlayable = false))
        assertFalse(FurnitureAvailability.shown(floor, onlyPlayable = true))
        assertTrue(FurnitureAvailability.shown(null, onlyPlayable = true))
        assertTrue(FurnitureAvailability.shown(Availability(PlayState.READY, Surface.NONE), onlyPlayable = true))
        assertEquals("No bed here: playing on the floor.", FurnitureAvailability.describe(FurnitureAvailability.of(bedPair, emptyList(), unit)))
        assertEquals("Treated as standing: plays where they are, as made.",
            FurnitureAvailability.describe(FurnitureAvailability.of(bedPair, emptyList(), unit, TreatAs.STANDING)))
    }

    @Test fun autoplayPlaysWhatFitsNothingHereOnTheFloorWhenItRests() {
        val floor = AutoPlace("floor", null, piece(FurnitureKind.FLOOR))
        val resting = AutoClip("bed", "On the bed", bedPair, floorRests = true)
        val hanging = AutoClip("edge", "Bed edge", req(F.standing, moved(F.lyingBack(0.55), -0.7, 0.0)), floorRests = false)
        val opts = AutoplayPlanner.options(listOf(floor), listOf(resting, hanging), unit)
        assertEquals(listOf("bed"), opts.map { it.clip.id })
        assertEquals("floor", opts.single().place.key)
        // with a bed, on the bed (not the floor)
        val bed = AutoPlace("BED", SceneObject.BED, piece(FurnitureKind.BED_DOUBLE))
        assertEquals(setOf("BED"), AutoplayPlanner.options(listOf(bed, floor), listOf(resting), unit).map { it.place.key }.toSet())
    }

    @Test fun everyPlacesBedTakesALyingPairOrItsNarrowOnesPlayItOnTheFloor() {
        // two side by side: on every measured bed wide enough for two (Sen'jin's hut's bed, 1.2 yards across, isn't)
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val drop = FurnitureAvailability.floorDrop(bodies(*lying))
        for (loc in LocationInfo.parseAll(File(assets, "locations.json").readText())) for (p in loc.furniture) {
            val f = Furniture.of(p) ?: continue
            if (!f.kind.bed) continue
            val wide = (p.extent?.surface?.widthY ?: 2f) >= FurnitureSpec.DOUBLE_WIDTH_YARDS
            val a = FurnitureAvailability.of(bedPair, listOf(f), unit, drop = drop)
            if (wide) assertEquals(PlayState.ON_FURNITURE, a.state, "${loc.id}: ${a.notice}")
            else { assertEquals(PlayState.FLOOR, a.state, loc.id); assertTrue(a.floorRests); assertEquals("Nothing here fits it as a bed: playing on the floor.", a.notice) }
        }
    }
}
