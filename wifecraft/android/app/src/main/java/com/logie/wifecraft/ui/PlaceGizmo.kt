package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class Mode { ORBIT, CAMERA2, MOVE, TURN }

/** Whether the finger at [at] is on the thing [p] as the camera sees it: near its feet, middle or head (people stand about two yards). */
private fun onThing(cam: CameraPose, p: Placement, at: Offset, w: Float, h: Float, reach: Float): Boolean {
    val tall = 1.8f * p.scale.coerceIn(0.3f, 4f)
    return listOf(0f, tall / 2f, tall).any { dz ->
        FloorMath.project(cam, Vec3(p.x, p.y, p.z + dz), w, h)?.let { (sx, sy) -> hypot(sx - at.x, sy - at.y) <= reach } == true
    }
}

/**
 * The stage and its gestures. A tap asks the renderer what is under the finger ([onTap]: the editor selects it). One
 * finger that starts on the selected, unlocked thing drags it on its own floor (FloorMath.dragDelta, snapped), following the
 * ground under it (SceneView.groundAt, at the height it stood above the ground); two fingers then turn and resize it. Anywhere
 * else one finger orbits the camera and two pan and pinch to zoom. While a thing moves [onLive] has it as it is; [onCommit]
 * has it when the fingers lift (one undo step). The Turn and Lift handles beside the selected thing do the same for a turn
 * about its feet and its height.
 */
@Composable
fun PlaceViewport(
    view: SceneView,
    camera: OrbitCamera,
    onCamera: (OrbitCamera) -> Unit,
    selected: Placement?,
    canMove: Boolean,
    snap: Snap,
    onTap: (sx: Float, sy: Float, w: Int, h: Int) -> Unit,
    onLive: (Placement) -> Unit,
    onCommit: (Placement) -> Unit,
    onSize: (w: Int, h: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(0 to 0) }
    val cam by rememberUpdatedState(camera)
    val sel by rememberUpdatedState(selected)
    val movable by rememberUpdatedState(canMove)
    val snapNow by rememberUpdatedState(snap)
    val tap by rememberUpdatedState(onTap)
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    val camChange by rememberUpdatedState(onCamera)
    // each gesture's number: a ground height that comes back after its gesture ended is dropped
    val gesture = remember { intArrayOf(0) }
    val density = LocalDensity.current

    Box(modifier.onSizeChanged { size = it.width to it.height; onSize(it.width, it.height) }) {
        AndroidView({ (view.parent as? ViewGroup)?.removeView(view); view }, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val my = ++gesture[0]
                    val w = this.size.width.toFloat()
                    val h = this.size.height.toFloat()
                    val cam0 = cam
                    val pose = cam0.pose()
                    val s0 = sel
                    val onIt = s0 != null && movable && onThing(pose, s0, down.position, w, h, 72.dp.toPx())
                    var mode = if (onIt) Mode.MOVE else Mode.ORBIT
                    var moved = false
                    var now: Placement? = s0
                    var lift: Float? = null
                    var camNow = cam0
                    if (onIt && s0 != null) view.groundAt(s0.x, s0.y, s0.z + 2f) { g -> if (gesture[0] == my) lift = if (g == null) 0f else s0.z - g }
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            moved = true
                            val a = pressed[0]; val b = pressed[1]
                            val a0 = a.previousPosition.x to a.previousPosition.y; val b0 = b.previousPosition.x to b.previousPosition.y
                            val a1 = a.position.x to a.position.y; val b1 = b.position.x to b.position.y
                            val n = now
                            if ((mode == Mode.MOVE || mode == Mode.TURN) && n != null) {
                                mode = Mode.TURN
                                val turned = n.copy(
                                    yaw = wrapAngle(n.yaw + Gestures.yawOfTwist(Gestures.twist(a0, b0, a1, b1))),
                                    scale = Snap.clampScale(n.scale * Gestures.pinch(a0, b0, a1, b1)),
                                )
                                now = turned
                                live(turned)
                            } else {
                                mode = Mode.CAMERA2
                                val m0 = Gestures.mid(a0, b0); val m1 = Gestures.mid(a1, b1)
                                camNow = camNow.pan(m1.first - m0.first, m1.second - m0.second, h).zoom(Gestures.pinch(a0, b0, a1, b1))
                                camChange(camNow)
                            }
                        } else {
                            val c = pressed[0]
                            if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                            if (moved) when (mode) {
                                Mode.MOVE -> if (s0 != null) {
                                    val d = FloorMath.dragDelta(pose, down.position.x to down.position.y, c.position.x to c.position.y, w, h, s0.z)
                                    val n = now
                                    if (d != null && n != null) {
                                        val x = snapNow.pos(s0.x + d.x); val y = snapNow.pos(s0.y + d.y)
                                        val next = n.copy(x = x, y = y)
                                        now = next
                                        live(next)
                                        view.groundAt(x, y, s0.z + 2f) { g ->
                                            val cur = now
                                            if (gesture[0] == my && cur != null && cur.x == x && cur.y == y) {
                                                val z = followGround(g, lift ?: 0f, cur.z)
                                                if (z != cur.z) { now = cur.copy(z = z); live(cur.copy(z = z)) }
                                            }
                                        }
                                    }
                                }
                                Mode.ORBIT -> {
                                    val dl = c.position - c.previousPosition
                                    camNow = camNow.orbit(dl.x, dl.y, h)
                                    camChange(camNow)
                                }
                                else -> Unit   // one finger left of two: wait for the lift
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    val end = now
                    when {
                        !moved -> tap(down.position.x, down.position.y, w.toInt(), h.toInt())
                        end == null -> Unit
                        mode == Mode.MOVE -> view.groundAt(end.x, end.y, (s0?.z ?: end.z) + 2f) { g ->
                            commit(end.copy(z = followGround(g, lift ?: 0f, end.z)))
                        }
                        mode == Mode.TURN -> commit(end.copy(yaw = snapNow.yaw(end.yaw), scale = snapNow.scale(end.scale)))
                    }
                }
            },
        )

        // the handles beside the selected thing
        val s = selected
        val (w, h) = size
        if (s != null && canMove && w > 0 && h > 0) {
            val pose = camera.pose()
            val base = FloorMath.project(pose, Vec3(s.x, s.y, s.z), w.toFloat(), h.toFloat())
            if (base != null && base.first in 0f..w.toFloat() && base.second in 0f..h.toFloat()) {
                val px = with(density) { 52.dp.toPx() }
                val cur by rememberUpdatedState(s)
                // Turn: right of the feet; dragged round them
                val turnAt = IntOffset((base.first + px * 1.2f).roundToInt().coerceIn(0, (w - px).toInt()), (base.second - px / 2).roundToInt().coerceIn(0, (h - px).toInt()))
                val turnNow by rememberUpdatedState(turnAt)
                val baseNow by rememberUpdatedState(base)
                Handle("Turn", turnAt, Modifier.pointerInput(s.id) {
                    var p = cur
                    detectDragGestures(
                        onDragStart = { p = cur },
                        onDragEnd = { commit(p.copy(yaw = snapNow.yaw(p.yaw))) },
                        onDragCancel = { commit(p) },
                    ) { change, _ ->
                        val o = turnNow.x.toFloat() to turnNow.y.toFloat()
                        val p0 = o.first + change.previousPosition.x to o.second + change.previousPosition.y
                        val p1 = o.first + change.position.x to o.second + change.position.y
                        p = p.copy(yaw = wrapAngle(p.yaw + Gestures.yawAround(baseNow, p0, p1)))
                        live(p)
                        change.consume()
                    }
                })
                // Lift: above the head; dragged up and down
                val liftAt = IntOffset((base.first - px / 2).roundToInt().coerceIn(0, (w - px).toInt()), (base.second - px * 3.2f).roundToInt().coerceIn(0, (h - px).toInt()))
                Handle("Lift", liftAt, Modifier.pointerInput(s.id) {
                    var p = cur
                    detectDragGestures(
                        onDragStart = { p = cur },
                        onDragEnd = { commit(p.copy(z = snapNow.pos(p.z))) },
                        onDragCancel = { commit(p) },
                    ) { change, drag ->
                        val k = FloorMath.worldPerPixelAt(cam.pose(), Vec3(p.x, p.y, p.z), h.toFloat())
                        p = p.copy(z = p.z - drag.y * k)
                        live(p)
                        change.consume()
                    }
                })
            }
        }
    }
}

@Composable
private fun Handle(label: String, at: IntOffset, gestures: Modifier) {
    Box(
        Modifier.offset { at }.size(52.dp).background(Color(0xCC1A1206), CircleShape).border(2.dp, WowColors.gold, CircleShape).then(gestures),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { Text(label, style = frizStyle(12.sp, Color.White)) }
}
