package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The live scene's camera modes (Room, Orbit, Follow), its pan and lift and their limits (simulation/SceneCamera). */
class SceneCameraTest {
    private val room = CameraPose(Vec3(0f, 0f, 3f), Vec3(5f, 0f, 1.5f), 50f)
    private val floor = 0f
    private val dt = 1f / 60f

    private fun eye(c: SceneCamera) = Vec3(c.eyeX, c.eyeY, c.eyeZ)
    private fun at(c: SceneCamera) = Vec3(c.atX, c.atY, c.atZ)
    private fun dist(a: Vec3, b: Vec3) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    private fun camera() = SceneCamera(room).also { it.floorZ = floor; it.update(dt, false, 0f, 0f, 0f) }

    /** Runs [frames] frames and returns the largest move of the eye or the target in one of them. */
    private fun run(c: SceneCamera, frames: Int, focus: Vec3? = null): Float {
        var largest = 0f
        repeat(frames) {
            val e = eye(c); val a = at(c)
            c.update(dt, focus != null, focus?.x ?: 0f, focus?.y ?: 0f, focus?.z ?: 0f)
            largest = maxOf(largest, dist(e, eye(c)), dist(a, at(c)))
        }
        return largest
    }

    @Test fun theCameraButtonCyclesRoomOrbitFollow() {
        assertEquals(CameraMode.ORBIT, CameraMode.ROOM.next(canFollow = true))
        assertEquals(CameraMode.FOLLOW, CameraMode.ORBIT.next(canFollow = true))
        assertEquals(CameraMode.ROOM, CameraMode.ORBIT.next(canFollow = false))
        assertEquals(CameraMode.ROOM, CameraMode.FOLLOW.next(canFollow = true))
    }

    @Test fun orbitStartsExactlyOnTheRoomsViewAndThenTurns() {
        val c = camera()
        c.setMode(CameraMode.ORBIT)
        val moved = run(c, 30)
        assertEquals(CameraMode.ORBIT, c.mode)
        assertTrue(moved < 1e-3f, "nothing moves until a finger does: $moved")
        assertEquals(0f, dist(room.eye, eye(c)), 1e-3f)
        assertEquals(0f, dist(room.at, at(c)), 1e-3f)
        c.dragBy(300f, 0f, 1000f); c.release(0f, 0f, 1000f)
        run(c, 60)
        assertEquals(0f, dist(room.at, at(c)), 1e-3f)                       // it turns round the room's point
        assertEquals(dist(room.eye, room.at), dist(eye(c), at(c)), 1e-2f)   // at the room's distance (beyond the NPC limit)
        assertTrue(dist(room.eye, eye(c)) > 1f)
        assertFalse(c.moving)
    }

    @Test fun theOrbitWrapsWithoutAJump() {
        val c = camera()
        c.setMode(CameraMode.ORBIT)
        run(c, 5)
        var largest = 0f
        // forty half turns one way: the yaw stays within a turn of zero and no frame jumps
        repeat(40) {
            c.dragBy(-1000f, 0f, 1000f)   // pi radians
            largest = maxOf(largest, run(c, 20))
        }
        c.release(0f, 0f, 1000f)
        largest = maxOf(largest, run(c, 60))
        assertTrue(abs(c.orbitYaw) <= PI + 0.01, "yaw ${c.orbitYaw}")
        assertTrue(largest < 2.5f, "a frame moved $largest")
        // forty half turns is twenty whole turns: back where it started
        assertEquals(0f, dist(room.eye, eye(c)), 0.02f)
        assertEquals(0f, OrbitMath.wrap(4f * PI.toFloat()), 1e-4f)
        assertEquals(-PI.toFloat() / 2f, OrbitMath.wrap(3f * PI.toFloat() / 2f), 1e-4f)
        assertEquals(0f, OrbitMath.wrap(Float.NaN))
    }

    @Test fun theLiftMovesTheTargetUpAndDownWithinTheRoom() {
        val c = camera()
        c.setMode(CameraMode.ORBIT)
        run(c, 5)
        c.liftBy(0.6f)
        val first = run(c, 1)
        assertTrue(first < 0.6f, "eased, not a snap: $first")
        run(c, 60)
        assertEquals(room.at.z + 0.6f, c.atZ, 1e-2f)
        assertEquals(room.eye.z + 0.6f, c.eyeZ, 1e-2f)                      // the camera rises with it, pitch unchanged
        // held to about the ceiling and just above the floor
        repeat(100) { c.liftBy(SceneCamera.LIFT_STEP) }
        run(c, 90)
        assertEquals(floor + SceneCamera.MAX_TARGET_H, c.atZ, 1e-2f)
        repeat(100) { c.liftBy(-SceneCamera.LIFT_STEP) }
        run(c, 90)
        assertEquals(floor + SceneCamera.MIN_TARGET_H, c.atZ, 1e-2f)
        assertTrue(c.eyeZ >= floor + SceneCamera.EYE_ABOVE_FLOOR - 1e-3f)
        // with the floor unknown, the lift is held to MAX_LIFT either way of the base; 0 is always allowed
        assertEquals(-SceneCamera.MAX_LIFT, OrbitMath.liftLow(1f, Float.NaN))
        assertEquals(SceneCamera.MAX_LIFT, OrbitMath.liftHigh(1f, Float.NaN))
        assertEquals(0f, OrbitMath.liftHigh(9f, 0f))
        assertEquals(0f, OrbitMath.liftLow(-1f, 0f))
    }

    @Test fun twoFingersPanAlongTheGroundAndLift() {
        val c = camera()
        c.setMode(CameraMode.ORBIT)
        run(c, 5)
        // the room looks along +x from x=0: its right is -y. Fingers right: the room follows them, the target goes left (+y)
        c.pan(100f, 0f, 1000f); c.letGo()
        run(c, 60)
        assertTrue(c.atY > 0.1f, "left of the view: ${c.atY}")
        assertEquals(room.at.x, c.atX, 1e-2f)
        assertEquals(room.at.z, c.atZ, 1e-2f)
        // fingers down: the camera rises
        c.pan(0f, 100f, 1000f); c.letGo()
        run(c, 60)
        assertTrue(c.atZ > room.at.z + 0.1f)
        // far pans are held to MAX_PAN from the base
        repeat(50) { c.pan(1000f, 0f, 1000f) }
        c.letGo()
        run(c, 90)
        assertEquals(SceneCamera.MAX_PAN, c.atY - room.at.y, 0.02f)
        assertEquals(0.5f, OrbitMath.panScale(6f, 8f, 5f), 1e-5f)
        assertEquals(1f, OrbitMath.panScale(1f, 1f, 5f))
    }

    @Test fun orbitAndFollowSwitchWithoutAJump() {
        val c = camera()
        val npc = Vec3(7f, 2f, 1.05f)
        c.setMode(CameraMode.ORBIT)
        run(c, 5, npc)
        c.dragBy(150f, 50f, 1000f); c.liftBy(0.4f); c.release(0f, 0f, 1000f)
        run(c, 60, npc)
        // Orbit to Follow: the target glides to the NPC, the view never jumps
        c.setMode(CameraMode.FOLLOW)
        val toFollow = run(c, 120, npc)
        assertEquals(CameraMode.FOLLOW, c.mode)
        assertEquals(0f, dist(npc, at(c)), 1e-2f)
        assertTrue(toFollow < 0.25f, "a frame moved $toFollow")
        // Follow, then the NPC is let go: the view holds; Follow to Orbit keeps the target exactly where it is
        c.liftBy(0.5f)
        run(c, 60, npc)
        val before = eye(c); val beforeAt = at(c)
        c.setMode(CameraMode.ORBIT)
        val toOrbit = run(c, 60)
        assertTrue(toOrbit < 1e-3f, "Follow to Orbit moved $toOrbit")
        assertEquals(0f, dist(before, eye(c)), 1e-3f)
        assertEquals(0f, dist(beforeAt, at(c)), 1e-3f)
        // and the room's view is eased back to
        c.setMode(CameraMode.ROOM)
        val toRoom = run(c, 30)
        assertEquals(0f, dist(room.eye, eye(c)), 1e-4f)
        assertTrue(toRoom < dist(before, room.eye) / 4f)
    }

    @Test fun followWaitsForSomeoneToFollow() {
        val c = camera()
        c.setMode(CameraMode.FOLLOW)
        run(c, 30)
        assertEquals(CameraMode.ROOM, c.mode)
        assertEquals(0f, dist(room.eye, eye(c)), 1e-4f)
        run(c, 30, Vec3(6f, 1f, 1f))
        assertEquals(CameraMode.FOLLOW, c.mode)
        assertEquals(0f, dist(Vec3(6f, 1f, 1f), at(c)), 1e-2f)
    }
}
