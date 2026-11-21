package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import com.logie.wifecraft.ui.CameraChoice
import com.logie.wifecraft.ui.SceneHud
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

/**
 * The furniture in the scene: partner animations played where their bodies fit (a bed's, a couch's slots), refused where they
 * don't, the player's Play animation on a piece, the locations' furniture rows, and the furniture camera views. Synthetic
 * bodies (anim/PoseFixtures); nothing here has run on a phone.
 */
class FurnitureSceneTest {
    private val F = PoseFixtures
    private val step = 1f / 120f

    /** Two clips as a pair, actor 1 at [dy] body units to actor 0's left (slots at human scale, as the live scene's). */
    private fun pair(id: String, a: Anim, b: Anim, dx: Double = 0.0, dy: Double = 0.0, locations: Set<String> = emptySet()) =
        PartnerPair(id, "Pair $id", a, b, PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human((dx * BodyGeometry.REF_UNIT).toFloat(), (dy * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true, locations)

    private val sideBySideLying get() = pair("lie2", F.lyingBack(0.55), F.lyingBack(0.55), dy = 0.6)
    private val sideBySideSitting get() = pair("sit2", F.sitting(0.45), F.sitting(0.45), dy = 0.55)
    private val floorStanding get() = pair("hug", F.standing, F.standing, dx = 0.6)

    private fun until(sim: SceneSimulation, limit: Int = 20_000, done: () -> Boolean) {
        var n = 0
        while (!done()) { sim.tick(step); n++; check(n < limit) { "never happened" } }
    }

    @Test fun aBedPairPlaysOnTheBedsLyingSlots() {
        val p = sideBySideLying
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(1), pairs = listOf(p))
        val r = sim.requirementOf(p)!!
        assertEquals(Surface.BED, r.surface)
        assertTrue(sim.partner("a", "b"))
        val v = sim.partnerOf("a")!!
        assertEquals(SceneObject.BED, v.furniture)
        assertTrue(v.emote < 0)
        // the origin is where the fit put it: both actors on the bed's lying slots (or the bed itself)
        val bed = sim.furniture(SceneObject.BED)!!
        val fit = FurnitureFit.fit(r, bed, SceneSimulation.HUMAN_BODY_UNIT)!!
        assertTrue(fit.slots.all { it.startsWith("lie_") || it == "bed" }, fit.slots.toString())
        assertEquals(fit.x, v.ox, 1e-4f); assertEquals(fit.y, v.oy, 1e-4f)
        until(sim) { sim.partnerOf("a")?.stage == "PLAY" }
        for (id in listOf("a", "b")) {
            val at = sim.position(id)!!
            val (lx, ly) = bed.toLocal(at.x, at.y)
            assertTrue(abs(lx) <= bed.spec.halfX && abs(ly) <= bed.spec.halfY, "$id stands on the bed: $lx, $ly")
        }
        // the bed is held while they play; the cameras see them on it
        assertFalse(sim.available(SceneObject.BED, "c"))
        assertEquals(bed, sim.onFurniture("a")!!.first)
    }

    @Test fun aPairThatNeedsASurfaceIsNeverChosenWithoutOne() {
        // two side by side on a seat: a chair holds one, so the emotes instead; a couch holds both
        val p = sideBySideSitting
        for (seed in 1..10) {
            val chair = SceneSimulation(listOf("a", "b"), setOf(SceneObject.CHAIR), Random(seed), pairs = listOf(p))
            assertTrue(chair.partner("a", "b"))
            assertTrue(chair.partnerOf("a")!!.emote >= 0, "seed $seed: the emotes, not the seat pair")
            val couch = SceneSimulation(listOf("a", "b"), setOf(SceneObject.COUCH), Random(seed), pairs = listOf(p))
            assertTrue(couch.partner("a", "b"))
            assertEquals(SceneObject.COUCH, couch.partnerOf("a")!!.furniture)
        }
        // lying on a bed with no bed: on the floor in its place (lowered, it rests there: FurnitureAvailability.floorRests)
        val bare = SceneSimulation(listOf("a", "b"), emptySet(), Random(3), pairs = listOf(sideBySideLying))
        assertTrue(bare.partner("a", "b"))
        assertTrue(bare.partnerOf("a")!!.emote < 0)
        assertNull(bare.partnerOf("a")!!.furniture)
        // but one that wouldn't rest on the floor lowered (two sitting with their feet down): the emotes, as before
        val seats = SceneSimulation(listOf("a", "b"), emptySet(), Random(3), pairs = listOf(sideBySideSitting))
        assertTrue(seats.partner("a", "b"))
        assertTrue(seats.partnerOf("a")!!.emote >= 0)
    }

    @Test fun aFloorPairPlaysOnTheFloorOrOnABed() {
        // two lying side by side on the floor (a head-to-feet pair is longer than a bed at human scale: floor only)
        val p = pair("floor2", F.lyingBack(0.0), F.lyingBack(0.0), dy = 0.6)
        assertEquals(Surface.FLOOR, FurnitureAnimation.of(p).requirement.surface)
        val places = HashSet<SceneObject?>()
        for (seed in 1..30) {
            val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(seed), pairs = listOf(p))
            assertTrue(sim.partner("a", "b"))
            val v = sim.partnerOf("a")!!
            assertTrue(v.emote < 0)
            places += v.furniture
        }
        assertEquals(setOf(null, SceneObject.BED), places)
        // a standing pair: the floor only, never the bed
        for (seed in 1..10) {
            val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(seed), pairs = listOf(floorStanding))
            assertTrue(sim.partner("a", "b"))
            assertNull(sim.partnerOf("a")!!.furniture)
        }
    }

    @Test fun aCounterAnimationIsRefusedOnABedAndPlaysAtATable() {
        val lean = RequirementClassifier.classify(F.leaning())
        assertEquals(Surface.TABLE_OR_COUNTER, lean.surface)
        val sim = SceneSimulation(listOf("a"), setOf(SceneObject.BED, SceneObject.TABLE), Random(1))
        val order = PerformanceOrder(null, listOf(PlaybackRole("lean:0", Vec3(0f, 0f, 0f), 0f)), 2f)
        assertFalse(sim.performOn("a", SceneObject.BED, lean, order))
        assertTrue(sim.performOn("a", SceneObject.TABLE, lean, order))
        // it walks there first, then plays at the table's front, holding the table
        assertFalse(sim.available(SceneObject.TABLE, "b"))
        until(sim) { Pose().also { p -> sim.pose(0, p) }.playback != null }
        val table = sim.furniture(SceneObject.TABLE)!!
        val at = sim.position("a")!!
        val (lx, _) = table.toLocal(at.x, at.y)
        assertTrue(lx > 0f && lx < table.spec.halfX + 1f, "at the table's front: $lx")
        // a lying one on the table refused too (nothing on it takes a bed's height)
        assertFalse(sim.performOn("a", SceneObject.TABLE, RequirementClassifier.classify(F.lyingBack(0.55)), order))
    }

    @Test fun thePlayersPairOnFurnitureTakesTheNearestPartnerWhenNoneIsGiven() {
        val p = sideBySideSitting
        val sim = SceneSimulation(listOf("a", "b", "c"), setOf(SceneObject.COUCH), Random(2), pairs = listOf(p))
        assertFalse(sim.partnerOn(SceneObject.BED, p.id, "a", null))   // not in the scene
        assertFalse(sim.partnerOn(SceneObject.COUCH, "nope", "a", null))
        assertTrue(sim.partnerOn(SceneObject.COUCH, p.id, "a", "c"))
        assertEquals(setOf("a", "c"), setOf(sim.partnerOf("a")!!.partner, sim.partnerOf("c")!!.partner))
        assertEquals(SceneObject.COUCH, sim.partnerOf("a")!!.furniture)
        // it plays to its end and both leave the couch free
        until(sim) { sim.partnerOf("a") == null }
        assertTrue(sim.available(SceneObject.COUCH, "b"))
    }

    @Test fun pairsOnlyNamedForFurnitureStillGoByTheirNames() {
        // clips with no body to read: as before, the location name decides (a couch pair on the couch, its seat the origin)
        val blank = Anim("x", "x", "", 2f, true, emptyList(), "s:x", 0L)
        val named = PartnerPair("sofa", "Pair sofa", blank, blank, PartnerSlot(-0.4f, 0f, 0f, 0f), PartnerSlot(0.4f, 0f, 0f, 180f), false, setOf("SOFA"))
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.COUCH), Random(4), pairs = listOf(named))
        assertTrue(sim.partner("a", "b"))
        val v = sim.partnerOf("a")!!
        assertEquals(SceneObject.COUCH, v.furniture)
        assertEquals(SceneObject.COUCH.approach.x, v.ox, 1e-4f)
    }

    @Test fun everyBedroomHasItsFurnitureMeasured() {
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val all = LocationInfo.parseAll(File(assets, "locations.json").readText())
        for (loc in all.filter { it.interactions.isNotEmpty() }) {
            assertEquals(loc.interactions.map { it.item }.toSet(), loc.furniture.map { it.item }.toSet(), loc.id)
            for (piece in loc.furniture) {
                val f = assertNotNull(Furniture.of(piece), "${loc.id} ${piece.kind}")
                val row = loc.interactions.first { it.item == piece.item }
                // on the floor its user stands on, its surface over it at a height a body can use
                assertEquals(row.entry.z, f.z, 0.05f, loc.id)
                // (Fairbreeze's round bed is the tallest: its domed cushion 1.22 yards up at its middle, as measured)
                assertTrue(f.spec.surface in 0.4f..1.25f, "${loc.id} ${piece.item}: ${f.spec.surface}")
                when (piece.item) {
                    // the bed's middle is where its user lies (measured: its real footprint's middle, near it); its foot
                    // opposite the lying facing, unless the measure found its head the other way round
                    "BED" -> {
                        val e = piece.extent
                        if (e == null) { assertEquals(row.pose.x, f.x, 1e-3f); assertEquals(row.pose.y, f.y, 1e-3f) }
                        else assertTrue(kotlin.math.hypot(row.pose.x - f.x, row.pose.y - f.y) < 0.6f, "${loc.id}: ${f.x}, ${f.y}")
                        val turn = if (e != null && e.headEnd > 0) 0f else Math.PI.toFloat()
                        assertEquals(1f, kotlin.math.cos(f.yaw - row.facing - turn), 1e-3f, loc.id)
                    }
                    // the table's front toward its reader
                    "TABLE" -> assertTrue(f.toLocal(row.entry.x, row.entry.y).first > 0f, loc.id)
                }
            }
        }
        val embassy = all.first { it.id == "embassy" }.furniture.single()
        assertEquals("TABLE", embassy.kind)
    }

    @Test fun theCameraButtonCyclesThroughTheFurnitureViews() {
        var c = CameraChoice(CameraMode.ROOM)
        val seen = ArrayList<CameraChoice>()
        repeat(9) { c = SceneHud.nextCamera(c, canFollow = true, views = 5); seen += c }
        assertEquals(listOf(CameraChoice(CameraMode.ORBIT), CameraChoice(CameraMode.FOLLOW)) +
            (0 until 5).map { CameraChoice(CameraMode.ROOM, it) } + listOf(CameraChoice(CameraMode.ROOM), CameraChoice(CameraMode.ORBIT)), seen)
        // without views: as before; an orbit dragged from a view goes on to the next view
        assertEquals(CameraChoice(CameraMode.ROOM), SceneHud.nextCamera(CameraChoice(CameraMode.FOLLOW), true, 0))
        assertEquals(CameraChoice(CameraMode.ROOM, 3), SceneHud.nextCamera(CameraChoice(CameraMode.ORBIT, 2), true, 5))
        assertEquals("Foot", SceneHud.cameraLabel(CameraChoice(CameraMode.ROOM, 0), "Foot"))
        assertEquals("Camera: Follow. Tap for Foot.", SceneHud.cameraDescription(CameraChoice(CameraMode.FOLLOW), true, listOf("Foot", "Head")))
    }

    @Test fun theLiveSceneShowsAFurnitureViewOfWhoIsOnIt() {
        val room = CameraPose(Vec3(0f, -4f, 3f), Vec3(0f, 0f, 1f), 50f)
        val live = LiveScene(listOf("a", "b"), setOf(SceneObject.BED), emptyMap(), room, 0f, 0f, 0f, 0f, Random(5), partnerChance = 0f)
        live.running = true
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {}
            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {}
            override fun activity(index: Int, activity: Activity) {}
        }
        val spot = InteractionSpot(Vec3(2.6f, 1.2f, 0f), Vec3(2.6f, 2.2f, 0.6f), -1.5707964f)
        live.command { it.setSpot(SceneObject.BED, spot); it.use("a", SceneObject.BED) }
        var snap: SceneSnapshot? = null
        var t = 0L
        var frames = 0
        while (snap?.furnitureViews?.get("a") == null && frames < 3000) { live.frame(t, sink) { snap = it }; t += 16_666_667L; frames++ }
        assertEquals(listOf(FurnitureView.FOOT, FurnitureView.HEAD, FurnitureView.LEFT, FurnitureView.RIGHT, FurnitureView.TOP),
            snap!!.furnitureViews["a"])
        assertNull(snap!!.furnitureViews["b"])
        // the Foot view: the camera's room becomes it (the eye past the bed's foot), and back to the room's when it is off
        live.selected = 0
        live.furnitureView = FurnitureView.FOOT
        live.frame(t, sink) {}; t += 16_666_667L
        val bed = Furniture.of(SceneObject.BED, spot)
        val eye = live.camera.room.eye
        assertTrue(bed.toLocal(eye.x, eye.y).first > bed.spec.halfX, "the eye is past the foot: ${bed.toLocal(eye.x, eye.y)}")
        assertEquals(live.camera.room.eye.x, live.camera.eyeX, 1e-3f)
        live.furnitureView = null
        live.frame(t, sink) {}; t += 16_666_667L
        assertSame(room, live.camera.room)
        // the walls: asked along the floor a few times a view (not once an eye); none clear: the view is had all the same
        var asked = 0
        live.clearance = CameraClearance { _, _ -> asked++; false }
        live.furnitureView = FurnitureView.HEAD
        live.frame(t, sink) {}
        assertNotSame(room, live.camera.room)
        assertTrue(asked in 1..40, "asked $asked times")
    }
}
