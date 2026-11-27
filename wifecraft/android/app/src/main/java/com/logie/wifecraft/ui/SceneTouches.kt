package com.logie.wifecraft.ui

import androidx.compose.ui.input.pointer.PointerInputScope
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.engine.Hit
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.CameraMode
import com.logie.wifecraft.simulation.BuildGizmo
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.TouchGesture
import com.logie.wifecraft.simulation.TwoFingers
import com.logie.wifecraft.simulation.LifeSim
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

// The touches on the live view (see LiveSceneScreen).

/**
 * The touches on the live view: a drag or pinch orbits (the camera's mode follows: [cameraMode], [setCameraMode]), two fingers
 * pan and twist, a tap picks what is under it ([onTap]), a still finger held opens the radial menu ([onLong], with the life
 * simulation) and in Build mode a finger on a gizmo handle drags it. Moved out of [LiveSceneScreen] as it was.
 */
internal suspend fun PointerInputScope.sceneTouches(
    view: SceneView,
    live: LiveScene,
    build: BuildController,
    life: LifeSim?,
    cameraMode: () -> CameraMode,
    setCameraMode: (CameraMode) -> Unit,
    onLong: (Hit?, Float, Float) -> Unit,
    onTap: (Hit?) -> Unit,
) {
        val slop = 8.dp.toPx()
        val velocity = FloatArray(2)
        // a drag or pinch on the room's view starts the orbit there (it begins exactly on the room's view)
        // a still finger held this long opens the radial menu on what is under it (with the life simulation)
        val longPressOn = life != null
        fun beginOrbit() {
            val next = SceneHud.cameraForTouch(cameraMode())
            if (next != cameraMode()) { setCameraMode(next); live.camera.setMode(next) }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // build mode: a finger down on a gizmo handle drags it along its axis (not the camera)
            build.viewSize(size.width.toFloat(), size.height.toFloat())
            val gizmoGrab = build.on && build.grab(down.position.x, down.position.y, BuildGizmo.TOUCH_DP.dp.toPx())
            val gesture = TouchGesture(slop)
            gesture.down(down.position.x, down.position.y, down.uptimeMillis)
            var last = down.position
            var lastTime = down.uptimeMillis
            var twoFingers = false
            var span = 0f
            var angle = 0f
            var twist = 0f
            // the two fingers' midpoint, and how far it has gone (a pan starts past the slop: a pinch's drift never pans)
            var midX = 0f
            var midY = 0f
            var travel = 0f
            var held = false
            while (true) {
                val wait = LONG_PRESS_MS - (SystemClock.uptimeMillis() - down.uptimeMillis)
                val event = if (longPressOn && !build.on && !held && !twoFingers && gesture.kind == TouchGesture.Kind.PENDING && wait > 0)
                    withTimeoutOrNull(wait) { awaitPointerEvent() } else awaitPointerEvent()
                if (event == null) {
                    held = true
                    val at = last
                    view.pick(at.x, at.y, size.width, size.height) { hit -> onLong(hit, at.x, at.y) }
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                event.changes.firstOrNull()?.let { lastTime = it.uptimeMillis }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val nowSpan = TwoFingers.span(a.x, a.y, b.x, b.y)
                    val nowAngle = TwoFingers.angle(a.x, a.y, b.x, b.y)
                    val nowMidX = (a.x + b.x) / 2f
                    val nowMidY = (a.y + b.y) / 2f
                    if (!twoFingers || span <= 0f) {
                        twoFingers = true
                        gesture.multi()
                    } else {
                        beginOrbit()
                        // pinch zooms; a twist turns the orbit once it is plainly a twist (a pinch's wobble never does);
                        // the two moving together pan along the ground (sideways) and raise or lower the view (up and down)
                        live.camera.pinch(nowSpan / span)
                        twist += TwoFingers.turn(angle, nowAngle)
                        if (abs(twist) > TWIST_DEADZONE) live.camera.twist(TwoFingers.turn(angle, nowAngle))
                        val moveX = nowMidX - midX
                        val moveY = nowMidY - midY
                        travel += hypot(moveX, moveY)
                        if (travel > slop * PAN_SLOPS) live.camera.pan(moveX, moveY, size.height.toFloat())
                        pressed.forEach { it.consume() }
                    }
                    span = nowSpan
                    angle = nowAngle
                    midX = nowMidX
                    midY = nowMidY
                } else {
                    span = 0f   // one finger of a pinch lifted: the next two-finger move starts afresh
                    if (!twoFingers && !held) {
                        val finger = pressed.firstOrNull { it.id == down.id } ?: pressed[0]
                        last = finger.position
                        if (gesture.move(last.x, last.y, finger.uptimeMillis)) {
                            if (gizmoGrab) build.dragTo(last.x, last.y) else {
                                beginOrbit()
                                live.camera.dragBy(gesture.dx, gesture.dy, size.height.toFloat())
                            }
                            finger.consume()
                        }
                    }
                }
            }
            when {
                gizmoGrab -> build.endDrag()
                held -> Unit   // the long press opened the radial menu: no tap, no fling
                gesture.up(lastTime) -> if (!(build.on && build.tapMarker(last.x, last.y, 32.dp.toPx())))
                    view.pick(last.x, last.y, size.width, size.height) { onTap(it) }
                gesture.kind == TouchGesture.Kind.DRAG -> {
                    gesture.velocity(lastTime, velocity)
                    live.camera.release(velocity[0], velocity[1], size.height.toFloat())
                }
                else -> live.camera.letGo()
            }
        }
}

/** Radians two fingers must turn before a pinch turns the orbit too. */
private const val TWIST_DEADZONE = 0.15f
/** Touch slops two fingers' midpoint must travel before they pan (a pinch's drift never does). */
private const val PAN_SLOPS = 2f
/** A still finger held this long on the view opens the radial menu (a tap is shorter: TouchGesture's 500 ms). */
private const val LONG_PRESS_MS = 520L
