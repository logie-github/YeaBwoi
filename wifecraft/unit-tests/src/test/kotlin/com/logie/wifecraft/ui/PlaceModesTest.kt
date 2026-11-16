package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The placement editor's tools: rays from the camera's matrices, the gizmo's drags and hits, placing mode, cycling, taps, tips. */
class PlaceModesTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f, what: String = "") = assertTrue(abs(a - b) <= eps, "$what: $a is not $b")
    private fun near(a: Vec3, b: Vec3, eps: Float = 1e-3f) { near(a.x, b.x, eps, "x"); near(a.y, b.y, eps, "y"); near(a.z, b.z, eps, "z") }

    // the Fold's inner screen; a camera 10 yards back along -Y from the origin, 5 up
    private val w = 2184f
    private val h = 1968f
    private val pose = CameraPose(Vec3(0f, -10f, 5f), Vec3(0f, 0f, 0f), 50f)
    private val matrices = ViewMatrices(pose.eye, Mat4.lookAt(pose.eye, pose.at), Mat4.perspective(pose.fov, w / h), w / h)
    private val chair = Placement("chair", PlaceKind.PROP, 7, null, "Chair", 0f, 0f, 0f, 0f, 1f)

    @Test fun matrixRaysAgreeWithThePoseRays() {
        val m = MatrixRay(matrices)
        val p = PoseRay(pose)
        for ((sx, sy) in listOf(w / 2 to h / 2, 100f to 200f, w - 10f to h - 30f, 900f to 1500f)) {
            val (o1, d1) = m.ray(sx, sy, w, h)
            val (o2, d2) = p.ray(sx, sy, w, h)
            near(o1, o2); near(d1, d2, 1e-3f)
        }
        val q = Vec3(1.5f, 2f, 0.5f)
        val a = assertNotNull(m.project(q, w, h)); val b = assertNotNull(p.project(q, w, h))
        near(a.first, b.first, 0.5f); near(a.second, b.second, 0.5f)
        near(m.worldPerPixel(q, h), p.worldPerPixel(q, h), 1e-4f)
        // behind the eye: nowhere on the screen
        assertNull(m.project(Vec3(0f, -20f, 5f), w, h))
    }

    @Test fun theInverseUndoesTheMatrix() {
        val vp = Mat4.multiply(matrices.proj, matrices.view)
        val i = assertNotNull(Mat4.invert(vp))
        val id = Mat4.multiply(vp, i)
        for (k in 0 until 16) near(id[k], if (k % 5 == 0) 1f else 0f, 1e-3f)
        assertNull(Mat4.invert(FloatArray(16)))
    }

    @Test fun matricesAreUsedOnlyWhenTheyAreTheCamerasNow() {
        assertTrue(ViewRays.choose(matrices, pose, w, h) is MatrixRay)
        assertTrue(ViewRays.choose(null, pose, w, h) is PoseRay)
        // the camera moved since the renderer said what it was
        assertTrue(ViewRays.choose(matrices, pose.copy(eye = Vec3(1f, -10f, 5f)), w, h) is PoseRay)
        // the view changed shape (the Fold opened)
        assertTrue(ViewRays.choose(matrices, pose, 1080f, 2520f) is PoseRay)
    }

    @Test fun theClosestPointOfAnAxisToARay() {
        // a ray down the y axis at x = 3 crosses the x axis at t = 3
        val t = assertNotNull(GizmoMath.closestOnAxis(Vec3(3f, -10f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f)))
        near(t, 3f)
        // a ray along the axis: no answer
        assertNull(GizmoMath.closestOnAxis(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 5f, 0f), Vec3(1f, 0f, 0f)))
    }

    @Test fun dragsAnArrowAlongItsAxisOnly() {
        val cam = MatrixRay(matrices)
        val c = assertNotNull(cam.project(Vec3(0f, 0f, 0f), w, h))
        val xEnd = assertNotNull(cam.project(Vec3(1f, 0f, 0f), w, h))
        val drag = assertNotNull(GizmoDrag.begin(GizmoAxis.X, chair, cam, c.first, c.second, w, h))
        // the finger goes where (1, 0, 0) is on the screen, and a little up: only x changes, by a yard
        val p = drag.to(cam, xEnd.first, xEnd.second - 30f, w, h)
        near(p.x, 1f, 0.02f); near(p.y, 0f); near(p.z, 0f)
        // the z arrow: up the screen raises it
        val zEnd = assertNotNull(cam.project(Vec3(0f, 0f, 1f), w, h))
        val up = assertNotNull(GizmoDrag.begin(GizmoAxis.Z, chair, cam, c.first, c.second, w, h)).to(cam, zEnd.first, zEnd.second, w, h)
        near(up.z, 1f, 0.02f); near(up.x, 0f)
        // snapping: to the grid
        val snapped = drag.to(cam, xEnd.first, xEnd.second, w, h, Snap(on = true, grid = 0.5f))
        near(snapped.x, 1f)
    }

    @Test fun anArrowSeenEndOnFollowsTheScreen() {
        // looking straight down the y axis: the y arrow points at the eye
        val flat = CameraPose(Vec3(0f, -10f, 0.0f), Vec3(0f, 0f, 0f), 50f)
        val cam = PoseRay(flat)
        val c = assertNotNull(cam.project(Vec3(0f, 0f, 0f), w, h))
        val drag = assertNotNull(GizmoDrag.begin(GizmoAxis.Y, chair, cam, c.first, c.second, w, h))
        val p = drag.to(cam, c.first + 200f, c.second, w, h)
        assertTrue(abs(p.y) <= GizmoMath.MAX_MOVE, "moved ${p.y}")
    }

    @Test fun theFloorHandleSlidesOnItsLevel() {
        val cam = PoseRay(pose)
        val c = assertNotNull(cam.project(Vec3(0f, 0f, 0f), w, h))
        val there = assertNotNull(cam.project(Vec3(2f, 3f, 0f), w, h))
        val p = assertNotNull(GizmoDrag.begin(GizmoAxis.FLOOR, chair, cam, c.first, c.second, w, h)).to(cam, there.first, there.second, w, h)
        near(p.x, 2f, 0.01f); near(p.y, 3f, 0.01f); near(p.z, 0f)
    }

    @Test fun theRingTurnsAboutTheFeet() {
        val top = CameraPose(Vec3(0f, -3f, 10f), Vec3(0f, 0f, 0f), 50f)
        val cam = PoseRay(top)
        val a = assertNotNull(cam.project(Vec3(1f, 0f, 0f), w, h))
        val b = assertNotNull(cam.project(Vec3(0f, 1f, 0f), w, h))
        val p = assertNotNull(GizmoDrag.begin(GizmoAxis.YAW, chair, cam, a.first, a.second, w, h)).to(cam, b.first, b.second, w, h)
        near(p.yaw, (PI / 2).toFloat(), 0.01f)
        // with 45° steps it lands on a step
        val s = assertNotNull(GizmoDrag.begin(GizmoAxis.YAW, chair, cam, a.first, a.second, w, h))
            .to(cam, b.first, b.second, w, h, Snap(on = true, angleDeg = 45f))
        near(s.yaw, (PI / 2).toFloat(), 1e-3f)
    }

    @Test fun theBoxesResize() {
        near(GizmoMath.scaleFactor(0f to 0f, 100f to 0f, 200f to 0f), 2f)
        near(GizmoMath.scaleFactor(0f to 0f, 100f to 0f, 50f to 0f), 0.5f)
        assertTrue(GizmoMath.scaleFactor(0f to 0f, 0f to 0f, 0f to -100f) > 1f)
        val cam = PoseRay(pose)
        val c = assertNotNull(cam.project(Vec3(0f, 0f, 0f), w, h))
        val p = assertNotNull(GizmoDrag.begin(GizmoAxis.UNIFORM, chair, cam, c.first + 100f, c.second, w, h)).to(cam, c.first + 300f, c.second, w, h)
        near(p.scale, 3f, 0.01f)
        // never past the limits
        val big = assertNotNull(GizmoDrag.begin(GizmoAxis.UNIFORM, chair.copy(scale = 15f), cam, c.first + 100f, c.second, w, h)).to(cam, c.first + 900f, c.second, w, h)
        assertEquals(Snap.MAX_SCALE, big.scale)
        assertNull(GizmoDrag.begin(GizmoAxis.NONE, chair, cam, 0f, 0f, w, h))
    }

    @Test fun theFallbackHitTestFindsTheHandlesWhereTheRendererDrawsThem() {
        val cam = PoseRay(pose)
        val o = Vec3(0f, 0f, 0f)
        val c = assertNotNull(cam.project(o, w, h))
        // the renderer's arrow: 0.19 of the view's height on the screen
        val len = GizmoMath.HANDLE_SHARE * h * cam.worldPerPixel(o, h)
        val xTip = assertNotNull(cam.project(Vec3(len * 0.9f, 0f, 0f), w, h))
        val zTip = assertNotNull(cam.project(Vec3(0f, 0f, len * 0.9f), w, h))
        assertEquals(GizmoAxis.X, GizmoMath.hit(cam, GizmoTool.MOVE, chair, xTip.first, xTip.second, w, h))
        assertEquals(GizmoAxis.Z, GizmoMath.hit(cam, GizmoTool.MOVE, chair, zTip.first, zTip.second, w, h))
        val square = assertNotNull(cam.project(Vec3(0.35f * len, 0.35f * len, 0f), w, h))
        assertEquals(GizmoAxis.FLOOR, GizmoMath.hit(cam, GizmoTool.MOVE, chair, square.first, square.second, w, h))
        assertEquals(GizmoAxis.FLOOR, GizmoMath.hit(cam, GizmoTool.MOVE, chair, c.first, c.second, w, h))
        assertEquals(GizmoAxis.NONE, GizmoMath.hit(cam, GizmoTool.MOVE, chair, 10f, 10f, w, h))
        val ring = assertNotNull(cam.project(Vec3(-0.9f * len, 0f, 0f), w, h))
        assertEquals(GizmoAxis.YAW, GizmoMath.hit(cam, GizmoTool.ROTATE, chair, ring.first, ring.second, w, h))
        assertEquals(GizmoAxis.NONE, GizmoMath.hit(cam, GizmoTool.ROTATE, chair, c.first, c.second, w, h))
        val xBox = assertNotNull(cam.project(Vec3(0.88f * len, 0f, 0f), w, h))
        assertEquals(GizmoAxis.X, GizmoMath.hit(cam, GizmoTool.SCALE, chair, xBox.first, xBox.second, w, h))
        assertEquals(GizmoAxis.UNIFORM, GizmoMath.hit(cam, GizmoTool.SCALE, chair, c.first, c.second, w, h))
    }

    @Test fun eachModeDragsItsOwnHandles() {
        assertEquals(GizmoAxis.X, GizmoMath.dragAxis(GizmoTool.MOVE, GizmoAxis.X))
        assertEquals(GizmoAxis.FLOOR, GizmoMath.dragAxis(GizmoTool.MOVE, GizmoAxis.FLOOR))
        assertEquals(GizmoAxis.NONE, GizmoMath.dragAxis(GizmoTool.MOVE, GizmoAxis.YAW))
        assertEquals(GizmoAxis.YAW, GizmoMath.dragAxis(GizmoTool.ROTATE, GizmoAxis.YAW))
        assertEquals(GizmoAxis.NONE, GizmoMath.dragAxis(GizmoTool.ROTATE, GizmoAxis.X))
        // a placement has one size: Scale's axis boxes size the whole thing
        assertEquals(GizmoAxis.UNIFORM, GizmoMath.dragAxis(GizmoTool.SCALE, GizmoAxis.Y))
        assertEquals(GizmoAxis.UNIFORM, GizmoMath.dragAxis(GizmoTool.SCALE, GizmoAxis.UNIFORM))
        assertEquals(GizmoAxis.NONE, GizmoMath.dragAxis(GizmoTool.SCALE, GizmoAxis.NONE))
    }

    private val lamp = Asset("p:42", AssetKind.PROP, 42, "Stormwind lamp", "Lighting")

    @Test fun placingDropsOnLiftAndStaysArmed() {
        var s = Placing(lamp)
        assertEquals("Placing: Stormwind lamp", s.title)
        assertEquals("Loading the model…", s.line)
        // not loaded yet: a drag drops nothing
        s = s.press().follow(Vec3(1f, 2f, 0f))
        var (after, drop) = s.release()
        assertNull(drop); assertEquals(0, after.placed)
        s = after.loaded(true, null)
        // a drag over the floor, the finger lifts: one placed, still armed
        s = s.press().follow(Vec3(3f, 4f, 0f)).follow(null)
        val r = s.release(); after = r.first; drop = r.second
        assertEquals(Vec3(3f, 4f, 0f), drop); assertEquals(1, after.placed); assertTrue(!after.dragging)
        // a tap aims: no drop; Drop puts one at the ghost
        s = after.aim(10f, 20f)
        assertEquals(10f to 20f, s.cross)
        val (d2, at2) = s.dropHere()
        assertEquals(Vec3(3f, 4f, 0f), at2); assertEquals(2, d2.placed)
        assertTrue(d2.line.startsWith("2 placed"))
        // two fingers: a camera move, nothing drops
        val (c3, at3) = d2.press().cancelDrag().release()
        assertNull(at3); assertEquals(2, c3.placed)
        // the ghost turns
        near(Placing(lamp).turned((PI / 2).toFloat()).yaw, (PI / 2).toFloat())
        // a model that can't be drawn says why and drops nothing
        val bad = Placing(lamp).loaded(false, "file 42 couldn't be read").follow(Vec3(0f, 0f, 0f))
        assertEquals("file 42 couldn't be read", bad.line); assertNull(bad.dropHere().second)
    }

    @Test fun tappingTheSameSpotCyclesThroughWhatIsUnderIt() {
        val cam = PoseRay(pose)
        val a = chair.copy(id = "a", x = 0f, y = 0f)
        val b = chair.copy(id = "b", x = 0f, y = 2f)       // behind a, further from the eye
        val far = chair.copy(id = "far", x = 8f, y = 0f)
        val c = assertNotNull(cam.project(Vec3(0f, 0f, 0.9f), w, h))
        val under = SelectCycle.under(cam, listOf(b, far, a), c.first, c.second, w, h, 120f)
        assertEquals(listOf("a", "b"), under)
        // the renderer said b: b first
        val ids = SelectCycle.order("b", under)
        assertEquals(listOf("b", "a"), ids)
        var cyc = SelectCycle.next(TapCycle(), c.first, c.second, ids)
        assertEquals("b", cyc.selected)
        cyc = SelectCycle.next(cyc, c.first + 5f, c.second, ids)
        assertEquals("a", cyc.selected)
        cyc = SelectCycle.next(cyc, c.first, c.second, ids)
        assertEquals("b", cyc.selected)
        // somewhere else starts again; nothing there selects nothing
        assertEquals("a", SelectCycle.next(cyc, c.first + 400f, c.second, listOf("a")).selected)
        assertNull(SelectCycle.next(cyc, 5f, 5f, emptyList()).selected)
    }

    @Test fun doubleTaps() {
        assertTrue(TapTiming.isDouble(1000L, 10f to 10f, 1200L, 20f to 15f))
        assertTrue(!TapTiming.isDouble(1000L, 10f to 10f, 1400L, 10f to 10f))
        assertTrue(!TapTiming.isDouble(1000L, 10f to 10f, 1100L, 300f to 10f))
        assertTrue(!TapTiming.isDouble(null, null, 1100L, 300f to 10f))
    }

    @Test fun theTipSaysTheMode() {
        assertEquals(PlaceTip.PLACING, PlaceTip.of(placing = true, selected = true, locked = false, tool = GizmoTool.MOVE))
        assertEquals(PlaceTip.BROWSE, PlaceTip.of(false, false, false, GizmoTool.MOVE))
        assertEquals(PlaceTip.LOCKED, PlaceTip.of(false, true, true, GizmoTool.ROTATE))
        assertEquals(PlaceTip.MOVE, PlaceTip.of(false, true, false, GizmoTool.MOVE))
        assertEquals(PlaceTip.ROTATE, PlaceTip.of(false, true, false, GizmoTool.ROTATE))
        assertEquals(PlaceTip.SCALE, PlaceTip.of(false, true, false, GizmoTool.SCALE))
        assertEquals("Drag the ghost to place, lift to drop. It stays armed for the next one: Done when you're finished.", PlaceTip.PLACING.text)
        assertEquals(PlaceTip.values().size, PlaceTip.values().map { it.id }.toSet().size)
    }

    @Test fun focusFramesTheThing() {
        val cam = OrbitCamera(Vec3(0f, 0f, 0f), 0f, 0.4f, 30f)
        val f = cam.focus(Vec3(5f, 5f, 1f), 2f)
        assertEquals(Vec3(5f, 5f, 1f), f.target); assertEquals(cam.yaw, f.yaw)
        assertTrue(f.distance in 3f..6f, "distance ${f.distance}")
        assertEquals(OrbitCamera.FOCUS_MAX, cam.focus(Vec3(0f, 0f, 0f), 500f).distance)
    }
}
