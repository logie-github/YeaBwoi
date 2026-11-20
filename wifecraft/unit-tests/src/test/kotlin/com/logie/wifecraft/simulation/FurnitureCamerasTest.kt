package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

/** The furniture's camera presets: which views each kind has, framed on the people, kept in the room and clear of walls. */
class FurnitureCamerasTest {
    private fun at(kind: FurnitureKind, x: Float = 0f, y: Float = 0f, yaw: Float = 0f) = Furniture(kind.name, FurnitureSpec.standard(kind), x, y, 0f, yaw)

    private fun dist(a: Vec3, b: Vec3) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    private val bed = at(FurnitureKind.BED_DOUBLE, 2f, 1f, (PI / 2).toFloat())
    private val people = listOf(bed.toWorld(0f, 0.4f, 0.7f), bed.toWorld(-0.8f, 0.4f, 0.75f), bed.toWorld(0f, -0.4f, 0.7f), bed.toWorld(-0.8f, -0.4f, 0.75f))

    @Test fun aBedHasItsFootHeadSidesAndTop() {
        val views = FurnitureCameras.presets(bed, people)
        assertEquals(listOf(FurnitureView.FOOT, FurnitureView.HEAD, FurnitureView.LEFT, FurnitureView.RIGHT, FurnitureView.TOP), views.map { it.view })
        val mid = Vec3(people.map { it.x }.average().toFloat(), people.map { it.y }.average().toFloat(), people.map { it.z }.average().toFloat())
        for (v in views) {
            assertEquals(mid, v.at, "${v.view} looks at the people's middle")
            assertTrue(v.distance in FurnitureCameras.MIN_DISTANCE..FurnitureCameras.MAX_DISTANCE)
            assertEquals(v.distance, dist(v.eye, v.at), 1e-3f)
            assertFalse(v.moved)
            // the people are inside the view: each within the half angle of the line of sight
            val los = Vec3(v.at.x - v.eye.x, v.at.y - v.eye.y, v.at.z - v.eye.z)
            for (p in people) {
                val to = Vec3(p.x - v.eye.x, p.y - v.eye.y, p.z - v.eye.z)
                val cos = (los.x * to.x + los.y * to.y + los.z * to.z) / (dist(v.eye, v.at) * dist(v.eye, p))
                assertTrue(Math.toDegrees(kotlin.math.acos(cos.toDouble())) < v.fov / 2, "${v.view}: a person is out of view")
            }
        }
        val foot = views.first { it.view == FurnitureView.FOOT }
        val head = views.first { it.view == FurnitureView.HEAD }
        // the foot is along the bed's front (+x of its frame), the head opposite and higher (over the headboard)
        assertTrue(bed.toLocal(foot.eye.x, foot.eye.y).first > bed.spec.halfX)
        assertTrue(bed.toLocal(head.eye.x, head.eye.y).first < -bed.spec.halfX)
        val fd = Vec3(foot.eye.x - foot.at.x, foot.eye.y - foot.at.y, 0f); val hd = Vec3(head.eye.x - head.at.x, head.eye.y - head.at.y, 0f)
        assertTrue((fd.x * hd.x + fd.y * hd.y) / (sqrt(fd.x * fd.x + fd.y * fd.y) * sqrt(hd.x * hd.x + hd.y * hd.y)) < -0.99f)
        assertTrue(head.eye.z > foot.eye.z)
        val left = views.first { it.view == FurnitureView.LEFT }; val right = views.first { it.view == FurnitureView.RIGHT }
        assertTrue(bed.toLocal(left.eye.x, left.eye.y).second > 0f && bed.toLocal(right.eye.x, right.eye.y).second < 0f)
        assertTrue(views.first { it.view == FurnitureView.TOP }.pitch > 1.3f)
    }

    @Test fun aSmallRoomBringsTheEyesInAndWidensTheView() {
        val room = RoomBounds(Vec3(-1f, -2f, 0f), Vec3(5f, 4.2f, 3f))
        val views = FurnitureCameras.presets(bed, people, room)
        assertTrue(views.isNotEmpty())
        for (v in views) assertTrue(room.inside(v.eye), "${v.view} at ${v.eye} is outside the room")
        val moved = views.filter { it.moved }
        assertTrue(moved.isNotEmpty())
        assertTrue(moved.all { it.fov >= FurnitureCameras.FOV })
        // no room at all for any view: none offered
        assertTrue(FurnitureCameras.presets(bed, people, RoomBounds(Vec3(1.5f, 0.5f, 0f), Vec3(2.5f, 1.5f, 1f))).isEmpty())
    }

    @Test fun aWallTheSceneReportsIsNeverLookedThrough() {
        // a wall along x = 3.5: nothing beyond it
        val wall = CameraClearance { eye, _ -> eye.x < 3.5f }
        val views = FurnitureCameras.presets(bed, people, clearance = wall)
        for (v in views) assertTrue(v.eye.x < 3.5f, "${v.view} at ${v.eye}")
        val free = FurnitureCameras.presets(bed, people)
        assertTrue(views.zip(free).any { (a, b) -> a.distance < b.distance || a.pitch > b.pitch } || views.size < free.size)
    }

    @Test fun seatsAndTablesHaveTheirOwnViews() {
        val couch = at(FurnitureKind.COUCH, 0f, 0f, 0f)
        val seated = listOf(Vec3(0.05f, 0.6f, 0.9f), Vec3(0.05f, -0.6f, 0.9f))
        val views = FurnitureCameras.presets(couch, seated)
        assertEquals(listOf(FurnitureView.FRONT, FurnitureView.LEFT, FurnitureView.RIGHT, FurnitureView.BEHIND, FurnitureView.THREE_QUARTER),
            views.map { it.view })
        assertTrue(views.first { it.view == FurnitureView.FRONT }.eye.x > 0.5f)
        assertTrue(views.first { it.view == FurnitureView.BEHIND }.eye.x < -0.5f)
        assertEquals(listOf(FurnitureView.FRONT, FurnitureView.LEFT, FurnitureView.RIGHT, FurnitureView.BEHIND, FurnitureView.THREE_QUARTER),
            FurnitureCameras.presets(at(FurnitureKind.CHAIR), listOf(Vec3(0f, 0f, 0.9f))).map { it.view })
        val table = FurnitureCameras.presets(at(FurnitureKind.TABLE), emptyList())
        assertEquals(listOf(FurnitureView.FRONT, FurnitureView.LEFT, FurnitureView.RIGHT, FurnitureView.THREE_QUARTER), table.map { it.view })
        // nobody given: it looks at the furniture's surface
        assertEquals(0f, table[0].at.x, 1e-4f)
    }

    @Test fun theOrbitNumbersAreSceneCamerasOwn() {
        val at = Vec3(1f, 2f, 0.5f)
        val eye = FurnitureCameras.eyeOf(at, 0.7f, 0.4f, 3f)
        assertEquals(1f + cos(0.4f) * cos(0.7f) * 3f, eye.x, 1e-4f)
        assertEquals(0.5f + sin(0.4f) * 3f, eye.z, 1e-4f)
        val (yaw, pitch, d) = FurnitureCameras.orbitOf(com.logie.wifecraft.story.CameraPose(eye, at, 50f))
        assertEquals(0.7f, yaw, 1e-4f); assertEquals(0.4f, pitch, 1e-4f); assertEquals(3f, d, 1e-4f)
    }
}
