// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.logie.v3gold.gesture.Cancellable
import com.logie.v3gold.gesture.GestureConfig
import com.logie.v3gold.gesture.GestureOutput
import com.logie.v3gold.gesture.GestureRecognizer
import com.logie.v3gold.gesture.GestureScheduler
import com.logie.v3gold.gesture.Stick
import com.logie.v3gold.gesture.VitaButton
import org.vita3k.emulator.Emulator
import org.vita3k.emulator.overlay.InputOverlay
import org.vita3k.emulator.overlay.InputOverlay.ControlId
import kotlin.math.roundToInt

/**
 * Gesture controls in place of Vita3K's on-screen buttons. Vita3K's [InputOverlay] still owns the
 * virtual controller and receives every touch on the game screen (see the InputOverlay patch);
 * while gestures are on it draws nothing and hands the touches to [GestureRecognizer], whose
 * output drives the same virtual controller.
 */
class GoldGestures(private val overlay: InputOverlay) {
    private val handler = Handler(Looper.getMainLooper())

    /** Read once and updated by [setEnabled], since it's checked on every touch and every frame. */
    var enabled = GoldConfig(overlay.context).gesturesEnabled
        private set

    private val output = object : GestureOutput {
        override fun setButton(button: VitaButton, pressed: Boolean) {
            overlay.setButton(controlFor(button), pressed)
        }

        override fun setStick(stick: Stick, x: Float, y: Float) {
            val (xAxis, yAxis) = when (stick) {
                Stick.LEFT -> ControlId.axis_left_x to ControlId.axis_left_y
                Stick.RIGHT -> ControlId.axis_right_x to ControlId.axis_right_y
            }
            overlay.setAxis(xAxis, axisValue(x))
            // The controller's y axis points down.
            overlay.setAxis(yAxis, axisValue(-y))
        }

        override fun openMenu() {
            (overlay.context as? Emulator)?.openPauseMenuFromController()
        }

        override fun onHoldRecognized() {
            if (GoldConfig(overlay.context).haptics) {
                overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    private val recognizer: GestureRecognizer

    init {
        val density = overlay.resources.displayMetrics.density
        val scheduler = GestureScheduler { delayMs, action ->
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMs)
            Cancellable { handler.removeCallbacks(runnable) }
        }
        recognizer = GestureRecognizer(
            GestureConfig(
                touchSlopPx = 14 * density,
                stickRadiusPx = 56 * density,
                twoFingerSwipePx = 56 * density,
            ),
            scheduler,
            output
        )
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        recognizer.cancelAll()
        this.enabled = enabled
        GoldConfig(overlay.context).gesturesEnabled = enabled
        overlay.invalidate()
    }

    /** Lets go of every button and centers both sticks. */
    fun releaseAll() = recognizer.cancelAll()

    fun onSizeChanged(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep sideways swipes that start near the screen edges from turning into the system
            // back gesture. Android caps this at 200dp of height per edge.
            val density = overlay.resources.displayMetrics.density
            val edgeWidth = (32 * density).toInt()
            val exclusionHeight = (200 * density).toInt().coerceAtMost(height)
            val top = (height - exclusionHeight) / 2
            overlay.systemGestureExclusionRects = listOf(
                Rect(0, top, edgeWidth, top + exclusionHeight),
                Rect(width - edgeWidth, top, width, top + exclusionHeight)
            )
        }
    }

    /** Every touch on the game screen while gestures are on. Always consumes it. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    recognizer.dropStaleTouches()
                }
                val i = event.actionIndex
                recognizer.pointerDown(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    recognizer.pointerMove(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
                }
                recognizer.movesFinished()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                recognizer.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }

            MotionEvent.ACTION_CANCEL -> recognizer.cancelAll()
        }
        return true
    }

    private fun controlFor(button: VitaButton): Int = when (button) {
        VitaButton.CROSS -> ControlId.a
        VitaButton.CIRCLE -> ControlId.b
        VitaButton.SQUARE -> ControlId.x
        VitaButton.TRIANGLE -> ControlId.y
        VitaButton.SELECT -> ControlId.select
        VitaButton.START -> ControlId.start
        VitaButton.L1 -> ControlId.l1
        VitaButton.R1 -> ControlId.r1
        VitaButton.DPAD_UP -> ControlId.dup
        VitaButton.DPAD_DOWN -> ControlId.ddown
        VitaButton.DPAD_LEFT -> ControlId.dleft
        VitaButton.DPAD_RIGHT -> ControlId.dright
    }

    private fun axisValue(value: Float): Short =
        (value * Short.MAX_VALUE).roundToInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
