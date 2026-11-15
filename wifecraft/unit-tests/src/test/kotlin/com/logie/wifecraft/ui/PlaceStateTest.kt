package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.catalog.Catalog
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.catalog.EmoteCatalog
import com.logie.wifecraft.catalog.EmoteKind
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The placement editor's pure state: its camera, the floor under a finger, gestures, snapping, the library, stages, emotes. */
class PlaceStateTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f, what: String = "") = assertTrue(abs(a - b) <= eps, "$what: $a is not $b")
    private fun near(a: Vec3, b: Vec3, eps: Float = 1e-3f) {
        near(a.x, b.x, eps, "x"); near(a.y, b.y, eps, "y"); near(a.z, b.z, eps, "z")
    }

    // a camera 10 yards back along -Y from the origin, 5 up, looking at the origin
    private val cam = CameraPose(Vec3(0f, -10f, 5f), Vec3(0f, 0f, 0f), 50f)
    private val w = 2184f
    private val h = 1968f

    @Test fun theScreenCentreMeetsTheFloorWhereTheCameraLooks() {
        val p = assertNotNull(FloorMath.onPlane(cam, w / 2, h / 2, w, h, 0f))
        near(p, Vec3(0f, 0f, 0f))
    }

    @Test fun projectAndOnPlaneAreEachOthersInverse() {
        for (pt in listOf(Vec3(2f, 3f, 0f), Vec3(-4f, 1f, 0f), Vec3(1f, -2f, 0f))) {
            val (sx, sy) = assertNotNull(FloorMath.project(cam, pt, w, h))
            near(assertNotNull(FloorMath.onPlane(cam, sx, sy, w, h, 0f)), pt, 1e-2f)
        }
        // a point behind the eye isn't on the screen
        assertNull(FloorMath.project(cam, Vec3(0f, -20f, 5f), w, h))
    }

    @Test fun rightOnTheScreenIsTheViewersRightOnTheFloor() {
        // looking along +Y, the right is +X; lower on the screen is nearer the camera (-Y)
        val r = assertNotNull(FloorMath.onPlane(cam, w * 0.75f, h / 2, w, h, 0f))
        assertTrue(r.x > 0.5f && abs(r.y) < 1e-3f)
        val low = assertNotNull(FloorMath.onPlane(cam, w / 2, h * 0.75f, w, h, 0f))
        assertTrue(low.y < -0.5f)
    }

    @Test fun aDragMovesAlongItsOwnFloorAndKeepsTheHeight() {
        val d = assertNotNull(FloorMath.dragDelta(cam, w / 2 to h / 2, w * 0.6f to h / 2, w, h, 0f))
        assertTrue(d.x > 0f); near(d.y, 0f); near(d.z, 0f)
        // the same drag of something standing 2 yards up meets its own floor (nearer the eye: a shorter move)
        val up = assertNotNull(FloorMath.dragDelta(cam, w / 2 to h / 2, w * 0.6f to h / 2, w, h, 2f))
        assertTrue(up.x in 0f..d.x); near(up.z, 0f)
        // a finger above the horizon meets no floor
        assertNull(FloorMath.dragDelta(cam, w / 2 to h / 2, w / 2 to -h * 0.1f, w, h, 0f))
    }

    @Test fun aDragStepIsNeverAMileLong() {
        val flat = CameraPose(Vec3(0f, -10f, 0.5f), Vec3(0f, 0f, 0.3f), 50f)
        val d = FloorMath.dragDelta(flat, w / 2 to h * 0.9f, w / 2 to h * 0.51f, w, h, 0f)
        if (d != null) assertTrue(kotlin.math.hypot(d.x, d.y) <= FloorMath.MAX_STEP + 1e-3f)
    }

    @Test fun nudgesGoAlongTheWorldAxisNearestTheView() {
        near(FloorMath.nudge(cam, 1, 0, 0.5f), Vec3(0f, 0.5f, 0f))
        near(FloorMath.nudge(cam, 0, 1, 0.5f), Vec3(0.5f, 0f, 0f))
        val skew = CameraPose(Vec3(-10f, -3f, 4f), Vec3(0f, 0f, 0f), 50f)
        near(FloorMath.nudge(skew, -1, 0, 1f), Vec3(-1f, 0f, 0f))
    }

    @Test fun theOrbitCameraSeesWhatItsPoseSees() {
        val o = OrbitCamera.from(cam)
        near(o.eye, cam.eye); near(o.pose().at, cam.at); near(o.fov, 50f)
        // orbiting keeps the distance and the target; pitch is kept off the floor and short of straight down
        val turned = o.orbit(300f, -5000f, h)
        near(turned.distance, o.distance); near(turned.target, o.target)
        assertTrue(turned.pitch >= OrbitCamera.MIN_PITCH && turned.yaw != o.yaw)
        assertEquals(OrbitCamera.MAX_PITCH, o.orbit(0f, 1e6f, h).pitch)
        // zoom: spreading the fingers comes nearer, within limits
        assertTrue(o.zoom(2f).distance < o.distance)
        assertEquals(OrbitCamera.MIN_DISTANCE, o.zoom(1e6f).distance)
        assertEquals(o, o.zoom(0f))
    }

    @Test fun panningDragsTheFloorWithTheFingers() {
        val o = OrbitCamera.from(cam)
        // fingers to the right: the floor goes right, so the target goes left (-X here)
        assertTrue(o.pan(100f, 0f, h).target.x < 0f)
        // fingers down: the floor comes toward the viewer, the target goes forward (+Y)
        assertTrue(o.pan(0f, 100f, h).target.y > 0f)
        near(o.pan(100f, 100f, h).target.z, 0f)
    }

    @Test fun twistAndPinch() {
        val a = 0f to 0f
        near(Gestures.twist(a, 100f to 0f, a, 0f to 100f), (PI / 2).toFloat())
        near(Gestures.pinch(a, 100f to 0f, a, 200f to 0f), 2f)
        assertEquals(1f, Gestures.pinch(a, a, a, 5f to 5f))
        // a twist across the ±180° seam is the short way round
        near(Gestures.twist(a, -100f to 1f, a, -100f to -1f), 0.02f, 1e-3f)
        near(Gestures.yawAround(0f to 0f, 10f to 0f, 0f to 10f), -(PI / 2).toFloat())
    }

    @Test fun snapping() {
        val s = Snap(on = true, grid = 0.5f, angleDeg = 15f)
        near(s.pos(1.26f), 1.5f); near(s.pos(-0.2f), 0f)
        near(s.yaw(Math.toRadians(22.0).toFloat()), Math.toRadians(15.0).toFloat())
        near(Snap().pos(1.26f), 1.26f)
        near(s.step, 0.5f); near(Snap().step, 0.1f)
        near(s.scale(1.234f), 1.25f)
        near(Snap().scale(100f), Snap.MAX_SCALE); near(Snap().scale(Float.NaN), 1f)
        near(wrapAngle(3 * PI.toFloat()), PI.toFloat())
    }

    @Test fun groundFollowingKeepsTheLift() {
        assertEquals(12f, followGround(10f, 2f, 99f))
        assertEquals(99f, followGround(null, 2f, 99f))
    }

    @Test fun selectionHidingAndLocking() {
        fun pl(id: String, hidden: Boolean = false, locked: Boolean = false) =
            Placement(id, PlaceKind.PROP, 1, null, id, 0f, 0f, 0f, 0f, 1f, hidden = hidden, locked = locked)
        val lamp = pl("lamp"); val chair = pl("chair")
        var ui = PlaceUi()
        ui = ui.tapped(PlaceKeys.of("lamp"), listOf(lamp, chair))
        assertEquals("lamp", ui.selected)
        assertTrue(ui.canMove(lamp))
        assertTrue(!ui.canMove(lamp.copy(locked = true)) && !ui.canMove(null))
        // hiding the selected one deselects it; a hidden one can't be tapped unless "show hidden" is on
        val hid = lamp.copy(hidden = true)
        assertNull(ui.afterHide(hid).selected)
        assertEquals("lamp", ui.toggleShowHidden().afterHide(hid).selected)
        assertNull(ui.tapped(PlaceKeys.of("lamp"), listOf(hid, chair)).selected)
        assertEquals("lamp", ui.toggleShowHidden().tapped(PlaceKeys.of("lamp"), listOf(hid, chair)).selected)
        assertEquals(setOf("lamp"), ui.notDrawn(listOf(hid, chair)))
        assertEquals(emptySet(), ui.toggleShowHidden().notDrawn(listOf(hid, chair)))
        // the floor or the cast's own key selects nothing
        assertNull(ui.tapped("valeera", listOf(lamp)).selected)
        assertNull(ui.tapped(null, listOf(lamp)).selected)
        ui = ui.select("chair").keepOnly(listOf("chair"))
        assertEquals(PlaceUi("chair"), ui)
        assertEquals("a:b", PlaceKeys.idOf(PlaceKeys.of("a:b")))
    }

    @Test fun libraryEntriesBecomePlacementsOfTheirKind() {
        val at = Vec3(1f, 2f, 3f)
        val prop = placementOf(Asset("p:7", AssetKind.PROP, 7, "Stormwind Lamp", "Lighting"), at, 0.5f, listOf("stormwind_lamp"))
        assertEquals(PlaceKind.PROP, prop.kind); assertEquals(7, prop.asset); assertEquals("stormwind_lamp_2", prop.id)
        assertEquals(Triple(1f, 2f, 3f), Triple(prop.x, prop.y, prop.z)); assertEquals(0.5f, prop.yaw)
        val wmo = placementOf(Asset("b:9", AssetKind.BUILDING, 9, "Inn", "Buildings"), at, 0f, emptyList())
        assertEquals(PlaceKind.PROP, wmo.kind); assertEquals(9, wmo.asset)
        val c = placementOf(Asset("c:1", AssetKind.CREATURE, 55, "Khadgar", "Humanoid", display = 321), at, 0f, emptyList())
        assertEquals(PlaceKind.CREATURE, c.kind); assertEquals(321, c.asset); assertNull(c.actorId)
        val m = placementOf(Asset("m:5", AssetKind.CREATURE_MODEL, 5, "Wolf", "Beast", display = 77), at, 0f, emptyList())
        assertEquals(77, m.asset)
        val n = placementOf(Catalog.ownNpc("alleria", "Alleria"), at, 0f, emptyList())
        assertEquals(PlaceKind.NPC, n.kind); assertEquals("alleria", n.actorId); assertEquals(0, n.asset)
        val d = duplicateOf(prop, listOf(prop.id))
        assertTrue(d.id != prop.id && d.x == prop.x + 1f && d.asset == prop.asset)
    }

    private val lib = Catalog(
        (1..100).map { Asset("p:$it", AssetKind.PROP, it, "Chair $it", if (it % 2 == 0) "Furniture" else "Lighting", if (it <= 10) "Human" else Catalog.ANY, 2) } +
            (1..3).map { Asset("b:${1000 + it}", AssetKind.BUILDING, 1000 + it, "House $it", "Buildings") } +
            Asset("c:1", AssetKind.CREATURE, 9, "Wolf", "Beast", display = 5),
    )

    @Test fun libraryTabsPagesAndChips() {
        var q = LibraryQuery(LibraryTab.PROPS)
        val p0 = LibraryPager.page(lib, emptyList(), q)
        assertEquals(100, p0.total); assertEquals(LibraryQuery.PAGE, p0.items.size)
        assertEquals(3, LibraryQuery.pages(p0.total))
        q = q.next(p0.total).next(p0.total).next(p0.total)
        assertEquals(2, q.page)
        assertEquals(20, LibraryPager.page(lib, emptyList(), q).items.size)
        assertEquals(1, q.prev().page); assertEquals(0, q.prev().prev().prev().page)
        // a chip filters, tapping it again clears it; a new tab or new words go back to the first page
        q = q.withCategory("Furniture")
        assertEquals(0, q.page); assertEquals(50, LibraryPager.page(lib, emptyList(), q).total)
        assertNull(q.withCategory("Furniture").category)
        assertEquals(setOf("Furniture", "Lighting"), LibraryPager.chips(lib, q).toSet())
        assertEquals(3, LibraryPager.page(lib, emptyList(), q.withTab(LibraryTab.BUILDINGS)).total)
        assertEquals(1, LibraryPager.page(lib, emptyList(), LibraryQuery(LibraryTab.CREATURES)).total)
        assertEquals(1, LibraryPager.page(lib, emptyList(), LibraryQuery(LibraryTab.PROPS, "chair 7")).items.count { it.name == "Chair 7" })
        // My NPCs: the cast
        val own = listOf(Catalog.ownNpc("a", "Alleria"), Catalog.ownNpc("n", "Nyrelle"))
        assertEquals(listOf("Nyrelle"), LibraryPager.page(lib, own, LibraryQuery(LibraryTab.MY_NPCS, "nyr")).items.map { it.name })
        assertTrue(LibraryPager.chips(lib, LibraryQuery(LibraryTab.MY_NPCS)).isEmpty())
    }

    @Test fun theStarterPackIsTheStagesStyle() {
        val q = LibraryQuery(LibraryTab.STARTER, style = LibraryPager.styleOf("loc:embassy:inside"))
        assertEquals("Human", q.style)
        val page = LibraryPager.page(lib, emptyList(), q)
        assertTrue(page.total > 0 && page.items.all { it.style == "Human" })
        assertEquals(Catalog.ANY, LibraryPager.styleOf("studio"))
        assertTrue(LibraryPager.detail(page.items.first()).startsWith("Prop"))
    }

    @Test fun theStageListHasTheStudioThePlacesAndEveryLocationView() {
        val assets = File(System.getProperty("wifecraft.assets"))
        val places = PlaceInfo.parseAll(File(assets, "places.json").readText())
        val locations = LocationInfo.parseAll(File(assets, "locations.json").readText())
        val list = StageChoices.list(places, locations)
        assertEquals("studio", list.first().first)
        for (p in places) assertTrue(list.any { it.first == p.id }, p.id)
        assertTrue(list.any { it.first == "loc:embassy:inside" && it.second.endsWith("Inside") })
        assertTrue(list.any { it.first == "loc:embassy:outside" && it.second.endsWith("Outside") })
        assertEquals("embassy" to "inside", StageChoices.locationOf("loc:embassy:inside"))
        assertNull(StageChoices.locationOf("studio")); assertNull(StageChoices.locationOf("loc:embassy"))
        val embassy = locations.first { it.id == "embassy" }
        for (v in embassy.views.keys) assertNotNull(StageChoices.camera(embassy, v), v)
        assertNull(StageChoices.camera(embassy, "nowhere"))
    }

    @Test fun emotesAreGroupedInTheCatalogsOrder() {
        val list = listOf(
            Emote(1, "Wave", 67, 0, listOf(5, 6)),
            Emote(10001, "Work", 100, 0, emptyList(), EmoteKind.NPC, "Work and life", true),
            Emote(100069, "EmoteDance", 69, 0, emptyList(), EmoteKind.ANIM, "Dance", false, 3),
            Emote(2, "Laugh", -1, 0, listOf(7)),
        )
        val g = EmoteGroups.of(list)
        assertEquals(listOf(EmoteCatalog.EMOTES, "Dance", "Work and life"), g.map { it.first })
        assertEquals(listOf("Wave"), EmoteGroups.of(list, "wav").flatMap { it.second }.map { it.name })
        assertEquals("once · 2 voice lines", EmoteGroups.detail(list[0]))
        assertEquals("voice only · 1 voice line", EmoteGroups.detail(list[3]))
        assertEquals("once · 3 ways", EmoteGroups.detail(list[2]))
        val p = Placement("a", PlaceKind.NPC, 0, "a", "A", 0f, 0f, 0f, 0f, 1f)
        val held = EmoteGroups.apply(p, list[1])
        assertEquals(Triple(100, 10001, true), Triple(held.anim, held.emoteId, held.loop))
        val voice = EmoteGroups.apply(p, list[3])
        assertNull(voice.anim); assertEquals(2, voice.emoteId)
        val idle = EmoteGroups.apply(held, null)
        assertNull(idle.anim); assertNull(idle.emoteId)
    }

    @Test fun inspectorNumbers() {
        assertEquals(1.5f, readNumber(" 1,5 ")); assertNull(readNumber("x")); assertNull(readNumber("NaN"))
        assertEquals("1.5", showNumber(1.5f)); assertEquals("2", showNumber(2.0001f)); assertEquals("0", showNumber(-0.001f))
        near(yawRadians(yawDegrees(1f)), 1f); near(yawRadians(270f), -(PI / 2).toFloat())
    }
}
